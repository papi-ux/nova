#!/usr/bin/env python3

import unittest
import contextlib
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
from pathlib import Path
from unittest.mock import patch

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
        for tag in ("v1.3.7-beta.1", "v1.3.7-beta.12"):
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
                body = extract_release_notes(changelog, tag)
                if "-rc." in tag:
                    body = body.split("\n---\n\n", 1)[1]
                self.assertEqual(body, wording + "\n\n- Exact release fact.\n")

    def test_stable_and_beta_keep_exact_bytes_including_apk_names(self) -> None:
        body = "Résumé → Nova.  \n\n`Nova-Android-arm64-v8a.apk`\n\nlast line"
        changelog = "## 1.5.0 - 2026-11-13\n\n" + body + "\n\n## 1.4.13 - 2026-08-01\n\nold\n"
        for tag in ("v1.5.0", "v1.5.0-beta.2"):
            with self.subTest(tag=tag):
                self.assertEqual(extract_release_notes(changelog, tag).encode(), (body + "\n").encode())

    def test_rc_labels_the_selected_version_and_changes_only_exact_apk_basenames(self) -> None:
        stable_names = ["Nova-Android-" + abi + ".apk" for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")]
        untouched = [
            "Nova-Android-arm64-v8a.apk.sha256", "Nova-Android-arm64-v8a.apk.bak",
            "Nova-Android-arm64-v8a.apk-extra", "prefix-Nova-Android-arm64-v8a.apk",
            "Nova-Android-arm64-v8a.apks", "Nova-Android-arm64-v8a.apk_name",
            "Nova-Android-universal.apk", "Nova-Beta-Android-x86_64.apk",
            "Nova-Linux-x86_64-alpha.flatpak", "https://example.org/Nova-Android-arm64-v8a.apk",
            "https://github.com/papi-ux/nova/releases/download/v1.4.13/Nova-Android-x86_64.apk",
        ]
        body = "\n".join("`" + name + "`" for name in stable_names + untouched)
        changelog = CHANGELOG.replace("Curated summary.", body)
        for tag in ("v1.3.7-rc.1", "v1.3.7-rc.12"):
            with self.subTest(tag=tag):
                preamble, rendered = extract_release_notes(changelog, tag).split("\n---\n\n", 1)
                for phrase in (tag[1:], "people who choose Beta", "Stable 1.3.7 isnt published yet",
                               "com.papi.nova.pre", "Move to signed updates", "aarch64/beta",
                               "Steam Frame has no RC bundle", "https://updates.papi-ux.com/nova/"):
                    self.assertIn(phrase, preamble)
                expected = "\n".join("`" + name.replace("Nova-Android-", "Nova-Beta-Android-") + "`"
                                     for name in stable_names)
                expected += "\n" + "\n".join("`" + name + "`" for name in untouched)
                self.assertEqual(rendered, expected + "\n\n- Exact release fact.\n")

    def test_rc_still_requires_a_unique_dated_nonempty_section(self) -> None:
        for heading in ("## 1.3.7", "## 1.3.7 - TBD", "## 1.3.7 - 2026-8-19"):
            with self.subTest(heading=heading), self.assertRaisesRegex(ValueError, "found 0"):
                extract_release_notes(CHANGELOG.replace("## 1.3.7 - 2026-08-19", heading), "v1.3.7-rc.1")
        with self.assertRaisesRegex(ValueError, "found 2"):
            extract_release_notes(CHANGELOG + "\n## 1.3.7 - 2026-08-20\n\nduplicate", "v1.3.7-rc.1")
        with self.assertRaisesRegex(ValueError, "empty"):
            extract_release_notes("## 1.3.7 - 2026-08-19\n\n", "v1.3.7-rc.1")

    def test_rc_preserves_complete_urls_with_apk_query_fragment_and_nested_text(self) -> None:
        for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
            name = "Nova-Android-" + abi + ".apk"
            urls = [
                "[Unrelated download](https://example.org/download?filename=" + name + ")",
                "https://example.org/download#filename=" + name,
                "https://example.org/?next=https://nested.example/download?filename=" + name,
                "https://example.org/?next=(https://nested.example/#filename=" + name + ")",
                '<a href="https://example.org/?filename=' + name + '">download</a>',
                "<https://example.org/?filename=" + name + ">",
                "`https://example.org/?filename=" + name + "`",
                "//example.org/?filename=" + name,
                "custom:download?filename=" + name,
            ]
            with self.subTest(abi=abi):
                body = "`" + name + "`\n\n" + "\n".join(urls)
                changelog = "## 1.5.0 - 2026-11-13\n\n" + body + "\n"
                rendered = extract_release_notes(changelog, "v1.5.0-rc.1").split("\n---\n\n", 1)[1]
                expected = "`Nova-Beta-Android-" + abi + ".apk`\n\n" + "\n".join(urls) + "\n"
                self.assertEqual(rendered.encode(), expected.encode())

    def test_workflow_stage_and_final_readback_use_the_same_body(self) -> None:
        """Run the workflow's notes staging and complete readback with offline forge/asset doubles."""
        root = Path(__file__).resolve().parents[1]
        workflow = (root / ".github/workflows/build.yml").read_text()
        stage = workflow.split("        id: stage-release\n", 1)[1].split("        run: |\n", 1)[1]
        stage = textwrap.dedent(stage.split("\n      - name:", 1)[0])
        # Asset admission is separate; execute the real notes/create/edit/equality portion.
        stage = stage[stage.index('release_notes="$RUNNER_TEMP/nova-release-notes.md"'):]
        readback = workflow.split('nova-release-readback.py" <<\'PY\'\n', 1)[1].split("\n          PY\n", 1)[0]
        readback = compile(textwrap.dedent(readback), "nova-release-readback.py", "exec")
        fake_gh = '''
gh() {
  if [[ "$2" == view ]]; then
    if [[ "$#" -eq 3 ]]; then test -f "$FAKE_BODY"; else cat "$FAKE_BODY"; fi
  elif [[ "$2" == create || "$2" == edit ]]; then
    echo "$2" > "$FAKE_OPERATION"
    while [[ "$1" != --notes-file ]]; do shift; done
    cp "$2" "$FAKE_BODY"
  else return 99; fi
}
'''
        for tag in ("v1.3.7", "v1.3.7-rc.1"):
            for existing in (False, True):
                with self.subTest(tag=tag, existing=existing), tempfile.TemporaryDirectory() as directory:
                    tmp = Path(directory)
                    (tmp / "scripts").mkdir()
                    for name in ("extract_release_notes.py", "stable_release_notes.py"):
                        shutil.copyfile(root / "scripts" / name, tmp / "scripts" / name)
                    changelog = CHANGELOG.replace("Curated summary.", "Résumé. `Nova-Android-arm64-v8a.apk`")
                    (tmp / "CHANGELOG.md").write_text(changelog)
                    body_file = tmp / "remote-body.md"
                    if existing:
                        body_file.write_text("old body")
                    prefix = "Nova-Beta-Android" if "-rc." in tag else "Nova-Android"
                    names = [prefix + "-" + abi + ".apk" for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")]
                    names.append("Nova-Linux-x86_64-alpha.flatpak")
                    assets = tmp / "assets"
                    assets.mkdir()
                    for name in names:
                        content = (name + " fixture").encode()
                        (assets / name).write_bytes(content)
                        (assets / (name + ".sha256")).write_text(hashlib.sha256(content).hexdigest() + "  " + name + "\n")
                    env = dict(os.environ, RUNNER_TEMP=str(tmp), GITHUB_REF_NAME=tag,
                               NOVA_RELEASE_CHANNEL="prerelease" if "-rc." in tag else "stable",
                               EXPECTED_SOURCE_COMMIT="c" * 40, NOVA_ASSET_PREFIX=prefix,
                               APK_ASSET_DIR=str(assets), GITHUB_OUTPUT=str(tmp / "output"),
                               FAKE_BODY=str(body_file), FAKE_OPERATION=str(tmp / "operation"))
                    # macOS Bash 3 treats an empty channel array as unset under -u.
                    result = subprocess.run(["bash", "-ec", fake_gh + stage], cwd=tmp, env=env,
                                            capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    expected = extract_release_notes(changelog, tag).encode()
                    staged_file = tmp / "nova-release-notes.md"
                    self.assertEqual(staged_file.read_bytes(), expected)
                    self.assertEqual(body_file.read_bytes(), expected)
                    self.assertEqual((tmp / "operation").read_text().strip(), "edit" if existing else "create")

                    real_run = subprocess.run

                    def output(argv, **kwargs):
                        argv = list(argv)
                        if argv[:2] == ["git", "rev-parse"]:
                            return "c" * 40 + "\n"
                        if argv[:3] == ["gh", "release", "view"]:
                            if argv[-1] == "assets":
                                return json.dumps({"assets": [{"name": p.name} for p in assets.iterdir()]})
                            return body_file.read_text()
                        if argv[:2] == ["python3", "scripts/extract_release_notes.py"]:
                            return real_run(argv, cwd=tmp, stdout=subprocess.PIPE, check=True, **kwargs).stdout
                        self.fail("unexpected readback command: " + repr(argv))

                    def run(argv, **kwargs):
                        if argv[:2] == ["git", "fetch"]:
                            return subprocess.CompletedProcess(argv, 0)
                        if argv[:3] == ["gh", "release", "download"]:
                            shutil.copyfile(assets / argv[argv.index("--pattern") + 1],
                                            Path(argv[argv.index("--dir") + 1]) / argv[argv.index("--pattern") + 1])
                            return subprocess.CompletedProcess(argv, 0)
                        self.fail("unexpected readback command: " + repr(argv))

                    def final_readback():
                        with patch.dict(os.environ, env), patch.object(sys, "argv", ["readback", "final"]), \
                             patch.object(subprocess, "check_output", side_effect=output), \
                             patch.object(subprocess, "run", side_effect=run), contextlib.redirect_stdout(io.StringIO()):
                            exec(readback, {"__name__": "__main__"})

                    final_readback()
                    staged_file.write_bytes(expected + b"changed\n")
                    with self.assertRaisesRegex(SystemExit, "Curated notes changed after staging"):
                        final_readback()
                    staged_file.write_bytes(expected)
                    body_file.write_bytes(expected + b"changed\n")
                    with self.assertRaisesRegex(SystemExit, "Published release notes do not match"):
                        final_readback()

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
