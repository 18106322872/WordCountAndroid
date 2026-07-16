package com.henry.wordcount

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Chaquopy 桥接层：在 App 进程内启动内嵌 Python，并调用 wordcount.py 的移动端 API。
 *
 * 图片 OCR 已改为 Kotlin 层 Tesseract（见 OcrEngine），本层只负责把文字/文件
 * 交给 Python 做「Word 口径」字数统计，不再下载或管理任何模型。
 */
object PythonEngine {

    private var started = false

    fun start(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        started = true
    }

    /** 批量统计文档类文件，返回 Python list 转换后的 Java List（元素为含嵌套结构的 Map）。 */
    fun countFiles(paths: List<String>): Any {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        return mod.callAttr("count_files", paths).toJava(List::class.java)
    }

    /** 统计一段已识别的文字（来自 Kotlin 层 Tesseract OCR）。返回 Map。 */
    fun countText(text: String, name: String): Map<*, *> {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        @Suppress("UNCHECKED_CAST")
        return mod.callAttr("count_text", text, name).toJava(Map::class.java) as Map<*, *>
    }

    /** 导出「无法准确统计内容」PDF。filesInfo: List of (name, statsMap, metaMap, srcPath, ext)。 */
    fun buildExportPdf(filesInfo: List<List<Any?>>, outPath: String): String? {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        val result = mod.callAttr("build_export_pdf", filesInfo, outPath)
        val s = result.toString()
        return if (s == "None") null else s
    }
}
