package bnbemu.server

/** Explicit, reversible local testing supplies; never saved as account ownership. */
object LocalTestSupplies {
    val enabled: Boolean get() = java.lang.Boolean.getBoolean("bnbemu.testSupplies")
    val items = listOf(10003 to "Item/ItemWildGinsengC", 10004 to "Item/ShieldC")

    fun appendTo(base: List<Pair<Int, String>>, mode: Int, active: Boolean = enabled): List<Pair<Int, String>> =
        if (active && (mode == 0 || mode in 14..16)) base + items else base
}
