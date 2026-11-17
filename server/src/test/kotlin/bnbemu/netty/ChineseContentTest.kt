package bnbemu.netty

import bnbemu.data.FxdynMapInfo
import bnbemu.packet.*
import bnbemu.server.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChineseContentTest {
    @Test fun extraProductsAndMonsterMapsPreserveLegacyIndexes() {
        // 2026-11-17: 客户端 CShopItemMan::Read (0x670965) 的数量字段为 16 位有符号读取,
        // 逐条分配 0x210 对象, 无 1000 上限 —— 旧 995 保守帽解除, 实测 ~2200 商品可解析。
        assertTrue(ShopCatalog.DEFAULT_ITEMS.size < 32767)
        assertEquals(ShopCatalog.DEFAULT_ITEMS.size, ShopCatalog.DEFAULT_ITEMS.map(ShopCatalog::appearanceKey).distinct().size)
        assertFalse(ShopCatalog.DEFAULT_ITEMS.any { it.itemId == 930000006 })
        val definitionBlock = ItemDef.buildBlock(ItemDef.DEFAULT_ITEMS)
        assertTrue(definitionBlock.size < 0xFFFFFF)
        assertEquals(ItemDef.DEFAULT_ITEMS, ItemDef.decodeBlock(definitionBlock))
        val before = FxdynMapInfo.records.filter { it.flag == 0 }
        val after = FxdynMapInfo.gameRecords.filter { it.flag == 0 }
        // Menu indexes are stable; native Map/ indexes change when assets are added.
        before.zip(after.take(before.size)).forEach { (original, ported) ->
            assertEquals(original, ported.copy(sortOrder = original.sortOrder), original.name)
        }
        assertEquals(75, after.size - before.size)
        assertTrue(after.map { it.name }.containsAll(listOf("Monster37", "Monster38", "Monster39")))
        assertEquals(after.size, bnbemu.handler.room.loadMapIdTable().size)
    }

    @Test fun portedMonsterThemesAndNativeMapIndexesMatchTheirAssetFamilies() {
        val nativeIds = checkNotNull(javaClass.getResourceAsStream("/data/ported-native-map-indices.tsv"))
            .bufferedReader().use { it.readLines() }.associate {
                val fields = it.split('\t'); fields[1] to fields[0].toInt()
            }
        val expected = mapOf("MonSeal" to (27 to 248), "MonTurtle" to (24 to 251), "MonRobot" to (18 to 253))
        expected.forEach { (family, countAndTheme) ->
            val maps = FxdynMapInfo.gameRecords.filter { it.name.startsWith(family) && !it.name.endsWith("Random") }
            assertEquals(countAndTheme.first, maps.size, family)
            maps.forEach { map ->
                assertEquals(countAndTheme.second, map.themeId, map.name)
                assertEquals(nativeIds[map.name], map.sortOrder, map.name)
                val stage = map.name.removePrefix(family).toInt()
                assertEquals(if ((stage - 1) % 3 == 0) 1 else 0, map.tail1, map.name)
            }
        }
    }

    @Test fun portedMonsterFamiliesHaveNativeSelectionGroupEntries() {
        // CMapSelectDialog uses random records (subtype 6) as monster family
        // headers. Loading concrete boards alone does not expose a new family.
        val rows = FxdynMapInfo.gameRecords.filter { it.flag == 0 }
        for ((family, theme) in mapOf("MonSeal" to 248, "MonTurtle" to 251, "MonRobot" to 253)) {
            val header = rows.single { it.name == "${family}Random" }
            assertEquals(6, header.subtype)
            assertEquals(theme, header.themeId)
            assertEquals(1, header.tail1)
            assertEquals(0, header.sortOrder, "Virtual headers must not resolve to a board asset")
            assertTrue(rows.any { it.group == family && it.subtype == 0 && it.tail1 == 1 })
        }
    }

    @Test fun monsterMapLabelsUseInstalledNationalCatalogNames() {
        val rows = FxdynMapInfo.localizedRecords.associateBy { it.name }
        for ((name, display) in mapOf("MonSeal01" to "海皇 01-1", "MonTurtle01" to "神秘海域 01-1",
            "MonRobot01" to "机械帝国 01-1", "MonPen01" to "帝企鹅 01-1",
            "Cemetery03" to "墓地03", "Camp08" to "坦克08", "Patrit14" to "海盗14", "Village05" to "小区05")) {
            assertEquals(display, rows.getValue(name).display)
        }
        assertEquals("机械帝国", rows.getValue("MonRobotRandom").display)
    }

    @Test fun announcedMapChecksumMatchesTheActualLocalizedMapPayload() {
        assertEquals(342, FxdynMapInfo.records.size)
        val body = Unpooled.buffer()
        try {
            FxConfig().encode(SendPacketBase(body, 3))
            val announced = body.Decode4
            assertEquals(FxdynMapInfo.checksum(FxdynMapInfo.localizedPlain), announced)
            assertTrue(announced != 0)
            val changed = FxdynMapInfo.localizedPlain.copyOf()
            changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
            assertNotEquals(announced, FxdynMapInfo.checksum(changed))
        } finally { body.release() }
    }

    @Test fun translatedMapDisplayPreservesAllRoutingAndGameplayFields() {
        val original = FxdynMapInfo.gameRecords
        val localized = FxdynMapInfo.localizedRecords
        assertEquals(original.size, localized.size)
        original.zip(localized).forEach { (a, b) ->
            assertEquals(a, b.copy(display = a.display))
            assertTrue(b.display.any { it in '\u3400'..'\u9fff' }, b.name)
        }
        assertEquals("墓地04", localized.filter { it.flag == 0 }[176].display)
        assertEquals("Cemetery04", localized.filter { it.flag == 0 }[176].name)
        assertTrue(FxdynMapInfo.localizedPlain.size > 0)
    }

    @Test fun catalogNamesAreChineseAndRoundTripWithoutChangingProductResources() {
        val catalog = ShopCatalog.DEFAULT_ITEMS
        assertEquals(catalog, ShopCatalog.decodeBlock(ShopCatalog.buildBlock(catalog)))
        catalog.forEach { assertTrue(it.name.any { c -> c in '\u3400'..'\u9fff' }, it.name) }
        ShopCatalog.loadBaseItems().forEach { original ->
            val translated = catalog.singleOrNull { it.prodId == original.prodId }
            if (translated != null) assertEquals(original, translated.copy(name = original.name,
                tail = translated.tail.copy(f7 = original.tail.f7, price1 = original.tail.price1, price2 = original.tail.price2, f9 = original.tail.f9))) // explicit home-page merchandising rank
            else assertTrue(catalog.any { ShopCatalog.appearanceKey(it) == ShopCatalog.appearanceKey(original) } ||
                original.itemId == 2082 || ItemDef.DEFAULT_ITEMS.any { it.itemId == original.itemId && it.category == 14 &&
                    (it.name == "Item/Bomb" || it.param in setOf(121,126)) }, "Hidden products must be explicitly retired or duplicate entries")
        }
        assertTrue(catalog.any { ShopCatalog.appearanceKey(it) == "bubble:45" })
        assertTrue(LocalSystemText.translations.size >= 300)
        assertEquals("全套装扮", LocalSystemText.translations["set01"])
    }

    @Test fun singlePlayerInventoryDoesNotClonePurchasesIntoUnusedSecondPlayerBag() {
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val body = Unpooled.buffer()
        try {
            val user = User(cs, 9616, "潜在游鱼")
            cs.users.add(user)
            localUserInventory(user).encode(SendPacketBase(body, 3))
            assertEquals(2, body.Decode1)
            repeat(2) { inventory ->
                val ids = mutableListOf<Int>()
                repeat(body.Decode4) {
                    ids += body.Decode4
                    body.Decode1; body.Decode4; body.DecodeCipherStr
                    body.skipBytes(8); body.Decode4; body.Decode1
                }
                if (inventory == 1) assertFalse(930000045 in ids)
            }
        } finally { body.release(); ch.finishAndReleaseAll() }
    }
}
