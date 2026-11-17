package bnbemu.handler.shop
import bnbemu.netty.*
import bnbemu.packet.*
import bnbemu.server.*
/** Verified EN op267 body: E4(product id), E4(option), E1(payment mode). */
val ShopPurchaseReq = PKTHandler { c ->
    val user = c.users.firstOrNull() ?: return@PKTHandler
    if (readableBytes() != 9) { c send ShopPurchaseResult(false); return@PKTHandler }
    val productId = Decode4
    val option = Decode4
    val payment = Decode1
    // Only the observed Lucci path is supported. Never trust client-side prices.
    if (option != 0 || payment != 0) { c send ShopPurchaseResult(false); return@PKTHandler }
    val product = ShopCatalog.DEFAULT_ITEMS.firstOrNull { it.prodId == productId }
    try {
        val result = LocalShop.store.purchase(user.accountKey, product)
        val success = result.status == PurchaseStatus.OK
        user.lucci = result.account.lucci
        c send ShopPurchaseResult(success)
        if (success) {
            c send UserDynamicInfo(0, user.lucci)
            c send UserItemUpdate(itemId = result.itemId, quantity = result.account.items.getValue(result.itemId))
        }
        println("[LocalPurchase] product=$productId status=${result.status} balance=${user.lucci}")
    } catch (e: java.io.IOException) {
        c send ShopPurchaseResult(false)
        System.err.println("[LocalPurchase] save failed: ${e.javaClass.simpleName}")
    }
}
