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
        // ── 兜底层：原始二进制扫描（保留为回退） ──
        val rawText = scanDwgRaw(file.absolutePath)
        var finalStats = countTextKotlin(rawText)
        var finalText = rawText
        val rawChars = finalStats.fourth

        // ── 主路径（v1.5.16）：dwg→dxf 结构化文字抽取 + 图框页数 ──
        var dxfPages: Int? = null
        var dxfPagesReason: String? = null
        var dxfMojibake = false
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
                // v1.5.88: DXF 结构化抽取是主路径；仅当 rawText（二进制ASCII扫描）明显是噪声
                // 时才优先采用 DXF，避免 scanDwgRaw 把元数据/坐标/随机字节当作有效文字虚增字数。
                val rawLooksLikeNoise = rawChars > 10000 && dxfStats.fourth > 0 && rawChars > dxfStats.fourth * 5
                if (printedScope || (!dxfMojibake && (dxfStats.fourth >= rawChars || dxfQualityGood || rawLooksLikeNoise))) {
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

        // ── v1.5.61 / v1.5.86: 终极兜底 ──
        // 仅当源 DWG 二进制中确有中文字符证据时才做原始字节扫描；
        // 否则对栅格化/英文 DWG 的随机字节扫描会产生虚假 CJK 膨胀。
        if (!recoverySucceeded && finalStats.second <= 5 && cjkInRaw >= 50) {
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
        val finalWords4 = finalStats.second + finalStats.third
        val charsNow = finalStats.fourth
        // v1.5.86: 对齐桌面「栅格化/编码丢失 → 必须用PDF统计」。
        // 桌面在 pdf_fallback=False 检测模式下：凡无法直接提取中文的 DWG 一律标记 needsPdf，
        // 不再把字节噪声当中文虚增字数。这里用多重判定覆盖所有栅格化/英文图纸情形：
        //   1) rasterizedTrigger：结构化抽取字数远低于 CAD 典型密度(<1000字/页)
        //   2) encodedLostTrigger：源含大量疑似CJK字节但结构化抽取几乎无中文、且恢复未取到可信中文
        //      （对应桌面 encoder_garbled，同样必须走PDF）
        //   3) cjkLostTrigger：中文严重丢失但有少量非中文残留
        // 注意：不再用 !recoverySucceeded 抑制 needsPdf——桌面对"恢复取到噪声也仍按栅格化判 needsPdf"，
        // 只要最终可统计字数远低于每页密度即判定为必须用PDF。
        val negligibleCjk = itemsCjk <= 5
        val rasterizedTrigger = framesKnown
                && !printedScope
                && (finalWords4 < framesVal4 * 1000)
        val encodedLostTrigger = !printedScope
                && negligibleCjk
                && (cjkInRaw > 50000)
                && !recoverySucceeded
        val cjkLostTrigger = framesKnown
                && !recoverySucceeded
                && (finalStats.second <= 5)
                && (finalStats.third >= 100)
                && (cjkInRaw >= 100)
        val needsPdf = rasterizedTrigger || encodedLostTrigger || cjkLostTrigger || (charsNow < 50)
        val rasterized4 = framesKnown && (finalWords4 < framesVal4 * 1000)
        if (rasterized4) {
            Log.d("WordCount", "DWG rasterized $dName: words=$finalWords4 frames=$framesVal4 recovery=$recoverySucceeded")
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
