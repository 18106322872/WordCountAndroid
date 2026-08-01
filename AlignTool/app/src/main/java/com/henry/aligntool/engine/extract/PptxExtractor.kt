package com.henry.aligntool.engine.extract

import com.henry.aligntool.engine.Anchor
import com.henry.aligntool.engine.Block
import com.henry.aligntool.engine.Font
import com.henry.aligntool.engine.OoxmlUtil
import com.henry.aligntool.engine.Slot
import com.henry.aligntool.engine.XElement

/**
 * pptx 抽取（等价桌面 _pptx_walk / _block_pairs_pptx :150）。
 *
 * 遍历每张 slide 的 <p:sp>（文本框/标题）与 <p:graphicFrame> 中的表格单元格，
 * 逐个 <a:p> 产出 PptxPara 锚点（持有该 <a:p> + 位置 slide/shape/inner）。
 * SmartArt（diagram）由 SmartArt.kt 单独抽取，避免与正文段落重复。
 */
object PptxExtractor {

    fun extractSlide(slideDom: XElement, slidePart: String, slideIdx: Int): List<Slot> {
        val out = mutableListOf<Slot>()
        var shapeIdx = 0

        for (sp in slideDom.find("sp")) {
            val txBody = sp.findFirst("txBody") ?: continue
            shapeIdx = addParas(txBody, slidePart, slideIdx, shapeIdx, out)
        }

        for (gf in slideDom.find("graphicFrame")) {
            if (isSmartArt(gf)) continue
            val tbl = gf.findFirst("tbl")
            if (tbl != null) {
                // 表格：每个单元格的 txBody 作为独立 shape 处理
                for (row in tbl.find("tr")) {
                    for (tc in row.find("tc")) {
                        val txBody = tc.findFirst("txBody") ?: continue
                        shapeIdx = addParas(txBody, slidePart, slideIdx, shapeIdx, out)
                    }
                }
            } else {
                val txBody = gf.findFirst("txBody") ?: continue
                shapeIdx = addParas(txBody, slidePart, slideIdx, shapeIdx, out)
            }
        }
        return out
    }

    private fun addParas(
        txBody: XElement,
        slidePart: String,
        slideIdx: Int,
        startShapeIdx: Int,
        out: MutableList<Slot>
    ): Int {
        var shapeIdx = startShapeIdx
        var innerIdx = 0
        for (p in txBody.find("p")) {
            val text = OoxmlUtil.collectText(p, "t")
            val font = firstRunFont(p)
            out.add(
                Slot(
                    Anchor.PptxPara(slidePart, p, slideIdx, shapeIdx, innerIdx),
                    Block(
                        text, font,
                        align = pAlgn(p),
                        slideIdx = slideIdx, shapeIdx = shapeIdx, innerIdx = innerIdx
                    )
                )
            )
            innerIdx++
        }
        return shapeIdx + 1
    }

    private fun firstRunFont(scope: XElement): Font? {
        val r = scope.findFirst("r") ?: return null
        return OoxmlUtil.pptxFontFromRpr(r.findFirst("rPr"))
    }

    /** 取段落对齐 a:pPr 的 algn（居中=ctr / 两端对齐=just / 左=l / 右=r）。 */
    private fun pAlgn(p: XElement): String? {
        return p.findFirst("pPr")?.ownAttr("algn")
    }

    /** graphicFrame 是否承载 SmartArt（diagram）。 */
    fun isSmartArt(gf: XElement): Boolean {
        val gd = gf.findFirst("graphicData")
        val uri = gd?.getAttrValue("uri", "a") ?: ""
        return uri.contains("diagram")
    }
}
