"""Exercise production RTSP parsing/receiving, without a paired host or device.

NOVA_RTSP_HARNESS supplies a CMake-built harness. Otherwise build the actual
selected core with ASan/UBSan. NOVA_MOONLIGHT_SOURCE_DIR selects original source
for a retained RED run; neither mode edits the selected source.
"""
import contextlib
import errno
import os
import pathlib
import re
import shutil
import socket
import subprocess
import tempfile
import threading
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
LIMIT = 1024 * 1024


@contextlib.contextmanager
def server(reply):
    errors, requests = [], []
    stop = threading.Event()
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        listener.settimeout(0.1)

        def serve():
            while not stop.is_set():
                try:
                    connection, _ = listener.accept()
                except socket.timeout:
                    continue
                with connection:
                    connection.settimeout(3)
                    try:
                        data = b""
                        while b"\r\n\r\n" not in data:
                            chunk = connection.recv(8192)
                            if not chunk:
                                raise ValueError("request ended before its headers")
                            data += chunk
                            if len(data) > LIMIT:
                                raise ValueError("unexpected oversized fixture request")
                        header, body = data.split(b"\r\n\r\n", 1)
                        length = re.search(rb"(?im)^Content-Length: *(\d+)", header)
                        while length and len(body) < int(length[1]):
                            chunk = connection.recv(8192)
                            if not chunk:
                                raise ValueError("request ended before its payload")
                            body += chunk
                        requests.append(header.decode("ascii"))
                        connection.sendall(reply(requests[-1]))
                    except (BrokenPipeError, ConnectionResetError):
                        # A bounded receiver may close while the server is sending.
                        pass
                    except Exception as error:
                        errors.append(str(error))

        worker = threading.Thread(target=serve)
        worker.start()
        try:
            yield listener.getsockname()[1], requests
        finally:
            stop.set()
            worker.join(timeout=4)
            if worker.is_alive():
                raise AssertionError("fixture server did not stop")
            if errors:
                raise AssertionError(errors)


def sized_response(size):
    length = size
    while True:
        header = f"RTSP/1.0 200 OK\r\nContent-Length: {length}\r\n\r\n".encode()
        new_length = size - len(header)
        if new_length == length:
            return header + b"x" * length
        length = new_length


class MoonlightRtspTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        supplied = os.environ.get("NOVA_RTSP_HARNESS")
        if supplied:
            cls.harness = pathlib.Path(supplied)
            if not cls.harness.is_file():
                raise AssertionError("declared RTSP harness is missing")
            return
        if not shutil.which("cmake"):
            raise AssertionError("cmake is required for the RTSP regression")
        source = pathlib.Path(os.environ.get("NOVA_MOONLIGHT_SOURCE_DIR", ROOT / "app/src/main/jni/moonlight-core/moonlight-common-c")).resolve()
        if not (source / "src/RtspConnection.c").is_file():
            raise AssertionError("initialize the pinned native submodules first")
        cls.work = tempfile.TemporaryDirectory(prefix="nova-rtsp-")
        cls.addClassCleanup(cls.work.cleanup)
        work = pathlib.Path(cls.work.name)
        (work / "CMakeLists.txt").write_text(f'''cmake_minimum_required(VERSION 3.16)
project(nova_rtsp_regression LANGUAGES C)
set(BUILD_SHARED_LIBS OFF)
add_subdirectory("{source.as_posix()}" core)
add_executable(rtsp "{(ROOT / 'tools/moonlight_rtsp_harness.c').as_posix()}")
target_compile_definitions(rtsp PRIVATE NOVA_RTSP_SOURCE="{source.as_posix()}/src/RtspConnection.c" HAS_SOCKLEN_T NDEBUG)
target_include_directories(rtsp PRIVATE "{source.as_posix()}/enet/include" "{source.as_posix()}/reedsolomon")
target_link_libraries(rtsp PRIVATE moonlight-common-c)
''')
        flags = "-fsanitize=address,undefined -fno-omit-frame-pointer"
        for command in (["cmake", "-S", str(work), "-B", str(work / "build"), "-DCMAKE_BUILD_TYPE=Release", f"-DCMAKE_C_FLAGS={flags}"],
                        ["cmake", "--build", str(work / "build"), "--parallel", "2"]):
            built = subprocess.run(command, capture_output=True, text=True, timeout=180)
            if built.returncode:
                raise AssertionError(built.stdout + built.stderr)
        cls.harness = work / "build/rtsp"

    def run_fixture(self, *arguments):
        ran = subprocess.run([str(self.harness), *map(str, arguments)], capture_output=True, text=True, timeout=20)
        self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
        values = {key: int(value) for key, value in re.findall(r"(\w+)=(-?\d+)", ran.stdout)}
        self.assertIn("ret", values, ran.stdout + ran.stderr)
        self.assertLessEqual(values["maxAllocation"], LIMIT, ran.stdout + ran.stderr)
        return values

    def tcp(self, size):
        response = sized_response(size)
        with server(lambda request: response) as (port, requests):
            result = self.run_fixture("tcp", port)
        self.assertEqual(len(requests), 1)
        return result

    def test_tcp_small_response(self):
        self.assertEqual(self.tcp(256)["ret"], 1)

    def test_tcp_below_limit(self):
        self.assertEqual(self.tcp(LIMIT - 1)["ret"], 1)

    def test_tcp_at_limit_refused_without_growing_buffer(self):
        result = self.tcp(LIMIT)
        self.assertEqual(result["ret"], 0)
        self.assertEqual(result["error"], errno.EMSGSIZE)

    def test_tcp_above_limit_refused_without_growing_buffer(self):
        result = self.tcp(LIMIT + 16384)
        self.assertEqual(result["ret"], 0)
        self.assertEqual(result["error"], errno.EMSGSIZE)

    def enet(self, first, second, expected):
        result = self.run_fixture("enet", first, second)
        self.assertEqual(result["ret"], expected)
        self.assertEqual(result["destroyed"], result["delivered"])

    def test_enet_small_response(self):
        self.enet(128, 64, 1)

    def test_enet_at_limit(self):
        self.enet(LIMIT, 0, 1)

    def test_enet_oversized_first_packet(self):
        self.enet(LIMIT + 1, 0, 0)

    def test_enet_at_combined_limit(self):
        self.enet(128, LIMIT - 128, 1)

    def test_enet_oversized_combined_response(self):
        self.enet(128, LIMIT - 127, 0)

    def handshake(self, session, expected):
        def reply(request):
            command, target, _ = request.splitlines()[0].split(" ")
            headers = ""
            body = b""
            if command == "DESCRIBE":
                body = b"v=0\r\na=rtpmap:96 H264/90000\r\n"
            if command == "SETUP" and target == "streamid=audio/0/0" and session is not None:
                headers = f"Session: {session}\r\n"
            if target == "streamid=video/0/0":
                received = re.search(r"(?m)^Session: ([^\r\n]*)", request)
                if received is None or received[1] != "DEADBEEFCAFE":
                    raise AssertionError("valid Session parameters were not stripped")
            return f"RTSP/1.0 200 OK\r\n{headers}Content-Length: {len(body)}\r\n\r\n".encode() + body

        with server(reply) as (port, requests):
            result = self.run_fixture("handshake", port)
        self.assertEqual(result["ret"], expected)
        self.assertEqual(len(requests), 7 if expected == 0 else 3)

    def test_valid_session(self):
        self.handshake("DEADBEEFCAFE", 0)

    def test_valid_session_with_parameters(self):
        self.handshake("DEADBEEFCAFE;timeout = 90", 0)

    def test_missing_session(self):
        self.handshake(None, -1)

    def test_empty_session(self):
        self.handshake("", -1)

    def test_delimiter_only_session(self):
        self.handshake(";;;", -1)


class NativePatchBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory(prefix="nova-native-patches-")
        self.addCleanup(self.work.cleanup)
        self.repo = pathlib.Path(self.work.name)
        relative = pathlib.Path("app/src/main/jni/moonlight-core")
        self.source = self.repo / relative / "moonlight-common-c"
        self.patches = self.repo / relative / "patches"
        self.patches.parent.mkdir(parents=True)
        shutil.copytree(ROOT / relative / "patches", self.patches)
        original = pathlib.Path(os.environ.get("NOVA_MOONLIGHT_SOURCE_DIR", ROOT / relative / "moonlight-common-c")).resolve()
        self.git(self.repo, "init", "-q")
        self.git(self.repo, "clone", "--shared", "--quiet", "--no-checkout", str(original), str(self.source))
        self.pin = self.git(ROOT, "rev-parse", "HEAD:" + (relative / "moonlight-common-c").as_posix()).stdout.strip()
        self.git(self.source, "checkout", "--quiet", "--detach", self.pin)
        self.git(self.repo, "update-index", "--add", "--cacheinfo", "160000", self.pin, (relative / "moonlight-common-c").as_posix())
        self.git(self.repo, "commit", "--quiet", "-m", "fixture core pin")

    def git(self, path, *arguments):
        ran = subprocess.run(["git", "-c", "user.name=Nova", "-c", "user.email=nova@papi-ux.com", "-C", str(path), *arguments], capture_output=True, text=True, timeout=20)
        self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
        return ran

    def bootstrap(self, success=True):
        ran = subprocess.run(["bash", str(ROOT / "tools/apply-native-patches.sh")], cwd=self.repo, capture_output=True, text=True, timeout=20)
        if success:
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
        else:
            self.assertNotEqual(ran.returncode, 0, ran.stdout + ran.stderr)
        return ran

    def assert_patched(self):
        source = (self.source / "src/RtspConnection.c").read_text()
        self.assertIn("PYROWAVE_PROFILE_TOKEN", source)
        self.assertIn("MAX_RTSP_RESPONSE_SIZE", source)
        self.assertIn("sessionToken == NULL", source)
        self.assertEqual(self.git(self.source, "rev-list", "--count", self.pin + "..HEAD").stdout.strip(), "10")

    def test_fresh_and_repeat_bootstrap(self):
        self.bootstrap()
        self.assert_patched()
        head = self.git(self.source, "rev-parse", "HEAD").stdout
        self.bootstrap()
        self.assertEqual(self.git(self.source, "rev-parse", "HEAD").stdout, head)

    def apply_old_eight(self):
        patches = sorted(self.patches.glob("*.patch"))[:8]
        self.git(self.source, "am", *map(str, patches))

    def test_upgrade_preserves_old_eight_commits(self):
        self.apply_old_eight()
        old = self.git(self.source, "rev-parse", "HEAD").stdout.strip()
        self.bootstrap()
        self.assert_patched()
        self.git(self.source, "merge-base", "--is-ancestor", old, "HEAD")

    def test_conflicting_developer_change_is_preserved(self):
        self.apply_old_eight()
        path = self.source / "src/RtspConnection.c"
        changed = path.read_bytes() + b"\n/* developer fixture change */\n"
        path.write_bytes(changed)
        old = self.git(self.source, "rev-parse", "HEAD").stdout
        self.bootstrap(success=False)
        self.assertEqual(path.read_bytes(), changed)
        self.assertEqual(self.git(self.source, "rev-parse", "HEAD").stdout, old)

    def prepare(self, destination, success=True):
        script = self.repo / "prepare.cmake"
        script.write_text(f'''cmake_minimum_required(VERSION 3.24)
include("{(ROOT / 'clients/deck/cmake/NovaHardenRtsp.cmake').as_posix()}")
nova_deck_prepare_rtsp("{self.source.as_posix()}" "{destination.as_posix()}")
''')
        ran = subprocess.run(["cmake", "-P", str(script)], capture_output=True, text=True, timeout=20)
        if success:
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
        else:
            self.assertNotEqual(ran.returncode, 0, ran.stdout + ran.stderr)
        return ran

    def test_linux_preparation_and_already_applied_source(self):
        destination = self.repo / "prepared"
        before = self.git(self.source, "status", "--porcelain").stdout
        self.prepare(destination)
        source = (destination / "src/RtspConnection.c").read_bytes()
        self.assertTrue(b"MAX_RTSP_RESPONSE_SIZE" in source, "RTSP bound is absent from the generated source")
        self.assertEqual(self.git(self.source, "status", "--porcelain").stdout, before)
        self.bootstrap()
        # Force the timestamp collision that a fast reconfigure can encounter.
        stamp = (destination / "src/RtspConnection.c").stat().st_mtime_ns
        os.utime(self.source / "src/RtspConnection.c", ns=(stamp, stamp))
        self.prepare(destination)
        self.assertEqual((destination / "src/RtspConnection.c").read_bytes(), (self.source / "src/RtspConnection.c").read_bytes())
        self.assertEqual(self.git(self.source, "status", "--porcelain").stdout, before)

    def test_partial_linux_patch_is_refused(self):
        self.bootstrap()
        source = self.source / "src/RtspConnection.c"
        changed = source.read_bytes().replace(b"#define MAX_RTSP_RESPONSE_SIZE (1024 * 1024)", b"#define MAX_RTSP_RESPONSE_SIZE (4 * 1024 * 1024)")
        source.write_bytes(changed)
        self.prepare(self.repo / "partial", success=False)
        self.assertEqual(source.read_bytes(), changed)


if __name__ == "__main__":
    unittest.main(verbosity=2)
