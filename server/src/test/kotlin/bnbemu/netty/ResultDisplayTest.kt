package bnbemu.netty

import bnbemu.packet.*
import bnbemu.packet.room.GameResult
import bnbemu.server.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ResultDisplayTest {
    private data class Case(val label: String,val level: Int,val exp: Int,val gold: Int)
    @Test fun resultSummariesAndRoomBadgesUseTheEarnedValuesAndCurrentLevel() {
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        val oldMode=Room.localGameMode
        try {
            Room.reset();Room.localGameMode=16
            Room.slots.forEach { it.user=null;it.isAI=false }
            val user=User(socket,1,"result-display-test")
            Room.slots[0].user=user
            for ((label,level,exp,gold) in listOf(Case("loss",31,25000,50000),Case("win",44,50000,100000))) {
                user.level=level
                user.exp=LocalEconomy.progress(exp).second
                val reward=LocalEconomy.Reward(gold,exp)
                val b=Unpooled.buffer()
                try {
                    GameResult(winnerTeam=if(label=="win") 0 else 1,rewards=mapOf(0 to reward)).encode(SendPacketBase(b,3))
                    val raw=ByteArray(b.readableBytes());b.getBytes(0,raw)
                    // Follow the independent native reader's field widths.
                    b.skipBytes(11);assertEquals(1,b.Decode1);b.Decode4
                    repeat(4) { b.Decode1 }
                    assertEquals(exp,b.Decode4);b.Decode1
                    assertEquals(gold,b.Decode4);b.Decode1
                    b.Decode4;b.Decode1;b.Decode4;repeat(3){b.Decode1}
                    assertEquals(0,b.Decode2,"Starting progress before this first award")
                    assertEquals(LocalEconomy.progressRatio(level,user.exp),b.Decode2,"Ending progress")
                    assertEquals(level,b.Decode2)
                    assertEquals(level,b.Decode2,"Native +88 must update the global nickname medal cache")
                    repeat(4){b.Decode1};b.Decode4;b.Decode2
                    assertEquals(gold,b.Decode4,"Native row +98 is the per-player GOLD column, not experience")
                    b.Decode2;b.Decode4
                    assertEquals(gold,b.Decode4,"Native row +A8 is the animated coin total")
                    val audit=System.getProperty("bnbemu.nativeResultAudit")
                    if(audit!=null) {
                        Files.createDirectories(Path.of(audit))
                        Files.write(Path.of(audit,"$label.bin"),raw)
                    }
                } finally { b.release() }
                for(packet in listOf(PracticeRoomInfo(user=user),PracticeStartInfo(user=user))) {
                    val room=Unpooled.buffer()
                    try {
                        packet.encode(SendPacketBase(room,3))
                        val raw=ByteArray(room.readableBytes());room.getBytes(0,raw)
                        val token=encodeName(user.name)
                        val at=(0..raw.size-token.size).first { n -> token.indices.all { raw[n+it]==token[it] } }
                        room.readerIndex(at+token.size)
                        room.Decode1;room.Decode1;room.Decode4;room.Decode1;room.Decode4
                        assertEquals(level,room.Decode2,"Room and game-start badge must not use replay level 1")
                    } finally { room.release() }
                }
            }
        } finally {
            Room.slots.forEach { it.user=null;it.isAI=false };Room.reset();Room.localGameMode=oldMode;channel.finishAndReleaseAll()
        }
    }

    @Test fun channelContextInitializesPersistedLevelExperienceAndWalletWithoutChangingOtherFields() {
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        val baseline=Unpooled.buffer();val actual=Unpooled.buffer()
        try {
            val user=User(socket,1,"level-context-test")
            user.level=44;user.exp=550;user.lucci=105000
            Unk20(6).encode(SendPacketBase(baseline,3))
            Unk20(6,user).encode(SendPacketBase(actual,3))
            assertEquals(44,actual.readableBytes())
            val changed=(1..2).toSet()+(4..7).toSet()+(15..18).toSet()
            for(i in 0 until 44) if(i !in changed) assertEquals(baseline.getByte(i),actual.getByte(i),"Unexpected change at $i")
            actual.readerIndex(1);assertEquals(44,actual.Decode2)
            actual.readerIndex(4);assertEquals(550,actual.Decode4)
            actual.readerIndex(15);assertEquals(105000,actual.Decode4)
        } finally { baseline.release();actual.release();channel.finishAndReleaseAll() }
    }

    @Test fun profileProgressBarUsesThousandthsOfCurrentLevel() {
        val b=Unpooled.buffer()
        try {
            UserInfoResult(characterName="",motto="",guildName="",curExp=550,maxExp=2250,level=44).encode(SendPacketBase(b,3))
            b.Decode4;b.Decode4;b.Decode1;b.Decode2;b.Decode1;b.Decode2;b.Decode2;b.Decode1
            repeat(3){b.Decode4}
            assertEquals(550,b.Decode4);assertEquals(2250,b.Decode4);assertEquals(244,b.Decode4)
        } finally { b.release() }
    }

    @Test fun progressMathPreservesExistingThresholdsAndHandlesLevelWrapAndCap() {
        assertEquals(50000,LocalEconomy.totalExperience(44,550))
        assertEquals(25000,LocalEconomy.totalExperience(31,250))
        assertEquals(156,LocalEconomy.progressRatio(31,250))
        assertEquals(244,LocalEconomy.progressRatio(44,550))
        assertEquals(800,LocalEconomy.progressRatio(1,80))
        assertEquals(100,LocalEconomy.progressRatio(2,15))
        assertEquals(999,LocalEconomy.progressRatio(720,0))
        assertEquals(0,LocalEconomy.experienceRatio(-1,100))
        assertEquals(999,LocalEconomy.experienceRatio(Int.MAX_VALUE,1))
    }
}
