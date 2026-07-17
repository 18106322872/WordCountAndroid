package com.henry.wordcount

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * DWG 文件文字提取（轻量方案，无外部依赖）。
 *
 * DWG 是 AutoCAD 专有二进制格式，安卓上无免费可靠的解析库：
 *   - Aspose.CAD：商业授权（~$2000+）
 *   - ODA Teigha：需原生 .dll/.so，安卓无法使用
 *   - ezdxf / python-dxf：只支持 DXF，不支持 DWG
 *
 * 本实现通过扫描二进制文件中的可读字符串来提取文字，
 * 能捕获大部分 TEXT/MTEXT/ATTDEF 实体文本（DWG R2000+ 文字以 UTF-16LE 编码存储）。
 * 精度不如真正的 CAD 解析库，但对「字数统计」场景基本够用。
 */
object DwgEngine {

    fun extractText(file: File): String {
        val bytes = file.readBytes()
        val results = LinkedHashSet<String>() // 去重保序

        // 1. 扫描 UTF-16LE 字符串（DWG R2000+ 文字实体主要编码）
        extractStrings(bytes, StandardCharsets.UTF_16LE, results)

        // 2. 扫描 UTF-8 字符串（部分元数据/旧版文字可能用 UTF-8）
        extractStrings(bytes, StandardCharsets.UTF_8, results)

        // 3. 过滤：去掉太短、纯数字、明显非文字的内容
        val filtered = results.filter { s ->
            s.length >= 2 &&
            !s.matches(Regex("^\\d+$")) &&
            !s.matches(Regex("^[\\x00-\\x1f\\x7f]+$")) &&
            s.trim().length >= 2
        }

        return filtered.joinToString("\n")
    }

    private fun extractStrings(bytes: ByteArray, cs: Charset, output: MutableSet<String>) {
        var i = 0
        while (i < bytes.size - 3) {
            // 尝试从当前位置解码字符串
            var len = 0
            while (i + len * cs.newEncoder().maxBytesPerChar() <= bytes.size && len < 500) {
                // 检查是否是有效的字符序列
                val end = minOf(i + (len + 1) * 2, bytes.size)
                if (end <= i + 1) break
                len++
                // 尝试解码当前长度的片段
                try {
                    val candidate = String(bytes, i, (len) * (if (cs == StandardCharsets.UTF_16LE) 2 else 1), cs)
                    // 检查是否有足够的可打印字符
                    val printableCount = candidate.count { it.isLetterOrDigit() || it.isWhitespace() ||
                        "，。！？、；：""''（）【】《》—…·–,.!?;:\"'()-".contains(it) }
                    if (candidate.length >= 2 && printableCount >= candidate.length * 0.6 &&
                        candidate.any { it.isLetterOrDigit() || "，。！？、；：""''（）【】《》—…·–".contains(it) }) {
                        if (len >= 2) output.add(candidate.trim())
                    }
                } catch (_: Exception) {}
            }
            i++
        }

        // 更高效的扫描方式：直接查找连续的 CJK/字母片段
        scanForTextRuns(bytes, cs, output)
    }

    private fun scanForTextRuns(bytes: ByteArray, cs: Charset, output: MutableSet<String>) {
        val unitSize = if (cs == StandardCharsets.UTF_16LE) 2 else 1
        var start = -1
        val sb = StringBuilder()

        for (pos in bytes.indices step unitSize) {
            if (pos + unitSize > bytes.size) break
            val ch = try {
                String(bytes, pos, unitSize, cs)[0]
            } catch (_: Exception) {
                continue
            }

            val isTextChar = when {
                ch.isLetterOrDigit() -> true
                ch.isWhitespace() -> true
                "，。！？、；：""''（）【】《》—…·–,.!?;:\"'-_@#%/+=<>{}[]|\\`~\$€£¥°℃%×÷±√∑∏πΩαβγδθλμφψω∈∞≈≠≤≥±²³¹⁰⁺⁻⁽⁾ⁿ‰§¶†‡•◦①②③④⑤⑥⑦⑧⑨⑩ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫⒶⒷⒸⒹⒺⒻⒼⒽⒾⒿⓀⓁⓂⓃⓄⓅⓆⓇⓈⓉⓊⓋⓌⓍⓎⓏ".contains(ch) -> true
                else -> false
            }

            if (isTextChar) {
                if (start == -1) start = pos
                sb.append(ch)
            } else {
                if (sb.length >= 2) {
                    val str = sb.toString().trim()
                    if (str.length >= 2 && str.any { it.isLetterOrDigit() || "，。！？、；：""''（）【】《》—…·–".contains(it) }) {
                        output.add(str)
                    }
                }
                sb.clear()
                start = -1
            }
        }
        // 处理末尾残留
        if (sb.length >= 2) {
            val str = sb.toString().trim()
            if (str.length >= 2) output.add(str)
        }
    }
}
