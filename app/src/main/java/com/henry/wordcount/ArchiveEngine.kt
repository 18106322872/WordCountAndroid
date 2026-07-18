package com.henry.wordcount

import android.util.Log
import java.io.File
import java.io.ByteArrayOutputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.CompressorInputStream
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

/**
 * 压缩文件字数统计引擎。
 *
 * 支持格式：ZIP / RAR / 7z / TAR / GZ / BZ2 / XZ 等（取决于 commons-compress 支持范围）。
 *
 * 功能：
 *   1. 解压读取内部文件列表
 *   2. 递归识别每个内部文件的类型并统计字数
 *   3. 返回每个内部文件的字数详情（InnerResult 列表）
 *   4. 支持嵌套压缩包（递归解压，有限深度防爆炸）
 */
object ArchiveEngine {

    /** 最大递归深度，防止压缩包套娃导致栈溢出或超时 */
    private const val MAX_DEPTH = 3

    /** 单个压缩包最多处理的文件数 */
    private const val MAX_FILES = 500

    /**
     * 处理一个压缩文件，返回：
     *   - 全部拼接的文本（用于总字数统计）
     *   - 内部各文件的统计详情列表
     *   - 工作表/子文件名列表
     */
    fun processArchive(file: File): ArchiveProcessResult {
        val innerResults = mutableListOf<InnerResult>()
        val sheetNames = mutableListOf<String>()
        val allText = StringBuilder()
        val ext = file.extension.lowercase()

        when (ext) {
            "zip" -> processZip(file, innerResults, sheetNames, allText, 0)
            "rar", "7z", "tar", "gz", "bz2", "xz", "tgz" -> {
                processWithCommonsCompress(file, ext, innerResults, sheetNames, allText, 0)
            }
            else -> {
                // 尝试按 ZIP 格式打开（有些 .docx/.xlsx 实际也是 ZIP）
                try { processZip(file, innerResults, sheetNames, allText, 0) } catch (_: Exception) {}
            }
        }

        return ArchiveProcessResult(
            text = allText.toString(),
            inner = innerResults,
            sheets = sheetNames,
            isArchive = true
        )
    }

    // ── ZIP 处理（直接用 Java ZipFile，性能最好）─────────
    private fun processZip(
        file: File,
        results: MutableList<InnerResult>,
        sheets: MutableList<String>,
        allText: StringBuilder,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        var count = 0
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList().sortedBy { it.name }
            for (entry in entries) {
                if (count++ >= MAX_FILES) break
                if (entry.isDirectory) continue

                val name = entry.name.substringAfterLast('/')
                // 跳过隐藏文件和系统文件
                if (name.startsWith(".") || name == "Thumbs.db" || name.startsWith("__MACOSX")) continue

                try {
                    val bytes = ByteArrayOutputStream().use { bos ->
                        zip.getInputStream(entry).use { it.copyTo(bos) }
                        bos.toByteArray()
                    }

                    val entryExt = name.substringAfterLast('.', "").lowercase()

                    // 检查是否是嵌套压缩包
                    if (isArchiveExt(entryExt) && depth < MAX_DEPTH) {
                        // 写入临时文件后递归处理
                        val tmpFile = File.createTempFile("arch_nested_", ".$entryExt")
                        try {
                            tmpFile.writeBytes(bytes)
                            val nested = processArchive(tmpFile)
                            results.add(InnerResult(
                                name = name,
                                words = countTextKotlin(nested.text).first,
                                fe = countTextKotlin(nested.text).second,
                                nc = countTextKotlin(nested.text).third,
                                chars = countTextKotlin(nested.text).fourth,
                                pages = null
                            ))
                            results.addAll(nested.inner)
                            sheets.addAll(nested.sheets.map { "$name/$it" })
                            allText.append(nested.text)
                        } finally { tmpFile.delete() }
                        continue
                    }

                    // 根据扩展名选择提取方式
                    val text = extractTextByExt(bytes, entryExt)

                    if (text.isNotBlank()) {
                        val stats = countTextKotlin(text)
                        results.add(InnerResult(
                            name = name,
                            words = stats.first,
                            fe = stats.second,
                            nc = stats.third,
                            chars = stats.fourth,
                            pages = null
                        ))
                        sheets.add(name)
                        allText.append(text).append("\n\n=== $name ===\n\n")
                    } else {
                        // 内容为空但仍记录
                        results.add(InnerResult(
                            name = name,
                            words = 0, fe = 0, nc = 0, chars = 0, pages = null
                        ))
                        sheets.add(name)
                    }
                } catch (_: Exception) {
                    // 单个条目失败不影响整体
                    results.add(InnerResult(name = name, words = 0, fe = 0, nc = 0, chars = 0, pages = null))
                    sheets.add(name)
                }
            }
        }
    }

    // ── Commons Compress 处理（RAR/7z/TAR/GZ/BZ2/XZ）──────
    private fun processWithCommonsCompress(
        file: File,
        ext: String,
        results: MutableList<InnerResult>,
        sheets: MutableList<String>,
        allText: StringBuilder,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        var count = 0

        try {
            file.inputStream().use { fis ->
                val ais: ArchiveInputStream? = runCatching {
                    when (ext) {
                        "gz" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP, fis) as? ArchiveInputStream
                        "bz2" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2, fis) as? ArchiveInputStream
                        "xz" -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ, fis) as? ArchiveInputStream
                        else -> ArchiveStreamFactory().createArchiveInputStream(ArchiveStreamFactory.detect(fis), fis)
                    }
                }.getOrNull() ?: return

                var entry: ArchiveEntry? = ais.nextEntry
                while (entry != null && count++ < MAX_FILES) {
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfterLast('/')
                        try {
                            val bytes = ByteArrayOutputStream().use { bos ->
                                ais.copyTo(bos)
                                bos.toByteArray()
                            }
                            val entryExt = name.substringAfterLast('.', "").lowercase()
                            val text = extractTextByExt(bytes, entryExt)

                            if (text.isNotBlank()) {
                                val stats = countTextKotlin(text)
                                results.add(InnerResult(name = name, words = stats.first, fe = stats.second,
                                    nc = stats.third, chars = stats.fourth, pages = null))
                                sheets.add(name)
                                allText.append(text).append("\n\n=== $name ===\n\n")
                            }
                        } catch (_: Exception) { /* skip */ }
                    }
                    entry = ais.nextEntry
                }
            }
        } catch (e: Exception) {
            // Commons Compress 可能不支持某些变体格式，静默降级
            Log.w("ArchiveEngine", "${ext.uppercase()} 解压失败: ${e.message}")
        }
    }

    // ── 按扩展名提取文本 ──────────────────────────────────
    internal fun extractTextByExt(bytes: ByteArray, ext: String): String {
        return when (ext) {
            "txt", "csv", "log", "xml", "html", "htm", "json",
            "java", "kt", "py", "js", "ts", "c", "cpp", "h", "cs",
            "md", "rst", "sql", "sh", "bat", "yml", "yaml", "toml",
            "ini", "cfg", "conf", "properties", "gradle", "mf",
            "css", "scss", "less", "svg" -> {
                // 纯文本：尝试 UTF-8 → GBK → ISO-8859-1 降级
                decodeText(bytes)
            }
            "doc" -> {
                // 用临时文件 + OldOfficeEngine
                val tmp = createTmp(bytes, ".doc")
                runCatching { OldOfficeEngine.extractText(tmp) }.getOrElse { "" }.also { tmp.delete() }
            }
            "docx" -> {
                val tmp = createTmp(bytes, ".docx")
                runCatching { OoXmlEngine.extractText(tmp) }.getOrElse { "" }.also { tmp.delete() }
            }
            "xls" -> {
                val tmp = createTmp(bytes, ".xls")
                runCatching { OldOfficeEngine.extractText(tmp) }.getOrElse { "" }.also { tmp.delete() }
            }
            "xlsx" -> {
                val tmp = createTmp(bytes, ".xlsx")
                runCatching { OoXmlEngine.extractText(tmp) }.getOrElse { "" }.also { tmp.delete() }
            }
            "pdf" -> {
                val tmp = createTmp(bytes, ".pdf")
                runCatching { PdfExtractor.extractText(tmp) }.getOrElse { "" }.also { tmp.delete() }
            }
            else -> "" // 不支持的格式返回空
        }
    }

    // ── 工具方法 ──────────────────────────────────────────

    private fun isArchiveExt(ext: String): Boolean {
        return ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
    }

    private fun createTmp(bytes: ByteArray, ext: String): File {
        val f = File.createTempFile("wc_arch_", ext)
        f.writeBytes(bytes)
        return f
    }

    private fun decodeText(bytes: ByteArray): String {
        // 尝试多种编码
        val encodings = listOf(Charsets.UTF_8, Charset.forName("GBK"), Charsets.ISO_8859_1, Charset.forName("UTF-16"))
        for (enc in encodings) {
            try {
                val text = String(bytes, enc)
                if (!text.contains("\uFFFD")) return text // 无替换字符说明解码正确
            } catch (_: Exception) {}
        }
        return String(bytes, Charsets.UTF_8)
    }

    data class ArchiveProcessResult(
        val text: String,
        val inner: List<InnerResult>,
        val sheets: List<String>,
        val isArchive: Boolean
    )
}


