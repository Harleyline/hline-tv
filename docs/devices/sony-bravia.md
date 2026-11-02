# 索尼 BRAVIA（Sony）

结论先说：**能装**。2015 年至今的安卓 BRAVIA（Android TV 5.0 → Google TV 14）都满足
HLine TV 的 minSdk 21，装法和其他安卓电视一样，用 `tools/install.sh` 或 `adb install`。
真正会挡路的不是系统版本，而是索尼自己的三件事：

1. **未知来源**默认关着（Android 8 起是按来源授权，不是一个总开关）；
2. **2021 年以后的机型多数只有「无线调试」**，没有 USB 调试/网络调试开关，要用配对码；
3. **索尼桌面的接管和省电策略**：`com.sony.dtv.tvlaunchercustomizer` 会在开机后把首页拉回索尼
   自己的界面，而「节能」把待机时的后台和网络一起关掉。

下面按代写清楚。**本文没有在索尼真机上逐条复验过**，依据是本仓库 issue 里报告人贴出的日志
（每处标了 issue 号）、索尼官方帮助页的菜单路径，以及下面「索尼上已知的差异」一节里那些
已经在代码里落地、注释里记了机型的处理。

## 代际与已知机型

| 代 | 系统 | 芯片 | 报告过的机型（`ro.product.model`） | 备注 |
|---|---|---|---|---|
| 2015–2017 | Android 5.0.2 / 6.0 / 7.0 / 8.0 | MT5891 / MT5893 | `BRAVIA_ATV3_4K`（KD-55X，4K，**1.5 GB 内存**） | 内存 1.5 GB，HLine TV 自动判为低端，效果走「流畅」 |
| 2018–2020 | Android 8.0 / 9.0 | MT5893 / MT5895 | `BRAVIA 4K GB`（#840）、A8G、X90J（#20）、85X95EK（#659） | 这一代的菜单是「设备偏好设置 → 安全和限制」 |
| 2021+ | Google TV，Android 10 / 12 / 14 | MT5895 / XR | `BRAVIA VH1`（#2）、`BRAVIA 4K AE2`（#433）、X90K（#645）、XR90（#259） | 多数只有无线调试（#747） |

## 一、先打开 ADB

**Android 8 及以前（2015–2018 年机型）**

1. 设置 → 关于 → 连按「内部版本号」7 次（不是「Android 版本」）
2. 回到设置 → 开发者选项 → 打开 **USB 调试**（有的固件叫「网络调试」/「ADB 调试」）
3. 电视的 IP：设置 → 网络 → 已连接的网络

**Android 9 及以后（2019–2020）** 路径多一层「设备偏好设置」：
设置 → 设备偏好设置 → 关于 → 内部版本号 ×7 → 设备偏好设置 → 开发者选项 → USB 调试。

**2021+ Google TV**：设置 → 系统 → 关于 → 连按「Android TV OS 版本」或「版本号」7 次 →
设置 → 系统 → 开发者选项。

**只有「无线调试」的机器（Android 11+，例如 Android 14 的 BRAVIA，#747）**：
开发者选项 → 无线调试 → 使用配对码配对设备，然后：

```bash
adb pair <电视IP>:<配对屏上的端口>      # 输入电视上那 6 位码
adb connect <电视IP>:<无线调试主页上的端口>   # 注意和上一步不是一个端口
adb tcpip 5555                          # 之后固定用 5555，不用再配对
```

`tools/install.sh` 会引导这两步（脚本里 `ADB_PAIR`/`ADB_CODE`/`ADB_CONNECT`，或者交互式输入）。

## 二、允许安装

索尼的「未知来源」从 Android 8 起是**按来源授权**的（哪个 App 被允许发起安装），ADB 安装不受它限制，
但用 U 盘 + 文件管理器、或者用「下载器」类 App 安装时会撞上它：

- Android 6/7：设置 → 安全和限制 → **未知来源**，打开
- Android 8/9：设置 →（设备偏好设置 →）**安全和限制 → 未知来源**，在列表里给对应的文件管理器/下载器打开
  （索尼官方帮助页的原文就是这条路径，型号不同菜单名略有差异）
- Google TV：设置 → 应用 → 安全和限制 → 未知来源 → 选对应的 App
- 装的如果是**手机版文件管理器**，它可能不出现在这个列表里——用 ADB 装最省事

## 三、安装

```bash
TV=192.168.1.30 tools/install.sh            # 标准版
TV=192.168.1.30 tools/install.sh guard      # 标准版装不上、或首页被索尼拉回时
```

- **standard**：正常声明 HOME 的桌面。索尼允许第三方桌面，`cmd package set-home-activity` 就能接管，
  这一版在大多数 BRAVIA 上够用。
- **guard**：完全不声明 HOME（避开「拒绝安装声明 CATEGORY_HOME 的包」这类固件），改由无障碍服务
  应答 Home 键。用在两种情况下：standard 报 `INSTALL_FAILED_...`；或者装好以后
  **开机/待机唤醒后首页又回到索尼自己的界面**（`com.sony.dtv.tvlaunchercustomizer`）。

装完按提示设为默认桌面。索尼上还可以手动指定一次，避免第一次按 Home 弹出选择框：

```bash
adb shell cmd package set-home-activity app.orchard.tv/app.orchard.tv.MainActivity
```

## 四、索尼上要顺手改的一项系统设置：节能

设置 → 系统 → 电源（或「节能」）→ **节能模式：关**（有的固件叫「电视待机」而不是「关机」）。
索尼在「节能/深度待机」下会断网并冻结后台，HLine TV 的守护和屏保就活不下来，ADB 也连不上
（这台待机时网络休眠，唤不醒，要遥控器开机——TCL 上已经踩过一次，见 `docs/HANDOFF.md`）。
默认的「电视待机」不会影响保修，也不改动任何系统应用。

## 五、索尼上已知的差异（都在代码里处理过）

| 现象 | 处理 | 出处 |
|---|---|---|
| 控制中心调亮度：系统亮度值变了，屏幕背光不动 | 索尼走的是自己的图像设置，`DISPLAY_SETTINGS` 解析到 `com.sony.dtv.configsettings`，控制中心直接打开索尼的图像菜单 | `system/Brightness.kt`，BRAVIA VH1 / Android 12 / #2 |
| 默认没有按键音 | 索尼固件常把系统按键音关掉，HLine TV 的界面音默认用自带音效 | `settings/AerialSettings.kt` 的 `UiSounds`，#160 #169 |
| 语音搜索的虚拟输入设备 `virtual-search` 自称是手柄 | 守护的按键过滤把它当虚拟设备放行，否则按 Home 会走错分支 | `guard/HomeGuardService.kt`，#840 |
| 4K 面板但界面层只有 1080p | 4K 画布（`UhdCanvas`）在 MT5895 上默认不开：1.51 按「显示模式是界面 2 倍」自动开，X90K 上控制中心和网格掉帧、4K 屏保缩在左上角 | `ui/components/UhdCanvas.kt`，#645 |
| 两个调谐器同名 | 标成「模拟/数字电视」区分 | `system/SystemState.kt`，#433 |
| 切信号源只换了画面，没切输入 | 索尼走 `TvInputManager` 的 session，代码里单独处理 | `system/SystemState.kt`，#259 |
| 「视频」屏保放完一遍退回照片 | 8 秒没有新画面就把这段移出队列；索尼 85X95EK 报告过，修了但**没在索尼上复验** | `ui/home/VideoSaver.kt`，#659 |

## 六、120 Hz 与效果档

- BRAVIA XR 的面板是 120 Hz，但系统给界面的模式常是 60 Hz。Aerial 在 **Android 12+** 上会挑一个
  「同分辨率、可无缝切换」的更高刷新率模式（`alternativeRefreshRates`）；索尼 2021+ 多数还是
  Android 10，用不上这条路，只能给系统一个 `preferredRefreshRate` 提示，由固件决定。
- 内存 1.5–2 GB 的老 BRAVIA 会被 `DeviceClass` 判成低端，`效果：自动` 落到「流畅」。想要玻璃和视差，
  在个性化里手动选「完整」，但这代机的 GPU 通常撑不住，会掉帧。

## 七、卸载与还原

```bash
tools/restore.sh --uninstall
```

索尼不需要关掉任何系统应用，也不需要动 `com.sony.dtv.*`。如果之前为了省事停用过索尼的推广包
（`com.sony.dtv.sonyselect`、`com.sony.dtv.mysony` 之类），那些**不是本项目做的**，`restore.sh` 不会碰，
要自己在设置 → 应用里恢复。
