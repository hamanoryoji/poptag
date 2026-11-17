package bnbemu.netty

import bnbemu.packet.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShopHomeTest {
    @Test fun nativeHomeSelectorsReceiveSixNewAndFourRecommendedRealProducts() {
        val catalog = ShopCatalog.DEFAULT_ITEMS
        val encoded = ShopCatalog.decodeBlock(ShopCatalog.buildBlock(catalog))
        assertEquals(catalog, encoded)
        val fresh = encoded.filter { it.tail.f7.toInt() in -9999..-1001 }
        val recommended = encoded.filter { it.tail.f7.toInt() <= -10000 }
        assertEquals(6, fresh.size)
        assertEquals(4, recommended.size)
        assertEquals(10, (fresh + recommended).map { it.prodId }.distinct().size)
        for (product in fresh + recommended) {
            assertTrue(product.tail.price1 > 0)
            assertNotNull(ItemDef.DEFAULT_ITEMS.find { it.itemId == product.itemId })
        }
    }

    @Test fun reportedDuplicatesAndMisidentifiedUnlockAreNotForSaleButDefinitionsSurvive() {
        assertFalse(ShopCatalog.DEFAULT_ITEMS.any { it.itemId == 2082 })
        val unlock = ItemDef.DEFAULT_ITEMS.single { it.itemId == 2082 }
        assertEquals(16, unlock.category)
        assertEquals("蓝妹妹使用权", unlock.desc)
        for (style in listOf(121,126)) {
            val ids = ItemDef.DEFAULT_ITEMS.filter { it.category == 14 && it.param == style }.map { it.itemId }
            assertTrue(ids.isNotEmpty())
            assertFalse(ShopCatalog.DEFAULT_ITEMS.any { it.itemId in ids })
        }
        for (style in listOf(119,125)) assertTrue(ShopCatalog.DEFAULT_ITEMS.any { product ->
            ItemDef.DEFAULT_ITEMS.any { it.itemId == product.itemId && it.category == 14 && it.param == style }
        })
    }
}
