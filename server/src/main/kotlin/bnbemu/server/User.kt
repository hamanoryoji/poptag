package bnbemu.server

import bnbemu.netty.ClientSocket

class User(
    val c: ClientSocket,
    val id: Int,
    val name: String,
    var level: Int = 1,
    var lucci: Int = 5000,
    var exp: Int = 0,
    val win: Int = 0,
    val draw: Int = 0,
    val lose: Int = 0,
    val motto: String = "永远的泡泡堂",
    val favorite: String = "Bazzi",
    val accountKey: String = name,
) {
    var selectedBomber: Int = 6 // Uni / 小乖

    fun applyAccount(account: LocalShopAccount) {
        lucci = account.lucci
        val progress = LocalEconomy.progress(account.totalExperience)
        level = progress.first; exp = progress.second
    }
    init { applyAccount(LocalShop.store.account(accountKey)) }
}
