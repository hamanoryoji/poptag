package bnbemu.netty

import bnbemu.packet.*
import bnbemu.server.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LocalPlayersTest {
    @TempDir lateinit var directory: Path
    @Test fun loginHistoryStartsEmptyPersistsChineseNamesAndUpdatesWithoutDuplicates() {
        val path=directory.resolve("history/players.properties")
        val history=LocalPlayerHistory(path)
        assertTrue(history.list().isEmpty(),"Never seed captured sample players")
        val player=LocalPlayerRecord("潜在游鱼","潜在游鱼",9616)
        history.record(player);history.record(player)
        assertEquals(listOf(player),LocalPlayerHistory(path).list())
        val renamed=player.copy(name="测试昵称")
        history.record(renamed)
        assertEquals(listOf(renamed),LocalPlayerHistory(path).list())
    }

    @Test fun lobbyListAndRosterUseTheSameActualPlayerAndLiveLevel() {
        val socket=ClientSocket();val channel=EmbeddedChannel(socket)
        try {
            val user=User(socket,9616,"潜在游鱼",accountKey="local-players-test")
            user.level=62;socket.users.add(user)
            val entries=LocalPlayers.sessionEntries(socket)
            val roster=LocalPlayers.rosterEntries(socket)
            assertEquals("潜在游鱼",entries.first().name)
            assertEquals(62,entries.first().level)
            assertEquals("潜在游鱼",roster.first().name)
            assertEquals(9616,roster.first().accountId)
            assertEquals(entries.map { it.name },roster.map { it.name })
            assertFalse(entries.any { it.name in setOf("Kuso","Lotso","Jacko","suan","Hello","Mirage","Static","ffdf") })
            val b=Unpooled.buffer()
            try {
                SessionList(entries).encode(SendPacketBase(b,3))
                assertEquals(0,b.Decode2);assertEquals(entries.size,b.Decode1)
                val token=ByteArray(b.Decode2);b.readBytes(token)
                assertEquals("潜在游鱼",decodeName(token))
                b.Decode1;b.Decode1;b.Decode4;b.Decode1;b.Decode4
                assertEquals(62,b.Decode2,"Actual wire level for the lobby list medal")
            } finally { b.release() }
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun upperLobbyLevelPanelGetsItsOwnLevelAndProgressPacket() {
        val socket=ClientSocket();val channel=EmbeddedChannel(socket);val b=Unpooled.buffer()
        try {
            val user=User(socket,9616,"潜在游鱼",accountKey="upper-level-panel-test")
            user.level=44;user.exp=550
            UserMapInfo(localUserMapEntry(user)).encode(SendPacketBase(b,3))
            assertEquals(1,b.Decode1)
            val token=ByteArray(b.Decode2);b.readBytes(token);assertEquals(user.name,decodeName(token))
            b.Decode1;b.Decode1;b.Decode4;b.Decode1;b.Decode4
            assertEquals(44,b.Decode2,"CUserMap self entry +5C: medal and Lv. text")
            repeat(4){b.Decode4};b.Decode1;b.Decode4;repeat(4){b.Decode2}
            assertEquals(244,b.Decode4,"CUserMap self entry +17C: upper LEVEL progress")
            b.Decode1;assertEquals(0,b.readableBytes())
        } finally { b.release();channel.finishAndReleaseAll() }
    }
}
