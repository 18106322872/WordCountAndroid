/*
 * dwg2dxf_jni.c — JNI bridge for LibreDWG dwg2dxf on Android.
 *
 * v1.5.5(WordCount): 之前尝试把 dwg2dxf 编译成可执行二进制 + Runtime.exec()
 * 在 Android 上调用，被 SELinux 拦截（禁止从 app 私有目录 exec 原生二进制）。
 * 本文件改用 **JNI 加载 .so 动态库** 方式（Android 原生支持，不受 SELinux 限制）：
 *   Kotlin 侧 System.loadLibrary("dwg2dxf") → 调本文件的 native 函数 →
 *   内部调用 LibreDWG 的 dwg_read_file + dwg_write_dxf 完成 DWG→DXF 转换。
 *
 * 编译（CI，NDK aarch64）：
 *   aarch64-linux-android<API>-clang --shared -fPIC \
 *     -I<libredwg>/src -I<libredwg>/include \
 *     dwg2dxf_jni.c <libredwg>/src/.libs/libdwg.a \
 *     -o libdwg2dxf.so
 */

#include <jni.h>
#include <string.h>
#include <stdio.h>

/* LibreDWG public + internal headers (paths provided at compile time via -I) */
#include "dwg.h"
#include "bits.h"

/*
 * Java signature:
 *   package com.henry.wordcount;
 *   object DwgConverter {
 *     external fun dwg2dxf(input: String, output: String): Int
 *   }
 *
 * Returns 0 on success, non-zero (LibreDWG error code or -1/-2) on failure.
 */
JNIEXPORT jint JNICALL
Java_com_henry_wordcount_DwgConverter_dwg2dxf(JNIEnv *env, jobject thiz,
                                              jstring jinput, jstring joutput)
{
    const char *input = (*env)->GetStringUTFChars(env, jinput, NULL);
    const char *output = (*env)->GetStringUTFChars(env, joutput, NULL);
    if (!input || !output) {
        if (input)  (*env)->ReleaseStringUTFChars(env, jinput, input);
        if (output) (*env)->ReleaseStringUTFChars(env, joutput, output);
        return -1;
    }

    Dwg_Data dwg;
    memset(&dwg, 0, sizeof(Dwg_Data));
    dwg.opts = 1;   /* default verbosity */

    int error = dwg_read_file((char *)input, &dwg);
    if (error >= DWG_ERR_CRITICAL) {
        (*env)->ReleaseStringUTFChars(env, jinput, input);
        (*env)->ReleaseStringUTFChars(env, joutput, output);
        return error;   /* read failed */
    }

    Bit_Chain dat = { 0 };
    dat.version = dwg.header.version;
    dat.from_version = dwg.header.from_version;
    dat.fh = fopen(output, "wb");
    if (!dat.fh) {
        dwg_free(&dwg);
        (*env)->ReleaseStringUTFChars(env, jinput, input);
        (*env)->ReleaseStringUTFChars(env, joutput, output);
        return -2;   /* cannot open output file */
    }

    error = dwg_write_dxf(&dat, &dwg);
    fclose(dat.fh);
    dwg_free(&dwg);

    (*env)->ReleaseStringUTFChars(env, jinput, input);
    (*env)->ReleaseStringUTFChars(env, joutput, output);

    return (error >= DWG_ERR_CRITICAL) ? error : 0;
}
