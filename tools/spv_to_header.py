#!/usr/bin/env python3
"""Vendor compiled SPIR-V as a header, so building this app needs no shader compiler."""

from pathlib import Path
import struct
import sys

SHADERS = Path(__file__).resolve().parent.parent / "app/src/main/jni/pyrowave-core/shaders"


def emit(symbol, glsl_name, spv_path, out_name):
    data = Path(spv_path).read_bytes()
    assert len(data) % 4 == 0, len(data)
    words = struct.unpack("<%dI" % (len(data) // 4), data)

    lines = []
    for i in range(0, len(words), 8):
        lines.append("    " + " ".join("0x%08xu," % w for w in words[i:i + 8]))
    body = "\n".join(lines)

    (SHADERS / out_name).write_text(
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


emit("present_vert_spv", "present.vert", "/tmp/present.vert.spv", "present_vert_spv.h")
emit("present_frag_spv", "present.frag", "/tmp/present.frag.spv", "present_frag_spv.h")
