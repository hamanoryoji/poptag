package bnbemu.netty

import bnbemu.server.*
import bnbemu.packet.room.GameResult
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcResultLevelTest {
    @Test fun kunPrototypeCannotReplaceStableNpcWithoutExplicitOptIn() {
        assertFalse(kunPrototypeEnabled())
        assertEquals(20,localNpcCharacter(4,20,false))
        assertEquals(25,localNpcCharacter(4,20,true))
        assertEquals(21,localNpcCharacter(5,21,true))
    }
    @Test fun resultNpcMedalsFollowTheSamePresetMappingAsTheNativeRoom() {
        val oldMode=Room.localGameMode
        try {
            Room.reset();Room.localGameMode=16
            Room.slots.forEach { it.user=null;it.isAI=false }
            val slot=Room.slots[4];slot.isAI=true
            val levels=listOf(3,10,52,109,163)
            for(preset in 0..4) for(winner in 0..1) {
                slot.npcDifficulty=preset
                val b=Unpooled.buffer()
                try {
                    GameResult(winnerTeam=winner).encode(SendPacketBase(b,3))
                    b.skipBytes(15);assertEquals(2,b.Decode1)
                    assertEquals(if(winner==0) 2 else 0,b.Decode1)
                    b.Decode1;b.Decode1;assertEquals(levels[preset],b.Decode2)
                } finally { b.release() }
            }
        } finally { Room.reset();Room.localGameMode=oldMode;Room.slots[4].isAI=false }
    }
    @Test fun priorLevel100CapCanContinueWithoutChangingTheWalletLimitOrXpCurve() {
        val oldCap=(1 until 100).sumOf(LocalEconomy::experienceToNext)
        assertEquals(100 to 0,LocalEconomy.progress(oldCap))
        assertTrue(LocalEconomy.progress(oldCap+50000).first>100)
        assertEquals(720,LocalEconomy.maxLevel)
        assertEquals(3_000_000,LocalEconomy.walletLimit)
        assertEquals(100,LocalEconomy.experienceToNext(1))
        assertEquals(5050,LocalEconomy.experienceToNext(100))
        assertEquals(720 to 0,LocalEconomy.progress(LocalEconomy.maxExperience))
    }
}
