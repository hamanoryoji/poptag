package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// server = CGameSession::OnBombKickThrow
val BombKickThrowReq = PKTHandler { c ->
    // EN 9-byte C107: packed direction/slot, bomb id, source and target
    // row/column, then flight duration. This is not a room-title request.
    if (readableBytes() != 9) return@PKTHandler
    val actionSlot = Decode1
    val bombId = Decode2
    val fromRow = Decode1
    val fromColumn = Decode1
    val targetRow = Decode1
    val targetColumn = Decode1
    val moveDuration = Decode2
    val slot = actionSlot and 15
    if (slot !in Room.slots.indices || actionSlot ushr 4 !in 0..7) return@PKTHandler
    if (Room.slots[slot].user?.c !== c && !(Room.slots[slot].isAI && Room.uniqueClients.any { it === c })) return@PKTHandler
    if (listOf(fromRow, fromColumn, targetRow, targetColumn).any { it !in 0..14 } || moveDuration !in 1..10000) return@PKTHandler
    Room.onBombKickThrow(actionSlot, bombId, fromRow, fromColumn, targetRow, targetColumn, moveDuration)
}
