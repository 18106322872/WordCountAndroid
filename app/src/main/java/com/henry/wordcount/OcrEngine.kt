package com.henry.wordcount

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OCR 引擎（v1.0.18）：基于 Google ML Kit Text Recognition。
 *
 * 相比 v1.0.16/1.0.17 使用的 Tesseract(tess-two)，ML Kit 是官方纯 Kotlin 接口、
 * 无 JNI 原生崩溃（SIGSEGV）风险，且原生支持中文，因此不会再出现图片闪退。
 *
 * 识别过程包在 try/catch 中：模型未就绪 / 设备不支持 / 超时 等情况只会返回空串并标记
 * ocrFailed，绝不会导致 App 崩溃。
 */
object OcrEngine {

    /** OCR 开关：ML Kit 稳定，默认开启 */
    @Volatile var ocrEnabled: Boolean = true

    /** 是否曾识别失败（供 UI 显示提示） */
    @Volatile var ocrFailed: Boolean = false
        private set

    /** 懒加载的中文文字识别器（同时兼容拉丁字母） */
    private val recognizer by lazy {
        TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
    }

    /**
     * 识别图片文件中的文字。
     * @return 识别到的文字；失败或空图返回空串。
     */
    fun recognize(context: Context, imageFile: File): String {
        if (!ocrEnabled) return ""
        return try {
            val bitmap = decodeSampled(imageFile, 2048) ?: return ""
            val image = InputImage.fromBitmap(bitmap, 0)
            // 在 IO 线程上阻塞等待结果（超时 20s，给模型首次加载留足时间）
            val visionText = Tasks.await(recognizer.process(image), 20, TimeUnit.SECONDS)
            visionText.text ?: ""
        } catch (e: Throwable) {
            Log.w("WordCount", "OCR 失败 ${imageFile.name}: ${e.javaClass.simpleName}: ${e.message}")
            ocrFailed = true
            ""
        }
    }

    /** 安全解码图片：按最大边长缩放，避免超大图 OOM。 */
    private fun decodeSampled(file: File, maxDim: Int): android.graphics.Bitmap? {
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
