package com.henry.wordcount

import android.util.Log
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFTextShape
import java.io.File
import java.io.FileInputStream

/**
 * 新版 Office 文本提取层：Apache POI OOXML（纯 Java，安卓原生运行）。
 *
 * v1.0.14 新增：彻底绕开 Chaqopy/Python，解决 AssetFinder/scripts 问题。
 * 支持格式：
 *   - .docx → XWPFDocument（逐段取文本）
 *   - .xlsx → XSSFWorkbook（逐单元格取显示文本）
 *   - .pptx → XMLSlideShow（逐页取文本形状）
 */
object DocxEngine {

    private const val TAG = "DocxEngine"

    fun extractText(file: File): String {
        val ext = file.extension.lowercase()
        return try {
            FileInputStream(file).use { fis ->
                when (ext) {
                    "docx" -> extractDocx(fis)
                    "xlsx" -> extractXlsx(fis)
                    "pptx" -> extractPptx(fis)
                    else -> throw IllegalArgumentException("不支持的 OOXML 格式: .$ext")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OOXML 提取失败 [${file.name}]: ${e.javaClass.simpleName}: ${e.message}")
            ""
        }
    }

    private fun extractDocx(fis: FileInputStream): String {
        val doc = XWPFDocument(fis)
        val sb = StringBuilder()
        try {
            for (para: XWPFParagraph in doc.paragraphs) {
                val text = para.text
                if (!text.isNullOrBlank()) sb.append(text).append("\n")
            }
            // 也检查表格中的文本
            for (table in doc.tables) {
                for (row in table.rows) {
                    for (cell in row.tableCells) {
                        val cellText = cell.text
                        if (!cellText.isNullOrBlank()) sb.append(cellText).append(" ")
                    }
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append("\n")
                }
            }
        } finally {
            runCatching { doc.close() }
        }
        return sb.toString()
    }

    private fun extractXlsx(fis: FileInputStream): String {
        val wb = XSSFWorkbook(fis)
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

    private fun extractPptx(fis: FileInputStream): String {
        val ppt = XMLSlideShow(fis)
        val sb = StringBuilder()
        try {
            for (slide in ppt.slides) {
                for (shape in slide.shapes) {
                    if (shape is XSLFTextShape) {
                        val t = shape.text
                        if (!t.isNullOrBlank()) sb.append(t).append("\n")
                    }
                    // 也检查表格和组合形状中的文本
                    // 简化版：只处理顶层文本形状
                }
            }
        } finally {
            runCatching { ppt.close() }
        }
        return sb.toString()
    }
}
