#!/usr/bin/env bash
#
# 算出下一个版本号。用法：next-version.sh <当前版本> <bump 类型> <发布渠道> [自定义版本号]
# 成功时把新版本号打到标准输出，别的话一律打到标准错误。
#
# ============================================================
#  为什么版本号必须认发布渠道
# ============================================================
#
# 原先渠道（release/beta/alpha）【只管 GitHub Release 打不打预发布标记】，不进版本号。
# 于是站在 1.10.0 上选 patch + beta，算出来的是 1.10.1 —— 与将来那个正式版
# 【一模一样的号】。那个号当场就烧掉了：正式版再发就是重复的号，tag 也撞。
# 想避开只能每次手填 custom，而手填正是这份 workflow 存在的理由要消掉的东西。
#
# 现在渠道决定后缀：
#
#   alpha    x.y.z-alpha.N
#   beta     x.y.z-beta.N
#   release  x.y.z
#
# ============================================================
#  基准号什么时候动
# ============================================================
#
# 【当前带预发布后缀时，基准号冻结】。站在 1.10.1-beta.2 上，无论选 patch 还是 minor，
# 基准都还是 1.10.1 —— 那条线是在切第一个预发布时就定下的，中途改主意是罕见动作，
# 要改用 custom。不冻结的话「alpha.1 → alpha.2」根本无从谈起：每按一次都会跳号。
#
# 【当前是正式版时，按 bump 类型升】。1.10.0 + patch + alpha ＝ 1.10.1-alpha.1。
#
# 于是一条预发布走到正式版的完整路径是：
#
#   1.10.0  --patch+alpha-->  1.10.1-alpha.1  --alpha-->  1.10.1-alpha.2
#           --beta-->  1.10.1-beta.1  --beta-->  1.10.1-beta.2
#           --release-->  1.10.1      ← 正式版，号没被烧掉
#
# ============================================================
#  序号什么时候归一
# ============================================================
#
# 同一个渠道接着发就 +1；换了渠道从 1 起。alpha.2 之后选 beta 是 beta.1，不是 beta.3。
#
# 【不许倒着走】：beta 之后选 alpha 会被拦。1.10.1-alpha.1 在 Maven 版本序里【小于】
# 1.10.1-beta.2，发出去在玩家那边是降级 —— 装着 beta.2 的人根本看不到它，
# 而两个平台上会多出一个排在旧版下面的包。真要重开一条 alpha 线，换基准号或用 custom。
set -euo pipefail

CURRENT="${1:?用法：next-version.sh <当前版本> <bump> <渠道> [自定义]}"
BUMP="${2:?}"
CHANNEL="${3:?}"
CUSTOM="${4:-}"

die() { printf '::error::%s\n' "$1" >&2; exit 1; }

BASE="${CURRENT%%-*}"
SUFFIX="${CURRENT#"$BASE"}"
printf '%s' "$BASE" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' \
  || die "当前版本 $CURRENT 不是 x.y.z 也不是 x.y.z-后缀，无法在它基础上升"

case "$CHANNEL" in
  release) NEWRANK=3 ;;
  beta)    NEWRANK=2 ;;
  alpha)   NEWRANK=1 ;;
  *) die "认不出的发布渠道：$CHANNEL" ;;
esac

# 当前后缀里的渠道与序号。认不出形状时当作「没有渠道」，序号从 1 起
CUR_CH=""; CUR_N=0; CURRANK=3
if [ -n "$SUFFIX" ]; then
  if printf '%s' "$SUFFIX" | grep -qE '^-(alpha|beta)\.[0-9]+$'; then
    CUR_CH="${SUFFIX#-}"; CUR_CH="${CUR_CH%%.*}"
    CUR_N="${SUFFIX##*.}"
    case "$CUR_CH" in beta) CURRANK=2 ;; alpha) CURRANK=1 ;; esac
  else
    printf '::notice::当前后缀 %s 不是 -alpha.N / -beta.N 的形状，序号从 1 重新起\n' "$SUFFIX" >&2
    CURRANK=1
  fi
fi

if [ "$BUMP" = "custom" ]; then
  [ -n "$CUSTOM" ] || die "选了 custom 就必须填 custom_version"
  # 后缀只收 [0-9A-Za-z.-]：tag 名与 jar 文件名都由它拼出来
  printf '%s' "$CUSTOM" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$' \
    || die "custom_version=$CUSTOM 不是 x.y.z 或 x.y.z-后缀（如 1.10.1-beta.1）。tag 名与 jar 文件名都由它拼出来，不接受别的形状"
  printf '%s\n' "$CUSTOM"
  exit 0
fi

if [ "$BUMP" = "promote" ]; then
  [ -n "$SUFFIX" ] || die "当前是 $CURRENT，本来就没有预发布后缀，没什么可转正的。要升号请选 patch / minor / major"
  [ "$CHANNEL" = "release" ] || die "promote 的意思就是转成正式版，发布渠道却选了 $CHANNEL。二选一：渠道改成 release，或者 bump 改成 patch/minor/major"
fi

if [ -n "$SUFFIX" ]; then
  # 基准号冻结，理由见文件头
  NEWBASE="$BASE"
  if [ "$BUMP" != "promote" ]; then
    printf '::notice::当前 %s 带预发布后缀，基准号冻结在 %s，忽略 bump=%s。要换基准号请用 custom\n' \
      "$CURRENT" "$BASE" "$BUMP" >&2
  fi
  [ "$NEWRANK" -ge "$CURRANK" ] \
    || die "当前是 $CURRENT，渠道却选了 $CHANNEL —— 那个号在 Maven 版本序里【小于】现在这个，发出去是降级：装着 $CURRENT 的人看不到它。要重开一条线请换基准号（custom）"
else
  [ "$BUMP" != "promote" ] || die "内部错误：promote 走到了没有后缀的分支"
  IFS=. read -r MA MI PA <<< "$BASE"
  case "$BUMP" in
    major) MA=$((MA + 1)); MI=0; PA=0 ;;
    minor) MI=$((MI + 1)); PA=0 ;;
    patch) PA=$((PA + 1)) ;;
    *) die "认不出的 bump 类型：$BUMP" ;;
  esac
  NEWBASE="$MA.$MI.$PA"
fi

if [ "$CHANNEL" = "release" ]; then
  printf '%s\n' "$NEWBASE"
else
  if [ "$CHANNEL" = "$CUR_CH" ]; then N=$((CUR_N + 1)); else N=1; fi
  printf '%s-%s.%s\n' "$NEWBASE" "$CHANNEL" "$N"
fi
