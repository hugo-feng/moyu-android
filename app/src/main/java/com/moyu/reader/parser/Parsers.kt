package com.moyu.reader.parser

import com.moyu.reader.reader.ChapterSplitter
import com.moyu.reader.reader.TextEncodingDetector

/**
 * 解析结果（解析层与存储层之间的契约）。
 *
 * 解析器只负责「字节 → 书名/作者/章节」，不碰数据库、不碰界面。
 * 这样每个解析器都能被纯单元测试覆盖，也便于将来加格式（如 MOBI）而不动仓储。
 */
data class ParsedBook(
    val title: String,
    val author: String,
    val intro: String,
    /** 采用的编码名（EPUB 恒为 UTF-8） */
    val encoding: String,
    /** 编码识别是否不确定：界面应提示用户可手动切换 */
    val encodingUncertain: Boolean,
    val chapters: List<ParsedChapter>,
    /** 封面图片字节（EPUB 内嵌封面）；无封面为 null */
    val coverBytes: ByteArray? = null,
    /** 非致命告警，用于界面提示 */
    val warnings: List<String> = emptyList(),
) {
    // ByteArray 让 data class 的 equals/hashCode 退化为引用比较，
    // 这在把 ParsedBook 当状态比较时会产生「内容相同却不相等」的意外。
    // 解析结果只用于一次性传递，不参与相等性判断，因此显式覆写为按内容比较。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedBook) return false
        return title == other.title &&
            author == other.author &&
            intro == other.intro &&
            encoding == other.encoding &&
            encodingUncertain == other.encodingUncertain &&
            chapters == other.chapters &&
            warnings == other.warnings &&
            (coverBytes?.contentEquals(other.coverBytes ?: ByteArray(0)) ?: (other.coverBytes == null))
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + intro.hashCode()
        result = 31 * result + encoding.hashCode()
        result = 31 * result + encodingUncertain.hashCode()
        result = 31 * result + chapters.hashCode()
        result = 31 * result + warnings.hashCode()
        result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class ParsedChapter(
    val index: Int,
    val title: String,
    val content: String,
    val start: Int,
    val length: Int,
    val detected: Boolean,
)

/**
 * TXT 解析器。
 *
 * 流程：字节 → 编码识别 → 换行归一化 → 分章。
 * 每个环节都复用被单元测试覆盖过的引擎（TextEncodingDetector / ChapterSplitter），
 * 这里只做编排。
 */
object TxtParser {

    fun parse(bytes: ByteArray, fileName: String): ParsedBook {
        val decoded = TextEncodingDetector.detectAndDecode(bytes)
        val text = TextEncodingDetector.normalize(decoded.text)

        if (text.isBlank()) {
            throw IllegalArgumentException("文件内容为空")
        }

        val rawChapters = ChapterSplitter.split(text)
        val chapters = rawChapters.map {
            ParsedChapter(
                index = it.index,
                title = it.title,
                content = it.content,
                start = it.start,
                length = it.length,
                detected = it.detected,
            )
        }

        val warnings = buildList {
            if (decoded.uncertain) {
                add("编码识别可能不准确（判定为 ${decoded.encoding}），可在导入后手动切换")
            }
            if (rawChapters.none { it.detected }) {
                add("未识别到章节标题，已按每 ${ChapterSplitter.DEFAULT_FALLBACK_CHUNK_SIZE} 字分块")
            }
        }

        return ParsedBook(
            title = guessTitle(text, fileName),
            author = guessAuthor(text),
            intro = "",
            encoding = decoded.encoding,
            encodingUncertain = decoded.uncertain,
            chapters = chapters,
            warnings = warnings,
        )
    }

    /**
     * 猜书名：优先取正文开头的「《书名》」，否则用文件名。
     * 只在文件开头 300 字内找，避免把正文里的书名号误当书名。
     */
    private fun guessTitle(text: String, fileName: String): String {
        val head = text.take(300)
        Regex("《([^》]{1,40})》").find(head)?.let { return it.groupValues[1].trim() }
        val firstLine = head.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.length in 1..40 && Regex("书名|作者").containsMatchIn(firstLine)) {
            return firstLine.replace(Regex("^(书名|作\\s*者)\\s*[:：]\\s*"), "").trim().ifEmpty { fallbackTitle(fileName) }
        }
        return fallbackTitle(fileName)
    }

    /** 从「作者：xxx」提取作者，找不到则标为佚名。 */
    private fun guessAuthor(text: String): String {
        val head = text.take(1000)
        return Regex("作\\s*者\\s*[:：]\\s*(\\S{1,20})").find(head)?.groupValues?.get(1)?.trim() ?: "佚名"
    }

    private fun fallbackTitle(fileName: String): String =
        fileName.substringBeforeLast('.').trim().ifEmpty { "未命名" }
}
