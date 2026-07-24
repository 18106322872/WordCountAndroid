package com.henry.wordcount

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Regex
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 纯 Kotlin 实现的 DOCX 文档比较器（v1.1.53 重写版）。
 *
 * 设计目标：输出文档与 Word「审阅-比较」结果一致。
 * 核心思路：
 *   1. 以【原文档 XML 为底板】，保留完整格式。
 *   2. 段落级对齐：exact 相等 → 黑字(EQ)；rev 段落整体包含于 orig 段落 → rev 黑字(SBLACK)+orig 差值红字(SDEL)；
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

    /** 对齐后的一次操作。tag: EQ / REP / DEL / INS / SBLACK / SDEL */
    data class CmpOp(
        val tag: String,
        val oi: Int,   // 原文档段落下标（-1 表示无）
        val rj: Int,   // 修订文档段落下标（-1 表示无）
        val pos: Double,
        val gap: String = ""   // SDEL 时承载被删除的原文片段
    )

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
        var totalInsChars = 0
        var totalDelChars = 0
        val bodyParts = mutableListOf<String>()

        for (op in ops) {
            when (op.tag) {
                "EQ" -> {
                    bodyParts.add(origParas[op.oi].xml)
                }
                "SBLACK" -> {
                    bodyParts.add(revParas[op.rj].xml)
                }
                "SDEL" -> {
                    bodyParts.add(wrapDeletedText(origParas[op.oi].xml, op.gap, author, date, ridSeq))
                    delCount++
                    totalDelChars += op.gap.length
                }
                "DEL" -> {
                    bodyParts.add(wrapDeletedParagraph(origParas[op.oi].xml, author, date, ridSeq))
                    delCount++
                    totalDelChars += origParas[op.oi].text.length
                }
                "INS" -> {
                    bodyParts.add(wrapInsertedParagraph(revParas[op.rj].xml, author, date, ridSeq))
                    insCount++
                    totalInsChars += revParas[op.rj].text.length
                }
                "REP" -> {
                    val (pXml, delC, insC) = buildDiffParagraphXml(
                        origParas[op.oi].xml,
                        origParas[op.oi].text,
                        revParas[op.rj].text,
                        author, date, ridSeq
                    )
                    bodyParts.add(pXml)
                    totalDelChars += delC
                    totalInsChars += insC
                    repCount++
                }
            }
        }

        writeOutputDocx(origFile, outPath, bodyParts)

        val modifiedChars = totalInsChars + totalDelChars
        val summary = buildString {
            append("插入 $insCount 处(${totalInsChars}字) | 删除 $delCount 处(${totalDelChars}字) | 修改 $repCount 处")
        }

        Log.d(TAG, "result: ins=$insCount($totalInsChars字) del=$delCount($totalDelChars字) rep=$repCount chars=$modifiedChars")

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
    //  段落对齐（exact + SUBEQ 拆分 + 相似 LCS + 受控合并）
    // ══════════════════════════════════════════════════════

    private fun alignParagraphs(origParas: List<Para>, revParas: List<Para>): List<CmpOp> {
        val n = origParas.size
        val m = revParas.size
        val oUsed = BooleanArray(n)
        val rUsed = BooleanArray(m)
        val ops = mutableListOf<CmpOp>()

        // 阶段A：exact
        for (i in 0 until n) {
            if (oUsed[i]) continue
            val ot = origParas[i].text
            if (ot.isEmpty()) continue
            for (j in 0 until m) {
                if (rUsed[j]) continue
                if (ot == revParas[j].text) {
                    ops.add(CmpOp("EQ", i, j, i.toDouble()))
                    oUsed[i] = true
                    rUsed[j] = true
                    break
                }
            }
        }

        // 阶段B：SUBEQ（rev 整体包含于 orig，且 rev 明显更短）
        for (i in 0 until n) {
            if (oUsed[i]) continue
            val ot = origParas[i].text
            if (ot.isEmpty()) continue
            val contained = mutableListOf<Pair<Int, Int>>() // (position, revIndex)
            for (j in 0 until m) {
                if (rUsed[j]) continue
                val rt = revParas[j].text
                if (rt.length < 10) continue
                if (rt.length >= 0.7 * ot.length) continue
                val pos = ot.indexOf(rt)
                if (pos >= 0) contained.add(Pair(pos, j))
            }
            if (contained.isEmpty()) continue
            contained.sortBy { it.first }
            val segs = mutableListOf<CmpOp>()
            var prev = 0
            var frac = 0.0
            for ((pos, j) in contained) {
                if (pos > prev) {
                    segs.add(CmpOp("SDEL", i, -1, i.toDouble() + frac, ot.substring(prev, pos)))
                    frac += 0.1
                }
                segs.add(CmpOp("SBLACK", i, j, i.toDouble() + frac))
                frac += 0.1
                prev = pos + revParas[j].text.length
                rUsed[j] = true
            }
            if (prev < ot.length) {
                segs.add(CmpOp("SDEL", i, -1, i.toDouble() + frac, ot.substring(prev, ot.length)))
            }
            oUsed[i] = true
            ops.addAll(segs)
        }

        // 阶段C：相似度感知 LCS（仅对其余段落）
        val remO = mutableListOf<Int>()
        val remR = mutableListOf<Int>()
        for (i in 0 until n) if (!oUsed[i]) remO.add(i)
        for (j in 0 until m) if (!rUsed[j]) remR.add(j)

        val useSimilarity = (remO.size * remR.size) <= 6000
        val codes = computeDiffText(
            remO.map { origParas[it].text },
            remR.map { revParas[it].text },
            useSimilarity
        )
        val raw = mutableListOf<CmpOp>()
        for (c in codes) {
            when (c.tag) {
                "equal" -> {
                    for (k in c.i1 until c.i2) {
                        val oi = remO[k]
                        val rj = remR[c.j1 + (k - c.i1)]
                        raw.add(CmpOp("REP", oi, rj, oi.toDouble()))
                    }
                }
                "delete" -> {
                    for (k in c.i1 until c.i2) raw.add(CmpOp("DEL", remO[k], -1, remO[k].toDouble()))
                }
                "insert" -> {
                    for (k in c.j1 until c.j2) raw.add(CmpOp("INS", -1, remR[k], remR[k].toDouble()))
                }
            }
        }
        // 受控合并：相邻「删除+插入」仅当相似时合并为内联修订(REP)
        val mergedRaw = mutableListOf<CmpOp>()
        var ri = 0
        while (ri < raw.size) {
            val op = raw[ri]
            if (op.tag == "DEL" && ri + 1 < raw.size && raw[ri + 1].tag == "INS" &&
                isAligned(origParas[op.oi].text, revParas[raw[ri + 1].rj].text)
            ) {
                mergedRaw.add(CmpOp("REP", op.oi, raw[ri + 1].rj, op.pos))
                ri += 2
            } else {
                mergedRaw.add(op)
                ri += 1
            }
        }
        ops.addAll(mergedRaw)

        ops.sortWith(compareBy({ it.pos }, { if (it.tag == "SDEL") 0 else 1 }))
        return ops
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
                    val lookAhead = kotlin.math.min(25, aChunk.size - ci, bChunk.size - cji)
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
    ): Triple<String, Int, Int> {
        val runs = extractWRuns(origXml)
        val ops = charDiff(origText, revText)

        val sb = StringBuilder("<w:p>")
        var delChars = 0
        var insChars = 0
        for (op in ops) {
            when (op.tag) {
                "equal" -> {
                    val seg = extractRunsInRange(runs, op.i1, op.i2)
                    if (seg.isNotEmpty()) sb.append(seg)
                }
                "delete" -> {
                    val seg = extractRunsInRange(runs, op.i1, op.i2)
                    if (seg.isNotEmpty()) {
                        sb.append("<w:del w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">")
                        sb.append(seg)
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
                        sb.append("<w:del w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">")
                        sb.append(dSeg)
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
        return Triple(sb.toString(), delChars, insChars)
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
        return "<w:p>$pPr<w:del w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">$inner</w:del></w:p>"
    }

    private fun wrapInsertedParagraph(paraXml: String, author: String, date: String, ridSeq: IntArray): String {
        val pPr = extractPPr(paraXml)
        val inner = extractParaInner(paraXml)
        return "<w:p>$pPr<w:ins w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\">$inner</w:ins></w:p>"
    }

    private fun wrapDeletedText(origXml: String, gap: String, author: String, date: String, ridSeq: IntArray): String {
        val pPr = extractPPr(origXml)
        val rPr = extractFirstRPr(origXml)
        return "<w:p>$pPr<w:del w:id=\"${nextRid(ridSeq)}\" w:author=\"$author\" w:date=\"$date\"><w:r>$rPr<w:t xml:space=\"preserve\">${escapeXml(gap)}</w:t></w:r></w:del></w:p>"
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
