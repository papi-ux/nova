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
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from typing import Iterable, Sequence

DEFAULT_SERIAL = os.environ.get("RETROID_ID", "24c12bdd")
DEFAULT_PACKAGE = os.environ.get("NOVA_PACKAGE", "com.papi.nova.debug")
DEFAULT_ACTIVITY = os.environ.get(
    "NOVA_LIBRARY_ACTIVITY",
    "com.papi.nova.ui.NovaLibraryActivity",
)
DEFAULT_ARTIFACT_DIR = Path(os.environ.get("NOVA_SMOKE_ARTIFACT_DIR", "/tmp"))
DEFAULT_APK = Path(
    "app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk"
)
CRASH_PATTERNS = (
    "FATAL EXCEPTION",
    "ANR in com.papi.nova",
    "Process: com.papi.nova.debug",
    "Force finishing activity com.papi.nova",
    "has died: prcp TOP",
)
LIBRARY_RAIL_LABELS = (
    "Refresh",
    "Options",
    "System",
    "Switch",
    "All",
    "Recent",
    "Sources",
    "HDR",
    "More",
)
COMMAND_CENTER_LABELS = (
    "Quick Keys",
    "ESC",
    "Alt + Enter",
    "Alt + F4",
    "Stats Overlay",
    "End",
    "Disconnect",
)
TOUCH_CONTROLS_CAPTION = "On-screen overlay; physical gamepad stays active."


class UiNode:
    def __init__(self, text: str, content_desc: str, bounds: tuple[int, int, int, int]):
        self.text = text
        self.content_desc = content_desc
        self.bounds = bounds

    @property
    def label(self) -> str:
        return self.text or self.content_desc

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
        yield UiNode(attrs.get("text", ""), attrs.get("content-desc", ""), bounds)


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


def analyze_library_rail(xml_text: str) -> CheckResult:
    missing = [label for label in LIBRARY_RAIL_LABELS if not find_nodes(xml_text, label)]
    values: dict[str, object] = {}
    failures: list[str] = []

    rail_bounds = [
        _first_bounds(xml_text, label)
        for label in LIBRARY_RAIL_LABELS
        if _first_bounds(xml_text, label) is not None
    ]
    if rail_bounds:
        values["rail_right"] = max(bounds[2] for bounds in rail_bounds if bounds)
        values["rail_bottom"] = max(bounds[3] for bounds in rail_bounds if bounds)

    hint_nodes = []
    for node in parse_ui_nodes(xml_text):
        label = " ".join(node.label.split()).casefold()
        if any(token in label for token in ("select", "back", "menu", "button_start", "button_select")):
            hint_nodes.append(node)
    if hint_nodes:
        hint_left = min(node.bounds[0] for node in hint_nodes)
        values["hint_left"] = hint_left
        if "rail_right" in values:
            gap = hint_left - int(values["rail_right"])
            values["hint_gap"] = gap
            if gap <= 0:
                failures.append("controller hint bar overlaps the Library rail")

    ok = not missing and not failures
    return CheckResult(ok=ok, missing=missing, failures=failures, values=values)


def analyze_command_center(xml_text: str) -> CheckResult:
    missing = [label for label in COMMAND_CENTER_LABELS if not find_nodes(xml_text, label)]
    missing.extend(
        label
        for label in ("Touch Controls", TOUCH_CONTROLS_CAPTION)
        if not find_nodes(xml_text, label)
    )
    values: dict[str, object] = {}
    failures: list[str] = []

    quick_keys = _first_bounds(xml_text, "Quick Keys")
    touch = _first_bounds(xml_text, "Touch Controls")
    if quick_keys:
        values["quick_keys_top"] = quick_keys[1]
        if quick_keys[1] > 650:
            failures.append("Quick Keys are not visible early enough for first-paint controller use")
    values["touch_controls_visible"] = touch is not None

    return CheckResult(ok=not missing and not failures, missing=missing, failures=failures, values=values)


def scan_logcat(log_text: str) -> CheckResult:
    failures = [pattern for pattern in CRASH_PATTERNS if pattern in log_text]
    values = {
        "stream_active": "stream_active" in log_text,
        "video_stream_started": "Starting video stream" in log_text,
        "audio_stream_started": "Starting audio stream" in log_text,
        "clean_disconnect": "ENet peer acknowledged disconnection" in log_text
        or "Nova SSE: Stopped" in log_text,
    }
    return CheckResult(ok=not failures, failures=failures, values=values)


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


def ensure_adb_device(serial: str) -> None:
    if shutil.which("adb") is None:
        raise SystemExit("adb not found on PATH")
    result = subprocess.run(["adb", "devices"], text=True, capture_output=True, check=False)
    if f"{serial}\tdevice" not in result.stdout:
        raise SystemExit(f"ADB device {serial!r} is not connected as 'device'.\n{result.stdout}")


def read_system_setting(adb: Adb, key: str) -> str:
    return adb.shell(f"settings get system {key}", check=False).strip()


def write_system_setting(adb: Adb, key: str, value: str) -> None:
    adb.shell(f"settings put system {key} {value}", check=False)


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


def maybe_force_landscape(adb: Adb, args: argparse.Namespace) -> dict[str, str] | None:
    if not getattr(args, "force_landscape", True):
        return None
    previous = capture_rotation_settings(adb)
    force_landscape(adb)
    return previous


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


def start_library(adb: Adb, package: str, activity: str) -> None:
    adb.shell(f"am force-stop {package}")
    direct = adb.run(["shell", f"am start -n {package}/{activity}"], check=False)
    if direct.returncode != 0:
        print("direct Library activity launch failed; falling back to launcher monkey", flush=True)
        adb.run(
            ["shell", f"monkey -p {package} -c android.intent.category.LAUNCHER 1"],
            timeout=20,
        )
    time.sleep(3)


def dump_xml(adb: Adb, output: Path) -> str:
    remote = "/sdcard/window_dump.xml"
    adb.shell(f"uiautomator dump {remote}", timeout=20)
    xml = adb.run(["exec-out", "cat", remote], timeout=20).stdout
    output.write_text(xml, encoding="utf-8")
    return xml


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


def read_logcat(adb: Adb) -> str:
    return adb.run(["logcat", "-d"], timeout=30, check=False).stdout


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
    ensure_adb_device(args.serial)
    adb = Adb(args.serial, args.dry_run)
    rotation_settings: dict[str, str] | None = None
    try:
        maybe_install(adb, args)
        clear_logcat(adb)
        start_library(adb, args.package, args.activity)
        rotation_settings = maybe_force_landscape(adb, args)

        prefix = artifact_prefix(args, "library")
        png = prefix.with_suffix(".png")
        xml_path = prefix.with_suffix(".xml")
        capture_png(adb, png)
        xml = dump_xml(adb, xml_path)
        result = analyze_library_rail(xml)

        for key in ("KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN"):
            adb.input_keyevent(key)
        time.sleep(0.5)
        after_dpad_xml = prefix.with_name(prefix.name + "_after_dpad").with_suffix(".xml")
        after_xml = dump_xml(adb, after_dpad_xml)
        focused = next((node.bounds for node in parse_ui_nodes(after_xml) if "true" in node.label.casefold()), None)
        if focused:
            result.values["focused_after_dpad"] = focused

        log_result = scan_logcat(read_logcat(adb))
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
    ensure_adb_device(args.serial)
    adb = Adb(args.serial, args.dry_run)
    rotation_settings: dict[str, str] | None = None
    try:
        rotation_settings = maybe_force_landscape(adb, args)
        clear_logcat(adb)
        if not args.assume_open:
            open_command_center(adb)

        prefix = artifact_prefix(args, "command_center")
        png = prefix.with_suffix(".png")
        xml_path = prefix.with_suffix(".xml")
        capture_png(adb, png)
        xml = dump_xml(adb, xml_path)
        result = analyze_command_center(xml)

        adb.swipe(360, 920, 360, 430)
        time.sleep(0.5)
        controls_xml = prefix.with_name(prefix.name + "_controls").with_suffix(".xml")
        scrolled_xml = dump_xml(adb, controls_xml)
        if not result.ok:
            result = analyze_command_center(xml + scrolled_xml)

        log_result = scan_logcat(read_logcat(adb))
        result = result.merge(log_result)
        write_report(prefix, "Nova Retroid Command Center smoke", result, [png, xml_path, controls_xml])
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

    for attempt in range(1, 9):
        xml_path = prefix.with_name(f"{prefix.name}_launch_attempt_{attempt}").with_suffix(".xml")
        xml_text = dump_xml(adb, xml_path)
        launch = find_launch_button_node(xml_text)
        if launch is not None:
            x, y = launch.center
            adb.tap(x, y)
            time.sleep(1.5)
        if wait_for_focus(adb, f"{args.package}/com.papi.nova.Game", 2):
            return True
    return False


def end_stream_from_command_center(adb: Adb, args: argparse.Namespace, prefix: Path) -> None:
    for attempt in range(1, 5):
        xml_path = prefix.with_name(f"{prefix.name}_end_attempt_{attempt}").with_suffix(".xml")
        xml_text = dump_xml(adb, xml_path)
        if tap_first_label(adb, xml_text, "End"):
            time.sleep(0.8)
            confirm_xml = dump_xml(adb, prefix.with_name(prefix.name + "_end_confirm").with_suffix(".xml"))
            tap_first_label(adb, confirm_xml, "YES") or tap_first_label(adb, confirm_xml, "Yes")
            wait_for_focus(adb, args.activity, 25)
            return
        adb.swipe(360, 430, 360, 920)
        time.sleep(0.5)


def run_live_stream(args: argparse.Namespace) -> CheckResult:
    ensure_adb_device(args.serial)
    adb = Adb(args.serial, args.dry_run)
    rotation_settings: dict[str, str] | None = None
    try:
        maybe_install(adb, args)
        clear_logcat(adb)
        start_library(adb, args.package, args.activity)
        rotation_settings = maybe_force_landscape(adb, args)

        prefix = artifact_prefix(args, "live_stream")
        library_xml = prefix.with_name(prefix.name + "_library").with_suffix(".xml")
        xml = dump_xml(adb, library_xml)
        library_result = analyze_library_rail(xml)

        if not launch_stream_from_library(adb, args, prefix, xml):
            raise SystemExit(f"Timed out waiting for {args.package}/com.papi.nova.Game")

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

        if not args.no_end_stream:
            end_stream_from_command_center(adb, args, prefix)

        log_result = scan_logcat(read_logcat(adb))
        result = library_result.merge(command_result).merge(log_result)
        write_report(
            prefix,
            "Nova Retroid live stream smoke",
            result,
            [library_xml, stream_png, stream_xml, command_png, command_xml],
        )
        return result
    finally:
        restore_rotation_settings(adb, rotation_settings)


def add_common_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--serial", default=DEFAULT_SERIAL)
    parser.add_argument("--repo", default=os.getcwd())
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--activity", default=DEFAULT_ACTIVITY)
    parser.add_argument("--apk", default=str(DEFAULT_APK))
    parser.add_argument("--artifacts-dir", default=str(DEFAULT_ARTIFACT_DIR))
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument(
        "--no-force-landscape",
        dest="force_landscape",
        action="store_false",
        help="do not temporarily lock display rotation to landscape for Retroid smoke captures",
    )
    parser.add_argument("--dry-run", action="store_true")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Nova Retroid smoke automation")
    subparsers = parser.add_subparsers(dest="command", required=True)

    library = subparsers.add_parser("library", help="safe Library rail smoke")
    add_common_args(library)
    library.set_defaults(func=run_library)

    command = subparsers.add_parser("command-center", help="inspect Command Center from an active stream")
    add_common_args(command)
    command.add_argument("--assume-open", action="store_true", help="skip controller chord and inspect current UI")
    command.set_defaults(func=run_command_center)

    live = subparsers.add_parser("live-stream", help="launch, open Command Center, and end a stream")
    add_common_args(live)
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
