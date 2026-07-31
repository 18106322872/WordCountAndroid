package com.henry.aligntool.engine.write

import com.henry.aligntool.model.AlignOptions
import com.henry.aligntool.engine.Anchor
import com.henry.aligntool.engine.Block
import com.henry.aligntool.model.MarkMode
import com.henry.aligntool.engine.Slot
import com.henry.aligntool.engine.XmlDom
import com.henry.aligntool.engine.XElement

/**
 * SmartArt 写入（等价桌面 _pptx_smartart_* :301-498）。
 *
 * 在 dgm:pt 的 dgm:t 内、原有 <a:p> 之后新增译文段落（保留原文 run/字体）。
 * 属 v2 保真增强（开发说明 §5.5），此处提供可用实现。
 */
object SmartArtWriter {

    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"

    fun apply(slots: List<Slot>, pairs: List<Pair<Block, Block>>, options: AlignOptions) {
        for (i in pairs.indices) {
            val slot = slots.getOrNull(i) ?: break
            val other = pairs[i].second
            val own = pairs[i].first
            if (other.text.isBlank()) continue
            if (other.text.trim() == own.text.trim()) continue
            if (slot.anchor is Anchor.SmartArtPt) {
                insertIntoPt(slot.anchor.pt, other, options)
            }
        }
    }

    private fun insertIntoPt(pt: XElement, other: Block, options: AlignOptions) {
        val dgmT = pt.findFirst("t") ?: return // dgm:t
        val refP = dgmT.find("p").lastOrNull() ?: return // a:p
        val frag = buildPara(other.font, other.text, options.markSource)
        if (options.otherFirst) dgmT.insertBefore(refP, frag) else dgmT.insertAfter(refP, frag)
    }

    private fun buildPara(font: com.henry.aligntool.engine.Font?, text: String, mark: MarkMode): XElement {
        val rpr = buildRpr(font, mark)
        val xml = "<a:p xmlns:a=\"$A\"><a:r>$rpr<a:t xml:space=\"preserve\">${escText(text)}</a:t></a:r></a:p>"
        return XmlDom.parseFragment(xml)
    }

    private fun buildRpr(font: com.henry.aligntool.engine.Font?, mark: MarkMode): String {
        val sb = StringBuilder("<a:rPr lang=\"zh-CN\"")
        if (font?.sizePt != null) sb.append(" sz=\"${(font.sizePt * 100).toInt()}\"")
        if (font?.bold == true) sb.append(" b=\"1\"")
        if (font?.italic == true) sb.append(" i=\"1\"")
        sb.append(">")
        if (font?.name != null) {
            val n = escAttr(font.name)
            sb.append("<a:latin typeface=\"$n\"/><a:ea typeface=\"$n\"/><a:cs typeface=\"$n\"/>")
        }
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
