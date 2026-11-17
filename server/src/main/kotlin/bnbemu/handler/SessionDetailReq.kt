package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.JoinFreeSessionResult
import bnbemu.packet.RoomEntry
import bnbemu.packet.RoomList
import bnbemu.server.LocalRoomRequest
import bnbemu.server.Room
import bnbemu.server.startLocalRoom

// ============================================================================
// C→S op 84 — 進房註冊檢查的「等待應答」(客戶端 30-tick 檢查失敗時發出)
// ============================================================================
// 實測線序 (2026-11-17 整合包實錄): 客戶端進房 30 tick 後 (CA.exe 0x521B89) 若註冊檢查
// 未通過, 自動發 C→S op 84 並把等待鎖 [stage+0x2F78] 置 4 —— 選角/換圖/開始/商城
// 按鈕全部變灰。body 5B = E1(子碼 0) + E4(附加 0) (抓包 5A 96 CA 53 95 兩欄解密後均 0)。
//
// 上游 BnbEmu 與本 fork 的 recv 表都沒有這個 opcode ([UNKNOWN] 84), 鎖永遠無人解開 ——
// 這就是「進房後按鈕全灰」在所有版本上都重現的原因。
//
// 應答: S→C op 84 JoinFreeSessionResult (packet/JoinFreeSessionResult.kt)。客戶端
// CLobbyStage::OnPacket case 'T' (0x54) → sub_507CC7:
//   1. 第一件事就是清等待鎖 (等待鎖正是進房後發 op 84 時置 4 的同一把);
//   2. result=0 成功分支讀 E2(房號) E4(序號) E1(房型) E1(狀態) 寫進 CFxContext,
//      再按房號到 op 87 房間快取找房 (0x505123) 恢復房間舞台。
// 房號必須與 op 83 / op 87 條目一致 (=75); 房型 = Room.localGameMode。
//
// 備忘: 本 handler 與 JoinFreeSessionReq (C→S op 77) 的應答是同一個 S→C 包 ——
// 84 是「快速加入」與「進房註冊檢查」共用的等待應答, 兩個請求端各取所需。
val SessionDetailReq = PKTHandler { c ->
    val subCode = Decode1
    val extra = Decode4
    println("[SessionDetailReq] C→S op 84 subCode=$subCode extra=$extra -> 應答 S→C 84 + 重發進房鏈 (補新舞台房間數據)")
    // 1) 房間列表條目先行 (與 op 83 進房同構: RoomList → 結果包 → 整鏈),
    //    保證 sub_507CC7 成功分支的 0x505123 房號查找命中。
    c.send(RoomList(listOf(RoomEntry.practice(number = 75))))
    // 2) 應答本身: 清等待鎖 + 攜帶房號/房型 (原樣回顯當前房間模式)。
    c send JoinFreeSessionResult(result = 0, session = 75, seq = 0, sessionType = Room.localGameMode, state = 0)
    // 3) ⚠️ 重發整套房間鏈。客戶端 op 84 成功分支會重切房間舞台 (sub_507CC7),
    //    新舞台的房間數據是空的 —— 官方服務器此時必須重發整鏈 (上游 RoomEntryChain
    //    的 backToRoom 註釋同源: 「不補這條鏈, 房主位/座位/房名/選圖都是降級狀態」)。
    //    不重發的實測後果 (2026-11-17): 電腦假人全部消失、選圖按鈕灰、
    //    點角色/開始無任何包發出 —— 舞台空轉。
    if (Room.slots.any { it.user != null || it.isAI }) {
        val player = Room.slots[0].user
        val request = LocalRoomRequest(
            kind = 1,
            characters = listOf(player?.selectedBomber?.toInt() ?: 6),
            option = Room.localGameMode,
            extra = 0,
        )
        startLocalRoom(c, request, sendSessionInfo = true)
    }
}
