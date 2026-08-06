package com.henry.aligntool.engine

/**
 * 配对算法（等价桌面 align_core.block_pairs :119 / _block_pairs_docx :349 / _block_pairs_pptx :150 / _xlsx_walk :274）。
 *
 * 这是桌面版反复修错位才稳定的核心逻辑，手机版 1:1 复刻：
 *   1) 两份都带 Excel 位置 → 按 (sheetIdx,row,col) 同位置匹配
 *   2) 两份都带 PPTX 位置 → 按 (slideIdx,shapeIdx,innerIdx) 同位置匹配
 *   3) 否则（docx / 跨格式兜底）→ 编号/章节锚定 + 区间回溯（见 anchored）
 * 未配对块进入 extras（附在文档末尾，标记 UNPAIRED_MARK）。
 */
object Pairing {

    data class Result(
        val pairs: List<Pair<Block, Block>>,      // (骨架块, 对方块)，长度 = 骨架块数，与 slots 一一对齐
        val extras: List<Pair<String, Block>>     // ("src"|"tgt", 未配对块)
    )

    fun blockPairs(src: List<Block>, tgt: List<Block>): Result {
        val bothExcel = src.isNotEmpty() && tgt.isNotEmpty() &&
                src.all { it.sheetIdx != null } && tgt.all { it.sheetIdx != null }
        if (bothExcel) return xlsx(src, tgt)

        val bothPptx = src.isNotEmpty() && tgt.isNotEmpty() &&
                src.all { it.slideIdx != null } && tgt.all { it.slideIdx != null }
        if (bothPptx) return pptx(src, tgt)

        // docx / 跨格式兜底：编号锚定 + 区间回溯（v1.0.21 起与桌面 _block_pairs_docx 对齐）
        return anchored(src, tgt)
    }

    // 按 (sheetIdx,row,col) 同位置匹配（_xlsx_walk 遍历顺序）
    private fun xlsx(src: List<Block>, tgt: List<Block>): Result {
        val srcMap = LinkedHashMap<Triple<Int, Int, Int>, Block>()
        for (b in src) srcMap[Triple(b.sheetIdx!!, b.row!!, b.col!!)] = b
        val tgtMap = LinkedHashMap<Triple<Int, Int, Int>, Block>()
        for (b in tgt) tgtMap[Triple(b.sheetIdx!!, b.row!!, b.col!!)] = b

        val pairs = mutableListOf<Pair<Block, Block>>()
        val extras = mutableListOf<Pair<String, Block>>()
        for ((k, sb) in srcMap) {
            val tb = tgtMap[k]
            if (tb != null) pairs.add(sb to tb) else extras.add("src" to sb)
        }
        for ((k, tb) in tgtMap) {
            if (!srcMap.containsKey(k)) extras.add("tgt" to tb)
        }
        return Result(pairs, extras)
    }

    // 按 (slideIdx,shapeIdx,innerIdx) 同位置匹配（_block_pairs_pptx）
    private fun pptx(src: List<Block>, tgt: List<Block>): Result {
        val srcMap = LinkedHashMap<Triple<Int, Int, Int>, Block>()
        for (b in src) srcMap[Triple(b.slideIdx!!, b.shapeIdx!!, b.innerIdx!!)] = b
        val tgtMap = LinkedHashMap<Triple<Int, Int, Int>, Block>()
        for (b in tgt) tgtMap[Triple(b.slideIdx!!, b.shapeIdx!!, b.innerIdx!!)] = b

        val pairs = mutableListOf<Pair<Block, Block>>()
        val extras = mutableListOf<Pair<String, Block>>()
        for ((k, sb) in srcMap) {
            val tb = tgtMap[k]
            if (tb != null) pairs.add(sb to tb) else extras.add("src" to sb)
        }
        for ((k, tb) in tgtMap) {
            if (!srcMap.containsKey(k)) extras.add("tgt" to tb)
        }
        return Result(pairs, extras)
    }

    // ───────────────────────── docx 主路径：编号/章节锚定 + 区间回溯 ─────────────────────────
    // 等价桌面 align_core._block_pairs_docx (v1.0.22)：把"一段对不上后面全错"的根因
    // （块数不等时裸 i↔i 偏移累积）彻底解决。三步：
    //   ① 综合锚点：优先阿拉伯层级编号（1.1/2.3.4，中英一致）→"D:.."，否则章节序号
    //      锚点（第一章↔Chapter I →"C:1"、一、↔I. →"I:1" 等）→"C:/S:/P:/A:/I:"；
    //   ② 短孤立段（封面"施/施"拆字残留、纯标点、单字）标记为 filler，不进位置配对
    //      （skel 端留空不插入，oth 端进 extras）；⚠️ 表格永不为 filler；
    //   ③ 按文档顺序贪心配对同名锚点（局部窗口 W=12 容错插入/删除），切区间，区间内
    //      仅非 filler 索引从首端顺序配对，差异局限区间尾端，绝不跨锚点传播。
    private fun anchored(src: List<Block>, tgt: List<Block>): Result {
        val n = src.size
        val m = tgt.size

        // 1) 标注锚点与 filler（双端）
        val skelAnchor = LinkedHashMap<Int, String>()   // skel_idx -> 锚点key
        val othAnchor = LinkedHashMap<Int, String>()
        val skelFiller = mutableSetOf<Int>()
        val othFiller = mutableSetOf<Int>()
        for (i in src.indices) {
            val k = leadAnchor(src[i].text)
            if (k != null) skelAnchor[i] = k
            else if (isDocxFiller(src[i])) skelFiller.add(i)
        }
        for (j in tgt.indices) {
            val k = leadAnchor(tgt[j].text)
            if (k != null) othAnchor[j] = k
            else if (isDocxFiller(tgt[j])) othFiller.add(j)
        }

        // 2) 按文档顺序贪心配对同名锚点（局部窗口 W 容错插入/删除），保持单调
        val W = 12
        val skelAk = skelAnchor.toList().sortedBy { it.first }   // [(idx,key),...]
        val othAk = othAnchor.toList().sortedBy { it.first }
        val pairsIdx = mutableListOf<Pair<Int, Int>>()           // (si,oi)，si、oi 均单调递增
        var i = 0
        var j = 0
        while (i < skelAk.size && j < othAk.size) {
            val ks = skelAk[i].second
            val ko = othAk[j].second
            if (ks == ko) {
                pairsIdx.add(skelAk[i].first to othAk[j].first)
                i++; j++; continue
            }
            // 编号不同：在对方局部窗口内找相同编号，跳过本方多出的条目
            var found = -1
            for (jj in j until minOf(j + W, othAk.size)) {
                if (othAk[jj].second == ks) { found = jj; break }
            }
            if (found >= 0) { j = found; continue }
            found = -1
            for (ii in i until minOf(i + W, skelAk.size)) {
                if (skelAk[ii].second == ko) { found = ii; break }
            }
            if (found >= 0) { i = found; continue }
            // 双方均有孤立多出的编号条目（罕见），一并跳过
            i++; j++
        }

        // 2.5) 落实锚点对：skel 锚点段直接配对其对应 oth 段
        val pairArr = Array<Block?>(n) { null }
        for ((si, oi) in pairsIdx) pairArr[si] = tgt[oi]

        val extras = mutableListOf<Pair<String, Block>>()

        // 3) 区间回溯：哨兵 (-1,-1) + 锚点对 + (n,m)，区间内非 filler 索引从首端顺序配对
        val bounds = mutableListOf(Pair(-1, -1))
        bounds.addAll(pairsIdx)
        bounds.add(Pair(n, m))
        for (b in 0 until bounds.size - 1) {
            val s0 = bounds[b].first; val o0 = bounds[b].second
            val s1 = bounds[b + 1].first; val o1 = bounds[b + 1].second
            val skelGap = (s0 + 1 until s1).filter { it !in skelFiller }
            val othGap = (o0 + 1 until o1).filter { it !in othFiller }
            val L = minOf(skelGap.size, othGap.size)
            for (k in 0 until L) pairArr[skelGap[k]] = tgt[othGap[k]]
            // 其他端多出的（区间尾端）→ extras；skel 端多出的（区间尾端）留 null（不插入）
            for (x in othGap.subList(L, othGap.size)) extras.add("tgt" to tgt[x])
        }
        // 未被配对且为 filler 的 oth 段进 extras（保持桌面语义）
        for (j2 in othFiller) extras.add("tgt" to tgt[j2])

        // 4) 转成 Result：pairs 长度 = 骨架块数，与 slots 一一对齐；
        //    未配对的骨架块用 DUMMY 占位（other.text 空 → 写入端跳过，不插入译文）。
        val pairs = (0 until n).map { i2 -> src[i2] to (pairArr[i2] ?: DUMMY) }
        return Result(pairs, extras)
    }

    /** 占位块：未配对的骨架块以它填充，写入端因 text 为空而跳过插入。 */
    private val DUMMY = Block("")

    // ── 短孤立段判定（等价桌面 _is_docx_filler）──
    private fun isDocxFiller(b: Block): Boolean {
        if (b.isTable || b.isCell) return false   // ⚠️ 表格/单元格永不为 filler（单元格 text 可能短，但成对出现，位置配对）
        val s = (b.text ?: "").trim()
        if (s.isEmpty()) return true
        if (RE_FILLER_FULL.matches(s)) return true
        val core = s.replace(RE_FILLER_STRIP, "")
        return core.length <= 1
    }

    // ── 综合锚点（等价桌面 _lead_anchor）──
    private fun leadAnchor(text: String?): String? {
        val d = leadDotten(text)
        if (d != null) return "D:$d"
        val sec = leadSection(text)
        if (sec != null) return sec
        return null
    }

    /** 段首阿拉伯层级编号（1.1 / 2.3.4）；要求至少 1 个 '.'，避免年份/页码误锚。无则 null。 */
    private fun leadDotten(text: String?): String? {
        val s = (text ?: "").trim()
        if (s.isEmpty()) return null
        val m = RE_LEAD_DOTTED.find(s) ?: return null
        return m.groupValues[1]
    }

    /** 段首章节序号锚点（等价桌面 _lead_section）：归一为 C:/S:/P:/A:/I: 键，跨语言配对。
     *  显式不锚定 (一)(二) 与 1. 2. 列表（过于细碎、中英常不对称，易触发级联错位）。 */
    private fun leadSection(text: String?): String? {
        val s = (text ?: "").trim()
        if (s.isEmpty()) return null
        // ---- 章 / 节 / 部分（中文 第X…）----
        RE_SEC_CHAP_CN_CH.find(s)?.let { m -> cn2int(m.groupValues[1])?.let { return "C:$it" } }
        RE_SEC_CHAP_CN_SEC.find(s)?.let { m -> cn2int(m.groupValues[1])?.let { return "S:$it" } }
        RE_SEC_CHAP_CN_PART.find(s)?.let { m -> cn2int(m.groupValues[1])?.let { return "P:$it" } }
        RE_SEC_APPEND_CN.find(s)?.let { m ->
            val tok = m.groupValues[1]
            if (tok.length == 1 && tok[0] in 'A'..'Z') return "A:${tok[0] - 'A' + 1}"
            if (tok.length == 1 && tok[0] in 'a'..'z') return "A:${tok[0] - 'a' + 1}"
            cn2int(tok)?.let { return "A:$it" }
        }
        // ---- 章 / 节 / 部分 / 附录（英文）----
        RE_SEC_EN.find(s)?.let { m ->
            val kind = m.groupValues[1].lowercase()
            val tok = m.groupValues[2]
            val v = when {
                tok.all { it.isDigit() } -> tok.toIntOrNull()
                tok.all { it.isLetter() } -> {
                    val up = tok.uppercase()
                    if (up.all { it in "IVXLCDM" }) roman2int(up)
                    else if (up.length == 1) (up[0] - 'A' + 1)
                    else engOrdinal(tok)
                }
                else -> null
            }
            if (v != null) {
                val k = when (kind) {
                    "chapter" -> "C"; "section" -> "S"; "part" -> "P"; "appendix" -> "A"; else -> null
                }
                if (k != null) return "$k:$v"
            }
        }
        // ---- 条目：罗马数字 I. II. III.（章节标题风格，非正文列表）----
        RE_SEC_ROMAN_ITEM.find(s)?.let { m -> roman2int(m.groupValues[1])?.let { return "I:$it" } }
        // ---- 条目：中文数字 一、二、…（段首中文数字+顿号/点，章节标题风格）----
        RE_SEC_CN_ITEM.find(s)?.let { m -> cn2int(m.groupValues[1])?.let { return "I:$it" } }
        return null
    }

    // ── 正则表 ──
    private val FILLER_CHARS = "\\s\\-—–…·.,。:：;；、?!！()（）\\[\\]【】\"'"
    private val RE_FILLER_FULL = Regex("^[$FILLER_CHARS]+\$")
    private val RE_FILLER_STRIP = Regex("[$FILLER_CHARS]+")

    private val RE_LEAD_DOTTED = Regex("""^\s*(\d+(?:\.\d+){1,3})(?![.\d])""")

    private val RE_SEC_CHAP_CN_CH = Regex("""^第\s*([零一二三四五六七八九十百千两]+)\s*章""")
    private val RE_SEC_CHAP_CN_SEC = Regex("""^第\s*([零一二三四五六七八九十百千两]+)\s*节""")
    private val RE_SEC_CHAP_CN_PART = Regex("""^第\s*([零一二三四五六七八九十百千两]+)\s*部分""")
    private val RE_SEC_APPEND_CN = Regex("""^附录\s*([A-Za-z零一二三四五六七八九十]+)""")
    private val RE_SEC_EN = Regex("""^(Chapter|CHAPTER|Section|SECTION|Part|PART|Appendix|APPENDIX)\s+([IVXLCDMivxlcdm]+|[0-9]+|[A-Za-z]+)""")
    private val RE_SEC_ROMAN_ITEM = Regex("""^\s*([IVXLCDM]+)\s*[\.．、]""")
    private val RE_SEC_CN_ITEM = Regex("""^\s*([零一二三四五六七八九十百千两]+)\s*[、．.]""")

    // ── 数字归一 ──
    private fun cn2int(s: String?): Int? {
        val map = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        val str = (s ?: "").trim()
        if (str.isEmpty()) return null
        if (str.length == 1 && str[0] in map) return map[str[0]]
        if (str == "十") return 10
        var v = 0
        var tmp = 0
        for (ch in str) {
            when {
                ch in map -> tmp = map[ch]!!
                ch == '十' -> { v += if (tmp > 0) tmp * 10 else 10; tmp = 0 }
                ch == '百' -> { v += if (tmp > 0) tmp * 100 else 100; tmp = 0 }
                ch == '千' -> { v += if (tmp > 0) tmp * 1000 else 1000; tmp = 0 }
                else -> return null
            }
        }
        v += tmp
        return if (v > 0) v else null
    }

    private fun roman2int(s: String?): Int? {
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        val str = (s ?: "").trim().uppercase()
        if (str.isEmpty() || str.any { it !in map }) return null
        var total = 0
        var prev = 0
        for (ch in str.reversed()) {
            val v = map[ch]!!
            total += if (v < prev) -v else v
            prev = v
        }
        return if (total == 0) null else total
    }

    private fun engOrdinal(s: String?): Int? {
        val map = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
            "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
            "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
            "eleventh" to 11, "twelfth" to 12
        )
        return map[(s ?: "").trim().lowercase()]
    }
}
