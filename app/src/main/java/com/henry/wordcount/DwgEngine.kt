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
 *
 * v1.0.16: 增加超时保护（5秒），防止大文件卡死 UI 线程/协程。
 */
object DwgEngine {

    /** 超时时间（毫秒）：大 DWG 文件扫描不应超过此时间 */
    private const val TIMEOUT_MS = 5_000L

    // 常见中英文标点
    private val PUNCT = setOf<Char>(
        '\uff0c', '\u3002', '\uff01', '\uff1f', '\u3001', '\uff1b', '\uff1a',
        '\uff08', '\uff09', '\u3010', '\u3011', '\u300a', '\u300b',
        '\u2014', '\u2026', '\u00b7', '\u2013',
        ',', '.', '!', '?', ';', ':', '\'',
        '\u2018', '\u2019', '"', '"', ' ', '\u3000', '\t', '\n', '\r'
    )

    fun extractText(file: File): String {
        return extractTextSafe(file)
    }

    /** 带超时保护的提取方法（供外部调用，防止大文件卡死 UI）。 */
    fun extractTextSafe(file: File): String {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        return try {
            val bytes = file.readBytes()
            val results = LinkedHashSet<String>()
            extractRunsTimed(bytes, StandardCharsets.UTF_16LE, results, deadline)
            if (System.currentTimeMillis() < deadline) {
                extractRunsTimed(bytes, StandardCharsets.UTF_8, results, deadline)
            }
            results
                .filter { it.length >= 2 && it.any { c -> c.isLetterOrDigit() } }
                .joinToString("\n")
        } catch (_: Throwable) { "" }
    }

    /** 带超时的字符串扫描。 */
    private fun extractRunsTimed(bytes: ByteArray, cs: Charset, output: MutableSet<String>, deadline: Long) {
        val unit = if (cs == StandardCharsets.UTF_16LE) 2 else 1
        val sb = StringBuilder()
        var pos = 0
        while (pos + unit <= bytes.size) {
            // 超时检查：每扫描 100KB 检查一次时间
            if ((pos and 0xFFFF) == 0 && System.currentTimeMillis() > deadline) break
            val ch = try {
                String(bytes, pos, unit, cs)[0]
            } catch (_: Exception) {
                flush(sb, output); pos += unit; continue
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
