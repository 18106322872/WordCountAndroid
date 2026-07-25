package com.henry.wordcount

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.text.Regex
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 纯 Kotlin 实现的 DOCX 文档比较器（v1.1.63 修复删除渲染+回退SUB_EQ黑字版）。
 *
 * 设计目标：输出文档与 Word「审阅-比较」结果一致。
 * 核心思路：
 *   1. 以【原文档 XML 为底板】，保留完整格式。
 *   2. 段落级对齐：exact 相等 → 黑字(EQ)；相似配对(greedy) → 内联字符级 diff(REP)；其余 orig→红字删除(DEL)，rev→蓝字插入(INS)。
 *      相似段落(字符 LCS 比率≥0.5) → 内联字符级 diff(REP)；其余 orig→红字删除(DEL)，rev→蓝字插入(INS)。
 *   3. 相邻「删除+插入」仅当二者相似时才合并为一段内联修订(REP)，避免无关段落被错误合并。
 *
 * 完全不依赖 Python/lxml，用 Android 标准库（ZipFile + 正则）实现。
 */
object DocxComparator {

    private const val TAG = "DocxCompare"

    data class Para(val xml: String, val text: String)
    data class CompareResult(
        val ok: Boolean,
        val error: String? = null,
        val outputPath: String? = null,
        val modifiedChars: Int = 0,
        val insCount: Int = 0,
        val delCount: Int = 0,
        val repCount: Int = 0,
        val summary: String = ""
    )

    /** 对齐后的一次操作。tag: EQ / SUB_EQ / REP / DEL / INS */
    data class CmpOp(
        val tag: String,
        val oi: Int,   // 原文档段落下标（-1 表示无）
        val rj: Int,   // 修订文档段落下标（-1 表示无）
        val pos: Double,
        val gap: String = "",   // SDEL 时承载被删除的原文片段
        val delExclude: List<Pair<Int, Int>>? = null  // 部分消耗时标记被子串占用的区间（DEL 只输出剩余片段）
    )

    /** 跟踪 orig 段落的使用状态（支持部分消耗）*/
    private data class OrigState(
        val index: Int,
        val usedRanges: MutableList<Pair<Int, Int>> = mutableListOf(),
        var fullyUsed: Boolean = false
    ) {
        fun markUsed(start: Int, end: Int) {
            usedRanges.add(Pair(start, end))
            val totalUsed = usedRanges.sumOf { it.second - it.first }
            fullyUsed = (totalUsed >= /* textLen - computed separately */ 0)
        }
    }

    data class WRun(val start: Int, val end: Int, val runXml: String)

    fun compare(
        context: Context?,
        origPath: String,
        revPath: String,
        outPath: String,
        optsJson: String
    ): CompareResult {
        return try {
            doCompare(origPath, revPath, outPath, optsJson)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM: ${e.message}")
            CompareResult(ok = false, error = "内存不足，请关闭其他应用后重试")
        } catch (e: Throwable) {
            Log.e(TAG, "compare error: ${e.javaClass.simpleName}: ${e.message}")
            CompareResult(ok = false, error = "${e.javaClass.simpleName}: ${e.message?.take(300)}")
        }
    }

    private fun doCompare(origPath: String, revPath: String, outPath: String, optsJson: String): CompareResult {
        val origFile = File(origPath)
        val revFile = File(revPath)
        if (!origFile.isFile()) return CompareResult(ok = false, error = "原文档不存在: $origPath")
        if (!revFile.isFile()) return CompareResult(ok = false, error = "修订文档不存在: $revPath")

        val opts = try { org.json.JSONObject(optsJson) } catch (_: Exception) { org.json.JSONObject() }
        val author = "WordCount"
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

        val origParas = readParagraphs(origFile, "orig")
        val revParas = readParagraphs(revFile, "rev")

        val ops = alignParagraphs(origParas, revParas)

        // ── 构建输出 body ──
        val ridSeq = intArrayOf(0)
        var insCount = 0
        var delCount = 0
        var repCount = 0
        var totalInsChars = 0       // 新增字符（计入修改字数）
        var totalDelChars = 0       // 删除字符（仅统计，不计入modifiedChars）
        var eqBlackChars = 0        // 未改动字符（EQ 段落全字数 + REP 段落中 equal 部分）
        val bodyParts = mutableListOf<String>()

        for (op in ops) {
            when (op.tag) {
                "EQ" -> {
                    // 完全相同段落 → 黑字不变
                    bodyParts.add(revParas[op.rj].xml)
                    eqBlackChars += revParas[op.rj].text.length
                }
                "SUB_EQ" -> {
                    // 子串匹配段：修订档段落是原文档某段的子串
                    // → 按修订档原样输出（黑字），与 Word 原生一致（Word W06/W07 均为黑字）
                    bodyParts.add(revParas[op.rj].xml)
                    eqBlackChars += revParas[op.rj].text.length
                }
                "DEL" -> {
                    // 原档中被删除的段落（仅非空段落）
                    if (origParas[op.oi].text.isNotEmpty()) {
                        if (op.delExclude != null) {
                            // 部分消耗的 orig：只输出未被子串提取的剩余片段为红字删除
                            bodyParts.add(wrapPartialDeletedParagraph(origParas[op.oi].xml, op.delExclude, author, date, ridSeq))
                        } else {
                            bodyParts.add(wrapDeletedParagraph(origParas[op.oi].xml, author, date, ridSeq))
                        }
                        delCount++
                        totalDelChars += origParas[op.oi].text.length
                    }
                }
                "INS" -> {
                    // 修订档中新插入的段落
                    bodyParts.add(wrapInsertedParagraph(revParas[op.rj].xml, author, date, ridSeq))
                    insCount++
                    totalInsChars += revParas[op.rj].text.length
                }
                "REP" -> {
                    // 相似段落 → 内联字符级 diff（蓝字插入+红字删除）
                    val (pXml, delC, insC, eqC) = buildDiffParagraphXml(
                        origParas[op.oi].xml,
                        origParas[op.oi].text,
                        revParas[op.rj].text,
                        author, date, ridSeq
                    )
                    bodyParts.add(pXml)
                    totalDelChars += delC
                    totalInsChars += insC
                    eqBlackChars += eqC   // REP 段落中未修改的部分也是"黑字"
                    repCount++
                }
            }
        }

        writeOutputDocx(origFile, outPath, bodyParts)

        // 修改字数 = 修订档总字数 - 未改动的字数（仅 EQ）
        // 保证 ≤ 修订档总字数（用户要求：最高=修订档字数）
        val revTotalChars = revParas.sumOf { it.text.length }
        val modifiedChars = kotlin.math.max(0, revTotalChars - eqBlackChars)
        val summary = buildString {
            append("插入 $insCount 处(${totalInsChars}字) | 删除 $delCount 处(${totalDelChars}字) | 修改 $repCount 处")
        }

        Log.d(TAG, "result: ins=$insCount(${totalInsChars}字) del=$delCount(${totalDelChars}字) rep=$repCount chars=$modifiedChars")

        return CompareResult(
            ok = true,
            outputPath = outPath,
            modifiedChars = modifiedChars,
            insCount = insCount,
            delCount = delCount,
            repCount = repCount,
            summary = summary
        )
    }

    // ══════════════════════════════════════════════════════
    //  段落对齐（v1.1.60: 子串优先匹配）
    //  Phase1: Exact EQ → Phase2: Substring EQ → Phase3: REP → Phase4: DEL/INS
    // ══════════════════════════════════════════════════════

    private fun alignParagraphs(origParas: List<Para>, revParas: List<Para>): List<CmpOp> {
        val n = origParas.size
        val m = revParas.size
        val oStates = Array(n) { OrigState(it) }
        val rUsed = BooleanArray(m)
        val ops = mutableListOf<CmpOp>()

        // ── Phase 1: Exact matches → EQ（完全相同的段落，黑字不变）──
        for (j in 0 until m) {
            if (rUsed[j]) continue
            val rt = revParas[j].text
            if (rt.isEmpty()) continue
            for (i in 0 until n) {
                if (oStates[i].fullyUsed) continue
                if (origParas[i].text == rt) {
                    ops.add(CmpOp("EQ", i, j, 0.0))
                    oStates[i].fullyUsed = true
                    rUsed[j] = true
                    break
                }
            }
        }

        // ── Phase 2: Substring EQ（rev段落是某个orig段落的子串 → 输出全黑）──
        // 关键改进：短的 rev 段落如果完整包含于长 orig 段落中，
        // 优先匹配为 EQ(全黑)，避免后续把长 orig 整体配为 REP 导致子串内容重复输出。
        val subEqOps = mutableListOf<CmpOp>()
        // 按 rev 段落长度排序（短的优先匹配）
        val revByLen = (0 until m)
            .filter { !rUsed[it] && revParas[it].text.isNotEmpty() }
            .sortedBy { revParas[it].text.length }

        for (rj in revByLen) {
            if (rUsed[rj]) continue
            val rjText = revParas[rj].text
            var bestI = -1
            var bestPos = -1
            for (i in 0 until n) {
                if (oStates[i].fullyUsed) continue
                val oiText = origParas[i].text
                if (oiText == rjText) continue  // Phase1 已处理
                val pos = oiText.indexOf(rjText)
                if (pos >= 0) {
                    bestI = i
                    bestPos = pos
                    break
                }
            }
            if (bestI >= 0) {
                ops.add(CmpOp("SUB_EQ", bestI, rj, 0.0))
                oStates[bestI].markUsed(bestPos, bestPos + rjText.length)
                // 检查是否已完全消耗
                val totalUsed = oStates[bestI].usedRanges.sumOf { it.second - it.first }
                oStates[bestI].fullyUsed = (totalUsed >= origParas[bestI].text.length)
                rUsed[rj] = true
            }
        }

        // ── Phase 3: Containment REP + Similarity REP ──
        val candidates = mutableListOf<Triple<Double, Int, Int>>()
        val SIM_THRESHOLD = 0.35
        for (i in 0 until n) {
            if (oStates[i].fullyUsed) continue
            val oiText = origParas[i].text
            if (oiText.isEmpty()) continue
            for (j in 0 until m) {
                if (rUsed[j]) continue
                val rjText = revParas[j].text
                if (rjText.isEmpty()) continue
                val s = similarity(oiText, rjText)
                if (s >= SIM_THRESHOLD) {
                    val hasContainment = oiText in rjText || rjText in oiText
                    candidates.add(Triple(s, i, j))
                }
            }
        }
        candidates.sortByDescending { it.first }

        for ((s, oi, rj) in candidates) {
            if (oStates[oi].fullyUsed || rUsed[rj]) continue
            val otLen = origParas[oi].text.length
            val rtLen = revParas[rj].text.length
            val hasContainment = origParas[oi].text in revParas[rj].text ||
                                  revParas[rj].text in origParas[oi].text
            val lengthOk = (rtLen >= otLen * 0.4 || otLen >= rtLen * 0.4)
            if (!lengthOk && !hasContainment) continue
            ops.add(CmpOp("REP", oi, rj, 0.0))
            oStates[oi].fullyUsed = true
            rUsed[rj] = true
        }

        // ── Phase 2.5: Position-Proximity Fallback（位置邻近回退）──
        // 解决"orig 段落的前/后缀嵌入 rev 段落"导致无法匹配的问题。
        // 例: O2("通过测评...") 的前12字符 = R3 的后12字符，
        //     similarity=0.153 < 0.35 阈值 → 无法进入 Phase3 REP → 变成独立 DEL ❌
        // 修复：如果位置邻近(pos_diff≤2)且有首尾重叠(≥6ch)，强制配对为 REP。
        val proximityRepList = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until n) {
            if (oStates[i].fullyUsed) continue
            val oiText = origParas[i].text
            if (oiText.isEmpty()) continue

            var bestJ = -1
            var bestScore = -1
            var bestOverlap = 0

            for (j in 0 until m) {
                if (rUsed[j]) continue
                val rjText = revParas[j].text
                if (rjText.isEmpty()) continue

                val posDiff = kotlin.math.abs(i - j)
                val overlap = maxOverlap(oiText, rjText)

                if (overlap >= 6 && posDiff <= 2) {
                    val score = overlap * 3 + (3 - posDiff) * 5
                    if (score > bestScore) {
                        bestScore = score
                        bestJ = j
                        bestOverlap = overlap
                    }
                }
            }

            if (bestJ >= 0) {
                ops.add(CmpOp("REP", i, bestJ, 0.0))
                oStates[i].fullyUsed = true
                rUsed[bestJ] = true
                proximityRepList.add(Pair(i, bestJ))
            }
        }

        // ── Phase 4: DEL / INS ──
        // 被 SUB_EQ 部分消耗的 orig 段落：不再整体输出为 DEL（会与子串黑字重复），
        // 而是只输出未被子串占用的剩余片段为红字删除（delExclude 标记已占用区间）。
        for (i in 0 until n) {
            if (!oStates[i].fullyUsed && origParas[i].text.isNotEmpty()) {
                if (oStates[i].usedRanges.isNotEmpty()) {
                    // 部分消耗：输出剩余片段为红字删除
                    ops.add(CmpOp("DEL", i, -1, 0.0, delExclude = oStates[i].usedRanges.toList()))
                } else {
                    ops.add(CmpOp("DEL", i, -1, 0.0))
                }
            }
        }
        for (j in 0 until m) {
            if (!rUsed[j] && revParas[j].text.isNotEmpty()) {
                ops.add(CmpOp("INS", -1, j, 0.0))
            }
        }

        // ── 双指针归并排序（支持一对多：SUB_EQ 按 rj 索引）──
        return mergeInDocumentOrder(ops, n, m)
    }

    /**
     * 双指针归并：按文档逻辑顺序重排操作序列。
     * 以修订档段落顺序为骨架，支持一对多（同一 orig 可匹配多个 SUB_EQ）。
     * 核心原则：修订档顺序优先（INS/SUB_EQ 先于 DEL 输出）。
     */
    private fun mergeInDocumentOrder(ops: List<CmpOp>, n: Int, m: Int): List<CmpOp> {
        // 构建查找表
        val opByOi = mutableMapOf<Int, CmpOp>()     // oi → op (EQ/REP/DEL, 一对一)
        val opByRj = mutableMapOf<Int, CmpOp>()      // rj → op (所有类型, 一对一 by rj)
        for (op in ops) {
            if (op.oi >= 0 && op.tag != "SUB_EQ") opByOi[op.oi] = op  // SUB_EQ 不放入 oi 表（可能一对多）
            if (op.rj >= 0) opByRj[op.rj] = op
        }

        val result = mutableListOf<CmpOp>()
        var i = 0
        var j = 0
        while (i < n || j < m) {
            // 优先检查 Oi ↔ Rj 是否有匹配（EQ 或 REP）
            val matchedAtIj = if (i < n && j < m) {
                val op = opByOi[i]
                if (op != null && op.rj == j && op.tag in listOf("EQ", "REP")) op else null
            } else null

            if (matchedAtIj != null) {
                result.add(matchedAtIj)
                i++; j++
            } else if (j < m && opByRj[j]?.tag == "SUB_EQ") {
                // 修订档当前位置是子串匹配的全黑段落
                result.add(opByRj[j]!!)
                j++
            } else if (j < m && opByRj[j]?.tag == "INS") {
                // 修订档当前位置是新增段落 → 优先输出（修订档顺序驱动）
                result.add(opByRj[j]!!)
                j++
            } else if (i < n && opByOi[i]?.tag == "DEL") {
                // 原文档当前位置是被删除段落
                result.add(opByOi[i]!!)
                i++
            } else if (i < n && !opByOi.containsKey(i)) {
                i++
            } else if (j < m && !opByRj.containsKey(j)) {
                j++
            } else {
                if (j < m) j++ else if (i < n) i++ else break
            }
        }

        return result
    }

    /**
     * 两段文本的相似度（基于修正的 LCS 比率）。
     */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        // 快速路径：包含关系
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        if (longer.contains(shorter)) return shorter.length.toDouble() / longer.length
        // 标准 LCS 比率
        return lcsRatio(a, b)
    }

    /**
     * 计算两段文本的最大首尾重叠长度。
     * 用于位置邻近回退：当 orig 的前缀与 rev 的后缀重叠（或反之），
     * 说明两个段落可能是"首尾相接"关系，应配对为 REP 而非独立 DEL。
     */
    private fun maxOverlap(a: String, b: String): Int {
        val maxCheck = kotlin.math.min(kotlin.math.min(a.length, b.length), 30)
        // a 的前缀 = b 的后缀
        for (k in maxCheck downTo 1) {
            if (k <= b.length && a.startsWith(b.substring(b.length - k))) return k
        }
        // a 的后缀 = b 的前缀
        for (k in maxCheck downTo 1) {
            if (k <= a.length && k <= b.length && a.substring(a.length - k) == b.substring(0, k)) return k
        }
        return 0
    }

    /**
     * 两段落是否应作为「相似配对」参与内联修订：
     *  - 完全相同 → true
     *  - rev 整体包含于 orig：rev 近似等于 orig(≥70%) → true(内联)；否则交给 SUBEQ 处理
     *  - orig 整体包含于 rev：orig 明显更短(<70%) → false(删除+插入)；否则 true(小幅扩写)
     *  - 否则按字符 LCS 比率 ≥0.5
     */
    private fun isAligned(o: String, r: String): Boolean {
        if (o.isEmpty() || r.isEmpty()) return false
        if (o == r) return true
        if (o.contains(r)) {
            // r 整体包含于 o
            return r.length >= 0.7 * o.length
        }
        if (r.contains(o)) {
            // o 整体包含于 r
            if (o.length < 0.7 * r.length) return false
            return true
        }
        return lcsRatio(o, r) >= 0.5
    }

    private fun lcsRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 2.0 * lcsLen(a, b) / (a.length + b.length)
    }

    private fun lcsLen(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0 || n == 0) return 0
        if (m.toLong() * n <= 4_000_000L) {
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in 1..m) {
                val ai = a[i - 1]
                for (j in 1..n) {
                    dp[i][j] = if (ai == b[j - 1]) dp[i - 1][j - 1] + 1 else kotlin.math.max(dp[i - 1][j], dp[i][j - 1])
                }
            }
            return dp[m][n]
        } else {
            val dp = IntArray(n + 1)
            for (i in 0 until m) {
                val ai = a[i]
                var prev = 0
                for (j in 0 until n) {
                    val tmp = dp[j + 1]
                    dp[j + 1] = if (ai == b[j]) prev + 1 else kotlin.math.max(dp[j], tmp)
                    prev = tmp
                }
            }
            return dp[n]
        }
    }

    // ══════════════════════════════════════════════════════
    //  Diff 算法（标准 LCS DP + 回溯 → opcodes），支持相似度配对
    // ══════════════════════════════════════════════════════

    private data class Quad(val tag: String, val i1: Int, val i2: Int, val j1: Int, val j2: Int)

    /** 四元组，用于 buildDiffParagraphXml 返回 (xml, delChars, insChars, equalChars) */
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun computeDiffText(a: List<String>, b: List<String>, useSimilarity: Boolean): List<Quad> {
        val m = a.size
        val n = b.size
        if (m == 0) return if (n == 0) emptyList() else listOf(Quad("insert", 0, 0, 0, n))
        if (n == 0) return listOf(Quad("delete", 0, m, 0, 0))

        val eq: (String, String) -> Boolean = if (useSimilarity) ({ x, y -> isAligned(x, y) }) else ({ x, y -> x == y })

        if (m.toLong() * n.toLong() > 4_000_000L) return computeDiffChunked(a, b, eq)

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            val ai = a[i - 1]
            for (j in 1..n) {
                dp[i][j] = if (eq(ai, b[j - 1])) dp[i - 1][j - 1] + 1
                else kotlin.math.max(dp[i - 1][j], dp[i][j - 1])
            }
        }

        val revOps = mutableListOf<String>()
        var i = m; var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && eq(a[i - 1], b[j - 1]) -> { revOps.add("eq"); i--; j-- }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> { revOps.add("ins"); j-- }
                else -> { revOps.add("del"); i-- }
            }
        }

        revOps.reverse()
        val result = mutableListOf<Quad>()
        var idx = 0
        var ci = 0; var cj = 0
        while (idx < revOps.size) {
            when (revOps[idx]) {
                "eq" -> {
                    val si = ci; val sj = cj
                    while (idx < revOps.size && revOps[idx] == "eq") { ci++; cj++; idx++ }
                    result.add(Quad("equal", si, ci, sj, cj))
                }
                "ins" -> {
                    val sj = cj
                    while (idx < revOps.size && revOps[idx] == "ins") { cj++; idx++ }
                    result.add(Quad("insert", ci, ci, sj, cj))
                }
                "del" -> {
                    val si = ci
                    while (idx < revOps.size && revOps[idx] == "del") { ci++; idx++ }
                    result.add(Quad("delete", si, ci, cj, cj))
                }
                else -> idx++
            }
        }
        return result
    }

    private fun computeDiffChunked(a: List<String>, b: List<String>, eq: (String, String) -> Boolean): List<Quad> {
        val chunkSize = 500
        val result = mutableListOf<Quad>()
        var ai = 0; var bi = 0
        while (ai < a.size || bi < b.size) {
            val aEnd = kotlin.math.min(ai + chunkSize, a.size)
            val bEnd = kotlin.math.min(bi + chunkSize, b.size)
            val aChunk = a.subList(ai, aEnd)
            val bChunk = b.subList(bi, bEnd)
            if (aChunk.isEmpty() && bChunk.isNotEmpty()) {
                result.add(Quad("insert", ai, ai, bi, bEnd)); bi = bEnd; continue
            }
            if (bChunk.isEmpty() && aChunk.isNotEmpty()) {
                result.add(Quad("delete", ai, aEnd, bi, bi)); ai = aEnd; continue
            }
            if (aChunk.isEmpty() && bChunk.isEmpty()) break
            var ci = 0; var cji = 0
            while (ci < aChunk.size || cji < bChunk.size) {
                if (ci < aChunk.size && cji < bChunk.size && eq(aChunk[ci], bChunk[cji])) {
                    val si = ci; val sji = cji
                    while (ci < aChunk.size && cji < bChunk.size && eq(aChunk[ci], bChunk[cji])) { ci++; cji++ }
                    result.add(Quad("equal", ai + si, ai + ci, bi + sji, bi + cji))
                } else {
                    val lookAhead = kotlin.math.min(25, kotlin.math.min(aChunk.size - ci, bChunk.size - cji))
                    var found = false
                    search@ for (di in 0..lookAhead) {
                        for (dj in 0..lookAhead) {
                            if (di == 0 && dj == 0) continue
                            val ai2 = ci + di; val bj2 = cji + dj
                            if (ai2 < aChunk.size && bj2 < bChunk.size && eq(aChunk[ai2], bChunk[bj2])) {
                                if (dj == 0) { result.add(Quad("delete", ai + ci, ai + ai2, bi + cji, bi + cji)); ci = ai2 }
                                else if (di == 0) { result.add(Quad("insert", ai + ci, ai + ci, bi + cji, bi + bj2)); cji = bj2 }
                                else { result.add(Quad("replace", ai + ci, ai + ai2, bi + cji, bi + bj2)); ci = ai2; cji = bj2 }
                                found = true; break@search
                            }
                        }
                    }
                    if (!found) {
                        if (ci < aChunk.size || cji < bChunk.size)
                            result.add(Quad("replace", ai + ci, ai + aChunk.size, bi + cji, bi + bChunk.size))
                        ci = aChunk.size; cji = bChunk.size
                    }
                }
            }
            ai = aEnd; bi = bEnd
        }
        return result
    }

    // ══════════════════════════════════════════════════════
    //  修改段落：字符级内联 diff（保留原格式）
    // ══════════════════════════════════════════════════════

    private fun buildDiffParagraphXml(
        origXml: String, origText: String, revText: String,
        author: String, date: String, ridSeq: IntArray
    ): Quadruple<String, Int, Int, Int> {
        val runs = extractWRuns(origXml)
        val ops = charDiff(origText, revText)

        val sb = StringBuilder("<w:p>")
        var delChars = 0
        var insChars = 0
        var equalChars = 0
        for (op in ops) {
            when (op.tag) {
                "equal" -> {
                    val seg = extractRunsInRange(runs, op.i1, op.i2)
                    if (seg.isNotEmpty()) sb.append(seg)
                    equalChars += (op.i2 - op.i1)
                }
                "delete" -> {
                    val seg = extractRunsInRange(runs, op.i1, op.i2)
                    if (seg.isNotEmpty()) {
                        val delSeg = toDelText(seg)
                        sb.append("<w:del w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">")
                        sb.append(delSeg)
                        sb.append("</w:del>")
                        delChars += (op.i2 - op.i1)
                    }
                }
                "insert" -> {
                    val seg = revText.substring(op.j1, op.j2)
                    if (seg.isNotEmpty()) {
                        sb.append("<w:ins w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\"><w:r><w:rPr><w:color w:val=\"2E74B5\"/><w:u w:val=\"single\"/></w:rPr><w:t xml:space=\"preserve\">${escapeXml(seg)}</w:t></w:r></w:ins>")
                        insChars += (op.j2 - op.j1)
                    }
                }
                "replace" -> {
                    val dSeg = extractRunsInRange(runs, op.i1, op.i2)
                    if (dSeg.isNotEmpty()) {
                        val delDSeg = toDelText(dSeg)
                        sb.append("<w:del w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">")
                        sb.append(delDSeg)
                        sb.append("</w:del>")
                        delChars += (op.i2 - op.i1)
                    }
                    val iSeg = revText.substring(op.j1, op.j2)
                    if (iSeg.isNotEmpty()) {
                        sb.append("<w:ins w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\"><w:r><w:rPr><w:color w:val=\"2E74B5\"/><w:u w:val=\"single\"/></w:rPr><w:t xml:space=\"preserve\">${escapeXml(iSeg)}</w:t></w:r></w:ins>")
                        insChars += (op.j2 - op.j1)
                    }
                }
            }
        }
        sb.append("</w:p>")
        return Quadruple(sb.toString(), delChars, insChars, equalChars)
    }

    private fun charDiff(textO: String, textR: String): List<Quad> {
        val listO = textO.map { it.toString() }
        val listR = textR.map { it.toString() }
        return computeDiffText(listO, listR, false)
    }

    // ══════════════════════════════════════════════════════
    //  DOCX 段落解析（保留完整 XML）
    // ══════════════════════════════════════════════════════

    private fun readParagraphs(docx: File, label: String): List<Para> {
        val paras = mutableListOf<Para>()
        try {
            ZipFile(docx).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return emptyList()
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                val paraXmls = extractTopLevelParas(xml)
                for (px in paraXmls) {
                    val text = extractParaText(px)
                    paras.add(Para(px, text))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readParagraphs($label) error: ${e.javaClass.simpleName}: ${e.message}")
        }
        Log.d(TAG, "readParagraphs($label): ${paras.size} 段落")
        return paras
    }

    private fun extractTopLevelParas(xml: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        val n = xml.length
        while (i < n) {
            val open = xml.indexOf("<w:p", i)
            if (open < 0) break
            val after = if (open + 4 < n) xml[open + 4] else '>'
            if (after != ' ' && after != '>' && after != '/' && after != '\t' && after != '\n') {
                i = open + 4
                continue
            }
            var depth = 0
            var k = open
            var closeIdx = -1
            while (k < n) {
                if (k + 4 < n && xml.startsWith("<w:p", k)) {
                    val a = xml[k + 4]
                    if (a == ' ' || a == '>' || a == '/' || a == '\t' || a == '\n') {
                        depth++
                        val gt = xml.indexOf('>', k)
                        if (gt < 0) break
                        k = gt + 1
                        continue
                    }
                }
                if (xml.startsWith("</w:p>", k)) {
                    depth--
                    if (depth == 0) { closeIdx = k + 6; break }
                    k += 6
                    continue
                }
                k++
            }
            if (closeIdx < 0) break
            result.add(xml.substring(open, closeIdx))
            i = closeIdx
        }
        return result
    }

    private fun extractParaText(paraXml: String): String {
        val sb = StringBuilder()
        val m = Regex("<w:t(?![a-zA-Z])[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL).findAll(paraXml)
        for (it in m) sb.append(it.groupValues[1])
        return sb.toString()
    }

    private fun extractWRuns(paraXml: String): List<WRun> {
        val runs = mutableListOf<WRun>()
        var offset = 0
        var i = 0
        val n = paraXml.length
        while (i < n) {
            val rIdx = paraXml.indexOf("<w:r", i)
            if (rIdx < 0) break
            val endIdx = paraXml.indexOf("</w:r>", rIdx)
            if (endIdx < 0) break
            val runXml = paraXml.substring(rIdx, endIdx + 6)
            val tMatch = Regex("<w:t(?![a-zA-Z])[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL).find(runXml)
            val text = tMatch?.groupValues?.get(1) ?: ""
            if (text.isNotEmpty()) {
                runs.add(WRun(offset, offset + text.length, runXml))
                offset += text.length
            }
            i = endIdx + 6
        }
        return runs
    }

    private fun extractRunsInRange(runs: List<WRun>, start: Int, end: Int): String {
        if (start >= end) return ""
        val sb = StringBuilder()
        for (run in runs) {
            if (run.end <= start || run.start >= end) continue
            val overlapStart = kotlin.math.max(run.start, start)
            val overlapEnd = kotlin.math.min(run.end, end)
            val runText = extractRunText(run.runXml)
            if (runText.isEmpty()) continue
            val sub = runText.substring(overlapStart - run.start, overlapEnd - run.start)
            if (sub.isEmpty()) continue
            val rPr = extractRPr(run.runXml)
            sb.append("<w:r>")
            if (rPr.isNotEmpty()) sb.append(rPr)
            sb.append("<w:t xml:space=\"preserve\">${escapeXml(sub)}</w:t>")
            sb.append("</w:r>")
        }
        return sb.toString()
    }

    private fun extractRunText(runXml: String): String {
        val m = Regex("<w:t(?![a-zA-Z])[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL).find(runXml)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun extractRPr(runXml: String): String {
        val m = Regex("<w:rPr.*?</w:rPr>", RegexOption.DOT_MATCHES_ALL).find(runXml)
        return m?.value ?: ""
    }

    // ══════════════════════════════════════════════════════
    //  整段 删除/插入 包裹
    // ══════════════════════════════════════════════════════

    private fun wrapDeletedParagraph(paraXml: String, author: String, date: String, ridSeq: IntArray): String {
        val pPr = extractPPr(paraXml)
        val inner = extractParaInner(paraXml)
        val delInner = toDelText(inner)
        return "<w:p>$pPr<w:del w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">$delInner</w:del></w:p>"
    }

    private fun wrapInsertedParagraph(paraXml: String, author: String, date: String, ridSeq: IntArray): String {
        val pPr = extractPPr(paraXml)
        val inner = extractParaInner(paraXml)
        return "<w:p>$pPr<w:ins w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">$inner</w:ins></w:p>"
    }

    /**
     * 部分删除：原文档大段被拆出若干子段(SUB_EQ)后，剩余片段按红字删除输出。
     * exclude 标记已被子段占用的区间，只输出其补集区间为红字。
     */
    private fun wrapPartialDeletedParagraph(paraXml: String, exclude: List<Pair<Int, Int>>, author: String, date: String, ridSeq: IntArray): String {
        val pPr = extractPPr(paraXml)
        val runs = extractWRuns(paraXml)
        val totalLen = extractParaText(paraXml).length
        val kept = complementRanges(exclude, totalLen)
        val sb = StringBuilder("<w:p>$pPr")
        for ((s, e) in kept) {
            if (e <= s) continue
            val seg = extractRunsInRange(runs, s, e)
            if (seg.isNotEmpty()) {
                val delSeg = toDelText(seg)
                sb.append("<w:del w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">$delSeg</w:del>")
            }
        }
        sb.append("</w:p>")
        return sb.toString()
    }

    /** 将 <w:t>...</w:t> 转为 <w:delText>...</w:delText>，用于删除标记内部。 */
    private fun toDelText(xml: String): String {
        return xml.replace("<w:t", "<w:delText").replace("</w:t>", "</w:delText>")
    }

    /** 计算 [0,total) 中排除 exclude 区间后的补集区间（升序、不重叠）。 */
    private fun complementRanges(exclude: List<Pair<Int, Int>>, total: Int): List<Pair<Int, Int>> {
        if (exclude.isEmpty()) return listOf(Pair(0, total))
        val sorted = exclude.sortedBy { it.first }
        val res = mutableListOf<Pair<Int, Int>>()
        var cur = 0
        for ((s, e) in sorted) {
            if (s > cur) res.add(Pair(cur, s))
            cur = kotlin.math.max(cur, e)
        }
        if (cur < total) res.add(Pair(cur, total))
        return res
    }

    private fun wrapDeletedText(origXml: String, gap: String, author: String, date: String, ridSeq: IntArray): String {
        val pPr = extractPPr(origXml)
        val rPr = extractFirstRPr(origXml)
        return "<w:p>$pPr<w:del w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\"><w:r>$rPr<w:delText xml:space=\"preserve\">${escapeXml(gap)}</w:delText></w:r></w:del></w:p>"
    }

    private fun extractPPr(paraXml: String): String {
        val m = Regex("<w:pPr.*?</w:pPr>", RegexOption.DOT_MATCHES_ALL).find(paraXml)
        return m?.value ?: ""
    }

    private fun extractFirstRPr(paraXml: String): String {
        return extractRPr(paraXml)
    }

    private fun extractParaInner(paraXml: String): String {
        var s = paraXml
        val openMatch = Regex("<w:p[^>]*>").find(s)
        if (openMatch != null) s = s.substring(openMatch.range.last + 1)
        val closeIdx = s.lastIndexOf("</w:p>")
        if (closeIdx >= 0) s = s.substring(0, closeIdx)
        s = Regex("<w:pPr.*?</w:pPr>", RegexOption.DOT_MATCHES_ALL).replace(s, "")
        return s
    }

    // ══════════════════════════════════════════════════════
    //  OOXML 工具
    // ══════════════════════════════════════════════════════

    private fun nextRid(seq: IntArray): Int { seq[0]++; return seq[0] }

    private fun escapeXml(text: String): String {
        var last = 0
        val sb = StringBuilder(text.length + 16)
        for (i in text.indices) {
            val c = text[i]
            val replacement = when (c) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                else -> null
            }
            if (replacement != null) {
                if (last < i) sb.append(text, last, i)
                sb.append(replacement); last = i + 1
            }
        }
        if (last < text.length) sb.append(text, last, text.length)
        return sb.toString()
    }

    // ══════════════════════════════════════════════════════
    //  输出 DOCX 写入（保留原文档模板）
    // ══════════════════════════════════════════════════════

    private fun writeOutputDocx(origDocx: File, outPath: String, bodyXmlParts: List<String>) {
        val bodyContent = bodyXmlParts.joinToString("\n")
        ZipFile(origDocx).use { zin ->
            ZipOutputStream(File(outPath).outputStream()).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    when (entry.name) {
                        "word/document.xml" -> {
                            val origDoc = zin.getInputStream(entry).bufferedReader().readText()
                            val newDoc = replaceBody(origDoc, bodyContent)
                            zout.putNextEntry(ZipEntry("word/document.xml"))
                            zout.write(newDoc.toByteArray(Charsets.UTF_8))
                            zout.closeEntry()
                        }
                        "word/settings.xml" -> {
                            val settingsXml = zin.getInputStream(entry).bufferedReader().readText()
                            zout.putNextEntry(ZipEntry("word/settings.xml"))
                            zout.write(ensureTrackRevisions(settingsXml).toByteArray(Charsets.UTF_8))
                            zout.closeEntry()
                        }
                        else -> {
                            zout.putNextEntry(ZipEntry(entry.name))
                            zin.getInputStream(entry).copyTo(zout)
                            zout.closeEntry()
                        }
                    }
                }
            }
        }
    }

    private fun replaceBody(origDoc: String, bodyContent: String): String {
        val bodyOpen = origDoc.indexOf("<w:body")
        if (bodyOpen < 0) return origDoc
        val gt = origDoc.indexOf('>', bodyOpen)
        if (gt < 0) return origDoc
        val bodyStart = gt + 1
        val bodyClose = origDoc.lastIndexOf("</w:body>")
        if (bodyClose < 0) return origDoc
        val sectPrMatch = Regex("<w:sectPr.*?</w:sectPr>", RegexOption.DOT_MATCHES_ALL).find(origDoc)
        val sectPr = sectPrMatch?.value ?: ""
        return origDoc.substring(0, bodyStart) + "\n" + bodyContent + "\n" + sectPr + "\n" + origDoc.substring(bodyClose)
    }

    private fun ensureTrackRevisions(originalSettings: String): String {
        return if (originalSettings.contains("w:trackRevisions")) originalSettings
        else originalSettings.replace("</w:settings>", "<w:trackRevisions w:val=\"true\"/></w:settings>")
    }
}
