package com.henry.wordcount

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chaquopy 桥接层：v11 激进稳定版。
 *
 * 核心问题（v1.0.12 验证后确认）：
 *   Chaquopy AssetFinder 路径在部分设备上不稳定，即使逐文件单例调用也会失败。
 *   根因是 Chaquopy 内部状态/文件系统层面的时序问题，非调用方式可绕过。
 *
 * v11 策略：
 *   1) 每次 Python 调用前**无条件**重新初始化（绕开 isStarted 缓存判断）
 *   2) 初始化后立即**预热**——强制 import 所有重度模块，确保 AssetFinder 数据就位
 *   3) 失败时**最多重试 3 次**（每次都完整重新初始化）
 *   4) TXT 格式由 Kotlin 直接处理（完全绕开 Python，减少调用频率）
 */
object PythonEngine {

    private const val MAX_RETRIES = 3

    /** 是否已完成预热（避免每次调用都预热） */
    @Volatile private var warmedUp = false

    /**
     * 无条件重新初始化 Python 引擎。
     * 不再依赖 isStarted() 缓存——该缓存可能在设备上不准确。
     * Chaquopy 内部会处理重复 start() 的幂等性。
     */
    fun start(context: Context) {
        try {
            Python.start(AndroidPlatform(context))
            Log.d("PythonEngine", "Python.start() 完成")
        } catch (e: Exception) {
            Log.e("PythonEngine", "Python.start() 异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 预热：强制 import 所有重度模块，确保 AssetFinder 在"空闲"状态下完成所有文件提取。
     * 只在首次调用时执行；如果预热失败不阻断后续操作（各方法有自己的重试机制）。
     */
    fun warmup(context: Context) {
        if (warmedUp) return
        try {
            val py = Python.getInstance()
            val mod = py.getModule("wordcount")
            mod.callAttr("_chaquopy_warmup")
            warmedUp = true
            Log.d("PythonEngine", "预热成功")
        } catch (e: Exception) {
            Log.w("PythonEngine", "预热失败（不影响后续调用）: ${e.javaClass.simpleName}: ${e.message}")
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
               lower.contains("no such file") ||
               lower.contains("ioerror") ||
               lower.contains("oserror")
    }

    /** 多重重试：最多 MAX_RETRIES 次，每次都完整重新初始化+预热 */
    private inline fun <T> withRetry(context: Context, action: () -> T): T {
        var lastException: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                // 每次尝试前都重新初始化
                start(context)
                if (attempt == 1) warmup(context)
                return action()
            } catch (e: Exception) {
                val msg = e.message ?: ""
                Log.w("PythonEngine", "第 $attempt/$MAX_RETRIES 次调用异常: ${e.javaClass.simpleName}: ${msg.take(120)}")
                lastException = e
                if (!isRetryableError(msg)) {
                    Log.w("PythonEngine", "非重试类错误，直接抛出")
                    throw e
                }
                // 重置预热标志，下次重新预热
                warmedUp = false
                // 短暂等待后再重试（给文件系统一点时间稳定）
                if (attempt < MAX_RETRIES) Thread.sleep(200L)
            }
        }
        Log.e("PythonEngine", "已重试 $MAX_RETRIES 次均失败")
        throw lastException!!
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

    /**
     * 比较两份 DOCX，生成带修订标记的 .docx 并统计修改句字数。
     *
     * @param origPath 原文档缓存路径
     * @param revPath  修订文档缓存路径
     * @param outPath  比对结果输出路径
     * @param optsJson 选项 JSON（level/case/whitespace/table/header_footer/footnote/textbox/field）
     * @return Python 端返回的 JSON 字符串 {"ok":bool,"out_path":str,"insertions":N,
     *         "deletions":N,"replacements":N,"modified_sentence_chars":M,"error":str?}
     */
    fun compareDocx(context: Context, origPath: String, revPath: String, outPath: String, optsJson: String): String? {
        return withRetry(context) {
            val py = Python.getInstance()
            val mod = py.getModule("wordcount")
            val result = mod.callAttr("compare_docx", origPath, revPath, outPath, optsJson)
            result.toString()
        }
    }
}
