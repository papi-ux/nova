"""Exercise actual Vulkan feature-query chains without a physical GPU."""

import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class PyroWaveFeatureDiagnosticsTest(unittest.TestCase):
    def test_driver_feature_diagnostics(self):
        compiler = shutil.which("clang++") or shutil.which("g++")
        headers = pathlib.Path(os.environ.get("VULKAN_HEADERS_DIR", "/usr/include"))
        if compiler is None or not (headers / "vulkan/vulkan_core.h").is_file():
            self.skipTest("requires a C++ compiler and Vulkan headers (VULKAN_HEADERS_DIR)")
        with tempfile.TemporaryDirectory() as work:
            work = pathlib.Path(work)
            # NDK headers can supply Vulkan, but its libc headers must not shadow the host's.
            for name in ("vulkan", "vk_video"):
                if (headers / name).is_dir():
                    shutil.copytree(headers / name, work / name)
            binary = work / "feature-diagnostics"
            built = subprocess.run([
                compiler, "-std=c++11", "-Wall", "-Wextra", "-Werror",
                "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                f"-I{work}", f"-I{ROOT / 'app/src/main/jni/pyrowave-core'}",
                str(ROOT / "tools/pyrowave_feature_diagnostics_harness.cpp"), "-o", str(binary),
            ], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            ran = subprocess.run([str(binary)], capture_output=True, text=True)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("all feature diagnostic cases passed", ran.stdout)


if __name__ == "__main__":
    unittest.main()
