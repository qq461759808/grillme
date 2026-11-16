# 开发笔记

写给下一个要改这个项目的人（很可能是一个没有上下文的新会话）。这里记的是**为什么这么做**，
以及**哪些地方一改就会出事**。产品说明看 `README.md`，发版说明看 `RELEASE_NOTES.md`。

---

## 0. 一句话现状

两端都已完成并发布在 https://github.com/qq461759808/grillme
（`v1.1.4` 最新，`v1.1.3` / `v1.1.2` / `v1.1.1` / `v1.1.0` / `v1.0.0` 是前五个版本）：
电脑端是单文件 Python（`grill.py`，约 3500 行）+ PyInstaller 打的 exe；
安卓端是纯 Java（15 个文件，无 AndroidX、无 Kotlin、无第三方库）。
四语言词表在 `lang/strings.json`，203 条 × zh/en/ja/ru。

1.1.4 修了三件事：

- **安卓：锁屏解锁后不弹窗**（用户报的）。锁屏时系统把悬浮窗摘掉，但
  `GrillOverlay.showing` 还是 true，`tickOnce()` 于是永远走"弹窗已经挂着"那条
  分支，时间涨到 167/60 也不弹新窗，切回 App 才补弹。修法：`isShowing()` 改成
  真去问窗口还在不在；`ACTION_SCREEN_OFF` 时主动 `dismiss()` 并记下欠一道题
  （详见 §6.23）。
- **电脑：收起形态 → 常驻形态**。不再有"收起/展开"这套来回切换，设置里直接选
  「悬浮条」还是「托盘图标」（`home_mode`），选完就一直是那个样子（§5.2）。
  老配置的 `collapse_tray` / `pill_collapsed` 会自动迁移。
- **电脑：托盘菜单的手感**。托盘模式下主窗口是 withdraw 的，而 Tk 的菜单必须挂在
  已映射的窗口上——这是"右键一下不出来、得点第二下"的根因。现在养了一个 1×1 的
  透明锚点当菜单的爹，另外加了一条 50ms 的轮询保证"点屏幕任意位置都能关掉"
  （§6.22）。

1.1.3 修的是 1.1.2 里"托盘图标点了没反应"：

- 用户报：收进托盘之后，图标在、悬停有提示，但左键/双击/右键全都没反应。
- 定位到：图标注册、消息循环、展开链路全部健康，**唯独 Shell 不投递点击回调**，
  根因没查到（详见 §6.19）。
- 修法：不再只靠 Shell——`NIM_SETVERSION(4)` 拿到图标矩形，
  主线程自己用 `GetAsyncKeyState` + `GetCursorPos` 判断有没有点它。
- `tray_debug.log` 改成默认就写，下次出问题不用再靠猜。

1.1.2 只动了电脑端的收起形态：

- **系统托盘图标回来了，而且做成设置项**：`collapse_tray=1`（默认）收进任务栏托盘，
  `0` 缩成右下角悬浮小方块。Win11 会把新图标塞进「^」，程序改注册表自动提到任务栏（§6.17）。
- 悬浮小方块从 32×32 的蓝底白圈圆环（用户说像"小眼睛"）改成 **24×24 的应用图标本体**。
- 上次收着的话，下次启动照原样收着（`pill_collapsed`）。
- 托盘点击这条路在这台机器上没法用合成鼠标验证（系统吞注入按键），
  是靠 `PostMessageW` 验证消息链路的——细节见 §6.17 最后一段。

1.1.0 相比 1.0.0 的三处变化（都记在 §6）：

- **单实例锁**——1.0.0 没有，双击两次 exe 就会出现两个进程各跑一套计时，
  也就是用户报的「两条时间线在并行」（§6.14）。
- **计时改成单调时钟增量**，不再「每次 tick 加 1」+「时间戳相减」两套账（§6.15）。
- **小条能收成右下角一个小图标**，设置里能关掉「我承认在摸鱼」，
  电脑端新增开机自启动。

1.1.1 修的是 1.1.0 交付时的四处"说好的没做到"：

- 小条收起后不再是"白框里一个点"，改成画出来的应用图标（§6.17）。
- 第二个实例重新弹出「已经在运行」的提示框（§6.14）。
- 安卓在 B 站/知乎里计时停滞、不弹窗——`sinceLast++` 改成真实时间增量（§6.22）。
- 安卓首页秒数 5 秒才跳一下（§6.19）。

---

## 1. 仓库地图

```
grill.py                 电脑端全部代码（单文件，见 §3）
lang/strings.json        唯一词表，两端共用（安卓通过 assets.srcDirs 打包）
docs/*.png               README 里的截图 + icon-512.png（图标的源头）
build/res/               打包用：grill.ico（由 icon-512.png 转）、version_info.txt
android/                 安卓工程（见 §4）
dist/                    产物，被 .gitignore 排除，只进 release 附件
启动电脑端.bat           双击跑源码（pythonw 静默启动）
```

**`lang/strings.json` 是唯一的文案来源。** 改文案只改这一个文件，两端都跟着变。
改完必须跑一遍校验（见 §7.1），它会查四语言是否齐全、`{0}` 占位符是否一致、有没有重复 key。

---

## 2. 核心机制

一句话：**随机把你拦下来，逼你打字说出「现在为什么必须做这件事」，才放你继续。**

三个不能动的设计：

1. **间隔随机**，在「最短–最长」之间取，不可预测。不显示倒计时。
2. **弹窗没有关闭按钮**，Alt+F4、Esc 都无效，必须打字提交。
3. **不拦截、不锁屏、不说教**，只记录。

电脑端判定"是否在用"的主信号是**系统最后输入时间**（`idle_seconds()`），
不是"有没有动窗口"——发呆、看视频、读长文都算在用。安卓拿不到全局最后输入时间，
退化成"屏幕亮着就算在用"。

---

## 3. 电脑端 `grill.py`

单文件约 3500 行，只依赖标准库（**没有第三方包，这是刻意的**，见 §8）。按段落分：

| 段落 | 职责 |
|---|---|
| `_resolve_data_dir()` / `ensure_dirs()` | 数据目录，`%APPDATA%\Grill`（旧名 `AskMe` 会自动搬过来） |
| `load_json` / `save_json` / `append_log` / `read_log` | 配置、状态、记录读写。**读一律 `utf-8-sig`**（§6.16） |
| `parse_hhmm` / `in_dnd` / `fmt_span` / `fmt_ago` | 时间工具 |
| `LASTINPUTINFO` / `idle_seconds` / `is_locked` / `is_fullscreen` | **Win32 状态探测**，ctypes 直调 user32 |
| `beep` | 响铃 |
| `foreground_app()` | 前台进程名 + 窗口标题 |
| `capture_image` / `capture_jpeg` | **截图，走 GDI+ 而不是 PIL**（见 §5.1） |
| `Usage` | 按应用累计时长 |
| `webdav_test` / `pick_rest_tip` | 同步与文案 |
| `Switch` / `ScrollFrame` / `PromptText` / `button` | 手写的 tkinter 小控件 |
| `AskPopup` | 质问弹窗 |
| `Pill` | 右下角小条。常驻形态二选一：一直挂着（`"pill"`）或只留托盘图标（`"tray"`），见 `mode()` / `apply_mode()`（§5.2） |
| `Tray` / `NOTIFYICONDATAW` / `_tray_icon` | 系统托盘图标。**独立线程 + 队列，不能挂 Python 窗口过程**（§6.17） |
| `build_pill_menu` / `popup_menu` / `_watch_menu_close` | 小条和托盘共用的右键菜单，以及"点屏幕任意位置都能关掉它"的轮询（§6.18） |
| `autostart_target` / `autostart_get` / `autostart_set` | 开机自启，写 `HKCU\...\Run` |
| `acquire_single_instance` / `poke_existing` | **单实例锁**（§6.14） |
| `AskMe` | 主程序：`tick()` 每秒跑一次 |
| `selftest()` / `main()` | 入口 |

**主循环只有一个**：`AskMe.tick()` 每秒被 `root.after(1000, ...)` 叫醒一次，
调 `_tick_once()` 判定状态。异常被 `try/except` 包住并写 `error.log`，
**保证 tick 自己不会断**（断了就再也不会弹窗了）。

`_tick_once()` 开头先算 `dt = time.monotonic() - self._last_tick`，
`active` / `since_last` / `session_secs` 三个量都加这一个 `dt`——
**不要改回「每秒 +1」**，见 §6.15。

数据文件（`%APPDATA%\Grill\`）：

| 文件 | 内容 |
|---|---|
| `config.json` | 设置项 |
| `state.json` | 计时状态：`day` / `active` / `since_last` / `session_secs` / `saved_at` / `next_due` |
| `wake.flag` | 第二个实例留下的记号，第一个实例 tick 发现后展开小条并删掉它 |
| `log.jsonl` | 一条一行，字段见下 |
| `usage/*.json` | 按应用累计 |
| `shots/*.jpg` | 弹窗前的截图 + `thumbs/` 缩略图 |
| `error.log` | 异常堆栈 |
| `tray_debug.log` | 托盘收到的每条消息 + 图标矩形 + 兜底判定（§6.18），每次启动清空 |

`log.jsonl` 一条的样子：

```json
{"ts": 1794802202.65, "time": "12:10", "day": "2026-11-16", "answer": "正在干正事呢",
 "kind": "normal", "active": 0, "n": 1, "id": "pc-1794802202652"}
```

`id` 形如 `pc-<毫秒时间戳>`，安卓是 `android-<...>`，同步时按 `id` 求并集，所以**两端不能撞 id**。

---

## 4. 安卓端

纯 Java，`compileSdk/targetSdk 36`、`minSdk 24`、AGP 8.13.2 + Gradle 8.14.7、JDK 21。

| 文件 | 行数 | 职责 |
|---|---|---|
| `GrillService.java` | 742 | 前台服务，计时与弹窗调度。**核心** |
| `SettingsActivity.java` | 769 | 设置页（30 项，每项带问号解释） |
| `MainActivity.java` | 566 | 首页：开关、倒计时、权限清单、最近记录 |
| `GrillOverlay.java` | 465 | 质问窗，用 `TYPE_APPLICATION_OVERLAY` 悬浮窗实现 |
| `Shots.java` | 370 | MediaProjection 截图，含暂停/恢复 |
| `Store.java` | 359 | SQLite，表结构刻意对齐电脑端 JSONL 字段 |
| `Ui.java` | 344 | 手写控件（卡片、分隔线、行、进度条） |
| `Cfg.java` | 275 | SharedPreferences 封装 |
| `StatsActivity.java` | 263 | 统计页 |
| `Sync.java` | 254 | WebDAV 同步 |
| `Http.java` | 224 | 手写 HTTP/WebDAV 客户端（不引 OkHttp） |
| `HistoryActivity.java` | 207 | 记录页 |
| `Notifier.java` | 125 | 常驻通知 |
| `Lang.java` | 107 | 读 `assets/strings.json` |
| `BootReceiver.java` | 27 | 开机自启 |

**为什么用悬浮窗而不是 Activity**：Android 10+ 禁止后台启动 Activity，
但 `TYPE_APPLICATION_OVERLAY` 的悬浮窗不受此限。这是整个安卓端能成立的前提，
**不要改成 Activity**，否则在后台永远弹不出来。

**弹窗必须能被"打字"放行**，所以悬浮窗里要放一个真正的 `EditText`，
这带来一个副作用：输入法窗口在悬浮窗之上，会吃掉键盘区域的点击（见 §6.4）。

---

## 5. 电脑端：几个必须知道的实现细节

### 5.1 截图用 GDI+，不是 PIL

`capture_image()` 里手写了 `_GdiplusStartupInput`、`_EncoderParameter` 等结构体，
走 `gdi32` + `gdiplus` 出 JPEG。**不用 PIL 是为了不引入第三方依赖**（见 §8）。
改这段要小心 ctypes 的 `argtypes`/`restype` 声明，漏一个就会在 64 位上截断指针。

### 5.2 药丸（`Pill`）是 Toplevel，不是主窗口

`AskMe.__init__` 里 `self.root.withdraw()` 把主窗口藏起来了，界面上只有右下角那条药丸。
所以**用"有没有主窗口"来判断程序是否启动会误判**（`MainWindowHandle` 是 0 是正常的）。

药丸只有**一种形态**，常驻形态二选一，由设置项 `home_mode` 决定，`apply_mode()` 一次摆好：

| `home_mode` | 界面 | 尺寸 |
|---|---|---|
| `"pill"`（默认） | 右下角小条一直挂着 | 214×40 |
| `"tray"` | 窗口 `withdraw()`，只留系统托盘图标；悬停显示状态文字 | — |

1.1.4 之前这里有两套动作：`collapse()` / `expand()`，收起后又分"进托盘"和"缩成
24×24 小方块"。用户的原话是「这种悬浮条和托盘图标的切换太奇怪了」——点托盘图标
小条冒出来、点「收起来」又没了，来回切。**别再把它改回去**：要么一直看得见，
要么老实待在托盘里，中间态没有价值。

托盘注册不上时 `mode()` 自动返回 `"pill"`，否则设成 `"tray"` 就彻底没界面了。

定位按**右下边缘**算（`self._right` / `self._bottom`），不是左上角——
不然宽高一变，小条就一边缩一边往左上跳。拖拽时同步更新这两个值。

`reveal()` 管"有人又双击了一次 exe"：小条模式下闪边框，托盘模式下图标闪不了，
只能把悬停提示临时改成「我在这儿」，5 秒后改回来。

### 5.3 打包后才有意义的两段代码

- `_console_for_selftest()`：无控制台的 exe 跑 `--selftest` 时，`AttachConsole(-1)` 挂到调用者的
  cmd，挂不上就 `AllocConsole()` 新开一个。
- `sys.excepthook` 里的 `MessageBoxW`：启动期崩了要弹框，不能只写 `error.log`——
  用户不会去翻 AppData。

---

## 6. 踩过的坑（改代码前先看这节）

每条都是**真发生过、真修过**的。症状写清楚，是为了下次遇到能认出来。

### 6.1 中文 Windows 的 GBK 会崩掉 `--selftest`

**症状**：打包后的 exe 跑 `--selftest` 抛 `UnicodeEncodeError: 'gbk' codec can't encode character '\u200b'`。
源码在 UTF-8 终端跑永远碰不到。

**根因**：cmd 默认代码页 936（GBK），而浏览器标题里常带零宽空格 `U+200B`（`Microsoft Edge` 就有），
GBK 编不出来，`print` 直接抛。

**修法**：`selftest()` 第一行 `sys.stdout.reconfigure(errors="replace")`；`_console_for_selftest()`
里 `open("CONOUT$", "w", errors="replace")` 且**故意不指定 encoding**（跟着控制台代码页走，中文才不乱码）。

### 6.2 `--selftest` 在无控制台环境会永久挂住

**症状**：双击 exe 跑 `--selftest`，黑窗口不动，得手动关。

**根因**：第一版用 `input("按回车关闭…")` 等用户。管道、远程会话、MSYS 的 cmd 里
`AttachConsole` 会失败 → 走 `AllocConsole` → `input()` 永远等不到键盘。

**修法**：`_pause_before_close()` 用 `msvcrt.kbhit()` 轮询，30 秒自动关，按任意键立即关。
实测退出码 0、耗时 31.3 秒。

### 6.3 切语言后整页右下各缩 122px

**症状**：设置页切语言（`recreate()`）后，「返回」从 x=77 跳到 x=199，整页往右下缩。

**根因**：`Ui.padForSystemBars` 的**兜底分支**写成了 `l0 + statusBarHeight(c)`——
把状态栏高度加到了**左边距**上。`recreate()` 时 insets 常常赶不上那 400ms 的等待，
必命中兜底分支，于是每次都错。

**修法**：兜底改成 `l0`，并且左右一律忽略（竖屏下系统栏只在上下）。

### 6.4 键盘弹起时「放行」按钮点不动

**症状**：安卓质问窗里，键盘一弹，底部按钮点了没反应；收起键盘同一点击就成功。

**根因**：输入法窗口在 `TYPE_APPLICATION_OVERLAY` 之上，落在键盘矩形内的点击被它消费。
悬浮窗不是 Activity，系统不会替它让位。

**修法**：`GrillOverlay` 里 `setOnApplyWindowInsetsListener` 读
`WindowInsets.Type.ime().bottom` 补底部 padding；API 30 以下用
「可见区域比窗口矮多少」估算（`imeByVisibleFrame`）。

### 6.5 安卓倒计时每次打开 App 就归零

**症状**：频繁看首页的人永远等不到质问。

**根因**：`MainActivity.onResume` 每次都发 `ACTION_START`，而服务的 `ACTION_START` 分支
**无条件** `sinceLast=0; nextDue=firstMinutes*60`。

**修法**：加 `EXTRA_RESET`。`GrillService.startFresh()` 带 `reset=true`（用户点开始），
`start()` 不带（onResume 拉起，保留计时）。

### 6.6 安卓首页空转 15fps 持续重绘

**症状**：`dumpsys gfxinfo` 显示空闲时每秒 15 帧。

**根因**：`update()` 每秒无条件 `setText` + `new RippleDrawable` + SQLite 查询 + 权限 IPC。

**修法**：字段拼签名，没变直接 return；背景预构建；重查询降频到 5 秒（`tickN % 5`）。
修完空闲 6 秒 **0 帧**。

### 6.7 安卓投屏常驻很费电

**症状**：状态栏一直显示「屏幕共享」，担心耗电。

**根因**：不是投屏本身费电，是 `ImageReader.setOnImageAvailableListener` 持续
`acquireLatestImage()`，队列永远有空位 → VirtualDisplay 为屏幕每次变化多合成一份副本。
实测在带自动播放的 App 里，投屏贡献约 27 个百分点 CPU。

**修法**：`VirtualDisplay.setSurface(null)` 断开合成端，需要时再 `setSurface(reader.getSurface())`
接回（`Shots.pause()` / `resume()`）。`GrillService.syncShotPipeline()` 每 tick 判断：
只在「快到质问时间（20 秒内）且没在弹窗」时才 `resume()`，其余时间 `pause()`。
**Android 14 限制的是「一个 MediaProjection 只能 createVirtualDisplay 一次」，
`setSurface` 切换不受限，可以反复暂停/恢复。**

### 6.8 小米/HyperOS 的投屏授权会先让你选 App

**症状**：授权弹窗先弹「分享或录制应用」，默认「应用范围 = 单个应用」，只能抓那一个 App。

**修法**：API 34+ 用 `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`，
系统直接把范围定成「整个屏幕」，跳过选应用。

### 6.9 手工改 SharedPreferences 的 `<string>` 会被读成空串

**症状**：为了测试改 `day`，注入 XML 后服务日志显示 `day= active=0`，触发跨天重置冲掉 `next_due`。

**根因**：手写的 `<string name="day">2026-11-16</string>` 被解析成空串（和行尾、BOM 都无关）。

**修法**：**先让 App 自己写出那个文件**，再用正则只改数字，push 回去。别从零手写 XML。

### 6.10 `unitOf(key)` 的单位判断永远走错分支

**症状**：「最少字数」改成 260 后显示成「260 分钟」。

**根因**：`unitOf(key)` 收的是**配置键名**（`min_chars`），里面却拿**单位名**（`char`）去比。

**修法**：把单位直接作为参数传下去，不再靠 key 反查。

### 6.11 免打扰时间：行上显示 22:00，点开选择器是 00:00

**根因**：`timeRow` 解析时用写死的 `"00:00"` 当默认值，而 `Cfg.dndStart()` 的默认值是 `"22:00"`。
一按确定就把设置改成 00:00。

**修法**：从行上**显示的那个值**解析。

### 6.12 设置项的问号：两次返工

1. 独立圆形 `TextView` → 英文/俄文标题换行时被挤成 0 宽**直接消失**
   （`LinearLayout` 里 `wrap_content` 的标题会吃满宽度）
2. `ClickableSpan` + `LinkMovementMethod` → 这台机器上点了**没反应**（扫了三个坐标都不弹）
3. 最终：问号是**标题文字里的字符**（`\u2009\u2009?` + `RelativeSizeSpan(0.85)` + `MUTED`），
   **整条标题** `setOnClickListener` 弹解释。窄空格×2 是为了别贴着文字（贴着会读成
   「两次质问的最短间隔?」这种疑问句），且比普通空格窄，中文最长标题不会被挤到第二行。

### 6.13 调试时踩的环境坑

- **`top -n 1` 是进程启动至今的平均值**，不是瞬时值。测瞬时 CPU 用 `top -b -n 2 -d 1` 取第二次。
- **屏幕超时 60 秒**：息屏后 `adb input tap` 自然无反应，先 `dumpsys power | grep mWakefulness`。
- **adb 坐标**：截图是缩放过的，目测坐标会错。一律用 `uiautomator dump` 的 `bounds`。
- **小米没有 AOSP 的 `START u0` 日志**：查 Activity 跳转看
  `dumpsys activity activities | grep topResumedActivity`。
- **`View.getDefaultSize()` 陷阱**：裸 `View` 在 `AT_MOST` 下 `wrap_content` 返回整个可用高度。

### 6.14 「两条时间线在并行」——真凶是两个实例

**症状**：右下角小条显示的时长和弹窗的节奏对不上；偶尔同一时刻弹出两个窗；
记录的 `active`/`span` 数值互相矛盾。

**根因**：1.0.0 没有单实例锁。双击两次 exe（或者开机自启之后又手动点了一次），
就有两个进程各跑一套计时、各弹各的窗、互相覆盖 `state.json`。
**两条小条还停在完全相同的坐标上，重叠成一个**，所以看着完全不像有两个进程——
这是最容易被误判成"计时逻辑有 bug"的地方。

**修法**：`acquire_single_instance()` 用命名 mutex（`Local\GrillMe.SingleInstance`）。
必须用 `ctypes.WinDLL("kernel32", use_last_error=True)` + `ctypes.get_last_error()`——
普通 `ctypes.windll` 拿不到可靠错误码，中间的 ctypes 调用会把 last error 冲掉，
`CreateMutexW` 明明说"已存在"，`GetLastError()` 却返回 0。

第二个实例**必须弹消息框**，而且要 `MB_TOPMOST | MB_SETFOREGROUND`。

第一版我图省事，只写了个 `wake.flag` 让第一个实例闪一下，不弹框。结果是：用户
双击了两次，屏幕上什么都没发生，他既不知道"已经开了一个"，也不知道程序在哪儿——
这正是用户反馈的那句"怎么最后也没了"。静默失败比弹框难用得多。

所以现在是两件事一起做：
1. `MessageBoxW(..., 0x40 | 0x40000 | 0x10000)` 明确告诉用户"已经在运行了"；
2. 同时写 `wake.flag`，第一个实例在每秒的 tick 里发现它就展开小条并闪边框
   （`Pill.flash()`）——框上那句"它就在屏幕右下角"得是真的。

不加 `MB_TOPMOST` 的话，框会被前台窗口压在下面，用户照样以为"没反应"。

### 6.15 `since_last` 和 `session_start` 是两套账

**症状**：小条说「连续用了 3 小时」，弹窗却说「已经连续用了 5 分钟」；
系统睡眠唤醒后数字猛跳一大截。

**根因**：`active`/`since_last` 靠「每次 tick 加 1」，暗含"after 恰好 1 秒"；
`session_start` 是时间戳，用 `now - session_start` 算。两者在三种情况下必然分叉：

1. 弹窗开着时 `since_last` 不涨（对），但 `session_start` 在走；
2. 系统睡眠时 `after` 不触发，`since_last` 停住，时间戳照样往前走；
3. `session_start` 存进了 `state.json`，重启后连关机那段时间也算进"这一轮"。

**修法**：全部改成**单调时钟增量**——`dt = time.monotonic() - last_tick`，
三个量（`active` / `since_last` / `session_secs`）加同一个 `dt`。
`dt > 5` 秒视为机器睡过或被挂起，直接丢弃（那段时间人不在，算进去就是把账做假）。
`session_secs` 落盘时带 `saved_at`，重启间隔超过 120 秒就归零——"连续"确实断了。

### 6.16 配置文件被加上 BOM 之后，所有设置静默回默认

**症状**：用 PowerShell / 记事本改了一下 `config.json`，再打开程序，设置全变默认了。

**根因**：`Set-Content -Encoding UTF8`（PowerShell 5.1）和记事本「另存为 UTF-8」
都会在文件开头写 `EF BB BF`。`json.load` 见到 BOM 直接抛
`Unexpected UTF-8 BOM`，`load_json` 于是返回 fallback `{}`，
`load_cfg` 拿 `DEFAULTS` 补全——**用户看不到任何报错，配置就这么没了**。

**修法**：读 JSON 一律 `encoding="utf-8-sig"`（`load_json` 和 `read_log` 都改了）。
`save_json` 仍然写不带 BOM 的 UTF-8。

### 6.17 系统托盘：三个坑，一个比一个隐蔽

托盘图标现在是**默认的收起形态**（设置项 `collapse_tray`，可以关掉改用悬浮小方块）。
做它的时候踩了三个坑，每个都会让程序"看着没问题、其实坏了"。

**坑一：窗口过程绝对不能挂 Python 回调。**

最自然的写法是 `CreateWindowExW` 时挂一个 ctypes 的 `WNDPROC`，在里面处理鼠标事件。
在 CPython 3.14 + Tk 上这条路会把进程直接打崩：

```
Fatal Python error: PyEval_RestoreThread: the function must be called
with the GIL held ... (the current Python thread state is NULL)
```

因为 Tk 的 `mainloop` 是在**释放 GIL** 的状态下调 `DispatchMessageW` 的，
消息派发到 Python 回调时线程状态是空的。表现是"点一下托盘图标，程序就没了"。

正确做法（`Tray` 现在就是这么写的）：窗口过程挂系统的 `DefWindowProcW`
（`ctypes.cast(u.DefWindowProcW, WNDPROC)`，纯 C，不回调 Python），
另开一个 daemon 线程 `GetMessageW` 把消息取出来，只把"发生了什么"丢进
`queue.Queue`；主线程用 `root.after(150, self._pump)` 捞队列再动界面。
全程没有 C→Python 的回调，就不会崩。

**坑二：ctypes 参数不声明 `argtypes` 会静默出错。**

`DefWindowProcW` / `PostMessageW` / `CreateWindowExW` 不声明 `argtypes` 时，
ctypes 默认把参数当 32 位 `int`，64 位下 LPARAM 一超过 2^31 就抛 `OverflowError`。
而且异常是在窗口过程里抛的，无窗口 exe 连日志都看不到，表现就是"点了没反应"。
所有 `HWND` / `LPARAM` / `WPARAM` / `HINSTANCE` 参数都必须显式声明。

**坑三：Win11 默认把新图标塞进「^」，但有办法自动提出来。**

`Shell_NotifyIconW(NIM_ADD)` 返回成功、图标也确实注册上了，但 Win11 把它放进
`^` 溢出面板、任务栏上看不见。系统没给 API，但显示偏好存在注册表里：

```
HKCU\Control Panel\NotifyIconSettings\<按 exe 路径算出来的数字>
    ExecutablePath  REG_SZ   图标所属 exe 的完整路径
    IsPromoted      REG_DWORD 1 = 显示在任务栏
```

`Tray._promote()` 遍历这个键，只改 `ExecutablePath` 正好等于自己那一项，
把 `IsPromoted` 置 1——Explorer 看到就会把图标挪出来，等于替用户去设置里点了一次。
两个注意点：

- 这个键要**等 Explorer 见过图标之后**才会建出来，所以得隔几秒重试几次；
- 跑源码时 `sys.executable` 是 `python.exe`，改它会把**别的** Python 程序的图标
  一起提上来。所以 `_promote()` 里有 `getattr(sys, "frozen", False)` 的判断，
  只有打包后的 exe 才动注册表（调试时可以用 `GRILL_PROMOTE_TEST=1` 强行放开）。

**关于 `Shell_NotifyIconGetRect` 返回 `0x80070057 (E_INVALIDARG)`：这是正常的。**
那个 API 要求图标用 `NOTIFYICON_VERSION_4` 注册，没设就是 V0，查不到矩形
不代表图标没显示——别被它误导（我就被误导过一轮）。1.1.3 起 `_add()` 会调
`NIM_SETVERSION(4)`，于是能查到真实矩形了（本机实测 `(1534,1152)-(1566,1200)`，
32×48 就是任务栏那一格的点击区）。

**V0 和 V4 的回调消息不一样。** V4 左键发 `NIN_SELECT` / `NIN_KEYSELECT`
（`WM_USER + 0` / `WM_USER + 1`）、**右键改成发 `WM_CONTEXTMENU`（0x7B）**，
V0 才是 `WM_LBUTTONUP` / `WM_RBUTTONUP`。`_pump()` 里两套都认，
`_run()` 里 `WM_CONTEXTMENU` 也要接，不然换了版本右键就没反应。
回调消息本身用 `WM_APP + 1`（`0x8001`），别用 `WM_USER` 段——那里是 Shell 的地盘。

**`TaskbarCreated` 必须处理**（`RegisterWindowMessageW("TaskbarCreated")`），
否则资源管理器重启一次，图标就永久消失。

**收起图标别画圆环。** 中间试过一版：收起后是 32×32 蓝底白圈的圆环，
用户的原话是"这个蓝色小眼睛感觉不如之前的小方块"。现在直接贴应用图标本体
（深色圆角方块 + 橙色对话框），24×24，状态靠右下角一个小圆点表达。
图标是 24×24 的 PNG 以 base64 内联在 `grill.py` 里（`_ICON_PNG`，915 字节）——
不走 `--add-data`，少一条 onefile 下 `sys._MEIPASS` 的出错路径。
注意 `tk.PhotoImage` 必须留引用（`self._img`），否则被 GC 掉图就没了。

**这个环境没法验证真实点击**：这台机器的沙箱会吞掉合成的鼠标按键
（`mouse_event` 和 `SendInput` 都返回成功，但系统不处理，连"点一下关掉溢出面板"
都做不到；`SetCursorPos` 移动光标却是好的）。所以托盘点击是靠
`PostMessageW(hwnd, WM_APP+1, 1, WM_LBUTTONUP)` 验证的——这条通了，
说明消息循环和展开链路都对，剩下的只有 Explorer 投递这一环，那是 API 契约。
兜底路径（再双击一次 exe → `poke_existing()` → 展开）可以真机验证，走的就是这条。

### 6.18 托盘图标点不动：Shell 一条回调都不发

**症状**（1.1.2 用户报的）：小条收进托盘之后，图标在那儿、鼠标停上去还弹提示文字，
但左键、双击、右键全都没反应，跟没接电一样。

**已确认的部分**：`NIM_ADD` 返回 TRUE、`NIM_SETVERSION(4)` 返回 TRUE、
`Shell_NotifyIconGetRect` 能查到正确的矩形、悬停提示是程序自己设的那句、
往窗口 `PostMessage` 一个点击消息能正常展开小条。
也就是说：图标注册健康、消息循环活着、展开链路没问题，**唯独 Shell 不往我们这儿投递**。

**根因没定位到**。查过但没排除的：V0/V4 语义、回调消息值（`WM_APP` 段）、
`uID`、窗口是否 top-level、`IsPromoted` 注册表提升、UIPI 完整性级别。
这台机器的沙箱会吞掉合成的鼠标按键（`mouse_event` 和 `SendInput` 都返回成功但系统
不处理，连 `GetAsyncKeyState` 都读不到），所以没法自己复现、只能靠用户反馈。

**结论：不要只靠 Shell 投递。** 1.1.3 加了 `Tray._watch()`——主线程每 200ms
自己看一眼，不依赖任何回调：

1. `GetAsyncKeyState(VK_LBUTTON/RBUTTON)` 取**低位**（自上次调用以来按下过）。
   为什么用低位而不是比对按下/松开状态：一拍 200ms，手快的人按下又松开都在
   同一拍里完成，比对状态会整个漏掉；低位是累加的，轮询慢也不会漏。
2. 光标位置在不在图标矩形里，用 `Shell_NotifyIconGetRect` 拿（所以必须 V4）。
3. 两个都成立就当成"点了图标"，往队列里塞一条，走和真实回调完全相同的路径。

三个坑：
- **不管图标在不在，每一拍都要调 `GetAsyncKeyState` 把标志位吃掉**，
  否则展开期间攒下的点击会在刚收起的下一拍被误判成"点了图标"。
- 图标在「^」面板里、面板没展开时 `GetRect` 会失败，这时兜底无能为力——
  日志里会记一条"拿不到图标矩形"，用户点开「^」之后就能正常用了。
- 收到过真实回调时（`_last_evt` 在 0.5 秒内）兜底不插手，免得两套机制打架。

**`tray_debug.log` 改成默认就写**（原来是 `GRILL_TRAY_DEBUG=1` 才写）。
用户报"点了没反应"的时候手上一点证据都没有，只能靠猜——这个教训比 bug 本身值钱。
每次启动清空，不会无限长大。

### 6.19 安卓："每跑一次 +1"的计时，一卡就永久落后

**症状**：在 B 站、知乎这类一直出帧的 App 里，倒计时不走字；说好一分钟提醒，
等十分钟也不弹窗；切回 App 一看，剩余时间只少了几秒。

**根因**：`GrillService.tick` 是 `ui.postDelayed(this, 1000)` 自调度，`tickOnce()`
里写的是 `sinceLast++`——**默认"这一拍恰好隔了一秒"**。只要主线程被占住（投屏的
VirtualDisplay 一直在合成，SurfaceFlinger 压力大），这一拍就会晚到甚至丢掉。
丢一拍账上就永久少一秒，丢一百拍用户就等不到弹窗。

顺带还有两处会放大这个问题：
- `syncShotPipeline()` 里 `nearDue = (nextDue - sinceLast) <= 20`，`sinceLast` 不涨
  就永远不 `resume()`，抓帧管道永远不接上，弹窗前的截图也永远拿不到；
- `!canPopup()` / 弹窗挂着这两条分支 `return` 在 `saveEvery()` 之前，落盘值一直是
  旧的，首页读到的"剩余时间"就更不走了。

**修法**：
1. `tickOnce(int dt)`，`dt` 由 `SystemClock.uptimeMillis()` 的差值算出来。
   **必须用 `uptimeMillis` 而不是 `elapsedRealtime`**：后者把深度睡眠也算进去，
   锁屏睡一夜，一解锁 `dt` 就是八小时，`sinceLast` 一口气补满、立刻弹窗。
   `uptimeMillis` 只算设备醒着的时间，正好对应"你确实在用"。
2. `dt` 夹在 `[1, 120]`。单拍最多认两分钟，防止极端情况一口气补满。
3. 两条提前 `return` 的分支补上 `saveEvery(now)`。
4. `screenRx` 里原来写 `ui.removeCallbacksAndMessages(null)`，会顺手把已经排好的
   `syncTick` 一起清掉，同步从此再也不跑。改成 `ui.removeCallbacks(tick)`。

实测（23054RA19C / HyperOS，B 站视频播放中）：`since_last` 30 秒走 30 秒。

### 6.20 安卓：秒数 5 秒才跳一下

**症状**：首页倒计时带秒，但读数五秒才变一次。

**根因**：`MainActivity.update()` 每秒跑一次，但 `GrillService.secondsLeft()` 读的是
SharedPreferences，而 `saveEvery()` 是**每 5 秒**才落盘一次。界面每秒都在读同一个
旧值，自然五秒一跳。

**修法**：服务里留一个内存活读数 `liveLeft`（每次 tick 用 `markLive()` 刷新），
`secondsLeft()` 优先用它，并按 `uptimeMillis` 的经过时间自己往下减。落盘值只作为
服务没起来时的兜底。`onDestroy()` 里把 `liveLeft` 置 -1 表示失效。

### 6.21 安卓版本号忘了跟着改

1.1.0 的 APK 装上去，`dumpsys package` 里还是 `versionName=1.0.0`——
`android/app/build.gradle` 的 `versionCode` / `versionName` 没跟着动。
发版清单里必须包含这一项，否则用户没法判断自己装的是哪一版。

顺带一个坑：`adb install` 在 `versionCode` 没升的时候会报
`INSTALL_FAILED_VERSION_DOWNGRADE`，但**错误信息是空的**（只显示 `failed to install`）。
调试时加 `-d` 允许降级，发版时老老实实把 `versionCode` 加一。

### 6.22 托盘右键菜单：三个"为什么"

用户原话：「有时候右键一下不出来菜单，得再右键一下」「菜单出来后没办法点击屏幕
任意位置消失，必须点个按键才行，但悬浮条的就可以」。

三条结论都是实测出来的：

**一、Tk 的菜单必须挂在一个已经映射到屏幕上的窗口上。**
托盘模式下 `AskApp.root` 是 `withdraw()` 的，`tk.Menu(root)` 弹在它上面就出不来
——`winfo_ismapped()` 一直是 0、尺寸一直是 1×1。这就是"第一次点不出来"。
修法：`Tray._make_anchor()` 养一个 1×1、`-alpha 0.01`、`overrideredirect` 的
Toplevel 当菜单的爹，弹之前把它挪到鼠标位置并 `focus_force()`。

**二、`tk_popup` 是阻塞的。**
它在内部跑一个嵌套事件循环，菜单没关就不返回。所以任何"弹完再做点什么"的代码
都排在它后面，等于永远不会执行。要轮询"用户有没有点别处"，必须**先**把轮询排
进事件队列（`root.after`），**再**调 `tk_popup`。嵌套循环里 `after` 回调照常跑。

**三、`GetAsyncKeyState` 的低位是"读一次就清"。**
托盘兜底轮询（`Tray._watch`）和菜单轮询（`_watch_menu_close`）都要读它，谁先读
谁把点击吃掉，另一条链就永远等不到。用模块级计数 `_MENU_WATCH` 隔开：菜单开着
的时候 `_watch` 不碰键盘状态。

菜单本身还是 Tk 的 `tk.Menu`，没换成 Win32 `TrackPopupMenu`——后者的焦点语义在
"没有活动窗口"的场景下更容易出岔子，而 Tk 这条路的显示问题已经被锚点解决了。
"点外面关掉"由 `_watch_menu_close` 每 50ms 轮询保证，不依赖任何焦点信号。

**这台机器没法验证菜单交互**：会话是非交互桌面（`ImageGrab` 全黑、
`mouse_event` 注入的按键 `GetAsyncKeyState` 读不到、Tk 菜单映射不出来直接挂住）。
形态切换、菜单条目、菜单对象构造是程序化自检验证过的（`grilltest/selftest_mode.py`），
"点一下菜单出来、点外面关掉"只能靠上面三条推理 + 用户实测。

### 6.23 安卓：锁屏解锁后不弹窗

用户复现路径：「"质问我"在后台 → 锁屏 → 解锁 → 直接开 B 站 → 不弹窗；
切回"质问我"的一瞬间才突然弹出来」。

实测日志（`adb logcat -s grillme`）：

```
ask kind=normal showing=true                    ← 锁屏前弹窗挂着
屏幕事件 SCREEN_OFF since=62 nextDue=60
屏幕事件 SCREEN_ON  since=62 nextDue=60
屏幕事件 USER_PRESENT since=63 nextDue=60
tick dt=1 active=1517 since=167/60 overlay=true canPopup=true app=tv.danmaku.bili
```

`since` 涨到 167/60 还不弹，说明 `tickOnce()` 一直命中这一条：

```java
if (overlay != null && overlay.isShowing()) { ...; return; }   // 弹窗挂着，先不管
```

**根因**：锁屏时系统把 `TYPE_APPLICATION_OVERLAY` 的窗口摘掉了，但
`GrillOverlay.showing` 这个旗子没人复位，`isShowing()` 又只返回这个旗子。

**修法**（三处，缺一不可）：

1. `GrillOverlay.isShowing()` 真去问窗口还在不在：`root.getParent() != null`
   且（`isAttachedToWindow()` 或刚弹出来不到 2 秒）。加那 2 秒宽限是因为
   `addView()` 返回时窗口还没 attach，下一帧才有——不然 `ask()` 会立刻误判成
   "没弹出来"，铃声不响、15 秒后重复弹。
2. `screenRx` 处理 `ACTION_SCREEN_OFF`：有 overlay 就 `dismiss()`，并
   `pending = true` 把"欠着的那道题"记下来，解锁回来接着问。
3. `ACTION_USER_PRESENT` 再兜一次：窗口已经不在的残留对象直接扔掉。

另外给 `ask()` 加了失败退避（`askFailAt`，15 秒）：`sinceLast` 一直 ≥ `nextDue`
的时候，每一拍都会重新 `ask()`，而 `ask()` 里带截图——不拦就是一秒一张，手机发烫。

验证方法：`adb shell input keyevent 26` 锁屏 → 等 15 秒 → 上滑两下 + 盲打密码 +
回车解锁 → `adb shell monkey -p tv.danmaku.bili -c android.intent.category.LAUNCHER 1`
→ 看日志里有没有 `ask kind=back showing=true`，再截图确认弹窗盖在 B 站上。
（锁屏界面截图是全黑的，系统安全策略，只能盲打。）

---

## 7. 构建与发布

### 7.1 改完词表必须校验

```bash
cd <项目根> && python - <<'EOF'
import io, json, re, collections
raw = io.open('lang/strings.json', encoding='utf-8').read()
s = json.loads(raw)['s']
LANGS = ['zh','en','ja','ru']; p = []
keys = re.findall(r'^    "([^"]+)": \{', raw, re.M)
dup = [k for k,n in collections.Counter(keys).items() if n > 1]
if dup: p.append('重复 key: %s' % dup)
for k,row in s.items():
    for l in LANGS:
        if not row.get(l): p.append('%s 缺 %s' % (k,l))
    ph = {l: sorted(re.findall(r'\{\d+\}', row.get(l) or '')) for l in LANGS}
    if len(set(tuple(v) for v in ph.values())) != 1: p.append('%s 占位符不一致' % k)
print('条数 %d  问题 %d' % (len(s), len(p)))
for x in p: print(' -', x)
EOF
```

### 7.2 电脑端

```bash
# 源码直接跑（需要带 tkinter 的 Python，见 §8）
"/d/Program Files/Python314/python.exe" grill.py --selftest   # 环境自检
"/d/Program Files/Python314/python.exe" grill.py --demo       # 立刻弹一次

# 打 exe（用同一个解释器，别用 PATH 里那个——它没有 tkinter）
"/d/Program Files/Python314/python.exe" -m PyInstaller \
  --noconfirm --clean --onefile --windowed --name GrillMe \
  --icon build/res/grill.ico --version-file build/res/version_info.txt grill.py
```

`--onefile` 单文件 13.7 MB、启动 0.5 秒。图标从 `docs/icon-512.png` 用 Pillow 转多尺寸 ico。
体积构成：python314.dll 6.5 MB + OpenSSL 7.0 MB（WebDAV 走 HTTPS）+ Tcl/Tk 5.3 MB。
**裁掉用不到的标准库模块只省 5 KB**，别在这上面浪费时间。

### 7.3 安卓端

```bash
cd android && gradle assembleRelease
# 产物 android/app/build/outputs/apk/release/app-release.apk
```

项目里**没有 `gradlew`**，必须用系统 gradle。

### 7.4 发版

发版前对一遍这张单子（1.1.0 就是漏了第 2 条，APK 装上去还写着 1.0.0）：

1. `README.md` / `RELEASE_NOTES.md` 里的版本号和附件名；
2. **`android/app/build.gradle` 的 `versionCode` / `versionName`**——最容易漏；
3. `grill.py` 里如果打版本号也要同步；
4. 重新 `assembleRelease` 并确认体积没异常；
5. `git tag`、push、传 release 附件。

```bash
git tag -f v1.1.4 && git push <remote> main:main "+refs/tags/v1.1.4:refs/tags/v1.1.4"
```

`dist/` 被 `.gitignore` 排除——二进制只进 release 附件，不进源码树。
附件命名：`grillme-1.1.4-win64.zip`（exe + 文档）、`grillme-1.1.4.apk`、`grillme-1.1.4.zip`（安卓 + 文档）。
**只改电脑端时，安卓那两个附件不用重出**，release 里沿用上一版即可——但要在说明里写清楚。

---

## 8. 这台开发机的环境（换机器要重新确认）

| 项 | 位置 / 说明 |
|---|---|
| 带 tkinter 的 Python | `D:\Program Files\Python314\python.exe`（3.14.9，64 位，Tk 9.0） |
| **不能用来打包的 Python** | `~/.workbuddy-ai/binaries/python/3.13.12`——**没有 tkinter** |
| PyInstaller / Pillow | 装在 `--user`：`%APPDATA%\Python\Python314` |
| Android SDK | `C:\Android\sdk` |
| Gradle | `C:\Android\gradle-8.14.7\bin\gradle`（项目内无 gradlew） |
| 网络 | **直连 `github.com` 超时**；`127.0.0.1:10808` 的 SOCKS5 能通；`api.github.com` 直连就通 |
| 凭据 | PortableGit **没有凭据管理器**，push 必须现给 token |

推代码的固定写法：

```bash
git -c http.proxy=socks5h://127.0.0.1:10808 push \
  "https://x-access-token:<token>@github.com/qq461759808/grillme.git" main:main --tags
```

token 只出现在命令行，**不要写进 remote 或任何文件**。上传 release 附件走
`uploads.github.com`（也要挂 SOCKS）。

工具层的两个限制：

- PowerShell 里 `Start-Process` 会因环境变量 `HTTPS_PROXY` / `https_proxy` 大小写重复报
  「已添加项」，改用 Python `subprocess` 启动。
- `Add-Type` 被安全策略拦（"compiles and loads .NET code at runtime"），
  截图改用 Pillow 的 `ImageGrab`，枚举窗口用自写的 ctypes `EnumWindows` 脚本。

---

## 9. 调试手段

**电脑端**：`--selftest` 逐项打印（数据目录、锁屏、空闲秒数、全屏、免打扰、前台程序、
截图、时间归属读写、日志读写）；`--demo` 立刻弹一次。

**安卓端**：

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ui.xml
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png /tmp/s.png
adb logcat -d | grep -c "FATAL EXCEPTION"        # 崩溃计数，应为 0
adb shell dumpsys gfxinfo com.grillme.app        # 看是否空转重绘
```

在 MSYS/Git Bash 里跑 adb 要加 `export MSYS2_ARG_CONV_EXCL='*'`，否则 `/sdcard/...` 会被转成 Windows 路径。

**验计时有没有真的在走**（不用等，直接读账）：

```bash
A=$(adb shell run-as com.grillme.app cat shared_prefs/grill.xml | grep since_last | grep -o '[0-9]*')
sleep 30
B=$(adb shell run-as com.grillme.app cat shared_prefs/grill.xml | grep since_last | grep -o '[0-9]*')
echo "$A -> $B（真实 30 秒）"
```

`run-as` 只在 **debug 包**上有效，release 签名会报 `package not debuggable`——要读
SharedPreferences 就先 `assembleDebug` 装一版。

**想马上看到弹窗**，不用等 3~20 分钟：force-stop 之后直接改
`shared_prefs/grill.xml` 里的 `since_last`，推到 `next_due` 前一秒，再启动即可。
注意要先 `adb push` 到 `/data/local/tmp/`，再 `run-as ... cp` 进去，直接写会因权限失败。

`GrillService` 只在**出问题时**才打日志（单拍补了 >3 秒、或耗时 >300ms），
平时 `adb logcat -s grillme` 是安静的。这条日志就是给"用户说计时不走"准备的。

---

## 10. 待办与已知缺口

- **托盘点击的根因还没查到**（§6.19）：1.1.3 用轮询兜底绕过去了，功能可用，
  但"为什么 Shell 一条回调都不发"仍然不明。如果以后遇到同样症状的托盘程序，
  这条线索值得继续挖。
- **托盘菜单的交互没在真机上验过**（§6.22）：锚点 + 轮询这两条都是推理出来的，
  这台开发机的桌面是非交互的，Tk 菜单根本映射不出来。下次有机会请用户实测：
  右键一下能不能出、点别处能不能收、菜单开着时再右键会不会叠。
- **兜底轮询在「^」面板收起时无能为力**：拿不到图标矩形就没法判断。此时用户
  点开「^」再点图标即可（面板一展开矩形就有了）。日志里会留一条记录。
- **电脑端还没接 `lang/strings.json`**：UI 里约 340 条中文字符串（去重 307）是硬编码的，日/俄没翻。
  安卓已经接了（`Lang.java` 读 `assets/strings.json`）。这是最大的一处不一致。
- **安卓用的是 debug 签名**（`build.gradle` 里 `signingConfig signingConfigs.debug`），
  注释里写了发布时用 `keystore.properties` 覆盖。现在能装能用，但升级会签名冲突。
- **安卓没有"无输入"判定**（系统不给全局最后输入时间），"屏幕亮着就算在用"。
- **全屏判定只认主屏**，副屏全屏播放会被判成"离开"。
- 电脑端 `shots/` 的清理按 `shot_keep_days`，默认 0（不清理），长期会攒大。
- **截屏授权（MediaProjection）这条路没在真机上完整验过**：`dumpsys media_projection`
  为 null 时 `Shots.ready()` 是 false，整条抓帧管道根本不建，测计时和弹窗都测不到它。
  要验得手动在 App 里点「截屏授权 → 整个屏幕 → 开始」。
- **设置页「显示『我承认在摸鱼』」关掉后的布局没在真机上看过**。弹窗开着（默认值）是对的。
- **安卓 `sinceLast` 已改成增量记账**（§6.18）。电脑端 §6.15 是同一个病，两边现在
  都用真实经过时间加账，改计时逻辑时别再写回 `xxx++`。

---

## 11. 别"好心"改掉的东西

这些是刻意的设计，看起来像缺陷，其实不是：

- **不显示「距下次提问还有多久」**——会让人盯着倒计时等，甚至提前把答案演一遍。
  小条上那个「连续在用 X」是**已经用了多久**，不是倒计时，措辞别改回去。
- **不做 App 白名单**（"用 Excel 时不弹"）——"在用 Excel"不等于"在工作"，那是给自己开后门。
- **弹窗没有关闭按钮**——能关就等于能跳过，整个机制就废了。
- **不做 AI 分析回答**——要联网、要花钱，违背"离线可用"。
- **电脑端不引第三方包**——只用标准库，所以截图手写 GDI+、HTTP 手写 WebDAV。
  想引 PIL/requests 之前先想清楚：这会毁掉"解压即用、不装依赖"这个前提。
- **小条的形态是「常驻二选一」，不要再加"收起/展开"**：设置里 `home_mode` 选
  「悬浮条」或「托盘图标」，选完就一直是那个样子。1.1.4 之前那套
  「点托盘图标把小条叫出来、点收起来又没了」的来回切换，用户的原话是
  「太奇怪了」。藏起来用户就找不到，转头会再双击一次 exe，然后又变成两个实例
  （§6.14）——所以要留托盘这条路；而托盘在某些机器上会被策略禁掉，
  `Pill.mode()` 会自动退回悬浮条。两边都是兜底，不是可选装饰。
- **托盘的窗口过程不能改回 Python 回调**——会在点第一下的时候把进程打崩（§6.17）。
  看着"这样写更直观"，实际是个必崩的写法。
- **托盘点击不能只靠 Shell 投递**——用户的机器上 Shell 一条回调都不发，
  而图标注册一切正常（§6.19）。`Tray._watch()` 那条轮询看着像补丁，其实是必需的：
  少了它，在那种环境下整个托盘形态就是块死图标。
- **托盘菜单的爹必须是那个 1×1 锚点，不能改回 `root`**（§6.22）：托盘模式下
  `root` 是 withdraw 的，菜单挂在它上面根本不显示。同理，`popup_menu()` 里的
  轮询必须排在 `tk_popup` **之前**——`tk_popup` 会阻塞，写在后面等于没写。
- **单实例锁不能去掉，第二个实例的消息框也不能去掉**——见 §6.14。两者是一套的：
  锁负责不让它跑起来，框负责让用户知道为什么没跑起来。而且第二个实例写的
  `wake.flag` 是托盘的兜底出口：万一托盘图标点不动，再双击一次 exe 就能把小条叫出来。
