package com.henry.wordcount

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    /** 外部可通过此引用向已有列表追加新文件（onNewIntent 时使用） */
    companion object {
        @Volatile var pendingUris: List<Uri>? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUrisFromIntent(intent)
        setContent { WordCountApp(initialUris = uris) }
    }

    /** v1.0.16: 处理从微信/千牛等应用后续传入的文件，追加到已有列表而非替换 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // 必须调用，否则 getIntent() 返回旧 intent
        val newUris = extractUrisFromIntent(intent)
        if (newUris.isNotEmpty()) {
            pendingUris = newUris
        }
    }

    private fun extractUrisFromIntent(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return mutableListOf<Uri>().apply {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { add(it) }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { add(it) }
                }
                Intent.ACTION_VIEW -> { intent.data?.let { add(it) } }
            }
        }
    }
}

data class InnerResult(
    val name: String,
    val words: Int, val fe: Int, val nc: Int, val chars: Int,
    val pages: Int?
)

data class FileResult(
    val name: String,
    val ext: String,
    val isArchive: Boolean,
    val words: Int, val fe: Int, val nc: Int, val chars: Int,
    val pages: Int?,
    val pagesReason: String?,
    val sheets: List<String>,
    val inner: List<InnerResult>,
    val hasUnreliable: Boolean,
)

data class FileEntry(
    val id: String,
    val displayName: String,
    val cachePath: String,
    var selected: Boolean = true,
    val result: FileResult? = null,
    val error: String? = null,
    val rawResult: Map<*, *>? = null,
)

/** 从 URI 推断文件扩展名（优先 filename，其次 MIME type） */
private fun guessExt(context: android.content.Context, uri: Uri): String {
    // 1) 从 URI 的 display name 取扩展名
    val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
    if (!name.isNullOrBlank()) {
        val dotIdx = name.lastIndexOf('.')
        if (dotIdx > 0) return name.substring(dotIdx).lowercase()
    }
    // 2) 从 ContentResolver 的 MIME type 反推
    try {
        val mime = context.contentResolver.getType(uri)
        if (!mime.isNullOrBlank()) {
            if (mime.startsWith("image/")) {
                // 图片 MIME → 扩展名映射
                return when (mime) {
                    "image/png" -> ".png"
                    "image/jpeg" -> ".jpg"
                    "image/gif" -> ".gif"
                    "image/webp" -> ".webp"
                    "image/bmp" -> ".bmp"
                    else -> ".png"
                }
            }
            return when (mime) {
                "application/pdf" -> ".pdf"
                "text/plain" -> ".txt"
                "application/msword", "application/vnd.ms-word" -> ".doc"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
                "application/vnd.ms-excel" -> ".xls"
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
                "application/vnd.ms-powerpoint" -> ".ppt"
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx"
                "application/dxf", "application/x-dxf" -> ".dxf"
                "application/dwg", "image/vnd.dwg" -> ".dwg"
                // 压缩包
                "application/zip" -> ".zip"
                "application/x-zip-compressed" -> ".zip"
                "application/x-rar-compressed", "application/rar" -> ".rar"
                "application/gzip", "application/x-gzip" -> ".gz"
                "application/x-tar", "application/tar" -> ".tar"
                "application/x-7z-compressed" -> ".7z"
                else -> ""
            }
        }
    } catch (_: Exception) {}
    // 3) 从 URI path 取（content URI 通常无效，但 file:// 可以）
    try {
        val path = uri.path
        if (!path.isNullOrBlank()) {
            val dotIdx = path.lastIndexOf('.')
            if (dotIdx > 0) return path.substring(dotIdx).lowercase()
        }
    } catch (_: Exception) {}
    return ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordCountApp(initialUris: List<Uri>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val entries = remember { mutableStateListOf<FileEntry>() }
    var busy by remember { mutableStateOf(false) }

    // SAF 文件选择器（不需要任何存储权限——OpenMultipleDocuments 在所有 Android 版本上均无需授权即可使用）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addFiles(context, scope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, uris)
    }

    /** 选文件入口：直接启动 SAF 选择器（无需任何运行时权限申请） */
    fun pickWithPermission() {
        picker.launch(arrayOf("*/*"))
    }

    // 处理启动时从千牛/微信分享进来的文件
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (initialUris.isNotEmpty()) {
            addFiles(context, scope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, initialUris)
        }
    }

    // v1.0.16: 监听从微信/千牛后续传入的文件（onNewIntent → pendingUris），追加到已有列表
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // 每隔 2 秒检查一次是否有新文件需要追加（轻量轮询，避免复杂状态管理）
        while (true) {
            kotlinx.coroutines.delay(2000)
            val uris = MainActivity.pendingUris
            if (uris != null && uris.isNotEmpty() && !busy) {
                MainActivity.pendingUris = null // 消费掉
                addFiles(context, scope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, uris)
            }
        }
    }

    val totals = run {
        val sel = entries.filter { it.selected && it.result != null }
        var w = 0; var fe = 0; var nc = 0; var ch = 0; var pg = 0
        sel.forEach { r ->
            w += r.result!!.words; fe += r.result!!.fe; nc += r.result!!.nc; ch += r.result!!.chars
            pg += r.result!!.pages ?: estimatePages(r.result!!.chars)
        }
        mapOf("words" to w, "fe" to fe, "nc" to nc, "chars" to ch, "pages" to pg)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("字数统计  v1.0.17") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Column(Modifier.padding(12.dp)) {
                    // 全选/取消全选 + 合计行
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entries.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                val idx = entries.indices
                                for (i in idx) entries[i] = entries[i].copy(selected = true)
                            }, modifier = Modifier.padding(end = 4.dp)) { Text("全选", style = MaterialTheme.typography.labelLarge) }
                            OutlinedButton(onClick = {
                                val idx = entries.indices
                                for (i in idx) entries[i] = entries[i].copy(selected = false)
                            }) { Text("取消全选", style = MaterialTheme.typography.labelLarge) }
                            OutlinedButton(onClick = {
                                entries.clear()
                            }) { Text("清空", style = MaterialTheme.typography.labelLarge) }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("合计（已选 ${entries.count { it.selected }} 项）", fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("字数 ${totals["words"]} ｜ 中文 ${totals["fe"]} ｜ 非中文 ${totals["nc"]} ｜ 页数 ${totals["pages"]}")
                    }
                    Spacer(Modifier.padding(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickWithPermission() }, modifier = Modifier.weight(1f)) { Text("选择文件") }
                        OutlinedButton(
                            onClick = { exportUnreliable(context, scope, snackbar, entries) },
                            modifier = Modifier.weight(1f),
                            enabled = entries.any { it.selected && it.result?.hasUnreliable == true }
                        ) { Text("导出不可识别内容") }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (entries.isEmpty() && !busy) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFFBDBDBD)
                    )
                    Spacer(Modifier.padding(12.dp))
                    Text("从千牛/微信 → 长按文件 → 用其他应用打开 → 选「字数统计」",
                        color = Color.Gray, modifier = Modifier.padding(horizontal = 8.dp))
                    Text("或点下方「选择文件」从本机选取",
                        color = Color.Gray, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    FileCard(entry,
                        onToggle = { e ->
                            val i = entries.indexOf(e)
                            if (i >= 0) entries[i] = e.copy(selected = !e.selected)
                        },
                        onDelete = { e ->
                            val i = entries.indexOf(e)
                            if (i >= 0) entries.removeAt(i)
                        },
                        onOpen = { e -> openWithOtherApp(context, e) }
                    )
                }
                if (busy) item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun FileCard(entry: FileEntry, onToggle: (FileEntry) -> Unit, onDelete: (FileEntry) -> Unit, onOpen: (FileEntry) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = entry.selected, onCheckedChange = { onToggle(entry) })
                Column(Modifier.weight(1f)) {
                    // 文件名可点击：用其它应用打开
                    Text(
                        entry.displayName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpen(entry) }
                    )
                    val r = entry.result
                    if (r != null) {
                    Text(
                        "字数 ${r.words} ｜ 中文 ${r.fe} ｜ 非中文 ${r.nc} ｜ 页 ${r.pages ?: estimatePages(r.chars)}" +
                                (if (r.pagesReason != null) " ｜ ${r.pagesReason}" else ""),
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray
                    )
                        if (r.hasUnreliable) Text("含无法准确统计的内容（可导出）", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00))
                    } else if (entry.error != null) {
                        // 截取首行/前200字，避免满屏 traceback；改前缀为"处理出错"
                        val shortErr = entry.error!!.substringBefore('\n').take(200)
                        Text("处理出错：$shortErr", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB00020))
                    } else {
                        Text("统计中…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(" ${entry.result?.ext?.uppercase() ?: "?"} ", Modifier.padding(6.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
                }
                // 用其它应用打开
                IconButton(onClick = { onOpen(entry) }) {
                    Text("打开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                // 删除按钮
                IconButton(onClick = { onDelete(entry) }) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "删除", tint = Color.Gray)
                }
            }
            entry.result?.inner?.forEach { inner ->
                Row(Modifier.padding(start = 40.dp, top = 2.dp)) {
                    Text("└ ${inner.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("字 ${inner.words} 中 ${inner.fe} 非 ${inner.nc}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            entry.result?.sheets?.forEach { s ->
                Text("▪ 工作表：$s", Modifier.padding(start = 40.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 业务逻辑
// ---------------------------------------------------------------------------

/**
 * 纯 Kotlin 字数统计（与 Python 端 wordcount.py 算法完全一致）。
 * Word 口径：字数 = 中文字符和朝鲜语单词 + 非中文单词
 * 返回 (words, fe, nc, chars)
 */
fun countTextKotlin(text: String): Quadruple<Int, Int, Int, Int> {
    // FarEast 正则：CJK + 朝鲜语 + 全角（与 Python 端 _FAR 完全一致）
    val farEastRegex = Regex("[\\u1100-\\u11FF\\u3000-\\u303F\\u3130-\\u318F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uA960-\\uA97C\\uAC00-\\uD7A3\\uD7B0-\\uD7FF\\uF900-\\uFAFF\\uFF00-\\uFFEF]")
    val nonCjkRegex = Regex("[^\\s\\u1100-\\u11FF\\u3000-\\u303F\\u3130-\\u318F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uA960-\\uA97C\\uAC00-\\uD7A3\\uD7B0-\\uD7FF\\uF900-\\uFAFF\\uFF00-\\uFFEF]+")

    // 按段落分割（与 Python 端 re.split(r"\n\s*\n") 一致）
    val paragraphs = text.split(Regex("\\n\\s*\\n"))
    var totalFe = 0
    var totalNc = 0
    var totalChars = 0

    for (para in paragraphs) {
        val trimmed = para.trim()
        if (trimmed.isNotEmpty()) {
            totalFe += farEastRegex.findAll(trimmed).count()
            totalNc += nonCjkRegex.findAll(trimmed).count()
            totalChars += trimmed.replace(Regex("\\s"), "").length
        }
    }

    val words = totalFe + totalNc
    return Quadruple(words, totalFe, totalNc, totalChars)
}

/** 简单四元组（避免引入额外依赖）*/
data class Quadruple<out A, out B, out C, out D>(val first: A, val second: B, val third: C, val fourth: D)

/** 用系统/其它应用打开缓存文件（点击文件名或「打开」触发）。 */
private fun openWithOtherApp(context: android.content.Context, entry: FileEntry) {
    try {
        val file = File(entry.cachePath)
        if (!file.exists()) {
            Log.w("WordCount", "打开失败：缓存文件不存在 ${entry.displayName}")
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val mime = mimeForExt(entry.result?.ext ?: "")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "用其他应用打开"))
    } catch (e: Throwable) {
        Log.w("WordCount", "打开文件失败 ${entry.displayName}: ${e.message}")
    }
}

/** 扩展名 → MIME type（用于「用其他应用打开」）。 */
private fun mimeForExt(ext: String): String {
    return when (ext.lowercase()) {
        ".doc" -> "application/msword"
        ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ".xls" -> "application/vnd.ms-excel"
        ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ".ppt" -> "application/vnd.ms-powerpoint"
        ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        ".pdf" -> "application/pdf"
        ".txt" -> "text/plain"
        ".csv" -> "text/csv"
        ".xml" -> "text/xml"
        ".json" -> "application/json"
        ".html", ".htm" -> "text/html"
        ".png" -> "image/png"
        ".jpg", ".jpeg" -> "image/jpeg"
        ".gif" -> "image/gif"
        ".bmp" -> "image/bmp"
        ".webp" -> "image/webp"
        ".dwg" -> "application/dwg"
        ".dxf" -> "application/dxf"
        else -> "*/*"
    }
}

private fun copyUriToCache(context: android.content.Context, uri: Uri): File {
    val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
        ?: "file_${System.currentTimeMillis()}"
    val safe = name.replace(Regex("[^\\w.\\-]"), "_")
    // 保留原始扩展名用于路由判断；如果 filename 没有扩展名则从 MIME type 推断后缀
    var outName = "wc_${System.currentTimeMillis()}_$safe"
    val extFromName = safe.substringAfterLast('.', "").lowercase()
    if (extFromName.isBlank() || extFromName.length > 6 || extFromName.contains(" ")) {
        val guessed = guessExt(context, uri)
        if (guessed.isNotBlank()) outName += guessed
    }
    val out = File(context.cacheDir, outName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { input.copyTo(it) }
    }
    // v1.0.16 兜底：若缓存文件仍无有效扩展名，用 magic bytes 检测并重命名
    if (out.extension.isBlank() || out.extension.length > 6) {
        val magicExt = detectExtFromMagicBytes(out)
        if (magicExt.isNotBlank()) {
            val renamed = File(out.parentFile, "${out.name}.$magicExt")
            if (out.renameTo(renamed)) return renamed
        }
    }
    return out
}

/** 用 magic bytes 检测文件真实格式（用于 content URI 无扩展名时兜底）。 */
private fun detectExtFromMagicBytes(file: File): String {
    return try {
        val header = file.inputStream().use { it.readNBytes(8) }
        when {
            header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                    && header[2] == 0x03.toByte() && header[3] == 0x04.toByte() -> "zip"
            header.size >= 6 && header[0] == 0x52.toByte() && header[1] == 0x61.toByte()
                    && header[2] == 0x72.toByte() && header[3] == 0x21.toByte() -> "rar"
            header.size >= 2 && (header[0].toInt() and 0xFF) == 0x1F && (header[1].toInt() and 0xFF) == 0x8B -> "gz"
            header.size >= 4 && header[0] == 0x25.toByte() && header[1] == 0x50.toByte()
                    && header[2] == 0x44.toByte() && header[3] == 0x46.toByte() -> "pdf"
            header.size >= 8 && header[0] == 0x37.toByte() && header[1] == 0x7A.toByte()
                    && header[2] == 0xBC.toByte() && header[3] == 0xAF.toByte() -> "7z"
            else -> ""
        }
    } catch (_: Throwable) { "" }
}

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "tif", "tiff", "gif", "webp")
private val OLD_OFFICE_EXTS = setOf("doc", "xls", "ppt")
private val OOXML_EXTS = setOf("docx", "xlsx", "pptx")
private val PDF_EXTS = setOf("pdf")
private val DWG_EXTS = setOf("dwg")
private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
private val TXT_EXTS = setOf("txt")

private fun addFiles(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    entries: androidx.compose.runtime.snapshots.SnapshotStateList<FileEntry>,
    busyRef: () -> Boolean,
    busySet: (Boolean) -> Unit,
    uris: List<Uri>
) {
    if (busyRef()) return
        scope.launch(Dispatchers.Main) {
            busySet(true)
            try {
                runCatching { PythonEngine.start(context) }
                val files = uris.map { copyUriToCache(context, it) }
            val imageFiles = mutableListOf<File>()
            val oldOfficeFiles = mutableListOf<File>()
            val ooxmlFiles = mutableListOf<File>()       // v1.0.15: OoXmlEngine
            val pdfFiles = mutableListOf<File>()          // v1.0.15: PdfExtractor
            val dwgFiles = mutableListOf<File>()
            val archiveFiles = mutableListOf<File>()     // v1.0.15: ArchiveEngine
            val txtFiles = mutableListOf<File>()
            for (f in files) {
                val ext = f.extension.lowercase().removePrefix(".")
                when {
                    ext in IMAGE_EXTS -> imageFiles.add(f)
                    ext in OLD_OFFICE_EXTS -> oldOfficeFiles.add(f)
                    ext in OOXML_EXTS -> ooxmlFiles.add(f)
                    ext in PDF_EXTS -> pdfFiles.add(f)
                    ext in DWG_EXTS -> dwgFiles.add(f)
                    ext in ARCHIVE_EXTS -> archiveFiles.add(f)
                    ext in TXT_EXTS || ext.isBlank() -> txtFiles.add(f)
                    else -> txtFiles.add(f)
                }
            }

            withContext(Dispatchers.IO) {
                // ════════════════════════════════════════
                // 全部纯 Java/Kotlin，不依赖 Chaquopy/Python
                // ════════════════════════════════════════

                // 压缩包 → 纯 Kotlin 递归统计内部文件（ZIP/GZ/TGZ/TAR）；rar/7z 提示不支持
                archiveFiles.forEachIndexed { i, f ->
                    try {
                        val res = ArchiveEngine.extract(f, context.cacheDir)
                        if (res == null) {
                            // 区分"格式不支持"和"解析失败"
                            val ext = f.extension.lowercase()
                            val isSupported = ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz")
                            val errMsg = if (isSupported)
                                "压缩包解析失败（文件可能损坏或密码保护）"
                            else
                                "暂不支持此格式（.$ext）。支持：ZIP / RAR4 / 7Z / TAR / GZ"
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_arch", displayName = f.name, cachePath = f.absolutePath,
                                error = errMsg))
                        } else {
                            val resMap = mapOf(
                                "name" to f.name, "ext" to ".${f.extension.lowercase()}",
                                "stats" to mapOf("words" to res.words, "fe" to res.fe, "nc" to res.nc, "chars" to res.chars),
                                "meta" to mapOf("inner" to res.inner.map { innerToMeta(it) }),
                                "is_archive" to true,
                                "pages" to res.inner.sumOf { it.pages ?: estimatePages(it.chars) }
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_arch", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "压缩包解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_arch", displayName = f.name, cachePath = f.absolutePath, error = "压缩包解析失败（${e.message}）"))
                    }
                }

                // OOXML (docx/xlsx/pptx) → 纯 Kotlin 解析（不再经过 Python，规避设备端 Chaquopy 失败）
                ooxmlFiles.forEachIndexed { i, f ->
                    try {
                        val res = OoXmlEngine.extract(f)
                        if (res == null) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = f.name, cachePath = f.absolutePath, error = "无法解析此 OOXML 文件（可能损坏或非标准格式）"))
                        } else {
                            val stats = countTextKotlin(res.text)
                            val resMap = mapOf(
                                "name" to f.name, "ext" to ".${f.extension.lowercase()}",
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to mapOf("sheets" to res.sheets),
                                "pages" to res.pages
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "OOXML 解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = f.name, cachePath = f.absolutePath, error = "OOXML 解析失败（${e.message}）"))
                    }
                }

                // PDF → 纯 Kotlin 解析（不再经过 Python，规避设备端 Chaquopy 失败）
                pdfFiles.forEachIndexed { i, f ->
                    try {
                        val res = PdfExtractor.extract(f)
                        if (res == null || res.text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf", displayName = f.name, cachePath = f.absolutePath, error = "无法从该 PDF 提取文字（可能为纯图片扫描件或加密文件）"))
                        } else {
                            val stats = countTextKotlin(res.text)
                            val resMap = mapOf(
                                "name" to f.name, "ext" to ".pdf",
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to emptyMap<String, Any?>(),
                                "pages" to res.pages
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "PDF 解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf", displayName = f.name, cachePath = f.absolutePath, error = "PDF 解析失败（${e.message}）"))
                    }
                }

                // 老格式(.doc/.xls/.ppt)：POI scratchpad 抽文本 -> Kotlin 统计（不再经过 Python）
                oldOfficeFiles.forEachIndexed { i, f ->
                    try {
                        val text = OldOfficeEngine.extractText(f)
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = f.name, cachePath = f.absolutePath, error = "此老格式文件内容为空或无法读取"))
                        } else {
                            val stats = countTextKotlin(text)
                            val extDot = ".${f.extension.lowercase()}"
                            val resMap = mapOf(
                                "name" to f.name, "ext" to extDot,
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to emptyMap<String, Any?>()
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "老格式解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = f.name, cachePath = f.absolutePath, error = "无法解析此老格式（${e.message}），建议另存为 .docx/.xlsx/.pptx"))
                    }
                }

                // DWG(CAD)：二进制扫描提取文字 -> Kotlin 统计（不再经过 Python）
                dwgFiles.forEachIndexed { i, f ->
                    try {
                        val text = DwgEngine.extractText(f)
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = f.name, cachePath = f.absolutePath, error = "DWG 文件未提取到文字（可能为纯图形/复杂编码），建议导出为 DXF 后统计"))
                        } else {
                            val stats = countTextKotlin(text)
                            val resMap = mapOf(
                                "name" to f.name, "ext" to ".dwg",
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to emptyMap<String, Any?>()
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "DWG 解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = f.name, cachePath = f.absolutePath, error = "DWG 解析失败（${e.message}）"))
                    }
                }

                // TXT 类：纯 Kotlin 处理
                txtFiles.forEachIndexed { i, f ->
                    try {
                        val text = f.readText(Charsets.UTF_8)
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = f.name, cachePath = f.absolutePath,
                                error = "文件内容为空"))
                        } else {
                            val stats = countTextKotlin(text)
                            val resMap = mapOf(
                                "name" to f.name,
                                "ext" to ".txt",
                                "stats" to mapOf(
                                    "words" to stats.first,
                                    "fe" to stats.second,
                                    "nc" to stats.third,
                                    "chars" to stats.fourth
                                ),
                                "meta" to emptyMap<String, Any?>()
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "TXT 读取失败 ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = f.name, cachePath = f.absolutePath,
                            error = "读取失败（${e.message}）"))
                    }
                }
                // 图片类：OCR 当前不可用（v1.0.17，Tesseract JNI 在 Android 上不稳定）
                imageFiles.forEachIndexed { i, f ->
                    // 始终显示"暂不支持"——不尝试调用 Tesseract 避免闪退
                    entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = f.name, cachePath = f.absolutePath,
                        error = "图片文字识别暂不支持（当前设备兼容性问题）"))
                }
            }
        } catch (e: Throwable) {
            Log.e("WordCount", "文件处理异常: ${e.javaClass.simpleName}: ${e.message}", e)
            scope.launch { snackbar.showSnackbar("处理出错：${e.message}") }
        } finally {
            busySet(false)
        }
    }
}

/** 文本类格式（无明确页概念）按字符量估算页数：每 ~1000 字符一页，至少 1 页。 */
fun estimatePages(chars: Int): Int = maxOf(1, (chars + 999) / 1000)

/** 压缩包内层结果 → toFileResult 可回解析的 meta 结构。 */
private fun innerToMeta(r: InnerResult): Map<String, Any?> {
    return mapOf(
        "name" to r.name,
        "stats" to mapOf("words" to r.words, "fe" to r.fe, "nc" to r.nc, "chars" to r.chars),
        "meta" to mapOf("pages" to r.pages)
    )
}

private fun toFileResult(m: Map<*, *>?, srcPath: String): FileResult {
    val stats = m?.get("stats") as? Map<*, *> ?: emptyMap<String, Any>()
    val meta = m?.get("meta") as? Map<*, *> ?: emptyMap<String, Any>()
    val inner = (meta["inner"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty().map { im ->
        val s = im["stats"] as? Map<*, *> ?: emptyMap<String, Any>()
        InnerResult(
            name = (im["name"] as? String) ?: "",
            words = (s["words"] as? Number)?.toInt() ?: 0,
            fe = (s["fe"] as? Number)?.toInt() ?: 0,
            nc = (s["nc"] as? Number)?.toInt() ?: 0,
            chars = (s["chars"] as? Number)?.toInt() ?: 0,
            pages = (im["meta"] as? Map<*, *>)?.get("pages") as? Int
        )
    }
    val imgPages = meta["img_pages"] as? List<*>
    val imageOnly = meta["image_only"] as? Boolean ?: false
    val ext = (m?.get("ext") as? String) ?: ""
    return FileResult(
        name = (m?.get("name") as? String) ?: "",
        ext = ext,
        isArchive = (m?.get("is_archive") as? Boolean) ?: false,
        words = (stats["words"] as? Number)?.toInt() ?: 0,
        fe = (stats["fe"] as? Number)?.toInt() ?: 0,
        nc = (stats["nc"] as? Number)?.toInt() ?: 0,
        chars = (stats["chars"] as? Number)?.toInt() ?: 0,
        pages = (m?.get("pages") as? Int) ?: estimatePages((stats["chars"] as? Number)?.toInt() ?: 0),
        pagesReason = m?.get("pages_reason") as? String,
        sheets = (meta["sheets"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
        inner = inner,
        hasUnreliable = ext == ".pdf" && (imageOnly || !imgPages.isNullOrEmpty())
    )
}

private fun exportUnreliable(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    entries: List<FileEntry>
) {
    scope.launch(Dispatchers.Main) {
        try {
            val sel = entries.filter { it.selected && it.result?.hasUnreliable == true && it.rawResult != null }
            if (sel.isEmpty()) { snackbar.showSnackbar("没有可导出的不可识别内容"); return@launch }
            val filesInfo = sel.map {
                listOf<Any?>(
                    it.result!!.name,
                    it.rawResult!!["stats"],
                    it.rawResult!!["meta"],
                    it.cachePath,
                    it.result!!.ext
                )
            }
            val out = File(context.getExternalFilesDir(null), "无法准确统计内容_${System.currentTimeMillis()}.pdf")
            var res: String? = null
            withContext(Dispatchers.IO) {
                res = PythonEngine.buildExportPdf(context, filesInfo, out.absolutePath)
            }
            if (res != null) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "打开导出的 PDF"))
            } else {
                snackbar.showSnackbar("无可导出内容（需 fitz；当前构建未含 pymupdf）")
            }
        } catch (e: Exception) {
            snackbar.showSnackbar("导出失败：${e.message}")
        }
    }
}
