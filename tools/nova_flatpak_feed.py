#!/usr/bin/env python3
"""Prepare a channel build and the public metadata for a signed Flatpak repo.

No signing keys, hosting credentials, or installation state are managed here.
The catalog is advisory; Flatpak verifies and installs from its configured remote.
"""
import argparse
import base64
import html
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit

APP = "com.papi_ux.Nova"
CHANNELS = ("stable", "beta")
ARCHITECTURES = ("x86_64", "aarch64")
COMMIT = re.compile(r"[0-9a-f]{64}\Z")
TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?\Z")
BUILD_TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?P<suffix>-(?:beta|rc)\.[1-9][0-9]*)?\Z")


def feed_url(value):
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or not re.fullmatch(r"https://[A-Za-z0-9./_-]+", value)):
        raise ValueError("Feed URL must use HTTPS without credentials, query, or fragment")
    return value.rstrip("/")


def validate_release(release, channel):
    if channel not in CHANNELS or not TAG.fullmatch(release.get("tag_name", "")):
        raise ValueError("Select a versioned Nova release and a known channel")
    if release.get("draft") or not release.get("published_at"):
        raise ValueError("Only a published release may enter the update feed")
    if channel == "stable" and release.get("prerelease"):
        raise ValueError("A prerelease cannot enter the stable channel")
    if channel != "stable" and not release.get("prerelease"):
        raise ValueError("The beta channel requires an explicitly marked prerelease")


def version_suffix(tag):
    match = BUILD_TAG.fullmatch(tag)
    if not match:
        raise ValueError("Use vMAJOR.MINOR.PATCH with an optional numbered -beta.N or -rc.N suffix")
    return match.group("suffix") or ""


def prepare_release(manifest, version=None):
    if manifest.get("app-id") != APP:
        raise ValueError("Wrong app")
    # Work on a copy, so build helpers cannot change the checked-in manifest.
    manifest = json.loads(json.dumps(manifest))
    module = next(item for item in manifest["modules"] if isinstance(item, dict) and item.get("name") == "nova-deck")
    opts = module["config-opts"]
    enabled = "-DNOVA_DECK_BUILD_PYROWAVE=ON" in opts
    if not enabled or "modules/pyrowave.json" not in manifest["modules"]:
        raise ValueError("The standard Linux package must include PyroWave and its lazy probe")
    suffix = version_suffix(version) if version is not None else ""
    opts[:] = [v for v in opts if not v.startswith("-DNOVA_DECK_VERSION_SUFFIX=")]
    opts.append(f"-DNOVA_DECK_VERSION_SUFFIX={suffix}")
    return manifest


def prepare(manifest, channel, url, version):
    if channel not in CHANNELS:
        raise ValueError("Wrong channel")
    if bool(version_suffix(version)) != (channel == "beta"):
        raise ValueError("The release tag must match the selected update channel")
    manifest = prepare_release(manifest, version)
    module = next(item for item in manifest["modules"] if isinstance(item, dict) and item.get("name") == "nova-deck")
    opts = module["config-opts"]
    opts[:] = [v for v in opts if not v.startswith(("-DNOVA_DECK_UPDATE_CHANNEL=", "-DNOVA_DECK_UPDATE_URL="))]
    opts.extend([f"-DNOVA_DECK_UPDATE_CHANNEL={channel}", f"-DNOVA_DECK_UPDATE_URL={feed_url(url)}"])
    manifest["branch"] = channel
    return manifest


def catalog(channel, revision, version, architecture="x86_64"):
    if (channel not in CHANNELS or architecture not in ARCHITECTURES
            or not COMMIT.fullmatch(revision) or not TAG.fullmatch(version)):
        raise ValueError("Invalid channel catalog")
    return {"appId": APP, "channel": channel, "arch": architecture, "commit": revision, "version": version}


def catalog_path(channel, architecture):
    if channel not in CHANNELS or architecture not in ARCHITECTURES:
        raise ValueError("Unknown update channel or architecture")
    # Existing installations keep their root catalog paths and x86 identity.
    return Path(f"{channel}.json") if architecture == "x86_64" else Path(architecture) / f"{channel}.json"


def descriptors(url, key, channel):
    if channel not in CHANNELS:
        raise ValueError("Unknown update channel")
    url = feed_url(url)
    if not key or len(key) > 65536:
        raise ValueError("Missing or oversized exported public key")
    trust = "GPGKey=" + base64.b64encode(key).decode("ascii") + "\n"
    common = f"Title=Nova\nUrl={url}/repo\n" + trust
    repo = "[Flatpak Repo]\n" + common
    ref = ("[Flatpak Ref]\n" + common + f"Name={APP}\nBranch={channel}\nIsRuntime=false\n"
           "SuggestRemoteName=nova\nRuntimeRepo=https://dl.flathub.org/repo/flathub.flatpakrepo\n")
    return repo, ref


def write_site(repository, site, previous, channel, version, url, key, architecture="x86_64"):
    catalog_path(channel, architecture)  # Validate selection before repository I/O.
    refs = subprocess.check_output(["ostree", f"--repo={repository}", "refs"], text=True).splitlines()
    if f"app/{APP}/{architecture}/{channel}" not in refs:
        raise ValueError("Selected architecture/channel is missing from the exported repository")
    entries = []
    for arch in ARCHITECTURES:
        for branch in CHANNELS:
            ref = f"app/{APP}/{arch}/{branch}"
            if ref not in refs:
                continue
            revision = subprocess.check_output(["ostree", f"--repo={repository}", "rev-parse", ref], text=True).strip()
            path = catalog_path(branch, arch)
            if (arch, branch) == (architecture, channel):
                data = catalog(branch, revision, version, arch)
            else:
                # Validate every retained architecture/channel independently
                # against its own signature-verified ref, even for equal commits.
                data = json.loads((previous / path).read_text())
                if data != catalog(branch, revision, data.get("version", ""), arch):
                    raise ValueError("Previous catalog does not match its signed architecture/channel commit")
            entries.append((arch, branch, path, data))
    site.mkdir(parents=True, exist_ok=True)
    links = []
    for arch, branch, path, data in entries:
        output = site / path; output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(data, indent=2) + "\n")
        repo_text, ref_text = descriptors(url, key, branch)
        (site / "nova.flatpakrepo").write_text(repo_text)
        # Flatpak chooses the installation architecture. These descriptors stay
        # universal; architecture identity belongs to the ref and advisory JSON.
        (site / f"nova-{branch}.flatpakref").write_text(ref_text)
        arch_label = " (aarch64)" if arch == "aarch64" else ""
        links.append(f'<li><a href="nova-{branch}.flatpakref">Install Nova ({branch})</a>: {html.escape(data["version"])}{arch_label}</li>')
    (site / "index.html").write_text(
        '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
        '<title>Nova for Linux</title><main><h1>Nova for Linux</h1>'
        '<p>Choose a channel and open its installer in your software manager. '
        'Nova can then check for updates from Settings → Nova Updates.</p><ul>' + "".join(links) + '</ul>'
        '<p>Beta builds are previews. Both channels include PyroWave; '
        'Nova checks your device only when you choose it.</p>'
        '<p>Your pairing and settings are kept when updating the same Nova app. '
        'Existing standalone downloads need this one-time channel installation.</p>'
        '<p><a href="https://github.com/papi-ux/nova/releases">Release notes and downloads</a></p></main></html>\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate-release")
    validate.add_argument("release", type=Path)
    validate.add_argument("--channel", choices=CHANNELS, required=True)
    suffix_cmd = commands.add_parser("version-suffix")
    suffix_cmd.add_argument("version")
    release_cmd = commands.add_parser("prepare-release")
    release_cmd.add_argument("manifest", type=Path)
    release_cmd.add_argument("output", type=Path)
    release_cmd.add_argument("--version")
    prepare_cmd = commands.add_parser("prepare")
    prepare_cmd.add_argument("manifest", type=Path)
    prepare_cmd.add_argument("output", type=Path)
    prepare_cmd.add_argument("--channel", choices=CHANNELS, required=True)
    prepare_cmd.add_argument("--url", required=True)
    prepare_cmd.add_argument("--version", required=True)
    site_cmd = commands.add_parser("site")
    for name in ("repository", "site", "previous", "key"):
        site_cmd.add_argument("--" + name, type=Path, required=True)
    site_cmd.add_argument("--channel", choices=CHANNELS, required=True)
    site_cmd.add_argument("--version", required=True)
    site_cmd.add_argument("--url", required=True)
    site_cmd.add_argument("--arch", choices=ARCHITECTURES, default="x86_64")
    args = parser.parse_args()
    if args.command == "validate-release":
        validate_release(json.loads(args.release.read_text()), args.channel)
    elif args.command == "version-suffix":
        print(version_suffix(args.version))
    elif args.command in ("prepare", "prepare-release"):
        if args.output.parent.resolve() != args.manifest.parent.resolve():
            raise ValueError("Keep generated manifest beside the original to preserve relative sources")
        manifest = json.loads(args.manifest.read_text())
        prepared = prepare(manifest, args.channel, args.url, args.version) if args.command == "prepare" else prepare_release(manifest, args.version)
        args.output.write_text(json.dumps(prepared, indent=2) + "\n")
    else:
        write_site(args.repository, args.site, args.previous, args.channel, args.version, args.url, args.key.read_bytes(), args.arch)


if __name__ == "__main__":
    main()
