package bnbemu.netty

import bnbemu.packet.RoomEntry
import bnbemu.packet.RoomList
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * S→C op 87 (RoomList) 真實線格式回歸 — 2026-11-17 按客戶端反汇编修正。
 *
 * 客戶端解析鏈 (CA.exe, 全部親自反汇编核對):
 *   0x50FF27:  E2(數) → 每房: 0x78F1E0 讀 E4(blobLen)+blob 並 3 度解密
 *   0x78E2F0:  解密 blob 後 0x78C120 檢查 blob[0]==0x18, 讀 E4(payloadLen),
 *              初始化 reader 於 blob[5..5+payloadLen], 並強制 blobLen == payloadLen+5
 *   0x50FF27:  0x78BE30 (Decode2) → **房號** (0x505123 的查找鍵!),
 *              0x78BE60 (Decode4) → 會話句柄 (cache+0x44)
 *   0x504E9B:  Str(房名 0x78CE60) → B(+0x48) B(+0x54) B(+0x6C) W(+0x78)
 *              B(+0x84) B(+0x90) B(+0x9C) B(+0x20) B(+0x60) E4(+0xC0 會話索引)
 *              B(+0xA8) → [閘門 0x6C054E(0x43) 成立再讀 B(+0xB4)]
 *
 * ⚠️ 舊 AI 版把 rd 房號 61 (0x3D) 當「未知欄位」、把句柄 119 當房號, 還發明了
 * 「0x00 ssd 類型字節」→ 我們的條目被客戶端註冊成 61 號房, op 83 找 75 號房
 * 永遠失敗 → 註冊斷鏈 → 選角/選圖/開始全部降級 (2026-11-17 整合包實錄)。
 *
 * 對帳基準 (上游筆記記錄的官方 blobLen): rd 條目 33B、tst 練習房條目 31B ——
 * 修正後的編碼兩筆都逐字節吻合。
 */
class RoomListEntryTest {

    private fun le32(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun decodeEntry(body: io.netty.buffer.ByteBuf): ByteArray {
        assertEquals(1, body.Decode2)                    // 房間數 = 1
        val blobLen = body.Decode4
        val wire = ByteArray(blobLen)
        body.readBytes(wire)
        assertEquals(0, body.readableBytes())
        return RoomEntry.Companion.decipher3(wire)
    }

    @Test
    fun testPracticeEntryWireFormatMatchesClientParser() {
        val body = Unpooled.buffer()
        try {
            RoomList(listOf(RoomEntry.practice(number = 75))).encode(SendPacketBase(body, 3))
            val plain = decodeEntry(body)

            // 0x78C120: blob[0] 必須是 0x18 標記, E4(payloadLen), blobLen == payloadLen + 5
            assertEquals(0x18, plain[0].toInt() and 0xFF)
            val payloadLen = le32(plain, 1)
            assertEquals(26, payloadLen)                     // 練習房 (空房名) 官方 = 31-5
            assertEquals(31, plain.size)                     // 官方 tst 練習房條目 = 31B
            assertEquals(payloadLen + 5, plain.size)

            // stream = plain[5..]; 首個 W = 房號 (0x505123 查找鍵)
            var o = 5
            assertEquals(75, le16(plain, o)); o += 2
            assertEquals(0, le32(plain, o)); o += 4          // 會話句柄
            val nameLen = le32(plain, o); o += 4             // Str 房名
            assertEquals("", String(plain, o, nameLen, Charsets.US_ASCII)); o += nameLen
            o += 3 + 2 + 3                                   // B×3, W, B×3
            o += 2                                           // B(+0x20), B(+0x60)
            assertEquals(0, le32(plain, o)); o += 4          // E4 會話索引 (+0xC0)
            assertEquals(0x00, plain[o].toInt() and 0xFF); o += 1   // B(+0xA8)
            assertEquals(0x00, plain[o].toInt() and 0xFF); o += 1   // B(+0xB4) 閘門成立
            assertEquals(plain.size, o, "流必須恰好消費完整 payload")
        } finally {
            body.release()
        }
    }

    @Test
    fun testRdCaptureReinterpretation() {
        // enter-channel.pcap "rd" 房: 官方 blobLen = 33。房號 = 61 (0x3D, 與上游筆記
        // 「rd = 61」一致), 會話句柄 = 119, 會話索引 = 1040。
        val entry = RoomEntry(
            name = "rd", number = 61, handle = 119, sessionIdx = 1040,
            b48 = 0x00, b54 = 0x90, b6C = 0x00, w78 = 0x0000,
            b84 = 0x00, b90 = 0x08, b9C = 0x07, b20 = 0x08, b60 = 0x10, bA8 = 0x00,
        )
        val body = Unpooled.buffer()
        try {
            RoomList(listOf(entry)).encode(SendPacketBase(body, 3))
            val plain = decodeEntry(body)

            assertEquals(33, plain.size, "官方 rd 條目 blobLen = 33")
            assertEquals(28, le32(plain, 1))
            var o = 5
            assertEquals(61, le16(plain, o)); o += 2         // 房號 = 61
            assertEquals(119, le32(plain, o)); o += 4        // 會話句柄 = 119
            val nameLen = le32(plain, o); o += 4
            assertEquals("rd", String(plain, o, nameLen, Charsets.US_ASCII)); o += nameLen
            o += 3 + 2 + 3 + 2
            assertEquals(1040, le32(plain, o)); o += 4
            o += 2
            assertEquals(plain.size, o)
        } finally {
            body.release()
        }
    }
}
