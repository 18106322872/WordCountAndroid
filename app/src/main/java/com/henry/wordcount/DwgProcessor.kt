package com.henry.wordcount

import android.content.Context
import android.util.Log
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
            // v1.5.93: 任何意外异常都不得让 DWG 处理崩溃（否则压缩包内层 DWG 会被静默丢弃，
            // 导致 22→28 类文件数丢失）。一律回退到原始字节扫描基线，保证返回一个非空、可用的结果。
            Log.w("WordCount", "DWG process 异常兜底 $dName: ${e.javaClass.simpleName}: ${e.message}")
            val fb = try { scanDwgRaw(file.absolutePath) } catch (_: Throwable) { "" }
            val fs = try { countTextKotlin(fb) } catch (_: Throwable) { Quadruple(0, 0, 0, 0) }
            DwgProcessResult(fs.first, fs.second, fs.third, fs.fourth, 1, "异常兜底", false, "process异常: ${e.message}", null, fb)
        }
    }

    private suspend fun processInner(context: Context, file: File, dName: String): DwgProcessResult {
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
        if (dxfRes.path != null) {
            val dxfFile = File(dxfPath)
            if (dxfFile.exists() && dxfFile.length() > 0) {
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
                // v1.5.93: 纯英文/编号 DWG（dwg2dxf 把块炸开成大量实体→字数虚高但几乎无中文）
                // 不应采信其膨胀的矢量文字，改用 OLE 预览 OCR（见下方 OLE 块）。对齐桌面：
                // 桌面在 encoding_loss 时丢弃 LibreDWG 膨胀 items，改用 dwggrep + OLE 预览 OCR。
                val dxfCjkRatioToTotal = if (dxfStats.fourth > 0) dxfCjkCount.toDouble() / dxfStats.fourth else 0.0
                nonChineseDxf = dxfStats.fourth >= 500 && dxfCjkCount <= 5 && dxfCjkRatioToTotal < 0.01
                // v1.5.92: DWG 结构化 DXF 是正式统计的唯一可信来源。只要 DWG→DXF 转换成功、
                // 解析到非空文字且不是乱码，就直接采用 DXF，不再因二进制 ASCII 扫描数量
                // 更大而回退到噪声。raw 扫描仅用于后续 CJK 恢复/兜底。
                // v1.5.93: 纯英文/编号 DWG 的 DXF 矢量文字是 dwg2dxf 块炸开的膨胀结果，
                // 不可采信（nonChineseDxf 为 true 时排除），改用 OLE 预览 OCR + 原始字节扫描。
                val dxfUsable = dxfStats.fourth > 0 && !dxfMojibake && !nonChineseDxf
                if (printedScope || dxfUsable) {
                    finalStats = dxfStats
                    finalText = dxfText
                }
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
        if ((nonChineseDxf || encodingLoss) && !recoverySucceeded && !oleApplied) {
            try {
                val oleRes = DwgOleExtractor.extractOleText(dxfPath)
                if (oleRes.text.isNotBlank()) {
                    val oleStats = countTextKotlin(oleRes.text)
                    if (oleStats.fourth > 0) {
                        finalStats = oleStats
                        finalText = oleRes.text
                        curTotal = oleStats.second + oleStats.third
                        dxfPagesReason = "OLE预览OCR(对象${oleRes.objects}/位图${oleRes.bitmapsOcred})"
                        recoverySucceeded = true
                        oleApplied = true
                        Log.d("WordCount", "DWG OLE OCR 采用 $dName: fe=${oleStats.second} nc=${oleStats.third} chars=${oleStats.fourth}")
                    }
                }
            } catch (e: Throwable) {
                Log.w("WordCount", "DWG OLE OCR 失败 $dName: ${e.message}")
            }
            // OLE 未给出中文时，回退原始字节 CJK 扫描补充（仅当当前 fe 仍极低）
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

        // ── v1.5.101: 与桌面对齐 —— 读不出来的 DWG 显示"-"而非噪声字数 ──
        // 桌面对无法提取中文/无法统计的 DWG 显示"-"。手机若结构化/RAW 仅抽出少量
        // 噪声（无真实中文恢复、字符数<50 且 needsPdf），应归零，
        // 让 UI 走 needsPdf && !hasStats 分支显示"-"，避免"3个字"这类误导。
        // v1.5.100 条件过严（要求 nc=0），v1.5.101 放宽为 fe=0 即可覆盖英文/编号噪声。
        if (needsPdf && !recoverySucceeded && !oleApplied
            && finalStats.second == 0 && finalStats.fourth < 50) {
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
}
