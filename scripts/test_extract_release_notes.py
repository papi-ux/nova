#!/usr/bin/env python3

import unittest
import subprocess
import sys
import tempfile
from pathlib import Path

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

    def test_stable_rejects_beta_install_instructions_in_extracted_body(self) -> None:
        for wording in (
            "While Nova 1.3.7 is in beta, use the packages attached to this prerelease.",
            "While the release is still in beta, use its packages.",
            "Until 1.3.7 ships, use the beta packages.",
            "Use the packages attached to the prerelease you are reading.",
            "The final release does not exist yet.",
            "The stable release named by the install commands doesn't exist yet.",
        ):
            with self.subTest(wording=wording):
                with self.assertRaisesRegex(ValueError, "stable release notes"):
                    extract_release_notes(CHANGELOG.replace("Curated summary.", wording), "v1.3.7")

    def test_stable_rejects_unverified_matched_host_release_claims(self) -> None:
        for wording in (
            "Nova is matched with Polaris 1.3.7.",
            "Use the matching Polaris release.",
            "These releases are a matched pair.",
            "Nova pairs with Polaris v1.3.7.",
        ):
            with self.subTest(wording=wording):
                with self.assertRaisesRegex(ValueError, "stable release notes"):
                    extract_release_notes(CHANGELOG.replace("Curated summary.", wording), "v1.3.7")

    def test_rewrapping_beta_instructions_cannot_bypass_stable_gate(self) -> None:
        wording = "While Nova\n1.3.7 is in\nbeta, use the attached packages."
        with self.assertRaisesRegex(ValueError, "stable release notes"):
            extract_release_notes(CHANGELOG.replace("Curated summary.", wording), "v1.3.7")

    def test_beta_and_rc_keep_their_install_instructions(self) -> None:
        wording = "While Nova 1.3.7 is in beta, use the packages attached to this prerelease."
        changelog = CHANGELOG.replace("Curated summary.", wording)
        for tag in ("v1.3.7-beta.1", "v1.3.7-rc.2"):
            with self.subTest(tag=tag):
                self.assertEqual(extract_release_notes(changelog, tag), wording + "\n\n- Exact release fact.\n")

    def test_stable_allows_beta_app_features_and_version_requirements(self) -> None:
        wording = (
            "The separate Nova Beta app enables experimental PyroWave. "
            "This feature needs Polaris 1.3.7. Pair the device with Polaris. "
            "Matching Polaris client controls are versioned independently."
        )
        self.assertEqual(
            extract_release_notes(CHANGELOG.replace("Curated summary.", wording), "v1.3.7"),
            wording + "\n\n- Exact release fact.\n",
        )

    def test_stable_checks_only_the_selected_release_section(self) -> None:
        changelog = CHANGELOG.replace("Pending.", "While Nova 1.3.8 is in beta, use the prerelease.")
        self.assertEqual(extract_release_notes(changelog, "v1.3.7"), "Curated summary.\n\n- Exact release fact.\n")

    def test_actual_release_cli_refuses_stable_body_without_emitting_it(self) -> None:
        changelog = CHANGELOG.replace("Curated summary.", "The final release does not exist yet.")
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "CHANGELOG.md"
            path.write_text(changelog)
            command = [sys.executable, str(Path(__file__).with_name("extract_release_notes.py")), "v1.3.7", str(path)]
            result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("stable release notes", result.stderr)


if __name__ == "__main__":
    unittest.main()
