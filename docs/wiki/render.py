#!/usr/bin/env python3
"""把仓库里的文档渲染成 GitHub wiki 的页面。

为什么要有这一步，而不是把文档手抄一份到 wiki 上
------------------------------------------------
手抄就是两份，两份就会分叉，而分叉之后人们信的是 wiki——它更显眼。这份文档已经烂过
一次（整整一版只写了 IPhoneApp），修它的时候还专门加了个编译守门人
（docs/AddonApiExamples.java）。wiki 副本在仓库外、CI 外，守门人管不着它。

所以 wiki 是【生成物】：仓库是唯一真源，这个脚本负责翻译过去，CI 负责推。

它做三件事
----------
1. 复制 PAGES 里列的文件，改成 wiki 的页面名；
2. 每页顶上加一行"这是自动生成的，别在这儿改"，并链回源文件；
3. 把仓库里的相对链接改掉——wiki 与仓库不在同一个路径空间下，
   照抄过去的 (docs/addon-api.md) 在 wiki 上是 404。

用法：python3 docs/wiki/render.py <输出目录>
"""

import os
import posixpath
import re
import sys
from urllib.parse import urljoin

REPO = "november521/mcphone"
BRANCH = "main"
BLOB = f"https://github.com/{REPO}/blob/{BRANCH}/"

# 源文件 → wiki 页面名。加一页就在这里加一行
PAGES = {
    "docs/wiki/Home.md": "Home.md",
    "README.md": "Manual.md",
    "docs/addon-api.md": "Addon-API.md",
    "docs/wiki/_Sidebar.md": "_Sidebar.md",
}

# 侧边栏不加横幅：它每页都显示一遍，加了就是满屏的免责声明
NO_BANNER = {"_Sidebar.md"}

LINK = re.compile(r"\]\(([^)\s]+)(\s+\"[^\"]*\")?\)")


def banner(source: str) -> str:
    return (
        f"> 📄 本页由仓库自动同步，**请勿在 wiki 里直接编辑**——下一次同步会覆盖掉。\n"
        f"> 源文件：[`{source}`]({BLOB}{source}) ｜ 对应分支：`{BRANCH}`（NeoForge 1.21.1）\n\n"
    )


# 页面之间互链时写的就是 wiki 的页面名（Home、Manual、Addon-API），别把它们当路径
PAGE_NAMES = {name[:-3] for name in PAGES.values()}


def rewrite(target: str, source: str) -> str:
    """把一个链接目标改成在 wiki 上也走得通的形式"""
    if target.startswith(("http://", "https://", "#", "mailto:")):
        return target

    head, _, anchor = target.partition("#")
    anchor = "#" + anchor if anchor else ""

    # 已经是 wiki 页面名（Home.md 与 _Sidebar.md 里那些）：原样留着
    if head in PAGE_NAMES:
        return target

    # 指向另一个被同步的文件：换成它在 wiki 上的页面名
    path = posixpath.normpath(posixpath.join(posixpath.dirname(source), head))
    page = PAGES.get(path)
    if page:
        return page[:-3] + anchor

    # 其余一律指回仓库里的那个文件——wiki 上没有它，但 GitHub 上有。
    # 用 urljoin 而不是自己拼：README 里有 ../../tree/1.20.1-forge 这种【相对于 GitHub 的
    # 页面路径】的写法，自己拼会拼出 blob/main/../../tree/... 这种走不通的东西
    return urljoin(BLOB + source, target)


def render(source: str, out_dir: str) -> None:
    page = PAGES[source]
    text = open(source, encoding="utf-8").read()

    text = LINK.sub(
        lambda m: "](" + rewrite(m.group(1), source) + (m.group(2) or "") + ")", text)

    if page not in NO_BANNER:
        text = banner(source) + text

    out = os.path.join(out_dir, page)
    with open(out, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"  {source}  ->  {page}  ({len(text)} 字节)")


def main() -> int:
    if len(sys.argv) != 2:
        print("用法：python3 docs/wiki/render.py <输出目录>", file=sys.stderr)
        return 2

    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)

    missing = [s for s in PAGES if not os.path.isfile(s)]
    if missing:
        print("源文件不见了：" + "、".join(missing), file=sys.stderr)
        return 1

    print(f"渲染 {len(PAGES)} 页到 {out_dir}/")
    for source in PAGES:
        render(source, out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
