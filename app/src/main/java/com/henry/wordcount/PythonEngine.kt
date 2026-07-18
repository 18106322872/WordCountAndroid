package com.henry.wordcount

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chaquopy 桥接层：v10 双重重试增强版。
 * Python 端返回 JSON 字符串，Kotlin 端用 JSONObject/JSONArray 解析，
 * 彻底绕开 Chaquopy 的 .toJava() 对嵌套结构（list/dict）转换失败的问题。
 *
 * v10 增强：
 *   1) 对 Chaquopy AssetFinder 路径丢失错误（第二次调用时常见），
 *      自动重新初始化 Python 引擎并重试。
 *   2) 对 FileNotFoundError / IOError 类系统错误也重试（覆盖面更广）。
 *   3) 重试前先重置 started 标志，确保 Python.start() 真正重新执行。
 */
object PythonEngine {

    private var started = false

    fun start(context: Context) {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            started = true
        } catch (e: Exception) {
            Log.w("PythonEngine", "start 异常（可能需要重新初始化）: ${e.message}")
            try {
                Python.start(AndroidPlatform(context))
                started = true
            } catch (e2: Exception) {
                Log.e("PythonEngine", "强制重新初始化也失败: ${e2.message}")
            }
        }
    }

    /** 判断是否为需要重试的 Chaquopy/系统路径类错误 */
    private fun isRetryableError(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("assetfinder") ||
               lower.contains("chaquopy") ||
               lower.contains("scripts") ||
               lower.contains("filenotfounderror") ||
               lower.contains("file not found") ||
               lower.contains("/data/data") ||
               lower.contains("no such file")
    }

    /** 对 Chaquopy/路径类错误做一次重新初始化+重试 */
    private inline fun <T> withRetry(context: Context, action: () -> T): T {
        return try {
            action()
        } catch (e: Exception) {
            val msg = e.message ?: ""
            // 打印完整异常链以便调试
            Log.w("PythonEngine", "Python 调用异常: ${e.javaClass.simpleName}: $msg")
            if (isRetryableError(msg)) {
                Log.w("PythonEngine", "检测到可重试错误，重新初始化 Python 后重试...")
                started = false
                start(context)
                try {
                    val result = action()
                    Log.d("PythonEngine", "重试成功！")
                    result
                } catch (e2: Exception) {
                    Log.e("PythonEngine", "重试仍失败: ${e2.javaClass.simpleName}: ${e2.message}")
                    throw e2
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
