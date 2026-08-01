package com.henry.aligntool.engine

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader

/**
 * 极简、命名空间安全的 XML DOM，专为 Android 纯 XML 读写 OOXML 设计。
 *
 * 为什么不用 javax.xml.parsers / org.w3c.dom：
 *   Android 不提供完整的 DOM 实现（javax.xml.parsers.DocumentBuilder 在运行时抛错），
 *   且 poi-ooxml 依赖的 StAX 在 Android 上也会崩（见开发说明 §3）。
 * 本类只用 Android 运行时自带的 org.xmlpull.v1（kxml2）解析，自带可序列化 DOM，
 * 完整保留命名空间声明与属性顺序，满足 OOXML 回写需求。
 *
 * 设计要点：
 * - 解析时记录每个元素的 xmlns 声明（nsDecls）与其属性（含命名空间）。
 * - 序列化时只重新输出「祖先作用域内尚未声明」的命名空间，避免重复 xmlns。
 * - 提供 insertBefore/insertAfter/appendChild/removeChild，供 Writer 原位插入。
 */
sealed interface XNode

data class XText(val text: String) : XNode

data class XAttr(
    val nsUri: String,
    val prefix: String,
    val localName: String,
    val value: String
) {
    val qname: String get() = if (prefix.isEmpty()) localName else "$prefix:$localName"
}

class XElement(
    var nsUri: String,
    var prefix: String,
    var localName: String,
    val nsDecls: MutableMap<String, String> = mutableMapOf(), // prefix -> uri（"" 表示默认命名空间）
    val attrs: MutableList<XAttr> = mutableListOf(),
    val children: MutableList<XNode> = mutableListOf()
) : XNode {
    var parent: XElement? = null

    val qname: String get() = if (prefix.isEmpty()) localName else "$prefix:$localName"

    fun getAttr(localName: String, nsUri: String = ""): XAttr? =
        attrs.firstOrNull { it.localName == localName && it.nsUri == nsUri }

    fun getAttrValue(localName: String, nsUri: String = ""): String? =
        getAttr(localName, nsUri)?.value

    /** 取与元素自身同命名空间的属性（OOXML 中无前缀属性继承元素命名空间）。 */
    fun ownAttr(localName: String): String? = getAttrValue(localName, nsUri)

    /** 查找所有 localName 匹配的子元素（递归，深度优先）。 */
    fun find(localName: String): List<XElement> {
        val out = mutableListOf<XElement>()
        for (c in children) if (c is XElement) {
            if (c.localName == localName) out.add(c)
            out.addAll(c.find(localName))
        }
        return out
    }

    fun findFirst(localName: String): XElement? = find(localName).firstOrNull()

    fun appendChild(node: XNode) {
        if (node is XElement) node.parent = this
        children.add(node)
    }

    fun insertBefore(ref: XNode, node: XNode) {
        val idx = children.indexOf(ref)
        if (idx < 0) {
            appendChild(node)
            return
        }
        if (node is XElement) node.parent = this
        children.add(idx, node)
    }

    fun insertAfter(ref: XNode, node: XNode) {
        val idx = children.indexOf(ref)
        if (idx < 0) {
            appendChild(node)
            return
        }
        if (node is XElement) node.parent = this
        children.add(idx + 1, node)
    }

    fun removeChild(node: XNode) {
        children.remove(node)
    }

    /** 设置/替换属性（按 localName+nsUri 定位）。 */
    fun setAttr(localName: String, nsUri: String, prefix: String, value: String) {
        val i = attrs.indexOfFirst { it.localName == localName && it.nsUri == nsUri }
        val a = XAttr(nsUri, prefix, localName, value)
        if (i >= 0) attrs[i] = a else attrs.add(a)
    }

    /** 删除属性（按 localName+nsUri）。 */
    fun removeAttr(localName: String, nsUri: String = "") {
        attrs.removeIf { it.localName == localName && it.nsUri == nsUri }
    }
}

object XmlDom {

    private fun newFactory(): XmlPullParserFactory {
        val f = XmlPullParserFactory.newInstance()
        f.setNamespaceAware(true)
        return f
    }

    /** 解析整个 XML，返回合成根（#root），其 children 为真正的顶层元素。 */
    fun parse(input: InputStream): XElement = parse(XmlPullParserFactory.newInstance().let {
        val f = newFactory()
        val p = f.newPullParser()
        p.setInput(input, "UTF-8")
        p
    })

    fun parseString(xml: String): XElement {
        val p = newFactory().newPullParser()
        p.setInput(StringReader(xml))
        return parse(p)
    }

    private fun parse(parser: XmlPullParser): XElement {
        val root = XElement("", "", "#root")
        val stack = ArrayDeque<XElement>()
        stack.addLast(root)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val uri = parser.namespace ?: ""
                    val pre = parser.prefix ?: ""
                    val name = parser.name
                    val el = XElement(uri, pre, name)
                    // 命名空间声明
                    val depth = parser.depth
                    for (i in 0 until parser.getNamespaceCount(depth)) {
                        val dp = parser.getNamespacePrefix(i) ?: ""
                        val du = parser.getNamespaceUri(i) ?: ""
                        if (du.isNotEmpty()) el.nsDecls[dp] = du
                    }
                    // 属性
                    for (i in 0 until parser.attributeCount) {
                        el.attrs.add(
                            XAttr(
                                nsUri = parser.getAttributeNamespace(i) ?: "",
                                prefix = parser.getAttributePrefix(i) ?: "",
                                localName = parser.getAttributeName(i),
                                value = parser.getAttributeValue(i) ?: ""
                            )
                        )
                    }
                    stack.last().appendChild(el)
                    stack.addLast(el)
                }
                XmlPullParser.END_TAG -> {
                    if (stack.size > 1) stack.removeLast()
                }
                XmlPullParser.TEXT, XmlPullParser.IGNORABLE_WHITESPACE -> {
                    stack.last().children.add(XText(parser.text ?: ""))
                }
                XmlPullParser.CDSECT -> {
                    stack.last().children.add(XText(parser.text ?: ""))
                }
            }
            event = parser.next()
        }
        return root
    }

    /** 解析单根片段（用于 Writer 构造插入元素），返回该根 XElement。 */
    fun parseFragment(xml: String): XElement {
        val root = parseString(xml)
        return root.children.filterIsInstance<XElement>().firstOrNull()
            ?: throw IllegalArgumentException("XmlDom.parseFragment: 无根元素")
    }

    /** 序列化整棵 DOM。standalone 默认 true（OOXML 部件惯例）。 */
    fun serialize(root: XElement, standalone: Boolean = true): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"")
        sb.append(if (standalone) "yes" else "no")
        sb.append("\"?>\n")
        val inScope = mutableMapOf<String, String>() // prefix -> uri（"" 默认）
        for (child in root.children) serializeNode(child, inScope, sb)
        return sb.toString()
    }

    private fun serializeNode(node: XNode, inScope: MutableMap<String, String>, sb: StringBuilder) {
        when (node) {
            is XText -> sb.append(escapeText(node.text))
            is XElement -> {
                // 计算需要新声明的命名空间
                val newDecls = mutableMapOf<String, String>()
                for ((p, u) in node.nsDecls) {
                    if (inScope[p] != u) newDecls[p] = u
                }
                val childScope = LinkedHashMap(inScope)
                childScope.putAll(newDecls)

                sb.append('<').append(node.qname)
                for ((p, u) in newDecls) {
                    if (p.isEmpty()) sb.append(" xmlns=\"").append(u).append('"')
                    else sb.append(" xmlns:").append(p).append("=\"").append(u).append('"')
                }
                for (a in node.attrs) {
                    sb.append(' ').append(a.qname).append("=\"").append(escapeAttr(a.value)).append('"')
                }
                if (node.children.isEmpty()) {
                    sb.append("/>")
                } else {
                    sb.append('>')
                    for (c in node.children) serializeNode(c, childScope, sb)
                    sb.append("</").append(node.qname).append('>')
                }
            }
        }
    }

    private fun escapeText(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttr(s: String): String = escapeText(s).replace("\"", "&quot;")
}
