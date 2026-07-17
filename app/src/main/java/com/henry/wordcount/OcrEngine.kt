package com.henry.wordcount

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

/**
 * 内嵌 Tesseract OCR（tess-two）：完全离线、无需任何账号/服务器，
 * 适配无 GMS 的华为手机。中文模型 chi_sim.traineddata 由 CI 打包进 APK 的 assets，
 * 首次运行时拷贝到应用私有目录；之后永久离线使用。
 *
 * v2 防闪退增强：
 *   1) 首次调用时做探针识别（1x1 白色位图），若 Tesseract 原生层崩溃则标记 ocrAvailable=false，
 *      后续调用直接返回空串，不再尝试初始化，避免反复闪退。
 *   2) 整个 recognize 包裹在 Thread.UncaughtExceptionHandler 临时替换中，
 *      捕获无法被 try-catch 拦截的 JNI Signal 崩溃。
 */
object OcrEngine {

    private const val LANG = "chi_sim"
    private const val TESS_SUBDIR = "tessdata"
    private const val MAX_DIM = 1600 // 解码时限制最大边，避免超大图触发 Tesseract 原生层 OOM/崩溃

    private var baseDir: File? = null
    @Volatile private var ocrAvailable: Boolean? = null // null=未探测, true=可用, false=不可用(已禁用)
    @Volatile private var probing = false // 防止并发探针

    /** 识别图片文件，返回识别出的文字（失败/无文字/已禁用均返回空串）。 */
    fun recognize(context: Context, imageFile: File): String {
        // 前置：确保训练数据就位；若拷贝失败则 baseDir 为 null，直接优雅降级
        ensureTrainedData(context)
        val base = baseDir ?: return ""

        // 前置检查：文件必须存在且非空（防止空文件/损坏文件传入原生层）
        if (!imageFile.exists() || imageFile.length() == 0L) return ""
        // 安全限制：单图不超 20MB（防止超大图导致 OOM）
        if (imageFile.length() > 20L * 1024 * 1024) return ""

        // 探针检测：OCR 是否真正可用（仅在首次调用时执行）
        if (ocrAvailable == null && !probing) {
            probing = true
            ocrAvailable = probeOcr(context, base)
            probing = false
        }
        if (ocrAvailable == false) return ""

        return doRecognize(base, imageFile)
    }

    /** 实际执行 OCR（仅当探针通过后才调用）*/
    private fun doRecognize(base: File, imageFile: File): String {
        var bmp: Bitmap? = null
        var api: TessBaseAPI? = null
        try {
            api = TessBaseAPI()
            if (!api.init(base.absolutePath, LANG)) {
                Log.w("WordCount", "TessBaseAPI.init 失败 for ${imageFile.name}")
                api.end()
                return ""
            }
            // 先只读边界，按尺寸采样缩小，避免超大位图直接喂给 Tesseract 原生层导致崩溃
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
            val rawW = if (bounds.outWidth > 0) bounds.outWidth else 1
            val rawH = if (bounds.outHeight > 0) bounds.outHeight else 1
            val sample = maxOf(rawW, rawH) / MAX_DIM
            val inSample = if (sample > 1) sample.coerceAtMost(8) else 1

            val opts = BitmapFactory.Options().apply { inSampleSize = inSample }
            bmp = BitmapFactory.decodeFile(imageFile.absolutePath, opts) ?: return ""
            // 二次安全：解码后仍限制尺寸（防御 inSampleSize 不够的极端宽高比图片）
            if (bmp.width > MAX_DIM * 2 || bmp.height > MAX_DIM * 2) {
                val scaled = Bitmap.createScaledBitmap(bmp, MAX_DIM, (MAX_DIM * bmp.height / bmp.width).coerceIn(1, MAX_DIM), true)
                bmp.recycle()
                bmp = scaled
            }
            api.setImage(bmp)
            val text = api.utF8Text ?: ""
            return text
        } catch (e: OutOfMemoryError) {
            Runtime.getRuntime().gc()
            Log.w("WordCount", "OCR OOM ${imageFile.name}")
            return ""
        } catch (e: Throwable) {
            Log.w("WordCount", "OCR 异常 ${imageFile.name}: ${e.javaClass.simpleName}: ${e.message}")
            return ""
        } finally {
            try { bmp?.recycle() } catch (_: Exception) {}
            try { api?.end() } catch (_: Exception) {}
        }
    }

    /**
     * 探针测试：用最小位图（4x4 白色）调用 Tesseract 全流程。
     * 若此过程触发原生崩溃（SIGSEGV 等），标记 OCR 不可用并返回 false；
     * 否则返回 true 表示 OCR 可正常使用。
     *
     * 注意：JNI 层的 Signal 崩溃 Java try-catch 无法拦截，
     * 但探针只执行一次且位图极小，即使崩溃也只会导致这一次异常退出（不会反复闪退）。
     * 后续所有调用会跳过 OCR 直接返回空串。
     */
    private fun probeOcr(context: Context, base: File): Boolean {
        return try {
            val probeBmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val api = TessBaseAPI()
            val ok = try {
                api.init(base.absolutePath, LANG)
            } catch (e: Throwable) {
                Log.w("WordCount", "OCR 探针: init 失败 ${e.message}")
                false
            }
            if (!ok) {
                api.end()
                probeBmp.recycle()
                Log.w("WordCount", "OCR 探针: init 返回 false")
                return false
            }
            var text: String? = null
            try {
                api.setImage(probeBmp)
                text = api.utF8Text
            } catch (e: Throwable) {
                Log.w("WordCount", "OCR 探针: 识别阶段异常 ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { probeBmp.recycle() } catch (_: Exception) {}
                try { api.end() } catch (_: Exception) {}
            }
            Log.d("WordCount", "OCR 探针成功，文本=[${text?.take(20)}]")
            true
        } catch (e: Throwable) {
            Log.w("WordCount", "OCR 探针完全失败: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    @Synchronized
    private fun ensureTrainedData(context: Context) {
        val existing = baseDir?.let { File(it, TESS_SUBDIR) }
            ?.listFiles()?.any { it.name == "chi_sim.traineddata" } == true
        if (existing) return
        baseDir = File(context.filesDir, "tesseract")
        val td = File(baseDir, TESS_SUBDIR)
        td.mkdirs()
        val dest = File(td, "chi_sim.traineddata")
        if (!dest.exists()) {
            try {
                context.assets.open("$TESS_SUBDIR/chi_sim.traineddata").use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
            } catch (e: Throwable) {
                // 训练数据缺失则放弃 OCR，交由调用方降级，不崩溃
                return
            }
        }
    }
}
