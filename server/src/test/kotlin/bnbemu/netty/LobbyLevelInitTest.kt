package bnbemu.netty

import bnbemu.handler.StageInitReq
import bnbemu.packet.decodeName
import bnbemu.server.Room
import bnbemu.server.User
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LobbyLevelInitTest {
    @Test fun enteringAndReenteringLobbyRefreshesUpperPanelFromActualUser() {
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        try {
            val user=User(socket,190001,"lobby-level-init-test")
            socket.users.add(user)
            for(level in listOf(44,62)) {
                user.level=level;user.exp=550
                val request=Unpooled.buffer()
                try { StageInitReq.run { request.handle(socket) } } finally { request.release() }
                var panel: PKT?=null
                while(true) {
                    val packet=channel.readOutbound<PKT>() ?: break
                    if(OpcodeManager.getSendOp(packet::class.java)==254) panel=packet
                }
                assertNotNull(panel)
                val body=Unpooled.buffer()
                try {
                    panel!!.encode(SendPacketBase(body,3))
                    assertEquals(1,body.Decode1)
                    val token=ByteArray(body.Decode2);body.readBytes(token)
                    assertEquals(user.name,decodeName(token))
                    body.Decode1;body.Decode1;body.Decode4;body.Decode1;body.Decode4
                    assertEquals(level,body.Decode2,"Upper LEVEL panel must refresh after a match")
                } finally { body.release() }
            }
        } finally { Room.reset();channel.finishAndReleaseAll() }
    }
}
