package com.henry.wordcount

import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.abs
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
        val decodeMode: String,
        /** v1.5.40: 诊断摘要——结构化逐值解码 vs 整文件 GBK/UTF-8 扫描各能拿到的 CJK 数，用于定位真机编码问题 */
        val diag: String = ""
    )

    /** 图层可见性信息（端口桌面 ezdxf layer 判定） */
    private data class LayerInfo(val plot: Boolean, val on: Boolean, val frozen: Boolean)

    /** DXF 作用域：模型空间实体 + 块定义表（块名 → 块内实体） + 图层可见性 + XREF 块集合 */
    private data class DxfScopes(
        val ms: List<DxfEntity>,
        val blocks: LinkedHashMap<String, MutableList<DxfEntity>>,
        val layers: Map<String, LayerInfo> = emptyMap(),
        val xrefBlocks: Set<String> = emptySet(),
        val hasOle2Frame: Boolean = false
    )

    /** ezdxf 口径下不算「顶层实体」的从属记录：POLYLINE 的顶点、INSERT 的属性、序列结束符 */
    private val SUB_ENTITY_TYPES = setOf("VERTEX", "SEQEND", "ATTRIB")

    /** 图纸空间块名：R13+ 写作 *Paper_SpaceN（N≥0），转旧版本时写作 $PAPER_SPACEN。
     *  v1.6.6: 排除无数字后缀的 *Paper_Space（活动 paper space 占位块），
     *  LibreDWG 常把它和真实布局块都写出，导致 ezdxf 只计 1 个 layout 而手机误计 2 个。 */
    private val PAPER_BLOCK_NAME = Regex("^[*\\$]Paper_Space[0-9]+$", RegexOption.IGNORE_CASE)

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

    /**
     * v1.5.39 逐值双解码（按「常用汉字更多 + 常用占比更高」择优）。
     * 不再依赖整文件全局编码判定：DXF 里 UTF-8 与 GB18030 文本可能混排，且真机
     * ARM 输出的编码分布与本地不同，全局判定容易把整篇误判，导致中文全丢
     * （给排水_t3 / Tenova 在 v1.5.38 因此显示「点选PDF统计」且字数为 0）。
     * 改为每个文本值独立试 UTF-8 / GB18030，取「常用汉字数更多、且常用字占比更高」
     * 的一方；都为 0 时取无替换符的一方。decodeDxfEscapes(\U+XXXX/\M+XXXX) 由
     * grabText 在 decodeValue 之后统一处理，转义与编码无关，优先级最高。
     */
    private fun decodeValue(s: String, enc: String): String {
        if (s.isEmpty()) return s
        val b = try { s.toByteArray(Charsets.ISO_8859_1) } catch (_: Exception) { return s }
        val u8 = try { String(b, StandardCharsets.UTF_8) } catch (_: Exception) { null }
        val gb = try { String(b, charset("GB18030")) } catch (_: Exception) { null }
        val gbk = try { String(b, charset("GBK")) } catch (_: Exception) { null }
        fun score(t: String?): Pair<Int, Double> {
            if (t == null) return 0 to 0.0
            val cjk = cjkCountOf(t)
            if (cjk == 0) return 0 to 0.0
            val common = commonCountOf(t)
            return common to (common.toDouble() / cjk)
        }
        val su = score(u8); val sg = score(gb); val sgk = score(gbk)
        // 取「常用汉字最多」的一方（常用字占比相近时以更多中文为准）
        val candidates = listOf(u8 to su, gb to sg, gbk to sgk).filter { it.second.first > 0 }
        val best = candidates.maxWithOrNull(compareBy({ it.second.first }, { it.second.second }))
        if (best != null) return best.first ?: s
        // 都抽不到中文：取无替换符的一方，否则回退原串
        val noffd = listOf(u8, gb, gbk).filterNotNull().firstOrNull { "\uFFFD" !in it }
        return noffd ?: u8 ?: gb ?: gbk ?: s
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

    // ───────────────────────────── 文字抽取（端口桌面 v1.6.51 _collect_dxf_texts） ─────────────────────────────

    /**
     * v1.5.59: 对齐桌面 v1.6.53 `_collect_dxf_texts` 的文字收集策略。
     *
     * 历史问题（水雾电气图-7区实测）：
     *   1) 旧逻辑对全文件文字做全局去重，CAD 中同一串文字在 32 张图上重复出现
     *      是合法的（标题栏、图例、材料表表头…），全局去重会吃掉 ~72% 中文。
     *   2) 旧逻辑不按 INSERT 引用次数展开块定义；且只取 ATTDEF 模板、没抓 INSERT
     *      携带的 ATTRIB 实例值（水雾图 2050 个中文丢失）。
     *
     * 新策略：
     *   · 遍历模型空间 + 各 *Paper_SpaceN 图纸空间布局
     *   · 每个 INSERT 递归展开其块定义内文字（按引用次数重复计入）
     *   · 同时收集 INSERT 自带的 ATTRIB 实例值
     *   · 不再全局去重，按翻译计费口径逐次计数
     *   · cache 仅用于避免块循环引用死递归，不用于去重
     */

    /** 从单个实体取一段文字（TEXT/ATTDEF/ATTRIB/MTEXT/MULTILEADER），空或无文字返回空串 */
    private fun textOf(e: DxfEntity, enc: String): String {
        return when (e.type) {
            "TEXT", "ATTDEF", "ATTRIB" -> {
                val vs = e.values(1)
                if (vs.isNotEmpty() && vs[0].isNotBlank()) {
                    decodeDxfEscapes(decodeValue(vs[0].trim(), enc)).trim()
                } else ""
            }
            "MTEXT" -> {
                val s = (e.values(1) + e.values(3)).joinToString("")
                cleanMtext(decodeDxfEscapes(decodeValue(s, enc))).trim()
            }
            "MULTILEADER" -> {
                val s = (e.values(304) + e.values(302)).joinToString("")
                decodeDxfEscapes(decodeValue(s, enc)).trim()
            }
            else -> ""
        }
    }

    /**
     * 按『图纸渲染所见』收集 DXF 全部文字段落。
     * 返回列表（已 trim，非空），调用方用 "\n" join 后计数。
     */
    private fun collectDxfTexts(scopes: DxfScopes, enc: String): List<String> {
        val out = mutableListOf<String>()
        val blockCache = mutableMapOf<String, List<String>>()

        fun blockTexts(name: String, depth: Int): List<String> {
            if (depth > 6) return emptyList()
            blockCache[name]?.let { return it }
            val res = mutableListOf<String>()
            blockCache[name] = res // 先占位，防块循环引用死递归
            val blk = scopes.blocks[name] ?: return res
            for (be in blk) {
                if (!isEntityVisible(be)) continue
                if (!isLayerVisible(layerOf(be), scopes.layers)) continue
                if (be.type == "INSERT") {
                    val bname = be.values(2).firstOrNull()?.trim() ?: ""
                    if (isXrefBlock(bname, scopes.xrefBlocks, scopes.hasOle2Frame)) continue
                    res.addAll(blockTexts(bname, depth + 1))
                    for (a in be.attribs) {
                        val s = textOf(a, enc)
                        if (s.isNotBlank()) res.add(s)
                    }
                } else {
                    val s = textOf(be, enc)
                    if (s.isNotBlank()) res.add(s)
                }
            }
            blockCache[name] = res
            return res
        }

        val spaces = mutableListOf<List<DxfEntity>>()
        spaces.add(scopes.ms)
        for ((name, ents) in scopes.blocks) {
            if (PAPER_BLOCK_NAME.containsMatchIn(name)) spaces.add(ents)
        }

        for (space in spaces) {
            for (e in space) {
                if (!isEntityVisible(e)) continue
                if (!isLayerVisible(layerOf(e), scopes.layers)) continue
                if (e.type == "INSERT") {
                    val bname = e.values(2).firstOrNull()?.trim() ?: ""
                    if (isXrefBlock(bname, scopes.xrefBlocks, scopes.hasOle2Frame)) continue
                    out.addAll(blockTexts(bname, 0))
                    for (a in e.attribs) {
                        val s = textOf(a, enc)
                        if (s.isNotBlank()) out.add(s)
                    }
                } else {
                    val s = textOf(e, enc)
                    if (s.isNotBlank()) out.add(s)
                }
            }
        }
        return out
    }

    /** 去重（保序）后拼成整段文本 —— 仅用于 raw 字节兜底恢复，不再用于主路径 */
    private fun joinUnique(items: List<String>): String {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<String>()
        for (c in items) {
            if (c !in seen) { seen.add(c); out.add(c) }
        }
        return globalRecoverCjk(out.joinToString("\n"))
    }

    // ─────────────────────── 出图口径（现在与 collectDxfTexts 一致） ───────────────────────

    /**
     * 一次遍历同时拿到「模型空间实体」与「块定义表」。
     * ENTITIES 段 == 桌面 doc.modelspace()（LibreDWG 不写组码 67）；
     * BLOCKS 段每个 BLOCK…ENDBLK 是一个块定义，其中 *Paper_SpaceN 就是各图纸布局。
     */
    // ───────────────────────────── 图层可见性 / XREF 过滤（端口桌面 ezdxf _collect_dxf_texts） ─────────────────────────────
    // v1.9.8：桌面版 _collect_dxf_texts 会剔除「不可打印/关闭/冻结」图层、不可见实体、
    // 以及外部参照(XREF)块，避免把图纸上实际不显示的内容计入字数（如全铜外形图从 914→~200）。
    // 手机端此前无此过滤，与桌面口径不一致；此处补齐，使抽文字数与桌面严格对齐。

    private fun layerOf(e: DxfEntity): String {
        for ((c, v) in e.items) if (c == 8) return v.trim()
        return ""
    }

    private fun isLayerVisible(layerName: String, layers: Map<String, LayerInfo>): Boolean {
        if (layerName.isEmpty()) return true
        val L = layers[layerName] ?: return true
        if (!L.plot) return false
        return L.on && !L.frozen
    }

    private fun isXrefBlock(name: String, xrefBlocks: Set<String>, hasOle2Frame: Boolean): Boolean {
        if (name.isEmpty()) return false
        val bn = name.uppercase()
        if ("XREF" in bn) return true
        if (name in xrefBlocks) {
            // v1.8.33：含 OLE2FRAME 的封面/目录/说明页，标题栏带 xref_path 但 PDF 字数包含它，
            // 仅当块名像标题栏时才允许展开（与桌面一致）；其余参照图一律过滤。
            if (hasOle2Frame && ("TITLE BLOCK" in bn || "TITLEBLOCK" in bn)) return false
            return true
        }
        return false
    }

    private fun isEntityVisible(e: DxfEntity): Boolean {
        // 不可见标志：组码 60 的 bit 0x01
        for ((c, v) in e.items) {
            if (c == 60) {
                val inv = v.trim().toIntOrNull() ?: 0
                if (inv and 0x01 != 0) return false
            }
        }
        // ATTRIB 隐藏标志：组码 70 的 bit 0x01
        if (e.type == "ATTRIB") {
            for ((c, v) in e.items) {
                if (c == 70) {
                    val fl = v.trim().toIntOrNull() ?: 0
                    if (fl and 0x01 != 0) return false
                }
            }
        }
        return true
    }

    private fun parseAllSections(lines: List<String>): DxfScopes {
        val ms = mutableListOf<DxfEntity>()
        val blocks = LinkedHashMap<String, MutableList<DxfEntity>>()
        val layers = LinkedHashMap<String, LayerInfo>()
        val xrefBlocks = mutableSetOf<String>()
        var section: String? = null
        var curEnt: DxfEntity? = null
        var curList: MutableList<DxfEntity>? = null
        var curBlock: String? = null
        var awaitingName = false
        // BLOCK 头字段（xref 判定）
        var curBlockFlags = 0
        var curBlockXref = ""
        // TABLES / LAYER 解析
        var tableAwaiting = false
        var inLayerTable = false
        var curLayerName: String? = null
        var curLayerFlags = 0
        var curLayerPlot = 1
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
                        "BLOCK" -> { curBlock = null; awaitingName = true; curList = mutableListOf(); curEnt = null; curBlockFlags = 0; curBlockXref = "" }
                        "ENDBLK" -> {
                            val nm = curBlock
                            if (nm != null) {
                                blocks[nm] = curList ?: mutableListOf()
                                // v1.9.8：flags 0x04 (xref-dependent) 视为外部参照块
                                if (curBlockFlags and 0x04 != 0) xrefBlocks.add(nm)
                            }
                            curBlock = null; curList = null; curEnt = null; awaitingName = false; curBlockFlags = 0; curBlockXref = ""
                        }
                        else -> {
                            val e = DxfEntity(vs); curEnt = e; curList?.add(e)
                        }
                    }
                } else if (code == "2" && awaitingName && curEnt == null) {
                    curBlock = vs; awaitingName = false
                } else if (curEnt == null && curBlock != null && !awaitingName) {
                    // BLOCK 头字段（flags / xref 路径），位于第一个实体之前
                    if (code == "70") curBlockFlags = vs.toIntOrNull() ?: 0
                    else if (code == "1") curBlockXref = vs
                } else {
                    val ci = code.toIntOrNull()
                    if (ci != null) curEnt?.items?.add(ci to value)
                }
            } else if (section == "TABLES") {
                if (code == "0") {
                    when (vs) {
                        "TABLE" -> { tableAwaiting = true; inLayerTable = false; curLayerName = null }
                        "LAYER" -> {
                            if (curLayerName != null) {
                                layers[curLayerName] = LayerInfo(curLayerPlot == 1, (curLayerFlags and 0x10) == 0, (curLayerFlags and 0x01) != 0)
                            }
                            curLayerName = null; curLayerFlags = 0; curLayerPlot = 1
                        }
                        "ENDTAB" -> {
                            if (curLayerName != null) {
                                layers[curLayerName] = LayerInfo(curLayerPlot == 1, (curLayerFlags and 0x10) == 0, (curLayerFlags and 0x01) != 0)
                            }
                            inLayerTable = false; curLayerName = null
                        }
                    }
                } else if (code == "2" && tableAwaiting) {
                    inLayerTable = (vs == "LAYER")
                    tableAwaiting = false
                } else if (inLayerTable) {
                    if (code == "2" && curLayerName == null) curLayerName = vs
                    else if (code == "70") curLayerFlags = vs.toIntOrNull() ?: 0
                    else if (code == "290") curLayerPlot = vs.toIntOrNull() ?: 1
                }
            }
        }
        // OLE2FRAME 检测（用于 XREF 标题栏例外）
        var hasOle2 = false
        for (e in ms) if (e.type == "OLE2FRAME") { hasOle2 = true; break }
        if (!hasOle2) {
            for (blk in blocks.values) for (e in blk) if (e.type == "OLE2FRAME") { hasOle2 = true; break }
        }
        return DxfScopes(ms, blocks, layers, xrefBlocks, hasOle2)
    }

    /**
     * 出图口径文字：现在复用 collectDxfTexts，与桌面 v1.6.53 `_collect_dxf_texts`
     * 口径一致（模型空间 + 各 *Paper_SpaceN 布局 + INSERT 递归展开 + ATTRIB 实例值）。
     */
    private fun printedTextOf(scopes: DxfScopes, enc: String): String {
        return collectDxfTexts(scopes, enc).joinToString("\n")
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

    /**
     * 出图口径「去重」：按行保序去重（同桌面 ezdxf 出图口径的 seen-set 去重）。
     * 桌面 v1.6.53 的 _extract_dwg_via_ezdxf_printed 在展开 INSERT 后仍对最终
     * 文本做全局去重，避免同一串文字（如桩号/轴号表）被逐次插入重复计数导致字数虚高
     * （巴布亚桩基：不去重 202558 字 → 去重 23980 字，对齐桌面 23960）。
     * 仅用于 printedText（出图口径分支），不影响标准口径 text（水雾等按翻译计费需逐次计数）。
     */
    private fun dedupeText(t: String): String {
        val seen = LinkedHashSet<String>()
        val out = StringBuilder()
        for (line in t.split("\n")) {
            val s = line.trim()
            if (s.isEmpty()) continue
            if (s !in seen) {
                seen.add(s)
                if (out.isNotEmpty()) out.append("\n")
                out.append(s)
            }
        }
        return out.toString()
    }
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
        val f = File(dxfPath)
        val raw = try { f.readBytes() } catch (_: Exception) { return AnalysisResult("", "", null, "DXF 读取失败", "") }
        if (raw.isEmpty()) return AnalysisResult("", "", null, "DXF 为空", "")
        // ── v1.5.58: 大文件 OOM 防护 ─────────────────────────────────────────
        // 8.2MB 的 DWG 经 dwg2dxf 转出的 DXF 实测约 46MB。analyze 旧路径会同时持有
        //   raw(byte[]) + ISO-8859-1 String(2x) + split 百万行 List + recover 四份大 String，
        // 叠加远超堆上限 → 真机 OOM。防御：
        //   · DXF > 150MB：直接走流式降级 analyzeLarge（不 readBytes 全量 / 不 split / 不 recover），
        //     内存 O(1)，保证超大文件也能出字数、不崩溃。
        //   · 25MB < DXF ≤ 150MB：保留完整结构化解析，但跳过整文件 recover 兜底
        //     （该兜底主要为异常编码的小文件服务，大文件结构化已能拿中文，且它叠加 4 份
        //     大 String 是 OOM 主因）。
        if (raw.size > 150 * 1024 * 1024) {
            return analyzeLarge(dxfPath)
        }
        val lines = String(raw, Charsets.ISO_8859_1).split("\n")
        val encDecision = detectEncoding(lines)
        lastDecodeMode = "global-${encDecision.enc}(u8=${encDecision.u8Cjk},gb=${encDecision.gbCjk})"
        val entities = parseEntities(lines)
        // v1.5.59: 文字提取改用 collectDxfTexts（按 INSERT 引用展开 + ATTRIB 实例值 + 取消全局去重）
        val scopes = parseAllSections(lines)
        val text = collectDxfTexts(scopes, encDecision.enc).joinToString("\n")
        // v1.5.60: printedText 改为出图口径「去重」版本（对齐桌面 ezdxf 出图口径）。
        // 仅在高密度触发时才被采用（见 MainActivity 出图口径分支），水雾等常规密度文件
        // 仍走标准口径 text（逐次计数），不受影响。
        val printed = dedupeText(text)

        val sec = splitSections(lines)
        // ENTITIES 段 == 桌面 doc.modelspace()（LibreDWG 不写组码 67）
        val msLines = if (sec.entities.isNotEmpty()) sec.entities else lines
        val msEnts = countTopLevelEntities(msLines)

        // 图纸空间布局：优先按桌面 ezdxf 的 LAYOUT 对象计数（OBJECTS 段），
        // 缺失或为零时回退到 BLOCKS 段的 *Paper_SpaceN 块计数。
        val layoutCount = countLayoutObjects(lines)
        val blockStats = paperBlockStats(sec.blocks)
        // v1.9.126: 区分「含真实(非视口)实体的布局」(paper) 与「仅视口布局」(bareViewport)，
        // 对齐桌面 count_cad_frames 的 paper / bare_viewport_layouts 口径。
        val bareViewport = blockStats.count { it.total > 0 && it.nonVp == 0 }
        val paperTotalEnts = blockStats.sumOf { it.total }
        var paper = blockStats.count { it.nonVp > 0 }
        if (paper == 0 && bareViewport == 0 && layoutCount >= 1) {
            // 兜底：BLOCKS 段未解析出纸张布局时，退回 LAYOUT 对象计数（旧行为）
            paper = layoutCount
        }

        // v1.5.59: 优先采用 LWPOLYLINE 闭合图框（CAD 图纸图框通常用 LWPOLYLINE 绘制）
        //   并补上 INSERT 块引用还原的图框（对齐桌面 _detect_lwpolyline_sheets：
        //   模型空间 LWPOLYLINE 矩形 + 块恢复矩形 合并后喂给 _count_geom_frames）。
        //   水雾电气图-7区 仅靠 lwRects 得 17 页，合并 blockFrameRects 后达 32 页。
        val lwRects = lwpolylineRects(msLines)
        val bfRects = blockFrameRects(scopes.ms, scopes.blocks)
        val geoLw = countGeomFrames(lwRects + bfRects)
        val rects = rawClosedPolylines(msLines)
        val geo = countGeomFrames(rects)
        val sheets = distinctSheetNumbers(entities)
        val det = countDetailSheets(entities, msLines)
        val (frames, reason) = pickFrames(geoLw, geo, paper, sheets, det, entities.isNotEmpty(), msEnts, paperTotalEnts, bareViewport)

        // ── v1.5.40: 整文件 CJK 兜底恢复 ─────────────────────────────────────
        // 真机上交叉编译的 libdwg2dxf.so 可能把中文写成 GBK 字节 / \U+XXXX 转义 /
        // 其它混合形态，导致逐值双解码拿到的中文极少。这里直接对整份 DXF 原始字节
        // 做多编码扫描（GBK / GB18030 / UTF-8 抽连续 CJK 段 + 全文转义还原），
        // 当结构化结果的中文明显偏少时采用兜底结果，避免误判「需要 PDF」。
        val structCjk = cjkCountOf(text)
        val textChars = text.length
        // v1.5.58: 大文件跳过整文件多编码兜底——recover 会叠加 GBK/GB18030/UTF-8/latin1
        //   四份大 String，是 OOM 主因；且大文件结构化解析本就能拿到中文，兜底价值低
        val rawRecovered = if (raw.size <= 50 * 1024 * 1024) recoverCjkFromRawDxf(raw) else ""
        val rawCjk = cjkCountOf(rawRecovered)
        val rawCommon = commonCountOf(rawRecovered)
        val rawCommonRatio = if (rawCjk > 0) rawCommon.toDouble() / rawCjk else 0.0
        val rawChars = rawRecovered.length
        // 仅当结构化结果中文偏少、且整文件扫描明显更多（去噪）时才采用兜底。
        // v1.5.60: 放宽门槛（50→200 / commonRatio 2→5），覆盖「结构化解析几乎抽不到中文、
        // 但 DXF 原始字节（含 \U+XXXX 转义）能还原出真实中文」的真机场景（水雾电气图-7区
        // 在部分真机上 collectDxfTexts 仅得极少 CJK，靠整文件转义还原可拿回约 25071 字）。
        // v1.5.88: 再加 commonRatio>=0.10 门控，避免英文/栅格化图纸的 DXF 原始字节被
        // GBK/UTF-16 巧合解码成大量伪 CJK（如 L01-A01D03...dwg 因 commonRatio≈0 虚增到 10059 字）。
        // v1.5.92: 增加 rawChars >= textChars 门控，避免结构化英文文本充足的图纸被
        // 少量伪 CJK 兜底覆盖，导致总字数暴跌或出现假中文。
        val useRecovered = structCjk < 200 && rawCjk >= maxOf(structCjk + 100, 200) && rawCommon >= 5
                && rawCommonRatio >= 0.10 && rawChars >= textChars
        val finalText = if (useRecovered) rawRecovered else text
        val diag = "enc=${encDecision.enc}(u8=${encDecision.u8Cjk},gb=${encDecision.gbCjk}) " +
                "structCjk=$structCjk rawCjk=$rawCjk rawCommon=$rawCommon"
        return AnalysisResult(finalText, printed, frames, reason, lastDecodeMode, diag)
    }

    /**
     * v1.5.40: 整文件 CJK 兜底恢复。
     * 当结构化逐值解码拿到的中文极少时调用。直接对 DXF 原始字节做多编码扫描，
     * 覆盖 ARM 上 LibreDWG 把中文写成 GBK 字节 / 转义 / 其它形态而逐值解码漏掉的情况。
     * 返回去重后的中文文本（每行一段）。
     */
    private fun recoverCjkFromRawDxf(rawBytes: ByteArray): String {
        val collected = mutableListOf<String>()
        // 1) 整文件按不同编码解码后抽连续 CJK 段（带「常用字」过滤抑制 GBK 噪声）
        for (csName in listOf("GBK", "GB18030", "UTF-8")) {
            try {
                val decoded = String(rawBytes, charset(csName))
                collected.add(extractCjkRuns(decoded))
            } catch (_: Exception) {}
        }
        // 2) 全文转义还原（\U+XXXX / \M+nXXXX 可能散落在任意位置，包括结构化解析漏掉的值）
        try {
            val latin1 = String(rawBytes, Charsets.ISO_8859_1)
            collected.add(decodeDxfEscapes(latin1))
        } catch (_: Exception) {}
        return joinUnique(collected.flatMap { it.split("\n") })
    }

    /**
     * v1.5.58: 超大 DXF 流式降级解析（OOM 防护）。
     * 当 DXF 原始字节 > 150MB 时，analyze 的完整路径（raw byte[] + ISO-8859-1 String(2x)
     * + split 百万行 List + recover 四份大 String）会叠加远超堆上限 → 真机 OOM。
     * 这里改用 BufferedReader 逐行配对扫描，只在流中维护必要状态，内存占用 O(1)：
     *   · 抽 TEXT/MTEXT/ATTDEF/MULTILEADER/ATTRIB 的文本值（组码 1/3/7/9）并逐值解码
     *   · 数 *Paper_SpaceN 布局块数作为页数估算
     * 仅抽取文本值（远小于原始 DXF），保证超大文件也能出字数和近似页数、绝不崩溃。
     */
    private fun analyzeLarge(dxfPath: String): AnalysisResult {
        val reader = try {
            File(dxfPath).bufferedReader(Charsets.ISO_8859_1)
        } catch (_: Exception) {
            return AnalysisResult("", "", null, "DXF 读取失败", "")
        }
        val sb = StringBuilder()
        var inTextEntity = false
        var paper = 0
        try {
            var code = reader.readLine()
            while (code != null) {
                val value = reader.readLine() ?: break
                val ci = code.trim().toIntOrNull()
                if (ci == 0) {
                    val t = value.trim()
                    inTextEntity = t in setOf("TEXT", "MTEXT", "ATTDEF", "MULTILEADER", "ATTRIB")
                    if (t.startsWith("*Paper_Space") || t.startsWith("\$Paper_Space")) paper++
                } else if (inTextEntity && ci in setOf(1, 3, 7, 9)) {
                    sb.append(decodeValue(value, "UTF-8")).append("\n")
                }
                code = reader.readLine()
            }
        } catch (_: Exception) {
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
        val text = sb.toString()
        val frames = if (paper >= 1) paper else if (text.isNotEmpty()) 1 else null
        val reason = when {
            paper >= 1 -> "布局计数(流式降级)"
            text.isNotEmpty() -> "有文本·按1页估(流式降级)"
            else -> "超大文件·无文本"
        }
        return AnalysisResult(text, "", frames, reason, "stream-large", "stream-large raw>150MB")
    }

    /** 从一段字符串中抽取最长连续 CJK 段（≥2 字，且含常用字或较长），忽略纯 ASCII / GBK 噪声段 */
    private fun extractCjkRuns(s: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = s.length
        while (i < n) {
            val cp = s[i].code
            if (cp in 0x4E00..0x9FFF) {
                var j = i
                var cjk = 0
                var common = 0
                while (j < n) {
                    val c = s[j].code
                    if (c in 0x4E00..0x9FFF) {
                        cjk++
                        if (c in DwgRawCjkScanner.COMMON_CJK_CHARS) common++
                        j++
                    } else if (c in 0x3000..0x303F || c in 0xFF00..0xFFEF || c == 0x20) {
                        j++
                    } else break
                }
                if (cjk >= 2 && (common >= 1 || cjk >= 6)) sb.append(s.substring(i, j)).append("\n")
                i = j
            } else i++
        }
        return sb.toString()
    }


    /** 端口桌面 count_cad_frames 的判定优先级 */
    private fun pickFrames(geoLw: Int, geo: Int, paper: Int, sheets: Int, det: Int,
                          hasEntities: Boolean, msEnts: Int, paperTotalEnts: Int, bareViewport: Int): Pair<Int?, String?> {
        // 布局稀疏 → 改用几何图框（dwg2dxf 常把所有图挤进 Model 空间）
        val geoBest = if (geoLw >= 1) geoLw else geo
        if (geoBest >= 3 && geoBest > paper + 1 && msEnts > 1000 && paperTotalEnts <= paper * 8) {
            val reason = if (geoLw >= 1) "布局稀疏·改用LWPOLYLINE图框估算" else "布局稀疏·改用几何图框估算"
            return Pair(geoBest, reason)
        }
        if (paper >= 1) return Pair(paper, "布局计数")
        // v1.9.126: 端口桌面 v1.8.109——仅视口布局(图纸内容全在 Model 空间)且几何图框≤1 时
        // 按 1 页计；几何图框>1（多张独立图纸拼在 Model）时不强行 1 页，信任几何
        // （XT26220/21/22：2 个仅视口布局 + Model 含 3 张独立图框 → 实际 3 页）。
        if (bareViewport >= 1 && msEnts > 50 && geoBest <= 1) {
            return Pair(1, "Model单图·图纸布局仅含视口")
        }
        if (det >= 1 && det >= sheets) return Pair(det, "详图聚类估算")
        if (sheets >= 1) return Pair(sheets, "标题块图号")
        if (geoLw >= 1) return Pair(geoLw, "LWPOLYLINE图框估算")
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
     * 扫 OBJECTS 段，按 LAYOUT 对象统计命名布局数（排除 Model）。
     * 桌面版 ezdxf 的 doc.layouts 实际遍历的是 LAYOUT 字典对象，而不是 BLOCKS 里的
     * *Paper_Space* 块。LibreDWG 转 DXF 时常同时写出 *Paper_Space（无数字后缀，
     * 活动 paper space 占位）和 *Paper_Space0 两个块，导致按块计数变成 2，
     * 而 ezdxf 只识别到一个 LAYOUT。此函数直接对齐 ezdxf 的 layout 口径。
     */
    private fun countLayoutObjects(lines: List<String>): Int {
        var inObjects = false
        var inLayout = false
        var name: String? = null
        var count = 0
        val n = lines.size
        var i = 0
        while (i < n - 1) {
            val code = lines[i].trim()
            val value = lines[i + 1].trim()
            if (code == "0" && value == "SECTION") {
                if (i + 3 < n && lines[i + 2].trim() == "2") {
                    inObjects = lines[i + 3].trim() == "OBJECTS"
                    i += 4
                    continue
                }
            }
            if (code == "0" && value == "ENDSEC") {
                if (inLayout) {
                    if (name != null && name.uppercase() != "MODEL") count++
                    inLayout = false
                    name = null
                }
                inObjects = false
                i += 2
                continue
            }
            if (!inObjects) { i += 2; continue }
            if (code == "0") {
                if (inLayout) {
                    if (name != null && name.uppercase() != "MODEL") count++
                    inLayout = false
                    name = null
                }
                if (value == "LAYOUT") {
                    inLayout = true
                }
            } else if (inLayout && code == "1") {
                name = value
            }
            i += 2
        }
        if (inLayout && name != null && name.uppercase() != "MODEL") count++
        return count
    }

    /** 单个纸张布局块的实体统计（端口桌面 count_cad_frames 的 paper / bare_viewport_layouts 区分） */
    private data class BlockStat(val name: String, val total: Int, val nonVp: Int)

    /**
     * 扫 BLOCKS 段，统计每个 *Paper_SpaceN 块内的顶层实体数，并区分 VIEWPORT 实体。
     * v1.9.126: 同时统计 total（含视口）与 nonVp（非视口实体数），据此区分：
     *   - paper         = 含真实(非视口)实体的布局数（对齐桌面 paper）
     *   - bareViewport  = 仅含视口、无任何真实实体的布局数（对齐桌面 bare_viewport_layouts）
     *   - paperTotalEnts= 所有纸张布局实体总数（含视口，对齐桌面 paper_total_ents）
     * 仅作为 LAYOUT 对象缺失时的兜底；桌面版优先使用 LAYOUT 对象计数。
     */
    private fun paperBlockStats(blocks: List<String>): List<BlockStat> {
        val res = mutableListOf<BlockStat>()
        var inBlock = false
        var awaitingName = false
        var curName: String? = null
        var total = 0
        var nonVp = 0
        val n = blocks.size
        var i = 0
        while (i < n - 1) {
            val code = blocks[i].trim()
            val value = blocks[i + 1].trim()
            i += 2
            if (code == "0") {
                if (value == "BLOCK") {
                    inBlock = true; awaitingName = true; curName = null; total = 0; nonVp = 0
                } else if (value == "ENDBLK") {
                    val name = curName
                    // v1.6.6: 过滤极简单/占位 layout block（实体数<2），避免 dwg2dxf 残留的
                    // 空布局或仅含标题块占位符的 block 被误计为独立图纸页。
                    if (inBlock && name != null && PAPER_BLOCK_NAME.containsMatchIn(name) && total >= 2) {
                        res.add(BlockStat(name, total, nonVp))
                    }
                    inBlock = false; awaitingName = false; curName = null; total = 0; nonVp = 0
                } else if (inBlock) {
                    if (value == "VIEWPORT") {
                        // 视口实体：计入 total（桌面 paper_total_ents 含视口），但不计入 nonVp
                        total++
                    } else if (value !in SUB_ENTITY_TYPES) {
                        total++; nonVp++
                    }
                }
            } else if (inBlock && awaitingName && code == "2") {
                curName = value
                awaitingName = false
            }
        }
        return res
    }

    // ── 几何图框：LWPOLYLINE/POLYLINE 闭合多段线外接框（端口 _raw_closed_polylines） ──

    /**
     * v1.5.59: 单独统计 LWPOLYLINE 闭合矩形（端口桌面 _detect_lwpolyline_sheets）。
     * CAD 中图纸图框通常绘制为闭合 LWPOLYLINE；优先于 LINE 重建图框。
     */
    private fun lwpolylineRects(lines: List<String>): List<Rect> {
        val rects = mutableListOf<Rect>()
        val n = lines.size
        var i = 0
        while (i < n - 1) {
            val code = lines[i].trim()
            val `val` = if (i + 1 < n) lines[i + 1].trim() else ""
            if (code == "0" && `val` == "LWPOLYLINE") {
                val pts = mutableListOf<Pair<Double, Double>>()
                var closed = false
                var j = i + 2
                // v1.5.59 修复：实体体内必须按「组码行+值行」成对步进（每次 j+=2）。
                // 旧代码在 10 分支内 j+=2、末尾又 j+=1，导致读完顶点后错位，
                // 把 layer "0"（组码8 的值"0"）误判为新实体而提前 break，
                // 闭合标志/顶点全丢 → 漏掉大图框（水雾图仅 17 页而非 32）。
                while (j < n - 1) {
                    val c2 = lines[j].trim()
                    val v2 = if (j + 1 < n) lines[j + 1].trim() else ""
                    if (c2 == "0") break
                    if (c2 == "70") {
                        if (((v2.toDoubleOrNull()?.toInt() ?: 0) and 1) == 1) closed = true
                        j += 2
                    } else if (c2 == "10") {
                        val x = v2.toDoubleOrNull()
                        if (x != null && j + 3 < n && lines[j + 2].trim() == "20") {
                            val y = lines[j + 3].trim().toDoubleOrNull()
                            if (y != null) pts.add(x to y)
                        }
                        j += 2
                    } else {
                        j += 2
                    }
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
                // v1.5.59 修复：实体体内同样按「组码行+值行」成对步进（每次 j+=2），
                // 与 lwpolylineRects 一致，避免错位丢顶点。
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
                                if (y != null) pts.add(x to y)
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
                                    if (y != null) pts.add(x to y)
                                }
                            }
                        }
                    }
                    j += 2
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

    /**
     * v1.5.59: 图框块引用还原（端口桌面 _block_frame_rects / 已验证 Python 端口）。
     * 遍历模型空间 INSERT，递归取块内最大闭合 LWPOLYLINE 外框（须符合 √2 图纸比例），
     * 按 INSERT 缩放/插入点变换到世界坐标，补回『图框以块引用存放』时漏计的图纸页数。
     * 水雾电气图-7区 仅靠 lwpolylineRects 得 17 页，补上本函数后达 32 页，与桌面对齐。
     */
    private fun blockFrameRects(ms: List<DxfEntity>, blocks: LinkedHashMap<String, MutableList<DxfEntity>>): List<Rect> {
        val cache = LinkedHashMap<String, Rect?>()
        /** 从已解析的 DxfEntity（须为 LWPOLYLINE）求轴对齐外框 */
        fun lwRectOfEntity(e: DxfEntity): Rect? {
            if (e.type != "LWPOLYLINE") return null
            try {
                var closed = false
                for ((c, v) in e.items) {
                    if (c == 70) {
                        try {
                            if (((v.toDoubleOrNull()?.toInt() ?: 0) and 1) == 1) closed = true
                        } catch (_: Exception) {}
                    }
                }
                if (!closed) return null
                val pts = mutableListOf<Pair<Double, Double>>()
                var i = 0
                while (i < e.items.size - 1) {
                    val c = e.items[i].first
                    val v = e.items[i].second
                    if (c == 10) {
                        val x = v.toDoubleOrNull()
                        if (x != null && i + 1 < e.items.size && e.items[i + 1].first == 20) {
                            val y = e.items[i + 1].second.toDoubleOrNull()
                            if (y != null) pts.add(x to y)
                        }
                    }
                    i += 1
                }
                if (pts.size < 4) return null
                var minx = Double.MAX_VALUE; var miny = Double.MAX_VALUE
                var maxx = -Double.MAX_VALUE; var maxy = -Double.MAX_VALUE
                for (p in pts) {
                    if (p.first < minx) minx = p.first
                    if (p.second < miny) miny = p.second
                    if (p.first > maxx) maxx = p.first
                    if (p.second > maxy) maxy = p.second
                }
                return Rect(minx, miny, maxx, maxy)
            } catch (_: Exception) {
                return null
            }
        }
        /** 按 INSERT 的 xscale/yscale/insert 把块内矩形变换到世界坐标 */
        fun xform(r: Rect, ins: DxfEntity): Rect? {
            try {
                val sx = ins.values(41).firstOrNull()?.toDoubleOrNull() ?: 1.0
                val sy = ins.values(42).firstOrNull()?.toDoubleOrNull() ?: 1.0
                val ix = ins.values(10).firstOrNull()?.toDoubleOrNull() ?: 0.0
                val iy = ins.values(20).firstOrNull()?.toDoubleOrNull() ?: 0.0
                val x1 = r.minx * sx + ix; val x2 = r.maxx * sx + ix
                val y1 = r.miny * sy + iy; val y2 = r.maxy * sy + iy
                return Rect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
            } catch (_: Exception) {
                return null
            }
        }
        fun blockMaxRect(name: String, depth: Int): Rect? {
            if (depth > 4) return null
            if (cache.containsKey(name)) return cache[name]
            cache[name] = null  // 占位：防止块互相引用造成的无限递归
            val blk = blocks[name] ?: emptyList()
            var best: Rect? = null
            var bestArea = 0.0
            for (e in blk) {
                var r = lwRectOfEntity(e)
                if (r == null && e.type == "INSERT") {
                    val subName = e.values(2).firstOrNull()?.trim() ?: ""
                    val sub = blockMaxRect(subName, depth + 1)
                    r = if (sub != null) xform(sub, e) else null
                }
                if (r != null) {
                    val a = (r.maxx - r.minx) * (r.maxy - r.miny)
                    if (a > bestArea) { bestArea = a; best = r }
                }
            }
            cache[name] = best
            return best
        }
        val out = mutableListOf<Rect>()
        for (e in ms) {
            if (e.type != "INSERT") continue
            val rot = try { e.values(50).firstOrNull()?.toDoubleOrNull() ?: 0.0 } catch (_: Exception) { 0.0 }
            // 跳过旋转块（旋转后轴对齐外框失真，且 Python 端口验证旋转块不是图框）
            if (abs(rot) > 1e-6 && abs(abs(rot) - 360) > 1e-6) continue
            val name = e.values(2).firstOrNull()?.trim() ?: ""
            val baseR = blockMaxRect(name, 0) ?: continue
            val r = xform(baseR, e) ?: continue
            val w = r.maxx - r.minx
            val h = r.maxy - r.miny
            if (w <= 0 || h <= 0) continue
            val ar = max(w, h) / min(w, h)
            if (ar < 1.30 || ar > 1.55) continue  // 仅保留 √2 图纸比例
            out.add(r)
        }
        return out
    }

    /**
     * 端口桌面 v1.6.50 _count_geom_frames：过滤过小/过扁、去整体外框、
     * 取互不包含最大矩形，并支持『拼板外框展开』。
     *
     * v1.5.59 关键修复（水雾电气图-7区 17→32）：
     *   1) 折叠几乎重合的重复矩形；
     *   2) 若某框直接包含 >=2 个尺寸相近且互不重叠的子框，判定为拼板容器，
     *      用子框替代它；
     *   3) 发生容器展开时跳过面积断层裁剪 + 1% 阈值，避免混合比例尺拼板被误杀。
     */
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

        // 折叠几乎重合的重复矩形
        val dedup = mutableListOf<RectArea>()
        for (r in cand) {
            if (dedup.any { contains(it.rect, r.rect) && contains(r.rect, it.rect) }) continue
            dedup.add(r)
        }
        val cand2 = dedup.toMutableList()
        cand2.sortByDescending { it.area }

        // 取互不包含的最大矩形
        val maximal = mutableListOf<RectArea>()
        for (r in cand2) {
            if (maximal.any { contains(it.rect, r.rect) }) continue
            maximal.add(r)
        }
        if (maximal.isEmpty()) return 0

        // 容器展开：拼板外框 → 内部并排真实图框
        fun strictIn(inner: Rect, outer: Rect): Boolean =
            contains(outer, inner) && !contains(inner, outer)
        fun overlap(p: Rect, q: Rect): Boolean =
            !(p.maxx <= q.minx || q.maxx <= p.minx || p.maxy <= q.miny || q.maxy <= p.miny)

        val expanded = mutableListOf<RectArea>()
        var didExpand = false
        for (r in maximal) {
            val kids = cand2.filter { strictIn(it.rect, r.rect) }
            val direct = kids.filter { k -> kids.none { m -> m !== k && strictIn(k.rect, m.rect) } }
            var ok = direct.size >= 2
            if (ok) {
                val areas = direct.map { it.area }
                if (areas.maxOrNull()!! > 1.5 * areas.minOrNull()!!) ok = false
            }
            if (ok) {
                for (i in direct.indices) {
                    for (j in i + 1 until direct.size) {
                        if (overlap(direct[i].rect, direct[j].rect)) {
                            ok = false
                            break
                        }
                    }
                    if (!ok) break
                }
            }
            if (ok) {
                expanded.addAll(direct)
                didExpand = true
            } else {
                expanded.add(r)
            }
        }

        // 去重（坐标精度 3 位）
        val seen = LinkedHashSet<String>()
        val maximal2 = mutableListOf<RectArea>()
        for (r in expanded) {
            val key = "%.3f,%.3f,%.3f,%.3f".format(r.rect.minx, r.rect.miny, r.rect.maxx, r.rect.maxy)
            if (key in seen) continue
            seen.add(key)
            maximal2.add(r)
        }
        if (maximal2.isEmpty()) return 0
        maximal2.sortByDescending { it.area }

        if (!didExpand && maximal2.size == 2) {
            // v1.9.126: 端口桌面 _count_geom_frames——最大框面积 >= 3× 次大框视为
            // 『主图 + 附属块/标题栏/修订表』，合并为 1 张图（XT26224：主图框 18.3M
            // 与右侧附属块 5.84M 比值 3.13× → 合并为 1 页）。仅 2 框且未发生拼板展开时启用，
            // 避免把『1 张大图 + 多张独立小图』的混合图纸误杀（3+ 张走既有拼板/容器逻辑）。
            val areas = maximal2.map { it.area }.sortedDescending()
            if (areas[0] >= 3.0 * areas[1]) return 1
        }
        if (!didExpand) {
            // 未展开时沿用旧逻辑：面积断层裁剪 + 1% 阈值
            if (maximal2.size >= 2) {
                val ratios = (0 until maximal2.size - 1).map { maximal2[it].area / max(maximal2[it + 1].area, 1.0) }
                val maxGapIdx = ratios.indices.maxByOrNull { ratios[it] } ?: 0
                if (ratios[maxGapIdx] >= 5) {
                    for (k in maximal2.size - 1 downTo maxGapIdx + 1) maximal2.removeAt(k)
                }
            }
            val maxArea = maximal2.maxByOrNull { it.area }?.area ?: 0.0
            val thr = max(minArea.toDouble(), maxArea * 0.01)
            return maximal2.count { it.area >= thr }
        }
        return maximal2.size
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

    // ══════════════════════════════════════════════════════════════════════════════
    // v1.9.88：流式「按 INSERT 引用次数展开」文字抽取
    //          （端口桌面 _collect_dxf_texts，内存安全版）
    // ══════════════════════════════════════════════════════════════════════════════
    // 旧 analyze() 走 readBytes() + split("\n") 把整份 DXF 展开成 List<String>：
    // 22MB DXF → 数百万个 String 对象、堆上数百 MB → 真机 OOM，故 v1.9.2 起被弃用，
    // 降级为「扁平组码扫描」（DwgProcessor.extractDxfTextsSimple）。而扁平扫描有
    // 致命口径缺陷：块定义在 BLOCKS 段只出现一次，不按 INSERT 引用次数展开，
    // 且行级去重会把标题栏/图例这类合法重复文字吃掉 →
    // 大图比桌面少算 40%~50%（v1.9.85 实测 FA-31013 兜底 1866 vs 桌面 3160）。
    //
    // 本实现单遍流式扫描，只保留文本类实体（几何实体读完即弃），
    // 内存与「文字量」成正比而非「文件体积」，200MB+ DXF 也能安全解析。
    //
    // 口径严格对齐桌面 _collect_dxf_texts：
    //   · 模型空间(ENTITIES 段) + 各 *Paper_SpaceN 布局块
    //   · INSERT 递归展开块定义内文字（按引用次数重复计入）
    //   · INSERT 自带的 ATTRIB 实例值（非 ATTDEF 模板）
    //   · 剔除 关闭/冻结/非打印 图层、invisible 实体、隐藏 ATTRIB
    //   · 跳过外部参照(XREF)块
    //   · 不做全局去重
    // ══════════════════════════════════════════════════════════════════════════════

    /** 紧凑实体：只保留抽取文字所需字段（几何实体不入内存） */
    private class SEnt(val type: String) {
        var layer: String = ""
        var visible: Boolean = true
        var insertName: String = ""
        var decoded: String = ""
        val attribs = ArrayList<String>(2)
        val sb = StringBuilder()
    }

    /** 流式扫描中保留的实体类型；其余（LINE/ARC/HATCH…几何实体）读完即弃 */
    private val STREAM_KEEP_TYPES = setOf(
        "TEXT", "MTEXT", "ATTDEF", "MULTILEADER", "INSERT", "OLE2FRAME", "DIMENSION"
    )

    /** 展开结果上限：极端「块炸弹」图纸防失控（正常图纸远达不到） */
    private const val MAX_STREAM_OUT = 400_000

    /**
     * 把一组组码写入紧凑实体（只关心文字/图层/可见性/块名）。
     *
     * ⚠️ 必须按实体类型收窄组码，否则会把「样式名/提示串」当正文计入：
     *   · 组码 3 只有 MTEXT 是正文续段；DIMENSION/TEXT/ATTDEF 的组码 3 是样式名或提示串
     *   · 组码 302/304 只有 MULTILEADER 是文字
     *   · OLE2FRAME 的组码 1 是二进制数据，不能当文字
     */
    private fun applyCode(e: SEnt, gc: String, value: String) {
        when (gc) {
            "8" -> e.layer = value.trim()
            "2" -> { if (e.type == "INSERT") e.insertName = value.trim() }
            "60" -> { if ((value.trim().toIntOrNull() ?: 0) and 0x01 != 0) e.visible = false }
            "70" -> {
                // ATTRIB 隐藏标志：组码 70 的 bit 0x01
                if (e.type == "ATTRIB" && ((value.trim().toIntOrNull() ?: 0) and 0x01 != 0)) e.visible = false
            }
            "1" -> { if (e.type != "OLE2FRAME") e.sb.append(value) }
            "3" -> { if (e.type == "MTEXT") e.sb.append(value) }
            "302", "304" -> { if (e.type == "MULTILEADER") e.sb.append(value) }
        }
    }

    /**
     * 流式抽取 DXF 全部可见文字（按 INSERT 引用次数展开块）。
     * 返回已 trim 的非空文字行列表；解析失败或空结果返回 emptyList()，调用方应回退旧兜底。
     */
    fun extractTextsStreaming(dxfPath: String): List<String> {
        val f = File(dxfPath)
        if (!f.exists() || f.length() <= 0L) return emptyList()

        val blocks = LinkedHashMap<String, ArrayList<SEnt>>()
        val ms = ArrayList<SEnt>()
        val layerHidden = HashSet<String>()
        val layerKnown = HashSet<String>()
        val xrefBlocks = HashSet<String>()
        var hasOle2 = false

        // —— 扫描状态（先声明，供下方局部函数捕获）——
        var section = ""
        var sectionAwaiting = false
        var tableAwaiting = false
        var inLayerTable = false
        var curLayerName: String? = null
        var curLayerFlags = 0
        var curLayerColor = 1
        var curLayerPlot = 1
        var blockName: String? = null
        var blockList: ArrayList<SEnt>? = null
        var blockAwaitingName = false
        var blockFlags = 0
        var blockXref = ""
        var curEnt: SEnt? = null
        var lastInsert: SEnt? = null
        var attribOwner: SEnt? = null

        fun decodeText(raw: String): String {
            if (raw.isEmpty()) return ""
            var allAscii = true
            for (i in raw.indices) { if (raw[i].code > 0x7F) { allAscii = false; break } }
            val s = if (allAscii) raw else {
                val b = raw.toByteArray(Charsets.ISO_8859_1)
                val u8 = try { String(b, Charsets.UTF_8) } catch (_: Throwable) { raw }
                val gb = try { String(b, charset("GB18030")) } catch (_: Throwable) { raw }
                if (cjkCountOf(gb) >= cjkCountOf(u8)) gb else u8
            }
            // 桌面口径：所有实体文字统一过 clean_mtext（去掉 \fSimSun|b0; 等排版指令）
            return cleanMtext(decodeDxfEscapes(s)).trim()
        }

        fun flushLayer() {
            val n = curLayerName
            if (n != null && inLayerTable) {
                layerKnown.add(n)
                val on = curLayerColor >= 0                 // 桌面 ezdxf：色号(62)为负 = 图层关闭
                val frozen = (curLayerFlags and 0x01) != 0
                if (!on || frozen || curLayerPlot != 1) layerHidden.add(n)
            }
            curLayerName = null; curLayerFlags = 0; curLayerColor = 1; curLayerPlot = 1
        }

        fun finishBlock() {
            val n = blockName
            val l = blockList
            if (n != null && l != null) {
                blocks[n] = l
                if ((blockFlags and 0x04) != 0 || blockXref.isNotEmpty()) xrefBlocks.add(n)
            }
            blockName = null; blockList = null; blockAwaitingName = false
            blockFlags = 0; blockXref = ""
        }

        /** 结束当前实体：解码其文字；ATTRIB 归位到所属 INSERT */
        fun finishEntity() {
            val e = curEnt ?: return
            val t = decodeText(e.sb.toString())
            e.sb.setLength(0)
            if (e.type == "ATTRIB") {
                if (t.isNotEmpty() && e.visible) attribOwner?.attribs?.add(t)
            } else {
                e.decoded = t
            }
            curEnt = null
        }

        try {
            java.io.BufferedReader(
                java.io.InputStreamReader(java.io.FileInputStream(f), Charsets.ISO_8859_1), 1 shl 16
            ).use { br ->
                var code = br.readLine()
                while (code != null) {
                    val value = br.readLine() ?: break
                    val gc = code.trim()

                    if (gc == "0") {
                        finishEntity()
                        val vs = value.trim()
                        when (vs) {
                            "SECTION" -> {
                                flushLayer(); finishBlock()
                                section = ""; sectionAwaiting = true; lastInsert = null
                            }
                            "ENDSEC" -> {
                                flushLayer(); finishBlock()
                                section = ""; inLayerTable = false; tableAwaiting = false; lastInsert = null
                            }
                            "ENDTAB" -> { flushLayer(); inLayerTable = false; tableAwaiting = false }
                            "ENDBLK" -> { finishBlock(); lastInsert = null }
                            "SEQEND" -> { lastInsert = null }
                            "TABLE" -> { flushLayer(); tableAwaiting = true; inLayerTable = false }
                            "LAYER" -> { flushLayer() }
                            "BLOCK" -> {
                                finishBlock()
                                blockAwaitingName = true
                                blockList = ArrayList()
                                blockFlags = 0; blockXref = ""
                                lastInsert = null
                            }
                            "ATTRIB" -> {
                                // ATTRIB 不进实体表，只挂到紧邻其前的 INSERT
                                curEnt = SEnt("ATTRIB")
                                attribOwner = lastInsert
                            }
                            "VERTEX" -> { /* POLYLINE 顶点，忽略但不打断 INSERT→ATTRIB 归属 */ }
                            else -> {
                                lastInsert = null
                                if (vs in STREAM_KEEP_TYPES) {
                                    val e = SEnt(vs)
                                    val l = blockList
                                    if (l != null) l.add(e) else if (section == "ENTITIES") ms.add(e)
                                    curEnt = e
                                    if (vs == "INSERT") lastInsert = e
                                    if (vs == "OLE2FRAME") hasOle2 = true
                                }
                            }
                        }
                        code = br.readLine()
                        continue
                    }

                    // SECTION 名紧跟在 0/SECTION 之后
                    if (sectionAwaiting && gc == "2") {
                        section = value.trim(); sectionAwaiting = false
                        code = br.readLine()
                        continue
                    }

                    val e = curEnt
                    when (section) {
                        "TABLES" -> {
                            if (gc == "2") {
                                if (tableAwaiting) {
                                    inLayerTable = (value.trim() == "LAYER"); tableAwaiting = false
                                } else if (inLayerTable && curLayerName == null) {
                                    curLayerName = value.trim()
                                }
                            } else if (inLayerTable) {
                                when (gc) {
                                    "70" -> curLayerFlags = value.trim().toIntOrNull() ?: 0
                                    "62" -> curLayerColor = value.trim().toIntOrNull() ?: 1
                                    "290" -> curLayerPlot = value.trim().toIntOrNull() ?: 1
                                }
                            }
                        }
                        "BLOCKS" -> {
                            if (gc == "2" && blockAwaitingName) {
                                blockName = value.trim(); blockAwaitingName = false
                            } else if (e == null) {
                                // BLOCK 头字段（位于块内第一个实体之前）
                                if (gc == "70") blockFlags = value.trim().toIntOrNull() ?: 0
                                else if (gc == "1") blockXref = value.trim()
                            } else {
                                applyCode(e, gc, value)
                            }
                        }
                        "ENTITIES" -> { if (e != null) applyCode(e, gc, value) }
                    }
                    code = br.readLine()
                }
                finishEntity()
            }
        } catch (_: Throwable) {
            return emptyList()
        }

        // ── 展开阶段：模型空间 + 各 *Paper_SpaceN 布局，INSERT 按引用次数递归展开 ──
        val out = ArrayList<String>(8192)
        val cache = HashMap<String, ArrayList<String>>()

        fun hiddenLayer(layer: String): Boolean {
            if (layerKnown.isEmpty()) return false        // 没解析到图层表 → 不做任何图层过滤
            if (layer.isEmpty()) return false
            return layerHidden.contains(layer)
        }

        fun isXref(name: String): Boolean {
            if (name.isEmpty()) return false
            val bn = name.uppercase()
            if (bn.contains("XREF")) return true
            if (xrefBlocks.contains(name)) {
                if (hasOle2 && (bn.contains("TITLE BLOCK") || bn.contains("TITLEBLOCK"))) return false
                return true
            }
            return false
        }

        fun expandBlock(name: String, sink: ArrayList<String>, depth: Int) {
            if (depth > 6) return
            if (out.size >= MAX_STREAM_OUT) return
            val cached = cache[name]
            if (cached != null) { if (cached.isNotEmpty()) sink.addAll(cached); return }
            val res = ArrayList<String>(8)
            cache[name] = res                              // 先占位，防块循环引用死递归
            val ents = blocks[name] ?: return
            for (e in ents) {
                if (out.size >= MAX_STREAM_OUT) return
                if (!e.visible) continue
                if (hiddenLayer(e.layer)) continue
                if (e.type == "INSERT") {
                    if (isXref(e.insertName)) continue
                    expandBlock(e.insertName, res, depth + 1)
                    for (a in e.attribs) if (a.isNotBlank()) res.add(a)
                } else if (e.type != "OLE2FRAME") {
                    if (e.decoded.isNotBlank()) res.add(e.decoded)
                }
            }
            // ⚠️ 必须把本次展开结果并入调用方：res 同时作为缓存对象存在，
            // 若不 addAll 到 sink，则「首次展开的块文字」只会进缓存、永远不进输出，
            // 嵌套块（块里再套 INSERT）更会整层丢失。
            if (res.isNotEmpty()) sink.addAll(res)
        }

        fun walk(ents: List<SEnt>) {
            for (e in ents) {
                if (out.size >= MAX_STREAM_OUT) return
                if (!e.visible) continue
                if (hiddenLayer(e.layer)) continue
                if (e.type == "INSERT") {
                    if (isXref(e.insertName)) continue
                    expandBlock(e.insertName, out, 1)
                    for (a in e.attribs) if (a.isNotBlank()) out.add(a)
                } else if (e.type != "OLE2FRAME") {
                    if (e.decoded.isNotBlank()) out.add(e.decoded)
                }
            }
        }

        walk(ms)
        if (ms.isEmpty()) {
            // 少数 DXF（部分 LibreDWG 产物）把模型空间也写成 *Model_Space 块
            for ((name, ents) in blocks) {
                if (name.equals("*Model_Space", ignoreCase = true)) walk(ents)
            }
        }
        for ((name, ents) in blocks) {
            if (PAPER_BLOCK_NAME.containsMatchIn(name)) walk(ents)
        }
        return out
    }
}
