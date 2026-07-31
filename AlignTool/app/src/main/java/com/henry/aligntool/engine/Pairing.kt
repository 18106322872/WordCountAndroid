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

        return sequential(src, tgt)
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

    // 顺序 i↔i（docx / 跨格式兜底）
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
