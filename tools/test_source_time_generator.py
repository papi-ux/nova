"""Source-time build-tool selection, including the isolated raw-chain entrypoint."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import test_source_time_core as harness


class SourceTimeGeneratorTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="nova-source-time-generator-")
        self.addCleanup(temporary.cleanup)
        self.work = Path(temporary.name).resolve()
        self.bin = self.work / "bin"
        self.bin.mkdir()
        self.environment = patch.dict(os.environ, {"PATH": str(self.bin)})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def executable(self, name, body="raise SystemExit(0)\n"):
        executable = self.bin / name
        executable.write_text(f"#!{sys.executable}\n" + body)
        executable.chmod(0o755)
        return executable

    def test_ninja_without_make(self):
        self.executable("ninja")
        self.assertEqual(harness.select_cmake_generator(), "Ninja")

    def test_ninja_preferred_even_when_make_is_earlier_on_path(self):
        make_bin = self.work / "make-bin"
        make_bin.mkdir()
        self.executable("make").rename(make_bin / "make")
        self.executable("ninja")
        os.environ["PATH"] = os.pathsep.join((str(make_bin), str(self.bin)))
        self.assertEqual(harness.select_cmake_generator(), "Ninja")

    def test_make_fallback(self):
        self.executable("make")
        self.assertEqual(harness.select_cmake_generator(), "Unix Makefiles")

    def test_nonexecutable_ninja_uses_make(self):
        self.executable("ninja").chmod(0o644)
        self.executable("make")
        self.assertEqual(harness.select_cmake_generator(), "Unix Makefiles")

    def test_missing_tools_have_a_typed_error(self):
        with self.assertRaisesRegex(harness.MissingCMakeBuildTool, "ninja.*make.*PATH"):
            harness.select_cmake_generator()

    def test_isolated_entrypoint_reports_missing_tools_before_preparation(self):
        result = subprocess.run(
            [sys.executable, "-I", str(ROOT / "tools/test_source_time_core.py"),
             "--chain", "deck", "--work-dir", str(self.work / "missing-tools")],
            cwd=self.work, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("SOURCE_TIME_BUILD_TOOL_MISSING", result.stderr)
        self.assertIn("ninja", result.stderr)
        self.assertIn("make", result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertFalse((self.work / "missing-tools/source").exists())

    def test_isolated_raw_configure_and_build_keep_the_selected_generator(self):
        # This CMake stub records configure's generator and requires the build
        # to consume that same directory, without compiling or patching a core.
        self.executable("cmake", '''import json
from pathlib import Path
import sys
arguments = sys.argv[1:]
with Path(__file__).with_name("commands.jsonl").open("a") as log:
    log.write(json.dumps(arguments) + "\\n")
if arguments[0] == "--build":
    build = Path(arguments[1])
    assert (build / "generator").read_text() in ("Ninja", "Unix Makefiles")
else:
    generator = arguments[arguments.index("-G") + 1]
    build = Path(arguments[arguments.index("-B") + 1])
    build.mkdir()
    (build / "generator").write_text(generator)
    program = build / "source_time"
    program.write_text("#!" + sys.executable + "\\nraise SystemExit(0)\\n")
    program.chmod(0o755)
''')
        for tool, generator in (("ninja", "Ninja"), ("make", "Unix Makefiles")):
            with self.subTest(generator=generator):
                backend = self.executable(tool)
                commands_log = self.bin / "commands.jsonl"
                commands_log.unlink(missing_ok=True)
                destination = self.work / tool
                source = self.work / "immutable-core"
                environment = dict(os.environ, NOVA_MOONLIGHT_SOURCE_DIR=str(source),
                                   CMAKE_GENERATOR="unavailable ambient generator")
                result = subprocess.run(
                    [sys.executable, "-I", str(ROOT / "tools/test_source_time_core.py"),
                     "--chain", "raw", "--work-dir", str(destination)],
                    cwd=self.work, env=environment, capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                commands = [json.loads(line) for line in commands_log.read_text().splitlines()]
                self.assertEqual(commands[0][commands[0].index("-G") + 1], generator)
                self.assertEqual(commands[1][:2], ["--build", str(destination / "build")])
                self.assertEqual((destination / "build/generator").read_text(), generator)
                self.assertIn(source.as_posix(), (destination / "CMakeLists.txt").read_text())
                self.assertFalse((destination / "source").exists())
                self.assertFalse(source.exists())
                backend.unlink()


if __name__ == "__main__":
    unittest.main()
