package com.henry.wordcount

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chaquopy 桥接层：v8 JSON 序列化方案。
 *
 * Python 端 count_files / count_text 返回 json.dumps() JSON 字符串，
 * Kotlin 端用 JSONObject/JSONArray 解析为原生 List/Map。
 * 彻底绕过 Chaquopy .toJava() 对复杂嵌套结构的类型转换失败。
 */
object PythonEngine {

    private var started = false

    fun start(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        started = true
    }

    /** 递归将 JSONArray/JSONObject 转为 Kotlin 原生 List<Map> / Map。 */
    @Suppress("UNCHECKED_CAST")
    private fun convertJsonElement(any: Any?): Any? {
        return when (any) {
            is JSONArray -> {
                val list = mutableListOf<Any?>()
                for (i in 0 until any.length()) {
                    list.add(convertJsonElement(any.get(i)))
                }
                list
            }
            is JSONObject -> {
                val map = mutableMapOf<String, Any?>()
                val keys = any.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = convertJsonElement(any.get(key))
                }
                map
            }
            else -> any  // String / Int / Long / Double / Boolean / null
        }
    }

    /**
     * 批量统计文档类文件。返回 List<Map<String, Any?>>，与 MainActivity 兼容。
     */
    fun countFiles(paths: List<String>): Any {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        val jsonStr = mod.callAttr("count_files", paths).toString()
        return convertJsonElement(JSONArray(jsonStr))
    }

    /**
     * 统计一段已识别的文字（OCR）。返回 Map<String, Any?>。
     */
    fun countText(text: String, name: String): Map<*, *> {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        val jsonStr = mod.callAttr("count_text", text, name).toString()
        return (convertJsonElement(JSONObject(jsonStr)) as? Map<*, *>)
            ?: emptyMap<String, Any?>()
    }

    /** 导出「无法准确统计内容」PDF。 */
    fun buildExportPdf(filesInfo: List<List<Any?>>, outPath: String): String? {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        val result = mod.callAttr("build_export_pdf", filesInfo, outPath)
        val s = result.toString()
        return if (s == "None") null else s
    }
}
