package bnbemu.netty

import bnbemu.packet.*
import bnbemu.server.*
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FiveBugRecoveryTest {
    @Test fun nativeUserInitializationCarriesTheSelectedCharacter() {
        for (character in listOf(0, 5, 6, 7, 10)) {
            val body = Unpooled.buffer()
            try {
                Unk20(character).encode(SendPacketBase(body, 3))
                assertEquals(1, body.Decode1)
                body.Decode2; body.Decode1; body.Decode4; body.Decode1; body.Decode4
                body.Decode1; body.Decode1; body.Decode4; body.Decode4; body.Decode1; body.Decode4
                assertEquals(if (character == 10) 4 else character, body.Decode1)
            } finally { body.release() }
        }
        val body = Unpooled.buffer()
        try {
            Unk20().encode(SendPacketBase(body, 3))
            body.readerIndex(28)
            assertEquals(6, body.Decode1, "The fallback must be Uni / 小乖")
        } finally { body.release() }
    }

    @Test fun monsterRoundSuppliesAreOneNeedleOneShieldAndOneGinseng() {
        val entries = matchSupplyEntries(14)
        assertEquals(listOf(10000, 10001, 10002), entries.filter { it.slot != 255 }.map { it.itemId })
        assertEquals(listOf(0, 1, 2), entries.filter { it.slot != 255 }.map { it.slot })
        assertTrue(entries.all { it.param == 1 })
        for (id in listOf(10000, 10001, 10002))
            assertEquals(1, ItemDef.DEFAULT_ITEMS.single { it.itemId == id }.category)
        assertEquals(listOf(10000), matchSupplyEntries(16).filter { it.slot != 255 }.map { it.itemId })
        val old = Room.localGameMode
        try {
            Room.localGameMode = 14
            repeat(2) {
                for ((index, packet) in needleStockPackets().withIndex()) {
                    val body = Unpooled.buffer()
                    try {
                        packet.encode(SendPacketBase(body, 3))
                        assertEquals(0, body.Decode1)
                        assertEquals(10000 + index, body.Decode4)
                        assertEquals(1, body.Decode4, "Every new round replenishes exactly one")
                    } finally { body.release() }
                }
            }
        } finally { Room.localGameMode = old }
    }

    @Test fun initialNpcIdentityRefreshUsesTheSelectedNpcAppearance() {
        OpcodeManager.recvOps
        val socket = ClientSocket()
        val channel = EmbeddedChannel(socket)
        val oldMode = Room.localGameMode
        val saved = Room.slots.map { listOf(it.user, it.isAI, it.bomber.toInt(), it.color.toInt(), it.state.toInt(), it.AILevel, it.npcDifficulty) }
        try {
            socket.users.add(User(socket, 90001, "room-order-test"))
            val request = Unpooled.buffer()
            SendPacketBase(request, 3).apply {
                Encode2(75); Encode2(0); Encode2(0)
                Encode1(1); Encode1(1); Encode1(6); Encode1(16); Encode4(0)
            }
            channel.writeInbound(request)
            val seenAi = mutableSetOf<Int>()
            val named = mutableSetOf<Int>()
            val pending = mutableSetOf<Int>()
            while (true) {
                val packet = channel.readOutbound<PKT>() ?: break
                val op = OpcodeManager.getSendOp(packet::class.java)
                if (op !in listOf(103, 105, 114)) continue
                val body = Unpooled.buffer()
                try {
                    packet.encode(SendPacketBase(body, 3))
                    if (op == 114) {
                        val slot = body.Decode1
                        val expected = if (slot == 0) 6 else 20 + slot - 4
                        assertEquals(expected, body.Decode1, "NPC appearance must use its selected role")
                        assertEquals(expected, body.Decode1, "Deferred preview must not restore captured role5")
                        pending += slot
                    } else if (op == 103) {
                        val slot = body.Decode2
                        if (slot >= 4) assertTrue(slot in named, "op103 must see the selected NPC role")
                        seenAi += slot
                    }
                    else {
                        val slot = body.Decode1
                        if (slot >= 4) {
                            assertEquals(20 + slot - 4, body.Decode1)
                            assertTrue(slot in pending)
                            named += slot
                        }
                    }
                } finally { body.release() }
            }
            assertEquals(setOf(4, 5, 6, 7), named)
            assertTrue(seenAi.containsAll(named))
            assertEquals(6, Room.slots[0].bomber.toInt())
        } finally {
            Room.reset()
            Room.slots.forEachIndexed { index, slot ->
                val old = saved[index]
                slot.user = old[0] as User?; slot.isAI = old[1] as Boolean
                slot.bomber = old[2] as Int; slot.color = old[3] as Int
                slot.state = old[4] as Int; slot.AILevel = old[5] as Int; slot.npcDifficulty = old[6] as Int
            }
            Room.localGameMode = oldMode
            channel.finishAndReleaseAll()
        }
    }
    @Test fun practiceRescueDoesNotReplaceTheInventoryWhileAnItemIsInUse() {
        OpcodeManager.recvOps
        val socket = ClientSocket()
        val channel = EmbeddedChannel(socket)
        val user = User(socket, 90003, "needle-reentry-test")
        val oldUser = Room.slots[0].user
        val oldMode = Room.localGameMode
        try {
            Room.slots[0].user = user
            Room.localGameMode = 15
            val request = Unpooled.buffer()
            SendPacketBase(request, 3).apply {
                Encode2(154); Encode2(0); Encode2(0); Encode2(0); Encode2(0)
            }
            channel.writeInbound(request)
            assertNull(channel.readOutbound<PKT>(), "Rescue must not invalidate live inventory references")
        } finally {
            Room.slots[0].user = oldUser
            Room.localGameMode = oldMode
            channel.finishAndReleaseAll()
        }
    }

    @Test fun waitingRoomEquipmentRefreshPreservesSelectedCharacterWithoutRefillingItems() {
        OpcodeManager.recvOps
        val socket = ClientSocket()
        val channel = EmbeddedChannel(socket)
        val user = User(socket, 90004, "room-appearance-refresh-test")
        user.selectedBomber = 7
        val oldUser = Room.slots[0].user
        try {
            Room.reset()
            Room.slots[0].user = user
            assertTrue(Room.refreshWaitingAppearance(user))
            val packets = mutableListOf<PKT>()
            while (true) packets += channel.readOutbound<PKT>() ?: break
            assertEquals(listOf(100, 105), packets.map { OpcodeManager.getSendOp(it::class.java) })
            val body = Unpooled.buffer()
            try {
                packets.last().encode(SendPacketBase(body, 3))
                assertEquals(0, body.Decode1)
                assertEquals(7, body.Decode1)
            } finally { body.release() }
            Room.startGame()
            channel.outboundMessages().clear()
            assertFalse(Room.refreshWaitingAppearance(user), "Prepare packets must not enter an active match")
            assertNull(channel.readOutbound<PKT>())
        } finally {
            Room.reset()
            Room.slots[0].user = oldUser
            channel.finishAndReleaseAll()
        }
    }

}
