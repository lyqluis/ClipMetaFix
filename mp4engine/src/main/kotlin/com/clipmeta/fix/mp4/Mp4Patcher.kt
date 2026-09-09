package com.clipmeta.fix.mp4

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Core patch logic:
 * - mvhd/tkhd/mdhd creation+modification bytes from A -> B
 * - udta: filtered copy (skip mcvr)
 * - meta: full replacement
 *
 * Streaming: mdat not loaded, copy via channel.
 */
object Mp4Patcher {

    /**
     * Patch file B using extracted info from A, writing to dest (temporary).
     * Returns true if successful.
     */
    fun patch(sourceB: File, extractedA: Mp4Parser.Extracted, dest: File): PatchResult {
        val moovHeader = Mp4Parser.findMoov(sourceB) ?: return PatchResult.Failure("moov not found in B")
        val moovStart = moovHeader.start
        val moovEnd = moovHeader.end
        val moovBytes = Mp4Parser.readMoovBytes(sourceB)
        val layoutB = Mp4Parser.parseMoovLayout(moovBytes)

        // Clone moov bytes for in-place time patch
        val patchedMoovForTimes = moovBytes.clone()

        // 1. Patch mvhd
        if (extractedA.mvhdTimes != null && layoutB.mvhdHeader != null) {
            val off = layoutB.mvhdDataOffsetInMoov
            val verB = patchedMoovForTimes[off].toInt() and 0xFF
            val lenB = if (verB == 1) 16 else 8
            val lenA = extractedA.mvhdTimes.size
            // If versions match, direct copy; else convert (truncate or zero-extend)
            if (lenA == lenB) {
                System.arraycopy(extractedA.mvhdTimes, 0, patchedMoovForTimes, off + 4, lenB)
            } else if (lenA == 8 && lenB == 16) {
                // A v0 -> B v1: write 64-bit (high 0, low 32)
                // creation
                writeU64FromU32(patchedMoovForTimes, off + 4, extractedA.mvhdTimes, 0)
                writeU64FromU32(patchedMoovForTimes, off + 12, extractedA.mvhdTimes, 4)
            } else if (lenA == 16 && lenB == 8) {
                // A v1 -> B v0: truncate to low 32
                writeU32FromU64Low(patchedMoovForTimes, off + 4, extractedA.mvhdTimes, 0)
                writeU32FromU64Low(patchedMoovForTimes, off + 8, extractedA.mvhdTimes, 8)
            }
        }

        // 2. Patch trak tkhd/mdhd in order
        for (i in layoutB.trakLayouts.indices) {
            if (i >= extractedA.trakTimes.size) break
            val t = extractedA.trakTimes[i]
            val tl = layoutB.trakLayouts[i]
            tl.tkhdHeader?.let { h ->
                if (t.tkhdTimes != null) {
                    val off = (h.start + h.headerSize).toInt()
                    val verB = patchedMoovForTimes[off].toInt() and 0xFF
                    val lenB = if (verB == 1) 16 else 8
                    val lenA = t.tkhdTimes.size
                    if (lenA == lenB) {
                        System.arraycopy(t.tkhdTimes, 0, patchedMoovForTimes, off + 4, lenB)
                    } else if (lenA == 8 && lenB == 16) {
                        writeU64FromU32(patchedMoovForTimes, off + 4, t.tkhdTimes, 0)
                        writeU64FromU32(patchedMoovForTimes, off + 12, t.tkhdTimes, 4)
                    } else if (lenA == 16 && lenB == 8) {
                        writeU32FromU64Low(patchedMoovForTimes, off + 4, t.tkhdTimes, 0)
                        writeU32FromU64Low(patchedMoovForTimes, off + 8, t.tkhdTimes, 8)
                    }
                }
            }
            tl.mdhdHeader?.let { h ->
                if (t.mdhdTimes != null) {
                    val off = (h.start + h.headerSize).toInt()
                    val verB = patchedMoovForTimes[off].toInt() and 0xFF
                    val lenB = if (verB == 1) 16 else 8
                    val lenA = t.mdhdTimes.size
                    if (lenA == lenB) {
                        System.arraycopy(t.mdhdTimes, 0, patchedMoovForTimes, off + 4, lenB)
                    } else if (lenA == 8 && lenB == 16) {
                        writeU64FromU32(patchedMoovForTimes, off + 4, t.mdhdTimes, 0)
                        writeU64FromU32(patchedMoovForTimes, off + 12, t.mdhdTimes, 4)
                    } else if (lenA == 16 && lenB == 8) {
                        writeU32FromU64Low(patchedMoovForTimes, off + 4, t.mdhdTimes, 0)
                        writeU32FromU64Low(patchedMoovForTimes, off + 8, t.mdhdTimes, 8)
                    }
                }
            }
        }

        // 3. Re-parse layout after time patch? Offsets unchanged (sizes same), so layoutB still valid for structural rebuild
        // But we need to use patchedMoovForTimes as base for rebuilding children raws

        // Build new child list
        val bChildren = layoutB.childHeaders // still offsets relative to moov start, but based on original moovBytes; patched bytes have same sizes so offsets still valid
        // Map type to children raws from patched bytes
        val newChildrenRaws = mutableListOf<ByteArray>()
        // Pre-build new udta and meta raws
        val newUdtaRaw: ByteArray? = if (extractedA.udtaChildrenFiltered.isNotEmpty()) {
            buildBox("udta", concat(extractedA.udtaChildrenFiltered))
        } else null
        val newMetaRaw: ByteArray? = extractedA.metaRaw // already includes header

        var hasUdtaInB = bChildren.any { it.type.contentEquals(TYPE_UDTA) }
        var hasMetaInB = bChildren.any { it.type.contentEquals(TYPE_META) }
        var udtaHandled = false
        var metaHandled = false

        for (ch in bChildren) {
            when {
                ch.type.contentEquals(TYPE_UDTA) -> {
                    if (newUdtaRaw != null) newChildrenRaws.add(newUdtaRaw) else {
                        // filtered empty -> remove udta (no add)
                    }
                    udtaHandled = true
                }
                ch.type.contentEquals(TYPE_META) -> {
                    if (newMetaRaw != null) newChildrenRaws.add(newMetaRaw) else {
                        // keep original? spec says replace, if A has no meta keep B's meta? But A should have meta
                        // If A has no meta, keep B's
                        val raw = patchedMoovForTimes.copyOfRange(ch.start.toInt(), (ch.start + ch.size).toInt())
                        newChildrenRaws.add(raw)
                    }
                    metaHandled = true
                }
                else -> {
                    // For trak/mvhd etc, use patched bytes slice
                    val raw = patchedMoovForTimes.copyOfRange(ch.start.toInt(), (ch.start + ch.size).toInt())
                    newChildrenRaws.add(raw)
                }
            }
        }
        // Insert missing udta/meta if B didn't have them but A does
        if (!udtaHandled && newUdtaRaw != null) {
            // insert after mvhd if exists, otherwise at beginning
            val mvhdIdx = newChildrenRaws.indexOfFirst { it.size >= 8 && it.copyOfRange(4, 8).contentEquals(TYPE_MVHD) }
            if (mvhdIdx >= 0) newChildrenRaws.add(mvhdIdx + 1, newUdtaRaw) else newChildrenRaws.add(0, newUdtaRaw)
        }
        if (!metaHandled && newMetaRaw != null) {
            // insert after udta if exists, else after mvhd
            val udtaIdx = newChildrenRaws.indexOfFirst { it.size >= 8 && it.copyOfRange(4, 8).contentEquals(TYPE_UDTA) }
            val mvhdIdx = newChildrenRaws.indexOfFirst { it.size >= 8 && it.copyOfRange(4, 8).contentEquals(TYPE_MVHD) }
            val insertPos = when {
                udtaIdx >= 0 -> udtaIdx + 1
                mvhdIdx >= 0 -> mvhdIdx + 1
                else -> 0
            }
            newChildrenRaws.add(insertPos, newMetaRaw)
        }

        val newMoovPayload = concat(newChildrenRaws)
        val newMoov = buildBox("moov", newMoovPayload)

        // 4. Stream copy: B[0, moovStart) + newMoov + B[moovEnd, EOF)
        try {
            FileOutputStream(dest).use { fos ->
                val outChannel = fos.channel
                FileInputStream(sourceB).use { fis ->
                    val inChannel = fis.channel
                    // copy head
                    copyRange(inChannel, outChannel, 0, moovStart)
                    // write new moov
                    outChannel.write(ByteBuffer.wrap(newMoov))
                    // copy tail
                    val fileSize = sourceB.length()
                    if (moovEnd < fileSize) {
                        copyRange(inChannel, outChannel, moovEnd, fileSize - moovEnd)
                    }
                }
            }
        } catch (e: Exception) {
            return PatchResult.Failure("IO error: ${e.message}")
        }

        // 5. Validate new file moov parses
        try {
            val checkMoov = Mp4Parser.readMoovBytes(dest)
            Mp4Parser.parseMoovLayout(checkMoov) // throws if invalid
        } catch (e: Exception) {
            return PatchResult.Failure("Validation failed: ${e.message}")
        }

        return PatchResult.Success(dest, newMoov.size.toLong(), moovHeader.size)
    }

    private fun copyRange(inChannel: FileChannel, outChannel: FileChannel, start: Long, count: Long) {
        var pos = start
        var remaining = count
        inChannel.position(pos)
        val buf = ByteBuffer.allocate(8192)
        while (remaining > 0) {
            buf.clear()
            val toRead = minOf(buf.capacity().toLong(), remaining).toInt()
            buf.limit(toRead)
            val read = inChannel.read(buf)
            if (read <= 0) break
            buf.flip()
            outChannel.write(buf)
            remaining -= read
        }
    }

    private fun buildBox(typeAscii: String, payload: ByteArray): ByteArray {
        val type = typeAscii.toByteArray(Charsets.US_ASCII)
        val size = 8 + payload.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put(type)
        buf.put(payload)
        return buf.array()
    }

    private fun buildBox(typeAscii: String, payloads: List<ByteArray>): ByteArray = buildBox(typeAscii, concat(payloads))

    private fun concat(parts: List<ByteArray>): ByteArray {
        if (parts.isEmpty()) return ByteArray(0)
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    private fun writeU64FromU32(dest: ByteArray, destOff: Int, src: ByteArray, srcOff: Int) {
        // src 4 bytes big-endian unsigned -> dest 8 bytes (high 4 zero, low 4 src)
        dest[destOff] = 0
        dest[destOff + 1] = 0
        dest[destOff + 2] = 0
        dest[destOff + 3] = 0
        System.arraycopy(src, srcOff, dest, destOff + 4, 4)
    }

    private fun writeU32FromU64Low(dest: ByteArray, destOff: Int, src: ByteArray, srcOff: Int) {
        // src 8 bytes -> dest 4 bytes low
        System.arraycopy(src, srcOff + 4, dest, destOff, 4)
    }

    sealed class PatchResult {
        data class Success(val file: File, val newMoovSize: Long, val oldMoovSize: Long) : PatchResult()
        data class Failure(val reason: String) : PatchResult()
    }
}
