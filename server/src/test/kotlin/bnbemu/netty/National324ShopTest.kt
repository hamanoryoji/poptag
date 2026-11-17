package bnbemu.netty

import bnbemu.packet.*
import bnbemu.server.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class National324ShopTest {
    @TempDir lateinit var directory: Path

    @Test fun firstBatchFitsTheVerifiedLegacyCatalogAndHasDistinctPurchasableAppearances() {
        val items = ItemDef.DEFAULT_ITEMS.filter { it.itemId in 950_000_000 until 960_000_000 }
        assertEquals(48, items.size)
        assertEquals(mapOf(12 to 24, 14 to 24), items.groupingBy { it.category }.eachCount())
        assertTrue(ItemDef.buildBlock(ItemDef.DEFAULT_ITEMS).size < 0xFFFFFF)
        val definitions = ItemDef.decodeBlock(ItemDef.buildBlock(ItemDef.DEFAULT_ITEMS)).associateBy { it.itemId }
        val products = ShopCatalog.decodeBlock(ShopCatalog.buildBlock(ShopCatalog.DEFAULT_ITEMS))
        val imported = products.filter { it.itemId in definitions && it.itemId in items.map { d -> d.itemId } }
        // Star-river upgrade style 134 is explicitly retired as a duplicate.
        val visible = items.filterNot { it.category == 14 && it.param == 134 }
        println("[Diag] visible=" + visible.map { it.itemId }.sorted() + " imported=" + imported.map { it.itemId }.sorted())
        println("[Diag] 丢失: " + (visible.map { it.itemId } - imported.map { it.itemId }))
        println("[Diag] 多余: " + (imported.map { it.itemId } - visible.map { it.itemId }))
        assertEquals(visible.size, imported.size)
        assertEquals(visible.size, imported.map { ShopCatalog.appearanceKey(it) }.distinct().size)
        for (item in items) {
            assertEquals(item, definitions[item.itemId])
            assertTrue(item.desc.any { it in '\u3400'..'\u9fff' })
            assertFalse(item.desc.contains('\uFFFD'))
            if (item in visible) assertEquals(item.desc, imported.single { it.itemId == item.itemId }.name)
        }
    }

    @Test fun importedBubbleAndDecorationSurvivePurchaseEquipAndInventoryRebuild() {
        val store = LocalShopStore(directory)
        store.reward("new-catalog-test", "fixture-funds", LocalEconomy.Reward(40000000,0))
        for (category in listOf(12, 14)) {
            val definition = ItemDef.DEFAULT_ITEMS.first { it.itemId in 950_000_000 until 960_000_000 && it.category == category }
            val product = ShopCatalog.DEFAULT_ITEMS.single { it.itemId == definition.itemId }
            assertEquals(PurchaseStatus.OK, store.purchase("new-catalog-test", product).status)
            val slot = if (category == 14) 148 else 144
            store.changeEquipment("new-catalog-test", 0, listOf(slot to definition.itemId))
            val account = LocalShopStore(directory).account("new-catalog-test")
            val inventory = localInventoryEntries(account, 0, emptyList()).single { it.itemId == definition.itemId }
            assertEquals(definition.name, inventory.token)
            assertEquals(slot, inventory.slot)
            if (category == 14) assertEquals(definition.param, localBombStyle(account))
            assertTrue(inventory.expireDate.all { it == 0.toByte() })
        }
    }
}
