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

/** 单个点位的独立配置 */
class PointConfig(
    /** 每轮到该点时连续点击的次数 */
    var tapCount: Int = 1,
    /** 该点内部连点的间隔（毫秒），tapCount > 1 时生效 */
    var tapIntervalMs: Long = 100L,
    /** 点完该点后、移动到下一个点前的延迟（毫秒） */
    var delayMs: Long = 500L
)

/** 点位 = 悬浮标记视图 + 独立配置 */
class MarkerItem(val view: TextView, val config: PointConfig)

/**
 * 悬浮层控制器：
 *  - 悬浮控制面板（拖动柄 / 加减点位 / 开始暂停 / 全局设置 / 收起）
 *  - 可拖动的编号点位标记，按编号顺序循环点击；轻点标记可单独设置
 *    该点的点击次数、连点间隔和点完后延迟
 *  - 全局设置（按压时长、随机抖动、循环轮数、新点位默认延迟）
 * 点位与设置通过 SharedPreferences 持久化。
 */
class OverlayController(private val service: AutoClickService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = service.getSharedPreferences("auto_clicker", Context.MODE_PRIVATE)

    private var panel: LinearLayout? = null
    private lateinit var playBtn: TextView
    private lateinit var countLabel: TextView
    private val items = mutableListOf<MarkerItem>()
    private var dialogView: View? = null

    // ---- 全局参数 ----
    private var defaultDelayMs = prefs.getLong("interval", 500L)  // 新点位的默认“点完后延迟”
    private var tapDurationMs = prefs.getLong("duration", 30L)    // 每次按压时长
    private var jitterMs = prefs.getLong("jitter", 0L)            // 延迟随机抖动 ±ms
    private var loopCount = prefs.getInt("loops", 0)              // 循环轮数，0 = 无限

    var running = false
        private set
    private var pointIndex = 0
    private var tapWithinPoint = 0
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
        val settingsBtn = panelButton("⚙") { showGlobalSettings() }
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
        dismissDialog()
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
        if (::countLabel.isInitialized) countLabel.text = "${items.size}点"
    }

    // ==================== 点位标记 ====================

    /** 在屏幕中央附近添加一个新点位（略微错开，避免完全重叠） */
    private fun addMarkerAtCenter() {
        val dm = service.resources.displayMetrics
        val offset = dp(28) * items.size
        addMarker(
            dm.widthPixels / 2 + offset % dp(112),
            dm.heightPixels / 2 + offset % dp(112),
            PointConfig(delayMs = defaultDelayMs)
        )
        saveMarkers()
    }

    private fun addMarker(centerX: Int, centerY: Int, config: PointConfig) {
        val marker = TextView(service).apply {
            text = (items.size + 1).toString()
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
        val item = MarkerItem(marker, config)
        // 轻点标记打开该点的单独设置
        marker.setOnClickListener { showPointSettings(item) }
        makeDraggable(marker, marker, onDragEnd = { saveMarkers() }) { lp }
        wm.addView(marker, lp)
        items.add(item)
        updateCountLabel()
    }

    private fun removeLastMarker() {
        val last = items.removeLastOrNull() ?: return
        runCatching { wm.removeView(last.view) }
        saveMarkers()
        updateCountLabel()
    }

    private fun removeMarker(item: MarkerItem) {
        if (!items.remove(item)) return
        runCatching { wm.removeView(item.view) }
        renumberMarkers()
        saveMarkers()
        updateCountLabel()
    }

    private fun renumberMarkers() {
        items.forEachIndexed { i, it -> it.view.text = (i + 1).toString() }
    }

    private fun removeAllMarkerViews() {
        items.forEach { runCatching { wm.removeView(it.view) } }
        items.clear()
        updateCountLabel()
    }

    /** 运行时把标记设为不可触摸并半透明，让注入的点击穿透到游戏 */
    private fun setMarkersClickThrough(clickThrough: Boolean) {
        items.forEach { item ->
            val lp = item.view.layoutParams as WindowManager.LayoutParams
            lp.flags = if (clickThrough) {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            item.view.alpha = if (clickThrough) 0.35f else 1f
            runCatching { wm.updateViewLayout(item.view, lp) }
        }
    }

    private fun markerCenter(v: View): Pair<Float, Float> {
        val lp = v.layoutParams as WindowManager.LayoutParams
        return (lp.x + markerSizePx / 2f) to (lp.y + markerSizePx / 2f)
    }

    // ---- 持久化 ----
    // 格式: x,y,tapCount,tapIntervalMs,delayMs | x,y,... （兼容旧版 x,y 格式）

    private fun saveMarkers() {
        val s = items.joinToString("|") { item ->
            val (cx, cy) = markerCenter(item.view)
            val c = item.config
            "${cx.roundToInt()},${cy.roundToInt()},${c.tapCount},${c.tapIntervalMs},${c.delayMs}"
        }
        prefs.edit().putString("points", s).apply()
    }

    private fun restoreMarkers() {
        val s = prefs.getString("points", null)?.takeIf { it.isNotBlank() } ?: return
        s.split("|").forEach { entry ->
            val p = entry.split(",")
            val x = p.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val y = p.getOrNull(1)?.toIntOrNull() ?: return@forEach
            val config = PointConfig(
                tapCount = (p.getOrNull(2)?.toIntOrNull() ?: 1).coerceIn(1, 10_000),
                tapIntervalMs = (p.getOrNull(3)?.toLongOrNull() ?: 100L).coerceIn(20, 3_600_000),
                delayMs = (p.getOrNull(4)?.toLongOrNull() ?: defaultDelayMs).coerceIn(20, 3_600_000)
            )
            addMarker(x, y, config)
        }
    }

    // ==================== 点击循环 ====================

    fun startClicking() {
        if (running) return
        if (items.isEmpty()) {
            toast("请先用 ➕ 添加点击位置")
            return
        }
        running = true
        pointIndex = 0
        tapWithinPoint = 0
        loopsLeft = loopCount
        dismissDialog()
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
            if (!running || items.isEmpty()) return
            if (pointIndex >= items.size) pointIndex = 0
            val item = items[pointIndex]
            val (cx, cy) = markerCenter(item.view)
            service.tap(cx, cy, tapDurationMs) {
                if (!running) return@tap
                val cfg = item.config
                tapWithinPoint++
                var delay: Long
                if (tapWithinPoint < cfg.tapCount) {
                    // 同一个点还没点够次数：用该点的连点间隔
                    delay = cfg.tapIntervalMs
                } else {
                    // 该点完成，进入下一个点：用该点的“点完后延迟”
                    tapWithinPoint = 0
                    pointIndex++
                    if (pointIndex >= items.size) {
                        pointIndex = 0
                        if (loopCount > 0 && --loopsLeft <= 0) {
                            stopClicking()
                            toast("已完成 $loopCount 轮点击")
                            return@tap
                        }
                    }
                    delay = cfg.delayMs
                }
                if (jitterMs > 0) delay += Random.nextLong(-jitterMs, jitterMs + 1)
                handler.postDelayed(this, delay.coerceAtLeast(tapDurationMs + 5))
            }
        }
    }

    // ==================== 设置弹窗 ====================

    private fun makeRow(label: String, value: String): Pair<LinearLayout, EditText> {
        val ctx = service
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

    private fun showDialog(title: String, rows: List<View>, buttons: List<View>) {
        dismissDialog()
        val ctx = service
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            buttons.forEach { addView(it) }
        }
        val dialog = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.parseColor("#EE263238"), dp(14).toFloat())
            setPadding(dp(18), dp(14), dp(18), dp(10))
            addView(TextView(ctx).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(6))
            })
            rows.forEach { addView(it) }
            addView(buttonRow)
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
        dialogView = dialog
    }

    private fun dismissDialog() {
        dialogView?.let { runCatching { wm.removeView(it) } }
        dialogView = null
    }

    /** 全局设置：按压时长、抖动、循环轮数、新点位默认延迟 */
    private fun showGlobalSettings() {
        stopClicking()
        val (r1, delayEdit) = makeRow("新点位默认延迟 (毫秒)", defaultDelayMs.toString())
        val (r2, durationEdit) = makeRow("按压时长 (毫秒)", tapDurationMs.toString())
        val (r3, jitterEdit) = makeRow("随机抖动 ±(毫秒)", jitterMs.toString())
        val (r4, loopsEdit) = makeRow("循环轮数 (0=无限)", loopCount.toString())

        showDialog(
            "全局设置（轻点圆点可单独设置每个点）",
            listOf(r1, r2, r3, r4),
            listOf(
                panelButton("取消") { dismissDialog() },
                panelButton("保存") {
                    defaultDelayMs = (delayEdit.text.toString().toLongOrNull() ?: defaultDelayMs).coerceIn(20, 3_600_000)
                    tapDurationMs = (durationEdit.text.toString().toLongOrNull() ?: tapDurationMs).coerceIn(1, 5_000)
                    jitterMs = (jitterEdit.text.toString().toLongOrNull() ?: jitterMs).coerceIn(0, 10_000)
                    loopCount = (loopsEdit.text.toString().toIntOrNull() ?: loopCount).coerceIn(0, 1_000_000)
                    prefs.edit()
                        .putLong("interval", defaultDelayMs)
                        .putLong("duration", tapDurationMs)
                        .putLong("jitter", jitterMs)
                        .putInt("loops", loopCount)
                        .apply()
                    dismissDialog()
                    toast("全局设置已保存")
                }
            )
        )
    }

    /** 单个点位设置：点击次数、连点间隔、点完后延迟 */
    private fun showPointSettings(item: MarkerItem) {
        if (running) return
        val num = items.indexOf(item) + 1
        val c = item.config
        val (r1, countEdit) = makeRow("点击次数 (每轮)", c.tapCount.toString())
        val (r2, intervalEdit) = makeRow("连点间隔 (毫秒)", c.tapIntervalMs.toString())
        val (r3, delayEdit) = makeRow("点完后延迟 (毫秒)", c.delayMs.toString())

        showDialog(
            "点位 $num 设置",
            listOf(r1, r2, r3),
            listOf(
                panelButton("删除") {
                    dismissDialog()
                    removeMarker(item)
                    toast("已删除点位 $num")
                },
                panelButton("取消") { dismissDialog() },
                panelButton("保存") {
                    c.tapCount = (countEdit.text.toString().toIntOrNull() ?: c.tapCount).coerceIn(1, 10_000)
                    c.tapIntervalMs = (intervalEdit.text.toString().toLongOrNull() ?: c.tapIntervalMs).coerceIn(20, 3_600_000)
                    c.delayMs = (delayEdit.text.toString().toLongOrNull() ?: c.delayMs).coerceIn(20, 3_600_000)
                    saveMarkers()
                    dismissDialog()
                    toast("点位 $num 设置已保存")
                }
            )
        )
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
