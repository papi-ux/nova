"""Synchronize fixture input with observations from the real Qt event loop."""

import time


def wait_for_ui_observations(observation, app, timeout=3):
    # xdotool returning only acknowledges X11 delivery. Qt may still have the
    # key release, deferred focus change and layout polish queued. Wait for two
    # fresh app-owned observations before sending another key or using geometry.
    after = time.time_ns()
    deadline = time.monotonic() + timeout
    remaining = 2
    while time.monotonic() < deadline:
        if app.poll() is not None:
            raise AssertionError(f"Nova exited {app.returncode} while settling input")
        try:
            updated = observation.stat().st_mtime_ns
        except FileNotFoundError:
            updated = 0
        if updated > after:
            after = updated
            remaining -= 1
            if remaining == 0:
                return
        time.sleep(.01)
    raise AssertionError("Nova did not publish fresh UI observations after input")
