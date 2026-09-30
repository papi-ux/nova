"""Stable-only wording checks, ported from Polaris's release-notes gate.

Apply these to the selected Nova release body, never to historical sections or
Unreleased. Prereleases retain their install instructions. References to the
separate Nova Beta app and Polaris feature requirements remain valid.
"""

from __future__ import annotations

import re

VERSION = r"v?\d+\.\d+\.\d+"
SUBJECT = rf"(?:nova(?:\s+{VERSION})?|{VERSION}|(?:the|this)\s+release)"

REFUSED = tuple(
    (re.compile(pattern, re.IGNORECASE), reason)
    for pattern, reason in (
        (
            rf"\bwhile\s+{SUBJECT}\s+is\s+(?:still\s+)?(?:in\s+beta|a\s+beta|in\s+prerelease|a\s+prerelease)\b",
            "carries instructions for a release still in beta",
        ),
        (
            rf"\buntil\s+{SUBJECT}\s+(?:ships|is\s+(?:out|released|stable)|goes\s+stable)\b",
            "carries instructions for a release still in beta",
        ),
        (r"\bthe\s+prerelease\s+you\s+are\s+reading\b", "sends stable readers to a prerelease"),
        (r"\battached\s+to\s+(?:the|this)\s+prerelease\b", "sends stable readers to a prerelease"),
        (
            r"\b(?:final|stable)\s+release\b(?:[^.]|\.(?=\w)){0,60}?\b(?:does\s+not|doesn't)\s+exist\s+yet\b",
            "says the release named by the install instructions does not exist",
        ),
        (r"\bmatched\s+(?:(?:with|to)\s+)?polaris\b", "claims a matched Polaris release without release evidence"),
        (
            rf"\bmatching\s+polaris\s+(?:release|version|{VERSION})",
            "claims a matched Polaris release without release evidence",
        ),
        (r"\bmatched\s+pair\b", "claims a matched release pair without release evidence"),
        (
            rf"\b(?:matches|pairs|paired)\s+(?:with\s+)?polaris\s+{VERSION}",
            "claims a matched Polaris release without release evidence",
        ),
    )
)


def findings(text: str) -> list[tuple[int, str, str]]:
    """Return recognizable beta-only or matched-release claims with line numbers."""
    found = []
    for pattern, reason in REFUSED:
        for match in pattern.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            found.append((line, " ".join(match.group(0).split()), reason))
    return sorted(found)
