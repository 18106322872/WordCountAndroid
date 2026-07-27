package com.henry.wordcount

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.math.max

    /**
     * 纯 Kotlin 的 OOXML（docx / xlsx / pptx）文本抽取与页数统计层。
     *
     * v1.0.26 核心改进：
     *   - docx：移除过于激进的字母/CJK过滤（v1.0.24 要求 w:t 内容必须含字母或汉字，
     *          导致纯数字内容如身份证号、学年学期、分数、学分等被丢弃，丢失 ~340 字）
     *   - docx：移除页眉/页脚提取（Word「字数统计」的"包括文本框、脚注和尾注"不含页眉页脚）
     *   - docx：页数统计优化（避免纯文本长度估算导致 1 页文档报 4 页）
     *   - docx：<w:t> 内容做 strip-tags 二次清洗（v1.0.24 遗留，保留）
     */
object OoXmlEngine {

    data class OoxmlResult(
        val text: String,
        val pages: Int,
        val kind: String, // "docx" | "xlsx" | "pptx"
        val sheets: List<String> = emptyList(),
        // v1.3.3: 隐藏工作表（名称 + 抽取文本），默认不计入文件字数，由 UI 决定是否勾选合计
        val hiddenSheets: List<Pair<String, String>> = emptyList(),
        val pagesReason: String = "",
        // v1.2.3: docProps/app.xml 中的权威统计（Word/WPS 保存时写入，与 Word 字数统计完全一致）
        // 0 表示无此元数据（如 POI 生成的文件），由调用方退回现算
        val metaPages: Int = 0,
        val metaWords: Int = 0,
        val metaChars: Int = 0
    )

    fun extract(file: File): OoxmlResult? {
        val ext = file.extension.lowercase()
        if (ext !in setOf("docx", "xlsx", "pptx")) return null
        val zip = try { ZipFile(file) } catch (_: Throwable) { return null }
        return try {
            when (ext) {
                "docx" -> extractDocx(zip)
                "xlsx" -> extractXlsx(zip)
                "pptx" -> extractPptx(zip)
                else -> null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { zip.close() }
        }
    }

    // ───────────────────────── docx ─────────────────────────
    /**
     * 提取 docx 文本。
     *
     * 仅提取 word/document.xml（正文+文本框），不提取 header/footer。
     * Word「字数统计」对话框的"包括文本框、脚注和尾注"选项**不包括**页眉页脚，
     * 因此页眉页脚不计入统计口径。（v1.0.26 修复：旧版错误地计入了页眉页脚）
     *
     * v1.0.27 页数统计改进：
     *   Word 保存时会写入 w:lastRenderedPageBreak（上次渲染时的分页位置），这是最可靠
     *   的分页信号。优先级：lastRenderedPageBreak > 显式分页符 > sectPr 节数。
     *   三种信号取最大值，确保与 Word 打开时看到的页数一致。
     */
    private fun extractDocx(zip: ZipFile): OoxmlResult {
        val sb = StringBuilder()
        val pageCounter = intArrayOf(0)

        // 1) 主文档 body（word/document.xml）—— 唯一数据源
        val bodyXml = readEntry(zip, "word/document.xml") ?: ""
        appendDocxXmlText(bodyXml, sb) { pageCounter[0]++ }

        val text = sb.toString()
        // ── 页数统计（v1.1.14 重写：智能排版感知）──
        //
        // v1.1.13 问题分析（用户用 Word 另存后的文件实测）：
        //   1.docx: LRP=4 → break_based=5 ✓(Word=5) 但用户旧版APP显示4（可能是传输中丢失标记或版本差异）
        //   2.docx: LRP=10 → break_based=11 ✗(Word=12) → 普遍少1页
        //
        // 根因：
        //   a) Word 的 lastRenderedPageBreak 标记的是"分页点"位置，N个标记=N+1页
        //      但 Word 有时不在最后一页写标记（最后一页自然结束不需要），导致 N+1 可能比实际少1
        //   b) v1.1.13 的 content_estimate capping 对有格式的文档（表格/图片）严重偏高
        //      （15749字→估算15~21页，实际仅5页），capping 反而把正确值覆盖成错误值
        //
        // v1.1.14 方案：
        //   A) 有 lastRenderedPageBreak 时：信任它为主信号，但加 +1 安全边距
        //      （因为 Word 经常少写1个末尾标记）
        //   B) 完全不用 content_estimate 做 max-capping（格式文档的估算完全不可靠）
        //   C) 无任何分页标记时：从 XML 读实际页面尺寸+默认字号，算出更准的每页容量

        // 信号 A: lastRenderedPageBreak（Word 自身渲染记录）
        val renderedBreaks = """<w:lastRenderedPageBreak/>""".toRegex().findAll(bodyXml).count()

        var pages: Int
        var pagesReason = ""
        if (renderedBreaks > 0) {
            // 标准公式：N 个 lastRenderedPageBreak 标记 = N+1 页
            // （Word 在每个分页点写标记，从第1页开始，N个标记意味着翻N次到第N+1页）
            // 不再加安全边距——实测表明边距在某些文档上导致多算1~2页
            pages = maxOf(1, 1 + renderedBreaks)
            pagesReason = "word_rendered_breaks_n${renderedBreaks}"
        } else {
            // Fallback: 显式分页符 + 节分隔符
            val explicitBreaks = pageCounter[0]

            val bodyMatchResult = """<w:body>(.*?)</w:body>""".toRegex(RegexOption.DOT_MATCHES_ALL).find(bodyXml)
            var separatorSectPr = 0
            if (bodyMatchResult != null) {
                val bodyContent = bodyMatchResult.groupValues[1]
                val parasInBody = """<w:p[\s>]""".toRegex().findAll(bodyContent).toList()
                val sectsInBody = """<w:sectPr[\s>]""".toRegex().findAll(bodyContent).toList()
                if (sectsInBody.size > 1) {
                    for (i in 0 until sectsInBody.size - 1) {
                        val spRelPos = sectsInBody[i].range.first
                        val hasParaAfter = parasInBody.any { it.range.first > spRelPos }
                        if (hasParaAfter) separatorSectPr++
                    }
                }
            }

            val totalBreaks = explicitBreaks + separatorSectPr
            if (totalBreaks > 0) {
                pages = maxOf(1, 1 + totalBreaks)
                pagesReason = "explicit_breaks_n${totalBreaks}"
            } else {
                // 无任何分页标记：基于实际页面尺寸的智能估算
                // 从 sectPr 读页面尺寸和边距，按中文文档默认排版（宋体/小四/1.5倍行距）计算
                pages = estimatePagesFromLayout(bodyXml, text)
                pagesReason = "layout_estimate"
            }

            // ★ v1.1.14: 不再做 content_estimate max-capping！
            // 格式化文档（含表格/图片）的内容估算会严重偏高（15749字→15~21页 vs 实际5页）
            // 有分页标记时，信任分页信号（已加安全边距），不做覆盖
        }
        // ── v1.2.3: 读取 docProps/app.xml 的权威统计（Word/WPS 保存时写入）──
        // Word 的「字数统计」对话框数值即来源于此：Pages=页数、Words=字数(不计空格)、
        // Characters=字符数(不计空格)。POI 生成的文件无这些字段（仅 <Application>），退回现算。
        val appXml = readEntry(zip, "docProps/app.xml") ?: ""
        val metaPages = extractAppInt(appXml, "Pages")
        val metaWords = extractAppInt(appXml, "Words")
        val metaChars = extractAppInt(appXml, "Characters")

        return OoxmlResult(
            text = text,
            pages = pages,
            kind = "docx",
            pagesReason = pagesReason,
            metaPages = metaPages,
            metaWords = metaWords,
            metaChars = metaChars
        )
    }

    /**
     * v1.1.14: 基于文档实际页面尺寸的智能页数估算。
     *
     * 从 document.xml 的 <w:sectPr> 读取页面尺寸和边距，
     * 按中文 Word 默认排版参数（宋体 小四=12pt / 1.5倍行距 / A4纸）计算每页可容纳的字符数。
     *
     * 比固定系数（750/1050）更准确，因为不同页面设置（A4/Letter/B5、宽/窄边距）
     * 的每页容量差异可达 ±30%。
     */
    private fun estimatePagesFromLayout(bodyXml: String, text: String): Int {
        // 默认值（A4 / 宋体小四 / 1.5倍行距 / 标准边距）
        var pageW_twip = 11906   // A4 width in twips (210mm)
        var pageH_twip = 16838   // A4 height in twips (297mm)
        var marginTop = 1440      // 1 inch top
        var marginBottom = 1440   // 1 inch bottom
        var marginLeft = 1800     // 1.25 inch left
        var marginRight = 1800    // 1.25 inch right

        // 从 sectPr 提取实际页面尺寸
        """<w:pgSz\s+w:w="(\d+)"\s+w:h="(\d+)"""".toRegex().find(bodyXml)?.let { m ->
            pageW_twip = m.groupValues[1].toInt()
            pageH_twip = m.groupValues[2].toInt()
        }
        """<w:pgMar\s+w:top="(\d+)"\s+w:right="(\d+)"\s+w:bottom="(\d+)"\s+w:left="(\d+)"""".toRegex()
            .find(bodyXml)?.let { m ->
                marginTop = m.groupValues[1].toInt()
                marginRight = m.groupValues[2].toInt()
                marginBottom = m.groupValues[3].toInt()
                marginLeft = m.groupValues[4].toInt()
            }

        // 可用区域（twips）
        val contentW = pageW_twip - marginLeft - marginRight
        val contentH = pageH_twip - marginTop - marginBottom

        // 中文文档默认排版参数：
        // - 字号：小四 = 12pt（half-points = 24），但很多文档用 五号=10.5pt(hp=21) 或 宋体=12pt(hp=24)
        // - 行距：1.5 倍（Word 默认对中文正文）
        // - 每字符平均宽度：中文字符 ≈ 字号宽度（方正/宋体约 0.85~1.0 倍字号）
        //
        // 计算：
        //   每行字数 ≈ contentW_twips / (fontSize_pt * 20)  [twips per point = 20]
        //   每页行数 ≈ contentH_twips / (fontSize_pt * lineSpacing * 20)
        //   每页字符 ≈ 每行字数 * 每页行数 * charWidthRatio

        // 使用保守估计：五号(10.5pt) + 1.5倍行距 + 全角中文(1字号宽)
        val fontSizePt = 10.5
        val lineSpacing = 1.5
        val charWidthRatio = 1.0f // 中文字符基本占满字号宽度

        val charsPerLine = (contentW / (fontSizePt * 20.0)).toFloat()
        val linesPerPage = (contentH / (fontSizePt * lineSpacing * 20.0)).toFloat()
        val charsPerPage = (charsPerLine * linesPerPage * charWidthRatio).toInt()

        // 安全下限/上限
        val safeCharsPerPage = charsPerPage.coerceIn(800, 2500)

        val cleanCharCount = text.replace(Regex("\\s"), "").length
        return if (cleanCharCount > 0 && safeCharsPerPage > 0) {
            maxOf(1, (cleanCharCount + safeCharsPerPage - 1) / safeCharsPerPage)
        } else {
            1
        }
    }

    /**
     * 从 OOXML XML 中抽取文本（核心方法）。
     *
     * v1.0.26 修复：
     *   1. 移除 v1.0.24 的字母/CJK 过滤——旧逻辑要求 w:t 内容必须含字母或汉字，
     *      导致纯数字内容（身份证号、学年学期、分数、学分等）被丢弃，单文件可丢 ~340 字。
     *      新逻辑：保留所有可打印字符（与桌面版 Python / Word 口径一致）。
     *   2. 保持逐 <w:r> run 边界提取架构（对正常文档准确）
     *   3. 对每个 <w:t> 提取结果做 strip-tags 清洗（v1.0.24 遗留）
     */
    private fun appendDocxXmlText(
        xml: String,
        sb: StringBuilder,
        onPageBreak: (() -> Unit)? = null
    ) {
        if (xml.isBlank()) return

        // Step 1: 按段落（<w:p>）切分，保持段落结构
        val paraRe = """(?s)<w:p[ >].*?</w:p>""".toRegex()
        paraRe.findAll(xml).forEach { paraMatch ->
            val paraXml = paraMatch.value

            // 检查本段是否含分页符
            if (paraXml.contains("""w:type="page"""")) onPageBreak?.invoke()

            // Step 2: 在段落内逐个处理 <w:r> run
            val runRe = """(?s)<w:r[ >].*?</w:r>""".toRegex()
            runRe.findAll(paraXml).forEach { runMatch ->
                val runXml = runMatch.value

                // 跳过隐藏文字：run 属性中含 w:vanish 或 w:hidden
                if (VANISH_RE.containsMatchIn(runXml)) return@forEach
                if (HIDDEN_RE.containsMatchIn(runXml)) return@forEach

                // Step 3: 在 run 内提取 <w:t> 文本，然后清洗嵌套标签
                val tRe = """(?s)<w:t[^>]*>(.*?)</w:t>""".toRegex()
                tRe.findAll(runXml).forEach { tMatch ->
                    val raw = decodeXml(tMatch.groupValues[1])
                    // 清洗可能嵌套在 <w:t> 内的 XML 标签和孤立实体引用
                    val clean = raw.replace("""<[^>]+>""", "")
                        .replace("""&[a-z]+;""".toRegex(), "")

                    // v1.0.26：只要求非空白且含可打印字符（不再强制要求字母/汉字）
                    // 与桌面版 wordcount.py 的 ord(c) >= 32 判定对齐
                    if (clean.isNotBlank() && clean.any { it.code >= 32 || it == '\t' || it == '\n' || it == '\r' }) {
                        sb.append(clean)
                    }
                }
            }

            // 段落结束追加换行
            sb.append('\n')
        }
    }

    // ───────────────────────── 正则常量 ──
    /** 隐藏文字检测：<w:r> 内含 w:vanish */
    private val VANISH_RE = """<w:vanish\b""".toRegex()

    /** 隐藏文字检测：<w:r> 内含 w:hidden */
    private val HIDDEN_RE = """<w:hidden\b""".toRegex()

    // ───────────────────────── xlsx ─────────────────────────
    /**
     * xlsx 文本提取——模拟"全选 → 复制 → 粘贴到 Word"的效果：
     *   按工作表顺序，每个工作表内按行优先（从上到下、从左到右），
     *   同一行单元格间用 \t 分隔，行间用 \n 分隔。
     *
     * v1.0.25 关键修复（与桌面版 openpyxl / Word 口径对齐，实测 words=1988/fe=1780/nc=208）：
     *   1. 单元格正则支持自闭合空单元格 <c .../>——旧正则 <c ...>(.*?)</c> 会把自闭合空格
     *      与紧随其后的单元格"吞"成一个，导致列错位 + 该格 t="s" 落到内层从而共享字符串解析失效。
     *   2. 共享字符串判定改为看 <c> 开标签属性是否含 t="s"（旧代码在内层 body 里找 t="s"，
     *      而 t="s" 只存在于开标签，导致判定永远为 false，所有共享字符串被输出成索引数字）。
     *   3. Excel 日期序列号（整数 20000~60000）转中文短日期「MM月DD日」，与复制到 Word 的显示一致。
     *   4. 通过 workbook.xml + workbook.xml.rels 排除隐藏工作表（state=hidden/veryHidden），只统计可见表。
     *   5. 不再往被统计文本插入 [工作表N] 标签（旧代码会因此每个表多算约 3 个中文 + 2 个非中文词）。
     */
    /**
     * xlsx 文本提取——模拟"全选 → 复制 → 粘贴到 Word"的效果，按工作表顺序统计。
     *
     * v1.3.3 关键变更（隐藏工作表处理）：
     *   - 遍历全部工作表（含 hidden / veryHidden），逐表抽取单元格 + 该表专属绘图层（文本框/艺术字）。
     *   - 可见表：文本计入文件默认字数（与 Word「包括文本框」口径一致）。
     *   - 隐藏表：文本单独返回（OoxmlResult.hiddenSheets），默认【不计入】文件字数与合计，
     *     由 UI 以「红隐 + 勾选框」形式呈现，用户勾选后才并入合计。
     *   - 绘图层按 worksheet 的 rels 归属到具体工作表（xl/worksheets/sheetN.xml.rels →
     *     xl/drawings/drawingN.xml + vmlDrawingN.vml），避免隐藏表的文本框混入默认合计。
     */
    private fun extractXlsx(zip: ZipFile): OoxmlResult {
        val shared = readSharedStrings(zip)

        // 1) 解析 workbook.xml：按顺序取 (name, state, r:id)
        val wbXml = readEntry(zip, "xl/workbook.xml") ?: ""
        val nameAttrRe = "name=\"([^\"]*)\"".toRegex()
        val stateAttrRe = "state=\"([^\"]*)\"".toRegex()
        val ridAttrRe = "r:id=\"([^\"]*)\"".toRegex()
        val sheetRefs = mutableListOf<Triple<String, String, String>>()
        """<sheet\b[^>]*/>""".toRegex().findAll(wbXml).forEach { m ->
            val tag = m.value
            val nm = nameAttrRe.find(tag)?.groupValues?.get(1) ?: ""
            val st = stateAttrRe.find(tag)?.groupValues?.get(1) ?: "visible"
            val ri = ridAttrRe.find(tag)?.groupValues?.get(1) ?: ""
            sheetRefs.add(Triple(nm, st, ri))
        }

        // 2) 解析 rels：r:id → worksheet 文件路径
        val relsXml = readEntry(zip, "xl/_rels/workbook.xml.rels") ?: ""
        val idAttrRe = "Id=\"([^\"]*)\"".toRegex()
        val tgtAttrRe = "Target=\"([^\"]*)\"".toRegex()
        val rid2tgt = HashMap<String, String>()
        """<Relationship\b[^>]*/>""".toRegex().findAll(relsXml).forEach { m ->
            val tag = m.value
            val id = idAttrRe.find(tag)?.groupValues?.get(1)
            val tg = tgtAttrRe.find(tag)?.groupValues?.get(1)
            if (id != null && tg != null) rid2tgt[id] = tg
        }

        // 3) 构建全部工作表（含隐藏）。SheetInfo: (name, worksheetPath, hidden)
        val allSheets = mutableListOf<SheetInfo>()
        for ((nm, state, rid) in sheetRefs) {
            val tgt = rid2tgt[rid] ?: continue
            val trimmed = tgt.trimStart('/')
            val path = if (trimmed.startsWith("xl/")) trimmed else "xl/$trimmed"
            allSheets.add(SheetInfo(nm, path, state == "hidden" || state == "veryHidden"))
        }
        // 兜底：workbook.xml/rels 解析不到时，退回旧逻辑（全部 sheetN.xml，按序号，均视为可见）
        if (allSheets.isEmpty()) {
            Collections.list(zip.entries())
                .filter { it.name.matches("""xl/worksheets/sheet\d+\.xml""".toRegex()) }
                .sortedBy { """\d+""".toRegex().find(it.name)?.value?.toInt() ?: 0 }
                .mapIndexed { i, e -> SheetInfo("工作表${i + 1}", e.name, false) }
                .let { allSheets.addAll(it) }
        }

        // 支持自闭合 / 完整 元素的正则
        val rowRe = """<row\b([^>]*?)(?:/>|>(.*?)</row>)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val rowNumRe = "r=\"(\\d+)\"".toRegex()
        val cellRe = """<c\b([^>]*?)(?:/>|>(.*?)</c>)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val cellRefRe = "r=\"([A-Z]+)(\\d+)\"".toRegex()
        val vRe = """<v>(.*?)</v>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val tRe = """<t[^>]*>(.*?)</t>""".toRegex(RegexOption.DOT_MATCHES_ALL)

        val visibleNames = mutableListOf<String>()
        val visibleSb = StringBuilder()
        val hiddenSheets = mutableListOf<Pair<String, String>>() // (sheetName, text)

        allSheets.forEachIndexed { idx, si ->
            val (siName, siPath, siHidden) = si
            val sheetName = if (siName.isNotBlank()) siName else "工作表${idx + 1}"
            val xml = readEntry(zip, siPath) ?: return@forEachIndexed

            // 按行提取单元格
            val cellsSb = StringBuilder()
            val rows = mutableListOf<Pair<Int, String>>()
            rowRe.findAll(xml).forEach { rm ->
                val body = rm.groupValues[2]
                if (body.isEmpty()) return@forEach
                val rowNum = rowNumRe.find(rm.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                rows.add(Pair(rowNum, body))
            }
            rows.sortBy { it.first }
            for ((_, rowBody) in rows) {
                val cells = mutableListOf<Pair<Int, String>>()
                cellRe.findAll(rowBody).forEach { cm ->
                    val attrs = cm.groupValues[1]
                    val inner = cm.groupValues[2]
                    val ref = cellRefRe.find(attrs) ?: return@forEach
                    val colNum = colNameToIndex(ref.groupValues[1])
                    cells.add(Pair(colNum, cellText(attrs, inner, shared, vRe, tRe)))
                }
                cells.sortBy { it.first }
                val line = StringBuilder()
                var first = true
                for ((_, txt) in cells) {
                    if (txt.isNotBlank()) {
                        if (!first) line.append('\t')
                        line.append(txt)
                        first = false
                    }
                }
                if (line.isNotEmpty()) cellsSb.append(line).append('\n')
            }

            // 本工作表专属绘图层（文本框/艺术字），按 sheet 归属
            val drawText = extractSheetDrawing(zip, siPath)

            val sheetText = cellsSb.toString() + if (drawText.isNotBlank()) drawText else ""
            if (siHidden) {
                hiddenSheets.add(Pair(sheetName, sheetText))
            } else {
                visibleNames.add(sheetName)
                visibleSb.append(sheetText).append('\n')
            }
        }

        // 兜底：极少数 workbook 解析异常的文件，仍补抽全部绘图层（保持 v1.3.2 行为）
        if (allSheets.isEmpty()) {
            val dt = extractAllDrawings(zip)
            if (dt.isNotBlank()) visibleSb.append(dt)
        }

        val text = visibleSb.toString()
        val pages = max(1, visibleNames.size)
        return OoxmlResult(text, pages, "xlsx", visibleNames, hiddenSheets, "")
    }

    /** 工作表信息：名称、worksheet 的 zip 内路径、是否隐藏 */
    private data class SheetInfo(val name: String, val path: String, val hidden: Boolean)

    /**
     * 提取单个工作表的绘图层（文本框/艺术字）文本，按 sheet 归属。
     * 读 worksheet 的 rels 找到对应的 drawingN.xml（DrawingML <a:t>），
     * 以及其 vmlDrawing（老版 Excel 文本框 VML）。解析异常时返回空串。
     */
    private fun extractSheetDrawing(zip: ZipFile, sheetPath: String): String {
        val drawingPath = drawingPathForSheet(zip, sheetPath) ?: return ""
        val sb = StringBuilder()
        return try {
            val xml = readEntry(zip, drawingPath) ?: return ""
            """<a:t[^>]*>(.*?)</a:t>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach {
                sb.append(decodeXml(it.groupValues[1])).append('\n')
            }
            // VML 兜底（老版 Excel 文本框）
            val vmlPath = vmlPathForDrawing(zip, drawingPath)
            if (vmlPath != null) {
                val vxml = readEntry(zip, vmlPath) ?: return sb.toString()
                """<w:txbxContent[^>]*>(.*?)</w:txbxContent>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(vxml).forEach { block ->
                    """<w:t[^>]*>(.*?)</w:t>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(block.groupValues[1]).forEach {
                        sb.append(decodeXml(it.groupValues[1])).append('\n')
                    }
                }
                """<v:textbox[^>]*>(.*?)</v:textbox>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(vxml).forEach { block ->
                    """<text[^>]*>(.*?)</text>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(block.groupValues[1]).forEach {
                        sb.append(decodeXml(it.groupValues[1])).append('\n')
                    }
                }
            }
            sb.toString()
        } catch (_: Throwable) {
            sb.toString()
        }
    }

    /** 从 worksheet 的 rels 找到其 drawing 关系目标路径（zip 内绝对路径）。 */
    private fun drawingPathForSheet(zip: ZipFile, sheetPath: String): String? {
        val dir = sheetPath.substringBeforeLast('/')
        val name = sheetPath.substringAfterLast('/')
        val relsXml = readEntry(zip, "$dir/_rels/$name.rels") ?: return null
        val tgtAttrRe = "Target=\"([^\"]*)\"".toRegex()
        val typeAttrRe = "Type=\"([^\"]*)\"".toRegex()
        var drawingTgt: String? = null
        """<Relationship\b[^>]*/>""".toRegex().findAll(relsXml).forEach { m ->
            val tag = m.value
            val type = typeAttrRe.find(tag)?.groupValues?.get(1) ?: ""
            if (type.endsWith("/drawing") || type.endsWith("/drawingml")) {
                drawingTgt = tgtAttrRe.find(tag)?.groupValues?.get(1)
            }
        }
        return drawingTgt?.let { resolveRelPath(dir, it) }
    }

    /** 从 drawing 的 rels 找到其 vmlDrawing 目标路径。 */
    private fun vmlPathForDrawing(zip: ZipFile, drawingPath: String): String? {
        val dir = drawingPath.substringBeforeLast('/')
        val name = drawingPath.substringAfterLast('/')
        val relsXml = readEntry(zip, "$dir/_rels/$name.rels") ?: return null
        val tgtAttrRe = "Target=\"([^\"]*)\"".toRegex()
        val typeAttrRe = "Type=\"([^\"]*)\"".toRegex()
        var vmlTgt: String? = null
        """<Relationship\b[^>]*/>""".toRegex().findAll(relsXml).forEach { m ->
            val tag = m.value
            val type = typeAttrRe.find(tag)?.groupValues?.get(1) ?: ""
            if (type.endsWith("/vmlDrawing")) {
                vmlTgt = tgtAttrRe.find(tag)?.groupValues?.get(1)
            }
        }
        return vmlTgt?.let { resolveRelPath(dir, it) }
    }

    /** 把相对路径（可能含 ../）解析为 zip 内绝对路径。 */
    private fun resolveRelPath(baseDir: String, rel: String): String {
        val out = mutableListOf<String>()
        for (p in (baseDir.split('/') + rel.split('/'))) {
            when (p) {
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.lastIndex) else out.add(p)
                ".", "" -> {} // 跳过
                else -> out.add(p)
            }
        }
        return out.joinToString("/")
    }

    /**
     * v1.3.2 遗留（兜底用）：抽取全部绘图层文本（所有 drawingN.xml 的 <a:t> + vmlDrawing 文本框）。
     * 仅在 workbook 解析异常、无法按 sheet 归属时才调用，避免隐藏表文本框污染默认合计。
     */
    private fun extractAllDrawings(zip: ZipFile): String {
        val sb = StringBuilder()
        return try {
            val entries = Collections.list(zip.entries())
            for (e in entries) {
                if (e.name.matches("""xl/drawings/drawing\d+\.xml""".toRegex())) {
                    val xml = readEntry(zip, e.name) ?: continue
                    """<a:t[^>]*>(.*?)</a:t>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach {
                        sb.append(decodeXml(it.groupValues[1])).append('\n')
                    }
                }
            }
            for (e in entries) {
                if (e.name.matches("""xl/drawings/vmlDrawing\d+\.vml""".toRegex())) {
                    val xml = readEntry(zip, e.name) ?: continue
                    """<w:txbxContent[^>]*>(.*?)</w:txbxContent>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { block ->
                        """<w:t[^>]*>(.*?)</w:t>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(block.groupValues[1]).forEach {
                            sb.append(decodeXml(it.groupValues[1])).append('\n')
                        }
                    }
                    """<v:textbox[^>]*>(.*?)</v:textbox>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { block ->
                        """<text[^>]*>(.*?)</text>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(block.groupValues[1]).forEach {
                            sb.append(decodeXml(it.groupValues[1])).append('\n')
                        }
                    }
                }
            }
            sb.toString()
        } catch (_: Throwable) {
            sb.toString()
        }
    }

    /**
     * 单元格取值：处理共享字符串 / inlineStr / 公式字符串 / 数字 / Excel 日期序列号。
     * 注意：类型判定必须看 <c> 开标签属性 attrs（t="s"/"str"/"inlineStr"），不能在 inner 里找。
     */
    private fun cellText(
        attrs: String,
        inner: String,
        shared: List<String>,
        vRe: Regex,
        tRe: Regex
    ): String {
        if (inner.isEmpty()) return ""
        // inlineStr：<is>...<t>..</t></is>（v1.3.0修复：用 findAll 拼接所有段，与 readSharedStrings 一致）
        if (attrs.contains("t=\"inlineStr\"")) {
            val sb = StringBuilder()
            tRe.findAll(inner).forEach { sb.append(decodeXml(it.groupValues[1])) }
            return sb.toString()
        }
        val v = vRe.find(inner)?.groupValues?.get(1)?.trim() ?: ""
        // 共享字符串：t="s"，<v> 是索引
        if (attrs.contains("t=\"s\"") && v.isNotEmpty()) {
            return v.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
        }
        // 公式字符串结果：t="str"
        if (attrs.contains("t=\"str\"")) {
            return decodeXml(v)
        }
        // 数字：识别 Excel 日期序列号（整数 20000~60000）→ 中文短日期，与 Word 显示一致
        if (v.isNotEmpty()) {
            val d = v.toDoubleOrNull()
            if (d != null && d == Math.floor(d) && d > 20000 && d < 60000) {
                return try {
                    val date = java.time.LocalDate.of(1899, 12, 30).plusDays(d.toLong())
                    String.format("%02d月%02d日", date.monthValue, date.dayOfMonth)
                } catch (_: Throwable) {
                    v
                }
            }
            return v
        }
        return ""
    }

    /** Excel 列名 → 列索引（A=0, B=1, ..., Z=25, AA=26, ...） */
    private fun colNameToIndex(name: String): Int {
        var idx = 0
        for (c in name) {
            idx = idx * 26 + (c.uppercaseChar().code - 'A'.code + 1)
        }
        return idx - 1
    }

    // ───────────────────────── pptx ─────────────────────────
    private fun extractPptx(zip: ZipFile): OoxmlResult {
        val slideEntries = Collections.list(zip.entries())
            .filter { it.name.matches("""ppt/slides/slide\d+\.xml""".toRegex()) }
            .sortedBy { """\d+""".toRegex().find(it.name)?.value?.toInt() ?: 0 }
        val sb = StringBuilder()
        slideEntries.forEachIndexed { idx, entry ->
            val xml = readEntry(zip, entry.name) ?: return@forEachIndexed
            sb.append("\n[幻灯片${idx + 1}]\n")
            val tRe = """<a:t[^>]*>(.*?)</a:t>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            tRe.findAll(xml).forEach { sb.append(decodeXml(it.groupValues[1])).append(' ') }
            sb.append('\n')
        }
        val text = sb.toString()
        val pages = max(1, slideEntries.size)
        return OoxmlResult(text, pages, "pptx")
    }

    // ───────────────────────── 工具 ─────────────────────────
    private fun readSharedStrings(zip: ZipFile): List<String> {
        val xml = readEntry(zip, "xl/sharedStrings.xml") ?: return emptyList()
        val out = mutableListOf<String>()
        // 支持自闭合 <si/>（空字符串），保持索引对齐
        val siRe = """<si\b[^>]*?(?:/>|>(.*?)</si>)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val tRe = """<t[^>]*>(.*?)</t>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        siRe.findAll(xml).forEach { siMatch ->
            val inner = siMatch.groupValues[1]
            val sb = StringBuilder()
            tRe.findAll(inner).forEach { sb.append(decodeXml(it.groupValues[1])) }
            out.add(sb.toString())
        }
        return out
    }

    private fun readEntry(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        zip.getInputStream(entry).use { `is` ->
            val bytes = `is`.readBytes()
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    // v1.2.3: 从 docProps/app.xml 提取整数型统计字段（Pages/Words/Characters 等）
    private fun extractAppInt(xml: String, tag: String): Int {
        if (xml.isBlank()) return 0
        val m = """<$tag>(\d+)</$tag>""".toRegex().find(xml)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun decodeXml(s: String): String {
        return s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("""&#x([0-9a-fA-F]+);""".toRegex()) { m -> m.groupValues[1].toInt(16).toChar().toString() }
            .replace("""&#(\d+);""".toRegex()) { m -> m.groupValues[1].toInt().toChar().toString() }
    }
}
