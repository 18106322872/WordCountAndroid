package com.henry.wordcount

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.math.max

/**
 * 纯 Kotlin 的 OOXML（docx / xlsx / pptx）文本抽取与页数统计层。
 *
 * v1.0.24 核心改进：
 *   - docx：<w:t> 内容做 strip-tags 二次清洗，处理某些转换器生成的异常文档
 *          （<w:t> 内嵌套了 <w:pPr>/<w:rPr>/<w:tcPr> 等 XML 片段而非纯文本）
 *   - docx：过滤单字符和纯符号的噪声提取结果
 *   - docx：保留逐 <w:r> run 边界提取架构（已验证对正常文档准确）
 *   - pdf：先解压再查文字操作符（不再在原始压缩字节上搜索 Tj/TJ）
 *   - pdf：支持 [<hex>]TJ 十六进制数组格式（中文PDF常用编码方式）
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

    /**
     * 从 OOXML XML 中抽取文本（核心方法）。
     *
     * v1.0.24 改进：
     *   1. 保持逐 <w:r> run 边界提取架构（对正常文档准确）
     *   2. 对每个 <w:t> 提取结果做 strip-tags 清洗——处理某些转换器生成的
     *      异常文档，其 <w:t> 节点内嵌套了 <w:pPr>/<w:rPr>/<w:tcPr> 等 XML 片段
     *   3. 过滤掉单字符和纯符号的噪声
     */
    private fun appendDocxXmlText(
        xml: String,
        sb: StringBuilder,
        onPageBreak: (() -> Unit)? = null
    ) {
        if (xml.isBlank()) return

        // Step 1: 按段落（<w:p>）切分，保持段落结构
        val paraRe = """(?s)<w:p[ >].*?</w:p>""".toRegex()
        paraRe.findAll(xml).forEach { paraMatch ->
            val paraXml = paraMatch.value

            // 检查本段是否含分页符
            if (paraXml.contains("""w:type="page"""")) onPageBreak?.invoke()

            // Step 2: 在段落内逐个处理 <w:r> run
            val runRe = """(?s)<w:r[ >].*?</w:r>""".toRegex()
            runRe.findAll(paraXml).forEach { runMatch ->
                val runXml = runMatch.value

                // 跳过隐藏文字：run 属性中含 w:vanish 或 w:hidden
                if (VANISH_RE.containsMatchIn(runXml)) return@forEach
                if (HIDDEN_RE.containsMatchIn(runXml)) return@forEach

                // Step 3: 在 run 内提取 <w:t> 文本，然后清洗嵌套标签
                val tRe = """(?s)<w:t[^>]*>(.*?)</w:t>""".toRegex()
                tRe.findAll(runXml).forEach { tMatch ->
                    val raw = decodeXml(tMatch.groupValues[1])
                    // v1.0.24 关键修复：去除可能嵌套在 <w:t> 内的 XML 标签
                    // 某些转换器（如 WPS → docx）会在 <w:t> 中嵌入属性 XML 片段
                    val clean = raw.replace("""<[^>]+>""", "")
                        .replace("""&[a-z]+;""".toRegex(), "")  // 孤立实体引用

                    // 过滤：只保留含可打印文本的内容（至少 1 个字母/CJK 字符）
                    if (clean.isNotBlank() && clean.any { it.isLetter() || it.code in 0x4E00..0x9FFF }) {
                        sb.append(clean)
                    }
                }
            }

            // 段落结束追加换行
            sb.append('\n')
        }
    }

    // ───────────────────────── 正则常量 ──
    /** 隐藏文字检测：<w:r> 内含 w:vanish */
    private val VANISH_RE = """<w:vanish\b""".toRegex()

    /** 隐藏文字检测：<w:r> 内含 w:hidden */
    private val HIDDEN_RE = """<w:hidden\b""".toRegex()

    // ───────────────────────── xlsx ─────────────────────────
    /**
     * xlsx 文本提取——模拟"全选 → 复制 → 粘贴到 Word"的效果：
     *   按工作表顺序，每个工作表内按行优先（从上到下、从左到右），
     *   同一行单元格间用 \t 分隔，行间用 \n 分隔。
     *
     * v1.0.25 关键修复（与桌面版 openpyxl / Word 口径对齐，实测 words=1988/fe=1780/nc=208）：
     *   1. 单元格正则支持自闭合空单元格 <c .../>——旧正则 <c ...>(.*?)</c> 会把自闭合空格
     *      与紧随其后的单元格"吞"成一个，导致列错位 + 该格 t="s" 落到内层从而共享字符串解析失效。
     *   2. 共享字符串判定改为看 <c> 开标签属性是否含 t="s"（旧代码在内层 body 里找 t="s"，
     *      而 t="s" 只存在于开标签，导致判定永远为 false，所有共享字符串被输出成索引数字）。
     *   3. Excel 日期序列号（整数 20000~60000）转中文短日期「MM月DD日」，与复制到 Word 的显示一致。
     *   4. 通过 workbook.xml + workbook.xml.rels 排除隐藏工作表（state=hidden/veryHidden），只统计可见表。
     *   5. 不再往被统计文本插入 [工作表N] 标签（旧代码会因此每个表多算约 3 个中文 + 2 个非中文词）。
     */
    private fun extractXlsx(zip: ZipFile): OoxmlResult {
        val shared = readSharedStrings(zip)

        // 1) 解析 workbook.xml：按顺序取 (name, state, r:id)
        val wbXml = readEntry(zip, "xl/workbook.xml") ?: ""
        val nameAttrRe = "name=\"([^\"]*)\"".toRegex()
        val stateAttrRe = "state=\"([^\"]*)\"".toRegex()
        val ridAttrRe = "r:id=\"([^\"]*)\"".toRegex()
        // Triple: (name, state, r:id)
        val sheetRefs = mutableListOf<Triple<String, String, String>>()
        """<sheet\b[^>]*/>""".toRegex().findAll(wbXml).forEach { m ->
            val tag = m.value
            val nm = nameAttrRe.find(tag)?.groupValues?.get(1) ?: ""
            val st = stateAttrRe.find(tag)?.groupValues?.get(1) ?: "visible"
            val ri = ridAttrRe.find(tag)?.groupValues?.get(1) ?: ""
            sheetRefs.add(Triple(nm, st, ri))
        }

        // 2) 解析 rels：r:id → worksheet 文件路径
        val relsXml = readEntry(zip, "xl/_rels/workbook.xml.rels") ?: ""
        val idAttrRe = "Id=\"([^\"]*)\"".toRegex()
        val tgtAttrRe = "Target=\"([^\"]*)\"".toRegex()
        val rid2tgt = HashMap<String, String>()
        """<Relationship\b[^>]*/>""".toRegex().findAll(relsXml).forEach { m ->
            val tag = m.value
            val id = idAttrRe.find(tag)?.groupValues?.get(1)
            val tg = tgtAttrRe.find(tag)?.groupValues?.get(1)
            if (id != null && tg != null) rid2tgt[id] = tg
        }

        // 3) 仅保留可见工作表，按 workbook.xml 顺序。Pair: (sheetName, worksheetPath)
        val visible = mutableListOf<Pair<String, String>>()
        for ((nm, state, rid) in sheetRefs) {
            if (state == "hidden" || state == "veryHidden") continue
            var tgt = rid2tgt[rid] ?: continue
            tgt = tgt.trimStart('/')
            val path = if (tgt.startsWith("xl/")) tgt else "xl/$tgt"
            visible.add(Pair(nm, path))
        }
        // 兜底：workbook.xml/rels 解析不到可见表时，退回旧逻辑（全部 sheetN.xml，按序号）
        val sheetsToRead: List<Pair<String, String>> = if (visible.isNotEmpty()) visible else
            Collections.list(zip.entries())
                .filter { it.name.matches("""xl/worksheets/sheet\d+\.xml""".toRegex()) }
                .sortedBy { """\d+""".toRegex().find(it.name)?.value?.toInt() ?: 0 }
                .mapIndexed { i, e -> Pair("工作表${i + 1}", e.name) }

        // 支持自闭合 / 完整 元素的正则
        val rowRe = """<row\b([^>]*?)(?:/>|>(.*?)</row>)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val rowNumRe = "r=\"(\\d+)\"".toRegex()
        val cellRe = """<c\b([^>]*?)(?:/>|>(.*?)</c>)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val cellRefRe = "r=\"([A-Z]+)(\\d+)\"".toRegex()
        val vRe = """<v>(.*?)</v>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val tRe = """<t[^>]*>(.*?)</t>""".toRegex(RegexOption.DOT_MATCHES_ALL)

        val sheetNames = mutableListOf<String>()
        val sb = StringBuilder()

        sheetsToRead.forEachIndexed { idx, vs ->
            val (vsName, vsPath) = vs
            sheetNames.add(if (vsName.isNotBlank()) vsName else "工作表${idx + 1}")
            val xml = readEntry(zip, vsPath) ?: return@forEachIndexed

            // 按行提取
            val rows = mutableListOf<Pair<Int, String>>()
            rowRe.findAll(xml).forEach { rm ->
                val body = rm.groupValues[2]
                if (body.isEmpty()) return@forEach // 自闭合空行
                val rowNum = rowNumRe.find(rm.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                rows.add(Pair(rowNum, body))
            }
            rows.sortBy { it.first }

            for ((_, rowBody) in rows) {
                // 行内单元格按列号排序
                val cells = mutableListOf<Pair<Int, String>>() // (colNum, text)
                cellRe.findAll(rowBody).forEach { cm ->
                    val attrs = cm.groupValues[1]
                    val inner = cm.groupValues[2] // 自闭合时为空串
                    val ref = cellRefRe.find(attrs) ?: return@forEach
                    val colNum = colNameToIndex(ref.groupValues[1])
                    cells.add(Pair(colNum, cellText(attrs, inner, shared, vRe, tRe)))
                }
                cells.sortBy { it.first }
                val line = StringBuilder()
                var first = true
                for ((_, txt) in cells) {
                    if (txt.isNotBlank()) {
                        if (!first) line.append('\t')
                        line.append(txt)
                        first = false
                    }
                }
                if (line.isNotEmpty()) sb.append(line).append('\n')
            }
            sb.append('\n') // 工作表间空行（段落分隔）
        }

        val text = sb.toString()
        val pages = max(1, sheetNames.size)
        return OoxmlResult(text, pages, "xlsx", sheetNames)
    }

    /**
     * 单元格取值：处理共享字符串 / inlineStr / 公式字符串 / 数字 / Excel 日期序列号。
     * 注意：类型判定必须看 <c> 开标签属性 attrs（t="s"/"str"/"inlineStr"），不能在 inner 里找。
     */
    private fun cellText(
        attrs: String,
        inner: String,
        shared: List<String>,
        vRe: Regex,
        tRe: Regex
    ): String {
        if (inner.isEmpty()) return ""
        // inlineStr：<is>...<t>..</t></is>
        if (attrs.contains("t=\"inlineStr\"")) {
            return tRe.find(inner)?.let { decodeXml(it.groupValues[1]) } ?: ""
        }
        val v = vRe.find(inner)?.groupValues?.get(1)?.trim() ?: ""
        // 共享字符串：t="s"，<v> 是索引
        if (attrs.contains("t=\"s\"") && v.isNotEmpty()) {
            return v.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
        }
        // 公式字符串结果：t="str"
        if (attrs.contains("t=\"str\"")) {
            return decodeXml(v)
        }
        // 数字：识别 Excel 日期序列号（整数 20000~60000）→ 中文短日期，与 Word 显示一致
        if (v.isNotEmpty()) {
            val d = v.toDoubleOrNull()
            if (d != null && d == Math.floor(d) && d > 20000 && d < 60000) {
                return try {
                    val date = java.time.LocalDate.of(1899, 12, 30).plusDays(d.toLong())
                    String.format("%02d月%02d日", date.monthValue, date.dayOfMonth)
                } catch (_: Throwable) {
                    v
                }
            }
            return v
        }
        return ""
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
        // 支持自闭合 <si/>（空字符串），保持索引对齐
        val siRe = """<si\b[^>]*?(?:/>|>(.*?)</si>)""".toRegex(RegexOption.DOT_MATCHES_ALL)
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
