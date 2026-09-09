"""平板的外壳贴图 —— 由手机那张 frame.png 拼出来，不是另画一张。

    docs/make_tablet_frame.py   （在仓库根目录跑，直接覆盖 textures/phone/frame_tablet.png）

为什么是拼的，不是重画

外壳这一圈是有【剖面】的：从外往里数 3 像素透明边距、1 像素黑描边、3 像素浅银高光、
1 像素暗线、4 像素中灰机身、4 像素黑内框。手机那张图上四条边、四个角全按这个剖面画好了，
重画一张平板的等于把这套剖面照抄一遍 —— 抄错一个像素，两台设备摆在一起就看得出不是一家的。

所以这里做的是搬运：四个角原样搬过来（圆角与描边一个像素都不动），四条边取一段【没有按键】
的截面平铺出去（直边上的剖面处处相同，平铺与拉伸结果一样，但平铺不会因为倍数不整而糊）。

按键换到上下两条边

手机竖着拿，音量在左、电源在右；平板横过来，这两组就得挪到上下两条边上——物品模型里那六个键
就是这么挪的（见 tablet_black.json），外壳这一圈也得跟着挪，否则玩家在物品上看到键在上下、
一开界面又跑到左右去了。

【电源在上、音量在下，都靠左那一头】：

  上下 —— 平板正着拿的时候电源在顶边，跟真平板一个样。
  靠左 —— 屏幕右侧那一条是导航栏（见 NavBarLayout），实体键再挤到右边去，一台机器上
          能按的和不能按的就全堆在同一侧了。

沿边的位置按"离手机顶边多远，就离平板左边多远"换算。像素尺寸不变——按键是实体，屏幕大一圈
它不该跟着变大。

尺寸

手机 272×432 是机身 136×216 的 2 倍；平板机身 256×184，同一个倍数就是 512×368。
PhoneSkin.drawFrame 按 DeviceMetrics.TABLET 的机身尺寸切九宫格，四角的 24 设计像素在这张图上
正好是 48 像素 —— 与下面搬运用的 CORNER 是同一个数，改一个必须改另一个。
"""
import struct
import zlib

SRC = 'src/main/resources/assets/mcphone/textures/phone/frame.png'
DST = 'src/main/resources/assets/mcphone/textures/phone/frame_tablet.png'

# 平板机身 256×184，与手机同为 2 倍图
DST_W, DST_H = 512, 368

# 四角搬多大。与 PhoneChassis.FRAME_CORNER 的 24 设计像素对应（2 倍图上是 48）
CORNER = 48

# 平铺用的那一段截面取在哪儿：都得避开按键，也避开圆角
CLEAN_ROW = 300    # 左右边框：取这一行的横截面
CLEAN_COL = 136    # 上下边框：取这一列的竖截面

# 手机上按键在哪几行（含两端）。左边三段：静音拨片、音量＋、音量－；右边一段：电源
LEFT_KEYS = [(75, 93), (106, 140), (147, 181)]
RIGHT_KEYS = [(108, 149)]

# 一枚按键连同它底下那段边框，从外沿往里取多宽。剖面一共 16 像素（3 边距 + 13 圈），
# 整段搬过去才不会在按键两头留下接缝
BAND = 16


def read_png(path):
    data = open(path, 'rb').read()
    pos, idat, w, h, depth, color = 8, b'', None, None, None, None
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + ln]
        if typ == b'IHDR':
            w, h, depth, color = struct.unpack('>IIBB', chunk[:10])
        elif typ == b'IDAT':
            idat += chunk
        pos += 12 + ln
    if depth != 8:
        raise SystemExit('只认 8 位通道，这张是 %d 位' % depth)

    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    raw = zlib.decompress(idat)
    stride = w * ch
    out, prev, i = bytearray(), bytearray(stride), 0
    for _ in range(h):
        f = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        # PNG 的五种逐行滤波，得原样还原，不能只处理常见那两种
        if f == 1:
            for x in range(ch, stride):
                line[x] = (line[x] + line[x - ch]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x - ch] if x >= ch else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - ch] if x >= ch else 0
                b, c = prev[x], (prev[x - ch] if x >= ch else 0)
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out += line
        prev = line

    px = [[(0, 0, 0, 0)] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            i = (y * w + x) * ch
            if ch == 4:
                px[y][x] = tuple(out[i:i + 4])
            elif ch == 3:
                px[y][x] = (out[i], out[i + 1], out[i + 2], 255)
            else:
                raise SystemExit('只认 RGB / RGBA')
    return w, h, px


def write_png(path, px):
    h, w = len(px), len(px[0])
    raw = bytearray()
    for row in px:
        raw.append(0)
        for p in row:
            raw += bytes(p)

    def chunk(tag, body):
        c = tag + body
        return struct.pack('>I', len(body)) + c + struct.pack('>I', zlib.crc32(c) & 0xFFFFFFFF)

    open(path, 'wb').write(
        b'\x89PNG\r\n\x1a\n'
        + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
        + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
        + chunk(b'IEND', b''))


def main():
    sw, sh, src = read_png(SRC)
    print('手机外壳 %d×%d → 平板外壳 %d×%d' % (sw, sh, DST_W, DST_H))

    dst = [[(0, 0, 0, 0)] * DST_W for _ in range(DST_H)]

    # 1) 四个角原样搬。圆角、描边、高光都在这 48×48 里，一个像素都不动
    for y in range(CORNER):
        for x in range(CORNER):
            dst[y][x] = src[y][x]                                     # 左上
            dst[y][DST_W - CORNER + x] = src[y][sw - CORNER + x]      # 右上
            dst[DST_H - CORNER + y][x] = src[sh - CORNER + y][x]      # 左下
            dst[DST_H - CORNER + y][DST_W - CORNER + x] = src[sh - CORNER + y][sw - CORNER + x]

    # 2) 上下边：把一列干净截面横着铺过去
    for x in range(CORNER, DST_W - CORNER):
        for y in range(CORNER):
            dst[y][x] = src[y][CLEAN_COL]
            dst[DST_H - CORNER + y][x] = src[sh - CORNER + y][CLEAN_COL]

    # 3) 左右边：把一行干净截面竖着铺下来
    for y in range(CORNER, DST_H - CORNER):
        for x in range(CORNER):
            dst[y][x] = src[CLEAN_ROW][x]
            dst[y][DST_W - CORNER + x] = src[CLEAN_ROW][sw - CORNER + x]

    # 4) 按键挪到上下两条边：手机左边的那三枚（音量/静音）到平板【下】边，
    #    右边的电源到【上】边；沿边都从左往右数（理由见文件开头）。
    #    src 的 x 是"从外沿往里"，贴过去时也得让 dst 的外沿对上：上边是 y=0 那头，下边是 y=H-1 那头
    for ya, yb in LEFT_KEYS:
        for y in range(ya, yb + 1):
            for x in range(BAND):
                dst[DST_H - 1 - x][y] = src[y][x]

    for ya, yb in RIGHT_KEYS:
        for y in range(ya, yb + 1):
            for x in range(BAND):
                dst[x][y] = src[y][sw - 1 - x]

    write_png(DST, dst)

    # 自检：中间必须是【透明】的 —— 外壳最后画、盖在内容之上，不透明就把整块屏幕糊掉了
    solid = [(x, y) for y in range(CORNER, DST_H - CORNER)
             for x in range(CORNER, DST_W - CORNER) if dst[y][x][3] > 0]
    if solid:
        raise SystemExit('中间那块不是全透明，%d 个像素有色，第一个在 %s' % (len(solid), solid[0]))

    keys = sum(1 for y in range(DST_H) for x in range(DST_W)
               if dst[y][x][3] > 0 and (y < 3 or y >= DST_H - 3))
    print('写出 %s，上下边沿的按键像素 %d 个（左右边沿应为 0）' % (DST, keys))
    side = sum(1 for y in range(DST_H) for x in range(DST_W)
               if dst[y][x][3] > 0 and (x < 3 or x >= DST_W - 3))
    if side:
        raise SystemExit('左右边沿还有 %d 个像素，说明按键没挪干净' % side)


if __name__ == '__main__':
    main()
