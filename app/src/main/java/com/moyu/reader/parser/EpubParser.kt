package com.moyu.reader.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * EPUB 解析器。
 *
 * 实现依赖的选择说明（这是刻意的工程取舍）：
 *
 *   Web 验证器用 epubjs —— 浏览器端没有解压能力，必须引库；
 *   而 Android 平台**自带** `java.util.zip`，HTML/XML 解析则由已有的 jsoup 负责。
 *   因此这里不再引入第三方 EPUB 库：EPUB 的核心规范（container.xml → OPF →
 *   manifest/spine → NCX/nav）本身就是「ZIP + 受控 XML」，自己实现约 200 行，
 *   比再引入一个依赖更可控，也少一份版本与体积累担。
 *
 * 章节划分以 spine 顺序为准（这是规范定义的阅读顺序），
 * 并把各章正文顺序拼接后计算 `start` 偏移 —— 于是 EPUB 与 TXT 共用
 * 同一套「全局字符偏移」坐标系，进度/书签/搜索无需分叉。
 */
object EpubParser {

    private const val CONTAINER_PATH = "META-INF/container.xml"

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    fun parse(bytes: ByteArray, fileName: String): ParsedBook {
        val entries = unzip(bytes)
        if (entries.isEmpty()) throw IllegalArgumentException("这个文件不是有效的 EPUB（无法解包）")

        // —— 1) container.xml → OPF 路径 ——
        val containerBytes = entries[CONTAINER_PATH]
            ?: throw IllegalArgumentException("不是有效的 EPUB：缺少 META-INF/container.xml")
        val containerDoc = parseXml(containerBytes)
        val opfPath = containerDoc.selectFirst("rootfile")?.attr("full-path")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("不是有效的 EPUB：container.xml 中找不到 rootfile")

        val opfBytes = entries[opfPath]
            ?: throw IllegalArgumentException("EPUB 包文件缺失：$opfPath")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = parseXml(opfBytes)

        // —— 2) metadata ——
        val title = opf.selectFirst("metadata > title, dc|title")?.text()?.trim()
            .orEmpty()
            .ifEmpty { fileName.substringBeforeLast('.').trim().ifEmpty { "未命名" } }
        val author = opf.selectFirst("metadata > creator, dc|creator")?.text()?.trim()
            .orEmpty()
            .ifEmpty { "佚名" }
        val intro = opf.selectFirst("metadata > description, dc|description")?.text()?.trim().orEmpty()

        // —— 3) manifest ——
        val manifest = mutableMapOf<String, ManifestItem>()
        opf.select("manifest > item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotBlank() && href.isNotBlank()) {
                manifest[id] = ManifestItem(
                    id = id,
                    href = resolvePath(opfDir, href),
                    mediaType = item.attr("media-type"),
                    properties = item.attr("properties"),
                )
            }
        }

        // —— 4) spine（阅读顺序）——
        val spineIds = opf.select("spine > itemref").map { it.attr("idref") }.filter { it.isNotBlank() }
        if (spineIds.isEmpty()) throw IllegalArgumentException("EPUB 的 spine 为空，没有可读内容")

        // —— 5) 封面 ——
        var coverBytes: ByteArray? = null
        val coverItem = manifest.values.firstOrNull { it.properties.split(' ').contains("cover-image") }
            ?: opf.selectFirst("metadata > meta[name=cover]")?.attr("content")?.let { manifest[it] }
        if (coverItem != null) {
            coverBytes = entries[coverItem.href]
        }

        // —— 6) 按 spine 生成章节 ——
        val chapters = mutableListOf<ParsedChapter>()
        val pathToIndex = mutableMapOf<String, Int>()
        var globalOffset = 0

        for (id in spineIds) {
            val item = manifest[id] ?: continue
            if (!item.mediaType.contains("html", ignoreCase = true) &&
                !item.href.endsWith(".xhtml", true) &&
                !item.href.endsWith(".html", true)
            ) {
                continue
            }
            val raw = entries[item.href] ?: continue
            val html = String(raw, Charsets.UTF_8)
            val doc = Jsoup.parse(html)

            val text = htmlToPlainText(doc)
            if (text.isEmpty()) {
                pathToIndex[item.href] = (chapters.size - 1).coerceAtLeast(0)
                continue
            }

            var chapterTitle = doc.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty()
            if (chapterTitle.isEmpty()) chapterTitle = doc.title().trim()

            // 剥掉正文开头与标题重复的那一行：
            // 阅读器会把 title 单独渲染成标题，若正文也含标题就会重复显示两遍。
            val body = stripLeadingHeading(text, chapterTitle)

            val index = chapters.size
            chapters.add(
                ParsedChapter(
                    index = index,
                    title = chapterTitle.ifEmpty { "第 ${index + 1} 节" },
                    content = body,
                    start = globalOffset,
                    length = body.length,
                    detected = true,
                )
            )
            globalOffset += body.length
            pathToIndex[item.href] = index
        }

        if (chapters.isEmpty()) {
            throw IllegalArgumentException("这个 EPUB 里没有可读的正文内容（可能只有图片或受 DRM 保护）")
        }

        val warnings = buildList {
            if (opf.select("spine > itemref").isEmpty()) add("EPUB 的 spine 为空，已按文件顺序生成章节")
        }

        return ParsedBook(
            title = title,
            author = author,
            intro = intro,
            encoding = "UTF-8",
            encodingUncertain = false,
            chapters = chapters,
            coverBytes = coverBytes,
            warnings = warnings,
        )
    }

    /** 解析应用内私有的 epub 加密标记（粗略探测 DRM，给出更明确的提示）。 */
    fun looksEncrypted(bytes: ByteArray): Boolean {
        val entries = runCatching { unzip(bytes) }.getOrNull() ?: return false
        return entries.containsKey("META-INF/encryption.xml")
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /** 解包 ZIP。跳过 macOS 打包残留，避免污染 manifest。 */
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory &&
                    !name.startsWith("__MACOSX/") &&
                    !name.endsWith(".DS_Store")
                ) {
                    result[name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun parseXml(bytes: ByteArray): Document =
        Jsoup.parse(String(bytes, Charsets.UTF_8), "", Parser.xmlParser())

    /**
     * 归一化 ZIP 内路径。
     * 处理 `../`、`./`、开头的 `/`，以及百分号编码 —— EPUB 里这几种写法都真实存在。
     */
    private fun resolvePath(baseDir: String, href: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        val cleanHref = decoded.substringBefore('#').substringBefore('?')
        val combined = if (cleanHref.startsWith("/")) cleanHref.substring(1) else "$baseDir$cleanHref"

        val out = ArrayDeque<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(segment)
            }
        }
        return out.joinToString("/")
    }

    /**
     * XHTML → 纯文本。
     *
     * 用 jsoup 走真实 DOM，因此 script/style 不会被混进正文（正则方案最常翻车的地方）。
     * 块级元素之间插入换行以保留段落结构。
     */
    private fun htmlToPlainText(doc: Document): String {
        val body = doc.body() ?: return ""
        val builder = StringBuilder()

        fun walk(element: Element) {
            for (child in element.childNodes()) {
                when (child) {
                    is org.jsoup.nodes.TextNode -> builder.append(child.text())
                    is Element -> {
                        val tag = child.tagName().lowercase()
                        if (tag in SKIP_TAGS) continue
                        when (tag) {
                            "br" -> builder.append('\n')
                            "hr" -> builder.append('\n')
                            "img" -> {
                                val alt = child.attr("alt")
                                builder.append(if (alt.isNotBlank()) "［图：$alt］" else "［图片］")
                            }
                            else -> {
                                val block = tag in BLOCK_TAGS
                                if (block) builder.append('\n')
                                walk(child)
                                if (block) builder.append('\n')
                            }
                        }
                    }
                }
            }
        }

        walk(body)

        return builder.toString()
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t\\x0B\\f]+"), " ")
            // 逐行 trim：必须放在「全角空格转普通空格」之后，
            // 否则行首的全角空格会残留，与阅读器的首行缩进叠加成双倍缩进。
            .lineSequence()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * 剥掉正文开头与标题重复的那一行。
     *
     * 判定保守：只在**标题是首行的前缀**时才剥离。
     * 反过来不成立（首行只是标题的一小段时视为正文），
     * 因为误删正文的代价远大于偶尔留下一个重复标题。
     */
    private fun stripLeadingHeading(text: String, title: String): String {
        val normalizedTitle = title.replace(Regex("[\\s\\u3000]+"), "")
        if (normalizedTitle.isEmpty() || text.isEmpty()) return text

        val newlineIndex = text.indexOf('\n')
        val firstLine = (if (newlineIndex == -1) text else text.substring(0, newlineIndex))
            .replace(Regex("[\\s\\u3000]+"), "")
        if (firstLine.isEmpty()) return text
        if (!firstLine.startsWith(normalizedTitle)) return text

        if (newlineIndex == -1) return ""
        return text.substring(newlineIndex + 1).trimStart('\n')
    }

    private val SKIP_TAGS = setOf("script", "style", "head", "title", "meta", "link")

    private val BLOCK_TAGS = setOf(
        "address", "article", "aside", "blockquote", "div", "dl", "dd", "dt",
        "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3",
        "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre",
        "section", "table", "tr", "ul",
    )
}
