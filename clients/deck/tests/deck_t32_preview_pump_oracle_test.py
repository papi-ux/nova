#!/usr/bin/env python3
import pathlib
import tempfile
import unittest

import sys

sys.dont_write_bytecode = True

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import deck_t32_preview_pump_oracle as oracle


CTEST_LOG = """Test project /home/deck/nova-t31-src/build/deck-t31
    Start 3: nova_deck_stream_media_adapters_test
3/8 Test #3: nova_deck_stream_media_adapters_test .........   Passed    0.32 sec
    Start 4: nova_deck_qsg_render_node_scenegraph_smoke
4/8 Test #4: nova_deck_qsg_render_node_scenegraph_smoke ...   Passed    0.20 sec

100% tests passed, 0 tests failed out of 8
"""

QSG_LOG = """Nova Deck QSGRenderNode VAAPI/EGL render path status=ready objects=1 layers=2 ready=1 planned=1 readiness stayed false until shader composition proof Hardware-backed VAAPI frame decoded and exported as DRM_PRIME dmabuf metadata; Qt Quick render-target readiness is proven
Nova Deck QSGRenderNode scenegraph smoke passed: product render-node path entered 2 consecutive render passes; imported two DRM_PRIME layers, proved shader composition, then reported ready
"""

READY_LAST_TEST_LOG = """4/4 Testing: nova_deck_qsg_render_node_scenegraph_smoke
Output:
----------------------------------------------------------
Nova Deck QSGRenderNode VAAPI/EGL render path status=ready objects=1 layers=2 ready=1 planned=1 readiness stayed false until shader composition proof Hardware-backed VAAPI frame decoded and exported as DRM_PRIME dmabuf metadata; Qt Quick render-target readiness is proven
Nova Deck QSGRenderNode scenegraph smoke passed: product render-node path entered 2 consecutive render passes; imported two DRM_PRIME layers, proved shader composition, then reported ready
<end of output>
"""

UNSUPPORTED_QSG_LOG = QSG_LOG.replace("status=ready", "status=unsupported-non-opengl-scene-graph").replace(
    "ready=1", "ready=0"
)


class DeckT32PreviewPumpOracleTest(unittest.TestCase):
    def write_artifacts(self, directory: pathlib.Path, *, qsg_log: str = QSG_LOG) -> None:
        (directory / "ctest.log").write_text(CTEST_LOG, encoding="utf-8")
        (directory / "qsg-gamescope-smoke.log").write_text(qsg_log, encoding="utf-8")
        (directory / "LastTest.log").write_text(READY_LAST_TEST_LOG, encoding="utf-8")

    def test_oracle_accepts_deck_ctest_preview_pump_and_gamescope_ready_artifacts(self):
        with tempfile.TemporaryDirectory() as temp:
            artifact_dir = pathlib.Path(temp)
            self.write_artifacts(artifact_dir)

            results = oracle.validate_oracle(artifact_dir=artifact_dir, deck_root=ROOT)

        self.assertIn("preview pump source guard PASS", results)
        self.assertIn("Deck artifacts PASS", results)
        self.assertIn("gamescope QSG ready proof PASS", results)
        self.assertIn("route guardrails PASS", results)

    def test_oracle_rejects_headless_or_unsupported_qsg_artifacts(self):
        with tempfile.TemporaryDirectory() as temp:
            artifact_dir = pathlib.Path(temp)
            self.write_artifacts(artifact_dir, qsg_log=UNSUPPORTED_QSG_LOG)

            with self.assertRaisesRegex(oracle.OracleFailure, "gamescope QSG ready render proof"):
                oracle.validate_oracle(artifact_dir=artifact_dir, deck_root=ROOT)

    def test_oracle_rejects_missing_preview_pump_ctest_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            artifact_dir = pathlib.Path(temp)
            self.write_artifacts(artifact_dir)
            (artifact_dir / "ctest.log").write_text(CTEST_LOG.replace("nova_deck_stream_media_adapters_test", "missing"), encoding="utf-8")

            with self.assertRaisesRegex(oracle.OracleFailure, "Deck CTest preview pump binary"):
                oracle.validate_oracle(artifact_dir=artifact_dir, deck_root=ROOT)


if __name__ == "__main__":
    unittest.main()
