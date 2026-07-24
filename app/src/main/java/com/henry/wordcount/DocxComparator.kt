package com.henry.wordcount

import android.content.Context
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
 * 纯 Kotlin 实现的 DOCX 文档比较器。
 *
 * 完全不依赖 Python/lxml，用 Android 标准库（ZipFile + XmlPullParser）实现。
 * 替代原 Python 版 compare_docx（该版本因 Chaquopy lxml C 扩展崩溃无法使用）。
 *
 * 功能：
 * - 解析两份 DOCX 的段落+表格文本
 * - 段落级 difflib.SequenceMatcher 比较
 * - 字符/词级细粒度 diff（w:ins 蓝下划线 / w:del 红删除线）
 * - 输出带修订标记的标准 OOXML DOCX
 */
object DocxComparator {

    // ── OOXML 命名空间 ──
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val XML_SPACE = "http://www.w3.org/XML/1998/namespace"

    // ── 数据类 ──
    data class Block(val type: String, val text: String) // "p" 或 "tbl"
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

    /**
     * 比较两份 DOCX 文件。
     *
     * @param context Android context
     * @param origPath 原文档路径
     * @param revPath 修订文档路径
     * @param outPath 输出路径
     * @param optsJson JSON 格式的比较选项
     * @return CompareResult
     */
    fun compare(
        context: Context?,
        origPath: String,
        revPath: String,
        outPath: String,
        optsJson: String
    ): CompareResult {
        return try {
            doCompare(origPath, revPath, outPath, optsJson)
        } catch (e: Throwable) {
            CompareResult(ok = false, error = "${e.javaClass.simpleName}: ${e.message?.take(300)}")
        }
    }

    private fun doCompare(origPath: String, revPath: String, outPath: String, optsJson: String): CompareResult {
        // 前置校验
        val origFile = File(origPath)
        val revFile = File(revPath)
        if (!origFile.isFile()) return CompareResult(ok = false, error = "原文档不存在: $origPath")
        if (!revFile.isFile()) return CompareResult(ok = false, error = "修订文档不存在: $revPath")

        // 解析选项
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

        // 读取两份文档的块
        val blocksO = readDocxBlocks(origFile)
        val blocksR = readDocxBlocks(revFile)

        // 段落级 diff
        val opcodes = diffBlocks(blocksO.map { it.text }, blocksR.map { it.text })

        // 合并相邻 delete+insert 为 replace
        val merged = mergeReplace(opcodes)

        // 构建输出 body XML
        val ridSeq = intArrayOf(0)
        fun nextRid(): Int { ridSeq[0]++; return ridSeq[0] }

        val bodyParts = mutableListOf<String>()
        var modifiedChars = 0
        var insCount = 0
        var delCount = 0
        var repCount = 0

        for ((tag, i1, i2, j1, j2) in merged) {
            when (tag) {
                "equal" -> {
                    for (k in i1 until i2) {
                        val el = blocksO[k]
                        if (el.type == "p") bodyParts.add(buildPlainParagraph(el.text))
                        else bodyParts.add(buildNoteParagraph("[原文表格] ${el.text.take(200)}"))
                    }
                }
                "delete" -> {
                    for (k in i1 until i2) {
                        val el = blocksO[k]
                        if (el.type == "p") {
                            bodyParts.add(buildDeletedParagraph(el.text, author, date, ::nextRid))
                            modifiedChars += countModifiedSentences(el.text, listOf(Pair(0, el.text.length)))
                        } else {
                            bodyParts.add(buildNoteParagraph("[已删除表格] ${el.text.take(200)}"))
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
                    val single = (i2 - i1 == 1 && j2 - j1 == 1)
                    if (single) {
                        val bo = blocksO[i1]
                        val br = blocksR[j1]
                        if (bo.type == "p" && br.type == "p") {
                            val (pXml, ranges) = buildDiffParagraph(
                                bo.text, br.text, level, author, date, ::nextRid,
                                caseSensitive, ignoreWs
                            )
                            bodyParts.add(pXml)
                            modifiedChars += countModifiedSentences(bo.text, ranges)
                            repCount++
                            continue
                        }
                        if (bo.type == "tbl" && br.type == "tbl" && useTable) {
                            bodyParts.add(buildNoteParagraph("[原表格] ${bo.text.take(200)}"))
                            bodyParts.add(buildNoteParagraph("[修订表格] ${br.text.take(200)}"))
                            modifiedChars += countTextChars(bo.text)
                            repCount++
                            continue
                        }
                    }
                    // 多块替换或类型不匹配
                    for (k in i1 until i2) {
                        val el = blocksO[k]
                        if (el.type == "p") {
                            bodyParts.add(buildDeletedParagraph(el.text, author, date, ::nextRid))
                            modifiedChars += countModifiedSentences(el.text, listOf(Pair(0, el.text.length)))
                        } else {
                            bodyParts.add(buildNoteParagraph("[已删除表格] ${el.text.take(200)}"))
                            modifiedChars += countTextChars(el.text)
                        }
                        delCount++
                    }
                    for (k in j1 until j2) {
                        val el = blocksR[k]
                        if (el.type == "p") bodyParts.add(buildInsertedParagraph(el.text, author, date, ::nextRid))
                        else bodyParts.add(buildNoteParagraph("[新增表格] ${el.text.take(200)}"))
                        insCount++
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
            val textO = extractExtraText(origFile, kind)
            val textR = extractExtraText(revFile, kind)
            if (textO != textR) {
                bodyParts.add(buildNoteParagraph(label))
                val (pXml, _) = buildDiffParagraph(textO, textR, level, author, date, ::nextRid, caseSensitive, ignoreWs)
                bodyParts.add(pXml)
            }
        }

        // 写出输出 DOCX
        writeOutputDocx(origFile, outPath, bodyParts)

        val summary = buildString {
            append("插入 $insCount 段 | 删除 $delCount 段 | 修改 $repCount 段")
            if (modifiedChars > 0) append(" | 修改字数约 $modifiedChars")
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

    // ════════════════════════════════════════════════════════
    //  DOCX 读取
    // ════════════════════════════════════════════════════════

    /**
     * 从 DOCX 中读取所有段落和表格文本。
     * 返回 [(type, text), ...]，type 为 "p" 或 "tbl"。
     */
    fun readDocxBlocks(docx: File): List<Block> {
        val blocks = mutableListOf<Block>()
        ZipFile(docx).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return emptyList()
            val xml = zip.getInputStream(entry).bufferedReader().readText()
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

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val nsTag = getNsTag(parser)
                        when (nsTag) {
                            "$W:body" -> inBody = true
                            "$W:p" -> if (inBody && !inTbl) { inP = true; pText = StringBuilder() }
                            "$W:tbl" -> if (inBody) { inTbl = true; tblTexts = mutableListOf() }
                            "$W:tr" -> if (inTbl) inTr = true
                            "$W:tc" -> if (inTr) inTc = true
                            "$W:p" -> if (inTc) { /* 表格内的段落，重置 */ pText = StringBuilder() }
                            "$W:t" -> inT = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inT) {
                            val text = parser.text ?: ""
                            if (inTc) {
                                // 表格单元格内文本 — 稍后处理
                                pText.append(text)
                            } else if (inP) {
                                pText.append(text)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val nsTag = getNsTag(parser)
                        when (nsTag) {
                            "$W:t" -> inT = false
                            "$W:p" -> {
                                if (inTc) {
                                    // 表格单元格内的段落结束
                                    tblTexts.add(pText.toString())
                                    pText = StringBuilder()
                                } else if (inP) {
                                    blocks.add(Block("p", pText.toString()))
                                    inP = false
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
        }
        return blocks
    }

    /**
     * 提取附加区域文本（页眉页脚/脚注/文本框/域）。
     */
    private fun extractExtraText(docx: File, kind: String): String {
        val parts = mutableListOf<String>()
        try {
            ZipFile(docx).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return ""
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                val factory = XmlPullParserFactory.newInstance()
                factory.setNamespaceAware(true)
                val parser = factory.newPullParser()
                parser.setInput(StringReader(xml))

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
                                inTarget = true
                                depth = 1
                                currentText = StringBuilder()
                                // 对于 headerReference/footerReference，提取 r:id
                                if (kind == "header_footer") {
                                    val rid = parser.getAttributeValue(R, "id") ?: ""
                                    parts.add("[header:${if (nsTag.contains("footer")) "footer" else "header"}:$rid]")
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
    //  Diff 算法（简化版 difflib.SequenceMatcher）
    // ════════════════════════════════════════════════════════

    /**
     * 计算两个列表的 diff opcodes。
     * 返回 [(tag, i1, i2, j1, j2), ...]，tag 为 equal/delete/insert/replace。
     */
    private fun <T> diffBlocks(a: List<T>, b: List<T>): List<Quadruple> {
        // 使用 LCS（最长公共子序列）计算 opcodes
        val m = a.size
        val n = b.size
        if (m == 0) return if (n == 0) emptyList() else listOf(Quadruple("insert", 0, 0, 0, n))
        if (n == 0) return listOf(Quadruple("delete", 0, m, 0, 0))

        // LCS 动态规划表（仅保留前一行以节省内存）
        val prev = IntArray(n + 1)
        val curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = 0
            for (j in 1..n) {
                curr[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1
                           else maxOf(curr[j - 1], prev[j])
            }
            System.arraycopy(curr, 0, prev, 0, n + 1)
        }

        // 回溯构建 opcodes
        val opcodes = mutableListOf<Quadruple>()
        var i = m
        var nIdx = n

        while (i > 0 || nIdx > 0) {
            if (i > 0 && nIdx > 0 && a[i - 1] == b[nIdx - 1]) {
                i--; nIdx--
            } else if (nIdx > 0 && (i == 0 || curr[nIdx] >= prev[nIdx])) {
                // insert at b[nIdx-1]
                // 收集连续的 insert
                val startJ = nIdx
                while (nIdx > 0 && (i == 0 || a[i - 1] != b[nIdx - 1]) &&
                       (nIdx == 1 || curr[nIdx - 1] < curr[nIdx])) {
                    nIdx--
                }
                opcodes.add(Quadruple("insert", i, i, nIdx, startJ))
            } else if (i > 0) {
                // delete from a[i-1]
                val startI = i
                while (i > 0 && (nIdx == 0 || a[i - 1] != b[nIdx - 1]) &&
                       (i == 1 || prev[i - 1] < prev[i])) {
                    i--
                }
                opcodes.add(Quadruple("delete", i, startI, nIdx, nIdx))
            } else {
                break
            }
        }

        opcodes.reverse()

        // 合并相邻相同 tag 的操作并转换为标准格式
        // 同时处理 replace（相邻 delete+insert）
        return normalizeOpcodes(opcodes, m, n)
    }

    /**
     * 合并相邻 delete+insert 为 replace 操作。
     */
    private fun mergeReplace(opcodes: List<Quadruple>): List<Quadruple> {
        val result = mutableListOf<Quadruple>()
        var i = 0
        while (i < opcodes.size) {
            val op = opcodes[i]
            if (op.tag == "delete" && i + 1 < opcodes.size && opcodes[i + 1].tag == "insert") {
                val next = opcodes[i + 1]
                if ((op.i2 - op.i1) == (next.j2 - next.j1)) {
                    result.add(Quadruple("replace", op.i1, op.i2, next.j1, next.j2))
                    i += 2
                    continue
                }
            }
            if (op.tag == "insert" && i + 1 < opcodes.size && opcodes[i + 1].tag == "delete") {
                val next = opcodes[i + 1]
                if ((next.i2 - next.i1) == (op.j2 - op.j1)) {
                    result.add(Quadruple("replace", next.i1, next.i2, op.j1, op.j2))
                    i += 2
                    continue
                }
            }
            result.add(op)
            i++
        }
        return result
    }

    /**
     * 标准化 opcodes —— 补充 equal 区域，确保覆盖整个序列。
     */
    private fun normalizeOpcodes(raw: List<Quadruple>, m: Int, n: Int): List<Quadruple> {
        if (raw.isEmpty()) return listOf(Quadruple("equal", 0, m, 0, n))

        val result = mutableListOf<Quadruple>()
        var ai = 0
        var bj = 0

        for (op in raw) {
            // 补充前面的 equal 区域
            if (op.i1 > ai || op.j1 > bj) {
                result.add(Quadruple("equal", ai, op.i1, bj, op.j1))
            }
            result.add(op)
            ai = op.i2
            bj = op.j2
        }

        // 补充末尾的 equal 区域
        if (ai < m || bj < n) {
            result.add(Quadruple("equal", ai, m, bj, n))
        }

        return result
    }

    // ════════════════════════════════════════════════════════
    //  字符/词级 Diff
    // ════════════════════════════════════════════════════════

    /**
     * 对两个段落做字符/词级 diff。
     * 返回 (XML字符串, 删除范围列表)。
     */
    private fun buildDiffParagraph(
        textO: String, textR: String, level: String,
        author: String, date: String, ridFn: () -> Int,
        caseSensitive: Boolean, ignoreWs: Boolean
    ): Pair<String, List<Pair<Int, Int>>> {
        val toksO = tokenize(textO, level)
        val toksR = tokenize(textR, level)
        val normO = normalizeTokens(toksO, caseSensitive, ignoreWs)
        val normR = normalizeTokens(toksR, caseSensitive, ignoreWs)

        val opcodes = diffBlocks(normO.map { it.norm }, normR.map { it.norm })

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
                        val s = toksO[i1].start
                        val e = toksO[i2 - 1].end
                        delRanges.add(Pair(s, e))
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

    /**
     * 分词：返回 list of Token。
     * level="char" → 逐字符；level="word" → CJK单字 + 英文单词 + 其他单字符。
     */
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
                    tokens.add(Token(ch.toString(), ch.toString(), i, i + 1))
                    i++
                }
                ch.isLetterOrDigit() || ch == '_' -> {
                    var j = i
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                    val word = text.substring(i, j)
                    tokens.add(Token(word, word, i, j))
                    i = j
                }
                else -> {
                    tokens.add(Token(ch.toString(), ch.toString(), i, i + 1))
                    i++
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

    /** 构建 w:r 元素（可包裹在 w:ins/w:del 中）。kind: "ins"|""|null */
    private fun makeRun(text: String, kind: String?, author: String, date: String, rid: Int): String {
        val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return when (kind) {
            "ins" -> """<w:ins w:id="$rid" w:author="$author" w:date="$date"><w:r><w:rPr><w:color w:val="2E74B5"/><w:u w:val="single"/></w:rPr><w:t xml:space="preserve">$esc</w:t></w:r></w:ins>"""
            "del" -> """<w:del w:id="$rid" w:author="$author" w:date="$date"><w:r><w:rPr><w:strike/><w:color w:val="C00000"/></w:rPr><w:t xml:space="preserve">$esc</w:t></w:r></w:del>"""
            else -> """<w:r><w:t xml:space="preserve">$esc</w:t></w:r>"""
        }
    }

    private fun buildPlainParagraph(text: String): String {
        val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return if (text.isNotEmpty()) """<w:p xmlns:w="$W"><w:r><w:t xml:space="preserve">$esc</w:t></w:r></w:p>"""
               else """<w:p xmlns:w="$W"/>"""
    }

    private fun buildDeletedParagraph(text: String, author: String, date: String, ridFn: () -> Int): String {
        val sb = StringBuilder("""<w:p xmlns:w="$W">""")
        if (text.isNotEmpty()) sb.append(makeRun(text, "del", author, date, ridFn()))
        sb.append("""</w:p>""")
        return sb.toString()
    }

    private fun buildInsertedParagraph(text: String, author: String, date: String, ridFn: () -> Int): String {
        val sb = StringBuilder("""<w:p xmlns:w="$W">""")
        if (text.isNotEmpty()) sb.append(makeRun(text, "ins", author, date, ridFn()))
        sb.append("""</w:p>""")
        return sb.toString()
    }

    private fun buildNoteParagraph(label: String): String {
        val esc = label.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt>")
        return """<w:p xmlns:w="$W"><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">$esc</w:t></w:r></w:p>"""
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
                    total += sent.replace("\\s".toRegex(), "").length
                    break
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

    /**
     * 写出比较结果 DOCX：基于原文档 ZIP 模板，替换 document.xml 和 settings.xml。
     */
    private fun writeOutputDocx(origDocx: File, outPath: String, bodyXmlParts: List<String>) {
        val bodyContent = bodyXmlParts.joinToString("\n")

        ZipFile(origDocx).use { zin ->
            ZipOutputStream(File(outPath).outputStream()).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    when (entry.name) {
                        "word/document.xml" -> {
                            val newDoc = buildNewDocument(bodyContent)
                            val newEntry = ZipEntry("word/document.xml")
                            zout.putNextEntry(newEntry)
                            zout.write(newDoc.toByteArray(Charsets.UTF_8))
                            zout.closeEntry()
                        }
                        "word/settings.xml" -> {
                            // 添加 trackRevisions
                            val newSettings = buildSettingsWithTrackRevisions(zin.getInputStream(entry).bufferedReader().readText())
                            val newEntry = ZipEntry("word/settings.xml")
                            zout.putNextEntry(newEntry)
                            zout.write(newSettings.toByteArray(Charsets.UTF_8))
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

    /**
     * 构建新的 document.xml，将 body 内容替换为比较结果。
     */
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

    /**
     * 在 settings.xml 中添加/更新 trackRevisions 元素。
     */
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

    /** 获取带命名空间前缀的标签名 */
    private fun getNsTag(parser: XmlPullParser): String {
        val ns = parser.namespace ?: ""
        val local = parser.name ?: ""
        return if (ns.isNotEmpty()) "$ns:$local" else local
    }

    /** 四元组：(tag, i1, i2, j1, j2) */
    data class Quadruple(val tag: String, val i1: Int, val i2: Int, val j1: Int, val j2: Int)
}
