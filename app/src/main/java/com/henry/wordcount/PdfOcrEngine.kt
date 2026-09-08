package com.henry.wordcount

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shockwave.pdfium.PdfiumCore
import com.shockwave.pdfium.util.Size
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * PDF OCR 兜底引擎（用于图片型/扫描件 PDF）。
 *
 * v1.0.41 重构：
 *   - 移除 PyMuPDF(fitz) 路径（Chaqoupy 下报 FileNotFoundError: AssetFinder/scripts）
 *   - 文字型 PDF 的文本提取已由 MainActivity 中的 Python pdfminer 主导处理
 *   - 本引擎专注于：当 pdfminer 也提取不到文字时（纯图/扫描件），用渲染+OCR 兜底
 *
 * 渲染链路（按优先级）：
 *  1) 系统 PdfRenderer → ML Kit OCR
 *  2) PdfiumAndroid PFD 模式 → ML Kit OCR（支持 JPX/JBIG2）
 *  3) PdfiumAndroid ByteArray 模式 → ML Kit OCR
 *  4) 内嵌图片提取（多策略）→ ML Kit OCR
 */
/**
 * 方案 C 的"强引擎"统一接口：ML Kit 之外的第二个 OCR 阅读器（当前实现为 PaddleOCR，PP-OCRv4）。
 * 仅作为高召回兜底，不污染 ML Kit 主路径结果。后续更换引擎只需替换实现，路由逻辑不动。
 */
interface StrongOcr {
    /** 引擎是否就绪（模型存在且初始化成功）。false 时 PdfOcrEngine 自动退回纯 ML Kit。 */
    val available: Boolean
    /** 识别单张位图，失败/未就绪返回 null。 */
    fun recognize(bitmap: android.graphics.Bitmap): String?
}

object PdfOcrEngine {

    private const val MAX_PAGES = 40
    private const val MAX_DIM = 6000         // v1.9.129: 渲染上限对齐桌面 uncapped 6x（A4 6x≈5051px）；原 4096 会把 6x 渲染钳在 4096，召回低于桌面 RapidOCR
    // v1.9.89: 页级并行 OCR（对齐桌面 v1.8.92 _pdf_image_ocr 页级并行）——
    //   · 每页一个任务占用一个池 worker，渲染 + 预处理并行（每任务独立打开 PdfRenderer，
    //     避免共享实例并发渲染；开销仅几 ms）；
    //   · ML Kit 路径全并行（recognizer.process 线程安全）；PaddleOCR 路径
    //     PaddleOcr.recognize 内部锁串行（Paddle-Lite predictor 非线程安全，多实例内存
    //     翻倍有 OOM 风险；锁串行 + 渲染/预处理并行即接近桌面提速效果）；
    //   · 并行度 2：Android 内存/发热约束，2 页渲染峰值可控（实测渲染+预处理常占
    //     OCR 总耗时 30~50%，并行后整文件耗时显著下降）。
    private const val PDF_OCR_PARALLEL = 2
    /** v1.9.89: 页级并行任务池（daemon 线程，防进程残留，对齐桌面 _DaemonThreadPoolExecutor）。 */
    private val pdfOcrPool: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(PDF_OCR_PARALLEL) { r ->
            Thread(r, "pdf-ocr-pool").apply { isDaemon = true }
        }
    }
    // v1.7.1: 分块 1100px，在常见工程图页面上可达 3x2=6 块；每块放大到 2560px，
    // 给 detLongSize=1920 的检测模型提供超采样源图，避免 v1.7.0 因 1920 源图信息量
    // 不足导致小字漏检、字数反而下降的问题。
    // 每块放大到 1920px 与 detLongSize 对齐，避免二次缩放模糊。
    // v1.9.140: 1100→1600。6× 升采样位图现在钳到 5000px，若 TILE=1100/step=950 则 5000/950≈5.3
    //   起点 → 6×6=36 块 PaddleOCR → 90s 超时（v1.9.138 翻车）。TILE=1600/step=1450 →
    //   5000/1450≈3.5 起点 → 4×4=16 块 → ~50-70s 完成 90s 预算。2× 端 1684px/1450≈1.2 起点
    //   → 2×2=4 块（原来 1100 是 2×2=4 块持平），小 2×(<1600) 变 1×1=1 块省时。
    private const val TILE_SPLIT_PX = 1600
    private const val TILE_OVERLAP_PX = 150   // v1.9.129: 分块重叠（对齐桌面 RapidOCR 1400px 块/150px 重叠），避免文字被切块边界切断漏识
    private const val TILE_UPSCALE_PX = 2560
    private const val LOW_RECALL = 200       // v1.5.91: 渲染路径召回低于此字数改试内嵌图
    private const val STRONG_TRIGGER = 800    // 方案 C：主路径总字数低于此值才启用强引擎兜底（图纸类 ML Kit 常 <800，故 PaddleOCR 多会介入）
    private const val PER_PAGE_STRONG_TRIGGER = 120  // v1.9.51: 单页 ML Kit 召回低于此值（图纸类页面）即从一开始用 PaddleOCR 兜底；文字型页面 ML Kit 召回高，不触发 → 仅图纸类走 PaddleOCR
    private const val ADAPT_MIN_CHARS = 400      // v1.9.126: 对齐桌面 _ADAPT_MIN_CHARS，基准倍率识别字数低于此值视为欠识别，触发升采样
    private const val ADAPT_UPSCALE_PX = 2048    // v1.9.126: 升采样趟每块放大上限（高于基准 1280），让小字以接近原生分辨率喂给检测模型

    // v1.9.126: 对齐桌面 v1.8.109 PDF OCR 自适应升采样——基础倍率下某页识别字数 < 400 时，
    // 用 6× 重新渲染该页并识别，取字数更多者。图纸类 PDF 密集小字低于检测阈值时召回可提升数倍。
    private const val ADAPT_SCALE = 6f
    // v1.9.140: 6× 升采样位图长边上限——P403051 类扫描件 2× 认不出字，6× 必须跑；
    //   但满幅 6×(6000px)≈35 块 PaddleOCR 超时/OOM（v1.9.138 翻车），v1.9.139 公式 bug
    //   又只渲染到 2000px(2× 的 1.19×) → OCR 仍只 3 字。改为"钳制到 5000px"：
    //   P403051 6× 实际渲染 5000px (≈ v1.9.130 满幅 5052 的 99%) → 配合 TILE_SPLIT_PX=1600
    //   切成 ~16 块 PaddleOCR → 估 50-70s 低于 90s 预算；分块重叠由 mergeOcrTexts 模糊去重处理。
    //   v1.9.132 旧版"超限即跳过"导致 OCR 空，本版只钳尺寸、绝不跳。
    private const val UPSCALE_MAX_LONG_PX = 5000
    // v1.9.151: 单页 OCR 超时 90s → 240s。
    //   v1.9.150 翻车真因：5000px/16 块 PaddleOCR 实测 ~100-130s，90s 超时把它强行 cancel(true)，
    //   Paddle-Lite 原生 C++ 线程未正常结束、继续分配内存，叠加泄漏 → :countservice 被系统 OOM killer 杀，
    //   主界面永远等不到结果（卡 1/1 + 通知消失）。v1.9.130 满幅 6×(5052px) 之所以能出 6000 字，
    //   是因为当时还没有 90s 超时（v1.9.132 才加），PaddleOCR 能跑完。
    //   240s 留 2× 余量让 16 块 PaddleOCR 跑完，native 线程正常 return → 不 OOM → 进程存活。
    //   仍保留 cancel + 2× 基准兜底（v1.9.133），万一真超 240s 也不至于永久卡死。
    private const val PER_PAGE_TIMEOUT_SEC = 240L
    private const val PER_PAGE_TIMEOUT_SEC = 90L
    // v1.9.132: 进度心跳间隔（秒）。OCR 期间每 N 秒强制发一次 onProgress，
    //   避免长时间大文件 OCR 时主界面「卡 0/1 不动」+ 通知栏被 Android 误判为不活跃。
    private const val PROGRESS_HEARTBEAT_SEC = 5L
    // v1.9.134: 字高分布门禁——仅当 2× 基准漏抓"主体字"时才跑 6× 升采样（用户提速诉求）。
    //   先由 ML Kit 探针取该页所有行高 → 直方图众数=主体字高 → 统计主体字总字数：
    //     · 主体字总字数 < BODY_MIN_CHARS：稀疏页/只有极小标签，跑 6× 也捡不到主体 → 跳过 6×。
    //     · 2× 基准已捕获 ≥ BODY_CAPTURE_RATIO 的主体字：主体已抓到，仅极小字漏 → 跳过 6×。
    //     · 其余（主体字 2× 没抓到）：跑 6× 并集（符合"连主体大小都统计不出就跑 6×"）。
    private const val BODY_MIN_CHARS = 30
    private const val BODY_CAPTURE_RATIO = 0.6f

    data class PdfOcrResult(val text: String, val pages: Int)

    enum class FailReason {
        OK,
        OCR_DISABLED,
        RENDER_BLANK,
        RENDER_FAILED,
        RENDER_PARTIAL,
        PDFIUM_UNAVAILABLE,
        PDFIUM_BLANK,
        PDFIUM_FAILED,
        OCR_EMPTY,
        NO_EMBEDDED_IMAGES
    }

    @Volatile var lastFailReason: FailReason = FailReason.OK
        private set

    /** 上一次失败的具体异常信息 */
    @Volatile var lastFailDetail: String = ""
        private set

    /** 上一次 OCR 尝试的详细过程诊断（v1.3.82：供 MainActivity 展示给用户） */
    @Volatile var lastDiag: String = ""
        private set

    // v1.5.100: 供 UI 直接显示的简明诊断（PaddleOCR 是否工作、各路径字数）
    @Volatile var lastPaddleAvailable: Boolean = false
        private set
    @Volatile var lastPaddleInitError: String = ""
        private set
    @Volatile var lastPrimaryChars: Int = 0
        private set
    @Volatile var lastStrongChars: Int = 0
        private set
    @Volatile var lastMergedChars: Int = 0
        private set
    // v1.6.1: 强引擎每页渲染/识别详细诊断，用于定位 PaddleOCR 为何为 0
    @Volatile var lastStrongDiag: String = ""
        private set

    /**
     * 提取 PDF 文本（渲染+OCR）。
     * @param forPrintMode v1.3.81: 为"文字型但Kotlin无法解码"的PDF使用更高渲染质量（PRINT模式+2x分辨率），
     *        提升中文 OCR 识别率。默认 false（扫描件/图片型用普通 DISPLAY 模式即可）。
     */
    fun extractText(context: Context, file: File, forPrintMode: Boolean = false, onProgress: ((Int, Int) -> Unit)? = null): PdfOcrResult? {
        lastFailReason = FailReason.OK
        lastFailDetail = ""
        lastDiag = ""
        lastPaddleAvailable = false
        lastPaddleInitError = ""
        lastPrimaryChars = 0
        lastStrongChars = 0
        lastMergedChars = 0
        lastStrongDiag = ""
        if (!OcrEngine.ocrEnabled) {
            Log.w("WordCount", "PdfOcr 跳过: ocrEnabled=false")
            lastFailReason = FailReason.OCR_DISABLED
            lastDiag = "OCR已禁用(ocrEnabled=false)"
            return null
        }
        // v1.9.52: 提前初始化强引擎（PaddleOCR）；模型缺失时 available=false，不抛异常、不阻断主流程
        PaddleOcr.ensureInit(context)
        lastPaddleAvailable = PaddleOcr.available
        lastPaddleInitError = PaddleOcr.lastError ?: ""
        Log.d("WordCount", "PdfOcr 开始: ${file.name} (${file.length()} bytes) printMode=$forPrintMode ocrEnabled=${OcrEngine.ocrEnabled} ocrFailed=${OcrEngine.ocrFailed} strongOcr=${PaddleOcr.available} paddleErr=${lastPaddleInitError}")

        // v1.9.130: 三级 OCR 回退链——任一引擎失败(返回 null)立即试下一个，避免「PaddleOCR 可用却在本文件上
        // 失败 → 直接 null → 显示'OCR未成功'」的致命缺口（如纯图片 PDF P403051）。
        //   ① 强引擎 PaddleOCR(available 时)
        //   ② ML Kit（系统 PdfRenderer 渲染，走与强引擎不同的渲染路径，常能补 PaddleOCR 漏掉的页）
        //   ③ PdfiumAndroid（第三渲染后端，对个别 PdfRenderer/PaddleOCR 双双失败的 PDF 仍有机会出图）
        // 仅当三者全部失败才返回 null，界面才会显示「OCR未成功」。
        val result = if (PaddleOcr.available) {
            renderAndRecognizeStrong(context, file, forPrintMode, onProgress)
                ?: renderWithSystemMlKit(context, file, forPrintMode, onProgress)
                ?: renderWithPdfium(context, file, forPrintMode)
        } else {
            renderWithSystemMlKit(context, file, forPrintMode, onProgress)
                ?: renderWithPdfium(context, file, forPrintMode)
        }

        lastMergedChars = result?.text?.length ?: 0
        if (result == null) {
            if (lastDiag.isEmpty()) {
                lastDiag = "全部路径失败: reason=${lastFailReason.name}"
                if (lastFailDetail.isNotEmpty()) lastDiag += " detail=$lastFailDetail"
            }
            Log.w("WordCount", "PdfOcr 全部路径失败: ${file.name}, $lastDiag")
        }
        return result
    }

    /**
     * 方案 C 强引擎路径：用系统 PdfRenderer / PdfiumCore 渲染每页，交给 PaddleOCR 识别。
     * 仅在 ML Kit 主路径召回偏低时由 extractText 调用。引擎未就绪或异常时返回 null。
     *
     * v1.6.2: 优先走系统 PdfRenderer（已知对 ML Kit 有效，避免 PdfiumCore 对特定 PDF
     * 渲染失败导致强引擎完全拿不到图）；PdfiumCore 作为高分辨率补充。所有路径都记录
     * 详细诊断，即使最终返回 null，lastStrongDiag 也不会为空。
     */
    private fun runStrongOcr(context: Context, file: File, onProgress: ((Int, Int) -> Unit)? = null): PdfOcrResult? {
        val diag = StringBuilder()
        val sb = StringBuilder()
        var any = false
        var blankCount = 0
        var failCount = 0
        var pdfiumOkCount = 0
        var sysOkCount = 0
        var pageCount = 0

        fun processBitmap(bmp: Bitmap, source: String, pageIdx: Int) {
            try {
                val dark = darkPixelRatio(bmp)
                val darkStr = String.format("%.2f", dark)
                // v1.6.3: 暗像素>90% 疑似黑底白字/深色背景，自动反色后再识别，
                // 并与增强图/原图多路比较取字数最多者。
                val needsInvert = dark > 90.0
                val inv = if (needsInvert) invertBitmap(bmp) else null
                val variants = mutableListOf<Pair<Bitmap?, String>>()
                variants.add(enhanceBitmap(bmp) to "enh")
                if (inv != null) {
                    variants.add(enhanceBitmap(inv) to "invEnh")
                }

                var bestText = ""
                var bestLabel = ""
                val detail = StringBuilder()
                for ((variantBmp, label) in variants) {
                    if (variantBmp == null) continue
                    val t = try {
                    val maxSide = max(variantBmp.width, variantBmp.height)
                    // v1.7.1: 输入上限恢复 2560，与 TILE_UPSCALE_PX 一致；源图大于 2560 时
                    // 先缩放到 2560 再交给模型 resize 到 detLongSize=1920，保留超采样细节。
                    val inputBmp = if (maxSide > 2560) {
                        try {
                            val scale = 2560f / maxSide
                            Bitmap.createScaledBitmap(variantBmp, (variantBmp.width * scale).toInt().coerceAtLeast(1), (variantBmp.height * scale).toInt().coerceAtLeast(1), true)
                        } catch (_: Throwable) { variantBmp }
                    } else variantBmp
                    recognizeTiledGeneric(inputBmp, upscalePx = 1280) { PaddleOcr.recognize(it) ?: "" }
                    } catch (_: Throwable) { "" }
                    detail.append("$label=${t.length}")
                    if (t.length > bestText.length) { bestText = t; bestLabel = label }
                }
                // 回收内部生成的临时 variant，原图由 caller 负责 recycle
                for ((variantBmp, label) in variants) {
                    if (variantBmp != null && variantBmp !== bmp) variantBmp.recycle()
                }

                diag.append(" p${pageIdx + 1}[$source ${bmp.width}x${bmp.height} d=$darkStr% best=$bestLabel($detail)]")
                if (bestText.isNotBlank()) { sb.append(bestText).append('\n'); any = true }
            } catch (_: Throwable) {
                diag.append(" p${pageIdx + 1}[$source procErr]")
            }
        }

        // Stage 1: 系统 PdfRenderer 2x（与 ML Kit 同一路径，最可能成功）
        var sysPfd: ParcelFileDescriptor? = null
        var sysRenderer: PdfRenderer? = null
        try {
            sysPfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            sysRenderer = PdfRenderer(sysPfd)
            pageCount = sysRenderer.pageCount
            val limit = min(pageCount, MAX_PAGES)
            for (i in 0 until limit) {
                onProgress?.invoke(i + 1, pageCount)
                val page = try { sysRenderer.openPage(i) } catch (_: Throwable) { failCount++; continue }
                try {
                    val w = page.width; val h = page.height
                    if (w <= 0 || h <= 0) { failCount++; continue }
                    // v1.9.41: 强引擎渲染从 3x 降到 2x，配合单变体+1280 输入，整体 OCR 耗时降到约原来的 1/8，
                    // 消除"最后文件走到 N/N 后还卡很久"的现象（召回损失极小，ML Kit 主路径不受影响）。
                    val scale = min(2f, MAX_DIM.toFloat() / max(w, h))
                    val bw = max(1, (w * scale).toInt())
                    val bh = max(1, (h * scale).toInt())
                    val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { failCount++; continue }
                    try {
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        if (!isBlankBitmap(bmp)) {
                            processBitmap(bmp, "Sys", i)
                            sysOkCount++
                        } else {
                            blankCount++
                        }
                    } finally { bmp.recycle() }
                } finally { page.close() }
            }
        } catch (e: Throwable) {
            diag.append(" [sysErr:${e.javaClass.simpleName}]")
        }

        // Stage 2: 若系统 PdfRenderer 没拿到任何字，再用 PdfiumCore 3x 高分辨率补充
        if (!any) {
            try {
                val core = PdfiumCore(context)
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val doc = core.newDocument(pfd)
                val pc = core.getPageCount(doc)
                if (pageCount == 0) pageCount = pc
                val limit = min(pc, MAX_PAGES)
                for (i in 0 until limit) {
                    onProgress?.invoke(i + 1, pageCount)
                    try { core.openPage(doc, i) } catch (_: Throwable) { failCount++; continue }
                    try {
                        val sz = core.getPageSize(doc, i) ?: continue
                        val sw = sz.width.toInt(); val sh = sz.height.toInt()
                        if (sw <= 0 || sh <= 0) continue
                        val sc = min(3f, MAX_DIM.toFloat() / max(sw, sh))
                        val bw = max(1, (sw * sc).toInt())
                        val bh = max(1, (sh * sc).toInt())
                        val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { failCount++; continue }
                        try {
                            core.renderPageBitmap(doc, bmp, i, 0, 0, bw, bh)
                            if (!isBlankBitmap(bmp)) {
                                processBitmap(bmp, "Pdfium", i)
                                pdfiumOkCount++
                            } else {
                                blankCount++
                            }
                        } finally { bmp.recycle() }
                    } catch (_: Throwable) { failCount++ }
                }
            } catch (e: Throwable) {
                diag.append(" [pdfiumErr:${e.javaClass.simpleName}]")
            }
        }

        lastStrongDiag = "强引擎:pages=$pageCount blank=$blankCount fail=$failCount sys=$sysOkCount pdfium=$pdfiumOkCount$diag"
        Log.d("WordCount", "PdfOcr(强引擎) 汇总: $lastStrongDiag")
        return if (any) PdfOcrResult(sb.toString().trim(), pageCount) else null
    }

    // ══════════════════ 1) PaddleOCR 强引擎路径 ══════════════════

    /**
     * v1.9.89: 单页渲染辅助——独立打开 PdfRenderer 渲染第 i 页为 Bitmap。
     * 供页级并行任务调用（每任务独立打开，避免共享 PdfRenderer 并发渲染冲突）。
     * 失败返回 null。
     *
     * v1.9.126: 增加 scaleOverride 参数，支持自适应 6× 升采样重渲染。
     */
    private fun renderPageSysBitmap(file: File, i: Int, forPrintMode: Boolean, scaleOverride: Float? = null, maxLongPx: Int = -1): Bitmap? {
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            try {
                val page = renderer.openPage(i)
                try {
                    val w = page.width; val h = page.height
                    if (w <= 0 || h <= 0) return null
                    val scale = if (scaleOverride != null) {
                        // v1.9.140: maxLongPx 钳制——把最终位图长边限制到 maxLongPx 像素。
                        //   scaleOverride 是对 native 页面的倍数（P403051 native≈842, 2×=1684），
                        //   要让 6× 最终位图长边 ≤ UPSCALE_MAX_LONG_PX(5000) 而不是 2× 的一半，
                        //   必须按 native 钳制（5000/842=5.94 而非 5000/1684=2.97）。
                        var s = min(scaleOverride, MAX_DIM.toFloat() / max(w, h).toFloat())
                        if (maxLongPx > 0) {
                            val cap = maxLongPx.toFloat() / max(w, h).toFloat()
                            if (s > cap) s = cap
                        }
                        s
                    } else {
                        val baseScale = computeScale(w, h)
                        val rawScale = if (forPrintMode) baseScale * 3f else baseScale * 2f
                        min(rawScale, MAX_DIM.toFloat() / max(w, h).toFloat())
                    }
                    val bw = max(1, (w * scale).toInt()); val bh = max(1, (h * scale).toInt())
                    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) ?: return null
                    val renderMode = if (forPrintMode) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    page.render(bmp, null, null, renderMode)
                    bmp
                } finally { page.close() }
            } finally { runCatching { renderer.close() }; runCatching { pfd.close() } }
        } catch (_: Throwable) { null }
    }

    /**
     * v1.9.52: 对齐桌面版 RapidOCR 整页识别口径。
     * 进入此函数的 PDF 已被上层判定为图纸类/图片型/文字层污染，直接整页渲染 → PaddleOCR，
     * 不再先跑 ML Kit 再兜底 PaddleOCR。进度在每页识别完成后回调，杜绝"走到 N/N 还卡"。
     *
     * v1.9.89: Stage 1 改为页级并行（渲染+预处理并行，PaddleOcr.recognize 内部锁串行识别），
     * 对齐桌面 v1.8.92 页级并行提速；Stage 2 Pdfium 兜底保留串行（仅全空时触发，极少发生）。
     */
    private fun renderAndRecognizeStrong(context: Context, file: File, forPrintMode: Boolean, onProgress: ((Int, Int) -> Unit)?): PdfOcrResult? {
        val diag = StringBuilder()
        val sb = StringBuilder()
        var anyText = false
        var blankCount = 0
        var errorCount = 0
        var pageCount = 0
        // v1.9.133: 超时页兜底用——每页 2× 基准结果算完后立即发布到这里，超时分支读取作最优可用解。
        val pageBase = java.util.concurrent.ConcurrentHashMap<Int, String>()

        fun processBitmap(bmp: Bitmap, source: String, pageIdx: Int) {
            try {
                if (isBlankBitmap(bmp)) { blankCount++; return }
                val dark = darkPixelRatio(bmp)
                val needsInvert = dark > 95.0
                val baseBmp = if (needsInvert) (invertBitmap(bmp) ?: bmp) else bmp
                val enhanced = enhanceBitmap(baseBmp) ?: baseBmp
                val raw = try {
                    recognizeTiledGeneric(enhanced, upscalePx = 1280) { PaddleOcr.recognize(it) ?: "" }
                } catch (_: Throwable) { "" }
                val text = filterStrongCjkNoise(raw)
                if (text.isNotBlank()) {
                    sb.append(text).append('\n')
                    anyText = true
                    lastStrongChars += text.length
                    diag.append(" p${pageIdx + 1}:$source=${text.length}")
                } else {
                    diag.append(" p${pageIdx + 1}:${source}空")
                }
                if (enhanced !== baseBmp && enhanced !== bmp) enhanced.recycle()
                if (baseBmp !== bmp) baseBmp.recycle()
            } catch (_: Throwable) {
                errorCount++
                diag.append(" p${pageIdx + 1}:${source}Err")
            }
        }

        /**
         * v1.9.126: 单页 PaddleOCR 识别。返回 (rawLen, filteredText)。
         * rawLen 用于自适应升采样决策（过滤前比较，避免噪声过滤误杀小字）。
         */
        fun recognizePageStrong(bmp: Bitmap): Pair<Int, String> {
            val dark = darkPixelRatio(bmp)
            val needsInvert = dark > 95.0
            val baseBmp = if (needsInvert) (invertBitmap(bmp) ?: bmp) else bmp
            val enhanced = enhanceBitmap(baseBmp) ?: baseBmp
            val raw = try {
                recognizeTiledGeneric(enhanced, upscalePx = 1280) { PaddleOcr.recognize(it) ?: "" }
            } catch (_: Throwable) { "" }
            val text = filterStrongCjkNoise(raw)
            if (enhanced !== baseBmp && enhanced !== bmp) enhanced.recycle()
            if (baseBmp !== bmp) baseBmp.recycle()
            return raw.length to text
        }

        // Stage 1: 系统 PdfRenderer（2x/3x 渲染），v1.9.89 页级并行
        try {
            val pfd0 = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r0 = PdfRenderer(pfd0)
            pageCount = r0.pageCount
            runCatching { r0.close() }; runCatching { pfd0.close() }
            val limit = min(pageCount, MAX_PAGES)
            diag.append("Strong(Paddle): ${pageCount}页 print=$forPrintMode")
            if (limit > 0) {
                val futures = (0 until limit).map { i ->
                    pdfOcrPool.submit(java.util.concurrent.Callable {
                        try {
                            // v1.9.132: 进度心跳——每页"开始"就先发一次 onProgress，
                            //   避免长时间大图 OCR 时主界面"卡 0/N 不动"。
                            //   完成后会再发一次（同一 i+1），UI 端会更新到 "i+1/N 完成"。
                            try { onProgress?.invoke(i + 1, pageCount) } catch (_: Throwable) {}
                            val bmp = renderPageSysBitmap(file, i, forPrintMode)
                            if (bmp == null) null
                            else try {
                                if (isBlankBitmap(bmp)) Triple(i, "", true)
                                else {
                                    val (_, baseText) = recognizePageStrong(bmp)
                                    pageBase[i] = baseText   // v1.9.133: 发布 2× 基准，供超时兜底
                                    // v1.9.129: 基准倍率 + 6× 升采样双遍并集（不再只取字数更多者）。
                                    // 移动端 PP-OCRv4(.nb) 模型弱于桌面 RapidOCR，不同渲染倍率会捕获不同文字，
                                    // 并集可显著提升召回，逼近桌面计数。
                                    // v1.9.136(修正2): 6× 升采样由 MAX_DIM(6000) 钳制尺寸；重复计数靠 mergeOcrTexts 模糊去重。
                                    var bestText = baseText
                                    // v1.9.140: 6× 升采样 + renderPageSysBitmap 的 maxLongPx 钳制（按 native 像素）。
                                    //   传 ADAPT_SCALE(6) 与 UPSCALE_MAX_LONG_PX，renderPageSysBitmap 内部按 native 长边
                                    //   钳到 ≤5000px；v1.9.139 在调用处用 bmp 推算 upScale 公式错了（bmp 是 2× native），
                                    //   导致 6× 实际只渲染 2000px → P403051 OCR 仍空。本版按 native 钳，绝对值正确。
                                    val upBmp = renderPageSysBitmap(file, i, forPrintMode, ADAPT_SCALE, UPSCALE_MAX_LONG_PX)
                                    if (upBmp != null) {
                                        try {
                                            if (!isBlankBitmap(upBmp)) {
                                                // v1.9.134: 字高分布门禁——仅当 2× 基准漏抓"主体字"时才跑 6× 升采样
                                                if (isBodyCaptured(bmp, baseText)) {
                                                    Diag.d("PdfOcr p${i+1}: 升采样跳过(主体字已抓到/稀疏页)，只用 2× 基准")
                                                } else {
                                                    val (_, upText) = recognizePageStrong(upBmp)
                                                    bestText = mergeOcrTexts(baseText, upText)
                                                    Diag.d("PdfOcr p${i+1}: 6×升采样 → ${upBmp.width}x${upBmp.height}(≤${UPSCALE_MAX_LONG_PX}px) 合并 +${upText.length}字")
                                                }
                                            } else {
                                                Diag.d("PdfOcr p${i+1}: 升采样位图空白，跳过")
                                            }
                                        } finally { upBmp.recycle() }
                                    }
                                    // v1.9.131: 6× 升采样完成后只发一次进度（与基准同号 i+1），
                                    // 之前用 i+1+pageCount 会让计数器超过 total，导致 6/4 这种"超总数"显示。
                                    try { onProgress?.invoke(i + 1, pageCount) } catch (_: Throwable) {}
                                    Triple(i, bestText, false)
                                }
                            } finally { bmp.recycle() }
                        } catch (_: Throwable) { null }
                    })
                }
                for ((idx, f) in futures.withIndex()) {
                    // v1.9.132: 单页超时 PER_PAGE_TIMEOUT_SEC(90s)——超过即放弃该页继续下一页。
                    //   避免 P403051 类大图单页卡住整个文件的 N 分钟。
                    val r = try { f.get(PER_PAGE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) { null }
                    if (r == null) {
                        if (!f.isDone) {
                            try { f.cancel(true) } catch (_: Throwable) {}
                            Diag.d("PdfOcr p${idx+1}: 超时 ${PER_PAGE_TIMEOUT_SEC}s 取消")
                        }
                        // v1.9.133: 超时页不再丢弃，用已发布的 2× 基准（最优可用解）兜底，宁可少算 6× 补的小字也比计 0 字强。
                        val fb = pageBase[idx]
                        if (!fb.isNullOrBlank()) {
                            sb.append(fb).append('\n'); anyText = true
                            lastStrongChars += fb.length
                            diag.append(" [p${idx+1}:超时→2×基准兜底${fb.length}字]")
                        } else {
                            errorCount++; diag.append(" [p${idx+1}:超时且无基准]")
                        }
                        continue
                    }
                    val (_, text, blank) = r
                    if (blank) { blankCount++; diag.append(" [p${idx+1}:空白]"); continue }
                    if (text.isNotBlank()) {
                        sb.append(text).append('\n')
                        anyText = true
                        lastStrongChars += text.length
                        diag.append(" p${idx + 1}:Sys=${text.length}")
                    } else {
                        diag.append(" p${idx + 1}:Sys空")
                    }
                }
            }
        } catch (e: Throwable) {
            diag.append(" [sysErr:${e.javaClass.simpleName}]")
            lastFailReason = FailReason.RENDER_FAILED
            lastFailDetail = e.javaClass.simpleName + ": " + e.message
        }

        // Stage 2: PdfiumCore 兜底（系统渲染失败或全空时，串行——极少触发）
        if (!anyText && pageCount > 0) {
            try {
                val core = PdfiumCore(context)
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val doc = core.newDocument(pfd)
                val pc = core.getPageCount(doc)
                if (pageCount == 0) pageCount = pc
                val limit = min(pc, MAX_PAGES)
                diag.append(" | Pdfium fallback")
                for (i in 0 until limit) {
                    try {
                        core.openPage(doc, i)
                        val sz = core.getPageSize(doc, i) ?: continue
                        val sw = sz.width.toInt(); val sh = sz.height.toInt()
                        if (sw <= 0 || sh <= 0) continue
                        val sc = min(3f, MAX_DIM.toFloat() / max(sw, sh))
                        val bw = max(1, (sw * sc).toInt()); val bh = max(1, (sh * sc).toInt())
                        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) ?: continue
                        try {
                            core.renderPageBitmap(doc, bmp, i, 0, 0, bw, bh)
                            processBitmap(bmp, "Pdfium", i)
                        } finally { bmp.recycle() }
                    } catch (_: Throwable) { errorCount++ }
                    onProgress?.invoke(i + 1, pageCount)
                }
            } catch (e: Throwable) {
                diag.append(" [pdfiumErr:${e.javaClass.simpleName}]")
            }
        }

        lastStrongDiag = "强引擎:Paddle pages=$pageCount blank=$blankCount err=$errorCount$diag"
        lastDiag = lastStrongDiag
        val text = sb.toString().trim()
        return if (text.isNotBlank()) PdfOcrResult(text, pageCount) else null
    }

    // ══════════════════ 2) ML Kit 兜底路径（PaddleOCR 不可用时）══════════════════

    /**
     * v1.9.52: PaddleOCR 模型缺失/初始化失败时的纯 ML Kit 兜底。
     * 同样保证进度在每页 OCR 完成后回调，不再卡 N/N。
     *
     * v1.9.89: 页级并行——ML Kit recognizer.process 线程安全，渲染+识别全并行
     * （每任务独立打开 PdfRenderer），对齐桌面 v1.8.92 页级并行提速。
     */
    private fun renderWithSystemMlKit(context: Context, file: File, forPrintMode: Boolean, onProgress: ((Int, Int) -> Unit)?): PdfOcrResult? {
        val diag = StringBuilder()
        // v1.9.133: 超时页兜底用——每页 2× 基准结果算完后立即发布，超时分支读取作最优可用解。
        val pageBase = java.util.concurrent.ConcurrentHashMap<Int, String>()
        val pfd0 = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Throwable) {
            lastFailReason = FailReason.RENDER_FAILED
            lastFailDetail = "PFD打开失败: ${e.javaClass.simpleName}"
            lastDiag = "SysRenderer(MLKit): $lastFailDetail"
            return null
        }
        val renderer0 = try { PdfRenderer(pfd0) } catch (e: Throwable) {
            runCatching { pfd0.close() }
            lastFailReason = FailReason.RENDER_FAILED
            lastFailDetail = "PdfRenderer创建失败: ${e.javaClass.simpleName}"
            lastDiag = "SysRenderer(MLKit): $lastFailDetail"
            return null
        }
        val pageCount = renderer0.pageCount
        runCatching { renderer0.close() }; runCatching { pfd0.close() }
        val limit = min(pageCount, MAX_PAGES)
        val sb = StringBuilder()
        var anyRenderedContent = false; var pageErrors = 0
        var blankCount = 0; var ocrEmptyCount = 0

        diag.append("SysRenderer(MLKit): ${pageCount}页(forPrint=$forPrintMode)")

        /**
         * v1.9.126: 单页 ML Kit 识别。返回 (rawLen, text)。
         */
        fun recognizePageMlKit(bmp: Bitmap): Pair<Int, String> {
            val darkRatio = darkPixelRatio(bmp)
            val needsInvert = darkRatio > 95.0
            var ocrBmp: Bitmap = bmp
            var inverted: Bitmap? = null
            if (needsInvert) {
                inverted = invertBitmap(bmp)
                if (inverted != null) ocrBmp = inverted
            }
            val t = try { recognizeTiled(ocrBmp) } catch (_: Throwable) { "" }
            inverted?.recycle()
            return t.length to t
        }

        // v1.9.89: 页级并行提交（渲染 + 反色 + 分块 OCR 全在 worker 线程；
        // ML Kit recognizer.process 线程安全，可并发。结果按页序收集保证文字顺序稳定）
        if (limit > 0) {
            val futures = (0 until limit).map { i ->
                pdfOcrPool.submit(java.util.concurrent.Callable {
                    try {
                        // v1.9.132: 进度心跳——每页"开始"先发一次 onProgress（与强引擎路径同设计）
                        try { onProgress?.invoke(i + 1, pageCount) } catch (_: Throwable) {}
                        val bmp = renderPageSysBitmap(file, i, forPrintMode)
                        if (bmp == null) null
                        else try {
                            if (isBlankBitmap(bmp)) Triple(i, "", true)
                            else {
                                val (_, baseText) = recognizePageMlKit(bmp)
                                pageBase[i] = baseText   // v1.9.133: 发布 2× 基准，供超时兜底
                                // v1.9.129: 基准倍率 + 6× 升采样双遍并集（同强引擎路径），最大化移动端召回。
                                // v1.9.136(修正2): 6× 升采样由 MAX_DIM(6000) 钳制尺寸；重复计数靠 mergeOcrTexts 模糊去重。
                                var bestText = baseText
                                // v1.9.140: 同强引擎路径——6× 升采样 + renderPageSysBitmap 按 native maxLongPx 钳制。
                                val upBmp = renderPageSysBitmap(file, i, forPrintMode, ADAPT_SCALE, UPSCALE_MAX_LONG_PX)
                                if (upBmp != null) {
                                    try {
                                        if (!isBlankBitmap(upBmp)) {
                                            // v1.9.134: 字高分布门禁——仅当 2× 基准漏抓"主体字"时才跑 6× 升采样
                                            if (isBodyCaptured(bmp, baseText)) {
                                                Diag.d("PdfOcr p${i+1}: 升采样跳过(主体字已抓到/稀疏页)，只用 2× 基准")
                                            } else {
                                                    val (_, upText) = recognizePageMlKit(upBmp)
                                                    bestText = mergeOcrTexts(baseText, upText)
                                                    Diag.d("PdfOcr p${i+1}: 6×升采样(MLKit) → ${upBmp.width}x${upBmp.height}(≤${UPSCALE_MAX_LONG_PX}px) 合并 +${upText.length}字")
                                            }
                                        } else {
                                            Diag.d("PdfOcr p${i+1}: 升采样位图空白，跳过")
                                        }
                                    } finally { upBmp.recycle() }
                                }
                                // v1.9.131: 6× 升采样完成后只发一次进度（与基准同号 i+1），
                                // 之前用 i+1+pageCount 会让计数器超过 total，导致 6/4 这种"超总数"显示。
                                try { onProgress?.invoke(i + 1, pageCount) } catch (_: Throwable) {}
                                Triple(i, bestText, false)
                            }
                        } finally { bmp.recycle() }
                    } catch (_: Throwable) { null }
                })
            }
            for ((idx, f) in futures.withIndex()) {
                // v1.9.132: 单页超时 90s（与强引擎同口径），超时即放弃。
                val r = try { f.get(PER_PAGE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) { null }
                if (r == null) {
                    if (!f.isDone) {
                        try { f.cancel(true) } catch (_: Throwable) {}
                        Diag.d("PdfOcr ML Kit p${idx+1}: 超时 ${PER_PAGE_TIMEOUT_SEC}s 取消")
                    }
                    // v1.9.133: 超时页用已发布的 2× 基准兜底（最优可用解），不丢弃。
                    val fb = pageBase[idx]
                    if (!fb.isNullOrBlank()) {
                        sb.append(fb).append('\n'); anyRenderedContent = true
                        diag.append(" [p${idx+1}:超时→2×基准兜底${fb.length}字]")
                    } else {
                        pageErrors++
                        diag.append(" [p${idx+1}:超时且无基准]")
                    }
                    continue
                }
                val (_, text, blank) = r
                if (blank) { blankCount++; continue }
                anyRenderedContent = true
                if (text.isNotBlank()) {
                    sb.append(text).append('\n')
                    diag.append(" p${idx + 1}:${text.length}字")
                } else {
                    ocrEmptyCount++
                    diag.append(" [p${idx+1}:OCR空结果]")
                }
            }
        }

        diag.append(" | 渲染:${if (anyRenderedContent) "有内容" else "全空白"} 空白:$blankCount OCR空:$ocrEmptyCount 错误:$pageErrors")

        val text = sb.toString().trim()
        var result: PdfOcrResult? = null
        if (text.isNotBlank()) result = PdfOcrResult(text, pageCount)
        else if (anyRenderedContent) lastFailReason = FailReason.OCR_EMPTY
        else if (pageErrors > 0) lastFailReason = FailReason.RENDER_PARTIAL
        else lastFailReason = FailReason.RENDER_BLANK
        lastDiag = diag.toString()
        return result
    }

    // ══════════════════ 3) PdfiumAndroid（旧入口，已不再被 extractText 调用，保留备用）══════════════════

    private fun renderWithPdfium(context: Context, file: File, forPrintMode: Boolean = false): PdfOcrResult? {
        val core = try { PdfiumCore(context) } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(pdfium) 初始化失败: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.PDFIUM_UNAVAILABLE
            lastFailDetail = "${e.javaClass.simpleName}: ${e.message}"
            lastDiag = "PdfiumCore: 不可用($lastFailDetail)"
            return null
        }

        // 模式A：PFD
        val pfdResult = tryRenderWithPfd(core, file, forPrintMode)
        if (pfdResult != null) return pfdResult

        // 模式B：ByteArray
        if (lastFailReason == FailReason.PDFIUM_FAILED || lastFailReason == FailReason.PDFIUM_BLANK) {
            Log.d("WordCount", "PdfOcr(pdfium) PFD失败(${lastFailReason})，尝试ByteArray模式...")
            val bytesResult = tryRenderWithBytes(core, file, forPrintMode)
            if (bytesResult != null) return bytesResult
        }
        // Pdfium 失败时补充诊断
        if (lastDiag.isEmpty()) lastDiag = "Pdfium: ${lastFailReason.name} $lastFailDetail"
        return null
    }

    private fun tryRenderWithPfd(core: PdfiumCore, file: File, forPrintMode: Boolean = false): PdfOcrResult? {
        val pfd = try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) } catch (_: Throwable) {
            lastFailReason = FailReason.PDFIUM_FAILED; lastFailDetail = "PFD open failed"; return null
        }
        return try {
            val doc = core.newDocument(pfd)
            val pageCount = core.getPageCount(doc)
            val limit = min(pageCount, MAX_PAGES)
            val sb = StringBuilder()
            var anyContent = false; var errors = 0
            for (i in 0 until limit) {
                try { core.openPage(doc, i) } catch (e: Throwable) { errors++; continue }
                try {
                    val sz: Size = core.getPageSize(doc, i)
                    val sw = sz.width; val sh = sz.height
                    if (sw <= 0 || sh <= 0) { errors++; continue }
                    val baseSc = computeScale(sw.toInt(), sh.toInt())
                    // v1.5.69: DISPLAY 模式也 2x（原 1x 识别率不足）；MAX_DIM 钳制防 OOM
                    val sc = min(if (forPrintMode) baseSc * 2f else baseSc * 2f, MAX_DIM.toFloat() / max(sw, sh).toFloat())
                    val bw = max(1, (sw * sc).toInt()); val bh = max(1, (sh * sc).toInt())
                    val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { errors++; continue }
                    try {
                        core.renderPageBitmap(doc, bmp, i, 0, 0, bw, bh)
                        if (isBlankBitmap(bmp)) continue
                        anyContent = true
                        val t = recognizeTiled(bmp)
                        if (t.isNotBlank()) sb.append(t).append('\n')
                    } catch (e: Throwable) { errors++; lastFailDetail = "PFD.render[$i]: ${e.javaClass.simpleName}" } finally { bmp.recycle() }
                } finally { /* closeDocument 释放所有页面 */ }
            }
            val text = sb.toString().trim()
            if (text.isNotBlank()) PdfOcrResult(text, pageCount)
            else { lastFailReason = if (anyContent) FailReason.OCR_EMPTY else FailReason.PDFIUM_BLANK; null }
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(PFD) 失败: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { pfd.close() }
            lastFailReason = FailReason.PDFIUM_FAILED
            lastFailDetail = "PFD: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    private fun tryRenderWithBytes(core: PdfiumCore, file: File, forPrintMode: Boolean = false): PdfOcrResult? {
        return try {
            val bytes = file.readBytes()
            val doc = core.newDocument(bytes)
            val pageCount = core.getPageCount(doc)
            val limit = min(pageCount, MAX_PAGES)
            val sb = StringBuilder()
            var anyContent = false; var errors = 0
            for (i in 0 until limit) {
                try { core.openPage(doc, i) } catch (_: Throwable) { errors++; continue }
                try {
                    val sz: Size = core.getPageSize(doc, i)
                    val sw = sz.width; val sh = sz.height
                    if (sw <= 0 || sh <= 0) { errors++; continue }
                    val baseSc = computeScale(sw.toInt(), sh.toInt())
                    // v1.5.69: DISPLAY 模式也 2x（原 1x 识别率不足）；MAX_DIM 钳制防 OOM
                    val sc = min(if (forPrintMode) baseSc * 2f else baseSc * 2f, MAX_DIM.toFloat() / max(sw, sh).toFloat())
                    val bw = max(1, (sw * sc).toInt()); val bh = max(1, (sh * sc).toInt())
                    val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { errors++; continue }
                    try {
                        core.renderPageBitmap(doc, bmp, i, 0, 0, bw, bh)
                        if (isBlankBitmap(bmp)) continue
                        anyContent = true
                        val t = recognizeTiled(bmp)
                        if (t.isNotBlank()) sb.append(t).append('\n')
                    } catch (e: Throwable) { errors++; lastFailDetail = "Bytes.render[$i]: ${e.javaClass.simpleName}" } finally { bmp.recycle() }
                } finally { /* closeDocument 释放 */ }
            }
            val text = sb.toString().trim()
            if (text.isNotBlank()) PdfOcrResult(text, pageCount)
            else { lastFailReason = if (anyContent) FailReason.OCR_EMPTY else FailReason.PDFIUM_BLANK; null }
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(BYTES) 失败: ${e.javaClass.simpleName}: ${e.message}")
            lastFailReason = FailReason.PDFIUM_FAILED
            lastFailDetail = "Bytes: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    // ══════════════════ 3) 内嵌图片提取（多策略）══════════════════

    private fun extractEmbeddedImages(file: File): List<Bitmap> {
        val results = mutableListOf<Bitmap>()
        val seenOffsets = mutableSetOf<Int>()

        try {
            val data = file.readBytes()
            if (data.size < 100) return emptyList()

            strategyA_XObjectImage(data, results, seenOffsets)
            if (results.isEmpty()) strategyB_RelaxedImage(data, results, seenOffsets)
            if (results.isEmpty()) strategyC_RawStreamDecode(data, results, seenOffsets)

            Log.d("WordCount", "PdfOcr(内嵌图片) 多策略提取 ${results.size} 张")
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr(内嵌图片) 异常: ${e.javaClass.simpleName}: ${e.message}")
        }
        return results
    }

    /** 策略A: 标准 /Type/XObject /Subtype/Image ... /Length N 字典 */
    private fun strategyA_XObjectImage(data: ByteArray, out: MutableList<Bitmap>, seen: MutableSet<Int>) {
        val str = String(data, 0, min(data.size, 10 * 1024 * 1024), Charsets.ISO_8859_1)
        val imgPattern = Regex("""/Type\s*/XObject\s*/Subtype\s*/Image[^>]*?/Length\s+(\d+)""")
        for (match in imgPattern.findAll(str)) {
            if (out.size >= MAX_PAGES) break
            try {
                val length = match.groupValues[1].trim().toIntOrNull() ?: continue
                if (length < 100 || length > 50_000_000) continue
                val dictEnd = match.range.last
                val streamStart = str.indexOf("stream", dictEnd)
                if (streamStart < 0 || streamStart > dictEnd + 500) continue
                val b1 = data.getOrNull(streamStart + 6)?.toInt() ?: 0
                val offset = when {
                    b1 == 0x0D -> 2
                    b1 == 0x0A -> 1
                    else -> 0
                }.coerceAtMost(2)
                val dataStart = streamStart + 7 + offset
                if (dataStart + length > data.size || seen.contains(dataStart)) continue
                seen.add(dataStart)
                val imgBytes = data.sliceArray(dataStart until dataStart + length)
                val bmp = BitmapFactoryDecode(imgBytes) ?: continue
                if (bmp.width > 10 && bmp.height > 10) out.add(bmp) else bmp.recycle()
            } catch (_: Throwable) {}
        }
    }

    /** 策略B: 宽松 /Subtype/Image 匹配 + 广域 /Length 搜索 */
    private fun strategyB_RelaxedImage(data: ByteArray, out: MutableList<Bitmap>, seen: MutableSet<Int>) {
        val str = String(data, 0, min(data.size, 10 * 1024 * 1024), Charsets.ISO_8859_1)
        val subtypePatterns = listOf(
            """/Subtype\s*/\s*Image""".toRegex(RegexOption.IGNORE_CASE),
            """/S\s*/\s*Image""".toRegex(RegexOption.IGNORE_CASE)
        )
        for (pattern in subtypePatterns) {
            for (match in pattern.findAll(str)) {
                if (out.size >= MAX_PAGES) break
                try {
                    val pos = match.range.start
                    val searchRegion = str.substring(max(0, pos - 500), min(str.length, pos + 500))
                    val lenMatch = """/Length\s+(\d+)""".toRegex().find(searchRegion) ?: continue
                    val length = lenMatch.groupValues[1].trim().toIntOrNull() ?: continue
                    if (length < 100 || length > 50_000_000) continue
                    val afterMatch = str.substring(pos, min(str.length, pos + 2000))
                    val streamIdx = afterMatch.indexOf("stream")
                    if (streamIdx < 0) continue
                    val absStreamPos = pos + streamIdx
                    val b1 = data.getOrNull(absStreamPos + 6)?.toInt() ?: 0
                    val offset = when {
                        b1 == 0x0D -> 2
                        b1 == 0x0A -> 1
                        else -> 0
                    }.coerceAtMost(2)
                    val dataStart = absStreamPos + 7 + offset
                    if (dataStart + length > data.size || seen.contains(dataStart)) continue
                    seen.add(dataStart)
                    val imgBytes = data.sliceArray(dataStart until dataStart + length)
                    val bmp = BitmapFactoryDecode(imgBytes) ?: continue
                    if (bmp.width > 10 && bmp.height > 10) out.add(bmp) else bmp.recycle()
                } catch (_: Throwable) {}
            }
        }
    }

    /** 策略C: stream 块暴力解码 */
    private fun strategyC_RawStreamDecode(data: ByteArray, out: MutableList<Bitmap>, seen: MutableSet<Int>) {
        val kw = "stream".toByteArray(Charsets.US_ASCII)
        val endKw = "endstream".toByteArray(Charsets.US_ASCII)
        var pos = 0
        var attempts = 0
        val maxAttempts = 50

        while (pos <= data.size - kw.size - 2 && attempts < maxAttempts && out.size < MAX_PAGES) {
            attempts++
            val idx = indexOfBytes(data, kw, pos)
            if (idx < 0) break
            val afterKw = idx + kw.size
            val dataStart = when {
                afterKw + 1 < data.size && data[afterKw] == '\r'.code.toByte()
                        && afterKw + 2 < data.size && data[afterKw + 1] == '\n'.code.toByte() -> afterKw + 2
                afterKw < data.size && data[afterKw] == '\n'.code.toByte() -> afterKw + 1
                afterKw < data.size && data[afterKw] == '\r'.code.toByte() -> afterKw + 1
                else -> { pos = idx + 1; continue }
            }
            val endPos = indexOfBytes(data, endKw, dataStart)
            val dataLen = if (endPos >= 0) endPos - dataStart else min(10 * 1024 * 1024, data.size - dataStart)
            if (dataLen > 1024 && dataLen < 10 * 1024 * 1024 && !seen.contains(dataStart)) {
                seen.add(dataStart)
                try {
                    val chunk = data.sliceArray(dataStart until dataStart + dataLen)
                    val bmp = BitmapFactoryDecode(chunk)
                    if (bmp != null && bmp.width > 50 && bmp.height > 50) out.add(bmp) else bmp?.recycle()
                } catch (_: Throwable) {}
            }
            pos = if (endPos >= 0) endPos + endKw.size else dataStart + dataLen
        }
    }

    // ───────────────────── 工具函数 ─────────────────────

    private fun BitmapFactoryDecode(bytes: ByteArray): Bitmap? = try {
        val opts = android.graphics.BitmapFactory.Options()
        opts.inSampleSize = 1
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Throwable) { null }

    private fun isBlankBitmap(bmp: Bitmap): Boolean {
        return try {
            val w = bmp.width; val h = bmp.height
            if (w <= 0 || h <= 0) return@isBlankBitmap true
            val sx = max(1, w / 32); val sy = max(1, h / 32)
            var nw = 0; var s = 0; var y = 0
            while (y < h) { var x = 0
            while (x < w) { val px = bmp.getPixel(x, y)
                s++
                if ((px shr 16 and 0xFF) < 248 || (px shr 8 and 0xFF) < 248 || (px and 0xFF) < 248) nw++
                x += sx
            }
            y += sy
            }
            if (s == 0) true else (nw.toDouble() / s) < 0.005
        } catch (_: Throwable) { false }
    }

    // v1.3.85: 计算暗像素(文字)占比，判断渲染图是否"有可见文字"但ML Kit认不出
    // 返回 0~100 的百分比。阈值<128为暗像素(深色文字)。
    private fun darkPixelRatio(bmp: Bitmap): Double {
        return try {
            val w = bmp.width; val h = bmp.height
            if (w <= 0 || h <= 0) return@darkPixelRatio 0.0
            val sx = max(1, w / 48); val sy = max(1, h / 48)  // 采样48x48网格
            var dark = 0; var s = 0; var y = 0
            while (y < h) { var x = 0
            while (x < w) { val px = bmp.getPixel(x, y)
                val r = px shr 16 and 0xFF; val g = px shr 8 and 0xFF; val b = px and 0xFF
                // 亮度(感知加权)
                val lum = (0.299 * r + 0.587 * g + 0.114 * b)
                if (lum < 128) dark++
                s++
                x += sx
            }
            y += sy
            }
            if (s == 0) 0.0 else (dark.toDouble() / s) * 100.0
        } catch (_: Throwable) { 0.0 }
    }

    // v1.3.86: 反色Bitmap（暗像素>95%时自动反色后再OCR）
    private fun invertBitmap(src: Bitmap): Bitmap? {
        return try {
            val w = src.width; val h = src.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val p = pixels[i]
                // 保持Alpha通道，反转RGB
                pixels[i] = (p and -0x1000000.toInt()) or
                    (0x00FFFFFF - (p and 0x00FFFFFF))
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            out
        } catch (_: Throwable) { null }
    }

    /**
     * v1.5.90: 分块 OCR —— 把整页位图切成 cols×rows 小块分别识别再合并。
     * 密集工程图整页超大（常 3000px+）时，OCR 文字检测对小字召回率低；
     * 切成 ~1000px 的小块后每块文字更少、更易被检测，整体召回显著提升。
     * 小块过小时（最长边 <2000）先放大再识别，进一步改善小字识别。
     *
     * v1.5.99: 抽象为通用分块函数，供 ML Kit 主路径与 PaddleOCR 强引擎路径共用。
     */
    private fun recognizeTiled(src: Bitmap): String =
        recognizeTiledGeneric(src) { OcrEngine.recognizeBitmap(it, skipPostFilter = true) }

    /**
     * 通用分块 OCR。传入 recognizer 可分别接入 ML Kit 或 PaddleOCR 等强引擎。
     * 强引擎路径必须分块，否则整页大图输入会被模型内部压缩到固定输入尺寸（如 960px），
     * 导致密集小字工程图严重漏识别。
     *
     * @param upscalePx 每块放大目标；ML Kit 可用 2000 提升小字召回，PaddleOCR 因内部
     *                  detLongSize=960，放太大无益，建议用 1280。
     */
    private fun recognizeTiledGeneric(src: Bitmap, upscalePx: Int = TILE_UPSCALE_PX, recognizer: (Bitmap) -> String?): String {
        if (src.width <= 0 || src.height <= 0) return ""
        val target = TILE_SPLIT_PX
        val overlap = TILE_OVERLAP_PX
        // v1.9.129: 生成带重叠的切块起点（对齐桌面 RapidOCR 1400px 块/150px 重叠）。
        // 以 step=target-overlap 滑动步进，最后一块锚定到图边(w-target)，保证整图无遗漏覆盖；
        // 相邻块重叠 overlap 像素，避免文字被切块边界切断而漏识。
        val step = max(1, target - overlap)
        fun axisStarts(len: Int): List<Int> {
            val xs = mutableListOf<Int>()
            var x = 0
            while (x + target < len) { xs.add(x); x += step }
            xs.add(len - target) // 末块锚定图边，覆盖剩余区域（消除旧版无重叠时的边界漏识 & 防止末段遗漏）
            return xs.distinct().sorted()
        }
        val xs = axisStarts(src.width)
        val ys = axisStarts(src.height)
        if (xs.size == 1 && ys.size == 1) {
            return try { recognizer(src) ?: "" } catch (_: Throwable) { "" }
        }
        val cols = xs.size; val rows = ys.size
        val sb = StringBuilder()
        var totalChars = 0
        for (y0 in ys) {
            val h = min(target, src.height - y0)
            for (x0 in xs) {
                val w = min(target, src.width - x0)
                if (w <= 0 || h <= 0) continue
                var tile: Bitmap? = null
                try {
                    tile = Bitmap.createBitmap(src, x0, y0, w, h)
                    val ocrBmp = if (max(w, h) < upscalePx) {
                        try {
                            val scale = (upscalePx.toDouble() / max(w, h)).coerceAtMost(3.0)
                            val nw = (w * scale).toInt().coerceAtLeast(1)
                            val nh = (h * scale).toInt().coerceAtLeast(1)
                            Bitmap.createScaledBitmap(tile, nw, nh, true)
                        } catch (_: Throwable) { tile }
                    } else tile
                    val t = recognizer(ocrBmp ?: tile) ?: ""
                    if (t.isNotBlank()) { sb.append(t).append('\n'); totalChars += t.length }
                    if (ocrBmp != null && ocrBmp !== tile) ocrBmp.recycle()
                } catch (_: Throwable) {
                } finally {
                    tile?.recycle()
                }
            }
        }
        Log.d("WordCount", "PdfOcr 分块OCR: ${src.width}x${src.height} -> ${cols}x$rows 块(重叠$overlap), 识别 $totalChars 字")
        return sb.toString().trim()
    }

    /** 合并两份 OCR 结果：以 primary 为基准，把 secondary 中未出现过的行按 normKey 去重追加。
     *  v1.9.136(修正2): 增加模糊去重——对 6× 升采样分块重叠导致的"同一行被切到多块、
     *    或相邻块读出几乎相同内容"进行子串包含/高词重叠检测，避免把 ~1000 字虚增到 ~6000 字。 */
    private fun mergeOcrTexts(primary: String, secondary: String): String {
        if (primary.isBlank()) return secondary.trim()
        if (secondary.isBlank()) return primary.trim()
        // v1.6.5: 词级软去重——主路径已识别出的"有效词"建集合；
        // 强引擎某行若其所有有效词都已存在于主路径，视为重复
        // （如主已识别的大字标题/图框被强引擎重复识别），跳过该行，避免重复计字。
        // 若强引擎行含主路径未出现的新词（小字标注），则保留，保证召回。
        val primaryWords = primary.split(Regex("\\s+"))
            .map { normKey(it) }.filter { it.length >= 2 }.toSet()
        val seenLines = primary.lines().map { normKey(it) }.filter { it.isNotEmpty() }.toMutableSet()
        val sb = StringBuilder(primary.trim())
        for (ln in secondary.lines()) {
            val k = normKey(ln)
            if (k.isEmpty()) continue
            if (isDuplicateOcrLine(k, seenLines)) continue
            val words = ln.split(Regex("\\s+")).map { normKey(it) }.filter { it.length >= 2 }
            val redundant = words.isNotEmpty() && words.all { it in primaryWords }
            if (!redundant) {
                sb.append('\n').append(ln)
                seenLines.add(k)
            }
        }
        return sb.toString().trim()
    }

    /** 判断新行是否与已保留行重复/近似重复。 */
    private fun isDuplicateOcrLine(k: String, seen: Set<String>): Boolean {
        if (k in seen) return true
        val kw = k.split(Regex("\\s+")).filter { it.length >= 2 }
        for (s in seen) {
            // 子串包含：一行完全或部分出现在另一行中（分块边界常把同一行切成两半）
            if (k.contains(s) || s.contains(k)) return true
            // 高词重叠：较长行（≥3 个有效词）若与已保留行共享 ≥80% 有效词，视为同一句重复识别
            val sw = s.split(Regex("\\s+")).filter { it.length >= 2 }
            if (kw.size >= 3 && sw.size >= 3) {
                val inter = kw.intersect(sw).size
                val minLen = minOf(kw.size, sw.size)
                if (inter * 10 >= minLen * 8) return true
            }
        }
        return false
    }

    // ════════════════ v1.9.134: 字高分布门禁（决定要不要跑 6× 升采样）════════════════

    /** 取行高直方图众数（主体字高）。出现次数最多者为众数；并列时取较大字高。 */
    private fun dominantHeight(lines: List<OcrEngine.OcrLine>): Int? {
        if (lines.isEmpty()) return null
        val bins = mutableMapOf<Int, Int>()
        val binW = 6
        for (l in lines) {
            val b = (l.height / binW) * binW
            bins[b] = (bins[b] ?: 0) + 1
        }
        var bestKey: Int? = null
        var bestCount = -1
        for ((k, v) in bins) {
            if (v > bestCount || (v == bestCount && k > (bestKey ?: 0))) {
                bestCount = v
                bestKey = k
            }
        }
        return bestKey
    }

    /**
     * 判断 2× 基准是否已抓到"主体字"。
     * true  = 主体已抓到 / 稀疏页 / 纯图无字 → 跳过 6× 升采样（省时，符合用户"主体已统计就不跑 6×"诉求）。
     * false = 主体字 2× 没抓到 → 跑 6× 并集（符合用户"连主体大小都统计不出就跑 6×"诉求）。
     */
    private fun isBodyCaptured(bmp: Bitmap, baseText: String): Boolean {
        // v1.9.138 修正: 2× 基准 OCR 是真相——若根本没认到字或极少，
        // 不能因 ML Kit 视觉探针"检测到行框"就跳过 6×（P403051 案例：2× 探针能检测到行框
        // 但分辨率太低认不出字 → 必须 6× 升采样才能读出）。只信任 2× OCR 实际字数。
        if (baseText.isBlank() || baseText.length < BODY_MIN_CHARS) return false
        // 2× 已抓到一定量字：走原 v1.9.134 探针逻辑确认主体覆盖，省 6×
        val lines = OcrEngine.recognizeLines(bmp)
        if (lines.isEmpty()) return true   // 探针空 + 2× 有字 → 已够，跳过
        val bodyH = dominantHeight(lines) ?: return true
        val band = max(4, (bodyH * 0.3).toInt())
        val bodyChars = lines.filter { kotlin.math.abs(it.height - bodyH) <= band }.sumOf { it.text.length }
        if (bodyChars < BODY_MIN_CHARS) return true   // 稀疏页
        return baseText.length >= (bodyChars * BODY_CAPTURE_RATIO).toInt()
    }

    /** 归一化行文本用于去重（去空白、去标点符号、小写）。 */
    private fun normKey(s: String): String =
        s.lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

/** v1.8.3: 与 countTextKotlin 的 FarEast 口径完全一致（含全角 U+FF00–FFEF、CJK 标点 U+3000–303F）。 */
    private fun isCjkChar(c: Char): Boolean = isFarEast(c)

    /**
     * v1.8.0: 强引擎中文噪声过滤。
     * PaddleOCR 对纯英文工程图容易把线条、剖面线、图框角标等误识成孤立汉字
     *（如"一"、"口"、"丁"）。这里无条件过滤掉强引擎结果中"短小孤立"的 CJK
     * 片段：1-3 个 CJK 且没有西文词、没有数字的行全部丢弃。
     * 真实中文行通常 CJK≥4，或中英混合，不会只有 1-3 个孤立汉字。
     */
    /** v1.8.2: 改为 internal，供 FileProcessor 在合并 PDF 文本层后二次去噪。 */
    internal fun filterStrongCjkNoise(strong: String): String {
        if (strong.isBlank()) return strong
        return strong.lines().filter { raw ->
            val t = raw.trim()
            if (t.isEmpty()) return@filter false
            val cjk = t.count { isCjkChar(it) }
            if (cjk == 0) return@filter true
            val hasWesternWord = Regex("[A-Za-z]{2,}").containsMatchIn(t)
            val digits = t.count { it.isDigit() }
            // 丢弃：1-3 个孤立 CJK（无西文词、无数字），99% 是工程图符号误识
            !(cjk in 1..3 && !hasWesternWord && digits == 0)
        }.joinToString("\n")
    }

    /**
     * v1.8.3: 与 MainActivity.countTextKotlin 的 FarEast 判定完全一致。
     * countTextKotlin 把“中文(fe)”定义为 FarEast 区间（含汉字/假名/韩文/全角/中文标点），
     * 旧版 isCjkChar 漏掉全角(U+FF00–U+FFEF)与中文标点(U+3000–U+303F)，导致这两类字符
     * 被计入“中文”却不被噪声过滤器移除 → 纯英文图纸始终挂着十几个“中文”。
     */
    private fun isFarEast(c: Char): Boolean {
        val code = c.code
        return code in 0x1100..0x11FF ||   // Hangul Jamo
               code in 0x3000..0x303F ||   // CJK 符号与标点
               code in 0x3130..0x318F ||   // Hangul 兼容字母
               code in 0x3400..0x4DBF ||   // CJK Extension A
               code in 0x4E00..0x9FFF ||   // CJK Unified
               code in 0xA960..0xA97C ||   // Hangul
               code in 0xAC00..0xD7A3 ||   // Hangul Syllables
               code in 0xD7B0..0xD7FF ||   // Hangul
               code in 0xF900..0xFAFF ||   // CJK 兼容汉字
               code in 0xFF00..0xFFEF      // 全角字母/数字/符号
    }

    /**
     * v1.8.3: 文档级中文噪声根除。
     * 若最终合并文本中 FarEast 字符占比极低(<15%，与 OcrEngine.postFilter 阈值一致，
     * 视为 OCR/文本层伪中文)，直接剔除全部 FarEast 字符，保证纯英文图纸中文数=0；
     * 真实中文文档占比高，原样保留。用 countTextKotlin 同源口径，避免“计入却不过滤”的错配。
     */
    internal fun stripNoiseFarEast(text: String): String {
        if (text.isBlank()) return text
        val stats = countTextKotlin(text)   // (words, fe, nc, chars)
        val fe = stats.second
        val chars = stats.fourth
        if (fe == 0) return text
        val ratio = if (chars == 0) 0f else fe.toFloat() / chars.toFloat()
        if (ratio >= 0.15f) return text      // 真实中文文档，保留
        return text.filter { !isFarEast(it) }
    }

    private fun computeScale(w: Int, h: Int): Float {
        val maxSide = max(w, h)
        return if (maxSide <= MAX_DIM) 1f else MAX_DIM.toFloat() / maxSide
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, fromIndex: Int): Int {
        outer@ for (i in fromIndex..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** v1.9.60: 恢复 OCR 状态摘要，让 UI 能区分文字层/OCR。 */
    fun buildOcrNote(pages: Int, mergedTag: String = ""): String {
        return if (mergedTag.isNotBlank()) "已OCR扫描${pages}页($mergedTag)" else "已OCR扫描${pages}页"
    }

    /**
     * v1.5.100: 图片预处理——转灰度 + 自动对比度拉伸 + 对比度增强。
     * 对齐桌面 RapidOCR 的 `_ocr_image_chunked` 预处理（autocontrast + contrast 1.6），
     * 可显著提升 CAD 工程图中浅灰/细小文字的检出率。
     */
    private fun enhanceBitmap(src: Bitmap): Bitmap? {
        return try {
            val w = src.width; val h = src.height
            if (w <= 0 || h <= 0) return@enhanceBitmap null
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            var minLum = 255; var maxLum = 0
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
            }
            val range = maxLum - minLum
            // autocontrast 拉伸到满量程后再增强 1.6 倍
            val scale = if (range <= 0) 1.6f else (255f / range) * 1.6f
            val translate = -minLum * scale
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b)
                val v = (lum * scale + translate).toInt().coerceIn(0, 255)
                pixels[i] = (p and -0x1000000.toInt()) or (v shl 16) or (v shl 8) or v
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            out
        } catch (_: Throwable) { null }
    }

}
