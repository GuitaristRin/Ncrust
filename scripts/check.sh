#!/usr/bin/env bash
#
# check.sh - 按目录统计 Kotlin 源文件行数与占比
# 用法：在仓库任意位置执行 bash scripts/check.sh

set -euo pipefail

BOLD="\033[1m"
GREEN="\033[32m"
YELLOW="\033[33m"
RESET="\033[0m"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_DIR="$ROOT/app/src/main/java/com/takahashirinta/ncrust"
cd "$BASE_DIR" || exit 1

echo ""
echo -e "${BOLD}📊 Ncrust 项目代码行数统计 (纯 Kotlin)${RESET}"
echo "==========================================="

# 一次递归枚举统一模块、总行数、文件数与 Top 10 的统计范围。
file_rows=()
kt_total=0
file_count=0
while IFS= read -r -d '' f; do
    f=${f#./}
    lines=$(wc -l < "$f")
    file_rows+=("$lines"$'\t'"$f")
    kt_total=$((kt_total + lines))
    file_count=$((file_count + 1))
done < <(find . -type f -name '*.kt' -print0)

module_rows=""
if [ "$file_count" -gt 0 ]; then
    # 按一级包分组，ui 按二级包；新增包自动出现，不维护目录白名单。
    module_rows=$(printf '%s\n' "${file_rows[@]}" | awk -F '\t' '
        {
            depth = split($2, parts, "/")
            if (depth == 1) module = "(根目录)"
            else if (parts[1] == "ui") module = depth > 2 ? "ui/" parts[2] : "ui/root"
            else module = parts[1]
            totals[module] += $1
        }
        END { for (module in totals) printf "%s\t%d\n", module, totals[module] }
    ' | sort)
fi

# ─── 输出 ───
echo ""
echo -e "${GREEN}■■■ 按包/目录统计${RESET}"
if [ "$file_count" -gt 0 ]; then
    while IFS=$'\t' read -r module lines; do
        printf "  %-25s %s\n" "$module" "$lines"
    done <<< "$module_rows"
fi
printf "  %-25s ${YELLOW}%s${RESET}\n" "合计" "$kt_total"

echo ""
echo "==========================================="
printf "${BOLD}  总计: ${YELLOW}%s${RESET} 行  /  %d 个文件${RESET}\n" "$kt_total" "$file_count"
echo "==========================================="

# ─── 占比 ───
if [ "$kt_total" -gt 0 ]; then
    echo ""
    echo -e "${BOLD}各模块占比${RESET}"
    while IFS=$'\t' read -r module lines; do
        pct=$(awk "BEGIN {printf \"%.1f\", $lines*100/$kt_total}")
        printf "  %-25s %s%%\n" "$module" "$pct"
    done <<< "$module_rows"
fi

# ─── Top 10 文件 ───
echo ""
echo -e "${BOLD}📄 最大文件 Top 10${RESET}"
if [ "$file_count" -gt 0 ]; then
    # sed 读取完整排序结果，避免 head 提前关闭管道被 pipefail 误判为失败。
    printf '%s\n' "${file_rows[@]}" | sort -nr | sed -n '1,10p' | while IFS=$'\t' read -r lines path; do
        printf "  %5d  %s\n" "$lines" "$path"
    done
fi

echo ""
