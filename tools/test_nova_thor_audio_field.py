import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("nova_thor_audio_field.py")


def load_module(test_case):
    test_case.assertTrue(MODULE_PATH.exists(), "Thor field analyzer must exist")
    spec = importlib.util.spec_from_file_location("nova_thor_audio_field", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class NovaThorAudioFieldTest(unittest.TestCase):
    def test_analyze_logcat_correlates_stream_audio_focus_and_route(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display role stream id=0 target=largest
            Nova: Android display launch stream id=0
            Nova: Android display role companion id=1 stream_id=0
            Nova: Android display audio context stream_id=0 display_id=0
            Nova: Android display audio route display_id=0 device_id=7 type=2
            Nova: Android display focus role=game display_id=0 window=true game_top_resumed=true
            Nova: Android display focus role=companion display_id=1 window=true game_top_resumed=true
            """,
            source_process_scoped=True,
        )

        self.assertEqual(report["schema_version"], 3)
        self.assertEqual(report["stream_display_ids"], [0])
        self.assertEqual(report["companion_display_ids"], [1])
        self.assertEqual(report["audio_context_display_ids"], [0])
        self.assertEqual(
            report["audio_routes"],
            [{"device_id": "7", "display_id": 0, "type": "2"}],
        )
        self.assertTrue(report["checks"]["audio_context_matches_stream"])
        self.assertTrue(report["checks"]["game_top_resumed_observed"])
        self.assertTrue(report["checks"]["companion_window_observed"])
        self.assertTrue(report["checks"]["runtime_errors_absent"])
        self.assertTrue(report["checks"]["source_process_scoped"])
        self.assertTrue(report["checks"]["diagnostic_evidence_complete"])
        self.assertFalse(report["physical_audio_verified"])

    def test_inventory_reports_a_second_output_device_as_a_choice(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display role stream id=0 target=largest
            Nova: Android display audio route display_id=0 device_id=2 type=2
            Nova: Android display audio inventory display_id=0 outputs=2 routes=0
            Nova: Android display audio output device_id=2 type=2 address=none
            Nova: Android display audio output device_id=9 type=9 address=hdmi-top
            """
        )

        self.assertEqual(report["latest_audio_inventory"]["outputs"], 2)
        self.assertEqual(
            report["audio_outputs"],
            [
                {"device_id": "2", "type": "2", "address": "none"},
                {"device_id": "9", "type": "9", "address": "hdmi-top"},
            ],
        )
        self.assertTrue(report["checks"]["audio_inventory_observed"])
        self.assertTrue(report["checks"]["audio_output_choice_exists"])

    def test_single_output_device_is_reported_as_no_choice(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display role stream id=0 target=largest
            Nova: Android display audio inventory display_id=0 outputs=1 routes=0
            Nova: Android display audio output device_id=2 type=2 address=none
            """
        )

        # The whole point of the capture: one output means setPreferredDevice has nowhere to aim,
        # so this reads False rather than None. Only a missing inventory line is unknown.
        self.assertTrue(report["checks"]["audio_inventory_observed"])
        self.assertFalse(report["checks"]["audio_output_choice_exists"])

    def test_presentation_route_carrying_live_audio_is_flagged(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display role stream id=0 target=largest
            Nova: Android display audio inventory display_id=0 outputs=1 routes=2
            Nova: Android display audio output device_id=2 type=2 address=none
            Nova: Android display audio presentation index=0 live_audio=true presentation_display_id=none selected=true
            Nova: Android display audio presentation index=1 live_audio=true presentation_display_id=1 selected=false
            """
        )

        self.assertEqual(
            report["audio_presentation_routes"],
            [
                {
                    "index": 0,
                    "live_audio": True,
                    "presentation_display_id": "none",
                    "selected": True,
                },
                {
                    "index": 1,
                    "live_audio": True,
                    "presentation_display_id": "1",
                    "selected": False,
                },
            ],
        )
        # A route that names a display and carries live audio is the second pre-34 lever, and it
        # is independent of the device count -- here there is only one output device.
        self.assertFalse(report["checks"]["audio_output_choice_exists"])
        self.assertTrue(report["checks"]["presentation_route_offers_audio"])

    def test_route_without_a_presentation_display_offers_no_affinity(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display role stream id=0 target=largest
            Nova: Android display audio inventory display_id=0 outputs=1 routes=1
            Nova: Android display audio presentation index=0 live_audio=true presentation_display_id=none selected=true
            """
        )

        self.assertFalse(report["checks"]["presentation_route_offers_audio"])

    def test_absent_inventory_is_unknown_rather_than_no_choices(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display role stream id=0 target=largest
            Nova: Android display audio route display_id=0 device_id=2 type=2
            """
        )

        # A capture from a build that predates the inventory must not be readable as "this device
        # has one output and no routes" -- that is the exact wrong conclusion to draw from silence.
        self.assertFalse(report["checks"]["audio_inventory_observed"])
        self.assertIsNone(report["checks"]["audio_output_choice_exists"])
        self.assertIsNone(report["checks"]["presentation_route_offers_audio"])

    def test_analyze_logcat_flags_mismatch_and_window_failures(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display role stream id=0 target=primary
            Nova: Android display audio context stream_id=0 display_id=1
            FATAL EXCEPTION: main
            android.view.WindowManager$InvalidDisplayException: Unable to add window
            """
        )

        self.assertFalse(report["checks"]["audio_context_matches_stream"])
        self.assertFalse(report["checks"]["runtime_errors_absent"])
        self.assertEqual(report["runtime_errors"]["fatal_exception"], 1)
        self.assertEqual(report["runtime_errors"]["invalid_display"], 1)
        self.assertEqual(report["runtime_errors"]["unable_to_add_window"], 1)

    def test_unscoped_saved_input_cannot_complete_diagnostics(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display launch stream id=0
            Nova: Android display role companion id=1 stream_id=0
            Nova: Android display audio context stream_id=0 display_id=0
            Nova: Android display audio route display_id=0 device_id=7 type=2
            Nova: Android display focus role=game display_id=0 window=true game_top_resumed=true
            Nova: Android display focus role=companion display_id=1 window=true game_top_resumed=true
            """
        )

        self.assertFalse(report["source_process_scoped"])
        self.assertFalse(report["checks"]["source_process_scoped"])
        self.assertFalse(report["checks"]["diagnostic_evidence_complete"])

    def test_report_never_copies_unrecognized_or_sensitive_log_text(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display launch stream id=0
            Connecting to host=192.0.2.44 token=do-not-copy pc_name=BedroomRig
            Nova: Android display audio route display_id=0 device_id=none type=none
            """
        )
        encoded = json.dumps(report, sort_keys=True)

        self.assertNotIn("192.0.2.44", encoded)
        self.assertNotIn("do-not-copy", encoded)
        self.assertNotIn("BedroomRig", encoded)
        self.assertEqual(
            report["audio_routes"],
            [{"device_id": "none", "display_id": 0, "type": "none"}],
        )
        self.assertIn("human", report["claim_status"])

    def test_read_adb_logcat_is_serial_and_nova_pid_scoped_and_read_only(self):
        module = load_module(self)
        pid = type("Completed", (), {"stdout": "4242\n", "returncode": 0})()
        completed = type("Completed", (), {"stdout": "safe raw log"})()
        with patch.object(module.shutil, "which", return_value="/opt/adb"), patch.object(
            module.subprocess,
            "run",
            side_effect=[pid, completed],
        ) as run:
            output = module.read_adb_logcat("THOR123", "com.papi.nova.debug")

        self.assertEqual(output, "safe raw log")
        self.assertEqual(
            run.call_args_list,
            [
                unittest.mock.call(
                    ["/opt/adb", "-s", "THOR123", "shell", "pidof", "-s", "com.papi.nova.debug"],
                    check=False,
                    text=True,
                    stdout=module.subprocess.PIPE,
                    stderr=module.subprocess.PIPE,
                ),
                unittest.mock.call(
                    ["/opt/adb", "-s", "THOR123", "logcat", "-d", "-v", "raw", "--pid", "4242"],
                    check=True,
                    text=True,
                    stdout=module.subprocess.PIPE,
                ),
            ],
        )

    def test_read_adb_logcat_fails_closed_when_nova_is_not_running(self):
        module = load_module(self)
        missing = type("Completed", (), {"stdout": "", "returncode": 1})()
        with patch.object(module.shutil, "which", return_value="/opt/adb"), patch.object(
            module.subprocess,
            "run",
            return_value=missing,
        ) as run, self.assertRaisesRegex(SystemExit, "Nova package is not running"):
            module.read_adb_logcat("THOR123", "com.papi.nova.debug")

        self.assertEqual(run.call_count, 1)

    def test_read_adb_logcat_rejects_invalid_package_before_subprocess(self):
        module = load_module(self)
        with patch.object(module.shutil, "which", return_value="/opt/adb"), patch.object(
            module.subprocess,
            "run",
        ) as run, self.assertRaises(SystemExit):
            module.read_adb_logcat("THOR123", "com.papi.nova.debug;rm")

        run.assert_not_called()

    def test_latest_run_correlation_is_not_taken_from_deduplicated_history(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display role stream id=0 target=primary
            Nova: Android display audio context stream_id=0 display_id=0
            Nova: Android display role stream id=2 target=external
            Nova: Android display audio context stream_id=2 display_id=2
            Nova: Android display role stream id=0 target=primary
            Nova: Android display audio context stream_id=0 display_id=2
            """
        )

        self.assertEqual(report["stream_display_ids"], [0])
        self.assertEqual(report.get("latest_stream_display_id"), 0)
        self.assertEqual(report.get("latest_audio_context_display_id"), 2)
        self.assertFalse(report["checks"]["audio_context_matches_stream"])

    def test_checks_ignore_stale_focus_success_before_latest_stream_launch(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display role stream id=0 target=primary
            Nova: Android display audio context stream_id=0 display_id=0
            Nova: Android display focus role=game display_id=0 window=true game_top_resumed=true
            Nova: Android display focus role=companion display_id=2 window=true game_top_resumed=true
            Nova: Android display role stream id=2 target=external
            Nova: Android display audio context stream_id=2 display_id=2
            Nova: Android display focus role=game display_id=2 window=true game_top_resumed=false
            Nova: Android display focus role=companion display_id=0 window=false game_top_resumed=false
            """
        )

        self.assertTrue(report.get("latest_run_marker_found"))
        self.assertEqual(report["stream_display_ids"], [2])
        self.assertFalse(report["checks"]["game_top_resumed_observed"])
        self.assertFalse(report["checks"]["companion_window_observed"])



    def test_newer_launch_marker_wins_over_an_older_role_marker(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display role stream id=0 target=primary
            Nova: Android display focus role=game display_id=0 window=true game_top_resumed=true
            Nova: Android display focus role=companion display_id=2 window=true game_top_resumed=true
            Nova: Android display launch stream id=2
            Nova: Android display audio context stream_id=2 display_id=2
            Nova: Android display focus role=game display_id=2 window=true game_top_resumed=false
            Nova: Android display focus role=companion display_id=0 window=false game_top_resumed=false
            """
        )

        self.assertEqual(report["stream_display_ids"], [2])
        self.assertFalse(report["checks"]["game_top_resumed_observed"])
        self.assertFalse(report["checks"]["companion_window_observed"])


    def test_audio_claim_cannot_overwrite_authoritative_stream_selection(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display role stream id=0 target=primary
            Nova: Android display launch stream id=0
            Nova: Android display audio context stream_id=1 display_id=1
            """
        )

        self.assertEqual(report["latest_stream_display_id"], 0)
        self.assertEqual(report.get("latest_audio_stream_display_id"), 1)
        self.assertEqual(report["latest_audio_context_display_id"], 1)
        self.assertFalse(report["checks"]["audio_context_matches_stream"])

    def test_negative_display_ids_cannot_create_positive_evidence(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display launch stream id=-1
            Nova: Android display audio context stream_id=-1 display_id=-1
            Nova: Android display focus role=game display_id=-1 window=true game_top_resumed=true
            """
        )

        self.assertFalse(report["latest_run_marker_found"])
        self.assertIsNone(report["latest_stream_display_id"])
        self.assertIsNone(report["checks"]["audio_context_matches_stream"])
        self.assertFalse(report["checks"]["game_top_resumed_observed"])

    def test_quoted_or_prefixed_marker_text_is_not_accepted(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            I/OtherApp: diagnostic quote: Nova: Android display launch stream id=2
            I/OtherApp: diagnostic quote: Nova: Android display audio context stream_id=2 display_id=2
            I/OtherApp: diagnostic quote: Nova: Android display focus role=game display_id=2 window=true game_top_resumed=true
            """
        )

        self.assertFalse(report["latest_run_marker_found"])
        self.assertEqual(report["stream_display_ids"], [])
        self.assertFalse(report["checks"]["game_top_resumed_observed"])

    def test_latest_route_and_focus_state_cannot_inherit_earlier_success(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display launch stream id=0
            Nova: Android display role companion id=1 stream_id=0
            Nova: Android display audio context stream_id=0 display_id=0
            Nova: Android display audio route display_id=0 device_id=7 type=2
            Nova: Android display focus role=game display_id=0 window=true game_top_resumed=true
            Nova: Android display focus role=companion display_id=1 window=true game_top_resumed=true
            Nova: Android display audio route display_id=1 device_id=8 type=2
            Nova: Android display focus role=game display_id=0 window=false game_top_resumed=false
            Nova: Android display focus role=companion display_id=1 window=false game_top_resumed=false
            """,
            source_process_scoped=True,
        )

        self.assertTrue(report["checks"]["audio_route_observed"])
        self.assertFalse(report["checks"]["audio_route_matches_stream"])
        self.assertFalse(report["checks"]["game_top_resumed_observed"])
        self.assertFalse(report["checks"]["companion_window_observed"])
        self.assertFalse(report["checks"]["diagnostic_evidence_complete"])

    def test_markerless_audio_and_focus_lines_cannot_create_positive_evidence(self):
        module = load_module(self)
        report = module.analyze_logcat(
            """
            Nova: Android display audio context stream_id=2 display_id=2
            Nova: Android display audio route display_id=2 device_id=7 type=2
            Nova: Android display focus role=game display_id=2 window=true game_top_resumed=true
            Nova: Android display focus role=companion display_id=0 window=true game_top_resumed=true
            """
        )

        self.assertFalse(report["latest_run_marker_found"])
        self.assertEqual(report["audio_context_display_ids"], [])
        self.assertEqual(report["audio_routes"], [])
        self.assertEqual(report["focus_events"], [])
        self.assertIsNone(report["checks"]["audio_context_matches_stream"])
        self.assertFalse(report["checks"]["audio_route_observed"])
        self.assertFalse(report["checks"]["game_top_resumed_observed"])
        self.assertFalse(report["checks"]["companion_window_observed"])


if __name__ == "__main__":
    unittest.main()
