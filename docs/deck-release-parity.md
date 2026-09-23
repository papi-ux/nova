# Deck release parity

Owner-approved target: standalone Nova with built-in streaming, OLED HDR10 at
90 fps, and full Android product parity. Android reference:
[`72e9635d`](https://github.com/papi-ux/nova/commit/72e9635d5ac425d52725629c5d9031ab7d21795f),
2026-09-17. Freeze product behavior at this reference; later features need an
explicit scope change, while release-blocking corrections remain eligible.

Approved scope expansion, 2026-09-23: the native client also targets Linux
laptops, desktops and other handhelds, including configurable high rates such as
240 fps on capable systems. The [Linux client roadmap](linux-client-roadmap.md)
records fullscreen/desktop behavior, complete mouse input, host discovery,
continued UI/backend work, native theme choices and copy consistency. It retains
the Deck HDR90 floor and separates implementation from physical acceptance.
Material You is explicitly excluded from the Linux theme set; the remaining
appearance and accessibility requirements still apply.

The [Nightly acceptance contract](https://github.com/papi-ux/polaris-nightly/blob/main/docs/deck-release-acceptance-v1.md)
owns cross-product release admission through DECK-01 to DECK-07. This document
owns Nova's capability inventory. The existing
[diagnostics preview gate](deck-product-readiness-checklist.md) proves only that
preview surface; it is not the release checklist.

## What counts as parity

Implement the same player capability and ownership/security behavior using native
SteamOS interfaces. Android Activities, services, permissions and JNI plumbing
are implementation details, not APIs to emulate. A missing player capability
cannot be dismissed as platform-specific. Hardware-dependent actions must state
their availability honestly and preserve their supported fallback.

All capabilities below are release requirements. `Partial` means code or evidence
exists but the complete player flow is not accepted. `Missing` means the Deck
implementation is absent. Only evidence on the admitted installed artifact can
make a row `Accepted`. The inventory does not claim a full client currently ships.

## Capability inventory

| ID | Capability and Android reference | Deck status at reference | Required acceptance |
|---|---|---|---|
| P01 | Servers, discovery, manual add, Wake-on-LAN (`PcView`, discovery/manager) | Partial: existing Moonlight identities and live probes | Fresh install finds/adds, wakes, selects, forgets and rechecks hosts without Moonlight. Offline and service-unavailable states differ. |
| P02 | PIN/QR pairing and trust (`PairingManager`, `NovaQrScanActivity`) | Partial: pinned reads of imported identity | Native PIN pairing, persisted Nova identity, certificate mismatch, re-pair and revoke tests. QR uses an available camera or image/file input; no built-in Deck camera is assumed. |
| P03 | Library, details, source/platform/runtime labels (`NovaLibrary*`, `NovaGameDetail*`) | Partial: live list with fixture-oriented shell | Browse/search/select real libraries and emulator/utility entries with controller/touch; empty, offline and unavailable states remain actionable. |
| P04 | Live library/artwork refresh (`NovaLibraryLiveRefresh`, `NovaArtworkLibraryUpdater`) | Missing | Host edits update an open library without losing selection, identity or per-game settings. Reconnect resynchronizes. |
| P05 | Artwork Studio (`NovaArtworkStudio`) | Missing | Search, select, reset and save artwork with the same host permission/error behavior. |
| P06 | Pin to Home Screen and pinned state | Partial: Nova app Steam shortcut only | Per-game Steam shortcuts preserve selected art and canonical game identity; Steam-running refusal/retry and upgrade work safely. |
| P07 | Play Setup (`NovaPlaySetup*`, `NovaLaunch*`) | Missing | Independent per-game resolution, FPS, encoder, launch/display mode, host scope, preset, Steam behavior and resolved-plan truth; reset restores the intended scope. |
| P08 | Face-button layout and per-game overrides (`NovaFaceButtonLayoutOverrides`) | Missing | Label/position choices persist per game and affect host input consistently. |
| P09 | Spaces (`NovaSpace*`) | Missing | Select/remember permitted Space, browse its library, launch/resume with exact identity, and reject stale or denied access. Ordinary streaming remains available. |
| P10 | Native session start and media (`NvConnection`, `MoonBridge`, decoder/audio bindings) | Partial: headless native H.264 decode | GUI launches without blocking; real video, sound and controls remain in Nova; cancellation at each connection stage cleans up. |
| P11 | Ownership, watch, resume, disconnect and quit | Partial: native stop/host cancellation | Distinguish disconnect from ending the owned host game; watch/non-owner paths cannot mutate or terminate another session. |
| P12 | Reconnect and suspend (`Game`, `ReconnectOverlay`) | Partial: termination bookkeeping | Network/host interruption and suspend recover or return an actionable terminal state without stale sessions or stuck input. |
| P13 | SDR/codec/pacing/scaling settings | Partial: H.264/SDR prototype | Honor effective stream settings with supported codec selection, stable frame pacing and truthful fallback. Standard hosts remain supported. |
| P14 | HDR and display rates (`GameHdrDecision`, display/device policies) | Missing | LCD SDR60 and OLED SDR90/HDR90 including HDR+90 together; Main10 decode, correct color/metadata and physical presentation, not counters alone. |
| P15 | Audio output, channel configuration, host audio and effects | Missing: callback counters only | Opus decode and native playback, channel mapping, output/routing changes, supported processing preferences and A/V sync through teardown/recovery. |
| P16 | Command Center (`NovaQuickMenu*`) | Missing | All reference actions and states reachable by controller with safe focus return; no overlay shortcut leaks into the game. |
| P17 | NovaHUD (`NovaHud*`, `NovaStreamHud*`) | Missing | Full/slim/FPS modes, opacity/layout preferences, current client/host stats, provenance and unavailable states; no fabricated media loss or presentation rate. |
| P18 | Live Tuning (`NovaPolarisSync*`, live-tuning contract) | Missing | Paired revision-aware changes, encoder-confirmed applied bitrate, enable/disable/fixed-rate semantics and stale event resync. |
| P19 | Doctor, support reports, Auto Fix and Undo | Missing | Refresh current evidence, distinguish host/network/client findings, execute only permitted reversible actions, verify/undo exact session changes and export sanitized reports. |
| P20 | Polaris Sync and settings ownership | Missing | Same host/client/per-game scope, conflict/revision behavior, relaunch-required messaging and offline recovery as the reference. |
| P21 | Themes, layouts, font scale and accessibility (`NovaTheme*`, library/menu preferences) | Missing | Reference theme choices and high-contrast/font-scale behavior adapted to 1280x800, controller focus, touch and external display layouts. |
| P22 | Built-in/external controllers and Steam Input | Partial: shell primary/secondary actions | Full buttons/sticks/triggers, deadzones, hotplug/multiple controllers and correct controller identity; no double delivery. |
| P23 | Mouse, keyboard, touch, gestures and virtual controls | Missing | Mirror Desktop and game input, relative/absolute modes, cursor/trackpad preferences, touch gestures and virtual keyboard/control customization with native focus/capture. |
| P24 | Gyro, rumble, audio haptics and advanced controller features | Missing | Supported motion/touchpad/LED/battery/rumble features and gyro aim preferences work on applicable devices; no false support for absent hardware. |
| P25 | Companion/external display behavior | Missing | Native display targeting, companion controls, focus, hiding and restoration preserve the player capability when a second display exists. |
| P26 | Background/session affordances and keep-awake | Missing | Game Mode/application lifecycle and native notifications preserve session visibility and safe control; no Android service or notification API is required. |
| P27 | Settings persistence, reset, logs and import/export | Missing | All applicable reference preferences have native equivalents, survive upgrades, reset correct scopes, and export no credentials or private raw diagnostics. |
| P28 | Install/update/release | Partial: shell Flatpak and CI | Self-contained signed/versioned distribution with explicit permissions, reproducible dependencies, Steam entry, fresh install and upgrade; APK release flow remains intact. |

The settings audit includes every leaf preference in the frozen
[Android preference catalog](https://github.com/papi-ux/nova/blob/72e9635d5ac425d52725629c5d9031ab7d21795f/app/src/main/res/xml/preferences.xml)
and the modern settings, per-game override and UI-state implementations at that
same commit. Category-to-capability coverage is: stream quality P07/P13/P14;
display/audio P13/P15; dual screen P25; input/controllers P08/P22/P23/P24;
overlays/controls P16/P17/P23/P24; appearance/accessibility P21;
network/session/advanced/storage/support P01/P02/P11/P12/P19/P20/P26/P27.
An implementation PR names the exact preferences it closes and retains their
default, scope, availability, persistence and reset behavior in its tests.

## Implementation order and interfaces

1. Establish a cancellable asynchronous session controller around the existing
   GameStream core and prove native presentation/audio. Keep connection work off
   the GUI thread and session secrets in the backend.
2. Add Main10/10-bit HDR presentation through libplacebo/Vulkan with native Qt
   overlays; retain SDR compatibility. Require actual compositor/display support
   before advertising HDR.
3. Add Nova-owned identity/discovery/pairing and complete input/recovery.
4. Deliver the remaining product rows using paired Polaris APIs and shared
   conformance fixtures. Android remains buildable and releasable throughout.
5. Admit the exact installed Flatpak on LCD and OLED; then extend the one-tag,
   one-release-page publisher with the Deck artifact and checksum.

Use one backend-owned session state and sanitized public models. Reuse host wire
contracts; do not fork settings ownership, authority, game identity or error
semantics in QML. Physical acceptance retains a 60-minute soak, 20 launch/stop
cycles and five suspend/resume cycles, plus the required media, controls, parity,
security and standard-host/Spaces compatibility matrix.

## Release disposition

[PR #334](https://github.com/papi-ux/nova/pull/334) integrated the native Alpha:
standalone PIN/Trusted Pair, libraries, Play Setup, native video/audio,
Command Center/NovaHUD, Doctor/Undo, Sync/settings, recovery and initial input.
[v1.4.12](https://github.com/papi-ux/nova/releases/tag/v1.4.12) published the Alpha
Flatpak. Full product parity and supported-release physical acceptance remain
open. The reference-status column above records the September 17 baseline;
`Missing` there does not mean a capability is still wholly unimplemented.

A supported release remains gated on the required capability and installed
artifact evidence. The Alpha, headless proof and offline diagnostics cannot
close those gates. Static HDR10 and high-refresh Linux work including 240 fps are
in scope; HDR10+ and a new Nordstern transport remain separate research work.
See the Linux roadmap for the approved additions and implementation sequence.
