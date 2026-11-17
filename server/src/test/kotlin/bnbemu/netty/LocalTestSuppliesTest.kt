package bnbemu.netty

import bnbemu.server.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalTestSuppliesTest {
    @Test fun disablingTestSuppliesRestoresEveryExistingRoundRule() {
        val base = listOf(10000 to "Item/ItemNiddle")
        for (mode in listOf(0, 14, 15, 16)) {
            assertEquals(base, LocalTestSupplies.appendTo(base, mode, false))
            val active = LocalTestSupplies.appendTo(base, mode, true)
            assertEquals(listOf(10000, 10003, 10004), active.map { it.first })
            assertEquals(base, LocalTestSupplies.appendTo(base, 2, true))
        }
    }

    @Test fun testingOnlyProvisionsOneDisplayedChargeAndDoesNotChangeSavedOwnership() {
        val previous = System.getProperty("bnbemu.testSupplies")
        try {
            System.setProperty("bnbemu.testSupplies", "true")
            for (mode in listOf(0, 14, 15, 16)) {
                val entries = matchSupplyEntries(mode)
                for (id in listOf(10003, 10004)) {
                    assertEquals(2, entries.count { it.itemId == id })
                    assertTrue(entries.filter { it.itemId == id }.all { it.param == 1 })
                }
            }
            System.setProperty("bnbemu.testSupplies", "false")
            assertFalse(matchSupplyEntries(14).any { it.itemId in 10003..10004 })
        } finally {
            if (previous == null) System.clearProperty("bnbemu.testSupplies")
            else System.setProperty("bnbemu.testSupplies", previous)
        }
    }
}
