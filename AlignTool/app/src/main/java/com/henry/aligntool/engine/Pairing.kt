package com.henry.aligntool.engine

/**
 * 配对算法（等价桌面 align_core.block_pairs :119 / _block_pairs_pptx :150 / _xlsx_walk :274）。
 *
 * 这是桌面版反复修错位才稳定的核心逻辑，手机版 1:1 复刻：
 *   1) 两份都带 Excel 位置 → 按 (sheetIdx,row,col) 同位置匹配
 *   2) 两份都带 PPTX 位置 → 按 (slideIdx,shapeIdx,innerIdx) 同位置匹配
 *   3) 否则（docx / 跨格式兜底）→ 遍历顺序 i↔i 配对
 * 未配对块进入 extras（附在文档末尾，标记 UNPAIRED_MARK）。
 */
object Pairing {

    data class Result(
        val pairs: List<Pair<Block, Block>>,      // (骨架块, 对方块)
        val extras: List<Pair<String, Block>>     // ("src"|"tgt", 未配对块)
    )

    fun blockPairs(src: List<Block>, tgt: List<Block>): Result {
        val bothExcel = src.isNotEmpty() && tgt.isNotEmpty() &&
                src.all { it.sheetIdx != null } && tgt.all { it.sheetIdx != null }
        if (bothExcel) return xlsx(src, tgt)

        val bothPptx = src.isNotEmpty() && tgt.isNotEmpty() &&
                src.all { it.slideIdx != null } && tgt.all { it.slideIdx != null }
        if (bothPptx) return pptx(src, tgt)

        // docx / 跨格式兜底：编号锚点对齐（v1.0.19 起），避免一段错位整篇级联
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

    // 编号锚点对齐（docx 主路径，v1.0.19 新增十进制编号锚点，v1.0.20 加章节序号锚点）
    // 两层锚点 + 局部窗口策略，彻底避免"一段对不上后面全错"的级联错位：
    //   ① 主锚点：十进制编号（2.1 / 3.2.1）全局贪心配对（v1.0.19 已验证 0 错配，最强）
    //   ② 补充锚点：章节序号（第一章/Chapter I、一、/I.、二、/II.、（一）/① 等）
    //      仅在「相邻两个十进制主锚点之间的局部窗口」内前向扫描配对，
    //      防止跨章重复序号（如两个「一、」「SEC4」）错配到别的章。
    // 编号之间的无编号段落只在「相邻锚点之间的局部区间」做位置配对，某段对不上只影响局部。
    private fun anchored(src: List<Block>, tgt: List<Block>): Result {
        val usedSrc = BooleanArray(src.size)
        val usedTgt = BooleanArray(tgt.size)

        // ① 主锚点：十进制编号全局贪心（复用 v1.0.19 已验证逻辑，保证 EN 侧单调、0 错配）
        val srcDec = Array(src.size) { numKey(src[it].text) }
        val tgtDec = Array(tgt.size) { numKey(tgt[it].text) }
        val decAnchors = mutableListOf<Pair<Int, Int>>()
        var di = 0
        for (i in src.indices) {
            if (usedSrc[i] || srcDec[i] == null) continue
            var j = di
            while (j < tgt.size) {
                if (!usedTgt[j] && tgtDec[j] == srcDec[i]) break
                j++
            }
            if (j < tgt.size) {
                decAnchors.add(i to j)
                usedSrc[i] = true
                usedTgt[j] = true
                di = j + 1
            }
        }
        decAnchors.sortBy { it.first }

        // ② 补充锚点：章节序号，限定在相邻主锚点之间的局部窗口前向扫描
        val srcOrd = Array(src.size) { if (usedSrc[it]) null else ordKey(src[it].text) }
        val tgtOrd = Array(tgt.size) { if (usedTgt[it]) null else ordKey(tgt[it].text) }
        // 主锚点边界（含虚拟起点 (-1,-1) 与虚拟终点 (src.size,tgt.size)）
        val bounds = mutableListOf(Pair(-1, -1))
        bounds.addAll(decAnchors)
        bounds.add(Pair(src.size, tgt.size))
        val ordAnchors = mutableListOf<Pair<Int, Int>>()
        for (k in 0 until bounds.size - 1) {
            val sLo = bounds[k].first
            val tLo = bounds[k].second
            val sHi = bounds[k + 1].first
            val tHi = bounds[k + 1].second
            val sStart = sLo + 1
            val sEnd = sHi - 1
            val tStart = tLo + 1
            val tEnd = tHi - 1
            if (sStart > sEnd || tStart > tEnd) continue
            // 局部前向扫描：CN 顺序，每个未用章节序号找下一个未用同 key EN 块
            var tj = tStart
            for (si in sStart..sEnd) {
                if (usedSrc[si] || srcOrd[si] == null) continue
                var t = tj
                while (t <= tEnd) {
                    if (!usedTgt[t] && tgtOrd[t] == srcOrd[si]) break
                    t++
                }
                if (t <= tEnd) {
                    ordAnchors.add(si to t)
                    usedSrc[si] = true
                    usedTgt[t] = true
                    tj = t + 1
                }
            }
        }

        // 合并主锚点 + 补充锚点，按 CN 顺序排序；EN 侧天然单调（主锚点单调 + 补充锚点在窗口内单调）
        val anchors = (decAnchors + ordAnchors).sortedBy { it.first }

        val pairs = mutableListOf<Pair<Block, Block>>()
        val extras = mutableListOf<Pair<String, Block>>()
        var si = 0
        var ti = 0
        for (a in anchors.indices) {
            val sA = anchors[a].first
            val tA = anchors[a].second
            // 局部区间位置配对（区间可能为 0 或负差——译文侧锚点非单调时 tA<ti，
            // 该区间的骨架块全部进 src-extras，绝不反向索引，防止 IndexOutOfBounds）
            val sGap = sA - si
            val tGap = tA - ti
            if (sGap > 0 && tGap > 0) {
                val g = minOf(sGap, tGap)
                for (j in 0 until g) pairs.add(src[si + j] to tgt[ti + j])
                for (j in g until sGap) extras.add("src" to src[si + j])
                for (j in g until tGap) extras.add("tgt" to tgt[ti + j])
            } else if (sGap > 0) {
                for (j in 0 until sGap) extras.add("src" to src[si + j])
            } else if (tGap > 0) {
                for (j in 0 until tGap) extras.add("tgt" to tgt[ti + j])
            }
            // 锚点本身严格配对
            pairs.add(src[sA] to tgt[tA])
            si = sA + 1
            ti = tA + 1
        }
        // 尾部区间
        val sGap = src.size - si
        val tGap = tgt.size - ti
        if (sGap > 0 && tGap > 0) {
            val g = minOf(sGap, tGap)
            for (j in 0 until g) pairs.add(src[si + j] to tgt[ti + j])
            for (j in g until sGap) extras.add("src" to src[si + j])
            for (j in g until tGap) extras.add("tgt" to tgt[ti + j])
        } else if (sGap > 0) {
            for (j in 0 until sGap) extras.add("src" to src[si + j])
        } else if (tGap > 0) {
            for (j in 0 until tGap) extras.add("tgt" to tgt[ti + j])
        }
        return Result(pairs, extras)
    }

    /** 段落前缀十进制编号（2.1 / 3.2.1 等）。无则返回 null。 */
    private fun numKey(text: String): String? {
        val m = DEC_RE.find(text.trimStart()) ?: return null
        return m.groupValues[1]
    }

    /** 章节序号锚点：第一章/Chapter I、一、/I.、二、/II.、（一）/① 等。无则返回 null。 */
    private fun ordKey(text: String): String? {
        val t = text.trimStart()
        CHAP_CN_RE.find(t)?.let { return "CH" + (cnNumToInt(it.groupValues[1]) ?: return null) }
        CHAP_EN_RE.find(t)?.let {
            val g = it.groupValues[1]
            val v = if (g.all { c -> c.isDigit() }) g.toIntOrNull() else romanToInt(g)
            if (v != null) return "CH$v"
        }
        SEC_CN_RE.find(t)?.let { m ->
            val g = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
            return "SEC" + (cnNumToInt(g) ?: return null)
        }
        SEC_EN_RE.find(t)?.let { return "SEC" + (romanToInt(it.groupValues[1]) ?: return null) }
        CIRCLED_RE.find(t)?.let { return "SEC" + (circledToInt(it.groupValues[1]) ?: return null) }
        return null
    }

    private val DEC_RE = Regex("""^\s*(\d+(?:\.\d+)*)""")
    private val CHAP_CN_RE = Regex("""第\s*([一二三四五六七八九十百零〇两]+)\s*章""")
    private val CHAP_EN_RE = Regex("""Chapter\s+([IVXLCDM]+|\d+)""", RegexOption.IGNORE_CASE)
    private val SEC_CN_RE = Regex("""^\s*(?:[（(]\s*([一二三四五六七八九十百零〇两]+)\s*[)）]\s*[、.．]?|([一二三四五六七八九十百零〇两]+)\s*[、.．])""")
    private val SEC_EN_RE = Regex("""^\s*([IVXLCDM]+)\s*\.""", RegexOption.IGNORE_CASE)
    private val CIRCLED_RE = Regex("""^\s*([①-⑳])""")

    /** 中文数字 → 阿拉伯数字（支持 一~九十九、百，如 二十一→21）。 */
    private fun cnNumToInt(s: String): Int? {
        val map = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9, '零' to 0, '〇' to 0)
        var total = 0
        var current = 0
        for (c in s) {
            when {
                c in map -> current = map[c]!!
                c == '十' -> { if (current == 0) current = 1; total += current * 10; current = 0 }
                c == '百' -> { if (current == 0) current = 1; total += current * 100; current = 0 }
                c == '千' -> { if (current == 0) current = 1; total += current * 1000; current = 0 }
                else -> return null
            }
        }
        total += current
        return if (total == 0) null else total
    }

    /** 罗马数字 → 阿拉伯数字（I→1, IV→4, IX→9, XX→20 ...）。 */
    private fun romanToInt(s: String): Int? {
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var prev = 0
        for (c in s.reversed()) {
            val v = map[c] ?: return null
            total += if (v < prev) -v else v
            prev = v
        }
        return if (total == 0) null else total
    }

    /** 带圈数字 ①..⑳ → 1..20。 */
    private fun circledToInt(s: String): Int? {
        val code = s.codePointAt(0)
        return if (code in 0x2460..0x2473) code - 0x2460 + 1 else null
    }

    // 顺序 i↔i（docx 无编号时的兜底，等价于 anchored 全区间位置配对）
    private fun sequential(src: List<Block>, tgt: List<Block>): Result {
        val n = minOf(src.size, tgt.size)
        val pairs = mutableListOf<Pair<Block, Block>>()
        for (i in 0 until n) pairs.add(src[i] to tgt[i])
        val extras = mutableListOf<Pair<String, Block>>()
        for (i in n until src.size) extras.add("src" to src[i])
        for (i in n until tgt.size) extras.add("tgt" to tgt[i])
        return Result(pairs, extras)
    }
}
