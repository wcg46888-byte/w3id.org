package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：通过 dispatchGesture 注入点击手势（无需 Root），
 * 同时承载悬浮控制面板（TYPE_ACCESSIBILITY_OVERLAY，无需额外的悬浮窗权限）。
 */
class AutoClickService : AccessibilityService() {

    companion object {
        /** 服务运行时的单例引用，供 MainActivity 查询状态和控制面板 */
        @Volatile
        var instance: AutoClickService? = null
            private set
    }

    private var overlay: OverlayController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayController(this)
        overlay?.showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要监听任何事件，仅使用手势注入能力
    }

    override fun onInterrupt() {
        overlay?.stopClicking()
    }

    override fun onDestroy() {
        instance = null
        overlay?.destroy()
        overlay = null
        super.onDestroy()
    }

    /** 显示 / 隐藏悬浮面板 */
    fun togglePanel() {
        overlay?.togglePanel()
    }

    /**
     * 在屏幕坐标 (x, y) 注入一次点按。
     * @param durationMs 按压时长（毫秒）
     * @param onDone 手势完成（或被取消）后的回调，在主线程执行
     */
    fun tap(x: Float, y: Float, durationMs: Long, onDone: () -> Unit) {
        val path = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone()
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone()
        }, null)
        if (!dispatched) onDone()
    }
}
