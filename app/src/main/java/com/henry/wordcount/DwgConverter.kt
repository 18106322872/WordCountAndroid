package com.henry.wordcount

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * v1.5.2(WordCount): 从 APK assets 提取 dwg2dxf 原生二进制到应用私有目录并赋可执行权限。
 *
 * 设计要点：
 *  - dwg2dxf 是 CI 用 Android NDK 交叉编译出的 arm64 静态二进制，打包在 app/src/main/assets/dwg2dxf。
 *  - 若 CI 交叉编译失败，APK 不含该二进制 → ensureBinary 返回 null → 上层显示"无法统计.dwg文件"（回退）。
 *  - 仅在文件不存在或版本标记变化时重写，避免每次启动都 I/O。
 *  - v1.5.2: convert() 用 Runtime.exec() 在 Kotlin 侧执行转换（绕开 Chaquopy subprocess 的 AssetFinder/scripts bug）
 */
object DwgConverter {
    private const val ASSET_NAME = "dwg2dxf"
    private const val VERSION = 1  // 改 dwg2dxf 二进制时 +1 强制重提取

    /**
     * 确保 dwg2dxf 二进制已提取到 filesDir/bin/ 并可执行。返回二进制路径，失败返回 null。
     */
    @Synchronized
    fun ensureBinary(context: Context): String? {
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()
        val outFile = File(binDir, ASSET_NAME)
        val marker = File(binDir, "$ASSET_NAME.ver")

        // 资源是否存在
        val assetNames = runCatching { context.assets.list("") }.getOrElse { emptyArray() }
        if (assetNames == null || !assetNames.contains(ASSET_NAME)) {
            Log.w("DwgConverter", "assets 中未找到 $ASSET_NAME（APK 未打包 dwg2dxf）")
            return null
        }

        return try {
            val needExtract = !outFile.exists() || !marker.exists() ||
                    runCatching { marker.readText() }.getOrNull() != VERSION.toString()
            if (needExtract) {
                context.assets.open(ASSET_NAME).use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                // 赋予可执行权限（Android 要求显式 setExecutable）
                outFile.setExecutable(true, false)
                marker.writeText(VERSION.toString())
                Log.d("DwgConverter", "已提取 $ASSET_NAME -> ${outFile.absolutePath}")
            }
            // 兜底确保可执行位
            if (!outFile.canExecute()) outFile.setExecutable(true, false)
            if (outFile.canExecute()) outFile.absolutePath else null
        } catch (e: Throwable) {
            Log.e("DwgConverter", "提取 $ASSET_NAME 失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 用 Runtime.exec() 执行 dwg2dxf 转换（Kotlin 侧，绕开 Chaquopy subprocess 的 AssetFinder/scripts bug）。
     *
     * @param dwgPath 输入 .dwg 文件绝对路径
     * @param converterBinary ensureBinary() 返回的 dwg2dxf 二进制路径
     * @return 转换成功的 .dxf 文件绝对路径；失败返回 null（日志记录原因）
     */
    fun convert(dwgPath: String, converterBinary: String): String? {
        // 输出 DXF 路径：与 dwg 同目录同名，扩展名改 .dxf
        val dxfPath = dwgPath.removeSuffix(".dwg") + ".dxf"
        // 先清理可能存在的旧 DXF
        File(dxfPath).delete()

        try {
            val proc = Runtime.getRuntime().exec(arrayOf(converterBinary, "-o", dxfPath, dwgPath))
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val rc = proc.waitFor()

            if (rc != 0) {
                Log.e("DwgConverter", "dwg2dxf 返回码 $rc, stderr=$stderr, stdout=$stdout")
            }

            // dwg2dxf 有时不认 -o 参数，直接输出到同目录
            val altDxf = dwgPath.removeSuffix(".dwg") + ".dxf"
            val targetDxf = File(if (File(dxfPath).exists()) dxfPath else altDxf)

            if (targetDxf.exists() && targetDxf.length() > 0) {
                Log.d("DwgConverter", "转换成功: ${targetDxf.absolutePath} (${targetDxf.length()} bytes)")
                return targetDxf.absolutePath
            }

            Log.e("DwgConverter", "dwg2dxf 转换后未生成 DXF 文件 (rc=$rc)")
            return null
        } catch (e: Exception) {
            Log.e("DwgConverter", "执行 dwg2dxf 失败: ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        }
    }
}
