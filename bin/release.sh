#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

version="${1:-}"
if [[ -z "$version" ]]; then
  version="$(grep 'versionName' app/build.gradle | head -1 | sed 's/.*"\(.*\)"/\1/')"
fi

tag="v${version}"

if git rev-parse "$tag" >/dev/null 2>&1; then
  echo "Tag ${tag} already exists locally." >&2
  exit 1
fi

if git ls-remote --exit-code --tags origin "$tag" >/dev/null 2>&1; then
  echo "Tag ${tag} already exists on origin." >&2
  exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree is not clean. Commit release prep before tagging." >&2
  exit 1
fi

bash scripts/check-public-docs.sh
bash scripts/check-public-surface.sh
./gradlew -PnovaAbis=arm64-v8a,x86_64 assembleNonRoot_gameRelease

git push origin master
git tag -a "$tag" -m "Nova ${tag}"
git push origin "$tag"

cat <<EOF
Tagged ${tag}.

GitHub Actions will create or update the public release and upload:
  - Nova-Android-arm64-v8a.apk
  - Nova-Android-x86_64.apk
  - matching .sha256 files

Release URL:
  https://github.com/papi-ux/nova/releases/tag/${tag}
EOF
