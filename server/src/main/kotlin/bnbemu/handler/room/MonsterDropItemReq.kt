package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// EN CGONpcBasicMon::DropItem -> CItemMan::RequestDrop (0x58694C / 0x65DCA0).
// C141 contains three E1 fields, not the legacy region's 7-byte kick packet.
val MonsterDropItemReq = PKTHandler { c ->
    if (readableBytes() != 3) return@PKTHandler
    val itemType = Decode1
    val x = Decode1
    val y = Decode1
    if (Room.localGameMode != 14 || Room.uniqueClients.none { it === c }) return@PKTHandler
    println("[Monster] Drop request type=$itemType x=$x y=$y")
}
