package com.example.metafix.mp4

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object MetaFixer {

    fun fix(donor: DonorMeta, b: Path, out: Path) {
        FileChannel.open(b, StandardOpenOption.READ).use { ch ->
            val top = scanTopLevel(ch)
            val moov = top.firstOrNull { it.type == T.Moov }
                ?: throw Mp4Exception("B 中没有 moov")
            val moovBuf = readAt(ch, moov.offset, moov.size.toInt())
            val newMoov = rebuildMoov(moovBuf, donor)

            FileChannel.open(
                out,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { dst ->
                // 顺序严格为：moov 之前的字节 + 新 moov + moov 之后的字节
                copyRange(ch, 0L, moov.offset, dst)
                writeAll(dst, newMoov)
                copyRange(ch, moov.end, ch.size(), dst)
            }
        }
    }

    private fun rebuildMoov(moovBuf: ByteArray, donor: DonorMeta): ByteArray {
        val view = BoxView(moovBuf)
        val moovBox = view.children().single()
        val src = view.children(moovBox.payloadStart, moovBox.end)
        if (src.count { it.type == T.Trak } != donor.tracks.size)
            throw Mp4Exception("A/B 轨道数不一致，无法按顺序对应")

        val out = ByteArrayOutputStream()
        var trakIdx = 0
        var wroteUdta = false
        for (box in src) {
            when (box.type) {
                T.Mvhd -> out.write(patchDates(view, box, donor.mvhdVersion, donor.mvhdDates, "mvhd"))
                T.Trak -> out.write(patchTrak(view, box, donor.tracks[trakIdx++]))
                T.Meta -> out.write(donor.metaBox ?: view.bytes(box))
                T.Udta -> { out.write(buildUdta(donor)); wroteUdta = true }
                else -> out.write(view.bytes(box))
            }
        }
        if (!wroteUdta && donor.udtaChildren.isNotEmpty()) out.write(buildUdta(donor))
        return wrapBox(T.Moov, out.toByteArray())
    }

    private fun patchTrak(view: BoxView, trak: Box, dates: DonorMeta.TrackDates): ByteArray {
        val out = ByteArrayOutputStream()
        for (child in view.children(trak.payloadStart, trak.end)) {
            when (child.type) {
                T.Tkhd -> out.write(patchDates(view, child, dates.tkhdVersion, dates.tkhdDates, "tkhd"))
                T.Mdia -> {
                    val mo = ByteArrayOutputStream()
                    for (c in view.children(child.payloadStart, child.end)) {
                        if (c.type == T.Mdhd)
                            mo.write(patchDates(view, c, dates.mdhdVersion, dates.mdhdDates, "mdhd"))
                        else
                            mo.write(view.bytes(c))
                    }
                    out.write(wrapBox(T.Mdia, mo.toByteArray()))
                }
                else -> out.write(view.bytes(child))
            }
        }
        return wrapBox(T.Trak, out.toByteArray())
    }

    private fun patchDates(
        view: BoxView, box: Box,
        donorVersion: Int, donorDates: ByteArray, name: String
    ): ByteArray {
        val version = view.buf[box.payloadStart].toInt() and 0xFF
        if (version != donorVersion)
            throw Mp4Exception("$name 版本不一致：A=v$donorVersion B=v$version")
        val bytes = view.bytes(box)
        donorDates.copyInto(bytes, destinationOffset = box.headerSize + 4)
        return bytes
    }

    private fun buildUdta(donor: DonorMeta): ByteArray {
        val payload = donor.udtaChildren.fold(ByteArray(0)) { acc, c -> acc + c }
        return wrapBox(T.Udta, payload)
    }

    private fun writeAll(dst: FileChannel, data: ByteArray) {
        val buf = ByteBuffer.wrap(data)
        var idle = 0
        while (buf.hasRemaining()) {
            val n = dst.write(buf)
            if (n == 0 && ++idle > 1000) throw Mp4Exception("写入无进展")
            if (n > 0) idle = 0
        }
    }
}
