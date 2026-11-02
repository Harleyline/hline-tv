# 1.5：包体、刷新率与效果档（2026-11-02）

## 结论

1. **安装包 16.3 MB → 10.8 MB（−5.5 MB，−34%）**。省下的几乎全是字体：内置的 Noto Sans JP / KR
   原来是带 100–900 九个字重的可变字体（9.6 MB、10.4 MB），而界面所有样式只请求 W400（`Type`，
   1.72 起统一）。实例化成单个 400 后是 5.4 MB、5.8 MB，**字形一个没少**。
2. **壁纸 JPEG → WebP**（质量 92），2.24 MB → 1.40 MB。assets 在 APK 里是不压缩存储的，所以这一项
   一比一变成安装包的缩小。
3. **120 Hz 只给撑得住的电视**：新增 `DeviceClass.capable`（非低端、≥2 GB、≥4 核、OpenGL ES 3），
   达标的才把界面切到面板最高刷新率（上限 120 → 144 Hz）；Android 12 以前没有「无缝切换」的
   信息，只给 `preferredRefreshRate` 提示，不换模式（索尼 2021 年后的机型多是 Android 10）。
4. **没有新增动画**。原因见最后一节：这台机器上没有可验收的电视，加了也验不了。

## 一、包体

改前改后（APK 内实际占用，压缩后）：

| 项 | 1.3（146） | 1.5（147） | 变化 |
|---|---|---|---|
| **APK 合计** | 16.32 MB | **10.83 MB** | **−5.49 MB（−34%）** |
| 字体 | 11.42 MB | 6.74 MB | −4.68 MB |
| assets（壁纸、城市表、网页、影片目录） | 2.35 MB | 1.53 MB | −0.82 MB |
| dex | 2.03 MB | 2.03 MB | 0 |
| resources.arsc | 0.41 MB | 0.41 MB | 0 |

字体逐项：

| 文件 | 字体 | 原始 | 压缩后 |
|---|---|---|---|
| `res/eG.ttf` | Noto Sans JP | 9 589 900 → 5 527 K | 5 852 K → 3 208 K |
| `res/eT.ttf` | Noto Sans KR | 10 414 588 → 5 949 K | 5 332 K → 2 915 K |
| `res/SR.ttf` | Inter | 856 K（未动） | 447 K |
| `res/N_.ttf` | Noto Serif Tibetan | 662 K（未动） | 254 K |
| `res/by.ttf` | Noto Sans Arabic | 166 K（未动） | 74 K |

### 怎么做的

```bash
# 单字重 + 去掉竖排表（Compose 不排竖排 CJK）
fonttools varLib.instancer noto_sans_jp.ttf wght=400 --update-name-table -o jp_w400.ttf
fonttools subset jp_w400.ttf --unicodes='*' --drop-tables+=vmtx,vhea,VORG \
  --layout-features='*' --name-IDs='*' -o noto_sans_jp.ttf
```

代码侧（`ui/theme/Theme.kt`）：`cjkFonts()` 原来为每个字重造一个 `Font(res, W100..W900,
variationSettings=…)`，现在是一个 `Font(res, W400)`。顺带少了 8 个字体对象，`FontWarmup` 和
首帧的文字测量少解析 8 次（这是 1.48 那批把首帧文字测量从 58 ms 压到 4 ms 的路径之一）。

### 为什么这样是安全的

- **覆盖没变**：JP 17 936 → 17 808 字形、KR 24 964 → 24 868 字形，少的只是竖排专用的副本；
  假名、谚文、汉字（含 JIS 外的简体字，例如「气」）都在。App 名字是用户数据，用的是同一套字形表。
- **视觉没变**：400 这个实例和原来 `wght=400` 解析出来的轮廓是同一套，只是把可变轴的 delta 合并掉。
  界面从来不请求 400 以外的 CJK 字重（`Type` 全部 W400；`HomeChrome` 里两处 W500 用在拉丁
  副标题上，走 Inter）。
- **老系统不受影响**：`cjkFonts` 仍然在 Android 8.0 以下返回空表，那些电视本来就用系统字体排
  CJK。
- 已用 FreeType 渲染样张核对：「天気予報 アニメ 設定 東京 漢字 / 날씨 설정 서울 한국어 /
  Weather 12:45 21° 天气 Настройки」全部正常，没有豆腐块。

回滚：把这两个 ttf 换回 `git checkout` 里的版本，`cjkFonts` 恢复成 9 个字重，其它都不用动。

### 壁纸

五张照片 1920×1080，原 JPEG q≈85；改成 WebP 质量 92：2 244 K → 1 396 K。`city_lights` 是夜景
噪声图，从 646 K 只降到 639 K（这一类图 WebP 没什么优势），换格式是为了统一；另外四张省 40–60%。
`Gradients.photo()` 改成读 `wallpapers/$name.webp`，BitmapFactory 从 Android 4.0 起就认 WebP。

## 二、刷新率与效果档

改之前：`preferSmoothestRefreshRate()` 在 Android 12 以上、非 Lite 时，挑一个同分辨率、可无缝
切换的更高刷新率模式，上限 120 Hz；Android 11 及以下直接返回，一点不做。

改之后：

1. 先过 `DeviceClass.capable(context)`：非低端（不走 `DeviceClass.lowEnd`）、内存 ≥ 2 GB、
   核心 ≥ 4、`reqGlEsVersion` ≥ 3.0。**帧数翻倍就是 GPU 的活翻倍**，两核小内存的机器换成 120 Hz
   只会隔帧掉，看起来比 60 Hz 更差。
2. Android 12+：在「同分辨率 + `alternativeRefreshRates` 认可」的模式里取最高，上限 144.5 Hz
   （原来 120.5，覆盖 144 Hz 面板）。
3. Android 11 及以下：只设 `preferredRefreshRate` 提示，**不换模式**。这一代系统不会告诉你哪个
   切换是无缝的，换模式在不少电视上是一秒黑屏，每次回首页都黑一次——这是 1.51 已经踩过的坑。
   索尼 2021 年后的 BRAVIA（Android 10）就是这一类：面板 120 Hz，界面常年 60 Hz，这条提示是
   它们唯一能接受的做法。

诊断报告 `Effects` 行现在带 `capable=`，例如
`low-end=false capable=true (low-RAM flag false, 3789 MB, 8 cores, GLES 3.2, memory class 512 MB)`，
慢机型反馈时能直接看出是哪一档被挡下。

「满特效」这次落在三件可验证的事上：完整效果（玻璃、视差、阴影、灯带）、面板最高刷新率、
`capable` 判定。**没有**打开 4K 画布：MT9655 的实测是硬件合成器照样 1080p 送显（#607、
`docs/HANDOFF.md`），开了只是多花半个核。

## 三、没做的，以及为什么

| 想省 / 想加 | 结论 |
|---|---|
| Inter 也做子集 | 收到 Latin + 西里尔 + 标点也只从 856 K 降到 619 K（gvar 里是 `opsz`×`wght` 两轴的交点，砍不动），省 24 万，风险是漏字（希腊、越南语、上下标、货币符号）。不值 |
| `cities-cn.tsv` 249 K → gzip 约 70 K | 要引入 `GZIPInputStream`，而这份表同时给手机页用，省 18 万。留作可选 |
| 4K 画布自动打开 | 见上，实测无收益 |
| 新增「真玻璃模糊」（Android 12+ `RenderEffect`） | 这是「满特效」最想要的东西，但每帧多一次全屏离屏模糊，**只能在真机上定档**。没有电视可验，不加 |
| 磨砂图 320×180 提到 480×270 | 玻璃会更细，但要改烘焙缓存键（旧缓存留在 `files/` 里），且同样需要真机看效果 |

## 四、验证

- `:app:assembleStandardRelease`、`:app:assembleGuardRelease` 均通过；产物 11 357 429 / 11 356 422 字节。
- **102 个单元测试全部通过**（顺手修了 `AerialCaptionTest`：航拍地名改成中文/拉丁两套字幕后
  这个测试没跟着改，main 上本来就编译不过）。
- `lintVitalStandardRelease` 通过；字体样张用 FreeType 渲染核对过。
- **没做的**：真机。小米 MFTR0、TCL MT5879 都不在这台机器上，所以字体观感、120 Hz、索尼的安装
  路径都只有静态依据。发版前建议在两台上各装一次（`BUILD=1 tools/install.sh`），重点看：首页
  与日/韩界面文字是否与 1.3 一致、诊断 `Effects` 行的 `capable=`、以及刷没刷新率有没有变化。
