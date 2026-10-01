import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools import nova_flatpak_feed as feed


class FeedTests(unittest.TestCase):
    def test_release_manifest_preserves_the_numbered_tag_without_enabling_updates(self):
        root = Path(__file__).resolve().parents[1]
        source = json.loads((root / "clients/deck/packaging/flatpak/com.papi_ux.Nova.json").read_text())
        source["modules"][-1]["config-opts"].append("-DNOVA_DECK_VERSION_SUFFIX=-beta.99")
        for tag, suffix in [(None, ""), ("v1.4.14", ""), ("v1.4.14-beta.1", "-beta.1"), ("v1.4.14-rc.2", "-rc.2")]:
            with self.subTest(tag=tag):
                result = feed.prepare_release(source, tag)
                opts = result["modules"][-1]["config-opts"]
                self.assertEqual([v for v in opts if v.startswith("-DNOVA_DECK_VERSION_SUFFIX=")],
                                 ["-DNOVA_DECK_VERSION_SUFFIX=" + suffix])
                self.assertFalse(any(v.startswith("-DNOVA_DECK_UPDATE_") for v in opts))
                self.assertNotIn("branch", result)
        self.assertIn("-DNOVA_DECK_VERSION_SUFFIX=-beta.99", source["modules"][-1]["config-opts"])

    def test_feed_build_requires_the_exact_release_channel_and_number(self):
        root = Path(__file__).resolve().parents[1]
        source = json.loads((root / "clients/deck/packaging/flatpak/com.papi_ux.Nova.json").read_text())
        for channel, tag, suffix in [("stable", "v1.4.14", ""), ("beta", "v1.4.14-beta.1", "-beta.1"), ("beta", "v1.4.14-rc.2", "-rc.2")]:
            result = feed.prepare(source, channel, "https://example.org/nova", tag)
            self.assertIn("-DNOVA_DECK_VERSION_SUFFIX=" + suffix, result["modules"][-1]["config-opts"])
        for channel, tag in [("stable", "v1.4.14-beta.1"), ("beta", "v1.4.14"), ("beta", "v1.4.14-beta"), ("beta", "v1.4.14-beta.0")]:
            with self.subTest(channel=channel, tag=tag), self.assertRaises(ValueError):
                feed.prepare(source, channel, "https://example.org/nova", tag)

    def test_cmake_release_version_validates_the_optional_numbered_suffix(self):
        module = Path(__file__).resolve().parents[1] / "clients/deck/cmake/NovaReleaseVersion.cmake"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "version.cmake"
            script.write_text('set(PROJECT_VERSION "1.4.14")\ninclude("' + str(module) + '")\n'
                              'file(WRITE "' + str(root / "version.txt") + '" "${NOVA_DECK_FULL_VERSION}")\n')
            for suffix in ["", "-beta.1", "-beta.12", "-rc.2"]:
                with self.subTest(suffix=suffix):
                    subprocess.run(["cmake", "-DNOVA_DECK_VERSION_SUFFIX=" + suffix, "-P", str(script)], check=True, capture_output=True, text=True)
                    self.assertEqual((root / "version.txt").read_text(), "1.4.14" + suffix)
            for suffix in ["beta.1", "-beta", "-beta.0", "-rc.0", "-beta.1.2", "-alpha.1", "-beta.1\n", '-beta.1";x']:
                with self.subTest(suffix=suffix):
                    result = subprocess.run(["cmake", "-DNOVA_DECK_VERSION_SUFFIX=" + suffix, "-P", str(script)], capture_output=True, text=True)
                    self.assertNotEqual(result.returncode, 0)

    def test_release_admission(self):
        release = dict(tag_name="v1.4.13-beta.1", published_at="2026-01-01", draft=False, prerelease=True)
        feed.validate_release(release, "beta")
        with self.assertRaises(ValueError):
            feed.validate_release(release, "pyrowave")
        for channel, changes in [("stable", {}), ("beta", {"draft": True}),
                                 ("beta", {"published_at": None}), ("beta", {"prerelease": False}),
                                 ("beta", {"tag_name": "master"}), ("other", {})]:
            with self.subTest(channel=channel, changes=changes), self.assertRaises(ValueError):
                feed.validate_release(release | changes, channel)
        feed.validate_release(release | dict(prerelease=False, tag_name="v1.4.13"), "stable")

    def test_standard_manifest_includes_pyrowave_in_both_channels(self):
        root = Path(__file__).resolve().parents[1] / "clients/deck/packaging/flatpak"
        for channel in feed.CHANNELS:
            name = "com.papi_ux.Nova.json"
            source = json.loads((root / name).read_text())
            result = feed.prepare(source, channel, "https://example.org/nova/")
            self.assertEqual(result["branch"], channel)
            self.assertNotIn("branch", source)
            self.assertEqual(result["finish-args"], source["finish-args"])
            without_codec = json.loads(json.dumps(source))
            without_codec["modules"].remove("modules/pyrowave.json")
            with self.assertRaises(ValueError):
                feed.prepare(without_codec, channel, "https://example.org/nova")
            with self.assertRaises(ValueError):
                feed.prepare(source, "pyrowave", "https://example.org/nova")

    def test_url_and_signing_metadata(self):
        for bad in ["http://example.org", "https://user:secret@example.org", "https://example.org/#x", "https://example.org/?x"]:
            with self.assertRaises(ValueError):
                feed.feed_url(bad)
        repo, ref = feed.descriptors("https://example.org/nova", b"public key fixture", "beta")
        self.assertIn("GPGKey=", repo)
        self.assertIn("Branch=beta\n", ref)
        self.assertIn("Name=com.papi_ux.Nova\n", ref)
        self.assertNotIn("NoGPGVerify", repo + ref)
        with self.assertRaises(ValueError):
            feed.descriptors("https://example.org", b"", "beta")

    def test_preserves_other_channels_and_refuses_stale_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); previous = root / "previous"; previous.mkdir()
            old = feed.catalog("stable", "a" * 64, "v1.4.12")
            (previous / "stable.json").write_text(json.dumps(old))
            refs = f"app/{feed.APP}/x86_64/stable\napp/{feed.APP}/x86_64/beta\n"

            def ostree(args, **kwargs):
                if args[-1] == "refs":
                    return refs
                return ("a" if args[-1].endswith("stable") else "b") * 64 + "\n"

            with patch.object(feed.subprocess, "check_output", side_effect=ostree):
                args = (root / "repo", root / "site", previous, "beta", "v1.4.13-beta.1", "https://example.org/nova", b"public fixture")
                feed.write_site(*args)
                self.assertEqual(json.loads((root / "site/stable.json").read_text()), old)
                self.assertEqual(json.loads((root / "site/beta.json").read_text())["commit"], "b" * 64)
                self.assertTrue((root / "site/nova-beta.flatpakref").is_file())
                (previous / "stable.json").write_text(json.dumps(old | {"commit": "c" * 64}))
                with self.assertRaises(ValueError):
                    feed.write_site(*args)


if __name__ == "__main__":
    unittest.main()
