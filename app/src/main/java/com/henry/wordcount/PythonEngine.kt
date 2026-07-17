package com.henry.wordcount

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chaquopy 桥接层：v8 最小改动版。
 * Python 端返回 JSON 字符串，Kotlin 端用 JSONObject/JSONArray 解析。
 */
object PythonEngine {

    private var started = false

    fun start(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        started = true
    }

    private fun toNative(obj: Any?): Any? {
        if (obj is JSONArray) {
            val list = ArrayList<Any?>(obj.length())
            for (i in 0 until obj.length()) list.add(toNative(obj.get(i)))
            return list
        }
        if (obj is JSONObject) {
            val map = HashMap<String, Any?>()
            val it = obj.keys()
            while (it.hasNext()) { val k = it.next(); map[k] = toNative(obj.get(k)) }
            return map
        }
        return obj
    }

    fun countFiles(paths: List<String>): Any {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        val s = mod.callAttr("count_files", paths).toString()
        return toNative(JSONArray(s))
    }

    fun countText(text: String, name: String): Map<*, *> {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        val s = mod.callAttr("count_text", text, name).toString()
        @Suppress("UNCHECKED_CAST")
        return (toNative(JSONObject(s)) as? Map<*, *>) ?: emptyMap<String, Any?>()
    }

    fun buildExportPdf(filesInfo: List<List<Any?>>, outPath: String): String? {
        val py = Python.getInstance()
        val mod = py.getModule("wordcount")
        val result = mod.callAttr("build_export_pdf", filesInfo, outPath)
        val s = result.toString()
        return if (s == "None") null else s
    }
}
