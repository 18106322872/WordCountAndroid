package com.henry.wordcount

import java.io.File

/**
 * PDF 文本提取引擎（v1.0.15 临时空壳）。
 * TODO: 实现真正的 PDF 解析逻辑
 */
object PdfExtractor {
    fun extractText(file: File): String {
        // 临时返回空字符串，待实现
        return ""
    }

    internal fun extractFromBytes(bytes: ByteArray): String = ""

    internal fun extractTextFromDecompressed(data: ByteArray): String = ""
}
