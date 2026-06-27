#!/usr/bin/env python3
"""Repeatable Deck-side rootless Podman validation route for Nova Deck preview smokes.

The route is intentionally local/offline once source is present on the Steam Deck:
it builds the checked-out source inside the known Deck Arch/Qt buildtools image,
runs CTest, then runs the product QSG smoke against the live Game Mode gamescope
Wayland socket. It does not invoke remote-play startup, endpoint scanning, secret handling,
Polaris backend launch flows, or fake stream pixels.
"""
from __future__ import annotations

import argparse
import os
import shlex
import subprocess
import sys
from pathlib import Path, PurePosixPath
from typing import Iterable, Sequence

DEFAULT_DECK = "deck@10.0.0.39"
DEFAULT_REMOTE_SOURCE = PurePosixPath("/home/deck/nova-t31-src")
DEFAULT_ARTIFACT_DIR = PurePosixPath("/home/deck/nova-t31-src/build/deck-t31-artifacts")
DEFAULT_IMAGE = "localhost/nova-t24-arch-qt-buildtools"
DEFAULT_BUILD_DIR = "build/deck-t31"
DEFAULT_ORACLE = "deck_t32_preview_pump_oracle.py"

SCRIPT_ROOT = Path(__file__).resolve().parent
DECK_ROOT = SCRIPT_ROOT.parent
REPO_ROOT = DECK_ROOT.parents[1]

# Build the words from fragments so the guardrail scanner does not flag the
# scanner itself. These are route boundaries, not a denylist for the whole repo.
FORBIDDEN_ROUTE_TOKENS = [
    "Li" + "StartConnection",
    "Moon" + "light",
    "Sun" + "shine",
    "Host" + "Store",
    "start" + "Stream",
    "launch" + "Game",
]
ROUTE_SOURCE_FILES = [
    SCRIPT_ROOT / "deck_t31_podman_validation.py",
]


def q(value: object) -> str:
    return shlex.quote(str(value))


def repo_root_from_source(source: Path | None) -> Path:
    if source is not None:
        return source.expanduser().resolve()
    return REPO_ROOT


def build_container_shell(
    *,
    source_dir: PurePosixPath,
    artifact_dir: PurePosixPath,
    build_dir: str = DEFAULT_BUILD_DIR,
) -> str:
    ctest_log = artifact_dir / "ctest.log"
    qsg_log = artifact_dir / "qsg-gamescope-smoke.log"
    env_log = artifact_dir / "runtime-env.log"
    return " && ".join(
        [
            "set -euo pipefail",
            f"cd {q(source_dir)}",
            f"mkdir -p {q(artifact_dir)}",
            "printf '%s\n' "
            "\"QT_QPA_PLATFORM=$QT_QPA_PLATFORM\" "
            "\"WAYLAND_DISPLAY=$WAYLAND_DISPLAY\" "
            "\"QSG_RHI_BACKEND=$QSG_RHI_BACKEND\" "
            "\"LIBVA_DRIVER_NAME=$LIBVA_DRIVER_NAME\" "
            f"> {q(env_log)}",
            f"cmake -S clients/deck -B {q(build_dir)} -G Ninja -DNOVA_DECK_BUILD_QT_SHELL=ON",
            f"cmake --build {q(build_dir)}",
            f"ctest --test-dir {q(build_dir)} --output-on-failure 2>&1 | tee {q(ctest_log)}",
            f"(cp -f {q(build_dir)}/Testing/Temporary/LastTest.log {q(artifact_dir / 'LastTest.log')} || true)",
            "QT_QPA_PLATFORM=wayland WAYLAND_DISPLAY=gamescope-0 QSG_RHI_BACKEND=opengl "
            "LIBVA_DRIVER_NAME=radeonsi "
            f"{q(PurePosixPath(build_dir) / 'nova_deck_qsg_render_node_scenegraph_smoke')} "
            f"2>&1 | tee {q(qsg_log)}",
        ]
    )


def build_podman_validation_command(
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


def build_ssh_validation_command(deck: str, podman_command: Sequence[str]) -> list[str]:
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


def build_oracle_command(local_artifact_dir: Path) -> list[str]:
    return [sys.executable, str(SCRIPT_ROOT / DEFAULT_ORACLE), "--artifacts", str(local_artifact_dir)]


def find_forbidden_route_tokens(files: Iterable[Path] = ROUTE_SOURCE_FILES) -> list[str]:
    findings: list[str] = []
    for path in files:
        text = path.read_text(encoding="utf-8")
        lowered = text.lower()
        for token in FORBIDDEN_ROUTE_TOKENS:
            if token.lower() in lowered:
                findings.append(f"{path.relative_to(REPO_ROOT)}: {token}")
    return findings


def run_command(command: Sequence[str], *, log_path: Path | None = None) -> None:
    if log_path:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("w", encoding="utf-8") as log:
            log.write("$ " + " ".join(q(part) for part in command) + "\n")
            log.flush()
            subprocess.run(list(command), check=True, stdout=log, stderr=subprocess.STDOUT, text=True)
    else:
        subprocess.run(list(command), check=True)


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deck", default=DEFAULT_DECK, help="SSH target, default deck@10.0.0.39")
    parser.add_argument("--source", type=Path, default=REPO_ROOT, help="local repo/source root to sync")
    parser.add_argument("--remote-source", default=str(DEFAULT_REMOTE_SOURCE), help="Deck source directory")
    parser.add_argument("--remote-artifacts", default=str(DEFAULT_ARTIFACT_DIR), help="Deck artifact directory")
    parser.add_argument("--local-artifacts", type=Path, default=REPO_ROOT / "build" / "deck-t31-artifacts")
    parser.add_argument("--image", default=DEFAULT_IMAGE)
    parser.add_argument("--skip-sync", action="store_true", help="expect --remote-source already exists on Deck")
    parser.add_argument("--skip-oracle", action="store_true", help="pull artifacts without running the T32 preview pump oracle")
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
        print("refusing route with forbidden Deck T31 tokens:", file=sys.stderr)
        for finding in guardrails:
            print(f"  {finding}", file=sys.stderr)
        return 2

    sync_command = build_rsync_command(source, args.deck, remote_source)
    podman_command = build_podman_validation_command(
        source_dir=remote_source,
        artifact_dir=remote_artifacts,
        image=args.image,
    )
    ssh_command = build_ssh_validation_command(args.deck, podman_command)
    pull_command = build_artifact_pull_command(args.deck, remote_artifacts, local_artifacts)
    oracle_command = build_oracle_command(local_artifacts)

    if args.dry_run:
        if not args.skip_sync:
            print("SYNC:", " ".join(q(part) for part in sync_command))
        print("VALIDATE:", " ".join(q(part) for part in ssh_command))
        print("PULL_ARTIFACTS:", " ".join(q(part) for part in pull_command))
        if not args.skip_oracle:
            print("ORACLE:", " ".join(q(part) for part in oracle_command))
        return 0

    local_artifacts.mkdir(parents=True, exist_ok=True)
    if not args.skip_sync:
        run_command(sync_command, log_path=local_artifacts / "rsync-source.log")
    run_command(ssh_command, log_path=local_artifacts / "deck-podman-validation.log")
    run_command(pull_command, log_path=local_artifacts / "rsync-artifacts.log")
    if not args.skip_oracle:
        run_command(oracle_command, log_path=local_artifacts / "deck-t32-preview-pump-oracle.log")
    print(f"Deck T31 artifacts copied to {local_artifacts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
