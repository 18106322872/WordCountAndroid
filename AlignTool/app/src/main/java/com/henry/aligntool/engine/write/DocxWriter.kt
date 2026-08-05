package com.henry.aligntool.engine.write

import com.henry.aligntool.model.AlignOptions
import com.henry.aligntool.engine.Anchor
import kotlin.math.ceil
import kotlin.math.max
import com.henry.aligntool.engine.Block
import com.henry.aligntool.engine.OoxmlUtil
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
                is Anchor.DocxTextbox -> insertIntoTextbox(a.box, other, options)
                is Anchor.DocxCell -> insertIntoCell(a.tc, other, options)
                else -> {}
            }
        }
        if (extras.isNotEmpty()) appendExtras(docDom, extras)
    }

    private fun insertPara(docDom: XElement, anchorP: XElement, other: Block, options: AlignOptions) {
        // 锚点段落若含浮动文本框（封面等），把译文逐行插入文本框内部（v1.0.13 修复：
        // 译文进原文文本框、且按行分段，不再作为框外一坨平铺段落）。
        val box = anchorP.findFirst("txbxContent")
        if (box != null) {
            insertIntoTextbox(box, other, options)
            return
        }
        val frag = buildPara(other.font, other.text, options.markSource, other.align)
        if (options.otherFirst) anchorP.parent?.insertBefore(anchorP, frag)
        else anchorP.parent?.insertAfter(anchorP, frag)
    }

    private fun insertIntoTextbox(box: XElement, other: Block, options: AlignOptions) {
        // 译文按换行拆成多段（collectText 已在段落边界补 \n），过滤空行，逐行插入文本框。
        val lines = other.text.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        // 文本框内容通常居中（封面），无对齐信息时默认居中；有则沿用原文对齐。
        val align = other.align ?: "center"
        // v1.0.15：文本框尺寸固定、译文常比原文长 → 估算统一字号，保证原文+译文整体不溢出框高
        // （只缩小、绝不放大；框不增大 → 不互相覆盖、不延伸到下一页）。
        val (wPt, hPt) = boxExtentPoints(box)
        val origText = OoxmlUtil.collectText(box, "t")
        val baseSz = max(existingMaxSz(box), other.font?.sizePt ?: 18.0)
        val totalChars = origText.length + other.text.length
        val fitSz = fitFontSize(totalChars, wPt, hPt, baseSz)
        val font = if (fitSz < baseSz - 0.01) other.font?.copy(sizePt = fitSz) else other.font
        val frags = lines.map { buildPara(font, it, MarkMode.NONE, align, tight = true) }
        if (options.otherFirst) {
            val first = box.children.firstOrNull()
            if (first != null) for (f in frags.asReversed()) box.insertBefore(first, f)
            else for (f in frags) box.appendChild(f)
        } else {
            for (f in frags) box.appendChild(f)
        }
        // 若需要缩小，则把框内全部 run（原文+译文）统一设为适配字号，确保无溢出。
        if (fitSz < baseSz - 0.01) setRunSizes(box, (fitSz * 2).toInt())
    }

    /** 从文本框向上找 <wp:extent> 读尺寸（EMU），返回 (宽pt, 高pt)；找不到返回 0。 */
    private fun boxExtentPoints(box: XElement): Pair<Double, Double> {
        var e: XElement? = box.parent
        while (e != null) {
            val ext = e.findFirst("extent")
            if (ext != null) {
                val cx = ext.ownAttr("cx")?.toDoubleOrNull()
                val cy = ext.ownAttr("cy")?.toDoubleOrNull()
                if (cx != null && cy != null) return Pair(cx / 12700.0, cy / 12700.0)
            }
            e = e.parent
        }
        return Pair(0.0, 0.0)
    }

    /** 文本框内现有 run 的最大字号（半磅值 /2）；无显式 sz 时回退 18。 */
    private fun existingMaxSz(box: XElement): Double {
        var m = 0.0
        for (r in box.find("r")) {
            val sz = r.findFirst("rPr")?.findFirst("sz")?.ownAttr("val")?.toDoubleOrNull()
            if (sz != null && sz / 2 > m) m = sz / 2
        }
        return if (m > 0) m else 18.0
    }

    /** 估算在 sz 字号下，totalChars 文字在宽 wPt 文本框内需要的行数（保守：字符宽按 0.6em）。 */
    private fun estimateLines(totalChars: Int, wPt: Double, sz: Double): Int {
        val usableW = max(20.0, wPt - 16.0)
        val cpl = max(1.0, usableW / (sz * 0.6))
        return max(1, ceil(totalChars / cpl).toInt())
    }

    /** 从 baseSz 向下搜索，取能放进框高的最大字号（行高按 1.4 估算留安全余量；下限 6pt）。 */
    private fun fitFontSize(totalChars: Int, wPt: Double, hPt: Double, baseSz: Double): Double {
        if (wPt <= 0 || hPt <= 0) return baseSz
        val usableH = max(8.0, hPt - 12.0)
        val floor = 6.0
        var best = floor
        var sz = baseSz
        while (sz >= floor - 0.01) {
            val need = estimateLines(totalChars, wPt, sz) * sz * 1.4
            if (need <= usableH) { best = sz; break }
            sz -= 0.5
        }
        return best
    }

    /** 把 scope 内所有 <w:r> 的字号统一设为 halfPt（保留字体名），用于缩字号适配。 */
    private fun setRunSizes(scope: XElement, halfPt: Int) {
        for (r in scope.find("r")) {
            var rpr = r.findFirst("rPr")
            if (rpr == null) {
                rpr = XmlDom.parseFragment("<w:rPr xmlns:w=\"$W\"></w:rPr>")
                r.children.add(0, rpr)
                rpr.parent = r
            }
            rpr.setAttr("sz", W, "w", halfPt.toString())
            rpr.setAttr("szCs", W, "w", halfPt.toString())
        }
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

    private fun buildPara(font: Font?, text: String, mark: MarkMode, align: String?, tight: Boolean = false): XElement {
        val rpr = buildRpr(font, mark)
        // 段落对齐：有则重建 <w:jc/>（v1.0.11 修复全退回左对齐）；
        // tight：文本框内译文收紧段间距（before/after=0，单倍行距），避免溢出框。
        val pprParts = mutableListOf<String>()
        if (tight) pprParts.add("<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>")
        if (align != null) pprParts.add("<w:jc w:val=\"${escAttr(align)}\"/>")
        val ppr = if (pprParts.isNotEmpty()) "<w:pPr>${pprParts.joinToString("")}</w:pPr>" else ""
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
        .replace("\n", "<w:br/>")

    private fun escAttr(s: String): String = escText(s).replace("\"", "&quot;")
}
