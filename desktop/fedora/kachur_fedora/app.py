"""Native Kachur VPN proof of concept for GNOME (GTK4 + libadwaita)."""
from __future__ import annotations

from pathlib import Path
import sys
from threading import Thread

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, GLib, Gtk

from .backend import (
    VPNError, VPNState, check_interface, pretty_bytes, pretty_handshake,
    read_state, switch_tunnel,
)

STYLES = b"""
window.kachur { background: #08192e; color: #eff6ff; }
.kachur-title { font-weight: 800; font-size: 26px; color: #f5f9ff; }
.kachur-subtitle { color: #9db6d1; font-size: 13px; }
.kachur-card { background: #102b49; border-radius: 22px; border: 1px solid #2a4969; padding: 20px; }
.kachur-stat { background: #173857; border-radius: 15px; padding: 14px; }
.kachur-stat-value { color: #ffffff; font-weight: 700; font-size: 19px; }
.kachur-stat-name { color: #a5bed5; font-size: 12px; }
.kachur-status { color: #eaf2ff; font-size: 20px; font-weight: 700; }
.kachur-connected { color: #4ee29d; }
.kachur-button { border-radius: 999px; min-height: 82px; min-width: 82px; background: #416eea; color: white; font-size: 17px; font-weight: 700; }
.kachur-button-connected { background: #186c59; }
.kachur-error { color: #ffb9b9; }
entry.kachur-entry { background: #193756; border-radius: 12px; color: white; }
"""


def label(text: str, css: str | None = None) -> Gtk.Label:
    obj = Gtk.Label(label=text)
    obj.set_wrap(True)
    obj.set_xalign(0)
    if css:
        obj.add_css_class(css)
    return obj


class MainWindow(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application) -> None:
        super().__init__(application=app, title="Kachur VPN", default_width=430, default_height=720)
        self.add_css_class("kachur")
        self.current_state = VPNState(False)
        self.busy = False
        self._refresh_running = False
        self._handshake_check_running = False
        self._handshake_checked = False

        toolbar = Adw.ToolbarView()
        header = Adw.HeaderBar()
        header.set_title_widget(Gtk.Label(label="Kachur VPN • Fedora"))
        toolbar.add_top_bar(header)
        self.set_content(toolbar)

        scroll = Gtk.ScrolledWindow()
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        toolbar.set_content(scroll)
        body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=18)
        body.set_margin_top(20)
        body.set_margin_bottom(24)
        body.set_margin_start(20)
        body.set_margin_end(20)
        scroll.set_child(body)

        body.append(label("KACHUR VPN", "kachur-title"))
        body.append(label("Персональный VPN-клиент для Fedora и GNOME", "kachur-subtitle"))

        hero = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14)
        hero.add_css_class("kachur-card")
        body.append(hero)

        # Reuse exactly the Android launcher artwork where supported by GTK.
        icon = Path(__file__).resolve().parents[3] / (
            "app/src/main/res/drawable-xxxhdpi/ic_vpn_launcher_foreground.webp"
        )
        if icon.is_file():
            picture = Gtk.Picture.new_for_filename(str(icon))
            picture.set_size_request(108, 108)
            picture.set_content_fit(Gtk.ContentFit.CONTAIN)
            hero.append(picture)
        else:
            shield = Gtk.Image.new_from_icon_name("network-vpn-symbolic")
            shield.set_pixel_size(64)
            hero.append(shield)

        self.status = label("Проверяем соединение…", "kachur-status")
        self.status.set_xalign(0.5)
        hero.append(self.status)

        self.power = Gtk.Button(label="⏻  Включить VPN")
        self.power.add_css_class("kachur-button")
        self.power.set_tooltip_text("Подключить или отключить выбранный интерфейс AmneziaWG")
        self.power.connect("clicked", self.on_toggle)
        hero.append(self.power)

        profile_card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        profile_card.add_css_class("kachur-card")
        profile_card.append(label("Имя VPN-интерфейса"))
        self.profile = Gtk.Entry()
        self.profile.set_text("kch0")
        self.profile.set_max_length(15)
        self.profile.set_placeholder_text("Интерфейс Kachur: kch0")
        self.profile.add_css_class("kachur-entry")
        self.profile.set_editable(False)
        profile_card.append(self.profile)
        profile_card.append(label(
            "Для Kachur зарезервирован отдельный интерфейс kch0 и профиль "
            "/etc/amnezia/amneziawg/kch0.conf. Существующий интерфейс amn0 "
            "приложения AmneziaVPN не затрагивается.",
            "kachur-subtitle",
        ))
        body.append(profile_card)

        stats = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        body.append(stats)
        self.rx = self.add_stat(stats, "Получено", "0 Б")
        self.tx = self.add_stat(stats, "Отправлено", "0 Б")
        self.handshake = self.add_stat(stats, "Handshake", "Не проверен")

        self.handshake_button = Gtk.Button(label="Проверить handshake")
        self.handshake_button.set_tooltip_text(
            "Однократная проверка через системный запрос прав администратора"
        )
        self.handshake_button.connect("clicked", self.on_check_handshake)
        body.append(self.handshake_button)
        body.append(label(
            "Автообновление счётчиков не запрашивает права администратора. "
            "Для чтения времени handshake нажмите кнопку: появится отдельный "
            "системный запрос. Пароль не сохраняется.",
            "kachur-subtitle",
        ))

        self.message = label("", "kachur-subtitle")
        self.message.set_selectable(True)
        body.append(self.message)

        self.refresh_button = Gtk.Button(label="Обновить состояние")
        self.refresh_button.connect("clicked", lambda *_: self.refresh())
        body.append(self.refresh_button)

        body.append(label(
            "Первый рабочий прототип: системный AmneziaWG + ручное включение через polkit. "
            "IP-исключения, автоподключение и плитку GNOME разрабатываем отдельно.",
            "kachur-subtitle",
        ))
        GLib.idle_add(self.refresh)
        GLib.timeout_add_seconds(8, self._tick)

    def add_stat(self, box: Gtk.Box, name: str, value: str) -> Gtk.Label:
        card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        card.add_css_class("kachur-stat")
        card.set_hexpand(True)
        card.append(label(name, "kachur-stat-name"))
        result = label(value, "kachur-stat-value")
        card.append(result)
        box.append(card)
        return result

    def _tick(self) -> bool:
        if not self.busy and not self._handshake_check_running:
            self.refresh()
        return GLib.SOURCE_CONTINUE

    def refresh(self, *_args: object) -> None:
        if self.busy or self._refresh_running or self._handshake_check_running:
            return
        try:
            name = check_interface(self.profile.get_text())
        except VPNError as exc:
            self.message.set_text(str(exc))
            return
        self._refresh_running = True

        def worker() -> None:
            try:
                state, error = read_state(name), ""
            except VPNError as exc:
                state, error = None, str(exc)
            GLib.idle_add(self._show_state, name, state, error, True)

        Thread(target=worker, daemon=True).start()

    def on_check_handshake(self, *_args: object) -> None:
        if self.busy or self._handshake_check_running:
            return
        if not self.current_state.active:
            self.message.set_text("Интерфейс kch0 не запущен. Сначала подключите Kachur VPN.")
            return
        try:
            name = check_interface(self.profile.get_text())
        except VPNError as exc:
            self.message.set_text(str(exc))
            return

        self._handshake_check_running = True
        self.handshake_button.set_sensitive(False)
        self.message.set_text(
            "Проверяем handshake. Может появиться системный запрос прав администратора."
        )

        def worker() -> None:
            try:
                state, error = read_state(name, check_handshake=True), ""
            except VPNError as exc:
                state, error = None, str(exc)
            GLib.idle_add(self._handshake_finished, name, state, error)

        Thread(target=worker, daemon=True).start()

    def _handshake_finished(
        self, name: str, state: VPNState | None, error: str
    ) -> bool:
        self._handshake_check_running = False
        self.handshake_button.set_sensitive(self.current_state.active and not self.busy)
        if error:
            self.message.set_text(error)
            self.refresh()
            return GLib.SOURCE_REMOVE
        assert state is not None
        self._handshake_checked = True
        self._show_state(name, state, "", False)
        return GLib.SOURCE_REMOVE

    def _show_state(
        self, name: str, state: VPNState | None, error: str,
        preserve_handshake: bool = False,
    ) -> bool:
        self._refresh_running = False
        if self.profile.get_text().strip() != name:
            return GLib.SOURCE_REMOVE
        if error:
            self.status.set_text("Статус недоступен")
            self.message.set_text(error)
            self.power.set_sensitive(False)
            self.handshake_button.set_sensitive(False)
            return GLib.SOURCE_REMOVE
        assert state is not None

        # Keep the last explicitly checked timestamp while counters auto-refresh.
        if (
            preserve_handshake and state.active and not state.last_handshake
            and self.current_state.active and self.current_state.last_handshake
        ):
            state = VPNState(
                True, state.received, state.sent, self.current_state.last_handshake
            )
        if not state.active:
            self._handshake_checked = False

        self.current_state = state
        if state.active and state.last_handshake:
            status_text = "Туннель активен • handshake получен"
        elif state.active and self._handshake_checked:
            status_text = "Туннель активен • handshake не получен"
        elif state.active:
            status_text = "Туннель запущен • handshake не проверен"
        else:
            status_text = "VPN выключен"
        self.status.set_text(status_text)
        if state.active and state.last_handshake:
            self.status.add_css_class("kachur-connected")
        else:
            self.status.remove_css_class("kachur-connected")
        if state.active:
            self.power.add_css_class("kachur-button-connected")
        else:
            self.power.remove_css_class("kachur-button-connected")
        self.power.set_label("⏻  Отключить VPN" if state.active else "⏻  Включить VPN")
        self.rx.set_text(pretty_bytes(state.received))
        self.tx.set_text(pretty_bytes(state.sent))
        self.handshake.set_text(
            pretty_handshake(state.last_handshake)
            if state.last_handshake else
            ("Не получен" if self._handshake_checked else "Не проверен")
        )
        self.message.set_text("")
        self.power.set_sensitive(not self.busy)
        self.handshake_button.set_sensitive(state.active and not self.busy)
        return GLib.SOURCE_REMOVE

    def on_toggle(self, *_args: object) -> None:
        if self.busy or self._handshake_check_running:
            return
        try:
            name = check_interface(self.profile.get_text())
        except VPNError as exc:
            self.message.set_text(str(exc))
            return
        connect = not self.current_state.active
        self.busy = True
        self._handshake_checked = False
        self.power.set_sensitive(False)
        self.handshake_button.set_sensitive(False)
        self.status.set_text("Подключаем…" if connect else "Отключаем…")
        self.message.set_text("Может появиться системный запрос пароля администратора.")

        def worker() -> None:
            try:
                switch_tunnel(name, connect)
                error = ""
            except VPNError as exc:
                error = str(exc)
            GLib.idle_add(self._finished, error)

        Thread(target=worker, daemon=True).start()

    def _finished(self, error: str) -> bool:
        self.busy = False
        self.power.set_sensitive(True)
        if error:
            self.message.set_text(error)
        self.refresh()
        return GLib.SOURCE_REMOVE


class KachurApp(Adw.Application):
    def __init__(self) -> None:
        super().__init__(application_id="com.kachur.vpn.Fedora")

    def do_activate(self) -> None:
        style = Adw.StyleManager.get_default()
        style.set_color_scheme(Adw.ColorScheme.FORCE_DARK)
        provider = Gtk.CssProvider()
        provider.load_from_data(STYLES)
        Gtk.StyleContext.add_provider_for_display(
            Gdk.Display.get_default(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )
        window = self.props.active_window
        if window is None:
            window = MainWindow(self)
        window.present()


def main() -> None:
    raise SystemExit(KachurApp().run(sys.argv))
