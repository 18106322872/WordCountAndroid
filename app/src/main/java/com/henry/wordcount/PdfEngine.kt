package com.henry.wordcount

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.Inflater
import java.util.zip.DataFormatException

/**
 * 轻量级 PDF 文本提取器（纯 Java，零外部依赖）。
 *
 * v1.0.14 设计思路：PdfBox 在 Android 上有 java.awt 编译依赖导致构建失败，
 * 故自实现一个最小化 PDF 流解析器：
 *   1) 扫描 PDF 原始字节流，定位 stream/endstream 对
 *   2) 解码 FlateDecode（zlib/deflate，Android 原生支持 Inflater）
 *   3) 从解码后的字节中提取可读文本（过滤控制字符）
 *
 * 局限性：不处理交叉引用流、加密 PDF、CJK 字体编码映射等复杂情况。
 * 对大部分「文字型 PDF」（Word/WPS 导出的 PDF、文本 PDF）效果良好；
 * 对扫描件/图片 PDF 会返回空字符串。
 */
object PdfEngine {

    private const val TAG = "PdfEngine"

    fun extractText(file: File): String {
        try {
            val bytes = FileInputStream(file).use { it.readBytes() }
            return extractTextFromBytes(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "PDF 提取失败 [${file.name}]: ${e.message}")
            return ""
        }
    }

    /** 粗略估算页数（通过统计 /Page /Type /Catalog 等标记） */
    fun getPageCount(file: File): Int {
        try {
            val text = FileInputStream(file).use { it.bufferedReader().readText() }
            // 统计 /Type /Page（非 /Pages）出现次数作为粗略页数
            val pagePattern = Regex("/Type\\s*/Page[^s]")
            return pagePattern.findAll(text).count().takeIf { it > 0 } ?: -1
        } catch (_: Exception) {
            return -1
        }
    }

    // ── 内部实现 ──

    private fun extractTextFromBytes(bytes: ByteArray): String {
        val result = StringBuilder()
        val content = String(bytes, Charsets.ISO_8859_1)

        // 查找所有 stream ... endstream 块
        var start = 0
        while (true) {
            val streamStart = content.indexOf("stream", start)
            if (streamStart < 0) break

            // stream 关键字后应跟 \r\n 或 \n
            val dataStart = when {
                bytes.getOrNull(streamStart + 6) == 0x0D.toByte() && bytes.getOrNull(streamStart + 7) == 0x0A.toByte() -> streamStart + 8
                bytes.getOrNull(streamStart + 6) == 0x0A.toByte() -> streamStart + 7
                else -> streamStart + 6
            }

            val endStream = content.indexOf("endstream", dataStart)
            if (endStream < 0) break

            // 提取流数据（endstream 前可能有 \r\n）
            var dataEnd = endStream
            if (dataEnd > 0 && bytes.getOrNull(dataEnd - 1) == 0x0A.toByte()) dataEnd--
            if (dataEnd > 0 && bytes.getOrNull(dataEnd - 1) == 0x0D.toByte()) dataEnd--

            if (dataEnd > dataStart) {
                val streamData = bytes.sliceArray(dataStart until dataEnd)
                val decoded = tryDecodeStream(streamData, content, streamStart)
                if (decoded.isNotEmpty()) {
                    val text = extractReadableText(decoded)
                    if (text.isNotEmpty()) result.append(text).append("\n")
                }
            }

            start = endStream + 9 // 跳过 "endstream"
        }

        return result.toString()
    }

    /** 尝试解码流数据（支持 FlateDecode 和原始文本） */
    private fun tryDecodeStream(data: ByteArray, fullContent: String, streamKeywordPos: Int): ByteArray {
        // 向前查找此 stream 对象的 Filter 声明（通常在前面的 obj 定义中）
        // 典型格式: /Filter /FlateDecode 或 /Filter [/FlateDecode]
        val regionStart = (streamKeywordPos - 200).coerceAtLeast(0)
        val region = fullContent.substring(regionStart, streamKeywordPos)

        return when {
            region.contains("/FlateDecode") -> decodeFlate(data)
            else -> data // 未编码或未知编码，直接当原始数据
        }
    }

    private fun decodeFlate(compressed: ByteArray): ByteArray {
        try {
            val inflater = Inflater(true) // raw deflate (no zlib header)
            inflater.setInput(compressed)
            val output = ByteArrayOutputStream(compressed.size * 4)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count > 0) output.write(buf, 0, count)
                else break
            }
            output.close()
            return output.toByteArray()
        } catch (e: DataFormatException) {
            // 可能是 zlib 格式（带 header），重试
            try {
                val inflater2 = Inflater() // zlib wrapper
                inflater2.setInput(compressed)
                val output = ByteArrayOutputStream(compressed.size * 4)
                val buf = ByteArray(8192)
                while (!inflater2.finished()) {
                    val count = inflater2.inflate(buf)
                    if (count > 0) output.write(buf, 0, count)
                    else break
                }
                output.close()
                return output.toByteArray()
            } catch (_: Exception) {
                return ByteArray(0)
            }
        } catch (_: Exception) {
            return ByteArray(0)
        }
    }

    /** 从解码后的字节中提取可读文本（过滤掉二进制垃圾和控制字符） */
    private fun extractReadableText(decoded: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < decoded.size) {
            val b = decoded[i].toInt() and 0xFF
            when {
                b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D -> {
                    // 控制字符：跳过连续的控制字符块
                    i++
                }
                b >= 0x20 && b < 0x7F -> {
                    // ASCII 可打印字符
                    sb.append(b.toChar())
                    i++
                }
                b >= 0x80 -> {
                    // 尝试 UTF-8 多字节序列
                    var charLen = 0
                    var codePoint = 0
                    when {
                        b in 0xC0..0xDF -> { charLen = 2; codePoint = b and 0x1F }
                        b in 0xE0..0xEF -> { charLen = 3; codePoint = b and 0x0F }
                        b in 0xF0..0xF7 -> { charLen = 4; codePoint = b and 0x07 }
                        else -> { i++; continue }
                    }
                    var valid = true
                    for (j in 1 until charLen) {
                        if (i + j >= decoded.size) { valid = false; break }
                        val cb = decoded[i + j].toInt() and 0xFF
                        if (cb !in 0x80..0xBF) { valid = false; break }
                        codePoint = (codePoint shl 6) or (cb and 0x3F)
                    }
                    if (valid && codePoint > 0) {
                        sb.appendCodePoint(codePoint)
                        i += charLen
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
        return sb.toString()
    }
}
