#!/bin/bash
set -e

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  # No version arg — re-release current version (overwrite existing release)
  VERSION=$(grep 'versionName' app/build.gradle | head -1 | sed 's/.*"\(.*\)"/\1/')
  echo "Re-releasing v${VERSION}"
else
  echo "Releasing v${VERSION}"

  # Bump versionCode (increment from current)
  CURRENT_CODE=$(grep 'versionCode' app/build.gradle | head -1 | sed 's/[^0-9]//g')
  NEW_CODE=$((CURRENT_CODE + 1))

  # Update build.gradle
  sed -i "s/versionName \".*\"/versionName \"${VERSION}\"/" app/build.gradle
  sed -i "s/versionCode = .*/versionCode = ${NEW_CODE}/" app/build.gradle
  echo "  versionName: ${VERSION}, versionCode: ${NEW_CODE}"
fi

# Build
echo "Building..."
./gradlew assembleNonRoot_gameRelease -q

APK="app/build/outputs/apk/nonRoot_game/release/app-nonRoot_game-arm64-v8a-release.apk"
if [ ! -f "$APK" ]; then
  echo "ERROR: APK not found at $APK"
  exit 1
fi

# Commit if there are changes
if ! git diff --quiet app/build.gradle 2>/dev/null; then
  git add app/build.gradle
  git commit -m "release: Nova v${VERSION}"
fi

# Push
git push origin master

# Delete existing release/tag if present
gh release delete "v${VERSION}" --repo papi-ux/nova --yes 2>/dev/null || true
git tag -d "v${VERSION}" 2>/dev/null || true
git push origin --delete "v${VERSION}" 2>/dev/null || true

# Create release
gh release create "v${VERSION}" "$APK" \
  --repo papi-ux/nova \
  --title "Nova v${VERSION}" \
  --generate-notes

echo ""
echo "Released Nova v${VERSION}"
echo "  APK: $APK"
echo "  URL: https://github.com/papi-ux/nova/releases/tag/v${VERSION}"
