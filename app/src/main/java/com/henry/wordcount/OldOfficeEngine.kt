package com.henry.wordcount

import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.hpsf.SummaryInformation
import org.apache.poi.hssf.usermodel.HSSFTextbox
import org.apache.poi.hssf.usermodel.HSSFWorkbook
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

    fun extractText(file: File): String {
        val ext = file.extension.lowercase()
        FileInputStream(file).use { fis ->
            return when (ext) {
                "doc" -> extractDoc(fis)
                "xls" -> extractXls(fis)
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

    private fun extractXls(fis: FileInputStream): String {
        val wb = HSSFWorkbook(fis)
        val formatter = DataFormatter()
        val sb = StringBuilder()
        try {
            for (i in 0 until wb.numberOfSheets) {
                // v1.3.0: 跳过隐藏工作表（与 .xlsx 的 OoXmlEngine 行为一致：隐藏表不统计）
                if (wb.isSheetHidden(i)) continue
                val sheet = wb.getSheetAt(i) ?: continue
                // 单元格文本（v1.3.2: 不再插入 [工作表:名] 标签，与 .xlsx 一致，避免多算表名）
                for (row in sheet) {
                    val cells = row.mapNotNull { cell ->
                        formatter.formatCellValue(cell).takeIf { it.isNotBlank() }
                    }
                    if (cells.isNotEmpty()) sb.append(cells.joinToString(" ")).append("\n")
                }
                // v1.3.2: 文本框文本（HSSFTextbox：Excel 文本框/艺术字里的文字都在这里）
                // 包在 try 里：无绘图或个别形状异常时不影响单元格统计
                try {
                    val patriarch = sheet.drawingPatriarch
                    if (patriarch != null) {
                        for (shape in patriarch.children) {
                            if (shape is HSSFTextbox) {
                                val txt = shape.getString()?.string
                                if (!txt.isNullOrBlank()) sb.append(txt).append("\n")
                            }
                        }
                    }
                } catch (_: Throwable) { }
            }
        } finally {
            runCatching { wb.close() }
        }
        return sb.toString()
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
}
