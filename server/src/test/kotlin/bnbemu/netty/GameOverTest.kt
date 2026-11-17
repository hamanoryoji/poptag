package bnbemu.netty

import bnbemu.handler.room.BomberActionReq
import bnbemu.packet.room.ReviveBomber
import bnbemu.packet.room.GameResult
import bnbemu.packet.room.InitGameResult
import bnbemu.server.Room
import bnbemu.server.SlotMatchState
import bnbemu.server.User
import bnbemu.server.isAlive
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 对局结束判定回归测试
 *
 * 触发结算的两条路径：
 *   1. 真实路径 —— C→S op 112 action=0x60（客户端自己判定"只剩一支队伍"，每帧重发）
 *   2. 兼容路径 —— C→S op 154 action=5（只对真人玩家成立：bomber+0x4D0 = m_selfidx 闸门）
 *
 * 结算包：S→C op 169 InitGameResult → op 150 GameResult。
 * 会打出 "XXX has been resurrected!"），只留给"复活币"道具用（Room.reviveBomber）。
 */
class GameOverTest {

    @Test fun aiBattleTimeoutStartsAfterReadySettlesOnceAndKeepsPracticeUntimed() {
        val oldMode = Room.localGameMode
        val oldLimit = Room.aiBattleTimeLimitMs
        try {
            Room.localGameMode = 16
            Room.aiBattleTimeLimitMs = 20
            val user = User(cs, 1, "ai-timeout-${System.nanoTime()}")
            Room.slots[0].user = user
            Room.startGame()
            Room.checkAiBattleTimeout(Long.MAX_VALUE)
            assertFalse(Room.gameOverSent, "Loading is not playing time")
            Room.onBoardReady()
            Thread.sleep(30)
            Room.onBoardReady()
            Room.checkAiBattleTimeout()
            assertTrue(Room.gameOverSent, "Repeated ready must not reset the countdown")
            val packets = collectSent()
            val body = encode(packets.single { opcodeOf(it) == 150 })
            try {
                assertEquals(1, body.Decode1, "Preserve the normal battle result document type")
                assertEquals(1, body.Decode1, "Opponent wins when the challenge expires")
                body.skipBytes(9)
                assertEquals(1, body.Decode1)
                body.Decode4
                assertEquals(2, body.Decode1, "Timeout must use the verified loss enum")
            } finally { body.release() }
            assertEquals(bnbemu.server.LocalEconomy.walletLimit,user.lucci)
            assertEquals(25000,bnbemu.server.LocalShop.store.account(user.accountKey).totalExperience)
            repeat(30) { Room.checkAiBattleTimeout(Long.MAX_VALUE) }
            assertEquals(bnbemu.server.LocalEconomy.walletLimit,bnbemu.server.LocalShop.store.account(user.accountKey).lucci)
            ch.outboundMessages().clear()
            Room.localGameMode = 15
            Room.startGame()
            Room.onBoardReady()
            Room.checkAiBattleTimeout(Long.MAX_VALUE)
            Room.checkMonsterTimeout(Long.MAX_VALUE)
            assertFalse(Room.gameOverSent, "Practice must stay untimed")
        } finally { Room.localGameMode = oldMode; Room.aiBattleTimeLimitMs = oldLimit }
    }

    @Test fun realBattleSettlementCreditsOnlyOnceAndUsesPersistedRewardsInResultPacket() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 16
            val user = User(cs, 1, "economy-round-${System.nanoTime()}")
            bnbemu.server.LocalShop.store.purchase(user.accountKey, bnbemu.packet.ShopItemEntry(9,14,"spend",itemId=9,tail=bnbemu.packet.ShopItemTail(price1=2995000)))
            Room.slots[0].user = user
            Room.startGame()
            Room.onLastTeamStanding(0)
            val result = collectSent().single { opcodeOf(it) == 150 }
            val body = encode(result)
            try {
                body.skipBytes(11)
                assertEquals(1, body.Decode1)
                body.Decode4; body.Decode1; repeat(3) { body.Decode1 }
                assertEquals(50000, body.Decode4)
                assertEquals(0, body.Decode1)
                assertEquals(100000, body.Decode4)
            } finally { body.release() }
            assertEquals(105000,user.lucci)
            assertEquals(44,user.level);assertEquals(550,user.exp)
            repeat(30) { Room.onLastTeamStanding(0) }
            assertEquals(105000,bnbemu.server.LocalShop.store.account(user.accountKey).lucci)
            ch.outboundMessages().clear()
            Room.startGame()
            Room.onLastTeamStanding(1)
            collectSent()
            val after = bnbemu.server.LocalShop.store.account(user.accountKey)
            assertEquals(155000,after.lucci)
            assertEquals(75000,after.totalExperience)
        } finally { Room.localGameMode = oldMode }
    }

    private fun assertImmediateMissileBurst(packets: List<PKT>, row: Int, column: Int, power: Int) {
        assertFalse(packets.any { opcodeOf(it)==183 },"S183 creates dropped items, not explosions")
        assertEquals(1,packets.count { opcodeOf(it)==139 })
        assertEquals(1,packets.count { opcodeOf(it)==138 })
        assertTrue(packets.indexOfFirst { opcodeOf(it)==139 } < packets.indexOfFirst { opcodeOf(it)==138 })
        val created=encode(packets.single { opcodeOf(it)==139 })
        val id: Int
        try {
            assertEquals(Room.monsterBombContext,created.Decode2)
            assertEquals(9,created.Decode1,"Use the native non-player owner")
            id=created.Decode4
            assertEquals(0,created.Decode4);assertEquals(0,created.Decode4)
            assertEquals(row,created.Decode1);assertEquals(column,created.Decode1)
            assertEquals(1,created.Decode1);assertEquals(power,created.Decode1)
            assertEquals(id,created.Decode2);assertEquals(0,created.Decode2);assertEquals(0,created.Decode1)
            assertEquals(0,created.readableBytes())
        } finally { created.release() }
        val ignited=encode(packets.single { opcodeOf(it)==138 })
        try {
            assertEquals(1,ignited.Decode1);assertEquals(id,ignited.Decode2)
            assertEquals(0,ignited.Decode1);assertEquals(0,ignited.Decode4)
            assertEquals(0,ignited.readableBytes())
        } finally { ignited.release() }
        assertFalse(Room.bombs.any { it.id==id },"An instant burst must not remain scheduled to explode again")
    }

    @Test fun mechanicalMissileSelfDeathBeforeImpactDoesNotDiscardItsExplosionOrRepeatDeath() {
        val oldMode = Room.localGameMode
        fun handle(hex: String, handler: bnbemu.netty.PKTHandler) {
            val input = Unpooled.wrappedBuffer(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            try { with(handler) { input.handle(cs) } } finally { input.release() }
        }
        try {
            Room.localGameMode = 14
            for ((map,type,script) in listOf(Triple("MonRobot03",14,10), Triple("MonRobot13",15,101))) {
                Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == map }.key)
                Room.onBoardReady()
                val init = java.nio.ByteBuffer.allocate(24).apply {
                    for (v in listOf(-1,1,0,0,script,-1)) putInt(v xor 0x96CA5395.toInt())
                }.array()
                repeat(2) { // Native ids are reused by the next missile lifetime.
                    Room.onMonsterCreate(type,init,0); ch.outboundMessages().clear()
                    // Captured on 2026-11-15: C114(id64,damage1,self65535)
                    // precedes C117(id64,kind6,power1,row0,column5).
                    handle("a529a5685a96",bnbemu.handler.room.MonsterDamageReq)
                    assertEquals(1,drain().count { opcodeOf(it)==151 })
                    ch.outboundMessages().clear()
                    handle("a5295c5b5a5f",bnbemu.handler.room.MonsterAttackReq)
                    val packets=drain()
                    assertImmediateMissileBurst(packets,0,5,1)
                    assertEquals(0,packets.count { opcodeOf(it)==151 },"Do not request native death a second time")
                    ch.outboundMessages().clear()
                    handle("a5295c5b5a5f",bnbemu.handler.room.MonsterAttackReq)
                    assertTrue(drain().isEmpty(),"Repeated impact must not explode twice")
                    Room.onMonsterDestroyed(64,1);ch.outboundMessages().clear()
                    handle("a5295c5b5a5f",bnbemu.handler.room.MonsterAttackReq)
                    assertTrue(drain().isEmpty(),"A released missile must reject stale impacts")
                }
                Room.reset()
            }
        } finally { Room.reset();Room.localGameMode=oldMode }
    }

    @Test fun capturedMechanicalMissileImpactGetsNativeExplosionOnceForBothMissileTypes() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            for ((map, nativeType, scriptClass) in listOf(Triple("MonRobot03",14,10), Triple("MonRobot13",15,101))) {
                Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == map }.key)
                Room.onBoardReady(); ch.outboundMessages().clear()
                val init = java.nio.ByteBuffer.allocate(24)
                    .putInt(-1 xor 0x96CA5395.toInt()).putInt(1 xor 0x96CA5395.toInt())
                    .putInt(0x96CA5395.toInt()).putInt(0x96CA5395.toInt())
                    .putInt(scriptClass xor 0x96CA5395.toInt()).putInt(-1 xor 0x96CA5395.toInt()).array()
                Room.onMonsterCreate(nativeType, init, 0)
                ch.outboundMessages().clear()
                Room.onMonsterMissileImpact(64, 1, 99, 99)
                assertTrue(drain().isEmpty(), "Invalid board coordinates must not reach the native flame factory")
                val bytes = "a5295c5b5f53".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val input = Unpooled.wrappedBuffer(bytes)
                try { with(bnbemu.handler.room.MonsterAttackReq) { input.handle(cs) } } finally { input.release() }
                assertImmediateMissileBurst(drain(),5,9,1)
                ch.outboundMessages().clear()
                Room.onMonsterMissileImpact(64, 1, 9, 5)
                assertTrue(drain().isEmpty(), "Repeated collision reports must not create repeated explosions")
                Room.onMonsterDestroyed(64, 1)
                assertEquals(1, drain().count { opcodeOf(it) == 179 })
                Room.reset()
            }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun capturedTurtleShellKickGetsItsNativeRollAcknowledgementOnce() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonTurtle01" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val bytes = "a5605896ca51a596ca53c56935ac6a6935ac6aa5".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            repeat(2) { attempt ->
                val input = Unpooled.wrappedBuffer(bytes)
                try { with(bnbemu.handler.room.MonsterKickReq) { input.handle(cs) } } finally { input.release() }
                if (attempt == 0) {
                    val packet = encode(drain().single { opcodeOf(it) == 151 })
                    try {
                        assertEquals(14, packet.Decode2); assertEquals(9, packet.Decode2); assertEquals(22, packet.Decode1)
                        assertEquals(2, packet.Decode1); assertEquals(560, packet.Decode4); assertEquals(80, packet.Decode4)
                        assertEquals(-1, packet.Decode4); assertEquals(-1, packet.Decode4); assertEquals(0, packet.Decode1)
                        assertEquals(0, packet.readableBytes())
                    } finally { packet.release() }
                    ch.outboundMessages().clear()
                } else assertTrue(drain().isEmpty(), "Do not restart a rolling shell for a retransmission")
            }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun turtle01ClearsAfterPurpleTargetsWhileRespawningRedActorsRemainAlive() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            val stage = bnbemu.server.LocalMonsterStages.stages.getValue("MonTurtle01")
            assertFalse(stage.monsters.getValue(0).influencesClear)
            assertFalse(stage.monsters.values.first { it.scriptClass == 11 }.influencesClear)
            assertTrue(stage.monsters.getValue(4).influencesClear)
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == stage.name }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val init = java.nio.ByteBuffer.allocate(24)
                .putInt(0x96CA5395.toInt()).putInt(1 xor 0x96CA5395.toInt())
                .putInt(0x96CA5395.toInt()).putInt(0x96CA5395.toInt())
                .putInt(11 xor 0x96CA5395.toInt()).putInt(-1 xor 0x96CA5395.toInt()).array()
            Room.onMonsterCreate(19, init, 0)
            assertEquals(1, drain().count { opcodeOf(it) == 177 })
            ch.outboundMessages().clear()
            val targets = stage.objectives.sorted()
            assertEquals(24, targets.size)
            for (id in targets.dropLast(1)) {
                Room.onMonsterDamage(id, 1, 0); Room.onMonsterDestroyed(id, 1)
            }
            assertFalse(drain().any { opcodeOf(it) in listOf(150,169) }, "The last real target is still alive")
            ch.outboundMessages().clear()
            Room.onMonsterDamage(targets.last(), 1, 0); Room.onMonsterDestroyed(targets.last(), 1)
            assertEquals(1, drain().count { opcodeOf(it) == 150 }, "Initial reds and dynamic red64 must not block the second board")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun turtle02ReusedNetworkSlotsDoNotKeepObjectivesFromDeadPurpleActors() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            val stage = bnbemu.server.LocalMonsterStages.stages.getValue("MonTurtle02")
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == stage.name }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            fun create(scriptClass: Int) {
                val init = java.nio.ByteBuffer.allocate(24)
                    .putInt(0x96CA5395.toInt()).putInt(1 xor 0x96CA5395.toInt())
                    .putInt(0x96CA5395.toInt()).putInt(0x96CA5395.toInt())
                    .putInt(scriptClass xor 0x96CA5395.toInt()).putInt(-1 xor 0x96CA5395.toInt()).array()
                Room.onMonsterCreate(19, init, 0)
            }
            // Captured board two creates a purple replacement in slot 64,
            // destroys it, then reuses that same slot for a recurring red actor.
            create(1081)
            val targets = stage.objectives.sorted()
            for (id in targets.dropLast(1)) {
                Room.onMonsterDamage(id, 1, 0); Room.onMonsterDestroyed(id, 1)
            }
            assertFalse(drain().any { opcodeOf(it) == 150 }, "Live purple actors still block clear")
            Room.onMonsterDamage(64, 1, 0); Room.onMonsterDestroyed(64, 1)
            create(12)
            ch.outboundMessages().clear()
            Room.onMonsterDamage(targets.last(), 1, 0); Room.onMonsterDestroyed(targets.last(), 1)
            assertEquals(1, drain().count { opcodeOf(it) == 150 }, "A recycled red slot must not retain the previous purple objective")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun turtle02TerminalPurpleDeathCompletesWithoutAnObjectDeletionRequest() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            val stage = bnbemu.server.LocalMonsterStages.stages.getValue("MonTurtle02")
            val replacement = stage.monsters.values.single { it.scriptClass == 1081 }
            assertTrue(replacement.settlesOnDefeat)
            assertFalse(stage.monsters.values.single { it.scriptClass == 108 }.settlesOnDefeat)
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == stage.name }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val init = java.nio.ByteBuffer.allocate(24)
                .putInt(0x96CA5395.toInt()).putInt(1 xor 0x96CA5395.toInt())
                .putInt(0x96CA5395.toInt()).putInt(0x96CA5395.toInt())
                .putInt(1081 xor 0x96CA5395.toInt()).putInt(-1 xor 0x96CA5395.toInt()).array()
            Room.onMonsterCreate(19, init, 0)
            for (id in stage.objectives) {
                Room.onMonsterDamage(id, 1, 0); Room.onMonsterDestroyed(id, 1)
            }
            assertFalse(drain().any { opcodeOf(it) == 150 }, "The script-created purple is still alive")
            ch.outboundMessages().clear()
            Room.onMonsterDamage(64, 1, 0)
            assertEquals(1, drain().count { opcodeOf(it) == 150 }, "Native terminal starfish do not always send C137 after confirmed death")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun turtleSecondBoardUsesNativeTransitionWithoutReturningToRoomOrRefillingItems() {
        val oldMode = Room.localGameMode
        val oldMap = Room.mapId
        try {
            Room.localGameMode = 14
            val maps = bnbemu.handler.room.mapIdTable
            val selected = maps.entries.single { it.value.name == "MonTurtle01" }.key
            val stage = bnbemu.server.LocalMonsterStages.stages.getValue("MonTurtle02")
            val boss = maps.entries.single { it.value.name == "MonTurtle03" }.key
            Room.mapId = selected
            Room.startGame(maps.entries.single { it.value.name == stage.name }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            for (id in stage.objectives) {
                Room.onMonsterDamage(id, 1, 0); Room.onMonsterDestroyed(id, 1)
            }
            assertEquals(1, drain().count { opcodeOf(it) == 150 })
            val deadline = System.currentTimeMillis() + 8000
            // startGame updates activeMapId before the same task sends S169.
            // Wait for the transition packet as well, not just the early field write.
            while (System.currentTimeMillis() < deadline &&
                (Room.activeMapId != boss || drain().count { opcodeOf(it) == 169 } < 2)) Thread.sleep(10)
            assertEquals(boss, Room.activeMapId)
            assertEquals(selected, Room.mapId, "Stage advancement must retain the user's first-board selection")
            val ops = drain().map { opcodeOf(it) }
            assertFalse(155 in ops || 112 in ops, "Do not return to or reconstruct the room between turtle boards")
            assertFalse(136 in ops, "Wait for the client to acknowledge native map loading")
            assertEquals(2, ops.count { it == 169 }, "Settlement followed by native mode-2 board replacement")
            Room.onMonsterBoardLoaded(cs)
            assertEquals(1, drain().count { opcodeOf(it) == 136 })
            ch.outboundMessages().clear()
            Room.onMonsterBoardLoaded(cs)
            assertTrue(drain().isEmpty(), "Duplicate load acknowledgement must not initialize the board again")
            assertFalse(198 in ops || 274 in ops, "Native board advancement must not refill inventory")
        } finally { Room.reset(); Room.localGameMode = oldMode; Room.mapId = oldMap }
    }

    @Test fun turtle06RequiresAllSixGhostsAndIgnoresRecurringRedActors() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            val stage = bnbemu.server.LocalMonsterStages.stages.getValue("MonTurtle16")
            assertEquals((12..17).toSet(), stage.objectives)
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == stage.name }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            for (id in 0..11) { Room.onMonsterDamage(id, 1, 0); Room.onMonsterDestroyed(id, 1) }
            assertFalse(drain().any { opcodeOf(it) == 150 }, "Red actors are not the clear objectives")
            for (id in listOf(12,13,16,17)) { Room.onMonsterDamage(id, 1, 0); Room.onMonsterDestroyed(id, 1) }
            assertFalse(drain().any { opcodeOf(it) == 150 }, "The two central ghosts are still alive, as in the captured run")
            Room.onMonsterDamage(14, 1, 0); Room.onMonsterDestroyed(14, 1)
            assertFalse(drain().any { opcodeOf(it) == 150 })
            Room.onMonsterDamage(15, 1, 0); Room.onMonsterDestroyed(15, 1)
            assertEquals(1, drain().count { opcodeOf(it) == 150 }, "All six ghosts have now been defeated")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun capturedShellCollisionBaseAndSizeGoToTheirActualNativeFields() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonTurtle01" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            for ((raw, expected) in listOf(
                "a56ba561a56ba56a" to listOf(11, 2, 3),
                "a56ba560a54aa548" to listOf(12, 35, 33)
            )) {
                val input = Unpooled.wrappedBuffer(raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                try { with(bnbemu.handler.room.MonsterStateReq) { input.handle(cs) } } finally { input.release() }
                val packet = encode(drain().single { opcodeOf(it) == 151 })
                try {
                    assertEquals(14, packet.Decode2); assertEquals(2, packet.Decode2)
                    assertEquals(expected[0], packet.Decode1)
                    assertEquals(expected[1], packet.Decode2); assertEquals(expected[2], packet.Decode2)
                    assertEquals(0, packet.readableBytes())
                } finally { packet.release() }
                ch.outboundMessages().clear()
            }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun redAndPurpleShellKicksPreserveAllFourDirectionalEndpoints() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonTurtle01" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val targets = listOf(200 to 200, 240 to 160, 280 to 200, 240 to 240)
            for (id in listOf(0, 9)) for ((direction, target) in targets.withIndex()) {
                val input = encode(PKT { out -> with(out) {
                    Encode2(id); Encode1(direction)
                    Encode4(240); Encode4(200); Encode4(target.first); Encode4(target.second); Encode1(0)
                } })
                try { with(bnbemu.handler.room.MonsterKickReq) { input.handle(cs) } } finally { input.release() }
                val out = encode(drain().single { opcodeOf(it) == 151 })
                try {
                    assertEquals(14, out.Decode2); assertEquals(id, out.Decode2); assertEquals(22, out.Decode1)
                    assertEquals(direction, out.Decode1)
                    assertEquals(listOf(240, 200, target.first, target.second), List(4) { out.Decode4 }, "id=$id direction=$direction")
                    assertEquals(0, out.Decode1); assertEquals(0, out.readableBytes())
                } finally { out.release() }
                ch.outboundMessages().clear()
            }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun sealFallingBombIsSquareAndIgnitesOnItsNativeImpact() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonSeal03" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            Room.onMonsterCreate(9, ByteArray(24), 0)
            ch.outboundMessages().clear()
            Room.onMonsterProjectileImpact(64, 1, 6, 7)
            val packets = drain()
            assertEquals(listOf(179, 139, 138), packets.map { opcodeOf(it) })
            val body = encode(packets.single { opcodeOf(it) == 139 })
            try {
                body.Decode2; body.Decode1; repeat(3) { body.Decode4 };body.Decode1;body.Decode1
                assertEquals(1, body.Decode1, "Sea Emperor uses the native square explosion attribute")
                assertEquals(1, body.Decode1, "National script radius must not gain an extra tile")
            } finally { body.release() }
            assertFalse(Room.bombs.any { it.ownerSlotId == 9 }, "An impact must not leave a 2.5-second fuse")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun kickedShellCanBeFrozenAgainInsteadOfLosingItsNextLockAcknowledgement() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonTurtle01" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            Room.onMonsterFreeze(0, 100, 100)
            val payload = "5896ca51a596ca53c56935ac6a6935ac6aa5".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Room.onMonsterKick(0, payload, cs)
            ch.outboundMessages().clear()
            Room.onMonsterFreeze(0, 120, 100)
            assertEquals(1, drain().count { opcodeOf(it) == 151 }, "A rolled shell must accept its next freeze")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun robotMergeWaitsForBothHalvesThenUsesNativeCombineAndTransformEvents() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonRobot18" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            Room.onRobotReady(0, 2)
            Room.onRobotReady(0, 1, 4)
            assertTrue(drain().isEmpty())
            Room.onRobotReady(1, 1, 4)
            val merge = encode(drain().single { opcodeOf(it) == 151 })
            try {
                assertEquals(14, merge.Decode2); assertEquals(0, merge.Decode2)
                assertEquals(15, merge.Decode1); assertEquals(8, merge.Decode1)
                assertEquals(0, merge.readableBytes())
            } finally { merge.release() }
            ch.outboundMessages().clear()
            Room.onRobotReady(0, 2)
            Room.onRobotReady(0, 1, 1) // A late first-phase duplicate must not undo phase two.
            assertTrue(drain().isEmpty())
            Room.onRobotReady(1, 2)
            val transform = encode(drain().single { opcodeOf(it) == 151 })
            try {
                assertEquals(14, transform.Decode2); assertEquals(0, transform.Decode2)
                assertEquals(16, transform.Decode1); assertEquals(0, transform.readableBytes())
            } finally { transform.release() }
            ch.outboundMessages().clear()
            Room.onMonsterNativeState(2, 14, byteArrayOf(0x5b))
            assertEquals(1, drain().count { opcodeOf(it) == 151 })
            ch.outboundMessages().clear()
            Room.onRobotReady(1, 2)
            assertTrue(drain().isEmpty(), "Duplicate readiness cannot replay transform")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun capturedRobotLaserUsesNativeEffectInsteadOfInventedBombs() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonRobot03" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val input = Unpooled.wrappedBuffer("a568565d53585858585996ca5649".chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            try { with(bnbemu.handler.room.MonsterLaserReq) { input.handle(cs) } } finally { input.release() }
            val out = encode(drain().single { opcodeOf(it) == 268 })
            try {
                assertEquals(9, out.Decode1); assertEquals(12, out.Decode1); assertEquals(7, out.Decode1)
                assertEquals(2, out.Decode1); assertEquals(2, out.Decode1); assertEquals(2, out.Decode1)
                assertEquals(2, out.Decode1); assertEquals(3, out.Decode1); assertEquals(1500, out.Decode4)
                assertEquals(0, out.readableBytes())
            } finally { out.release() }
            assertTrue(Room.bombs.isEmpty(), "Native laser owns its collision and lifetime")
            assertEquals("MonsterLaserReq", OpcodeManager.recvOps[155])
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun robotPartsSynchronizeHealthAndAngerBeforeCombination() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonRobot18" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            Room.onMonsterDamage(0, 1, 0)
            val hp = encode(drain().single { opcodeOf(it) == 151 })
            try {
                assertEquals(14, hp.Decode2); assertEquals(0, hp.Decode2)
                assertEquals(2, hp.Decode1); assertEquals(5, hp.Decode2)
            } finally { hp.release() }
            ch.outboundMessages().clear()
            Room.onMonsterDamage(0, 1, 0)
            assertEquals(2, drain().count { opcodeOf(it) == 151 }, "HP4 and anger must reach the native combine script")
            assertFalse(drain().any { opcodeOf(it) in listOf(150, 169) })
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun capturedSealContactWithUnsetNativePlayerIndexPopsLockedBoss() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonSeal03" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val boss = bnbemu.server.LocalMonsterStages.stages.getValue("MonSeal03").monsters.values.first { it.boss }
            Room.onMonsterDamage(boss.id, boss.hp, 0)
            ch.outboundMessages().clear()
            val input = Unpooled.buffer().writeShort(boss.id xor 0xa569).writeShort(0x5a96)
            try { with(bnbemu.handler.room.MonsterFinishReq) { input.handle(cs) } } finally { input.release() }
            assertEquals(1, drain().count { opcodeOf(it) == 151 })
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun impactFusedBombLandsBeforeImmediateIgnitionButNormalTankKeepsItsFuse() {
        val instant = bnbemu.server.Bomb(1, 9, 0, 1, false, isMoving = true, expireAt = 100).apply { landingFuseMs = 0 }
        assertTrue(instant.updateState(100)); assertEquals(1, instant.state)
        assertTrue(instant.updateState(100)); assertEquals(0, instant.state)
        val normal = bnbemu.server.Bomb(2, 0, 0, 0, false, isMoving = true, expireAt = 100)
        assertTrue(normal.updateState(100)); assertEquals(1, normal.state)
        assertFalse(normal.updateState(101)); assertEquals(2100, normal.expireAt)
    }

    @Test fun capturedPenguin03BossRectangleCannotCreateExplosionsOutsideThirteenRows() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonPen09" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            assertEquals(bnbemu.server.LocalMapBounds(15, 13), bnbemu.server.LocalMapBounds.forBoard("MonPen09"))
            // Actual last attack preceding 0x562688: C117  a5 68 58 59 50 52.
            val input = Unpooled.wrappedBuffer("a56858595052".chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            try { with(bnbemu.handler.room.MonsterAttackReq) { input.handle(cs) } } finally { input.release() }
            val attacks = drain().filter { opcodeOf(it) == 139 }
            assertEquals(17, attacks.size, "Discard the seven out-of-board cells in row 13")
            for (packet in attacks) {
                val body = encode(packet)
                try {
                    body.Decode2; assertEquals(9, body.Decode1)
                    repeat(3) { body.Decode4 }
                    val row = body.Decode1; val column = body.Decode1
                    assertTrue(row in 0..12 && column in 0..14, "Native tile lookup must never return null")
                } finally { body.release() }
            }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun penguin03WaveGeneratorCanFinishItsDeclaredSelfDamageWithoutBeingFrozen() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonPen08" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            Room.onMonsterDamage(0, 2, 65535)
            assertTrue(drain().isEmpty(), "The script declares only one point of self-damage")
            Room.onMonsterDamage(0, 1, 65535)
            assertEquals(1, drain().count { opcodeOf(it) == 151 })
            ch.outboundMessages().clear()
            Room.onMonsterDamage(0, 1, 65535)
            assertTrue(drain().isEmpty(), "Script death is acknowledged once")
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonPen01" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val ice = bnbemu.server.LocalMonsterStages.stages.getValue("MonPen01").monsters.values.first { it.type == "iceMon" }
            Room.onMonsterDamage(ice.id, 1, 65535)
            assertTrue(drain().isEmpty(), "Ordinary ice monsters still require freezing and contact")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun penguin03CannotClearWhileItsReplacementMonstersRemainAlive() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            val stage = bnbemu.server.LocalMonsterStages.stages.getValue("MonPen07")
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == stage.name }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val init = java.nio.ByteBuffer.allocate(24)
                .putInt(-1 xor 0x96CA5395.toInt()).putInt(1 xor 0x96CA5395.toInt())
                .putInt(0x96CA5395.toInt()).putInt(0x96CA5395.toInt())
                .putInt(101 xor 0x96CA5395.toInt()).putInt(-1 xor 0x96CA5395.toInt()).array()
            repeat(3) { Room.onMonsterCreate(5, init, 0) }
            assertEquals(3, drain().count { opcodeOf(it) == 177 })
            ch.outboundMessages().clear()
            for (id in stage.objectives) {
                if (stage.monsters.getValue(id).type == "iceMon") Room.onMonsterFreeze(id, 0, 0)
                Room.onMonsterDamage(id, 1, 0); Room.onMonsterDestroyed(id, 1)
            }
            assertFalse(drain().any { opcodeOf(it) in listOf(169, 150) }, "Three replacement monsters are still on the board")
            for (id in 64..65) {
                Room.onMonsterFreeze(id, 0, 0); Room.onMonsterDamage(id, 1, 0); Room.onMonsterDestroyed(id, 1)
            }
            assertFalse(drain().any { opcodeOf(it) in listOf(169, 150) })
            Room.onMonsterFreeze(66, 0, 0); Room.onMonsterDamage(66, 1, 0); Room.onMonsterDestroyed(66, 1)
            assertEquals(1, drain().count { opcodeOf(it) == 150 })
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun capturedGloveThrowMustLandBeforeItCanExplode() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 15
            Room.startGame(); ch.outboundMessages().clear()
            Room.addBomb(0, 6, 1, bombId = 350, broadcastInitialState = false)
            val deadline = System.currentTimeMillis() + 1500
            while (Room.getBomb(350) == null && System.currentTimeMillis() < deadline) Thread.sleep(2)
            val raw = "5aa4375c5b5c58ad3e".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val input = Unpooled.wrappedBuffer(raw)
            try { with(bnbemu.handler.room.BombKickThrowReq) { input.handle(cs) } } finally { input.release() }
            while (drain().none { opcodeOf(it) == 141 } && System.currentTimeMillis() < deadline) Thread.sleep(2)
            val bomb = Room.getBomb(350)!!
            assertTrue(bomb.isMoving, "Glove direction zero also needs a landing state")
            assertTrue(bomb.updateState(bomb.expireAt)); assertEquals(1, bomb.state)
            assertTrue(bomb.updateState(bomb.expireAt)); assertEquals(0, bomb.state)
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun capturedShoeKickUsesTheEnRequestAndResponseOpcodes() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "Monster25" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            Room.addBomb(0, 11, 10, bombId = 10, broadcastInitialState = false)
            val deadline = System.currentTimeMillis() + 1000
            while (Room.getBomb(10) == null && System.currentTimeMillis() < deadline) Thread.sleep(2)
            val bytes = "1aa5635150515aa681".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val input = Unpooled.wrappedBuffer(bytes)
            try { with(bnbemu.handler.room.BombKickThrowReq) { input.handle(cs) } } finally { input.release() }
            while (drain().none { opcodeOf(it) == 141 } && System.currentTimeMillis() < deadline) Thread.sleep(2)
            val packet = encode(drain().single { opcodeOf(it) == 141 })
            try {
                assertEquals(0x40, packet.Decode1); assertEquals(10, packet.Decode2)
                assertEquals(11, packet.Decode1); assertEquals(10, packet.Decode1)
                assertEquals(11, packet.Decode1); assertEquals(0, packet.Decode1)
                assertEquals(0, packet.readableBytes())
            } finally { packet.release() }
            assertEquals("BombKickThrowReq", OpcodeManager.recvOps[107])
            assertTrue(Room.getBomb(10)?.isMoving == true)
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun sealRollAndTurtleShellRepliesPreserveTheirNativePayloads() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            for ((map, request, event) in listOf(Triple("MonSeal03", 2, 7), Triple("MonTurtle03", 11, 14))) {
                Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == map }.key)
                Room.onBoardReady(); ch.outboundMessages().clear()
                val payload = encode(PKT {
                    if (request == 2) { it.Encode4(123); it.Encode2(6); it.Encode2(3); it.Encode2(2) }
                    else { it.Encode1(2); it.Encode1(0) }
                })
                val raw = ByteArray(payload.readableBytes()); payload.readBytes(raw); payload.release()
                Room.onMonsterNativeState(0, request, raw)
                val packet = encode(drain().single { opcodeOf(it) == 151 })
                try {
                    assertEquals(14, packet.Decode2); assertEquals(0, packet.Decode2); assertEquals(event, packet.Decode1)
                    val echoed = ByteArray(packet.readableBytes()); packet.readBytes(echoed)
                    org.junit.jupiter.api.Assertions.assertArrayEquals(raw, echoed)
                } finally { packet.release() }
                Room.reset()
            }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun capturedCrocodileShrinkAndReleaseRequestsHaveNativeAcknowledgements() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonSeal01" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            for ((request, event) in listOf(6 to 9, 7 to 10)) {
                val input = encode(PKT { it.Encode2(0); it.Encode2(request) })
                try { with(bnbemu.handler.room.MonsterStateReq) { input.handle(cs) } } finally { input.release() }
                val response = encode(drain().last { opcodeOf(it) == 151 })
                try {
                    assertEquals(14, response.Decode2); assertEquals(0, response.Decode2)
                    assertEquals(event, response.Decode1); assertEquals(0, response.readableBytes())
                } finally { response.release() }
            }
            assertTrue(drain().none { opcodeOf(it) == 150 }, "Shrinking is not a death or a stage clear")
            Room.onMonsterDamage(0, 1, 0)
            assertTrue(drain().any { opcodeOf(it) == 151 })
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun allMonsterStagesCanClearUsingTheCompactInitialNativeObjectIds() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            for ((name, stage) in bnbemu.server.LocalMonsterStages.stages) {
                val initial = stage.monsters.values.filter { it.initial }
                assertEquals((0 until initial.size).toList(), initial.map { it.id }, name)
                assertTrue(stage.objectives.isNotEmpty(), name)
                Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == name }.key)
                Room.onBoardReady(); ch.outboundMessages().clear()
                for (monster in initial) {
                    if (monster.type in setOf("iceMon", "starFishMon")) Room.onMonsterFreeze(monster.id, 0, 0)
                    Room.onMonsterDamage(monster.id, monster.hp, 0)
                    if (monster.boss) Room.onMonsterFinish(monster.id, 0)
                    Room.onMonsterDestroyed(monster.id, 1)
                }
                val deadline = System.currentTimeMillis() + 1000
                while (System.currentTimeMillis() < deadline && drain().none { opcodeOf(it) == 150 }) Thread.sleep(2)
                assertEquals(1, drain().count { opcodeOf(it) == 150 }, name)
                Room.reset(); ch.outboundMessages().clear()
            }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun capturedTankShotPreservesItsFlightDestinationAndDirection() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "Monster25" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val bytes = "a56b5a96ca53a750515a78565159a2b9".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val input = Unpooled.wrappedBuffer(bytes)
            try { with(bnbemu.handler.room.SetBomberEventReq) { input.handle(cs) } } finally { input.release() }
            val packet = encode(drain().single { opcodeOf(it) == 139 })
            try {
                assertEquals(2, packet.Decode2); assertEquals(0, packet.Decode1)
                assertEquals(50, packet.Decode4); packet.Decode4; packet.Decode4
                assertEquals(10, packet.Decode1); assertEquals(11, packet.Decode1)
                assertEquals(0, packet.Decode1); assertEquals(0x22, packet.Decode1)
                assertEquals(12, packet.Decode1); assertEquals(11, packet.Decode1); assertEquals(3, packet.Decode1)
                assertEquals(50, packet.Decode2); assertEquals(0, packet.Decode2); assertEquals(0, packet.Decode1)
                assertEquals(0, packet.readableBytes())
            } finally { packet.release() }
            val deadline = System.currentTimeMillis() + 1000
            while (Room.getBomb(50) == null && System.currentTimeMillis() < deadline) Thread.sleep(2)
            assertTrue(Room.getBomb(50)?.isMoving == true)
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun allMonsterBoardTransitionsUseTheClientsNativeMapIds() {
        val nativeIds = checkNotNull(javaClass.getResourceAsStream("/native-map-index.tsv"))
            .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { val parts = it.split('\t'); parts[1] to parts[0].toInt() }
        assertEquals(33, nativeIds.getValue("Cemetery21"))
        assertEquals(86, nativeIds.getValue("MonPen20"))
        val stages = bnbemu.server.LocalMonsterStages.stages
        assertEquals(123, stages.size)
        for (name in stages.keys) {
            val packet = encode(bnbemu.packet.room.MonsterNextBoard(name, 123))
            try {
                assertEquals(2, packet.Decode1); assertEquals(123, packet.Decode4)
                assertEquals(nativeIds.getValue(name), packet.Decode2, "Native map id for $name")
                assertEquals(0, packet.Decode2); assertEquals(0, packet.Decode1)
                assertEquals(0, packet.readableBytes())
            } finally { packet.release() }
        }
    }

    @Test fun capturedMapAttackCreatesOneImmediateRowInsteadOfFlyingBombs() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "Monster06" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val req = encode(PKT { out -> out.Encode2(0); out.Encode1(1)
                out.Encode1(14); out.Encode1(2); out.Encode1(5); out.Encode1(0) })
            try { with(bnbemu.handler.room.MonsterAttackReq) { req.handle(cs) } } finally { req.release() }
            val packets = drain()
            val cells = packets.filter { opcodeOf(it) == 139 }
            assertEquals(15, cells.size)
            val ids = cells.mapIndexed { column, p ->
                val packet = encode(p)
                try {
                    assertEquals(2, packet.Decode2); assertEquals(9, packet.Decode1)
                    val id = packet.Decode4; packet.Decode4; packet.Decode4
                    assertEquals(5, packet.Decode1); assertEquals(column, packet.Decode1)
                    assertEquals(1, packet.Decode1); assertEquals(0, packet.Decode1)
                    assertEquals(id, packet.Decode2)
                    id
                } finally { packet.release() }
            }
            val explosion = encode(packets.single { opcodeOf(it) == 138 })
            try {
                assertEquals(15, explosion.Decode1)
                for (id in ids) {
                    assertEquals(id, explosion.Decode2); assertEquals(0, explosion.Decode1)
                    explosion.Decode4
                }
            } finally { explosion.release() }
            assertEquals(138, opcodeOf(packets.last()), "Ignite immediately after all cells are initialized")
            assertTrue(Room.bombs.none { it.ownerSlotId == 9 }, "No delayed or flying bubbles may remain")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun scriptedDirectionalWaterLinesRespectDirectionAndBoardEdges() {
        val row = (0..14).map { it to 5 }
        assertEquals(row, Room.monsterDirectionalCells(0, 5, 2, 14))
        assertEquals(row.reversed(), Room.monsterDirectionalCells(14, 5, 0, 14))
        assertEquals(listOf(7 to 1, 7 to 0), Room.monsterDirectionalCells(7, 1, 1, 14))
        assertEquals(listOf(7 to 13, 7 to 14), Room.monsterDirectionalCells(7, 13, 3, 14))
    }

    @Test fun scriptedFallingProjectileUsesNativeCreateAndOneLandingBomb() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "Monster06" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val init = ByteArray(24) { (it * 7 + 3).toByte() }
            Room.onMonsterCreate(9, init, 0)
            val create = encode(drain().single())
            try {
                assertEquals(177, opcodeOf(drain().single()))
                assertEquals(64, create.Decode2); assertEquals(0, create.Decode1)
                assertEquals(9, create.Decode2)
                val actual = ByteArray(24); create.readBytes(actual)
                org.junit.jupiter.api.Assertions.assertArrayEquals(init, actual)
            } finally { create.release() }
            ch.outboundMessages().clear()
            val impact = encode(PKT { out -> out.Encode2(64); out.Encode1(4)
                out.Encode1(2); out.Encode1(7); out.Encode1(6) })
            try { with(bnbemu.handler.room.MonsterAttackReq) { impact.handle(cs) } } finally { impact.release() }
            assertEquals(1, drain().count { opcodeOf(it) == 139 })
            assertEquals(1, drain().count { opcodeOf(it) == 179 })
            assertEquals(1, Room.bombs.count { it.ownerSlotId == 9 && it.gridX == 6 && it.gridY == 7 })
            ch.outboundMessages().clear()
            Room.onMonsterProjectileImpact(64, 1, 6, 7)
            assertTrue(drain().isEmpty(), "A duplicate landing cannot emit another bomb")
            Room.onMonsterCreate(9, init, 0)
            val reused = encode(drain().single())
            try { assertEquals(64, reused.Decode2) } finally { reused.release() }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun rectangleWavesContainOnlyTheirScriptedBorder() {
        for (radius in 3..9) {
            val points = Room.monsterRectangleBorder(7, 6, radius)
            assertEquals(points.size, points.toSet().size)
            assertFalse(points.contains(7 to 6))
            assertTrue(points.all { (x, y) -> x in 0..14 && y in 0..14 &&
                maxOf(kotlin.math.abs(x - 7), kotlin.math.abs(y - 6)) == radius })
        }
        assertEquals(24, Room.monsterRectangleBorder(7, 6, 3).size)
        assertEquals(setOf(0 to 3, 1 to 3, 2 to 3, 3 to 3, 3 to 2, 3 to 1, 3 to 0),
            Room.monsterRectangleBorder(0, 0, 3).toSet())
    }

    @Test fun monsterBombsFollowTheActualPlayerContext() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "Monster03" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            assertEquals(2, Room.monsterBombContext)
            Room.observeBombContext(0, 1, ClientSocket())
            assertEquals(2, Room.monsterBombContext, "Unrelated connections cannot change the context")
            Room.observeBombContext(0, 1, cs)
            Room.onMonsterAttack(0, 2, 3, 0, 7, 6)
            drain().filter { opcodeOf(it) == 139 }.forEach { packet ->
                val b = encode(packet)
                try { assertEquals(1, b.Decode2); assertEquals(9, b.Decode1) } finally { b.release() }
            }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun trappedAndRescuedReportsDoNotEndMonsterMatch() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "Monster01" }.key)
            ch.outboundMessages().clear()
            for (action in listOf(2, 3)) {
                val req = encode(PKT { out -> out.Encode2(0); out.Encode2(action) })
                try { with(bnbemu.handler.room.BoxItemSpawnReport) { req.handle(cs) } } finally { req.release() }
                assertEquals(SlotMatchState.ALIVE, Room.slots[0].matchState)
                assertTrue(drain().isEmpty(), "Trap/rescue alone must not emit a death or result packet")
            }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun explicitMonsterRestartFromBossSelectionLoadsPartOne() {
        val oldMode = Room.localGameMode
        val oldMap = Room.mapId
        val oldTheme = Room.themeId
        val maps = bnbemu.handler.room.mapIdTable
        try {
            Room.localGameMode = 14
            for (family in listOf("Monster", "MonPen", "MonSeal", "MonTurtle", "MonRobot")) {
                val boss = maps.entries.single { it.value.name == "${family}03" }
                val first = maps.entries.single { it.value.name == "${family}01" }
                Room.mapId = boss.key; Room.themeId = boss.value.themeId
                bnbemu.handler.room.launchLocalMatch(cs, boss.key, boss.value.themeId)
                assertEquals(first.key, Room.activeMapId, family)
                assertEquals(first.key, Room.mapId, family)
                Room.reset(); Room.localGameMode = 14
            }
        } finally {
            Room.reset(); Room.localGameMode = oldMode
            Room.mapId = oldMap; Room.themeId = oldTheme
        }
    }

    @Test fun mixedPenguinMobsAndBossUseTheirOwnScriptHealth() {
        val oldMode = Room.localGameMode
        val maps = bnbemu.handler.room.mapIdTable
        try {
            Room.localGameMode = 14
            Room.startGame(maps.entries.single { it.value.name == "MonPen01" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            Room.onMonsterDamage(0, 1, 0)
            assertEquals(1, drain().count { opcodeOf(it) == 151 })
            ch.outboundMessages().clear()
            Room.onMonsterDamage(6, 1, 0)
            assertTrue(drain().isEmpty(), "Ice mobs still require freeze then contact")
            Room.startGame(maps.entries.single { it.value.name == "MonPen03" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            repeat(19) { Room.onMonsterDamage(0, 1, 0) }
            assertEquals(20, drain().count { opcodeOf(it) == 151 }, "19 HP updates and one anger event")
            val health = encode(drain().last())
            try {
                assertEquals(14, health.Decode2); assertEquals(0, health.Decode2)
                assertEquals(2, health.Decode1); assertEquals(1, health.Decode2)
                assertEquals(0, health.readableBytes())
            } finally { health.release() }
            ch.outboundMessages().clear()
            Room.onMonsterDamage(0, 1, 0)
            assertEquals(2, drain().count { opcodeOf(it) == 151 })
            Room.onMonsterDamage(0, 1, 0)
            assertEquals(2, drain().count { opcodeOf(it) == 151 })
            val trapped = encode(drain().last())
            try {
                assertEquals(14, trapped.Decode2); assertEquals(0, trapped.Decode2)
                assertEquals(1, trapped.Decode1); assertEquals(0, trapped.readableBytes())
            } finally { trapped.release() }
            Room.onMonsterFinish(0, 7)
            assertEquals(2, drain().count { opcodeOf(it) == 151 })
            Room.onMonsterFinish(0, 0)
            assertEquals(3, drain().count { opcodeOf(it) == 151 })
            Room.onMonsterFinish(0, 0)
            assertEquals(3, drain().count { opcodeOf(it) == 151 })
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun bossAngerUsesOriginalThresholdOnceAndSpeedRequestsAreAcknowledged() {
        val oldMode = Room.localGameMode
        try {
            val def = bnbemu.server.LocalMonsterStages.stages.getValue("MonPen03").monsters.getValue(0)
            assertEquals(6, def.angerHp)
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonPen03" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            Room.onMonsterDamage(0, def.hp - def.angerHp, 0)
            assertEquals(2, drain().size)
            val angry = encode(drain().last())
            try {
                assertEquals(14, angry.Decode2); assertEquals(0, angry.Decode2)
                assertEquals(0, angry.Decode1); assertEquals(0, angry.readableBytes())
            } finally { angry.release() }
            ch.outboundMessages().clear()
            Room.onMonsterDamage(0, 1, 0)
            assertEquals(1, drain().size, "Do not restart the anger script on every hit")
            ch.outboundMessages().clear()
            val req = encode(bnbemu.netty.PKT { out -> out.Encode2(0); out.Encode2(1); out.Encode1(7) })
            try { with(bnbemu.handler.room.MonsterStateReq) { req.handle(cs) } } finally { req.release() }
            val speed = encode(drain().single())
            try {
                assertEquals(14, speed.Decode2); assertEquals(0, speed.Decode2)
                assertEquals(6, speed.Decode1); assertEquals(7, speed.Decode1)
                assertEquals(0, speed.readableBytes())
            } finally { speed.release() }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun bothBossFamiliesHonorTheirScriptedAttackRequests() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "Monster03" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            Room.onMonsterAttack(0, 2, 3, 0, 7, 6)
            val packets = drain().filter { opcodeOf(it) == 139 }
            assertEquals(24, packets.size, "A native rectangle request must not invent an unrelated projectile")
            packets.forEach { packet ->
                val b = encode(packet)
                try {
                    assertEquals(2, b.Decode2); assertEquals(9, b.Decode1)
                    val token = b.Decode4; assertTrue(token in 0x6000..0x7fff)
                    assertEquals(0, b.Decode4); assertEquals(0, b.Decode4)
                    val y = b.Decode1; val x = b.Decode1
                    assertTrue((x to y) in Room.monsterRectangleBorder(7, 6, 3))
                    assertEquals(1, b.Decode1)
                    val flags = b.Decode1
                    assertEquals(0, flags and 15)
                    assertEquals(0, flags and 0x20, "Rectangle animation must not spawn a flying bubble")
                    assertEquals(token, b.Decode2)
                    assertEquals(0, b.Decode2); assertEquals(0, b.Decode1)
                    assertEquals(0, b.readableBytes())
                } finally { b.release() }
            }
            assertFalse(drain().any { opcodeOf(it) == 148 }, "Do not substitute terrain traps for explosions")
            ch.outboundMessages().clear()
            Room.onMonsterDamage(0, 1, 65535)
            assertTrue(drain().isEmpty(), "NPC water-flames cannot damage their own boss")
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonPen03" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            Room.onMonsterAttack(0, 1, 3, 2, 7, 6)
            assertEquals(3, drain().count { opcodeOf(it) == 139 }, "Penguin script directionDest must be honored too")
            ch.outboundMessages().clear()
            Room.onMonsterAttack(0, 2, 3, 0, 7, 6)
            assertEquals(24, drain().count { opcodeOf(it) == 139 }, "Penguin retains the border attack")
            Room.onMonsterDamage(0, 20, 0); ch.outboundMessages().clear()
            Room.onMonsterAttack(0, 2, 3, 0, 7, 6)
            assertTrue(drain().isEmpty(), "Trapped bosses cannot attack")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun capturedOctopusTargetedAttackProducesTravellingNpcBubble() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "Monster03" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val req = encode(bnbemu.netty.PKT { out ->
                out.Encode2(0); out.Encode1(3); out.Encode1(0x23)
                out.Encode1(12); out.Encode1(2); out.Encode1(3)
                out.Encode2(2000); out.Encode1(6); out.Encode1(2)
            })
            try { with(bnbemu.handler.room.MonsterAttackReq) { req.handle(cs) } } finally { req.release() }
            val movingBomb = Room.bombs.single { it.ownerSlotId == 9 }
            assertTrue(movingBomb.isMoving)
            val landingTime = movingBomb.expireAt
            assertFalse(movingBomb.updateState(landingTime - 1))
            assertTrue(movingBomb.updateState(landingTime))
            assertEquals(1, movingBomb.state, "S138 state 1 must land the projectile before it can explode")
            assertFalse(movingBomb.isMoving)
            assertFalse(movingBomb.shouldRemove())
            assertEquals(landingTime + 2000, movingBomb.expireAt)
            assertFalse(movingBomb.updateState(landingTime + 1999))
            assertTrue(movingBomb.updateState(landingTime + 2000))
            assertEquals(0, movingBomb.state)
            assertTrue(movingBomb.shouldRemove())
            val bubble = encode(drain().single { opcodeOf(it) == 139 })
            try {
                assertEquals(2, bubble.Decode2); assertEquals(9, bubble.Decode1)
                bubble.Decode4; bubble.Decode4; bubble.Decode4
                assertEquals(6, bubble.Decode1); assertEquals(2, bubble.Decode1)
                assertEquals(0, bubble.Decode1); assertEquals(0x24, bubble.Decode1)
                assertEquals(12, bubble.Decode1); assertEquals(2, bubble.Decode1)
                assertEquals(3, bubble.Decode1, "Direction must not be replaced by a speed value")
            } finally { bubble.release() }
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun bossSummonsUseSeparateIdsAndRetainScriptDefinition() {
        val oldMode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonPen12" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            val init = java.nio.ByteBuffer.allocate(24)
                .putInt(-1 xor 0x96CA5395.toInt()).putInt(1 xor 0x96CA5395.toInt())
                .putInt(0x96CA5395.toInt()).putInt(0x96CA5395.toInt())
                .putInt(101 xor 0x96CA5395.toInt()).putInt(-1 xor 0x96CA5395.toInt()).array()
            Room.onMonsterCreate(6, init, 7)
            assertTrue(drain().isEmpty(), "Unoccupied slots cannot control summons")
            Room.onMonsterCreate(6, init, 0)
            val created = encode(drain().single())
            try {
                assertEquals(177, opcodeOf(drain().single()))
                assertEquals(64, created.Decode2); assertEquals(0, created.Decode1)
                assertEquals(6, created.Decode2); assertEquals(24, created.readableBytes())
                val body = ByteArray(24); created.readBytes(body)
                org.junit.jupiter.api.Assertions.assertArrayEquals(init, body)
            } finally { created.release() }
            ch.outboundMessages().clear()
            Room.onMonsterDamage(64, 1, 0)
            assertEquals(1, drain().count { opcodeOf(it) == 151 }, "Summons must not become invulnerable")
            Room.onMonsterDestroyed(64, 1)
            ch.outboundMessages().clear()
            Room.onMonsterCreate(6, init, 0)
            val reused = encode(drain().single())
            try { assertEquals(64, reused.Decode2) } finally { reused.release() }
            ch.outboundMessages().clear()
            Room.onMonsterDamage(64, 1, 0)
            assertEquals(1, drain().size, "Reusing a released id must clear its old defeated state")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test fun firstStageClearReturnsThroughPrepareWithoutEndingTheRun() {
        val oldMode = Room.localGameMode
        val stages = bnbemu.server.LocalMonsterStages.stages
        assertEquals("MonPen02", stages.getValue("MonPen01").next)
        assertEquals("MonPen03", stages.getValue("MonPen02").next)
        assertEquals(null, stages.getValue("MonPen03").next)
        try {
            Room.localGameMode = 14
            Room.startGame(bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonPen01" }.key)
            Room.onBoardReady(); ch.outboundMessages().clear()
            for ((id, monster) in stages.getValue("MonPen01").monsters) {
                if (monster.type == "iceMon") Room.onMonsterFreeze(id, 0, 0)
                Room.onMonsterDamage(id, monster.hp, 0)
                Room.onMonsterDestroyed(id, 1)
            }
            val ops = drain().map { opcodeOf(it) }
            assertEquals(1, ops.count { it == 169 })
            assertEquals(1, ops.count { it == 150 })
            assertFalse(155 in ops, "Show settlement before leaving the cleared stage")
            val settlement = encode(drain().single { opcodeOf(it) == 150 })
            try {
                assertEquals(0, settlement.Decode1, "Intermediate wins must not start the full mission-clear flow")
                assertEquals(0, settlement.Decode1, "The player still wins the completed stage")
            } finally { settlement.release() }
            val deadline = System.currentTimeMillis() + 7500
            val nextId = bnbemu.handler.room.mapIdTable.entries.single { it.value.name == "MonPen02" }.key
            while (System.currentTimeMillis() < deadline &&
                (Room.activeMapId != nextId || drain().count { opcodeOf(it) == 169 } < 2)) Thread.sleep(10)
            assertEquals(nextId, Room.activeMapId)
            val transitioned = drain().map { opcodeOf(it) }
            assertFalse(112 in transitioned || 155 in transitioned, "Do not reconstruct the prepare room between boards")
            assertFalse(136 in transitioned, "Wait for native C131 map-loaded acknowledgement")
            val nextBoard = encode(drain().last { opcodeOf(it) == 169 })
            try {
                assertEquals(2, nextBoard.Decode1); nextBoard.Decode4
                assertEquals(bnbemu.handler.room.mapIdTable.getValue(nextId).themeId, nextBoard.Decode2)
                assertEquals(0, nextBoard.Decode2); assertEquals(0, nextBoard.Decode1)
                assertEquals(0, nextBoard.readableBytes())
            } finally { nextBoard.release() }
            ch.outboundMessages().clear()
            Room.onMonsterBoardLoaded(ClientSocket())
            Room.onBomberDie(0)
            assertTrue(drain().isEmpty(), "Ignore unrelated loading acknowledgements and old-board deaths")
            val loaded = encode(PKT { out -> out.Encode1(1) })
            try { with(bnbemu.handler.room.MonsterBoardLoadedReq) { loaded.handle(cs) } } finally { loaded.release() }
            assertEquals(1, drain().count { opcodeOf(it) == 136 })
            ch.outboundMessages().clear()
            Room.onMonsterBoardLoaded(cs)
            assertTrue(drain().isEmpty(), "Duplicate load acknowledgement must not restart the board")
            Room.onBoardReady()
            assertEquals(2, Room.monsterBombContext)
            assertFalse(198 in transitioned || 274 in transitioned, "Do not refill supplies between stages")
        } finally { Room.reset(); Room.localGameMode = oldMode }
    }

    @Test
    fun octopusNormalStageConfirmsMobDeathOnlyOnceAndDoesNotGuessBossHp() {
        val mode = Room.localGameMode
        val mapId = Room.mapId
        val maps = bnbemu.data.FxdynMapInfo.gameRecords.filter { it.flag == 0 }
        try {
            Room.localGameMode = 14
            Room.mapId = maps.indexOfFirst { it.group == "Monster" && it.stage == "npc" && it.subtype == 0 }
            Room.startGame(); Room.onBoardReady(); ch.outboundMessages().clear()
            Room.onMonsterDamage(2, 1, 7)
            assertTrue(drain().isEmpty())
            Room.onMonsterDamage(2, 1, 0)
            val death = encode(drain().single { opcodeOf(it) == 151 })
            try {
                assertEquals(14, death.Decode2); assertEquals(2, death.Decode2)
                assertEquals(4, death.Decode1); assertEquals(0, death.readableBytes())
            } finally { death.release() }
            ch.outboundMessages().clear()
            Room.onMonsterDamage(2, 1, 0)
            assertTrue(drain().isEmpty(), "Repeated damage must not replay the death animation")
            Room.mapId = maps.indexOfFirst { it.name == "Monster03" }
            assertTrue(Room.mapId >= 0)
            Room.startGame(loadedMapId = Room.mapId); Room.onBoardReady(); ch.outboundMessages().clear()
            Room.onMonsterDamage(0, 1, 0)
            val update = encode(drain().single())
            try {
                assertEquals(14, update.Decode2); assertEquals(0, update.Decode2)
                assertEquals(2, update.Decode1); assertEquals(19, update.Decode2)
            } finally { update.release() }
        } finally { Room.localGameMode = mode; Room.mapId = mapId }
    }

    @Test
    fun capturedMonsterDropUsesThreeBytesWithoutDisconnecting() {
        assertEquals("MonsterDropItemReq", OpcodeManager.recvOps[141])
        val mode = Room.localGameMode
        try {
            Room.localGameMode = 14
            val body = Unpooled.wrappedBuffer(byteArrayOf(0x7c, 0x58, 0x53))
            try {
                with(bnbemu.handler.room.MonsterDropItemReq) { body.handle(cs) }
                assertEquals(0, body.readableBytes())
                assertTrue(ch.isActive)
            } finally { body.release() }
        } finally { Room.localGameMode = mode }
    }

    @Test
    fun iceMonsterRequiresFreezeThenTouchAndThawCancelsFrozenHit() {
        val mode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame()
            Room.onBoardReady()
            ch.outboundMessages().clear()
            Room.onMonsterDamage(2, 1, 0)
            assertTrue(drain().isEmpty(), "Ordinary HP must not be guessed")
            Room.onMonsterFreeze(2, 160, 240)
            val freeze = drain().single { opcodeOf(it) == 151 }
            val body = encode(freeze)
            try {
                assertEquals(14, body.Decode2)
                assertEquals(2, body.Decode2)
                assertEquals(1, body.Decode1)
                assertEquals(160, body.Decode4)
                assertEquals(240, body.Decode4)
                assertEquals(0, body.readableBytes())
            } finally { body.release() }
            ch.outboundMessages().clear()
            Room.onMonsterDamage(2, 1, 7)
            assertTrue(drain().isEmpty(), "Empty player slot must not defeat a monster")
            Room.onMonsterDamage(2, 1, 0)
            val dead = encode(drain().single { opcodeOf(it) == 151 })
            try {
                assertEquals(14, dead.Decode2)
                assertEquals(2, dead.Decode2)
                assertEquals(4, dead.Decode1)
                assertEquals(0, dead.readableBytes())
            } finally { dead.release() }
            ch.outboundMessages().clear()
            Room.onMonsterDamage(2, 1, 0)
            assertTrue(drain().isEmpty())
            Room.onMonsterFreeze(3, 80, 120)
            ch.outboundMessages().clear()
            Room.onMonsterThaw(3)
            val thaw = encode(drain().single { opcodeOf(it) == 151 })
            try { thaw.Decode2; thaw.Decode2; assertEquals(8, thaw.Decode1) } finally { thaw.release() }
            ch.outboundMessages().clear()
            Room.onMonsterDamage(0, 1, 0)
            assertTrue(drain().isEmpty())
            Room.localGameMode = 15
            Room.onMonsterFreeze(3, 1, 1)
            assertTrue(drain().isEmpty())
        } finally { Room.localGameMode = mode }
    }

    @Test
    fun monsterControlGrantsOnceAndReleasesOnlyValidRegisteredRange() {
        val mode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.startGame()
            Room.onBoardReady()
            Room.onBoardReady()
            val grants = drain().filter { opcodeOf(it) == 178 }
            assertEquals(120, grants.size)
            grants.forEachIndexed { id, packet ->
                val body = encode(packet)
                try {
                    assertEquals(id, body.Decode2)
                    assertEquals(0, body.Decode1)
                    assertEquals(0, body.readableBytes())
                } finally { body.release() }
            }
            Room.onMonsterDestroyed(-1, 0)
            Room.onMonsterDestroyed(120, 0)
            Room.onMonsterDestroyed(0, 7)
            Room.onMonsterDestroyed(0, 7)
            val removals = drain().filter { opcodeOf(it) == 179 }
            assertEquals(1, removals.size)
            val body = encode(removals.single())
            try { assertEquals(0, body.Decode2); assertEquals(7, body.Decode4) } finally { body.release() }
            ch.outboundMessages().clear()
            Room.localGameMode = 15
            Room.startGame()
            Room.onBoardReady()
            assertFalse(drain().any { opcodeOf(it) == 178 })
        } finally { Room.localGameMode = mode }
    }

    @Test
    fun monsterTimeoutWaitsForBoardAndReturnsToSameRoom() {
        val mode = Room.localGameMode
        val limit = Room.monsterTimeLimitMs
        val returnDelay = Room.returnToRoomDelayMs
        try {
            Room.localGameMode = 14
            Room.monsterTimeLimitMs = 20
            Room.returnToRoomDelayMs = 20
            Room.startGame()
            Room.checkMonsterTimeout(Long.MAX_VALUE)
            assertFalse(Room.gameOverSent, "Loading time must not consume the round")
            Room.onBoardReady()
            Thread.sleep(30)
            Room.onBoardReady()
            Room.checkMonsterTimeout()
            assertTrue(Room.gameOverSent, "Duplicate ready must not reset the deadline")
            val sent = collectSent()
            val ops = sent.map(::opcodeOf)
            assertFalse(170 in ops, "Unverified end phase must not be sent to the monster client")
            assertTrue(sent.any { opcodeOf(it) == 169 })
            val result = sent.first { opcodeOf(it) == 150 }
            val body = encode(result)
            try {
                assertEquals(0, body.Decode1, "Timeout must not show mission clear")
                assertEquals(1, body.Decode1, "Timeout must be a loss")
                assertEquals(0, body.Decode1)
                assertEquals(0, body.Decode1, "Timeout must never show perfect victory")
            } finally { body.release() }
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline && drain().none { opcodeOf(it) == 83 }) Thread.sleep(10)
            assertTrue(drain().any { opcodeOf(it) == 155 })
            assertTrue(drain().any { opcodeOf(it) == 83 })
            assertEquals(14, Room.localGameMode)
            Room.startGame()
            assertFalse(Room.gameOverSent)
        } finally {
            Room.localGameMode = mode
            Room.monsterTimeLimitMs = limit
            Room.returnToRoomDelayMs = returnDelay
        }
    }

    @Test
    fun monsterDeathUsesNativeLossEnumWithoutPerfectOrMissionClearEffects() {
        val mode = Room.localGameMode
        try {
            Room.localGameMode = 14
            Room.slots.drop(1).forEach { it.isAI = false }
            Room.startGame()
            Room.onBomberDie(0)
            val result = collectSent().first { opcodeOf(it) == 150 }
            val body = bodyOf(result)
            assertEquals(0, dec1(body[0]), "Death must not show mission clear")
            assertEquals(0, dec1(body[3]), "Death must not show perfect victory")
            assertEquals(2, dec1(body[16]), "Native loss enum is 2, not draw enum 1")
            val win = bodyOf(GameResult(winnerTeam = 0))
            assertEquals(0, dec1(win[3]))
            assertEquals(0, dec1(win[16]))
            Room.localGameMode = 16
            val battle = bodyOf(GameResult(winnerTeam = 1))
            assertEquals(0, dec1(battle[3]), "Do not alter battle header")
            assertEquals(2, dec1(battle[16]), "Battle defeat must use the native loss enum too")
            val battleWin = bodyOf(GameResult(winnerTeam = 0))
            assertEquals(0, dec1(battleWin[16]), "Battle victory must not use draw enum 1")
        } finally { Room.localGameMode = mode }
    }

    @Test
    fun monsterDeadlineCannotEndTrainingOrAnAbandonedRound() {
        val mode = Room.localGameMode
        try {
            Room.localGameMode = 15
            Room.startGame()
            Room.onBoardReady()
            Room.checkMonsterTimeout(Long.MAX_VALUE)
            assertFalse(Room.gameOverSent)
            Room.localGameMode = 14
            Room.startGame()
            Room.onBoardReady()
            Room.endGame()
            Room.checkMonsterTimeout(Long.MAX_VALUE)
            assertFalse(Room.gameOverSent)
        } finally { Room.localGameMode = mode }
    }

    @Test
    fun monsterBoardAndServerUseNativeTwoMinuteLimit() {
        assertEquals(120_000L, Room.monsterTimeLimitMs)
        val board = bodyOf(bnbemu.packet.PracticeBoard(bnbemu.packet.MONSTER_TIME_LIMIT_SEC))
        val field = Unpooled.wrappedBuffer(board.copyOfRange(8, 12))
        try { assertEquals(120, field.Decode4) } finally { field.release() }
    }

    @Test
    fun testResultReturnsToExistingRoomAndAllowsAnotherRound() {
        val savedReturnDelay = Room.returnToRoomDelayMs
        Room.returnToRoomDelayMs = 20
        try {
            Room.startGame()
            Room.onLastTeamStanding(0)
            collectSent()
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline && drain().none { opcodeOf(it) == 423 }) Thread.sleep(10)
            val ops = drain().map(::opcodeOf)
            assertTrue(ops.indexOf(155) > ops.indexOf(150), "Return must follow results: $ops")
            assertTrue(ops.indexOf(83) > ops.indexOf(155), "Restore room only after stage change: $ops")
            assertTrue(Room.slots[1].isAI)
            Room.startGame()
            assertFalse(Room.gameOverSent)
            assertTrue(Room.slots[0].isAlive)
        } finally { Room.returnToRoomDelayMs = savedReturnDelay }
    }

    @Test
    fun testLeavingAfterResultCancelsPendingRoomReturn() {
        val savedReturnDelay = Room.returnToRoomDelayMs
        Room.returnToRoomDelayMs = 100
        try {
            Room.startGame()
            Room.onLastTeamStanding(0)
            collectSent()
            Room.cancelPendingReturn()
            Thread.sleep(200)
            assertFalse(drain().any { opcodeOf(it) == 155 })
        } finally { Room.returnToRoomDelayMs = savedReturnDelay }
    }

    private lateinit var ch: EmbeddedChannel
    private lateinit var cs: ClientSocket
    private var savedDelay = 0L

    @BeforeEach
    fun setUp() {
        cs = ClientSocket()
        ch = EmbeddedChannel(cs)
        savedDelay = Room.gameOverDelayMs
        Room.gameOverDelayMs = 0

        // 练习房布局: slot 0 = 玩家, slot 1..7 = AI (见 CreatePracticeSessionReq)
        Room.slots.forEach { slot ->
            slot.user = null
            slot.isAI = false
        }
        Room.slots[0].user = User(cs, id = 1, name = "tester")
        for (i in 1..7) Room.slots[i].isAI = true
        Room.reset()
    }

    @AfterEach
    fun tearDown() {
        Room.gameOverDelayMs = savedDelay
        Room.slots.forEach { slot ->
            slot.user = null
            slot.isAI = false
        }
        Room.reset()
        ch.finish()
    }

    // ---- 工具 ----

    /** 等待调度线程把结算包写进 outbound 队列 (结算延迟已置 0, 但仍跨 EventLoop 线程)。 */
    private fun collectSent(timeoutMs: Long = 2000): List<PKT> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            @Suppress("UNCHECKED_CAST")
            val list = ch.outboundMessages().toList() as List<PKT>
            if (list.any { opcodeOf(it) == 150 }) return list
            Thread.sleep(10)
        }
        @Suppress("UNCHECKED_CAST")
        return ch.outboundMessages().toList() as List<PKT>
    }

    private fun drain(): List<PKT> {
        @Suppress("UNCHECKED_CAST")
        return ch.outboundMessages().toList() as List<PKT>
    }

    private fun encode(pkt: PKT): ByteBuf {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf, 3))
        return buf
    }

    // opcode 不写在包体里, 由 Codec 在写线时按包类名从 yaml 查出并前置 (见 OpcodeManager)
    private fun opcodeOf(pkt: PKT): Int = OpcodeManager.getSendOp(pkt::class.java)

    private fun bodyOf(pkt: PKT): ByteArray {
        val buf = encode(pkt)
        val body = ByteArray(buf.readableBytes())
        buf.readBytes(body)
        return body
    }

    // 3 度帧的字段都带混淆: Encode1 ^0x5A / Encode2 ^0xA569 / Encode4 ^0x96CA5395
    private fun dec1(b: Byte): Int = (b.toInt() and 0xFF) xor 0x5A

    /** 结算三连里的 op 150 的 winnerTeam 字段 */
    private fun winnerTeamOf(sent: List<PKT>): Int = dec1(bodyOf(sent.first { opcodeOf(it) == 150 })[1])

    // ---- 包编码 ----

    @Test
    fun testPacketClassesAreBoundToOpcodes() {
        assertEquals(169, OpcodeManager.getSendOp(InitGameResult(0, 0, 0)::class.java), "InitGameResult 必须是 op 169")
        assertEquals(150, OpcodeManager.getSendOp(GameResult(0)::class.java), "GameResult 必须是 op 150")
    }

    // ---- 兼容路径: C→S op 154 action=5 ----

    @Test
    fun testKillAllEnemiesEndsMatchWithPlayerWin() {
        Room.startGame()
        ch.outboundMessages().clear()

        // 逐个击杀 AI 1..7
        for (slot in 1..7) {
            Room.onBomberDie(slot)
            if (slot < 7) {
                assertFalse(
                    drain().any { opcodeOf(it) == 150 },
                    "还有 AI 存活时不得结算 (slot=$slot 阵亡后)"
                )
            }
        }

        val sent = collectSent()
        val ops = sent.map(::opcodeOf)
        assertTrue(ops.contains(169), "AI 全灭必须下发 op 169 InitGameResult, ops=$ops")
        assertTrue(ops.contains(150), "AI 全灭必须下发 op 150 GameResult, ops=$ops")
        assertEquals(0, winnerTeamOf(sent), "玩家胜利时 winnerTeam 必须为 0")
    }

    @Test
    fun testPlayerDeathStillEndsMatchWithAiWin() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(0)
        val sent = collectSent()
        val ops = sent.map(::opcodeOf)

        assertTrue(ops.contains(159), "必须先广播 op 159 BomberDie, ops=$ops")
        assertTrue(ops.contains(169) && ops.contains(150), "结算包缺失, ops=$ops")
        assertEquals(1, winnerTeamOf(sent), "AI 胜利时 winnerTeam 必须为 1")
    }

    @Test
    fun testMatchContinuesWhileBothSidesHaveSurvivors() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(1)
        Room.onBomberDie(2)
        Thread.sleep(120)

        val ops = drain().map(::opcodeOf)
        assertTrue(ops.contains(159), "阵亡必须广播 op 159, ops=$ops")
        assertFalse(ops.contains(150), "双方都还有人时不得结算, ops=$ops")
        assertFalse(Room.gameOverSent)
    }

    @Test
    fun testDuplicateDeathReportIsIgnored() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(3)
        Room.onBomberDie(3) // 客户端 OnTick 重发 / 残流
        Room.onBomberDie(3)

        val ops = drain().map(::opcodeOf)
        assertEquals(1, ops.count { it == 159 }, "同一槽位重复阵亡上报只能广播一次, ops=$ops")
    }

    @Test
    fun testResultIsSentOnlyOnce() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(0)
        collectSent()
        val first = drain().count { opcodeOf(it) == 150 }
        assertEquals(1, first, "结算只能下发一次")

        // 迟到的其他阵亡上报不得再触发第二套结算
        Room.onBomberDie(4)
        Thread.sleep(120)
        assertEquals(1, drain().count { opcodeOf(it) == 150 }, "结算后不得重复下发 op 150")
    }

    @Test
    fun testAliveStateIsResetOnNextMatch() {
        Room.startGame()
        for (slot in 1..7) Room.onBomberDie(slot)
        collectSent()
        assertTrue(Room.gameOverSent, "第一局应当已结算")

        // 第二局: 所有参战槽位重新复活, 计数与结算标志都要复位
        Room.startGame()
        assertFalse(Room.gameOverSent, "新一局必须清空结算标志")
        assertTrue(Room.slots[1].isAlive && Room.slots[7].isAlive, "新一局所有 AI 必须复活")
        assertTrue(Room.slots[0].isAlive, "新一局玩家必须复活")

        ch.outboundMessages().clear()
        Room.onBomberDie(1)
        Thread.sleep(120)
        val ops = drain().map(::opcodeOf)
        assertTrue(ops.contains(159), "新一局首次阵亡上报不能被当成重复上报吞掉, ops=$ops")
        assertFalse(ops.contains(150))
    }

    @Test
    fun testUnknownSlotIsDropped() {
        Room.startGame()
        ch.outboundMessages().clear()
        Room.onBomberDie(8)
        Room.onBomberDie(-1)
        assertTrue(drain().isEmpty(), "越界槽位不得广播也不得触发结算")
        assertFalse(Room.gameOverSent)
    }

    @Test
    fun testEmptySlotIsAlwaysOutOfMatch() {
        // slot 5 没座位
        Room.slots[5].isAI = false
        Room.startGame()
        assertEquals(SlotMatchState.OUT_OF_MATCH, Room.slots[5].matchState)
        assertEquals(SlotMatchState.ALIVE, Room.slots[6].matchState, "AI 槽必须是 ALIVE")
        assertFalse(Room.slots[5].isAlive)
        ch.outboundMessages().clear()

        // 空槽的错位/迟到阵亡上报必须被丢弃：不能给没有 bomber 的槽位广播 op 159
        Room.onBomberDie(5)
        assertTrue(drain().isEmpty(), "空槽阵亡上报不得广播 op 159")
        assertEquals(SlotMatchState.OUT_OF_MATCH, Room.slots[5].matchState, "空槽状态不得被上报改写")
        assertFalse(Room.gameOverSent)
    }

    @Test
    fun testDeathOnlyTransitionsFromAlive() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(2)
        assertEquals(SlotMatchState.DEAD, Room.slots[2].matchState)
        Room.onBomberDie(2)
        assertEquals(SlotMatchState.DEAD, Room.slots[2].matchState, "DEAD 是终态, 重复上报不得改变状态")
        assertEquals(1, drain().count { opcodeOf(it) == 159 }, "只有一次 ALIVE→DEAD 才广播")
    }

    @Test
    fun testPureHumanRoomDoesNotEndOnFirstDeath() {
        // 将来若开纯人类 PvP 房 (无 AI), 死一个人不能被误判成"AI 全灭"而立刻结算
        for (i in 1..7) Room.slots[i].isAI = false
        Room.slots[1].user = User(cs, id = 2, name = "tester2")
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onBomberDie(0)
        Thread.sleep(120)
        val ops = drain().map(::opcodeOf)
        assertTrue(ops.contains(159), "阵亡仍要广播 op 159, ops=$ops")
        assertFalse(ops.contains(150), "对手还活着时不得结算, ops=$ops")
        assertFalse(Room.gameOverSent)
    }

    // ---- 真实路径: C→S op 112 action=0x60 ----

    @Test
    fun testHandlerBindsOp112() {
        assertEquals("BomberActionReq", OpcodeManager.recvOps[112])
        assertSame(BomberActionReq, handlerArray[112])
    }

    /**
     * 实战日志原样样本: `3A A5 39 A7 6E`
     *   E1(0x3A ^ 0x5A = 0x60) → action 0x60 / slot 0
     *   E2(0xA539 ^ 0xA569 = 0x0050) → x = 80
     *   E2(0xA76E ^ 0xA569 = 0x0207) → y = 519
     */
    @Test
    fun testCapturedAction60BodyEndsMatchWithPlayerWin() {
        Room.startGame()
        ch.outboundMessages().clear()

        val raw = byteArrayOf(0x3A, 0xA5.toByte(), 0x39, 0xA7.toByte(), 0x6E)
        val buf = Unpooled.wrappedBuffer(raw)
        with(BomberActionReq) {
            buf.handle(cs)
        }
        assertEquals(0, buf.readableBytes(), "5B body 必须被完整消费")

        val sent = collectSent()
        val ops = sent.map(::opcodeOf)
        assertTrue(ops.contains(169) && ops.contains(150), "结算包缺失, ops=$ops")
        assertEquals(0, winnerTeamOf(sent), "存活方是玩家槽 → winnerTeam = 0")
    }

    @Test
    fun testAction60DoesNotReplayDeathAnimation() {
        Room.startGame()
        ch.outboundMessages().clear()

        Room.onLastTeamStanding(0)
        Thread.sleep(120)

        val ops = drain().map(::opcodeOf)
        assertFalse(ops.contains(159), "AI 的死亡动画客户端本地已经播过, 不得再广播 op 159, ops=$ops")
        assertEquals(SlotMatchState.DEAD, Room.slots[1].matchState, "落败方要补记 DEAD")
        assertTrue(Room.slots[0].isAlive, "存活方保持 ALIVE")
    }

    @Test
    fun testRepeatedAction60EndsMatchOnlyOnce() {
        Room.startGame()
        ch.outboundMessages().clear()

        // 客户端每帧重发, 实测一秒能来几百条
        repeat(50) { Room.onLastTeamStanding(0) }

        val sent = collectSent()
        assertEquals(1, sent.count { opcodeOf(it) == 150 }, "action=0x60 洪水只能触发一次结算")
        repeat(5) { Room.onLastTeamStanding(0) }
        Thread.sleep(120)
        assertEquals(1, drain().count { opcodeOf(it) == 150 }, "结算后不得重复下发 op 150")
    }

    @Test
    fun testAction60FromAiSlotGivesAiWin() {
        Room.startGame()
        ch.outboundMessages().clear()

        // 玩家先阵亡 (op154 action=5 只对真人成立, 这条路径仍然有效),
        // 之后客户端会替存活的 AI 发 action=0x60 —— 但结算此时早就发过了, 不能重发
        Room.onBomberDie(0)
        val first = collectSent()
        assertEquals(1, first.count { opcodeOf(it) == 150 }, "玩家阵亡立刻结算")
        assertEquals(1, winnerTeamOf(first), "AI 胜, winnerTeam=1")

        ch.outboundMessages().clear()
        Room.onLastTeamStanding(3)
        Thread.sleep(120)
        assertTrue(drain().isEmpty(), "已经结算过, 迟到的 action=0x60 不得再下发任何包")
    }

    @Test
    fun testAction60FromEmptySlotIsIgnored() {
        Room.startGame()
        ch.outboundMessages().clear()

        // slot 5 是空槽 (练习房只有 slot 0 + 1..7 AI? 这里清掉一个座位来模拟空槽)
        Room.slots[5].isAI = false
        Room.onLastTeamStanding(5)

        Thread.sleep(120)
        assertTrue(drain().isEmpty(), "空槽的错位上报不得触发结算")
        assertFalse(Room.gameOverSent)
        Room.slots[5].isAI = true
    }
}
