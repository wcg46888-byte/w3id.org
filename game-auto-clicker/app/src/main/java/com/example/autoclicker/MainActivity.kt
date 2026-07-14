package com.example.autoclicker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * 主界面：引导用户开启无障碍服务，并控制悬浮面板的显示 / 隐藏。
 * 实际的连点操作全部由 AutoClickService + OverlayController 在悬浮层完成。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.main_title)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A237E"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })

        statusText = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#EEEEEE"))
            }
        }
        root.addView(statusText)

        root.addView(space(16))

        root.addView(Button(this).apply {
            text = getString(R.string.btn_open_accessibility)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(space(8))

        root.addView(Button(this).apply {
            text = getString(R.string.btn_toggle_panel)
            setOnClickListener {
                val service = AutoClickService.instance
                if (service == null) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_need_service),
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    service.togglePanel()
                }
            }
        })

        root.addView(space(20))

        root.addView(TextView(this).apply {
            text = getString(R.string.main_steps)
            textSize = 15f
            setTextColor(Color.parseColor("#37474F"))
            setLineSpacing(0f, 1.15f)
        })

        root.addView(space(20))

        root.addView(TextView(this).apply {
            text = getString(R.string.warning_note)
            textSize = 13f
            setTextColor(Color.parseColor("#BF360C"))
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val on = AutoClickService.instance != null
        statusText.text = getString(
            if (on) R.string.status_service_on else R.string.status_service_off
        )
        statusText.setTextColor(Color.parseColor(if (on) "#1B5E20" else "#B71C1C"))
    }

    private fun space(dpValue: Int) = TextView(this).apply {
        height = dp(dpValue)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
