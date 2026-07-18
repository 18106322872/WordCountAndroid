package com.henry.wordcount

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * DWG 文件文字提取（轻量方案，无外部依赖）。
 *
 * DWG 是 AutoCAD 专有二进制格式，安卓上无免费可靠的解析库。
 * 本实现通过扫描二进制文件中的可读字符串来提取文字，
 * 能捕获大部分 TEXT/MTEXT/ATTDEF 实体文本（DWG R2000+ 文字以 UTF-16LE 编码存储）。
 *
 * v1.0.17 改进：
 *   - 文件大小限制：>2MB 直接放弃（大 DWG 二进制扫描无意义且极慢）
 *   - 严格过滤：只保留含 CJK/字母数字的连续序列（≥3 字符），丢弃纯乱码
 *   - 输出上限：总字符不超过 5000（防止二进制噪声污染统计）
 *   - 超时保护：3 秒扫描超时
 */
object DwgEngine {

    /** 最大处理的文件大小（字节）。超过此大小的 DWG 直接返回空。 */
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024 // 2MB

    /** 扫描超时（毫秒） */
    private const val TIMEOUT_MS = 3_000L

    /** 单个字符串最小长度 */
    private const val MIN_STRING_LEN = 3

    /** 输出总字符上限 */
    private const val MAX_OUTPUT_CHARS = 5_000

    fun extractText(file: File): String {
        return extractTextSafe(file)
    }

    /** 带大小限制和超时保护的提取方法。 */
    fun extractTextSafe(file: File): String {
        // 大小检查
        val len = file.length()
        if (len > MAX_FILE_SIZE || len <= 0) return ""
        if (len < 16) return "" // 太小不可能是有效 DWG

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        return try {
            val bytes = file.readBytes()
            val results = LinkedHashSet<String>()
            scanForStrings(bytes, StandardCharsets.UTF_16LE, results, deadline)
            if (System.currentTimeMillis() < deadline) {
                scanForStrings(bytes, StandardCharsets.UTF_8, results, deadline)
            }
            // 严格过滤 + 输出截断
            val output = results
                .filter { it.length >= MIN_STRING_LEN && hasMeaningfulContent(it) }
                .joinToString("\n")
            if (output.length > MAX_OUTPUT_CHARS) output.take(MAX_OUTPUT_CHARS) else output
        } catch (_: Throwable) { "" }
    }

    /**
     * 扫描二进制中的可读字符串。
     *
     * 关键改进（v1.0.17）：
     *   - 只收集连续的**字母/数字/CJK**字符序列（空白和标点作为分隔符而非连接符）
     *   - 这样可以避免把二进制数据中的零散字节拼凑成"长字符串"
     */
    private fun scanForStrings(bytes: ByteArray, cs: Charset, output: MutableSet<String>, deadline: Long) {
        val unit = if (cs == StandardCharsets.UTF_16LE) 2 else 1
        val sb = StringBuilder()
        var pos = 0
        while (pos + unit <= bytes.size) {
            // 超时检查：每 64KB 检查一次
            if ((pos and 0xFFFF) == 0 && System.currentTimeMillis() > deadline) break
            val ch = try {
                String(bytes, pos, unit, cs)[0]
            } catch (_: Exception) {
                flush(sb, output); pos += unit; continue
            }
            // **只接受真正的文字字符**——字母、数字、CJK；空白/标点一律断开
            if (isTextChar(ch)) {
                sb.append(ch)
            } else {
                flush(sb, output)
            }
            pos += unit
        }
        flush(sb, output)
    }

    private fun flush(sb: StringBuilder, output: MutableSet<String>) {
        if (sb.length >= MIN_STRING_LEN) {
            val s = sb.toString().trim()
            if (s.length >= MIN_STRING_LEN && hasMeaningfulContent(s)) output.add(s)
        }
        sb.setLength(0)
    }

    /**
     * 判断是否为"有意义的内容"：
     *   - 必须包含至少一个 CJK 字符或拉丁字母（排除纯数字/纯符号串）
     */
    private fun hasMeaningfulContent(s: String): Boolean {
        var hasAlpha = false
        var hasCjk = false
        for (c in s) {
            if (c.isLetter()) {
                hasAlpha = true
                if (isCjk(c)) hasCjk = true
            }
        }
        return hasAlpha && s.length >= MIN_STRING_LEN
    }

    /**
     * 判断是否为文字字符（用于拼接字符串）。
     * 注意：v1.0.17 不再把空白和标点当连接符——它们现在是分隔符。
     */
    private fun isTextChar(ch: Char): Boolean {
        return ch.isLetterOrDigit() || isCjk(ch)
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF ||   // CJK Unified Ideographs
                code in 0x3400..0x4DBF ||   // CJK Extension A
                code in 0x3000..0x303F ||   // Symbols/Punctuation (少量保留)
                code in 0xFF00..0xFFEF)     // Fullwidth forms
    }
}
