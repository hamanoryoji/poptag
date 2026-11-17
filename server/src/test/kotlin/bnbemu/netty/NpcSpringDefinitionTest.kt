package bnbemu.netty

import bnbemu.packet.ItemDef
import bnbemu.server.matchSupplyEntries
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcSpringDefinitionTest {
    @Test fun nativeRedNpcLookupCanResolveAllThreeConsumables() {
        val decoded=ItemDef.decodeBlock(ItemDef.buildBlock(ItemDef.DEFAULT_ITEMS))
        for(name in listOf("Item/Shield","Item/ItemNiddle","Item/Spring")) {
            val definition=decoded.single { it.name==name }
            assertEquals(1,definition.category)
        }
        assertEquals(10005,decoded.single { it.name=="Item/Spring" }.itemId)
        // Registering the definition must not add a free player hotbar item.
        for(mode in listOf(0,14,15,16))
            assertFalse(matchSupplyEntries(mode).any { it.token=="Item/Spring" })
    }
}
