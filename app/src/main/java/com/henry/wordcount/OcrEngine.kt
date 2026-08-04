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
 * OCR 引擎（v1.0.18+）：基于 Google ML Kit Text Recognition（中文识别器，同时兼容拉丁字母）。
 *
 * ML Kit 的 Latin 识别器选项类（TextRecognizerOptions）在本项目的依赖图谱下无法稳定参与编译
 * （16.0.0 改为 Play Services 动态分发，AAR 内不含该类），因此统一使用「中文识别器」，
 * 并通过后处理解决「纯英文图被误识别出中文」的问题（v1.0.19 修复）：
 *   - 用中文识别器识别整张图（它对中英文都能识别）。
 *   - 若结果中「真实 CJK 表意文字（汉字/假名）」占比很低（<15%），说明这是英文/数字图，
 *     里面零星出现的中文是模型幻觉 → 直接剔除所有 CJK 字符，只保留拉丁/数字，得到干净英文。
 *   - 若 CJK 占比高 → 是真实中文图，原样保留（中文识别器对其中拉丁部分也能正确识别）。
 * 这样既保证英文图只出英文，又保证中文图正常出中文，且只需一个能稳定编译的识别器。
 */
object OcrEngine {

    /** OCR 开关：ML Kit 稳定，默认开启 */
    @Volatile var ocrEnabled: Boolean = true

    /** 是否曾识别失败（供 UI 显示提示） */
    @Volatile var ocrFailed: Boolean = false
        private set

    /** 中文识别器（同时兼容拉丁字母） */
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
            val raw = tryRecognize(image, 20)
            if (raw.isBlank()) return ""
            postFilter(raw)
        } catch (e: Throwable) {
            Log.w("WordCount", "OCR 失败 ${imageFile.name}: ${e.javaClass.simpleName}: ${e.message}")
            ocrFailed = true
            ""
        }
    }

    /**
     * 识别 Bitmap 中的文字（供 PDF 渲染后逐页识别复用）。
     * @param skipPostFilter 是否跳过后处理（PDF OCR 场景设为 true，保留全部识别结果包括中文）
     * @return 识别到的文字；失败或空图返回空串。
     */
    fun recognizeBitmap(bitmap: android.graphics.Bitmap, skipPostFilter: Boolean = false): String {
        if (!ocrEnabled) return ""
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val raw = tryRecognize(image, 20)
            if (raw.isBlank()) return ""
            if (skipPostFilter) raw else postFilter(raw)
        } catch (e: Throwable) {
            Log.w("WordCount", "OCR(Bitmap) 失败: ${e.javaClass.simpleName}: ${e.message}")
            ocrFailed = true
            ""
        }
    }

    private fun tryRecognize(image: InputImage, timeoutSec: Long): String {
        return try {
            val visionText = Tasks.await(recognizer.process(image), timeoutSec, TimeUnit.SECONDS)
            visionText.text ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * 后处理：剔除英文图中的中文幻觉。
     * 仅统计「表意文字」（汉字/假名）占比来决定；中文标点不计入占比判断，但一律会被剔除。
     */
    private fun postFilter(text: String): String {
        var ideo = 0
        for (c in text) if (c.isIdeograph()) ideo++
        val ratio = if (text.isEmpty()) 0f else ideo.toFloat() / text.length
        return if (ratio > 0.15f) {
            text // 真实中文/含中文 → 原样保留
        } else {
            text.filter { !it.isCjkChar() } // 英文为主 → 去掉所有 CJK 字符（含中文标点），保留拉丁/数字
        }
    }

    /** 是否为 CJK / 日文假名等表意文字或全角/符号（统一视为“中文相关字符”，用于剔除） */
    private fun Char.isCjkChar(): Boolean {
        val c = code
        return c in 0x3000..0x303F || // CJK 符号与标点
                c in 0x3040..0x30FF || // 假名
                c in 0x3400..0x4DBF ||
                c in 0x4E00..0x9FFF ||
                c in 0x2E80..0x2EFF ||
                c in 0xF900..0xFAFF ||
                c in 0xFF00..0xFFEF    // 全角字母/数字/符号
    }

    /** 是否为真正的表意文字（汉字/假名），用于判断是否“含中文”，不含标点 */
    private fun Char.isIdeograph(): Boolean {
        val c = code
        return c in 0x3040..0x30FF ||
                c in 0x3400..0x4DBF ||
                c in 0x4E00..0x9FFF ||
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
