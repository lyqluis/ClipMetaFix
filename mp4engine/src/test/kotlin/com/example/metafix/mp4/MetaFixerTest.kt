package com.example.metafix.mp4

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class MetaFixerTest {

    @get:Rule val tmp = TemporaryFolder()

    // ---------- 构造工具 ----------

    private fun u32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()

    private fun typeOf(s: String): Int =
        (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code

    private fun box(type: Int, vararg parts: ByteArray): ByteArray {
        val payload = parts.fold(ByteArray(0)) { acc, p -> acc + p }
        return u32(payload.size + 8) + u32(type) + payload
    }

    private fun fullBox(type: Int, version: Int, flags: Int, payload: ByteArray): ByteArray =
        box(type, u32((version shl 24) or flags) + payload)

    private fun datesBox(type: Int, creation: Int, modification: Int, filler: Int): ByteArray =
        fullBox(type, 0, if (type == T.Tkhd) 7 else 0,
            u32(creation) + u32(modification) + ByteArray(filler))

    // ---------- 样本构造 ----------

    private fun buildA(): ByteArray {
        val mvhd = datesBox(T.Mvhd, 0x11111111, 0x22222222, 92)
        val trakV = box(T.Trak,
            datesBox(T.Tkhd, 0x33333333, 0x44444444, 80),
            box(T.Mdia, datesBox(T.Mdhd, 0x55555555, 0x66666666, 24)))
        val trakA = box(T.Trak,
            datesBox(T.Tkhd, 0x77777777, 0x88888888.toInt(), 80),
            box(T.Mdia, datesBox(T.Mdhd, 0x99999999.toInt(), 0xAAAAAAAA.toInt(), 24)))
        val udta = box(T.Udta,
            box(T.Xyz, "+31.4165+121.2694/".toByteArray()),
            box(T.Mcvr, ByteArray(64) { it.toByte() }))
        val meta = fullBox(T.Meta, 0, 0, ByteArray(100) { (it * 7 + 3).toByte() })
        val moov = box(T.Moov, mvhd, udta, meta, trakV, trakA)
        return box(T.Ftyp, "mp42".toByteArray()) + ByteArray(16) + moov
    }

    private fun buildB(moovFirst: Boolean): Pair<ByteArray, ByteArray> {
        val mdatPayload = ByteArray(4096) { (it % 251).toByte() }
        val mdat = box(typeOf("mdat"), mdatPayload)
        val mvhd = datesBox(T.Mvhd, 0xBBBBBBBB.toInt(), 0xCCCCCCCC.toInt(), 92)
        val trakV = box(T.Trak,
            datesBox(T.Tkhd, 0xDDDDDDDD.toInt(), 0xEEEEEEEE.toInt(), 80),
            box(T.Mdia, datesBox(T.Mdhd, 0xABCDEF01.toInt(), 0xABCDEF02.toInt(), 24)))
        val trakA = box(T.Trak,
            datesBox(T.Tkhd, 0xABCDEF03.toInt(), 0xABCDEF04.toInt(), 80),
            box(T.Mdia, datesBox(T.Mdhd, 0xABCDEF05.toInt(), 0xABCDEF06.toInt(), 24)))
        val meta = fullBox(T.Meta, 0, 0, ByteArray(20))
        val moov = box(T.Moov, mvhd, meta, trakV, trakA)
        val ftyp = box(T.Ftyp, "mp42".toByteArray())
        return (if (moovFirst) ftyp + moov + mdat else ftyp + mdat + moov) to mdatPayload
    }

    // ---------- 测试 ----------

    @Test fun `moov 后置 - 端到端修复`() = runFixTest(false)

    @Test fun `moov 前置 - 端到端修复`() = runFixTest(true)

    private fun runFixTest(moovFirst: Boolean) {
        val aFile = tmp.newFile("a.mp4").apply { writeBytes(buildA()) }
        val (bBytes, mdatPayload) = buildB(moovFirst)
        val bFile = tmp.newFile("b.mp4").apply { writeBytes(bBytes) }
        val outFile = tmp.newFile("out.mp4")

        MetaFixer.fix(DonorExtractor.extract(aFile.toPath()), bFile.toPath(), outFile.toPath())

        FileChannel.open(outFile.toPath(), StandardOpenOption.READ).use { ch ->
            val topBoxes = scanTopLevel(ch)
            val moov = topBoxes.first { it.type == T.Moov }
            val view = BoxView(readAt(ch, moov.offset, moov.size.toInt()))
            val children = view.children()

            // 1. mvhd 日期 == A
            val mvhd = children.first { it.type == T.Mvhd }
            assertArrayEquals(
                u32(0x11111111) + u32(0x22222222),
                view.buf.copyOfRange(mvhd.payloadStart + 4, mvhd.payloadStart + 12)
            )

            // 2. trak 的 tkhd/mdhd 日期 == A（按顺序）
            val traks = children.filter { it.type == T.Trak }
            assertEquals(2, traks.size)
            fun scopeDates(scope: Box, type: Int): ByteArray {
                val b = view.children(scope.payloadStart, scope.end).first { it.type == type }
                return view.buf.copyOfRange(b.payloadStart + 4, b.payloadStart + 12)
            }
            val mdia0 = view.children(traks[0].payloadStart, traks[0].end)
                .first { it.type == T.Mdia }
            assertArrayEquals(u32(0x33333333) + u32(0x44444444), scopeDates(traks[0], T.Tkhd))
            assertArrayEquals(u32(0x55555555) + u32(0x66666666), scopeDates(mdia0, T.Mdhd))
            assertArrayEquals(u32(0x77777777) + u32(0x88888888.toInt()), scopeDates(traks[1], T.Tkhd))

            // 3. udta：有 ©xyz、无 mcvr
            val udta = children.firstOrNull { it.type == T.Udta }
              ?: throw AssertionError("输出缺少 udta")
            val udtaKids = view.children(udta.payloadStart, udta.end)
            assertTrue(udtaKids.any { it.type == T.Xyz })
            assertFalse(udtaKids.any { it.type == T.Mcvr })

            // 4. meta 字节级 == A
            val (viewA, kidsA) = parseA()
            assertArrayEquals(
                viewA.bytes(kidsA.first { it.type == T.Meta }),
                view.bytes(children.first { it.type == T.Meta })
            )

            // 5. mdat 字节级一致
            val mdat = topBoxes.first { it.type == typeOf("mdat") }
            val payload = readAt(ch, mdat.offset + mdat.headerSize, mdatPayload.size)
            assertArrayEquals(mdatPayload, payload)

            // 6. 顶层结构：ftyp/mdat/moov
            assertEquals(3, topBoxes.size)
        }
    }

    private fun parseA(): Pair<BoxView, List<Box>> {
        val f = tmp.newFile("a_ref.mp4").apply { writeBytes(buildA()) }
        FileChannel.open(f.toPath(), StandardOpenOption.READ).use { ch ->
            val moov = scanTopLevel(ch).first { it.type == T.Moov }
            val view = BoxView(readAt(ch, moov.offset, moov.size.toInt()))
            return view to view.children()
        }
    }

    @Test fun `非 MP4 给出明确错误`() {
        val junk = tmp.newFile("junk.mp4").apply { writeBytes(ByteArray(100)) }
        try {
            DonorExtractor.extract(junk.toPath())
            fail("应当抛出 Mp4Exception")
        } catch (e: Mp4Exception) {
            assertTrue(e.message!!.contains("moov"))
        }
    }
}
