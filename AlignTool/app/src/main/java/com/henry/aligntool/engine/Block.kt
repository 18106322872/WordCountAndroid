package com.henry.aligntool.engine

/**
 * 字体信息（格式无关）。
 * 由抽取层从 first run 读取；写入层据此在骨架文件里重建对应格式(rPr / rPr)的字体属性。
 *
 * 等价桌面 align_core._docx_run_font (:196) / _pptx_para_font (:1121) 的字体 dict。
 */
data class Font(
    val name: String? = null,        // 兜底字体名（中/英）；分字样不全时回退用
    val sizePt: Double? = null,      // 字号（磅）
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val color: String? = null,       // 十六进制 RRGGBB，大写，不含 '#'
    // 分字样（OOXML 原生）：保证中/英/复杂文字各用原文对应字体，避免中文被套英文西文字体
    val ascii: String? = null,       // docx/xlsx w:ascii
    val hAnsi: String? = null,       // docx/xlsx w:hAnsi
    val eastAsia: String? = null,    // docx/xlsx w:eastAsia（中文等关键）
    val cs: String? = null,          // docx/xlsx w:cs 或 pptx a:cs
    val latin: String? = null,       // pptx a:latin
    val ea: String? = null           // pptx a:ea（中文等关键）
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
    // 段落对齐：docx 存 w:jc 的 val（如 center/both/left/right），pptx 存 a:pPr 的 algn（如 ctr/just/l/r）。
    // 写入端据此在插入的译文段落里重建 pPr 对齐，避免合并后全退回左对齐。
    val align: String? = null,
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
