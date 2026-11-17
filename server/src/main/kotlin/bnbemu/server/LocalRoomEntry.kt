package bnbemu.server

import bnbemu.data.FxdynMapInfo
import bnbemu.netty.ClientSocket
import bnbemu.packet.PracticeRoomInfo
import bnbemu.packet.PracticeSetPlayer
import bnbemu.packet.PracticeSlot103
import bnbemu.packet.PracticeSlot104
import bnbemu.packet.PracticeSlot114
import bnbemu.packet.PracticeSlotReady
import bnbemu.packet.PracticeUnk423
import bnbemu.packet.RoomEntry
import bnbemu.packet.RoomList
import bnbemu.packet.SessionInfoResult
import bnbemu.packet.SessionSimpleInfo
import bnbemu.packet.Unk113
import bnbemu.packet.UserSimpleInfoResult
import bnbemu.packet.room.SelectMap

// ============================================================================
// 本地房间进入链 (op 75 CreatePracticeSessionReq / op 77 JoinFreeSessionReq 共用)
// ============================================================================
// 房间模型与作者原版完全一致 (2026-11-17 恢复, 不再做 8 槽全员 AI 的改造):
//   · mode 15 (练习): 单人, 槽 1..7 关闭
//   · mode 14 (怪物): 单人, 槽 1..7 关闭 (怪物由地图脚本在对局内生成)
//   · mode 16 (激战): 槽 4..7 = 4 个电脑 NPC
// 与原版的唯一差别: 房间列表 (op 87) 必须携带本房条目 (房号 75) ——
// 客户端 op 83 处理器 (0x506513) 用房号到 op 87 填充的房间缓存里找房
// (0x505123), 找不到 → 「整段跳过」→ 玩家注册断链 → 30 tick 后客户端自动发
// C→S op 84 并把 [stage+0x2F78] 置 4 → 选角/换图/开始/商城全灰。
// 原版发 RoomList(emptyList()) → 缓存恒空 → 这就是「按钮全灰」的根因。
// op 87 条目的线格式见 packet/RoomList.kt (房号在 stream 首个 W, 2026-11-17
// 反汇编 0x50FF27/0x504E9B 钉死; 旧 AI 版把房号错放在 E4 导致注册永远失败)。
// ============================================================================
// 激战 NPC 实体断言 (2026-11-17)
// ============================================================================
// 反汇编客户端 op 103 处理器 (0x52B39A) 发现它只是"更新器": 0x6861C8 查 AI 实体
// 指针 (ctx+0x1E4+slot*0xC), 实体不存在时 (edi=0) 只写难度+刷 UI, 不创建实体。
// 进房链里的 103 到达时激战策略的每槽上下文尚未就绪 → NPC 配置丢失 →
// 对局内的 NPC 是空壳: 不动、不死、不掉道具 (用户实测)。
// 之后任何客户端主动触发的 103 (op 88 换人/调难度、op 89 踢出再请回) 都发生在
// 策略就绪后 → 有效 —— 这解释了"替换/调等级/踢了再请回后 NPC 就正常"。
// 修法: op 156 (NpcEventReq, NPC 房间定时器) 是"客户端 NPC 系统已就绪"的信号,
// 第一次收到时按 op 88 成功路径的同形补发全部 NPC 的 [op 105 + op 103]。
// 每个房间代 (roomGeneration) 只断言一次, 防止 156 定时器触发循环。
fun assertNpcEntities(c: ClientSocket) {
    if (Room.localGameMode != 16) return
    if (Room.npcAssertedGeneration == Room.currentGeneration()) return
    Room.npcAssertedGeneration = Room.currentGeneration()
    println("[NpcAssert] 客户端就绪 (op 156), 补发激战 NPC 实体配置 [105+103]")
    for (slot in 4..7) {
        val s = Room.slots[slot]
        if (!s.isAI) continue
        val bomber = s.bomber.toInt()
        val assert = listOf(
            bnbemu.packet.room.SlotBomber(slot, bomber),
            PracticeSlot103(slot, isAI = true, aiLevel = 0, flag = s.npcDifficulty),
        )
        if (Room.uniqueClients.any()) Room.BC(assert[0], assert[1]) else c.send(assert[0], assert[1])
    }
}

fun startLocalRoom(c: ClientSocket, request: LocalRoomRequest, sendSessionInfo: Boolean = true) {
    // A new room must also stop the previous match and clear its running flag.
    Room.reset()
    Room.localGameMode = request.gameMode
    // Each mode starts on an appropriate concrete map, not the captured room's
    // unrelated map index (and never a random-map pseudo entry).
    val initialName = when (request.gameMode) {
        14 -> "MonPen01"
        15 -> "Patrit14" // 海盗 14 is the user's preferred training default.
        else -> "Cemetery04"
    }
    FxdynMapInfo.records.filter { it.flag == 0 }.withIndex()
        .firstOrNull { it.value.name == initialName }?.let {
            Room.mapId = it.index
            Room.themeId = it.value.sortOrder
        }
    println("[LocalBattleMode] kind=${request.kind} mode=${request.gameMode} chars=${request.characters} option=${request.option} extra=${request.extra} raw=${request.rawHex}")

    // 玩家 (op 7 已把 c.users[0] 填好) 放 slot 0 (leader); 其余槽位按原版模型。
    val player = c.users.firstOrNull()
    if (player != null) {
        Room.slots[0].apply {
            this.user = player
            isAI = false
            state = FLAG_CHIEF
            bomber = player.selectedBomber
            color = 0
        }
        for (i in 1..7) {
            Room.slots[i].apply {
                this.user = null
                isAI = request.gameMode == 16 && i >= 4
                bomber = request.aiCharacter(i)
                color = 7
                AILevel = i
                npcDifficulty = 4
                state = if (isAI || request.gameMode == 0) 0 else FLAG_CLOSED
            }
        }
    }

    val head = buildList {
        // 房间列表必须含本房条目: op 83 / op 84 成功分支都用房号 (75) 到
        // op 87 填充的客户端房间缓存里找房 (0x505123), 找不到会「整段跳过」。
        add(RoomList(listOf(RoomEntry.practice(number = 75))))
        if (sendSessionInfo) add(SessionSimpleInfo(gameMode = request.gameMode))
        add(UserSimpleInfoResult())
        add(SessionInfoResult())
        add(Unk113())
        add(PracticeRoomInfo(bombStyle = localBombStyle(player), user = player))
        add(SelectMap(themeId = Room.themeId, mapId = Room.mapId))
    }
    c.send(*head.toTypedArray())

    // 106×8 (sq=208~229)
    repeat(8) { slot ->
        c.send(PracticeSlotReady(slot, if (slot == 0 || Room.slots[slot].isAI) 2 else if (request.gameMode == 0) 0 else 1))
    }
    // 每槽交错发送 [114, 104, 105], 槽间补「下一槽」的 103 (槽 7 无 103),
    // 与官方线序一致; 激战 (mode 16) 只有槽 4..7 是电脑位。
    repeat(8) { slot ->
        if (slot != 0 && !Room.slots[slot].isAI) {
            return@repeat   // 练习/怪物模式: 仅玩家槽位, 其余关闭
        }
        val bomber = if (slot == 0) player?.selectedBomber?.toInt() ?: 6 else request.aiCharacter(slot)
        c.send(
            PracticeSlot114(slot, bomber),
            PracticeSlot104(slot),
        )
        // The NPC strategy refreshes labels on op103, after reading the role.
        c.send(PracticeSetPlayer(slot, bomber))
        if (request.gameMode == 16 && slot >= 4)
            // ⚠️ refSlot (aiLevel 形参位置) 必须指向「有玩家信息」的槽位 —— 玩家信息
            // 只有 op 100 (SetPlayer) 会写, 即房主的 0 号位。指回 NPC 自己的槽位时,
            // 客户端 GetPlayerInfo_Plus32 查不到数据 → NPC 实例化成空壳:
            // 不动、不死、不掉道具 (2026-11-17 用户实测); 手动替换 NPC 后正常,
            // 因为 op 88 应答里的 refSlot 指向房主位 —— 本行即按此修正为 0。
            c.send(PracticeSlot103(slot, isAI = true, aiLevel = 0, flag = 4))
    }
    bnbemu.server.needleStockPackets().forEach { c send it }   // 先建持有物, op198 热键穿戴的前置
    c.send(localUserInventory(player, practice = true))
    if (request.gameMode == 16) c send bnbemu.packet.NpcAvailability()
    c.send(PracticeUnk423())
}
