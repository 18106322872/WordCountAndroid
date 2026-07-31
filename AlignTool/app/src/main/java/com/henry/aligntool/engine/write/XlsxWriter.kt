package com.henry.aligntool.engine.write

import com.henry.aligntool.model.AlignOptions
import com.henry.aligntool.engine.Anchor
import com.henry.aligntool.engine.Block
import com.henry.aligntool.engine.Font
import com.henry.aligntool.model.MarkMode
import com.henry.aligntool.engine.Slot
import com.henry.aligntool.engine.XmlDom
import com.henry.aligntool.engine.XElement

/**
 * xlsx 写入（等价桌面 export_xlsx :1066）。
 *
 * 同一单元格内换行追加对方语言：own + "\n" + other（otherFirst 时反过来）。
 * 改为 inlineStr 写入（不再依赖共享字符串表），用对方字体重建 <rPr>。
 *
 * 已知 MVP 限制：
 * - 竖排 textRotation==255 → 横排 0、自动开启 wrap_text 需改 cellXfs 样式，
 *   本期未做（见开发说明 §5.3，列为 v2 保真增强）。
 */
object XlsxWriter {

    private const val SS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    fun apply(slots: List<Slot>, pairs: List<Pair<Block, Block>>, options: AlignOptions) {
        for (i in pairs.indices) {
            val slot = slots.getOrNull(i) ?: break
            val other = pairs[i].second
            val own = pairs[i].first
            if (other.text.isBlank()) continue
            if (other.text.trim() == own.text.trim()) continue
            if (slot.anchor is Anchor.XlsxCell) {
                replaceCell(slot.anchor.c, own.text, other, options)
            }
        }
    }

    private fun replaceCell(c: XElement, own: String, other: Block, options: AlignOptions) {
        val combined = if (options.otherFirst) "${other.text}\n$own" else "$own\n${other.text}"
        c.setAttr("t", "", "", "inlineStr")
        c.children.clear()
        val rpr = buildRpr(other.font, options.markSource)
        val xml = "<is xmlns=\"$SS\"><r>$rpr<t xml:space=\"preserve\">${escText(combined)}</t></r></is>"
        c.appendChild(XmlDom.parseFragment(xml))
    }

    private fun buildRpr(font: Font?, mark: MarkMode): String {
        val sb = StringBuilder("<rPr>")
        if (font?.name != null) {
            val n = escAttr(font.name)
            sb.append("<rFonts ascii=\"$n\" hAnsi=\"$n\" eastAsia=\"$n\"/>")
        }
        if (font?.sizePt != null) sb.append("<sz val=\"${(font.sizePt * 2).toInt()}\"/>")
        if (font?.bold == true) sb.append("<b/>")
        if (font?.italic == true) sb.append("<i/>")
        if (font?.color != null) sb.append("<color rgb=\"FF${font.color}\"/>")
        if (mark == MarkMode.BOLD) sb.append("<b/>")
        if (mark == MarkMode.HIGHLIGHT) sb.append("<color rgb=\"FFFFF2CC\"/>")
        sb.append("</rPr>")
        return sb.toString()
    }

    private fun escText(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escAttr(s: String): String = escText(s).replace("\"", "&quot;")
}
