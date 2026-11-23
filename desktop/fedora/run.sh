#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$here"

if [[ ${1:-} == --offline-test ]]; then
    umask 077
    log=$(mktemp "$HOME/kachur-offline.XXXXXXXX.log")
    printf 'Файл отчёта: %s\n' "$log"
    sudo -v
    if sudo /usr/local/libexec/kachur-vpn-network offline-test 2>&1 | tee "$log"; then
        printf 'Тест завершён. Можно снова подключить AmneziaVPN.\n'
    else
        printf 'Тест прерван. При необходимости дождитесь остановки службы Kachur перед запуском AmneziaVPN.\n' >&2
        exit 1
    fi
    exit 0
fi

exec python3 -m kachur_fedora
