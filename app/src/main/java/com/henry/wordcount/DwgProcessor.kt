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
            Log.w("WordCount", "DWG process 异常兜底 $dName: ${e.javaClass.simpleName}: ${e.message}")
            DwgProcessResult(0, 0, 0, 0, 1, "异常兜底", true, "process异常: ${e.message}", null, "")
        }
    }
    private suspend fun processInner(context: Context, file: File, dName: String): DwgProcessResult {
        // ── v1.9.11: 主路径改为 Python cad_core（ezdxf 同源，与桌面 wordcount.py 逐字节对齐）──
        val pyDxfPath = "${file.parent}/${file.nameWithoutExtension}.dxf"
        try {
            val pyDxfRes = DwgIsolatedRunner.convertToDxf(context, file.absolutePath, pyDxfPath)
            if (pyDxfRes.path != null) {
                val pyDxfFile = File(pyDxfPath)
                if (pyDxfFile.exists() && pyDxfFile.length() > 0 && isDxfComplete(pyDxfPath)) {
                    val pyJson = PythonEngine.extractCadDxf(context, pyDxfPath, file.absolutePath)
                    val obj = JSONObject(pyJson)
                    val arr = obj.getJSONArray("items")
                    val items = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) items.add(arr.getString(i))
                    val pyPages = if (obj.has("pages") && !obj.isNull("pages")) obj.getInt("pages") else 1
                    val pyPagesReason = obj.optString("pages_reason")
                    val pyNeedsPdf = obj.optBoolean("needs_pdf", false)
                    // OLE 合并：office 嵌入文字走 Python（与桌面 cad_ole_ocr 同源）；位图 OLE 兜底走 Kotlin ML Kit OCR
                    val allItems = ArrayList(items)
                    val oleMarks = ArrayList<String>()
                    var oleOfficeOk = false
                    try {
                        val oleJson = PythonEngine.extractOleOffice(context, pyDxfPath)
                        val oo = JSONObject(oleJson)
                        val joined = oo.optString("joined", "")
                        if (joined.isNotBlank()) {
                            for (ln in joined.lines()) { val t = ln.trim(); if (t.isNotEmpty()) allItems.add(t) }
                            oleMarks.add("OLE-office")
                            oleOfficeOk = true
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "DWG OLE-office(Python)失败 $dName: ${e.message}")
                    }
                    if (!oleOfficeOk) {
                        try {
                            val oleRes = DwgOleExtractor.extractOleText(pyDxfPath)
                            if (oleRes.text.isNotBlank()) {
                                for (ln in oleRes.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) allItems.add(t) }
                                oleMarks.add("OLE-ocr")
                            }
                        } catch (_: Throwable) {}
                        try {
                            val oleRes2 = DwgOleExtractor.extractOleTextFromDwg(file.absolutePath)
                            if (oleRes2.text.isNotBlank()) {
                                for (ln in oleRes2.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) allItems.add(t) }
                                oleMarks.add("DWG-OLE-ocr")
                            }
                        } catch (_: Throwable) {}
                    }
                    var pyWords = 0; var pyFe = 0; var pyNc = 0; var pyChars = 0
                    try {
                        val co = JSONObject(PythonEngine.countCadItems(context, allItems))
                        pyWords = co.optInt("words", 0); pyFe = co.optInt("fe", 0)
                        pyNc = co.optInt("nc", 0); pyChars = co.optInt("chars", 0)
                    } catch (e2: Throwable) {
                        Log.w("WordCount", "DWG Python count 失败→Kotlin $dName: ${e2.message}")
                        val kt = countTextKotlin(allItems.joinToString("\n"))
                        pyWords = kt.first; pyFe = kt.second; pyNc = kt.third; pyChars = kt.fourth
                    }
                    val pyReason = (pyPagesReason ?: "") + (if (oleMarks.isNotEmpty()) "·" + oleMarks.joinToString("·") else "")
                    Log.d("WordCount", "DWG Python主路径 $dName: words=$pyWords fe=$pyFe nc=$pyNc chars=$pyChars pages=$pyPages($pyReason)")
                    return DwgProcessResult(pyWords, pyFe, pyNc, pyChars, pyPages, pyReason, pyNeedsPdf, pyReason, null, allItems.joinToString("\n"))
                }
            }
        } catch (e: Throwable) {
            Log.w("WordCount", "DWG Python主路径失败→回退Kotlin $dName: ${e.javaClass.simpleName}: ${e.message}")
        }
        // ── 回退：原 Kotlin 解析链（保留）──
        // ── 兜底层：原始二进制扫描（保留为回退） ──
        val rawText = scanDwgRaw(file.absolutePath)
        var finalStats = countTextKotlin(rawText)
        var finalText = rawText
        val rawChars = finalStats.fourth
        // ── 主路径（v1.5.16）：dwg→dxf 结构化文字抽取 + 图框页数 ──
        var dxfPages: Int? = null
        var dxfPagesReason: String? = null
        var dxfMojibake = false
        var nonChineseDxf = false
        var dxfText = ""
        var dxfDiag = ""
        var printedScope = false
        val dxfPath = "${file.parent}/${file.nameWithoutExtension}.dxf"
        val dxfRes = DwgIsolatedRunner.convertToDxf(context, file.absolutePath, dxfPath)
        var dxfSuccess = false
        if (dxfRes.path != null) {
            val dxfFile = File(dxfPath)
            if (dxfFile.exists() && dxfFile.length() > 0 && isDxfComplete(dxfPath)) {
                dxfSuccess = true
                val analysis = DwgDxfParser.analyze(dxfPath)
                dxfText = analysis.text
                dxfDiag = analysis.diag
                var dxfStats = countTextKotlin(dxfText)
                dxfPages = analysis.frames
                dxfPagesReason = analysis.framesReason
                // ── v1.5.33: 出图口径裁剪 ──
                if (analysis.printedText.isNotEmpty() && dxfPages != null && dxfPages > 0) {
                    val allWords = dxfStats.second + dxfStats.third
                    val allDensity = allWords.toDouble() / dxfPages
                    if (allDensity > 3000.0 && allWords > dxfPages * 1000) {
                        var pCjk = 0; var pCommon = 0
                        val pSet = HashSet<Char>()
                        for (ch in analysis.printedText) {
                            val cp = ch.code
                            if (cp in 0x4E00..0x9FFF) {
                                pCjk++; pSet.add(ch)
                                if (cp in DwgRawCjkScanner.COMMON_CJK_CHARS) pCommon++
                            }
                        }
                        val pCr = if (pCjk > 0) pCommon.toDouble() / pCjk else 0.0
                        val pDv = if (pCjk > 0) pSet.size.toDouble() / pCjk else 1.0
                        val pStats = countTextKotlin(analysis.printedText)
                        val pWords = pStats.second + pStats.third
                        if (pCjk >= 200 && pCr >= 0.30 && pDv < 0.60 && pWords in 1 until allWords) {
                            dxfText = analysis.printedText
                            dxfStats = pStats
                            printedScope = true
                            dxfPagesReason = "${dxfPagesReason ?: ""}·出图口径统计"
                            Log.d("WordCount", "DWG 出图口径 $dName: 全量=$allWords(密度${"%.0f".format(allDensity)}) → 出图=$pWords cjk=$pCjk cr=${"%.2f".format(pCr)} dv=${"%.2f".format(pDv)}")
                        } else {
                            Log.d("WordCount", "DWG 出图口径 REJECTED $dName: pWords=$pWords all=$allWords cjk=$pCjk cr=${"%.2f".format(pCr)} dv=${"%.2f".format(pDv)}")
                        }
                    }
                }
                // ── v1.5.24: DXF mojibake 检测 ──
                var dxfCjkCount = 0; var dxfRealCjk = 0
                for (ch in dxfText) {
                    val cp = ch.code
                    if (cp in 0x4E00..0x9FFF) { dxfCjkCount++; if (cp in DwgRawCjkScanner.COMMON_CJK_CHARS) dxfRealCjk++ }
                }
                val dxfCommonRatio = if (dxfCjkCount > 0) dxfRealCjk.toDouble() / dxfCjkCount else 0.0
                val dxfDensity = dxfStats.fourth.toDouble() / maxOf(dxfPages ?: 1, 1)
                dxfMojibake = (dxfCjkCount >= 200) && (dxfCommonRatio < 0.05) && (dxfDensity > 2500)
                val dxfQualityGood = (dxfCjkCount >= 50) && (dxfCommonRatio >= 0.30) && (dxfCommonRatio < 0.98)
                // v1.5.93 / v1.8.5: 纯英文/编号 DWG（dwg2dxf 把块炸开成大量实体→字数虚高但几乎无中文）
                // 不应采信其膨胀的矢量文字，改用 OLE 预览 OCR（见下方 OLE 块）。对齐桌面：
                // 桌面在 encoding_loss 时丢弃 LibreDWG 膨胀 items，改用 dwggrep + OLE 预览 OCR。
                // v1.8.5 修正：旧判定只用基本汉字(0x4E00-0x9FFF)计数，但 countTextKotlin 的"中文(fe)"
                // 还包含全角/中文标点/CJK扩展/Hangul。纯英文图纸经 dwg2dxf 后可能残留少量这类字符，
                // 导致 fe>0 但基本汉字<=5，旧判定把膨胀 DXF 误判为可用。改用 fe 与 fe 占比综合判定。
                val dxfFeRatioToTotal = if (dxfStats.fourth > 0) dxfStats.second.toDouble() / dxfStats.fourth else 0.0
                // v1.8.6: 门限收紧。桌面版对 fe=22/nc=5 的同类文件已判定"无法提取"，
                // 手机端残留 54 个 FarEast 字符（含全角/中文标点）即说明 DXF 不可靠。
                // 只要 fe 占比 <1% 即视为纯英文/编号图纸的膨胀 DXF。
                nonChineseDxf = dxfStats.fourth >= 500 && dxfFeRatioToTotal < 0.01
                // v1.5.92: DWG 结构化 DXF 是正式统计的唯一可信来源。只要 DWG→DXF 转换成功、
                // 解析到非空文字且不是乱码，就直接采用 DXF，不再因二进制 ASCII 扫描数量
                // 更大而回退到噪声。raw 扫描仅用于后续 CJK 恢复/兜底。
                // v1.5.93: 纯英文/编号 DWG 的 DXF 矢量文字是 dwg2dxf 块炸开的膨胀结果，
                // 不可采信（nonChineseDxf 为 true 时排除），改用 OLE 预览 OCR + 原始字节扫描。
                val dxfUsable = dxfStats.fourth > 0 && !dxfMojibake
        // v1.9.2: DwgDxfParser 复杂结构化在部分真机上漏抽文字，加 gc=1/3 简易抽取覆盖
        // v1.9.5: 结构化解析与简易流式抽取合并，取并集。部分真机上结构化解析会漏抽
        // TEXT/MTEXT 文字，而简易 gc=1/3 能补回；合并后避免全 0。
        val simpleText = extractDxfTextsSimple(dxfPath)
        if (simpleText.isNotBlank()) {
            val simpleStats = countTextKotlin(simpleText)
            // v1.9.8: 主路径用结构化 DXF 文字（与桌面 ezdxf _collect_dxf_texts 口径一致：
            // INSERT 展开 + 图层可见性过滤）。简易 gc=1/3 抽取仅作『结构化抽空』兜底，
            // 不与结构化合并（两者都直接读模型空间 TEXT/MTEXT，合并会让同一段文字被计两次）。
            // 仅当结构化抽空(0字)或明显漏抽(简易>2倍)时才回退到简易抽取，避免编号膨胀/重复计。
            if (dxfStats.fourth == 0 || simpleStats.fourth > dxfStats.fourth * 2) {
                dxfText = simpleText
                dxfStats = simpleStats
            }
            dxfPagesReason = (dxfPagesReason ?: "") + "·v1.9.6简易抽取"
            Log.d("WordCount", "DWG dxf 简易抽取 $dName: simpleChars=${simpleStats.fourth} nonChineseDxf=$nonChineseDxf structChars=${dxfStats.fourth}")
        }
                if (printedScope || dxfUsable) {
                    finalStats = dxfStats
                    finalText = dxfText
                }
                // v1.9.6: 不再对 nonChineseDxf 清零。纯英文/编号 DWG 的字数
                // 应保留（与桌面版一致），仅由简易抽取避免 INSERT 块展开膨胀。
                // OLE/OCR 路径仅在 DXF 无文字时兜底。
                Log.d("WordCount", "DWG dxf $dName: enc=${analysis.decodeMode} raw=$rawChars dxf=${dxfStats.fourth} cjk=$dxfCjkCount cr=${"%.3f".format(dxfCommonRatio)} den=${"%.0f".format(dxfDensity)} moji=$dxfMojibake pages=$dxfPages($dxfPagesReason)")
            }
        }
        // ── v1.5.29: 编码丢失检测 + GBK/UTF-16 原始字节 CJK 恢复 ──
        val hasValidFrames = (dxfPages != null) && (dxfPages > 0)
        val framesForDensity = if (hasValidFrames) dxfPages!! else 1
        var curTotal = finalStats.second + finalStats.third
        val density = curTotal.toDouble() / maxOf(framesForDensity, 1)
        var itemsCjk = 0; var realCjk = 0
        val textForCjkCheck = if (dxfText.isNotEmpty()) dxfText else rawText
        for (ch in textForCjkCheck) { val cp = ch.code; if (cp in 0x4E00..0x9FFF) { itemsCjk++; if (cp in DwgRawCjkScanner.COMMON_CJK_CHARS) realCjk++ } }
        val garbled = (itemsCjk >= 50) && (realCjk.toDouble() / maxOf(itemsCjk, 1) < 0.05)
        val curCjkRatio = if (curTotal > 0) itemsCjk.toDouble() / curTotal else 0.0
        val zeroCjkLoss = (curTotal >= 500) && (itemsCjk <= 5) && (curCjkRatio < 0.01)
        var sparse = false
        var cjkInRaw = 0
        try {
            val dwgRawBytes = file.readBytes()
            try {
                val decoded = String(dwgRawBytes, charset("GB18030"))
                for (c in decoded) { if (c.code in 0x4E00..0x9FFF) cjkInRaw++ }
            } catch (_: Exception) {}
            sparse = (cjkInRaw > 50000) && (realCjk.toDouble() / maxOf(framesForDensity, 1) < 50.0)
        } catch (_: Exception) {}
        val encodingLoss = zeroCjkLoss || garbled || sparse
        val densityTrigger = hasValidFrames && (density > 3000.0 && curTotal > framesForDensity * 1000)
        val needsRecovery = densityTrigger || encodingLoss || dxfMojibake
        var recoverySucceeded = false
        if (needsRecovery) {
            val recovered = DwgRawCjkScanner.scanRawDwg(file.absolutePath)
            Log.d("WordCount", "DWG CJK recovery $dName: method=${recovered.method} cjk=${recovered.cjkTotal} div=${"%.3f".format(recovered.cjkDiversity)} cr=${"%.3f".format(recovered.commonRatio)}")
            // v1.5.88: dxfMojibake 时仍需校验 recovery 质量（commonRatio>=0.10），
            // 否则 recovery 可能把随机字节巧合解码的伪中文直接替换进来。
            val recoveryQualityOk = recovered.commonRatio >= 0.10 && recovered.cjkDiversity < 0.6
            val mayReplace = (dxfMojibake && recoveryQualityOk) || DwgRawCjkScanner.shouldReplaceDxfResult(finalStats.fourth, itemsCjk, recovered)
            if (mayReplace && recovered.text.isNotEmpty()) {
                val recStats = countTextKotlin(recovered.text)
                val effectiveMaxRatio = if (dxfMojibake) 8.0 else DwgRawCjkScanner.MAX_REPLACE_RATIO
                val base = if (finalStats.fourth <= 50) recStats.fourth else finalStats.fourth
                val limit = (base * effectiveMaxRatio).toInt().coerceAtLeast(100)
                if (recStats.fourth <= limit) {
                    finalStats = recStats
                    finalText = recovered.text
                    dxfPagesReason = "${recovered.method}字节扫描恢复"
                    recoverySucceeded = true
                    curTotal = finalStats.second + finalStats.third
                    Log.d("WordCount", "DWG CJK recovery APPLIED $dName: now=${recStats.fourth} fe=${recStats.second}")
                } else {
                    Log.w("WordCount", "DWG CJK recovery REJECTED (oversize) $dName")
                }
            } else {
                Log.d("WordCount", "DWG CJK recovery SKIPPED (safety gate) $dName")
            }
        }
        // ── v1.5.93: 编码丢失 / 纯英文 DXF → OLE 预览 OCR 替换膨胀矢量文字 ──
        // 对齐桌面：桌面在 encoding_loss 丢弃 LibreDWG 膨胀 items，改用 dwggrep(原始字节)
        // + OLE 嵌入预览 OCR。手机无 dwggrep，故以 OLE 预览位图 OCR 作为可见文字来源
        // （含中文 fe 与编号 nc），缺失时回退原始字节 CJK 扫描。仅在 recovery 尚未成功时介入，
        // 避免覆盖已成功的栅格化/乱码中文恢复结果（如水雾等中文充足图纸）。
        var oleApplied = false
        // v1.9.9: 对齐桌面 extract_cad —— OLE 嵌入文字与 DXF 文字无条件合并（并集，不替换）。
        // v1.9.8 的 "mergedStats.fourth > finalStats.fourth" 守卫过严：当 DwgOleExtractor 未识别
        // office 嵌入（xlsx/docx/pptx Package 流）而返回 0 时，无论怎么抽取都不满足条件，导致
        // L01-31003-31035 大量 DWG 的标题栏文字丢失（桌面1201 vs 手机278）。现在改为：先 dxf 路径，
        // 再 DWG 二进制直接扫 CFB 兜底（dxf 转写丢失 OLE2FRAME 时）。
        try {
            val oleRes = DwgOleExtractor.extractOleText(dxfPath)
            if (oleRes.text.isNotBlank()) {
                finalText = if (finalText.isBlank()) oleRes.text else finalText + "\n" + oleRes.text
                finalStats = countTextKotlin(finalText)
                curTotal = finalStats.second + finalStats.third
                dxfPagesReason = (dxfPagesReason ?: "") + "·DXF-OLE"
                oleApplied = true
                Log.d("WordCount", "DWG OLE合并(dxf) $dName: oleChars=${countTextKotlin(oleRes.text).fourth} mergedChars=${finalStats.fourth}")
            }
        } catch (e: Throwable) {
            Log.w("WordCount", "DWG OLE(dxf)失败 $dName: ${e.message}")
        }
        // v1.9.9: dxf 转换失败或 dxf 内无 OLE 时，从 DWG 二进制直接扫 CFB 兜底
        if (!oleApplied) {
            try {
                val oleRes2 = DwgOleExtractor.extractOleTextFromDwg(file.absolutePath)
                if (oleRes2.text.isNotBlank()) {
                    finalText = if (finalText.isBlank()) oleRes2.text else finalText + "\n" + oleRes2.text
                    finalStats = countTextKotlin(finalText)
                    curTotal = finalStats.second + finalStats.third
                    dxfPagesReason = (dxfPagesReason ?: "") + "·DWG-OLE"
                    oleApplied = true
                    Log.d("WordCount", "DWG OLE合并(dwg二进制) $dName: oleChars=${countTextKotlin(oleRes2.text).fourth} mergedChars=${finalStats.fourth}")
                }
            } catch (e: Throwable) {
                Log.w("WordCount", "DWG OLE(dwg二进制)失败 $dName: ${e.message}")
            }
        }
        // OLE 未给出文字且 fe 仍极低时，回退原始字节 CJK 扫描补充
        if (!oleApplied && finalStats.second <= 5) {
            try {
                val rawScan = DwgRawCjkScanner.scanRawDwg(file.absolutePath)
                if (rawScan.cjkTotal >= 200 && rawScan.cjkDiversity < 0.6 && rawScan.commonRatio >= 0.10 && rawScan.text.isNotBlank()) {
                    val rs = countTextKotlin(rawScan.text)
                    finalStats = rs
                    finalText = rawScan.text
                    curTotal = rs.second + rs.third
                    dxfPagesReason = "${rawScan.method}原始字节扫描恢复"
                    recoverySucceeded = true
                    oleApplied = true
                    Log.d("WordCount", "DWG OLE缺失→原始字节扫描 $dName: cjk=${rawScan.cjkTotal}")
                }
            } catch (_: Throwable) {}
        }
        // ── v1.5.61 / v1.5.86: 终极兜底 ──
        // 仅当源 DWG 二进制中确有中文字符证据时才做原始字节扫描；
        // 否则对栅格化/英文 DWG 的随机字节扫描会产生虚假 CJK 膨胀。
        if (!recoverySucceeded && !oleApplied && finalStats.second <= 5 && cjkInRaw >= 50) {
            val rawScanner = DwgRawCjkScanner.scanRawDwg(file.absolutePath)
            if (rawScanner.cjkTotal >= 200 && rawScanner.cjkDiversity < 0.6 && rawScanner.commonRatio >= 0.10 && rawScanner.text.isNotEmpty()) {
                val rs = countTextKotlin(rawScanner.text)
                finalStats = rs
                finalText = rawScanner.text
                dxfPagesReason = "${rawScanner.method}原始字节扫描"
                recoverySucceeded = true
                curTotal = finalStats.second + finalStats.third
                Log.d("WordCount", "DWG raw scanner APPLIED $dName: method=${rawScanner.method} cjk=${rawScanner.cjkTotal} common=${"%.2f".format(rawScanner.commonRatio)} words=${rs.first}")
            } else {
                Log.d("WordCount", "DWG raw scanner SKIPPED/REJECTED $dName: cjk=${rawScanner.cjkTotal} common=${"%.2f".format(rawScanner.commonRatio)} div=${"%.2f".format(rawScanner.cjkDiversity)}")
            }
        }
        // ── v1.8.9 / v1.9.9: 仅在 dxf 转换成功（dxfSuccess=true）但扫描结果是二进制垃圾时归零。
        // v1.9.9 放宽：dxf 转换失败（dxfSuccess=false）时不再归零 —— 否则 L01-31003-31035 等大量 DWG
        // 在手机端会显示 0 字（即便 DWG 二进制 OLE 兜底也找不到 CFB），与桌面真值（1298/3971/2781 等）不符。
        // 改为：保留 scanDwgRaw 的最终字数（即便可能是少量噪声），避免 dxf 失败的图纸全 0；
        // dxf 成功但纯垃圾的场景仍归零（v1.8.9 L01 10059字案例）。
        if (dxfSuccess && !recoverySucceeded && !oleApplied && finalStats.first > 3 && finalStats.fourth > finalStats.first * 8) {
            // dxf 成功 + 最终 stats 字符数 ≫ 词数（典型垃圾特征：每个字节计成单字）。
            Log.d("WordCount", "DWG dxf成功但纯垃圾→归零-(无可靠来源) $dName: rawWords=${finalStats.first} chars=${finalStats.fourth} fe=${finalStats.second} nc=${finalStats.third}")
            finalStats = Quadruple(0, 0, 0, 0)
            finalText = ""
        }
        // v1.9.5: DXF 转换成功但完全抽不出文字时，归零字数但保留图框页数；
        // 需求改为"只清字数不清页数"，避免用户看到页数 1 与真实图纸差异过大。
        if (dxfSuccess && finalStats.fourth == 0 && finalStats.first <= 3 && dxfText.isBlank()) {
            Log.d("WordCount", "DWG DXF抽空→归零字数保留页数(无文字) $dName: pages=$dxfPages($dxfPagesReason)")
            finalStats = Quadruple(0, 0, 0, 0)
            finalText = ""
            // dxfPages 保留，让 UI 仍显示 LibreDWG 估算图框数
        }
        // ── 回退：栅格化/稀疏/字数极少时置 needsPdf ──
        val framesKnown = (dxfPages != null) && (dxfPages >= 1)
        val framesVal4 = if (framesKnown) dxfPages!! else 1
        val charsNow = finalStats.fourth
        // v1.5.92: 栅格化判定改为按「字符数/页 < 500」。原 finalWords4=fe+nc 是混合单位，
        // 英文/编号图纸（如 L01-A01D03）会因此误报 needsPdf；用 chars 更符合"字/页"语义。
        // 桌面在 pdf_fallback=False 检测模式下：凡无法直接提取中文的 DWG 一律标记 needsPdf，
        // 这里用多重判定覆盖栅格化/编码丢失/中文丢失三类情形。
        val negligibleCjk = itemsCjk <= 5
        val rasterizedTrigger = framesKnown
                && !printedScope
                && (finalStats.fourth < framesVal4 * 500)
        val encodedLostTrigger = !printedScope
                && negligibleCjk
                && (cjkInRaw > 50000)
                && !recoverySucceeded
        val cjkLostTrigger = framesKnown
                && !recoverySucceeded
                && (finalStats.second <= 5)
                && (finalStats.third >= 100)
                && (cjkInRaw >= 100)
        val needsPdf = rasterizedTrigger || encodedLostTrigger || cjkLostTrigger || (finalStats.fourth < 50)
        val rasterized4 = framesKnown && (finalStats.fourth < framesVal4 * 500)
        if (rasterized4) {
            Log.d("WordCount", "DWG rasterized $dName: words=$curTotal frames=$framesVal4 recovery=$recoverySucceeded")
        }
        // ── v1.6.1: 与桌面对齐 —— 读不出来的 DWG 显示"-"而非噪声字数 ──
        // 桌面对无法提取中文/无法统计的 DWG 显示"-"。手机若结构化/RAW/OLE 仅抽出
        // 少量非中文噪声（无真实中文、字数<=3 且 needsPdf），强制归零，
        // 让 UI 走 needsPdf && !hasStats 分支显示"-"，避免"3个字"这类误导。
        // v1.5.102 只覆盖 recovery/OLE 未成功的情况；本次覆盖 OLE 误拾少量编号噪声的场景。
        if (needsPdf && finalStats.second == 0 && finalStats.first <= 3) {
            Log.d("WordCount", "DWG 归零显示-(不可读) $dName: words=${finalStats.first} chars=${finalStats.fourth}")
            finalStats = Quadruple(0, 0, 0, 0)
            finalText = ""
        }
        val pages = dxfPages ?: estimatePages(finalStats.fourth)
        val cadParts = if (finalText.isNotBlank()) computeCadParts(finalText) else null
        return DwgProcessResult(
            words = finalStats.first,
            fe = finalStats.second,
            nc = finalStats.third,
            chars = finalStats.fourth,
            pages = pages,
            pagesReason = dxfPagesReason,
            needsPdf = needsPdf,
            diag = dxfDiag,
            cadParts = cadParts,
            finalText = finalText
        )
    }

    /** v1.8.8: 仅保留与 countTextKotlin 口径一致的 FarEast 字符（含 CJK/假名/韩文/全角/中文标点）。
     *  用于 nonChineseDxf 触发时丢弃 OLE 预览 OCR 误识别的西文/数字/编号噪声。 */
    /**
     * v1.9.0: DXF 完整性守卫。LibreDWG 转换成功时，完整 DXF 必定以 ENDSEC/EOF 结尾；
     * 若转换中途被杀/崩溃，会产生"几何实体已写出但 LAYOUT/文字未写、无 EOF"的半成品 DXF，
     * 这种文件通过 length>0 检查但解析会得到 0 字 + 虚高页数，与桌面不符。现要求必须以 EOF 结尾
     * 才视为可信，否则按转换失败处理（DXF 转换失败兜底归零显示"-"，与桌面对齐）。
     * 只读末尾 512 字节，开销可忽略。 */
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
