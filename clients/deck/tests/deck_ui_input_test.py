"""Pure contract test for deck_ui_input: the settling predicate and its bounded failure evidence.

A deterministic fake clock, observation and app drive the real helper: no real waits, processes,
X server or device.
"""
import json
import os
import tempfile
import types
import unittest
from unittest import mock

import deck_ui_input

TIMEOUT_MESSAGE = "Nova did not publish fresh UI observations after input"
WALL = 1_700_000_000_000_000_000


class FakeTime:
    def __init__(self):
        self.wall, self.mono, self.sleeps = WALL, 100.0, []

    def time_ns(self):
        return self.wall

    def monotonic(self):
        return self.mono

    def monotonic_ns(self):
        return int(self.mono * 1_000_000_000)

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.mono += seconds
        self.wall += int(seconds * 1_000_000_000)


class FakeObservation:
    """Returns the scripted stat results in order, then repeats the last one."""

    def __init__(self, results):
        self.results, self.calls = list(results), 0

    def stat(self):
        result = self.results[min(self.calls, len(self.results) - 1)]
        self.calls += 1
        if isinstance(result, BaseException):
            raise result
        if isinstance(result, int):
            return types.SimpleNamespace(st_mtime_ns=result, st_ino=7, st_size=42)
        return result


class FakeApp:
    def __init__(self, exit_on_poll=None, returncode=3):
        self.polls, self.exit_on_poll, self.exit_code, self.returncode = 0, exit_on_poll, returncode, None

    def poll(self):
        self.polls += 1
        if self.exit_on_poll is not None and self.polls >= self.exit_on_poll:
            self.returncode = self.exit_code
        return self.returncode


class BrokenInode:
    """A stat result whose extra metadata cannot be read; the predicate only needs st_mtime_ns."""

    def __init__(self, mtime):
        self.st_mtime_ns = mtime

    @property
    def st_ino(self):
        raise RuntimeError("inode unavailable")


def run_wait(results, app=None):
    clock = FakeTime()
    observation = FakeObservation(results)
    with mock.patch.object(deck_ui_input, "time", clock):
        deck_ui_input.wait_for_ui_observations(observation, app or FakeApp())
    return clock, observation


def failed_wait(results, app=None):
    clock = FakeTime()
    observation = FakeObservation(results)
    with mock.patch.object(deck_ui_input, "time", clock):
        try:
            deck_ui_input.wait_for_ui_observations(observation, app or FakeApp())
        except AssertionError as error:
            return clock, observation, str(error)
    raise AssertionError("the settling wait unexpectedly succeeded")


def evidence(message):
    first, _, rest = message.partition("\n[settling evidence] ")
    return first, json.loads(rest)


class SettlingContract(unittest.TestCase):
    def test_two_strictly_increasing_fresh_observations_succeed(self):
        clock, observation = run_wait([WALL + 1, WALL + 2])
        self.assertEqual(observation.calls, 2)
        self.assertEqual(clock.sleeps, [0.01])

    def test_one_fresh_observation_is_not_enough(self):
        clock, observation, message = failed_wait([WALL + 5])
        first, summary = evidence(message)
        self.assertEqual(first, TIMEOUT_MESSAGE)
        self.assertEqual(summary["fresh_seen"], 1)
        self.assertEqual(summary["fresh_needed"], 2)
        self.assertEqual(summary["polls"], observation.calls)
        self.assertTrue(all(seconds == 0.01 for seconds in clock.sleeps))
        self.assertAlmostEqual(sum(clock.sleeps), 3.0, delta=0.02)
        self.assertIsNone(summary["app_returncode"])
        self.assertLessEqual(len(summary["recent_mtime_ino_size"]), deck_ui_input.EVIDENCE_SAMPLES)

    def test_equal_or_older_mtimes_are_not_fresh(self):
        _, _, message = failed_wait([WALL - 1, WALL])
        first, summary = evidence(message)
        self.assertEqual(first, TIMEOUT_MESSAGE)
        self.assertEqual(summary["fresh_seen"], 0)
        self.assertEqual(summary["max_mtime_ns"], WALL)

    def test_a_repeated_mtime_after_one_fresh_one_is_not_a_second(self):
        _, _, message = failed_wait([WALL + 9, WALL + 9, WALL + 9])
        self.assertEqual(evidence(message)[1]["fresh_seen"], 1)

    def test_missing_observation_counts_as_zero(self):
        _, observation, message = failed_wait([FileNotFoundError()])
        first, summary = evidence(message)
        self.assertEqual(first, TIMEOUT_MESSAGE)
        self.assertEqual(summary["missing"], observation.calls)
        self.assertEqual(summary["fresh_seen"], 0)

    def test_app_exit_keeps_the_original_prefix(self):
        _, observation, message = failed_wait([WALL + 1], FakeApp(exit_on_poll=1, returncode=3))
        first, summary = evidence(message)
        self.assertEqual(first, "Nova exited 3 while settling input")
        self.assertEqual(observation.calls, 0)
        self.assertEqual(summary["app_returncode"], 3)

    def test_evidence_is_bounded(self):
        _, _, message = failed_wait([WALL + 5])
        tail = message.partition("\n[settling evidence] ")[2]
        self.assertLessEqual(len(tail), deck_ui_input.EVIDENCE_MAX_CHARS)
        oversized = deck_ui_input._format_evidence({"padding": "x" * 2048})
        self.assertEqual(len(oversized), deck_ui_input.EVIDENCE_MAX_CHARS)
        self.assertTrue(oversized.endswith("...[truncated]"))

    def test_unreadable_extra_metadata_neither_breaks_success_nor_failure(self):
        run_wait([BrokenInode(WALL + 1), BrokenInode(WALL + 2)])
        _, _, message = failed_wait([BrokenInode(WALL - 1)])
        self.assertTrue(message.startswith(TIMEOUT_MESSAGE + "\n"))

    def test_evidence_formatting_failure_keeps_the_primary_failure(self):
        with mock.patch.object(deck_ui_input, "_format_evidence", side_effect=RuntimeError("format")):
            _, _, message = failed_wait([WALL + 5])
        self.assertEqual(message, TIMEOUT_MESSAGE + "\n[settling evidence unavailable: RuntimeError]")
        with mock.patch.object(deck_ui_input, "_format_evidence", side_effect=RuntimeError("format")):
            _, _, message = failed_wait([WALL + 1], FakeApp(exit_on_poll=1, returncode=9))
        self.assertTrue(message.startswith("Nova exited 9 while settling input\n[settling evidence unavailable"))


class FailureNote(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.log = os.path.join(self.directory.name, "app.log")

    def tearDown(self):
        self.directory.cleanup()

    def test_state_is_closed_and_log_tail_is_bounded_and_filtered(self):
        filler = ("ordinary line " * 8 + "\n") * 400
        with open(self.log, "w", encoding="utf-8") as handle:
            handle.write(filler + "QML warning: ready\nfetch https://10.0.0.1/path?x=1\nsessionToken=abc\n-----BEGIN CERT\n")
        state = {"focus": "game-7", "game": "game-7", "detailOpen": False, "playSetup": {"secret": "x"},
                 "artwork": {"logoReady": True, "posterReady": False, "hero": "image://library-art/1"},
                 "titles": ["A"], "busy": False}
        note = deck_ui_input.settling_failure_note(lambda: state, self.log)
        header, _, rest = note.partition("\n")
        closed = json.loads(header.removeprefix("[settling state] "))
        self.assertEqual(closed, {"focus": "game-7", "game": "game-7", "detailOpen": False, "busy": False,
                                  "artwork": {"logoReady": True, "posterReady": False}})
        log_part = rest.partition("\n")[2]
        self.assertLessEqual(len(log_part.encode("utf-8")), deck_ui_input.NOTE_LOG_TAIL_BYTES)
        self.assertIn("QML warning: ready", log_part)
        for withheld in ("https://", "sessionToken", "BEGIN CERT"):
            self.assertNotIn(withheld, log_part)
        self.assertIn("[line withheld]", log_part)

    def test_utf8_log_tail_respects_the_byte_bound_at_a_character_boundary(self):
        with open(self.log, "w", encoding="utf-8") as handle:
            handle.write("界" * 10000)
        note = deck_ui_input.settling_failure_note(lambda: {}, self.log)
        log_part = note.partition("\n")[2].partition("\n")[2]
        self.assertLessEqual(len(log_part.encode("utf-8")), deck_ui_input.NOTE_LOG_TAIL_BYTES)

    def test_read_failures_are_reported_not_raised(self):
        def broken():
            raise ValueError("state unreadable")
        note = deck_ui_input.settling_failure_note(broken, os.path.join(self.directory.name, "missing.log"))
        self.assertIn("[settling state unavailable: ValueError]", note)
        self.assertIn("[app log tail unavailable: FileNotFoundError]", note)

    def test_attaching_the_note_keeps_the_original_exception(self):
        self.assertTrue(hasattr(BaseException, "add_note"))
        try:
            raise AssertionError(TIMEOUT_MESSAGE)
        except AssertionError as caught:
            error, traceback = caught, caught.__traceback__
        deck_ui_input.attach_settling_failure_note(error, lambda: {"focus": "game-7"}, self.log)
        self.assertEqual(error.args, (TIMEOUT_MESSAGE,))
        self.assertIs(error.__traceback__, traceback)
        self.assertEqual(len(error.__notes__), 1)
        self.assertIn('[settling state] {"artwork":{},"focus":"game-7"}', error.__notes__[0])

    def test_a_failing_note_never_masks_the_original_exception(self):
        error = AssertionError(TIMEOUT_MESSAGE)
        with mock.patch.object(deck_ui_input, "settling_failure_note", side_effect=RuntimeError("note")):
            deck_ui_input.attach_settling_failure_note(error, lambda: {}, self.log)
        self.assertEqual(error.args, (TIMEOUT_MESSAGE,))
        self.assertFalse(getattr(error, "__notes__", []))


if __name__ == "__main__":
    unittest.main()
