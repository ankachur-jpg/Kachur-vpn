#!/usr/bin/env bash
set -euo pipefail

adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.codename
adb shell pm list packages com.google.android.marvin.talkback
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
runner=com.kachur.vpn.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.kachur.vpn.RedesignUiTest "$runner" | tee instrumentation.txt
adb shell am instrument -w -e class com.kachur.vpn.QuickControlsUiTest "$runner" | tee quick-controls.txt
adb shell am instrument -w -e class com.kachur.vpn.TvLauncherUiTest "$runner" | tee tv-launcher.txt
grep -Eq '^OK \(3 tests\)' instrumentation.txt
grep -Eq '^OK \(1 test\)' quick-controls.txt
grep -Eq '^OK \(1 test\)' tv-launcher.txt

mkdir -p ui-screenshots
for shot in 01-home-dark 02-home-light 03-servers 04-diagnostics 05-ip-routes 06-backups 07-settings 08-large-text 09-icon-dark 10-icon-light 11-home-connected-preview 12-tv-banner 13-tv-icon; do
    target="ui-screenshots/$shot.png"
    adb exec-out run-as com.kachur.vpn cat "files/ui-screenshots/$shot.png" > "$target"
    test "$(od -A n -t x1 -N 8 "$target" | tr -d ' \n')" = "89504e470d0a1a0a"
done

adb shell am force-stop com.kachur.vpn
adb shell am start -W -n com.kachur.vpn/.MainActivity
for attempt in {1..10}; do
    keys=$(adb shell cmd notification list)
    if [[ "$keys" == *'|com.kachur.vpn|2|'* ]]; then break; fi
    sleep 1
done
[[ "$keys" == *'|com.kachur.vpn|2|'* ]]
[[ "$keys" != *'|com.kachur.vpn|1|'* ]]
echo 'Cold restart restored the ordinary control with no foreground VPN notification'
