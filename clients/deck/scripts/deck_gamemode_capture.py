#!/usr/bin/env python3
"""Local-only Nova Deck Game Mode capture harness.

This script is intentionally conservative: it enumerates Nova-ish windows,
selects only a 1280x800-ish Nova Deck product window, targets input at that
window id, records proof artifacts, and fails closed on helper/ambiguous
windows. It does not launch Moonlight, Sunshine, games, network discovery,
Polaris endpoints, or public publishing actions.
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Iterable, Sequence


EXPECTED_WIDTH = 1280
EXPECTED_HEIGHT = 800
DEFAULT_TOLERANCE = 4
DEFAULT_WINDOW_NAME = "Nova"
TARGET_TITLE_RE = re.compile(r"^Nova Deck$", re.IGNORECASE)
HELPER_RE = re.compile(r"helper|splash|popup|tooltip|steamwebhelper|gamescope|overlay", re.IGNORECASE)
SENSITIVE_ENV_KEY_RE = re.compile(
    r"token|secret|password|passwd|api_key|private_key|access_key|credential|cookie|cert",
    re.IGNORECASE,
)


class HarnessError(RuntimeError):
    pass


class SelectionError(HarnessError):
    pass


@dataclasses.dataclass(frozen=True)
class Geometry:
    x: int
    y: int
    width: int
    height: int

    def is_expected_deck_size(self, tolerance: int = DEFAULT_TOLERANCE) -> bool:
        return abs(self.width - EXPECTED_WIDTH) <= tolerance and abs(self.height - EXPECTED_HEIGHT) <= tolerance

    def is_helper_sized(self) -> bool:
        return self.width <= 32 or self.height <= 32


@dataclasses.dataclass(frozen=True)
class WindowCandidate:
    window_id: str
    name: str = ""
    window_class: str = ""
    geometry: Geometry = Geometry(0, 0, 0, 0)
    pid: int | None = None
    mapped: bool | None = None

    def as_dict(self) -> dict:
        return {
            "window_id": self.window_id,
            "name": self.name,
            "window_class": self.window_class,
            "geometry": dataclasses.asdict(self.geometry),
            "pid": self.pid,
            "mapped": self.mapped,
            "plausible": is_plausible_nova_deck_window(self),
        }


def run(argv: Sequence[str], *, check: bool = True, text: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(list(argv), check=check, text=text, capture_output=True)


def parse_xdotool_geometry(output: str) -> Geometry:
    shell_values = dict(re.findall(r"^([A-Z]+)=(-?\d+)$", output, flags=re.MULTILINE))
    if "WIDTH" in shell_values and "HEIGHT" in shell_values:
        return Geometry(
            x=int(shell_values.get("X", 0)),
            y=int(shell_values.get("Y", 0)),
            width=int(shell_values["WIDTH"]),
            height=int(shell_values["HEIGHT"]),
        )
    position = re.search(r"Position:\s*(-?\d+),(-?\d+)", output)
    geometry = re.search(r"Geometry:\s*(\d+)x(\d+)", output)
    if not geometry:
        raise HarnessError(f"could not parse xdotool geometry: {output!r}")
    x = int(position.group(1)) if position else 0
    y = int(position.group(2)) if position else 0
    return Geometry(x=x, y=y, width=int(geometry.group(1)), height=int(geometry.group(2)))


def _optional_xdotool(args: Sequence[str]) -> str:
    try:
        return run(["xdotool", *args]).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ""


def enumerate_xdotool_candidates(name: str = DEFAULT_WINDOW_NAME) -> list[WindowCandidate]:
    if shutil.which("xdotool") is None:
        raise HarnessError("xdotool not found; cannot enumerate Deck windows")
    search = run(["xdotool", "search", "--name", name], check=False)
    window_ids = [line.strip() for line in search.stdout.splitlines() if line.strip()]
    candidates: list[WindowCandidate] = []
    for window_id in window_ids:
        raw_geometry = _optional_xdotool(["getwindowgeometry", "--shell", window_id])
        try:
            geometry = parse_xdotool_geometry(raw_geometry)
        except HarnessError:
            geometry = Geometry(0, 0, 0, 0)
        pid_raw = _optional_xdotool(["getwindowpid", window_id])
        mapped_raw = _optional_xdotool(["getwindowmapstate", window_id])
        candidates.append(
            WindowCandidate(
                window_id=window_id,
                name=_optional_xdotool(["getwindowname", window_id]),
                window_class=_optional_xdotool(["getwindowclassname", window_id]),
                geometry=geometry,
                pid=int(pid_raw) if pid_raw.isdigit() else None,
                mapped=(mapped_raw.lower() == "ismapped") if mapped_raw else None,
            )
        )
    return candidates


def is_plausible_nova_deck_window(candidate: WindowCandidate, *, tolerance: int = DEFAULT_TOLERANCE) -> bool:
    if not candidate.geometry.is_expected_deck_size(tolerance):
        return False
    if candidate.geometry.is_helper_sized():
        return False
    if candidate.mapped is False:
        return False
    if HELPER_RE.search(candidate.name) or HELPER_RE.search(candidate.window_class):
        return False
    title_ok = bool(TARGET_TITLE_RE.search(candidate.name)) or candidate.window_class.lower() in {"nova-deck", "nova"}
    return title_ok


def select_nova_deck_window(
    candidates: Sequence[WindowCandidate],
    *,
    expected_pid: int | None = None,
    tolerance: int = DEFAULT_TOLERANCE,
) -> WindowCandidate:
    plausible = [c for c in candidates if is_plausible_nova_deck_window(c, tolerance=tolerance)]
    if expected_pid is not None:
        pid_matches = [c for c in plausible if c.pid == expected_pid]
        if len(pid_matches) == 1:
            return pid_matches[0]
        if len(pid_matches) > 1:
            ids = ", ".join(c.window_id for c in pid_matches)
            raise SelectionError(f"ambiguous Nova Deck windows for pid {expected_pid}: {ids}")
    if not plausible:
        details = json.dumps([c.as_dict() for c in candidates], indent=2)
        raise SelectionError(f"no 1280x800 Nova Deck window found; candidates={details}")
    if len(plausible) > 1:
        ids = ", ".join(c.window_id for c in plausible)
        raise SelectionError(f"ambiguous Nova Deck windows: {ids}")
    return plausible[0]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, data: object) -> None:
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")


def redact_sensitive_output(output: str) -> str:
    """Redact likely secret-bearing environment-style output before artifact writes."""
    redacted_lines = []
    for line in output.splitlines():
        key, separator, _value = line.partition("=")
        if separator and SENSITIVE_ENV_KEY_RE.search(key):
            redacted_lines.append(f"{key}=[REDACTED]")
        else:
            redacted_lines.append(line)
    suffix = "\n" if output.endswith("\n") else ""
    return "\n".join(redacted_lines) + suffix


def capture_window(window_id: str, path: Path, *, display: str | None, timeout: int = 5) -> None:
    if shutil.which("ffmpeg") is None:
        raise HarnessError("ffmpeg not found; cannot capture selected window")
    env = os.environ.copy()
    if display:
        env["DISPLAY"] = display
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "x11grab", "-window_id", str(window_id), "-frames:v", "1", str(path),
    ]
    subprocess.run(cmd, check=True, env=env, timeout=timeout)
    if not path.exists() or path.stat().st_size == 0:
        raise HarnessError(f"capture failed or empty: {path}")


def send_window_key(window_id: str, key: str) -> None:
    if shutil.which("xdotool") is None:
        raise HarnessError("xdotool not found; cannot send window-targeted input")
    run(["xdotool", "key", "--window", str(window_id), key])


def make_image_diff(before: Path, after: Path, diff: Path) -> dict:
    before_hash = sha256_file(before)
    after_hash = sha256_file(after)
    changed = before_hash != after_hash
    compare = shutil.which("magick") or shutil.which("compare")
    compare_exit = None
    if compare:
        if Path(compare).name == "magick":
            cmd = [compare, "compare", "-metric", "AE", str(before), str(after), str(diff)]
        else:
            cmd = [compare, "-metric", "AE", str(before), str(after), str(diff)]
        proc = subprocess.run(cmd, text=True, capture_output=True)
        compare_exit = proc.returncode
        if not diff.exists() and changed:
            diff.write_text(f"ImageMagick compare did not create a diff; stderr={proc.stderr}\n")
    else:
        diff.write_text("ImageMagick compare unavailable; SHA-256 changed=%s\nbefore=%s\nafter=%s\n" % (changed, before_hash, after_hash))
    return {"changed": changed, "before_sha256": before_hash, "after_sha256": after_hash, "compare_exit": compare_exit}


def make_contact_sheet(images: Sequence[Path], output: Path) -> None:
    magick = shutil.which("magick")
    montage = shutil.which("montage")
    if magick:
        subprocess.run([magick, "montage", *map(str, images), "-tile", f"{len(images)}x1", "-geometry", "+8+8", str(output)], check=True)
    elif montage:
        subprocess.run([montage, *map(str, images), "-tile", f"{len(images)}x1", "-geometry", "+8+8", str(output)], check=True)
    else:
        output.write_text("Contact sheet unavailable; install ImageMagick. Images:\n" + "\n".join(str(p) for p in images) + "\n")


def record_session_proof(root: Path, *, binary: Path | None = None) -> dict:
    proof: dict[str, object] = {}
    commands = {
        "loginctl_sessions": ["loginctl", "list-sessions", "--no-legend"],
        "user_environment": ["systemctl", "--user", "show-environment"],
        "gamescope_processes": ["pgrep", "-a", "gamescope"],
    }
    for key, cmd in commands.items():
        try:
            proc = subprocess.run(cmd, text=True, capture_output=True, timeout=5)
            proof[key] = {
                "exit": proc.returncode,
                "stdout": redact_sensitive_output(proc.stdout[-4000:]),
                "stderr": redact_sensitive_output(proc.stderr[-2000:]),
            }
        except Exception as exc:
            proof[key] = {"error": str(exc)}
    if binary:
        proof["binary"] = str(binary)
        if binary.exists():
            proof["binary_sha256"] = sha256_file(binary)
            try:
                proc = subprocess.run(["ldd", str(binary)], text=True, capture_output=True, timeout=10)
                proof["ldd"] = {
                    "exit": proc.returncode,
                    "stdout": redact_sensitive_output(proc.stdout[-8000:]),
                    "stderr": redact_sensitive_output(proc.stderr[-4000:]),
                }
            except Exception as exc:
                proof["ldd"] = {"error": str(exc)}
            try:
                proc = subprocess.run([str(binary), "--smoke-exit"], text=True, capture_output=True, timeout=15)
                proof["smoke_exit"] = {
                    "exit": proc.returncode,
                    "stdout": redact_sensitive_output(proc.stdout[-4000:]),
                    "stderr": redact_sensitive_output(proc.stderr[-4000:]),
                }
            except Exception as exc:
                proof["smoke_exit"] = {"error": str(exc)}
    write_json(root / "session_proof.json", proof)
    return proof


def cleanup_recorded_pid(pid_file: Path | None, root: Path) -> dict:
    result = {"pid_file": str(pid_file) if pid_file else None, "attempted": False, "remaining": None}
    if not pid_file or not pid_file.exists():
        write_json(root / "cleanup.json", result)
        return result
    pid_text = pid_file.read_text().strip()
    if not pid_text.isdigit():
        raise HarnessError(f"pid file does not contain a numeric pid: {pid_file}")
    pid = int(pid_text)
    result.update({"attempted": True, "pid": pid})
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    time.sleep(1.0)
    try:
        os.kill(pid, 0)
        result["remaining"] = True
    except ProcessLookupError:
        result["remaining"] = False
    write_json(root / "cleanup.json", result)
    if result["remaining"]:
        raise HarnessError(f"staged process still remains after cleanup: {pid}")
    return result


def run_live(args: argparse.Namespace) -> int:
    root = Path(args.artifact_root).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    candidates = enumerate_xdotool_candidates(args.window_name)
    write_json(root / "window_candidates.json", [c.as_dict() for c in candidates])
    selected = select_nova_deck_window(candidates, expected_pid=args.expected_pid, tolerance=args.tolerance)
    write_json(root / "selected_window.json", selected.as_dict())
    if args.dry_run:
        print(f"selected {selected.window_id} {selected.geometry.width}x{selected.geometry.height}")
        return 0
    before = root / "before.png"
    after_tab = root / "after_tab.png"
    after_down = root / "after_down.png"
    diff = root / "focus_diff.png"
    contact = root / "focus_contact.png"
    capture_window(selected.window_id, before, display=args.display)
    send_window_key(selected.window_id, "Tab")
    time.sleep(args.settle_seconds)
    capture_window(selected.window_id, after_tab, display=args.display)
    send_window_key(selected.window_id, "Down")
    time.sleep(args.settle_seconds)
    capture_window(selected.window_id, after_down, display=args.display)
    diff_result = make_image_diff(before, after_tab, diff)
    make_contact_sheet([before, after_tab, after_down], contact)
    record_session_proof(root, binary=Path(args.binary).expanduser().resolve() if args.binary else None)
    cleanup = cleanup_recorded_pid(Path(args.pid_file).expanduser() if args.pid_file else None, root)
    summary = {
        "artifact_root": str(root),
        "selected_window": selected.as_dict(),
        "captures": [str(before), str(after_tab), str(after_down)],
        "focus_diff": str(diff),
        "contact_sheet": str(contact),
        "diff_result": diff_result,
        "cleanup": cleanup,
        "safety": "local-only UI capture; no Moonlight/Sunshine/game/backend/discovery/HostStore/publication action",
    }
    write_json(root / "harness_summary.json", summary)
    if not diff_result["changed"]:
        raise HarnessError("window-targeted input produced identical before/after screenshots")
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


def run_self_test() -> int:
    candidates = [
        WindowCandidate("0x02", "Nova Deck", geometry=Geometry(0, 0, 1280, 800), pid=4242),
        WindowCandidate("0x01", "Nova Deck helper", geometry=Geometry(0, 0, 1, 1), pid=4100),
    ]
    selected = select_nova_deck_window(candidates, expected_pid=4242)
    assert selected.window_id == "0x02"
    try:
        select_nova_deck_window([
            WindowCandidate("0x02", "Nova Deck", geometry=Geometry(0, 0, 1280, 800), pid=1),
            WindowCandidate("0x03", "Nova Deck", geometry=Geometry(0, 0, 1280, 800), pid=2),
        ])
    except SelectionError as exc:
        assert "ambiguous" in str(exc)
    else:
        raise AssertionError("ambiguous valid windows did not fail closed")
    print("self-test PASS: rejects helpers, selects 1280x800 target, fails closed on ambiguity")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Nova Deck Game Mode capture harness")
    parser.add_argument("--artifact-root", default="nova-deck-gamemode-capture-artifacts")
    parser.add_argument("--window-name", default=DEFAULT_WINDOW_NAME)
    parser.add_argument("--expected-pid", type=int)
    parser.add_argument("--pid-file")
    parser.add_argument("--binary")
    parser.add_argument("--display", default=os.environ.get("DISPLAY"))
    parser.add_argument("--tolerance", type=int, default=DEFAULT_TOLERANCE)
    parser.add_argument("--settle-seconds", type=float, default=0.35)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.self_test:
            return run_self_test()
        return run_live(args)
    except HarnessError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
