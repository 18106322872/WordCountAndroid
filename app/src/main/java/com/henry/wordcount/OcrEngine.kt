package com.henry.wordcount

import android.content.Context
import android.util.Log
import java.io.File

/**
 * OCR 引擎（v1.0.17：默认禁用）。
 *
 * Tesseract (tess-two) 在部分 Android 设备上通过 JNI 调用原生库时，
 * 会触发 SIGSEGV 信号导致整个 App 进程崩溃（Java 层的 UncaughtExceptionHandler 无法拦截）。
 * 此问题与设备/ABI 相关，无法在运行时可靠预测或规避。
 *
 * 因此 v1.0.17 起 **默认禁用**。后续若需启用，可考虑替换为 Google ML Kit Text Recognition API
 *（纯 Java/Kotlin 接口、无 JNI 崩溃风险、离线可用），但需引入额外的 play-services 依赖。
 */
object OcrEngine {

    /** OCR 开关：当前固定为 false（Tesseract JNI 在 Android 上不稳定） */
    @Volatile var ocrEnabled: Boolean = false

    /** 是否曾因崩溃被自动禁用（供 UI 显示提示） */
    @Volatile var ocrCrashed: Boolean = false
        private set

    /**
     * 识别图片文件。当前始终返回空串（OCR 已禁用）。
     * 调用方应检查 ocrEnabled / ocrCrashed 并向用户展示相应提示。
     */
    fun recognize(context: Context, imageFile: File): String {
        if (!ocrEnabled) return ""
        // 以下代码仅在 ocrEnabled 被手动改为 true 时执行（开发者调试用途）
        Log.w("WordCount", "OCR is experimental and may crash on some devices")
        return ""
    }
}
