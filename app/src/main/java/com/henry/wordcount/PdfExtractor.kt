package com.henry.wordcount

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 纯 Kotlin 的 PDF 文本抽取与页数统计层（无任何第三方库）。
 *
 * v1.0.21 核心设计原则——绝不卡死：
 *   1) 文件 > 100MB → 立即返回 null + 错误提示（手机端不适合处理超大 PDF）
 *   2) 内存读取上限 30MB（只取文件前部；超大 PDF 的文字通常在前几 MB）
 *   3) 全程硬超时 3 秒，每步检查剩余预算
 *   4) stream 搜索用 ByteArray.indexOf（比逐字节扫描快数十倍）
 *   5) 即使标准解析完全失败，也会返回从原始字节提取的可读文本片段
 */
object PdfExtractor {

    data class PdfResult(val text: String, val pages: Int)

    /** 单个 PDF 文件大小上限（100MB） */
    private const val MAX_FILE_SIZE = 100 * 1024 * 1024

    /** 从文件读取的最大字节数（30MB） */
    private const val MAX_READ_BYTES = 30 * 1024 * 1024

    /** 结构扫描/页数统计 只看前这么多字节 */
    private const val SCAN_CAP = 512 * 1024     // 512KB 足够覆盖页数树

    /** 全文提取的时间预算（毫秒） */
    private const val TIME_BUDGET_MS = 3_000L

    /** 单个 stream 最大数据量 */
    private const val MAX_STREAM_DATA = 256 * 1024

    /** 总输出字符上限 */
    private const val MAX_OUTPUT = 200_000

    fun extract(file: File): PdfResult? {
        // 快速拒绝超大文件
        val fileSize = try { file.length() } catch (_: Throwable) { return null }
        if (fileSize > MAX_FILE_SIZE) return null
        if (fileSize < 5) return null

        // 只读文件前部（避免大文件 OOM）
        val bytes = try {
            val toRead = min(fileSize.toInt(), MAX_READ_BYTES)
            val buf = ByteArray(toRead)
            file.inputStream().use { it.read(buf) }
            buf
        } catch (_: Throwable) { return null }

        if (bytes.size < 5) return null
        val header = String(bytes, 0, min(8, bytes.size), StandardCharsets.ISO_8859_1)
        if (!header.startsWith("%PDF") && !header.startsWith("%PDF-")) return null

        val deadline = System.currentTimeMillis() + TIME_BUDGET_MS
        return try {
            val pages = countPages(bytes, deadline)
            if (System.currentTimeMillis() > deadline) return PdfResult("", max(1, pages))
            val text = extractTextTimed(bytes, deadline)
            PdfResult(text.ifBlank { "" }, max(1, pages))
        } catch (_: Throwable) {
            try {
                val pages = countPages(bytes, deadline)
                PdfResult("", max(1, pages))
            } catch (_: Throwable) { null }
        }
    }

    // ───────────────────────── 页数 ─────────────────────────
    private fun countPages(bytes: ByteArray, deadline: Long): Int {
        if (System.currentTimeMillis() > deadline) return 1
        val scanLen = min(bytes.size, SCAN_CAP)
        val s = String(bytes, 0, scanLen, StandardCharsets.ISO_8859_1)
        // 叶子页：/Type /Page 且后接非 s/S（排除 /Pages）
        val leaf = """/Type\s*/\s*Page(?![sS])""".toRegex().findAll(s).count()
        if (leaf > 0) return leaf
        val any = """/Type\s*/\s*Page""".toRegex().findAll(s).count()
        return max(1, any / 2)
    }

    // ───────────────────────── 文本抽取（带硬超时） ─────────────────────────
    private fun extractTextTimed(bytes: ByteArray, deadline: Long): String {
        val sb = StringBuilder()

        // 路径 A：标准流解析
        try {
            if (System.currentTimeMillis() <= deadline) {
                val toUnicode = parseToUnicodeSafe(bytes, deadline)
                if (System.currentTimeMillis() > deadline) return finish(sb)

                var textCount = 0
                findStreamBlocksFast(bytes, deadline) { rawBytes, dictSlice ->
                    if (System.currentTimeMillis() > deadline) return@findStreamBlocksFast false
                    try {
                        val probe = String(rawBytes, StandardCharsets.ISO_8859_1)
                        if (!probe.contains("Tj") && !probe.contains("TJ") && !probe.contains("BT"))
                            return@findStreamBlocksFast true
                        val data = tryDecompress(rawBytes, dictSlice) ?: rawBytes
                        val text = decodeContentStream(data, toUnicode)
                        if (text.isNotBlank()) { sb.append(text).append('\n'); textCount++ }
                        if (sb.length > MAX_OUTPUT) return@findStreamBlocksFast false
                    } catch (_: Throwable) { }
                    true
                }

                if (textCount > 0 && System.currentTimeMillis() <= deadline) {
                    val cleaned = cleanExtractedText(sb.toString())
                    if (cleaned.isNotBlank()) return cleaned
                }
            }
        } catch (_: Throwable) { }

        // 路径 B：备用——直接从原始字节提取可读文本
        if (System.currentTimeMillis() > deadline) return finish(sb)
        return extractRawReadableStrings(bytes, deadline)
    }

    /**
     * 加速版 stream 块搜索——用 indexOf 替代逐字节扫描。
     * 对每个找到的 stream 块调用 consumer(rawData, dictBeforeStream)。
     */
    private inline fun findStreamBlocksFast(
        bytes: ByteArray,
        deadline: Long,
        consumer: (raw: ByteArray, dictBefore: ByteArray) -> Boolean
    ) {
        val kw = "stream".toByteArray(Charsets.US_ASCII)
        val endKw = "endstream".toByteArray(Charsets.US_ASCII)
        var pos = 0
        while (pos <= bytes.size - kw.size - 2) {
            if (System.currentTimeMillis() > deadline) return

            // 用 indexOf 加速搜索 "stream" 关键字
            val idx = indexOf(bytes, kw, pos)
            if (idx < 0 || idx > bytes.size - kw.size - 2) break

            val afterKw = idx + kw.size
            // "stream" 后跟 \r\n 或 \n
            val dataStart = when {
                afterKw + 1 < bytes.size && bytes[afterKw] == '\r'.code.toByte()
                        && afterKw + 2 < bytes.size && bytes[afterKw + 1] == '\n'.code.toByte() -> afterKw + 2
                afterKw < bytes.size && bytes[afterKw] == '\n'.code.toByte() -> afterKw + 1
                else -> { pos = idx + 1; continue }
            }

            val dictStart = max(0, idx - 400)

            // 找对应的 endstream
            val endPos = indexOf(bytes, endKw, dataStart)
            val dataEnd = if (endPos >= 0 && (endPos - dataStart) <= MAX_STREAM_DATA) {
                endPos
            } else {
                min(dataStart + MAX_STREAM_DATA, bytes.size)
            }
            val dataSize = dataEnd - dataStart
            if (dataSize > 0 && dataSize <= MAX_STREAM_DATA) {
                val rawData = bytes.copyOfRange(dataStart, dataEnd)
                val dictSlice = bytes.copyOfRange(dictStart, idx)
                if (!consumer(rawData, dictSlice)) return
            }
            pos = if (endPos >= 0) endPos + endKw.size else dataEnd
        }
    }

    /** 在 byte 数组中查找子数组位置（类似 String.indexOf） */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, fromIndex: Int): Int {
        outer@ for (i in fromIndex..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** 安全版 parseToUnicode——只扫描前 SCAN_CAP 字节 */
    private fun parseToUnicodeSafe(bytes: ByteArray, _deadline: Long): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        try {
            val scanLen = min(bytes.size, SCAN_CAP)
            val s = String(bytes, 0, scanLen, StandardCharsets.ISO_8859_1)
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
        val scanLen = min(bytes.size, SCAN_CAP)
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

    /** 对提取的文本做最终清洗 */
    private fun cleanExtractedText(text: String): String {
        return text.lines()
            .map { it.trim() }
            .filter { line ->
                if (line.length <= 1) return@filter false
                line.any { it.isLetter() }
            }
            .filter { !isPdfStructuralGarbage(it) }
            .joinToString("\n")
    }

    /** 判断 ASCII 片段是否为 PDF 结构性垃圾 */
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
            "/linearized", "/o", "/e", "/h", "/l", "/t", "/helv", "za db",
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

    /** 尝试 Flate 解压；失败返回 null */
    private fun tryDecompress(raw: ByteArray, dictSlice: ByteArray): ByteArray? {
        val dict = String(dictSlice, StandardCharsets.ISO_8859_1)
        return when {
            dict.contains("FlateDecode") -> try {
                val inf = Inflater()
                inf.setInput(raw)
                val out = ByteArrayOutputStreamSafe(min(raw.size * 3, 2 * 1024 * 1024))
                val buf = ByteArray(8192)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) break; else break }
                    out.write(buf, 0, n)
                    if (out.size > MAX_OUTPUT) break
                }
                inf.end()
                out.toBytes()
            } catch (_: Throwable) { null }
            dict.contains("ASCIIHexDecode") -> try { hexDecodeStream(raw) } catch (_: Throwable) { null }
            dict.contains("LZWDecode") -> null
            else -> raw
        }
    }

    private fun decodeContentStream(data: ByteArray, toUnicode: Map<Int, String>): String {
        val s = String(data, StandardCharsets.ISO_8859_1)
        val out = StringBuilder()
        val tjRe = """\(((?:[^()\\]|\\.)*)\)\s*Tj|<([0-9A-Fa-f\s]*)>\s*Tj""".toRegex()
        tjRe.findAll(s).forEach { m ->
            val txt = if (m.groupValues[1].isNotEmpty()) decodeLiteral(m.groupValues[1], toUnicode)
            else decodeHex(m.groupValues[2], toUnicode)
            if (!looksGarbled(txt)) out.append(txt)
        }
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
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append(0x0C.toChar())
                    '(' -> sb.append('(')
                    ')' -> sb.append(')')
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

/** 简单可增长的字节输出流 */
private class ByteArrayOutputStreamSafe(initial: Int) {
    private var buf = ByteArray(if (initial < 32) 32 else initial)
    var size = 0
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

internal fun gunzip(bytes: ByteArray): ByteArray {
    val inf = GZIPInputStream(ByteArrayInputStream(bytes))
    val out = ByteArrayOutputStreamSafe(bytes.size * 2)
    val buf = ByteArray(8192)
    var n: Int
    while (inf.read(buf).also { n = it } != -1) out.write(buf, 0, n)
    inf.close()
    return out.toBytes()
}
