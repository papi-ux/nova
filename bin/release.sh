#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

declared_version="$(grep 'versionName' app/build.gradle | head -1 | sed 's/.*"\(.*\)"/\1/')"
requested="${1:-$declared_version}"
version="${requested%%-*}"
channel_suffix="${requested#"$version"}"
if [[ "$version" != "$declared_version" ]]; then
  echo "Requested release version ${version} does not match app version ${declared_version}." >&2
  exit 1
fi
if [[ -n "$channel_suffix" && ! "$channel_suffix" =~ ^-(beta|rc)\.[0-9]+$ ]]; then
  echo "Release channel suffix must be -beta.N or -rc.N; got '${channel_suffix}'." >&2
  exit 1
fi

# A beta is built as preRelease, which installs beside a stable Nova rather than over it. The
# validation build below is the same variant CI will build from the tag.
if [[ -n "$channel_suffix" ]]; then
  build_type=PreRelease
  gradle_channel_flags=("-PnovaVersionSuffix=${channel_suffix}")
else
  build_type=Release
  gradle_channel_flags=()
fi

tag="v${requested}"

require_exact_master_head() {
  local current_branch local_head remote_master

  current_branch="$(git symbolic-ref --quiet --short HEAD || true)"
  if [[ "$current_branch" != "master" ]]; then
    echo "Release tagging requires the local master branch; found '${current_branch:-detached HEAD}'." >&2
    exit 1
  fi

  git fetch --no-tags origin refs/heads/master:refs/remotes/origin/master
  local_head="$(git rev-parse HEAD)"
  remote_master="$(git rev-parse refs/remotes/origin/master)"
  if [[ "$local_head" != "$remote_master" ]]; then
    echo "Release HEAD ${local_head} does not match origin/master ${remote_master}." >&2
    exit 1
  fi
}

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

require_exact_master_head

bash scripts/check-public-docs.sh
bash scripts/check-public-surface.sh
./gradlew -PnovaAbis=arm64-v8a,armeabi-v7a,x86_64 "${gradle_channel_flags[@]}" "assembleNonRoot_game${build_type}"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Release validation changed the working tree; refusing to tag." >&2
  exit 1
fi
require_exact_master_head

git tag -a "$tag" -m "Nova ${tag}"
if ! git push --atomic origin \
  HEAD:refs/heads/master \
  "refs/tags/${tag}:refs/tags/${tag}"
then
  git tag -d "$tag" >/dev/null
  echo "Atomic master/tag publication failed; removed local ${tag}." >&2
  exit 1
fi

cat <<EOF
Tagged ${tag}.

GitHub Actions will create or update the public release and upload:
  - Nova-Android-arm64-v8a.apk
  - Nova-Android-armeabi-v7a.apk
  - Nova-Android-x86_64.apk
  - matching .sha256 files

Release URL:
  https://github.com/papi-ux/nova/releases/tag/${tag}
EOF
