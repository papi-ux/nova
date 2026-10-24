import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

from tools import nova_flatpak_feed as feed


class FeedTests(unittest.TestCase):
    def test_beta_accepts_a_published_stable_release_without_changing_its_identity(self):
        release = dict(tag_name="v1.5.0", published_at="2026-01-01", draft=False, prerelease=False)
        original = release.copy()
        feed.validate_release(release, "beta")
        self.assertEqual(release, original)

    def test_stable_source_can_build_the_beta_ref_without_inventing_a_beta_version(self):
        root = Path(__file__).resolve().parents[1]
        source = json.loads((root / "clients/deck/packaging/flatpak/com.papi_ux.Nova.json").read_text())
        result = feed.prepare(source, "beta", "https://example.org/nova", "v1.5.0")
        self.assertEqual(result["branch"], "beta")
        opts = result["modules"][-1]["config-opts"]
        self.assertIn("-DNOVA_DECK_UPDATE_CHANNEL=beta", opts)
        self.assertIn("-DNOVA_DECK_VERSION_SUFFIX=", opts)
        self.assertEqual(result["finish-args"], source["finish-args"])
        self.assertNotIn("branch", source)

    def test_release_flag_must_match_the_numbered_tag_on_either_channel(self):
        release = dict(tag_name="v1.5.0", published_at="2026-01-01", draft=False, prerelease=False)
        for tag, marked in [("v1.5.0", True), ("v1.5.0-beta.1", False), ("v1.5.0-rc.2", False),
                            ("v1.5.0-beta", True), ("v1.5.0-beta.0", True), ("v1.5.0", None)]:
            for channel in feed.CHANNELS:
                with self.subTest(tag=tag, marked=marked, channel=channel), self.assertRaises(ValueError):
                    feed.validate_release(release | dict(tag_name=tag, prerelease=marked), channel)

    def test_generated_appstream_changes_only_the_current_release_version(self):
        project = Path(__file__).resolve().parents[1] / "clients/deck"
        source = project / "packaging/flatpak/com.papi_ux.Nova.metainfo.xml"
        original = source.read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "com.papi_ux.Nova.metainfo.xml"
            script = root / "metadata.cmake"
            script.write_text('set(PROJECT_VERSION "1.5.0")\ninclude("' + str(project / "cmake/NovaReleaseVersion.cmake") + '")\n'
                              'include("' + str(project / "cmake/NovaAppStream.cmake") + '")\n'
                              'nova_deck_configure_appstream("' + str(source) + '" "' + str(output) + '")\n')
            for suffix in ["", "-beta.1", "-rc.2"]:
                with self.subTest(suffix=suffix):
                    subprocess.run(["cmake", "-DNOVA_DECK_VERSION_SUFFIX=" + suffix, "-P", str(script)], check=True, capture_output=True, text=True)
                    metadata = ET.fromstring(output.read_bytes())
                    releases = metadata.findall("./releases/release")
                    self.assertEqual(releases[0].attrib["version"], "1.5.0" + suffix)
                    self.assertEqual(releases[0].attrib["type"], "development")
                    self.assertEqual(releases[1].attrib["version"], "1.4.13")
                    self.assertEqual(output.read_bytes(), original.replace(b'version="1.5.0"', ('version="1.5.0' + suffix + '"').encode(), 1))
            self.assertEqual(source.read_bytes(), original)

    def test_release_manifest_preserves_the_numbered_tag_without_enabling_updates(self):
        root = Path(__file__).resolve().parents[1]
        source = json.loads((root / "clients/deck/packaging/flatpak/com.papi_ux.Nova.json").read_text())
        source["modules"][-1]["config-opts"].append("-DNOVA_DECK_VERSION_SUFFIX=-beta.99")
        for tag, suffix in [(None, ""), ("v1.5.0", ""), ("v1.5.0-beta.1", "-beta.1"), ("v1.5.0-rc.2", "-rc.2")]:
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
        for channel, tag, suffix in [("stable", "v1.5.0", ""), ("beta", "v1.5.0", ""), ("beta", "v1.5.0-beta.1", "-beta.1"), ("beta", "v1.5.0-rc.2", "-rc.2")]:
            result = feed.prepare(source, channel, "https://example.org/nova", tag)
            self.assertIn("-DNOVA_DECK_VERSION_SUFFIX=" + suffix, result["modules"][-1]["config-opts"])
        for channel, tag in [("stable", "v1.5.0-beta.1"), ("beta", "v1.5.0-beta"), ("beta", "v1.5.0-beta.0")]:
            with self.subTest(channel=channel, tag=tag), self.assertRaises(ValueError):
                feed.prepare(source, channel, "https://example.org/nova", tag)

    def test_cmake_release_version_validates_the_optional_numbered_suffix(self):
        module = Path(__file__).resolve().parents[1] / "clients/deck/cmake/NovaReleaseVersion.cmake"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "version.cmake"
            script.write_text('set(PROJECT_VERSION "1.5.0")\ninclude("' + str(module) + '")\n'
                              'file(WRITE "' + str(root / "version.txt") + '" "${NOVA_DECK_FULL_VERSION}")\n')
            for suffix in ["", "-beta.1", "-beta.12", "-rc.2"]:
                with self.subTest(suffix=suffix):
                    subprocess.run(["cmake", "-DNOVA_DECK_VERSION_SUFFIX=" + suffix, "-P", str(script)], check=True, capture_output=True, text=True)
                    self.assertEqual((root / "version.txt").read_text(), "1.5.0" + suffix)
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

    def test_build_target_admission_does_not_remove_catalog_preservation_support(self):
        for arch, channel in [("x86_64", "stable"), ("x86_64", "beta"), ("aarch64", "beta")]:
            feed.validate_build_target(arch, channel)
        for arch, channel in [("aarch64", "stable"), ("arm64", "beta"), ("riscv64", "beta"),
                              ("x86_64", "preview"), ("aarch64\n", "beta")]:
            with self.subTest(arch=arch, channel=channel), self.assertRaises(ValueError):
                feed.validate_build_target(arch, channel)
        # Unselected signed refs remain preservable even though new ARM Stable builds are gated.
        self.assertEqual(feed.catalog_path("stable", "aarch64"), Path("aarch64/stable.json"))

    def test_standard_manifest_includes_pyrowave_in_both_channels(self):
        root = Path(__file__).resolve().parents[1] / "clients/deck/packaging/flatpak"
        for channel in feed.CHANNELS:
            name = "com.papi_ux.Nova.json"
            source = json.loads((root / name).read_text())
            tag = "v1.5.0" if channel == "stable" else "v1.5.0-beta.1"
            result = feed.prepare(source, channel, "https://example.org/nova/", tag)
            self.assertEqual(result["branch"], channel)
            self.assertNotIn("branch", source)
            self.assertEqual(result["finish-args"], source["finish-args"])
            without_codec = json.loads(json.dumps(source))
            without_codec["modules"].remove("modules/pyrowave.json")
            with self.assertRaises(ValueError):
                feed.prepare(without_codec, channel, "https://example.org/nova", tag)
            with self.assertRaises(ValueError):
                feed.prepare(source, "pyrowave", "https://example.org/nova", tag)

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


    def test_arm_catalog_matches_only_the_canonical_selected_architecture(self):
        expected = {"appId": feed.APP, "channel": "beta", "arch": "aarch64", "commit": "a" * 64, "version": "v1.5.0-beta.1"}
        self.assertEqual(feed.catalog("beta", "a" * 64, "v1.5.0-beta.1", "aarch64"), expected)
        for arch in ["", "arm64", "arm", "riscv64", "aarch64\n", "../aarch64"]:
            with self.subTest(arch=arch), self.assertRaises(ValueError):
                feed.catalog("beta", "a" * 64, "v1.5.0-beta.1", arch)

    def test_arm_site_never_substitutes_an_x86_ref_with_the_same_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(feed.subprocess, "check_output", side_effect=[f"app/{feed.APP}/x86_64/beta\n", "a" * 64 + "\n"]):
                with self.assertRaises(ValueError):
                    feed.write_site(root / "repo", root / "site", root / "previous", "beta", "v1.5.0-beta.1",
                                    "https://example.org/nova", b"public fixture", "aarch64")

    def test_mixed_refs_preserve_both_legacy_x86_catalogs_and_the_other_arm_channel(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); previous = root / "previous"; previous.mkdir(); (previous / "aarch64").mkdir()
            refs = {f"app/{feed.APP}/x86_64/stable": "a" * 64, f"app/{feed.APP}/x86_64/beta": "b" * 64,
                    f"app/{feed.APP}/aarch64/stable": "c" * 64, f"app/{feed.APP}/aarch64/beta": "d" * 64}
            old_catalogs = {}
            for arch, branch, revision, version in [("x86_64", "stable", "a" * 64, "v1.4.13"),
                                                     ("x86_64", "beta", "b" * 64, "v1.4.13-beta.1"),
                                                     ("aarch64", "stable", "c" * 64, "v1.4.13")]:
                path = Path(branch + ".json") if arch == "x86_64" else Path(arch) / (branch + ".json")
                data = {"appId": feed.APP, "channel": branch, "arch": arch, "commit": revision, "version": version}
                encoded = json.dumps(data, indent=2) + "\n"; (previous / path).write_text(encoded); old_catalogs[path] = encoded
            queried = []
            def ostree(args, **kwargs):
                if args[-1] == "refs": return "\n".join(refs) + "\n"
                queried.append(args[-1]); return refs[args[-1]] + "\n"
            with patch.object(feed.subprocess, "check_output", side_effect=ostree):
                feed.write_site(root / "repo", root / "site", previous, "beta", "v1.5.0-beta.1",
                                "https://example.org/nova", b"public fixture", "aarch64")
            for path, encoded in old_catalogs.items(): self.assertEqual((root / "site" / path).read_text(), encoded)
            self.assertEqual(json.loads((root / "site/aarch64/beta.json").read_text()),
                             {"appId": feed.APP, "channel": "beta", "arch": "aarch64", "commit": "d" * 64, "version": "v1.5.0-beta.1"})
            self.assertEqual(set(queried), set(refs))
            repo, ref = feed.descriptors("https://example.org/nova", b"public fixture", "beta")
            self.assertEqual((root / "site/nova.flatpakrepo").read_text(), repo)
            self.assertEqual((root / "site/nova-beta.flatpakref").read_text(), ref)

    def test_cross_arch_previous_catalog_is_validated_even_during_default_x86_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); previous = root / "previous"; (previous / "aarch64").mkdir(parents=True)
            wrong = {"appId": feed.APP, "channel": "beta", "arch": "x86_64", "commit": "a" * 64, "version": "v1.4.13-beta.1"}
            (previous / "aarch64/beta.json").write_text(json.dumps(wrong))
            refs = f"app/{feed.APP}/x86_64/beta\napp/{feed.APP}/aarch64/beta\n"
            with patch.object(feed.subprocess, "check_output", side_effect=lambda args, **kwargs: refs if args[-1] == "refs" else "a" * 64 + "\n"):
                with self.assertRaises(ValueError):
                    feed.write_site(root / "repo", root / "site", previous, "beta", "v1.5.0-beta.1",
                                    "https://example.org/nova", b"public fixture")

    def test_unsupported_site_architecture_fails_before_ostree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(feed.subprocess, "check_output") as query:
                with self.assertRaises(ValueError):
                    feed.write_site(root / "repo", root / "site", root / "previous", "beta", "v1.5.0-beta.1",
                                    "https://example.org/nova", b"public fixture", "arm64")
                query.assert_not_called()


class CatalogPreservationTests(unittest.TestCase):
    def repository(self):
        return {f"app/{feed.APP}/{arch}/{channel}": letter * 64
                for (arch, channel), letter in zip(
                    (("x86_64", "stable"), ("x86_64", "beta"),
                     ("aarch64", "stable"), ("aarch64", "beta")), "abcd")}

    def catalogs(self, refs):
        return {feed.catalog_path(channel, arch): feed.catalog(channel, revision, "v1.5.0", arch)
                for ref, revision in refs.items() for _, _, arch, channel in [ref.split("/")]}

    def backend(self, refs, catalogs, requested):
        def output(args, **kwargs):
            if args[0] == "curl":
                requested.append(args[-1])
                return json.dumps(catalogs[Path(args[-1].removeprefix("https://example.org/nova/"))])
            if args[-1] == "refs":
                return "\n".join(refs) + "\n"
            return refs[args[-1]] + "\n"
        return output

    def test_either_architecture_publish_preserves_all_other_architecture_channels(self):
        for selected_arch in feed.ARCHITECTURES:
            for selected_channel in feed.CHANNELS:
                with self.subTest(arch=selected_arch, channel=selected_channel), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory); refs = self.repository(); old = self.catalogs(refs); requested = []
                    with patch.object(feed.subprocess, "check_output", side_effect=self.backend(refs, old, requested)):
                        feed.preserve_catalogs(root / "repo", root / "previous", "https://example.org/nova")
                        refs[f"app/{feed.APP}/{selected_arch}/{selected_channel}"] = "e" * 64
                        feed.write_site(root / "repo", root / "site", root / "previous", selected_channel,
                                        "v1.5.1", "https://example.org/nova", b"public fixture", selected_arch)
                    self.assertEqual(set(requested), {"https://example.org/nova/" + path.as_posix() for path in old})
                    self.assertEqual(len(requested), 4)
                    selected = feed.catalog_path(selected_channel, selected_arch)
                    for path, data in old.items():
                        self.assertEqual(json.loads((root / "previous" / path).read_text()), data)
                        expected = feed.catalog(selected_channel, "e" * 64, "v1.5.1", selected_arch) if path == selected else data
                        self.assertEqual(json.loads((root / "site" / path).read_text()), expected)
                    self.assertFalse((root / "site/x86_64").exists())
                    self.assertIn("stable releases intentionally delivered to that branch", (root / "site/index.html").read_text())
                    for channel in feed.CHANNELS:
                        repo, descriptor = feed.descriptors("https://example.org/nova", b"public fixture", channel)
                        self.assertEqual((root / "site/nova.flatpakrepo").read_text(), repo)
                        self.assertEqual((root / "site" / f"nova-{channel}.flatpakref").read_text(), descriptor)

    def test_invalid_prior_catalogs_never_replace_existing_destination(self):
        invalid = [None, [], {}, {"appId": "org.example.Unrelated"}, {"channel": "stable"},
                   {"arch": "x86_64"}, {"commit": "f" * 64}, {"version": 1},
                   {"version": "not-a-release"}, {"extra": "unrelated metadata"}]
        for malformed in invalid:
            with self.subTest(metadata=malformed), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); refs = self.repository(); catalogs = self.catalogs(refs)
                path = Path("aarch64/beta.json")
                catalogs[path] = catalogs[path] | malformed if isinstance(malformed, dict) and malformed else malformed
                previous = root / "previous"; previous.mkdir(); sentinel = previous / "stable.json"
                sentinel.write_bytes(b"existing catalog must survive")
                with patch.object(feed.subprocess, "check_output", side_effect=self.backend(refs, catalogs, [])):
                    with self.assertRaises(ValueError):
                        feed.preserve_catalogs(root / "repo", previous, "https://example.org/nova")
                self.assertEqual(sentinel.read_bytes(), b"existing catalog must survive")
                self.assertEqual(list(previous.rglob("*")), [sentinel])

    def test_fetch_or_decode_failure_leaves_existing_destination_unchanged(self):
        for failure in ("http", "json", "oversized"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); refs = self.repository(); old = self.catalogs(refs); requested = []
                previous = root / "previous"; previous.mkdir(); sentinel = previous / "stable.json"; sentinel.write_bytes(b"keep")
                backend = self.backend(refs, old, requested)
                def output(args, **kwargs):
                    if args[0] == "curl" and args[-1].endswith("aarch64/beta.json"):
                        if failure == "http": raise subprocess.CalledProcessError(22, args)
                        if failure == "json": return "{"
                        return " " * 65536 + "{}"
                    return backend(args, **kwargs)
                with patch.object(feed.subprocess, "check_output", side_effect=output):
                    with self.assertRaises(subprocess.CalledProcessError if failure == "http" else ValueError):
                        feed.preserve_catalogs(root / "repo", previous, "https://example.org/nova")
                self.assertEqual(sentinel.read_bytes(), b"keep")
                self.assertEqual(list(previous.rglob("*")), [sentinel])

    def test_site_rejects_bad_catalog_or_descriptor_before_overwriting_output(self):
        for bad in ([], {"appId": "org.example.Unrelated"}, {"arch": "x86_64"}, {"version": 1}, "invalid-key", "invalid-url"):
            with self.subTest(bad=bad), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); refs = self.repository(); old = self.catalogs(refs)
                previous = root / "previous"
                for path, data in old.items():
                    dest = previous / path; dest.parent.mkdir(parents=True, exist_ok=True)
                    if path == Path("aarch64/beta.json") and isinstance(bad, (dict, list)):
                        data = data | bad if isinstance(bad, dict) else bad
                    dest.write_text(json.dumps(data))
                site = root / "site"; site.mkdir(); sentinel = site / "stable.json"; sentinel.write_bytes(b"keep")
                url = "http://example.org/nova" if bad == "invalid-url" else "https://example.org/nova"
                key = b"" if bad == "invalid-key" else b"public fixture"
                with patch.object(feed.subprocess, "check_output", side_effect=self.backend(refs, old, [])):
                    with self.assertRaises(ValueError):
                        feed.write_site(root / "repo", site, previous, "stable", "v1.5.1", url, key)
                self.assertEqual(sentinel.read_bytes(), b"keep")
                self.assertEqual(list(site.rglob("*")), [sentinel])

    def test_preservation_cli_selects_only_supported_nova_refs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); refs = self.repository(); old = self.catalogs(refs); requested = []
            refs["app/org.example.Unrelated/aarch64/beta"] = "f" * 64
            refs[f"app/{feed.APP}/riscv64/beta"] = "f" * 64
            argv = ["nova_flatpak_feed.py", "preserve-catalogs", "--repository", str(root / "repo"),
                    "--previous", str(root / "previous"), "--url", "https://example.org/nova"]
            with patch("sys.argv", argv), patch.object(feed.subprocess, "check_output", side_effect=self.backend(refs, old, requested)):
                feed.main()
            self.assertEqual(set(requested), {"https://example.org/nova/" + path.as_posix() for path in old})


class WorkflowTests(unittest.TestCase):
    root = Path(__file__).resolve().parents[1]

    def test_workflow_and_compiled_default_use_the_same_final_feed_address(self):
        workflow = (self.root / ".github/workflows/flatpak-feed.yml").read_text()
        cmake = (self.root / "clients/deck/CMakeLists.txt").read_text()
        workflow_url = re.search(r"^      NOVA_FEED_URL: (\S+)$", workflow, re.MULTILINE).group(1)
        cmake_url = re.search(r'set\(NOVA_DECK_UPDATE_URL "([^"]+)" CACHE STRING', cmake).group(1)
        self.assertEqual(workflow_url, cmake_url)
        self.assertEqual(cmake_url, "https://updates.papi-ux.com/nova")

    def test_actual_workflow_preflight_refuses_unsupported_inputs_before_release_requests(self):
        workflow = (self.root / ".github/workflows/flatpak-feed.yml").read_text()
        preflight = textwrap.dedent(re.search(r"python3 - <<'PY'\n(.*?)\n          PY", workflow, re.DOTALL).group(1))
        for arch, channel, tag, accepted in [("x86_64", "stable", "v1.5.0", True),
                                              ("x86_64", "beta", "v1.5.0", True),
                                              ("aarch64", "beta", "v1.5.0", True),
                                              ("aarch64", "beta", "v1.5.0-rc.2", True),
                                              ("aarch64", "stable", "v1.5.0", False),
                                              ("arm64", "beta", "v1.5.0", False),
                                              ("x86_64", "unknown", "v1.5.0", False),
                                              ("x86_64", "beta", "v1.5.0-beta.0", False),
                                              ("x86_64", "beta", "master", False)]:
            with self.subTest(arch=arch, channel=channel, tag=tag), patch.dict(os.environ, NOVA_ARCH=arch, NOVA_CHANNEL=channel, NOVA_TAG=tag):
                if accepted:
                    exec(preflight, {})
                else:
                    with self.assertRaises((ValueError, SystemExit)):
                        exec(preflight, {})

    def test_native_architecture_and_nested_artifact_keep_existing_signature_guards(self):
        workflow = (self.root / ".github/workflows/flatpak-feed.yml").read_text()
        self.assertIn("options: [x86_64, aarch64]", workflow)
        self.assertIn("runs-on: ${{ inputs.arch == 'aarch64' && 'ubuntu-24.04-arm' || 'ubuntu-latest' }}", workflow)
        for binding in ['test "$(uname -m)" = "$NOVA_ARCH"', 'feed-build-${NOVA_ARCH}.json',
                        'flatpak-builder --user --arch="$NOVA_ARCH"', 'ref="app/com.papi_ux.Nova/$NOVA_ARCH/$NOVA_CHANNEL"',
                        '--url "$NOVA_FEED_URL" --arch "$NOVA_ARCH"', 'flatpak build-bundle --arch="$NOVA_ARCH"',
                        'Nova-Linux-${NOVA_ARCH}-${NOVA_CHANNEL}.flatpak',
                        'nova-linux-${{ inputs.arch }}-${{ inputs.channel }}-installer',
                        '--repository site/nova/repo --site site/nova', 'file://$PWD/site/nova/repo',
                        'path: site/nova/nova-${{ inputs.channel }}.flatpakref',
                        '<a href="https://papi-ux.com/nova/">Nova product page</a>']:
            self.assertIn(binding, workflow)
        self.assertNotIn('site/repo', workflow)
        # Source changes retain the existing off-by-default publish and verified preservation path.
        self.assertRegex(workflow, r"publish:\n(?:.*\n){0,4}        default: false")
        self.assertIn('if: inputs.publish', workflow)
        self.assertIn('--set=gpg-verify=true --set=gpg-verify-summary=true', workflow)
        self.assertIn('pull --untrusted candidate "$ref"', workflow)
        self.assertIn('--repository site/nova/repo --previous previous', workflow)
        self.assertIn('cancel-in-progress: false', workflow)
        build_job = workflow.split('  build:\n', 1)[1].split('  publish:\n', 1)[0]
        self.assertIsNone(re.search(r"^    environment:", build_job, re.MULTILINE), 'Private rehearsal must not gain an approval environment')


if __name__ == "__main__":
    unittest.main()
