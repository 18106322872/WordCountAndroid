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
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

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
    // 运行时从 PackageManager 读取真实版本号，始终与 build.gradle 的 versionName 同步（不依赖 BuildConfig）
    val appVersionName = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.26"
    } catch (e: Exception) {
        "1.0.26"
    }
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
        topBar = { TopAppBar(title = { Text("字数统计  v$appVersionName") }) },
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

/** 缓存文件 + 原始显示名称（用于修复 wc_XXXX 临时名问题） */
data class CachedFile(val file: File, val displayName: String)

/** 判断字符串是否像真实文件名（含非十六进制字符、有扩展名、长度合理） */
private fun looksLikeRealFilename(s: String): Boolean {
    val trimmed = s.trim()
    if (trimmed.length < 3 || trimmed.length > 200) return false
    // 纯十六进制/数字字符串（如 UUID 片段）不算真文件名
    val alphaCount = trimmed.count { it.isLetter() }
    if (alphaCount == 0) return false  // 纯数字/符号
    // 必须包含至少一个非十六进制字母（排除纯 hash）
    val nonHexAlpha = trimmed.count { it.isLetter() && it !in 'a'..'f' && it !in 'A'..'F' }
    if (alphaCount > 0 && nonHexAlpha == 0 && trimmed.length > 10) {
        // 全是 hex 字母+数字且较长 → 可能是 hash/UUID
        return false
    }
    return true
}

/**
 * 获取 URI 对应的显示文件名。
 * v1.0.35 重写：所有策略结果必须通过 looksLikeHash() 检测，
 * 某些 ROM 的 ContentResolver / DocumentFile 返回内部 ID（如 9e20f478899dc29...），
 * 必须拦截并降级为友好名称。
 * v1.0.38 增强：新增策略4(PDF元数据/Title)、策略5(URI path更宽松匹配)；
 * 兜底名从时间戳改为基于文件类型的友好名称（不再显示数字ID）。
 */
private fun resolveDisplayName(context: android.content.Context, uri: Uri): String {
    // 辅助函数：检测字符串是否像 hash/UUID/内部 ID
    fun looksLikeHash(s: String): Boolean {
        val t = s.trim()
        if (t.length < 8) return false
        // 纯 hex 字符串（长度>=16，典型 UUID/hash）
        if (t.matches(Regex("^[a-fA-F0-9]{16,}$"))) return true
        if (t.matches(Regex("^[a-fA-F0-9]{8}-([a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}$"))) return true
        // wc_ 或 file_ 前缀 + 长数字（临时文件名模式）
        if (t.matches(Regex("^(wc_|file_)[0-9a-f]{10,}"))) return true
        // v1.0.36 新增：带扩展名的 hash（如 ContentResolver 返回的 "9e20f478899dc29eb1xxx.pdf"）
        // 某些 ROM 的 SAF 用内部 ID+原始扩展名作为 DISPLAY_NAME
        val dotIdx = t.lastIndexOf('.')
        if (dotIdx > 0 && dotIdx < t.length - 1 && dotIdx <= 64) {
            val base = t.substring(0, dotIdx)
            val ext = t.substring(dotIdx + 1)
            // 扩展名是已知文件类型 且 basename 像 hash/长ID
            if (ext.lowercase() in setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","png","jpg","jpeg","bmp","gif","webp","tif","tiff","zip","rar","7z")
                && base.length >= 10
                && !base.any { it.code in 0x4E00..0x9FFF }
                && base.count { it.isLetterOrDigit() } > base.length * 0.85) {
                // 进一步确认 basename 不像有意义的文件名：
                // 要么纯 hex，要么字母全是小写且无元音/语义，要么纯数字
                val hasVowel = base.any { it.lowercaseChar() in 'a'..'z' && it.lowercaseChar() in setOf('a','e','i','o','u') }
                val isPureHex = base.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                val isMostlyDigits = base.count { it.isDigit() } > base.length * 0.6
                if (isPureHex || (!hasVowel && isMostlyDigits) || base.length > 24) return true
            }
        }
        // 以数字和少量字母为主的长字符串（内部 ID 特征）：字母+数字混合，长度>20，无中文/无常见扩展名
        if (t.length > 20 && !t.contains(".") && !t.any { it.code in 0x4E00..0x9FFF } &&
            t.count { it.isLetterOrDigit() } > t.length * 0.9) return true
        return false
    }

    // 策略1（最可靠）: ContentResolver 查询 OpenableColumns.DISPLAY_NAME
    try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && cursor.columnCount > 0) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank() && !looksLikeHash(name)) {
                    Log.d("WordCount", "resolveDisplayName s1(ContentResolver OK): '$name'")
                    return name.trim()
                }
                Log.d("WordCount", "resolveDisplayName s1 被hash拦截: '$name'")
            }
        }
    } catch (_: Throwable) {}

    // 策略2: DocumentFile.fromSingleUri
    androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name?.let { name ->
        if (name.isNotBlank() && !looksLikeHash(name)) {
            Log.d("WordCount", "resolveDisplayName s2(DocumentFile OK): '$name'")
            return name.trim()
        }
        Log.d("WordCount", "resolveDisplayName s2 被hash拦截/空: '$name'")
    }

    // 策略3: 从 URI path 提取文件名（SAF 编码路径解码）
    uri.lastPathSegment?.let { seg ->
        try {
            val decoded = java.net.URLDecoder.decode(seg, "UTF-8")
            var extracted = decoded.substringAfterLast('/')
                .substringAfterLast('\\')
                .substringAfterLast(':')
            // 去掉 SAF document ID 前缀（如 "primary:"）
            extracted = extracted.removePrefix("primary:")
                .removePrefix("home:")
                .removePrefix("document:")
            if (extracted.isNotBlank() && extracted.length < 250 && !looksLikeHash(extracted)) {
                Log.d("WordCount", "resolveDisplayName s3(URI path OK): '$extracted'")
                return extracted.trim()
            }
        } catch (_: Throwable) {}
    }

    // 策略4（v1.0.38 新增）: 对 PDF 文件，尝试从 PDF 元数据提取 /Title
    val ext = guessExt(context, uri)
    if (ext.lowercase() == "pdf") {
        try {
            // 先用 ContentResolver 拿到 InputStream 读取前 64KB（够覆盖大部分 PDF 的 trailer/Info 字典）
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(minOf(65536, input.available().coerceAtLeast(8192)))
                val totalRead = input.read(header)
                if (totalRead > 0) {
                    val title = extractPdfTitleFromBytes(header, totalRead)
                    if (!title.isNullOrBlank() && !looksLikeHash(title) && title.length <= 150) {
                        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                        if (clean.isNotBlank()) {
                            Log.d("WordCount", "resolveDisplayName s4(PDF /Title): '$clean'")
                            return "$clean.pdf"
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    // 策略5（v1.0.38 新增）: URI path 更宽松匹配——有些 ROM 的 lastPathSegment
    // 包含 document ID 前缀但尾部有真实文件名（如 "primary:Documents/我的文件.pdf"）
    uri.lastPathSegment?.let { seg ->
        try {
            val decoded = java.net.URLDecoder.decode(seg, "UTF-8")
            // 从 decoded 路径中取最后一个路径段（去掉所有已知名前缀）
            var candidate = decoded.substringAfterLast('/')
                .substringAfterLast('\\')
                .substringAfterLast(':')
                .removePrefix("primary:")
                .removePrefix("home:")
                .removePrefix("document:")
                .removePrefix("msf:")
                .removePrefix("raw:")
            // 如果候选名包含 CJK 字符或明显不是纯数字ID，直接采用（跳过 looksLikeHash）
            if (candidate.isNotBlank() && candidate.length <= 200 &&
                (candidate.any { it.code in 0x4E00..0x9FFF } || looksLikeRealFilename(candidate))) {
                Log.d("WordCount", "resolveDisplayName s5(宽松路径): '$candidate'")
                return candidate.trim()
            }
        } catch (_: Throwable) {}
    }

    // 兜底：基于文件类型的友好名称（v1.0.38: 不再使用时间戳数字ID）
    val friendlyExt = if (ext.isNotBlank()) ".$ext" else ""
    val typeLabel = when (ext.lowercase()) {
        "pdf" -> "PDF文档"
        "doc", "docx" -> "Word文档"
        "xls", "xlsx" -> "Excel表格"
        "ppt", "pptx" -> "PPT演示"
        "txt" -> "文本文件"
        "png", "jpg", "jpeg", "bmp", "gif", "webp" -> "图片"
        "zip", "rar", "7z" -> "压缩包"
        "dwg", "dxf" -> "CAD图纸"
        else -> "文件"
    }
    val friendly = "$typeLabel${if (cachedFileCounter > 0) "_$cachedFileCounter" else ""}$friendlyExt"
    cachedFileCounter++
    Log.w("WordCount", "resolveDisplayName 全部策略失败 → 兜底: '$friendly' (uri=$uri)")
    return friendly
}

/** v1.0.38: 兜底命名计数器（避免同名冲突） */
private var cachedFileCounter = 0

/**
 * v1.0.38 新增：从原始 PDF 字节中提取 /Title 元数据。
 * 用轻量正则扫描，不依赖完整 PDF 解析库。
 * 支持 PDF 字符串格式：(literal text) 和 <hex-encoded>。
 * @return 标题文本；未找到或解析失败返回 null
 */
private fun extractPdfTitleFromBytes(data: ByteArray, length: Int): String? {
    try {
        val str = String(data, 0, length, Charsets.ISO_8859_1)
        // 匹配 /Title 后跟字符串值：(text) 或 <hex>
        // PDF 规范允许 /Title 出现在 Info dict 中，可能在文件后半部分（trailer 附近）
        val patterns = listOf(
            Regex("""/Title\s*\(([^)]*)\)"""),           // (literal text)
            Regex("""/Title\s*<([0-9a-fA-F]+)>""")       // <hex encoded>
        )
        for (pattern in patterns) {
            val match = pattern.find(str) ?: continue
            val value = match.groupValues[1]
            if (value.isEmpty()) continue
            // 判断是 hex 还是 literal
            if (match.value.contains('<') && match.value.contains('>')) {
                // Hex 编码：两字节一组 → UTF-8 或 Latin-1 解码
                return try {
                    val bytes = ByteArray(value.length / 2)
                    for (i in value.indices step 2) {
                        bytes[i / 2] = value.substring(i, i + 2).toInt(16).toByte()
                    }
                    String(bytes, Charsets.UTF_8).also { decoded ->
                        // 验证解码结果不含控制字符
                        if (decoded.any { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) null
                        else decoded.trim()
                    }
                } catch (_: Throwable) { null }
            } else {
                // Literal string：可能有 PDF 转义序列 \n \t \( \) \\
                val unescaped = value
                    .replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\(", "(").replace("\\)", ")")
                    .replace("\\\\", "\\")
                return unescaped.trim().takeIf { it.isNotEmpty() }
            }
        }
    } catch (_: Throwable) {}
    return null
}

/** v1.0.39: 独立 hash 检测函数（copyUriToCache 安全网用，不依赖 resolveDisplayName 内嵌版本） */
private fun looksLikeHashString(s: String): Boolean {
    val t = s.trim()
    if (t.length < 8) return false
    // 纯 hex（>=16字符）
    if (t.matches(Regex("^[a-fA-F0-9]{16,}$"))) return true
    // UUID 格式
    if (t.matches(Regex("^[a-fA-F0-9]{8}-([a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}$"))) return true
    // wc_/file_ 前缀
    if (t.matches(Regex("^(wc_|file_)[0-9a-f]{10,}"))) return true
    // 带扩展名的 hash（如 "9e20f478899dc29eb1xxx.pdf"）
    val dotIdx = t.lastIndexOf('.')
    if (dotIdx > 0 && dotIdx < t.length - 1 && dotIdx <= 64) {
        val base = t.substring(0, dotIdx)
        val ext = t.substring(dotIdx + 1)
        if (ext.lowercase() in setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","png","jpg","jpeg","bmp","gif","webp")
            && base.length >= 10 && !base.any { it.code in 0x4E00..0x9FFF }
            && base.count { it.isLetterOrDigit() } > base.length * 0.85) {
            val hasVowel = base.any { it.lowercaseChar() in 'a'..'z' && it.lowercaseChar() in setOf('a','e','i','o','u') }
            val isPureHex = base.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            val isMostlyDigits = base.count { it.isDigit() } > base.length * 0.6
            if (isPureHex || (!hasVowel && isMostlyDigits) || base.length > 24) return true
        }
    }
    // 长数字字母混合无中文
    if (t.length > 20 && !t.contains(".") && !t.any { it.code in 0x4E00..0x9FFF }
        && t.count { it.isLetterOrDigit() } > t.length * 0.9) return true
    return false
}

private fun copyUriToCache(context: android.content.Context, uri: Uri): CachedFile {
    val originalName = resolveDisplayName(context, uri)

    // v1.0.39 最终安全网：即使 resolveDisplayName 返回了 hash 类名称，强制覆盖
    // （某些 ROM 的 ContentResolver 返回的值能绕过 looksLikeHash 所有策略）
    val displayName = if (looksLikeHashString(originalName)) {
        val ext = guessExt(context, uri)
        val typeLabel = when (ext.lowercase()) {
            "pdf" -> "PDF文档"
            "doc", "docx" -> "Word文档"
            "xls", "xlsx" -> "Excel表格"
            "ppt", "pptx" -> "PPT演示"
            "txt" -> "文本文件"
            "png", "jpg", "jpeg", "bmp", "gif", "webp" -> "图片"
            else -> "文档"
        }
        val safeExt = if (ext.isNotBlank()) ".$ext" else ""
        val result = "$typeLabel$safeExt"
        Log.w("WordCount", "copyUriToCache 安全网触发: '$originalName' → '$result'")
        result
    } else {
        originalName
    }

    val safe = displayName.replace(Regex("[^\\w.\\-]"), "_")
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
            if (out.renameTo(renamed)) return CachedFile(renamed, displayName)
        }
    }
    return CachedFile(out, displayName)
}

/**
 * v1.0.35 新增：将 PDF 每页渲染为 PNG 图片文件。
 * 用途：当 ML Kit OCR 不可用时，把 PDF 页面交给 Python RapidOCR（图片 OCR）识别。
 *
 * 使用 Android 系统 PdfRenderer（minSdk 26 已满足，无需额外依赖），
 * 与 PdfOcrEngine 共用同一渲染机制，区别在于输出到文件而非 Bitmap→MLKit。
 *
 * @return 渲染出的 PNG 文件列表（可能为空）；调用方负责事后清理临时文件
 */
private fun renderPdfPagesToPngs(pdfFile: File): List<File> {
    val pngs = mutableListOf<File>()
    val pfd = try {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (e: Throwable) {
        Log.w("WordCount", "renderPdfPagesToPngs 打开失败: ${e.message}")
        return emptyList()
    }
    val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Throwable) {
        Log.w("WordCount", "renderPdfPagesToPngs 创建 Renderer 失败: ${e.message}")
        runCatching { pfd.close() }
        return emptyList()
    }
    try {
        val pageCount = renderer.pageCount
        val limit = minOf(pageCount, 40) // 最多 40 页，避免 OOM
        val tmpDir = pdfFile.parentFile ?: File(System.getProperty("java.io.tmpdir"))
        for (i in 0 until limit) {
            val page = try { renderer.openPage(i) } catch (_: Throwable) { continue }
            try {
                val w = page.width; val h = page.height
                if (w <= 0 || h <= 0) continue
                // 2.0x 缩放（与 Python 端 fitz.Matrix(2.5,2.5) 接近，兼顾速度和识别率）
                val scale = minOf(2048f / maxOf(w, h), 2f).coerceAtLeast(1f)
                val bw = maxOf(1, (w * scale).toInt())
                val bh = maxOf(1, (h * scale).toInt())
                val bmp = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { continue }
                try {
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pngFile = File(tmpDir, "pdf_ocr_${pdfFile.nameWithoutExtension}_p${i + 1}.png")
                    FileOutputStream(pngFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    pngs.add(pngFile)
                } finally {
                    bmp.recycle()
                }
            } finally {
                page.close()
            }
        }
        Log.d("WordCount", "renderPdfPagesToPngs: ${pdfFile.name} → ${pngs.size}/$limit 页")
    } catch (e: Throwable) {
        Log.w("WordCount", "renderPdfPagesToPngs 异常: ${e.message}")
    } finally {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
    return pngs
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
            cachedFileCounter = 0  // 重置兜底命名计数器
            try {
                runCatching { PythonEngine.start(context) }
                val cachedFiles = uris.map { copyUriToCache(context, it) }
                val files = cachedFiles.map { it.file }
            val imageFiles = mutableListOf<CachedFile>()
            val oldOfficeFiles = mutableListOf<CachedFile>()
            val ooxmlFiles = mutableListOf<CachedFile>()       // v1.0.15: OoXmlEngine
            val pdfFiles = mutableListOf<CachedFile>()          // v1.0.15: PdfExtractor
            val dwgFiles = mutableListOf<CachedFile>()
            val archiveFiles = mutableListOf<CachedFile>()     // v1.0.15: ArchiveEngine
            val txtFiles = mutableListOf<CachedFile>()
            for (cf in cachedFiles) {
                val f = cf.file
                val ext = f.extension.lowercase().removePrefix(".")
                when {
                    ext in IMAGE_EXTS -> imageFiles.add(cf)
                    ext in OLD_OFFICE_EXTS -> oldOfficeFiles.add(cf)
                    ext in OOXML_EXTS -> ooxmlFiles.add(cf)
                    ext in PDF_EXTS -> pdfFiles.add(cf)
                    ext in DWG_EXTS -> dwgFiles.add(cf)
                    ext in ARCHIVE_EXTS -> archiveFiles.add(cf)
                    ext in TXT_EXTS || ext.isBlank() -> txtFiles.add(cf)
                    else -> txtFiles.add(cf)
                }
            }

            withContext(Dispatchers.IO) {
                // ════════════════════════════════════════
                // 全部纯 Java/Kotlin，不依赖 Chaquopy/Python
                // ════════════════════════════════════════

                // 压缩包 → 纯 Kotlin 递归统计内部文件（ZIP/GZ/TGZ/TAR）；rar/7z 提示不支持
                archiveFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val res = ArchiveEngine.extract(f, context.cacheDir)
                        if (res == null) {
                            val ext = f.extension.lowercase()
                            val isSupported = ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz")
                            val errMsg = if (isSupported) {
                            if (ext == "rar")
                                "RAR 格式不支持（仅支持 RAR4，RAR5 需先用电脑转为 ZIP）"
                            else
                                "压缩包解析失败（文件可能损坏或密码保护）"
                        } else
                            "暂不支持此格式（.$ext）。支持：ZIP / RAR4 / 7Z / TAR / GZ"
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_arch", displayName = dName, cachePath = f.absolutePath,
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
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_arch", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "压缩包解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_arch", displayName = dName, cachePath = f.absolutePath, error = "压缩包解析失败（${e.message}）"))
                    }
                }

                // OOXML (docx/xlsx/pptx) → 纯 Kotlin 解析（不再经过 Python，规避设备端 Chaquopy 失败）
                ooxmlFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val res = OoXmlEngine.extract(f)
                        if (res == null) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = dName, cachePath = f.absolutePath, error = "无法解析此 OOXML 文件（可能损坏或非标准格式）"))
                        } else {
                            val stats = countTextKotlin(res.text)
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".${f.extension.lowercase()}",
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to mapOf("sheets" to res.sheets),
                                "pages" to res.pages
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "OOXML 解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = dName, cachePath = f.absolutePath, error = "OOXML 解析失败（${e.message}）"))
                    }
                }

                // ═══════════════════ v1.0.41 重构：PDF 三级提取 + 智能优选 ═══════════════════
                //
                // 核心问题（v1.0.40 暴露）：
                //   • Kotlin PdfExtractor 对 ObjStm/现代 PDF 几乎完全失效（282KB的PDF只提取3字符）
                //   • PyMuPDF(fitz) 在 Chaqopy 下报 FileNotFoundError: AssetFinder/scripts，不可用
                //   · OCR 链路在文本提取失败后才触发，但 Python 后备排在 OCR 之后永远跑不到
                //
                // 新流程（三级提取 + 优选）：
                //   Level 1: PdfExtractor (Kotlin 原生，快速但弱) → 基线结果A
                //   Level 2: Python pdfminer (pdfminer.six，正确处理 ObjStm/ToUnicode/CMap) → 结果B
                //   Level 3: ML Kit OCR (PdfRenderer→Bitmap→识别 / PdfiumAndroid 后备) → 结果C
                //   最终: 选 chars 最多的有效结果；全失败则报错
                //
                pdfFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        // ── Level 1: Kotlin PdfExtractor（快速预筛）──
                        val ktRes = PdfExtractor.extract(f)
                        val ktStats = countTextKotlin(ktRes.text)

                        // ── Level 2: Python pdfminer（文字型 PDF 的主力）──
                        var pyWords = 0; var pyFe = 0; var pyNc = 0; var pyChars = 0; var pyPages = 0
                        var pyOk = false
                        try {
                            val pyResults = PythonEngine.countFiles(context, listOf(f.absolutePath))
                            @Suppress("UNCHECKED_CAST")
                            val pyList = pyResults as? List<Map<String, Any?>>
                            if (!pyList.isNullOrEmpty()) {
                                val py0 = pyList[0]
                                if (py0["ok"] == true) {
                                    val pyData = py0["result"] as? Map<String, Any?>
                                    if (pyData != null) {
                                        val pyS = pyData["stats"] as? Map<String, Any?>
                                        pyWords = (pyS?.get("words") as? Number)?.toInt() ?: 0
                                        pyFe = (pyS?.get("fe") as? Number)?.toInt() ?: 0
                                        pyNc = (pyS?.get("nc") as? Number)?.toInt() ?: 0
                                        pyChars = (pyS?.get("chars") as? Number)?.toInt() ?: 0
                                        pyPages = (pyData["pages"] as? Number)?.toInt() ?: ktRes.pages
                                        pyOk = true
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            Log.w("WordCount", "PDF Python pdfminer 异常: $dName - ${e.javaClass.simpleName}: ${e.message}")
                        }

                        Log.d("WordCount", "PDF $dName → KT:${ktStats.fourth}ch PY:${pyChars}ch KT_rel=${ktRes.reliable}")

                        // ── 决策：选 Kolt 或 Python 的较好结果 ──
                        //   pdfminer 通常更准确（处理了 ToUnicode CMap / ObjStm 等）
                        //   但如果两者都很少字符，说明可能是图片型 PDF → 需要 OCR
                        val usePython = pyOk && pyChars > ktStats.fourth

                        val bestWords = if (usePython) pyWords else ktStats.first
                        val bestFe = if (usePython) pyFe else ktStats.second
                        val bestNc = if (usePython) pyNc else ktStats.third
                        val bestChars = if (usePython) pyChars else ktStats.fourth
                        val bestPages = if (usePython && pyPages > 0) pyPages else ktRes.pages
                        val bestTextReliable = if (usePython) true else ktRes.reliable

                        // 判定是否还需要尝试 OCR
                        val bestCjkRatio = if (bestChars > 0) bestFe.toDouble() / bestChars else 0.0
                        val hasControlChars = false // 已由 Python/Kotlin 内部处理
                        val looksLikeGarbage = bestChars > 200 && bestFe < 30 && bestCjkRatio < 0.15
                        val needOcr = bestChars < 10 || (!bestTextReliable && bestChars < 50) || looksLikeGarbage

                        if (!needOcr) {
                            // ★ 文本提取足够好 → 直接使用
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".pdf",
                                "stats" to mapOf("words" to bestWords, "fe" to bestFe, "nc" to bestNc, "chars" to bestChars),
                                "meta" to emptyMap<String, Any?>(),
                                "pages" to bestPages
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_ok", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        } else {
                            // ★ 文本太少 → 尝试 OCR
                            val ocrRes = PdfOcrEngine.extractText(context, f)

                            if (ocrRes != null) {
                                // OCR 成功
                                val ocrStats = countTextKotlin(ocrRes.text)
                                val resMap = mapOf(
                                    "name" to dName, "ext" to ".pdf",
                                    "stats" to mapOf("words" to ocrStats.first, "fe" to ocrStats.second, "nc" to ocrStats.third, "chars" to ocrStats.fourth),
                                    "meta" to emptyMap<String, Any?>(),
                                    "pages" to ocrRes.pages
                                )
                                val fr = toFileResult(resMap, f.absolutePath)
                                entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_ocr", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                            } else {
                                // 全部失败 → 显示最佳可用结果或错误
                                if (bestChars > 0) {
                                    // 有一些文本（虽然少）→ 降级使用
                                    Log.w("WordCount", "PDF 降级(文本少+OCR失败): $dName best=$bestCharsch")
                                    val resMap = mapOf(
                                        "name" to dName, "ext" to ".pdf",
                                        "stats" to mapOf("words" to bestWords, "fe" to bestFe, "nc" to bestNc, "chars" to bestChars),
                                        "meta" to emptyMap<String, Any?>(),
                                        "pages" to bestPages
                                    )
                                    val fr = toFileResult(resMap, f.absolutePath)
                                    entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_fallback", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                                } else {
                                    // 完全没有文本 → 报错
                                    var pdfPageCount = max(1, bestPages)
                                    try {
                                        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                                        val renderer = PdfRenderer(pfd)
                                        pdfPageCount = renderer.pageCount
                                        renderer.close(); pfd.close()
                                    } catch (_: Throwable) {}
                                    val reason = PdfOcrEngine.lastFailReason
                                    val detail = PdfOcrEngine.lastFailDetail
                                    val errMsg = when (reason) {
                                        PdfOcrEngine.FailReason.OCR_DISABLED ->
                                            "此 PDF 为扫描件/图片型文件（$pdfPageCount 页），OCR 引擎未就绪。"
                                        PdfOcrEngine.FailReason.RENDER_FAILED,
                                        PdfOcrEngine.FailReason.PDFIUM_FAILED,
                                        PdfOcrEngine.FailReason.PDFIUM_UNAVAILABLE,
                                        PdfOcrEngine.FailReason.RENDER_BLANK,
                                        PdfOcrEngine.FailReason.PDFIUM_BLANK ->
                                            "此 PDF 为扫描件/图片型文件（$pdfPageCount 页），渲染引擎无法处理（可能为 JPEG2000/JBIG2 编码）。${if(detail.isNotBlank()) "($detail)" else ""}"
                                        PdfOcrEngine.FailReason.OCR_EMPTY,
                                        PdfOcrEngine.FailReason.NO_EMBEDDED_IMAGES ->
                                            "此 PDF 为扫描件/图片型文件（$pdfPageCount 页），OCR 未识别到有效文字。"
                                        PdfOcrEngine.FailReason.RENDER_PARTIAL ->
                                            "此 PDF 部分页面渲染异常（$pdfPageCount 页），OCR 结果不完整。"
                                        else -> "无法从该 PDF 提取文字（$pdfPageCount 页，可能为纯图片、加密或损坏文件）。${if(detail.isNotBlank()) "\n原因: $detail" else ""}"
                                    }
                                    entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_err", displayName = dName, cachePath = f.absolutePath, error = errMsg))
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "PDF 解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf", displayName = dName, cachePath = f.absolutePath, error = "PDF 解析失败（${e.message}）"))
                    }
                }

                // 老格式(.doc/.xls/.ppt)：POI scratchpad 抽文本 -> Kotlin 统计（不再经过 Python）
                oldOfficeFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val text = OldOfficeEngine.extractText(f)
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = dName, cachePath = f.absolutePath, error = "此老格式文件内容为空或无法读取"))
                        } else {
                            val stats = countTextKotlin(text)
                            val extDot = ".${f.extension.lowercase()}"
                            val resMap = mapOf(
                                "name" to dName, "ext" to extDot,
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to emptyMap<String, Any?>()
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "老格式解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = dName, cachePath = f.absolutePath, error = "无法解析此老格式（${e.message}），建议另存为 .docx/.xlsx/.pptx"))
                    }
                }

                // DWG(CAD)：二进制扫描提取文字 -> Kotlin 统计（不再经过 Python）
                dwgFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val text = DwgEngine.extractText(f)
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = dName, cachePath = f.absolutePath, error = "DWG 文件未提取到文字（可能为纯图形/复杂编码），建议导出为 DXF 后统计"))
                        } else {
                            val stats = countTextKotlin(text)
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".dwg",
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to emptyMap<String, Any?>(),
                                "pages" to 1
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "DWG 解析失败 ${f.name}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = dName, cachePath = f.absolutePath, error = "DWG 解析失败（${e.message}）"))
                    }
                }

                // TXT 类：纯 Kotlin 处理
                txtFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val text = f.readText(Charsets.UTF_8)
                        if (text.isBlank()) {
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = dName, cachePath = f.absolutePath,
                                error = "文件内容为空"))
                        } else {
                            val stats = countTextKotlin(text)
                            val resMap = mapOf(
                                "name" to dName,
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
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Log.w("WordCount", "TXT 读取失败 ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = dName, cachePath = f.absolutePath,
                            error = "读取失败（${e.message}）"))
                    }
                }
                // 图片类：OCR（v1.0.18 起使用 Google ML Kit，稳定不闪退）
                imageFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    try {
                        val text = OcrEngine.recognize(context, f)
                        if (text.isBlank()) {
                            val err = if (OcrEngine.ocrFailed)
                                "图片识别失败（模型未就绪或设备不支持）"
                            else
                                "未识别到文字（纯图/手写/模糊不清）"
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath,
                                error = err))
                        } else {
                            val stats = countTextKotlin(text)
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".img",
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to emptyMap<String, Any?>()
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: OutOfMemoryError) {
                        Runtime.getRuntime().gc()
                        Log.w("WordCount", "图片过大 OOM ${f.name}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath, error = "图片过大，内存不足"))
                    } catch (e: Throwable) {
                        Log.w("WordCount", "OCR 失败 ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
                        entries.add(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath, error = "图片识别失败（${e.message}）"))
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
