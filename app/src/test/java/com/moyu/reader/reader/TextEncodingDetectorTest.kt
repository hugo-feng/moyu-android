package com.moyu.reader.reader

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextEncodingDetector 的纯 JVM 单元测试（不需要 Robolectric / 模拟器）。
 *
 * 覆盖任务要求的判定：严格 UTF-8 接受、GBK/GB18030 不误判为 UTF-8、
 * UTF-8 BOM 剥离、UTF-16LE/BE BOM 识别、normalize 换行归一化、
 * decodeAs 用错编码产生替换字符。
 */
class TextEncodingDetectorTest {

    private val utf8 = StandardCharsets.UTF_8
    private val gb18030: Charset = Charset.forName("GB18030")
    private val gbk: Charset = Charset.forName("GBK")

    private val sample = "第一章 开始\n这是中文正文，包含标点。\nThe quick brown fox.\n"

    // ---------------------------------------------------------------- UTF-8

    @Test
    fun `valid utf8 is accepted as UTF-8 with no replacement chars`() {
        val bytes = sample.toByteArray(utf8)
        assertTrue(
            "valid UTF-8 must pass the strict decoder",
            TextEncodingDetector.canDecodeStrictly(bytes, utf8),
        )
        val r = TextEncodingDetector.detectAndDecode(bytes)
        assertEquals("UTF-8", r.encoding)
        assertFalse("clean UTF-8 must not be flagged uncertain", r.uncertain)
        assertEquals(0, r.replacementCount)
        assertEquals(sample, r.text)
    }

    @Test
    fun `empty input yields empty UTF-8 result`() {
        val r = TextEncodingDetector.detectAndDecode(ByteArray(0))
        assertEquals("", r.text)
        assertEquals("UTF-8", r.encoding)
        assertEquals(0, r.replacementCount)
        assertFalse(r.uncertain)
    }

    // ------------------------------------------------------- GBK / GB18030

    @Test
    fun `gb18030 bytes are not misdetected as utf8`() {
        val bytes = sample.toByteArray(gb18030)
        assertFalse(
            "GB18030 bytes must NOT be strictly decodable as UTF-8",
            TextEncodingDetector.canDecodeStrictly(bytes, utf8),
        )
        val r = TextEncodingDetector.detectAndDecode(bytes)
        assertNotEquals("GB18030 bytes were misdetected as UTF-8", "UTF-8", r.encoding)
        assertEquals("GB18030", r.encoding)
        assertEquals(0, r.replacementCount)
        assertEquals(sample, r.text)
        assertFalse(r.uncertain)
    }

    @Test
    fun `gbk bytes are not misdetected as utf8`() {
        val bytes = sample.toByteArray(gbk)
        assertFalse(
            "GBK bytes must NOT be strictly decodable as UTF-8",
            TextEncodingDetector.canDecodeStrictly(bytes, utf8),
        )
        val r = TextEncodingDetector.detectAndDecode(bytes)
        assertNotEquals("GBK bytes were misdetected as UTF-8", "UTF-8", r.encoding)
        assertEquals(0, r.replacementCount)
        assertEquals(sample, r.text)
    }

    // ------------------------------------------------------------- BOM 处理

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    @Test
    fun `utf8 BOM is stripped so it never appears in output`() {
        val r = TextEncodingDetector.detectAndDecode(utf8Bom + sample.toByteArray(utf8))
        assertEquals("UTF-8", r.encoding)
        assertFalse(r.uncertain)
        assertEquals("BOM char must not be present anywhere", -1, r.text.indexOf('\uFEFF'))
        assertEquals(sample, r.text)
    }

    @Test
    fun `utf16le BOM is detected`() {
        val bytes = "\uFEFF".toByteArray(StandardCharsets.UTF_16LE) +
            sample.toByteArray(StandardCharsets.UTF_16LE)
        val r = TextEncodingDetector.detectAndDecode(bytes)
        assertEquals("UTF-16LE", r.encoding)
        assertFalse(r.uncertain)
        // 观察到的实际行为：JDK 的 UTF-16LE 解码器不会吞掉 BOM，
        // 因此原文仍带一个 U+FEFF，由 normalize() 负责去掉（见下一个断言）。
        assertEquals("\uFEFF$sample", r.text)
        assertEquals("normalize() must remove the leading BOM char", sample, TextEncodingDetector.normalize(r.text))
    }

    @Test
    fun `utf16be BOM is detected`() {
        val bytes = "\uFEFF".toByteArray(StandardCharsets.UTF_16BE) +
            sample.toByteArray(StandardCharsets.UTF_16BE)
        val r = TextEncodingDetector.detectAndDecode(bytes)
        assertEquals("UTF-16BE", r.encoding)
        assertFalse(r.uncertain)
        assertEquals("\uFEFF$sample", r.text)
        assertEquals(sample, TextEncodingDetector.normalize(r.text))
    }

    // -------------------------------------------------- 无 BOM 的 UTF-16 启发式

    /**
     * 观察到的真实行为（限制，不是期望行为）：
     * 纯 ASCII 的 UTF-16LE 文本里每个高位字节都是 0x00，而 0x00 是**合法** UTF-8，
     * 因此第 2 步「严格 UTF-8 校验」会先命中，第 3 步的无 BOM UTF-16 启发式根本走不到，
     * 结果被当成 UTF-8 且 uncertain = false（正文里混入 NUL）。此测试固定记录该行为。
     */
    @Test
    fun `bomless ascii utf16le falls through to UTF-8 (observed limitation)`() {
        val text = "Chapter 1\n".repeat(20)
        val bytes = text.toByteArray(StandardCharsets.UTF_16LE)
        assertTrue(
            "ASCII utf16le bytes are (accidentally) valid UTF-8",
            TextEncodingDetector.canDecodeStrictly(bytes, utf8),
        )
        val r = TextEncodingDetector.detectAndDecode(bytes)
        assertEquals("UTF-8", r.encoding)
        assertFalse("misdetection is not flagged as uncertain", r.uncertain)
        assertTrue("decoded text contains embedded NULs", r.text.contains('\u0000'))
        assertNotEquals(text, r.text)
    }

    /** 乱码字节破坏 UTF-8 严格校验、且高位字节以 0x00 为主时，启发式才生效。 */
    @Test
    fun `bomless utf16le with a utf8-breaking char is detected by the heuristic`() {
        val text = "caf\u00E9\n".repeat(30)
        val bytes = text.toByteArray(StandardCharsets.UTF_16LE)
        assertFalse(TextEncodingDetector.canDecodeStrictly(bytes, utf8))
        val r = TextEncodingDetector.detectAndDecode(bytes)
        assertEquals("UTF-16LE", r.encoding)
        assertTrue("heuristic result must be flagged uncertain", r.uncertain)
        assertEquals(text, r.text)
    }

    // ---------------------------------------------------------------- normalize

    @Test
    fun `normalize converts CRLF and CR to LF`() {
        assertEquals("a\nb\nc", TextEncodingDetector.normalize("a\r\nb\rc"))
        assertEquals("a\nb", TextEncodingDetector.normalize("\uFEFFa\r\nb"))
        assertEquals("", TextEncodingDetector.normalize(""))
        assertEquals("\n\n", TextEncodingDetector.normalize("\r\n\r"))
        assertEquals("a\nb", TextEncodingDetector.normalize("a\r\nb"))
        assertEquals("x\ny\nz", TextEncodingDetector.normalize("x\ny\nz"))
    }

    // ----------------------------------------------------------------- decodeAs

    @Test
    fun `decodeAs with the wrong encoding produces replacement chars`() {
        val gbkBytes = sample.toByteArray(gb18030)

        val asUtf8 = TextEncodingDetector.decodeAs(gbkBytes, "UTF-8")
        assertEquals("UTF-8", asUtf8.encoding)
        assertTrue("expected U+FFFD replacements, got ${asUtf8.replacementCount}", asUtf8.replacementCount > 0)
        assertTrue(asUtf8.text.contains('\uFFFD'))
        assertTrue("garbled decode must be flagged uncertain", asUtf8.uncertain)
        assertNotEquals(sample, asUtf8.text)

        val asGbk = TextEncodingDetector.decodeAs(gbkBytes, "GB18030")
        assertEquals(0, asGbk.replacementCount)
        assertEquals(sample, asGbk.text)
        assertFalse(asGbk.uncertain)
    }

    @Test
    fun `decodeAs utf8 skips a BOM`() {
        val bytes = utf8Bom + sample.toByteArray(utf8)
        val r = TextEncodingDetector.decodeAs(bytes, "UTF-8")
        assertEquals(sample, r.text)
        assertEquals(-1, r.text.indexOf('\uFEFF'))
        assertEquals(0, r.replacementCount)
    }

    @Test
    fun `decodeAs with an unknown charset name falls back to UTF-8 instead of throwing`() {
        val bytes = sample.toByteArray(utf8)
        val r = TextEncodingDetector.decodeAs(bytes, "NOT-A-REAL-CHARSET")
        assertEquals(sample, r.text)
        assertEquals(0, r.replacementCount)
    }
}
