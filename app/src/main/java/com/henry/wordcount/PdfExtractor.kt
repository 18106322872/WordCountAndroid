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

    /**
     * 从 PDF 文件提取全部可读文本。
     * 返回拼接的文本内容；如果文件无法解析或为纯图片 PDF 则返回空字符串。
     */
    fun extractText(file: File): String {
        val bytes = file.readBytes()
        return extractFromBytes(bytes)
    }

    internal fun extractFromBytes(bytes: ByteArray): String {
        // 验证 PDF 头
        if (bytes.size < 5 || !bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII).startsWith("%PDF")) {
            return ""
        }

        // 1. 构建对象偏移表（xref 或 xref stream）
        val offsets = buildOffsetMap(bytes)

        // 2. 遍历所有流对象，尝试提取文本
        val sb = StringBuilder()
        for ((objNum, offset) in offsets) {
            try {
                // 读取对象定义：N obj ... endobj
                val objStart = findObjectStart(bytes, offset) ?: continue
                val objEnd = findEndObj(bytes, objStart)
                if (objEnd < 0) continue

                val objBytes = bytes.copyOfRange(objStart, objEnd.coerceAtMost(bytes.size))

                // 检查是否包含 /FlateDecode 过滤器
                val hasFlate = objBytes.toString(Charsets.US_ASCII).contains("/Filter") &&
                        objBytes.toString(Charsets.US_ASCII).contains("FlateDecode")

                if (!hasFlate) {
                    // 无压缩流：直接在对象体中搜索文本操作符
                    val text = extractTextFromRaw(objBytes)
                    if (text.isNotBlank()) sb.append(text).append("\n")
                    continue
                }

                // 找到 stream...endstream 区间并解压
                val streamData = extractStreamBytes(objBytes)
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

    /** 构建 PDF 对象号 → 文件偏移量的映射 */
    private fun buildOffsetMap(bytes: ByteArray): Map<Int, Int> {
        val offsets = mutableMapOf<Int, Int>()

        // 方案 A：查找 xref 表（最常见）
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
        // 从末尾往前搜 startxref（通常在最后 ~1KB 内）
        val searchStart = maxOf(0, bytes.size - 4096)
        for (i in bytes.size - 1 downTo searchStart) {
            if (i + 9 < bytes.size &&
                bytes[i] == 's'.code.toByte() &&
                bytes.copyOfRange(i, i + 9).toString(Charsets.US_ASCII) == "startxref"
            ) {
                // 读取后面的数字（跳过空白）
                var pos = i + 9
                while (pos < bytes.size && bytes[pos].toInt().let { it == 10 || it == 13 || it == 32 }) pos++
                val numStart = pos
                while (pos < bytes.size && bytes[pos].toInt().let { it >= '0'.code && it <= '9'.code }) pos++
                return bytes.copyOfRange(numStart, pos).toString(Charsets.US_ASCII).trim().toIntOrNull() ?: -1
            }
        }
        return -1
    }

    private fun parseXrefTable(bytes: ByteArray, xrefPos: Int, offsets: MutableMap<Int, Int>) {
        // 跳过 "xref" 行
        var pos = xrefPos
        val tail = bytes.copyOfRange(pos, minOf(pos + 200, bytes.size)).toString(Charsets.US_ASCII)
        // 找到第一个子表的起始对象号
        val lineEnd = tail.indexOf('\n').takeIf { it > 0 } ?: tail.indexOf('\r').takeIf { it > 0 } ?: return
        pos += lineEnd + 1

        // 循环读取子表
        while (pos < bytes.size) {
            // 读子表头："start count\n"
            val header = readLine(bytes, pos) ?: break
            pos += header.first.length + 1 // 含换行
            val parts = header.first.trim().split(Regex("\\s+"))
            if (parts.size < 2) break
            val startObj = parts[0].toIntOrNull() ?: break
            val count = parts[1].toIntOrNull() ?: break

            // 读 count 个每行 20 字节的条目
            for (i in 0 until count) {
                if (pos + 20 > bytes.size) break
                val entry = bytes.copyOfRange(pos, pos + 20).toString(Charsets.US_ASCII)
                val entryParts = entry.trim().split(Regex("\\s+"))
                if (entryParts.size >= 3) {
                    // 类型：n=使用中, f=自由
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
        val pattern = Regex("(\\d+)\\s+0\\s+obj", setOf(RegexOption.MULTILINE))
        // 在原始字节中搜索（转为 string 可能丢失二进制信息，但 obj 声明是 ASCII）
        val text = bytes.toString(Charsets.ISO_8859_1)
        for (match in pattern.findAll(text)) {
            val objNum = match.groupValues[1].toIntOrNull() ?: continue
            offsets[objNum] = match.range.first
        }
    }

    // ── 流数据提取 ────────────────────────────────────────

    private fun findObjectStart(bytes: ByteArray, offset: Int): Int? {
        var pos = offset
        // 跳过前导空白
        while (pos < bytes.size && isWhitespace(bytes[pos])) pos++
        // 验证 "N 0 obj" 模式
        val chunk = bytes.copyOfRange(pos, minOf(pos + 50, bytes.size)).toString(Charsets.US_ASCII)
        if (!Regex("\\d+\\s+0\\s+obj").containsMatchIn(chunk)) return null
        return pos
    }

    private fun findEndObj(bytes: ByteArray, start: Int): Int {
        val tail = bytes.copyOfRange(start, minOf(start + (bytes.size - start), bytes.size))
        val str = tail.toString(Charsets.US_ASCII)
        val idx = str.indexOf("endobj")
        return if (idx >= 0) start + idx else -1
    }

    /** 从对象字节数组中提取 stream 和 endstream 之间的原始（可能压缩）数据 */
    private fun extractStreamBytes(objBytes: ByteArray): ByteArray? {
        val str = objBytes.toString(Charsets.US_ASCII)
        val streamIdx = str.indexOf("stream")
        if (streamIdx < 0) return null

        // stream 后面可能有 \r\n 或 \n
        var dataStart = streamIdx + 6
        while (dataStart < objBytes.size && (objBytes[dataStart].toInt() == 10 || objBytes[dataStart].toInt() == 13)) dataStart++

        val endStreamIdx = str.indexOf("endstream", dataStart)
        if (endStreamIdx <= dataStart) return null

        // endstream 前可能有 \r 或 \n
        var dataEnd = endStreamIdx
        while (dataEnd > dataStart && (objBytes[dataEnd - 1].toInt() == 10 || objBytes[dataEnd - 1].toInt() == 13)) dataEnd--

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

    /**
     * 从未压缩的 PDF 内容流中提取文本。
     * 匹配：
     *   - (hello world)Tj  → 字面字符串 + Tj 操作符
     *   - [(a)(b)(c)]TJ    → 字符串数组 + TJ 操作符
     *   - (text)           ← 孤立的字面字符串
     */
    internal fun extractTextFromDecompressed(data: ByteArray): String {
        val content = data.toString(Charsets.ISO_8859_1)
        val sb = StringBuilder()

        // 提取 (...)Tj 模式
        val tjPattern = Regex("\\(([^)]*)\\)\\s*Tj")
        for (m in tjPattern.findAll(content)) {
            val t = unescapePdfString(m.groupValues[1])
            if (t.isNotBlank()) sb.append(t).append(" ")
        }

        // 提取 [(...)...(...)]TJ 模式
        val tjBigPattern = Regex("\\[((?:[^]\\]]|\\][^)]*)*?)\\]\\s*TJ")
        for (m in tjBigPattern.findAll(content)) {
            val inner = m.groupValues[1]
            // 提取内部每个 (...)
            val innerPat = Regex("\\(([^)]*)\\)")
            for (im in innerPat.findAll(inner)) {
                val t = unescapePdfString(im.groupValues[1])
                if (t.isNotBlank()) sb.append(t)
            }
            sb.append(" ")
        }

        // 也提取独立的括号字符串（某些 PDF 不用 Tj 操作符）
        if (sb.isEmpty()) {
            val standalonePattern = Regex("\\(([^)]{2,})\\)")
            for (m in standalonePattern.findAll(content)) {
                val t = unescapePdfString(m.groupValues[1])
                if (t.isNotBlank() && !t.matches(Regex("[\\d\\s]+"))) sb.append(t).append(" ")
            }
        }

        return sb.toString()
    }

    /** 从原始（未解压）对象字节中提取文本（用于无 FlateDecode 的简单 PDF）*/
    private fun extractTextFromRaw(objBytes: ByteArray): String {
        val str = objBytes.toString(Charsets.ISO_8859_1)
        val sb = StringBuilder()
        // 直接搜索 (text)Tj
        val tjPattern = Regex("\\(([^)]*)\\)\\s*T[jJ]")
        for (m in tjPattern.findAll(str)) {
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
                    ')' -> sb.append(')')
                    '\\' -> sb.append('\\')
                    else -> {
                        // 八进制转义 \ddd
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

    private fun isWhitespace(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return if (v == 0x09 || v == 0x0A || v == 0x0D || v == 0x20) 1 else 0
    }

    /** 从指定位置开始读一行（到 \n 或 \r 或 EOF） */
    private fun readLine(bytes: ByteArray, start: Int): Pair<String, Int>? {
        if (start >= bytes.size) return null
        var end = start
        while (end < bytes.size && bytes[end].toInt() != 10 && bytes[end].toInt() != 13) end++
        return Pair(bytes.copyOfRange(start, end).toString(Charsets.US_ASCII), end)
    }
}
