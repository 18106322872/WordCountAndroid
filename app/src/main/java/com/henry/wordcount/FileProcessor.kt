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
 *   - 老格式: OldOfficeEngine.extractDocFull / extractXlsDetailed / extractPptFull——字数用本程序 countTextKotlin 口径（与桌面 Word COM ComputeStatistics 对齐）。
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
        // v1.9.103：L1 文字层同样去噪（与桌面 extract_pdf / L2 pdfminer 口径一致），
        // 避免全角标点 / (cid: 占位符被 FAR_EAST 误计为「中文」/非中文词虚增。
        val ktCleanText = sanitizePdfTextLayer(ktRes.text)
        val ktStats = countTextKotlin(ktCleanText)
        // v1.5.66: 用系统 PdfRenderer 取可靠页数
        val realPages = reliablePdfPageCount(f)

        // v1.9.60: 文字层 PDF 走 Kotlin 快速路径，跳过 Python pdfminer 初始化与抽取，
        // 直接秒出。阈值放宽：可靠文字层每页>=200字符即可；纯英文/非中文路径每页>=200字符；
        // 再兜底：可靠且总字符>=1000也直接走文字层，避免正常文字型 PDF（如 3b016...）被误判进 OCR。
        val denomPagesFast = if (realPages > 1) realPages else 1
        val ktCharsPerPage = ktStats.fourth.toDouble() / denomPagesFast
        val suspiciousLowFe = ktStats.second > 0 && ktStats.second < 30
        // v1.9.120: 检测 L1 原始文本是否被 CID 解码失败污染——若整段就是 (cid:/Latin-1 垃圾，
        // 即使 sanitize 后剩 >=500 nc 字符触发快速路径，结果也是垃圾，必须走 Python/OCR 兜底。
        val l1RawPoisoned = ktRes.text.length >= 10 && pdfTextIsPoisoned(ktRes.text)
        val pureNonCjkFast = ktStats.second == 0 && ktStats.third >= 500 && ktCharsPerPage >= 200.0
        val normalFast = ktRes.reliable && ktStats.fourth >= 500 && ktCharsPerPage >= 200.0 && !suspiciousLowFe
        val anyReliableFast = ktRes.reliable && ktStats.fourth >= 1000 && ktCharsPerPage >= 100.0
        if ((normalFast || pureNonCjkFast || anyReliableFast) && !l1RawPoisoned) {
            return ProcessOutput(mapOf(
                "name" to dName, "ext" to ".pdf",
                "stats" to mapOf("words" to ktStats.first, "fe" to ktStats.second, "nc" to ktStats.third, "chars" to ktStats.fourth),
                "meta" to emptyMap<String, Any?>(),
                "pages" to denomPagesFast,
                "diag" to "【PDF诊断】Kotlin快速路径：${ktStats.fourth}字(fe=${ktStats.second},nc=${ktStats.third})/${denomPagesFast}页，跳过Python/OCR",
                "ocrNote" to "文本提取充分，未触发OCR"
            ), null)
        }

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
        // v1.9.53 FIX: 纯英文字符层 PDF（bestFe==0）不是「伪中文」，不应判为垃圾走 OCR。
        // 桌面 extract_pdf 仅对「含 CJK 的文字层」做 CID 检测；纯英文文字层直接走文字层秒出。
        // 此前 3b01623708fda016f81421fd6e4244dd.pdf（每页 3733 英文字符、fe=0）被误判→整本 19 页走 PaddleOCR 极慢。
        val looksLikeGarbage = bestFe > 0 && bestChars > 200 && bestFe < 30 && bestCjkRatio < 0.15
        val isFailedChinesePdf = bestChars > 20 && bestFe == 0 && bestChars < 500
        val denomPages = if (realPages > 1) realPages else 1
        val avgCharsPerPage = bestChars.toDouble() / denomPages
        val avgWordsPerPage = bestWords.toDouble() / denomPages
        val lowDensity = avgCharsPerPage < 800.0 || avgWordsPerPage < 200.0
        val needOcr = bestChars < 10 || (!bestTextReliable && bestChars < 50) || looksLikeGarbage || isFailedChinesePdf || lowDensity || cjkLooksLikeCidGarbage || (l1RawPoisoned && !usePython)
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
                // v1.9.52: 对齐桌面版 extract_pdf 的 whole_poisoned 口径——触发 OCR 分支说明
                // 该 PDF 是图纸类/图片型/文字层污染，应以整页 OCR 结果为准，不再把 Level1/Level2
                // 的少量文本层补回 OCR（避免重复计数/污染）。
                val finalText = PdfOcrEngine.stripNoiseFarEast(PdfOcrEngine.filterStrongCjkNoise(ocrRes.text))
                val ocrStats = countTextKotlin(finalText)
                val resMap = mapOf(
                    "name" to dName, "ext" to ".pdf",
                    "stats" to mapOf("words" to ocrStats.first, "fe" to ocrStats.second, "nc" to ocrStats.third, "chars" to ocrStats.fourth),
                    "meta" to emptyMap<String, Any?>(),
                    "pages" to ocrRes.pages,
                    "diag" to "$pdfDiag\n(OCR补充)",
                    "ocrNote" to PdfOcrEngine.buildOcrNote(ocrRes.pages, "")
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
        // v1.9.99：一律采用原始抽取结果，不再用 docProps/app.xml 的 Words 覆盖。
        // 原逻辑有两个覆盖分支，都会按 ratio 等比缩放 fe/nc/chars：
        //   ① metaWords > 0 && !hasVml                     -> outWords = metaWords
        //   ② metaWords > 0 && rawWords > metaWords * 1.5  -> outWords = metaWords
        // 这两个分支在中文文档上会严重偏低。实测 HQ6中文说明书.docx：
        //   桌面版 wordcount.py（不依赖 Word）  5624 词 / 中文 4964 / 非中文 660
        //   本引擎原始抽取                      5390 词 / 中文 4774 / 非中文 616
        //   app.xml metaWords                   3385  <- 比前两者低 2000+ 词
        // 因 5390 > 3385 * 1.5 = 5077，分支②被触发，把正确的 5390 钳成 3385，
        // 手机端因此比桌面版少约 40%（截图 3385 / 中文 2998 / 非中文 386 与
        // ratio=5390/3385=1.5923 的等比缩放结果完全吻合）。
        // Word 写入 app.xml 的 Words 口径含页眉页脚脚注尾注、且其 CJK 计数与
        // 桌面版 FarEast 正则并不一致，数值可能远高于也可能远低于正文实际字数，
        // 不能当作正确值或上界使用。桌面版 wordcount.py 从不读取该元数据修正计数，
        // 为与其逐字对齐，此处直接用原始抽取结果；若将来发现真的多算，应当修
        // 解析器本身，而不是靠元数据钳制掩盖。
        val outWords = rawWords
        val outFe = rawFe
        val outNc = rawNc
        val outChars = rawChars
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
        var hiddenText: List<Pair<String, String>> = emptyList()
        var xlsVisible: List<String> = emptyList()
        var pptNotes: List<SheetStat> = emptyList()
        var pptImages = 0
        var xlsImages = 0
        if (extLower == "doc") {
            val docRes = OldOfficeEngine.extractDocFull(f)
            text = docRes.text
            docPages = docRes.pages
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
        // v1.9.105: .doc 统一用本程序「Word 口径」统计抽取文本（与 docx/txt/pdf/xls/ppt 一致），
        // 不再用 SummaryInformation.wordCount 覆盖 words、再用本程序 nc 算残差 fe —— 那种混算会让 fe
        // 变成「Word 保存字数 − 本程序非中文词数」的残差，与桌面（Word COM ComputeStatistics：
        // words=Stat0/fe=Stat6/nc=words−fe/chars=Stat3，四数同源）口径不一致。桌面 .doc 走 Word COM
        // ComputeStatistics，约等于对「含脚注/尾注/文本框的完整文本」用本程序口径统计；HWPF
        // WordExtractor.text() 默认已含脚注/尾注/文本框，故直接 countTextKotlin(text) 即可对齐。
        // pages 仍取自 SummaryInformation.pageCount（与桌面 ComputeStatistics 页数一致）。
        val stats = countTextKotlin(text)
        val extDot = ".$extLower"
        val pagesValue = if (docPages > 0) docPages else null
        val outWords = stats.first
        val outFe = stats.second
        val outNc = stats.third
        val outChars = stats.fourth
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
