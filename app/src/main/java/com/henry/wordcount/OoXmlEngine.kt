package com.henry.wordcount

import java.io.File
import java.util.zip.ZipFile
import java.io.ByteArrayOutputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Office Open XML（.docx / .xlsx / .pptx）纯 Kotlin 文本抽取引擎。
 *
 * 不依赖 poi-ooxml（Android StAX 兼容性问题），直接将 OOXML 当 ZIP 解压后
 * 用 SAX 读取内部 XML，提取文本内容。
 *
 * 支持格式：
 *   - .docx → word/document.xml 中 <w:t> 元素
 *   - .xlsx → xl/sharedStrings.xml + 各 sheet 的 <v> 引用
 *   - .pptx → ppt/slides/slideN.xml 中 <a:t> 元素
 */
object OoXmlEngine {

    fun extractText(file: File): String {
        val ext = file.extension.lowercase()
        ZipFile(file).use { zip ->
            return when (ext) {
                "docx" -> extractDocx(zip)
                "xlsx" -> extractXlsx(zip)
                "pptx" -> extractPptx(zip)
                else -> throw IllegalArgumentException("不支持的 OOXML 格式: .$ext")
            }
        }
    }

    // ── DOCX ──────────────────────────────────────────────
    private fun extractDocx(zip: ZipFile): String {
        val entry = zip.getEntry("word/document.xml")
            ?: return ""
        val bytes = readZipEntry(zip, entry)
        return parseDocxXml(bytes)
    }

    /** 用简易 SAX 提取 <w:t> 内的文本（忽略 <w:tab/>、<w:br/> 等特殊元素，只取文字）*/
    internal fun parseDocxXml(bytes: ByteArray): String {
        val sb = StringBuilder()
        val factory = SAXParserFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setNamespaceAware(true)
        val parser = factory.newSAXParser()
        val handler = object : org.xml.sax.helpers.DefaultHandler() {
            private var inWt = false
            private val buf = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attrs: org.xml.sax.Attributes?) {
                // w:t 是 Word 文本元素（namespace URI 为 http://schemas.openxmlformats.org/wordprocessingml/2006/main）
                if (localName == "t") inWt = true
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (localName == "t" && inWt) {
                    sb.append(buf)
                    buf.clear()
                    inWt = false
                }
                // 段落结束加换行
                if (localName == "p") sb.append("\n")
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inWt && ch != null) buf.append(ch, start, length)
            }
        }
        parser.parse(bytes.inputStream(), handler)
        return sb.toString()
    }

    // ── XLSX ──────────────────────────────────────────────
    private fun extractXlsx(zip: ZipFile): String {
        // 1. 读取共享字符串表
        val sharedEntry = zip.getEntry("xl/sharedStrings.xml")
        val sharedStrings = if (sharedEntry != null) {
            val bytes = readZipEntry(zip, sharedEntry)
            parseSharedStrings(bytes)
        } else emptyList()

        // 2. 遍历所有 sheet
        val sb = StringBuilder()
        val sheetEntries = zip.entries().toList()
            .filter { it.name.startsWith("xl/worksheets/") && it.name.endsWith(".xml") }
            .sortedBy { it.name }

        for (sheetEnt in sheetEntries) {
            val sheetBytes = readZipEntry(zip, sheetEnt)
            sb.append(parseSheetXml(sheetBytes, sharedStrings))
            sb.append("\n")
        }
        return sb.toString()
    }

    /** 解析 sharedStrings.xml 返回字符串列表 */
    internal fun parseSharedStrings(bytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val factory = SAXParserFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setNamespaceAware(true)
        val parser = factory.newSAXParser()
        val handler = object : org.xml.sax.helpers.DefaultHandler() {
            private var inSi = false
            private var inT = false
            private val buf = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attrs: org.xml.sax.Attributes?) {
                if (localName == "si") { inSi = true; buf.clear() }
                if (localName == "t" && inSi) inT = true
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (localName == "t" && inT) inT = false
                if (localName == "si" && inSi) {
                    strings.add(buf.toString())
                    buf.clear()
                    inSi = false
                }
            }
            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inT && ch != null) buf.append(ch, start, length)
            }
        }
        parser.parse(bytes.inputStream(), handler)
        return strings
    }

    /** 解析单个 sheet XML，用共享字符串表解析单元格引用 */
    internal fun parseSheetXml(bytes: ByteArray, sharedStrings: List<String>): String {
        val rows = mutableMapOf<Int, MutableList<Pair<Int, String>>>() // rowNum -> [(colIdx, text)]
        val factory = SAXParserFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setNamespaceAware(true)
        val parser = factory.newSAXParser()
        val handler = object : org.xml.sax.helpers.DefaultHandler() {
            private var currentRow = -1
            private var currentCol = -1
            private var inV = false
            private var cellType = "s" // s=shared string, inlineStr=inline, other=direct value
            private val buf = StringBuilder()
            private var firstRow = true

            override fun startElement(uri: String?, localName: String?, qName: String?, attrs: org.xml.sax.Attributes?) {
                when (localName) {
                    "row" -> {
                        currentRow = attrs?.getValue("r")?.toIntOrNull() ?: (rows.keys.maxOrNull()?.plus(1) ?: 1)
                        firstRow = false
                    }
                    "c" -> {
                        currentCol = colToIndex(attrs?.getValue("c") ?: "")
                        cellType = attrs?.getValue("t") ?: ""
                    }
                    "v" -> { inV = true; buf.clear() }
                    "is" -> { /* inline string */ cellType = "inlineStr" }
                    "t" -> { if (cellType == "inlineStr") { inV = true; buf.clear() } }
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (localName) {
                    "v" -> {
                        if (inV) {
                            val text = when (cellType) {
                                "s" -> { // 共享字符串索引
                                    val idx = buf.toString().trim().toIntOrNull()
                                    if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else buf.toString()
                                }
                                "inlineStr" -> buf.toString()
                                else -> buf.toString() // 数字/公式等原样输出
                            }
                            if (currentRow >= 0 && currentCol >= 0) {
                                rows.getOrPut(currentRow) { mutableListOf() }.add(Pair(currentCol, text))
                            }
                        }
                        inV = false
                    }
                    "t" -> { if (cellType == "inlineStr") inV = false }
                    "row" -> currentRow = -1
                }
            }
            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inV && ch != null) buf.append(ch, start, length)
            }
        }
        parser.parse(bytes.inputStream(), handler)

        // 按行号排序输出
        val sheetName = "[工作表]"
        val out = StringBuilder()
        rows.toSortedMap().forEach { (_, cells) ->
            cells.sortedBy { it.first }.forEach { out.append(it.second).append('\t') }
            out.append('\n')
        }
        if (out.isNotEmpty()) out.insert(0, "$sheetName\n")
        return out.toString()
    }

    /** 将 A1/BZ27 形式的列地址转为 0-based 索引 */
    private fun colToIndex(colRef: String): Int {
        var idx = 0
        for (c in colRef) {
            if (c.isLetter()) idx = idx * 26 + (c.uppercaseChar() - 'A' + 1)
            else break
        }
        return idx - 1
    }

    // ── PPTX ──────────────────────────────────────────────
    private fun extractPptx(zip: ZipFile): String {
        val sb = StringBuilder()
        val slideEntries = zip.entries().toList()
            .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
            .sortedBy { it.name }

        for ((idx, slideEnt) in slideEntries.withIndex()) {
            val bytes = readZipEntry(zip, slideEnt)
            val text = parsePptxSlideXml(bytes)
            if (text.isNotBlank()) {
                sb.append("[第${idx + 1}页]\n").append(text).append("\n")
            }
        }
        return sb.toString()
    }

    internal fun parsePptxSlideXml(bytes: ByteArray): String {
        val sb = StringBuilder()
        val factory = SAXParserFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setNamespaceAware(true)
        val parser = factory.newSAXParser()
        val handler = object : org.xml.sax.helpers.DefaultHandler() {
            private var inT = false
            private val buf = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attrs: org.xml.sax.Attributes?) {
                // <a:t> 是 DrawingML 文本元素
                if (localName == "t") inT = true
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (localName == "t" && inT) {
                    sb.append(buf).append(" ")
                    buf.clear()
                    inT = false
                }
            }
            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inT && ch != null) buf.append(ch, start, length)
            }
        }
        parser.parse(bytes.inputStream(), handler)
        return sb.toString()
    }

    // ── 工具方法 ──────────────────────────────────────────
    private fun readZipEntry(zip: ZipFile, entry: java.util.zip.ZipEntry): ByteArray {
        ByteArrayOutputStream().use { bos ->
            zip.getInputStream(entry).use { it.copyTo(bos) }
            return bos.toByteArray()
        }
    }
}
