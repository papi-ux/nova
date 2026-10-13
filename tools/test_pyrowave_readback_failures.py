"""Fail each step the debug SGSR readback checks, through a fake Vulkan loader, without a GPU."""

import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

# Just enough of the Android headers for the renderer to compile on a host. Logs go to stdout so a
# failing case shows what the renderer said.
STUBS = {
    "android/log.h": """#pragma once
#include <stdarg.h>
#include <stdio.h>
enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
static inline int __android_log_print(int priority, const char *tag, const char *format, ...) {
  va_list args;
  va_start(args, format);
  printf("log %d %s: ", priority, tag);
  vprintf(format, args);
  printf("\\n");
  va_end(args);
  return 0;
}
""",
    "android/native_window.h": """#pragma once
typedef struct ANativeWindow ANativeWindow;
""",
    "sys/system_properties.h": """#pragma once
#define PROP_VALUE_MAX 92
static inline int __system_property_get(const char *name, char *value) {
  (void)name;
  value[0] = 0;
  return 0;
}
""",
}


class PyroWaveReadbackFailureTest(unittest.TestCase):
    def test_each_checked_step_ends_the_readback(self):
        compiler = shutil.which("clang++") or shutil.which("g++")
        headers = pathlib.Path(os.environ.get("VULKAN_HEADERS_DIR", "/usr/include"))
        if compiler is None or not (headers / "vulkan/vulkan_core.h").is_file():
            self.skipTest("requires a C++ compiler and Vulkan headers (VULKAN_HEADERS_DIR)")
        core = ROOT / "app/src/main/jni/pyrowave-core"
        with tempfile.TemporaryDirectory() as work:
            work = pathlib.Path(work)
            # NDK headers can supply Vulkan, but its libc headers must not shadow the host's.
            for name in ("vulkan", "vk_video"):
                if (headers / name).is_dir():
                    shutil.copytree(headers / name, work / name)
            for name, text in STUBS.items():
                (work / name).parent.mkdir(parents=True, exist_ok=True)
                (work / name).write_text(text)
            binary = work / "readback-failures"
            built = subprocess.run([
                compiler, "-std=c++17", "-O1", "-Wall", "-Wextra", "-Wno-unused-parameter",
                "-Wno-unused-private-field", "-Werror",
                "-fsanitize=address,undefined", "-fno-sanitize-recover=all", "-fno-omit-frame-pointer",
                f"-I{work}", f"-I{core}", f"-I{core / 'include'}",
                str(ROOT / "tools/pyrowave_readback_failure_harness.cpp"), "-o", str(binary),
            ], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            ran = subprocess.run([str(binary)], capture_output=True, text=True, timeout=60)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("all readback failure cases passed", ran.stdout)


if __name__ == "__main__":
    unittest.main()
