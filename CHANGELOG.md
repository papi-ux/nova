# Changelog

## 1.1.1 - 2026-05-28

Nova 1.1.1 is the public release candidate for the 1.1 line. It keeps the 1.1 feature set intact and focuses on release readiness, contributor build guardrails, and current-surface smoke stability instead of rewriting the older `v1.1.0` tag.

### Release hardening

- Prepared the public 1.1 release candidate from the current `master` branch instead of rewriting the older `v1.1.0` tag.
- Added an actionable Gradle preflight for missing `moonlight-common-c` submodule sources before Android native build errors cascade.
- Hardened Compose smoke assertions around the settings refactor so the release line tracks the current settings/library surfaces.
- Kept release workflow checks green across lint, unit tests, CodeQL, public hygiene, dependency submission, and release APK assembly.

## 1.1.0 - 2026-05-20

Nova 1.1.0 is a major handheld-focused polish release for Polaris-backed play. It upgrades the Library, launch flow, Command Center, NovaHUD, stream startup states, controller behavior, and release validation path so Nova feels less like a generic Moonlight-style grid and more like a purpose-built Android console client for real couch and handheld sessions.

### Highlights

- **Richer Polaris Library experience**
  - Refined the Polaris-backed Library into a more console-like browsing surface with stronger game cards, clearer launch context, better focus behavior, and more useful session state.
  - Improved game detail and launch sheets so Headless, Virtual Display, active-session, watch/resume, and host recommendation states are easier to understand before launching.
  - Added more explicit launch-profile summaries so Nova can explain what the host is about to do instead of hiding launch-mode decisions behind generic buttons.

- **Command Center and NovaHUD polish**
  - Improved the in-stream Command Center as the main operations surface for tuning, overlays, session actions, and disconnect behavior.
  - Routed structured stream performance samples from the video renderer into NovaHUD while preserving the legacy overlay text path.
  - Reworked NovaHUD sparkline sampling around a fixed primitive ring buffer to reduce stream-loop allocation pressure.
  - Improved HUD state handling for target FPS, host-render-limited state, stream health, and Polaris-backed tuning/session context.

- **Better stream startup and recovery states**
  - Normalized raw stream progress states so users see readable startup language instead of internal labels like `idle`.
  - Improved lock-screen/unlock retry behavior by only treating Polaris unlock responses as successful when the host reports a real success.
  - Improved reconnect/resume confidence for active Polaris sessions.
  - Added saved-host port recovery so Nova can retry a stale local address on the default Polaris HTTP port and persist the corrected port after a successful poll.

- **Direct launch and shortcut reliability**
  - Improved Polaris preflight handling for direct launches and shortcut-driven flows.
  - Strengthened launch behavior when Nova enters through launcher shortcuts, host grids, or Library detail flows.
  - Added regression coverage around launch profile summaries, Polaris API parsing, stream sync, and shortcut state.

- **Focus, motion, and handheld UX**
  - Added Nova's focus motion system for clearer controller/D-pad navigation.
  - Improved focus visibility across Compose and legacy Android views.
  - Refined handheld-first Library, detail, overlay, and settings surfaces with more consistent Nova visual language.
  - Added source guards and tests to prevent polished surfaces from regressing back into cramped generic Android layouts.

- **Performance hardening**
  - Expanded Baseline Profile coverage for startup, Library detail, settings, and launch-adjacent Compose journeys.
  - Reduced hot-path HUD allocation overhead during streams.
  - Documented a measured JNI bridge policy for future `@FastNative` and `@CriticalNative` work instead of applying risky annotations before profiling proves they help.
  - Kept stream-loop work focused on measured improvements rather than speculative native/JNI changes.

- **Settings and release polish**
  - Refined settings copy, version display, and profile/default state handling.
  - Updated public release notes, reliability notes, and 1.1 release documentation.
  - Added/hardened tests for settings definitions, settings UI state, theme resources, HUD state, launch summaries, Library state, quick menu state, focus drawables, and stream overlays.

- **Build, CI, and contributor guardrails**
  - Added actionable Gradle/native-build preflight behavior for missing `moonlight-common-c` submodule sources before opaque NDK errors cascade.
  - Bounded emulator smoke runtime in CI.
  - Kept release workflow checks aligned across lint, unit tests, CodeQL, dependency submission, public hygiene, and release APK assembly.
  - Added/updated Retroid smoke tooling for repeatable handheld validation.

### Device validation

- Verified the ARM64 debug APK on Retroid hardware over ADB with:
  - Polaris Library launch
  - HEVC stream resume
  - NovaHUD enablement from Command Center
  - Command Center disconnect back to the Library
  - bounded logcat/crash-buffer checks

### Upgrade notes

- Nova 1.1.0 keeps compatibility with Moonlight/GameStream-style hosts, but the richest new Library, launch, tuning, session, and HUD states require a recent Polaris host.
- Existing installs that already received the 1.0.10 stream-resolution migration keep those repaired stream defaults.
- Contributors building from source should initialize submodules with:

  ```bash
  git submodule update --init --recursive
  ```

  Nova now fails earlier with a clearer message when required native submodule sources are missing.

## 1.0.10 - 2026-05-19

- Migrated upgraded installs that still carried the old Balanced 720p stream resolution so Shield, Retroid, and Android TV clients request 1080p after updating.
- Preserved the explicit Performance preset at 720p while repairing only legacy Balanced defaults.
- Kept cached Auto Safe launch profiles from forcing 1080p-capable clients down to 720p unless Polaris reports a confirmed recovery profile.
- Added regression coverage for the upgraded-install resolution migration and the cached Auto Safe 1080p floor.

## 1.0.9 - 2026-05-19

- Cleared stale Active Session UI after a confirmed local End request for Polaris-backed streams.
- Deferred local End markers until quit confirmation so canceling the dialog does not hide a still-active session.
- Scoped local End markers by host/PC and expired them quickly to avoid stale state affecting future launches.
- Consumed Polaris' terminal idle lifecycle event from `v1.0.17` so direct launches and quick-menu End stay aligned.
- Aligned first-run stream defaults with the Balanced preset so new Android TV/Shield installs request 1080p instead of silently staying at 720p.
- Added regression coverage for marker scoping, expiration, confirmed quit, and stale Active Session clearing.

## 1.0.8 - 2026-05-18

- Strengthened controller and TV focus states in the dashboard, library game grid, and launch affordances so selected items stand out more clearly.
- Tuned Nova Library density for handheld landscape devices with a 4-card Continue rail, 3-column default grid, shorter game cards, and smaller HDR/Recent badges.
- Explicitly syncs the selected MangoHUD state before Polaris-backed library launches so headless launches do not inherit stale host overlay settings.
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
