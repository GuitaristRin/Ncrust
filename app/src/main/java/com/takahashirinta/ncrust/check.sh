#!/usr/bin/env bash
#
# check.sh - 按目录统计 Kotlin 源文件行数与占比
# 用法：在 ncrust 源码根目录执行 ./check.sh

set -euo pipefail

BOLD="\033[1m"
GREEN="\033[32m"
YELLOW="\033[33m"
RESET="\033[0m"

BASE_DIR="/home/rain/AndroidStudioProjects/Ncrust/app/src/main/java/com/takahashirinta/ncrust"
cd "$BASE_DIR" || exit 1

echo ""
echo -e "${BOLD}📊 Ncrust 项目代码行数统计 (纯 Kotlin)${RESET}"
echo "==========================================="

# ─── 分类累加 ───
auth=0
crypto=0
library=0
lyric=0
network=0
player=0
ui_components=0
ui_navigation=0
ui_player=0
ui_screen=0
ui_theme=0
ui_viewmodel=0
ui_root=0
main=0

# 使用 glob 精确匹配，避免路径前缀冲突
for f in \
    auth/*.kt \
    crypto/*.kt \
    library/*.kt \
    lyric/*.kt \
    network/*.kt network/crypto/*.kt network/model/*.kt \
    player/*.kt \
    ui/components/*.kt \
    ui/navigation/*.kt \
    ui/player/*.kt \
    ui/screen/*.kt \
    ui/theme/*.kt \
    ui/viewmodel/*.kt \
    ui/*.kt \
    *.kt
do
    [ -f "$f" ] || continue
    lines=$(wc -l < "$f")
    case "$f" in
        auth/*)             auth=$((auth + lines)) ;;
        crypto/*)           crypto=$((crypto + lines)) ;;
        library/*)          library=$((library + lines)) ;;
        lyric/*)            lyric=$((lyric + lines)) ;;
        network/crypto/*)   network=$((network + lines)) ;;
        network/model/*)    network=$((network + lines)) ;;
        network/*)          network=$((network + lines)) ;;
        player/*)           player=$((player + lines)) ;;
        ui/components/*)    ui_components=$((ui_components + lines)) ;;
        ui/navigation/*)    ui_navigation=$((ui_navigation + lines)) ;;
        ui/player/PlayerCardOverlay.kt) ui_player=$((ui_player + lines)) ;;
        ui/player/PlayerCard.kt)        ui_player=$((ui_player + lines)) ;;
        ui/player/FullPlayerControls.kt) ui_player=$((ui_player + lines)) ;;
        ui/player/LyricsView.kt)        ui_player=$((ui_player + lines)) ;;
        ui/player/QueueView.kt)         ui_player=$((ui_player + lines)) ;;
        ui/player/SlimProgressBar.kt)   ui_player=$((ui_player + lines)) ;;
        ui/player/*)        ui_player=$((ui_player + lines)) ;;
        ui/screen/*)        ui_screen=$((ui_screen + lines)) ;;
        ui/theme/*)         ui_theme=$((ui_theme + lines)) ;;
        ui/viewmodel/*)     ui_viewmodel=$((ui_viewmodel + lines)) ;;
        ui/*)               ui_root=$((ui_root + lines)) ;;
        *.kt)               main=$((main + lines)) ;;
    esac
done

kt_total=$((auth + crypto + library + lyric + network + player +
            ui_components + ui_navigation + ui_player + ui_screen +
            ui_theme + ui_viewmodel + ui_root + main))

# ─── 输出 ───
echo ""
echo -e "${GREEN}■■■ 按包/目录统计${RESET}"
printf "  %-25s %s\n" "auth"                    "${auth}"
printf "  %-25s %s\n" "crypto"                  "${crypto}"
printf "  %-25s %s\n" "library"                 "${library}"
printf "  %-25s %s\n" "lyric"                   "${lyric}"
printf "  %-25s %s\n" "network"                 "${network}"
printf "  %-25s %s\n" "player"                  "${player}"
printf "  %-25s %s\n" "ui/components"           "${ui_components}"
printf "  %-25s %s\n" "ui/navigation"           "${ui_navigation}"
printf "  %-25s %s\n" "ui/player (播放卡片)"    "${ui_player}"
printf "  %-25s %s\n" "ui/screen"               "${ui_screen}"
printf "  %-25s %s\n" "ui/theme"                "${ui_theme}"
printf "  %-25s %s\n" "ui/viewmodel"            "${ui_viewmodel}"
printf "  %-25s %s\n" "ui/ResponsiveContent"    "${ui_root}"
printf "  %-25s %s\n" "MainActivity.kt"         "${main}"
printf "  %-25s ${YELLOW}%s${RESET}\n" "合计"   "${kt_total}"

# 文件数
file_count=$(find . -name '*.kt' -not -name 'check.sh' | wc -l)
echo ""
echo "==========================================="
printf "${BOLD}  总计: ${YELLOW}%s${RESET} 行  /  %d 个文件${RESET}\n" "${kt_total}" "${file_count}"
echo "==========================================="

# ─── 占比 ───
if [ "$kt_total" -gt 0 ]; then
    echo ""
    echo -e "${BOLD}各模块占比${RESET}"
    for mod in auth crypto library lyric network player ui_components ui_navigation ui_player ui_screen ui_theme ui_viewmodel ui_root main; do
        val=${!mod}
        name=$mod
        case "$mod" in
            ui_components) name="ui/components" ;;
            ui_navigation) name="ui/navigation" ;;
            ui_player)     name="ui/player" ;;
            ui_screen)     name="ui/screen" ;;
            ui_theme)      name="ui/theme" ;;
            ui_viewmodel)  name="ui/viewmodel" ;;
            ui_root)       name="ui/root" ;;
            main)          name="MainActivity" ;;
        esac
        pct=$(awk "BEGIN {printf \"%.1f\", ${val}*100/${kt_total}}")
        printf "  %-25s %s%%\n" "$name" "$pct"
    done
fi

# ─── Top 10 文件 ───
echo ""
echo -e "${BOLD}📄 最大文件 Top 10${RESET}"
find . -name '*.kt' -exec wc -l {} \; | sort -nr | head -10 | while read -r lines path; do
    printf "  %5d  %s\n" "$lines" "${path#./}"
done

echo ""
