#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "Запустите установщик через sudo: sudo bash desktop/fedora/install-polkit.sh" >&2
    exit 1
fi

target_user="$(printenv SUDO_USER || true)"
if [[ -z "$target_user" || ! "$target_user" =~ ^[a-z_][a-z0-9_-]*[$]?$ ]]; then
    echo "Не удалось определить обычного пользователя. Запустите скрипт через sudo из его сеанса." >&2
    exit 1
fi

network_helper="/usr/local/libexec/kachur-vpn-network"
import_helper="/usr/local/libexec/kachur-vpn-helper"
if [[ ! -x "$network_helper" || ! -x "$import_helper" ]]; then
    echo "Сначала установите оба помощника Kachur в /usr/local/libexec." >&2
    exit 1
fi

install -D -m 0644 /dev/stdin /usr/share/polkit-1/actions/com.kachur.vpn.policy <<'POLICY'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE policyconfig PUBLIC
 "-//freedesktop//DTD PolicyKit Policy Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/PolicyKit/1/policyconfig.dtd">
<policyconfig>
  <vendor>Kachur VPN</vendor>
  <action id="com.kachur.vpn.connect">
    <description>Connect Kachur VPN</description>
    <message>Connect Kachur VPN</message>
    <defaults>
      <allow_any>auth_admin</allow_any>
      <allow_inactive>auth_admin</allow_inactive>
      <allow_active>auth_admin</allow_active>
    </defaults>
    <annotate key="org.freedesktop.policykit.exec.path">/usr/local/libexec/kachur-vpn-network</annotate>
    <annotate key="org.freedesktop.policykit.exec.argv1">connect</annotate>
  </action>
  <action id="com.kachur.vpn.disconnect">
    <description>Disconnect Kachur VPN</description>
    <message>Disconnect Kachur VPN</message>
    <defaults>
      <allow_any>auth_admin</allow_any>
      <allow_inactive>auth_admin</allow_inactive>
      <allow_active>auth_admin</allow_active>
    </defaults>
    <annotate key="org.freedesktop.policykit.exec.path">/usr/local/libexec/kachur-vpn-network</annotate>
    <annotate key="org.freedesktop.policykit.exec.argv1">disconnect</annotate>
  </action>
  <action id="com.kachur.vpn.import-profile">
    <description>Import Kachur VPN profile</description>
    <message>Import Kachur VPN profile</message>
    <defaults>
      <allow_any>auth_admin</allow_any>
      <allow_inactive>auth_admin</allow_inactive>
      <allow_active>auth_admin</allow_active>
    </defaults>
    <annotate key="org.freedesktop.policykit.exec.path">/usr/local/libexec/kachur-vpn-helper</annotate>
    <annotate key="org.freedesktop.policykit.exec.argv1">import-profile</annotate>
  </action>
  <action id="com.kachur.vpn.import-bypass">
    <description>Import Kachur VPN bypass IP list</description>
    <message>Import Kachur VPN bypass IP list</message>
    <defaults>
      <allow_any>auth_admin</allow_any>
      <allow_inactive>auth_admin</allow_inactive>
      <allow_active>auth_admin</allow_active>
    </defaults>
    <annotate key="org.freedesktop.policykit.exec.path">/usr/local/libexec/kachur-vpn-helper</annotate>
    <annotate key="org.freedesktop.policykit.exec.argv1">import-bypass</annotate>
  </action>
</policyconfig>
POLICY

python3 - "$target_user" <<'PY'
from pathlib import Path
import json
import re
import sys

username = sys.argv[1]
if not re.fullmatch(r"[a-z_][a-z0-9_-]*[$]?", username):
    raise SystemExit("Недопустимое имя пользователя.")
rule = f"""polkit.addRule(function(action, subject) {{
    if (subject.user === {json.dumps(username)} &&
        subject.local && subject.active &&
        (action.id === "com.kachur.vpn.connect" ||
         action.id === "com.kachur.vpn.disconnect")) {{
        return polkit.Result.YES;
    }}
}});
"""
path = Path("/etc/polkit-1/rules.d/49-kachur-vpn.rules")
path.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
path.write_text(rule, encoding="utf-8")
path.chmod(0o644)
PY

chmod 0644 /usr/share/polkit-1/actions/com.kachur.vpn.policy
if command -v restorecon >/dev/null 2>&1; then
    restorecon -F /usr/share/polkit-1/actions/com.kachur.vpn.policy \
        /etc/polkit-1/rules.d/49-kachur-vpn.rules || true
fi

echo "Polkit настроен для пользователя $target_user."
echo "Без пароля разрешены только connect/disconnect Kachur в активном локальном сеансе."
echo "Импорт профиля и JSON-списка по-прежнему требует авторизации администратора."
