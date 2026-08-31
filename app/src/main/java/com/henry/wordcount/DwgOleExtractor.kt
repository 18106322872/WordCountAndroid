package com.henry.wordcount

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * v1.5.93: 从 DWG 转换出的 DXF 中抽取 OLE2FRAME 嵌入对象的预览位图并 OCR，
 * 用以恢复桌面版 WordCount 的「OLE 嵌入文本」口径（fe 中文主要来自这里）。
 *
 * 背景（端口自桌面 cad_ole_ocr.py + 已验证的 cfb_proto.py）：
 *   - AutoCAD 把粘贴进来的对象存成 OLE2FRAME 实体；其组码 310–319 的 hex 数据
 *     拼回就是一个 CFB（OLE2 复合文档）。
 *   - CFB 内含 CONTENTS / OlePres000 等流，保存该对象的预览位图（BMP 或 DIB）。
 *   - 桌面把这些位图渲染后 OCR，得到 dwg2dxf 矢量文字丢失的中文（fe）。
 *   手机无 dwggrep，故以「预览位图 → BitmapFactory 解码 → ML Kit OCR」作为等效来源。
 *
 * 全程异常隔离：任一 blob / 流 / 位图 / OCR 失败都只跳过该片段，绝不让整体崩溃
 * （否则会触发 DwgProcessor 抛异常 → 内层 DWG 被压缩包引擎静默丢弃）。
 */
object DwgOleExtractor {

    private const val TAG = "WordCount"

    data class OleExtractResult(
        val text: String,        // 合并后的 OCR 文本（按行去重，近似桌面「合并同类粘贴对象」）
        val objects: Int,         // 发现的 OLE2FRAME 实体数
        val bitmapsOcred: Int     // 实际 OCR 的位图数
    )

    private val CFB_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )
    private val BINARY_GROUPS = setOf(
        "310", "311", "312", "313", "314", "315", "316", "317", "318", "319"
    )
    private val PRES_NAMES = listOf("CONTENTS", "OlePres000", "OlePres001", "OlPres000", "OlePres")
    // v1.9.80: 20 → 6。单个 DWG 最多 OCR 的预览位图数。
    // 旧值 20 配合「1920 不够 30 字再跑 2560」的双档策略，单文件最坏要跑 40+ 次 PaddleOCR
    // （每张 2560px 位图约 10MB），实测单文件耗时 12~17 分钟并在第 5 个文件 OOM 崩溃。
    // 实测 OLE 预览图之间文字高度重复（addLines 按行去重），6 张已能覆盖绝大部分唯一文本，
    // 而 OLE 通道对中文(fe)的实际贡献仅 0~4 字，削减的收益远大于损失。
    // v1.9.81: 6 → 12。采样解码（见 decodeBmpManual）把单张 OCR 从 ~50s 降到亚秒级，
    // 恢复 v1.9.80 因削减位图上限而丢失的 OLE 字数（FA-00003 曾 2994→1711），
    // 同时保留下方 OLE_DEADLINE_MS 墙钟截止作为最坏情况保护。
    private const val MAX_BITMAPS_PER_FILE = 12
    // v1.9.81: OLE 全程墙钟截止。FA-00003 实测 oleOcr=315s（大 DXF 逐行扫描+多张 OCR），
    // 超时即停止继续扫后续 blob / 位图，保证单文件 OLE 阶段不再拖到分钟级。
    private const val OLE_DEADLINE_MS = 120_000L
    private const val MAX_OCR_TEXT_CHARS = 12000

    // ─────────── v1.9.62: OLE 收获通用逻辑（office / EMF矢量文字 / 位图OCR 三路并存） ───────────
    private val EMF_SIG = byteArrayOf(0x20, 0x45, 0x4D, 0x46)   // " EMF" 位于 EMF 头偏移 40
    private val ZIP_SIG = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** 一次抽取过程内的去重/合并状态（跨 blob 共享，用于合并"同一对象粘贴多次"）。 */
    private class HarvestState {
        val acceptedFragSets = mutableListOf<Set<String>>()
        val allLines = LinkedHashSet<String>()
        var bitmapsOcred = 0
    }

    /** 把一段文本按行并入状态；与已接受对象高度相似则返回 false（视为重复粘贴）。 */
    private fun addLines(text: String, st: HarvestState, minLen: Int): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.length >= minLen }
        if (lines.isEmpty()) return false
        val fragSet = lines.toSet()
        if (st.acceptedFragSets.any { jaccard(it, fragSet) >= 0.9 }) return false
        st.acceptedFragSets.add(fragSet)
        for (ln in lines) st.allLines.add(ln)
        return true
    }

    /** 在任意字节流里定位 EMF 起始（dSignature " EMF" 位于 EMF 头偏移 40，且 iType==1）。 */
    private fun findEmfStart(data: ByteArray): Int {
        if (data.size < 48) return -1
        var i = 40
        val end = data.size - 4
        while (i <= end) {
            if (data[i] == EMF_SIG[0] && data[i + 1] == EMF_SIG[1] &&
                data[i + 2] == EMF_SIG[2] && data[i + 3] == EMF_SIG[3]) {
                val start = i - 40
                if (start >= 0 && u32(data, start) == 1) return start
            }
            i++
        }
        return -1
    }

    /**
     * v1.9.62: 直接从 EMF 矢量记录里抽文字（EMR_EXTTEXTOUTW / EMR_EXTTEXTOUTA）。
     * 桌面用 Win32 GDI 把 EMF 渲染成 PNG 再 OCR；移动端没有 GDI，但粘贴进 CAD 的
     * Excel/Word 内容在 EMF 里本身就是 Unicode 文字记录，直接解析比"渲染+OCR"更准、
     * 更快、零识别损失。这是 FA-31018 这类"仅 OLE 矢量对象"DWG 在移动端只有个位数字的根因。
     */
    private fun extractEmfText(data: ByteArray, start0: Int): String {
        val out = LinkedHashSet<String>()
        var off = start0
        val n = data.size
        var guard = 0
        while (off + 8 <= n && guard < 200000 && out.size < MAX_OCR_TEXT_CHARS) {
            guard++
            val type = u32(data, off)
            val size = u32(data, off + 4)
            if (size < 8 || off + size > n) break
            if (type == 84 || type == 83) {           // EMR_EXTTEXTOUTW / EMR_EXTTEXTOUTA
                try {
                    // 布局：type(0) size(4) bounds(8,16B) iGraphicsMode(24) exScale(28) eyScale(32) → EmrText 始于 36
                    val et = off + 36
                    if (size >= 76 && et + 40 <= n) {
                        val chars = u32(data, et + 8)
                        val offString = u32(data, et + 12)
                        if (chars >= 1 && chars <= 4000 && offString >= 0) {
                            val sOff = et + offString
                            val nBytes = if (type == 84) chars * 2 else chars
                            if (sOff >= 0 && sOff + nBytes <= n) {
                                val s = if (type == 84)
                                    String(data, sOff, nBytes, Charsets.UTF_16LE)
                                else
                                    String(data, sOff, nBytes, Charsets.ISO_8859_1)
                                val t = s.trim()
                                if (t.isNotBlank()) out.add(t)
                            }
                        }
                    }
                } catch (_: Throwable) { /* 单条记录失败不影响整体 */ }
            }
            off += size
            if (size == 0) break
        }
        return out.joinToString("\n")
    }

    /** 从任意字节里按 PK 头定位并解 ZIP 抽 Office 文本（不依赖 CFB 目录，抗目录损坏）。 */
    private fun extractTextFromZipAt(data: ByteArray): String? {
        val idx = indexOfBytesFrom(data, ZIP_SIG, 0)
        if (idx < 0) return null
        return try {
            extractTextFromZip(data.copyOfRange(idx, data.size))
        } catch (_: Throwable) { null }
    }

    /** 判定 CFB 流名是否像"预览/内容"流（名称常带大小写与不可见前缀差异）。 */
    private fun isPresOrContentName(name: String): Boolean {
        val n = name.lowercase()
        if (PRES_NAMES.any { n.contains(it.lowercase()) }) return true
        return n.contains("package") || n.contains("contents") || n.contains("content")
            || n.contains("olepres") || n.contains("olpres") || n.contains("ole10native")
            || n.contains("native") || n.contains("pres")
    }

    /**
     * v1.9.62: 单个 OLE 对象的统一收获入口。
     * 三路并存（此前 office 命中会 continue 掉 OCR，FA-00003 类位图漏字即由此而来）：
     *   ① Office Package(ZIP) 文本 —— 100% 准确，最优先；
     *   ② EMF 矢量文字记录直取     —— 无 GDI 也能拿到粘贴对象的真实文字；
     *   ③ 预览位图 OCR             —— 兜底（1920/2560 双档放大）。
     * 任一路径异常都只跳过该路径，绝不外抛（否则 DwgProcessor 会整文件失败）。
     */
    private fun harvestBlob(blob: ByteArray, context: Context?, maxBitmaps: Int, st: HarvestState) {
        try {
            val cfb = Cfb(blob)
            // ① Office Package
            val officeText = extractOfficeTextFromCfb(cfb)
            if (!officeText.isNullOrBlank()) addLines(officeText, st, 1)
            // ② EMF 矢量文字（每个候选流都试，命中即止）
            for (name in cfb.listNames()) {
                if (!isPresOrContentName(name)) continue
                val stream = cfb.getStream(name) ?: continue
                val emfStart = findEmfStart(stream)
                if (emfStart < 0) continue
                val t = extractEmfText(stream, emfStart)
                if (t.isNotBlank()) { addLines(t, st, 2); break }
            }
            // ③ 预览位图 OCR（不再被 office/EMF 命中互斥掉）
            for (name in cfb.listNames()) {
                if (st.bitmapsOcred >= maxBitmaps) break
                if (!isPresOrContentName(name)) continue
                val stream = cfb.getStream(name) ?: continue
                val (bmpBytes, _) = findBitmap(stream) ?: continue
                val text = ocrBitmap(bmpBytes, context) ?: continue
                if (text.isBlank()) continue
                if (addLines(text, st, 2)) st.bitmapsOcred++
            }
            return
        } catch (_: Throwable) { /* CFB 解析失败 → 走下方原始字节兜底 */ }

        // 原始字节兜底：CFB 目录损坏/魔数误命中时仍能靠 magic 抢救
        try {
            val zipText = extractTextFromZipAt(blob)
            if (!zipText.isNullOrBlank()) addLines(zipText, st, 1)
            val emfStart = findEmfStart(blob)
            if (emfStart >= 0) {
                val t = extractEmfText(blob, emfStart)
                if (t.isNotBlank()) addLines(t, st, 2)
            }
            if (st.bitmapsOcred < maxBitmaps) {
                val bm = findBitmap(blob)
                if (bm != null) {
                    val text = ocrBitmap(bm.first, context)
                    if (!text.isNullOrBlank() && addLines(text, st, 2)) st.bitmapsOcred++
                }
            }
        } catch (_: Throwable) {}
    }

    /** 带起始下标的 indexOfBytes（用于大缓冲区连续扫描 CFB/EMF/ZIP 魔数）。 */
    private fun indexOfBytesFrom(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        if (needle.size > haystack.size) return -1
        var i = maxOf(0, from)
        while (i <= haystack.size - needle.size) {
            var j = 0
            while (j < needle.size) {
                if (haystack[i + j] != needle[j]) break
                j++
            }
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    /**
     * 从 DXF 中抽取所有 OLE2FRAME 嵌入对象的文本。
     * 顺序：① office 嵌入（xlsx/docx/pptx Package 流 → ZIP → 文本）；② 预览位图 OCR。
     * @param dxfPath dwg→dxf 转换产物路径（不存在时直接返回空，绝不影响主流程）
     */
    fun extractOleText(dxfPath: String, maxBitmaps: Int = MAX_BITMAPS_PER_FILE, context: Context? = null): OleExtractResult {
        return try {
            val deadline = System.currentTimeMillis() + OLE_DEADLINE_MS
            val blobs = findOleBlobs(dxfPath, deadline)
            if (blobs.isEmpty()) return OleExtractResult("", 0, 0)

            // v1.9.62: 统一走 harvestBlob——office / EMF矢量文字 / 位图OCR 三路并存
            val st = HarvestState()
            for (blob in blobs) {
                if (st.allLines.size >= MAX_OCR_TEXT_CHARS) break
                // v1.9.81: 墙钟截止，最坏情况不再拖到分钟级
                if (System.currentTimeMillis() > deadline) {
                    Log.d(TAG, "DwgOleExtractor OLE 截止时间已到，停止处理剩余 ${blobs.size - blobs.indexOf(blob) - 1} 个 blob")
                    break
                }
                try {
                    harvestBlob(blob, context, maxBitmaps, st)
                } catch (e: Throwable) {
                    Log.d(TAG, "DwgOleExtractor blob 解析失败(跳过): ${e.message}")
                }
            }
            OleExtractResult(st.allLines.joinToString("\n"), blobs.size, st.bitmapsOcred)
        } catch (e: Throwable) {
            Log.w(TAG, "DwgOleExtractor.extractOleText 失败: ${e.message}")
            OleExtractResult("", 0, 0)
        }
    }

    /**
     * v1.9.9: 直接从 DWG 二进制扫描 CFB（OLE2 复合文档）—— LibreDWG→DXF 在 Android 上经常
     * 不保留 OLE2FRAME 实体，导致 dxfPath 路径拿不到 OLE。直接扫 DWG 文件二进制可补救此情况，
     * 也能在 dwg2dxf 转换失败时（31003-31035 现象）作为 OLE 兜底来源。
     */
    fun extractOleTextFromDwg(dwgPath: String, maxScans: Int = 64, context: Context? = null): OleExtractResult {
        return try {
            val file = File(dwgPath)
            if (!file.exists() || file.length() < 1024L) return OleExtractResult("", 0, 0)
            val size = file.length().toInt()
            val buf = ByteArray(size)
            val nRead = try { java.io.FileInputStream(file).use { it.read(buf) } } catch (e: Throwable) { return OleExtractResult("", 0, 0) }
            if (nRead < 512) return OleExtractResult("", 0, 0)

            // v1.9.62: 统一走 harvestBlob（office / EMF矢量文字 / 位图OCR 三路并存）
            val st = HarvestState()
            var cfbCount = 0
            var i = 0
            val end = nRead - 8
            while (i < end && cfbCount < maxScans && st.allLines.size < MAX_OCR_TEXT_CHARS) {
                val found = indexOfBytesFrom(buf, CFB_MAGIC, i)
                if (found < 0 || found >= end) break
                // 限制 slice 大小，避免超大 DWG 反复 copyOfRange 造成内存压力
                val sliceEnd = (found.toLong() + 32L * 1024 * 1024).coerceAtMost(nRead.toLong()).toInt()
                val slice = buf.copyOfRange(found, sliceEnd)
                var parsed = false
                try {
                    Cfb(slice)   // 校验是否合法 CFB
                    parsed = true
                } catch (_: Throwable) {
                    parsed = false
                }
                if (parsed) {
                    try { harvestBlob(slice, context, 2, st) } catch (e: Throwable) {
                        Log.d(TAG, "DwgOleExtractor CFB 收获失败(跳过): ${e.message}")
                    }
                    cfbCount++
                    i = found + 512
                } else {
                    // 不是合法 CFB：仍尝试原始字节抢救（ZIP / EMF / DIB）
                    try {
                        val zipText = extractTextFromZipAt(slice)
                        if (!zipText.isNullOrBlank()) addLines(zipText, st, 1)
                        val emfStart = findEmfStart(slice)
                        if (emfStart >= 0) {
                            val t = extractEmfText(slice, emfStart)
                            if (t.isNotBlank()) addLines(t, st, 2)
                        }
                        if (st.bitmapsOcred < 2) {
                            val bm = findBitmap(slice)
                            if (bm != null) {
                                val text = ocrBitmap(bm.first, context)
                                if (!text.isNullOrBlank() && addLines(text, st, 2)) st.bitmapsOcred++
                            }
                        }
                    } catch (_: Throwable) {}
                    i = found + 1
                }
            }
            OleExtractResult(st.allLines.joinToString("\n"), cfbCount, st.bitmapsOcred)
        } catch (e: Throwable) {
            Log.w(TAG, "DwgOleExtractor.extractOleTextFromDwg 失败: ${e.message}")
            OleExtractResult("", 0, 0)
        }
    }

    // ZIP magic
    private val ZIP_MAGIC = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte())
    // Office 包内可能含文本的 XML 路径前缀
    private val OFFICE_XML_HINTS = listOf(
        "sharedstrings.xml",   // xlsx
        "document.xml",        // docx
        "slides/slide",        // pptx slides
        "noteslide",           // pptx notes
        "comments.xml"         // xlsx/docx comments
    )

    /**
     * v1.9.9: 从 CFB 内抽取 Office Package 流（xlsx/docx/pptx）→ 解 ZIP → 抽文本。
     * 对齐桌面 cad_ole_ocr.py 的 office 优先路径。无 office 流或 ZIP 损坏时返回 null。
     */
    private fun extractOfficeTextFromCfb(cfb: Cfb): String? {
        // v1.9.62: 流名不再硬匹配——CFB 目录名常带大小写/不可见前缀差异（Package / package /
        // \u0001Package / CONTENTS 等）。按"名称含 package 优先、其次含 contents"模糊取流，
        // 且必须校验 PK 头；取不到再退回精确旧名，最后再对整段字节做 PK 扫描。
        val names = cfb.listNames()
        fun pick(vararg hints: String): ByteArray? {
            for (h in hints) {
                for (n in names) {
                    if (!n.lowercase().contains(h)) continue
                    val b = cfb.getStream(n) ?: continue
                    if (b.size >= 4 && b[0] == ZIP_MAGIC[0] && b[1] == ZIP_MAGIC[1] &&
                        b[2] == ZIP_MAGIC[2] && b[3] == ZIP_MAGIC[3]) return b
                }
            }
            return null
        }
        val pkgBytes = pick("package") ?: pick("contents", "content")
            ?: cfb.getStream("Package") ?: cfb.getStream("package")
            ?: cfb.getStream("Contents") ?: cfb.getStream("CONTENTS")
            ?: cfb.getStream("CONTENTS\u0000") ?: return null
        if (pkgBytes.size < 4) return null
        if (pkgBytes[0] != ZIP_MAGIC[0] || pkgBytes[1] != ZIP_MAGIC[1] ||
            pkgBytes[2] != ZIP_MAGIC[2] || pkgBytes[3] != ZIP_MAGIC[3]) return null
        return extractTextFromZip(pkgBytes)
    }

    /**
     * 解 ZIP 字节，扫所有 entry 名匹配 office 文本 entry 的 XML 内容，用正则提取 `<...t...>([^<]+)</...t...>` 标签。
     * 单 entry 文本超 50000 字截断（防御性），整 ZIP 总文本超 MAX_OCR_TEXT_CHARS 截断。
     */
    private fun extractTextFromZip(zipBytes: ByteArray): String? {
        return try {
            val out = LinkedHashSet<String>()
            val maxTotal = MAX_OCR_TEXT_CHARS
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zis ->
                var ent = zis.nextEntry
                while (ent != null) {
                    val name = ent.name.lowercase()
                    val isOfficeXml = OFFICE_XML_HINTS.any { name.contains(it) }
                    if (isOfficeXml && !ent.isDirectory) {
                        val raw = zis.readBytes()
                        val text = extractTextFromOfficeXml(raw)
                        if (!text.isNullOrBlank()) {
                            for (ln in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                                if (out.size >= maxTotal) break
                                out.add(ln)
                            }
                        }
                    }
                    ent = zis.nextEntry
                }
            }
            if (out.isEmpty()) null else out.joinToString("\n")
        } catch (e: Throwable) {
            Log.d(TAG, "DwgOleExtractor.extractTextFromZip 失败: ${e.message}")
            null
        }
    }

    /**
     * 从 Office XML 字节中抽所有 `<w:t>...</w:t>`、`<t>...</t>`、`<a:t>...</a:t>` 等文本标签内容。
     * 用宽匹配 regex：`<[a-zA-Z0-9_:]+t[^>]*>([^<]+)</[a-zA-Z0-9_:]+t>` 与简单 `<t>([^<]+)</t>`。
     */
    private fun extractTextFromOfficeXml(xmlBytes: ByteArray): String? {
        return try {
            val xml = String(xmlBytes, Charsets.UTF_8)
            val sb = StringBuilder()
            // 1) 任意带前缀的 <...:t...>...</...:t>（docx/pptx 标准）
            val re1 = Regex("<[A-Za-z0-9_:]+t[^>]*>([^<]+)</[A-Za-z0-9_:]+t>")
            for (m in re1.findAll(xml)) {
                val v = m.groupValues[1].trim()
                if (v.isNotEmpty()) sb.append(v).append('\n')
            }
            // 2) 简单 <t>...</t>（xlsx sharedStrings）
            if (sb.isEmpty()) {
                val re2 = Regex("<t[^>]*>([^<]+)</t>")
                for (m in re2.findAll(xml)) {
                    val v = m.groupValues[1].trim()
                    if (v.isNotEmpty()) sb.append(v).append('\n')
                }
            }
            if (sb.isEmpty()) null else sb.toString()
        } catch (e: Throwable) {
            null
        }
    }

    // ───────────────────────── DXF 扫描：OLE2FRAME 310–319 ─────────────────────────

    /** 流式扫描 DXF，收集每个 OLE2FRAME 实体的 310–319 hex → 还原 CFB 字节。 */
    private fun findOleBlobs(dxfPath: String, deadlineMs: Long = Long.MAX_VALUE): List<ByteArray> {
        val file = File(dxfPath)
        if (!file.exists() || file.length() == 0L) return emptyList()
        val blobs = mutableListOf<ByteArray>()
        try {
            val reader = file.bufferedReader(Charsets.UTF_8)
            var state = 0          // 0=期待组码行, 1=期待值行
            var curCode = ""
            var curValue = ""
            var inOle = false
            var hexParts = mutableListOf<String>()
            // v1.9.81: 手动迭代替代 forEachLine，可在大 DXF 逐行扫描中途检查墙钟截止
            // （FA-00003 类 222MB DXF 全文扫描本身就要数分钟）
            reader.useLines { lines ->
                val it = lines.iterator()
                scan@ while (it.hasNext()) {
                    val raw = it.next()
                    if (System.currentTimeMillis() > deadlineMs) break@scan
                    val line = raw.trim()
                    if (line.isEmpty()) continue@scan
                if (state == 0) {
                    curCode = line
                    state = 1
                } else {
                    curValue = line
                    state = 0
                    if (inOle) {
                        if (curCode == "0") {
                            flushOle(hexParts, blobs)
                            inOle = false
                            hexParts = mutableListOf()
                            if (curValue.equals("OLE2FRAME", ignoreCase = true)) {
                                inOle = true
                                hexParts = mutableListOf()
                            }
                        } else if (curCode in BINARY_GROUPS && curValue.isNotEmpty()) {
                            hexParts.add(curValue.replace(" ", ""))
                        }
                    } else {
                        if (curCode == "0" && curValue.equals("OLE2FRAME", ignoreCase = true)) {
                            inOle = true
                            hexParts = mutableListOf()
                        }
                    }
                }
                }
            }
            if (inOle) flushOle(hexParts, blobs)
        } catch (e: Throwable) {
            Log.w(TAG, "DwgOleExtractor.findOleBlobs 失败: ${e.message}")
        }
        return blobs
    }

    private fun flushOle(hexParts: List<String>, out: MutableList<ByteArray>) {
        if (hexParts.isEmpty()) return
        val joined = hexParts.joinToString("")
        val data = unhex(joined) ?: return
        if (data.size < CFB_MAGIC.size) return
        val m = indexOfBytes(data, CFB_MAGIC)
        val blob = if (m >= 0) data.copyOfRange(m, data.size) else data
        if (blob.size >= 512) out.add(blob)
    }

    /** 在 haystack 中查找子数组 needle，返回起始下标；未找到返回 -1（不依赖可能缺失的 ByteArray.indexOf）。 */
    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        if (needle.size > haystack.size) return -1
        var i = 0
        while (i <= haystack.size - needle.size) {
            var j = 0
            while (j < needle.size) {
                if (haystack[i + j] != needle[j]) break
                j++
            }
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    // ───────────────────────── 位图恢复 ─────────────────────────

    /**
     * 从一段字节里找位图：整段以 "BM" 开头即 BMP；否则扫描 BITMAPINFOHEADER(DIB)
     * 并补 14 字节文件头转成标准 BMP。返回 (bmpBytes, kind) 或 null。
     */
    private fun findBitmap(data: ByteArray): Pair<ByteArray, String>? {
        if (data.size < 2) return null
        if (data[0] == 0x42.toByte() && data[1] == 0x4D.toByte()) {
            return Pair(data, "bmp")
        }
        var i = 0
        while (i + 40 <= data.size) {
            val bsize = u32(data, i)
            if (bsize in listOf(40, 56, 108, 124)) {
                val w = i32(data, i + 4)
                val h = i32(data, i + 8)
                val planes = u16(data, i + 12)
                val bpp = u16(data, i + 14)
                val absW = kotlin.math.abs(w)
                val absH = kotlin.math.abs(h)
                if (absW in 1..20000 && absH in 1..20000 && planes == 1 && bpp in listOf(1, 4, 8, 16, 24, 32)) {
                    val dib = data.copyOfRange(i, data.size)
                    val colorTable = if (bpp <= 8) {
                        val clrUsed = u32(data, i + 32)
                        val n = if (clrUsed > 0) clrUsed else (1 shl bpp)
                        n * 4
                    } else 0
                    val dataOffset = 14 + bsize + colorTable
                    val filesize = 14 + dib.size
                    // 手工拼 14 字节 BMP 文件头（不依赖 ByteArray.plus），再拼接 DIB
                    val header = ByteArray(14)
                    header[0] = 0x42.toByte(); header[1] = 0x4D.toByte()
                    val fsBytes = writeU32(filesize)
                    header[2] = fsBytes[0]; header[3] = fsBytes[1]; header[4] = fsBytes[2]; header[5] = fsBytes[3]
                    // 偏移 6,7 保留字 = 0
                    val doBytes = writeU32(dataOffset)
                    header[10] = doBytes[0]; header[11] = doBytes[1]; header[12] = doBytes[2]; header[13] = doBytes[3]
                    val outBytes = ByteArray(header.size + dib.size)
                    System.arraycopy(header, 0, outBytes, 0, header.size)
                    System.arraycopy(dib, 0, outBytes, header.size, dib.size)
                    return Pair(outBytes, "dib")
                }
            }
            i++
        }
        return null
    }

    // ───────────────────────── OCR ─────────────────────────

    private fun ocrBitmap(bmpBytes: ByteArray, context: Context? = null): String? {
        // v1.9.54: OLE 预览位图 OCR 主引擎改为 PaddleOCR（与 v1.9.53 的 DWG IMAGE OCR 修复一致）。
        // OLE 预览图多为粘贴进来的中文规格书/图框（CONTENTS 流里的 32-bit BMP），ML Kit 中文识别器
        // 偏弱且 postFilter 易误剔真实中文（这正是 FA-31018 等"仅 OLE 位图"DWG 在移动端 0 字的根因）；
        // PaddleOCR(PP-OCRv4) 与桌面 RapidOCR 同宗，保留全部中文字符，对齐桌面口径。
        // PaddleOcr.available==false（模型缺失/初始化失败）时自动回退 ML Kit，零回归。
        var bmp = BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size)
        if (bmp == null) bmp = decodeBmpManual(bmpBytes)
        if (bmp == null) return null
        return try {
            // v1.9.62: 双档放大识别。小预览图（如 544×209）在 1920 档仍可能字太细，
            // 首档结果过短（<30 字）时再用 2560 档重试一次，取更丰富的一份。
            val t1 = recognizeAt(bmp, 1920, context)
            val len1 = t1?.trim()?.length ?: 0
            // v1.9.80: 只要 1920 档识别出任何文字就直接采用，不再为「补足 30 字」再跑一次 2560 档。
            // 2560 档单张位图约 10MB 且 PaddleOCR 耗时翻倍，是单文件 40+ 次 OCR 与 OOM 崩溃的主因；
            // 改为仅在首档完全识别不出文字（0 字）时才升级到 2560 档重试，保留小图细字的兜底能力。
            if (len1 > 0) return t1
            val t2 = recognizeAt(bmp, 2560, context)
            val len2 = t2?.trim()?.length ?: 0
            if (len2 > len1) t2 else (t1 ?: t2)
        } catch (e: Throwable) {
            Log.w(TAG, "DwgOleExtractor.ocrBitmap 失败: ${e.message}")
            null
        } finally {
            try { bmp.recycle() } catch (_: Throwable) {}
        }
    }

    /** 按目标最长边缩放后识别（PaddleOCR 优先，不可用时回退 ML Kit）。 */
    private fun recognizeAt(src: Bitmap, targetDim: Int, context: Context?): String? {
        val scaled = scaleToLong(src, targetDim)
        return try {
            if (context != null) {
                PaddleOcr.ensureInit(context)
                if (PaddleOcr.available) PaddleOcr.recognize(scaled)
                else OcrEngine.recognizeBitmap(scaled, true)
            } else {
                OcrEngine.recognizeBitmap(scaled, true)
            }
        } finally {
            if (scaled !== src) { try { scaled.recycle() } catch (_: Throwable) {} }
        }
    }

    /**
     * v1.9.51: 手动解码 BMP，作为 BitmapFactory.decodeByteArray 的回退。
     * Android BitmapFactory 对 32-bit BI_BITFIELDS（AutoCAD OLE 预览常见）常返回 null，
     * 导致 OCR 拿不到图、字数归零（FA-31018 类"仅 CONTENTS 位图"OLE）。
     * 对齐桌面 PIL：支持 24-bit BI_RGB 与 32-bit BI_RGB/BI_BITFIELDS，逐像素转 ARGB_8888。
     */
    private fun decodeBmpManual(data: ByteArray): Bitmap? {
        return try {
            val hasFileHeader = data.size >= 2 && data[0] == 0x42.toByte() && data[1] == 0x4D.toByte()
            val dibOff = if (hasFileHeader) 14 else 0
            if (data.size < dibOff + 40) return null
            val headerSize = u32(data, dibOff)
            if (headerSize < 40 || headerSize > 124) return null
            val w = i32(data, dibOff + 4)
            val h = i32(data, dibOff + 8)
            val absW = kotlin.math.abs(w)
            val absH = kotlin.math.abs(h)
            if (absW <= 0 || absH <= 0 || absW > 20000 || absH > 20000) return null
            val planes = u16(data, dibOff + 12)
            if (planes != 1) return null
            val bpp = u16(data, dibOff + 14)
            // v1.9.62: 支持 1/4/8-bit 索引色预览图（此前只认 24/32，8-bit 预览直接解码失败 → 漏字）
            if (bpp != 1 && bpp != 4 && bpp != 8 && bpp != 24 && bpp != 32) return null
            val compression = u32(data, dibOff + 16)
            if (compression != 0 && compression != 3) return null
            val colorTableSize = if (bpp <= 8) {
                val clrUsed = u32(data, dibOff + 32)
                val n = if (clrUsed > 0) clrUsed else (1 shl bpp)
                n * 4
            } else 0
            val maskOff = dibOff + headerSize
            val pixelOff = if (hasFileHeader) u32(data, 10) else (maskOff + colorTableSize)
            if (pixelOff <= 0 || pixelOff >= data.size) return null
            val stride = if (bpp == 32) absW * 4 else ((bpp * absW + 31) / 32) * 4
            val needed = stride * absH
            if (pixelOff + needed > data.size) return null

            val rMask = if (bpp == 32 && compression == 3) u32(data, maskOff) else 0x00FF0000
            val gMask = if (bpp == 32 && compression == 3) u32(data, maskOff + 4) else 0x0000FF00
            val bMask = if (bpp == 32 && compression == 3) u32(data, maskOff + 8) else 0x000000FF
            val rSh = rShift(rMask); val gSh = rShift(gMask); val bSh = rShift(bMask)

            // v1.9.62: 索引色（1/4/8-bit）调色板：位于 DIB 头之后，每项 4 字节 BGRA
            val palette = if (bpp <= 8) {
                val n = 1 shl bpp
                val pal = IntArray(n)
                var k = 0
                while (k < n) {
                    val p = maskOff + k * 4
                    val bb = if (p + 2 < data.size) data[p].toInt() and 0xFF else 0
                    val gg = if (p + 1 < data.size) data[p + 1].toInt() and 0xFF else 0
                    val rr = if (p < data.size) data[p + 2].toInt() and 0xFF else 0
                    pal[k] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                    k++
                }
                pal
            } else IntArray(0)

            // v1.9.81: 巨型预览图采样解码。AutoCAD OLE 预览图最大可达 20000×20000（4 亿像素），
            // 此前逐像素 Kotlin 循环 + IntArray 全量分配，单张耗时 50 秒以上且分配数百 MB
            // （FA-00003 单文件 oleOcr=315s 与第 5 个文件 OOM 崩溃的主因之一）。
            // OCR 最终只需 1920 长边（约 2M 像素），采样到 ≤4M 像素对识别零损失。
            val totalPx = absW.toLong() * absH.toLong()
            val step = if (totalPx <= 4_000_000L) 1
                       else maxOf(2, kotlin.math.ceil(kotlin.math.sqrt(totalPx / 4_000_000.0)).toInt())
            val outW = (absW + step - 1) / step
            val outH = (absH + step - 1) / step
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(outW * outH)
            var idx = 0
            var row = 0
            while (row < absH) {
                // height 为正：底图行在文件最前，需翻转；为负：顶图向下
                val srcRow = if (h >= 0) (absH - 1 - row) else row
                val rowBase = pixelOff + srcRow * stride
                var col = 0
                while (col < absW) {
                    if (bpp <= 8) {
                        // 索引色：按位取出调色板下标（1/4/8-bit 打包，行按 4 字节对齐）
                        val bitPos = col * bpp
                        val byteOff = rowBase + (bitPos ushr 3)
                        if (byteOff < 0 || byteOff >= data.size) { pixels[idx++] = 0xFF000000.toInt(); col += step; continue }
                        val raw = data[byteOff].toInt() and 0xFF
                        val shift = 8 - bpp - (bitPos and 7)
                        val palIdx = (raw ushr shift) and ((1 shl bpp) - 1)
                        pixels[idx++] = if (palIdx < palette.size) palette[palIdx] else 0xFF000000.toInt()
                        col += step
                        continue
                    }
                    val px = if (bpp == 24) {
                        val off = rowBase + col * 3
                        if (off + 3 > data.size) 0
                        else {
                            val bb = data[off].toInt() and 0xFF
                            val gg = data[off + 1].toInt() and 0xFF
                            val rr = data[off + 2].toInt() and 0xFF
                            (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                        }
                    } else {
                        val off = rowBase + col * 4
                        if (off + 4 > data.size) 0
                        else {
                            val dw = (data[off].toInt() and 0xFF) or
                                     ((data[off + 1].toInt() and 0xFF) shl 8) or
                                     ((data[off + 2].toInt() and 0xFF) shl 16) or
                                     ((data[off + 3].toInt() and 0xFF) shl 24)
                            val rr = (dw and rMask) ushr rSh
                            val gg = (dw and gMask) ushr gSh
                            val bb = (dw and bMask) ushr bSh
                            (0xFF shl 24) or ((rr and 0xFF) shl 16) or ((gg and 0xFF) shl 8) or (bb and 0xFF)
                        }
                    }
                    pixels[idx++] = px
                    col += step
                }
                row += step
            }
            bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
            bmp
        } catch (e: Throwable) {
            Log.w(TAG, "DwgOleExtractor.decodeBmpManual 失败: ${e.message}")
            null
        }
    }

    /** 取最低置位位索引（用于 BI_BITFIELDS 通道掩码右移量）。mask=0 时返回 0 安全兜底。 */
    private fun rShift(mask: Int): Int {
        var m = mask
        var s = 0
        while (m != 0 && (m and 1) == 0) { m = m ushr 1; s++ }
        return s
    }

    /** 按最长边缩放/放大，对齐 PaddleOCR 的 detLongSize=1920，避免小预览图文字过细识别不出。 */
    private fun scaleToLong(src: Bitmap, targetDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val long = maxOf(w, h)
        if (long == targetDim) return src
        val scale = targetDim.toDouble() / long
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 1.0 else inter.toDouble() / union
    }

    private fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < out.size) {
            val hi = hexVal(s[i * 2])
            val lo = hexVal(s[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
            i++
        }
        return out
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun u16(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF)) or ((data[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or
        ((data[off + 1].toInt() and 0xFF) shl 8) or
        ((data[off + 2].toInt() and 0xFF) shl 16) or
        ((data[off + 3].toInt() and 0xFF) shl 24)

    private fun i32(data: ByteArray, off: Int): Int = u32(data, off)

    private fun writeU32(v: Int): ByteArray {
        return byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte()
        )
    }

    // ───────────────────────── CFB(OLE2 复合文档) 解析 ─────────────────────────
    // 端口自已验证的 cfb_proto.py：header / FAT / DIFAT / 目录 / mini-FAT。

    private const val ENDOFCHAIN = -2      // 0xFFFFFFFE
    private const val FREESECT = -1        // 0xFFFFFFFF
    private const val DIFSECT = -4
    private const val FATSECT = -3
    private const val NOSTREAM = -1

    private class Cfb(data: ByteArray) {
        val sectorShift: Int
        val sectorSize: Int
        val miniShift: Int
        val miniSize: Int
        val miniCutoff: Int
        val firstDir: Int
        val firstMini: Int
        val numMini: Int
        val fat = mutableListOf<Int>()
        val minifat = mutableListOf<Int>()
        val streams = mutableMapOf<String, Pair<Int, Int>>() // name -> (start, size)

        // 构造时把 data 存为字段，供 getStream / readMiniStream 在调用时访问。
        private val rawData: ByteArray = data

        init {
            require(data.size >= 512 &&
                data[0] == 0xD0.toByte() && data[1] == 0xCF.toByte() &&
                data[2] == 0x11.toByte() && data[3] == 0xE0.toByte() &&
                data[4] == 0xA1.toByte() && data[5] == 0xB1.toByte() &&
                data[6] == 0x1A.toByte() && data[7] == 0xE1.toByte()
            ) { "not a CFB/OLE2 file" }
            sectorShift = u16(data, 30)
            sectorSize = 1 shl sectorShift
            miniShift = u16(data, 32)
            miniSize = 1 shl miniShift
            miniCutoff = u32(data, 56)
            firstDir = u32(data, 48)
            firstMini = u32(data, 60)
            numMini = u32(data, 64)
            val firstDifat = u32(data, 68)
            val numDifat = u32(data, 72)
            buildFat(firstDifat, numDifat)
            buildMiniFat()
            walkDir()
        }

        private fun buildFat(firstDifat: Int, numDifat: Int) {
            // 头部 109 个 FAT 扇区索引
            for (i in 0 until 109) {
                val idx = u32(rawData, 76 + i * 4)
                if (idx == ENDOFCHAIN || idx == FREESECT) break
                if (idx >= rawData.size / sectorSize) break
                loadFatSector(idx)
            }
            // DIFAT 链
            if (numDifat != 0 && firstDifat != ENDOFCHAIN && firstDifat != FREESECT) {
                var isect = firstDifat
                var guard = 0
                while (isect != ENDOFCHAIN && isect != FREESECT && guard < 10000) {
                    guard++
                    val sec = readSector(isect)
                    val per = if (sectorSize == 512) 127 else (sectorSize / 4 - 1)
                    for (k in 0 until per) {
                        val idx = u32(sec, k * 4)
                        if (idx == ENDOFCHAIN || idx == FREESECT) break
                        if (idx >= rawData.size / sectorSize) break
                        loadFatSector(idx)
                    }
                    val next = u32(sec, per * 4)
                    isect = next
                }
            }
        }

        private fun loadFatSector(idx: Int) {
            val sec = readSector(idx)
            val n = sectorSize / 4
            for (k in 0 until n) fat.add(u32(sec, k * 4))
        }

        private fun buildMiniFat() {
            if (numMini == 0 || firstMini == ENDOFCHAIN || firstMini == FREESECT) {
                minifat.clear()
                return
            }
            val raw = readChain(firstMini, -1)
            val n = (numMini * sectorSize) / 4
            var k = 0
            while (k < n && k * 4 + 4 <= raw.size) {
                minifat.add(u32(raw, k * 4))
                k++
            }
        }

        private fun readSector(sect: Int): ByteArray {
            val off = sectorSize * (sect + 1)
            if (off < 0 || off + sectorSize > rawData.size) return ByteArray(sectorSize)
            return rawData.copyOfRange(off, off + sectorSize)
        }

        /** 读取 FAT/mini-FAT 链；size<=0 表示读到链尾（ENDOFCHAIN）。 */
        private fun readChain(start: Int, size: Int): ByteArray {
            if (start == ENDOFCHAIN || start == FREESECT || start < 0) return ByteArray(0)
            val out = ByteArrayOutputStream()
            var sect = start
            val limit = if (size > 0) (size + sectorSize - 1) / sectorSize else Int.MAX_VALUE
            var guard = 0
            while (guard < limit && sect != ENDOFCHAIN && sect != FREESECT && sect >= 0 && sect < fat.size) {
                guard++
                val off = sectorSize * (sect + 1)
                if (off < 0 || off + sectorSize > rawData.size) break
                out.write(rawData, off, sectorSize)
                sect = fat[sect]
            }
            val b = out.toByteArray()
            return if (size > 0 && b.size > size) b.copyOf(size) else b
        }

        private fun walkDir() {
            val dirBytes = readChain(firstDir, -1)
            val n = dirBytes.size / 128
            for (sid in 0 until n) {
                val ent = dirBytes.copyOfRange(sid * 128, sid * 128 + 128)
                if (ent.size < 128) break
                val namelen = u16(ent, 64)
                val typ = ent[66].toInt() and 0xFF
                val start = u32(ent, 116)
                val szlow = u32(ent, 120)
                if ((typ == 2 || typ == 5) && namelen > 2) {
                    val name = try {
                        String(ent, 0, namelen - 2, Charset.forName("UTF-16LE"))
                    } catch (_: Throwable) { "" }
                    if (name.isNotEmpty()) streams[name] = Pair(start, szlow)
                }
            }
        }

        /** v1.9.62: 暴露目录内所有流名，供"模糊匹配"取 Package / OlePres / Ole10Native 等流。 */
        fun listNames(): List<String> = streams.keys.toList()

        fun getStream(name: String): ByteArray? {
            val (start, size) = streams[name] ?: return null
            if (size == 0) return ByteArray(0)
            if (size < miniCutoff && name != "Root Entry") {
                return readMiniStream(start, size)
            }
            return readChain(start, size)
        }

        private fun readMiniStream(start: Int, size: Int): ByteArray {
            val container = readChain(firstMini, -1)
            if (container.isEmpty()) return ByteArray(0)
            val out = ByteArrayOutputStream()
            var sect = start
            val limit = (size + miniSize - 1) / miniSize
            var guard = 0
            while (guard < limit && sect != ENDOFCHAIN && sect != FREESECT && sect >= 0 && sect < minifat.size) {
                guard++
                val off = sect * miniSize
                if (off < 0 || off + miniSize > container.size) break
                out.write(container, off, miniSize)
                sect = minifat[sect]
            }
            val b = out.toByteArray()
            return if (b.size > size) b.copyOf(size) else b
        }
    }
}
