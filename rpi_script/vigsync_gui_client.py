
import base64
import hashlib
import json
import queue
import ssl
import threading
import time
import tkinter as tk
from dataclasses import dataclass
from datetime import datetime
from tkinter import filedialog, messagebox, scrolledtext, ttk
from typing import Callable

import paho.mqtt.client as mqtt
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


DEFAULT_STATUS_SUFFIX = "status"
DEFAULT_EVENTS_SUFFIX = "events"
MAX_EVENTS = 250
LOG_LIMIT = 500

WINDOW_BG = "#dbe4f0"
PHONE_BG = "#f4f7fb"
SURFACE = "#ffffff"
SURFACE_SOFT = "#eef3fb"
BORDER = "#d7dfeb"
TEXT_PRIMARY = "#18212f"
TEXT_SECONDARY = "#6a7688"
ACCENT = "#2563eb"
ACCENT_SOFT = "#dce9ff"
SUCCESS = "#1d8f5b"
SUCCESS_SOFT = "#def7e8"
WARN = "#c27803"
WARN_SOFT = "#fff2d8"
SMS = "#0f766e"
SMS_SOFT = "#d8f6f1"


@dataclass
class PairingConfig:
    broker_url: str
    port: int
    username: str = ""
    password: str = ""
    use_tls: bool = False
    shared_key: str = ""
    topic_prefix: str = "vigsync"
    device_name: str = ""
    device_id: str = ""

    @classmethod
    def from_dict(cls, data: dict) -> "PairingConfig":
        tls_value = (
            data.get("SSL/TLS")
            if "SSL/TLS" in data
            else data.get("SLL/TLS", data.get("use_tls", False))
        )
        return cls(
            broker_url=str(data.get("brokerUrl", data.get("broker_url", ""))).strip(),
            port=int(data.get("port", data.get("broker_port", 1883))),
            username=str(data.get("username", "")).strip(),
            password=str(data.get("password", "")).strip(),
            use_tls=bool(tls_value),
            shared_key=str(data.get("sharedKey", data.get("shared_key", ""))).strip(),
            topic_prefix=str(data.get("topicPrefix", data.get("topic_prefix", "vigsync"))).strip(),
            device_name=str(data.get("deviceName", data.get("device_name", ""))).strip(),
            device_id=str(data.get("deviceId", data.get("device_id", ""))).strip(),
        )


@dataclass
class EventViewModel:
    event_id: int
    topic: str
    event_type: str
    sender_name: str
    sender_id: str
    timestamp_text: str
    battery_text: str
    encrypted_data: str
    decrypted_data: str
    summary: str
    raw_payload: str
    is_status: bool
    source_label: str
    title_text: str
    body_text: str


class VigSyncDecryptor:
    IV_LENGTH = 12

    def __init__(self, shared_key: str) -> None:
        self.shared_key = shared_key.strip()

    def decrypt(self, encrypted_base64: str) -> str:
        if not self.shared_key:
            return encrypted_base64
        key_bytes = base64.b64decode(self.shared_key)
        payload = base64.b64decode(encrypted_base64)
        if len(payload) <= self.IV_LENGTH:
            raise ValueError("Encrypted payload is too short")
        iv = payload[: self.IV_LENGTH]
        ciphertext = payload[self.IV_LENGTH :]
        plain_bytes = AESGCM(key_bytes).decrypt(iv, ciphertext, None)
        return plain_bytes.decode("utf-8")


class DesktopNotifier:
    def __init__(self) -> None:
        self.available = False
        self._notification_cls = None
        self._audio = None
        self._import_error: str | None = None

        try:
            from winotify import Notification, audio

            self._notification_cls = Notification
            self._audio = audio
            self.available = True
        except Exception as exc:
            self._import_error = str(exc)

    @property
    def import_error(self) -> str | None:
        return self._import_error

    def notify(self, title: str, message: str) -> None:
        if not self.available or self._notification_cls is None:
            return
        try:
            toast = self._notification_cls(
                app_id="VigSync Client",
                title=title[:64],
                msg=message[:240],
                duration="short",
            )
            if self._audio is not None:
                toast.set_audio(self._audio.Default, loop=False)
            toast.show()
        except Exception:
            return


class TrayController:
    def __init__(
        self,
        on_open: Callable[[], None],
        on_toggle_connection: Callable[[], None],
        on_exit: Callable[[], None],
        is_connected: Callable[[], bool],
    ) -> None:
        self._on_open = on_open
        self._on_toggle_connection = on_toggle_connection
        self._on_exit = on_exit
        self._is_connected = is_connected
        self.available = False
        self._icon = None
        self._import_error: str | None = None

        try:
            import pystray
            from PIL import Image, ImageDraw

            self._pystray = pystray
            self._image_cls = Image
            self._image_draw_cls = ImageDraw
            self.available = True
        except Exception as exc:
            self._import_error = str(exc)

    @property
    def import_error(self) -> str | None:
        return self._import_error

    def start(self) -> bool:
        if not self.available or self._icon is not None:
            return False
        image = self._create_icon_image()
        menu = self._pystray.Menu(
            self._pystray.MenuItem("Open VigSync", lambda icon, item: self._on_open()),
            self._pystray.MenuItem(self._connection_label, lambda icon, item: self._on_toggle_connection()),
            self._pystray.MenuItem("Exit", lambda icon, item: self._on_exit()),
        )
        self._icon = self._pystray.Icon("vigsync_client", image, "VigSync Client", menu)
        self._icon.run_detached()
        return True

    def stop(self) -> None:
        if self._icon is None:
            return
        try:
            self._icon.stop()
        finally:
            self._icon = None

    def update_menu(self) -> None:
        if self._icon is None:
            return
        try:
            self._icon.update_menu()
        except Exception:
            return

    def _connection_label(self, _item) -> str:
        return "Disconnect" if self._is_connected() else "Connect"

    def _create_icon_image(self):
        image = self._image_cls.new("RGB", (64, 64), "#2563eb")
        draw = self._image_draw_cls.Draw(image)
        draw.rounded_rectangle((8, 8, 56, 56), radius=14, fill="#eff6ff")
        draw.text((20, 18), "V", fill="#2563eb")
        return image


class VigSyncGuiClient:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("VigSync Client")
        self.root.geometry("500x940")
        self.root.minsize(460, 820)
        self.root.configure(bg=WINDOW_BG)

        self.message_queue: queue.Queue[tuple[str, object]] = queue.Queue()
        self.client: mqtt.Client | None = None
        self.worker_thread: threading.Thread | None = None
        self.connected = False
        self.active_topics: list[str] = []
        self.events: list[EventViewModel] = []
        self.seen_message_keys: set[str] = set()
        self.log_lines: list[str] = []
        self.next_event_id = 1
        self.active_scroll_canvas: tk.Canvas | None = None
        self.feed_wrap_labels: list[tk.Widget] = []
        self.notifications_enabled = tk.BooleanVar(value=True)
        self.minimize_to_tray_enabled = tk.BooleanVar(value=True)
        self.tray_hint_shown = False
        self.exiting = False
        self.notifier = DesktopNotifier()
        self.tray_controller = TrayController(
            on_open=self.restore_from_tray,
            on_toggle_connection=self.toggle_connection_from_tray,
            on_exit=self.exit_from_tray,
            is_connected=lambda: self.connected,
        )

        self.total_events = 0
        self.notification_events = 0
        self.call_events = 0
        self.sms_events = 0
        self.current_page = "home"

        self._configure_styles()
        self._build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.root.bind("<Unmap>", self._on_window_unmap)
        self.root.after(150, self.process_queue)

    def _configure_styles(self) -> None:
        style = ttk.Style()
        style.theme_use("clam")
        style.configure(".", font=("Segoe UI", 10), background=PHONE_BG, foreground=TEXT_PRIMARY)
        style.configure("Primary.TButton", font=("Segoe UI Semibold", 10), padding=(14, 10), background=ACCENT, foreground="#ffffff", bordercolor=ACCENT)
        style.map("Primary.TButton", background=[("active", "#184fc4")])
        style.configure("Secondary.TButton", font=("Segoe UI Semibold", 10), padding=(14, 10), background=SURFACE, foreground=TEXT_PRIMARY, bordercolor=BORDER)
        style.map("Secondary.TButton", background=[("active", ACCENT_SOFT)])
        style.configure("TEntry", fieldbackground="#ffffff", bordercolor=BORDER)
        style.configure("TCheckbutton", background=SURFACE, foreground=TEXT_PRIMARY)

    def _build_ui(self) -> None:
        outer = tk.Frame(self.root, bg=WINDOW_BG, padx=0, pady=0)
        outer.pack(fill=tk.BOTH, expand=True)

        self.phone_shell = tk.Frame(
            outer,
            bg=PHONE_BG,
            highlightthickness=0,
            bd=0,
        )
        self.phone_shell.pack(fill=tk.BOTH, expand=True)
        self.root.bind_all("<MouseWheel>", self._on_mousewheel, add="+")

        topbar = tk.Frame(self.phone_shell, bg=PHONE_BG, height=28)
        topbar.pack(fill=tk.X, padx=18, pady=(10, 4))
        tk.Label(topbar, text="VigSync", bg=PHONE_BG, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 11)).pack(side=tk.LEFT)
        tk.Label(topbar, text="Client", bg=PHONE_BG, fg=TEXT_SECONDARY, font=("Segoe UI", 10)).pack(side=tk.RIGHT)

        self.content_host = tk.Frame(self.phone_shell, bg=PHONE_BG)
        self.content_host.pack(fill=tk.BOTH, expand=True, padx=14, pady=(0, 8))

        self.home_page = tk.Frame(self.content_host, bg=PHONE_BG)
        self.settings_page = tk.Frame(self.content_host, bg=PHONE_BG)
        self._build_home_page()
        self._build_settings_page()

        self.bottom_nav = tk.Frame(self.phone_shell, bg=SURFACE, height=72, highlightbackground=BORDER, highlightcolor=BORDER, highlightthickness=1)
        self.bottom_nav.pack(fill=tk.X, side=tk.BOTTOM)
        self.home_nav_button = self._build_nav_button(self.bottom_nav, "⌂", "Home", "home")
        self.settings_nav_button = self._build_nav_button(self.bottom_nav, "⚙", "Settings", "settings")
        self.show_page("home")
    def _build_nav_button(self, parent: tk.Frame, icon: str, label: str, page: str) -> tk.Frame:
        button = tk.Frame(parent, bg=SURFACE, cursor="hand2")
        button.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        icon_label = tk.Label(button, text=icon, bg=SURFACE, fg=TEXT_SECONDARY, font=("Segoe UI Symbol", 16))
        icon_label.pack(pady=(12, 0))
        text_label = tk.Label(button, text=label, bg=SURFACE, fg=TEXT_SECONDARY, font=("Segoe UI Semibold", 9))
        text_label.pack(pady=(2, 10))
        for widget in (button, icon_label, text_label):
            widget.bind("<Button-1>", lambda _event, target=page: self.show_page(target))
        button.icon_label = icon_label
        button.text_label = text_label
        return button

    def _build_home_page(self) -> None:
        header = tk.Frame(self.home_page, bg=PHONE_BG)
        header.pack(fill=tk.X, pady=(4, 14))
        tk.Label(header, text="Home", bg=PHONE_BG, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 22)).pack(anchor="w")
        tk.Label(header, text="Live stream from your VigSync server device", bg=PHONE_BG, fg=TEXT_SECONDARY, font=("Segoe UI", 10)).pack(anchor="w", pady=(4, 0))

        self.status_card = tk.Frame(self.home_page, bg=SURFACE, highlightbackground=BORDER, highlightcolor=BORDER, highlightthickness=1, padx=16, pady=16)
        self.status_card.pack(fill=tk.X)

        top = tk.Frame(self.status_card, bg=SURFACE)
        top.pack(fill=tk.X)
        tk.Label(top, text="Device Status", bg=SURFACE, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 12)).pack(side=tk.LEFT)
        self.connection_badge = tk.Label(top, text="Disconnected", bg="#f3f4f6", fg=TEXT_SECONDARY, font=("Segoe UI Semibold", 9), padx=10, pady=4)
        self.connection_badge.pack(side=tk.RIGHT)

        self.device_name_var = tk.StringVar(value="No server selected")
        self.device_meta_var = tk.StringVar(value="Open Settings to load pairing data")
        self.last_seen_var = tk.StringVar(value="No heartbeat yet")
        self.battery_var = tk.StringVar(value="Battery --")
        self.counts_var = tk.StringVar(value="0 alerts")

        tk.Label(self.status_card, textvariable=self.device_name_var, bg=SURFACE, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 17)).pack(anchor="w", pady=(16, 0))
        tk.Label(self.status_card, textvariable=self.device_meta_var, bg=SURFACE, fg=TEXT_SECONDARY, font=("Segoe UI", 10), wraplength=300, justify="left").pack(anchor="w", pady=(4, 0))

        chips = tk.Frame(self.status_card, bg=SURFACE)
        chips.pack(fill=tk.X, pady=(16, 0))
        self._build_status_chip(chips, self.battery_var).pack(side=tk.LEFT)
        self._build_status_chip(chips, self.counts_var).pack(side=tk.LEFT, padx=(8, 0))
        tk.Label(self.status_card, textvariable=self.last_seen_var, bg=SURFACE, fg=TEXT_SECONDARY, font=("Segoe UI", 10)).pack(anchor="w", pady=(14, 0))

        hero_actions = tk.Frame(self.home_page, bg=PHONE_BG)
        hero_actions.pack(fill=tk.X, pady=(14, 14))
        ttk.Button(hero_actions, text="Connect", style="Primary.TButton", command=self.connect).pack(side=tk.LEFT, fill=tk.X, expand=True)
        ttk.Button(hero_actions, text="Disconnect", style="Secondary.TButton", command=self.disconnect).pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(10, 0))

        feed_header = tk.Frame(self.home_page, bg=PHONE_BG)
        feed_header.pack(fill=tk.X, pady=(0, 10))
        header_left = tk.Frame(feed_header, bg=PHONE_BG)
        header_left.pack(side=tk.LEFT, fill=tk.X, expand=True)
        tk.Label(header_left, text="Recent Activity", bg=PHONE_BG, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 13)).pack(anchor="w")
        self.feed_subtitle = tk.Label(header_left, text="Incoming SMS, calls, and notifications", bg=PHONE_BG, fg=TEXT_SECONDARY, font=("Segoe UI", 10), justify="left", anchor="w")
        self.feed_subtitle.pack(anchor="w", pady=(2, 0))
        ttk.Button(feed_header, text="Clear All", style="Secondary.TButton", command=self.clear_all_events).pack(side=tk.RIGHT, padx=(10, 0))

        feed_shell = tk.Frame(self.home_page, bg=PHONE_BG)
        feed_shell.pack(fill=tk.BOTH, expand=True)
        self.feed_canvas = tk.Canvas(feed_shell, bg=PHONE_BG, bd=0, highlightthickness=0)
        self.feed_canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        feed_scrollbar = ttk.Scrollbar(feed_shell, orient=tk.VERTICAL, command=self.feed_canvas.yview)
        feed_scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.feed_canvas.configure(yscrollcommand=feed_scrollbar.set)

        self.feed_inner = tk.Frame(self.feed_canvas, bg=PHONE_BG)
        self.feed_window = self.feed_canvas.create_window((0, 0), window=self.feed_inner, anchor="nw")
        self.feed_inner.bind("<Configure>", self._on_feed_configure)
        self.feed_canvas.bind("<Configure>", self._on_feed_canvas_resize)
        self.feed_canvas.bind("<Enter>", lambda _event: self._set_active_scroll_canvas(self.feed_canvas))
        self.feed_canvas.bind("<Leave>", lambda _event: self._set_active_scroll_canvas(None))

        self.empty_feed_label = tk.Label(self.feed_inner, text="No alerts yet.\nWhen the phone captures messages, they will appear as cards here.", bg=PHONE_BG, fg=TEXT_SECONDARY, font=("Segoe UI", 11), justify="left", anchor="w", padx=8, pady=12, wraplength=10)
        self.empty_feed_label.pack(fill=tk.X)
        self.feed_wrap_labels.append(self.empty_feed_label)

    def _build_status_chip(self, parent: tk.Frame, text_var: tk.StringVar) -> tk.Label:
        return tk.Label(parent, textvariable=text_var, bg=SURFACE_SOFT, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 9), padx=10, pady=5)

    def _build_settings_page(self) -> None:
        header = tk.Frame(self.settings_page, bg=PHONE_BG)
        header.pack(fill=tk.X, pady=(4, 14))
        tk.Label(header, text="Settings", bg=PHONE_BG, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 22)).pack(anchor="w")
        tk.Label(header, text="Pair the client and manage the MQTT connection", bg=PHONE_BG, fg=TEXT_SECONDARY, font=("Segoe UI", 10)).pack(anchor="w", pady=(4, 0))

        self.settings_scroll_canvas = tk.Canvas(self.settings_page, bg=PHONE_BG, bd=0, highlightthickness=0)
        self.settings_scroll_canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        settings_scrollbar = ttk.Scrollbar(self.settings_page, orient=tk.VERTICAL, command=self.settings_scroll_canvas.yview)
        settings_scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.settings_scroll_canvas.configure(yscrollcommand=settings_scrollbar.set)

        self.settings_inner = tk.Frame(self.settings_scroll_canvas, bg=PHONE_BG)
        self.settings_window = self.settings_scroll_canvas.create_window((0, 0), window=self.settings_inner, anchor="nw")
        self.settings_inner.bind("<Configure>", self._on_settings_configure)
        self.settings_scroll_canvas.bind("<Configure>", self._on_settings_canvas_resize)
        self.settings_scroll_canvas.bind("<Enter>", lambda _event: self._set_active_scroll_canvas(self.settings_scroll_canvas))
        self.settings_scroll_canvas.bind("<Leave>", lambda _event: self._set_active_scroll_canvas(None))

        self.pairing_card = self._build_surface_card(self.settings_inner)
        self.pairing_card.pack(fill=tk.X, pady=(0, 12))
        tk.Label(self.pairing_card, text="Pairing JSON", bg=SURFACE, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 12)).pack(anchor="w")
        tk.Label(self.pairing_card, text="Paste the server QR payload or load it from a file.", bg=SURFACE, fg=TEXT_SECONDARY, font=("Segoe UI", 10), wraplength=290, justify="left").pack(anchor="w", pady=(4, 10))
        self.pairing_input = scrolledtext.ScrolledText(self.pairing_card, height=7, wrap=tk.WORD, font=("Cascadia Code", 9), bg="#fbfcfe", fg=TEXT_PRIMARY, relief="flat", highlightthickness=1, highlightbackground=BORDER)
        self.pairing_input.pack(fill=tk.X)
        pairing_actions = tk.Frame(self.pairing_card, bg=SURFACE)
        pairing_actions.pack(fill=tk.X, pady=(10, 0))
        ttk.Button(pairing_actions, text="Load File", style="Secondary.TButton", command=self.load_pairing_file).pack(side=tk.LEFT, fill=tk.X, expand=True)
        ttk.Button(pairing_actions, text="Apply", style="Primary.TButton", command=self.apply_pairing_json).pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(10, 0))

        self.config_card = self._build_surface_card(self.settings_inner)
        self.config_card.pack(fill=tk.X, pady=(0, 12))
        tk.Label(self.config_card, text="Connection", bg=SURFACE, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 12)).pack(anchor="w", pady=(0, 10))

        self.broker_var = tk.StringVar()
        self.port_var = tk.StringVar(value="1883")
        self.username_var = tk.StringVar()
        self.password_var = tk.StringVar()
        self.tls_var = tk.BooleanVar(value=False)
        self.topic_prefix_var = tk.StringVar(value="vigsync")
        self.shared_key_var = tk.StringVar()
        self.server_device_name_var = tk.StringVar()
        self.server_device_id_var = tk.StringVar()
        self._build_entry_field(self.config_card, "Broker", self.broker_var)
        self._build_entry_field(self.config_card, "Port", self.port_var)
        self._build_entry_field(self.config_card, "Username", self.username_var)
        self._build_entry_field(self.config_card, "Password", self.password_var, show="*")
        self._build_entry_field(self.config_card, "Topic Prefix", self.topic_prefix_var)
        self._build_entry_field(self.config_card, "Server Device Name", self.server_device_name_var)
        self._build_entry_field(self.config_card, "Server Device ID", self.server_device_id_var)
        self._build_entry_field(self.config_card, "Shared Key", self.shared_key_var, show="*")
        ttk.Checkbutton(self.config_card, text="Use SSL/TLS", variable=self.tls_var).pack(anchor="w", pady=(4, 0))

        self.log_card = self._build_surface_card(self.settings_inner)
        self.log_card.pack(fill=tk.BOTH, expand=True, pady=(0, 12))
        tk.Label(self.log_card, text="Activity Log", bg=SURFACE, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 12)).pack(anchor="w")
        tk.Label(self.log_card, text="Technical diagnostics stay here so the Home screen stays simple.", bg=SURFACE, fg=TEXT_SECONDARY, font=("Segoe UI", 10), wraplength=290, justify="left").pack(anchor="w", pady=(4, 10))
        self.log_output = scrolledtext.ScrolledText(self.log_card, height=10, wrap=tk.WORD, font=("Cascadia Code", 9), bg="#fbfcfe", fg=TEXT_PRIMARY, relief="flat", highlightthickness=1, highlightbackground=BORDER)
        self.log_output.pack(fill=tk.BOTH, expand=True)
        ttk.Button(self.log_card, text="Clear Log", style="Secondary.TButton", command=self.clear_log).pack(fill=tk.X, pady=(10, 0))

        self.behavior_card = self._build_surface_card(self.settings_inner)
        self.behavior_card.pack(fill=tk.X, pady=(0, 12))
        tk.Label(self.behavior_card, text="Background Behavior", bg=SURFACE, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 12)).pack(anchor="w")
        tk.Label(self.behavior_card, text="Control desktop notifications and tray behavior.", bg=SURFACE, fg=TEXT_SECONDARY, font=("Segoe UI", 10), wraplength=290, justify="left").pack(anchor="w", pady=(4, 10))
        ttk.Checkbutton(self.behavior_card, text="Desktop notifications for new alerts", variable=self.notifications_enabled).pack(anchor="w", pady=(0, 6))
        ttk.Checkbutton(self.behavior_card, text="Minimize to system tray", variable=self.minimize_to_tray_enabled).pack(anchor="w")

    def _build_surface_card(self, parent: tk.Frame) -> tk.Frame:
        return tk.Frame(parent, bg=SURFACE, highlightbackground=BORDER, highlightcolor=BORDER, highlightthickness=1, padx=14, pady=14)

    def _build_entry_field(self, parent: tk.Frame, label: str, variable: tk.StringVar, show: str | None = None) -> None:
        wrapper = tk.Frame(parent, bg=SURFACE)
        wrapper.pack(fill=tk.X, pady=(0, 10))
        tk.Label(wrapper, text=label, bg=SURFACE, fg=TEXT_SECONDARY, font=("Segoe UI Semibold", 9)).pack(anchor="w", pady=(0, 5))
        ttk.Entry(wrapper, textvariable=variable, show=show or "").pack(fill=tk.X)

    def show_page(self, page_name: str) -> None:
        self.current_page = page_name
        for page in (self.home_page, self.settings_page):
            page.pack_forget()
        if page_name == "settings":
            self.settings_page.pack(fill=tk.BOTH, expand=True)
        else:
            self.home_page.pack(fill=tk.BOTH, expand=True)
        self._refresh_nav_state()

    def _refresh_nav_state(self) -> None:
        for name, button in (("home", self.home_nav_button), ("settings", self.settings_nav_button)):
            active = name == self.current_page
            bg = ACCENT_SOFT if active else SURFACE
            fg = ACCENT if active else TEXT_SECONDARY
            button.configure(bg=bg)
            button.icon_label.configure(bg=bg, fg=fg)
            button.text_label.configure(bg=bg, fg=fg)

    def load_pairing_file(self) -> None:
        path = filedialog.askopenfilename(title="Open Pairing JSON", filetypes=[("JSON Files", "*.json"), ("Text Files", "*.txt"), ("All Files", "*.*")])
        if not path:
            return
        with open(path, "r", encoding="utf-8") as handle:
            self.pairing_input.delete("1.0", tk.END)
            self.pairing_input.insert(tk.END, handle.read())

    def apply_pairing_json(self) -> None:
        raw_text = self.pairing_input.get("1.0", tk.END).strip()
        if not raw_text:
            messagebox.showerror("Missing JSON", "Paste the pairing JSON first.")
            return
        try:
            data = json.loads(raw_text)
            config = PairingConfig.from_dict(data)
        except Exception as exc:
            messagebox.showerror("Invalid JSON", f"Could not parse pairing JSON:\n{exc}")
            return

        self.broker_var.set(config.broker_url)
        self.port_var.set(str(config.port))
        self.username_var.set(config.username)
        self.password_var.set(config.password)
        self.tls_var.set(config.use_tls)
        self.topic_prefix_var.set(config.topic_prefix)
        self.server_device_name_var.set(config.device_name)
        self.server_device_id_var.set(config.device_id)
        if config.shared_key:
            self.shared_key_var.set(config.shared_key)

        self.device_name_var.set(config.device_name or "Server ready")
        self.device_meta_var.set(config.device_id or "Pairing applied")
        self.enqueue_log("INFO", "Pairing JSON applied to settings.")

    def build_config(self) -> PairingConfig:
        broker_url = self.broker_var.get().strip()
        for scheme in ("mqtt://", "tcp://", "ssl://"):
            if broker_url.lower().startswith(scheme):
                broker_url = broker_url[len(scheme):]
        broker_url = broker_url.rstrip("/")
        return PairingConfig(
            broker_url=broker_url,
            port=int(self.port_var.get().strip()),
            username=self.username_var.get().strip(),
            password=self.password_var.get().strip(),
            use_tls=self.tls_var.get(),
            shared_key=self.shared_key_var.get().strip(),
            topic_prefix=self.topic_prefix_var.get().strip(),
            device_name=self.server_device_name_var.get().strip(),
            device_id=self.server_device_id_var.get().strip(),
        )

    def connect(self) -> None:
        if self.connected:
            self.enqueue_log("INFO", "Client is already connected.")
            return
        try:
            config = self.build_config()
        except Exception as exc:
            messagebox.showerror("Invalid configuration", f"Fix the client settings first:\n{exc}")
            return
        if not config.broker_url or not config.topic_prefix or not config.device_id:
            messagebox.showerror("Missing fields", "Broker, topic prefix, and server device ID are required.")
            return

        self._set_connection_badge("Connecting", ACCENT_SOFT, ACCENT)
        self.last_seen_var.set(f"Connecting to {config.broker_url}:{config.port}")
        self.worker_thread = threading.Thread(target=self._connect_worker, args=(config,), daemon=True)
        self.worker_thread.start()

    def _connect_worker(self, config: PairingConfig) -> None:
        client_id = f"vigsync_gui_{int(time.time())}"
        client = mqtt.Client(client_id=client_id, clean_session=True)
        client.reconnect_delay_set(min_delay=1, max_delay=30)
        client.max_inflight_messages_set(20)
        client.max_queued_messages_set(500)
        if config.username or config.password:
            client.username_pw_set(config.username, config.password)
        if config.use_tls:
            client.tls_set(cert_reqs=ssl.CERT_REQUIRED, tls_version=ssl.PROTOCOL_TLS_CLIENT)

        client.on_connect = self._on_connect
        client.on_disconnect = self._on_disconnect
        client.on_message = self._on_message
        self.client = client
        self.active_topics = [
            f"{config.topic_prefix}/{DEFAULT_EVENTS_SUFFIX}/{config.device_id}",
            f"{config.topic_prefix}/{DEFAULT_STATUS_SUFFIX}/{config.device_id}",
        ]
        try:
            client.connect(config.broker_url, config.port, keepalive=60)
            client.loop_forever()
        except Exception as exc:
            self.message_queue.put(("status", "Disconnected"))
            self.message_queue.put(("log", self.format_log("ERROR", f"Connection failed: {exc}")))
    def disconnect(self) -> None:
        if self.client is None:
            return
        try:
            self.client.disconnect()
        except Exception as exc:
            self.enqueue_log("ERROR", f"Disconnect failed: {exc}")

    def _on_connect(self, client: mqtt.Client, _userdata, _flags, rc: int) -> None:
        if rc != 0:
            self.message_queue.put(("status", f"Connect failed (rc={rc})"))
            self.message_queue.put(("log", self.format_log("ERROR", f"MQTT connect rejected with rc={rc}")))
            return
        for topic in self.active_topics:
            result, mid = client.subscribe(topic, qos=1)
            if result != mqtt.MQTT_ERR_SUCCESS:
                self.message_queue.put(("log", self.format_log("ERROR", f"Subscribe failed for {topic} rc={result}")))
            else:
                self.message_queue.put(("log", self.format_log("INFO", f"Subscribe requested for {topic} mid={mid}")))
        self.connected = True
        self.message_queue.put(("status", "Connected"))
        self.message_queue.put(("log", self.format_log("INFO", f"Connected and subscribed to: {', '.join(self.active_topics)}")))

    def _on_disconnect(self, _client: mqtt.Client, _userdata, rc: int) -> None:
        self.connected = False
        message = "Disconnected" if rc == 0 else f"Disconnected unexpectedly (rc={rc})"
        self.message_queue.put(("status", message))
        self.message_queue.put(("log", self.format_log("WARNING", message)))

    def _on_message(self, _client: mqtt.Client, _userdata, message: mqtt.MQTTMessage) -> None:
        try:
            payload_text = message.payload.decode("utf-8")
            message_key = hashlib.sha256(f"{message.topic}\0{payload_text}".encode("utf-8", errors="replace")).hexdigest()
            if message_key in self.seen_message_keys:
                self.message_queue.put(("log", self.format_log("TRACE", f"Duplicate message ignored on {message.topic}")))
                return
            self.seen_message_keys.add(message_key)
            if len(self.seen_message_keys) > MAX_EVENTS * 4:
                self.seen_message_keys = set(list(self.seen_message_keys)[-MAX_EVENTS * 2:])
            raw_message = json.loads(payload_text)
            event = self.build_event_view_model(message.topic, raw_message, payload_text)
            self.message_queue.put(("event", event))
        except Exception as exc:
            self.message_queue.put(("log", self.format_log("ERROR", f"Failed to parse message on {message.topic}: {exc}")))

    def build_event_view_model(self, topic: str, raw_message: dict, payload_text: str) -> EventViewModel:
        decryptor = VigSyncDecryptor(self.shared_key_var.get())
        encrypted_data = raw_message.get("data") or ""
        decrypted_data = ""
        if encrypted_data:
            try:
                decrypted_data = decryptor.decrypt(encrypted_data)
            except Exception as exc:
                decrypted_data = f"[DECRYPTION FAILED] {exc}"

        event_type = raw_message.get("type") or "STATUS"
        sender_name = raw_message.get("senderName") or raw_message.get("sender_name") or "Unknown"
        sender_id = raw_message.get("senderId") or raw_message.get("sender_id") or "Unknown"
        battery_level = raw_message.get("batteryLevel")
        battery_text = f"{battery_level}%" if battery_level is not None else "--"
        timestamp_text = self.format_timestamp(raw_message.get("timestamp"))
        source_label, title_text, body_text = self._parse_event_content(event_type, decrypted_data, sender_name)
        summary_source = body_text or title_text or decrypted_data or encrypted_data or "Heartbeat received"
        summary = summary_source.replace("\n", " ")
        if len(summary) > 120:
            summary = summary[:117] + "..."
        event_id = self.next_event_id
        self.next_event_id += 1
        return EventViewModel(
            event_id=event_id,
            topic=topic,
            event_type=event_type,
            sender_name=sender_name,
            sender_id=sender_id,
            timestamp_text=timestamp_text,
            battery_text=battery_text,
            encrypted_data=encrypted_data,
            decrypted_data=decrypted_data,
            summary=summary,
            raw_payload=payload_text,
            is_status=event_type.upper() == "STATUS",
            source_label=source_label,
            title_text=title_text,
            body_text=body_text,
        )

    def _parse_event_content(self, event_type: str, decrypted_data: str, fallback_sender: str) -> tuple[str, str, str]:
        upper = event_type.upper()
        source_label = fallback_sender
        title_text = self._event_title_from_type(event_type)
        body_text = decrypted_data.strip() if decrypted_data else ""

        if "MISSED" in upper and "CALL" in upper:
            caller = self._extract_after_markers(decrypted_data, ["From:", "from "])
            title_text = "Missed call"
            body_text = caller or "Someone tried to reach you."
            source_label = "Phone"
            return source_label, title_text, body_text

        if "CALL" in upper:
            call_text = self._extract_after_markers(decrypted_data, ["From:", "from ", "Active Call:"])
            title_text = "Call activity"
            body_text = call_text or decrypted_data.strip() or "Call event detected."
            source_label = self._extract_source_name(decrypted_data, "Phone")
            return source_label, title_text, body_text

        if "SMS" in upper:
            sms_text = decrypted_data.strip()
            source_label = "SMS"
            title_text = "Text message"
            if sms_text.startswith("[") and "]" in sms_text:
                sms_text = sms_text.split("]", 1)[1].strip()
            body_text = sms_text
            return source_label, title_text, body_text

        source_label, body_text = self._parse_notification_payload(decrypted_data, fallback_sender)
        title_text = source_label
        return source_label, title_text, body_text

    def _parse_notification_payload(self, decrypted_data: str, fallback_sender: str) -> tuple[str, str]:
        if not decrypted_data:
            return fallback_sender, "Notification received."

        parts = decrypted_data.split("|", 2)
        if len(parts) == 3:
            app_name, package_name, message_blob = parts
            source_label = self._humanize_source_label(app_name, package_name)
            message_text = self._clean_notification_text(message_blob)
            return source_label, message_text

        return fallback_sender, decrypted_data.strip()

    def _humanize_source_label(self, app_name: str, package_name: str) -> str:
        package_map = {
            "com.whatsapp": "WhatsApp",
            "org.telegram.messenger": "Telegram",
            "com.facebook.orca": "Messenger",
            "com.instagram.android": "Instagram",
            "com.google.android.apps.messaging": "Messages",
            "com.samsung.android.messaging": "Messages",
            "com.android.server.telecom": "Phone",
            "com.google.android.dialer": "Phone",
            "com.samsung.android.dialer": "Phone",
        }
        if package_name in package_map:
            return package_map[package_name]
        cleaned = app_name.strip()
        if cleaned and not cleaned.startswith("com."):
            return cleaned
        if package_name:
            tail = package_name.split(".")[-1]
            return tail.replace("_", " ").title()
        return "App alert"

    def _clean_notification_text(self, message_blob: str) -> str:
        text = message_blob.strip()
        if ": " in text:
            sender, message = text.split(": ", 1)
            sender = sender.strip()
            message = message.strip()
            if sender and message:
                return f"{sender}\n{message}"
        return text

    def _extract_source_name(self, decrypted_data: str, fallback: str) -> str:
        if not decrypted_data:
            return fallback
        parts = decrypted_data.split("|", 2)
        if len(parts) >= 2:
            return self._humanize_source_label(parts[0], parts[1])
        return fallback

    def _extract_after_markers(self, text: str, markers: list[str]) -> str:
        if not text:
            return ""
        for marker in markers:
            if marker in text:
                return text.split(marker, 1)[1].strip()
        return text.strip()

    def _event_title_from_type(self, event_type: str) -> str:
        upper = event_type.upper()
        if "SMS" in upper:
            return "Text message"
        if "MISSED" in upper and "CALL" in upper:
            return "Missed call"
        if "CALL" in upper:
            return "Call activity"
        return "Notification"

    def format_timestamp(self, timestamp_ms) -> str:
        try:
            if timestamp_ms is None:
                return "N/A"
            return datetime.fromtimestamp(int(timestamp_ms) / 1000).strftime("%Y-%m-%d %H:%M:%S")
        except Exception:
            return str(timestamp_ms)

    def process_queue(self) -> None:
        try:
            while True:
                event_type, payload = self.message_queue.get_nowait()
                if event_type == "status":
                    self.update_connection_state(str(payload))
                elif event_type == "log":
                    self.append_log(str(payload))
                elif event_type == "event":
                    self.handle_incoming_event(payload)
        except queue.Empty:
            pass
        finally:
            self.root.after(150, self.process_queue)

    def handle_incoming_event(self, event: object) -> None:
        if not isinstance(event, EventViewModel):
            return
        self.append_log(self.format_log("EVENT", self.render_event_log(event)))
        self.device_name_var.set(event.sender_name)
        self.device_meta_var.set(event.sender_id)
        self.battery_var.set(f"Battery {event.battery_text}")
        self.last_seen_var.set(f"Last seen {event.timestamp_text}")

        if event.is_status:
            return

        self.events.insert(0, event)
        if len(self.events) > MAX_EVENTS:
            self.events.pop()

        self.total_events += 1
        upper = event.event_type.upper()
        if "SMS" in upper:
            self.sms_events += 1
        elif "CALL" in upper:
            self.call_events += 1
        else:
            self.notification_events += 1

        self.counts_var.set(f"{self.total_events} alerts")
        self.feed_subtitle.configure(text=self._build_feed_summary())
        self.render_event_cards()
        self.notify_for_event(event)

    def _build_feed_summary(self) -> str:
        return f"{self.notification_events} notifications • {self.call_events} calls • {self.sms_events} SMS"

    def render_event_cards(self) -> None:
        for child in self.feed_inner.winfo_children():
            child.destroy()
        self.feed_wrap_labels.clear()

        if not self.events:
            self.empty_feed_label = tk.Label(self.feed_inner, text="No alerts yet.\nWhen the phone captures messages, they will appear as cards here.", bg=PHONE_BG, fg=TEXT_SECONDARY, font=("Segoe UI", 11), justify="left", anchor="w", padx=8, pady=12, wraplength=10)
            self.empty_feed_label.pack(fill=tk.X)
            self.feed_wrap_labels.append(self.empty_feed_label)
            return

        for event in self.events:
            card = self._build_event_card(self.feed_inner, event)
            card.pack(fill=tk.X, pady=(0, 10))
        self._update_feed_wraplengths(self.feed_canvas.winfo_width())

    def _build_event_card(self, parent: tk.Frame, event: EventViewModel) -> tk.Frame:
        accent, accent_soft = self._event_colors(event.event_type)
        card = tk.Frame(parent, bg=SURFACE, highlightbackground=BORDER, highlightcolor=BORDER, highlightthickness=1, padx=14, pady=14)

        top = tk.Frame(card, bg=SURFACE)
        top.pack(fill=tk.X)
        left = tk.Frame(top, bg=SURFACE)
        left.pack(side=tk.LEFT, fill=tk.X, expand=True)

        icon_text = self._event_icon(event.event_type)
        icon = tk.Label(left, text=icon_text, bg=accent_soft, fg=accent, font=("Segoe UI Symbol", 14), padx=10, pady=8)
        icon.pack(side=tk.LEFT)

        title_block = tk.Frame(left, bg=SURFACE)
        title_block.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(10, 0))
        tk.Label(title_block, text=event.title_text, bg=SURFACE, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 11)).pack(anchor="w")
        source_label = tk.Label(title_block, text=event.source_label, bg=SURFACE, fg=TEXT_SECONDARY, font=("Segoe UI Semibold", 9), justify="left", anchor="w", wraplength=10)
        source_label.pack(anchor="w", pady=(2, 0))
        tk.Label(title_block, text=event.timestamp_text, bg=SURFACE, fg=TEXT_SECONDARY, font=("Segoe UI", 9)).pack(anchor="w", pady=(2, 0))

        right = tk.Frame(top, bg=SURFACE)
        right.pack(side=tk.RIGHT)
        tk.Label(right, text=self._event_badge(event.event_type), bg=accent_soft, fg=accent, font=("Segoe UI Semibold", 9), padx=10, pady=4).pack(side=tk.LEFT)
        remove_button = tk.Label(right, text="✕", bg=SURFACE, fg=TEXT_SECONDARY, font=("Segoe UI Semibold", 11), cursor="hand2", padx=8)
        remove_button.pack(side=tk.LEFT, padx=(6, 0))
        remove_button.bind("<Button-1>", lambda _event, event_id=event.event_id: self.remove_event(event_id))

        message = event.body_text or "Encrypted content received."
        message_label = tk.Label(card, text=message, bg=SURFACE, fg=TEXT_PRIMARY, font=("Segoe UI Semibold", 10), wraplength=10, justify="left", anchor="w")
        message_label.pack(anchor="w", fill=tk.X, pady=(14, 0))
        self.feed_wrap_labels.extend([source_label, message_label])
        return card

    def remove_event(self, event_id: int) -> None:
        self.events = [event for event in self.events if event.event_id != event_id]
        self._recalculate_event_counters()
        self.render_event_cards()

    def clear_all_events(self) -> None:
        self.events.clear()
        self._recalculate_event_counters()
        self.render_event_cards()

    def _recalculate_event_counters(self) -> None:
        self.total_events = len(self.events)
        self.notification_events = 0
        self.call_events = 0
        self.sms_events = 0
        for event in self.events:
            upper = event.event_type.upper()
            if "SMS" in upper:
                self.sms_events += 1
            elif "CALL" in upper:
                self.call_events += 1
            else:
                self.notification_events += 1
        self.counts_var.set(f"{self.total_events} alerts")
        self.feed_subtitle.configure(text=self._build_feed_summary())

    def _event_badge(self, event_type: str) -> str:
        upper = event_type.upper()
        if "SMS" in upper:
            return "SMS"
        if "MISSED" in upper and "CALL" in upper:
            return "Missed"
        if "CALL" in upper:
            return "Call"
        return "Alert"

    def _event_icon(self, event_type: str) -> str:
        upper = event_type.upper()
        if "SMS" in upper:
            return "✉"
        if "MISSED" in upper and "CALL" in upper:
            return "✕"
        if "CALL" in upper:
            return "☎"
        return "◔"

    def _event_colors(self, event_type: str) -> tuple[str, str]:
        upper = event_type.upper()
        if "SMS" in upper:
            return SMS, SMS_SOFT
        if "MISSED" in upper and "CALL" in upper:
            return "#c2410c", "#ffedd5"
        if "CALL" in upper:
            return WARN, WARN_SOFT
        return ACCENT, ACCENT_SOFT

    def update_connection_state(self, text: str) -> None:
        if text == "Connected":
            self._set_connection_badge("Connected", SUCCESS_SOFT, SUCCESS)
        elif text.startswith("Connect failed"):
            self._set_connection_badge("Failed", WARN_SOFT, WARN)
        elif text.startswith("Disconnected unexpectedly"):
            self._set_connection_badge("Interrupted", WARN_SOFT, WARN)
        else:
            self._set_connection_badge("Disconnected", "#f3f4f6", TEXT_SECONDARY)

    def _set_connection_badge(self, text: str, bg: str, fg: str) -> None:
        self.connection_badge.configure(text=text, bg=bg, fg=fg)
        self.tray_controller.update_menu()

    def notify_for_event(self, event: EventViewModel) -> None:
        if not self.notifications_enabled.get():
            return
        title = f"{event.source_label} • {event.title_text}"
        body = event.body_text or event.summary or "New alert received."
        self.notifier.notify(title, body)

    def render_event_log(self, event: EventViewModel) -> str:
        lines = [
            f"Topic: {event.topic}",
            f"Type: {event.event_type}",
            f"Sender: {event.sender_name} ({event.sender_id})",
            f"Source: {event.source_label}",
            f"Title: {event.title_text}",
            f"Timestamp: {event.timestamp_text}",
            f"Battery: {event.battery_text}",
        ]
        if event.body_text:
            lines.append(f"Body: {event.body_text}")
        if event.encrypted_data:
            lines.append(f"Encrypted data: {event.encrypted_data}")
        if event.decrypted_data:
            lines.append(f"Decrypted data: {event.decrypted_data}")
        return "\n".join(lines)

    def _on_feed_configure(self, _event=None) -> None:
        self.feed_canvas.configure(scrollregion=self.feed_canvas.bbox("all"))

    def _on_feed_canvas_resize(self, event) -> None:
        self.feed_canvas.itemconfigure(self.feed_window, width=event.width)
        self._update_feed_wraplengths(event.width)

    def _on_settings_configure(self, _event=None) -> None:
        self.settings_scroll_canvas.configure(scrollregion=self.settings_scroll_canvas.bbox("all"))

    def _on_settings_canvas_resize(self, event) -> None:
        self.settings_scroll_canvas.itemconfigure(self.settings_window, width=event.width)

    def _set_active_scroll_canvas(self, canvas: tk.Canvas | None) -> None:
        self.active_scroll_canvas = canvas

    def _update_feed_wraplengths(self, canvas_width: int) -> None:
        wrap = max(120, canvas_width - 56)
        for label in self.feed_wrap_labels:
            try:
                label.configure(wraplength=wrap)
            except tk.TclError:
                continue

    def _on_mousewheel(self, event) -> None:
        if self.active_scroll_canvas is None:
            return
        delta = 0
        if event.delta:
            delta = int(-event.delta / 120)
        if delta == 0:
            return
        self.active_scroll_canvas.yview_scroll(delta, "units")

    def hide_to_tray(self) -> None:
        if not self.minimize_to_tray_enabled.get():
            return
        started = self.tray_controller.start()
        if started and not self.tray_hint_shown:
            self.tray_hint_shown = True
            self.notifier.notify("VigSync Client", "App minimized to tray. Use the tray icon to reopen it.")
        self.root.withdraw()

    def restore_from_tray(self) -> None:
        self.root.after(0, self._restore_window)

    def _restore_window(self) -> None:
        self.root.deiconify()
        self.root.state("normal")
        self.root.lift()
        self.root.focus_force()

    def toggle_connection_from_tray(self) -> None:
        self.root.after(0, self._toggle_connection_ui_thread)

    def _toggle_connection_ui_thread(self) -> None:
        if self.connected:
            self.disconnect()
        else:
            self.connect()

    def exit_from_tray(self) -> None:
        self.root.after(0, self._exit_application)

    def _exit_application(self) -> None:
        self.exiting = True
        self.tray_controller.stop()
        try:
            self.disconnect()
        finally:
            self.root.destroy()

    def _on_window_unmap(self, _event=None) -> None:
        if self.exiting or not self.minimize_to_tray_enabled.get():
            return
        if self.root.state() == "iconic":
            self.hide_to_tray()

    def enqueue_log(self, level: str, message: str) -> None:
        self.message_queue.put(("log", self.format_log(level, message)))

    def format_log(self, level: str, message: str) -> str:
        stamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        return f"[{stamp}] [{level}]\n{message}\n\n"

    def append_log(self, text: str) -> None:
        self.log_lines.append(text)
        if len(self.log_lines) > LOG_LIMIT:
            self.log_lines = self.log_lines[-LOG_LIMIT:]
        self.log_output.configure(state=tk.NORMAL)
        self.log_output.delete("1.0", tk.END)
        self.log_output.insert(tk.END, "".join(self.log_lines))
        self.log_output.see(tk.END)
        self.log_output.configure(state=tk.DISABLED)

    def clear_log(self) -> None:
        self.log_lines.clear()
        self.log_output.configure(state=tk.NORMAL)
        self.log_output.delete("1.0", tk.END)
        self.log_output.configure(state=tk.DISABLED)

    def on_close(self) -> None:
        if self.minimize_to_tray_enabled.get() and not self.exiting:
            self.hide_to_tray()
            return
        self._exit_application()


def main() -> None:
    root = tk.Tk()
    app = VigSyncGuiClient(root)
    app.enqueue_log("INFO", "Open Settings, apply the pairing JSON, add the shared key if needed, then connect.")
    root.mainloop()


if __name__ == "__main__":
    main()
