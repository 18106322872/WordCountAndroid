package com.henry.wordcount

import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.hpsf.SummaryInformation
import org.apache.poi.hssf.usermodel.HSSFSimpleShape
import org.apache.poi.hssf.usermodel.HSSFTextbox
import org.apache.poi.hssf.usermodel.HSSFShapeGroup
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hssf.usermodel.HSSFChart
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFSheet
import org.apache.poi.hslf.usermodel.HSLFSlide
import org.apache.poi.hslf.usermodel.HSLFSlideMaster
import org.apache.poi.hslf.usermodel.HSLFShape
import org.apache.poi.hslf.usermodel.HSLFTextShape
import org.apache.poi.hslf.usermodel.HSLFTextParagraph
import org.apache.poi.ss.usermodel.DataFormatter
import java.io.File
import java.io.FileInputStream

/**
 * 老版 Office（.doc/.xls/.ppt）文本抽取层。
 *
 * 用 Apache POI（Java，安卓原生运行，不依赖 Windows / GMS）：
 *  - .doc  -> HWPF + WordExtractor
 *  - .xls  -> HSSFWorkbook（逐单元格取显示文本）
 *  - .ppt  -> HSLFSlideShow（逐页取文本形状）
 *
 * 抽出的纯文本交给 PythonEngine.countText 复用现有「Word 口径」字数统计，
 * 与原文档/图片路径的 UI 完全一致。
 *
 * 注意：安卓运行时缺 java.awt，个别机型上 HSLF(.ppt) 可能在类加载时抛出
 * NoClassDefFoundError。这里只负责抽取，异常由调用方（MainActivity）捕获并
 * 优雅降级，不会导致 App 崩溃。
 */
object OldOfficeEngine {

    data class DocResult(
        val text: String,
        val pages: Int = 0,  // 0 表示未知，由调用方估算
        // v1.2.3: SummaryInformation 中的权威统计（Word/WPS 保存时写入）
        // 0 表示无此元数据，由调用方退回现算
        val words: Int = 0,  // PID 15 WordCount = 字数(不计空格)
        val chars: Int = 0   // PID 16 CharCount = 字符数(不计空格)
    )

    /** v1.3.34: .ppt 完整提取结果（文本 + 备注列表 + 嵌入图片数） */
    data class PptResult(
        val text: String,
        val pages: Int = 0,
        val notesSlides: List<SheetStat> = emptyList(),
        val imageCount: Int = 0
    )

    fun extractText(file: File): String {
        val ext = file.extension.lowercase()
        FileInputStream(file).use { fis ->
            return when (ext) {
                "doc" -> extractDoc(fis)
                "xls" -> extractXlsDetailed(file).text
                "ppt" -> extractPpt(fis)
                else -> throw IllegalArgumentException("不支持的格式: .$ext")
            }
        }
    }

    private fun extractDoc(fis: FileInputStream): String {
        val doc = HWPFDocument(fis)
        val extractor = WordExtractor(doc)
        try {
            // WordExtractor.text() 是 HWPF 最可靠的文本提取方式
            return extractor.text ?: ""
        } finally {
            runCatching { extractor.close() }
            runCatching { doc.close() }
        }
    }

    /**
     * 完整提取 DOC 文档：文本 + 元数据（页数等）。
     * v1.1.10 新增：解决 HWPF 默认提取文本不完整 + 无页数信息的问题。
     */
    fun extractDocFull(file: File): DocResult {
        val fis = FileInputStream(file)
        val doc = HWPFDocument(fis)
        try {
            val extractor = WordExtractor(doc)
            val text = StringBuilder(extractor.text ?: "")

            // v1.3.95：补齐 .doc 此前漏统计的批注（comments）文本。
            // 页眉/页脚/脚注已被 WordExtractor.text() 默认包含；文本框文字同样被抽到。
            // 批注通过 HWPFDocument.getCommentsRange() 取到批注区的 Range，再取文本即可
            // （POI 5.2.5 无 getComments()/Comments 类，只有 getCommentsRange()->Range）。
            // 整段 try-catch 包裹，任何异常都不影响主文本统计。
            try {
                val commentsText = doc.getCommentsRange()?.text()
                if (!commentsText.isNullOrBlank()) text.append('\n').append(commentsText.trim())
            } catch (_: Throwable) { }

            val finalText = text.toString()

            // 尝试从文档属性获取统计信息（Word/WPS 保存时写入，与「字数统计」完全一致）
            var pages = 0
            var words = 0
            var chars = 0
            try {
                val si = doc.summaryInformation
                if (si != null) {
                    // SummaryInformation 标准属性：PAGE_COUNT(14)/WORD_COUNT(15)/CHAR_COUNT(16)
                    val pc = si.pageCount
                    if (pc > 0) pages = pc
                    val wc = si.wordCount
                    if (wc > 0) words = wc
                    val cc = si.charCount
                    if (cc > 0) chars = cc
                }
            } catch (_: Throwable) {}

            runCatching { extractor.close() }
            return DocResult(text = finalText, pages = pages, words = words, chars = chars)
        } finally {
            runCatching { doc.close() }
        }
    }

    /** .xls 抽取结果：可见表文本(计入默认字数) + 可见表名 + 隐藏表(名称, 文本)列表 + 嵌入图片数 */
    data class XlsResult(val text: String, val visibleNames: List<String>, val hiddenSheets: List<Pair<String, String>>, val imageCount: Int = 0)

    /**
     * v1.3.3: .xls 逐工作表抽取（含隐藏表）。
     * v1.9.115: 隐藏表判断补 veryHidden——hiddenFlag = isSheetHidden || isSheetVeryHidden，
     * 与桌面 xlrd visibility==0 判可见口径对齐（state=1/2 均归隐藏表明细，默认不计入合计）。
     * 由 UI 以「红隐 + 勾选框」呈现，用户勾选后才并入合计。
     * 文本框 + 自选图形文字（HSSFSimpleShape）按 sheet 的 drawingPatriarch 归属，避免隐藏表文字污染默认合计。
     */
    internal fun extractXlsDetailed(file: File): XlsResult {
        val fis = FileInputStream(file)
        val wb = HSSFWorkbook(fis)
        val formatter = DataFormatter()
        val visibleSb = StringBuilder()
        val visibleNames = mutableListOf<String>()
        val hidden = mutableListOf<Pair<String, String>>()
        // v1.3.44: .xls 图片计数优先用工作簿级 API（getAllPictures 覆盖全部嵌入图片，
        // 比 drawingPatriarch 遍历更全面——后者只返回锚定到某 sheet 绘图层的形状，
        // 而 OLE2 容器中可能存在未锚定到任何绘图层的图片数据）
        var totalImages = 0
        try { totalImages = wb.allPictures.size } catch (_: Throwable) {}
        try {
            for (i in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(i) ?: continue
                val name = wb.getSheetName(i)
                val hiddenFlag = wb.isSheetHidden(i) || wb.isSheetVeryHidden(i)
                val sb = StringBuilder()
                // 单元格文本（不再插入 [工作表:名] 标签）
                for (row in sheet) {
                    val cells = row.mapNotNull { cell ->
                        formatter.formatCellValue(cell).takeIf { it.isNotBlank() }
                    }
                    if (cells.isNotEmpty()) sb.append(cells.joinToString(" ")).append("\n")
                }
                // v1.3.8: 递归收集绘图层文字（文本框/自选图形/编组内子形状）。
                // v1.3.5 用 HSSFSimpleShape 统一取文本框+自选图形，但 patriarch.children
                // 只返回顶层形状——当图形被编组（HSSFShapeGroup）时，子形状在 group.getChildren()
                // 里，旧代码整组丢弃。现改为递归遍历，使 .xls 与 .xlsx 的 DrawingML 扁平 <a:t> 对齐。
                // 图片(HSSFPicture)的 getString() 返回 null，自然被过滤。
                try {
                    val patriarch = sheet.drawingPatriarch
                    if (patriarch != null) {
                        for (shape in patriarch.children) {
                            collectShapeText(shape, sb)
                        }
                    }
                } catch (_: Throwable) { }
                // v1.3.11: 计入图表文字（图表标题 + 系列名/图例名）。这些是翻译内容。
                // 注：POI HSSFChart 无轴标题公开 API（桌面版靠 Office COM 取轴标题），
                //     Android 端先计入图表标题与系列名；xls 轴标题暂无法经 POI 取得。
                try {
                    val charts = HSSFChart.getSheetCharts(sheet)
                    for (ch in charts) {
                        val ct = ch.chartTitle
                        if (!ct.isNullOrBlank()) sb.append(ct).append("\n")
                        for (s in ch.series) {
                            val st = s.seriesTitle
                            if (!st.isNullOrBlank()) sb.append(st).append("\n")
                        }
                    }
                } catch (_: Throwable) { }
                if (hiddenFlag) hidden.add(Pair(name, sb.toString()))
                else {
                    visibleNames.add(name)
                    visibleSb.append(sb.toString())
                }
            }
        } finally {
            runCatching { wb.close() }
            runCatching { fis.close() }
        }
        return XlsResult(visibleSb.toString(), visibleNames, hidden, totalImages)
    }

    /**
     * v1.3.8: 递归收集 HSSF 形状文字（含编组内子形状）。
     * - HSSFSimpleShape: 文本框/自选图形/艺术字 → 取文字
     * - HSSFShapeGroup: 编组 → 递归遍历子形状
     * - 其他（图片等）→ 忽略
     */
    private fun collectShapeText(shape: org.apache.poi.hssf.usermodel.HSSFShape, sb: StringBuilder) {
        when (shape) {
            is HSSFShapeGroup -> {
                for (child in shape.children) collectShapeText(child, sb)
            }
            is HSSFSimpleShape -> {
                val txt = shape.string?.string
                if (!txt.isNullOrBlank()) sb.append(txt).append("\n")
            }
        }
    }

    /**
     * v1.3.42: 递归统计 HSSF 形状中的图片（含编组内嵌套图片）。
     * v1.3.40 只检查 patriarch 顶层 children 的 HSSFPicture，
     * 但 Excel 中图片常被放入编组（HSSFShapeGroup）导致漏检。
     */
    private fun countImagesRecursive(shape: org.apache.poi.hssf.usermodel.HSSFShape, counter: IntArray) {
        when (shape) {
            is org.apache.poi.hssf.usermodel.HSSFPicture -> counter[0]++
            is HSSFShapeGroup -> for (child in shape.children) countImagesRecursive(child, counter)
        }
    }

    private fun extractPpt(fis: FileInputStream): String {
        // HSLF 读取二进制 PowerPoint；逐页提取文本形状文字。
        // 安卓缺 java.awt 时此处可能抛出 NoClassDefFoundError，由调用方捕获降级。
        val ppt = HSLFSlideShow(fis)
        val sb = StringBuilder()
        try {
            for (slide in ppt.slides) {
                for (shape in slide.shapes) {
                    if (shape is HSLFTextShape) {
                        val t = shape.text
                        if (!t.isNullOrBlank()) sb.append(t).append("\n")
                    }
                }
            }
        } finally {
            runCatching { ppt.close() }
        }
        return sb.toString()
    }

    /**
     * v1.3.39: .ppt 完整提取（文本 + 备注幻灯片 + 嵌入图片数）。
     * 与 extractPptx(pptx) 对齐：返回 notesSlides 和 imageCount 供 UI 展开显示。
     *
     * 文本提取策略（v1.3.53 重写）：
     *   纯形状遍历：collectHslfShapeText() 使用完整 run 链路（extractTextShapeFullText）
     *   这是最接近电脑版 COM 的方式——COM 也是遍历 slide.Shapes 检查 HasTextFrame/HasTable。
     *   v1.3.52 双来源（getTextParagraphs + shape traversal）导致约 2x 重复计数。
     *   范围：Slides + SlideMasters（某些 PPT 可见文本在 Master 层）
     *   不做去重
     */
    internal fun extractPptFull(file: File): PptResult {
        val fis = FileInputStream(file)
        val ppt = HSLFSlideShow(fis)
        val notesList = mutableListOf<SheetStat>()
        var imgCount = 0
        val textSb = StringBuilder()
        try {
            // ── 主文本：Slides（形状遍历，对齐 COM slide.Shapes）──
            for (slide in ppt.slides) {
                try {
                    for (shape in slide.shapes)
                        collectHslfShapeText(shape, textSb)
                } catch (_: Throwable) {}
            }

            // ── 补充：SlideMasters ──
            // 某些 PPT 可见内容文本存储在 Master 层。
            // v1.3.43 含 Master 时 D7B1=256，砍掉 Master 后暴跌到 182。
            for (master in ppt.slideMasters) {
                try {
                    for (shape in master.shapes)
                        collectHslfShapeText(shape, textSb)
                } catch (_: Throwable) {}
            }

            // ── 图片计数 ──
            try {
                for (slide in ppt.slides)
                    for (shape in slide.shapes)
                        if (org.apache.poi.hslf.usermodel.HSLFPictureShape::class.java.isInstance(shape)) imgCount++
            } catch (_: Throwable) {}

            // ── 备注文本（HSLF: 通过 ppt.notes 获取所有备注幻灯片）──
            try {
                for (notes in ppt.notes) {
                    val notesSb = StringBuilder()
                    for (nShape in notes.shapes) {
                        if (nShape is HSLFTextShape) {
                            val nt = nShape.text
                            if (!nt.isNullOrBlank()) notesSb.append(nt).append("\n")
                        }
                    }
                    val notesText = notesSb.toString().trim()
                    if (notesText.isNotEmpty()) {
                        val nStats = countTextKotlin(notesText)
                        notesList.add(SheetStat(
                            name = "备注 ${notesList.size + 1}",
                            words = nStats.first, fe = nStats.second, nc = nStats.third, chars = nStats.fourth
                        ))
                    }
                }
            } catch (_: Throwable) {}
        } finally {
            runCatching { ppt.close() }
        }
        return PptResult(
            text = textSb.toString(),
            pages = maxOf(1, ppt.slides.size),
            notesSlides = notesList,
            imageCount = imgCount
        )
    }

    /**
     * v1.3.50: 提取 HSLFTable 表格文本（逐单元格）。
     * getTextParagraphs() 可能不完全覆盖表格结构中的文本，需显式提取补充。
     */
    private fun extractHslfTableText(table: org.apache.poi.hslf.usermodel.HSLFTable, sb: StringBuilder) {
        try {
            for (r in 0 until table.numberOfRows) {
                val rowSb = StringBuilder()
                for (c in 0 until table.numberOfColumns) {
                    val cell = table.getCell(r, c)
                    if (cell != null) {
                        val ct = (cell as? HSLFTextShape)?.text?.trim() ?: ""
                        if (ct.isNotEmpty()) rowSb.append(ct).append(" ")
                    }
                }
                val rowText: String = rowSb.toString().trim()
                if (rowText.isNotEmpty()) sb.append(rowText).append("\n")
            }
        } catch (_: Throwable) {}
    }

    /**
     * v1.3.38: 递归收集 HSLF 形状文字（含编组 + 表格 + 简单形状）。
     * 与电脑版 COM 对齐：COM 遍历 slide.Shapes 检查 HasTextFrame + HasTable。
     * - HSLFGroupShape: 编组 → 递归遍历子形状
     * - HSLFTextShape: 文本形状 → 用 POI 完整链路 getTextParagraphs→getTextRuns→getRawText（v1.3.50: shape.text 可能丢文本）
     * - HSLFTable: 表格 → 逐单元格提取文字
     */
    private fun collectHslfShapeText(shape: org.apache.poi.hslf.usermodel.HSLFShape, sb: StringBuilder) {
        try {
            when (shape) {
                is org.apache.poi.hslf.usermodel.HSLFGroupShape -> {
                    for (child in shape.shapes) collectHslfShapeText(child, sb)
                }
                is HSLFTextShape -> {
                    // v1.3.50: 用 POI 完整文本链路替代 shape.text（可能丢文本）
                    val t = extractTextShapeFullText(shape)
                    if (t.isNotEmpty()) sb.append(t).append("\n")
                }
                is org.apache.poi.hslf.usermodel.HSLFTable -> {
                    try {
                        val numRows = shape.numberOfRows
                        val numCols = shape.numberOfColumns
                        for (r in 0 until numRows) {
                            val rowSb = StringBuilder()
                            for (c in 0 until numCols) {
                                val cell = shape.getCell(r, c)
                                if (cell != null) {
                                    val ct = (cell as? HSLFTextShape)?.text?.trim() ?: ""
                                    if (ct.isNotEmpty()) rowSb.append(ct).append(" ")
                                }
                            }
                            val rowText: String = rowSb.toString().trim()
                            if (rowText.isNotEmpty()) sb.append(rowText).append("\n")
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * v1.3.40: 用 POI 标准文本模型 API 获取幻灯片/母版的所有文本块。
     * getTextParagraphs() 返回该页所有文本区域（标题栏、文本框、自选图形、表格单元格、占位符等）。
     * 单一来源提取，不再与 collectHslfShapeText 并用（v1.3.40 fix: 双来源粒度不同导致重复计数）。
     */
    private fun extractSheetTextParagraphs(sheet: HSLFSheet, sb: StringBuilder) {
        try {
            val paras = sheet.getTextParagraphs()
            extractTextParagraphTree(paras, sb)
        } catch (_: Throwable) {}
    }

    /** v1.3.40: 递归处理 getTextParagraphs 返回的文本段落树。
     *  POI 5.2.5: HSLFTextParagraph.getText() 不存在，文本在逐 run 的 HSLFTextRun.getRawText() 上。 */
    private fun extractTextParagraphTree(obj: Any?, sb: StringBuilder) {
        when (obj) {
            is HSLFTextParagraph -> {
                for (run in obj.getTextRuns()) {
                    val t = run.getRawText()
                    if (!t.isNullOrBlank()) sb.append(t)
                }
            }
            is List<*> -> for (item in obj) extractTextParagraphTree(item, sb)
        }
    }

    /**
     * v1.3.50: 用 POI 完整文本链路提取 HSLFTextShape 的全部文本。
     * 链路：getTextParagraphs() → HSLFTextParagraph → getTextRuns() → HSLFTextRun → getRawText()
     * 这比 shape.text 更完整（后者内部可能跳过某些 run 或段落）。
     * 降级：如果链路失败，fallback 到 shape.text。
     */
    private fun extractTextShapeFullText(shape: HSLFTextShape): String {
        return try {
            val sb = StringBuilder()
            val paras = shape.textParagraphs
            for (p in paras) {
                for (run in p.textRuns) {
                    val t = run.rawText
                    if (!t.isNullOrEmpty()) sb.append(t)
                }
            }
            sb.toString()
        } catch (_: Throwable) {
            shape.text ?: ""
        }
    }

    /** 归一化后去重追加（历史保留，当前主路径不再使用 seen 去重） */
    private fun appendUnique(text: String?, sb: StringBuilder, seen: MutableSet<String>) {
        if (!text.isNullOrBlank()) {
            val norm = text.trim()
            if (norm.isNotEmpty() && seen.add(norm)) sb.append(norm).append("\n")
        }
    }
}
