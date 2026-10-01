"""Check malformed decoder input and parser progress without a GPU."""

import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class PyroWavePacketGuardTest(unittest.TestCase):
    def test_block_lengths_and_progress_in_c_and_cpp(self):
        for language, standard, candidates in (
            ("c", "c11", ("clang", "gcc")),
            ("c++", "c++11", ("clang++", "g++")),
        ):
            with self.subTest(language=language):
                compiler = next((shutil.which(c) for c in candidates if shutil.which(c)), None)
                self.assertIsNotNone(compiler, f"requires a {language} compiler")
                with tempfile.TemporaryDirectory() as work:
                    binary = pathlib.Path(work) / "packet-guard"
                    built = subprocess.run([
                        compiler, "-x", language, f"-std={standard}", "-O2", "-Wall", "-Wextra", "-Werror",
                        "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                        f"-I{ROOT / 'app/src/main/jni/pyrowave-core'}",
                        str(ROOT / "tools/pyrowave_packet_guard_harness.c"), "-o", str(binary),
                    ], capture_output=True, text=True)
                    self.assertEqual(built.returncode, 0, built.stderr)
                    ran = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
                    self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
                    self.assertIn("all packet length cases passed", ran.stdout)


if __name__ == "__main__":
    unittest.main()
