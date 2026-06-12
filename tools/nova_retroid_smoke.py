#!/usr/bin/env python3
"""Retroid-first Nova smoke automation.

This script keeps the manual Retroid receipts repeatable without requiring any
third-party Python packages. It can run a safe Library rail check, inspect an
already-active Command Center, or drive the full launch -> stream -> Command
Center -> End flow used for Nova 1.1.0 polish passes.
"""

from __future__ import annotations

import argparse
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from typing import Iterable, Sequence

DEFAULT_SERIAL = os.environ.get("NOVA_ADB_SERIAL") or os.environ.get("RETROID_ID")
DEFAULT_PACKAGE = os.environ.get("NOVA_PACKAGE", "com.papi.nova.debug")
DEFAULT_ACTIVITY = os.environ.get(
    "NOVA_LIBRARY_ACTIVITY",
    "com.papi.nova.ui.NovaLibraryActivity",
)
DEFAULT_ARTIFACT_DIR = Path(os.environ.get("NOVA_SMOKE_ARTIFACT_DIR", "/tmp"))
DEFAULT_REPO = Path(__file__).resolve().parents[1]
DEFAULT_APK = Path(
    "app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk"
)
NOVA_PACKAGE_PREFIX = "com.papi.nova"
DRAWER_FIRST_LIBRARY_REQUIRED_LABELS = (
    "Library Options",
    "System",
)
OBSOLETE_LIBRARY_RAIL_LABELS = (
    "Search this library",
    "All",
    "Recent",
    "Sources",
    "HDR",
    "More",
)
HUD_LABELS = (
    "FPS",
    "RTT",
    "Bitrate",
    "Codec",
    "Resolution",
    "HOST",
)
DRAWER_FIRST_LIBRARY_CONTENT_LABELS = (
    "Build your library",
    "No games found",
    "Continue playing",
    "Recently played",
)
COMMAND_CENTER_LABELS = (
    "Quick Keys",
    "ESC",
    "Alt + Enter",
    "Alt + F4",
    "F11",
    "Insert",
    "Stats Overlay",
    "End session",
    "Disconnect",
)
TOUCH_CONTROLS_CAPTION = "On-screen overlay; physical gamepad stays active."
PHONE_DASHBOARD_LABELS = (
    "Nova",
    "Settings",
    "Servers",
    "Library",
    "Setup & Server Management",
    "Add Server",
    "Scan Pair",
    "Hosts",
)
PHONE_SETTINGS_LABELS = (
    "Settings",
    "Search settings",
    "Quality Preset",
    "Video resolution",
    "Video frame rate",
    "Client Stream Defaults",
    "A Select",
    "B Back",
)
PHONE_LIBRARY_LABELS = (
    "Library",
    "Library Options",
    "System",
    "A Select",
    "B Back",
)
PHONE_LIBRARY_BASE_FORBIDDEN_FILTER_LABELS = (
    "Search this library",
    "Sources",
    "HDR",
    "Sort",
    "Layout",
    "Density",
)
PHONE_LIBRARY_OPTIONS_DRAWER_LABELS = (
    "Library Options",
    "Search this library",
    "Refresh",
    "All",
    "Recent",
    "Sources",
    "Sort",
    "Layout",
)
PHONE_LIBRARY_OPTIONS_FORBIDDEN_SYSTEM_LABELS = (
    "Switch",
    "Settings",
    "Diagnostics",
    "About",
)
PHONE_SYSTEM_DRAWER_LABELS = (
    "System",
    "Switch",
    "Settings",
    "Diagnostics",
    "About",
)
PHONE_SYSTEM_FORBIDDEN_LIBRARY_LABELS = (
    "Search this library",
    "Sources",
    "HDR",
    "Sort",
    "Layout",
    "Density",
)


class UiNode:
    def __init__(
        self,
        text: str,
        content_desc: str,
        bounds: tuple[int, int, int, int],
        attrs: dict[str, str] | None = None,
    ):
        self.text = text
        self.content_desc = content_desc
        self.bounds = bounds
        self.attrs = dict(attrs or {})

    @property
    def label(self) -> str:
        return self.text or self.content_desc

    @property
    def focused(self) -> bool:
        return self.attrs.get("focused") == "true"

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.bounds
        return ((left + right) // 2, (top + bottom) // 2)

    def __repr__(self) -> str:
        return f"UiNode(label={self.label!r}, bounds={self.bounds!r})"


class CheckResult:
    def __init__(
        self,
        ok: bool,
        missing: Sequence[str] | None = None,
        failures: Sequence[str] | None = None,
        values: dict[str, object] | None = None,
    ):
        self.ok = ok
        self.missing = list(missing or [])
        self.failures = list(failures or [])
        self.values = dict(values or {})

    def merge(self, other: "CheckResult") -> "CheckResult":
        merged = CheckResult(
            self.ok and other.ok,
            [*self.missing, *other.missing],
            [*self.failures, *other.failures],
            {**self.values, **other.values},
        )
        return merged


def parse_bounds(raw: str) -> tuple[int, int, int, int]:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw.strip())
    if not match:
        raise ValueError(f"invalid Android bounds: {raw!r}")
    return tuple(int(group) for group in match.groups())  # type: ignore[return-value]


def _iter_nodes_from_element_tree(xml_text: str) -> Iterable[UiNode]:
    root = ET.fromstring(xml_text)
    for elem in root.iter("node"):
        raw_bounds = elem.attrib.get("bounds")
        if not raw_bounds:
            continue
        try:
            bounds = parse_bounds(raw_bounds)
        except ValueError:
            continue
        yield UiNode(
            text=elem.attrib.get("text", ""),
            content_desc=elem.attrib.get("content-desc", ""),
            bounds=bounds,
            attrs=elem.attrib,
        )


def _iter_nodes_with_regex(xml_text: str) -> Iterable[UiNode]:
    for raw_node in re.findall(r"<node\b[^>]*/?>", xml_text):
        attrs = dict(re.findall(r"([\w:-]+)=\"([^\"]*)\"", raw_node))
        raw_bounds = attrs.get("bounds")
        if not raw_bounds:
            continue
        try:
            bounds = parse_bounds(raw_bounds)
        except ValueError:
            continue
        yield UiNode(
            attrs.get("text", ""),
            attrs.get("content-desc", ""),
            bounds,
            attrs=attrs,
        )


def parse_ui_nodes(xml_text: str) -> list[UiNode]:
    try:
        return list(_iter_nodes_from_element_tree(xml_text))
    except ET.ParseError:
        return list(_iter_nodes_with_regex(xml_text))


def _matches(label: str, wanted: str) -> bool:
    label_norm = " ".join(label.split()).casefold()
    wanted_norm = " ".join(wanted.split()).casefold()
    if label_norm == wanted_norm:
        return True
    if len(wanted_norm) <= 4:
        return False
    return wanted_norm in label_norm


def find_nodes(xml_text: str, wanted: str) -> list[UiNode]:
    return [
        node
        for node in parse_ui_nodes(xml_text)
        if _matches(node.text, wanted) or _matches(node.content_desc, wanted)
    ]


def _first_bounds(xml_text: str, wanted: str) -> tuple[int, int, int, int] | None:
    nodes = find_nodes(xml_text, wanted)
    return nodes[0].bounds if nodes else None


def hud_drag_points(
    xml_text: str,
    *,
    screen_width: int,
    screen_height: int,
) -> dict[str, tuple[int, int]] | None:
    """Return a safe drag gesture anchored inside the currently visible Nova HUD.

    The HUD changes size/mode after a tap. Retroid smoke must therefore derive the drag
    start from the post-tap XML instead of reusing coordinates from the previous HUD
    mode; otherwise the drag may start below the compact top-left HUD and hit the
    stream/game surface instead.
    """
    candidates: list[UiNode] = []
    for label in HUD_LABELS:
        candidates.extend(find_nodes(xml_text, label))
    if not candidates:
        return None

    anchor = min(candidates, key=lambda node: (node.bounds[1], node.bounds[0]))
    from_x, from_y = anchor.center
    to_x = min(screen_width - 80, from_x + max(640, screen_width // 2))
    to_y = min(screen_height - 80, from_y + max(160, screen_height // 6))
    if to_x <= from_x:
        to_x = max(0, from_x - max(240, screen_width // 4))
    if to_y <= from_y:
        to_y = max(0, from_y - max(120, screen_height // 8))
    return {"from": (from_x, from_y), "to": (to_x, to_y)}


def analyze_library_rail(xml_text: str) -> CheckResult:
    missing = [
        label
        for label in DRAWER_FIRST_LIBRARY_REQUIRED_LABELS
        if not find_nodes(xml_text, label)
    ]
    values: dict[str, object] = {}
    failures: list[str] = []
    nodes = parse_ui_nodes(xml_text)

    options_nodes = find_nodes(xml_text, "Library Options")
    if options_nodes:
        options = min(options_nodes, key=lambda node: (node.bounds[1], node.bounds[0]))
        values["options_left"] = options.bounds[0]
        values["options_top"] = options.bounds[1]
        if options.bounds[1] > 260 or options.bounds[0] > 320:
            failures.append("Library Options is not in the top toolbar")

    obsolete_labels: list[str] = []
    for node in nodes:
        label = " ".join(node.label.split())
        if not label:
            continue
        left, top, right, bottom = node.bounds
        height = bottom - top
        if left >= 520 or top <= 250:
            continue
        # Populated game cards expose small metadata chips such as "HDR" and "Recent"
        # near the left edge; those are not the obsolete permanent rail controls.
        if height <= 28 and label.casefold() in {"hdr", "recent"}:
            continue
        if any(_matches(label, wanted) for wanted in OBSOLETE_LIBRARY_RAIL_LABELS):
            obsolete_labels.append(label)
    values["obsolete_rail_labels"] = sorted(set(obsolete_labels))
    if obsolete_labels:
        failures.append("permanent landscape Library rail is still visible")

    content_nodes: list[UiNode] = []
    for label in DRAWER_FIRST_LIBRARY_CONTENT_LABELS:
        content_nodes.extend(find_nodes(xml_text, label))
    if content_nodes:
        content_left = min(node.bounds[0] for node in content_nodes)
        values["content_left"] = content_left
        if content_left > 220:
            failures.append("library content still starts after old rail")

    hint_nodes = []
    for node in nodes:
        label = " ".join(node.label.split()).casefold()
        if any(token in label for token in ("select", "back", "menu", "button_start", "button_select")):
            hint_nodes.append(node)
    if hint_nodes:
        hint_left = min(node.bounds[0] for node in hint_nodes)
        values["hint_left"] = hint_left
        if hint_left > 180:
            failures.append("controller hint bar still reserves old rail width")
    else:
        missing.append("controller hint bar")

    ok = not missing and not failures
    return CheckResult(ok=ok, missing=missing, failures=failures, values=values)


def analyze_command_center(xml_text: str) -> CheckResult:
    # Harden the Command Center oracle to accept safer flows:
    # - 'Disconnect' (non-destructive) may be present without 'End' (destructive).
    # - Either 'Quick Keys' OR visible Touch Controls (caption or node) should satisfy
    #   the controller-affordance requirement for first-paint checks.
    # - 'Stats Overlay' remains a required telemetry affordance.
    required = ["Stats Overlay"]
    # Accept either Disconnect or End as satisfying lifecycle affordance presence
    lifecycle_present = bool(find_nodes(xml_text, "Disconnect") or find_nodes(xml_text, "End"))

    missing = [label for label in required if not find_nodes(xml_text, label)]
    if not lifecycle_present:
        missing.append("Lifecycle affordance (Disconnect/End)")

    # Touch controls may be present as a node or as a caption string; accept either.
    touch_present = bool(find_nodes(xml_text, "Touch Controls") or find_nodes(xml_text, TOUCH_CONTROLS_CAPTION))

    values: dict[str, object] = {}
    failures: list[str] = []

    quick_keys = _first_bounds(xml_text, "Quick Keys")
    touch = _first_bounds(xml_text, "Touch Controls")
    if quick_keys:
        values["quick_keys_top"] = quick_keys[1]
        if quick_keys[1] > 650 and not touch_present:
            failures.append("Quick Keys are not visible early enough for first-paint controller use")

    values["touch_controls_visible"] = touch is not None or touch_present

    # Accept either quick keys OR touch controls as satisfying the Command Center surface.
    if not (touch_present or quick_keys):
        failures.append("Command Center surface missing both Quick Keys and Touch Controls")

    ok = not missing and not failures
    return CheckResult(ok=ok, missing=missing, failures=failures, values=values)


def analyze_required_labels(xml_text: str, labels: Sequence[str], surface: str) -> CheckResult:
    missing = [label for label in labels if not find_nodes(xml_text, label)]
    values: dict[str, object] = {
        f"{surface}_label_count": len({node.label for node in parse_ui_nodes(xml_text) if node.label}),
    }
    return CheckResult(ok=not missing, missing=[f"{surface}: {label}" for label in missing], values=values)


def visible_surface_values(xml_text: str, prefix: str, labels: Sequence[str]) -> dict[str, object]:
    values: dict[str, object] = {}
    for label in labels:
        bounds = _first_bounds(xml_text, label)
        if bounds is not None:
            values[f"{prefix}_{label.replace(' ', '_').casefold()}_bounds"] = bounds
    return values


def _present_labels(xml_text: str, labels: Sequence[str]) -> list[str]:
    return [label for label in labels if find_nodes(xml_text, label)]


def _shown_count_label(xml_text: str) -> str | None:
    for node in parse_ui_nodes(xml_text):
        label = " ".join(node.label.split())
        if re.fullmatch(r"\d+\s+shown", label, flags=re.IGNORECASE):
            return label
    return None


def _phone_library_base_forbidden_filter_nodes(xml_text: str) -> list[tuple[str, UiNode]]:
    nodes = parse_ui_nodes(xml_text)
    max_bottom = max((node.bounds[3] for node in nodes), default=0)
    footer_top = int(max_bottom * 0.82) if max_bottom else 0
    exposed: list[tuple[str, UiNode]] = []

    for node in nodes:
        label = " ".join(node.label.split())
        if not label:
            continue
        label_norm = label.casefold()
        left, top, right, bottom = node.bounds
        width = right - left
        height = bottom - top

        # Controller hint bars legitimately mention drawer-owned shortcuts such as
        # "Y Layout". Treat the bottom chrome as navigation help, not a leaked
        # Library Options control.
        if top >= footer_top and (
            label_norm == "layout" or "y layout" in label_norm or "select" in label_norm or "back" in label_norm
        ):
            continue

        # Game cards expose tiny metadata badges (HDR/Recent). The Library base
        # must reject filter controls, but those badges are content metadata, not
        # a persistent filter rail.
        if _matches(label, "HDR") and width <= 80 and height <= 32:
            continue

        for wanted in PHONE_LIBRARY_BASE_FORBIDDEN_FILTER_LABELS:
            if _matches(label, wanted):
                exposed.append((wanted, node))
                break

    return exposed


def analyze_phone_library_base(xml_text: str) -> CheckResult:
    result = analyze_required_labels(xml_text, PHONE_LIBRARY_LABELS, "phone_library_base")
    result.values.update(visible_surface_values(xml_text, "phone_library_base", PHONE_LIBRARY_LABELS))
    shown_count = _shown_count_label(xml_text)
    if shown_count:
        result.values["phone_library_base_shown_count_label"] = shown_count

    exposed_filters = [label for label, _node in _phone_library_base_forbidden_filter_nodes(xml_text)]
    result.values["phone_library_base_exposed_filter_labels"] = sorted(set(exposed_filters))
    if exposed_filters:
        result.failures.append("phone library base exposes drawer-owned filters")
        result.ok = False
    return result


def analyze_phone_library_options_drawer(xml_text: str) -> CheckResult:
    result = analyze_required_labels(
        xml_text,
        PHONE_LIBRARY_OPTIONS_DRAWER_LABELS,
        "phone_library_options_drawer",
    )
    result.values.update(
        visible_surface_values(xml_text, "phone_library_options_drawer", PHONE_LIBRARY_OPTIONS_DRAWER_LABELS)
    )
    result.values["phone_library_options_layout_scrolled"] = False
    mixed_system = _present_labels(xml_text, PHONE_LIBRARY_OPTIONS_FORBIDDEN_SYSTEM_LABELS)
    result.values["phone_library_options_drawer_system_labels"] = mixed_system
    if mixed_system:
        result.failures.append("phone library drawer mixes system controls")
        result.ok = False
    return result


def analyze_phone_system_drawer(xml_text: str) -> CheckResult:
    result = analyze_required_labels(xml_text, PHONE_SYSTEM_DRAWER_LABELS, "phone_system_drawer")
    result.values.update(visible_surface_values(xml_text, "phone_system_drawer", PHONE_SYSTEM_DRAWER_LABELS))
    mixed_library = _present_labels(xml_text, PHONE_SYSTEM_FORBIDDEN_LIBRARY_LABELS)
    result.values["phone_system_drawer_library_labels"] = mixed_library
    if mixed_library:
        result.failures.append("phone system drawer mixes library controls")
        result.ok = False
    return result


def nova_package_needles(package: str) -> tuple[str, ...]:
    needles = {package, NOVA_PACKAGE_PREFIX}
    if package.endswith(".debug") or package.endswith(".release"):
        needles.add(package.rsplit(".", 1)[0])
    return tuple(sorted(needles, key=len, reverse=True))


def scan_logcat(log_text: str, package: str = DEFAULT_PACKAGE) -> CheckResult:
    needles = nova_package_needles(package)
    package_seen = any(needle in log_text for needle in needles)
    patterns: list[str] = []
    for needle in needles:
        patterns.extend(
            [
                f"ANR in {needle}",
                f"Process: {needle}",
                f"Force finishing activity {needle}",
            ]
        )
    failures = [pattern for pattern in patterns if pattern in log_text]
    if "FATAL EXCEPTION" in log_text and package_seen and not any(
        failure.startswith("Process:") for failure in failures
    ):
        failures.append("FATAL EXCEPTION for Nova package")
    if "has died: prcp TOP" in log_text and package_seen:
        failures.append("Nova process died")
    values = {
        "stream_active": "stream_active" in log_text,
        "video_stream_started": "Starting video stream" in log_text,
        "audio_stream_started": "Starting audio stream" in log_text,
        "clean_disconnect": "ENet peer acknowledged disconnection" in log_text
        or "Nova SSE: Stopped" in log_text,
    }
    return CheckResult(ok=not failures, failures=failures, values=values)


def live_stream_log_result(log_result: CheckResult, *, require_clean_disconnect: bool) -> CheckResult:
    required = ["stream_active", "video_stream_started", "audio_stream_started"]
    if require_clean_disconnect:
        required.append("clean_disconnect")
    missing = [key for key in required if not log_result.values.get(key)]
    return CheckResult(
        ok=log_result.ok and not missing,
        missing=missing,
        failures=log_result.failures,
        values=log_result.values,
    )


class Adb:
    def __init__(self, serial: str, dry_run: bool = False):
        self.serial = serial
        self.dry_run = dry_run

    def run(
        self,
        args: Sequence[str],
        *,
        timeout: int = 30,
        check: bool = True,
        capture_output: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        cmd = ["adb", "-s", self.serial, *args]
        print("$", " ".join(cmd), flush=True)
        if self.dry_run:
            return subprocess.CompletedProcess(cmd, 0, "", "")
        result = subprocess.run(
            cmd,
            text=True,
            capture_output=capture_output,
            timeout=timeout,
            check=False,
        )
        if check and result.returncode != 0:
            raise RuntimeError(
                f"adb command failed ({result.returncode}): {' '.join(cmd)}\n"
                f"stdout={result.stdout}\nstderr={result.stderr}"
            )
        return result

    def shell(self, command: str, *, timeout: int = 30, check: bool = True) -> str:
        return self.run(["shell", command], timeout=timeout, check=check).stdout

    def input_keyevent(self, key: str) -> None:
        self.shell(f"input keyevent {key}")

    def tap(self, x: int, y: int) -> None:
        self.shell(f"input tap {x} {y}")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 350) -> None:
        self.shell(f"input swipe {x1} {y1} {x2} {y2} {duration_ms}")


def adb_device_states(devices_output: str) -> dict[str, str]:
    states: dict[str, str] = {}
    for line in devices_output.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            states[parts[0]] = parts[1]
    return states


def connected_adb_devices(devices_output: str) -> list[str]:
    return [serial for serial, state in adb_device_states(devices_output).items() if state == "device"]


def ensure_adb_device(serial: str | None, *, dry_run: bool = False) -> str:
    if dry_run:
        resolved = serial or "dry-run-device"
        print(f"dry-run: skipping ADB device discovery (serial={resolved})", flush=True)
        return resolved
    if shutil.which("adb") is None:
        raise SystemExit("adb not found on PATH")
    result = subprocess.run(["adb", "devices"], text=True, capture_output=True, check=False)
    states = adb_device_states(result.stdout)
    if serial:
        if states.get(serial) != "device":
            raise SystemExit(f"ADB device {serial!r} is not connected as 'device'.\n{result.stdout}")
        return serial
    devices = [device for device, state in states.items() if state == "device"]
    if len(devices) == 1:
        print(f"ADB serial not supplied; using only connected device {devices[0]!r}", flush=True)
        return devices[0]
    if not devices:
        raise SystemExit("No ADB devices are connected as 'device'. Pass --serial or set NOVA_ADB_SERIAL.")
    raise SystemExit(
        "Multiple ADB devices are connected; pass --serial or set NOVA_ADB_SERIAL.\n"
        f"Connected devices: {', '.join(devices)}"
    )


def read_system_setting(adb: Adb, key: str) -> str:
    return adb.shell(f"settings get system {key}", check=False).strip()


def write_system_setting(adb: Adb, key: str, value: str) -> None:
    adb.shell(f"settings put system {key} {value}", check=False)


def delete_system_setting(adb: Adb, key: str) -> None:
    adb.shell(f"settings delete system {key}", check=False)


def capture_rotation_settings(adb: Adb) -> dict[str, str]:
    return {
        "accelerometer_rotation": read_system_setting(adb, "accelerometer_rotation"),
        "user_rotation": read_system_setting(adb, "user_rotation"),
    }


def restore_rotation_settings(adb: Adb, settings: dict[str, str] | None) -> None:
    if not settings:
        return
    for key, value in settings.items():
        if value and value != "null":
            write_system_setting(adb, key, value)
        else:
            delete_system_setting(adb, key)


def _display_rect(adb: Adb) -> tuple[int, int] | None:
    output = adb.shell("dumpsys display | grep -m 1 'mCurrentDisplayRect' || true", check=False)
    match = re.search(r"Rect\(0, 0 - (\d+), (\d+)\)", output)
    if not match:
        return None
    return (int(match.group(1)), int(match.group(2)))


def force_landscape(adb: Adb, timeout_s: float = 5.0, settle_s: float = 1.5) -> bool:
    write_system_setting(adb, "accelerometer_rotation", "0")
    write_system_setting(adb, "user_rotation", "1")
    deadline = time.time() + timeout_s
    last_rect: tuple[int, int] | None = None
    while time.time() < deadline:
        last_rect = _display_rect(adb)
        if last_rect and last_rect[0] > last_rect[1]:
            print(f"display: forced landscape {last_rect[0]}x{last_rect[1]}", flush=True)
            if settle_s > 0:
                time.sleep(settle_s)
            return True
        time.sleep(0.25)
    print(f"display: landscape lock requested; current rect={last_rect}", flush=True)
    return False


def maybe_force_landscape(adb: Adb, args: argparse.Namespace) -> tuple[dict[str, str] | None, CheckResult]:
    if not getattr(args, "force_landscape", True):
        return None, CheckResult(ok=True, values={"force_landscape": False})
    if adb.dry_run:
        return None, CheckResult(ok=True, values={"force_landscape": "dry_run"})
    previous = capture_rotation_settings(adb)
    ok = force_landscape(adb)
    return previous, CheckResult(
        ok=ok,
        failures=[] if ok else ["display did not rotate to landscape"],
        values={"force_landscape": ok},
    )


def timestamp() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def artifact_prefix(args: argparse.Namespace, name: str) -> Path:
    root = Path(args.artifacts_dir).expanduser()
    root.mkdir(parents=True, exist_ok=True)
    return root / f"nova_retroid_{name}_{timestamp()}"


def maybe_install(adb: Adb, args: argparse.Namespace) -> None:
    if args.skip_install:
        print("install: skipped")
        return
    apk = Path(args.apk)
    if not apk.is_absolute():
        apk = Path(args.repo) / apk
    if not apk.exists():
        raise SystemExit(f"APK not found: {apk}")
    adb.run(["install", "-r", "-d", str(apk)], timeout=180)


def _library_start_command(args: argparse.Namespace) -> str:
    command = ["am", "start", "-n", f"{args.package}/{args.activity}"]
    if getattr(args, "host", None):
        command.extend(["--es", "host", str(args.host)])
    if getattr(args, "server_name", None):
        command.extend(["--es", "server_name", str(args.server_name)])
    if getattr(args, "http_port", None) is not None:
        command.extend(["--ei", "http_port", str(args.http_port)])
    if getattr(args, "https_port", None) is not None:
        command.extend(["--ei", "https_port", str(args.https_port)])
    if getattr(args, "unique_id", None):
        command.extend(["--es", "unique_id", str(args.unique_id)])
    if getattr(args, "pc_uuid", None):
        command.extend(["--es", "pc_uuid", str(args.pc_uuid)])
    return " ".join(shlex.quote(part) for part in command)


def start_library(adb: Adb, args: argparse.Namespace) -> CheckResult:
    target = f"{args.package}/{args.activity}"
    adb.shell(f"am force-stop {args.package}")
    command = _library_start_command(args)
    direct = adb.run(["shell", command], check=False)
    direct_focused = direct.returncode == 0 and wait_for_focus(adb, target, 5)
    if direct_focused:
        time.sleep(1)
        return CheckResult(
            ok=True,
            values={
                "library_start_direct_focused": True,
                "library_start_fallback_used": False,
                "library_start_fallback_focused": False,
            },
        )

    print("direct Library activity launch failed or did not focus; falling back to launcher monkey", flush=True)
    adb.run(
        ["shell", f"monkey -p {args.package} -c android.intent.category.LAUNCHER 1"],
        timeout=20,
    )
    time.sleep(3)
    fallback_focused = wait_for_focus(adb, target, 5)
    return CheckResult(
        ok=fallback_focused,
        failures=[] if fallback_focused else ["Library activity did not focus after launcher fallback"],
        values={
            "library_start_direct_focused": False,
            "library_start_fallback_used": True,
            "library_start_fallback_focused": fallback_focused,
        },
    )


def ensure_library_focused(adb: Adb, args: argparse.Namespace, reason: str, timeout_s: int = 5) -> CheckResult:
    target = f"{args.package}/{args.activity}"
    if wait_for_focus(adb, target, 1):
        return CheckResult(ok=True, values={f"{reason}_refocused": False})

    print(f"library focus lost after {reason}; restarting Library activity", flush=True)
    start_library(adb, args)
    focused = wait_for_focus(adb, target, timeout_s)
    return CheckResult(
        ok=focused,
        failures=[] if focused else [f"Library activity is not focused after {reason}"],
        values={f"{reason}_refocused": True, "library_focus_restored": focused},
    )


def dump_xml(adb: Adb, output: Path) -> str:
    remote = "/sdcard/window_dump.xml"
    adb.shell(f"uiautomator dump {remote}", timeout=20)
    xml = adb.run(["exec-out", "cat", remote], timeout=20).stdout
    output.write_text(xml, encoding="utf-8")
    return xml


def wait_for_library_rail(
    adb: Adb,
    output: Path,
    *,
    timeout_s: float = 8.0,
    interval_s: float = 0.5,
) -> tuple[str, CheckResult]:
    deadline = time.time() + timeout_s
    xml = ""
    result = CheckResult(ok=False)
    while True:
        xml = dump_xml(adb, output)
        result = analyze_library_rail(xml)
        if result.ok or time.time() >= deadline:
            return xml, result
        time.sleep(interval_s)


def capture_png(adb: Adb, output: Path) -> None:
    print("$", f"adb -s {adb.serial} exec-out screencap -p > {output}", flush=True)
    if adb.dry_run:
        output.write_bytes(b"")
        return
    with output.open("wb") as handle:
        result = subprocess.run(
            ["adb", "-s", adb.serial, "exec-out", "screencap", "-p"],
            stdout=handle,
            stderr=subprocess.PIPE,
            timeout=30,
            check=False,
        )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode("utf-8", "replace"))


def current_focus(adb: Adb) -> str:
    return adb.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true", check=False)


def wait_for_focus(adb: Adb, needle: str, timeout_s: int) -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        focus = current_focus(adb)
        if needle in focus:
            return True
        time.sleep(1)
    return False


def clear_logcat(adb: Adb) -> None:
    adb.run(["logcat", "-c"], check=False)


def begin_logcat_window(adb: Adb, args: argparse.Namespace) -> str | None:
    if getattr(args, "clear_logcat", False):
        clear_logcat(adb)
        return None
    if adb.dry_run:
        return None
    marker = adb.shell("date '+%m-%d %H:%M:%S.000'", check=False).strip()
    return marker or None


def read_logcat(adb: Adb, since: str | None = None) -> str:
    command = ["logcat", "-d"]
    if since:
        command.extend(["-T", since])
    return adb.run(command, timeout=30, check=False).stdout


def write_report(prefix: Path, title: str, result: CheckResult, artifacts: Sequence[Path]) -> None:
    report = prefix.with_suffix(".txt")
    lines = [
        title,
        f"status={'PASS' if result.ok else 'FAIL'}",
        f"missing={result.missing}",
        f"failures={result.failures}",
        f"values={result.values}",
        "artifacts:",
    ]
    lines.extend(f"  {path}" for path in artifacts)
    report.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(report.read_text(encoding="utf-8"))


def run_library(args: argparse.Namespace) -> CheckResult:
    args.serial = ensure_adb_device(args.serial, dry_run=args.dry_run)
    adb = Adb(args.serial, args.dry_run)
    rotation_settings: dict[str, str] | None = None
    try:
        maybe_install(adb, args)
        logcat_since = begin_logcat_window(adb, args)
        start_result = start_library(adb, args)
        rotation_settings, rotation_result = maybe_force_landscape(adb, args)
        focus_result = ensure_library_focused(adb, args, "rotation")

        prefix = artifact_prefix(args, "library")
        png = prefix.with_suffix(".png")
        xml_path = prefix.with_suffix(".xml")
        xml, library_result = wait_for_library_rail(adb, xml_path)
        capture_png(adb, png)
        result = start_result.merge(rotation_result).merge(focus_result).merge(library_result)

        for key in ("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN"):
            adb.input_keyevent(key)
        time.sleep(0.5)
        after_dpad_xml = prefix.with_name(prefix.name + "_after_dpad").with_suffix(".xml")
        after_xml = dump_xml(adb, after_dpad_xml)
        focused = next((node.bounds for node in parse_ui_nodes(after_xml) if node.focused), None)
        if focused:
            result.values["focused_after_dpad"] = focused

        log_result = scan_logcat(read_logcat(adb, since=logcat_since), args.package)
        result = result.merge(log_result)
        write_report(prefix, "Nova Retroid Library smoke", result, [png, xml_path, after_dpad_xml])
        return result
    finally:
        restore_rotation_settings(adb, rotation_settings)


def open_command_center(adb: Adb) -> None:
    adb.shell(
        "input gamepad keycombination -t 300 "
        "KEYCODE_BUTTON_START KEYCODE_BUTTON_SELECT",
        timeout=10,
    )
    time.sleep(1.5)


def run_command_center(args: argparse.Namespace) -> CheckResult:
    args.serial = ensure_adb_device(args.serial, dry_run=args.dry_run)
    adb = Adb(args.serial, args.dry_run)
    rotation_settings: dict[str, str] | None = None
    try:
        rotation_settings, rotation_result = maybe_force_landscape(adb, args)
        logcat_since = begin_logcat_window(adb, args)
        if not args.assume_open:
            open_command_center(adb)

        prefix = artifact_prefix(args, "command_center")
        png = prefix.with_suffix(".png")
        xml_path = prefix.with_suffix(".xml")
        capture_png(adb, png)
        xml = dump_xml(adb, xml_path)
        command_result = analyze_command_center(xml)
        result = rotation_result.merge(command_result)

        adb.swipe(360, 920, 360, 430)
        time.sleep(0.5)
        controls_xml = prefix.with_name(prefix.name + "_controls").with_suffix(".xml")
        scrolled_xml = dump_xml(adb, controls_xml)
        if not command_result.ok:
            command_result = analyze_command_center(xml + scrolled_xml)
            result = rotation_result.merge(command_result)

        log_result = scan_logcat(read_logcat(adb, since=logcat_since), args.package)
        result = result.merge(log_result)
        write_report(prefix, "Nova Retroid Command Center smoke", result, [png, xml_path, controls_xml])
        return result
    finally:
        restore_rotation_settings(adb, rotation_settings)


def run_phone(args: argparse.Namespace) -> CheckResult:
    args.serial = ensure_adb_device(args.serial, dry_run=args.dry_run)
    adb = Adb(args.serial, args.dry_run)
    rotation_settings: dict[str, str] | None = None
    try:
        maybe_install(adb, args)
        logcat_since = begin_logcat_window(adb, args)
        rotation_settings = capture_rotation_settings(adb) if not args.dry_run else None
        write_system_setting(adb, "accelerometer_rotation", "0")
        write_system_setting(adb, "user_rotation", "0")

        prefix = artifact_prefix(args, "phone")
        artifacts: list[Path] = []
        result = CheckResult(ok=True)

        adb.shell(f"am force-stop {args.package}")
        adb.run(
            ["shell", f"monkey -p {args.package} -c android.intent.category.LAUNCHER 1"],
            timeout=20,
        )
        time.sleep(3)
        launcher_png = prefix.with_name(prefix.name + "_launcher").with_suffix(".png")
        launcher_xml_path = prefix.with_name(prefix.name + "_launcher").with_suffix(".xml")
        capture_png(adb, launcher_png)
        launcher_xml = dump_xml(adb, launcher_xml_path)
        artifacts.extend([launcher_png, launcher_xml_path])

        library_xml = launcher_xml
        library_result = analyze_phone_library_base(launcher_xml)
        launcher_is_library = all(
            find_nodes(launcher_xml, label) for label in ("Library", "Library Options", "System")
        )

        if launcher_is_library:
            result = result.merge(library_result)
        else:
            dashboard_result = analyze_required_labels(launcher_xml, PHONE_DASHBOARD_LABELS, "dashboard")
            dashboard_result.values.update(
                visible_surface_values(launcher_xml, "dashboard", PHONE_DASHBOARD_LABELS)
            )
            result = result.merge(dashboard_result)

            if tap_first_label(adb, launcher_xml, "Settings"):
                time.sleep(2)
                settings_png = prefix.with_name(prefix.name + "_settings").with_suffix(".png")
                settings_xml_path = prefix.with_name(prefix.name + "_settings").with_suffix(".xml")
                capture_png(adb, settings_png)
                settings_xml = dump_xml(adb, settings_xml_path)
                artifacts.extend([settings_png, settings_xml_path])
                settings_result = analyze_required_labels(settings_xml, PHONE_SETTINGS_LABELS, "settings")
                settings_result.values.update(
                    visible_surface_values(settings_xml, "settings", PHONE_SETTINGS_LABELS)
                )
                result = result.merge(settings_result)
            else:
                result = result.merge(CheckResult(ok=False, missing=["dashboard: Settings tap target"]))

            start_result = start_library(adb, args)
            result = result.merge(start_result)
            time.sleep(2)
            library_png = prefix.with_name(prefix.name + "_library").with_suffix(".png")
            library_xml_path = prefix.with_name(prefix.name + "_library").with_suffix(".xml")
            capture_png(adb, library_png)
            library_xml = dump_xml(adb, library_xml_path)
            artifacts.extend([library_png, library_xml_path])
            library_result = analyze_phone_library_base(library_xml)
            result = result.merge(library_result)

        library_focused = f"{args.package}/{args.activity}" in current_focus(adb)
        result.values["library_focused"] = library_focused
        if not library_focused:
            result.failures.append("Library activity did not remain focused")
            result.ok = False

        if tap_first_label(adb, library_xml, "Library Options"):
            time.sleep(1.2)
            left_png = prefix.with_name(prefix.name + "_left_library_options").with_suffix(".png")
            left_xml_path = prefix.with_name(prefix.name + "_left_library_options").with_suffix(".xml")
            capture_png(adb, left_png)
            left_xml = dump_xml(adb, left_xml_path)
            artifacts.extend([left_png, left_xml_path])
            left_result = analyze_phone_library_options_drawer(left_xml)
            if any(item.endswith(": Layout") for item in left_result.missing):
                adb.swipe(560, 920, 560, 430)
                time.sleep(0.5)
                left_scrolled_xml_path = prefix.with_name(
                    prefix.name + "_left_library_options_scrolled"
                ).with_suffix(".xml")
                left_scrolled_xml = dump_xml(adb, left_scrolled_xml_path)
                artifacts.append(left_scrolled_xml_path)
                left_result = analyze_phone_library_options_drawer(left_xml + left_scrolled_xml)
                left_result.values["phone_library_options_layout_scrolled"] = True
            result = result.merge(left_result)
            adb.input_keyevent("KEYCODE_BACK")
            time.sleep(0.7)
            after_left_xml_path = prefix.with_name(prefix.name + "_after_left_back").with_suffix(".xml")
            library_xml = dump_xml(adb, after_left_xml_path)
            artifacts.append(after_left_xml_path)
        else:
            result = result.merge(CheckResult(ok=False, missing=["library: Library Options tap target"]))

        if tap_first_label(adb, library_xml, "System"):
            time.sleep(1.2)
            right_png = prefix.with_name(prefix.name + "_right_system").with_suffix(".png")
            right_xml_path = prefix.with_name(prefix.name + "_right_system").with_suffix(".xml")
            capture_png(adb, right_png)
            right_xml = dump_xml(adb, right_xml_path)
            artifacts.extend([right_png, right_xml_path])
            result = result.merge(analyze_phone_system_drawer(right_xml))
            adb.input_keyevent("KEYCODE_BACK")
            time.sleep(0.7)
            after_right_xml_path = prefix.with_name(prefix.name + "_after_right_back").with_suffix(".xml")
            after_right_xml = dump_xml(adb, after_right_xml_path)
            artifacts.append(after_right_xml_path)
            after_right_result = analyze_phone_library_base(after_right_xml)
            if after_right_result.ok:
                result.values["phone_after_system_back_library_restored"] = True
            else:
                result = result.merge(
                    CheckResult(
                        ok=False,
                        failures=["phone Library did not restore after System drawer Back"],
                        values={"phone_after_system_back_library_restored": False},
                    )
                )
        else:
            result = result.merge(CheckResult(ok=False, missing=["library: System tap target"]))

        log_result = scan_logcat(read_logcat(adb, since=logcat_since), args.package)
        result = result.merge(log_result)
        write_report(prefix, "Nova phone two-zone Library smoke", result, artifacts)
        return result
    finally:
        restore_rotation_settings(adb, rotation_settings)


def find_launch_button_node(xml_text: str) -> UiNode | None:
    candidates: list[UiNode] = []
    for node in parse_ui_nodes(xml_text):
        label = " ".join(node.label.split()).casefold()
        if not label.startswith("launch"):
            continue
        if any(blocked in label for blocked in ("launch mode", "launch controls", "launch options")):
            continue
        candidates.append(node)
    if not candidates:
        return None
    return max(candidates, key=lambda node: (node.bounds[2] - node.bounds[0]) * (node.bounds[3] - node.bounds[1]))


def analyze_game_detail_first_paint(xml_text: str, expected_title: str | None = None) -> CheckResult:
    required = ["Launch controls", "Launch Mode", "Headless", "Virtual"]
    if expected_title:
        required.insert(0, expected_title)
    result = analyze_required_labels(xml_text, required, "detail_first_paint")
    result.values.update(visible_surface_values(xml_text, "detail_first_paint", required))

    launch = find_launch_button_node(xml_text)
    if launch is None:
        result.missing.append("detail_first_paint: launch action")
        result.ok = False
    else:
        result.values["detail_launch_button_bounds"] = launch.bounds
    return result


def tap_first_label(adb: Adb, xml_text: str, label: str) -> bool:
    nodes = find_nodes(xml_text, label)
    if not nodes:
        return False
    x, y = nodes[0].center
    adb.tap(x, y)
    return True


def launch_stream_from_library(
    adb: Adb,
    args: argparse.Namespace,
    prefix: Path,
    initial_xml: str,
) -> bool:
    if args.launch_text and tap_first_label(adb, initial_xml, args.launch_text):
        time.sleep(1.2)
    elif not args.launch_text:
        adb.input_keyevent("KEYCODE_DPAD_CENTER")
        time.sleep(1.2)

    if wait_for_focus(adb, f"{args.package}/com.papi.nova.Game", 1):
        return True

    deadline = time.time() + max(1, int(getattr(args, "timeout", 45)))
    attempt = 1
    while time.time() < deadline:
        xml_path = prefix.with_name(f"{prefix.name}_launch_attempt_{attempt}").with_suffix(".xml")
        xml_text = dump_xml(adb, xml_path)
        launch = find_launch_button_node(xml_text)
        if launch is not None:
            x, y = launch.center
            adb.tap(x, y)
            time.sleep(1.5)
        remaining = max(1, min(2, int(deadline - time.time())))
        if wait_for_focus(adb, f"{args.package}/com.papi.nova.Game", remaining):
            return True
        attempt += 1
    return False


def end_stream_from_command_center(adb: Adb, args: argparse.Namespace, prefix: Path) -> CheckResult:
    for attempt in range(1, 5):
        xml_path = prefix.with_name(f"{prefix.name}_end_attempt_{attempt}").with_suffix(".xml")
        xml_text = dump_xml(adb, xml_path)
        if tap_first_label(adb, xml_text, "End session") or tap_first_label(adb, xml_text, "End"):
            time.sleep(0.8)
            confirm_xml = dump_xml(adb, prefix.with_name(prefix.name + "_end_confirm").with_suffix(".xml"))
            confirmed = tap_first_label(adb, confirm_xml, "YES") or tap_first_label(adb, confirm_xml, "Yes")
            if not confirmed:
                return CheckResult(
                    ok=False,
                    failures=["End confirmation button not found"],
                    values={"end_stream_confirmed": False, "returned_to_library": False},
                )
            returned = wait_for_focus(adb, args.activity, getattr(args, "timeout", 25))
            return CheckResult(
                ok=returned,
                failures=[] if returned else ["Library focus did not return after ending stream"],
                values={"end_stream_confirmed": True, "returned_to_library": returned},
            )
        adb.swipe(360, 430, 360, 920)
        time.sleep(0.5)
    return CheckResult(
        ok=False,
        failures=["End action not found in Command Center"],
        values={"end_stream_confirmed": False, "returned_to_library": False},
    )


def run_live_stream(args: argparse.Namespace) -> CheckResult:
    args.serial = ensure_adb_device(args.serial, dry_run=args.dry_run)
    adb = Adb(args.serial, args.dry_run)
    rotation_settings: dict[str, str] | None = None
    prefix: Path | None = None
    stream_started = False
    end_attempted = False
    try:
        maybe_install(adb, args)
        logcat_since = begin_logcat_window(adb, args)
        start_result = start_library(adb, args)
        rotation_settings, rotation_result = maybe_force_landscape(adb, args)
        focus_result = ensure_library_focused(adb, args, "rotation")

        prefix = artifact_prefix(args, "live_stream")
        library_xml = prefix.with_name(prefix.name + "_library").with_suffix(".xml")
        xml = dump_xml(adb, library_xml)
        library_result = start_result.merge(rotation_result).merge(focus_result).merge(analyze_library_rail(xml))

        if not launch_stream_from_library(adb, args, prefix, xml):
            raise SystemExit(f"Timed out waiting for {args.package}/com.papi.nova.Game")
        stream_started = True

        stream_png = prefix.with_name(prefix.name + "_stream").with_suffix(".png")
        stream_xml = prefix.with_name(prefix.name + "_stream").with_suffix(".xml")
        capture_png(adb, stream_png)
        dump_xml(adb, stream_xml)

        open_command_center(adb)
        command_png = prefix.with_name(prefix.name + "_command_center").with_suffix(".png")
        command_xml = prefix.with_name(prefix.name + "_command_center").with_suffix(".xml")
        capture_png(adb, command_png)
        cc_xml_text = dump_xml(adb, command_xml)
        command_result = analyze_command_center(cc_xml_text)

        if not command_result.ok:
            adb.swipe(360, 920, 360, 430)
            time.sleep(0.5)
            scrolled = dump_xml(adb, prefix.with_name(prefix.name + "_command_center_controls").with_suffix(".xml"))
            command_result = analyze_command_center(cc_xml_text + scrolled)

        end_result = CheckResult(ok=True, values={"end_stream_skipped": args.no_end_stream})
        if not args.no_end_stream:
            end_result = end_stream_from_command_center(adb, args, prefix)
            end_attempted = True

        log_result = live_stream_log_result(
            scan_logcat(read_logcat(adb, since=logcat_since), args.package),
            require_clean_disconnect=not args.no_end_stream,
        )
        result = library_result.merge(command_result).merge(end_result).merge(log_result)
        write_report(
            prefix,
            "Nova Retroid live stream smoke",
            result,
            [library_xml, stream_png, stream_xml, command_png, command_xml],
        )
        return result
    finally:
        if stream_started and not args.no_end_stream and not end_attempted and prefix is not None:
            try:
                cleanup_result = end_stream_from_command_center(adb, args, prefix)
                if not cleanup_result.ok:
                    adb.shell(f"am force-stop {args.package}", check=False)
            except Exception as exc:  # best-effort cleanup must not hide the original failure
                print(f"cleanup: failed to end stream cleanly: {exc}", flush=True)
                try:
                    adb.shell(f"am force-stop {args.package}", check=False)
                except Exception:
                    pass
        restore_rotation_settings(adb, rotation_settings)


def _default(value: object, use_defaults: bool) -> object:
    return value if use_defaults else argparse.SUPPRESS


def add_common_args(parser: argparse.ArgumentParser, *, use_defaults: bool = True) -> None:
    parser.add_argument(
        "--serial",
        default=_default(DEFAULT_SERIAL, use_defaults),
        help=(
            "ADB serial. Defaults to NOVA_ADB_SERIAL/RETROID_ID; when omitted, "
            "the helper uses the only connected adb device or fails if there are zero/multiple devices."
        ),
    )
    parser.add_argument("--repo", default=_default(str(DEFAULT_REPO), use_defaults))
    parser.add_argument("--package", default=_default(DEFAULT_PACKAGE, use_defaults))
    parser.add_argument("--activity", default=_default(DEFAULT_ACTIVITY, use_defaults))
    parser.add_argument("--apk", default=_default(str(DEFAULT_APK), use_defaults))
    parser.add_argument("--artifacts-dir", default=_default(str(DEFAULT_ARTIFACT_DIR), use_defaults))
    parser.add_argument("--skip-install", action="store_true", default=_default(False, use_defaults))
    parser.add_argument("--host", default=_default(os.environ.get("NOVA_SMOKE_HOST"), use_defaults))
    parser.add_argument("--server-name", default=_default(os.environ.get("NOVA_SMOKE_SERVER_NAME"), use_defaults))
    parser.add_argument(
        "--http-port",
        type=int,
        default=_default(int(os.environ.get("NOVA_SMOKE_HTTP_PORT", "47989")), use_defaults),
    )
    parser.add_argument(
        "--https-port",
        type=int,
        default=_default(int(os.environ.get("NOVA_SMOKE_HTTPS_PORT", "47984")), use_defaults),
    )
    parser.add_argument("--unique-id", default=_default(os.environ.get("NOVA_SMOKE_UNIQUE_ID"), use_defaults))
    parser.add_argument("--pc-uuid", default=_default(os.environ.get("NOVA_SMOKE_PC_UUID"), use_defaults))
    parser.add_argument(
        "--no-force-landscape",
        dest="force_landscape",
        action="store_false",
        default=_default(True, use_defaults),
        help="do not temporarily lock display rotation to landscape for Retroid smoke captures",
    )
    parser.add_argument(
        "--clear-logcat",
        action="store_true",
        default=_default(False, use_defaults),
        help="clear the whole device logcat before smoke; by default the helper reads from a start timestamp instead",
    )
    parser.add_argument("--dry-run", action="store_true", default=_default(False, use_defaults))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Nova Retroid smoke automation")
    add_common_args(parser)
    subparser_common = argparse.ArgumentParser(add_help=False)
    add_common_args(subparser_common, use_defaults=False)
    subparsers = parser.add_subparsers(dest="command", required=True)

    library = subparsers.add_parser("library", parents=[subparser_common], help="safe Library rail smoke")
    library.set_defaults(func=run_library)

    command = subparsers.add_parser(
        "command-center",
        parents=[subparser_common],
        help="inspect Command Center from an active stream",
    )
    command.add_argument("--assume-open", action="store_true", help="skip controller chord and inspect current UI")
    command.set_defaults(func=run_command_center)

    phone = subparsers.add_parser(
        "phone",
        parents=[subparser_common],
        help="portrait phone two-zone Library/drawer form-factor smoke",
    )
    phone.set_defaults(func=run_phone)

    live = subparsers.add_parser(
        "live-stream",
        parents=[subparser_common],
        help="launch, open Command Center, and end a stream",
    )
    live.add_argument("--launch-text", default=os.environ.get("NOVA_SMOKE_LAUNCH_TEXT", "Steam Big Picture"))
    live.add_argument("--timeout", type=int, default=45)
    live.add_argument("--no-end-stream", action="store_true")
    live.set_defaults(func=run_live_stream)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    result = args.func(args)
    return 0 if result.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
