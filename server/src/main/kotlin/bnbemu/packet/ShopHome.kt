package bnbemu.packet

/** CShopStageNew::RefreshMain (5CA4C8) fills six new and four recommended slots.
 * Native CShopItem predicates 660E66/660E7E read signed tail.f7:
 * recommended <= -10000; new strictly between -10000 and -1000.
 */
internal fun refreshShopHome(items: List<ShopItemEntry>): List<ShopItemEntry> {
    val definitions = ItemDef.DEFAULT_ITEMS.associateBy { it.itemId }
    val newBubbles = items.filter { it.itemId in 950_000_000 until 960_000_000 && definitions[it.itemId]?.category == 14 }.take(3)
    val newDecorations = items.filter { it.itemId in 950_000_000 until 960_000_000 && definitions[it.itemId]?.category == 12 }.take(3)
    val new = (newBubbles + newDecorations).map { it.prodId }
    val recommended = listOf(45,119,125).mapNotNull { style -> items.firstOrNull {
        definitions[it.itemId]?.let { d -> d.category == 14 && d.param == style } == true
    } } + items.filter { it.itemId in 940_000_000 until 950_000_000 && it.prodId !in new }.take(1)
    val rank = (new.mapIndexed { i,id -> id to -2000L+i } +
        recommended.mapIndexed { i,item -> item.prodId to -11000L+i }).toMap()
    return items.map { item ->
        val value = rank[item.prodId] ?: if (item.tail.f7.toInt() < -1000) 0L else item.tail.f7
        item.copy(tail = item.tail.copy(f7 = value and 0xFFFFFFFFL))
    }
}
