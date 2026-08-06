package com.henry.wordcount

import android.util.Log
import java.io.File

/**
 * v1.5.6(WordCount): convert() 返回 DwgResult（含错误码和诊断文本），
 * 便于 UI 显示具体失败原因。
 *
 * 错误码含义（来自 JNI）：
 *   0   = 成功
 *  -1   = 参数空
 *  -2   = fopen 输出文件失败
 *  -10~-37 = dwg_read_file 失败
 *  -20~-47 = dwg_write_dxf 失败
 *  -99  = JNI 异常
 *  -100 = 原生库加载失败
 *  -3   = DXF 未生成(rc=0但空文件)
 */
object DwgConverter {

    @Volatile private var loaded = false

    @Synchronized fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("dwg2dxf")
            loaded = true
            Log.i("DwgConverter", "libdwg2dxf.so loaded OK")
            true
        } catch (e: Throwable) {
            Log.e("DwgConverter", "loadLibrary failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    external fun dwg2dxf(input: String, output: String): Int

    data class DwgResult(
        val path: String? = null,
        val errorCode: Int = 0,
        val diagText: String = ""
    )

    fun convert(dwgPath: String, dxfPath: String): DwgResult {
        if (!ensureLoaded()) {
            return DwgResult(errorCode = -100, diagText = "原生库加载失败")
        }

        File(dxfPath).delete()
        File(dxfPath.replaceAfterLast(".", "diag")).delete()

        return try {
            val rc = dwg2dxf(dwgPath, dxfPath)
            if (rc == 0) {
                val f = File(dxfPath)
                if (f.exists() && f.length() > 0) {
                    Log.i("DwgConverter", "OK: $dxfPath (${f.length()}B)")
                    DwgResult(path = dxfPath, errorCode = 0, diagText = readDiag(dxfPath))
                } else {
                    DwgResult(errorCode = -3, diagText = "DXF empty (rc=0)")
                }
            } else {
                val diag = readDiag(dxfPath)
                val msg = when {
                    rc < -50 -> "internal err $rc"
                    rc == -1 -> "null params"
                    rc == -2 -> "cannot open output"
                    rc in -37..-10 -> "DWG read fail (err=${-10-rc})"
                    rc in -47..-20 -> "DXF write fail (err=${-20-rc})"
                    else -> "unknown $rc"
                }
                Log.e("DwgConverter", "FAIL: $msg | diag=$diag")
                DwgResult(errorCode = rc, diagText = "$msg | $diag".trimEnd("| ").trim())
            }
        } catch (e: Throwable) {
            Log.e("DwgConverter", "exception: ${e.message}", e)
            DwgResult(errorCode = -99, diagText = "JNI exception: ${e.message}")
        }
    }

    private fun readDiag(dxfPath: String): String {
        val df = File(dxfPath.replaceAfterLast(".", "diag"))
        return if (df.exists()) try { df.readText().trim() } catch (_: Exception) { "" } else ""
    }
}
