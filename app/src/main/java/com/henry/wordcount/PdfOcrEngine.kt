package com.henry.wordcount

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 文本提取的 OCR 兜底引擎（v1.0.24）。
 *
 * 部分 PDF（尤其是扫描件 / 快拍类 App 导出的 PDF）文字层是「子集化 CID 字体 + 无 ToUnicode 映射」，
 * 纯文本解析无法把字形编码还原成 Unicode（只能得到乱码或空文本）。这类 PDF 只能靠
 * 把每一页渲染成图片后用 OCR 识别。
 *
 * 实现：android.graphics.pdf.PdfRenderer（系统自带，minSdk 26 已满足）把页面渲染成 Bitmap，
 *        再交给已有的 ML Kit 中文识别器（OcrEngine）逐页识别，拼合后返回纯文本 + 页数。
 * 该路径与图片 OCR 共用同一套识别与后处理，因此字数统计口径与图片、WORD 完全一致。
 */
object PdfOcrEngine {

    /** 单次最多渲染的页数，避免超大 PDF 卡死 / OOM */
    private const val MAX_PAGES = 40

    /** 渲染位图最大边长（与图片 OCR 的 decodeSampled 上限保持一致，兼顾识别率与内存） */
    private const val MAX_DIM = 2048

    data class PdfOcrResult(val text: String, val pages: Int)

    /**
     * 用 OCR 方式从 PDF 提取文字。
     * @return 识别到的纯文本 + 页数；任何失败 / 无文字返回 null
     */
    fun extractText(file: File): PdfOcrResult? {
        if (!OcrEngine.ocrEnabled) {
            Log.w("WordCount", "PdfOcr 跳过: ocrEnabled=false (ML Kit 未就绪)")
            return null
        }
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr 打开文件失败 ${file.name}: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr 创建 Renderer 失败 ${file.name}: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { pfd.close() }
            return null
        }
        return try {
            val pageCount = renderer.pageCount
            Log.d("WordCount", "PdfOcr ${file.name}: pageCount=$pageCount")
            val sb = StringBuilder()
            val limit = min(pageCount, MAX_PAGES)
            var ocrSuccessPages = 0
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
                        Log.w("WordCount", "PdfOcr 创建 Bitmap 失败 ${file.name}: ${e.message}")
                        continue
                    }
                    try {
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                        if (t.isNotBlank()) { sb.append(t).append('\n'); ocrSuccessPages++ }
                    } finally {
                        bmp.recycle()
                    }
                } finally {
                    page.close()
                }
            }
            val text = sb.toString().trim()
            Log.d("WordCount", "PdfOcr ${file.name}: ocrSuccessPages=$ocrSuccessPages/$limit, textLen=${text.length}")
            if (text.isBlank()) null else PdfOcrResult(text, pageCount)
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr 处理异常 ${file.name}: ${e.message}")
            null
        } finally {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    /** 计算渲染缩放：原生尺寸已 <= MAX_DIM 则 1x，否则等比缩放到上限 */
    private fun computeScale(w: Int, h: Int): Float {
        val maxSide = max(w, h)
        return if (maxSide <= MAX_DIM) 1f else MAX_DIM.toFloat() / maxSide
    }
}
