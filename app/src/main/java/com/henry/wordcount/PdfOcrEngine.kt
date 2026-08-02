package com.henry.wordcount

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shockwave.pdfium.PdfiumCore
import com.shockwave.pdfium.util.Size
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * PDF OCR 兜底引擎（用于图片型/扫描件 PDF）。
 *
 * v1.0.41 重构：
 *   - 移除 PyMuPDF(fitz) 路径（Chaqoupy 下报 FileNotFoundError: AssetFinder/scripts）
 *   - 文字型 PDF 的文本提取已由 MainActivity 中的 Python pdfminer 主导处理
 *   - 本引擎专注于：当 pdfminer 也提取不到文字时（纯图/扫描件），用渲染+OCR 兜底
 *
 * 渲染链路（按优先级）：
 *  1) 系统 PdfRenderer → ML Kit OCR
 *  2) PdfiumAndroid PFD 模式 → ML Kit OCR（支持 JPX/JBIG2）
 *  3) PdfiumAndroid ByteArray 模式 → ML Kit OCR
 *  4) 内嵌图片提取（多策略）→ ML Kit OCR
 */
object PdfOcrEngine {

    private const val MAX_PAGES = 40
    private const val MAX_DIM = 3072  // v1.0.41: 提高到 3K 以改善 OCR 识别率（原 2048）

    data class PdfOcrResult(val text: String, val pages: Int)

    enum class FailReason {
        OK,
        OCR_DISABLED,
        RENDER_BLANK,
        RENDER_FAILED,
        RENDER_PARTIAL,
        PDFIUM_UNAVAILABLE,
        PDFIUM_BLANK,
        PDFIUM_FAILED,
        OCR_EMPTY,
        NO_EMBEDDED_IMAGES
    }

    @Volatile var lastFailReason: FailReason = FailReason.OK
        private set

    /** 上一次失败的具体异常信息 */
    @Volatile var lastFailDetail: String = ""
        private set

    /** 上一次 OCR 尝试的详细过程诊断（v1.3.82：供 MainActivity 展示给用户） */
    @Volatile var lastDiag: String = ""
        private set

    /**
     * 提取 PDF 文本（渲染+OCR）。
     * @param forPrintMode v1.3.81: 为"文字型但Kotlin无法解码"的PDF使用更高渲染质量（PRINT模式+2x分辨率），
     *        提升中文 OCR 识别率。默认 false（扫描件/图片型用普通 DISPLAY 模式即可）。
     */
    fun extractText(context: Context, file: File, forPrintMode: Boolean = false): PdfOcrResult? {
        lastFailReason = FailReason.OK
        lastFailDetail = ""
        lastDiag = ""
        if (!OcrEngine.ocrEnabled) {
            Log.w("WordCount", "PdfOcr 跳过: ocrEnabled=false")
            lastFailReason = FailReason.OCR_DISABLED
            lastDiag = "OCR已禁用(ocrEnabled=false)"
            return null
        }
        Log.d("WordCount", "PdfOcr 开始: ${file.name} (${file.length()} bytes) printMode=$forPrintMode")

        // 1) 系统 PdfRenderer
        val sys = renderWithSystem(file, forPrintMode)
        if (sys != null) return sys

        // 2) PdfiumAndroid（PFD + ByteArray 双模式）
        val pdfium = renderWithPdfium(context, file, forPrintMode)
        if (pdfium != null) return pdfium

        // 3) 内嵌图片提取（多策略）
        Log.d("WordCount", "PdfOcr 尝试路径3(内嵌图片提取): ${file.name}")
        val images = extractEmbeddedImages(file)
        if (images.isNotEmpty()) {
            val sb = StringBuilder()
            var anyText = false
            for ((idx, bmp) in images.withIndex()) {
                try {
                    val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                    if (t.isNotBlank()) { sb.append(t).append('\n'); anyText = true }
                    Log.d("WordCount", "PdfOcr(内嵌图${idx+1}) OCR: ${t.length}字")
                } catch (_: Throwable) {}
                finally { bmp.recycle() }
            }
            if (anyText) {
                Log.d("WordCount", "PdfOcr(内嵌图片) 成功: ${images.size}张图")
                return PdfOcrResult(sb.toString().trim(), images.size)
            }
            lastFailReason = FailReason.OCR_EMPTY
        } else {
            lastFailReason = FailReason.NO_EMBEDDED_IMAGES
        }

        // 汇总最终诊断
        if (lastDiag.isEmpty()) {
            lastDiag = "全部路径失败: reason=${lastFailReason.name}"
            if (lastFailDetail.isNotEmpty()) lastDiag += " detail=$lastFailDetail"
        }
        Log.w("WordCount", "PdfOcr 全部路径失败: ${file.name}, $lastDiag")
        return null
    }

    // ══════════════════ 1) 系统 PdfRenderer ══════════════════

    private fun renderWithSystem(file: File, forPrintMode: Boolean = false): PdfOcrResult? {
        val diag = StringBuilder()
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Throwable) {
            lastFailReason = FailReason.RENDER_FAILED
            lastFailDetail = "PFD打开失败: ${e.javaClass.simpleName}"
            lastDiag = "SysRenderer: $lastFailDetail"
            return null
        }
        val renderer = try { PdfRenderer(pfd) } catch (e: Throwable) {
            runCatching { pfd.close() }
            lastFailReason = FailReason.RENDER_FAILED
            lastFailDetail = "PdfRenderer创建失败: ${e.javaClass.simpleName}"
            lastDiag = "SysRenderer: $lastFailDetail"
            return null
        }
        var result: PdfOcrResult? = null
        try {
            val pageCount = renderer.pageCount
            val limit = min(pageCount, MAX_PAGES)
            val sb = StringBuilder()
            var anyRenderedContent = false; var anyOcrText = false; var pageErrors = 0
            var blankCount = 0; var ocrEmptyCount = 0

            diag.append("SysRenderer: ${pageCount}页(forPrint=$forPrintMode)")

            for (i in 0 until limit) {
                try {
                    val page = renderer.openPage(i)
                    try {
                        val w = page.width; val h = page.height
                        if (w <= 0 || h <= 0) continue
                        // v1.3.81: PRINT模式用2x分辨率+PRINT渲染，提升中文OCR识别率
                        val baseScale = computeScale(w, h)
                        val scale = if (forPrintMode) baseScale * 2f else baseScale
                        val bw = max(1, (w * scale).toInt()); val bh = max(1, (h * scale).toInt())
                        val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { pageErrors++; continue }
                        try {
                            val renderMode = if (forPrintMode) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            page.render(bmp, null, null, renderMode)
                            if (isBlankBitmap(bmp)) { blankCount++; continue }
                            anyRenderedContent = true
                            val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                            if (t.isNotBlank()) { sb.append(t).append('\n'); anyOcrText = true; diag.append(" p${i+1}:${t.length}字") }
                            else { ocrEmptyCount++ }
                        } catch (_: Throwable) { pageErrors++ } finally { bmp.recycle() }
                    } finally { page.close() }
                } catch (_: Throwable) { pageErrors++ }
            }

            diag.append(" | 渲染:${if (anyRenderedContent) "有内容" else "全空白"} 空白:$blankCount OCR空:$ocrEmptyCount 错误:$pageErrors")

            val text = sb.toString().trim()
            if (text.isNotBlank()) result = PdfOcrResult(text, pageCount)
            else if (anyRenderedContent) lastFailReason = FailReason.OCR_EMPTY
            else if (pageErrors > 0) lastFailReason = FailReason.RENDER_PARTIAL
            else lastFailReason = FailReason.RENDER_BLANK
        } catch (e: Throwable) {
            lastFailReason = FailReason.RENDER_FAILED
            lastFailDetail = e.javaClass.simpleName + ": " + e.message
        } finally {
            runCatching { renderer.close() }; runCatching { pfd.close() }
        }
        lastDiag = diag.toString()
        return result
    }

    // ══════════════════ 2) PdfiumAndroid ══════════════════

    private fun renderWithPdfium(context: Context, file: File, forPrintMode: Boolean = false): PdfOcrResult? {
        val core = try { PdfiumCore(context) } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(pdfium) 初始化失败: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.PDFIUM_UNAVAILABLE
            lastFailDetail = "${e.javaClass.simpleName}: ${e.message}"
            lastDiag = "PdfiumCore: 不可用($lastFailDetail)"
            return null
        }

        // 模式A：PFD
        val pfdResult = tryRenderWithPfd(core, file, forPrintMode)
        if (pfdResult != null) return pfdResult

        // 模式B：ByteArray
        if (lastFailReason == FailReason.PDFIUM_FAILED || lastFailReason == FailReason.PDFIUM_BLANK) {
            Log.d("WordCount", "PdfOcr(pdfium) PFD失败(${lastFailReason})，尝试ByteArray模式...")
            val bytesResult = tryRenderWithBytes(core, file, forPrintMode)
            if (bytesResult != null) return bytesResult
        }
        // Pdfium 失败时补充诊断
        if (lastDiag.isEmpty()) lastDiag = "Pdfium: ${lastFailReason.name} $lastFailDetail"
        return null
    }

    private fun tryRenderWithPfd(core: PdfiumCore, file: File, forPrintMode: Boolean = false): PdfOcrResult? {
        val pfd = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) } catch (_: Throwable) {
            lastFailReason = FailReason.PDFIUM_FAILED; lastFailDetail = "PFD open failed"; return null
        }
        return try {
            val doc = core.newDocument(pfd)
            val pageCount = core.getPageCount(doc)
            val limit = min(pageCount, MAX_PAGES)
            val sb = StringBuilder()
            var anyContent = false; var errors = 0
            for (i in 0 until limit) {
                try { core.openPage(doc, i) } catch (e: Throwable) { errors++; continue }
                try {
                    val sz: Size = core.getPageSize(doc, i)
                    val sw = sz.width; val sh = sz.height
                    if (sw <= 0 || sh <= 0) { errors++; continue }
                    val baseSc = computeScale(sw.toInt(), sh.toInt())
                    val sc = if (forPrintMode) baseSc * 2f else baseSc
                    val bw = max(1, (sw * sc).toInt()); val bh = max(1, (sh * sc).toInt())
                    val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { errors++; continue }
                    try {
                        core.renderPageBitmap(doc, bmp, i, 0, 0, bw, bh)
                        if (isBlankBitmap(bmp)) continue
                        anyContent = true
                        val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                        if (t.isNotBlank()) sb.append(t).append('\n')
                    } catch (e: Throwable) { errors++; lastFailDetail = "PFD.render[$i]: ${e.javaClass.simpleName}" } finally { bmp.recycle() }
                } finally { /* closeDocument 释放所有页面 */ }
            }
            val text = sb.toString().trim()
            if (text.isNotBlank()) PdfOcrResult(text, pageCount)
            else { lastFailReason = if (anyContent) FailReason.OCR_EMPTY else FailReason.PDFIUM_BLANK; null }
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(PFD) 失败: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { pfd.close() }
            lastFailReason = FailReason.PDFIUM_FAILED
            lastFailDetail = "PFD: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    private fun tryRenderWithBytes(core: PdfiumCore, file: File, forPrintMode: Boolean = false): PdfOcrResult? {
        return try {
            val bytes = file.readBytes()
            val doc = core.newDocument(bytes)
            val pageCount = core.getPageCount(doc)
            val limit = min(pageCount, MAX_PAGES)
            val sb = StringBuilder()
            var anyContent = false; var errors = 0
            for (i in 0 until limit) {
                try { core.openPage(doc, i) } catch (_: Throwable) { errors++; continue }
                try {
                    val sz: Size = core.getPageSize(doc, i)
                    val sw = sz.width; val sh = sz.height
                    if (sw <= 0 || sh <= 0) { errors++; continue }
                    val baseSc = computeScale(sw.toInt(), sh.toInt())
                    val sc = if (forPrintMode) baseSc * 2f else baseSc
                    val bw = max(1, (sw * sc).toInt()); val bh = max(1, (sh * sc).toInt())
                    val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { errors++; continue }
                    try {
                        core.renderPageBitmap(doc, bmp, i, 0, 0, bw, bh)
                        if (isBlankBitmap(bmp)) continue
                        anyContent = true
                        val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                        if (t.isNotBlank()) sb.append(t).append('\n')
                    } catch (e: Throwable) { errors++; lastFailDetail = "Bytes.render[$i]: ${e.javaClass.simpleName}" } finally { bmp.recycle() }
                } finally { /* closeDocument 释放 */ }
            }
            val text = sb.toString().trim()
            if (text.isNotBlank()) PdfOcrResult(text, pageCount)
            else { lastFailReason = if (anyContent) FailReason.OCR_EMPTY else FailReason.PDFIUM_BLANK; null }
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(BYTES) 失败: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.PDFIUM_FAILED
            lastFailDetail = "Bytes: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    // ══════════════════ 3) 内嵌图片提取（多策略）══════════════════

    private fun extractEmbeddedImages(file: File): List<Bitmap> {
        val results = mutableListOf<Bitmap>()
        val seenOffsets = mutableSetOf<Int>()

        try {
            val data = file.readBytes()
            if (data.size < 100) return emptyList()

            strategyA_XObjectImage(data, results, seenOffsets)
            if (results.isEmpty()) strategyB_RelaxedImage(data, results, seenOffsets)
            if (results.isEmpty()) strategyC_RawStreamDecode(data, results, seenOffsets)

            Log.d("WordCount", "PdfOcr(内嵌图片) 多策略提取 ${results.size} 张")
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(内嵌图片) 异常: ${e.javaClass.simpleName}: ${e.message}")
        }
        return results
    }

    /** 策略A: 标准 /Type/XObject /Subtype/Image ... /Length N 字典 */
    private fun strategyA_XObjectImage(data: ByteArray, out: MutableList<Bitmap>, seen: MutableSet<Int>) {
        val str = String(data, 0, min(data.size, 10 * 1024 * 1024), Charsets.ISO_8859_1)
        val imgPattern = Regex("""/Type\s*/XObject\s*/Subtype\s*/Image[^>]*?/Length\s+(\d+)""")
        for (match in imgPattern.findAll(str)) {
            if (out.size >= MAX_PAGES) break
            try {
                val length = match.groupValues[1].trim().toIntOrNull() ?: continue
                if (length < 100 || length > 50_000_000) continue
                val dictEnd = match.range.last
                val streamStart = str.indexOf("stream", dictEnd)
                if (streamStart < 0 || streamStart > dictEnd + 500) continue
                val b1 = data.getOrNull(streamStart + 6)?.toInt() ?: 0
                val offset = when {
                    b1 == 0x0D -> 2
                    b1 == 0x0A -> 1
                    else -> 0
                }.coerceAtMost(2)
                val dataStart = streamStart + 7 + offset
                if (dataStart + length > data.size || seen.contains(dataStart)) continue
                seen.add(dataStart)
                val imgBytes = data.sliceArray(dataStart until dataStart + length)
                val bmp = BitmapFactoryDecode(imgBytes) ?: continue
                if (bmp.width > 10 && bmp.height > 10) out.add(bmp) else bmp.recycle()
            } catch (_: Throwable) {}
        }
    }

    /** 策略B: 宽松 /Subtype/Image 匹配 + 广域 /Length 搜索 */
    private fun strategyB_RelaxedImage(data: ByteArray, out: MutableList<Bitmap>, seen: MutableSet<Int>) {
        val str = String(data, 0, min(data.size, 10 * 1024 * 1024), Charsets.ISO_8859_1)
        val subtypePatterns = listOf(
            """/Subtype\s*/\s*Image""".toRegex(RegexOption.IGNORE_CASE),
            """/S\s*/\s*Image""".toRegex(RegexOption.IGNORE_CASE)
        )
        for (pattern in subtypePatterns) {
            for (match in pattern.findAll(str)) {
                if (out.size >= MAX_PAGES) break
                try {
                    val pos = match.range.start
                    val searchRegion = str.substring(max(0, pos - 500), min(str.length, pos + 500))
                    val lenMatch = """/Length\s+(\d+)""".toRegex().find(searchRegion) ?: continue
                    val length = lenMatch.groupValues[1].trim().toIntOrNull() ?: continue
                    if (length < 100 || length > 50_000_000) continue
                    val afterMatch = str.substring(pos, min(str.length, pos + 2000))
                    val streamIdx = afterMatch.indexOf("stream")
                    if (streamIdx < 0) continue
                    val absStreamPos = pos + streamIdx
                    val b1 = data.getOrNull(absStreamPos + 6)?.toInt() ?: 0
                    val offset = when {
                        b1 == 0x0D -> 2
                        b1 == 0x0A -> 1
                        else -> 0
                    }.coerceAtMost(2)
                    val dataStart = absStreamPos + 7 + offset
                    if (dataStart + length > data.size || seen.contains(dataStart)) continue
                    seen.add(dataStart)
                    val imgBytes = data.sliceArray(dataStart until dataStart + length)
                    val bmp = BitmapFactoryDecode(imgBytes) ?: continue
                    if (bmp.width > 10 && bmp.height > 10) out.add(bmp) else bmp.recycle()
                } catch (_: Throwable) {}
            }
        }
    }

    /** 策略C: stream 块暴力解码 */
    private fun strategyC_RawStreamDecode(data: ByteArray, out: MutableList<Bitmap>, seen: MutableSet<Int>) {
        val kw = "stream".toByteArray(Charsets.US_ASCII)
        val endKw = "endstream".toByteArray(Charsets.US_ASCII)
        var pos = 0
        var attempts = 0
        val maxAttempts = 50

        while (pos <= data.size - kw.size - 2 && attempts < maxAttempts && out.size < MAX_PAGES) {
            attempts++
            val idx = indexOfBytes(data, kw, pos)
            if (idx < 0) break
            val afterKw = idx + kw.size
            val dataStart = when {
                afterKw + 1 < data.size && data[afterKw] == '\r'.code.toByte()
                        && afterKw + 2 < data.size && data[afterKw + 1] == '\n'.code.toByte() -> afterKw + 2
                afterKw < data.size && data[afterKw] == '\n'.code.toByte() -> afterKw + 1
                afterKw < data.size && data[afterKw] == '\r'.code.toByte() -> afterKw + 1
                else -> { pos = idx + 1; continue }
            }
            val endPos = indexOfBytes(data, endKw, dataStart)
            val dataLen = if (endPos >= 0) endPos - dataStart else min(10 * 1024 * 1024, data.size - dataStart)
            if (dataLen > 1024 && dataLen < 10 * 1024 * 1024 && !seen.contains(dataStart)) {
                seen.add(dataStart)
                try {
                    val chunk = data.sliceArray(dataStart until dataStart + dataLen)
                    val bmp = BitmapFactoryDecode(chunk)
                    if (bmp != null && bmp.width > 50 && bmp.height > 50) out.add(bmp) else bmp?.recycle()
                } catch (_: Throwable) {}
            }
            pos = if (endPos >= 0) endPos + endKw.size else dataStart + dataLen
        }
    }

    // ───────────────────── 工具函数 ─────────────────────

    private fun BitmapFactoryDecode(bytes: ByteArray): Bitmap? = try {
        val opts = android.graphics.BitmapFactory.Options()
        opts.inSampleSize = 1
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Throwable) { null }

    private fun isBlankBitmap(bmp: Bitmap): Boolean {
        return try {
            val w = bmp.width; val h = bmp.height
            if (w <= 0 || h <= 0) return@isBlankBitmap true
            val sx = max(1, w / 32); val sy = max(1, h / 32)
            var nw = 0; var s = 0; var y = 0
            while (y < h) { var x = 0
            while (x < w) { val px = bmp.getPixel(x, y)
                s++
                if ((px shr 16 and 0xFF) < 248 || (px shr 8 and 0xFF) < 248 || (px and 0xFF) < 248) nw++
                x += sx
            }
            y += sy
            }
            if (s == 0) true else (nw.toDouble() / s) < 0.005
        } catch (_: Throwable) { false }
    }

    private fun computeScale(w: Int, h: Int): Float {
        val maxSide = max(w, h)
        return if (maxSide <= MAX_DIM) 1f else MAX_DIM.toFloat() / maxSide
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, fromIndex: Int): Int {
        outer@ for (i in fromIndex..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
