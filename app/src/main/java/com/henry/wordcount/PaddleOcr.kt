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

    // v1.9.185: 检测边长双档。1920=图纸类原值（v1.7.0，保 CAD 小字召回，P403051 切片路径不变）；
    // 736=桌面 RapidOCR 同款（limit_side_len≈736）——AH+(1) 类竖向扫描件整页单次识别用：
    // det 1920 会把整行文字切碎成单词碎片逐词计数（每个碎片单独成词）→ 字数虚增约 10× 且识别量暴增变慢；
    // 桌面实证：RapidOCR 2x + 词数口径 = 5495 ≈ 真值 5532（ah_probe2.py）。
    const val DET_LONG_DRAWING = 1920
    const val DET_LONG_SCAN = 736

    /** 当前引擎实际使用的检测边长档位；0=无就绪引擎。 */
    @Volatile private var currentDetLong = 0

    /** 曾初始化失败的档位（避免每次识别反复重试拖慢统计）。 */
    private val failedDetLongs = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /**
     * 初始化 PaddleOCR（加载 PP-OCRv4 .nb 模型）。模型缺失/失败则 available=false，不抛异常。
     * v1.9.185: 支持按检测边长档位初始化——detLongSize 仅初始化时可设，档位不同需重建引擎；
     * 切档失败自动回退图纸档 1920，保证原有路径始终有引擎可用；同一文件所有页传同一档位，最多重建一次。
     */
    fun ensureInit(context: Context, detLong: Int = DET_LONG_DRAWING) {
        if (available && currentDetLong == detLong) return
        if (detLong in failedDetLongs && !available) return
        synchronized(lock) {
            if (available && currentDetLong == detLong) return
            if (detLong in failedDetLongs && !available) return
            initTried = true
            if (ocr != null) {
                try { ocr?.releaseModel() } catch (_: Throwable) {}
                ocr = null
                available = false
                currentDetLong = 0
            }
            if (!initEngine(context.applicationContext, detLong) && detLong != DET_LONG_DRAWING
                && DET_LONG_DRAWING !in failedDetLongs) {
                // 扫描件档初始化失败 → 回退图纸档 1920，保证图纸/切片路径仍有引擎
                initEngine(context.applicationContext, DET_LONG_DRAWING)
            }
        }
    }

    /** 用指定检测边长初始化引擎；成功置 available/currentDetLong 并返回 true。 */
    private fun initEngine(appCtx: Context, detLong: Int): Boolean {
        return try {
            val engine = OCR(appCtx)
            val config = OcrConfig()
            // 相对路径：assets/models/ch_PP-OCRv4/{cls,det,rec}.nb
            config.modelPath = "models/ch_PP-OCRv4"
            config.clsModelFilename = "cls.nb"
            config.detModelFilename = "det.nb"
            config.recModelFilename = "rec.nb"
            config.labelPath = "labels/ppocr_keys_v1.txt"
            // v1.7.0: 检测输入分辨率 1920 提升工程图小字召回（图纸档）；
            // v1.9.185: 扫描件档传 736 对齐桌面 RapidOCR，整行成框不切碎。
            config.detLongSize = detLong
            // v1.7.0: 阈值从 0.2 降到 0.15。v1.6.9 字数 478 距离桌面 706 仍差 228，
            // 说明 0.2 对弱对比度小字过滤偏严；0.15 可在噪声可控前提下再提召回。
            config.scoreThreshold = 0.15f
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
                currentDetLong = detLong
                lastError = null
                true
            } else {
                available = false
                currentDetLong = 0
                lastError = err?.message ?: err?.javaClass?.simpleName ?: "未知初始化失败"
                try { engine.releaseModel() } catch (_: Throwable) {}
                false
            }
        } catch (t: Throwable) {
            available = false
            currentDetLong = 0
            ocr = null
            lastError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    /**
     * v1.9.185: 带检测边长档位的识别入口——先 ensureInit(档位) 再走统一 recognize。
     * 同一文件所有页传同一档位，通常最多触发一次引擎重建（秒级）。
     */
    fun recognize(context: Context, bitmap: Bitmap, detLong: Int): String? {
        ensureInit(context, detLong)
        if (!available) return null
        return recognize(bitmap)
    }

    /**
     * 识别位图；返回识别文本，失败/未就绪返回 null。
     * v1.9.89: Paddle-Lite predictor 非线程安全，页级并行下多 worker 并发调用会崩溃/结果错乱。
     * 因此整段识别加锁串行；渲染/预处理在锁外并行（见 PdfOcrEngine 页级并行），
     * 多页总耗时 ≈ 单页识别 × 页数（识别是瓶颈），但渲染时间被摊薄，且避免多模型实例内存翻倍 OOM。
     * 注：synchronized 调用与 ensureInit 同款（无需 import kotlin.concurrent.synchronized，
     * 该项目已验证该 import 反而 Unresolved；inline lambda 内裸 return 为非局部返回，直接返回本函数）。
     */
    override fun recognize(bitmap: Bitmap): String? {
        synchronized(lock) {
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
