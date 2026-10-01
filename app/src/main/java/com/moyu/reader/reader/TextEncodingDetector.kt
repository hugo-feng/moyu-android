package com.moyu.reader.reader

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 文本编码识别与解码。
 *
 * 与 Web 验证器（src/engine/encoding.ts）保持同一套判定顺序与语义，
 * 这样两端的导入结果可以互相比对：
 *   1. BOM 优先（UTF-8 / UTF-16LE / UTF-16BE）；
 *   2. 严格 UTF-8 校验（用平台解码器的 REPORT 模式，不自造字节校验器）；
 *   3. 无 BOM 的 UTF-16 启发式（ASCII 文本在 UTF-16 下出现大量 0x00）；
 *   4. GB18030（GBK 的超集）；
 *   5. 兜底 UTF-8 并标记不确定。
 *
 * 为什么必须做这件事：中文 TXT 没有统一编码，GBK 文本若按 UTF-8 解码会整篇变成
 * 替换字符 —— 这是同类阅读器最常见的差评来源。
 */
object TextEncodingDetector {

    /** 中文环境常见的候选编码，顺序即优先级。 */
    val CANDIDATES: List<String> = listOf("UTF-8", "GB18030", "UTF-16LE", "UTF-16BE", "GBK", "Big5")

    data class Result(
        val text: String,
        /** 实际采用的编码名 */
        val encoding: String,
        /** 解码产生的替换字符数量（U+FFFD），越小越可信 */
        val replacementCount: Int,
        /** 是否走了兜底分支：界面应提示「编码可能不准确，可手动切换」 */
        val uncertain: Boolean,
    )

    /** 用指定编码严格校验是否可解码（不产生替换字符）。 */
    fun canDecodeStrictly(bytes: ByteArray, charset: Charset): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /** 宽松解码：非法序列替换为 U+FFFD，绝不抛异常。 */
    fun decodeLenient(bytes: ByteArray, charset: Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun countReplacements(text: String): Int {
        var count = 0
        for (ch in text) if (ch == '\uFFFD') count++
        return count
    }

    /** 可读字符占比：用于判断解码结果是否可信（乱码文本这个比例会很低）。 */
    private fun readableRatio(text: String): Double {
        if (text.isEmpty()) return 1.0
        val sample = minOf(text.length, 4000)
        var readable = 0
        for (i in 0 until sample) {
            val c = text[i].code
            val ok = (c in 0x4E00..0x9FFF) ||      // CJK 统一表意文字
                (c in 0x20..0x7E) ||                // 可打印 ASCII
                c == 0x0A || c == 0x0D || c == 0x09 ||
                (c in 0x3000..0x303F) ||            // CJK 标点
                (c in 0xFF00..0xFFEF)               // 全角字符
            if (ok) readable++
        }
        return readable.toDouble() / sample
    }

    /** 探测并解码。 */
    fun detectAndDecode(bytes: ByteArray): Result {
        if (bytes.isEmpty()) {
            return Result("", "UTF-8", 0, uncertain = false)
        }

        // 1) BOM 优先
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            val text = decodeLenient(bytes.copyOfRange(3, bytes.size), StandardCharsets.UTF_8)
            return Result(text, "UTF-8", 0, uncertain = false)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Result(decodeLenient(bytes, Charsets.UTF_16LE), "UTF-16LE", 0, uncertain = false)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Result(decodeLenient(bytes, Charsets.UTF_16BE), "UTF-16BE", 0, uncertain = false)
        }

        // 2) 严格 UTF-8 校验（整段校验：避免「前半段合法、后半段乱码」被误判）
        if (canDecodeStrictly(bytes, StandardCharsets.UTF_8)) {
            val text = decodeLenient(bytes, StandardCharsets.UTF_8)
            return Result(text, "UTF-8", countReplacements(text), uncertain = false)
        }

        // 3) 无 BOM 的 UTF-16 启发式
        val probe = bytes.copyOfRange(0, minOf(bytes.size, 65536))
        if (probe.size >= 4) {
            var evenNulls = 0
            var oddNulls = 0
            val pairs = probe.size / 2
            for (i in 0 until pairs) {
                if (probe[i * 2] == 0.toByte()) evenNulls++
                if (probe[i * 2 + 1] == 0.toByte()) oddNulls++
            }
            if (pairs > 0) {
                val evenRatio = evenNulls.toDouble() / pairs
                val oddRatio = oddNulls.toDouble() / pairs
                if (oddRatio > 0.3 && evenRatio < 0.05) {
                    return Result(decodeLenient(bytes, Charsets.UTF_16LE), "UTF-16LE", 0, uncertain = true)
                }
                if (evenRatio > 0.3 && oddRatio < 0.05) {
                    return Result(decodeLenient(bytes, Charsets.UTF_16BE), "UTF-16BE", 0, uncertain = true)
                }
            }
        }

        // 4) GB18030（GBK 的超集，覆盖全部中文 Windows 编码）
        val gb18030 = runCatching { Charset.forName("GB18030") }.getOrNull()
        if (gb18030 != null) {
            val text = decodeLenient(bytes, gb18030)
            val replacements = countReplacements(text)
            return Result(
                text = text,
                encoding = "GB18030",
                replacementCount = replacements,
                uncertain = replacements > 0 || readableRatio(text) < 0.5,
            )
        }

        // 5) 兜底
        val text = decodeLenient(bytes, StandardCharsets.UTF_8)
        return Result(text, "UTF-8", countReplacements(text), uncertain = true)
    }

    /** 用指定编码重新解码（界面上手工切换编码时调用）。 */
    fun decodeAs(bytes: ByteArray, encoding: String): Result {
        val charset = runCatching { Charset.forName(encoding) }.getOrDefault(StandardCharsets.UTF_8)
        // 跳过 BOM，避免 BOM 字符混入正文
        var slice = bytes
        if (encoding.equals("UTF-8", ignoreCase = true) &&
            bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            slice = bytes.copyOfRange(3, bytes.size)
        }
        val text = decodeLenient(slice, charset)
        val replacements = countReplacements(text)
        return Result(
            text = text,
            encoding = encoding,
            replacementCount = replacements,
            uncertain = replacements > 0 || readableRatio(text) < 0.5,
        )
    }

    /**
     * 给某个编码打可信度分数，用于界面上给出「更可能的编码」建议。
     * 依据是可读字符比例减去替换字符惩罚。
     */
    fun score(bytes: ByteArray, encoding: String): Double {
        val result = decodeAs(bytes, encoding)
        if (result.text.isEmpty()) return 1.0
        val penalty = result.replacementCount.toDouble() / result.text.length
        return (readableRatio(result.text) - penalty * 4).coerceAtLeast(0.0)
    }

    /** 归一化换行（CRLF / CR → LF）并去掉残留的 BOM 字符。 */
    fun normalize(raw: String): String {
        var text = raw
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }
}
