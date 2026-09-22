"""Observe the default EGL or explicitly selected Vulkan app render thread."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

with tempfile.TemporaryDirectory(prefix="nova-graphics-startup-") as temporary:
    root = Path(temporary)
    report = root / "graphics.json"
    env = dict(os.environ, QT_QPA_PLATFORM="xcb", QT_FORCE_STDERR_LOGGING="1",
               LIBGL_ALWAYS_SOFTWARE="1", XDG_CONFIG_HOME=str(root / "config"),
               NOVA_DECK_GAMEPAD_DEVICE="/dev/null")
    for name in ("QT_XCB_GL_INTEGRATION", "QSG_RHI_BACKEND", "QT_QUICK_BACKEND", "NOVA_DECK_LIVE"):
        env.pop(name, None)
    vulkan = "--vulkan" in sys.argv[2:]
    result = subprocess.run([
        sys.argv[1], "--frontend-smoke-graphics-state", str(report),
        "--frontend-smoke-exit-after-ms", "1200",
    ] + (["--experimental-vulkan-stream"] if vulkan else []), env=env, capture_output=True, text=True, timeout=15)
    assert result.returncode == 0, result.stderr
    assert report.exists(), "app never reached a render-thread graphics observation: " + result.stderr
    state = json.loads(report.read_text())
    assert state == {"openGl": not vulkan, "vulkan": vulkan,
                     "eglDisplayCurrent": not vulkan, "eglContextCurrent": not vulkan}, state
    assert not any(error in result.stderr for error in ("ReferenceError", "TypeError", "failed to load")), result.stderr
print("Real app render thread selected " + ("explicit Vulkan" if vulkan else "default OpenGL/EGL"))
