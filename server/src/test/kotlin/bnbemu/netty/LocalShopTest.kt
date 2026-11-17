package bnbemu.netty
import bnbemu.server.*
import bnbemu.packet.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import io.netty.buffer.Unpooled

class LocalShopTest {
    @TempDir lateinit var directory: Path
    private fun item(id: Int = 2000, price: Int = 500, count: Int = 1) = ShopItemEntry(id, 14, "Test", itemId=id, count=count, tail=ShopItemTail(price1=price))
    @Test fun purchasePersistsWalletAndInventoryTogether() {
        val store=LocalShopStore(directory)
        val result=store.purchase("Alice",item())
        assertEquals(PurchaseStatus.OK,result.status)
        assertEquals(LocalEconomy.startingGold-500,result.account.lucci)
        assertEquals(1,result.account.items[2000])
        val reopened=LocalShopStore(directory).account("ALICE")
        assertEquals(result.account,reopened)
    }
    @Test fun repeatPurchaseAccumulatesAndChargesEachTime() {
        val store=LocalShopStore(directory)
        store.purchase("a",item(count=6))
        val second=store.purchase("a",item(count=6))
        assertEquals(12,second.account.items[2000]);assertEquals(LocalEconomy.startingGold-1000,second.account.lucci)
    }
    @Test fun unaffordablePurchaseDoesNotMutateWalletOrInventory() {
        val store=LocalShopStore(directory);val before=store.account("a")
        val result=store.purchase("a",item(price=LocalEconomy.walletLimit+1))
        assertEquals(PurchaseStatus.INSUFFICIENT_FUNDS,result.status)
        assertEquals(before,result.account)
        assertFalse(result.account.items.containsKey(2000))
    }
    @Test fun malformedAndCashPurchasesCannotChargeWallet() {
        val store=LocalShopStore(directory);val before=store.account("a")
        assertEquals(PurchaseStatus.INVALID_PRODUCT,store.purchase("a",null).status)
        assertEquals(PurchaseStatus.INVALID_QUANTITY,store.purchase("a",item(),quantity=0).status)
        assertEquals(PurchaseStatus.INVALID_QUANTITY,store.purchase("a",item(),quantity=Int.MAX_VALUE).status)
        assertEquals(PurchaseStatus.UNSUPPORTED_CURRENCY,store.purchase("a",item(price=0)).status)
        assertEquals(PurchaseStatus.UNSUPPORTED_CURRENCY,store.purchase("a",item(),currency=1).status)
        assertEquals(before,store.account("a"))
    }
    @Test fun fullBagBlocksNewItemsButAllowsExistingStacks() {
        val store=LocalShopStore(directory)
        repeat(128) { assertEquals(PurchaseStatus.OK,store.purchase("a",item(id=it+1,price=1)).status) }
        val before=store.account("a")
        assertEquals(PurchaseStatus.BAG_FULL,store.purchase("a",item(id=129,price=1)).status)
        assertEquals(before,store.account("a"))
        assertEquals(PurchaseStatus.OK,store.purchase("a",item(id=1,price=1)).status)
    }
    @Test fun stackOverflowDoesNotCharge() {
        val store=LocalShopStore(directory)
        assertEquals(PurchaseStatus.OK,store.purchase("a",item(count=65535,price=1)).status)
        val before=store.account("a")
        assertEquals(PurchaseStatus.INVALID_QUANTITY,store.purchase("a",item()).status)
        assertEquals(before,store.account("a"))
    }
    @Test fun failedSaveDoesNotChangeMemoryBalanceOrInventory() {
        val store=LocalShopStore(directory.resolve("blocked"));val before=store.account("a")
        Files.writeString(directory.resolve("blocked"),"not a directory")
        assertThrows(java.io.IOException::class.java) { store.purchase("a",item()) }
        assertEquals(before,store.account("a"))
    }
    @Test fun resultPacketAndBindingsMatchPairedClient() {
        assertEquals("ShopPurchaseReq",OpcodeManager.recvOps[267])
        assertNotNull(handlerArray[267])
        assertEquals(202,OpcodeManager.getSendOp(ShopPurchaseResult(true)::class.java))
        val b=Unpooled.buffer();ShopPurchaseResult(true).encode(SendPacketBase(b,3))
        assertEquals(0,b.Decode1);assertEquals(8,b.readableBytes());assertEquals(0L,b.readLong())
        val f=Unpooled.buffer();ShopPurchaseResult(false).encode(SendPacketBase(f,3));assertEquals(1,f.Decode1);assertEquals(0,f.readableBytes())
    }
}
