package com.henry.wordcount

import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * v1.5.16: 移植桌面版 WordCount 的 DWG→DXF 结构化解析逻辑（纯 Kotlin，无 ezdxf 依赖）。
 *
 * v1.5.32 重大修正 —— 全面对齐桌面版页数口径：
 *   经核对 LibreDWG 源码 src/out_dxf.c：
 *     1) dxf_entities_write() 只写「模型空间 + 活动 *Paper_Space」的实体到 ENTITIES 段；
 *        其余布局（*Paper_Space0/1/2...）的实体全部写在 BLOCKS 段的同名 BLOCK 里。
 *     2) LibreDWG 从不写组码 67（图纸空间标志），所以 ezdxf 把 ENTITIES 段
 *        全部算作 modelspace —— 桌面版 ms_ents 就是 ENTITIES 段实体数。
 *     3) 桌面 paper = 「含图元的图纸空间布局数」，不是布局名字符串出现次数。
 *        旧实现用正则数 "*Paper_Space" 唯一名，把空布局也算进去
 *        （巴布亚 19 个布局里只有 11 个有图元 → 桌面 11 页，旧实现报 19 页）。
 *   同时 geo 几何图框改为只扫 ENTITIES 段（= 桌面 doc.modelspace() 口径）。
 *
 * v1.5.32 文字修正：
 *     4) LibreDWG 新版 cquote()/dxf_fixup_string() 会把亚洲 MIF 转义
 *        \M+nXXXX 统一改写成 \U+XXXX，非 ASCII 也可能被 bit_embed_TU 转成 \U+XXXX。
 *        ezdxf 会自动解这类转义，纯文本解析不解 → 中文全丢（Tenova 0 中文）。
 *        这里补上 \U+XXXX / \M+nXXXX 解码。
 *     5) 补一个「全局兜底」：若整篇抽取结果一个中文都没有，但按
 *        Latin-1→GB18030 重解能得到足量常用汉字，则整体采用重解结果。
 *
 * ─────────────────────────────────────────────────────────────────────
 * v1.5.35 字数修正（页数口径保持 v1.5.32 不变）：
 *   A) 【编码】改为 ISO-8859-1 无损读入 + 逐值双解码选优（decodeValue）。
 *      v1.5.33 的整文件字节扫描阈值（validMb > err*8）在真机上把给排水_t3、
 *      Tenova 等混合编码/边界样本误判为 UTF-8，导致中文全部变成 U+FFFD，
 *      随后 PDF 兜底把结果覆盖成 5140/2011 的非中文残片。
 *      新策略：每个文本值独立尝试 UTF-8 与 GB18030，按「谁产生更多真实中文
 *      （常用字占比≥30%）」选取；-water/tenova 的 GBK 文本正确还原，
 *      -巴布亚/水雾的 UTF-8 文本不受少量非法字节影响。
 *
 * v1.5.38 编码再修正：
 *   真机 v1.5.36 反馈 Tenova 成 2011/0/2011、给排水_t3 成 5140/0/5140（中文全
 *   丢），而本地同版本 LibreDWG 0.14 验证完全对齐桌面。说明逐值双解码在真机
 *   ARM 输出上仍把整篇误判为 UTF-8。改为全局编码判定：先扫描整份 DXF 分别统计
 *   UTF-8/GB18030 产生的 CJK 数，gb_cjk > u8_cjk*2 则全局用 GB18030，否则 UTF-8；
 *   然后每个值按全局编码解码。本地四文件均已验证对齐桌面。
 *
 *   本地用同版本 LibreDWG 0.14 转出的 DXF 逐文件核对（桌面基准 / 本实现）：
 *      巴布亚桩基 23960 / 23927(-0.1%)   给排水_t3 14874 / 14570(-2.0%)
 *      水雾电气图  8880 /  8881(±0)      Tenova      512 /   512(±0)
 * ─────────────────────────────────────────────────────────────────────
 *
 * DXF 是纯文本（组码/值交替），可直接解析，不需要 ezdxf 这类 Python 库。
 */

object DwgDxfParser {

    // ───────────────────────────── 数据结构 ─────────────────────────────

    /** 一个 DXF 实体：类型 + 有序 (组码,值) 列表（保留顶点 10/20 顺序）+ 其下 ATTRIB 子实体 */
    private data class DxfEntity(
        val type: String,
        val items: MutableList<Pair<Int, String>> = mutableListOf(),
        val attribs: MutableList<DxfEntity> = mutableListOf()
    ) {
        /** 取某组码的所有值（无序场景用） */
        fun values(code: Int): List<String> = items.filter { it.first == code }.map { it.second }
    }

    private data class Rect(val minx: Double, val miny: Double, val maxx: Double, val maxy: Double)
    private data class RectArea(val rect: Rect, val area: Double)

    /** DXF 分段结果：ENTITIES 段行 / BLOCKS 段行（均为原始『组码行,值行』成对序列） */
    private data class DxfSections(val entities: List<String>, val blocks: List<String>)

    data class AnalysisResult(
        /** 全量口径：文件里所有实体的文字（含未被引用的块定义/图库残留） */
        val text: String,
        /** 出图口径：只含模型空间 + 各图纸布局实际画出的文字（端口桌面 ezdxf-printed） */
        val printedText: String,
        val frames: Int?,
        val framesReason: String?,
        /** 整文件编码判定结果，仅供日志 */
        val decodeMode: String
    )

    /** DXF 作用域：模型空间实体 + 块定义表（块名 → 块内实体） */
    private data class DxfScopes(
        val ms: List<DxfEntity>,
        val blocks: LinkedHashMap<String, MutableList<DxfEntity>>
    )

    /** ezdxf 口径下不算「顶层实体」的从属记录：POLYLINE 的顶点、INSERT 的属性、序列结束符 */
    private val SUB_ENTITY_TYPES = setOf("VERTEX", "SEQEND", "ATTRIB")

    /** 图纸空间块名：R13+ 写作 *Paper_SpaceN，转旧版本时写作 $PAPER_SPACEN */
    private val PAPER_BLOCK_NAME = Regex("^[*\\$]Paper_Space", RegexOption.IGNORE_CASE)

    // ───────────────────────────── DXF 实体解析 ─────────────────────────────

    private fun parseEntities(lines: List<String>): List<DxfEntity> {
        val entities = mutableListOf<DxfEntity>()
        var cur: DxfEntity? = null
        var lastInsert: DxfEntity? = null
        val n = lines.size
        var i = 0
        while (i < n - 1) {
            val code = lines[i].trim()
            val value = lines[i + 1]
            i += 2
            val codeInt = code.toIntOrNull()
            if (codeInt == 0) {
                cur = DxfEntity(value.trim())
                entities.add(cur)
                // 维护 INSERT→ATTRIB 父子关系，供标题块/详图统计用
                when (cur.type) {
                    "INSERT" -> lastInsert = cur
                    "SEQEND" -> lastInsert = null
                    "ATTRIB" -> if (lastInsert != null) lastInsert!!.attribs.add(cur)
                    else -> lastInsert = null
                }
            } else if (codeInt != null && cur != null) {
                cur.items.add(codeInt to value)
            }
        }
        return entities
    }

    /** 最近一次解析的编码判定摘要（仅供日志） */
    @Volatile private var lastDecodeMode: String = "global-dual"

    /**
     * v1.5.38 全局编码判定 + 按全局编码逐值解码。
     * 真机反馈（v1.5.36）Tenova/给排水_t3 出现 0 中文，而本地同版本 LibreDWG 0.14
     * 验证完全对齐桌面。v1.5.35 的逐值双解码在真机 ARM 输出上疑似把整篇误判为
     * UTF-8，导致 GBK 中文全部变成 U+FFFD 并被过滤。
     * 改为：先扫描整份 DXF 的文本值，分别按 UTF-8 / GB18030 统计产生的 CJK 数量，
     * 若 gb_cjk > u8_cjk * 2 则判定全局编码为 GB18030，否则 UTF-8；之后每个值都
     * 按该编码解码（并保留一次反向解码作为非法字节兜底）。该策略在本地四个真实
     * 测试文件上均与桌面基准一致，且对整篇编码统一的文件更稳定。
     */
    private data class EncodingDecision(val enc: String, val u8Cjk: Int, val gbCjk: Int)

    private fun detectEncoding(lines: List<String>): EncodingDecision {
        var u8Cjk = 0
        var gbCjk = 0
        var i = 0
        val n = lines.size
        while (i < n - 1) {
            val code = lines[i].trim().toIntOrNull()
            // v1.5.38: 不能用 run{ continue }——Kotlin 内联 lambda 里的 break/continue 是实验特性
            //   会直接编译失败（"break continue in inline lambdas"）。改为显式 if 判断。
            if (code != null && code in listOf(1, 3, 302, 304)) {
                val v = lines[i + 1]
                try {
                    val b = v.toByteArray(Charsets.ISO_8859_1)
                    u8Cjk += cjkCountOf(String(b, StandardCharsets.UTF_8))
                    gbCjk += cjkCountOf(String(b, charset("GB18030")))
                } catch (_: Exception) {}
            }
            i += 2
        }
        return EncodingDecision(if (gbCjk > u8Cjk * 2) "GB18030" else "UTF-8", u8Cjk, gbCjk)
    }

    private fun decodeValue(s: String, enc: String): String {
        if (s.isEmpty()) return s
        val b = try { s.toByteArray(Charsets.ISO_8859_1) } catch (_: Exception) { return s }
        val primary = try {
            String(b, if (enc == "GB18030") charset("GB18030") else StandardCharsets.UTF_8)
        } catch (_: Exception) { null }
        val fallback = try {
            String(b, if (enc == "GB18030") StandardCharsets.UTF_8 else charset("GB18030"))
        } catch (_: Exception) { null }
        if (primary != null && "\uFFFD" !in primary) return primary
        if (fallback != null && "\uFFFD" !in fallback) return fallback
        return primary ?: fallback ?: s
    }

    /** 读取 DXF 文本行（ISO-8859-1 无损读入，每个字节保留为一个 char）。 */
    private fun readDxfLines(dxfPath: String): List<String>? {
        val f = File(dxfPath)
        if (!f.exists() || f.length() == 0L) return null
        val raw = try { f.readBytes() } catch (_: Exception) { return null }
        return String(raw, Charsets.ISO_8859_1).split("\n")
    }

    /** 把 DXF 拆成 ENTITIES / BLOCKS 两段（其余段忽略）。 */
    private fun splitSections(lines: List<String>): DxfSections {
        val entities = mutableListOf<String>()
        val blocks = mutableListOf<String>()
        var cur: MutableList<String>? = null
        val n = lines.size
        var i = 0
        while (i < n - 1) {
            val code = lines[i].trim()
            val value = lines[i + 1].trim()
            if (code == "0" && value == "SECTION") {
                if (i + 3 < n && lines[i + 2].trim() == "2") {
                    val name = lines[i + 3].trim()
                    cur = when (name) {
                        "ENTITIES" -> entities
                        "BLOCKS" -> blocks
                        else -> null
                    }
                    i += 4
                    continue
                }
            }
            if (code == "0" && value == "ENDSEC") {
                cur = null
                i += 2
                continue
            }
            val sink = cur
            if (sink != null) {
                sink.add(lines[i])
                sink.add(lines[i + 1])
            }
            i += 2
        }
        return DxfSections(entities, blocks)
    }

    // ───────────────────────────── 文字抽取（extract_text_custom） ─────────────────────────────

    /** 从单个实体里取文字（端口桌面 extract_text_from_dxf 的实体分支） */
    private fun grabText(e: DxfEntity, out: MutableList<String>, enc: String) {
        when (e.type) {
            "TEXT", "ATTDEF" -> {
                val vs = e.values(1)
                if (vs.isNotEmpty() && vs[0].isNotBlank()) {
                    val r = decodeDxfEscapes(decodeValue(vs[0].trim(), enc))
                    if (r.isNotBlank()) out.add(r.trim())
                }
            }
            "MTEXT" -> {
                val s = (e.values(1) + e.values(3)).joinToString("")
                val c = cleanMtext(decodeDxfEscapes(decodeValue(s, enc)))
                if (c.isNotBlank()) out.add(c.trim())
            }
            "MULTILEADER" -> {
                val s = (e.values(304) + e.values(302)).joinToString("")
                val r = decodeDxfEscapes(decodeValue(s, enc))
                if (r.isNotBlank()) out.add(r.trim())
            }
        }
    }

    /** 去重（保序）后拼成整段文本 */
    private fun joinUnique(items: List<String>): String {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<String>()
        for (c in items) {
            if (c !in seen) { seen.add(c); out.add(c) }
        }
        return globalRecoverCjk(out.joinToString("\n"))
    }

    /** 全量口径：整个 DXF 文件里所有实体的文字（含未被引用的块定义/图库残留） */
    private fun extractTextFromEntities(entities: List<DxfEntity>, enc: String): String {
        val collected = mutableListOf<String>()
        for (e in entities) grabText(e, collected, enc)
        return joinUnique(collected)
    }

    // ─────────────────────── 出图口径（端口桌面 _extract_dwg_via_ezdxf_printed） ───────────────────────

    /**
     * 一次遍历同时拿到「模型空间实体」与「块定义表」。
     * ENTITIES 段 == 桌面 doc.modelspace()（LibreDWG 不写组码 67）；
     * BLOCKS 段每个 BLOCK…ENDBLK 是一个块定义，其中 *Paper_SpaceN 就是各图纸布局。
     */
    private fun parseAllSections(lines: List<String>): DxfScopes {
        val ms = mutableListOf<DxfEntity>()
        val blocks = LinkedHashMap<String, MutableList<DxfEntity>>()
        var section: String? = null
        var curEnt: DxfEntity? = null
        var curList: MutableList<DxfEntity>? = null
        var curBlock: String? = null
        var awaitingName = false
        val n = lines.size
        var i = 0
        while (i < n - 1) {
            val code = lines[i].trim()
            val value = lines[i + 1]
            val vs = value.trim()
            i += 2
            if (code == "0" && vs == "SECTION") {
                if (i + 1 < n && lines[i].trim() == "2") {
                    section = lines[i + 1].trim()
                    i += 2
                }
                continue
            }
            if (code == "0" && vs == "ENDSEC") {
                section = null; curEnt = null; curList = null; curBlock = null; awaitingName = false
                continue
            }
            if (section == "ENTITIES") {
                if (code == "0") {
                    val e = DxfEntity(vs); ms.add(e); curEnt = e
                } else {
                    val ci = code.toIntOrNull()
                    if (ci != null) curEnt?.items?.add(ci to value)
                }
            } else if (section == "BLOCKS") {
                if (code == "0") {
                    when (vs) {
                        "BLOCK" -> { curBlock = null; awaitingName = true; curList = mutableListOf(); curEnt = null }
                        "ENDBLK" -> {
                            val nm = curBlock
                            if (nm != null) blocks[nm] = curList ?: mutableListOf()
                            curBlock = null; curList = null; curEnt = null; awaitingName = false
                        }
                        else -> {
                            val e = DxfEntity(vs); curEnt = e; curList?.add(e)
                        }
                    }
                } else if (code == "2" && awaitingName && curEnt == null) {
                    curBlock = vs; awaitingName = false
                } else {
                    val ci = code.toIntOrNull()
                    if (ci != null) curEnt?.items?.add(ci to value)
                }
            }
        }
        return DxfScopes(ms, blocks)
    }

    /**
     * 出图口径文字：遍历模型空间 + 各 *Paper_SpaceN 布局，
     *   INSERT   → 递归展开其块定义（visited 防环）后取里面的文字
     *   其他实体 → 直接取文字
     * 未被任何 INSERT 引用的块定义（图库残留 / 不出图的说明模板）不计入。
     *
     * 巴布亚桩基实测：全量 59597 字 → 出图 23932 字，桌面基准 23960 字（偏差 -0.1%）。
     */
    private fun printedTextOf(scopes: DxfScopes, enc: String): String {
        val out = mutableListOf<String>()

        fun expand(ins: DxfEntity, visited: MutableSet<String>) {
            val nm = ins.values(2).firstOrNull()?.trim() ?: ""
            if (nm.isEmpty() || nm in visited) return
            visited.add(nm)
            val blk = scopes.blocks[nm] ?: return
            for (be in blk) {
                if (be.type == "INSERT") expand(be, visited) else grabText(be, out, enc)
            }
        }

        fun walk(ents: List<DxfEntity>) {
            for (e in ents) {
                if (e.type == "INSERT") expand(e, HashSet()) else grabText(e, out, enc)
            }
        }

        walk(scopes.ms)
        for ((name, ents) in scopes.blocks) {
            if (PAPER_BLOCK_NAME.containsMatchIn(name)) walk(ents)
        }
        return joinUnique(out)
    }

    /** 端口桌面 clean_mtext()：去掉 MTEXT 格式码 */
    private fun cleanMtext(s: String): String {
        var r = s.replace("\\P", "\n").replace("\\p", "\n")
            .replace("\\~", " ").replace("\\^I", " ").replace("\\^J", " ")
        r = r.replace(Regex("\\\\[A-Za-z][^;{}]*;"), "")
        r = r.replace("\\\\", "\\").replace("{", "").replace("}", "").replace("\\", "")
        return r
    }

    // ── DXF 转义解码：\U+XXXX（Unicode）/ \M+nXXXX（亚洲 MIF） ──
    private val UNICODE_ESC = Regex("\\\\U\\+([0-9A-Fa-f]{4})")
    private val MIF_ESC = Regex("\\\\M\\+([1-5])([0-9A-Fa-f]{4})")

    /**
     * 解开 DXF 里的字符转义。
     * ezdxf 读 DXF 时会自动展开 \U+XXXX，纯文本解析必须手工补上，
     * 否则 LibreDWG 新版写出的中文（被 cquote 统一转成 \U+XXXX）会全部丢失。
     * \M+nXXXX 是老式亚洲码页转义：n=1 ShiftJIS / 2 Big5 / 3 Wansung / 4 Johab / 5 GBK。
     */
    private fun decodeDxfEscapes(s: String): String {
        if (s.isEmpty()) return s
        var r = s
        if (r.contains("\\M+")) {
            r = MIF_ESC.replace(r) { m ->
                val cs = when (m.groupValues[1]) {
                    "1" -> "Shift_JIS"
                    "2" -> "Big5"
                    "3" -> "EUC-KR"
                    "5" -> "GBK"
                    else -> "GBK"
                }
                try {
                    val v = m.groupValues[2].toInt(16)
                    val b = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
                    String(b, charset(cs))
                } catch (_: Exception) {
                    m.value
                }
            }
        }
        if (r.contains("\\U+")) {
            r = UNICODE_ESC.replace(r) { m ->
                try {
                    m.groupValues[1].toInt(16).toChar().toString()
                } catch (_: Exception) {
                    m.value
                }
            }
        }
        return r
    }

    private fun cjkCountOf(t: String): Int = t.count { it.code in 0x4E00..0x9FFF }
    private fun commonCountOf(t: String): Int = t.count { it.code in DwgRawCjkScanner.COMMON_CJK_CHARS }

    /**
     * 全局兜底：整篇一个中文都没有时，尝试把全文按 Latin-1 回编码再用 GB18030 解，
     * 若能得到足量「常用汉字」则整体替换。
     * 只在「零中文」时触发，因此不会影响本就抽到中文的文件（如巴布亚桩基）。
     */
    private fun globalRecoverCjk(text: String): String {
        if (text.isEmpty()) return text
        if (cjkCountOf(text) > 0) return text
        val viaLatin1 = try {
            String(text.toByteArray(Charsets.ISO_8859_1), charset("GB18030"))
        } catch (_: Exception) {
            null
        }
        if (viaLatin1 != null && commonCountOf(viaLatin1) >= 20) return viaLatin1
        return text
    }

    // ───────────────────────────── 页数统计（count_cad_frames 全分支） ─────────────────────────────

    /** 对外主入口：一次性解析出文字 + 页数 */
    fun analyze(dxfPath: String): AnalysisResult {
        val lines = readDxfLines(dxfPath) ?: return AnalysisResult("", "", null, "DXF 读取失败", "")
        val encDecision = detectEncoding(lines)
        lastDecodeMode = "global-${encDecision.enc}(u8=${encDecision.u8Cjk},gb=${encDecision.gbCjk})"
        val entities = parseEntities(lines)
        val text = extractTextFromEntities(entities, encDecision.enc)
        // v1.5.33：额外算一份「出图口径」文字，交由 MainActivity 在密度异常时采用
        val printed = try { printedTextOf(parseAllSections(lines), encDecision.enc) } catch (_: Throwable) { "" }

        val sec = splitSections(lines)
        // ENTITIES 段 == 桌面 doc.modelspace()（LibreDWG 不写组码 67）
        val msLines = if (sec.entities.isNotEmpty()) sec.entities else lines
        val msEnts = countTopLevelEntities(msLines)

        // 图纸空间布局：LibreDWG 把非活动布局的实体写在 BLOCKS 段的 *Paper_SpaceN 块里
        val paperCounts = paperBlockEntityCounts(sec.blocks)
        val paper = paperCounts.values.count { it > 0 }
        val paperTotalEnts = paperCounts.values.sum()

        val rects = rawClosedPolylines(msLines)
        val geo = countGeomFrames(rects)
        val sheets = distinctSheetNumbers(entities)
        val det = countDetailSheets(entities, msLines)
        val (frames, reason) = pickFrames(geo, paper, sheets, det, entities.isNotEmpty(), msEnts, paperTotalEnts)
        return AnalysisResult(text, printed, frames, reason, lastDecodeMode)
    }

    /** 端口桌面 count_cad_frames 的判定优先级（逐行对齐 wordcount.py:2461-2498） */
    private fun pickFrames(geo: Int, paper: Int, sheets: Int, det: Int, hasEntities: Boolean,
                          msEnts: Int, paperTotalEnts: Int): Pair<Int?, String?> {
        // 布局稀疏 → 改用几何图框（dwg2dxf 常把所有图挤进 Model 空间）
        if (geo >= 3 && geo > paper + 1 && msEnts > 1000 && paperTotalEnts <= paper * 8) {
            return Pair(geo, "布局稀疏·改用几何图框估算")
        }
        if (paper >= 1) return Pair(paper, "布局计数")
        if (det >= 1 && det >= sheets) return Pair(det, "详图聚类估算")
        if (sheets >= 1) return Pair(sheets, "标题块图号")
        if (geo >= 1) return Pair(geo, "几何图框估算")
        if (det >= 1) return Pair(det, "详图聚类估算")
        if (hasEntities) return Pair(1, "有图元·按1页估")
        return Pair(null, "CAD 无图框/布局，无法统计页数")
    }

    /** 统计一段 DXF 里的「顶层实体」数（排除 VERTEX/SEQEND/ATTRIB，对齐 ezdxf len(layout)） */
    private fun countTopLevelEntities(sec: List<String>): Int {
        var c = 0
        var i = 0
        val n = sec.size
        while (i < n - 1) {
            if (sec[i].trim() == "0") {
                val t = sec[i + 1].trim()
                if (t !in SUB_ENTITY_TYPES) c++
            }
            i += 2
        }
        return c
    }

    /**
     * 扫 BLOCKS 段，统计每个 *Paper_SpaceN 块内的顶层实体数。
     * 对齐桌面 count_cad_frames 里的：
     *     for layout in doc.layouts:  nents = len(list(layout))
     *     paper_total_ents += nents;  if nents > 0: paper += 1
     * 注意：活动的 *Paper_Space（无数字后缀）被 LibreDWG 跳过不写实体，
     * 与 ezdxf 视角一致（其实体落在 ENTITIES 段，被当成 modelspace）。
     */
    private fun paperBlockEntityCounts(blocks: List<String>): Map<String, Int> {
        val res = LinkedHashMap<String, Int>()
        var inBlock = false
        var awaitingName = false
        var curName: String? = null
        var count = 0
        val n = blocks.size
        var i = 0
        while (i < n - 1) {
            val code = blocks[i].trim()
            val value = blocks[i + 1].trim()
            i += 2
            if (code == "0") {
                if (value == "BLOCK") {
                    inBlock = true; awaitingName = true; curName = null; count = 0
                } else if (value == "ENDBLK") {
                    val name = curName
                    if (inBlock && name != null && PAPER_BLOCK_NAME.containsMatchIn(name)) {
                        res[name] = (res[name] ?: 0) + count
                    }
                    inBlock = false; awaitingName = false; curName = null; count = 0
                } else if (inBlock && value !in SUB_ENTITY_TYPES) {
                    count++
                }
            } else if (inBlock && awaitingName && code == "2") {
                curName = value
                awaitingName = false
            }
        }
        return res
    }

    // ── 几何图框：LWPOLYLINE/POLYLINE 闭合多段线外接框（端口 _raw_closed_polylines） ──
    private fun rawClosedPolylines(lines: List<String>): List<Rect> {
        val n = lines.size
        val rects = mutableListOf<Rect>()
        var i = 0
        while (i < n - 1) {
            val code = lines[i].trim()
            val `val` = if (i + 1 < n) lines[i + 1].trim() else ""
            if (code == "0" && (`val` == "LWPOLYLINE" || `val` == "POLYLINE")) {
                val etype = `val`
                val pts = mutableListOf<Pair<Double, Double>>()
                var closed = false
                var inVertex = false
                var j = i + 2
                while (j < n - 1) {
                    val c2 = lines[j].trim()
                    val v2 = if (j + 1 < n) lines[j + 1].trim() else ""
                    if (c2 == "0") {
                        if (etype == "POLYLINE") {
                            if (v2 == "VERTEX") {
                                inVertex = true
                                j += 2
                                continue
                            } else {
                                break
                            }
                        } else {
                            break
                        }
                    }
                    if (etype == "LWPOLYLINE") {
                        if (c2 == "70") {
                            if (((v2.toDoubleOrNull()?.toInt() ?: 0) and 1) == 1) closed = true
                        } else if (c2 == "10") {
                            val x = v2.toDoubleOrNull()
                            if (x != null && j + 3 < n && lines[j + 2].trim() == "20") {
                                val y = lines[j + 3].trim().toDoubleOrNull()
                                if (y != null) { pts.add(x to y); j += 2 }
                            }
                        }
                    } else { // POLYLINE
                        if (inVertex) {
                            if (c2 == "70") {
                                if (((v2.toDoubleOrNull()?.toInt() ?: 0) and 1) == 1) closed = true
                            } else if (c2 == "10") {
                                val x = v2.toDoubleOrNull()
                                if (x != null && j + 3 < n && lines[j + 2].trim() == "20") {
                                    val y = lines[j + 3].trim().toDoubleOrNull()
                                    if (y != null) { pts.add(x to y); j += 2 }
                                }
                            }
                        }
                    }
                    j += 1
                }
                if (closed && pts.size >= 4) {
                    var minx = Double.MAX_VALUE; var miny = Double.MAX_VALUE
                    var maxx = -Double.MAX_VALUE; var maxy = -Double.MAX_VALUE
                    for (p in pts) {
                        if (p.first < minx) minx = p.first
                        if (p.second < miny) miny = p.second
                        if (p.first > maxx) maxx = p.first
                        if (p.second > maxy) maxy = p.second
                    }
                    rects.add(Rect(minx, miny, maxx, maxy))
                }
                i = j
            } else {
                i += 1
            }
        }
        return rects
    }

    /** 端口桌面 _count_geom_frames：过滤过小/过扁、去整体外框、取互不包含最大矩形 */
    private fun countGeomFrames(rects: List<Rect>, minSide: Int = 150, minArea: Int = 40000, maxAr: Int = 10): Int {
        val cand = mutableListOf<RectArea>()
        for (r in rects) {
            val w = r.maxx - r.minx
            val h = r.maxy - r.miny
            if (w < minSide || h < minSide) continue
            val area = w * h
            if (area < minArea) continue
            val ar = max(w, h) / max(min(w, h), 1.0)
            if (ar > maxAr) continue
            cand.add(RectArea(r, area))
        }
        if (cand.isEmpty()) return 0
        cand.sortByDescending { it.area }
        // 去掉单一整体外框（最大者明显大于次大者）
        if (cand.size >= 2 && cand[0].area > cand[1].area * 4) cand.removeAt(0)
        if (cand.isEmpty()) return 0
        // 取互不包含的最大矩形（过滤框内小框）
        val maximal = mutableListOf<RectArea>()
        for (r in cand) {
            if (maximal.any { contains(it.rect, r.rect) }) continue
            maximal.add(r)
        }
        if (maximal.isEmpty()) return 0
        maximal.sortByDescending { it.area }
        // 按面积自然聚类：最大断层以上视为真正图框
        if (maximal.size >= 2) {
            val ratios = (0 until maximal.size - 1).map { maximal[it].area / max(maximal[it + 1].area, 1.0) }
            val maxGapIdx = ratios.indices.maxByOrNull { ratios[it] } ?: 0
            if (ratios[maxGapIdx] >= 5) {
                for (k in maximal.size - 1 downTo maxGapIdx + 1) maximal.removeAt(k)
            }
        }
        val maxArea = maximal.maxByOrNull { it.area }?.area ?: 0.0
        val thr = max(minArea.toDouble(), maxArea * 0.01)
        val filtered = maximal.filter { it.area >= thr }
        return filtered.size
    }

    private fun contains(big: Rect, o: Rect): Boolean {
        return big.minx - 5 <= o.minx && big.miny - 5 <= o.miny &&
                o.maxx <= big.maxx + 5 && o.maxy <= big.maxy + 5
    }

    // ── 标题块图号：INSERT 下 ATTRIB 的图号属性去重（端口 _distinct_sheet_numbers） ──
    private fun distinctSheetNumbers(entities: List<DxfEntity>): Int {
        val sheetTag = Regex("(?i)SHEET_NUMBER|图号|页号|DWGNO|NO|PAGE|SHEET|DRAWING_NO|图纸编号|图纸|SOUZAITU|SUOZAI|所在图")
        val strongSheet = Regex("(?i)SHEET_NUMBER|图号|页号|DRAWING_NO|图纸编号")
        val titleTag = Regex("(?i)DRAWING_TITLE|图名|标题|TITLE|NAME")
        val titleName = Regex("(?i)图框|边框|幅面|图纸|标题栏|会签栏|签名栏|FRAME|FRM|BORDER|BORD|TITLE|TBAR|TB|SHEET|FORMAT|GB|国标")
        val detailName = Regex("(?i)大样|节点|DETAIL|CALL|CALLOUT|NOTE|局部|NOSING|标高|型材|SECTION|DET|局部放大|索引|放大|详图|节点详图|大样图|详图索引")
        val normSheet = Regex("\\d{4,6}")

        val sheets = LinkedHashSet<String>()
        for (e in entities) {
            if (e.type != "INSERT") continue
            val name = e.values(2).firstOrNull()?.trim() ?: ""
            val isDetail = detailName.containsMatchIn(name)
            var hasTitle = false
            val anyTag = mutableListOf<String>()
            val vals = mutableListOf<String>()
            for (a in e.attribs) {
                val tag = a.values(2).firstOrNull()?.trim() ?: ""
                val txt = a.values(1).firstOrNull()?.trim() ?: ""
                anyTag.add(tag)
                if (titleTag.containsMatchIn(tag)) hasTitle = true
                if (sheetTag.containsMatchIn(tag) && txt.isNotEmpty()) vals.add(txt)
            }
            if (vals.isEmpty()) continue
            if (isDetail) {
                for (v in vals) normSheet.find(v)?.let { sheets.add(it.value) }
                continue
            }
            if (hasTitle || titleName.containsMatchIn(name) || strongSheet.containsMatchIn(anyTag.joinToString(" "))) {
                for (v in vals) normSheet.find(v)?.let { sheets.add(it.value) }
            }
        }
        return sheets.size
    }

    // ── 详图聚类：大样/节点 INSERT 按位置网格聚类（端口 _count_detail_sheets） ──
    private fun countDetailSheets(entities: List<DxfEntity>, msLines: List<String>): Int {
        val detailPat = Regex("(?i)大样|节点|DETAIL|CALLOUT|详图|detail|节点详图|大样图|详图索引")
        val idTags = setOf("DWGNO", "NO", "PAGE", "页号", "图号", "SHEET")
        val detailPos = mutableListOf<Pair<Double, Double>>()
        for (e in entities) {
            if (e.type != "INSERT") continue
            val name = e.values(2).firstOrNull()?.trim() ?: ""
            if (!detailPat.containsMatchIn(name)) continue
            var hasId = false
            for (a in e.attribs) {
                val tag = a.values(2).firstOrNull()?.trim() ?: ""
                if (tag in idTags) { hasId = true; break }
            }
            if (!hasId) continue
            val x = e.values(10).firstOrNull()?.toDoubleOrNull()
            val y = e.values(20).firstOrNull()?.toDoubleOrNull()
            if (x != null && y != null) detailPos.add(x to y)
        }
        if (detailPos.size < 2) return 0
        // 取最大闭合 LWPOLYLINE 作网格尺寸参考
        val rectsAll = rawClosedPolylines(msLines)
        val largest = rectsAll.maxByOrNull { (it.maxx - it.minx) * (it.maxy - it.miny) } ?: return 0
        val w = largest.maxx - largest.minx
        val h = largest.maxy - largest.miny
        if (w < 1 || h < 1) return 0
        val ar = max(w, h) / max(min(w, h), 1.0)
        val gridSize = if (ar > 3) min(w, h) else max(w, h)
        if (gridSize > 20000 || gridSize < 1) return 0
        val cells = LinkedHashSet<Pair<Int, Int>>()
        for ((x, y) in detailPos) {
            cells.add(Pair(round(x / gridSize).toInt(), round(y / gridSize).toInt()))
        }
        return cells.size
    }
}
