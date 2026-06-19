#!/usr/bin/env python3
"""Static guard for the local-only Deck Moonlight handoff preflight slice."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCAN_FILES = [
    ROOT / "src" / "stream" / "deck_moonlight_handoff_preflight.h",
    ROOT / "src" / "stream" / "deck_moonlight_handoff_preflight.cpp",
    ROOT / "src" / "main.cpp",
    ROOT / "qml" / "Main.qml",
    ROOT / "CMakeLists.txt",
]

FORBIDDEN_PATTERNS = [
    ("process launch API", re.compile(r"\b(QProcess|fork\s*\(|system\s*\(|popen\s*\(|xdg-open|flatpak\s+run)\b")),
    ("exec API", re.compile(r"\bexec(?:l|le|lp|lpe|v|ve|vp|vpe)?\s*\(")),
    ("Moonlight runtime connection", re.compile(r"\b(LiStartConnection|LiStopConnection|LiInterruptConnection|MoonBridge\.startConnection)\b")),
    ("Moonlight stream shell command", re.compile(r"moonlight\s+stream", re.IGNORECASE)),
    ("Moonlight custom URI", re.compile(r"moonlight://", re.IGNORECASE)),
    ("host HTTP launch surface", re.compile(r"(/launch|/resume|/cancel|/serverinfo|/applist)\b", re.IGNORECASE)),
    ("Android host/pairing/persistence", re.compile(r"\b(NvHTTP|PairingManager|ComputerDatabaseManager|HostStore|DiscoveryService)\b")),
    ("network discovery/probing", re.compile(r"\b(mDNS|NSD|zeroconf|socket\s*\(|connect\s*\(|send\s*\(|recv\s*\()\b")),
    ("media/device probe", re.compile(r"\b(av_hwdevice_ctx_create|PipeWire|PulseAudio|VA-API|/dev/input|xdotool|ffmpeg|systemctl|pgrep|ldd)\b", re.IGNORECASE)),
    ("private endpoint literal", re.compile(r"\b(?:10|127|169\.254|172\.(?:1[6-9]|2\d|3[0-1])|192\.168)\.\d{1,3}\.\d{1,3}\b")),
    ("MAC-like literal", re.compile(r"\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\b")),
    ("private key header", re.compile(r"BEGIN [A-Z ]*PRIVATE KEY")),
    ("secret-looking assignment", re.compile(r"\b(?:api[_-]?key|client[_-]?secret|session[_-]?token|password)\b\s*[:=]", re.IGNORECASE)),
]

ALLOWED_CMAKE_PATTERN = re.compile(r"nova_deck_moonlight_handoff_preflight|deck_moonlight_handoff", re.IGNORECASE)


def normalized_text(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    if path.name == "CMakeLists.txt":
        # Target/file names necessarily contain the feature name. Keep command/runtime checks active.
        text = ALLOWED_CMAKE_PATTERN.sub("PRELIGHT_TARGET_NAME", text)
    if path.name == "main.cpp":
        # Qt signal wiring/event loop are not network connect/probe or process exec surfaces.
        text = text.replace("QObject::connect", "QT_SIGNAL_CONNECT")
        text = text.replace("connect(notifier_", "QT_SIGNAL_CONNECT(notifier_")
        text = text.replace("return app.exec();", "return QT_APP_EVENT_LOOP;")
    if path.name == "Main.qml":
        # Existing inert preview URI path is local copy text, not a host HTTP launch endpoint.
        text = text.replace("preview://nova-deck/launch", "preview://nova-deck/PREVIEW_PATH")
    return text


def main() -> int:
    failures: list[str] = []
    for path in SCAN_FILES:
        if not path.exists():
            failures.append(f"missing guarded file: {path.relative_to(ROOT)}")
            continue
        text = normalized_text(path)
        for label, pattern in FORBIDDEN_PATTERNS:
            match = pattern.search(text)
            if match:
                rel = path.relative_to(ROOT)
                failures.append(f"{rel}: forbidden {label}: {match.group(0)!r}")
    if failures:
        print("Deck Moonlight preflight source guard failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("Deck Moonlight preflight source guard passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
