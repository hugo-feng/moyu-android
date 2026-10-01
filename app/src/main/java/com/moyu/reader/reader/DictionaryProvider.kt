package com.moyu.reader.reader

import com.moyu.reader.data.db.MoyuDatabase
import com.moyu.reader.data.db.UserDictionaryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * 查词（词典）。
 *
 * 设计取舍，与 Web 验证器一致：
 *   1. **不做自动分词**。中文没有词边界，分词算法在「长按选词」场景下
 *      给出的常常是用户没选的词，体验反而更差。这里只查用户**已选中的文本**。
 *   2. **内置表刻意精简**。离线内置一份高频字/词表，保证查词功能
 *      在任何设备、无网络时都能立刻给出有意义的结果；冷僻词如实提示「未收录」，
 *      并引导用户导入自定义词典 —— 这比假装自己是一部完整词典诚实也实用。
 *   3. 用户自定义词典以 JSON 存在一张表里，查询时载入内存。
 *      词典是只读的高频查询数据，放内存比每次查 SQLite 快得多。
 */
class DictionaryProvider(private val database: MoyuDatabase) {

    data class Sense(val pos: String?, val definition: String)

    data class Entry(
        val word: String,
        val phonetic: String?,
        val senses: List<Sense>,
        /** 释义来源，界面据此标注可信度 */
        val source: String,
    )

    data class LookupResult(
        val query: String,
        val entries: List<Entry>,
        val miss: Boolean,
    )

    /** 用户词典的内存缓存；首次查询时加载，导入/删除后失效。 */
    @Volatile
    private var cachedUserEntries: Map<String, Entry>? = null

    /** 内置常用字词（离线可用）。 */
    private val builtin: Map<String, Entry> = buildBuiltin()

    /** 内置表规模，供设置页展示。 */
    val builtinSize: Int get() = builtin.size

    suspend fun lookup(raw: String): LookupResult = withContext(Dispatchers.IO) {
        val query = normalize(raw)
        if (query.isEmpty()) return@withContext LookupResult("", emptyList(), true)

        val user = loadUserEntries()
        val entries = mutableListOf<Entry>()

        // 用户词典优先，便于覆盖内置释义
        user[query]?.let { entries.add(it) }
        if (entries.isEmpty()) builtin[query]?.let { entries.add(it) }

        // 单字查询时做前缀扩展：用户查一个字，往往是想了解它组成的词
        if (query.length == 1 && !isAscii(query)) {
            val prefix = mutableListOf<Entry>()
            user.entries.filter { it.key.length > 1 && it.key.startsWith(query) }
                .take(5)
                .forEach { prefix.add(it.value) }
            builtin.entries.filter { it.key.length > 1 && it.key.startsWith(query) }
                .take(5 - prefix.size)
                .forEach { prefix.add(it.value) }
            entries.addAll(prefix)
        }

        LookupResult(query, entries, entries.isEmpty())
    }

    /** 导入自定义词典（每行：词条<TAB>拼音<TAB>释义，或用空格分隔）。 */
    suspend fun importDictionary(name: String, text: String): Int = withContext(Dispatchers.IO) {
        val parsed = parseDictionaryText(text)
        if (parsed.isEmpty()) return@withContext 0

        val json = JSONObject().apply {
            parsed.forEach { (word, entry) ->
                put(
                    word,
                    JSONObject().apply {
                        entry.phonetic?.let { put("p", it) }
                        put("d", entry.senses.joinToString("；") { it.definition })
                    },
                )
            }
        }

        database.userDictionaryDao().upsert(
            UserDictionaryEntity(
                id = "d_" + UUID.randomUUID().toString().replace("-", "").take(12),
                name = name,
                entriesJson = json.toString(),
                entryCount = parsed.size,
                importedAt = System.currentTimeMillis(),
            )
        )
        invalidateCache()
        parsed.size
    }

    suspend fun listDictionaries(): List<Pair<String, Pair<String, Int>>> = withContext(Dispatchers.IO) {
        database.userDictionaryDao().getAll().map { it.id to (it.name to it.entryCount) }
    }

    suspend fun deleteDictionary(id: String) = withContext(Dispatchers.IO) {
        database.userDictionaryDao().deleteById(id)
        invalidateCache()
    }

    private fun invalidateCache() {
        cachedUserEntries = null
    }

    private suspend fun loadUserEntries(): Map<String, Entry> {
        cachedUserEntries?.let { return it }
        val result = mutableMapOf<String, Entry>()
        for (entity in database.userDictionaryDao().getAll()) {
            runCatching {
                val json = JSONObject(entity.entriesJson)
                json.keys().forEach { word ->
                    val obj = json.optJSONObject(word) ?: return@forEach
                    result[word] = Entry(
                        word = word,
                        phonetic = obj.optString("p").takeIf { it.isNotEmpty() },
                        senses = listOf(Sense(null, obj.optString("d"))),
                        source = "user",
                    )
                }
            }
        }
        cachedUserEntries = result
        return result
    }

    /** 规范化查询串：去空白与首尾标点，限制长度。 */
    fun normalize(raw: String): String =
        raw.replace(Regex("\\s+"), "")
            .trim { !it.isLetterOrDigit() }
            .take(MAX_QUERY_LENGTH)

    private fun isAscii(text: String): Boolean = text.all { it.code < 128 }

    /** 解析词典文本。格式宽容是必要的：本地词典文件格式非常混乱。 */
    private fun parseDictionaryText(text: String): Map<String, Entry> {
        val result = mutableMapOf<String, Entry>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

            val parts = line.split(Regex("[\\t ]+"))
            if (parts.size < 2) continue

            val word = parts[0]
            if (word.isEmpty()) continue

            // 形如 /fēng/ 的部分视为音标
            var index = 1
            var phonetic: String? = null
            if (index < parts.size && parts[index].startsWith("/") && parts[index].endsWith("/")) {
                phonetic = parts[index]
                index++
            }
            val definition = parts.drop(index).joinToString(" ").trim()
            if (definition.isEmpty()) continue

            result[word] = Entry(word, phonetic, listOf(Sense(null, definition)), "user")
        }
        return result
    }

    /**
     * 内置常用字词表。
     *
     * 刻意保持精简：目标是让查词在没有自定义词典时也能立刻可用，
     * 而不是假装收录了整部汉语词典（那会让 APK 膨胀几十兆）。
     */
    private fun buildBuiltin(): Map<String, Entry> {
        val map = mutableMapOf<String, Entry>()

        fun add(word: String, phonetic: String, pos: String?, definition: String) {
            map[word] = Entry(word, phonetic, listOf(Sense(pos, definition)), "builtin")
        }

        // —— 高频单字 ——
        add("的", "de", "助", "用在定语后，表示修饰关系。")
        add("一", "yī", "数", "最小的正整数；表示同一、全部或专一。")
        add("不", "bù", "副", "表示否定。")
        add("人", "rén", "名", "能制造和使用工具、进行社会活动的动物。")
        add("我", "wǒ", "代", "第一人称代词，说话人自己。")
        add("在", "zài", "动", "存在；居于某处。")
        add("有", "yǒu", "动", "表示存在、拥有。")
        add("这", "zhè", "代", "指示代词，指较近的人或事物。")
        add("来", "lái", "动", "由别处到此处。")
        add("说", "shuō", "动", "用话来表达意思。")
        add("时", "shí", "名", "时间；时机；时辰。")
        add("要", "yào", "动", "希望得到；需要。")
        add("就", "jiù", "副", "表示紧接着、随即或仅仅。")
        add("会", "huì", "动", "懂得；能够。")
        add("生", "shēng", "动", "生育；生长。")
        add("死", "sǐ", "动", "失去生命。")
        add("天", "tiān", "名", "天空；一昼夜；天气。")
        add("地", "dì", "名", "大地；处所。")
        add("心", "xīn", "名", "心脏；思想、感情。")
        add("道", "dào", "名", "路；道理；方法。")
        add("无", "wú", "动", "没有。")
        add("为", "wéi", "动", "做；当作。")
        add("大", "dà", "形", "在体积、数量等方面超过一般。")
        add("小", "xiǎo", "形", "在体积、数量等方面不及一般。")
        add("风", "fēng", "名", "空气的流动；风气、风俗。")
        add("雨", "yǔ", "名", "从云中降落的水滴。")
        add("剑", "jiàn", "名", "古代兵器，长条形，一端尖，两边有刃。")
        add("刀", "dāo", "名", "用来切、割、砍的工具或兵器。")
        add("灵", "líng", "名", "灵魂；精神。")
        add("气", "qì", "名", "气体；气息；精神状态。")
        add("力", "lì", "名", "力量；能力。")
        add("火", "huǒ", "名", "物体燃烧时产生的光和热。")
        add("水", "shuǐ", "名", "无色无味的液体，生命必需。")
        add("山", "shān", "名", "地面上高起的部分。")
        add("城", "chéng", "名", "城市；城墙。")
        add("国", "guó", "名", "国家。")
        add("王", "wáng", "名", "君主；同类中最强者。")
        add("帝", "dì", "名", "君主；皇帝。")
        add("仙", "xiān", "名", "神话中长生不死、有神通的人。")
        add("魔", "mó", "名", "神话中的鬼怪；邪恶力量。")
        add("龙", "lóng", "名", "传说中的神异动物；帝王的象征。")
        add("爱", "ài", "动", "对人或事物有深挚的感情。")
        add("恨", "hèn", "动", "仇视；怨恨。")
        add("笑", "xiào", "动", "露出愉快的表情，发出欢喜的声音。")
        add("哭", "kū", "动", "因悲哀或激动而流泪出声。")
        add("走", "zǒu", "动", "行走；离开。")
        add("看", "kàn", "动", "使视线接触人或物。")
        add("听", "tīng", "动", "用耳朵接受声音。")
        add("想", "xiǎng", "动", "思考；希望；怀念。")
        add("知", "zhī", "动", "知道；了解。")

        // —— 常用双字词与成语 ——
        add("世界", "shì jiè", "名", "时间和空间的总和；地球上人类社会的总体。")
        add("江湖", "jiāng hú", "名", "江河湖泊；泛指四方各地。在武侠语境中指侠客与武林人士活动的社会圈。")
        add("修炼", "xiū liàn", "动", "修行练功，多指道家、佛家或仙侠设定中提升境界的过程。")
        add("境界", "jìng jiè", "名", "土地的界限；事物所达到的程度或表现的情况。")
        add("法术", "fǎ shù", "名", "方术、幻术；神话中超自然的手段。")
        add("灵气", "líng qì", "名", "仙侠设定中天地间可供修炼的超凡能量。")
        add("元气", "yuán qì", "名", "中国哲学中指构成万物的原始物质；也指人的精神、生命力。")
        add("师兄", "shī xiōng", "名", "同门中拜师较早或年长的男性。")
        add("不可思议", "bù kě sī yì", "成语", "原有神秘奥妙的意思，现多指无法想象、难以理解。")
        add("莫名其妙", "mò míng qí miào", "成语", "说不出其中的奥妙，形容事情很奇怪、使人不明白。")
        add("恍然大悟", "huǎng rán dà wù", "成语", "一下子完全明白过来。")
        add("一鸣惊人", "yī míng jīng rén", "成语", "比喻平时没有突出表现，一下子做出惊人的成绩。")

        // —— 常见英文 ——
        add("the", "/ðə/", "art.", "定冠词，表示特指。")
        add("and", "/ænd/", "conj.", "和；并且。")
        add("chapter", "/ˈtʃæptə(r)/", "n.", "章；回；时期。")
        add("book", "/bʊk/", "n.", "书；卷。")
        add("read", "/riːd/", "v.", "阅读；朗读。")
        add("night", "/naɪt/", "n.", "夜晚。")
        add("sword", "/sɔːd/", "n.", "剑；刀。")
        add("world", "/wɜːld/", "n.", "世界；世人。")

        return map
    }

    companion object {
        const val MAX_QUERY_LENGTH = 24
    }
}
