# Changelog

## Unreleased

## 1.3.4 - 2026-08-07

Nova 1.3.4 rebuilds the game detail window as a destination of its own and then holds the rest of the app to the standard it set. It replaces the launch-path modals with a single Play Setup screen, puts Nova on the Polaris brand palette and typeface, makes Portable Chrome mean what its contract always said, and fixes a set of controller defects that compiled and tested cleanly while being unusable in the hand.

### Highlights

- Rebuilds the **game detail window** around its artwork and opens it as its own window rather than a bottom sheet, with the launch path collapsed into a single **Play Setup** destination — the last three modals become a contextual strip, and the host's answer is shown beside the game's so a per-game override and the host default are no longer a screen apart.
- Puts Nova on the **Polaris brand palette and typeface**, adding Space Grotesk as the chrome face with a subset that keeps tabular figures, and takes Title Case app-wide for names while leaving prose in sentence case.
- Makes **Portable Chrome** literal. The contract has said "smoked graphite / dim moonlight grey / silver shell chrome" since it was written; what shipped was an `#A2ADBA` window with dark text, the only always-light theme in Nova. The reference is a silver PSP-1000: the shell is silver, the screen is not. Every contrast ratio is measured rather than judged by eye.
- Reduces **thirteen corner values to three steps and a pill**, and moves sixteen uppercase chrome labels onto one typeface — they had been spelling out eleven trackings across three units, seven of them with no font family at all and so rendering in Roboto beside Space Grotesk.
- **Separates focus from selection.** Six components drew them identically, and the damage ran the direction that is easy to miss: an unfocused *selected* item was painted with the focus fill, so two things on screen claimed to be where you are.
- Fixes controls that **watched focus but could never receive it**. The Polaris Sync stream display selector could not be reached with a controller at all — changing it required touching the screen, on a handheld.
- Gives surfaces that opened with **focus left behind** somewhere to land: Polaris Sync opens on the stream display modes, Settings on the category rail rather than on Back, where the first press used to leave the screen you had just opened.
- Fixes the **settings search field trapping the d-pad**: it was a plain text field, so once focused the direction keys went into the text and there was no way off it without a touchscreen.
- Keeps the **companion window focusable** instead of clearing focus from the companion display, which had left Android's input dispatcher with no focused window and timing out on controller input, and adds a reversible Hide Companion action driven from the notification.
- Makes a **wrong completion estimate correctable** and guards the gauge that shows it.
- Replaces the raw toasts drawn **over a running game** with themed, differentiated feedback, so a failure no longer looks exactly like a success.

### Compatibility and behavior

- Preserves every Library mode, the theme set, minSdk 21, targetSdk 36, existing database and routing authorities, Moonlight-compatible hosts, Polaris integration, and signed in-place upgrades.
- Portable Chrome changes polarity from light to dark. Its surfaces, focus alphas, particle density and error colour all move with it; the previous error colour was a dark maroon that fails the 4.5:1 gate on dark cards, where the fallback is silent.
- Sheets keep their own 26dp corner: a sheet reads as an edge of the screen rather than as a card on it.
- Six sentences that had been hardcoded English became string resources. Three of them were also assigned to the library's on-screen error state, so the same untranslated sentence was appearing twice at once.
- The dashboard theme picker now reads the shared theme array instead of keeping a second copy in Kotlin, so a theme added to the array can no longer appear in Settings and silently not on the dashboard.

### Release packaging

- Bumps the Android app to versionName 1.3.4 and versionCode 36.
- Publishes signed ARM64, ARMv7, and x86_64 APKs plus portable SHA-256 sidecars through the GitHub release workflow.

### Validation notes

- Verified on a Retroid Pocket 6 against a Polaris host: library, game detail, Play Setup, artwork studio, Settings, the System and Options drawers, the theme picker, the Polaris Sync sheet, and the in-stream Command Center.
- `connectedNonRoot_gameDebugAndroidTest` passes 36/36 on that device; the JVM suite is green, as are `lintNonRoot_gameRelease` with `-PlintFailOnError=true`, both release and androidTest assembles, and the helper and onboarding tests.
- Two changes are **not** verified on hardware and are called out rather than implied: the drawer-anchored snackbar placement inside the in-stream Command Center, and HUD theming under the graphite Portable Chrome. Both need a live stream to observe, and both fall back to previously shipped behaviour.
- The first-focus request lands after a settle delay, so an input arriving inside that window is overwritten when the request completes. This is a race between the delay and the user rather than a wrong destination, and it wants measuring on hardware.
- Roughly ninety raw toasts remain, all in files that host dialogs. Each needs the same question answered individually — is a window above the activity when this fires — because a naive conversion hides the message rather than restyling it. Three of them pass an application context and cannot become snackbars without moving where the message is raised.
- Issues #177 and #178 stay open until physical AYN Thor results are captured; the companion focus lifecycle work here is not itself proof of that validation.

## 1.3.3 - 2026-07-30

Nova 1.3.3 turns the Library and dual-screen experience into a controller-first command surface. It adds Spotlight Row, explicit Follow / Stream / Companion display roles, the Thor companion command deck, broader Material You semantics, and focused navigation, lifecycle, and API 33 reliability fixes.

### Highlights

- Adds **Spotlight Row** as a fourth persisted Library mode beside Grid, Compact Grid, and List, with centered artwork, adjacent-card peeks, stable-ID focus restoration, touch snapping, readable offline fallbacks, and adaptive controller guidance.
- Adds a visual **Follow / Stream / Companion** role composer that previews display assignments, applies changes explicitly, survives hotplug safely, and reuses Nova's existing display-routing authority.
- Ships the **Thor companion command deck** on the non-stream display while preserving keyboard, Quick Menu, touchpad, controller-focus, lifecycle, and teardown behavior.
- Applies **Material You** and OLED-aware semantic surfaces across system bars, dialogs, settings, Library chrome, focus states, and companion controls.
- Keeps controller focus stable while server state refreshes, fixes the API 33 codec-settings dialog crash, and strengthens display-ID reconciliation, stream geometry, server removal, polling, manual-add, and active-session cleanup.
- Adds a distinct **Modern** Settings action and clearer **Manage** server action without changing card activation semantics.

### Compatibility and behavior

- Preserves Grid, Compact Grid, and List; Spotlight Row remains optional.
- Preserves Android scaled text rather than shrinking source typography at large font scales, including a two-line Spotlight title and complete accessibility semantics.
- Keeps Android focus/navigation as the only movement authority; adaptive chrome observes successfully handled input without consuming or duplicating navigation.
- Preserves minSdk 21, targetSdk 36, existing database and routing authorities, Moonlight-compatible hosts, Polaris integration, artwork fallbacks, and signed in-place upgrades.
- Issue #127 remains separate; its current workaround is **Screen Launch → Top Screen**, not Auto.

### Release packaging

- Bumps the Android app to versionName 1.3.3 and versionCode 35.
- Publishes signed ARM64, ARMv7, and x86_64 APKs plus portable SHA-256 sidecars through the GitHub release workflow.

### Validation notes

- Final publication requires the exact tagged ARM64 APK to pass package/version and signer continuity checks plus physical AYN Thor validation for OLED appearance, real D-pad/shoulder/touch behavior, both Stream/Companion directions, reconnect/focus, active-session command-deck lifecycle, teardown, and recovery.
- Roadmap issue #115 remains the release hardware-evidence umbrella until that physical matrix is recorded; merged child issues #169, #170, and #171 are not themselves proof of release validation.

## 1.3.2 - 2026-07-19

Nova 1.3.2 adds adjustable menu and drawer glass, sharpens controller focus rendering, and delivers focused reliability fixes for companion displays, updater recovery, launch diagnostics, and verified host endpoint mobility.

### Highlights

- Adds an independent **Menu & Drawer Opacity** control from 0–100%, with live Settings and Command Center previews, readable 0% focus states, and automatic adaptive backdrop blur on Android 12 and newer.
- Preserves the intended opacity of D-pad artwork instead of flattening asset transparency during rendering.
- Keeps external-display companion controls owned by the active Game session through Android `Presentation` and removes stale controls after teardown.
- Hardens APK download, validation, staging, and retry recovery without weakening package identity or signer verification.
- Enriches launch diagnostics and classifies near-target stream health without presenting healthy small FPS variance as a failure.
- Preserves certificate- and UUID-validated host routes across LAN and private-overlay address changes while keeping endpoint and host identity semantics separate.

### Release packaging

- Bumps the Android app to versionName 1.3.2 and versionCode 34.
- Publishes signed ARM64, ARMv7, and x86_64 APKs plus portable SHA-256 sidecars through the GitHub release workflow.

### Validation notes

- Final publication validation must use the tagged ARM64 release APK for package identity, Library, Menu & Drawer Opacity at 100%/25%/0%, adaptive-blur cleanup, preflight, live stream, physical controller and D-pad focus, Command Center, NovaHUD, updater, reconnect, and cleanup proof.
- Physical Thor audio routing remains tracked separately and is not claimed fixed by this release.

## 1.3.1 - 2026-07-11

Nova 1.3.1 is a focused follow-up to the matched Polaris 1.3 client release. It tightens Watch-stream ownership, keeps NovaHUD readable at Retroid-class widths, and makes the published checksum files portable for normal download-and-verify workflows.

### Highlights

- Shows **Watch active stream** only when a foreign-owned session has a live owner stream, and derives Watch launch resolution and FPS from the owner's active capture/encoder profile instead of the viewer's local display preference.
- Refines NovaHUD Performance and Debug layouts so the headline FPS remains prominent, detail metrics stay visually separate, and the 0% opacity preset removes panel chrome without hiding the text.
- Preserves complete three-digit headline FPS values in the compact Debug HUD by constraining lower-priority stream metadata before the FPS lane is allowed to ellipsize.

### Release packaging

- Bumps the Android app to versionName 1.3.1 and versionCode 33.
- Writes portable SHA-256 sidecars for the signed ARM64, ARMv7, and x86_64 APK assets so `sha256sum -c` works from a clean download directory.

### Validation notes

- The Watch-stream fix was exercised with an independently paired owner emulator and Retroid Pocket 6 viewer using the owner's live `1920x1080 60fps` profile.
- NovaHUD Minimal, Performance, and Debug modes were verified during a physical RP6 live stream, including a complete `114 TGT 120` Debug headline without FPS clipping.
- Final publication validation must use the tagged ARM64 release APK for package identity, Library, preflight, live stream, physical controller, Command Center, NovaHUD, and cleanup proof.

## 1.3.0 - 2026-07-11

Nova 1.3.0 is the matched Polaris 1.3 client release. It adds a self-service stream doctor, display planning, a signature-validating in-app updater, and a denser controller-friendly dashboard across portrait, landscape, Android TV, and external-display use.

### Highlights

- Adds **Diagnose This Stream** with HOST / NET / CLIENT findings and actionable recovery guidance backed by Polaris session diagnostics.
- Adds display-resolution planning and launch presets so requested modes, host capabilities, and stream-display choices are visible before launch.
- Adds an in-app Update Center that checks GitHub release metadata, validates package identity, signing certificate, and version before handing installation to Android's package installer.
- Refines Private Headless Stream, Host Virtual Display, Mirror Desktop, Steam Launch, and Direct launch wording while preserving Polaris display-mode intent.
- Reworks the server dashboard for denser one-screen portrait use, stronger landscape hierarchy, and predictable DPAD focus across cockpit actions.
- Hardens Command Center initial and follow-up focus so controllers and Android TV remotes land on useful actions consistently.
- Improves post-session recovery and terminal-event handling so stale events do not hijack a resumed or newly launched session.
- Preserves the selected app language across reconnects and refreshed host sessions.
- Surfaces AMD host and capture-path truth alongside the existing Polaris diagnostics.
- Routes Thor companion controls and stream audio to the selected external display while retaining safe handheld fallbacks.

### Release packaging

- Bumps the Android app to versionName 1.3.0 and versionCode 32.
- Publishes signed ARM64, ARMv7, and x86_64 APKs plus SHA-256 checksum files through the GitHub release workflow.
- Targets Polaris v1.3.0 while retaining standard Moonlight-compatible host support.

### Validation notes

- Current master CI is green across APK build, CodeQL, public hygiene, and dependency submission before this release-prep branch.
- Final publication validation uses the tagged ARM64 release APK for Library, preflight, live stream, Command Center, Update Center, and cleanup smoke evidence.

## 1.2.1 - 2026-07-04

Nova 1.2.1 is a focused post-1.2 polish patch. It keeps the Polaris 1.2 streaming contract intact while tightening the public theme language, Library option persistence, README showcase assets, and Retroid smoke diagnostics for handheld dogfooding.

### Highlights

- Keeps the theme picker and settings copy centered on Portable Chrome, with smoked graphite, dim silver, and PlayStation-symbol accent language instead of transitional PSP slash labels.
- Preserves Miami Nebula as flamingo-pink first, with cyan and aqua as supporting neon-water contrast.
- Persists Library Options choices so sorting, layout, poster-title visibility, and source filtering survive app restarts and controller shortcut changes.
- Refreshes the GitHub README showcase media with lighter WebP/GIF assets and clearer Nova positioning.
- Improves Retroid UI dump diagnostics so automation failures report whether the device, package, or accessibility dump path is the actual problem.

### Release packaging

- Bumps the Android app to versionName 1.2.1 and versionCode 31.
- Publishes fresh release APK assets for ARM64, ARMv7, and x86_64 through the GitHub release workflow.
- Intended as the small Nova companion patch for Polaris v1.2.x users.

### Validation notes

- Current master CI is green across APK build, CodeQL, public hygiene, and dependency submission before this release-prep branch.
- Final publication should use the tagged ARM64 release APK for a fresh Retroid Pocket 6 smoke: Library, saved Library Options, preflight, live stream, Command Center, and cleanup.

## 1.2.0 - 2026-07-03

Nova 1.2.0 is the matched Polaris 1.2 handheld release. It focuses on the PSP / Portable Chrome visual pass, clearer Polaris-backed launch choices, display-target handling, live-session recovery, and stream UI polish for Retroid-class handhelds and Android TV devices.

### Highlights

- Adds the PSP / Portable Chrome theme and removes legacy Polaris-purple accent leaks from XML, views, focus rings, sheets, and Compose surfaces.
- Deepens the handheld shell into a smoked-graphite / dim silver visual style with readable text, restrained steel highlights, and less washed-out panel chrome.
- Clarifies Polaris launch modes so Private Headless Stream, Host Virtual Display, Mirror Desktop, Steam Launch, and Direct choices read as explicit user intent instead of mystery buttons from the swamp.
- Adds Android display target selection plumbing for external-display workflows while preserving safe fallbacks on handheld-only devices.
- Suppresses false host-lock overlays on owned headless streams and improves session/terminal-event cleanup so Nova exits stale Game surfaces more reliably.
- Improves Polaris API parsing, session truth, preflight copy, HUD/Command Center state, and high-FPS recovery messaging.

### Release packaging

- Bumps the Android app to versionName 1.2.0 and versionCode 30.
- Publishes fresh release APK assets for ARM64, ARMv7, and x86_64 through the GitHub release workflow.
- Intended as the Nova companion release for Polaris v1.2.0.

### Validation notes

- CI on current master is green across APK build, CodeQL, public hygiene, and dependency submission before this release bump.
- Final handheld confidence should include a matched Nova v1.2.0 APK against Polaris v1.2.0 with Library, preflight, live stream, Command Center, stop/cleanup, and real physical-controller proof.

## 1.1.3 - 2026-06-12

Nova 1.1.3 is the public APK publishing patch for the 1.1.2 confidence build. It preserves the same handheld Library, Command Center, NovaHUD, Insert-key, stale-session recovery, input, crash, and dependency hardening work from 1.1.2, while bumping the Android package version so GitHub Releases / Obtainium installs can update cleanly.

### Release packaging

- Bumps the Android app to versionName `1.1.3` and versionCode `29`.
- Publishes fresh signed release APK assets for ARM64, ARMv7, and x86_64 through the release workflow.
- Keeps the 1.1.2 device-validation story intact: Retroid ARM64 Library → stream → Command Center → NovaHUD diagnostics → non-destructive disconnect.

### Upgrade notes

- Users on `1.1.2` or earlier should receive this as a normal Android update because the package versionCode increases to `29`.

## 1.1.2 - 2026-06-12

Nova 1.1.2 is a confidence patch for the 1.1 line. It makes the Polaris-backed Library cleaner on handhelds, adds a plain-artwork poster option, turns NovaHUD into a useful stream-health explainer, adds the requested Insert key affordance, and rolls in current crash/input/dependency hardening.

### Highlights

- **Cleaner Polaris Library**
  - Polishes hero artwork, card readability, compact grid density, landscape footer spacing, and selected-game context so Retroid-style layouts feel less cramped.
  - Adds **Poster titles** in Library Options: keep readable title/caption overlays by default, or choose **Plain artwork** for clean cover-art posters.
  - Prevents incomplete fallback app-list data from showing up as reliable Library entries when Polaris metadata is not available.
  - Keeps fallback failures in-app with clearer provenance instead of confusing legacy wording.
  - Adds a first-screen **End session** escape hatch next to **Resume stream** for owned active sessions, so stale streams can be cleared without resuming into a dead surface.

- **Command Center, NovaHUD, and special keys**
  - Adds **Insert** to Command Center Quick Keys and More Keys / Send special keys for overlays and tools that use Insert, including OptiScaler-style workflows.
  - Adds actionable NovaHUD diagnostics: health reasons such as **Host capped**, stream-truth copy such as **Stream 30 • Host capped**, debug **HOST / NET / CLIENT** chips, and a privacy-safe **Copy HUD Diagnostics** row.
  - Lets long-press on NovaHUD reopen Command Center, while HUD position is persisted and clamped inside the safe zone.
  - Updates high-FPS launch copy to say **High FPS stream**, making 120 FPS read as a stream target instead of a guaranteed game-render promise.

- **Input, crash, and dependency hardening**
  - Sends stylus pen events before pointer-capture mouse gates so pressure-capable touch/stylus paths are not swallowed.
  - Avoids app-grid null crashes on malformed or incomplete app data.
  - Updates Netty and Wire runtime dependency pins and keeps the release build checks green.

### Device validation

- Verified the current `master` ARM64 debug APK on Retroid Pocket 6:
  - populated Polaris Library grid returned after cleanup,
  - Steam Big Picture resumed into the stream Activity,
  - Command Center exposed **Disconnect**, **End session**, **Nova HUD**, **Stats Overlay**, and **Copy HUD Diagnostics**,
  - NovaHUD toggled on, cycled into debug mode, showed **HOST / NET / CLIENT**, dragged safely, and long-pressed back into Command Center,
  - non-destructive **Disconnect** returned to the populated Library,
  - bounded logcat scan found no Nova fatal / ANR / native crash signatures.

### Caveats

- The Retroid smoke reached the stream/control surfaces, not a gameplay movement pass.
- Retroid shell clipboard readback reported `No shell command implementation`; diagnostic-copy behavior is covered by visible UI/tap-no-crash evidence plus unit tests for privacy-safe formatting.
- No fresh MagicPad / MagicOS / Apollo device logcat was collected in this release pass.

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
