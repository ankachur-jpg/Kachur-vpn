#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

if [[ "${GITHUB_ACTIONS:-}" != true || "$(adb get-serialno)" != emulator-* ||
    "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
    printf '%s\n' "The migration test uninstalls the app on a disposable CI emulator." >&2
    exit 1
fi

work=$(mktemp -d "$RUNNER_TEMP/kachur-release-test.XXXXXX")
remote=/data/local/tmp/kachur-ci-migration.kvb
cleanup() {
    adb shell rm -f "$remote" >/dev/null 2>&1 || true
    rm -rf -- "$work"
}
trap cleanup EXIT

tools="$ANDROID_HOME/build-tools/36.0.0"
unsigned=app/build/outputs/apk/release/app-release-unsigned.apk
metadata=$("$tools/aapt2" dump badging "$unsigned")
version_code=$(sed -n "s/^package: name='com.kachur.vpn' versionCode='\([0-9][0-9]*\)'.*/\1/p" <<< "$metadata")
version_name=$(sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p" <<< "$metadata")
[[ "$version_code" =~ ^[1-9][0-9]*$ && -n "$version_name" ]]
next_version_code=$((version_code + 1))

# A new short-lived signer for this run. All inputs and signed test packages stay in RUNNER_TEMP.
password_file="$work/test-password"
keystore="$work/ci-signer.p12"
openssl rand -hex 24 > "$password_file"
keytool -genkeypair -storetype PKCS12 -keystore "$keystore" -storepass:file "$password_file" \
    -alias ci-test -keyalg RSA -keysize 2048 -validity 2 -dname "CN=Kachur VPN disposable CI signer" \
    -noprompt >/dev/null
fingerprint=$(keytool -exportcert -storetype PKCS12 -keystore "$keystore" \
    -storepass:file "$password_file" -alias ci-test | sha256sum | cut -d ' ' -f1)

first="$work/release.apk"
bash scripts/build-personal-release.sh "$keystore" ci-test "$fingerprint" "$first" < "$password_file"

# Build an actual monotonic upgrade in a temporary source tree.
upgrade_source="$work/upgrade-source"
mkdir -p "$upgrade_source"
git archive HEAD | tar -x -C "$upgrade_source"
cp -a third_party/amneziawg/build "$upgrade_source/third_party/amneziawg/"
sed -i -E "s/^([[:space:]]*versionCode = )$version_code$/\1$next_version_code/" "$upgrade_source/app/build.gradle.kts"
grep -Eq "^[[:space:]]*versionCode = $next_version_code$" "$upgrade_source/app/build.gradle.kts"
upgrade="$work/release-upgrade.apk"
bash "$upgrade_source/scripts/build-personal-release.sh" "$keystore" ci-test "$fingerprint" "$upgrade" < "$password_file"

debug=app/build/outputs/apk/debug/app-debug.apk
test_debug=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
test_release="$work/release-instrumentation.apk"
"$tools/apksigner" sign --ks "$keystore" --ks-type PKCS12 --ks-key-alias ci-test \
    --ks-pass "file:$password_file" --v4-signing-enabled false \
    --out "$test_release" "$test_debug"
"$tools/apksigner" verify "$test_release"

instrument() {
    local method=$1 code=$2 results="$work/$1.txt"
    adb shell am instrument -w -e class "com.kachur.vpn.ReleaseMigrationTest#$method" \
        -e expectedVersionCode "$code" -e expectedVersionName "$version_name" \
        com.kachur.vpn.test/androidx.test.runner.AndroidJUnitRunner | tee "$results"
    grep -Eq '^OK \(1 test\)' "$results"
}

adb uninstall com.kachur.vpn.test
adb uninstall com.kachur.vpn
adb install "$debug"
adb install "$test_debug"
instrument exportsEncryptedBackupFromDebug "$version_code"
adb exec-out run-as com.kachur.vpn cat cache/ci-migration.kvb > "$work/migration.kvb"
test -s "$work/migration.kvb"

if rejected=$(adb install -r "$first" 2>&1); then
    printf '%s\n' 'Android unexpectedly accepted a release over a differently signed debug installation.' >&2
    exit 1
fi
grep -Fq 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' <<< "$rejected"
echo 'Android rejected the different debug and release signatures'

adb uninstall com.kachur.vpn.test
adb uninstall com.kachur.vpn
adb push "$work/migration.kvb" "$remote"
adb install "$first"
adb install "$test_release"
instrument restoresBackupIntoReinstalledRelease "$version_code"

adb shell rm -f "$remote"
adb install -r "$upgrade"
instrument higherVersionKeepsRestoredData "$next_version_code"
echo 'Encrypted backup restored after uninstall and data survived a signed version upgrade'
