package com.windy.pyime

import java.io.File

/**
 * 拼音词库 + 候选算法,等价移植自 PC 版 pyime.py 的 Dict 类。
 * 分词、模糊音、简拼、选词调权(bump)、调权写回文件,逻辑与 Python 版保持一致。
 *
 * 构造参数 raw 是 yaml 文件的完整文本(rime 格式:yaml 头 + "..." 分界 + 制表符分隔的词条体)。
 */
class PinyinDict(raw: String) {

    /** 一个词条(词 + 权重)。weight 可变,选词调权时就地修改;table 与 abbr 共享同一实例,改一处两边同步。 */
    class WordWeight(val word: String, var weight: Int)

    /** 返回给 UI 的候选:词 + 消耗的音节数。 */
    data class Candidate(val word: String, val nseg: Int)

    private val table = HashMap<String, MutableList<WordWeight>>()   // "ni hao" -> 按权重降序
    private val syllables = HashSet<String>()                        // 所有合法音节
    private val fuzzy = HashMap<String, List<String>>()              // 音节 -> 等价音节(不含自身)
    private val letterFuzzy = HashMap<Char, List<Char>>()            // 简拼声母字母 -> 模糊等价字母(不含自身)
    private val abbr = HashMap<String, MutableList<WordWeight>>()    // 声母串 -> 候选(共享 table 的实例)
    // 末字简拼索引:(前缀全拼, 末尾声母串) -> 候选(共享 table 的实例)。
    // 如 设计 she ji -> ("she","j");计算机 ji suan ji -> ("ji suan","j")、("ji","sj")
    private val partAbbr = HashMap<Pair<String, String>, MutableList<WordWeight>>()
    // 单字母索引:拼音首字母 -> 候选(共享 table 的实例)。
    // 用 py[0] 归桶,所以 lao shi=老师 也归在 'l' 下,zh/ch/sh 归在 z/c/s 下。
    private val initialIdx = HashMap<Char, MutableList<WordWeight>>()
    private var maxsyl = 1
    private val initSubs = ArrayList<Pair<String, String>>()         // 声母替换对

    init {
        // ---- 解析词条体 ----
        val idx = raw.indexOf("\n...")
        val body = if (idx >= 0) raw.substring(idx + 4) else raw
        for (line in body.split("\n")) {
            val parts = line.split("\t")
            if (parts.size < 2 || parts[0].isEmpty() || parts[0].startsWith("#")) continue
            val word = parts[0]
            val py = parts[1].trim()
            if (py.isEmpty()) continue
            val weight = if (parts.size > 2) parts[2].trim().toIntOrNull() ?: 0 else 0
            table.getOrPut(py) { ArrayList() }.add(WordWeight(word, weight))
            for (s in py.split(" ")) syllables.add(s)
        }
        for (v in table.values) v.sortByDescending { it.weight }
        maxsyl = syllables.maxOfOrNull { it.length } ?: 1

        // ---- 模糊音:声母替换对 + 韵母替换对,构建每个音节的等价闭包 ----
        val finalSubs = ArrayList<Pair<String, String>>()
        for ((a, b) in FUZZY_PAIRS) {
            for ((x, y) in listOf(a to b, b to a)) {
                if (x in INITIALS_1 || x in INITIALS_2) initSubs.add(x to y)
                else finalSubs.add(x to y)
            }
        }
        // 模糊音闭包:不再只保留词库里已存在的等价音节,生成出来的「虚拟音节」
        // (如 ting→tin、ding→din)也一并纳入。这样 segment 能把 tinz 切成 tin+z,
        // 再经模糊键 tin→ting 命中「停止」等词;否则 tin 不被识别会被切成 ti'n'z。
        val virtual = HashSet<String>()   // 词库里不存在、仅由模糊音生成的音节
        for (s in syllables.toList()) {
            val group = HashSet<String>().apply { add(s) }
            val todo = ArrayDeque<String>().apply { add(s) }
            while (todo.isNotEmpty()) {
                val cur = todo.removeLast()
                val vs = ArrayList<String>()
                for ((x, y) in initSubs) if (initialOf(cur) == x) vs.add(y + cur.substring(x.length))
                for ((x, y) in finalSubs) if (cur.endsWith(x)) vs.add(cur.substring(0, cur.length - x.length) + y)
                for (v in vs) if (v.isNotEmpty() && v !in group) { group.add(v); todo.add(v) }
            }
            // 闭包是等价类,类内每个成员(含虚拟音节)都登记一份模糊映射
            for (member in group) {
                if (member !in syllables) virtual.add(member)
                if (group.size > 1) fuzzy[member] = (group - member).sorted()
            }
        }
        syllables.addAll(virtual)
        if (virtual.isNotEmpty()) maxsyl = maxOf(maxsyl, virtual.maxOf { it.length })

        // 简拼字母模糊闭包:只取 FUZZY_PAIRS 里两端都是单字母声母的对(如 l/r、n/l,
        // z/zh、s/sh 这类因为简拼本就取首字符,两端已经是同一个字母,无需再处理),
        // 构成字母级等价闭包,供简拼(snt/srt/slt 应等价命中"水龙头")按字母展开查找用。
        val letterPairs = FUZZY_PAIRS.filter { (a, b) -> a.length == 1 && b.length == 1 }
        val letterAlphabet = HashSet<Char>()
        for ((a, b) in letterPairs) { letterAlphabet.add(a[0]); letterAlphabet.add(b[0]) }
        for (c in letterAlphabet) {
            val group = HashSet<Char>().apply { add(c) }
            val todo = ArrayDeque<Char>().apply { add(c) }
            while (todo.isNotEmpty()) {
                val cur = todo.removeLast()
                for ((a, b) in letterPairs) for ((x, y) in listOf(a[0] to b[0], b[0] to a[0])) {
                    if (cur == x && y !in group) { group.add(y); todo.add(y) }
                }
            }
            if (group.size > 1) letterFuzzy[c] = (group - c).sorted()
        }

        // ---- 简拼索引:每音节取首字母,按权重降序、截断 ----
        if (ENABLE_SHOUPIN) {
            for ((py, words) in table) {
                val sylls = py.split(" ")
                if (sylls.size < 2) continue   // 单字简拼太歧义,不收
                val key = sylls.joinToString("") { it[0].toString() }
                abbr.getOrPut(key) { ArrayList() }.addAll(words)   // 共享 WordWeight 实例
            }
            for (v in abbr.values) {
                v.sortByDescending { it.weight }
                if (v.size > MAX_SHOUPIN_PER_KEY) v.subList(MAX_SHOUPIN_PER_KEY, v.size).clear()
            }
        }

        // ---- 单字母索引:拼音首字母 -> 候选,按权重降序(不截断,全部保留)----
        if (ENABLE_SINGLE_INITIAL) {
            for ((py, words) in table) {
                val c = py[0]
                if (c in ABBR_LETTERS) initialIdx.getOrPut(c) { ArrayList() }.addAll(words)   // 共享 WordWeight 实例
            }
            for (v in initialIdx.values) {
                v.sortByDescending { it.weight }
                if (v.size > MAX_SINGLE_INITIAL_PER_KEY) v.subList(MAX_SINGLE_INITIAL_PER_KEY, v.size).clear()
            }
        }

        // ---- 末字简拼索引:前 p 个字全拼 + 末尾 n-p 个字只打声母,按权重降序、截断 ----
        if (ENABLE_TAIL_INITIAL) {
            for ((py, words) in table) {
                val sylls = py.split(" ")
                val n = sylls.size
                if (n < 2) continue
                for (p in 1 until n) {   // 前 p 个全拼,后 n-p 个取声母
                    val inits = sylls.subList(p, n).joinToString("") { it[0].toString() }
                    // 末尾有零声母音节(a/e/o 开头)时不能用声母简拼
                    if (!inits.all { it.toString() in INITIALS_1 }) continue
                    val key = sylls.subList(0, p).joinToString(" ") to inits
                    partAbbr.getOrPut(key) { ArrayList() }.addAll(words)   // 共享 WordWeight 实例
                }
            }
            for (v in partAbbr.values) {
                v.sortByDescending { it.weight }
                if (v.size > MAX_TAIL_INITIAL_PER_KEY) v.subList(MAX_TAIL_INITIAL_PER_KEY, v.size).clear()
            }
        }
    }

    /** 取音节(或不完整音节)的声母,无声母返回空串。 */
    private fun initialOf(s: String): String {
        if (s.length >= 2 && s.substring(0, 2) in INITIALS_2) return s.substring(0, 2)
        return if (s.isNotEmpty() && s[0].toString() in INITIALS_1) s[0].toString() else ""
    }

    /**
     * 把字母串切分成音节列表;优先让整串都被合法音节覆盖(全局匹配),
     * 如 kangu 切成 kan+gu(看顾)而非贪心的 kang+u(看)。无法整串覆盖时
     * 退回贪心最长匹配、切不动的字母单独成段。
     */
    fun segment(buf: String): List<String> {
        val n = buf.length
        // can[i]:buf[i..] 能否被合法音节完整覆盖(' 视为透明的音节边界)
        val can = BooleanArray(n + 1)
        can[n] = true
        for (i in n - 1 downTo 0) {
            if (buf[i] == '\'') { can[i] = can[i + 1]; continue }
            val maxL = minOf(maxsyl, n - i)
            for (L in 1..maxL) {
                if (can[i + L] && buf.substring(i, i + L) in syllables) { can[i] = true; break }
            }
        }
        val segs = ArrayList<String>()
        var i = 0
        if (can[0]) {
            // 整串可完整切分:仍最长优先,但只选不会让剩余部分无解的音节
            while (i < n) {
                if (buf[i] == '\'') { i++; continue }
                val maxL = minOf(maxsyl, n - i)
                for (L in maxL downTo 1) {
                    if (can[i + L] && buf.substring(i, i + L) in syllables) {
                        segs.add(buf.substring(i, i + L)); i += L; break
                    }
                }
            }
            return segs
        }
        // 无法整串覆盖:退回原贪心(切不动的字母单独成段)
        while (i < n) {
            if (buf[i] == '\'') { i++; continue }
            var matched = false
            val maxL = minOf(maxsyl, n - i)
            for (L in maxL downTo 1) {
                val s = buf.substring(i, i + L)
                if (s in syllables) { segs.add(s); i += L; matched = true; break }
            }
            if (!matched) { segs.add(buf[i].toString()); i++ }
        }
        return segs
    }

    /** 完整音节列表 sub 的所有模糊音组合键,第一个固定是原拼音。 */
    fun fuzzyKeys(sub: List<String>): List<String> {
        var combos = listOf("")
        for (s in sub) {
            val vs = listOf(s) + (fuzzy[s] ?: emptyList())
            val next = ArrayList<String>(combos.size * vs.size)
            for (c in combos) for (v in vs) next.add(if (c.isEmpty()) v else "$c $v")
            combos = if (next.size > MAX_FUZZY_KEYS) next.subList(0, MAX_FUZZY_KEYS) else next
        }
        return combos
    }

    /** 简拼字母串 letters 的所有模糊音字母组合键,第一个固定是原字母串。 */
    fun abbrKeys(letters: String): List<String> {
        var combos = listOf("")
        for (ch in letters) {
            val vs = listOf(ch) + (letterFuzzy[ch] ?: emptyList())
            val next = ArrayList<String>(combos.size * vs.size)
            for (c in combos) for (v in vs) next.add(c + v)
            combos = if (next.size > MAX_FUZZY_KEYS) next.subList(0, MAX_FUZZY_KEYS) else next
        }
        return combos
    }

    /**
     * 枚举字母串的所有「整串音节切分」方案,按贪心偏好排序(音节数少优先、靠前音节长优先;
     * 首个即与 segment 的贪心结果一致)。无法整串覆盖时返回空表。
     * 用于首选切分查不到候选时,回退到另一种有候选的切分(如 cuangai 的 cuang ai
     * 无候选时改用 cuan gai)。
     */
    private fun fullSegs(letters: String, cap: Int = 24): List<List<String>> {
        val n = letters.length
        val can = BooleanArray(n + 1)   // can[i]:letters[i..] 能否被合法音节完整覆盖
        can[n] = true
        for (i in n - 1 downTo 0) {
            val maxL = minOf(maxsyl, n - i)
            for (L in 1..maxL) {
                if (can[i + L] && letters.substring(i, i + L) in syllables) { can[i] = true; break }
            }
        }
        if (!can[0]) return emptyList()
        val results = ArrayList<List<String>>()
        fun dfs(i: Int, acc: ArrayList<String>) {
            if (results.size >= cap) return
            if (i == n) { results.add(ArrayList(acc)); return }
            val maxL = minOf(maxsyl, n - i)
            for (L in maxL downTo 1) {   // 最长优先
                if (can[i + L] && letters.substring(i, i + L) in syllables) {
                    acc.add(letters.substring(i, i + L))
                    dfs(i + L, acc)
                    acc.removeAt(acc.size - 1)
                }
            }
        }
        dfs(0, ArrayList())
        // 排序键:音节数少优先;数量相同时按各音节长度逐位比较,靠前音节长的优先
        val cmp = Comparator<List<String>> { a, b ->
            if (a.size != b.size) return@Comparator a.size - b.size
            for (i in a.indices) {
                val d = b[i].length - a[i].length
                if (d != 0) return@Comparator d
            }
            0
        }
        return results.sortedWith(cmp)
    }

    /** 该音节切分整串(精确或模糊键)是否能查到候选词。 */
    private fun segHasCands(sub: List<String>): Boolean =
        fuzzyKeys(sub).any { table[it]?.isNotEmpty() == true }

    /**
     * 优先用 segment 的贪心切分;若它能整串覆盖却查不到任何候选,则在其它整串切分里
     * 挑第一个有候选的(实现「组合无候选时自动换有候选的组合」,如 cuangai 改用 cuan gai)。
     */
    fun bestSegment(buf: String): List<String> {
        val primary = segment(buf)
        if (primary.isEmpty() || !primary.all { it in syllables }) return primary  // 无切分或非整串覆盖:保持原贪心行为
        if (segHasCands(primary)) return primary
        for (ss in fullSegs(buf.replace("'", ""))) {
            if (ss != primary && segHasCands(ss)) return ss
        }
        return primary
    }

    /**
     * 返回 (候选列表, 分段);整串匹配时精确与模糊拼音的候选合并,统一按权重排序
     * (同权重精确在前),再逐级缩短;只匹配完整音节,不做前缀补全。最后按消耗音节数降序稳定排序。
     */
    fun candidates(buf: String): Pair<List<Candidate>, List<String>> {
        val segs = bestSegment(buf)
        if (segs.isEmpty()) return Pair(emptyList(), emptyList())
        // 内部三元组:词、消耗音节数、权重(权重用于同消耗数时的排序,与 Python 版一致)
        val out = ArrayList<Triple<String, Int, Int>>()
        val seen = HashSet<String>()
        fun add(word: String, nseg: Int, weight: Int) {
            if (word !in seen && out.size < MAX_CANDS) { seen.add(word); out.add(Triple(word, nseg, weight)) }
        }

        // 单字母:只打一个字母时,只列出该拼音首字母开头的「单字」(过滤多字词组),纯按权重(initialIdx 已排好序)
        val letters0 = buf.replace("'", "")
        if (letters0.length == 1) {
            initialIdx[letters0[0]]?.let { lst ->
                for (ww in lst) if (ww.word.length == 1) add(ww.word, segs.size, ww.weight)
                return Pair(out.map { Candidate(it.first, it.second) }, segs)
            }
        }

        for (n in segs.size downTo 1) {
            val sub = segs.subList(0, n)
            if (sub.all { it in syllables }) {
                // 精确 + 模糊全部候选,统一按权重降序、同权重精确(键序号小)在前
                val pool = ArrayList<Triple<String, Int, Int>>()
                for ((ki, k) in fuzzyKeys(sub).withIndex()) {
                    table[k]?.forEach { pool.add(Triple(it.word, it.weight, ki)) }
                }
                pool.sortWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
                for (t in pool) add(t.first, n, t.second)
            }
            // 整串完整匹配同级:补「末字简拼」候选(前若干字全拼 + 末若干字只打声母,
            // 如 sej=设计、jisuanj=计算机、nihm=你好吗),消耗整个缓冲区
            if (n == segs.size) addPartAbbr(buf, segs.size, ::add)
        }

        // 简拼:整串全是允许的声母字母时,按声母串(含字母级模糊音展开)补充候选(消耗整个缓冲区)
        val letters = buf.replace("'", "")
        if (abbr.isNotEmpty() && letters.length >= 2 && letters.all { it in ABBR_LETTERS }) {
            val abbrPool = ArrayList<Triple<String, Int, Int>>()
            for ((ki, k) in abbrKeys(letters).withIndex()) {
                abbr[k]?.forEach { abbrPool.add(Triple(it.word, it.weight, ki)) }
            }
            abbrPool.sortWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
            for (t in abbrPool) add(t.first, segs.size, t.second)
        }
        // 消耗输入更多的候选排前(如 nbn:吃满 3 字母的"能不能"应在只占 1 个的"嗯"前);
        // 同消耗数按权重降序;稳定排序,权重也相同时保持插入次序(精确>模糊>简拼)
        val sorted = out.sortedWith(
            compareByDescending<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third }
        )
        return Pair(sorted.map { Candidate(it.first, it.second) }, segs)
    }

    /**
     * 末字简拼:把末尾 t 个字母当作连续声母、前缀整段切成完整音节去查索引。
     * 直接在原始字母串上枚举 t(不依赖贪心切分,避免 nihm 里 hm 被当成音节)。
     */
    private fun addPartAbbr(buf: String, nseg: Int, add: (String, Int, Int) -> Unit) {
        if (partAbbr.isEmpty()) return
        val letters = buf.replace("'", "")
        val pool = ArrayList<Triple<String, Int, Int>>()
        for (t in 1 until letters.length) {   // 末尾 t 个字母作声母,剩下作全拼前缀
            val tail = letters.substring(letters.length - t)
            if (!tail.all { it.toString() in INITIALS_1 }) break  // 出现非声母,再往左不可能全是声母
            val pre = segment(letters.substring(0, letters.length - t))
            if (!pre.all { it in syllables }) continue   // 前缀切不成完整音节
            for ((ki, combo) in fuzzyKeys(pre).withIndex()) {
                partAbbr[combo to tail]?.forEach { pool.add(Triple(it.word, it.weight, ki + t * 100)) }  // t 越小(全拼越多)越靠前
            }
        }
        pool.sortWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
        for (p in pool) add(p.first, nseg, p.second)
    }

    /**
     * 选词调权:把选中词的权重提为同拼音候选池里的最大权重 + 1(内存立即生效)。
     * 返回需写回文件的 (拼音键, 新权重);已是唯一最高或找不到时返回 null。
     */
    fun bump(word: String, sub: List<String>): Pair<String, Int>? {
        val sylls = sub.filter { it != "'" }
        if (sylls.isEmpty()) return null
        var target: String? = null
        var pool: List<WordWeight>? = null

        if (sylls.all { it in syllables }) {
            val keys = fuzzyKeys(sylls)   // 第一个是原拼音,优先归到精确键
            for (k in keys) {
                if (table[k]?.any { it.word == word } == true) { target = k; break }
            }
            if (target != null) {
                val p = ArrayList<WordWeight>()
                for (k in keys) table[k]?.let { p.addAll(it) }
                pool = p
            }
        }
        if (target == null) {   // 简拼选词:反查该词真正的拼音键(简拼按字母模糊展开)
            val letters = sylls.joinToString("")
            var abKey: String? = null
            for (k in abbrKeys(letters)) {
                val ab = abbr[k]
                if (ab != null && ab.any { it.word == word }) { abKey = k; pool = ab; break }
            }
            if (abKey != null) {
                for ((k, v) in table) {
                    val ss = k.split(" ")
                    if (ss.size == abKey.length &&
                        ss.indices.all { ss[it][0] == abKey[it] } &&
                        v.any { it.word == word }
                    ) { target = k; break }
                }
            }
        }
        if (target == null && sylls.size == 1) {
            // 单字母选词(如打 s 出「是/说/上」):反查该字真正的拼音键。
            // 单字母不是完整音节、也不是简拼(长度不够),前两条反查都命中不了,
            // 否则调权直接被跳过——打单字母选词永远排不到前面。
            val letter = sylls[0].firstOrNull()
            if (letter != null && initialIdx.containsKey(letter)) {
                for ((k, v) in table) {
                    if (!k.contains(" ") && k.firstOrNull() == letter && v.any { it.word == word }) {
                        target = k; pool = v
                        break
                    }
                }
            }
        }
        val t = target ?: return null
        val p = pool ?: return null
        // 比较池并入「简拼桶」:与 PC 版一致。否则用全拼选词只把词提到同音词里第一,
        // 进不了简拼(如 zb)的竞争;并入后,选一次「在不」就能让它在 zb 里也排第一。
        val cmpPool = ArrayList<WordWeight>(p)
        val syllsT = t.split(" ")
        if (syllsT.size >= 2) {
            abbr[syllsT.joinToString("") { it[0].toString() }]?.let { cmpPool.addAll(it) }
        }
        // 比较池并入「单字母桶」:打一个字母时候选取自 initialIdx(同拼音首字母的所有词),
        // 但调权只在同精确拼音组里比,会出现「选了 哦(o)却排不到 欧/偶(ou)前」。
        // 并入后,新权重高过整个单字母桶,单字母列表里也能排第一。
        initialIdx[t[0]]?.let { cmpPool.addAll(it) }
        // 各桶共享同一 WordWeight 实例,按实例去重,避免下面「唯一最高」判断把同一个词数成多个
        val uniq = cmpPool.distinct()
        val mx = uniq.maxOf { it.weight }
        val cur = table[t]!!.first { it.word == word }.weight
        if (cur == mx && uniq.count { it.weight == mx } == 1) return null   // 已是唯一最高
        val new = mx + 1
        setWeight(t, word, new)
        return t to new
    }

    private fun setWeight(key: String, word: String, weight: Int) {
        val lst = table[key] ?: return
        lst.firstOrNull { it.word == word }?.weight = weight   // 共享实例,abbr 同步更新
        lst.sortByDescending { it.weight }
        initialIdx[key[0]]?.sortByDescending { it.weight }     // 单字母索引(按拼音首字母归桶)
        val sylls = key.split(" ")
        if (sylls.size >= 2) {
            abbr[sylls.joinToString("") { it[0].toString() }]?.sortByDescending { it.weight }   // 简拼桶
            for (p in 1 until sylls.size) {   // 末字简拼各切分点
                val inits = sylls.subList(p, sylls.size).joinToString("") { it[0].toString() }
                partAbbr[sylls.subList(0, p).joinToString(" ") to inits]?.sortByDescending { it.weight }
            }
        }
    }

    companion object {
        // ---- 配置(与 pyime.py 顶部一致) ----
        // 不限制候选数(与 PC 版一致):生僻字如「唳」在 li 的候选里排名靠后,截断会导致翻不到。
        // UI 侧改成分批懒渲染(见 PinyinImeService.renderMoreCands),所以放开上限不影响按键流畅度。
        const val MAX_CANDS = Int.MAX_VALUE
        const val MAX_PINYIN = 30
        const val MAX_FUZZY_KEYS = 24
        const val ENABLE_SHOUPIN = true
        const val MAX_SHOUPIN_PER_KEY = 80
        const val ENABLE_SINGLE_INITIAL = true            // 单字母:打一个字母即列出该拼音首字母开头的词,按词频
        const val MAX_SINGLE_INITIAL_PER_KEY = Int.MAX_VALUE  // 每个首字母保留多少候选(不限制,全部保留)
        const val ENABLE_TAIL_INITIAL = true     // 末字简拼:前面音节全拼、末尾若干字只打声母,如 sej->设计、jisuanj->计算机
        const val MAX_TAIL_INITIAL_PER_KEY = 80  // 每个「前缀+末声母」键保留多少候选(按词频)

        // 模糊音:每对双向等价;清空则关闭
        val FUZZY_PAIRS = listOf(
            "z" to "zh", "c" to "ch", "s" to "sh",   // 平翘舌
            "en" to "eng", "in" to "ing",
            "l" to "r", "on" to "ong",
            "n" to "l"                               // l/n 不分(南方口音):leixin/neixin 都能出「类型」
        )

        val INITIALS_2 = setOf("zh", "ch", "sh")
        // 单字符声母,存成单字符字符串集合(与 Python frozenset 行为一致,便于和 String 比较)
        val INITIALS_1 = "bpmfdtnlgkhjqxrzcsyw".map { it.toString() }.toHashSet()
        // 简拼允许的字母(Char 集合):声母 + 零声母音节首字母 a/e/o(否则 安卓=az、欧盟=om 查不到)
        val ABBR_LETTERS = ("bpmfdtnlgkhjqxrzcsyw" + "aeo").toHashSet()

        /** 从文件加载词库。 */
        fun load(file: File): PinyinDict = PinyinDict(file.readText(Charsets.UTF_8))

        /**
         * 把词库文件里 word+key 那行的权重列改为 weight,覆盖写回(单文件、小改动,直接覆盖即可)。
         * 找不到对应行返回 false。线程安全由调用方(单线程写队列)保证。
         */
        fun updateDictFile(file: File, word: String, key: String, weight: Int): Boolean {
            val lines = file.readText(Charsets.UTF_8).split("\n").toMutableList()
            var body = false
            var done = false
            for (i in lines.indices) {
                val line = lines[i]
                if (!body) { if (line.trim() == "...") body = true; continue }
                val parts = line.split("\t").toMutableList()
                if (parts.size >= 2 && parts[0] == word && parts[1].trim() == key) {
                    if (parts.size >= 3) parts[2] = weight.toString() else parts.add(weight.toString())
                    lines[i] = parts.joinToString("\t")
                    done = true
                    break
                }
            }
            if (!done) return false
            file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
            return true
        }
    }
}
