package bnbemu.packet

import bnbemu.netty.PKT

// ============================================================================
// S→C op 84 — 快速加入房間結果 (JoinFreeSessionResult)
// ============================================================================
// C→S op 77 (JoinFreeSessionReq, 大廳「快速加入 / 激战模式」入口) 的回應。
//
// 客户端处理链 (上游 reference/CA.exe.c, EN v18):
//   CLobbyStage::OnPacket 0x00510287 switch case 'T' (0x54 = 84) → sub_507CC7:
//     · vtable[272](0) 清掉 op 77 发包点 (sub_50D4DC) 设置的等待锁
//     · switch (Decode1 result):
//         case 0  成功 → E2(房号) + E4(序号) + E1(房型) + E1(状态)
//                 写进 CFxContext (sub_68739E / sub_68626F / sub_687393),
//                 sub_505123 按房号到 op 87 房间缓存找房 → 切进房间舞台
//         case 2..12 失败 → 只弹对话框, 不再读任何字段 (失败包 1 字节即可)
//     ⚠️ 没有 case 1; 与 op 83 EnterSessionResult 的差别: 83 是「进指定房」
//     (case 1 = WrongPassword), 84 是「自动找房」(case 2 = CantFindEmptyRoom);
//     成功分支字段也不同 (83: E1+E1+E2+E1+E1; 84: E1+E2+E4+E1+E1)。
//
// 错误码 → 客户端弹窗: 2 CantFindEmptyRoom / 3 InvalidGrade / 4 InvalidLucci /
//   6 DontHaveTMItemJoin / 7 UserRejectJoinSession / 8 InvalidLevel / …
//
// 离线单机: 快速加入没有别的玩家可匹配 —— 直接当「创建 AI 激战房」用:
// 回 result=0 + 本地房号 75, 客户端会走成功分支切进房间舞台,
// 服务端紧接着下发给 op 75 同款的整套房间初始化链 (见 handler/JoinFreeSessionReq.kt)。
fun JoinFreeSessionResult(
    result: Int = 0,
    session: Int = 75,
    seq: Int = 0,
    sessionType: Int = 16,
    state: Int = 0,
) = PKT {
    Encode1(result)
    if (result == 0) {
        Encode2(session)
        Encode4(seq)
        Encode1(sessionType)
        Encode1(state)
    }
}
