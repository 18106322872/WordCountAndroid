package com.henry.aligntool.engine

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 纯 Kotlin 的 zip 部件替换工具。
 *
 * OOXML 是 zip 包。手机版不引入 POI，直接按部件名读写：
 * 解析某部件 XML → 在 XmlDom 上原位插入 → 序列化 → 用本工具把新部件写回 zip，
 * 其余部件（[Content_Types].xml、rels、media 等）逐字节保留。
 */
object ZipUtil {

    /** 读出 zip 内某条目的原始字节（找不到返回 null）。 */
    fun readEntry(src: File, entryName: String): ByteArray? {
        ZipFile(src).use { zf ->
            val e = zf.getEntry(entryName) ?: return null
            zf.getInputStream(e).use { return it.readBytes() }
        }
    }

    /** 列出 zip 内全部条目名。 */
    fun listEntries(src: File): List<String> {
        ZipFile(src).use { zf ->
            return zf.entries().toList().map { it.name }
        }
    }

    /**
     * 生成新 zip：把 [replacements] 里的条目替换为新字节，其余条目原样拷贝。
     * 所有 XML 部件统一用 DEFLATED 重新压缩（文本无损，体积接近）。
     */
    fun rewriteEntries(src: File, replacements: Map<String, ByteArray>, out: File) {
        ZipFile(src).use { zf ->
            ZipOutputStream(out.outputStream()).use { zos ->
                for (e in zf.entries()) {
                    val name = e.name
                    val bytes = replacements[name] ?: zf.getInputStream(e).readBytes()
                    val ne = ZipEntry(name)
                    // 目录条目(以 / 结尾)不写数据
                    if (!name.endsWith("/")) {
                        ne.method = ZipEntry.DEFLATED
                    }
                    zos.putNextEntry(ne)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
    }
}
