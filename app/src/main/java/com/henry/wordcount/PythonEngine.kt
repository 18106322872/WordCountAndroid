package com.henry.wordcount

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chaquopy 桥接层：v9 重试增强版。
 * Python 端返回 JSON 字符串，Kotlin 端用 JSONObject/JSONArray 解析，
 * 彻底绕开 Chaquopy 的 .toJava() 对嵌套结构（list/dict）转换失败的问题。
 *
 * v9 增强：对 Chaquopy AssetFinder 路径丢失错误（第二次调用时常见），
 * 自动重新初始化 Python 引擎并重试一次。
 */
object PythonEngine {

    private var started = false

    fun start(context: Context) {
        // 每次都尝试 start（Chaquopy 内部会判断是否已初始化）
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            started = true
        } catch (e: Exception) {
            Log.w("PythonEngine", "start 异常（可能需要重新初始化）: ${e.message}")
            // 强制重新初始化：某些情况下 isStarted() 返回 true 但内部状态已损坏
            try {
                Python.start(AndroidPlatform(context))
                started = true
            } catch (e2: Exception) {
                Log.e("PythonEngine", "强制重新初始化也失败: ${e2.message}")
            }
        }
    }

    /** 对 AssetFinder 类型的错误做一次重新初始化+重试 */
    private inline fun <T> withRetry(context: Context, action: () -> T): T {
        return try {
            action()
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("AssetFinder") || msg.contains("chaquopy") || msg.contains("scripts")) {
                Log.w("PythonEngine", "检测到 AssetFinder 路径错误，重新初始化 Python 后重试")
                started = false
                start(context)
                try { action() } catch (e2: Exception) {
                    throw e2 // 重试仍失败则抛出原始异常
                }
            } else {
                throw e
            }
        }
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

    fun countFiles(context: Context, paths: List<String>): Any {
        return withRetry(context) {
            val py = Python.getInstance()
            val mod = py.getModule("wordcount")
            val s = mod.callAttr("count_files", paths).toString()
            val native = toNative(JSONArray(s))
            native ?: emptyList<Any?>()
        }
    }

    fun countText(context: Context, text: String, name: String): Map<*, *> {
        return withRetry(context) {
            val py = Python.getInstance()
            val mod = py.getModule("wordcount")
            val s = mod.callAttr("count_text", text, name).toString()
            @Suppress("UNCHECKED_CAST")
            (toNative(JSONObject(s)) as? Map<*, *>) ?: emptyMap<String, Any?>()
        }
    }

    fun buildExportPdf(context: Context, filesInfo: List<List<Any?>>, outPath: String): String? {
        return withRetry(context) {
            val py = Python.getInstance()
            val mod = py.getModule("wordcount")
            val result = mod.callAttr("build_export_pdf", filesInfo, outPath)
            val s = result.toString()
            if (s == "None") null else s
        }
    }
}
