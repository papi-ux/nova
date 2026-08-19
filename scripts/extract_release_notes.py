#!/usr/bin/env python3
"""Extract one exact version section from Nova's changelog."""

from __future__ import annotations

import re
import sys
from pathlib import Path


VERSION_TAG = re.compile(r"^v(?P<version>[0-9]+(?:\.[0-9]+){2})$")
RELEASE_HEADING = re.compile(
    r"^## (?P<version>[0-9]+(?:\.[0-9]+){2}) - (?P<date>[0-9]{4}-[0-9]{2}-[0-9]{2})$",
    re.MULTILINE,
)


def extract_release_notes(changelog: str, tag: str) -> str:
    tag_match = VERSION_TAG.fullmatch(tag)
    if not tag_match:
        raise ValueError(f"release tag must be vMAJOR.MINOR.PATCH, got: {tag}")

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

    return f"{body}\n"


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3):
        print(
            "usage: extract_release_notes.py vMAJOR.MINOR.PATCH [CHANGELOG.md]",
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
