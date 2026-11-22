#!/usr/bin/env bash
set -euo pipefail

adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.codename
adb shell pm list packages com.google.android.marvin.talkback
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.kachur.vpn.test/androidx.test.runner.AndroidJUnitRunner | tee instrumentation.txt
grep -Eq '^OK \(3 tests\)' instrumentation.txt

mkdir -p ui-screenshots
for shot in 01-home-dark 02-home-light 03-servers 04-diagnostics 05-ip-routes 06-backups 07-settings 08-large-text 09-icon-dark 10-icon-light 11-home-connected-preview; do
    target="ui-screenshots/$shot.png"
    adb exec-out run-as com.kachur.vpn cat "files/ui-screenshots/$shot.png" > "$target"
    test "$(od -A n -t x1 -N 8 "$target" | tr -d ' \n')" = "89504e470d0a1a0a"
done
