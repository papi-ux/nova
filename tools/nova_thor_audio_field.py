#!/usr/bin/env python3
"""Build a privacy-safe Nova Thor display/audio field report from logcat.

The report proves Android display/focus/audio wiring only. A Thor owner must still
confirm which physical speaker and volume slider respond during the stream.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path


DISPLAY_ID = r"(\d+)"
STREAM_ROLE_RE = re.compile(
    rf"^Nova: Android display role stream id={DISPLAY_ID} target=[A-Za-z0-9_-]+$"
)
STREAM_LAUNCH_RE = re.compile(rf"^Nova: Android display launch stream id={DISPLAY_ID}$")
COMPANION_ROLE_RE = re.compile(
    rf"^Nova: Android display role companion id={DISPLAY_ID} stream_id={DISPLAY_ID}$"
)
AUDIO_CONTEXT_RE = re.compile(
    rf"^Nova: Android display audio context stream_id={DISPLAY_ID} display_id={DISPLAY_ID}$"
)
AUDIO_ROUTE_RE = re.compile(
    rf"^Nova: Android display audio route display_id={DISPLAY_ID} "
    r"device_id=(none|\d+) type=(none|\d+)$"
)
FOCUS_RE = re.compile(
    rf"^Nova: Android display focus role=(game|companion) display_id={DISPLAY_ID} "
    r"window=(true|false) game_top_resumed=(true|false)$"
)
PACKAGE_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$")
ERROR_MARKERS = {
    "fatal_exception": "FATAL EXCEPTION",
    "invalid_display": "InvalidDisplayException",
    "unable_to_add_window": "Unable to add window",
    "window_type_mismatch": "Window type mismatch",
}


def _append_unique(values, value):
    if value not in values:
        values.append(value)


def _match(pattern: re.Pattern, line: str):
    return pattern.fullmatch(line.strip())


def analyze_logcat(logcat: str, *, source_process_scoped: bool = False) -> dict:
    lines = logcat.splitlines()
    run_start = None
    for index, line in enumerate(lines):
        if _match(STREAM_ROLE_RE, line) or _match(STREAM_LAUNCH_RE, line):
            run_start = index
    latest_run_marker_found = run_start is not None
    lines = lines[run_start:] if run_start is not None else []

    stream_display_ids = []
    companion_display_ids = []
    companion_stream_display_ids = []
    audio_stream_display_ids = []
    audio_context_display_ids = []
    audio_routes = []
    focus_events = []
    runtime_errors = {kind: 0 for kind in ERROR_MARKERS}
    latest_stream_display_id = None
    latest_companion_display_id = None
    latest_companion_stream_display_id = None
    latest_audio_stream_display_id = None
    latest_audio_context_display_id = None

    for line in lines:
        match = _match(STREAM_ROLE_RE, line) or _match(STREAM_LAUNCH_RE, line)
        if match:
            latest_stream_display_id = int(match.group(1))
            _append_unique(stream_display_ids, latest_stream_display_id)

        match = _match(COMPANION_ROLE_RE, line)
        if match:
            latest_companion_display_id = int(match.group(1))
            _append_unique(companion_display_ids, latest_companion_display_id)
            latest_companion_stream_display_id = int(match.group(2))
            _append_unique(companion_stream_display_ids, latest_companion_stream_display_id)

        match = _match(AUDIO_CONTEXT_RE, line)
        if match:
            latest_audio_stream_display_id = int(match.group(1))
            latest_audio_context_display_id = int(match.group(2))
            _append_unique(audio_stream_display_ids, latest_audio_stream_display_id)
            _append_unique(audio_context_display_ids, latest_audio_context_display_id)

        match = _match(AUDIO_ROUTE_RE, line)
        if match:
            route = {
                "display_id": int(match.group(1)),
                "device_id": match.group(2),
                "type": match.group(3),
            }
            _append_unique(audio_routes, route)

        match = _match(FOCUS_RE, line)
        if match:
            event = {
                "role": match.group(1),
                "display_id": int(match.group(2)),
                "window": match.group(3) == "true",
                "game_top_resumed": match.group(4) == "true",
            }
            _append_unique(focus_events, event)

        for kind, marker in ERROR_MARKERS.items():
            if marker in line:
                runtime_errors[kind] += 1

    audio_context_matches = None
    if (
        latest_stream_display_id is not None
        and latest_audio_stream_display_id is not None
        and latest_audio_context_display_id is not None
    ):
        audio_context_matches = (
            latest_stream_display_id
            == latest_audio_stream_display_id
            == latest_audio_context_display_id
        )

    companion_stream_matches = None
    if latest_stream_display_id is not None and latest_companion_stream_display_id is not None:
        companion_stream_matches = latest_stream_display_id == latest_companion_stream_display_id

    latest_audio_route = audio_routes[-1] if audio_routes else None
    audio_route_matches = bool(
        latest_stream_display_id is not None
        and latest_audio_route is not None
        and latest_audio_route["display_id"] == latest_stream_display_id
    )

    def latest_focus_event(role: str, display_id):
        if display_id is None:
            return None
        return next(
            (
                event
                for event in reversed(focus_events)
                if event["role"] == role and event["display_id"] == display_id
            ),
            None,
        )

    latest_game_focus_event = latest_focus_event("game", latest_stream_display_id)
    latest_companion_focus_event = latest_focus_event("companion", latest_companion_display_id)
    game_window_observed = bool(latest_game_focus_event and latest_game_focus_event["window"])
    game_top_resumed_observed = bool(
        latest_game_focus_event and latest_game_focus_event["game_top_resumed"]
    )
    companion_window_observed = bool(
        latest_companion_focus_event and latest_companion_focus_event["window"]
    )
    runtime_errors_absent = latest_run_marker_found and not any(runtime_errors.values())
    diagnostic_evidence_complete = bool(
        source_process_scoped
        and latest_run_marker_found
        and audio_context_matches is True
        and companion_stream_matches is True
        and audio_route_matches
        and game_window_observed
        and game_top_resumed_observed
        and companion_window_observed
        and runtime_errors_absent
    )

    return {
        "schema_version": 2,
        "claim_status": (
            "diagnostic wiring only; physical speaker and AYN volume-slider routing require human confirmation"
        ),
        "physical_audio_verified": False,
        "source_process_scoped": source_process_scoped,
        "latest_run_marker_found": latest_run_marker_found,
        "stream_display_ids": stream_display_ids,
        "companion_display_ids": companion_display_ids,
        "companion_stream_display_ids": companion_stream_display_ids,
        "audio_stream_display_ids": audio_stream_display_ids,
        "audio_context_display_ids": audio_context_display_ids,
        "latest_stream_display_id": latest_stream_display_id,
        "latest_companion_display_id": latest_companion_display_id,
        "latest_companion_stream_display_id": latest_companion_stream_display_id,
        "latest_audio_stream_display_id": latest_audio_stream_display_id,
        "latest_audio_context_display_id": latest_audio_context_display_id,
        "audio_routes": audio_routes,
        "latest_audio_route": latest_audio_route,
        "focus_events": focus_events,
        "latest_game_focus_event": latest_game_focus_event,
        "latest_companion_focus_event": latest_companion_focus_event,
        "runtime_errors": runtime_errors,
        "checks": {
            "source_process_scoped": source_process_scoped,
            "audio_context_matches_stream": audio_context_matches,
            "companion_stream_matches_stream": companion_stream_matches,
            "audio_route_observed": bool(audio_routes),
            "audio_route_matches_stream": audio_route_matches,
            "game_window_observed": game_window_observed,
            "game_top_resumed_observed": game_top_resumed_observed,
            "companion_window_observed": companion_window_observed,
            "runtime_errors_absent": runtime_errors_absent,
            "diagnostic_evidence_complete": diagnostic_evidence_complete,
        },
    }


def read_adb_logcat(serial: str, package: str) -> str:
    adb = shutil.which("adb")
    if not adb:
        raise SystemExit("adb was not found in PATH")
    if not PACKAGE_RE.fullmatch(package):
        raise SystemExit("invalid Android package name")

    pid_result = subprocess.run(
        [adb, "-s", serial, "shell", "pidof", "-s", package],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    pid = pid_result.stdout.strip()
    if pid_result.returncode != 0 or not pid.isdigit():
        raise SystemExit(f"Nova package is not running: {package}")

    completed = subprocess.run(
        [adb, "-s", serial, "logcat", "-d", "-v", "raw", "--pid", pid],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return completed.stdout


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="Create a privacy-safe Nova Thor display/audio field report",
    )
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--input", type=Path, help="saved PID-scoped raw logcat file, or - for stdin")
    source.add_argument("--serial", help="ADB serial to query with read-only PID-scoped logcat -d")
    parser.add_argument(
        "--package",
        default="com.papi.nova.debug",
        help="Nova package for --serial PID scoping (default: com.papi.nova.debug)",
    )
    parser.add_argument("--output", type=Path, help="write JSON here instead of stdout")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    source_process_scoped = False
    if args.serial:
        logcat = read_adb_logcat(args.serial, args.package)
        source_process_scoped = True
    elif str(args.input) == "-":
        logcat = sys.stdin.read()
    else:
        logcat = args.input.read_text(encoding="utf-8", errors="replace")

    encoded = json.dumps(
        analyze_logcat(logcat, source_process_scoped=source_process_scoped),
        indent=2,
        sort_keys=True,
    ) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    else:
        sys.stdout.write(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
