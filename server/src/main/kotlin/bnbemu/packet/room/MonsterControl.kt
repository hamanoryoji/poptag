package bnbemu.packet.room

import bnbemu.netty.PKT

// EN 0x64EBC9 -> 0x64DD98: E2(object id), E1(local player slot).
// Unknown ids are explicitly ignored by FindObject (0x64D573).
fun MonsterControl(objectId: Int, playerSlot: Int) = PKT {
    Encode2(objectId)
    Encode1(playerSlot)
}

// EN 0x64EBC9 -> 0x64DCE2: remove a registered network object.
fun MonsterDespawn(objectId: Int, parameter: Int) = PKT {
    Encode2(objectId)
    Encode4(parameter)
}

// Stage-clear component id 14 (0x57B966 / 0x5698FC). It resolves the
// monster's secondary interface before dispatching the native ice event.
fun MonsterStageEvent(objectId: Int, event: Int, x: Int = 0, y: Int = 0, encodedPayload: ByteArray? = null) = PKT {
    Encode2(14)
    Encode2(objectId)
    Encode1(event)
    if (encodedPayload != null) {
        EncodeBuffer(encodedPayload)
    } else if (event == 1) {
        Encode4(x)
        Encode4(y)
    }
}

// AttackMon (0x585207) and BlueMon (0x587604): event 2, E2 HP.
fun MonsterHealth(objectId: Int, hp: Int) = PKT {
    Encode2(14); Encode2(objectId); Encode1(2); Encode2(hp)
}


// CNetObjectMan::OnPacket (0x64EC35): native 24-byte encoded init block.
fun MonsterCreate(objectId: Int, playerSlot: Int, type: Int, parameters: ByteArray) = PKT {
    require(parameters.size == 24)
    Encode2(objectId); Encode1(playerSlot); Encode2(type)
    buf.writeBytes(parameters)
}

// AttackMon event 1 (0x58523D) sets native state 32 (trapped), HP zero.
// Unlike IceMon event 1, this branch does not consume coordinates.
fun MonsterBossLocked(objectId: Int) = PKT {
    Encode2(14); Encode2(objectId); Encode1(1)
}

// BasicMon event 6 (0x586CBC) calls the native speed setter 0x475CCE.
fun MonsterSpeed(objectId: Int, speed: Int) = PKT {
    Encode2(14); Encode2(objectId); Encode1(6); Encode1(speed)
}

// EN268 / 548817 invokes the native map laser effect, retaining script dimensions/timing.
fun MonsterLaser(row: Int, column: Int, kind: Int, direction: Int, color: Int, width: Int, length: Int, duration: Int) = PKT {
    Encode1(9); Encode1(row); Encode1(column); Encode1(kind); Encode1(direction)
    Encode1(color); Encode1(width); Encode1(length); Encode4(duration)
}

