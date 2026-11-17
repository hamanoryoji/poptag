package bnbemu.netty

import bnbemu.packet.*
import bnbemu.server.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files

class LocalEquipmentTest {
    @TempDir lateinit var directory: Path
    private val bomb = 930000045
    private fun product(id: Int = bomb) = ShopItemEntry(id, 14, "Test", itemId=id, tail=ShopItemTail(price1=1))

    @Test fun roomAndStartIdentityMetadataCannotResetTheSelectedPracticeColor() {
        val socket=ClientSocket()
        val channel=io.netty.channel.embedded.EmbeddedChannel(socket)
        val user=User(socket,91004,"practice-color-wire")
        val oldMode=Room.localGameMode
        val oldUser=Room.slots[0].user
        val oldColor=Room.slots[0].color
        try {
            Room.slots[0].user=user
            for(mode in listOf(0,15)) for(color in listOf(0,3,5,6)) {
                Room.localGameMode=mode
                Room.slots[0].color=color
                for(packet in listOf(PracticeRoomInfo(user=user),PracticeStartInfo(user=user))) {
                    val body=io.netty.buffer.Unpooled.buffer()
                    try {
                        packet.encode(SendPacketBase(body,3))
                        assertEquals(0,body.Decode1);assertEquals(user.id,body.Decode4)
                        // Independent native PSlotUserInfo reader (44A738..44A79B).
                        val roomFlag=body.Decode1
                        repeat(3) { body.Decode1 }
                        if(roomFlag!=0) body.Decode1
                        val metadata=body.Decode2 // state + bomber in little-endian order
                        assertEquals(2,metadata and 2,"Local practice must retain the map-script controller bit")
                        assertEquals(color,body.Decode1,"S100 must not overwrite S112's color")
                        assertEquals(user.name,body.DecodeCipherStr)
                    } finally { body.release() }
                }
            }
        } finally {
            Room.localGameMode=oldMode;Room.slots[0].user=oldUser;Room.slots[0].color=oldColor
            channel.finishAndReleaseAll()
        }
    }

    @Test fun avatarSectionCarriesFacesSeparatelyFromOrdinaryAccessories() {
        val body = io.netty.buffer.Unpooled.buffer()
        try {
            SendPacketBase(body, 3).encodeAvatarDefinitions(listOf(
                ItemDefEntry(970088170, 19, "Item/AvatarDeco", skinId=6, targetCharId=1, param=8),
                ItemDefEntry(777, 12, "Item/CharDeco", skinId=6, targetCharId=1, param=23)))
            assertEquals(1, body.Decode1)
            assertEquals(6, body.Decode4)
            assertEquals(1, body.Decode4)
            assertEquals(8, body.Decode4)
            assertEquals(0, body.readableBytes())
        } finally { body.release() }
    }

    @Test fun roomAndMatchTransmitTheOwnedEquippedFaceAfterBombStyle() {
        val socket = ClientSocket()
        val channel = io.netty.channel.embedded.EmbeddedChannel(socket)
        try {
            val user = User(socket, 91003, "face-wire-regression")
            val face = ItemDef.DEFAULT_ITEMS.first { it.category == 19 && it.skinId == 6 && it.targetCharId == 1 }
            assertTrue(LocalShop.store.purchase(user.accountKey,
                ShopItemEntry(face.itemId, 19, "Face", itemId=face.itemId, tail=ShopItemTail(price1=1))).status == PurchaseStatus.OK)
            LocalShop.store.changeEquipment(user.accountKey, 0, listOf(20 to face.itemId))
            for (packet in listOf(PracticeRoomInfo(45,user), PracticeStartInfo(45,user))) {
                val body = io.netty.buffer.Unpooled.buffer()
                try {
                    packet.encode(SendPacketBase(body,3))
                    body.readerIndex(body.writerIndex()-17)
                    assertEquals(45,body.Decode4)
                    assertEquals(1,body.Decode1)
                    assertEquals(6,body.Decode4)
                    assertEquals(1,body.Decode4)
                    assertEquals(face.param,body.Decode4)
                    assertEquals(0,body.readableBytes())
                } finally { body.release() }
            }
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun accessoryWireFormatUsesNativeResourceIndexes() {
        val body = io.netty.buffer.Unpooled.buffer()
        try {
            SendPacketBase(body, 3).encodeAppearanceDefinitions(listOf(
                ItemDefEntry(2282, 10, "Item/ShopDeco", skinId=1, targetCharId=1),
                ItemDefEntry(777, 12, "Item/CharDeco", skinId=-1, targetCharId=4, param=23)))
            assertEquals(1, body.Decode1)
            assertEquals(1, body.Decode1)
            assertEquals(1, body.Decode2)
            assertEquals(1, body.Decode1)
            assertEquals(-1, body.Decode4)
            assertEquals(4, body.Decode4)
            assertEquals(23, body.Decode4)
            assertEquals(0, body.readableBytes())
        } finally { body.release() }
    }

    @Test fun permanentOwnedEntryEncodesAZeroDateAndNativeUnlimitedDuration() {
        val body = io.netty.buffer.Unpooled.buffer()
        try {
            UserInventoryEntry(930000045, slot=255, expireDate=ByteArray(8), duration=2400000)
                .Encode(SendPacketBase(body, 3))
            body.Decode4; body.Decode1; body.Decode4
            assertEquals(0, body.Decode2)
            assertEquals(0, body.Decode4)
            assertEquals(0, body.Decode4)
            assertEquals(2400000, body.Decode4)
        } finally { body.release() }
    }

    @Test fun equippedAndBagCopiesPreserveEquipmentAcrossRestartAndPurchase() {
        val store = LocalShopStore(directory)
        store.purchase("a", product())
        val equipped = store.changeEquipment("a", 0, listOf(249 to bomb, 255 to bomb))
        assertEquals(bomb, equipped.equipment[0]?.get(249))
        store.purchase("a", product())
        val reopened = LocalShopStore(directory).account("A")
        assertEquals(equipped.equipment, reopened.equipment)
        assertEquals(2, reopened.items[bomb])
        assertEquals(LocalEconomy.startingGold-2, reopened.lucci)
    }

    @Test fun unequippingRemovesAppearanceButKeepsItemAndMoney() {
        val store = LocalShopStore(directory)
        store.purchase("a", product())
        val equipped = store.changeEquipment("a", 0, listOf(249 to bomb))
        val removed = store.changeEquipment("a", 0, listOf(255 to bomb))
        assertTrue(removed.equipment.isEmpty())
        assertEquals(equipped.items, removed.items)
        assertEquals(equipped.lucci, removed.lucci)
        assertTrue(LocalShopStore(directory).account("a").equipment.isEmpty())
    }

    @Test fun unownedItemsCannotBeEquippedAndPlayerInventoriesStaySeparate() {
        val store = LocalShopStore(directory)
        store.purchase("a", product())
        store.changeEquipment("a", 0, listOf(249 to bomb))
        store.changeEquipment("a", 1, listOf(249 to 123, 255 to bomb))
        assertEquals(mapOf(0 to mapOf(249 to bomb)), store.account("a").equipment)
        assertThrows(IllegalArgumentException::class.java) { store.changeEquipment("a", 2, listOf(249 to bomb)) }
    }

    @Test fun roomAndLobbySnapshotsSendEachEquippedCosmeticOnce() {
        val account = LocalShopAccount(items=mapOf(bomb to 1), equipment=mapOf(0 to mapOf(249 to bomb)))
        for (defaults in listOf(DEFAULT_USER_INVENTORY_ITEMS, DEFAULT_PRACTICE_INVENTORY_ITEMS)) {
            val entries = localInventoryEntries(account, 0, defaults)
            val purchased = entries.filter { it.itemId == bomb }
            assertEquals(listOf(249), purchased.map { it.slot })
            assertEquals(listOf("BombDeco/Bomb045"), purchased.map { it.token })
            assertEquals(listOf(255), localInventoryEntries(account, 1, defaults).filter { it.itemId == bomb }.map { it.slot })
        }
    }

    @Test fun failedEquipmentSaveDoesNotChangeMemory() {
        val blocked = directory.resolve("blocked")
        val store = LocalShopStore(blocked)
        store.purchase("a", product())
        val before = store.account("a")
        Files.list(blocked).use { paths -> paths.forEach { Files.delete(it) } }
        Files.delete(blocked)
        Files.writeString(blocked, "blocked")
        assertThrows(java.io.IOException::class.java) { store.changeEquipment("a", 0, listOf(249 to bomb)) }
        assertEquals(before, store.account("a"))
    }

    @Test fun bombStyleComesFromEquippedDefinitionRatherThanProductId() {
        val account = LocalShopAccount(items=mapOf(bomb to 1), equipment=mapOf(0 to mapOf(249 to bomb)))
        assertEquals(45, localBombStyle(account))
        assertEquals(0, localBombStyle(account, 1))
        assertEquals(0, localBombStyle(account.copy(equipment=emptyMap())))
        assertEquals(0, localBombStyle(account.copy(items=emptyMap())))
    }

    @Test fun roomAndStartPacketsCarryBombStyleAndPreserveCaptureLayout() {
        for ((original, packet) in listOf(EN_OP100_ROOM to PracticeRoomInfo(45), EN_OP100_START to PracticeStartInfo(45))) {
            val body = io.netty.buffer.Unpooled.buffer()
            try {
                packet.encode(SendPacketBase(body, 3))
                val actual = ByteArray(body.readableBytes()).also { body.getBytes(0, it) }
                assertEquals(original.size, actual.size)
                assertArrayEquals(original.copyOfRange(0, original.size-5), actual.copyOfRange(0, actual.size-5))
                body.readerIndex(actual.size-5)
                assertEquals(45, body.Decode4)
                assertEquals(original.last(), body.readByte())
            } finally { body.release() }
        }
    }
}
