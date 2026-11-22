"""Minimal AmneziaWG system integration for the first Fedora prototype.

No privileged command is run at import/startup. The user must explicitly press
Connect/Disconnect, at which point polkit authenticates awg-quick. No shell=True.
The prototype reserves kch0 and never controls other VPN interfaces.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
import re
import shutil
import subprocess

_VALID_IFACE = re.compile(r"^[A-Za-z_][A-Za-z0-9_.-]{0,14}$")


class VPNError(RuntimeError):
    """A user-facing error without private config, keys or endpoint data."""


@dataclass(frozen=True)
class VPNState:
    active: bool
    received: int = 0
    sent: int = 0
    last_handshake: int = 0


def check_interface(name: str) -> str:
    name = name.strip()
    if not _VALID_IFACE.fullmatch(name) or name in {".", ".."}:
        raise VPNError("Имя интерфейса должно содержать до 15 букв, цифр, _ или -.")
    return name


def parse_transfer(output: str) -> tuple[int, int]:
    received = sent = 0
    for line in output.splitlines():
        fields = line.split()
        if len(fields) != 3:
            continue
        try:
            received += max(0, int(fields[1]))
            sent += max(0, int(fields[2]))
        except ValueError:
            continue
    return received, sent


def parse_handshakes(output: str) -> int:
    timestamps = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) != 2:
            continue
        try:
            timestamps.append(max(0, int(fields[1])))
        except ValueError:
            continue
    return max(timestamps, default=0)


def pretty_bytes(value: int) -> str:
    for unit in ("Б", "КБ", "МБ", "ГБ", "ТБ"):
        if value < 1024 or unit == "ТБ":
            return f"{value:.0f} {unit}" if unit == "Б" else f"{value:.1f} {unit}"
        value /= 1024
    return "0 Б"


def pretty_handshake(timestamp: int) -> str:
    if not timestamp:
        return "Пока нет"
    return datetime.fromtimestamp(timestamp).strftime("%d.%m %H:%M:%S")


def _tool(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise VPNError(f"Не найдена утилита {name}. Установите AmneziaWG для Linux.")
    return path


def _run(args: list[str], timeout: int = 8) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            args, capture_output=True, text=True, timeout=timeout, check=False
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise VPNError("Не удалось связаться с системным VPN-движком.") from exc


def read_state(name: str) -> VPNState:
    """Read device state without privileges; handshake is best-effort.

    The tunnel device existing does NOT prove a completed handshake.
    Device byte counters are readable from sysfs even without CAP_NET_ADMIN.
    """
    name = check_interface(name)
    device = Path("/sys/class/net") / name
    if not device.is_dir():
        return VPNState(active=False)

    def counter(field: str) -> int:
        try:
            return max(0, int((device / "statistics" / field).read_text().strip()))
        except (OSError, ValueError):
            return 0

    handshake = 0
    awg = shutil.which("awg")
    if awg:
        result = _run([awg, "show", name, "latest-handshakes"])
        if result.returncode == 0:
            handshake = parse_handshakes(result.stdout)
    return VPNState(True, counter("rx_bytes"), counter("tx_bytes"), handshake)


def switch_tunnel(name: str, connect: bool) -> None:
    """Require explicit polkit authorization; never execute arbitrary paths."""
    name = check_interface(name)
    if name != "kch0":
        raise VPNError(
            "Для безопасности прототип управляет только отдельным интерфейсом kch0. "
            "Существующие интерфейсы AmneziaVPN, включая amn0, не трогаем."
        )
    quick = _tool("awg-quick")
    userspace = _tool("amneziawg-go")
    pkexec = _tool("pkexec")
    env = _tool("env")
    action = "up" if connect else "down"
    result = _run(
        [pkexec, env, f"WG_QUICK_USERSPACE_IMPLEMENTATION={userspace}",
         quick, action, name],
        timeout=90,
    )
    if result.returncode:
        # Deliberately don't reveal awg-quick stderr (may include config details).
        raise VPNError(
            "Не удалось изменить состояние VPN. Проверьте системные разрешения "
            "и профиль /etc/amnezia/amneziawg/kch0.conf."
        )
