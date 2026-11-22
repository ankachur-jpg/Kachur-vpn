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
        self.profile.set_text("amn0")
        self.profile.set_max_length(15)
        self.profile.set_placeholder_text("Например, amn0")
        self.profile.add_css_class("kachur-entry")
        self.profile.connect("activate", lambda *_: self.refresh())
        profile_card.append(self.profile)
        profile_card.append(label(
            "Пока используется уже настроенный профиль /etc/amneziawg/<имя>.conf. "
            "Импорт файлов и менеджер серверов добавим в следующем этапе.",
            "kachur-subtitle",
        ))
        body.append(profile_card)

        stats = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        body.append(stats)
        self.rx = self.add_stat(stats, "Получено", "0 Б")
        self.tx = self.add_stat(stats, "Отправлено", "0 Б")
        self.handshake = self.add_stat(stats, "Handshake", "Пока нет")

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
        if not self.busy:
            self.refresh()
        return GLib.SOURCE_CONTINUE

    def refresh(self, *_args: object) -> None:
        if self.busy or self._refresh_running:
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
            GLib.idle_add(self._show_state, name, state, error)

        Thread(target=worker, daemon=True).start()

    def _show_state(self, name: str, state: VPNState | None, error: str) -> bool:
        self._refresh_running = False
        if self.profile.get_text().strip() != name:
            return GLib.SOURCE_REMOVE
        if error:
            self.status.set_text("Статус недоступен")
            self.message.set_text(error)
            self.power.set_sensitive(False)
            return GLib.SOURCE_REMOVE
        assert state is not None
        self.current_state = state
        self.status.set_text(("Подключено • handshake подтверждён" if state.last_handshake else "Туннель запущен • handshake не проверен") if state.active else "VPN выключен")
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
        self.handshake.set_text(pretty_handshake(state.last_handshake))
        self.message.set_text("")
        self.power.set_sensitive(not self.busy)
        return GLib.SOURCE_REMOVE

    def on_toggle(self, *_args: object) -> None:
        if self.busy:
            return
        try:
            name = check_interface(self.profile.get_text())
        except VPNError as exc:
            self.message.set_text(str(exc))
            return
        connect = not self.current_state.active
        self.busy = True
        self.power.set_sensitive(False)
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
