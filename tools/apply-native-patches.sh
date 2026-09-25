#!/usr/bin/env bash
# Apply Nova's PyroWave protocol patches to the pinned moonlight-common-c checkout.
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

if grep -q PYROWAVE_PROFILE_TOKEN src/RtspConnection.c; then
  echo "moonlight-common-c already carries the protocol patches; nothing to do."
  exit 0
fi

git -c user.name=nova -c user.email=nova@papi-ux.com am "$patches"/*.patch

# Asserted rather than trusted: a partial apply leaves a library that builds and cannot negotiate,
# which is the whole failure this exists to prevent.
grep -q PYROWAVE_PROFILE_TOKEN src/RtspConnection.c
echo "Applied Nova's PyroWave protocol patches."
