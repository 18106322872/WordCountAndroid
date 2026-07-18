package com.henry.wordcount

import android.util.Log
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileInputStream

/**
 * PDF 文本提取层：Apache PdfBox（纯 Java，安卓原生运行）。
 *
 * v1.0.14 新增：彻底绕开 Chaqopy/Python，解决 AssetFinder/scripts 导致的 100% 失败问题。
 * PdfBox 是纯 Java 实现，不依赖任何原生库或 Python 运行时。
 */
object PdfEngine {

    private const val TAG = "PdfEngine"

    /**
     * 从 PDF 文件提取全部文本内容。
     * @return 提取出的文本（可能为空字符串），不会返回 null
     */
    fun extractText(file: File): String {
        var doc: PDDocument? = null
        try {
            FileInputStream(file).use { fis ->
                doc = PDDocument.load(fis)
                val stripper = PDFTextStripper()
                // 设置排序：按阅读顺序提取（而非原始 PDF 流顺序）
                stripper.sortByPosition = true
                val text = stripper.getText(doc)
                return text ?: ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "PDF 文本提取失败 [${file.name}]: ${e.javaClass.simpleName}: ${e.message}")
            return ""
        } finally {
            try { doc?.close() } catch (_: Exception) {}
        }
    }

    /** 获取 PDF 页数（用于展示） */
    fun getPageCount(file: File): Int {
        try {
            PDDocument.load(FileInputStream(file)).use { doc ->
                return doc.numberOfPages
            }
        } catch (_: Exception) {
            return -1
        }
    }
}
