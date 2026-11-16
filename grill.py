# -*- coding: utf-8 -*-
"""
质问我 · Grill  (电脑端)

一个会随机把你拦下来、逼你说清楚「为什么现在必须做这件事」的小程序。

核心规则:
  1. 以「屏幕是否亮着」为主信号。屏幕亮着就算在用——发呆、看视频、读长文
     都不算离开。只有锁屏、或者长时间无输入且不在全屏播放时，才停表。
  2. 每累计 40-90 分钟(随机,不可预测)弹一次。不显示倒计时。
  3. 弹窗没有关闭按钮,Alt+F4 和 Esc 都无效,必须打字提交才消失。
  4. 允许和上一条回答完全相同,但禁止粘贴(防复制上次答案)。
  5. 不拦截、不锁屏、不说教。只记录,回头给你看账。

运行:  python grill.py
调试:  python grill.py --demo        立刻弹一次
       python grill.py --selftest    只做环境自检,不开窗口
"""

import base64
import ctypes
import json
import os
import queue
import random
import shutil
import sys
import tempfile
import threading
import time
import traceback
import urllib.error
import urllib.request
import uuid
from ctypes import wintypes
from datetime import date, datetime, timedelta

import tkinter as tk

APP_NAME = "Grill"
TITLE = "质问我 · 监督我"
LEGACY_NAMES = ["AskMe"]


def _resolve_data_dir():
    """改名后把旧目录搬过来，搬不动就继续用旧的，绝不丢数据。"""
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    target = os.path.join(base, APP_NAME)
    if os.path.isdir(target):
        return target
    for old_name in LEGACY_NAMES:
        old = os.path.join(base, old_name)
        if os.path.isdir(old):
            try:
                shutil.move(old, target)
                return target
            except Exception:
                return old
    return target


DATA_DIR = _resolve_data_dir()
CFG_PATH = os.path.join(DATA_DIR, "config.json")
LOG_PATH = os.path.join(DATA_DIR, "log.jsonl")
STATE_PATH = os.path.join(DATA_DIR, "state.json")
WAKE_PATH = os.path.join(DATA_DIR, "wake.flag")
ERR_PATH = os.path.join(DATA_DIR, "error.log")
SYNC_PATH = os.path.join(DATA_DIR, "sync.json")
SHOTS_DIR = os.path.join(DATA_DIR, "shots")
USAGE_DIR = os.path.join(DATA_DIR, "usage")

FONT = "Microsoft YaHei UI"

INK = "#111827"
MUTED = "#6B7280"
FAINT = "#9CA3AF"
LINE = "#E5E7EB"
EDGE = "#94A3B8"
BG = "#FFFFFF"
SOFT = "#F7F8FA"
ACCENT = "#185FA5"
ACCENT_DARK = "#0C447C"
ACCENT_PALE = "#9DB4CC"
OK = "#1D9E75"
WARN = "#854F0B"
DANGER = "#A32D2D"
DUP1 = "#B45309"
DUP2 = "#A32D2D"

# 收起态小方块的底色，跟图标本体的深色一致，接缝看不出来
ICON_BG = "#191C22"

# 收起后那个 24×24 的小方块。就是应用图标本身（深色方块 + 橙色对话框），
# 从 build/res/grill.ico 缩到 24px 后内联在这里。
#
# 为什么内联成 base64 而不是打包成数据文件：PyInstaller onefile 下数据文件要
# 走 --add-data + sys._MEIPASS，多一条出错的路；而这段只有 1.2 KB。
# 为什么不用 Canvas 画：24px 下画不出对话框的圆角和三个点，糊成一团。
_ICON_PNG = (
    "iVBORw0KGgoAAAANSUhEUgAAABgAAAAYCAIAAABvFaqvAAADWklEQVR42o2VMYgdVRSGv3Pmzsx7"
    "u5vduBJJiEVik1ik2C2UgAQFlVSpjIWtpBEJ9paKWAlRjJWINkEkAbsQMEIgTcRCAxpLA0ZCxOxq"
    "9u17M3PPbzHvbcJiNjlcLpw7M+ee85/z/2P7nn4GACS5u3vB41lEjggz6900O7d6MEip3HrwSJPU"
    "dW0zmYCmgQzq4bDwQihCZuwcTCBhRplKd59sjgRJUlnXbp4jG1Y4TUebdwpUFlSJHATh5qmqmskk"
    "9bhEZDBMdze0b7ftWcL0kHSMO+v8uabFoSFC2b1w92RmSAI33Rvz9sv+1nFfHuDifyoUYfw95uzF"
    "OPtdLNSEAMysWFxadvfCWR9xYtU/OlXMOy56pLYvw2G+5sVVu/G7frqpYUWIiHAJSZK60IlVi6Bt"
    "yEGXkRB0QRdTgLtMDtqGCE6sWheaGYlpHAwGCQdBMYRkjIRI8wawKZy0YHRihMMg0YMiAXJAitkS"
    "wiuuXs9nLjSjRiTOXW7PXW5JjBqdudBcvZ69gmkd0w+BNDua7riNxzr1+eTX3/JTi8Njh4s3PhkD"
    "xw7PX7mR3/ls/Oyh4sf354a1SX0GJoFIQog+EEBQ1bz+fLo0x8oBX16y144mYHnJVg740ZXi1SOp"
    "qiGmw92XJmQ91wpnbaRvTg+PP1fGpryCDA4B3lNr5hZEgw/t4rX25Mebu+csx4xrPTQ5a9wqjBzQ"
    "4IYyZmg25coYKJMDjHGrnKfVmJn3mIXCTeevtV5QVrj1Y0Z/mxlm+GwvK7zg/LXWTTHrUrGw+IQU"
    "IQYlP9/MG//qyMFUFWYQopgzg4jZlIsw/hrpg683v7zS7Bps3eS2d//BnLuplMDGRHOVffrm/MmX"
    "aoJLPzQffrs5mqhw65uBcXstbt2NxeF9OhZFmg7kFpkWaiadXjiU1u/p3a82vvh+LEjOA69QJlua"
    "I8eDtNZ0jnrPjbWRXjlS/vJHPv3e2o1b+cldbr1wbZO0vJ3Mtnf/wbaZCPWldcGeXX7nn2gyiwNr"
    "Q4+USsPKqu71yLuuNbOAwri9nsuC+YKm085aaSCpSKW7J0mprHLOvULKpoh0msnqwxU3UOFFKitJ"
    "U/GvB8OmmeSuVUiPJ/5mllJZVfW2vwh1PYiyiogd07gfp8dkq1H/AQ7XCTbXf808AAAAAElFTkSu"
    "QmCC"
)


DEFAULTS = {
    # 总控
    "enabled": 1,
    # 节奏
    "min_minutes": 40,
    "max_minutes": 90,
    "first_minutes": 25,
    # 判定
    "idle_stop_enabled": 1,
    "away_minutes": 10,
    "watch_off_screen": 0,
    "off_screen_grace_minutes": 10,
    "alert_when_off_screen": 1,
    # 门槛
    "min_chars": 2,
    "allow_repeat": 1,
    "block_paste": 1,
    "cooldown_seconds": 3.0,
    # 弹窗
    "show_moyu": 1,
    # 常驻形态
    "collapse_tray": 1,
    "pill_collapsed": 0,
    # 提醒
    "sound": 1,
    "sound_uri": "beep",
    "dnd_enabled": 1,
    "dnd_start": "22:00",
    "dnd_end": "08:00",
    # 附加
    "long_session_alert": 0,
    "long_session_minutes": 180,
    "repeat_warn": 2,
    "repeat_alert": 3,
    # 行为统计与截图
    "track_app": 1,
    "track_titles": 1,
    "shot_enabled": 1,
    "shot_width": 800,
    "shot_quality": 60,
    "shot_keep_days": 30,
    # 同步
    "sync_enabled": 0,
    "sync_url": "",
    "sync_user": "",
    "sync_pass": "",
    "sync_device_id": "pc",
    # 临时
    "paused_until": 0,
}

_LOG_CACHE = {"mtime": 0, "rows": []}


# ---------------------------------------------------------------- 基础设施

def ensure_dirs():
    os.makedirs(DATA_DIR, exist_ok=True)


def load_json(path, fallback):
    try:
        # utf-8-sig 而不是 utf-8：PowerShell 的 Set-Content -Encoding UTF8、
        # 记事本「另存为 UTF-8」都会在开头塞一个 BOM，而 json.load 见到 BOM
        # 直接抛，于是整个配置被当成空文件读回来——所有设置静默回默认值。
        # 这个坑是实测踩到的：用 PowerShell 改了一下 show_moyu，配置全没了。
        with open(path, "r", encoding="utf-8-sig") as f:
            return json.load(f)
    except Exception:
        return fallback


def save_json(path, obj):
    ensure_dirs()
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def load_cfg():
    raw = load_json(CFG_PATH, {})
    if not isinstance(raw, dict):
        raw = {}
    # 旧版配置迁移
    if "idle_seconds" in raw and "away_minutes" not in raw:
        try:
            raw["away_minutes"] = max(1, int(raw["idle_seconds"]) // 60)
        except Exception:
            pass
    if "beep" in raw and "sound" not in raw:
        raw["sound"] = int(bool(raw["beep"]))
    # 早期把 WebDAV 密码单独存在 sync.json，现在统一进 config.json
    legacy = load_json(SYNC_PATH, {})
    if isinstance(legacy, dict):
        if legacy.get("pass") and not raw.get("sync_pass"):
            raw["sync_pass"] = str(legacy["pass"])
        if legacy.get("user") and not raw.get("sync_user"):
            raw["sync_user"] = str(legacy["user"])
        if legacy.get("url") and not raw.get("sync_url"):
            raw["sync_url"] = str(legacy["url"])
    cfg = dict(DEFAULTS)
    cfg.update({k: v for k, v in raw.items() if k in DEFAULTS})
    return cfg


def save_cfg(cfg):
    save_json(CFG_PATH, {k: cfg.get(k, DEFAULTS[k]) for k in DEFAULTS})


def append_log(rec):
    ensure_dirs()
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    _LOG_CACHE["mtime"] = 0


def read_log():
    if not os.path.exists(LOG_PATH):
        return []
    try:
        mtime = os.path.getmtime(LOG_PATH)
    except OSError:
        mtime = 0
    if _LOG_CACHE["mtime"] == mtime and _LOG_CACHE["rows"]:
        return [dict(r) for r in _LOG_CACHE["rows"]]

    rows = []
    try:
        # 同 load_json：容忍 BOM。log.jsonl 被别的工具加过 BOM 的话，
        # 用 utf-8 读会连第一条都解析不出来。
        with open(LOG_PATH, "r", encoding="utf-8-sig") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    r = json.loads(line)
                except Exception:
                    continue
                if not isinstance(r, dict):
                    continue
                if not r.get("id"):
                    r["id"] = "pc-%d" % int(float(r.get("ts", 0)) * 1000)
                rows.append(r)
    except Exception:
        return []
    rows.sort(key=lambda r: float(r.get("ts", 0)))
    _LOG_CACHE["mtime"] = mtime
    _LOG_CACHE["rows"] = rows
    return [dict(r) for r in rows]


def log_error(exc_text):
    try:
        ensure_dirs()
        with open(ERR_PATH, "a", encoding="utf-8") as f:
            f.write(f"\n[{datetime.now():%Y-%m-%d %H:%M:%S}]\n{exc_text}\n")
    except Exception:
        pass


def tray_dbg(msg):
    """托盘相关的调试日志。只有设了 GRILL_TRAY_DEBUG 才写，正式版等于空转。"""
    if not os.environ.get("GRILL_TRAY_DEBUG"):
        return
    try:
        stamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        with open(os.path.join(DATA_DIR, "tray_debug.log"), "a",
                  encoding="utf-8") as f:
            f.write(f"[{stamp}] {msg}\n")
    except Exception:
        pass


# ---------------------------------------------------------------- 时间工具

def parse_hhmm(s, fallback=(0, 0)):
    try:
        h, m = str(s).strip().replace("：", ":").split(":")
        h, m = int(h), int(m)
        if 0 <= h < 24 and 0 <= m < 60:
            return h, m
    except Exception:
        pass
    return fallback


def in_dnd(cfg, dt=None):
    if not int(cfg.get("dnd_enabled", 0)):
        return False
    dt = dt or datetime.now()
    sh, sm = parse_hhmm(cfg.get("dnd_start"), (22, 0))
    eh, em = parse_hhmm(cfg.get("dnd_end"), (8, 0))
    s, e = sh * 60 + sm, eh * 60 + em
    if s == e:
        return False
    cur = dt.hour * 60 + dt.minute
    if s < e:
        return s <= cur < e
    return cur >= s or cur < e


def fmt_span(seconds):
    seconds = max(0, int(seconds))
    if seconds < 60:
        return f"{seconds} 秒"
    mins = seconds // 60
    if mins < 60:
        return f"{mins} 分钟"
    h, m = mins // 60, mins % 60
    if m == 0:
        return f"{h} 小时"
    return f"{h} 小时 {m} 分"


def fmt_ago(ts):
    if not ts:
        return ""
    return fmt_span(time.time() - float(ts)) + "前"


# ---------------------------------------------------------------- Windows 状态

class LASTINPUTINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.UINT), ("dwTime", wintypes.DWORD)]


_lii = LASTINPUTINFO()
_lii.cbSize = ctypes.sizeof(LASTINPUTINFO)
_user32 = ctypes.windll.user32
_kernel32 = ctypes.windll.kernel32
gdi32 = ctypes.windll.gdi32
gdi32.CreateCompatibleDC.restype = wintypes.HDC
gdi32.CreateCompatibleDC.argtypes = [wintypes.HDC]
gdi32.CreateCompatibleBitmap.restype = wintypes.HBITMAP
gdi32.CreateCompatibleBitmap.argtypes = [wintypes.HDC, ctypes.c_int, ctypes.c_int]
gdi32.SelectObject.restype = wintypes.HGDIOBJ
gdi32.SelectObject.argtypes = [wintypes.HDC, wintypes.HGDIOBJ]
gdi32.BitBlt.argtypes = [wintypes.HDC, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                         ctypes.c_int, wintypes.HDC, ctypes.c_int, ctypes.c_int,
                         wintypes.DWORD]
gdi32.DeleteObject.argtypes = [wintypes.HGDIOBJ]
gdi32.DeleteDC.argtypes = [wintypes.HDC]
_user32.GetDC.restype = wintypes.HDC
_user32.GetDC.argtypes = [wintypes.HWND]
_user32.ReleaseDC.argtypes = [wintypes.HWND, wintypes.HDC]
_user32.GetForegroundWindow.restype = wintypes.HWND
_user32.OpenInputDesktop.restype = wintypes.HANDLE
_user32.OpenInputDesktop.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
_user32.CloseDesktop.argtypes = [wintypes.HANDLE]

_DESKTOP_CLASSES = {"Progman", "WorkerW", "Shell_TrayWnd", "Windows.UI.Core.CoreWindow"}


def idle_seconds():
    """距离上一次键盘或鼠标操作过了多少秒。"""
    try:
        if not _user32.GetLastInputInfo(ctypes.byref(_lii)):
            return 0.0
        now = _kernel32.GetTickCount() & 0xFFFFFFFF
        delta = (now - _lii.dwTime) & 0xFFFFFFFF
        return delta / 1000.0
    except Exception:
        return 0.0


def is_locked():
    """当前输入桌面不是用户桌面 = 已锁屏 / 正在切用户。"""
    h = None
    try:
        h = _user32.OpenInputDesktop(0, False, 0x0100)
        return not h
    except Exception:
        return False
    finally:
        if h:
            try:
                _user32.CloseDesktop(h)
            except Exception:
                pass


def _window_class(hwnd):
    buf = ctypes.create_unicode_buffer(256)
    try:
        _user32.GetClassNameW(hwnd, buf, 256)
    except Exception:
        return ""
    return buf.value


def is_fullscreen():
    """前台窗口是否铺满整块屏幕（看视频、全屏文档、游戏）。"""
    try:
        hwnd = _user32.GetForegroundWindow()
        if not hwnd:
            return False
        if _window_class(hwnd) in _DESKTOP_CLASSES:
            return False
        rect = wintypes.RECT()
        if not _user32.GetWindowRect(hwnd, ctypes.byref(rect)):
            return False
        sw = _user32.GetSystemMetrics(0)
        sh = _user32.GetSystemMetrics(1)
        if sw <= 0 or sh <= 0:
            return False
        return (rect.left <= 1 and rect.top <= 1
                and rect.right >= sw - 1 and rect.bottom >= sh - 1)
    except Exception:
        return False


def force_foreground(win):
    """尽量把窗口抢到最前并拿到键盘焦点。"""
    try:
        win.update_idletasks()
        hwnd = ctypes.windll.user32.GetParent(win.winfo_id()) or win.winfo_id()
        ctypes.windll.user32.SetForegroundWindow(hwnd)
    except Exception:
        pass


# ---------------------------------------------------------------- 声音

def beep(cfg=None):
    try:
        import winsound
    except Exception:
        return
    uri = str((cfg or {}).get("sound_uri") or "beep")
    if uri and uri.lower() != "beep" and os.path.exists(uri):
        try:
            winsound.PlaySound(uri, winsound.SND_FILENAME | winsound.SND_ASYNC)
            return
        except Exception:
            pass
    try:
        winsound.MessageBeep(0x00000040)
    except Exception:
        pass


# ---------------------------------------------------------------- 前台窗口

_PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

_kernel32.OpenProcess.restype = wintypes.HANDLE
_kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
_kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
_kernel32.QueryFullProcessImageNameW.argtypes = [
    wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
_user32.GetWindowThreadProcessId.argtypes = [
    wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
_user32.GetWindowTextLengthW.argtypes = [wintypes.HWND]
_user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]


def foreground_app():
    """返回 (进程名, 窗口标题)。自己的窗口返回 ("", "")。"""
    try:
        hwnd = _user32.GetForegroundWindow()
        if not hwnd:
            return "", ""
        pid = wintypes.DWORD(0)
        _user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if not pid.value or pid.value == os.getpid():
            return "", ""
        name = ""
        h = _kernel32.OpenProcess(_PROCESS_QUERY_LIMITED_INFORMATION, False, pid.value)
        if h:
            buf = ctypes.create_unicode_buffer(1024)
            size = wintypes.DWORD(1024)
            if _kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
                name = os.path.basename(buf.value or "")
            _kernel32.CloseHandle(h)
        n = _user32.GetWindowTextLengthW(hwnd)
        title = ""
        if n > 0:
            tbuf = ctypes.create_unicode_buffer(n + 2)
            _user32.GetWindowTextW(hwnd, tbuf, n + 2)
            title = (tbuf.value or "").strip()
        return (name or "unknown"), title
    except Exception:
        return "", ""


# ---------------------------------------------------------------- 截图（GDI+）

_gdiplus = None
_gdip_token = ctypes.c_void_p(0)
_gdip_codecs = {}


class _GUID(ctypes.Structure):
    _fields_ = [("Data1", wintypes.DWORD), ("Data2", wintypes.WORD),
                ("Data3", wintypes.WORD), ("Data4", ctypes.c_ubyte * 8)]


class _GdiplusStartupInput(ctypes.Structure):
    _fields_ = [("GdiplusVersion", wintypes.UINT),
                ("DebugEventCallback", ctypes.c_void_p),
                ("SuppressBackgroundThread", wintypes.BOOL),
                ("SuppressExternalCodecs", wintypes.BOOL)]


class _ImageCodecInfo(ctypes.Structure):
    _fields_ = [("Clsid", _GUID), ("FormatID", _GUID),
                ("CodecName", wintypes.LPWSTR), ("DllName", wintypes.LPWSTR),
                ("FormatDescription", wintypes.LPWSTR),
                ("FilenameExtension", wintypes.LPWSTR),
                ("MimeType", wintypes.LPWSTR), ("Flags", wintypes.DWORD),
                ("Version", wintypes.DWORD), ("SigCount", wintypes.DWORD),
                ("SigSize", wintypes.DWORD),
                ("SigPattern", ctypes.POINTER(ctypes.c_ubyte)),
                ("SigMask", ctypes.POINTER(ctypes.c_ubyte))]


class _EncoderParameter(ctypes.Structure):
    _fields_ = [("Guid", _GUID), ("NumberOfValues", ctypes.c_ulong),
                ("Type", ctypes.c_ulong), ("Value", ctypes.c_void_p)]


class _EncoderParameters(ctypes.Structure):
    _fields_ = [("Count", ctypes.c_uint), ("Parameter", _EncoderParameter * 1)]


def _mkguid(s):
    u = uuid.UUID(s)
    g = _GUID()
    g.Data1 = u.time_low
    g.Data2 = u.time_mid
    g.Data3 = u.time_hi_version
    for i, b in enumerate(u.bytes[8:]):
        g.Data4[i] = b
    return g


def _gdiplus_ready(mime="image/jpeg"):
    """懒初始化 GDI+，返回指定格式编码器的 CLSID。"""
    global _gdiplus
    if _gdiplus is None:
        gp = ctypes.WinDLL("gdiplus")
        gp.GdiplusStartup.argtypes = [ctypes.POINTER(ctypes.c_void_p),
                                      ctypes.POINTER(_GdiplusStartupInput),
                                      ctypes.c_void_p]
        gp.GdiplusStartup.restype = ctypes.c_int
        gp.GdipGetImageEncodersSize.argtypes = [ctypes.POINTER(ctypes.c_uint),
                                                ctypes.POINTER(ctypes.c_uint)]
        gp.GdipGetImageEncoders.restype = ctypes.c_int
        gp.GdipCreateBitmapFromHBITMAP.restype = ctypes.c_int
        gp.GdipCreateBitmapFromHBITMAP.argtypes = [wintypes.HBITMAP, wintypes.HPALETTE,
                                                   ctypes.POINTER(ctypes.c_void_p)]
        gp.GdipGetImageThumbnail.restype = ctypes.c_int
        gp.GdipGetImageThumbnail.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_uint,
                                             ctypes.POINTER(ctypes.c_void_p),
                                             ctypes.c_void_p, ctypes.c_void_p]
        gp.GdipSaveImageToFile.restype = ctypes.c_int
        gp.GdipSaveImageToFile.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR,
                                           ctypes.POINTER(_GUID), ctypes.c_void_p]
        gp.GdipDisposeImage.argtypes = [ctypes.c_void_p]
        gp.GdipLoadImageFromFile.restype = ctypes.c_int
        gp.GdipLoadImageFromFile.argtypes = [wintypes.LPCWSTR,
                                             ctypes.POINTER(ctypes.c_void_p)]
        gp.GdipGetImageWidth.argtypes = [ctypes.c_void_p,
                                         ctypes.POINTER(ctypes.c_uint)]
        gp.GdipGetImageHeight.argtypes = [ctypes.c_void_p,
                                          ctypes.POINTER(ctypes.c_uint)]

        si = _GdiplusStartupInput()
        si.GdiplusVersion = 1
        if gp.GdiplusStartup(ctypes.byref(_gdip_token), ctypes.byref(si), None) != 0:
            raise OSError("GdiplusStartup failed")
        _gdiplus = gp
    else:
        gp = _gdiplus

    if mime in _gdip_codecs:
        return _gdip_codecs[mime]

    num = ctypes.c_uint(0)
    size = ctypes.c_uint(0)
    gp.GdipGetImageEncodersSize(ctypes.byref(num), ctypes.byref(size))
    if not size.value:
        raise OSError("no image encoders")
    buf = ctypes.create_string_buffer(size.value)
    if gp.GdipGetImageEncoders(num, size, buf) != 0:
        raise OSError("GdipGetImageEncoders failed")
    arr = ctypes.cast(buf, ctypes.POINTER(_ImageCodecInfo))
    for i in range(num.value):
        if (arr[i].MimeType or "").lower() == mime:
            _gdiplus = gp
            _gdip_codecs[mime] = arr[i].Clsid
            return _gdip_codecs[mime]
    raise OSError("encoder not found: " + mime)


def capture_image(path, max_w=800, quality=60, mime="image/jpeg", rect=None):
    """截图并缩放后保存。rect=(x,y,w,h) 只截该区域，否则截主屏。"""
    try:
        clsid = _gdiplus_ready(mime)
        gp = _gdiplus
        if rect:
            sx, sy, sw, sh = [int(v) for v in rect]
        else:
            sx, sy = 0, 0
            sw = _user32.GetSystemMetrics(0)
            sh = _user32.GetSystemMetrics(1)
        if sw <= 0 or sh <= 0:
            return False
        hdc = _user32.GetDC(None)
        mem = gdi32.CreateCompatibleDC(hdc)
        bmp = gdi32.CreateCompatibleBitmap(hdc, sw, sh)
        old = gdi32.SelectObject(mem, bmp)
        ok = gdi32.BitBlt(mem, 0, 0, sw, sh, hdc, sx, sy, 0x00CC0020)
        gdi32.SelectObject(mem, old)
        if not ok:
            gdi32.DeleteObject(bmp)
            gdi32.DeleteDC(mem)
            _user32.ReleaseDC(None, hdc)
            return False

        img = ctypes.c_void_p(0)
        if gp.GdipCreateBitmapFromHBITMAP(bmp, None, ctypes.byref(img)) != 0:
            gdi32.DeleteObject(bmp)
            gdi32.DeleteDC(mem)
            _user32.ReleaseDC(None, hdc)
            return False

        tw = int(max(160, min(sw, int(max_w or 800))))
        th = int(round(sh * tw / float(sw)))
        thumb = ctypes.c_void_p(0)
        if gp.GdipGetImageThumbnail(img, tw, th, ctypes.byref(thumb), None, None) != 0:
            gp.GdipDisposeImage(img)
            gdi32.DeleteObject(bmp)
            gdi32.DeleteDC(mem)
            _user32.ReleaseDC(None, hdc)
            return False

        params_ptr = None
        params = None
        if mime == "image/jpeg":
            q = ctypes.c_long(int(max(20, min(95, int(quality or 60)))))
            params = _EncoderParameters()
            params.Count = 1
            params.Parameter[0].Guid = _mkguid("1D5BE4B5-FA4A-452D-9CDD-5DB35105E7EB")
            params.Parameter[0].NumberOfValues = 1
            params.Parameter[0].Type = 4
            params.Parameter[0].Value = ctypes.cast(ctypes.byref(q), ctypes.c_void_p)
            params_ptr = ctypes.byref(params)

        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        rc = gp.GdipSaveImageToFile(thumb, path, ctypes.byref(clsid), params_ptr)

        gp.GdipDisposeImage(thumb)
        gp.GdipDisposeImage(img)
        gdi32.DeleteObject(bmp)
        gdi32.DeleteDC(mem)
        _user32.ReleaseDC(None, hdc)
        return rc == 0 and os.path.exists(path)
    except Exception:
        log_error(traceback.format_exc())
        return False


def capture_jpeg(path, max_w=800, quality=60):
    return capture_image(path, max_w, quality, "image/jpeg")


def image_to_png(src, dst, max_w=160):
    """把任意 GDI+ 认识的图片缩放后存成 PNG（tkinter 只能直接显示 PNG/GIF）。"""
    try:
        _gdiplus_ready("image/png")
        gp = _gdiplus
        clsid = _gdip_codecs["image/png"]
        img = ctypes.c_void_p(0)
        if gp.GdipLoadImageFromFile(src, ctypes.byref(img)) != 0:
            return False
        w = ctypes.c_uint(0)
        h = ctypes.c_uint(0)
        gp.GdipGetImageWidth(img, ctypes.byref(w))
        gp.GdipGetImageHeight(img, ctypes.byref(h))
        if not w.value or not h.value:
            gp.GdipDisposeImage(img)
            return False
        tw = int(max(32, min(w.value, int(max_w))))
        th = max(1, int(round(h.value * tw / float(w.value))))
        thumb = ctypes.c_void_p(0)
        if gp.GdipGetImageThumbnail(img, tw, th, ctypes.byref(thumb), None, None) != 0:
            gp.GdipDisposeImage(img)
            return False
        os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
        rc = gp.GdipSaveImageToFile(thumb, dst, ctypes.byref(clsid), None)
        gp.GdipDisposeImage(thumb)
        gp.GdipDisposeImage(img)
        return rc == 0 and os.path.exists(dst)
    except Exception:
        log_error(traceback.format_exc())
        return False


def thumb_path_for(shot_rel, max_w=200):
    """截图路径 -> 缩略图 PNG 路径（宽度写进文件名，改宽度会自动重建）。"""
    p = os.path.join(DATA_DIR, shot_rel)
    d, f = os.path.split(p)
    return os.path.join(d, "thumbs", "%s-%d.png" % (os.path.splitext(f)[0], int(max_w)))


def ensure_thumb(shot_rel, max_w=200):
    """有就复用，没有就生成。返回 PNG 绝对路径，失败返回空串。"""
    if not shot_rel:
        return ""
    src = os.path.join(DATA_DIR, shot_rel)
    if not os.path.exists(src):
        return ""
    dst = thumb_path_for(shot_rel, max_w)
    try:
        if os.path.exists(dst) and os.path.getmtime(dst) >= os.path.getmtime(src):
            return dst
    except Exception:
        pass
    return dst if image_to_png(src, dst, max_w) else ""


def shot_path_for(rec_id):
    folder = os.path.join(SHOTS_DIR, date.today().isoformat())
    os.makedirs(folder, exist_ok=True)
    return os.path.join(folder, f"{datetime.now():%H%M%S}-{rec_id}.jpg")


def cleanup_shots(keep_days):
    """删掉超过 keep_days 天的截图目录。keep_days<=0 表示永久保留。"""
    keep = int(keep_days or 0)
    if keep <= 0 or not os.path.isdir(SHOTS_DIR):
        return 0
    cutoff = (date.today() - timedelta(days=keep)).isoformat()
    removed = 0
    try:
        for name in os.listdir(SHOTS_DIR):
            p = os.path.join(SHOTS_DIR, name)
            if not os.path.isdir(p):
                continue
            if len(name) == 10 and name[4] == "-" and name < cutoff:
                shutil.rmtree(p, ignore_errors=True)
                removed += 1
    except Exception:
        log_error(traceback.format_exc())
    return removed


# ---------------------------------------------------------------- 时间归属

class Usage:
    """按秒累计前台程序，落盘成每日聚合。"""

    MAX_TITLES = 40

    def __init__(self):
        self.day = date.today().isoformat()
        self.apps = {}
        self.dirty = False
        self._load()

    def _path(self, day=None):
        return os.path.join(USAGE_DIR, (day or self.day) + ".json")

    def _load(self):
        d = load_json(self._path(), {})
        self.apps = d.get("apps", {}) if isinstance(d, dict) else {}
        if not isinstance(self.apps, dict):
            self.apps = {}

    def roll(self):
        today = date.today().isoformat()
        if today != self.day:
            self.save(force=True)
            self.day = today
            self.apps = {}
            self._load()

    def add(self, app, title, sec=1):
        if not app:
            return
        rec = self.apps.setdefault(app, {"sec": 0, "titles": {}})
        rec["sec"] = int(rec.get("sec", 0)) + sec
        if title:
            titles = rec.setdefault("titles", {})
            titles[title] = int(titles.get(title, 0)) + sec
        self.dirty = True

    def save(self, force=False):
        if not self.dirty and not force:
            return
        for rec in self.apps.values():
            titles = rec.get("titles") or {}
            if len(titles) > self.MAX_TITLES:
                top = dict(sorted(titles.items(), key=lambda kv: -kv[1])[:self.MAX_TITLES])
                rest = sum(v for k, v in titles.items() if k not in top)
                if rest > 0:
                    top["（其他）"] = top.get("（其他）", 0) + rest
                rec["titles"] = top
        try:
            os.makedirs(USAGE_DIR, exist_ok=True)
            save_json(self._path(), {"day": self.day, "apps": self.apps})
            self.dirty = False
        except Exception:
            log_error(traceback.format_exc())


def load_usage(days=1, end_offset=0):
    """合并最近 days 天的使用数据；end_offset 表示从几天前开始往前算。"""
    out = {}
    newest = date.today() - timedelta(days=max(0, int(end_offset)))
    for i in range(max(1, int(days))):
        d = (newest - timedelta(days=i)).isoformat()
        data = load_json(os.path.join(USAGE_DIR, d + ".json"), {})
        apps = data.get("apps", {}) if isinstance(data, dict) else {}
        if not isinstance(apps, dict):
            continue
        for app, rec in apps.items():
            cur = out.setdefault(app, {"sec": 0, "titles": {}})
            cur["sec"] += int(rec.get("sec", 0) or 0)
            for k, v in (rec.get("titles") or {}).items():
                cur["titles"][k] = cur["titles"].get(k, 0) + int(v or 0)
    return out


def log_in_range(days=1, end_offset=0):
    """按日期取记录。"""
    newest_d = date.today() - timedelta(days=max(0, int(end_offset)))
    oldest_d = newest_d - timedelta(days=max(1, int(days)) - 1)
    lo, hi = oldest_d.isoformat(), newest_d.isoformat()
    return [r for r in read_log() if lo <= str(r.get("day", "")) <= hi]


# ---------------------------------------------------------------- WebDAV

def webdav_test(cfg):
    """对配置的目录做一次 PROPFIND。返回 (是否可用, 说明)。"""
    url = str(cfg.get("sync_url") or "").strip()
    user = str(cfg.get("sync_user") or "").strip()
    pwd = str(cfg.get("sync_pass") or "")
    if not url:
        return False, "先填服务器地址。"
    if not url.lower().startswith(("http://", "https://")):
        return False, "地址要以 http:// 或 https:// 开头。"
    if not url.endswith("/"):
        url += "/"
    req = urllib.request.Request(url, method="PROPFIND")
    req.add_header("Depth", "0")
    req.add_header("Content-Type", "application/xml; charset=utf-8")
    if user or pwd:
        token = base64.b64encode(f"{user}:{pwd}".encode("utf-8")).decode("ascii")
        req.add_header("Authorization", "Basic " + token)
    try:
        with urllib.request.urlopen(req, timeout=12) as r:
            return True, f"连上了，HTTP {r.status}。"
    except urllib.error.HTTPError as e:
        if e.code in (200, 207):
            return True, f"连上了，HTTP {e.code}。"
        if e.code == 401:
            return False, "账号或密码不对（HTTP 401）。"
        if e.code == 403:
            return False, "拒绝访问（HTTP 403），检查账号权限。"
        if e.code == 404:
            return True, "账号能通过，但这个目录还不存在，同步时会自动建。"
        if e.code == 405:
            return True, "服务器不支持 PROPFIND，但地址可达。"
        return False, f"服务器返回 HTTP {e.code}。"
    except urllib.error.URLError as e:
        return False, f"连不上：{e.reason}"
    except Exception as e:
        return False, f"出错：{e}"


REST_TIPS = [
    ("喝一杯水", "起身去接水，喝完再回来。别拿咖啡或茶代替水。"),
    ("看向窗外最远的地方", "找一个能看见的最远物体，盯 20 秒。让眼睛的调焦肌松一下。"),
    ("闭上眼睛，什么都不看", "闭眼 20 秒。屏幕不会跑，天也不会塌。"),
    ("站起来走两步", "走到房间另一头再回来。让血往下半身走一走。"),
    ("慢慢转一下脖子", "向左看到肩膀，停 3 秒；再向右。别用力甩。"),
    ("把肩膀放下来", "耸肩到耳朵，停 5 秒，然后彻底放松。重复三次。"),
    ("甩甩手腕", "十指张开再握拳，重复十次。这是给鼠标手的。"),
    ("深呼吸三次", "吸 4 秒，屏 7 秒，呼 8 秒。就三次，不用更多。"),
    ("站起来后仰一下", "手扶后腰，慢慢后仰 10 秒，把蜷着的腰打开。"),
    ("看看远处的人或物", "抬头，视线离开屏幕，看 20 秒。"),
    ("把手机推到够不着的地方", "顺手推远，再回来坐下。"),
    ("揉一下眼睛", "闭眼，用指腹轻按眼眶四周。别压眼球。"),
    ("去趟厕所或倒杯水", "离开这张椅子两分钟，比什么拉伸都管用。"),
    ("把坐姿调回来", "脚踩实地面，屏幕降到眼睛平视略低。"),
]

_last_tip = {"i": -1}


def pick_rest_tip():
    """随机挑一条，尽量不和上一条重复。"""
    n = len(REST_TIPS)
    if n <= 1:
        return REST_TIPS[0]
    i = random.randrange(n)
    if i == _last_tip["i"]:
        i = (i + 1) % n
    _last_tip["i"] = i
    return REST_TIPS[i]


# ---------------------------------------------------------------- 小控件

class Switch(tk.Canvas):
    """自绘开关，避免各平台 Checkbutton 长得不一样。"""

    def __init__(self, master, var, bg=BG, **kw):
        super().__init__(master, width=42, height=24, bg=bg,
                         highlightthickness=0, bd=0, **kw)
        self.var = var
        self.configure(cursor="hand2")
        self.bind("<Button-1>", self._toggle)
        try:
            self.var.trace_add("write", lambda *a: self._draw())
        except Exception:
            pass
        self._draw()

    def _draw(self):
        self.delete("all")
        on = bool(self.var.get())
        track = ACCENT if on else "#D1D5DB"
        self.create_oval(1, 3, 19, 21, fill=track, outline="")
        self.create_oval(23, 3, 41, 21, fill=track, outline="")
        self.create_rectangle(10, 3, 32, 21, fill=track, outline="")
        cx = 32 if on else 10
        self.create_oval(cx - 8, 4, cx + 8, 20, fill=BG, outline="")

    def _toggle(self, _=None):
        self.var.set(0 if self.var.get() else 1)


class ScrollFrame(tk.Frame):
    """竖向滚动容器。"""

    def __init__(self, master, bg=BG):
        super().__init__(master, bg=bg)
        self.canvas = tk.Canvas(self, bg=bg, highlightthickness=0, bd=0)
        self.vsb = tk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=self.vsb.set)
        self.vsb.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.inner = tk.Frame(self.canvas, bg=bg)
        self._win = self.canvas.create_window((0, 0), window=self.inner, anchor="nw")
        self.inner.bind("<Configure>", self._sync_region)
        self.canvas.bind("<Configure>", self._sync_width)
        self.canvas.bind("<Enter>", lambda e: self._bind_wheel(True))
        self.canvas.bind("<Leave>", lambda e: self._bind_wheel(False))

    def _sync_region(self, _=None):
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def _sync_width(self, event):
        self.canvas.itemconfigure(self._win, width=event.width)

    def _bind_wheel(self, on):
        seq = "<MouseWheel>"
        if on:
            self.canvas.bind_all(seq, self._wheel)
        else:
            self.canvas.unbind_all(seq)

    def _wheel(self, event):
        try:
            self.canvas.yview_scroll(int(-event.delta / 120), "units")
        except Exception:
            pass


class PromptText(tk.Text):
    """带占位符的输入框。占位符不算内容。可选禁止粘贴。"""

    def __init__(self, master, placeholder="", block_paste=True, **kw):
        super().__init__(master, **kw)
        self._ph = placeholder
        self._has_ph = False
        self.tag_configure("ph", foreground=FAINT)
        self.bind("<FocusIn>", self._clear_ph)
        self.bind("<FocusOut>", self._restore_ph)
        if block_paste:
            for seq in ("<<Paste>>", "<Control-v>", "<Control-V>",
                        "<Shift-Insert>", "<Button-3>"):
                self.bind(seq, lambda e: "break")
        self._put_ph()

    def _put_ph(self):
        if not super().get("1.0", "end-1c").strip():
            super().delete("1.0", "end")
            super().insert("1.0", self._ph, "ph")
            self._has_ph = True

    def _clear_ph(self, _=None):
        if self._has_ph:
            super().delete("1.0", "end")
            self._has_ph = False

    def _restore_ph(self, _=None):
        if not super().get("1.0", "end-1c").strip():
            self._put_ph()

    def value(self):
        if self._has_ph:
            return ""
        return super().get("1.0", "end-1c").strip()

    def reset(self, placeholder=None):
        if placeholder is not None:
            self._ph = placeholder
        super().delete("1.0", "end")
        self._has_ph = False
        self._put_ph()


def bind_tree(widget, seq, func):
    widget.bind(seq, func)
    for child in widget.winfo_children():
        bind_tree(child, seq, func)


def button(parent, text, cmd, kind="ghost", **kw):
    if kind == "primary":
        cfg = dict(bg=ACCENT, fg=BG, activebackground=ACCENT_DARK,
                   activeforeground=BG, font=(FONT, 10))
    elif kind == "danger":
        cfg = dict(bg=BG, fg=DANGER, activebackground=SOFT,
                   activeforeground=DANGER, font=(FONT, 9),
                   highlightbackground=LINE, highlightthickness=1)
    else:
        cfg = dict(bg="#EFF2F6", fg=INK, activebackground="#E2E7EE", activeforeground=INK,
                   font=(FONT, 9), highlightbackground="#DDE2E9", highlightthickness=1)
    cfg.update(kw)
    return tk.Button(parent, text=text, command=cmd, relief="flat", bd=0,
                     cursor="hand2", padx=16, pady=7, **cfg)


# ---------------------------------------------------------------- 弹窗

POPUP_W = 620
POPUP_H = 500


class AskPopup:
    def __init__(self, app, reason="normal", rec_id=None, shot=None):
        self.app = app
        self.reason = reason
        self.rec_id = rec_id or uuid.uuid4().hex[:16]
        self.shot = shot or ""
        self.mode = "normal"
        self.closed = False
        self.tip = pick_rest_tip() if reason == "rest" else None
        self.opened_at = time.time()
        cooldown = float(app.cfg.get("cooldown_seconds", 0) or 0)
        self.ready_at = self.opened_at + max(0.0, cooldown)
        self._after_id = None

        w = tk.Toplevel(app.root)
        self.win = w
        w.overrideredirect(True)
        w.attributes("-topmost", True)
        w.configure(bg=EDGE)
        w.protocol("WM_DELETE_WINDOW", lambda: None)
        w.bind("<Escape>", lambda e: "break")
        w.bind("<Alt-F4>", lambda e: "break")

        sw, sh = w.winfo_screenwidth(), w.winfo_screenheight()
        x = max(0, (sw - POPUP_W) // 2)
        y = max(0, int((sh - POPUP_H) * 0.26))
        w.geometry(f"{POPUP_W}x{POPUP_H}+{x}+{y}")

        shell = tk.Frame(w, bg=BG)
        shell.pack(fill="both", expand=True, padx=1, pady=1)

        tk.Frame(shell, bg=ACCENT, height=5).pack(fill="x")

        body = tk.Frame(shell, bg=BG)
        body.pack(fill="both", expand=True, padx=32, pady=(20, 18))

        self.title_lbl = tk.Label(
            body, text=self._title(), bg=BG, fg=INK,
            font=(FONT, 19, "bold"), anchor="w",
        )
        self.title_lbl.pack(fill="x")

        self.sub_lbl = tk.Label(
            body, text=self._subtitle(), bg=BG, fg=MUTED,
            font=(FONT, 10), anchor="w", justify="left",
        )
        self.sub_lbl.pack(fill="x", pady=(7, 0))

        last = app.last_answer
        if self.tip:
            card = tk.Frame(body, bg="#FFF6E5")
            card.pack(fill="x", pady=(14, 0))
            tk.Label(card, text="现在做这件事", bg="#FFF6E5", fg="#8A5200",
                     font=(FONT, 8), anchor="w").pack(fill="x", padx=14, pady=(10, 0))
            tk.Label(card, text=self.tip[0], bg="#FFF6E5", fg="#7A3E00",
                     font=(FONT, 15, "bold"), anchor="w").pack(fill="x", padx=14, pady=(2, 0))
            tk.Label(card, text=self.tip[1], bg="#FFF6E5", fg="#8A5200",
                     font=(FONT, 9), anchor="w", justify="left",
                     wraplength=POPUP_W - 110).pack(fill="x", padx=14, pady=(4, 12))
        elif last:
            prev = tk.Frame(body, bg=SOFT)
            prev.pack(fill="x", pady=(14, 0))
            tk.Label(prev, text="上次你说", bg=SOFT, fg=FAINT,
                     font=(FONT, 8), anchor="w").pack(fill="x", padx=12, pady=(8, 0))
            tk.Label(prev, text=self._clip(last, 46) + "　·　" + fmt_ago(app.last_ts),
                     bg=SOFT, fg=MUTED, font=(FONT, 10), anchor="w",
                     justify="left", wraplength=POPUP_W - 100).pack(
                fill="x", padx=12, pady=(2, 9))

        box = tk.Frame(body, bg=BG, highlightbackground=LINE, highlightthickness=1)
        box.pack(fill="x", pady=(16, 0))
        self.text = PromptText(
            box, placeholder="在这里写。",
            block_paste=bool(app.cfg.get("block_paste", 1)),
            font=(FONT, 12), fg=INK, bg=BG, relief="flat",
            wrap="word", height=4, width=1, padx=12, pady=10,
            insertbackground=INK, highlightthickness=0,
        )
        self.text.pack(fill="both", expand=True)
        self.text.bind("<KeyRelease>", self._on_key)
        self.text.bind("<Control-Return>", lambda e: (self.submit("normal"), "break")[1])

        self.hint = tk.Label(
            body, text=self._hint_text(), bg=BG, fg=MUTED,
            font=(FONT, 10), anchor="w", justify="left",
            wraplength=POPUP_W - 80,
        )
        self.hint.pack(fill="x", pady=(9, 0))

        row = tk.Frame(body, bg=BG)
        row.pack(fill="x", pady=(14, 0))

        self.btn = tk.Button(
            row, text="说清楚了，继续", command=lambda: self.submit("normal"),
            bg=ACCENT, fg=BG, activebackground=ACCENT_DARK, activeforeground=BG,
            font=(FONT, 11), relief="flat", bd=0, cursor="hand2",
            padx=24, pady=9,
        )
        self.btn.pack(side="left")

        self.moyu_btn = tk.Button(
            row, text="我承认在摸鱼", command=self.switch_to_moyu,
            bg=SOFT, fg=MUTED, activebackground=LINE, activeforeground=INK,
            font=(FONT, 10), relief="flat", bd=0, cursor="hand2",
            padx=16, pady=9,
        )
        # 起身提醒那类弹窗本来就没有这个按钮；设置里也能关掉。
        # 关掉之后「承认摸鱼」这条路走不通，但弹窗本身照旧——它只是少一个出口。
        if not self.tip and int(app.cfg.get("show_moyu", 1)):
            self.moyu_btn.pack(side="left", padx=(10, 0))

        self.counter = tk.Label(
            row, text="0 字", bg=BG, fg=FAINT, font=(FONT, 10), anchor="e",
        )
        self.counter.pack(side="right")

        tk.Label(
            body, text="关不掉。写完就放你走。",
            bg=BG, fg=MUTED, font=(FONT, 9), anchor="w",
        ).pack(fill="x", pady=(14, 0))

        if app.cfg.get("sound", 1):
            beep(app.cfg)

        self._on_key()
        self._tick_cooldown()
        w.after(90, self._grab_focus)

    @staticmethod
    def _clip(s, n):
        s = (s or "").replace("\n", " ").strip()
        return s if len(s) <= n else s[:n] + "…"

    def _title(self):
        if self.reason == "rest":
            return "该起身了"
        if self.reason == "long":
            return "你还没起身"
        if self.reason == "back":
            return "回来了，把刚才那条补上"
        return "现在，你在做什么？"

    def _subtitle(self):
        n = self.app.day_count + 1
        s = self.app.session_seconds()
        # 刚打开就手动点「立刻问我一次」时 s 是 0，说「已经连续用了 0 秒」很傻
        span = fmt_span(s) if s >= 60 else "不到一分钟"
        if self.reason in ("long", "rest"):
            return f"你已经连续用了 {span} · 今天第 {n} 次"
        return f"今天第 {n} 次 · 已经连续用了 {span}"

    def _hint_text(self):
        if self.reason == "rest":
            return "回来之后，写一句你做了什么。写完就放你走。"
        if self.reason == "long":
            return "写一句：你打算什么时候起来，起来做什么。"
        if self.reason == "back":
            return "写一句：刚才那段时间，你实际在做什么。"
        return "写一句话：你在做什么，以及为什么现在必须做它。"

    def _grab_focus(self):
        if self.closed:
            return
        force_foreground(self.win)
        self.win.lift()
        self.text.focus_force()

    def _on_key(self, _=None):
        n = len(self.text.value())
        self.counter.config(text=f"{n} 字")
        ready = time.time() >= self.ready_at
        minc = int(self.app.cfg["min_chars"])
        self.btn.config(bg=ACCENT if (n >= minc and ready) else ACCENT_PALE)

    def _tick_cooldown(self):
        if self.closed:
            return
        left = self.ready_at - time.time()
        if left > 0.02:
            txt = ("等 %.1f 秒" % left) if left < 10 else ("等 %d 秒" % int(left + 0.999))
            self.btn.config(text=txt, state="disabled")
            self.hint.config(text="先想清楚。倒计时结束才放行。", fg=MUTED)
        else:
            if str(self.btn.cget("state")) == "disabled":
                self.btn.config(text="说清楚了，继续", state="normal")
                self.hint.config(text=self._hint_text(), fg=MUTED)
        self._on_key()
        self._after_id = self.win.after(100, self._tick_cooldown)

    def switch_to_moyu(self):
        self.mode = "moyu"
        self.title_lbl.config(text="行。那接下来 10 分钟，你打算做什么？")
        self.hint.config(
            text="别写「继续摸鱼」。写一件具体的小事，5 分钟内能开始的。", fg=MUTED
        )
        self.moyu_btn.pack_forget()
        self.text.reset("比如：把那份表格的第一列填完。")
        self.counter.config(text="0 字")
        self.btn.config(bg=ACCENT_PALE)
        self.text.focus_force()

    def submit(self, kind="normal"):
        txt = self.text.value()
        minc = int(self.app.cfg["min_chars"])

        if time.time() < self.ready_at:
            self.hint.config(text="倒计时还没走完。", fg=DANGER)
            self.text.focus_force()
            return

        if len(txt) < minc:
            self.hint.config(text=f"太短了。至少写 {minc} 个字。", fg=DANGER)
            self.text.focus_force()
            return

        self.app.record(txt, self.mode, trigger_ts=self.opened_at,
                        rec_id=self.rec_id, shot=self.shot)
        self.close()

    def close(self):
        if self.closed:
            return
        self.closed = True
        if self._after_id:
            try:
                self.win.after_cancel(self._after_id)
            except Exception:
                pass
        try:
            self.win.destroy()
        except Exception:
            pass
        self.app.popup = None


# ---------------------------------------------------------------- 状态小条

class Pill:
    """
    右下角那条状态小条。

    能收起来——不是隐藏。隐藏起来用户就找不到了，转头又双击一次 exe，于是两个
    实例并排跑（这正是"两条时间线"的老毛病）。

    收起后有两种形态，设置里选（`collapse_tray`）：

    - **系统托盘图标**（默认）：真正进任务栏托盘，不占桌面。Win11 会把新注册的
      图标先塞进「^」溢出面板，`Tray._promote()` 会把它提到任务栏上；提不上来
      时用户还能去「任务栏设置 → 其他系统托盘图标」手动打开。
      左键点图标 = 展开，右键 = 菜单。
    - **悬浮小方块**：24×24，就是应用图标本身（深色方块 + 橙色对话框），
      右下角一个小圆点表示状态。托不起托盘的时候自动退到这条。

    两种形态都收得回来：托盘靠点图标，小方块靠点它自己；万一都不行，
    再双击一次 exe 也会把它叫出来（见 `poke_existing`）。
    """

    W, H = 214, 40
    MINI = 24

    def __init__(self, app):
        self.app = app
        w = tk.Toplevel(app.root)
        self.win = w
        w.overrideredirect(True)
        w.attributes("-topmost", True)
        w.attributes("-alpha", 0.94)
        w.configure(bg=EDGE)

        sw, sh = w.winfo_screenwidth(), w.winfo_screenheight()
        # 记右边缘和下边缘，而不是左上角：展开/收起时宽高会变，
        # 按右下角对齐，小条才不会一边缩一边跳。
        self._right = sw - 26
        self._bottom = sh - 74
        self.collapsed = False
        w.geometry(f"{self.W}x{self.H}+{self._right - self.W}+{self._bottom - self.H}")

        outer = tk.Frame(w, bg=BG)
        outer.pack(fill="both", expand=True, padx=1, pady=1)
        self.outer = outer

        self.dot = tk.Label(outer, text="●", fg=OK, bg=BG, font=(FONT, 9))
        self.dot.pack(side="left", padx=(12, 0))

        self.lbl = tk.Label(
            outer, text="准备中", fg=INK, bg=BG, font=(FONT, 9), anchor="w"
        )
        self.lbl.pack(side="left", padx=(7, 0), fill="x", expand=True)

        # 收起后显示的那个小方块：就是应用图标本身，24×24。
        # 状态靠右下角那个小圆点表达；收进托盘时靠鼠标悬停的提示文字。
        n = self.MINI
        c = tk.Canvas(w, width=n, height=n, bg=ICON_BG,
                      highlightthickness=0, bd=0)
        self.icon = c
        self._img = tk.PhotoImage(data=_ICON_PNG)     # 必须留引用，否则被回收
        c.create_image(0, 0, anchor="nw", image=self._img)
        self.mini_dot = c.create_oval(n - 8.5, n - 8.5, n - 0.5, n - 0.5,
                                      fill=OK, outline=BG, width=1)
        self._state_color = OK

        self._drag = None
        self._moved = False
        bind_tree(w, "<Button-1>", self._press)
        bind_tree(w, "<B1-Motion>", self._drag_move)
        bind_tree(w, "<ButtonRelease-1>", self._release)
        bind_tree(w, "<Button-3>", self._menu)

    def _apply(self):
        w, h = (self.MINI, self.MINI) if self.collapsed else (self.W, self.H)
        try:
            self.win.geometry(f"{w}x{h}+{self._right - w}+{self._bottom - h}")
            self.win.lift()
        except Exception:
            log_error(traceback.format_exc())

    def _use_tray(self):
        """这一版该收进托盘还是缩成悬浮小图标。托盘注册不上就退回悬浮。"""
        return bool(int(self.app.cfg.get("collapse_tray", 1))) and self.app.tray_ok()

    def _remember(self, flag):
        """记住"收着"还是"展开"，下次启动照原样来。

        不然每次开机都得手动再收一遍——用托盘的人尤其烦这个。
        """
        try:
            if int(self.app.cfg.get("pill_collapsed", 0)) != flag:
                self.app.cfg["pill_collapsed"] = flag
                save_cfg(self.app.cfg)
        except Exception:
            pass

    def collapse(self):
        if self.collapsed:
            return
        self.collapsed = True
        self._remember(1)
        if self._use_tray():
            # 进托盘：小条整个收掉，桌面上什么都不留
            try:
                self.win.withdraw()
            except Exception:
                pass
            self.app.tray_show(self.lbl.cget("text"))
            return
        self.outer.pack_forget()
        self.icon.pack()
        self._apply()

    def expand(self):
        if not self.collapsed:
            return
        self.collapsed = False
        self._remember(0)
        self.app.tray_hide()
        try:
            self.win.deiconify()
        except Exception:
            pass
        self.icon.pack_forget()
        self.outer.pack(fill="both", expand=True, padx=1, pady=1)
        self._apply()

    def toggle(self):
        if self.collapsed:
            self.expand()
        else:
            self.collapse()

    def flash(self, times=3):
        """闪几下边框。用户多半是没找到这条才又双击了一次 exe，得给点动静。"""
        def step(i):
            try:
                if not self.win.winfo_exists():
                    return
                if i >= times * 2:
                    if self.collapsed:
                        self.icon.itemconfig(self.mini_dot, fill=self._state_color)
                    else:
                        self.win.configure(bg=EDGE)
                    return
                hot = (i % 2 == 0)
                if self.collapsed:
                    # 收起时没有边框可闪，就闪状态点
                    self.icon.itemconfig(self.mini_dot,
                                         fill=BG if hot else self._state_color)
                else:
                    self.win.configure(bg=ACCENT if hot else EDGE)
                self.win.after(180, lambda: step(i + 1))
            except Exception:
                pass
        step(0)

    def _press(self, e):
        self._drag = (e.x_root - self.win.winfo_x(), e.y_root - self.win.winfo_y())
        self._moved = False

    def _drag_move(self, e):
        if not self._drag:
            return
        self._moved = True
        x = e.x_root - self._drag[0]
        y = e.y_root - self._drag[1]
        self.win.geometry(f"+{x}+{y}")
        self._right = x + self.win.winfo_width()
        self._bottom = y + self.win.winfo_height()

    def _release(self, _=None):
        if not self._moved:
            if self.collapsed:
                self.expand()
            else:
                self.app.open_history()
        self._drag = None

    def _menu(self, e):
        m = build_pill_menu(self.app, self.win, self.collapsed)
        try:
            m.tk_popup(e.x_root, e.y_root)
        finally:
            m.grab_release()

    def set_state(self, text, color):
        try:
            if not self.win.winfo_exists():
                return
            self.lbl.config(text=text)
            self.dot.config(fg=color)
            self._state_color = color
            # 收起后没有文字，状态全靠右下角那个小圆点的颜色表达
            self.icon.itemconfig(self.mini_dot, fill=color)
            # 收进托盘时，状态靠鼠标悬停的提示文字
            if self.collapsed and self._use_tray():
                self.app.tray_tip(text)
        except Exception:
            pass


def build_pill_menu(app, parent, collapsed):
    """小条右键菜单。小条和托盘图标共用同一份——两处各写一份迟早会走岔。"""
    m = tk.Menu(parent, tearoff=0)
    m.add_command(label="看记录", command=app.open_history)
    m.add_command(label="行为统计", command=app.open_usage)
    m.add_command(label="立刻问我一次", command=lambda: app.show_popup("normal"))
    m.add_separator()
    if app.cfg.get("enabled", 1):
        m.add_command(label="关闭提醒（总开关）", command=app.set_enabled_false)
    else:
        m.add_command(label="打开提醒（总开关）", command=app.set_enabled_true)
    m.add_separator()
    m.add_command(label="暂停 30 分钟", command=lambda: app.pause(30))
    m.add_command(label="暂停 1 小时", command=lambda: app.pause(60))
    m.add_command(label="今天不再提醒", command=app.pause_until_tomorrow)
    m.add_command(label="立刻恢复", command=lambda: app.pause(0))
    m.add_separator()
    m.add_command(label="展开小条" if collapsed else "收起来",
                  command=app.toggle_pill)
    m.add_command(label="设置", command=app.open_settings)
    m.add_command(label="退出", command=app.quit)
    return m


# ---------------------------------------------------------------- 系统托盘

WM_TRAY = 0x8001            # WM_APP + 1，托盘回调消息
NIM_ADD, NIM_MODIFY, NIM_DELETE = 0, 1, 2
NIF_MESSAGE, NIF_ICON, NIF_TIP = 0x01, 0x02, 0x04
IMAGE_ICON = 1
LR_LOADFROMFILE = 0x10
IDI_APPLICATION = 32512
WM_LBUTTONUP = 0x0202
WM_LBUTTONDBLCLK = 0x0203
WM_RBUTTONUP = 0x0205
WM_CLOSE = 0x0010
# 新版 Shell 左键不再发 WM_LBUTTONUP，改发这两个（都是 WM_USER 段）
NIN_SELECT = 0x0400
NIN_KEYSELECT = 0x0401


class GUID(ctypes.Structure):
    _fields_ = [("Data1", wintypes.DWORD),
                ("Data2", wintypes.WORD),
                ("Data3", wintypes.WORD),
                ("Data4", ctypes.c_byte * 8)]


class NOTIFYICONDATAW(ctypes.Structure):
    """Win10/11 用的完整版（976 字节）。少一个字段 Shell_NotifyIconW 就返回失败。"""
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("hWnd", wintypes.HWND),
        ("uID", wintypes.UINT),
        ("uFlags", wintypes.UINT),
        ("uCallbackMessage", wintypes.UINT),
        ("hIcon", wintypes.HICON),
        ("szTip", wintypes.WCHAR * 128),
        ("dwState", wintypes.DWORD),
        ("dwStateMask", wintypes.DWORD),
        ("szInfo", wintypes.WCHAR * 256),
        ("uVersion", wintypes.UINT),
        ("szInfoTitle", wintypes.WCHAR * 64),
        ("dwInfoFlags", wintypes.DWORD),
        ("guidItem", GUID),
        ("hBalloonIcon", wintypes.HICON),
    ]


WNDPROC = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, wintypes.HWND, wintypes.UINT,
                             wintypes.WPARAM, wintypes.LPARAM)


class WNDCLASSW(ctypes.Structure):
    _fields_ = [("style", wintypes.UINT),
                ("lpfnWndProc", WNDPROC),
                ("cbClsExtra", ctypes.c_int),
                ("cbWndExtra", ctypes.c_int),
                ("hInstance", wintypes.HINSTANCE),
                ("hIcon", wintypes.HICON),
                ("hCursor", wintypes.HANDLE),
                ("hbrBackground", wintypes.HBRUSH),
                ("lpszMenuName", wintypes.LPCWSTR),
                ("lpszClassName", wintypes.LPCWSTR)]


class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]


class MSG(ctypes.Structure):
    """注意最后的 lPrivate：Win8 之后加的字段，少了它 sizeof 会小 8 字节，
    GetMessageW 往里写就越界了。"""
    _fields_ = [("hwnd", wintypes.HWND),
                ("message", wintypes.UINT),
                ("wParam", wintypes.WPARAM),
                ("lParam", wintypes.LPARAM),
                ("time", wintypes.DWORD),
                ("pt", POINT),
                ("lPrivate", wintypes.DWORD)]


def _tray_icon():
    """拿一个 HICON。

    打包后 exe 里就嵌着 grill.ico，直接抽第 0 个；跑源码时读仓库里的那份；
    都没有就退到系统默认图标——图标丑一点，总比托盘里什么都没有强。
    """
    u = ctypes.WinDLL("user32", use_last_error=True)
    try:
        sh = ctypes.WinDLL("shell32", use_last_error=True)
        sh.ExtractIconExW.argtypes = [wintypes.LPCWSTR, ctypes.c_int,
                                      ctypes.POINTER(wintypes.HICON),
                                      ctypes.POINTER(wintypes.HICON),
                                      wintypes.UINT]
        sh.ExtractIconExW.restype = wintypes.UINT
        big, small = wintypes.HICON(), wintypes.HICON()
        if sh.ExtractIconExW(sys.executable, 0, ctypes.byref(big),
                             ctypes.byref(small), 1) > 0:
            if small.value:
                return small
            if big.value:
                return big
    except Exception:
        pass
    try:
        ico = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "build", "res", "grill.ico")
        if os.path.exists(ico):
            u.LoadImageW.argtypes = [wintypes.HINSTANCE, wintypes.LPCWSTR,
                                     wintypes.UINT, ctypes.c_int, ctypes.c_int,
                                     wintypes.UINT]
            u.LoadImageW.restype = wintypes.HANDLE
            h = u.LoadImageW(None, ico, IMAGE_ICON, 0, 0, LR_LOADFROMFILE)
            if h:
                return wintypes.HICON(h)
    except Exception:
        pass
    u.LoadIconW.argtypes = [wintypes.HINSTANCE, wintypes.LPCWSTR]
    u.LoadIconW.restype = wintypes.HICON
    return u.LoadIconW(None, ctypes.cast(IDI_APPLICATION, wintypes.LPCWSTR))


class Tray:
    """右下角的系统托盘图标。

    **为什么用"独立线程 + 队列"，而不是窗口过程回调 Python**：

    最自然的写法是 `CreateWindowExW` 时挂一个 ctypes 的 `WNDPROC`，在里面直接
    处理鼠标事件。实测在 CPython 3.14 + Tk 上这条路会直接把进程打崩：

        Fatal Python error: PyEval_RestoreThread: the function must be called
        with the GIL held ... (the current Python thread state is NULL)

    因为 Tk 的 mainloop 是在**释放 GIL** 的状态下调 `DispatchMessageW` 的，消息
    一路派发到我们的 Python 回调时，线程状态是空的。点一下托盘图标，程序就没了。

    所以这里反过来：窗口过程挂系统的 `DefWindowProcW`（纯 C，不回调 Python），
    自己开一个线程 `GetMessageW` 把消息取出来，只把"发生了什么"丢进队列；
    主线程用 `root.after` 定期捞队列再动界面。全程没有 C→Python 的回调。

    Win11 会把新注册的图标塞进「^」溢出面板、默认不显示，`_promote()` 里
    把对应的注册表项打开——见那里的注释。
    """

    CLASS = "GrillMeTrayWnd"
    _last_tip = "质问我"

    def __init__(self, app, tip="质问我"):
        self.app = app
        self.ok = False
        self.hwnd = None
        self._nid = None
        self._icon = None
        self._added = False
        self._taskbar_created = 0
        self._stop = False
        self._q = queue.Queue()
        self._ready = threading.Event()
        self._thread = None
        self._last_tip = tip or "质问我"
        try:
            self._thread = threading.Thread(target=self._run, name="grill-tray",
                                            daemon=True)
            self._thread.start()
            if not self._ready.wait(5) or not self.hwnd:
                raise OSError("托盘窗口没建起来")
            # 先加一次再删掉：只为了探"这台机器能不能注册托盘图标"，
            # 图标本身等真的收起时才挂上去。
            self.ok = self._add(self._last_tip)
            if self.ok:
                self.remove()
            self.app.root.after(150, self._pump)
        except Exception:
            log_error(traceback.format_exc())
            self.ok = False

    # ---- 后台线程：建窗口 + 取消息

    def _run(self):
        try:
            u = ctypes.WinDLL("user32", use_last_error=True)
            k = ctypes.WinDLL("kernel32", use_last_error=True)
            u.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT,
                                         wintypes.WPARAM, wintypes.LPARAM]
            u.DefWindowProcW.restype = ctypes.c_ssize_t
            u.RegisterClassW.argtypes = [ctypes.POINTER(WNDCLASSW)]
            u.CreateWindowExW.argtypes = [wintypes.DWORD, wintypes.LPCWSTR,
                                          wintypes.LPCWSTR, wintypes.DWORD,
                                          ctypes.c_int, ctypes.c_int,
                                          ctypes.c_int, ctypes.c_int,
                                          wintypes.HWND, wintypes.HMENU,
                                          wintypes.HINSTANCE, wintypes.LPVOID]
            u.CreateWindowExW.restype = wintypes.HWND
            u.RegisterWindowMessageW.argtypes = [wintypes.LPCWSTR]
            u.RegisterWindowMessageW.restype = wintypes.UINT
            u.GetMessageW.argtypes = [ctypes.POINTER(MSG), wintypes.HWND,
                                      wintypes.UINT, wintypes.UINT]
            u.GetMessageW.restype = ctypes.c_int
            k.GetModuleHandleW.argtypes = [wintypes.LPCWSTR]
            k.GetModuleHandleW.restype = wintypes.HINSTANCE

            self._u = u
            # 窗口过程直接用系统的，不挂 Python 回调（见类注释）
            proc = ctypes.cast(u.DefWindowProcW, WNDPROC)
            hinst = k.GetModuleHandleW(None)
            wc = WNDCLASSW()
            wc.style = 0
            wc.lpfnWndProc = proc
            wc.cbClsExtra = 0
            wc.cbWndExtra = 0
            wc.hInstance = hinst
            wc.hIcon = None
            wc.hCursor = None
            wc.hbrBackground = None
            wc.lpszMenuName = None
            wc.lpszClassName = self.CLASS
            u.RegisterClassW(ctypes.byref(wc))

            # 一个永远不显示的普通窗口，只用来收托盘消息。
            # 用 HWND_MESSAGE（消息专用窗口）收不到任务栏重建的广播。
            self.hwnd = u.CreateWindowExW(0, self.CLASS, "GrillMe", 0,
                                          0, 0, 0, 0, None, None, hinst, None)
            if not self.hwnd:
                return
            # 资源管理器重启后托盘里的图标会全部消失，必须自己加回来
            self._taskbar_created = u.RegisterWindowMessageW("TaskbarCreated")
            self._ready.set()

            msg = MSG()
            tray_dbg(f"消息循环开始 hwnd=0x{self.hwnd:X} "
                     f"callback=0x{WM_TRAY:X} taskbar_created=0x{self._taskbar_created:X} "
                     f"sizeof(MSG)={ctypes.sizeof(MSG)}")
            while not self._stop:
                # 不按 hwnd 过滤：万一 Shell 把回调投到了别的窗口（或者投成线程
                # 消息），过滤掉就永远查不出来。这个线程只有这一个窗口，放宽无害。
                r = u.GetMessageW(ctypes.byref(msg), None, 0, 0)
                if r == 0 or r == -1:
                    tray_dbg(f"GetMessage 返回 {r}，循环退出")
                    break
                tray_dbg(f"收到消息 0x{msg.message:X} hwnd=0x{(msg.hwnd or 0):X} "
                         f"wParam=0x{msg.wParam:X} lParam=0x{msg.lParam:X}")
                if msg.message == WM_TRAY:
                    self._q.put(("click", msg.lParam & 0xFFFF))
                elif msg.message == WM_CLOSE:
                    break
                elif self._taskbar_created and msg.message == self._taskbar_created:
                    self._q.put(("taskbar", 0))
        except Exception:
            log_error(traceback.format_exc())
        finally:
            self._ready.set()

    # ---- 主线程：捞队列

    def _pump(self):
        try:
            while True:
                kind, ev = self._q.get_nowait()
                tray_dbg(f"pump 取到 {kind} ev=0x{ev:X}")
                if kind == "click":
                    pill = getattr(self.app, "pill", None)
                    if ev in (WM_LBUTTONUP, WM_LBUTTONDBLCLK,
                              NIN_SELECT, NIN_KEYSELECT):
                        if pill is not None:
                            tray_dbg("→ 展开小条")
                            pill.expand()
                    elif ev == WM_RBUTTONUP:
                        self._menu()
                elif kind == "taskbar":
                    self._added = False
                    self._add(self._last_tip)
        except queue.Empty:
            pass
        except Exception:
            log_error(traceback.format_exc())
        if not self._stop:
            try:
                self.app.root.after(150, self._pump)
            except Exception:
                pass

    # ---- 加/删图标

    def _add(self, tip):
        try:
            self._last_tip = (tip or "质问我")[:127]
            if self._icon is None:
                self._icon = _tray_icon()
            nid = NOTIFYICONDATAW()
            nid.cbSize = ctypes.sizeof(NOTIFYICONDATAW)
            nid.hWnd = self.hwnd
            nid.uID = 1
            nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP
            nid.uCallbackMessage = WM_TRAY
            nid.hIcon = self._icon
            nid.szTip = self._last_tip
            u = ctypes.WinDLL("shell32", use_last_error=True)
            u.Shell_NotifyIconW.argtypes = [wintypes.DWORD,
                                            ctypes.POINTER(NOTIFYICONDATAW)]
            u.Shell_NotifyIconW.restype = wintypes.BOOL
            ok = bool(u.Shell_NotifyIconW(NIM_ADD, ctypes.byref(nid)))
            self._nid = nid
            self._added = ok
            tray_dbg(f"NIM_ADD ok={ok} err={ctypes.get_last_error()} tip={self._last_tip!r}")
            return ok
        except Exception:
            log_error(traceback.format_exc())
            return False

    def set_tip(self, text):
        if not self._added or self._nid is None:
            return
        try:
            text = (text or "质问我")[:127]
            if text == self._last_tip:
                return          # 每秒的 tick 都会走到这儿，没变就别去骚扰 Shell
            self._last_tip = text
            self._nid.uFlags = NIF_TIP
            self._nid.szTip = text
            u = ctypes.WinDLL("shell32", use_last_error=True)
            u.Shell_NotifyIconW.argtypes = [wintypes.DWORD,
                                            ctypes.POINTER(NOTIFYICONDATAW)]
            u.Shell_NotifyIconW(NIM_MODIFY, ctypes.byref(self._nid))
        except Exception:
            pass

    def remove(self):
        if not self._added or self._nid is None:
            return
        try:
            u = ctypes.WinDLL("shell32", use_last_error=True)
            u.Shell_NotifyIconW.argtypes = [wintypes.DWORD,
                                            ctypes.POINTER(NOTIFYICONDATAW)]
            u.Shell_NotifyIconW(NIM_DELETE, ctypes.byref(self._nid))
            tray_dbg("NIM_DELETE")
        except Exception:
            pass
        self._added = False

    def destroy(self):
        self.remove()
        self._stop = True
        try:
            if self.hwnd:
                self._u.PostMessageW(self.hwnd, WM_CLOSE, 0, 0)
        except Exception:
            pass
        self.hwnd = None

    # ---- Win11：把它从「^」里提上来

    def _promote(self, attempt=0):
        """Win11 把新注册的托盘图标一律塞进「^」溢出面板，默认不显示。

        系统没给 API，但显示偏好是存在
        `HKCU\\Control Panel\\NotifyIconSettings\\<按 exe 路径算出来的数字>`
        底下的，`IsPromoted=1` 就等于「在任务栏上显示」。Explorer 看到这个值
        就会把图标挪出来——等于替用户去设置里点了一次。

        只改 `ExecutablePath` 正好等于我们自己那一项，别人的一概不碰。
        这一项要等 Explorer 见过图标之后才会建出来，所以得隔几秒重试几次。
        """
        if not self._added:
            return
        if not getattr(sys, "frozen", False) and not os.environ.get("GRILL_PROMOTE_TEST"):
            # 跑源码时这个键属于 python.exe，改它会把别的 Python 程序的图标也提上来
            return
        try:
            import winreg
        except Exception:
            return
        try:
            root = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                                  r"Control Panel\NotifyIconSettings")
        except OSError:
            return                      # Win10 没这个键，正常
        try:
            me = os.path.normcase(os.path.abspath(sys.executable))
            n = winreg.QueryInfoKey(root)[0]
            for i in range(n):
                name = winreg.EnumKey(root, i)
                try:
                    sk = winreg.OpenKey(root, name, 0, winreg.KEY_READ
                                        | winreg.KEY_SET_VALUE)
                except OSError:
                    continue
                try:
                    exe = str(winreg.QueryValueEx(sk, "ExecutablePath")[0] or "")
                except OSError:
                    exe = ""
                if exe and os.path.normcase(os.path.abspath(exe)) == me:
                    try:
                        cur = winreg.QueryValueEx(sk, "IsPromoted")[0]
                    except OSError:
                        cur = None
                    if cur != 1:
                        winreg.SetValueEx(sk, "IsPromoted", 0, winreg.REG_DWORD, 1)
                        tray_dbg(f"IsPromoted 置 1: {name}")
                    else:
                        tray_dbg(f"IsPromoted 已是 1: {name}")
                    return
        finally:
            try:
                winreg.CloseKey(root)
            except Exception:
                pass
        # 还没建出来，过几秒再看。三次都找不到就算了——用户自己点一次也就好了。
        if attempt < 3:
            try:
                self.app.root.after(3000, lambda: self._promote(attempt + 1))
            except Exception:
                pass

    def _menu(self):
        x, y = self.app.root.winfo_pointerxy()
        m = build_pill_menu(self.app, self.app.root, True)
        try:
            m.tk_popup(x, y)
        finally:
            m.grab_release()


# ---------------------------------------------------------------- 开机自启

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
AUTOSTART_NAME = "Grill"


def autostart_target():
    """注册表里该写什么。打包后就是 exe 自己；跑源码就用 pythonw 带脚本路径。"""
    if getattr(sys, "frozen", False):
        return '"%s"' % sys.executable
    exe = sys.executable or "python.exe"
    pyw = os.path.join(os.path.dirname(exe), "pythonw.exe")
    if os.path.exists(pyw):
        # 用 pythonw 而不是 python：开机时别给用户弹一个黑窗口
        exe = pyw
    return '"%s" "%s"' % (exe, os.path.abspath(__file__))


def autostart_get():
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            v, _ = winreg.QueryValueEx(k, AUTOSTART_NAME)
            return str(v)
    except Exception:
        return ""


def autostart_set(on):
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0,
                            winreg.KEY_SET_VALUE) as k:
            if on:
                winreg.SetValueEx(k, AUTOSTART_NAME, 0, winreg.REG_SZ,
                                  autostart_target())
            else:
                try:
                    winreg.DeleteValue(k, AUTOSTART_NAME)
                except FileNotFoundError:
                    pass
        return True
    except Exception:
        log_error(traceback.format_exc())
        return False


def _cmd_exe_path(cmd):
    cmd = (cmd or "").strip()
    if cmd.startswith('"'):
        return cmd[1:].split('"', 1)[0]
    return cmd.split(" ")[0]


# ---------------------------------------------------------------- 单实例

_MUTEX = None
_K32 = None


def _kernel32():
    """use_last_error=True 的 kernel32。

    普通 ctypes.windll 拿不到可靠的 GetLastError——中间的 ctypes 调用会把错误码
    冲掉，CreateMutexW 明明说"已存在"，GetLastError 却返回 0。
    """
    global _K32
    if _K32 is None:
        _K32 = ctypes.WinDLL("kernel32", use_last_error=True)
        _K32.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL,
                                      wintypes.LPCWSTR]
        _K32.CreateMutexW.restype = wintypes.HANDLE
    return _K32


def poke_existing():
    """
    第二个实例被拦下时，给正在跑的那个留个记号，让它把悬浮条展开。

    为什么不用消息框：无窗口进程里弹的 MessageBox 会被前台窗口盖住，
    用户照样不知道程序在哪儿；而且它会卡住那个进程直到有人点确定。
    写个文件、由对方每秒的 tick 自己发现，最省事也最不容易出错。
    """
    try:
        with open(WAKE_PATH, "w", encoding="utf-8") as f:
            f.write(str(time.time()))
        return True
    except Exception:
        return False


def acquire_single_instance():
    """
    只允许跑一个。

    两个实例各跑一套计时、各弹各的窗，还会互相覆盖 state.json——这就是
    「两条时间线在并行」的真凶：双击了两次 exe（或者开机自启之后又手动点了一次）。
    两套药丸还都停在右下角同一组坐标上，重叠成一个，看着完全不像有两个进程。
    """
    global _MUTEX
    try:
        k = _kernel32()
        _MUTEX = k.CreateMutexW(None, False, "Local\\GrillMe.SingleInstance")
        return ctypes.get_last_error() != 183      # ERROR_ALREADY_EXISTS
    except Exception:
        return True                                # 拿不到锁就照常跑，别把程序拦死


# ---------------------------------------------------------------- 主程序

class AskMe:
    def __init__(self):
        ensure_dirs()
        self.cfg = load_cfg()
        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title(TITLE)
        try:
            self.root.option_add("*Font", (FONT, 9))
        except Exception:
            pass

        self._closing = False
        self.popup = None
        self.history = None
        self.settings = None
        self.usage_win = None
        self.last_answer = ""
        self.last_ts = 0
        self.day = date.today().isoformat()
        # 三个计时量全是"秒数"，只由 tick 里的时间增量喂。以前 active/since_last
        # 是"每秒 +1"、session_start 是时间戳，两套账在弹窗停留、系统睡眠、
        # 重启恢复时都会各走各的——看起来就像有两条时间线在并行。现在统一成一条。
        self.active = 0.0
        self.since_last = 0.0
        self.session_secs = 0.0
        self.next_due = int(self.cfg["first_minutes"]) * 60
        self._last_tick = time.monotonic()
        self.day_count = 0
        self._ticks = 0
        self.long_alerted = False
        self.pending = None
        self.off_screen_since = None
        self.usage = Usage()
        self._last_usage_save = 0
        self._usage_acc = 0.0
        self._cleanup_done = False

        self._restore_state()
        self.tray = Tray(self)
        self.pill = Pill(self)
        if int(self.cfg.get("pill_collapsed", 0)):
            # 上次是收着的，这次也收着——但别在窗口还没画好之前就收
            self.root.after(400, self.pill.collapse)
        self.root.after(1000, self.tick)
        self.root.after(1200, self._startup_chores)

    # ---------------- 收起形态

    def tray_ok(self):
        return bool(self.tray and self.tray.ok)

    def tray_show(self, tip=""):
        if self.tray and self.tray.ok:
            self.tray._add(tip or "质问我")
            self.tray._promote()

    def tray_hide(self):
        if self.tray:
            self.tray.remove()

    def tray_tip(self, text):
        if self.tray and self.tray.ok:
            self.tray.set_tip(text)

    def toggle_pill(self):
        self.pill.toggle()

    def apply_collapse_mode(self):
        """设置里换了收起形态之后立刻生效。

        只在**已经收着**的时候按新形态重收一次，好让人当场看见区别。
        如果小条是展开的，就不动它——万一托盘图标在某人机器上点不动，
        强行收起来等于把人关在门外。展开状态下改设置，下次收起来时用新形态。
        """
        if not self.pill.collapsed:
            return
        self.pill.expand()
        self.pill.collapse()

    def _startup_chores(self):
        try:
            cleanup_shots(self.cfg.get("shot_keep_days", 30))
            self._cleanup_done = True
        except Exception:
            log_error(traceback.format_exc())
        # 开机自启存的是绝对路径。exe 被挪了地方，注册表里那条就成死链，
        # 下次开机"什么也没发生"。只在旧路径确实不存在时才改——不然用户临时
        # 换个位置试一下，就把真正的那条覆盖了。
        try:
            cur = autostart_get()
            if cur and cur != autostart_target():
                old = _cmd_exe_path(cur)
                if old and not os.path.exists(old):
                    autostart_set(True)
        except Exception:
            log_error(traceback.format_exc())

    # ---------------- 状态存取

    def _restore_state(self):
        st = load_json(STATE_PATH, {})
        if st.get("day") == self.day:
            self.active = max(0.0, float(st.get("active", 0) or 0))
            self.since_last = max(0.0, float(st.get("since_last", 0) or 0))
            nd = float(st.get("next_due", 0) or 0)
            if nd > 0:
                self.next_due = nd
            # 「这一轮连续用了多久」只在真的没断过的时候才延续。
            # 关机一夜再开机还接着算，就会显示「这一轮 11 小时」——比归零更假。
            saved_at = float(st.get("saved_at", 0) or 0)
            if saved_at and time.time() - saved_at <= 120:
                self.session_secs = max(0.0, float(st.get("session_secs", 0) or 0))
            self.long_alerted = bool(st.get("long_alerted", False))
        recs = [r for r in read_log() if r.get("day") == self.day]
        self.day_count = len(recs)
        if recs:
            self.last_answer = recs[-1].get("answer", "")
            self.last_ts = float(recs[-1].get("ts", 0))

    def _save_state(self):
        save_json(STATE_PATH, {
            "day": self.day,
            "active": round(self.active, 1),
            "since_last": round(self.since_last, 1),
            "session_secs": round(self.session_secs, 1),
            "saved_at": time.time(),
            "next_due": self.next_due,
            "long_alerted": self.long_alerted,
        })

    def session_seconds(self):
        return int(max(0.0, self.session_secs))

    # ---------------- 主循环

    def tick(self):
        if self._closing:
            return
        try:
            self._tick_once()
        except Exception:
            log_error(traceback.format_exc())
        if self._closing:
            return
        try:
            if self.root.winfo_exists():
                self.root.after(1000, self.tick)
        except Exception:
            pass

    def _decide(self, now, locked=None):
        """返回 (in_use, 状态标签, 颜色)。locked 由调用方算好传进来——
        同一 tick 里查两次锁屏状态，中间正好解锁的话两次结果会打架。"""
        if not int(self.cfg.get("enabled", 1)):
            return False, "已关闭", FAINT
        if now < float(self.cfg.get("paused_until", 0) or 0):
            left = int(float(self.cfg["paused_until"]) - now)
            return False, f"已暂停 {max(0, left) // 60} 分", FAINT
        if in_dnd(self.cfg):
            return False, "免打扰时段", FAINT

        if locked is None:
            locked = is_locked()
        if not locked:
            self.off_screen_since = None
            if not int(self.cfg.get("idle_stop_enabled", 1)):
                return True, "", OK
            away = float(self.cfg.get("away_minutes", 10)) * 60
            if idle_seconds() < away or is_fullscreen():
                return True, "", OK
            return False, "你不在，已停表", FAINT

        # 屏幕黑着
        if not int(self.cfg.get("watch_off_screen", 0)):
            self.off_screen_since = None
            return False, "锁屏，已停表", FAINT
        if self.off_screen_since is None:
            self.off_screen_since = now
        grace = float(self.cfg.get("off_screen_grace_minutes", 10)) * 60
        if now - self.off_screen_since < grace:
            left = int((grace - (now - self.off_screen_since)) / 60) + 1
            return True, f"锁屏，还等 {left} 分", WARN
        return False, "锁屏太久，已停表", FAINT

    def _tick_once(self):
        now = time.time()
        # 时间增量取单调时钟：系统改时间、夏令时、对时都不该影响计时。
        # 上限 5 秒是为了吃掉两种异常——机器睡了一觉（after 停了几小时才回来），
        # 或者被挂起卡了很久。那段时间人不在，算进"在用"就是把账做假了。
        mono = time.monotonic()
        dt = mono - self._last_tick
        self._last_tick = mono
        if dt < 0 or dt > 5:
            dt = 0.0

        today = date.today().isoformat()
        if today != self.day:
            self.day = today
            self.active = 0.0
            self.since_last = 0.0
            self.session_secs = 0.0
            self.day_count = 0
            self.long_alerted = False
            self.next_due = int(self.cfg["first_minutes"]) * 60
            self.usage.roll()
            if self._cleanup_done:
                cleanup_shots(self.cfg.get("shot_keep_days", 30))

        was_locked = self._was_locked
        locked = is_locked()
        in_use, label, color = self._decide(now, locked)

        if in_use and self.popup is None:
            self.active += dt
            self.since_last += dt
            self.session_secs += dt
            # 按程序统计也走同一个增量：攒够 1 秒才记一笔，免得 0.98 秒被
            # 记成 1 秒、一秒一秒攒出几十分钟的假时长。
            self._usage_acc += dt
            if self._usage_acc >= 1.0:
                n = int(self._usage_acc)
                self._usage_acc -= n
                self._sample_usage(n)
        elif not in_use:
            self.session_secs = 0.0
            self._usage_acc = 0.0
            self.long_alerted = False

        if self.usage.dirty and now - self._last_usage_save >= 60:
            self.usage.save()
            self._last_usage_save = now

        # 到点
        if in_use and self.since_last >= self.next_due and self.popup is None:
            if locked:
                if self.cfg.get("alert_when_off_screen", 1) and self.cfg.get("sound", 1):
                    beep(self.cfg)
                self.pending = {"trigger_ts": now, "reason": "back"}
                self._roll_next()
            else:
                self.show_popup("normal")

        # 解锁后补弹
        if was_locked and not locked and self.pending and self.popup is None:
            p = self.pending
            self.pending = None
            if in_use:
                self.show_popup(p.get("reason", "back"))

        # 连续使用太久
        if (in_use and not self.long_alerted
                and int(self.cfg.get("long_session_alert", 0))
                and self.session_secs >= float(self.cfg.get("long_session_minutes", 180)) * 60
                and self.popup is None):
            self.long_alerted = True
            if locked:
                if self.cfg.get("alert_when_off_screen", 1) and self.cfg.get("sound", 1):
                    beep(self.cfg)
                self.pending = {"trigger_ts": now, "reason": "rest"}
            else:
                self.show_popup("rest")

        self._was_locked = locked
        self._ticks += 1
        if self._ticks % 30 == 0:
            self._save_state()

        # 有人双击了第二次 exe：把他叫回来，把悬浮条亮出来。
        if os.path.exists(WAKE_PATH):
            try:
                os.remove(WAKE_PATH)
            except OSError:
                pass
            self.pill.expand()
            self.pill.flash()

        self._refresh_pill(in_use, label, color)

    _was_locked = False

    def _refresh_pill(self, in_use, label, color):
        if self.popup is not None:
            self.pill.set_state("正在被问", WARN)
            return
        if not in_use:
            self.pill.set_state(label or "已停表", color)
            return
        used = self.session_seconds()
        tail = fmt_span(used) if used >= 60 else "刚开始"
        # 说「连续在用」而不是「这一轮」：后者容易被读成"距下次提问还剩多久"，
        # 而那个数字是故意不显示的（见 DEVELOPING.md §11）。
        self.pill.set_state(f"连续在用 {tail}", color)

    def _roll_next(self):
        lo = int(self.cfg["min_minutes"]) * 60
        hi = max(lo + 60, int(self.cfg["max_minutes"]) * 60)
        self.next_due = random.randint(lo, hi)
        self.since_last = 0.0

    def _sample_usage(self, sec=1):
        if not int(self.cfg.get("track_app", 1)):
            return
        try:
            app, title = foreground_app()
            if not app:
                return
            if not int(self.cfg.get("track_titles", 1)):
                title = ""
            self.usage.add(app, title[:120], max(1, int(sec)))
        except Exception:
            log_error(traceback.format_exc())

    # ---------------- 弹窗与记录

    def show_popup(self, reason="normal"):
        if self.popup is not None:
            return
        rid = uuid.uuid4().hex[:16]
        shot = ""
        if int(self.cfg.get("shot_enabled", 1)):
            try:
                p = shot_path_for(rid)
                if capture_jpeg(p, self.cfg.get("shot_width", 800),
                                self.cfg.get("shot_quality", 60)):
                    shot = os.path.relpath(p, DATA_DIR)
                    ensure_thumb(shot)
            except Exception:
                log_error(traceback.format_exc())
        self.popup = AskPopup(self, reason=reason, rec_id=rid, shot=shot)

    def record(self, text, kind, trigger_ts=None, rec_id=None, shot=None):
        self.last_answer = text
        self.last_ts = time.time()
        self.day_count += 1
        delay = int(max(0, self.last_ts - trigger_ts)) if trigger_ts else 0
        append_log({
            "id": rec_id or uuid.uuid4().hex[:16],
            "ts": self.last_ts,
            "time": datetime.now().strftime("%H:%M"),
            "day": date.today().isoformat(),
            "answer": text,
            "kind": kind,
            "active": int(self.active),
            "span": int(self.since_last),
            "n": self.day_count,
            "delay": delay,
            "shot": shot or "",
            "src": "pc",
        })
        self._roll_next()
        self._save_state()

    def pause(self, minutes):
        self.cfg["paused_until"] = time.time() + minutes * 60 if minutes > 0 else 0
        save_cfg(self.cfg)

    def pause_until_tomorrow(self):
        t = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=1)
        self.cfg["paused_until"] = t.timestamp()
        save_cfg(self.cfg)

    def set_enabled_false(self):
        self.cfg["enabled"] = 0
        save_cfg(self.cfg)

    def set_enabled_true(self):
        self.cfg["enabled"] = 1
        save_cfg(self.cfg)

    # ---------------- 面板

    def open_history(self):
        if self.history is not None and self.history.win.winfo_exists():
            self.history.win.lift()
            self.history.win.focus_force()
            self.history.reload()
            return
        self.history = HistoryWindow(self)

    def open_settings(self):
        if self.settings is not None and self.settings.win.winfo_exists():
            self.settings.win.lift()
            self.settings.win.focus_force()
            return
        self.settings = SettingsWindow(self)

    def open_usage(self):
        if self.usage_win is not None and self.usage_win.win.winfo_exists():
            self.usage_win.win.lift()
            self.usage_win.win.focus_force()
            self.usage_win.reload()
            return
        self.usage_win = UsageWindow(self)

    def apply_settings(self, new_cfg):
        old_due = self.next_due
        mode_changed = (int(self.cfg.get("collapse_tray", 1))
                        != int(new_cfg.get("collapse_tray", 1)))
        self.cfg.update(new_cfg)
        save_cfg(self.cfg)
        lo = int(self.cfg["min_minutes"]) * 60
        hi = max(lo + 60, int(self.cfg["max_minutes"]) * 60)
        if not (lo <= old_due <= hi):
            self.next_due = random.randint(lo, hi)
            self.since_last = 0
        if mode_changed:
            # 换了收起形态就当场收一次，不然用户改完设置看不到任何变化
            self.root.after(60, self.apply_collapse_mode)

    def quit(self):
        self._closing = True
        self._save_state()
        try:
            self.usage.save(force=True)
        except Exception:
            pass
        # 托盘图标不会随进程消失，得自己摘掉，否则任务栏上留一个点不动的僵尸图标
        try:
            self.tray.destroy()
        except Exception:
            pass
        try:
            self.root.destroy()
        except Exception:
            pass
        os._exit(0)


# ---------------------------------------------------------------- 历史窗口

class HistoryWindow:
    def __init__(self, app):
        self.app = app
        w = tk.Toplevel(app.root)
        self.win = w
        w.title(TITLE + " · 记录")
        w.configure(bg=SOFT)
        w.geometry("820x700")
        w.minsize(720, 560)
        w.protocol("WM_DELETE_WINDOW", self.close)
        self._heat_recs = []
        self._imgs = []
        self._img_count = 0

        head = tk.Frame(w, bg=BG)
        head.pack(fill="x")
        inner = tk.Frame(head, bg=BG)
        inner.pack(fill="x", padx=26, pady=(18, 14))
        tk.Label(inner, text="记录", bg=BG, fg=INK,
                 font=(FONT, 16, "bold"), anchor="w").pack(side="left")
        self.summary = tk.Label(inner, text="", bg=BG, fg=MUTED,
                                font=(FONT, 9), anchor="w")
        self.summary.pack(side="left", padx=(14, 0), pady=(4, 0))

        self.q = tk.StringVar()
        ent = tk.Entry(inner, textvariable=self.q, font=(FONT, 10), relief="flat",
                       bg=SOFT, fg=INK, width=22, highlightbackground=LINE,
                       highlightthickness=1)
        ent.pack(side="right", ipady=5)
        ent.bind("<KeyRelease>", lambda e: self.reload())
        tk.Label(inner, text="搜索", bg=BG, fg=FAINT,
                 font=(FONT, 9)).pack(side="right", padx=(0, 8))

        tk.Frame(w, bg=LINE, height=1).pack(fill="x")

        bar = tk.Frame(w, bg=SOFT)
        bar.pack(side="bottom", fill="x", padx=26, pady=(10, 18))
        button(bar, "导出 CSV", self.export_csv).pack(side="left")
        button(bar, "导出 JSON", self.export_json).pack(side="left", padx=(8, 0))
        button(bar, "行为统计", self.app.open_usage).pack(side="left", padx=(8, 0))
        button(bar, "设置", self.app.open_settings).pack(side="left", padx=(8, 0))
        button(bar, "关闭", self.close).pack(side="right")

        self.heat = tk.Canvas(w, bg=BG, height=104, highlightthickness=0, bd=0)
        self.heat.pack(side="bottom", fill="x", padx=26)
        tk.Label(w, text="一天里被问的时刻", bg=SOFT, fg=MUTED,
                 font=(FONT, 9), anchor="w").pack(side="bottom", fill="x",
                                                 padx=28, pady=(12, 4))

        body = tk.Frame(w, bg=BG)
        body.pack(fill="both", expand=True, padx=26, pady=(14, 0))
        body.configure(highlightbackground=LINE, highlightthickness=1)
        self.txt = tk.Text(body, bg=BG, fg=INK, font=(FONT, 10), relief="flat",
                           wrap="word", padx=16, pady=12, width=1,
                           insertbackground=BG, highlightthickness=0)
        sb = tk.Scrollbar(body, command=self.txt.yview)
        self.txt.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.txt.pack(side="left", fill="both", expand=True)

        self.txt.tag_configure("day", foreground=ACCENT_DARK, font=(FONT, 10, "bold"),
                               spacing1=10, spacing3=4)
        self.txt.tag_configure("time", foreground=ACCENT, font=(FONT, 9, "bold"))
        self.txt.tag_configure("body", foreground=INK, font=(FONT, 10),
                               lmargin1=0, lmargin2=54, spacing3=6)
        self.txt.tag_configure("dim", foreground=FAINT, font=(FONT, 8))
        self.txt.tag_configure("moyu", foreground=WARN, font=(FONT, 8, "bold"))
        self.txt.tag_configure("rest", foreground=OK, font=(FONT, 8, "bold"))
        self.txt.tag_configure("dup1", foreground=DUP1)
        self.txt.tag_configure("dup2", foreground=DUP2)
        self.txt.tag_configure("empty", foreground=FAINT, font=(FONT, 10))
        self.txt.config(state="disabled")
        self.heat.bind("<Configure>", lambda e: self._heat(self._heat_recs))

        self.reload()
        w.after(120, lambda: force_foreground(w))

    # -------- 数据

    def _all(self):
        return read_log()

    def reload(self):
        recs = self._all()
        kw = self.q.get().strip().lower()
        if kw:
            recs = [r for r in recs if kw in str(r.get("answer", "")).lower()]
        self._render(recs, bool(kw))
        self._summary()
        try:
            self.win.update_idletasks()
        except Exception:
            pass
        self._heat(self._all())

    def _summary(self):
        recs = self._all()
        today = date.today().isoformat()
        t = [r for r in recs if r.get("day") == today]
        week_ago = (date.today() - timedelta(days=6)).isoformat()
        wrecs = [r for r in recs if str(r.get("day", "")) >= week_ago]
        days = len({r.get("day") for r in wrecs}) or 1
        total = len(recs)
        self.summary.config(
            text=f"共 {total} 条 · 今天 {len(t)} 次 · 近 7 天日均 {len(wrecs) / days:.1f} 次")

    def _render(self, recs, filtered=False):
        self.txt.config(state="normal")
        self.txt.delete("1.0", "end")
        self._imgs = []
        self._img_count = 0

        if not recs:
            if filtered:
                self.txt.insert("end", "\n没有匹配的记录。\n", "empty")
            else:
                self.txt.insert("end", "\n还没有记录。\n\n", "empty")
                self.txt.insert("end", "开着电脑待着就行。它会在你干活的时候，挑一个你想不到的时刻出现。",
                                "empty")
            self.txt.config(state="disabled")
            return

        seen = {}
        warn_n = int(self.app.cfg.get("repeat_warn", 2) or 2)
        alert_n = int(self.app.cfg.get("repeat_alert", 3) or 3)
        cur_day = None
        for r in recs:
            day = str(r.get("day", ""))
            if day != cur_day:
                cur_day = day
                try:
                    d = datetime.strptime(day, "%Y-%m-%d").date()
                    label = d.strftime("%Y 年 %m 月 %d 日")
                    wd = "一二三四五六日"[d.weekday()]
                    label += f"　周{wd}"
                except Exception:
                    label = day
                self.txt.insert("end", label + "\n", "day")

            self.txt.insert("end", f"{r.get('time', '--:--')}   ", "time")
            if r.get("kind") == "moyu":
                self.txt.insert("end", "摸鱼  ", "moyu")
            elif r.get("kind") == "rest":
                self.txt.insert("end", "起身  ", "rest")

            ans = str(r.get("answer", "")).strip()
            tag = "body"
            if ans:
                seen[ans] = seen.get(ans, 0) + 1
                if seen[ans] >= alert_n:
                    tag = "body dup2"
                elif seen[ans] >= warn_n:
                    tag = "body dup1"
            self.txt.insert("end", ans + "\n", tag)
            self._maybe_image(r)

        self.txt.config(state="disabled")
        self.txt.see("1.0")

    def _maybe_image(self, r):
        shot = str(r.get("shot") or "")
        if not shot or self._img_count >= 150:
            return
        png = ensure_thumb(shot)
        if not png:
            return
        try:
            img = tk.PhotoImage(file=png)
        except Exception:
            return
        self._imgs.append(img)
        self._img_count += 1

        holder = tk.Frame(self.txt, bg="#D3DAE3", padx=1, pady=1, cursor="hand2")
        lbl = tk.Label(holder, image=img, bd=0, bg=BG)
        lbl.pack()

        def open_shot(_=None, s=shot):
            ShotViewer(self.app, s)

        holder.bind("<Button-1>", open_shot)
        lbl.bind("<Button-1>", open_shot)

        self.txt.insert("end", "   ")
        self.txt.window_create("end", window=holder, pady=4)
        self.txt.insert("end", "\n")

    def _heat(self, recs):
        self._heat_recs = recs
        self.heat.delete("all")
        try:
            w = max(400, self.heat.winfo_width())
        except Exception:
            w = 740
        counts = [0] * 24
        for r in recs:
            try:
                h = datetime.fromtimestamp(float(r.get("ts", 0))).hour
                counts[h] += 1
            except Exception:
                pass
        peak = max(counts) or 1
        pad_l, pad_r = 6, 6
        slot = (w - pad_l - pad_r) / 24.0
        bar_w = max(6, slot - 4)
        base_y = 76
        for i, c in enumerate(counts):
            x = pad_l + i * slot + 2
            h = 0 if c == 0 else max(3, int(round(c / peak * 52)))
            color = "#D6E4F2" if c == 0 else (ACCENT if c < peak else ACCENT_DARK)
            self.heat.create_rectangle(x, base_y - h, x + bar_w, base_y,
                                       fill=color, outline="")
            if c:
                self.heat.create_text(x + bar_w / 2, base_y - h - 8, text=str(c),
                                      fill=MUTED, font=(FONT, 7))
        self.heat.create_line(pad_l, base_y + 1, w - pad_r, base_y + 1, fill=LINE)
        for i in range(24):
            if i % 6 == 0:
                x = pad_l + i * slot + 2 + bar_w / 2
                self.heat.create_text(x, base_y + 16, text=f"{i}时",
                                      fill=FAINT, font=(FONT, 7))

    # -------- 导出

    def export_csv(self):
        import csv
        path = os.path.join(DATA_DIR, f"记录-{date.today().isoformat()}.csv")
        recs = self._all()
        try:
            with open(path, "w", encoding="utf-8-sig", newline="") as f:
                wr = csv.writer(f)
                wr.writerow(["日期", "时间", "回答", "类型", "响应延迟秒", "来源"])
                for r in recs:
                    wr.writerow([r.get("day", ""), r.get("time", ""),
                                 r.get("answer", ""), r.get("kind", ""),
                                 r.get("delay", ""), r.get("src", "pc")])
        except Exception:
            log_error(traceback.format_exc())
            messagebox_info(self.win, "导出失败", "写文件出错，详见 error.log")
            return
        messagebox_info(self.win, "导出好了", f"{len(recs)} 条记录已存到:\n{path}")

    def export_json(self):
        path = os.path.join(DATA_DIR, f"记录-{date.today().isoformat()}.json")
        recs = self._all()
        save_json(path, recs)
        messagebox_info(self.win, "导出好了", f"{len(recs)} 条记录已存到:\n{path}")

    def close(self):
        try:
            self.win.destroy()
        except Exception:
            pass
        self.app.history = None


# ---------------------------------------------------------------- 看截图

class ShotViewer:
    def __init__(self, app, shot_rel):
        self.app = app
        self.shot = shot_rel
        self.src = os.path.join(DATA_DIR, shot_rel)
        self.tmp = os.path.join(tempfile.gettempdir(),
                                "grill-view-%s.png" % uuid.uuid4().hex[:8])

        w = tk.Toplevel(app.root)
        self.win = w
        w.title(TITLE + " · 截图")
        w.configure(bg=SOFT)
        w.protocol("WM_DELETE_WINDOW", self.close)
        self.photo = None

        if os.path.exists(self.src) and image_to_png(self.src, self.tmp, 1200):
            try:
                self.photo = tk.PhotoImage(file=self.tmp)
            except Exception:
                self.photo = None

        if self.photo:
            pw, ph = self.photo.width(), self.photo.height()
            w.geometry("%dx%d" % (min(1260, pw + 48), min(920, ph + 110)))
            tk.Label(w, image=self.photo, bg=SOFT, bd=0).pack(
                padx=24, pady=(20, 10))
        else:
            tk.Label(w, text="这张图找不到了，可能已经被清理。",
                     bg=SOFT, fg=MUTED, font=(FONT, 10)).pack(padx=40, pady=(50, 10))

        bar = tk.Frame(w, bg=SOFT)
        bar.pack(fill="x", padx=24, pady=(0, 16))
        button(bar, "打开原文件", self.open_file).pack(side="left")
        button(bar, "删除这张", self.delete).pack(side="left", padx=(8, 0))
        button(bar, "关闭", self.close).pack(side="right")

        w.after(120, lambda: force_foreground(w))

    def open_file(self):
        try:
            os.startfile(self.src)
        except Exception:
            messagebox_info(self.win, "打不开", self.src)

    def delete(self):
        from tkinter import messagebox
        if not messagebox.askyesno("删除截图", "删掉这张图？记录本身会保留。",
                                   parent=self.win):
            return
        for p in (self.src, thumb_path_for(self.shot), self.tmp):
            try:
                if p and os.path.exists(p):
                    os.remove(p)
            except Exception:
                log_error(traceback.format_exc())
        if self.app.history is not None and self.app.history.win.winfo_exists():
            self.app.history.reload()
        self.close()

    def close(self):
        try:
            self.win.destroy()
        except Exception:
            pass
        try:
            if os.path.exists(self.tmp):
                os.remove(self.tmp)
        except Exception:
            pass


# ---------------------------------------------------------------- 时间分布

class UsageWindow:
    MODES = [("today", "今天", 1, 0), ("yesterday", "昨天", 1, 1),
             ("week", "近 7 天", 7, 0)]
    BAR_W = 640

    def __init__(self, app):
        self.app = app
        self.mode = "today"
        self.view = "app"
        w = tk.Toplevel(app.root)
        self.win = w
        w.title(TITLE + " · 行为统计")
        w.configure(bg=SOFT)
        w.geometry("760x700")
        w.minsize(700, 520)
        w.protocol("WM_DELETE_WINDOW", self.close)

        head = tk.Frame(w, bg=BG)
        head.pack(fill="x")
        inner = tk.Frame(head, bg=BG)
        inner.pack(fill="x", padx=26, pady=(18, 10))
        tk.Label(inner, text="行为统计", bg=BG, fg=INK,
                 font=(FONT, 16, "bold"), anchor="w").pack(side="left")
        self.summary = tk.Label(inner, text="", bg=BG, fg=MUTED,
                                font=(FONT, 9), anchor="w")
        self.summary.pack(side="left", padx=(14, 0), pady=(4, 0))

        seg = tk.Frame(head, bg=BG)
        seg.pack(fill="x", padx=26, pady=(0, 12))

        self.view_btns = {}
        for key, label in (("app", "按程序"), ("text", "按回答")):
            b = tk.Button(seg, text=label, command=lambda k=key: self.set_view(k),
                          font=(FONT, 9), relief="flat", bd=0, cursor="hand2",
                          padx=14, pady=5)
            b.pack(side="left", padx=(0, 6))
            self.view_btns[key] = b

        self.seg_btns = {}
        for key, label, _, _ in reversed(self.MODES):
            b = tk.Button(seg, text=label, command=lambda k=key: self.set_mode(k),
                          font=(FONT, 9), relief="flat", bd=0, cursor="hand2",
                          padx=14, pady=5)
            b.pack(side="right", padx=(6, 0))
            self.seg_btns[key] = b

        tk.Frame(w, bg=LINE, height=1).pack(fill="x")

        foot = tk.Frame(w, bg=SOFT)
        foot.pack(side="bottom", fill="x", padx=26, pady=(8, 16))
        self.foot_lbl = tk.Label(
            foot, text="只统计屏幕亮着、而且你在电脑前的时间。锁屏和离开都不算。",
            bg=SOFT, fg=FAINT, font=(FONT, 8), anchor="w")
        self.foot_lbl.pack(side="left")
        button(foot, "关闭", self.close).pack(side="right")

        self.sf = ScrollFrame(w, bg=SOFT)
        self.sf.pack(fill="both", expand=True, padx=26, pady=(12, 0))

        self.reload()
        w.after(120, lambda: force_foreground(w))

    def set_mode(self, key):
        self.mode = key
        self.reload()

    def set_view(self, key):
        self.view = key
        self.reload()

    def reload(self):
        try:
            self.app.usage.save(force=True)
        except Exception:
            pass
        for key, label, days, off in self.MODES:
            active = key == self.mode
            self.seg_btns[key].config(
                bg=ACCENT if active else SOFT,
                fg=BG if active else MUTED,
                activebackground=ACCENT_DARK if active else LINE,
                activeforeground=BG if active else INK)
        for key in ("app", "text"):
            active = key == self.view
            self.view_btns[key].config(
                bg=ACCENT if active else SOFT,
                fg=BG if active else MUTED,
                activebackground=ACCENT_DARK if active else LINE,
                activeforeground=BG if active else INK)

        days, off = 1, 0
        for key, _, d, o in self.MODES:
            if key == self.mode:
                days, off = d, o
        if self.view == "app":
            self.foot_lbl.config(text="只统计屏幕亮着、而且你在电脑前的时间。锁屏和离开都不算。")
            self._render_app(load_usage(days, off))
        else:
            self.foot_lbl.config(text="按你当时的回答归类，时长是那一轮真正在用的时间。")
            self._render_text(log_in_range(days, off))
        try:
            self.win.update_idletasks()
        except Exception:
            pass

    def _render_app(self, data):
        for child in self.sf.inner.winfo_children():
            child.destroy()

        total = sum(int(r.get("sec", 0)) for r in data.values())
        if total <= 0:
            tk.Label(self.sf.inner, text="\n这段时间还没有记录。\n\n"
                     "程序在后台每秒看一眼你正在用的程序，攒够一分钟就落一次盘。",
                     bg=SOFT, fg=FAINT, font=(FONT, 10), justify="left").pack(
                anchor="w", padx=4, pady=(30, 0))
            self.summary.config(text="没有数据")
            return

        self.summary.config(text=f"合计 {fmt_span(total)} · {len(data)} 个程序")

        items = sorted(data.items(), key=lambda kv: -int(kv[1].get("sec", 0)))
        rows = items[:14]
        rest = items[14:]
        if rest:
            sec = sum(int(r.get("sec", 0)) for _, r in rest)
            titles = {}
            for _, r in rest:
                for k, v in (r.get("titles") or {}).items():
                    titles[k] = titles.get(k, 0) + int(v)
            rows = rows + [("其他 %d 个程序" % len(rest), {"sec": sec, "titles": titles})]

        for app, rec in rows:
            sec = int(rec.get("sec", 0))
            pct = sec * 100.0 / total
            row = tk.Frame(self.sf.inner, bg=SOFT)
            row.pack(fill="x", pady=(0, 14))

            line = tk.Frame(row, bg=SOFT)
            line.pack(fill="x")
            tk.Label(line, text=app, bg=SOFT, fg=INK, font=(FONT, 10),
                     anchor="w").pack(side="left")
            tk.Label(line, text=f"{fmt_span(sec)}　{pct:.0f}%", bg=SOFT, fg=MUTED,
                     font=(FONT, 9), anchor="e").pack(side="right")

            bar = tk.Canvas(row, bg="#EDF0F4", height=6, highlightthickness=0, bd=0)
            bar.pack(fill="x", pady=(5, 0))

            def draw(event, pct=pct, bar=bar):
                bar.delete("all")
                w = max(1, int(event.width))
                bar.create_rectangle(0, 0, max(2, int(w * pct / 100.0)), 6,
                                     fill=ACCENT if pct >= 10 else ACCENT_PALE,
                                     outline="")

            bar.bind("<Configure>", draw)

            titles = rec.get("titles") or {}
            if titles:
                top = max(titles.items(), key=lambda kv: int(kv[1]))[0]
                if top and top != app:
                    tk.Label(row, text=self._clip(top, 70), bg=SOFT, fg=FAINT,
                             font=(FONT, 8), anchor="w").pack(fill="x", pady=(3, 0))

    def _render_text(self, recs):
        for child in self.sf.inner.winfo_children():
            child.destroy()

        groups = {}
        prev_ts = 0.0
        total_chars = 0
        for r in recs:
            ts = float(r.get("ts") or 0)
            span = int(r.get("span") or 0)
            if span <= 0 and prev_ts > 0 and ts > prev_ts:
                span = int(ts - prev_ts)
            if ts:
                prev_ts = ts
            ans = str(r.get("answer") or "").strip()
            if not ans:
                continue
            g = groups.setdefault(ans, {"n": 0, "sec": 0, "moyu": 0})
            g["n"] += 1
            g["sec"] += max(0, span)
            total_chars += len(ans)
            if r.get("kind") == "moyu":
                g["moyu"] += 1

        if not groups:
            tk.Label(self.sf.inner, text="\n这段时间还没有回答。\n\n"
                     "被问的时候写了什么，这里就按那句话归类。",
                     bg=SOFT, fg=FAINT, font=(FONT, 10), justify="left").pack(
                anchor="w", padx=4, pady=(30, 0))
            self.summary.config(text="没有数据")
            return

        total_n = sum(g["n"] for g in groups.values())
        total_sec = sum(g["sec"] for g in groups.values())
        avg_len = int(total_chars / total_n) if total_n else 0
        self.summary.config(
            text="%d 次 · %d 种说法 · 平均 %d 字" % (total_n, len(groups), avg_len))

        if total_sec <= 0:
            tk.Label(self.sf.inner, text="这些记录还没带时长，只有次数。",
                     bg=SOFT, fg=FAINT, font=(FONT, 8)).pack(anchor="w", pady=(0, 10))

        items = sorted(groups.items(), key=lambda kv: (-kv[1]["sec"], -kv[1]["n"]))
        rows = items[:15]
        rest = items[15:]
        if rest:
            rows = rows + [("其他 %d 种说法" % len(rest), {
                "n": sum(g["n"] for _, g in rest),
                "sec": sum(g["sec"] for _, g in rest), "moyu": 0})]

        basis = total_sec if total_sec > 0 else total_n
        for ans, g in rows:
            pct = (g["sec"] if total_sec > 0 else g["n"]) * 100.0 / (basis or 1)
            row = tk.Frame(self.sf.inner, bg=SOFT)
            row.pack(fill="x", pady=(0, 14))

            line = tk.Frame(row, bg=SOFT)
            line.pack(fill="x")
            tk.Label(line, text=self._clip(ans, 26), bg=SOFT, fg=INK,
                     font=(FONT, 10), anchor="w").pack(side="left")
            right = "%d 次" % g["n"]
            if g["sec"] > 0:
                right = fmt_span(g["sec"]) + "　" + right
            tk.Label(line, text=right, bg=SOFT, fg=MUTED, font=(FONT, 9),
                     anchor="e").pack(side="right")

            bar = tk.Canvas(row, bg="#EDF0F4", height=6, highlightthickness=0, bd=0)
            bar.pack(fill="x", pady=(5, 0))

            def draw(event, pct=pct, bar=bar):
                bar.delete("all")
                w = max(1, int(event.width))
                bar.create_rectangle(0, 0, max(2, int(w * pct / 100.0)), 6,
                                     fill=ACCENT if pct >= 10 else ACCENT_PALE,
                                     outline="")

            bar.bind("<Configure>", draw)

            notes = []
            if g["n"] >= 2:
                notes.append("这句话你说了 %d 次" % g["n"])
            if g["moyu"]:
                notes.append("其中 %d 次承认在摸鱼" % g["moyu"])
            if notes:
                tk.Label(row, text="　·　".join(notes), bg=SOFT,
                         fg=DUP1 if g["n"] >= 3 else FAINT,
                         font=(FONT, 8), anchor="w").pack(fill="x", pady=(3, 0))

    @staticmethod
    def _clip(s, n):
        s = str(s or "").strip()
        return s if len(s) <= n else s[:n] + "…"

    def close(self):
        try:
            self.win.destroy()
        except Exception:
            pass
        self.app.usage_win = None


# ---------------------------------------------------------------- 设置窗口

class SettingsWindow:
    def __init__(self, app):
        self.app = app
        w = tk.Toplevel(app.root)
        self.win = w
        w.title(TITLE + " · 设置")
        w.configure(bg=BG)
        w.geometry("560x680")
        w.minsize(520, 520)
        w.protocol("WM_DELETE_WINDOW", self.close)

        head = tk.Frame(w, bg=BG)
        head.pack(fill="x", padx=26, pady=(18, 0))
        tk.Label(head, text="设置", bg=BG, fg=INK,
                 font=(FONT, 15, "bold"), anchor="w").pack(anchor="w")
        tk.Label(head, text="改完立刻生效。数值项直接改数字，开关点一下。",
                 bg=BG, fg=MUTED, font=(FONT, 9), anchor="w").pack(anchor="w", pady=(3, 12))
        tk.Frame(w, bg=LINE, height=1).pack(fill="x")

        bar = tk.Frame(w, bg=BG)
        bar.pack(side="bottom", fill="x", padx=26, pady=(10, 16))
        button(bar, "保存", self.save, kind="primary").pack(side="left")
        button(bar, "取消", self.close).pack(side="left", padx=(8, 0))
        button(bar, "恢复默认", self.reset).pack(side="right")
        tk.Frame(w, bg=LINE, height=1).pack(side="bottom", fill="x")

        self.sf = ScrollFrame(w, bg=BG)
        self.sf.pack(fill="both", expand=True)
        self.vars = {}
        self.sysvars = {}
        self._build(self.sf.inner)
        w.after(120, lambda: force_foreground(w))

    # -------- 构建

    def _section(self, parent, title, desc=""):
        f = tk.Frame(parent, bg=BG)
        f.pack(fill="x", padx=26, pady=(16, 2))
        tk.Label(f, text=title, bg=BG, fg=ACCENT_DARK,
                 font=(FONT, 10, "bold"), anchor="w").pack(fill="x")
        if desc:
            tk.Label(f, text=desc, bg=BG, fg=FAINT, font=(FONT, 8),
                     anchor="w", justify="left").pack(fill="x", pady=(2, 0))
        return f

    def _row(self, parent, label, desc=""):
        row = tk.Frame(parent, bg=BG)
        row.pack(fill="x", padx=26, pady=(9, 0))
        left = tk.Frame(row, bg=BG)
        left.pack(side="left", fill="x", expand=True)
        tk.Label(left, text=label, bg=BG, fg=INK, font=(FONT, 10),
                 anchor="w").pack(fill="x")
        if desc:
            tk.Label(left, text=desc, bg=BG, fg=FAINT, font=(FONT, 8),
                     anchor="w", justify="left", wraplength=380).pack(fill="x", pady=(2, 0))
        return row

    def _bool(self, parent, key, label, desc=""):
        row = self._row(parent, label, desc)
        var = tk.IntVar(value=int(self.app.cfg.get(key, DEFAULTS[key])))
        self.vars[key] = ("bool", var)
        Switch(row, var, bg=BG).pack(side="right", padx=(12, 0), pady=(2, 0))

    def _sys_bool(self, parent, label, desc, getter, setter):
        """开关的状态不在 config.json 里，而在别处（比如注册表）。
        点「保存」才落盘，跟其它项一致——点了取消就什么都不改。"""
        row = self._row(parent, label, desc)
        var = tk.IntVar(value=1 if getter() else 0)
        self.sysvars[label] = (var, setter)
        Switch(row, var, bg=BG).pack(side="right", padx=(12, 0), pady=(2, 0))

    def _num(self, parent, key, label, desc="", width=8, suffix="", is_float=False):
        row = self._row(parent, label, desc)
        var = tk.StringVar(value=str(self.app.cfg.get(key, DEFAULTS[key])))
        self.vars[key] = ("float" if is_float else "num", var)
        holder = tk.Frame(row, bg=BG)
        holder.pack(side="right", padx=(12, 0))
        tk.Entry(holder, textvariable=var, font=(FONT, 10), relief="flat",
                 bg=SOFT, fg=INK, width=width, justify="right",
                 highlightbackground=LINE, highlightthickness=1).pack(side="left", ipady=4)
        if suffix:
            tk.Label(holder, text=suffix, bg=BG, fg=FAINT,
                     font=(FONT, 9)).pack(side="left", padx=(5, 0))

    def _time(self, parent, key, label, desc=""):
        row = self._row(parent, label, desc)
        var = tk.StringVar(value=str(self.app.cfg.get(key, DEFAULTS[key])))
        self.vars[key] = ("time", var)
        tk.Entry(row, textvariable=var, font=(FONT, 10), relief="flat",
                 bg=SOFT, fg=INK, width=7, justify="center",
                 highlightbackground=LINE, highlightthickness=1).pack(
            side="right", padx=(12, 0), ipady=4)

    def _text(self, parent, key, label, desc="", button_cmd=None, button_text="",
              secret=False, width=20):
        row = self._row(parent, label, desc)
        var = tk.StringVar(value=str(self.app.cfg.get(key, DEFAULTS[key])))
        self.vars[key] = ("text", var)
        holder = tk.Frame(row, bg=BG)
        holder.pack(side="right", padx=(12, 0))
        if button_cmd:
            tk.Button(holder, text=button_text, command=button_cmd, bg=BG, fg=ACCENT,
                      activebackground=SOFT, font=(FONT, 8), relief="flat", bd=0,
                      cursor="hand2", padx=6).pack(side="right")
        tk.Entry(holder, textvariable=var, font=(FONT, 9), relief="flat",
                 bg=SOFT, fg=INK, width=width, justify="right",
                 show="\u2022" if secret else "",
                 highlightbackground=LINE, highlightthickness=1).pack(
            side="right", ipady=4, padx=(0, 4))

    def _build(self, p):
        cfg = self.app.cfg

        self._section(p, "总开关", "关掉之后完全不计时、不提醒。想临时歇一会儿用悬浮条右键的「暂停」。")
        self._bool(p, "enabled", "开启提醒", "总开关。等于「是否弹窗」——弹窗是这个工具唯一的入口。")

        self._section(p, "启动与常驻",
                      "程序没有主窗口，界面上只有右下角那条。收起来之后它照样计时。")
        self._bool(p, "collapse_tray", "收起时用系统托盘图标",
                   "开：收进任务栏托盘，桌面上什么都不留。左键点一下图标把它叫回来，"
                   "右键出菜单。Win11 会把新图标先塞进「^」里，程序会自动把它提到"
                   "任务栏；万一没提上来，去「设置 → 个性化 → 任务栏 → 其他系统"
                   "托盘图标」里打开一次就行。"
                   "关：缩成右下角一个小方块，永远看得见。")
        self._sys_bool(p, "开机自启动",
                       "登录 Windows 后自己开始计时。否则每天开机都得记得手动点一下。",
                       lambda: bool(autostart_get()), autostart_set)

        self._section(p, "节奏", "活跃时间每满一段，随机挑一个时刻问你。不显示倒计时。")
        self._num(p, "min_minutes", "最短间隔", "两次提问之间，活跃时间至少隔多久。", suffix="分钟")
        self._num(p, "max_minutes", "最长间隔", "再久也不会超过这个数。", suffix="分钟")
        self._num(p, "first_minutes", "启动后首次", "打开程序后，最早多久可能被问。", suffix="分钟")

        self._section(p, "判定什么算「在用」",
                      "屏幕亮着就算在用——发呆、看视频、读长文都不算离开。")
        self._bool(p, "idle_stop_enabled", "长时间无输入就停表",
                   "关掉后只要没锁屏就一直计时，适合挂着看长视频。")
        self._num(p, "away_minutes", "多久无输入算离开", "仅在上面开关打开时生效。全屏播放时不会停表。",
                  suffix="分钟")

        self._section(p, "锁屏模式",
                      "打开后，连「没碰电脑也没碰手机、人还在浪费时间」一起管：锁屏后仍计时，到点响铃，回来补弹。")
        self._bool(p, "watch_off_screen", "锁屏后仍然计时和提醒",
                   "关掉时只管屏幕亮着的部分。")
        self._num(p, "off_screen_grace_minutes", "锁屏宽限",
                  "超过这个时间就算人真走了，停表。避免放一整夜累积假时长。", suffix="分钟")
        self._bool(p, "alert_when_off_screen", "锁屏时到点响铃",
                   "屏幕黑着看不到弹窗，靠声音叫你回来。")

        self._section(p, "提交门槛", "防止肌肉记忆糊过去。")
        self._num(p, "min_chars", "至少写几个字", "少于这个字数提交不了。", suffix="字")
        self._bool(p, "allow_repeat", "允许和上一条完全相同",
                   "不检查重复。但要配合下面的禁止粘贴，否则等于允许复制。")
        self._bool(p, "block_paste", "禁止粘贴",
                   "防止把上次的答案复制过来。手打才作数。")
        self._num(p, "cooldown_seconds", "冷静期", "弹出后多少秒才允许提交，可以填 0.5 这种小数。0 = 不等待。",
                  suffix="秒", is_float=True)
        self._bool(p, "show_moyu", "显示「我承认在摸鱼」",
                   "关掉之后只剩「说清楚了，继续」一条路。想彻底不给自己留台阶就关掉它。")

        self._section(p, "提醒方式", "电脑端没有震动，只有声音。")
        self._bool(p, "sound", "响铃", "")
        self._text(p, "sound_uri", "铃声", "填 beep 用系统提示音，或选一个 wav 文件。",
                   button_cmd=self._pick_wav, button_text="选文件")
        self._bool(p, "dnd_enabled", "免打扰时段", "这段时间内完全暂停，不弹不问。")
        self._time(p, "dnd_start", "开始", "24 小时制，例如 22:00")
        self._time(p, "dnd_end", "结束", "跨零点自动处理，例如 08:00")

        self._section(p, "附加", "")
        self._bool(p, "long_session_alert", "连续使用太久补问一次",
                   "在一轮连续使用里，超过阈值额外插一次提问。")
        self._num(p, "long_session_minutes", "阈值", "连续用多久补问。", suffix="分钟")
        self._num(p, "repeat_warn", "重复几次标黄", "同一句话出现第几次开始变黄。", suffix="次")
        self._num(p, "repeat_alert", "重复几次标红", "同一句话出现第几次开始变红。", suffix="次")

        self._section(p, "统计与截图",
                      "每秒看一眼你正在用的程序，攒成每天的时间分布。截图只在弹窗前拍一张，存在本地。")
        self._bool(p, "track_app", "记录每个程序用了多久",
                   "关掉就不统计时间分布，其余功能不受影响。")
        self._bool(p, "track_titles", "同时记录窗口标题",
                   "能看出「在 Chrome 里干什么」。标题可能带文档名或网址，介意就关掉。")
        self._bool(p, "shot_enabled", "弹窗前自动截图",
                   "只在弹窗前拍一张，压缩后存本地，用来事后核对当时在干什么。")
        self._num(p, "shot_width", "截图宽度", "越小越省空间。", suffix="像素")
        self._num(p, "shot_quality", "JPEG 质量", "20-95。60 大约是原图的十分之一。", width=6)
        self._num(p, "shot_keep_days", "截图保留天数", "超过就自动删除。0 = 永久保留。", suffix="天")
        shotrow = tk.Frame(p, bg=BG)
        shotrow.pack(fill="x", padx=26, pady=(10, 0))
        button(shotrow, "打开截图文件夹", self._open_shots).pack(side="left")
        self.shot_lbl = tk.Label(shotrow, text="", bg=BG, fg=FAINT,
                                 font=(FONT, 8), anchor="w")
        self.shot_lbl.pack(side="left", padx=(10, 0))

        self._section(p, "同步（WebDAV）",
                      "任何支持 WebDAV 的服务都能用，不限于某一家。只同步记录，不同步设置和计时状态。")
        self._bool(p, "sync_enabled", "开启同步", "")
        self._text(p, "sync_url", "服务器地址", "例如 https://dav.example.com/dav/AskMe/", width=27)
        self._text(p, "sync_user", "账号", "一般是注册邮箱。")
        self._text(p, "sync_pass", "密码", "要填「应用密码」，不是登录密码。", secret=True)
        self._text(p, "sync_device_id", "设备名", "两台设备必须不一样，比如 pc / android。")
        syncrow = tk.Frame(p, bg=BG)
        syncrow.pack(fill="x", padx=26, pady=(10, 0))
        button(syncrow, "测试连接", self._test_webdav).pack(side="left")
        self.test_lbl = tk.Label(syncrow, text="", bg=BG, fg=MUTED, font=(FONT, 8),
                                 anchor="w", justify="left", wraplength=330)
        self.test_lbl.pack(side="left", padx=(10, 0))

        tk.Frame(p, bg=BG, height=14).pack()

    def _open_shots(self):
        try:
            os.makedirs(SHOTS_DIR, exist_ok=True)
            os.startfile(SHOTS_DIR)
        except Exception:
            messagebox_info(self.win, "打不开", SHOTS_DIR)

    def _test_webdav(self):
        cfg = dict(self.app.cfg)
        for k in ("sync_url", "sync_user", "sync_pass", "sync_device_id"):
            if k in self.vars:
                cfg[k] = str(self.vars[k][1].get()).strip()
        self.test_lbl.config(text="正在连接…", fg=MUTED)

        def work():
            ok, msg = webdav_test(cfg)
            try:
                self.win.after(0, lambda: self.test_lbl.config(
                    text=msg, fg=OK if ok else DANGER))
            except Exception:
                pass

        threading.Thread(target=work, daemon=True).start()

    def _pick_wav(self):
        from tkinter import filedialog
        path = filedialog.askopenfilename(
            parent=self.win, title="选一个 wav 铃声",
            filetypes=[("Wave 音频", "*.wav"), ("所有文件", "*.*")])
        if path:
            kind, var = self.vars["sound_uri"]
            var.set(path)

    # -------- 保存

    def _collect(self):
        out = {}
        for key, (kind, var) in self.vars.items():
            if kind == "bool":
                out[key] = int(var.get())
            elif kind == "num":
                try:
                    out[key] = int(str(var.get()).strip())
                except Exception:
                    raise ValueError(key)
            elif kind == "float":
                try:
                    out[key] = round(float(str(var.get()).strip().replace("，", ".")), 2)
                except Exception:
                    raise ValueError(key)
            elif kind == "time":
                s = str(var.get()).strip()
                h, m = parse_hhmm(s, (-1, -1))
                if h < 0:
                    raise ValueError(key)
                out[key] = f"{h:02d}:{m:02d}"
            else:
                out[key] = str(var.get()).strip()
        return out

    LABELS = {
        "min_minutes": "最短间隔", "max_minutes": "最长间隔", "first_minutes": "启动后首次",
        "away_minutes": "多久无输入算离开", "off_screen_grace_minutes": "锁屏宽限",
        "min_chars": "至少写几个字", "cooldown_seconds": "冷静期",
        "long_session_minutes": "连续使用阈值", "repeat_warn": "重复几次标黄",
        "repeat_alert": "重复几次标红", "dnd_start": "免打扰开始", "dnd_end": "免打扰结束",
        "shot_width": "截图宽度", "shot_quality": "JPEG 质量", "shot_keep_days": "截图保留天数",
    }

    # 每项「程序真的能处理」的边界，和「推荐」分开。
    # 推荐只是提示，不是围墙：填 999 就存 999，只提醒一句超了。
    # 绝对边界只有一个作用——别让值大到把程序弄崩（JPEG 编码器不认 101，
    # 20000 像素宽的 Bitmap 直接分配失败）。夹断了必须说清楚改成了多少，
    # 不能像以前那样悄悄改掉，用户还以为自己填错了。
    LIMITS = {
        # key: (绝对下限, 绝对上限, 推荐下限, 推荐上限, 名字)
        "shot_width": (1, 10000, 160, 3840, "截图宽度"),
        "shot_quality": (1, 100, 20, 95, "JPEG 质量"),
        "cooldown_seconds": (0, 3600, 0, 60, "冷静期"),
    }

    def save(self):
        try:
            new = self._collect()
        except ValueError as e:
            key = str(e)
            messagebox_info(self.win, "不对", f"「{self.LABELS.get(key, key)}」得填数字。")
            return

        notes = []
        for key, (alo, ahi, rlo, rhi, name) in self.LIMITS.items():
            v = new[key]
            if v < alo:
                new[key] = alo
            elif v > ahi:
                new[key] = ahi
            if new[key] != v:
                notes.append(f"「{name}」只能是 {alo} – {ahi}，已改成 {new[key]}。")
            elif not (rlo <= new[key] <= rhi):
                notes.append(f"「{name}」现在是 {new[key]}，超出推荐 {rlo} – {rhi}。")

        errs = []
        if new["min_minutes"] < 1 or new["max_minutes"] < 1:
            errs.append("间隔得大于 0。")
        if new["max_minutes"] <= new["min_minutes"]:
            errs.append("最长间隔得比最短间隔大。")
        if new["min_chars"] < 1:
            errs.append("最少字数至少是 1。")
        if new["away_minutes"] < 1:
            errs.append("无输入判定至少 1 分钟。")
        if new["off_screen_grace_minutes"] < 0:
            errs.append("锁屏宽限不能是负数。")
        if new["repeat_alert"] <= new["repeat_warn"]:
            errs.append("标红的次数得比标黄大。")
        if new["long_session_minutes"] < 1:
            errs.append("连续使用阈值至少 1 分钟。")
        if new["shot_keep_days"] < 0:
            errs.append("截图保留天数不能是负数。")
        if new["sync_enabled"] and not new["sync_url"].strip():
            errs.append("开了同步就得填服务器地址。")
        if new["sync_enabled"] and not new["sync_device_id"].strip():
            errs.append("开了同步就得给这台设备起个名字。")
        if errs:
            messagebox_info(self.win, "不对", "\n".join(errs))
            return

        self.app.apply_settings(new)
        for label, (var, setter) in self.sysvars.items():
            try:
                setter(bool(var.get()))
            except Exception:
                log_error(traceback.format_exc())
        if notes:
            messagebox_info(self.win, "已保存", "\n".join(notes))
        self.close()

    def reset(self):
        from tkinter import messagebox
        if not messagebox.askyesno("恢复默认", "把所有设置恢复成默认值？记录不受影响。",
                                   parent=self.win):
            return
        for key, (kind, var) in self.vars.items():
            if kind == "bool":
                var.set(int(DEFAULTS[key]))
            else:
                var.set(str(DEFAULTS[key]))
        for label, (var, _setter) in self.sysvars.items():
            var.set(0)

    def close(self):
        try:
            self.win.destroy()
        except Exception:
            pass
        self.app.settings = None


def messagebox_info(parent, title, text):
    from tkinter import messagebox
    messagebox.showinfo(title, text, parent=parent)


# ---------------------------------------------------------------- 入口

def selftest():
    # 中文 Windows 的 cmd 默认是 GBK 代码页，而窗口标题里可能带零宽空格
    # （U+200B）这类 GBK 编不出来的字符，一 print 就抛 UnicodeEncodeError，
    # 自检跑到一半直接崩。宁可把这种字符显示成问号，也不能让自检挂掉。
    try:
        sys.stdout.reconfigure(errors="replace")
    except Exception:
        pass

    ensure_dirs()
    print(f"数据目录        {DATA_DIR}")
    print(f"配置            {load_cfg()}")
    print(f"锁屏状态        {'已锁' if is_locked() else '未锁'}")
    print(f"空闲秒数        {idle_seconds():.1f}")
    print(f"前台全屏        {is_fullscreen()}")
    print(f"免打扰(现在)    {in_dnd(load_cfg())}")
    app_name, app_title = foreground_app()
    print(f"前台程序        {app_name}  |  {app_title[:40]}")

    probe = os.path.join(SHOTS_DIR, "_selftest.jpg")
    ok = capture_jpeg(probe, 480, 55)
    size = os.path.getsize(probe) if ok and os.path.exists(probe) else 0
    print(f"截图 JPEG       {'OK %d 字节' % size if ok else 'FAIL'}")
    if os.path.exists(probe):
        os.remove(probe)

    u = Usage()
    u.add("__selftest__", "自检", 5)
    u.save(force=True)
    got_u = load_usage(1).get("__selftest__", {}).get("sec")
    print(f"时间归属写读    {'OK' if got_u else 'FAIL'}")
    if got_u:
        u.apps.pop("__selftest__", None)
        u.dirty = True
        u.save(force=True)

    rec = {"id": "selftest", "ts": time.time(), "time": "12:00",
           "day": "1970-01-01", "answer": "自检写入", "kind": "normal",
           "active": 0, "n": 0, "delay": 0, "shot": "", "src": "pc"}
    append_log(rec)
    got = [r for r in read_log() if r.get("id") == "selftest"]
    print(f"写读日志        {'OK' if got else 'FAIL'}")
    if got:
        # 清理自检记录
        rows = [r for r in read_log() if r.get("id") != "selftest"]
        tmp = LOG_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        os.replace(tmp, LOG_PATH)
        _LOG_CACHE["mtime"] = 0
        print("清理自检记录    OK")
    print("自检完成")


def _console_for_selftest():
    """
    打包成无控制台的 exe 之后，print 出来的东西没人看得见。

    所以跑 --selftest 时把标准流接回控制台：从 cmd 里调用就挂到那个窗口，
    双击调用就新开一个。返回 "allocated" 表示是新开的，结束前得停一下，
    不然窗口一闪就没了。
    """
    if not getattr(sys, "frozen", False):
        return None
    try:
        k32 = ctypes.windll.kernel32
        mode = None
        if k32.AttachConsole(-1):          # ATTACH_PARENT_PROCESS
            mode = "attached"
        elif k32.AllocConsole():
            mode = "allocated"
        if not mode:
            return None
        # 故意不指定 encoding：跟着控制台自己的代码页走（中文系统就是 GBK），
        # 中文才不会乱码。errors="replace" 是给零宽空格那类字符兜底。
        sys.stdout = open("CONOUT$", "w", errors="replace", buffering=1)
        sys.stderr = sys.stdout
        sys.stdin = open("CONIN$", "r", errors="replace", buffering=1)
        return mode
    except Exception:
        return None


def _pause_before_close(seconds=30):
    """
    双击启动时新开的那个控制台，得停一下让人看清输出。

    但不能用 input() 死等——在没有键盘的场合（管道、远程会话）它会一直挂着，
    用户只看到一个不动的窗口，还以为程序坏了。所以改成限时等待按键。
    """
    try:
        import msvcrt
        deadline = time.time() + seconds
        while time.time() < deadline:
            if msvcrt.kbhit():
                msvcrt.getch()
                return
            time.sleep(0.1)
    except Exception:
        pass


def main():
    if "--selftest" in sys.argv:
        mode = _console_for_selftest()
        try:
            selftest()
        finally:
            if mode == "allocated":
                try:
                    print("\n（30 秒后自动关闭，按任意键立即关闭）")
                except Exception:
                    pass
                _pause_before_close()
        return

    def hook(exc_type, exc, tb):
        text = "".join(traceback.format_exception(exc_type, exc, tb))
        log_error(text)
        # 无控制台的 exe 崩溃时日志写在 AppData 里，用户根本不会去看。
        # 弹一个框，至少知道是崩了、崩在哪。
        try:
            ctypes.windll.user32.MessageBoxW(
                None, text[-1200:], "Grill Me 启动失败", 0x10)
        except Exception:
            pass

    sys.excepthook = hook

    if not acquire_single_instance():
        # 已经有实例在跑。第二个实例绝对不能开始计时——两套计时同时写
        # state.json，就是"两条时间线"的来源。
        woke = poke_existing()
        if woke:
            body = ("质问我已经在运行了。\n\n"
                    "它就在屏幕右下角，刚才已经把它点亮了一下——"
                    "没看见的话：收起时它要么是任务栏托盘里的那个小方块图标"
                    "（左键点一下就叫回来），要么是右下角一个悬浮的小方块。\n\n"
                    "这次不会再开第二个，所以不会出现两套时间线。")
        else:
            body = ("质问我已经在运行了。\n\n"
                    "这次不会再开第二个，所以不会出现两套时间线。")
        # MB_ICONINFORMATION | MB_TOPMOST | MB_SETFOREGROUND
        # 不加 TOPMOST 的话，框会被前台窗口压在下面，用户以为"没反应"。
        try:
            ctypes.windll.user32.MessageBoxW(
                None, body, "质问我 · 已经在运行", 0x40 | 0x40000 | 0x10000)
        except Exception:
            pass
        return

    app = AskMe()
    if "--demo" in sys.argv:
        app.root.after(600, lambda: app.show_popup("normal"))
    app.root.mainloop()


if __name__ == "__main__":
    main()
