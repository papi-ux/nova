import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools import nova_flatpak_feed as feed


class FeedTests(unittest.TestCase):
    def test_release_admission(self):
        release = dict(tag_name="v1.4.13-beta.1", published_at="2026-01-01", draft=False, prerelease=True)
        feed.validate_release(release, "beta")
        feed.validate_release(release, "pyrowave")
        for channel, changes in [("stable", {}), ("beta", {"draft": True}),
                                 ("beta", {"published_at": None}), ("beta", {"prerelease": False}),
                                 ("beta", {"tag_name": "master"}), ("other", {})]:
            with self.subTest(channel=channel, changes=changes), self.assertRaises(ValueError):
                feed.validate_release(release | changes, channel)
        feed.validate_release(release | dict(prerelease=False, tag_name="v1.4.13"), "stable")

    def test_real_manifests_keep_codec_channels_separate(self):
        root = Path(__file__).resolve().parents[1] / "clients/deck/packaging/flatpak"
        for channel in feed.CHANNELS:
            name = "com.papi_ux.Nova.pyrowave.json" if channel == "pyrowave" else "com.papi_ux.Nova.json"
            source = json.loads((root / name).read_text())
            result = feed.prepare(source, channel, "https://example.org/nova/")
            self.assertEqual(result["branch"], channel)
            self.assertNotIn("branch", source)
            self.assertEqual(result["finish-args"], source["finish-args"])
            with self.assertRaises(ValueError):
                feed.prepare(source, "beta" if channel == "pyrowave" else "pyrowave", "https://example.org/nova")

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
