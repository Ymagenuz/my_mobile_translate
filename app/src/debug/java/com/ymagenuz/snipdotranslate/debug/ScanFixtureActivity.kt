package com.ymagenuz.snipdotranslate.debug

import android.app.Activity
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** A screen of known text to scan. Pass the boolean extra "secure" to block capture. */
class ScanFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && intent.getBooleanExtra("clearClipboard", false)) {
            getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        }
        if (intent.getBooleanExtra("secure", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(48, 160, 48, 48)
        }
        LINES.forEach { (text, size) ->
            page.addView(
                TextView(this).apply {
                    setTextColor(Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                    setPadding(0, 24, 0, 24)
                    this.text = text
                },
            )
        }
        setContentView(page)
    }

    private companion object {
        val LINES = listOf(
            "Translation on the go" to 26f,
            "The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs." to 18f,
            "人工智能正在改变我们学习语言的方式，翻译软件也变得越来越准确。" to 18f,
            "使用 Kotlin 编写 Android 应用非常方便。" to 18f,
            "これは日本語のテキストです。" to 18f,
            "Small print: accuracy should hold at eleven points." to 11f,
        )
    }
}
