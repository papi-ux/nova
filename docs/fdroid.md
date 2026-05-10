# F-Droid and IzzyOnDroid Packaging Notes

Nova is prepared for release-APK distribution and is being prepared for F-Droid-compatible source builds.

## Current Status

| Channel | Status | Notes |
|---|---|---|
| IzzyOnDroid | Declined for now | App request #159 was declined under IzzyOnDroid's current policy/space limits for games and gaming-focused apps. Nova remains prepared for a future re-review if that policy changes or an exception path opens. |
| F-Droid main | Not ready yet | The Android build still consumes prebuilt native static libraries for OpenSSL and Opus. F-Droid main generally expects those dependencies to be built from source in the build recipe. |
| Self-hosted F-Droid repo | Possible | The existing GitHub release APKs can be indexed in a self-hosted repo, but users would need to add that repo manually. |

## App Metadata

Fastlane metadata lives in:

```text
fastlane/metadata/android/en-US/
```

The metadata is intentionally written for public stores and app-index mirrors:

- title and short description are store-sized
- full description avoids marketing-only language and names the Polaris relationship clearly
- screenshots are copied from `docs/screenshots/`
- changelog `18.txt` matches Nova `versionCode = 18`

## APK Scan and Security Notes

The `v1.0.2` release hardens the security surfaces that were raised during public scanner review:

- externally supplied host and path handling in Moonlight/Polaris HTTP flows
- poster/content URI handling used by launcher and TV surfaces
- cache path validation for app artwork
- stream notification PendingIntent creation

IzzyOnDroid's APK scan also flagged two manifest items that are expected for Nova:

- `android.permission.CAMERA` is used for QR pairing with a Polaris host. The camera hardware feature is declared optional.
- `android.accessibilityservice.AccessibilityService` is used by the optional physical keyboard helper for shortcut passthrough while streaming. The service is not exported, requires Android's `BIND_ACCESSIBILITY_SERVICE` permission, and must be enabled by the user in system settings.

## F-Droid Build Switch

Nova supports a build-time switch for F-Droid-style builds:

```bash
./gradlew -PnovaFdroid=true -PnovaAbis=arm64-v8a assembleNonRoot_gameRelease
```

That switch sets `BuildConfig.FDROID_BUILD=true` and removes the GitHub Releases and Obtainium update shortcuts from the app settings UI. It does not change the package name, signing, streaming behavior, or Polaris integration.

## Known F-Droid Main Blockers

The current native build imports prebuilt static libraries:

```text
app/src/main/jni/moonlight-core/libopus/*/libopus.a
app/src/main/jni/moonlight-core/openssl/*/libcrypto.a
app/src/main/jni/moonlight-core/openssl/*/libssl.a
```

Those libraries are documented upstream in `app/src/main/jni/moonlight-core/Build.txt`, but for F-Droid main they should be replaced by a source build in the F-Droid recipe or in Nova's Gradle/NDK build.

The practical path is:

1. Add source-fetch/build steps for Opus and OpenSSL.
2. Wire the generated static libraries into the existing NDK build.
3. Build at least `arm64-v8a` with `-PnovaFdroid=true`.
4. Validate the resulting APK against the same runtime smoke path used by CI.
5. Submit `metadata/com.papi.nova.yml` to `fdroiddata` once the native dependency path no longer uses checked-in prebuilt libraries.

## Suggested fdroiddata Shape

This is a starting point, not a ready-to-submit recipe:

```yaml
Categories:
  - Multimedia
License: GPL-3.0-only
AuthorName: papi-ux
SourceCode: https://github.com/papi-ux/nova
IssueTracker: https://github.com/papi-ux/nova/issues
Changelog: https://github.com/papi-ux/nova/blob/master/CHANGELOG.md

AutoName: Nova

RepoType: git
Repo: https://github.com/papi-ux/nova.git

Builds:
  - versionName: 1.0.3
    versionCode: 18
    commit: v1.0.3
    subdir: .
    gradle:
      - nonRoot_gameRelease
    gradleprops:
      - novaFdroid=true
      - novaAbis=arm64-v8a

AutoUpdateMode: Version v%v
UpdateCheckMode: Tags
CurrentVersion: 1.0.3
CurrentVersionCode: 18
```

`v1.0.3` contains the F-Droid build switch, store metadata, public scanner hardening, and the latest Wake-on-LAN setup fixes. Official F-Droid main still needs the source-built native dependency path described above; do not submit this sample against `v1.0.0` because that tag predates the packaging prep.

## Future IzzyOnDroid Checklist

Nova's first IzzyOnDroid request was declined because Nova is game-streaming/gaming-focused. If IzzyOnDroid's category policy changes or a specific exception path becomes available, confirm the following before requesting re-review:

- confirm the latest GitHub release has signed APK assets
- confirm the release contains `Nova-Android-arm64-v8a.apk` and `Nova-Android-x86_64.apk`
- confirm `fastlane/metadata/android/en-US` is present in the default branch
- mention the `v1.0.2` scanner hardening and link this document's APK scan notes
- mention that Nova is Android-only and currently publishes `arm64-v8a` and `x86_64` APKs
- mention that Nova is a game streaming client, not a bundled game
- mention that Polaris-related tuning indicators are local host state, not a cloud AI service
- link the release page: `https://github.com/papi-ux/nova/releases/latest`

After IzzyOnDroid accepts Nova, add an IzzyOnDroid badge to the README beside the GitHub, GitHub Store, and Obtainium links.
