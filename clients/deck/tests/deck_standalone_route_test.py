"""Exercise the installed shell entry points with an isolated, absent identity."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile


with tempfile.TemporaryDirectory(prefix="nova-standalone-test-") as temporary:
    root = Path(temporary)
    moonlight = root / "Moonlight.conf"
    moonlight.write_text("[General]\ncertificate=unused\nkey=unused\n", encoding="utf-8")
    env = dict(os.environ, QT_QPA_PLATFORM="offscreen", QT_QUICK_BACKEND="software",
               QT_FORCE_STDERR_LOGGING="1", NOVA_DECK_IDENTITY_DIR=str(root / "nova"),
               NOVA_DECK_MOONLIGHT_CONF=str(moonlight))
    for args, code in [(["--standalone", "--print-live-state"], 2),
                       (["--standalone", "--smoke-exit"], 0),
                       (["--pair", "--smoke-exit"], 0),
                       (["--manage-pcs", "--smoke-exit"], 0)]:
        result = subprocess.run([sys.argv[1], *args], env=env, capture_output=True, text=True, timeout=10)
        if result.returncode != code:
            raise AssertionError(f"{args}: exit {result.returncode}; {result.stderr}")
        if "--print-live-state" in args and ("No Nova pairing" not in result.stdout or "moonlight-override" in result.stdout):
            raise AssertionError("standalone silently fell back to Moonlight")
        if any(error in result.stderr for error in ("ReferenceError", "TypeError", "failed to load", "is not a type")):
            raise AssertionError(result.stderr)
        if (root / "nova").exists():
            raise AssertionError("opening setup or printing status created credentials before Pair was selected")
print("Standalone routes passed without reading Moonlight identity or creating credentials")
