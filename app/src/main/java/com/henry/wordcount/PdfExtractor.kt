package com.henry.wordcount

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.comparisons.minOf

/**
 * 纯 Kotlin 的 PDF 文本抽取与页数统计层（无任何第三方库）。
 *
 * 能力范围（够用即可，目标是「字数统计」而非精确排版）：
 *  - 页数：扫描 /Type /Page（叶子页，排除 /Pages）计数。
 *  - 文本：解压 FlateDecode 内容流，解析 Tj / TJ 操作符中的字面串与十六进制串，
 *          应用 ToUnicode CMap（若内嵌）还原可读文字；无 CMap 时按 CP1252 解码。
 *  - 重压缩流（LZW/ASCII85 等）未实现，会优雅跳过该流（不崩溃）。
 *
 * 全部用标准 JDK：Inflater / GZIPInputStream / 正则。不碰任何外部依赖，规避此前 CI 编译失败。
 */
object PdfExtractor {

    data class PdfResult(val text: String, val pages: Int)

    fun extract(file: File): PdfResult? {
        val bytes = try { file.readBytes() } catch (_: Throwable) { return null }
        // PDF 魔数检查（宽容模式：不以 %PDF 开头也尝试）
        if (bytes.size < 5) return null
        val header = String(bytes, 0, minOf(8, bytes.size), StandardCharsets.ISO_8859_1)
        if (!header.startsWith("%PDF") && !header.startsWith("%PDF-")) {
            // 非 PDF 文件，直接返回 null 不浪费时间
            return null
        }
        return try {
            val pages = countPages(bytes)
            val text = extractTextRobust(bytes)
            PdfResult(text, max(1, pages))
        } catch (_: Throwable) {
            // 最后兜底：至少返回页数
            try { PdfResult("", max(1, countPages(bytes))) } catch (_: Throwable) { null }
        }
    }

    // ───────────────────────── 页数 ─────────────────────────
    private fun countPages(bytes: ByteArray): Int {
        val s = String(bytes, StandardCharsets.ISO_8859_1)
        // 叶子页：/Type /Page 且后接非 s/S（排除 /Pages）
        val leaf = """/Type\s*/\s*Page(?![sS])""".toRegex().findAll(s).count()
        if (leaf > 0) return leaf
        // 兜底：退化为统计 /Page 出现次数（含 /Pages），再 /2 估算
        val any = """/Type\s*/\s*Page""".toRegex().findAll(s).count()
        return max(1, any / 2)
    }

    // ───────────────────────── 文本（鲁棒版：逐流容错） ─────────────────────────
    private fun extractTextRobust(bytes: ByteArray): String {
        val sb = StringBuilder()
        // 1) 先尝试标准流解析
        try {
            val toUnicode = parseToUnicode(bytes)
            val rawStr = String(bytes, StandardCharsets.ISO_8859_1)
            val streamRe = """(?s)stream\r?\n(.*?)endstream""".toRegex()
            var streamCount = 0
            var textCount = 0
            streamRe.findAll(rawStr).forEach { m ->
                streamCount++
                try {
                    val raw = m.groupValues[1].toByteArray(StandardCharsets.ISO_8859_1)
                    val probe = String(raw, StandardCharsets.ISO_8859_1)
                    if (!probe.contains("Tj") && !probe.contains("TJ") && !probe.contains("BT")) return@forEach
                    val data = tryDecompress(raw, bytes, m.range.first) ?: raw
                    val text = decodeContentStream(data, toUnicode)
                    if (text.isNotBlank()) { sb.append(text).append('\n'); textCount++ }
                } catch (_: Throwable) { /* 单流失败跳过 */ }
            }
            if (textCount > 0) {
                val cleaned = cleanExtractedText(sb.toString())
                if (cleaned.isNotBlank()) return cleaned
            }
        } catch (_: Throwable) { /* 标准解析完全失败，尝试备用方案 */ }

        // 2) 备用方案：直接从原始字节中提取所有可读文本片段
        return extractRawReadableStrings(bytes)
    }

    /** 从 PDF 原始字节中提取可读 UTF-8 / CP1252 字符串片段（保守模式）。 */
    private fun extractRawReadableStrings(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size - 3) {
            val ch = bytes[i].toInt() and 0xFF
            if (ch >= 0x20 && ch < 0x7F) {
                var j = i
                while (j < bytes.size) {
                    val c2 = bytes[j].toInt() and 0xFF
                    if (c2 < 0x20 || c2 > 0x7E) break
                    j++
                }
                if (j - i >= 4) {
                    val candidate = String(bytes, i, j - i, StandardCharsets.US_ASCII)
                    // 过滤掉 PDF 结构性关键词和操作符（它们不是正文）
                    if (!isPdfStructuralGarbage(candidate)) sb.append(candidate).append(' ')
                }
                i = j
            } else if ((ch == 0xE4 || ch == 0xE5 || ch == 0xE6 || ch == 0xE7 ||
                       ch == 0xE8 || ch == 0xE9) && i + 2 < bytes.size) {
                val b2 = bytes[i+1].toInt() and 0xFF
                val b3 = bytes[i+2].toInt() and 0xFF
                if (b2 in 0x80..0xBF && b3 in 0x80..0xBF) {
                    var j = i
                    while (j + 2 < bytes.size) {
                        val c1 = bytes[j].toInt() and 0xFF
                        val c2b = bytes[j+1].toInt() and 0xFF
                        val c3 = bytes[j+2].toInt() and 0xFF
                        if (c1 in 0xE0..0xEF && c2b in 0x80..0xBF && c3 in 0x80..0xBF) j += 3 else break
                    }
                    if (j > i) {
                        try { sb.append(String(bytes, i, j - i, StandardCharsets.UTF_8)).append(' ') } catch (_: Throwable) {}
                    }
                    i = j
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return sb.toString().trim()
    }

    /** 对提取的文本做最终清洗：去掉纯数字/符号行、合并多余空行。 */
    private fun cleanExtractedText(text: String): String {
        return text.lines()
            .map { it.trim() }
            .filter { line ->
                if (line.length <= 1) return@filter false
                // 保留含字母/CJK 的行，丢弃纯数字/纯符号行
                val hasAlpha = line.any { it.isLetter() }
                hasAlpha
            }
            .filter { !isPdfStructuralGarbage(it) }
            .joinToString("\n")
    }

    /** 判断 ASCII 片段是否为 PDF 结构性垃圾（非正文内容）。 */
    private fun isPdfStructuralGarbage(s: String): Boolean {
        // PDF 操作符 / 关键字 / 字典键 —— 这些不是用户可见文字
        val lower = s.lowercase()
        val garbagePrefixes = listOf(
            "/type", "/subtype", "/filter", "/length", "/root", "/parent",
            "/resources", "/font", "/encoding", "/tounicode", "/contents", "/mediabox",
            "/cropbox", "/rotate", "/annots", "/pages", "/kids", "/count", "/catalog",
            "/basefont", "/firstchar", "/lastchar", "/widths", "/descriptor",
            "/name", "/cs", "/gs", "/d", "/i", "/j", "/jm", "/mcid",
            "/structparents", "/lang", "/actualtext", "/alt", "/b", "/c", "/ca",
            "/s", "/f", "/a", "/n", "/v", "/r", "/tr", "/ref", "/p",
            "stream", "endstream", "obj", "endobj", "xref", "trailer", "startxref",
            "flatedecode", "asciihexdecode", "lzwdecode", "ccittfaxdecode", "dctdecode",
            "beginbfchar", "endbfchar", "beginbfrange", "endbfrange",
            "/linearized", "/o", "/e", "/h", "/l", "/t", "/helv", "/za db",
            "cidfont", "cidtounicodemap"
        )
        for (prefix in garbagePrefixes) {
            if (lower.startsWith(prefix)) return true
        }
        // 纯数字串或纯符号串 → 不是正文
        if (s.all { it.isDigit() || it == '.' || it == '-' || it == '+' }) return true
        if (s.length <= 2 && !s.any { it.isLetterOrDigit() }) return true
        return false
    }

    /** 尝试 Flate 解压；失败返回 null（调用方用原数据）。 */
    private fun tryDecompress(raw: ByteArray, full: ByteArray, streamStart: Int): ByteArray? {
        // 仅当流前导字典声明 FlateDecode 才解压（避免误伤）
        val before = full.copyOfRange(max(0, streamStart - 400), streamStart)
        val dict = String(before, StandardCharsets.ISO_8859_1)
        val useFlate = dict.contains("FlateDecode")
        val useHex = dict.contains("ASCIIHexDecode")
        val useLzw = dict.contains("LZWDecode")
        return when {
            useFlate -> try {
                val inf = Inflater()
                inf.setInput(raw)
                val out = ByteArrayOutputStreamSafe(raw.size * 3)
                val buf = ByteArray(8192)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) break; else break }
                    out.write(buf, 0, n)
                }
                inf.end()
                out.toBytes()
            } catch (_: Throwable) { null }
            useHex -> try { hexDecodeStream(raw) } catch (_: Throwable) { null }
            useLzw -> null // 未实现，跳过
            else -> raw
        }
    }

    private fun decodeContentStream(data: ByteArray, toUnicode: Map<Int, String>): String {
        val s = String(data, StandardCharsets.ISO_8859_1)
        val out = StringBuilder()
        // Tj: (...) Tj  或  <...> Tj
        val tjRe = """\(((?:[^()\\]|\\.)*)\)\s*Tj|<([0-9A-Fa-f\s]*)>\s*Tj""".toRegex()
        tjRe.findAll(s).forEach { m ->
            val txt = if (m.groupValues[1].isNotEmpty()) decodeLiteral(m.groupValues[1], toUnicode)
            else decodeHex(m.groupValues[2], toUnicode)
            out.append(txt)
        }
        // TJ: [ (a) 12 (b) -3 <c> ] TJ
        val tjArrRe = """\[\s*((?:(?:\((?:[^()\\]|\\.)*\)|<[0-9A-Fa-f\s]*>|-?\d+)\s*)*)\]\s*TJ""".toRegex()
        tjArrRe.findAll(s).forEach { m ->
            val inner = m.groupValues[1]
            val partRe = """\(((?:[^()\\]|\\.)*)\)|<([0-9A-Fa-f\s]*)>""".toRegex()
            partRe.findAll(inner).forEach { p ->
                val txt = if (p.groupValues[1].isNotEmpty()) decodeLiteral(p.groupValues[1], toUnicode)
                else decodeHex(p.groupValues[2], toUnicode)
                out.append(txt)
            }
        }
        return out.toString()
    }

    private fun decodeLiteral(lit: String, toUnicode: Map<Int, String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < lit.length) {
            val c = lit[i]
            if (c == '\\') {
                i++
                if (i >= lit.length) break
                val n = lit[i]
                when (n) {
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); '\\' -> sb.append('\\')
                    '(' -> sb.append('('); ')' -> sb.append(')')
                    in '0'..'7' -> {
                        // 八进制 \ddd
                        var oct = ""
                        var j = i
                        while (j < lit.length && lit[j] in '0'..'7' && oct.length < 3) { oct += lit[j]; j++ }
                        i = j - 1
                        val code = oct.toIntOrNull(8) ?: 0
                        sb.append(mapGlyph(code, toUnicode))
                    }
                    else -> sb.append(n)
                }
                i++
            } else {
                sb.append(mapGlyph(c.code, toUnicode))
                i++
            }
        }
        return sb.toString()
    }

    private fun decodeHex(hex: String, toUnicode: Map<Int, String>): String {
        val clean = hex.replace("\\s".toRegex(), "")
        if (clean.length % 2 != 0) return ""
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < clean.length) {
            val code = clean.substring(i, i + 2).toIntOrNull(16) ?: 0
            sb.append(mapGlyph(code, toUnicode))
            i += 2
        }
        return sb.toString()
    }

    /** 字形码 → 字符：有 ToUnicode 映射用映射，否则 CP1252。 */
    private fun mapGlyph(code: Int, toUnicode: Map<Int, String>): String {
        toUnicode[code]?.let { return it }
        return try { String(byteArrayOf(code.toByte()), cp1252()) } catch (_: Throwable) { "\uFFFD" }
    }

    private var _cp1252: Charset? = null
    private fun cp1252(): Charset {
        if (_cp1252 == null) {
            _cp1252 = try { Charset.forName("windows-1252") } catch (_: Throwable) { StandardCharsets.ISO_8859_1 }
        }
        return _cp1252!!
    }

    // ───────────────────────── ToUnicode ─────────────────────────
    private fun parseToUnicode(bytes: ByteArray): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val s = String(bytes, StandardCharsets.ISO_8859_1)
        // 找所有 ToUnicode 流
        val re = """(?s)/ToUnicode\s*(\d+\s+\d+\s+obj)?.*?stream\r?\n(.*?)endstream""".toRegex()
        re.findAll(s).forEach { m ->
            val cm = m.groupValues[2]
            // beginbfchar
            """(?s)beginbfchar\s*(.*?)\s*endbfchar""".toRegex().findAll(cm).forEach { blk ->
                """<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""".toRegex().findAll(blk.groupValues[1]).forEach { e ->
                    val src = e.groupValues[1].toIntOrNull(16) ?: return@forEach
                    val dst = codePointsToStr(e.groupValues[2])
                    map[src] = dst
                }
            }
            // beginbfrange
            """(?s)beginbfrange\s*(.*?)\s*endbfrange""".toRegex().findAll(cm).forEach { blk ->
                """<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""".toRegex().findAll(blk.groupValues[1]).forEach { e ->
                    val start = e.groupValues[1].toIntOrNull(16) ?: return@forEach
                    val end = e.groupValues[2].toIntOrNull(16) ?: return@forEach
                    val dstStart = e.groupValues[3].toIntOrNull(16) ?: return@forEach
                    var d = dstStart
                    for (src in start..end) { map[src] = codePointsToStr(d.toString(16)); d++ }
                }
            }
        }
        return map
    }

    private fun codePointsToStr(hex: String): String {
        val clean = hex.replace("\\s".toRegex(), "")
        if (clean.isEmpty()) return ""
        return try {
            val cps = mutableListOf<Int>()
            var i = 0
            while (i + 1 < clean.length) { cps.add(clean.substring(i, i + 2).toIntOrNull(16) ?: 0); i += 2 }
            // 若每码点 4 位（UCS-2）
            if (clean.length % 4 == 0 && clean.length >= 4) {
                val cps2 = mutableListOf<Int>()
                var j = 0
                while (j + 3 < clean.length) { cps2.add(clean.substring(j, j + 4).toIntOrNull(16) ?: 0); j += 4 }
                String(cps2.toIntArray(), 0, cps2.size)
            } else {
                String(cps.toIntArray(), 0, cps.size)
            }
        } catch (_: Throwable) { "" }
    }

    private fun hexDecodeStream(raw: ByteArray): ByteArray {
        val s = String(raw, StandardCharsets.ISO_8859_1).replace("\\s".toRegex(), "")
            .substringBefore(">")
        val out = ByteArrayOutputStreamSafe(s.length / 2)
        var i = 0
        while (i + 1 < s.length) {
            val b = s.substring(i, i + 2).toIntOrNull(16) ?: 0
            out.write(b)
            i += 2
        }
        return out.toBytes()
    }
}

/** 简单可增长的字节输出流（避免引入 Apache Commons 等依赖）。 */
private class ByteArrayOutputStreamSafe(initial: Int) {
    private var buf = ByteArray(if (initial < 32) 32 else initial)
    private var size = 0
    fun write(b: Int) {
        if (size >= buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = b.toByte()
    }
    fun write(b: ByteArray, off: Int, len: Int) {
        if (size + len > buf.size) { var n = buf.size; while (n < size + len) n *= 2; buf = buf.copyOf(n) }
        System.arraycopy(b, off, buf, size, len); size += len
    }
    fun toBytes(): ByteArray = buf.copyOf(size)
}

// 复用的 gzip 工具（供 ArchiveEngine 解 .gz/.tgz 使用）
internal fun gunzip(bytes: ByteArray): ByteArray {
    val inf = GZIPInputStream(ByteArrayInputStream(bytes))
    val out = ByteArrayOutputStreamSafe(bytes.size * 2)
    val buf = ByteArray(8192)
    var n: Int
    while (inf.read(buf).also { n = it } != -1) out.write(buf, 0, n)
    inf.close()
    return out.toBytes()
}
