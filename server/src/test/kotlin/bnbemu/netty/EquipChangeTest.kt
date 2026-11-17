package bnbemu.netty

import bnbemu.handler.decodeEquipChange
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EquipChangeTest {
    private fun parse(hex: String) = Unpooled.wrappedBuffer(
        hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    ).let { buffer -> try { buffer.decodeEquipChange() } finally { buffer.release() } }

    @Test fun purchaseAutoEquipWithoutTrailer() {
        val change = requireNotNull(parse("58 5A A5 6B A3 A1 A4 FF 38 A5 A1 A4 FF 38"))
        assertEquals(2, change.action)
        assertEquals(listOf(249, 255), change.items.map { it.equipSlot })
        assertEquals(listOf(930000045, 930000045), change.items.map { it.itemId })
        assertNull(change.sessionCount)
        assertNull(change.flag)
    }

    @Test fun myPageTrailerStillSupported() {
        val change = requireNotNull(parse("59 5A A5 68 CE 96 CA 5B B7 5B 5A"))
        assertEquals(3, change.action)
        assertEquals(2082, change.items.single().itemId)
        assertEquals(1, change.sessionCount)
        assertEquals(0, change.flag)
    }

    @Test fun truncatedAndUnexpectedTrailingBytesAreRejected() {
        for (hex in listOf("58", "58 5A A5 6B A3", "58 5A A5 69 00", "58 5A FF FF")) {
            assertNull(parse(hex))
        }
    }
}
