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
CHANNELS = ("stable", "beta", "pyrowave")
COMMIT = re.compile(r"[0-9a-f]{64}\Z")
TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?\Z")


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
        raise ValueError("Experimental channels require an explicitly marked prerelease")


def prepare(manifest, channel, url):
    if channel not in CHANNELS or manifest.get("app-id") != APP:
        raise ValueError("Wrong app or channel")
    # Work on a copy, so build helpers cannot change the checked-in manifest.
    manifest = json.loads(json.dumps(manifest))
    module = next(item for item in manifest["modules"] if isinstance(item, dict) and item.get("name") == "nova-deck")
    opts = module["config-opts"]
    enabled = "-DNOVA_DECK_BUILD_PYROWAVE=ON" in opts
    if enabled != (channel == "pyrowave"):
        raise ValueError("PyroWave builds must use the separate pyrowave channel")
    opts[:] = [v for v in opts if not v.startswith(("-DNOVA_DECK_UPDATE_CHANNEL=", "-DNOVA_DECK_UPDATE_URL="))]
    opts.extend([f"-DNOVA_DECK_UPDATE_CHANNEL={channel}", f"-DNOVA_DECK_UPDATE_URL={feed_url(url)}"])
    manifest["branch"] = channel
    return manifest


def catalog(channel, revision, version):
    if channel not in CHANNELS or not COMMIT.fullmatch(revision) or not TAG.fullmatch(version):
        raise ValueError("Invalid channel catalog")
    return {"appId": APP, "channel": channel, "arch": "x86_64", "commit": revision, "version": version}


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


def write_site(repository, site, previous, channel, version, url, key):
    site.mkdir(parents=True, exist_ok=True)
    refs = subprocess.check_output(["ostree", f"--repo={repository}", "refs"], text=True).splitlines()
    if f"app/{APP}/x86_64/{channel}" not in refs:
        raise ValueError("Selected channel is missing from the exported repository")
    links = []
    for branch in CHANNELS:
        ref = f"app/{APP}/x86_64/{branch}"
        if ref not in refs:
            continue
        revision = subprocess.check_output(["ostree", f"--repo={repository}", "rev-parse", ref], text=True).strip()
        if branch == channel:
            data = catalog(branch, revision, version)
        else:
            # Preserve other channels, but never retain metadata for a different
            # commit than the one pulled from the signature-verified repository.
            data = json.loads((previous / f"{branch}.json").read_text())
            if data != catalog(branch, revision, data.get("version", "")):
                raise ValueError("Previous channel catalog does not match its signed commit")
        (site / f"{branch}.json").write_text(json.dumps(data, indent=2) + "\n")
        repo_text, ref_text = descriptors(url, key, branch)
        (site / "nova.flatpakrepo").write_text(repo_text)
        (site / f"nova-{branch}.flatpakref").write_text(ref_text)
        links.append(f'<li><a href="nova-{branch}.flatpakref">Install Nova ({branch})</a> — {html.escape(data["version"])}</li>')
    (site / "index.html").write_text(
        '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
        '<title>Nova for Linux</title><main><h1>Nova for Linux</h1>'
        '<p>Choose a channel and open its installer in your software manager. '
        'Nova can then check for updates from Settings → Nova Updates.</p><ul>' + "".join(links) + '</ul>'
        '<p>Beta builds are previews. The PyroWave channel includes an experimental SDR codec '
        'and stays separate from regular builds.</p>'
        '<p>Your pairing and settings are kept when updating the same Nova app. '
        'Existing standalone downloads need this one-time channel installation.</p>'
        '<p><a href="https://github.com/papi-ux/nova/releases">Release notes and downloads</a></p></main></html>\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate-release")
    validate.add_argument("release", type=Path)
    validate.add_argument("--channel", choices=CHANNELS, required=True)
    prepare_cmd = commands.add_parser("prepare")
    prepare_cmd.add_argument("manifest", type=Path)
    prepare_cmd.add_argument("output", type=Path)
    prepare_cmd.add_argument("--channel", choices=CHANNELS, required=True)
    prepare_cmd.add_argument("--url", required=True)
    site_cmd = commands.add_parser("site")
    for name in ("repository", "site", "previous", "key"):
        site_cmd.add_argument("--" + name, type=Path, required=True)
    site_cmd.add_argument("--channel", choices=CHANNELS, required=True)
    site_cmd.add_argument("--version", required=True)
    site_cmd.add_argument("--url", required=True)
    args = parser.parse_args()
    if args.command == "validate-release":
        validate_release(json.loads(args.release.read_text()), args.channel)
    elif args.command == "prepare":
        if args.output.parent.resolve() != args.manifest.parent.resolve():
            raise ValueError("Keep generated manifest beside the original to preserve relative sources")
        args.output.write_text(json.dumps(prepare(json.loads(args.manifest.read_text()), args.channel, args.url), indent=2) + "\n")
    else:
        write_site(args.repository, args.site, args.previous, args.channel, args.version, args.url, args.key.read_bytes())


if __name__ == "__main__":
    main()
