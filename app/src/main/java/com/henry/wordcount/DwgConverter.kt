package com.henry.wordcount

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * v1.5.3(WordCount): 从 APK assets 提取 dwg2dxf 原生二进制到应用私有目录并赋可执行权限。
 *
 * 设计要点：
 *  - dwg2dxf 是 CI 用 Android NDK 交叉编译出的 arm64 静态二进制，打包在 app/src/main/assets/dwg2dxf。
 *  - 若 CI 交叉编译失败，APK 不含该二进制 → ensureBinary 返回 null → 上层显示"无法统计.dwg文件"（回退）。
 *  - 仅在文件不存在或版本标记变化时重写，避免每次启动都 I/O。
 *  - v1.5.2: convert() 用 Runtime.exec() 在 Kotlin 侧执行转换（绕开 Chaquopy subprocess 的 AssetFinder/scripts bug）
 *  - v1.5.3: 改用 ProcessBuilder + 设置工作目录 + 多种参数组合重试（修复"dwg2dxf 转换失败"）
 */
object DwgConverter {
    private const val ASSET_NAME = "dwg2dxf"
    private const val VERSION = 2  // v1.5.3: +1 强制重提取（逻辑变更）

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
                Log.d("DwgConverter", "已提取 $ASSET_NAME -> ${outFile.absolutePath} (${outFile.length()} bytes)")
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
     * 用 ProcessBuilder 执行 dwg2dxf 转换（v1.5.3 重写：设置工作目录 + 多策略重试）。
     *
     * 关键修复（v1.5.3）：
     *   1. 使用 ProcessBuilder 替代 Runtime.exec()，可设置工作目录和环境变量
     *   2. 工作目录设为 DWG 文件所在目录（dwg2dxf 默认在 CWD 输出 .dxf）
     *   3. 多种参数组合重试（-o 在前/在后、-y 覆盖、无 -o 自动命名）
     *   4. 详细的 stderr/stdout 日志用于真机调试
     *
     * @param dwgPath 输入 .dwg 文件绝对路径
     * @param converterBinary ensureBinary() 返回的 dwg2dxf 二进制路径
     * @return 转换成功的 .dxf 文件绝对路径；失败返回 null（日志记录原因）
     */
    fun convert(dwgPath: String, converterBinary: String): String? {
        val dwgFile = File(dwgPath)
        val dxfPath = dwgPath.removeSuffix(".dwg") + ".dxf"

        // 清理可能存在的旧 DXF
        File(dxfPath).delete()

        // 工作目录：DWG 文件所在目录（dwg2dxf 默认在此输出）
        val workDir = dwgFile.parentFile ?: File("/data/local/tmp")

        // 记录二进制信息
        Log.i("DwgConverter", "开始转换: dwg=$dwgPath, bin=$converterBinary, workDir=${workDir.absolutePath}")
        Log.i("DwgConverter", "二进制大小: ${File(converterBinary).length()} bytes, 可执行: ${File(converterBinary).canExecute()}")

        // ── 策略列表：按优先级尝试不同参数组合 ──
        val strategies = listOf(
            // 策略1：-o 输出路径 + -y 覆盖（标准用法，-o 在文件后）
            listOf("-y", "-o", dxfPath, dwgPath),
            // 策略2：-y + 文件在前、-o 在后（某些版本支持）
            listOf("-y", dwgPath, "-o", dxfPath),
            // 策略3：仅 -y 不指定输出（自动在 CWD 生成同名 .dxf）
            listOf("-y", dwgPath),
            // 策略4：不加任何选项（最简模式）
            listOf(dwgPath),
        )

        for ((idx, argsExtra) in strategies.withIndex()) {
            val fullArgs = mutableListOf(converterBinary)
            fullArgs.addAll(argsExtra)

            Log.d("DwgConverter", "策略 ${idx + 1}: ${fullArgs.joinToString(" ")}")

            try {
                val pb = ProcessBuilder(fullArgs)
                    .directory(workDir)
                    .redirectErrorStream(true)  // 合并 stderr 到 stdout

                // 清除环境变量干扰（Android 进程环境可能有问题）
                pb.environment()["HOME"] = workDir.absolutePath
                pb.environment()["TMPDIR"] = contextTempDir()

                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val rc = proc.waitFor()

                Log.d("DwgConverter", "策略 ${idx + 1}: rc=$rc, output=${output.take(500)}")

                // 检查多种可能的 DXF 输出位置
                val candidates = mutableListOf<String>().apply {
                    add(dxfPath)                                    // 显式指定的路径
                    add(File(workDir, dwgFile.nameWithoutExtension + ".dxf").absolutePath)  // CWD 同名
                    add(File(workDir, dwgFile.name + ".dxf").absolutePath)                // CWD 原名+.dxf
                }.distinct()

                for (candidate in candidates) {
                    val f = File(candidate)
                    if (f.exists() && f.length() > 0) {
                        Log.i("DwgConverter", "✅ 转换成功(策略${idx+1}): ${f.absolutePath} (${f.length()} bytes)")
                        return f.absolutePath
                    }
                }

                // 此策略失败，继续下一个
                Log.w("DwgConverter", "策略 ${idx + 1} 未生成 DXF 文件, rc=$rc")

            } catch (e: Exception) {
                Log.e("DwgConverter", "策略 ${idx + 1} 异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // 所有策略都失败 → 列出工作目录内容帮助诊断
        Log.e("DwgConverter", "❌ 所有策略均失败。工作目录内容:")
        workDir.listFiles()?.forEach { f ->
            Log.e("DwgConverter", "  ${f.name} (${f.length()} bytes)")
        }
        return null
    }

    /** 获取可用的临时目录 */
    private fun contextTempDir(): String {
        val tmpDirs = listOf(
            System.getProperty("java.io.tmpdir"),
            "/data/local/tmp",
            "/sdcard/Android/data/com.henry.wordcount2/cache",
            "/data/data/com.henry.wordcount2/cache"
        )
        for (dir in tmpDirs) {
            if (dir != null && File(dir).exists()) return dir
        }
        return "/data/local/tmp"
    }
}
