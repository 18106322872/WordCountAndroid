package com.henry.aligntool

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 纯 View 入口（不依赖 Compose / Material3）。
 *
 * 作用：在会崩的 MainActivity 之前先判断 filesDir/crash.log 是否有「上次崩溃堆栈」。
 * - 有：用最朴素的 TextView 把堆栈完整展示出来（哪怕 MainActivity 每次都崩，这里也绝不会崩），
 *       用户可读到/截图发回定位；点「清除并打开」才进入主程序。
 * - 无：直接拉起 MainActivity。
 *
 * 为何要独立出来：之前把崩溃显示写在 MainScreen(Compose) 里，而崩溃本身也在同一组合中、
 * 每次启动都崩在同一处，导致 crash.log 还没渲染就被 readCrashLog 删掉，永远看不到堆栈。
 */
class CrashGateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = File(filesDir, "crash.log")
        val log = if (crashFile.exists()) runCatching { crashFile.readText() }.getOrNull() else null

        if (log.isNullOrBlank()) {
            // 无崩溃记录，直接进入主程序
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "上次运行崩溃（请把下面内容发开发者定位）："
            setTextColor(Color.RED)
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        val body = TextView(this).apply {
            text = log
            setTextColor(Color.DKGRAY)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
        }
        val btn = Button(this).apply {
            text = "清除日志并打开应用"
            setOnClickListener {
                runCatching { crashFile.delete() }
                startActivity(Intent(this@CrashGateActivity, MainActivity::class.java))
                finish()
            }
        }
        container.addView(title)
        container.addView(body)
        container.addView(btn)
        scroll.addView(container)
        setContentView(scroll)
    }
}
