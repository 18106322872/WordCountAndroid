package com.henry.wordcount

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.minOf

/**
 * 纯 Kotlin 的 PDF 文本抽取与页数统计层（无任何第三方库）。
 *
 * v1.0.20 重大修复：
 *   - 消除「大 PDF 卡死/转圈不进列表」的根因：此前多处将整个 PDF（可能数 MB～数十 MB）
 *     转成 String 再跑 DOTALL 正则（stream/endstream、ToUnicode、countPages），
 *     在 Android 上导致严重回溯甚至 OOM。现改为：
 *       1) 结构扫描仅取文件前 2 MB（页数 / ToUnicode CMap 定位）
 *       2) stream 提取改用迭代字节扫描（搜 "stream\n" → 跳到 "endstream"），
 *          不再对全文件做正则匹配
 *       3) 全程 5 秒时间预算，超时立即返回已有结果
 *       4) 总输出上限 200 KB，防止单 PDF 内存爆炸
 */
object PdfExtractor {

    data class PdfResult(val text: String, val pages: Int)

    /** 结构分析最多读取前多少字节（2 MB 足够覆盖页数树 + ToUnicode CMap） */
    private const val SCAN_CAP = 2 * 1024 * 1024

    /** 全文提取的时间预算（毫秒），超时立即返回已提取内容 */
    private const val TIME_BUDGET_MS = 5_000L

    /** 单个 PDF 最大输出字符数 */
    private const val MAX_OUTPUT = 200_000

    /** stream 关键字后最大数据量（防止把整个文件当做一个 stream） */
    private const val MAX_STREAM_DATA = 256 * 1024

    fun extract(file: File): PdfResult? {
        val bytes = try { file.readBytes() } catch (_: Throwable) { return null }
        if (bytes.size < 5) return null
        val header = String(bytes, 0, minOf(8, bytes.size), StandardCharsets.ISO_8859_1)
        if (!header.startsWith("%PDF") && !header.startsWith("%PDF-")) return null
        return try {
            val deadline = System.currentTimeMillis() + TIME_BUDGET_MS
            val pages = countPages(bytes)
            val text = extractTextTimed(bytes, deadline)
            PdfResult(text, max(1, pages))
        } catch (_: Throwable) {
            try { PdfResult("", max(1, countPages(bytes))) } catch (_: Throwable) { null }
        }
    }

    // ───────────────────────── 页数 ─────────────────────────
    /** 只在文件前 SCAN_CAP 字节中搜索 /Type /Page，避免大文件全转 String */
    private fun countPages(bytes: ByteArray): Int {
        val scanLen = minOf(bytes.size, SCAN_CAP)
        val s = String(bytes, 0, scanLen, StandardCharsets.ISO_8859_1)
        // 叶子页：/Type /Page 且后接非 s/S（排除 /Pages）
        val leaf = """/Type\s*/\s*Page(?![sS])""".toRegex().findAll(s).count()
        if (leaf > 0) return leaf
        val any = """/Type\s*/\s*Page""".toRegex().findAll(s).count()
        return max(1, any / 2)
    }

    // ───────────────────────── 文本（带时间预算 + 迭代扫描） ─────────────────────────
    private fun extractTextTimed(bytes: ByteArray, deadline: Long): String {
        val sb = StringBuilder()
        try {
            // 1) 先尝试标准流解析（迭代扫描，不用全文正则）
            val toUnicode = parseToUnicodeSafe(bytes, deadline)
            if (System.currentTimeMillis() > deadline) return finish(sb)

            var textCount = 0
            // 迭代式查找所有 stream...endstream 块
            findStreamBlocks(bytes) { rawBytes, dictSlice ->
                if (System.currentTimeMillis() > deadline) return@findStreamBlocks false // 超时停止
                try {
                    val probe = String(rawBytes, StandardCharsets.ISO_8859_1)
                    if (!probe.contains("Tj") && !probe.contains("TJ") && !probe.contains("BT")) return@findStreamBlocks true
                    val data = tryDecompress(rawBytes, dictSlice) ?: rawBytes
                    val text = decodeContentStream(data, toUnicode)
                    if (text.isNotBlank()) { sb.append(text).append('\n'); textCount++ }
                    if (sb.length > MAX_OUTPUT) return@findStreamBlocks false // 达到上限
                } catch (_: Throwable) { }
                true // 继续下一个 stream
            }

            if (textCount > 0 && System.currentTimeMillis() <= deadline) {
                val cleaned = cleanExtractedText(sb.toString())
                if (cleaned.isNotBlank()) return cleaned
            }
        } catch (_: Throwable) { /* 标准解析失败 */ }

        // 2) 备用方案：直接从原始字节中提取可读文本片段（同样有大小上限）
        if (System.currentTimeMillis() > deadline) return finish(sb)
        return extractRawReadableStrings(bytes, deadline)
    }

    /**
     * 迭代式扫描 PDF 中的所有 stream 数据块。
     * 对每个找到的 stream 块调用 consumer(rawData, dictBeforeStream)。
     * consumer 返回 false 则停止扫描；返回 true 则继续。
     */
    private inline fun findStreamBlocks(
        bytes: ByteArray,
        consumer: (raw: ByteArray, dictBefore: ByteArray) -> Boolean
    ) {
        val keyword = byteArrayOf('s'.code.toByte(), 't'.code.toByte(), 'r'.code.toByte(),
            'e'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte())
        val endKeyword = byteArrayOf('e'.code.toByte(), 'n'.code.toByte(), 'd'.code.toByte(),
            's'.code.toByte(), 't'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte())
        var i = 0
        while (i < bytes.size - 12) {
            // 找 "stream" 关键字
            if (matchesAt(bytes, i, keyword)) {
                val afterKw = i + keyword.size
                // "stream" 后跟 \r\n 或 \n
                val dataStart = when {
                    afterKw + 1 < bytes.size && bytes[afterKw] == '\r'.code.toByte()
                            && afterKw + 2 < bytes.size && bytes[afterKw + 1] == '\n'.code.toByte() -> afterKw + 2
                    afterKw < bytes.size && bytes[afterKw] == '\n'.code.toByte() -> afterKw + 1
                    else -> { i++; continue }
                }

                // 往前找字典片段（用于判断 Filter 类型）
                val dictStart = max(0, i - 400)

                // 找对应的 "endstream"
                var endPos = findEndStream(bytes, dataStart, endKeyword)
                val dataEnd = if (endPos >= 0) endPos else {
                    // 没找到 endstream，用长度限制兜底
                    minOf(dataStart + MAX_STREAM_DATA, bytes.size)
                }
                val dataSize = dataEnd - dataStart
                if (dataSize > 0 && dataSize <= MAX_STREAM_DATA) {
                    val rawData = bytes.copyOfRange(dataStart, dataEnd)
                    val dictSlice = bytes.copyOfRange(dictStart, i)
                    if (!consumer(rawData, dictSlice)) return
                }
                i = if (endPos >= 0) endPos + endKeyword.size else dataEnd
            } else {
                i++
            }
        }
    }

    /** 从 start 位置开始往后找 "endstream" */
    private fun findEndStream(bytes: ByteArray, start: Int, pattern: ByteArray): Int {
        var i = start
        while (i <= bytes.size - pattern.size) {
            if (matchesAt(bytes, i, pattern)) return i
            i++
        }
        return -1
    }

    private fun matchesAt(bytes: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > bytes.size) return false
        for (j in pattern.indices) {
            if (bytes[offset + j] != pattern[j]) return false
        }
        return true
    }

    /** 安全版 parseToUnicode——只扫描前 SCAN_CAP 字节 */
    private fun parseToUnicodeSafe(bytes: ByteArray, _deadline: Long): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        try {
            val scanLen = minOf(bytes.size, SCAN_CAP)
            val s = String(bytes, 0, scanLen, StandardCharsets.ISO_8859_1)
            // 用非贪婪但有限制的正则
            val re = """(?s)/ToUnicode\s*(\d+\s+\d+\s+obj)?.*?stream\r?\n(.*?)endstream""".toRegex()
            re.findAll(s).forEach { m ->
                val cm = m.groupValues[2]
                """(?s)beginbfchar\s*(.*?)\s*endbfchar""".toRegex().findAll(cm).forEach { blk ->
                    """<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""".toRegex().findAll(blk.groupValues[1]).forEach { e ->
                        val src = e.groupValues[1].toIntOrNull(16) ?: return@forEach
                        val dst = codePointsToStr(e.groupValues[2])
                        map[src] = dst
                    }
                }
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
        } catch (_: Throwable) { }
        return map
    }

    /** 从 PDF 原始字节中提取可读文本片段——带大小和时间限制 */
    private fun extractRawReadableStrings(bytes: ByteArray, deadline: Long): String {
        val sb = StringBuilder()
        val scanLen = minOf(bytes.size, SCAN_CAP)
        var i = 0
        while (i < scanLen - 3) {
            if (System.currentTimeMillis() > deadline) break
            val ch = bytes[i].toInt() and 0xFF
            if (ch in 0x20..0x7F) {
                var j = i
                while (j < scanLen) {
                    val c2 = bytes[j].toInt() and 0xFF
                    if (c2 < 0x20 || c2 > 0x7E) break
                    j++
                }
                if (j - i >= 4) {
                    val candidate = String(bytes, i, j - i, StandardCharsets.US_ASCII)
                    if (candidate.any { it == ' ' || it == '\t' } && !isPdfStructuralGarbage(candidate)) {
                        sb.append(candidate).append(' ')
                    }
                }
                i = j
            } else if ((ch == 0xE4 || ch == 0xE5 || ch == 0xE6 || ch == 0xE7 ||
                       ch == 0xE8 || ch == 0xE9) && i + 2 < scanLen) {
                val b2 = bytes[i+1].toInt() and 0xFF
                val b3 = bytes[i+2].toInt() and 0xFF
                if (b2 in 0x80..0xBF && b3 in 0x80..0xBF) {
                    var j = i
                    while (j + 2 < scanLen) {
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
                val hasAlpha = line.any { it.isLetter() }
                hasAlpha
            }
            .filter { !isPdfStructuralGarbage(it) }
            .joinToString("\n")
    }

    /** 判断 ASCII 片段是否为 PDF 结构性垃圾（非正文内容）。 */
    private fun isPdfStructuralGarbage(s: String): Boolean {
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
            "cidfont", "cidtounicodemap",
            "helvetica", "arial", "times", "courier", "symbol", "zapf",
            "winansi", "macroman", "identity", "type0", "type1", "truetype",
            "embedded", "subset", "fontfile", "fontname", "cmap", "wmode",
            "descendant", "registry", "ordering", "supplement", "differences",
            "fontbbox", "characterspacing", "wordspacing", "leading", "baseline"
        )
        for (prefix in garbagePrefixes) {
            if (lower.startsWith(prefix)) return true
        }
        if (s.all { it.isDigit() || it == '.' || it == '-' || it == '+' }) return true
        if (s.length <= 2 && !s.any { it.isLetterOrDigit() }) return true
        return false
    }

    /** 尝试 Flate 解压；失败返回 null。dictSlice 是 stream 之前的字典片段。 */
    private fun tryDecompress(raw: ByteArray, dictSlice: ByteArray): ByteArray? {
        val dict = String(dictSlice, StandardCharsets.ISO_8859_1)
        val useFlate = dict.contains("FlateDecode")
        val useHex = dict.contains("ASCIIHexDecode")
        val useLzw = dict.contains("LZWDecode")
        return when {
            useFlate -> try {
                val inf = Inflater()
                inf.setInput(raw)
                val out = ByteArrayOutputStreamSafe(minOf(raw.size * 3, 2 * 1024 * 1024))
                val buf = ByteArray(8192)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) break; else break }
                    out.write(buf, 0, n)
                    if (out.size > MAX_OUTPUT) break // 防止解压结果过大
                }
                inf.end()
                out.toBytes()
            } catch (_: Throwable) { null }
            useHex -> try { hexDecodeStream(raw) } catch (_: Throwable) { null }
            useLzw -> null
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
            if (!looksGarbled(txt)) out.append(txt)
        }
        // TJ: [ (a) 12 (b) -3 <c> ] TJ
        val tjArrRe = """\[\s*((?:(?:\((?:[^()\\]|\\.)*\)|<[0-9A-Fa-f\s]*>|-?\d+)\s*)*)\]\s*TJ""".toRegex()
        tjArrRe.findAll(s).forEach { m ->
            val inner = m.groupValues[1]
            val partRe = """\(((?:[^()\\]|\\.)*)\)|<([0-9A-Fa-f\s]*)>""".toRegex()
            partRe.findAll(inner).forEach { p ->
                val txt = if (p.groupValues[1].isNotEmpty()) decodeLiteral(p.groupValues[1], toUnicode)
                else decodeHex(p.groupValues[2], toUnicode)
                if (!looksGarbled(txt)) out.append(txt)
            }
        }
        return out.toString()
    }

    /** 判断一段解码结果是否为乱码 */
    private fun looksGarbled(s: String): Boolean {
        if (s.isEmpty()) return false
        var bad = 0
        for (c in s) {
            val code = c.code
            if (code < 0x20 && c != '\n' && c != '\r' && c != '\t') bad++
            else if (code == 0xFFFD) bad++
        }
        return bad > s.length * 0.20
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

    private fun codePointsToStr(hex: String): String {
        val clean = hex.replace("\\s".toRegex(), "")
        if (clean.isEmpty()) return ""
        return try {
            val cps = mutableListOf<Int>()
            var i = 0
            while (i + 1 < clean.length) { cps.add(clean.substring(i, i + 2).toIntOrNull(16) ?: 0); i += 2 }
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

    private fun finish(sb: StringBuilder): String {
        val r = sb.toString()
        return if (r.length > MAX_OUTPUT) r.take(MAX_OUTPUT) else r
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
        System.arraycopy(b, off, buf, size, size); size += len
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
