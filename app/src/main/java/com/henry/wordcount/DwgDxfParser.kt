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
 * 背景：Android 端原先只做「原始二进制字节扫描」(scanDwgRaw)，会漏掉绝大多数 CAD 矢量文字，
 * 且页数硬编码为 1。桌面版主路径是 dwg→dxf(LibreDWG)→结构化抽取 + count_cad_frames 多分支页数，
 * 统计准确。本文件把这两块完整移植：
 *   - extractText()   ← 桌面 extract_text_custom()（读 TEXT/ATTDEF/MTEXT/MULTILEADER 实体）
 *   - countFrames()   ← 桌面 count_cad_frames() 的全部分支（几何图框 / 标题块图号 / 详图聚类 / 布局数 / 兜底）
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

    data class AnalysisResult(
        val text: String,
        val frames: Int?,
        val framesReason: String?
    )

    // ───────────────────────────── DXF 实体解析 ─────────────────────────────

    private fun parseEntities(dxfPath: String): List<DxfEntity> {
        val lines = readDxfLines(dxfPath) ?: return emptyList()
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

    /** 读取 DXF 文本行（严格 UTF-8，失败回退 GB18030）。
     *  Kotlin/JVM 默认 UTF-8 解码遇到非法字节会静默替换为 U+FFFD，
     *  导致含 GBK 字节的 DXF 永远走不到 GB18030 回退。这里用 REPORT 模式，
     *  非法字节时抛异常，强制进入 GB18030 解码，与桌面 Python 行为一致。 */
    private fun readDxfLines(dxfPath: String): List<String>? {
        val f = File(dxfPath)
        if (!f.exists() || f.length() == 0L) return null
        val raw = try {
            f.readBytes()
        } catch (_: Exception) {
            return null
        }
        val text = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: Exception) {
            try { String(raw, charset("GB18030")) } catch (_: Exception) { return null }
        }
        return text.split("\n")
    }

    // ───────────────────────────── 文字抽取（extract_text_custom） ─────────────────────────────

    private fun extractTextFromEntities(entities: List<DxfEntity>): String {
        val collected = mutableListOf<String>()
        for (e in entities) {
            when (e.type) {
                "TEXT", "ATTDEF" -> {
                    val vs = e.values(1)
                    if (vs.isNotEmpty() && vs[0].isNotBlank()) {
                        val recovered = tryRecoverMojibake(vs[0].trim())
                        if (recovered.isNotBlank()) collected.add(recovered)
                    }
                }
                "MTEXT" -> {
                    val s = (e.values(1) + e.values(3)).joinToString("")
                    val cleaned = cleanMtext(tryRecoverMojibake(s))
                    if (cleaned.isNotBlank()) collected.add(cleaned.trim())
                }
                "MULTILEADER" -> {
                    val s = (e.values(304) + e.values(302)).joinToString("")
                    val recovered = tryRecoverMojibake(s)
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
        return out.joinToString("\n")
    }

    /** 端口桌面 clean_mtext()：去掉 MTEXT 格式码 */
    private fun cleanMtext(s: String): String {
        var r = s.replace("\\P", "\n").replace("\\p", "\n")
            .replace("\\~", " ").replace("\\^I", " ").replace("\\^J", " ")
        r = r.replace(Regex("\\\\[A-Za-z][^;{}]*;"), "")
        r = r.replace("\\\\", "\\").replace("{", "").replace("}", "").replace("\\", "")
        return r
    }

    /**
     * 尝试逆转 DXF 文本中常见的编码误读。
     * LibreDWG 写 DXF 时，若把源 DWG 里的 GBK/GB18030 字节误按 Latin-1 或 UTF-8
     * 解码，就会出现 mojibake。这里把字符串按 Latin-1 / UTF-8 回编码成字节后
     * 再用 GB18030 解码，若得到明显更多的真实中文则采用。
     */
    private fun tryRecoverMojibake(s: String): String {
        if (s.isEmpty()) return s
        fun cjkCount(t: String): Int = t.count { it.code in 0x4E00..0x9FFF }
        fun commonCount(t: String): Int = t.count { it.code in DwgRawCjkScanner.COMMON_CJK_CHARS }
        val origCjk = cjkCount(s)
        val origCommon = commonCount(s)

        // 尝试 1：Latin-1 字节 → GB18030
        val viaLatin1 = try {
            val bytes = s.toByteArray(Charsets.ISO_8859_1)
            String(bytes, charset("GB18030"))
        } catch (_: Exception) { null }
        if (viaLatin1 != null) {
            val c = cjkCount(viaLatin1)
            val cc = commonCount(viaLatin1)
            if (c > origCjk && cc > origCommon && cc >= 2) return viaLatin1
        }

        // 尝试 2：UTF-8 字节 → GB18030（处理 UTF-8 误解码出的随机 CJK 码点）
        val viaUtf8 = try {
            val bytes = s.toByteArray(Charsets.UTF_8)
            String(bytes, charset("GB18030"))
        } catch (_: Exception) { null }
        if (viaUtf8 != null) {
            val c = cjkCount(viaUtf8)
            val cc = commonCount(viaUtf8)
            if (c > origCjk && cc > origCommon && cc >= 2) return viaUtf8
        }

        return s
    }

    // ───────────────────────────── 页数统计（count_cad_frames 全分支） ─────────────────────────────

    /** 对外主入口：一次性解析出文字 + 页数 */
    fun analyze(dxfPath: String): AnalysisResult {
        val entities = parseEntities(dxfPath)
        val text = extractTextFromEntities(entities)
        val rects = rawClosedPolylines(dxfPath)
        val geo = countGeomFrames(rects)
        val paper = rawLayoutCount(dxfPath)
        val sheets = distinctSheetNumbers(entities)
        val det = countDetailSheets(entities, dxfPath)
        val (frames, reason) = pickFrames(geo, paper, sheets, det, entities.isNotEmpty())
        return AnalysisResult(text, frames, reason)
    }

    /** 端口桌面 count_cad_frames 的判定优先级 */
    private fun pickFrames(geo: Int, paper: Int, sheets: Int, det: Int, hasEntities: Boolean): Pair<Int?, String?> {
        // 布局稀疏 → 改用几何图框（dwg2dxf 常把所有图挤进 Model 空间）
        if (geo >= 3 && geo > paper + 1) {
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

    // ── 几何图框：LWPOLYLINE/POLYLINE 闭合多段线外接框（端口 _raw_closed_polylines） ──
    private fun rawClosedPolylines(dxfPath: String): List<Rect> {
        val lines = readDxfLines(dxfPath) ?: return emptyList()
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

    /** 端口桌面 _raw_layout_count：统计 *Paper_Space* 图纸空间布局数 */
    private fun rawLayoutCount(dxfPath: String): Int {
        val lines = readDxfLines(dxfPath) ?: return 0
        val text = lines.joinToString("\n")
        val re = Regex("\\*Paper_Space(?:\\d*)")
        val unique = LinkedHashSet<String>()
        for (m in re.findAll(text)) {
            var s = m.value
            if (s == "*Paper_Space") s = "*Paper_Space0"
            unique.add(s)
        }
        return unique.size
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
    private fun countDetailSheets(entities: List<DxfEntity>, dxfPath: String): Int {
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
        val rectsAll = rawClosedPolylines(dxfPath)
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
