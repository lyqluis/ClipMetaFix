package com.clipmeta.fix.mp4

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-level MP4 parser. Handles 32/64-bit sizes, size==0, ©xyz byte compare, meta 4B header.
 * Not loading mdat into memory.
 */
object Mp4Parser {

    data class TopLevelBox(val header: BoxHeader)

    /** Locate all top-level boxes */
    fun parseTopLevel(file: File): List<BoxHeader> {
        RandomAccessFile(file, "r").use { raf ->
            val result = mutableListOf<BoxHeader>()
            var offset = 0L
            val len = raf.length()
            while (offset + 8 <= len) {
                val h = readHeader(raf, offset) ?: break
                result.add(h)
                if (h.size == 0L) break // extends to EOF
                offset = h.end
                // guard against overflow / bad size
                if (h.size < h.headerSize) break
            }
            return result
        }
    }

    fun findMoov(file: File): BoxHeader? = parseTopLevel(file).find { it.typeEquals("moov") }

    fun readBoxBytes(file: File, header: BoxHeader): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(header.start)
            val buf = ByteArray(header.size.toInt())
            raf.readFully(buf)
            return buf
        }
    }

    /** Read moov bytes fully (moov is small) */
    fun readMoovBytes(file: File): ByteArray {
        val moov = findMoov(file) ?: error("moov not found")
        return readBoxBytes(file, moov)
    }

    /** Parse children of a container given its payload bytes and container type. */
    fun parseChildren(payload: ByteArray, payloadOffsetInFile: Long, containerType: String): List<BoxHeader> {
        // For meta, payload starts with 4B version+flags, children after that
        val isMeta = containerType == "meta"
        val startOff = if (isMeta) 4 else 0
        val list = mutableListOf<BoxHeader>()
        var offset = startOff
        while (offset + 8 <= payload.size) {
            val size = readU32(payload, offset)
            val type = payload.copyOfRange(offset + 4, offset + 8)
            var headerSize = 8
            var boxSize = size.toLong() and 0xFFFFFFFFL
            if (boxSize == 1L) {
                if (offset + 16 > payload.size) break
                boxSize = readU64(payload, offset + 8)
                headerSize = 16
            } else if (boxSize == 0L) {
                boxSize = (payload.size - offset).toLong()
            }
            if (boxSize < headerSize) break
            if (offset + boxSize > payload.size) break
            // file offset for this child
            val fileStart = payloadOffsetInFile + offset + (if (isMeta) 0 else 0) // caller handles base
            // But payloadOffsetInFile already points to dataOffset of parent; we need absolute
            list.add(BoxHeader(fileStart, headerSize, boxSize, type))
            offset += boxSize.toInt()
        }
        return list
    }

    /**
     * Struct to locate key boxes inside moov bytes.
     */
    data class MoovLayout(
        val moovHeaderSize: Int,
        val moovSize: Long,
        val mvhdHeader: BoxHeader?,
        val mvhdDataOffsetInMoov: Int, // offset from moov start to mvhd version byte
        val trakLayouts: List<TrakLayout>,
        val udtaHeader: BoxHeader?,
        val metaHeader: BoxHeader?,
        val childHeaders: List<BoxHeader> // all direct children of moov (with file-relative start暂用moov内offset)
    )

    data class TrakLayout(
        val trakHeader: BoxHeader,
        val tkhdHeader: BoxHeader?,
        val mdhdHeader: BoxHeader?,
        val trakOffsetInMoov: Int
    )

    /** Parse moov bytes (including header) into layout with offsets relative to moov start (0). */
    fun parseMoovLayout(moovBytes: ByteArray): MoovLayout {
        val moovHeader = readHeaderFromBytes(moovBytes, 0)
        val moovHeaderSize = moovHeader.headerSize
        val payloadOffset = moovHeaderSize
        val payloadSize = (moovHeader.size - moovHeaderSize).toInt()
        val payload = moovBytes.copyOfRange(payloadOffset, payloadOffset + payloadSize)
        // Parse direct children; their start is relative to moov start
        val children = parseChildrenFromBytes(moovBytes, moovHeaderSize)
        var mvhd: BoxHeader? = null
        var udta: BoxHeader? = null
        var meta: BoxHeader? = null
        val traks = mutableListOf<BoxHeader>()
        for (c in children) {
            when {
                c.type.contentEquals(TYPE_MVHD) -> mvhd = c
                c.type.contentEquals(TYPE_UDTA) -> udta = c
                c.type.contentEquals(TYPE_META) -> meta = c
                c.type.contentEquals(TYPE_TRAK) -> traks.add(c)
            }
        }
        val trakLayouts = traks.map { trakH ->
            val trakPayloadStart = trakH.start.toInt() + trakH.headerSize
            val trakPayloadEnd = (trakH.start + trakH.size).toInt()
            val trakBytes = moovBytes.copyOfRange(trakPayloadStart, trakPayloadEnd)
            // Find tkhd inside trak (direct child)
            val trakChildren = parseChildrenFromBytes(moovBytes, trakH.start.toInt() + trakH.headerSize, trakH.size - trakH.headerSize)
            var tkhd: BoxHeader? = null
            var mdhd: BoxHeader? = null
            for (tc in trakChildren) {
                if (tc.type.contentEquals(TYPE_TKHD)) tkhd = tc
            }
            // mdhd is inside mdia; need deeper
            val mdia = trakChildren.find { it.type.contentEquals(TYPE_MDIA) }
            if (mdia != null) {
                val mdiaPayloadStart = mdia.start.toInt() + mdia.headerSize
                val mdiaChildren = parseChildrenFromBytes(moovBytes, mdiaPayloadStart, mdia.size - mdia.headerSize)
                for (mc in mdiaChildren) {
                    if (mc.type.contentEquals(TYPE_MDHD)) mdhd = mc
                    // mdhd could also be nested? In spec mdhd direct under mdia
                }
                // fallback: search recursively if not found (some files have mdia->mdhd direct)
                if (mdhd == null) {
                    // search inside mdia payload bytes recursively? quick scan
                }
            }
            // also consider tkhd may be inside trak directly, mdhd inside mdia
            TrakLayout(trakH, tkhd, mdhd, trakH.start.toInt())
        }
        // mvhd data offset in moov is mvhd.start + headerSize
        val mvhdDataOffset = mvhd?.let { (it.start + it.headerSize).toInt() } ?: -1
        return MoovLayout(
            moovHeaderSize = moovHeaderSize,
            moovSize = moovHeader.size,
            mvhdHeader = mvhd,
            mvhdDataOffsetInMoov = mvhdDataOffset,
            trakLayouts = trakLayouts,
            udtaHeader = udta,
            metaHeader = meta,
            childHeaders = children
        )
    }

    /** Extracted info from file A */
    data class Extracted(
        val mvhdTimes: ByteArray?, // 8 or 16 bytes (creation+mod)
        val mvhdVersion: Int,
        val trakTimes: List<TrakTimes>,
        val udtaChildrenFiltered: List<ByteArray>, // each is full box bytes (header+payload) excluding mcvr
        val metaRaw: ByteArray? // full meta box bytes including header
    )

    data class TrakTimes(
        val tkhdTimes: ByteArray?,
        val tkhdVersion: Int,
        val mdhdTimes: ByteArray?,
        val mdhdVersion: Int
    )

    fun extract(file: File): Extracted {
        val moovBytes = readMoovBytes(file)
        val layout = parseMoovLayout(moovBytes)
        // mvhd times
        var mvhdTimes: ByteArray? = null
        var mvhdVer = 0
        if (layout.mvhdHeader != null) {
            val off = layout.mvhdDataOffsetInMoov
            val version = moovBytes[off].toInt() and 0xFF
            mvhdVer = version
            val len = if (version == 1) 16 else 8
            // creation at off+4, modification at off+8 (v0) or off+12 (v1)
            // we copy creation+mod together = 8 or 16 bytes starting at off+4
            mvhdTimes = moovBytes.copyOfRange(off + 4, off + 4 + len)
        }
        val trakTimes = layout.trakLayouts.map { tl ->
            var tkhdTimes: ByteArray? = null
            var tkhdVer = 0
            var mdhdTimes: ByteArray? = null
            var mdhdVer = 0
            tl.tkhdHeader?.let { h ->
                val off = (h.start + h.headerSize).toInt()
                val v = moovBytes[off].toInt() and 0xFF
                tkhdVer = v
                val l = if (v == 1) 16 else 8
                tkhdTimes = moovBytes.copyOfRange(off + 4, off + 4 + l)
            }
            tl.mdhdHeader?.let { h ->
                val off = (h.start + h.headerSize).toInt()
                val v = moovBytes[off].toInt() and 0xFF
                mdhdVer = v
                val l = if (v == 1) 16 else 8
                mdhdTimes = moovBytes.copyOfRange(off + 4, off + 4 + l)
            }
            TrakTimes(tkhdTimes, tkhdVer, mdhdTimes, mdhdVer)
        }
        // udta filtered
        val udtaChildrenFiltered = mutableListOf<ByteArray>()
        layout.udtaHeader?.let { uh ->
            val udtaStart = uh.start.toInt()
            val udtaEnd = (uh.start + uh.size).toInt()
            val payloadStart = udtaStart + uh.headerSize
            val children = parseChildrenFromBytes(moovBytes, payloadStart, uh.size - uh.headerSize)
            for (ch in children) {
                if (ch.type.contentEquals(TYPE_MCVR)) continue
                val raw = moovBytes.copyOfRange(ch.start.toInt(), (ch.start + ch.size).toInt())
                udtaChildrenFiltered.add(raw)
            }
        }
        // meta raw
        val metaRaw = layout.metaHeader?.let { mh ->
            moovBytes.copyOfRange(mh.start.toInt(), (mh.start + mh.size).toInt())
        }
        return Extracted(mvhdTimes, mvhdVer, trakTimes, udtaChildrenFiltered, metaRaw)
    }

    // ---------- low level helpers ----------

    private fun readHeader(raf: RandomAccessFile, offset: Long): BoxHeader? {
        if (offset + 8 > raf.length()) return null
        raf.seek(offset)
        val sizeBytes = ByteArray(4)
        raf.readFully(sizeBytes)
        val type = ByteArray(4)
        raf.readFully(type)
        var size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        var headerSize = 8
        if (size == 1L) {
            if (offset + 16 > raf.length()) return null
            val largesize = ByteArray(8)
            raf.readFully(largesize)
            size = ByteBuffer.wrap(largesize).order(ByteOrder.BIG_ENDIAN).long
            headerSize = 16
        } else if (size == 0L) {
            size = raf.length() - offset
        }
        return BoxHeader(offset, headerSize, size, type)
    }

    private fun readHeaderFromBytes(bytes: ByteArray, offset: Int): BoxHeader {
        val size = readU32(bytes, offset).toLong() and 0xFFFFFFFFL
        val type = bytes.copyOfRange(offset + 4, offset + 8)
        return if (size == 1L) {
            val largesize = readU64(bytes, offset + 8)
            BoxHeader(offset.toLong(), 16, largesize, type)
        } else {
            val s = if (size == 0L) (bytes.size - offset).toLong() else size
            BoxHeader(offset.toLong(), 8, s, type)
        }
    }

    private fun parseChildrenFromBytes(moovBytes: ByteArray, payloadStart: Int, payloadSize: Long): List<BoxHeader> {
        val list = mutableListOf<BoxHeader>()
        var off = payloadStart
        val end = payloadStart + payloadSize.toInt()
        while (off + 8 <= end && off + 8 <= moovBytes.size) {
            val size = readU32(moovBytes, off).toLong() and 0xFFFFFFFFL
            val type = moovBytes.copyOfRange(off + 4, off + 8)
            var headerSize = 8
            var boxSize = size
            if (boxSize == 1L) {
                if (off + 16 > end) break
                boxSize = readU64(moovBytes, off + 8)
                headerSize = 16
            } else if (boxSize == 0L) {
                boxSize = (end - off).toLong()
            }
            if (boxSize < headerSize) break
            if (off + boxSize > end) break
            list.add(BoxHeader(off.toLong(), headerSize, boxSize, type))
            off += boxSize.toInt()
        }
        return list
    }

    private fun parseChildrenFromBytes(moovBytes: ByteArray, absStartInMoov: Int): List<BoxHeader> {
        // absStartInMoov points to payload start (already after parent header)
        // We need to know payload size: read until we hit next sibling; but for moov we know moov size
        // This overload is for moov direct children: payload is moov header size .. moov end
        // Use remaining bytes
        val remaining = moovBytes.size - absStartInMoov
        return parseChildrenFromBytes(moovBytes, absStartInMoov, remaining.toLong())
    }

    private fun readU32(bytes: ByteArray, off: Int): Int {
        return ((bytes[off].toInt() and 0xFF) shl 24) or
                ((bytes[off + 1].toInt() and 0xFF) shl 16) or
                ((bytes[off + 2].toInt() and 0xFF) shl 8) or
                (bytes[off + 3].toInt() and 0xFF)
    }

    private fun readU64(bytes: ByteArray, off: Int): Long {
        val bb = ByteBuffer.wrap(bytes, off, 8).order(ByteOrder.BIG_ENDIAN)
        return bb.long
    }
}
