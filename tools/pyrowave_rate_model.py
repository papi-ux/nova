#!/usr/bin/env python3
"""Port PyroWave's bitrate model into Nova, and print the reference numbers the port is tested against.

PyroWave's author publishes an objective model of the bitrate the codec needs, regressed from a sweep
over four game clips, as a generated C header: eval-results/pyrowave_regression_results.h in
Themaister/pyrowave. This script reads that header from a pyrowave checkout and writes two files:

  app/src/main/java/com/papi/nova/binding/video/PyroWaveRateTable.kt
      every coefficient in the header, as Kotlin, for PyroWaveRateModel to evaluate;
  app/src/test/resources/pyrowave-rate-reference.csv
      what upstream's own C function answers over a grid, from tools/pyrowave_rate_fixture.c
      compiled against the same header, for PyroWaveRateModelTest to compare the port with.

The header is pinned by sha256 to the revision Nova's prebuilt codec is built from. A different header
is refused rather than ported, because the commit recorded beside the table would then be a lie: move
UPSTREAM_COMMIT and HEADER_SHA256 together, on purpose.

    python3 tools/pyrowave_rate_model.py --pyrowave <checkout>          # write both files
    python3 tools/pyrowave_rate_model.py --pyrowave <checkout> --check  # fail if either is stale
"""

import argparse
import hashlib
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
TABLE = REPO_ROOT / "app/src/main/java/com/papi/nova/binding/video/PyroWaveRateTable.kt"
FIXTURE = REPO_ROOT / "app/src/test/resources/pyrowave-rate-reference.csv"
FIXTURE_PROGRAM = REPO_ROOT / "tools/pyrowave_rate_fixture.c"

UPSTREAM = "https://github.com/Themaister/pyrowave"
UPSTREAM_COMMIT = "186f0393b77f7755953b5ecde994bb1cec2e4155"
HEADER_PATH = "eval-results/pyrowave_regression_results.h"
HEADER_SHA256 = "384e2ca2901318dd0e82a4bad44c7be902984382e87127d58c751b5ed95246a3"

HEIGHT_FACTORS = 16
COEFFICIENTS = 8

# The evaluation PyroWaveRateModel reproduces. The sha256 pin already fixes the header, so these are
# here to fail with a reason rather than a hash if a future pin moves to a header that evaluates
# differently.
EXPECTED_EVALUATION = (
    "pixel_non_linear = sqrt(pixel_non_linear * 1e-6) - 2.0;",
    "estimate += power_chain * result->poly_coefficients[coeff];",
    "power_chain *= pixel_non_linear;",
    "return estimate * 8e-3 * fps;",
)

BUCKET = re.compile(r"^\t\{ (\d+), (\d+), ([01]), \{ ([^{}]*) \} \},$")
NUMBER = re.compile(r"^-?\d+(\.\d+)?([eE][-+]?\d+)?$")


def fail(message):
    print(f"pyrowave_rate_model: {message}", file=sys.stderr)
    sys.exit(1)


def define(text, name):
    match = re.search(rf"^#define {name} (.+)$", text, re.MULTILINE)
    if match is None:
        fail(f"the header defines no {name}")
    return match.group(1).strip()


def read_header(checkout):
    header = checkout / HEADER_PATH
    data = header.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != HEADER_SHA256:
        fail(
            f"{header} has sha256 {digest}, not the pinned {HEADER_SHA256} of {UPSTREAM_COMMIT}. "
            "Check out that commit, or move UPSTREAM_COMMIT and HEADER_SHA256 together on purpose."
        )
    text = data.decode("utf-8")
    for line in EXPECTED_EVALUATION:
        if line not in text:
            fail(f"the header no longer evaluates its polynomial as expected: missing {line!r}")

    for index in range(HEIGHT_FACTORS):
        h = 1.0 + index / 8.0
        # Upstream truncates the name (2.875 is spelled 2_87), so compare what the name says.
        name = f"PYROWAVE_HEIGHT_FACTOR_{int(h)}_{int(h * 100) % 100:02d}"
        if not re.search(rf"^\t{name} = {index},?$", text, re.MULTILINE):
            fail(f"the header does not map {name} to {index}")

    min_psnr = int(define(text, "PYROWAVE_REGRESSION_MIN_PSNR_HVS_M_H"))
    max_psnr = int(define(text, "PYROWAVE_REGRESSION_MAX_PSNR_HVS_M_H"))
    min_pixels = define(text, "PYROWAVE_REGRESSION_MIN_PIXELS")
    max_pixels = define(text, "PYROWAVE_REGRESSION_MAX_PIXELS")
    for value in (min_pixels, max_pixels):
        if not re.fullmatch(r"\(\d+ \* \d+\)", value):
            fail(f"a pixel bound is no longer a product of two numbers: {value}")

    buckets = {}
    for line in text.splitlines():
        match = BUCKET.match(line)
        if match is None:
            continue
        psnr, factor, chroma444 = (int(match.group(i)) for i in range(1, 4))
        numbers = [token.strip() for token in match.group(4).split(",")]
        if len(numbers) != COEFFICIENTS or not all(NUMBER.match(n) for n in numbers):
            fail(f"bucket {psnr}, {factor}, {chroma444} does not hold eight plain numbers")
        key = (psnr, factor, chroma444)
        if key in buckets:
            fail(f"bucket {key} appears twice")
        buckets[key] = numbers

    expected = {
        (psnr, factor, chroma444)
        for psnr in range(min_psnr, max_psnr + 1)
        for factor in range(HEIGHT_FACTORS)
        for chroma444 in (0, 1)
    }
    if set(buckets) != expected:
        missing = sorted(expected - set(buckets))
        extra = sorted(set(buckets) - expected)
        fail(f"the header's buckets are not the full table: missing {missing[:4]}, extra {extra[:4]}")

    licence = (checkout / "LICENSE").read_text(encoding="utf-8").strip()
    if "Permission is hereby granted, free of charge" not in licence:
        fail("the checkout's LICENSE is not the MIT licence this port relies on")

    return {
        "buckets": buckets,
        "min_psnr": min_psnr,
        "max_psnr": max_psnr,
        "min_pixels": min_pixels.strip("()"),
        "max_pixels": max_pixels.strip("()"),
        "licence": licence,
    }


def kotlin_double(token):
    # Kotlin reads 655 as an Int; a double literal needs a point or an exponent. The digits are
    # otherwise copied as upstream printed them, so the compiler rounds the same decimal text the C
    # compiler did and lands on the same double.
    return token if ("." in token or "e" in token or "E" in token) else token + ".0"


def family_name(factor, chroma444):
    return f"heightFactor{factor:02d}Chroma{'444' if chroma444 else '420'}"


def render_table(model):
    licence = "\n".join(("// " + line).rstrip() for line in model["licence"].splitlines())
    families = []
    for chroma444 in (0, 1):
        for factor in range(HEIGHT_FACTORS):
            families.append((factor, chroma444))

    out = []
    out.append("// Generated by tools/pyrowave_rate_model.py. Do not edit: rerun the script against the")
    out.append("// upstream header instead.")
    out.append("//")
    out.append(f"// Source: {HEADER_PATH} in {UPSTREAM}")
    out.append(f"// Upstream commit: {UPSTREAM_COMMIT}")
    out.append(f"// Header sha256: {HEADER_SHA256}")
    out.append("//")
    out.append("// The coefficients are PyroWave's, fitted by its author's eval-results/regress.py, and are")
    out.append("// used here under PyroWave's MIT licence:")
    out.append("//")
    out.append(licence)
    out.append("")
    out.append("package com.papi.nova.binding.video")
    out.append("")
    out.append("/**")
    out.append(" * Upstream's bitrate regression: one polynomial per quality, viewing distance and chroma.")
    out.append(" *")
    out.append(" * Each bucket is the eight coefficients c0 to c7 of a polynomial in x = sqrt(pixels / 1e6) - 2")
    out.append(" * whose value is kilobytes per frame. [PyroWaveRateModel] evaluates it; this only holds the")
    out.append(" * numbers, exactly as the header prints them.")
    out.append(" */")
    out.append("internal object PyroWaveRateTable {")
    out.append(f'    const val UPSTREAM_COMMIT = "{UPSTREAM_COMMIT}"')
    out.append(f'    const val HEADER_SHA256 = "{HEADER_SHA256}"')
    out.append("")
    out.append(f"    const val MIN_PSNR = {model['min_psnr']}")
    out.append(f"    const val MAX_PSNR = {model['max_psnr']}")
    out.append(f"    const val MIN_PIXELS = {model['min_pixels']}")
    out.append(f"    const val MAX_PIXELS = {model['max_pixels']}")
    out.append(f"    const val HEIGHT_FACTORS = {HEIGHT_FACTORS}")
    out.append(f"    const val COEFFICIENTS = {COEFFICIENTS}")
    out.append("")
    out.append("    /** The eight coefficients of one bucket, or null for one the header has no row for. */")
    out.append("    fun coefficients(psnr: Int, heightFactor: Int, chroma444: Boolean): DoubleArray? {")
    out.append("        if (psnr !in MIN_PSNR..MAX_PSNR || heightFactor !in 0 until HEIGHT_FACTORS) return null")
    out.append("        val family = families[(if (chroma444) HEIGHT_FACTORS else 0) + heightFactor]")
    out.append("        val start = (psnr - MIN_PSNR) * COEFFICIENTS")
    out.append("        return family.copyOfRange(start, start + COEFFICIENTS)")
    out.append("    }")
    out.append("")
    out.append("    // One array per viewing distance and chroma, every 4:2:0 family and then every 4:4:4 one.")
    out.append("    // Split into one function each because a single initialiser holding all of them would")
    out.append("    // come close to the JVM's 64 KB limit on the size of a method.")
    out.append("    private val families: Array<DoubleArray> = arrayOf(")
    for factor, chroma444 in families:
        out.append(f"        {family_name(factor, chroma444)}(),")
    out.append("    )")
    for factor, chroma444 in families:
        h = 1.0 + factor / 8.0
        out.append("")
        chroma = "4:4:4" if chroma444 else "4:2:0"
        out.append(f"    // H {h:.3f}, {chroma}: {model['min_psnr']} dB to {model['max_psnr']} dB.")
        out.append(f"    private fun {family_name(factor, chroma444)}() = doubleArrayOf(")
        for psnr in range(model["min_psnr"], model["max_psnr"] + 1):
            numbers = [kotlin_double(n) for n in model["buckets"][(psnr, factor, chroma444)]]
            out.append("        " + ", ".join(numbers[:4]) + ",")
            out.append("        " + ", ".join(numbers[4:]) + f", // {psnr} dB")
        out.append("    )")
    out.append("}")
    return "\n".join(out) + "\n"


def render_fixture(checkout):
    compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if compiler is None:
        fail("no C compiler on this machine")
    with tempfile.TemporaryDirectory() as work:
        binary = pathlib.Path(work) / "fixture"
        command = [
            compiler,
            "-std=c11",
            "-O2",
            "-ffp-contract=off",
            "-Wall",
            "-Wextra",
            "-Werror",
            # -isystem, not -I: the header trips -Wsign-compare, and it is upstream's to fix, not a
            # reason to relax the warnings on the program here.
            "-isystem",
            str(checkout / "eval-results"),
            str(FIXTURE_PROGRAM),
            "-o",
            str(binary),
            "-lm",
        ]
        built = subprocess.run(command, capture_output=True, text=True)
        if built.returncode != 0:
            fail(f"the fixture program did not build:\n{built.stderr}")
        ran = subprocess.run([str(binary)], capture_output=True, text=True)
        if ran.returncode != 0:
            fail(f"the fixture program failed:\n{ran.stderr}")
    provenance = [
        "# Upstream's own answers, for PyroWaveRateModelTest. Generated by tools/pyrowave_rate_model.py",
        "# from tools/pyrowave_rate_fixture.c compiled against the header below. Do not edit.",
        f"# source {HEADER_PATH} in {UPSTREAM}",
        f"# upstream-commit {UPSTREAM_COMMIT}",
        f"# header-sha256 {HEADER_SHA256}",
    ]
    return "\n".join(provenance) + "\n" + ran.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--pyrowave", required=True, type=pathlib.Path, help="a pyrowave checkout")
    parser.add_argument("--check", action="store_true", help="compare with the committed files instead")
    args = parser.parse_args()

    model = read_header(args.pyrowave)
    outputs = {TABLE: render_table(model), FIXTURE: render_fixture(args.pyrowave)}

    if args.check:
        stale = [p for p, text in outputs.items() if not p.exists() or p.read_text(encoding="utf-8") != text]
        for path in stale:
            print(f"stale: {path.relative_to(REPO_ROOT)}", file=sys.stderr)
        if stale:
            sys.exit(1)
        print("PyroWaveRateTable.kt and pyrowave-rate-reference.csv match the pinned header.")
        return

    for path, text in outputs.items():
        path.write_text(text, encoding="utf-8")
        print(f"wrote {path.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
