package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// IceMon::Freeze (0x58858A) and thaw (0x5882D0), C116.
val MonsterStateReq = PKTHandler { c ->
    if (Room.uniqueClients.none { it === c } || readableBytes() < 4) return@PKTHandler
    val objectId = Decode2
    val event = Decode2
    when {
        event == 1 && readableBytes() == 1 -> Room.onMonsterSpeed(objectId, Decode1)
        event == 4 && readableBytes() == 8 -> Room.onMonsterFreeze(objectId, Decode4, Decode4)
        event == 5 && readableBytes() == 0 -> Room.onMonsterThaw(objectId)
        event == 12 && readableBytes() == 2 -> Room.onRobotReady(objectId, 1, Decode2)
        event == 13 && readableBytes() == 0 -> Room.onRobotReady(objectId, 2)
        event in setOf(2, 6, 7, 8, 9, 10, 11, 14, 15) -> {
            val payload = ByteArray(readableBytes()); readBytes(payload)
            Room.onMonsterNativeState(objectId, event, payload)
        }
        else -> {
            if (event != 0) println("[Monster] UnhandledState id=$objectId event=$event bytes=${readableBytes()}")
            skipBytes(readableBytes()) // Local movement and initial serialization.
        }
    }
}

// IceMon sends C114 only after a frozen monster is touched/struck.
val MonsterDamageReq = PKTHandler { c ->
    if (Room.uniqueClients.none { it === c } || readableBytes() != 6) return@PKTHandler
    val objectId = Decode2
    val damage = Decode2
    val playerSlot = Decode2
    Room.onMonsterDamage(objectId, damage, playerSlot)
}

// Actual shell-kick capture: C168 = id + direction + four coordinates + player.
// StarFishMon S151 event22 consumes the same encoded payload after the id.
val MonsterKickReq = PKTHandler { c ->
    if (Room.uniqueClients.none { it === c } || readableBytes() != 20) return@PKTHandler
    val id = Decode2
    val payload = ByteArray(18); readBytes(payload)
    Room.onMonsterKick(id, payload, c)
}

// AttackMon::OnBomberCollision (0x584C31), virtual RequestFinish 0x58492F.
val MonsterFinishReq = PKTHandler { c ->
    if (Room.uniqueClients.none { it === c } || readableBytes() != 4) return@PKTHandler
    val objectId = Decode2
    val reportedSlot = Decode2
    val senderSlot = Room.slots.indexOfFirst { it.user?.c === c }
    // Native GetPlayerIndex can remain -1 in the local monster scene.
    if (reportedSlot != 65535 && reportedSlot != senderSlot) return@PKTHandler
    Room.onMonsterFinish(objectId, senderSlot)
}

// CNetObjectMan::RequestCreate (0x64C5F4): E2 type + encoded init data.
val MonsterCreateReq = PKTHandler { c ->
    if (Room.uniqueClients.none { it === c } || readableBytes() != 26) return@PKTHandler
    val type = Decode2
    val parameters = ByteArray(24)
    readBytes(parameters)
    val controller = Room.slots.indexOfFirst { it.user?.c === c }
    Room.onMonsterCreate(type, parameters, controller)
}


// C117 builders: directionDest 0x548441 and rectangle 0x54854B.
val MonsterAttackReq = PKTHandler { c ->
    if (Room.uniqueClients.none { it === c } || readableBytes() !in setOf(6, 7, 11)) return@PKTHandler
    val id = Decode2; val kind = Decode1
    if (kind == 6 && readableBytes() == 3) {
        val power = Decode1; val y = Decode1; val x = Decode1
        Room.onMonsterMissileImpact(id, power, x, y)
    } else if (kind == 4 && readableBytes() == 3) {
        val power = Decode1; val y = Decode1; val x = Decode1
        Room.onMonsterProjectileImpact(id, power, x, y)
    } else if (kind == 2 && readableBytes() == 3) {
        val distance = Decode1; val y = Decode1; val x = Decode1
        Room.onMonsterAttack(id, kind, distance, 0, x, y)
    } else if (kind == 1 && readableBytes() == 4) {
        val power = Decode1; val direction = Decode1; val y = Decode1; val x = Decode1
        Room.onMonsterAttack(id, kind, direction, power, x, y)
    } else if (kind == 3 && readableBytes() == 8) {
        // 0x5485D5: power|0x20, target row/column, direction, E2 duration, origin Y/X.
        val power = Decode1 and 0x1f
        val targetY = Decode1; val targetX = Decode1
        val direction = Decode1; val duration = Decode2
        val y = Decode1; val x = Decode1
        if (direction in 0..3) Room.onMonsterProjectile(id, power, x, y, targetX, targetY, duration, direction)
    } else skipBytes(readableBytes())
}

// C155 58DCC7; native S268 548817 consumes type9 followed by the script laser fields.
val MonsterLaserReq = PKTHandler { c ->
    if (Room.uniqueClients.none { it === c } || readableBytes() != 14) return@PKTHandler
    val id = Decode2; val row = Decode1; val column = Decode1
    if (Decode1 != 9) return@PKTHandler
    val kind = Decode1; val direction = Decode1; val color = Decode1
    val width = Decode1; val length = Decode1; val duration = Decode4
    Room.onRobotLaser(id, row, column, kind, direction, color, width, length, duration)
}
