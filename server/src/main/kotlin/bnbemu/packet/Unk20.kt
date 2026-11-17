package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.netty.toBA
import bnbemu.server.User

// 進頻道握手推送 #14 (seq=139, 44B) — 與 ResEstimateRTT 同 op 但為大廳推送版, 原樣重放
fun Unk20(character: Int = 6, user: User? = null) = PKT {
    // CStage vtable+0xEC -> 0x4F9485 -> CUserInfoMan::Read(0x6E6109).
    // The captured byte at offset28 resets the selected bomber to Dao/0.
    val data = EN_UNK20_BLOB.copyOf()
    // The replay contains level 1 and zero XP/coins. This is the local-user
    // cache used by the lobby and My Page nickname medal, not just a UI label.
    fun field(offset: Int, width: Int, value: Int, mask: Int) {
        val encoded = value xor mask
        repeat(width) { data[offset + it] = (encoded ushr (8 * (width - 1 - it))).toByte() }
    }
    if (user != null) {
        field(1, 2, user.level, 0xa569)
        field(4, 4, user.exp, 0x96ca5395.toInt())
        field(15, 4, user.lucci, 0x96ca5395.toInt())
    }
    data[28] = ((if (character == 10) 4 else character.coerceIn(0, 7)) xor 0x5a).toByte()
    EncodeBuffer(data)
}

private val EN_UNK20_BLOB = """
    5ba5685a 96ca5395 5a96ca53 955a5a96 ca539596 ca53955a
    96ca5395 5aa5695a 5aa5695a 5a96ca53 955a5a5a
""".toBA()
