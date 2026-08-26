#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

python3 <<'PY'
from pathlib import Path
import re
import sys

root = Path.cwd()
readme = root / "README.md"
text = readme.read_text(encoding="utf-8")

if len(re.findall(r"\b[\w'-]+\b", text)) >= 1800:
    print("README must remain below 1,800 words", file=sys.stderr)
    sys.exit(1)
if re.search(r"(?im)^#{1,6}\s+.*(?:what(?:'s| is) new|latest release|release)\s*:?.*v\d", text):
    print("README must not duplicate a version-specific release section", file=sys.stderr)
    sys.exit(1)

required_links = (
    "https://papi-ux.com/nova/",
    "https://papi-ux.com/nova/#themes",
    "https://papi-ux.com/docs/nova/",
    "https://papi-ux.com/docs/nova/quickstart/",
    "https://papi-ux.com/docs/nova/compatibility/",
    "https://papi-ux.com/docs/roadmap/",
    "https://matrix.to/#/#papi-ux:papi-ux.com",
    "https://github.com/papi-ux/nova/releases/latest",
    "CHANGELOG.md",
    "SECURITY.md",
    ".github/CONTRIBUTING.md",
)
for link in required_links:
    if link not in text:
        print(f"README is missing canonical link: {link}", file=sys.stderr)
        sys.exit(1)

targets = set()

for match in re.finditer(r'\]\(([^)]+)\)', text):
    target = match.group(1).strip()
    if "://" in target or target.startswith("#") or target.startswith("mailto:"):
        continue
    target = target.split("#", 1)[0].split("?", 1)[0]
    if target:
        targets.add(target)

for match in re.finditer(r'(?:src|srcset)=["\']([^"\']+)["\']', text):
    target = match.group(1).strip().split(",", 1)[0].strip().split(" ", 1)[0]
    if "://" in target or target.startswith("data:") or target.startswith("#"):
        continue
    target = target.split("#", 1)[0].split("?", 1)[0]
    if target:
        targets.add(target)

missing = sorted(str(path) for path in targets if not (root / path).exists())
if missing:
    print("README references missing local files:", file=sys.stderr)
    for path in missing:
        print(f"  - {path}", file=sys.stderr)
    sys.exit(1)

media = [root / target for target in targets if (root / target).suffix.lower() in {".gif", ".png", ".webp", ".webm", ".mp4"}]
media_bytes = sum(path.stat().st_size for path in media)
if media_bytes >= 1_000_000:
    print(f"README embedded media must remain below 1 MB; found {media_bytes} bytes", file=sys.stderr)
    sys.exit(1)
PY

expected_arm64_asset="Nova-Android-arm64-v8a.apk"
expected_armv7_asset="Nova-Android-armeabi-v7a.apk"
expected_x86_asset="Nova-Android-x86_64.apk"
expected_latest_arm64_url="https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-arm64-v8a.apk"
expected_latest_armv7_url="https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-armeabi-v7a.apk"
expected_latest_x86_url="https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-x86_64.apk"

grep -Fq "$expected_arm64_asset" README.md
grep -Fq "$expected_armv7_asset" README.md
grep -Fq "$expected_x86_asset" README.md
grep -Fq "$expected_latest_arm64_url" README.md
grep -Fq "$expected_latest_armv7_url" README.md
grep -Fq "$expected_latest_x86_url" README.md
grep -Fq "arm64-v8a,armeabi-v7a,x86_64" app/build.gradle
grep -Fq "unsigned_apks=(\"\${APK_DIR}\"/*release-unsigned.apk)" .github/workflows/build.yml
grep -Fq 'gh release upload "${GITHUB_REF_NAME}" "${release_assets[@]}" --clobber' .github/workflows/build.yml
grep -Fq 'python3 scripts/extract_release_notes.py "${GITHUB_REF_NAME}" > "$release_notes"' .github/workflows/build.yml
grep -Fq -- '--notes-file "$release_notes"' .github/workflows/build.yml
grep -Fq 'gh release edit "${GITHUB_REF_NAME}"' .github/workflows/build.yml
grep -Fq 'published_notes="$(gh release view "${GITHUB_REF_NAME}" --json body --jq .body)"' .github/workflows/build.yml
grep -Fq 'ref: ${{ github.sha }}' .github/workflows/build.yml
grep -Fq 'EXPECTED_SOURCE_COMMIT: ${{ needs.verify.outputs.source_commit }}' .github/workflows/build.yml
grep -Fq 'git fetch --no-tags --force origin' .github/workflows/build.yml
grep -Fq -- '--draft' .github/workflows/build.yml
grep -Fq -- '--draft=true' .github/workflows/build.yml
grep -Fq -- '--draft=false' .github/workflows/build.yml
if grep -Fq -- '--generate-notes' .github/workflows/build.yml; then
  echo "Release workflow must publish the curated changelog section, not generated notes." >&2
  exit 1
fi
python3 - <<'PY'
from pathlib import Path

workflow = Path(".github/workflows/build.yml").read_text(encoding="utf-8")
steps = (
    "Verify release tag matches built source",
    "Stage curated GitHub release",
    "Upload GitHub Release assets",
    "Verify GitHub Release assets",
    "Publish verified draft release",
)
positions = [workflow.find(f"      - name: {step}\n") for step in steps]
if -1 in positions or positions != sorted(positions):
    raise SystemExit("Release source, notes, assets, and publication steps must stay ordered")
if workflow.count("ref: ${{ github.sha }}") != 3:
    raise SystemExit("Every Nova build lane must check out the exact event source")

stage_start = positions[1]
upload_start = positions[2]
stage = workflow[stage_start:upload_start]
if stage.count("--verify-tag") != 2 or "gh release upload" in stage:
    raise SystemExit("Release create and edit must verify the tag before any asset upload")
if stage.count('echo "publish_draft=true"') != 2 or "is_draft=" in stage:
    raise SystemExit("Both new and existing releases must remain draft until verification")
if stage.find("published_notes=") < stage.find("gh release edit"):
    raise SystemExit("Release notes must be read back after staging")

expected_assets = (
    "Nova-Android-arm64-v8a.apk",
    "Nova-Android-arm64-v8a.apk.sha256",
    "Nova-Android-armeabi-v7a.apk",
    "Nova-Android-armeabi-v7a.apk.sha256",
    "Nova-Android-x86_64.apk",
    "Nova-Android-x86_64.apk.sha256",
)
verify = workflow[positions[3]:positions[4]]
verify_lines = [line.strip() for line in verify.splitlines()]
for asset in expected_assets:
    if verify_lines.count(asset) != 1:
        raise SystemExit(f"Release verification must name {asset} exactly once")
PY
python3 scripts/test_extract_release_notes.py
grep -Fq "Nova-Android-\${abi}.apk" .github/workflows/build.yml
grep -Fq "F-Droid and IzzyOnDroid Packaging Notes" docs/fdroid.md
grep -Fq 'buildConfigField "boolean", "FDROID_BUILD"' app/build.gradle
grep -Fq "BuildConfig.FDROID_BUILD" app/src/main/java/com/papi/nova/preferences/StreamSettings.kt

metadata_dir="fastlane/metadata/android/en-US"
required_metadata=(
  "$metadata_dir/title.txt"
  "$metadata_dir/short_description.txt"
  "$metadata_dir/full_description.txt"
  "$metadata_dir/changelogs/16.txt"
  "$metadata_dir/changelogs/38.txt"
  "$metadata_dir/changelogs/41.txt"
  "$metadata_dir/images/icon.png"
  "docs/fdroid.md"
)

for path in "${required_metadata[@]}"; do
  if [[ ! -s "$path" ]]; then
    echo "Missing or empty store metadata file: $path" >&2
    exit 1
  fi
done

short_description_len="$(python3 - <<'PY'
from pathlib import Path
print(len(Path("fastlane/metadata/android/en-US/short_description.txt").read_text(encoding="utf-8").strip()))
PY
)"
if [[ "$short_description_len" -gt 80 ]]; then
  echo "Fastlane short description is ${short_description_len} characters; keep it at 80 or less." >&2
  exit 1
fi

phone_screenshot_count="$(find "$metadata_dir/images/phoneScreenshots" -type f -name '*.png' | wc -l)"
if [[ "$phone_screenshot_count" -lt 4 ]]; then
  echo "Expected at least 4 phone screenshots for store metadata, found $phone_screenshot_count." >&2
  exit 1
fi

expected_github_store_url="https://github-store.org/app?repo=papi-ux/nova"
expected_obtainium_version_regex="versionExtractionRegEx%5C%22%3A%5C%22v%28.%2B%29"
expected_obtainium_apk_regex="Nova-Android-arm64-v8a%5C%5C%5C%5C.apk%24"
grep -Fq "$expected_github_store_url" README.md
grep -Fq "$expected_obtainium_version_regex" README.md
grep -Fq "$expected_obtainium_apk_regex" README.md

if ! grep -Fq "https://papi-ux.com/images/products/showcase-v1.3.8-v1.3.6-provenance.json" README.md; then
  echo "README must link the published provenance manifest." >&2
  exit 1
fi

if ! grep -Fq "## AI Transparency" README.md; then
  echo "README must keep the AI Transparency section." >&2
  exit 1
fi

echo "Public docs and release references look clean."
