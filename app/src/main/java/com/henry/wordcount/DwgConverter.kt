package com.henry.wordcount

import android.util.Log
import java.io.File

/**
 * v1.5.9: 新增 dwg2pdf() 原生函数（DWG -> PDF 导出看图）。
 * dwg2dxf() 保留用于字数统计管线（DWG -> DXF -> Python 统计）。
 *
 * 错误码含义（来自 JNI）：
 *   0   = 成功
 *  -1   = 参数空
 *  -2   = fopen 输出文件失败
 *  -10~-37 = dwg_read_file 失败
 *  -20~-47 = dwg_write_dxf 失败
 *  -30~-67 = dwg2pdf 内部 PDF 写失败（暂未细分，统一 -30）
 *  -99  = JNI 异常
 *  -100 = 原生库加载失败
 *  -3   = DXF 未生成(rc=0但空文件)
 */
object DwgConverter {

    @Volatile private var loaded = false
    /** v1.5.9: 记录 loadLibrary 失败的底层异常，供 UI 诊断（解决 -100 无详细信息问题） */
    @Volatile var loadError: String? = null

    @Synchronized fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("dwg2dxf")
            loaded = true
            loadError = null
            Log.i("DwgConverter", "libdwg2dxf.so loaded OK")
            true
        } catch (e: Throwable) {
            loadError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e("DwgConverter", "loadLibrary failed: $loadError", e)
            false
        }
    }

    external fun dwg2dxf(input: String, output: String): Int
    external fun dwg2pdf(input: String, output: String): Int

    data class DwgResult(
        val path: String? = null,
        val errorCode: Int = 0,
        val diagText: String = ""
    )

    /** DWG -> DXF（字数统计管线用） */
    fun convert(dwgPath: String, dxfPath: String): DwgResult {
        if (!ensureLoaded()) {
            return DwgResult(errorCode = -100, diagText = "原生库加载失败: ${loadError ?: "unknown"}")
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
                DwgResult(errorCode = rc, diagText = "$msg | $diag".trim().removeSuffix("|").trim())
            }
        } catch (e: Throwable) {
            Log.e("DwgConverter", "exception: ${e.message}", e)
            DwgResult(errorCode = -99, diagText = "JNI exception: ${e.message}")
        }
    }

    /** DWG -> PDF（导出看图用） */
    fun convertToPdf(dwgPath: String, pdfPath: String): DwgResult {
        if (!ensureLoaded()) {
            return DwgResult(errorCode = -100, diagText = "原生库加载失败: ${loadError ?: "unknown"}")
        }

        File(pdfPath).delete()
        File(pdfPath.replaceAfterLast(".", "diag")).delete()

        return try {
            val rc = dwg2pdf(dwgPath, pdfPath)
            if (rc == 0) {
                val f = File(pdfPath)
                if (f.exists() && f.length() > 0) {
                    Log.i("DwgConverter", "PDF OK: $pdfPath (${f.length()}B)")
                    DwgResult(path = pdfPath, errorCode = 0, diagText = readDiag(pdfPath))
                } else {
                    DwgResult(errorCode = -3, diagText = "PDF empty (rc=0)")
                }
            } else {
                val diag = readDiag(pdfPath)
                val msg = when {
                    rc == -1 -> "null params"
                    rc == -2 -> "cannot open output"
                    rc in -37..-10 -> "DWG read fail (err=${-10-rc})"
                    rc == -30 -> "PDF write failed"
                    else -> "unknown $rc"
                }
                Log.e("DwgConverter", "PDF FAIL: $msg | diag=$diag")
                DwgResult(errorCode = rc, diagText = "$msg | $diag".trim().removeSuffix("|").trim())
            }
        } catch (e: Throwable) {
            Log.e("DwgConverter", "pdf exception: ${e.message}", e)
            DwgResult(errorCode = -99, diagText = "JNI exception: ${e.message}")
        }
    }

    private fun readDiag(outPath: String): String {
        val df = File(outPath.replaceAfterLast(".", "diag"))
        return if (df.exists()) try { df.readText().trim() } catch (_: Exception) { "" } else ""
    }
}
