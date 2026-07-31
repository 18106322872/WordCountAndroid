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
 * pptx 写入（等价桌面 export_pptx :1313 / _ppt_insert_para :1214）。
 *
 * 文本框/表格内原位插入对方语言段落，用对方自身字体（保持译文字体独立，桌面 v1.0.9 修过）。
 *
 * v2 待办（MVP 暂不做，见开发说明 §5.5）：超框自动缩字 _ppt_autoshrink :1257。
 */
object PptxWriter {

    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"

    fun apply(slots: List<Slot>, pairs: List<Pair<Block, Block>>, options: AlignOptions) {
        for (i in pairs.indices) {
            val slot = slots.getOrNull(i) ?: break
            val other = pairs[i].second
            val own = pairs[i].first
            if (other.text.isBlank()) continue
            if (other.text.trim() == own.text.trim()) continue
            if (slot.anchor is Anchor.PptxPara) {
                insertPara(slot.anchor.p, other, options)
            }
        }
    }

    private fun insertPara(anchorP: XElement, other: Block, options: AlignOptions) {
        val frag = buildPara(other.font, other.text, options.markSource)
        if (options.otherFirst) anchorP.parent?.insertBefore(anchorP, frag)
        else anchorP.parent?.insertAfter(anchorP, frag)
    }

    private fun buildPara(font: Font?, text: String, mark: MarkMode): XElement {
        val rpr = buildRpr(font, mark)
        val xml = "<a:p xmlns:a=\"$A\"><a:r>$rpr<a:t xml:space=\"preserve\">${escText(text)}</a:t></a:r></a:p>"
        return XmlDom.parseFragment(xml)
    }

    private fun buildRpr(font: Font?, mark: MarkMode): String {
        val sb = StringBuilder("<a:rPr lang=\"zh-CN\"")
        if (font?.sizePt != null) sb.append(" sz=\"${(font.sizePt * 100).toInt()}\"")
        if (font?.bold == true) sb.append(" b=\"1\"")
        if (font?.italic == true) sb.append(" i=\"1\"")
        sb.append(">")
        if (font?.name != null) {
            val n = escAttr(font.name)
            sb.append("<a:latin typeface=\"$n\"/><a:ea typeface=\"$n\"/><a:cs typeface=\"$n\"/>")
        }
        if (font?.underline == true) sb.append("<a:u/>")
        if (font?.color != null) sb.append("<a:solidFill><a:srgbClr val=\"${font.color}\"/></a:solidFill>")
        if (mark == MarkMode.BOLD) sb.append("<a:b/>")
        if (mark == MarkMode.HIGHLIGHT) sb.append("<a:solidFill><a:srgbClr val=\"FFF2CC\"/></a:solidFill>")
        sb.append("</a:rPr>")
        return sb.toString()
    }

    private fun escText(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escAttr(s: String): String = escText(s).replace("\"", "&quot;")
}
