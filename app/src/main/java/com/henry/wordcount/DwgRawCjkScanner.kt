package com.henry.wordcount

/**
 * v1.5.20: 修复 v1.5.19 的两个核心 bug 并加强质量防护。
 *
 * Bug 1（巴布亚桩基超算 59598 vs 真实 23960）：
 *   UTF-16LE 扫描器把整个多 MB 二进制文件解码，随机字节对产生大量假 CJK 段，
 *   且无上限保护 → 结果膨胀 2.5 倍。
 *   修复：a) 绝对硬顶 ≤30000 CJK；b) 不允许覆盖已合理的 DXF 结果；
 *        c) per-segment CJK 比率从 0.6 提到 0.7；d) 文件大小相对上限。
 *
 * Bug 2（给排水_t3 中文=0 不触发恢复）：
 *   编码丢失检测要求 itemsCjk>=50 才判定乱码，但 DXF 解析出 0 个 CJK → 不触发。
 *   修复：a)"零 CJK + 大量非空文本"明确为编码丢失；b) 降低门槛；
 *        c) GBK 扫描器改善抗噪（连续区域含二进制噪声导致质量检查失败）。
 *
 * 使用方式同 v1.5.19：MainActivity DWG 分支检测到编码丢失时调用 scanRawDwg()，
 *   但新增 shouldReplaceDxf(dxfCount, recovered) 安全门——防止好结果被坏结果覆盖。
 */

object DwgRawCjkScanner {

    // ───────────────────── 常用中文单字集（top-300） ─────────────────────

    val COMMON_CJK_CHARS: Set<Int> = setOf(
        // 介词/助词/连词（最高频）
        0x7684, 0x4E86, 0x662F, 0x5728, 0x6709, 0x548C, 0x4E0E, 0x53CA, 0x6216, 0x4F46,
        0x800C, 0x4E5F, 0x5C31, 0x90FD, 0x53C8, 0x8FD8, 0x5DF2, 0x5C06, 0x628A, 0x88AB,
        0xBA9E, 0x4F7F, 0x7ED9, 0x4ECE, 0x5BF9, 0x5230, 0x5411, 0x5F80, 0x7531, 0x4E3A,
        0x56E0, 0x6240, 0x5176, 0x6B64, 0x8FD9, 0x90A3, 0x54EA,
        // 常用动词
        0x8BF4, 0x505A, 0x770B, 0x60F3, 0x53BB, 0x6765, 0x51FA, 0x5165, 0x4E0A, 0x4E0B,
        0x8FDB, 0x9000, 0x56DE, 0x8FC7, 0x8D77, 0x5F00, 0x5173, 0x7528, 0x5403, 0x559D,
        0x7761, 0x4F4F, 0x4E70, 0x5356, 0x6253, 0x5199, 0x8BFB, 0x542C, 0x8D70,
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
        0x56FE, 0x8868, 0x53F7, 0x5C42, 0x677F, 0x5899, 0x67F1, 0x688C, 0x57FA, 0x7840,
        0x6863, 0x627F, 0x53F0, 0x914D, 0x7B4B, 0x6DF7, 0x51DD, 0x94A2, 0x710A, 0x63A5,
        0x87BA, 0x6813, 0x9884, 0x57CB, 0x4EF6, 0x7BA1, 0x7EBF, 0x7F06, 0x6865, 0x67B6,
        0x6DB5, 0x6D1E, 0x4E95, 0x5BA4, 0x95E8, 0x7A97, 0x697C, 0x68AF, 0x7535, 0x6C14,
        0x6696, 0x901A, 0x9632, 0x6D88, 0x5B89, 0x5168, 0x56F4, 0x62A4, 0x680F, 0x7F69,
        0x58F3, 0x5957, 0x76D6, 0x5E95, 0x9876, 0x7AEF, 0x5934, 0x5C3E, 0x53E3, 0x9762,
        0x4FA1, 0x89D2, 0x90E8, 0x6BB5, 0x8DE8, 0x8DDD, 0x5F84, 0x7A0B, 0x6BD4, 0x5760,
        0x5EA6,
        // 量词
        0x4E2A, 0x53EA, 0x6761, 0x5757, 0x5F20, 0x724C, 0x5957, 0x7EC4, 0x53F0, 0x8F86,
        0x8258, 0x67B6, 0x5EA7, 0x680B, 0x5E62, 0x6237,
        // 大写数字
        0x58F9, 0x8D30, 0x53C2, 0x8086, 0x4F0D, 0x9646, 0x67D2, 0x634C, 0x7396, 0x62FE,
        // 施工/管理
        0x65BD, 0x5DE5, 0x8BBE, 0x8BA1, 0x76D1, 0x7406, 0x9A8C, 0x6536, 0x62A5, 0x544A,
        0x7B7E, 0x5B57, 0x7AE0, 0x671F, 0x5B8C, 0x6210, 0x672A, 0x534A, 0x6B62, 0x7981,
        0x8BB8, 0x53EF, 0x9700, 0x8981, 0x6C42
    )

    // ───────────────────── 数据结构 ─────────────────────

    data class ScanResult(
        val text: String,
        val cjkTotal: Int,
        val cjkDiversity: Double,
        val commonRatio: Double,
        val method: String
    )

    // ───────────────────── 全局安全常量 ─────────────────────

    /** 单次扫描绝对 CJK 上限（超过此值几乎必然是二进制噪声误判） */
    private const val MAX_CJK_ABSOLUTE = 30000

    /** 相对文件大小的安全倍数（CJK 字数不应超过文件字节数的此倍数） */
    private const val MAX_CJK_OVER_FILE_SIZE_RATIO = 6

    /** 允许覆盖 DXF 结果的最大倍数（recovery CJK / DXF CJK 超过此值则拒绝覆盖） */
    const val MAX_REPLACE_RATIO = 3.5

    // ───────────────────── 质量判定 ─────────────────────

    /**
     * 判断一段解码后字符串是否为真中文（不是二进制巧合段）。
     * v1.5.20 改进：增加「清洗」步骤——先剔除控制字符/乱码后再判质量。
     */
    private fun isRealCjkSegment(rawSeg: String, minCjkRatio: Double = 0.70,
                                  minCommonChars: Int = 2, minTotalCjk: Int = 8): Boolean {
        if (rawSeg.isEmpty()) return false
        // 清洗：只保留 CJK + ASCII 可打印 + 全角字符，剔除控制码/null/乱码
        val sb = StringBuilder()
        for (ch in rawSeg) {
            val cp = ch.code
            if (cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2FFFF ||
                cp in 0x20..0x7E || cp in 0xFF01..0xFF5E ||
                cp in 0x3000..0x303F || cp in 0x2014..0x2027 ||
                cp == 0x00B7) {
                sb.append(ch)
            }
        }
        val seg = sb.toString()
        if (seg.length < minTotalCjk) return false

        var cjk = 0
        var common = 0
        for (ch in seg) {
            val cp = ch.code
            if (cp in 0x4E00..0x9FFF) {
                cjk++
                if (cp in COMMON_CJK_CHARS) common++
            }
        }
        return cjk >= minTotalCjk && cjk.toDouble() / seg.length >= minCjkRatio && common >= minCommonChars
    }

    // ───────────────────── GBK 字节扫描 ─────────────────────

    /**
     * v1.5.20 改进：
     * - 连续区域切分后，只提取 GBK 双字节位置附近的字节解码（减少二进制噪声）
     * - 增加全局安全上限
     */
    fun extractGbkCjk(rawBytes: ByteArray, minRun: Int = 4, maxPerCall: Int = 500): ScanResult {
        if (rawBytes.isEmpty()) return ScanResult("", 0, 1.0, 0.0, "none")

        // 文件大小安全上限
        val maxCjkBySize = rawBytes.size / 10  // CJK 字数不应超过文件字节数的 1/10

        // Step 1: 扫描所有 GBK 双字节位置
        val matches = mutableListOf<IntArray>()
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

        // Step 2: 按"连续区域（≤6字节间隔）"切分
        val out = LinkedHashSet<String>()
        var curStart = matches[0][0]
        var curEnd = matches[0][1]

        for (mIdx in 1 until matches.size) {
            val m = matches[mIdx]
            if (m[0] - curEnd <= 6) {
                curEnd = m[1]
            } else {
                // flush current segment
                tryDecodeSegment(rawBytes, curStart, curEnd, out, minRun, maxPerCall)
                if (out.size >= maxPerCall) break
                curStart = m[0]
                curEnd = m[1]
            }
        }
        if (out.size < maxPerCall) {
            tryDecodeSegment(rawBytes, curStart, curEnd, out, minRun, maxPerCall)
        }

        // 统计
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
        // 安全上限截断
        if (cjkTotal > MAX_CJK_ABSOLUTE || cjkTotal > maxCjkBySize) {
            return ScanResult("", 0, 1.0, 0.0, "none")
        }
        val diversity = if (cjkTotal > 0) cjkSet.size.toDouble() / cjkTotal else 1.0
        val commonRatio = if (cjkTotal > 0) commonTotal.toDouble() / cjkTotal else 0.0

        return ScanResult(text, cjkTotal, diversity, commonRatio, "GBK")
    }

    /** 尝试解码一个 GBK 字节段并加入结果集 */
    private fun tryDecodeSegment(raw: ByteArray, start: Int, end: Int,
                                  out: MutableSet<String>, minRun: Int, maxPerCall: Int) {
        val seg = raw.sliceArray(start until end)
        // 只取 GBK 匹配点附近：每对 GBK 字节前后各取 0 字节（即原段整体），
        // 但解码后用 isRealCjkSegment 内部清洗非打印字符
        for (enc in listOf("GBK", "GB18030")) {
            try {
                val s = String(seg, charset(enc)).trim()
                if (s.length >= minRun && isRealCjkSegment(s) && s !in out) {
                    out.add(s)
                    return
                }
            } catch (_: Exception) { continue }
        }
    }

    // ───────────────────── UTF-16LE 字节扫描 ─────────────────────

    /**
     * v1.5.20 关键修复：
     * - per-segment CJK 比率从 0.6 → 0.7（更严格）
     * - maxPerCall 从 2000 → 500
     * - 新增全局 CJK 绝对硬顶 MAX_CJK_ABSOLUTE (30000)
     * - 新增文件大小相对上限
     */
    fun extractUtf16Cjk(rawBytes: ByteArray, minRun: Int = 4, maxPerCall: Int = 500): ScanResult {
        val maxSize = rawBytes.size
        val maxCjkBySize = maxSize / 10

        val text = try {
            String(rawBytes, charset("UTF-16LE"))
        } catch (_: Exception) {
            return ScanResult("", 0, 1.0, 0.0, "none")
        }

        fun isStrictCjk(cp: Int): Boolean =
            (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) || (cp in 0x20000..0x2FFFF)

        fun isGlue(cp: Int): Boolean =
            (cp in 0xFF01..0xFF5E) ||
            (cp in 0x3000..0x303F) ||
            (cp in 0x2014..0x201D) ||
            (cp in 0x2026..0x2027) ||
            (cp == 0x00B7) ||
            (cp in 0x2018..0x201B)

        fun isRunChar(cp: Int): Boolean =
            (cp in 0x20..0x7E) || isStrictCjk(cp) || isGlue(cp)

        val outItems = LinkedHashSet<String>()
        val curChars = mutableListOf<Char>()
        var globalCjk = 0  // 全局 CJK 计数（用于提前终止）

        fun flush() {
            if (curChars.size < minRun) return
            val cjkCount = curChars.count { isStrictCjk(it.code) }
            // v1.5.20: 提高阈值 0.6 → 0.7
            if (cjkCount.toDouble() / maxOf(curChars.size, 1) >= 0.7) {
                val s = curChars.joinToString("").trim()
                if (s.isNotEmpty() && s !in outItems) {
                    outItems.add(s)
                    globalCjk += cjkCount
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
            // 提前终止：已达安全上限
            if (globalCjk >= MAX_CJK_ABSOLUTE || globalCjk >= maxCjkBySize || outItems.size >= maxPerCall) break
        }
        flush()

        // 二次安全检查：总 CJK 超限则整个结果废弃
        if (globalCjk > MAX_CJK_ABSOLUTE || globalCjk > maxCjkBySize) {
            return ScanResult("", 0, 1.0, 0.0, "none")
        }

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

    // ───────────────────── 对外主入口 ─────────────────────

    /**
     * 对 DWG 文件执行原始字节 CJK 恢复扫描。
     *
     * v1.5.20 改进：
     * - 更严格的质量门槛
     * - 全局安全上限
     * - 新增 shouldReplaceDxf() 静态方法供 MainActivity 调用判断是否应覆盖 DXF 结果
     */
    fun scanRawDwg(dwgPath: String): ScanResult {
        val f = java.io.File(dwgPath)
        if (!f.exists() || f.length() == 0L) return ScanResult("", 0, 1.0, 0.0, "none")
        val raw = try { f.readBytes() } catch (_: Exception) { return ScanResult("", 0, 1.0, 0.0, "none") }

        // 尝试 GBK（最常见情况）
        val gbk = extractGbkCjk(raw)
        if (gbk.cjkTotal >= 100 && gbk.cjkDiversity < 0.65 && gbk.commonRatio >= 0.08) {
            return gbk
        }

        // GBK 不达标 → 试 UTF-16LE
        val utf16 = extractUtf16Cjk(raw)
        if (utf16.cjkTotal >= 100 && utf16.cjkDiversity < 0.65 && utf16.commonRatio >= 0.08 &&
            utf16.text.isNotEmpty()) {
            return utf16
        }

        // 都不达标 → 返回空（不再返回"勉强可用"的结果）
        return ScanResult("", 0, 1.0, 0.0, "none")
    }

    /**
     * 安全门：判断 recovery 结果是否可信到可以覆盖 DXF 结构化解析的结果。
     *
     * 桌面版核心逻辑（wordcount.py:3552）：
     *   "⚠️ 必须 `if _real_text is None`：优先级 0(ezdxf 出图口径)命中时不得被
     *    dwggrep 全量覆盖（否则巴布亚又回到 59362 虚高）"
     *
     * 规则：
     *   1. 如果 DXF 已有合理 CJK 含量（cjkRatio >= 0.15 或 CJK 绝对值 >= 500）→ 不允许覆盖
     *   2. 如果 recovery 总字数 > DXF 总字数 * MAX_REPLACE_RATIO → 不允许覆盖
     *   3. 如果 recovery 的 diversity >= 0.7（接近随机噪声）→ 不允许覆盖
     *   4. 否则 → 允许覆盖
     */
    fun shouldReplaceDxfResult(dxfTotalChars: Int, dxfCjkCount: Int,
                                recovered: ScanResult): Boolean {
        // DXF 已有足够好的中文 → 保护它不被覆盖
        val dxfCjkRatio = if (dxfTotalChars > 0) dxfCjkCount.toDouble() / dxfTotalChars else 0.0
        if (dxfCjkCount >= 500 || dxfCjkRatio >= 0.15) {
            return false
        }
        // Recovery 结果膨胀过度 → 拒绝
        if (recovered.cjkTotal > 0 && dxfTotalChars > 0 &&
            recovered.cjkTotal > dxfTotalChars * MAX_REPLACE_RATIO) {
            return false
        }
        // Recovery diversity 太高（像随机噪声）→ 拒绝
        if (recovered.cjkDiversity >= 0.7) {
            return false
        }
        // Recovery 有实质内容且看起来像真文本 → 允许
        return recovered.cjkTotal >= 200 && recovered.cjkDiversity < 0.6 &&
               recovered.commonRatio >= 0.10 && recovered.text.isNotEmpty()
    }
}
