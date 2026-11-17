package bnbemu.server

import bnbemu.packet.ShopItemEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.Properties

/** Offline-only Lucci wallet. Every purchase commits wallet and inventory together. */

data class LocalShopAccount(
    val lucci: Int = LocalEconomy.startingGold, val items: Map<Int, Int> = emptyMap(),
    val equipment: Map<Int, Map<Int, Int>> = emptyMap(), // inventory -> equipment slot -> item ID
    val totalExperience: Int = 0,
    val rewardReceipts: List<String> = emptyList()
)
enum class PurchaseStatus { OK, INVALID_PRODUCT, INVALID_QUANTITY, UNSUPPORTED_CURRENCY, INSUFFICIENT_FUNDS, BAG_FULL }
data class PurchaseOutcome(val status: PurchaseStatus, val account: LocalShopAccount, val itemId: Int = 0, val added: Int = 0)

class LocalShopStore(private val directory: Path) {
    companion object { const val CAPACITY = 128 }
    private val accounts = mutableMapOf<String, LocalShopAccount>()
    private fun key(name: String) = name.lowercase(java.util.Locale.ROOT)
    private fun file(name: String): Path {
        val hash = MessageDigest.getInstance("SHA-256").digest(key(name).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return directory.resolve("$hash.properties")
    }
    @Synchronized fun account(name: String): LocalShopAccount = accounts.getOrPut(key(name)) {
        val path = file(name)
        if (!Files.exists(path)) LocalShopAccount() else {
            val p = Properties().apply { Files.newInputStream(path).use { load(it) } }
            val money = p.getProperty("lucci").toInt().also { require(it >= 0) }
            val items = p.stringPropertyNames().filter { it.startsWith("item.") }.associate {
                val id = it.removePrefix("item.").toInt().also { n -> require(n > 0) }
                val count = p.getProperty(it).toInt().also { n -> require(n in 1..65535) }
                id to count
            }
            require(items.size <= CAPACITY)
            val equipment = (0..1).associateWith { inventory ->
                p.stringPropertyNames().filter { it.startsWith("equip.$inventory.") }.associate {
                    val slot = it.substringAfterLast('.').toInt().also { n -> require(n in 0..254) }
                    val id = p.getProperty(it).toInt().also { n -> require(n in items) }
                    slot to id
                }
            }.filterValues { it.isNotEmpty() }
            val legacy = p.getProperty("economy.version") != "1"
            val value = LocalShopAccount(if (legacy) LocalEconomy.startingGold else money, items, equipment,
                p.getProperty("experience", "0").toInt().also { require(it in 0..LocalEconomy.maxExperience) },
                p.getProperty("reward.receipts", "").split(',').filter { it.isNotBlank() }.takeLast(128))
            if (legacy) {
                val backup = path.resolveSibling(path.fileName.toString() + ".pre-economy-v1.bak")
                if (!Files.exists(backup)) Files.copy(path, backup)
                commit(name, value)
            }
            value
        }
    }
    private fun commit(name: String, value: LocalShopAccount) {
        Files.createDirectories(directory)
        val destination = file(name)
        val temporary = Files.createTempFile(directory, "purchase-", ".tmp")
        try {
            val p = Properties().apply {
                setProperty("lucci", value.lucci.toString())
                setProperty("economy.version", "1")
                setProperty("experience", value.totalExperience.toString())
                setProperty("reward.receipts", value.rewardReceipts.joinToString(","))
                value.items.forEach { (id, count) -> setProperty("item.$id", count.toString()) }
                value.equipment.forEach { (inventory, slots) ->
                    slots.forEach { (slot, id) -> setProperty("equip.$inventory.$slot", id.toString()) }
                }
            }
            Files.newOutputStream(temporary).use { p.store(it, "Offline PopTag wallet and inventory") }
            try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
            accounts[key(name)] = value
        } finally { Files.deleteIfExists(temporary) }
    }
    @Synchronized fun purchase(name: String, product: ShopItemEntry?, quantity: Int = 1, currency: Int = 0): PurchaseOutcome {
        val old = account(name)
        fun fail(s: PurchaseStatus) = PurchaseOutcome(s, old)
        if (product == null || product.itemId <= 0 || product.count <= 0) return fail(PurchaseStatus.INVALID_PRODUCT)
        if (quantity !in 1..999) return fail(PurchaseStatus.INVALID_QUANTITY)
        if (currency != 0 || product.tail.price1 <= 0) return fail(PurchaseStatus.UNSUPPORTED_CURRENCY)
        val cost = product.tail.price1.toLong() * quantity
        if (cost > old.lucci) return fail(PurchaseStatus.INSUFFICIENT_FUNDS)
        val added = product.count.toLong() * quantity
        val current = old.items[product.itemId] ?: 0
        if (added > 65535 || current + added > 65535) return fail(PurchaseStatus.INVALID_QUANTITY)
        if (current == 0 && old.items.size >= CAPACITY) return fail(PurchaseStatus.BAG_FULL)
        val next = old.copy(lucci = old.lucci - cost.toInt(), items = old.items + (product.itemId to (current + added.toInt())))
        commit(name, next)
        return PurchaseOutcome(PurchaseStatus.OK, next, product.itemId, added.toInt())
    }

    @Synchronized fun reward(name: String, receipt: String, reward: LocalEconomy.Reward): PurchaseOutcome {
        require(receipt.matches(Regex("[A-Za-z0-9-]{1,80}")))
        require(reward.gold >= 0 && reward.experience >= 0)
        val old = account(name)
        if (receipt in old.rewardReceipts) return PurchaseOutcome(PurchaseStatus.OK, old)
        val next = old.copy(
            lucci = (old.lucci.toLong() + reward.gold).coerceAtMost(LocalEconomy.walletLimit.toLong()).toInt(),
            totalExperience = (old.totalExperience.toLong() + reward.experience).coerceAtMost(LocalEconomy.maxExperience.toLong()).toInt(),
            rewardReceipts = (old.rewardReceipts + receipt).takeLast(128))
        commit(name, next)
        return PurchaseOutcome(PurchaseStatus.OK, next, added = next.lucci - old.lucci)
    }

    @Synchronized fun changeEquipment(name: String, inventory: Int, changes: List<Pair<Int, Int>>): LocalShopAccount {
        val old = account(name)
        require(inventory in 0..1)
        require(changes.all { (slot, _) -> slot in 0..255 })
        // The packet can contain an equipped copy and an unequipped (255) bag copy
        // of the same item. Apply removals first, then actual equipment slots.
        val ownedChanges = changes.filter { (_, id) -> id in old.items }
        val mentioned = ownedChanges.map { it.second }.toSet()
        val slots = (old.equipment[inventory] ?: emptyMap()).filterValues { it !in mentioned }.toMutableMap()
        changes.filter { (slot, id) -> id == 0 && slot != 255 }.forEach { (slot, _) -> slots.remove(slot) }
        ownedChanges.filter { (slot, _) -> slot != 255 }.forEach { (slot, id) -> slots[slot] = id }
        val equipment = old.equipment.toMutableMap()
        if (slots.isEmpty()) equipment.remove(inventory) else equipment[inventory] = slots.toMap()
        val next = old.copy(equipment = equipment.toMap())
        if (next != old) commit(name, next)
        return next
    }
}
object LocalShop { val store = LocalShopStore(Path.of(System.getProperty("bnbemu.accountsDir", "data/local-accounts"))) }
