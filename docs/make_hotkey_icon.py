"""生成「快捷键」App 的图标：assets/mcphone/textures/app/hotkey.png（20×20 RGBA）。

与 docs/ 下其它 make_*_icons.py 同一路数：图标是画出来的、不是手绘的，
所以生成脚本跟着进仓库 —— 以后要调色或者改形状，改这里重跑，不要直接改 PNG。

图案是一道闪电：这个 App 的动作就是「触发一次」。选它是因为它在 20px 上剪影够硬
—— 试过键帽加列表线、键帽加五角星两版，缩到这个尺寸都糊成一块圆角色斑，
和「记事本」「终端」那两个放在一起分不出来。

用法：
    python docs/make_hotkey_icon.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 20
OUT = (Path(__file__).resolve().parent.parent
       / "shared/src/main/resources/assets/mcphone/textures/app/hotkey.png")

BOLT = [(11, 1), (4, 11), (9, 11), (8, 19), (16, 8), (11, 8)]

FILL = (255, 205, 60, 255)
EDGE = (150, 105, 0, 255)


def main():
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 描一道暗边：金色落在浅色壁纸上会化掉，深色描边让剪影在任何背景下都立得住
    d.polygon(BOLT, fill=FILL, outline=EDGE)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
