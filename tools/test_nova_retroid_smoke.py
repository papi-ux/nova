import importlib.util
import unittest
from pathlib import Path


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

    def test_library_rail_analysis_reports_all_required_items_and_hint_spacing(self):
        xml = """
        <hierarchy>
          <node text="Refresh" bounds="[51,496][201,584]" />
          <node text="Options" bounds="[219,496][370,584]" />
          <node text="System" bounds="[388,496][539,584]" />
          <node text="Switch" bounds="[51,593][201,681]" />
          <node text="All" bounds="[48,689][293,779]" />
          <node text="Recent" bounds="[299,690][539,778]" />
          <node text="Sources" bounds="[51,787][290,875]" />
          <node text="HDR" bounds="[299,787][539,875]" />
          <node text="More" bounds="[51,884][290,972]" />
          <node text="A Select" bounds="[590,1016][710,1062]" />
        </hierarchy>
        """

        result = nova_retroid_smoke.analyze_library_rail(xml)

        self.assertTrue(result.ok)
        self.assertEqual(result.values["rail_right"], 539)
        self.assertEqual(result.values["hint_left"], 590)
        self.assertEqual(result.values["hint_gap"], 51)
        self.assertEqual(result.missing, [])

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


if __name__ == "__main__":
    unittest.main()
