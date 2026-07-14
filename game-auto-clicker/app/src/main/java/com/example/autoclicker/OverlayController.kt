package com.example.autoclicker

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 悬浮层控制器：
 *  - 悬浮控制面板（拖动柄 / 加减点位 / 开始暂停 / 设置 / 收起）
 *  - 可拖动的编号点位标记，按编号顺序循环点击
 *  - 设置弹窗（间隔、按压时长、随机抖动、循环次数）
 * 点位与设置通过 SharedPreferences 持久化。
 */
class OverlayController(private val service: AutoClickService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = service.getSharedPreferences("auto_clicker", Context.MODE_PRIVATE)

    private var panel: LinearLayout? = null
    private lateinit var playBtn: TextView
    private lateinit var countLabel: TextView
    private val markers = mutableListOf<TextView>()
    private var settingsView: View? = null

    // ---- 运行参数（可在设置弹窗中修改）----
    private var intervalMs = prefs.getLong("interval", 500L)   // 两次点击之间的间隔
    private var tapDurationMs = prefs.getLong("duration", 30L) // 每次按压时长
    private var jitterMs = prefs.getLong("jitter", 0L)         // 间隔随机抖动 ±ms
    private var loopCount = prefs.getInt("loops", 0)           // 循环轮数，0 = 无限

    var running = false
        private set
    private var index = 0
    private var loopsLeft = 0

    private val markerSizePx = dp(44)

    // ==================== 面板 ====================

    fun showPanel() {
        if (panel != null) return
        val ctx = service

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(Color.parseColor("#DD263238"), dp(22).toFloat())
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            x = dp(12)
            y = dp(160)
        }

        // 拖动柄：拖动它移动整个面板
        val dragHandle = panelButton("⠿").apply { setTextColor(Color.parseColor("#90A4AE")) }
        makeDraggable(dragHandle, root) { lp }

        val addBtn = panelButton("➕") { addMarkerAtCenter() }
        val removeBtn = panelButton("➖") { removeLastMarker() }
        playBtn = panelButton("▶") { if (running) stopClicking() else startClicking() }
        val settingsBtn = panelButton("⚙") { showSettings() }
        val closeBtn = panelButton("✕") { hidePanel() }

        countLabel = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(4), 0)
        }
        updateCountLabel()

        root.addView(dragHandle)
        root.addView(addBtn)
        root.addView(removeBtn)
        root.addView(playBtn)
        root.addView(settingsBtn)
        root.addView(countLabel)
        root.addView(closeBtn)

        wm.addView(root, lp)
        panel = root
        restoreMarkers()
    }

    fun hidePanel() {
        stopClicking()
        removeAllMarkerViews()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        settingsView?.let { runCatching { wm.removeView(it) } }
        settingsView = null
    }

    fun togglePanel() {
        if (panel == null) showPanel() else hidePanel()
    }

    fun destroy() = hidePanel()

    private fun panelButton(text: String, onClick: (() -> Unit)? = null): TextView =
        TextView(service).apply {
            this.text = text
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            if (onClick != null) setOnClickListener { onClick() }
        }

    private fun updateCountLabel() {
        if (::countLabel.isInitialized) countLabel.text = "${markers.size}点"
    }

    // ==================== 点位标记 ====================

    /** 在屏幕中央附近添加一个新点位（略微错开，避免完全重叠） */
    private fun addMarkerAtCenter() {
        val dm = service.resources.displayMetrics
        val offset = dp(28) * markers.size
        addMarker(dm.widthPixels / 2 + offset % dp(112), dm.heightPixels / 2 + offset % dp(112))
        saveMarkers()
    }

    private fun addMarker(centerX: Int, centerY: Int) {
        val marker = TextView(service).apply {
            text = (markers.size + 1).toString()
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B3E53935"))
                setStroke(dp(2), Color.WHITE)
            }
        }
        val lp = overlayParams(markerSizePx, markerSizePx).apply {
            x = centerX - markerSizePx / 2
            y = centerY - markerSizePx / 2
        }
        makeDraggable(marker, marker, onDragEnd = { saveMarkers() }) { lp }
        wm.addView(marker, lp)
        markers.add(marker)
        updateCountLabel()
    }

    private fun removeLastMarker() {
        val last = markers.removeLastOrNull() ?: return
        runCatching { wm.removeView(last) }
        saveMarkers()
        updateCountLabel()
    }

    private fun removeAllMarkerViews() {
        markers.forEach { runCatching { wm.removeView(it) } }
        markers.clear()
        updateCountLabel()
    }

    /** 运行时把标记设为不可触摸并半透明，让注入的点击穿透到游戏 */
    private fun setMarkersClickThrough(clickThrough: Boolean) {
        markers.forEach { m ->
            val lp = m.layoutParams as WindowManager.LayoutParams
            lp.flags = if (clickThrough) {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            m.alpha = if (clickThrough) 0.35f else 1f
            runCatching { wm.updateViewLayout(m, lp) }
        }
    }

    private fun markerCenter(m: View): Pair<Float, Float> {
        val lp = m.layoutParams as WindowManager.LayoutParams
        return (lp.x + markerSizePx / 2f) to (lp.y + markerSizePx / 2f)
    }

    // ---- 持久化 ----

    private fun saveMarkers() {
        val s = markers.joinToString("|") { m ->
            val (cx, cy) = markerCenter(m)
            "${cx.roundToInt()},${cy.roundToInt()}"
        }
        prefs.edit().putString("points", s).apply()
    }

    private fun restoreMarkers() {
        val s = prefs.getString("points", null)?.takeIf { it.isNotBlank() } ?: return
        s.split("|").forEach { pair ->
            val xy = pair.split(",")
            val x = xy.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val y = xy.getOrNull(1)?.toIntOrNull() ?: return@forEach
            addMarker(x, y)
        }
    }

    // ==================== 点击循环 ====================

    fun startClicking() {
        if (running) return
        if (markers.isEmpty()) {
            toast("请先用 ➕ 添加点击位置")
            return
        }
        running = true
        index = 0
        loopsLeft = loopCount
        setMarkersClickThrough(true)
        playBtn.text = "⏸"
        handler.post(tick)
    }

    fun stopClicking() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        setMarkersClickThrough(false)
        if (::playBtn.isInitialized) playBtn.text = "▶"
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!running || markers.isEmpty()) return
            val m = markers[index % markers.size]
            val (cx, cy) = markerCenter(m)
            service.tap(cx, cy, tapDurationMs) {
                if (!running) return@tap
                index++
                if (index >= markers.size) {
                    index = 0
                    if (loopCount > 0 && --loopsLeft <= 0) {
                        stopClicking()
                        toast("已完成 $loopCount 轮点击")
                        return@tap
                    }
                }
                val jitter = if (jitterMs > 0) Random.nextLong(-jitterMs, jitterMs + 1) else 0L
                val delay = (intervalMs + jitter).coerceAtLeast(tapDurationMs + 10)
                handler.postDelayed(this, delay)
            }
        }
    }

    // ==================== 设置弹窗 ====================

    private fun showSettings() {
        if (settingsView != null) return
        stopClicking()
        val ctx = service

        fun row(label: String, value: String): Pair<LinearLayout, EditText> {
            val edit = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value)
                setTextColor(Color.WHITE)
                textSize = 14f
                minWidth = dp(90)
            }
            val rowView = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(ctx).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    minWidth = dp(150)
                })
                addView(edit)
            }
            return rowView to edit
        }

        val (r1, intervalEdit) = row("点击间隔 (毫秒)", intervalMs.toString())
        val (r2, durationEdit) = row("按压时长 (毫秒)", tapDurationMs.toString())
        val (r3, jitterEdit) = row("随机抖动 ±(毫秒)", jitterMs.toString())
        val (r4, loopsEdit) = row("循环轮数 (0=无限)", loopCount.toString())

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(panelButton("取消") { dismissSettings() })
            addView(panelButton("保存") {
                intervalMs = (intervalEdit.text.toString().toLongOrNull() ?: intervalMs).coerceIn(20, 3_600_000)
                tapDurationMs = (durationEdit.text.toString().toLongOrNull() ?: tapDurationMs).coerceIn(1, 5_000)
                jitterMs = (jitterEdit.text.toString().toLongOrNull() ?: jitterMs).coerceIn(0, 10_000)
                loopCount = (loopsEdit.text.toString().toIntOrNull() ?: loopCount).coerceIn(0, 1_000_000)
                prefs.edit()
                    .putLong("interval", intervalMs)
                    .putLong("duration", tapDurationMs)
                    .putLong("jitter", jitterMs)
                    .putInt("loops", loopCount)
                    .apply()
                dismissSettings()
                toast("设置已保存")
            })
        }

        val dialog = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.parseColor("#EE263238"), dp(14).toFloat())
            setPadding(dp(18), dp(14), dp(18), dp(10))
            addView(TextView(ctx).apply {
                text = "连点设置"
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(6))
            })
            addView(r1); addView(r2); addView(r3); addView(r4)
            addView(buttons)
        }

        // 设置窗口需要可获取焦点，才能弹出输入法
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        wm.addView(dialog, lp)
        settingsView = dialog
    }

    private fun dismissSettings() {
        settingsView?.let { runCatching { wm.removeView(it) } }
        settingsView = null
    }

    // ==================== 工具方法 ====================

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /**
     * 让 [handle] 拖动时移动 [target] 所在的悬浮窗。
     * 小于触摸滑动阈值的按下抬起仍作为点击事件分发。
     */
    private fun makeDraggable(
        handle: View,
        target: View,
        onDragEnd: (() -> Unit)? = null,
        params: () -> WindowManager.LayoutParams
    ) {
        val slop = dp(6)
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        handle.setOnTouchListener { v, e ->
            val lp = params()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (dragging || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        dragging = true
                        lp.x = startX + dx.roundToInt()
                        lp.y = startY + dy.roundToInt()
                        runCatching { wm.updateViewLayout(target, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) onDragEnd?.invoke() else v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun roundedBg(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(v: Int): Int =
        (v * service.resources.displayMetrics.density).roundToInt()

    private fun toast(msg: String) =
        Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()
}
