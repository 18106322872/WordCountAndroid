package com.henry.wordcount

import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
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
            return extractor.text ?: ""
        } finally {
            runCatching { extractor.close() }
            runCatching { doc.close() }
        }
    }

    private fun extractXls(fis: FileInputStream): String {
        val wb = HSSFWorkbook(fis)
        val formatter = DataFormatter()
        val sb = StringBuilder()
        try {
            for (i in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(i) ?: continue
                sb.append("\n[工作表: ${sheet.sheetName}]\n")
                for (row in sheet) {
                    val cells = row.mapNotNull { cell ->
                        formatter.formatCellValue(cell).takeIf { it.isNotBlank() }
                    }
                    if (cells.isNotEmpty()) sb.append(cells.joinToString(" ")).append("\n")
                }
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
