"""Compile and run the PyroWave SDP matcher harness, under the sanitizers when available.

The matcher decides, by reading text a host sent, whether this client will stream a codec profile it
implements. Getting that wrong is not an error, it is a wrong picture, so the harness covers the
malformed and adversarial payloads an RTSP connection would never let a test reach.
"""

import pathlib
import shutil
import subprocess
import tempfile
import unittest


REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
HARNESS = REPO_ROOT / "tools" / "pyrowave_sdp_harness.c"
HEADER_DIR = (
    REPO_ROOT / "app" / "src" / "main" / "jni" / "moonlight-core" / "moonlight-common-c" / "src"
)


class PyroWaveSdpMatcherTest(unittest.TestCase):
    def test_matcher_accepts_and_refuses_what_it_should(self):
        if not (HEADER_DIR / "PyroWaveSdp.h").exists():
            self.skipTest(
                "moonlight-common-c is not initialised, or the PyroWave patch series is not applied"
            )

        compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
        if compiler is None:
            self.skipTest("no C compiler on this machine")

        with tempfile.TemporaryDirectory() as work:
            binary = pathlib.Path(work) / "harness"
            command = [
                compiler,
                "-std=c11",
                "-O1",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-fsanitize=address,undefined",
                "-fno-omit-frame-pointer",
                f"-I{HEADER_DIR}",
                str(HARNESS),
                "-o",
                str(binary),
            ]
            built = subprocess.run(command, capture_output=True, text=True)
            if built.returncode != 0 and "sanitize" in built.stderr:
                # Some toolchains ship without the sanitizer runtimes. The cases still matter.
                command = [c for c in command if not c.startswith("-fsanitize")]
                built = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)

            ran = subprocess.run([str(binary)], capture_output=True, text=True)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("all cases passed", ran.stdout)


if __name__ == "__main__":
    unittest.main()
