package bnbemu.netty

import bnbemu.handler.room.decodeLocalStartRequest
import bnbemu.packet.*
import bnbemu.server.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcProtocolTest {
    @Test fun resultWaitIsTwoSecondsInClientFrames() {
        val body=Unpooled.buffer()
        try {
            bnbemu.packet.room.GameResult().encode(SendPacketBase(body,3))
            repeat(5) {body.Decode1}
            assertEquals(60,body.Decode4)
        } finally {body.release()}
    }
    @Test fun observedRoomNpcTimerEventIsNotDecodedAsAirplaneAndStaysConnected() {
        assertEquals("NpcEventReq",OpcodeManager.recvOps[156])
        val previous=Room.localGameMode
        val slot=Room.slots[4];val oldAI=slot.isAI
        val channel=EmbeddedChannel(ClientSocket())
        try {
            Room.localGameMode=16;slot.isAI=true
            val buf=Unpooled.buffer()
            SendPacketBase(buf,3).apply { Encode2(156) }
            buf.writeBytes("a56da561a569".toBA())
            channel.writeInbound(buf)
            assertTrue(channel.isActive)
            // 2026-11-17: 首个 op 156 现在会先触发 NPC 实体断言 [105+103]×NPC槽,
            // 然后才是本事件的 270 回显。
            val first=channel.readOutbound<PKT>()
            assertEquals(105,OpcodeManager.getSendOp(first::class.java))
            val second=channel.readOutbound<PKT>()
            assertEquals(103,OpcodeManager.getSendOp(second::class.java))
            val pkt=channel.readOutbound<PKT>()
            assertEquals(270,OpcodeManager.getSendOp(pkt::class.java))
            val body=Unpooled.buffer()
            try {
                pkt.encode(SendPacketBase(body,3))
                val bytes=ByteArray(body.readableBytes()).also {body.readBytes(it)}
                assertArrayEquals("a56da561a569".toBA(),bytes)
            } finally {body.release()}
            val availability=Unpooled.buffer()
            try {
                NpcAvailability().encode(SendPacketBase(availability,3))
                assertEquals(0,availability.Decode1);assertEquals(2,availability.Decode1)
                assertEquals(0,availability.readableBytes())
            } finally {availability.release()}
        } finally {Room.localGameMode=previous;slot.isAI=oldAI;channel.finishAndReleaseAll()}
    }
    @Test fun firstNpcCanBeRemovedAndReopenedInEditableRegion() {
        OpcodeManager.recvOps
        val previous = Room.localGameMode
        val slot = Room.slots[4]
        val oldBomber=slot.bomber;val oldAI=slot.isAI;val oldState=slot.state;val oldDifficulty=slot.npcDifficulty
        val channel=EmbeddedChannel(ClientSocket())
        try {
            Room.localGameMode=16;slot.isAI=true;slot.npcDifficulty=4
            fun click(action: Int) {
                val buf=Unpooled.buffer()
                SendPacketBase(buf,3).apply { Encode2(89);Encode1(0x40 or action) }
                channel.writeInbound(buf)
            }
            click(1)
            assertFalse(slot.isAI)
            assertEquals(FLAG_CLOSED, slot.state.toInt())
            click(0)
            assertTrue(slot.isAI)
            assertEquals(20,slot.bomber.toInt())
            assertEquals(4,slot.npcDifficulty)
            assertTrue(channel.isActive)
        } finally {
            slot.bomber=oldBomber;slot.isAI=oldAI;slot.state=oldState;slot.npcDifficulty=oldDifficulty
            Room.localGameMode=previous;channel.finishAndReleaseAll()
        }
    }
    @Test fun observedNpcStartAndTrainingVariantsDecodeWithoutOverread() {
        val header = "96ca5391a5f95a96ca53915a"
        for (tail in listOf("96ca5395", "5b96ca5395", "5a1234567896ca5395")) {
            val buf = Unpooled.wrappedBuffer((header+tail).toBA())
            try {
                val request = requireNotNull(buf.decodeLocalStartRequest())
                assertEquals(4, request.mapId)
                assertEquals(0, request.eventParam)
                assertEquals(0, buf.readableBytes())
            } finally { buf.release() }
        }
        val truncated = Unpooled.wrappedBuffer((header+"96ca53").toBA())
        try { assertNull(truncated.decodeLocalStartRequest()) } finally { truncated.release() }
    }

    @Test fun realShortNpcStartDispatchDoesNotDisconnect() {
        OpcodeManager.recvOps
        val previous = Room.localGameMode
        val channel = EmbeddedChannel(ClientSocket())
        try {
            Room.localGameMode = 16
            val buf = Unpooled.buffer()
            SendPacketBase(buf,3).apply { Encode2(92) }
            buf.writeBytes("96ca5391a5f95a96ca53915a96ca5395".toBA())
            channel.writeInbound(buf)
            val ops = mutableListOf<Int>()
            while (true) {
                val pkt = channel.readOutbound<PKT>() ?: break
                val opcode = OpcodeManager.getSendOp(pkt::class.java)
                ops.add(opcode)
                if (opcode == 112) {
                    val body = Unpooled.buffer()
                    try {
                        pkt.encode(SendPacketBase(body,3))
                        body.Decode4;body.Decode2;body.DecodeCipherStr;body.Decode4
                        assertEquals(16,body.Decode1,"NPC battle must retain mode16 after game start")
                    } finally {body.release()}
                }
            }
            assertEquals(listOf(111,112,100,136), ops)
            assertTrue(channel.isActive)
        } finally { Room.localGameMode = previous; channel.finishAndReleaseAll() }
    }

    @Test fun npcCharacterAndDifficultyRequestUpdatesBothAndAcknowledgesOwner() {
        OpcodeManager.recvOps
        val previous = Room.localGameMode
        val slot = Room.slots[4]
        val oldBomber = slot.bomber; val oldAI = slot.isAI; val oldDifficulty = slot.npcDifficulty
        val channel = EmbeddedChannel(ClientSocket())
        try {
            Room.localGameMode = 16
            val buf = Unpooled.buffer()
            SendPacketBase(buf,3).apply { Encode2(88); Encode1(0); Encode1(4); Encode1(21); Encode1(4) }
            channel.writeInbound(buf)
            assertEquals(21, slot.bomber.toInt())
            assertEquals(4, slot.npcDifficulty)
            val appearance = channel.readOutbound<PKT>()
            val configuration = channel.readOutbound<PKT>()
            assertEquals(105, OpcodeManager.getSendOp(appearance::class.java))
            assertEquals(103, OpcodeManager.getSendOp(configuration::class.java))
            val encoded = Unpooled.buffer()
            try {
                configuration.encode(SendPacketBase(encoded,3))
                assertEquals(4, encoded.Decode2); assertEquals(1, encoded.Decode1)
                assertEquals(0, encoded.Decode2); assertEquals(4, encoded.Decode2)
            } finally { encoded.release() }
        } finally {
            slot.bomber=oldBomber;slot.isAI=oldAI;slot.npcDifficulty=oldDifficulty
            Room.localGameMode=previous;channel.finishAndReleaseAll()
        }
    }
}
