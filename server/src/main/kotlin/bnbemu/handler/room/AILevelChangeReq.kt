package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.PracticeSlot103
import bnbemu.server.Room
import bnbemu.server.bomber
import bnbemu.packet.room.SlotBomber

// ============================================================================
// 设置电脑敌人等级 (AILevelChangeReq)
// EN: C→S op 88 (0x58), 载荷 4B = E1(chief/f1) E1(slotIdx) E1(newLevel) E1(flags)
//     S→C op 103 (PracticeSlot103), 载荷 7B = E2(slotIdx) E1Bool(isAI) E2(newLevel) E2(flags)
// ============================================================================
val AILevelChangeReq = PKTHandler { c ->
    if (readableBytes() != 4) return@PKTHandler
    val chief = Decode1
    val slotIdx = Decode1
    val newLevel = Decode1
    val flags = Decode1
    if (Room.localGameMode in 14..15) {
        if (slotIdx in 4..7) c.send(bnbemu.packet.PracticeSlotReady(slotIdx, 1), PracticeSlot103(slotIdx, false, 0, 0))
        return@PKTHandler
    }

    if (Room.localGameMode == 16) {
        val allowed = (0..13).toList() + (20..23).toList() + if (bnbemu.server.kunPrototypeEnabled()) listOf(25) else emptyList()
        if (slotIdx !in 4..7 || chief !in 0..3 || flags !in 0..4 || newLevel !in allowed) return@PKTHandler
        val selected = bnbemu.server.localNpcCharacter(slotIdx,newLevel)
        val slot = Room.slots[slotIdx]
        slot.isAI = true
        slot.bomber = selected
        slot.npcDifficulty = flags
        println("[NpcDifficulty] slot=$slotIdx character=$newLevel preset=$flags badge=${bnbemu.server.npcDifficultyBadgeLevel(flags)}")
        // In NPC mode op103's third field is the controlling player slot;
        // the last field carries difficulty. op105 changes the NPC character.
        val appearance = SlotBomber(slotIdx, selected)
        val configuration = PracticeSlot103(slotIdx, isAI = true, aiLevel = chief, flag = flags)
        if (Room.uniqueClients.any()) Room.BC(appearance, configuration) else c.send(appearance, configuration)
        return@PKTHandler
    }

    val slot = Room.slots.getOrNull(slotIdx)
    if (slot != null) {
        slot.isAI = true
        slot.AILevel = newLevel
    }

    val pkt = PracticeSlot103(slot = slotIdx, isAI = true, aiLevel = newLevel, flag = 0)
    if (Room.uniqueClients.any()) {
        Room BC pkt
    } else {
        c send pkt
    }
}
