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
 * v1.0.27 核心改进：
 *   - 修复 /Image 误判：旧版在 stream 字典区域简单搜索 "/Image" 关键字，
 *     导致 ProcSet[/PDF/Text/ImageB/ImageC/ImageI] 等非图片流被错误跳过。
 *     新版只匹配真正的 XObject 图片子类型（/Subtype/Image 或 /S/Image）。
 *   - 改进 stream 搜索正则：兼容 \n 和 \r\n 两种换行格式。
 *   - 加强 cleanExtractedText 过滤：更彻底地排除 PDF 结构性垃圾。
 *   - OCR 触发条件优化：提取文字极少时主动降级 OCR。
 *
 * v1.0.23 核心原则——绝对不卡死、绝对不返回 null：
 *   1) 文件 > 50MB → 快速返回空结果（手机端不适合处理超大 PDF）
 *   2) 内存读取上限 2MB（避免 OOM；文字内容通常在前几十 KB）
 *   3) 全程硬超时 5 秒，每步都检查剩余预算
 *   4) 最多处理 20 个 stream 块
 *   5) extract() 永远返回非 null PdfResult（即使文本为空）
 *   6) 任何异常都被捕获并降级为空结果，不会崩溃或卡死
 */
object PdfExtractor {

    data class PdfResult(val text: String, val pages: Int, val reliable: Boolean = true,
                          val diag: String = "")  // v1.3.66: 内部诊断信息

    /** 标记文本是否来自路径B（原始字节扫描）—— 路径B内容永远不可靠 */
    data class TextSource(val text: String, val fromRawScan: Boolean, val diag: String = "")

    /** 单个 PDF 文件大小上限（50MB） */
    private const val MAX_FILE_SIZE = 50 * 1024 * 1024

    /** 从文件读取的最大字节数（2MB——足够覆盖绝大多数 PDF 的文字层） */
    private const val MAX_READ_BYTES = 2 * 1024 * 1024

    /** 结构扫描/页数统计 只看前这么多字节 */
    private const val SCAN_CAP = 256 * 1024     // 256KB 足够覆盖页数树

    /** 全文提取的时间预算（毫秒）*/
    private const val TIME_BUDGET_MS = 5_000L

    /** 单个 stream 最大数据量 */
    private const val MAX_STREAM_DATA = 128 * 1024

    /** 总输出字符上限 */
    private const val MAX_OUTPUT = 100_000

    /** 最多处理的 stream 块数 */
    private const val MAX_STREAMS = 100  // v1.3.59: 20→100（多页 PDF 可能超过 20 个内容流）

    /**
     * 提取 PDF 文本。**永远不返回 null**，最坏情况返回 ("", 1)。
     * v1.3.66: 返回值含 diag 字段，携带内部诊断信息（用于 UI 显示）。
     */
    fun extract(file: File): PdfResult {
        val fileSize: Long = try { file.length() } catch (_: Throwable) { return PdfResult("", 1) }
        if (fileSize > MAX_FILE_SIZE || fileSize < 5) return PdfResult("", 1)

        val bytes: ByteArray = try {
            val toRead = min(fileSize.toInt(), MAX_READ_BYTES)
            val buf = ByteArray(toRead)
            val nRead = file.inputStream().use { it.read(buf) }
            if (nRead <= 0) return PdfResult("", 1)
            buf.copyOf(nRead)
        } catch (_: Throwable) {
            return PdfResult("", 1)
        }

        val header = try {
            String(bytes, 0, min(8, bytes.size), StandardCharsets.ISO_8859_1)
        } catch (_: Throwable) {
            return PdfResult("", 1)
        }
        if (!header.startsWith("%PDF") && !header.startsWith("%PDF-")) return PdfResult("", 1)

        val deadline = System.currentTimeMillis() + TIME_BUDGET_MS
        val diagSb = StringBuilder()
        return try {
            val pages = countPagesSafe(bytes, deadline)
            if (System.currentTimeMillis() > deadline) return PdfResult("", max(1, pages), false, "超时[页数=$pages]")

            val source = extractTextRobust(bytes, deadline, diagSb)
            val reliable = !source.fromRawScan && isTextReliable(source.text, bytes)
            val finalText = source.text.ifBlank { "" }

            val stats = quickStats(finalText)
            diagSb.insert(0, "流处理: ${source.diag}\n")
            diagSb.append("最终: ${stats.first}字(fe=${stats.second},可靠=$reliable)\n")
            if (source.fromRawScan) diagSb.append("⚠️ 使用路径B(原始扫描)\n")

            PdfResult(finalText, max(1, pages), reliable, diagSb.toString().trim())
        } catch (e: Throwable) {
            PdfResult("", 1, false, "异常:${e.message}")
        }
    }

    /**
     * v1.0.27: 判断提取的文本是否可靠。
     *
     * 不可靠的特征：
     *   - 文本含大量 PDF 结构关键词（obj, endobj, /Type, stream 等）
     *   - 文本看起来像是从 PDF 二进制数据中扫描出来的垃圾
     *   - 文本过长（>10000字符）但文件是图片型 PDF 的典型大小
     */
    private fun isTextReliable(text: String, _originalBytes: ByteArray): Boolean {
        if (text.isBlank()) return false  // 空文本 = 不可靠
        val lower = text.lowercase()
        // 检测 PDF 结构残留
        val structKeywords = listOf(" endobj", " xref", " trailer", " startxref", " stream", " endstream")
        var structCount = 0
        for (kw in structKeywords) {
            if (lower.contains(kw)) structCount++
        }
        // 如果文本中包含 >=2 个结构关键词，很可能是路径B垃圾
        if (structCount >= 2) return false
        // 检查是否有大量斜杠命令（PDF 字典语法）
        val slashOps = """/[a-z]+""".toRegex().findAll(lower).count()
        if (slashOps > 0 && slashOps > text.length / 20) return false
        // 含 obj/endobj 标记 → 路径B 垃圾
        if ("""(\d+\s+\d+\s+obj|endobj)""".toRegex().containsMatchIn(text)) return false
        return true
    }

    // ───────────────────────── 页数（安全版） ─────────────────────────
    /**
     * v1.0.29 改进：多种策略依次尝试，兼容线性化PDF、压缩对象流等非常规结构。
     *
     * 策略优先级：
     *   1) 直接计数 /Type/Page 叶节点（标准 PDF）
     *   2) 从 /Type/Pages 父节点的 /Count 字段读取（线性化/交叉引用流）
     *   3) 回退到 1
     */
    private fun countPagesSafe(bytes: ByteArray, deadline: Long): Int {
        return try {
            if (System.currentTimeMillis() > deadline) return 1
            val scanLen = min(bytes.size, SCAN_CAP)
            val s = String(bytes, 0, scanLen, StandardCharsets.ISO_8859_1)

            // 策略1：直接 /Type/Page（排除 /Pages）
            val leaf = """/Type\s*/\s*Page(?![sS])""".toRegex().findAll(s).count()
            if (leaf > 0) return leaf

            // 策略2：从 /Type/Pages 的 /Count N 提取
            val pagesCountMatch = """/Type\s*/\s*Pages[^>]*?/Count\s+(\d+)""".toRegex(RegexOption.DOT_MATCHES_ALL).find(s)
            if (pagesCountMatch != null) {
                val c = pagesCountMatch.groupValues[1].toIntOrNull()
                if (c != null && c > 0) return c
            }

            // 策略3：宽松匹配 /Count（在任意 Pages 对象上下文中）
            val countMatches = """/Count\s+(\d+)""".toRegex().findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }
            if (countMatches.any()) return countMatches.max()

            // 策略4：回退
            1
        } catch (_: Throwable) { 1 }
    }

    // ───────────────────────── 文本提取（鲁棒版） ─────────────────────────
    /**
     * @return TextSource(提取文本, 是否来自路径B原始扫描, 诊断信息)
     *         路径A=标准流解析(较可靠), 路径B=原始字节扫描(永远不可靠)
     */
    private fun extractTextRobust(bytes: ByteArray, deadline: Long, diagSb: StringBuilder): TextSource {
        val sb = StringBuilder()

        // 路径 A：标准流解析（带严格限制）
        try {
            if (System.currentTimeMillis() <= deadline) {
                // 一次性解析全文件 ToUnicode 映射（文件上限 2MB，安全）
                val toUnicode = if (System.currentTimeMillis() <= deadline) parseToUnicodeSafe(bytes, deadline) else emptyMap()
                diagSb.append("ToUnicode=${toUnicode.size}条; ")

                
                var textCount = 0
                var streamIdx = 0
                var totalStreams = 0
                var tjCount = 0
                var sampleHex = ""
                var cidTriggered = false
                var feBeforeCID = 0
                var feAfterCID = 0

                // v1.3.69: 累积所有流的 hex 原始字节——用于后续 CJK 编码 fallback
                val allHexBytes = ByteArrayOutputStreamSafe(8192)
                
                findStreamsSafe(bytes, deadline) { rawBytes, dictSlice ->
                    streamIdx++
                    totalStreams++
                    if (streamIdx > MAX_STREAMS) return@findStreamsSafe false
                    if (System.currentTimeMillis() > deadline) return@findStreamsSafe false

                    try {
                        val dictStr = String(dictSlice, StandardCharsets.ISO_8859_1)
                        if (isImageXObject(dictStr)) return@findStreamsSafe true

                        val data = tryDecompressSafe(rawBytes) ?: rawBytes
                        val probe = String(data, StandardCharsets.ISO_8859_1)
                        if (!probe.contains("Tj") && !probe.contains("TJ") && !probe.contains("BT"))
                            return@findStreamsSafe true

                        // 收集内容流样本（第一个含 Tj 的流的前300字符）
                        if (sampleHex.isEmpty() && probe.length > 20) {
                            sampleHex = probe.take(300)
                            // 统计 hex vs literal 比例
                            val hexMatches = """<[0-9A-Fa-f]{2,}>""".toRegex().findAll(probe).count()
                            val litMatches = """\([^)]{2,}\)""".toRegex().findAll(probe).count()
                            diagSb.append("样本hex=").append(hexMatches).append("个, 样本lit=").append(litMatches).append("个; ")
                        }

                        // v1.3.69: 累积此流中所有 hex 字符串的原始字节（用于 CJK fallback）
                        collectHexBytesFromStream(probe, allHexBytes)

                        // 先用标准模式解码
                        val text1 = decodeContentStream(data, toUnicode)
                        val s1 = quickStats(text1)
                        feBeforeCID += s1.second
                        
                        // v1.3.66: 再单独用 CID 模式解码对比
                        val textCid = decodeContentStreamInternal(
                            String(data, StandardCharsets.ISO_8859_1), toUnicode, true
                        )
                        val sCid = quickStats(textCid)
                        feAfterCID += sCid.second
                        if (sCid.second > s1.second) cidTriggered = true
                        
                        // 统计 Tj/TJ 操作符数量
                        val tjInStream = """Tj\b""".toRegex().findAll(probe).count()
                        val tjArrInStream = """TJ\b""".toRegex().findAll(probe).count()
                        tjCount += tjInStream + tjArrInStream
                        
                        // 用更好的结果
                        val text = if (sCid.second > s1.second) textCid else text1
                        if (text.isNotBlank()) { sb.append(text).append('\n'); textCount++ }
                        if (sb.length > MAX_OUTPUT) return@findStreamsSafe false
                    } catch (_: Throwable) { }
                    true
                }

                val cidStatus = if (cidTriggered) "生效" else "未胜出"
                diagSb.append("扫描${totalStreams}流(限${MAX_STREAMS}), 有文本${textCount}流, Tj=${tjCount}; ")
                diagSb.append("CID模式${cidStatus}(fe1B=$feBeforeCID feCID=$feAfterCID); ")
                if (sampleHex.isNotEmpty()) diagSb.append("流样本: ${sampleHex.take(200)}")

                // v1.3.72: 全流统一的 CJK 编码 fallback（带阈值保护）
                // 当 ToUnicode 为空时，对所有流累积的 hex 原始字节尝试 CJK 编码解码。
                // 注意：仅当某编码产生明显更多 FarEast 字符时才采用，
                // 否则 hex 字节可能是字体度量/坐标等非文本数据（v1.3.70 证实）。
                val rawHexBytes = allHexBytes.toBytes()
                if (toUnicode.isEmpty() && rawHexBytes.size >= 4) {
                    val currentFe = maxOf(feBeforeCID, feAfterCID)
                    val cjkDiag = StringBuilder()
                    val cjkResult = tryCjkEncodingsOnBytes(rawHexBytes, cjkDiag)
                    diagSb.append("; CJKfallback: ${cjkDiag}")

                    if (cjkResult.isNotEmpty()) {
                        val cjkStats = quickStats(cjkResult)
                        // v1.3.72: 恢复阈值——CJK 结果必须比当前多至少 5 个 fe 才采用
                        // v1.3.70 诊断证实：7266 字节用任何编码都 fe=0（非文本数据），
                        // 无阈值会导致垃圾数据(9957字/fe=1)污染结果
                        if (cjkStats.second >= max(currentFe + 5, 5)) {
                            diagSb.append(" [采用(fe${currentFe}→${cjkStats.second})]")
                            val merged = mergeWithCJKText(sb.toString(), cjkResult)
                            if (merged.isNotBlank()) {
                                val cleaned = cleanExtractedText(merged)
                                if (cleaned.isNotBlank()) return TextSource(cleaned, false, diagSb.toString())
                            }
                        } else {
                            diagSb.append(" [未采用:fe不足(${cjkStats.second}<${max(currentFe + 5, 5)})]")
                        }
                    }
                }

                if (textCount > 0 && System.currentTimeMillis() <= deadline) {
                    val cleaned = cleanExtractedText(sb.toString())
                    if (cleaned.isNotBlank()) return TextSource(cleaned, false, diagSb.toString())
                }
            }
        } catch (e: Throwable) { diagSb.append("路径A异常:${e.message} ") }

        // 路径 B：备用——直接从原始字节扫描可读字符串
        if (System.currentTimeMillis() > deadline) return TextSource(sb.toString(), false, diagSb.toString())
        diagSb.append("→路径B")
        return TextSource(extractRawReadableStrings(bytes, deadline), true, diagSb.toString())
    }

    /**
     * 加速版 stream 块搜索——用 indexOf 替代逐字节扫描。
     * 对每个找到的 stream 块调用 consumer(rawData, dictBeforeStream)。
     *
     * v1.0.27 改进：兼容 \n、\r\n、\r 等多种换行格式。
     */
    private inline fun findStreamsSafe(
        bytes: ByteArray,
        deadline: Long,
        consumer: (raw: ByteArray, dictBefore: ByteArray) -> Boolean
    ) {
        val kw = "stream".toByteArray(Charsets.US_ASCII)
        val endKw = "endstream".toByteArray(Charsets.US_ASCII)
        var pos = 0
        var iterations = 0
        val MAX_ITERATIONS = 5000  // 防止极端情况下的死循环

        while (pos <= bytes.size - kw.size - 2 && iterations < MAX_ITERATIONS) {
            iterations++
            if (System.currentTimeMillis() > deadline) return

            val idx = indexOf(bytes, kw, pos)
            if (idx < 0 || idx > bytes.size - kw.size - 2) break

            val afterKw = idx + kw.size
            // v1.0.27: 兼容多种换行格式 (\r\n | \n | \r)
            val dataStart = when {
                afterKw + 1 < bytes.size && bytes[afterKw] == '\r'.code.toByte()
                        && afterKw + 2 < bytes.size && bytes[afterKw + 1] == '\n'.code.toByte() -> afterKw + 2
                afterKw < bytes.size && bytes[afterKw] == '\n'.code.toByte() -> afterKw + 1
                afterKw < bytes.size && bytes[afterKw] == '\r'.code.toByte() -> afterKw + 1
                else -> { pos = idx + 1; continue }
            }

            val dictStart = max(0, idx - 200)  // 缩小字典窗口

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

    /** 安全版 parseToUnicode——扫描全文件（文件上限 2MB，安全） */
    private fun parseToUnicodeSafe(bytes: ByteArray, _deadline: Long): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        try {
            val s = String(bytes, StandardCharsets.ISO_8859_1)
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
                // v1.3.56: 增加 begincidchar / begincidrange 解析
                // 很多中文 PDF（尤其是 Word → PDF 转换的）使用 CID 映射而非 bfchar
                """(?s)begincidchar\s*(.*?)\s*endcidchar""".toRegex().findAll(cm).forEach { blk ->
                    """<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""".toRegex().findAll(blk.groupValues[1]).forEach { e ->
                        val src = e.groupValues[1].toIntOrNull(16) ?: return@forEach
                        val dst = codePointsToStr(e.groupValues[2])
                        if (dst.isNotEmpty()) map[src] = dst
                    }
                }
                """(?s)begincidrange\s*(.*?)\s*endcidrange""".toRegex().findAll(cm).forEach { blk ->
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
            // v1.0.28: 可打印范围是 0x20..0x7E。旧版写成 0x20..0x7F，与内层
            // 跳出条件 (c2 > 0x7E) 不一致：遇到 0x7F(DEL) 字节时外层进入可打印
            // 分支、内层立刻跳出、i=j=i 不前进，导致在 5s deadline 内空转。
            if (ch in 0x20..0x7E) {
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

    /** 对提取的文本做最终清洗
     *
     * v1.0.27 加强过滤：
     *   - 过滤含 PDF 结构标记的行（obj/endobj/xref/trailer 等）
     *   - 过滤含大量斜杠命令的行（PDF 字典语法残留）
     *   - 过滤过短的行（<=1 字符）
     *   - 要求行内必须含有字母（排除纯数字/符号垃圾）
     */
    private fun cleanExtractedText(text: String): String {
        return text.lines()
            .map { it.trim() }
            .filter { line ->
                if (line.length <= 1) return@filter false
                // 必须含至少一个字母（排除纯数字/符号行）
                if (!line.any { it.isLetter() }) return@filter false
                // v1.0.27: 排除 PDF 结构残留
                val lower = line.lowercase()
                if (lower.contains(" obj") || lower.contains("endobj")) return@filter false
                if (lower.contains("xref") || lower.contains("trailer") || lower.contains("startxref")) return@filter false
                if ("""[/]\w+""".toRegex().containsMatchIn(line) && !hasCjkOrHighByte(line)) {
                    // 大量 /命令 格式且无 CJK/高字节字母 → 可能是字典残留
                    val slashCount = """[/]""".toRegex().findAll(line).count()
                    if (slashCount >= 3) return@filter false
                }
                // 原有过滤
                if (isPdfStructuralGarbage(line)) return@filter false
                true
            }
            .joinToString("\n")
    }

    /**
     * v1.0.27 精确检测图片 XObject 流（修复旧版 /Image 误判 bug）。
     *
     * 只在以下情况判定为图片流：
     *   - /Subtype/Image（标准写法）
     *   - /S/Image（简写）
     * 这些必须出现在 XObject 声明上下文中（即字典里有 /Type/XObject 或前面有 /XObject）。
     *
     * 排除以下误判场景：
     *   - ProcSet[/PDF/Text/ImageB/ImageC/ImageI] — 页面级图形状态集合声明
     *   - 任何其他包含 "Image" 关键字但非 XObject 子类型的字典
     */
    private fun isImageXObject(dictStr: String): Boolean {
        // 快速检查：不含 Image 关键字直接返回 false
        if (!dictStr.contains("Image", ignoreCase = true)) return false
        // 精确匹配 XObject 图片子类型：/Subtype/Image 或 /S/Image。
        // 不再用 contains("/Image") 简单匹配，避免 ProcSet[/PDF/Text/ImageB/ImageC/ImageI]
        // 这类页面级资源声明被误判为图片流而跳过，导致纯文字 PDF 整体丢失。
        return """/Subtype\s*/\s*Image""".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(dictStr)
                || """/S\s*/\s*Image""".toRegex(RegexOption.IGNORE_CASE).containsMatchIn(dictStr)
    }

    /** v1.0.27: 检查字符串是否含 CJK 或高字节字符 */
    private fun hasCjkOrHighByte(s: String): Boolean {
        for (c in s) {
            if (c.code > 127) return true
        }
        return false
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
    private fun tryDecompressSafe(raw: ByteArray): ByteArray? {
        return try {
            val dict = ""  // 不再需要字典检查——调用方已做过滤
            // 只处理 FlateDecode（最常见的 PDF 流编码）
            val inf = Inflater()
            inf.setInput(raw)
            val out = ByteArrayOutputStreamSafe(min(raw.size * 3, 512 * 1024))
            val buf = ByteArray(4096)
            var iterations = 0
            while (!inf.finished() && iterations < 5000) {
                iterations++
                val n = inf.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size > MAX_OUTPUT) break
            }
            inf.end()
            out.toBytes()
        } catch (_: Throwable) { null }
    }

    private fun decodeContentStream(data: ByteArray, toUnicode: Map<Int, String>): String {
        val s = String(data, StandardCharsets.ISO_8859_1)

        // v1.3.65: 双模式解码——先尝试标准 1 字节模式；如果结果不含中文（fe=0）
        // 且字符数足够多，说明很可能是 Identity-H CID 编码的中文 PDF，
        // 自动切换到 2 字节大端 CID 模式重解。
        val text1Byte = decodeContentStreamInternal(s, toUnicode, false)

        // 判断是否需要 CID 重解：fe=0 且字符数>50（排除空内容/纯英文短文本）
        val stats1 = quickStats(text1Byte)
        val needsRetry = stats1.second == 0 && stats1.first > 50 && s.contains(Regex("<[0-9A-Fa-f]{4,}>"))

        return if (needsRetry) {
            val text2Byte = decodeContentStreamInternal(s, toUnicode, true)
            val stats2 = quickStats(text2Byte)
            // 选中文更多的结果（fe 更高 = 中文更多）
            if (stats2.second > stats1.second) text2Byte else text1Byte
        } else {
            text1Byte
        }
    }
    
    /** 快速统计 (chars, fe) 用于判断是否需要 CID 重解 */
    private fun quickStats(text: String): Pair<Int, Int> {
        var chars = 0; var fe = 0
        for (c in text) {
            if (!c.isWhitespace()) chars++
            if (isFarEast(c.code)) fe++
        }
        return Pair(chars, fe)
    }
    
    /** FarEast 区间判断（与 MainActivity.countTextKotlin 一致） */
    private fun isFarEast(code: Int): Boolean =
        code in 0x1100..0x11FF || code in 0x2000..0x206F ||
        code in 0x3000..0x303F || code in 0x3130..0x318F ||
        code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
        code in 0xA960..0xA97C || code in 0xAC00..0xD7A3
    
    private fun decodeContentStreamInternal(s: String, toUnicode: Map<Int, String>, cidMode: Boolean): String {
        val out = StringBuilder()
        val tjRe = """\(((?:[^()\\]|\\.)*)\)\s*Tj|<([0-9A-Fa-f\s]*)>\s*Tj""".toRegex()
        tjRe.findAll(s).forEach { m ->
            val txt = if (m.groupValues[1].isNotEmpty()) decodeLiteral(m.groupValues[1], toUnicode)
            else if (cidMode) decodeHexCID(m.groupValues[2], toUnicode)
            else decodeHex(m.groupValues[2], toUnicode)
            if (!looksGarbled(txt)) out.append(txt)
        }
        val tjArrRe = """\[\s*((?:(?:\((?:[^()\\]|\\.)*\)|<[0-9A-Fa-f\s]*>|-?\d+)\s*)*)\]\s*TJ""".toRegex()
        tjArrRe.findAll(s).forEach { m ->
            val inner = m.groupValues[1]
            val partRe = """\(((?:[^()\\]|\\.)*)\)|<([0-9A-Fa-f\s]*)>""".toRegex()
            partRe.findAll(inner).forEach { p ->
                val txt = if (p.groupValues[1].isNotEmpty()) decodeLiteral(p.groupValues[1], toUnicode)
                else if (cidMode) decodeHexCID(p.groupValues[2], toUnicode)
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

    /**
     * v1.3.65: CID 模式 hex 解码——用于 Identity-H 等 CID 编码的 PDF 字体。
     *
     * 与 decodeHex（每次读 2 hex = 1 字节）不同，这里每次读 4 hex = 2 字节大端 CID。
     * 中文 Word→PDF 转换文件通常用 Identity-H 编码，内容流中每个汉字是 2 字节的 CID，
     * 例如 <4E2D> = CID 0x4E2D → 通过 ToUnicode 或直接映射到 U+4E2D "中"。
     */
    private fun decodeHexCID(hex: String, toUnicode: Map<Int, String>): String {
        val clean = hex.replace("\\s".toRegex(), "")
        if (clean.length % 4 != 0) return ""  // CID 模式要求 4 的倍数长度
        val sb = StringBuilder()
        var i = 0
        while (i + 3 < clean.length) {
            // 大端读取 2 字节 = 1 个 CID
            val high = clean.substring(i, i + 2).toIntOrNull(16) ?: 0
            val low = clean.substring(i + 2, i + 4).toIntOrNull(16) ?: 0
            val cid = (high shl 8) or low
            sb.append(mapGlyph(cid, toUnicode))
            i += 4
        }
        return sb.toString()
    }

    /** 将 hex 字符串写入 ByteArray 输出流 */
    private fun writeHexBytes(out: ByteArrayOutputStreamSafe, hex: String) {
        val clean = hex.replace("\\s".toRegex(), "")
        var i = 0
        while (i + 1 < clean.length) {
            val b = clean.substring(i, i + 2).toIntOrNull(16) ?: 0
            out.write(b)
            i += 2
        }
    }

    /**
     * v1.3.69: 从单个内容流中收集所有 hex 字符串的原始字节到输出缓冲区。
     */
    private fun collectHexBytesFromStream(streamText: String, out: ByteArrayOutputStreamSafe) {
        // 匹配 Tj 的 hex 参数: <hex> Tj
        """(<[0-9A-Fa-f\s]+>\s*Tj)""".toRegex().findAll(streamText).forEach { m ->
            writeHexBytes(out, m.groupValues[1])
        }
        // 匹配 TJ 数组中的 hex 片段: [ ... <hex> ... ] TJ
        """(\[\s*.*?\]\s*TJ)""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(streamText).forEach { m ->
            """<([0-9A-Fa-f\s]+)>""".toRegex().findAll(m.groupValues[1]).forEach { h ->
                writeHexBytes(out, h.groupValues[1])
            }
        }
    }

    /**
     * v1.3.72: 对累积的 hex 原始字节尝试多种 CJK 编码解码。
     * 修复 v1.3.69 bug: bestFe 初始为 0，当所有编码的 fe 都为 0 时
     * bestResult 永远保持空串导致返回"无效"。
     *
     * @param diag 输出每种编码的诊断信息（编码名、fe、字数）
     * @return 解码后的文本（选取 fe 最高的编码），空串仅当 rawBytes 为空
     */
    private fun tryCjkEncodingsOnBytes(rawBytes: ByteArray, diag: StringBuilder): String {
        if (rawBytes.isEmpty()) return ""

        val encodings = listOf("GBK", "GB18030", "Big5", "EUC-TW", "Shift_JIS", "EUC-KR", "UTF-8")
        var bestResult = ""
        var bestFe = -1   // v1.3.70: 改为 -1，确保第一个结果总能被记录
        var bestEnc = ""
        val details = StringBuilder()  // 记录每种编码的详细结果

        for (enc in encodings) {
            try {
                val charset = if (enc == "UTF-8") StandardCharsets.UTF_8 else Charset.forName(enc)
                val decoded = String(rawBytes, charset)
                val stats = quickStats(decoded)
                // v1.3.70: >= 而非 >，确保第一个结果（即使 fe=0）也被记录
                if (stats.second >= bestFe) {
                    bestFe = stats.second
                    bestResult = decoded
                    bestEnc = enc
                }
                // 记录每种编码的诊断
                if (details.isNotEmpty()) details.append(" ")
                details.append("${enc}(fe=${stats.second},字=${stats.first})")
            } catch (_: Throwable) {
                if (details.isNotEmpty()) details.append(" ")
                details.append("${enc}(异常)")
            }
        }

        diag.append(details.toString())
        return bestResult
    }

    /**
     * v1.3.69: 将原有文本与 CJK 解码文本合并。
     */
    private fun mergeWithCJKText(original: String, cjkText: String): String {
        if (cjkText.isBlank()) return original
        if (original.isBlank()) return cjkText
        return original + "\n" + cjkText
    }

    private fun mapGlyph(code: Int, toUnicode: Map<Int, String>): String {
        toUnicode[code]?.let { return it }
        // v1.3.56: CID 编码 fallback 修复
        // 中文 PDF 常用 Identity-H 编码，此时内容流中的 hex 值直接就是 Unicode 码点
        // （如 <4E2D> = U+4E2D = "中"）。旧代码用 code.toByte() 截断到 8 位
        // 导致 0x4E2D → 0x2D("-")，所有中文全部丢失。
        // 新逻辑：多字节码点(>=0x100) 尝试直接当 Unicode；单字节才走 cp1252 fallback
        return if (code >= 0x100) {
            try { String(intArrayOf(code), 0, 1) } catch (_: Throwable) { "\uFFFD" }
        } else {
            try { String(byteArrayOf(code.toByte()), cp1252()) } catch (_: Throwable) { "\uFFFD" }
        }
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
