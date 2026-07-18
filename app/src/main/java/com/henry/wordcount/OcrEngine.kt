package com.henry.wordcount

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OCR 引擎（v1.0.18+）：基于 Google ML Kit Text Recognition。
 *
 * 相比 v1.0.16/1.0.17 使用的 Tesseract(tess-two)，ML Kit 是官方纯 Kotlin 接口、
 * 无 JNI 原生崩溃（SIGSEGV）风险，且原生支持中文，因此不会再出现图片闪退。
 *
 * 中英文混合识别策略（v1.0.19 修复「纯英文图被误识别出中文」）：
 *   - 先用拉丁识别器（对英文/数字极准，不会产生中文幻觉）。
 *   - 若拉丁结果含 ≥5 个拉丁字母 → 直接采用拉丁结果（纯英文/数字图，避免中文模型幻觉）。
 *   - 否则再用中文识别器；若中文结果里中文占比 > 20% → 采用中文结果（真实中文图，
 *     中文识别器同时能识别其中的拉丁部分）；否则退回拉丁结果（少量误识中文被丢弃）。
 * 这样纯英文图只出英文、中文图正常出中文，混合图也尽量兼顾。
 */
object OcrEngine {

    /** OCR 开关：ML Kit 稳定，默认开启 */
    @Volatile var ocrEnabled: Boolean = true

    /** 是否曾识别失败（供 UI 显示提示） */
    @Volatile var ocrFailed: Boolean = false
        private set

    /** 中文识别器（同时兼容拉丁字母） */
    private val chineseRecognizer by lazy {
        TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
    }

    /** 拉丁识别器（英文/数字，不幻觉中文） */
    private val latinRecognizer by lazy {
        TextRecognition.getClient()
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

            // 1) 先用拉丁识别器（快、准、无中文幻觉）
            val latinText = tryRecognize(latinRecognizer, image, 15)
            val latinLetters = latinText.count { it.isLetter() && it.code < 0x2E80 }
            if (latinLetters >= 5) {
                // 明显是英文/拉丁内容，直接采用，避免中文模型把英文误读成中文
                return latinText
            }

            // 2) 拉丁不足 → 用中文识别器（它也能识别拉丁部分）
            val chineseText = tryRecognize(chineseRecognizer, image, 20)
            val cjkCount = chineseText.count { it.isCjkChar() }
            val cjkRatio = if (chineseText.isBlank()) 0f else cjkCount.toFloat() / chineseText.length
            if (cjkRatio > 0.2f) {
                // 中文占比高 → 真实中文图像，采用中文识别器结果
                chineseText
            } else {
                // 中文占比低（多为拉丁/数字，可能含少量误识中文）→ 退回更准的拉丁结果
                if (latinText.isNotBlank()) latinText else chineseText
            }
        } catch (e: Throwable) {
            Log.w("WordCount", "OCR 失败 ${imageFile.name}: ${e.javaClass.simpleName}: ${e.message}")
            ocrFailed = true
            ""
        }
    }

    private fun tryRecognize(recognizer: TextRecognizer, image: InputImage, timeoutSec: Long): String {
        return try {
            val visionText = Tasks.await(recognizer.process(image), timeoutSec, TimeUnit.SECONDS)
            visionText.text ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    /** 判断是否为 CJK / 日文假名等表意文字 */
    private fun Char.isCjkChar(): Boolean {
        val c = code
        return c in 0x3040..0x30FF || // 假名
                c in 0x4E00..0x9FFF ||
                c in 0x3400..0x4DBF ||
                c in 0x3000..0x303F ||
                c in 0xFF00..0xFFEF ||
                c in 0x2E80..0x2EFF ||
                c in 0xF900..0xFAFF
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
