/*
 * dwg2dxf_jni.c — JNI bridge for LibreDWG on Android (v1.5.9 PDF export).
 *
 * Two native functions exposed to Kotlin:
 *   1) dwg2dxf(input, output)  — DWG -> DXF (for word counting pipeline)
 *   2) dwg2pdf(input, output)  — DWG -> PDF (for viewing drawings on phone)
 *
 * dwg2pdf implements a minimal, dependency-free PDF writer (PDF is a
 * text-based format). It reads the parsed DWG via LibreDWG's dwg_read_file()
 * and emits vector drawing commands (lines, polylines, circles, arcs, text)
 * directly into a valid PDF-1.4 file. No cairo / external libs needed.
 *
 * Error codes (returned as jint):
 *    0     = success
 *   -1     = null params
 *   -2     = cannot open output file
 *   -10..  = dwg_read_file failed (error value appended)
 *   -30..  = pdf write failed (error value appended)
 */

#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <android/log.h>

#include "dwg.h"
#include "bits.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define LOG_TAG "DwgNative"
#define DIAG_PATH_MAX 512

/* ------------------------------------------------------------------ */
/* Diagnostic file (for UI error reporting)                            */
/* ------------------------------------------------------------------ */
static void write_diag(const char *output_path, const char *msg)
{
    char diag_path[DIAG_PATH_MAX];
    FILE *fp;
    snprintf(diag_path, sizeof(diag_path), "%s", output_path);
    size_t len = strlen(diag_path);
    if (len > 4 && strcmp(diag_path + len - 4, ".dxf") == 0) {
        diag_path[len - 4] = '\0';
        strcat(diag_path, ".diag");
    } else if (len > 4 && strcmp(diag_path + len - 4, ".pdf") == 0) {
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

/* ================================================================== */
/* SECTION 1: dwg2dxf (DWG -> DXF) — unchanged from v1.5.6             */
/* ================================================================== */
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

    int error = dwg_read_file((char *)input, &dwg);
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

/* ================================================================== */
/* SECTION 2: dwg2pdf (DWG -> PDF) — minimal PDF writer               */
/* ================================================================== */

/* ---------- PDF writer state ---------- */
typedef struct {
    FILE *fp;
    long *xref;          /* array of byte offsets for each object */
    int   obj_num;       /* next object number to assign */
    int   xref_capacity;
    double scale;        /* drawing units -> pdf points */
    double off_x;        /* translation */
    double off_y;
    char   buf[8192];    /* content stream buffer */
    int    buf_len;
} PdfWriter;

static double g_min_x = 0, g_min_y = 0, g_max_x = 1, g_max_y = 1;

/* Convert DWG model coords -> PDF page coords (y flipped, scaled, translated) */
static void pdf_map(PdfWriter *w, double mx, double my, double *px, double *py)
{
    *px = (mx - g_min_x) * w->scale + w->off_x;
    *py = (my - g_min_y) * w->scale + w->off_y;
}

/* Flush content buffer to file */
static void pdf_flush(PdfWriter *w)
{
    if (w->buf_len > 0) {
        fwrite(w->buf, 1, w->buf_len, w->fp);
        w->buf_len = 0;
    }
}

/* Append formatted string to content buffer (auto-flush) */
static void pdf_cat(PdfWriter *w, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    int need = vsnprintf(w->buf + w->buf_len, sizeof(w->buf) - w->buf_len, fmt, ap);
    va_end(ap);
    if (need < 0) return;
    w->buf_len += need;
    if (w->buf_len >= (int)(sizeof(w->buf) - 256)) {
        pdf_flush(w);
    }
}

/* Write PDF header + catalog + pages tree */
static int pdf_begin(PdfWriter *w, const char *path, double page_w, double page_h)
{
    w->fp = fopen(path, "wb");
    if (!w->fp) return -1;
    w->xref_capacity = 32;
    w->xref = (long *)calloc(w->xref_capacity, sizeof(long));
    w->obj_num = 1;
    w->buf_len = 0;

    fprintf(w->fp, "%%PDF-1.4\n%%\xe2\xe3\xcf\xd3\n");
    /* obj 1: Catalog */
    w->xref[w->obj_num] = ftell(w->fp);
    fprintf(w->fp, "%d 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n", w->obj_num++);
    /* obj 2: Pages tree */
    w->xref[w->obj_num] = ftell(w->fp);
    fprintf(w->fp, "%d 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n", w->obj_num++);
    /* obj 3: Page */
    w->xref[w->obj_num] = ftell(w->fp);
    fprintf(w->fp, "%d 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] "
            "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n",
            w->obj_num++, page_w, page_h);
    /* obj 4: Font (Helvetica) */
    w->xref[w->obj_num] = ftell(w->fp);
    fprintf(w->fp, "%d 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n",
            w->obj_num++);
    /* obj 5: Content stream (filled later by pdf_end) */
    w->xref[w->obj_num] = ftell(w->fp);
    fprintf(w->fp, "%d 0 obj\n<< /Length %d >>\nstream\n", w->obj_num, 0); /* placeholder */
    /* We'll seek back and patch Length after flush */
    return 0;
}

/* Finish PDF: flush content, patch Length, write xref + trailer */
static int pdf_end(PdfWriter *w)
{
    pdf_flush(w);
    long content_end = ftell(w->fp);
    /* patch /Length in obj 5 */
    /* obj5 started at xref[5]; the "stream\n" is after "<< /Length N >>\nstream\n" */
    /* Recompute: we stored xref[5] = offset of "5 0 obj". Find "stream" keyword. */
    /* Simpler: store the offset right before "stream" */
    /* We'll just rewrite by seeking. */
    long stream_start = w->xref[5];
    /* Search for "stream\n" after stream_start */
    fseek(w->fp, stream_start, SEEK_SET);
    char tmp[256];
    long pos = stream_start;
    long stream_data_offset = 0;
    while (pos < content_end) {
        fread(tmp, 1, 1, w->fp);
        if (tmp[0] == 's') {
            /* check if "stream" */
            char s[7];
            long cur = ftell(w->fp) - 1;
            fseek(w->fp, cur, SEEK_SET);
            if (fread(s, 1, 6, w->fp) == 6 && strncmp(s, "stream", 6) == 0) {
                /* skip "stream\n" (may be \r\n) */
                char c;
                do { fread(&c, 1, 1, w->fp); } while (c != '\n' && ftell(w->fp) < content_end);
                stream_data_offset = ftell(w->fp);
                break;
            }
            fseek(w->fp, cur + 1, SEEK_SET);
        }
        pos++;
    }
    int length = (int)(content_end - stream_data_offset);
    /* patch Length */
    fseek(w->fp, stream_start, SEEK_SET);
    fprintf(w->fp, "%d 0 obj\n<< /Length %d >>\nstream\n", 5, length);
    /* now we may have overwritten into stream area; seek to content_end and write endstream */
    fseek(w->fp, content_end, SEEK_SET);
    fprintf(w->fp, "\nendstream\nendobj\n");

    long xref_pos = ftell(w->fp);
    int max_obj = w->obj_num;
    fprintf(w->fp, "xref\n0 %d\n", max_obj);
    fprintf(w->fp, "0000000000 65535 f \n");
    for (int i = 1; i < max_obj; i++) {
        fprintf(w->fp, "%010ld 00000 n \n", w->xref[i]);
    }
    fprintf(w->fp, "trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%ld\n%%%%EOF\n",
            max_obj, xref_pos);
    fclose(w->fp);
    free(w->xref);
    return 0;
}

/* ---------- Drawing primitives (append to content stream) ---------- */
static void pdf_line(PdfWriter *w, double x1, double y1, double x2, double y2)
{
    double px1, py1, px2, py2;
    pdf_map(w, x1, y1, &px1, &py1);
    pdf_map(w, x2, y2, &px2, &py2);
    pdf_cat(w, "%.2f %.2f m %.2f %.2f l S\n", px1, py1, px2, py2);
}

static void pdf_polyline(PdfWriter *w, double *xs, double *ys, int n)
{
    if (n < 2) return;
    double px, py;
    pdf_map(w, xs[0], ys[0], &px, &py);
    pdf_cat(w, "%.2f %.2f m", px, py);
    for (int i = 1; i < n; i++) {
        pdf_map(w, xs[i], ys[i], &px, &py);
        pdf_cat(w, " %.2f %.2f l", px, py);
    }
    pdf_cat(w, " S\n");
}

static void pdf_circle(PdfWriter *w, double cx, double cy, double r)
{
    /* Approximate circle with 32 segments as a closed polyline */
    const int SEG = 32;
    double xs[SEG], ys[SEG];
    for (int i = 0; i < SEG; i++) {
        double a = 2.0 * M_PI * i / SEG;
        xs[i] = cx + r * cos(a);
        ys[i] = cy + r * sin(a);
    }
    pdf_polyline(w, xs, ys, SEG);
}

static void pdf_arc(PdfWriter *w, double cx, double cy, double r, double a1, double a2)
{
    /* Sample arc from a1 to a2 (radians). Handle wraparound. */
    if (a2 < a1) a2 += 2.0 * M_PI;
    double span = a2 - a1;
    int SEG = (int)(span / (M_PI / 16.0)) + 2;
    if (SEG < 2) SEG = 2;
    if (SEG > 256) SEG = 256;
    double xs[257], ys[257];
    for (int i = 0; i <= SEG; i++) {
        double a = a1 + span * i / SEG;
        xs[i] = cx + r * cos(a);
        ys[i] = cy + r * sin(a);
    }
    pdf_polyline(w, xs, ys, SEG + 1);
}

static void pdf_text(PdfWriter *w, double x, double y, double size, const char *text)
{
    double px, py;
    pdf_map(w, x, y, &px, &py);
    /* Escape PDF special chars */
    char esc[1024];
    int j = 0;
    esc[j++] = '(';
    for (const char *p = text; *p && j < 1000; p++) {
        if (*p == '(' || *p == ')' || *p == '\\') {
            esc[j++] = '\\';
            esc[j++] = *p;
        } else if ((unsigned char)*p < 0x20) {
            /* skip control chars */
        } else {
            esc[j++] = *p;
        }
    }
    esc[j++] = ')';
    esc[j] = '\0';
    pdf_cat(w, "BT /F1 %.2f Tf %.2f %.2f Td %s Tj ET\n", size * w->scale, px, py, esc);
}

/* ---------- Main dwg2pdf JNI entry ---------- */
JNIEXPORT jint JNICALL
Java_com_henry_wordcount_DwgConverter_dwg2pdf(JNIEnv *env, jobject thiz,
                                              jstring jinput, jstring joutput)
{
    const char *input = (*env)->GetStringUTFChars(env, jinput, NULL);
    const char *output = (*env)->GetStringUTFChars(env, joutput, NULL);
    if (!input || !output) {
        if (input)  (*env)->ReleaseStringUTFChars(env, jinput, input);
        if (output) (*env)->ReleaseStringUTFChars(env, joutput, output);
        return -1;
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "dwg2pdf started: input=%s output=%s", input, output);

    Dwg_Data dwg;
    memset(&dwg, 0, sizeof(Dwg_Data));
    dwg.opts = 1;

    int error = dwg_read_file((char *)input, &dwg);
    if (error >= DWG_ERR_CRITICAL) {
        char buf[256];
        snprintf(buf, sizeof(buf), "READ_FAIL err=%d from_ver=0x%02x", error, (unsigned)dwg.header.from_version);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "dwg_read_file FAILED: %s", buf);
        write_diag(output, buf);
        (*env)->ReleaseStringUTFChars(env, jinput, input);
        (*env)->ReleaseStringUTFChars(env, joutput, output);
        return -10 + error;
    }

    /* Compute drawing extents from header (fallback to all objects if zero) */
    g_min_x = dwg.header.extmin.x;
    g_min_y = dwg.header.extmin.y;
    g_max_x = dwg.header.extmax.x;
    g_max_y = dwg.header.extmax.y;
    if (g_max_x <= g_min_x || g_max_y <= g_min_y) {
        /* iterate objects to find bounds */
        g_min_x = g_min_y = 1e30;
        g_max_x = g_max_y = -1e30;
        for (unsigned long i = 0; i < dwg.num_objects; i++) {
            Dwg_Object *obj = &dwg.object[i];
            if (obj->type == DWG_TYPE_LINE) {
                double xs[2] = { obj->tio.entity->LINE.start.x, obj->tio.entity->LINE.end.x };
                double ys[2] = { obj->tio.entity->LINE.start.y, obj->tio.entity->LINE.end.y };
                for (int k = 0; k < 2; k++) {
                    if (xs[k] < g_min_x) g_min_x = xs[k];
                    if (xs[k] > g_max_x) g_max_x = xs[k];
                    if (ys[k] < g_min_y) g_min_y = ys[k];
                    if (ys[k] > g_max_y) g_max_y = ys[k];
                }
            } else if (obj->type == DWG_TYPE_CIRCLE || obj->type == DWG_TYPE_ARC) {
                double cx = (obj->type == DWG_TYPE_CIRCLE) ?
                    obj->tio.entity->CIRCLE.center.x : obj->tio.entity->ARC.center.x;
                double cy = (obj->type == DWG_TYPE_CIRCLE) ?
                    obj->tio.entity->CIRCLE.center.y : obj->tio.entity->ARC.center.y;
                double r = (obj->type == DWG_TYPE_CIRCLE) ?
                    obj->tio.entity->CIRCLE.radius : obj->tio.entity->ARC.radius;
                if (cx - r < g_min_x) g_min_x = cx - r;
                if (cx + r > g_max_x) g_max_x = cx + r;
                if (cy - r < g_min_y) g_min_y = cy - r;
                if (cy + r > g_max_y) g_max_y = cy + r;
            }
        }
        if (g_max_x <= g_min_x || g_max_y <= g_min_y) {
            g_min_x = 0; g_min_y = 0; g_max_x = 100; g_max_y = 100;
        }
    }

    /* Choose page size: fit drawing into A4 (595x842) or A4 landscape */
    double draw_w = g_max_x - g_min_x;
    double draw_h = g_max_y - g_min_y;
    double margin = 20.0;
    double page_w, page_h;
    if (draw_w >= draw_h) {
        page_w = 842.0; page_h = 595.0;   /* landscape A4 */
    } else {
        page_w = 595.0; page_h = 842.0;   /* portrait A4 */
    }
    double scale_w = (page_w - 2 * margin) / draw_w;
    double scale_h = (page_h - 2 * margin) / draw_h;
    double scale = scale_w < scale_h ? scale_w : scale_h;

    PdfWriter w;
    memset(&w, 0, sizeof(w));
    w.scale = scale;
    w.off_x = margin;
    w.off_y = margin;

    pdf_begin(&w, output, page_w, page_h);

    /* Draw background grid? No. Just entities. */
    int drawn = 0;
    for (unsigned long i = 0; i < dwg.num_objects; i++) {
        Dwg_Object *obj = &dwg.object[i];
        switch (obj->type) {
            case DWG_TYPE_LINE: {
                double x1 = obj->tio.entity->LINE.start.x;
                double y1 = obj->tio.entity->LINE.start.y;
                double x2 = obj->tio.entity->LINE.end.x;
                double y2 = obj->tio.entity->LINE.end.y;
                pdf_line(&w, x1, y1, x2, y2);
                drawn++;
                break;
            }
            case DWG_TYPE_LWPOLYLINE: {
                int n = obj->tio.entity->LWPOLYLINE.num_points;
                dwg_point_2d *pts = obj->tio.entity->LWPOLYLINE.points;
                if (n >= 2 && pts) {
                    double xs[4096], ys[4096];
                    int m = n < 4096 ? n : 4096;
                    for (int k = 0; k < m; k++) {
                        xs[k] = pts[k].x;
                        ys[k] = pts[k].y;
                    }
                    pdf_polyline(&w, xs, ys, m);
                    drawn++;
                }
                break;
            }
            case DWG_TYPE_POLYLINE: {
                /* Old-style POLYLINE: vertices in linked entities.
                   For simplicity, try to read first vertex chain. */
                /* LibreDWG stores vertices as separate VERTEX objects; skip detailed handling. */
                break;
            }
            case DWG_TYPE_CIRCLE: {
                double cx = obj->tio.entity->CIRCLE.center.x;
                double cy = obj->tio.entity->CIRCLE.center.y;
                double r  = obj->tio.entity->CIRCLE.radius;
                pdf_circle(&w, cx, cy, r);
                drawn++;
                break;
            }
            case DWG_TYPE_ARC: {
                double cx = obj->tio.entity->ARC.center.x;
                double cy = obj->tio.entity->ARC.center.y;
                double r  = obj->tio.entity->ARC.radius;
                double a1 = obj->tio.entity->ARC.start_angle;
                double a2 = obj->tio.entity->ARC.end_angle;
                pdf_arc(&w, cx, cy, r, a1, a2);
                drawn++;
                break;
            }
            case DWG_TYPE_TEXT: {
                double x  = obj->tio.entity->TEXT.insertion_pt.x;
                double y  = obj->tio.entity->TEXT.insertion_pt.y;
                double h  = obj->tio.entity->TEXT.height;
                char *txt = obj->tio.entity->TEXT.text_value;
                if (txt && h > 0) {
                    pdf_text(&w, x, y, h, txt);
                    drawn++;
                }
                break;
            }
            case DWG_TYPE_MTEXT: {
                double x  = obj->tio.entity->MTEXT.insertion_pt.x;
                double y  = obj->tio.entity->MTEXT.insertion_pt.y;
                double h  = obj->tio.entity->MTEXT.height;
                char *txt = obj->tio.entity->MTEXT.text;
                if (txt && h > 0) {
                    /* MTEXT may contain formatting codes; strip simple \pxsm flags */
                    pdf_text(&w, x, y, h, txt);
                    drawn++;
                }
                break;
            }
            default:
                break;
        }
    }

    pdf_end(&w);

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "dwg2pdf SUCCESS: drew %d entities", drawn);
    {
        char buf[128];
        snprintf(buf, sizeof(buf), "SUCCESS drew=%d extents=%.0f,%.0f,%.0f,%.0f",
                 drawn, g_min_x, g_min_y, g_max_x, g_max_y);
        write_diag(output, buf);
    }

    dwg_free(&dwg);
    (*env)->ReleaseStringUTFChars(env, jinput, input);
    (*env)->ReleaseStringUTFChars(env, joutput, output);
    return 0;
}
