package com.henry.wordcount

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * 纯 Kotlin 压缩包统计层（无任何第三方库）。
 *
 * 支持：ZIP（含嵌套 zip 递归）、GZ / TGZ（gzip + tar）、TAR。
 * 不支持：RAR / 7Z（需原生库，安卓无法纯 Kotlin 实现）→ 调用方提示。
 *
 * 对压缩包内每个受支持的文件，复用既有引擎抽取文本并复用 countTextKotlin 统计，
 * 逐文件给出字数/中文/非中文/字符/页数，汇总为 ArchiveResult，供 MainActivity 展示「逐文件详情」。
 */
object ArchiveEngine {

    data class ArchiveResult(
        val inner: List<InnerResult>,
        val words: Int, val fe: Int, val nc: Int, val chars: Int
    )

    /** cacheDir 用于解包内层文件到临时文件（引擎多接收 File）。rar/7z 返回 null。 */
    fun extract(file: File, cacheDir: File): ArchiveResult? {
        val ext = file.extension.lowercase()
        return try {
            when (ext) {
                "zip" -> fromZip(file, cacheDir)
                "gz", "tgz" -> fromGzip(file, cacheDir)
                "tar" -> fromTar(file.readBytes(), cacheDir)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun aggregate(inner: List<InnerResult>): ArchiveResult {
        var w = 0; var fe = 0; var nc = 0; var ch = 0
        inner.forEach { w += it.words; fe += it.fe; nc += it.nc; ch += it.chars }
        return ArchiveResult(inner, w, fe, nc, ch)
    }

    // ───────────────────────── ZIP ─────────────────────────
    private fun fromZip(file: File, cacheDir: File): ArchiveResult {
        val zip = ZipFile(file)
        val inner = mutableListOf<InnerResult>()
        try {
            for (entry in Collections.list(zip.entries())) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.endsWith("/")) continue
                val bytes = zip.getInputStream(entry).readBytes()
                processEntry(name, bytes, cacheDir, inner)
            }
        } finally { runCatching { zip.close() } }
        return aggregate(inner)
    }

    /** 嵌套 zip：用 ZipInputStream 枚举内层条目。 */
    private fun processNestedZip(bytes: ByteArray, cacheDir: File, inner: MutableList<InnerResult>) {
        val zis = ZipInputStream(ByteArrayInputStream(bytes))
        var e: ZipEntry? = zis.nextEntry
        while (e != null) {
            if (!e.isDirectory) {
                val name = e.name
                val entryBytes = zis.readBytes()
                processEntry(name, entryBytes, cacheDir, inner)
            }
            zis.closeEntry()
            e = zis.nextEntry
        }
        runCatching { zis.close() }
    }

    // ───────────────────────── GZIP / TAR ─────────────────────────
    private fun fromGzip(file: File, cacheDir: File): ArchiveResult {
        val bytes = file.readBytes()
        val decompressed = gunzip(bytes) // PdfExtractor.gunzip
        val inner = mutableListOf<InnerResult>()
        val isTar = decompressed.size > 262 &&
                String(decompressed.copyOfRange(257, 262), StandardCharsets.ISO_8859_1) == "ustar"
        if (isTar || file.extension.lowercase() == "tgz") {
            processTar(decompressed, cacheDir, inner)
        } else {
            // 单文件 gzip：用去掉 .gz 的名字
            val baseName = file.name.removeSuffix(".gz").removeSuffix(".GZ")
            processEntry(baseName, decompressed, cacheDir, inner)
        }
        return aggregate(inner)
    }

    private fun fromTar(bytes: ByteArray, cacheDir: File): ArchiveResult {
        val inner = mutableListOf<InnerResult>()
        processTar(bytes, cacheDir, inner)
        return aggregate(inner)
    }

    /** 解析 tar（512 字节块；支持 ustar 普通名 + prefix；GNU 长名 'L'）。 */
    private fun processTar(bytes: ByteArray, cacheDir: File, inner: MutableList<InnerResult>) {
        var pos = 0
        var pendingLongName: String? = null
        while (pos + 512 <= bytes.size) {
            val header = bytes.copyOfRange(pos, pos + 512)
            pos += 512
            val name = readTarName(header)
            val sizeStr = String(header.copyOfRange(124, 136), StandardCharsets.ISO_8859_1).trim()
            val size = octalToLong(sizeStr)
            val typeFlag = (header[156].toInt() and 0xFF).toChar()
            if (name.isEmpty() && size <= 0) break // 结束块
            val dataSize = if (size < 0) 0 else size
            val rounded = ((dataSize + 511) / 512) * 512
            val data = if (dataSize > 0 && pos + dataSize <= bytes.size) bytes.copyOfRange(pos, pos + dataSize.toInt()) else ByteArray(0)
            pos += rounded.toInt()
            when (typeFlag) {
                'L' -> { // GNU 长文件名：数据块即文件名
                    pendingLongName = String(data, StandardCharsets.UTF_8).trimEnd('\u0000')
                }
                '0', '\u0000' -> { // 普通文件
                    val finalName = pendingLongName?.let { if (name.isNotEmpty()) "$it/$name" else it } ?: name
                    pendingLongName = null
                    if (finalName.isNotBlank()) processEntry(finalName, data, cacheDir, inner)
                }
                else -> { pendingLongName = null }
            }
        }
    }

    private fun readTarName(header: ByteArray): String {
        // 0..100 主名（null 结尾）；345..500 prefix（ustar）
        val main = String(header.copyOfRange(0, 100), StandardCharsets.ISO_8859_1).substringBefore('\u0000')
        val isUstar = String(header.copyOfRange(257, 262), StandardCharsets.ISO_8859_1) == "ustar"
        val prefix = if (isUstar) String(header.copyOfRange(345, 500), StandardCharsets.ISO_8859_1).substringBefore('\u0000') else ""
        return if (prefix.isNotEmpty()) "$prefix/$main" else main
    }

    private fun octalToLong(s: String): Long {
        val cleaned = s.takeWhile { it in '0'..'7' }
        return if (cleaned.isEmpty()) 0L else cleaned.toLongOrNull(8) ?: 0L
    }

    // ───────────────────────── 内层文件路由 ─────────────────────────
    private val SUPPORTED = setOf(
        "txt", "csv", "json", "xml", "md", "log", "html", "htm",
        "docx", "xlsx", "pptx", "pdf", "doc", "xls", "ppt", "dwg"
    )

    private fun processEntry(name: String, bytes: ByteArray, cacheDir: File, inner: MutableList<InnerResult>) {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "zip") { processNestedZip(bytes, cacheDir, inner); return }
        if (ext !in SUPPORTED) return // 跳过不支持/二进制噪声
        val tmp = File(cacheDir, "arc_${System.currentTimeMillis()}_${sanitize(name)}")
        try {
            tmp.writeBytes(bytes)
            val ooxml = if (ext in setOf("docx", "xlsx", "pptx")) OoXmlEngine.extract(tmp) else null
            val pdf = if (ext == "pdf") PdfExtractor.extract(tmp) else null
            val text: String? = ooxml?.text ?: pdf?.text ?: when (ext) {
                "doc", "xls", "ppt" -> runCatching { OldOfficeEngine.extractText(tmp) }.getOrNull()
                "dwg" -> runCatching { DwgEngine.extractText(tmp) }.getOrNull()
                else -> runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
            }
            if (text.isNullOrBlank()) return
            val stats = countTextKotlin(text)
            val pages = ooxml?.pages ?: pdf?.pages ?: estimatePages(stats.fourth)
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

    private fun sanitize(name: String): String =
        name.replace(Regex("""[^\w.\-/]"""), "_").takeLast(80)
}
