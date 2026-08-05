package com.henry.wordcount

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * v1.5.1(WordCount): 从 APK assets 提取 dwg2dxf 原生二进制到应用私有目录并赋可执行权限。
 *
 * 设计要点：
 *  - dwg2dxf 是 CI 用 Android NDK 交叉编译出的 arm64 静态二进制，打包在 app/src/main/assets/dwg2dxf。
 *  - 若 CI 交叉编译失败，APK 不含该二进制 → ensureBinary 返回 null → 上层显示"无法统计.dwg文件"（回退）。
 *  - 仅在文件不存在或版本标记变化时重写，避免每次启动都 I/O。
 */
object DwgConverter {
    private const val ASSET_NAME = "dwg2dxf"
    private const val VERSION = 1  // 改 dwg2dxf 二进制时 +1 强制重提取

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
}
