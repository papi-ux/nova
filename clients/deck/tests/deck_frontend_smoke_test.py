#!/usr/bin/env python3
import contextlib
import io
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = ROOT.parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import deck_frontend_smoke as smoke


class DeckFrontendSmokeRouteTest(unittest.TestCase):
    def test_podman_command_runs_visible_wayland_shell_offline_and_writes_frontend_artifacts(self):
        command = smoke.build_podman_smoke_command(
            source_dir=pathlib.PurePosixPath("/home/deck/nova-frontend-smoke-src"),
            artifact_dir=pathlib.PurePosixPath("/home/deck/nova-frontend-smoke-src/build/deck-frontend-smoke-artifacts"),
        )
        joined = " ".join(command)

        self.assertEqual(command[:3], ["podman", "run", "--rm"])
        self.assertIn("--network=none", command)
        self.assertIn("localhost/nova-t24-arch-qt-buildtools", command)
        self.assertIn("QT_QPA_PLATFORM=wayland", joined)
        self.assertIn("WAYLAND_DISPLAY=gamescope-0", joined)
        self.assertIn("QSG_RHI_BACKEND=opengl", joined)
        self.assertIn("NOVA_DECK_FRONTEND_SMOKE=1", joined)
        self.assertIn("environment-summary.txt", joined)
        self.assertIn("ui-launch.log", joined)
        self.assertIn("qml-runtime.log", joined)
        self.assertIn("smoke-summary.txt", joined)
        self.assertIn("backend-dto-interaction-smoke.txt", joined)
        self.assertIn("backend-readonly-state-matrix-smoke.txt", joined)
        self.assertIn("frontend-frame-capture.png", joined)
        self.assertIn("frontend-expanded-diagnostics-capture.png", joined)
        self.assertIn("expanded-diagnostics-frame-smoke.txt", joined)
        self.assertIn("nova-deck --frontend-smoke-exit-after-ms 2500", joined)
        self.assertIn("--frontend-smoke-backend-dto-interactions", joined)
        self.assertIn("--frontend-smoke-readonly-state lab-gated", joined)
        self.assertIn("--frontend-smoke-readonly-state-matrix", joined)
        self.assertIn("--frontend-smoke-capture", joined)
        self.assertIn("--frontend-smoke-expanded-diagnostics-frame", joined)
        self.assertIn("--frontend-smoke-expanded-diagnostics-capture", joined)

    def test_container_shell_asserts_backend_dto_interaction_artifact_is_public_and_sanitized(self):
        shell = smoke.build_container_shell(
            source_dir=pathlib.PurePosixPath("/src"),
            artifact_dir=pathlib.PurePosixPath("/src/build/deck-frontend-smoke-artifacts"),
        )

        self.assertIn("backend-dto-interaction-smoke.txt", shell)
        self.assertIn("backend-preflight-dto-preview", shell)
        self.assertIn("backend-diagnostics-dto-preview", shell)
        self.assertIn("backend-preflight-blocked", shell)
        self.assertIn("backend-diagnostics-ready", shell)
        self.assertIn("lab-gate-disabled", shell)
        self.assertIn("redacted-public-dto", shell)
        self.assertIn("backend-owned-read-only-dto-v1", shell)
        self.assertIn("dto-parity-ready", shell)
        self.assertIn("dto_contract=backend-owned-read-only-dto-v1", shell)
        self.assertIn("dto_privacy=redacted-public-dto", shell)
        self.assertIn("dto_readiness=dto-parity-ready", shell)
        self.assertIn("dto_owner=backend-owned-read-only-model", shell)
        self.assertIn("dto_parity_contract=backend-owned-read-only-dto-v1", shell)
        self.assertIn("dto_parity_verdict=pass", shell)
        self.assertIn("backend-readonly-state-matrix-smoke.txt", shell)
        for scenario in ["empty", "offline", "unpaired", "library-unavailable", "lab-gated"]:
            self.assertIn(f"matrix_scenario={scenario}", shell)
        self.assertIn("primary=", shell)
        self.assertIn("diagnostics=", shell)
        self.assertIn("collapsedFirstPaint=true", shell)
        self.assertIn("expansionToggle=secondary-diagnostics-toggle", shell)
        self.assertIn("controllerReachable=true", shell)
        self.assertIn("expandedVisible=true", shell)
        self.assertIn("expandedDiagnosticsCopy=Matrix diagnostic:", shell)
        self.assertIn("expandedDtoParityCopy=DTO parity:", shell)
        self.assertIn("dtoParity=DTO parity:", shell)
        self.assertIn("diagnostics_expansion=controller-reachable", shell)
        self.assertIn("diagnostics_first_paint=collapsed", shell)
        self.assertIn("expanded-diagnostics-frame-smoke.txt", shell)
        self.assertIn("frontend-expanded-diagnostics-capture.png", shell)
        self.assertIn("expanded_frame_smoke=expanded-diagnostics-frame-smoke.txt", shell)
        self.assertIn("expanded_frame_capture=frontend-expanded-diagnostics-capture.png", shell)
        self.assertIn("liveExpandedBy=keyboard-controller-toggle", shell)
        self.assertIn("expandedFrameSanitized=true", shell)
        self.assertIn("expandedFrameReadable=true", shell)
        self.assertIn("expandedFrameFocusTarget=secondary-diagnostics-toggle", shell)
        self.assertIn("expandedDiagnosticsLaneFocusTarget=expanded-diagnostics-lane", shell)
        self.assertIn("expandedDiagnosticsLaneReadable=true", shell)
        self.assertIn("expandedDensityRowsPaged=true", shell)
        self.assertIn("expandedDiagnosticsLaneHeight=132", shell)
        self.assertIn("expandedDiagnosticsPageAffordanceVisible=true", shell)
        self.assertIn("expandedDiagnosticsPageAffordancePosition=before-blocker-copy", shell)
        self.assertIn("expandedDiagnosticsPageAffordanceText=Diagnostics page 1 of 2", shell)
        self.assertIn("expandedDiagnosticsScrollNavigationMoved=true", shell)
        self.assertIn("expandedDiagnosticsPostScrollCue=Diagnostics page 2 of 2", shell)
        self.assertIn("expandedDiagnosticsPostScrollCueContrast=13", shell)
        self.assertIn("expandedDiagnosticsPostScrollCueSpacing=separate-row-after-blocker-copy", shell)
        self.assertIn("expandedDiagnosticsPostScrollCueOverlapsBlocker=false", shell)
        self.assertIn("expandedDiagnosticsPostScrollTarget=lifecycle-dto-details", shell)
        self.assertIn("expandedDiagnosticsFocusAffordance=4px focus ring + active focus badge", shell)
        self.assertIn("expandedDiagnosticsPage2Readable=true", shell)
        self.assertIn("diagnostics_page_position=page-1-of-2-scroll-affordance", shell)
        self.assertIn("diagnostics_scroll_navigation=page-2-lifecycle-dto-proof-no-crowding", shell)
        self.assertIn("diagnostics_focus_lane=expanded-diagnostics-lane", shell)
        self.assertIn("diagnostics_focus_affordance=4px-ring-active-badge", shell)
        self.assertIn("diagnostics_cue_contrast=13.56:1", shell)
        self.assertIn("diagnostics_page2_readability=lifecycle-dto-readable", shell)
        self.assertIn("diagnostics_density=lane-paged-breathing-room", shell)
        self.assertIn("product_readiness_gate=deck-diagnostics-expanded-lane-v1", shell)
        self.assertIn("product_readiness_verdict=pass", shell)
        self.assertIn("product_readiness_next=backend-fed-read-only-dto-parity", shell)
        self.assertIn("player_flow_gate=deck-player-flow-product-shell-v1", shell)
        self.assertIn("first_paint_hierarchy=host-game-launch", shell)
        self.assertIn("selected_game_readability=large-title-selected-badge", shell)
        self.assertIn("launch_cta=copy-safe-plan", shell)
        self.assertIn("blocked_state_copy=player-safe-no-backend-power", shell)
        self.assertIn("product_state_gate=deck-product-state-matrix-v1", shell)
        self.assertIn("product_state_visuals=player-facing-state-card", shell)
        self.assertIn("product_state_focus=state-card-copy-diagnostics", shell)
        self.assertIn("stateHeadline=Product state: Host offline", shell)
        self.assertIn("stateHeadline=Product state: Library unavailable", shell)
        self.assertIn("stateHeadline=Product state: Lab gate locked", shell)
        self.assertIn("stateAction=Reconnect the host or choose another backend-owned snapshot.", shell)
        self.assertIn("stateSafety=Backend power stays off; no retry or network probe runs.", shell)
        self.assertIn("stateProvenance=dto-player-state/backend-owned/redacted-public", shell)
        self.assertIn("stateFocusOrder=state-card-copy-diagnostics", shell)
        self.assertIn("dto_player_state_provenance=dto-player-state/backend-owned/redacted-public", shell)
        self.assertIn("dto_player_state_focus_order=state-card-copy-diagnostics", shell)
        self.assertIn("dto_player_state_focus_order_copy=Focus order: state card → Copy plan → Show diagnostics", shell)
        self.assertIn("diagnostics=Matrix diagnostic:", shell)
        self.assertIn("android_touched_guard=app-unchanged", shell)
        self.assertIn("expandedFrameFirstPaintCrowding=false", shell)
        self.assertIn("Launch blocked by lab gate.", shell)
        self.assertIn("Host offline. Reconnect or pick another host.", shell)
        self.assertIn("Pair this host before launch preview.", shell)
        self.assertIn("Library unavailable. Try again when the read-only snapshot is back.", shell)
        self.assertIn("awk -F'primary='", shell)
        self.assertIn(
            f"! grep -E {smoke.q(r'([0-9]{1,3}[.]){3}[0-9]{1,3}|BEGIN [A-Z ]+|raw[A-Z]')}",
            shell,
        )

    def test_container_shell_clears_remote_artifacts_before_collecting_current_run(self):
        shell = smoke.build_container_shell(
            source_dir=pathlib.PurePosixPath("/src"),
            artifact_dir=pathlib.PurePosixPath("/src/build/deck-frontend-smoke-artifacts"),
        )

        self.assertIn("rm -rf /src/build/deck-frontend-smoke-artifacts && mkdir -p /src/build/deck-frontend-smoke-artifacts", shell)
        self.assertLess(shell.index("rm -rf /src/build/deck-frontend-smoke-artifacts"), shell.index("environment-summary.txt"))

    def test_reset_local_artifacts_removes_stale_files_before_current_run(self):
        artifact_dir = smoke.REPO_ROOT / "build" / "deck-frontend-smoke-artifacts-test"
        stale = artifact_dir / "frontend-frame-capture.png"
        try:
            artifact_dir.mkdir(parents=True, exist_ok=True)
            stale.write_text("stale", encoding="utf-8")

            smoke.reset_local_artifacts(artifact_dir)

            self.assertTrue(artifact_dir.is_dir())
            self.assertFalse(stale.exists())
        finally:
            if artifact_dir.exists():
                smoke.shutil.rmtree(artifact_dir)

    def test_main_resets_local_artifacts_before_smoke_commands(self):
        artifact_dir = smoke.REPO_ROOT / "build" / "deck-frontend-smoke-artifacts-main-test"
        stale = artifact_dir / "stale.txt"
        try:
            artifact_dir.mkdir(parents=True, exist_ok=True)
            stale.write_text("stale", encoding="utf-8")

            with (
                mock.patch.object(smoke, "find_forbidden_route_tokens", return_value=[]),
                mock.patch.object(smoke, "run_command") as run_command,
            ):
                exit_code = smoke.main([
                    "--skip-sync",
                    "--local-artifacts",
                    str(artifact_dir),
                ])

            self.assertEqual(exit_code, 0)
            self.assertFalse(stale.exists())
            self.assertEqual(run_command.call_count, 2)
            self.assertEqual(
                run_command.call_args_list[0].kwargs["log_path"],
                artifact_dir / "deck-frontend-smoke.log",
            )
            self.assertEqual(
                run_command.call_args_list[1].kwargs["log_path"],
                artifact_dir / "rsync-artifacts.log",
            )
        finally:
            if artifact_dir.exists():
                smoke.shutil.rmtree(artifact_dir)

    def test_main_refuses_to_run_when_android_app_tree_has_uncommitted_changes(self):
        with mock.patch.object(smoke, "git_has_path_changes", return_value=True) as changed:
            exit_code = smoke.main([
                "--dry-run",
                "--skip-sync",
                "--local-artifacts",
                "/repo/build/deck-frontend-smoke-artifacts",
            ])

        self.assertEqual(exit_code, 3)
        changed.assert_called_once_with(smoke.REPO_ROOT, "app")

    def test_main_checks_android_app_untouched_before_sync_and_deck_smoke(self):
        artifact_dir = smoke.REPO_ROOT / "build" / "deck-frontend-smoke-artifacts-android-guard-test"
        try:
            with (
                mock.patch.object(smoke, "find_forbidden_route_tokens", return_value=[]),
                mock.patch.object(smoke, "git_has_path_changes", return_value=False) as unchanged,
                mock.patch.object(smoke, "run_command") as run_command,
            ):
                exit_code = smoke.main([
                    "--skip-sync",
                    "--local-artifacts",
                    str(artifact_dir),
                ])

            self.assertEqual(exit_code, 0)
            unchanged.assert_called_once_with(smoke.REPO_ROOT, "app")
            self.assertEqual(run_command.call_count, 2)
        finally:
            if artifact_dir.exists():
                smoke.shutil.rmtree(artifact_dir)

    def test_remote_artifact_cleanup_refuses_source_dir_and_paths_outside_source(self):
        unsafe_paths = (
            pathlib.PurePosixPath("/"),
            pathlib.PurePosixPath("/home/deck"),
            pathlib.PurePosixPath("/src"),
            pathlib.PurePosixPath("/src/deck-frontend-smoke-artifacts"),
            pathlib.PurePosixPath("/tmp/deck-frontend-smoke-artifacts"),
            pathlib.PurePosixPath(""),
        )
        for artifact_dir in unsafe_paths:
            with self.subTest(artifact_dir=str(artifact_dir)):
                with self.assertRaises(ValueError):
                    smoke.build_container_shell(
                        source_dir=pathlib.PurePosixPath("/src"),
                        artifact_dir=artifact_dir,
                    )

    def test_remote_artifact_cleanup_refuses_parent_traversal(self):
        traversal_paths = (
            pathlib.PurePosixPath("/src/../deck-frontend-smoke-artifacts"),
            pathlib.PurePosixPath("/src/build/../../deck-frontend-smoke-artifacts"),
        )
        for artifact_dir in traversal_paths:
            with self.subTest(artifact_dir=str(artifact_dir)):
                with self.assertRaises(ValueError):
                    smoke.build_container_shell(
                        source_dir=pathlib.PurePosixPath("/src"),
                        artifact_dir=artifact_dir,
                    )

    def test_local_artifact_cleanup_refuses_broad_or_non_artifact_paths(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            broad = pathlib.Path(tmpdir)
            unsafe_paths = (
                pathlib.Path("/"),
                pathlib.Path.home(),
                smoke.REPO_ROOT,
                smoke.REPO_ROOT / "deck-frontend-smoke-artifacts",
                pathlib.Path(""),
                broad,
                broad / "deck-output",
                broad / "deck-frontend-smoke-artifacts",
            )
            for artifact_dir in unsafe_paths:
                with self.subTest(artifact_dir=str(artifact_dir)):
                    if not artifact_dir.exists() and artifact_dir.is_absolute():
                        artifact_dir.mkdir(parents=True)
                    with self.assertRaises(ValueError):
                        smoke.reset_local_artifacts(artifact_dir)

    def test_local_artifact_cleanup_refuses_parent_traversal_outside_build_dir(self):
        unsafe = smoke.REPO_ROOT / "build" / ".." / "deck-frontend-smoke-artifacts"

        with self.assertRaises(ValueError):
            smoke.reset_local_artifacts(unsafe)

    def test_dry_run_prints_sync_validate_pull_and_no_oracle(self):
        stdout = io.StringIO()

        with contextlib.redirect_stdout(stdout):
            exit_code = smoke.main([
                "--dry-run",
                "--skip-sync",
                "--local-artifacts",
                "/repo/build/deck-frontend-smoke-artifacts",
            ])

        text = stdout.getvalue()
        self.assertEqual(exit_code, 0)
        self.assertIn("VALIDATE:", text)
        self.assertIn("PULL_ARTIFACTS:", text)
        self.assertNotIn("ORACLE:", text)

    def test_route_guardrails_reject_streaming_discovery_pairing_credentials_and_private_hosts(self):
        self.assertEqual(smoke.find_forbidden_route_tokens(), [])

    def test_diagnostics_product_readiness_checklist_captures_reusable_gate(self):
        checklist = REPO_ROOT / "docs" / "deck-product-readiness-checklist.md"
        text = checklist.read_text(encoding="utf-8")

        required_contract = (
            "deck-diagnostics-expanded-lane-v1",
            "collapsed first paint",
            "page-1 cue",
            "focus ring",
            "active focus badge",
            "D-pad scroll to page 2",
            "right-rail breathing room",
            "page-2 cue contrast/readability",
            "lifecycle idle/no stream",
            "redacted-public-dto",
            "sanitized artifacts",
            "backendPowerStarted=false",
            "stream=false",
            "backend-fed read-only DTO parity",
        )
        for required in required_contract:
            self.assertIn(required, text)

        forbidden_scope = (
            "HostStore",
            "Moonlight",
            "LiStartConnection",
            "credential read",
            "discovery probe",
            "input packet path",
        )
        for forbidden in forbidden_scope:
            self.assertNotIn(forbidden, text)

    def test_source_sync_excludes_build_git_and_secret_shaped_files(self):
        command = smoke.build_rsync_command(
            pathlib.Path("/repo"),
            "deck@10.0.0.39",
            pathlib.PurePosixPath("/home/deck/nova-frontend-smoke-src"),
        )
        joined = " ".join(command)

        for excluded in ["/.git/", "/build/", "/.gradle/", "/local.properties", ".env*", "id_*", "*.pem"]:
            self.assertIn(excluded, joined)

    def test_command_log_redacts_private_deck_addresses(self):
        self.assertEqual(
            smoke.redact_private_addresses("rsync deck@10.0.0.39:/home/deck/source"),
            "rsync deck@<private-ip>:/home/deck/source",
        )
        self.assertEqual(
            smoke.redact_private_addresses("ssh: Could not resolve hostname steamdeck.local"),
            "ssh: Could not resolve hostname <private-host>",
        )

    def test_run_command_redacts_private_addresses_from_subprocess_output_logs(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            log_path = pathlib.Path(tmpdir) / "smoke.log"

            smoke.run_command(
                [sys.executable, "-c", "print('ssh: connect to host 10.0.0.39 port 22 failed')"],
                log_path=log_path,
            )

            log = log_path.read_text(encoding="utf-8")
            self.assertIn("<private-ip>", log)
            self.assertNotIn("10.0.0.39", log)

    def test_run_command_redacts_failed_subprocess_stderr_in_logs_and_exceptions(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            log_path = pathlib.Path(tmpdir) / "rsync-artifacts.log"

            with self.assertRaises(smoke.subprocess.CalledProcessError) as failure:
                smoke.run_command(
                    [
                        sys.executable,
                        "-c",
                        "import sys; print('rsync: steamdeck.local failed from 10.0.0.39 via 192.168.1.77', file=sys.stderr); sys.exit(23)",
                        "deck@10.0.0.39:/remote/artifacts",
                        "steamdeck.local:/remote/artifacts",
                    ],
                    log_path=log_path,
                )

            log = log_path.read_text(encoding="utf-8")
            self.assertIn("<private-ip>", log)
            self.assertIn("<private-host>", log)
            self.assertNotIn("10.0.0.39", log)
            self.assertNotIn("192.168.1.77", log)
            self.assertNotIn("steamdeck.local", log)
            self.assertNotIn("10.0.0.39", failure.exception.output)
            self.assertNotIn("192.168.1.77", failure.exception.output)
            self.assertNotIn("steamdeck.local", failure.exception.output)
            self.assertNotIn("10.0.0.39", str(failure.exception))
            self.assertNotIn("steamdeck.local", str(failure.exception))
            self.assertNotIn("10.0.0.39", " ".join(failure.exception.cmd))
            self.assertNotIn("steamdeck.local", " ".join(failure.exception.cmd))

    def test_dry_run_redacts_private_deck_targets(self):
        stdout = io.StringIO()

        with contextlib.redirect_stdout(stdout):
            exit_code = smoke.main(["--dry-run"])

        text = stdout.getvalue()
        self.assertEqual(exit_code, 0)
        self.assertIn("<private-ip>", text)
        self.assertNotIn("10.0.0.39", text)


if __name__ == "__main__":
    unittest.main()
