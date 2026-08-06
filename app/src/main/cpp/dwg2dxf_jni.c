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
    long   len_off;      /* file offset of the Length digits in obj 5 */
    long   stream_off;   /* file offset where content stream data begins */
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
    /* obj 5: Content stream (Length patched in place by pdf_end) */
    w->xref[w->obj_num] = ftell(w->fp);
    fprintf(w->fp, "%d 0 obj\n<< /Length ", w->obj_num++);
    w->len_off = ftell(w->fp);
    fprintf(w->fp, "0000000000 >>\nstream\n");
    w->stream_off = ftell(w->fp);
    return 0;
}

/* Finish PDF: flush content, patch Length in place, write xref + trailer */
static int pdf_end(PdfWriter *w)
{
    pdf_flush(w);
    long content_end = ftell(w->fp);
    long length = content_end - w->stream_off;
    /* Patch Length in place using a fixed 10-digit width (no byte shift). */
    fseek(w->fp, w->len_off, SEEK_SET);
    fprintf(w->fp, "%010d", (int)length);
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

/* ---------- DXF-based geometry extraction (version-proof) ----------
 * Instead of touching LibreDWG's internal entity structs (which change
 * between releases), we ask LibreDWG to emit a temporary ASCII DXF via
 * dwg_write_dxf(), then parse the stable DXF group-code text format to
 * extract LINE / LWPOLYLINE / CIRCLE / ARC / TEXT / MTEXT geometry and
 * draw it into the PDF. Only dwg_read_file + dwg_write_dxf are used.
 * ------------------------------------------------------------------ */

#ifndef MAX_DXF_LINE
#define MAX_DXF_LINE 1024
#endif

/* Parse a DXF group-code line. Returns 1 if the line is a valid integer
 * group code (0..65535), else 0 (stray/non-numeric line to be skipped). */
static int dxf_parse_code(const char *s, int *ok)
{
    char *end;
    long v = strtol(s, &end, 10);
    *ok = (end != s) && (*end == '\0' || *end == '\n' || *end == '\r' || *end == ' ');
    return (int)v;
}

/* Read the next (code, value) pair from a DXF stream. Returns 1 on success,
 * 0 on EOF. Skips stray non-numeric lines so pairing stays in sync. */
static int dxf_next(FILE *f, int *code, char *val, int vbsize)
{
    char line[MAX_DXF_LINE];
    for (;;) {
        if (!fgets(line, sizeof(line), f)) return 0;
        int ok;
        int c = dxf_parse_code(line, &ok);
        if (!ok) continue;            /* skip stray line */
        *code = c;
        if (!fgets(val, vbsize, f)) return 0;
        size_t L = strlen(val);
        while (L > 0 && (val[L - 1] == '\n' || val[L - 1] == '\r')) val[--L] = '\0';
        return 1;
    }
}

/* Pass 1: compute drawing extents (incl. circle/arc radius padding). */
static int dxf_bounds(const char *path, double *mnx, double *mny,
                      double *mxx, double *mxy)
{
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    *mnx = *mny =  1e30;
    *mxx = *mxy = -1e30;
    int in_ent = 0, code;
    char val[MAX_DXF_LINE];
    char cur[40]; cur[0] = 0;
    double cx = 0, cy = 0, rad = 0, pendX = 0;
    int pendSet = 0;
    #define UPD(x, y) do { if ((x) < *mnx) *mnx = (x); if ((x) > *mxx) *mxx = (x); \
                           if ((y) < *mny) *mny = (y); if ((y) > *mxy) *mxy = (y); } while (0)
    while (dxf_next(f, &code, val, sizeof(val))) {
        if (code == 0) {
            if (strcmp(val, "ENDSEC") == 0 || strcmp(val, "EOF") == 0) {
                if (strcmp(val, "EOF") == 0) break;
                in_ent = 0; cur[0] = 0; continue;
            }
            if (strcmp(val, "SECTION") == 0) continue;
            strncpy(cur, val, sizeof(cur) - 1); cur[sizeof(cur) - 1] = 0;
            in_ent = 1; cx = cy = rad = 0; continue;
        }
        if (!in_ent) continue;
        if (code == 2) {
            if (strcmp(val, "ENTITIES") == 0) in_ent = 1;
            else if (strcmp(val, "ENDSEC") == 0) in_ent = 0;
            continue;
        }
        if (code == 10 || code == 11) { pendX = atof(val); pendSet = 1; }
        else if (code == 20 || code == 21) {
            if (pendSet) { double x = pendX, y = atof(val); pendSet = 0; cx = x; cy = y; UPD(x, y); }
        }
        else if (code == 40) {
            if (strcmp(cur, "CIRCLE") == 0 || strcmp(cur, "ARC") == 0) {
                rad = atof(val); UPD(cx - rad, cy - rad); UPD(cx + rad, cy + rad);
            }
        }
    }
    #undef UPD
    fclose(f);
    if (*mxx <= *mnx || *mxy <= *mny) { *mnx = 0; *mny = 0; *mxx = 100; *mxy = 100; }
    return 0;
}

/* Pass 2: draw entities into the PDF writer. */
static int dxf_draw(const char *path, PdfWriter *w, int *drawn_out)
{
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    int in_ent = 0, code, drawn = 0;
    char val[MAX_DXF_LINE];
    char cur[40]; cur[0] = 0;

    double sx = 0, sy = 0, ex = 0, ey = 0; int haveS = 0, haveE = 0;
    double cx = 0, cy = 0, rad = 0, a1 = 0, a2 = 0;
    double px = 0, py = 0, ix = 0, iy = 0; int havePrev = 0;
    double pendX = 0; int pendSet = 0;
    double height = 0; char text[2048]; text[0] = 0;

    #define RESET_ENT() do { haveS = haveE = 0; rad = 0; a1 = 0; a2 = 0; \
        havePrev = 0; pendSet = 0; height = 0; text[0] = 0; \
        cx = cy = 0; sx = sy = ex = ey = 0; ix = iy = 0; } while (0)
    #define FLUSH_TEXT() do { \
        if ((strcmp(cur, "TEXT") == 0 || strcmp(cur, "MTEXT") == 0) \
            && height > 0 && text[0] && (ix != 0 || iy != 0)) { \
            pdf_text(w, ix, iy, height, text); drawn++; \
        } else if (strcmp(cur, "LWPOLYLINE") == 0 && havePrev) { \
            drawn++; \
        } } while (0)

    while (dxf_next(f, &code, val, sizeof(val))) {
        if (code == 0) {
            if (strcmp(val, "ENDSEC") == 0 || strcmp(val, "EOF") == 0) {
                FLUSH_TEXT();
                if (strcmp(val, "EOF") == 0) break;
                in_ent = 0; cur[0] = 0; continue;
            }
            if (strcmp(val, "SECTION") == 0) continue;
            FLUSH_TEXT();          /* flush previous entity */
            RESET_ENT();
            strncpy(cur, val, sizeof(cur) - 1); cur[sizeof(cur) - 1] = 0;
            in_ent = 1; continue;
        }
        if (!in_ent) continue;
        if (code == 2) {
            if (strcmp(val, "ENTITIES") == 0) in_ent = 1;
            else if (strcmp(val, "ENDSEC") == 0) in_ent = 0;
            continue;
        }
        if (code == 10 || code == 11) { pendX = atof(val); pendSet = 1; }
        else if (code == 20 || code == 21) {
            if (pendSet) {
                double x = pendX, y = atof(val); pendSet = 0;
                if (strcmp(cur, "LINE") == 0) {
                    if (!haveS) { sx = x; sy = y; haveS = 1; }
                    else if (!haveE) { ex = x; ey = y; haveE = 1;
                        pdf_line(w, sx, sy, ex, ey); drawn++; }
                } else if (strcmp(cur, "CIRCLE") == 0) {
                    cx = x; cy = y;
                } else if (strcmp(cur, "ARC") == 0) {
                    cx = x; cy = y;
                } else if (strcmp(cur, "LWPOLYLINE") == 0) {
                    if (havePrev) pdf_line(w, px, py, x, y);
                    px = x; py = y; havePrev = 1;
                } else if (strcmp(cur, "TEXT") == 0 || strcmp(cur, "MTEXT") == 0) {
                    ix = x; iy = y;
                }
            }
        }
        else if (code == 40) {
            if (strcmp(cur, "CIRCLE") == 0) { rad = atof(val); pdf_circle(w, cx, cy, rad); drawn++; }
            else if (strcmp(cur, "ARC") == 0) { rad = atof(val); pdf_arc(w, cx, cy, rad, a1, a2); drawn++; }
            else if (strcmp(cur, "TEXT") == 0 || strcmp(cur, "MTEXT") == 0) { height = atof(val); }
        }
        else if (code == 50) { a1 = atof(val); }
        else if (code == 51) { a2 = atof(val); }
        else if (code == 1 || code == 3) {
            if (text[0]) strncat(text, " ", sizeof(text) - strlen(text) - 1);
            strncat(text, val, sizeof(text) - 1);
        }
    }
    #undef RESET_ENT
    #undef FLUSH_TEXT
    fclose(f);
    *drawn_out = drawn;
    return 0;
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

    /* Write a temporary DXF (LibreDWG handles entity extraction internally). */
    char dxfpath[1024];
    snprintf(dxfpath, sizeof(dxfpath), "%s.dxftmp", output);
    Bit_Chain dat = { 0 };
    dat.version = dwg.header.version;
    dat.from_version = dwg.header.from_version;
    dat.fh = fopen(dxfpath, "wb");
    if (!dat.fh) {
        char buf[256];
        snprintf(buf, sizeof(buf), "DXF_FOPEN_FAIL: %s", dxfpath);
        write_diag(output, buf);
        dwg_free(&dwg);
        (*env)->ReleaseStringUTFChars(env, jinput, input);
        (*env)->ReleaseStringUTFChars(env, joutput, output);
        return -2;
    }
    error = dwg_write_dxf(&dat, &dwg);
    fclose(dat.fh);
    if (error >= DWG_ERR_CRITICAL) {
        char buf[256];
        snprintf(buf, sizeof(buf), "DXF_WRITE_FAIL err=%d", error);
        write_diag(output, buf);
        remove(dxfpath);
        dwg_free(&dwg);
        (*env)->ReleaseStringUTFChars(env, jinput, input);
        (*env)->ReleaseStringUTFChars(env, joutput, output);
        return -20 + error;
    }

    /* Compute extents from the DXF. */
    double mnx, mny, mxx, mxy;
    dxf_bounds(dxfpath, &mnx, &mny, &mxx, &mxy);
    g_min_x = mnx; g_min_y = mny; g_max_x = mxx; g_max_y = mxy;

    double draw_w = g_max_x - g_min_x;
    double draw_h = g_max_y - g_min_y;
    if (draw_w <= 0) draw_w = 1;
    if (draw_h <= 0) draw_h = 1;
    double margin = 20.0;
    double page_w, page_h;
    if (draw_w >= draw_h) { page_w = 842.0; page_h = 595.0; }   /* landscape A4 */
    else                  { page_w = 595.0; page_h = 842.0; }   /* portrait A4  */
    double scale_w = (page_w - 2 * margin) / draw_w;
    double scale_h = (page_h - 2 * margin) / draw_h;
    double scale = scale_w < scale_h ? scale_w : scale_h;

    PdfWriter w;
    memset(&w, 0, sizeof(w));
    w.scale = scale;
    w.off_x = margin;
    w.off_y = margin;

    pdf_begin(&w, output, page_w, page_h);

    int drawn = 0;
    dxf_draw(dxfpath, &w, &drawn);

    pdf_end(&w);
    remove(dxfpath);

    {
        char buf[160];
        snprintf(buf, sizeof(buf), "SUCCESS drew=%d extents=%.0f,%.0f,%.0f,%.0f",
                 drawn, g_min_x, g_min_y, g_max_x, g_max_y);
        write_diag(output, buf);
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "dwg2pdf SUCCESS: drew %d entities", drawn);

    dwg_free(&dwg);
    (*env)->ReleaseStringUTFChars(env, jinput, input);
    (*env)->ReleaseStringUTFChars(env, joutput, output);
    return 0;
}
