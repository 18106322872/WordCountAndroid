package com.henry.wordcount

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * DWG 文件文字提取（轻量方案，无外部依赖）。
 *
 * DWG 是 AutoCAD 专有二进制格式，安卓上无免费可靠的解析库：
 *   - Aspose.CAD：商业授权（~$2000+）
 *   - ODA Teigha：需原生 .dll/.so，安卓无法使用
 *   - ezdxf / python-dxf：只支持 DXF，不支持 DWG
 *
 * 本实现通过扫描二进制文件中的可读字符串来提取文字，
 * 能捕获大部分 TEXT/MTEXT/ATTDEF 实体文本（DWG R2000+ 文字以 UTF-16LE 编码存储）。
 * 精度不如真正的 CAD 解析库，但对「字数统计」场景基本够用。
 */
object DwgEngine {

    // 常见中英文标点（刻意不含双引号，避免 Kotlin 字符串字面量歧义）
    private val PUNCT = setOf<Char>(
        '，', '。', '！', '？', '、', '；', '：',
        '（', '）', '【', '】', '《', '》', '—', '…', '·', '–',
        ',', '.', '!', '?', ';', ':', '\'',
        '‘', '’', '“', '”', ' ', '　', '\t', '\n', '\r'
    )

    fun extractText(file: File): String {
        val bytes = file.readBytes()
        val results = LinkedHashSet<String>() // 去重保序
        extractRuns(bytes, StandardCharsets.UTF_16LE, results)
        extractRuns(bytes, StandardCharsets.UTF_8, results)
        return results
            .filter { it.length >= 2 && it.any { c -> c.isLetterOrDigit() } }
            .joinToString("\n")
    }

    private fun extractRuns(bytes: ByteArray, cs: Charset, output: MutableSet<String>) {
        val unit = if (cs == StandardCharsets.UTF_16LE) 2 else 1
        val sb = StringBuilder()
        var pos = 0
        while (pos + unit <= bytes.size) {
            val ch = try {
                String(bytes, pos, unit, cs)[0]
            } catch (_: Exception) {
                flush(sb, output)
                pos += unit
                continue
            }
            if (isWordChar(ch)) {
                sb.append(ch)
            } else {
                flush(sb, output)
            }
            pos += unit
        }
        flush(sb, output)
    }

    private fun flush(sb: StringBuilder, output: MutableSet<String>) {
        if (sb.length >= 2) {
            val s = sb.toString().trim()
            if (s.length >= 2 && s.any { it.isLetterOrDigit() }) output.add(s)
        }
        sb.setLength(0)
    }

    private fun isWordChar(ch: Char): Boolean {
        return ch.isLetterOrDigit() || ch.isWhitespace() || ch in PUNCT
    }
}
