package com.clipmeta.fix.mp4

import org.junit.Assert.*
import org.junit.Test

class Mp4LocationTest {

    @Test
    fun testParseStandardCxyz() {
        val raw = Mp4TestHelper.makeCxyz()
        val loc = XyzLocation.parse(listOf(raw))
        assertEquals("+31.4165+121.2694/", loc)
    }

    @Test
    fun testEmptyAndMcvrOnly() {
        assertNull(XyzLocation.parse(emptyList()))
        val mcvr = Mp4TestHelper.makeMcvr()
        assertNull(XyzLocation.parse(listOf(mcvr)))
        assertFalse(XyzLocation.hasLocation(listOf(mcvr)))
    }

    @Test
    fun testMixedListSkipsMcvr() {
        val mcvr = Mp4TestHelper.makeMcvr()
        val cxyz = Mp4TestHelper.makeCxyz()
        // mcvr 在前也要跳过找到 ©xyz
        assertEquals("+31.4165+121.2694/", XyzLocation.parse(listOf(mcvr, cxyz)))
        assertTrue(XyzLocation.hasLocation(listOf(mcvr, cxyz)))
    }

    @Test
    fun testCorruptBoxIgnored() {
        // 过短 / 非©xyz 类型
        assertNull(XyzLocation.parse(listOf(ByteArray(4))))
        val notXyz = Mp4TestHelper.buildBox("free", "hello".toByteArray())
        assertNull(XyzLocation.parse(listOf(notXyz)))
    }
}
