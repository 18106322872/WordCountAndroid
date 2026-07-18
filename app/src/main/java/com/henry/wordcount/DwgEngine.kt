package com.henry.wordcount

import java.io.File
import java.io.FileInputStream

/**
 * DWG 文件文字提取（轻量方案，无外部依赖）。
 *
 * 关键修复（v1.0.18）：
 *   - 改为**流式分块扫描**（每次只读 64KB），不再 file.readBytes() 一次性读全文件，
 *     因此大文件（数 MB~数十 MB）也能在超时内跑完，不会再“转圈/卡死”。
 *   - 扫描规则改为：只收集“连续可打印 ASCII 串（≥4 且像单词）”以及
 *     UTF-16LE 的“字母\0字母\0”模式串、UTF-8 中文三字节序列。
 *     不再把任意二进制字节当文字，因此字数接近真实图纸文字量，不会虚高到十几万。
 *   - 去重 + 总输出上限，进一步防止二进制噪声污染统计。
 */
object DwgEngine {

    private const val CHUNK = 64 * 1024
    private const val TIMEOUT_MS = 6_000L
    private const val MIN_RUN = 4
    private const val MAX_OUTPUT_CHARS = 8_000
    private const val MAX_TOKENS = 800

    fun extractText(file: File): String = extractTextSafe(file)

    fun extractTextSafe(file: File): String {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        return try {
            val out = StringBuilder()
            val seen = LinkedHashSet<String>()
            val asciiBuf = StringBuilder()
            val cjkBuf = StringBuilder()

            FileInputStream(file).use { fis ->
                val buf = ByteArray(CHUNK)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    if (System.currentTimeMillis() > deadline) break
                    scanChunk(buf, n, asciiBuf, cjkBuf, seen, out)
                    if (seen.size >= MAX_TOKENS) break
                }
                // flush 剩余缓冲
                flushRun(asciiBuf, seen, out)
                flushCjk(cjkBuf, seen, out)
            }
            val result = out.toString()
            if (result.length > MAX_OUTPUT_CHARS) result.take(MAX_OUTPUT_CHARS) else result
        } catch (_: Throwable) { "" }
    }

    private fun scanChunk(
        buf: ByteArray, len: Int,
        asciiBuf: StringBuilder, cjkBuf: StringBuilder,
        seen: LinkedHashSet<String>, out: StringBuilder
    ) {
        var i = 0
        while (i < len) {
            val b = buf[i].toInt() and 0xFF
            when {
                // 普通 ASCII 可打印字符（且下一字节不是 0x00，避免误判 UTF-16LE）
                b in 0x20..0x7E && (i + 1 >= len || (buf[i + 1].toInt() and 0xFF) != 0x00) -> {
                    asciiBuf.append(b.toChar()); i++
                }
                // UTF-16LE 的 ASCII 文本：'A'(0x41) 后跟 0x00
                b in 0x20..0x7E && i + 1 < len && (buf[i + 1].toInt() and 0xFF) == 0x00 -> {
                    while (i + 1 < len) {
                        val c = buf[i].toInt() and 0xFF
                        val nxt = buf[i + 1].toInt() and 0xFF
                        if (c in 0x20..0x7E && nxt == 0x00) { asciiBuf.append(c.toChar()); i += 2 }
                        else break
                    }
                }
                // UTF-8 中文三字节序列
                b in 0xE0..0xEF && i + 2 < len -> {
                    val b2 = buf[i + 1].toInt() and 0xFF
                    val b3 = buf[i + 2].toInt() and 0xFF
                    if (b2 in 0x80..0xBF && b3 in 0x80..0xBF) {
                        val cp = ((b and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                        val ch = cp.toChar()
                        if (isCjk(ch)) cjkBuf.append(ch) else flushCjk(cjkBuf, seen, out)
                        i += 3
                    } else {
                        flushCjk(cjkBuf, seen, out); flushRun(asciiBuf, seen, out); i++
                    }
                }
                // 其它（控制字符/二进制）：断开所有串
                else -> {
                    flushRun(asciiBuf, seen, out)
                    flushCjk(cjkBuf, seen, out)
                    i++
                }
            }
        }
    }

    private fun flushRun(buf: StringBuilder, seen: LinkedHashSet<String>, out: StringBuilder) {
        if (buf.isEmpty()) return
        val s = buf.toString()
        buf.setLength(0)
        if (s.length >= MIN_RUN && looksLikeWord(s)) {
            if (seen.add(s)) out.append(s).append('\n')
        }
    }

    private fun flushCjk(buf: StringBuilder, seen: LinkedHashSet<String>, out: StringBuilder) {
        if (buf.isEmpty()) return
        val s = buf.toString()
        buf.setLength(0)
        // 中文串至少 3 字，减少二进制随机字节误判成 CJK 的情况
        if (s.length >= 3) {
            if (seen.add(s)) out.append(s).append('\n')
        }
    }

    /**
     * 判断串是否“像真实单词”，用于滤掉二进制乱码：
     *   - 必须含字母
     *   - 且必须含元音 a/e/i/o/u（去掉“或含数字”的宽松条件，否则随机十六进制如 "1F2A"
     *     也会被当成单词，导致字数虚高）
     * 这样能排除无元音的随机串，同时保留 "Layer"、"Door"、"Wall"、"Model" 等真实文本。
     */
    private fun looksLikeWord(s: String): Boolean {
        var hasLetter = false
        var hasVowel = false
        for (c in s) {
            if (c.isLetter()) {
                hasLetter = true
                if (c.lowercaseChar() in "aeiou") hasVowel = true
            }
        }
        return hasLetter && hasVowel
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF ||
                code in 0x3000..0x303F || code in 0xFF00..0xFFEF ||
                code in 0x2E80..0x2EFF || code in 0xF900..0xFAFF
    }
}
