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

    // 编号锚点对齐（docx 主路径，v1.0.19 新增）
    // 思路：抽取每段前缀十进制编号（如 2.1 / 3.2.1），按编号严格配对；
    // 编号之间的无编号段落只在「相邻两个编号锚点之间的局部区间」做位置配对。
    // 这样某段对不上只影响局部，不会整篇错位（解决"一段不一致后面全错"）。
    private fun anchored(src: List<Block>, tgt: List<Block>): Result {
        val srcKeys = src.map { numKey(it.text) }
        val tgtKeys = tgt.map { numKey(it.text) }
        val srcByKey = LinkedHashMap<String, MutableList<Int>>()
        val tgtByKey = LinkedHashMap<String, MutableList<Int>>()
        for (i in src.indices) {
            val k = srcKeys[i]
            if (k != null) srcByKey.getOrPut(k) { mutableListOf() }.add(i)
        }
        for (i in tgt.indices) {
            val k = tgtKeys[i]
            if (k != null) tgtByKey.getOrPut(k) { mutableListOf() }.add(i)
        }
        // 同编号按文档顺序一一配对（翻译件两侧编号序列一致 → 全局单调递增）
        val anchorPairs = mutableListOf<Pair<Int, Int>>()
        for ((k, sList) in srcByKey) {
            val tList = tgtByKey[k] ?: continue
            val m = minOf(sList.size, tList.size)
            for (j in 0 until m) anchorPairs.add(sList[j] to tList[j])
        }
        anchorPairs.sortBy { it.first }   // 按骨架文档顺序；译文侧对翻译对亦单调递增

        val pairs = mutableListOf<Pair<Block, Block>>()
        val extras = mutableListOf<Pair<String, Block>>()
        var si = 0
        var ti = 0
        for (a in anchorPairs.indices) {
            val sA = anchorPairs[a].first
            val tA = anchorPairs[a].second
            // 局部区间 [si, sA-1] ↔ [ti, tA-1] 位置配对
            val sGap = sA - si
            val tGap = tA - ti
            val g = minOf(sGap, tGap)
            for (j in 0 until g) pairs.add(src[si + j] to tgt[ti + j])
            for (j in g until sGap) extras.add("src" to src[si + j])
            for (j in g until tGap) extras.add("tgt" to tgt[ti + j])
            // 锚点本身严格配对
            pairs.add(src[sA] to tgt[tA])
            si = sA + 1
            ti = tA + 1
        }
        // 尾部区间
        val sGap = src.size - si
        val tGap = tgt.size - ti
        val g = minOf(sGap, tGap)
        for (j in 0 until g) pairs.add(src[si + j] to tgt[ti + j])
        for (j in g until sGap) extras.add("src" to src[si + j])
        for (j in g until tGap) extras.add("tgt" to tgt[ti + j])
        return Result(pairs, extras)
    }

    /** 抽取段落前缀的十进制编号（2.1 / 3.2.1 等）。无则返回 null。 */
    private fun numKey(text: String): String? {
        val m = DEC_RE.find(text.trimStart()) ?: return null
        return m.groupValues[1]
    }

    private val DEC_RE = Regex("""^\s*(\d+(?:\.\d+)*)""")

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
