package bnbemu.server

import bnbemu.packet.ShopItemEntry
import java.util.Properties

/** Local balancing rules, not claimed to be the private server's reward rates. */
object LocalEconomy {
    private val config = Properties().apply {
        LocalEconomy::class.java.getResourceAsStream("/data/local-economy.properties")!!.use { load(it) }
    }
    private fun value(key: String) = config.getProperty(key).toInt().also { require(it >= 0) }
    val startingGold = value("starting.gold")
    val walletLimit = 3_000_000
    // National published level table reaches 720. Keep the user's local XP curve.
    val maxLevel = 720
    fun experienceToNext(level: Int) = 100 + 50 * (level.coerceIn(1,maxLevel) - 1)
    fun progress(total: Int): Pair<Int,Int> {
        var remaining = total.coerceAtLeast(0); var level = 1
        while (level < maxLevel && remaining >= experienceToNext(level)) {
            remaining -= experienceToNext(level); level++
        }
        return level to if (level == maxLevel) 0 else remaining
    }
    val maxExperience = (1 until maxLevel).sumOf(::experienceToNext)
    fun totalExperience(level: Int, remainder: Int): Int =
        ((1 until level.coerceIn(1, maxLevel)).sumOf(::experienceToNext).toLong() + remainder.coerceAtLeast(0))
            .coerceAtMost(maxExperience.toLong()).toInt()
    // Native experience bars use thousandths, with 999 as their maximum.
    fun progressRatio(level: Int, remainder: Int): Int =
        if (level >= maxLevel) 999 else experienceRatio(remainder, experienceToNext(level))
    fun experienceRatio(remainder: Int, threshold: Int): Int =
        (remainder.coerceAtLeast(0).toLong() * 1000 / threshold.coerceAtLeast(1)).coerceAtMost(999).toInt()
    data class Reward(val gold: Int = 0, val experience: Int = 0)
    // Gold is the nominal round award; XP remains the actual credited amount for bar animation.
    fun resultReward(requested: Reward, actual: Reward): Reward = requested.copy(experience = actual.experience)
    fun walletLimitNotice(requested: Reward, actual: Reward): String? = when {
        requested.gold<=0 || actual.gold>=requested.gold -> null
        actual.gold==0 -> "金币已达300万上限，本局金币不再增加；经验照常结算。"
        else -> "金币已达300万上限，本局仅增加${actual.gold}金币；经验照常结算。"
    }
    fun reward(mode: Int, won: Boolean, boss: Boolean = false): Reward {
        val key = when {
            mode == 15 -> return Reward() // Unlimited solo practice never earns rewards.
            mode == 14 && won -> if (boss) "monster.boss" else "monster.stage"
            mode == 14 -> "monster.loss"
            mode == 16 || mode == 0 -> if (won) "battle.win" else "battle.loss"
            else -> return Reward()
        }
        return Reward(value("$key.gold"), value("$key.exp"))
    }
    private val prices: Map<Int,Int> by lazy {
        LocalEconomy::class.java.getResourceAsStream("/data/local-shop-prices.tsv")!!.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate {
                val parts = it.split('\t'); parts[0].toInt() to parts[1].toInt().also { n -> require(n > 0) }
            }
        }
    }
    fun price(item: ShopItemEntry): Int {
        val unit = prices[item.itemId] ?: when (item.category) {
        1, 15 -> 5000
        3 -> 80000
        19 -> 120000
        14 -> 180000
        else -> 120000
        }
        return if (item.category in setOf(1,15)) (unit.toLong() * item.count.coerceAtLeast(1)).coerceAtMost(880000).toInt() else unit
    }
    fun priced(item: ShopItemEntry): ShopItemEntry =
        item.copy(tail = item.tail.copy(price1 = price(item), price2 = price(item), f9 = 1))
}
