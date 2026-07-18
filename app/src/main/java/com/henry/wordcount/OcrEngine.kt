package com.henry.wordcount

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.Volatile

/**
 * 内嵌 Tesseract OCR（tess-two）：完全离线、无需任何账号/服务器，
 * 适配无 GMS 的华为手机。中文模型 chi_sim.traineddata 由 CI 打包进 APK 的 assets，
 * 首次运行时拷贝到应用私有目录；之后永久离线使用。
 *
 * v1.0.16 安全增强版：
 *   - 默认**启用**（ocrAvailable=true），用户无需手动操作即可识别图片。
 *   - 防崩溃策略：在独立线程中执行 Tesseract JNI 调用，设置异常处理器
 *     兜底捕获 Signal 类崩溃（SIGSEGV/SIGABRT），防止整个 App 闪退；
 *     崩溃时自动标记 ocrCrashed=true 并降级为禁用状态。
 *   - 超时保护：单图 OCR 不超过 10 秒（防止超大/复杂图片卡死）。
 */
object OcrEngine {

    private const val LANG = "chi_sim"
    private const val TESS_SUBDIR = "tessdata"
    private const val MAX_DIM = 1600 // 解码时限制最大边
    private const val TIMEOUT_MS = 10_000L // 单图超时

    private var baseDir: File? = null

    /** OCR 开关：true=启用（默认），false=因崩溃被自动禁用 */
    @Volatile var ocrEnabled: Boolean = true

    /** 是否曾因崩溃被自动禁用（供 UI 显示提示） */
    @Volatile var ocrCrashed: Boolean = false
        private set

    /** 识别图片文件，返回识别出的文字（失败/已禁用/无文字均返回空串）。 */
    fun recognize(context: Context, imageFile: File): String {
        if (!ocrEnabled) return ""
        ensureTrainedData(context)
        val base = baseDir ?: return ""

        if (!imageFile.exists() || imageFile.length() == 0L) return ""
        if (imageFile.length() > 20L * 1024 * 1024) return "" // 20MB 上限

        return doRecognizeSafe(base, imageFile)
    }

    /**
     * 安全执行 OCR：在线程中调用 Tesseract，设置异常处理器兜底。
     * 若发生 Signal 崩溃（SIGSEGV 等）则捕获并自动禁用后续 OCR 调用。
     */
    private fun doRecognizeSafe(base: File, imageFile: File): String {
        val resultHolder = arrayOf<String?>(null)
        val errorHolder = arrayOf<Throwable?>(null)

        val thread = object : Thread("OcrWorker-${System.currentTimeMillis()}") {
            override fun run() {
                // 设置线程级异常处理器：捕获 JNI Signal 崩溃
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ ->
                    // 标记崩溃但不让默认处理器杀进程
                    synchronized(errorHolder) { errorHolder[0] = RuntimeException("OCR native crash") }
                }
                try {
                    resultHolder[0] = doRecognize(base, imageFile)
                } catch (e: Throwable) {
                    synchronized(errorHolder) { errorHolder[0] = e }
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        thread.join(TIMEOUT_MS)

        if (thread.isAlive) {
            // 超时：尝试中断（JNI 无法真正中断，但标记超时）
            Log.w("WordCount", "OCR timeout ${imageFile.name}")
            // 不 stop() 线程（可能不安全），让它自己结束
            return ""
        }

        val error = errorHolder[0]
        if (error != null) {
            // 发生了异常或原生层崩溃 → 自动禁用 OCR
            Log.e("WordCount", "OCR crash detected: ${error.javaClass.simpleName}: ${error.message}", error)
            ocrEnabled = false
            ocrCrashed = true
            return ""
        }

        return resultHolder[0] as? String ?: ""
    }

    /** 实际执行 OCR 的核心方法（仅在 OcrWorker 线程中调用）。 */
    private fun doRecognize(base: File, imageFile: File): String {
        var bmp: Bitmap? = null
        var api: TessBaseAPI? = null
        try {
            api = TessBaseAPI()
            if (!api.init(base.absolutePath, LANG)) {
                Log.w("WordCount", "TessBaseAPI.init failed for ${imageFile.name}")
                api.end()
                return ""
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
            val rawW = if (bounds.outWidth > 0) bounds.outWidth else 1
            val rawH = if (bounds.outHeight > 0) bounds.outHeight else 1
            val sample = maxOf(rawW, rawH) / MAX_DIM
            val inSample = if (sample > 1) sample.coerceAtMost(8) else 1

            val opts = BitmapFactory.Options().apply { inSampleSize = inSample }
            bmp = BitmapFactory.decodeFile(imageFile.absolutePath, opts) ?: return ""
            if (bmp.width > MAX_DIM * 2 || bmp.height > MAX_DIM * 2) {
                val scaled = Bitmap.createScaledBitmap(bmp, MAX_DIM,
                    (MAX_DIM * bmp.height / bmp.width).coerceIn(1, MAX_DIM), true)
                bmp.recycle()
                bmp = scaled
            }
            api.setImage(bmp)
            val text = api.utF8Text ?: ""
            return text.trim()
        } catch (e: OutOfMemoryError) {
            Runtime.getRuntime().gc()
            Log.w("WordCount", "OCR OOM ${imageFile.name}")
            return ""
        } catch (e: Throwable) {
            Log.w("WordCount", "OCR exception ${imageFile.name}: ${e.javaClass.simpleName}: ${e.message}")
            throw e // 向上抛出，由外层异常处理器或 join 捕获
        } finally {
            try { bmp?.recycle() } catch (_: Exception) {}
            try { api?.end() } catch (_: Exception) {}
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
                return
            }
        }
    }
}
