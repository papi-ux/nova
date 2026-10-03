# Nova Linux objective status

Original source audit: 2026-09-26, Nova staging
`78e9593ab9fcf0f8b4a2ebe86fd7e47d9d8adc2f`. Updated for the 1.4.14 candidate
on 2026-10-03; the checkpoint below supersedes the older implementation status
where named. This is an execution map for the [Linux roadmap](linux-client-roadmap.md)
and the [frozen parity requirements](deck-release-parity.md). September 17
`Missing` entries and imported `status/not-started` labels are historical input,
not instructions to rebuild features now present in this source.

Nova Linux serves Linux laptops, desktops and handhelds. Steam Deck LCD/OLED are
required device profiles. Existing `clients/deck/` paths, Flatpak app identity and
Nightly `DECK-01`–`DECK-07` IDs remain stable identifiers; they do not limit the
product to Steam Deck. Gitea branding PR #1 and Tuning correction PR #2 are
already merged in the audited staging source.

## 1.4.14 candidate checkpoint

- The standard x86_64 bundle includes experimental PyroWave and an isolated
  device check on explicit selection. H.264, HEVC and Auto bypass that probe.
  SDR 8-bit 4:2:0 is supported by this route; HDR, 4:4:4 and Spaces are not.
- Hosts, Settings, Library and Play Setup have a landscape/desktop sizing pass.
  Regular and Compact remove the duplicate Continue row; Stage keeps its
  landscape choice when a taller window uses Regular. Six native themes,
  Compact/Standard/Large controls, 80–130 percent text and separate Command
  Center opacity are implemented. Saved appearance preferences survive updates.
- Wake PC, reviewed Steam registration, first-run pairing and named startup
  failures are implemented. A sent wake packet and an accepted end-session
  request are distinguished from an awake PC and verified game shutdown.
- PyroWave advice uses calibrated handheld viewing, with host advice preferred
  when available. Automatic advice stays capped at 300 Mbps. Manual review and
  live controls respect the PC's advertised limit, defaulting to 300 Mbps and
  allowing up to 500 Mbps where advertised. Play Setup's preset list still ends
  at 300 Mbps; it retains an allowed saved custom value.
- An experimental ARM64 Steam Frame route adds a pinned provider, bounded V4L2
  buffer ownership and shared startup/Play Setup discovery. Real pictures with
  all three codecs are recorded; sustained 1080p/90 performance, color/clarity
  and broader physical acceptance remain incomplete. See the
  [Frame development guide](../clients/deck/docs/steam-frame.md).

These are source checkpoints, not closure of the broad parity objectives. The
current Deck physical picture/audio/controller check remains pending. Installed
stream startup, disconnect/resume/end evidence is narrower than that check.
Frame qualification remains separate; there is no public ARM64 asset or XR/HDR
claim. The original execution order and residual matrix below retain those
acceptance obligations.

## Original execution order

1. Resolve installation and correctness blockers, then consolidate the existing
   PRs. Android 16 KB alignment (#74) and beta version codes (#77) are existing
   release fixes, not new implementation assignments. For Linux, #80 consolidates
   asynchronous fixture fixes; #88 covers a real settings-focus gap; #91 adds
   diagnostics for the unresolved launch-mode chooser CI failure. Require green
   checks on the eventual combined source before admitting a candidate.
2. Finish the remaining Nova Linux product gaps below. Prioritize playable media,
   recovery, input, truthful diagnostics and the HDR packaging/presentation gap.
   Extend existing adapters and shared host contracts instead of rebuilding
   pairing, audio, relative mouse, discovery, rates, settings or overlays.
3. Accept the exact installed Nova Linux artifact on the required device profiles.
   Retain LCD SDR60, OLED SDR90/HDR90 together, laptops/desktops, X11/Wayland,
   audible audio and controls, 60-minute soak, 20 launch/stop cycles and five
   suspend/resume cycles. Higher-rate Linux profiles need their own cadence proof.
4. Resume Nordstern physical baseline work after the product release priority.
   Existing GameStream product work does not depend on a new transport or the
   Nordstern baseline. Container-server experiments and unrelated research do not
   replace these product priorities.

`Present` below means an implementation exists in the audited staging source.
It does not mean full parity, a reviewed open PR, an installed candidate, or
physical acceptance. Named residuals include implementation gaps and required
acceptance; none of the P01–P28 acceptance issues closes from this audit alone.

## Linux additions

| ID / Gitea issue | Present implementation and source | Remaining work |
|---|---|---|
| L01 / #15 | Native standalone client and documented compatibility audit | Complete named laptop, desktop and handheld matrix; hybrid GPU, display, audio and installed-artifact coverage. A single NVIDIA desktop result does not validate all NVIDIA systems. |
| L02 / #16 | Library, pairing, Play Setup, settings, overlays and responsive UI | Complete controller-only navigation and residual parity flows; review #88 rather than reopening its focus fix. |
| L03 / #17 | `deck_stream_capabilities.h`, play settings and pacing carry 15–240 fps and manual requests through 500 Mbps storage, bounded by the advertised host limit (300 Mbps when absent) | Physical high-refresh cadence, decode/network capacity and installed profiles. Higher bitrate requires a matching host contract. Do not reinstate a 90 fps ceiling or promise unlimited rates. |
| L04 / #18 | `deck_window_controller.*`, settings/Command Center and fullscreen shortcut; geometry and mode persistence | Physical Game Mode, mixed-DPI/multi-monitor transitions and installed upgrades. |
| L05 / #19 | `deck_desktop_input_bridge.*`, direct pointer and X11/Wayland relative capture/release | Physical game aiming, focus transitions, hotplug and sandbox/compositor compatibility. Android Mouse Mode PR #86 is a separate platform fix. |
| L06 / #20 | `NovaTheme.qml` removes Material You and migrates saved selection to Polaris Aurora | Installed upgrade/accessibility acceptance; preserve other themes and preferences. Do not port Android Material You back into Linux. |
| L07 / #21 | `deck_host_discovery.*` and `PairHost.qml`: bounded explicit Avahi search, selection, cancellation, manual entry, existing PIN/Trusted Pair | Installed/network compatibility, unavailable Avahi and routed/multicast-blocked networks. Discovery never grants trust. |
| L08 / #22 | Video & Stream plus the 1.4.14 landscape, copy, density and text/opacity draft-editor pass | Wider interface copy, small-window and large-text audit. Product wording is Nova Linux. |

Paths in this table are under `clients/deck/src/` or `clients/deck/qml/`.
The [roadmap checkpoints](linux-client-roadmap.md#implementation-status) retain
the earlier automated/compositor checks and the exact, limited desktop upgrade
evidence. Those are retained results, not new physical tests from this audit.

## Parity objectives

Source pointers below are under `clients/deck/`. The corresponding runtime,
contract and QML tests remain regression coverage; a test's existence does not
establish its current CI result or installed-device acceptance.

| ID / issue | Present implementation and source | Remaining gap or acceptance |
|---|---|---|
| P01 / #24 | Local discovery, manual add, selection, removal and explicit Wake PC: `src/runtime/deck_host_discovery.cpp`, `deck_pairing_controller.cpp`, `deck_host_wake.cpp` | Physical wake/routing and complete offline/service distinction; installed fresh setup and persistence. |
| P02 / #25 | Standalone Nova identity, PIN/Trusted Pair, pinned trust: `src/identity/deck_pairing.cpp` | QR/image onboarding and installed changed-certificate, revoke, unpair/re-pair matrix. No Moonlight dependency for normal setup. |
| P03 / #26 | Live library, query, details, labels and layouts: `src/runtime/deck_library_controller.cpp`, `qml/LibraryBrowser.qml` | Controller/touch, empty/offline, emulator and standard-host acceptance. Host Heroic metadata PRs are complementary. |
| P04 / #27 | Bounded background refresh and artwork handling: `deck_library_controller.cpp`, `deck_library_artwork.cpp` | Exact installed host-edit/reconnect/selection/settings preservation; broader offline/delta semantics remain distinct. |
| P05 / #28 | Desktop artwork operations and bounded Space artwork UI: `deck_library_artwork.cpp`, `qml/SpaceArtwork.qml` | Full manual Space search/replacement/reset needs a host API extension; installed permissions/error coverage. |
| P06 / #29 | Steam shortcut registration: `src/runtime/deck_steam_shortcuts.cpp` | Complete per-game art/identity, Steam-running refusal/retry and upgrade acceptance. Steam remains optional. |
| P07 / #30 | Play Setup, reviewed host plans and scoped profiles: `qml/PlaySetup.qml`, `deck_play_settings.cpp` | Full option/permission/reset/standard-host matrix. Tuning fix #2 is merged; unresolved chooser CI is tracked by #91. |
| P08 / #31 | Persisted face-button policy in play settings and controller routing | Installed per-game label/position and host-input agreement; retain scope/reset tests. |
| P09 / #32 | Authorized Space selection and scoped libraries/session assembly: `src/polaris/deck_spaces.cpp` | Installed allowed/denied/stale launch/resume matrix and remaining Space product gaps. Host runtime/GPU acceptance is separately owned. |
| P10 / #33 | Asynchronous GUI native session, VA-API media, Opus/PipeWire: `deck_native_session.cpp`, `src/stream/deck_stream_media_adapters.cpp` | Physical playable output, cancellation at every stage, cadence and resource cleanup. Reuse this path; input/audio are not no-op adapters now. |
| P11 / #34 | Disconnect, resume, end-game and ownership checks in `deck_native_session.cpp` | Installed owner/non-owner/watch matrix. Android watchability PR #76 does not close Linux acceptance. |
| P12 / #35 | Reconnect policy, sleep handling and audio recovery: `deck_reconnect_policy.h`, `deck_native_session.cpp`, `src/stream/deck_audio_recovery.cpp` | Physical network loss, host restart, suspend/resume and held-input/resource cleanup. |
| P13 / #36 | H.264/HEVC choice, frame pacing, scaling and display-aware profiles | Standard-host/media fallback acceptance. The preserved 10-bit SDR proposal in issue #6 is not part of staging. |
| P14 / #37 | Main10/color/capability work; the standard Flatpak manifest packages the opt-in Vulkan presenter and pinned libplacebo; high-rate settings | Vulkan streaming still negotiates SDR only. Implement capability-gated live HDR selection/negotiation and verify compositor/output color metadata, hardware import and actual OLED HDR90 presentation. The normal launcher remains OpenGL/EGL. Linux SDR240 controls do not prove cadence or HDR240. |
| P15 / #38 | Opus/PipeWire output, channels, PC audio preference, bounded route recovery: `src/stream/deck_audio_output.cpp` | Audible output/routing, surround, effects and A/V sync. Issue #8 retains delay-measurement groundwork; it is not a completed synchronization policy. |
| P16 / #39 | Native Command Center and overlay input ownership: `qml/NativeStreamPreview.qml` | Complete reference actions, focus return and shortcut isolation on installed normal/Vulkan paths. Android menu explanations in #79 are separate. |
| P17 / #40 | Client/host HUD models and layout/preferences: `deck_hud_metrics.cpp`, `deck_hud_host.cpp` | Complete metrics/provenance and installed availability/cadence truth. Never treat control loss as media loss. |
| P18 / #41 | Paired live tuning and fixed bitrate through `deck_native_session.cpp` and host contracts | Encoder-confirmed application, stale/revision resync and ownership acceptance on the installed host/client pair. |
| P19 / #42 | Scoped Doctor actions, recovery journal, Undo and sanitized support export: `deck_doctor_actions.cpp`, `deck_doctor_receipts.cpp`, `deck_support_report.cpp` | Richer explanations, crash/log sharing, Space actions and next-launch trials; installed reversible-action/receipt acceptance. Read-only host Spaces findings do not supply Linux Space Auto Fix. |
| P20 / #43 | Scoped host settings and Sync: `deck_host_settings.cpp`, session/background Sync | Complete old/new host, conflicts, offline/reconnect, ownership and no-echo acceptance. Do not rebuild the existing Sync flow. |
| P21 / #44 | Six native themes including Director, migration, 80–130 percent text, separate control sizes, layouts and settings: `qml/NovaTheme.qml`, `qml/SettingsHub.qml` | Remaining accessibility, small-window/external-display acceptance. Material You exclusion is the approved Linux adaptation. |
| P22 / #45 | Native buttons/sticks/triggers, deadzone and input ownership: `deck_input_hub.cpp`, `src/stream/deck_controller_input.cpp` | Physical built-in/external controllers, Steam Input, hotplug, multiple-controller identity and no duplicate delivery. |
| P23 / #46 | Direct/relative mouse and keyboard forwarding, overlay capture/release, native UI text entry | Remaining touch/trackpad, gesture and customizable virtual-control parity; physical X11/Wayland and lifecycle acceptance. UI touch is not complete streamed touch parity. |
| P24 / #47 | Scoped single-controller two-motor rumble and preference: `deck_rumble.cpp`, `deck_rumble_linux.cpp` | Physical rumble plus multi-controller feedback, gyro, trigger motors, LEDs, audio haptics and applicable device features. |
| P25 / #48 | Window/display targeting and restoration: `deck_window_controller.cpp` | Native companion controls and full second-display lifecycle. Android companion PR #78 does not implement Linux companion behavior. |
| P26 / #49 | Application/session/sleep lifecycle in native session and window controllers | Native background visibility, notification/keep-awake applicability and installed Game Mode lifecycle acceptance. |
| P27 / #50 | Scoped persistent settings/reset and sanitized support reports | Full leaf-preference audit, settings import/export and installed upgrade/reset acceptance. Existing report export and Doctor recovery are not missing. |
| P28 / #51 | Nova Linux branding, Alpha Flatpak packaging, stable app ID, Android/Linux/Flatpak CI | Exact admitted artifact, fresh install/upgrade, checksums, distribution verification and physical profiles. Prior Alpha publication does not close supported-release admission. |

## Existing work and duplicate prevention

Keep one implementation lane per correction. PR #80 includes #81–#84; review the
consolidated result and preserve contributor attribution. Android PR #86 includes
#85. Those included PRs are not additional feature deliveries. #88–#91 build on
#80 and must retain their additional changes when that prerequisite lands.

Android artwork lifetime/cache (#87/#90), watch eligibility (#76), companion
fallback (#78), menu explanations (#79), Mouse Mode (#86), Kotlin qualification
(#89), page alignment (#74) and beta versioning (#77) preserve the Android lane.
They do not fill Linux parity rows merely because the feature names match.

Polaris owns host correctness and APIs; the original September 26 queue included ping-session
publication (#122), admission/artwork races (#126/#124), Heroic discovery/metadata
(#128/#130), Spaces observations (#136/#137), input modifiers (#139), Vulkan LTO
(#140), optional import covers (#135), bounded cover lookups (#142) and debug
assets (#143). This is historical ownership context; check current issue/PR state before
starting work. Merged source and physical acceptance remain separate. Nightly owns the acceptance contract and evidence decision,
not another implementation of these APIs.

Before starting another issue, read its current source, linked PR and residual
acceptance here. If a slice is implemented, work on the named gap or acceptance
instead. Record exact source/artifact identity and limitations; do not close a
broad acceptance objective from a narrow fix or a green test run.
