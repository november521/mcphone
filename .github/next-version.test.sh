#!/usr/bin/env bash
#
# next-version.sh 的断言测试。CI 的 guard-version 每次推送都跑一遍。
#
# 【为什么这个脚本值得有测试】：它算错了不会报错，只会安静地产出一个坏版本号，
# 而那个号会被写进 gradle.properties、烧进 jar、打成 tag、发到两个平台 ——
# 发现的时候已经发出去了。而它又是纯函数（进去三个字符串，出来一个），
# 测起来只要一张表。
set -uo pipefail
cd "$(dirname "$0")"
S=./next-version.sh
PASS=0; FAIL=0

ok() {  # ok <期望> <当前> <bump> <渠道> [自定义]
  local want="$1"; shift
  local got; got=$(bash "$S" "$@" 2>/dev/null)
  if [ "$got" = "$want" ]; then PASS=$((PASS+1))
  else FAIL=$((FAIL+1)); printf '  ✗ %s %s %s → 得到 %s，应为 %s\n' "$1" "$2" "$3" "${got:-（失败）}" "$want"; fi
}
no() {  # no <说明> <当前> <bump> <渠道> [自定义]
  local why="$1"; shift
  if bash "$S" "$@" >/dev/null 2>&1
  then FAIL=$((FAIL+1)); printf '  ✗ 该拦没拦：%s（%s %s %s）\n' "$why" "$1" "$2" "$3"
  else PASS=$((PASS+1)); fi
}

# 预发布走完再转正：正式版的号不被烧掉
ok 1.10.1-alpha.1 1.10.0         patch alpha
ok 1.10.1-alpha.2 1.10.1-alpha.1 patch alpha
ok 1.10.1-beta.1  1.10.1-alpha.2 patch beta
ok 1.10.1-beta.2  1.10.1-beta.1  patch beta
ok 1.10.1         1.10.1-beta.2  patch release

# 不走预发布，直接发正式版
ok 1.10.1 1.10.0 patch   release
ok 1.11.0 1.10.0 minor   release
ok 2.0.0  1.10.0 major   release

# 渠道进版本号，基准号照 bump 走（当前没有后缀时）
ok 1.11.0-beta.1  1.10.0 minor beta
ok 2.0.0-alpha.1  1.10.0 major alpha

# 当前带后缀时基准号冻结：bump 选什么都不动基准
ok 1.10.1-beta.3 1.10.1-beta.2 patch beta
ok 1.10.1-beta.3 1.10.1-beta.2 minor beta
ok 1.10.1-beta.3 1.10.1-beta.2 major beta

# promote 仍然可用，等同于渠道选 release
ok 1.10.1 1.10.1-beta.2  promote release
ok 1.10.1 1.10.1-alpha.1 promote release

# 进位
ok 1.9.10-alpha.1 1.9.9  patch alpha
ok 1.10.1-beta.10 1.10.1-beta.9 patch beta

# custom 原样放行（含后缀）
ok 1.2.3      1.10.0 custom release 1.2.3
ok 1.2.3-rc.1 1.10.0 custom beta    1.2.3-rc.1

# 该拦的
no 'beta 之后回 alpha 是降级'      1.10.1-beta.2 patch alpha
no '没有后缀却要转正'              1.10.0        promote release
no 'promote 却选了 beta 渠道'      1.10.1-beta.1 promote beta
no 'custom 没填号'                 1.10.0        custom  release
no 'custom 填了不合法的号'         1.10.0        custom  release  'v1.2.3'
no '当前版本号形状不对'            1.10          patch   release
no '认不出的渠道'                  1.10.0        patch   nightly

if [ "$FAIL" != "0" ]; then
  printf '::error::next-version 断言 %d 条没过（共 %d 条）\n' "$FAIL" "$((PASS+FAIL))"
  exit 1
fi
printf 'next-version 全部通过：%d 条断言\n' "$PASS"
