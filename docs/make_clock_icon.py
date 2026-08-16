"""生成时钟 App 的 20×20 图标。没有 PIL，自己写 PNG 编码。

风格照着现有图标来：平涂、四五种颜色、深色描边、填满格子。
指针摆成 10:10 —— 钟表广告的经典姿势，两根指针对称向上，
在 20 像素下也认得出是个钟，而且看着像在笑。
"""
import zlib, struct, math

W = H = 20

RIM   = (0x30, 0x30, 0x30, 255)   # 深灰描边，取自 settings.png
FACE  = (0xE7, 0xE7, 0xE7, 255)   # 表盘，同上
HAND  = (0x30, 0x30, 0x30, 255)   # 指针
MARK  = (0x7C, 0x7C, 0x7C, 255)   # 刻度，settings.png 的中灰
PIN   = (0xD7, 0x00, 0x00, 255)   # 中心轴，music.png 的红
CLEAR = (0, 0, 0, 0)

CX = CY = 9.5
R_OUT = 9.4      # 外缘
R_RIM = 8.1      # 描边内沿

px = [[CLEAR for _ in range(W)] for _ in range(H)]


def dist(x, y):
    return math.hypot(x - CX, y - CY)


def seg_dist(x, y, x0, y0, x1, y1):
    """点到线段的距离"""
    dx, dy = x1 - x0, y1 - y0
    if dx == 0 and dy == 0:
        return math.hypot(x - x0, y - y0)
    t = max(0.0, min(1.0, ((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy)))
    return math.hypot(x - (x0 + t * dx), y - (y0 + t * dy))


def hand(angle_deg, length):
    """从 12 点起顺时针的角度，返回指针末端坐标"""
    a = math.radians(angle_deg)
    return CX + math.sin(a) * length, CY - math.cos(a) * length


# 10:10 —— 时针在 10 时 10 分处（305°），分针在 10 分处（60°）
HX, HY = hand(305, 4.6)   # 时针，短粗
MX, MY = hand(60, 6.4)    # 分针，长细

for y in range(H):
    for x in range(W):
        cx, cy = x + 0.5, y + 0.5
        d = dist(cx, cy)

        if d > R_OUT:
            continue                       # 圆外，透明
        if d > R_RIM:
            px[y][x] = RIM                 # 描边
            continue

        px[y][x] = FACE                    # 表盘底

        # 四个整点刻度：12 / 3 / 6 / 9
        for ang in (0, 90, 180, 270):
            mx, my = hand(ang, 6.9)
            if math.hypot(cx - mx, cy - my) < 0.75:
                px[y][x] = MARK

        # 指针压在刻度之上
        if seg_dist(cx, cy, CX, CY, MX, MY) < 0.60:
            px[y][x] = HAND
        if seg_dist(cx, cy, CX, CY, HX, HY) < 0.78:
            px[y][x] = HAND

        # 中心轴最后画，盖住两根指针的根部
        if d < 1.05:
            px[y][x] = PIN


def chunk(tag, data):
    return (struct.pack('>I', len(data)) + tag + data
            + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))


raw = bytearray()
for y in range(H):
    raw.append(0)                          # 每行滤波器 0（None）
    for x in range(W):
        raw.extend(px[y][x])

png = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
       + chunk(b'IEND', b''))

out = '/root/projects/mcphone/src/main/resources/assets/mcphone/textures/app/clock.png'
open(out, 'wb').write(png)
print(f'写出 {out}  {len(png)} 字节')

# 画个字符预览，好确认它真的像个钟
print()
for y in range(H):
    row = ''
    for x in range(W):
        c = px[y][x]
        cx, cy = x + 0.5, y + 0.5
        if c == CLEAR: row += ' '
        elif c == PIN: row += 'o'
        elif c == MARK: row += '+'
        elif c == FACE: row += '.'
        elif dist(cx, cy) > R_RIM: row += '#'      # 描边
        else: row += '@'                            # 指针
    print('   ' + row)
