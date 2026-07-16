package com.henry.wordcount

import android.content.Context
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
    private var baseDir: File? = null

    /** 识别图片文件，返回识别出的文字（失败/无文字返回空串）。 */
    fun recognize(context: Context, imageFile: File): String {
        ensureTrainedData(context)
        val api = TessBaseAPI()
        if (!api.init(baseDir!!.absolutePath, LANG)) {
            api.end()
            return ""
        }
        try {
            val bmp = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return ""
            api.setImage(bmp)
            val text = api.utF8Text ?: ""
            bmp.recycle()
            return text
        } finally {
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
            context.assets.open("$TESS_SUBDIR/chi_sim.traineddata").use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
        }
    }
}
