"""天气 App 图标：云后面一个太阳。20×20，风格同其余图标。

不画特定的某种天（雨/雪/雷），因为天是会变的——图标固定画"天气"这件事
本身。太阳与云的组合是这个含义最通用的画法，20 像素下也认得出。

画法：先把太阳和云各自算成一张布尔掩码，再统一描边。直接边画边描的话，
两个形状叠在一起的地方会互相把对方的边擦出洞来。
"""
import zlib, struct, math

W = H = 20
SUN   = (0xFF, 0xC8, 0x33, 255)
SUN_D = (0xC8, 0x8A, 0x14, 255)
CLOUD = (0xE7, 0xE7, 0xE7, 255)
CLD_D = (0x30, 0x30, 0x30, 255)
CLEAR = (0, 0, 0, 0)


def blank():
    return [[False] * W for _ in range(H)]


def add_disc(mask, cx, cy, r):
    for y in range(H):
        for x in range(W):
            if math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= r:
                mask[y][x] = True


def add_rect(mask, x0, y0, x1, y1):
    for y in range(max(0, y0), min(H, y1)):
        for x in range(max(0, x0), min(W, x1)):
            mask[y][x] = True


def edge_of(mask, blockers=()):
    """掩码的外圈一像素。被 blockers 盖住的那一侧不算边"""
    out = blank()
    for y in range(H):
        for x in range(W):
            if not mask[y][x]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                inside = 0 <= nx < W and 0 <= ny < H
                if inside and mask[ny][nx]:
                    continue
                blocked = inside and any(b[ny][nx] for b in blockers)
                if not blocked:
                    out[y][x] = True
                    break
    return out


# ---- 太阳：偏右上，下半会被云挡住 ----
sun = blank()
add_disc(sun, 12.5, 6.5, 4.6)

# ---- 云：三个圆压一条底边，连成一朵 ----
cloud = blank()
add_disc(cloud, 7.0, 12.0, 4.4)
add_disc(cloud, 11.5, 13.0, 3.8)
add_disc(cloud, 3.8, 14.0, 3.2)
add_rect(cloud, 1, 13, 16, 17)

sun_edge = edge_of(sun, blockers=(cloud,))
cloud_edge = edge_of(cloud)

px = [[CLEAR] * W for _ in range(H)]
for y in range(H):
    for x in range(W):
        if sun[y][x]:
            px[y][x] = SUN_D if sun_edge[y][x] else SUN
for y in range(H):
    for x in range(W):
        if cloud[y][x]:                      # 云画在太阳之上
            px[y][x] = CLD_D if cloud_edge[y][x] else CLOUD


def chunk(tag, data):
    return (struct.pack('>I', len(data)) + tag + data
            + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))


raw = bytearray()
for y in range(H):
    raw.append(0)
    for x in range(W):
        raw.extend(px[y][x])

png = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
       + chunk(b'IEND', b''))

out = '/root/projects/mcphone/src/main/resources/assets/mcphone/textures/app/weather.png'
open(out, 'wb').write(png)
print(f'写出 {out}  {len(png)} 字节\n')

CH = {SUN: '*', SUN_D: '+', CLOUD: '.', CLD_D: '#', CLEAR: ' '}
for y in range(H):
    print('   ' + ''.join(CH[px[y][x]] for x in range(W)))
