package com.henry.wordcount

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max

/**
 * 把比较结果（或任意）DOCX 渲染成长图 PNG（v1.1.82：修复 ins/del 深度计数器被自闭合标签/误匹配污染）。
 * 纯 Kotlin + Android Canvas 实现，零第三方依赖（不触碰 Chaquopy/lxml）。
 * 颜色规则：纯黑=未改动；蓝色=插入(ins)；红色删除线=删除(del)。
 * 编号：从 &lt;w:numPr&gt; 提取 numId/ilvl，自动递增渲染 "1. " "2. "... 前缀。
 */
object DocxImageRenderer {

    private const val TAG = "DocxImageRenderer"

    // 渲染比例（每 point 对应的像素）。A4 宽 595pt。
    private const val PT = 1.6f
    private const val PAGE_W = (595 * PT).toInt()          // A4 宽
    private const val MARGIN = (54 * PT).toInt()           // 0.75 英寸页边距
    private const val DEFAULT_SZ = 21                      // 默认字号（半磅，10.5pt）
    private const val LINE_FACTOR = 1.5f                   // 行距
    private const val PARA_GAP = (6 * PT).toInt()          // 段落间距
    private const val COLOR_INS = 0xFF0000FF.toInt()       // 蓝（插入）
    private const val COLOR_DEL = 0xFFFF0000.toInt()       // 红（删除）
    private const val MAX_H = 30000                        // 长图高度上限，避免 OOM

    private const val MARK_NONE = 0
    private const val MARK_INS = 1
    private const val MARK_DEL = 2

    private data class Seg(
        val text: String,
        val color: Int,
        val strike: Boolean,
        val underline: Boolean,
        val sizeHalf: Int,
        val breakLine: Boolean = false
    )

    private data class RawLine(val segs: List<Seg>, val x: Float, val maxSz: Int)

    private data class LayoutLine(
        val segs: List<Seg>,
        val x: Float,
        val baselineY: Float,
        val height: Float
    )

    private data class Block(
        val segs: List<Seg>,
        val firstLineTwips: Int,
        val leftTwips: Int,
        val hangingTwips: Int,
        val numPrefix: String = ""         // 编号前缀，如 "1. " "2. "
    )

    /** 编号计数器：key=(numId, ilvl) → value=当前序号 */
    private data class NumKey(val numId: Int, val ilvl: Int)

    fun render(docxPath: String, outPngPath: String): Boolean {
        return try {
            val xml = readDocumentXml(docxPath) ?: return false
            val numCounters = mutableMapOf<NumKey, Int>()
            val blocks = parseBody(xml, numCounters)
            val lines = layout(blocks)
            val totalH = computeHeight(lines)
            if (totalH <= MARGIN * 2 + 1) return false
            val safeH = max(MARGIN * 2, minOf(totalH, MAX_H))
            val bmp = Bitmap.createBitmap(PAGE_W, safeH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            drawLines(canvas, lines)
            val out = File(outPngPath)
            out.parentFile?.mkdirs()
            val os = out.outputStream()
            val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            os.close()
            bmp.recycle()
            ok
        } catch (e: Throwable) {
            Log.w(TAG, "render failed: ${e.message}")
            false
        }
    }

    // ---------- 解析 ----------

    private fun readDocumentXml(path: String): String? {
        return try {
            val zf = ZipFile(path)
            val entry = zf.getEntry("word/document.xml")
            if (entry == null) {
                zf.close()
                null
            } else {
                zf.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .also { zf.close() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "readDocumentXml failed: ${e.message}")
            null
        }
    }

    /**
     * v1.3.25: 用正则提取 <w:p> 和 <w:tbl>（替代 v1.3.24 的手动 indexOf+findCloseTag 深度计数）。
     * 教训（v1.3.18/v1.3.23）：OOXML 嵌套结构下手写标签匹配/深度计数极易错乱；
     * 正则 DOT_MATCHES_ALL 非贪婪匹配在跨运行时一致且更可靠。
     */
    private fun parseBody(xml: String, numCounters: MutableMap<NumKey, Int>): List<Block> {
        val blocks = mutableListOf<Block>()
        val bodyMatch = Regex("""<w:body\b[^>]*>(.*?)</w:body>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
        val body = bodyMatch?.groupValues?.get(1) ?: xml

        // 收集所有 <w:p> 和 <w:tbl> 的 (start, end, type) 位置
        data class Elem(val start: Int, val end: Int, val isTbl: Boolean)
        val elems = mutableListOf<Elem>()

        // 正则提取所有顶层 <w:p>（非贪婪，按出现顺序）
        for (m in Regex("""<w:p\b[^>]*>.*?</w:p>""", RegexOption.DOT_MATCHES_ALL).findAll(body)) {
            elems.add(Elem(m.range.first, m.range.last + 1, false))
        }
        // 正则提取所有 <w:tbl>
        for (m in Regex("""<w:tbl\b.*?</w:tbl>""", RegexOption.DOT_MATCHES_ALL).findAll(body)) {
            elems.add(Elem(m.range.first, m.range.last + 1, true))
        }

        // 按位置排序，依次处理（保持文档自然顺序）
        elems.sortBy { it.start }
        for (elem in elems) {
            val xmlSnippet = body.substring(elem.start, elem.end)
            if (elem.isTbl) {
                blocks.addAll(parseTable(xmlSnippet, numCounters))
            } else {
                blocks.add(parseParagraphBlock(xmlSnippet, numCounters))
            }
        }
        return blocks
    }

    /**
     * v1.3.25: 用正则提取 <w:tr>/<w:tc>（替代手动 indexOf+findCloseTag）。
     * 每行 = 一个 Block，单元格间用 "   " 分隔横向排版。
     * 保底：如果结构解析失败，直接从表格 XML 抽全部文本作为单个 Block。
     */
    private fun parseTable(tx: String, numCounters: MutableMap<NumKey, Int>): List<Block> {
        val res = mutableListOf<Block>()

        // 正则提取所有 <w:tr>
        val trMatches = Regex("""<w:tr\b[^>]*>.*?</w:tr>""", RegexOption.DOT_MATCHES_ALL).findAll(tx)
        var hasContent = false
        for (trM in trMatches) {
            val trXml = trM.value
            val rowSegs = mutableListOf<Seg>()
            var firstCell = true

            // 正则提取该行所有 <w:tc>
            val tcMatches = Regex("""<w:tc\b[^>]*>.*?</w:tc>""", RegexOption.DOT_MATCHES_ALL).findAll(trXml)
            for (tcM in tcMatches) {
                val cellSegs = parseCell(tcM.value, numCounters)
                if (!firstCell && cellSegs.isNotEmpty()) {
                    rowSegs.add(Seg("   ", Color.BLACK, false, false, DEFAULT_SZ))
                }
                rowSegs.addAll(cellSegs)
                firstCell = false
            }

            if (rowSegs.isNotEmpty()) {
                res.add(Block(rowSegs, 0, 0, 0, ""))
                hasContent = true
            }
        }

        // 保底：如果结构解析没产生任何内容，直接抽文本
        if (!hasContent) {
            val fallbackTexts = Regex("""<w:t[^>]*>([^<]*)</w:t>""").findAll(tx)
                .map { unescape(it.groupValues[1]) }.filter { it.isNotEmpty() }
            if (fallbackTexts.any()) {
                val segs = fallbackTexts.map { Seg(it, Color.BLACK, false, false, DEFAULT_SZ) }
                res.add(Block(segs.toList(), 0, 0, 0, ""))
            }
        }

        return res
    }
    
    /** v1.3.25: 用正则提取单元格内 <w:p>（替代手动 indexOf+findCloseTag）。 */
    private fun parseCell(tcXml: String, numCounters: MutableMap<NumKey, Int>): List<Seg> {
        val segs = mutableListOf<Seg>()
        val pMatches = Regex("""<w:p\b[^>]*>.*?</w:p>""", RegexOption.DOT_MATCHES_ALL).findAll(tcXml)
        for (pM in pMatches) {
            segs.addAll(parseParagraphSegs(pM.value))
        }
        return segs
    }

    private fun parseParagraphBlock(px: String, numCounters: MutableMap<NumKey, Int>): Block {
        val rawSegs = parseParagraphSegs(px)
        val pPr = Regex("""<w:pPr.*?</w:pPr>""", RegexOption.DOT_MATCHES_ALL).find(px)?.value
        var firstLine = 0
        var left = 0
        var hanging = 0
        var numPrefix = ""
        // 检测是否为纯删除段落（pPr 的 rPr 中含 <w:del> 标记，或段落内容几乎全在 <w:del> 内）
        val isDelParagraph = detectDelParagraph(px)
        pPr?.let {
            firstLine = Regex("""<w:ind[^>]*w:firstLine="(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            left = Regex("""<w:ind[^>]*w:left="(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            hanging = Regex("""<w:ind[^>]*w:hanging="(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            // 提取编号 <w:numPr><w:ilvl w:val="X"/><w:numId w:val="Y"/></w:numPr>
            val numM = Regex("""<w:numPr>\s*<w:ilvl\s+w:val="(\d+)"/?>\s*<w:numId\s+w:val="(\d+)"/>.*?</w:numPr>""",
                RegexOption.DOT_MATCHES_ALL).find(it)
            if (numM != null) {
                val ilvl = numM.groupValues[1].toIntOrNull() ?: 0
                val numId = numM.groupValues[2].toIntOrNull() ?: 0
                val key = NumKey(numId, ilvl)
                val nextNum = (numCounters[key] ?: 0) + 1
                numCounters[key] = nextNum
                // 根据层级生成编号格式：ilvl=0 → "1. " ; ilvl=1 → "a) " 等（简化为数字+句点）
                numPrefix = when (ilvl) {
                    0 -> "$nextNum. "
                    1 -> "$nextNum. "
                    else -> "$nextNum. "
                }
            } else {
                // 无 numPr 时重置所有计数器（Word 在非列表段落处不重置，
                // 但为安全起见不做自动重置，仅不生成前缀）
            }
        }

        // 对纯删除段强制修正颜色为红色+删除线（安全网）
        val segs = if (isDelParagraph) {
            rawSegs.map { seg ->
                if (seg.text.isEmpty()) seg
                else seg.copy(color = COLOR_DEL, strike = true)
            }
        } else {
            rawSegs
        }

        // 如果有编号前缀，把它作为黑色 Seg 段插入到最前面（编号本身不应该是红色）
        val finalSegs = if (numPrefix.isNotEmpty()) {
            listOf(Seg(numPrefix, Color.BLACK, false, false, DEFAULT_SZ)) + segs
        } else {
            segs
        }

        return Block(finalSegs, firstLine, left, hanging, numPrefix)
    }

    /** 检测段落是否为纯删除段（整个段落内容都是被删除的）。 */
    private fun detectDelParagraph(px: String): Boolean {
        // 先统计文本量：正常 <w:t> 文本 vs <w:delText> 删除文本
        val normalTextLen = Regex("""<w:t(?![a-zA-Z])[^>]*>(.*?)</w:t>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(px).sumOf { it.groupValues[1].replace(Regex("\\s"), "").length }
        val delTextLen = Regex("""<w:delText[^>]*>(.*?)</w:delText>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(px).sumOf { it.groupValues[1].length }

        // 如果没有实质删除内容，不是删除段
        if (delTextLen <= 0) return false

        // 方法1：pPr 的 rPr 中包含 <w:del> 段落级删除标记（Word 原生比较格式）
        // 仅当正常文本很少时才判定为纯删除段
        val pprM = Regex("""<w:pPr.*?</w:pPr>""", RegexOption.DOT_MATCHES_ALL).find(px)
        if (pprM != null && Regex("""<w:del\b(?![Tt])""").containsMatchIn(pprM.value)) {
            if (normalTextLen <= delTextLen * 0.15) return true  // 正常文本 ≤ 删除文本的 15%
        }

        // 方法2：顶层 <w:del> 包裹且正常文本极少（我们的比较器输出格式）
        val hasTopLevelDel = Regex("""<w:del\b(?![Tt])[^>]*>""").find(px) != null
        if (hasTopLevelDel && normalTextLen <= 2) return true

        return false
    }

    private fun parseParagraphSegs(px: String): List<Seg> {
        val segs = mutableListOf<Seg>()
        var insDepth = 0
        var delDepth = 0
        var i = 0
        val n = px.length
        while (i < n) {
            val lt = px.indexOf('<', i)
            if (lt < 0) break
            val gt = px.indexOf('>', lt)
            if (gt < 0) break
            val tag = px.substring(lt, gt + 1)
            when {
                // v1.1.82 修复：精确匹配 <w:ins>/<w:del> 开标签，排除：
                //   1) 自闭合标签（pPr/rPr 中的 <w:ins ../> / <w:del ../> 属性标记）
                //   2) 以 "ins" 开头的其他标签如 <w:instrText>
                isOpenTag(tag, "w:ins") -> insDepth++
                tag == "</w:ins>" -> insDepth--
                isOpenTag(tag, "w:del") && !tag.startsWith("<w:delText") -> delDepth++
                tag == "</w:del>" -> delDepth--
                // v1.1.82 修复：精确匹配 <w:r> 开标签（非自闭合），排除 <w:rFonts>/<w:rStyle> 等
                // 假 run 标签被误判后，</w:r> 搜索会跳过大量 XML（含 <w:ins>/<w:del>）
                isOpenTag(tag, "w:r") -> {
                    val re = px.indexOf("</w:r>", gt)
                    if (re < 0) { i = gt + 1; continue }
                    val runXml = px.substring(gt + 1, re)
                    val (rColor, rSz, rStrike, rUnderline) = parseRunRPr(runXml)
                    val mark = if (delDepth > 0) MARK_DEL else if (insDepth > 0) MARK_INS else MARK_NONE
                    val tokRe = Regex(
                        """<(w:t|w:delText)(?![a-zA-Z])[^>]*>(.*?)</\1>|<w:tab\s*/?>|<w:br\s*/?>|<w:cr\s*/?>""",
                        RegexOption.DOT_MATCHES_ALL
                    )
                    for (m in tokRe.findAll(runXml)) {
                        when {
                            m.groupValues[1].isNotEmpty() -> {
                                val isDelText = m.groupValues[1] == "w:delText"
                                val text = unescape(m.groupValues[2])
                                if (text.isNotEmpty()) {
                                    val c = when {
                                        isDelText || mark == MARK_DEL -> COLOR_DEL
                                        mark == MARK_INS -> COLOR_INS
                                        else -> (rColor ?: Color.BLACK)
                                    }
                                    segs.add(
                                        Seg(
                                            text, c,
                                            rStrike || isDelText || mark == MARK_DEL,
                                            rUnderline, rSz
                                        )
                                    )
                                }
                            }
                            m.value.startsWith("<w:tab") ->
                                segs.add(Seg("    ", rColor ?: Color.BLACK, rStrike, rUnderline, rSz))
                            else ->
                                segs.add(Seg("", rColor ?: Color.BLACK, rStrike, rUnderline, rSz, breakLine = true))
                        }
                    }
                    i = re + 6
                    continue
                }
            }
            i = gt + 1
        }
        return segs
    }

    private fun parseRunRPr(runXml: String): Quad<Int?, Int, Boolean, Boolean> {
        val colorM = Regex("""<w:color[^>]*w:val="([0-9A-Fa-f]{6})""").find(runXml)
        val color = colorM?.groupValues?.get(1)?.let {
            if (it.equals("auto", true)) null else try {
                Color.parseColor("#$it")
            } catch (e: Throwable) { null }
        }
        val sz = Regex("""<w:sz[^>]*w:val="(\d+)""").find(runXml)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_SZ
        val strike = runXml.contains("<w:strike")
        val underline = Regex("""<w:u[^>]*w:val="""").containsMatchIn(runXml) &&
                !Regex("""w:val="none"""").containsMatchIn(runXml)
        return Quad(color, sz, strike, underline)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun findCloseTag(s: String, openIdx: Int, openTag: String, closeTag: String): Int {
        var depth = 0
        var i = openIdx
        val n = s.length
        val openLen = openTag.length
        while (i < n) {
            val o = s.indexOf(openTag, i)
            val c = s.indexOf(closeTag, i)
            if (c < 0) return n
            if (o in 0 until c) {
                // 必须是真正的标签开始（"<w:p" 后接空白或 '>'），避免把 <w:pPr> 等误判为段落
                val after = s.getOrElse(o + openLen) { '>' }
                if (after in " >/\t\n") {
                    depth++
                    i = o + openLen
                } else {
                    i = o + openLen
                }
            } else {
                depth--
                i = c + closeTag.length
                if (depth == 0) return i
            }
        }
        return n
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    /**
     * 精确匹配 OOXML 开标签（非自闭合）。
     * 要求 tag 以 "<name" 开头，且下一字符是空格、> 或 /（排除 <w:instrText> 被误匹配为 <w:ins），
     * 同时排除自闭合标签（以 "/>" 结尾），如 pPr/rPr 中的 <w:ins ../> / <w:del ../>。
     */
    private fun isOpenTag(tag: String, name: String): Boolean {
        if (!tag.startsWith("<$name")) return false
        val afterName = tag.getOrElse(name.length + 1) { return false }
        // 下一字符必须是空格、> 或 /（确保标签名精确匹配，不把 <w:instrText 当 <w:ins）
        if (afterName !in " />") return false
        // 排除自闭合标签
        return !tag.endsWith("/>")
    }

    // ---------- 布局 ----------

    private fun layout(blocks: List<Block>): List<LayoutLine> {
        val out = mutableListOf<LayoutLine>()
        var y = MARGIN.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (block in blocks) {
            val leftPx = block.leftTwips * PT / 20f
            val firstExtra = if (block.hangingTwips > 0) -(block.hangingTwips * PT / 20f)
            else (block.firstLineTwips * PT / 20f)
            val leftX = MARGIN + leftPx
            val firstLineX = leftX + firstExtra
            val lineMaxW = (PAGE_W - MARGIN) - leftX
            val rawLines = wrapParagraph(block.segs, firstLineX, leftX, lineMaxW, paint)
            for (raw in rawLines) {
                val h = raw.maxSz / 2f * PT * LINE_FACTOR
                val baseline = y + h * 0.82f
                out.add(LayoutLine(raw.segs, raw.x, baseline, h))
                y += h
            }
            y += PARA_GAP
        }
        return out
    }

    private fun wrapParagraph(
        segs: List<Seg>, firstLineX: Float, leftX: Float, lineMaxW: Float, paint: Paint
    ): List<RawLine> {
        val raw = mutableListOf<RawLine>()
        if (segs.isEmpty()) {
            raw.add(RawLine(emptyList(), leftX, DEFAULT_SZ))
            return raw
        }
        var curSegs = mutableListOf<Seg>()
        var curW = 0f
        var x = firstLineX
        var maxSz = DEFAULT_SZ
        fun flush() {
            if (curSegs.isNotEmpty()) {
                raw.add(RawLine(curSegs.toList(), x, maxSz))
                curSegs = mutableListOf()
                curW = 0f
                maxSz = DEFAULT_SZ
                x = leftX
            }
        }
        for (seg in segs) {
            if (seg.breakLine) {
                flush()
                x = leftX
                continue
            }
            var remaining = seg.text
            while (remaining.isNotEmpty()) {
                val avail = lineMaxW - curW
                var fit = fitChars(paint, seg, remaining, avail)
                if (fit <= 0) {
                    flush()
                    val avail2 = lineMaxW - curW
                    fit = fitChars(paint, seg, remaining, avail2)
                    if (fit <= 0) fit = 1
                }
                val piece = remaining.substring(0, fit)
                curSegs.add(seg.copy(text = piece))
                curW += measure(paint, seg, piece)
                if (seg.sizeHalf > maxSz) maxSz = seg.sizeHalf
                remaining = remaining.substring(fit)
                if (remaining.isNotEmpty()) flush()
            }
        }
        flush()
        return raw
    }

    private fun fitChars(paint: Paint, seg: Seg, text: String, avail: Float): Int {
        if (avail <= 0f) return 0
        paint.textSize = seg.sizeHalf / 2f * PT
        var best = 0
        for (k in 1..text.length) {
            if (paint.measureText(text.substring(0, k)) <= avail) best = k else break
        }
        return best
    }

    private fun measure(paint: Paint, seg: Seg, s: String): Float {
        paint.textSize = seg.sizeHalf / 2f * PT
        return paint.measureText(s)
    }

    private fun computeHeight(lines: List<LayoutLine>): Int {
        if (lines.isEmpty()) return 0
        return (lines.last().baselineY + lines.last().height * 0.2f + MARGIN).toInt()
    }

    // ---------- 绘制 ----------

    private fun drawLines(canvas: Canvas, lines: List<LayoutLine>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (line in lines) {
            var x = line.x
            for (seg in line.segs) {
                if (seg.text.isEmpty()) continue
                paint.textSize = seg.sizeHalf / 2f * PT
                paint.color = seg.color
                paint.isStrikeThruText = seg.strike
                paint.isUnderlineText = seg.underline
                canvas.drawText(seg.text, x, line.baselineY, paint)
                x += paint.measureText(seg.text)
            }
        }
    }
}
