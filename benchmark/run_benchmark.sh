#!/usr/bin/env bash
# 生产式性能测量: 对"已安装的 release app"做冷启动/滚动帧率基线。
#
# 与 connectedCheck 的区别: 不重装、不卸载被测应用。测的是真实使用状态
# (登录态、Coil 磁盘缓存、ART profile 都在), debug 包与 release 包冷启动
# 差 2~7 倍(R8 + 非 debuggable), 只有 release 数字才有参考价值。
#
# 用法:
#   benchmark/run_benchmark.sh [startup|scroll|all]
#
# 流程:
#   1. 构建 release 并安装(若系统无 keystore.properties, 用临时 key 签,
#      仅用于本机测试, 不影响正式签名配置)
#   2. 打开 app 扫码登录一次(登录态保存在设备, 不再被卸载)
#   3. 正常使用几分钟让封面缓存与 ART profile 热起来
#   4. 跑本脚本测量。迭代间 app 数据不动。
set -euo pipefail

cd "$(dirname "$0")/.."

ADB="${ADB_BINARY:-adb}"
BENCH_APK="benchmark/build/outputs/apk/debug/benchmark-debug.apk"
APP_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
PACKAGE="com.takahashirinta.ncrust"
BENCH_PKG="$PACKAGE.benchmark"
TARGET="${1:-all}"
ADB_BIN="$(command -v "$ADB")"

# 定位 SDK build-tools（跨平台）：优先 ANDROID_HOME/SDK_ROOT, 其次 local.properties 的 sdk.dir
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK_DIR" ] && [ -f local.properties ]; then
  SDK_DIR="$(grep -E '^sdk\.dir=' local.properties | head -1 | cut -d= -f2-)"
fi
SDK_BUILD_TOOLS=""
if [ -n "$SDK_DIR" ] && [ -d "$SDK_DIR/build-tools" ]; then
  SDK_BUILD_TOOLS="$(ls -d "$SDK_DIR"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
fi
APKSIGNER="apksigner"
if [ -n "$SDK_BUILD_TOOLS" ]; then
  if [ -x "${SDK_BUILD_TOOLS}apksigner" ]; then
    APKSIGNER="${SDK_BUILD_TOOLS}apksigner"
  elif [ -f "${SDK_BUILD_TOOLS}apksigner.bat" ]; then
    APKSIGNER="${SDK_BUILD_TOOLS}apksigner.bat"
  fi
fi

# ---------- release 构建与安装 ----------
install_release() {
  echo "== 构建 release =="
  ./gradlew :app:assembleRelease --console=plain -q

  local signed_apk="$APP_APK"
  if [ -f keystore.properties ]; then
    echo "== 项目有正式签名, assembleRelease 已产出签名包 =="
    signed_apk="app/build/outputs/apk/release/app-release.apk"
  else
    echo "== 无 keystore.properties, 用临时 key 签名(仅本机测量用) =="
    local ks="$PWD/build/benchmark-sign.jks"
    [ -f "$ks" ] || keytool -genkeypair -keystore "$ks" -alias bench -keyalg RSA \
      -keysize 2048 -validity 10000 -storepass bench123 -keypass bench123 \
      -dname "CN=NcrustBenchmark" >/dev/null 2>&1
    "$APKSIGNER" sign --ks "$ks" --ks-pass pass:bench123 \
      --key-pass pass:bench123 --out /tmp/app-release-bench.apk "$APP_APK"
    signed_apk=/tmp/app-release-bench.apk
  fi

  echo "== 安装 release =="
  if ! $ADB install "$signed_apk" 2>/tmp/install_err.txt; then
    if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" /tmp/install_err.txt; then
      echo "!! 已有签名不同的旧版, 卸载后重装(登录态会清, 需重新扫码)"
      $ADB uninstall "$PACKAGE" >/dev/null
      $ADB install "$signed_apk"
    else
      cat /tmp/install_err.txt; exit 1
    fi
  fi
  echo "!! 新装/重装后请在手机上打开 app 扫码登录一次, 并用几分钟让缓存热起来"
}

# ---------- 测量 ----------
ensure_bench_apk() {
  echo "== 构建并安装 benchmark APK =="
  ./gradlew :benchmark:assembleDebug --console=plain -q
  $ADB install -r -t "$BENCH_APK" >/dev/null
}

run() {
  local cls="$1"
  echo "== $cls =="
  # self-instrumenting 模块的 instrumentation 组件没有 .test 后缀(target 是自己)
  $ADB shell am instrument -w -e class "$cls" \
    "$BENCH_PKG/androidx.test.runner.AndroidJUnitRunner"
}

echo "== 检查被测应用 =="
if ! $ADB shell pm list packages | grep -q "$PACKAGE"; then
  install_release
fi
if $ADB shell dumpsys package "$PACKAGE" | grep -q "debuggable=true"; then
  echo "!! 警告: 设备上是 debuggable 版本, 数字偏慢。建议先跑 install_release 流程。"
fi

echo "== 系统动画 =="
# 保存原始值, 退出时(含 Ctrl-C / 出错)还原, 避免脚本异常结束把设备动画永久关掉。
ORIG_WINDOW_ANIM="$($ADB shell settings get global window_animation_scale | tr -d '\r')"
ORIG_TRANSITION_ANIM="$($ADB shell settings get global transition_animation_scale | tr -d '\r')"
ORIG_ANIMATOR_ANIM="$($ADB shell settings get global animator_duration_scale | tr -d '\r')"
restore_animations() {
  [ "$ORIG_WINDOW_ANIM" = "null" ] || $ADB shell settings put global window_animation_scale "$ORIG_WINDOW_ANIM" >/dev/null 2>&1 || true
  [ "$ORIG_TRANSITION_ANIM" = "null" ] || $ADB shell settings put global transition_animation_scale "$ORIG_TRANSITION_ANIM" >/dev/null 2>&1 || true
  [ "$ORIG_ANIMATOR_ANIM" = "null" ] || $ADB shell settings put global animator_duration_scale "$ORIG_ANIMATOR_ANIM" >/dev/null 2>&1 || true
}
trap restore_animations EXIT

# 只有 startup 需要关系统动画(避免窗口转场动画混入启动帧)。
# scroll / expand 必须保持开启: Compose 动画时长受 animator_duration_scale 控制
# (MotionDurationScale), 关掉会让展开动画瞬间完成、完全测不到真实帧。
disable_animations() {
  echo "== 关闭系统动画(仅 startup; 退出时自动还原) =="
  $ADB shell settings put global window_animation_scale 0
  $ADB shell settings put global transition_animation_scale 0
  $ADB shell settings put global animator_duration_scale 0
}

ensure_bench_apk

case "$TARGET" in
  startup) disable_animations; run com.takahashirinta.ncrust.benchmark.StartupBenchmark ;;
  scroll)  run com.takahashirinta.ncrust.benchmark.HomeScrollBenchmark ;;
  expand)  run com.takahashirinta.ncrust.benchmark.ExpandPlayerBenchmark ;;
  all)     disable_animations; run com.takahashirinta.ncrust.benchmark.StartupBenchmark
           restore_animations
           run com.takahashirinta.ncrust.benchmark.HomeScrollBenchmark
           run com.takahashirinta.ncrust.benchmark.ExpandPlayerBenchmark ;;
  *) echo "用法: $0 [startup|scroll|expand|all]"; exit 2 ;;
esac

echo ""
echo "== 完成。perfetto trace 与结果 JSON 在设备: =="
echo "  /storage/emulated/0/Android/media/$BENCH_PKG/"
echo "把需要的 trace pull 回: adb pull /storage/emulated/0/Android/media/$BENCH_PKG/ benchmark/output/"