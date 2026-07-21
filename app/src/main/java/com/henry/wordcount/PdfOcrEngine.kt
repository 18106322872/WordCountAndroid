package com.henry.wordcount

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import com.shockwave.pdfium.PdfiumCore
import com.shockwave.pdfium.util.Size
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 文本提取的 OCR 兜底引擎。
 *
 * 渲染链路（按优先级）：
 *  1) 系统 PdfRenderer（轻量）→ ML Kit OCR
 *  2) PdfiumAndroid（支持 JPX/JBIG2 扫描图）→ ML Kit OCR
 *  3) PDF 内嵌图片直接提取（v1.0.39）
 *  4) PyMuPDF(fitz) 渲染整页为图 → ML Kit OCR （v1.0.40，终极后备）
 */
object PdfOcrEngine {

    private const val MAX_PAGES = 40
    private const val MAX_DIM = 2048

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
        NO_EMBEDDED_IMAGES,
        PYMUPDF_UNAVAILABLE,
        PYMUPDF_FAILED,
        PYMUPDF_NO_IMAGES
    }

    @Volatile var lastFailReason: FailReason = FailReason.OK
        private set

    /** 上一次失败的具体异常信息（用于诊断） */
    @Volatile var lastFailDetail: String = ""
        private set

    fun extractText(context: Context, file: File): PdfOcrResult? {
        lastFailReason = FailReason.OK
        lastFailDetail = ""
        if (!OcrEngine.ocrEnabled) {
            Log.w("WordCount", "PdfOcr 跳过: ocrEnabled=false")
            lastFailReason = FailReason.OCR_DISABLED
            return null
        }
        Log.d("WordCount", "PdfOcr 开始: ${file.name} (${file.length()} bytes)")

        // 1) 系统 PdfRenderer
        val sys = renderWithSystem(file)
        if (sys != null) return sys

        // 2) PdfiumAndroid（PFD + ByteArray 双模式）
        val pdfium = renderWithPdfium(context, file)
        if (pdfium != null) return pdfium

        // 3) v1.0.39+: 内嵌图片提取（多策略 v1.0.40 增强）
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
            Log.w("WordCount", "PdfOcr 内嵌图片OCR结果为空: ${file.name}")
        } else {
            lastFailReason = FailReason.NO_EMBEDDED_IMAGES
            Log.w("WordCount", "PdfOcr 未找到内嵌图片: ${file.name}")
        }

        // 4) v1.0.40: PyMuPDF(fitz) 渲染整页 → ML Kit OCR（终极后备）
        Log.d("WordCount", "PdfOcr 尝试路径4(PyMuPDF渲染): ${file.name}")
        val pymupdf = renderWithPyMupdf(context, file)
        if (pymupdf != null) return pymupdf

        Log.w("WordCount", "PdfOcr 全部路径失败: ${file.name}, reason=$lastFailReason detail=$lastFailDetail")
        return null
    }

    // ══════════════════ 系统 PdfRenderer ══════════════════

    private fun renderWithSystem(file: File): PdfOcrResult? {
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Throwable) {
            lastFailReason = FailReason.RENDER_FAILED; return null
        }
        val renderer = try { PdfRenderer(pfd) } catch (e: Throwable) {
            runCatching { pfd.close() }
            lastFailReason = FailReason.RENDER_FAILED; return null
        }
        var result: PdfOcrResult? = null
        try {
            val pageCount = renderer.pageCount
            val limit = min(pageCount, MAX_PAGES)
            val sb = StringBuilder()
            var anyRenderedContent = false; var anyOcrText = false; var pageErrors = 0

            for (i in 0 until limit) {
                try {
                    val page = renderer.openPage(i)
                    try {
                        val w = page.width; val h = page.height
                        if (w <= 0 || h <= 0) continue
                        val scale = computeScale(w, h)
                        val bw = max(1, (w * scale).toInt()); val bh = max(1, (h * scale).toInt())
                        val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { pageErrors++; continue }
                        try {
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            if (isBlankBitmap(bmp)) continue
                            anyRenderedContent = true
                            val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                            if (t.isNotBlank()) { sb.append(t).append('\n'); anyOcrText = true }
                        } catch (_: Throwable) { pageErrors++ } finally { bmp.recycle() }
                    } finally { page.close() }
                } catch (_: Throwable) { pageErrors++ }
            }

            val text = sb.toString().trim()
            if (text.isNotBlank()) result = PdfOcrResult(text, pageCount)
            else if (anyRenderedContent) lastFailReason = FailReason.OCR_EMPTY
            else if (pageErrors > 0) lastFailReason = FailReason.RENDER_PARTIAL
            else lastFailReason = FailReason.RENDER_BLANK
        } catch (e: Throwable) {
            lastFailReason = FailReason.RENDER_FAILED
        } finally {
            runCatching { renderer.close() }; runCatching { pfd.close() }
        }
        return result
    }

    // ══════════════════ PdfiumAndroid ══════════════════

    private fun renderWithPdfium(context: Context, file: File): PdfOcrResult? {
        val core = try { PdfiumCore(context) } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(pdfium) 初始化失败: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.PDFIUM_UNAVAILABLE
            lastFailDetail = "${e.javaClass.simpleName}: ${e.message}"
            return null
        }

        // 模式A：PFD
        val pfdResult = tryRenderWithPfd(core, file)
        if (pfdResult != null) return pfdResult

        // 模式B：ByteArray（读取文件内容，绕过 PFD 问题）
        if (lastFailReason == FailReason.PDFIUM_FAILED || lastFailReason == FailReason.PDFIUM_BLANK) {
            Log.d("WordCount", "PdfOcr(pdfium) PFD失败(${lastFailReason})，尝试ByteArray模式...")
            val bytesResult = tryRenderWithBytes(core, file)
            if (bytesResult != null) return bytesResult
        }
        return null
    }

    /** Pdfium 模式A：ParcelFileDescriptor（doc 类型由编译器本地推断） */
    private fun tryRenderWithPfd(core: PdfiumCore, file: File): PdfOcrResult? {
        val pfd = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) } catch (_: Throwable) {
            lastFailReason = FailReason.PDFIUM_FAILED; lastFailDetail = "PFD open failed"; return null
        }
        return try {
            val doc = core.newDocument(pfd)
            // ── 内联渲染（避免 doc 类型传递问题）──
            val pageCount = core.getPageCount(doc)
            val limit = min(pageCount, MAX_PAGES)
            val sb = StringBuilder()
            var anyContent = false; var anyOcr = false; var errors = 0
            for (i in 0 until limit) {
                try { core.openPage(doc, i) } catch (e: Throwable) { errors++; continue }
                try {
                    val sz: Size = core.getPageSize(doc, i)
                    val sw = sz.width; val sh = sz.height
                    if (sw <= 0 || sh <= 0) { errors++; continue }
                    val sc = computeScale(sw, sh)
                    val bw = max(1, (sw * sc).toInt()); val bh = max(1, (sh * sc).toInt())
                    val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { errors++; continue }
                    try {
                        core.renderPageBitmap(doc, bmp, i, 0, 0, bw, bh)
                        if (isBlankBitmap(bmp)) continue
                        anyContent = true
                        val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                        if (t.isNotBlank()) { sb.append(t).append('\n'); anyOcr = true }
                    } catch (e: Throwable) { errors++; lastFailDetail = "renderPageBitmap[$i]: ${e.javaClass.simpleName}" } finally { bmp.recycle() }
                } finally { /* 页面随 closeDocument 统一释放 */ }
            }
            val text = sb.toString().trim()
            if (text.isNotBlank()) PdfOcrResult(text, pageCount)
            else { lastFailReason = if (anyContent) FailReason.OCR_EMPTY else FailReason.PDFIUM_BLANK; lastFailDetail = "pages=$pageCount content=$anyContent ocr=$anyOcr errors=$errors"; null }
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(PDF) 失败: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { pfd.close() }
            lastFailReason = FailReason.PDFIUM_FAILED
            lastFailDetail = "${e.javaClass.simpleName}: ${e.message}"
            null
        } finally {
            // 注意：closeDocument 在 try 块内 doc 可用范围之后无法调用；依赖 finally 的 pfd.close()
        }
    }

    /** Pdfium 模式B：文件内容为 ByteArray */
    private fun tryRenderWithBytes(core: PdfiumCore, file: File): PdfOcrResult? {
        return try {
            val bytes = file.readBytes()
            val doc = core.newDocument(bytes)
            // ── 内联渲染（同上，doc 类型本地推断）──
            val pageCount = core.getPageCount(doc)
            val limit = min(pageCount, MAX_PAGES)
            val sb = StringBuilder()
            var anyContent = false; var anyOcr = false; var errors = 0
            for (i in 0 until limit) {
                try { core.openPage(doc, i) } catch (_: Throwable) { errors++; continue }
                try {
                    val sz: Size = core.getPageSize(doc, i)
                    val sw = sz.width; val sh = sz.height
                    if (sw <= 0 || sh <= 0) { errors++; continue }
                    val sc = computeScale(sw, sh)
                    val bw = max(1, (sw * sc).toInt()); val bh = max(1, (sh * sc).toInt())
                    val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { errors++; continue }
                    try {
                        core.renderPageBitmap(doc, bmp, i, 0, 0, bw, bh)
                        if (isBlankBitmap(bmp)) continue
                        anyContent = true
                        val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                        if (t.isNotBlank()) { sb.append(t).append('\n'); anyOcr = true }
                    } catch (e: Throwable) { errors++; lastFailDetail = "Bytes.renderPageBitmap[$i]: ${e.javaClass.simpleName}" } finally { bmp.recycle() }
                } finally { /* 页面随 closeDocument 统一释放 */ }
            }
            val text = sb.toString().trim()
            if (text.isNotBlank()) PdfOcrResult(text, pageCount)
            else { lastFailReason = if (anyContent) FailReason.OCR_EMPTY else FailReason.PDFIUM_BLANK; lastFailDetail = "Bytes pages=$pageCount content=$anyContent errors=$errors"; null }
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(BYTES) 失败: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.PDFIUM_FAILED
            lastFailDetail = "Bytes: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    // ══════════════════ v1.0.40: PyMuPDF(fitz) 渲染整页 ══════════════════

    /**
     * 路径4：通过 Chaquopy Python 桥接调用 PyMuPDF 渲染 PDF 页面为 PNG，
     * 再用 ML Kit OCR 识别。
     *
     * 这是终极后备——PyMuPDF 基于 MuPDF（同一个库），支持所有 PDF 特性：
     *   - JPEG2000 (JPX) ✓
     *   - JBIG2 ✓
     *   - 加密 PDF（如有密码则跳过）
     *   - ObjStm / 交叉引用流 / 线性化 PDF
     */
    private fun renderWithPyMupdf(context: Context, file: File): PdfOcrResult? {
        try {
            val (ok, pages, b64Images) = PythonEngine.renderPdfPages(context, file.absolutePath)
            if (!ok || b64Images.isEmpty()) {
                lastFailReason = when {
                    !ok -> FailReason.PYMUPDF_UNAVAILABLE
                    else -> FailReason.PYMUPDF_NO_IMAGES
                }
                lastFailDetail = "PyMuPDF ok=$ok pages=$pages images=${b64Images.size}"
                Log.w("WordCount", "PdfOcr(PyMuPDF) 失败: ${file.name} - $lastFailDetail")
                return null
            }

            Log.d("WordCount", "PdfOcr(PyMuPDF) 渲染成功: ${file.name} → ${b64Images.size}张图, ${pages}页")
            val sb = StringBuilder()
            var anyText = false
            for ((idx, b64) in b64Images.withIndex()) {
                try {
                    val imgBytes = Base64.decode(b64, Base64.DEFAULT)
                    val bmp = BitmapFactoryDecode(imgBytes) ?: continue
                    if (bmp.width < 10 || bmp.height < 10) { bmp.recycle(); continue }
                    // 缩放到合理尺寸避免 OOM
                    val scaled = scaleBitmapIfNeeded(bmp)
                    try {
                        val t = OcrEngine.recognizeBitmap(scaled ?: bmp, skipPostFilter = true)
                        if (t.isNotBlank()) { sb.append(t).append('\n'); anyText = true }
                        Log.d("WordCount", "PdfOcr(PyMuPDF图${idx+1}) OCR: ${t.length}字 (raw=${bmp.width}x${bmp.height})")
                    } finally { scaled?.recycle(); if (scaled != bmp) bmp.recycle() }
                } catch (_: Throwable) {}
            }
            return if (anyText) {
                PdfOcrResult(sb.toString().trim(), pages.coerceAtLeast(b64Images.size))
            } else {
                lastFailReason = FailReason.OCR_EMPTY
                lastFailDetail = "PyMuPDF rendered ${b64Images.size} images but ML Kit found no text"
                null
            }
        } catch (e: Throwable) {
            lastFailReason = FailReason.PYMUPDF_FAILED
            lastFailDetail = "${e.javaClass.simpleName}: ${e.message}"
            Log.w("WordCount", "PdfOcr(PyMuPDF) 异常: ${file.name} - $lastFailDetail")
            return null
        }
    }

    /** 将 Bitmap 缩放到 MAX_DIM 以内（避免大图 OOM） */
    private fun scaleBitmapIfNeeded(bmp: Bitmap): Bitmap? {
        val w = bmp.width; val h = bmp.height
        if (w <= MAX_DIM && h <= MAX_DIM) return null  // 不需要缩放
        val scale = MAX_DIM.toFloat() / max(w, h)
        val nw = max(1, (w * scale).toInt())
        val nh = max(1, (h * scale).toInt())
        return try { Bitmap.createScaledBitmap(bmp, nw, nh, true) } catch (_: Throwable) { null }
    }

    // ══════════════════ v1.0.40 增强：多策略内嵌图片提取 ══════════════════

    /**
     * 从 PDF 原始字节中提取内嵌图片。v1.0.40 多策略版：
     *
     *   策略A: 标准 XObject 图片字典正则（原逻辑）
     *   策略B: 宽松 /Subtype/Image 匹配 + 广域 /Length 搜索
     *   策略C: stream 块暴力解码（尝试将每个 stream 当图片解码）
     *   策略D: JPEG/JPEG2000/PNG 签名搜索
     */
    private fun extractEmbeddedImages(file: File): List<Bitmap> {
        val results = mutableListOf<Bitmap>()
        val seenOffsets = mutableSetOf<Int>()  // 避免重复提取同一块数据

        try {
            val data = file.readBytes()
            if (data.size < 100) return emptyList()

            // ── 策略A: 标准字典正则 ──
            strategyA_XObjectImage(data, results, seenOffsets)

            // ── 策略B: 宽松匹配 ──
            if (results.isEmpty()) strategyB_RelaxedImage(data, results, seenOffsets)

            // ── 策略C: stream 暴力解码（仅当前面无结果时，限制前20个stream）──
            if (results.isEmpty()) strategyB_RawStreamDecode(data, results, seenOffsets)

            Log.d("WordCount", "PdfOcr(内嵌图片) 多策略提取 ${results.size} 张 [A/B/C/D]")
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

    /** 策略B: 宽松 /Subtype/Image 匹配，在前后 500 字符内找 /Length */
    private fun strategyB_RelaxedImage(data: ByteArray, out: MutableList<Bitmap>, seen: MutableSet<Int>) {
        val str = String(data, 0, min(data.size, 10 * 1024 * 1024), Charsets.ISO_8859_1)
        // 匹配 /Subtype/Image 或 /S/Image（不要求前面有 /Type/XObject）
        val subtypePatterns = listOf(
            """/Subtype\s*/\s*Image""".toRegex(RegexOption.IGNORE_CASE),
            """/S\s*/\s*Image""".toRegex(RegexOption.IGNORE_CASE)
        )
        for (pattern in subtypePatterns) {
            for (match in pattern.findAll(str)) {
                if (out.size >= MAX_PAGES) break
                try {
                    val pos = match.range.start
                    // 在位置前后各 500 字符范围内搜索 /Length N
                    val searchRegion = str.substring(max(0, pos - 500), min(str.length, pos + 500))
                    val lenMatch = """/Length\s+(\d+)""".toRegex().find(searchRegion) ?: continue
                    val length = lenMatch.groupValues[1].trim().toIntOrNull() ?: continue
                    if (length < 100 || length > 50_000_000) continue

                    // 找 stream 关键字（从匹配位置向后搜）
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

    /** 策略C: 暴力 stream 解码（对每个 stream 尝试 BitmapFactory） */
    private fun strategyB_RawStreamDecode(data: ByteArray, out: MutableList<Bitmap>, seen: MutableSet<Int>) {
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
            // stream 数据长度限制: 1KB ~ 10MB
            val dataLen = if (endPos >= 0) endPos - dataStart else min(10 * 1024 * 1024, data.size - dataStart)
            if (dataLen > 1024 && dataLen < 10 * 1024 * 1024 && !seen.contains(dataStart)) {
                seen.add(dataStart)
                try {
                    val chunk = data.sliceArray(dataStart until dataStart + dataLen)
                    val bmp = BitmapFactoryDecode(chunk)
                    if (bmp != null && bmp.width > 50 && bmp.height > 50) {
                        out.add(bmp)
                        Log.d("WordCount", "PdfOcr(策略C-stream暴力) 找到图: ${bmp.width}x${bmp.height}")
                    } else { bmp?.recycle() }
                } catch (_: Throwable) {}
            }
            pos = if (endPos >= 0) endPos + endKw.size else dataStart + dataLen
        }
    }

    private fun BitmapFactoryDecode(bytes: ByteArray): Bitmap? = try {
        val opts = android.graphics.BitmapFactory.Options()
        opts.inSampleSize = 1
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Throwable) { null }

    // ───────────────────── 工具函数 ─────────────────────

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

    /** 在 byte 数组中查找子数组位置（类似 String.indexOf） */
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
