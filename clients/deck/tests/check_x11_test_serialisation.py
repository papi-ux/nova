#!/usr/bin/env python3
"""Every test that starts an X server must hold RESOURCE_LOCK nova_deck_x11.

Some xvfb-run packages hand out :99 to two callers starting at the same moment despite -a, so two
concurrent X fixtures can end up on one display and whichever loses fails for no visible reason. The
lock is what prevents that, and it only works if every X-starting test takes it: one test that starts
an X server without it can take the display out from under a test that believes it has exclusive use.

Two tests were missing from the list, and the reason they went unnoticed is worth recording. Grepping
CMakeLists.txt for this cannot be trusted. add_test often closes on the same line as its last argument,
so a line scan attributes the next block's xvfb-run to the previous test, and a regex over add_test(...)
blocks mis-slices them. Two hand-written audits gave two different wrong answers, of seven and
twenty-one, before ctest itself gave two. So this asks ctest, which reports commands and properties
already resolved, including names added through a foreach.

Run by CTest against its own build directory. --show-only lists tests without running any, so this
cannot recurse.
"""

import json
import subprocess
import sys

LOCK = "nova_deck_x11"


def main(argv):
    if len(argv) != 1:
        print("usage: check_x11_test_serialisation.py <build-dir>", file=sys.stderr)
        return 2
    build_dir = argv[0]

    done = subprocess.run(
        ["ctest", "--test-dir", build_dir, "--show-only=json-v1"],
        capture_output=True, text=True,
    )
    if done.returncode != 0:
        print("ctest --show-only failed:\n%s" % done.stderr, file=sys.stderr)
        return 1

    tests = json.loads(done.stdout)["tests"]

    def properties(test):
        return {p["name"]: p.get("value") for p in test.get("properties", [])}

    def locks(test):
        """RESOURCE_LOCK arrives as a list, since CMake allows several locks on one test.

        Comparing it to a string is always false, which made the first version of this guard report
        every X test as unserialised. Normalise, then ask for membership.
        """
        value = properties(test).get("RESOURCE_LOCK")
        if value is None:
            return []
        return value if isinstance(value, list) else [value]

    starts_x, holds_lock = set(), set()
    for test in tests:
        name = test["name"]
        if any("xvfb" in str(part) for part in test.get("command", [])):
            starts_x.add(name)
        if LOCK in locks(test):
            holds_lock.add(name)

    unserialised = sorted(starts_x - holds_lock)
    if unserialised:
        print(
            "these tests start an X server without RESOURCE_LOCK %s, so they can collide on a\n"
            "display with a test that holds it. Add them to the foreach list in clients/deck/"
            "CMakeLists.txt:" % LOCK,
            file=sys.stderr,
        )
        for name in unserialised:
            print("  %s" % name, file=sys.stderr)
        return 1

    # Over-locking is not a correctness problem, but it serialises a test for no reason and the list
    # is meant to say what it means, so it is reported rather than left to rot.
    pointless = sorted(holds_lock - starts_x)
    if pointless:
        print(
            "these tests hold RESOURCE_LOCK %s but start no X server, so they are serialised for\n"
            "nothing. Remove them from the foreach list, or say in a comment what they share:" % LOCK,
            file=sys.stderr,
        )
        for name in pointless:
            print("  %s" % name, file=sys.stderr)
        return 1

    print("%d tests start an X server and all %d hold %s" % (len(starts_x), len(holds_lock), LOCK))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
