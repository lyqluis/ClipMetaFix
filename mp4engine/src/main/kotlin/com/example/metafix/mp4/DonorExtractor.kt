package com.example.metafix.mp4

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** 从 A（原片）提取的元数据。日期一律为“原样字节”，不做任何时间换算 */
class DonorMeta internal constructor(
    val mvhdVersion: Int,
    val mvhdDates: ByteArray,               // creation+modification 原始字节（v0=8B, v1=16B）
    val tracks: List<TrackDates>,
    val udtaChildren: List<ByteArray>,      // 除 mcvr 外的 udta 子 box 完整字节
    val metaBox: ByteArray?,                // A 的整个 meta box（含 header），无则 null
) {
    class TrackDates internal constructor(
        val tkhdVersion: Int, val tkhdDates: ByteArray,
        val mdhdVersion: Int, val mdhdDates: ByteArray,
    )
}

object DonorExtractor {

    fun extract(a: Path): DonorMeta {
        FileChannel.open(a, StandardOpenOption.READ).use { ch ->
            val top = scanTopLevel(ch)
            val moov = top.firstOrNull { it.type == T.Moov }
                ?: throw Mp4Exception("A 不是 MP4 或缺少 moov")
            val buf = readAt(ch, moov.offset, moov.size.toInt())
            val view = BoxView(buf)
            val children = view.children()

            // 1. mvhd 日期
            val mvhd = children.firstOrNull { it.type == T.Mvhd }
                ?: throw Mp4Exception("A 缺少 mvhd")
            val mvhdVer = versionOf(view, mvhd)
            val mvhdDates = view.buf.copyOfRange(
                mvhd.payloadStart + 4, mvhd.payloadStart + 4 + dateLen(mvhdVer)
            )

            // 2. 每个 trak 的 tkhd / mdhd 日期（按 trak 顺序对应）
            val tracks = children.filter { it.type == T.Trak }.map { trak ->
                val tkhd = view.children(trak.payloadStart, trak.end)
                    .firstOrNull { it.type == T.Tkhd }
                    ?: throw Mp4Exception("A 的 trak 缺少 tkhd")
                val tkhdVer = versionOf(view, tkhd)
                val tkhdDates = view.buf.copyOfRange(
                    tkhd.payloadStart + 4, tkhd.payloadStart + 4 + dateLen(tkhdVer)
                )
                val mdia = view.children(trak.payloadStart, trak.end)
                    .firstOrNull { it.type == T.Mdia }
                    ?: throw Mp4Exception("A 的 trak 缺少 mdia")
                val mdhd = view.children(mdia.payloadStart, mdia.end)
                    .firstOrNull { it.type == T.Mdhd }
                    ?: throw Mp4Exception("A 的 mdia 缺少 mdhd")
                val mdhdVer = versionOf(view, mdhd)
                val mdhdDates = view.buf.copyOfRange(
                    mdhd.payloadStart + 4, mdhd.payloadStart + 4 + dateLen(mdhdVer)
                )
                DonorMeta.TrackDates(tkhdVer, tkhdDates, mdhdVer, mdhdDates)
            }

            // 3. udta 子 box：跳过 mcvr，其余原样保留
            val udtaChildren = children.firstOrNull { it.type == T.Udta }
                ?.let { view.children(it.payloadStart, it.end) }
                ?.filter { it.type != T.Mcvr }
                ?.map { view.bytes(it) }
                ?: emptyList()

            // 4. meta 整段（含 4 字节 version+flags 头和 box header）
            val meta = children.firstOrNull { it.type == T.Meta }?.let { view.bytes(it) }

            return DonorMeta(mvhdVer, mvhdDates, tracks, udtaChildren, meta)
        }
    }

    private fun versionOf(view: BoxView, box: Box): Int =
        view.buf[box.payloadStart].toInt() and 0xFF

    private fun dateLen(version: Int): Int = if (version == 1) 16 else 8
}
