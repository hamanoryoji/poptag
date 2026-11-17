package bnbemu.netty

import bnbemu.packet.*
import bnbemu.server.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class LocalEconomyTest {
    @TempDir lateinit var directory: Path

    @Test fun allCatalogProductsUseRealGoldWithSeveralPriceBandsAndNoCash() {
        val items = ShopCatalog.decodeBlock(ShopCatalog.buildBlock(ShopCatalog.DEFAULT_ITEMS))
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.tail.price1 in 1..880000 && it.tail.price2 == it.tail.price1 && it.tail.f9 == 1 })
        val definitions = ItemDef.DEFAULT_ITEMS.associateBy { it.itemId }
        val bubbles = items.filter { definitions[it.itemId]?.category == 14 }
        assertTrue(bubbles.map { it.tail.price1 }.distinct().size >= 3)
        assertFalse(items.any { it.tail.price1 == 1000 })
    }

    @Test fun legacyMigrationBacksUpBalanceAndKeepsOwnedAndEquippedItemsExactlyOnce() {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest("legacy".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val file = directory.resolve("$hash.properties")
        Files.writeString(file,"lucci=99999999\nitem.930000045=1\nequip.0.249=930000045\n")
        val migrated = LocalShopStore(directory).account("LEGACY")
        assertEquals(LocalEconomy.startingGold,migrated.lucci)
        assertEquals(1,migrated.items[930000045])
        assertEquals(930000045,migrated.equipment[0]?.get(249))
        assertTrue(Files.readString(directory.resolve("$hash.properties.pre-economy-v1.bak")).contains("lucci=99999999"))
        val store = LocalShopStore(directory)
        store.purchase("legacy",ShopItemEntry(1,14,"test",itemId=2,tail=ShopItemTail(price1=1200)))
        assertEquals(LocalEconomy.startingGold-1200,LocalShopStore(directory).account("legacy").lucci,"Migration must not reset the wallet again")
    }

    @Test fun awardsPersistOnceEvenAfterRestartAndSpendDoesNotResetExperience() {
        val store = LocalShopStore(directory)
        store.purchase("a",ShopItemEntry(9,14,"spend",itemId=9,tail=ShopItemTail(price1=2995000)))
        store.purchase("a",ShopItemEntry(9,14,"spend",itemId=9,tail=ShopItemTail(price1=2995000)))
        val won = LocalEconomy.reward(16,true)
        val first = store.reward("a","match-1",won).account
        assertEquals(105000,first.lucci);assertEquals(50000,first.totalExperience)
        val reopened = LocalShopStore(directory)
        assertEquals(first,reopened.reward("a","match-1",won).account)
        assertEquals(44 to 550,LocalEconomy.progress(first.totalExperience))
        val purchased = reopened.purchase("a",ShopItemEntry(1,14,"test",itemId=2,tail=ShopItemTail(price1=1200))).account
        assertEquals(103800,purchased.lucci);assertEquals(50000,purchased.totalExperience)
        assertEquals(2 to 0,LocalEconomy.progress(100))
        assertEquals(3 to 0,LocalEconomy.progress(250))
        assertEquals(720 to 0,LocalEconomy.progress(Int.MAX_VALUE))
    }

    @Test fun campaignRewardsAndPracticeAreSeparateAndWalletCapsSafely() {
        assertEquals(LocalEconomy.Reward(),LocalEconomy.reward(15,true,true))
        assertEquals(LocalEconomy.Reward(100000,50000),LocalEconomy.reward(14,true,false))
        assertEquals(LocalEconomy.Reward(200000,100000),LocalEconomy.reward(14,true,true))
        val store=LocalShopStore(directory)
        val account=store.reward("a","large",LocalEconomy.Reward(Int.MAX_VALUE,Int.MAX_VALUE)).account
        assertEquals(LocalEconomy.walletLimit,account.lucci)
        assertEquals(LocalEconomy.maxExperience,account.totalExperience)
    }
}
