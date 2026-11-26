#!/usr/bin/env bash
set -euo pipefail
[[ $EUID != 0 ]] || { echo "Run as regular user, not root"; exit 1; }
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
src="$here/../fedora"
missing=0
for cmd in python3 ip iptables curl pkexec awg awg-quick resolvectl systemctl; do
  command -v "$cmd" >/dev/null || { echo "Missing: $cmd"; missing=1; }
done
python3 -c 'import gi; gi.require_version("Gtk","4.0"); gi.require_version("Adw","1"); from gi.repository import Gtk, Adw' || missing=1
systemctl is-active --quiet systemd-resolved || { echo "systemd-resolved is not active"; missing=1; }
[[ $missing == 0 ]] || { echo "Dependency preflight failed; no changes made."; exit 1; }
sudo install -Dm755 "$src/kachur-vpn-helper" /usr/local/libexec/kachur-vpn-helper
sudo install -Dm755 "$src/kachur-vpn-network.py" /usr/local/libexec/kachur-vpn-network
sudo bash "$src/install-polkit.sh"
dest="$HOME/.local/share/kachur-vpn-omarchy"
mkdir -p "$dest" "$HOME/.local/share/applications"
cp -a "$src/." "$dest/"
python3 - "$dest" <<'PY'
from pathlib import Path
import sys
p = str(Path(sys.argv[1]) / "run.sh")
p = p.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace(chr(96), "\\" + chr(96))
entry = Path.home() / ".local/share/applications/com.kachur.vpn.Fedora.desktop"
entry.write_text('[Desktop Entry]\nType=Application\nName=Kachur VPN Omarchy\nExec=/usr/bin/bash "' + p + '"\nIcon=network-vpn-symbolic\nTerminal=false\nCategories=Network;\n', encoding="utf-8")
PY
echo "Installed launcher. Automatic connection is not enabled in this experimental version."
