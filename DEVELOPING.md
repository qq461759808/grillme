# 开发笔记

写给下一个要改这个项目的人（很可能是一个没有上下文的新会话）。这里记的是**为什么这么做**，
以及**哪些地方一改就会出事**。产品说明看 `README.md`，发版说明看 `RELEASE_NOTES.md`。

---

## 0. 一句话现状

两端都已完成并发布在 https://github.com/qq461759808/grillme （`v1.1.0`，`v1.0.0` 是首个版本）：
电脑端是单文件 Python（`grill.py`，约 3000 行）+ PyInstaller 打的 exe；
安卓端是纯 Java（15 个文件，无 AndroidX、无 Kotlin、无第三方库）。
四语言词表在 `lang/strings.json`，203 条 × zh/en/ja/ru。

1.1.0 相比 1.0.0 的三处变化（都记在 §6）：

- **单实例锁**——1.0.0 没有，双击两次 exe 就会出现两个进程各跑一套计时，
  也就是用户报的「两条时间线在并行」（§6.14）。
- **计时改成单调时钟增量**，不再「每次 tick 加 1」+「时间戳相减」两套账（§6.15）。
- **小条能收成右下角一个小圆点**，设置里能关掉「我承认在摸鱼」，
  电脑端新增开机自启动。

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

单文件约 3000 行，只依赖标准库（**没有第三方包，这是刻意的**，见 §8）。按段落分：

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
| `Pill` | 右下角小条，能收成 40×40 的小圆点（`collapse()` / `expand()` / `flash()`） |
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

第二个实例**不弹消息框**：无窗口进程里的 MessageBox 会被前台窗口盖住，
而且会卡住那个进程直到有人点确定。改成写 `wake.flag`，
第一个实例在每秒的 tick 里发现它就展开小条并闪边框（`Pill.flash()`）。

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

### 6.17 想加系统托盘图标的话，先看这条

这条是给"下次又想把小条塞进托盘"的人看的。

**ctypes 回调里 LPARAM 会溢出**：`DefWindowProcW` / `PostMessageW` 不声明 `argtypes` 时，
ctypes 默认把参数当 32 位 `int`，64 位下 LPARAM 一超过 2^31 就抛 `OverflowError`。
而且异常是在**窗口过程里**抛的，无窗口 exe 连日志都看不到，表现就是"点了没反应"。
所有 `HWND` / `LPARAM` / `WPARAM` / `HINSTANCE` 参数都必须显式声明。

**更要命的是 Win11 的行为**：`Shell_NotifyIconW(NIM_ADD)` 返回成功、
`Shell_NotifyIconGetRect` 也能查到矩形，但图标**不会**出现在任务栏或 `^` 溢出面板里，
必须由用户去「设置 → 个性化 → 任务栏 → 其他系统托盘图标」手动打开。
所以最后没走托盘：改成「小条缩成右下角一个小圆点」（`Pill.collapse()`），
不依赖 Explorer、位置永远在右下角、点了就展开。

如果哪天真的要做托盘，`TaskbarCreated` 那条消息必须处理
（`RegisterWindowMessageW("TaskbarCreated")`），否则资源管理器重启一次图标就永久消失。

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
python grill.py --selftest     # 环境自检
python grill.py --demo         # 立刻弹一次

# 打 exe
pyinstaller --noconfirm --clean --onefile --windowed --name GrillMe \
  --icon build/res/grill.ico --version-file build/res/version_info.txt grill.py
```

`--onefile` 单文件 13.0 MB、启动 0.5 秒。图标从 `docs/icon-512.png` 用 Pillow 转多尺寸 ico。
体积构成：python314.dll 6.5 MB + OpenSSL 7.0 MB（WebDAV 走 HTTPS）+ Tcl/Tk 5.3 MB。
**裁掉用不到的标准库模块只省 5 KB**，别在这上面浪费时间。

### 7.3 安卓端

```bash
cd android && gradle assembleRelease
# 产物 android/app/build/outputs/apk/release/app-release.apk
```

项目里**没有 `gradlew`**，必须用系统 gradle。

### 7.4 发版

```bash
git tag -f v1.0.0 && git push <remote> main:main "+refs/tags/v1.0.0:refs/tags/v1.0.0"
```

`dist/` 被 `.gitignore` 排除——二进制只进 release 附件，不进源码树。
附件命名：`grillme-1.0.0-win64.zip`（exe + 文档）、`grillme-1.0.0.apk`、`grillme-1.0.0.zip`（安卓 + 文档）。

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

---

## 10. 待办与已知缺口

- **电脑端还没接 `lang/strings.json`**：UI 里约 340 条中文字符串（去重 307）是硬编码的，日/俄没翻。
  安卓已经接了（`Lang.java` 读 `assets/strings.json`）。这是最大的一处不一致。
- **安卓用的是 debug 签名**（`build.gradle` 里 `signingConfig signingConfigs.debug`），
  注释里写了发布时用 `keystore.properties` 覆盖。现在能装能用，但升级会签名冲突。
- **安卓没有"无输入"判定**（系统不给全局最后输入时间），"屏幕亮着就算在用"。
- **全屏判定只认主屏**，副屏全屏播放会被判成"离开"。
- 电脑端 `shots/` 的清理按 `shot_keep_days`，默认 0（不清理），长期会攒大。
- **1.1.0 的安卓改动只过了编译，没上真机**：`Cfg.SHOW_SLIP` + `GrillOverlay` 隐藏
  「我承认在摸鱼」+ 设置页那一行。逻辑就三行，但"开关关掉后按钮真的不占位"
  这件事只有真机看得出来（`LinearLayout` 里 GONE 的 view 不参与 measure，
  所以 `passBtn` 的 weight 从 1.2 改成 1 是多余的保险，不影响布局）。
- **安卓 `sinceLast` 还是「每秒 +1」**（`GrillService` 里 `sinceLast++`），
  和电脑端 §6.15 那个问题同源，只是安卓没有 `sessionStart` 时间戳并行，
  而且服务 tick 用的是 `Handler` 定时，漂得没那么明显。要彻底对齐得改成增量记账。

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
- **小条「收起」是缩成小圆点，不是隐藏**——藏起来用户就找不到，
  转头会再双击一次 exe，然后又变成两个实例（§6.14）。
- **单实例锁不能去掉**——见 §6.14，去掉就等于把"两条时间线"放回来。
