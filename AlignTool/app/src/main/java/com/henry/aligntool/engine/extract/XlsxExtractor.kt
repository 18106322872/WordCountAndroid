package com.henry.aligntool.engine.extract

import com.henry.aligntool.engine.Anchor
import com.henry.aligntool.engine.Block
import com.henry.aligntool.engine.Font
import com.henry.aligntool.engine.OoxmlUtil
import com.henry.aligntool.engine.Slot
import com.henry.aligntool.engine.XElement

/**
 * xlsx 抽取（等价桌面 _xlsx_walk :274，按 sheet/row/col 顺序产出，与位置配对一致）。
 *
 * 解析 xl/sharedStrings.xml（共享字符串）、xl/styles.xml（单元格字体），
 * 再逐工作表抽单元格。每个 <c> 产出一个 XlsxCell 锚点（持有该 <c> 元素 + 位置）。
 */
object XlsxExtractor {

    /** 解析共享字符串表 → 索引文本列表。 */
    fun parseSharedStrings(dom: XElement?): List<String> {
        if (dom == null) return emptyList()
        val out = mutableListOf<String>()
        for (si in dom.find("si")) {
            val sb = StringBuilder()
            for (t in si.find("t")) sb.append(OoxmlUtil.collectText(t, "t"))
            out.add(sb.toString())
        }
        return out
    }

    /** 解析样式 → 按「单元格样式索引(s)」索引的字体列表（cellXfs → fonts）。 */
    fun parseStylesFonts(dom: XElement?): List<Font?> {
        if (dom == null) return emptyList()
        val fontEls = dom.findFirst("fonts")?.find("font") ?: return emptyList()
        val fontList = fontEls.map { parseFontEl(it) }
        val xfs = dom.findFirst("cellXfs")?.find("xf") ?: return fontList
        return xfs.map { xf ->
            val fid = xf.ownAttr("fontId")?.toIntOrNull() ?: 0
            fontList.getOrNull(fid)
        }
    }

    private fun parseFontEl(fontEl: XElement): Font {
        val name = fontEl.findFirst("name")?.ownAttr("val")
        val sz = fontEl.findFirst("sz")?.ownAttr("val")?.toDoubleOrNull()
        val bold = fontEl.findFirst("b") != null
        val italic = fontEl.findFirst("i") != null
        val color = fontEl.findFirst("color")?.let { it.ownAttr("rgb") ?: it.ownAttr("indexed") }
        val colorHex = color?.let { if (it.length >= 6) it.takeLast(6) else it }
        return Font(
            name = name,
            sizePt = sz,
            bold = if (bold) true else null,
            italic = if (italic) true else null,
            underline = null,
            color = colorHex
        )
    }

    /** 抽取单个工作表（已解析为 dom）的单元格。 */
    fun extractSheet(
        sheetDom: XElement,
        sheetPart: String,
        sheetIdx: Int,
        shared: List<String>,
        fonts: List<Font?>
    ): List<Slot> {
        val cells = mutableListOf<XElement>()
        for (c in sheetDom.find("c")) cells.add(c)

        // 按 (row, col) 排序，保证与桌面遍历顺序一致
        val sorted = cells.sortedWith(compareBy(
            { rowOf(cells, it) },
            { colOf(it) }
        ))
        val out = mutableListOf<Slot>()
        for (c in sorted) {
            val rAttr = c.ownAttr("r") ?: continue
            val (colLetters, rowStr) = splitRef(rAttr)
            val row = rowStr.toIntOrNull() ?: 0
            val col = colNameToIndex(colLetters)
            val text = cellText(c, shared)
            val sIdx = c.ownAttr("s")?.toIntOrNull() ?: 0
            val font = fonts.getOrNull(sIdx)
            out.add(
                Slot(
                    Anchor.XlsxCell(sheetPart, c, sheetIdx, row, col),
                    Block(text, font, sheetIdx = sheetIdx, row = row, col = col)
                )
            )
        }
        return out
    }

    private fun rowOf(all: List<XElement>, c: XElement): Int {
        val r = c.ownAttr("r") ?: return 0
        return splitRef(r).second.toIntOrNull() ?: 0
    }

    private fun colOf(c: XElement): Int {
        val r = c.ownAttr("r") ?: return 0
        return colNameToIndex(splitRef(r).first)
    }

    private fun splitRef(ref: String): Pair<String, String> {
        val letters = ref.takeWhile { it.isLetter() }
        val digits = ref.dropWhile { it.isLetter() }
        return letters to digits
    }

    private fun cellText(c: XElement, shared: List<String>): String {
        val t = c.ownAttr("t")
        return when (t) {
            "s" -> {
                val idx = c.findFirst("v")?.let { OoxmlUtil.collectText(it, "v") }?.trim()?.toIntOrNull()
                idx?.let { shared.getOrNull(it) } ?: ""
            }
            "inlineStr" -> {
                val isEl = c.findFirst("is")
                isEl?.let { OoxmlUtil.collectText(it, "t") } ?: ""
            }
            "str" -> {
                c.findFirst("v")?.let { OoxmlUtil.collectText(it, "v") }
                    ?.let { OoxmlUtil.decodeXml(it) } ?: ""
            }
            else -> {
                // 数字 / 公式结果（日期序列号转换暂略，保留原值）
                c.findFirst("v")?.let { OoxmlUtil.collectText(it, "v") }?.trim() ?: ""
            }
        }
    }

    /** Excel 列名 → 列索引（A=0, B=1, ..., Z=25, AA=26, ...）。 */
    private fun colNameToIndex(name: String): Int {
        var idx = 0
        for (c in name) idx = idx * 26 + (c.uppercaseChar().code - 'A'.code + 1)
        return idx - 1
    }
}
