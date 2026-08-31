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
     * 启动 Python 引擎（幂等守卫）。
     *
     * v1.3.80: 真正的首次启动已在 WordCountApplication.onCreate()（主线程）完成。
     * 此处仅作兜底——若尚未启动则启动，绝不重复初始化，避免后台线程重复 start()
     * 触发 Chaquopy AssetFinder/scripts 提取竞态（部分设备 FileNotFoundError）。
     */
    fun start(context: Context) {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
                Log.d("PythonEngine", "Python.start() 完成（兜底启动）")
            }
        } catch (e: Exception) {
            Log.e("PythonEngine", "Python.start() 异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 读取 Application 层记录的 Python 启动错误（若有） */
    private fun appStartError(context: Context): String? {
        val app = context.applicationContext
        return if (app is WordCountApplication) app.pythonStartError else null
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
        // v1.3.80: 启动只做一次（Application.onCreate 已主线程启动；此处 isStarted 守卫兜底）。
        // 绝不在重试循环里重新调用 Python.start()，否则后台线程重复初始化会触发
        // Chaquopy AssetFinder/scripts 提取竞态。
        start(context)
        if (!warmedUp) warmup(context)
        var lastException: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
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
            // v1.3.61: 改用 count_files_json（Python 端 json.dumps+default=str）
            //   v1.3.60 在 Kotlin 端传 default=str 失败：
            //   callAttr 把 Kotlin Map 当位置参数 → TypeError（default 是 keyword-only）
            //   异常被吞掉 → pyOk 永远 false → PDF 永远用 Kotlin 的 695 字符
            val s = mod.callAttr("count_files_json", paths).toString()
            Log.d("WordCount", "PY json len=${s.length} head=${s.take(300)}")
            val native = toNative(JSONArray(s))
            native ?: emptyList<Any?>()
        }
    }

    /** v1.3.63: 诊断函数——验证 Python 引擎正常工作并返回环境信息 */
    fun testPython(context: Context): String {
        val appErr = appStartError(context)
        return try {
            withRetry(context) {
                val py = Python.getInstance()
                val mod = py.getModule("wordcount")
                mod.callAttr("python_test").toString()
            }
        } catch (e: Exception) {
            // 若 Application 层启动已失败，直接给出根因；否则给出当前异常
            "Python引擎启动失败: ${appErr ?: (e.javaClass.simpleName + ": " + e.message)}"
        }
    }

    /**
     * v1.9.11: DWG/DXF 结构化统计（对齐桌面 wordcount.py 的 ezdxf 提取，逐字节同源）。
     * 返回 cad_core.extract_dxf_json 的 JSON 字符串：
     *   {"items":[...],"pages":N,"pages_reason":"...","needs_pdf":bool,"encoder_garbled":bool}
     *
     * @param dxfPath dwg2dxf 已转换好的 DXF 路径（可能含 LibreDWG 结构缺陷，Python 端 sanitize 修复）
     * @param dwgPath 原始 DWG 路径（可空，用于中文丢失时的 GBK/UTF-16 字节扫描恢复）
     */
    /**
     * v1.9.39: 新增 outDir 参数（可空）。cad_core 会把 DWG 内嵌 IMAGE 实体的 embedded_image
     * 字节导出为 PNG 落盘到 outDir，路径列表返回到 result.image_pngs。
     * 主进程 DwgProcessor 拿到后调 DwgImageOcrExtractor 跑 ML Kit OCR。
     */
    fun extractCadDxf(context: Context, dxfPath: String, dwgPath: String?, outDir: String? = null): String {
        return withRetry(context) {
            val py = Python.getInstance()
            val mod = py.getModule("cad_core")
            val s = when {
                dwgPath != null && outDir != null -> mod.callAttr("extract_dxf_json", dxfPath, dwgPath, outDir).toString()
                dwgPath != null -> mod.callAttr("extract_dxf_json", dxfPath, dwgPath).toString()
                outDir != null -> mod.callAttr("extract_dxf_json", dxfPath, null, outDir).toString()
                else -> mod.callAttr("extract_dxf_json", dxfPath).toString()
            }
            s
        }
    }

    /**
     * v1.9.11: 用桌面同源 count_items 对 items 计数，返回 JSON {"fe","nc","chars","words"}
     * v1.9.83 FIX: Chaquopy 把 Kotlin List<String> 转成不可迭代的 java.util.ArrayList 代理，
     * 导致 Python 端 `for it in items:` 抛 TypeError: 'ArrayList' object is not iterable，
     * pyWords 恒为 0。改为显式构建 Python list 再传入。
     */
    fun countCadItems(context: Context, items: List<String>): String {
        return withRetry(context) {
            val py = Python.getInstance()
            val mod = py.getModule("cad_core")
            val pyList = py.builtins.callAttr("list")
            for (item in items) {
                pyList.callAttr("append", item)
            }
            mod.callAttr("count_items_json", pyList).toString()
        }
    }

    /** v1.9.11: 提取 DXF 内 OLE2FRAME 的 office 嵌入文字（xlsx/docx/pptx，桌面 cad_ole_ocr 同源）。
     *  返回 JSON {"joined":str,"ole_count":N,"unique_objects":N} */
    fun extractOleOffice(context: Context, dxfPath: String): String {
        return withRetry(context) {
            val py = Python.getInstance()
            val mod = py.getModule("cad_core")
            mod.callAttr("extract_ole_office_json", dxfPath).toString()
        }
    }

    fun countText(context: Context, text: String, name: String): Map<*, *> {
        return withRetry(context) {
            val py = Python.getInstance()
            val mod = py.getModule("wordcount")
            val jsonMod = py.getModule("json")
            val pyResult = mod.callAttr("count_text", text, name)
            val s = jsonMod.callAttr("dumps", pyResult, mapOf(Pair("default", py.getModule("builtins").get("str")))).toString()
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
