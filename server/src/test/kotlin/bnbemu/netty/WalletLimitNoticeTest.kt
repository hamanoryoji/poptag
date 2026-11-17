package bnbemu.netty

import bnbemu.server.LocalEconomy
import bnbemu.packet.StageNotice
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class WalletLimitNoticeTest {
    @Test fun cappedAwardsExplainTheActualCreditAndUncappedAwardsStaySilent() {
        for(won in listOf(false,true)) {
            val requested=LocalEconomy.reward(16,won)
            assertEquals(if(won)100000 else 50000,requested.gold)
            assertNull(LocalEconomy.walletLimitNotice(requested,requested))
            assertTrue(requireNotNull(LocalEconomy.walletLimitNotice(requested,requested.copy(gold=0))).contains("不再增加"))
            assertTrue(requireNotNull(LocalEconomy.walletLimitNotice(requested,requested.copy(gold=20000))).contains("20000金币"))
        }
        assertNull(LocalEconomy.walletLimitNotice(LocalEconomy.Reward(),LocalEconomy.Reward()))
        assertEquals(3000000,LocalEconomy.walletLimit)
    }
    @Test fun resultShowsRoundAwardEvenWhenWalletIsFullOrPartiallyCapped() {
        for(won in listOf(false,true)) {
            val requested=LocalEconomy.reward(16,won)
            for(creditedGold in listOf(0,20000,requested.gold)) {
                val actual=requested.copy(gold=creditedGold)
                assertEquals(requested,LocalEconomy.resultReward(requested,actual))
                assertEquals(creditedGold,actual.gold,"Display must not alter the wallet credit")
            }
            assertEquals(requested.copy(experience=0),LocalEconomy.resultReward(requested,LocalEconomy.Reward()))
        }
        assertEquals(LocalEconomy.Reward(),LocalEconomy.resultReward(LocalEconomy.Reward(),LocalEconomy.Reward()))
    }
    @Test fun noticeUsesNativeStageMessageKindAndChineseCipherString() {
        val text=requireNotNull(LocalEconomy.walletLimitNotice(LocalEconomy.Reward(50000,25000),LocalEconomy.Reward(0,25000)))
        val packet=StageNotice(text)
        assertEquals(23,OpcodeManager.getSendOp(packet::class.java))
        val b=Unpooled.buffer()
        try {
            packet.encode(SendPacketBase(b,3))
            assertEquals(0,b.Decode1);assertEquals(text,b.DecodeCipherStr)
            assertEquals(0,b.readableBytes())
        } finally { b.release() }
    }
}
