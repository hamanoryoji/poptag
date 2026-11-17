package bnbemu.server

import io.netty.channel.EventLoop
import io.netty.util.concurrent.ScheduledFuture
import bnbemu.isEN
import bnbemu.isJP
import bnbemu.netty.ClientSocket
import bnbemu.netty.NettyServer.workerGroup
import bnbemu.netty.PKT
import bnbemu.packet.room.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

// 之後改用class
object Room {
    private val executor: EventLoop = workerGroup.next()
    private var tickFuture: ScheduledFuture<*>? = null
    private val roomGeneration = AtomicLong()
    // Allow the result intro and XP/coin animations to finish before returning.
    // Intermediate monster boards keep their independent five-second transition.
    internal var returnToRoomDelayMs = 10000L
    @Volatile private var matchRunning = false
    @Volatile private var monsterDeadlineNanos: Long? = null
    internal var monsterTimeLimitMs = bnbemu.packet.MONSTER_TIME_LIMIT_SEC * 1000L
    @Volatile private var aiBattleDeadlineNanos: Long? = null
    internal var aiBattleTimeLimitMs = bnbemu.packet.AI_BATTLE_TIME_LIMIT_SEC * 1000L
    private var monsterControlGranted = false
    private val releasedMonsterObjects = mutableSetOf<Int>()
    private val frozenMonsterObjects = mutableSetOf<Int>()
    private val shrunkenMonsterObjects = mutableSetOf<Int>()
    private val defeatedMonsterObjects = mutableSetOf<Int>()
    private val detonatedMonsterMissiles = mutableSetOf<Int>()
    private val angryBossObjects = mutableSetOf<Int>()
    private val lockedBossObjects = mutableSetOf<Int>()
    private val spawnedMonsterObjects = mutableMapOf<Int, LocalMonster>()
    private val monsterProjectileObjects = mutableSetOf<Int>()
    private val monsterHealth = mutableMapOf<Int, Int>()
    private val monsterStageObjectives = mutableSetOf<Int>()
    private val robotReady = mutableMapOf<Int, Int>()
    private val robotReadyHealth = mutableMapOf<Int, Int>()
    private var robotMergeAnnounced = false
    private var robotTransformed = false
    private val monsterKickRequests = mutableMapOf<Int, Pair<ByteArray, Long>>()
    private var monsterTransitionPending = false
    private var monsterBoardLoadPending = false
    private var monsterBoardStartSent = false
    private var economyReceipt = ""
    private var economySettled = false
    private val pendingWalletNotices = mutableMapOf<ClientSocket,String>()
    private fun loadedMonsterStage(): LocalMonsterStage? {
        val name = bnbemu.handler.room.mapIdTable[activeMapId]?.name ?: return null
        return LocalMonsterStages.stages[name]
    }
    private fun validMonsterBombCell(x: Int, y: Int): Boolean {
        val name = bnbemu.handler.room.mapIdTable[activeMapId]?.name ?: return false
        return LocalMapBounds.forBoard(name)?.contains(x, y) == true
    }
    // CNetObjectMan caps initial registered objects at 120 (0x64EC6C).
    internal const val MONSTER_OBJECT_CAPACITY = 120

    // Invalidates both delayed results and returns when a player leaves or starts anew.
    fun cancelPendingReturn() { roomGeneration.incrementAndGet() }
    // 当前房间代 (供 NPC 实体断言等"每房间一次"的幂等标记使用)
    fun currentGeneration(): Long = roomGeneration.get()
    // 最近一次激战 NPC 实体断言所对应的房间代; reset() 会通过 cancelPendingReturn 换代
    var npcAssertedGeneration: Long = -1
    private var nextMonsterBombId = 0x6000
    // Native CMapPublicValue uses 1000 records, with 600..999 reserved
    // beyond the board's ordinary entries (65DB41). High monster bomb IDs
    // belong to a different system and would overrun this table at 65DF5E.
    private var nextScriptBombId = 600

    fun onMapScriptBombs(client: ClientSocket, request: bnbemu.handler.room.MapScriptBombRequest) {
        val generation=roomGeneration.get()
        executor.execute {
            if(generation!=roomGeneration.get() || !matchRunning || gameOverSent || client !in uniqueClients) return@execute
            val name=bnbemu.handler.room.mapIdTable[activeMapId]?.name ?: return@execute
            if(!name.startsWith("PangLand")) return@execute
            val bounds=LocalMapBounds.forBoard(name) ?: return@execute
            val cells=request.cells.distinct()
            if(cells.any { (row,column)-> !bounds.contains(column,row) }) return@execute
            cells.forEach { (row,column)->
                val id=nextScriptBombId
                nextScriptBombId=if(id==999) 600 else id+1
                // The native sprinkler owns the flight; ordinary bomb state
                // synchronization supplies its eventual explosion, not an item drop.
                addBomb(8,row,column,bombId=id,broadcastInitialState=false)
                BC(MapScriptBomb(request.power,id,request.sourceX,request.sourceY,row,column,request.state))
            }
            println("[MapScriptBomb] map=$name power=${request.power} state=${request.state} source=${request.sourceX},${request.sourceY} cells=${cells.size}")
        }
    }
    internal var monsterBombContext = 0
        private set

    fun observeBombContext(slotId: Int, context: Int, client: ClientSocket) {
        if (!matchRunning || localGameMode != 14 || context !in 0..2) return
        if (slotId !in slots.indices || slots[slotId].user?.c !== client) return
        monsterBombContext = context
    }
    private var nextBombId = 0
    val slots = Array(8) { Slot() }
    var mapId = 89
    var activeMapId = mapId
        private set
    var themeId = 0
    var localGameMode = 15
    fun localAiCharacter(slot: Int) = if (localGameMode == 16) localNpcCharacter(slot,20 + (slot.coerceIn(4, 7) - 4)) else 5
    val bombs = mutableListOf<Bomb>()
    var lastAirplaneTime = 0L

    val activeSlotCount: Int
        get() = slots.count { it.user != null || it.isAI }

    val uniqueClients: Sequence<ClientSocket>
        get() = slots.asSequence()
            .mapNotNull { it.user?.c } // 取得所有slot中有玩家的 ClientSocket
            .distinctBy { it.ch.id() } // 過濾掉 2P 的重複連線 對2P只會發一次

    // Updating cosmetics must reach the prepare stage before the next round.
    // Never reinitialize inventories or inject prepare packets into a live game.
    fun refreshWaitingAppearance(user: User): Boolean {
        val index = slots.indexOfFirst { it.user === user }
        if (matchRunning || index != 0) return false
        BC(bnbemu.packet.PracticeRoomInfo(localBombStyle(user), user),
            bnbemu.packet.PracticeSetPlayer(index, user.selectedBomber))
        return true
    }

    fun startGame(loadedMapId: Int = mapId) {
        economyReceipt = java.util.UUID.randomUUID().toString()
        economySettled = false
        monsterBoardLoadPending = false
        monsterBoardStartSent = false
        activeMapId = loadedMapId
        // Boss boards use the stage-clear context (native CGameContext index 2).
        // Authoritative player C106 requests keep this synchronized thereafter.
        monsterBombContext = if (loadedMonsterStage()?.monsters?.values?.any { it.boss } == true) 2 else 0
        cancelPendingReturn()
        // 每 200ms 一次炸弹与对局状态检测
        tickFuture?.cancel(false)

        // 每局开局把槽位生命状态重置成 (座位上有人 ? ALIVE : OUT_OF_MATCH) 并清结算标志。
        // 必须在这里做, 否则上一局阵亡的槽位会带着 DEAD 进入下一局,
        // 导致开局第一次死亡上报被当成重复上报吞掉、或开局即判定全灭。
        resetMatchState()
        matchRunning = true
        monsterDeadlineNanos = null
        aiBattleDeadlineNanos = null
        monsterControlGranted = false
        releasedMonsterObjects.clear()
        frozenMonsterObjects.clear()
        shrunkenMonsterObjects.clear()
        defeatedMonsterObjects.clear()
        detonatedMonsterMissiles.clear()
        angryBossObjects.clear()
        lockedBossObjects.clear()
        spawnedMonsterObjects.clear()
        monsterProjectileObjects.clear()
        monsterHealth.clear()
        monsterStageObjectives.clear()
        monsterKickRequests.clear()
        robotReady.clear(); robotReadyHealth.clear(); robotMergeAnnounced = false; robotTransformed = false
        monsterStageObjectives.addAll(loadedMonsterStage()?.objectives.orEmpty())
        monsterTransitionPending = false
        tickFuture = executor.scheduleAtFixedRate(::onTick, 0, 200, TimeUnit.MILLISECONDS)

        if (!isEN) {
            // temp fix jp crash
            if (isJP)
                mapId = 118

            val spawnPositions = (0..7).shuffled()
            val random = Random.nextInt(9999)
            BC(
                LaunchGameStage(spawnPositions),
                AirplaneItemProb(),
                DoStartGame(random)
            )
        }
        // EN: 对局推进完全由客户端本地 AI (CAIPlayer / CAIGoal*) 与本地时钟驱动,
        // 服务端只在 C→S op 106 时回 S→C op 139、2.5s 后回 op 138 引爆, 无需任何周期广播。
        // (曾误加每 400ms 的 op 172 "ItemThrow" 广播, 实为地图事件时间轴同步, 见 MapEventSync.kt)
    }

    // CGameSession::Update - 定时更新水泡倒计时并广播爆炸状态
    // 注意: S→C op 138 SetBombState(bombId, 0) (CA.exe 0x005481BD -> 0x00534B18 -> 0x00565E9F)
    // 已完整处理水泡引爆、十字水柱、炸箱子与爆出道具。
    // 绝不可在此追加发送 S→C op 148 (CA.exe 0x0054BA1A -> 0x00477CEF), 因为 op 148 实为
    // DropObstacleResult (放置地面障碍物: 1=地雷, 2=陷阱, 3=香蕉皮 Item/BananaGround, 4=图钉 Item/PushpinGround)!
    private fun onTick() {
        val now = System.currentTimeMillis()
        checkMonsterTimeout()
        checkAiBattleTimeout()
        val stateChanged = mutableListOf<Bomb>()

        synchronized(bombs) {
            bombs.removeIf { bomb ->
                if (bomb.updateState(now)) {
                    stateChanged.add(bomb)
                }
                bomb.shouldRemove()
            }
        }

        if (stateChanged.isNotEmpty()) {
            val npcExplosions = stateChanged.filter { it.ownerSlotId == 9 && it.state == 0 }
            if (npcExplosions.isNotEmpty()) BC(SetBombStateBatch(npcExplosions.map { it.id }))
            stateChanged.filterNot { it.ownerSlotId == 9 && it.state == 0 }.forEach { bomb ->
                BC(SetBombState(bomb.id, bomb.state))
            }
            // Land first so the native object reaches its target cell, then
            // ignite in the same tick for impact-fused monster projectiles.
            val impactFused = stateChanged.filter { it.ownerSlotId == 9 && it.state == 1 && it.landingFuseMs == 0L }
            if (impactFused.isNotEmpty()) {
                synchronized(bombs) {
                    impactFused.forEach { it.updateState(now) }
                    bombs.removeAll(impactFused.toSet())
                }
                BC(SetBombStateBatch(impactFused.map { it.id }))
            }
        }
    }

    // Start the countdown only after the actual player has loaded the board.
    // Repeated loading acknowledgements must never extend the round.
    fun onBoardReady() {
        if (matchRunning && localGameMode == 16 && aiBattleDeadlineNanos == null && !gameOverSent) {
            aiBattleDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(aiBattleTimeLimitMs)
        }
        if (monsterBoardLoadPending) {
            if (!monsterBoardStartSent) return
            monsterBombContext = 2
            monsterBoardLoadPending = false
        }
        if (matchRunning && localGameMode == 14 && monsterDeadlineNanos == null && !gameOverSent) {
            monsterDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(monsterTimeLimitMs)
        }
        if (matchRunning && localGameMode == 14 && !monsterControlGranted && !gameOverSent) {
            val controller = slots.indexOfFirst { it.user != null }
            if (controller < 0) return
            monsterControlGranted = true
            // Initial map monsters are already registered locally, but carry owner
            // -1 / remote=true. Grant control only in the offline stage-clear mode.
            // Nonexistent ids are safe no-ops; capacity is the native registry limit.
            repeat(MONSTER_OBJECT_CAPACITY) { BC(MonsterControl(it, controller)) }
            println("[Monster] Granted local control slot=$controller capacity=$MONSTER_OBJECT_CAPACITY")
        }
    }

    fun onMonsterBoardLoaded(client: ClientSocket) {
        if (!monsterBoardLoadPending || monsterBoardStartSent || localGameMode != 14) return
        if (uniqueClients.none { it === client }) return
        monsterBoardStartSent = true
        BC(bnbemu.packet.PracticeBoard(timeLimitSec = bnbemu.packet.MONSTER_TIME_LIMIT_SEC))
    }

    fun onMonsterDestroyed(objectId: Int, parameter: Int) {
        if (!matchRunning || localGameMode != 14 || gameOverSent || !monsterControlGranted) return
        if (objectId !in 0 until MONSTER_OBJECT_CAPACITY || !releasedMonsterObjects.add(objectId)) return
        spawnedMonsterObjects.remove(objectId)
        monsterProjectileObjects.remove(objectId)
        BC(MonsterDespawn(objectId, parameter))
        checkMonsterStageClear()
    }

    private fun acceptsMonsterEventContext(objectId: Int) =
        matchRunning && localGameMode == 14 && !gameOverSent && monsterControlGranted &&
            objectId in 0 until MONSTER_OBJECT_CAPACITY && objectId !in releasedMonsterObjects

    private fun acceptsMonsterEvent(objectId: Int) =
        acceptsMonsterEventContext(objectId) && objectId !in defeatedMonsterObjects

    fun onMonsterFreeze(objectId: Int, x: Int, y: Int) {
        if (!acceptsMonsterEvent(objectId) || !frozenMonsterObjects.add(objectId)) return
        BC(MonsterStageEvent(objectId, 1, x, y))
        println("[Monster] Freeze id=$objectId x=$x y=$y")
    }

    fun onMonsterThaw(objectId: Int) {
        if (!acceptsMonsterEvent(objectId) || !frozenMonsterObjects.remove(objectId)) return
        BC(MonsterStageEvent(objectId, 8))
        println("[Monster] Thaw id=$objectId")
    }

    fun onMonsterKick(objectId: Int, payload: ByteArray, client: ClientSocket) {
        if (!acceptsMonsterEvent(objectId) || payload.size != 18) return
        val definition = spawnedMonsterObjects[objectId] ?: loadedMonsterStage()?.monsters?.get(objectId) ?: return
        if (!definition.type.equals("starFishMon", ignoreCase = true)) return
        val direction = (payload[0].toInt() and 255) xor 0x5a
        val reportedSlot = (payload[17].toInt() and 255) xor 0x5a
        val slot = slots.indexOfFirst { it.user?.c === client }
        if (reportedSlot != 255 && reportedSlot != slot) return
        if (direction !in 0..3 || slot !in slots.indices || slots[slot].user?.c !== client) return
        val bounds = LocalMapBounds.forBoard(loadedMonsterStage()?.name.orEmpty()) ?: return
        val coordinates = (0..3).map { java.nio.ByteBuffer.wrap(payload, 1 + it * 4, 4).int xor 0x96CA5395.toInt() }
        if (coordinates[0] !in 0 until bounds.width * 40 || coordinates[1] !in 0 until bounds.height * 40) return
        if (coordinates[2] != -1 && coordinates[2] !in 0 until bounds.width * 40) return
        if (coordinates[3] != -1 && coordinates[3] !in 0 until bounds.height * 40) return
        val now = System.currentTimeMillis()
        val previous = monsterKickRequests[objectId]
        if (previous != null && previous.first.contentEquals(payload) && now - previous.second < 80) return
        monsterKickRequests[objectId] = payload.copyOf() to now
        frozenMonsterObjects.remove(objectId) // Rolling exits the native locked state; a later hit must lock it again.
        val response = payload.copyOf()
        // S151's decoder splits the unchanged wire order into X and Y endpoint pairs.
        // Native fields are fromX,toX,fromY,toY, not two XY vectors.
        response[17] = (slot xor 0x5a).toByte()
        BC(MonsterStageEvent(objectId, 22, encodedPayload = response))
        println("[Monster] Kick id=$objectId slot=$slot reported=$reportedSlot direction=$direction")
    }

    fun onRobotReady(objectId: Int, phase: Int, hp: Int? = null) {
        if (!acceptsMonsterEvent(objectId) || phase !in 1..2) return
        val stage = loadedMonsterStage() ?: return
        val parts = stage.monsters.values.filter { it.initial && !it.boss && it.type.equals("bossRobot", true) }
        if (parts.size != 2 || parts.none { it.id == objectId }) return
        val boss = stage.monsters.values.singleOrNull { it.boss && it.type.equals("bossRobot", true) } ?: return
        if (phase == 1) {
            if (robotMergeAnnounced || robotReady[objectId] == 2) return
            val part = parts.single { it.id == objectId }
            if (hp == null || hp !in 1..part.hp) return
            robotReadyHealth[objectId] = hp
        } else if (!robotMergeAnnounced || robotReady[objectId] != 1) return
        robotReady[objectId] = phase
        if (phase == 1 && !robotMergeAnnounced && parts.all { (robotReady[it.id] ?: 0) >= 1 }) {
            robotMergeAnnounced = true
            val life = parts.sumOf { robotReadyHealth.getValue(it.id) }.coerceIn(1, 255)
            BC(MonsterStageEvent(parts.first().id, 15, encodedPayload = byteArrayOf((life xor 0x5a).toByte())))
            println("[Monster] Robot combine ready hp=$life")
        } else if (phase == 2 && !robotTransformed && parts.all { robotReady[it.id] == 2 }) {
            robotTransformed = true
            monsterHealth[boss.id] = parts.sumOf { robotReadyHealth.getValue(it.id) }.coerceIn(1, 255)
            BC(MonsterStageEvent(parts.first().id, 16))
            println("[Monster] Robot transform boss=${boss.id} hp=${monsterHealth[boss.id]}")
        }
    }

    fun onRobotLaser(objectId: Int, row: Int, column: Int, kind: Int, direction: Int, color: Int, width: Int, length: Int, duration: Int) {
        if (!acceptsMonsterEvent(objectId) || objectId in lockedBossObjects) return
        val definition = loadedMonsterStage()?.monsters?.get(objectId) ?: return
        if (!definition.type.equals("bossRobot", true) || !validMonsterBombCell(column, row)) return
        if (kind !in 1..2 || direction !in 1..3 || color !in 1..2 || width !in 1..15 || length !in 1..15 || duration !in 1..10000) return
        BC(MonsterLaser(row, column, kind, direction, color, width, length, duration))
    }

    /** Native C116 requests have a different event numbering from S151. */
    fun onMonsterNativeState(objectId: Int, request: Int, payload: ByteArray) {
        if (!acceptsMonsterEvent(objectId) || objectId in lockedBossObjects) return
        val definition = spawnedMonsterObjects[objectId] ?: loadedMonsterStage()?.monsters?.get(objectId) ?: return
        fun word(offset: Int) = ((payload[offset].toInt() and 255) shl 8 or (payload[offset + 1].toInt() and 255)) xor 0xa569
        val event = when (request) {
            2 -> {
                // AttackMon event 7 consumes tick + action; SealMon's action 6
                // additionally consumes its two script-defined roll parameters.
                if (payload.size !in setOf(6, 10)) return
                val action = word(4)
                if (definition.type == "bossMon2" && action == 6) {
                    if (payload.size != 10) return
                } else if (payload.size != 6) return
                7
            }
            6 -> {
                if (definition.type != "pudadakMon" || payload.isNotEmpty() || !shrunkenMonsterObjects.add(objectId)) return
                9
            }
            7 -> {
                if (definition.type != "pudadakMon" || payload.isNotEmpty() || !shrunkenMonsterObjects.remove(objectId)) return
                10
            }
            8, 9, 10 -> {
                if (payload.size != 4) return
                val a = word(0).toShort().toInt(); val b = word(2).toShort().toInt()
                if (a !in -512..512 || b !in -512..512) return
                // Native 586BEC: event11 writes +110/+114 (base X/Y);
                // event12 writes +108/+10C (width/height), event13 is centre.
                when (request) { 8 -> 11; 9 -> 12; else -> 13 }
            }
            14 -> {
                if (!definition.type.equals("bossRobot", true) || !definition.boss || payload.size != 1 || ((payload[0].toInt() and 255) xor 0x5a) != 1 || !robotTransformed) return
                18 // RobotMon copies the merged life supplied by stage event15.
            }
            15 -> {
                if (payload.size != 1 || ((payload[0].toInt() and 255) xor 0x5a) !in 0..1) return
                17 // BasicMon::SetValid; required by both halves and the combined body.
            }
            11 -> {
                // Turtle shell toggles: two encoded bytes, native event 14.
                if (payload.size != 2 || payload.any { ((it.toInt() and 255) xor 0x5a) !in 0..3 }) return
                14
            }
            else -> return
        }
        BC(MonsterStageEvent(objectId, event, encodedPayload = if (request == 14) byteArrayOf() else payload))
        println("[Monster] NativeState id=$objectId type=${definition.type} request=$request event=$event bytes=${payload.size}")
    }

    fun onMonsterCreate(type: Int, parameters: ByteArray, controller: Int) {
        if (!matchRunning || localGameMode != 14 || gameOverSent || !monsterControlGranted) return
        if (type !in 3..22 || parameters.size != 24 || controller !in slots.indices || slots[controller].user == null) return
        // Script-defined objects occupy ids 0..48 in the shipped maps. Dynamic
        // projectiles use a separate pool below the native 120-object limit.
        val stage = loadedMonsterStage() ?: return
        val projectile = type == 9
        val id = (64 until MONSTER_OBJECT_CAPACITY).firstOrNull {
            it !in spawnedMonsterObjects && it !in monsterProjectileObjects
        } ?: return
        val scriptClass = java.nio.ByteBuffer.wrap(parameters, 16, 4).int xor 0x96CA5395.toInt()
        val definition = if (projectile) null else (stage.monsters.values.firstOrNull { it.scriptClass == scriptClass } ?: return)
        // Network ids identify a slot, not a monster lifetime. A recurring red
        // actor must not inherit a killed purple actor's completion objective.
        monsterStageObjectives.remove(id)
        if (projectile) {
            // Native attack/autokick creates a type-9 falling projectile.
            // Its 24-byte init block is positional data, not a monster class.
            monsterProjectileObjects.add(id)
        } else {
            val actor = checkNotNull(definition)
            spawnedMonsterObjects[id] = actor.copy(id = id, initial = false).apply {
                influencesClear = actor.influencesClear
                settlesOnDefeat = actor.settlesOnDefeat
            }
            // Non-boss scripts can replace defeated monsters or create later
            // waves. Those live actors must also be defeated before settlement.
            // Boss summons retain the original boss-only completion objective.
            if (stage.monsters.values.none { it.boss } && actor.influencesClear) monsterStageObjectives.add(id)
        }
        releasedMonsterObjects.remove(id)
        defeatedMonsterObjects.remove(id)
        detonatedMonsterMissiles.remove(id)
        frozenMonsterObjects.remove(id)
        shrunkenMonsterObjects.remove(id)
        angryBossObjects.remove(id)
        lockedBossObjects.remove(id)
        monsterHealth.remove(id)
        BC(MonsterCreate(id, controller, type, parameters))
        println("[Monster] Created script object id=$id type=$type controller=$controller")
    }

    fun onMonsterMissileImpact(objectId: Int, power: Int, x: Int, y: Int) {
        // Native missile death first sends C114, then C117(kind6), then C137.
        // C114 has already marked this actor defeated. It still owes its native
        // explosion until release; ordinary dead monsters remain rejected.
        if (!acceptsMonsterEventContext(objectId) || power !in 0..14 || !validMonsterBombCell(x, y)) return
        val actor = spawnedMonsterObjects[objectId] ?: return
        if (!actor.type.equals("bombMissile", true) && !actor.type.equals("bombMissile2", true)) return
        if (objectId in detonatedMonsterMissiles) return
        // S183 is a dropped-item notification (5486A9 -> 55FE7A/55C96F),
        // not an explosion. Use the established non-player bomb factory and
        // ignite in the same ordered packet stream, with no fuse or idle bomb.
        val bomb = createMonsterBomb(x, y, power, rectangle = true) ?: return
        detonatedMonsterMissiles.add(objectId)
        synchronized(bombs) { bombs.removeIf { it.id == bomb && it.ownerSlotId == 9 } }
        val needsDeath = defeatedMonsterObjects.add(objectId)
        BC(SetBombStateBatch(listOf(bomb)))
        if (needsDeath) BC(MonsterStageEvent(objectId, 4))
        println("[Monster] Missile impact id=$objectId type=${actor.type} x=$x y=$y power=$power")
    }

    fun onMonsterProjectileImpact(objectId: Int, power: Int, x: Int, y: Int) {
        if (!acceptsMonsterEvent(objectId) || objectId !in monsterProjectileObjects) return
        if (power !in 0..14 || !validMonsterBombCell(x, y)) return
        // CGO projectile 58C449 reports kind 4 only after its own landing animation.
        // Duplicate impact requests must not create a second bomb.
        monsterProjectileObjects.remove(objectId)
        releasedMonsterObjects.add(objectId)
        BC(MonsterDespawn(objectId, 1))
        val sealImpact = loadedMonsterStage()?.name?.startsWith("MonSeal") == true
        val bomb = createMonsterBomb(x, y, if (sealImpact) power else power + 1, sealImpact)
        if (sealImpact && bomb != null) {
            synchronized(bombs) { bombs.removeIf { it.id == bomb && it.ownerSlotId == 9 } }
            BC(SetBombStateBatch(listOf(bomb)))
        }
    }

    private fun createMonsterBomb(x: Int, y: Int, power: Int, rectangle: Boolean, target: Pair<Int, Int>? = null, fuseMs: Long? = null, direction: Int = 0): Int? {
        // Native 0x562638 dereferences the tile without a null check. In
        // particular the captured MonPen09 attack included row 13 on a 13-row
        // board. Validate against the physical map, not an assumed 15x15 grid.
        if (!validMonsterBombCell(x, y) || (target != null && !validMonsterBombCell(target.first, target.second))) return null
        val id = synchronized(bombs) {
            repeat(0x2000) {
                val candidate = nextMonsterBombId
                nextMonsterBombId = if (candidate == 0x7fff) 0x6000 else candidate + 1
                if (bombs.none { it.id == candidate }) {
                    bombs += Bomb(candidate, 9, (y shl 8) or x, 0, false, isMoving = target != null,
                        gridX = x, gridY = y,
                        expireAt = System.currentTimeMillis() + (fuseMs ?: if (rectangle) 350L else 2500L)).apply {
                            if (target != null && rectangle && loadedMonsterStage()?.name?.startsWith("MonSeal") == true) landingFuseMs = 0L
                        }
                    return@synchronized candidate
                }
            }
            return null
        }
        // Owner 9 is CBombOwnerNotBomber; the local client factory constructs
        // CGOGameBomb for this owner in monster mode (same 0x1e0 allocation).
        // Attribute 1 selects the native square explosion (5646A0); zero is cross.
        // S139 stores row first (+9c), column second (+a8), unlike script X/Y.
        // Its optional third byte indexes the four native direction vectors.
        BC(SetBomberEvent(9, y, x, id, id, subType = monsterBombContext, flag0 = if (rectangle) 1 else 0,
            flag1 = power.coerceIn(if (rectangle) 0 else 1, 15), targetX = target?.second, targetY = target?.first,
            travelDirection = direction))
        println("[Monster] Bomb id=$id rectangle=$rectangle x=$x y=$y power=$power target=$target")
        return id
    }

    fun onMonsterProjectile(objectId: Int, power: Int, x: Int, y: Int, targetX: Int, targetY: Int, duration: Int, direction: Int = 0) {
        if (!acceptsMonsterEvent(objectId) || objectId in lockedBossObjects) return
        val stage = loadedMonsterStage() ?: return
        val definition = spawnedMonsterObjects[objectId] ?: stage.monsters[objectId] ?: return
        if (power !in 0..14 || !validMonsterBombCell(x, y) || !validMonsterBombCell(targetX, targetY) || duration !in 1..10000 || direction !in 0..3) return
        createMonsterBomb(x, y, if (stage.name.startsWith("MonSeal")) power else power + 1, stage.name.startsWith("MonSeal"), targetX to targetY, duration.toLong(), direction)
    }

    fun onMonsterAttack(objectId: Int, kind: Int, parameter: Int, power: Int, x: Int, y: Int) {
        if (!acceptsMonsterEvent(objectId) || objectId in lockedBossObjects) return
        val stage = loadedMonsterStage() ?: return
        val definition = spawnedMonsterObjects[objectId] ?: stage.monsters[objectId] ?: return
        if (!validMonsterBombCell(x, y)) return
        when (kind) {
            1 -> {
                if (parameter !in 0..3 || power !in 0..14) return
                // directionDest requests a directional water line at the script
                // position, not a flying bomb. Native autokick is handled by kind 3.
                val ids = monsterDirectionalCells(x, y, parameter, power).mapNotNull { (bx, by) ->
                    createMonsterBomb(bx, by, 0, true)
                }
                if (ids.isNotEmpty()) {
                    val idSet = ids.toSet()
                    synchronized(bombs) { bombs.removeIf { it.ownerSlotId == 9 && it.id in idSet } }
                    // Initialize and ignite in the same request; no flight or fuse.
                    BC(SetBombStateBatch(ids))
                }
            }
            2 -> {
                if (!definition.boss || parameter !in 1..14) return
                // Each script rectangle step is a border, not a filled square.
                // Radius-zero native square explosions affect exactly one cell.
                monsterRectangleBorder(x, y, parameter).forEach { (bx, by) ->
                    createMonsterBomb(bx, by, 0, true)
                }
            }
        }
    }

    internal fun monsterDirectionalCells(x: Int, y: Int, direction: Int, power: Int): List<Pair<Int, Int>> {
        require(direction in 0..3 && power in 0..14)
        val (dx, dy) = listOf(-1 to 0, 0 to -1, 1 to 0, 0 to 1)[direction]
        return (0..power).map { x + dx * it to y + dy * it }
            .takeWhile { (cx, cy) -> cx in 0..14 && cy in 0..14 }
    }

    internal fun monsterRectangleBorder(x: Int, y: Int, distance: Int): List<Pair<Int, Int>> {
        require(distance in 1..14)
        return buildList {
            for (bx in x - distance..x + distance) {
                for (by in y - distance..y + distance) {
                    if (bx !in 0..14 || by !in 0..14) continue
                    if (kotlin.math.abs(bx - x) == distance || kotlin.math.abs(by - y) == distance) add(bx to by)
                }
            }
        }
    }

    fun onMonsterSpeed(objectId: Int, speed: Int) {
        if (!acceptsMonsterEvent(objectId) || objectId in lockedBossObjects || speed !in 1..255) return
        BC(MonsterSpeed(objectId, speed))
        println("[Monster] Speed id=$objectId value=$speed")
    }

    fun onMonsterFinish(objectId: Int, playerSlot: Int) {
        if (!acceptsMonsterEvent(objectId) || objectId !in lockedBossObjects) return
        if (playerSlot !in slots.indices || slots[playerSlot].user == null) return
        lockedBossObjects.remove(objectId)
        defeatedMonsterObjects.add(objectId)
        BC(MonsterStageEvent(objectId, 4))
        println("[Monster] Boss popped id=$objectId slot=$playerSlot")
    }

    fun onMonsterDamage(objectId: Int, damage: Int, playerSlot: Int) {
        if (!acceptsMonsterEvent(objectId) || damage <= 0 || objectId in lockedBossObjects) return
        if (playerSlot != 65535 && (playerSlot !in slots.indices || slots[playerSlot].user == null)) return
        val definition = spawnedMonsterObjects[objectId] ?: loadedMonsterStage()?.monsters?.get(objectId)
        if (definition?.boss == true && playerSlot == 65535) return
        val scriptedIceDamage = playerSlot == 65535 && definition?.type == "iceMon" &&
            LocalMonsterScriptDamage.permits(loadedMonsterStage()?.name.orEmpty(), definition, damage)
        val frozen = frozenMonsterObjects.remove(objectId)
        if (!frozen) {
            // Ice monsters require freezing and contact, including those mixed
            // into ordinary stages. Resource HP is authoritative for bosses.
            if (definition == null || (definition.type == "iceMon" && !scriptedIceDamage)) return
            val remaining = (monsterHealth[objectId] ?: definition.hp) - damage
            monsterHealth[objectId] = remaining.coerceAtLeast(0)
            if (definition.boss || definition.type.equals("bossRobot", ignoreCase = true)) {
                BC(MonsterHealth(objectId, remaining.coerceAtLeast(0)))
                if (remaining > 0 && definition.angerHp > 0 && remaining <= definition.angerHp && angryBossObjects.add(objectId)) {
                    BC(MonsterStageEvent(objectId, 0))
                    println("[Monster] Boss anger id=$objectId hp=$remaining threshold=${definition.angerHp}")
                }
            }
            if (remaining > 0) return
            if (definition.boss) {
                lockedBossObjects.add(objectId)
                BC(MonsterBossLocked(objectId))
                println("[Monster] Boss trapped id=$objectId slot=$playerSlot")
                return
            }
        }
        defeatedMonsterObjects.add(objectId)
        BC(MonsterStageEvent(objectId, 4))
        if (scriptedIceDamage) println("[Monster] Script self-damage completed id=$objectId map=${loadedMonsterStage()?.name}")
        println("[Monster] Monster defeated id=$objectId slot=$playerSlot")
        // Terminal script-created starfish can retain their dead native object
        // without sending C137. Their confirmed death completes the objective;
        // actors with selfdie triggers still wait for their replacement flow.
        if (spawnedMonsterObjects[objectId]?.settlesOnDefeat == true) checkMonsterStageClear()
    }

    private fun checkMonsterStageClear() {
        if (!matchRunning || localGameMode != 14 || gameOverSent || monsterTransitionPending) return
        val stage = loadedMonsterStage() ?: return
        if (monsterStageObjectives.isEmpty() || !monsterStageObjectives.all {
                it in defeatedMonsterObjects && (it in releasedMonsterObjects || spawnedMonsterObjects[it]?.settlesOnDefeat == true)
            }) return
        val next = stage.next?.let { name ->
            bnbemu.data.FxdynMapInfo.gameRecords.filter { it.flag == 0 }.withIndex().firstOrNull { it.value.name == name }
        }
        if (next == null) {
            emitMatchResult(true, slots.indexOfFirst { it.user != null })
            return
        }
        monsterTransitionPending = true
        val generation = roomGeneration.get()
        endGame()
        println("[MonsterStage] Cleared ${stage.name}; loading ${next.value.name}")
        // Keep settlement visible, then replace the board inside CGameStage.
        // The native C131 acknowledgement gates S136; supplies are not refilled.
        val rewards = settleEconomy(true, false)
        BC(InitGameResult(mode = 0, mapId = 0), GameResult(winnerTeam = 0, delayFrames = 60, missionClear = false, rewards = rewards))
        executor.schedule({
            if (roomGeneration.get() != generation || !monsterTransitionPending) return@schedule
            startGame(next.index)
            monsterBoardLoadPending = true
            monsterBombContext = 2
            BC(MonsterNextBoard(next.value.name, Random.nextInt(0x7fffffff)))
        }, 5000, TimeUnit.MILLISECONDS)
    }

    /** Only called by the explicitly armed, local one-shot crash reproduction. */
    internal fun advanceCrashProbeBoards() {
        val stage = loadedMonsterStage() ?: return
        if (stage.name !in setOf("MonPen07", "MonPen08") || localGameMode != 14 || !matchRunning || !monsterControlGranted) return
        val targets = (stage.monsters.values.filter { it.initial } + spawnedMonsterObjects.values)
            .filter { it.id !in defeatedMonsterObjects && it.id !in releasedMonsterObjects }
            .filterNot { stage.name == "MonPen08" && LocalMonsterScriptDamage.permits(stage.name, it, 1) }
        for (monster in targets) {
            if (monster.type == "iceMon") onMonsterFreeze(monster.id, 0, 0)
            onMonsterDamage(monster.id, monster.hp, 0)
        }
    }

    internal fun checkMonsterTimeout(nowNanos: Long = System.nanoTime()) {
        val deadline = monsterDeadlineNanos ?: return
        if (!matchRunning || localGameMode != 14 || gameOverSent || nowNanos < deadline) return
        monsterDeadlineNanos = null
        emitMatchResult(false, 1, "怪物模式时间耗尽")
    }

    internal fun checkAiBattleTimeout(nowNanos: Long = System.nanoTime()) {
        val deadline = aiBattleDeadlineNanos ?: return
        if (!matchRunning || localGameMode != 16 || gameOverSent || nowNanos < deadline) return
        aiBattleDeadlineNanos = null
        // The challenge was not completed in time. Use the existing verified
        // failure result / reward / return-to-room flow, never an extra death.
        emitMatchResult(false, slots.indexOfFirst { it.isAI }.takeIf { it >= 0 } ?: 1, "激战模式时间耗尽")
    }

    // 客户端上报槽位事件 (Opcode 149) 时触发
    fun onEventItem(slotId: Int, token: Int, action: Int, param: Int) {
    }

    // 可移动箱子 (CGOTileMovableBox) 的最新推动状态。
    // 来源: C→S op 109 PushMovableBoxReq, 消费后必须广播 S→C op 142 MovableBoxMove。
    // 客户端 (CA.exe 0x0054CABA) 只做格子校验 + 入队, 没有任何本地位移预测,
    // 箱子位移的唯一实现 0x565CE0 只有一个调用者 —— S→C op 142 的 handler 0x0054A5F9。
    // 所以「不回包」会让箱子在任何客户端都不动。
    data class MovableBoxState(
        var gridX: Int,
        var gridY: Int,
        var targetX: Int,
        var targetY: Int,
        var slotId: Int
    )

    private val movableBoxes = ConcurrentHashMap<Int, MovableBoxState>()
    private val movableBoxSentAt = ConcurrentHashMap<Int, Long>()

    // 完全相同的推箱动作(同 boxSeq + 同 src/dst)在该窗口内只广播一次。
    //
    // 去重是必须的: 客户端 0x0054CABA 之后进入 0x5393C2 的待确认队列, OnTick (0x0053A700)
    // 每帧检查、重试计数 > 5 就把**同一个动作**再发一次。而客户端 0x565CE0 会先校验
    // "箱子此刻仍在 (gridX,gridY)" —— 同一个 src→dst 广播第二次必然校验失败, 各客户端会
    // 触发 0x549C35 往服务端回 C→S op 149 回滚上报。
    // 但窗口必须有限: 万一一端的箱子因为漏包/校验失败没动, 玩家再推一次时必须能重播自愈,
    // 否则那个箱子会永久卡死。窗口取 400ms, 远大于一次重发周期(帧级), 又短于人的连点间隔。
    // 箱子被推回去再推回来时 src/dst 都不同, 不会被误判成重复。
    internal var movableBoxDedupeWindowMs = 400L

    // 返回是否真的广播了 (重复上报不广播, 便于测试/调试)。
    fun onMovableBoxPush(
        boxSeq: Int,
        gridX: Int,
        gridY: Int,
        targetX: Int,
        targetY: Int,
        slotId: Int
    ): Boolean {
        // boxSeq/末尾字节都是 E1/E2 原样字段, 只做宽度兜底, 不做语义过滤:
        // 末尾字节是客户端 CGameMsgCache 待确认队列下标, 收到的这一端 (0x565CE0 第 6 参数) 并不使用,
        // 过滤掉反而可能让某些槽位的推箱永远不生效。
        if (boxSeq !in 0..0xFFFF || slotId !in 0..0xFF) return false

        val now = System.currentTimeMillis()
        val next = MovableBoxState(gridX, gridY, targetX, targetY, slotId)
        val prev = movableBoxes[boxSeq]
        if (prev == next && now - (movableBoxSentAt[boxSeq] ?: 0L) < movableBoxDedupeWindowMs) {
            return false
        }
        movableBoxes[boxSeq] = next
        movableBoxSentAt[boxSeq] = now

        // 广播给所有客户端(含推箱者本人): 推箱者自己也需要这一包才会看到箱子动
        BC(MovableBoxMove(boxSeq, gridX, gridY, targetX, targetY, slotId))
        return true
    }

    fun movableBoxState(boxSeq: Int): MovableBoxState? = movableBoxes[boxSeq]

    // 角色死亡上报处理 (C→S op 154 action=5 -> CA.exe 0x0046DB1D CGOGameBomber::OnDie)
    //
    //   这条上报只对**真人玩家**成立 —— 0x46DB01 处先调 0x45FBD3,
    //   其判据是 `0 <= bomber+0x4D0 < 2`, 而 `+0x4D0` 是调试串 0x892838 "m_selfidx = " 所指的
    //   **网络玩家序号** (相邻的 +0x4DC 是 "m_faislot", AI 槽号)。AI 的 m_selfidx 恒为 -1
    //   (bomber 初始化 0x4680xx 置 -1, 只有真人在 0x639FE3 循环里经 0x45FDDD 赋 0/1),
    //   所以**杀光 AI 时一个 action=5 都不会来**。整段实战日志 (1510 行 / 72 个 op154) 里
    //   action 只有 4 / 1 / 0, 没有 2 / 3 / 5, 也印证了这点。
    //   → 「玩家赢」必须靠 [onLastTeamStanding] (op112 action=0x60); 这里保留 action=5 是为了
    //     处理玩家自己阵亡 (以及将来真正的多真人房)。
    //
    // 结算判定规则 (2026-11-05 修正):
    //   旧实现只在 slotId == 0 (玩家自己阵亡) 时下发结算, 于是「杀光全部 AI」之后对局永远不会结束 ——
    //   客户端只是把每个阵亡的 bomber 转发给服务端, 自身没有任何"敌人清空"判定, 全部依赖服务端下令。
    //   现在按 Slot.matchState (OUT_OF_MATCH / ALIVE / DEAD) 统计双方存活人数, 任意一方全灭即结束对局:
    //     · 人类全灭            → AI 胜 (与原行为一致, 胜利者取一个仍活着的 AI 槽位)
    //     · AI 全灭 && 人类存活 → 人类胜 (胜利者取玩家槽位, GameResult winnerTeam = 0)
    //   结算包固定 1.5s 延迟下发: 客户端要先播完阵亡动画。
    //   顺序 op 169 InitGameResult → op 150 GameResult（缺 169/150 不弹结算窗）。
    fun onBomberDie(slotId: Int) {
        if (localGameMode == 14 && (monsterTransitionPending || monsterBoardLoadPending)) return
        if (slotId !in slots.indices) return

        val slot = slots[slotId]
        // 只有"参战且存活"的槽位才接受阵亡上报:
        //   · DEAD       → 重复上报 (CGameMsgCache 重发 / 抓包残流 / 连续两次 OnDie), 忽略,
        //                  否则会重复广播 op 159 并可能触发第二套结算包;
        //   · OUT_OF_MATCH → 空槽的错位/迟到上报, 忽略, 绝不能给没有 bomber 的槽位发 op 159
        //                  (客户端 0x0054C378 会拿 slotId 去 session 的 bomber 数组取对象)。
        if (slot.matchState != SlotMatchState.ALIVE) return
        slot.matchState = SlotMatchState.DEAD
        if (localGameMode == 14)
            println("[MonsterDeathAudit] player=$slotId actualMap=$activeMapId selectedMap=$mapId defeated=${defeatedMonsterObjects.sorted()} released=${releasedMonsterObjects.sorted()}")

        // 广播 S→C op 159 BomberDie 触发客户端播放死亡动画与淘汰状态设置 (CA.exe 0x0054C378)
        BC(BomberDie(slotId))
        println(
            "[Room] BomberDie slot=$slotId (" + (if (slot.user != null) "人类" else if (slot.isAI) "AI" else "空槽") +
                ") | 存活人类=${slots.indices.filter { slots[it].user != null && slots[it].isAlive }} " +
                "存活AI=${slots.indices.filter { slots[it].isAI && slots[it].isAlive }}"
        )

        finishMatchIfDecided()
    }

    // 客户端"只剩一支队伍"上报 (C→S op 112 action=0x60) —— EN 客户端唯一的对局结束信号
    //
    // 逆向 (CA.exe 0x54B380~0x54B450, 帧更新里每帧跑一次):
    //   1) 开一个 9 槽的计数组 [ebp-0x5C] (0x24 = 9*4), 对每个 bomber:
    //        if (bomber->vtable[0xAC]() != 0)  ++cnt[bomber->+0x84]      // +0x84 = 队伍号 (0..8)
    //   2) 统计非空队伍的个数 n; 若 n > 1 直接 ret (还有两队以上, 对局继续)
    //   3) n == 1 时, 对每个 bomber:
    //        if (bomber->+0x8 != 0 && bomber->vtable[0x150]() == 0)      // 0x45FBA0: 不是空壳
    //            0x54A773(bomber) -> 发 C→S op 112: E1(0x60 | slot) + E2(x) + E2(y)
    //   即:**客户端自己判定"只剩一支队伍", 然后对存活方每帧狂发 action=0x60**, 一直发到服务端
    //   下发结算为止 (实测杀光 AI 后连发数百条, 其它局内包全部停摆 —— 客户端卡在这个等待里)。
    //
    // 所以这个包就是"赢"的唯一信号, 而且双向通用:
    //   · 存活方是真人槽 → 玩家胜 (客户端已本地判死全部 AI, 但永远不会为 AI 发 op154 action=5,
    //     见 docs/agents-notes-battle.md: op154 action=5 有 m_selfidx 闸门, AI 恒不通过)
    //   · 存活方是 AI 槽   → AI 胜 (玩家全灭, 这时通常也已经有 op154 action=5 slot=0 先到)
    //
    // ⚠️ 不要再给落败槽位广播 S→C op 159: 客户端本地阵亡时已经播过死亡动画 (OnDie 是本地函数),
    //    服务端只是"不知道", 再来一条 159 会让死亡动作重播。
    fun onLastTeamStanding(slotId: Int) {
        if (slotId !in slots.indices) return
        if (gameOverSent) return

        // 单座位房 (1 人无 AI) 客户端从第一帧就会满足"只剩一队", 那不是胜负而是没对手;
        // 真正参战的房间至少要有两个座位 (练习房 = 1 真人 + 1..7 AI)
        if (activeSlotCount < 2) return

        val winner = slots[slotId]
        // 空槽上报丢弃: 客户端只会替"真人或 AI"的存活槽位发这个包 (vtable[0x150] == 0 的过滤)
        if (!winner.hasSeat()) return

        val humanWin = winner.user != null
        // 落败方一律补记 DEAD, 让 matchState 与服务端认知一致 (客户端本地早就判死了)
        slots.forEach { s ->
            val sameSide = if (humanWin) s.user != null else s.isAI
            if (s.hasSeat() && !sameSide) s.matchState = SlotMatchState.DEAD
        }

        // action=0x60 是每帧重发的, 这里靠 gameOverSent 去重
        emitMatchResult(humanWin, slotId)
    }

    /**
     * 一方全灭即结束对局。调用前必须已经把阵亡槽位标记为 [SlotMatchState.DEAD]。
     * 只有[人类]与[AI]两个阵营都还有人时才继续对局; 任一方空出则立刻结算。
     */
    private fun finishMatchIfDecided() {
        if (gameOverSent) return

        // 阵营划分: 有人坐着 = 人类阵营, isAI = AI 阵营。不参战的空槽两边都进不去。
        val humanIdx = slots.indices.filter { slots[it].user != null }
        val enemyIdx = slots.indices.filter { slots[it].isAI }
        if (humanIdx.isEmpty()) return          // 没有人类就没有"人类胜负"可言, 交给上层流程

        val humanAlive = humanIdx.filter { slots[it].isAlive }
        val enemyAlive = enemyIdx.filter { slots[it].isAlive }

        // 结束条件只有两个: 人类全灭, 或 AI 全灭 (enemyIdx 为空时不存在"AI 全灭"这种胜负)。
        // 这样即使将来开了纯人类 PvP 房, 死一个人也不会被误判成"敌人清空"。
        val humanWiped = humanAlive.isEmpty()
        val enemyWiped = enemyIdx.isNotEmpty() && enemyAlive.isEmpty()
        if (!humanWiped && !enemyWiped) return   // 双方都还有人, 继续打

        // 人类全灭(含与 AI 同时全灭) → AI 胜, 沿用旧的 winnerSlotId = 1 语义;
        // 若还有 AI 活着, 胜利跳舞交给真正活下来的那个槽位。
        val humanWin = !humanWiped
        val winnerSlot = when {
            humanWin -> humanAlive.first()
            enemyAlive.isNotEmpty() -> enemyAlive.first()
            else -> 1
        }

        emitMatchResult(humanWin, winnerSlot, "存活人类=$humanAlive 存活AI=$enemyAlive")
    }

    /**
     * 真正下发结算包。两条触发路径共用:
     *   · [finishMatchIfDecided]  —— 靠 op154 action=5 逐个记录阵亡后的服务端统计
     *   · [onLastTeamStanding]    —— 靠 op112 action=0x60 客户端自报"只剩一支队伍"
     *
     * 固定顺序 op 169 InitGameResult → op 150 GameResult（客户端 0x55856A → 0x5584CC 建
     * CGameResultDocument，op 150 再填金币/经验并弹窗）。
     */
    private fun settleEconomy(won: Boolean, boss: Boolean): Map<Int, LocalEconomy.Reward> {
        if (economySettled || economyReceipt.isEmpty()) return emptyMap()
        economySettled = true
        val requested = LocalEconomy.reward(localGameMode, won, boss)
        if (requested == LocalEconomy.Reward()) return emptyMap()
        val rewards = mutableMapOf<Int, LocalEconomy.Reward>()
        val credited = mutableSetOf<String>()
        slots.forEachIndexed { index, slot ->
            val user = slot.user ?: return@forEachIndexed
            if (!credited.add(user.accountKey.lowercase(java.util.Locale.ROOT))) return@forEachIndexed
            try {
                val before = LocalShop.store.account(user.accountKey)
                val after = LocalShop.store.reward(user.accountKey, economyReceipt, requested).account
                user.applyAccount(after)
                val actual = LocalEconomy.Reward(after.lucci - before.lucci, after.totalExperience - before.totalExperience)
                // Result shows the round award; only the capped actual delta enters the wallet.
                rewards[index] = LocalEconomy.resultReward(requested, actual)
                user.c send bnbemu.packet.UserDynamicInfo(0, user.lucci)
                LocalEconomy.walletLimitNotice(requested,actual)?.let { message ->
                    pendingWalletNotices[user.c]=message
                }
                println("[Economy] mode=$localGameMode win=$won boss=$boss gold=${actual.gold} exp=${actual.experience} level=${user.level} balance=${user.lucci}")
            } catch (e: java.io.IOException) {
                System.err.println("[Economy] Reward save failed: ${e.javaClass.simpleName}; no reward announced")
            }
        }
        return rewards
    }

    private fun emitMatchResult(humanWin: Boolean, winnerSlot: Int, detail: String = "") {
        if (gameOverSent) return
        gameOverSent = true
        println(
            "[Room] 对局结束: " + (if (humanWin) "玩家胜" else "AI 胜") +
                " | winnerSlot=$winnerSlot" + (if (detail.isEmpty()) "" else " | $detail")
        )

        val generation = roomGeneration.get()
        executor.schedule({
            if (roomGeneration.get() != generation || !gameOverSent) return@schedule
            val rewards = settleEconomy(humanWin, loadedMonsterStage()?.monsters?.values?.any { it.boss } == true)
            BC(
                InitGameResult(mode = 0, mapId = 0),
                GameResult(winnerTeam = if (humanWin) 0 else 1, delayFrames = 60, rewards = rewards)
            )
            // 结算后停掉对局主循环 (水泡倒计时), 与客户端离开棋盘同步
            endGame()
            executor.schedule(returnToRoom@{
                if (roomGeneration.get() != generation || !gameOverSent) return@returnToRoom
                BC(ReturnToRoom())
                // Allow a frame to finish destroying CGameStage before room-only packets.
                executor.schedule({
                    if (roomGeneration.get() == generation && gameOverSent) {
                        uniqueClients.forEach { client ->
                            sendLocalRoomSnapshot(client)
                            // CGameStage has a separate S23 handler. Display
                            // this notice only after returning to the room.
                            pendingWalletNotices.remove(client)?.let { client send bnbemu.packet.StageNotice(it) }
                        }
                        println("[Room] Returned to existing room mode=$localGameMode map=$mapId")
                    }
                }, 500, TimeUnit.MILLISECONDS)
            }, returnToRoomDelayMs, TimeUnit.MILLISECONDS)
        }, gameOverDelayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * 把某个已阵亡的槽位拉回场上（S→C op 160 [ReviveBomber] 的入口）。
     *
     * 目前**没有任何调用点**：这是给"复活币"类道具预留的基础设施 —— 客户端侧 0x55345D 会
     * 把 bomber 置活、清掉陷阱/水泡并播状态 43 的复活动作，服务端这里同步自己的 `matchState`，
     * 否则下一次 [finishMatchIfDecided] 仍会把这名玩家算作阵亡。
     *
     * 接入复活币时要补的东西（等 C→S 侧的道具使用包实测后再接）：
     *   1. 道具校验（持有量 / 是否局内 / 是否真死），
     *   2. 扣道具 + 下发对应的背包同步包，
     *   3. 决定复活后的位置/保护期（客户端 0x4606C2 会把位置交还给本地状态机）。
     *
     * @return true 表示确实下线了复活的 160 包
     */
    fun reviveBomber(slotId: Int): Boolean {
        if (slotId !in slots.indices) return false
        if (gameOverSent) return false              // 已经结算, 复活没有意义
        val slot = slots[slotId]
        if (!slot.hasSeat()) return false           // 空槽没有 bomber 可复活
        if (slot.matchState != SlotMatchState.DEAD) return false

        slot.matchState = SlotMatchState.ALIVE
        BC(ReviveBomber(slotId))
        println("[Room] ReviveBomber slot=$slotId")
        return true
    }

    // 结算结果包的下发延迟。测试可置 0 以便同步断言。
    internal var gameOverDelayMs = 1500L

    // 是否已经下发过本局结算 (防止重复结算)
    internal var gameOverSent = false

    fun addBomb(
        slotId: Int,
        gridX: Int,
        gridY: Int,
        isSpecial: Boolean = false,
        unk: Int = 0,
        bombId: Int? = null,
        broadcastInitialState: Boolean = true,
        flightDurationMs: Int? = null
    ): Int {
        val allocatedId = bombId ?: nextBombId++
        if (allocatedId >= nextBombId) {
            nextBombId = allocatedId + 1
        }
        executor.execute {
            val bomb = Bomb(allocatedId, slotId, (gridY shl 8) or gridX, 0, isSpecial).apply {
                this.gridX = gridX
                this.gridY = gridY
                this.isMoving = flightDurationMs != null
                this.expireAt = System.currentTimeMillis() + (flightDurationMs?.toLong() ?: 2500L)
            }
            synchronized(bombs) {
                bombs.removeIf { it.id == allocatedId }
                bombs += bomb
            }
            if (broadcastInitialState) {
                BC(SetBombState(allocatedId, state = 1))
            }
        }
        return allocatedId
    }

    fun getBomb(bombId: Int): Bomb? {
        return bombs.find { it.id == bombId }
    }

    fun onBombKickThrow(actionSlot: Int, bombId: Int, fromRow: Int, fromColumn: Int, targetRow: Int, targetColumn: Int, duration: Int) {
        executor.execute {
            if (!matchRunning || gameOverSent) return@execute
            val bomb = getBomb(bombId) ?: return@execute
            bomb.gridX = targetRow; bomb.gridY = targetColumn
            // Native S141 uses 0..3 for glove flight and 4..7 for shoe movement.
            // Both need the landing S138 before explosion; state0 while flying is ignored.
            bomb.isMoving = true
            bomb.expireAt = System.currentTimeMillis() + duration
            BC(BombKickThrow(actionSlot, bombId, fromRow, fromColumn, targetRow, targetColumn))
        }
    }

    fun BC(vararg packets: PKT) {
        uniqueClients.forEach { client ->
            packets.forEach { client.ctx.write(it) }
            client.ctx.flush()
        }
    }

    infix fun BC(packet: PKT) {
        uniqueClients.forEach { client ->
            client.ctx.writeAndFlush(packet)
        }
    }

    fun encodeSlots(c: ClientSocket) {
        slots.forEachIndexed { index, slot ->
            val user = slot.user
            if (user != null) {
                c send SetPlayer(index, slot, user)
            } else {
                c send SetSlotState(index, slot)
            }
        }
    }

    fun endGame() {
        aiBattleDeadlineNanos = null
        matchRunning = false
        monsterDeadlineNanos = null
        tickFuture?.cancel(false)
        bombs.clear()
        movableBoxes.clear()
        movableBoxSentAt.clear()
    }

    // 座位上有人 (人类玩家或 AI)：这是"能不能参战"的判据, 只看座位占用, 不看本局死活。
    private fun Slot.hasSeat(): Boolean = user != null || isAI

    /**
     * 重置一局的参战/存活状态与结算标志 (开局时调用)。
     *
     * 参战槽位 (座位上有人) 置 ALIVE，空槽置 OUT_OF_MATCH —— 保留"空槽"这个第三态，
     * 而不是简单把所有槽位都置 ALIVE，否则 [onBomberDie] 的守卫挡不住
     * "给根本没有 bomber 的槽位广播 op 159" 这类错位/迟到上报。
     *
     * 这里同时把"谁参战"定格成一局开始时的快照：对局中途有人离开也不会把他从结算名单里
     * 抹掉（人是这一局的参战者，离开只是掉线）。
     *
     * ⚠️ 将来若支持"中途入座"，新玩家入座时必须把该槽位置回 [SlotMatchState.ALIVE]，
     *    否则他的阵亡上报会被当成"不参战/重复上报"吞掉，该玩家阵亡后对局永远不会结束。
     */
    fun resetMatchState() {
        gameOverSent = false
        slots.forEach { it.matchState = if (it.hasSeat()) SlotMatchState.ALIVE else SlotMatchState.OUT_OF_MATCH }
    }

    fun reset() {
        pendingWalletNotices.clear()
        aiBattleDeadlineNanos = null
        economyReceipt = ""
        economySettled = false
        monsterBoardLoadPending = false
        monsterBoardStartSent = false
        matchRunning = false
        monsterDeadlineNanos = null
        cancelPendingReturn()
        tickFuture?.cancel(false)
        bombs.clear()
        movableBoxes.clear()
        movableBoxSentAt.clear()
        nextBombId = 0
        nextScriptBombId = 600
        lastAirplaneTime = 0L
        resetMatchState()
    }
}
