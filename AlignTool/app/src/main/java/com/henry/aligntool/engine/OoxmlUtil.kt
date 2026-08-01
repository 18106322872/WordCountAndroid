package com.henry.aligntool.engine

/**
 * OOXML 抽取/字体解析共享工具。复刻 WordCountAndroid.OoXmlEngine 的实体解码与
 * 桌面 align_core 的字体读取逻辑，供各抽取器/写入器复用。
 */
object OoxmlUtil {

    fun decodeXml(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toInt().toChar().toString() }

    /**
     * 收集 el 子树内所有 [leaf] 叶子元素的文本（文档顺序）。
     * docx 额外把 w:tab→\t、w:br/w:cr→\n（pptx 无此结构，自动忽略）。
     */
    fun collectText(el: XElement, leaf: String): String {
        val sb = StringBuilder()
        fun walk(n: XNode) {
            if (n !is XElement) return
            when (n.localName) {
                leaf -> for (c in n.children) if (c is XText) sb.append(decodeXml(c.text))
                "tab" -> sb.append('\t')
                "br", "cr" -> sb.append('\n')
                else -> for (c in n.children) walk(c)
            }
        }
        walk(el)
        return sb.toString()
    }

    /** 从 docx 的 <w:rPr> 解析字体（等价 align_core._docx_run_font :196）。 */
    fun docxFontFromRpr(rPr: XElement?): Font? {
        if (rPr == null) return null
        val rFonts = rPr.findFirst("rFonts")
        // 四种字样分别保留，不能直接压成一个 name（中文 eastAsia 与西文 ascii 往往不同）
        val ascii = rFonts?.ownAttr("ascii")
        val hAnsi = rFonts?.ownAttr("hAnsi")
        val eastAsia = rFonts?.ownAttr("eastAsia")
        val cs = rFonts?.ownAttr("cs")
        val name = ascii ?: hAnsi ?: eastAsia ?: cs
        val sz = rPr.findFirst("sz")?.ownAttr("val")?.toDoubleOrNull()?.let { it / 2 }
        val bold = rPr.findFirst("b")?.let { it.ownAttr("val") != "0" } ?: false
        val italic = rPr.findFirst("i")?.let { it.ownAttr("val") != "0" } ?: false
        val underline = rPr.findFirst("u")?.let {
            val v = it.ownAttr("val")
            v != null && v != "none" && v != "0"
        } ?: false
        val color = rPr.findFirst("color")?.ownAttr("val")
        if (name == null && sz == null && !bold && !italic && !underline && color == null
            && ascii == null && hAnsi == null && eastAsia == null && cs == null) return null
        return Font(
            name = name,
            sizePt = sz,
            bold = if (bold) true else null,
            italic = if (italic) true else null,
            underline = if (underline) true else null,
            color = color,
            ascii = ascii,
            hAnsi = hAnsi,
            eastAsia = eastAsia,
            cs = cs
        )
    }

    /** 从 pptx 的 <a:rPr> 解析字体（等价 align_core._pptx_para_font :1121）。 */
    fun pptxFontFromRpr(rPr: XElement?): Font? {
        if (rPr == null) return null
        val sz = rPr.ownAttr("sz")?.toDoubleOrNull()?.let { it / 100 } // sz 单位为百分之一磅
        val bold = boolAttrOrChild(rPr, "b")
        val italic = boolAttrOrChild(rPr, "i")
        val underline = rPr.findFirst("u")?.let {
            val v = it.ownAttr("val")
            v != null && v != "none" && v != "0"
        } ?: (rPr.ownAttr("u")?.let { it != "none" && it != "0" } ?: false)
        // 西文(latin)与中文(ea)分开保留
        val latin = rPr.findFirst("latin")?.ownAttr("typeface")
        val ea = rPr.findFirst("ea")?.ownAttr("typeface")
        val cs = rPr.findFirst("cs")?.ownAttr("typeface")
        val name = latin ?: ea ?: cs
        val color = rPr.findFirst("solidFill")?.findFirst("srgbClr")?.ownAttr("val")
        if (name == null && sz == null && !bold && !italic && !underline && color == null
            && latin == null && ea == null && cs == null) return null
        return Font(
            name = name,
            sizePt = sz,
            bold = if (bold) true else null,
            italic = if (italic) true else null,
            underline = if (underline) true else null,
            color = color,
            latin = latin,
            ea = ea,
            cs = cs
        )
    }

    private fun boolAttrOrChild(rPr: XElement, attr: String): Boolean {
        val av = rPr.ownAttr(attr)
        if (av != null) return av == "1" || av == "true" || av == "on"
        return rPr.findFirst(attr) != null
    }
}
