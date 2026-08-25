package com.henry.wordcount

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v1.9.39: DWG 内嵌 IMAGE 实体 OCR。
 *
 * 对齐桌面 wordcount.py `_extract_cad_rendered_via_ocr` 中导出 IMAGE + OCR 的口径。
 * cad_core 端已用 ezdxf 把 IMAGE.embedded_image 导出为 PNG 落盘到 outDir；
 * 本类读取 PNG 列表，调 OcrEngine（Google ML Kit 中文识别器）对每张识别，
 * 合并所有识别文本返回。OcrEngine 自带纯英文图的 CJK 幻觉剔除。
 *
 * 异常隔离：单张失败不影响整体。识别慢/大图多时 OcrEngine 单次超时 20s；
 * 整批走 IO 线程，调用方应放后台协程。
 */
object DwgImageOcrExtractor {

    private const val TAG = "DwgImgOcr"
    private const val MAX_IMAGES_PER_FILE = 20

    data class ImageOcrResult(
        val text: String,        // 合并后的 OCR 文本（按行去重）
        val imagesScanned: Int,  // 实际处理的 PNG 数
        val ocrFailed: Int       // 识别失败/空图数
    )

    fun extract(context: Context, pngPaths: List<String>): ImageOcrResult {
        if (pngPaths.isEmpty()) return ImageOcrResult("", 0, 0)
        val allLines = LinkedHashSet<String>()
        var scanned = 0
        var failed = 0
        val max = minOf(pngPaths.size, MAX_IMAGES_PER_FILE)
        for (i in 0 until max) {
            val p = pngPaths[i]
            try {
                val f = File(p)
                if (!f.exists() || f.length() <= 0L) { failed++; continue }
                val raw = OcrEngine.recognize(context, f)
                scanned++
                if (raw.isBlank()) { failed++; continue }
                for (ln in raw.lines()) {
                    val t = ln.trim()
                    if (t.isNotEmpty() && t.length >= 1) allLines.add(t)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "IMAGE OCR 失败 $p: ${e.message}")
                failed++
            }
        }
        return ImageOcrResult(allLines.joinToString("\n"), scanned, failed)
    }
}
