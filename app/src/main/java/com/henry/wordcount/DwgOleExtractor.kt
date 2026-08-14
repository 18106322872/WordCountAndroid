package com.henry.wordcount

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
    private const val MAX_BITMAPS_PER_FILE = 4
    private const val MAX_OCR_TEXT_CHARS = 3000

    /**
     * 从 DXF 中抽取所有 OLE2FRAME 预览位图的 OCR 文本。
     * @param dxfPath dwg→dxf 转换产物路径（不存在时直接返回空，绝不影响主流程）
     */
    fun extractOleText(dxfPath: String, maxBitmaps: Int = MAX_BITMAPS_PER_FILE): OleExtractResult {
        return try {
            val blobs = findOleBlobs(dxfPath)
            if (blobs.isEmpty()) return OleExtractResult("", 0, 0)

            // 已接受的文本行集合，用于合并「粘贴多次的同一对象」（近似桌面去重）
            val acceptedFragSets = mutableListOf<Set<String>>()
            val allLines = LinkedHashSet<String>()
            var bitmapsOcred = 0

            for (blob in blobs) {
                if (bitmapsOcred >= maxBitmaps) break
                if (allLines.size >= MAX_OCR_TEXT_CHARS) break
                try {
                    val cfb = Cfb(blob)
                    for (sname in PRES_NAMES) {
                        if (bitmapsOcred >= maxBitmaps) break
                        if (allLines.size >= MAX_OCR_TEXT_CHARS) break
                        val stream = cfb.getStream(sname) ?: continue
                        val (bmp, _) = findBitmap(stream) ?: continue
                        val text = ocrBitmap(bmp) ?: continue
                        if (text.isBlank()) continue
                        val lines = text.lines().map { it.trim() }.filter { it.length >= 2 }
                        if (lines.isEmpty()) continue
                        // 对象合并：若本 blob 的行集合与已接受对象高度相似，视为同一粘贴对象，跳过
                        val fragSet = lines.toSet()
                        if (acceptedFragSets.any { jaccard(it, fragSet) >= 0.9 }) continue
                        acceptedFragSets.add(fragSet)
                        for (ln in lines) allLines.add(ln)
                        bitmapsOcred++
                    }
                } catch (e: Throwable) {
                    Log.d(TAG, "DwgOleExtractor blob 解析失败(跳过): ${e.message}")
                }
            }
            OleExtractResult(allLines.joinToString("\n"), blobs.size, bitmapsOcred)
        } catch (e: Throwable) {
            Log.w(TAG, "DwgOleExtractor.extractOleText 失败: ${e.message}")
            OleExtractResult("", 0, 0)
        }
    }

    // ───────────────────────── DXF 扫描：OLE2FRAME 310–319 ─────────────────────────

    /** 流式扫描 DXF，收集每个 OLE2FRAME 实体的 310–319 hex → 还原 CFB 字节。 */
    private fun findOleBlobs(dxfPath: String): List<ByteArray> {
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
            reader.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
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

    private fun ocrBitmap(bmpBytes: ByteArray): String? {
        val bmp = BitmapFactory.decodeByteArray(bmpBytes, 0, bmpBytes.size) ?: return null
        return try {
            val scaled = scaleDown(bmp, 1600)
            val text = try {
                OcrEngine.recognizeBitmap(scaled, true)
            } finally {
                if (scaled !== bmp) bmp.recycle()
                scaled.recycle()
            }
            text
        } catch (e: Throwable) {
            Log.w(TAG, "DwgOleExtractor.ocrBitmap 失败: ${e.message}")
            null
        }
    }

    /** 按最大边长缩放，避免超大预览图 OOM；返回新 Bitmap 时回收旧图。 */
    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val scale = maxOf(1, maxOf(w, h) / maxDim)
        return if (scale <= 1) src else Bitmap.createScaledBitmap(src, w / scale, h / scale, true)
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
