package com.moyu.reader.reader

/**
 * 章节序号标签。
 *
 * 阅读页的天头书眉与「即将翻走的那一页」的快照都要用它，
 * 因此提取到 reader 包而不是留在 UI 层 —— 两处各写一份必然会漂移，
 * 而漂移的表现是「翻页动画里显示第 3 章，翻完变成第 4 章」。
 *
 * 规则：
 *   - 楔子 / 序章 / 番外 这类特殊篇名不参与编号，统一显示「篇外」。
 *     给它们编号会造成「序章是第 1 章、第一章变成第 2 章」的错位。
 *   - 标题里已经写明「第 N 章」的，不重复编号，显示「正文」。
 *   - 其余按顺序编号。
 */
object ChapterLabels {

    /** 特殊篇名：这些不编号。 */
    private val SPECIAL = Regex(
        "^(楔子|序章|序言|自序|前言|引子|引言|尾声|终章|完结章|后记|附录|外传|作者的话|作品相关|设定|人物介绍|番外)",
    )

    /** 标题里已含中文章节号。 */
    private val CN_NUMBERED = Regex(
        "第\\s*[0-9零一二三四五六七八九十百千万两〇]+\\s*[章节回卷节篇部集话]",
    )

    /** 标题里已含英文章节号。 */
    private val EN_NUMBERED = Regex("^(chapter|chap\\.?|part)\\b", RegexOption.IGNORE_CASE)

    fun labelFor(title: String, index: Int): String {
        val trimmed = title.trim()
        if (SPECIAL.containsMatchIn(trimmed)) return "篇外"
        if (CN_NUMBERED.containsMatchIn(trimmed)) return "正文"
        if (EN_NUMBERED.containsMatchIn(trimmed)) return "正文"
        return "第 ${index + 1} 章"
    }
}
