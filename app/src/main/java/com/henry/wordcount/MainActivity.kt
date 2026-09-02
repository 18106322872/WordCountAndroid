package com.henry.wordcount

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * v1.9.19: 后台保护风险前缀。真机上"切后台不统计"绝大多数是系统层拦截而非代码逻辑，
 * 这里把已知缺失项直接写进进度文案，用户一眼可见、无需连 logcat。
 */

/**
 * v1.9.36: 诊断日志已清理，仅保留空函数兼容旧调用点。
 */
private fun logStatsLine(context: android.content.Context, name: String, done: Int, total: Int) {
    // v1.9.36: 诊断日志已不再需要，函数保留以兼容现有调用点，内部空实现。
}


private fun bgWarn(): String {
    // v1.9.36: 后台统计已验证可用，移除诊断前缀。
    return ""
}


/** v1.9.60: 跟踪恢复轮询协程，便于长按暂停/停止时取消，避免旧轮询误杀新批次。 */
private var recoverPollingJob: kotlinx.coroutines.Job? = null

/**
 * v1.9.95: 判断本批统计是否已收尾（wc_results.jsonl 末尾出现 batch_end 标记）。
 * 用途：统计服务 stopSelf 后系统回收存在延迟，此时查询「服务是否运行」仍可能为真，
 * 导致用户刚测完点清理就被拦下。批次实际上已结束即可安全清理。
 * 只读末尾 8KB，避免整文件读入（结果文件可能很大）。
 */
private fun batchFinished(context: android.content.Context): Boolean {
    return try {
        val dir = context.cacheDir ?: return true
        val f = java.io.File(dir, "wc_results.jsonl")
        // 无结果文件 = 当前没有进行中的批次，允许清理
        if (!f.exists()) return true
        val size = f.length()
        val from = if (size > 8192) size - 8192 else 0L
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray((size - from).toInt())
            raf.read(buf)
            String(buf, Charsets.UTF_8).contains("batch_end")
        }
    } catch (_: Throwable) {
        false
    }
}
class MainActivity : ComponentActivity() {
    /** 外部可通过此引用向已有列表追加新文件（onNewIntent 时使用） */
    companion object {
        @Volatile var pendingUris: List<Uri>? = null
        // v1.5.55: 微信等分享传入时，Intent EXTRA_SUBJECT 常携带原文件名，
        // 但 ContentResolver.DISPLAY_NAME 只返回内部缓存 ID。这里临时保存 hint。
        @Volatile var pendingUriNames: MutableMap<Uri, String> = mutableMapOf()

        /** v1.9.19: 是否已获电池优化豁免（false 时切后台极可能被国产 ROM 冻结）。 */
        @Volatile var batteryUnrestricted: Boolean = true

        /**
         * v1.9.95: 判断后台统计服务 :countservice 是否仍在运行。
         * 用于「清理临时文件」入口的互斥守卫——统计期间 arc_ 内层临时文件与
         * dwg_imgs 下的 OCR 图片都正在被读写，此时清理会让正在统计的文件读不到内容。
         * 统计跑在独立进程，跨进程只能通过系统 ActivityManager 查询。
         */
        fun isCountingServiceRunning(ctx: Context): Boolean {
            return try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return false
                @Suppress("DEPRECATION")
                // 注意：getRunningServices(Int) 带参数，Kotlin 不会生成属性访问语法，
                // 必须显式写 getRunningServices(...)，写 runningServices(...) 会 Unresolved reference。
                val running = am.getRunningServices(Int.MAX_VALUE) ?: return false
                running.any { it.service.className == "com.henry.wordcount2.CountingService" }
            } catch (e: Throwable) {
                // 查询失败时保守判定为「正在统计」，宁可不清理也不误删正在用的临时文件
                Log.w("WordCountMain", "isCountingServiceRunning 查询失败，保守判定为统计中: ${e.message}")
                true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.9.107: 按 versionName 失效 wc_results.jsonl 缓存 + 杀本进程（带走 :countservice）。
        // 完整根因（v1.9.106 修了一半）：
        //   ① APK 升级后 cacheDir/wc_results.jsonl 旧值仍被 onStart→recoverResults 按 id 去重恢复
        //      ——v1.9.106 已修：按 versionName 比对 SharedPreferences，删 jsonl + 重置读取偏移
        //   ② Android service 在 :countservice 子进程跑，APK 升级后该子进程**不重载字节码**——
        //      v1.9.99 的 app.xml 钳制删除修复其实在 APK 里了，但 service 进程仍跑 v1.9.98 时代的
        //      字节码（已被 fork 出），所以用户测 HQ6 仍看到钳制值 3385。
        //   ③ 普通 app 没权限 KILL_BACKGROUND_PROCESSES（system-only），杀不掉子进程——
        //      唯一可行的工程做法是杀掉本主进程（service 子进程 fork 自本进程，一起被带走）。
        //      下次启动 MainActivity+service 都会加载新 APK 字节码，v1.9.99+ 修复真正生效。
        // 体感：app 首次启动 v1.9.107 会闪退一次（杀进程是异步的，先 setContent 再 OS 回收），
        //       用户从 launcher 重开即正常。同版本内 resume 不触发此路径。
        try {
            @Suppress("DEPRECATION")
            val cur = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            val sp = getSharedPreferences("wordcount_prefs", Context.MODE_PRIVATE)
            val prev = sp.getString("wc_cache_version", null)
            if (prev != cur && cur.isNotEmpty()) {
                val dir = cacheDir
                if (dir != null) {
                    java.io.File(dir, "wc_results.jsonl").delete()
                    java.io.File(dir, "wc_results.jsonl.tmp").delete()
                }
                RecoverState.lastOffset = 0L
                RecoverState.lastProgressKey = ""
                RecoverState.lastProgressDone = -1
                sp.edit().putString("wc_cache_version", cur).apply()
                // v1.9.107 杀本主进程（service fork 自本进程，一起被带走）。
                // 用户从 launcher 重开即可加载新 APK 字节码，service 也用新代码。
                Log.i("WordCountMain", "v1.9.107: 版本 $prev -> $cur, 杀本进程以让 service 重载新字节码")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        } catch (_: Throwable) { }
        ensureBackgroundCapability()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                logStatsLine(this@MainActivity, "ON_START", 0, 0)
                // v1.9.25: 切回前台时恢复可能被冻结期间产出的统计结果（按 id 去重）。
                val sink = currentEntriesSink
                if (sink != null) {
                    recoverResults(this@MainActivity, sink)
                } else {
                    val list = currentEntries
                    if (list != null) {
                        recoverResults(this@MainActivity, sink = { e -> if (list.none { x -> x.id == e.id }) list.add(e) })
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                logStatsLine(this@MainActivity, "ON_STOP", 0, 0)
            }
            override fun onDestroy(owner: LifecycleOwner) {
                logStatsLine(this@MainActivity, "ON_DESTROY", 0, 0)
            }
        })
        val uris = extractUrisFromIntent(intent)
        setContent { WordCountApp(initialUris = uris) }
    }
    // v1.9.60: 直接关闭程序（非配置变更）时取消全部统计并取消通知栏通知
    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            CountingService.stop(this)
            WordCountForegroundService.stop(this)
        }
    }

    /**
     * v1.9.19: 后台统计的两项系统前置条件，缺任一项都会导致"切后台不统计"。
     *
     * ① POST_NOTIFICATIONS(Android 13+)：未授予时前台 service 的通知不可见，
     *    国产 ROM 会把"无可见通知的前台服务"当普通后台进程直接冻结。
     * ② 电池优化豁免：小米/华为/OPPO/vivo 默认对未豁免的 app 在切后台后
     *    杀死或冻结其线程与子进程(:dwgisolated)，前台 service 也拦不住。
     *
     * 两者都只在缺失时请求一次(电池豁免用 SharedPreferences 记忆)，拒绝也不阻塞主流程。
     */
    private fun ensureBackgroundCapability() {
        // ① 通知权限
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val granted = checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 9019)
                }
            } catch (e: Throwable) {
                Log.w("MainActivity", "request notification permission failed: ${e.message}")
            }
        }
        // ② 电池优化豁免（只主动引导一次，之后可在系统设置里自行开启）
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val ignoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                pm.isIgnoringBatteryOptimizations(packageName) else true
            batteryUnrestricted = ignoring
            if (!ignoring) {
                val sp = getSharedPreferences("wordcount_prefs", Context.MODE_PRIVATE)
                if (!sp.getBoolean("asked_battery_opt", false)) {
                    sp.edit().putBoolean("asked_battery_opt", true).apply()
                    val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                }
            }
        } catch (e: Throwable) {
            Log.w("MainActivity", "battery optimization check failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // v1.9.19: 用户从系统设置返回后刷新豁免状态，供统计前提示使用
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            batteryUnrestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                pm.isIgnoringBatteryOptimizations(packageName) else true
        } catch (_: Throwable) {}
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
        val nameHints = mutableMapOf<Uri, String>()
        // 微信/QQ 等分享时，原文件名可能放在这些 extras 里
        val candidateNames = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.getStringExtra(Intent.EXTRA_TITLE),
            intent.getStringExtra("android.intent.extra.SUBJECT"),
            intent.getStringExtra("title"),
            intent.getStringExtra("_display_name"),
            intent.getStringExtra(Intent.EXTRA_TEXT)
        ).map { it.trim() }.filter { it.isNotBlank() }
        return mutableListOf<Uri>().apply {
            fun recordHint(uri: Uri) {
                if (candidateNames.isNotEmpty()) {
                    // 多个文件共享一个 SUBJECT 时，所有文件都用同一个 hint；
                    // 实际微信通常一次只发一个文件，问题不大。
                    nameHints[uri] = candidateNames.first()
                }
            }
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { recordHint(it); add(it) }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                        recordHint(uri); add(uri)
                    }
                }
                Intent.ACTION_VIEW -> { intent.data?.let { add(it) } }
            }
        }.also {
            pendingUriNames = nameHints
        }
    }
}

data class InnerResult(
    val name: String,
    val words: Int, val fe: Int, val nc: Int, val chars: Int,
    val pages: Int?,
    /** v1.5.86: 内层文件（如栅格化 DWG）无法直接提取中文时置 true，UI 提示"必须用PDF统计"且不计入合计 */
    val needsPdf: Boolean = false,
    /** v1.9.68: false 表示仅占位（未统计），true 表示已填充结果 */
    val done: Boolean = true
)

/** 单个工作表统计（含隐藏表）：名称 + 字数 */
data class SheetStat(
    val name: String,
    val words: Int, val fe: Int, val nc: Int, val chars: Int
)

/** v1.5.61: CAD 文字拆分（文字部分 / 纯编号部分），供展开后分别勾选汇总 */
data class CadPartStats(
    val textWords: Int, val textFe: Int, val textNc: Int, val textChars: Int,
    val codeWords: Int, val codeFe: Int, val codeNc: Int, val codeChars: Int,
    val textItems: Int, val codeItems: Int
)

data class FileResult(
    val name: String,
    val ext: String,
    val isArchive: Boolean,
    val words: Int, val fe: Int, val nc: Int, val chars: Int,
    val pages: Int?,
    val pagesReason: String?,
    val sheets: List<String>,
    // v1.3.3: 隐藏工作表列表（默认不计入文件字数与合计，由 UI 勾选后才并入）
    val hiddenSheets: List<SheetStat> = emptyList(),
    // v1.3.32: PPT 备注幻灯片列表（默认不计入文件字数与合计，由 UI 勾选后才并入）
    val notesSlides: List<SheetStat> = emptyList(),
    // v1.3.32: PPT 嵌入图片数量
    val imageCount: Int = 0,
    // v1.3.32: 文件内部标题（docProps/core.xml <dc:title>），用于修复 URI 无法获取真实文件名的问题
    val internalTitle: String = "",
    val inner: List<InnerResult>,
    val hasUnreliable: Boolean,
    // v1.3.64: PDF 诊断信息（Python 是否工作、错误、决策过程），显示到界面便于排查
    val diag: String? = null,
    // v1.5.66: PDF 的 OCR 状态摘要（直接显示在主界面，无需展开诊断），便于真机排查
    //   - "文本充分，未触发OCR" | "已OCR扫描X页" | "⚠️ OCR未成功，已用文本层降级"
    val ocrNote: String? = null,
    // v1.5.36: DWG 统计不准、需用户选文字型 PDF 来重新统计时置 true（驱动 UI 提示与弹窗选 PDF）
    val needsPdf: Boolean = false,
    // v1.5.61: CAD 文字/纯编号拆分（仅 DWG 文件可能非空）
    val cadParts: CadPartStats? = null,
)

data class FileEntry(
    val id: String,
    val displayName: String,
    val cachePath: String,
    var selected: Boolean = true,
    val result: FileResult? = null,
    val error: String? = null,
    val rawResult: Map<*, *>? = null,
    // v1.5.56: 用户手动重命名的文件名（优先于自动 displayName 显示）
    val userRenamedName: String? = null,
)

/** 从 URI 推断文件扩展名（优先 filename，其次 MIME type） */
private fun guessExt(context: android.content.Context, uri: Uri): String {
    // 1) 从 URI 的 display name 取扩展名
    val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
    if (!name.isNullOrBlank()) {
        val dotIdx = name.lastIndexOf('.')
        if (dotIdx > 0) return name.substring(dotIdx + 1).lowercase() // ← v1.1.31: 不含前导点
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

/** v1.1.50: 为文件名生成稳定的短hash（用于通用名后缀，区分同名文件） */
private fun absoluteHashCode(s: String): Int {
    var h = 0
    for (c in s) {
        h = 31 * h + c.code
        // 防止溢出为负数时显示问题（取绝对值但保留分布）
        if (h == Int.MIN_VALUE) h = 0 else if (h < 0) h = -h
    }
    return h
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    // v1.9.20: workScope 改用 App 级常驻协程域。此前用 ProcessLifecycleOwner.lifecycleScope，
    // 其协程在进程级 lifecycle 派发 DESTROYED 时取消——部分 ROM 在 App 切后台后会回调
    // ON_STOP/ON_DESTROY，统计协程树整体被取消，表现为"切后台不统计"。
    // WordCountApplication.appScope 由前台 service 守护，与进程同生命周期，切后台不取消。
    val workScope = WordCountApplication.appScope
    val snackbar = remember { SnackbarHostState() }

    val entries = remember { mutableStateListOf<FileEntry>() }
    currentEntries = entries
    var busy by remember { mutableStateOf(false) }
    // v1.5.36: 用户为「统计不准」的 DWG 点选文字型 PDF 时，记录当前正在选 PDF 的条目 id
    var pdfPickEntryId by remember { mutableStateOf<String?>(null) }
    // v1.5.56: 用户手动重命名文件条目
    var renameEntry by remember { mutableStateOf<FileEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    // v1.1.1: 文档比较模式开关
    var compareMode by remember { mutableStateOf(false) }
    // v1.9.0: 统计进度提示（与电脑版状态栏一致）
    var progressText by remember { mutableStateOf<String?>(null) }
    // v1.9.60: 长按统计进度弹出的暂停/停止控制对话框标记
    var showCountControl by remember { mutableStateOf(false) }
    /**
     * v1.9.62: 是否处于"已暂停"——直接由进度文案前缀派生，
     * 这样服务侧恢复统计、进度一更新，弹窗自动切回「暂停统计 / 停止统计」，无需额外同步状态。
     */
    val countPaused: Boolean = progressText?.startsWith("已暂停") == true

    /** v1.9.62: 暂停前的进度文案，继续时原样恢复。 */
    var pausedProgressText by remember { mutableStateOf<String?>(null) }

    /**
     * v1.9.62: 真正暂停——通知统计服务在"文件边界"停下来，但**不杀服务、不清条目、不置空进度**。
     * 主界面因此保持显示已统计文件的列表与合计行，长按进度区可再选"继续"或"停止"。
     */
    fun pauseCounting() {
        CountingService.pause(context)
        pausedProgressText = progressText
        progressText = "已暂停 · " + (progressText ?: "统计中")
    }

    /** v1.9.62: 继续——通知服务继续统计剩下未统计的文件。 */
    fun resumeCounting() {
        CountingService.resume(context)
        progressText = pausedProgressText
        pausedProgressText = null
    }

    /** v1.9.60/1.9.62: 停止——取消恢复轮询、停止统计服务（通知随之取消）、清进度、仅保留已统计条目。 */
    fun stopAndShowComplete() {
        recoverPollingJob?.cancel()
        recoverPollingJob = null
        CountingService.stop(context)
        WordCountForegroundService.stop(context)
        progressText = null
        busy = false
        // v1.9.62: 不再删除任何条目——已统计的文件要留在界面上显示合计；
        // 未完成的只是没有对应条目，界面自然只剩已完成部分（即"统计完成后的界面"）。
    }

    // SAF 文件选择器（v1.9.66: 改为 StartActivityForResult + 显式 ACTION_OPEN_DOCUMENT + EXTRA_ALLOW_MULTIPLE，
    // 避免某些 ROM 对 OpenMultipleDocuments 支持不一致导致只能单选）。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()
            val clip = data?.clipData
            if (clip != null && clip.itemCount > 0) {
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            } else {
                data?.data?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) {
                addFiles(context, workScope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, uris, onProgress = { name, done, total ->                 // v1.9.39: 去掉 total<=0 守卫，让程序内进度与通知栏同步（通知栏从 0/N 开始 → 程序内也从 0/N 开始）；
//          只有 finalizeBatch 传入的清空信号 (name="", done=0, total=0) 才把进度置 null。
progressText = if (name.isBlank() && done == 0 && total == 0) null else (bgWarn() + "正在统计文件$name，已统计$done/$total")
                    if (total > 0) {
                        // v1.9.20: 后台时 UI 不重组，进度改由前台通知实时展示；同时落盘文件级日志供真机排查
                        // v1.9.30: 进度通知统一由 :countservice 进程的 CountingService 负责，
                        // 主进程不再重复发通知，避免通知栏出现两个进度互相覆盖/乱跳。
                        logStatsLine(context, name, done, total)
                    } })
            }
        }
    }

    // v1.5.36: 文字型 PDF 选择器（仅 PDF）。用于 DWG 统计不准时，让用户手动选一份同图文字型 PDF 重新统计。
    //   注：Android 走 SAF 读文件，原 document URI 已丢失、缓存目录无法像桌面那样 find_sibling_pdf，
    //   故不能自动找同目录 PDF，必须由用户在手机上手动选。
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val entryId = pdfPickEntryId
        if (uri != null && entryId != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val cf = copyUriToCache(context, uri)
                    val rec = recomputeFromPdf(context, cf.file, cf.displayName)
                    withContext(Dispatchers.Main) {
                        val idx = entries.indexOfFirst { it.id == entryId }
                        if (idx < 0) return@withContext
                        val entry = entries[idx]
                        val old = entry.result
                        if (rec == null || old == null) {
                            Toast.makeText(context, "该 PDF 未提取到文字，请选「文字型」PDF（非扫描件）", Toast.LENGTH_LONG).show()
                            return@withContext
                        }
                        // 用 PDF 统计结果覆盖 DWG 的字数/页数，关闭 needsPdf 提示
                        val newResult = old.copy(
                            words = rec.words,
                            fe = rec.fe,
                            nc = rec.nc,
                            chars = rec.chars,
                            pages = rec.pages ?: old.pages,
                            pagesReason = "来自文字型PDF",
                            diag = rec.diag,
                            needsPdf = false
                        )
                        entries[idx] = entry.copy(result = newResult)
                        Toast.makeText(context, "已用文字型PDF重新统计：${rec.chars}字", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    Diag.w( "选PDF重统计异常 ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "选PDF重统计失败：${e.message?.take(120)}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        pdfPickEntryId = null
    }

    /** 选文件入口：直接启动 SAF 选择器（无需任何运行时权限申请） */
    fun pickWithPermission() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        picker.launch(intent)
    }

    // 处理启动时从千牛/微信分享进来的文件
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (initialUris.isNotEmpty()) {
            addFiles(context, workScope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, initialUris, onProgress = { name, done, total ->                 // v1.9.39: 去掉 total<=0 守卫，让程序内进度与通知栏同步（通知栏从 0/N 开始 → 程序内也从 0/N 开始）；
//          只有 finalizeBatch 传入的清空信号 (name="", done=0, total=0) 才把进度置 null。
progressText = if (name.isBlank() && done == 0 && total == 0) null else (bgWarn() + "正在统计文件$name，已统计$done/$total")
                if (total > 0) {
                    // v1.9.20: 后台时 UI 不重组，进度改由前台通知实时展示；同时落盘文件级日志供真机排查
                    // v1.9.30: 进度通知统一由 :countservice 进程的 CountingService 负责，
                    // 主进程不再重复发通知，避免通知栏出现两个进度互相覆盖/乱跳。
                    logStatsLine(context, name, done, total)
                } })
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
                    addFiles(context, workScope, snackbar, entries, busyRef = { busy }, busySet = { busy = it }, uris, onProgress = { name, done, total ->                 // v1.9.39: 去掉 total<=0 守卫，让程序内进度与通知栏同步（通知栏从 0/N 开始 → 程序内也从 0/N 开始）；
//          只有 finalizeBatch 传入的清空信号 (name="", done=0, total=0) 才把进度置 null。
progressText = if (name.isBlank() && done == 0 && total == 0) null else (bgWarn() + "正在统计文件$name，已统计$done/$total")
                if (total > 0) {
                    // v1.9.20: 后台时 UI 不重组，进度改由前台通知实时展示；同时落盘文件级日志供真机排查
                    // v1.9.30: 进度通知统一由 :countservice 进程的 CountingService 负责，
                    // 主进程不再重复发通知，避免通知栏出现两个进度互相覆盖/乱跳。
                    logStatsLine(context, name, done, total)
                } })
            }
        }
    }

    // v1.3.3: 隐藏工作表的勾选状态（key = "${entry.id}::${sheetName}"）
    val hiddenSelected = remember { mutableStateMapOf<String, Boolean>() }

    val totals = run {
        val sel = entries.filter { it.selected && it.result != null }
        var w = 0; var fe = 0; var nc = 0; var ch = 0; var pg = 0; var pendingPdf = 0
        sel.forEach { r ->
            val result = r.result!!
            if (result.isArchive) {
                // v1.5.81: 压缩包按内层文件勾选状态汇总（默认全选）
                result.inner.forEachIndexed { index, inner ->
                    // v1.5.89: 压缩包内层文件全部计入勾选合计（包括 needsPdf），与电脑版保持一致。
                    // needsPdf 仅作为明细提示，不影响汇总。
                    if (hiddenSelected["${r.id}::inner::$index"] != false) {
                        w += inner.words; fe += inner.fe; nc += inner.nc; ch += inner.chars
                        pg += inner.pages ?: estimatePages(inner.chars)
                    }
                }
            } else {
                // v1.5.37/v1.5.62: 需要 PDF 来统计的 DWG 仍把已拿到的字数计入合计（与电脑版一致）。
                // 只有完全没拿到字数时才只计页数。
                val hasStats = result.words > 0 || result.fe > 0 || result.nc > 0
                if (result.needsPdf && !hasStats) {
                    pg += result.pages ?: estimatePages(result.chars)
                    pendingPdf += 1
                } else {
                    // v1.5.61/62: DWG 有文字/纯编号拆分时，按展开勾选状态计入合计
                    val cp = result.cadParts
                    if (cp != null) {
                        val textChecked = hiddenSelected["${r.id}::cad::text"] != false
                        val codeChecked = hiddenSelected["${r.id}::cad::code"] != false
                        when {
                            textChecked && codeChecked -> { w += result.words; fe += result.fe; nc += result.nc; ch += result.chars }
                            textChecked -> { w += cp.textWords; fe += cp.textFe; nc += cp.textNc; ch += cp.textChars }
                            codeChecked -> { w += cp.codeWords; fe += cp.codeFe; nc += cp.codeNc; ch += cp.codeChars }
                        }
                    } else {
                        w += result.words; fe += result.fe; nc += result.nc; ch += result.chars
                    }
                    pg += result.pages ?: estimatePages(result.chars)
                }
            }
            // 勾选的隐藏工作表计入合计（页数不另计，沿用文件级页数）
            r.result!!.hiddenSheets.forEach { hs ->
                if (hiddenSelected["${r.id}::${hs.name}"] == true) {
                    w += hs.words; fe += hs.fe; nc += hs.nc; ch += hs.chars
                }
            }
            // v1.3.32/v1.3.34: 勾选的 PPT 备注汇总计入合计（一条汇总勾选控制全部备注）
            if (hiddenSelected["${r.id}::notes::_summary_"] == true) {
                r.result!!.notesSlides.forEach { ns ->
                    w += ns.words; fe += ns.fe; nc += ns.nc; ch += ns.chars
                }
            }
        }
        mapOf("words" to w, "fe" to fe, "nc" to nc, "chars" to ch, "pages" to pg, "pendingPdf" to pendingPdf)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TabToggle("字数统计", !compareMode) { compareMode = false }
                        Spacer(Modifier.width(6.dp))
                        TabToggle("文档比较", compareMode) { compareMode = true }
                        Spacer(Modifier.weight(1f))
                        Text("v${appVersionName}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.Gray,
                            modifier = Modifier.padding(end = 16.dp))
                    }
                }
            }, actions = {
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                val app = ctx.applicationContext as? WordCountApplication
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "选项", tint = Color.Gray)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("导出诊断日志") },
                            onClick = {
                                expanded = false
                                scope.launch(Dispatchers.IO) { Diag.exportAndShare(ctx) }
                            },
                            leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("清理临时文件") },
                            onClick = {
                                expanded = false
                                scope.launch(Dispatchers.IO) {
                                    // v1.9.95: 统计进行中禁止清理——保护正在读写的 arc_ 内层临时文件与 dwg_imgs OCR 图片。
                                    // 统计空闲时改为 protectRecentMinutes = 0（不再按 mtime 保护）：
                                    // 旧逻辑保护最近 60 分钟有修改的项，而每天多次测同一 RAR 时
                                    // cacheDir 下 dwg_imgs 等残留目录的 mtime 几乎都在 60 分钟内，
                                    // 结果全部被跳过、removed 恒为 0，永远提示「没有可清理的临时文件」。
                                    // 服务 stopSelf 后系统回收有延迟，批次已收尾即视为可清理
                                    // 调用处位于文件级 Composable（不在 MainActivity 类作用域内），
                                    // companion object 成员必须带类名限定，否则 Unresolved reference。
                                    val busy = MainActivity.isCountingServiceRunning(ctx.applicationContext)
                                            && !batchFinished(ctx.applicationContext)
                                    if (busy) {
                                        scope.launch(Dispatchers.Main) {
                                            Toast.makeText(ctx, "统计进行中，请结束后再清理", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        val (removed, freed) = app?.cleanupTempFiles(maxAgeHours = 0, protectRecentMinutes = 0) ?: (0 to 0L)
                                        scope.launch(Dispatchers.Main) {
                                            val msg = if (removed > 0) {
                                                "已清理 $removed 个临时目录，释放 ${freed / 1024 / 1024}MB"
                                            } else {
                                                "没有可清理的临时文件"
                                            }
                                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) }
                        )
                    }
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = if (!compareMode) {
            {
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
                                    CountingService.stop(context)
                                    WordCountForegroundService.stop(context)
                                    recoverPollingJob?.cancel()
                                    recoverPollingJob = null
                                    progressText = null
                                    busy = false
                                    entries.clear()
                                }) { Text("清空", style = MaterialTheme.typography.labelLarge) }
                            }
                            Spacer(Modifier.weight(1f))
                            Text("合计（已选 ${entries.count { it.selected }} 项）", fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            // v1.5.37: 有 DWG 待 PDF 统计时，合计行与电脑 APP 一致：字数/中文/非中文处显示"-"，只显示页数
                            val pending = totals["pendingPdf"] ?: 0
                            val totalText = if (pending > 0) {
                                "字数 - ｜ 中文 - ｜ 非中文 - ｜ 页数 ${totals["pages"]}"
                            } else {
                                "字数 ${totals["words"]} ｜ 中文 ${totals["fe"]} ｜ 非中文 ${totals["nc"]} ｜ 页数 ${totals["pages"]}"
                            }
                            Text(totalText)
                        }
                        Spacer(Modifier.padding(4.dp))
                        var isExporting by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pickWithPermission() }, modifier = Modifier.weight(1f)) { Text("选择文件") }
                            OutlinedButton(
                                onClick = { exportUnreliable(context, scope, snackbar, entries, onStateChange = { isExporting = it }) },
                                modifier = Modifier.weight(1f),
                                enabled = entries.any { it.selected && it.result?.hasUnreliable == true } && !isExporting
                            ) { Text(if (isExporting) "导出中…" else "导出未统计图片") }
                        }
                    }
                }
            }
        } else { {} }
    ) { padding ->
        if (compareMode) {
            CompareScreen(
                context = context,
                scope = scope,
                snackbar = snackbar,
                availableFiles = entries.toList(),
                modifier = Modifier.padding(padding)
            )
        } else {
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
                        // v1.3.4: 空状态说明改为 11.txt 内容；左对齐、黑色字体；新增说明时往 helpLines 追加（编号顺延）
                        val helpLines = listOf(
                            "1、导入文件：从千牛/微信→长按文件→用其他应用打开→选「字数统计」，或点下方「选择文件」从本机选取；",
                            "2、Word字数页数核对：手机安装Word后，doc/docx文件2页以上的可点文件名右侧「Word」直接在 Word 里打开，左下角选「页面视图」，上下滚动查看页数和总字数；",
                            "3、PPT/Excel/PDF 页数核对：手机安装Wps后，点击文件右侧「Wps」直接在Wps里打开，PPT直接下拉，Excel左下角选「逐页输出图片」，PDF下拉查看页数，右下角可看到页数。",
                            "4、\"导出未统计图片\"按钮功能：导出Word/Excel/PPT里的内嵌图片，这部分内容未统计字数。"
                        )
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            helpLines.forEach { line ->
                                Text(line, color = Color.Black, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
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
                            onOpen = { e -> openWithOtherApp(context, e) },
                            onOpenWord = { e -> openWithWord(context, e) },
                            onOpenWps = { e -> openWithWps(context, e) },
                            // v1.5.36: 点红色提示 → 记录条目 id 并启动 PDF 选择器（仅限文字型 PDF）
                            onPickPdf = { e ->
                                pdfPickEntryId = e.id
                                pdfPicker.launch(arrayOf("application/pdf"))
                            },
                            // v1.5.56: 长按文件名或点编辑图标 → 手动重命名（解决微信分享拿不到原文件名的问题）
                            onRename = { e ->
                                renameEntry = e
                                renameText = e.userRenamedName ?: e.displayName
                            },
                            hiddenSelected = hiddenSelected,
                            onToggleHidden = { id, name ->
                                val k = "$id::$name"
                                hiddenSelected[k] = !(hiddenSelected[k] ?: false)
                            },
                            // v1.5.81: 压缩包内层文件单独勾选
                            onToggleInner = { id, index ->
                                val k = "$id::inner::$index"
                                hiddenSelected[k] = !(hiddenSelected[k] ?: true)
                            }
                        )
                    }
                    // v1.9.8: 进度指示器移到列表内部、位于已完成条目之后，
                    // 避免被第一个文件结果挡住，第二个及以后文件也能看到进度。
                    if (progressText != null) {
                        item(key = "__progress__") {
                            val ptext = progressText!!
                            val (cur, total) = run {
                                val m = Regex("(\\d+)/(\\d+)").find(ptext)
                                if (m != null) (m.groupValues[1].toInt() to m.groupValues[2].toInt()) else (0 to 0)
                            }
                            val ratio = if (total > 0) cur.toFloat() / total else 0f
                            Column(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(onClick = {}, onLongClick = { showCountControl = true })
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (total > 0) {
                                    // v1.9.56: 进度始终显示 cur/total，0/N 时下方额外显示“准备中...”，与通知栏对齐。
                                    Text(
                                        text = "${cur}/${total}",
                                        color = Color(0xFF1565C0),
                                        fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp),
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                    if (cur == 0) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "准备中...",
                                            color = Color(0xFF1565C0),
                                            fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { ratio.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(8.dp),
                                        color = Color(0xFF1565C0)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    text = ptext,
                                    color = Color.Black,
                                    fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // v1.9.62: 长按统计进度弹出的控制对话框
    //   未暂停 → 「暂停统计 / 停止统计」
    //   已暂停 → 「继续统计 / 停止统计」（此时才能接着把剩下没统计的文件跑完）
    if (showCountControl) {
        AlertDialog(
            onDismissRequest = { showCountControl = false },
            title = { Text(if (countPaused) "统计已暂停" else "统计控制") },
            text = {
                Text(
                    if (countPaused)
                        "已统计的文件已列出并显示合计数。\n继续：从下一个未统计的文件接着跑；\n停止：彻底结束并取消通知栏。"
                    else
                        "暂停：在当前文件统计完后停下，界面保留已统计内容与合计；\n停止：彻底结束并取消通知栏。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCountControl = false
                    stopAndShowComplete()
                }) { Text("停止统计") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCountControl = false
                    if (countPaused) resumeCounting() else pauseCounting()
                }) { Text(if (countPaused) "继续统计" else "暂停统计") }
            }
        )
    }
    // v1.5.56: 手动重命名文件对话框（微信分享等场景系统不暴露原文件名时兜底）
    renameEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            title = { Text("重命名") },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("新文件名") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    if (newName.isNotBlank()) {
                        val i = entries.indexOfFirst { it.id == entry.id }
                        if (i >= 0) {
                            entries[i] = entry.copy(userRenamedName = newName)
                        }
                    }
                    renameEntry = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameEntry = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TabToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray
    Text(
        label,
        color = color,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.clickable { onClick() }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(
    entry: FileEntry,
    onToggle: (FileEntry) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onOpen: (FileEntry) -> Unit,
    onOpenWord: (FileEntry) -> Unit,
    onOpenWps: (FileEntry) -> Unit,
    // v1.5.36: DWG 统计不准时，点击红色提示 → 弹窗选文字型 PDF 重新统计
    onPickPdf: (FileEntry) -> Unit,
    // v1.5.56: 长按文件名或点编辑图标可手动重命名
    onRename: (FileEntry) -> Unit,
    // v1.5.61: 改为 MutableMap, CAD 文字/纯编号勾选需要直接写入状态
    hiddenSelected: MutableMap<String, Boolean>,
    onToggleHidden: (String, String) -> Unit,
    // v1.5.81: 压缩包内层文件勾选
    onToggleInner: (String, Int) -> Unit
) {
    val expanded = remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            // 第一行：文件名横跨全宽（方便显示长文件名和点击打开）
            // v1.3.34: 有备注/图片的 PPT 在文件名前加红"备"/"图"标记（和隐藏工作表的"隐"一样醒目）
            val r = entry.result
            val prefixTags = buildString {
                if (r?.notesSlides?.isNotEmpty() == true) append("备")
                if ((r?.imageCount ?: 0) > 0) append("图")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = entry.selected, onCheckedChange = { onToggle(entry) })
                // v1.3.36: 文件名行只显示纯文件名，不显示"备"/"图"/"隐"等前缀
                // v1.5.56: 优先显示用户重命名，长按或点编辑图标可改名
                Text(
                    entry.userRenamedName ?: entry.displayName,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = { onOpen(entry) },
                            onLongClick = { onRename(entry) }
                        )
                )
                // 编辑文件名按钮
                IconButton(onClick = { onRename(entry) }, modifier = Modifier.size(28.dp)) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = "重命名", tint = Color.Gray)
                }
                // 删除按钮
                IconButton(onClick = { onDelete(entry) }, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "删除", tint = Color.Gray)
                }
            }
            // 第二行：左边统计信息（占更多空间）+ 右边文件类型/Word/Excel按钮（上下排列）
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    // r 已在文件名行前定义（用于 prefixTags 和此处共用）
                    if (r != null) {
                        val isEstimated = r.pagesReason?.contains("estimate") == true
                        val pageLabel = if (isEstimated) "页 ${r.pages ?: estimatePages(r.chars)}(估)"
                            else "页 ${r.pages ?: estimatePages(r.chars)}"
                        // v1.5.37/v1.5.62: 需要 PDF 统计的 DWG 仍显示当前已拿到的字数（与电脑版
                        // 一致），方便用户知道“已有统计”是多少；只有完全没拿到字数时才显示"-"。
                        val hasStats = r.words > 0 || r.fe > 0 || r.nc > 0
                        val statsText = if (r.needsPdf && !hasStats) {
                            "字数 - ｜ 中文 - ｜ 非中文 - ｜ $pageLabel" +
                                    (if (r.pagesReason != null && !isEstimated) " ｜ ${r.pagesReason}" else "")
                        } else {
                            "字数 ${r.words} ｜ 中文 ${r.fe} ｜ 非中文 ${r.nc} ｜ $pageLabel" +
                                    (if (r.pagesReason != null && !isEstimated) " ｜ ${r.pagesReason}" else "")
                        }
                        Text(
                            statsText,
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                        // v1.9.60: PDF/DWG 诊断短摘要常显（一眼看出文字层/OCR/OLE 提取状态），
                        // 完整决策详情见展开明细里的多行诊断文本。
                        val shortNote = when {
                            !r.ocrNote.isNullOrBlank() -> r.ocrNote
                            !r.diag.isNullOrBlank() -> {
                                // DWG 诊断取关键 ole_dxf/ole_dwg/img_ocr 片段，避免整行过长
                                val d = r.diag!!.take(120)
                                if (d.contains("ole_dxf") || d.contains("ole_dwg") || d.contains("img_ocr")) "DWG诊断: ${d}" else d
                            }
                            else -> null
                        }
                        if (shortNote != null) {
                            Text(
                                shortNote,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (shortNote.contains("OCR未成功") || shortNote.contains("失败") || shortNote.contains("err=")) Color(0xFFB00020) else Color(0xFF2E7D32),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        // v1.3.6: 明细折叠/展开切换（点击统计行展开）
                        // v1.3.34: 备注合并为一条汇总，所以 notesSlides 只算 1
                        val detailCount = (r.inner?.size ?: 0) + (r.sheets?.size ?: 0) +
                            (r.hiddenSheets?.size ?: 0) +
                            (if (r.notesSlides?.isNotEmpty() == true) 1 else 0) +
                            (if (r.cadParts != null) 2 else 0) +
                            if (r.imageCount > 0) 1 else 0
                        if (detailCount > 0) {
                            Text(
                                if (expanded.value) "▲ 收起明细" else "▶ 展开${detailCount}项明细",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { expanded.value = !expanded.value }
                            )
                        }
                        if (r.hasUnreliable) Text("含未统计图片（可导出）", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00))
                    } else if (entry.error != null) {
                        val shortErr = entry.error!!.substringBefore('\n').take(200)
                        Text("处理出错：$shortErr", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB00020))
                    } else {
                        Text("统计中…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                // 右侧：文件类型标签 + 隐藏表/备注/图片标记 + Word/WPS 按钮（上下排列）
                Column(horizontalAlignment = Alignment.End) {
                    // v1.3.3: 有隐藏工作表的文件，在右列顶部显示红色小"隐"字
                    if (entry.result?.hiddenSheets?.isNotEmpty() == true) {
                        Text("隐", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020),
                            modifier = Modifier.padding(bottom = 2.dp))
                    }
                    // v1.3.32/v1.3.34: 有备注的 PPT，显示红色小"备"字（和"隐"同色）
                    if (entry.result?.notesSlides?.isNotEmpty() == true) {
                        Text("备", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020),
                            modifier = Modifier.padding(bottom = 2.dp))
                    }
                    // v1.3.32/v1.3.34: 有嵌入图片的 PPT，显示红色小"图"字（和"隐"同色）
                    if ((entry.result?.imageCount ?: 0) > 0) {
                        Text("图", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020),
                            modifier = Modifier.padding(bottom = 2.dp))
                    }
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(" ${entry.result?.ext?.uppercase() ?: "?"} ", Modifier.padding(6.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    // v1.3.0: 用 Word 打开（仅 Word 文档/txt/rtf 显示）
                    val wordExts = setOf(".doc", ".docx", ".txt", ".rtf")
                    if (wordExts.contains((entry.result?.ext ?: "").lowercase())) {
                        Text("Word",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2B579A),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { onOpenWord(entry) })
                    }
                    // v1.3.4/v1.3.32: 用 Wps 打开（xls/xlsx + ppt/pptx + pdf，仅支持 WPS）
                    val wpsExts = setOf(".xls", ".xlsx", ".ppt", ".pptx", ".pdf")
                    if (wpsExts.contains((entry.result?.ext ?: "").lowercase())) {
                        Text("Wps",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF217346),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { onOpenWps(entry) })
                    }
                    // v1.5.37/v1.5.63: DWG 统计不准（needsPdf）且确实没拿到任何字数时，
                    //   在右下角显示红色「用PDF统计」按钮，点此弹窗选一份文字型 PDF 来
                    //   重新统计。如果已经拿到字数（如 v1.5.62 水雾终极兜底成功），则不再
                    //   显示该按钮，避免用户困惑。
                    val result = entry.result
                    val hasAnyWords = (result?.words ?: 0) > 0 || (result?.fe ?: 0) > 0 || (result?.nc ?: 0) > 0
                    if (result?.needsPdf == true && !hasAnyWords) {
                        Text("用PDF统计",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB00020),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { onPickPdf(entry) })
                    }
                }
            }
            // v1.3.6: 明细区默认折叠，点击"展开N项明细"才显示
            if (expanded.value) {
            // v1.5.81: 压缩包内层文件带勾选框，可控制是否计入底部合计
            entry.result?.inner?.forEachIndexed { index, inner ->
                val innerKey = "${entry.id}::inner::$index"
                val innerChecked = hiddenSelected[innerKey] ?: true
                Column(Modifier.padding(start = 32.dp, top = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = innerChecked, onCheckedChange = { onToggleInner(entry.id, index) }, modifier = Modifier.size(24.dp))
                        Text(inner.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // v1.5.85/v1.5.88: 第一行只显示文件名(独占一行避免截断)，第二行显示 字数/中文/非中文｜页数
                    // v1.5.89: 内层文件恢复显示实际字数（与电脑版压缩包统计口径一致）；
                    // needsPdf 仅保留字段，不再把字数替换为提示。
                    val pageStr = inner.pages?.let { "页 $it" } ?: "页 -"
                    Text("字 ${inner.words} 中 ${inner.fe} 非 ${inner.nc} ｜ $pageStr",
                        Modifier.padding(start = 24.dp), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            entry.result?.sheets?.forEach { s ->
                Text("▪ 工作表：$s", Modifier.padding(start = 40.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            // v1.3.3: 隐藏工作表（红"隐" + 勾选框 + 名称 + 字数），勾选后并入合计
            entry.result?.hiddenSheets?.forEach { hs ->
                val checked = hiddenSelected["${entry.id}::${hs.name}"] ?: false
                Row(Modifier.padding(start = 32.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("隐", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020))
                    Checkbox(checked = checked, onCheckedChange = { onToggleHidden(entry.id, hs.name) }, modifier = Modifier.size(24.dp))
                    Text(hs.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("字 ${hs.words} 中 ${hs.fe} 非 ${hs.nc}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            // v1.3.34: PPT 备注汇总（红"备" + 勾选框 + "所有备注(N张)" + 汇总字数），勾选后并入合计
            val notes = entry.result?.notesSlides
            if (!notes.isNullOrEmpty()) {
                // 汇总所有备注的字数
                val totalNotesWords = notes.sumOf { it.words }
                val totalNotesFe = notes.sumOf { it.fe }
                val totalNotesNc = notes.sumOf { it.nc }
                // 用第一条备注的 key 作为勾选状态 key（勾选=全部并入）
                val notesKey = "${entry.id}::notes::_summary_"
                val notesChecked = hiddenSelected[notesKey] ?: false
                Row(Modifier.padding(start = 32.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("备", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020))
                    Checkbox(checked = notesChecked, onCheckedChange = { onToggleHidden(entry.id, "notes::_summary_") }, modifier = Modifier.size(24.dp))
                    Text("备注（${notes.size}）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("字 $totalNotesWords 中 $totalNotesFe 非 $totalNotesNc", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            // v1.5.61: CAD 文字/纯编号拆分（默认都勾选，与父行总数一致；可取消勾选以汇总指定部分）
            val cadParts = entry.result?.cadParts
            if (cadParts != null) {
                val textKey = "${entry.id}::cad::text"
                val codeKey = "${entry.id}::cad::code"
                val textChecked = hiddenSelected[textKey] ?: true
                val codeChecked = hiddenSelected[codeKey] ?: true
                Row(Modifier.padding(start = 32.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("文", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2B579A))
                    Checkbox(checked = textChecked, onCheckedChange = { hiddenSelected[textKey] = !(hiddenSelected[textKey] ?: true) }, modifier = Modifier.size(24.dp))
                    Text("文字部分（${cadParts.textItems}）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("字 ${cadParts.textWords} 中 ${cadParts.textFe} 非 ${cadParts.textNc}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Row(Modifier.padding(start = 32.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("编", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2B579A))
                    Checkbox(checked = codeChecked, onCheckedChange = { hiddenSelected[codeKey] = !(hiddenSelected[codeKey] ?: true) }, modifier = Modifier.size(24.dp))
                    Text("纯编号部分（${cadParts.codeItems}）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("字 ${cadParts.codeWords} 中 ${cadParts.codeFe} 非 ${cadParts.codeNc}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            // v1.3.32/v1.3.34: PPT 嵌入图片（红"图" + 张数），页数列显示张数，字数列显示"—"
            if ((entry.result?.imageCount ?: 0) > 0) {
                Row(Modifier.padding(start = 32.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("图", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB00020))
                    Text("嵌入图片", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("—", style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                        modifier = Modifier.padding(end = 8.dp))
                    Text("${entry.result!!.imageCount} 张", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            // v1.9.58: PDF/DWG 完整诊断（含决策路径）放到展开明细末尾，便于复制发开发者定位。
            val diagText = entry.result?.diag
            if (!diagText.isNullOrBlank()) {
                Text(
                    diagText,
                    Modifier.padding(start = 32.dp, top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            } // end expanded
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
/**
 * 纯 Kotlin 字数统计，严格对齐桌面版 wordcount.py 的 count_unit 算法（v1.2.6 回退修正）。
 *
 * 口径与桌面版 wordcount.py 完全一致（该算法已用 Word COM 校验）：
 *   fe（中文字符和朝鲜语单词）= 落在 FarEast Unicode 区间内的每个字符各算 1 个
 *   nc（非中文单词）           = 连续的「非空白、非 FarEast」字符串算 1 个词
 *   chars（字符数不计空格）    = 所有非空白字符总数
 *   words = fe + nc
 *
 * FarEast 区间定义（与桌面版 _FAR 完全一致 + v1.2.2 新增通用标点）：
 *   \u1100-\u11FF   Hangul Jamo
 *   \u2000-\u206F   通用标点（弯引号、破折号、省略号等，v1.2.2新增）
 *   \u3000-\u303F   CJK 符号与标点
 *   \u3130-\u318F   Hangul 兼容 Jamo
 *   \u3400-\u4DBF   CJK 扩展 A
 *   \u4E00-\u9FFF   CJK 基本平面
 *   \uA960-\uA97C   Hangul Jamo 扩展 A
 *   \uAC00-\uD7A3   Hangul 音节
 *   \uD7B0-\uD7FF   Hangul Jamo 扩展 B
 *   \uF900-\uFAFF   CJK 兼容
 *   \uFF00-\uFFEF   全角字符
 *
 * 重要：ASCII 标点（,.!?()等）不在 FarEast 区间内，会被归入 nc 连续段
 * （这是正确行为——与 Word COM 的 FarEastCharacters 定义一致）
 *
 * 返回 (words, fe, nc, chars)
 */
fun countTextKotlin(text: String): Quadruple<Int, Int, Int, Int> {
    // FarEast 正则：与桌面版 wordcount.py 的 _FAR 完全一致（v1.2.8: 去掉 v1.2.2 误加的 \u2000-\u206F）
    val farEastRegex = Regex("[\\u1100-\\u11FF\\u3000-\\u303F\\u3130-\\u318F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uA960-\\uA97C\\uAC00-\\uD7A3\\uD7B0-\\uD7FF\\uF900-\\uFAFF\\uFF00-\\uFFEF]")
    // 非 CJK 词：连续的非空白、非 FarEast 字符串
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

/**
 * v1.9.103（同步桌面 wordcount.py v1.8.61）：PDF 文字层去噪，供 L1 Kotlin 快速路径使用。
 * 英文/混排 PDF 被排版软件塞入全角标点（全角括号/句号/分号/半角片假名中点），或 CAD 转 PDF
 * 字体未嵌入时吐出 (cid:NNNN) 占位符，会被 FAR_EAST 误计为「中文」/非中文词虚增。
 * 与桌面 extract_pdf 逐段清洗口径一致：英文为主段落（CJK 占比<15%）删 CJK 内容/标点/全角标点，
 * 删连续装饰中点；整段 (cid: 乱码丢弃；中文主导段原样保留。仅作用于 PDF 文字层，不作用于 OCR 结果。
 */
private val PDF_CJK_CONTENT_RE = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uAC00-\\uD7A3]")
private val PDF_CJK_PUNCT_RE = Regex("[\\u3000-\\u303F]")
private val PDF_FW_PUNCT_RE = Regex("[\\uFF00-\\uFF0F\\uFF1A-\\uFF20\\uFF3B-\\uFF40\\uFF5B-\\uFF9F\\uFFE0-\\uFFEF]")
private val PDF_LEADER_DOT_RE = Regex("[・･]{2,}")
private val PDF_CID_RE = Regex("\\(cid:|\\(CID:", RegexOption.IGNORE_CASE)

private fun pdfTextIsPoisoned(para: String): Boolean {
    if (para.isEmpty()) return true
    val s = para.trim()
    if (s.length < 10) return false
    val n = s.length
    val cid = PDF_CID_RE.findAll(s).count()
    if (cid >= 3) return true
    var bad = 0
    var exotic = 0
    s.codePoints().toArray().forEach { cp ->
        if (cp == 0x00 || cp == 0x08 || cp == 0x0B || cp == 0x0C || cp == 0x0E || cp == 0x0F ||
            (0xE000 <= cp && cp <= 0xF8FF) ||
            (0xF0000 <= cp && cp <= 0xFFFFD) ||
            (0x100000 <= cp && cp <= 0x10FFFD)) {
            bad++
        }
        if ((0x0080 <= cp && cp <= 0x024F) ||
            (0x02B0 <= cp && cp <= 0x036F) ||
            (0x2150 <= cp && cp <= 0x22FF) ||
            (0x2300 <= cp && cp <= 0x23FF)) {
            exotic++
        }
    }
    return bad >= maxOf(8, (n * 0.05).toInt()) || exotic >= maxOf(20, (n * 0.30).toInt())
}

fun sanitizePdfTextLayer(text: String): String {
    val paragraphs = text.split(Regex("\\n\\s*\\n"))
    val out = mutableListOf<String>()
    for (para in paragraphs) {
        val trimmed = para.trim()
        if (trimmed.isEmpty()) continue
        if (pdfTextIsPoisoned(trimmed)) continue
        var p = PDF_LEADER_DOT_RE.replace(trimmed, "")
        val nonSpace = p.replace(Regex("\\s"), "")
        if (nonSpace.isEmpty()) continue
        val cjkContent = PDF_CJK_CONTENT_RE.findAll(p).count()
        if (cjkContent.toDouble() / nonSpace.length < 0.15) {
            p = PDF_CJK_CONTENT_RE.replace(p, "")
            p = PDF_CJK_PUNCT_RE.replace(p, "")
            p = PDF_FW_PUNCT_RE.replace(p, "")
        }
        val cleaned = p.trim()
        if (cleaned.isNotEmpty()) out.add(cleaned)
    }
    return out.joinToString("\n\n")
}

/**
 * v1.5.61: 判断一条 CAD 文字条目是否属于『纯编号』（不需要翻译）。
 * 端口桌面版 wordcount.py is_cad_code_item，保守判定：
 *   - 含任何 CJK/假名/韩文          → 文字部分
 *   - 含任何长度 ≥2 的连续字母串    → 文字部分（DN100 / PE / Pump / Room）
 *   - 其余（纯数字、数字+下划线、单字母+数字、纯符号、空串）→ 纯编号部分
 */
private val CAD_CJK_RE = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\u3040-\\u30ff\\uac00-\\ud7af]")
private val CAD_WORD_RE = Regex("[A-Za-z\\u00c0-\\u024f]{2,}")
fun isCadCodeItem(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty()) return true
    if (CAD_CJK_RE.containsMatchIn(t)) return false
    if (CAD_WORD_RE.containsMatchIn(t)) return false
    return true
}

/**
 * v1.5.61: 把 CAD 提取文本拆成文字部分 / 纯编号部分，并分别统计。
 * 父行总数不变；展开后两个子项可加选框控制是否并入底部合计。
 * 没有编号条目时返回 null（不必展开）。
 */
fun computeCadParts(text: String): CadPartStats? {
    if (text.isBlank()) return null
    val textLines = mutableListOf<String>()
    val codeLines = mutableListOf<String>()
    for (line in text.split("\n")) {
        val s = line.trim()
        if (s.isEmpty()) continue
        if (isCadCodeItem(s)) codeLines.add(s) else textLines.add(s)
    }
    if (codeLines.isEmpty()) return null
    val textStats = countTextKotlin(textLines.joinToString("\n"))
    val codeStats = countTextKotlin(codeLines.joinToString("\n"))
    return CadPartStats(
        textWords = textStats.first, textFe = textStats.second, textNc = textStats.third, textChars = textStats.fourth,
        codeWords = codeStats.first, codeFe = codeStats.second, codeNc = codeStats.third, codeChars = codeStats.fourth,
        textItems = textLines.size, codeItems = codeLines.size
    )
}

/**
 * v1.5.10: 直接扫描 DWG 二进制提取文字（移植自 port_dwg.py improved 模式）。
 * 不再依赖 DXF 中间格式，避免 DXF TEXT/MTEXT 含尺寸数值/坐标/格式码导致字数虚高。
 * 算法：扫描原始字节 → ASCII串 + UTF-16LE串 + CJK → stopwords/元音/数字比例过滤 → 去重
 */
private val DWG_STOPWORDS = setOf(
    "standard","bylayer","byblock","continuous","defpoints","model","layout",
    "paper","space","center","dashed","dot","divide","border","phantom",
    "hidden","dashdot","chain","zigzag",
    "txt","romans","romanc","italicc","italict","scripts","scriptc",
    "greeks","greeke","cyrillic","cyriltlc","monotxt","simplex",
    "complex","isoct","isocteur",
    "autocad","acad","entity","handle","object","dictionary",
    "linetype","layer","style","block","viewport","ucs","view",
    "table","id","type","owner","flags","count","index","name",
    "data","null","true","false","none","normal","color","width",
    "height","length","angle","point","line","circle","arc","text",
    "dimension","leader","hatch","solid","polyline","insert",
    "attrib","mtext","attdef","acdb","acds","acim","objects",
    "classes","handles","summaryinfo","preview","appinfo",
    "filedeps","security","revhistory","header","auxheader",
    "signature","template"
)

private fun isCjkChar(cp: Int): Boolean {
    return (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) ||
           (cp in 0x3000..0x303F) || (cp in 0xFF00..0xFFEF) ||
           (cp in 0x2E80..0x2EFF) || (cp in 0xF900..0xFAFF)
}

/** 归一化行键：去空白、去标点、小写，用于软去重 */
internal fun normKey(s: String): String =
    s.lowercase().replace(Regex("[\\s\\p{P}]+"), "")

/** 判断字符串是否像真实文字（非 CAD 元数据/坐标/二进制垃圾）*/
private fun looksLikeRealText(s: String, minRun: Int): Boolean {
    if (s.length < minRun) return false
    val low = s.lowercase()
    if (low in DWG_STOPWORDS) return false
    // 至少 3 个字母
    val letters = s.count { it.isLetter() }
    if (letters < 3) return false
    // 数字占比不超过 50%（排除坐标）
    val digits = s.count { it.isDigit() }
    if (s.isNotEmpty() && digits.toDouble() / s.length > 0.5) return false
    // 符号占比不超过 35%
    val alnum = letters + digits
    if (s.isNotEmpty() && (s.length - alnum).toDouble() / s.length > 0.35) return false
    // 纯大写短串（<=6字符）通常是缩写/代码
    if (s.all { it.isUpperCase() || !it.isLetter() } && s.length <= 6) return false
    // 需要元音或空格（多词短语）
    val hasVowel = s.any { it.lowercase() in "aeiou" }
    val hasSpace = ' ' in s
    return hasVowel || hasSpace
}

fun extractDxfText(dxfPath: String): String {
    // v1.5.10: 此函数保留签名兼容但不再用于 DWG 字数统计；
    // DWG 统计已改用 scanDwgRaw() 直接扫二进制。
    // 若传入的是 DXF 文件则走原有逻辑兜底。
    return if (dxfPath.endsWith(".dxf", ignoreCase = true)) {
        extractDxfTextCompat(dxfPath)
    } else {
        scanDwgRaw(dxfPath)
    }
}

/** 原有 DXF 解析逻辑（仅作 .dxf 文件兜底）*/
private fun extractDxfTextCompat(dxfPath: String): String {
    val sb = StringBuilder()
    try {
        val lines = File(dxfPath).readLines()
        var i = 0
        var currentEntity = ""
        while (i < lines.size - 1) {
            val code = lines[i].trim()
            val value = if (i + 1 < lines.size) lines[i + 1].trim() else ""
            when (code) {
                "0" -> currentEntity = value.uppercase()
                "1", "3" -> {
                    if (value.isNotEmpty() && (currentEntity == "TEXT" || currentEntity == "MTEXT" || currentEntity == "ATTDEF")) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(value)
                    }
                }
            }
            i += 2
        }
    } catch (e: Exception) {
        Diag.w( "DXF 兼容提取异常: ${e.message}")
    }
    return sb.toString()
}

/**
 * 核心：直接扫描 DWG 二进制字节提取文字。
 * 移植自 port_dwg.py improved 模式——与电脑端完全一致的过滤逻辑。
 */
fun scanDwgRaw(dwgPath: String): String {
    val out = mutableListOf<String>()
    val seen = HashSet<String>(500)
    val asciiBuf = StringBuilder()
    val cjkBuf = StringBuilder()

    fun flushAscii() {
        if (asciiBuf.isEmpty()) return
        val s = asciiBuf.toString()
        asciiBuf.clear()
        if (looksLikeRealText(s, 4) && s !in seen) {
            seen.add(s); out.add(s)
        }
    }

    fun flushCjk() {
        if (cjkBuf.isEmpty()) return
        val s = cjkBuf.toString()
        cjkBuf.clear()
        if (s.length >= 3 && s !in seen) {
            seen.add(s); out.add(s)
        }
    }

    try {
        val data = File(dwgPath).readBytes()
        val n = data.size
        var i = 0
        while (i < n && out.size < 50000) {  // 上限防 OOM
            val b = data[i].toInt() and 0xFF
            when {
                // ASCII run (not part of UTF-16LE)
                b in 0x20..0x7E && (i + 1 >= n || data[i + 1] != 0x00.toByte()) -> {
                    asciiBuf.append(b.toChar()); i++
                }
                // UTF-16LE string
                b in 0x20..0x7E && i + 1 < n && data[i + 1] == 0x00.toByte() -> {
                    while (i + 1 < n) {
                        val c = data[i].toInt() and 0xFF
                        val nx = data[i + 1]
                        if (c in 0x20..0x7E && nx == 0x00.toByte()) {
                            asciiBuf.append(c.toChar()); i += 2
                        } else break
                    }
                }
                // CJK (UTF-8 3-byte sequence)
                b in 0xE0..0xEF && i + 2 < n -> {
                    val b2 = data[i + 1].toInt() and 0xFF
                    val b3 = data[i + 2].toInt() and 0xFF
                    if (b2 in 0x80..0xBF && b3 in 0x80..0xBF) {
                        val cp = ((b and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                        if (isCjkChar(cp)) {
                            cjkBuf.append(cp.toChar())
                        } else { flushCjk(); flushAscii() }
                        i += 3
                    } else { flushCjk(); flushAscii(); i++ }
                }
                else -> { flushAscii(); flushCjk(); i++ }
            }
        }
        flushAscii(); flushCjk()
    } catch (e: Exception) {
        Diag.w( "DWG 二进制扫描异常: ${e.message}")
    }

    return out.joinToString("\n")
}

/** 简单四元组（避免引入额外依赖）*/
data class Quadruple<out A, out B, out C, out D>(val first: A, val second: B, val third: C, val fourth: D)

/** 用系统/其它应用打开缓存文件（点击文件名或「打开」触发）。 */
private fun openWithOtherApp(context: android.content.Context, entry: FileEntry) {
    try {
        val file = File(entry.cachePath)
        if (!file.exists()) {
            Diag.w( "打开失败：缓存文件不存在 ${entry.displayName}")
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
        Diag.w( "打开文件失败 ${entry.displayName}: ${e.message}")
    }
}

/** v1.3.0: 直接拉起手机 Microsoft Word 打开文件（用于核对 Word 显示的页数和字数）。
 *  若未安装 Word 或打开失败，退回系统选择器。 */
private fun openWithWord(context: android.content.Context, entry: FileEntry) {
    try {
        val file = File(entry.cachePath)
        if (!file.exists()) {
            Diag.w( "用Word打开失败：缓存文件不存在 ${entry.displayName}")
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val mime = mimeForExt(entry.result?.ext ?: "")
        // 直接指定 Word 包名，不经过 resolveActivity 检查（该检查在某些设备上误判）
        try {
            val wordIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                `package` = "com.microsoft.office.word"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(wordIntent)
        } catch (e: android.content.ActivityNotFoundException) {
            // 未安装 Word，退回系统选择器
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(fallback, "用其他应用打开"))
        }
    } catch (e: Throwable) {
        Diag.w( "用Word打开失败 ${entry.displayName}: ${e.message}")
    }
}

/** v1.3.3: 直接拉起手机 Excel/WPS 打开文件（用于核对 Excel 显示的页数和字数）。
 *  优先级：WPS → Microsoft Excel → 系统选择器。WPS 的「逐页输出图片」导出动作无文件级
 *  页数元数据，无法自动读取，故提供此按钮让用户手动核对。 */
private fun openWithWps(context: android.content.Context, entry: FileEntry) {
    try {
        val file = File(entry.cachePath)
        if (!file.exists()) {
            Diag.w( "用Wps打开失败：缓存文件不存在 ${entry.displayName}")
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val mime = mimeForExt(entry.result?.ext ?: "")
        // v1.3.4: 仅支持用 WPS 打开（不再尝试 Microsoft Excel / 系统选择器）
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                `package` = "cn.wps.moffice_eng"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // 未安装 WPS
            android.widget.Toast.makeText(
                context, "未安装 WPS，请先安装 WPS 后再打开", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    } catch (e: Throwable) {
        Diag.w( "用Wps打开失败 ${entry.displayName}: ${e.message}")
    }
}

/**
 * v1.5.36: 用户为「统计不准」的 DWG 选定一份文字型 PDF 后，用该 PDF 重新统计该文件。
 *   复用 PDF 提取 Level1(Kotlin PdfExtractor)+Level2(Python pdfminer)，取 chars 较多者，
 *   返回新的字数/页数；若都提取不到文字则返回 null（调用方保留原 DWG 结果并提示）。
 *   仅用于「文字型」PDF（非扫描件）；扫描件/图片型 PDF 抽不到字，会返回 null。
 *   取代旧版 exportDwgToPdf（LibreDWG 自渲染的 PDF 文字层是栅格化图像，抽不到字、没用）。
 */
private data class RecomputedPdf(val words: Int, val fe: Int, val nc: Int, val chars: Int, val pages: Int?, val diag: String?)

private suspend fun recomputeFromPdf(context: android.content.Context, pdfFile: File, dName: String): RecomputedPdf? {
    return withContext(Dispatchers.IO) {
        try {
            // ── Level 1: Kotlin PdfExtractor（快速预筛）──
            val ktRes = PdfExtractor.extract(pdfFile)
            // v1.9.103：L1 文字层同样去噪（与桌面 extract_pdf / L2 pdfminer 口径一致）。
            val ktCleanText = sanitizePdfTextLayer(ktRes.text)
            val ktStats = countTextKotlin(ktCleanText)
            var bestWords = ktStats.first; var bestFe = ktStats.second; var bestNc = ktStats.third; var bestChars = ktStats.fourth
            var bestPages: Int? = ktRes.pages
            var bestDiag = "Kotlin提取(${bestChars}字)"
            Diag.d( "recomputeFromPdf L1 $dName: chars=$bestChars words=$bestWords pages=$bestPages")
            // ── Level 2: Python pdfminer（文字型 PDF 主力）──
            try {
                val pyResults = PythonEngine.countFiles(context, listOf(pdfFile.absolutePath))
                @Suppress("UNCHECKED_CAST")
                val pyList = pyResults as? List<Map<String, Any?>>
                if (!pyList.isNullOrEmpty()) {
                    val py0 = pyList[0]
                    if (py0["ok"] == true) {
                        val pyData = py0["result"] as? Map<String, Any?>
                        val pyS = pyData?.get("stats") as? Map<String, Any?>
                        val pyWords = (pyS?.get("words") as? Number)?.toInt() ?: 0
                        val pyFe = (pyS?.get("fe") as? Number)?.toInt() ?: 0
                        val pyNc = (pyS?.get("nc") as? Number)?.toInt() ?: 0
                        val pyChars = (pyS?.get("chars") as? Number)?.toInt() ?: 0
                        val pyPages = (pyData?.get("pages") as? Number)?.toInt()
                        if (pyChars > bestChars) {
                            bestWords = pyWords; bestFe = pyFe; bestNc = pyNc; bestChars = pyChars
                            bestPages = pyPages ?: bestPages
                            bestDiag = "Python提取(${bestChars}字)"
                        }
                        Diag.d( "recomputeFromPdf L2 $dName: chars=$pyChars pages=$pyPages")
                    } else {
                        Diag.w( "recomputeFromPdf L2 ok=false $dName: ${py0["error"]}")
                    }
                }
            } catch (e: Throwable) {
                Diag.w( "recomputeFromPdf Python异常 $dName: ${e.javaClass.simpleName}: ${e.message}")
            }
            if (bestChars <= 0) {
                Diag.w( "recomputeFromPdf 未提取到文字 $dName")
                null
            } else {
                RecomputedPdf(bestWords, bestFe, bestNc, bestChars, bestPages, bestDiag)
            }
        } catch (e: Throwable) {
            Diag.w( "recomputeFromPdf 异常 $dName: ${e.message}")
            null
        }
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
 * v1.5.55: 扫描 URI 的所有可见部分（path segments / query / fragment / lastPathSegment），
 * 尝试找回被 ContentResolver 隐藏的真实文件名。微信/QQ 等应用分享文件时，
 * DISPLAY_NAME 经常返回内部缓存 ID，但原文件名可能仍保留在 URI 的某个片段里。
 */
private fun scanUriForRealName(uri: Uri): String? {
    val knownExts = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt",
        "png", "jpg", "jpeg", "bmp", "gif", "webp", "tif", "tiff",
        "zip", "rar", "7z", "dwg", "dxf"
    )
    val rawSources = listOfNotNull(
        uri.toString(),
        uri.encodedPath,
        uri.path,
        uri.query,
        uri.fragment,
        uri.lastPathSegment
    )
    val parts = mutableListOf<String>()
    rawSources.forEach { raw ->
        // 有些路径会被整体 URL 编码一次甚至两次，逐级解码
        var current = raw
        repeat(3) {
            val decoded = try { java.net.URLDecoder.decode(current, "UTF-8") } catch (_: Throwable) { current }
            if (decoded == current) return@repeat
            parts.addAll(decoded.split('/', '\\', '?', '&', '=', '#', ':', ';'))
            current = decoded
        }
        // 最后也把未解码的源本身按分隔符拆开（防止解码失败时遗漏）
        parts.addAll(raw.split('/', '\\', '?', '&', '=', '#', ':', ';'))
    }

    return parts.asSequence()
        .map { it.trim() }
        .filter { it.length in 5..250 }
        .filter { cand ->
            val dot = cand.lastIndexOf('.')
            if (dot <= 0 || dot >= cand.length - 1) return@filter false
            cand.substring(dot + 1).lowercase() in knownExts
        }
        .filterNot { looksLikeHashString(it) }
        .sortedWith(
            compareByDescending<String> { it.any { c -> c.code in 0x4E00..0x9FFF } }
                .thenByDescending { it.length }
                .thenByDescending { it.count { c -> c.isLetterOrDigit() } }
        )
        .firstOrNull()
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
    // v1.1.10 放宽判定：避免误伤真实长文件名（如学号文件名 "3343976213xxx.docx"）
    fun looksLikeHash(s: String): Boolean {
        val t = s.trim()
        if (t.length < 8) return false
        // 纯 hex 字符串（长度>=16，典型 UUID/hash）
        if (t.matches(Regex("^[a-fA-F0-9]{16,}$"))) return true
        if (t.matches(Regex("^[a-fA-F0-9]{8}-([a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}$"))) return true
        // wc_ 或 file_ 前缀 + 长数字（临时文件名模式）
        if (t.matches(Regex("^(wc_|file_)[0-9a-f]{10,}"))) return true
        // 带扩展名的 hash（如 ContentResolver 返回的 "9e20f478899dc29eb1xxx.pdf"）
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
                // 要么纯 hex，要么字母全是小写且无元音/语义，要么超长(>36)
                // v1.1.10: 24→36，避免误伤 10~30 字符的数字编号文件名
                val hasVowel = base.any { it.lowercaseChar() in 'a'..'z' && it.lowercaseChar() in setOf('a','e','i','o','u') }
                val isPureHex = base.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                val isMostlyDigits = base.count { it.isDigit() } > base.length * 0.6
                // v1.1.11 修复：纯 hex basename ≥16 字符即判定为 hash（之前 >36 导致 28~32 字符的 ContentResolver 内部 ID 漯网）
                // 非纯 hex 的数字编号文件名（如学号）才需要更长阈值
                val hasCjkPunct = base.any { it in setOf('(', ')', '（', '）', '[', ']', '【', '】', '_', '-', '#', '@') }
                if (isPureHex && base.length >= 16 && !hasCjkPunct) return true
                if (!isPureHex && !hasVowel && isMostlyDigits && !hasCjkPunct && base.length > 36) return true
            }
        }
        // 以数字和少量字母为主的长字符串（内部 ID 特征）：字母+数字混合，长度>20，无中文/无常见扩展名
        if (t.length > 20 && !t.contains(".") && !t.any { it.code in 0x4E00..0x9FFF } &&
            t.count { it.isLetterOrDigit() } > t.length * 0.9) return true
        return false
    }

    // 策略0（v1.5.56）: 部分 ROM / 微信分享 ContentProvider 在 _data 列暴露真实文件路径，
    //   从路径里取到的文件名通常比 DISPLAY_NAME 可靠。
    try {
        context.contentResolver.query(
            uri,
            arrayOf("_data"),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && cursor.columnCount > 0) {
                val path = cursor.getString(0)
                if (!path.isNullOrBlank()) {
                    val name = path.trim().substringAfterLast('/').substringAfterLast('\\')
                    if (name.isNotBlank() && name.contains('.') && !looksLikeHash(name)) {
                        Diag.d( "resolveDisplayName s0(_data path OK): '$name'")
                        return name.trim()
                    }
                    Diag.d( "resolveDisplayName s0 _data 被hash拦截/无扩展名: '$name'")
                }
            }
        }
    } catch (_: Throwable) {}

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
                    Diag.d( "resolveDisplayName s1(ContentResolver OK): '$name'")
                    return name.trim()
                }
                Diag.d( "resolveDisplayName s1 被hash拦截: '$name'")
            }
        }
    } catch (_: Throwable) {}

    // 策略2: DocumentFile.fromSingleUri
    androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name?.let { name ->
        if (name.isNotBlank() && !looksLikeHash(name)) {
            Diag.d( "resolveDisplayName s2(DocumentFile OK): '$name'")
            return name.trim()
        }
        Diag.d( "resolveDisplayName s2 被hash拦截/空: '$name'")
    }

    // 策略2.5 (v1.5.55): 扫描 URI 全部片段（path/query/fragment）。
    // 微信/QQ 等分享时，ContentResolver 只返回内部缓存 ID，但原文件名可能还藏在 URI 里。
    scanUriForRealName(uri)?.let { name ->
        if (name.isNotBlank()) {
            Diag.d( "resolveDisplayName s2.5(URI scan OK): '$name'")
            return name.trim()
        }
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
                Diag.d( "resolveDisplayName s3(URI path OK): '$extracted'")
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
                            Diag.d( "resolveDisplayName s4(PDF /Title): '$clean'")
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
                Diag.d( "resolveDisplayName s5(宽松路径): '$candidate'")
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
    Diag.w( "resolveDisplayName 全部策略失败 → 兜底: '$friendly' (uri=$uri)")
    return friendly
}

/** v1.0.38: 兜底命名计数器（避免同名冲突） */
private var cachedFileCounter = 0
/** v1.1.13: 安全网替换名计数器（确保每个被替换的文件有唯一序号） */
private var friendlyNameCounter = 0

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

/** v1.1.10: 独立 hash 检测函数（copyUriToCache 安全网用，不依赖 resolveDisplayName 内嵌版本）
 *  与 resolveDisplayName 内的 looksLikeHash 保持同步放宽判定。
 */
private fun looksLikeHashString(s: String): Boolean {
    val t = s.trim()
    if (t.length < 8) return false
    // 纯 hex（>=16字符）
    if (t.matches(Regex("^[a-fA-F0-9]{16,}$"))) return true
    // UUID 格式
    if (t.matches(Regex("^[a-fA-F0-9]{8}-([a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}$"))) return true
    // wc_/file_ 前缀
    if (t.matches(Regex("^(wc_|file_)[0-9a-f]{10,}"))) return true
    // 带扩展名的 hash
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
            val hasCjkPunct = base.any { it in setOf('(', ')', '（', '）', '[', ']', '【', '】', '_', '-', '#', '@') }
            // v1.1.11: 纯 hex ≥16 即 hash；非纯 hex 编号文件名才需 >36
            if (isPureHex && base.length >= 16 && !hasCjkPunct) return true
            if (!isPureHex && !hasVowel && isMostlyDigits && !hasCjkPunct && base.length > 36) return true
        }
    }
    // 长数字字母混合无中文
    if (t.length > 20 && !t.contains(".") && !t.any { it.code in 0x4E00..0x9FFF }
        && t.count { it.isLetterOrDigit() } > t.length * 0.9) return true
    return false
}

/**
 * v1.1.12: 检测"可疑文件名"——不像人类命名的任何长字符串。
 * 作为 looksLikeHashString 的补充，捕获那些不完全匹配 hex/UUID 格式
 * 但明显是系统内部 ID（如 ContentResolver 返回的编码标识符）。
 *
 * 规则：
 * 1. 名字较长(>12)且无 CJK 字符
 * 2. 去扩展名后，包含连续 8+ 个十六进制字符的序列（hash 特征片段）
 * 3. 或者 basename 看起来像随机字母数字混合（高熵特征）
 */
private fun isSuspiciousFilename(s: String): Boolean {
    val t = s.trim()
    if (t.length < 8) return false
    // 有 CJK 字符 → 很可能是真实文件名
    if (t.any { it.code in 0x4E00..0x9FFF }) return false

    // 分离 basename 和扩展名
    val dotIdx = t.lastIndexOf('.')
    val base = if (dotIdx > 0 && dotIdx < t.length - 1) t.substring(0, dotIdx) else t

    // 规则1: basename 中有连续 12+ 个 hex 字符的子串 → 几乎可以确定是某种编码 ID
    if (base.contains(Regex("[a-fA-F0-9]{12,}"))) return true

    // 规则2: basename 以 "9e" 或类似 hex 前缀开头 + 长度>=16 + 无语义分隔符
    // （某些 ROM 的 ContentResolver 内部 ID 固定以 9e 开头）
    if (base.length >= 16 && Regex("^[a-fA-F0-9]{2,}[a-fA-F0-9]+").matches(base)) return true

    // 规则3: 超长basename(>24) + 字母数字占比>90% + 不含元音(或极少) + 无空格/常见标点
    if (base.length > 24) {
        val alnumRatio = base.count { it.isLetterOrDigit() }.toFloat() / base.length
        val vowelCount = base.count { it.lowercaseChar() in setOf('a','e','i','o','u') }
        val letterCount = base.count { it.isLetter() }
        val vowelRatio = if (letterCount > 0) vowelCount.toFloat() / letterCount else 1f
        // 正常英文文件名元音比例约 30-60%；hash/ID 通常 < 10%
        if (alnumRatio > 0.85f && vowelRatio < 0.15f && letterCount >= 8) return true
    }

    return false
}

/**
 * v1.1.26: 检测编号模式或通用名——这类名称不包含有意义的文件标识信息。
 *
 * 覆盖范围：
 *   - 编号模式："1-1"、"1-(1)"、"图1"、"Sheet1"、"Document (2)" 等
 *   - 短编号 + 扩展名："1.docx"、"2.pdf"、"a.txt" 等
 *   - 通用名前缀："Word文档"、"PDF文档" 等
 */
private fun isNumberedOrGenericName(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty()) return true

    // 已有 CJK 且长度>4 → 大概率是有意义的中文文件名，不拦截
    if (t.any { it.code in 0x4E00..0x9FFF } && t.length > 4) return false

    // 分离 basename 和扩展名
    val dotIdx = t.lastIndexOf('.')
    val base = if (dotIdx > 0 && dotIdx < t.length - 1) t.substring(0, dotIdx) else t

    // 通用名前缀（安全网生成的）
    if (base.startsWith("Word文档") || base.startsWith("PDF文档") ||
        base.startsWith("Excel表格") || base.startsWith("PPT演示") ||
        base.startsWith("文本文件") || base.startsWith("图片") ||
        base.startsWith("文档") || base.startsWith("压缩包") ||
        base.startsWith("CAD图纸")) return true

    // 编号模式1: "数字-数字" 如 "1-1"、"2-3"，可选尾括号如 "1-1)"
    if (base.matches(Regex("^\\d+[)-]\\d+\\)?$"))) return true

    // 编号模式1b: "数字-数字)(数字)" 如 "1-1)(1)"
    if (base.matches(Regex("^\\d+[)-]\\d+\\)\\(\\d+\\)$"))) return true

    // 编号模式3: 纯短编号（1~3个字符）如 "1"、"2"、"a"
    if (base.length <= 3 && base.all { it.isLetterOrDigit() }) return true

    // 编号模式4: "单词+数字" 的短模式如 "Sheet1"、"Doc2"、"图1"
    if (base.matches(Regex("^(Sheet|Doc|Document|File|Image|Pic|图|档|表|页)\\d*$", RegexOption.IGNORE_CASE))) return true

    // 编号模式5: "数字)" 或 "(数字)" 或纯数字
    // 拆分为多个简单条件避免复杂正则转义问题
    if (Regex("""^\d+\)$""").matches(base)) return true       // "123)"
    if (Regex("""^\(\d+\)?$""").matches(base)) return true     // "(123)" 或 "(123"
    if (base.all { it.isDigit() } && base.isNotEmpty()) return true  // 纯数字

    // 编号模式6: "No." / "NO." 开头后跟纯数字/短文本
    if (Regex("""^(?i)(no\.?|number#?)\s*\d*$""").matches(base)) return true

    return false
}

private fun copyUriToCache(context: android.content.Context, uri: Uri): CachedFile {
    // v1.5.55: 微信/QQ 等分享传入时，Intent extras(EXTRA_SUBJECT/TITLE/_display_name)
    // 常携带原文件名，比 ContentResolver.DISPLAY_NAME 更可靠。
    val intentNameHint = MainActivity.pendingUriNames[uri]?.trim()
    val resolvedName = resolveDisplayName(context, uri)
    val originalName = when {
        intentNameHint.isNullOrBlank() -> resolvedName
        looksLikeHashString(intentNameHint) || isSuspiciousFilename(intentNameHint)
            || isNumberedOrGenericName(intentNameHint) -> resolvedName
        else -> intentNameHint
    }
    Diag.d( "copyUriToCache originalName='$originalName' hint='${intentNameHint ?: ""}' resolved='$resolvedName'")

    // v1.5.53 修正：basename <= 8 会误伤正常短文件名（如 Tenova.dwg、图.dwg），
    //   把它们全换成 "文档_<hash>.dwg" 导致用户无法识别。改为只拦截真正无意义的：
    //   A. hash/UUID/内部ID（looksLikeHashString / isSuspiciousFilename）
    //   B. 编号模式："1-1"、"1-(1)"、"图1"、"Sheet1"、纯数字、"No.1" 等
    //   C. 通用名前缀（"Word文档"/"PDF文档" 等，含安全网自身生成的前缀）
    //   D. 明确 "数字-数字)" 等带括号的短编号
    val baseName = originalName.substringBeforeLast('.').ifBlank { originalName }
    val isShortOrGeneric = looksLikeHashString(originalName)
            || isSuspiciousFilename(originalName)
            || isNumberedOrGenericName(originalName)
            // 明确捕获 "数字-数字)" 等带括号的短编号
            || Regex("""^\d+[)-]\d+\)?$""").matches(baseName)

    val displayName = if (isShortOrGeneric) {
        val ext = guessExt(context, uri)
        val safeExt = if (ext.isNotBlank()) ".$ext" else ""
        // v1.5.55: 对内部 ID/编号等无意义名，优先直接显示清理后的原始 ID，
        // 比 "文档_xxxx.dwg" 更简洁，也保留更多识别信息。
        val cleanedOriginal = originalName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val directName = if (cleanedOriginal.contains('.') && cleanedOriginal.length in 5..128) cleanedOriginal else ""
        val isSafeNetPrefix = directName.startsWith("Word文档") || directName.startsWith("PDF文档")
            || directName.startsWith("Excel表格") || directName.startsWith("PPT演示")
            || directName.startsWith("文本文件") || directName.startsWith("图片")
            || directName.startsWith("文档") || directName.startsWith("压缩包")
            || directName.startsWith("CAD图纸")
        if (directName.isNotBlank() && !isSafeNetPrefix) {
            Diag.w( "copyUriToCache 安全网直接显示内部ID: '$originalName' → '$directName'")
            directName
        } else {
            val typeLabel = when (ext.lowercase()) {
                "pdf" -> "PDF文档"
                "doc", "docx" -> "Word文档"
                "xls", "xlsx" -> "Excel表格"
                "ppt", "pptx" -> "PPT演示"
                "txt" -> "文本文件"
                "png", "jpg", "jpeg", "bmp", "gif", "webp" -> "图片"
                else -> "文档"
            }
            // v1.1.50: 用文件路径hash生成短后缀（4位hex），确保同名文件可区分
            val shortHash = absoluteHashCode(originalName).toString(16).takeLast(4).uppercase()
            val result = "${typeLabel}_${shortHash}${safeExt}"
            Diag.w( "copyUriToCache 安全网触发: '$originalName' → '$result' (baseName='$baseName' len=${baseName.length})")
            result
        }
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

    // v1.1.13: 如果 displayName 是通用名（安全网替换的），尝试从文件内部元数据获取真实标题
    val finalDisplayName = tryExtractInternalTitle(out, displayName)

    return CachedFile(out, finalDisplayName)
}

/**
 * v1.1.26: 从已缓存的文件中提取有意义的显示名称，用于替换非理想名称。
 * 按优先级尝试：dc:title > PDF /Title > DOCX内容首行 > 原名
 *
 * 触发条件（v1.1.26 放宽）：不仅限于通用名前缀，
 * 对任何编号模式/无意义名称都尝试内部标题提取。
 */
private fun tryExtractInternalTitle(file: File, currentName: String): String {
    // 只在当前名称是非理想名称时才尝试（避免覆盖真实有意义的文件名）
    val isGenericName = currentName.startsWith("Word文档") ||
        currentName.startsWith("PDF文档") || currentName.startsWith("Excel表格") ||
        currentName.startsWith("PPT演示") || currentName.startsWith("文本文件") ||
        currentName.startsWith("图片") || currentName.startsWith("文档") ||
        currentName.startsWith("压缩包") || currentName.startsWith("CAD图纸")
    // v1.1.26 扩展：编号模式名称也需要尝试提取
    val isNumbered = isNumberedOrGenericName(currentName)
    if (!isGenericName && !isNumbered) return currentName

    val ext = file.extension.lowercase()
    return try {
        if (ext == "docx") {
            // 策略1: docProps/core.xml <dc:title>
            val zip = java.util.zip.ZipFile(file)
            try {
                val entry = zip.getEntry("docProps/core.xml")
                if (entry != null) {
                    val xml = zip.getInputStream(entry).bufferedReader().readText()
                    val titleRe = """<dc:title[^>]*>(.*?)</dc:title>""".toRegex()
                    val title = titleRe.find(xml)?.groupValues?.get(1)?.trim()
                    if (!title.isNullOrBlank() && title.length <= 150 &&
                        !title.any { it.code < 0x20 }) {
                        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                        Diag.d( "DOCX内部标题: '$clean'")
                        return "$clean.docx"
                    }
                }
                // 策略2: DOCX 内容中有意义的文本（扫描所有 w:t，找第一个像标题的）
                // v1.1.19 修复：两轮扫描——优先选CJK标题(第一轮)，跳过编号/代码/标签(第二轮)
                val bodyEntry = zip.getEntry("word/document.xml")
                if (bodyEntry != null) {
                    val bodyXml = zip.getInputStream(bodyEntry).bufferedReader().readText()
                    val tRe = """<w:t[^>]*>(.*?)</w:t>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    // 常见英文标签模式（跳过这些）
                    val labelPattern = Regex("""^(?i)(no|to|from|date|name|subject|cc|bcc|ref|re|page|tel|fax|email|address|id|code|type|copy|total|amount|note|dear|sir|mr|ms|mrs|dr|prof)\s*[:.]?\s*$""")
                    val labelPattern2 = Regex("""^[A-Z][a-z]?[\.:]\s*$""")  // 单/双字母+冒号/点
                    // 编号/代码/ID模式（统一社会信用代码、身份证号、注册号等）
                    val codePattern = Regex("""^[A-Za-z]*[0-9]{6,}.*$""")   // 含6位以上连续数字
                    val codePattern2 = Regex("""^\w{10,}[\)\]】]?$""")       // 长字母数字串+可选右括号
                    var cjkCandidate: String? = null
                    var fallbackCandidate: String? = null
                    tRe.findAll(bodyXml).forEach { tMatch ->
                        val raw = tMatch.groupValues?.get(1)?.trim()
                            ?.replace(Regex("<[^>]+>"), "")?.trim() ?: return@forEach
                        if (raw.isBlank() || raw.length < 2) return@forEach
                        // 跳过纯英文标签
                        if (labelPattern.matches(raw) || labelPattern2.matches(raw)) return@forEach
                        // 跳过编号/代码类文本（身份证号、统一社会信用代码等）
                        if (codePattern.matches(raw) || codePattern2.matches(raw)) return@forEach
                        // 跳过纯数字或以数字开头的长串（页码、日期数字等）
                        if (raw.matches(Regex("""^\d{2,}.*$""")) && raw.length <= 20) return@forEach
                        // 含 CJK → 最佳候选（大概率是中文标题），继续扫描看有没有更长的
                        if (raw.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF ||
                                             it.code in 0xA960..0xA97C || it.code in 0xF900..0xFAFF ||
                                             it.code in 0x3000..0x303F }) {
                            val short = if (raw.length > 25) raw.substring(0, 25) + "…" else raw
                            val clean = short.replace(Regex("[\\\\/:*?\"<>|\n\r\t]"), "_").trim()
                            if (clean.isNotBlank()) {
                                val prevLen = cjkCandidate?.length ?: 0
                                if (raw.length > prevLen) {
                                    cjkCandidate = clean
                                }
                            }
                        } else if (fallbackCandidate == null) {
                            // 纯英文但够长（>=4字符且不含标点结尾的标签式写法）→ 兜底候选
                            if (raw.length >= 4 && !raw.endsWith(":") && !raw.endsWith(".")) {
                                val short = if (raw.length > 25) raw.substring(0, 25) + "…" else raw
                                val clean = short.replace(Regex("[\\\\/:*?\"<>|\n\r\t]"), "_").trim()
                                if (clean.isNotBlank()) fallbackCandidate = clean
                            }
                        }
                    }
                    // 优先用 CJK 标题，其次兜底
                    val chosen = cjkCandidate ?: fallbackCandidate
                    if (chosen != null) {
                        Diag.d( "DOCX有意义的标题文本: '$chosen' (CJK=${
                            cjkCandidate != null}, fallback=${fallbackCandidate != null})")
                        return "$chosen.docx"
                    }
                    Diag.d( "DOCX未找到有意义的标题文本，保留通用名")
                }
            } finally { zip.close() }
        }

        if (ext == "pdf") {
            // 策略3: PDF /Title 元数据
            val bytes = file.inputStream().use { ins ->
                val buf = ByteArray(minOf(65536, file.length().toInt()))
                val total = ins.read(buf)
                if (total == buf.size) buf else buf.copyOf(total)
            }
            extractPdfTitleFromBytes(bytes, bytes.size)?.let { title ->
                val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                if (clean.isNotBlank()) return "$clean.pdf"
            }
        }

        // 策略4: TXT 首行
        if (ext == "txt" || ext == "csv" || ext == "log") {
            file.inputStream().use { ins ->
                val header = ByteArray(minOf(4096, file.length().toInt()))
                val n = ins.read(header)
                if (n > 0) {
                    val firstLine = String(header, 0, n, Charsets.UTF_8)
                        .lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    if (!firstLine.isNullOrBlank() && firstLine.length >= 2) {
                        val short = if (firstLine.length > 25) firstLine.substring(0, 25) + "…" else firstLine
                        val clean = short.replace(Regex("[\\\\/:*?\"<>|\n\r\t]"), "_").trim()
                        if (clean.isNotBlank()) return "$clean.$ext"
                    }
                }
            }
        }

        currentName
    } catch (_: Throwable) {
        currentName
    }
    // v1.1.35: 如果提取后仍然是通用名（如 "PDF文档.pdf"），则生成带序号的友好名
    // 避免多个文件都显示 "PDF文档.pdf" 无法区分
    if (currentName.startsWith("PDF文档") || currentName.startsWith("Word文档") ||
        currentName.startsWith("Excel表格") || currentName.startsWith("PPT演示") ||
        currentName.startsWith("文本文件") || currentName.startsWith("图片") ||
        currentName.startsWith("文档") || currentName.startsWith("压缩包")) {
        // 用文件大小+修改时间的哈希生成短序号，保证同一文件始终同名、不同文件不同名
        val sig = (file.length() xor (file.lastModified() / 1000L)).toInt()
        val shortId = if (sig < 0) -sig else sig
        val base = currentName.substringBeforeLast('.')
        val e = currentName.substringAfterLast('.', "")
        val suffix = if (e.isNotBlank()) ".$e" else ""
        return "${base}_${shortId.toString(36).take(4)}$suffix"
    }
    return currentName
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
        Diag.w( "renderPdfPagesToPngs 打开失败: ${e.message}")
        return emptyList()
    }
    val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Throwable) {
        Diag.w( "renderPdfPagesToPngs 创建 Renderer 失败: ${e.message}")
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
        Diag.d( "renderPdfPagesToPngs: ${pdfFile.name} → ${pngs.size}/$limit 页")
    } catch (e: Throwable) {
        Diag.w( "renderPdfPagesToPngs 异常: ${e.message}")
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

// ════════════════════════════════════════════════════════════════════════════
// v1.9.25: 后台统计架构重构
//   根因：真机日志证明切后台后 Activity 主进程被系统冻结（心跳中断 10 分钟级），
//   即使在主进程内启动前台服务 + WakeLock 仍无效。
//   修复：把实际统计工作搬到独立前台进程 :countservice（CountingService），
//   该进程唯一职责就是持前台优先级 + 唤醒锁地跑统计，不被 Activity 主进程冻结牵连。
//   统计结果追加写入外部缓存 wc_results.jsonl；MainActivity 轮询该文件（切回前台时
//   亦在 ON_START 恢复），按 id 去重并入 entries。主进程 inline 回退与独立进程服务
//   共用同一份 processBatchToEntries 逻辑，统计口径完全一致。
// ════════════════════════════════════════════════════════════════════════════

/** 当前用于结果恢复的 sink（去重后写入 entries）。进程重建后由 ON_START 恢复时复用。 */
private var currentEntriesSink: ((FileEntry) -> Unit)? = null
/** 当前 entries 列表引用（进程重建后由 Composable 重新赋值），供 ON_START 恢复兜底。 */
private var currentEntries: androidx.compose.runtime.snapshots.SnapshotStateList<FileEntry>? = null

/** recoverResults 的跨调用状态：增量读取偏移与进度去重。 */
private object RecoverState {
    @Volatile var lastOffset: Long = 0L
    @Volatile var lastProgressKey: String = ""
    @Volatile var lastProgressDone: Int = -1
}
/**
 * 从内部缓存 wc_results.jsonl 恢复已统计结果（在主进程冻结期间服务仍持续写入）。
 * v1.9.30: 真正增量读取——记录上次读到的文件偏移，每次只消费新增行；
 * 同时给 progress 做 (name,total,done) 去重，避免每次轮询把历史进度重新触发一遍，
 * 造成 App 内进度条/通知栏进度乱跳。
 * CountingService 用 O_APPEND atomic 写入，本进程即使每 1.5s 轮询也不会读到半行。
 * 返回是否见到 BATCH_END（本批完成）。
 */
private fun recoverResults(
    context: android.content.Context,
    sink: (FileEntry) -> Unit,
    onProgress: ((String, Int, Int) -> Unit)? = null
): Boolean {
    return try {
        val dir = context.cacheDir ?: return false
        val f = java.io.File(dir, "wc_results.jsonl")
        if (!f.exists()) { RecoverState.lastOffset = 0L; return false }
        val len = f.length()
        // 文件被重建（新批次开始/用户删除）时重置偏移，避免读到旧内容或跳过新内容。
        if (len < RecoverState.lastOffset) {
            RecoverState.lastOffset = 0L
            RecoverState.lastProgressKey = ""
            RecoverState.lastProgressDone = -1
        }
        if (len == RecoverState.lastOffset) return false
        var sawEnd = false
        java.io.FileInputStream(f).use { fis ->
            val skip = fis.skip(RecoverState.lastOffset)
            if (skip != RecoverState.lastOffset) {
                RecoverState.lastOffset = 0L
                return false
            }
            java.io.BufferedReader(java.io.InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        val o = org.json.JSONObject(line)
                        if (o.optString("type", "") == "batch_end") { sawEnd = true; continue }
                        if (o.optString("type", "") == "progress") {
                            val name = o.optString("name", "")
                            val done = o.optInt("done", 0)
                            val total = o.optInt("total", 0)
                            val key = "$name|$total"
                            // 只接受同一文件+总数的递增 done，过滤历史重复 progress。
                            if (key != RecoverState.lastProgressKey || done > RecoverState.lastProgressDone) {
                                RecoverState.lastProgressKey = key
                                RecoverState.lastProgressDone = done
                                onProgress?.invoke(name, done, total)
                            }
                            continue
                        }
                        val id = o.getString("id")
                        val displayName = o.getString("displayName")
                        val cachePath = o.getString("cachePath")
                        val entry = if (o.has("error") && !o.isNull("error")) {
                            FileEntry(id = id, displayName = displayName, cachePath = cachePath, error = o.getString("error"))
                        } else {
                            val rr = if (o.has("rawResultJson") && !o.isNull("rawResultJson")) {
                                val s = o.getString("rawResultJson")
                                if (s == "null") null else jsonToMap(org.json.JSONObject(s))
                            } else null
                            FileEntry(id = id, displayName = displayName, cachePath = cachePath,
                                result = toFileResult(rr, cachePath), rawResult = rr)
                        }
                        sink(entry)
                    } catch (_: Throwable) {}
                }
            }
        }
        RecoverState.lastOffset = len
        sawEnd
    } catch (_: Throwable) { false }
}

private fun finalizeBatch(
    context: android.content.Context,
    heartbeatJob: kotlinx.coroutines.Job,
    busySet: (Boolean) -> Unit,
    onProgress: ((String, Int, Int) -> Unit)?
) {
    try { heartbeatJob.cancel() } catch (_: Throwable) {}
    busySet(false)
    onProgress?.invoke("", 0, 0)
    WordCountForegroundService.stop(context)
    DwgIsolatedRunner.stopIsolated(context)
}

private fun addFiles(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    entries: androidx.compose.runtime.snapshots.SnapshotStateList<FileEntry>,
    busyRef: () -> Boolean,
    busySet: (Boolean) -> Unit,
    uris: List<Uri>,
    onProgress: ((String, Int, Int) -> Unit)? = null
) {
    if (busyRef()) return
    // v1.9.26: 清掉上轮 wc_results.jsonl，避免新旧批混合（旧批的 _arch 行会被本批误恢复）。
    try {
        val dir = context.cacheDir
        java.io.File(dir, "wc_results.jsonl").delete()
        java.io.File(dir, "wc_results.jsonl.tmp").delete()
    } catch (_: Throwable) {}
    val sink: (FileEntry) -> Unit = { e ->
        // v1.9.27: sink 在 IO 线程被调（recoverResults 走 appScope.launch(IO)），
        // SnapshotStateList.add 必须在 Main 线程才触发 Compose UI 重组，
        // 否则只更新状态值不刷 UI——这就是"log HEARTBEAT 在打、list 不动"的根因。
        scope.launch(Dispatchers.Main) {
            // v1.9.66: same-id 替换，支持压缩包聚合条目实时刷新。
            val idx = entries.indexOfFirst { it.id == e.id }
            if (idx >= 0) entries[idx] = e else entries.add(e)
        }
    }
    // v1.9.29: 所有进度回调统一包到 Main 线程，避免 IO 线程改 mutableState 导致 UI 不刷新。
    val mainProgress: (String, Int, Int) -> Unit = { name, done, total ->
        scope.launch(Dispatchers.Main) { onProgress?.invoke(name, done, total) }
    }
    currentEntriesSink = sink
    scope.launch(Dispatchers.Main) {
        busySet(true)
        cachedFileCounter = 0
        val heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && busyRef()) {
                delay(5000L)
                logStatsLine(context, "HEARTBEAT", 0, 0)
            }
        }
        logStatsLine(context, "BATCH_START files=${uris.size}", 0, 0)

        val runInline: suspend () -> Unit = {
            try { PythonEngine.start(context) } catch (_: Throwable) {}
            val cf = uris.map { copyUriToCache(context, it) }
            MainActivity.pendingUriNames.clear()
            processBatchToEntries(context, cf,
                onProgress = mainProgress,
                // v1.9.66: same-id 替换，支持压缩包聚合条目边统计边更新。
                emit = { e -> scope.launch(Dispatchers.Main) {
                    val idx = entries.indexOfFirst { it.id == e.id }
                    if (idx >= 0) entries[idx] = e else entries.add(e)
                } },
                onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
            finalizeBatch(context, heartbeatJob, busySet, mainProgress)
        }

        try {
            val cf = uris.map { copyUriToCache(context, it) }
            MainActivity.pendingUriNames.clear()
            val started = CountingService.startBatch(context, cf.map { it.file.absolutePath }, cf.map { it.displayName })
            if (!started) {
                Diag.w( "CountingService 启动失败，回退本进程 inline 统计")
                runInline()
            } else {
                // 正常路径：结果由 CountingService 写入 wc_results.jsonl。
                // 主进程轮询该文件恢复结果；看门狗兜底：45 分钟内未见到 BATCH_END 则强制收尾。
                recoverPollingJob = scope.launch {
                    var done = false
                    var elapsed = 0L
                    // v1.9.88: 看门狗超时 120→45 分钟。DWG 批内已有 beginBatch 的 40 分钟硬预算
                    // （动态分配 + Kotlin 流式快路径保证每个文件出数），45 分钟看门狗仅兜底
                    // 「批次里的非 DWG 文件 + DWG 转换/收尾」余量，不再作为 DWG 慢的遮羞布。
                    while (isActive && !done && elapsed < 45 * 60 * 1000L) {
                        // v1.9.55: 轮询间隔 1500ms → 500ms，让主界面进度更贴近通知栏进度。
                        delay(500L)
                        elapsed += 500L
                        if (recoverResults(context, sink, mainProgress)) done = true
                    }
                    if (done) {
                        finalizeBatch(context, heartbeatJob, busySet, mainProgress)
                    } else {
                        Diag.w( "统计看门狗超时，强制收尾并恢复已产出结果")
                        recoverResults(context, sink, mainProgress)
                        finalizeBatch(context, heartbeatJob, busySet, mainProgress)
                    }
                }
            }
        } catch (e: Throwable) {
            Diag.e( "addFiles 异常，回退 inline: ${e.message}", e)
            try { runInline() } catch (_: Throwable) {}
        }
    }
}

/**
 * v1.9.62: 批次统计控制（暂停 / 继续 / 停止）。
 *
 * 统计跑在 :countservice 独立进程，主进程改不了它的内存，因此由 CountingService 通过
 * Intent action 改这里的标志位；统计循环在「每个文件开始处」过闸门：
 *   - 暂停：闸门内自旋等待（不退出、不杀服务），通知栏显示"已暂停"，已统计条目全部保留；
 *   - 继续：清标志，循环立即接着跑剩下未统计的文件；
 *   - 停止：清标志并置 stopped，循环 return，服务收尾并取消通知。
 */
class BatchControl {
    @Volatile var paused: Boolean = false
    @Volatile var stopped: Boolean = false

    /** 协程内闸门（DWG 流水线用，挂起不占线程）。 */
    suspend fun waitIfPaused() {
        while (paused && !stopped) kotlinx.coroutines.delay(300L)
    }

    /**
     * 阻塞闸门（非挂起的 forEachIndexed 循环用；这些循环本就跑在 IO 线程做阻塞 I/O）。
     * 暂停时自旋等待；返回 false 表示"已停止"，调用方应 `return@forEachIndexed` 跳过本文件
     * （后续文件同样会在闸门处被跳过，等价于停止整批）。
     */
    fun gateBlocking(): Boolean {
        while (paused && !stopped) {
            try { Thread.sleep(300L) } catch (_: Throwable) { return !stopped }
        }
        return !stopped
    }
}

/**
 * v1.9.62: DWG 批量「转换 ↔ OCR」流水线。
 *
 * 旧行为（串行）：转换(隔离进程) → 解析+OCR → 下一份 —— OCR 期间转换进程全程空闲。
 * 新行为：生产者只做「阶段A 转换」，把 DXF 丢进容量 1 的有界 Channel 后立刻去转换下一份；
 *        消费者单线程做「阶段B 解析 + OLE/IMAGE OCR + 计数」。
 *        于是「第 N+1 份的转换」与「第 N 份的 OCR」重叠，OCR 密集批次可省 30%~45%。
 *
 * 三大硬约束均未被破坏：
 *   ① PaddleOCR 是 object 单例、native 非线程安全 → 阶段B 只有一个消费者协程，天然串行；
 *   ② LibreDWG 全局状态污染、:dwgisolated 每文件 stopSelf → 阶段A 仍严格串行，
 *      同一时刻只有一个转换进程，绝不并发复用；
 *   ③ Chaquopy 单解释器 → PythonEngine 只在阶段B 被调用，与阶段A 无交叠。
 */
internal suspend fun processDwgPipelined(
    context: android.content.Context,
    dwgFiles: List<CachedFile>,
    control: BatchControl,
    onProgress: (name: String, done: Int, total: Int) -> Unit,
    onResult: (i: Int, f: java.io.File, dName: String, res: DwgProcessor.DwgProcessResult) -> Unit,
    onError: (i: Int, f: java.io.File, dName: String, msg: String?) -> Unit
) {
    if (dwgFiles.isEmpty()) return
    val total = dwgFiles.size
    if (total == 1) {
        // 单文件无需重叠，走原路径（与旧版行为完全一致）
        val cf = dwgFiles[0]
        control.waitIfPaused()
        if (!control.stopped) {
            try {
                val res = DwgProcessor.process(context, cf.file, cf.displayName)
                onResult(0, cf.file, cf.displayName, res)
            } catch (e: Throwable) {
                onError(0, cf.file, cf.displayName, e.message)
            }
        }
        return
    }
    // v1.9.88: 批量预算——40 分钟硬约束从「本批第一个 DWG 开始转换」起算（转换也占用预算），
    // 覆盖整批所有 DWG；消费者每完成一个文件 endFile() 配平，perFileBudgetMs 据此动态收紧。
    DwgProcessor.beginBatch(total)
    try {
        kotlinx.coroutines.coroutineScope {
            val channel = kotlinx.coroutines.channels.Channel<Pair<Int, DwgProcessor.DwgConvertOutcome?>>(capacity = 1)
            // 生产者：阶段A 转换（串行，独占 :dwgisolated）
            launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    for ((idx, cf) in dwgFiles.withIndex()) {
                        control.waitIfPaused()
                        if (control.stopped) break
                        val conv = try {
                            DwgProcessor.convertPhase(context, cf.file)
                        } catch (e: Throwable) {
                            null
                        }
                        channel.send(idx to conv)
                    }
                } finally {
                    try { channel.close() } catch (_: Throwable) {}
                }
            }
            // 消费者：阶段B 解析 + OCR + 计数（单线程串行，保护 PaddleOCR 单例与 Chaquopy）
            launch(kotlinx.coroutines.Dispatchers.IO) {
                var done = 0
                // 用 receiveCatching 显式取元素（避免依赖 ReceiveChannel.iterator 扩展的 import）
                while (true) {
                    val rc = channel.receiveCatching()
                    if (rc.isClosed) break
                    val item = rc.getOrNull() ?: continue
                    val idx = item.first
                    val conv = item.second
                    control.waitIfPaused()
                    if (control.stopped) break
                    val cf = dwgFiles[idx]
                    try {
                        val res = DwgProcessor.analyzePhase(
                            context, cf.file, cf.displayName,
                            conv?.dxfPath, conv?.diagnostics ?: ""
                        )
                        onResult(idx, cf.file, cf.displayName, res)
                    } catch (e: Throwable) {
                        onError(idx, cf.file, cf.displayName, e.message)
                    } finally {
                        // v1.9.95: 删除本文件转换出的中间 DXF（单份可达 200MB+，此前从不删除，
                        // 一天多次测同一 RAR 会累积几十份堆在 cacheDir 根目录且清理功能匹配不到）
                        DwgProcessor.deleteIntermediateDxf(conv?.dxfPath)
                        // v1.9.88: 无论成功/失败都配平预算计数，保证后续文件预算计算准确
                        DwgProcessor.endFile()
                    }
                    done++
                    try { onProgress(cf.displayName, done, total) } catch (_: Throwable) {}
                }
            }
        }
    } catch (e: Throwable) {
        Diag.w( "DWG 流水线异常，回退串行: ${e.message}")
        dwgFiles.forEachIndexed { i, cf ->
            if (!control.gateBlocking()) return@forEachIndexed
                        try {
                val res = DwgProcessor.process(context, cf.file, cf.displayName)
                onResult(i, cf.file, cf.displayName, res)
            } catch (ex: Throwable) {
                onError(i, cf.file, cf.displayName, ex.message)
            } finally {
                // v1.9.88: 串行回退也要配平预算计数
                DwgProcessor.endFile()
            }
        }
    }
}

// v1.9.25: 把原 addFiles 内联统计逻辑抽成独立挂起函数，供 MainActivity（inline 回退）
// 与 CountingService（:countservice 独立前台进程）共用，确保两端统计口径一致。
internal suspend fun processBatchToEntries(
    context: android.content.Context,
    cachedFiles: List<CachedFile>,
    onProgress: (name: String, done: Int, total: Int) -> Unit,
    emit: (FileEntry) -> Unit,
    onError: (String) -> Unit,
    control: BatchControl = BatchControl()
) {
    try {
                val pyStartResult = runCatching { PythonEngine.start(context) }
                Diag.d( "PythonEngine.start: ${if (pyStartResult.isSuccess) "OK" else "FAIL: ${pyStartResult.exceptionOrNull()?.message}"}")
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
                    if (!control.gateBlocking()) return@forEachIndexed
                                        try {
                                                            // v1.9.68: 压缩包先 emit 骨架（所有内层文件名列出），每统计完一个替换对应占位，
                                                            // 暂停时也能看到全部文件名和已统计结果，与电脑版行为一致。
                                                            val archiveId = "arch::${f.absolutePath}"
                                                            val archiveExt = f.extension.lowercase().let { if (it.isBlank()) "" else ".${it}" }
                                                            val archiveInner = mutableListOf<InnerResult>()
                                                            fun buildEntry(final: Boolean): FileEntry {
                                                                val innerMaps = archiveInner.map { ir ->
                                                                    mapOf(
                                                                        "name" to ir.name,
                                                                        "stats" to mapOf("words" to ir.words, "fe" to ir.fe, "nc" to ir.nc, "chars" to ir.chars),
                                                                        "meta" to mapOf("pages" to ir.pages, "needs_pdf" to ir.needsPdf)
                                                                    )
                                                                }
                                                                val doneInner = archiveInner.filter { it.done }
                                                                val totalWords = doneInner.sumOf { it.words }
                                                                val totalFe = doneInner.sumOf { it.fe }
                                                                val totalNc = doneInner.sumOf { it.nc }
                                                                val totalChars = doneInner.sumOf { it.chars }
                                                                val totalPages = doneInner.sumOf { it.pages ?: estimatePages(it.chars) }
                                                                val aggResMap = mapOf(
                                                                    "name" to dName,
                                                                    "ext" to archiveExt,
                                                                    "is_archive" to true,
                                                                    "stats" to mapOf("words" to totalWords, "fe" to totalFe, "nc" to totalNc, "chars" to totalChars),
                                                                    "meta" to mapOf("inner" to innerMaps, "needs_pdf" to archiveInner.any { it.needsPdf && it.done }),
                                                                    "pages" to totalPages
                                                                )
                                                                val fr = toFileResult(aggResMap, f.absolutePath)
                                                                return FileEntry(id = archiveId, displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = aggResMap)
                                                            }
                                                            val res = ArchiveEngine.extract(f, context.cacheDir, context,
                                                                onProgress = archProg@{ done, total ->
                                                                    if (!control.gateBlocking()) return@archProg
                                                                    onProgress(dName, done, total)
                                                                },
                                                                gate = { control.gateBlocking() },
                                                                onEntries = { names ->
                                                                    archiveInner.addAll(names.map { full ->
                                                                        InnerResult(name = full.substringAfterLast('/'), words = 0, fe = 0, nc = 0, chars = 0, pages = null, needsPdf = false, done = false)
                                                                    })
                                                                    emit(buildEntry(final = false))
                                                                },
                                                                onInner = { inner ->
                                                                    // v1.9.77 FIX：优先匹配未完成的同名占位；若已全部完成（如重试/替换场景）则回退匹配
                                                                    // 任意同名项做更新，杜绝同一文件出现两条进度（v1.9.76 偶有"双 0 进度"回归）。
                                                                    val idx = archiveInner.indexOfFirst { it.name == inner.name && !it.done }.let { if (it < 0) archiveInner.indexOfFirst { it.name == inner.name } else it }
                                                                    if (idx >= 0) {
                                                                        archiveInner[idx] = InnerResult(name = inner.name, words = inner.words, fe = inner.fe, nc = inner.nc, chars = inner.chars, pages = inner.pages, needsPdf = inner.needsPdf, done = true)
                                                                    } else {
                                                                        archiveInner.add(InnerResult(name = inner.name, words = inner.words, fe = inner.fe, nc = inner.nc, chars = inner.chars, pages = inner.pages, needsPdf = inner.needsPdf, done = true))
                                                                    }
                                                                    emit(buildEntry(final = false))
                                                                }
                                                            )
                                                            if (res == null) {
                                                                val ext = f.extension.lowercase()
                                                                val isSupported = ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz")
                                                                val errMsg = if (isSupported) {
                                                                    if (ext == "rar")
                                                                        "RAR 解析失败（文件可能损坏、密码保护或为空）"
                                                                    else
                                                                        "压缩包解析失败（文件可能损坏或密码保护）"
                                                                } else
                                                                    "暂不支持此格式（.${ext}）。支持：ZIP / RAR4 / 7Z / TAR / GZ"
                                                                emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_arch", displayName = dName, cachePath = f.absolutePath,
                                                                    error = errMsg))
                                                            } else {
                                                                emit(buildEntry(final = true))
                                                            }
                    } catch (e: Throwable) {
                        Diag.w( "压缩包解析失败 ${f.name}: ${e.message}")
                        emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_arch", displayName = dName, cachePath = f.absolutePath, error = "压缩包解析失败（${e.message}）"))
                    }
                }

                // OOXML (docx/xlsx/pptx) → 纯 Kotlin 解析（不再经过 Python，规避设备端 Chaquopy 失败）
                ooxmlFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    if (!control.gateBlocking()) return@forEachIndexed
                                        try {
                        val res = OoXmlEngine.extract(f)
                        if (res == null) {
                            emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = dName, cachePath = f.absolutePath, error = "无法解析此 OOXML 文件（可能损坏或非标准格式）"))
                        } else {
                            val stats = countTextKotlin(res.text)
                            // v1.3.89 metaWords 安全网（修复 VML 文本框双写导致翻倍的反案例）：
                            //   v1.3.4 因调查问卷元数据(Words=1089)过期决定一律现算、不用 metaWords。
                            //   但营业执照类 WPS 文件每个文本框同时存 DrawingML + VML 两份，
                            //   现算把 v:textbox 内嵌的 p/r/t 也提取了 → 690 词 vs Word 真值 175。
                            //   策略：metaWords > 0 且 现算 > 1.5×metaWords 时，判定为重复/膨胀，
                            //         优先用 metaWords（Word/WPS 自带统计最权威），fe/nc 按比例分配。
                            //   否则保持现算（覆盖 v1.3.4 的元数据过期场景）。
                            val rawWords = stats.first
                            val rawFe = stats.second
                            val rawNc = stats.third
                            val rawChars = stats.fourth
                            // v1.3.98: 恢复 metaWords 安全网（智能模式）。
                            // 背景：v1.3.93 因"含 VML 文本框的中文营业执照 metaWords 偏低"而一刀切废弃。
                            // 但对无 VML 的普通 docx（如纯英文翻译件），metaWords 与 Word 对话框完全一致，
                            // 弃用后现算值因 fallback 补充扫描/子串去重不完美导致偏多（175→439）。
                            // 策略：
                            //   ① 无 VML 且 metaWords > 0 → 直接用 metaWords（= Word 真值）
                            //   ② 有 VML 但现算值 > 1.5×metaWords → 用 metaWords（判定为膨胀）
                            //   ③ 其他情况 → 保持现算（覆盖元数据过期/文本框额外内容场景）
                            val outWords: Int
                            val outFe: Int
                            val outNc: Int
                            val outChars: Int
                            if (res.metaWords > 0 && !res.hasVml) {
                                // 无 VML 文本框：metaWords = Word 对话框字数，最权威
                                outWords = res.metaWords
                                val ratio = if (rawWords > 0) rawWords.toDouble() / res.metaWords else 1.0
                                outFe = (rawFe / ratio).toInt().coerceAtLeast(0)
                                outNc = (rawNc / ratio).toInt().coerceAtLeast(0)
                                outChars = (rawChars / ratio).toInt().coerceAtLeast(0)
                                Diag.d( "docx: 使用 metaWords=${res.metaWords}(无VML权威值) 现算=$rawWords")
                            } else if (res.metaWords > 0 && rawWords > (res.metaWords * 1.5).toInt()) {
                                // 有 VML 但现算明显膨胀：回退到 metaWords
                                outWords = res.metaWords
                                val ratio = rawWords.toDouble() / res.metaWords
                                outFe = (rawFe / ratio).toInt().coerceAtLeast(0)
                                outNc = (rawNc / ratio).toInt().coerceAtLeast(0)
                                outChars = (rawChars / ratio).toInt().coerceAtLeast(0)
                                Diag.d( "docx: 回退 metaWords=${res.metaWords}(现算${rawWords}膨胀>1.5x)")
                            } else {
                                // 默认：用现算值
                                outWords = rawWords
                                outFe = rawFe
                                outNc = rawNc
                                outChars = rawChars
                                Diag.d( "docx: 现算=($rawWords,$rawFe,$rawNc,$rawChars) metaWords=${res.metaWords}")
                            }
                            val outPages = if (res.metaPages > 0) res.metaPages else res.pages
                            val outReason = if (res.pagesReason.isNotBlank()) res.pagesReason else null
                            Diag.d( "docx: 现算=($rawWords,$rawFe,$rawNc,$rawChars) metaWords=${res.metaWords}(不使用) 输出=($outWords,$outFe,$outNc,$outChars) pages=$outPages")
                            // v1.3.3: 隐藏工作表单独统计（默认不计入合计，UI 勾选后才并入）
                            val hiddenStats = res.hiddenSheets.map { (n, t) ->
                                val s = countTextKotlin(t)
                                SheetStat(n, s.first, s.second, s.third, s.fourth)
                            }
                            // v1.3.32: PPT 备注幻灯片单独统计（默认不计入合计，UI 勾选后才并入）
                            val notesStats = res.notesSlides.map { (n, t) ->
                                val s = countTextKotlin(t)
                                SheetStat(n, s.first, s.second, s.third, s.fourth)
                            }
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".${f.extension.lowercase()}",
                                "stats" to mapOf("words" to outWords, "fe" to outFe, "nc" to outNc, "chars" to outChars),
                                "meta" to mapOf("sheets" to res.sheets, "hidden_sheets" to hiddenStats,
                                    "notes_slides" to notesStats, "image_count" to res.imageCount,
                                    "internal_title" to res.internalTitle),
                                "pages" to outPages,
                                "pages_reason" to outReason
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            // v1.3.32: 如果 URI 无法获取真实文件名（显示为"PPT演示_A04D.pptx"等生成名），
                            //   尝试用 OOXML 内部标题（docProps/core.xml <dc:title>）替换
                            val finalDisplayName = if (res.internalTitle.isNotBlank() &&
                                (dName.startsWith("PPT演示_") || dName.startsWith("Word文档_") ||
                                 dName.startsWith("Excel表格_") || dName.startsWith("PDF文档_") ||
                                 dName.startsWith("文档_") || dName.startsWith("文本文件_") ||
                                 looksLikeHashString(dName) || isSuspiciousFilename(dName))) {
                                val ext = f.extension.lowercase()
                                "${res.internalTitle}.${if (ext.isNotBlank()) ext else "file"}"
                            } else dName
                            emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = finalDisplayName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Diag.w( "OOXML 解析失败 ${f.name}: ${e.message}")
                        emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_oo", displayName = dName, cachePath = f.absolutePath, error = "OOXML 解析失败（${e.message}）"))
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
                    if (!control.gateBlocking()) return@forEachIndexed
                                        try {
                        // ── Level 1: Kotlin PdfExtractor（快速预筛）──
                        val ktRes = PdfExtractor.extract(f)
                        val ktStats = countTextKotlin(ktRes.text)
                        Diag.d( "PDF Level1(Kotlin) $dName: chars=${ktStats.fourth} words=${ktStats.first} reliable=${ktRes.reliable} pages=${ktRes.pages}")
                        // v1.5.66: 用系统 PdfRenderer 取可靠页数（Kotlin 的 countPagesSafe 对压缩流 PDF 会误判成 1 页）
                        val realPages = reliablePdfPageCount(f)
                        Diag.d( "PDF 可靠页数 $dName: realPages=$realPages (ktPages=${ktRes.pages})")
                        // v1.9.60: 与 FileProcessor 对齐——高密度可靠文字层直接秒出，跳过 Python pdfminer/OCR。
                        val denomPagesFast = if (realPages > 1) realPages else 1
                        val ktCharsPerPage = ktStats.fourth.toDouble() / denomPagesFast
                        val suspiciousLowFe = ktStats.second > 0 && ktStats.second < 30
                        val pureNonCjkFast = ktStats.second == 0 && ktStats.third >= 500 && ktCharsPerPage >= 200.0
                        val normalFast = ktRes.reliable && ktStats.fourth >= 500 && ktCharsPerPage >= 200.0 && !suspiciousLowFe
                        val anyReliableFast = ktRes.reliable && ktStats.fourth >= 1000 && ktCharsPerPage >= 100.0
                        if (normalFast || pureNonCjkFast || anyReliableFast) {
                            val pdfDiag = buildString {
                                appendLine("【PDF诊断】Kotlin快速路径：${ktStats.fourth}字(fe=${ktStats.second},nc=${ktStats.third})/${denomPagesFast}页，跳过Python/OCR")
                                appendLine("KT内部: ${ktRes.diag}")
                            }.trimEnd()
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".pdf",
                                "stats" to mapOf("words" to ktStats.first, "fe" to ktStats.second, "nc" to ktStats.third, "chars" to ktStats.fourth),
                                "meta" to emptyMap<String, Any?>(),
                                "pages" to denomPagesFast,
                                "diag" to pdfDiag,
                                "ocrNote" to "文本提取充分，未触发OCR"
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_fast", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                            return@forEachIndexed
                        }
                        // ── Level 2: Python pdfminer（文字型 PDF 的主力）──
                        var pyWords = 0; var pyFe = 0; var pyNc = 0; var pyChars = 0; var pyPages = 0
                        var pyOk = false
                        var pyError: String? = null
                        // v1.3.63: 先测试 Python 引擎是否正常工作
                        // v1.3.64: 将诊断结果存到变量，最终拼进界面显示
                        var pyDiag: String? = null
                        try {
                            pyDiag = PythonEngine.testPython(context)
                            Diag.d( "PDF Python诊断 $dName: $pyDiag")
                        } catch (e: Throwable) {
                            pyDiag = "Python诊断异常: ${e.javaClass.simpleName}: ${e.message}"
                            Diag.w( "PDF Python诊断失败 $dName: $pyDiag")
                        }
                        try {
                            val pyResults = PythonEngine.countFiles(context, listOf(f.absolutePath))
                            @Suppress("UNCHECKED_CAST")
                            val pyList = pyResults as? List<Map<String, Any?>>
                            Diag.d( "PDF Level2(Python) $dName: raw=$pyResults")
                            if (!pyList.isNullOrEmpty()) {
                                val py0 = pyList[0]
                                Diag.d( "PDF Level2 $dName: py0_ok=${py0["ok"]} keys=${py0.keys}")
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
                                    } else {
                                        Diag.w( "PDF Level2 $dName: pyData为null, raw result=${py0["result"]}")
                                    }
                                } else {
                                    pyError = py0["error"]?.toString()
                                    Diag.w( "PDF Level2 $dName: Python返回ok=false, error=$pyError")
                                }
                            } else {
                                Diag.w( "PDF Level2 $dName: pyList为空或null")
                            }
                        } catch (e: Throwable) {
                            Diag.w( "PDF Python pdfminer 异常: $dName - ${e.javaClass.simpleName}: ${e.message}")
                        }

                        Diag.d( "PDF $dName → KT:${ktStats.fourth}ch(fe=${ktStats.second}) PY:${pyChars}ch(fe=$pyFe)(pyOk=$pyOk) KT_rel=${ktRes.reliable}")

                        // ── 决策：选 Kotlin 或 Python 的较好结果 ──
                        //   pdfminer 通常更准确（处理了 ToUnicode CMap / ObjStm 等）
                        //
                        //   v1.3.53 修复：Kotlin PdfExtractor 对 Identity-H / 多字节 CID 编码的中文 PDF
                        //   会产生大量 Latin-1 乱码（fe=0 但 char 数虚高）。此时不能单纯比较 char 数，
                        //   而应检测 Kotlin 结果是否为"假阳性"（fe=0 + 大量 nc）。
                        //
                        //   选择逻辑：
                        //     a) Kotlin 结果像 CID 乱码（fe=0 且 chars>100）→ 只要 Python 成功就用 Python
                        //     b) 否则 → Python chars 更多时用 Python（原有逻辑）
                        val ktLooksLikeCidGarbage = ktStats.fourth > 100 && ktStats.second == 0
                                                && ktStats.third > ktStats.fourth * 0.5
                        val usePython = pyOk && (pyChars > ktStats.fourth || ktLooksLikeCidGarbage)
                        Diag.d( "PDF决策 $dName: pyOk=$pyOk pyChars=$pyChars ktChars=${ktStats.fourth} usePython=$usePython cidGarbage=$ktLooksLikeCidGarbage pyError=$pyError")

                        // v1.3.66: 拼出可直接显示到界面的诊断信息（含 PdfExtractor 内部诊断）
                        var pdfDiag = buildString {
                            appendLine("【PDF诊断】")
                            appendLine("Python测试: ${pyDiag ?: "(未执行)"}")
                            appendLine("Kotlin提取: ${ktStats.fourth}字(fe=${ktStats.second},可靠=${ktRes.reliable})")
                            if (ktRes.diag.isNotEmpty()) appendLine("KT内部: ${ktRes.diag}")
                            appendLine("Python提取: ${if (pyOk) "${pyChars}字(fe=$pyFe)" else "失败"}")
                            if (!pyOk && pyError != null) appendLine("Python错误: $pyError")
                            appendLine("决策: ${if (usePython) "用Python" else "用Kotlin"}(pyOk=$pyOk)")
                        }.trimEnd()

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
                        // v1.3.92: 有字符但零中文 → CID/ToUnicode 解码失败的中文 PDF（如 Word 导出 PDF）
                        // 此类 PDF 的中文以 CID 编码存储，Kotlin 无法解码成 PUA/乱码被过滤后只剩英文碎片
                        val isFailedChinesePdf = bestChars > 20 && bestFe == 0 && bestChars < 500
                        // v1.5.86: 英文/图片型 PDF 的 hex/CID 数据常被误解码为大量 CJK，抬升 bestChars
                        // 导致跳过 OCR。真中文常用字占比 >=0.20；伪中文常 <0.10，强制 OCR。
                        val commonCjkCount = ktRes.text.count { it.code in DwgRawCjkScanner.COMMON_CJK_CHARS }
                        val cjkCommonRatio = if (bestFe > 0) commonCjkCount.toDouble() / bestFe else 1.0
                        val cjkLooksLikeCidGarbage = !usePython && bestFe > 50 && cjkCommonRatio < 0.10
                        // v1.5.68: 对齐桌面 extract_pdf 的 whole_poisoned 逻辑 —— 低字数密度（图片型/扫描件 PDF）
                        //   即使 pdfminer/PdfExtractor 已抽到少量文字，也必须强制全页 OCR。
                        //   桌面判定 avg_chars < 800 即 whole_poisoned。
                        //   注意：PdfExtractor 可能抽出大量 PDF 结构/CID 垃圾字符，导致 bestChars 虚高而
                        //   有效字数(bestWords) 极少，因此密度判断必须同时看有效字数，并使用可靠页数 realPages。
                        //   例：AH+.pdf 纯图片型 avg≈29 < 800；正确 27 页文件有效字数 315/27≈12 < 200。
                        val avgCharsPerPage = bestChars.toDouble() / maxOf(1, realPages)
                        val avgWordsPerPage = bestWords.toDouble() / maxOf(1, realPages)
                        val lowDensity = avgCharsPerPage < 800.0 || avgWordsPerPage < 200.0
                        val needOcr = bestChars < 10 || (!bestTextReliable && bestChars < 50) || looksLikeGarbage || isFailedChinesePdf || lowDensity || cjkLooksLikeCidGarbage
                        Diag.d( "PDF OCR决策 $dName: bestChars=$bestChars bestFe=$bestFe bestPages=$bestPages realPages=$realPages avgChars/p=$avgCharsPerPage avgWords/p=$avgWordsPerPage lowDensity=$lowDensity needOcr=$needOcr (garbage=$looksLikeGarbage failedCn=$isFailedChinesePdf cidGarbage=$cjkLooksLikeCidGarbage)")
                        if (lowDensity) pdfDiag += "\nOCR触发: 低字数密度(avg ${"%.0f".format(avgWordsPerPage)}字/页<200)→按桌面口径强制全页OCR"
                        if (cjkLooksLikeCidGarbage) pdfDiag += "\nOCR触发: CJK常用字占比过低(${"%.2f".format(cjkCommonRatio)})，疑似CID/hex伪中文"

                        if (!needOcr) {
                            // ★ 文本提取足够好 → 直接使用
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".pdf",
                                "stats" to mapOf("words" to bestWords, "fe" to bestFe, "nc" to bestNc, "chars" to bestChars),
                                "meta" to emptyMap<String, Any?>(),
                                "pages" to (if (realPages > 1) realPages else bestPages),
                                "diag" to pdfDiag,
                                "ocrNote" to "文本提取充分，未触发OCR"
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_ok", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        } else {
                            // ★ 文本太少 → 尝试 OCR
                            // v1.3.81: 对"glyph-ID编码垃圾"(ktLooksLikeCidGarbage)使用PRINT模式+2x分辨率渲染
                            // 提升中文 PDF 的 OCR 识别率（普通 DISPLAY 模式对文字偏小的 PDF 渲染质量不足）
                            // v1.3.93: isFailedChinesePdf（Word 导出中文 PDF，CID 解码失败）也用 PRINT 高分辨率
                            // v1.5.66: lowDensity(图片/扫描型PDF) 不再强制 PRINT 模式——
                            //   部分 PDF 在 RENDER_MODE_FOR_PRINT 下会渲染成空白(isBlankBitmap 把整页
                            //   跳过→OCR返回空→降级到 Kotlin 的错结果/错误页数)。改用 DISPLAY 模式
                            //   (更兼容, 文本/图片 PDF 均可靠渲染)，仅保留 looksLikeGarbage/isFailedChinesePdf
                            //   的 PRINT 高分辨率(这两类确需更清晰渲染)。
                            val ocrForPrintMode = looksLikeGarbage || isFailedChinesePdf
                            val ocrRes = PdfOcrEngine.extractText(context, f, forPrintMode = ocrForPrintMode, onProgress = { done, total ->
                                onProgress(dName, done, total)
                            })

                            if (ocrRes != null) {
                                // v1.9.52: 对齐桌面版 extract_pdf 的 whole_poisoned 口径——触发 OCR 分支说明
                                // 该 PDF 是图纸类/图片型/文字层污染，应以整页 OCR 结果为准，不再把 Level1/Level2
                                // 的少量文本层补回 OCR（避免重复计数/污染）。
                                val finalText = PdfOcrEngine.stripNoiseFarEast(PdfOcrEngine.filterStrongCjkNoise(ocrRes.text))
                                val ocrStats = countTextKotlin(finalText)
                                val resMap = mapOf(
                                    "name" to dName, "ext" to ".pdf",
                                    "stats" to mapOf("words" to ocrStats.first, "fe" to ocrStats.second, "nc" to ocrStats.third, "chars" to ocrStats.fourth),
                                    "meta" to emptyMap<String, Any?>(),
                                    "pages" to ocrRes.pages,
                                    "diag" to "$pdfDiag\n(OCR补充)",
                                    "ocrNote" to PdfOcrEngine.buildOcrNote(ocrRes.pages, "")
                                )
                                val fr = toFileResult(resMap, f.absolutePath)
                                emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_ocr", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                            } else {
                                // 全部失败 → 显示最佳可用结果或错误
                                if (bestChars > 0) {
                                    // 有一些文本（虽然少）→ 降级使用
                                    val ocrDiag = PdfOcrEngine.lastDiag
                                    Diag.w( "PDF 降级(文本少+OCR失败): $dName best=${bestChars}ch ocrDiag=$ocrDiag")
                                    val resMap = mapOf(
                                        "name" to dName, "ext" to ".pdf",
                                        "stats" to mapOf("words" to bestWords, "fe" to bestFe, "nc" to bestNc, "chars" to bestChars),
                                        "meta" to emptyMap<String, Any?>(),
                                        "pages" to (if (realPages > 1) realPages else bestPages),
                                        "diag" to "$pdfDiag\n(降级:文本少+OCR失败)\nOCR详情: ${if (ocrDiag.isNotEmpty()) ocrDiag else "无"}",
                                        "ocrNote" to "⚠️ OCR未成功，已用文本层降级(详见诊断)"
                                    )
                                    val fr = toFileResult(resMap, f.absolutePath)
                                    emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_fallback", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                                } else {
                                    // 完全没有文本 → 报错
                                    var pdfPageCount = if (bestPages > 1) bestPages else 1
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
                                    emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf_err", displayName = dName, cachePath = f.absolutePath, error = errMsg))
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Diag.w( "PDF 解析失败 ${f.name}: ${e.message}")
                        emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_pdf", displayName = dName, cachePath = f.absolutePath, error = "PDF 解析失败（${e.message}）"))
                    }
                }

                // 老格式(.doc/.xls/.ppt)：POI scratchpad 抽文本 -> Kotlin 统计（不再经过 Python）
                oldOfficeFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    if (!control.gateBlocking()) return@forEachIndexed
                                        try {
                        val extLower = f.extension.lowercase()
                        // v1.9.105: DOC 用 extractDocFull 取完整文本(HWPF 默认含脚注/尾注/文本框)+页数元数据；
                        // 字数改用本程序 countTextKotlin 口径（与桌面 Word COM ComputeStatistics 对齐），不再用 SummaryInformation。
                        val text: String
                        var docPages: Int = 0  // 0 = 未知
                        var hiddenText: List<Pair<String, String>> = emptyList() // v1.3.3: .xls 隐藏表
                        var xlsVisible: List<String> = emptyList() // v1.3.4: .xls 可见表名（明细展示用）
                        var pptNotes: List<SheetStat> = emptyList()  // v1.3.34: .ppt 备注列表
                        var pptImages: Int = 0                       // v1.3.34: .ppt 嵌入图片数
                        var xlsImages: Int = 0                       // v1.3.40: .xls 嵌入图片数
                        if (extLower == "doc") {
                            val docRes = OldOfficeEngine.extractDocFull(f)
                            text = docRes.text
                            docPages = docRes.pages
                        } else if (extLower == "xls") {
                            // v1.3.3: .xls 逐表抽取，隐藏表单独返回
                            val xlsRes = OldOfficeEngine.extractXlsDetailed(f)
                            text = xlsRes.text
                            hiddenText = xlsRes.hiddenSheets
                            xlsVisible = xlsRes.visibleNames
                            xlsImages = xlsRes.imageCount  // v1.3.40: .xls 嵌入图片计数
                        } else {
                            // v1.3.34: .ppt 用 extractPptFull 获取文本+备注+图片（与 .pptx 对齐）
                            val pptRes = OldOfficeEngine.extractPptFull(f)
                            text = pptRes.text
                            docPages = pptRes.pages
                            // v1.3.36 修复：直接赋值给外层 var，不能用 val（否则遮蔽外层变量，
                            // 导致 resMap 写入的是初始空值——与 v1.3.33 imageCount bug 同因）
                            pptNotes = pptRes.notesSlides
                            pptImages = pptRes.imageCount
                        }
                        if (text.isBlank()) {
                            emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = dName, cachePath = f.absolutePath, error = "此老格式文件内容为空或无法读取"))
                        } else {
                            val stats = countTextKotlin(text)
                            val extDot = ".$extLower"
                            // 构造 pages：DOC 有元数据页数就用，否则留 null 让 toFileResult 走 estimatePages 兜底
                            val pagesValue = if (docPages > 0) docPages else null
                            // v1.9.105: .doc 统一用本程序「Word 口径」统计抽取文本（与 docx/txt/pdf/xls/ppt 一致），
                            // 不再用 SummaryInformation.wordCount 覆盖 words、再用本程序 nc 算残差 fe —— 那样会让 fe
                            // 变成「Word 保存字数 − 本程序非中文词数」的残差，与桌面（Word COM ComputeStatistics：
                            // words=Stat0/fe=Stat6/nc=words−fe/chars=Stat3，四数同源）口径不一致。
                            // HWPF WordExtractor.text() 默认已含脚注/尾注/文本框，故直接 countTextKotlin(text)。
                            val outWords = stats.first
                            val outFe = stats.second
                            val outNc = stats.third
                            val outChars = stats.fourth
                            // v1.3.3: 隐藏工作表单独统计（默认不计入合计，UI 勾选后才并入）
                            val hiddenStats = hiddenText.map { (n, t) ->
                                val s = countTextKotlin(t)
                                SheetStat(n, s.first, s.second, s.third, s.fourth)
                            }
                            val resMap = mutableMapOf<String, Any?>(
                                "name" to dName, "ext" to extDot,
                                "stats" to mapOf("words" to outWords, "fe" to outFe, "nc" to outNc, "chars" to outChars),
                                "meta" to mapOf(
                                    "sheets" to xlsVisible, "hidden_sheets" to hiddenStats,
                                    "notes_slides" to pptNotes, "image_count" to (pptImages + xlsImages)
                                )
                            )
                            if (pagesValue != null) {
                                resMap["pages"] = pagesValue
                                resMap["pages_reason"] = "doc_summary_info"
                            }
                            val fr = toFileResult(resMap.toMap(), f.absolutePath)
                            emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap.toMap()))
                        }
                    } catch (e: Throwable) {
                        Diag.w( "老格式解析失败 ${f.name}: ${e.message}")
                        emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_o", displayName = dName, cachePath = f.absolutePath, error = "无法解析此老格式（${e.message}），建议另存为 .docx/.xlsx/.pptx"))
                    }
                }

                // DWG(CAD)：v1.5.11 原始二进制扫描为主路径 + "CAD 转 PDF 统计"回退分支
                //   主路径：scanDwgRaw() 直接扫字节（移植自 port_dwg.py improved 模式）
                //   回退：电脑端 wordcount 已验证——某些 CAD 文字被压缩/特殊编码/代理对象，
                //         raw scan 拿不到正确字数，必须先导出 PDF 再从 PDF 文本层提取。
                //         LibreDWG 导出的 PDF 文字是可选中文本层（BT/Tj 操作符），提取即可统计，
                //         图形乱不影响字数统计。全程免费、Kotlin 原生（PdfExtractor），无 Python 依赖。
                // v1.9.62: DWG 改走「转换 ↔ OCR 流水线」，下一份图纸的转换与上一份的 OCR 重叠。
                // 结果仍按文件顺序产出（消费者 FIFO），字数口径与串行完全一致。
                processDwgPipelined(
                    context = context,
                    dwgFiles = dwgFiles,
                    control = control,
                    onProgress = onProgress,
                    onResult = { i, f, dName, res ->
                        val cadParts = res.cadParts
                        val cadPartsMeta = cadParts?.let { mapOf(
                            "text_words" to it.textWords, "text_fe" to it.textFe, "text_nc" to it.textNc, "text_chars" to it.textChars,
                            "code_words" to it.codeWords, "code_fe" to it.codeFe, "code_nc" to it.codeNc, "code_chars" to it.codeChars,
                            "text_items" to it.textItems, "code_items" to it.codeItems
                        ) }
                        val resMap = mapOf(
                            "name" to dName, "ext" to ".dwg",
                            "stats" to mapOf("words" to res.words, "fe" to res.fe, "nc" to res.nc, "chars" to res.chars),
                            "meta" to mapOf<String, Any?>("pages_reason" to (res.pagesReason ?: ""), "needs_pdf" to res.needsPdf,
                                "cad_parts" to cadPartsMeta),
                            "pages" to res.pages,
                            // v1.5.40: 把 DXF 编码诊断透传出去，便于定位真机仍 0 中文的原因
                            "diag" to res.diag
                        )
                        val fr = toFileResult(resMap, f.absolutePath)
                        emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                    },
                    onError = { i, f, dName, msg ->
                        Diag.w( "DWG 扫描失败 ${f.name}: $msg")
                        emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_w", displayName = dName, cachePath = f.absolutePath, error = "无法统计.dwg文件（$msg）"))
                    }
                )

                // TXT 类：纯 Kotlin 处理
                txtFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    if (!control.gateBlocking()) return@forEachIndexed
                                        try {
                        val text = f.readText(Charsets.UTF_8)
                        if (text.isBlank()) {
                            emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = dName, cachePath = f.absolutePath,
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
                            emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: Throwable) {
                        Diag.w( "TXT 读取失败 ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
                        emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_t", displayName = dName, cachePath = f.absolutePath,
                            error = "读取失败（${e.message}）"))
                    }
                }
                // 图片类：OCR（v1.0.18 起使用 Google ML Kit，稳定不闪退）
                imageFiles.forEachIndexed { i, cf ->
                    val f = cf.file
                    val dName = cf.displayName
                    if (!control.gateBlocking()) return@forEachIndexed
                                        try {
                        val text = OcrEngine.recognize(context, f)
                        if (text.isBlank()) {
                            val err = if (OcrEngine.ocrFailed)
                                "图片识别失败（模型未就绪或设备不支持）"
                            else
                                "未识别到文字（纯图/手写/模糊不清）"
                            emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath,
                                error = err))
                        } else {
                            val stats = countTextKotlin(text)
                            val resMap = mapOf(
                                "name" to dName, "ext" to ".img",
                                "stats" to mapOf("words" to stats.first, "fe" to stats.second, "nc" to stats.third, "chars" to stats.fourth),
                                "meta" to emptyMap<String, Any?>()
                            )
                            val fr = toFileResult(resMap, f.absolutePath)
                            emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath, result = fr, rawResult = resMap))
                        }
                    } catch (e: OutOfMemoryError) {
                        Runtime.getRuntime().gc()
                        Diag.w( "图片过大 OOM ${f.name}")
                        emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath, error = "图片过大，内存不足"))
                    } catch (e: Throwable) {
                        Diag.w( "OCR 失败 ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
                        emit(FileEntry(id = "e${System.currentTimeMillis()}_${i}_i", displayName = dName, cachePath = f.absolutePath, error = "图片识别失败（${e.message}）"))
                    }
                }
            }
        } catch (e: Throwable) {
    } catch (e: Throwable) {
        Diag.e( "文件处理异常: ${e.javaClass.simpleName}: ${e.message}", e)
        onError("处理出错：${e.message}")
    }
}


/** 文本类格式（无明确页概念）按字符量估算页数。
 *  v1.1.10 改进：中文文档平均 ~750 字符/页（适应大字号/大行距/表格多的场景）。
 *  纯英文文档约 ~1000 字符/页（Word 默认格式）。
 *  取保守值确保不会严重低估。
 */
fun estimatePages(chars: Int): Int = maxOf(1, (chars + 749) / 750)

// v1.5.66: 可靠的 PDF 页数（绕过 Kotlin PdfExtractor.countPagesSafe 对 ObjStm 压缩流
//   PDF 误判成 1 页的 bug）。直接用系统 PdfRenderer.pageCount（与 PdfOcrEngine OCR 分支
//   同源的可靠页数来源），失败返回 0 由上层回退 bestPages。
private fun reliablePdfPageCount(file: File): Int {
    return try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        try { r.pageCount } finally { r.close(); runCatching { pfd.close() } }
    } catch (_: Throwable) { 0 }
}

/** 压缩包内层结果 → toFileResult 可回解析的 meta 结构。 */
private fun innerToMeta(r: InnerResult): Map<String, Any?> {
    return mapOf(
        "name" to r.name,
        "stats" to mapOf("words" to r.words, "fe" to r.fe, "nc" to r.nc, "chars" to r.chars),
        "meta" to mapOf("pages" to r.pages, "needs_pdf" to r.needsPdf)
    )
}

/** v1.9.25: JSONObject → Map<String, Any?>（rawResult 跨进程 JSON 往返的还原；JSONObject.NULL 视为 null）。 */
private fun jsonToMap(o: org.json.JSONObject): Map<String, Any?> {
    val m = HashMap<String, Any?>()
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        m[k] = jsonValue(o.opt(k))
    }
    return m
}

private fun jsonValue(v: Any?): Any? = when {
    v == null || v == org.json.JSONObject.NULL -> null
    v is org.json.JSONObject -> jsonToMap(v)
    v is org.json.JSONArray -> (0 until v.length()).map { jsonValue(v.opt(it)) }
    else -> v
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
            pages = (im["meta"] as? Map<*, *>)?.get("pages") as? Int,
            needsPdf = (im["meta"] as? Map<*, *>)?.get("needs_pdf") as? Boolean ?: false
        )
    }
    val imgPages = meta["img_pages"] as? List<*>
    val imageOnly = meta["image_only"] as? Boolean ?: false
    val ext = (m?.get("ext") as? String) ?: ""
    // v1.3.33: OOXML 嵌入图片数量（来自 meta["image_count"]），供 hasUnreliable 判断
    val imageCount = (meta["image_count"] as? Number)?.toInt() ?: 0
    // v1.5.61: CAD 文字/纯编号拆分
    val cadPartsMap = meta["cad_parts"] as? Map<*, *>
    val cadParts = cadPartsMap?.let {
        CadPartStats(
            textWords = (it["text_words"] as? Number)?.toInt() ?: 0,
            textFe = (it["text_fe"] as? Number)?.toInt() ?: 0,
            textNc = (it["text_nc"] as? Number)?.toInt() ?: 0,
            textChars = (it["text_chars"] as? Number)?.toInt() ?: 0,
            codeWords = (it["code_words"] as? Number)?.toInt() ?: 0,
            codeFe = (it["code_fe"] as? Number)?.toInt() ?: 0,
            codeNc = (it["code_nc"] as? Number)?.toInt() ?: 0,
            codeChars = (it["code_chars"] as? Number)?.toInt() ?: 0,
            textItems = (it["text_items"] as? Number)?.toInt() ?: 0,
            codeItems = (it["code_items"] as? Number)?.toInt() ?: 0
        )
    }
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
        hiddenSheets = (meta["hidden_sheets"] as? List<*>)?.mapNotNull { it as? SheetStat } ?: emptyList(),
        // v1.3.32: PPT 备注幻灯片
        notesSlides = (meta["notes_slides"] as? List<*>)?.mapNotNull { it as? SheetStat } ?: emptyList(),
        // v1.3.32: PPT 嵌入图片数量
        imageCount = imageCount,
        // v1.3.32: 文件内部标题
        internalTitle = (meta["internal_title"] as? String) ?: "",
        inner = inner,
        // v1.3.39: 仅 Office 文档（pptx/ppt/docx/xlsx/xls）含嵌入图片时才显示"导出未统计图片"按钮
        // 图片型 PDF 不再触发导出按钮（PDF 无法像 OOXML 那样解压提取内嵌图片）
        hasUnreliable = imageCount > 0,
        // v1.3.64: PDF 诊断信息（来自 resMap["diag"]）
        diag = m?.get("diag") as? String,
        // v1.5.66: PDF 的 OCR 状态摘要（来自 resMap["ocrNote"]）
        ocrNote = m?.get("ocrNote") as? String,
        // v1.5.36: DWG 统计不准、需文字型 PDF 重新统计时由扫描分支置 true
        needsPdf = (meta["needs_pdf"] as? Boolean) ?: false,
        // v1.5.61: CAD 文字/纯编号拆分
        cadParts = cadParts
    )
}

private fun exportUnreliable(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    entries: List<FileEntry>,
    onStateChange: (Boolean) -> Unit = {}
) {
    scope.launch(Dispatchers.Main) {
        onStateChange(true)
        try {
            val sel = entries.filter { it.selected && it.result?.hasUnreliable == true && it.cachePath.isNotBlank() }
            if (sel.isEmpty()) { snackbar.showSnackbar("没有可导出的未统计图片"); return@launch }

            val out = File(context.getExternalFilesDir(null), "无法准确统计内容_${System.currentTimeMillis()}.pdf")
            var totalCount = 0
            withContext(Dispatchers.IO) {
                // v1.3.35: 纯 Kotlin 实现（不依赖 Python/Chaquopy，彻底避免 AssetFinder 报错）
                totalCount = buildExportPdfKotlin(sel, out.absolutePath)
            }
            if (totalCount > 0) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "打开导出的 PDF"))
            } else {
                snackbar.showSnackbar("无可导出内容")
                out.delete()
            }
        } catch (e: Exception) {
            snackbar.showSnackbar("导出失败：${e.message}")
        } finally {
            onStateChange(false)
        }
    }
}

/** v1.3.35: 纯 Kotlin 实现 PDF 导出——从 OOXML 包抽取嵌入图片，每张图片一页 PDF。
 *  返回成功写入的页数。 */
private fun buildExportPdfKotlin(entries: List<FileEntry>, outPath: String): Int {
    val pdf = PdfDocument()
    var totalPages = 0
    val pageW = 595
    val pageH = 842
    val maxImgW = pageW - 40
    val headerH = 36f
    val headerPaint = android.graphics.Paint().apply {
        textSize = 12f; isAntiAlias = true; color = android.graphics.Color.BLACK
    }
    val mediaExts = setOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".tif", ".tiff", ".webp")

    for (entry in entries) {
        val srcPath = entry.cachePath
        val ext = entry.result?.ext ?: ""
        val name = entry.result?.name ?: entry.displayName
        val words = entry.result?.words ?: 0
        val fe = entry.result?.fe ?: 0
        val nc = entry.result?.nc ?: 0
        val imageCount = entry.result?.imageCount ?: 0

        if (imageCount <= 0 && ext != ".pdf") continue

        val imgEntries = mutableListOf<Pair<String, String>>()

        // v1.3.38/v1.3.40: 分三种格式——OOXML(ZIP)、OLE2(.ppt)、OLE2(.xls)
        // .xls 也是 OLE2 格式（非 ZIP），ZipFile 会静默失败，需用 POI HSSF 提取
        if (ext in setOf(".pptx", ".docx", ".xlsx")) {
            try {
                val zip = ZipFile(srcPath)
                val zipEntries = java.util.Collections.list(zip.entries())
                    .filter { ze ->
                        val nm = ze.name.lowercase()
                        (("/media/" in nm || nm.startsWith("media/")) &&
                                !nm.endsWith("/") && mediaExts.any { nm.endsWith(it) })
                    }
                    .sortedBy { it.name }
                var n = 0
                for (ze in zipEntries) {
                    n++
                    val tmp = File(
                        System.getProperty("java.io.tmpdir"),
                        "wc_export_${System.currentTimeMillis()}_$n${ze.name.substringAfterLast('.')}"
                    )
                    try {
                        zip.getInputStream(ze).use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                        imgEntries.add(tmp.absolutePath to "$name - 图片 $n")
                    } catch (_: Exception) {}
                }
                zip.close()
            } catch (_: Exception) {}
        } else if (ext == ".ppt") {
            // .ppt 是 OLE2 格式，需用 POI HSLF 提取嵌入图片（与 extractPptFull 统计 imageCount 对应）
            try {
                val fis = java.io.FileInputStream(srcPath)
                val ppt = org.apache.poi.hslf.usermodel.HSLFSlideShow(fis)
                var n = 0
                for (pd in ppt.pictureData) {
                    n++
                    // v1.3.38: 用图片数据魔数(magic number)判断真实格式，不依赖 POI 枚举命名
                    // JPEG: FF D8 FF ; PNG: 89 50 4E 47 ; 其余(EMF/WMF 矢量)跳过无法在 PDF 显示
                    val data = pd.data ?: continue
                    val ext2 = when {
                        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "jpg"
                        data.size >= 4 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "png"
                        else -> null
                    } ?: continue
                    val tmp = File(
                        System.getProperty("java.io.tmpdir"),
                        "wc_export_${System.currentTimeMillis()}_$n.$ext2"
                    )
                    try {
                        tmp.outputStream().use { it.write(data) }
                        imgEntries.add(tmp.absolutePath to "$name - 图片 $n")
                    } catch (_: Exception) {}
                }
                ppt.close()
                fis.close()
            } catch (_: Exception) {}
        } else if (ext == ".xls") {
            // v1.3.44: .xls 用工作簿级 getAllPictures() 提取（覆盖全部嵌入图片，不限于绘图层）
            try {
                val fis = java.io.FileInputStream(srcPath)
                val wb = org.apache.poi.hssf.usermodel.HSSFWorkbook(fis)
                var n = 0
                for (picData in wb.allPictures) {
                    val data = picData.data
                    n++
                    val ext2 = when {
                        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "jpg"
                        data.size >= 4 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "png"
                        else -> null
                    } ?: continue
                    val tmp = File(
                        System.getProperty("java.io.tmpdir"),
                        "wc_export_${System.currentTimeMillis()}_$n.$ext2"
                    )
                    try {
                        tmp.outputStream().use { it.write(data) }
                        imgEntries.add(tmp.absolutePath to "$name - 图片 $n")
                    } catch (_: Exception) {}
                }
                wb.close()
                fis.close()
            } catch (_: Exception) {}
        }

        for ((imgPath, label) in imgEntries) {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(imgPath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                try { File(imgPath).delete() } catch (_: Exception) {}
                continue
            }

            val scale = minOf(maxImgW.toFloat() / opts.outWidth, 1f)
            val dw = (opts.outWidth * scale).toInt()
            val dh = (opts.outHeight * scale).toInt()
            val canvasH = (headerH + dh + 4).toInt()

            val pageInfo = PdfDocument.PageInfo.Builder(pageW, maxOf(pageH, canvasH), totalPages + 1).create()
            val page = pdf.startPage(pageInfo)
            val canvas = page.canvas

            val headerText = "$name  |  $label  |  字数$words 中文$fe 非中文$nc"
            canvas.drawText(headerText, 10f, 20f, headerPaint)

            val bmpOpts = BitmapFactory.Options()
            bmpOpts.inSampleSize = if (scale < 0.5f) 2 else 1
            val bmp = BitmapFactory.decodeFile(imgPath, bmpOpts)
            if (bmp != null) {
                val left = ((pageW - dw) / 2f).coerceAtLeast(0f)
                canvas.drawBitmap(bmp, null, android.graphics.RectF(left, headerH + 2, left + dw, headerH + 2 + dh), null)
                bmp.recycle()
            }

            pdf.finishPage(page)
            totalPages++
            try { File(imgPath).delete() } catch (_: Exception) {}
        }
    }

    if (totalPages > 0) {
        pdf.writeTo(FileOutputStream(outPath))
    }
    pdf.close()
    return totalPages
}

// ═══════════════════════════════════════════════════════════════════════════
// v1.1.1: 文档比较界面（仿 Word「审阅 → 比较」）
// ═══════════════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════════════
// v1.1.1: 文档比较界面（仿 Word「审阅 → 比较」）
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CompareScreen(
    context: android.content.Context,
    scope: CoroutineScope = rememberCoroutineScope(),
    snackbar: SnackbarHostState,
    availableFiles: List<FileEntry> = emptyList(),   // v1.1.2: 字数统计列表中的文件（供直接选用）
    modifier: Modifier = Modifier
) {
    var origCf by remember { mutableStateOf<CachedFile?>(null) }
    var revCf by remember { mutableStateOf<CachedFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resultJson by remember { mutableStateOf<String?>(null) }
    var outPath by remember { mutableStateOf<String?>(null) }
    var imgPath by remember { mutableStateOf<String?>(null) }   // 长图 PNG 路径（与 outPath 对应）
    var rendering by remember { mutableStateOf(false) }          // 正生成/打开长图

    // 比较设置（对应 Word 比较对话框）
    var optCase by remember { mutableStateOf(true) }   // 大小写更改
    var optTable by remember { mutableStateOf(true) }   // 表格
    var optHf by remember { mutableStateOf(true) }      // 页眉和页脚
    var optFn by remember { mutableStateOf(true) }      // 脚注和尾注
    var optTb by remember { mutableStateOf(true) }      // 文本框
    var optField by remember { mutableStateOf(true) }   // 域

    var pickSlot by remember { mutableStateOf(0) } // 0=原文档 1=修订文档
    var showListPicker by remember { mutableStateOf(0) } // 0=不显示 1=选原文档 2=选修订文档
    // v1.1.2: 从字数统计列表中过滤出 DOCX 文件供选用
    val docxFiles = remember(availableFiles) {
        availableFiles.filter { fe ->
            val p = fe.cachePath.lowercase()
            p.endsWith(".docx") || p.endsWith(".doc")
        }
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val cf = copyUriToCache(context, uri)
                if (pickSlot == 0) origCf = cf else revCf = cf
            } catch (e: Throwable) {
                scope.launch { snackbar.showSnackbar("选择文件失败：${e.message}") }
            }
        }
    }
    fun pick(slot: Int) {
        pickSlot = slot
        docPicker.launch(arrayOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword"
        ))
    }

    fun doCompare() {
        val o = origCf ?: return
        val r = revCf ?: return
        busy = true
        resultJson = null
        outPath = null
        scope.launch(Dispatchers.IO) {
            try {
                val out = File(context.cacheDir, "compare_result_${System.currentTimeMillis()}.docx")
                // v1.1.36: 使用纯 Kotlin 实现（DocxComparator），不再经过 Python 引擎
                // 原因：Chaquopy lxml C 扩展在 Android 上触发 fatal 级 AssetFinder 崩溃，
                //       历经 v1.1.15~v1.35 共 20 个版本验证，Python 层拦截全部无效。
                val cmpOpts = org.json.JSONObject().apply {
                    put("level", "word")   // v1.3.9: 固��字词级别（字符级别已移除，两者结果一致）
                    put("case", optCase)
                    put("table", optTable)
                    put("header_footer", optHf)
                    put("footnote", optFn)
                    put("textbox", optTb)
                    put("field", optField)
                }.toString()
                val res = DocxComparator.compare(context, o.file.absolutePath, r.file.absolutePath, out.absolutePath, cmpOpts)
                withContext(Dispatchers.Main) {
                    if (res.ok) {
                        val j = org.json.JSONObject().apply {
                            put("ok", true)
                            put("outputPath", res.outputPath)
                            put("modifiedChars", res.modifiedChars)
                            put("insCount", res.insCount)
                            put("delCount", res.delCount)
                            put("repCount", res.repCount)
                            put("summary", res.summary)
                        }
                        resultJson = j.toString()
                        outPath = res.outputPath
                        imgPath = res.outputPath?.removeSuffix(".docx")?.plus(".png")
                    } else {
                        snackbar.showSnackbar("比较失败：${res.error ?: "未知错误"}")
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    val detail = e.message ?: e.javaClass.simpleName
                    snackbar.showSnackbar("比较异常 [${e.javaClass.simpleName}]：${detail.take(300)}")
                }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 原文档
        CompareFileCard("原文档", origCf?.displayName, "选择原文档", docxFiles.isNotEmpty(),
            onPick = { pick(0) }, onPickFromList = { showListPicker = 1 })
        // 修订文档
        CompareFileCard("修订文档", revCf?.displayName, "选择修订文档", docxFiles.isNotEmpty(),
            onPick = { pick(1) }, onPickFromList = { showListPicker = 2 })

        // 比较设置
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("比较设置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                // v1.3.14: 固定2列网格布局，保证选择框纵向完美对齐
                // （FlowRow+IntrinsicSize.Max在v1.3.13仍无法对齐，改用显式Row网格）
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompareCheck("大小写更改", optCase) { optCase = it }
                        CompareCheck("表格", optTable) { optTable = it }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompareCheck("页眉和页脚", optHf) { optHf = it }
                        CompareCheck("脚注和尾注", optFn) { optFn = it }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompareCheck("文本框", optTb) { optTb = it }
                        CompareCheck("域", optField) { optField = it }
                    }
                }
            }
        }

        // 开始比较
        Button(
            onClick = { doCompare() },
            modifier = Modifier.fillMaxWidth(),
            enabled = origCf != null && revCf != null && !busy
        ) { Text(if (busy) "比较中…" else "开始比较") }

        if (busy) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        // 结果
        resultJson?.let { rj ->
            val j = org.json.JSONObject(rj)
            val modChars = j.optInt("modifiedChars", 0)
            val ins = j.optInt("insCount", 0)
            val del = j.optInt("delCount", 0)
            val rep = j.optInt("repCount", 0)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("比较完成", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("修改涉及的句子总字数：", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
                        Text("$modChars", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleLarge)
                        Text(" 字/词", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
                    }
                    Text("插入 $ins 处 ｜ 删除 $del 处 ｜ 修改 $rep 处", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val docx = outPath
                            val png = imgPath
                            if (docx != null && png != null && !rendering) {
                                rendering = true
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        DocxImageRenderer.render(docx, png)
                                    }
                                    withContext(Dispatchers.Main) {
                                        rendering = false
                                        if (ok) openImageFile(context, png)
                                        else snackbar.showSnackbar("生成长图失败，可改用「分享Word」")
                                    }
                                }
                            }
                        }, modifier = Modifier.weight(1f), enabled = !rendering) {
                            Text(if (rendering) "生成中…" else "打开结果")
                        }
                        OutlinedButton(onClick = {
                            outPath?.let { shareDocxFile(context, it, docxMime) }
                        }, modifier = Modifier.weight(1f)) { Text("分享Word") }
                    }
                }
            }
        }
    }

    // v1.1.2: 从字数统计列表选择文档的对话框
    if (showListPicker > 0 && docxFiles.isNotEmpty()) {
        val slotLabel = if (showListPicker == 1) "原文档" else "修订文档"
        AlertDialog(
            onDismissRequest = { showListPicker = 0 },
            title = { Text("从字数统计列表选择$slotLabel") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(docxFiles) { fe ->
                        TextButton(
                            onClick = {
                                val cf = CachedFile(File(fe.cachePath), fe.displayName)
                                if (showListPicker == 1) origCf = cf else revCf = cf
                                showListPicker = 0
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(fe.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showListPicker = 0 }) { Text("取消") } }
        )
    }

}

@Composable
private fun CompareFileCard(label: String, name: String?, btnText: String,
    hasListFiles: Boolean = false, onPick: () -> Unit, onPickFromList: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Spacer(Modifier.padding(2.dp))
                Text(name ?: "未选择", fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasListFiles) {
                    TextButton(onClick = onPickFromList, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("从列表", fontSize = 12.sp)
                    }
                }
                OutlinedButton(onClick = onPick) { Text(btnText) }
            }
        }
    }
}

@Composable
private fun CompareCheck(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}

private fun openDocxFile(context: android.content.Context, path: String, mime: String) {
    try {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "打开比对结果"))
    } catch (e: Throwable) {
        Diag.w( "打开比对结果失败: ${e.message}")
    }
}

/** 打开比对结果的长图 PNG（把 WORD 截成长图后用图片查看器打开）。 */
private fun openImageFile(context: android.content.Context, path: String) {
    try {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/png")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "打开长图"))
    } catch (e: Throwable) {
        Diag.w( "打开长图失败: ${e.message}")
    }
}

/** 分享比对结果 WORD（仍分享 .docx，内容不变）。 */
private fun shareDocxFile(context: android.content.Context, path: String, mime: String) {
    try {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享比对结果"))
    } catch (e: Throwable) {
        Diag.w( "分享比对结果失败: ${e.message}")
    }
}

