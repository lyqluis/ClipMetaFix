package com.clipmeta.fix.mp4

import java.nio.ByteOrder

/**
 * ©xyz (0xA9 78 79 7A) GPS 解析。
 * 实测小米 payload：4B 语言/标志 (00 12 15 C7) + ASCII "+xx.xxxx+yyy.yyyy/"。
 * 不做坐标语义换算，只做字节级提取 + ASCII 解码，供展示与防呆用。
 */
object XyzLocation {

    /** 从 extract() 的 udtaChildrenFiltered 中提取 GPS 字符串，无则 null。 */
    fun parse(filtered: List<ByteArray>): String? {
        for (raw in filtered) {
            if (raw.size < 8 + 4 + 1) continue
            if (raw[4] != 0xA9.toByte() || raw[5] != 0x78.toByte() ||
                raw[6] != 0x79.toByte() || raw[7] != 0x7A.toByte()
            ) continue
            val headerSize = headerSizeOf(raw) ?: continue
            // payload 前 4B 为语言/标志，跳过
            val textStart = headerSize + 4
            if (textStart >= raw.size) continue
            val textBytes = raw.copyOfRange(textStart, raw.size)
            val s = textBytes.toString(Charsets.US_ASCII)
                .trim { it == '\u0000' || it == ' ' || it == '\n' || it == '\r' || it == '\t' }
                .trimEnd('/')
            if (s.isNotBlank()) return "$s/"
        }
        return null
    }

    /** 是否含 ©xyz（用于修复前断言，防止静默产出无 GPS 文件）。 */
    fun hasLocation(filtered: List<ByteArray>): Boolean = parse(filtered) != null

    fun hasLocation(extracted: Mp4Parser.Extracted): Boolean =
        hasLocation(extracted.udtaChildrenFiltered)

    private fun headerSizeOf(raw: ByteArray): Int? {
        if (raw.size < 8) return null
        val size = ((raw[0].toInt() and 0xFF) shl 24) or
            ((raw[1].toInt() and 0xFF) shl 16) or
            ((raw[2].toInt() and 0xFF) shl 8) or
            (raw[3].toInt() and 0xFF)
        val sizeL = size.toLong() and 0xFFFFFFFFL
        return when (sizeL) {
            1L -> {
                if (raw.size < 16) null
                else {
                    // largesize 校验（可选，不强制）
                    val bb = java.nio.ByteBuffer.wrap(raw, 8, 8).order(ByteOrder.BIG_ENDIAN)
                    val large = bb.long
                    if (large < 16 || large > raw.size) null else 16
                }
            }
            0L -> 8 // size==0 延伸到尾，header 仍是 8
            else -> if (sizeL < 8 || sizeL > raw.size) null else 8
        }
    }
}
