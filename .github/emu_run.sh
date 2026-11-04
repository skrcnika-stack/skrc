#!/bin/bash
# 跑一次測試；如果模擬器系統本身當掉（App 完全收不到 GPS），重開模擬器再跑一次
API=$1; FONT=$2
bash .github/emu_test.sh $API $FONT
n=$(grep -c "SpeedCamSay: FG" emu_out/app_log.txt 2>/dev/null); n=${n:-0}
if [ "$n" -lt 10 ]; then
  echo "只有 $n 句前景語音，判定模擬器異常，重開再測一次"
  mv emu_out emu_try1
  adb reboot; sleep 10; adb wait-for-device
  for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 5; done
  sleep 20
  adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0; adb shell settings put global animator_duration_scale 0
  bash .github/emu_test.sh $API $FONT
  mv emu_try1 emu_out/try1
  echo "第一次只有 $n 句，已重測" >> emu_out/steps.txt
fi
