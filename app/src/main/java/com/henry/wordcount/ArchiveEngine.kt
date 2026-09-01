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

    /** v1.9.90: 嵌套压缩包递归展开的最大层数（对齐桌面 _list_archive_entries 的 depth<5）。 */
    private const val MAX_ARCHIVE_DEPTH = 5

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
    /**
     * v1.9.63: 新增 gate / onInner 回调。
     *  - gate: 返回 false 表示"已停止"，调用方应立即终止整包（暂停时 gate 会阻塞到继续）。
     *  - onEntries: 开始统计前返回内层文件列表（不含目录），供上层先 emit"骨架"占位。
     *  - onInner: 每个内层文件统计完成后立即回调，上层按名称替换对应占位，实现"统计一个加一个"，
     *    暂停时也能看到全部文件名和已统计结果（v1.9.68）。
     */
    suspend fun extract(file: File, cacheDir: File, context: Context? = null, onProgress: ((Int, Int) -> Unit)? = null,
                        gate: (() -> Boolean)? = null, onEntries: ((List<String>) -> Unit)? = null,
                        onInner: ((InnerResult) -> Unit)? = null, depth: Int = 0): ArchiveResult? {
        return try {
            val ext = file.extension.lowercase()
            when {
                ext == "zip" || (ext.isBlank() && isZipMagic(file)) -> fromZipCommonsCompress(file, cacheDir, context, onProgress, gate, onEntries, onInner, depth)
                ext == "rar" || (ext.isBlank() && isRarMagic(file)) -> fromRar(file, cacheDir, context, onProgress, gate, onEntries, onInner, depth)
                ext in setOf("gz", "tgz") -> fromGzip(file, cacheDir, context, gate, onEntries, onInner, depth)
                ext == "tar" || (ext.isBlank() && isTarMagic(file)) -> fromTarDirect(file, cacheDir, context, gate, onEntries, onInner, depth)
                ext == "7z" -> fromSevenZip(file, cacheDir, context, gate, onEntries, onInner, depth)
                else -> {
                    // 兜底：按 magic bytes 再试一次
                    if (isZipMagic(file)) fromZipCommonsCompress(file, cacheDir, context, onProgress, gate, onEntries, onInner, depth)
                    else if (isRarMagic(file)) fromRar(file, cacheDir, context, onProgress, gate, onEntries, onInner, depth)
                    else if (isGzipMagic(file)) fromGzip(file, cacheDir, context, gate, onEntries, onInner, depth)
                    else null
                }
            }
        } catch (e: Throwable) {
            Diag.w( "ArchiveEngine.extract 异常 ${file.name}: ${e.message}")
            null
        }
    }

    private fun aggregate(inner: List<InnerResult>): ArchiveResult {
        var w = 0; var fe = 0; var nc = 0; var ch = 0
        // v1.5.89: 压缩包内层文件全部计入合计，与电脑版保持一致。
        // 电脑版对压缩包里的 DWG 直接统计（即使数值偏高），手机版此前把 needsPdf 内层排除导致总字数 0，
        // 与电脑版不一致；现改为保留 needsPdf 标记仅作提示，但合计仍按实际提取结果累加。
        inner.forEach { w += it.words; fe += it.fe; nc += it.nc; ch += it.chars }
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
    private suspend fun fromZipCommonsCompress(file: File, cacheDir: File, context: Context?, onProgress: ((Int, Int) -> Unit)? = null,
                                                gate: (() -> Boolean)? = null, onEntries: ((List<String>) -> Unit)? = null,
                                                onInner: ((InnerResult) -> Unit)? = null, depth: Int = 0): ArchiveResult {
        val inner = mutableListOf<InnerResult>()
        org.apache.commons.compress.archivers.zip.ZipFile(file).use { zis ->
            val allEntries = zis.entries.toList()
            val fileEntries = allEntries.filter { !it.isDirectory }
            if (fileEntries.isNotEmpty()) onEntries?.invoke(fileEntries.map { it.name })
            val zipTotal = fileEntries.size
            // v1.9.88: 压缩包内 DWG ≥2 时建立批量预算（40 分钟硬约束覆盖整批内层 DWG）
            val zipDwgCount = fileEntries.count { it.name.substringAfterLast('.', "").equals("dwg", true) }
            val batchActive = zipDwgCount >= 2
            if (batchActive) DwgProcessor.beginBatch(zipDwgCount)
            for ((idx, entry) in fileEntries.withIndex()) {
                if (gate?.invoke() == false) break
                val zipDone = idx + 1
                onProgress?.invoke(zipDone, zipTotal)
                val bytes = zis.getInputStream(entry)?.readBytes() ?: continue
                // v1.9.90: 统一由 processEntryNested 处理——嵌套压缩包（任意格式）递归展开，
                // 对齐桌面 _list_archive_entries（depth<5、任意层级混合格式嵌套均展开）
                inner.addAll(processEntryNested(entry.name, bytes, cacheDir, context, gate, onInner, depth))
                // v1.9.88: 每个内层 DWG 完成后配平批量预算计数
                if (batchActive && entry.name.substringAfterLast('.', "").equals("dwg", true)) DwgProcessor.endFile()
            }
        }
        return aggregate(inner)
    }

    // ──────────────────── RAR (unrar5j，支持 RAR4/RAR5) ────────────────────
    private suspend fun fromRar(file: File, cacheDir: File, context: Context?, onProgress: ((Int, Int) -> Unit)? = null,
                                gate: (() -> Boolean)? = null, onEntries: ((List<String>) -> Unit)? = null,
                                onInner: ((InnerResult) -> Unit)? = null, depth: Int = 0): ArchiveResult? {
        val inner = mutableListOf<InnerResult>()
        val dest = File(cacheDir, "rar_${System.currentTimeMillis()}")
        dest.mkdirs()
        return try {
            val result = Unrar5j.extract(file.absolutePath, dest.absolutePath, null)
            Diag.d( "RAR extract ${file.name}: total=${result?.totalFiles ?: -1} success=${result?.successCount ?: -1} isSuccess=${result?.isSuccess}")
            if (result == null || (!result.isSuccess && result.successCount == 0)) {
                Diag.w( "RAR 解压无成功文件: ${file.name} total=${result?.totalFiles ?: -1} success=${result?.successCount ?: -1}")
                null
            } else {
                if ((result.totalFiles ?: 0) > result.successCount) {
                    Diag.w( "RAR 部分解压: ${file.name} total=${result.totalFiles} success=${result.successCount}")
                }
                val rarFiles = dest.walkTopDown().filter { it.isFile }.toList()
                val rarNames = rarFiles.map { it.relativeTo(dest).path.replace('\\', '/') }
                if (rarNames.isNotEmpty()) onEntries?.invoke(rarNames)
                val rarTotal = rarFiles.size
                // v1.9.88: 压缩包内 DWG ≥2 时建立批量预算（40 分钟硬约束覆盖整批内层 DWG）。
                // 只对本包自己建立的计数做 endFile 配平，避免与外层嵌套包的计数串扰。
                val rarDwgCount = rarFiles.count { it.extension.equals("dwg", true) }
                val batchActive = rarDwgCount >= 2
                if (batchActive) DwgProcessor.beginBatch(rarDwgCount)
                for ((idx, f) in rarFiles.withIndex()) {
                    // v1.9.63: 内层文件边界检查暂停/停止闸门——暂停时阻塞到继续，停止时终止整包。
                    // 这样暂停期间已统计完成的内层文件已通过 onInner 落盘，主界面汇总不再为 0。
                    if (gate?.invoke() == false) break
                    // v1.9.1: 处理前先上报已完成数，保证进入首个大文件（DWG 可能数分钟）时界面即有提示
                    onProgress?.invoke(idx, rarTotal)
                    // v1.5.88: 保留 RAR 内相对路径，避免同名文件被覆盖/统计显示不全
                    val relName = f.relativeTo(dest).path.replace('\\', '/')
                    try {
                        // v1.9.90: processEntryNested 统一处理嵌套压缩包递归展开（对齐桌面）
                        inner.addAll(processEntryNested(relName, f.readBytes(), cacheDir, context, gate, onInner, depth))
                    } catch (_: Throwable) {}
                    // v1.9.88: 每个内层 DWG 完成后配平批量预算计数
                    if (batchActive && f.extension.equals("dwg", true)) DwgProcessor.endFile()
                    onProgress?.invoke(idx + 1, rarTotal)
                }
                Diag.d( "RAR processed ${file.name}: innerFiles=${inner.size}")
                aggregate(inner)
            }
        } catch (e: Throwable) {
            Diag.w( "RAR 解析异常 ${file.name}: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { dest.deleteRecursively() }
        }
    }

    // ──────────────────── GZ / TGZ ────────────────────
    private suspend fun fromGzip(file: File, cacheDir: File, context: Context?,
                                 gate: (() -> Boolean)? = null, onEntries: ((List<String>) -> Unit)? = null,
                                 onInner: ((InnerResult) -> Unit)? = null, depth: Int = 0): ArchiveResult {
        val bytes = file.readBytes()
        val decompressed = gunzipCompat(bytes)
        val inner = mutableListOf<InnerResult>()
        val isTar = decompressed.size > 262 &&
                String(decompressed.copyOfRange(257, 262), StandardCharsets.ISO_8859_1) == "ustar"
        if (isTar || file.extension.lowercase() == "tgz") {
            val names = collectTarNames(decompressed)
            if (names.isNotEmpty()) onEntries?.invoke(names)
            // v1.9.88: 压缩包内 DWG ≥2 时建立批量预算（40 分钟硬约束覆盖整批内层 DWG）
            val tDwgCount = names.count { it.substringAfterLast('.', "").equals("dwg", true) }
            val batchActive = tDwgCount >= 2
            if (batchActive) DwgProcessor.beginBatch(tDwgCount)
            processTar(decompressed, cacheDir, inner, context, gate, onInner, batchActive, depth)
        } else {
            val baseName = file.name.removeSuffix(".gz").removeSuffix(".GZ")
            onEntries?.invoke(listOf(baseName))
            // v1.9.90: processEntryNested 统一处理（单文件 .gz 内也可能是嵌套压缩包字节流）
            inner.addAll(processEntryNested(baseName, decompressed, cacheDir, context, gate, onInner, depth))
        }
        return aggregate(inner)
    }

    // ──────────────────── TAR ────────────────────
    private suspend fun fromTarDirect(file: File, cacheDir: File, context: Context?,
                                      gate: (() -> Boolean)? = null, onEntries: ((List<String>) -> Unit)? = null,
                                      onInner: ((InnerResult) -> Unit)? = null, depth: Int = 0): ArchiveResult {
        val bytes = file.readBytes()
        val inner = mutableListOf<InnerResult>()
        val names = collectTarNames(bytes)
        if (names.isNotEmpty()) onEntries?.invoke(names)
        // v1.9.88: 压缩包内 DWG ≥2 时建立批量预算（40 分钟硬约束覆盖整批内层 DWG）
        val tDwgCount = names.count { it.substringAfterLast('.', "").equals("dwg", true) }
        val batchActive = tDwgCount >= 2
        if (batchActive) DwgProcessor.beginBatch(tDwgCount)
        processTar(bytes, cacheDir, inner, context, gate, onInner, batchActive, depth)
        return aggregate(inner)
    }

    /** 7Z（commons-compress SevenZFile） */
    private suspend fun fromSevenZip(file: File, cacheDir: File, context: Context?,
                                     gate: (() -> Boolean)? = null, onEntries: ((List<String>) -> Unit)? = null,
                                     onInner: ((InnerResult) -> Unit)? = null, depth: Int = 0): ArchiveResult {
        val inner = mutableListOf<InnerResult>()
        val names = collectSevenZipNames(file)
        if (names.isNotEmpty()) onEntries?.invoke(names)
        // v1.9.88: 压缩包内 DWG ≥2 时建立批量预算（40 分钟硬约束覆盖整批内层 DWG）
        val zDwgCount = names.count { it.substringAfterLast('.', "").equals("dwg", true) }
        val batchActive = zDwgCount >= 2
        if (batchActive) DwgProcessor.beginBatch(zDwgCount)
        SevenZFile(file).use { sevenz ->
            while (true) {
                if (gate?.invoke() == false) break
                val entry = sevenz.nextEntry ?: break
                if (!entry.isDirectory) {
                    val bytes = sevenz.getInputStream(entry).readBytes()
                    if (bytes.isNotEmpty()) {
                        // v1.9.90: processEntryNested 统一处理嵌套压缩包递归展开（对齐桌面）
                        inner.addAll(processEntryNested(entry.name, bytes, cacheDir, context, gate, onInner, depth))
                        // v1.9.88: 每个内层 DWG 完成后配平批量预算计数
                        if (batchActive && entry.name.substringAfterLast('.', "").equals("dwg", true)) DwgProcessor.endFile()
                    }
                }
            }
        }
        return aggregate(inner)
    }

    // ──────────────────── TAR 解析器（复用原有逻辑） ────────────────────
    private suspend fun processTar(bytes: ByteArray, cacheDir: File, inner: MutableList<InnerResult>, context: Context? = null,
                                    gate: (() -> Boolean)? = null, onInner: ((InnerResult) -> Unit)? = null,
                                    batchActive: Boolean = false, depth: Int = 0) {
        var pos = 0
        var pendingLongName: String? = null
        while (pos + 512 <= bytes.size) {
            if (gate?.invoke() == false) break
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
                    if (finalName.isNotBlank()) {
                        // v1.9.90: processEntryNested 统一处理嵌套压缩包递归展开（对齐桌面）
                        inner.addAll(processEntryNested(finalName, data, cacheDir, context, gate, onInner, depth))
                        // v1.9.88: 每个内层 DWG 完成后配平批量预算计数
                        if (batchActive && finalName.substringAfterLast('.', "").equals("dwg", true)) DwgProcessor.endFile()
                    }
                }
                else -> { pendingLongName = null }
            }
        }
    }

    /** v1.9.68: TAR 预扫描，只收集文件名（不含目录），用于提前 emit 骨架。 */
    private fun collectTarNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
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
                    if (finalName.isNotBlank()) names.add(finalName)
                }
                else -> { pendingLongName = null }
            }
        }
        return names
    }

    /** v1.9.68: 7Z 预扫描，只收集文件名（不含目录），用于提前 emit 骨架。 */
    private fun collectSevenZipNames(file: File): List<String> {
        val names = mutableListOf<String>()
        SevenZFile(file).use { sevenz ->
            while (true) {
                val entry = sevenz.nextEntry ?: break
                if (!entry.isDirectory && entry.name.isNotBlank()) names.add(entry.name)
            }
        }
        return names
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
    /** 嵌套压缩包后缀集合。v1.9.90 起不再跳过，而是由 processEntryNested 递归展开（对齐桌面）。 */
    private val NESTED_ARCHIVE_SKIP = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")

    /**
     * v1.9.90: 内层文件统一入口（对齐桌面 _list_archive_entries 的嵌套展开语义）。
     *  - 普通受支持文件：走 processEntry 统计，返回单个 InnerResult（onInner 在此触发，保持流式语义）；
     *  - 嵌套压缩包（任意格式 zip/rar/7z/tar/gz/tgz/bz2/xz）：写临时文件后递归 extract() 展开，
     *    返回嵌套包内所有叶子 InnerResult（内层 onInner 已由递归 extract 触发，此处不重复触发）；
     *  - 递归层数上限 MAX_ARCHIVE_DEPTH=5（对齐桌面 depth<5），防无限嵌套；
     *  - 桌面行为：压缩包内部是"普通目录"时递归展开不生成目录节点、是压缩包时作为节点继续展开，
     *    展开目录（桌面 _wc_extracted）跳过——Android 端内层文件以相对路径 name 表达层级，
     *    递归 extract 天然覆盖"任意层级、任意格式混合嵌套"。
     */
    private suspend fun processEntryNested(name: String, bytes: ByteArray, cacheDir: File, context: Context? = null,
                                            gate: (() -> Boolean)? = null, onInner: ((InnerResult) -> Unit)? = null,
                                            depth: Int = 0): List<InnerResult> {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in NESTED_ARCHIVE_SKIP) {
            if (depth >= MAX_ARCHIVE_DEPTH) {
                Diag.d( "嵌套压缩包超过最大层数 $MAX_ARCHIVE_DEPTH，跳过 '$name'（对齐桌面 depth<5）")
                return emptyList()
            }
            val tmp = writeTemp(bytes, name, cacheDir) ?: return emptyList()
            try {
                val nestedRes = extract(tmp, cacheDir, context, gate = gate, onInner = onInner, depth = depth + 1)
                return nestedRes?.inner ?: emptyList()
            } finally {
                runCatching { tmp.delete() }
            }
        }
        val ir = processEntry(name, bytes, cacheDir, context)
        if (ir != null) onInner?.invoke(ir)
        return if (ir != null) listOf(ir) else emptyList()
    }

    /**
     * 内层文件统一走与「单独打开该文件」完全相同的 FileProcessor。
     * 这样压缩包内任意格式（PDF/OOXML/老Office/图片/DWG/文本/未知）的统计路径与结果，
     * 都与单独打开该文件一丝不差（v1.5.82 彻底统一，取代此前重写一遍的独立逻辑）。
     * v1.9.63: 返回 InnerResult?（统计成功时非 null），供调用方即时 emit 进度条目。
     * v1.9.90: 嵌套压缩包已由 processEntryNested 前置拦截递归展开，此处仅处理普通受支持文件；
     *          保留 NESTED_ARCHIVE_SKIP 判断作防御（正常流程不可达）。
     */
    private suspend fun processEntry(name: String, bytes: ByteArray, cacheDir: File, context: Context? = null): InnerResult? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in NESTED_ARCHIVE_SKIP) return null
        if (context == null) return null
        val tmp = writeTemp(bytes, name, cacheDir) ?: return null
        try {
            val out = FileProcessor.process(context, tmp, name.substringAfterLast('/'))
            val m = out.resMap
            if (m == null) {
                // 单个内层文件无结果（如图片无文字/PDF全失败），记录后跳过，不影响其他文件
                Diag.d( "processEntry skip '$name': resMap=null error=${out.error}")
                return null
            }
            val stats = m["stats"] as? Map<*, *> ?: emptyMap<String, Any>()
            val meta = m["meta"] as? Map<*, *> ?: emptyMap<String, Any>()
            val words = (stats["words"] as? Number)?.toInt() ?: 0
            val fe = (stats["fe"] as? Number)?.toInt() ?: 0
            val nc = (stats["nc"] as? Number)?.toInt() ?: 0
            val chars = (stats["chars"] as? Number)?.toInt() ?: 0
            val pages = (m["pages"] as? Int) ?: estimatePages(chars)
            val needsPdf = (meta["needs_pdf"] as? Boolean) ?: false
            return InnerResult(
                name = name.substringAfterLast('/'),
                words = words, fe = fe, nc = nc, chars = chars, pages = pages,
                needsPdf = needsPdf
            )
        } catch (e: Throwable) {
            // v1.5.90: 单个内层文件异常不得导致整个压缩包归零；记录后继续
            Diag.w( "processEntry exception '$name': ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            runCatching { tmp.delete() }
        }
    }

    // 内层文件（PDF/OOXML/老Office/图片/DWG/文本/未知）统一由 FileProcessor 处理，
    // 与「单独打开该文件」走完全相同的代码路径，统计结果必然一致。

    /** 写临时文件供引擎使用。只取短文件名，避免中文字符被替换后产生子目录。 */
    private fun writeTemp(bytes: ByteArray, name: String, cacheDir: File): File? {
        return try {
            val shortName = name.substringAfterLast('/').substringAfterLast('\\')
            val safe = shortName.replace(Regex("[^\\w.]"), "_").takeLast(80).ifEmpty { "bin" }
            val tmp = File(cacheDir, "arc_${System.currentTimeMillis()}_$safe")
            tmp.writeBytes(bytes)
            tmp
        } catch (e: Throwable) {
            Diag.w( "writeTemp failed for '$name': ${e.javaClass.simpleName}: ${e.message}")
            null
        }
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
