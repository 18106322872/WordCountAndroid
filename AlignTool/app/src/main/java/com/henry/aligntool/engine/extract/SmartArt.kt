package com.henry.aligntool.engine.extract

import com.henry.aligntool.engine.Anchor
import com.henry.aligntool.engine.Block
import com.henry.aligntool.engine.Font
import com.henry.aligntool.engine.OoxmlUtil
import com.henry.aligntool.engine.Slot
import com.henry.aligntool.engine.XElement

/**
 * SmartArt（diagram）抽取（等价桌面 _pptx_smartart_* :301-498）。
 *
 * 解析 ppt/diagrams/dataN.xml 的 dgm:dataModel → 每个 dgm:pt 抽文本（dgm:t 内的 a:t）。
 * 为不与幻灯片正文段落的位置键冲突，SmartArt 块使用偏移 slideIdx（10000+图序）。
 */
object SmartArt {

    fun extract(diagrams: List<Pair<String, XElement>>): List<Slot> {
        val out = mutableListOf<Slot>()
        diagrams.forEachIndexed { di, (dataPart, dom) ->
            val pts = dom.find("pt") // 递归包含所有层级 dgm:pt
            pts.forEachIndexed { pi, pt ->
                val modelId = pt.getAttrValue("modelId") ?: pi.toString()
                val text = OoxmlUtil.collectText(pt, "t")
                val font = firstRunFont(pt)
                out.add(
                    Slot(
                        Anchor.SmartArtPt(dataPart, pt, modelId),
                        Block(
                            text, font,
                            slideIdx = 10000 + di,
                            shapeIdx = 0,
                            innerIdx = pi,
                            modelId = modelId
                        )
                    )
                )
            }
        }
        return out
    }

    private fun firstRunFont(scope: XElement): Font? {
        val r = scope.findFirst("r") ?: return null
        return OoxmlUtil.pptxFontFromRpr(r.findFirst("rPr"))
    }
}
