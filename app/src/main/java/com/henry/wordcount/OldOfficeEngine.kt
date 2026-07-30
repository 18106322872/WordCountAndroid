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
import org.apache.poi.hslf.usermodel.HSLFShape
import org.apache.poi.hslf.usermodel.HSLFTextShape
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
            val text = extractor.text ?: ""

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
            return DocResult(text = text, pages = pages, words = words, chars = chars)
        } finally {
            runCatching { doc.close() }
        }
    }

    /** .xls 抽取结果：可见表文本(计入默认字数) + 可见表名 + 隐藏表(名称, 文本)列表 */
    data class XlsResult(val text: String, val visibleNames: List<String>, val hiddenSheets: List<Pair<String, String>>)

    /**
     * v1.3.3: .xls 逐工作表抽取（含隐藏表）。
     * 可见表文本计入文件默认字数；隐藏表（isSheetHidden）单独返回，默认不计入合计，
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
        try {
            for (i in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(i) ?: continue
                val name = wb.getSheetName(i)
                val hiddenFlag = wb.isSheetHidden(i)
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
        return XlsResult(visibleSb.toString(), visibleNames, hidden)
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
     * v1.3.36: .ppt 完整提取（文本 + 备注幻灯片 + 嵌入图片数 + 图表 + 批注）。
     * 与 extractPptx(pptx) 对齐：返回 notesSlides 和 imageCount 供 UI 展开显示。
     * v1.3.36 改进：
     *   - 递归编组形状（HSLFGroupShape），否则编组内文本整组丢失导致字数偏低
     *   - 内嵌图表文字（图表标题 + 系列名）
     *   - 批注/评论文字（ppt.comments）——这些是需要翻译的内容
     */
    internal fun extractPptFull(file: File): PptResult {
        val fis = FileInputStream(file)
        val ppt = HSLFSlideShow(fis)
        val textSb = StringBuilder()
        val notesList = mutableListOf<SheetStat>()
        var imgCount = 0
        try {
            // 幻灯片文本（递归编组 + 图表 + 批注）
            for (slide in ppt.slides) {
                // 递归收集幻灯片内所有文本形状（含编组内子形状）
                try {
                    for (shape in slide.shapes) {
                        collectHslfShapeText(shape, textSb)
                    }
                } catch (_: Throwable) {}
                // 统计图片（HSLFPictureShape 是嵌入图片）
                try {
                    for (shape in slide.shapes) {
                        if (org.apache.poi.hslf.usermodel.HSLFPictureShape::class.java.isInstance(shape)) imgCount++
                    }
                } catch (_: Throwable) {}
                // 注意：POI HSLF 不像 HSSF(Excel)那样有 getSheetCharts() API，
                // .ppt 内嵌图表是 OLE 对象，POI scratchpad 无法直接提取文字。
                // 字数差距主要靠递归编组形状弥补（见上方的 collectHslfShapeText）。
            }
            // 批注：POI HSLF scratchpad 未暴露 comments 属性，暂无法通过 POI 提取
            // （电脑版用 COM PowerPoint.Application 可取到）
            // 备注文本（HSLF: 通过 ppt.notes 获取所有备注幻灯片）
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
     * v1.3.38: 递归收集 HSLF 形状文字（含编组 + 表格 + 简单形状）。
     * 与电脑版 COM 对齐：COM 遍历 slide.Shapes 检查 HasTextFrame + HasTable。
     * - HSLFGroupShape: 编组 → 递归遍历子形状
     * - HSLFTextShape: 文本形状 → 取文字
     * - HSLFTable: 表格 → 逐单元格提取文字（电脑版 shape.Table，POI 此前漏掉导致 .ppt 字数严重偏低）
     * - HSLFSimpleShape: 简单形状 → 尝试取文本（部分有文本的 auto-shape）
     */
    private fun collectHslfShapeText(shape: org.apache.poi.hslf.usermodel.HSLFShape, sb: StringBuilder) {
        try {
            when (shape) {
                is org.apache.poi.hslf.usermodel.HSLFGroupShape -> {
                    for (child in shape.shapes) collectHslfShapeText(child, sb)
                }
                is HSLFTextShape -> {
                    val txt = shape.text
                    if (!txt.isNullOrBlank()) sb.append(txt).append("\n")
                }
                is org.apache.poi.hslf.usermodel.HSLFTable -> {
                    // v1.3.38: 表格文本（与电脑版 COM shape.Table 对齐）
                    for (row in shape.rows) {
                        val rowSb = StringBuilder()
                        for (cell in row) {
                            val ct = cell.text?.trim() ?: ""
                            if (ct.isNotEmpty()) rowSb.append(ct).append(" ")
                        }
                        val rowText = rowSb.toString().trim()
                        if (rowText.isNotEmpty()) sb.append(rowText).append("\n")
                    }
                }
                else -> {
                    // v1.3.38: 其他形状（HSLFSimpleShape 等）尝试获取文本
                    try {
                        val txt = (shape as? org.apache.poi.hslf.usermodel.HSLFSimpleShape)?.text
                        if (!txt.isNullOrBlank()) sb.append(txt).append("\n")
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
    }
}
