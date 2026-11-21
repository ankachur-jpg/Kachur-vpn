#!/usr/bin/env bash
set -euo pipefail

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
upstream=$(cd -- "${1:?Pass the upstream checkout path}" && pwd)
pin=fb64e74ba5a0a54e9185b8776bcb8088afb772c9
test "$(git -C "$upstream" rev-parse HEAD)" = "$pin"
git -C "$upstream" apply --check "$here/android.patch"
git -C "$upstream" apply "$here/android.patch"

cd "$upstream"
# Only the userspace backend is distributed. Do not build or package the root tooling.
sed -i 's/targets("libwg-go.so", "libwg.so", "libwg-quick.so")/targets("libwg-go.so")/' tunnel/build.gradle.kts
sed -i 's/26\.1\.10909125/28.2.13676358/' settings.gradle.kts
# Remove the UI project so its app signing and rendering settings are irrelevant.
sed -i '/^include(":ui")$/d' settings.gradle.kts
# Exclude the upstream service declaration; Kachur VPN owns the service lifecycle.
printf '<manifest />\n' > tunnel/src/main/AndroidManifest.xml
# Use the upstream Go runtime patch with the minimum version required by its go.mod.
sed -i -e 's/GO_VERSION := 1.24.2/GO_VERSION := 1.24.4/' \
    -e 's/^GO_HASH_linux-amd64 := .*/GO_HASH_linux-amd64 := 77e5da33bb72aeaef1ba4418b6fe511bc4d041873cbf82e5aa6318740df98717/' \
    tunnel/tools/libwg-go/Makefile
GOTOOLCHAIN=local ./gradlew :tunnel:assembleRelease --stacktrace

out="$here/build"
licenses="$out/assets/amneziawg-licenses"
mkdir -p "$licenses"
cp tunnel/build/outputs/aar/tunnel-release.aar "$out/tunnel.aar"
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    unzip -Z1 "$out/tunnel.aar" | grep -Fx "jni/$abi/libwg-go.so"
done
cp COPYING "$licenses/amneziawg-android.txt"
cp "$here/NOTICE" "$licenses/NOTICE.txt"
(
    cd tunnel/tools/libwg-go
    cp "$(go env GOROOT)/LICENSE" "$licenses/go.txt"
    go mod download -json | python3 -c '
import json
import pathlib
import shutil
import sys

decoder = json.JSONDecoder()
data = sys.stdin.read().lstrip()
out = pathlib.Path(sys.argv[1])
while data:
    module, length = decoder.raw_decode(data)
    source = pathlib.Path(module["Dir"])
    name = module["Path"].replace("/", "_")
    for filename in ("LICENSE", "COPYING", "NOTICE", "PATENTS"):
        file = source / filename
        if file.is_file():
            shutil.copyfile(file, out / f"{name}-{filename}.txt")
    data = data[length:].lstrip()
' "$licenses"
)
