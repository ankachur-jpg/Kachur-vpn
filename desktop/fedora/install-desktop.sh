#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "$BASH_SOURCE")" && pwd)"
run_script="$script_dir/run.sh"

if [[ ! -f "$run_script" ]]; then
    echo "Не найден скрипт запуска: $run_script" >&2
    exit 1
fi

data_home="$HOME/.local/share"
config_home="$HOME/.config"
applications_dir="$data_home/applications"
autostart_dir="$config_home/autostart"
desktop_file="$applications_dir/com.kachur.vpn.Fedora.desktop"
autostart_file="$autostart_dir/com.kachur.vpn.Fedora.desktop"
mkdir -p "$applications_dir" "$autostart_dir"

python3 - "$run_script" "$desktop_file" "$autostart_file" <<'PY'
from pathlib import Path
import sys

run_script = str(Path(sys.argv[1]).resolve())
desktop_file = Path(sys.argv[2])
autostart_file = Path(sys.argv[3])

def quote_exec_arg(value: str) -> str:
    value = value.replace("\\", "\\\\")
    for char in ('"', chr(96), "$"):
        value = value.replace(char, "\\" + char)
    return '"' + value + '"'

base = [
    "[Desktop Entry]",
    "Version=1.0",
    "Type=Application",
    "Name=Kachur VPN",
    "Comment=Персональный VPN-клиент для Fedora",
    "TryExec=/usr/bin/bash",
    "Icon=network-vpn-symbolic",
    "Terminal=false",
    "StartupNotify=true",
    "StartupWMClass=com.kachur.vpn.Fedora",
    "Categories=Network;",
    "Keywords=VPN;AmneziaWG;Kachur;",
]
menu_contents = "\n".join([
    *base,
    "Exec=/usr/bin/bash " + quote_exec_arg(run_script),
    "",
])
autostart_contents = "\n".join([
    *base,
    "Name=Kachur VPN — автоподключение",
    "Comment=Запуск Kachur VPN и подключение туннеля при входе в Fedora",
    "Exec=/usr/bin/bash " + quote_exec_arg(run_script) + " --autoconnect",
    "X-GNOME-Autostart-enabled=true",
    "X-GNOME-Autostart-Delay=12",
    "StartupNotify=false",
    "",
])
desktop_file.write_text(menu_contents, encoding="utf-8")
autostart_file.write_text(autostart_contents, encoding="utf-8")
desktop_file.chmod(0o644)
autostart_file.chmod(0o644)
PY

if command -v desktop-file-validate >/dev/null 2>&1; then
    desktop-file-validate "$desktop_file"
    desktop-file-validate "$autostart_file"
fi

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$applications_dir" >/dev/null 2>&1 || true
fi

echo "Ярлык Kachur VPN установлен:"
echo "$desktop_file"
echo "Автозапуск с автоматическим подключением установлен:"
echo "$autostart_file"
echo "Автоподключение выполняется после входа в GNOME; AmneziaVPN не отключается автоматически."
