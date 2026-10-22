"""Synchronize fixture input with observations from the real Qt event loop."""

import collections
import json
import os
from pathlib import Path
import time

# Failure evidence only: nothing below changes the success predicate, the deadline,
# the 10 ms poll or the exit check, and nothing is written on success.
EVIDENCE_SAMPLES = 5
EVIDENCE_MAX_CHARS = 1024
NOTE_STATE_KEYS = ("host", "game", "focus", "busy", "layout", "detailOpen", "optionsOpen", "pickerOpen",
                   "nativePreviewOpen", "windowActive")
NOTE_ARTWORK_KEYS = ("heroReady", "iconReady", "logoReady", "posterReady")
NOTE_LOG_TAIL_BYTES = 16 * 1024
NOTE_WITHHELD_MARKERS = ("-----", "://", "token", "fingerprint", "password", "secret", "certificate", "key=")


class _SettlingEvidence:
    """A small, closed summary of what one settling wait observed. Never raises."""

    def __init__(self, after):
        self.wall_start_ns = after
        self.mono_start_ns = None
        self.polls = 0
        self.missing = 0
        self.fresh_seen = 0
        self.max_mtime_ns = None
        self.recent = collections.deque(maxlen=EVIDENCE_SAMPLES)
        try:
            self.mono_start_ns = time.monotonic_ns()
        except Exception:
            pass

    def sample(self, info, updated, after):
        try:
            self.polls += 1
            if info is None:
                self.missing += 1
            if self.max_mtime_ns is None or updated > self.max_mtime_ns:
                self.max_mtime_ns = updated
            if updated > after:
                self.fresh_seen += 1
            self.recent.append([updated, getattr(info, "st_ino", None), getattr(info, "st_size", None)])
        except Exception:
            pass

    def message(self, primary, app, after):
        try:
            summary = _format_evidence({
                "wall_start_ns": self.wall_start_ns, "wall_end_ns": time.time_ns(),
                "mono_start_ns": self.mono_start_ns, "mono_end_ns": time.monotonic_ns(),
                "polls": self.polls, "missing": self.missing, "fresh_needed": 2, "fresh_seen": self.fresh_seen,
                "last_after_ns": after, "max_mtime_ns": self.max_mtime_ns,
                "recent_mtime_ino_size": list(self.recent), "app_returncode": app.poll()})
            return primary + "\n[settling evidence] " + summary
        except Exception as error:
            return primary + "\n[settling evidence unavailable: " + type(error).__name__ + "]"


def _format_evidence(summary):
    text = json.dumps(summary, separators=(",", ":"), sort_keys=True)
    marker = "...[truncated]"
    return text if len(text) <= EVIDENCE_MAX_CHARS else text[:EVIDENCE_MAX_CHARS - len(marker)] + marker


def wait_for_ui_observations(observation, app, timeout=3):
    # xdotool returning only acknowledges X11 delivery. Qt may still have the
    # key release, deferred focus change and layout polish queued. Wait for two
    # fresh app-owned observations before sending another key or using geometry.
    after = time.time_ns()
    deadline = time.monotonic() + timeout
    remaining = 2
    evidence = _SettlingEvidence(after)
    while time.monotonic() < deadline:
        if app.poll() is not None:
            raise AssertionError(evidence.message(f"Nova exited {app.returncode} while settling input", app, after))
        try:
            info = observation.stat()
            updated = info.st_mtime_ns
        except FileNotFoundError:
            info = None
            updated = 0
        evidence.sample(info, updated, after)
        if updated > after:
            after = updated
            remaining -= 1
            if remaining == 0:
                return
        time.sleep(.01)
    raise AssertionError(evidence.message("Nova did not publish fresh UI observations after input", app, after))


def settling_failure_note(read_state, log_path):
    """Closed fixture state and a bounded, filtered app-log tail for a settling failure. Never raises."""
    parts = []
    try:
        state = read_state()
        closed = {key: state[key] for key in NOTE_STATE_KEYS
                  if key in state and isinstance(state[key], (str, bool, int)) and len(str(state[key])) <= 128}
        artwork = state.get("artwork") if isinstance(state.get("artwork"), dict) else {}
        closed["artwork"] = {key: artwork[key] for key in NOTE_ARTWORK_KEYS if isinstance(artwork.get(key), bool)}
        parts.append("[settling state] " + json.dumps(closed, separators=(",", ":"), sort_keys=True))
    except Exception as error:
        parts.append("[settling state unavailable: " + type(error).__name__ + "]")
    try:
        with open(log_path, "rb") as handle:
            handle.seek(0, 2)
            size = handle.tell()
            handle.seek(max(0, size - NOTE_LOG_TAIL_BYTES))
            tail = handle.read(NOTE_LOG_TAIL_BYTES).decode("utf-8", errors="replace")
        lines = ["[line withheld]" if any(marker in line.lower() for marker in NOTE_WITHHELD_MARKERS) else line
                 for line in tail.splitlines()]
        filtered = "\n".join(lines).encode("utf-8")[-NOTE_LOG_TAIL_BYTES:].decode("utf-8", errors="ignore")
        parts.append("[app log tail, last %d of %d bytes]\n" % (min(size, NOTE_LOG_TAIL_BYTES), size) + filtered)
    except Exception as error:
        parts.append("[app log tail unavailable: " + type(error).__name__ + "]")
    return "\n".join(parts)


def _task_stat(raw, pid):
    # procfs reports a zero file size; read its bounded content rather than
    # comparing it with st_size. comm can contain spaces and parentheses.
    prefix, separator, fields = raw.rpartition(") ")
    values = fields.split()
    if not separator or prefix.split("(", 1)[0].strip() != str(pid) or len(values) < 20:
        raise ValueError("Malformed task stat")
    return {"pid": pid, "state": values[0], "parent": int(values[1]),
            "userTicks": int(values[11]), "systemTicks": int(values[12]), "startTicks": int(values[19])}


def _bounded_proc_read(path, limit=4096):
    with path.open("r") as handle:
        value = handle.read(limit + 1)
    if len(value) > limit:
        raise ValueError("Oversized proc observation")
    return value.strip()


def _process_snapshot(app):
    # Only the fixture's direct child with the expected executable is observed.
    # Never attach a debugger, send a signal, or inspect an arbitrary PID.
    pid = app.pid
    if type(pid) is not int or pid <= 0 or app.poll() is not None:
        raise ValueError("Fixture child unavailable")
    if not isinstance(app.args, (list, tuple)) or not app.args:
        raise ValueError("Fixture executable unavailable")
    process = Path("/proc") / str(pid)
    expected = Path(app.args[0]).resolve(strict=True)
    if (process / "exe").readlink() != expected:
        raise ValueError("Fixture executable changed")
    task = process / "task" / str(pid)
    before = _task_stat(_bounded_proc_read(task / "stat"), pid)
    if before["parent"] != os.getpid():
        raise ValueError("Not the fixture's child")
    observed = dict(before)
    for name in ("wchan", "syscall"):
        try:
            observed[name] = _bounded_proc_read(task / name, 512)
        except OSError as failure:
            observed[name] = {"unavailable": type(failure).__name__}
    after = _task_stat(_bounded_proc_read(task / "stat"), pid)
    if (before["parent"], before["startTicks"]) != (after["parent"], after["startTicks"]):
        raise ValueError("Fixture child identity changed")
    if app.poll() is not None or (process / "exe").readlink() != expected:
        raise ValueError("Fixture child exited or changed executable")
    return observed


def settling_process_failure_note(app):
    """Failure-only evidence; never changes the settling predicate or deadline."""
    try:
        first = _process_snapshot(app)
        time.sleep(1)
        second = _process_snapshot(app)
        if (first["pid"], first["startTicks"]) != (second["pid"], second["startTicks"]):
            raise ValueError("Fixture child identity changed between observations")
        return "[settling process] " + json.dumps([first, second], separators=(",", ":"), sort_keys=True)
    except Exception as failure:
        return "[settling process unavailable: " + type(failure).__name__ + "]"


def attach_settling_failure_note(error, read_state, log_path, app=None):
    """Add the note to the original exception object; its message, traceback and context are kept."""
    try:
        error.add_note(settling_failure_note(read_state, log_path))
    except Exception:
        pass
    if app is not None:
        try:
            error.add_note(settling_process_failure_note(app))
        except Exception:
            pass
