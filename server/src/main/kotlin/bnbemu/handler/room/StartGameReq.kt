package bnbemu.handler.room

import bnbemu.data.FxdynMapInfo
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.PracticeAiLevel
import bnbemu.packet.PracticeBoard
import bnbemu.packet.PracticeStartInfo
import bnbemu.packet.PRACTICE_TIME_UNLIMITED_SEC
import bnbemu.packet.room.GameStartMapInfo
import bnbemu.server.Room
import kotlin.random.Random

// ============================================================================
// 地图表: 与 S→C op 210 共用唯一事实源 data/FxdynMapInfo.records
// ============================================================================
// 客户端 CDynamicMapMan 只把 flag==0 的记录按数组顺序装入选图数组，其 0-based 下标
// 就是 C→S op 85 / op 92 的 mapId；themeId 等于记录的 sort_order。
// op 210 的下发包体由同一 List<FxdynRecord> 序列化，因此改 JSON 时下发内容与
// mapId 编号必然同步，不会再出现「表已更新、编号没跟上」的漂移。
// 依据: CA.exe 0x52F56C / 0x51C60F / 0x531A09，详见 docs/client/ssd-file.md (3)(5)。
data class MapInfo(val name: String, val themeId: Int)

private const val FALLBACK_MAP_NAME = "Cemetery03"

val mapIdTable: Map<Int, MapInfo> by lazy { loadMapIdTable() }

// FxdynMapInfo 读取/校验失败一律 fail-fast，这里只对结果做完整性检查。
fun loadMapIdTable(): Map<Int, MapInfo> {
    val table = LinkedHashMap<Int, MapInfo>()
    FxdynMapInfo.gameRecords.filter { it.flag == 0 }.forEachIndexed { mapId, r ->
        table[mapId] = MapInfo(r.name, r.sortOrder)
    }
    check(table.isNotEmpty()) { "fxdynmapinfo.json 中没有 flag==0 的记录" }
    return table
}

// ============================================================================
// C→S op 92: 開始遊戲請求 (StartGameReq) — 房間/練習房內點擊"開始"後發出
// ============================================================================
// 逆向確認:
//   CA.exe 0x00528071 (CPrepareStrategyAIBattle::OnButtonClick / 房間開始序列).
//   客戶端在 0x00528076 構造 CSendPacket(0x5C = 92), 並於 0x00531A09 寫入欄位:
//     Encode4: mapId 地圖 ID
//     Encode2: themeId 地图主题 ID
//     Encode1: subtype 子模式 固定0
//     Encode4: roomSelectedMapId (房间界面原始选项 ID：取自 CDynamicMapMan[+0x40]，若房间选的是具体地图，此值与参数 1 mapId 相同。若房间选的是“随机地图”，则此处为该虚拟按钮 ID)
//     Encode1: isCustomMap （0 = 官方内置预置地图；1 = 自定义/外部下载地图）
//     Encode1: fileCheckType （地图文件完整性校验标志。1 = 跳过文件哈希校验（官方内置地图或随机地图）0 = 需比对文件哈希，此时封包体会在后面紧跟一个 Encode4(mapFileCrc32) 传入该地图文件的 CRC32 防作弊。）
//     Encode4: eventParam （活动 / 赛事参数）
//   (17B body, tst.pcap stream4 sq=91).
//   在舊版 TW_5 / CN_12 協議中對應 StartGameReq (TW_5 op 106).
// 伺服器回覆對局啟動序列:
//   [111 PracticeAiLevel, 112 GameStartMapInfo, 100 PracticeStartInfo, 136 PracticeBoard].
// 之後客戶端完成加載發送 149 Ready Ack, 伺服器啟動對局主循環 (Room.startGame).
val StartGameReq = PKTHandler { c ->
    val request = decodeLocalStartRequest() ?: return@PKTHandler
    val mapId = request.mapId
    val themeId = request.themeId

    if (mapId != Room.mapId || themeId != Room.themeId) {
        println("mapId or themeId mismatch")
    }

    // ⚠️ 激战 NPC 实体断言 (2026-11-17): 点"开始"的瞬间按 op 88 成功路径的同形
    // 补发 [105+103]×4 —— 对局加载读取的 NPC 配置必须在此刻是"活"的。进房链里的
    // 103 到达时客户端激战策略尚未就绪 (实测: 配置丢失, NPC 空壳僵直);
    // 用户手动换人/调难度后再开始则正常 —— 此处即复刻该时序, 兜底保证开局前
    // 配置是新的。每房间代只发一次 (assertNpcEntities 幂等)。
    bnbemu.server.assertNpcEntities(c)

    launchLocalMatch(c, mapId, themeId)
}

/** Start a board through the normal prepare-stage handshake. */
fun launchLocalMatch(c: bnbemu.netty.ClientSocket, mapId: Int, themeId: Int, replenish: Boolean = true) {
    // A user-started raid always begins at part one, including after reconnect
    // from a room whose old selected map was an intermediate stage.
    if (Room.localGameMode == 14 && replenish) {
        val name = mapIdTable[mapId]?.name.orEmpty()
        val parts = Regex("(MonPen|Monster|MonSeal|MonTurtle|MonRobot)([0-9]+)").matchEntire(name)
        if (parts != null) {
            val number = parts.groupValues[2].toInt()
            val first = parts.groupValues[1] + (number - (number - 1) % 3).toString().padStart(2, '0')
            val entry = mapIdTable.entries.firstOrNull { it.value.name == first }
            if (entry != null && entry.key != mapId) {
                Room.mapId = entry.key; Room.themeId = entry.value.themeId
                launchLocalMatch(c, entry.key, entry.value.themeId, replenish = true)
                return
            }
        }
    }
    val mapName = mapIdTable[mapId]?.name ?: FALLBACK_MAP_NAME
    println("StartGame. mapId: $mapId, mapName: $mapName")

    // 開局名冊 = 房間裡真正參戰的槽位 (op 89 踢掉的 AI 已被清成 user=null / isAI=false)。
    // 與 Room.resetMatchState 的 hasSeat() 判據必須完全一致, 否則會出現
    // 「服務端按 6 個 AI 判勝負、客戶端卻照 op112 的 8 槽名冊生成 7 個 AI」的錯位。
    // 異常防護: 若房間槽位尚未初始化 (例如非練習房流程), 退回舊的 8 槽名冊,
    // 避免給客戶端發 slotCount=0 導致對局載入失敗。
    val activeSlots = Room.slots.indices.filter { i -> Room.slots[i].user != null || Room.slots[i].isAI }
        .ifEmpty { (0..7).toList() }
    println("StartGame. activeSlots=$activeSlots (AI=${activeSlots.count { Room.slots[it].isAI }})")

    if (replenish) c.users.firstOrNull()?.let { user ->
        bnbemu.server.needleStockPackets().forEach { c send it }   // 先建持有物, op198 热键穿戴的前置
        c send bnbemu.server.localUserInventory(user, practice = true, inGame = true)
    }
    c.send(
        PracticeAiLevel(),
        GameStartMapInfo(
            randomSeed = Random.nextInt(0x7FFFFFFF),
            mapId = mapId,
            themeId = themeId,
            mapName = mapName,
            gameOptions = Room.localGameMode,
            activeSlots = activeSlots,
            timeLimitSec = 0,
            useSelectedColors = Room.localGameMode == 15,
        ),
        PracticeStartInfo(bombStyle = bnbemu.server.localBombStyle(c.users.firstOrNull()), user = c.users.firstOrNull()),
        // Keep the stage-clear clock and server deadline on the same time limit.
        PracticeBoard(timeLimitSec = when (Room.localGameMode) {
            14 -> bnbemu.packet.MONSTER_TIME_LIMIT_SEC
            15 -> 0
            16 -> bnbemu.packet.AI_BATTLE_TIME_LIMIT_SEC
            else -> null
        }),
    )

    // 啟動對局主循環 (onTick: 水泡倒计时与 op 138 爆炸状态同步)
    Room.startGame(loadedMapId = mapId)
}
