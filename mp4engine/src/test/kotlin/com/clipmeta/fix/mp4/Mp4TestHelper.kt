package com.clipmeta.fix.mp4

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Synthetic MP4 builder for unit tests, without external files.
 * Generates minimal valid structure that mirrors real Xiaomi samples:
 * ftyp + mdat + moov (mvhd + udta(©xyz+mcvr) + meta + trak video + trak audio)
 */
object Mp4TestHelper {

    fun buildBox(type: String, payload: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.US_ASCII)
        val size = 8 + payload.size
        val bb = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(size)
        bb.put(t)
        bb.put(payload)
        return bb.array()
    }

    fun buildBoxRawType(typeBytes: ByteArray, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val bb = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(size)
        bb.put(typeBytes)
        bb.put(payload)
        return bb.array()
    }

    fun u32(v: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v.toInt()).array()
    fun u64(v: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()

    fun makeMvhd(creation: Long, modification: Long, timescale: Long = 1000, duration: Long = 5000): ByteArray {
        val bb = ByteBuffer.allocate(110).order(ByteOrder.BIG_ENDIAN)
        bb.put(0) // version 0
        bb.put(byteArrayOf(0, 0, 0)) // flags
        bb.putInt(creation.toInt())
        bb.putInt(modification.toInt())
        bb.putInt(timescale.toInt())
        bb.putInt(duration.toInt())
        bb.putInt(0x00010000) // rate 1.0
        bb.putShort(0x0100.toShort()) // volume
        bb.putShort(0)
        bb.putLong(0); bb.putLong(0) // reserved
        // matrix 36 bytes identity
        bb.putInt(0x00010000); bb.putInt(0); bb.putInt(0)
        bb.putInt(0); bb.putInt(0x00010000); bb.putInt(0)
        bb.putInt(0); bb.putInt(0); bb.putInt(0x40000000.toInt())
        bb.putInt(0); bb.putInt(0); bb.putInt(0); bb.putInt(0); bb.putInt(0); bb.putInt(0) // pre_defined
        bb.putInt(2) // next track id
        return buildBox("mvhd", bb.array().copyOf(bb.position()))
    }

    fun makeTkhd(creation: Long, modification: Long, trackId: Int = 1): ByteArray {
        val bb = ByteBuffer.allocate(84).order(ByteOrder.BIG_ENDIAN)
        bb.put(0) // version
        bb.put(byteArrayOf(0, 0, 0x07)) // flags 0x000007
        bb.putInt(creation.toInt())
        bb.putInt(modification.toInt())
        bb.putInt(trackId)
        bb.putInt(0) // reserved
        bb.putInt(5000) // duration
        bb.putLong(0); bb.putLong(0) // reserved 8
        bb.putShort(0); bb.putShort(0) // layer alt
        bb.putShort(0x0100.toShort()); bb.putShort(0) // volume
        // matrix
        bb.putInt(0x00010000); bb.putInt(0); bb.putInt(0)
        bb.putInt(0); bb.putInt(0x00010000); bb.putInt(0)
        bb.putInt(0); bb.putInt(0); bb.putInt(0x40000000.toInt())
        bb.putInt(1280 shl 16); bb.putInt(720 shl 16) // width height 16.16
        val payload = bb.array().copyOf(bb.position())
        return buildBox("tkhd", payload)
    }

    fun makeMdhd(creation: Long, modification: Long, timescale: Long = 1000, duration: Long = 5000): ByteArray {
        val bb = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        bb.put(0); bb.put(byteArrayOf(0, 0, 0))
        bb.putInt(creation.toInt())
        bb.putInt(modification.toInt())
        bb.putInt(timescale.toInt())
        bb.putInt(duration.toInt())
        bb.putShort(0x55.toShort()) // language 'und'
        bb.putShort(0)
        return buildBox("mdhd", bb.array().copyOf(bb.position()))
    }

    fun makeHdlr(handler: String = "vide"): ByteArray {
        val bb = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(0) // version+flags
        bb.putInt(0) // pre_defined
        bb.put(handler.toByteArray(Charsets.US_ASCII))
        bb.putInt(0); bb.putInt(0); bb.putInt(0)
        bb.put("VideoHandler".toByteArray(Charsets.US_ASCII))
        bb.put(0)
        return buildBox("hdlr", bb.array().copyOf(bb.position()))
    }

    fun makeMdia(mdhd: ByteArray, hdlr: ByteArray = makeHdlr()): ByteArray {
        // mdia = mdhd + hdlr + minf (empty)
        val minf = buildBox("minf", ByteArray(0))
        val payload = concat(listOf(mdhd, hdlr, minf))
        return buildBox("mdia", payload)
    }

    fun makeTrak(tkhd: ByteArray, mdia: ByteArray): ByteArray = buildBox("trak", concat(listOf(tkhd, mdia)))

    fun makeCxyz(): ByteArray {
        // 8 header + 4 (00 12 15 c7) + ASCII "+31.4165+121.2694/"
        val payload = byteArrayOf(0x00, 0x12, 0x15, 0xC7.toByte()) + "+31.4165+121.2694/".toByteArray(Charsets.US_ASCII)
        return buildBoxRawType(TYPE_CXYZ, payload)
    }

    fun makeMcvr(): ByteArray {
        // fake JPEG FFD8 ... small 10 bytes for test
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10) + ByteArray(100) { 0x42 }
        return buildBox("mcvr", jpeg)
    }

    fun makeUdta(includeCxyz: Boolean = true, includeMcvr: Boolean = true): ByteArray {
        val children = mutableListOf<ByteArray>()
        if (includeCxyz) children.add(makeCxyz())
        if (includeMcvr) children.add(makeMcvr())
        return buildBox("udta", concat(children))
    }

    fun makeKeysAndIlst(): ByteArray {
        val keysEntries = listOf(
            "com.android.version",
            "com.android.manufacturer",
            "com.android.model",
            "com.xiaomi.product.marketname",
            "com.android.capture.fps",
            "com.video.file.type",
            "com.xiaomi.normal_video",
            "xiaomi.exifInfo.videoinfo"
        )
        val kb = ByteBuffer.allocate(4 + 4 + keysEntries.sumOf { 8 + it.length }).order(ByteOrder.BIG_ENDIAN)
        kb.putInt(0); kb.putInt(keysEntries.size)
        for (k in keysEntries) {
            val b = k.toByteArray(Charsets.US_ASCII)
            kb.putInt(8 + b.size); kb.putInt(0x6D647461); kb.put(b)
        }
        val keys = buildBox("keys", kb.array())
        val hdlrPayload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0) // version+flags
            putInt(0)
            put("mdta".toByteArray(Charsets.US_ASCII))
            putInt(0)
        }.array()
        val hdlr = buildBox("hdlr", hdlrPayload)

        val ilst = buildBox("ilst", "fake-ilst-payload".toByteArray(Charsets.US_ASCII))
        val metaPayload = ByteBuffer.allocate(4 + hdlr.size + keys.size + ilst.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0) // version+flags for meta
            put(hdlr); put(keys); put(ilst)
        }.array()
        // meta box itself: header + metaPayload
        return buildBox("meta", metaPayload)
    }

    fun makeFtyp(): ByteArray {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            put("mp42".toByteArray(Charsets.US_ASCII))
            putInt(0)
            put("isom".toByteArray(Charsets.US_ASCII))
            // Actually need 8: major 4 + minor 4 + compat 4 each. Simplify to 16 bytes ftyp size.
        }.array().copyOf(8)
        // Use standard 16 bytes? We'll just build 16 bytes ftyp
        val full = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            put("mp42".toByteArray(Charsets.US_ASCII)); putInt(0)
        }.array() + "isom".toByteArray(Charsets.US_ASCII) + "mp42".toByteArray(Charsets.US_ASCII)
        return buildBox("ftyp", full)
    }

    fun makeMdat(size: Int = 1024): ByteArray = buildBox("mdat", ByteArray(size) { (it % 256).toByte() })

    fun concat(parts: List<ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (p in parts) { System.arraycopy(p, 0, out, off, p.size); off += p.size }
        return out
    }

    /** Build full MP4: ftyp + mdat + moov */
    fun buildMp4(moov: ByteArray, mdatSize: Int = 2048): ByteArray {
        val ftyp = makeFtyp()
        val mdat = makeMdat(mdatSize)
        return concat(listOf(ftyp, mdat, moov))
    }

    fun buildMoov(mvhd: ByteArray, udta: ByteArray?, meta: ByteArray?, traks: List<ByteArray>): ByteArray {
        val children = mutableListOf<ByteArray>()
        children.add(mvhd)
        if (udta != null) children.add(udta)
        if (meta != null) children.add(meta)
        children.addAll(traks)
        return buildBox("moov", concat(children))
    }
}
