#!/usr/bin/env python3
import contextlib
import io
import pathlib
import sys
import unittest

sys.dont_write_bytecode = True

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import deck_t31_podman_validation as route


class DeckT31PodmanValidationRouteTest(unittest.TestCase):
    def test_podman_command_mounts_gamescope_runtime_dri_and_forces_deck_render_environment(self):
        command = route.build_podman_validation_command(
            source_dir=pathlib.PurePosixPath("/var/tmp/nova-t31-src"),
            artifact_dir=pathlib.PurePosixPath("/var/tmp/nova-t31-src/build/deck-t31-artifacts"),
        )
        joined = " ".join(command)

        self.assertEqual(command[:3], ["podman", "run", "--rm"])
        self.assertIn("localhost/nova-t24-arch-qt-buildtools", command)
        self.assertIn("/run/user/1000:/run/user/1000", joined)
        self.assertIn("/dev/dri:/dev/dri", joined)
        self.assertIn("QT_QPA_PLATFORM=wayland", joined)
        self.assertIn("WAYLAND_DISPLAY=gamescope-0", joined)
        self.assertIn("QSG_RHI_BACKEND=opengl", joined)
        self.assertIn("LIBVA_DRIVER_NAME=radeonsi", joined)
        self.assertIn("ctest --test-dir build/deck-t31", joined)
        self.assertIn("nova_deck_qsg_render_node_scenegraph_smoke", joined)
        self.assertIn("(cp -f build/deck-t31/Testing/Temporary/LastTest.log", joined)
        self.assertNotIn("LastTest.log || true && QT_QPA_PLATFORM=wayland", joined)
        self.assertIn("build/deck-t31-artifacts", joined)

    def test_route_runs_t32_preview_pump_oracle_after_artifact_pull(self):
        command = route.build_oracle_command(pathlib.Path("/repo/build/deck-t32-artifacts"))

        self.assertEqual(command[0], sys.executable)
        self.assertIn("deck_t32_preview_pump_oracle.py", command[1])
        self.assertEqual(command[-2:], ["--artifacts", "/repo/build/deck-t32-artifacts"])

    def test_guardrails_reject_streaming_host_launch_and_sensitive_routes(self):
        forbidden = route.find_forbidden_route_tokens()
        self.assertEqual(forbidden, [])

    def test_source_sync_excludes_local_build_git_and_secret_shaped_files(self):
        command = route.build_rsync_command(
            pathlib.Path("/repo"),
            "deck@<deck-host>",
            pathlib.PurePosixPath("/var/tmp/nova-t31-src"),
        )
        joined = " ".join(command)

        for excluded in ["/.git/", "/build/", "/.gradle/", "/local.properties", ".env*", "id_*", "*.pem"]:
            self.assertIn(excluded, joined)
        for root_only_secret_pattern in ["/.env*", "/id_*", "/*.pem"]:
            self.assertNotIn(root_only_secret_pattern, command)

    def test_dry_run_prints_the_machine_checkable_oracle_by_default(self):
        stdout = io.StringIO()

        with contextlib.redirect_stdout(stdout):
            exit_code = route.main([
                "--dry-run",
                "--skip-sync",
                "--local-artifacts",
                "/repo/build/deck-t32-artifacts",
            ])

        self.assertEqual(exit_code, 0)
        self.assertIn("ORACLE:", stdout.getvalue())
        self.assertIn("deck_t32_preview_pump_oracle.py", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
