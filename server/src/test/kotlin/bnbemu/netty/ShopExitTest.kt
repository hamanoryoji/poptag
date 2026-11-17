package bnbemu.netty

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShopExitTest {
    private fun request(state: Int) = Unpooled.buffer().also {
        SendPacketBase(it, 3).apply { Encode2(97); Encode1(state) }
    }

    @Test fun actualExitOpcodeDispatchesLobbyChange() {
        assertEquals("UserStageStateReq", OpcodeManager.recvOps[97])
        assertNotNull(handlerArray[97])
        val channel = EmbeddedChannel(ClientSocket())
        try {
            channel.writeInbound(request(0))
            val response = channel.readOutbound<PKT>()
            assertNotNull(response)
            assertEquals(97, OpcodeManager.getSendOp(response::class.java))
            val body = Unpooled.buffer()
            try {
                response.encode(SendPacketBase(body, 3))
                assertEquals(11, body.Decode2)
                assertEquals(0, body.readableBytes())
            } finally { body.release() }
            assertTrue(channel.isActive)
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun enteringShopDoesNotSendPlayerBack() {
        OpcodeManager.recvOps
        val channel = EmbeddedChannel(ClientSocket())
        try {
            channel.writeInbound(request(1))
            assertNull(channel.readOutbound<Any>())
            assertTrue(channel.isActive)
        } finally { channel.finishAndReleaseAll() }
    }
}
