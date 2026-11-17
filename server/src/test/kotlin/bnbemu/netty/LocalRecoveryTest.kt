package bnbemu.netty

import bnbemu.packet.*
import bnbemu.server.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalRecoveryTest {
    @Test fun battleNeedleUseDoesNotRefillAndEveryNewRoundGetsOneCharge() {
        val socket = ClientSocket()
        val channel = EmbeddedChannel(socket)
        val mode = Room.localGameMode
        try {
            Room.localGameMode = 16
            repeat(2) {
                val stock = Unpooled.buffer()
                try {
                    needleStockPackets().single().encode(SendPacketBase(stock, 3))
                    assertEquals(0, stock.Decode1)
                    assertEquals(10000, stock.Decode4)
                    assertEquals(1, stock.Decode4, "Each new round provisions one needle")
                } finally { stock.release() }
                val used = Unpooled.buffer()
                SendPacketBase(used, 3).apply {
                    Encode2(154); Encode2(0); Encode2(0); Encode2(0); Encode2(0)
                }
                channel.writeInbound(used)
                assertNull(channel.readOutbound<PKT>(), "Using a needle must not refill it within the round")
            }
            val lobbyGrid = Unpooled.buffer()
            try {
                UserInventoryInfo().encode(SendPacketBase(lobbyGrid, 3))
                lobbyGrid.Decode1; lobbyGrid.Decode4; lobbyGrid.skipBytes(8)
                assertEquals(0, lobbyGrid.Decode1, "Match needles must not appear as permanent bag-grid items")
            } finally { lobbyGrid.release() }
        } finally { Room.localGameMode = mode; channel.finishAndReleaseAll() }
    }

    @Test fun chineseNamesRoundTripInTheNativeClientEncoding() {
        for (name in listOf("潜在游鱼", "自救针", "大厅")) {
            val body = Unpooled.buffer()
            try {
                SendPacketBase(body, 3).EncodeCipherStr(name)
                assertEquals(name.length * 2, body.getUnsignedShort(0).toInt() xor 0xa569)
                assertEquals(name, body.DecodeCipherStr)
            } finally { body.release() }
        }
    }

    @Test fun localizedSystemTableHasValidEnvelopeAndChineseValues() {
        val body = Unpooled.buffer()
        try {
            FirstInfoInit().encode(SendPacketBase(body, 3))
            assertEquals(1, body.Decode1)
            val length = body.Decode4
            assertTrue(length < 65536, "Keep the legacy startup overlay bounded")
            assertEquals(length, body.readableBytes())
            val encoded = ByteArray(length).also { body.readBytes(it) }
            val wire = Unpooled.buffer()
            try {
                wire.writeBytes(encoded)
                val decoded = java.nio.ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                while (wire.readableBytes() >= 4) decoded.putInt(wire.readInt() xor 0x96ca5395.toInt())
                if (wire.readableBytes() >= 2) decoded.putShort((wire.readUnsignedShort().toInt() xor 0xa569).toShort())
                if (wire.isReadable) decoded.put((wire.readUnsignedByte().toInt() xor 0x5a).toByte())
                val plain = decoded.array()
                assertEquals(0x18, plain[0].toInt())
                val envelopeLength = (plain[1].toInt() and 255) or
                    ((plain[2].toInt() and 255) shl 8) or ((plain[3].toInt() and 255) shl 16)
                assertEquals(plain.size - 5, envelopeLength)
                val input = java.nio.ByteBuffer.wrap(plain).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                input.position(6)
                val entries = mutableMapOf<String, String>()
                fun string(): String = String(ByteArray(input.int).also { input.get(it) }, bnbemu.ACP)
                repeat(input.short.toInt() and 65535) {
                    val key = string()
                    entries[key] = string()
                }
                assertFalse(input.hasRemaining())
                assertEquals("大厅", entries["lobby"])
                assertEquals("自救针", entries["needle"])
                assertTrue(entries.size >= 100)
            } finally { wire.release() }
        } finally { body.release() }
    }

    @Test fun renamedAccountOwnsRoomAndStartMemberRecords() {
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        try {
            val user = User(cs, 23456, "潜在游鱼")
            for ((original, packet) in listOf(
                EN_OP100_ROOM to PracticeRoomInfo(45, user),
                EN_OP100_START to PracticeStartInfo(45, user)
            )) {
                val body = Unpooled.buffer()
                try {
                    packet.encode(SendPacketBase(body, 3))
                    body.Decode1
                    assertEquals(user.id, body.Decode4)
                    val originalName = original.indices.first { i ->
                        i + 6 <= original.size && original.copyOfRange(i, i + 6)
                            .contentEquals(byteArrayOf(0xa5.toByte(), 0x6d) + SESSION_STRING)
                    }
                    body.readerIndex(originalName)
                    assertEquals("潜在游鱼", body.DecodeCipherStr)
                    body.readerIndex(body.writerIndex() - 5)
                    assertEquals(45, body.Decode4)
                } finally { body.release() }
            }
        } finally { ch.finishAndReleaseAll() }
    }

    @Test fun needleUsesTheActualPlayerHotbarCategoryAndOwnedBagSlot() {
        assertEquals(1, ItemDef.DEFAULT_ITEMS.single { it.itemId == 10000 }.category)
        val body = Unpooled.buffer()
        try {
            needleStockPackets().single().encode(SendPacketBase(body, 3))
            assertEquals(0, body.Decode1)
            assertEquals(10000, body.Decode4)
            assertEquals(1, body.Decode4, "2026-11-08 用户要求: 道具栏携带 1 根 (可拾取地图掉落针)")
            body.Decode4
            assertEquals(255, body.Decode1, "Stock must enter the owned bag before equipping type 8")
        } finally { body.release() }
    }

    @Test fun expandedCatalogKeepsItsFullThreeByteLengthAndRevision() {
        val base = ShopCatalog.loadBaseItems()
        val expanded = ShopCatalog.DEFAULT_ITEMS
        assertEquals(expanded.size, expanded.map(ShopCatalog::appearanceKey).distinct().size)
        val large = expanded + expanded.map { it.copy(prodId = it.prodId + 100000) }
        // 明文块 (compress=false) 验证 24 位长度字段
        val block = ShopCatalog.buildBlock(large, compress = false)
        assertTrue(block.size > 65535)
        val length = (block[1].toInt() and 255) or ((block[2].toInt() and 255) shl 8) or
            ((block[3].toInt() and 255) shl 16)
        assertEquals(block.size - 5, length)
        assertEquals(large, ShopCatalog.decodeBlock(block))
        // 压缩块 (compress=1): 客户端 SSD 读取链原生支持, 回环也要通过
        val compressedBlock = ShopCatalog.buildBlock(large)
        assertEquals(large, ShopCatalog.decodeBlock(compressedBlock))
        val actual = ShopCatalog.buildBlock(expanded)
        assertEquals(ShopCatalog.adler32Zero(actual, 5, actual.size - 5), ShopCatalog.CATALOG_HASH)
        assertTrue(expanded.any { ShopCatalog.appearanceKey(it) == "bubble:45" })
    }
}
