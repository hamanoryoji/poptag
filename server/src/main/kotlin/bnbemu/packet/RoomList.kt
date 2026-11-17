package bnbemu.packet

import bnbemu.ACP
import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase

// ============================================================================
// 房間列表 — op 87 (2026-11-17 依 CA.exe 客戶端解析器完整重寫)
// ============================================================================
// 舊實現 (E4×4 + 名長 + 名字 + E4×3 + E1) 是對真實抓包的誤讀: 客戶端
// CLobbyStage::OnPacket 0x510287 case 87 → 0x50FF27 的真實解析為:
//
//   E2(房間數)
//   每房間:
//     E4(blobLen)                  ← 隨連線加密度解碼 (3度加密值)
//     blob (blobLen 位元組, 整體 3 度加密 0x96CA5395/0xA569/0x5A):
//       0x18                       ← 標記 (0x78C120 檢查, 非 0x18 整包丟棄)
//       E4(payloadLen)             ← blobLen = payloadLen + 5
//       0x00                       ← ssd 類型位元組 (0=原始流; 1=[E4長][數據])
//       stream:
//         W    (未知; rd 抓包 = 0x003D, 客戶端讀後丟棄)
//         E4   (房間編號! 低 16 位 = 房間快取陣列下標, 0x505123:
//               快取基址 [lobby+0x6968], 每條 0xCC, 上限 [lobby+0x6964])
//         Str  (房名: E4(長) + 位元組; 0x78CE60)
//         B    → cache+0x48
//         B    → cache+0x54
//         B    → cache+0x6C
//         W    → cache+0x78
//         B    → cache+0x84
//         B    → cache+0x90
//         B    → cache+0x9C
//         B    → cache+0x20 容器
//         B    → cache+0x60 容器
//         E4   → cache+0xC0 容器 (會話索引; op 83 尾部 0x52F513 用它)
//         B    → cache+0xA8
//         (閘門 0x6C054E(0x43) 成立再多讀 1B → cache+0xB4; 我們在尾部
//          恆多補 1 個 0x00 以兼容兩種情況)
//
// 驗證: enter-channel.pcap 的 "rd" 房 41B 實例按新格式逐位元組復算吻合:
//   blobLen=33 (=舊誤讀 f1), blob 首字節 0x18 (=舊誤讀 f2=0x1C18 首字節),
//   payloadLen=28, 房號=119 (=舊 f4), 房名 "rd", 會話索引=1040。
// 舊格式下客戶端把 f1 當 blobLen、f2 首字節當 0x18 標記失敗 → 快取永遠為空 →
// op 83 的房號查找「找不到整段跳過」→ 玩家註冊斷鏈 → 進房 30 tick 後客戶端
// 自動發 C→S op 84 請求房間詳情並鎖 UI (選角/換圖/開始/商城全灰) 的根因。
//
// op 83 (SessionSimpleInfo) 內硬編碼房號 75, 因此練習房條目 number 必須 = 75。
// 會話索引必須 < 客戶端會話列表數 ([0x993CD8], 由 op 72 填充; 我們單用戶 = 1
// 條, 故傳 0)。
class RoomEntry(
    val name: String,
    val number: Int,            // 房間編號: stream 首個 W (Decode2), 0x505123 查找鍵; 須與 op 83 一致
    val handle: Int = 0,        // 會話句柄: 其後的 E4 (cache+0x44; rd 抓包 = 119)
    val sessionIdx: Int = 0,    // 會話索引 (cache+0xC0; 須 < 客戶端 op 72 會話列表大小)
    val b48: Int = 0x00,
    val b54: Int = 0x90,
    val b6C: Int = 0x00,
    val w78: Int = 0x0000,
    val b84: Int = 0x00,
    val b90: Int = 0x08,        // rd 抓包 = 8
    val b9C: Int = 0x07,        // rd 抓包 = 7
    val b20: Int = 0x08,        // rd 抓包 = 8
    val b60: Int = 0x10,        // rd 抓包 = 16
    val bA8: Int = 0x00,
) {
    fun Encode(p: SendPacketBase) {
        val stream = java.io.ByteArrayOutputStream()
        fun w(v: Int) { stream.write(v and 0xFF); stream.write((v shr 8) and 0xFF) }
        fun e4(v: Int) {
            repeat(4) { stream.write((v ushr (8 * it)) and 0xFF) }
        }
        // ⚠️ 2026-11-17 反汇编钉死 (CA.exe 0x50FF27 → 0x78BE30/0x78BE60 → 0x504E9B):
        // stream 首个 W = 房号 (0x505123 查找键), 其后 E4 = 会话句柄 —— 旧 AI 版把
        // rd 房号 61 (0x3D) 当「未知字段」、把句柄 119 当房号 → 客户端把我们的条目
        // 注册成 61 号房, op 83 找 75 号房永远失败 → 注册断链 → 全部降级。
        // 另外: 官方条目没有所谓「0x00 ssd 类型字节」—— blob[5] 起就是 stream,
        // 旧版多插的 0x00 使 blobLen=34 ≠ 官方 33/31。修正后练习房条目 = 31B,
        // rd 复算 = 33B, 与官方两笔实测完全一致。
        w(number)
        e4(handle)
        val nameBytes = name.toByteArray(ACP)
        e4(nameBytes.size)
        stream.write(nameBytes)
        for (v in listOf(b48, b54, b6C)) stream.write(v and 0xFF)
        w(w78)
        for (v in listOf(b84, b90, b9C, b20, b60)) stream.write(v and 0xFF)
        e4(sessionIdx)
        stream.write(bA8 and 0xFF)
        stream.write(0x00)   // 尾部補位: 閘門 0x6C054E(0x43) 成立時的 cache+0xB4 讀取

        val payload = stream.toByteArray()
        val blob = java.io.ByteArrayOutputStream()
        blob.write(0x18)
        val pl = payload.size
        blob.write(pl and 0xFF); blob.write((pl shr 8) and 0xFF)
        blob.write(0); blob.write(0)
        blob.write(payload)

        p.Encode4(blob.size())
        p.EncodeBuffer(cipher3(blob.toByteArray()))
    }

    companion object {
        // 3 度加密內容變換 (與 EncodeCipherStr 同款, 但不含 E2 長度前綴):
        // 4B 組 LE 讀 ^ 0x96CA5395 按 BE 寫回; 餘 2B ^ 0xA569; 餘 1B ^ 0x5A
        fun cipher3(raw: ByteArray): ByteArray {
            val out = ByteArray(raw.size)
            var i = 0
            while (i + 4 <= raw.size) {
                val v = (raw[i].toInt() and 0xFF) or
                        ((raw[i + 1].toInt() and 0xFF) shl 8) or
                        ((raw[i + 2].toInt() and 0xFF) shl 16) or
                        ((raw[i + 3].toInt() and 0xFF) shl 24)
                val e = v xor 0x96CA5395.toInt()
                out[i] = (e ushr 24).toByte()
                out[i + 1] = (e ushr 16).toByte()
                out[i + 2] = (e ushr 8).toByte()
                out[i + 3] = e.toByte()
                i += 4
            }
            if (i + 2 <= raw.size) {
                val v = (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)
                val e = v xor 0xA569
                out[i] = ((e shr 8) and 0xFF).toByte()
                out[i + 1] = (e and 0xFF).toByte()
                i += 2
            }
            if (i < raw.size) out[i] = (raw[i].toInt() xor 0x5A).toByte()
            return out
        }

        // cipher3 的逆變換 (測試回讀/除錯用): 4B 組 BE 讀 ^ 0x96CA5395 按 LE 寫回
        fun decipher3(raw: ByteArray): ByteArray {
            val out = ByteArray(raw.size)
            var i = 0
            while (i + 4 <= raw.size) {
                val v = ((raw[i].toInt() and 0xFF) shl 24) or
                        ((raw[i + 1].toInt() and 0xFF) shl 16) or
                        ((raw[i + 2].toInt() and 0xFF) shl 8) or
                        (raw[i + 3].toInt() and 0xFF)
                val e = v xor 0x96CA5395.toInt()
                repeat(4) { out[i + it] = ((e ushr (8 * it)) and 0xFF).toByte() }
                i += 4
            }
            if (i + 2 <= raw.size) {
                val v = ((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)
                val e = v xor 0xA569
                out[i] = (e and 0xFF).toByte()
                out[i + 1] = ((e shr 8) and 0xFF).toByte()
                i += 2
            }
            if (i < raw.size) out[i] = (raw[i].toInt() xor 0x5A).toByte()
            return out
        }

        // 練習房條目: 房號與 op 83 (SessionSimpleInfo) 內的 E2(75) 一致
        fun practice(number: Int = 75, name: String = "", sessionIdx: Int = 0) =
            RoomEntry(name = name, number = number, sessionIdx = sessionIdx)
    }
}

fun RoomList(rooms: List<RoomEntry> = emptyList()) = PKT {
    Encode2(rooms.size)         // 0 = 清空本地房間列表
    rooms.forEach { it.Encode(this) }
}
