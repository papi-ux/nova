#!/usr/bin/env python3
"""Vendor compiled SPIR-V as a header, so building this app needs no shader compiler.

With no arguments it writes the two present headers from /tmp, exactly as it always has. The SGSR 1
header (#304) is written by name, and records the source it came from and the compiler that made it:

    python3 tools/spv_to_header.py sgsr1 /tmp/sgsr1.frag.spv "<compiler identity>"
"""

from pathlib import Path
import hashlib
import struct
import sys

SHADERS = Path(__file__).resolve().parent.parent / "app/src/main/jni/pyrowave-core/shaders"


def words_of(spv_path):
    data = Path(spv_path).read_bytes()
    assert len(data) % 4 == 0, len(data)
    words = struct.unpack("<%dI" % (len(data) // 4), data)
    assert words and words[0] == 0x07230203, "not SPIR-V"
    return words


def body_of(words):
    lines = []
    for i in range(0, len(words), 8):
        lines.append("    " + " ".join("0x%08xu," % w for w in words[i:i + 8]))
    return "\n".join(lines)


def emit(symbol, glsl_name, spv_path, out_name, out_dir=SHADERS):
    words = words_of(spv_path)
    body = body_of(words)

    (Path(out_dir) / out_name).write_text(
        "// Generated from %s. Do not edit.\n"
        "//\n"
        "// Regenerate with:\n"
        "//   glslc -O shaders/%s -o /tmp/%s.spv\n"
        "//   python3 tools/spv_to_header.py\n"
        "//\n"
        "// Vendored rather than compiled during the build, so building this app needs no shader\n"
        "// compiler and no CI job can break on one. The shader is frozen: three planes in, one\n"
        "// colour out, and it changes only if the colour handling does.\n"
        "#pragma once\n\n"
        "#include <cstdint>\n\n"
        "static const uint32_t %s[] = {\n%s\n};\n" % (glsl_name, glsl_name, glsl_name, symbol, body),
        encoding="utf-8")
    print("wrote", out_name, len(words), "words")


def emit_sgsr1(spv_path, compiler, out_dir=SHADERS):
    """The SGSR 1 header, with the source's sha256 and the compiler's identity in it."""
    assert compiler and "\n" not in compiler, "the compiler identity is one line"
    words = words_of(spv_path)
    source = hashlib.sha256((SHADERS / "sgsr1.frag").read_bytes()).hexdigest()
    payload = hashlib.sha256(Path(spv_path).read_bytes()).hexdigest()

    (Path(out_dir) / "sgsr1_frag_spv.h").write_text(
        "// Generated from sgsr1.frag. Do not edit.\n"
        "//\n"
        "// Source sha256: %s\n"
        "// Compiler: %s\n"
        "// SPIR-V sha256: %s (%d words)\n"
        "//\n"
        "// Regenerate with that compiler only:\n"
        "//   glslc -O shaders/sgsr1.frag -o /tmp/sgsr1.frag.spv\n"
        "//   python3 tools/spv_to_header.py sgsr1 /tmp/sgsr1.frag.spv \"<compiler identity>\"\n"
        "//\n"
        "// SGSR 1 (Snapdragon Game Super Resolution 1), BSD-3-Clause, Qualcomm Innovation Center; see\n"
        "// LICENSE-SGSR1. Vendored like the present shaders, so building this app needs no shader\n"
        "// compiler. The default present shaders and their headers are not regenerated with it.\n"
        "#pragma once\n\n"
        "#include <cstdint>\n\n"
        "static const uint32_t sgsr1_frag_spv[] = {\n%s\n};\n" % (source, compiler, payload, len(words), body_of(words)),
        encoding="utf-8")
    print("wrote sgsr1_frag_spv.h", len(words), "words")


if __name__ == "__main__":
    if len(sys.argv) == 1:
        emit("present_vert_spv", "present.vert", "/tmp/present.vert.spv", "present_vert_spv.h")
        emit("present_frag_spv", "present.frag", "/tmp/present.frag.spv", "present_frag_spv.h")
    elif len(sys.argv) == 4 and sys.argv[1] == "sgsr1":
        emit_sgsr1(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)
