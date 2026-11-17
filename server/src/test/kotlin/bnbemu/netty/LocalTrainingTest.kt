package bnbemu.netty

import bnbemu.packet.ItemDef
import bnbemu.server.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalTrainingTest {
    @Test fun lobbyBagGridDoesNotPersistMatchNeedles() {
        val body = Unpooled.buffer()
        try {
            bnbemu.packet.UserInventoryInfo().encode(SendPacketBase(body, 3))
            body.Decode1; body.Decode4; body.skipBytes(8)
            assertEquals(0, body.Decode1)
            assertFalse(body.isReadable)
        } finally { body.release() }
    }

    @Test fun selfRescueReportDoesNotRefillNeedle() {
        OpcodeManager.recvOps
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val mode = Room.localGameMode
        val user = Room.slots[0].user
        try {
            Room.localGameMode = 16
            Room.slots[0].user = User(cs, 1, "needle-test")
            val req = Unpooled.buffer()
            SendPacketBase(req, 3).apply {
                Encode2(154); Encode2(0); Encode2(0); Encode2(81); Encode2(0)
            }
            ch.writeInbound(req)
            // 2026-11-08 用户要求: 用针后不补发 (开局 1 根, 拾取地图掉落针自行补充)
            assertNull(ch.readOutbound<PKT>())
            assertTrue(ch.isActive)
        } finally {
            Room.slots[0].user = user
            Room.localGameMode = mode
            ch.finishAndReleaseAll()
        }
    }
    @Test fun trainingCreatesAnEmptyPracticeRoomAndNeverDeclaresSoloVictory() {
        OpcodeManager.recvOps
        val cs = ClientSocket()
        val ch = EmbeddedChannel(cs)
        val mode = Room.localGameMode
        val saved = Room.slots.map { Triple(it.user, it.isAI, it.state) }
        try {
            cs.users.add(User(cs, 1, "training"))
            val req = Unpooled.buffer()
            SendPacketBase(req, 3).apply {
                Encode2(75); EncodeStr(""); EncodeStr("")
                Encode1(0); Encode1(1); Encode1(5); Encode1(15); Encode4(66)
            }
            ch.writeInbound(req)
            assertEquals(15, Room.localGameMode)
            assertEquals(1, Room.activeSlotCount)
            assertTrue(Room.slots.drop(1).all { !it.isAI && it.user == null })
            // The common prepare UI automatically requests its usual battle bots.
            val openNpc = Unpooled.buffer()
            SendPacketBase(openNpc, 3).apply { Encode2(89); Encode1(0x40) }
            ch.writeInbound(openNpc)
            val configureNpc = Unpooled.buffer()
            SendPacketBase(configureNpc, 3).apply { Encode2(88); Encode1(0); Encode1(4); Encode1(20); Encode1(4) }
            ch.writeInbound(configureNpc)
            assertEquals(1, Room.activeSlotCount)
            Room.resetMatchState()
            Room.onLastTeamStanding(0)
            assertFalse(Room.gameOverSent, "Solo practice must continue without an opponent")
        } finally {
            Room.reset()
            saved.forEachIndexed { i, old -> Room.slots[i].apply { user = old.first; isAI = old.second; state = old.third } }
            Room.localGameMode = mode
            ch.finishAndReleaseAll()
        }
    }

    @Test fun localTrainingAndBattlePlayersHaveNeedleHotkeys() {
        val mode = Room.localGameMode
        fun entries(inGame: Boolean = true): List<Pair<Int, Int>> {
            val b = Unpooled.buffer()
            try {
                localUserInventory(null, practice = true, inGame = inGame).encode(SendPacketBase(b, 3))
                val entries = mutableListOf<Pair<Int, Int>>()
                repeat(b.Decode1) {
                    repeat(b.Decode4) {
                        val id = b.Decode4; val slot = b.Decode1
                        b.Decode4
                        val length = b.Decode2; b.skipBytes(length + 8 + 4 + 1)
                        entries.add(id to slot)
                    }
                }
                return entries
            } finally { b.release() }
        }
        try {
            Room.localGameMode = 15
            assertTrue(entries().containsAll(listOf(10000 to 0, 10000 to 255)))
            assertEquals(2, entries().count { it.first == 10000 }, "Unused 2P inventory must not receive match supplies")
            assertFalse(entries(inGame = false).any { it.first == 10000 }, "Room/lobby inventory must not retain match needles")
            Room.localGameMode = 16
            assertTrue(entries().containsAll(listOf(10000 to 0, 10000 to 255)))
            Room.localGameMode = 0
            assertFalse(entries().any { it.first == 10000 })
            assertEquals("Item/ItemNiddle", ItemDef.DEFAULT_ITEMS.single { it.itemId == 10000 }.name)
            assertEquals("Item/Shield", ItemDef.DEFAULT_ITEMS.single { it.itemId == 10001 }.name)
        } finally { Room.localGameMode = mode }
    }
}
