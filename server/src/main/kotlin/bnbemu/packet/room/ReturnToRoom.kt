package bnbemu.packet.room

import bnbemu.netty.PKT

// EN op155 -> CGameStage::OnReturn (0x54FC14): E1(chief slot).
// Clears round-only slot flags and changes to prepare stage 12 (0x54FFAC).
fun ReturnToRoom(chiefSlot: Int = 0) = PKT { Encode1(chiefSlot) }
