#!/usr/bin/env python3
"""生成贴图清单页 —— 从源码扫出这个模组认得的每一个贴图位。

    python3 docs/make_texture_manifest.py            # 写出 build/texture-manifest.html

================================================================
为什么是脚本，不是一份写好的清单
================================================================

写死一份清单，加一个 App、加一个可换肤元素就要记得同步它，而没有任何
机制会提醒你——于是它必然过期。PhoneTheme 头部那张贴图表就是这么烂掉的：
路径改了两个版本，注释一直没跟上，照它做出来的贴图一张都不会加载。

所以这里不存清单，只存"怎么把清单算出来"：

    App 图标      从 SPI 名单反推每个 PhoneApp 的 path
    天气图标      从 Weather.Kind 的枚举项
    可换肤元素    从 PhoneSkin.Element，连建议尺寸和兜底色都是从 javadoc 里读的
    兜底色的值    从 PhoneTheme 的常量表查

加了新东西，重跑一次就是了。
"""
import base64, html, json, os, re, struct, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.path.join(ROOT, 'src/main/resources/assets/mcphone/textures/')
SRC = os.path.join(ROOT, 'src/main/java/com/november/mcphone/')
BOX = 84


def read(p):
    return open(os.path.join(ROOT, p) if not os.path.isabs(p) else p, encoding='utf-8').read()


def info(rel):
    p = BASE + rel
    if not os.path.exists(p):
        return None
    d = open(p, 'rb').read()
    w, h, _, _ = struct.unpack('>IIBB', d[16:26])
    return {'w': w, 'h': h, 'bytes': len(d), 'data': base64.b64encode(d).decode()}


def clean(doc):
    t = re.sub(r'\{@link [^}]*?#?(\w+)\}', r'\1', doc)
    t = re.sub(r'^\s*\*\s?', '', t, flags=re.M).strip()
    return re.sub(r'\s+', ' ', t)


def collect():
    theme = {m.group(1): m.group(2) for m in re.finditer(
        r'public static final int (\w+)\s*=\s*(0x[0-9A-Fa-f]{8})',
        read('src/main/java/com/november/mcphone/core/client/PhoneTheme.java'))}
    rows = []

    # App 图标：SPI 名单 → 每个类的 super("path")
    # 【要剥注释】：这份名单允许写 # 开头的注释行（1.20.1-forge 那支就写着
    # 一行），不剥的话下面会拿 '#' 当类名去拼路径，当场 FileNotFoundError。
    # build.gradle 的 verifyServiceFiles 一直是剥的，这里以前漏了
    spi = [line.split('#', 1)[0].strip()
           for line in read('src/main/resources/META-INF/services/'
                            'com.november.mcphone.api.client.app.IPhoneApp').splitlines()]
    spi = [c for c in spi if c]
    for cls in spi:
        src = read('src/main/java/' + cls.replace('.', '/') + '.java')
        path = re.search(r'super\("(\w+)"\)', src).group(1)
        name = re.search(r'\n \* ([^\n]+?)(?: ——|$)', src)
        rows.append({'cat': 'App 图标', 'path': f'app/{path}.png', 'rec': '20×20',
                     'note': name.group(1).strip() if name else path,
                     'fallback': None, 'info': info(f'app/{path}.png')})

    # 天气图标：Weather.Kind
    wsrc = read('src/main/java/com/november/mcphone/feature/weather/Weather.java')
    wnote = {'clear': '晴天。太阳', 'rain': '下雨。云 + 落下的雨', 'snow': '下雪。云 + 雪花',
             'thunder': '雷雨。暗云 + 闪电', 'dry': '阴天，别处在下雨这里不下。只有云',
             'none': '下界与末地，没有天气这回事'}
    for _, suf, _ in re.findall(r'(\w+)\("(\w+)", (true|false)\)', wsrc):
        rows.append({'cat': '天气图标', 'path': f'weather/{suf}.png', 'rec': '32×32',
                     'note': wnote.get(suf, suf), 'fallback': None,
                     'info': info(f'weather/{suf}.png')})

    # 可换肤元素：PhoneSkin.Element，建议尺寸与兜底色都从 javadoc 里读
    psrc = read('src/main/java/com/november/mcphone/core/client/PhoneSkin.java')
    body = psrc[psrc.index('public enum Element'):psrc.index('/** 现在的路径')]
    for m in re.finditer(r'/\*\*(.*?)\*/\s*(\w+)\("([\w/]+)",\s*"([\w_]+)"\)', body, re.S):
        doc, _, path, legacy = m.groups()
        txt = clean(doc)
        size = re.search(r'建议\s*(\d+\s*×\s*\d+)', txt)
        fb = re.search(r'(COLOR_[A-Z_]+)', txt)
        rows.append({'cat': '可换肤元素', 'path': f'{path}.png',
                     'rec': size.group(1).replace(' ', '') if size else '任意（会拉伸）',
                     'note': txt.split('。')[0] + '。', 'legacy': f'gui/{legacy}.png',
                     'fallback': theme.get(fb.group(1)) if fb else None,
                     'info': info(path + '.png')})

    for rel, note in [('item/phone.png', '手机物品本身，物品栏与手上看到的'),
                      ('slot/empty_phone_slot.png', 'Curios 饰品栏里那个空槽的底图')]:
        i = info(rel)
        rows.append({'cat': '其他', 'path': rel,
                     'rec': f"{i['w']}×{i['h']}" if i else '—',
                     'note': note, 'fallback': None, 'info': i})
    return rows


# ================================================================
#  渲染
# ================================================================

CAT_NOTE = {
    'App 图标': '主屏格子里那一个个方块。全部 20×20，放大或缩小都会糊，照这个尺寸画。',
    '天气图标': '天气 App 里那张随天变的大图。已有的六张是脚本生成的占位图，等着被替换。',
    '可换肤元素': '这些是<b>可选</b>的。放了贴图就用贴图，没放就用右边那个兜底色画，'
                  '功能完全不受影响 —— 所以这一栏里的「待画」不是缺陷，是留给你的口子。',
    '其他': '物品与饰品槽，不在手机界面里。',
}

STYLE = """<style>
:root {
  --ground:#F4F3F7; --panel:#FFFFFF; --sunk:#ECEAF2;
  --rule:#D6D3E2; --rule-hard:#0F3460;
  --ink:#1B1A2B; --dim:#61607A; --faint:#8E8CA6;
  --gold:#B8860B; --gold-bg:#FFF4D6;
  --jade:#1F7A3D; --jade-bg:#DFF5E6;
  --check-a:#E8E6EF; --check-b:#F6F5F9;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --ground:#0E0E1A; --panel:#1A1A2E; --sunk:#141426;
    --rule:#2A2A44; --rule-hard:#0F3460;
    --ink:#DEDCE8; --dim:#9997B2; --faint:#6E6C8A;
    --gold:#FFD54F; --gold-bg:#33290A;
    --jade:#66FF88; --jade-bg:#0F2A17;
    --check-a:#15152A; --check-b:#1B1B32;
  }
}
:root[data-theme="dark"] {
  --ground:#0E0E1A; --panel:#1A1A2E; --sunk:#141426;
  --rule:#2A2A44; --rule-hard:#0F3460;
  --ink:#DEDCE8; --dim:#9997B2; --faint:#6E6C8A;
  --gold:#FFD54F; --gold-bg:#33290A;
  --jade:#66FF88; --jade-bg:#0F2A17;
  --check-a:#15152A; --check-b:#1B1B32;
}
* { box-sizing:border-box; }
body {
  margin:0; background:var(--ground); color:var(--ink);
  font:15px/1.65 system-ui,-apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
  -webkit-font-smoothing:antialiased;
}
.wrap { max-width:900px; margin:0 auto; padding:48px 24px 96px; }

/* ── 页眉 ── */
.top { border-bottom:2px solid var(--rule-hard); padding-bottom:22px; margin-bottom:8px; }
.eyebrow {
  font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace;
  font-size:11px; letter-spacing:.16em; text-transform:uppercase;
  color:var(--faint); margin:0 0 10px;
}
h1 { font-size:30px; line-height:1.2; margin:0 0 6px; text-wrap:balance; letter-spacing:-.01em; }
.sub { margin:0; color:var(--dim); max-width:62ch; }
.score { display:flex; gap:28px; margin-top:22px; flex-wrap:wrap; }
.score div { display:flex; align-items:baseline; gap:7px; }
.score b {
  font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace;
  font-size:26px; font-variant-numeric:tabular-nums; letter-spacing:-.02em;
}
.score span { font-size:12px; letter-spacing:.1em; text-transform:uppercase; color:var(--faint); }
.score .j b { color:var(--jade); }
.score .g b { color:var(--gold); }

/* ── 分组 ── */
section { margin-top:52px; }
.sec { display:flex; align-items:baseline; justify-content:space-between; gap:16px;
        border-bottom:1px solid var(--rule); padding-bottom:9px; flex-wrap:wrap; }
.sec h2 { font-size:19px; margin:0; letter-spacing:-.01em; }
.count { margin:0; font-size:12px; color:var(--faint);
  font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace; font-variant-numeric:tabular-nums; }
.count b { color:var(--ink); }
.lede { margin:14px 0 20px; color:var(--dim); font-size:14px; max-width:66ch; }
.lede b { color:var(--ink); font-weight:600; }

/* ── 每一行 ── */
.rows { display:flex; flex-direction:column; gap:10px; }
.row {
  display:grid; grid-template-columns:100px 1fr auto auto;
  gap:18px; align-items:center;
  background:var(--panel); border:1px solid var(--rule); border-radius:5px; padding:12px 16px;
}
.thumb {
  width:100px; height:100px; display:grid; place-items:center; border-radius:4px;
  background-image:
    linear-gradient(45deg,var(--check-a) 25%,transparent 25%,transparent 75%,var(--check-a) 75%),
    linear-gradient(45deg,var(--check-a) 25%,transparent 25%,transparent 75%,var(--check-a) 75%);
  background-size:12px 12px; background-position:0 0,6px 6px; background-color:var(--check-b);
}
.thumb img { image-rendering:pixelated; display:block; }
.ph { border:1px dashed var(--faint); border-radius:2px; }
.meta { min-width:0; }
.path {
  font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace;
  font-size:13.5px; color:var(--ink); word-break:break-all;
}
.note { margin:5px 0 0; font-size:13px; color:var(--dim); }
.legacy { margin-top:4px; font-size:11.5px; color:var(--faint); }
.legacy code { font-family:ui-monospace,Menlo,Consolas,monospace; }
.nums { text-align:right; font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace;
         font-variant-numeric:tabular-nums; white-space:nowrap; }
.rec { font-size:15px; }
.real { font-size:11.5px; color:var(--faint); margin-top:2px; }
.tag { font-size:11px; letter-spacing:.08em; padding:4px 9px; border-radius:3px; white-space:nowrap; }
.tag.have { color:var(--jade); background:var(--jade-bg); }
.tag.todo { color:var(--gold); background:var(--gold-bg); }

@media (max-width:680px) {
  .row { grid-template-columns:76px 1fr; grid-template-areas:"t m" "t n" "t s"; row-gap:6px; }
  .thumb { grid-area:t; width:76px; height:76px; }
  .meta { grid-area:m; } .nums { grid-area:n; text-align:left; } .stat { grid-area:s; }
}
</style>"""


def dims(r):
    if r['info']:
        return r['info']['w'], r['info']['h']
    m = re.match(r'(\d+)×(\d+)', r['rec'])
    return (int(m.group(1)), int(m.group(2))) if m else (32, 32)


def scaled(w, h):
    """按整数倍放大到 BOX 以内；本来就比 BOX 大的按比例缩，只为看个轮廓"""
    if max(w, h) <= BOX:
        s = max(1, min(8, BOX // max(w, h)))
        return w * s, h * s
    s = BOX / max(w, h)
    return round(w * s), round(h * s)


def cell(r):
    w, h = dims(r)
    dw, dh = scaled(w, h)
    if r['info']:
        img = (f'<img src="data:image/png;base64,{r["info"]["data"]}" alt="" '
               f'style="width:{dw}px;height:{dh}px">')
        real, status = f'{r["info"]["w"]}×{r["info"]["h"]}', '<span class="tag have">已有</span>'
    else:
        fb = r.get('fallback')
        # PhoneTheme 是 0xAARRGGBB，CSS 要 #RRGGBBAA
        col = ('#' + fb[4:] + fb[2:4]) if fb else 'transparent'
        img = f'<div class="ph" style="width:{dw}px;height:{dh}px;background:{col}"></div>'
        real, status = '—', '<span class="tag todo">待画</span>'

    extra = ''
    if r.get('legacy'):
        extra += f'<div class="legacy">老路径也认 <code>{html.escape(r["legacy"])}</code></div>'
    if not r['info'] and r.get('fallback'):
        extra += (f'<div class="legacy">缺图时填 <code>#{r["fallback"][4:]}</code>，'
                  f'透明度 <code>{int(r["fallback"][2:4], 16)}/255</code></div>')

    return (f'<article class="row"><div class="thumb">{img}</div>'
            f'<div class="meta"><code class="path">{html.escape(r["path"])}</code>'
            f'<p class="note">{r["note"]}</p>{extra}</div>'
            f'<div class="nums"><div class="rec">{html.escape(r["rec"])}</div>'
            f'<div class="real">现有 {real}</div></div>'
            f'<div class="stat">{status}</div></article>')


def render(rows, version):
    cats = []
    for r in rows:
        if not cats or cats[-1][0] != r['cat']:
            cats.append((r['cat'], []))
        cats[-1][1].append(r)

    secs = []
    for name, items in cats:
        n = sum(1 for i in items if i['info'])
        secs.append(f'<section><header class="sec"><h2>{name}</h2>'
                    f'<p class="count"><b>{len(items)}</b> 个 · 已有 {n} · '
                    f'待画 {len(items) - n}</p></header>'
                    f'<p class="lede">{CAT_NOTE[name]}</p>'
                    f'<div class="rows">{"".join(cell(i) for i in items)}</div></section>')

    have = sum(1 for r in rows if r['info'])
    return (f'<title>MCphone 贴图清单</title>\n{STYLE}\n'
            '<div class="wrap"><div class="top">'
            f'<p class="eyebrow">MCphone v{version} · assets/mcphone/textures/</p>'
            '<h1>贴图清单</h1>'
            '<p class="sub">这个模组认得的每一个贴图位。「待画」的那些不是缺陷 —— '
            '除了 App 图标，其余都有纯色兜底，不放图功能一样正常。'
            '已有的图里，时钟、天气那几张是脚本生成的占位图。</p>'
            f'<div class="score"><div><b>{len(rows)}</b><span>贴图位</span></div>'
            f'<div class="j"><b>{have}</b><span>已有</span></div>'
            f'<div class="g"><b>{len(rows) - have}</b><span>待画</span></div></div></div>'
            f'{"".join(secs)}</div>')


if __name__ == '__main__':
    version = re.search(r'mod_version=(\S+)', read('gradle.properties')).group(1)
    rows = collect()
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'build/texture-manifest.html')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, 'w', encoding='utf-8').write(render(rows, version))
    have = sum(1 for r in rows if r['info'])
    print(f'{len(rows)} 个贴图位：已有 {have}，待画 {len(rows) - have}')
    print(f'写出 {out}')
