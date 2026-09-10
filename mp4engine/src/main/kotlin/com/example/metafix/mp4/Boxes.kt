package com.example.metafix.mp4

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path

class Mp4Exception(msg: String) : Exception(msg)

/** box 类型：Int 的低 32 位即 4 字节 ASCII（© = 0xA9） */
internal object T {
    val Ftyp = typeOf("ftyp")
    val Moov = typeOf("moov")
    val Mvhd = typeOf("mvhd")
    val Trak = typeOf("trak")
    val Tkhd = typeOf("tkhd")
    val Mdia = typeOf("mdia")
    val Mdhd = typeOf("mdhd")
    val Udta = typeOf("udta")
    val Meta = typeOf("meta")
    val Mcvr = typeOf("mcvr")
    val Xyz  = byte4(0xA9, 'x'.code, 'y'.code, 'z'.code) // ©xyz

    private fun typeOf(s: String): Int = byte4(s[0].code, s[1].code, s[2].code, s[3].code)
    private fun byte4(a: Int, b: Int, c: Int, d: Int): Int = (a shl 24) or (b shl 16) or (c shl 8) or d
}

/** 内存中的一个 box（buf 为整个 moov 的字节，start 是 header 起点） */
internal class Box(
    val type: Int,
    val start: Int,
    val headerSize: Int, // 8 或 16
    val size: Long,      // 含 header
) {
    val payloadStart: Int get() = start + headerSize
    val end: Int get() = (start + size).toInt()
}

/** 对一段字节做 box 视图 */
internal class BoxView(val buf: ByteArray, val start: Int = 0, val end: Int = buf.size) {

    fun children(from: Int = start, to: Int = end): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            val h = ByteBuffer.wrap(buf, p, 8).order(ByteOrder.BIG_ENDIAN)
            var size = h.int.toLong() and 0xFFFFFFFFL
            val type = h.int
            var header = 8
            if (size == 1L) {
                size = ByteBuffer.wrap(buf, p + 8, 8).order(ByteOrder.BIG_ENDIAN).long
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong() // 延伸到容器末尾
            }
            if (size < header || p + size > to)
                throw Mp4Exception("非法 box @${p} type=${type.toString(16)} size=$size")
            out.add(Box(type, p, header, size))
            p += size.toInt()
        }
        if (p != to) throw Mp4Exception("box 未对齐：剩余 ${to - p} 字节")
        return out
    }

    fun bytes(box: Box): ByteArray = buf.copyOfRange(box.start, box.end)
}

/** 文件顶层 box（mdat 很大，只记偏移，不读内容） */
internal data class TopBox(val type: Int, val offset: Long, val headerSize: Int, val size: Long) {
    val end: Long get() = offset + size
}

internal fun scanTopLevel(ch: FileChannel): List<TopBox> {
    val out = ArrayList<TopBox>()
    val fileSize = ch.size()
    var pos = 0L
    while (pos + 8 <= fileSize) {
        val h = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        readFully(ch, h, pos)
        var size = h.int.toLong() and 0xFFFFFFFFL
        val type = h.int
        var header = 8
        if (size == 1L) {
            val lb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            readFully(ch, lb, pos + 8)
            size = lb.long
            header = 16
        } else if (size == 0L) {
            size = fileSize - pos
        }
        if (size < header || pos + size > fileSize) {
            val avail = minOf(16L, fileSize - pos).toInt()
            val hex = readAt(ch, pos, avail).joinToString(" ") { "%02x".format(it) }
            throw Mp4Exception(
                "顶层 box 非法 @${pos} sizeField=$size type=${type.toString(16)} raw=$hex"
            )
        }
        out.add(TopBox(type, pos, header, size))
        pos += size
    }
    return out
}

/** 从 pos 读满整个 buf（limit 为准）。读不到就报错，绝不空转 */
internal fun readFully(ch: FileChannel, buf: ByteBuffer, pos: Long) {
    var p = pos
    var idle = 0
    while (buf.hasRemaining()) {
        val n = ch.read(buf, p)
        if (n < 0) throw Mp4Exception("文件被截断")
        if (n == 0 && ++idle > 1000) throw Mp4Exception("读取无进展 @${p}")
        if (n > 0) idle = 0
        p += n
    }
    buf.flip()
}

internal fun readAt(ch: FileChannel, pos: Long, len: Int): ByteArray {
    val buf = ByteBuffer.allocate(len)
    readFully(ch, buf, pos)
    return buf.array()
}

/** 通道拷贝 [from, to)。进度用显式计数器 got，不依赖 ByteBuffer 的 position/limit 状态 */
internal fun copyRange(src: FileChannel, from: Long, to: Long, dst: FileChannel) {
    var p = from
    val buf = ByteBuffer.allocate(1024 * 1024)
    while (p < to) {
        val want = minOf(buf.capacity().toLong(), to - p).toInt()
        buf.clear()
        buf.limit(want)
        var got = 0
        var idle = 0
        while (got < want) {
            val n = src.read(buf, p + got)
            if (n < 0) throw Mp4Exception("文件被截断")
            if (n == 0 && ++idle > 1000) throw Mp4Exception("读取无进展 @${p + got}")
            if (n > 0) { idle = 0; got += n }
        }
        buf.flip()
        while (buf.hasRemaining()) {
            if (dst.write(buf) == 0) throw Mp4Exception("写入无进展")
        }
        p += got
    }
}

/** 用 4 字节 size 打包一个 box */
internal fun wrapBox(type: Int, payload: ByteArray): ByteArray {
    val total = payload.size.toLong() + 8
    val head = ByteBuffer.allocate(if (total <= 0xFFFFFFFFL) 8 else 16)
        .order(ByteOrder.BIG_ENDIAN)
    if (total <= 0xFFFFFFFFL) {
        head.putInt(total.toInt())
    } else {
        head.putInt(1).putLong(total + 8)
    }
    head.putInt(type)
    return head.array() + payload
}
