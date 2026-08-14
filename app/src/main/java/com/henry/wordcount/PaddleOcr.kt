package com.henry.wordcount

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.equationl.paddleocr4android.OCR
import com.equationl.paddleocr4android.OcrConfig
import com.equationl.paddleocr4android.CpuPowerMode
import com.equationl.paddleocr4android.Util.paddle.OcrResultModel
import com.equationl.paddleocr4android.bean.OcrResult
import com.equationl.paddleocr4android.callback.OcrInitCallback
import com.equationl.paddleocr4android.callback.OcrRunCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 方案 C 的"最强引擎"实现：PaddleOCR（equationl/paddleocr4android，Paddle-Lite 后端，PP-OCRv4 模型）。
 * 与桌面 RapidOCR 同宗（均为 PaddleOCR 引擎），是真正能逼近桌面识别率的移动端 OCR，
 * 取代原先的 Tesseract 占位兜底。
 *
 * 设计要点：
 *  - 实现包级 StrongOcr 接口，与 ML Kit 主路径完全解耦；后续若要换其他引擎仍只改这里。
 *  - 仅在 ML Kit 主路径召回偏低 / 全空时由 PdfOcrEngine 调用，绝不污染 ML Kit 现有好结果。
 *  - 模型(cls/det/rec .nb, PP-OCRv4)由 CI 下载进 app/src/main/assets/models/ch_PP-OCRv4/，随 APK 内置。
 *  - 若模型缺失或初始化失败，available=false，PdfOcrEngine 自动退回纯 ML Kit（零回归）。
 */
object PaddleOcr : StrongOcr {

    @Volatile override var available: Boolean = false
        private set

    /** 最近一次初始化失败的错误信息（供 UI 诊断显示）。 */
    @Volatile var lastError: String? = null
        private set

    /** 最近一次识别的运行信息（bitmap尺寸、simpleText长度、rawResult大小等），供诊断。 */
    @Volatile var lastRunInfo: String = ""
        private set

    @Volatile private var initTried = false
    private var ocr: OCR? = null
    private val lock = Any()

    /** 首次调用时初始化 PaddleOCR（加载 PP-OCRv4 .nb 模型）。模型缺失/失败则 available=false，不抛异常。 */
    fun ensureInit(context: Context) {
        if (initTried) return
        synchronized(lock) {
            if (initTried) return
            initTried = true
            try {
                val appCtx = context.applicationContext
                val engine = OCR(appCtx)
                val config = OcrConfig()
                // 相对路径：assets/models/ch_PP-OCRv4/{cls,det,rec}.nb
                config.modelPath = "models/ch_PP-OCRv4"
                config.clsModelFilename = "cls.nb"
                config.detModelFilename = "det.nb"
                config.recModelFilename = "rec.nb"
                config.labelPath = "labels/ppocr_keys_v1.txt"
                // v1.5.102: 先恢复库默认 960，排除 1280 导致检测输入尺寸异常使识别为 0。
                // 后续若确认 1280 可用再逐步提升。
                config.detLongSize = 960
                // 降低置信度阈值，避免小字/浅灰字因 score 略低被过滤。
                config.scoreThreshold = 0.05f
                config.isRunDet = true
                config.isRunCls = true
                config.isRunRec = true
                config.cpuPowerMode = CpuPowerMode.LITE_POWER_FULL
                config.isDrwwTextPositionBox = false

                val ok = AtomicBoolean(false)
                val latch = CountDownLatch(1)
                var err: Throwable? = null
                engine.initModel(config, object : OcrInitCallback {
                    override fun onSuccess() { ok.set(true); latch.countDown() }
                    override fun onFail(e: Throwable) { err = e; latch.countDown() }
                })
                latch.await(180, TimeUnit.SECONDS)
                if (ok.get() && err == null) {
                    ocr = engine
                    available = true
                    lastError = null
                } else {
                    available = false
                    lastError = err?.message ?: err?.javaClass?.simpleName ?: "未知初始化失败"
                    try { engine.releaseModel() } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                available = false
                ocr = null
                lastError = t.message ?: t.javaClass.simpleName
            }
        }
    }

    /** 识别位图；返回识别文本，失败/未就绪返回 null。 */
    override fun recognize(bitmap: Bitmap): String? {
        val engine = ocr ?: return null
        var text: String? = null
        var err: Throwable? = null
        var rawSize = -1
        var rawText: String? = null
        val latch = CountDownLatch(1)
        engine.run(bitmap, object : OcrRunCallback {
            override fun onSuccess(result: OcrResult) {
                text = result.simpleText
                rawSize = result.outputRawResult?.size ?: 0
                rawText = result.outputRawResult?.mapNotNull { it.label }?.joinToString("\n")
                latch.countDown()
            }
            override fun onFail(e: Throwable) {
                err = e
                latch.countDown()
            }
        })
        latch.await(120, TimeUnit.SECONDS)
        val result = if (err == null) {
            // v1.5.102: 若 simpleText 为空但 raw result 有文本，用 raw result 兜底。
            val simple = text?.trim() ?: ""
            val raw = rawText?.trim() ?: ""
            if (simple.isNotEmpty()) simple else if (raw.isNotEmpty()) raw else null
        } else null
        lastRunInfo = "bmp=${bitmap.width}x${bitmap.height} simple=${text?.length ?: -1} raw=$rawSize err=${err?.message ?: "none"} out=${result?.length ?: 0}"
        Log.d("WordCount", "PaddleOcr.recognize: $lastRunInfo")
        return result
    }

    fun dispose() {
        synchronized(lock) {
            try { ocr?.releaseModel() } catch (_: Throwable) {}
            ocr = null
            available = false
            lastError = null
            lastRunInfo = ""
        }
    }
}
