package bnbemu.server

import bnbemu.packet.*

// Room op100's accessory field feeds CSessionContext slot+0x1D8,
// then CGOGameBomber+0x480. It is the ItemDef category-14 param, not item ID.
fun localBombStyle(account: LocalShopAccount, inventory: Int = 0): Int =
    account.equipment[inventory].orEmpty().toSortedMap().values
        .filter { it in account.items }
        .mapNotNull { id -> ItemDef.DEFAULT_ITEMS.firstOrNull { it.itemId == id && it.category == 14 } }
        .lastOrNull()?.param?.coerceAtLeast(0) ?: 0

fun localBombStyle(user: User?): Int =
    if (user == null) 0 else localBombStyle(LocalShop.store.account(user.accountKey))

// S72 listItemBg and S254 v8 both select IDDeco by category-18 skin index.
// Never send the owned item ID (e.g. 2459) in place of the style (19).
fun localNamePlateStyle(account: LocalShopAccount): Int =
    account.equipment[0].orEmpty().toSortedMap().values.filter { it in account.items }
        .mapNotNull { id -> ItemDef.DEFAULT_ITEMS.firstOrNull { it.itemId == id && it.category == 18 } }
        .lastOrNull()?.skinId?.coerceAtLeast(0) ?: 0

fun localNamePlateStyle(user: User?): Int =
    if (user == null) 0 else localNamePlateStyle(LocalShop.store.account(user.accountKey))

/** Full owned + equipped inventory. Slot 255 is a bag entry, not an equipped slot. */
fun localInventoryEntries(
    account: LocalShopAccount, inventory: Int,
    defaults: List<UserInventoryEntry> = DEFAULT_USER_INVENTORY_ITEMS
): List<UserInventoryEntry> {
    val definitions = ItemDef.DEFAULT_ITEMS.associateBy { it.itemId }
    fun entry(id: Int, slot: Int) = UserInventoryEntry(
        itemId = id, slot = slot, param = account.items.getValue(id),
        token = definitions[id]?.name ?: "", expireDate = ByteArray(8), duration = 2400000
    )
    val equipped = account.equipment[inventory].orEmpty().toSortedMap().filterValues { it in account.items }
    // Native EquipItem creates the owned instance too. Sending slot255 for that
    // same cosmetic first creates a second bag icon when the profile opens.
    return defaults.filter { it.itemId !in account.items } +
        account.items.keys.filter { it !in equipped.values }.sorted().map { entry(it, 255) } +
        equipped.mapNotNull { (slot, id) ->
            if (id in account.items) entry(id, slot) else null
        }
}

fun localUserInventory(user: User?, practice: Boolean = false, inGame: Boolean = false) = UserInventory(
    (0..1).map { inventory ->
        // A single login must not duplicate its owned items into the unused 2P bag.
        val owner = if (inventory == 0) user else user?.c?.users?.getOrNull(inventory)
        val defaults = (if (practice) DEFAULT_PRACTICE_INVENTORY_ITEMS else DEFAULT_USER_INVENTORY_ITEMS) +
            if (inGame && (Room.localGameMode in 14..16 || (LocalTestSupplies.enabled && Room.localGameMode == 0)) && (inventory == 0 || owner != null)) matchSupplyEntries() else emptyList()
        if (owner == null) defaults else {
            val character = if (owner.selectedBomber == 10) 4 else owner.selectedBomber.coerceIn(0, 7)
            val names = listOf("Dao", "Dizini", "Mos", "Ethi", "Marid", "Bazzi", "Uni", "Kephi")
            val selectedDefaults = defaults.map {
                if (it.slot == 15) UserInventoryEntry(character + 1, slot = 15, param = 20,
                    token = names[character], expireDate = ByteArray(8), status = 192) else it
            }
            localInventoryEntries(LocalShop.store.account(owner.accountKey), inventory, selectedDefaults)
        }
    }
)

// S198 clears the owned bag, so both the owned entry (255) and hotbar entry (0)
// must be present. ItemDef category 1 selects consumables / inventory type 8;
// Category 42 incorrectly renders a gift. Battle mode consumes this one charge;
// StartGameReq provisions it again on each new round. Training keeps its native
// unlimited-use hook without persisting match supplies to the lobby bag.
// 2026-11-08 用户要求: 练习模式无限针; 激战/怪物模式开局携带 1 根 (地图掉落针可拾取补充)
private const val NEEDLE_CARRY_COUNT = 1
// Training keeps one displayed charge; the native use path preserves it.
fun needleCount(): Int = NEEDLE_CARRY_COUNT

/** Transient match needle entries; battle mode gets one usable charge per round. */
fun infiniteNeedleEntries() = listOf(
    UserInventoryEntry(10000, slot = 255, param = needleCount(), token = "Item/ItemNiddle", expireDate = ByteArray(8), duration = 2400000),
    UserInventoryEntry(10000, slot = 0, param = needleCount(), token = "Item/ItemNiddle", expireDate = ByteArray(8), duration = 2400000)
)

/** These supplies exist only for the current round, never in saved ownership. */
fun matchSupplyEntries(mode: Int = Room.localGameMode): List<UserInventoryEntry> {
    val supplies = LocalTestSupplies.appendTo(if (mode == 14) listOf(10000 to "Item/ItemNiddle", 10001 to "Item/Shield", 10002 to "Item/ItemWildGinseng")
        else listOf(10000 to "Item/ItemNiddle"), mode)
    return supplies.flatMapIndexed { index, (id, token) -> listOf(
        UserInventoryEntry(id, 255, 1, token, ByteArray(8)),
        UserInventoryEntry(id, index, 1, token, ByteArray(8))
    ) }
}

/**
 * 针的持有物初始化 (op 274 UserItemUpdate)。
 *
 * 逆向 (CA.exe 0x4FCCBF, op274/op320 共用): 该包经 0x6711e5/0x65d55f 在背包向量
 * [CUserInventoryMan+8] 创建 0xC8 字节持有物对象 —— 这是 op198 type8 热键穿戴
 * (FindOwnedItemById 闸门) 的前置条件。quantity 即道具栏堆叠数。
 * 只发 slotType=0 (第一玩家); 第一轮成对发 0/1 的写法保留待 2P 实测后再启用。
 */
fun needleStockPackets() = matchSupplyEntries().filter { it.slot == 255 }.map {
    UserItemUpdate(slotType = 0, itemId = it.itemId, quantity = needleCount(), expireHours = 0, flag1 = 255, flag2 = 0)
}

/** The EN slot accessory packet carries resource indexes, not owned item ids. */
fun localAppearanceDefinitions(user: User?): List<ItemDefEntry> {
    if (user == null) return emptyList()
    val account = LocalShop.store.account(user.accountKey)
    val ids = account.equipment[0].orEmpty().toSortedMap().values.filter { it in account.items }.distinct()
    return ids.mapNotNull { id -> ItemDef.DEFAULT_ITEMS.firstOrNull { it.itemId == id } }
}
