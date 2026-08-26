"""「阅读」App 的图标，以及书架上那本兜底的书。20×20 与 16×16，RGBA。

这些是【占位图】：形状对、含义清楚、不难看，但等着被手绘的替换。
路径与尺寸就是最终契约，替换时覆盖同名文件即可。

为什么用一张手写的点阵而不是几何函数
    别的图标（天气那六张）是用圆和多边形拼的，因为它们是圆的。书不是：
    它全靠"两页纸对着一条书脊"这个轮廓认出来，20 像素里每一个点摆在哪儿
    都算数，画出来再对着改比调参数快得多。

    所以下面就是这张图本身，一行一行摆在那儿。要改造型，直接改这些字符。

图例
    o 底色（图标那张的圆角方块；书架小图上是透明）
    # 纸  |  书脊  -  纸上的字
    空格 透明
"""
import zlib, struct, os

ART = [
    "   oooooooooooooo   ",
    " oooooooooooooooooo ",
    " oooooooooooooooooo ",
    "oooooooooooooooooooo",
    "ooooooooo||ooooooooo",
    "ooooooo##||##ooooooo",
    "ooooo####||####ooooo",
    "ooo######||######ooo",
    "oo#######||#######oo",
    "oo#-----#||#-----#oo",
    "oo#######||#######oo",
    "oo#-----#||#-----#oo",
    "oo#######||#######oo",
    "oo#-----#||#-----#oo",
    "oo#######||#######oo",
    "oo#-----#||#-----#oo",
    "oo#######||#######oo",
    " oeeeeeee||eeeeeeeo ",
    " oooooooooooooooooo ",
    "   oooooooooooooo   ",
]

CLEAR = (0, 0, 0, 0)
TILE  = (0x8A, 0x5A, 0x2B, 255)   # 皮革棕。主屏上十二个图标的底色互不相同，这一格占棕
PAGE  = (0xF6, 0xEC, 0xD4, 255)   # 纸
SPINE = (0x4A, 0x2C, 0x12, 255)   # 书脊，比底色深两档才压得住
TEXT  = (0xC2, 0xA5, 0x7A, 255)   # 纸上的字：只是暗示有字，不必真的可读
EDGE  = (0xD1, 0xBE, 0x94, 255)   # 最底下那一行纸：暗一档，书才有厚度而不是一张纸

COLORS = {' ': CLEAR, 'o': TILE, '#': PAGE, '|': SPINE, '-': TEXT, 'e': EDGE}


def png(path, pixels, size):
    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        for x in range(size):
            raw.extend(pixels[y][x])
    data = (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
            + chunk(b'IEND', b''))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'wb').write(data)
    return len(data)


def app_icon():
    """主屏那一格：整张 20×20，带圆角底。"""
    return [[COLORS[c] for c in row] for row in ART]


def shelf_icon():
    """书架列表里的兜底小图：16×16，只有书，没有底。

    书源画不出图标时（比如 Patchouli 里那种没有对应物品的书）用它。
    去掉底色是因为它画在列表行上，一块棕色方块会把行切断，而书本身
    悬在那里正好。裁的窗口对着书取，不是对着图取：书占 ART 的第 5～18 行、
    第 2～17 列，正好 16 列宽、14 行高，上下各留一行空就是 16×16。
    """
    out = []
    for row in ART[3:19]:
        out.append([CLEAR if c == 'o' else COLORS[c] for c in row[2:18]])
    return out


ASSETS = '/root/projects/mcphone/src/main/resources/assets/mcphone/textures/'

if __name__ == '__main__':
    n1 = png(ASSETS + 'app/reader.png', app_icon(), 20)
    n2 = png(ASSETS + 'reader/book.png', shelf_icon(), 16)
    print(f'app/reader.png {n1} B, reader/book.png {n2} B')
