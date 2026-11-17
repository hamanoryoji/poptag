package bnbemu.packet.room

import bnbemu.netty.PKT

// EN S141 (54BD14): direction/slot, bomb id, source row/column, target row/column.
fun BombKickThrow(actionSlot: Int, bombId: Int, fromRow: Int, fromColumn: Int, targetRow: Int, targetColumn: Int) = PKT {
    Encode1(actionSlot)
    Encode2(bombId)
    Encode1(fromRow); Encode1(fromColumn)
    Encode1(targetRow); Encode1(targetColumn)
}
