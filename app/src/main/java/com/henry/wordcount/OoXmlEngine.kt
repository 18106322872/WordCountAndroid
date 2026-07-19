package com.henry.wordcount

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.math.max

/**
 * 纯 Kotlin 的 OOXML（docx / xlsx / pptx）文本抽取与页数统计层。
 *
 * v1.0.21 改进：
 *   - docx：额外提取 word/header*.xml 和 word/footer*.xml（页眉页脚文字）
 *   - xlsx：按行列视觉顺序提取（匹配"全选→复制→粘贴到 Word"的效果），
 *     单元格间用制表符分隔，行间用换行分隔
 *   - 所有文本统一交给 countTextKotlin 按 Word 口径统计字数
 */
object OoXmlEngine {

    data class OoxmlResult(
        val text: String,
        val pages: Int,
        val kind: String, // "docx" | "xlsx" | "pptx"
        val sheets: List<String> = emptyList()
    )

    fun extract(file: File): OoxmlResult? {
        val ext = file.extension.lowercase()
        if (ext !in setOf("docx", "xlsx", "pptx")) return null
        val zip = try { ZipFile(file) } catch (_: Throwable) { return null }
        return try {
            when (ext) {
                "docx" -> extractDocx(zip)
                "xlsx" -> extractXlsx(zip)
                "pptx" -> extractPptx(zip)
                else -> null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { zip.close() }
        }
    }

    // ───────────────────────── docx ─────────────────────────
    private fun extractDocx(zip: ZipFile): OoxmlResult {
        val sb = StringBuilder()
        val pageCounter = intArrayOf(0)

        // 1) 主文档 body（word/document.xml）
        val bodyXml = readEntry(zip, "word/document.xml") ?: ""
        appendDocxXmlText(bodyXml, sb) { pageCounter[0]++ }

        // 2) 页眉（word/header1.xml, header2.xml, ...）
        for (i in 1..10) {
            val hXml = readEntry(zip, "word/header$i.xml") ?: break
            appendDocxXmlText(hXml, sb)
        }

        // 3) 页脚（word/footer1.xml, footer2.xml, ...）
        for (i in 1..10) {
            val fXml = readEntry(zip, "word/footer$i.xml") ?: break
            appendDocxXmlText(fXml, sb)
        }

        val text = sb.toString()
        val est = max(1, (text.length + 1499) / 1500)
        val pages = if (pageCounter[0] > 0) pageCounter[0] + 1 else est
        return OoxmlResult(text, pages, "docx")
    }

    /** 从一段 OOXML XML 中抽取文本追加到 sb；可选 onPageBreak 回调 */
    private fun appendDocxXmlText(
        xml: String,
        sb: StringBuilder,
        onPageBreak: (() -> Unit)? = null
    ) {
        if (xml.isBlank()) return
        val re = """<w:t[^>]*>(.*?)</w:t>|<w:br[^>]*w:type="page"[^>]*/>|<w:tab[^>]*/>|<w:br[^>]*/>|</w:p>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        re.findAll(xml).forEach { m ->
            val v = m.value
            when {
                v.startsWith("<w:t") -> sb.append(decodeXml(m.groupValues[1]))
                v.contains("""w:type="page"""") -> { onPageBreak?.invoke(); sb.append('\n') }
                v.startsWith("<w:tab") -> sb.append('\t')
                v.startsWith("<w:br") -> sb.append('\n')
                else -> sb.append('\n') // </w:p>
            }
        }
    }

    // ───────────────────────── xlsx ─────────────────────────
    /**
     * xlsx 文本提取——模拟"全选 → 复制 → 粘贴到 Word"的效果：
     *   按工作表顺序，每个工作表内按行优先（从上到下、从左到右），
     *   同一行单元格间用 \t 分隔，行间用 \n 分隔。
     */
    private fun extractXlsx(zip: ZipFile): OoxmlResult {
        val shared = readSharedStrings(zip)

        // 工作表按文件名序号排序
        val sheetEntries = Collections.list(zip.entries())
            .filter { it.name.matches("""xl/worksheets/sheet\d+\.xml""".toRegex()) }
            .sortedBy { """\d+""".toRegex().find(it.name)?.value?.toInt() ?: 0 }

        val sheetNames = mutableListOf<String>()
        val sb = StringBuilder()

        sheetEntries.forEachIndexed { idx, entry ->
            sheetNames.add("工作表${idx + 1}")
            val xml = readEntry(zip, entry.name) ?: return@forEachIndexed
            sb.append("\n[工作表${idx + 1}]\n")

            // 按行提取：先找所有 <row> 元素，每行内按 ref 排序单元格
            val rowRe = """<row[^>]*r="(\d+)"[^>]*>(.*?)</row>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val rows = mutableListOf<Pair<Int, String>>()
            rowRe.findAll(xml).forEach { rm ->
                val rowNum = rm.groupValues[1].toIntOrNull() ?: 0
                rows.add(Pair(rowNum, rm.groupValues[2]))
            }
            rows.sortBy { it.first }

            for ((_, rowBody) in rows) {
                val line = StringBuilder()
                // 行内单元格按列号排序
                val cells = mutableListOf<Triple<Int, String, String>>() // (colNum, type, value)
                val cellRe = """<c[^>]*r="([A-Z]+)(\d+)"[^>]*>(.*?)</c>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val vRe = """<v>(.*?)</v>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val tRe = """<t[^>]*>(.*?)</t>""".toRegex(RegexOption.DOT_MATCHES_ALL)

                cellRe.findAll(rowBody).forEach { cm ->
                    val colStr = cm.groupValues[1]
                    val colNum = colNameToIndex(colStr)
                    val body = cm.groupValues[3]
                    val isInline = body.contains("""t="inlineStr"""")
                    val txt = if (isInline) {
                        tRe.find(body)?.groupValues?.get(1)?.let { decodeXml(it) } ?: ""
                    } else {
                        val idxStr = vRe.find(body)?.groupValues?.get(1)?.trim()
                        if (body.contains("""t="s""") && idxStr != null) {
                            idxStr.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        } else {
                            idxStr ?: "" // 数字/公式值原样输出
                        }
                    }
                    cells.add(Triple(colNum, "", txt))
                }

                // 按列号排序后拼接（模拟 Excel 阅读顺序）
                cells.sortBy { it.first }
                var first = true
                for ((_, _, txt) in cells) {
                    if (txt.isNotBlank()) {
                        if (!first) line.append('\t')
                        line.append(txt)
                        first = false
                    }
                }
                if (line.isNotEmpty()) {
                    sb.append(line).append('\n')
                }
            }
            sb.append('\n')
        }

        val text = sb.toString()
        val pages = max(1, sheetEntries.size)
        return OoxmlResult(text, pages, "xlsx", sheetNames)
    }

    /** Excel 列名 → 列索引（A=0, B=1, ..., Z=25, AA=26, ...） */
    private fun colNameToIndex(name: String): Int {
        var idx = 0
        for (c in name) {
            idx = idx * 26 + (c.uppercaseChar().code - 'A'.code + 1)
        }
        return idx - 1
    }

    // ───────────────────────── pptx ─────────────────────────
    private fun extractPptx(zip: ZipFile): OoxmlResult {
        val slideEntries = Collections.list(zip.entries())
            .filter { it.name.matches("""ppt/slides/slide\d+\.xml""".toRegex()) }
            .sortedBy { """\d+""".toRegex().find(it.name)?.value?.toInt() ?: 0 }
        val sb = StringBuilder()
        slideEntries.forEachIndexed { idx, entry ->
            val xml = readEntry(zip, entry.name) ?: return@forEachIndexed
            sb.append("\n[幻灯片${idx + 1}]\n")
            val tRe = """<a:t[^>]*>(.*?)</a:t>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            tRe.findAll(xml).forEach { sb.append(decodeXml(it.groupValues[1])).append(' ') }
            sb.append('\n')
        }
        val text = sb.toString()
        val pages = max(1, slideEntries.size)
        return OoxmlResult(text, pages, "pptx")
    }

    // ───────────────────────── 工具 ─────────────────────────
    private fun readSharedStrings(zip: ZipFile): List<String> {
        val xml = readEntry(zip, "xl/sharedStrings.xml") ?: return emptyList()
        val out = mutableListOf<String>()
        val siRe = """<si>(.*?)</si>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val tRe = """<t[^>]*>(.*?)</t>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        siRe.findAll(xml).forEach { siMatch ->
            val inner = siMatch.groupValues[1]
            val sb = StringBuilder()
            tRe.findAll(inner).forEach { sb.append(decodeXml(it.groupValues[1])) }
            out.add(sb.toString())
        }
        return out
    }

    private fun readEntry(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        zip.getInputStream(entry).use { `is` ->
            val bytes = `is`.readBytes()
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    private fun decodeXml(s: String): String {
        return s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("""&#x([0-9a-fA-F]+);""".toRegex()) { m -> m.groupValues[1].toInt(16).toChar().toString() }
            .replace("""&#(\d+);""".toRegex()) { m -> m.groupValues[1].toInt().toChar().toString() }
    }
}
