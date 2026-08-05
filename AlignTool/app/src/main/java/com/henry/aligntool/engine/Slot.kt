package com.henry.aligntool.engine

/**
 * 抽取得到的「插入锚点」+ 对应文本块。
 *
 * 抽取与写入共用同一份 XmlDom，因此锚点直接持有 DOM 元素引用，
 * 写入时按 slots 顺序与配对结果一一对应，保证「第 i 个抽取块 = 第 i 个插入点」。
 */
sealed class Anchor {
    /** docx 段落：持有 <w:p> 元素引用。 */
    data class DocxPara(val p: XElement) : Anchor()

    /** docx 浮动文本框：持有父 <w:p> + 对应 <w:txbxContent>（v1.0.14：一个 <w:p> 可含多个文本框，需逐一对应）。 */
    data class DocxTextbox(val p: XElement, val box: XElement) : Anchor()

    /** docx 表格单元格：持有 <w:tc> 元素引用。 */
    data class DocxCell(val tc: XElement) : Anchor()

    /** xlsx 单元格：持有 <c> 元素引用 + 位置。 */
    data class XlsxCell(
        val sheetPart: String,
        val c: XElement,
        val sheetIdx: Int,
        val row: Int,
        val col: Int
    ) : Anchor()

    /** pptx 段落：位于某 slide 某 shape 内，持有 <a:p> 元素引用 + 位置。 */
    data class PptxPara(
        val slidePart: String,
        val p: XElement,
        val slideIdx: Int,
        val shapeIdx: Int,
        val innerIdx: Int
    ) : Anchor()

    /** SmartArt 节点：持有 dgm:pt 元素引用 + modelId。 */
    data class SmartArtPt(
        val dataPart: String,
        val pt: XElement,
        val modelId: String
    ) : Anchor()
}

data class Slot(val anchor: Anchor, val block: Block)
