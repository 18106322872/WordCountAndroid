package com.henry.wordcount

import android.util.Log
import java.io.File

/**
 * v1.5.5(WordCount): DWG→DXF 转换改为 JNI 加载 .so 动态库（替代 v1.5.2~1.5.3 的 Runtime.exec 二进制方案）。
 *
 * 背景：
 *  - v1.5.1~1.5.3 三次尝试用 Runtime.exec() 执行 dwg2dxf 可执行二进制，均被 Android SELinux 拦截
 *    （禁止从 app 私有目录 exec 原生二进制）。即使 chmod 0755 + 设置工作目录 + 多种参数组合也无效。
 *  - v1.5.5 改为：把 LibreDWG 编译成 libdwg2dxf.so（NDK 交叉编译，链接 libdwg.a），
 *    通过 System.loadLibrary 加载（Android 原生支持，不受 SELinux 限制），JNI 调用完成转换。
 *
 * 用法：
 *   val rc = DwgConverter.convert(dwgPath, dxfPath)
 *   rc == 0 → 转换成功，dxfPath 可用；rc != 0 → 失败（LibreDWG 错误码或 -1/-2）
 */
object DwgConverter {

    @Volatile
    private var loaded = false

    /**
     * 加载原生库（只加载一次）。libs 目录由 Gradle 自动打包（jniLibs/arm64-v8a/libdwg2dxf.so）。
     * 若加载失败（如架构不匹配），catch 后由 convert() 返回错误。
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("dwg2dxf")
            loaded = true
            Log.i("DwgConverter", "libdwg2dxf.so 加载成功")
            true
        } catch (e: Throwable) {
            Log.e("DwgConverter", "加载 libdwg2dxf.so 失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * JNI native 函数：调用 LibreDWG 的 dwg_read_file + dwg_write_dxf 完成 DWG→DXF 转换。
     * @return 0=成功；LibreDWG 错误码（>=DWG_ERR_CRITICAL）或 -1(参数错误)/-2(无法打开输出文件)
     */
    @Suppress("MemberVisibilityCanBePrivate")
    external fun dwg2dxf(input: String, output: String): Int

    /**
     * 转换 DWG→DXF。
     * @param dwgPath 输入 .dwg 绝对路径
     * @param dxfPath 输出 .dxf 绝对路径（调用方指定，通常同目录同名）
     * @return 转换成功的 .dxf 文件绝对路径；失败返回 null
     */
    fun convert(dwgPath: String, dxfPath: String): String? {
        if (!ensureLoaded()) {
            Log.e("DwgConverter", "原生库未加载，无法转换")
            return null
        }

        // 清理可能存在的旧 DXF
        File(dxfPath).delete()

        return try {
            val rc = dwg2dxf(dwgPath, dxfPath)
            if (rc == 0) {
                val f = File(dxfPath)
                if (f.exists() && f.length() > 0) {
                    Log.i("DwgConverter", "转换成功: $dxfPath (${f.length()} bytes)")
                    dxfPath
                } else {
                    Log.e("DwgConverter", "转换返回 0 但 DXF 文件不存在/为空")
                    null
                }
            } else {
                Log.e("DwgConverter", "dwg2dxf 返回错误码: $rc")
                null
            }
        } catch (e: Throwable) {
            Log.e("DwgConverter", "调用 dwg2dxf 异常: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }
}
