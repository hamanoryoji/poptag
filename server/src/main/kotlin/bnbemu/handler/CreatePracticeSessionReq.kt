package bnbemu.handler

import bnbemu.netty.PKTHandler
import bnbemu.server.decodeLocalRoomRequest
import bnbemu.server.startLocalRoom

// ============================================================================
// 进练习房 (C→S op 75) — 在练习房界面点"开始"时发出
// ============================================================================
// EN op75 以两个长度前缀字符串（房名、密码）开头；历史训练样本两者为空，
// 所以前四字节曾被误认作固定整数。后接 kind、playerCount、角色列表、option、extra。
// 历史 kind=0 样本保持训练 mode15；当前命名 NPC 房 kind=1 使用 mode16。
// 完整解析与边界检查见 server/LocalBattleMode.kt。
// 服务器连发整套房间设置回应 (seq 顺序, 见 server/LocalRoomEntry.kt):
//   [87 RoomList, 83 SessionSimpleInfo, 4(13B), 4(65B), 113, 100(78B), 108,
//    106×8, 每槽(114,104,105,103), 198, 423]
// 之后客户端自动跟发 297/302, 点"开始"发 op 92 触发游戏开始 (见 StartGameReq.kt)。
// ⚠️ 8 槽必须全部初始化 (官方 tst.pcap: 1 真人 + 7 电脑), 否则客户端 30 tick 后
// 自动发 op 84 锁 UI (选角/换图/开始/商城全灰) —— 详见 LocalRoomEntry.kt 注释。
// server = C???::OnCreatePracticeSession (原始名称未知, 绑定见 EN_18_recv.yaml)
val CreatePracticeSessionReq = PKTHandler { c ->
    val request = decodeLocalRoomRequest() ?: return@PKTHandler
    startLocalRoom(c, request, sendSessionInfo = true)
}
