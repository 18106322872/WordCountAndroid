package com.henry.wordcount

import android.content.Context
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 纯 Kotlin 实现的 DOCX 文档比较器（v1.1.52 重写版）。
 *
 * 完全不依赖 Python/lxml，用 Android 标准库（ZipFile + XmlPullParser）实现。
 * 替代原 Python 版 compare_docx（该版本因 Chaquopy lxml C 扩展崩溃无法使用）。
 *
 * v1.1.52 重写：
 * - 多段落 replace 改为子级 LCS 对齐 + 内联字符级 diff（匹配 Word 原生格式）
 * - 修复 modifiedChars 统计：只算实际变动的字数，不再整段累加
 * - 输出文档格式接近 Word「审阅-比较」结果（内联 w:ins/w:del 标记）
 */
object DocxComparator {

    private const val TAG = "DocxCompare"

    // ── OOXML 命名空间 ──
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    // ── 数据类 ──
    data class Block(val type: String, val text: String)
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
    data class Token(val orig: String, val norm: String, val start: Int, val end: Int)

    /** 单段落字符级 diff 最大 token 数 */
    private const val MAX_DIFF_TOKENS = 2000

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
        val level = opts.optString("level", "word")
        val caseSensitive = opts.optBoolean("case", true)
        val ignoreWs = opts.optBoolean("whitespace", false)
        val useTable = opts.optBoolean("table", true)
        val useHf = opts.optBoolean("header_footer", true)
        val useFn = opts.optBoolean("footnote", true)
        val useTb = opts.optBoolean("textbox", true)
        val useField = opts.optBoolean("field", true)

        val author = "WordCount"
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

        // ══ 阶段1：读取原文档块 ═══
        val blocksO = readBlocks(origFile, "orig")
        Log.d(TAG, "orig blocks: ${blocksO.size} (first=${blocksO.take(3).map { it.text.take(30) }})")

        // ══ 阶段2：读取修订文档块 ═══
        val blocksR = readBlocks(revFile, "rev")
        Log.d(TAG, "rev blocks: ${blocksR.size} (first=${blocksR.take(3).map { it.text.take(30) }})")

        // ══ 阶段3：计算 diff ═══
        val textsO = blocksO.map { it.text }
        val textsR = blocksR.map { it.text }
        val opcodes = computeDiff(textsO, textsR)
        Log.d(TAG, "opcodes: $opcodes")

        val merged = mergeReplace(opcodes)

        // ══ 阶段4：构建输出 ═══
        val ridSeq = intArrayOf(0)
        fun nextRid(): Int { ridSeq[0]++; return ridSeq[0] }

        var modifiedChars = 0
        var insCount = 0
        var delCount = 0
        var repCount = 0
        val bodyParts = mutableListOf<String>()

        for ((tag, i1, i2, j1, j2) in merged) {
            when (tag) {
                "equal" -> {
                    for (k in i1 until i2) {
                        val el = blocksO[k]
                        if (el.type == "p") bodyParts.add(buildPlainParagraph(el.text))
                        else bodyParts.add(buildNoteParagraph("[表格] ${el.text.take(200)}"))
                    }
                }
                "delete" -> {
                    for (k in i1 until i2) {
                        val el = blocksO[k]
                        if (el.type == "p") {
                            bodyParts.add(buildDeletedParagraph(el.text, author, date, ::nextRid))
                            modifiedChars += countTextChars(el.text)
                        } else {
                            bodyParts.add(buildNoteParagraph("[已删表格] ${el.text.take(200)}"))
                            modifiedChars += countTextChars(el.text)
                        }
                        delCount++
                    }
                }
                "insert" -> {
                    for (k in j1 until j2) {
                        val el = blocksR[k]
                        if (el.type == "p") bodyParts.add(buildInsertedParagraph(el.text, author, date, ::nextRid))
                        else bodyParts.add(buildNoteParagraph("[新增表格] ${el.text.take(200)}"))
                        insCount++
                    }
                }
                "replace" -> {
                    // v1.1.52: 多段落 replace 也做子对齐 + 内联字符级 diff
                    val subOrig = (i1 until i2).map { blocksO[it] }
                    val subRev = (j1 until j2).map { blocksR[it] }

                    // 只取段落类型的块做子对齐
                    val subPOrg = subOrig.filter { it.type == "p" }
                    val subPRev = subRev.filter { it.type == "p" }

                    if (subPOrg.isNotEmpty() || subPRev.isNotEmpty()) {
                        // 子级段落 LCS 对齐
                        val subTextsO = subPOrg.map { it.text }
                        val subTextsR = subPRev.map { it.text }
                        val subOps = computeDiff(subTextsO, subTextsR)
                        val subMerged = mergeReplace(subOps)

                        var localRepCount = 0
                        for ((stag, si1, si2, sj1, sj2) in subMerged) {
                            when (stag) {
                                "equal" -> {
                                    for (k in si1 until si2) {
                                        bodyParts.add(buildPlainParagraph(subPOrg[k].text))
                                    }
                                }
                                "delete" -> {
                                    for (k in si1 until si2) {
                                        bodyParts.add(buildDeletedParagraph(subPOrg[k].text, author, date, ::nextRid))
                                        modifiedChars += countTextChars(subPOrg[k].text)
                                    }
                                    delCount += (si2 - si1)
                                }
                                "insert" -> {
                                    for (k in sj1 until sj2) {
                                        bodyParts.add(buildInsertedParagraph(subPRev[k].text, author, date, ::nextRid))
                                    }
                                    insCount += (sj2 - sj1)
                                }
                                "replace" -> {
                                    // 单对段落：做内联字符级 diff
                                    for (di in 0 until minOf(si2 - si1, sj2 - sj1)) {
                                        val bo = subPOrg[si1 + di]
                                        val br = subPRev[sj1 + di]
                                        val maxLen = maxOf(bo.text.length, br.text.length)
                                        if (maxLen > MAX_DIFF_TOKENS) {
                                            bodyParts.add(buildDeletedParagraph(bo.text, author, date, ::nextRid))
                                            bodyParts.add(buildInsertedParagraph(br.text, author, date, ::nextRid))
                                            modifiedChars += countTextChars(bo.text) + countTextChars(br.text)
                                        } else {
                                            val (pXml, ranges) = buildDiffParagraph(
                                                bo.text, br.text, level, author, date, ::nextRid,
                                                caseSensitive, ignoreWs
                                            )
                                            bodyParts.add(pXml)
                                            // 只算实际被修改的字数（删除范围 + 插入估算）
                                            modifiedChars += countModifiedSentences(bo.text, ranges)
                                            // 加上插入部分的净增字数
                                            val insExtra = maxOf(0, countTextChars(br.text) - countTextChars(bo.text))
                                            if (insExtra > 0) modifiedChars += insExtra / 2  // 估算一半是新增
                                        }
                                        localRepCount++
                                    }
                                    // 剩余未配对的原文段落（删除）
                                    for (di in (si2 - si1) until (sj2 - sj1)) {
                                        // 不可能到这里
                                    }
                                    for (di in minOf(si2 - si1, sj2 - sj1) until (si2 - si1)) {
                                        bodyParts.add(buildDeletedParagraph(subPOrg[si1 + di].text, author, date, ::nextRid))
                                        modifiedChars += countTextChars(subPOrg[si1 + di].text)
                                        delCount++
                                    }
                                    for (di in minOf(si2 - si1, sj2 - sj1) until (sj2 - sj1)) {
                                        bodyParts.add(buildInsertedParagraph(subPRev[sj1 + di].text, author, date, ::nextRid))
                                        insCount++
                                    }
                                }
                            }
                        }
                        repCount += maxOf(localRepCount, 1)
                    }

                    // 表格类块直接标注
                    val tblOrg = subOrig.filter { it.type == "tbl" }
                    val tblRev = subRev.filter { it.type == "tbl" }
                    for (tbl in tblOrg) {
                        bodyParts.add(buildNoteParagraph("[原表格] ${tbl.text.take(200)}"))
                        modifiedChars += countTextChars(tbl.text)
                    }
                    for (tbl in tblRev) {
                        bodyParts.add(buildNoteParagraph("[修订表格] ${tbl.text.take(200)}"))
                        modifiedChars += countTextChars(tbl.text)
                    }
                }
            }
        }

        // 附加区域变更检测
        val extraKinds = mutableListOf<Triple<String, Boolean, String>>()
        if (useHf) extraKinds.add(Triple("header_footer", true, "【页眉/页脚变更】"))
        if (useFn) extraKinds.add(Triple("footnote", true, "【脚注/尾注变更】"))
        if (useTb) extraKinds.add(Triple("textbox", true, "【文本框变更】"))
        if (useField) extraKinds.add(Triple("field", true, "【域变更】"))

        for ((kind, _, label) in extraKinds) {
            val textO = extractExtraTextLight(origFile, kind)
            val textR = extractExtraTextLight(revFile, kind)
            if (textO != textR) {
                bodyParts.add(buildNoteParagraph(label))
                if (textO.isNotEmpty() || textR.isNotEmpty()) {
                    modifiedChars += Math.abs(textO.length - textR.length)
                }
            }
        }

        // 写出输出 DOCX
        writeOutputDocx(origFile, outPath, bodyParts)

        val summary = buildString {
            append("插入 $insCount 处 | 删除 $delCount 处 | 修改 $repCount 处")
            if (modifiedChars > 0) append(" | 修改字数约 $modifiedChars")
        }

        Log.d(TAG, "result: ins=$insCount del=$delCount rep=$repCount chars=$modifiedChars")

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

    // ════════════════════════════════════════════════════════
    //  DOCX 解析（双模式：命名空间感知 + fallback）
    // ════════════════════════════════════════════════════════

    private fun readBlocks(docx: File, label: String): List<Block> {
        var result: List<Block>? = null
        ZipFile(docx).use { zip ->
            val entry = zip.getEntry("word/document.xml")
            if (entry == null) {
                Log.w(TAG, "$label: no document.xml found!")
                return emptyList()
            }
            val xml = zip.getInputStream(entry).bufferedReader().readText()

            // 模式1：命名空间感知解析
            result = parseBlocksNsAware(xml)
            if (result!!.isEmpty()) {
                Log.w(TAG, "$label: ns-aware parsing returned 0 blocks, trying non-ns...")
                // 模式2：非命名空间感知解析（fallback）
                result = parseBlocksNonNs(xml)
            }
        }
        if (result == null) result = emptyList()
        Log.d(TAG, "$label: parsed ${result!!.size} blocks")
        return result!!
    }

    /**
     * 命名空间感知模式解析 document.xml。
     */
    private fun parseBlocksNsAware(xml: String): List<Block> {
        val blocks = mutableListOf<Block>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.setNamespaceAware(true)
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inBody = false
            var inP = false
            var inTbl = false
            var inTc = false
            var inTr = false
            var inT = false
            var pText = StringBuilder()
            var tblTexts = mutableListOf<String>()
            var pDepth = 0  // 嵌套深度跟踪

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val nsTag = getNsTag(parser)
                        when (nsTag) {
                            "$W:body" -> { inBody = true; Log.v(TAG, "START <w:body>") }
                            "$W:p" -> {
                                if (inBody && !inTbl) {
                                    inP = true; pDepth = 1; pText = StringBuilder()
                                } else if (inTc) {
                                    pText = StringBuilder()
                                }
                            }
                            "$W:tbl" -> if (inBody) { inTbl = true; tblTexts = mutableListOf() }
                            "$W:tr" -> if (inTbl) inTr = true
                            "$W:tc" -> if (inTr) inTc = true
                            "$W:t" -> inT = true
                            // 追踪嵌套的 w:p（如 w:p 内有 w:hyperlink 包含 w:p）
                            else -> { if (inP && parser.namespace == W) pDepth++ }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inT) {
                            val text = parser.text ?: ""
                            pText.append(text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val nsTag = getNsTag(parser)
                        when (nsTag) {
                            "$W:t" -> inT = false
                            "$W:p" -> {
                                if (inTc) {
                                    tblTexts.add(pText.toString())
                                    pText = StringBuilder()
                                } else if (inP) {
                                    pDepth--
                                    if (pDepth <= 0) {
                                        val txt = pText.toString()
                                        blocks.add(Block("p", txt))
                                        inP = false
                                        pDepth = 0
                                    }
                                }
                            }
                            "$W:tc" -> if (inTc) inTc = false
                            "$W:tr" -> if (inTr) inTr = false
                            "$W:tbl" -> if (inTbl) {
                                blocks.add(Block("tbl", tblTexts.joinToString("\n")))
                                inTbl = false
                            }
                            "$W:body" -> inBody = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseBlocksNsAware error: ${e.javaClass.simpleName}: ${e.message}")
        }
        return blocks
    }

    /**
     * 非命名空间感知模式解析（fallback）。
     * 匹配原始标签名如 "w:p"、"w:t"、"w:body" 等。
     */
    private fun parseBlocksNonNs(xml: String): List<Block> {
        val blocks = mutableListOf<Block>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.setNamespaceAware(false)
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inBody = false
            var inP = false
            var inTbl = false
            var inTc = false
            var inTr = false
            var inT = false
            var pText = StringBuilder()
            var tblTexts = mutableListOf<String>()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name ?: ""
                        when (name) {
                            "w:body", "body" -> inBody = true
                            "w:p", "p" -> if (inBody && !inTbl) { inP = true; pText = StringBuilder() }
                            "w:tbl", "tbl" -> if (inBody) { inTbl = true; tblTexts = mutableListOf() }
                            "w:tr", "tr" -> if (inTbl) inTr = true
                            "w:tc", "tc" -> if (inTr) inTc = true
                            "w:t", "t" -> inT = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inT) {
                            val text = parser.text ?: ""
                            pText.append(text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name ?: ""
                        when (name) {
                            "w:t", "t" -> inT = false
                            "w:p", "p" -> {
                                if (inTc) {
                                    tblTexts.add(pText.toString())
                                    pText = StringBuilder()
                                } else if (inP) {
                                    blocks.add(Block("p", pText.toString()))
                                    inP = false
                                }
                            }
                            "w:tc", "tc" -> if (inTc) inTc = false
                            "w:tr", "tr" -> if (inTr) inTr = false
                            "w:tbl", "tbl" -> if (inTbl) {
                                blocks.add(Block("tbl", tblTexts.joinToString("\n")))
                                inTbl = false
                            }
                            "w:body", "body" -> inBody = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseBlocksNonNs error: ${e.javaClass.simpleName}: ${e.message}")
        }
        return blocks
    }

    /**
     * 轻量级附加区域文本提取。
     */
    private fun extractExtraTextLight(docx: File, kind: String): String {
        val parts = mutableListOf<String>()
        try {
            ZipFile(docx).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return ""
                val factory = XmlPullParserFactory.newInstance()
                factory.setNamespaceAware(true)
                val parser = factory.newPullParser()
                parser.setInput(zip.getInputStream(entry), "UTF-8")

                var currentText = StringBuilder()
                var inTarget = false
                var inWt = false

                val targetStartTags = when (kind) {
                    "header_footer" -> setOf("$W:headerReference", "$W:footerReference")
                    "footnote" -> setOf("$W:footnote")
                    "textbox" -> setOf("$W:txbxContent")
                    "field" -> setOf("$W:fldSimple")
                    else -> emptySet()
                }

                var depth = 0
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            val nsTag = getNsTag(parser)
                            if (nsTag in targetStartTags && !inTarget) {
                                inTarget = true; depth = 1; currentText = StringBuilder()
                                if (kind == "header_footer") {
                                    val rid = parser.getAttributeValue(R, "id") ?: ""
                                    parts.add("[${if (nsTag.contains("footer")) "footer" else "header"}:$rid]")
                                    inTarget = false
                                }
                            } else if (inTarget) {
                                depth++
                                if (nsTag == "$W:t") inWt = true
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (inWt) currentText.append(parser.text ?: "")
                        }
                        XmlPullParser.END_TAG -> {
                            val nsTag = getNsTag(parser)
                            if (nsTag == "$W:t") inWt = false
                            if (inTarget) {
                                depth--
                                if (depth <= 0) {
                                    val txt = currentText.toString().trim()
                                    if (txt.isNotEmpty()) parts.add(txt)
                                    inTarget = false
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (_: Exception) {}
        return parts.joinToString("\n")
    }

    // ════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════
    //  Diff 算法（标准 LCS DP + 回溯 → opcodes）
    // ════════════════════════════════════════════════════════

    /**
     * 计算两个列表的 diff opcodes。
     * v1.1.40: 完全重写。标准 LCS DP → 回溯 → 合并 ranges。
     * 消除 v1.1.39 的多重嵌套/废弃代码路径，确保正确且高效。
     */
    private fun <T> computeDiff(a: List<T>, b: List<T>): List<Quadruple> {
        val m = a.size
        val n = b.size
        if (m == 0) return if (n == 0) emptyList() else listOf(Quadruple("insert", 0, 0, 0, n))
        if (n == 0) return listOf(Quadruple("delete", 0, m, 0, 0))

        // 大规模输入分块处理（避免 O(m*n) 内存）
        if (m.toLong() * n.toLong() > 200000L) return computeDiffChunked(a, b)

        // ══ 标准 LCS DP 表 ══
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            val ai = a[i - 1]
            for (j in 1..n) {
                dp[i][j] = if (ai == b[j - 1]) dp[i - 1][j - 1] + 1
                           else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }

        // ══ 回溯收集反向操作 ══
        val revOps = mutableListOf<String>() // "eq" / "ins" / "del"
        var i = m; var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == b[j - 1] -> { revOps.add("eq"); i--; j-- }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> { revOps.add("ins"); j-- }
                else -> { revOps.add("del"); i-- }
            }
        }

        // ══ 反转 + 合并相邻操作为 opcode ranges ══
        revOps.reverse()
        val result = mutableListOf<Quadruple>()
        var idx = 0
        var ci = 0; var cj = 0  // 游标

        while (idx < revOps.size) {
            when (revOps[idx]) {
                "eq" -> {
                    val si = ci; val sj = cj
                    while (idx < revOps.size && revOps[idx] == "eq") { ci++; cj++; idx++ }
                    result.add(Quadruple("equal", si, ci, sj, cj))
                }
                "ins" -> {
                    val sj = cj
                    while (idx < revOps.size && revOps[idx] == "ins") { cj++; idx++ }
                    result.add(Quadruple("insert", ci, ci, sj, cj))
                }
                "del" -> {
                    val si = ci
                    while (idx < revOps.size && revOps[idx] == "del") { ci++; idx++ }
                    result.add(Quadruple("delete", si, ci, cj, cj))
                }
                else -> idx++
            }
        }
        return result
    }

    /**
     * 大规模分块 diff（m*n > 200000 时使用）。
     */
    private fun <T> computeDiffChunked(a: List<T>, b: List<T>): List<Quadruple> {
        val chunkSize = 300
        val result = mutableListOf<Quadruple>()
        var ai = 0; var bi = 0
        while (ai < a.size || bi < b.size) {
            val aEnd = minOf(ai + chunkSize, a.size)
            val bEnd = minOf(bi + chunkSize, b.size)
            val aChunk = a.subList(ai, aEnd)
            val bChunk = b.subList(bi, bEnd)
            if (aChunk.isEmpty() && bChunk.isNotEmpty()) {
                result.add(Quadruple("insert", ai, ai, bi, bEnd)); bi = bEnd; continue
            }
            if (bChunk.isEmpty() && aChunk.isNotEmpty()) {
                result.add(Quadruple("delete", ai, aEnd, bi, bi)); ai = aEnd; continue
            }
            if (aChunk.isEmpty() && bChunk.isEmpty()) break
            // 块内贪心匹配
            var ci = 0; var cji = 0
            while (ci < aChunk.size || cji < bChunk.size) {
                if (ci < aChunk.size && cji < bChunk.size && aChunk[ci] == bChunk[cji]) {
                    val si = ci; val sji = cji
                    while (ci < aChunk.size && cji < bChunk.size && aChunk[ci] == bChunk[cji]) { ci++; cji++ }
                    result.add(Quadruple("equal", ai + si, ai + ci, bi + sji, bi + cji))
                } else {
                    val lookAhead = minOf(20, aChunk.size - ci, bChunk.size - cji)
                    var found = false
                    search@ for (di in 0..lookAhead) {
                        for (dj in 0..lookAhead) {
                            if (di == 0 && dj == 0) continue
                            val ai2 = ci + di; val bj2 = cji + dj
                            if (ai2 < aChunk.size && bj2 < bChunk.size && aChunk[ai2] == bChunk[bj2]) {
                                if (dj == 0) { result.add(Quadruple("delete", ai + ci, ai + ai2, bi + cji, bi + cji)); ci = ai2 }
                                else if (di == 0) { result.add(Quadruple("insert", ai + ci, ai + ci, bi + cji, bi + bj2)); cji = bj2 }
                                else { result.add(Quadruple("replace", ai + ci, ai + ai2, bi + cji, bi + bj2)); ci = ai2; cji = bj2 }
                                found = true; break@search
                            }
                        }
                    }
                    if (!found) {
                        if (ci < aChunk.size || cji < bChunk.size)
                            result.add(Quadruple("replace", ai + ci, ai + aChunk.size, bi + cji, bi + bChunk.size))
                        ci = aChunk.size; cji = bChunk.size
                    }
                }
            }
            ai = aEnd; bi = bEnd
        }
        return result
    }


    private fun mergeReplace(opcodes: List<Quadruple>): List<Quadruple> {
        val result = mutableListOf<Quadruple>()
        var i = 0
        while (i < opcodes.size) {
            val op = opcodes[i]
            if (op.tag == "delete" && i + 1 < opcodes.size && opcodes[i + 1].tag == "insert") {
                val next = opcodes[i + 1]
                result.add(Quadruple("replace", op.i1, op.i2, next.j1, next.j2))
                i += 2; continue
            }
            if (op.tag == "insert" && i + 1 < opcodes.size && opcodes[i + 1].tag == "delete") {
                val next = opcodes[i + 1]
                result.add(Quadruple("replace", next.i1, next.i2, op.j1, op.j2))
                i += 2; continue
            }
            result.add(op); i++
        }
        return result
    }

    // ════════════════════════════════════════════════════════
    //  字符/词级 Diff
    // ════════════════════════════════════════════════════════

    private fun buildDiffParagraph(
        textO: String, textR: String, level: String,
        author: String, date: String, ridFn: () -> Int,
        caseSensitive: Boolean, ignoreWs: Boolean
    ): Pair<String, List<Pair<Int, Int>>> {
        val toksO = tokenize(textO, level)
        val toksR = tokenize(textR, level)

        if (toksO.size > MAX_DIFF_TOKENS || toksR.size > MAX_DIFF_TOKENS) {
            val sb = StringBuilder()
            sb.append("<w:p xmlns:w=\"$W\">")
            sb.append(makeRun(textO, "del", author, date, ridFn()))
            sb.append(makeRun(textR, "ins", author, date, ridFn()))
            sb.append("</w:p>")
            return Pair(sb.toString(), listOf(Pair(0, textO.length)))
        }

        val normO = normalizeTokens(toksO, caseSensitive, ignoreWs)
        val normR = normalizeTokens(toksR, caseSensitive, ignoreWs)

        val opcodes = computeDiff(normO.map { it.norm }, normR.map { it.norm })

        val sb = StringBuilder()
        sb.append("<w:p xmlns:w=\"$W\">")
        val delRanges = mutableListOf<Pair<Int, Int>>()

        for ((tag, i1, i2, j1, j2) in opcodes) {
            when (tag) {
                "equal" -> {
                    val seg = toksO.subList(i1, i2).joinToString("") { it.orig }
                    if (seg.isNotEmpty()) sb.append(makeRun(seg, null, author, date, ridFn()))
                }
                "delete" -> {
                    val seg = toksO.subList(i1, i2).joinToString("") { it.orig }
                    if (seg.isNotEmpty()) {
                        sb.append(makeRun(seg, "del", author, date, ridFn()))
                        delRanges.add(Pair(toksO[i1].start, toksO[i2 - 1].end))
                    }
                }
                "insert" -> {
                    val seg = toksR.subList(j1, j2).joinToString("") { it.orig }
                    if (seg.isNotEmpty()) sb.append(makeRun(seg, "ins", author, date, ridFn()))
                }
                "replace" -> {
                    val dSeg = toksO.subList(i1, i2).joinToString("") { it.orig }
                    if (dSeg.isNotEmpty()) {
                        sb.append(makeRun(dSeg, "del", author, date, ridFn()))
                        delRanges.add(Pair(toksO[i1].start, toksO[i2 - 1].end))
                    }
                    val iSeg = toksR.subList(j1, j2).joinToString("") { it.orig }
                    if (iSeg.isNotEmpty()) sb.append(makeRun(iSeg, "ins", author, date, ridFn()))
                }
            }
        }
        sb.append("</w:p>")
        return Pair(sb.toString(), delRanges)
    }

    private fun tokenize(text: String, level: String): List<Token> {
        if (level == "char") {
            return text.mapIndexed { i, c -> Token(c.toString(), c.toString(), i, i + 1) }
        }
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            when {
                isCJK(ch) -> {
                    tokens.add(Token(ch.toString(), ch.toString(), i, i + 1)); i++
                }
                ch.isLetterOrDigit() || ch == '_' -> {
                    var j = i
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                    val word = text.substring(i, j)
                    tokens.add(Token(word, word, i, j)); i = j
                }
                else -> {
                    tokens.add(Token(ch.toString(), ch.toString(), i, i + 1)); i++
                }
            }
        }
        return tokens
    }

    private fun isCJK(c: Char): Boolean = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

    private fun normalizeTokens(tokens: List<Token>, caseSensitive: Boolean, ignoreWs: Boolean): List<Token> {
        return tokens.map { t ->
            var norm = t.orig
            if (!caseSensitive) norm = norm.lowercase(Locale.getDefault())
            if (ignoreWs) norm = norm.replace("\\s+".toRegex(), "")
            Token(t.orig, norm, t.start, t.end)
        }
    }

    // ════════════════════════════════════════════════════════
    //  OOXML XML 构建
    // ════════════════════════════════════════════════════════

    private fun makeRun(text: String, kind: String?, author: String, date: String, rid: Int): String {
        val esc = escapeXml(text)
        return when (kind) {
            "ins" -> "<w:ins w:id=\"$rid\" w:author=\"$author\" w:date=\"$date\"><w:r><w:rPr><w:color w:val=\"2E74B5\"/><w:u w:val=\"single\"/></w:rPr><w:t xml:space=\"preserve\">$esc</w:t></w:r></w:ins>"
            "del" -> "<w:del w:id=\"$rid\" w:author=\"$author\" w:date=\"$date\"><w:r><w:rPr><w:strike/><w:color w:val=\"C00000\"/></w:rPr><w:t xml:space=\"preserve\">$esc</w:t></w:r></w:del>"
            else -> "<w:r><w:t xml:space=\"preserve\">$esc</w:t></w:r>"
        }
    }

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

    private fun buildPlainParagraph(text: String): String {
        val esc = escapeXml(text)
        return if (text.isNotEmpty()) "<w:p xmlns:w=\"$W\"><w:r><w:t xml:space=\"preserve\">$esc</w:t></w:r></w:p>"
               else "<w:p xmlns:w=\"$W\"/>"
    }

    private fun buildDeletedParagraph(text: String, author: String, date: String, ridFn: () -> Int): String {
        val sb = StringBuilder("<w:p xmlns:w=\"$W\">")
        if (text.isNotEmpty()) sb.append(makeRun(text, "del", author, date, ridFn()))
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun buildInsertedParagraph(text: String, author: String, date: String, ridFn: () -> Int): String {
        val sb = StringBuilder("<w:p xmlns:w=\"$W\">")
        if (text.isNotEmpty()) sb.append(makeRun(text, "ins", author, date, ridFn()))
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun buildNoteParagraph(label: String): String {
        val esc = escapeXml(label)
        return "<w:p xmlns:w=\"$W\"><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">$esc</w:t></w:r></w:p>"
    }

    // ════════════════════════════════════════════════════════
    //  统计辅助
    // ════════════════════════════════════════════════════════

    private fun countModifiedSentences(text: String, ranges: List<Pair<Int, Int>>): Int {
        if (text.isBlank() || ranges.isEmpty()) return 0
        val sents = splitSentences(text)
        var total = 0
        for ((s, e, sent) in sents) {
            for ((rs, re) in ranges) {
                if (e > rs && s < re) {
                    total += sent.replace("\\s".toRegex(), "").length; break
                }
            }
        }
        return total
    }

    private fun countTextChars(text: String): Int = text.replace("\\s".toRegex(), "").length

    private fun splitSentences(text: String): List<Triple<Int, Int, String>> {
        val parts = text.split("(?<=[。！？；\\n\\r])".toRegex())
        val res = mutableListOf<Triple<Int, Int, String>>()
        var pos = 0
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                res.add(Triple(pos, pos + part.length, part))
                pos += part.length
            }
        }
        return res
    }

    // ════════════════════════════════════════════════════════
    //  输出 DOCX 写入
    // ════════════════════════════════════════════════════════

    private fun writeOutputDocx(origDocx: File, outPath: String, bodyXmlParts: List<String>) {
        val bodyContent = bodyXmlParts.joinToString("\n")
        ZipFile(origDocx).use { zin ->
            ZipOutputStream(File(outPath).outputStream()).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    when (entry.name) {
                        "word/document.xml" -> {
                            zout.putNextEntry(ZipEntry("word/document.xml"))
                            zout.write(buildNewDocument(bodyContent).toByteArray(Charsets.UTF_8))
                            zout.closeEntry()
                        }
                        "word/settings.xml" -> {
                            val settingsXml = zin.getInputStream(entry).bufferedReader().readText()
                            zout.putNextEntry(ZipEntry("word/settings.xml"))
                            zout.write(buildSettingsWithTrackRevisions(settingsXml).toByteArray(Charsets.UTF_8))
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

    private fun buildNewDocument(bodyContent: String): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="$W" xmlns:r="$R" mc:Ignorable="w14 wp14">
  <w:body>
$bodyContent
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>
      <w:cols w:space="720"/>
      <w:docGrid w:linePitch="360"/>
    </w:sectPr>
  </w:body>
</w:document>"""
    }

    private fun buildSettingsWithTrackRevisions(originalSettings: String): String {
        return try {
            if (originalSettings.contains("trackRevisions")) {
                originalSettings.replace("<w:trackRevisions[^/]*/>".toRegex(), "<w:trackRevisions w:val=\"true\"/>")
            } else {
                originalSettings.replace("</w:settings>", "<w:trackRevisions w:val=\"true\"/></w:settings>")
            }
        } catch (_: Exception) {
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:settings xmlns:w="$W">
  <w:trackRevisions w:val="true"/>
</w:settings>"""
        }
    }

    // ════════════════════════════════════════════════════════
    //  工具
    // ════════════════════════════════════════════════════════

    private fun getNsTag(parser: XmlPullParser): String {
        val ns = parser.namespace ?: ""
        val local = parser.name ?: ""
        return if (ns.isNotEmpty()) "$ns:$local" else local
    }

    data class Quadruple(val tag: String, val i1: Int, val i2: Int, val j1: Int, val j2: Int)
}
