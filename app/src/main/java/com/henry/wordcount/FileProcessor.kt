package com.henry.wordcount

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * 单一文件统计处理器（统一口径）。
 *
 * 端口自 MainActivity 的「单独打开一个文件」完整逻辑，作为压缩包内层文件与
 * 单独打开文件共用的唯一实现，从根本上保证两者统计路径与结果完全一致：
 *
 *   - PDF   : Kotlin PdfExtractor(Level1) + Python pdfminer(Level2 主力) + ML Kit OCR(Level3)，
 *            含 lowDensity 强制全页 OCR、CID 乱码/失败中文 PDF 的 PRINT/OCR 模式选择、
 *            可信文本层 normKey 软去重合并（v1.5.71）。
 *   - OOXML : OoXmlEngine.extract + metaWords 安全网（无 VML 用 metaWords；VML 且现算>1.5x 回退）。
 *   - 老格式: OldOfficeEngine.extractDocFull / extractXlsDetailed / extractPptFull + 元数据权威字数。
 *   - 图片  : OcrEngine.recognize（与单独打开图片完全一致，无额外配额）。
 *   - DWG   : DwgProcessor.process（dwg→dxf + 编码恢复 + 原始字节兜底 + 文字/编号拆分）。
 *   - 文本  : f.readText(UTF_8)（与单独打开 .txt/未知扩展名一致；不再额外做 GBK 回退，
 *            避免与单独打开结果分歧）。
 *
 * 返回 ProcessOutput(resMap, error)：resMap 结构与 MainActivity 原 resMap 完全一致，
 * 调用方（MainActivity / ArchiveEngine）据此构造 FileEntry / InnerResult，
 * 数字结果因此必然相同。
 */
object FileProcessor {

    data class ProcessOutput(
        val resMap: Map<String, Any?>?,   // 成功时非 null
        val error: String?                 // 失败时非 null（与单独打开该文件得到的错误/空结果一致）
    )

    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "tif", "tiff", "gif", "webp")
    private val OLD_OFFICE_EXTS = setOf("doc", "xls", "ppt")
    private val OOXML_EXTS = setOf("docx", "xlsx", "pptx")
    private val PDF_EXTS = setOf("pdf")
    private val DWG_EXTS = setOf("dwg")
    private val TXT_EXTS = setOf("txt")

    /** 路由到对应格式的处理器。displayName 用于 resMap["name"]（单独打开用原名，压缩包内层用短名）。 */
    suspend fun process(context: Context, file: File, displayName: String): ProcessOutput {
        val ext = file.extension.lowercase().removePrefix(".")
        return when {
            ext in IMAGE_EXTS -> processImage(context, file, displayName)
            ext in OLD_OFFICE_EXTS -> processOldOffice(file, displayName)
            ext in OOXML_EXTS -> processOoXml(file, displayName)
            ext in PDF_EXTS -> processPdf(context, file, displayName)
            ext in DWG_EXTS -> processDwg(context, file, displayName)
            ext in TXT_EXTS || ext.isBlank() -> processText(file, displayName)
            else -> processText(file, displayName) // 单独打开时未知扩展名统一按文本处理
        }
    }

    // ───────────────────────── PDF ─────────────────────────
    private suspend fun processPdf(context: Context, f: File, dName: String): ProcessOutput {
        // ── Level 1: Kotlin PdfExtractor（快速预筛）──
        val ktRes = PdfExtractor.extract(f)
        val ktStats = countTextKotlin(ktRes.text)
        // v1.5.66: 用系统 PdfRenderer 取可靠页数
        val realPages = reliablePdfPageCount(f)

        // ── Level 2: Python pdfminer（文字型 PDF 的主力，与单独打开完全一致）──
        var pyWords = 0; var pyFe = 0; var pyNc = 0; var pyChars = 0; var pyPages = 0
        var pyOk = false
        var pyError: String? = null
        var pyDiag: String? = null
        try {
            pyDiag = PythonEngine.testPython(context)
        } catch (e: Throwable) {
            pyDiag = "Python诊断异常: ${e.javaClass.simpleName}: ${e.message}"
        }
        try {
            val pyResults = PythonEngine.countFiles(context, listOf(f.absolutePath))
            @Suppress("UNCHECKED_CAST")
            val pyList = pyResults as? List<Map<String, Any?>>
            if (!pyList.isNullOrEmpty()) {
                val py0 = pyList[0]
                if (py0["ok"] == true) {
                    val pyData = py0["result"] as? Map<String, Any?>
                    if (pyData != null) {
                        val pyS = pyData["stats"] as? Map<String, Any?>
                        pyWords = (pyS?.get("words") as? Number)?.toInt() ?: 0
                        pyFe = (pyS?.get("fe") as? Number)?.toInt() ?: 0
                        pyNc = (pyS?.get("nc") as? Number)?.toInt() ?: 0
                        pyChars = (pyS?.get("chars") as? Number)?.toInt() ?: 0
                        pyPages = (pyData["pages"] as? Number)?.toInt() ?: ktRes.pages
                        pyOk = true
                    }
                } else {
                    pyError = py0["error"]?.toString()
                }
            }
        } catch (e: Throwable) {
            Log.w("WordCount", "PDF Python pdfminer 异常: $dName - ${e.javaClass.simpleName}: ${e.message}")
        }

        val ktLooksLikeCidGarbage = ktStats.fourth > 100 && ktStats.second == 0
                && ktStats.third > ktStats.fourth * 0.5
        val usePython = pyOk && (pyChars > ktStats.fourth || ktLooksLikeCidGarbage)

        var pdfDiag = buildString {
            appendLine("【PDF诊断】")
            appendLine("Python测试: ${pyDiag ?: "(未执行)"}")
            appendLine("Kotlin提取: ${ktStats.fourth}字(fe=${ktStats.second},可靠=${ktRes.reliable})")
            if (ktRes.diag.isNotEmpty()) appendLine("KT内部: ${ktRes.diag}")
            appendLine("Python提取: ${if (pyOk) "${pyChars}字(fe=$pyFe)" else "失败"}")
            if (!pyOk && pyError != null) appendLine("Python错误: $pyError")
            appendLine("决策: ${if (usePython) "用Python" else "用Kotlin"}(pyOk=$pyOk)")
        }.trimEnd()

        val bestWords = if (usePython) pyWords else ktStats.first
        val bestFe = if (usePython) pyFe else ktStats.second
        val bestNc = if (usePython) pyNc else ktStats.third
        val bestChars = if (usePython) pyChars else ktStats.fourth
        val bestPages = if (usePython && pyPages > 0) pyPages else ktRes.pages
        val bestTextReliable = if (usePython) true else ktRes.reliable

        // v1.5.86: 检测 CID/hex 解码产生的“伪中文”——英文/图片型 PDF 的内容流 hex 数据
        // 被 2-byte CID 模式错误解码后会产生大量 CJK 字符，抬升 bestChars 导致跳过 OCR。
        // 真中文常用字占比通常 >=0.20；随机/伪中文通常 <0.10。以此为据强制 OCR。
        val commonCjkCount = ktRes.text.count { it.code in DwgRawCjkScanner.COMMON_CJK_CHARS }
        val cjkCommonRatio = if (bestFe > 0) commonCjkCount.toDouble() / bestFe else 1.0
        val cjkLooksLikeCidGarbage = !usePython && bestFe > 50 && cjkCommonRatio < 0.10

        val bestCjkRatio = if (bestChars > 0) bestFe.toDouble() / bestChars else 0.0
        val looksLikeGarbage = bestChars > 200 && bestFe < 30 && bestCjkRatio < 0.15
        val isFailedChinesePdf = bestChars > 20 && bestFe == 0 && bestChars < 500
        val denomPages = if (realPages > 1) realPages else 1
        val avgCharsPerPage = bestChars.toDouble() / denomPages
        val avgWordsPerPage = bestWords.toDouble() / denomPages
        val lowDensity = avgCharsPerPage < 800.0 || avgWordsPerPage < 200.0
        val needOcr = bestChars < 10 || (!bestTextReliable && bestChars < 50) || looksLikeGarbage || isFailedChinesePdf || lowDensity || cjkLooksLikeCidGarbage
        if (lowDensity) pdfDiag += "\nOCR触发: 低字数密度(avg ${"%.0f".format(avgWordsPerPage)}字/页<200)→按桌面口径强制全页OCR"
        if (cjkLooksLikeCidGarbage) pdfDiag += "\nOCR触发: CJK常用字占比过低(${"%.2f".format(cjkCommonRatio)})，疑似CID/hex伪中文"

        return if (!needOcr) {
            val resMap = mapOf(
                "name" to dName, "ext" to ".pdf",
                "stats" to mapOf("words" to bestWords, "fe" to bestFe, "nc" to bestNc, "chars" to bestChars),
                "meta" to emptyMap<String, Any?>(),
                "pages" to (if (realPages > 1) realPages else bestPages),
                "diag" to pdfDiag,
                "ocrNote" to "文本提取充分，未触发OCR"
            )
            ProcessOutput(resMap, null)
        } else {
            val ocrForPrintMode = looksLikeGarbage || isFailedChinesePdf
            val ocrRes = PdfOcrEngine.extractText(context, f, forPrintMode = ocrForPrintMode)
            if (ocrRes != null) {
                // v1.5.92: 合并可信文本层，补齐 OCR 漏掉的片段（不再仅限中文，英文/编号
                // 型 PDF 如工程图的文本层同样可补齐）。用 normKey 软去重避免重复计数。
                val mergedText = if (ktRes.reliable && ktStats.fourth > 0 && ktRes.text.isNotBlank()) {
                    val ocrKeys = ocrRes.text.lines().map { normKey(it) }.filter { it.isNotEmpty() }.toSet()
                    val lines = ktRes.text.lines().map { it.trim() }.filter { it.length >= 3 }
                    val sb = StringBuilder(ocrRes.text)
                    for (ln in lines) {
                        if (normKey(ln) !in ocrKeys) sb.append('\n').append(ln)
                    }
                    sb.toString()
                } else ocrRes.text
                val ocrStats = countTextKotlin(mergedText)
                val mergedTag = if (mergedText != ocrRes.text) " +文本层" else ""
                val resMap = mapOf(
                    "name" to dName, "ext" to ".pdf",
                    "stats" to mapOf("words" to ocrStats.first, "fe" to ocrStats.second, "nc" to ocrStats.third, "chars" to ocrStats.fourth),
                    "meta" to emptyMap<String, Any?>(),
                    "pages" to ocrRes.pages,
                    "diag" to "$pdfDiag\n(OCR补充$mergedTag)",
                    "ocrNote" to "已OCR扫描${ocrRes.pages}页$mergedTag"
                )
                ProcessOutput(resMap, null)
            } else {
                if (bestChars > 0) {
                    val ocrDiag = PdfOcrEngine.lastDiag
                    val resMap = mapOf(
                        "name" to dName, "ext" to ".pdf",
                        "stats" to mapOf("words" to bestWords, "fe" to bestFe, "nc" to bestNc, "chars" to bestChars),
                        "meta" to emptyMap<String, Any?>(),
                        "pages" to (if (realPages > 1) realPages else bestPages),
                        "diag" to "$pdfDiag\n(降级:文本少+OCR失败)\nOCR详情: ${if (ocrDiag.isNotEmpty()) ocrDiag else "无"}",
                        "ocrNote" to "⚠️ OCR未成功，已用文本层降级(详见诊断)"
                    )
                    ProcessOutput(resMap, null)
                } else {
                    var pdfPageCount = if (bestPages > 1) bestPages else 1
                    try {
                        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(pfd)
                        pdfPageCount = renderer.pageCount
                        renderer.close(); pfd.close()
                    } catch (_: Throwable) {}
                    val reason = PdfOcrEngine.lastFailReason
                    val detail = PdfOcrEngine.lastFailDetail
                    val errMsg = when (reason) {
                        PdfOcrEngine.FailReason.OCR_DISABLED ->
                            "此 PDF 为扫描件/图片型文件（$pdfPageCount 页），OCR 引擎未就绪。"
                        PdfOcrEngine.FailReason.RENDER_FAILED,
                        PdfOcrEngine.FailReason.PDFIUM_FAILED,
                        PdfOcrEngine.FailReason.PDFIUM_UNAVAILABLE,
                        PdfOcrEngine.FailReason.RENDER_BLANK,
                        PdfOcrEngine.FailReason.PDFIUM_BLANK ->
                            "此 PDF 为扫描件/图片型文件（$pdfPageCount 页），渲染引擎无法处理（可能为 JPEG2000/JBIG2 编码）。${if (detail.isNotBlank()) "($detail)" else ""}"
                        PdfOcrEngine.FailReason.OCR_EMPTY,
                        PdfOcrEngine.FailReason.NO_EMBEDDED_IMAGES ->
                            "此 PDF 为扫描件/图片型文件（$pdfPageCount 页），OCR 未识别到有效文字。"
                        PdfOcrEngine.FailReason.RENDER_PARTIAL ->
                            "此 PDF 部分页面渲染异常（$pdfPageCount 页），OCR 结果不完整。"
                        else -> "无法从该 PDF 提取文字（$pdfPageCount 页，可能为纯图片、加密或损坏文件）。${if (detail.isNotBlank()) "\n原因: $detail" else ""}"
                    }
                    ProcessOutput(null, errMsg)
                }
            }
        }
    }

    // ───────────────────────── OOXML ─────────────────────────
    private fun processOoXml(f: File, dName: String): ProcessOutput {
        val res = OoXmlEngine.extract(f) ?: return ProcessOutput(null, "无法解析此 OOXML 文件（可能损坏或非标准格式）")
        val stats = countTextKotlin(res.text)
        val rawWords = stats.first
        val rawFe = stats.second
        val rawNc = stats.third
        val rawChars = stats.fourth
        val outWords: Int
        val outFe: Int
        val outNc: Int
        val outChars: Int
        if (res.metaWords > 0 && !res.hasVml) {
            outWords = res.metaWords
            val ratio = if (rawWords > 0) rawWords.toDouble() / res.metaWords else 1.0
            outFe = (rawFe / ratio).toInt().coerceAtLeast(0)
            outNc = (rawNc / ratio).toInt().coerceAtLeast(0)
            outChars = (rawChars / ratio).toInt().coerceAtLeast(0)
        } else if (res.metaWords > 0 && rawWords > (res.metaWords * 1.5).toInt()) {
            outWords = res.metaWords
            val ratio = rawWords.toDouble() / res.metaWords
            outFe = (rawFe / ratio).toInt().coerceAtLeast(0)
            outNc = (rawNc / ratio).toInt().coerceAtLeast(0)
            outChars = (rawChars / ratio).toInt().coerceAtLeast(0)
        } else {
            outWords = rawWords
            outFe = rawFe
            outNc = rawNc
            outChars = rawChars
        }
        val outPages = if (res.metaPages > 0) res.metaPages else res.pages
        val outReason = if (res.pagesReason.isNotBlank()) res.pagesReason else null
        val hiddenStats = res.hiddenSheets.map { (n, t) ->
            val s = countTextKotlin(t)
            SheetStat(n, s.first, s.second, s.third, s.fourth)
        }
        val notesStats = res.notesSlides.map { (n, t) ->
            val s = countTextKotlin(t)
            SheetStat(n, s.first, s.second, s.third, s.fourth)
        }
        val resMap = mapOf(
            "name" to dName, "ext" to ".${f.extension.lowercase()}",
            "stats" to mapOf("words" to outWords, "fe" to outFe, "nc" to outNc, "chars" to outChars),
            "meta" to mapOf(
                "sheets" to res.sheets, "hidden_sheets" to hiddenStats,
                "notes_slides" to notesStats, "image_count" to res.imageCount,
                "internal_title" to res.internalTitle
            ),
            "pages" to outPages,
            "pages_reason" to outReason
        )
        return ProcessOutput(resMap, null)
    }

    // ───────────────────────── 老格式(.doc/.xls/.ppt) ─────────────────────────
    private fun processOldOffice(f: File, dName: String): ProcessOutput {
        val extLower = f.extension.lowercase()
        val text: String
        var docPages = 0
        var docWords = 0
        var docChars = 0
        var hiddenText: List<Pair<String, String>> = emptyList()
        var xlsVisible: List<String> = emptyList()
        var pptNotes: List<SheetStat> = emptyList()
        var pptImages = 0
        var xlsImages = 0
        if (extLower == "doc") {
            val docRes = OldOfficeEngine.extractDocFull(f)
            text = docRes.text
            docPages = docRes.pages
            docWords = docRes.words
            docChars = docRes.chars
        } else if (extLower == "xls") {
            val xlsRes = OldOfficeEngine.extractXlsDetailed(f)
            text = xlsRes.text
            hiddenText = xlsRes.hiddenSheets
            xlsVisible = xlsRes.visibleNames
            xlsImages = xlsRes.imageCount
        } else {
            val pptRes = OldOfficeEngine.extractPptFull(f)
            text = pptRes.text
            docPages = pptRes.pages
            pptNotes = pptRes.notesSlides
            pptImages = pptRes.imageCount
        }
        if (text.isBlank()) return ProcessOutput(null, "此老格式文件内容为空或无法读取")
        val stats = countTextKotlin(text)
        val extDot = ".$extLower"
        val pagesValue = if (docPages > 0) docPages else null
        val outWords: Int
        val outFe: Int
        val outNc: Int
        val outChars: Int
        if (docWords > 0) {
            outNc = stats.third
            outFe = if (docWords - outNc > 0) docWords - outNc else 0
            outWords = docWords
            outChars = if (docChars > 0) docChars else stats.fourth
        } else {
            outWords = stats.first
            outFe = stats.second
            outNc = stats.third
            outChars = stats.fourth
        }
        val hiddenStats = hiddenText.map { (n, t) ->
            val s = countTextKotlin(t)
            SheetStat(n, s.first, s.second, s.third, s.fourth)
        }
        // 注：OldOfficeEngine.extractPptFull().notesSlides 已是 List<SheetStat>（与
        // OoXmlEngine 的 List<Pair> 不同），直接作为 notes_slides 透传，勿再解构。
        val resMap = mutableMapOf<String, Any?>(
            "name" to dName, "ext" to extDot,
            "stats" to mapOf("words" to outWords, "fe" to outFe, "nc" to outNc, "chars" to outChars),
            "meta" to mapOf(
                "sheets" to xlsVisible, "hidden_sheets" to hiddenStats,
                "notes_slides" to pptNotes, "image_count" to (pptImages + xlsImages)
            )
        )
        if (pagesValue != null) {
            resMap["pages"] = pagesValue
            resMap["pages_reason"] = "doc_summary_info"
        }
        return ProcessOutput(resMap.toMap(), null)
    }

    // ───────────────────────── 图片(OCR) ─────────────────────────
    private fun processImage(context: Context, f: File, dName: String): ProcessOutput {
        return try {
            val text = OcrEngine.recognize(context, f)
            if (text.isBlank()) {
                val err = if (OcrEngine.ocrFailed)
                    "图片识别失败（模型未就绪或设备不支持）"
                else
                    "未识别到文字（纯图/手写/模糊不清）"
                ProcessOutput(null, err)
            } else {
                val stats = countTextKotlin(text)
                val resMap = mapOf(
                    "name" to dName, "ext" to ".img",
                    "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                    "meta" to emptyMap<String, Any?>()
                )
                ProcessOutput(resMap, null)
            }
        } catch (e: OutOfMemoryError) {
            Runtime.getRuntime().gc()
            Log.w("WordCount", "图片过大 OOM ${f.name}")
            ProcessOutput(null, "图片过大，内存不足")
        } catch (e: Throwable) {
            Log.w("WordCount", "OCR 失败 ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
            ProcessOutput(null, "图片识别失败（${e.message}）")
        }
    }

    // ───────────────────────── DWG ─────────────────────────
    private suspend fun processDwg(context: Context, f: File, dName: String): ProcessOutput {
        return try {
            val res = DwgProcessor.process(context, f, dName)
            val cadParts = res.cadParts
            val cadPartsMeta = cadParts?.let {
                mapOf(
                    "text_words" to it.textWords, "text_fe" to it.textFe, "text_nc" to it.textNc, "text_chars" to it.textChars,
                    "code_words" to it.codeWords, "code_fe" to it.codeFe, "code_nc" to it.codeNc, "code_chars" to it.codeChars,
                    "text_items" to it.textItems, "code_items" to it.codeItems
                )
            }
            val resMap = mapOf(
                "name" to dName, "ext" to ".dwg",
                "stats" to mapOf("words" to res.words, "fe" to res.fe, "nc" to res.nc, "chars" to res.chars),
                "meta" to mapOf<String, Any?>("pages_reason" to (res.pagesReason ?: ""), "needs_pdf" to res.needsPdf,
                    "cad_parts" to cadPartsMeta),
                "pages" to res.pages,
                "diag" to res.diag
            )
            ProcessOutput(resMap, null)
        } catch (e: Throwable) {
            // v1.5.93: 绝不让内层 DWG 被静默丢弃（否则压缩包计数 22→28 类丢失）。
            // 即使异常，也返回一个非空的最小 resMap，使该文件计入总数（数值为 0），保证文件数正确。
            Log.w("WordCount", "DWG 扫描失败(兜底计数) ${f.name}: ${e.message}")
            val resMap = mapOf(
                "name" to dName, "ext" to ".dwg",
                "stats" to mapOf("words" to 0, "fe" to 0, "nc" to 0, "chars" to 0),
                "meta" to mapOf<String, Any?>("pages_reason" to "", "needs_pdf" to true, "cad_parts" to null),
                "pages" to 1,
                "diag" to "DWG处理异常兜底: ${e.message}"
            )
            ProcessOutput(resMap, null)
        }
    }

    // ───────────────────────── 文本(txt/未知) ─────────────────────────
    private fun processText(f: File, dName: String): ProcessOutput {
        return try {
            // 与单独打开一致：纯 UTF-8 读取（不做 GBK 回退）
            val text = f.readText(Charsets.UTF_8)
            if (text.isBlank()) {
                ProcessOutput(null, "文件内容为空")
            } else {
                val stats = countTextKotlin(text)
                val resMap = mapOf(
                    "name" to dName,
                    "ext" to ".txt",
                    "stats" to mapOf(
                        "words" to stats.first,
                        "fe" to stats.second,
                        "nc" to stats.third,
                        "chars" to stats.fourth
                    ),
                    "meta" to emptyMap<String, Any?>()
                )
                ProcessOutput(resMap, null)
            }
        } catch (e: Throwable) {
            Log.w("WordCount", "TXT 读取失败 ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
            ProcessOutput(null, "读取失败（${e.message}）")
        }
    }

    // ───────────────────────── 本地辅助 ─────────────────────────
    /** 与 MainActivity 同款：归一化（去空白/标点/小写）后逐行比对，用于 PDF 文本层软去重。 */
    private fun normKey(s: String): String {
        return s.lowercase()
            .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    }

    /** 可靠 PDF 页数（绕过 Kotlin PdfExtractor 对 ObjStm 压缩流误判 1 页）。 */
    private fun reliablePdfPageCount(file: File): Int {
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(pfd)
            try { r.pageCount } finally { r.close(); runCatching { pfd.close() } }
        } catch (_: Throwable) { 0 }
    }
}
