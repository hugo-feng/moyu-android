#!/usr/bin/env python3
"""生成应用图标的 PNG 位图（Android 8 以下与部分启动器会用到）。

## 为什么需要这个脚本

Android 8+ 用 `mipmap-anydpi-v26/` 里的自适应图标（矢量），但**低版本系统
与部分第三方启动器仍会读 `mipmap-*/ic_launcher.png`**。这些 PNG 不能只有一份，
必须按密度各出一套，否则在低密度屏上会被拉伸得模糊。

把生成逻辑写成脚本而不是手工贴图，是为了让图标**可重建**：
改了配色或构图，跑一次就能得到全部 15 个文件，
而不是逐个手工导出（那种做法几次之后就没人敢改图标了）。

## 构图

与 `drawable/ic_launcher_foreground.xml` 保持一致：
深青底 + 米色书 + 书脊留缝 + 书页下缘的厚度。
这里用 Pillow 直接画多边形而不是解析 SVG —— 图形很简单，
硬编码坐标比引入一个 SVG 渲染依赖划算得多。

## 用法

    python tools/gen_launcher_icons.py
"""

from __future__ import annotations

import os
from PIL import Image, ImageDraw

# 与 res/values/colors.xml 的 ic_launcher_background 一致
BG = (0x2E, 0x6B, 0x62)
PAGE_LEFT = (0xEF, 0xE7, 0xD8)
PAGE_RIGHT = (0xF7, 0xF1, 0xE6)
SPINE = (0x1E, 0x4A, 0x44)
RULE = (0xB9, 0xAE, 0x9A)

# 各密度的 ic_launcher.png 边长（与 Android 官方推荐的启动器图标尺寸一致）
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# 自适应图标前景层的尺寸（108dp 画布 → mdpi 108px 起）
FOREGROUND_DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

# 构图在 108×108 参考画布上的坐标，与 ic_launcher_foreground.xml 对应
REF = 108.0
BOOK = {
    "spine": [(52, 32), (56, 32), (56, 76), (52, 76)],
    "left": [(30, 30), (52, 32), (52, 76), (30, 74)],
    "right": [(56, 32), (78, 30), (78, 74), (56, 76)],
    "rules": [
        [(61, 41), (73, 40.2), (73, 42.4), (61, 43.2)],
        [(61, 47), (73, 46.2), (73, 48.4), (61, 49.2)],
        [(61, 53), (69, 52.4), (69, 54.6), (61, 55.2)],
    ],
    "thickness": [(30, 74), (52, 76), (56, 76), (78, 74), (78, 78), (56, 80), (52, 80), (30, 78)],
}


def scaled(points, size: int):
    """把参考画布坐标缩放到目标尺寸。"""
    k = size / REF
    return [(x * k, y * k) for x, y in points]


def draw_book(draw: ImageDraw.ImageDraw, size: int, with_colors: bool) -> None:
    """画书本。

    `with_colors=False` 时全部用同一个实色（前景层用白色）——
    那是给**前景层**用的，它只提供形状，颜色由底层的青绿透出来。
    """
    if with_colors:
        colors = {
            "left": PAGE_LEFT,
            "right": PAGE_RIGHT,
            "spine": SPINE,
            "thickness": BG,
        }
    else:
        white = (255, 255, 255)
        colors = {"left": white, "right": white, "spine": white, "thickness": white}

    for key in ("left", "right", "spine", "thickness"):
        draw.polygon(scaled(BOOK[key], size), fill=colors[key])

    # 书页上的「文字」细线只在前景层画：
    # 单色/纯形状层里画了也看不出来，反而变成无意义的缺口
    if with_colors:
        for rule in BOOK["rules"]:
            draw.polygon(scaled(rule, size), fill=RULE)


def make_legacy_icon(size: int, round_icon: bool) -> Image.Image:
    """整枚图标（底色 + 书），用于 Android 8 以下。

    注意书本在整枚图标里要缩到约 3/4：自适应图标的前景有 66% 安全区，
    而这里的画布就是完整图标，照搬参考坐标会让书顶到边缘。
    """
    # 4x 超采样再缩小，得到平滑边缘（Pillow 不原生支持抗锯齿多边形填充）
    ss = 4
    big = Image.new("RGBA", (size * ss, size * ss), BG + (255,))
    d = ImageDraw.Draw(big)
    draw_book(d, int(size * ss * 0.74), with_colors=True)

    img = big.resize((size, size), Image.LANCZOS)

    if round_icon:
        mask = Image.new("L", (size * ss, size * ss), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
        mask = mask.resize((size, size), Image.LANCZOS)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(img, (0, 0), mask)
        return out
    return img


def make_foreground(size: int) -> Image.Image:
    """前景层：透明底 + 白色书本，落在中心 66% 安全区内。"""
    ss = 4
    big = Image.new("RGBA", (size * ss, size * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    draw_book(d, size * ss, with_colors=False)
    return big.resize((size, size), Image.LANCZOS)


def main() -> None:
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
    root = os.path.normpath(root)

    written = 0
    for density, size in DENSITIES.items():
        out_dir = os.path.join(root, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)

        make_legacy_icon(size, round_icon=False).save(os.path.join(out_dir, "ic_launcher.png"))
        make_legacy_icon(size, round_icon=True).save(os.path.join(out_dir, "ic_launcher_round.png"))
        written += 2
        print(f"  mipmap-{density}/ic_launcher.png + ic_launcher_round.png  ({size}x{size})")

    for density, size in FOREGROUND_DENSITIES.items():
        out_dir = os.path.join(root, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        make_foreground(size).save(os.path.join(out_dir, "ic_launcher_foreground.png"))
        written += 1
        print(f"  mipmap-{density}/ic_launcher_foreground.png  ({size}x{size})")

    print(f"\n共生成 {written} 个文件。")
    print("自适应图标（Android 8+）走 drawable/*.xml 与 mipmap-anydpi-v26/，不经过本脚本。")


if __name__ == "__main__":
    main()
