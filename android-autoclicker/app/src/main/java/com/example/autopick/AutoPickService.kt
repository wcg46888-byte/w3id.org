package com.example.autopick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.Toast
import kotlin.math.hypot

/**
 * 拾取按钮自动点击无障碍服务。
 *
 * 原理:定时用无障碍自带的 takeScreenshot() 截屏,读取“拾取按钮固定坐标”那一小块
 * 像素;如果判定为金色(钱袋颜色)就 dispatchGesture 点一下,否则不点。
 *
 * 只需两个权限:无障碍(点击 + 截屏) + 悬浮窗(显示开关)。不需要 Root,
 * 不需要录屏权限弹窗,不需要连电脑。要求 Android 11(API 30)及以上。
 */
class AutoPickService : AccessibilityService() {

    companion object {
        // ============ 需要你校准的参数(横屏坐标) ============
        // 拾取按钮中心的 X / Y。用开发者选项“指针位置”按住按钮读出来后填这里。
        var TARGET_X = 1180
        var TARGET_Y = 520

        // 采样范围半径(在中心点附近取一个小方块做多点采样,更稳)
        const val SAMPLE = 8

        // 检测间隔(毫秒)。takeScreenshot 大约每秒一次上限,900ms 比较稳。
        const val INTERVAL = 900L

        // 点击后的冷却时间(毫秒),避免同一次出现被连点多次
        const val TAP_COOLDOWN = 600L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var busy = false
    private var lastTap = 0L

    private var wm: WindowManager? = null
    private var overlay: View? = null

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        handler.post(loop)
    }

    // ---------- 主循环 ----------
    private val loop = object : Runnable {
        override fun run() {
            if (running && !busy) checkAndTap()
            handler.postDelayed(this, INTERVAL)
        }
    }

    private fun checkAndTap() {
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    var hw: Bitmap? = null
                    var soft: Bitmap? = null
                    try {
                        hw = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)
                        // 硬件位图不能直接 getPixel,复制成软件位图
                        soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        if (soft != null && isGolden(soft, TARGET_X, TARGET_Y)) {
                            tap(TARGET_X.toFloat(), TARGET_Y.toFloat())
                        }
                    } finally {
                        soft?.recycle()
                        hw?.recycle()
                        res.hardwareBuffer.close()
                        busy = false
                    }
                }

                override fun onFailure(errorCode: Int) {
                    busy = false // 常见:截屏太频繁被限流,忽略本次
                }
            })
    }

    /** 判断中心小块是否为“金色”(钱袋颜色)。用色彩启发式,不依赖精确 RGB。 */
    private fun isGolden(bmp: Bitmap, cx: Int, cy: Int): Boolean {
        var hit = 0
        var total = 0
        var d = -SAMPLE
        while (d <= SAMPLE) {
            var e = -SAMPLE
            while (e <= SAMPLE) {
                val x = cx + d
                val y = cy + e
                if (x in 0 until bmp.width && y in 0 until bmp.height) {
                    val c = bmp.getPixel(x, y)
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    total++
                    // 金色:红高、绿中偏高、蓝低、红明显大于蓝
                    if (r > 150 && g > 90 && b < 110 && r - b > 55 && g > b) hit++
                }
                e += 3
            }
            d += 3
        }
        return total > 0 && hit.toFloat() / total > 0.45f
    }

    private fun tap(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        if (now - lastTap < TAP_COOLDOWN) return
        lastTap = now
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ---------- 悬浮开关 ----------
    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先在应用里授予悬浮窗权限", Toast.LENGTH_LONG).show()
            return
        }
        val btn = Button(this).apply {
            text = "▶"
            alpha = 0.75f
            setOnClickListener {
                running = !running
                text = if (running) "■" else "▶"
                Toast.makeText(context, if (running) "已开始拾取" else "已停止", Toast.LENGTH_SHORT).show()
            }
            // 长按:采样当前坐标的颜色,用来校准(会 Toast 出 RGB 和是否判为金色)
            setOnLongClickListener { sampleColor(); true }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 300
        }
        makeDraggable(btn, lp)
        overlay = btn
        wm?.addView(btn, lp)
    }

    /** 校准辅助:截一帧,报告目标点的 RGB 与判定结果。 */
    private fun sampleColor() {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    if (soft != null) {
                        val x = TARGET_X.coerceIn(0, soft.width - 1)
                        val y = TARGET_Y.coerceIn(0, soft.height - 1)
                        val c = soft.getPixel(x, y)
                        val msg = "屏幕${soft.width}x${soft.height}\n" +
                            "点($TARGET_X,$TARGET_Y) RGB=${Color.red(c)},${Color.green(c)},${Color.blue(c)}\n" +
                            "判定金色=${isGolden(soft, TARGET_X, TARGET_Y)}"
                        handler.post {
                            Toast.makeText(this@AutoPickService, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                    soft?.recycle()
                    hw?.recycle()
                    res.hardwareBuffer.close()
                }

                override fun onFailure(errorCode: Int) {}
            })
    }

    // ---------- 让悬浮按钮可拖动(拖动时不触发点击) ----------
    private var downX = 0f
    private var downY = 0f
    private var originX = 0
    private var originY = 0
    private var dragging = false

    private fun makeDraggable(v: View, lp: WindowManager.LayoutParams) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    originX = lp.x; originY = lp.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragging || hypot(dx.toDouble(), dy.toDouble()) > 20) {
                        dragging = true
                        lp.x = originX + dx.toInt()
                        lp.y = originY + dy.toInt()
                        wm?.updateViewLayout(view, lp)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> dragging // 拖过就吞掉,避免误触发点击
                else -> false
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        overlay?.let { runCatching { wm?.removeView(it) } }
        overlay = null
    }
}
