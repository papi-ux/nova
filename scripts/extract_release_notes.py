#!/usr/bin/env python3
"""Extract one exact version section from Nova's changelog.

Stable and beta tags get the section exactly as written. A release candidate gets
the same section with a short note on top, and the three Stable Android file names
in it become the Nova Beta files that an rc build attaches. The release workflow
stages the body and reads it back through this one script, so both expect the
same bytes.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from stable_release_notes import findings


# Prereleases select the base version's dated section; RCs also label their body.
VERSION_TAG = re.compile(
    r"^v(?P<version>[0-9]+(?:\.[0-9]+){2})(?:-(?P<channel>(?P<kind>beta|rc)\.[0-9]+))?$"
)
RELEASE_HEADING = re.compile(
    r"^## (?P<version>[0-9]+(?:\.[0-9]+){2}) - (?P<date>[0-9]{4}-[0-9]{2}-[0-9]{2})$",
    re.MULTILINE,
)

# build.yml names prerelease APKs Nova-Beta-Android-<abi>.apk, the separate com.papi.nova.pre
# app. Only a whole file name changes: a URL path, a .sha256 sibling or a name that is already
# Beta's stays as written, so links to other releases keep pointing where they did.
STABLE_APK = re.compile(
    r"(?<![\w./-])Nova-Android-(?P<abi>arm64-v8a|armeabi-v7a|x86_64)\.apk(?![\w-]|\.\w)"
)

RC_PREAMBLE = """\
this is **{name}**, for people who choose Beta. Stable {version} isnt published yet. the change list below describes the planned {version} release.

the attached Android APKs are the separate Nova Beta app (`com.papi.nova.pre`), with their own pairing and settings.

on Linux and Steam Deck, use the attached `Nova-Linux-x86_64-alpha.flatpak` or Nova's signed Beta channel. on [Get Nova](https://papi-ux.com/downloads/?product=nova), choose Beta and install its exact bundle, then choose **Move to signed updates**. the [Linux update channels](https://updates.papi-ux.com/nova/) page also has Beta installers. already using signed Beta, check **Settings → Nova → Nova Updates**.

Steam Frame has no RC bundle here. choose Frame Beta on Get Nova, install its pinned Frame bundle, then **Move to signed updates** to the signed `aarch64/beta` channel (ARM64).

the signed Beta feeds and guided installer can take a little while after publication to offer {name}.

---
"""


def release_candidate_body(body: str, version: str, tag: str) -> str:
    """The rc body: its preamble, then the section with only the Stable APK names changed."""
    preamble = RC_PREAMBLE.format(name=tag[1:], version=version)
    return preamble + "\n" + STABLE_APK.sub(r"Nova-Beta-Android-\g<abi>.apk", body)


def extract_release_notes(changelog: str, tag: str) -> str:
    tag_match = VERSION_TAG.fullmatch(tag)
    if not tag_match:
        raise ValueError(
            "release tag must be vMAJOR.MINOR.PATCH with an optional "
            f"-beta.N or -rc.N, got: {tag}"
        )

    version = tag_match.group("version")
    matches = [
        match for match in RELEASE_HEADING.finditer(changelog)
        if match.group("version") == version
    ]
    if len(matches) != 1:
        raise ValueError(
            f"expected exactly one dated CHANGELOG.md section for {tag}, found {len(matches)}"
        )

    match = matches[0]
    next_heading = re.search(r"^## ", changelog[match.end():], re.MULTILINE)
    end = match.end() + next_heading.start() if next_heading else len(changelog)
    body = changelog[match.end():end].strip()
    if not body:
        raise ValueError(f"release notes for {tag} are empty")

    if tag_match.group("channel") is None:
        refused = findings(body)
        if refused:
            details = "; ".join(
                f'line {line}: "{phrase}" {reason}' for line, phrase, reason in refused
            )
            raise ValueError(f"stable release notes for {tag}: {details}")

    if tag_match.group("kind") == "rc":
        body = release_candidate_body(body, version, tag)

    return f"{body}\n"


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3):
        print(
            "usage: extract_release_notes.py vMAJOR.MINOR.PATCH[-beta.N|-rc.N] [CHANGELOG.md]",
            file=sys.stderr,
        )
        return 2

    tag = argv[1]
    changelog_path = Path(argv[2]) if len(argv) == 3 else Path("CHANGELOG.md")
    try:
        notes = extract_release_notes(changelog_path.read_text(encoding="utf-8"), tag)
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1

    sys.stdout.write(notes)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
