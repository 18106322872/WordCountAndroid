package com.henry.wordcount

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.math.max

/**
 * 纯 Kotlin 的 OOXML（docx / xlsx / pptx）文本抽取与页数统计层。
 *
 * 设计原则（避免此前 CI 编译失败）：
 *  - 仅用标准 JDK：java.util.zip.ZipFile + 正则解析 XML，不引入任何第三方库。
 *  - 不碰 SAX / XMLReader（Android 上 setFeature 易抛异常），直接对 XML 字符串做正则抽取。
 *  - 抽出的纯文本交给 MainActivity 的 countTextKotlin 复用同一套「Word 口径」字数统计。
 *
 * 页数口径（贴近「用电脑 Office 打开看到的页数」）：
 *  - docx：显式分页符（<w:br w:type="page"/> 与 lastRenderedPageBreak）数量 + 1；无分页符时按字符量估算。
 *  - xlsx：工作表（sheet）数量作为页数代理。
 *  - pptx：幻灯片（slide）数量即为页数。
 */
object OoXmlEngine {

    data class OoxmlResult(
        val text: String,
        val pages: Int,
        val kind: String, // "docx" | "xlsx" | "pptx"
        val sheets: List<String> = emptyList()
    )

    /** 返回 null 表示不是可识别的 OOXML（调用方降级）。 */
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
        val xml = readEntry(zip, "word/document.xml") ?: ""
        val sb = StringBuilder()
        var pageBreaks = 0
        // 顺序扫描：文本 / 显式分页符 / tab / 普通换行 / 段落结束
        val re = """<w:t[^>]*>(.*?)</w:t>|<w:br[^>]*w:type="page"[^>]*/>|<w:tab[^>]*/>|<w:br[^>]*/>|</w:p>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        re.findAll(xml).forEach { m ->
            val v = m.value
            when {
                v.startsWith("<w:t") -> sb.append(decodeXml(m.groupValues[1]))
                v.contains("""w:type="page"""") -> { pageBreaks++; sb.append('\n') }
                v.startsWith("<w:tab") -> sb.append('\t')
                v.startsWith("<w:br") -> sb.append('\n')
                else -> sb.append('\n') // </w:p>
            }
        }
        val text = sb.toString()
        // 估算：每 ~1500 字符一页（无显式分页符时兜底）
        val est = max(1, (text.length + 1499) / 1500)
        val pages = if (pageBreaks > 0) pageBreaks + 1 else est
        return OoxmlResult(text, pages, "docx")
    }

    // ───────────────────────── xlsx ─────────────────────────
    private fun extractXlsx(zip: ZipFile): OoxmlResult {
        // 1) 共享字符串表
        val shared = readSharedStrings(zip)
        // 2) 各工作表（按文件名序号排序，保证顺序稳定）
        val sheetEntries = Collections.list(zip.entries())
            .filter { it.name.matches("""xl/worksheets/sheet\d+\.xml""".toRegex()) }
            .sortedBy { """\d+""".toRegex().find(it.name)?.value?.toInt() ?: 0 }
        val sheetNames = mutableListOf<String>()
        val sb = StringBuilder()
        sheetEntries.forEachIndexed { idx, entry ->
            sheetNames.add("工作表${idx + 1}")
            val xml = readEntry(zip, entry.name) ?: return@forEachIndexed
            sb.append("\n[工作表${idx + 1}]\n")
            // 共享字符串单元格：<c r=".." t="s"><v>idx</v></c>
            // 内联字符串：<c ... t="inlineStr"><is><t>..</t></is></c>
            val cellRe = """<c[^>]*>(.*?)</c>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val vRe = """<v>(.*?)</v>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val tRe = """<t[^>]*>(.*?)</t>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            cellRe.findAll(xml).forEach { cm ->
                val body = cm.groupValues[1]
                val isInline = body.contains("""t="inlineStr"""")
                val txt = if (isInline) {
                    tRe.find(body)?.groupValues?.get(1)?.let { decodeXml(it) } ?: ""
                } else {
                    val idxStr = vRe.find(body)?.groupValues?.get(1)?.trim()
                    if (body.contains("""t="s""") && idxStr != null) {
                        idxStr.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    } else {
                        idxStr ?: "" // 数字/公式值原样
                    }
                }
                if (txt.isNotBlank()) sb.append(txt).append(' ')
            }
            sb.append('\n')
        }
        val text = sb.toString()
        // 页数代理 = 工作表数量（Excel 无明确"页"概念，以工作表计）
        val pages = max(1, sheetEntries.size)
        return OoxmlResult(text, pages, "xlsx", sheetNames)
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
        val pages = max(1, slideEntries.size) // 幻灯片数即页数
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
            // OOXML 一律 UTF-8
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
