package com.henry.wordcount
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
/**
 * DWG 文件完整统计处理器。
 *
 * 端口自 MainActivity 的 DWG 处理主路径，确保压缩包内的 DWG 与单独打开走完全一致的引擎：
 *   1. 原始二进制扫描（scanDwgRaw）作为回退基线
 *   2. dwg→dxf 结构化文字抽取 + 图框页数（DwgIsolatedRunner + DwgDxfParser）
 *   3. 出图口径裁剪、DXF mojibake 检测、GBK/UTF-16 原始字节 CJK 恢复
 *   4. 终极兜底：DwgRawCjkScanner 原始字节扫描
 *   5. CAD 文字/纯编号拆分
 *
 * v1.5.81: 从 MainActivity 提取为共享对象，供 ArchiveEngine 复用。
 */
object DwgProcessor {
    data class DwgProcessResult(
        val words: Int,
        val fe: Int,
        val nc: Int,
        val chars: Int,
        val pages: Int,
        val pagesReason: String?,
        val needsPdf: Boolean,
        val diag: String,
        val cadParts: CadPartStats?,
        val finalText: String
    )
    suspend fun process(context: Context, file: File, dName: String = file.name): DwgProcessResult {
        return try {
            processInner(context, file, dName)
        } catch (e: Throwable) {
            // v1.8.9: 任何意外异常都归零显示"-"，绝不再把 scanDwgRaw 二进制噪声当作字数。
            // 此前该兜底返回原始字节扫描结果，对编码混乱/不可读的 DWG 会显示 10059 字/中文54
            // 这类假数字（与桌面"-"不符）。改返回零值 + needsPdf=true，UI 走 needsPdf 分支显示"-"，
            // 既不会让 app 崩溃（压缩包内层 DWG 仍会被统计、仅显示"-"），也不会误导用户。
            Log.w("WordCount", "DWG process 异常兜底 $dName: ${e.javaClass.simpleName}: ${e.message}")
            DwgProcessResult(0, 0, 0, 0, 1, "异常兜底", true, "process异常: ${e.message}", null, "")
        }
    }
    private suspend fun processInner(context: Context, file: File, dName: String): DwgProcessResult {
        // ── v1.9.12: 主路径改为 Python cad_core（ezdxf 同源，与桌面 wordcount.py 逐字节对齐）──
        //   v1.9.11 实测：Python 路径在部分手机上抛异常后回退到 Kotlin 旧解析链，
        //   旧链对 LibreDWG 损坏 DXF 输出严重虚高（00003 桌面 457 → Kotlin 3746）。
        //   因此 v1.9.12 规定：DWG 字数只能来自 Python；任何失败都显示"-"（needsPdf=true），
        //   绝不再回退 Kotlin。同时保留原 Kotlin 链代码供调试用，但默认不再进入。
        val pyDxfPath = "${file.parent}/${file.nameWithoutExtension}.dxf"
        try {
            val pyDxfRes = DwgIsolatedRunner.convertToDxf(context, file.absolutePath, pyDxfPath)
            if (pyDxfRes.path != null) {
                val pyDxfFile = File(pyDxfPath)
                if (pyDxfFile.exists() && pyDxfFile.length() > 0 && isDxfComplete(pyDxfPath)) {
                    val pyJson = PythonEngine.extractCadDxf(context, pyDxfPath, file.absolutePath)
                    val obj = JSONObject(pyJson)
                    val pyError = if (obj.has("error") && !obj.isNull("error")) obj.optString("error") else null
                    if (!pyError.isNullOrBlank()) {
                        Log.e("WordCount", "DWG Python 返回错误 $dName: ${pyError.take(200)}")
                    }
                    val arr = obj.getJSONArray("items")
                    val items = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) items.add(arr.getString(i))
                    val pyPages = if (obj.has("pages") && !obj.isNull("pages")) obj.getInt("pages") else 1
                    val pyPagesReason = obj.optString("pages_reason")
                    var pyNeedsPdf = obj.optBoolean("needs_pdf", false)
                    // 若 Python 成功运行但提取到 0 字（DXF 结构损坏无法解析），也标记 needsPdf，避免 Kotlin 虚高
                    if (items.isEmpty() && !pyNeedsPdf) pyNeedsPdf = true
                    // OLE 合并：office 嵌入文字走 Python；位图 OLE 兜底走 Kotlin ML Kit OCR
                    val allItems = ArrayList(items)
                    val oleMarks = ArrayList<String>()
                    var oleOfficeOk = false
                    try {
                        val oleJson = PythonEngine.extractOleOffice(context, pyDxfPath)
                        val oo = JSONObject(oleJson)
                        val oleErr = if (oo.has("error") && !oo.isNull("error")) oo.optString("error") else null
                        if (!oleErr.isNullOrBlank()) {
                            Log.e("WordCount", "DWG OLE-office 返回错误 $dName: ${oleErr.take(200)}")
                        }
                        val joined = oo.optString("joined", "")
                        if (joined.isNotBlank()) {
                            for (ln in joined.lines()) { val t = ln.trim(); if (t.isNotEmpty()) allItems.add(t) }
                            oleMarks.add("OLE-office")
                            oleOfficeOk = true
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "DWG OLE-office(Python)失败 $dName: ${e.message}")
                    }
                    if (!oleOfficeOk) {
                        try {
                            val oleRes = DwgOleExtractor.extractOleText(pyDxfPath)
                            if (oleRes.text.isNotBlank()) {
                                for (ln in oleRes.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) allItems.add(t) }
                                oleMarks.add("OLE-ocr")
                            }
                        } catch (_: Throwable) {}
                        try {
                            val oleRes2 = DwgOleExtractor.extractOleTextFromDwg(file.absolutePath)
                            if (oleRes2.text.isNotBlank()) {
                                for (ln in oleRes2.text.lines()) { val t = ln.trim(); if (t.isNotEmpty()) allItems.add(t) }
                                oleMarks.add("DWG-OLE-ocr")
                            }
                        } catch (_: Throwable) {}
                    }
                    val co = JSONObject(PythonEngine.countCadItems(context, allItems))
                    val cntErr = if (co.has("error") && !co.isNull("error")) co.optString("error") else null
                    if (!cntErr.isNullOrBlank()) {
                        Log.e("WordCount", "DWG count_items 返回错误 $dName: ${cntErr.take(200)}")
                    }
                    val pyWords = co.optInt("words", 0)
                    val pyFe = co.optInt("fe", 0)
                    val pyNc = co.optInt("nc", 0)
                    val pyChars = co.optInt("chars", 0)
                    val pyReason = (pyPagesReason ?: "") + (if (oleMarks.isNotEmpty()) "·" + oleMarks.joinToString("·") else "")
                    Log.d("WordCount", "DWG Python主路径 $dName: words=$pyWords fe=$pyFe nc=$pyNc chars=$pyChars pages=$pyPages($pyReason) items=${items.size}")
                    return DwgProcessResult(pyWords, pyFe, pyNc, pyChars, pyPages, pyReason, pyNeedsPdf, pyReason, null, allItems.joinToString("\n"))
                }
            }
        } catch (e: Throwable) {
            Log.e("WordCount", "DWG Python主路径失败 $dName: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        // Python 路径失败 → 返回零值 + needsPdf=true，显示"-"，绝不再回退 Kotlin 旧链（会虚高）
        Log.w("WordCount", "DWG $dName 走 Python 失败保护：显示'-'")
        return DwgProcessResult(0, 0, 0, 0, 1, "Python解析失败", true, "Python解析失败", null, "")
            }

    private fun isDxfComplete(path: String): Boolean {
        return try {
            val f = java.io.RandomAccessFile(path, "r")
            val size = f.length()
            val n = minOf(size, 512L).toInt()
            f.seek(size - n)
            val buf = ByteArray(n)
            f.readFully(buf)
            f.close()
            val tail = String(buf, Charsets.UTF_8).trim()
            tail.endsWith("EOF")
        } catch (_: Throwable) { false }
    }

    /** v1.9.2: DXF 简易 gc=1/3 兜底抽取。
     *  当 DwgDxfParser 复杂结构化解析在某些真机上漏抽文字（DXF 含 3185 个 TEXT/MTEXT
     *  但 collectDxfTexts 返回空列表）时，直接按 DXF 组码 1/3 顺序对原始字节做
     *  GB18030/UTF-8 双解码抽取，覆盖 _collect_dxf_texts 漏抽的场景。 */
    /** v1.9.3: DXF 简易 gc=1/3 兜底抽取（流式 BufferedReader，避免大 DXF OOM）。
     *  当 DwgDxfParser 复杂结构化解析在某些真机上漏抽文字时，直接按 DXF 组码 1/3
     *  顺序对原始字节做 GB18030/UTF-8 双解码抽取。流式处理 200MB 以下文件不爆内存。 */
    private fun extractDxfTextsSimple(path: String): String {
        return try {
            val f = java.io.File(path)
            if (!f.exists() || f.length() <= 0 || f.length() > 200L * 1024 * 1024) return ""
            val out = StringBuilder()
            val seen = HashSet<String>()  // v1.9.6: 行级去重，防止块炸开编号膨胀
            var curType: String? = null
            java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(f), Charsets.ISO_8859_1)).use { br ->
                var code = br.readLine()
                while (code != null) {
                    val value = br.readLine() ?: break
                    val gc = code.trim()
                    if (gc == "0") { curType = value.trim(); code = br.readLine(); continue }
                    if ((gc in setOf("1", "3", "7", "9", "304", "302")) && curType in setOf("TEXT", "MTEXT", "ATTDEF", "ATTRIB", "MULTILEADER")) {
                        val s = value.trim()
                        if (s.isNotEmpty()) {
                            val b = s.toByteArray(Charsets.ISO_8859_1)
                            val u8 = try { String(b, 0, b.size, Charsets.UTF_8) } catch (_: Throwable) { s }
                            val gb = try { String(b, 0, b.size, charset("GB18030")) } catch (_: Throwable) { s }
                            val decoded = if (countOfFarEast(gb) >= countOfFarEast(u8)) gb else u8
                            if (seen.add(decoded)) {
                                out.append(decoded).append("\n")
                            }
                        }
                    }
                    code = br.readLine()
                }
            }
            out.toString()
        } catch (_: Throwable) { "" }
    }
    private fun countOfFarEast(s: String): Int {
        var n = 0
        for (c in s) {
            val cp = c.code
            if (cp in 0x4E00..0x9FFF || cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF) n++
        }
        return n
    }
    private fun keepFarEastOnly(text: String): String {
        return text.filter { it.isFarEastForDwg() }
    }

    private fun Char.isFarEastForDwg(): Boolean {
        val c = code
        return c in 0x1100..0x11FF ||       // Hangul Jamo
               c in 0x3000..0x303F ||       // CJK 符号与标点
               c in 0x3130..0x318F ||       // Hangul Compatibility Jamo
               c in 0x3400..0x4DBF ||       // CJK Extension A
               c in 0x4E00..0x9FFF ||       // CJK Unified Ideographs
               c in 0xA960..0xA97C ||       // Hangul Jamo Extended-A
               c in 0xAC00..0xD7A3 ||       // Hangul Syllables
               c in 0xD7B0..0xD7FF ||       // Hangul Jamo Extended-B
               c in 0xF900..0xFAFF ||       // CJK Compatibility Ideographs
               c in 0xFF00..0xFFEF          // Fullwidth forms
    }
}
