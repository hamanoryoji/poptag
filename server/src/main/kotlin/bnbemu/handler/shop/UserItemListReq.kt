package bnbemu.handler.shop
import bnbemu.netty.PKTHandler
import bnbemu.packet.UserItemUpdate
import bnbemu.server.LocalShop
val UserItemListReq = PKTHandler { c ->
    c.users.firstOrNull()?.let { user ->
        LocalShop.store.account(user.accountKey).items.forEach { (id, count) ->
            c send UserItemUpdate(itemId = id, quantity = count)
        }
    }
}
