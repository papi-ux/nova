#!/usr/bin/env python3
import pathlib
import sys
import unittest
sys.dont_write_bytecode = True

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import deck_gamemode_capture as harness


class DeckGameModeCaptureHarnessTest(unittest.TestCase):
    def test_rejects_helpers_and_selects_1280x800_window(self):
        candidates = [
            harness.WindowCandidate(window_id="0x01", name="Nova Deck helper", geometry=harness.Geometry(0, 0, 1, 1), pid=4100),
            harness.WindowCandidate(window_id="0x02", name="Nova Deck", geometry=harness.Geometry(0, 0, 1280, 800), pid=4242),
        ]
        selected = harness.select_nova_deck_window(candidates, expected_pid=4242)
        self.assertEqual(selected.window_id, "0x02")

    def test_fails_closed_when_two_plausible_windows_remain(self):
        candidates = [
            harness.WindowCandidate(window_id="0x02", name="Nova Deck", geometry=harness.Geometry(0, 0, 1280, 800), pid=4242),
            harness.WindowCandidate(window_id="0x03", name="Nova Deck", geometry=harness.Geometry(0, 0, 1280, 800), pid=4243),
        ]
        with self.assertRaisesRegex(harness.SelectionError, "ambiguous"):
            harness.select_nova_deck_window(candidates)

    def test_old_tail_selector_fixture_would_choose_wrong_window(self):
        candidates = [
            harness.WindowCandidate(window_id="0x02", name="Nova Deck", geometry=harness.Geometry(0, 0, 1280, 800), pid=4242),
            harness.WindowCandidate(window_id="0x01", name="Nova Deck helper", geometry=harness.Geometry(0, 0, 1, 1), pid=4100),
        ]
        self.assertEqual(candidates[-1].geometry.width, 1)
        self.assertEqual(harness.select_nova_deck_window(candidates, expected_pid=4242).window_id, "0x02")

    def test_parses_xdotool_shell_geometry(self):
        geometry = harness.parse_xdotool_geometry("WINDOW=123\nX=12\nY=34\nWIDTH=1280\nHEIGHT=800\nSCREEN=0\n")
        self.assertEqual(geometry, harness.Geometry(12, 34, 1280, 800))

    def test_redacts_sensitive_session_environment_output(self):
        raw = "DISPLAY=:1\nAPI_TOKEN=supersecretvalue\nHERMES_PASSWORD=badsecret\nNORMAL=value\n"
        redacted = harness.redact_sensitive_output(raw)
        self.assertIn("DISPLAY=:1", redacted)
        self.assertIn("NORMAL=value", redacted)
        self.assertIn("API_TOKEN=[REDACTED]", redacted)
        self.assertIn("HERMES_PASSWORD=[REDACTED]", redacted)
        self.assertNotIn("supersecretvalue", redacted)
        self.assertNotIn("badsecret", redacted)


if __name__ == "__main__":
    unittest.main()
