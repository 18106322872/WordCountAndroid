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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = mutableListOf<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.forEach { uris.add(it) }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uris.add(it) }
            }
        }
        setContent { WordCountApp(initialUris = uris) }
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

    // SAF 文件选择器（先声明，因为 permLauncher 和 pickWithPermission 都要引用它）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addFiles(context, scope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, uris)
    }

    // ---- 运行时存储权限请求（Android 6+/13+ 分级）----
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val allOk = granted.values.all { it }
        if (allOk) {
            picker.launch(arrayOf("*/*"))
        } else {
            scope.launch { snackbar.showSnackbar("未授予存储权限，可能无法选取部分文件类型；仍可尝试选择") }
            picker.launch(arrayOf("*/*"))
        }
    }

    /** 带权限检查的选文件入口 */
    fun pickWithPermission() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: 分级媒体权限
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            // Android 6-12: 统一存储权限
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            picker.launch(arrayOf("*/*"))
        } else {
            permLauncher.launch(perms.toTypedArray())
        }
    }

    // 处理启动时从千牛/微信分享进来的文件
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (initialUris.isNotEmpty()) {
            addFiles(context, scope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, initialUris)
        }
    }

    val totals = run {
        val sel = entries.filter { it.selected && it.result != null }
        var w = 0; var fe = 0; var nc = 0; var ch = 0
        sel.forEach { r ->
            w += r.result!!.words; fe += r.result!!.fe; nc += r.result!!.nc; ch += r.result!!.chars
        }
        mapOf("words" to w, "fe" to fe, "nc" to nc, "chars" to ch)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("字数统计") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("合计（已选 ${entries.count { it.selected }} 项）", fontWeight = FontWeight.Bold)
                        Text("字数 ${totals["words"]} ｜ 中文 ${totals["fe"]} ｜ 非中文 ${totals["nc"]} ｜ 字符 ${totals["chars"]}")
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
fun FileCard(entry: FileEntry, onToggle: (FileEntry) -> Unit, onDelete: (FileEntry) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = entry.selected, onCheckedChange = { onToggle(entry) })
                Column(Modifier.weight(1f)) {
                    Text(entry.displayName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val r = entry.result
                    if (r != null) {
                        Text(
                            "字数 ${r.words} ｜ 中文 ${r.fe} ｜ 非中文 ${r.nc} ｜ 字符 ${r.chars}" +
                                    (if (r.pages != null) " ｜ 页 ${r.pages}" else if (r.pagesReason != null) " ｜ ${r.pagesReason}" else ""),
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                        if (r.hasUnreliable) Text("含无法准确统计的内容（可导出）", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00))
                    } else if (entry.error != null) {
                        Text("不支持：${entry.error}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB00020))
                    } else {
                        Text("统计中…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(" ${entry.result?.ext?.uppercase() ?: "?"} ", Modifier.padding(6.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
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
    return out
}

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "tif", "tiff", "gif", "webp")
private val OLD_OFFICE_EXTS = setOf("doc", "xls", "ppt")
private val DWG_EXTS = setOf("dwg")

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
            // 启动 Python 引擎
            PythonEngine.start(context)
            val files = uris.map { copyUriToCache(context, it) }
            val docPaths = mutableListOf<String>()
            val docNames = mutableListOf<String>()
            val imageFiles = mutableListOf<File>()
            val oldOfficeFiles = mutableListOf<File>()
            val dwgFiles = mutableListOf<File>()
            for (f in files) {
                val ext = f.extension.lowercase().removePrefix(".")
                when {
                    ext in IMAGE_EXTS -> imageFiles.add(f)
                    ext in OLD_OFFICE_EXTS -> oldOfficeFiles.add(f)
                    ext in DWG_EXTS -> dwgFiles.add(f)
                    else -> { docPaths.add(f.absolutePath); docNames.add(f.name) }
                }
            }

            withContext(Dispatchers.IO) {
                // 文档类：批量交给 Python 统计
                if (docPaths.isNotEmpty()) {
                    var raw: Any? = null
                    raw = PythonEngine.countFiles(docPaths)
                    (raw as? List<*>)?.forEachIndexed { i, item ->
                        val m = item as? Map<*, *>
                        val ok = m?.get("ok") as? Boolean ?: false
                        val name = m?.get("name") as? String ?: docNames.getOrNull(i) ?: "文件"
                        if (ok) {
                            val resMap = m?.get("result") as? Map<*, *>
                            val fr = toFileResult(resMap, docPaths[i])
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_d", displayName = name, cachePath = docPaths[i], result = fr, rawResult = resMap))
                        } else {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_d", displayName = name, cachePath = docPaths[i], error = m?.get("error") as? String))
                        }
                    }
                }
                // 图片类：Kotlin Tesseract OCR -> 识别文字交给 Python 计数
                imageFiles.forEachIndexed { i, f ->
                    try {
                        val text = OcrEngine.recognize(context, f)
                        var resMap: Map<*, *>? = null
                        resMap = PythonEngine.countText(text, f.name)
                        val fr = toFileResult(resMap, f.absolutePath)
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                    } catch (e: Throwable) {
                        Log.w("WordCount", "OCR 失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = f.name, cachePath = f.absolutePath, error = "OCR 识别失败（${e.message}）"))
                    }
                }
                // 老格式(.doc/.xls/.ppt)：Kotlin POI 抽文本 -> 复用 Python 计数
                oldOfficeFiles.forEachIndexed { i, f ->
                    try {
                        val text = OldOfficeEngine.extractText(f)
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = f.name, cachePath = f.absolutePath, error = "此老格式文件内容为空或无法读取"))
                        } else {
                            val resMap = PythonEngine.countText(text, f.name)
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "老格式解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = f.name, cachePath = f.absolutePath, error = "无法解析此老格式（${e.message}），建议另存为 .docx/.xlsx/.pptx"))
                    }
                }
                // DWG(CAD)：二进制扫描提取文字 -> 复用 Python 计算
                dwgFiles.forEachIndexed { i, f ->
                    try {
                        val text = DwgEngine.extractText(f)
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = f.name, cachePath = f.absolutePath, error = "DWG 文件未提取到文字（可能为纯图形/复杂编码），建议导出为 DXF 后统计"))
                        } else {
                            val resMap = PythonEngine.countText(text, f.name)
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = f.name, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "DWG 解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = f.name, cachePath = f.absolutePath, error = "DWG 解析失败（${e.message}）"))
                    }
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
        pages = m?.get("pages") as? Int,
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
                res = PythonEngine.buildExportPdf(filesInfo, out.absolutePath)
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
