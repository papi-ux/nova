#!/usr/bin/env python3

import unittest

from extract_release_notes import extract_release_notes


CHANGELOG = """# Changelog

## Unreleased

Pending.

## 1.3.7 - 2026-08-19

Curated summary.

- Exact release fact.

## 1.3.6 - 2026-08-12

Older summary.
"""


class ExtractReleaseNotesTest(unittest.TestCase):
    def test_extracts_only_the_exact_dated_release_section(self) -> None:
        self.assertEqual(
            extract_release_notes(CHANGELOG, "v1.3.7"),
            "Curated summary.\n\n- Exact release fact.\n",
        )

    def test_a_beta_reuses_the_notes_of_the_release_it_precedes(self) -> None:
        for tag in ("v1.3.7-beta.1", "v1.3.7-beta.12", "v1.3.7-rc.1"):
            self.assertEqual(
                extract_release_notes(CHANGELOG, tag),
                "Curated summary.\n\n- Exact release fact.\n",
            )

    def test_rejects_a_malformed_channel_suffix(self) -> None:
        for tag in ("v1.3.7-beta", "v1.3.7-alpha.1", "v1.3.7-beta.1.2", "v1.3.7beta.1"):
            with self.assertRaisesRegex(ValueError, "vMAJOR.MINOR.PATCH"):
                extract_release_notes(CHANGELOG, tag)

    def test_rejects_missing_and_malformed_tags(self) -> None:
        with self.assertRaisesRegex(ValueError, "found 0"):
            extract_release_notes(CHANGELOG, "v1.3.8")
        with self.assertRaisesRegex(ValueError, "vMAJOR.MINOR.PATCH"):
            extract_release_notes(CHANGELOG, "1.3.7")

    def test_rejects_duplicate_release_sections(self) -> None:
        duplicate = CHANGELOG + "\n## 1.3.7 - 2026-08-20\n\nDuplicate.\n"
        with self.assertRaisesRegex(ValueError, "found 2"):
            extract_release_notes(duplicate, "v1.3.7")

    def test_rejects_an_empty_release_section(self) -> None:
        empty = "# Changelog\n\n## 1.3.7 - 2026-08-19\n\n## 1.3.6 - 2026-08-12\n\nOlder.\n"
        with self.assertRaisesRegex(ValueError, "empty"):
            extract_release_notes(empty, "v1.3.7")


if __name__ == "__main__":
    unittest.main()
