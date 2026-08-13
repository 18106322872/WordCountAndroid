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
object PdfOcrEngine {

    private const val MAX_PAGES = 40
    private const val MAX_DIM = 4096         // v1.5.91: 4K 渲染上限
    private const val TILE_SPLIT_PX = 1000   // v1.5.92: 分块更细，产生更多小块以提升密集小字召回
    private const val TILE_UPSCALE_PX = 2000 // v1.5.92: 每块放大到 2K，比 1400 更清晰
    private const val LOW_RECALL = 200       // v1.5.91: 渲染路径召回低于此字数改试内嵌图

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

    /**
     * 提取 PDF 文本（渲染+OCR）。
     * @param forPrintMode v1.3.81: 为"文字型但Kotlin无法解码"的PDF使用更高渲染质量（PRINT模式+2x分辨率），
     *        提升中文 OCR 识别率。默认 false（扫描件/图片型用普通 DISPLAY 模式即可）。
     */
    fun extractText(context: Context, file: File, forPrintMode: Boolean = false): PdfOcrResult? {
        lastFailReason = FailReason.OK
        lastFailDetail = ""
        lastDiag = ""
        if (!OcrEngine.ocrEnabled) {
            Log.w("WordCount", "PdfOcr 跳过: ocrEnabled=false")
            lastFailReason = FailReason.OCR_DISABLED
            lastDiag = "OCR已禁用(ocrEnabled=false)"
            return null
        }
        Log.d("WordCount", "PdfOcr 开始: ${file.name} (${file.length()} bytes) printMode=$forPrintMode ocrEnabled=${OcrEngine.ocrEnabled} ocrFailed=${OcrEngine.ocrFailed}")

        // 1) 系统 PdfRenderer
        val sys = renderWithSystem(context, file, forPrintMode)
        val sysDiag = lastDiag
        if (sys != null) Log.d("WordCount", "PdfOcr 路径1(Sys) 召回 ${sys.text.length} 字")

        // 2) PdfiumAndroid（仅在系统渲染召回偏低时补充，避免重复渲染）
        val pdfium = if (sys == null || sys.text.length < LOW_RECALL) renderWithPdfium(context, file, forPrintMode) else null
        val pdfiumDiag = lastDiag
        if (pdfium != null) Log.d("WordCount", "PdfOcr 路径2(Pdfium) 召回 ${pdfium.text.length} 字")

        val diag = StringBuilder()
        if (sys != null) diag.append("[Sys:$sysDiag] ")
        if (pdfium != null) diag.append("[Pdfium:$pdfiumDiag] ")

        // 渲染召回已较好 -> 直接采用（密集小字在原生高分辨率下 ML Kit 才能读全）
        if (sys != null && sys.text.length >= LOW_RECALL) { lastDiag = diag.toString(); return sys }
        if (pdfium != null && pdfium.text.length >= LOW_RECALL) { lastDiag = diag.toString(); return pdfium }

        // 3) 内嵌图片提取（多策略）——栅格化/扫描件的最高分辨率来源，优先于低召回的整页渲染
        //    v1.5.91: 实测该 PDF 内嵌图原生 ~2088px，比 2x 整页渲染(~1682px)更清晰，
        //    整页渲染召回偏低时改走内嵌图，可显著拉近与电脑版 RapidOCR 的字数。
        Log.d("WordCount", "PdfOcr 尝试路径3(内嵌图片提取): ${file.name}")
        val images = extractEmbeddedImages(file)
        if (images.isNotEmpty()) {
            val sb = StringBuilder()
            var anyText = false
            val pageHint = sys?.pages ?: pdfium?.pages ?: images.size
            for ((idx, bmp) in images.withIndex()) {
                try {
                    val t = OcrEngine.recognizeBitmap(bmp, skipPostFilter = true)
                    if (t.isNotBlank()) { sb.append(t).append('\n'); anyText = true }
                    Log.d("WordCount", "PdfOcr(内嵌图${idx+1}) OCR: ${t.length}字")
                } catch (_: Throwable) {}
                finally { bmp.recycle() }
            }
            if (anyText) {
                Log.d("WordCount", "PdfOcr(内嵌图片) 成功: ${images.size}张图, ${sb.trim().length}字")
                lastDiag = diag.append("[内嵌图:${sb.trim().length}字]").toString()
                return PdfOcrResult(sb.toString().trim(), pageHint)
            }
            lastFailReason = FailReason.OCR_EMPTY
        } else {
            lastFailReason = FailReason.NO_EMBEDDED_IMAGES
        }

        // 内嵌图无果 -> 兜底返回已有的少量渲染文字（避免完全 0），再不行才判失败
        if (sys != null && sys.text.isNotBlank()) { lastDiag = diag.toString(); return sys }
        if (pdfium != null && pdfium.text.isNotBlank()) { lastDiag = diag.toString(); return pdfium }

        // 汇总最终诊断（v1.3.84: 累积所有路径）
        lastDiag = diag.toString().trim()
        if (lastDiag.isEmpty()) {
            lastDiag = "全部路径失败: reason=${lastFailReason.name}"
            if (lastFailDetail.isNotEmpty()) lastDiag += " detail=$lastFailDetail"
        }
        Log.w("WordCount", "PdfOcr 全部路径失败: ${file.name}, $lastDiag")
        return null
    }

    // ══════════════════ 1) 系统 PdfRenderer ══════════════════

    private fun renderWithSystem(context: Context, file: File, forPrintMode: Boolean = false): PdfOcrResult? {
        val diag = StringBuilder()
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Throwable) {
            lastFailReason = FailReason.RENDER_FAILED
            lastFailDetail = "PFD打开失败: ${e.javaClass.simpleName}"
            lastDiag = "SysRenderer: $lastFailDetail"
            return null
        }
        val renderer = try { PdfRenderer(pfd) } catch (e: Throwable) {
            runCatching { pfd.close() }
            lastFailReason = FailReason.RENDER_FAILED
            lastFailDetail = "PdfRenderer创建失败: ${e.javaClass.simpleName}"
            lastDiag = "SysRenderer: $lastFailDetail"
            return null
        }
        var result: PdfOcrResult? = null
        try {
            val pageCount = renderer.pageCount
            val limit = min(pageCount, MAX_PAGES)
            val sb = StringBuilder()
            var anyRenderedContent = false; var anyOcrText = false; var pageErrors = 0
            var blankCount = 0; var ocrEmptyCount = 0

            diag.append("SysRenderer: ${pageCount}页(forPrint=$forPrintMode)")

            for (i in 0 until limit) {
                try {
                    val page = renderer.openPage(i)
                    try {
                        val w = page.width; val h = page.height
                        if (w <= 0 || h <= 0) continue
                        // v1.3.81: PRINT模式用高分辨率+PRINT渲染，提升中文OCR识别率
                        // v1.3.83: 提升到3x（2x仍可能分辨率不足导致ML Kit空结果）
                        // v1.5.69: DISPLAY 模式也用 2x（原 1x 对小字识别率不足，导致扫描/图文 PDF 字数略少）；
                        //   用 MAX_DIM 上限钳制，避免大图 OOM。PRINT 仍 3x（更清晰渲染）。
                        val baseScale = computeScale(w, h)
                        val rawScale = if (forPrintMode) baseScale * 3f else baseScale * 2f
                        val scale = min(rawScale, MAX_DIM.toFloat() / max(w, h).toFloat())
                        val bw = max(1, (w * scale).toInt()); val bh = max(1, (h * scale).toInt())
                        val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { pageErrors++; diag.append(" [p${i+1}:创建位图失败]"); continue }
                        try {
                            val renderMode = if (forPrintMode) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            page.render(bmp, null, null, renderMode)
                            if (isBlankBitmap(bmp)) { blankCount++; continue }
                            anyRenderedContent = true

                            // v1.3.85: 计算暗像素(文字)占比，判断渲染图是否有可见内容
                            val darkRatio = darkPixelRatio(bmp)
                            if (i == 0) diag.append(" 暗像素:${String.format("%.2f", darkRatio)}%")

                            // v1.3.86: 暗像素>95%→渲染图近乎全黑，自动反色后再OCR
                            val needsInvert = darkRatio > 95.0
                            var ocrBmp: Bitmap = bmp
                            var inverted: Bitmap? = null
                            if (needsInvert) {
                                inverted = invertBitmap(bmp)
                                if (inverted != null) {
                                    ocrBmp = inverted
                                    if (i == 0) diag.append("[已反色]")
                                }
                            }

                            // v1.3.84: 保存第一页渲染Bitmap到缓存，用于调试"ML Kit返回空"问题
                            if (i == 0) {
                                try {
                                    val debugDir = File(context.cacheDir, "pdf_debug")
                                    debugDir.mkdirs()
                                    val debugFile = File(debugDir, "${file.nameWithoutExtension}_p0_render.png")
                                    val fos = java.io.FileOutputStream(debugFile)
                                    bmp.compress(Bitmap.CompressFormat.PNG, 90, fos)
                                    fos.close()
                                    Log.d("WordCount", "PdfOcr 调试: 已保存渲染位图 ${debugFile.absolutePath} (${debugFile.length()} bytes)")
                                    diag.append(" [调试图已保存]")
                                } catch (_: Throwable) {}
                            }

                            // v1.3.83: 区分OCR空结果 vs 异常，便于定位ML Kit问题
                            var ocrResult = ""
                            var ocrError: String? = null
                            try {
                                // v1.5.90: 改用分块 OCR（密集工程图整页召回低），提升 ML Kit 召回率
                                ocrResult = recognizeTiled(ocrBmp)
                            } catch (e: Exception) {
                                ocrError = "${e.javaClass.simpleName}: ${e.message}"
                            }
                            if (ocrResult.isNotBlank()) {
                                sb.append(ocrResult).append('\n'); anyOcrText = true
                                diag.append(" p${i+1}:${ocrResult.length}字")
                            } else if (ocrError != null) {
                                ocrEmptyCount++
                                diag.append(" [p${i+1}:OCR异常:${ocrError.take(60)}]")
                            } else {
                                ocrEmptyCount++
                                val tag = if (needsInvert && inverted != null) "反色后空" else "OCR空结果"
                                diag.append(" [p${i+1}:$tag ${bw}x${bh}]")
                            }
                            inverted?.recycle()
                        } catch (_: Throwable) { pageErrors++ } finally { bmp.recycle() }
                    } finally { page.close() }
                } catch (_: Throwable) { pageErrors++ }
            }

            diag.append(" | 渲染:${if (anyRenderedContent) "有内容" else "全空白"} 空白:$blankCount OCR空:$ocrEmptyCount 错误:$pageErrors")

            val text = sb.toString().trim()
            if (text.isNotBlank()) result = PdfOcrResult(text, pageCount)
            else if (anyRenderedContent) lastFailReason = FailReason.OCR_EMPTY
            else if (pageErrors > 0) lastFailReason = FailReason.RENDER_PARTIAL
            else lastFailReason = FailReason.RENDER_BLANK
        } catch (e: Throwable) {
            lastFailReason = FailReason.RENDER_FAILED
            lastFailDetail = e.javaClass.simpleName + ": " + e.message
        } finally {
            runCatching { renderer.close() }; runCatching { pfd.close() }
        }
        lastDiag = diag.toString()
        return result
    }

    // ══════════════════ 2) PdfiumAndroid ══════════════════

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
     * 密集工程图整页超大（常 3000px+）时，ML Kit 文字检测对小字召回率低；
     * 切成 ~1400px 的小块后每块文字更少、更易被检测，整体召回显著提升。
     * 小块过小时（最长边 <700）先放大再识别，进一步改善小字识别。
     */
    private fun recognizeTiled(src: Bitmap): String {
        if (src.width <= 0 || src.height <= 0) return ""
        val target = TILE_SPLIT_PX
        val cols = minOf(5, maxOf(1, ceil(src.width.toDouble() / target).toInt()))
        val rows = minOf(5, maxOf(1, ceil(src.height.toDouble() / target).toInt()))
        if (cols == 1 && rows == 1) {
            return try { OcrEngine.recognizeBitmap(src, skipPostFilter = true) } catch (_: Throwable) { "" }
        }
        val sb = StringBuilder()
        var totalChars = 0
        for (r in 0 until rows) {
            val y = (r * src.height) / rows
            val h = ((r + 1) * src.height) / rows - y
            for (c in 0 until cols) {
                val x = (c * src.width) / cols
                val w = ((c + 1) * src.width) / cols - x
                if (w <= 0 || h <= 0) continue
                var tile: Bitmap? = null
                try {
                    tile = Bitmap.createBitmap(src, x, y, w, h)
                    val ocrBmp = if (max(w, h) < TILE_UPSCALE_PX) {
                        try {
                            val scale = (TILE_UPSCALE_PX.toDouble() / max(w, h)).coerceAtMost(3.0)
                            val nw = (w * scale).toInt().coerceAtLeast(1)
                            val nh = (h * scale).toInt().coerceAtLeast(1)
                            Bitmap.createScaledBitmap(tile, nw, nh, true)
                        } catch (_: Throwable) { tile }
                    } else tile
                    val t = OcrEngine.recognizeBitmap(ocrBmp ?: tile, skipPostFilter = true)
                    if (t.isNotBlank()) { sb.append(t).append('\n'); totalChars += t.length }
                    if (ocrBmp != null && ocrBmp !== tile) ocrBmp.recycle()
                } catch (_: Throwable) {
                } finally {
                    tile?.recycle()
                }
            }
        }
        Log.d("WordCount", "PdfOcr 分块OCR: ${src.width}x${src.height} -> ${cols}x$rows 块, 识别 $totalChars 字")
        return sb.toString().trim()
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

}
