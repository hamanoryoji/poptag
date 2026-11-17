package bnbemu.netty

import bnbemu.packet.*
import bnbemu.server.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NationalShopImportTest {
    @TempDir lateinit var directory: Path

    @Test fun importedProductsKeepTheirDefinitionsAndNamesAcrossBothWireCatalogs() {
        val imported = ItemDef.DEFAULT_ITEMS.filter { it.itemId in 940_000_000 until 950_000_000 }
        assertTrue(imported.isNotEmpty())
        val definitions = ItemDef.decodeBlock(ItemDef.buildBlock(ItemDef.DEFAULT_ITEMS)).associateBy { it.itemId }
        val products = ShopCatalog.decodeBlock(ShopCatalog.buildBlock(ShopCatalog.DEFAULT_ITEMS)).associateBy { it.itemId }
        for (item in imported) {
            assertEquals(item, definitions[item.itemId])
            val product = assertNotNull(products[item.itemId]).let { products.getValue(item.itemId) }
            assertEquals(item.desc, product.name)
            assertTrue(product.name.any { it in '\u3400'..'\u9fff' })
            assertEquals(1, product.count)
            assertTrue(product.tail.price1 > 0)
        }
    }

    @Test fun realImportedProductCanBePurchasedAndReloadedWithoutChangingItsResource() {
        val product = ShopCatalog.DEFAULT_ITEMS.first { it.itemId in 940_000_000 until 950_000_000 }
        val store = LocalShopStore(directory)
        store.reward("import-test", "fixture-funds", LocalEconomy.Reward(product.tail.price1,0))
        val result = store.purchase("import-test", product)
        assertEquals(PurchaseStatus.OK, result.status)
        val restored = LocalShopStore(directory).account("import-test")
        assertEquals(1, restored.items[product.itemId])
        val inventory = localInventoryEntries(restored, 0, emptyList()).single()
        val definition = ItemDef.DEFAULT_ITEMS.single { it.itemId == product.itemId }
        assertEquals(definition.name, inventory.token)
        assertTrue(inventory.expireDate.all { it == 0.toByte() })
    }

    @Test fun diyBadgedBubblesAreHiddenButPurchasedDefinitionsRemainUsable() {
        val diy = setOf(89,90,91,92,93,94,95,96,97,99,100,107,108,109,110)
        val definitions = ItemDef.DEFAULT_ITEMS.associateBy { it.itemId }
        assertFalse(ShopCatalog.DEFAULT_ITEMS.any {
            definitions[it.itemId]?.let { d -> d.category == 14 && d.param in diy } == true
        })
        for (style in diy) assertTrue(definitions.values.any { it.category == 14 && it.param == style })
        val owned = LocalShopAccount(items = mapOf(930000089 to 1))
        assertEquals("BombDeco/Bomb089", localInventoryEntries(owned, 0, emptyList()).single().token)
        assertTrue(ShopCatalog.DEFAULT_ITEMS.any { definitions[it.itemId]?.let { d -> d.category == 14 && d.param == 45 } == true })
    }
}
