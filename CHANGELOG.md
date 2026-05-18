# Changelog

## 1.0.8 - 2026-05-18

- Strengthened controller and TV focus states in the dashboard, library game grid, and launch affordances so selected items stand out more clearly.
- Tuned Nova Library density for handheld landscape devices with a 4-card Continue rail, 3-column default grid, shorter game cards, and smaller HDR/Recent badges.
- Pointed the Help/GitHub menu action at Nova's GitHub repository instead of Moonlight.
- Routed dashboard Polaris startup and app metadata refresh work through Nova's lifecycle-aware runtime task helpers.
- Added Baseline Profile generation infrastructure for startup and library flows, with release dry-run coverage in the build.
- Reduced library filtering allocations and added stable Lazy layout content types for the library surface.
- Added Kotlin domain ID value classes plus a build guard that keeps kapt out of Nova's Kotlin build.

## 1.0.7 - 2026-05-18

- Added a public `armeabi-v7a` APK split for Chromecast with Google TV, Google TV Streamer, and other Android TV devices that expose only 32-bit ARM app support.
- Recognized common Steam Controller 2026 Bluetooth keyboard/mouse HID shapes across Android devices as controller input so Nova advertises a host gamepad and routes compatible D-pad/button events through the controller path.
- Added controller shortcuts for in-stream control: Guide/Mode + Start/Menu opens Nova's quick menu, and Guide/Mode + Y shows or cycles NovaHUD.
- Added a Start Polaris action that sends Wake-on-LAN to a paired host, waits for the Polaris Library API to become available, then opens Nova Library.
- Updated the release workflow to name, upload, and verify the 32-bit ARM APK plus checksum alongside the existing ARM64 and x86_64 assets.
- Refreshed README install guidance so users can choose the correct APK for ARM64 Android TV, Chromecast/32-bit ARM Android TV, and x86_64 Android devices.

## 1.0.6 - 2026-05-18

- Completed the Kotlin migration for the Android client runtime, UI/support layers, streaming contracts, and regression tests.
- Hardened stream lifecycle handling around runtime task cleanup, cursor visibility sync, controller button release scheduling, disconnect/resume, and video diagnostics.
- Aligned Polaris-backed launches with the paired RTSP profile Nova receives from the host before stream start.
- Improved HUD and Auto Quality evidence with independent latency samples, sanitized session summaries, target FPS, 1% low FPS, and video metric guard coverage.
- Refreshed dependency submission, CodeQL path guard, Gradle task wiring, release-readiness docs, and public README notes for the `v1.0.6` release line.

## 1.0.5 - 2026-05-13

- Merged adaptive bitrate into Nova's AI Auto Quality controls so users have one primary quality automation surface.
- Added richer Polaris launch sync, applied settings reporting, presentation status, and stream policy feedback.
- Improved the in-stream HUD with target FPS, 1% low FPS, pacing/host-limit indicators, Auto Quality state, and optimizer sync status.
- Added per-game profile controls, including clearing learned AI profile data from Nova.
- Improved Polaris-backed library, quick-menu, sync sheet, reconnect, and lock-screen flows for handheld use.

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
