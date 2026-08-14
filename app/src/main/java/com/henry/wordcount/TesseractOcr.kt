package com.henry.wordcount

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

/**
 * 方案 C 的"强引擎"实现：Tesseract（tess-two）作为 ML Kit 的高召回兜底。
 *
 * 设计要点：
 *  - 实现 PdfOcrEngine.StrongOcr 接口，后续可插拔替换为 PaddleOCR-mobile（同一接口，不返工）。
 *  - 仅在 ML Kit 主路径召回偏低 / 全空时由 PdfOcrEngine 调用，绝不污染 ML Kit 现有好结果。
 *  - 模型 chi_sim+eng 由 CI 下载进 app/src/main/assets/tessdata/，首次运行拷贝到 filesDir/tessdata/。
 *  - 若模型缺失，available=false，PdfOcrEngine 自动退回纯 ML Kit（零回归，构建不依赖模型存在）。
 *  - 识别前做 灰度 + 自动对比度拉伸（Tesseract 对工程图小字极依赖预处理）。
 */
object TesseractOcr : StrongOcr {

    @Volatile override var available: Boolean = false
        private set

    @Volatile private var initTried = false
    @Volatile private var baseApi: TessBaseAPI? = null
    private val lock = Any()

    /** 首次调用时初始化：从 assets 拷贝模型到 filesDir/tessdata/ 并 init TessBaseAPI。 */
    fun ensureInit(context: Context) {
        if (initTried) return
        synchronized(lock) {
            if (initTried) return
            initTried = true
            try {
                val assetDir = File(context.filesDir, "tessdata")
                assetDir.mkdirs()
                val am = context.assets
                val names = am.list("tessdata") ?: arrayOf()
                var any = false
                for (name in names) {
                    if (!name.endsWith(".traineddata")) continue
                    val dst = File(assetDir, name)
                    val src = am.open("tessdata/$name")
                    val tmp = File(assetDir, name + ".tmp")
                    try {
                        FileOutputStream(tmp).use { out -> src.copyTo(out) }
                    } finally {
                        try { src.close() } catch (_: Throwable) {}
                    }
                    if (!dst.exists() || tmp.length() != dst.length()) {
                        if (!tmp.renameTo(dst)) { tmp.delete() }
                    } else {
                        tmp.delete()
                    }
                    any = true
                }
                if (!any) { available = false; return }
                val api = TessBaseAPI()
                // datapath 必须是包含 tessdata/ 的父目录（即 filesDir）
                val ok = api.init(context.filesDir.absolutePath, "chi_sim+eng")
                if (!ok) {
                    try { api.end() } catch (_: Throwable) {}
                    available = false
                    return
                }
                baseApi = api
                available = true
            } catch (t: Throwable) {
                available = false
                baseApi = null
            }
        }
    }

    /** 识别位图；返回识别文本，失败/未就绪返回 null。 */
    override fun recognize(bitmap: Bitmap): String? {
        val api = baseApi ?: return null
        val prep = try { preprocess(bitmap) } catch (_: Throwable) { return null }
        return try {
            val txt = synchronized(lock) {
                api.setImage(prep)
                val t = api.getUTF8Text()
                api.clear()
                t
            }
            txt
        } catch (_: Throwable) {
            try { baseApi?.clear() } catch (_: Throwable) {}
            null
        } finally {
            prep.recycle()
        }
    }

    /**
     * 灰度 + 自动对比度拉伸（min-max）。工程图常低对比、灰底，拉伸后 Tesseract 召回显著提升。
     * 不做硬二值化，保留灰阶信息（Tesseract 自带 Otsu 二值化）。
     */
    private fun preprocess(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(px.size)
        var minL = 255
        var maxL = 0
        for (i in px.indices) {
            val r = (px[i] shr 16) and 0xFF
            val g = (px[i] shr 8) and 0xFF
            val b = px[i] and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            gray[i] = lum
            if (lum < minL) minL = lum
            if (lum > maxL) maxL = lum
        }
        val range = (maxL - minL).coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPx = IntArray(px.size)
        for (i in gray.indices) {
            val v = ((gray[i] - minL) * 255.0 / range).toInt().coerceIn(0, 255)
            outPx[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    fun dispose() {
        synchronized(lock) {
            try { baseApi?.end() } catch (_: Throwable) {}
            baseApi = null
            available = false
        }
    }
}
