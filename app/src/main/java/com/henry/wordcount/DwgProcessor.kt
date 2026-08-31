package com.henry.wordcount
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
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
    suspend fun process(context: Context, file: File, dName: String = file.name): DwgProcessResult {
        return try {
            processInner(context, file, dName)
        } catch (e: Throwable) {
            // v1.8.9: 任何意外异常都归零显示"-"，绝不再把 scanDwgRaw 二进制噪声当作字数。
            // 此前该兜底返回原始字节扫描结果，对编码混乱/不可读的 DWG 会显示 10059 字/中文54
            // 这类假数字（与桌面"-"不符）。改返回零值 + needsPdf=true，UI 走 needsPdf 分支显示"-"，
            // 既不会让 app 崩溃（压缩包内层 DWG 仍会被统计、仅显示"-"），也不会误导用户。
            Diag.w( "DWG process 异常兜底 $dName: ${e.javaClass.simpleName}: ${e.message}")
            DwgProcessResult(0, 0, 0, 0, 1, "异常兜底", true, "process异常: ${e.message}", null, "")
        }
    }
    private suspend fun processInner(context: Context, file: File, dName: String): DwgProcessResult {
        // v1.9.62: 拆成「阶段A 转换」+「阶段B 解析/OCR」。单文件处理与旧行为完全一致，
        // 只是把两段解耦，供批量流水线（下一份图纸的转换与上一份图纸的 OCR 重叠）复用。
        val conv = convertPhase(context, file)
        return analyzePhase(context, file, dName, conv.dxfPath, conv.diagnostics)
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
        try {
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
                    return DwgConvertOutcome(pyDxfPath, diagnostics.toString())
                } else {
                    diagnostics.append("dxf_incomplete_or_empty; ")
                }
            } else {
                diagnostics.append("dxf_path_null; ")
            }
        } catch (e: Throwable) {
            diagnostics.append("convert_ex=${e.javaClass.simpleName}:${e.message?.take(120)}; ")
            Diag.e( "DWG 转换请求失败 ${file.name}: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        return DwgConvertOutcome(null, diagnostics.toString())
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
            if (dxfPathIn != null && File(pyDxfPath).exists() && File(pyDxfPath).length() > 0) {
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
                    try {
                        // v1.9.39: 传 outDir 让 cad_core 把内嵌 IMAGE 导出为 PNG；后续 DwgImageOcrExtractor 跑 ML Kit OCR
                            val imgOutDir = "${context.cacheDir}/dwg_imgs/${file.nameWithoutExtension}_${System.currentTimeMillis()}"
                            val pyJson = PythonEngine.extractCadDxf(context, pyDxfPath, file.absolutePath, imgOutDir)
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
                        mark("oleOffice")
                        // v1.9.39: OLE office 与位图 OCR 不再互斥。FA-00003 等含大量嵌入位图(图例/截图/LOGO)
                        // 的 DWG 此前因 oleOfficeOk=true 直接跳过位图 OCR 而漏字（桌面 RapidOCR 全量 OCR +4669 字）。
                        var oleDxfLen = 0
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
                        mark("oleOcr")
                        // v1.9.80: DXF 通道已取到 OLE 文字时跳过 DWG CFB 通道。两条通道抽的是同一批
                        // 嵌入对象，再跑一遍等于把同样的位图重复 OCR，是单文件 40+ 次 OCR 的来源之一。
                        if (oleDxfLen <= 0) {
                            try {
                                val oleRes2 = DwgOleExtractor.extractOleTextFromDwg(file.absolutePath, context = context)
                                diagnostics.append("ole_dwg(cfb=${oleRes2.objects},txt=${oleRes2.text.length}); ")
                                if (oleRes2.text.isNotBlank()) {
                                    for (ln in oleRes2.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) allItems.add(t) }
                                    oleMarks.add("DWG-OLE-ocr")
                                }
                            } catch (_: Throwable) {}
                            mark("oleDwg")
                        } else {
                            diagnostics.append("ole_dwg(skip:DXF通道已取到文字); ")
                        }
                        // v1.9.39: DWG 内嵌 IMAGE 实体 OCR（对齐桌面 RapidOCR IMAGE 口径）。
                        if (imgPngs.isNotEmpty()) {
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
                            val co = JSONObject(PythonEngine.countCadItems(context, cleanedItems))
                            val cntErr = if (co.has("error") && !co.isNull("error")) co.optString("error") else null
                            if (!cntErr.isNullOrBlank()) diagnostics.append("count_err=${cntErr.take(80)}; ")
                            val pyWords = co.optInt("words", 0)
                            val pyFe = co.optInt("fe", 0)
                            val pyNc = co.optInt("nc", 0)
                            val pyChars = co.optInt("chars", 0)
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
                            Diag.d("DWG 分段耗时 $dName: $timingsSb| cntErr=${cntErr?.take(150)}")
                            val diag = "PY:${diagnostics}items=${items.size}"
                            Diag.d( "DWG Python主路径 $dName: words=$finalWords(py=$pyWords,k=$kWords) fe=$finalFe nc=$finalNc chars=$finalChars pages=$pyPages($pyReason) items=${items.size}")
                            return DwgProcessResult(finalWords, finalFe, finalNc, finalChars, pyPages, pyReason, finalNeedsPdf, diag, null, allItems.joinToString("\n"))
                        }
                    } catch (e: Throwable) {
                        diagnostics.append("py_ex=${e.javaClass.simpleName}:${e.message?.take(120)}; ")
                        Diag.e( "DWG Python主路径失败 $dName: ${e.javaClass.simpleName}: ${e.message}", e)
                    }
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
        // 都用 Kotlin 简易组码抽取再试一次，避免 service/后台/Python 异常导致直接 0 字。
        if (File(pyDxfPath).exists() && isDxfComplete(pyDxfPath)) {
            try {
                val fbLines = ArrayList<String>()
                val fallbackText = extractDxfTextsSimple(pyDxfPath)
                if (fallbackText.isNotBlank()) {
                    for (ln in fallbackText.lines()) { val t = ln.trim(); if (t.isNotEmpty()) fbLines.add(t) }
                }
                // v1.9.39: 兜底分支也合并 OLE（与主路径一致），不再依赖 Python 成功
                try {
                    val oleJson = PythonEngine.extractOleOffice(context, pyDxfPath)
                    val oo = JSONObject(oleJson)
                    val joined = oo.optString("joined", "")
                    if (joined.isNotBlank()) {
                        for (ln in joined.lines()) { val t = ln.trim(); if (t.isNotEmpty()) fbLines.add(t) }
                    }
                } catch (_: Throwable) {}
                try {
                    val oleRes = DwgOleExtractor.extractOleText(pyDxfPath, context = context)
                    if (oleRes.text.isNotBlank()) { for (ln in oleRes.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) fbLines.add(t) } }
                } catch (_: Throwable) {}
                try {
                    val oleRes2 = DwgOleExtractor.extractOleTextFromDwg(file.absolutePath, context = context)
                    if (oleRes2.text.isNotBlank()) { for (ln in oleRes2.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) fbLines.add(t) } }
                } catch (_: Throwable) {}
                // v1.9.39: 兜底分支也跑 IMAGE OCR（用 cacheDir 已有 PNG，可能不存在则跳过）
                try {
                    val imgOutDir2 = java.io.File("${context.cacheDir}/dwg_imgs")
                    val pngs2 = if (imgOutDir2.exists()) imgOutDir2.listFiles { f -> f.isFile && f.extension == "png" }?.map { it.absolutePath } ?: emptyList() else emptyList()
                    if (pngs2.isNotEmpty()) {
                        val imgRes2 = DwgImageOcrExtractor.extract(context, pngs2)
                        if (imgRes2.text.isNotBlank()) {
                            for (ln in imgRes2.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) fbLines.add(t) }
                        }
                    }
                } catch (_: Throwable) {}
                if (fbLines.isNotEmpty()) {
                    val merged = fbLines.joinToString("\n")
                    val cleaned = PdfOcrEngine.stripNoiseFarEast(merged)
                    val (_, feBefore, _, charsBefore) = countTextKotlin(merged)
                    val (fbWords, fbFe, fbNc, fbChars) = countTextKotlin(cleaned)
                    diagnostics.append("strip=${feBefore}->${fbFe}/${charsBefore};")
                    if (fbWords > 0) {
                        val fbReason = "Kotlin组码兜底" + (if (diagnostics.isNotEmpty()) "·" + diagnostics.toString().take(60) else "")
                        val fbDiag = "FB:${diagnostics}"
                        Diag.d( "DWG Kotlin组码兜底 $dName: words=$fbWords fe=$fbFe nc=$fbNc chars=$fbChars")
                        return DwgProcessResult(fbWords, fbFe, fbNc, fbChars, 1, fbReason, false, fbDiag, null, cleaned)
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
        return DwgProcessResult(0, 0, 0, 0, 1, failReason, true, diagnostics.toString(), null, "")
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
    private fun extractDxfTextsSimple(path: String): String {
        return try {
            val f = java.io.File(path)
            if (!f.exists() || f.length() <= 0 || f.length() > 200L * 1024 * 1024) return ""
            val out = StringBuilder()
            val seen = HashSet<String>()  // v1.9.6: 行级去重，防止块炸开编号膨胀
            var curType: String? = null
            java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(f), Charsets.ISO_8859_1)).use { br ->
                var code = br.readLine()
                while (code != null) {
                    val value = br.readLine() ?: break
                    val gc = code.trim()
                    if (gc == "0") { curType = value.trim(); code = br.readLine(); continue }
                    if ((gc in setOf("1", "3", "7", "9", "304", "302")) && curType in setOf("TEXT", "MTEXT", "ATTDEF", "ATTRIB", "MULTILEADER")) {
                        val s = value.trim()
                        if (s.isNotEmpty()) {
                            val b = s.toByteArray(Charsets.ISO_8859_1)
                            val u8 = try { String(b, 0, b.size, Charsets.UTF_8) } catch (_: Throwable) { s }
                            val gb = try { String(b, 0, b.size, charset("GB18030")) } catch (_: Throwable) { s }
                            val decoded = if (countOfFarEast(gb) >= countOfFarEast(u8)) gb else u8
                            if (seen.add(decoded)) {
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
