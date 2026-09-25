"""Verify the actual NDK compiler arguments, not only the makefile's declared default."""

import os
import pathlib
import shlex
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class NativeOptimizationTest(unittest.TestCase):
    def test_debug_apk_optimization_and_debugger_override(self):
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            self.skipTest("Android SDK not configured")
        ndk = pathlib.Path(sdk) / "ndk/27.0.12077973/ndk-build"
        if not ndk.is_file():
            self.skipTest("pinned NDK not installed")
        jni = ROOT / "app/src/main/jni"
        with tempfile.TemporaryDirectory() as work:
            for optimization, debug_checks, expected in [
                (None, "1", "-O2"), (None, "0", "-O2"), ("debug", "1", "-O0")
            ]:
                with self.subTest(optimization=optimization, debug_checks=debug_checks):
                    command = [
                        str(ndk), "-n", "-B", "V=1", "moonlight-core", "pyrowave-jni",
                        "NDK_PROJECT_PATH=null", f"APP_BUILD_SCRIPT={jni / 'Android.mk'}",
                        f"NDK_APPLICATION_MK={jni / 'Application.mk'}", "APP_ABI=arm64-v8a",
                        "NDK_DEBUG=1", f"NDK_OUT={work}/obj", f"NDK_LIBS_OUT={work}/lib",
                        "PRODUCT_FLAVOR=nonRoot", f"NOVA_NATIVE_DEBUG_CHECKS={debug_checks}",
                        "NOVA_AUDIO_RECEIVE_DIAGNOSTICS=0",
                    ]
                    if optimization:
                        command.append(f"APP_OPTIM={optimization}")
                    ran = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
                    self.assertEqual(ran.returncode, 0, ran.stderr)
                    for source in ["RtpVideoQueue.c", "reedsolomon/rs.c", "pyrowave_renderer.cpp"]:
                        lines = [line for line in ran.stdout.splitlines()
                                 if " -c " in line and source in line]
                        self.assertEqual(len(lines), 1, f"compile command missing for {source}")
                        args = shlex.split(lines[0])
                        flags = [arg for arg in args if arg.startswith("-O")]
                        self.assertTrue(flags, source)
                        self.assertEqual(flags[-1], expected, source)
                        self.assertIn("-g", args, source)
                        if source != "pyrowave_renderer.cpp":
                            self.assertEqual("-DLC_DEBUG" in args, debug_checks == "1", source)


if __name__ == "__main__":
    unittest.main()
