package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// Native 54D047: map replacement finished, C131 E1(1).
val MonsterBoardLoadedReq = PKTHandler { c ->
    if (readableBytes() == 1 && Decode1 == 1) Room.onMonsterBoardLoaded(c)
    else skipBytes(readableBytes())
}
