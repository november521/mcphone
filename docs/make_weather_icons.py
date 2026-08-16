"""天气页上那个大图标，六种天各一张。32×32 RGBA。

这些是【占位图】：形状对、含义清楚、不难看，但等着被手绘的替换。
路径与尺寸就是最终契约，替换时只要覆盖同名文件即可。

为什么是 32×32 而不是 20×20：这是页面上的主角，不是主屏格子里的小图标。
20 像素放大到 32 会糊，而 32 缩到别处也还看得清。
"""
import zlib, struct, math, os

S = 32

SUN    = (0xFF, 0xC8, 0x33, 255)
SUN_D  = (0xC8, 0x8A, 0x14, 255)
CLOUD  = (0xE7, 0xE7, 0xE7, 255)
CLOUD2 = (0xA8, 0xA8, 0xB4, 255)
DARK   = (0x30, 0x30, 0x30, 255)
RAIN   = (0x4F, 0x9F, 0xE0, 255)
SNOW   = (0xFF, 0xFF, 0xFF, 255)
BOLT   = (0xFF, 0xE0, 0x3A, 255)
VOID   = (0x8A, 0x76, 0xB0, 255)
CLEAR  = (0, 0, 0, 0)


def blank():
    return [[False] * S for _ in range(S)]


def disc(m, cx, cy, r):
    for y in range(S):
        for x in range(S):
            if math.hypot(x + .5 - cx, y + .5 - cy) <= r:
                m[y][x] = True


def rect(m, x0, y0, x1, y1):
    for y in range(max(0, y0), min(S, y1)):
        for x in range(max(0, x0), min(S, x1)):
            m[y][x] = True


def seg(m, x0, y0, x1, y1, w):
    dx, dy = x1 - x0, y1 - y0
    dd = dx * dx + dy * dy
    for y in range(S):
        for x in range(S):
            px, py = x + .5, y + .5
            t = 0. if dd == 0 else max(0., min(1., ((px - x0) * dx + (py - y0) * dy) / dd))
            if math.hypot(px - (x0 + t * dx), py - (y0 + t * dy)) <= w:
                m[y][x] = True


def poly(m, pts):
    """多边形填充，射线法。闪电那种折角形状用线段拼总是糊成一团"""
    for y in range(S):
        for x in range(S):
            px, py, inside, n = x + .5, y + .5, False, len(pts)
            for i in range(n):
                x0, y0 = pts[i]
                x1, y1 = pts[(i + 1) % n]
                if (y0 > py) != (y1 > py) and px < (x1 - x0) * (py - y0) / (y1 - y0) + x0:
                    inside = not inside
            if inside:
                m[y][x] = True


def edge_of(mask, blockers=()):
    out = blank()
    for y in range(S):
        for x in range(S):
            if not mask[y][x]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                inside = 0 <= nx < S and 0 <= ny < S
                if inside and mask[ny][nx]:
                    continue
                if inside and any(b[ny][nx] for b in blockers):
                    continue
                out[y][x] = True
                break
    return out


def paint(px, mask, fill, edge=None, blockers=()):
    e = edge_of(mask, blockers) if edge else None
    for y in range(S):
        for x in range(S):
            if mask[y][x]:
                px[y][x] = edge if (e and e[y][x]) else fill


def cloud_mask():
    """云：三个鼓包压一条平底。鼓包要高出底边足够多才看得出是云"""
    m = blank()
    disc(m, 13, 12, 6.2)      # 主鼓包，最高
    disc(m, 20.5, 14.5, 4.8)  # 右鼓包
    disc(m, 7.5, 15, 4.2)     # 左鼓包
    rect(m, 4, 14, 25, 20)    # 平底
    return m


def write(name, px):
    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))
    raw = bytearray()
    for y in range(S):
        raw.append(0)
        for x in range(S):
            raw.extend(px[y][x])
    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', S, S, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
           + chunk(b'IEND', b''))
    out = ('/root/projects/mcphone/src/main/resources/assets/mcphone/'
           f'textures/weather/{name}.png')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, 'wb').write(png)
    return len(png)


CH = {SUN: '*', SUN_D: '+', CLOUD: '.', CLOUD2: ':', DARK: '#',
      RAIN: '/', SNOW: 'o', BOLT: '!', VOID: '~', CLEAR: ' '}


def show(name, px, size):
    print(f'\n── {name}.png  {size} 字节 ' + '─' * 20)
    for y in range(S):
        row = ''.join(CH[px[y][x]] for x in range(S))
        if row.strip():
            print('  ' + row.rstrip())


made = []

# ---------- 晴：太阳 + 八道短光芒 ----------
px = [[CLEAR] * S for _ in range(S)]
sun = blank()
disc(sun, 16, 16, 7.5)
rays = blank()
for a in range(0, 360, 45):
    ra = math.radians(a)
    seg(rays, 16 + math.cos(ra) * 9.5, 16 + math.sin(ra) * 9.5,
              16 + math.cos(ra) * 13.0, 16 + math.sin(ra) * 13.0, 1.0)
paint(px, rays, SUN)
paint(px, sun, SUN, SUN_D)
made.append(('clear', px, write('clear', px)))

# ---------- 雨：云 + 三道斜雨线 ----------
px = [[CLEAR] * S for _ in range(S)]
paint(px, cloud_mask(), CLOUD, DARK)
drops = blank()
for x0 in (10, 16, 22):
    seg(drops, x0, 23, x0 - 2, 29, 1.0)
paint(px, drops, RAIN)
made.append(('rain', px, write('rain', px)))

# ---------- 雪：云 + 三片雪 ----------
px = [[CLEAR] * S for _ in range(S)]
paint(px, cloud_mask(), CLOUD, DARK)
# 雪花直接点像素：5 像素见方的地方画四道笔画，线宽再细也会糊成一块。
# 这个尺寸下只能一个点一个点摆
FLAKE = ("..#..",
         "#.#.#",
         ".###.",
         "#.#.#",
         "..#..")
flakes = blank()
for cx, cy in ((10, 25), (16, 28), (22, 24)):
    for dy, row in enumerate(FLAKE):
        for dx, ch in enumerate(row):
            if ch != '#':
                continue
            x, y = cx - 2 + dx, cy - 2 + dy
            if 0 <= x < S and 0 <= y < S:
                flakes[y][x] = True
paint(px, flakes, SNOW)
made.append(('snow', px, write('snow', px)))

# ---------- 雷雨：云 + 一道折角闪电 ----------
px = [[CLEAR] * S for _ in range(S)]
paint(px, cloud_mask(), CLOUD2, DARK)
bolt = blank()
poly(bolt, [(18, 21), (12, 27), (15.5, 27), (13, 32), (20, 25), (16, 25), (20.5, 21)])
paint(px, bolt, BOLT)
made.append(('thunder', px, write('thunder', px)))

# ---------- 阴：只有云，暗一档 ----------
px = [[CLEAR] * S for _ in range(S)]
paint(px, cloud_mask(), CLOUD2, DARK)
made.append(('dry', px, write('dry', px)))

# ---------- 无天气：空环加一横 ----------
px = [[CLEAR] * S for _ in range(S)]
ring, hole = blank(), blank()
disc(ring, 16, 16, 11)
disc(hole, 16, 16, 8)
band = blank()
for y in range(S):
    for x in range(S):
        if ring[y][x] and not hole[y][x]:
            band[y][x] = True
seg(band, 11, 16, 21, 16, 1.4)
paint(px, band, VOID)
made.append(('none', px, write('none', px)))

for name, px, size in made:
    show(name, px, size)

print(f'\n六张共 {sum(s for _, _, s in made)} 字节')
