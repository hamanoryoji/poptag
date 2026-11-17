package bnbemu.handler.room

import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.packet.NpcEvent
import bnbemu.server.Room
import bnbemu.server.assertNpcEntities

// EN op156 is emitted by NPC room timers (0x52D56E) and battle AI events.
// It is unrelated to the legacy TW AirplaneReq that previously disconnected EN.
val NpcEventReq = PKTHandler { c ->
    if (readableBytes() != 6 || Room.localGameMode != 16) return@PKTHandler
    // op 156 = 客户端 NPC 房间定时器启动 = 激战策略已就绪的信号:
    // 此时补发 NPC 实体配置 [105+103] (每房间代一次), 修复"进房 NPC 不动不死"。
    assertNpcEntities(c)
    val slot = Decode2
    val event = Decode2
    val parameter = Decode2
    if (slot !in 4..7 || !Room.slots[slot].isAI) return@PKTHandler
    val response = NpcEvent(slot, event, parameter)
    if (Room.uniqueClients.any()) Room BC response else c send response
}
