package com.henry.aligntool.engine

import com.henry.aligntool.engine.extract.DocxExtractor
import com.henry.aligntool.engine.extract.PptxExtractor
import com.henry.aligntool.engine.extract.SmartArt
import com.henry.aligntool.engine.extract.XlsxExtractor
import com.henry.aligntool.engine.write.DocxWriter
import com.henry.aligntool.engine.write.PptxWriter
import com.henry.aligntool.engine.write.SmartArtWriter
import com.henry.aligntool.engine.write.XlsxWriter
import com.henry.aligntool.model.AlignOptions
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 对齐引擎总入口（等价桌面 align_core.run_align :1456）。
 *
 * 流程：解析骨架文件 → 抽取 Slots(锚点+块) → 解析对方文件 → 抽取 Blocks →
 * Pairing.blockPairs 配对 → 各格式 Writer 原位插入 → 序列化被改动的部件并重写 zip。
 *
 * 与桌面一致：抽取与写入共用同一遍历顺序，保证「第 i 个抽取块 = 第 i 个插入点」。
 * 不引入 Chaquopy / Python / POI：纯 Kotlin + 纯 XML（XmlDom）实现。
 */
object AlignEngine {

    enum class Format { DOCX, XLSX, PPTX, UNKNOWN }

    data class AlignResult(
        val success: Boolean,
        val message: String,
        val paired: Int,
        val extras: Int,
        val outputFile: File? = null
    )

    fun detectFormat(file: File): Format = when (file.extension.lowercase()) {
        "docx" -> Format.DOCX
        "xlsx" -> Format.XLSX
        "pptx" -> Format.PPTX
        else -> Format.UNKNOWN
    }

    fun runAlign(skeleton: File, other: File, options: AlignOptions, outFile: File): AlignResult {
        val skelFmt = detectFormat(skeleton)
        val othFmt = detectFormat(other)
        if (skelFmt == Format.UNKNOWN || othFmt == Format.UNKNOWN)
            return AlignResult(false, "仅支持 docx / xlsx / pptx", 0, 0)
        return when (skelFmt) {
            Format.DOCX -> runDocx(skeleton, other, options, outFile)
            Format.XLSX -> runXlsx(skeleton, other, options, outFile)
            Format.PPTX -> runPptx(skeleton, other, options, outFile)
            Format.UNKNOWN -> AlignResult(false, "不支持的格式", 0, 0)
        }
    }

    // ───────────────────────── docx ─────────────────────────
    private fun runDocx(skeleton: File, other: File, options: AlignOptions, outFile: File): AlignResult {
        val skelDom = try {
            readPart(skeleton, "word/document.xml") ?: return fail("无法读取 word/document.xml")
        } catch (e: Throwable) {
            return fail("解析骨架文档失败: ${e.message}")
        }
        val skelSlots = try {
            DocxExtractor.extract(skelDom)
        } catch (e: Throwable) {
            return fail("抽取骨架段落失败(文档可能过大或结构异常): ${e.message}")
        }
        val othBytes = ZipUtil.readEntry(other, "word/document.xml")
        val othSlots = if (othBytes != null) try {
            DocxExtractor.extract(XmlDom.parse(othBytes.inputStream()))
        } catch (e: Throwable) {
            return fail("抽取译文文档失败: ${e.message}")
        } else emptyList()
        val (pairs, extras) = try {
            Pairing.blockPairs(skelSlots.map { it.block }, othSlots.map { it.block })
        } catch (e: Throwable) {
            return fail("配对失败: ${e.message}")
        }
        try {
            DocxWriter.apply(skelDom, skelSlots, pairs, options, extras)
        } catch (e: Throwable) {
            return fail("写入译文失败: ${e.message}")
        }
        val serialized = try {
            XmlDom.serialize(skelDom).toByteArray(StandardCharsets.UTF_8)
        } catch (e: Throwable) {
            return fail("序列化文档失败(可能内存不足): ${e.message}")
        }
        val replacements = mapOf("word/document.xml" to serialized)
        try {
            ZipUtil.rewriteEntries(skeleton, replacements, outFile)
        } catch (e: Throwable) {
            return fail("输出文件写入失败: ${e.message}")
        }
        return AlignResult(true, "对照完成", pairs.size, extras.size, outFile)
    }

    // ───────────────────────── xlsx ─────────────────────────
    private fun runXlsx(skeleton: File, other: File, options: AlignOptions, outFile: File): AlignResult {
        val (skelParts, skelDoms) = loadXlsxSheets(skeleton)
        val skelShared = XlsxExtractor.parseSharedStrings(readPart(skeleton, "xl/sharedStrings.xml"))
        val skelFonts = XlsxExtractor.parseStylesFonts(readPart(skeleton, "xl/styles.xml"))
        val skelSlots = skelParts.flatMapIndexed { i, part ->
            XlsxExtractor.extractSheet(skelDoms[part]!!, part, i, skelShared, skelFonts)
        }

        val (othParts, othDoms) = loadXlsxSheets(other)
        val othShared = XlsxExtractor.parseSharedStrings(readPart(other, "xl/sharedStrings.xml"))
        val othFonts = XlsxExtractor.parseStylesFonts(readPart(other, "xl/styles.xml"))
        val othSlots = othParts.flatMapIndexed { i, part ->
            XlsxExtractor.extractSheet(othDoms[part]!!, part, i, othShared, othFonts)
        }

        val (pairs, extras) = Pairing.blockPairs(skelSlots.map { it.block }, othSlots.map { it.block })
        XlsxWriter.apply(skelSlots, pairs, options)
        val replacements = skelParts.associateWith { XmlDom.serialize(skelDoms[it]!!).toByteArray(StandardCharsets.UTF_8) }
        ZipUtil.rewriteEntries(skeleton, replacements, outFile)
        return AlignResult(true, "对照完成", pairs.size, extras.size, outFile)
    }

    // ───────────────────────── pptx ─────────────────────────
    private fun runPptx(skeleton: File, other: File, options: AlignOptions, outFile: File): AlignResult {
        val skelSlides = listParts(skeleton, "ppt/slides/slide", ".xml")
        val skelSlideDoms = skelSlides.associateWith { readPart(skeleton, it)!! }
        val skelDiagrams = listParts(skeleton, "ppt/diagrams/data", ".xml")
        val skelDiagDoms = skelDiagrams.associateWith { readPart(skeleton, it)!! }
        val skelSlots = skelSlides.flatMapIndexed { i, part ->
            PptxExtractor.extractSlide(skelSlideDoms[part]!!, part, i)
        } + SmartArt.extract(skelDiagDoms.toList())

        val othSlides = listParts(other, "ppt/slides/slide", ".xml")
        val othSlideDoms = othSlides.associateWith { readPart(other, it)!! }
        val othDiagrams = listParts(other, "ppt/diagrams/data", ".xml")
        val othDiagDoms = othDiagrams.associateWith { readPart(other, it)!! }
        val othSlots = othSlides.flatMapIndexed { i, part ->
            PptxExtractor.extractSlide(othSlideDoms[part]!!, part, i)
        } + SmartArt.extract(othDiagDoms.toList())

        val (pairs, extras) = Pairing.blockPairs(skelSlots.map { it.block }, othSlots.map { it.block })
        PptxWriter.apply(skelSlots, pairs, options)
        SmartArtWriter.apply(skelSlots, pairs, options)
        val replacements = (skelSlides + skelDiagrams).associateWith { part ->
            val dom = if (part in skelSlideDoms) skelSlideDoms[part]!! else skelDiagDoms[part]!!
            XmlDom.serialize(dom).toByteArray(StandardCharsets.UTF_8)
        }
        ZipUtil.rewriteEntries(skeleton, replacements, outFile)
        return AlignResult(true, "对照完成", pairs.size, extras.size, outFile)
    }

    // ───────────────────────── 工具 ─────────────────────────
    private fun fail(msg: String) = AlignResult(false, msg, 0, 0)

    private fun readPart(file: File, name: String): XElement? {
        val bytes = ZipUtil.readEntry(file, name) ?: return null
        return XmlDom.parse(bytes.inputStream())
    }

    /** 列出 ppt/.../nameN.xml 类部件，按 N 排序返回完整部件名。 */
    private fun listParts(file: File, prefix: String, suffix: String): List<String> {
        return ZipUtil.listEntries(file)
            .filter { it.startsWith(prefix) && it.endsWith(suffix) }
            .sortedBy { Regex("""\d+""").find(it)?.value?.toInt() ?: 0 }
    }

    /** 读 workbook + rels，按文档顺序返回工作表部件路径，并解析各工作表 dom。 */
    private fun loadXlsxSheets(file: File): Pair<List<String>, Map<String, XElement>> {
        val wb = readPart(file, "xl/workbook.xml") ?: return emptyList<String>() to emptyMap()
        val rels = readPart(file, "xl/_rels/workbook.xml.rels")
        val rid2tgt = mutableMapOf<String, String>()
        rels?.find("Relationship")?.forEach { rel ->
            val id = rel.getAttrValue("Id")
            val tgt = rel.getAttrValue("Target")
            if (id != null && tgt != null) rid2tgt[id] = tgt
        }
        val parts = mutableListOf<String>()
        for (s in wb.find("sheet")) {
            val rid = s.getAttrValue("r:id") ?: continue
            val tgt = rid2tgt[rid] ?: continue
            parts.add(if (tgt.startsWith("/")) tgt.trimStart('/') else "xl/$tgt")
        }
        val doms = parts.associateWith { readPart(file, it)!! }
        return parts to doms
    }
}
