#!/usr/bin/env python3
"""Refuse a native library that a 16 KB page device cannot load.

Android 15 introduced devices with 16 KB memory pages, and a shared library whose LOAD segments are
aligned to 4 KB will not load on one. An APK carrying such a library fails to install outright.

Nothing else in this tree catches it. `apksigner` verifies the signature, the checksum sidecars match,
`aapt2 dump badging` reads the manifest, and even `zipalign -c -P 16` passes, because zipalign checks
where entries sit inside the zip rather than how the ELF inside them is aligned. That combination let a
4 KB aligned library ship in v1.4.13-beta.3 and made its APKs uninstallable.

ndk-build aligns what it links for 64 bit, so the libraries it compiles for arm64-v8a and x86_64 are
already right. The gap is the prebuilt PyroWave library, which Android.mk takes as
PREBUILT_SHARED_LIBRARY and so never links.

The requirement is 64 bit only, and this refuses nothing for 32 bit. A device with 16 KB pages runs no
32 bit code at all, so armeabi-v7a can never meet one, and ndk-build links it at 4 KB accordingly.
Demanding 16 KB there would fail every build for a configuration that cannot exist, and would cost
padding in the APK to fix. 32 bit images are reported as skipped rather than passed, so the exemption
is visible and a missing 64 bit library cannot hide behind it.

Usage:
  check_native_page_alignment.py <file.so|file.apk> [...]
"""

import struct
import sys
import zipfile

REQUIRED_ALIGNMENT = 0x4000  # 16 KB

PT_LOAD = 1
ELF_MAGIC = b"\x7fELF"
ELFCLASS32 = 1
ELFCLASS64 = 2


class NotAnElf(Exception):
    pass


def read_elf(data: bytes) -> tuple:
    """(elf_class, [every PT_LOAD p_align]) for an ELF image, read from its program headers."""
    if len(data) < 64 or data[:4] != ELF_MAGIC:
        raise NotAnElf("not an ELF image")

    elf_class = data[4]
    little_endian = data[5] == 1
    endian = "<" if little_endian else ">"

    if elf_class == ELFCLASS64:
        e_phoff, = struct.unpack_from(endian + "Q", data, 0x20)
        e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 0x36)
        type_offset, align_offset, word = 0x00, 0x30, "Q"
    elif elf_class == ELFCLASS32:
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
    return elf_class, alignments


def failures_for_image(name: str, data: bytes) -> tuple:
    """(failures, skip_note) for one image. A 32 bit image is skipped, never failed."""
    try:
        elf_class, alignments = read_elf(data)
    except NotAnElf as exc:
        return ["%s: %s" % (name, exc)], None
    if elf_class == ELFCLASS32:
        return [], "%s: 32 bit, exempt because no 16 KB page device runs 32 bit code" % name
    if not alignments:
        return ["%s: no PT_LOAD segments" % name], None
    bad = sorted({a for a in alignments if a < REQUIRED_ALIGNMENT})
    if bad:
        return ["%s: LOAD alignment %s, needs at least 0x%x. Link it with "
                "-Wl,-z,max-page-size=16384,-z,common-page-size=16384"
                % (name, ", ".join(hex(a) for a in bad), REQUIRED_ALIGNMENT)], None
    return [], None


def failures_for_path(path: str) -> tuple:
    """(failures, skipped) for one .so, or for every native library inside one .apk."""
    if path.endswith(".apk"):
        found, skipped = [], []
        with zipfile.ZipFile(path) as archive:
            members = [n for n in archive.namelist()
                       if n.startswith("lib/") and n.endswith(".so")]
            if not members:
                return ["%s: contains no native libraries" % path], []
            for member in members:
                problems, note = failures_for_image("%s!%s" % (path, member), archive.read(member))
                found += problems
                if note:
                    skipped.append(note)
        return found, skipped
    with open(path, "rb") as handle:
        problems, note = failures_for_image(path, handle.read())
    return problems, ([note] if note else [])


def main(argv: list) -> int:
    if not argv:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    problems, skipped = [], []
    for path in argv:
        found, notes = failures_for_path(path)
        problems += found
        skipped += notes
    # Printed even on success: the exemption has to be visible, or a 64 bit library that went missing
    # from the APK would read as a clean run.
    for note in skipped:
        print("skipped %s" % note)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print("\n%d native library problem(s)" % len(problems), file=sys.stderr)
        return 1
    print("every 64 bit native library is aligned to at least 0x%x (%d skipped as 32 bit)"
          % (REQUIRED_ALIGNMENT, len(skipped)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
