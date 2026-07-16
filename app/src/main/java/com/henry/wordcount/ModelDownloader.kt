package com.henry.wordcount

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 折中方案：OCR 模型（约几十 MB）在首次用到图片/扫描件时，
 * 从 GitHub Release 下载一次并解压到 App 私有目录，之后永久离线。
 *
 * 修改下面的 MODEL_URL 为你的仓库 Release 附件地址（见 README）。
 */
object ModelDownloader {

    // TODO: 把 Henry/WordCountAndroid 改成你自己的仓库名；ocr_models.zip 由 CI 自动发布
    const val MODEL_URL =
        "https://github.com/Henry/WordCountAndroid/releases/download/models/ocr_models.zip"

    private fun modelReady(dir: File): Boolean {
        val det = File(dir, "det")
        return det.exists() && det.listFiles().orEmpty().any { it.name.endsWith(".onnx") }
    }

    /**
     * 确保 OCR 模型已就位。已下载则直接返回目录；否则下载并解压。
     * onProgress: 0..100（仅下载阶段）。
     */
    suspend fun ensureModel(
        context: Context,
        onProgress: (Int) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "ocr_models")
        if (modelReady(dir)) return@withContext dir.absolutePath

        dir.mkdirs()
        val zip = File(context.cacheDir, "ocr_models.zip")
        download(MODEL_URL, zip, onProgress)
        unzip(zip, dir)
        zip.delete()
        dir.absolutePath
    }

    private fun download(url: String, out: File, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 300000
        conn.instanceFollowRedirects = true
        val total = conn.contentLength.takeIf { it > 0 } ?: -1
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(8192)
                var read: Int
                var done = 0L
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    done += read
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        conn.disconnect()
    }

    private fun unzip(zip: File, dest: File) {
        ZipInputStream(zip.inputStream()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val file = File(dest, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { os ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (zin.read(buf).also { len = it } != -1) os.write(buf, 0, len)
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }
}
