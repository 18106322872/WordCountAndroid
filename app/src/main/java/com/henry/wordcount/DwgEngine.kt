package com.henry.wordcount

import java.io.File
import java.io.FileInputStream

/**
 * DWG 文件文字提取（轻量方案，无外部依赖）。
 *
 * v1.0.20 重大修复：
 *   - 消除「所有 DWG 都统计出约 1000 字」的根因：此前扫描器会提取每个 DWG 都包含的
 *     AutoCAD 元数据词汇（层名/样式名/线型名/字体名等：Standard, ByLayer, Continuous,
 *     Model, Layout, txt, romans...），这些不是图纸真实文字内容而是结构元数据。
 *   - 现在增加 **DWG 元数据停用词表**（DWG_METADATA_STOPWORDS），在输出前过滤掉
 *     这些在每个 DWG 中都会出现的通用术语，只保留可能是用户实际写入的图纸文字。
 *   - 同时提高 MIN_RUN 到 5、降低 MAX_TOKENS 到 300，进一步减少噪声。
 */
object DwgEngine {

    private const val CHUNK = 64 * 1024
    private const val TIMEOUT_MS = 6_000L
    private const val MIN_RUN = 5          // v1.0.20: 从 4 提到 5，滤掉短噪声
    private const val MAX_OUTPUT_CHARS = 8_000
    private const val MAX_TOKENS = 300      // v1.0.20: 从 800 降到 300，减少元数据噪声量

    /**
     * DWG 元数据停用词表——这些词汇出现在几乎每个 AutoCAD DWG 文件中，
     * 是层名/样式名/线型名/字体名/表名等结构元数据，不是用户的图纸文字。
     *
     * 包含：
     *   - 预定义层名和特殊层
     *   - 标准样式/线型名称
     *   - SHX 字体文件名（不含扩展名的 .shx 名称）
     *   - 常见 AutoCAD 系统关键字
     *   - 通用英文单词（在 DWG 中出现频率极高但无实际语义）
     */
    private val DWG_METADATA_STOPWORDS = setOf(
        // ── 预定义层/对象类型 ──
        "standard", "bylayer", "byblock", "continuous",
        "defpoints", "model", "layout", "paper", "space",
        // ── 线型名称 ──
        "center", "dashed", "dot", "divide", "border", "phantom",
        "hidden", "dashdot", "chain", "zigzag",
        // ── SHX 字体文件名（AutoCAD 内置/常见第三方） ──
        "txt", "romans", "romanc", "italicc", "italict",
        "scripts", "scriptc", "greeks", "greekc",
        "cyrillic", "cyriltlc", "monotxt", "simplex",
        "complex", "isoct", "isocteur",
        // ── AutoCAD 系统关键字 / 表名 ──
        "autocad", "acad", "entity", "handle", "object",
        "dictionary", "linetype", "layer", "style", "block",
        "viewport", "ucs", "view", "table", "id", "type",
        "owner", "flags", "count", "index", "name", "data",
        "null", "true", "false", "none", "normal",
        // ── 极常见的通用词（在 DWG 元数据中高频但非用户文字）──
        "color", "width", "height", "length", "angle",
        "point", "line", "circle", "arc", "text",
        "dimension", "leader", "hatch", "solid", "polyline",
        "insert", "attrib", "mtext", "attdef",
        // ── 版本/格式相关 ──
        "acdb", "acds", "acim", "objects", "classes",
        "handles", "summaryinfo", "preview", "appinfo",
        "filedeps", "security", "revhistory", "header",
        "auxheader", "signature", "template"
    ).map { it.lowercase() }.toHashSet()

    fun extractText(file: File): String = extractTextSafe(file)

    fun extractTextSafe(file: String): String {
        return extractTextSafe(File(file))
    }

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
                b in 0x20..0x7E && (i + 1 >= len || (buf[i + 1].toInt() and 0xFF) != 0x00) -> {
                    asciiBuf.append(b.toChar()); i++
                }
                b in 0x20..0x7E && i + 1 < len && (buf[i + 1].toInt() and 0xFF) == 0x00 -> {
                    while (i + 1 < len) {
                        val c = buf[i].toInt() and 0xFF
                        val nxt = buf[i + 1].toInt() and 0xFF
                        if (c in 0x20..0x7E && nxt == 0x00) { asciiBuf.append(c.toChar()); i += 2 }
                        else break
                    }
                }
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
        if (s.length >= MIN_RUN && looksLikeWord(s) && !isMetadataWord(s)) {
            if (seen.add(s)) out.append(s).append('\n')
        }
    }

    private fun flushCjk(buf: StringBuilder, seen: LinkedHashSet<String>, out: StringBuilder) {
        if (buf.isEmpty()) return
        val s = buf.toString()
        buf.setLength(0)
        // 中文串至少 3 字；且过滤掉纯数字/CJK 组合的常见 DWG 元数据中文
        if (s.length >= 3) {
            if (seen.add(s)) out.append(s).append('\n')
        }
    }

    /** 判断是否为 DWG 元数据停用词（大小写不敏感） */
    private fun isMetadataWord(s: String): Boolean {
        return DWG_METADATA_STOPWORDS.contains(s.lowercase())
    }

    /**
     * 判断串是否"像真实单词"：
     *   - 必须含字母
     *   - 且必须含元音 a/e/i/o/u（排除随机十六进制如 "1F2A"）
     *   - v1.0.20: 长度 ≥ MIN_RUN（已在调用方保证），额外要求不能是全大写缩写
     *         （如 "ACAD"、"UCS" 这类全大写短串通常是系统标识符）
     */
    private fun looksLikeWord(s: String): Boolean {
        if (s.all { it.isUpperCase() } && s.length <= 6) return false // 全大写短串 → 系统缩写
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
