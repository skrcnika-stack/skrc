#!/bin/bash
# 在 GitHub 的 Android 模擬器裡：安裝、開啟、餵假 GPS 開過測速照相、切到背景，全程截圖＋記錄語音
API=$1
FONT=${2:-1.0}
OUT=emu_out; mkdir -p $OUT
PKG=tw.speedcam.app
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
log(){ echo "[$(date +%T)] $*" | tee -a $OUT/steps.txt; }
shot(){ adb exec-out screencap -p > "$OUT/$1.png"; log "截圖 $1"; }
# 舊版模擬器（Android 11 以下）的 geo fix 速度單位是 公尺/秒，新版是節
feed(){ while read lo la kn tag; do V=$kn; [ "$API" -le 30 ] && V=$(awk "BEGIN{print $kn*0.5144}"); adb emu geo fix $lo $la 50 10 $V >/dev/null 2>&1; [ "$tag" != "-" ] && shot "$tag"; sleep 1; done; }
log "API $API"; adb shell getprop ro.build.version.release | tee -a $OUT/steps.txt
adb shell wm size | tee -a $OUT/steps.txt; adb shell wm density | tee -a $OUT/steps.txt
adb install -r -g "$APK" 2>&1 | tee -a $OUT/steps.txt
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell cmd location set-location-enabled true 2>/dev/null
adb shell settings put secure location_mode 3 2>/dev/null
adb shell settings put system font_scale $FONT
log "字型大小 $FONT"
adb logcat -c
python3 .github/route.py W 3 | feed
log "開啟 App"
adb shell am start -n $PKG/.MainActivity
sleep 0.3; shot 00_open_0.3s
sleep 0.4; shot 00_open_0.7s
sleep 1.3; shot 00_open_2s
python3 .github/route.py W 5 | feed
shot 01_ready
log "A：時速 50 開向測速照相（前景）"
python3 .github/route.py A | feed
python3 .github/route.py W 24 | feed
log "B：時速 80 超速開向測速照相（前景）"
python3 .github/route.py B | feed
python3 .github/route.py W 24 | feed
log "O：對向（往北開，那支只拍往南）時速 80，前景不該提醒"
adb shell log -t SpeedCamMark "O-start"
python3 .github/route.py O | feed
adb shell log -t SpeedCamMark "O-end"
python3 .github/route.py W 24 | feed
log "C：按 Home 到背景，時速 80 開向測速照相"
adb shell input keyevent KEYCODE_HOME
python3 .github/route.py W 5 | feed
shot C_bg_home
python3 .github/route.py C | feed
python3 .github/route.py W 24 | feed
log "P：對向時速 80，背景不該提醒"
adb shell log -t SpeedCamMark "P-start"
python3 .github/route.py P | feed
adb shell log -t SpeedCamMark "P-end"
log "回到 App"
adb shell am start -n $PKG/.MainActivity
python3 .github/route.py W 4 | feed
shot D_back
log "E：關掉手機定位"
adb shell cmd location set-location-enabled false 2>/dev/null; adb shell settings put secure location_mode 0; adb shell settings put secure location_providers_allowed -gps,-network 2>/dev/null
sleep 7; shot E_loc_off
adb shell cmd location set-location-enabled true 2>/dev/null; adb shell settings put secure location_mode 3; adb shell settings put secure location_providers_allowed +gps,+network 2>/dev/null
python3 .github/route.py W 7 | feed
shot E_loc_on
adb logcat -d > $OUT/logcat.txt
grep -E "SpeedCam|AndroidRuntime|FATAL" $OUT/logcat.txt > $OUT/app_log.txt
adb shell dumpsys activity services $PKG > $OUT/services.txt 2>&1
log "完成"
exit 0
