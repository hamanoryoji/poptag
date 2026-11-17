package bnbemu.server

import bnbemu.netty.ClientSocket
import bnbemu.packet.*
import bnbemu.packet.room.SelectMap

/** Restore the existing room after op155; never recreate its selected NPCs. */
fun sendLocalRoomSnapshot(c: ClientSocket, includeInventory: Boolean = true) {
    val player = Room.slots.firstOrNull { it.user?.c === c }?.user
    c.send(SessionSimpleInfo(Room.localGameMode), UserSimpleInfoResult(),
        SessionInfoResult(), Unk113(), PracticeRoomInfo(localBombStyle(player), player),
        SelectMap(themeId = Room.themeId, mapId = Room.mapId))
    Room.slots.forEachIndexed { index, slot ->
        val occupied = slot.user != null || slot.isAI
        c.send(PracticeSlotReady(index, if (occupied) 2 else 1))
        if (occupied) {
            c.send(PracticeSlot114(index, slot.bomber.toInt()), PracticeSlot104(index))
            c.send(PracticeSetPlayer(index, slot.bomber.toInt()))
            if (slot.isAI) c.send(PracticeSlot103(index, true,
                if (Room.localGameMode == 16) 0 else slot.AILevel,
                if (Room.localGameMode == 16) slot.npcDifficulty else 1))
        }
    }
    if (includeInventory) {
    bnbemu.server.needleStockPackets().forEach { c send it }   // 先建持有物, op198 热键穿戴的前置
    c.send(localUserInventory(player, practice = true))
    }
    if (Room.localGameMode == 16) c.send(NpcAvailability())
    c.send(PracticeUnk423())
}
