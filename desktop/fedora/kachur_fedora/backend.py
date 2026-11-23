"""Minimal AmneziaWG system integration for the first Fedora prototype.

No privileged command is run at import/startup. The user must explicitly press
Connect/Disconnect, at which point Polkit authenticates a fixed helper. Handshake
inspection and profile import are separate, explicit Polkit actions. Periodic
refreshes never request administrator privileges. No shell=True is used.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
import re
import shutil
import subprocess

_VALID_IFACE = re.compile(r"^[A-Za-z_][A-Za-z0-9_.-]{0,14}$")
_NETWORK_HELPER = Path("/usr/local/libexec/kachur-vpn-network")
_IMPORT_HELPER = Path("/usr/local/libexec/kachur-vpn-helper")


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


def read_state(name: str, check_handshake: bool = False) -> VPNState:
    """Read interface counters without privileges.

    The tunnel device existing does NOT prove a completed handshake. Reading
    AmneziaWG's latest-handshake timestamp requires access to its privileged
    control socket, so it is queried only after an explicit user action via
    pkexec. Periodic UI refreshes never trigger an authentication prompt.
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
    if check_handshake:
        awg = _tool("awg")
        pkexec = _tool("pkexec")
        result = _run([pkexec, awg, "show", name, "latest-handshakes"], timeout=25)
        if result.returncode:
            raise VPNError(
                "Не удалось проверить handshake. Возможно, запрос прав администратора "
                "был отменён."
            )
        handshake = parse_handshakes(result.stdout)
    return VPNState(True, counter("rx_bytes"), counter("tx_bytes"), handshake)


def import_profile(source_path: str) -> None:
    """Import a validated .conf through a fixed, root-owned Polkit helper."""
    if not _IMPORT_HELPER.is_file() or not _IMPORT_HELPER.stat().st_mode & 0o111:
        raise VPNError(
            "Помощник безопасного импорта не установлен. Сначала выполните команду "
            "установки из инструкции Kachur VPN."
        )
    pkexec = _tool("pkexec")
    result = _run(
        [pkexec, str(_IMPORT_HELPER), "import-profile", str(Path(source_path).absolute())],
        timeout=45,
    )
    if result.returncode:
        # Never show helper output: a malformed profile may contain secret values.
        raise VPNError(
            "Импорт не выполнен. Проверьте формат AmneziaWG .conf; опасные директивы "
            "и команды в конфигурации запрещены."
        )


def _network_action(action: str) -> None:
    if not _NETWORK_HELPER.is_file():
        raise VPNError("Установите сетевой помощник Kachur по инструкции для Fedora.")
    result = _run([_tool("pkexec"), str(_NETWORK_HELPER), action], timeout=175)
    if result.returncode in (126, 127):
        raise VPNError("Запрос прав администратора отменён или недоступен.")
    if result.returncode:
        raise VPNError(
            result.stdout.strip() or "Сетевая операция Kachur не выполнена."
        )


def switch_tunnel(name: str, connect: bool) -> None:
    if check_interface(name) != "kch0":
        raise VPNError("Kachur управляет только интерфейсом kch0.")
    _network_action("connect" if connect else "disconnect")


def check_connection() -> None:
    _network_action("check")
