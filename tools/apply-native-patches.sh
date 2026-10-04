#!/usr/bin/env bash
# Apply Nova's protocol and upstream hardening patches to its pinned core.
#
# The submodule is pinned at its upstream commit and Nova's protocol changes live beside it as a patch
# series, so every checkout starts unpatched. Any build that compiles native code has to run this
# first: without it the library does not know the format, and a client that asks for PyroWave gets a
# session that negotiates something else while Nova has already built a PyroWave renderer. The Gradle
# guard verifyMoonlightCommonCSubmodule refuses that build, which is how a workflow that forgets this
# step fails loudly rather than shipping an APK that cannot negotiate.
#
# One script rather than a step copied into each workflow, because the copies drift and the one that
# is missing is the one nobody notices. tools/test_native_submodule_preflight.py requires every
# workflow that assembles to call it.
#
# Idempotent: a tree that already carries the patches is left alone, so a developer can run it after
# a submodule update without thinking about whether they already did.
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
submodule="$repo_root/app/src/main/jni/moonlight-core/moonlight-common-c"
patches="$repo_root/app/src/main/jni/moonlight-core/patches"

if [ ! -f "$submodule/src/RtspConnection.c" ]; then
  echo "moonlight-common-c is not initialised. Run: git submodule update --init --recursive" >&2
  exit 1
fi

cd "$submodule"

# A protocol marker alone cannot prove that a newer hardening patch was applied.
# Compare stable patch IDs so an existing eight-patch checkout can receive only
# the new patches, without reapplying or discarding its earlier commits.
if [ "$(git rev-parse --show-toplevel)" != "$submodule" ]; then
  echo "moonlight-common-c must be its own initialized Git checkout" >&2
  exit 1
fi
base_pin="$(git -C "$repo_root" rev-parse HEAD:app/src/main/jni/moonlight-core/moonlight-common-c)"
git merge-base --is-ancestor "$base_pin" HEAD
applied_ids="$(git log --format=%H -p "$base_pin"..HEAD | git patch-id --stable | awk '{print $1}')"
for native_patch in "$patches"/*.patch; do
  patch_id="$(git patch-id --stable < "$native_patch" | awk '{print $1}')"
  if [ -z "$patch_id" ]; then
    echo "Native patch has no diff: $native_patch" >&2
    exit 1
  fi
  if ! printf '%s\n' "$applied_ids" | grep -Fxq "$patch_id"; then
    git -c user.name=nova -c user.email=nova@papi-ux.com am "$native_patch"
  fi
done

# Asserted rather than trusted: a partial apply leaves a library that builds and cannot negotiate,
# which is the whole failure this exists to prevent.
grep -q PYROWAVE_PROFILE_TOKEN src/RtspConnection.c
grep -q MAX_RTSP_RESPONSE_SIZE src/RtspConnection.c
grep -q 'sessionToken == NULL' src/RtspConnection.c
echo "Nova's protocol and RTSP hardening patches are applied."
