import importlib.util
import io
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("nova_retroid_smoke.py")
spec = importlib.util.spec_from_file_location("nova_retroid_smoke", MODULE_PATH)
nova_retroid_smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nova_retroid_smoke)


class NovaRetroidSmokeHelpersTest(unittest.TestCase):
    def test_parse_bounds_returns_tuple(self):
        self.assertEqual(nova_retroid_smoke.parse_bounds("[48,689][293,779]"), (48, 689, 293, 779))

    def test_find_nodes_does_not_match_short_labels_inside_long_words(self):
        xml = """
        <hierarchy>
          <node text="Host render limited; next launch can target 60 FPS" bounds="[69,144][639,174]" />
          <node text="End" bounds="[825,220][934,276]" />
        </hierarchy>
        """

        nodes = nova_retroid_smoke.find_nodes(xml, "End")

        self.assertEqual(len(nodes), 1)
        self.assertEqual(nodes[0].bounds, (825, 220, 934, 276))

    def test_find_nodes_matches_text_and_content_description(self):
        xml = """
        <hierarchy>
          <node text="Quick Keys" content-desc="" bounds="[62,400][168,448]" />
          <node text="" content-desc="Touch Controls" bounds="[567,786][999,919]" />
        </hierarchy>
        """

        quick = nova_retroid_smoke.find_nodes(xml, "Quick Keys")
        touch = nova_retroid_smoke.find_nodes(xml, "Touch Controls")

        self.assertEqual(quick[0].bounds, (62, 400, 168, 448))
        self.assertEqual(touch[0].bounds, (567, 786, 999, 919))

    def test_parse_ui_nodes_preserves_focused_attribute(self):
        xml = """
        <hierarchy>
          <node text="Options" focused="true" bounds="[219,496][370,584]" />
          <node text="System" focused="false" bounds="[388,496][539,584]" />
        </hierarchy>
        """

        nodes = nova_retroid_smoke.parse_ui_nodes(xml)

        self.assertTrue(nodes[0].focused)
        self.assertFalse(nodes[1].focused)

    def test_hud_drag_points_use_visible_hud_label_bounds(self):
        xml = """
        <hierarchy>
          <node text="FPS" bounds="[68,50][95,66]" />
          <node text="23" bounds="[68,72][108,108]" />
          <node text="HOST" bounds="[309,88][351,105]" />
        </hierarchy>
        """

        points = nova_retroid_smoke.hud_drag_points(xml, screen_width=1920, screen_height=1080)

        self.assertIsNotNone(points)
        self.assertLess(points["from"][1], 132)
        self.assertGreater(points["to"][0], points["from"][0] + 600)
        self.assertGreater(points["to"][1], points["from"][1] + 80)

    def test_library_rail_analysis_accepts_populated_grid_metadata_chips(self):
        xml = """
        <hierarchy>
          <node text="Library" bounds="[252,93][353,128]" />
          <node text="19 shown" bounds="[252,130][337,153]" />
          <node text="Library Options" bounds="[40,84][234,162]" />
          <node text="System" bounds="[2081,84][2198,162]" />
          <node text="Steam Big Picture" bounds="[40,365][563,712]" />
          <node text="HDR" bounds="[71,387][108,407]" />
          <node text="Recent" bounds="[147,387][207,407]" />
          <node text="A Select · B Back · X Library · Y Layout · Menu System" bounds="[18,931][2220,1009]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_library_rail(xml)

        self.assertTrue(result.ok, result)
        self.assertEqual(result.missing, [])
        self.assertEqual(result.values["obsolete_rail_labels"], [])

    def test_library_rail_analysis_requires_controller_hint_bar(self):
        xml = """
        <hierarchy>
          <node text="Library Options" bounds="[44,44][220,100]" />
          <node text="Refresh" bounds="[1450,48][1560,112]" />
          <node text="System" bounds="[1575,48][1680,112]" />
          <node text="Build your library" bounds="[44,160][380,220]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_library_rail(xml)

        self.assertFalse(result.ok)
        self.assertIn("controller hint bar", result.missing)

    def test_library_rail_analysis_reports_drawer_first_controls_and_full_width_hint(self):
        xml = """
        <hierarchy>
          <node text="Library Options" bounds="[44,44][220,100]" />
          <node text="Refresh" bounds="[1450,48][1560,112]" />
          <node text="System" bounds="[1575,48][1680,112]" />
          <node text="Switch" bounds="[1695,48][1810,112]" />
          <node text="Build your library" bounds="[44,160][380,220]" />
          <node text="A Select" bounds="[44,1016][164,1062]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_library_rail(xml)

        self.assertTrue(result.ok)
        self.assertEqual(result.values["options_left"], 44)
        self.assertEqual(result.values["options_top"], 44)
        self.assertEqual(result.values["hint_left"], 44)
        self.assertEqual(result.values["obsolete_rail_labels"], [])
        self.assertEqual(result.missing, [])

    def test_library_rail_analysis_rejects_obsolete_permanent_left_rail(self):
        xml = """
        <hierarchy>
          <node text="Library" bounds="[44,44][169,89]" />
          <node text="Search this library" bounds="[68,308][273,349]" />
          <node text="Refresh" bounds="[77,401][141,435]" />
          <node text="Library Options" bounds="[214,401][297,435]" />
          <node text="System" bounds="[371,401][434,435]" />
          <node text="Switch" bounds="[81,485][137,519]" />
          <node text="All" bounds="[66,566][202,607]" />
          <node text="Recent" bounds="[282,566][418,607]" />
          <node text="Sources" bounds="[66,650][202,691]" />
          <node text="HDR" bounds="[282,650][418,691]" />
          <node text="More" bounds="[64,734][203,776]" />
          <node text="Build your library" bounds="[528,87][801,127]" />
          <node text="A Select" bounds="[542,1005][637,1046]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_library_rail(xml)

        self.assertFalse(result.ok)
        self.assertIn("permanent landscape Library rail is still visible", result.failures)
        self.assertIn("controller hint bar still reserves old rail width", result.failures)

    def test_wait_for_library_rail_retries_until_required_labels_are_visible(self):
        stale_launcher_xml = """
        <hierarchy>
          <node text="Watch Next" bounds="[176,80][323,128]" />
          <node text="Frozen" bounds="[288,438][388,479]" />
        </hierarchy>
        """
        ready_library_xml = """
        <hierarchy>
          <node text="Library Options" bounds="[44,44][220,100]" />
          <node text="Refresh" bounds="[1450,48][1560,112]" />
          <node text="System" bounds="[1575,48][1680,112]" />
          <node text="Build your library" bounds="[44,160][380,220]" />
          <node text="A Select" bounds="[44,1016][164,1062]" />
        </hierarchy>
        """

        with patch.object(nova_retroid_smoke, "dump_xml", side_effect=[stale_launcher_xml, ready_library_xml]) as dump, \
            patch.object(nova_retroid_smoke.time, "sleep") as sleep:
            xml, result = nova_retroid_smoke.wait_for_library_rail(
                object(),
                Path("/tmp/library.xml"),
                timeout_s=5,
                interval_s=0.01,
            )

        self.assertEqual(xml, ready_library_xml)
        self.assertTrue(result.ok)
        self.assertEqual(dump.call_count, 2)
        sleep.assert_called_once_with(0.01)

    def test_command_center_analysis_prioritizes_quick_keys_and_touch_controls_copy(self):
        xml = """
        <hierarchy>
          <node text="Quick Keys" bounds="[62,400][168,448]" />
          <node text="ESC" bounds="[62,462][363,545]" />
          <node text="Alt + Enter" bounds="[379,462][681,545]" />
          <node text="Alt + F4" bounds="[697,462][999,545]" />
          <node text="Stats Overlay" bounds="[62,848][494,946]" />
          <node text="Touch Controls" bounds="[567,786][999,919]" />
          <node text="On-screen overlay; physical gamepad stays active." bounds="[576,855][899,903]" />
          <node text="End" bounds="[825,220][934,276]" />
          <node text="Disconnect" bounds="[680,220][815,276]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_command_center(xml)

        self.assertTrue(result.ok)
        self.assertEqual(result.values["quick_keys_top"], 400)
        self.assertIs(result.values["touch_controls_visible"], True)
        self.assertEqual(result.missing, [])

    def test_command_center_accepts_disconnect_without_end(self):
        """Command Center may show a safe 'Disconnect' affordance without an 'End' destructive action; treat as OK.
        """
        xml = """
        <hierarchy>
          <node text="Quick Keys" bounds="[62,400][168,448]" />
          <node text="Disconnect" bounds="[680,220][815,276]" />
          <node text="Stats Overlay" bounds="[62,848][494,946]" />
          <node text="Touch Controls" bounds="[567,786][999,919]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_command_center(xml)

        # previously this would fail because 'End' was missing; the hardened oracle accepts Disconnect-only flows
        self.assertTrue(result.ok, result)
        self.assertNotIn("End", result.missing)
        self.assertIn("Quick Keys", [n.label for n in nova_retroid_smoke.parse_ui_nodes(xml)])

    def test_command_center_accepts_touch_controls_caption_without_quick_keys(self):
        """Touch Controls caption alone can satisfy the Command Center surface when Quick Keys are absent."""
        xml = """
        <hierarchy>
          <node text="Disconnect" bounds="[680,220][815,276]" />
          <node text="Stats Overlay" bounds="[62,848][494,946]" />
          <node text="On-screen overlay; physical gamepad stays active." bounds="[576,855][899,903]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_command_center(xml)

        self.assertTrue(result.ok, result)
        self.assertIs(result.values["touch_controls_visible"], True)
        self.assertEqual(result.failures, [])

    def test_command_center_accepts_touch_controls_when_quick_keys_are_below_first_paint(self):
        """A stale Quick Keys viewport cutoff should not fail when Touch Controls are visible."""
        xml = """
        <hierarchy>
          <node text="Quick Keys" bounds="[62,700][168,748]" />
          <node text="Disconnect" bounds="[680,220][815,276]" />
          <node text="Stats Overlay" bounds="[62,848][494,946]" />
          <node text="Touch Controls" bounds="[567,786][999,919]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_command_center(xml)

        self.assertTrue(result.ok, result)
        self.assertEqual(result.values["quick_keys_top"], 700)
        self.assertEqual(result.failures, [])

    def test_command_center_analysis_ignores_header_subtitle_ai_copy_when_checking_first_paint(self):
        xml = """
        <hierarchy>
          <node text="AI Auto Quality, stream controls, and session tools" bounds="[62,148][390,190]" />
          <node text="Quick Keys" bounds="[62,400][168,448]" />
          <node text="ESC" bounds="[62,462][363,545]" />
          <node text="Alt + Enter" bounds="[379,462][681,545]" />
          <node text="Alt + F4" bounds="[697,462][999,545]" />
          <node text="Stats Overlay" bounds="[62,848][494,946]" />
          <node text="Touch Controls" bounds="[567,786][999,919]" />
          <node text="On-screen overlay; physical gamepad stays active." bounds="[576,855][899,903]" />
          <node text="End" bounds="[825,220][934,276]" />
          <node text="Disconnect" bounds="[680,220][815,276]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_command_center(xml)

        self.assertTrue(result.ok)
        self.assertEqual(result.failures, [])

    def test_log_scan_flags_crashes_and_accepts_stream_evidence(self):
        clean = """
        Nova SSE: stream_active [streaming] Streaming to RetroidPocket6
        Starting video stream
        ENet peer acknowledged disconnection
        Nova SSE: Stopped
        """
        dirty = """
        FATAL EXCEPTION: main
        Process: com.papi.nova.debug
        """

        clean_result = nova_retroid_smoke.scan_logcat(clean)
        dirty_result = nova_retroid_smoke.scan_logcat(dirty)

        self.assertTrue(clean_result.ok)
        self.assertIs(clean_result.values["stream_active"], True)
        self.assertIs(clean_result.values["clean_disconnect"], True)
        self.assertFalse(dirty_result.ok)
        self.assertTrue(dirty_result.failures)

    def test_log_scan_ignores_unrelated_app_crashes(self):
        unrelated = """
        FATAL EXCEPTION: main
        Process: com.example.otherapp
        RuntimeException: other app had a bad day
        """

        result = nova_retroid_smoke.scan_logcat(unrelated, "com.papi.nova.debug")

        self.assertTrue(result.ok)
        self.assertEqual(result.failures, [])

    def test_live_stream_log_result_requires_stream_and_disconnect_evidence(self):
        missing = nova_retroid_smoke.live_stream_log_result(
            nova_retroid_smoke.scan_logcat("Starting video stream"),
            require_clean_disconnect=True,
        )
        no_disconnect = nova_retroid_smoke.live_stream_log_result(
            nova_retroid_smoke.scan_logcat(
                "Nova SSE: stream_active [streaming]\nStarting video stream\nStarting audio stream"
            ),
            require_clean_disconnect=True,
        )
        clean = nova_retroid_smoke.live_stream_log_result(
            nova_retroid_smoke.scan_logcat(
                "Nova SSE: stream_active [streaming]\n"
                "Starting video stream\nStarting audio stream\nNova SSE: Stopped"
            ),
            require_clean_disconnect=True,
        )

        self.assertFalse(missing.ok)
        self.assertIn("stream_active", missing.missing)
        self.assertFalse(no_disconnect.ok)
        self.assertIn("clean_disconnect", no_disconnect.missing)
        self.assertTrue(clean.ok)

    def test_find_launch_button_ignores_launch_mode_headers(self):
        xml = """
        <hierarchy>
          <node text="Launch controls" bounds="[254,623][1666,1080]" />
          <node text="Launch Mode" bounds="[284,665][1425,713]" />
          <node text="Launch 120 FPS" bounds="[264,795][1656,924]" />
        </hierarchy>
        """

        node = nova_retroid_smoke.find_launch_button_node(xml)

        self.assertIsNotNone(node)
        self.assertEqual(node.bounds, (264, 795, 1656, 924))

    def test_game_detail_first_paint_accepts_current_launch_controls_copy(self):
        xml = """
        <hierarchy>
          <node text="Steam Big Picture" bounds="[62,96][820,164]" />
          <node text="Launch controls" bounds="[254,623][1666,1080]" />
          <node text="Launch Mode" bounds="[284,665][1425,713]" />
          <node text="Headless" bounds="[303,728][712,781]" />
          <node text="Virtual" bounds="[742,728][1152,781]" />
          <node text="Launch 120 FPS" bounds="[264,795][1656,924]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_game_detail_first_paint(xml, "Steam Big Picture")

        self.assertTrue(result.ok, result.missing)
        self.assertEqual(result.missing, [])
        self.assertTrue(result.values["detail_launch_button_bounds"])
        self.assertNotIn("Launch options", result.missing)
        self.assertNotIn("NOVA", result.missing)

    def test_end_stream_from_command_center_reports_confirm_and_return(self):
        class FakeAdb:
            def __init__(self):
                self.taps = []
                self.swipes = []

            def tap(self, x, y):
                self.taps.append((x, y))

            def swipe(self, *args):
                self.swipes.append(args)

        args = type("Args", (), {"activity": "com.papi.nova.ui.NovaLibraryActivity", "timeout": 45})()
        xmls = [
            '<hierarchy><node text="End session" bounds="[825,220][934,276]" /></hierarchy>',
            '<hierarchy><node text="Yes" bounds="[700,600][900,700]" /></hierarchy>',
        ]

        with patch.object(nova_retroid_smoke, "dump_xml", side_effect=xmls), \
            patch.object(nova_retroid_smoke, "wait_for_focus", return_value=True), \
            patch.object(nova_retroid_smoke.time, "sleep"):
            result = nova_retroid_smoke.end_stream_from_command_center(FakeAdb(), args, Path("/tmp/live"))

        self.assertTrue(result.ok)
        self.assertIs(result.values["end_stream_confirmed"], True)
        self.assertIs(result.values["returned_to_library"], True)

    def test_end_stream_from_command_center_fails_without_confirmation(self):
        class FakeAdb:
            def __init__(self):
                self.swipes = []

            def tap(self, x, y):
                pass

            def swipe(self, *args):
                self.swipes.append(args)

        args = type("Args", (), {"activity": "com.papi.nova.ui.NovaLibraryActivity", "timeout": 45})()
        xmls = [
            '<hierarchy><node text="End session" bounds="[825,220][934,276]" /></hierarchy>',
            '<hierarchy><node text="Cancel" bounds="[700,600][900,700]" /></hierarchy>',
        ]

        with patch.object(nova_retroid_smoke, "dump_xml", side_effect=xmls), \
            patch.object(nova_retroid_smoke.time, "sleep"):
            result = nova_retroid_smoke.end_stream_from_command_center(FakeAdb(), args, Path("/tmp/live"))

        self.assertFalse(result.ok)
        self.assertIn("End confirmation button not found", result.failures)

    def test_display_rect_parser_reads_landscape_bounds(self):
        class FakeAdb:
            def shell(self, command, **kwargs):
                self.command = command
                return "    mCurrentDisplayRect=Rect(0, 0 - 1920, 1080)\n"

        rect = nova_retroid_smoke._display_rect(FakeAdb())

        self.assertEqual(rect, (1920, 1080))

    def test_force_landscape_writes_rotation_settings_until_landscape(self):
        class FakeAdb:
            def __init__(self):
                self.commands = []

            def shell(self, command, **kwargs):
                self.commands.append(command)
                if command.startswith("dumpsys display"):
                    return "    mCurrentDisplayRect=Rect(0, 0 - 1920, 1080)\n"
                return ""

        adb = FakeAdb()

        self.assertTrue(nova_retroid_smoke.force_landscape(adb, settle_s=0))
        self.assertIn("settings put system accelerometer_rotation 0", adb.commands)
        self.assertIn("settings put system user_rotation 1", adb.commands)

    def test_rotation_settings_restore_previous_values(self):
        class FakeAdb:
            def __init__(self):
                self.commands = []

            def shell(self, command, **kwargs):
                self.commands.append(command)
                return ""

        adb = FakeAdb()
        nova_retroid_smoke.restore_rotation_settings(
            adb,
            {"accelerometer_rotation": "1", "user_rotation": "0"},
        )

        self.assertEqual(
            adb.commands,
            [
                "settings put system accelerometer_rotation 1",
                "settings put system user_rotation 0",
            ],
        )

    def test_rotation_settings_restore_deletes_null_previous_values(self):
        class FakeAdb:
            def __init__(self):
                self.commands = []

            def shell(self, command, **kwargs):
                self.commands.append(command)
                return ""

        adb = FakeAdb()
        nova_retroid_smoke.restore_rotation_settings(
            adb,
            {"accelerometer_rotation": "null", "user_rotation": ""},
        )

        self.assertEqual(
            adb.commands,
            [
                "settings delete system accelerometer_rotation",
                "settings delete system user_rotation",
            ],
        )

    def test_start_library_reports_launcher_fallback_as_helper_signal_not_product_failure(self):
        class FakeAdb:
            def __init__(self):
                self.commands = []

            def shell(self, command, **kwargs):
                self.commands.append(["shell", command])
                return ""

            def run(self, command, **kwargs):
                self.commands.append(command)
                return type("Completed", (), {"returncode": 0, "stdout": "", "stderr": ""})()

        args = type(
            "Args",
            (),
            {
                "package": "com.papi.nova.debug",
                "activity": "com.papi.nova.ui.NovaLibraryActivity",
            },
        )()
        adb = FakeAdb()

        with patch.object(nova_retroid_smoke, "wait_for_focus", side_effect=[False, True]), \
            patch.object(nova_retroid_smoke.time, "sleep"):
            result = nova_retroid_smoke.start_library(adb, args)

        self.assertTrue(result.ok)
        self.assertTrue(result.values["library_start_fallback_used"])
        self.assertFalse(result.values["library_start_direct_focused"])
        self.assertTrue(result.values["library_start_fallback_focused"])
        self.assertTrue(any("monkey -p com.papi.nova.debug" in " ".join(command) for command in adb.commands))

    def test_ensure_library_focused_restarts_after_rotation_focus_loss(self):
        args = type(
            "Args",
            (),
            {
                "package": "com.papi.nova.debug",
                "activity": "com.papi.nova.ui.NovaLibraryActivity",
            },
        )()

        with patch.object(nova_retroid_smoke, "wait_for_focus", side_effect=[False, True]) as wait, \
            patch.object(nova_retroid_smoke, "start_library") as start:
            result = nova_retroid_smoke.ensure_library_focused(object(), args, "rotation")

        self.assertTrue(result.ok)
        self.assertTrue(result.values["rotation_refocused"])
        self.assertTrue(result.values["library_focus_restored"])
        start.assert_called_once()
        self.assertEqual(wait.call_count, 2)

    def test_ensure_library_focused_reports_when_restart_does_not_focus(self):
        args = type(
            "Args",
            (),
            {
                "package": "com.papi.nova.debug",
                "activity": "com.papi.nova.ui.NovaLibraryActivity",
            },
        )()

        with patch.object(nova_retroid_smoke, "wait_for_focus", side_effect=[False, False]), \
            patch.object(nova_retroid_smoke, "start_library"):
            result = nova_retroid_smoke.ensure_library_focused(object(), args, "rotation")

        self.assertFalse(result.ok)
        self.assertIn("Library activity is not focused after rotation", result.failures)
        self.assertTrue(result.values["rotation_refocused"])
        self.assertFalse(result.values["library_focus_restored"])

    def test_ensure_adb_device_skips_lookup_for_dry_run(self):
        with patch.object(nova_retroid_smoke.shutil, "which", side_effect=AssertionError("adb lookup should be skipped")), \
            patch.object(nova_retroid_smoke.subprocess, "run", side_effect=AssertionError("adb devices should be skipped")):
            serial = nova_retroid_smoke.ensure_adb_device(None, dry_run=True)

        self.assertEqual(serial, "dry-run-device")

    def test_ensure_adb_device_uses_only_connected_device_when_serial_omitted(self):
        completed = type("Completed", (), {"stdout": "List of devices attached\nsolo\tdevice\n"})()

        with patch.object(nova_retroid_smoke.shutil, "which", return_value="/usr/bin/adb"), \
            patch.object(nova_retroid_smoke.subprocess, "run", return_value=completed):
            serial = nova_retroid_smoke.ensure_adb_device(None)

        self.assertEqual(serial, "solo")

    def test_ensure_adb_device_requires_serial_for_multiple_devices(self):
        completed = type("Completed", (), {"stdout": "List of devices attached\none\tdevice\ntwo\tdevice\n"})()

        with patch.object(nova_retroid_smoke.shutil, "which", return_value="/usr/bin/adb"), \
            patch.object(nova_retroid_smoke.subprocess, "run", return_value=completed), \
            self.assertRaises(SystemExit) as raised:
            nova_retroid_smoke.ensure_adb_device(None)

        self.assertIn("Multiple ADB devices", str(raised.exception))

    def test_read_logcat_can_read_from_start_marker_without_clearing(self):
        class FakeAdb:
            def __init__(self):
                self.commands = []

            def run(self, command, **kwargs):
                self.commands.append(command)
                return type("Completed", (), {"stdout": "log text"})()

        adb = FakeAdb()

        self.assertEqual(nova_retroid_smoke.read_logcat(adb, since="05-22 20:19:00.000"), "log text")
        self.assertEqual(adb.commands, [["logcat", "-d", "-T", "05-22 20:19:00.000"]])

    def test_dump_xml_retries_transient_null_root_and_returns_hierarchy(self):
        class FakeAdb:
            serial = "retroid"
            dry_run = False

            def __init__(self):
                self.shell_calls = 0
                self.run_calls = []

            def shell(self, command, **kwargs):
                self.shell_calls += 1
                if self.shell_calls == 1:
                    raise RuntimeError("ERROR: null root node returned by UiTestAutomationBridge")
                return "UI hierchary dumped to: /sdcard/window_dump.xml"

            def run(self, command, **kwargs):
                self.run_calls.append((command, kwargs))
                return type("Completed", (), {"stdout": "<hierarchy><node text=\"Library\" bounds=\"[0,0][1,1]\" /></hierarchy>", "stderr": ""})()

        with tempfile.TemporaryDirectory() as tmp, patch.object(nova_retroid_smoke.time, "sleep") as sleep:
            output = Path(tmp) / "window.xml"
            xml = nova_retroid_smoke.dump_xml(FakeAdb(), output, attempts=2, interval_s=0.01)

            self.assertIn("<hierarchy", xml)
            self.assertIn("Library", output.read_text(encoding="utf-8"))
        sleep.assert_called_once_with(0.01)

    def test_dump_xml_fails_with_diagnostics_after_persistent_null_root(self):
        class FakeAdb:
            serial = "retroid"
            dry_run = False

            def shell(self, command, **kwargs):
                if command.startswith("uiautomator dump"):
                    raise RuntimeError("ERROR: null root node returned by UiTestAutomationBridge")
                if "mCurrentFocus" in command:
                    return "mCurrentFocus=Window{u0 com.papi.nova.debug/com.papi.nova.ui.NovaLibraryActivity}"
                if "activity top" in command:
                    return "ACTIVITY com.papi.nova.debug/com.papi.nova.ui.NovaLibraryActivity"
                return "Physical size: 1920x1080\nPhysical density: 320\n1\n0"

            def run(self, command, **kwargs):
                raise AssertionError("persistent dump failures should not cat stale XML")

        with tempfile.TemporaryDirectory() as tmp, patch.object(nova_retroid_smoke.time, "sleep"):
            output = Path(tmp) / "window.xml"
            with self.assertRaises(nova_retroid_smoke.UiDumpError) as raised:
                nova_retroid_smoke.dump_xml(FakeAdb(), output, attempts=2, interval_s=0.01)
            diagnostics = output.with_suffix(".xml.diagnostics.txt")

            self.assertTrue(diagnostics.exists())
            diagnostic_text = diagnostics.read_text(encoding="utf-8")

        self.assertIn("inspectable UI hierarchy", str(raised.exception))
        self.assertIn("diagnostics=", str(raised.exception))
        self.assertIn("mCurrentFocus", diagnostic_text)
        self.assertIn("Physical size", diagnostic_text)

    def test_main_reports_ui_dump_error_without_traceback(self):
        args = SimpleNamespace(
            func=lambda _args: (_ for _ in ()).throw(nova_retroid_smoke.UiDumpError("null root diagnostics"))
        )
        parser = type("Parser", (), {"parse_args": lambda self, argv: args})()
        stderr = io.StringIO()

        with patch.object(nova_retroid_smoke, "build_parser", return_value=parser), \
            patch.object(nova_retroid_smoke.sys, "stderr", stderr):
            exit_code = nova_retroid_smoke.main(["library"])

        self.assertEqual(exit_code, 1)
        self.assertIn("FAIL: null root diagnostics", stderr.getvalue())
        self.assertNotIn("Traceback", stderr.getvalue())

    def test_phone_surface_analysis_reports_missing_labels_by_surface(self):
        xml = """
        <hierarchy>
          <node text="Nova" bounds="[72,360][287,481]" />
          <node text="Settings" bounds="[1088,388][1208,508]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_required_labels(xml, ["Nova", "Library"], "dashboard")

        self.assertFalse(result.ok)
        self.assertIn("dashboard: Library", result.missing)
        self.assertEqual(result.values["dashboard_label_count"], 2)

    def test_phone_library_base_accepts_two_zone_shell_without_exposed_filter_row(self):
        xml = """
        <hierarchy>
          <node text="Library" bounds="[40,208][176,258]" />
          <node text="pc-papi.lan" bounds="[40,258][157,304]" />
          <node text="19 shown" bounds="[40,322][336,347]" />
          <node text="Library Options" bounds="[705,216][899,296]" />
          <node text="System" bounds="[917,216][1040,296]" />
          <node text="Continue" bounds="[54,453][273,480]" />
          <node text="A Select" bounds="[18,2261][1062,2339]" />
          <node text="B Back" bounds="[18,2261][1062,2339]" />
          <node text="X Library" bounds="[18,2261][1062,2339]" />
          <node text="Menu System" bounds="[18,2261][1062,2339]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_phone_library_base(xml)

        self.assertTrue(result.ok)
        self.assertEqual(result.missing, [])
        self.assertEqual(result.failures, [])
        self.assertEqual(result.values["phone_library_base_shown_count_label"], "19 shown")

    def test_phone_library_base_ignores_card_badges_and_controller_layout_hint(self):
        xml = """
        <hierarchy>
          <node text="Library" bounds="[252,93][353,128]" />
          <node text="19 shown" bounds="[252,130][337,153]" />
          <node text="Library Options" bounds="[40,84][234,162]" />
          <node text="System" bounds="[2081,84][2198,162]" />
          <node text="Steam Big Picture" bounds="[40,365][563,712]" />
          <node text="HDR" bounds="[71,387][108,407]" />
          <node text="Recent" bounds="[147,387][207,407]" />
          <node text="A Select · B Back · X Library · Y Layout · Menu System" bounds="[18,931][2220,1009]" />
          <node text="A Select" bounds="[52,947][154,993]" />
          <node text="B Back" bounds="[188,947][276,993]" />
          <node text="Y Layout" bounds="[453,947][559,993]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_phone_library_base(xml)

        self.assertTrue(result.ok)
        self.assertEqual(result.values["phone_library_base_exposed_filter_labels"], [])

    def test_phone_library_base_rejects_filter_controls_outside_left_drawer(self):
        xml = """
        <hierarchy>
          <node text="Library" bounds="[40,208][176,258]" />
          <node text="Library Options" bounds="[705,216][899,296]" />
          <node text="System" bounds="[917,216][1040,296]" />
          <node text="Search this library" bounds="[40,360][520,438]" />
          <node text="Sources" bounds="[48,448][240,510]" />
          <node text="A Select" bounds="[18,2261][1062,2339]" />
          <node text="B Back" bounds="[18,2261][1062,2339]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_phone_library_base(xml)

        self.assertFalse(result.ok)
        self.assertIn("phone library base exposes drawer-owned filters", result.failures)
        self.assertEqual(result.values["phone_library_base_exposed_filter_labels"], ["Search this library", "Sources"])

    def test_phone_drawer_analysis_enforces_library_and_system_ownership(self):
        left_xml = """
        <hierarchy>
          <node text="Library Options" bounds="[31,202][881,242]" />
          <node text="Search this library" bounds="[31,258][984,347]" />
          <node text="Refresh" bounds="[753,360][984,431]" />
          <node text="All" bounds="[52,463][215,511]" />
          <node text="Recent" bounds="[298,444][592,529]" />
          <node text="Sources" bounds="[610,444][930,529]" />
          <node text="Sort" bounds="[31,542][71,588]" />
          <node text="Layout" bounds="[31,1198][94,1244]" />
        </hierarchy>
        """
        right_xml = """
        <hierarchy>
          <node text="System" bounds="[96,199][216,239]" />
          <node text="pc-papi.lan" bounds="[96,252][356,277]" />
          <node text="Switch" bounds="[96,350][390,421]" />
          <node text="Settings" bounds="[96,441][390,512]" />
          <node text="Diagnostics" bounds="[96,532][390,603]" />
          <node text="About" bounds="[96,1700][390,1771]" />
        </hierarchy>
        """
        mixed_right_xml = right_xml.replace("</hierarchy>", "  <node text=\"Search this library\" bounds=\"[96,620][390,700]\" />\n</hierarchy>")

        left = nova_retroid_smoke.analyze_phone_library_options_drawer(left_xml)
        right = nova_retroid_smoke.analyze_phone_system_drawer(right_xml)
        mixed_right = nova_retroid_smoke.analyze_phone_system_drawer(mixed_right_xml)

        self.assertTrue(left.ok)
        self.assertTrue(right.ok)
        self.assertFalse(mixed_right.ok)
        self.assertIn("phone system drawer mixes library controls", mixed_right.failures)

    def test_run_phone_scrolls_library_options_drawer_when_layout_is_below_dump(self):
        base_xml = """
        <hierarchy>
          <node text="Library" bounds="[252,93][353,128]" />
          <node text="19 shown" bounds="[252,130][337,153]" />
          <node text="Library Options" bounds="[40,84][234,162]" />
          <node text="System" bounds="[2081,84][2198,162]" />
          <node text="A Select" bounds="[52,947][154,993]" />
          <node text="B Back" bounds="[188,947][276,993]" />
          <node text="Y Layout" bounds="[453,947][559,993]" />
        </hierarchy>
        """
        left_first_xml = """
        <hierarchy>
          <node text="Library Options" bounds="[31,83][985,123]" />
          <node text="Search this library" bounds="[31,139][1088,228]" />
          <node text="Refresh" bounds="[857,241][1088,312]" />
          <node text="All" bounds="[52,344][215,392]" />
          <node text="Recent" bounds="[322,345][526,391]" />
          <node text="Sources" bounds="[634,345][864,391]" />
          <node text="Sort" bounds="[31,423][71,469]" />
        </hierarchy>
        """
        left_scrolled_xml = """
        <hierarchy>
          <node text="Layout" bounds="[31,420][132,466]" />
          <node text="Density" bounds="[31,520][154,566]" />
        </hierarchy>
        """
        right_xml = """
        <hierarchy>
          <node text="System" bounds="[1329,80][1449,120]" />
          <node text="Switch" bounds="[1329,252][2207,359]" />
          <node text="Settings" bounds="[1329,372][2207,479]" />
          <node text="Diagnostics" bounds="[1329,732][1759,803]" />
          <node text="About" bounds="[1777,732][2207,803]" />
        </hierarchy>
        """

        class FakeAdb:
            def __init__(self):
                self.serial = "phone"
                self.dry_run = False
                self.swipes = []
                self.keys = []
                self.taps = []

            def shell(self, command, **kwargs):
                return ""

            def run(self, command, **kwargs):
                return type("Completed", (), {"returncode": 0, "stdout": "", "stderr": ""})()

            def tap(self, x, y):
                self.taps.append((x, y))

            def input_keyevent(self, key):
                self.keys.append(key)

            def swipe(self, x1, y1, x2, y2, duration_ms=350):
                self.swipes.append((x1, y1, x2, y2, duration_ms))

        fake_adb = FakeAdb()
        captured = {}
        args = type(
            "Args",
            (),
            {
                "serial": None,
                "dry_run": False,
                "package": "com.papi.nova.debug",
                "activity": "com.papi.nova.ui.NovaLibraryActivity",
                "artifacts_dir": "/tmp",
                "repo": str(MODULE_PATH.parents[1]),
                "apk": str(MODULE_PATH.parents[1] / "app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk"),
                "skip_install": True,
                "clear_logcat": False,
            },
        )()

        def capture_report(prefix, title, result, artifacts):
            captured["result"] = result
            captured["artifacts"] = [path.name for path in artifacts]

        with patch.object(nova_retroid_smoke, "ensure_adb_device", return_value="phone"), \
            patch.object(nova_retroid_smoke, "Adb", return_value=fake_adb), \
            patch.object(nova_retroid_smoke, "maybe_install"), \
            patch.object(nova_retroid_smoke, "begin_logcat_window", return_value="05-23 20:00:00.000"), \
            patch.object(nova_retroid_smoke, "capture_rotation_settings", return_value={}), \
            patch.object(nova_retroid_smoke, "write_system_setting"), \
            patch.object(nova_retroid_smoke, "restore_rotation_settings"), \
            patch.object(nova_retroid_smoke, "capture_png"), \
            patch.object(nova_retroid_smoke, "dump_xml", side_effect=[base_xml, left_first_xml, left_scrolled_xml, base_xml, right_xml, base_xml]), \
            patch.object(nova_retroid_smoke, "current_focus", return_value="com.papi.nova.debug/com.papi.nova.ui.NovaLibraryActivity"), \
            patch.object(nova_retroid_smoke, "read_logcat", return_value="com.papi.nova.debug clean"), \
            patch.object(nova_retroid_smoke, "write_report", side_effect=capture_report), \
            patch.object(nova_retroid_smoke.time, "sleep"):
            result = nova_retroid_smoke.run_phone(args)

        self.assertTrue(result.ok)
        self.assertTrue(fake_adb.swipes)
        self.assertTrue(result.values["phone_library_options_layout_scrolled"])
        self.assertIn("phone_system_drawer_settings_bounds", result.values)
        self.assertIn("phone", captured["artifacts"][0])

    def test_run_phone_accepts_launcher_library_and_captures_two_zone_drawers(self):
        base_xml = """
        <hierarchy>
          <node text="Library" bounds="[40,208][176,258]" />
          <node text="19 shown" bounds="[40,322][336,347]" />
          <node text="Library Options" bounds="[705,216][899,296]" />
          <node text="System" bounds="[917,216][1040,296]" />
          <node text="A Select" bounds="[18,2261][1062,2339]" />
          <node text="B Back" bounds="[18,2261][1062,2339]" />
        </hierarchy>
        """
        left_xml = """
        <hierarchy>
          <node text="Library Options" bounds="[31,202][881,242]" />
          <node text="Search this library" bounds="[31,258][984,347]" />
          <node text="Refresh" bounds="[753,360][984,431]" />
          <node text="All" bounds="[52,463][215,511]" />
          <node text="Recent" bounds="[298,444][592,529]" />
          <node text="Sources" bounds="[610,444][930,529]" />
          <node text="Sort" bounds="[31,542][71,588]" />
          <node text="Layout" bounds="[31,1198][94,1244]" />
        </hierarchy>
        """
        right_xml = """
        <hierarchy>
          <node text="System" bounds="[96,199][216,239]" />
          <node text="Switch" bounds="[96,350][390,421]" />
          <node text="Settings" bounds="[96,441][390,512]" />
          <node text="Diagnostics" bounds="[96,532][390,603]" />
          <node text="About" bounds="[96,1700][390,1771]" />
        </hierarchy>
        """

        class FakeAdb:
            def __init__(self):
                self.serial = "phone"
                self.dry_run = False
                self.taps = []
                self.keys = []

            def shell(self, command, **kwargs):
                return ""

            def run(self, command, **kwargs):
                return type("Completed", (), {"returncode": 0, "stdout": "", "stderr": ""})()

            def tap(self, x, y):
                self.taps.append((x, y))

            def input_keyevent(self, key):
                self.keys.append(key)

        fake_adb = FakeAdb()
        captured = {}
        args = type(
            "Args",
            (),
            {
                "serial": None,
                "dry_run": False,
                "package": "com.papi.nova.debug",
                "activity": "com.papi.nova.ui.NovaLibraryActivity",
                "artifacts_dir": "/tmp",
                "repo": str(MODULE_PATH.parents[1]),
                "apk": str(MODULE_PATH.parents[1] / "app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk"),
                "skip_install": True,
                "clear_logcat": False,
            },
        )()

        def capture_report(prefix, title, result, artifacts):
            captured["result"] = result
            captured["artifacts"] = [path.name for path in artifacts]

        with patch.object(nova_retroid_smoke, "ensure_adb_device", return_value="phone"), \
            patch.object(nova_retroid_smoke, "Adb", return_value=fake_adb), \
            patch.object(nova_retroid_smoke, "maybe_install"), \
            patch.object(nova_retroid_smoke, "begin_logcat_window", return_value="05-23 20:00:00.000"), \
            patch.object(nova_retroid_smoke, "capture_rotation_settings", return_value={}), \
            patch.object(nova_retroid_smoke, "write_system_setting"), \
            patch.object(nova_retroid_smoke, "restore_rotation_settings"), \
            patch.object(nova_retroid_smoke, "capture_png"), \
            patch.object(nova_retroid_smoke, "dump_xml", side_effect=[base_xml, left_xml, base_xml, right_xml, base_xml]), \
            patch.object(nova_retroid_smoke, "current_focus", return_value="com.papi.nova.debug/com.papi.nova.ui.NovaLibraryActivity"), \
            patch.object(nova_retroid_smoke, "read_logcat", return_value="com.papi.nova.debug clean"), \
            patch.object(nova_retroid_smoke, "write_report", side_effect=capture_report), \
            patch.object(nova_retroid_smoke.time, "sleep"):
            result = nova_retroid_smoke.run_phone(args)

        self.assertTrue(result.ok)
        self.assertTrue(captured["result"].ok)
        self.assertIn("phone_system_drawer_settings_bounds", result.values)
        self.assertEqual(fake_adb.keys, ["KEYCODE_BACK", "KEYCODE_BACK"])
        self.assertGreaterEqual(len(fake_adb.taps), 2)

    def test_library_start_command_includes_optional_debug_smoke_extras(self):
        args = type(
            "Args",
            (),
            {
                "package": "com.papi.nova.debug",
                "activity": "com.papi.nova.ui.NovaLibraryActivity",
                "host": "192.0.2.10",
                "server_name": "example-pc.local",
                "http_port": 47989,
                "https_port": 47984,
                "unique_id": "smoke pixel",
                "pc_uuid": "abc-123",
            },
        )()

        command = nova_retroid_smoke._library_start_command(args)

        self.assertIn("am start -n com.papi.nova.debug/com.papi.nova.ui.NovaLibraryActivity", command)
        self.assertIn("--es host 192.0.2.10", command)
        self.assertIn("--es server_name example-pc.local", command)
        self.assertIn("--ei http_port 47989", command)
        self.assertIn("--es unique_id 'smoke pixel'", command)
        self.assertIn("--es pc_uuid abc-123", command)

    def test_phone_subcommand_and_debug_manifest_are_available_for_adb_smoke(self):
        parser = nova_retroid_smoke.build_parser()
        args = parser.parse_args(["phone", "--dry-run"])
        manifest = MODULE_PATH.parents[1] / "app" / "src" / "debug" / "AndroidManifest.xml"
        manifest_text = manifest.read_text(encoding="utf-8")

        self.assertIs(args.func, nova_retroid_smoke.run_phone)
        self.assertIn('android:name="com.papi.nova.ui.NovaLibraryActivity"', manifest_text)
        self.assertIn('android:exported="true"', manifest_text)

    def test_smoke_help_documents_latest_debug_nova_polaris_pairing(self):
        parser = nova_retroid_smoke.build_parser()
        help_text = parser.format_help()

        self.assertEqual(nova_retroid_smoke.DEFAULT_PACKAGE, "com.papi.nova.debug")
        self.assertIn("app-nonRoot_game-arm64-v8a-debug.apk", str(nova_retroid_smoke.DEFAULT_APK))
        self.assertIn("latest available debug Nova APK", help_text)
        self.assertIn("latest available debug Polaris build", help_text)

    def test_default_repo_points_to_project_root(self):
        parser = nova_retroid_smoke.build_parser()
        args = parser.parse_args(["library"])

        self.assertEqual(Path(args.repo), MODULE_PATH.parents[1])

    def test_common_options_parse_before_or_after_subcommand(self):
        parser = nova_retroid_smoke.build_parser()

        before = parser.parse_args(["--artifacts-dir", "/tmp/before", "library", "--dry-run"])
        after = parser.parse_args(["library", "--artifacts-dir", "/tmp/after", "--dry-run"])

        self.assertEqual(before.artifacts_dir, "/tmp/before")
        self.assertTrue(before.dry_run)
        self.assertEqual(after.artifacts_dir, "/tmp/after")
        self.assertTrue(after.dry_run)


if __name__ == "__main__":
    unittest.main()
