package bnbemu.server

import bnbemu.netty.*
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil

data class LocalRoomRequest(val kind: Int, val characters: List<Int>, val option: Int, val extra: Int, val rawHex: String = "") {
    // Captured entry options: 0=human PvP, 14=monster raid,
    // 15=solo training, 16=named NPC battle. Never infer NPCs from kind=1.
    val gameMode: Int get() = if (kind == 0) 15 else option
    fun aiCharacter(slot: Int): Int = if (gameMode == 16) localNpcCharacter(slot,20 + (slot.coerceIn(4, 7) - 4)) else 5
}

/** EN op75: title token, password token, kind, player count, characters, option, extra. */
fun ByteBuf.decodeLocalRoomRequest(): LocalRoomRequest? {
    val start = readerIndex()
    val rawHex = runCatching { ByteBufUtil.hexDump(this, start, readableBytes()) }.getOrElse { "?" }
    val result = decodeLocalRoomRequestInner()
    if (result == null) {
        // 解析失败必须留痕: 客户端新模式入口 (激战等) 的 op 75 变体会在这里现形,
        // 否则 handler 静默返回 → 客户端等不到应答 → 界面完全没反应。
        println("[LocalBattleMode] op75 decode FAILED raw=$rawHex")
    }
    return result
}

private fun ByteBuf.decodeLocalRoomRequestInner(): LocalRoomRequest? {
    val start = readerIndex()
    return try {
        repeat(2) {
            if (readableBytes() < 2) return null
            val length = Decode2
            if (length !in 0..255 || readableBytes() < length) return null
            skipBytes(length)
        }
        if (readableBytes() < 2) return null
        val kind = Decode1
        val count = Decode1
        // kind 0=练习 1=激战; 2+ = 客户端其它模式入口 (怪物模式等) — 先放行并记录原始体,
        // 统一测试时由日志确定各入口的区分字节, 再做精确映射。
        if (kind !in 0..3 || count !in 1..2 || readableBytes() != count + 5) return null
        val characters = List(count) { Decode1 }
        val option = Decode1
        val extra = Decode4
        if (kind != 0 && option !in listOf(0, 14, 15, 16)) return null
        val rawHex = ByteBufUtil.hexDump(this, start, readerIndex() - start)
        LocalRoomRequest(kind, characters, option, extra, rawHex)
    } catch (_: IndexOutOfBoundsException) { null }
}
