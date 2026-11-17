package bnbemu.server

import bnbemu.netty.ClientSocket
import bnbemu.netty.SendPacketBase
import bnbemu.handler.CreatePracticeSessionReq
import bnbemu.handler.room.launchLocalMatch
import bnbemu.handler.room.mapIdTable
import io.netty.buffer.Unpooled
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in, one-shot local reproduction; absent marker means no behavior change. */
object LocalCrashProbe {
    fun onJoined(client: ClientSocket) {
        val marker = File("../port-audit/crash-probe/armed.txt")
        if (!marker.isFile || client.users.isEmpty()) return
        val name = marker.readText().trim()
        val board = mapIdTable.entries.singleOrNull { it.value.name == name } ?: return
        if (name !in LocalMonsterStages.stages) return
        val claim = File(marker.parentFile, "claimed-${System.currentTimeMillis()}.txt")
        if (!marker.renameTo(claim)) return
        client.ch.eventLoop().schedule({
            if (!client.ch.isActive) return@schedule
            val input = Unpooled.buffer()
            try {
                SendPacketBase(input, 3).apply {
                    Encode2(0); Encode2(0); Encode1(1); Encode1(1)
                    Encode1(client.users.first().selectedBomber); Encode1(14); Encode4(0)
                }
                with(CreatePracticeSessionReq) { input.handle(client) }
            } finally { input.release() }
            Room.mapId = board.key; Room.themeId = board.value.themeId
            sendLocalRoomSnapshot(client)
            client.ch.eventLoop().schedule({
                if (client.ch.isActive) {
                    needleStockPackets().forEach { client send it }
                    client send localUserInventory(client.users.first(), practice = true, inGame = true)
                    launchLocalMatch(client, board.key, board.value.themeId, replenish = false)
                    println("[CrashProbe] Started isolated board=$name; no player input required")
                    if (name == "MonPen07") {
                        repeat(180) { tick ->
                            client.ch.eventLoop().schedule({
                                if (client.ch.isActive) Room.advanceCrashProbeBoards()
                            }, 1000L + tick * 500L, TimeUnit.MILLISECONDS)
                        }
                    }
                }
            }, 1000, TimeUnit.MILLISECONDS)
        }, 3000, TimeUnit.MILLISECONDS)
    }
}
