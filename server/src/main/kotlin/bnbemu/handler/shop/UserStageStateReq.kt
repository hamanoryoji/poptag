package bnbemu.handler.shop

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.ChangeStage

// EN op97: 1 enters shop; 0 asks to return to lobby. Map selection uses op85.
val UserStageStateReq = PKTHandler { c ->
    if (readableBytes() != 1) return@PKTHandler
    if (Decode1 == 0) c send ChangeStage(11)
}
