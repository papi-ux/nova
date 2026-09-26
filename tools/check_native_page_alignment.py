#!/usr/bin/env python3
"""Refuse a native library that a 16 KB page device cannot load.

Android 15 introduced devices with 16 KB memory pages, and a shared library whose LOAD segments are
aligned to 4 KB will not load on one. An APK carrying such a library fails to install outright.

Nothing else in this tree catches it. `apksigner` verifies the signature, the checksum sidecars match,
`aapt2 dump badging` reads the manifest, and even `zipalign -c -P 16` passes, because zipalign checks
where entries sit inside the zip rather than how the ELF inside them is aligned. That combination let a
4 KB aligned library ship in v1.4.13-beta.3 and made its APKs uninstallable.

ndk-build aligns what it links, so the libraries it compiles are already right. The gap is the prebuilt
PyroWave library, which Android.mk takes as PREBUILT_SHARED_LIBRARY and so never links.

Usage:
  check_native_page_alignment.py <file.so|file.apk> [...]
"""

import struct
import sys
import zipfile

REQUIRED_ALIGNMENT = 0x4000  # 16 KB

PT_LOAD = 1
ELF_MAGIC = b"\x7fELF"


class NotAnElf(Exception):
    pass


def load_segment_alignments(data: bytes) -> list:
    """Every PT_LOAD p_align in an ELF image, read from its program headers."""
    if len(data) < 64 or data[:4] != ELF_MAGIC:
        raise NotAnElf("not an ELF image")

    elf_class = data[4]
    little_endian = data[5] == 1
    endian = "<" if little_endian else ">"

    if elf_class == 2:  # 64 bit
        e_phoff, = struct.unpack_from(endian + "Q", data, 0x20)
        e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 0x36)
        type_offset, align_offset, word = 0x00, 0x30, "Q"
    elif elf_class == 1:  # 32 bit
        e_phoff, = struct.unpack_from(endian + "I", data, 0x1C)
        e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 0x2A)
        type_offset, align_offset, word = 0x00, 0x1C, "I"
    else:
        raise NotAnElf("unknown ELF class %d" % elf_class)

    alignments = []
    for index in range(e_phnum):
        base = e_phoff + index * e_phentsize
        if base + e_phentsize > len(data):
            raise NotAnElf("program header %d runs past the end of the image" % index)
        p_type, = struct.unpack_from(endian + "I", data, base + type_offset)
        if p_type != PT_LOAD:
            continue
        p_align, = struct.unpack_from(endian + word, data, base + align_offset)
        alignments.append(p_align)
    return alignments


def failures_for_image(name: str, data: bytes) -> list:
    """The reasons this image would not load on a 16 KB page device."""
    try:
        alignments = load_segment_alignments(data)
    except NotAnElf as exc:
        return ["%s: %s" % (name, exc)]
    if not alignments:
        return ["%s: no PT_LOAD segments" % name]
    bad = sorted({a for a in alignments if a < REQUIRED_ALIGNMENT})
    if bad:
        return ["%s: LOAD alignment %s, needs at least 0x%x. Link it with "
                "-Wl,-z,max-page-size=16384,-z,common-page-size=16384"
                % (name, ", ".join(hex(a) for a in bad), REQUIRED_ALIGNMENT)]
    return []


def failures_for_path(path: str) -> list:
    """Checks one .so, or every native library inside one .apk."""
    if path.endswith(".apk"):
        found = []
        with zipfile.ZipFile(path) as archive:
            members = [n for n in archive.namelist()
                       if n.startswith("lib/") and n.endswith(".so")]
            if not members:
                return ["%s: contains no native libraries" % path]
            for member in members:
                found += failures_for_image("%s!%s" % (path, member), archive.read(member))
        return found
    with open(path, "rb") as handle:
        return failures_for_image(path, handle.read())


def main(argv: list) -> int:
    if not argv:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    problems = []
    for path in argv:
        problems += failures_for_path(path)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print("\n%d native library problem(s)" % len(problems), file=sys.stderr)
        return 1
    print("every native library is aligned to at least 0x%x" % REQUIRED_ALIGNMENT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
