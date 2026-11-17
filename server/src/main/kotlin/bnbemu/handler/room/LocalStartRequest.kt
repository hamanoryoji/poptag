package bnbemu.handler.room

import bnbemu.netty.*
import io.netty.buffer.ByteBuf

data class LocalStartRequest(val mapId: Int, val themeId: Int, val eventParam: Int)

fun ByteBuf.decodeLocalStartRequest(): LocalStartRequest? {
    if (readableBytes() !in listOf(16, 17, 21)) return null
    val mapId = Decode4
    val themeId = Decode2
    Decode1 // subtype
    Decode4 // selected map
    Decode1 // custom map
    // NPC battle omits the training-mode file-check flag and optional CRC.
    when (readableBytes()) {
        4 -> Unit
        5 -> if (Decode1 != 1) return null
        9 -> { if (Decode1 != 0) return null; Decode4 }
        else -> return null
    }
    return LocalStartRequest(mapId, themeId, Decode4)
}
