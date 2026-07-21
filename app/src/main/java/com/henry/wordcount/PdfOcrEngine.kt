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
 * PDF 文本提取的 OCR 兜底引擎。
 *
 * 渲染链路（按优先级）：
 *  1) 系统 PdfRenderer（轻量）→ ML Kit OCR
 *  2) PdfiumAndroid（支持 JPX/JBIG2 扫描图）→ ML Kit OCR
 *  3) PDF 内嵌图片直接提取（v1.0.39，完全绕过渲染器）
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
        NO_EMBEDDED_IMAGES
    }

    @Volatile var lastFailReason: FailReason = FailReason.OK
        private set

    fun extractText(context: Context, file: File): PdfOcrResult? {
        lastFailReason = FailReason.OK
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

        // 3) v1.0.39: 内嵌图片提取
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

        Log.w("WordCount", "PdfOcr 全部路径失败: ${file.name}, reason=$lastFailReason")
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
            Log.w("WordCount", "PdfOcr(pdfium) 初始化失败: ${e.javaClass.simpleName}")
            lastFailReason = FailReason.PDFIUM_UNAVAILABLE; return null
        }

        // 模式A：PFD
        val pfdResult = tryRenderWithPfd(core, file)
        if (pfdResult != null) return pfdResult

        // 模式B：ByteArray（读取文件内容，绕过 PFD 问题）
        if (lastFailReason == FailReason.PDFIUM_FAILED) {
            Log.d("WordCount", "PdfOcr(pdfium) PFD失败，尝试ByteArray模式...")
            val bytesResult = tryRenderWithBytes(core, file)
            if (bytesResult != null) return bytesResult
        }
        return null
    }

    /** Pdfium 模式A：ParcelFileDescriptor（doc 类型由编译器本地推断） */
    private fun tryRenderWithPfd(core: PdfiumCore, file: File): PdfOcrResult? {
        val pfd = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) } catch (_: Throwable) {
            lastFailReason = FailReason.PDFIUM_FAILED; return null
        }
        return try {
            val doc = core.newDocument(pfd)
            // ── 内联渲染（避免 doc 类型传递问题）──
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
                    } catch (_: Throwable) { errors++ } finally { bmp.recycle() }
                } finally { /* 页面随 closeDocument 统一释放 */ }
            }
            val text = sb.toString().trim()
            if (text.isNotBlank()) PdfOcrResult(text, pageCount)
            else { lastFailReason = if (anyContent) FailReason.OCR_EMPTY else FailReason.PDFIUM_BLANK; null }
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(PDF) 失败: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { pfd.close() }
            lastFailReason = FailReason.PDFIUM_FAILED; null
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
                    } catch (_: Throwable) { errors++ } finally { bmp.recycle() }
                } finally { /* 页面随 closeDocument 统一释放 */ }
            }
            val text = sb.toString().trim()
            if (text.isNotBlank()) PdfOcrResult(text, pageCount)
            else { lastFailReason = if (anyContent) FailReason.OCR_EMPTY else FailReason.PDFIUM_BLANK; null }
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(BYTES) 失败: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.PDFIUM_FAILED; null
        }
    }

    // ══════════════════ v1.0.39: PDF 内嵌图片提取 ══════════════════

    private fun extractEmbeddedImages(file: File): List<Bitmap> {
        val results = mutableListOf<Bitmap>()
        try {
            val data = file.readBytes()
            val str = String(data, 0, data.size, Charsets.ISO_8859_1)
            val imgPattern = Regex("""/Type\s*/XObject\s*/Subtype\s*/Image[^>]*?/Length\s+(\d+)""")
            val matches = imgPattern.findAll(str)

            for ((idx, match) in matches.withIndex()) {
                if (results.size >= MAX_PAGES) break
                try {
                    val length = match.groupValues[1].trim().toIntOrNull() ?: continue
                    if (length < 100 || length > 50_000_000) continue
                    val dictEnd = match.range.last
                    val streamStart = str.indexOf("stream", dictEnd)
                    if (streamStart < 0 || streamStart > dictEnd + 500) continue
                    // stream 后跟 \r\n 或 \n（PDF 规范）
                    val b1 = data.getOrNull(streamStart + 6)?.toInt() ?: 0
                    val offset = when {
                        b1 == 0x0D -> 2  // \r\n
                        b1 == 0x0A -> 1  // \n
                        else -> 0
                    }.coerceAtMost(2)
                    val dataStart = streamStart + 7 + offset
                    if (dataStart + length > data.size) continue
                    val imgBytes = data.sliceArray(dataStart until dataStart + length)
                    val bmp = BitmapFactoryDecode(imgBytes) ?: continue
                    if (bmp.width > 10 && bmp.height > 10) results.add(bmp) else bmp.recycle()
                } catch (_: Throwable) {}
            }
            Log.d("WordCount", "PdfOcr(内嵌图片) 提取 ${results.size} 张")
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(内嵌图片) 异常: ${e.javaClass.simpleName}")
        }
        return results
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
}
