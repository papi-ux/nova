"""Exercise GPU timestamp interpretation with a host compiler and sanitizer runtimes."""

import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class PyroWaveTimingTest(unittest.TestCase):
    def test_timestamp_intervals(self):
        compiler = shutil.which("clang++") or shutil.which("g++")
        if compiler is None:
            self.skipTest("no C++ compiler")
        with tempfile.TemporaryDirectory() as work:
            binary = pathlib.Path(work) / "timing"
            command = [
                compiler, "-std=c++11", "-O2", "-Wall", "-Wextra", "-Werror",
                "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                f"-I{ROOT / 'app/src/main/jni/pyrowave-core'}",
                str(ROOT / "tools/pyrowave_timing_harness.cpp"), "-o", str(binary),
            ]
            built = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            ran = subprocess.run([str(binary)], capture_output=True, text=True)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("all cases passed", ran.stdout)


if __name__ == "__main__":
    unittest.main()
