package com.henry.wordcount

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.text.Regex
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.usermodel.CharacterRun
import org.apache.poi.hwpf.usermodel.Paragraph
import org.apache.poi.hwpf.usermodel.Range

/**
 * 纯 Kotlin 实现的 DOCX 文档比较器（v1.2.1：旧版 .doc 转换保留缩进与字号）。
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

    /** 段落（或表格行）*/
    data class Para(val xml: String, val text: String, val isTableRow: Boolean = false)
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

        // ── .doc (OLE2) 自动转 .docx ──
        val tempFiles = mutableListOf<String>()
        val actualOrig = if (isOle2File(origPath)) {
            val converted = convertDocToMinimalDocx(origPath)
                ?: return CompareResult(ok = false, error = "无法读取原文档(旧版.doc格式转换失败): $origPath")
            tempFiles.add(converted)
            converted
        } else origPath
        val actualRev = if (isOle2File(revPath)) {
            val converted = convertDocToMinimalDocx(revPath)
                ?: return CompareResult(ok = false, error = "无法读取修订文档(旧版.doc格式转换失败): $revPath")
            tempFiles.add(converted)
            converted
        } else revPath

        val opts = try { org.json.JSONObject(optsJson) } catch (_: Exception) { org.json.JSONObject() }
        val author = "WordCount"
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

        val origParas = readParagraphs(File(actualOrig), "orig")
        val revParas = readParagraphs(File(actualRev), "rev")

        val ops = alignParagraphs(origParas, revParas)

        // ── 构建输出 body ──
        // 修订档字体/大小作为统一规范：所有结果文字使用修订档的字体与字号
        val fontRPr = buildFontRPr(revParas)
        val ridSeq = intArrayOf(0)
        var insCount = 0
        var delCount = 0
        var repCount = 0
        var totalInsChars = 0       // 新增字符（计入修改字数）
        var totalDelChars = 0       // 删除字符（仅统计，不计入modifiedChars）
        val bodyParts = mutableListOf<String>()

        for (op in ops) {
            // v1.3.16: 表格行（isTableRow）保留原始 <w:tr> XML，不套用段落级包装
            val isRevTableRow = op.rj >= 0 && revParas[op.rj].isTableRow
            val isOrigTableRow = op.oi >= 0 && origParas[op.oi].isTableRow

            when (op.tag) {
                "EQ" -> {
                    // 完全相同段落 → 黑字不变（表格行保留原始 <w:tr> 格式）
                    bodyParts.add(revParas[op.rj].xml)
                }
                "SUB_EQ" -> {
                    // 子串匹配段 → 按修订档原样输出
                    bodyParts.add(revParas[op.rj].xml)
                }
                "DEL" -> {
                    // 原档中被删除的段落/行
                    if (origParas[op.oi].text.isNotEmpty()) {
                        if (isOrigTableRow) {
                            // 表格行删除：保留原始 <w:tr> 结构，整行标红
                            bodyParts.add(wrapDeletedTableRow(origParas[op.oi].xml, author, date, ridSeq))
                        } else if (op.delExclude != null) {
                            bodyParts.add(wrapPartialDeletedParagraph(origParas[op.oi].xml, op.delExclude, author, date, ridSeq, fontRPr))
                        } else {
                            bodyParts.add(wrapDeletedParagraph(origParas[op.oi].xml, author, date, ridSeq, fontRPr))
                        }
                        delCount++
                        totalDelChars += origParas[op.oi].text.length
                    }
                }
                "INS" -> {
                    // 修订档中新插入的段落/行
                    if (isRevTableRow) {
                        // 表格行插入：保留原始 <w:tr> 结构，整行标蓝
                        bodyParts.add(wrapInsertedTableRow(revParas[op.rj].xml, author, date, ridSeq))
                    } else {
                        bodyParts.add(wrapInsertedParagraph(revParas[op.rj].xml, author, date, ridSeq))
                    }
                    insCount++
                    totalInsChars += revParas[op.rj].text.length
                }
                "REP" -> {
                    // 相似段落 → 内联字符级 diff
                    if (isRevTableRow) {
                        // v1.3.16: 表格行修改 → 输出修订版 <w:tr>（保留完整格式）
                        // TODO: 未来可做单元格级 diff（需解析 <w:tc> 逐格比对）
                        bodyParts.add(revParas[op.rj].xml)
                    } else {
                        val diff = buildDiffParagraphXml(
                            origParas[op.oi].xml,
                            origParas[op.oi].text,
                            revParas[op.rj].xml,
                            revParas[op.rj].text,
                            author, date, ridSeq, fontRPr
                        )
                        bodyParts.add(diff.xml)
                        totalDelChars += diff.delChars
                        totalInsChars += diff.insChars
                    }
                    repCount++
                }
            }
        }

        writeOutputDocx(File(actualOrig), outPath, bodyParts)

        // 涉及修改的句子总字数 = 修订档总字数 − 黑色整句字数（修订档视角）
        // v1.3.9 修复：此前 totalChars 从结果文档（原文+修订合并，含标记）计算，
        //   导致修改字数可能超过修订版总字数（结果文档天然比任一原始文档大）。
        //   现改为从修订档(actualRev)本身取总字数，保证 modifiedChars ≤ 修订档总字数。
        val revTotalChars = computeRevDocTotalChars(actualRev)
        val resultStats = computeResultDocStats(outPath)
        val modifiedChars = kotlin.math.max(0, revTotalChars - resultStats.blackWholeSentenceChars)
        val summary = buildString {
            append("插入 $insCount 处(${totalInsChars}字) | 删除 $delCount 处(${totalDelChars}字) | 修改 $repCount 处")
        }

        Log.d(TAG, "result: ins=$insCount(${totalInsChars}字) del=$delCount(${totalDelChars}字) rep=$repCount chars=$modifiedChars (total=${resultStats.totalChars} blackWhole=${resultStats.blackWholeSentenceChars})")

        // 清理 .doc 转换产生的临时 .docx 文件
        for (tp in tempFiles) {
            runCatching { File(tp).delete() }
        }

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

    /** buildDiffParagraphXml 的返回：xml + 各类字符数 + 黑色整句字数 */
    private data class DiffOut(
        val xml: String,
        val delChars: Int,
        val insChars: Int,
        val eqChars: Int,
        val blackWholeSentenceChars: Int
    )

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
        origXml: String, origText: String,
        revXml: String, revText: String,
        author: String, date: String, ridSeq: IntArray, fontRPr: String
    ): DiffOut {
        val runs = extractWRuns(origXml)
        val ops = charDiff(origText, revText)
        // 段落缩进/对齐等格式取自【修订后的文档】，使结果文档与修订档外观一致
        // （如果修订档该段没有缩进、原文档有，则回退原文档缩进）
        val revPPr = extractPPr(revXml)
        val pPr = if (revPPr.isNotEmpty()) revPPr else extractPPr(origXml)

        val sb = StringBuilder("<w:p>$pPr")
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
        // 收集「黑色」范围（rev 坐标）：equal 段在结果中为黑字
        val blackRanges = mutableListOf<Pair<Int, Int>>()
        for (op in ops) {
            if (op.tag == "equal") blackRanges.add(Pair(op.j1, op.j2))
        }
        val blackWhole = computeBlackWholeSentences(revText, blackRanges)
        return DiffOut(restampFont(sb.toString(), fontRPr), delChars, insChars, equalChars, blackWhole)
    }

    private fun charDiff(textO: String, textR: String): List<Quad> {
        val listO = textO.map { it.toString() }
        val listR = textR.map { it.toString() }
        return computeDiffText(listO, listR, false)
    }

    // ══════════════════════════════════════════════════════
    //  DOCX 段落解析（保留完整 XML）
    // ══════════════════════════════════════════════════════

    /**
     * v1.3.14: 提取文档全部段落（含表格内的段落）。
     * 此前版本只提取 <w:p>，完全忽略 <w:tbl> 表格，
     * 导致问卷等表格布局文档的大量内容丢失（Section C/D 无内容、字数严重偏低）。
     *
     * 策略：对表格内段落，把每个单元格的文本用空格拼接为一行，
     * 作为伪段落参与比对。这样既保留内容又不破坏逐段比对逻辑。
     */
    private fun readParagraphs(docx: File, label: String): List<Para> {
        val paras = mutableListOf<Para>()
        try {
            ZipFile(docx).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return emptyList()
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                // 提取 body 区域
                val bodyStart = xml.indexOf("<w:body")
                if (bodyStart < 0) return emptyList()
                val bodyGt = xml.indexOf('>', bodyStart)
                if (bodyGt < 0) return emptyList()
                val bodyEnd = xml.lastIndexOf("</w:body>")
                if (bodyEnd < 0) return emptyList()
                val bodyContent = xml.substring(bodyGt + 1, bodyEnd)

                // 1. 提取顶层段落 + 表格内段落
                extractParasAndTableParas(bodyContent, paras)
            }
        } catch (e: Exception) {
            Log.e(TAG, "readParagraphs($label) error: ${e.javaClass.simpleName}: ${e.message}")
        }
        Log.d(TAG, "readParagraphs($label): ${paras.size} 段落")
        return paras
    }

    /**
     * 从 body 内容中提取所有段落：
     * - 顶层 <w:p> 原样保留
     * - <w:tbl> 表格内每个单元格的段落拼接为伪段落（单元格间用空格分隔）
     */
    private fun extractParasAndTableParas(content: String, result: MutableList<Para>) {
        var i = 0
        val n = content.length
        while (i < n) {
            // 检查是否遇到表格
            if (content.startsWith("<w:tbl", i)) {
                val tblClose = findMatchingTag(content, i, "w:tbl")
                if (tblClose > 0) {
                    val tblContent = content.substring(i, tblClose)
                    extractTableParagraphs(tblContent, result)
                    i = tblClose
                    continue
                }
            }
            // 顶层段落
            if (content.startsWith("<w:p", i)) {
                val after = if (i + 4 < n) content[i + 4] else '>'
                if (after == ' ' || after == '>' || after == '/' || after == '\t' || after == '\n') {
                    val pClose = findMatchingTag(content, i, "w:p")
                    if (pClose > 0) {
                        val pXml = content.substring(i, pClose)
                        val text = extractParaText(pXml)
                        result.add(Para(pXml, text))
                        i = pClose
                        continue
                    }
                }
            }
            i++
        }
    }

    /**
     * 从表格 XML 中提取每行的拼接文本作为伪段落。
     * 每行 = 各单元格文本用空格连接（保持原横排阅读顺序）。
     *
     * v1.3.15 修复：此前用 i+4 检查分隔符，但 w:tr/w:tc 的标签名长度为4（"w:tr"），
     * i+4 仍在标签名内部（指向 'r' 或 'c'），导致永远匹配不到表格行和单元格！
     * 现改为 i+5（标签名长度+1=5）正确检查分隔符。
     */
    private fun extractTableParagraphs(tblXml: String, result: MutableList<Para>) {
        // 找所有 <w:tr> (表格行)
        var i = 0
        val n = tblXml.length
        while (i < n) {
            if (tblXml.startsWith("<w:tr", i)) {
                // v1.3.15: "w:tr" 长度4，分隔符在 i+5（不是 i+4）
                val after = if (i + 5 < n) tblXml[i + 5] else '>'
                if (after == ' ' || after == '>' || after == '/' || after == '\t' || after == '\n') {
                    val trClose = findMatchingTag(tblXml, i, "w:tr")
                    if (trClose > 0) {
                        val trContent = tblXml.substring(i, trClose)
                        // 提取该行所有单元格的文本
                        val cellTexts = mutableListOf<String>()
                        var j = 0
                        val trLen = trContent.length
                        while (j < trLen) {
                            if (trContent.startsWith("<w:tc", j)) {
                                // v1.3.15: "w:tc" 长度4，分隔符在 j+5
                                val tcAfter = if (j + 5 < trLen) trContent[j + 5] else '>'
                                if (tcAfter == ' ' || tcAfter == '>' || tcAfter == '/' || tcAfter == '\t' || tcAfter == '\n') {
                                    val tcClose = findMatchingTag(trContent, j, "w:tc")
                                    if (tcClose > 0) {
                                        val tcXml = trContent.substring(j, tcClose)
                                        // 提取单元格内所有 <w:t> 文本
                                        val cellTm = Regex("<w:t(?![a-zA-Z])[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL).findAll(tcXml)
                                        val sb = StringBuilder()
                                        for (tm in cellTm) sb.append(tm.groupValues[1])
                                        val txt = sb.toString().trim()
                                        if (txt.isNotEmpty()) cellTexts.add(txt)
                                        j = tcClose
                                        continue
                                    }
                                }
                            }
                            j++
                        }
                        if (cellTexts.isNotEmpty()) {
                            val joined = cellTexts.joinToString(" ")
                            // v1.3.16: 保留原始 <w:tr> XML（含完整格式），不再生成假 <w:p>
                            // 之前用 fakePXml 导致结果文档丢失所有表格格式（字体/颜色/边框/网格）
                            result.add(Para(trContent, joined, isTableRow = true))
                        }
                        i = trClose
                        continue
                    }
                }
            }
            i++
        }
    }

    /**
     * 查找匹配的闭合标签位置（处理嵌套）。
     * @param xml 源 XML
     * @param start 起始标签的开始位置
     * @param tagName 标签名（不含尖括号和前缀分隔符，如 "w:p" 或 "w:tbl"）
     * @return 闭合标签之后的位置（> 之后），未找到返回 -1
     */
    private fun findMatchingTag(xml: String, start: Int, tagName: String): Int {
        val openTag = "<$tagName"
        val closeTag = "</$tagName>"
        val n = xml.length
        // 找到起始标签的 >
        var k = start
        var depth = 0
        // 先跳过起始标签本身
        val gt = xml.indexOf('>', k)
        if (gt < 0) return -1
        k = gt + 1
        depth = 1
        while (k < n) {
            if (xml.startsWith(openTag, k)) {
                val a = if (k + openTag.length < n) xml[k + openTag.length] else '>'
                if (a == ' ' || a == '>' || a == '/' || a == '\t' || a == '\n') {
                    depth++
                    val g = xml.indexOf('>', k)
                    if (g < 0) return -1
                    k = g + 1
                    continue
                }
            }
            if (xml.startsWith(closeTag, k)) {
                depth--
                if (depth == 0) return k + closeTag.length
                k += closeTag.length
                continue
            }
            k++
        }
        return -1
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

    private fun wrapDeletedParagraph(paraXml: String, author: String, date: String, ridSeq: IntArray, fontRPr: String): String {
        val pPr = extractPPr(paraXml)
        val inner = extractParaInner(paraXml)
        val delInner = toDelText(inner)
        val xml = "<w:p>$pPr<w:del w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">$delInner</w:del></w:p>"
        return restampFont(xml, fontRPr)
    }

    private fun wrapInsertedParagraph(paraXml: String, author: String, date: String, ridSeq: IntArray): String {
        val pPr = extractPPr(paraXml)
        val inner = extractParaInner(paraXml)
        return "<w:p>$pPr<w:ins w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">$inner</w:ins></w:p>"
    }

    // ══════════════════════════════════════════════════════
    //  v1.3.16: 表格行级修订标记（保留 <w:tr> 完整结构）
    // ══════════════════════════════════════════════════════

    /**
     * 将表格行标记为删除（红字/删除线），保留原始 <w:tr><w:tc> 结构和格式。
     * 策略：在每个 <w:tc> 内的段落前插入 <w:del> 标记。
     */
    private fun wrapDeletedTableRow(trXml: String, author: String, date: String, ridSeq: IntArray): String {
        // 在每个 <w:tc> 中，把 <w:pr> 后的内容包进 <w:del>
        return trXml.replace(Regex("(<w:tc[^>]*>)"), "$1<w:del w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">")
            .replace(Regex("(</w:tc>)"), "</w:del>$1")
    }

    /**
     * 将表格行标记为插入（蓝字/下划线），保留原始 <w:tr><w:tc> 结构和格式。
     */
    private fun wrapInsertedTableRow(trXml: String, author: String, date: String, ridSeq: IntArray): String {
        return trXml.replace(Regex("(<w:tc[^>]*>)"), "$1<w:ins w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">")
            .replace(Regex("(</w:tc>)"), "</w:ins>$1")
    }

    /**
     * 部分删除：原文档大段被拆出若干子段(SUB_EQ)后，剩余片段按红字删除输出。
     * exclude 标记已被子段占用的区间，只输出其补集区间为红字。
     */
    private fun wrapPartialDeletedParagraph(paraXml: String, exclude: List<Pair<Int, Int>>, author: String, date: String, ridSeq: IntArray, fontRPr: String): String {
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
        return restampFont(sb.toString(), fontRPr)
    }

    /** 将 <w:t>...</w:t> 转为 <w:delText>...</w:delText>，用于删除标记内部。 */
    private fun toDelText(xml: String): String {
        return xml.replace("<w:t", "<w:delText").replace("</w:t>", "</w:delText>")
    }

    /**
     * 从修订档提取统一字体规范（rFonts + sz + szCs）：取出现频率最高的运行属性，
     * 仅保留字体与字号（去掉颜色/下划线/加粗等），用于让结果全文使用修订档字体。
     */
    private fun buildFontRPr(revParas: List<Para>): String {
        val freq = mutableMapOf<String, Int>()
        for (p in revParas) {
            for (r in extractWRuns(p.xml)) {
                val rPr = extractRPr(r.runXml)
                if (rPr.isEmpty()) continue
                val sb = StringBuilder()
                for (tag in listOf("w:rFonts", "w:sz", "w:szCs")) {
                    val m = Regex("<$tag\\b[^>]*/>").find(rPr)
                        ?: Regex("<$tag\\b[^>]*>.*?</$tag>", RegexOption.DOT_MATCHES_ALL).find(rPr)
                    if (m != null) sb.append(m.value)
                }
                if (sb.isNotEmpty()) {
                    val key = sb.toString()
                    freq[key] = (freq[key] ?: 0) + 1
                }
            }
        }
        if (freq.isEmpty()) return ""
        return "<w:rPr>${freq.maxByOrNull { it.value }?.key ?: ""}</w:rPr>"
    }

    /**
     * 将 xml 内所有 <w:r> 的运行属性统一为修订档字体(fontRPr)，
     * 同时保留原运行中的颜色/下划线/加粗/斜体（如红字删除、蓝字插入的标记）。
     */
    private fun restampFont(xml: String, fontRPr: String): String {
        if (fontRPr.isEmpty()) return xml
        return Regex("(<w:r>)(.*?)(</w:r>)", RegexOption.DOT_MATCHES_ALL).replace(xml) { m ->
            val inner = m.groupValues[2]
            val rPrMatch = Regex("<w:rPr.*?</w:rPr>", RegexOption.DOT_MATCHES_ALL).find(inner)
            val newRPr = if (rPrMatch != null) mergeFontRPr(rPrMatch.value, fontRPr) else fontRPr
            val newInner = if (rPrMatch != null) inner.replace(rPrMatch.value, newRPr) else "$newRPr$inner"
            "<w:r>$newInner</w:r>"
        }
    }

    /** 合并：以 fontRPr 的字体/字号为主，保留原 rPr 中的颜色/下划线/加粗/斜体。 */
    private fun mergeFontRPr(origRPr: String, fontRPr: String): String {
        val extras = StringBuilder()
        for (tag in listOf("w:color", "w:u", "w:b", "w:i", "w:highlight")) {
            val m = Regex("<$tag\\b[^>]*/>").find(origRPr)
                ?: Regex("<$tag\\b[^>]*>.*?</$tag>", RegexOption.DOT_MATCHES_ALL).find(origRPr)
            if (m != null) extras.append(m.value)
        }
        val fontInner = fontRPr.removePrefix("<w:rPr>").removeSuffix("</w:rPr>")
        return "<w:rPr>$fontInner$extras</w:rPr>"
    }

    /**
     * 计算段落中「完整黑色句子」的字符数。
     * blackRanges 为 rev 坐标下结果为黑字的区间（来自 equal 段）。
     * 仅当某个句子整体落在黑字区间内（无任何红/蓝标记）才计入，
     * 否则整句视为修改、不计入减法。
     */
    private fun computeBlackWholeSentences(text: String, blackRanges: List<Pair<Int, Int>>): Int {
        if (blackRanges.isEmpty() || text.isEmpty()) return 0
        val merged = mergeRanges(blackRanges.sortedBy { it.first })
        val delim = setOf('。', '！', '？', '；', '\n')
        var count = 0
        var s = 0
        for (idx in text.indices) {
            if (text[idx] in delim) {
                val e = idx + 1
                if (e > s && fullyCovered(s, e, merged)) count += (e - s)
                s = e
            }
        }
        if (text.length > s && fullyCovered(s, text.length, merged)) count += (text.length - s)
        return count
    }

    private fun mergeRanges(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val res = mutableListOf<Pair<Int, Int>>()
        var cs = ranges[0].first
        var ce = ranges[0].second
        for (k in 1 until ranges.size) {
            if (ranges[k].first <= ce) ce = kotlin.math.max(ce, ranges[k].second)
            else { res.add(cs to ce); cs = ranges[k].first; ce = ranges[k].second }
        }
        res.add(cs to ce)
        return res
    }

    private fun fullyCovered(s: Int, e: Int, merged: List<Pair<Int, Int>>): Boolean {
        if (e <= s) return false
        var pos = s
        for ((a, b) in merged) {
            if (a > pos) return false
            if (b >= e) return true
            if (b > pos) pos = b
        }
        return false
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
    //  结果文档字数统计（直接从输出 XML 解析，与用户看到的一致）
    // ══════════════════════════════════════════════════════

    private data class ResultDocStats(
        val totalChars: Int,                 // 修订侧总字数（<w:t> 黑+蓝，不含删除、不含空格）
        val blackWholeSentenceChars: Int     // 无 <w:ins> 句子中的黑字(<w:t>非插入)字数（不含空格）
    )

    /**
     * v1.3.15: 统计修订档本身的总字数（与 App 统一口径一致：fe + nc）。
     * 用于计算"涉及修改的句子总字数 = 修订档总字数 − 黑色整句字数"。
     *
     * 修复历史：
     *   v1.3.9: Regex(DOT_MATCHES_ALL) 匹配 <w:t> → 大文档返回 30 万+（虚高）
     *   v1.3.10: 改用 XmlPullParser，只取 <w:body> 内 <w:p> 段落中的 <w:t>
     *   v1.3.13: Android KXmlParser parser.name 返回带前缀名 → substringAfterLast(':')
     *   v1.3.15: 发现只统计 <w:p> 忽略 <w:tbl> 表格！问卷 Section B/C/D 全在表格内
     *           → revTotalChars 严重偏低。现同时提取表格内 <w:t> 文本参与统计。
     */
    private fun computeRevDocTotalChars(revPath: String): Int {
        try {
            ZipFile(revPath).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return 0
                val factory = XmlPullParserFactory.newInstance()
                factory.isNamespaceAware = false
                val parser = factory.newPullParser()
                parser.setInput(zip.getInputStream(entry), "UTF-8")
                val sb = StringBuilder()
                var inBody = false
                var inP = false
                var inT = false
                // v1.3.15: 也收集表格内的文字（表格不在 <w:p> 内）
                var inTbl = false
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            val raw = parser.name
                            val name = raw.substringAfterLast(':')
                            if (name == "body") inBody = true
                            else if (inBody && name == "p") inP = true
                            else if (inP && name == "t") inT = true
                            // v1.3.15: 进入表格时标记（表格内的 <w:t> 直接收集）
                            else if (inBody && name == "tbl") inTbl = true
                            else if (inTbl && name == "t") inT = true
                        }
                        XmlPullParser.TEXT -> {
                            if (inT) sb.append(parser.text)
                        }
                        XmlPullParser.END_TAG -> {
                            val raw = parser.name
                            val name = raw.substringAfterLast(':')
                            if (name == "t") inT = false
                            else if (name == "p") inP = false
                            else if (name == "tbl") inTbl = false
                            else if (name == "body") inBody = false
                        }
                    }
                    eventType = parser.next()
                }
                return countTextKotlin(sb.toString()).first
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute rev doc total chars", e)
            return 0
        }
    }

    private fun computeResultDocStats(outPath: String): ResultDocStats {
        try {
            ZipFile(outPath).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return ResultDocStats(0, 0)
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                return computeResultDocStatsSimple(xml)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute result doc stats", e)
            return ResultDocStats(0, 0)
        }
    }

    /**
     * 结果文档字数统计（与 Word「字符数(不计空格)」口径一致，等价于修订档视角）。
     *
     * 口径定义（用户指定：涉及修改句字数 = 修订档总字数 − 黑色整句字数）：
     *   1. 修订侧总字数 totalChars = 所有 <w:t>（黑字 + 蓝字插入）的字符数，排除空格；
     *      <w:delText>（红色删除）是「原文档独有、修订档没有」的文字，不计入修订侧总数。
     *   2. 黑色整句 blackWhole = 完全不含 <w:ins> 的句子中，黑字(<w:t> 非插入)的字符数，排除空格。
     *      <w:del>（红色删除）表示原文档比修订档多出的文字 —— 修订档该句本身未改动，
     *      因此删除标记【不破坏】黑色整句判定（只以蓝色 <w:ins> 插入作为“修改”依据）。
     *   3. 涉及修改的句子总字数 = totalChars − blackWhole。
     *
     * 注：空格不计入，与 Word 默认「字符数(不计空格)」一致；数字/英文字母正常计入。
     */
    private fun computeResultDocStatsSimple(xml: String): ResultDocStats {
        var totalChars = 0       // 修订侧总字数（<w:t> 黑+蓝，不含空格，不含删除）
        var blackWhole = 0       // 无 <w:ins> 句子中的黑字(<w:t> 非插入)字数，不含空格
        val delim = setOf('。', '！', '？', '；', '\n')

        // v1.3.16: 收集所有需要统计的文本块（顶层 <w:p> + 表格行拼接文本）
        val allBlocks = mutableListOf<String>()
        allBlocks.addAll(extractTopLevelParas(xml))
        // 从 <w:tbl> 中提取每行单元格拼接文本
        for (m in Regex("<w:tbl\\b.*?</w:tbl>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val tblXml = m.value
            for (trM in Regex("<w:tr\\b.*?</w:tr>", RegexOption.DOT_MATCHES_ALL).findAll(tblXml)) {
                val cellTexts = mutableListOf<String>()
                for (tcM in Regex("<w:tc\\b.*?</w:tc>", RegexOption.DOT_MATCHES_ALL).findAll(trM.value)) {
                    val texts = Regex("<w:t[^>]*>([^<]*)</w:t>").findAll(tcM.value)
                        .map { it.groupValues[1] }.filter { it.isNotEmpty() }.toList()
                    if (texts.isNotEmpty()) cellTexts.add(texts.joinToString(""))
                }
                if (cellTexts.isNotEmpty()) {
                    allBlocks.add(cellTexts.joinToString(" "))
                }
            }
        }

        for (px in allBlocks) {
            // 仅收集 <w:ins> 块范围（删除块不影响黑色整句判定，故忽略）
            val insBlocks = mutableListOf<IntRange>()
            for (m in Regex("<w:ins[^>]*>.*?</w:ins>", RegexOption.DOT_MATCHES_ALL).findAll(px)) {
                insBlocks.add(m.range)
            }
            fun inIns(pos: Int): Boolean = insBlocks.any { pos in it }

            data class Seg(val text: String, val isIns: Boolean, val isBlack: Boolean)
            val segs = mutableListOf<Seg>()
            for (tm in Regex("<w:(t|delText)[^>]*>(.*?)</w:\\1>", RegexOption.DOT_MATCHES_ALL).findAll(px)) {
                val tag = tm.groupValues[1]
                val txt = tm.groupValues[2]
                if (txt.isEmpty()) continue
                val isDel = (tag == "delText")
                val isIns = !isDel && inIns(tm.range.first)
                val isBlack = !isDel && !isIns
                segs.add(Seg(txt, isIns, isBlack))
                // 修订侧总数：黑字 + 蓝字插入（不含删除，不含空格）
                if (!isDel) {
                    for (ch in txt) if (ch != ' ') totalChars++
                }
            }

            val fullText = segs.joinToString("") { it.text }
            if (fullText.isEmpty()) continue

            // char 级标记数组
            val insArr = BooleanArray(fullText.length)
            val blackArr = BooleanArray(fullText.length)
            var idx = 0
            for (seg in segs) {
                for (i in 0 until seg.text.length) {
                    val p = idx + i
                    if (p < fullText.length) {
                        insArr[p] = seg.isIns
                        blackArr[p] = seg.isBlack
                    }
                }
                idx += seg.text.length
            }

            // 切句：无 <w:ins> 的句子计为黑色整句，累加其中的黑字（不含空格）
            var sStart = 0
            for (ci in fullText.indices) {
                if (fullText[ci] in delim) {
                    val sEnd = ci + 1
                    if (sEnd > sStart && !insArr.sliceArray(sStart until sEnd).any { it }) {
                        for (i in sStart until sEnd) {
                            if (blackArr[i] && fullText[i] != ' ') blackWhole++
                        }
                    }
                    sStart = sEnd
                }
            }
            if (sStart < fullText.length && !insArr.sliceArray(sStart until fullText.length).any { it }) {
                for (i in sStart until fullText.length) {
                    if (blackArr[i] && fullText[i] != ' ') blackWhole++
                }
            }
        }

        return ResultDocStats(totalChars, blackWhole)
    }

    /**
     * v1.3.16: 将 bodyParts 组装为文档 body 内容。
     * 关键改进：连续的 <w:tr> 元素自动包裹到 <w:tbl>...</w:tbl> 中，
     * 恢复表格结构（此前表格行被压平为独立段落，导致格式和布局全丢）。
     */
    private fun buildBodyContent(bodyXmlParts: List<String>): String {
        val sb = StringBuilder()
        var tblRowBuffer = mutableListOf<String>()

        fun flushTable() {
            if (tblRowBuffer.isNotEmpty()) {
                sb.append("<w:tbl>")
                for (row in tblRowBuffer) {
                    sb.append("\n").append(row)
                }
                sb.append("\n</w:tbl>\n")
                tblRowBuffer.clear()
            }
        }

        for (part in bodyXmlParts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("<w:tr") || trimmed.startsWith("<w:del>") && trimmed.contains("<w:tr")) {
                // 表格行（可能被 <w:del> 或 <w:ins> 包裹）
                tblRowBuffer.add(part)
            } else {
                // 非表格内容：先刷新累积的表格行
                flushTable()
                sb.append(part).append("\n")
            }
        }
        // 刷新末尾可能的表格
        flushTable()

        return sb.toString()
    }

    private fun writeOutputDocx(origDocx: File, outPath: String, bodyXmlParts: List<String>) {
        // v1.3.16: 用 buildBodyContent 替代简单 join，恢复表格结构
        val bodyContent = buildBodyContent(bodyXmlParts)
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

    // ══════════════════════════════════════════════════════
    //  .doc (OLE2) → 最简 .docx 转换
    // ══════════════════════════════════════════════════════

    /** 检测文件是否为 OLE2 / Compound Document 格式（旧版 .doc） */
    private fun isOle2File(path: String): Boolean {
        return try {
            val fis = FileInputStream(path)
            val magic = ByteArray(8)
            fis.read(magic)
            fis.close()
            // OLE2 magic: D0 CF 11 E0 A1 B1 1A E1
            magic[0] == 0xD0.toByte() && magic[1] == 0xCF.toByte() &&
            magic[2] == 0x11.toByte() && magic[3] == 0xE0.toByte()
        } catch (_: Exception) { false }
    }

    /**
     * 将旧版 .doc 文件转换为最简 .docx，并【保留段落缩进(pPr/w:ind)与字符字号(rPr/w:sz)】。
     * 用 POI HWPF 的 Range API 逐段/逐字符运行读取格式，再包装成最小有效 DOCX（ZIP + XML）。
     * 这样比较结果才能沿用「修改后的文件」的缩进与字号（原文件没有的段落才回退原文件）。
     * 返回临时文件路径，调用方负责清理。
     */
    private fun convertDocToMinimalDocx(docPath: String): String? {
        return try {
            val doc = HWPFDocument(FileInputStream(docPath))
            try {
                val range = doc.range
                val sb = StringBuilder()
                var emitted = 0
                for (pi in 0 until range.numParagraphs()) {
                    val para: Paragraph = runCatching { range.getParagraph(pi) }.getOrNull() ?: continue
                    val sbRuns = StringBuilder()
                    var paraLen = 0
                    for (ri in 0 until para.numCharacterRuns()) {
                        val run: CharacterRun = runCatching { para.getCharacterRun(ri) }.getOrNull() ?: continue
                        var runText = run.text() ?: ""
                        // 去掉段落标记/分节符等控制字符
                        runText = runText.replace("\r", "").replace("\u0007", "")
                            .replace("\u000c", "").replace("\u000b", "")
                        if (runText.isEmpty()) continue
                        val fontSize = runCatching { run.fontSize }.getOrElse { 0 }
                        val fontName = runCatching { run.fontName }.getOrNull() ?: ""
                        sbRuns.append("<w:r>${buildRunRPr(fontSize, fontName)}<w:t xml:space=\"preserve\">${escapeXml(runText)}</w:t></w:r>")
                        paraLen += runText.length
                    }
                    if (paraLen == 0) continue
                    val pPr = runCatching { buildParagraphPPr(para) }.getOrElse { "" }
                    sb.append("<w:p>$pPr$sbRuns</w:p>\n")
                    emitted++
                }
                if (emitted == 0) {
                    // 退化：Range 解析失败 → 退回纯文本转换
                    val txt = OldOfficeEngine.extractText(File(docPath))
                    if (txt.isBlank()) null else writeMinimalDocx(buildPlainParas(txt))
                } else {
                    writeMinimalDocx(sb.toString())
                }
            } finally {
                runCatching { doc.close() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "convertDocToMinimalDocx error: ${e.javaClass.simpleName}: ${e.message}")
            // 防御：任何异常都退回纯文本转换
            try {
                val txt = OldOfficeEngine.extractText(File(docPath))
                if (txt.isBlank()) null else writeMinimalDocx(buildPlainParas(txt))
            } catch (_: Exception) { null }
        }
    }

    /** 纯文本（按行）构建最小段落 XML（无格式，作为 .doc 解析失败时的退化路径）。 */
    private fun buildPlainParas(text: String): String {
        val escaped = escapeXml(text)
        return escaped.lines().filter { it.isNotBlank() }.joinToString("\n") { line ->
            "<w:p><w:r><w:t xml:space=\"preserve\">$line</w:t></w:r></w:p>"
        }
    }

    /** 把段落集合写入最小有效 DOCX，返回临时文件路径。 */
    private fun writeMinimalDocx(bodyParasXml: String): String {
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

        val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
</Relationships>"""

        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:o="urn:schemas-microsoft-com:office:office"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"
            xmlns:v="urn:schemas-microsoft-com:vml"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:w10="urn:schemas-microsoft-com:office:word"
            xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml"
            xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"
            xmlns:wpi="http://schemas.microsoft.com/office/word/2010/wordprocessingInk"
            xmlns:wne="http://schemas.microsoft.com/office/word/2006/wordml"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            mc:Ignorable="w14 wp14">
  <w:body>
$bodyParasXml
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="851" w:footer="992" w:gutter="0"/>
    </w:sectPr>
  </w:body>
</w:document>"""

        val tmpFile = File.createTempFile("wc_convert_", ".docx")
        ZipOutputStream(tmpFile.outputStream()).use { zout ->
            zout.putNextEntry(ZipEntry("[Content_Types].xml"))
            zout.write(contentTypes.toByteArray(Charsets.UTF_8))
            zout.closeEntry()

            zout.putNextEntry(ZipEntry("_rels/.rels"))
            zout.write(rels.toByteArray(Charsets.UTF_8))
            zout.closeEntry()

            zout.putNextEntry(ZipEntry("word/document.xml"))
            zout.write(documentXml.toByteArray(Charsets.UTF_8))
            zout.closeEntry()

            zout.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zout.write(docRels.toByteArray(Charsets.UTF_8))
            zout.closeEntry()
        }
        return tmpFile.absolutePath
    }

    /** 从 HWPF 段落读取缩进，生成 <w:pPr><w:ind .../></w:pPr>（twips 与 docx 单位一致）。
     *  firstLine>0 → 首行缩进；firstLine<0 → 悬挂缩进。无缩进返回空串。 */
    private fun buildParagraphPPr(para: Paragraph): String {
        val left = para.indentFromLeft
        val right = para.indentFromRight
        val first = para.firstLineIndent
        if (left == 0 && right == 0 && first == 0) return ""
        val ind = StringBuilder("<w:ind")
        if (left != 0) ind.append(" w:left=\"$left\"")
        if (right != 0) ind.append(" w:right=\"$right\"")
        if (first > 0) ind.append(" w:firstLine=\"$first\"")
        else if (first < 0) ind.append(" w:hanging=\"${-first}\"")
        ind.append("/>")
        return "<w:pPr>$ind</w:pPr>"
    }

    /** 生成字符运行属性：保留字号(w:sz/w:szCs)与字体名(w:rFonts)。fontSize 为半磅(与 docx 一致)。 */
    private fun buildRunRPr(fontSize: Int, fontName: String): String {
        val sb = StringBuilder("<w:rPr>")
        if (fontName.isNotBlank()) {
            val safe = fontName.replace("\"", "&quot;")
            sb.append("<w:rFonts w:ascii=\"$safe\" w:hAnsi=\"$safe\" w:eastAsia=\"$safe\" w:cs=\"$safe\"/>")
        }
        if (fontSize > 0) {
            sb.append("<w:sz w:val=\"$fontSize\"/><w:szCs w:val=\"$fontSize\"/>")
        }
        sb.append("</w:rPr>")
        return sb.toString()
    }
}
