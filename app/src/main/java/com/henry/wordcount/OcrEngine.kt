package com.henry.wordcount

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

/**
 * 内嵌 Tesseract OCR（tess-two）：完全离线、无需任何账号/服务器，
 * 适配无 GMS 的华为手机。中文模型 chi_sim.traineddata 由 CI 打包进 APK 的 assets，
 * 首次运行时拷贝到应用私有目录；之后永久离线使用。
 */
object OcrEngine {

    private const val LANG = "chi_sim"
    private const val TESS_SUBDIR = "tessdata"
    private const val MAX_DIM = 1600 // 解码时限制最大边，避免超大图触发 Tesseract 原生层 OOM/崩溃

    private var baseDir: File? = null

    /** 识别图片文件，返回识别出的文字（失败/无文字返回空串）。 */
    fun recognize(context: Context, imageFile: File): String {
        // 前置：确保训练数据就位；若拷贝失败则 baseDir 为 null，直接优雅降级
        ensureTrainedData(context)
        val base = baseDir ?: return ""

        val api = TessBaseAPI()
        if (!api.init(base.absolutePath, LANG)) {
            api.end()
            return ""
        }
        var bmp: Bitmap? = null
        try {
            // 先只读边界，按尺寸采样缩小，避免超大位图直接喂给 Tesseract 原生层导致崩溃
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
            val rawW = if (bounds.outWidth > 0) bounds.outWidth else 1
            val rawH = if (bounds.outHeight > 0) bounds.outHeight else 1
            val sample = maxOf(rawW, rawH) / MAX_DIM
            val inSample = if (sample > 1) sample.coerceAtMost(8) else 1

            val opts = BitmapFactory.Options().apply { inSampleSize = inSample }
            bmp = BitmapFactory.decodeFile(imageFile.absolutePath, opts) ?: return ""
            api.setImage(bmp)
            val text = api.utF8Text ?: ""
            return text
        } finally {
            bmp?.recycle()
            api.end()
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
