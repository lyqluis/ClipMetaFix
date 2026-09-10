package com.example.metafix.mp4

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object MetaFixer {

    /** donor=A 的元数据；b=剪辑版路径；out=输出路径（临时文件，验证通过前不碰 B） */
    fun fix(donor: DonorMeta, b: Path, out: Path) {
        FileChannel.open(b, StandardOpenOption.READ).use { ch ->
            val top = scanTopLevel(ch)
            val moov = top.firstOrNull { it.type == T.Moov }
                ?: throw Mp4Exception("B 不是 MP4 或缺少 moov")
            val moovBuf = readAt(ch, moov.offset, moov.size.toInt())
            val newMoov = rebuildMoov(moovBuf, donor)

            FileChannel.open(
                out,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
            ).use { dst ->
                copyRange(ch, 0, moov.offset, dst)                 // moov 之前的字节（ftyp + mdat）
                writeAll(dst, newMoov)                             // 打过补丁的新 moov
                copyRange(ch, moov.end, ch.size(), dst)            // moov 之后的字节（通常为空）
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
                // meta 整段替换为 A 的（含 header 原样字节）
                T.Meta -> out.write(donor.metaBox ?: view.bytes(box))
                // B 已有 udta（意料之外）：整体重建为 A 的筛选结果
                T.Udta -> { out.write(buildUdta(donor)); wroteUdta = true }
                else -> out.write(view.bytes(box)) // trak 结构、stbl 等一律不动
            }
        }
        // B 没有 udta：在 moov 末尾新建
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

    /** 只改 creation/modification：box 内 header + 4B version/flags 之后的 2×dateLen 字节 */
    private fun patchDates(
        view: BoxView, box: Box,
        donorVersion: Int, donorDates: ByteArray, name: String
    ): ByteArray {
        val version = view.buf[box.payloadStart].toInt() and 0xFF
        if (version != donorVersion)
            throw Mp4Exception("$name 版本不一致：A=v$donorVersion B=v$version")
        val bytes = view.bytes(box) // 已是副本，可直接改
        donorDates.copyInto(bytes, destinationOffset = box.headerSize + 4)
        return bytes
    }

    private fun buildUdta(donor: DonorMeta): ByteArray {
        val payload = donor.udtaChildren.fold(ByteArray(0)) { acc, c -> acc + c }
        return wrapBox(T.Udta, payload)
    }

    private fun writeAll(dst: FileChannel, data: ByteArray) {
       val buf = ByteBuffer.wrap(data)
        var pos = 0L
        while (buf.hasRemaining()) {
           if (dst.write(buf, pos) == 0) throw Mp4Exception("写入无进展")
           pos = buf.position().toLong()
        }
    }
}
