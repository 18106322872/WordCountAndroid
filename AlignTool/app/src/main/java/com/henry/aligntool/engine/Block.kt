package com.henry.aligntool.engine

/**
 * 字体信息（格式无关）。
 * 由抽取层从 first run 读取；写入层据此在骨架文件里重建对应格式(rPr / rPr)的字体属性。
 *
 * 等价桌面 align_core._docx_run_font (:196) / _pptx_para_font (:1121) 的字体 dict。
 */
data class Font(
    val name: String? = null,        // 字体名（中/英）
    val sizePt: Double? = null,      // 字号（磅）
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val color: String? = null        // 十六进制 RRGGBB，大写，不含 '#'
)

/**
 * 文本块（等价桌面 align_core.Block :30）。
 *
 * - 普通段落：text = 段落文本，font = 首个 run 字体，tableRows = null
 * - 表格：text = 整表压平文本，tableRows = 单元格文本矩阵，fontMatrix = 对应字体矩阵
 * - Excel 单元格：sheetIdx/row/col 携带位置，用于「位置配对」
 * - PPTX 段落/单元格：slideIdx/shapeIdx/innerIdx 携带位置
 * - SmartArt 节点：modelId 携带位置
 */
data class Block(
    val text: String,
    val font: Font? = null,
    val tableRows: List<List<String>>? = null,
    val fontMatrix: List<List<Font?>>? = null,
    // Excel 位置
    val sheetIdx: Int? = null,
    val row: Int? = null,
    val col: Int? = null,
    // PPTX 位置
    val slideIdx: Int? = null,
    val shapeIdx: Int? = null,
    val innerIdx: Int? = null,
    // SmartArt 位置
    val modelId: String? = null
) {
    val isTable: Boolean get() = tableRows != null

    /** 表格压平文本（等价桌面 Block.flatten，用于去重/预览）。 */
    fun flatten(): String {
        if (!isTable) return text
        return tableRows!!.joinToString("\n") { row ->
            row.map { (it ?: "").trim() }.joinToString("\t")
        }
    }
}
