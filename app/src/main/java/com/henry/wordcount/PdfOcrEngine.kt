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
 * 扫描件 / 快拍类 PDF 的文字层是「子集化 CID 字体 + 无 ToUnicode 映射」，纯文本解析只能得到乱码或空文本，
 * 只能把每一页渲染成图片后用 OCR 识别。本引擎与图片 OCR 共用同一套 ML Kit 识别器（OcrEngine），
 * 因此字数统计口径与图片、WORD 完全一致。
 *
 * 渲染器选择：
 *  - 优先用系统 PdfRenderer（轻量、零额外依赖）。
 *  - 若系统渲染失败或渲染出空白页（常见于扫描件把图嵌成 JPEG2000/JBIG2 压缩，系统 Pdfium 不解码），
 *    回退到 PdfiumAndroid（其内置 Pdfium 编译时启用了 OpenJPEG，可渲染 JPX/JBIG2 扫描图）。
 */
object PdfOcrEngine {

    /** 单次最多渲染的页数，避免超大 PDF 卡死 / OOM */
    private const val MAX_PAGES = 40

    /** 渲染位图最大边长（与图片 OCR 的 decodeSampled 上限保持一致） */
    private const val MAX_DIM = 2048

    data class PdfOcrResult(val text: String, val pages: Int)

    /** OCR/渲染失败的具体原因，供 UI 精确提示，不再含糊说「OCR 引擎无法识别」。 */
    enum class FailReason {
        OK,            // 成功
        OCR_DISABLED,  // ML Kit 未就绪（ocrEnabled=false）
        RENDER_BLANK,  // 系统渲染出空白页（疑似 JPX/JBIG2 扫描图）
        RENDER_FAILED, // 系统 PdfRenderer 打开/渲染抛异常
        PDFIUM_BLANK,  // 备用 Pdfium 也渲染出空白页
        PDFIUM_FAILED, // 备用 Pdfium 抛异常
        OCR_EMPTY      // 页面已渲染出内容，但 ML Kit 未识别到任何文字
    }

    /** 最近一次失败原因（extractText 返回 null 时有效）。 */
    @Volatile var lastFailReason: FailReason = FailReason.OK
        private set

    /**
     * 用 OCR 方式从 PDF 提取文字。
     * 优先系统 PdfRenderer；若渲染空白/失败（扫描件 JPX/JBIG2）则回退 PdfiumAndroid。
     * @return 识别到的纯文本 + 页数；任何失败 / 无文字返回 null（原因见 [lastFailReason]）
     */
    fun extractText(context: Context, file: File): PdfOcrResult? {
        lastFailReason = FailReason.OK
        if (!OcrEngine.ocrEnabled) {
            Log.w("WordCount", "PdfOcr 跳过: ocrEnabled=false (ML Kit 未就绪)")
            lastFailReason = FailReason.OCR_DISABLED
            return null
        }
        // 1) 系统 PdfRenderer（轻量）
        val sys = renderWithSystem(file)
        if (sys != null) return sys
        // 2) 回退 PdfiumAndroid（支持 JPX/JBIG2 扫描图）
        val pdfium = renderWithPdfium(context, file)
        if (pdfium != null) return pdfium
        return null
    }

    // ───────────────────────── 系统 PdfRenderer ─────────────────────────

    private fun renderWithSystem(file: File): PdfOcrResult? {
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(系统) 打开文件失败 ${file.name}: ${e.message}")
            return null
        }
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(系统) 创建 Renderer 失败 ${file.name}: ${e.message}")
            runCatching { pfd.close() }
            lastFailReason = FailReason.RENDER_FAILED
            return null
        }
        return try {
            val pageCount = renderer.pageCount
            Log.d("WordCount", "PdfOcr(系统) ${file.name}: pageCount=$pageCount")
            val sb = StringBuilder()
            val limit = min(pageCount, MAX_PAGES)
            var anyRenderedContent = false
            var anyOcrText = false
            for (i in 0 until limit) {
                val page = try { renderer.openPage(i) } catch (_: Throwable) { continue }
                try {
                    val w = page.width
                    val h = page.height
                    if (w <= 0 || h <= 0) continue
                    val scale = computeScale(w, h)
                    val bw = max(1, (w * scale).toInt())
                    val bh = max(1, (h * scale).toInt())
                    val bmp = try {
                        Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    } catch (e: Throwable) {
                        Log.w("WordCount", "PdfOcr(系统) 创建 Bitmap 失败 ${file.name}: ${e.message}")
                        continue
                    }
                    try {
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        if (isBlankBitmap(bmp)) {
                            Log.w("WordCount", "PdfOcr(系统) 第${i + 1}页渲染为空白页（疑似 JPX/JBIG2 扫描图）")
                            continue
                        }
                        anyRenderedContent = true
                        val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                        if (t.isNotBlank()) { sb.append(t).append('\n'); anyOcrText = true }
                    } finally {
                        bmp.recycle()
                    }
                } finally {
                    page.close()
                }
            }
            val text = sb.toString().trim()
            Log.d("WordCount", "PdfOcr(系统) ${file.name}: ocrSuccessPages=${if (anyOcrText) 1 else 0}/$limit, textLen=${text.length}")
            if (text.isBlank()) {
                lastFailReason = if (anyRenderedContent) FailReason.OCR_EMPTY else FailReason.RENDER_BLANK
                null
            } else PdfOcrResult(text, pageCount)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(系统) 处理异常 ${file.name}: ${e.message}")
            lastFailReason = FailReason.RENDER_FAILED
            null
        } finally {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    // ───────────────────────── 备用 PdfiumAndroid ─────────────────────────

    private fun renderWithPdfium(context: Context, file: File): PdfOcrResult? {
        val core = try {
            PdfiumCore(context)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(Pdfium) 初始化失败: ${e.message}")
            return null
        }
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(Pdfium) 打开文件失败 ${file.name}: ${e.message}")
            return null
        }
        val doc = try {
            core.newDocument(pfd)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(Pdfium) newDocument 失败 ${file.name}: ${e.message}")
            runCatching { pfd.close() }
            lastFailReason = FailReason.PDFIUM_FAILED
            return null
        }
        return try {
            val pageCount = core.getPageCount(doc)
            Log.d("WordCount", "PdfOcr(Pdfium) ${file.name}: pageCount=$pageCount")
            val sb = StringBuilder()
            val limit = min(pageCount, MAX_PAGES)
            var anyRenderedContent = false
            var anyOcrText = false
            for (i in 0 until limit) {
                try { core.openPage(doc, i) } catch (_: Throwable) { continue }
                try {
                    val size: Size = core.getPageSize(doc, i)
                    val w = size.width
                    val h = size.height
                    if (w <= 0 || h <= 0) continue
                    val scale = computeScale(w, h)
                    val bw = max(1, (w * scale).toInt())
                    val bh = max(1, (h * scale).toInt())
                    val bmp = try {
                        Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    } catch (e: Throwable) {
                        Log.w("WordCount", "PdfOcr(Pdfium) 创建 Bitmap 失败: ${e.message}")
                        continue
                    }
                    try {
                        core.renderPageBitmap(doc, bmp, i, 0, 0, bw, bh)
                        if (isBlankBitmap(bmp)) {
                            Log.w("WordCount", "PdfOcr(Pdfium) 第${i + 1}页渲染为空白页")
                            continue
                        }
                        anyRenderedContent = true
                        val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                        if (t.isNotBlank()) { sb.append(t).append('\n'); anyOcrText = true }
                    } finally {
                        bmp.recycle()
                    }
                } finally {
                    // 页面随 closeDocument(doc) 统一释放（PdfiumAndroid 1.9.0 无独立 closePage 可用签名）
                }
            }
            val text = sb.toString().trim()
            Log.d("WordCount", "PdfOcr(Pdfium) ${file.name}: ocrSuccessPages=${if (anyOcrText) 1 else 0}/$limit, textLen=${text.length}")
            if (text.isBlank()) {
                lastFailReason = if (anyRenderedContent) FailReason.OCR_EMPTY else FailReason.PDFIUM_BLANK
                null
            } else PdfOcrResult(text, pageCount)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(Pdfium) 处理异常 ${file.name}: ${e.message}")
            lastFailReason = FailReason.PDFIUM_FAILED
            null
        } finally {
            runCatching { core.closeDocument(doc) }
            runCatching { pfd.close() }
        }
    }

    /** 判断位图是否近似全白（扫描件渲染失败时整页空白）。抽样像素统计非白比例。 */
    private fun isBlankBitmap(bmp: Bitmap): Boolean {
        return try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return true
            val stepX = max(1, w / 32)
            val stepY = max(1, h / 32)
            var nonWhite = 0
            var samples = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val px = bmp.getPixel(x, y)
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    samples++
                    // 非白（允许轻微灰度误差）
                    if (r < 248 || g < 248 || b < 248) nonWhite++
                    x += stepX
                }
                y += stepY
            }
            if (samples == 0) return true
            // 非白像素占比极低 → 视为空白页
            (nonWhite.toDouble() / samples) < 0.005
        } catch (_: Throwable) {
            false
        }
    }

    /** 计算渲染缩放：原生尺寸已 <= MAX_DIM 则 1x，否则等比缩放到上限 */
    private fun computeScale(w: Int, h: Int): Float {
        val maxSide = max(w, h)
        return if (maxSide <= MAX_DIM) 1f else MAX_DIM.toFloat() / maxSide
    }
}
