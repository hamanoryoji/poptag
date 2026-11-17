package bnbemu.netty

import bnbemu.handler.room.SetBomberEventReq
import bnbemu.server.Room
import bnbemu.server.User
import io.netty.buffer.Unpooled
import io.netty.channel.DefaultChannelId
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Limited probe: two server-side channels with preassigned seats, not two playable clients. */
class MultiplayerProbeTest {
    @Test fun twoDistinctConnectionsReceiveEachPlayersNormalBombConfirmation() {
        val sockets=listOf(ClientSocket(),ClientSocket())
        val channels=sockets.map { EmbeddedChannel(DefaultChannelId.newInstance(),it) }
        val mode=Room.localGameMode
        try {
            Room.reset();Room.localGameMode=0
            Room.slots.forEach { it.user=null;it.isAI=false }
            sockets.forEachIndexed { slot,c ->
                Room.slots[slot].user=User(c,200001+slot,"multiplayer-probe-$slot")
            }
            assertEquals(2,Room.uniqueClients.count())
            sockets.forEachIndexed { slot,c ->
                val request=Unpooled.buffer()
                try {
                    SendPacketBase(request,3).apply {
                        Encode2(0);Encode1(slot);Encode4(80+slot)
                        Encode1(2+slot);Encode1(3+slot);Encode1(0);Encode1(2)
                    }
                    SetBomberEventReq.run { request.handle(c) }
                } finally { request.release() }
            }
            for(channel in channels) {
                repeat(2) { slot ->
                    val packet=channel.readOutbound<PKT>()
                    assertNotNull(packet)
                    assertEquals(139,OpcodeManager.getSendOp(packet::class.java))
                    val body=Unpooled.buffer()
                    try {
                        packet.encode(SendPacketBase(body,3))
                        assertEquals(0,body.Decode2);assertEquals(slot,body.Decode1)
                        assertEquals(80+slot,body.Decode4)
                        body.Decode4;body.Decode4
                        assertEquals(2+slot,body.Decode1);assertEquals(3+slot,body.Decode1)
                    } finally { body.release() }
                }
                assertNull(channel.readOutbound<Any>())
            }
            // Drain the ordinary asynchronous bomb registration before resetting shared state.
            val deadline=System.nanoTime()+2_000_000_000L
            while((Room.getBomb(80)==null || Room.getBomb(81)==null) && System.nanoTime()<deadline) Thread.sleep(5)
            assertNotNull(Room.getBomb(80));assertNotNull(Room.getBomb(81))
        } finally {
            Room.reset();Room.localGameMode=mode
            Room.slots.forEach { it.user=null;it.isAI=false }
            channels.forEach { it.finishAndReleaseAll() }
        }
    }
}
