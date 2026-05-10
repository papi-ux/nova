# Changelog

## 1.0.3 - 2026-05-09

- Added manual Wake-on-LAN MAC entry for hosts that do not report a MAC address.
- Normalized common MAC address formats before sending Wake-on-LAN packets.
- Updated public README guidance for Wake-on-LAN, VPN, and remote-host setup.

## 1.0.2 - 2026-04-28

- Hardened externally supplied host, URI, and cache path handling found during CodeQL/security review.
- Made stream notification actions use explicit immutable PendingIntents.
- Updated public README and F-Droid/IzzyOnDroid notes with the current packaging, scan, and inclusion status.

## 1.0.1 - 2026-04-25

- Added Fastlane metadata, screenshots, and F-Droid/IzzyOnDroid packaging notes for public store review.
- Added the `novaFdroid` build switch so F-Droid-style builds can hide GitHub and Obtainium update shortcuts.
- Renamed public APK release assets to `Nova-Android-arm64-v8a.apk` and `Nova-Android-x86_64.apk`, with stable latest-download links through GitHub Releases.

## 1.0.0 - 2026-04-20

- Prepared Nova for the first public `v1.0.0` release line with refreshed public-facing docs, repo hygiene checks, and release metadata.
- Tightened the public repo surface rules so maintainer-local files and session checkpoint notes are blocked from the tracked tree.
- Landed the current Polaris-aware Android client surface:
  richer session guidance, launch-mode awareness, reliability feedback, library polish, and live quick-menu controls.

## 2026-04-17

### Release and repo hygiene

- Tightened the public Obtainium configuration so it resolves directly to `app-nonRoot_game-arm64-v8a-release.apk` and tracks `v*` GitHub tags cleanly.
- Refreshed the public docs around the multi-platform repo layout, Steam Deck planning notes, and release/install guidance.
- Updated the Android CI workflows to use the current `setup-android` action and restored the emulator smoke test's hosted-runner KVM setup.

### Polaris-aware library and AI surfaces

- Added clearer Polaris-backed session labels across the library, quick menu, HUD, and detail surfaces:
  `Baseline`, `AI tune`, `Cached AI`, `Recovery tune`, and `Host adjusted`.
- Improved the host-specific Polaris library screen with a stronger featured `Continue` card:
  live/watch state, cover art, summary text, and a clearer primary action.
- Exposed richer Polaris session metadata in Nova so launch and in-stream UI can reflect source, confidence, freshness, and host-side normalization more accurately.

### Session reporting and grading alignment

- Extended Nova's Polaris reporting path so end-of-session data includes the target FPS used for host-side grading.
- Updated Polaris parsing coverage in Nova tests to keep the newer host metadata contract stable.
