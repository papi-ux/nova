"""Pin the page alignment of the prebuilt libraries, and the checker that reads it."""

import io
import pathlib
import struct
import unittest
import zipfile

from tools.check_native_page_alignment import (
    REQUIRED_ALIGNMENT,
    NotAnElf,
    failures_for_image,
    failures_for_path,
    load_segment_alignments,
)

ROOT = pathlib.Path(__file__).resolve().parents[1]
PREBUILT_DIR = ROOT / "app/src/main/jni/pyrowave-core"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")


def synthetic_elf(align, elf_class=2, little_endian=True):
    """A 64 or 32 bit ELF carrying one PT_LOAD with the given p_align, and nothing else.

    Hand built rather than checked in as a fixture, so the rejecting case costs no megabytes and
    states in code exactly which field decides it.
    """
    endian = "<" if little_endian else ">"
    if elf_class == 2:
        header_size, phentsize, phoff = 0x40, 0x38, 0x40
        ident = b"\x7fELF" + bytes([2, 1 if little_endian else 2, 1]) + bytes(9)
        header = bytearray(ident + struct.pack(endian + "HHI", 3, 0xB7, 1))
        header += struct.pack(endian + "QQQ", 0, phoff, 0)
        header += struct.pack(endian + "IHHHHHH", 0, header_size, phentsize, 1, 0, 0, 0)
        program = bytearray(struct.pack(endian + "II", 1, 5))
        program += struct.pack(endian + "QQQQQQ", 0, 0, 0, 0, 0, align)
    else:
        header_size, phentsize, phoff = 0x34, 0x20, 0x34
        ident = b"\x7fELF" + bytes([1, 1 if little_endian else 2, 1]) + bytes(9)
        header = bytearray(ident + struct.pack(endian + "HHI", 3, 0x28, 1))
        header += struct.pack(endian + "III", 0, phoff, 0)
        header += struct.pack(endian + "IHHHHHH", 0, header_size, phentsize, 1, 0, 0, 0)
        program = bytearray(struct.pack(endian + "I", 1))
        program += struct.pack(endian + "IIIIII", 0, 0, 0, 0, 0, 5)
        program += struct.pack(endian + "I", align)
    return bytes(header[:header_size]) + bytes(program[:phentsize])


class PrebuiltAlignmentTest(unittest.TestCase):
    """Android 15 brought devices with 16 KB pages, and a 4 KB aligned library will not load on one.

    v1.4.13-beta.3 shipped libpyrowave-shared.so at 0x1000 and its APKs could not be installed. These
    libraries are prebuilt, so ndk-build never links them and never applies its own 16 KB default: the
    alignment has to be asked for when they are built, and asserted here.
    """

    def test_every_prebuilt_is_16k_aligned(self):
        checked = 0
        for abi in ABIS:
            library = PREBUILT_DIR / abi / "libpyrowave-shared.so"
            if not library.is_file():
                continue
            checked += 1
            alignments = load_segment_alignments(library.read_bytes())
            self.assertTrue(alignments, "%s has no PT_LOAD segments" % abi)
            self.assertFalse(
                [a for a in alignments if a < REQUIRED_ALIGNMENT],
                "%s is aligned to %s. Rebuild it with "
                "-Wl,-z,max-page-size=16384,-z,common-page-size=16384; see %s/README.md"
                % (abi, ", ".join(hex(a) for a in sorted(set(alignments))), PREBUILT_DIR.name),
            )
        self.assertEqual(checked, len(ABIS), "a prebuilt ABI is missing from the tree")


class CheckerTest(unittest.TestCase):
    """The checker rejects on a number read out of a binary, so it is exactly the kind of rule that
    can silently accept everything. These cases pin both answers."""

    def test_rejects_4k_and_accepts_16k(self):
        self.assertTrue(failures_for_image("small.so", synthetic_elf(0x1000)))
        self.assertFalse(failures_for_image("big.so", synthetic_elf(0x4000)))

    def test_rejects_4k_on_32_bit_and_big_endian(self):
        self.assertTrue(failures_for_image("arm.so", synthetic_elf(0x1000, elf_class=1)))
        self.assertFalse(failures_for_image("arm.so", synthetic_elf(0x4000, elf_class=1)))
        self.assertTrue(failures_for_image("be.so", synthetic_elf(0x1000, little_endian=False)))

    def test_a_larger_alignment_is_acceptable(self):
        self.assertFalse(failures_for_image("huge.so", synthetic_elf(0x10000)))

    def test_refuses_a_file_that_is_not_an_elf(self):
        self.assertTrue(failures_for_image("text", b"this is not an ELF image at all, by any measure"))
        with self.assertRaises(NotAnElf):
            load_segment_alignments(b"short")

    def test_an_elf_with_no_load_segments_is_a_failure(self):
        endian = "<"
        ident = b"\x7fELF" + bytes([2, 1, 1]) + bytes(9)
        header = bytearray(ident + struct.pack(endian + "HHI", 3, 0xB7, 1))
        header += struct.pack(endian + "QQQ", 0, 0x40, 0)
        header += struct.pack(endian + "IHHHHHH", 0, 0x40, 0x38, 0, 0, 0, 0)
        self.assertTrue(failures_for_image("empty.so", bytes(header[:0x40])))

    def test_reads_every_native_library_inside_an_apk(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("lib/arm64-v8a/good.so", synthetic_elf(0x4000))
            archive.writestr("lib/arm64-v8a/bad.so", synthetic_elf(0x1000))
            archive.writestr("classes.dex", b"not a library")
        path = pathlib.Path(self.enterContext(__import__("tempfile").TemporaryDirectory())) / "t.apk"
        path.write_bytes(buffer.getvalue())

        problems = failures_for_path(str(path))
        self.assertEqual(len(problems), 1, problems)
        self.assertIn("bad.so", problems[0])

    def test_an_apk_with_no_native_libraries_is_reported(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("classes.dex", b"nothing native here")
        path = pathlib.Path(self.enterContext(__import__("tempfile").TemporaryDirectory())) / "e.apk"
        path.write_bytes(buffer.getvalue())
        self.assertTrue(failures_for_path(str(path)))


if __name__ == "__main__":
    unittest.main()
