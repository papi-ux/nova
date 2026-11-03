"""Native packet reception observer (Nova #442) against the production core.

NOVA_PACKET_RECEPTION_HARNESS supplies a harness built by the Deck CMake from its
own prepared core. Otherwise this prepares a build-owned copy of the selected core
and builds the harness with ASan/UBSan:

  NOVA_PACKET_RECEPTION_CHAIN=android        patches 0001-0011, as Android applies them
  NOVA_PACKET_RECEPTION_CHAIN=deck           the Deck's nova_deck_prepare_rtsp() chain
  NOVA_PACKET_RECEPTION_CHAIN=deck-pyrowave  the Deck chain plus its optional PyroWave patch

NOVA_MOONLIGHT_SOURCE_DIR selects the core (default: the pinned submodule). The
selected source is never modified. The session fixtures run real native sessions over
loopback; nothing here needs a host, device or network.
"""
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PATCHES = ROOT / "app/src/main/jni/moonlight-core/patches"
SPEC = ROOT / "docs/native-packet-reception-feedback.md"
RECEPTION_PATCH = "0011-feat-video-report-bounded-native-packet-reception.patch"

EXPECTED_CASES = {
    "unit": [
        "serializer_bounds_0_16_17", "golden_encodings", "decoder_refuses_malformed",
        "first_accepted_per_bin_keeps_socket_read_time", "sixteen_samples_then_timing_sample_limit",
        "zero_media_and_receive_loop_progress", "delayed_acceptance_counts_in_accept_interval",
        "stale_sample_omitted_incomplete", "long_interval_incomplete_without_samples",
        "counter_saturation_incomplete", "clock_failure_sentinel_once", "clock_regression_paths",
        "zero_length_interval_skipped_without_sequence", "sequence_stops_before_wrap_and_retire",
        "restart_resets_epoch", "host_offer_parser", "gate_function", "internal_bounds",
    ],
    "session-paths": [
        "session_encrypted_video_accepted_original_paths", "session_plaintext_video_accepted_original_paths",
    ],
    "session-reconnect": ["session_reconnect_fresh_epoch"],
    "session-clock": ["session_clock_failure_sentinel", "session_clock_regression_sentinel"],
    "session-gates": ["session_gates_no_echo_no_reports", "sdp_gates_without_session"],
}


def git_apply(directory, patch, *arguments):
    environment = {k: v for k, v in os.environ.items() if k not in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE")}
    environment["GIT_CEILING_DIRECTORIES"] = str(pathlib.Path(directory).resolve().parent)
    return subprocess.run(["git", "apply", "--whitespace=error", *arguments, str(patch)], cwd=directory,
                          env=environment, capture_output=True, text=True, timeout=30)


def copy_core(source, destination):
    shutil.copytree(source, destination, ignore=shutil.ignore_patterns(".git"), symlinks=True)


def prepare_android_chain(source, destination):
    """Unwind any patches the source already carries, then apply 0001..0011 in order."""
    copy_core(source, destination)
    patches = sorted(PATCHES.glob("*.patch"))
    for patch in reversed(patches):
        if git_apply(destination, patch, "--reverse", "--check").returncode == 0:
            undone = git_apply(destination, patch, "--reverse")
            if undone.returncode:
                raise AssertionError(undone.stderr)
    for patch in patches:
        applied = git_apply(destination, patch)
        if applied.returncode:
            raise AssertionError(f"{patch.name} does not apply after its predecessors:\n{applied.stderr}")


def prepare_deck_chain(source, destination, work, pyrowave):
    script = work / "prepare.cmake"
    script.write_text(f'''cmake_minimum_required(VERSION 3.24)
include("{(ROOT / 'clients/deck/cmake/NovaHardenRtsp.cmake').as_posix()}")
nova_deck_prepare_rtsp("{pathlib.Path(source).as_posix()}" "{destination.as_posix()}")
''')
    ran = subprocess.run(["cmake", "-P", str(script)], capture_output=True, text=True, timeout=60)
    if ran.returncode:
        raise AssertionError(ran.stdout + ran.stderr)
    if pyrowave:
        # Exactly what clients/deck/CMakeLists.txt does for NOVA_DECK_BUILD_PYROWAVE.
        ran = subprocess.run(["patch", "--batch", "-p1", "-i", str(ROOT / "clients/deck/patches/moonlight-pyrowave.patch")],
                             cwd=destination, capture_output=True, text=True, timeout=30)
        if ran.returncode:
            raise AssertionError(ran.stdout + ran.stderr)
        shutil.copyfile(ROOT / "clients/deck/pyrowave/protocol.h", destination / "src/NovaPyrowaveProtocol.h")


class NativePacketReceptionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied = os.environ.get("NOVA_PACKET_RECEPTION_HARNESS")
        if supplied:
            cls.harness = pathlib.Path(supplied)
            if not cls.harness.is_file():
                raise AssertionError("declared packet reception harness is missing")
            return
        if not shutil.which("cmake"):
            raise AssertionError("cmake is required for the packet reception fixtures")
        source = pathlib.Path(os.environ.get("NOVA_MOONLIGHT_SOURCE_DIR",
                                             ROOT / "app/src/main/jni/moonlight-core/moonlight-common-c")).resolve()
        if not (source / "src/RtspConnection.c").is_file() or not (source / "enet/include/enet/enet.h").is_file():
            raise AssertionError("initialize the pinned native submodules first")
        chain = os.environ.get("NOVA_PACKET_RECEPTION_CHAIN", "android")
        cls.work = tempfile.TemporaryDirectory(prefix="nova-packet-reception-")
        cls.addClassCleanup(cls.work.cleanup)
        work = pathlib.Path(cls.work.name)
        core = work / "core"
        if chain == "android":
            prepare_android_chain(source, core)
        elif chain in ("deck", "deck-pyrowave"):
            prepare_deck_chain(source, core, work, chain == "deck-pyrowave")
        else:
            raise AssertionError(f"unknown NOVA_PACKET_RECEPTION_CHAIN {chain}")
        (work / "CMakeLists.txt").write_text(f'''cmake_minimum_required(VERSION 3.16)
project(nova_packet_reception LANGUAGES C)
set(BUILD_SHARED_LIBS OFF)
add_subdirectory("{core.as_posix()}" core)
# Pinned ENet uses a null-based offset pattern UBSan reports; it predates this patch.
target_compile_options(enet PRIVATE -fno-sanitize=undefined)
target_compile_options(moonlight-common-c PRIVATE -fno-sanitize-recover=undefined)
target_compile_definitions(moonlight-common-c PRIVATE NOVA_PACKET_RECEPTION_TEST)
add_executable(reception "{(ROOT / 'tools/native_packet_reception_harness.c').as_posix()}")
target_compile_definitions(reception PRIVATE HAS_SOCKLEN_T NOVA_PACKET_RECEPTION_TEST)
target_compile_options(reception PRIVATE -fno-sanitize-recover=undefined)
target_include_directories(reception PRIVATE "{core.as_posix()}/enet/include" "{core.as_posix()}/reedsolomon")
target_link_libraries(reception PRIVATE moonlight-common-c enet)
''')
        configure = ["cmake", "-S", str(work), "-B", str(work / "build"), "-DCMAKE_BUILD_TYPE=Release",
                     "-DCMAKE_C_FLAGS=-fsanitize=address,undefined -fno-omit-frame-pointer"]
        if os.environ.get("OPENSSL_ROOT_DIR"):
            configure.append(f"-DOPENSSL_ROOT_DIR={os.environ['OPENSSL_ROOT_DIR']}")
        for command in (configure, ["cmake", "--build", str(work / "build"), "--parallel", "4"]):
            built = subprocess.run(command, capture_output=True, text=True, timeout=300)
            if built.returncode:
                raise AssertionError(built.stdout + built.stderr)
        cls.harness = work / "build/reception"

    def run_fixtures(self, command):
        ran = subprocess.run([str(self.harness), command], capture_output=True, text=True, timeout=60)
        output = ran.stdout + ran.stderr
        self.assertNotIn("runtime error", output, output)
        self.assertNotIn("ERROR: AddressSanitizer", output, output)
        results = dict(re.findall(r"^CASE (\S+) (PASS|FAIL)", ran.stdout, re.MULTILINE))
        # Every expected case must have run; a silently skipped case is a failure.
        self.assertEqual(sorted(results), sorted(EXPECTED_CASES[command]), output)
        self.assertEqual([name for name, verdict in results.items() if verdict != "PASS"], [], output)
        self.assertEqual(ran.returncode, 0, output)
        return ran.stdout

    def test_collector_serializer_parser_and_gates(self):
        output = self.run_fixtures("unit")
        bounds = dict(re.findall(r"(\w+)=(\d+)", re.search(r"^BOUNDS .*$", output, re.MULTILINE).group(0)))
        self.assertLessEqual(int(bounds["collector"]), 1024)
        self.assertEqual(int(bounds["sample"]), 16)
        self.assertEqual(int(bounds["payload"]), 212)

    def test_accepted_original_paths_in_both_video_modes(self):
        output = self.run_fixtures("session-paths")
        sessions = re.findall(r"^SESSION reports=(\d+) .*data=(\d+) parity=(\d+)", output, re.MULTILINE)
        self.assertEqual([(data, parity) for _, data, parity in sessions], [("19", "1"), ("19", "1")], output)

    def test_reconnect_starts_a_fresh_epoch(self):
        self.run_fixtures("session-reconnect")

    def test_clock_failure_and_regression_send_one_sentinel(self):
        self.run_fixtures("session-clock")

    def test_negotiation_gates(self):
        self.run_fixtures("session-gates")

    def test_spec_carries_the_serializer_golden_encodings(self):
        ran = subprocess.run([str(self.harness), "golden"], capture_output=True, text=True, timeout=20)
        self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
        golden = re.findall(r"^GOLDEN (\w+) ([0-9a-f]+)$", ran.stdout, re.MULTILINE)
        self.assertEqual(len(golden), 4)
        spec = SPEC.read_text(encoding="utf-8")
        for name, encoded in golden:
            self.assertIn(f"`{encoded}`", spec, f"spec golden vector {name} differs from the serializer")
        self.assertIn("**`0x7F10`**", spec)


class PacketReceptionPatchChainTest(unittest.TestCase):
    """The new patch applies after the existing ten and on the Deck's own chain."""

    def setUp(self):
        self.source = pathlib.Path(os.environ.get("NOVA_MOONLIGHT_SOURCE_DIR",
                                                  ROOT / "app/src/main/jni/moonlight-core/moonlight-common-c")).resolve()
        if not (self.source / "src/RtspConnection.c").is_file():
            raise AssertionError("initialize the pinned native submodules first")
        self.work = tempfile.TemporaryDirectory(prefix="nova-reception-chain-")
        self.addCleanup(self.work.cleanup)
        self.root = pathlib.Path(self.work.name)

    def test_android_chain_applies_in_order_and_adds_the_observer(self):
        prepare_android_chain(self.source, self.root / "android")
        self.assertIn("RtpvAddObservedPacket", (self.root / "android/src/VideoStream.c").read_text())
        self.assertIn("PYROWAVE_PROFILE_TOKEN", (self.root / "android/src/RtspConnection.c").read_text())
        self.assertIn("#define PACKET_RECEPTION_PTYPE 0x7F10", (self.root / "android/src/PacketReception.h").read_text())

    def test_deck_chain_and_optional_pyrowave_carry_the_observer(self):
        android_patched = "PYROWAVE_PROFILE_TOKEN" in (self.source / "src/RtspConnection.c").read_text()
        for name, pyrowave in (("deck", False), ("deck-pyrowave", True)):
            with self.subTest(name):
                if pyrowave and not shutil.which("patch"):
                    self.skipTest("patch is not installed")
                if pyrowave and android_patched:
                    # Pre-existing: the Deck PyroWave patch already conflicts with Android's own
                    # PyroWave patches 0001-0008 in a developer checkout; it needs a pristine core.
                    self.skipTest("Deck PyroWave needs a pristine core, not an Android-patched checkout")
                destination = self.root / name
                prepare_deck_chain(self.source, destination, self.root, pyrowave)
                self.assertTrue((destination / "src/PacketReception.c").is_file())
                self.assertIn("RtpvAddObservedPacket", (destination / "src/VideoStream.c").read_text())
                self.assertIn("MAX_RTSP_RESPONSE_SIZE", (destination / "src/RtspConnection.c").read_text())
                self.assertIn("PacketReceptionNoteHostOffer", (destination / "src/RtspConnection.c").read_text())

    def test_reception_patch_only_touches_its_declared_files(self):
        text = (PATCHES / RECEPTION_PATCH).read_text()
        touched = sorted(set(re.findall(r"^\+\+\+ b/(\S+)", text, re.MULTILINE)))
        self.assertEqual(touched, [
            "src/ControlStream.c", "src/PacketReception.c", "src/PacketReception.h", "src/Platform.c",
            "src/Platform.h", "src/RtpVideoQueue.c", "src/RtpVideoQueue.h", "src/RtspConnection.c",
            "src/SdpGenerator.c", "src/VideoStream.c",
        ])


if __name__ == "__main__":
    unittest.main(verbosity=2)
