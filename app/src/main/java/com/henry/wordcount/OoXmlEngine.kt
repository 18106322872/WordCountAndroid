package com.henry.wordcount

import java.io.File
import java.util.zip.ZipFile
import java.io.ByteArrayOutputStream

/**
 * OOXML 文本提取引擎（v1.0.15 临时空壳）。
 * TODO: 实现真正的 ZIP+XML 解析逻辑
 */
object OoXmlEngine {
    fun extractText(file: File): String {
        // 临时返回空字符串，待实现
        return ""
    }

    internal fun parseDocxXml(bytes: ByteArray): String = ""
    internal fun parseSharedStrings(bytes: ByteArray): List<String> = emptyList()
    internal fun parseSheetXml(bytes: ByteArray, sharedStrings: List<String>): String = ""
    internal fun parsePptxSlideXml(bytes: ByteArray): String = ""

    private fun colToIndex(colRef: String): Int = 0
    private fun readZipEntry(zip: ZipFile, entry: java.util.zip.ZipEntry): ByteArray = byteArrayOf()
}
