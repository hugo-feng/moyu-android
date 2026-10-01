package com.moyu.reader.storage

import com.moyu.reader.data.model.ImportResult
import com.moyu.reader.data.repository.BookRepository

/**
 * 内置示例书。
 *
 * 存在的意义：用户第一次打开应用时书架是空的，而「导入本地文件」需要先有文件。
 * 提供一本原创的示例书能让人立刻体验完整的阅读流程（分章、翻页、目录、书签、统计），
 * 这也是主流阅读 App 的通行做法。
 *
 * 内容刻意**混用多种章节标题格式**（第一章 / 第4章 / 第 九 章 / 楔子 / 番外），
 * 这样示例书本身就顺带验证了分章算法的兼容性 —— 如果分章出问题，一眼就能看出来。
 */
object SampleBook {

    const val TITLE = "剑影长歌"
    const val AUTHOR = "墨阅示例"

    /** 通过仓储导入示例书，走与真实导入完全相同的路径（避免出现「示例书走特例逻辑」的隐患）。 */
    suspend fun importInto(repo: BookRepository): ImportResult {
        val text = buildText()
        return repo.importFromText(
            fileName = "$TITLE.txt",
            text = text,
            encoding = "UTF-8",
        )
    }

    /** 生成示例文本。 */
    fun buildText(): String = buildString {
        append("《").append(TITLE).append("》\n")
        append("作者：").append(AUTHOR).append('\n')
        append("简介：一个少年从雪夜山道出发，走过剑冢与听雨楼，最终把名字留在江湖上的故事。")
        append("本示例文本由墨阅项目原创，仅用于功能演示。\n\n")

        val chapters = listOf(
            "楔子 雪夜" to 6,
            "第一章 初入江湖" to 9,
            "第二章 血雨腥风" to 8,
            "第三章 剑冢" to 10,
            "第4章 夜探听雨楼" to 7,
            "第五章 故人之约" to 9,
            "第六章 一线生机" to 8,
            "第七章 落子无悔" to 11,
            "第八章 长歌当哭" to 7,
            "第 九 章 归途" to 10,
            "第十章 灯下人" to 8,
            "第十一章 千金一诺" to 9,
            "第十二章 山雨欲来" to 12,
            "第十三章 剑影长歌" to 10,
            "番外 那年春深" to 6,
        )

        chapters.forEachIndexed { chapterIndex, (title, paragraphs) ->
            append(title).append('\n')
            repeat(paragraphs) { i ->
                append(paragraph("$title-$chapterIndex-$i"))
            }
            append('\n')
        }
    }

    /** 从文本导入（供示例书与「粘贴文本」功能共用）。 */
    private val SENTENCES = listOf(
        "山道上积雪未消，脚踩下去发出细碎的声响。",
        "他握紧了剑柄，指节因用力而泛白。",
        "远处传来一声悠长的钟鸣，惊起满林寒鸦。",
        "风从峡谷深处卷上来，带着铁锈与松脂的气味。",
        "她没有回头，只把斗篷的兜帽压低了些。",
        "灯火在窗纸上晃出一个模糊的人影。",
        "茶已经凉透，杯底沉着一层暗褐色的叶屑。",
        "马蹄声由远及近，又在门前骤然停住。",
        "刀锋映着月光，像一线流动的水银。",
        "他忽然笑了一声，那笑意却没有到达眼底。",
        "老人把一卷泛黄的帛书推到桌案中央。",
        "雨点开始敲打屋檐，先是零星，继而连成一片。",
        "她袖中的短匕已经滑出半寸。",
        "少年抬起头，眼里的惊惶已经褪尽。",
        "石门在身后缓缓合拢，隔绝了最后一线天光。",
    )

    /** 生成一段有辨识度的正文（不同章节内容不同，便于验证搜索与跳转）。 */
    private fun paragraph(seed: String): String {
        val builder = StringBuilder()
        val count = 3 + (seed.length % 4)
        repeat(count) { i ->
            builder.append(SENTENCES[(seed.length + i * 7) % SENTENCES.size])
            if (i % 3 == 2) {
                builder.append("第 ${i + 1} 次交手之后，「${seed.substringBefore('-')}」这个名字开始在江湖上被人低声提起，带着几分忌惮，也带着几分说不清的期待。")
            }
        }
        builder.append('\n')
        return builder.toString()
    }
}
