package com.henry.wordcount

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * v1.9.39: DWG 内嵌 IMAGE 实体 OCR。
 *
 * 对齐桌面 wordcount.py `_extract_cad_rendered_via_ocr` 中导出 IMAGE + OCR 的口径。
 * cad_core 端已用 ezdxf 把 IMAGE.embedded_image 导出为 PNG 落盘到 outDir；
 * 本类读取 PNG 列表，对每张识别，合并所有识别文本返回。
 *
 * v1.9.53: 主引擎改为 PaddleOCR（与桌面 RapidOCR 同宗，PP-OCRv4），保留全部字符（含中文），
 * 不再走 ML Kit + postFilter 剔除 CJK —— 这是 FA-00003 等「全图 DWG」字数远少于桌面（移动 485 vs 桌面 5126）
 * 的根因：ML Kit 中文识别器对英文/数字图识别率弱、且 postFilter 把真实中文误剔。
 * PaddleOCR.available==false（模型缺失）时自动回退 ML Kit，零回归。
 *
 * 异常隔离：单张失败不影响整体；整批走 IO 线程，调用方应放后台协程。
 */
object DwgImageOcrExtractor {

    private const val TAG = "DwgImgOcr"
    private const val MAX_IMAGES_PER_FILE = 20
    private const val MAX_DIM = 2048   // 解码限幅，避免超大内嵌图 OOM

    data class ImageOcrResult(
        val text: String,        // 合并后的 OCR 文本（按行去重）
        val imagesScanned: Int,  // 实际处理的 PNG 数
        val ocrFailed: Int       // 识别失败/空图数
    )

    fun extract(context: Context, pngPaths: List<String>): ImageOcrResult {
        if (pngPaths.isEmpty()) return ImageOcrResult("", 0, 0)
        PaddleOcr.ensureInit(context)
        val usePaddle = PaddleOcr.available
        val allLines = LinkedHashSet<String>()
        var scanned = 0
        var failed = 0
        val max = minOf(pngPaths.size, MAX_IMAGES_PER_FILE)
        for (i in 0 until max) {
            val p = pngPaths[i]
            try {
                val f = File(p)
                if (!f.exists() || f.length() <= 0L) { failed++; continue }
                val raw = if (usePaddle) {
                    val bmp = decodeSampled(f, MAX_DIM)
                    if (bmp == null) { failed++; continue }
                    val r = PaddleOcr.recognize(bmp)
                    bmp.recycle()
                    r
                } else {
                    OcrEngine.recognize(context, f)
                }
                scanned++
                if (raw.isNullOrBlank()) { failed++; continue }
                for (ln in raw.lines()) {
                    val t = ln.trim()
                    if (t.isNotEmpty()) allLines.add(t)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "IMAGE OCR 失败 $p: ${e.message}")
                failed++
            }
        }
        return ImageOcrResult(allLines.joinToString("\n"), scanned, failed)
    }

    /** 安全解码图片：按最长边缩放，避免超大图 OOM（与 OcrEngine.decodeSampled 一致）。 */
    private fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) return null
            val scale = maxOf(1, maxOf(w, h) / maxDim)
            val opts2 = BitmapFactory.Options().apply { inSampleSize = scale }
            BitmapFactory.decodeFile(file.absolutePath, opts2)
        } catch (_: Throwable) { null }
    }
}
