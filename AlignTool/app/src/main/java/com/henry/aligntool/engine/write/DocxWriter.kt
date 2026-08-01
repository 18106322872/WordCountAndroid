package com.henry.aligntool.engine.write

import com.henry.aligntool.model.AlignOptions
import com.henry.aligntool.engine.Anchor
import com.henry.aligntool.engine.Block
import com.henry.aligntool.engine.Font
import com.henry.aligntool.model.MarkMode
import com.henry.aligntool.engine.Pairing
import com.henry.aligntool.engine.Slot
import com.henry.aligntool.engine.XmlDom
import com.henry.aligntool.engine.XElement

/**
 * docx 写入（等价桌面 export_docx :979 / _docx_new_para_after :883）。
 *
 * 以骨架的 <w:p>/<w:tc> 为锚点，原位插入「对方语言」段落/文本：
 * - 继承对方原文字体（用对方 Block.font 重建 <w:rPr>）
 * - 内容相同（邮箱/数字等）不重复插入（去重）
 * - otherFirst 控制插在骨架之上/之下
 * - 未配对内容附在文档末尾（UNPAIRED_MARK）
 *
 * v2 待办（MVP 暂不做，见开发说明 §5.4）：Word 自动编号独立（_clone_abstract_num）。
 */
object DocxWriter {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun apply(
        docDom: XElement,
        slots: List<Slot>,
        pairs: List<Pair<Block, Block>>,
        options: AlignOptions,
        extras: List<Pair<String, Block>>
    ) {
        for (i in pairs.indices) {
            val slot = slots.getOrNull(i) ?: break
            val other = pairs[i].second
            val own = pairs[i].first
            if (other.text.isBlank()) continue
            if (other.text.trim() == own.text.trim()) continue // 去重
            when (val a = slot.anchor) {
                is Anchor.DocxPara -> insertPara(docDom, a.p, other, options)
                is Anchor.DocxCell -> insertIntoCell(a.tc, other, options)
                else -> {}
            }
        }
        if (extras.isNotEmpty()) appendExtras(docDom, extras)
    }

    private fun insertPara(docDom: XElement, anchorP: XElement, other: Block, options: AlignOptions) {
        val frag = buildPara(other.font, other.text, options.markSource, other.align)
        if (options.otherFirst) anchorP.parent?.insertBefore(anchorP, frag)
        else anchorP.parent?.insertAfter(anchorP, frag)
    }

    private fun insertIntoCell(tc: XElement, other: Block, options: AlignOptions) {
        val paras = tc.find("p")
        val ref = if (options.otherFirst) paras.firstOrNull() else paras.lastOrNull()
        val frag = buildPara(other.font, other.text, options.markSource, other.align)
        if (ref != null) {
            if (options.otherFirst) tc.insertBefore(ref, frag) else tc.insertAfter(ref, frag)
        } else {
            tc.appendChild(frag)
        }
    }

    private fun appendExtras(docDom: XElement, extras: List<Pair<String, Block>>) {
        val body = docDom.findFirst("document")?.findFirst("body") ?: return
        body.appendChild(buildPara(null, "――― 以下为未配对内容 ―――", MarkMode.NONE, null))
        for ((side, b) in extras) {
            val label = if (side == "src") "[原文] " else "[译文] "
            body.appendChild(buildPara(b.font, label + b.text, MarkMode.NONE, null))
        }
    }

    private fun buildPara(font: Font?, text: String, mark: MarkMode, align: String?): XElement {
        val rpr = buildRpr(font, mark)
        // 段落对齐：有则重建 <w:pPr><w:jc/>，保持译文居中/两端对齐等排版（v1.0.11 修复全退回左对齐）
        val ppr = if (align != null) "<w:pPr><w:jc w:val=\"${escAttr(align)}\"/></w:pPr>" else ""
        val xml = "<w:p xmlns:w=\"$W\">$ppr<w:r>$rpr<w:t xml:space=\"preserve\">${escText(text)}</w:t></w:r></w:p>"
        return XmlDom.parseFragment(xml)
    }

    private fun buildRpr(font: Font?, mark: MarkMode): String {
        val sb = StringBuilder("<w:rPr>")
        // 分字样写回：原文有几个字样就写几个，保证中文 eastAsia ≠ 西文 ascii（与译文文件一致）
        val rf = mutableListOf<String>()
        if (font?.ascii != null) rf += "w:ascii=\"${escAttr(font.ascii!!)}\""
        if (font?.hAnsi != null) rf += "w:hAnsi=\"${escAttr(font.hAnsi!!)}\""
        if (font?.eastAsia != null) rf += "w:eastAsia=\"${escAttr(font.eastAsia!!)}\""
        if (font?.cs != null) rf += "w:cs=\"${escAttr(font.cs!!)}\""
        if (rf.isNotEmpty()) {
            sb.append("<w:rFonts ${rf.joinToString(" ")}/>")
        } else if (font?.name != null) {
            // 兜底：仅有一个合并名时，四种字样统一（xlsx 等单名字体场景）
            val n = escAttr(font.name)
            sb.append("<w:rFonts w:ascii=\"$n\" w:hAnsi=\"$n\" w:eastAsia=\"$n\" w:cs=\"$n\"/>")
        }
        if (font?.sizePt != null) sb.append("<w:sz w:val=\"${(font.sizePt * 2).toInt()}\"/>")
        if (font?.bold == true) sb.append("<w:b/>")
        if (font?.italic == true) sb.append("<w:i/>")
        if (font?.underline == true) sb.append("<w:u w:val=\"single\"/>")
        if (font?.color != null) sb.append("<w:color w:val=\"${font.color}\"/>")
        if (mark == MarkMode.BOLD) sb.append("<w:b/>")
        if (mark == MarkMode.HIGHLIGHT) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"FFF2CC\"/>")
        sb.append("</w:rPr>")
        return sb.toString()
    }

    private fun escText(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escAttr(s: String): String = escText(s).replace("\"", "&quot;")
}
