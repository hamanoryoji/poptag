package bnbemu.netty

import bnbemu.handler.ChangeEquipReq
import bnbemu.server.*
import bnbemu.packet.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NamePlateDisplayTest {
    @Test fun onlyOwnedAndEquippedCategory18ItemsSelectANamePlateStyle() {
        val owned=LocalShopAccount(items=mapOf(2459 to 1),equipment=mapOf(0 to mapOf(231 to 2459)))
        assertEquals(19,localNamePlateStyle(owned))
        assertEquals(0,localNamePlateStyle(owned.copy(equipment=emptyMap())))
        assertEquals(0,localNamePlateStyle(owned.copy(items=emptyMap())))
    }
    @Test fun changingNamePlateRefreshesBothLobbyPanelAndListWithStyleNotItemId() {
        val socket=ClientSocket();val channel=EmbeddedChannel(socket);val request=Unpooled.buffer()
        try {
            // 账号存档会跨测试运行持久化 (data/local-accounts): 名牌测试依赖"购买前未拥有",
            // 固定账号名在第二次运行时样式已是 19 → 无变化不发包。用一次性账号名保证幂等。
            val user=User(socket,190019,"nameplate-display-test-"+System.nanoTime());socket.users.add(user)
            LocalShop.store.purchase(user.accountKey,ShopItemEntry(1,18,"test",itemId=2459,tail=ShopItemTail(price1=1)))
            SendPacketBase(request,3).apply { Encode1(5);Encode1(0);Encode2(1);Encode1(231);Encode4(2459) }
            ChangeEquipReq.run { request.handle(socket) }
            assertEquals(19,localUserMapEntry(user).v8)
            val packets=mutableListOf<PKT>()
            while(true) packets.add(channel.readOutbound<PKT>() ?: break)
            assertEquals(listOf(254,72),packets.map { OpcodeManager.getSendOp(it::class.java) })
            val list=Unpooled.buffer()
            try {
                packets.last().encode(SendPacketBase(list,3))
                list.Decode2;list.Decode1;val token=ByteArray(list.Decode2);list.readBytes(token)
                assertEquals(user.name,decodeName(token))
                list.Decode1;list.Decode1;list.Decode4;list.Decode1;list.Decode4;list.Decode2;list.Decode4
                assertEquals(19,list.Decode4,"S72 listItemBg is style 19, not item ID 2459")
            } finally { list.release() }
        } finally { request.release();channel.finishAndReleaseAll() }
    }
}
