package com.henry.wordcount

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
        val text: String,
        val frames: Int?,
        val framesReason: String?
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

    /** 读取 DXF 文本行。
     *  v1.5.32 修正：LibreDWG 0.14 写出的 DXF 字节编码混杂——
     *    部分 UTF-8、部分 GBK、个别非法字节，导致整文件严格 UTF-8 或 GB18030
     *    解码都会失败，进而整篇统计判空、全部归零（巴布亚/给排水实测如此）。
     *  改为用 ISO-8859-1 无损读入（每个字节保留为一个 char），
     *  结构解析（组码/实体名/块名均为 ASCII）完全不受影响；
     *  文本值再在 extractTextFromEntities 里按原字节回解为 UTF-8/GB18030（见 decodeValue）。
     */
    private fun readDxfLines(dxfPath: String): List<String>? {
        val f = File(dxfPath)
        if (!f.exists() || f.length() == 0L) return null
        val raw = try {
            f.readBytes()
        } catch (_: Exception) {
            return null
        }
        val text = String(raw, Charsets.ISO_8859_1)
        return text.split("\n")
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

    private fun extractTextFromEntities(entities: List<DxfEntity>): String {
        val collected = mutableListOf<String>()
        for (e in entities) {
            when (e.type) {
                "TEXT", "ATTDEF" -> {
                    val vs = e.values(1)
                    if (vs.isNotEmpty() && vs[0].isNotBlank()) {
                        val recovered = decodeDxfEscapes(decodeValue(vs[0].trim()))
                        if (recovered.isNotBlank()) collected.add(recovered)
                    }
                }
                "MTEXT" -> {
                    val s = (e.values(1) + e.values(3)).joinToString("")
                    val cleaned = cleanMtext(decodeDxfEscapes(decodeValue(s)))
                    if (cleaned.isNotBlank()) collected.add(cleaned.trim())
                }
                "MULTILEADER" -> {
                    val s = (e.values(304) + e.values(302)).joinToString("")
                    val recovered = decodeDxfEscapes(decodeValue(s))
                    if (recovered.isNotBlank()) collected.add(recovered.trim())
                }
            }
        }
        // 去重（保序）
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<String>()
        for (c in collected) {
            if (c !in seen) { seen.add(c); out.add(c) }
        }
        return globalRecoverCjk(out.joinToString("\n"))
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

    /**
     * 把 readDxfLines 以 ISO-8859-1 读入的「字节即字符」字符串还原为真实文本。
     * 先按原字节以 UTF-8 严格解码（LibreDWG 0.14 嵌入中文多为 UTF-8），
     * 失败再试 GB18030（兼容老 GBK 字节），仍失败则保留原样。
     * GBK 双字节的首字节落在 0x81–0xFE，均不是合法 UTF-8 引导字节，
     * 故 UTF-8 严格解码会失败并安全回退到 GB18030，二者不会误判。
     */
    private fun decodeValue(latin1: String): String {
        if (latin1.isEmpty()) return latin1
        val bytes = latin1.toByteArray(Charsets.ISO_8859_1)
        val asUtf8 = try { String(bytes, StandardCharsets.UTF_8) } catch (_: Exception) { null }
        if (asUtf8 != null) return asUtf8
        val asGbk = try { String(bytes, charset("GB18030")) } catch (_: Exception) { null }
        return asGbk ?: latin1
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
        val lines = readDxfLines(dxfPath) ?: return AnalysisResult("", null, "DXF 读取失败")
        val entities = parseEntities(lines)
        val text = extractTextFromEntities(entities)

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
        return AnalysisResult(text, frames, reason)
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
