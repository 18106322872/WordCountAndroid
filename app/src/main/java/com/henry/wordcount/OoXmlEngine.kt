package com.henry.wordcount

import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.math.max

    /**
     * 纯 Kotlin 的 OOXML（docx / xlsx / pptx）文本抽取与页数统计层。
     *
     * v1.0.26 核心改进：
     *   - docx：移除过于激进的字母/CJK过滤（v1.0.24 要求 w:t 内容必须含字母或汉字，
     *          导致纯数字内容如身份证号、学年学期、分数、学分等被丢弃，丢失 ~340 字）
     *   - docx：移除页眉/页脚提取（Word「字数统计」的"包括文本框、脚注和尾注"不含页眉页脚）
     *   - docx：页数统计优化（避免纯文本长度估算导致 1 页文档报 4 页）
     *   - docx：<w:t> 内容做 strip-tags 二次清洗（v1.0.24 遗留，保留）
     */
object OoXmlEngine {

    data class OoxmlResult(
        val text: String,
        val pages: Int,
        val kind: String, // "docx" | "xlsx" | "pptx"
        val sheets: List<String> = emptyList(),
        // v1.3.3: 隐藏工作表（名称 + 抽取文本），默认不计入文件字数，由 UI 决定是否勾选合计
        val hiddenSheets: List<Pair<String, String>> = emptyList(),
        // v1.3.32: PPT 备注幻灯片（名称 + 抽取文本），默认不计入文件字数，由 UI 决定是否勾选合计
        val notesSlides: List<Pair<String, String>> = emptyList(),
        // v1.3.32: PPT 嵌入图片数量（仅计数，不含文本）
        val imageCount: Int = 0,
        // v1.3.32: 文件内部标题（docProps/core.xml 的 <dc:title>），用于修复 URI 无法获取真实文件名的问题
        val internalTitle: String = "",
        val pagesReason: String = "",
        // v1.2.3: docProps/app.xml 中的权威统计（Word/WPS 保存时写入，与 Word 字数统计完全一致）
        // 0 表示无此元数据（如 POI 生成的文件），由调用方退回现算
        val metaPages: Int = 0,
        val metaWords: Int = 0,
        val metaChars: Int = 0,
        // v1.3.92: 标记 docx 是否检测到并剥离了 VML 兼容层文本框。
        // 仅当 hasVml=true 时，MainActivity 才启用 metaWords 安全网（防止无 VML 文件误触发）。
        val hasVml: Boolean = false
    )

    fun extract(file: File): OoxmlResult? {
        val ext = file.extension.lowercase()
        if (ext !in setOf("docx", "xlsx", "pptx")) return null
        val zip = try { ZipFile(file) } catch (_: Throwable) { return null }
        return try {
            when (ext) {
                "docx" -> extractDocx(zip)
                "xlsx" -> extractXlsx(zip)
                "pptx" -> extractPptx(zip)
                else -> null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { zip.close() }
        }
    }

    // ───────────────────────── docx ─────────────────────────
    /**
     * 提取 docx 文本。
     *
     * 仅提取 word/document.xml（正文+文本框），不提取 header/footer。
     * Word「字数统计」对话框的"包括文本框、脚注和尾注"选项**不包括**页眉页脚，
     * 因此页眉页脚不计入统计口径。（v1.0.26 修复：旧版错误地计入了页眉页脚）
     *
     * v1.0.27 页数统计改进：
     *   Word 保存时会写入 w:lastRenderedPageBreak（上次渲染时的分页位置），这是最可靠
     *   的分页信号。优先级：lastRenderedPageBreak > 显式分页符 > sectPr 节数。
     *   三种信号取最大值，确保与 Word 打开时看到的页数一致。
     */
    private fun extractDocx(zip: ZipFile): OoxmlResult {
        val sb = StringBuilder()
        val pageCounter = intArrayOf(0)

        // 1) 主文档 body（word/document.xml）—— 唯一数据源
        val rawXml = readEntry(zip, "word/document.xml") ?: ""

        // v1.3.89 剥离 VML 兼容层文本框（<v:textbox>...</v:textbox>）。
        // Word/WPS 保存 docx 时会对每个文本框同时写 DrawingML (w:txbxContent) 和 VML (v:textbox)
        // 两份格式以确保兼容性。appendDocxXmlText 用 <w:p>/<w:r>/<w:t> 正则无差别提取全部
        // 段落，导致同一文本框文字被算两次（实测营业执照 690 词 vs Word 真值 175）。
        // 剥离 VML 层后只保留 DrawingML 主本，与 Word"包括文本框"口径一致。
        val vmlRe = """<v:textbox[^>]*>.*?</v:textbox>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val hadVml = vmlRe.containsMatchIn(rawXml)
        val bodyXml = vmlRe.replace(rawXml, "")

        appendDocxXmlText(bodyXml, sb) { pageCounter[0]++ }

        // v1.3.94 补充扫描（精确去重模式）：
        // appendDocxXmlText 的 <w:p>/<w:r>/<w:t> 三层正则会遗漏嵌套在
        // mc:AlternateContent > mc:Fallback 容器中的 DrawingML 文本框文本。
        // 兜底：扫描 bodyXml 全部 <w:t>，用 HashSet 精确去重（仅追加全新文本片段），
        // 防止 v1.3.93 不去重导致的膨胀（771 词）。
        // v1.3.95b：统一去重基准 = 已提取文本做「空白归一化」后的拼接串。
        // 旧实现用空白拆分 token 集合，但正文提取把同一段落的多个 <w:t> 直接拼接（无空格），
        // 导致候选片段（单 <w:t> 内容）与集合中的整段 token 粒度不一致 → 漏去重、重复计数。
        // 改用子串匹配（归一化后）：任何已出现过的文本片段都不再计入，
        // 同时覆盖「正文子串 / 页眉页脚复用 / 图表与正文重复」三类重复。
        val normAcc = StringBuilder(sb.toString().replace(Regex("\\s+"), ""))

        val fallbackTRe = """<w:t[^>]*>(.*?)</w:t>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        var addedCount = 0
        fallbackTRe.findAll(bodyXml).forEach { tMatch ->
            val raw = decodeXml(tMatch.groupValues[1])
            val clean = raw.replace("""<[^>]+>""", "")
                .replace("""&[a-z]+;""".toRegex(), "")
                .trim()
            val norm = clean.replace(Regex("\\s+"), "")
            if (norm.isNotEmpty() && normAcc.indexOf(norm) < 0) {
                sb.append(clean)
                sb.append(' ')
                normAcc.append(norm)
                addedCount++
            }
        }
        if (addedCount > 0) {
            Log.d("WordCount", "docx fallback 精确去重后补充了 $addedCount 条新文本")
        }

        // v1.3.95：补齐此前漏统计的 docx 内容（对齐桌面版 wordcount.py 口径，用户清单要求计入）：
        //   - 页眉 / 页脚（header*.xml / footer*.xml）
        //   - 脚注 / 尾注（footnotes.xml / endnotes.xml）
        //   - 内嵌图表文字（word/charts/chartN.xml 的 <a:t>：图表标题/轴标题/系列名）
        //   - SmartArt / 图示文字（word/diagrams/dataN.xml 的 <a:t>）
        // 全部经空白归一化后的子串去重追加，避免与正文/彼此重复计数。
        appendDocxExtraXml(zip, sb, normAcc)

        val text = sb.toString()
        // ── 页数统计（v1.1.14 重写：智能排版感知）──
        //
        // v1.1.13 问题分析（用户用 Word 另存后的文件实测）：
        //   1.docx: LRP=4 → break_based=5 ✓(Word=5) 但用户旧版APP显示4（可能是传输中丢失标记或版本差异）
        //   2.docx: LRP=10 → break_based=11 ✗(Word=12) → 普遍少1页
        //
        // 根因：
        //   a) Word 的 lastRenderedPageBreak 标记的是"分页点"位置，N个标记=N+1页
        //      但 Word 有时不在最后一页写标记（最后一页自然结束不需要），导致 N+1 可能比实际少1
        //   b) v1.1.13 的 content_estimate capping 对有格式的文档（表格/图片）严重偏高
        //      （15749字→估算15~21页，实际仅5页），capping 反而把正确值覆盖成错误值
        //
        // v1.1.14 方案：
        //   A) 有 lastRenderedPageBreak 时：信任它为主信号，但加 +1 安全边距
        //      （因为 Word 经常少写1个末尾标记）
        //   B) 完全不用 content_estimate 做 max-capping（格式文档的估算完全不可靠）
        //   C) 无任何分页标记时：从 XML 读实际页面尺寸+默认字号，算出更准的每页容量

        // 信号 A: lastRenderedPageBreak（Word 自身渲染记录）
        val renderedBreaks = """<w:lastRenderedPageBreak/>""".toRegex().findAll(bodyXml).count()

        var pages: Int
        var pagesReason = ""
        if (renderedBreaks > 0) {
            // 标准公式：N 个 lastRenderedPageBreak 标记 = N+1 页
            // （Word 在每个分页点写标记，从第1页开始，N个标记意味着翻N次到第N+1页）
            // 不再加安全边距——实测表明边距在某些文档上导致多算1~2页
            pages = maxOf(1, 1 + renderedBreaks)
            pagesReason = "word_rendered_breaks_n${renderedBreaks}"
        } else {
            // Fallback: 显式分页符 + 节分隔符
            val explicitBreaks = pageCounter[0]

            val bodyMatchResult = """<w:body>(.*?)</w:body>""".toRegex(RegexOption.DOT_MATCHES_ALL).find(bodyXml)
            var separatorSectPr = 0
            if (bodyMatchResult != null) {
                val bodyContent = bodyMatchResult.groupValues[1]
                val parasInBody = """<w:p[\s>]""".toRegex().findAll(bodyContent).toList()
                val sectsInBody = """<w:sectPr[\s>]""".toRegex().findAll(bodyContent).toList()
                if (sectsInBody.size > 1) {
                    for (i in 0 until sectsInBody.size - 1) {
                        val spRelPos = sectsInBody[i].range.first
                        val hasParaAfter = parasInBody.any { it.range.first > spRelPos }
                        if (hasParaAfter) separatorSectPr++
                    }
                }
            }

            val totalBreaks = explicitBreaks + separatorSectPr
            if (totalBreaks > 0) {
                pages = maxOf(1, 1 + totalBreaks)
                pagesReason = "explicit_breaks_n${totalBreaks}"
            } else {
                // 无任何分页标记：基于实际页面尺寸的智能估算
                // 从 sectPr 读页面尺寸和边距，按中文文档默认排版（宋体/小四/1.5倍行距）计算
                pages = estimatePagesFromLayout(bodyXml, text)
                pagesReason = "layout_estimate"
            }

            // ★ v1.1.14: 不再做 content_estimate max-capping！
            // 格式化文档（含表格/图片）的内容估算会严重偏高（15749字→15~21页 vs 实际5页）
            // 有分页标记时，信任分页信号（已加安全边距），不做覆盖
        }
        // ── v1.2.3: 读取 docProps/app.xml 的权威统计（Word/WPS 保存时写入）──
        // Word 的「字数统计」对话框数值即来源于此：Pages=页数、Words=字数(不计空格)、
        // Characters=字符数(不计空格)。POI 生成的文件无这些字段（仅 <Application>），退回现算。
        val appXml = readEntry(zip, "docProps/app.xml") ?: ""
        val metaPages = extractAppInt(appXml, "Pages")
        val metaWords = extractAppInt(appXml, "Words")
        val metaChars = extractAppInt(appXml, "Characters")

        return OoxmlResult(
            text = text,
            pages = pages,
            kind = "docx",
            pagesReason = pagesReason,
            internalTitle = extractInternalTitle(zip),
            imageCount = countMediaImages(zip),
            metaPages = metaPages,
            metaWords = metaWords,
            metaChars = metaChars,
            hasVml = hadVml
        )
    }

    /**
     * v1.3.95：补齐 docx 此前漏统计的内容并去重追加到 sb。
     * 覆盖项（用户清单 + 桌面版 wordcount.py 口径）：
     *   - 页眉 / 页脚：word/headerN.xml、word/footerN.xml（<w:p>/<w:r>/<w:t> 同正文语法）
     *   - 脚注 / 尾注：word/footnotes.xml、word/endnotes.xml
     *   - 内嵌图表文字：word/charts/chartN.xml 的 <a:t>（图表标题/轴标题/系列名）
     *   - SmartArt / 图示文字：word/diagrams/dataN.xml 的 <a:t>
     * 这些文件中的文本可能与正文重复（页眉页脚被复用），故统一用 existingTokens 去重。
     *
     * @param existingTokens 已提取文本 token 集合（会被追加新 token，供后续继续去重）
     */
    private fun appendDocxExtraXml(zip: ZipFile, sb: StringBuilder, normAcc: StringBuilder) {
        // 1) 页眉 / 页脚 / 脚注 / 尾注：均为 OOXML wordprocessingML，用与正文相同的片段提取
        val wpmlNames = mutableListOf<String>()
        try {
            val entries = Collections.list(zip.entries())
            for (e in entries) {
                val n = e.name
                if (!n.startsWith("word/")) continue
                if (n.startsWith("word/header") && n.endsWith(".xml")) wpmlNames.add(n)
                else if (n.startsWith("word/footer") && n.endsWith(".xml")) wpmlNames.add(n)
                else if (n == "word/footnotes.xml") wpmlNames.add(n)
                else if (n == "word/endnotes.xml") wpmlNames.add(n)
            }
        } catch (_: Throwable) { }
        var extra = 0
        for (name in wpmlNames) {
            val xml = readEntry(zip, name) ?: continue
            extra += appendWpmlFragments(xml, sb, normAcc)
        }
        if (extra > 0) Log.d("WordCount", "docx 页眉/页脚/脚注/尾注 补充了 $extra 条文本")

        // 2) 图表 / SmartArt：DrawingML，文字在 <a:t> 中
        var aExtra = 0
        try {
            val entries = Collections.list(zip.entries())
            for (e in entries) {
                val n = e.name.lowercase()
                val isChart = n.startsWith("word/charts/chart") && n.endsWith(".xml")
                val isDiagram = n.startsWith("word/diagrams/data") && n.endsWith(".xml")
                if (!isChart && !isDiagram) continue
                val xml = readEntry(zip, e.name) ?: continue
                // <a:t> 内容不含子标签（纯文本叶子），用 [^<]* 即可
                """<a:t[^>]*>([^<]*)</a:t>""".toRegex().findAll(xml).forEach {
                    val t = decodeXml(it.groupValues[1]).trim()
                    val tNorm = t.replace(Regex("\\s+"), "")
                    if (tNorm.isNotEmpty() && normAcc.indexOf(tNorm) < 0) {
                        sb.append(t).append(' ')
                        normAcc.append(tNorm)
                        aExtra++
                    }
                }
            }
        } catch (_: Throwable) { }
        if (aExtra > 0) Log.d("WordCount", "docx 图表/SmartArt 补充了 $aExtra 条文本")
    }

    /**
     * v1.3.95：从 wordprocessingML（页眉/页脚/脚注/尾注）XML 中提取尚未出现的文本片段，
     * 去重追加到 sb，返回新增条数。解析逻辑与 appendDocxXmlText 一致（<w:p>/<w:r>/<w:t>）。
     */
    private fun appendWpmlFragments(xml: String, sb: StringBuilder, normAcc: StringBuilder): Int {
        var added = 0
        val paraRe = """(?s)<w:p[ >].*?</w:p>""".toRegex()
        val runRe = """(?s)<w:r[ >].*?</w:r>""".toRegex()
        val tRe = """(?s)<w:t[^>]*>(.*?)</w:t>""".toRegex()
        paraRe.findAll(xml).forEach { paraMatch ->
            val paraXml = paraMatch.value
            runRe.findAll(paraXml).forEach { runMatch ->
                val runXml = runMatch.value
                tRe.findAll(runXml).forEach { tMatch ->
                    val raw = decodeXml(tMatch.groupValues[1])
                    val clean = raw.replace("""<[^>]+>""", "")
                        .replace("""&[a-z]+;""".toRegex(), "")
                        .trim()
                    if (clean.isNotEmpty() && clean.any { it.code >= 32 } && normAcc.indexOf(clean.replace(Regex("\\s+"), "")) < 0) {
                        val norm = clean.replace(Regex("\\s+"), "")
                        sb.append(clean).append(' ')
                        normAcc.append(norm)
                        added++
                    }
                }
            }
        }
        return added
    }

    /**
     * v1.1.14: 基于文档实际页面尺寸的智能页数估算。
     *
     * 从 document.xml 的 <w:sectPr> 读取页面尺寸和边距，
     * 按中文 Word 默认排版参数（宋体 小四=12pt / 1.5倍行距 / A4纸）计算每页可容纳的字符数。
     *
     * 比固定系数（750/1050）更准确，因为不同页面设置（A4/Letter/B5、宽/窄边距）
     * 的每页容量差异可达 ±30%。
     */
    private fun estimatePagesFromLayout(bodyXml: String, text: String): Int {
        // 默认值（A4 / 宋体小四 / 1.5倍行距 / 标准边距）
        var pageW_twip = 11906   // A4 width in twips (210mm)
        var pageH_twip = 16838   // A4 height in twips (297mm)
        var marginTop = 1440      // 1 inch top
        var marginBottom = 1440   // 1 inch bottom
        var marginLeft = 1800     // 1.25 inch left
        var marginRight = 1800    // 1.25 inch right

        // 从 sectPr 提取实际页面尺寸
        """<w:pgSz\s+w:w="(\d+)"\s+w:h="(\d+)"""".toRegex().find(bodyXml)?.let { m ->
            pageW_twip = m.groupValues[1].toInt()
            pageH_twip = m.groupValues[2].toInt()
        }
        """<w:pgMar\s+w:top="(\d+)"\s+w:right="(\d+)"\s+w:bottom="(\d+)"\s+w:left="(\d+)"""".toRegex()
            .find(bodyXml)?.let { m ->
                marginTop = m.groupValues[1].toInt()
                marginRight = m.groupValues[2].toInt()
                marginBottom = m.groupValues[3].toInt()
                marginLeft = m.groupValues[4].toInt()
            }

        // 可用区域（twips）
        val contentW = pageW_twip - marginLeft - marginRight
        val contentH = pageH_twip - marginTop - marginBottom

        // 中文文档默认排版参数：
        // - 字号：小四 = 12pt（half-points = 24），但很多文档用 五号=10.5pt(hp=21) 或 宋体=12pt(hp=24)
        // - 行距：1.5 倍（Word 默认对中文正文）
        // - 每字符平均宽度：中文字符 ≈ 字号宽度（方正/宋体约 0.85~1.0 倍字号）
        //
        // 计算：
        //   每行字数 ≈ contentW_twips / (fontSize_pt * 20)  [twips per point = 20]
        //   每页行数 ≈ contentH_twips / (fontSize_pt * lineSpacing * 20)
        //   每页字符 ≈ 每行字数 * 每页行数 * charWidthRatio

        // 使用保守估计：五号(10.5pt) + 1.5倍行距 + 全角中文(1字号宽)
        val fontSizePt = 10.5
        val lineSpacing = 1.5
        val charWidthRatio = 1.0f // 中文字符基本占满字号宽度

        val charsPerLine = (contentW / (fontSizePt * 20.0)).toFloat()
        val linesPerPage = (contentH / (fontSizePt * lineSpacing * 20.0)).toFloat()
        val charsPerPage = (charsPerLine * linesPerPage * charWidthRatio).toInt()

        // 安全下限/上限
        val safeCharsPerPage = charsPerPage.coerceIn(800, 2500)

        val cleanCharCount = text.replace(Regex("\\s"), "").length
        return if (cleanCharCount > 0 && safeCharsPerPage > 0) {
            maxOf(1, (cleanCharCount + safeCharsPerPage - 1) / safeCharsPerPage)
        } else {
            1
        }
    }

    /**
     * 从 OOXML XML 中抽取文本（核心方法）。
     *
     * v1.0.26 修复：
     *   1. 移除 v1.0.24 的字母/CJK 过滤——旧逻辑要求 w:t 内容必须含字母或汉字，
     *      导致纯数字内容（身份证号、学年学期、分数、学分等）被丢弃，单文件可丢 ~340 字。
     *      新逻辑：保留所有可打印字符（与桌面版 Python / Word 口径一致）。
     *   2. 保持逐 <w:r> run 边界提取架构（对正常文档准确）
     *   3. 对每个 <w:t> 提取结果做 strip-tags 清洗（v1.0.24 遗留）
     */
    private fun appendDocxXmlText(
        xml: String,
        sb: StringBuilder,
        onPageBreak: (() -> Unit)? = null
    ) {
        if (xml.isBlank()) return

        // Step 1: 按段落（<w:p>）切分，保持段落结构
        val paraRe = """(?s)<w:p[ >].*?</w:p>""".toRegex()
        paraRe.findAll(xml).forEach { paraMatch ->
            val paraXml = paraMatch.value

            // 检查本段是否含分页符
            if (paraXml.contains("""w:type="page"""")) onPageBreak?.invoke()

            // Step 2: 在段落内逐个处理 <w:r> run
            val runRe = """(?s)<w:r[ >].*?</w:r>""".toRegex()
            runRe.findAll(paraXml).forEach { runMatch ->
                val runXml = runMatch.value

                // 跳过隐藏文字：run 属性中含 w:vanish 或 w:hidden
                if (VANISH_RE.containsMatchIn(runXml)) return@forEach
                if (HIDDEN_RE.containsMatchIn(runXml)) return@forEach

                // Step 3: 在 run 内提取 <w:t> 文本，然后清洗嵌套标签
                val tRe = """(?s)<w:t[^>]*>(.*?)</w:t>""".toRegex()
                tRe.findAll(runXml).forEach { tMatch ->
                    val raw = decodeXml(tMatch.groupValues[1])
                    // 清洗可能嵌套在 <w:t> 内的 XML 标签和孤立实体引用
                    val clean = raw.replace("""<[^>]+>""", "")
                        .replace("""&[a-z]+;""".toRegex(), "")

                    // v1.0.26：只要求非空白且含可打印字符（不再强制要求字母/汉字）
                    // 与桌面版 wordcount.py 的 ord(c) >= 32 判定对齐
                    if (clean.isNotBlank() && clean.any { it.code >= 32 || it == '\t' || it == '\n' || it == '\r' }) {
                        sb.append(clean)
                    }
                }
            }

            // 段落结束追加换行
            sb.append('\n')
        }
    }

    // ───────────────────────── 正则常量 ──
    /** 隐藏文字检测：<w:r> 内含 w:vanish */
    private val VANISH_RE = """<w:vanish\b""".toRegex()

    /** 隐藏文字检测：<w:r> 内含 w:hidden */
    private val HIDDEN_RE = """<w:hidden\b""".toRegex()

    // ───────────────────────── xlsx ─────────────────────────
    /**
     * xlsx 文本提取——模拟"全选 → 复制 → 粘贴到 Word"的效果：
     *   按工作表顺序，每个工作表内按行优先（从上到下、从左到右），
     *   同一行单元格间用 \t 分隔，行间用 \n 分隔。
     *
     * v1.0.25 关键修复（与桌面版 openpyxl / Word 口径对齐，实测 words=1988/fe=1780/nc=208）：
     *   1. 单元格正则支持自闭合空单元格 <c .../>——旧正则 <c ...>(.*?)</c> 会把自闭合空格
     *      与紧随其后的单元格"吞"成一个，导致列错位 + 该格 t="s" 落到内层从而共享字符串解析失效。
     *   2. 共享字符串判定改为看 <c> 开标签属性是否含 t="s"（旧代码在内层 body 里找 t="s"，
     *      而 t="s" 只存在于开标签，导致判定永远为 false，所有共享字符串被输出成索引数字）。
     *   3. Excel 日期序列号（整数 20000~60000）转中文短日期「MM月DD日」，与复制到 Word 的显示一致。
     *   4. 通过 workbook.xml + workbook.xml.rels 排除隐藏工作表（state=hidden/veryHidden），只统计可见表。
     *   5. 不再往被统计文本插入 [工作表N] 标签（旧代码会因此每个表多算约 3 个中文 + 2 个非中文词）。
     */
    /**
     * xlsx 文本提取——模拟"全选 → 复制 → 粘贴到 Word"的效果，按工作表顺序统计。
     *
     * v1.3.3 关键变更（隐藏工作表处理）：
     *   - 遍历全部工作表（含 hidden / veryHidden），逐表抽取单元格 + 该表专属绘图层（文本框/艺术字）。
     *   - 可见表：文本计入文件默认字数（与 Word「包括文本框」口径一致）。
     *   - 隐藏表：文本单独返回（OoxmlResult.hiddenSheets），默认【不计入】文件字数与合计，
     *     由 UI 以「红隐 + 勾选框」形式呈现，用户勾选后才并入合计。
     *   - 绘图层按 worksheet 的 rels 归属到具体工作表（xl/worksheets/sheetN.xml.rels →
     *     xl/drawings/drawingN.xml + vmlDrawingN.vml），避免隐藏表的文本框混入默认合计。
     */
    private fun extractXlsx(zip: ZipFile): OoxmlResult {
        val shared = readSharedStrings(zip)

        // 1) 解析 workbook.xml：按顺序取 (name, state, r:id)
        val wbXml = readEntry(zip, "xl/workbook.xml") ?: ""
        val nameAttrRe = "name=\"([^\"]*)\"".toRegex()
        val stateAttrRe = "state=\"([^\"]*)\"".toRegex()
        val ridAttrRe = "r:id=\"([^\"]*)\"".toRegex()
        val sheetRefs = mutableListOf<Triple<String, String, String>>()
        """<sheet\b[^>]*/>""".toRegex().findAll(wbXml).forEach { m ->
            val tag = m.value
            val nm = nameAttrRe.find(tag)?.groupValues?.get(1) ?: ""
            val st = stateAttrRe.find(tag)?.groupValues?.get(1) ?: "visible"
            val ri = ridAttrRe.find(tag)?.groupValues?.get(1) ?: ""
            sheetRefs.add(Triple(nm, st, ri))
        }

        // 2) 解析 rels：r:id → worksheet 文件路径
        val relsXml = readEntry(zip, "xl/_rels/workbook.xml.rels") ?: ""
        val idAttrRe = "Id=\"([^\"]*)\"".toRegex()
        val tgtAttrRe = "Target=\"([^\"]*)\"".toRegex()
        val rid2tgt = HashMap<String, String>()
        """<Relationship\b[^>]*/>""".toRegex().findAll(relsXml).forEach { m ->
            val tag = m.value
            val id = idAttrRe.find(tag)?.groupValues?.get(1)
            val tg = tgtAttrRe.find(tag)?.groupValues?.get(1)
            if (id != null && tg != null) rid2tgt[id] = tg
        }

        // 3) 构建全部工作表（含隐藏）。SheetInfo: (name, worksheetPath, hidden)
        val allSheets = mutableListOf<SheetInfo>()
        for ((nm, state, rid) in sheetRefs) {
            val tgt = rid2tgt[rid] ?: continue
            val trimmed = tgt.trimStart('/')
            val path = if (trimmed.startsWith("xl/")) trimmed else "xl/$trimmed"
            allSheets.add(SheetInfo(nm, path, state == "hidden" || state == "veryHidden"))
        }
        // 兜底：workbook.xml/rels 解析不到时，退回旧逻辑（全部 sheetN.xml，按序号，均视为可见）
        if (allSheets.isEmpty()) {
            Collections.list(zip.entries())
                .filter { it.name.matches("""xl/worksheets/sheet\d+\.xml""".toRegex()) }
                .sortedBy { """\d+""".toRegex().find(it.name)?.value?.toInt() ?: 0 }
                .mapIndexed { i, e -> SheetInfo("工作表${i + 1}", e.name, false) }
                .let { allSheets.addAll(it) }
        }

        // 支持自闭合 / 完整 元素的正则
        val rowRe = """<row\b([^>]*?)(?:/>|>(.*?)</row>)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val rowNumRe = "r=\"(\\d+)\"".toRegex()
        val cellRe = """<c\b([^>]*?)(?:/>|>(.*?)</c>)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val cellRefRe = "r=\"([A-Z]+)(\\d+)\"".toRegex()
        val vRe = """<v>(.*?)</v>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val tRe = """<t[^>]*>(.*?)</t>""".toRegex(RegexOption.DOT_MATCHES_ALL)

        val visibleNames = mutableListOf<String>()
        val visibleSb = StringBuilder()
        val hiddenSheets = mutableListOf<Pair<String, String>>() // (sheetName, text)
        // v1.3.8: drawing 路径去重——同一 drawingN.xml 可能被多个工作表 rels 同时指向
        //（复制工作表/模板另存场景），避免绘图层文字被重复计入
        val seenDrawings = mutableSetOf<String>()

        allSheets.forEachIndexed { idx, si ->
            val (siName, siPath, siHidden) = si
            val sheetName = if (siName.isNotBlank()) siName else "工作表${idx + 1}"
            val xml = readEntry(zip, siPath) ?: return@forEachIndexed

            // 按行提取单元格
            val cellsSb = StringBuilder()
            val rows = mutableListOf<Pair<Int, String>>()
            rowRe.findAll(xml).forEach { rm ->
                val body = rm.groupValues[2]
                if (body.isEmpty()) return@forEach
                val rowNum = rowNumRe.find(rm.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                rows.add(Pair(rowNum, body))
            }
            rows.sortBy { it.first }
            for ((_, rowBody) in rows) {
                val cells = mutableListOf<Pair<Int, String>>()
                cellRe.findAll(rowBody).forEach { cm ->
                    val attrs = cm.groupValues[1]
                    val inner = cm.groupValues[2]
                    val ref = cellRefRe.find(attrs) ?: return@forEach
                    val colNum = colNameToIndex(ref.groupValues[1])
                    cells.add(Pair(colNum, cellText(attrs, inner, shared, vRe, tRe)))
                }
                cells.sortBy { it.first }
                val line = StringBuilder()
                var first = true
                for ((_, txt) in cells) {
                    if (txt.isNotBlank()) {
                        if (!first) line.append('\t')
                        line.append(txt)
                        first = false
                    }
                }
                if (line.isNotEmpty()) cellsSb.append(line).append('\n')
            }

            // 本工作表专属绘图层（文本框/艺术字），按 sheet 归属（v1.3.8: 路径去重）
            val drawPath = drawingPathForSheet(zip, siPath)
            val drawText = if (drawPath != null && seenDrawings.add(drawPath)) {
                extractSheetDrawing(zip, siPath)
            } else if (drawPath == null) {
                // 无 DrawingML 时仍尝试 VML（VML 路径独立，不与 drawing 共用去重）
                extractSheetDrawing(zip, siPath)
            } else {
                "" // 已处理过的 drawing，跳过
            }

            val sheetText = cellsSb.toString() + if (drawText.isNotBlank()) drawText else ""
            if (siHidden) {
                hiddenSheets.add(Pair(sheetName, sheetText))
            } else {
                visibleNames.add(sheetName)
                visibleSb.append(sheetText).append('\n')
            }
        }

        // 兜底：极少数 workbook 解析异常的文件，仍补抽全部绘图层（保持 v1.3.2 行为）
        if (allSheets.isEmpty()) {
            val dt = extractAllDrawings(zip)
            if (dt.isNotBlank()) visibleSb.append(dt)
        }

        // v1.3.11: 计入内嵌图表文字（图表标题 / 坐标轴标题 / 图例系列名等，存于
        // xl/charts/chartN.xml 的 <a:t>）。这些是需要翻译的内容，与桌面版 wordcount.py
        // _extract_xlsx_chart_text 一致。系列名/分类标签多为公式引用(指向单元格，已在
        // 单元格中统计)，不会重复计入。图表文字独立于 drawingN.xml(形状文字)，互不重叠。
        val chartText = extractChartText(zip)
        if (chartText.isNotBlank()) visibleSb.append(chartText)

        val text = visibleSb.toString()
        val pages = max(1, visibleNames.size)
        return OoxmlResult(text, pages, "xlsx", visibleNames, hiddenSheets,
            internalTitle = extractInternalTitle(zip),
            imageCount = countMediaImages(zip))
    }

    /** 工作表信息：名称、worksheet 的 zip 内路径、是否隐藏 */
    private data class SheetInfo(val name: String, val path: String, val hidden: Boolean)

    /**
     * 提取单个工作表的绘图层（文本框/艺术字）文本，按 sheet 归属。
     * 读 worksheet 的 rels 找到对应的 drawingN.xml（DrawingML <a:t>），
     * 以及 vmlDrawingN.vml（老版 Excel/WPS 文本框，类型 /vmlDrawing）。
     * 解析异常时返回空串。
     *
     * v1.3.8 修复 VML：vmlPathForDrawing 从 drawing 自身的 rels 找 /vmlDrawing（永远为 null），
     *   改为从 worksheet rels 直接找 /vmlDrawing 关系。
     *
     * 只提取 <xdr:txBody> 内的 <a:t>（文本框/形状/标注）——这些是 drawingN.xml 里的形状文字。
     * 注意：图表的标题/轴标题等文字并不在 drawingN.xml 内，而在独立的 xl/charts/chartN.xml 中，
     *   由 extractChartText() 单独抽取并计入（这些也是需要翻译的内容，与桌面版一致）。
     * 两者文件不同、互不重叠，不会重复计数。
     */
    private fun extractSheetDrawing(zip: ZipFile, sheetPath: String): String {
        val sb = StringBuilder()
        // ① DrawingML: 只取 <xdr:txBody> 内的 <a:t>（文本框/形状），排除 <c:tx>（图表）
        val drawingPath = drawingPathForSheet(zip, sheetPath)
        if (drawingPath != null) {
            try {
                val xml = readEntry(zip, drawingPath) ?: ""
                // 先按 txBody 分块（与桌面版 wordcount.py _extract_xlsx_shapes_text 一致），
                // 再在每块内取 <a:t>，避免把图表文字算进去
                """<xdr:txBody[^>]*>(.*?)</xdr:txBody>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { txBody ->
                    """<a:t[^>]*>(.*?)</a:t>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(txBody.groupValues[1]).forEach {
                        sb.append(decodeXml(it.groupValues[1])).append('\n')
                    }
                }
            } catch (_: Throwable) {}
        }
        // ② VML 文本框：从 worksheet rels 直接找 /vmlDrawing
        val vmlPath = vmlPathForSheet(zip, sheetPath)
        if (vmlPath != null) {
            try {
                val vxml = readEntry(zip, vmlPath) ?: return sb.toString()
                """<w:txbxContent[^>]*>(.*?)</w:txbxContent>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(vxml).forEach { block ->
                    """<w:t[^>]*>(.*?)</w:t>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(block.groupValues[1]).forEach {
                        sb.append(decodeXml(it.groupValues[1])).append('\n')
                    }
                }
                """<v:textbox[^>]*>(.*?)</v:textbox>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(vxml).forEach { block ->
                    """<text[^>]*>(.*?)</text>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(block.groupValues[1]).forEach {
                        sb.append(decodeXml(it.groupValues[1])).append('\n')
                    }
                }
            } catch (_: Throwable) {}
        }
        return sb.toString()
    }

    /**
     * 提取内嵌图表文字（图表标题 / 坐标轴标题 / 图例系列名等）。
     * 图表文字写在独立的 xl/charts/chartN.xml 里，以 <a:t> 字面字符串存储；
     * 与形状文字(drawingN.xml 的 <xdr:txBody>)分属不同文件，互不重叠。
     * 只抽 <a:t>：系列名/分类标签/数据标签多数只存公式引用(<strRef>/<numRef>
     * 指向单元格)，其字面文字已在单元格统计，不会重复计入。
     * 与桌面版 wordcount.py _extract_xlsx_chart_text 口径一致（需要翻译的内容计入）。
     */
    private fun extractChartText(zip: ZipFile): String {
        val sb = StringBuilder()
        try {
            val entries = Collections.list(zip.entries())
            for (e in entries) {
                if (e.name.startsWith("xl/charts/chart") && e.name.endsWith(".xml")) {
                    val xml = readEntry(zip, e.name) ?: continue
                    // <a:t> 允许带属性(如 xml:space="preserve")，用 [^>]* 容错；
                    // <a:t> 为纯文本叶子节点，内容不含子标签，用 [^<]* 即可（无需 DOT_MATCHES_ALL）
                    """<a:t[^>]*>([^<]*)</a:t>""".toRegex().findAll(xml).forEach {
                        val t = decodeXml(it.groupValues[1]).trim()
                        if (t.isNotEmpty()) sb.append(t).append('\n')
                    }
                }
            }
        } catch (_: Throwable) {}
        return sb.toString()
    }

    /** 从 worksheet 的 rels 找到其 drawing 关系目标路径（zip 内绝对路径）。 */
    private fun drawingPathForSheet(zip: ZipFile, sheetPath: String): String? {
        val dir = sheetPath.substringBeforeLast('/')
        val name = sheetPath.substringAfterLast('/')
        val relsXml = readEntry(zip, "$dir/_rels/$name.rels") ?: return null
        val tgtAttrRe = "Target=\"([^\"]*)\"".toRegex()
        val typeAttrRe = "Type=\"([^\"]*)\"".toRegex()
        var drawingTgt: String? = null
        """<Relationship\b[^>]*/>""".toRegex().findAll(relsXml).forEach { m ->
            val tag = m.value
            val type = typeAttrRe.find(tag)?.groupValues?.get(1) ?: ""
            if (type.endsWith("/drawing") || type.endsWith("/drawingml")) {
                drawingTgt = tgtAttrRe.find(tag)?.groupValues?.get(1)
            }
        }
        return drawingTgt?.let { resolveRelPath(dir, it) }
    }

    /**
     * 从 worksheet 的 rels 找到其 vmlDrawing 目标路径（老版 Excel/WPS 文本框）。
     * v1.3.8 修复：此前错误地从 drawing 自身的 rels（xl/drawings/_rels/drawingN.xml.rels）
     * 查找 /vmlDrawing，但该文件只有图片关系，永远返回 null。
     * 现改为从 worksheet 的 rels（xl/worksheets/_rels/sheetN.xml.rels）直接查找，与 drawingPathForSheet 对称。
     */
    private fun vmlPathForSheet(zip: ZipFile, sheetPath: String): String? {
        val dir = sheetPath.substringBeforeLast('/')
        val name = sheetPath.substringAfterLast('/')
        val relsXml = readEntry(zip, "$dir/_rels/$name.rels") ?: return null
        val tgtAttrRe = "Target=\"([^\"]*)\"".toRegex()
        val typeAttrRe = "Type=\"([^\"]*)\"".toRegex()
        var vmlTgt: String? = null
        """<Relationship\b[^>]*/>""".toRegex().findAll(relsXml).forEach { m ->
            val tag = m.value
            val type = typeAttrRe.find(tag)?.groupValues?.get(1) ?: ""
            if (type.endsWith("/vmlDrawing")) {
                vmlTgt = tgtAttrRe.find(tag)?.groupValues?.get(1)
            }
        }
        return vmlTgt?.let { resolveRelPath(dir, it) }
    }

    /** 把相对路径（可能含 ../）解析为 zip 内绝对路径。 */
    private fun resolveRelPath(baseDir: String, rel: String): String {
        val out = mutableListOf<String>()
        for (p in (baseDir.split('/') + rel.split('/'))) {
            when (p) {
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.lastIndex) else out.add(p)
                ".", "" -> {} // 跳过
                else -> out.add(p)
            }
        }
        return out.joinToString("/")
    }

    /**
     * v1.3.2 遗留（兜底用）：抽取全部绘图层文本（所有 drawingN.xml 的 <a:t> + vmlDrawing 文本框）。
     * 仅在 workbook 解析异常、无法按 sheet 归属时才调用，避免隐藏表文本框污染默认合计。
     */
    private fun extractAllDrawings(zip: ZipFile): String {
        val sb = StringBuilder()
        return try {
            val entries = Collections.list(zip.entries())
            for (e in entries) {
                if (e.name.matches("""xl/drawings/drawing\d+\.xml""".toRegex())) {
                    val xml = readEntry(zip, e.name) ?: continue
                    """<a:t[^>]*>(.*?)</a:t>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach {
                        sb.append(decodeXml(it.groupValues[1])).append('\n')
                    }
                }
            }
            for (e in entries) {
                if (e.name.matches("""xl/drawings/vmlDrawing\d+\.vml""".toRegex())) {
                    val xml = readEntry(zip, e.name) ?: continue
                    """<w:txbxContent[^>]*>(.*?)</w:txbxContent>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { block ->
                        """<w:t[^>]*>(.*?)</w:t>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(block.groupValues[1]).forEach {
                            sb.append(decodeXml(it.groupValues[1])).append('\n')
                        }
                    }
                    """<v:textbox[^>]*>(.*?)</v:textbox>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { block ->
                        """<text[^>]*>(.*?)</text>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(block.groupValues[1]).forEach {
                            sb.append(decodeXml(it.groupValues[1])).append('\n')
                        }
                    }
                }
            }
            sb.toString()
        } catch (_: Throwable) {
            sb.toString()
        }
    }

    /**
     * 单元格取值：处理共享字符串 / inlineStr / 公式字符串 / 数字 / Excel 日期序列号。
     * 注意：类型判定必须看 <c> 开标签属性 attrs（t="s"/"str"/"inlineStr"），不能在 inner 里找。
     */
    private fun cellText(
        attrs: String,
        inner: String,
        shared: List<String>,
        vRe: Regex,
        tRe: Regex
    ): String {
        if (inner.isEmpty()) return ""
        // inlineStr：<is>...<t>..</t></is>（v1.3.0修复：用 findAll 拼接所有段，与 readSharedStrings 一致）
        if (attrs.contains("t=\"inlineStr\"")) {
            val sb = StringBuilder()
            tRe.findAll(inner).forEach { sb.append(decodeXml(it.groupValues[1])) }
            return sb.toString()
        }
        val v = vRe.find(inner)?.groupValues?.get(1)?.trim() ?: ""
        // 共享字符串：t="s"，<v> 是索引
        if (attrs.contains("t=\"s\"") && v.isNotEmpty()) {
            return v.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
        }
        // 公式字符串结果：t="str"
        if (attrs.contains("t=\"str\"")) {
            return decodeXml(v)
        }
        // 数字：识别 Excel 日期序列号（整数 20000~60000）→ 中文短日期，与 Word 显示一致
        if (v.isNotEmpty()) {
            val d = v.toDoubleOrNull()
            if (d != null && d == Math.floor(d) && d > 20000 && d < 60000) {
                return try {
                    val date = java.time.LocalDate.of(1899, 12, 30).plusDays(d.toLong())
                    String.format("%02d月%02d日", date.monthValue, date.dayOfMonth)
                } catch (_: Throwable) {
                    v
                }
            }
            return v
        }
        return ""
    }

    /** Excel 列名 → 列索引（A=0, B=1, ..., Z=25, AA=26, ...） */
    private fun colNameToIndex(name: String): Int {
        var idx = 0
        for (c in name) {
            idx = idx * 26 + (c.uppercaseChar().code - 'A'.code + 1)
        }
        return idx - 1
    }

    // ───────────────────────── pptx ─────────────────────────
    /**
     * 提取 pptx 文本（幻灯片 + 备注）+ 图片计数。
     *
     * v1.3.32 重大改进：
     *   1. 按段落(<a:p>)分组提取，同段落内<a:t>直接拼接不插空格，
     *      避免英文/数字 token 被空格拆成多个 nc 词（此前 nc 偏高 2~10 倍）
     *   2. 提取备注文本(ppt/notesSlides/)，作为独立明细供 UI 展开勾选
     *   3. 统计嵌入图片数量(ppt/media/)
     *   4. 清洗提取文本中的残留 XML 标签（某些 PPTX 的 <a:t> 内含 XML 片段）
     */
    private fun extractPptx(zip: ZipFile): OoxmlResult {
        // ── 幻灯片 ──
        val slideEntries = Collections.list(zip.entries())
            .filter { it.name.matches("""ppt/slides/slide\d+\.xml""".toRegex()) }
            .sortedBy { """\d+""".toRegex().find(it.name)?.value?.toInt() ?: 0 }

        val tRe = """<a:t[^>]*>(.*?)</a:t>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val pRe = """<a:p[^>]*>(.*?)</a:p>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        // 清洗残留 XML 标签（某些 PPTX 的 <a:t> 内容包含 <a:rPr> 等标签片段）
        val xmlTagRe = """<[^>]+>""".toRegex()
        // v1.3.35: 占位符默认文字过滤（与电脑版 python-pptx 对齐——python-pptx 不返回占位符默认文本）
        // 中英文常见 PPT 占位符文本（大小写不敏感匹配）
        val placeholderPatterns = listOf(
            "click to add title", "click to add subtitle", "click to add text",
            "click to add slide title", "click to add slide subtitle",
            "click to add notes",
            "点击此处添加标题", "点击此处添加副标题", "点击此处添加文本",
            "点击此处添加幻灯片标题", "点击此处添加幻灯片副标题",
            "点击此处添加备注",
            "date", "footer", "slide number", "页脚", "页码",
            " presenter name ", " company name ", "作者", "单位"
        ).map { Regex("^\\s*${Regex.escape(it)}\\s*$", RegexOption.IGNORE_CASE) }

        /** 判断一段文本是否为占位符默认文字 */
        fun isPlaceholderText(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.length <= 15) {  // 占位符通常很短
                for (p in placeholderPatterns) {
                    if (p.matches(trimmed)) return true
                }
            }
            return false
        }

        val sb = StringBuilder()
        slideEntries.forEachIndexed { idx, entry ->
            val xml = readEntry(zip, entry.name) ?: return@forEachIndexed
            // v1.3.35: 不再加 [幻灯片N] 标记（此前这些标记被计入字数，导致偏高 ~3fe/页）
            // 按段落提取：同段落内的 <a:t> 直接拼接，段落间用空格分隔
            val paras = pRe.findAll(xml)
            for (para in paras) {
                val pText = StringBuilder()
                tRe.findAll(para.groupValues[1]).forEach { match ->
                    pText.append(decodeXml(match.groupValues[1]))
                }
                val line = pText.toString().trim()
                if (line.isNotEmpty()) {
                    // 二次清洗：去除可能泄漏的 XML 标签
                    val clean = xmlTagRe.replace(line, "")
                    // v1.3.35: 跳过占位符默认文字（与电脑版 python-pptx 对齐）
                    if (clean.isNotEmpty() && !isPlaceholderText(clean)) sb.append(clean).append(' ')
                }
            }
            sb.append('\n')
        }

        // ── 备注幻灯片 ──
        // v1.3.40: 只从备注正文框（<p:sp> 内 <p:cNvPr type="body">）提取文本。
        // 对齐电脑版 python-pptx 的 ns.notes_text_frame——它只返回备注正文形状的文本，
        // 不会把 notesSlide 里其他形状（幻灯片缩略图/日期/页脚占位符等）的文本混入。
        // v1.38~v1.39 用 isPlaceholderText 过滤全量 <a:t> 仍有漏网之鱼（某些占位符文本
        // 不在已知列表中），彻底修复方式是源头只读 body 形状。
        val noteEntries = Collections.list(zip.entries())
            .filter { it.name.matches("""ppt/notesSlides/notesSlide\d+\.xml""".toRegex()) }
            .sortedBy { """\d+""".toRegex().find(it.name)?.value?.toInt() ?: 0 }

        val notesList = mutableListOf<Pair<String, String>>()
        // 匹配单个 <p:sp>...</p:sp> 形状块（PPTX 中形状互不嵌套，非贪婪即可）
        val spRe = """<p:sp[\s\S]*?</p:sp>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        // 判断形状是否为备注正文框
        val bodyTypeRe = """<p:cNvPr[^>]*\btype\s*=\s*"body"""".toRegex(RegexOption.IGNORE_CASE)

        noteEntries.forEachIndexed { idx, entry ->
            val xml = readEntry(zip, entry.name) ?: return@forEachIndexed
            val nsb = StringBuilder()
            // 只遍历 type="body" 的形状（备注正文框）
            for (spMatch in spRe.findAll(xml)) {
                val spXml = spMatch.value
                if (!bodyTypeRe.containsMatchIn(spXml)) continue
                // 在正文框内按段落提取
                val paras = pRe.findAll(spXml)
                for (para in paras) {
                    val pText = StringBuilder()
                    tRe.findAll(para.groupValues[1]).forEach { match ->
                        pText.append(decodeXml(match.groupValues[1]))
                    }
                    val line = pText.toString().trim()
                    if (line.isNotEmpty()) {
                        val clean = xmlTagRe.replace(line, "")
                        if (clean.isNotEmpty() && !isPlaceholderText(clean)) nsb.append(clean).append(' ')
                    }
                }
            }
            // 仅当备注有实际文本时才计入
            val notesText = nsb.toString().trim()
            if (notesText.isNotEmpty()) {
                notesList.add("备注${idx + 1}" to notesText)
            }
        }

        // ── 图片计数（仅统计媒体文件，排除 .xml/.rels）──
        val imageCount = countMediaImages(zip)

        // ── v1.3.36: 内嵌图表 + SmartArt 文本 ──
        // 这些是需要翻译的内容，与桌面版 wordcount.py 一致（之前只读 slideN.xml 漏掉）。
        // 图表文字在 ppt/charts/chartN.xml；SmartArt 文字在 ppt/diagrams/dataN.xml。
        // 两者与 slideN.xml 不重叠（slide 里只是引用），不会重复计入。
        val embeddedText = extractPptEmbeddedText(zip)

        val text = sb.toString() + if (embeddedText.isNotBlank()) "\n$embeddedText" else ""
        val pages = max(1, slideEntries.size)
        return OoxmlResult(text, pages, "pptx",
            notesSlides = notesList,
            imageCount = imageCount,
            internalTitle = extractInternalTitle(zip))
    }

    /**
     * v1.3.36: 提取 PPTX 内嵌图表(ppt/charts/chartN.xml)与 SmartArt(ppt/diagrams/dataN.xml)
     * 的文字。这些是翻译内容，桌面版 python-pptx 也会遍历 shape.has_chart 计入图表标题/轴标题。
     * SmartArt 在 python-pptx 下无法直接读取，这里从 diagrams/dataN.xml 补全。
     * 只抽 <a:t>，与 slideN.xml 不重叠。
     */
    private fun extractPptEmbeddedText(zip: ZipFile): String {
        val sb = StringBuilder()
        try {
            val entries = Collections.list(zip.entries())
            for (e in entries) {
                val nm = e.name.lowercase()
                val isChart = nm.startsWith("ppt/charts/chart") && nm.endsWith(".xml")
                val isDiagram = nm.startsWith("ppt/diagrams/data") && nm.endsWith(".xml")
                if (!isChart && !isDiagram) continue
                val xml = readEntry(zip, e.name) ?: continue
                """<a:t[^>]*>(.*?)</a:t>""".toRegex(RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach {
                    val t = decodeXml(it.groupValues[1]).trim()
                    if (t.isNotEmpty()) sb.append(t).append('\n')
                }
            }
        } catch (_: Throwable) {}
        return sb.toString()
    }

    // ───────────────────────── 工具 ─────────────────────────
    /**
     * 统计 OOXML 包内嵌入的图片数量（ppt/media、word/media、xl/media，排除 .xml/.rels）。
     * 这些图片是无法被文字抽取的"不可识别内容"，由 UI「导出不可识别内容」按钮导出为 PDF。
     */
    private fun countMediaImages(zip: ZipFile): Int {
        var n = 0
        val e = zip.entries()
        while (e.hasMoreElements()) {
            val name = e.nextElement().name.lowercase()
            if (("/media/" !in name) && !name.startsWith("media/")) continue
            if (name.endsWith(".xml") || name.endsWith(".rels") || name.endsWith("/")) continue
            n++
        }
        return n
    }

    /** 从 docProps/core.xml 提取 <dc:title> 作为文件内部标题（用于修复 URI 无法获取真实文件名的问题）。
     *  v1.3.34: 过滤 WPS/Office 默认模板标题（如"PowerPoint Presentation"、"PowerPoint 演示文稿"等），
     *  这些不是真实文件名，使用它们替换显示名反而更差。
     *  v1.3.35: 过滤后若为空，fallback 取第一张幻灯片首个非占位符文本作为标题。 */
    private fun extractInternalTitle(zip: ZipFile): String {
        val xml = readEntry(zip, "docProps/core.xml") ?: return ""
        // Dublin Core title: <dc:title>...</dc:title> or <cp:coreProperties> namespace variants
        val dcTitleRe = """<dc:title[^>]*>(.*?)</dc:title>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val m = dcTitleRe.find(xml) ?: return ""
        val title = decodeXml(m.groupValues[1]).trim()
        if (title.length < 2 || title.length > 200) return ""
        // 过滤 Office/WPS 默认模板标题——这些不是真实文件名
        val lower = title.lowercase()
        val defaultTitles = setOf(
            "powerpoint presentation", "powerpoint 演示文稿",
            "word document", "word 文档",
            "excel worksheet", "excel 工作表",
            "新建 microsoft word 文档", "新建 microsoft excel 工作表",
            "新建 microsoft powerpoint 演示文稿",
            "新建 xlsx 工作表", "新建 xls 工作表", "新建 docx 文档",
            "演示文稿", "工作簿1", "工作簿2", "工作簿3",
            "presentation1", "workbook1", "document1"
        )
        if (lower in defaultTitles) {
            // v1.3.35: fallback 到第一张幻灯片的第一个有意义文本
            return extractFallbackTitle(zip)
        }
        return title
    }

    /** 当 dc:title 为默认模板名时，尝试从第一张幻灯片提取标题文本作为 fallback。 */
    private fun extractFallbackTitle(zip: ZipFile): String {
        val tRe = """<a:t[^>]*>(.*?)</a:t>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        // 占位符模式（与 extractPptx 内一致）
        val placeholders = setOf(
            "click to add title", "click to add subtitle", "click to add text",
            "点击此处添加标题", "点击此处添加副标题", "点击此处添加文本",
            "date", "footer", "页脚"
        ).map { it.lowercase() }
        // 尝试读取 slide1.xml
        val slideXml = readEntry(zip, "ppt/slides/slide1.xml") ?: return ""
        val texts = mutableListOf<String>()
        tRe.findAll(slideXml).forEach { texts.add(decodeXml(it.groupValues[1]).trim()) }
        for (t in texts) {
            if (t.length >= 2 && t.length <= 100 && t.lowercase() !in placeholders) {
                return t
            }
        }
        return ""
    }

    private fun readSharedStrings(zip: ZipFile): List<String> {
        val xml = readEntry(zip, "xl/sharedStrings.xml") ?: return emptyList()
        val out = mutableListOf<String>()
        // 支持自闭合 <si/>（空字符串），保持索引对齐
        val siRe = """<si\b[^>]*?(?:/>|>(.*?)</si>)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val tRe = """<t[^>]*>(.*?)</t>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        siRe.findAll(xml).forEach { siMatch ->
            val inner = siMatch.groupValues[1]
            val sb = StringBuilder()
            tRe.findAll(inner).forEach { sb.append(decodeXml(it.groupValues[1])) }
            out.add(sb.toString())
        }
        return out
    }

    private fun readEntry(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        zip.getInputStream(entry).use { `is` ->
            val bytes = `is`.readBytes()
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    // v1.2.3: 从 docProps/app.xml 提取整数型统计字段（Pages/Words/Characters 等）
    private fun extractAppInt(xml: String, tag: String): Int {
        if (xml.isBlank()) return 0
        val m = """<$tag>(\d+)</$tag>""".toRegex().find(xml)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun decodeXml(s: String): String {
        return s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("""&#x([0-9a-fA-F]+);""".toRegex()) { m -> m.groupValues[1].toInt(16).toChar().toString() }
            .replace("""&#(\d+);""".toRegex()) { m -> m.groupValues[1].toInt().toChar().toString() }
    }
}
