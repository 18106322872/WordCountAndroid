package com.henry.wordcount

import java.io.File
import java.util.zip.Inflater
import java.nio.charset.Charset

/**
 * 纯 Kotlin PDF 文本提取引擎（不依赖 PdfBox / iText，无 java.awt 依赖）。
 *
 * 实现策略：逐字节扫描 PDF 原始结构，找到文本流对象后：
 *   1. 解析交叉引用表定位各对象偏移量
 *   2. 对 FlateDecode 流解压
 *   3. 用正则从解压内容中提取 Tj/TJ 操作符和 (...) 字面字符串中的文本
 *
 * 这是一个**极简实现**，覆盖绝大多数常见 PDF 文件（文字型 PDF）。
 * 对于扫描件/图片 PDF 仅返回空字符串（由调用方处理）。
 */
object PdfExtractor {

    fun extractText(file: File): String {
        val bytes = file.readBytes()
        return extractFromBytes(bytes)
    }

    internal fun extractFromBytes(bytes: ByteArray): String {
        // 验证 PDF 头
        if (bytes.size < 5 || !String(bytes.copyOfRange(0, 5), Charsets.US_ASCII).startsWith("%PDF")) {
            return ""
        }

        // 1. 构建对象偏移表（xref 或 xref stream）
        val offsets = buildOffsetMap(bytes)

        // 2. 遍历所有流对象，尝试提取文本
        val sb = StringBuilder()
        for ((objNum, offset) in offsets) {
            try {
                val objStart = findObjectStart(bytes, offset) ?: continue
                val objEnd = findEndObj(bytes, objStart)
                if (objEnd < 0) continue

                val objBytes = bytes.copyOfRange(objStart, objEnd.coerceAtMost(bytes.size))
                val rawStr = String(objBytes, Charsets.US_ASCII)

                // 检查是否包含 /FlateDecode 过滤器
                val hasFlate = rawStr.contains("/Filter") && rawStr.contains("FlateDecode")

                if (!hasFlate) {
                    val text = extractTextFromRaw(rawStr)
                    if (text.isNotBlank()) sb.append(text).append("\n")
                    continue
                }

                // 找到 stream...endstream 区间并解压
                val streamData = extractStreamBytes(rawStr, objBytes)
                if (streamData != null) {
                    val decompressed = inflate(streamData)
                    if (decompressed != null) {
                        val text = extractTextFromDecompressed(decompressed)
                        if (text.isNotBlank()) sb.append(text).append("\n")
                    }
                }
            } catch (_: Exception) { /* 跳过无法解析的对象 */ }
        }

        return sb.toString()
    }

    // ── 对象偏移表构建 ────────────────────────────────────
    private fun buildOffsetMap(bytes: ByteArray): Map<Int, Int> {
        val offsets = mutableMapOf<Int, Int>()

        // 方案 A：查找 xref 表
        val xrefPos = findXrefPosition(bytes)
        if (xrefPos > 0) {
            parseXrefTable(bytes, xrefPos, offsets)
            if (offsets.isNotEmpty()) return offsets
        }

        // 方案 B：线性扫描所有 "N 0 obj" 出现位置
        linearScanObjects(bytes, offsets)

        return offsets
    }

    /** 从文件尾部向前找 startxref 关键字的位置 */
    private fun findXrefPosition(bytes: ByteArray): Int {
        val searchStart = maxOf(0, bytes.size - 4096)
        for (i in bytes.size - 1 downTo searchStart) {
            if (i + 9 < bytes.size &&
                bytes[i] == 's'.code.toByte() &&
                String(bytes.copyOfRange(i, i + 9), Charsets.US_ASCII) == "startxref"
            ) {
                var pos = i + 9
                while (pos < bytes.size && isWhitespaceByte(bytes[pos])) pos++
                val numStart = pos
                while (pos < bytes.size && isDigitByte(bytes[pos])) pos++
                return String(bytes.copyOfRange(numStart, pos), Charsets.US_ASCII).trim().toIntOrNull() ?: -1
            }
        }
        return -1
    }

    private fun parseXrefTable(bytes: ByteArray, xrefPos: Int, offsets: MutableMap<Int, Int>) {
        var pos = xrefPos
        val tail = String(bytes.copyOfRange(pos, minOf(pos + 200, bytes.size)), Charsets.US_ASCII)
        val lineEnd = tail.indexOf('\n').takeIf { it > 0 } ?: tail.indexOf('\r').takeIf { it > 0 } ?: return
        pos += lineEnd + 1

        while (pos < bytes.size) {
            val header = readLine(bytes, pos) ?: break
            pos += header.first.length + 1
            val parts = header.first.trim().split(Regex("\\s+"))
            if (parts.size < 2) break
            val startObj = parts[0].toIntOrNull() ?: break
            val count = parts[1].toIntOrNull() ?: break

            for (i in 0 until count) {
                if (pos + 20 > bytes.size) break
                val entry = String(bytes.copyOfRange(pos, pos + 20), Charsets.US_ASCII)
                val entryParts = entry.trim().split(Regex("\\s+"))
                if (entryParts.size >= 3) {
                    if (entryParts.getOrNull(2)?.startsWith("n") == true) {
                        val off = entryParts.getOrNull(0)?.toLongOrNull()
                        if (off != null && off > 0) {
                            offsets[startObj + i] = off.toInt()
                        }
                    }
                }
                pos += 20
            }
        }
    }

    /** 线性扫描：找所有 N 0 obj 模式 */
    private fun linearScanObjects(bytes: ByteArray, offsets: MutableMap<Int, Int>) {
        val text = String(bytes, Charsets.ISO_8859_1)
        val pattern = Regex("(\\d+)\\s+0\\s*obj", setOf(RegexOption.MULTILINE))
        for (match in pattern.findAll(text)) {
            val objNum = match.groupValues[1].toIntOrNull() ?: continue
            offsets[objNum] = match.range.first
        }
    }

    // ── 流数据提取 ────────────────────────────────────────
    private fun findObjectStart(bytes: ByteArray, offset: Int): Int? {
        var pos = offset
        while (pos < bytes.size && isWhitespaceByte(bytes[pos])) pos++
        val chunk = String(bytes.copyOfRange(pos, minOf(pos + 50, bytes.size)), Charsets.US_ASCII)
        if (!Regex("\\d+\\s+0\\s*obj").containsMatchIn(chunk)) return null
        return pos
    }

    private fun findEndObj(bytes: ByteArray, start: Int): Int {
        val tail = bytes.copyOfRange(start, minOf(start + (bytes.size - start), bytes.size))
        val str = String(tail, Charsets.US_ASCII)
        val idx = str.indexOf("endobj")
        return if (idx >= 0) start + idx else -1
    }

    /** 从原始字节数组和对应的ASCII字符串中提取 stream 数据 */
    private fun extractStreamBytes(rawStr: String, objBytes: ByteArray): ByteArray? {
        val streamIdx = rawStr.indexOf("stream")
        if (streamIdx < 0) return null

        var dataStart = streamIdx + 6
        while (dataStart < objBytes.size && isNewlineByte(objBytes[dataStart])) dataStart++

        val endStreamIdx = rawStr.indexOf("endstream", dataStart)
        if (endStreamIdx <= dataStart) return null

        var dataEnd = endStreamIdx
        while (dataEnd > dataStart && isNewlineByte(objBytes[dataEnd - 1])) dataEnd--

        if (dataEnd <= dataStart) return null
        return objBytes.copyOfRange(dataStart, dataEnd)
    }

    private fun inflate(compressed: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater(true)
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count <= 0) break
                out.write(buf, 0, count)
            }
            out.toByteArray()
        } catch (_: Exception) { null }
    }

    // ── 文本提取（从 PDF 操作符和字面串）──────────────────
    internal fun extractTextFromDecompressed(data: ByteArray): String {
        val content = String(data, Charsets.ISO_8859_1)
        val sb = StringBuilder()

        // 提取 (...)Tj 模式
        val tjPattern = Regex("\\(([^)]*)\\)\\s*T[jJ]")
        for (m in tjPattern.findAll(content)) {
            val t = unescapePdfString(m.groupValues[1])
            if (t.isNotBlank()) sb.append(t).append(" ")
        }

        // 也提取独立的括号字符串
        if (sb.isEmpty()) {
            val standalonePattern = Regex("\\(([^)]{3,})\\)")
            for (m in standalonePattern.findAll(content)) {
                val t = unescapePdfString(m.groupValues[1])
                if (t.isNotBlank() && !t.matches(Regex("[\\d\\s]+"))) sb.append(t).append(" ")
            }
        }

        return sb.toString()
    }

    /** 从原始对象字符串中提取文本 */
    private fun extractTextFromRaw(rawStr: String): String {
        val sb = StringBuilder()
        val tjPattern = Regex("\\(([^)]*)\\)\\s*T[jJ]")
        for (m in tjPattern.findAll(rawStr)) {
            val t = unescapePdfString(m.groupValues[1])
            if (t.isNotBlank()) sb.append(t).append(" ")
        }
        return sb.toString()
    }

    /** 反转义 PDF 字面字符串中的转义序列 */
    private fun unescapePdfString(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    '(' -> sb.append('(')
                    ')' -> sb.append(')'
                    '\\' -> sb.append('\\')
                    else -> {
                        if (next in '0'..'9') {
                            val oct = s.substring(i + 1, minOf(i + 4, s.length)).takeWhile { it in '0'..'7' }
                            if (oct.isNotEmpty()) {
                                sb.append(oct.toInt(8).toChar())
                                i += oct.length
                            } else {
                                sb.append(next)
                            }
                        } else {
                            sb.append(next)
                        }
                    }
                }
                i += 2
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    // ── 工具方法 ──────────────────────────────────────────
    private fun isWhitespaceByte(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v == 0x09 || v == 0x0A || v == 0x0D || v == 0x20
    }

    private fun isDigitByte(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v in '0'.code..'9'.code
    }

    private fun isNewlineByte(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v == 0x0A || v == 0x0D
    }

    /** 从指定位置开始读一行 */
    private fun readLine(bytes: ByteArray, start: Int): Pair<String, Int>? {
        if (start >= bytes.size) return null
        var end = start
        while (end < bytes.size && !isNewlineByte(bytes[end])) end++
        return Pair(String(bytes.copyOfRange(start, end), Charsets.US_ASCII), end)
    }
}
