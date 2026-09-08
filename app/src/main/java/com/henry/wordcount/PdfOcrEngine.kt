    }

    // v1.9.152: 对内嵌图片进行 OCR（拼接后一次识别）。
    //   针对 P403051 类把整张扫描图切成多张 JPEG 内嵌在页里的 PDF：整页渲染会引入大量白边且分辨率不足，
    //   直接提取内嵌图并按 EMBEDDED_IMAGE_MAX_DIM 降采样后纵向拼接，像素数只有整页渲染的 ~40%，
    //   块数少、无白边，能在 ~60s 内出 ~1000 词（匹配桌面 WordCount 1119 词）。
    private fun ocrEmbeddedImages(file: File): String {
        try {
            val bmps = extractEmbeddedImages(file, EMBEDDED_IMAGE_MAX_DIM)
            if (bmps.isEmpty()) return ""
            Diag.d("PdfOcr 内嵌图片: 提取 ${bmps.size} 张, 开始拼接 OCR")

            // 所有图片按长边压到 EMBEDDED_IMAGE_MAX_DIM；拼接成一张纵向大图。
            val maxW = bmps.maxOfOrNull { it.width } ?: return ""
            val totalH = bmps.sumOf { it.height }
            if (maxW <= 0 || totalH <= 0) {
                for (b in bmps) b.recycle()
                return ""
            }
            val stitched = android.graphics.Bitmap.createBitmap(maxW, totalH, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(stitched)
            var y = 0
            for (bmp in bmps) {
                // 宽度对齐：若某张图宽度不足 maxW（例如 last 193 高的窄条），居中贴入。
                val x = (maxW - bmp.width) / 2
                canvas.drawBitmap(bmp, x.toFloat(), y.toFloat(), null)
                y += bmp.height
                bmp.recycle()
            }

            val (_, text) = recognizePageStrong(stitched)
            val chars = text.length
            Diag.d("PdfOcr 内嵌图片OCR: 拼接 ${maxW}x${totalH}, +$chars 字")
            stitched.recycle()
            return text
        } catch (e: Throwable) {
            Log.w("WordCount", "PdfOcr 内嵌图片OCR 异常: ${e.javaClass.simpleName}: ${e.message}")
            return ""
        }
    }

    /** 策略A: 标准 /Type/XObject /Subtype/Image ... /Length N 字典 */
