package bnbemu.netty

import bnbemu.handler.room.decodeMapScriptBombRequest
import bnbemu.packet.room.MapScriptBomb
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import bnbemu.server.*
import bnbemu.packet.*
import io.netty.channel.embedded.EmbeddedChannel

class MapScriptBombTest {
    @Test fun actualC133DispatchCreatesAResponseOnTheActivePangLandBoard() {
        OpcodeManager.recvOps
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        val user=User(socket,91005,"sprinkler-real-wire-test")
        val oldMode=Room.localGameMode
        val saved=Room.slots.map { Triple(it.user,it.isAI,it.meta.copyOf()) }
        try {
            Room.reset();Room.localGameMode=15
            Room.slots.forEach { it.user=null;it.isAI=false }
            socket.users.add(user);Room.slots[0].user=user
            val map=bnbemu.handler.room.mapIdTable.entries.first { it.value.name=="PangLand15" }.key
            Room.startGame(loadedMapId=map)
            val incoming=Unpooled.buffer()
            SendPacketBase(incoming,3).Encode2(133)
            incoming.writeBytes(byteArrayOf(0x5b,0xa5.toByte(),0xdb.toByte(),0xa4.toByte(),0x75,0x5b,0xa5.toByte(),0x6a,0x52,0x59))
            channel.writeInbound(incoming)
            val packets=mutableListOf<PKT>()
            for(attempt in 0 until 200) {
                while(true) packets+=channel.readOutbound<PKT>() ?: break
                if(packets.any { OpcodeManager.getSendOp(it::class.java)==139 }) break
                Thread.sleep(5)
            }
            val response=packets.single { OpcodeManager.getSendOp(it::class.java)==139 }
            val b=Unpooled.buffer()
            try {
                response.encode(SendPacketBase(b,3))
                assertSprinklerBody(b,8,3)
            } finally { b.release() }
        } finally {
            Room.reset();Room.localGameMode=oldMode
            Room.slots.forEachIndexed { i,s -> s.user=saved[i].first;s.isAI=saved[i].second;saved[i].third.copyInto(s.meta) }
            channel.finishAndReleaseAll()
        }
    }
    @Test fun actualPangLand15RequestIsAcceptedWithoutGuessingFieldWidths() {
        val b=Unpooled.wrappedBuffer(byteArrayOf(0x5b,0xa5.toByte(),0xdb.toByte(),0xa4.toByte(),0x75,0x5b,0xa5.toByte(),0x6a,0x52,0x59))
        try {
            val request=requireNotNull(b.decodeMapScriptBombRequest())
            assertEquals(284,request.sourceX);assertEquals(178,request.sourceY)
            assertEquals(1,request.power);assertEquals(3,request.state)
            assertEquals(listOf(8 to 3),request.cells)
            assertEquals(0,b.readableBytes())
        } finally { b.release() }
    }
    @Test fun nativeRequestKeepsSourcePixelsAndEveryScriptSelectedTarget() {
        val b=Unpooled.buffer()
        try {
            SendPacketBase(b,3).apply {
                Encode1(2);Encode2(178);Encode2(284);Encode1(1);Encode2(3)
                Encode1(0);Encode1(13);Encode1(12);Encode1(1)
            }
            val request=requireNotNull(b.decodeMapScriptBombRequest())
            assertEquals(284,request.sourceX);assertEquals(178,request.sourceY)
            assertEquals(1,request.power);assertEquals(3,request.state)
            assertEquals(listOf(0 to 13,12 to 1),request.cells)
            assertEquals(0,b.readableBytes())
        } finally { b.release() }
    }

    @Test fun truncatedOrOutOfBoardRequestsNeverProducePartialVolleys() {
        for(row in listOf(0,15)) {
            val b=Unpooled.buffer()
            try {
                SendPacketBase(b,3).apply {
                    Encode1(1);Encode2(178);Encode2(284);Encode1(1);Encode2(3)
                    Encode1(row);if(row==15) Encode1(1)
                }
                assertNull(b.decodeMapScriptBombRequest())
            } finally { b.release() }
        }
    }

    @Test fun sprinklerResponseUsesTheNativeBombBagAndScriptModelState() {
        assertEquals("MapScriptBombReq",OpcodeManager.recvOps[133])
        assertNotNull(handlerArray[133])
        val packet=MapScriptBomb(1,600,284,178,12,13)
        assertEquals(139,OpcodeManager.getSendOp(packet::class.java))
        val b=Unpooled.buffer()
        try {
            packet.encode(SendPacketBase(b,3))
            assertSprinklerBody(b,12,13)
        } finally { b.release() }
    }

    private fun assertSprinklerBody(b:io.netty.buffer.ByteBuf,row:Int,column:Int) {
        assertEquals(24,b.readableBytes())
        assertEquals(0,b.Decode2);assertEquals(8,b.Decode1)
        assertEquals(-9,b.Decode4)
        assertEquals(284,b.Decode4);assertEquals(178,b.Decode4)
        assertEquals(row,b.Decode1);assertEquals(column,b.Decode1)
        assertEquals(0,b.Decode1);assertEquals(1,b.Decode1)
        assertEquals(600,b.Decode2)
        assertEquals(3,b.Decode2,"Native Bomb/Bomb state3 is Sprinkler00, not an item ID")
        assertEquals(0,b.Decode1);assertEquals(0,b.readableBytes())
    }

    @Test fun idsOutsideTheNativeSprinklerTableCannotBeSent() {
        for(id in listOf(599,1000,0x4000,0x6000)) {
            val b=Unpooled.buffer()
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    MapScriptBomb(1,id,284,178,8,3).encode(SendPacketBase(b,3))
                }
                assertEquals(0,b.readableBytes())
            } finally { b.release() }
        }
    }
}
