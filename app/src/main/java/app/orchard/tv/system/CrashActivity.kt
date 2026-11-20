package app.orchard.tv.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.orchard.tv.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash report screen running in an isolated `:crash` process using pure Android Views
 * (no Compose/GPU shaders) so it stays reliable even on low-end or crashing TV firmwares.
 * Users can photograph this screen to report the exact stack trace and recent diagnostic lines.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val summary = intent.getStringExtra(EXTRA_SUMMARY).orEmpty().ifBlank { "Unknown Error" }
        val stack = intent.getStringExtra(EXTRA_STACK).orEmpty()
        val threadName = intent.getStringExtra(EXTRA_THREAD).orEmpty().ifBlank { "unknown" }
        val deviceInfo = intent.getStringExtra(EXTRA_DEVICE).orEmpty()
        val recentLog = intent.getStringExtra(EXTRA_RECENT).orEmpty()
        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0F"))
            setPadding(dp(40), dp(28), dp(40), dp(24))
        }

        // Title
        val title = TextView(this).apply {
            text = "HLine TV 遇到异常 · 请拍照反馈"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)

        // Device & metadata bar
        val meta = TextView(this).apply {
            text = "$timeText  |  Thread: $threadName
$deviceInfo"
            setTextColor(Color.parseColor("#A1A1A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(12))
        }
        root.addView(meta)

        // Exception headline
        val headline = TextView(this).apply {
            text = summary
            setTextColor(Color.parseColor("#FF6961"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            background = roundRect(Color.parseColor("#241517"), Color.parseColor("#5A2326"), dp(10))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(
            headline,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            },
        )

        // Scrollable stack trace + recent DiagLog
        val bodyText = buildString {
            append(stack.trim())
            if (recentLog.isNotBlank()) {
                append("

--- Recent HLineDiag ---
")
                append(recentLog.trim())
            }
        }
        val traceView = TextView(this).apply {
            text = bodyText
            setTextColor(Color.parseColor("#E5E5EA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
        val scroll = ScrollView(this).apply {
            background = roundRect(Color.parseColor("#18181C"), Color.parseColor("#2C2C32"), dp(10))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isFocusable = true
            isFocusableInTouchMode = true
            addView(traceView)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = dp(16)
            },
        )

        // Bottom buttons
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        val hint = TextView(this).apply {
            text = "方向键上下可滚动日志 · 按确定重启"
            setTextColor(Color.parseColor("#8E8E93"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        actions.addView(
            hint,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        val restartBtn = makeButton("重启 HLine TV", primary = true) {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            runCatching { startActivity(i) }
            finish()
        }
        val exitBtn = makeButton("退出", primary = false) {
            finish()
        }

        actions.addView(
            restartBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                marginEnd = dp(12)
            },
        )
        actions.addView(
            exitBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)),
        )
        root.addView(actions)

        setContentView(root)
        restartBtn.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun makeButton(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(22), 0, dp(22), 0)
            isFocusable = true
            isFocusableInTouchMode = false
            val normalBg = if (primary) Color.parseColor("#0A84FF") else Color.parseColor("#2C2C2E")
            val focusedBg = if (primary) Color.parseColor("#409CFF") else Color.parseColor("#48484A")
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), roundRect(focusedBg, Color.WHITE, dp(8)))
                addState(intArrayOf(android.R.attr.state_pressed), roundRect(focusedBg, Color.WHITE, dp(8)))
                addState(intArrayOf(), roundRect(normalBg, Color.TRANSPARENT, dp(8)))
            }
            setOnClickListener { onClick() }
        }

    private fun roundRect(fill: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val EXTRA_SUMMARY = "summary"
        private const val EXTRA_STACK = "stack"
        private const val EXTRA_THREAD = "thread"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_RECENT = "recent"

        fun launch(context: Context, thread: Thread, error: Throwable, device: String, recent: String) {
            val summary = "${error.javaClass.name}: ${error.message ?: "(no message)"}"
            val stack = android.util.Log.getStackTraceString(error).take(12_000)
            val intent = Intent(context, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_SUMMARY, summary)
                putExtra(EXTRA_STACK, stack)
                putExtra(EXTRA_THREAD, thread.name)
                putExtra(EXTRA_DEVICE, device)
                putExtra(EXTRA_RECENT, recent.take(4_000))
            }
            context.startActivity(intent)
        }
    }
}
