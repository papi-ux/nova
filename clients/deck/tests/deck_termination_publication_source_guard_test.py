"""Guard the native termination publication boundary, not a runtime race test.

A deterministic mid-store interleaving would require an injected production
hook. Instead, guard the existing seq_cst payload-before-flag protocol at the
actual callback and reader; rendered callback cases cover its consumers.
"""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / "src/stream/deck_stream_core.cpp").read_text()


def body(name):
    start = SOURCE.index(name)
    return SOURCE[start:SOURCE.index("\n}\n", start)]


class TerminationPublicationBoundaryTest(unittest.TestCase):
    def test_callback_publishes_error_before_ready_flag(self):
        callback = body("void DeckStreamSession::listenerConnectionTerminatedForSlot(")
        self.assertLess(callback.index("owner->terminationErrorCode_ = errorCode;"),
                        callback.index("owner->connectionTerminatedSeen_ = true;"),
                        "termination payload must precede ready flag")

    def test_snapshot_reads_ready_flag_before_payload(self):
        reader = body("DeckMoonlightConnectionStatus DeckStreamSession::connectionStatus()")
        self.assertLess(reader.index("connectionTerminatedSeen_.load()"),
                        reader.index("terminationErrorCode_.load()"))

    def test_prepare_clears_ready_flag_before_payload(self):
        prepare = body("DeckStreamTransition DeckStreamSession::prepare(")
        self.assertLess(prepare.index("connectionTerminatedSeen_ = false;"),
                        prepare.index("terminationErrorCode_ = 0;"))


if __name__ == "__main__":
    unittest.main()
