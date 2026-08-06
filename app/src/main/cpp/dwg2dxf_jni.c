/*
 * dwg2dxf_jni.c — JNI bridge for LibreDWG dwg2dxf on Android (v1.5.6 diagnostics).
 *
 * v1.5.5: 首次 JNI 方案，.so 加载成功但转换全部失败（错误码未知）。
 * v1.5.6: 增加详细诊断——用 __android_log_print 记录每步错误，
 *         返回区分化的错误码（负数=JNI层错误，正数=LibreDWG错误码），
 *         并通过写入 .diag 文件让 Kotlin 侧读取详细信息。
 *
 * 错误码定义：
 *    0  = 成功
 *   -1  = 参数空（input/output jstring 为 null）
 *   -2  = 无法打开输出文件（fopen 失败）
 *   -10 = dwg_read_file 失败（返回值作为正错误码追加）
 *   -20 = dwg_write_dxf 失败（返回值作为正错误码追加）
 */

#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

/* LibreDWG public + internal headers */
#include "dwg.h"
#include "bits.h"

#define LOG_TAG "DwgNative"
#define DIAG_PATH_MAX 512

/* 写诊断文件供 Kotlin 侧读取 */
static void write_diag(const char *output_path, const char *msg)
{
    char diag_path[DIAG_PATH_MAX];
    FILE *fp;
    snprintf(diag_path, sizeof(diag_path), "%s", output_path);
    size_t len = strlen(diag_path);
    if (len > 4 && strcmp(diag_path + len - 4, ".dxf") == 0) {
        diag_path[len - 4] = '\0';
        strcat(diag_path, ".diag");
    } else {
        strcat(diag_path, ".diag");
    }
    fp = fopen(diag_path, "w");
    if (fp) {
        fputs(msg, fp);
        fclose(fp);
    }
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "DIAG: %s (written to %s)", msg, diag_path);
}

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

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "dwg2dxf started: input=%s output=%s", input, output);

    Dwg_Data dwg;
    memset(&dwg, 0, sizeof(Dwg_Data));
    dwg.opts = 1;

    /* Step 1: read DWG */
    int error = dwg_read_file((char *)input, &dwg);

    /* Log DWG version info */
    {
        char ver_buf[256];
        const char *ver_str = "unknown";
        if (error >= DWG_ERR_CRITICAL) {
            snprintf(ver_buf, sizeof(ver_buf),
                "READ_FAIL err=%d from_ver=0x%02x file_ver=0x%02x",
                error, (unsigned)dwg.header.from_version, (unsigned)dwg.header.version);
        } else {
            switch (dwg.header.from_version) {
                case 0x06: ver_str = "R9"; break;
                case 0x09: ver_str = "R10"; break;
                case 0x0A: ver_str = "R11"; break;
                case 0x0C: ver_str = "R13"; break;
                case 0x0D: ver_str = "R14"; break;
                case 0x0E: ver_str = "R2000(AC1015)"; break;
                case 0x0F: ver_str = "R2004(AC1018)"; break;
                case 0x10: ver_str = "R2007(AC1021)"; break;
                case 0x11: ver_str = "R2010(AC1024)"; break;
                case 0x12: ver_str = "R2013(AC1027)"; break;
                case 0x13: ver_str = "R2018(AC1032)"; break;
                default: ver_str = "unknown/new"; break;
            }
            snprintf(ver_buf, sizeof(ver_buf),
                "READ_OK from_ver=0x%02x(%s) file_ver=0x%02x num_objects=%ld",
                (unsigned)dwg.header.from_version, ver_str,
                (unsigned)dwg.header.version, (long)dwg.num_objects);
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "DWG header: %s", ver_buf);
        write_diag(output, ver_buf);
    }

    if (error >= DWG_ERR_CRITICAL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "dwg_read_file FAILED: error code %d", error);
        (*env)->ReleaseStringUTFChars(env, jinput, input);
        (*env)->ReleaseStringUTFChars(env, joutput, output);
        return -10 + error;
    }

    /* Step 2: write DXF */
    Bit_Chain dat = { 0 };
    dat.version = dwg.header.version;
    dat.from_version = dwg.header.from_version;
    dat.fh = fopen(output, "wb");
    if (!dat.fh) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "fopen output failed: %s", output);
        { char buf[256]; snprintf(buf,sizeof(buf),"WRITE_FOPEN_FAIL: %s",output); write_diag(output,buf); }
        dwg_free(&dwg);
        (*env)->ReleaseStringUTFChars(env, jinput, input);
        (*env)->ReleaseStringUTFChars(env, joutput, output);
        return -2;
    }

    error = dwg_write_dxf(&dat, &dwg);
    fclose(dat.fh);

    if (error >= DWG_ERR_CRITICAL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "dwg_write_dxf FAILED: error code %d", error);
        { char buf[256]; snprintf(buf,sizeof(buf),"WRITE_FAIL err=%d",error); write_diag(output,buf); }
        dwg_free(&dwg);
        (*env)->ReleaseStringUTFChars(env, jinput, input);
        (*env)->ReleaseStringUTFChars(env, joutput, output);
        return -20 + error;
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "dwg2dxf SUCCESS");
    { write_diag(output, "SUCCESS"); }

    dwg_free(&dwg);
    (*env)->ReleaseStringUTFChars(env, jinput, input);
    (*env)->ReleaseStringUTFChars(env, joutput, output);
    return 0;
}
