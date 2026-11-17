package bnbemu.handler.room

import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// Local controller releases a network monster (CA.exe 0x64D615, C137).
val MonsterDestroyReq = PKTHandler { c ->
    if (readableBytes() != 6) return@PKTHandler
    val objectId = Decode2
    val parameter = Decode4
    if (Room.uniqueClients.none { it === c }) return@PKTHandler
    Room.onMonsterDestroyed(objectId, parameter)
}
