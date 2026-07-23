package com.example.autopick

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 一个只用来引导授权的简单界面:
 *  1) 授予悬浮窗权限
 *  2) 打开无障碍设置,启用本服务
 * 授权完成后,游戏里会出现一个悬浮的 ▶ 按钮,点它开始/停止。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val tip = TextView(this).apply {
            text = "拾取连点器\n\n" +
                "步骤:\n" +
                "1. 点【授予悬浮窗权限】\n" +
                "2. 点【打开无障碍设置】并启用“拾取连点器”\n" +
                "3. 进游戏,拖动悬浮 ▶ 按钮到不挡视线处\n" +
                "4. 长按悬浮按钮可校准颜色,单击开始/停止\n\n" +
                "注意:先在 AutoPickService.kt 里把 TARGET_X / TARGET_Y\n" +
                "改成你机器上拾取按钮的坐标(开发者选项-指针位置读取)。"
            textSize = 15f
        }
        root.addView(tip)

        val overlayBtn = Button(this).apply {
            text = "授予悬浮窗权限"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        }
        root.addView(overlayBtn)

        val accBtn = Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(accBtn)

        setContentView(root)
    }
}
