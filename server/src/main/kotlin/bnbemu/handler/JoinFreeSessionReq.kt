package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.packet.JoinFreeSessionResult
import bnbemu.packet.RoomEntry
import bnbemu.packet.RoomList
import bnbemu.server.LocalRoomRequest
import bnbemu.server.startLocalRoom

// ============================================================================
// C→S op 77 — 快速加入房間請求 (JoinFreeSessionReq) — 激战模式入口
// ============================================================================
// 大廳「快速加入 / 自动配对」與「激战模式」按鈕。旧 fork 的 recv 表没有这个
// opcode ([UNKNOWN] 77) → 客户端点了完全没反应, 这就是「激战模式完全开不了」。
//
// 請求結構 (CA.exe sub_50D4DC, 發包點 0x50EDD9 / 0x5144F9 / 0x5EDAD7 / 0x6B14EA):
//   E1(本地玩家数) + N×E1(每個本地玩家的角色 id) + E2(a2) + E2(a3)
// 实包 body: 5B 5C A5 0D A5 69 = E1(1) E1(6) E2(100) E2(0)
//   (末两栏 a2/a3 与等级/地图/模式筛选有关, 必須消費否则解析错位; 单机不用)
//
// 回應: S→C op 84 JoinFreeSessionResult —— 单机没有别的玩家可匹配,
// 直接创建一间本地 AI 激战房 (mode 16, 1 真人 + 7 电脑), 回成功分支:
//   E1(0) + E2(房号 75) + E4(0) + E1(房型 16) + E1(0)
// 客户端 0x507CC7 成功分支会用房号到 op 87 房间缓存找房后切房间舞台,
// 所以 RoomList(条目 75) 必须先于应答下发, 然后接整套房间初始化链。
val JoinFreeSessionReq = PKTHandler { c ->
    val playerCount = Decode1.coerceIn(0, 8)
    val bombers = List(playerCount) { Decode1 }
    val filter1 = Decode2
    val filter2 = Decode2
    println("[JoinFreeSessionReq] players=$playerCount bombers=$bombers filter=($filter1,$filter2) -> 创建本地激战房 (mode16)")

    val request = LocalRoomRequest(
        kind = 1,
        characters = bombers.ifEmpty { listOf(6) },
        option = 16,
        extra = 0,
    )
    // op 84 成功分支 (0x507CC7) 用房号 75 到 op 87 房间缓存找房后才切舞台:
    // 房间列表条目必须先就位 (与 op 84 的 E2(75) 一致)。
    c.send(RoomList(listOf(RoomEntry.practice(number = 75))))
    c send JoinFreeSessionResult(result = 0, session = 75, sessionType = 16)
    // op 84 已携带房号/房型/状态 (op 83 的等价物), 故不再重发 SessionSimpleInfo。
    startLocalRoom(c, request, sendSessionInfo = false)
}
