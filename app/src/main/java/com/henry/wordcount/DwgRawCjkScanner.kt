package com.henry.wordcount

/**
 * v1.5.19: 移植桌面版 WordCount 的 DWG 原始字节 CJK 恢复扫描器。
 *
 * 背景：LibreDWG 的 dwg2dxf 在 Android 上对含中文的 DWG 文件常出现编码丢失——
 *   把 GBK/UTF-16 编码的中文字节误作 Latin-1 解码，输出 mojibake（乱码）或直接抽空。
 *   桌面版通过多层恢复解决此问题（DXF解析 → ezdxf → dwggrep → GBK字节扫描 → UTF-16LE扫描）。
 *   本文件移植其中最关键的 2 层纯字节扫描（GBK + UTF-16LE），无需外部工具，
 *   直接在原始 DWG 二进制数据上扫描真实中文字符串。
 *
 * 使用方式：在 MainActivity DWG 分支中，当 DXF 解析结果触发「编码丢失」启发式时，
 *   调用 scanRawDwgBytes(dwgPath) 获取恢复文本，质量检查通过后替换最终统计结果。
 */

object DwgRawCjkScanner {

    // ───────────────────── 常用中文单字集（top-300，真中文 vs GBK巧合字符判据） ─────────────────────

    /** 来源：现代汉语常用字表（GB2312 一级字 + 常用二级字 + 现代汉语高频字 + CAD工程常用字） */
    val COMMON_CJK_CHARS: Set<Int> = setOf(
        // 介词/助词/连词（最高频）
        0x7684, 0x4E86, 0x662F, 0x5728, 0x6709, 0x548C, 0x4E0E, 0x53CA, 0x6216, 0x4F46,
        0x800C, 0x4E5F, 0x5C31, 0x90FD, 0x53C8, 0x8FD8, 0x5DF2, 0x5C06, 0x628A, 0x88AB,
        0xBA9E, 0x4F7F, 0x7ED9, 0x4ECE, 0x5BF9, 0x5230, 0x5411, 0x5F80, 0x7531, 0x4E3A,
        0x56E0, 0x6240, 0x5176, 0x6B64, 0x8FD9, 0x90A3, 0x54EA,
        // 常用动词
        0x8BF4, 0x505A, 0x770B, 0x60F3, 0x53BB, 0x6765, 0x51FA, 0x5165, 0x4E0A, 0x4E0B,
        0x8FDB, 0x9000, 0x56DE, 0x8FC7, 0x8D77, 0x5F00, 0x5173, 0x7528, 0x5403, 0x559D,
        0x7761, 0x4F4F, 0x4E70, 0x5356, 0x7ED9, 0x6253, 0x5199, 0x8BFB, 0x542C, 0x8D70,
        0x8DD1, 0x98FE, 0x5750, 0x7AD9, 0x7B11, 0x54ED, 0x558A, 0x53EB, 0x95EE,
        // 常用名词
        0x4EBA, 0x6211, 0x4F60, 0x4ED6, 0x5979, 0x5B83, 0x4EEC, 0x5BB6, 0x56FD, 0x57CE,
        0x6751, 0x8DEF, 0x8F66, 0x6C34, 0x706B, 0x571F, 0x6728, 0x91D1, 0x77F3, 0x5C71,
        0x6CB3, 0x6D77, 0x5929, 0x5730, 0x65E5, 0x6708, 0x5E74, 0x65F6, 0x5206, 0x79D2,
        0x70B9, 0x4ECA, 0x660E, 0x6628, 0x524D, 0x540E, 0x5DE6, 0x53F3, 0x4E2D, 0x95F4,
        0x5185, 0x5916, 0x91CC, 0x65C1, 0x8FB9,
        // 数词
        0x4E00, 0x4E8C, 0x4E09, 0x56DB, 0x4E94, 0x516D, 0x4E03, 0x516B, 0x4E5D, 0x5341,
        0x767E, 0x5343, 0x4E07, 0x4EBF, 0x51E0, 0x591A, 0x5C11, 0x5927, 0x5C0F, 0x957F,
        0x77ED, 0x9AD8, 0x4F4E, 0x8FDC, 0x8FD1, 0x5BBD, 0x7A84, 0x539A, 0x8584, 0x91CD,
        0x8F7B, 0x5FEB, 0x6162, 0x65E9, 0x665A, 0x65B0, 0x8001, 0x597D, 0x574F,
        // 形容词
        0x7F8E, 0x4E11, 0x70ED, 0x51B7, 0x5E72, 0x6E7F, 0x4EAE, 0x6697, 0x6E05, 0x6D4A,
        0x767D, 0x9ED1, 0x7EA2, 0x9EC4, 0x84DD, 0x7EFF, 0x7D2B, 0x7070, 0x94F6, 0x7EF4,
        // 时间/方位
        0x4E1C, 0x897F, 0x5357, 0x5317, 0x6625, 0x590F, 0x79CB, 0x51AC, 0x5468, 0x53F7,
        // 古文/虚词
        0x7B49, 0x4E4B, 0x4EE5, 0x4E8E, 0x4E4E, 0x77E3, 0x54C9, 0x82E5, 0x5219,
        0x7136, 0x867D, 0x76D6, 0x592B, 0x51E1, 0x8BF8,
        // CAD/工程常用字
        0x56FE, 0x8868, 0x53F7, 0x5C42, 0x677F, 0x5899, 0x67F1, 0x6881, 0x57FA, 0x7840,
        0x6863, 0x627F, 0x53F0, 0x914D, 0x7B4B, 0x6DF7, 0x51DD, 0x94A2, 0x710A, 0x63A5,
        0x87BA, 0x6813, 0x9884, 0x57CB, 0x4EF6, 0x7BA1, 0x7EBF, 0x7F06, 0x6865, 0x67B6,
        0x6DB5, 0x6D1E, 0x4E95, 0x5BA4, 0x95E8, 0x7A97, 0x697C, 0x68AF, 0x7535, 0x6C14,
        0x6696, 0x901A, 0x9632, 0x6D88, 0x5B89, 0x5168, 0x56F4, 0x62A4, 0x680F, 0x7F69,
        0x58F3, 0x5957, 0x76D6, 0x5E95, 0x9876, 0x7AEF, 0x5934, 0x5C3E, 0x53E3, 0x9762,
        0x4FA1, 0x89D2, 0x90E8, 0x6BB5, 0x8DE8, 0x8DDD, 0x5F84, 0x7A0B, 0x6BD4, 0x5761,
        0x5EA6,
        // 量词
        0x4E2A, 0x53EA, 0x6761, 0x5757, 0x5F20, 0x7247, 0x5957, 0x7EC4, 0x53F0, 0x8F86,
        0x8258, 0x67B6, 0x5EA7, 0x680B, 0x5E62, 0x6237,
        // 大写数字
        0x58F9, 0x8D30, 0x53C1, 0x8086, 0x4F0D, 0x9646, 0x67D2, 0x634C, 0x7396, 0x62FE,
        // 施工/管理
        0x65BD, 0x5DE5, 0x8BBE, 0x8BA1, 0x76D1, 0x7406, 0x9A8C, 0x6536, 0x62A5, 0x544A,
        0x7B7E, 0x5B57, 0x7AE0, 0x671F, 0x5B8C, 0x6210, 0x672A, 0x534A, 0x6B62, 0x7981,
        0x8BB8, 0x53EF, 0x9700, 0x8981, 0x6C42
    )

    // ───────────────────── 数据结构 ─────────────────────

    data class ScanResult(
        val text: String,              ///< 去重后的 recovered 文本（换行分隔）
        val cjkTotal: Int,             ///< 严格 CJK (U+4E00-U+9FFF) 字符总数
        val cjkDiversity: Double,      ///< 独立CJK字/总CJK字（真中文<0.6，乱码≈1.0）
        val commonRatio: Double,       ///< 常用字/总CJK（真中文≥0.10，乱码≈0）
        val method: String             ///< "GBK" / "UTF-16LE" / "none"
    )

    // ───────────────────── 质量判定 ─────────────────────

    /** 判断一段 GBK 解码后的字符串是否为真中文（不是 GBK 范围巧合段）。
     *  严格判据（同时满足）：
     *    1. 段内 CJK 基础平面(0x4E00-0x9FFF) 占比 >= 80%  （v1.5.86 对齐桌面 _gbk_seg_is_real_cjk）
     *    2. 段内 CJK 字符数 >= 10
     *    3. 段内常用字命中数 >= 2
     */
    private fun isRealCjkSegment(seg: String, minCjkRatio: Double = 0.8,
                                  minCommonChars: Int = 2, minTotalCjk: Int = 10): Boolean {
        if (seg.isEmpty()) return false
        var cjk = 0
        var common = 0
        for (ch in seg) {
            val cp = ch.code
            if (cp in 0x4E00..0x9FFF) {
                cjk++
                if (cp in COMMON_CJK_CHARS) common++
            }
        }
        return cjk >= minTotalCjk && cjk / maxOf(seg.length, 1) >= minCjkRatio && common >= minCommonChars
    }

    // ───────────────────── GBK 字节扫描（端口 _extract_dwg_gbk_cjk） ─────────────────────

    /**
     * 从 DWG 原始二进制字节中扫描 GBK 编码的中文字符串。
     *
     * 算法：
     *   1. 用 GBK 双字节正则 [\x81-\xfe][\x40-\xfe] 匹配所有 GBK 字符位置
     *   2. 按"连续 GBK 区域（中间允许 ≤4 字节间隔）"切分
     *   3. 每段 GBK 解码后用 isRealCjkSegment 过滤（CJK占比>=80%, 常用字>=2, CJK>=10）
     *   4. 去重后返回
     *
     * @param rawBytes DWG 文件原始字节
     * @param minRun 最小段长（默认 4 字符）
     * @param maxPerCall 最大返回段数（防止内存膨胀，默认 100000）
     */
    fun extractGbkCjk(rawBytes: ByteArray, minRun: Int = 4, maxPerCall: Int = 100000): ScanResult {
        if (rawBytes.isEmpty()) return ScanResult("", 0, 1.0, 0.0, "none")

        // Step 1: 扫描所有 GBK 双字节位置
        val matches = mutableListOf<IntArray>() // each: [start, end]
        var i = 0
        while (i < rawBytes.size - 1) {
            val b1 = rawBytes[i].toInt() and 0xFF
            val b2 = rawBytes[i + 1].toInt() and 0xFF
            if (b1 in 0x81..0xFE && b2 in 0x40..0xFE) {
                matches.add(intArrayOf(i, i + 2))
            }
            i++
        }
        if (matches.isEmpty()) return ScanResult("", 0, 1.0, 0.0, "none")

        // Step 2: 按"连续区域（≤4字节间隔）"切分
        val out = LinkedHashSet<String>()
        var curStart = matches[0][0]
        var curEnd = matches[0][1]

        for (mIdx in 1 until matches.size) {
            val m = matches[mIdx]
            if (m[0] - curEnd <= 4) {
                curEnd = m[1]
            } else {
                // flush current segment with quality filter
                val seg = rawBytes.sliceArray(curStart until curEnd)
                try {
                    val s = String(seg, charset("GBK")).trim()
                    if (s.length >= minRun && isRealCjkSegment(s) && s !in out) {
                        out.add(s)
                    }
                } catch (_: Exception) {
                    try {
                        val s = String(seg, charset("GB18030")).trim()
                        if (s.length >= minRun && isRealCjkSegment(s) && s !in out) {
                            out.add(s)
                        }
                    } catch (_: Exception) { /* ignore */ }
                }
                if (out.size >= maxPerCall) break
                curStart = m[0]
                curEnd = m[1]
            }
        }
        // flush last segment
        if (out.size < maxPerCall) {
            val seg = rawBytes.sliceArray(curStart until curEnd)
            try {
                val s = String(seg, charset("GBK")).trim()
                if (s.length >= minRun && isRealCjkSegment(s) && s !in out) {
                    out.add(s)
                }
            } catch (_: Exception) {
                try {
                    val s = String(seg, charset("GB18030")).trim()
                    if (s.length >= minRun && isRealCjkSegment(s) && s !in out) {
                        out.add(s)
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }

        // 统计 diversity / common ratio
        val text = out.joinToString("\n")
        var cjkTotal = 0
        val cjkSet = mutableSetOf<Int>()
        var commonTotal = 0
        for (ch in text) {
            val cp = ch.code
            if (cp in 0x4E00..0x9FFF) {
                cjkTotal++
                cjkSet.add(cp)
                if (cp in COMMON_CJK_CHARS) commonTotal++
            }
        }
        val diversity = if (cjkTotal > 0) cjkSet.size.toDouble() / cjkTotal else 1.0
        val commonRatio = if (cjkTotal > 0) commonTotal.toDouble() / cjkTotal else 0.0

        return ScanResult(text, cjkTotal, diversity, commonRatio, "GBK")
    }

    // ───────────────────── UTF-16LE 字节扫描（端口 _extract_dwg_utf16_cjk） ─────────────────────

    /**
     * 从 DWG 原始字节中扫描 UTF-16LE 编码的 CJK 字符串。
     *
     * 适用场景：巴布亚桩基等以 UTF-16LE 存储中文的 DWG，
     * LibreDWG→DXF 把它按 Latin-1误解码出 mojibake，但原始字节里 UTF-16LE 仍是干净真中文。
     *
     * 过滤规则：
     *   - 仅严格 CJK（基本平面+扩展A+扩展B）算中文，韩文/假名不计入
     *   - 全角 ASCII/CJK 标点等"胶水字符"允许连接但不计入中文占比
     *   - 段内严格 CJK 占比 >= 60% 才保留
     *   - 段长 >= minRun
     */
    fun extractUtf16Cjk(rawBytes: ByteArray, minRun: Int = 4, maxPerCall: Int = 4000): ScanResult {
        val text = try {
            String(rawBytes, charset("UTF-16LE"))
        } catch (_: Exception) {
            return ScanResult("", 0, 1.0, 0.0, "none")
        }

        fun isStrictCjk(cp: Int): Boolean =
            (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) || (cp in 0x20000..0x2FFFF)

        fun isGlue(cp: Int): Boolean =
            (cp in 0xFF01..0xFF5E) ||       // 全角 ASCII
            (cp in 0x3000..0x303F) ||       // 全角空格 + CJK 标点
            (cp in 0x2014..0x201D) ||       // — – … " "
            (cp in 0x2026..0x2027) ||       // … ‧
            (cp == 0x00B7) ||               // ·
            (cp in 0x2018..0x201B)          // ' ' ' '

        fun isRunChar(cp: Int): Boolean =
            (cp in 0x20..0x7E) || isStrictCjk(cp) || isGlue(cp)

        val outItems = LinkedHashSet<String>()
        val curChars = mutableListOf<Char>()

        fun flush() {
            if (curChars.size < minRun) return
            val cjkCount = curChars.count { isStrictCjk(it.code) }
            if (cjkCount.toDouble() / maxOf(curChars.size, 1) >= 0.6) {
                val s = curChars.joinToString("").trim()
                if (s.isNotEmpty() && s !in outItems) {
                    outItems.add(s)
                }
            }
        }

        for (ch in text) {
            val cp = ch.code
            if (isRunChar(cp)) {
                curChars.add(ch)
            } else {
                flush()
                curChars.clear()
            }
            if (outItems.size >= maxPerCall) break
        }
        flush()

        val joined = outItems.joinToString("\n")
        var cjkTotal = 0
        val cjkSet = mutableSetOf<Int>()
        var commonTotal = 0
        for (c in joined) {
            val cp = c.code
            if (isStrictCjk(cp)) {
                cjkTotal++
                cjkSet.add(cp)
                if (cp in COMMON_CJK_CHARS) commonTotal++
            }
        }
        val diversity = if (cjkTotal > 0) cjkSet.size.toDouble() / cjkTotal else 1.0
        val commonRatio = if (cjkTotal > 0) commonTotal.toDouble() / cjkTotal else 0.0

        return ScanResult(joined, cjkTotal, diversity, commonRatio, "UTF-16LE")
    }

    // ───────────────────── 对外主入口：自动选择最佳扫描方法 ─────────────────────

    /**
     * 对一个 DWG 文件执行原始字节 CJK 恢复扫描。
     *
     * 策略：先尝试 GBK（绝大多数中文 DWG），若 GBK 质量不达标再试 UTF-16LE。
     * 返回最佳 ScanResult；若两种都无有效结果则返回空 "none" 结果。
     */
    fun scanRawDwg(dwgPath: String): ScanResult {
        val f = java.io.File(dwgPath)
        if (!f.exists() || f.length() == 0L) return ScanResult("", 0, 1.0, 0.0, "none")
        val raw = try { f.readBytes() } catch (_: Exception) { return ScanResult("", 0, 1.0, 0.0, "none") }

        // 尝试 GBK（最常见情况）
        val gbk = extractGbkCjk(raw)
        if (gbk.cjkTotal >= 200 && gbk.cjkDiversity < 0.6 && gbk.commonRatio >= 0.10) {
            return gbk
        }

        // GBK 不达标 → 试 UTF-16LE
        val utf16 = extractUtf16Cjk(raw)
        // v1.5.62: 对齐桌面版 _extract_dwg_utf16_cjk，只要求 cjk_total 够大且 diversity
        // 不像乱码即可；不再卡 commonRatio（水雾类 CAD 图纸专业术语多，常用字占比
        // 可能低于 0.10 但仍是真中文）。
        if (utf16.cjkTotal >= 200 && utf16.cjkDiversity < 0.6 && utf16.text.isNotEmpty()) {
            return utf16
        }

        // 都不达标 → 返回较好的那个（即使质量勉强），让调用方决定是否使用
        return if (gbk.cjkTotal >= utf16.cjkTotal) gbk else utf16
    }

    // ───────────────────── v1.5.21: 安全门（防止字节扫描器覆盖好结果） ─────────────────────

    /** 允许覆盖 DXF 结果的最大倍数（recovery CJK / DXF CJK 超过此值则拒绝） */
    val MAX_REPLACE_RATIO = 3.5

    /**
     * 安全门：判断 recovery 结果是否可信到可以覆盖 DXF 结构化解析的结果。
     * 桌面版核心逻辑（wordcount.py:3552）：
     *   "⚠️ 必须 if _real_text is None：优先级 0(ezdxf 出图口径)命中时不得被
     *    dwggrep 全量覆盖（否则巴布亚又回到 59362 虚高）"
     */
    fun shouldReplaceDxfResult(dxfTotalChars: Int, dxfCjkCount: Int,
                                recovered: ScanResult): Boolean {
        val dxfCjkRatio = if (dxfTotalChars > 0) dxfCjkCount.toDouble() / dxfTotalChars else 0.0
        // DXF 已有足够好的中文 → 保护它不被覆盖
        if (dxfCjkCount >= 500 || dxfCjkRatio >= 0.15) return false
        // v1.5.39: DXF 抽到的中文为 0（基数=0）时，跳过「膨胀过度」比较——
        //   旧逻辑 recovered.cjkTotal > 0 * 3.5 恒为真，会把任何恢复都误判为
        //   膨胀过度而拒绝；此时没有 DXF 结果可保护，只要恢复本身质量可信即可。
        if (dxfTotalChars > 0 && recovered.cjkTotal > 0 &&
            recovered.cjkTotal.toDouble() > dxfTotalChars * MAX_REPLACE_RATIO) return false
        // Recovery diversity 太高（像随机噪声）→ 拒绝
        if (recovered.cjkDiversity >= 0.7) return false
        // Recovery 有实质内容且看起来像真文本 → 允许
        return recovered.cjkTotal >= 200 && recovered.cjkDiversity < 0.6 &&
               recovered.commonRatio >= 0.10 && recovered.text.isNotEmpty()
    }
}
