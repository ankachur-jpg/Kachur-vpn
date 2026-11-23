#!/usr/bin/python3 -I
"""Systemd worker for the fixed Kachur interface. No profile data goes to logs."""
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import subprocess
import time
from uuid import uuid4

IFACE = "kch0"
PROFILE = Path("/etc/amnezia/amneziawg/kch0.conf")
RUNTIME = Path("/run/kachur-vpn")
CONFIG = RUNTIME / "kch0.conf"
OWNED = RUNTIME / "owned"
UNIT = "kachur-vpn.service"
TABLE = "51821"
MARK = "0xca6d"
PRIORITY = "10501"
PROTOCOL = "242"
FIREWALL_CHAIN = "KACHUR_OUT"
TARGET = "1.1.1.1"
PROBE_HOST = "cloudflare-dns.com"
ENV = {"PATH": "/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin", "LC_ALL": "C"}
RULE = ("pref", PRIORITY, "not", "fwmark", MARK, "lookup", TABLE, "protocol", PROTOCOL)


class NetworkError(RuntimeError):
    pass


def run(*args, timeout=5, capture=True, env=None):
    try:
        return subprocess.run(
            args, env=ENV if env is None else env, timeout=timeout, text=True,
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE if capture else None,
            stderr=subprocess.DEVNULL if capture else None, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise NetworkError(f"Не удалось выполнить {Path(args[0]).name}.") from exc


def required(*args, message, timeout=5, env=None):
    result = run(*args, timeout=timeout, env=env)
    if result.returncode:
        raise NetworkError(message)
    return result.stdout


def device(name):
    return (Path("/sys/class/net") / name).exists()


def check_amnezia():
    # The interface check prevents simultaneous tunnels. Stale Amnezia
    # OUTPUT anchors are handled by a narrow Kachur-owned exception chain.
    if device("amn0"):
        raise NetworkError("Отключите AmneziaVPN в её окне, затем повторите попытку.")


def prepare_profile(text):
    """Keep the installed AWG options, but own routing and DNS in this service."""
    section = ""
    output = []
    addresses, defaults, dns = set(), set(), []
    seen_interface = False
    for raw in text.splitlines():
        line = raw.partition("#")[0].strip()
        if not line:
            continue
        if line == "[Interface]" and not seen_interface and not section:
            seen_interface = True
            section = "Interface"
            output.extend((line, "Table = off"))
            continue
        if line == "[Peer]" and seen_interface:
            section = "Peer"
            output.append(line)
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not section or not separator or not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", key) or not value:
            raise NetworkError("Некорректная структура профиля kch0.conf.")
        field = key.lower()
        if field in {"preup", "postup", "predown", "postdown", "saveconfig"}:
            raise NetworkError("Профиль kch0.conf содержит команды или SaveConfig.")
        if field in {"table", "fwmark", "dns", "address", "mtu"} and section != "Interface":
            raise NetworkError("Некорректное расположение параметра в профиле kch0.conf.")
        if field in {"table", "fwmark"}:
            continue
        if field == "dns":
            dns.extend(ipaddress.ip_address(item.strip()) for item in value.split(","))
            continue
        if field == "address":
            addresses.update(ipaddress.ip_interface(item.strip()).version for item in value.split(","))
        if field == "allowedips" and section == "Peer":
            networks = (ipaddress.ip_network(item.strip(), strict=False) for item in value.split(","))
            defaults.update(net.version for net in networks if net.prefixlen == 0)
        output.append(f"{key} = {value}")

    if 4 not in addresses or 4 not in defaults:
        raise NetworkError("Для полного IPv4-туннеля нужны IPv4 Address и AllowedIPs = 0.0.0.0/0.")
    ipv6 = 6 in addresses and 6 in defaults
    dns = list(dict.fromkeys(str(address) for address in dns if address.version == 4 or ipv6))
    return "\n".join(output) + "\n", dns or ["1.1.1.1", "1.0.0.1"], ipv6


def load_profile():
    info = PROFILE.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077 or info.st_size > 65536:
        raise NetworkError("Нужен обычный профиль kch0.conf владельца root с правами 600.")
    try:
        return prepare_profile(PROFILE.read_text(encoding="utf-8-sig"))
    except ValueError as exc:
        raise NetworkError("В профиле kch0.conf есть некорректный адрес или DNS.") from exc


def reserve(family):
    rules = required("ip", family, "rule", "show", message="Не удалось прочитать правила маршрутизации.")
    if re.search(rf"^{PRIORITY}:|\blookup {TABLE}(?:\s|$)", rules, re.MULTILINE):
        raise NetworkError("Приоритет или таблица маршрутов Kachur уже используется.")
    if run("ip", family, "route", "show", "table", TABLE).stdout.strip():
        raise NetworkError("Таблица маршрутов Kachur уже используется.")


def route_device(address, family="-4", uid=None, mark=None):
    args = ()
    if mark is not None:
        args += ("mark", str(mark))
    if uid is not None:
        args += ("uid", str(uid))
    result = required("ip", "-j", family, "route", "get", address, *args,
                      message="Маршрут проверки недоступен.")
    return json.loads(result)[0].get("dev")


def peer_endpoints():
    """Return resolved peer endpoint IPs and UDP ports, never hostnames."""
    output = required("awg", "show", IFACE, "endpoints",
                      message="Не удалось определить адрес сервера Kachur.")
    endpoints = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) != 2 or fields[1] in {"(none)", "none"}:
            continue
        value = fields[1]
        if value.startswith("["):
            closing = value.find("]")
            if closing < 0 or value[closing + 1:closing + 2] != ":":
                raise NetworkError("Некорректный адрес сервера Kachur.")
            host, port_text = value[1:closing], value[closing + 2:]
        else:
            host, separator, port_text = value.rpartition(":")
            if not separator:
                raise NetworkError("Некорректный адрес сервера Kachur.")
        try:
            address = ipaddress.ip_address(host)
            port = int(port_text)
        except ValueError as exc:
            raise NetworkError("Сервер Kachur должен иметь доступный IP-адрес и UDP-порт.") from exc
        if not 1 <= port <= 65535:
            raise NetworkError("Некорректный UDP-порт сервера Kachur.")
        endpoints.append((address, port))
    if not endpoints:
        raise NetworkError("Движок AmneziaWG не сообщил адрес сервера Kachur.")
    return endpoints


def remove_firewall_bypass():
    """Remove only the temporary KACHUR_OUT chains; leave Amnezia rules intact."""
    for tool in ("iptables", "ip6tables"):
        if not shutil.which(tool, path=ENV["PATH"]):
            continue
        output = required(tool, "-w", "2", "-S", "OUTPUT",
                          message="Не удалось проверить временные правила Kachur.")
        jump = re.compile(rf"^-A OUTPUT -j {re.escape(FIREWALL_CHAIN)}$", re.MULTILINE)
        while jump.search(output):
            required(tool, "-w", "2", "-D", "OUTPUT", "-j", FIREWALL_CHAIN,
                     message="Не удалось удалить переход временных правил Kachur.")
            output = required(tool, "-w", "2", "-S", "OUTPUT",
                              message="Не удалось проверить временные правила Kachur.")
        chain = run(tool, "-w", "2", "-S", FIREWALL_CHAIN)
        if chain.returncode == 0:
            required(tool, "-w", "2", "-F", FIREWALL_CHAIN,
                     message="Не удалось очистить временную цепочку Kachur.")
            required(tool, "-w", "2", "-X", FIREWALL_CHAIN,
                     message="Не удалось удалить временную цепочку Kachur.")


def install_firewall_bypass(ipv6_tunnel):
    """Allow only tunnel egress and the encrypted AWG endpoint past stale Amnezia anchors."""
    families = (("-4", "iptables"), ("-6", "ip6tables"))
    anchored = {}
    for family, tool in families:
        if not shutil.which(tool, path=ENV["PATH"]):
            continue
        output = required(tool, "-w", "2", "-S", "OUTPUT",
                          message="Не удалось проверить правила OUTPUT AmneziaVPN.")
        anchored[family] = bool(re.search(
            r"(?:^|\\s)(?:-j|-g) amnvpn\\.anchors(?:\\s|$)", output
        ))
    if not any(anchored.values()):
        return

    endpoints = peer_endpoints()
    for family, tool in families:
        if not anchored.get(family):
            continue
        rules = []
        if family == "-4" or ipv6_tunnel:
            rules.append(("-o", IFACE, "-j", "ACCEPT"))
        for address, port in endpoints:
            endpoint_family = "-4" if address.version == 4 else "-6"
            if endpoint_family != family:
                continue
            egress = route_device(str(address), family, mark=MARK)
            if not egress or egress in {IFACE, "amn0"}:
                raise NetworkError("Не удалось определить физический маршрут до сервера Kachur.")
            rules.append((
                "-o", egress, "-d", str(address), "-p", "udp",
                "--dport", str(port), "-j", "ACCEPT",
            ))
        if not rules:
            continue
        existing = run(tool, "-w", "2", "-S", FIREWALL_CHAIN)
        if existing.returncode == 0:
            raise NetworkError("Обнаружена незавершённая цепочка Kachur. Выполните отключение Kachur и повторите попытку.")
        required(tool, "-w", "2", "-N", FIREWALL_CHAIN,
                 message="Не удалось создать временную цепочку Kachur.")
        try:
            for rule in rules:
                required(tool, "-w", "2", "-A", FIREWALL_CHAIN, *rule,
                         message="Не удалось добавить ограниченное исключение Kachur.")
            required(tool, "-w", "2", "-A", FIREWALL_CHAIN, "-j", "RETURN",
                     message="Не удалось завершить временную цепочку Kachur.")
            required(tool, "-w", "2", "-I", "OUTPUT", "1", "-j", FIREWALL_CHAIN,
                     message="Не удалось включить ограниченное исключение Kachur.")
        except NetworkError:
            run(tool, "-w", "2", "-D", "OUTPUT", "-j", FIREWALL_CHAIN)
            run(tool, "-w", "2", "-F", FIREWALL_CHAIN)
            run(tool, "-w", "2", "-X", FIREWALL_CHAIN)
            raise
        print(
            f"KillSwitch {family} сохранён; разрешён только трафик через kch0 "
            "и зашифрованный UDP-трафик к серверу Kachur.",
            flush=True,
        )


def enabled_families():
    disabled = Path("/proc/sys/net/ipv6/conf/all/disable_ipv6")
    return ["-4", "-6"] if disabled.exists() and disabled.read_text().strip() == "0" else ["-4"]


def configure(uid):
    check_amnezia()
    if device(IFACE):
        raise NetworkError("Интерфейс kch0 уже занят. Отключите предыдущий запуск Kachur.")
    # Recover only our own orphaned exception chain from an interrupted run.
    remove_firewall_bypass()
    config, dns, ipv6 = load_profile()
    families = enabled_families()
    ipv6_available = "-6" in families
    if ipv6 and not ipv6_available:
        raise NetworkError("Профиль требует IPv6, но IPv6 отключён на Fedora.")
    for family in families:
        reserve(family)
    required("systemctl", "is-active", "--quiet", "systemd-resolved.service",
             message="Для DNS Kachur запустите systemd-resolved.")
    nameservers = re.findall(r"^\s*nameserver\s+(\S+)", Path("/etc/resolv.conf").read_text(), re.MULTILINE)
    if not nameservers or set(nameservers) - {"127.0.0.53", "127.0.0.54"}:
        raise NetworkError("Системный DNS Fedora должен использовать локальный stub systemd-resolved.")
    with CONFIG.open("x", encoding="utf-8") as stream:
        stream.write(config)
    OWNED.write_text("\n".join(families), encoding="ascii")

    quick_env = ENV.copy()
    userspace = shutil.which("amneziawg-go", path=ENV["PATH"])
    if userspace:
        quick_env["WG_QUICK_USERSPACE_IMPLEMENTATION"] = userspace
    required("awg-quick", "up", str(CONFIG), env=quick_env, timeout=25,
             message="AmneziaWG не поднял kch0. Проверьте установленный движок и профиль.")
    required("awg", "set", IFACE, "fwmark", MARK,
             message="Не удалось назначить метку транспорта AWG.")
    install_firewall_bypass(ipv6)
    for family in families:
        route = ("default", "dev", IFACE) if family == "-4" or ipv6 else ("unreachable", "default")
        required("ip", family, "route", "add", *route, "table", TABLE, "proto", PROTOCOL,
                 message="Не удалось добавить маршрут Kachur.")
        required("ip", family, "rule", "add", *RULE,
                 message="Не удалось включить правило маршрутизации Kachur.")
    if route_device(TARGET, uid=uid) != IFACE:
        raise NetworkError("Другой маршрут перехватывает тестовый IPv4-адрес.")
    for address in dns:
        family = "-6" if ":" in address else "-4"
        if route_device(address, family) != IFACE:
            raise NetworkError("DNS-сервер профиля недоступен через kch0.")
    required("resolvectl", "dns", IFACE, *dns, message="Не удалось установить DNS для kch0.")
    required("resolvectl", "domain", IFACE, "~.", message="Не удалось назначить маршрут DNS для kch0.")
    required("resolvectl", "default-route", IFACE, "yes",
             message="Не удалось включить DNS Kachur по умолчанию.")
    check_amnezia()
    print("Маршрут IPv4 и DNS настроены.", flush=True)
    if ipv6_available:
        print("Маршрут IPv6 настроен." if ipv6 else "Внешний IPv6 заблокирован на время сеанса.", flush=True)


def probe(uid):
    check_amnezia()
    if not device(IFACE) or route_device(TARGET, uid=uid) != IFACE:
        raise NetworkError("Проверочный IPv4-маршрут не проходит через kch0.")
    required("curl", "--disable", "--noproxy", "*", "--ipv4", "--interface", f"if!{IFACE}",
             "--proto", "=https", "--resolve", f"{PROBE_HOST}:443:{TARGET}", "--fail",
             "--silent", "--connect-timeout", "6", "--max-time", "12", "--output", "/dev/null",
             f"https://{PROBE_HOST}/cdn-cgi/trace", timeout=14,
             message="Тест HTTPS через kch0 не прошёл. Проверьте доступность туннеля.")
    print("HTTPS через kch0: OK.", flush=True)
    required("resolvectl", "query", "-4", "-i", IFACE, "--cache=no", PROBE_HOST, timeout=8,
             message="HTTPS работает, но запрос DNS через kch0 не прошёл.")
    print("DNS через kch0: OK.", flush=True)


def cleanup():
    if not OWNED.exists():
        if device(IFACE):
            raise NetworkError("kch0 существует без маркера владения; интерфейс оставлен без изменений.")
        remove_firewall_bypass()
        print("Откат Kachur: временные правила очищены.", flush=True)
        return
    families = OWNED.read_text(encoding="ascii").splitlines()
    if device(IFACE):
        # Removing the link also clears its per-link systemd-resolved settings.
        run("resolvectl", "revert", IFACE)
        run("ip", "link", "delete", "dev", IFACE)
    for family in families:
        run("ip", family, "rule", "delete", *RULE)
        run("ip", family, "route", "flush", "table", TABLE, "proto", PROTOCOL)
    for family in families:
        rules = required("ip", family, "rule", "show", message="Не удалось проверить откат маршрутов.")
        routes = run("ip", family, "route", "show", "table", TABLE).stdout
        if re.search(rf"^{PRIORITY}:", rules, re.MULTILINE) or routes.strip():
            raise NetworkError("Откат маршрутов неполный. Проверьте журнал службы Kachur.")
    if device(IFACE):
        raise NetworkError("Интерфейс kch0 остался после отката. Проверьте журнал службы Kachur.")
    # Amnezia's original OUTPUT anchors were never removed or edited.
    remove_firewall_bypass()
    OWNED.unlink(missing_ok=True)
    print("Откат Kachur: интерфейс, маршруты, DNS и временные правила сняты.", flush=True)


def notify_ready():
    address = os.environ["NOTIFY_SOCKET"]
    with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as notifier:
        notifier.connect("\0" + address[1:] if address.startswith("@") else address)
        notifier.sendall(b"READY=1")


def start(action, uid):
    if device(IFACE):
        raise NetworkError("Интерфейс kch0 уже существует.")
    check_amnezia()
    command = [
        "systemd-run", "--unit", UNIT, "--collect", "--quiet",
        "--property=RuntimeDirectory=kachur-vpn", "--property=RuntimeDirectoryMode=0700",
        "--property=UMask=0077", "--property=TimeoutStartSec=70s",
        "--property=TimeoutStopSec=60s",
        f"--property=ExecStopPost={Path(__file__)} cleanup",
    ]
    if action == "offline-test":
        log_tag = f"kachur-offline-{uuid4().hex}"
        command.extend(("--wait", "--property=Type=exec", "--property=RuntimeMaxSec=75s",
                        f"--property=SyslogIdentifier={log_tag}"))
    else:
        command.extend(("--property=Type=notify", "--property=NotifyAccess=main"))
    command.extend((str(Path(__file__)), "worker", action, str(uid)))
    result = run(*command, timeout=155, capture=False).returncode
    if action == "offline-test":
        journal = required("journalctl", "--no-pager", "-o", "cat", "-t", log_tag,
                           message="Не удалось прочитать журнал автономной проверки.")
        print(journal, end="", flush=True)
        if not result and "Откат Kachur:" not in journal:
            raise NetworkError("В журнале нет подтверждения отката Kachur.")
    return result


def main(argv):
    if os.geteuid() != 0:
        raise NetworkError("Запустите сетевой помощник с правами администратора.")
    if argv == ["cleanup"]:
        cleanup()
        return 0
    if len(argv) == 3 and argv[0] == "worker" and argv[1] in {"connect", "offline-test"}:
        configure(int(argv[2]))
        probe(int(argv[2]))
        if argv[1] == "connect":
            notify_ready()
            while device(IFACE):
                time.sleep(2)
        return 0
    uid = int(os.environ.get("PKEXEC_UID", os.environ.get("SUDO_UID", "0")))
    if argv in (["connect"], ["offline-test"]):
        result = start(argv[0], uid)
        if result and argv == ["connect"]:
            print("Запуск прерван. Подробности: sudo journalctl -u kachur-vpn.service -n 30.")
        return result
    if argv == ["disconnect"]:
        required("systemctl", "stop", UNIT, timeout=65, message="Не удалось остановить службу Kachur.")
        # Also remove a Kachur-owned firewall chain left by an interrupted stop.
        cleanup()
        if device(IFACE):
            raise NetworkError("kch0 остался активен. Проверьте журнал службы Kachur.")
        for family in enabled_families():
            reserve(family)
        return 0
    if argv == ["check"]:
        probe(uid)
        return 0
    raise NetworkError("Неизвестная команда сетевого помощника.")


if __name__ == "__main__":
    import sys

    try:
        raise SystemExit(main(sys.argv[1:]))
    except NetworkError as error:
        print(error, flush=True)
        raise SystemExit(1)
    except (OSError, ValueError, KeyError, IndexError):
        print("Не удалось прочитать системное состояние Kachur.", flush=True)
        raise SystemExit(1)
