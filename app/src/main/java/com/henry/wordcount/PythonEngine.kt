package com.henry.wordcount

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chaquopy 桥接层：在 App 进程内启动内嵌 Python，并调用 wordcount.py 的移动端 API。
 *
 * 图片 OCR 已改为 Kotlin 层 Tesseract（见 OcrEngine），本层只负责把文字/文件
 * 交给 Python 做「Word 口径」字数统计，不再下载或管理任何模型。
 *
 * v8 修复：Python 端改用 json.dumps() 返回 JSON 字符串，Kotlin 端用
 * JSONObject/JSONArray 解析为原生类型。彻底绕过 Chaquopy .toJava()
 * 对复杂嵌套结构（list of dicts 含嵌套 list/dict）的类型转换失败问题：
 *   - v7 输入端：'ArrayList' object is not iterable（已用 _to_py_list 修复）
 *   - v8 输出端：Cannot convert list object to java.util.List（本次修复）
 */
object PythonEngine {

    private var started = false

    fun start(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        started = true
    }

    // ── JSON → Kotlin 原生类型递归转换 ──

    /** 将 JSONObject/JSONArray/基本类型 递归转为 MainActivity 可直接用的 Kotlin 类型。 */
    private fun Any?.toJsonNative(): Any? = when (this) {
        is JSONArray -> (0 until length()).map { this.get(it).toJsonNative() }
        is JSONObject -> keys().asSequence().associateWith { get(it).toJsonNative() }
        else -> this  // String / Int / Long / Double / Boolean / null
    }

    /**
     * 批量统计文档类文件。
     *
     * Python count_files() 现返回 JSON 字符串：[{ok, result|error, name}, ...]。
     * 本方法解析后返回 List<Map<String, Any?>>，与 MainActivity 原有用法完全兼容。
     */
    fun countFiles(paths: List<String>): Any {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        val jsonStr = mod.callAttr("count_files", paths).toString()
        return JSONArray(jsonStr).toJsonNative()
    }

    /**
     * 统计一段已识别的文字（来自 Kotlin 层 Tesseract OCR）。
     *
     * Python count_text() 现返回 JSON 字符串。
     * 本方法解析后返回 Map<String, Any?>，与 MainActivity 原有用法完全兼容。
     */
    fun countText(text: String, name: String): Map<*, *> {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        val jsonStr = mod.callAttr("count_text", text, name).toString()
        @Suppress("UNCHECKED_CAST")
        return (JSONObject(jsonStr).toJsonNative() as? Map<*, *>)
            ?: emptyMap<String, Any?>()
    }

    /** 导出「无法准确统计内容」PDF。filesInfo: List of (name, statsMap, metaMap, srcPath, ext）。 */
    fun buildExportPdf(filesInfo: List<List<Any?>>, outPath: String): String? {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        val result = mod.callAttr("build_export_pdf", filesInfo, outPath)
        val s = result.toString()
        return if (s == "None") null else s
    }
}
