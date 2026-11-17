package bnbemu.netty

import bnbemu.server.*
import bnbemu.packet.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalBattleModeTest {
    private fun parse(hex: String) = Unpooled.wrappedBuffer(hex.toBA()).let {
        try { it.decodeLocalRoomRequest() } finally { it.release() }
    }

    @Test fun capturedTrainingRequestKeepsTrainingMode() {
        val req = requireNotNull(parse("a569a5695a5b5f5596ca53d7"))
        assertEquals(0, req.kind)
        assertEquals(15, req.gameMode)
        assertEquals(listOf(5), req.characters)
        assertEquals(5, req.aiCharacter(1))
    }

    @Test fun capturedNamedNpcRequestSkipsRealTitleRatherThanReadingItAsMode() {
        val req = requireNotNull(parse("a57df9b33dd4e1ea36fbb6be3df4e6ea3ce1a9b332f9a5695b5b5a4a96ca5395"))
        assertEquals(1, req.kind)
        assertEquals(16, req.gameMode)
        assertEquals(listOf(0), req.characters)
        assertEquals((20..23).toList(), (4..7).map { req.aiCharacter(it) })
    }

    @Test fun truncatedRequestsDoNotMutateRoom() {
        val old = Room.localGameMode
        for (hex in listOf("a57d", "a569a5695b5b", "a569a5695b5a5a96ca5395")) assertNull(parse(hex))
        assertEquals(old, Room.localGameMode)
    }

    @Test fun actualCreateDispatchSendsNpcModeAndNpcCharacters() {
        assertNotNull(OpcodeManager.recvOps[75])
        val previous = Room.localGameMode
        val socket = ClientSocket()
        val channel = EmbeddedChannel(socket)
        val oldUsers = Room.slots.map { it.user }
        val oldAi = Room.slots.map { it.isAI }
        socket.users.add(User(socket, 90006, "npc-entry-test"))
        try {
            val buf = Unpooled.buffer()
            SendPacketBase(buf,3).apply {
                Encode2(75); EncodeStr("NPC"); EncodeStr("")
                Encode1(1); Encode1(1); Encode1(0); Encode1(16); Encode4(0)
            }
            channel.writeInbound(buf)
            var mode: Int? = null
            val characters = mutableListOf<Int>()
            while (true) {
                val pkt = channel.readOutbound<PKT>() ?: break
                val body = Unpooled.buffer()
                try {
                    pkt.encode(SendPacketBase(body,3))
                    when (OpcodeManager.getSendOp(pkt::class.java)) {
                        83 -> { body.skipBytes(4); mode = body.Decode1 }
                        105 -> { body.Decode1; characters.add(body.Decode1) }
                    }
                } finally { body.release() }
            }
            assertEquals(16, mode)
            assertEquals(listOf(6) + (20..23).toList(), characters)
            assertEquals(20, Room.localAiCharacter(1))
            assertTrue(channel.isActive)
        } finally {
            Room.reset()
            Room.slots.forEachIndexed { i, slot -> slot.user = oldUsers[i]; slot.isAI = oldAi[i] }
            Room.localGameMode = previous; channel.finishAndReleaseAll()
        }
    }
    @Test fun capturedHumanPvpEntryNeverBecomesNamedNpcBattle() {
        val req = requireNotNull(parse("a565220abf2a660ce8473769b558a5695b5b5c5a96ca5395"))
        assertEquals(1, req.kind)
        assertEquals(0, req.gameMode)
        val socket = ClientSocket()
        val channel = EmbeddedChannel(socket)
        val previous = Room.localGameMode
        val oldUsers = Room.slots.map { it.user }
        val oldAi = Room.slots.map { it.isAI }
        try {
            socket.users.add(User(socket, 90005, "pvp-entry-test"))
            val request = Unpooled.buffer()
            SendPacketBase(request, 3).apply {
                Encode2(75); EncodeStr("PvP"); EncodeStr("")
                Encode1(1); Encode1(1); Encode1(6); Encode1(0); Encode4(0)
            }
            channel.writeInbound(request)
            assertEquals(0, Room.localGameMode)
            assertEquals(1, Room.activeSlotCount)
            assertTrue(Room.slots.none { it.isAI })
            val packets = mutableListOf<PKT>()
            while (true) packets += channel.readOutbound<PKT>() ?: break
            assertFalse(packets.any { OpcodeManager.getSendOp(it::class.java) == 103 })
            val open = Unpooled.buffer()
            SendPacketBase(open, 3).apply { Encode2(89); Encode1(0x40) }
            channel.writeInbound(open)
            assertFalse(Room.slots[4].isAI)
            assertEquals(1, Room.activeSlotCount)
        } finally {
            Room.reset()
            Room.slots.forEachIndexed { i, slot -> slot.user = oldUsers[i]; slot.isAI = oldAi[i] }
            Room.localGameMode = previous; channel.finishAndReleaseAll()
        }
    }

}
