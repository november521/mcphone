"""「任务书」App 的主屏图标。20×20 RGBA。

路径与尺寸就是换肤契约：assets/mcphone/textures/app/quests.png，
资源包换掉同名文件即可，这个脚本只是我们自己那张的来源。

为什么不画成一本书

「阅读」那张图标画的就是一本摊开的书 —— 实际上用的正是 FTB 任务书那张
物品贴图（两边调色板一个不差）。任务书这一格要是也照着那本书画，主屏上
两格就分不出来了，而它俩八成还挨着。所以这里走另一条路：清单 + 对勾，
「一件件做完」这个意思比书本身更贴近这个 App 干的事。

底色选深绿，也是同一个考虑

现有十三张图标的底色里没有绿：商店与相册是 #00AEFF、天气 #0090FF、
浏览器 #C8E6FF、时钟 #F7D349、阅读 #ECD390、音乐 #D70000、
传送石 #FFC3FF、聊天 #FBFFE6、记事本 #FFFFFF、设置 #E7E7E7、
相机与末影箱是黑。绿是唯一还空着的一档，隔着一屏也认得出是哪一格。

没有半透明像素

alpha 只有 0 与 255 两个值，与手绘的那几张一致。1.7.40 之前半透明像素
会被当成不透明画，那条已经修了；但边缘不做抗锯齿仍然是这一组图标的统一
风格，掺一张带羽化边的进去反而突兀。

圆角切法与其余十三张一致

每个角切掉五个像素：贴着角的那一行切 3 个、往里两行各切 1 个。这是从
reader.png 上量出来的，别改 —— 十四张图标摆在一起，圆角不一样一眼就看得见。
"""
import zlib, struct, os

S = 20

TILE  = (0x2F, 0x7D, 0x4F, 255)   # 深绿的底
PAPER = (0xF4, 0xEF, 0xDF, 255)   # 纸
EDGE  = (0xCF, 0xC5, 0xA8, 255)   # 纸的右下沿，让它看着有一点厚度
RULE  = (0xA9, 0x9E, 0x82, 255)   # 纸上那两条横线
TICK  = (0x1F, 0x7A, 0x3D, 255)   # 对勾，比底色再深一档
TICK_D= (0x14, 0x59, 0x2B, 255)   # 对勾的下沿
CLEAR = (0, 0, 0, 0)

# 角上切掉的五个像素，写成"离角多远"。四个角镜像着用
CORNER = [(0, 0), (1, 0), (2, 0), (0, 1), (0, 2)]

# 纸：x 4..15，y 3..16
PAPER_X0, PAPER_X1 = 4, 15
PAPER_Y0, PAPER_Y1 = 3, 16

# 纸上那两条横线：(y, x起, x止)
RULES = [(5, 6, 13), (7, 6, 11)]

# 对勾的骨架，一像素一格；每格再往下补一格，凑成两像素粗的笔画
# 左臂三格、右臂五格 —— 对勾的两臂等长就不像对勾了，像个 V
TICK_PATH = [(5, 11), (6, 12), (7, 13), (8, 14),
             (9, 13), (10, 12), (11, 11), (12, 10), (13, 9)]


def cut(x, y):
    """这个像素是不是被圆角切掉了"""
    for dx, dy in CORNER:
        for cx, sx in ((dx, 1), (S - 1 - dx, -1)):
            for cy, sy in ((dy, 1), (S - 1 - dy, -1)):
                if (x, y) == (cx, cy):
                    return True
    return False


def build():
    px = [[TILE for _ in range(S)] for _ in range(S)]

    for y in range(PAPER_Y0, PAPER_Y1 + 1):
        for x in range(PAPER_X0, PAPER_X1 + 1):
            px[y][x] = PAPER

    # 右沿与下沿压暗一像素
    for y in range(PAPER_Y0 + 1, PAPER_Y1 + 1):
        px[y][PAPER_X1] = EDGE
    for x in range(PAPER_X0 + 1, PAPER_X1 + 1):
        px[PAPER_Y1][x] = EDGE

    for y, x0, x1 in RULES:
        for x in range(x0, x1 + 1):
            px[y][x] = RULE

    for x, y in TICK_PATH:
        px[y][x] = TICK
        if y + 1 <= PAPER_Y1:
            px[y + 1][x] = TICK_D

    for y in range(S):
        for x in range(S):
            if cut(x, y):
                px[y][x] = CLEAR
    return px


def write_png(path, px):
    raw = b''
    for row in px:
        raw += b'\x00' + b''.join(bytes(c) for c in row)

    def chunk(tag, data):
        c = tag + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c))

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', S, S, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    with open(path, 'wb') as f:
        f.write(png)


if __name__ == '__main__':
    out = os.path.join(os.path.dirname(__file__), os.pardir,
                       'src/main/resources/assets/mcphone/textures/app/quests.png')
    px = build()
    write_png(os.path.normpath(out), px)

    legend = {TILE: '#', PAPER: 'P', EDGE: 'p', RULE: '-',
              TICK: 'V', TICK_D: 'v', CLEAR: '.'}
    for row in px:
        print(''.join(legend[c] for c in row))
