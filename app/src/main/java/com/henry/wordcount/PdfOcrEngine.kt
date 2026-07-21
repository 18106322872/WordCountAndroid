package com.henry.wordcount

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shockwave.pdfium.PdfiumCore
import com.shockwave.pdfium.util.Size
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 文本提取的 OCR 兜底引擎。
 *
 * 渲染链路（按优先级）：
 *  1) 系统 PdfRenderer（轻量）→ ML Kit OCR
 *  2) PdfiumAndroid（支持 JPX/JBIG2 扫描图）→ ML Kit OCR
 *  3) PDF 内嵌图片直接提取（v1.0.39 新增，完全绕过渲染器）
 *
 * v1.0.39 改进：
 *  - Pdfium 新增文件路径字符串打开模式（部分版本 PFD 有兼容问题）
 *  - 新增 extractEmbeddedImages 路径：从 PDF 二进制找 Image XObject → 解码 → OCR
 *  - 每步详细日志 + 失败原因精确分类
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
        NO_EMBEDDED_IMAGES   // v1.0.39: PDF 中未找到可提取的内嵌图片
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
        Log.d("WordCount", "PdfOcr 开始: ${file.name} (${file.length()} bytes, path=${file.absolutePath})")

        // 1) 系统 PdfRenderer
        val sys = renderWithSystem(file)
        if (sys != null) return sys

        // 2) PdfiumAndroid（先尝试 PFD 模式，再尝试文件路径模式）
        val pdfium = renderWithPdfium(context, file)
        if (pdfium != null) return pdfium

        // 3) v1.0.39: 内嵌图片提取（完全绕过渲染器，对扫描件最可靠）
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
                Log.d("WordCount", "PdfOcr(内嵌图片) 成功: ${images.size}张图, 总文字=${sb.length}")
                return PdfOcrResult(sb.toString().trim(), images.size)
            }
            lastFailReason = FailReason.OCR_EMPTY
        } else {
            lastFailReason = FailReason.NO_EMBEDDED_IMAGES
        }

        Log.w("WordCount", "PdfOcr 全部路径失败: ${file.name}, reason=$lastFailReason")
        return null
    }

    // ───────────────────── 系统 PdfRenderer ─────────────────────

    private fun renderWithSystem(file: File): PdfOcrResult? {
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(sys) PFD失败 ${file.name}: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.RENDER_FAILED
            return null
        }
        val renderer = try { PdfRenderer(pfd) } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(sys) Renderer失败 ${file.name}: ${e.message}")
            runCatching { pfd.close() }
            lastFailReason = FailReason.RENDER_FAILED
            return null
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
            Log.d("WordCount", "PdfOcr(sys) 完成: ${limit}页, 内容=$anyRenderedContent, OCR=$anyOcrText, 错误=$pageErrors")

            if (text.isNotBlank()) result = PdfOcrResult(text, pageCount)
            else if (anyRenderedContent) lastFailReason = FailReason.OCR_EMPTY
            else if (pageErrors > 0) lastFailReason = FailReason.RENDER_PARTIAL
            else lastFailReason = FailReason.RENDER_BLANK
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(sys) 异常: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.RENDER_FAILED
        } finally {
            runCatching { renderer.close() }; runCatching { pfd.close() }
        }
        return result
    }

    // ───────────────────── PdfiumAndroid（双模式） ─────────────────────

    private fun renderWithPdfium(context: Context, file: File): PdfOcrResult? {
        val core = try { PdfiumCore(context) } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(pdfium) 初始化失败: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.PDFIUM_UNAVAILABLE
            return null
        }

        // 尝试模式A：ParcelFileDescriptor
        val pfdResult = tryRenderWithPfd(core, file)
        if (pfdResult != null) return pfdResult

        // 尝试模式B：文件路径字符串（某些 Pdfium 版本 PFD 有 bug，路径模式更稳定）
        if (lastFailReason == FailReason.PDFIUM_FAILED) {
            Log.d("WordCount", "PdfOcr(pdfium) PFD失败，尝试文件路径模式...")
            val pathResult = tryRenderWithPath(core, file)
            if (pathResult != null) return pathResult
        }

        return null
    }

    /** Pdfium 模式A：用 ParcelFileDescriptor 打开 */
    private fun tryRenderWithPfd(core: PdfiumCore, file: File): PdfOcrResult? {
        val pfd = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(pdfium-PDF) PFD打开失败: ${e.message}")
            lastFailReason = FailReason.PDFIUM_FAILED; return null
        }
        return try {
            val doc = core.newDocument(pfd)
            doRenderPages(core, doc, file, pfd)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(pdfium-PDF) newDocument失败: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { pfd.close() }
            lastFailReason = FailReason.PDFIUM_FAILED; null
        }
    }

    /** Pdfium 模式B：用文件路径字符串打开（绕过 PFD 兼容性问题） */
    private fun tryRenderWithPath(core: PdfiumCore, file: File): PdfOcrResult? {
        return try {
            val doc = core.newDocument(file.absolutePath)
            doRenderPages(core, doc, file, null)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(pdfium-PATH) newDocument失败: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.PDFIUM_FAILED; null
        }
    }

    /** Pdfium 共享的页面渲染逻辑 */
    private fun doRenderPages(core: PdfiumCore, doc: Int, file: File, pfd: ParcelFileDescriptor?): PdfOcrResult? {
        return try {
            val pageCount = core.getPageCount(doc)
            val limit = min(pageCount, MAX_PAGES)
            val sb = StringBuilder()
            var anyContent = false; var anyOcr = false; var errors = 0

            for (i in 0 until limit) {
                try { core.openPage(doc, i) } catch (_: Throwable) { errors++; continue }
                try {
                    val size: Size = core.getPageSize(doc, i)
                    val w = size.width; val h = size.height
                    if (w <= 0 || h <= 0) { errors++; continue }
                    val scale = computeScale(w, h)
                    val bw = max(1, (w * scale).toInt()); val bh = max(1, (h * scale).toInt())
                    val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { errors++; continue }
                    try {
                        core.renderPageBitmap(doc, bmp, i, 0, 0, bw, bh)
                        if (isBlankBitmap(bmp)) continue
                        anyContent = true
                        val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                        if (t.isNotBlank()) { sb.append(t).append('\n'); anyOcr = true }
                    } catch (_: Throwable) { errors++ } finally { bmp.recycle() }
                } finally { /* page 随 closeDocument 统一释放 */ }
            }

            val text = sb.toString().trim()
            Log.d("WordCount", "PdfOcr(pdfium) 完成: ${limit}页, 内容=$anyContent, OCR=$anyOcr, 错误=$errors")
            if (text.isNotBlank()) PdfOcrResult(text, pageCount)
            else { lastFailReason = if (anyContent) FailReason.OCR_EMPTY else FailReason.PDFIUM_BLANK; null }
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(pdfium) 渲染异常: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.PDFIUM_FAILED; null
        } finally {
            runCatching { core.closeDocument(doc) }
            runCatching { pfd?.close() }
        }
    }

    // ══════════════════ v1.0.39: PDF 内嵌图片提取（最终后备） ══════════════════

    /**
     * 从 PDF 二进制中提取内嵌图片（Image XObject），用于扫描件 PDF 的 OCR。
     *
     * 原理：大多数扫描件 PDF 的每页内容就是一个或多个 Image XObject（JPEG/JPEG2000）。
     * 直接提取这些原始字节、解码为 Bitmap、交给 ML Kit OCR —— 完全不依赖任何 PDF 渲染器。
     *
     * @return 解码后的 Bitmap 列表（可能为空）；调用方负责 recycle
     */
    private fun extractEmbeddedImages(file: File): List<Bitmap> {
        val results = mutableListOf<Bitmap>()
        try {
            val data = file.readBytes()
            val str = String(data, 0, data.size, Charsets.ISO_8859_1)
            // 找所有 /Subtype /Image 对象（在 stream 前面声明）
            // PDF 格式: ... /Type /XObject /Subtype /Image ... /Length N >> stream ... endstream
            val imgPattern = Regex("""/Type\s*/XObject\s*/Subtype\s*/Image[^>]*?/Length\s+(\d+)""")
            val matches = imgPattern.findAll(str)

            for ((idx, match) in matches.withIndex()) {
                if (results.size >= MAX_PAGES) break
                try {
                    val length = match.groupValues[1].trim().toIntOrNull() ?: continue
                    if (length < 100 || length > 50_000_000) continue  // 合理范围: 100B ~ 50MB

                    // 从 dict 结束位置找 stream 关键字
                    val dictEnd = match.range.last
                    val streamStart = str.indexOf("stream", dictEnd)
                    if (streamStart < 0 || streamStart > dictEnd + 500) continue

                    // stream 关键字后面可能有 \r\n 或 \n（PDF 规范要求）
                    val dataStart = streamStart + 7 + when {
                        data.getOrNull(streamStart + 6)?.code == 0x0D -> 1  // \r\n
                        data.getOrNull(streamStart + 6)?.code == 0x0A -> 1  // \n
                        else -> 0
                    }.coerceAtMost(2)

                    // 确保不越界
                    if (dataStart + length > data.size) continue

                    // 提取原始图像数据
                    val imgBytes = data.sliceArray(dataStart until dataStart + length)

                    // 尝试解码为 Bitmap（支持 JPEG/PNG/GIF/WebP 等 Android 原生格式）
                    val bmp = tryDecodeImage(imgBytes) ?: continue
                    if (bmp.width > 10 && bmp.height > 10) {
                        results.add(bmp)
                        Log.d("WordCount", "PdfOcr(内嵌图${idx+1}) 提取成功: ${bmp.width}x${bmp.height}, 格式推断=猜, 大小=${imgBytes.size}bytes")
                    } else {
                        bmp.recycle()
                    }
                } catch (_: Throwable) {}
            }

            Log.d("WordCount", "PdfOcr(内嵌图片) 共提取 ${results.size} 张图片")
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(内嵌图片) 提取异常: ${e.javaClass.simpleName}: ${e.message}")
        }
        return results
    }

    /** 尝试用 Android BitmapFactory 解码图像字节（支持 JPEG/PNG/GIF/WebP/BMP） */
    private fun tryDecodeImage(bytes: ByteArray): Bitmap? {
        return try {
            val opts = android.graphics.BitmapFactory.Options()
            opts.inSampleSize = 1  // 不缩放，保持原尺寸以便 OCR
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bmp == null) null else bmp
        } catch (_: Throwable) { null }
    }

    // ───────────────────── 工具函数 ─────────────────────

    private fun isBlankBitmap(bmp: Bitmap): Boolean = try {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return true
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

    private fun computeScale(w: Int, h: Int): Float =
        if (max(w, h) <= MAX_DIM) 1f else MAX_DIM.toFloat() / max(w, h)
}
