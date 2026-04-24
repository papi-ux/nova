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

expected_asset="app-nonRoot_game-arm64-v8a-release.apk"
expected_asset_prefix="app-nonRoot_game-arm64-v8a-release"
expected_github_store_url="https://github-store.org/app?repo=papi-ux/nova"
expected_obtainium_version_regex="versionExtractionRegEx%5C%22%3A%5C%22v%28.%2B%29"

grep -Fq "$expected_asset" README.md
grep -Fq "$expected_github_store_url" README.md
grep -Fq "$expected_obtainium_version_regex" README.md
grep -Fq "$expected_asset_prefix" app/build.gradle
grep -Fq "find app/build/outputs/apk/nonRoot_game/release -name '*arm64*unsigned*.apk'" .github/workflows/build.yml
grep -Fq 'gh release upload "${GITHUB_REF_NAME}" "${APK_PATH}" "${APK_PATH}.sha256" --clobber' .github/workflows/build.yml

echo "Public docs and release references look clean."
