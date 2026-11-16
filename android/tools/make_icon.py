#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 Grill Me 的安卓图标：纯标准库（zlib + struct），4 倍超采样抗锯齿。

用法: py tools/make_icon.py
产出: app/src/main/res/mipmap-*/ic_launcher.png 与 docs/icon-512.png
"""
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# 画布坐标固定 512，最后按目标尺寸缩放
C = 512
SS = 4  # 超采样倍数


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def rounded_rect(x, y, x0, y0, x1, y1, r):
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def in_triangle(px, py, a, b, c):
    def sign(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])

    d1 = sign((px, py), a, b)
    d2 = sign((px, py), b, c)
    d3 = sign((px, py), c, a)
    neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
    pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
    return not (neg and pos)


BG_TOP = (0x25, 0x29, 0x31)
BG_BOT = (0x0E, 0x10, 0x14)
AMBER = (0xFF, 0xB0, 0x2E)
INK = (0x1A, 0x1D, 0x23)


def sample(x, y):
    """返回该点的 RGB，未命中返回 None（透明）。"""
    # 背景圆角方
    if not rounded_rect(x, y, 16, 16, C - 16, C - 16, 112):
        return None
    col = lerp(BG_TOP, BG_BOT, y / float(C))
    # 气泡主体
    bubble = rounded_rect(x, y, 92, 104, C - 92, 344, 66)
    # 气泡小尾巴
    tail = in_triangle(x, y, (168, 320), (140, 412), (256, 336))
    if bubble or tail:
        col = AMBER
        # 三个点
        for cx in (176, 256, 336):
            if (x - cx) ** 2 + (y - 228) ** 2 <= 26 * 26:
                col = INK
                break
    return col


def render(size):
    """渲染 size×size 的 RGBA 像素行列表。"""
    rows = []
    step = C / float(size)
    inv = 1.0 / (SS * SS)
    for py in range(size):
        row = bytearray()
        for px in range(size):
            r = g = b = a = 0
            for sy in range(SS):
                for sx in range(SS):
                    x = (px + (sx + 0.5) / SS) * step
                    y = (py + (sy + 0.5) / SS) * step
                    c = sample(x, y)
                    if c is not None:
                        r += c[0]
                        g += c[1]
                        b += c[2]
                        a += 255
            n = SS * SS
            if a:
                # 按覆盖到的样本数还原颜色
                cov = a / float(255)
                row += bytes((
                    int(r / cov),
                    int(g / cov),
                    int(b / cov),
                    int(a * inv),
                ))
            else:
                row += b"\x00\x00\x00\x00"
        rows.append(bytes(row))
    return rows


def write_png(path, size):
    rows = render(size)
    raw = b"".join(b"\x00" + r for r in rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    print("  %-58s %6d B" % (os.path.relpath(path, ROOT), len(png)))


def main():
    res = os.path.join(ROOT, "app", "src", "main", "res")
    targets = [
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
    ]
    print("生成安卓图标：")
    for folder, size in targets:
        write_png(os.path.join(res, folder, "ic_launcher.png"), size)
    write_png(os.path.join(ROOT, "..", "docs", "icon-512.png"), 512)
    print("完成")


if __name__ == "__main__":
    main()
