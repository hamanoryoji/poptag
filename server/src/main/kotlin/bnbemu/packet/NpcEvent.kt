package bnbemu.packet

import bnbemu.netty.PKT

// EN NPC speech/event: 0x52C1E7 reads three E2 values (slot, event, parameter).
fun NpcEvent(slot: Int, event: Int, parameter: Int) = PKT {
    Encode2(slot); Encode2(event); Encode2(parameter)
}

// 0x52B20C: unlock level 0 hides NPC22/23; 1 hides NPC23; 2 enables both.
// Only the verified local selector index 0 is written.
fun NpcAvailability() = PKT { Encode1(0); Encode1(2) }
