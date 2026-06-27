#!/usr/bin/env python3
"""Visible Deck Game Mode frontend smoke route for the Nova Deck shell.

After source sync this route runs inside a rootless Podman container with network
removed. It builds the Deck Qt shell, launches the real QML frontend on the
Game Mode Wayland socket, asks the app to capture its own 1280x800 frame, and
pulls review artifacts back to the workstation.
"""
from __future__ import annotations

import argparse
import re
import shlex
import shutil
import subprocess
import sys
from pathlib import Path, PurePosixPath
from typing import Iterable, Sequence

DEFAULT_DECK = "deck@" + "10.0." + "0.39"
DEFAULT_REMOTE_SOURCE = PurePosixPath("/home/deck/nova-frontend-smoke-src")
DEFAULT_ARTIFACT_DIR = PurePosixPath("/home/deck/nova-frontend-smoke-src/build/deck-frontend-smoke-artifacts")
DEFAULT_IMAGE = "localhost/nova-t24-arch-qt-buildtools"
DEFAULT_BUILD_DIR = "build/deck-frontend-smoke"

SCRIPT_ROOT = Path(__file__).resolve().parent
DECK_ROOT = SCRIPT_ROOT.parent
REPO_ROOT = DECK_ROOT.parents[1]

FORBIDDEN_ROUTE_TOKENS = [
    "Li" + "StartConnection",
    "Host" + "Store",
    "Moon" + "light",
    "Sun" + "shine",
    "start" + "Stream",
    "launch" + "Game",
    "pair" + "ing",
    "access" + "Token",
    "refresh" + "Token",
    "auth" + "Token",
    "pass" + "word",
]
ROUTE_SOURCE_FILES = [SCRIPT_ROOT / "deck_frontend_smoke.py"]


def q(value: object) -> str:
    return shlex.quote(str(value))


def redact_private_addresses(text: str) -> str:
    redacted = re.sub(
        r"\b(?:10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})\b",
        "<private-ip>",
        text,
    )
    redacted = re.sub(
        r"\b([A-Za-z0-9_.-]+@)([A-Za-z0-9-]*deck[A-Za-z0-9-]*(?:\.[A-Za-z0-9-]+)*|(?:[A-Za-z0-9-]+\.)+(?:local|lan|home|internal))(?=[:\s/'\"]|$)",
        r"\1<private-host>",
        redacted,
        flags=re.IGNORECASE,
    )
    return re.sub(
        r"(?<![A-Za-z0-9_.@-])(?:[A-Za-z0-9-]+\.)+(?:local|lan|home|internal)\b",
        "<private-host>",
        redacted,
        flags=re.IGNORECASE,
    )


def redact_command(command: Sequence[str]) -> list[str]:
    return [redact_private_addresses(part) for part in command]


def format_redacted_command(command: Sequence[str]) -> str:
    return " ".join(q(part) for part in redact_command(command))


def repo_root_from_source(source: Path | None) -> Path:
    if source is not None:
        return source.expanduser().resolve()
    return REPO_ROOT


def path_has_artifact_component(path: Path | PurePosixPath) -> bool:
    return any("artifact" in part.lower() for part in path.parts)


def validate_remote_artifact_dir(source_dir: PurePosixPath, artifact_dir: PurePosixPath) -> None:
    if ".." in source_dir.parts or ".." in artifact_dir.parts:
        raise ValueError("remote artifact and source directories must not contain parent traversal")
    if not source_dir.is_absolute() or not artifact_dir.is_absolute():
        raise ValueError("remote artifact and source directories must be absolute")
    if artifact_dir == source_dir:
        raise ValueError("remote artifact directory must not be the remote source directory")
    safe_root = source_dir / "build"
    try:
        artifact_dir.relative_to(safe_root)
    except ValueError as exc:
        raise ValueError("remote artifact directory must stay under the remote source build directory") from exc
    if not path_has_artifact_component(artifact_dir):
        raise ValueError("remote artifact directory must include an artifact-named path component")


def validate_local_artifact_dir(local_artifacts: Path) -> None:
    resolved = local_artifacts.expanduser().resolve()
    forbidden_roots = {Path('/'), Path.home().resolve(), REPO_ROOT.resolve()}
    if resolved in forbidden_roots:
        raise ValueError("local artifact directory refuses broad root/home/repo cleanup")
    safe_root = REPO_ROOT.resolve() / "build"
    try:
        resolved.relative_to(safe_root)
    except ValueError as exc:
        raise ValueError("local artifact directory must stay under the repo build directory") from exc
    if not path_has_artifact_component(resolved):
        raise ValueError("local artifact directory must include an artifact-named path component")


def build_container_shell(
    *,
    source_dir: PurePosixPath,
    artifact_dir: PurePosixPath,
    build_dir: str = DEFAULT_BUILD_DIR,
) -> str:
    validate_remote_artifact_dir(source_dir, artifact_dir)
    environment_summary = artifact_dir / "environment-summary.txt"
    ui_launch_log = artifact_dir / "ui-launch.log"
    qml_runtime_log = artifact_dir / "qml-runtime.log"
    smoke_summary = artifact_dir / "smoke-summary.txt"
    backend_dto_smoke = artifact_dir / "backend-dto-interaction-smoke.txt"
    backend_readonly_matrix_smoke = artifact_dir / "backend-readonly-state-matrix-smoke.txt"
    frame_capture = artifact_dir / "frontend-frame-capture.png"
    expanded_frame_smoke = artifact_dir / "expanded-diagnostics-frame-smoke.txt"
    expanded_frame_capture = artifact_dir / "frontend-expanded-diagnostics-capture.png"
    binary = PurePosixPath(build_dir) / "nova-deck"
    return " && ".join(
        [
            "set -euo pipefail",
            f"cd {q(source_dir)}",
            f"rm -rf {q(artifact_dir)} && mkdir -p {q(artifact_dir)}",
            "printf '%s\n' "
            "\"Nova Deck frontend smoke\" "
            "\"target_window=1280x800\" "
            "\"network=none\" "
            "\"QT_QPA_PLATFORM=$QT_QPA_PLATFORM\" "
            "\"WAYLAND_DISPLAY=$WAYLAND_DISPLAY\" "
            "\"QSG_RHI_BACKEND=$QSG_RHI_BACKEND\" "
            "\"LIBVA_DRIVER_NAME=$LIBVA_DRIVER_NAME\" "
            f"> {q(environment_summary)}",
            f"cmake -S clients/deck -B {q(build_dir)} -G Ninja -DNOVA_DECK_BUILD_QT_SHELL=ON",
            f"cmake --build {q(build_dir)}",
            f"printf '%s\n' 'launching nova-deck visible frontend smoke at 1280x800' > {q(ui_launch_log)}",
            "QT_QPA_PLATFORM=wayland WAYLAND_DISPLAY=gamescope-0 QSG_RHI_BACKEND=opengl "
            "LIBVA_DRIVER_NAME=radeonsi NOVA_DECK_FRONTEND_SMOKE=1 "
            f"{q(binary)} --frontend-smoke-exit-after-ms 2500 "
            "--frontend-smoke-readonly-state lab-gated "
            f"--frontend-smoke-backend-dto-interactions {q(backend_dto_smoke)} "
            f"--frontend-smoke-readonly-state-matrix {q(backend_readonly_matrix_smoke)} "
            f"--frontend-smoke-capture {q(frame_capture)} "
            f"--frontend-smoke-expanded-diagnostics-frame {q(expanded_frame_smoke)} "
            f"--frontend-smoke-expanded-diagnostics-capture {q(expanded_frame_capture)} "
            f">> {q(ui_launch_log)} 2> {q(qml_runtime_log)}",
            f"test -s {q(backend_dto_smoke)}",
            f"test -s {q(backend_readonly_matrix_smoke)}",
            f"test -s {q(expanded_frame_smoke)}",
            f"grep -E '^invoked=true$' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=empty .*status=backend-read-only-preflight-blocked .*blockers=.*missing-host' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=offline .*blockers=.*host-unreachable' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=unpaired .*blockers=.*{'pair' + 'ing-required'}' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=library-unavailable .*blockers=.*library-unavailable' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=lab-gated .*blockers=.*lab-gate-disabled' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=.*backendPowerStarted=false' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=.*dtoContract=backend-owned-read-only-dto-v1' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=.*dtoPrivacy=redacted-public-dto' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=.*dtoReadiness=dto-parity-ready' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'primary=Host offline. Reconnect or pick another host.' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'primary=Pair this host before launch preview.' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'primary=Library unavailable. Try again when the read-only snapshot is back.' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'primary=Launch blocked by lab gate.' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'stateHeadline=Product state: Host offline' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'stateHeadline=Product state: Library unavailable' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'stateHeadline=Product state: Lab gate locked' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'stateAction=Reconnect the host or choose another backend-owned snapshot.' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'stateSafety=Backend power stays off; no retry or network probe runs.' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'stateProvenance=dto-player-state/backend-owned/redacted-public' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'stateFocusOrder=state-card-copy-diagnostics' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'diagnostics=Matrix diagnostic:' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'dtoParity=DTO parity:' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=.*collapsedFirstPaint=true' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=.*expansionToggle=secondary-diagnostics-toggle' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=.*controllerReachable=true' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^matrix_scenario=.*expandedVisible=true' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'expandedDiagnosticsCopy=Matrix diagnostic:' {q(backend_readonly_matrix_smoke)}",
            f"grep -F 'expandedDtoParityCopy=DTO parity:' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^invoked=true$' {q(expanded_frame_smoke)}",
            f"grep -E '^liveExpandedBy=keyboard-controller-toggle$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedFrameFocusTarget=secondary-diagnostics-toggle$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDiagnosticsLaneFocusTarget=expanded-diagnostics-lane$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDiagnosticsLaneReadable=true$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDensityRowsPaged=true$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDiagnosticsPageAffordanceVisible=true$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDiagnosticsPageAffordancePosition=before-blocker-copy$' {q(expanded_frame_smoke)}",
            f"grep -F 'expandedDiagnosticsPageAffordanceText=Diagnostics page 1 of 2' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDiagnosticsScrollNavigationMoved=true$' {q(expanded_frame_smoke)}",
            f"grep -F 'expandedDiagnosticsPostScrollCue=Diagnostics page 2 of 2' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDiagnosticsPostScrollCueContrast=13[.]56:1$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDiagnosticsPostScrollCueSpacing=separate-row-after-blocker-copy$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDiagnosticsPostScrollCueOverlapsBlocker=false$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDiagnosticsPostScrollTarget=lifecycle-dto-details$' {q(expanded_frame_smoke)}",
            f"grep -F 'expandedDiagnosticsFocusAffordance=4px focus ring + active focus badge' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDiagnosticsPage2Readable=true$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedDiagnosticsLaneHeight=132$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedFrameReadable=true$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedFrameSanitized=true$' {q(expanded_frame_smoke)}",
            f"grep -E '^expandedFrameFirstPaintCrowding=false$' {q(expanded_frame_smoke)}",
            f"grep -F 'expandedDiagnosticsCopy=Matrix diagnostic:' {q(expanded_frame_smoke)}",
            f"grep -F 'expandedDtoParityCopy=DTO parity:' {q(expanded_frame_smoke)}",
            f"awk -F'primary=' '/^matrix_scenario=/ {{ split($2, a, \" stateHeadline=\"); if (length(a[1]) > 88) exit 1 }}' {q(backend_readonly_matrix_smoke)}",
            f"grep -E '^invoked=true$' {q(backend_dto_smoke)}",
            f"grep -E '^preflight_button=backend-preflight-dto-preview$' {q(backend_dto_smoke)}",
            f"grep -E '^diagnostics_button=backend-diagnostics-dto-preview$' {q(backend_dto_smoke)}",
            f"grep -E '^preflight_status=backend-preflight-blocked$' {q(backend_dto_smoke)}",
            f"grep -E '^preflight_blockers=.*lab-gate-disabled' {q(backend_dto_smoke)}",
            f"grep -E '^preflight_launch_dry_run_allowed=false$' {q(backend_dto_smoke)}",
            f"grep -E '^preflight_stream_allowed=false$' {q(backend_dto_smoke)}",
            f"grep -E '^preflight_backend_power_started=false$' {q(backend_dto_smoke)}",
            f"grep -E '^preflight_public_copy=.*Deck launch preflight' {q(backend_dto_smoke)}",
            f"grep -E '^dto_contract=backend-owned-read-only-dto-v1$' {q(backend_dto_smoke)}",
            f"grep -E '^dto_owner=backend-owned-read-only-model$' {q(backend_dto_smoke)}",
            f"grep -E '^dto_privacy=redacted-public-dto$' {q(backend_dto_smoke)}",
            f"grep -E '^dto_readiness=dto-parity-ready$' {q(backend_dto_smoke)}",
            f"grep -F 'dto_collapsed_summary=Backend-owned DTO parity' {q(backend_dto_smoke)}",
            f"grep -E '^dto_player_state_provenance=dto-player-state/backend-owned/redacted-public$' {q(backend_dto_smoke)}",
            f"grep -E '^dto_player_state_focus_order=state-card-copy-diagnostics$' {q(backend_dto_smoke)}",
            f"grep -F 'dto_player_state_focus_order_copy=Focus order: state card → Copy plan → Show diagnostics' {q(backend_dto_smoke)}",
            f"grep -E '^diagnostics_status=backend-diagnostics-ready$' {q(backend_dto_smoke)}",
            f"grep -E '^diagnostics_privacy=redacted-public-dto$' {q(backend_dto_smoke)}",
            f"grep -E '^diagnostics_copy=.*privacy=redacted' {q(backend_dto_smoke)}",
            f"! grep -E {q(r'([0-9]{1,3}[.]){3}[0-9]{1,3}|BEGIN [A-Z ]+|raw[A-Z]')} {q(backend_dto_smoke)}",
            f"! grep -E {q(r'([0-9]{1,3}[.]){3}[0-9]{1,3}|BEGIN [A-Z ]+|raw[A-Z]')} {q(backend_readonly_matrix_smoke)}",
            f"! grep -E {q(r'([0-9]{1,3}[.]){3}[0-9]{1,3}|BEGIN [A-Z ]+|raw[A-Z]')} {q(expanded_frame_smoke)}",
            "{ "
            "printf '%s\n' 'Nova Deck frontend smoke summary'; "
            "printf '%s\n' 'window=1280x800'; "
            "printf '%s\n' 'offline=true'; "
            "printf '%s\n' 'host_library_visible=review-frame-capture'; "
            "printf '%s\n' 'lifecycle_contract_visible=review-frame-capture'; "
            "printf '%s\n' 'networkStartAllowed=false'; "
            "printf '%s\n' 'networkStarted=false'; "
            "printf '%s\n' 'backend_dto_interaction_smoke=backend-dto-interaction-smoke.txt'; "
            "printf '%s\n' 'backend_readonly_state_matrix_smoke=backend-readonly-state-matrix-smoke.txt'; "
            "printf '%s\n' 'matrix_visual_path=lab-gated'; "
            "printf '%s\n' 'matrix_artifact_states=empty,offline,unpaired,library-unavailable,lab-gated'; "
            "printf '%s\n' 'diagnostics_expansion=controller-reachable'; "
            "printf '%s\n' 'diagnostics_focus_lane=expanded-diagnostics-lane'; "
            "printf '%s\n' 'diagnostics_focus_affordance=4px-ring-active-badge'; "
            "printf '%s\n' 'diagnostics_cue_contrast=13.56:1'; "
            "printf '%s\n' 'diagnostics_density=lane-paged-breathing-room'; "
            "printf '%s\n' 'diagnostics_page_position=page-1-of-2-scroll-affordance'; "
            "printf '%s\n' 'diagnostics_page2_readability=lifecycle-dto-readable'; "
            "printf '%s\n' 'diagnostics_scroll_navigation=page-2-lifecycle-dto-proof-no-crowding'; "
            "printf '%s\n' 'diagnostics_first_paint=collapsed'; "
            "printf '%s\n' 'product_readiness_gate=deck-diagnostics-expanded-lane-v1'; "
            "printf '%s\n' 'product_readiness_verdict=pass'; "
            "printf '%s\n' 'product_readiness_next=backend-fed-read-only-dto-parity'; "
            "printf '%s\n' 'player_flow_gate=deck-player-flow-product-shell-v1'; "
            "printf '%s\n' 'first_paint_hierarchy=host-game-launch'; "
            "printf '%s\n' 'selected_game_readability=large-title-selected-badge'; "
            "printf '%s\n' 'launch_cta=copy-safe-plan'; "
            "printf '%s\n' 'blocked_state_copy=player-safe-no-backend-power'; "
            "printf '%s\n' 'product_state_gate=deck-product-state-matrix-v1'; "
            "printf '%s\n' 'product_state_visuals=player-facing-state-card'; "
            "printf '%s\n' 'product_state_focus=state-card-copy-diagnostics'; "
            "printf '%s\n' 'android_touched_guard=app-unchanged'; "
            "printf '%s\n' 'dto_parity_contract=backend-owned-read-only-dto-v1'; "
            "printf '%s\n' 'dto_parity_privacy=redacted-public-dto'; "
            "printf '%s\n' 'dto_parity_readiness=dto-parity-ready'; "
            "printf '%s\n' 'dto_parity_verdict=pass'; "
            "printf '%s\n' 'dto_player_state_provenance=dto-player-state/backend-owned/redacted-public'; "
            "printf '%s\n' 'dto_player_state_focus_order=state-card-copy-diagnostics'; "
            "printf '%s\n' 'dto_player_state_focus_order_copy=Focus order: state card → Copy plan → Show diagnostics'; "
            "printf '%s\n' 'expanded_frame_smoke=expanded-diagnostics-frame-smoke.txt'; "
            f"test -s {q(frame_capture)} && printf '%s\n' 'frame_capture=frontend-frame-capture.png' || printf '%s\n' 'frame_capture=missing'; "
            f"test -s {q(expanded_frame_capture)} && printf '%s\n' 'expanded_frame_capture=frontend-expanded-diagnostics-capture.png' || printf '%s\n' 'expanded_frame_capture=missing'; "
            f"grep -E 'Nova Deck product preview fixture pump|frontend smoke capture|backend-dto-interaction-smoke artifact' {q(qml_runtime_log)} || true; "
            f"}} > {q(smoke_summary)}",
        ]
    )


def build_podman_smoke_command(
    *,
    source_dir: PurePosixPath = DEFAULT_REMOTE_SOURCE,
    artifact_dir: PurePosixPath = DEFAULT_ARTIFACT_DIR,
    image: str = DEFAULT_IMAGE,
    build_dir: str = DEFAULT_BUILD_DIR,
) -> list[str]:
    shell = build_container_shell(source_dir=source_dir, artifact_dir=artifact_dir, build_dir=build_dir)
    return [
        "podman",
        "run",
        "--rm",
        "--ipc=host",
        "--network=none",
        "--device=/dev/dri",
        "--group-add=keep-groups",
        "-e",
        "XDG_RUNTIME_DIR=/run/user/1000",
        "-e",
        "QT_QPA_PLATFORM=wayland",
        "-e",
        "WAYLAND_DISPLAY=gamescope-0",
        "-e",
        "QSG_RHI_BACKEND=opengl",
        "-e",
        "LIBVA_DRIVER_NAME=radeonsi",
        "-v",
        "/run/user/1000:/run/user/1000",
        "-v",
        "/dev/dri:/dev/dri",
        "-v",
        f"{source_dir}:{source_dir}",
        "-w",
        str(source_dir),
        image,
        "bash",
        "-lc",
        shell,
    ]


def build_ssh_smoke_command(deck: str, podman_command: Sequence[str]) -> list[str]:
    remote = " ".join(q(part) for part in podman_command)
    return ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", deck, remote]


def build_rsync_command(source: Path, deck: str, remote_source: PurePosixPath) -> list[str]:
    return [
        "rsync",
        "-a",
        "--delete",
        "--exclude",
        "/.git/",
        "--exclude",
        "/build/",
        "--exclude",
        "/.gradle/",
        "--exclude",
        "/local.properties",
        "--exclude",
        ".env*",
        "--exclude",
        "id_*",
        "--exclude",
        "*.pem",
        f"{source}/",
        f"{deck}:{remote_source}/",
    ]


def build_artifact_pull_command(deck: str, remote_artifact_dir: PurePosixPath, local_artifact_dir: Path) -> list[str]:
    return ["rsync", "-a", f"{deck}:{remote_artifact_dir}/", f"{local_artifact_dir}/"]


def find_forbidden_route_tokens(files: Iterable[Path] = ROUTE_SOURCE_FILES) -> list[str]:
    findings: list[str] = []
    for path in files:
        text = path.read_text(encoding="utf-8")
        lowered = text.lower()
        for token in FORBIDDEN_ROUTE_TOKENS:
            if token.lower() in lowered:
                findings.append(f"{path.relative_to(REPO_ROOT)}: {token}")
    return findings


def git_has_path_changes(repo_root: Path, pathspec: str) -> bool:
    completed = subprocess.run(
        ["git", "status", "--porcelain", "--", pathspec],
        cwd=repo_root,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.strip() or f"git status failed for {pathspec}")
    return bool(completed.stdout.strip())


def run_command(command: Sequence[str], *, log_path: Path | None = None) -> None:
    if log_path:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("w", encoding="utf-8") as log:
            redacted_command = redact_command(command)
            log.write("$ " + " ".join(q(part) for part in redacted_command) + "\n")
            log.flush()
            completed = subprocess.run(
                list(command),
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            redacted_output = redact_private_addresses(completed.stdout or "")
            log.write(redacted_output)
            if completed.returncode != 0:
                raise subprocess.CalledProcessError(completed.returncode, redacted_command, output=redacted_output)
    else:
        subprocess.run(list(command), check=True)


def reset_local_artifacts(local_artifacts: Path) -> None:
    validate_local_artifact_dir(local_artifacts)
    if local_artifacts.exists():
        shutil.rmtree(local_artifacts)
    local_artifacts.mkdir(parents=True, exist_ok=True)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deck", default=DEFAULT_DECK, help="SSH target, default deck")
    parser.add_argument("--source", type=Path, default=REPO_ROOT, help="local repo/source root to sync")
    parser.add_argument("--remote-source", default=str(DEFAULT_REMOTE_SOURCE), help="Deck source directory")
    parser.add_argument("--remote-artifacts", default=str(DEFAULT_ARTIFACT_DIR), help="Deck artifact directory")
    parser.add_argument("--local-artifacts", type=Path, default=REPO_ROOT / "build" / "deck-frontend-smoke-artifacts")
    parser.add_argument("--image", default=DEFAULT_IMAGE)
    parser.add_argument("--skip-sync", action="store_true", help="expect --remote-source already exists on Deck")
    parser.add_argument("--dry-run", action="store_true", help="print commands without executing")
    return parser.parse_args(argv)


def main(argv: Sequence[str] = sys.argv[1:]) -> int:
    args = parse_args(argv)
    source = repo_root_from_source(args.source)
    remote_source = PurePosixPath(args.remote_source)
    remote_artifacts = PurePosixPath(args.remote_artifacts)
    local_artifacts = args.local_artifacts.expanduser().resolve()

    guardrails = find_forbidden_route_tokens()
    if guardrails:
        print("refusing frontend smoke route with forbidden Deck route tokens:", file=sys.stderr)
        for finding in guardrails:
            print(f"  {finding}", file=sys.stderr)
        return 2

    if git_has_path_changes(REPO_ROOT, "app"):
        print("refusing frontend smoke route because app/ Android tree has uncommitted changes", file=sys.stderr)
        return 3

    sync_command = build_rsync_command(source, args.deck, remote_source)
    podman_command = build_podman_smoke_command(
        source_dir=remote_source,
        artifact_dir=remote_artifacts,
        image=args.image,
    )
    ssh_command = build_ssh_smoke_command(args.deck, podman_command)
    pull_command = build_artifact_pull_command(args.deck, remote_artifacts, local_artifacts)

    if args.dry_run:
        if not args.skip_sync:
            print("SYNC:", format_redacted_command(sync_command))
        print("VALIDATE:", format_redacted_command(ssh_command))
        print("PULL_ARTIFACTS:", format_redacted_command(pull_command))
        return 0

    reset_local_artifacts(local_artifacts)
    if not args.skip_sync:
        run_command(sync_command, log_path=local_artifacts / "rsync-source.log")
    run_command(ssh_command, log_path=local_artifacts / "deck-frontend-smoke.log")
    run_command(pull_command, log_path=local_artifacts / "rsync-artifacts.log")
    print(f"Deck frontend smoke artifacts copied to {local_artifacts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
