package com.henry.wordcount

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import com.github.junrar.Junrar
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * 压缩包统计层（ZIP/7Z/TAR/GZ 基于 Apache Commons Compress；RAR4 基于 junrar）。
 *
 * 支持：ZIP / RAR4 / 7Z / TAR / GZ / TGZ
 * 对每个内层受支持文件，复用既有引擎抽取文本并统计字数。
 */
object ArchiveEngine {

    /** v1.3.95：压缩包内层图片 OCR 全局配额（每次 extract 重置），防止大压缩包触发大量 OCR 卡死。 */
    private val ocrBudget = AtomicInteger(0)

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
            val header = file.inputStream().use { it.readNBytes(8) }
            when {
                header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                        && header[2] == 0x03.toByte() && header[3] == 0x04.toByte() -> true // PK\x03\x04 = ZIP
                header.size >= 6 && header[0] == 0x52.toByte() && header[1] == 0x61.toByte()
                        && header[2] == 0x72.toByte() && header[3] == 0x21.toByte()
                        && header[4] == 0x1A.toByte() && header[5] == 0x07.toByte() -> true // Rar! = RAR
                header.size >= 2 && (header[0].toInt() and 0xFF) == 0x1F && (header[1].toInt() and 0xFF) == 0x8B -> true // GZ
                header.size >= 262 && String(header.copyOfRange(257, 262), StandardCharsets.ISO_8859_1) == "ustar" -> true // TAR ustar
                header.size >= 6 && header[0] == 0x37.toByte() && header[1] == 0x7A.toByte()
                        && header[2] == 0xBC.toByte() && header[3] == 0xAF.toByte()
                        && header[4] == 0x27.toByte() && header[5] == 0x1C.toByte() -> true // 7z
                else -> false
            }
        } catch (_: Throwable) { false }
    }

    /** cacheDir 用于解包内层文件到临时文件。返回 null 表示不支持或解析失败。
     *  v1.3.95: 新增 context 参数——用于内层图片的 OCR 统计（用户清单要求计入压缩包内扫描图文字）。 */
    fun extract(file: File, cacheDir: File, context: Context? = null): ArchiveResult? {
        // 内层图片 OCR 全局配额（防止大压缩包触发大量 OCR 导致卡死/OOM）
        ocrBudget.set(5)
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
            null
        }
    }

    private fun aggregate(inner: List<InnerResult>): ArchiveResult {
        var w = 0; var fe = 0; var nc = 0; var ch = 0
        inner.forEach { w += it.words; fe += it.fe; nc += it.nc; ch += it.chars }
        return ArchiveResult(inner, w, fe, nc, ch)
    }

    // ──────────────────── Magic bytes helpers ────────────────────

    private fun isZipMagic(f: File): Boolean = try {
        val h = f.inputStream().use { it.readNBytes(4) }
        h.size >= 4 && h[0] == 0x50.toByte() && h[1] == 0x4B.toByte() && h[2] == 0x03.toByte() && h[3] == 0x04.toByte()
    } catch (_: Throwable) { false }

    private fun isRarMagic(f: File): Boolean = try {
        val h = f.inputStream().use { it.readNBytes(6) }
        h.size >= 6 && h[0] == 0x52.toByte() && h[1] == 0x61.toByte() && h[2] == 0x72.toByte()
                && h[3] == 0x21.toByte() && h[4] == 0x1A.toByte() && h[5] == 0x07.toByte()
    } catch (_: Throwable) { false }

    private fun isGzipMagic(f: File): Boolean = try {
        val h = f.inputStream().use { it.readNBytes(2) }
        h.size >= 2 && (h[0].toInt() and 0xFF) == 0x1F && (h[1].toInt() and 0xFF) == 0x8B
    } catch (_: Throwable) { false }

    private fun isTarMagic(f: File): Boolean = try {
        val bytes = f.readBytes()
        bytes.size > 262 && String(bytes.copyOfRange(257, 262), StandardCharsets.ISO_8859_1) == "ustar"
    } catch (_: Throwable) { false }

    // ──────────────────── ZIP (commons-compress) ────────────────────
    private fun fromZipCommonsCompress(file: File, cacheDir: File, context: Context?): ArchiveResult {
        val inner = mutableListOf<InnerResult>()
        file.inputStream().use { fis ->
            val zis = org.apache.commons.compress.archivers.zip.ZipFile(file)
            try {
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
            } finally { runCatching { zis.close() } }
        }
        return aggregate(inner)
    }

    // ──────────────────── RAR4 (junrar，纯 Java RAR 解压库) ────────────────────
    private fun fromRar(file: File, cacheDir: File, context: Context?): ArchiveResult? {
        val inner = mutableListOf<InnerResult>()
        val dest = File(cacheDir, "rar_${System.currentTimeMillis()}")
        dest.mkdirs()
        try {
            // v1.0.20: 检测 RAR5（junrar 仅支持 RAR4）
            val header = file.inputStream().use { it.readNBytes(8) }
            if (header.size >= 8 && header[0] == 0x52.toByte() && header[1] == 0x61.toByte()
                && header[2] == 0x72.toByte() && header[3] == 0x21.toByte()
                && header[4] == 0x1A.toByte() && header[5] == 0x07.toByte()
                && (header[6].toInt() and 0x01) == 0x01) {
                // 第 7 字节的 bit 0 = 1 表示 RAR5
                return null // 让调用方显示"RAR5 不支持"提示
            }

            Junrar.extract(file.absolutePath, dest.absolutePath)
            dest.walkTopDown().filter { it.isFile }.forEach { f ->
                try { processEntry(f.name, f.readBytes(), cacheDir, inner, context) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            // RAR5 / 加密 / 损坏等情况会抛异常
        } finally {
            runCatching { dest.deleteRecursively() }
        }
        return aggregate(inner)
    }

    // ──────────────────── GZ / TGZ ────────────────────
    private fun fromGzip(file: File, cacheDir: File, context: Context?): ArchiveResult {
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
    private fun fromTarDirect(file: File, cacheDir: File, context: Context?): ArchiveResult {
        val bytes = file.readBytes()
        val inner = mutableListOf<InnerResult>()
        processTar(bytes, cacheDir, inner, context)
        return aggregate(inner)
    }

    /** 7Z（commons-compress SevenZFile） */
    private fun fromSevenZip(file: File, cacheDir: File, context: Context?): ArchiveResult {
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
    private fun processTar(bytes: ByteArray, cacheDir: File, inner: MutableList<InnerResult>, context: Context? = null) {
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
    private val SUPPORTED_OOXML = setOf("docx", "xlsx", "pptx")
    private val SUPPORTED_OLD_OFFICE = setOf("doc", "xls", "ppt")
    private val SUPPORTED_TEXT = setOf(
        "txt", "csv", "json", "xml", "md", "log", "html", "htm",
        "ini", "cfg", "conf", "yaml", "yml", "toml", "properties",
        "sql", "sh", "bat", "cmd", "ps1", "py", "js", "ts", "java",
        "kt", "c", "cpp", "h", "hpp", "cs", "go", "rs", "rb", "php",
        "swift", "r", "m", "scala", "clj", "vue", "jsx", "tsx", "svelte"
    )

    /** 已知二进制/无法统计的扩展名——直接跳过，不尝试当文本读 */
    private val SKIP_EXTS = setOf(
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "svg",
        "mp3", "mp4", "avi", "mkv", "mov", "wav", "flac",
        "exe", "dll", "so", "dylib", "a", "o", "obj", "class",
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", // 嵌套压缩包不递归展开
        "ttf", "otf", "woff", "woff2", "eot",
        "db", "sqlite", "mdb", "accdb",
        "bin", "dat", "sys", "drv"
    )

    private fun processEntry(name: String, bytes: ByteArray, cacheDir: File, inner: MutableList<InnerResult>, context: Context? = null) {
        val ext = name.substringAfterLast('.', "").lowercase()
        // 图片：v1.3.95 改为尝试 OCR 统计（用户清单要求计入压缩包内扫描图文字）。
        // 仅在仍有 OCR 配额且图片字节较小（<2MB）时才 OCR，避免大压缩包卡死/OOM。
        val imageExts = setOf("png", "jpg", "jpeg", "bmp", "gif", "webp", "tif", "tiff")
        if (ext in imageExts) {
            if (context != null && ocrBudget.get() > 0 && bytes.size <= 2 * 1024 * 1024) {
                ocrBudget.decrementAndGet()
                val tmp = writeTemp(bytes, name, cacheDir) ?: return
                try {
                    val ocrText = runCatching { OcrEngine.recognize(context, tmp) }.getOrNull()
                    if (!ocrText.isNullOrBlank()) {
                        val stats = countTextKotlin(ocrText)
                        val pages = 1
                        inner.add(InnerResult(
                            name = name.substringAfterLast('/'),
                            words = stats.first, fe = stats.second, nc = stats.third,
                            chars = stats.fourth, pages = pages
                        ))
                        Log.d("WordCount", "压缩包内层图片 OCR: $name → ${stats.first} 词")
                    }
                } finally {
                    runCatching { tmp.delete() }
                }
            }
            return
        }
        // 跳过其他已知不可处理的二进制类型
        if (ext in SKIP_EXTS) return
        val tmp = writeTemp(bytes, name, cacheDir) ?: return
        try {
            val ooxml = if (ext in SUPPORTED_OOXML) OoXmlEngine.extract(tmp) else null
            val pdf = if (ext == "pdf") PdfExtractor.extract(tmp) else null
            val text: String? = ooxml?.text ?: pdf?.text ?: when {
                ext in SUPPORTED_OLD_OFFICE -> runCatching { OldOfficeEngine.extractText(tmp) }.getOrNull()
                ext == "dwg" -> runCatching { DwgEngine.extractTextSafe(tmp) }.getOrNull()
                // 已知文本类：UTF-8 优先，不可读则尝试 GBK；均不可读（实为二进制）则跳过
                // v1.0.20: 归档内层文件用更宽松的可读性判定（归档上下文下假阳性比假阴性危害小）
                ext in SUPPORTED_TEXT || ext.isBlank() -> runCatching {
                    val t = String(bytes, StandardCharsets.UTF_8)
                    when {
                        isReadableText(t, lenient = true) -> t
                        else -> {
                            val g = String(bytes, Charset.forName("GBK"))
                            if (isReadableText(g, lenient = true)) g else null
                        }
                    }
                }.getOrNull()
                // 未知扩展名：依次尝试 UTF-8 / GBK，不再先用 ISO-8859-1 判二进制
                // （ISO-8859-1 模式会把含少量非 ASCII 字节的文本误判为二进制）
                else -> runCatching {
                    val t = String(bytes, StandardCharsets.UTF_8)
                    when {
                        isReadableText(t, lenient = true) -> t
                        else -> {
                            val g = String(bytes, Charset.forName("GBK"))
                            if (isReadableText(g, lenient = true)) g else null
                        }
                    }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) return
            val stats = countTextKotlin(text)
            // DWG 是一张图纸，固定 1 页；其余按类型取页数，文本类兜底按字符量估算
            val pages = when {
                ooxml != null -> ooxml.pages
                pdf != null -> pdf.pages
                ext == "dwg" -> 1
                else -> estimatePages(stats.fourth)
            }
            inner.add(
                InnerResult(
                    name = name.substringAfterLast('/'),
                    words = stats.first, fe = stats.second, nc = stats.third,
                    chars = stats.fourth, pages = pages
                )
            )
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /**
     * 严格判断一段“解码后的文本”是否真的像可读文本（用于挡掉被误当文本的二进制乱码）。
     * 规则：
     *   - 长度至少 4
     *   - 控制字符（<0x20 且非换行/制表）占比不得超过 10%（二进制通常很高）
     *   - Unicode 替换符(U+FFFD)占比不得超过 2%（解码损坏的标志）
     *   - 可打印字符（ASCII 可打印 / 字母数字 / CJK）占比必须 > 85%
     * 满足才认为是文本，否则视为二进制/损坏，不应被统计字数。
     */
    private fun isReadableText(s: String, lenient: Boolean = false): Boolean {
        if (s.length < 4) return false
        var printable = 0
        var control = 0
        var replacement = 0
        for (c in s) {
            val code = c.code
            when {
                code in 0x20..0x7E -> printable++
                c.isLetterOrDigit() -> printable++
                code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x3000..0x303F
                        || code in 0xFF00..0xFFEF || code in 0x2E80..0x2EFF || code in 0xF900..0xFAFF -> printable++
                code == 0xFFFD -> replacement++
                code < 0x20 && c != '\n' && c != '\r' && c != '\t' -> control++
            }
        }
        // v1.0.20: 放宽阈值，修复 ZIP/RAR 全 0 问题
        val maxControl = if (lenient) 0.20 else 0.15
        val maxReplacement = if (lenient) 0.05 else 0.03
        val minPrintable = if (lenient) 0.60 else 0.75
        if (control > s.length * maxControl) return false
        if (replacement > s.length * maxReplacement) return false
        return printable > s.length * minPrintable
    }

    /** 判断 ISO-8859-1 字符串是否像二进制（控制字符太多） */
    private fun isBinaryLike(s: String): Boolean {
        if (s.length < 8) return true
        var control = 0
        for (i in s.indices) {
            val c = s[i]
            if (c.code < 0x20 && c != '\n' && c != '\r' && c != '\t') control++
        }
        return control > s.length * 0.10
    }

    /** 写临时文件供引擎使用。 */
    private fun writeTemp(bytes: ByteArray, name: String, cacheDir: File): File? {
        return try {
            val safe = name.replace(Regex("[^\\w.\\-/]"), "_").takeLast(80)
            val tmp = File(cacheDir, "arc_${System.currentTimeMillis()}_$safe")
            tmp.writeBytes(bytes)
            tmp
        } catch (_: Throwable) { null }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^\\w.\\-/]"), "_").takeLast(80)

    // ──────────────────── gzip 工具函数 ────────────────────
    internal fun gunzipCompat(bytes: ByteArray): ByteArray {
        return try {
            GzipCompressorInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } catch (_: Throwable) {
            // fallback: use the original gunzip implementation
            gunzip(bytes)
        }
    }
}
