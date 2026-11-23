#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
run_script="$script_dir/run.sh"

if [[ ! -f "$run_script" ]]; then
    echo "Не найден скрипт запуска: $run_script" >&2
    exit 1
fi

data_home="${XDG_DATA_HOME:-$HOME/.local/share}"
applications_dir="$data_home/applications"
desktop_file="$applications_dir/com.kachur.vpn.Fedora.desktop"
mkdir -p "$applications_dir"

python3 - "$run_script" "$desktop_file" <<'PY'
from pathlib import Path
import sys

run_script = str(Path(sys.argv[1]).resolve())
desktop_file = Path(sys.argv[2])

def quote_exec_arg(value: str) -> str:
    # Escape characters reserved by the freedesktop Desktop Entry Exec syntax.
    value = value.replace("\\", "\\\\")
    for char in ('"', "`", "$"):
        value = value.replace(char, "\\" + char)
    return '"' + value + '"'

contents = "\n".join([
    "[Desktop Entry]",
    "Version=1.0",
    "Type=Application",
    "Name=Kachur VPN",
    "Comment=Персональный VPN-клиент для Fedora",
    "Exec=/usr/bin/bash " + quote_exec_arg(run_script),
    "TryExec=/usr/bin/bash",
    "Icon=network-vpn-symbolic",
    "Terminal=false",
    "StartupNotify=true",
    "StartupWMClass=com.kachur.vpn.Fedora",
    "Categories=Network;",
    "Keywords=VPN;AmneziaWG;Kachur;",
    "",
])
desktop_file.write_text(contents, encoding="utf-8")
desktop_file.chmod(0o644)
PY

if command -v desktop-file-validate >/dev/null 2>&1; then
    desktop-file-validate "$desktop_file"
fi

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$applications_dir" >/dev/null 2>&1 || true
fi

echo "Ярлык Kachur VPN установлен:"
echo "$desktop_file"
echo "Откройте меню приложений Fedora и найдите Kachur VPN."
