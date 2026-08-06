package com.henry.aligntool.engine.extract

import com.henry.aligntool.engine.Anchor
import com.henry.aligntool.engine.Block
import com.henry.aligntool.engine.Font
import com.henry.aligntool.engine.OoxmlUtil
import com.henry.aligntool.engine.Slot
import com.henry.aligntool.engine.XElement

/**
 * docx 抽取（等价桌面 align_core._docx_walk :?，产出与写入共用同一遍历顺序）。
 *
 * 遍历 word/document.xml 的 <w:body>：
 * - 每个 <w:p> → 段落 Slot（Anchor.DocxPara 持有该 <w:p>）
 * - 每个 <w:tbl> 的每个 <w:tc> → 单元格 Slot（Anchor.DocxCell）
 * 表格内的嵌套段落不单独计数（避免与单元格重复），与桌面一致。
 */
object DocxExtractor {

    fun extract(dom: XElement): List<Slot> {
        val doc = dom.findFirst("document") ?: return emptyList()
        val body = doc.findFirst("body") ?: return emptyList()
        val out = mutableListOf<Slot>()
        walk(body, out)
        return out
    }

    private fun walk(el: XElement, out: MutableList<Slot>) {
        for (child in el.children.filterIsInstance<XElement>()) {
            when (child.localName) {
                "p" -> {
                    // v1.0.14：封面 <w:p> 内可含多个 <w:txbxContent>，每个独立成 Slot。
                    // v1.0.15：只取 mc:Choice 分支的文本框；跳过 mc:Fallback（VML 旧文本框，
                    // 无尺寸且会造成重复插入 → 译文被插两遍、乱）。
                    val txbxes = child.find("txbxContent").filter { !isUnderFallback(it) }
                    if (txbxes.isNotEmpty()) {
                        for (box in txbxes) {
                            val text = OoxmlUtil.collectText(box, "t")
                            out.add(Slot(Anchor.DocxTextbox(child, box), Block(text, firstRunFont(box), align = jcVal(child))))
                        }
                    } else {
                        val text = OoxmlUtil.collectText(child, "t")
                        out.add(Slot(Anchor.DocxPara(child), Block(text, firstRunFont(child), align = jcVal(child))))
                    }
                }
                "tbl" -> {
                    for (tr in child.find("tr")) {
                        for (tc in tr.find("tc")) {
                            val text = OoxmlUtil.collectText(tc, "t")
                            out.add(Slot(Anchor.DocxCell(tc), Block(text, firstRunFont(tc), align = jcVal(tc), isCell = true)))
                        }
                    }
                }
                else -> walk(child, out) // 文本框(txtbxContent)等嵌套段落仍计入；tbl 已单独处理不会误入
            }
        }
    }

    /** 判断元素是否位于 <mc:Fallback> 分支内（旧版 VML 文本框，应跳过，避免重复插入）。 */
    private fun isUnderFallback(el: XElement): Boolean {
        var e: XElement? = el.parent
        while (e != null) {
            if (e.localName == "Fallback") return true
            e = e.parent
        }
        return false
    }

    private fun firstRunFont(scope: XElement): Font? {
        // 1) 段落内第一个「带 <w:rFonts>」的 run（避免首个 run 仅是数字/标点而无字体，导致整段译文丢字体）
        for (r in scope.find("r")) {
            val f = OoxmlUtil.docxFontFromRpr(r.findFirst("rPr"))
            if (f != null) return f
        }
        // 2) 回退到段落标记属性 <w:pPr><w:rPr>（整段统一字体时常落在段落级而非每个 run）
        val pPr = scope.findFirst("pPr")
        if (pPr != null) {
            val f = OoxmlUtil.docxFontFromRpr(pPr.findFirst("rPr"))
            if (f != null) return f
        }
        return null
    }

    /** 取段落对齐 w:jc 的 val（居中=center / 两端对齐=both / 左=left / 右=right）。单元格取首个段落。 */
    private fun jcVal(scope: XElement): String? {
        val p = if (scope.localName == "tc") scope.findFirst("p") else scope
        return p?.findFirst("pPr")?.findFirst("jc")?.ownAttr("val")
    }
}
