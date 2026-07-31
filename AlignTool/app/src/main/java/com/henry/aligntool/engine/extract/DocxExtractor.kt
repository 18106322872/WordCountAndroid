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
                    val text = OoxmlUtil.collectText(child, "t")
                    out.add(Slot(Anchor.DocxPara(child), Block(text, firstRunFont(child))))
                }
                "tbl" -> {
                    for (tr in child.find("tr")) {
                        for (tc in tr.find("tc")) {
                            val text = OoxmlUtil.collectText(tc, "t")
                            out.add(Slot(Anchor.DocxCell(tc), Block(text, firstRunFont(tc))))
                        }
                    }
                }
                else -> walk(child, out) // 文本框(txtbxContent)等嵌套段落仍计入；tbl 已单独处理不会误入
            }
        }
    }

    private fun firstRunFont(scope: XElement): Font? {
        val r = scope.findFirst("r") ?: return null
        return OoxmlUtil.docxFontFromRpr(r.findFirst("rPr"))
    }
}
