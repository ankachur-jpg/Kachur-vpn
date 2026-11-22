#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

if (( $# < 3 || $# > 4 )); then
    fail "Использование: bash scripts/build-personal-release.sh KEYSTORE ALIAS CERT_SHA256 [OUTPUT.apk]"
fi

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
keystore=$(realpath -e -- "$1") || fail "Файл хранилища ключей недоступен."
[[ -f "$keystore" && -r "$keystore" ]] || fail "Файл хранилища ключей недоступен."
case "$keystore" in
    "$repo"|"$repo/"*) fail "Храните ключ подписи за пределами рабочего каталога Git." ;;
esac
alias_name=$2
expected_cert=${3//:/}
expected_cert=${expected_cert,,}
[[ "$expected_cert" =~ ^[0-9a-f]{64}$ ]] || fail "Укажите SHA-256 отпечаток сертификата (64 шестнадцатеричных знака)."

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[[ -n "$sdk" ]] || fail "Укажите путь к Android SDK в ANDROID_HOME."
build_tools="$sdk/build-tools/36.0.0"
for tool in aapt2 apksigner zipalign; do
    [[ -x "$build_tools/$tool" ]] || fail "Установите Android SDK Build-Tools 36.0.0."
done
command -v gradle >/dev/null || fail "Добавьте Gradle 8.10.2 в PATH."
[[ -r "$repo/third_party/amneziawg/build/tunnel.aar" ]] ||
    fail "Сначала соберите закреплённую версию AmneziaWG по docs/personal-release.md."

output=${4:-"$repo/dist/Kachur-VPN-release.apk"}
[[ "$output" == *.apk ]] || fail "Имя выходного файла должно заканчиваться на .apk."
mkdir -p -- "$(dirname -- "$output")"
output_dir=$(cd -- "$(dirname -- "$output")" && pwd -P)
output="$output_dir/$(basename -- "$output")"
unsigned="$repo/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ "$output" != "$unsigned" && "$output" != "$keystore" ]] ||
    fail "Выберите отдельный путь для подписанного APK."

stage=$(mktemp -d "$output_dir/.kachur-release.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT

# Gradle and the engine build never receive signing passwords.
gradle --no-daemon -p "$repo" :app:assembleRelease </dev/null
[[ -s "$unsigned" ]] || fail "Gradle не создал неподписанный release APK."
if "$build_tools/apksigner" verify "$unsigned" >/dev/null 2>&1; then
    fail "Ожидался неподписанный APK из задачи assembleRelease."
fi
badging=$("$build_tools/aapt2" dump badging "$unsigned")
grep -Fq "package: name='com.kachur.vpn'" <<< "$badging" ||
    fail "APK использует другое имя пакета."
if grep -Fxq 'application-debuggable' <<< "$badging"; then
    fail "APK содержит признак debug-сборки."
fi

"$build_tools/zipalign" -P 16 -f 4 "$unsigned" "$stage/aligned.apk"
"$build_tools/apksigner" sign \
    --ks "$keystore" --ks-type PKCS12 --ks-key-alias "$alias_name" \
    --v4-signing-enabled false --out "$stage/signed.apk" "$stage/aligned.apk"

"$build_tools/zipalign" -c -P 16 4 "$stage/signed.apk"
verification=$(LC_ALL=C "$build_tools/apksigner" verify --verbose --print-certs "$stage/signed.apk")
mapfile -t fingerprints < <(sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' <<< "$verification")
if (( ${#fingerprints[@]} != 1 )) || [[ "${fingerprints[0],,}" != "$expected_cert" ]]; then
    fail "Подпись не совпадает с ожидаемым сертификатом. Итоговый APK не заменён."
fi

mv -T -- "$stage/signed.apk" "$output"
printf 'APK: %s\nSHA-256 сертификата: %s\n' "$output" "$expected_cert"
