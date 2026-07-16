package com.henry.wordcount

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Chaquopy 桥接层：在 App 进程内启动内嵌 Python，并调用 wordcount.py 的移动端 API。
 *
 * 折中方案关键点：OCR 模型不在 APK 内，而是从 GitHub Release 下载后放在 ocrDir，
 * 通过环境变量 WORDCOUNT_OCR_DIR 告知 RapidOCR 使用下载的模型。
 */
object PythonEngine {

    private var started = false

    fun start(context: Context, ocrDir: String?) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        started = true
        if (!ocrDir.isNullOrEmpty()) {
            val py = Python.getInstance()
            val environ = py.getModule("os").get("environ")
            environ.callAttr("__setitem__", "WORDCOUNT_OCR_DIR", ocrDir)
        }
    }

    /** 批量统计，返回 Python list 转换后的 Java List（元素为含嵌套结构的 Map）。 */
    fun countFiles(paths: List<String>): Any {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        return mod.callAttr("count_files", paths).toJava(List::class.java)
    }

    /** 导出「无法准确统计内容」PDF。filesInfo: List of (name, statsMap, metaMap, srcPath, ext)。 */
    fun buildExportPdf(filesInfo: List<List<Any?>>, outPath: String): String? {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        val result = mod.callAttr("build_export_pdf", filesInfo, outPath)
        return if (result.isNone) null else result.toString()
    }
}
