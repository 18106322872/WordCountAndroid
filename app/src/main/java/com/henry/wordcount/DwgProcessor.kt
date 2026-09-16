package com.henry.wordcount
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
/**
 * DWG 文件完整统计处理器。
 *
 * 端口自 MainActivity 的 DWG 处理主路径，确保压缩包内的 DWG 与单独打开走完全一致的引擎：
 *   1. 原始二进制扫描（scanDwgRaw）作为回退基线
 *   2. dwg→dxf 结构化文字抽取 + 图框页数（DwgIsolatedRunner + DwgDxfParser）
 *   3. 出图口径裁剪、DXF mojibake 检测、GBK/UTF-16 原始字节 CJK 恢复
 *   4. 终极兜底：DwgRawCjkScanner 原始字节扫描
 *   5. CAD 文字/纯编号拆分
 *
 * v1.5.81: 从 MainActivity 提取为共享对象，供 ArchiveEngine 复用。
 */
object DwgProcessor {
    // v1.9.83: 关闭 64MB 硬上限。v1.9.81 引入的该守卫把 68~81MB 的 DXF 直接跳过 Python 解析，
    // 导致这些文件丢失 OLE/IMAGE 文字、字数远低于桌面；而 35~64MB 的文件仍要 10~16 分钟解析，
    // 说明大小不是慢的真因。恢复之前「全部走 Python 主路径」的行为，先保证字数准确；
    // 若后续仍有超大 DXF（如 FA-31018 的 222MB）OOM，再按真实 dxfMB 数据加动态守卫。
    private const val MAX_PY_DXF_BYTES = Long.MAX_VALUE

    // v1.9.84: Python ezdxf 解析单文件耗时随 DXF 大小非线性暴涨（35~44MB≈200~250s，
    // 49~59MB≈720~940s，68~81MB 逾 1000s），且不响应协程取消（原生阻塞调用 withTimeoutOrNull 无效）。
    // 用「独立线程跑 Python + CompletableFuture.get(timeout)」实现真超时：预算内走最准的 ezdxf 主路径，
    // 超时（极少数巨型文件）自动放弃并落入下方 Kotlin 流式扫描兜底，杜绝单文件卡 16 分钟拖垮整批。
    // v2.1.5: 240s→600s。实测 20260913施工图.dwg（DXF 28MB、实体复杂）Python 主路径需 >240s，
    // 240s 掐断后落入 Kotlin 组码兜底 → pages=1、字数偏离桌面。放宽到与 DWG 转换超时同级（600s），
    // 让主路径跑完拿桌面同口径的 words+pages；批量 28 文件的 40 分钟总预算由
    // perFileBudgetMs 的「剩余÷剩余数」动态分配约束，不受此上限影响（分配值通常远小于上限）。
    private const val PY_PARSE_BUDGET_MS = 600_000L

    // v2.1.5: 进度分母用的图框计数（DwgDxfParser.analyze）已取消超时护栏。
    // analyze 仍跑在独立后台线程，主线程不阻塞等待它（防 v2.1.2 大 DXF 挂起导致空白界面）；
    // 真实页数由 analyzePhase 的 Python 主路径 pages 给出（与桌面同口径）。DXF>40MB 直接跳过。
    private const val PAGE_COUNT_MAX_DXF_BYTES = 40L * 1024 * 1024

    // v2.1.9: 分析阶段整体看门狗——转换(:dwgisolated)已有独立 10min 超时，此处覆盖「转换之后」的分析阶段，
    // 任何子步骤(Python 解析/OLE/IMAGE OCR/count)偶发卡死都由此硬上限兜底返回"-"，杜绝"永久冻结需手动杀进程"。
    private const val ANALYZE_TOTAL_TIMEOUT_MS = 25L * 60_000
    // v2.1.10: 转换阶段外层看门狗——DwgIsolatedRunner 自带 8s 绑定 + 10min 转换超时，
    // 但若其 IPC Looper 线程随 :countservice 进程异常死亡而失效，内部超时不再触发；
    // 此处再套一层 11min 硬上限（略大于 10min 转换超时，避免与之竞态），确保转换调用
    // 无论何种原因都尽快返回 null（而非永久挂起），上层 analyzePhase 据此走 Kotlin 兜底/显示"-"，
    // 绝不冻结批次、绝不要求用户手动杀进程。
    private const val CONVERT_PHASE_TIMEOUT_MS = 11L * 60_000
    // v2.1.9: Python cad_core 解析超时护栏——恢复 v2.1.5 去掉的护栏，但放大到 15min：
    // 正常解析<1min 永不误杀（保留 v2.1.5 意图），仅真机内存紧张/GIL 争用导致真卡死时超时，落入 Kotlin 兜底。
    private const val PY_PARSE_TIMEOUT_MS = 15L * 60_000
    // v2.1.9: 兜底分支 DwgDxfParser.analyze 限时（此前注释称有护栏但代码未加），避免极端大 DXF 在此挂起。
    private const val ANALYZE_FRAME_TIMEOUT_MS = 60_000L

    // ===== v1.9.88: 批量总时长预算（硬约束：28 个 DWG ≤ 40 分钟）=====
    // 用户明确要求：超过 40 分钟「时间太长了已经没有意义」。因此不再给单文件固定 240s，
    // 而是在**整批 40 分钟预算内动态分配**：先到先得多，落后时自动压缩后续文件预算、降级可选阶段。
    // 必保阶段：矢量文字（Python ezdxf 主路径 / Kotlin 组码兜底）+ OLE office 文本。
    // 可选阶段（剩余预算不足时跳过）：OLE 位图 OCR、IMAGE 实体 OCR。
    const val DWG_BATCH_BUDGET_MS = 40 * 60 * 1000L
    @Volatile private var batchDeadlineMs = 0L
    @Volatile private var batchPending = 0

    /** 单文件地板预算：无论多紧张都至少留这么多给「Kotlin 抽取 + 转换」，保证不 0 字。 */
    const val PER_FILE_FLOOR_MS = 20_000L

    /** 低于此预算就不启动 Python 解析——启动也必然超时，纯属浪费。 */
    const val PY_MIN_START_MS = 45_000L

    @Volatile private var pyRunaway: Thread? = null

    /**
     * v1.9.88 关键修复：被放弃的 Python 线程仍持有 GIL 并继续跑完解析。
     * 此时任何新的 Python 调用（extractOleOffice / countCadItems / 下一个文件的
     * extractCadDxf）都会**阻塞等待它**，这正是 v1.9.85/1.9.86 里
     * 「超时后反而更慢」的根因：每个大文件白等 240s，还把后续文件一并拖住。
     * 这里显式跟踪失控线程，活着就整体让路给纯 Kotlin 路径。
     */
    fun notePyRunaway(t: Thread) { pyRunaway = t }

    /** 失控 Python 线程是否仍在跑：是则禁用一切 Python 调用。 */
    fun pyBusy(): Boolean {
        val t = pyRunaway ?: return false
        return try { t.isAlive } catch (_: Throwable) { false }
    }

    /** 批量开始前调用：设定整批预算与文件数。
     *  v1.9.88: 已有预算在跑（嵌套压缩包 / 多压缩包连续处理）时只追加计数、不重置 deadline——
     *  40 分钟硬预算从「本批第一个 DWG 开始转换」起算，覆盖整批所有 DWG。 */
    fun beginBatch(totalFiles: Int, budgetMs: Long = DWG_BATCH_BUDGET_MS) {
        if (batchDeadlineMs > 0L) {
            batchPending += totalFiles
            return
        }
        batchDeadlineMs = System.currentTimeMillis() + budgetMs
        batchPending = totalFiles
        pyRunaway = null
    }

    /** 每处理完一个文件调用一次，收缩待处理计数。 */
    fun endFile() {
        if (batchPending > 0) batchPending--
    }

    /** 当前文件可用预算 = 剩余总时间 / 剩余文件数，并夹在 [20s, 600s]。 */
    fun perFileBudgetMs(): Long {
        if (batchDeadlineMs <= 0L) return PY_PARSE_BUDGET_MS
        val remain = batchDeadlineMs - System.currentTimeMillis()
        val files = if (batchPending > 0) batchPending else 1
        return (remain / files).coerceIn(PER_FILE_FLOOR_MS, PY_PARSE_BUDGET_MS)
    }

    /** 剩余总预算（毫秒），用于判断是否还跑得起可选阶段。 */
    fun remainBatchMs(): Long {
        if (batchDeadlineMs <= 0L) return Long.MAX_VALUE
        return batchDeadlineMs - System.currentTimeMillis()
    }

    /**
     * 可选阶段（OLE 位图 OCR / IMAGE OCR）还能花多少毫秒：
     * 必须先给剩余每个文件留够地板预算，剩下的才是可自由支配的余量。
     * 返回 0 表示该阶段应跳过。
     */
    fun optionalStageMs(): Long {
        if (batchDeadlineMs <= 0L) return Long.MAX_VALUE
        val files = if (batchPending > 0) batchPending else 1
        val spare = remainBatchMs() - PER_FILE_FLOOR_MS * files
        return if (spare > 0L) spare else 0L
    }

    data class DwgProcessResult(
        val words: Int,
        val fe: Int,
        val nc: Int,
        val chars: Int,
        val pages: Int,
        val pagesReason: String?,
        val needsPdf: Boolean,
        val diag: String,
        val cadParts: CadPartStats?,
        val finalText: String
    )
    suspend fun process(context: Context, file: File, dName: String = file.name, onProgress: ((String, Int, Int) -> Unit)? = null): DwgProcessResult {
        return try {
            processInner(context, file, dName, onProgress)
        } catch (e: Throwable) {
            // v1.8.9: 任何意外异常都归零显示"-"，绝不再把 scanDwgRaw 二进制噪声当作字数。
            // 此前该兜底返回原始字节扫描结果，对编码混乱/不可读的 DWG 会显示 10059 字/中文54
            // 这类假数字（与桌面"-"不符）。改返回零值 + needsPdf=true，UI 走 needsPdf 分支显示"-"，
            // 既不会让 app 崩溃（压缩包内层 DWG 仍会被统计、仅显示"-"），也不会误导用户。
            Diag.w( "DWG process 异常兜底 $dName: ${e.javaClass.simpleName}: ${e.message}")
            DwgProcessResult(0, 0, 0, 0, 1, "异常兜底", true, "process异常: ${e.message}", CadPartStats(0,0,0,0,0,0,0,0,0,0), "")
        }
    }
    /**
     * v2.1.6: 把 DWG 提取文本拆成「文字部分 / 纯编号部分」，供结果卡片展开文/号两行。
     * 与 MainActivity.computeCadParts 同口径（isCadCodeItem + countTextKotlin），但**始终返回非空**——
     * 即便没有纯编号条目也展示「文字部分」，确保 DWG 始终有可展开的文/号（用户要求）。
     */
    private fun buildCadParts(text: String): CadPartStats {
        val textLines = mutableListOf<String>()
        val codeLines = mutableListOf<String>()
        for (line in text.split("\n")) {
            val s = line.trim()
            if (s.isEmpty()) continue
            if (isCadCodeItem(s)) codeLines.add(s) else textLines.add(s)
        }
        val textStats = countTextKotlin(textLines.joinToString("\n"))
        val codeStats = countTextKotlin(codeLines.joinToString("\n"))
        return CadPartStats(
            textWords = textStats.first, textFe = textStats.second, textNc = textStats.third, textChars = textStats.fourth,
            codeWords = codeStats.first, codeFe = codeStats.second, codeNc = codeStats.third, codeChars = codeStats.fourth,
            textItems = textLines.size, codeItems = codeLines.size
        )
    }

    private suspend fun processInner(context: Context, file: File, dName: String, onProgress: ((String, Int, Int) -> Unit)? = null): DwgProcessResult {
        // v2.1.1: 进度分母改为「图纸页数（图框计数）」而非固定 4 阶段，与用户要求「按页数显示进度」一致，
        // 也消除此前 total=4 与通知栏整体进度分母不一致、主界面/通知栏进度对不上的问题。
        // 先转换拿到 DXF，再用 Kotlin DwgDxfParser.analyze 算图框页数作为分母（与桌面/Python 主路径同一套 pickFrames 口径）。
        val conv = convertPhase(context, file)
        val totalPages = try {
            val dxf = conv.dxfPath
            if (dxf != null) {
                // v2.1.5: 取消「进度分母用 analyze 的 25s 超时护栏」。analyze 仍跑在独立后台线程，
                // 但主线程不再阻塞等待它——大/复杂 DXF 的 analyze 若挂起只会卡住该后台线程，
                // 不会冻结统计主路径（防 v2.1.2 空白界面的老问题）。真实页数由 Python 主路径 pages 给出；
                // 此处仅作进度分母，analyze 完成后由其线程异步回调更新分母。
                val dxfSize = try { java.io.File(dxf).length() } catch (_: Throwable) { 0L }
                if (dxfSize > 0 && dxfSize <= PAGE_COUNT_MAX_DXF_BYTES) {
                    val fut = CompletableFuture<DwgDxfParser.AnalysisResult?>()
                    val t = Thread {
                        try { fut.complete(DwgDxfParser.analyze(dxf)) }
                        catch (_: Throwable) { fut.complete(null) }
                    }
                    t.priority = Thread.MIN_PRIORITY
                    t.start()
                    fut.whenComplete { ar, _ ->
                        val f = (ar?.frames ?: 1).coerceAtLeast(1)
                        runCatching { onProgress?.invoke(dName, 0, f) }
                    }
                    1
                } else {
                    Diag.w("DWG 图框计数跳过：DXF ${dxfSize / 1048576}MB 过大，用 1 页进度分母")
                    1
                }
            } else 1
        } catch (_: Throwable) { 1 }
        runCatching { onProgress?.invoke(dName, 0, totalPages) }
        return try {
            // 转换完成→开始解析：给一个起步进度，表示「已转换，正在解析/计数」
            runCatching { onProgress?.invoke(dName, 1, totalPages) }
            // v2.1.9: 整体看门狗包裹分析阶段，硬上限兜底（任何子步骤卡死都不冻屏）
            val analyzeResult = runWithTimeout(ANALYZE_TOTAL_TIMEOUT_MS, dName) {
                analyzePhase(context, file, dName, conv.dxfPath, conv.diagnostics)
            }
            val res = analyzeResult ?: DwgProcessResult(
                0, 0, 0, 0, 1, "DWG分析超时(引擎卡死)", true,
                "analyze_timeout(>${ANALYZE_TOTAL_TIMEOUT_MS / 1000}s)",
                CadPartStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), ""
            )
            runCatching { onProgress?.invoke(dName, totalPages, totalPages) }
            res
        } finally {
            deleteIntermediateDxf(conv.dxfPath)
        }
    }

    /**
     * v1.9.95: 中间 DXF 转换产物用完即删。
     *
     * 转换产物落在 file.parent——压缩包内层文件的父目录就是 cacheDir 根目录，
     * 单份可达 200MB 以上（29MB 的 DWG 转出 222MB DXF）。此前从不删除：
     * 一天多次测同一个 RAR 会累积几十份 .dxf 堆在 cacheDir 根目录，
     * 而清理功能只扫 arc_ / rar_ / wc_ 前缀，.dxf 永远匹配不到——
     * 这正是「明明测了很多次，清理却提示没有可清理的临时文件」的根因。
     */
    fun deleteIntermediateDxf(dxfPath: String?) {
        if (dxfPath.isNullOrBlank()) return
        runCatching {
            val f = java.io.File(dxfPath)
            if (f.exists() && f.isFile) {
                val mb = f.length() / 1024 / 1024
                if (f.delete()) Diag.d("删除中间 DXF 产物 ${f.name} (${mb}MB)")
            }
        }
    }

    /**
     * v2.1.9: 整体看门狗。在独立工作线程跑 block（suspend），外层用 CompletableFuture.get(timeout) 兜底。
     * 分析阶段任何子步骤(Python 解析/OLE/IMAGE OCR/count)偶发卡死都不会冻结调用线程——
     * 超时即返回 null，由调用方降级为显示"-"（而非永久冻屏需手动杀进程）。
     * block 内部已自行处理 pyBusy/预算，且无线程亲和假设（pyBusy 仅查线程存活），放工作线程安全。
     */
    private fun <T> runWithTimeout(timeoutMs: Long, dName: String, block: suspend () -> T): T? {
        val fut = CompletableFuture<T>()
        val t = Thread {
            try {
                fut.complete(runBlocking { block() })
            } catch (e: Throwable) {
                fut.completeExceptionally(e)
            }
        }
        t.name = "dwg-analyze-watchdog"
        t.start()
        return try {
            fut.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            try { fut.cancel(true) } catch (_: Throwable) {}
            Diag.w("DWG 分析阶段整体超时 $dName: 超过 ${timeoutMs / 1000}s，降级显示'-'（引擎卡死，建议重试）")
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            // 把内部异常原样抛出，交给 process() 的外层 catch 归零并标记"-"
            throw e.cause ?: e
        }
    }

    data class DwgConvertOutcome(val dxfPath: String?, val diagnostics: String)

    /**
     * v1.9.62 流水线阶段 A：只做 DWG→DXF 转换（:dwgisolated 隔离进程，native dwg2dxf）。
     *
     * 与阶段 B（Python cad_core 解析 + OLE/IMAGE OCR + 计数）解耦后，
     * 「下一份图纸的转换」可以和「上一份图纸的 OCR」重叠执行——图片 DWG 批量统计里，
     * OCR 期间转换进程原本全程空闲，这是最主要的一段可重叠时间。
     *
     * 铁律保持：转换仍严格串行（同一时刻只有一个 :dwgisolated 进程），
     * 不破坏「LibreDWG 全局状态污染 → 每文件必须新进程」的约束。
     */
    suspend fun convertPhase(context: Context, file: File): DwgConvertOutcome {
        val pyDxfPath = "${file.parent}/${file.nameWithoutExtension}.dxf"
        val diagnostics = StringBuilder()
        return try {
            withTimeout(CONVERT_PHASE_TIMEOUT_MS) {
                var pyDxfRes = DwgIsolatedRunner.convertToDxf(context, file.absolutePath, pyDxfPath)
                diagnostics.append("convert_rc=${pyDxfRes.errorCode}(try1); ")
                if (!pyDxfRes.diagText.isNullOrBlank()) diagnostics.append("convert_diag=${pyDxfRes.diagText.take(80)}; ")
                // v1.9.31: 转换失败(path==null)或产物不完整(无 EOF)时重试一次。
                if (pyDxfRes.path == null || !isDxfComplete(pyDxfPath)) {
                    diagnostics.append("retry_dxf; ")
                    pyDxfRes = DwgIsolatedRunner.convertToDxf(context, file.absolutePath, pyDxfPath)
                    diagnostics.append("convert_rc2=${pyDxfRes.errorCode}; ")
                    if (!pyDxfRes.diagText.isNullOrBlank()) diagnostics.append("convert_diag2=${pyDxfRes.diagText.take(80)}; ")
                }
                if (pyDxfRes.path != null) {
                    val pyDxfFile = File(pyDxfPath)
                    if (pyDxfFile.exists() && pyDxfFile.length() > 0 && isDxfComplete(pyDxfPath)) {
                        DwgConvertOutcome(pyDxfPath, diagnostics.toString())
                    } else {
                        diagnostics.append("dxf_incomplete_or_empty; ")
                        DwgConvertOutcome(null, diagnostics.toString())
                    }
                } else {
                    diagnostics.append("dxf_path_null; ")
                    DwgConvertOutcome(null, diagnostics.toString())
                }
            }
        } catch (e: TimeoutCancellationException) {
            // 转换阶段外层看门狗触发：隔离进程无响应/内部超时失效，兜底返回 null，
            // 上层 analyzePhase 据此走 Kotlin 组码兜底或显示"-"，绝不冻结批次。
            diagnostics.append("convert_phase_timeout(>${CONVERT_PHASE_TIMEOUT_MS / 1000}s); ")
            Diag.e("DWG 转换阶段外层看门狗超时 ${file.name}: 隔离进程可能已无响应，降级处理")
            DwgConvertOutcome(null, diagnostics.toString())
        } catch (e: Throwable) {
            diagnostics.append("convert_ex=${e.javaClass.simpleName}:${e.message?.take(120)}; ")
            Diag.e( "DWG 转换请求失败 ${file.name}: ${e.javaClass.simpleName}: ${e.message}", e)
            DwgConvertOutcome(null, diagnostics.toString())
        }
    }

    /**
     * v1.9.62 流水线阶段 B：读取已有 DXF 做矢量文字抽取 + OLE/IMAGE OCR + 计数。
     * 由流水线保证单线程串行消费（PaddleOCR 单例 + Chaquopy 单解释器约束），
     * 且不与阶段 A 争用任何引擎。
     */
    suspend fun analyzePhase(
        context: Context,
        file: File,
        dName: String,
        dxfPathIn: String?,
        diagPrefix: String
    ): DwgProcessResult {
        val pyDxfPath = dxfPathIn ?: "${file.parent}/${file.nameWithoutExtension}.dxf"
        val diagnostics = StringBuilder(diagPrefix)
        var pyPathTried = false
        var pySuccessWithText = false

        try {
            val dxfLen = if (dxfPathIn != null && File(pyDxfPath).exists()) File(pyDxfPath).length() else 0L
            if (dxfPathIn != null && dxfLen > 0 && dxfLen <= MAX_PY_DXF_BYTES) {
                    pyPathTried = true
                    // v1.9.80: 分段计时，定位「统计极慢」的真实耗时环节；同时打印 cleaned 条数与
                    // Python 计数异常（cntErr），查清 pyWords 恒为 0 的根因（此前从未打印出来）。
                    val timingsSb = StringBuilder()
                    var tMark = System.currentTimeMillis()
                    fun mark(tag: String) {
                        val now = System.currentTimeMillis()
                        timingsSb.append("$tag=${now - tMark}ms ")
                        tMark = now
                    }
                    // v1.9.88: 只有「预算够 + Python 空闲」才启动 Python 解析。
                    // 预算不够时启动必然超时，等于白烧几十秒；Python 被失控线程占着时
                    // 启动则是排队空等（v1.9.85 每个大文件白等 240s 的真因）。
                    val pyBudget = perFileBudgetMs()
                    val pyRun = pyBudget >= PY_MIN_START_MS && !pyBusy()
                    if (!pyRun) {
                        diagnostics.append("py_skip(b=${pyBudget / 1000}s,busy=${pyBusy()}); ")
                        Diag.w("DWG 跳过 Python 解析 $dName: 预算 ${pyBudget / 1000}s / Python忙=${pyBusy()}，直接走 Kotlin 块展开")
                    }
                    if (pyRun) try {
                        // v1.9.39: 传 outDir 让 cad_core 把内嵌 IMAGE 导出为 PNG；后续 DwgImageOcrExtractor 跑 ML Kit OCR
                        // v1.9.86: 每个 DWG 用独立且确定性的输出目录，避免超时/兜底时扫描到别的文件 PNG。
                            val imgOutDir = "${context.cacheDir}/dwg_imgs/${file.nameWithoutExtension}"
                            try {
                                val imgOutDirFile = java.io.File(imgOutDir)
                                if (imgOutDirFile.exists()) imgOutDirFile.deleteRecursively()
                                imgOutDirFile.mkdirs()
                            } catch (_: Throwable) {}
                            // v1.9.84: 独立线程跑 Python 解析 + 真实超时。CompletableFuture.get(timeout) 在超时后
                            // 立即返回（后台线程继续跑完 Python 不影响后续），我们据此转 Kotlin 兜底。
                            val fut = CompletableFuture<String>()
                            val pyThread = Thread {
                                try {
                                    fut.complete(PythonEngine.extractCadDxf(context, pyDxfPath, file.absolutePath, imgOutDir))
                                } catch (e: Throwable) {
                                    fut.completeExceptionally(e)
                                }
                            }
                            pyThread.start()
                            // v2.1.5: 取消 Python 解析超时护栏——超时只会让主路径提前放弃、落入 Kotlin 兜底给 1 页
                            //（正是本施工图.dwg 的 bug）。改为让 ezdxf 主路径跑完，拿桌面同口径的 words+pages。
                            // 失控线程仍由 pyBusy() 在批量紧张时规避，但不再因超时丢弃正确结果。
                            val pyJsonOrNull = try {
                                // v2.1.9: 恢复 Python 解析超时护栏（v2.1.5 为"不提前放弃大文件"去掉了它，
                                // 却留下"真机偶发卡死永久冻结"的隐患：无超时→线程永久阻塞→无结果无报错 UI 冻屏）。
                                // 用 15min 大超时：正常解析(<1min)永不误杀，仅真机内存紧张/GIL 争用导致真卡死时超时，
                                // 落入 Kotlin 兜底（与 v1.9.84 行为一致），不再永久冻结。
                                fut.get(PY_PARSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            } catch (e: java.util.concurrent.TimeoutException) {
                                diagnostics.append("py_timeout(${PY_PARSE_TIMEOUT_MS / 1000}s); ")
                                Diag.w("DWG Python 解析超时 $dName: 超过 ${PY_PARSE_TIMEOUT_MS / 1000}s，降级 Kotlin 兜底")
                                null
                            } catch (e: java.util.concurrent.ExecutionException) {
                                diagnostics.append("py_ex=${e.javaClass.simpleName}:${e.message}; ")
                                Diag.e("DWG Python 解析异常 $dName: ${e.message}")
                                null
                            }
                            if (pyJsonOrNull == null) {
                                // 落入 Kotlin 兜底分支：抛异常让外层 catch 接管（进入 extractDxfTextsSimple + OLE/IMAGE）
                                throw RuntimeException("py_parse_timeout")
                            }
                            val pyJson = pyJsonOrNull
                        mark("dxfParse")
                        val obj = JSONObject(pyJson)
                        val pyError = if (obj.has("error") && !obj.isNull("error")) obj.optString("error") else null
                        if (!pyError.isNullOrBlank()) {
                            diagnostics.append("py_err=${pyError.take(120)}; ")
                            Diag.e( "DWG Python 返回错误 $dName: ${pyError.take(200)}")
                        }
                        val arr = obj.getJSONArray("items")
                        val items = ArrayList<String>(arr.length())
                        for (i in 0 until arr.length()) items.add(arr.getString(i))
                        // v1.9.39: 读取 cad_core 导出的内嵌 IMAGE PNG 路径列表
                        val imgPngs = if (obj.has("image_pngs") && !obj.isNull("image_pngs")) {
                            val imgs = obj.getJSONArray("image_pngs")
                            ArrayList<String>(imgs.length()).apply {
                                for (i in 0 until imgs.length()) add(imgs.getString(i))
                            }
                        } else emptyList()
                        var pyPages = if (obj.has("pages") && !obj.isNull("pages")) obj.getInt("pages") else 1
                        val pyPagesReason = obj.optString("pages_reason")
                        var pyNeedsPdf = obj.optBoolean("needs_pdf", false)
                        if (items.isEmpty() && !pyNeedsPdf) pyNeedsPdf = true

                        // v1.9.41 FIX: OLE 合并 + IMAGE OCR 不再受 items 非空守卫限制。
                        // 此前 FA-31018 这类"无矢量文字、仅 OLE 嵌入有字"的 DWG 被 items.isEmpty()
                        // 守卫整段跳过，落到 Kotlin 兜底仍失败 → 显示 0 字（桌面据此统计出 2230 字）。
                        // 现改为：矢量/ OLE / IMAGE 三路文字统一并入 allItems；仅当合并后确有文字才
                        // 提前 return，否则（全空/纯噪声）仍落入下方 Kotlin 组码兜底，保持原 items 空行为。
                        val allItems = ArrayList(items)
                        val oleMarks = ArrayList<String>()
                        var oleOfficeOk = false
                        // v1.9.88: Python 失控线程占着 GIL 时跳过 extractOleOffice——启动也是排队空等
                        // （v1.9.85 每个大文件白等 240s 的真因）。OLE office 文本由 Kotlin 侧
                        // DwgOleExtractor（EMF 矢量 / 预览位图 OCR 通道）尽力覆盖。
                        if (!pyBusy()) {
                            try {
                                val oleJson = PythonEngine.extractOleOffice(context, pyDxfPath)
                                val oo = JSONObject(oleJson)
                                val oleErr = if (oo.has("error") && !oo.isNull("error")) oo.optString("error") else null
                                if (!oleErr.isNullOrBlank()) diagnostics.append("ole_err=${oleErr.take(80)}; ")
                                val joined = oo.optString("joined", "")
                                if (joined.isNotBlank()) {
                                    for (ln in joined.lines()) { val t = ln.trim(); if (t.isNotEmpty()) allItems.add(t) }
                                    oleMarks.add("OLE-office")
                                    oleOfficeOk = true
                                }
                            } catch (e: Throwable) {
                                diagnostics.append("ole_office_ex=${e.javaClass.simpleName}:${e.message}; ")
                            }
                        } else {
                            diagnostics.append("ole_office_skip(pybusy); ")
                        }
                        mark("oleOffice")
                        // v1.9.39: OLE office 与位图 OCR 不再互斥。FA-00003 等含大量嵌入位图(图例/截图/LOGO)
                        // 的 DWG 此前因 oleOfficeOk=true 直接跳过位图 OCR 而漏字（桌面 RapidOCR 全量 OCR +4669 字）。
                        var oleDxfLen = 0
                        // v1.9.88: 可选阶段预算——剩余时间不足时跳过 OCR，只保留矢量文字 + OLE office。
                        // OLE 位图 OCR 与 IMAGE OCR 是单文件最耗时环节（每张位图 1~10s+），预算紧张时
                        // 必须让路，保证整批 40 分钟硬约束。
                        val optMs = optionalStageMs()
                        if (optMs > 0) {
                            try {
                                val oleRes = DwgOleExtractor.extractOleText(pyDxfPath, context = context)
                                oleDxfLen = oleRes.text.length
                                // v1.9.53: 诊断 FA-31018 类「仅 OLE 位图」0 字——记录两条 OLE 路径实际返回
                                diagnostics.append("ole_dxf(obj=${oleRes.objects},txt=$oleDxfLen,bmp=${oleRes.bitmapsOcred}); ")
                                if (oleRes.text.isNotBlank()) {
                                    for (ln in oleRes.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) allItems.add(t) }
                                    oleMarks.add("OLE-ocr")
                                }
                            } catch (_: Throwable) {}
                        } else {
                            diagnostics.append("ole_ocr_skip(b=${optMs / 1000}s); ")
                        }
                        mark("oleOcr")
                        // v1.9.80: DXF 通道已取到 OLE 文字时跳过 DWG CFB 通道。两条通道抽的是同一批
                        // 嵌入对象，再跑一遍等于把同样的位图重复 OCR，是单文件 40+ 次 OCR 的来源之一。
                        if (oleDxfLen <= 0) {
                            if (optMs > 0) {
                                try {
                                    val oleRes2 = DwgOleExtractor.extractOleTextFromDwg(file.absolutePath, context = context)
                                    diagnostics.append("ole_dwg(cfb=${oleRes2.objects},txt=${oleRes2.text.length}); ")
                                    if (oleRes2.text.isNotBlank()) {
                                        for (ln in oleRes2.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) allItems.add(t) }
                                        oleMarks.add("DWG-OLE-ocr")
                                    }
                                } catch (_: Throwable) {}
                            } else {
                                diagnostics.append("ole_dwg_skip(budget); ")
                            }
                            mark("oleDwg")
                        } else {
                            diagnostics.append("ole_dwg(skip:DXF通道已取到文字); ")
                        }
                        // v1.9.39: DWG 内嵌 IMAGE 实体 OCR（对齐桌面 RapidOCR IMAGE 口径）。
                        if (imgPngs.isNotEmpty()) {
                            if (optMs > 0) {
                                try {
                                    val imgRes = DwgImageOcrExtractor.extract(context, imgPngs)
                                    if (imgRes.text.isNotBlank()) {
                                        for (ln in imgRes.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) allItems.add(t) }
                                        oleMarks.add("IMG-ocr(${imgRes.imagesScanned}/${imgRes.imagesScanned + imgRes.ocrFailed})")
                                        diagnostics.append("img_ocr=${imgRes.imagesScanned}+${imgRes.ocrFailed}; ")
                                    }
                                } catch (e: Throwable) {
                                    diagnostics.append("img_ocr_ex=${e.javaClass.simpleName}:${e.message}; ")
                                }
                                try {
                                    imgPngs.forEach { java.io.File(it).delete() }
                                } catch (_: Throwable) {}
                                mark("imgOcr")
                            } else {
                                // v1.9.88: 预算不足跳过 IMAGE OCR，但 PNG 仍要清掉，避免残留给后续文件。
                                diagnostics.append("img_ocr_skip(budget); ")
                                try {
                                    imgPngs.forEach { java.io.File(it).delete() }
                                } catch (_: Throwable) {}
                            }
                        }
                        if (allItems.isNotEmpty()) {
                            // 合并后确有文字：仅当"文字全部来自 OLE/IMAGE、矢量 items 为空"时，
                            // 视为已成功提取（与桌面口径一致，桌面 OLE 计为真实字数），故 needs_pdf 置 false；
                            // 若矢量 items 本就非空，保留 cad_core 原汁原味的 pyNeedsPdf 判定。
                            val finalNeedsPdf = if (items.isEmpty()) false else pyNeedsPdf
                            val pyReason = (pyPagesReason ?: "") + (if (oleMarks.isNotEmpty()) "·" + oleMarks.joinToString("·") else "")
                            val mergedAll = allItems.joinToString("\n")
                            val cleanedText = PdfOcrEngine.stripNoiseFarEast(mergedAll)
                            val cleanedItems = cleanedText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            // v1.9.88: Python 计数也受 pyBusy 门控——失控线程占着 GIL 时 countCadItems 会排队空等，
                            // 直接退回 Kotlin countTextKotlin（v1.9.77 已有该兜底，口径一致），不阻塞不空等。
                            var pyWords = 0
                            var pyFe = 0
                            var pyNc = 0
                            var pyChars = 0
                            var cntErr: String? = null
                            if (!pyBusy()) {
                                try {
                                    val co = JSONObject(PythonEngine.countCadItems(context, cleanedItems))
                                    cntErr = if (co.has("error") && !co.isNull("error")) co.optString("error") else null
                                    if (!cntErr.isNullOrBlank()) diagnostics.append("count_err=${cntErr.take(80)}; ")
                                    pyWords = co.optInt("words", 0)
                                    pyFe = co.optInt("fe", 0)
                                    pyNc = co.optInt("nc", 0)
                                    pyChars = co.optInt("chars", 0)
                                } catch (e: Throwable) {
                                    diagnostics.append("count_ex=${e.javaClass.simpleName}:${e.message}; ")
                                }
                            } else {
                                diagnostics.append("count_skip(pybusy); ")
                            }
                            // v1.9.77 FIX：Python 主路径偶发计 0（Chaquopy 跨进程计数异常 / extract 返回空 items）
                            // 兜底——合并文本确有内容时，用 Kotlin countTextKotlin 重新计数（与 v1.9.69~1.9.75
                            // Kotlin 组码兜底口径一致，且此处计入 OLE/IMAGE 文本，更接近桌面真值），保证不回归为 0 字。
                            // 取 Python 与 Kotlin 的较大值：Python 正常时信任 Python，Python 误 0 时退回 Kotlin。
                            val (kWords, kFe, kNc, kChars) = countTextKotlin(mergedAll)
                            val finalWords = if (pyWords > 0) pyWords else kWords
                            val finalFe = if (pyFe > 0) pyFe else kFe
                            val finalNc = if (pyNc > 0) pyNc else kNc
                            val finalChars = if (pyChars > 0) pyChars else kChars
                            mark("count")
                            Diag.d("DWG analyze 主路径结果 $dName: items=${items.size} cleaned=${cleanedItems.size} oleMarks=$oleMarks finalNeedsPdf=$finalNeedsPdf pyWords=$pyWords kWords=$kWords finalWords=$finalWords")
                            // v1.9.80: 分段耗时 + Python 计数异常。此前 count_err 只进 diagnostics 从不打印，
                            // 导致 pyWords 恒为 0 却查不到原因；现单独打一行，下次日志即可定位。
                            Diag.d("DWG 分段耗时 $dName: dxfMB=${File(pyDxfPath).length() / 1048576} $timingsSb| cntErr=${cntErr?.takeLast(150)}")
                            val diag = "PY:${diagnostics}items=${items.size}"
                            Diag.d( "DWG Python主路径 $dName: words=$finalWords(py=$pyWords,k=$kWords) fe=$finalFe nc=$finalNc chars=$finalChars pages=$pyPages($pyReason) items=${items.size}")
                            return DwgProcessResult(finalWords, finalFe, finalNc, finalChars, pyPages, pyReason, finalNeedsPdf, diag, buildCadParts(cleanedText), allItems.joinToString("\n"))
                        }
                    } catch (e: Throwable) {
                        diagnostics.append("py_ex=${e.javaClass.simpleName}:${e.message?.take(120)}; ")
                        Diag.e( "DWG Python主路径失败 $dName: ${e.javaClass.simpleName}: ${e.message}", e)
                    }
                } else if (dxfLen > MAX_PY_DXF_BYTES) {
                    diagnostics.append("dxf_too_big(${dxfLen / 1048576}MB,skip_py); ")
                    Diag.w("DWG DXF 过大跳过Python解析 $dName: ${dxfLen / 1048576}MB，走 Kotlin 兜底")
                } else {
                    if (dxfPathIn == null) diagnostics.append("dxf_path_null; ")
                    else diagnostics.append("dxf_incomplete_or_empty; ")
                }
        } catch (e: Throwable) {
            diagnostics.append("analyze_ex=${e.javaClass.simpleName}:${e.message?.take(120)}; ")
            Diag.e( "DWG 解析阶段异常 $dName: ${e.javaClass.simpleName}: ${e.message}", e)
        }

        Diag.d("DWG 主路径无有效文字 $dName: pyErr=${if (diagnostics.contains("py_err")) "Y" else "N"} pyEx=${if (diagnostics.contains("py_ex")) "Y" else "N"}，进入 Kotlin 兜底/失败分支")
        // v1.9.16 兜底：只要 DXF 文件存在且完整，无论 Python 主路径是否成功/返回空，
        // 都用 Kotlin 抽取再试一次，避免 service/后台/Python 异常导致直接 0 字。
        if (File(pyDxfPath).exists() && isDxfComplete(pyDxfPath)) {
            try {
                val fbLines = ArrayList<String>()
                // v1.9.88: 兜底快渠道改为「流式块展开」extractTextsStreaming——按 INSERT 引用次数展开块 +
                // ATTRIB 实例值 + 图层过滤 + 不去重，语义对齐桌面 _collect_dxf_texts（「禁 0 输出」核心：
                // 预算不足/超时/转换失败时每个文件仍能拿到接近桌面的字数）。
                // 旧 extractDxfTextsSimple 是扁平组码扫描：块定义只计一次 + 全局行级去重，
                // 大文件低估 40~50%（如 31013: 1866 vs 桌面 3160），仅作二级回退。
                val streamTexts = try {
                    DwgDxfParser.extractTextsStreaming(pyDxfPath)
                } catch (e: Throwable) {
                    diagnostics.append("stream_ex=${e.javaClass.simpleName}; ")
                    emptyList()
                }
                if (streamTexts.isNotEmpty()) {
                    fbLines.addAll(streamTexts)
                    diagnostics.append("stream_lines=${streamTexts.size}; ")
                } else {
                    val legacy = extractDxfTextsSimple(pyDxfPath)
                    if (legacy.isNotBlank()) {
                        for (ln in legacy.lines()) { val t = ln.trim(); if (t.isNotEmpty()) fbLines.add(t) }
                        diagnostics.append("legacy_lines=${fbLines.size}; ")
                    }
                }
                // v1.9.39: 兜底分支也合并 OLE office（与主路径一致），不再依赖 Python 成功
                // v1.9.88: pyBusy 时跳过（排队空等），由下方 Kotlin 侧 OLE 通道尽力覆盖
                if (!pyBusy()) {
                    try {
                        val oleJson = PythonEngine.extractOleOffice(context, pyDxfPath)
                        val oo = JSONObject(oleJson)
                        val joined = oo.optString("joined", "")
                        if (joined.isNotBlank()) {
                            for (ln in joined.lines()) { val t = ln.trim(); if (t.isNotEmpty()) fbLines.add(t) }
                        }
                    } catch (_: Throwable) {}
                } else {
                    diagnostics.append("fb_ole_office_skip(pybusy); ")
                }
                // v1.9.88: 兜底分支的 OLE 位图 OCR / IMAGE OCR 同样受 optionalStageMs 预算门控。
                // 预算不足时跳过，但矢量文字（streamTexts）已保证有数。
                val optMs = optionalStageMs()
                if (optMs > 0) {
                    try {
                        val oleRes = DwgOleExtractor.extractOleText(pyDxfPath, context = context)
                        if (oleRes.text.isNotBlank()) { for (ln in oleRes.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) fbLines.add(t) } }
                    } catch (_: Throwable) {}
                    try {
                        val oleRes2 = DwgOleExtractor.extractOleTextFromDwg(file.absolutePath, context = context)
                        if (oleRes2.text.isNotBlank()) { for (ln in oleRes2.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) fbLines.add(t) } }
                    } catch (_: Throwable) {}
                    // v1.9.86: 兜底分支只扫当前 DWG 的确定性 IMAGE 目录，杜绝把别的文件/上一轮残留 PNG 重复 OCR。
                    try {
                        val imgOutDir2 = java.io.File("${context.cacheDir}/dwg_imgs/${file.nameWithoutExtension}")
                        val pngs2 = if (imgOutDir2.exists()) {
                            imgOutDir2.walkTopDown().filter { it.isFile && it.extension == "png" }.map { it.absolutePath }.toList()
                        } else emptyList()
                        if (pngs2.isNotEmpty()) {
                            val imgRes2 = DwgImageOcrExtractor.extract(context, pngs2)
                            if (imgRes2.text.isNotBlank()) {
                                for (ln in imgRes2.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) fbLines.add(t) }
                            }
                        }
                    } catch (_: Throwable) {}
                } else {
                    diagnostics.append("fb_ocr_skip(b=${optMs / 1000}s); ")
                }
                if (fbLines.isNotEmpty()) {
                    val merged = fbLines.joinToString("\n")
                    val cleaned = PdfOcrEngine.stripNoiseFarEast(merged)
                    // v1.9.86: 兜底分支也用 Python countCadItems 计数，与主路径口径一致（避免 Kotlin countTextKotlin 分词差异）。
                    // v1.9.88: pyBusy 时跳过 Python 计数（排队空等），退回 Kotlin 计数——禁 0 输出。
                    val cleanedItems = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    var fbWords = 0
                    var fbFe = 0
                    var fbNc = 0
                    var fbChars = 0
                    var cntErr: String? = null
                    if (!pyBusy()) {
                        try {
                            val co = JSONObject(PythonEngine.countCadItems(context, cleanedItems))
                            cntErr = if (co.has("error") && !co.isNull("error")) co.optString("error") else null
                            if (!cntErr.isNullOrBlank()) diagnostics.append("count_err=${cntErr.take(80)}; ")
                            fbWords = co.optInt("words", 0)
                            fbFe = co.optInt("fe", 0)
                            fbNc = co.optInt("nc", 0)
                            fbChars = co.optInt("chars", 0)
                        } catch (_: Throwable) {}
                    } else {
                        diagnostics.append("fb_count_skip(pybusy); ")
                    }
                    val (_, feBefore, _, charsBefore) = countTextKotlin(merged)
                    diagnostics.append("strip=${feBefore}->${fbFe}/${charsBefore};")
                    // v1.9.88: 禁 0 输出——Python 计数不可用或返回 0 时，强制用 Kotlin 计数出数。
                    if (fbWords <= 0) {
                        val (kWords2, kFe2, kNc2, kChars2) = countTextKotlin(cleaned)
                        if (fbWords <= 0) fbWords = kWords2
                        if (fbFe <= 0) fbFe = kFe2
                        if (fbNc <= 0) fbNc = kNc2
                        if (fbChars <= 0) fbChars = kChars2
                        diagnostics.append("count_fallback=kotlin; ")
                    }
                    // v2.1.1: 兜底分支也必须给出正确页数——Python 超时/失败时 cad_core 的 pages 拿不到，
                    // 旧逻辑硬编码 1 页（48 页图纸被报成 1 页）。这里用 Kotlin DwgDxfParser.analyze 重新算图框页数
                    // （与桌面 count_cad_frames / Python 主路径同一套 pickFrames·paperLayoutFrameCount 口径），
                    // 保证超时文件页数正确。analyze 在兜底路径无超时限制，跑完即返回，且对大文件有 OOM 防护。
                    val fbFrame = try {
                        // v2.1.3: 与主路径同理，兜底分支的 analyze 也加限时护栏，避免极端文件在此挂起。
                        val fut = CompletableFuture<DwgDxfParser.AnalysisResult?>()
                        val t = Thread {
                            try { fut.complete(DwgDxfParser.analyze(pyDxfPath)) }
                            catch (_: Throwable) { fut.complete(null) }
                        }
                        t.priority = Thread.MIN_PRIORITY
                        t.start()
                        // v2.1.9: 兜底分支 analyze 加 60s 限时（此前注释称有护栏但代码未加），避免极端大 DXF 在此挂起。
                        val ar = try { fut.get(ANALYZE_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: Throwable) { null }
                        if (ar != null) {
                            val f = if (ar.frames != null && ar.frames >= 1) ar.frames else 1
                            Pair(f, ar.framesReason)
                        } else Pair(1, null)
                    } catch (_: Throwable) { Pair(1, null) }
                    if (fbWords > 0) {
                        val pagesNote = if (fbFrame.first >= 2) "·布局内图框计数(兜底)" else ""
                        val fbReason = "Kotlin组码兜底" + (if (diagnostics.isNotEmpty()) "·" + diagnostics.toString().take(60) else "") + pagesNote
                        val fbDiag = "FB:${diagnostics}"
                        Diag.d( "DWG Kotlin组码兜底 $dName: words=$fbWords fe=$fbFe nc=$fbNc chars=$fbChars pages=${fbFrame.first}")
                        return DwgProcessResult(fbWords, fbFe, fbNc, fbChars, fbFrame.first, fbReason, false, fbDiag, buildCadParts(cleaned), cleaned)
                    }
                }
                diagnostics.append("fb_text_empty; ")
            } catch (e: Throwable) {
                diagnostics.append("fb_ex=${e.javaClass.simpleName}:${e.message?.take(120)}; ")
                Diag.e( "DWG Kotlin组码兜底失败 $dName: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // 全部失败 -> 显示"-"，但把诊断信息带出来便于排查
        Diag.w( "DWG $dName 全部路径失败：显示'-' diag=${diagnostics}")
        val failReason = "Python解析失败" + (if (diagnostics.isNotEmpty()) "·" + diagnostics.toString().take(60) else "")
        return DwgProcessResult(0, 0, 0, 0, 1, failReason, true, diagnostics.toString(), CadPartStats(0,0,0,0,0,0,0,0,0,0), "")
    }
    private fun isDxfComplete(path: String): Boolean {
        return try {
            val f = java.io.RandomAccessFile(path, "r")
            val size = f.length()
            val n = minOf(size, 512L).toInt()
            f.seek(size - n)
            val buf = ByteArray(n)
            f.readFully(buf)
            f.close()
            val tail = String(buf, Charsets.UTF_8).trim()
            tail.endsWith("EOF")
        } catch (_: Throwable) { false }
    }

    /** v1.9.2: DXF 简易 gc=1/3 兜底抽取。
     *  当 DwgDxfParser 复杂结构化解析在某些真机上漏抽文字（DXF 含 3185 个 TEXT/MTEXT
     *  但 collectDxfTexts 返回空列表）时，直接按 DXF 组码 1/3 顺序对原始字节做
     *  GB18030/UTF-8 双解码抽取，覆盖 _collect_dxf_texts 漏抽的场景。 */
    /** v1.9.3: DXF 简易 gc=1/3 兜底抽取（流式 BufferedReader，避免大 DXF OOM）。
     *  当 DwgDxfParser 复杂结构化解析在某些真机上漏抽文字时，直接按 DXF 组码 1/3
     *  顺序对原始字节做 GB18030/UTF-8 双解码抽取。流式处理 200MB 以下文件不爆内存。 */
    // v1.9.81: 组码集合提到对象级常量。此前每读一行 DXF 就 new 两个 HashSet，
    // 一个 200MB/千万行的 DXF 会在热循环里产生数千万次临时分配，是兜底路径的主要耗时。
    // 必须显式声明 Set<String?>：curType 是可空 String?，若推断为 Set<String> 则 contains 传 null 编译不过。
    private val DXF_TEXT_TYPES: Set<String?> = setOf("TEXT", "MTEXT", "ATTDEF", "ATTRIB", "MULTILEADER")
    private val DXF_TEXT_CODES: Set<String?> = setOf("1", "3", "7", "9", "304", "302")
    // v1.9.81: 去重集合与输出缓冲的内存上限（巨型 DXF 防 OOM）。达到上限后停止去重、直接追加，
    // 宁可少量重复也不让 HashSet 吃光堆内存。
    private const val MAX_SEEN_LINES = 150_000
    private const val MAX_OUT_CHARS = 3_000_000

    private fun extractDxfTextsSimple(path: String): String {
        return try {
            val f = java.io.File(path)
            // v1.9.81: 上限 200MB → 512MB。读取是流式 BufferedReader，内存占用只与去重集合/输出上限有关；
            // 此前 200MB 硬上限会让 FA-31018/FA-31003 这类超大 DXF 直接返回空串 → 主路径被 64MB 守卫跳过后
            // 兜底也拿不到文字 → 字数从 1302 掉成 0。
            if (!f.exists() || f.length() <= 0 || f.length() > 512L * 1024 * 1024) return ""
            val out = StringBuilder()
            val seen = HashSet<String>()  // v1.9.6: 行级去重，防止块炸开编号膨胀
            var curType: String? = null
            java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(f), Charsets.ISO_8859_1)).use { br ->
                var code = br.readLine()
                while (code != null) {
                    val value = br.readLine() ?: break
                    val gc = code.trim()
                    if (gc == "0") { curType = value.trim(); code = br.readLine(); continue }
                    if (gc in DXF_TEXT_CODES && curType in DXF_TEXT_TYPES) {
                        val s = value.trim()
                        if (s.isNotEmpty() && out.length < MAX_OUT_CHARS) {
                            val b = s.toByteArray(Charsets.ISO_8859_1)
                            // v1.9.81: 纯 ASCII 直接取用，跳过 GB18030/UTF-8 双解码。
                            // 图纸文字绝大多数是 ASCII，省掉每行两次 String 构造（千万行级省数秒~数十秒）。
                            var hasHigh = false
                            for (i in b.indices) { if (b[i] < 0) { hasHigh = true; break } }
                            val decoded = if (!hasHigh) s else {
                                val u8 = try { String(b, 0, b.size, Charsets.UTF_8) } catch (_: Throwable) { s }
                                val gb = try { String(b, 0, b.size, charset("GB18030")) } catch (_: Throwable) { s }
                                if (countOfFarEast(gb) >= countOfFarEast(u8)) gb else u8
                            }
                            if (seen.size < MAX_SEEN_LINES) {
                                if (seen.add(decoded)) out.append(decoded).append("\n")
                            } else {
                                out.append(decoded).append("\n")
                            }
                        }
                    }
                    code = br.readLine()
                }
            }
            out.toString()
        } catch (_: Throwable) { "" }
    }
    private fun countOfFarEast(s: String): Int {
        var n = 0
        for (c in s) {
            val cp = c.code
            if (cp in 0x4E00..0x9FFF || cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF) n++
        }
        return n
    }
    private fun keepFarEastOnly(text: String): String {
        return text.filter { it.isFarEastForDwg() }
    }

    private fun Char.isFarEastForDwg(): Boolean {
        val c = code
        return c in 0x1100..0x11FF ||       // Hangul Jamo
               c in 0x3000..0x303F ||       // CJK 符号与标点
               c in 0x3130..0x318F ||       // Hangul Compatibility Jamo
               c in 0x3400..0x4DBF ||       // CJK Extension A
               c in 0x4E00..0x9FFF ||       // CJK Unified Ideographs
               c in 0xA960..0xA97C ||       // Hangul Jamo Extended-A
               c in 0xAC00..0xD7A3 ||       // Hangul Syllables
               c in 0xD7B0..0xD7FF ||       // Hangul Jamo Extended-B
               c in 0xF900..0xFAFF ||       // CJK Compatibility Ideographs
               c in 0xFF00..0xFFEF          // Fullwidth forms
    }
}
