package com.clipmeta.fix.mp4

import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Mp4PatcherTest {

    @Rule @JvmField
    val tmp = TemporaryFolder()

    private fun writeFile(bytes: ByteArray): File {
        val f = tmp.newFile()
        f.writeBytes(bytes)
        return f
    }

    private fun buildSampleA(): ByteArray {
        val creation = 0xE6C5A0C3L // sample value
        val mvhd = Mp4TestHelper.makeMvhd(creation, creation)
        val tkhd1 = Mp4TestHelper.makeTkhd(creation, creation, 1)
        val tkhd2 = Mp4TestHelper.makeTkhd(creation, creation, 2)
        val mdhd1 = Mp4TestHelper.makeMdhd(creation, creation)
        val mdhd2 = Mp4TestHelper.makeMdhd(creation, creation, 48000)
        val trak1 = Mp4TestHelper.makeTrak(tkhd1, Mp4TestHelper.makeMdia(mdhd1, Mp4TestHelper.makeHdlr("vide")))
        val trak2 = Mp4TestHelper.makeTrak(tkhd2, Mp4TestHelper.makeMdia(mdhd2, Mp4TestHelper.makeHdlr("soun")))
        val udta = Mp4TestHelper.makeUdta(includeCxyz = true, includeMcvr = true)
        val meta = Mp4TestHelper.makeKeysAndIlst()
        val moov = Mp4TestHelper.buildMoov(mvhd, udta, meta, listOf(trak1, trak2))
        return Mp4TestHelper.buildMp4(moov, mdatSize = 4096)
    }

    private fun buildSampleB(): ByteArray {
        val creationB = 0x11223344L // different time
        val mvhd = Mp4TestHelper.makeMvhd(creationB, creationB, duration = 3360)
        val tkhd1 = Mp4TestHelper.makeTkhd(creationB, creationB, 1)
        val tkhd2 = Mp4TestHelper.makeTkhd(creationB, creationB, 2)
        val mdhd1 = Mp4TestHelper.makeMdhd(creationB, creationB, duration = 3360)
        val mdhd2 = Mp4TestHelper.makeMdhd(creationB, creationB, 48000, 3360)
        val trak1 = Mp4TestHelper.makeTrak(tkhd1, Mp4TestHelper.makeMdia(mdhd1, Mp4TestHelper.makeHdlr("vide")))
        val trak2 = Mp4TestHelper.makeTrak(tkhd2, Mp4TestHelper.makeMdia(mdhd2, Mp4TestHelper.makeHdlr("soun")))
        // B has small meta (only 2 keys) and NO udta
        val smallMetaPayload = ByteBuffer.allocate(4 + 64).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            put("hdlr-fake ".toByteArray())
            put("keys-fake ".toByteArray())
            put("ilst-fake ".toByteArray())
        }.array().copyOf(64 + 4)
        val metaB = Mp4TestHelper.buildBox("meta", smallMetaPayload)
        val moov = Mp4TestHelper.buildMoov(mvhd, null, metaB, listOf(trak1, trak2))
        return Mp4TestHelper.buildMp4(moov, mdatSize = 2048)
    }

    @Test
    fun testParserLocatesBoxes() {
        val fileA = writeFile(buildSampleA())
        val tops = Mp4Parser.parseTopLevel(fileA)
        assertTrue(tops.any { it.typeEquals("ftyp") })
        assertTrue(tops.any { it.typeEquals("mdat") })
        assertTrue(tops.any { it.typeEquals("moov") })
        // moov layout
        val moovBytes = Mp4Parser.readMoovBytes(fileA)
        val layout = Mp4Parser.parseMoovLayout(moovBytes)
        assertNotNull(layout.mvhdHeader)
        assertNotNull(layout.udtaHeader)
        assertNotNull(layout.metaHeader)
        assertEquals(2, layout.trakLayouts.size)
        assertNotNull(layout.trakLayouts[0].tkhdHeader)
        assertNotNull(layout.trakLayouts[0].mdhdHeader)
    }

    @Test
    fun testMoovFrontAndBack() {
        // Build moov front: moov + ftyp + mdat vs ftyp+mdat+moov
        val mvhd = Mp4TestHelper.makeMvhd(123, 123)
        val trak = Mp4TestHelper.makeTrak(Mp4TestHelper.makeTkhd(123,123), Mp4TestHelper.makeMdia(Mp4TestHelper.makeMdhd(123,123)))
        val moov = Mp4TestHelper.buildMoov(mvhd, null, Mp4TestHelper.makeKeysAndIlst(), listOf(trak))
        val mdat = Mp4TestHelper.makeMdat(512)
        val ftyp = Mp4TestHelper.makeFtyp()
        val front = Mp4TestHelper.concat(listOf(moov, ftyp, mdat))
        val back = Mp4TestHelper.concat(listOf(ftyp, mdat, moov))
        val fFront = writeFile(front)
        val fBack = writeFile(back)
        assertNotNull(Mp4Parser.findMoov(fFront))
        assertNotNull(Mp4Parser.findMoov(fBack))
        // Both parse
        Mp4Parser.parseMoovLayout(Mp4Parser.readMoovBytes(fFront))
        Mp4Parser.parseMoovLayout(Mp4Parser.readMoovBytes(fBack))
    }

    @Test
    fun testOutputBoxTreeLegal() {
        val fileA = writeFile(buildSampleA())
        val fileB = writeFile(buildSampleB())
        val extracted = Mp4Parser.extract(fileA)
        val out = tmp.newFile()
        val res = Mp4Patcher.patch(fileB, extracted, out)
        assertTrue(res is Mp4Patcher.PatchResult.Success)
        // Check output parses
        val tops = Mp4Parser.parseTopLevel(out)
        assertEquals(3, tops.size) // ftyp mdat moov
        val moovBytes = Mp4Parser.readMoovBytes(out)
        val layout = Mp4Parser.parseMoovLayout(moovBytes)
        // size self-consistent
        assertEquals(moovBytes.size.toLong(), layout.moovSize)
        // children sizes sum = moov payload
        val payloadSize = layout.moovSize - layout.moovHeaderSize
        val sum = layout.childHeaders.sumOf { it.size }
        assertEquals(payloadSize, sum)
    }

    @Test
    fun testDatesCopied() {
        val fileA = writeFile(buildSampleA())
        val fileB = writeFile(buildSampleB())
        val extractedA = Mp4Parser.extract(fileA)
        val out = tmp.newFile()
        Mp4Patcher.patch(fileB, extractedA, out)
        val extractedOut = Mp4Parser.extract(out)
        // mvhd
        assertArrayEquals(extractedA.mvhdTimes, extractedOut.mvhdTimes)
        // trak
        assertEquals(extractedA.trakTimes.size, extractedOut.trakTimes.size)
        for (i in extractedA.trakTimes.indices) {
            assertArrayEquals(extractedA.trakTimes[i].tkhdTimes, extractedOut.trakTimes[i].tkhdTimes)
            assertArrayEquals(extractedA.trakTimes[i].mdhdTimes, extractedOut.trakTimes[i].mdhdTimes)
        }
    }

    @Test
    fun testUdtaFiltered() {
        val fileA = writeFile(buildSampleA())
        val fileB = writeFile(buildSampleB())
        val extractedA = Mp4Parser.extract(fileA)
        // A should have 1 filtered child (©xyz) skipping mcvr
        assertEquals(1, extractedA.udtaChildrenFiltered.size)
        // Verify it's ©xyz type
        val raw = extractedA.udtaChildrenFiltered[0]
        assertEquals(0xA9.toByte(), raw[4])
        val out = tmp.newFile()
        Mp4Patcher.patch(fileB, extractedA, out)
        val moovOut = Mp4Parser.readMoovBytes(out)
        val layoutOut = Mp4Parser.parseMoovLayout(moovOut)
        assertNotNull(layoutOut.udtaHeader)
        // Check udta children in output
        val udtaHeader = layoutOut.udtaHeader!!
        val extractedOut = Mp4Parser.extract(out)
        assertEquals(1, extractedOut.udtaChildrenFiltered.size)
        assertFalse(extractedOut.udtaChildrenFiltered.any { it.copyOfRange(4,8).contentEquals("mcvr".toByteArray(Charsets.US_ASCII)) })
        // Ensure ©xyz present
        assertTrue(extractedOut.udtaChildrenFiltered[0].copyOfRange(4,8).contentEquals(TYPE_CXYZ))
    }

    @Test
    fun testMetaReplaced() {
        val fileA = writeFile(buildSampleA())
        val fileB = writeFile(buildSampleB())
        val extractedA = Mp4Parser.extract(fileA)
        val out = tmp.newFile()
        Mp4Patcher.patch(fileB, extractedA, out)
        val outMeta = Mp4Parser.extract(out).metaRaw
        assertNotNull(outMeta)
        assertArrayEquals(extractedA.metaRaw, outMeta)
    }

    @Test
    fun testMdatUnchanged() {
        val fileB = writeFile(buildSampleB())
        val fileA = writeFile(buildSampleA())
        val extractedA = Mp4Parser.extract(fileA)
        val out = tmp.newFile()
        Mp4Patcher.patch(fileB, extractedA, out)
        // Compare mdat payloads
        val bTops = Mp4Parser.parseTopLevel(fileB)
        val oTops = Mp4Parser.parseTopLevel(out)
        val bMdat = bTops.find { it.typeEquals("mdat") }!!
        val oMdat = oTops.find { it.typeEquals("mdat") }!!
        assertEquals(bMdat.size, oMdat.size)
        val bBytes = Mp4Parser.readBoxBytes(fileB, bMdat)
        val oBytes = Mp4Parser.readBoxBytes(out, oMdat)
        assertArrayEquals(bBytes, oBytes)
    }

    @Test
    fun testLargesizeAndSizeZero() {
        // Build a box with largesize (size==1)
        val payload = "hello-large".toByteArray()
        val bb = ByteBuffer.allocate(8 + 8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(1); bb.put("free".toByteArray()); bb.putLong((16 + payload.size).toLong()); bb.put(payload)
        val freeLarge = bb.array()
        val ftyp = Mp4TestHelper.makeFtyp()
        val mdat = Mp4TestHelper.makeMdat(100)
        val mvhd = Mp4TestHelper.makeMvhd(1,1)
        val trak = Mp4TestHelper.makeTrak(Mp4TestHelper.makeTkhd(1,1), Mp4TestHelper.makeMdia(Mp4TestHelper.makeMdhd(1,1)))
        val moov = Mp4TestHelper.buildMoov(mvhd, null, null, listOf(trak))
        val fileBytes = Mp4TestHelper.concat(listOf(ftyp, freeLarge, mdat, moov))
        val f = writeFile(fileBytes)
        val tops = Mp4Parser.parseTopLevel(f)
        assertTrue(tops.any { it.typeEquals("free") })
        assertEquals(4, tops.size)
    }
}
