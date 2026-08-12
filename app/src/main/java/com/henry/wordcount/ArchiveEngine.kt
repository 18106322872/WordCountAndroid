package com.henry.wordcount

import android.content.Context
import android.util.Log
import be.stef.rar.Unrar5j
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 压缩包统计层（ZIP/7Z/TAR/GZ 基于 Apache Commons Compress；RAR 基于 unrar5j，支持 RAR4/RAR5）。
 *
 * 支持：ZIP / RAR(4/5) / 7Z / TAR / GZ / TGZ
 * 对每个内层受支持文件，复用既有引擎抽取文本并统计字数，统计口径与单独打开文件保持一致。
 */
object ArchiveEngine {

    data class ArchiveResult(
        val inner: List<InnerResult>,
        val words: Int, val fe: Int, val nc: Int, val chars: Int
    )

    /**
     * 检测文件是否为已知压缩格式（基于 magic bytes）。
     * 在 copyUriToCache 扩展名不确定时作为兜底判断依据。
     */
    fun isArchive(file: File): Boolean {
        return try {
            val header = file.inputStream().use { readNBytesCompat(it, 8) }
            when {
                header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                        && header[2] == 0x03.toByte() && header[3] == 0x04.toByte() -> true // PK\x03\x04 = ZIP
                header.size >= 6 && header[0] == 0x52.toByte() && header[1] == 0x61.toByte()
                        && header[2] == 0x72.toByte() && header[3] == 0x21.toByte()
                        && header[4] == 0x1A.toByte() && header[5] == 0x07.toByte() -> true // Rar! = RAR
                header.size >= 2 && (header[0].toInt() and 0xFF) == 0x1F && (header[1].toInt() and 0xFF) == 0x8B -> true // GZ
                isTarMagic(file) -> true // TAR ustar
                header.size >= 6 && header[0] == 0x37.toByte() && header[1] == 0x7A.toByte()
                        && header[2] == 0xBC.toByte() && header[3] == 0xAF.toByte()
                        && header[4] == 0x27.toByte() && header[5] == 0x1C.toByte() -> true // 7z
                else -> false
            }
        } catch (_: Throwable) { false }
    }

    /** cacheDir 用于解包内层文件到临时文件。返回 null 表示不支持或解析失败。
     *  context 参数用于内层图片/PDF 的 OCR 统计。 */
    suspend fun extract(file: File, cacheDir: File, context: Context? = null): ArchiveResult? {
        return try {
            val ext = file.extension.lowercase()
            when {
                ext == "zip" || (ext.isBlank() && isZipMagic(file)) -> fromZipCommonsCompress(file, cacheDir, context)
                ext == "rar" || (ext.isBlank() && isRarMagic(file)) -> fromRar(file, cacheDir, context)
                ext in setOf("gz", "tgz") -> fromGzip(file, cacheDir, context)
                ext == "tar" || (ext.isBlank() && isTarMagic(file)) -> fromTarDirect(file, cacheDir, context)
                ext == "7z" -> fromSevenZip(file, cacheDir, context)
                else -> {
                    // 兜底：按 magic bytes 再试一次
                    if (isZipMagic(file)) fromZipCommonsCompress(file, cacheDir, context)
                    else if (isRarMagic(file)) fromRar(file, cacheDir, context)
                    else if (isGzipMagic(file)) fromGzip(file, cacheDir, context)
                    else null
                }
            }
        } catch (e: Throwable) {
            Log.w("WordCount", "ArchiveEngine.extract 异常 ${file.name}: ${e.message}")
            null
        }
    }

    private fun aggregate(inner: List<InnerResult>): ArchiveResult {
        var w = 0; var fe = 0; var nc = 0; var ch = 0
        // v1.5.86: 需用PDF统计(栅格化 DWG 等)的内层文件不计入压缩包合计，与电脑版一致
        inner.forEach { if (!it.needsPdf) { w += it.words; fe += it.fe; nc += it.nc; ch += it.chars } }
        return ArchiveResult(inner, w, fe, nc, ch)
    }

    // ──────────────────── Magic bytes helpers ────────────────────

    private fun isZipMagic(f: File): Boolean = try {
        val h = f.inputStream().use { readNBytesCompat(it, 4) }
        h.size >= 4 && h[0] == 0x50.toByte() && h[1] == 0x4B.toByte() && h[2] == 0x03.toByte() && h[3] == 0x04.toByte()
    } catch (_: Throwable) { false }

    private fun isRarMagic(f: File): Boolean = try {
        val h = f.inputStream().use { readNBytesCompat(it, 6) }
        h.size >= 6 && h[0] == 0x52.toByte() && h[1] == 0x61.toByte() && h[2] == 0x72.toByte()
                && h[3] == 0x21.toByte() && h[4] == 0x1A.toByte() && h[5] == 0x07.toByte()
    } catch (_: Throwable) { false }

    private fun isGzipMagic(f: File): Boolean = try {
        val h = f.inputStream().use { readNBytesCompat(it, 2) }
        h.size >= 2 && (h[0].toInt() and 0xFF) == 0x1F && (h[1].toInt() and 0xFF) == 0x8B
    } catch (_: Throwable) { false }

    private fun isTarMagic(f: File): Boolean = try {
        val bytes = f.readBytes()
        bytes.size > 262 && String(bytes.copyOfRange(257, 262), StandardCharsets.ISO_8859_1) == "ustar"
    } catch (_: Throwable) { false }

    // ──────────────────── readNBytes 兼容层 ────────────────────
    /** Android 低版本(API<33) InputStream 没有 readNBytes，用循环读取兜底。 */
    private fun readNBytesCompat(input: InputStream, n: Int): ByteArray {
        val result = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(result, read, n - read)
            if (r < 0) break
            read += r
        }
        return result.copyOf(read)
    }

    // ──────────────────── ZIP (commons-compress) ────────────────────
    private suspend fun fromZipCommonsCompress(file: File, cacheDir: File, context: Context?): ArchiveResult {
        val inner = mutableListOf<InnerResult>()
        org.apache.commons.compress.archivers.zip.ZipFile(file).use { zis ->
            val entries = zis.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement() as ZipArchiveEntry
                if (entry.isDirectory) continue
                val bytes = zis.getInputStream(entry)?.readBytes() ?: continue
                processEntry(entry.name, bytes, cacheDir, inner, context)
                // 嵌套 zip
                if (entry.name.lowercase().endsWith(".zip")) {
                    val nestedTmp = writeTemp(bytes, entry.name, cacheDir)
                    if (nestedTmp != null) {
                        val nestedRes = extract(nestedTmp, cacheDir, context)
                        if (nestedRes != null) inner.addAll(nestedRes.inner)
                        nestedTmp.delete()
                    }
                }
            }
        }
        return aggregate(inner)
    }

    // ──────────────────── RAR (unrar5j，支持 RAR4/RAR5) ────────────────────
    private suspend fun fromRar(file: File, cacheDir: File, context: Context?): ArchiveResult? {
        val inner = mutableListOf<InnerResult>()
        val dest = File(cacheDir, "rar_${System.currentTimeMillis()}")
        dest.mkdirs()
        return try {
            val result = Unrar5j.extract(file.absolutePath, dest.absolutePath, null)
            if (result == null || (!result.isSuccess && result.successCount == 0)) {
                Log.w("WordCount", "RAR 解压无成功文件: ${file.name} total=${result?.totalFiles ?: -1} success=${result?.successCount ?: -1}")
                null
            } else {
                dest.walkTopDown().filter { it.isFile }.forEach { f ->
                    try { processEntry(f.name, f.readBytes(), cacheDir, inner, context) } catch (_: Throwable) {}
                }
                aggregate(inner)
            }
        } catch (e: Throwable) {
            Log.w("WordCount", "RAR 解析异常 ${file.name}: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { dest.deleteRecursively() }
        }
    }

    // ──────────────────── GZ / TGZ ────────────────────
    private suspend fun fromGzip(file: File, cacheDir: File, context: Context?): ArchiveResult {
        val bytes = file.readBytes()
        val decompressed = gunzipCompat(bytes)
        val inner = mutableListOf<InnerResult>()
        val isTar = decompressed.size > 262 &&
                String(decompressed.copyOfRange(257, 262), StandardCharsets.ISO_8859_1) == "ustar"
        if (isTar || file.extension.lowercase() == "tgz") {
            processTar(decompressed, cacheDir, inner, context)
        } else {
            val baseName = file.name.removeSuffix(".gz").removeSuffix(".GZ")
            processEntry(baseName, decompressed, cacheDir, inner, context)
        }
        return aggregate(inner)
    }

    // ──────────────────── TAR ────────────────────
    private suspend fun fromTarDirect(file: File, cacheDir: File, context: Context?): ArchiveResult {
        val bytes = file.readBytes()
        val inner = mutableListOf<InnerResult>()
        processTar(bytes, cacheDir, inner, context)
        return aggregate(inner)
    }

    /** 7Z（commons-compress SevenZFile） */
    private suspend fun fromSevenZip(file: File, cacheDir: File, context: Context?): ArchiveResult {
        val inner = mutableListOf<InnerResult>()
        SevenZFile(file).use { sevenz ->
            while (true) {
                val entry = sevenz.nextEntry ?: break
                if (!entry.isDirectory) {
                    val bytes = sevenz.getInputStream(entry).readBytes()
                    if (bytes.isNotEmpty()) processEntry(entry.name, bytes, cacheDir, inner, context)
                }
            }
        }
        return aggregate(inner)
    }

    // ──────────────────── TAR 解析器（复用原有逻辑） ────────────────────
    private suspend fun processTar(bytes: ByteArray, cacheDir: File, inner: MutableList<InnerResult>, context: Context? = null) {
        var pos = 0
        var pendingLongName: String? = null
        while (pos + 512 <= bytes.size) {
            val header = bytes.copyOfRange(pos, pos + 512)
            pos += 512
            val name = readTarName(header)
            val sizeStr = String(header.copyOfRange(124, 136), StandardCharsets.ISO_8859_1).trim()
            val size = octalToLong(sizeStr)
            val typeFlag = (header[156].toInt() and 0xFF).toChar()
            if (name.isEmpty() && size <= 0) break
            val dataSize = if (size < 0) 0 else size
            val rounded = ((dataSize + 511) / 512) * 512
            val data = if (dataSize > 0 && pos + dataSize <= bytes.size) bytes.copyOfRange(pos, pos + dataSize.toInt()) else ByteArray(0)
            pos += rounded.toInt()
            when (typeFlag) {
                'L' -> { pendingLongName = String(data, StandardCharsets.UTF_8).trimEnd('\u0000') }
                '0', '\u0000' -> {
                    val finalName = pendingLongName?.let { if (name.isNotEmpty()) "$it/$name" else it } ?: name
                    pendingLongName = null
                    if (finalName.isNotBlank()) processEntry(finalName, data, cacheDir, inner, context)
                }
                else -> { pendingLongName = null }
            }
        }
    }

    private fun readTarName(header: ByteArray): String {
        val main = String(header.copyOfRange(0, 100), StandardCharsets.ISO_8859_1).substringBefore('\u0000')
        val isUstar = String(header.copyOfRange(257, 262), StandardCharsets.ISO_8859_1) == "ustar"
        val prefix = if (isUstar) String(header.copyOfRange(345, 500), StandardCharsets.ISO_8859_1).substringBefore('\u0000') else ""
        return if (prefix.isNotEmpty()) "$prefix/$main" else main
    }

    private fun octalToLong(s: String): Long {
        val cleaned = s.takeWhile { it in '0'..'7' }
        return if (cleaned.isEmpty()) 0L else cleaned.toLongOrNull(8) ?: 0L
    }

    // ──────────────────── 内层文件路由 ────────────────────
    /** 嵌套压缩包不在此递归展开（外层循环已对 .zip 递归；其余类型与单独打开压缩包一致地跳过）。 */
    private val NESTED_ARCHIVE_SKIP = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")

    /**
     * 内层文件统一走与「单独打开该文件」完全相同的 FileProcessor。
     * 这样压缩包内任意格式（PDF/OOXML/老Office/图片/DWG/文本/未知）的统计路径与结果，
     * 都与单独打开该文件一丝不差（v1.5.82 彻底统一，取代此前重写一遍的独立逻辑）。
     */
    private suspend fun processEntry(name: String, bytes: ByteArray, cacheDir: File, inner: MutableList<InnerResult>, context: Context? = null) {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in NESTED_ARCHIVE_SKIP) return
        if (context == null) return // 极少数无 context 的情况跳过（OCR/Python 不可用）
        val tmp = writeTemp(bytes, name, cacheDir) ?: return
        try {
            val out = FileProcessor.process(context, tmp, name.substringAfterLast('/'))
            val m = out.resMap ?: return // 如图片无文字/PDF 全失败：与单独打开得到"空/错误"一致，不计入
            val stats = m["stats"] as? Map<*, *> ?: emptyMap<String, Any>()
            val meta = m["meta"] as? Map<*, *> ?: emptyMap<String, Any>()
            val words = (stats["words"] as? Number)?.toInt() ?: 0
            val fe = (stats["fe"] as? Number)?.toInt() ?: 0
            val nc = (stats["nc"] as? Number)?.toInt() ?: 0
            val chars = (stats["chars"] as? Number)?.toInt() ?: 0
            val pages = (m["pages"] as? Int) ?: estimatePages(chars)
            // v1.5.86: 内层文件若自身需要"必须用PDF统计"（如栅格化 DWG），标记 needsPdf，
            // 明细中提示，且聚合时不计入压缩包合计（对齐电脑版：无法提取中文的 DWG 走 PDF，不虚增字数）。
            val needsPdf = (meta["needs_pdf"] as? Boolean) ?: false
            inner.add(InnerResult(
                name = name.substringAfterLast('/'),
                words = words, fe = fe, nc = nc, chars = chars, pages = pages,
                needsPdf = needsPdf
            ))
        } finally {
            runCatching { tmp.delete() }
        }
    }

    // 内层文件（PDF/OOXML/老Office/图片/DWG/文本/未知）统一由 FileProcessor 处理，
    // 与「单独打开该文件」走完全相同的代码路径，统计结果必然一致。

    /** 写临时文件供引擎使用。 */
    private fun writeTemp(bytes: ByteArray, name: String, cacheDir: File): File? {
        return try {
            val safe = name.replace(Regex("[^\\w.\\-/]"), "_").takeLast(80)
            val tmp = File(cacheDir, "arc_${System.currentTimeMillis()}_$safe")
            tmp.writeBytes(bytes)
            tmp
        } catch (_: Throwable) { null }
    }

    // ──────────────────── gzip 工具函数 ────────────────────
    internal fun gunzipCompat(bytes: ByteArray): ByteArray {
        return try {
            GzipCompressorInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } catch (_: Throwable) {
            gunzip(bytes)
        }
    }
}
