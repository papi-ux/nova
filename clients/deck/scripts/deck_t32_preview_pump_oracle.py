#!/usr/bin/env python3
"""Machine-checkable Deck preview frame pump oracle for the T31 Podman route.

This oracle is intentionally strict: it is for real Steam Deck Game Mode route
artifacts, not local/headless smoke output. It verifies that the route ran Deck
CTest, that the preview pump semantics are covered by the Deck media adapter
CTest source, that the gamescope QSG rerun produced a ready render proof, and
that the validation route stayed away from host streaming/launch paths.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Sequence

SCRIPT_ROOT = Path(__file__).resolve().parent
DECK_ROOT = SCRIPT_ROOT.parent
REPO_ROOT = DECK_ROOT.parents[1]
DEFAULT_ARTIFACT_DIR = REPO_ROOT / "build" / "deck-t32-artifacts"

sys.path.insert(0, str(SCRIPT_ROOT))
import deck_t31_podman_validation as route  # noqa: E402


class OracleFailure(RuntimeError):
    pass


def read_required(path: Path) -> str:
    if not path.is_file():
        raise OracleFailure(f"missing required artifact: {path}")
    return path.read_text(encoding="utf-8", errors="replace")


def require_contains(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise OracleFailure(f"{label}: missing {needle!r}")


def require_regex(text: str, pattern: str, label: str) -> None:
    if re.search(pattern, text, flags=re.MULTILINE) is None:
        raise OracleFailure(f"{label}: missing pattern {pattern!r}")


def validate_preview_pump_source(deck_root: Path = DECK_ROOT) -> list[str]:
    source = read_required(deck_root / "tests" / "deck_stream_media_adapters_test.cpp")
    required_needles = {
        "newest frame fixture is queued": "preview-fixture-newest",
        "older frame is released after coalescing": "previewFrame1Weak.expired()",
        "coalesced frame count is asserted": "previewFramePump.coalescedFrames() == 1",
        "only newest frame remains pending": "previewFramePump.pendingFrames() == 1",
        "newest frame is presented": "previewSink->lastDescriptor.surfaceId == 0x102",
        "newest source survives flush": 'previewSink->lastDescriptor.source == std::string("preview-fixture-newest")',
        "invalid reset fixture is queued": "preview-invalid-reset",
        "invalid reset increments invalidation count": "previewFramePump.invalidatedFrames() == 1",
        "invalid reset clears pending frame": "previewFramePump.pendingFrames() == 0",
        "invalid reset clears stale lease": "previewFrame2Weak.expired()",
    }
    for label, needle in required_needles.items():
        require_contains(source, needle, f"preview pump source guard: {label}")
    return ["preview pump source guard PASS"]


def validate_artifacts(artifact_dir: Path) -> list[str]:
    ctest_log = read_required(artifact_dir / "ctest.log")
    qsg_log = read_required(artifact_dir / "qsg-gamescope-smoke.log")
    read_required(artifact_dir / "LastTest.log")

    require_regex(ctest_log, r"100% tests passed, 0 tests failed out of \d+", "Deck CTest")
    require_regex(
        ctest_log,
        r"nova_deck_stream_media_adapters_test\s+\.+\s+Passed",
        "Deck CTest preview pump binary",
    )
    require_regex(
        ctest_log,
        r"nova_deck_qsg_render_node_scenegraph_smoke\s+\.+\s+Passed",
        "Deck CTest QSG smoke binary",
    )
    require_regex(
        qsg_log,
        r"Nova Deck QSGRenderNode VAAPI/EGL render path .*status=ready.*objects=1.*layers=2.*ready=1",
        "gamescope QSG ready render proof",
    )
    require_regex(
        qsg_log,
        r"Nova Deck QSGRenderNode scenegraph smoke passed: .*imported two DRM_PRIME layers",
        "gamescope QSG rerun binary",
    )
    require_contains(
        qsg_log,
        "readiness stayed false until shader composition proof",
        "gamescope QSG readiness gate",
    )
    return ["Deck artifacts PASS", "gamescope QSG ready proof PASS"]


def validate_route_guardrails() -> list[str]:
    findings = route.find_forbidden_route_tokens()
    if findings:
        raise OracleFailure("route guardrail failed:\n" + "\n".join(f"  {finding}" for finding in findings))
    return ["route guardrails PASS"]


def validate_oracle(artifact_dir: Path = DEFAULT_ARTIFACT_DIR, deck_root: Path = DECK_ROOT) -> list[str]:
    artifact_dir = artifact_dir.expanduser().resolve()
    results: list[str] = []
    results.extend(validate_preview_pump_source(deck_root))
    results.extend(validate_artifacts(artifact_dir))
    results.extend(validate_route_guardrails())
    return results


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, default=DEFAULT_ARTIFACT_DIR, help="local Deck route artifact directory")
    return parser.parse_args(argv)


def main(argv: Sequence[str] = sys.argv[1:]) -> int:
    args = parse_args(argv)
    try:
        results = validate_oracle(args.artifacts)
    except OracleFailure as exc:
        print(f"Deck T32 preview pump oracle FAIL: {exc}", file=sys.stderr)
        return 1
    print("Deck T32 preview pump oracle PASS")
    for result in results:
        print(f"- {result}")
    print(f"artifacts={args.artifacts.expanduser().resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
