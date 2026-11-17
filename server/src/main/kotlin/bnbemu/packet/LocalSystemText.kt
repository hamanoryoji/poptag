package bnbemu.packet

import bnbemu.ACP
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Local CSysStr overlays. Keys remain original, values use the client's CP_ACP. */
object LocalSystemText {
    val translations: Map<String, String> by lazy {
        // Keep the startup network overlay small; the full native table belongs
        // in ClientData/ClientString, not in the legacy first-connection packet.
        listOf("zh-CN.tsv").flatMap { file ->
        requireNotNull(javaClass.getResourceAsStream("/localization/$file"))
            .bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.removePrefix("\uFEFF") }.filter { '\t' in it }
                    .map { it.substringBefore('\t').lowercase() to it.substringAfter('\t').replace("\\n", "\n") }.toList()
            }
        }.toMap()
    }

    fun localize(original: ByteArray): ByteArray {
        val plain = transform(original, decode = true)
        val input = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        input.position(6)
        val entries = linkedMapOf<String, String>()
        fun readString(): String {
            val length = input.int
            require(length in 0..input.remaining())
            return String(ByteArray(length).also { input.get(it) }, ACP)
        }
        repeat(input.short.toInt() and 65535) {
            val key = readString()
            val value = readString()
            entries[key.lowercase()] = value
        }
        entries.putAll(translations)
        val payload = ByteArrayOutputStream()
        fun number(value: Int, size: Int) { repeat(size) { payload.write(value ushr (it * 8) and 255) } }
        fun string(value: String) { val bytes = value.toByteArray(ACP); number(bytes.size, 4); payload.write(bytes) }
        number(entries.size, 2)
        entries.forEach { (key, value) -> string(key); string(value) }
        val body = payload.toByteArray()
        val length = body.size + 1
        val envelope = byteArrayOf(0x18, length.toByte(), (length ushr 8).toByte(), (length ushr 16).toByte(), 0, 0) + body
        return transform(envelope, decode = false)
    }

    private fun transform(bytes: ByteArray, decode: Boolean): ByteArray {
        val result = bytes.copyOf()
        var offset = 0
        fun block(size: Int, mask: Int) {
            var value = 0
            repeat(size) { i ->
                val index = if (decode) i else size - 1 - i
                value = (value shl 8) or (bytes[offset + index].toInt() and 255)
            }
            value = value xor mask
            repeat(size) { i ->
                val shift = if (decode) i * 8 else (size - 1 - i) * 8
                result[offset + i] = (value ushr shift).toByte()
            }
            offset += size
        }
        while (offset + 4 <= bytes.size) block(4, 0x96ca5395.toInt())
        if (offset + 2 <= bytes.size) block(2, 0xa569)
        if (offset < bytes.size) result[offset] = (bytes[offset].toInt() xor 0x5a).toByte()
        return result
    }
}
