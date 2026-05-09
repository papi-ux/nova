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
PY

expected_arm64_asset="Nova-Android-arm64-v8a.apk"
expected_x86_asset="Nova-Android-x86_64.apk"
expected_latest_arm64_url="https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-arm64-v8a.apk"
expected_latest_x86_url="https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-x86_64.apk"
expected_github_store_url="https://github-store.org/app?repo=papi-ux/nova"
expected_obtainium_version_regex="versionExtractionRegEx%5C%22%3A%5C%22v%28.%2B%29"
expected_obtainium_apk_regex="Nova-Android-arm64-v8a%5C%5C%5C%5C.apk%24"

grep -Fq "$expected_arm64_asset" README.md
grep -Fq "$expected_x86_asset" README.md
grep -Fq "$expected_latest_arm64_url" README.md
grep -Fq "$expected_latest_x86_url" README.md
grep -Fq "$expected_github_store_url" README.md
grep -Fq "$expected_obtainium_version_regex" README.md
grep -Fq "$expected_obtainium_apk_regex" README.md
grep -Fq "arm64-v8a,x86_64" app/build.gradle
grep -Fq "unsigned_apks=(\"\${APK_DIR}\"/*release-unsigned.apk)" .github/workflows/build.yml
grep -Fq 'gh release upload "${GITHUB_REF_NAME}" "${release_assets[@]}" --clobber' .github/workflows/build.yml
grep -Fq "Nova-Android-\${abi}.apk" .github/workflows/build.yml
grep -Fq "F-Droid and IzzyOnDroid packaging notes" README.md
grep -Fq 'buildConfigField "boolean", "FDROID_BUILD"' app/build.gradle
grep -Fq "BuildConfig.FDROID_BUILD" app/src/main/java/com/papi/nova/preferences/StreamSettings.java

metadata_dir="fastlane/metadata/android/en-US"
required_metadata=(
  "$metadata_dir/title.txt"
  "$metadata_dir/short_description.txt"
  "$metadata_dir/full_description.txt"
  "$metadata_dir/changelogs/16.txt"
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

echo "Public docs and release references look clean."
