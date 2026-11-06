"""Build/run actual selected core source-time plumbing; preserve runs with --work-dir.

NOVA_MOONLIGHT_SOURCE_DIR selects the immutable source. --chain raw is the RED
baseline; android/deck/deck-pyrowave use the production patch preparation.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from test_native_packet_reception import prepare_android_chain, prepare_deck_chain


class MissingCMakeBuildTool(RuntimeError):
    """Neither supported CMake generator has a build tool on PATH."""


def select_cmake_generator():
    if shutil.which("ninja"):
        return "Ninja"
    if shutil.which("make"):
        return "Unix Makefiles"
    raise MissingCMakeBuildTool(
        "SOURCE_TIME_BUILD_TOOL_MISSING: install ninja (Ninja) or make "
        "(Unix Makefiles) on PATH")


def run(work, source, chain):
    generator = select_cmake_generator()
    core = source
    if chain == "android":
        core = work / "source"
        prepare_android_chain(source, core)
    elif chain != "raw":
        core = work / "source"
        prepare_deck_chain(source, core, work, chain == "deck-pyrowave")
    (work / "CMakeLists.txt").write_text(f'''cmake_minimum_required(VERSION 3.16)
project(nova_source_time LANGUAGES C)
set(BUILD_SHARED_LIBS OFF)
add_subdirectory("{core.as_posix()}" core)
add_executable(source_time "{(ROOT / 'tools/source_time_harness.c').as_posix()}")
target_compile_definitions(source_time PRIVATE HAS_SOCKLEN_T NDEBUG)
target_include_directories(source_time PRIVATE "{core.as_posix()}/enet/include" "{core.as_posix()}/reedsolomon")
target_link_libraries(source_time PRIVATE moonlight-common-c)
''')
    # cmake --build uses the generator cached by this explicit configure.
    commands = [["cmake", "-S", str(work), "-B", str(work / "build"), "-G", generator, "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_C_COMPILER=clang"],
                ["cmake", "--build", str(work / "build"), "--parallel", "2"],
                [str(work / "build/source_time")]]
    if os.environ.get("OPENSSL_ROOT_DIR"):
        commands[0].append("-DOPENSSL_ROOT_DIR=" + os.environ["OPENSSL_ROOT_DIR"])
    for i, command in enumerate(commands):
        print("COMMAND", command, flush=True)
        result = subprocess.run(command, capture_output=True, text=True, timeout=180)
        (work / f"{i:02d}.stdout").write_text(result.stdout)
        (work / f"{i:02d}.stderr").write_text(result.stderr)
        (work / f"{i:02d}.exit").write_text(str(result.returncode) + "\n")
        print(result.stdout, end=""); print(result.stderr, end="", file=sys.stderr)
        if result.returncode: return result.returncode
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chain", choices=("raw", "android", "deck", "deck-pyrowave"), default="android")
    parser.add_argument("--work-dir", type=Path)
    args = parser.parse_args()
    source = Path(os.environ.get("NOVA_MOONLIGHT_SOURCE_DIR", ROOT / "app/src/main/jni/moonlight-core/moonlight-common-c")).resolve()
    try:
        if args.work_dir:
            args.work_dir.mkdir(parents=True, exist_ok=False)
            raise SystemExit(run(args.work_dir.resolve(), source, args.chain))
        with tempfile.TemporaryDirectory(prefix="nova-source-time-") as temporary:
            raise SystemExit(run(Path(temporary), source, args.chain))
    except MissingCMakeBuildTool as error:
        print(error, file=sys.stderr)
        raise SystemExit(2)
