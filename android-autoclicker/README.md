# 拾取连点器(火炬之光:无限)

固定坐标的拾取按钮检测器:定时截屏,判断那个位置是不是变成了金色钱袋,
是就自动点一下,不是就不点。**不需要 Root、不需要录屏授权、不需要连电脑。**
要求 Android 11 (API 30) 及以上。

## 怎么编译成 APK

需要一台电脑装 [Android Studio](https://developer.android.com/studio)。

推荐做法(最省事):
1. Android Studio → New Project → **Empty Views Activity**(语言选 Kotlin,
   包名填 `com.example.autopick`,Minimum SDK 选 **API 30**)。
2. 用本项目里的同名文件覆盖新工程里的对应文件:
   - `app/src/main/AndroidManifest.xml`
   - `app/src/main/java/com/example/autopick/MainActivity.kt`
   - `app/src/main/java/com/example/autopick/AutoPickService.kt`(新增)
   - `app/src/main/res/xml/accessibility_config.xml`(新增)
   - `app/src/main/res/values/strings.xml`
   - `app/build.gradle.kts`(对照检查 minSdk / 依赖即可)
3. 删掉自动生成的 `activity_main.xml` 引用(本工程界面是纯代码写的,不用布局文件)。
4. 手机开 USB 调试连上,点 Run,直接装到手机。

或者:把本文件夹整个用 Android Studio “Open”,它会自动补上 Gradle Wrapper 再编译。

## 使用步骤

1. 装好后打开 App:
   - 点【授予悬浮窗权限】→ 打开开关
   - 点【打开无障碍设置】→ 找到“拾取连点器”→ 启用
2. **先校准坐标**(重要):
   - 开发者选项 → 打开“指针位置”
   - 进游戏(横屏),让拾取按钮出现,手指按住它中心,记下顶部显示的 X / Y
   - 把 `AutoPickService.kt` 里的 `TARGET_X` / `TARGET_Y` 改成这两个值,重新编译
3. 进游戏,把悬浮的 ▶ 按钮拖到不挡视线的地方。
4. **校准颜色**:长按悬浮按钮,会弹出目标点当前的 RGB 和“判定金色”结果。
   - 按钮出现时应显示 `判定金色=true`,不出现时应为 `false`
   - 若不准,调整 `isGolden()` 里的阈值,或微调坐标
5. 单击悬浮按钮:▶ 开始 / ■ 停止。

## 可调参数(都在 AutoPickService.kt 顶部)

| 参数 | 含义 |
|------|------|
| `TARGET_X / TARGET_Y` | 拾取按钮中心坐标(横屏) |
| `SAMPLE` | 采样方块半径,越大越稳但越慢 |
| `INTERVAL` | 检测间隔(ms),takeScreenshot 约 1 秒上限,别调太小 |
| `TAP_COOLDOWN` | 点击冷却,防同一次连点 |

## 说明与风险

- `takeScreenshot()` 系统有约每秒一次的限流,所以反应最快约 1 秒一次——
  对“捡装备”这种需求完全够用。
- 火炬之光:无限是联网游戏,官方条款通常禁止自动化工具,理论上有封号风险,
  自行权衡。
