package com.henry.wordcount

import android.util.Log
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

/**
 * 压缩文件字数统计引擎（v1.0.15 保守实现）。
 *
 * 支持格式：ZIP（Java 内置）/ 其他格式降级为 ZIP 尝试打开。
 * 使用 java.util.zip（Android 原生支持），不依赖 commons-compress 高级 API。
 *
 * 功能：
 *   1. 解压读取内部文件列表
 *   2. 递归识别每个内部文件的类型并统计字数
 *   3. 返回每个内部文件的字数详情（InnerResult 列表）
 */
object ArchiveEngine {

    private const val MAX_DEPTH = 3
    private const val MAX_FILES = 500

    fun processArchive(file: File): ArchiveProcessResult {
        val innerResults = mutableListOf<InnerResult>()
        val sheetNames = mutableListOf<String>()
        val allText = StringBuilder()

        // 统一用 ZipFile 打开（ZIP 是最通用的压缩格式；RAR/7z 在 Android 上需要额外 native 库）
        processZipInternal(file, innerResults, sheetNames, allText, 0)

        return ArchiveProcessResult(
            text = allText.toString(),
            inner = innerResults,
            sheets = sheetNames,
            isArchive = true
        )
    }

    private fun processZipInternal(
        file: File,
        results: MutableList<InnerResult>,
        sheets: MutableList<String>,
        allText: StringBuilder,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        var count = 0

        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList().sortedBy { it.name }
                for (entry in entries) {
                    if (count++ >= MAX_FILES) break
                    if (entry.isDirectory) continue

                    val name = entry.name.substringAfterLast('/')
                    if (name.startsWith(".") || name == "Thumbs.db" || name.startsWith("__MACOSX")) continue

                    try {
                        val bytes = ByteArrayOutputStream().use { bos ->
                            zip.getInputStream(entry).use { it.copyTo(bos) }
                            bos.toByteArray()
                        }

                        val entryExt = name.substringAfterLast('.', "").lowercase()

                        // 检查嵌套压缩包
                        if (isArchiveExt(entryExt) && depth < MAX_DEPTH) {
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
                            results.add(InnerResult(name = name, words = 0, fe = 0, nc = 0, chars = 0, pages = null))
                            sheets.add(name)
                        }
                    } catch (_: Exception) {
                        results.add(InnerResult(name = name, words = 0, fe = 0, nc = 0, chars = 0, pages = null))
                        sheets.add(name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ArchiveEngine", "ZIP 解压失败: ${e.message}")
        }
    }

    internal fun extractTextByExt(bytes: ByteArray, ext: String): String {
        return when (ext) {
            "txt", "csv", "log", "xml", "html", "htm", "json",
            "java", "kt", "py", "js", "ts", "c", "cpp", "h", "cs",
            "md", "rst", "sql", "sh", "yml", "yaml",
            "ini", "cfg", "properties", "gradle", "mf",
            "css", "scss", "svg" -> decodeText(bytes)
            "doc" -> {
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
            else -> ""
        }
    }

    private fun isArchiveExt(ext: String): Boolean =
        ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")

    private fun createTmp(bytes: ByteArray, ext: String): File {
        val f = File.createTempFile("wc_arch_", ext)
        f.writeBytes(bytes)
        return f
    }

    private fun decodeText(bytes: ByteArray): String {
        val encodings = listOf(
            Charsets.UTF_8,
            runCatching { java.nio.charset.Charset.forName("GB2312") }.getOrNull() ?: Charsets.ISO_8859_1,
            Charsets.ISO_8859_1
        )
        for (enc in encodings) {
            try {
                val text = String(bytes, enc)
                if (!text.contains("\uFFFD")) return text
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
