package com.clipmeta.fix.mp4

/**
 * MP4 box header info.
 * @param start offset from file start
 * @param headerSize 8 or 16
 * @param size total box size (including header), may be > 2^32 when largesize
 * @param type 4-byte type, raw bytes (e.g. 0xA9 0x78 0x79 0x7A for ©xyz)
 */
data class BoxHeader(
    val start: Long,
    val headerSize: Int,
    val size: Long,
    val type: ByteArray
) {
    val end: Long get() = start + size
    val dataOffset: Long get() = start + headerSize
    val dataSize: Long get() = size - headerSize

    fun typeEquals(ascii: String): Boolean {
        if (ascii.length != 4) return false
        val b = ascii.toByteArray(Charsets.US_ASCII)
        return type[0] == b[0] && type[1] == b[1] && type[2] == b[2] && type[3] == b[3]
    }

    fun typeEqualsBytes(expected: ByteArray): Boolean {
        if (expected.size != 4) return false
        return type[0] == expected[0] && type[1] == expected[1] && type[2] == expected[2] && type[3] == expected[3]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoxHeader) return false
        return start == other.start && headerSize == other.headerSize && size == other.size && type.contentEquals(other.type)
    }

    override fun hashCode(): Int {
        var r = start.hashCode()
        r = 31 * r + headerSize
        r = 31 * r + size.hashCode()
        r = 31 * r + type.contentHashCode()
        return r
    }
}

data class BoxNode(
    val header: BoxHeader,
    val children: List<BoxNode> = emptyList()
)

/** Known container types (have children, except meta which has 4B version prefix) */
internal val CONTAINER_TYPES = setOf(
    "moov", "trak", "mdia", "minf", "dinf", "stbl", "edts", "udta", "mvex", "moof", "traf"
)

/** Type bytes helpers */
internal val TYPE_MOOV = "moov".toByteArray(Charsets.US_ASCII)
internal val TYPE_MVHD = "mvhd".toByteArray(Charsets.US_ASCII)
internal val TYPE_TKHD = "tkhd".toByteArray(Charsets.US_ASCII)
internal val TYPE_MDHD = "mdhd".toByteArray(Charsets.US_ASCII)
internal val TYPE_UDTA = "udta".toByteArray(Charsets.US_ASCII)
internal val TYPE_META = "meta".toByteArray(Charsets.US_ASCII)
internal val TYPE_MCVR = "mcvr".toByteArray(Charsets.US_ASCII)
internal val TYPE_TRAK = "trak".toByteArray(Charsets.US_ASCII)
internal val TYPE_MDIA = "mdia".toByteArray(Charsets.US_ASCII)
internal val TYPE_FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
internal val TYPE_MDAT = "mdat".toByteArray(Charsets.US_ASCII)
// ©xyz : 0xA9 78 79 7A
internal val TYPE_CXYZ = byteArrayOf(0xA9.toByte(), 0x78, 0x79, 0x7A)
