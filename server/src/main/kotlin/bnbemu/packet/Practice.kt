package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase
import bnbemu.packet.room.SelectMap
import bnbemu.server.Room
import bnbemu.server.bomber
import bnbemu.server.color
import bnbemu.server.User

// ============================================================================
// 进练习房 (C→S op 75) 回应链 — 按 tst.pcap stream 4 真实线序 (TCP seq) 原样重放
// ============================================================================
// 客户端在练习房界面点"开始"发出 op 75 (body = E2(0) E2(0) E1(0) E1(1) E1(5) E1(15) E4(66)),
// 服务器连发房间设置; 随后客户端自动跟发 297/302 → 服务器回 [108, 106×8];
// 客户端再发 op 92 → 服务器回 [111, 112 SelectMap, 100(77B), 136] 启动游戏;
// 之后对局完全由客户端本地 AI 推进 (服务端只在 C→S op 106 时回 S→C op 139 / 2.5s 后 op 138)。
// ⚠️ 真实服务器另会按地图事件时间轴发 S→C op 172 (MapEventSync, 驱动 "Wave/Game/AirPlane1"),
//    它既不是 "ItemThrow" 也与 AI 行动无关, 见 packet/room/MapEventSync.kt 与 tools/rev_op172.py。
// 工具: tools/extract_op75.py / tools/gen_practice_kotlin.py; 基准 cap/practice_s2c_op*.bin

// ---- op 83 SessionSimpleInfo (6B): E1(0) E1(0) E2(75) E1(15) E1(0) ----
fun SessionSimpleInfo(gameMode: Int = 15) = PKT {
    EncodeBuffer(EN_OP83.copyOfRange(0, 4))
    Encode1(gameMode)
    EncodeBuffer(EN_OP83.takeLast(1).toByteArray())
}

// ---- op 113 (2B): E2(0) ----
fun Unk113() = PKT {
    EncodeBuffer(EN_OP113)
}

// ---- op 100 房间设置版 (78B) / 游戏开始版 (77B) — 两版仅 1 处字节差 ----
fun PracticeRoomInfo(bombStyle: Int = 0, user: User? = null) = PKT { encodePracticePlayerInfo(EN_OP100_ROOM, bombStyle, user) }
fun PracticeStartInfo(bombStyle: Int = 0, user: User? = null) = PKT { encodePracticePlayerInfo(EN_OP100_START, bombStyle, user) }

private fun SendPacketBase.encodePracticePlayerInfo(blob: ByteArray, bombStyle: Int, user: User?) {
    // op100 includes the room member's identity. A captured ID/name prevents a
    // renamed account from identifying itself as chief, disabling room controls.
    val marker = byteArrayOf(0xa5.toByte(), 0x6d) + SESSION_STRING
    val nameOffset = (5 until blob.size - marker.size).first { start ->
        marker.indices.all { blob[start + it] == marker[it] }
    }
    EncodeBuffer(blob.copyOfRange(0, 1))
    Encode4(user?.id ?: CAPTURE_USER_ID)
    // PSlotUserInfo::Read (44A793) decrypts three metadata bytes just
    // before the name. The final byte is color (low five bits); replaying
    // zero here overwrites the color already applied by S112 at launch.
    val color = user?.takeIf { Room.localGameMode in listOf(0, 15) }?.let { owner ->
        Room.slots.firstOrNull { it.user === owner }?.color?.toInt()
    }
    if (color == null) EncodeBuffer(blob.copyOfRange(5, nameOffset)) else {
        EncodeBuffer(blob.copyOfRange(5, nameOffset - 3))
        // 68848D tests bit1 of metadata[0] to elect the local map-script
        // controller. The captured START packet clears it (4 vs room's 6).
        val encodedMeta = ((blob[nameOffset-3].toInt() and 255) shl 8) or (blob[nameOffset-2].toInt() and 255)
        val meta = encodedMeta xor 0xa569
        Encode2(meta or 2)
        Encode1(color.coerceIn(0, 8))
    }
    val token = user?.let { encodeName(it.name) } ?: SESSION_STRING
    Encode2(token.size)
    EncodeBuffer(token)
    val accessoryOffset = blob.size - 11
    val infoStart = nameOffset + marker.size
    // CSimpleUserInfo::Read (4DFCE7): E1,E1,E4,E1,E4,E2(level).
    // The old replay always supplied level 1, so its badge never followed XP.
    if (user == null) EncodeBuffer(blob.copyOfRange(infoStart, accessoryOffset)) else {
        EncodeBuffer(blob.copyOfRange(infoStart, infoStart + 11))
        Encode2(user.level)
        EncodeBuffer(blob.copyOfRange(infoStart + 13, accessoryOffset))
    }
    // PSlotAccessory::Read (0x44A856), followed by CSessionContext setters
    // 0x687455 and 0x687217. Preserve the two-byte prefix and trailing flags.
    EncodeBuffer(blob.copyOfRange(accessoryOffset, accessoryOffset + 2))
    val definitions = bnbemu.server.localAppearanceDefinitions(user)
    encodeAppearanceDefinitions(definitions)
    EncodeBuffer(blob.copyOfRange(blob.size - 7, blob.size - 5))
    Encode4(bombStyle)
    encodeAvatarDefinitions(definitions)
}

// ---- op 108 练习房开局选图回应 (8B): E2(result=0) E2(themeId=0) E4(mapId=172) ----
//    CA.exe CPrepareStage::OnPacket (0x52A949) 的 case 108 = 0x51EFE0 是选图结果处理;
//    这里复用结构化 SelectMap 生成, 与 cap/practice_s2c_op108_10B_0.bin 的 body 逐字节一致。
fun PracticeSelectMap() = SelectMap(result = 0, themeId = 0, mapId = 172)

// ---- op 111 AILevelChange 空体 (1B): E1(0) —— 练习房开局时的 AI 等级重置 ----
fun PracticeAiLevel() = PKT {
    EncodeBuffer(EN_OP111)
}

// ---- op 136 棋盘/开局数据块 (178B) — 抓包回放 ----
// body[8:12] 是 E4 对局时限(秒), 抓包值 180 (×1000ms 存 CSessionContext+0x35F0)。
// timeLimitSec 非空时改写该字段 (练习模式无限时间用); null 保持逐字节回放。
const val PRACTICE_TIME_UNLIMITED_SEC = 2_147_483   // ×1000ms 后仍 < Int.MAX (约 24.8 天)
// EN CStageClearComponent::Init (0x57AF8A) sets a 120-second round clock.
const val MONSTER_TIME_LIMIT_SEC = 120
const val AI_BATTLE_TIME_LIMIT_SEC = 180

fun PracticeBoard(timeLimitSec: Int? = null) = PKT {
    val blob = if (timeLimitSec == null || timeLimitSec == 180) EN_OP136 else
        EN_OP136.copyOf().also {
            val enc = timeLimitSec xor 0x96CA5395.toInt()
            it[8] = ((enc ushr 24) and 0xFF).toByte()
            it[9] = ((enc ushr 16) and 0xFF).toByte()
            it[10] = ((enc ushr 8) and 0xFF).toByte()
            it[11] = (enc and 0xFF).toByte()
        }
    EncodeBuffer(blob)
}

// ---- op 139 已改为按 C→S op 106 字段动态生成 (见 packet/room/SetBomberEvent.kt) ----

// ---- 练习房专用 198 / 423 变体 (与进频道握手推送的 198/423 略异) ----
fun PracticeUserInventory(
    items: List<UserInventoryEntry> = DEFAULT_PRACTICE_INVENTORY_ITEMS
) = PKT {
    Encode1(2)
    repeat(2) {
        Encode4(items.size)
        items.forEach { it.Encode(this) }
    }
}
fun PracticeUnk198() = PracticeUserInventory()
fun PracticeUnk423() = PKT {
    EncodeBuffer(EN_OP423_PRACTICE)
}

internal fun SendPacketBase.encodeAppearanceDefinitions(definitions: List<ItemDefEntry>) {
    val slotDecorations = definitions.filter { it.category == 10 && it.targetCharId in 0..8 && it.skinId in 0..65535 }
    Encode1(slotDecorations.size)
    slotDecorations.forEach { Encode1(it.targetCharId); Encode2(it.skinId) }
    val characterDecorations = definitions.filter { it.category == 12 && it.skinId in -2..7 && it.targetCharId in 0..8 && it.param >= 0 }
    Encode1(characterDecorations.size)
    characterDecorations.forEach { Encode4(it.skinId); Encode4(it.targetCharId); Encode4(it.param) }
}

// PSlotAccessory::Read (0x44A9FB) reads the avatar array AFTER the bomb
// style. Faces/clothes (category 19) are separate from character decorations.
internal fun SendPacketBase.encodeAvatarDefinitions(definitions: List<ItemDefEntry>) {
    val avatars = definitions.filter { it.category == 19 && it.skinId in -2..7 && it.targetCharId in 0..8 && it.param >= 0 }
    Encode1(avatars.size)
    avatars.forEach { Encode4(it.skinId); Encode4(it.targetCharId); Encode4(it.param) }
}
