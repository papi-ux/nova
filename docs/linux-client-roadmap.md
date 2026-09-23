# Nova Linux client roadmap

Approved scope: 2026-09-23. Nova's native client targets Linux laptops, desktops
and handhelds, with Steam Deck as a supported device profile. Work continues in
`clients/deck/`; this scope change does not rename the package or application ID.

The [v1.4.12 Alpha](https://github.com/papi-ux/nova/releases/tag/v1.4.12) already
contains standalone PIN/Trusted Pair, libraries, Play Setup, native video/audio,
Command Center, NovaHUD and initial controller/keyboard/direct-pointer input.
The [Android parity inventory](deck-release-parity.md) remains the capability
baseline. Its September 17 status column is historical, not today's coding queue.

This roadmap adds Linux-wide usability and 240 fps to the implementation scope.
It retains Deck LCD SDR60 and OLED SDR90/HDR90 acceptance. Configurable rates,
successful negotiation and physically presented frames are separate results;
none establishes the others. HDR10+ and the Nordstern transport remain separate
research work.

## Installed starting point

Reported on 2026-09-23: the most recent Nova installations on the Steam Deck and
Linux machines came from the Flatpak distributed through the Nova GitHub
repository. Use those installed Flatpaks as the starting point for reproduction
and upgrade testing, including their sandbox permissions and existing settings
and pairing data.

Record each installation's version, Flatpak commit and corresponding release
asset/hash before testing. The distribution source is confirmed by the user;
the exact installed artifact identities have not yet been inspected. The source
audit commit below must not be assumed to match those installations.

## Approved additions

Rows below are planned unless updated in the implementation status section.
Existing partial behavior is called out so it can be extended rather than rebuilt.

| ID | Work | Completion criteria |
|---|---|---|
| L01 | Linux laptops, desktops and handhelds | Audit and address windowing, display scaling, external monitors, GPU/decoder selection, audio routing, input and packaging gaps. Publish an explicit tested compatibility matrix rather than infer support from the Steam Deck result. |
| L02 | Continue UI/UX optimization and backend wiring | Library, details, Play Setup, pairing, settings, Command Center and recovery remain usable with mouse/keyboard, controller and touch. Resize and text scaling preserve readable content, reachable actions and visible focus. Complete unfinished UI/backend flows with real state and actionable errors. |
| L03 | Remove arbitrary bitrate and frame-rate caps | Replace Deck-specific hard-coded ceilings with validated custom values and actual host/protocol/media constraints. Include 120, 144, 165 and 240 fps choices where supported. Carry the exact reviewed values through persistence, defaults, Sync, launch, resume, reconnect, media and pacing; explain unsupported combinations without silently rewriting saved choices. |
| L04 | Fullscreen and desktop window behavior | Provide visible windowed/fullscreen controls and a keyboard shortcut; remember window geometry and mode, handle monitor changes, scaling and focus restoration, and keep both normal and Vulkan presentation paths consistent. Game Mode continues to work. |
| L05 | Complete mouse implementation | Extend existing direct-pointer forwarding with relative capture for aiming, reliable capture/release and cursor behavior. Preserve buttons, wheel direction and scaling/cropping coordinates. Overlay shortcuts stay local; focus loss, device removal, monitor/window transitions and teardown release held input. Cover Wayland and X11 separately. |
| L06 | Remove Material You from Linux | Remove the theme option and its implementation from the native client. Existing saved selections fall back to Polaris Aurora without resetting text size or other preferences. Retain the other themes and accessibility choices; Android keeps its own theme behavior. |
| L07 | Find hosts on local/trusted networks | Add a user-visible search with selectable compatible hosts, progress, cancellation, deduplication and empty/error states. Selection feeds the existing pairing flow without requiring an IP address. Retain manual entry and PIN fallback. Discovery never grants trust or bypasses certificate checks; Trusted Pair remains a host-authorized decision. |
| L08 | Consistent interface copy | Use consistent title capitalization for headings, categories and named actions, including **Video & Stream**, while retaining sentence case for explanatory text. Audit related help text, search terms, error messages, clipping and wrapping at supported text sizes. |

Removing Material You is an approved native-platform adaptation to P21, rather
than a reason to omit the remaining appearance or accessibility capabilities.
The host-search requirement means finding compatible PCs on a local/trusted
network; it does not mean changing Linux Wi-Fi or firewall configuration.

## Implementation status

2026-09-23: the first appearance/copy slice is implemented locally and has passed
focused Linux verification:

- L06 removes Material You and migrates its saved selection to Polaris Aurora.
  The Linux settings regression verifies persistence and preservation of text
  size, library layout and game overrides. The actual-app appearance route
  passes for all five remaining themes, large text, focus and restart.
- L08 standardizes settings/category titles and corresponding appearance,
  audio, rumble, scaling and navigation labels. Audio/rumble help applies to
  Linux devices generally. The wider interface-copy audit remains open.
- Linux builds and all seven focused checks pass: settings migration, appearance,
  native preview, settings route, library experience, audio settings and library
  polish. Screenshots at 1280x800 and 960x600 with enlarged text were reviewed.
  The source slice is ready for review; installed Flatpak upgrade acceptance and
  publication remain pending.

The desktop-window slice (L04) is also implemented locally:

- Settings → Video & Stream → Window Mode and the in-game Command Center
  switch between fullscreen and windowed. Ctrl + Alt + Shift + F works in the
  library, pairing/PC management and both stream presentation paths.
- Desktop and Game Mode remember their own mode, normal size, maximized state
  and display choice. Restored geometry is constrained to the available screen;
  Wayland leaves positioning to the compositor. Within a running session, the
  library and Vulkan presentation retain their own normal geometry.
- Mode/display changes release held stream input and leave Command Center open
  until the player resumes. Vulkan handoff remains responsive under a stalled
  GPU, including cancellation, fullscreen switching and reopening.
- Eight focused Linux checks pass, covering geometry/persistence, native input,
  Settings and Command Center, actual-app Settings, Vulkan overlays, refusal and
  GPU pressure. Separate isolated X11 and Wayland compositor checks verify actual
  fullscreen dimensions and restoration after resize. These checks do not close
  the physical Game Mode, mixed-DPI/multi-monitor or installed Flatpak gates.

The rate-policy slice (L03) is implemented locally:

- The 90 fps device ceiling is removed from review, saved profiles, capability
  checks and frame delivery. Supported displays/hosts offer 120/144/165/240 fps;
  custom integral values follow the current Polaris profile range of 15–240 fps.
- Removed the hidden 100 Mbps number-parser ceiling and the separate 150 Mbps
  host-plan ceiling. The current Polaris host contract still accepts 1–300 Mbps;
  raising that host limit requires a matching host/protocol change, not a client
  claim of unlimited bitrate. The limits were rechecked against host source.
- Tests cover high-rate persistence, Sync import, capability and display changes,
  launch, disconnect/resume and reconnect, bounded pacing, QML custom entry and
  actual-app review/settings routes. Existing pairing/session authority remains
  in force. Physical 240 FPS cadence and Flatpak upgrade acceptance remain open.

The relative mouse slice (L05) is implemented locally:

- Direct Pointer remains the saved/default behavior. Relative Aiming adds X11 raw
  motion and Wayland pointer locking, hidden-cursor aiming and explicit release
  through Command Center, focus/window changes and mode changes.
- Settings and Command Center expose the mode, retain a path back to Direct
  Pointer on unsupported compositors, and explain capture refusal. Fractional
  motion and relative distance are retained; pixel-only scrolling is forwarded.
- Focused transport, settings, session and UI regressions pass. Xvfb and an
  isolated KWin Wayland compositor both pass native relative motion beyond
  display edges, cursor restoration, release and recapture. Physical input,
  hotplug, installed Flatpak and game acceptance remain open.

## Source audit and implementation order

Source baseline: [`c822632`](https://github.com/papi-ux/nova/commit/c822632), after
the Alpha integration and host-refusal/icon fixes. These are source observations,
not installed-device acceptance.

1. **Linux appearance and copy (L06/L08).** `qml/NovaTheme.qml` still offers
   `material_you`; `qml/SettingsHub.qml` contains `Video & stream`. Update the
   picker, saved-theme fallback and affected help/search text together. Review
   the actual appearance/settings screens with saved legacy preferences and
   enlarged text. This is the first implementation slice.
2. **Window and input behavior (L04/L05).** The shell has a fullscreen preference
   and the Vulkan view follows the library window's visibility, but that is not
   a complete user-facing mode toggle. Start in `qml/Main.qml`, `src/main.cpp`,
   `src/runtime/deck_vulkan_session_view.cpp` and
   `src/runtime/deck_desktop_input_bridge.*`. Preserve session-worker ownership
   and release-on-focus-loss behavior while adding desktop controls and relative
   input. Validate compositor-specific capture rather than assuming X11 and
   Wayland are interchangeable.
3. **Rate policy from UI to stream (L03).** The custom editor, saved configuration,
   host capability filter and pacing controller currently enforce a 90 fps
   ceiling. Bitrate checks also exist at 300 Mbps, with a narrower preset parser.
   Audit `qml/StreamProfileEditor.qml`, `qml/LiveBitrate.qml`,
   `src/runtime/deck_play_settings.cpp`, `deck_display_capabilities.*`,
   `deck_frame_pacing.h`, `src/polaris/deck_stream_capabilities.h`,
   `deck_game_tools.cpp`, `deck_host_settings.cpp` and the native session/media
   path. Separate client policy from real wire/host limits before changing them;
   retain overflow, malformed-value and allocation checks. Trace any host-owned
   limits to Polaris and record required host changes explicitly.
4. **Host search (L07).** `qml/PairHost.qml` and
   `src/runtime/deck_pairing_controller.*` currently ask for a name/address and
   already provide Trusted Pair and PIN. Add a bounded asynchronous discovery
   provider and results model, then connect selection to those existing flows.
   Exercise multiple interfaces, unavailable discovery services, stale results,
   same-name hosts, cancellation and host changes before pairing.
5. **Continuous Linux UI/compatibility work (L01/L02).** Apply responsive layout,
   focus and copy fixes throughout the slices above. Keep the residual parity
   work below visible; a desktop screenshot or a build alone cannot close it.

Source paths in this section are relative to `clients/deck/`. Each slice records
its exact change, relevant automated checks, visual evidence where applicable,
and any remaining installed/hardware validation in its review.

## Compatibility and acceptance matrix

The initial matrix must cover these dimensions; the audit determines the named
distributions, versions, GPUs and devices before they are advertised as tested.

| Dimension | Required coverage |
|---|---|
| Device and session | Steam Deck LCD/OLED in Game Mode and Desktop Mode; a Linux laptop and a desktop without a Steam dependency; handheld, mouse/keyboard and controller use. |
| Window system and display | Wayland and X11; windowed/fullscreen transitions; resize, fractional/high-DPI scaling, mixed-DPI external monitors, monitor disconnect and selected-display refresh changes. |
| Graphics and media | AMD, Intel and NVIDIA compatibility audit; decoder/render-device selection including hybrid laptops; supported codecs, SDR/HDR, truthful unavailable/fallback states and A/V synchronization. Do not label an untested combination supported. |
| Rates | Retain Deck SDR60/SDR90/HDR90 profiles. Validate high-refresh Linux profiles through 240 fps on capable hardware, distinguishing requested, negotiated, decoded, submitted, presented, repeated and dropped frames. Test bitrate values above the old client ceiling where the host/protocol supports them. |
| Audio and input | Speakers, headphones and external/routed audio; controller hotplug and Steam Input; direct/relative mouse, keyboard, overlays, focus changes and held-input release. |
| Network and identity | Local discovery, manual entry, Trusted Pair and PIN, pinned/revoked identity, multiple interfaces, network loss and stale discovery results; Polaris and standard compatible hosts; permitted and denied Spaces. |
| Distribution and lifecycle | Fresh install and upgrade from the repository-distributed Flatpaks already installed on Deck/Linux, with exact artifact identities and preserved pairing/settings; persistence/reset, desktop launch and optional Steam entry; cancellation, host restart, disconnect/end-game and resource cleanup. Retain the 60-minute soak, 20 launch/stop cycles and five suspend/resume cycles. |

Implementation can proceed before all hardware is available. A missing physical
result remains explicitly unverified; a configuration option or fixture test is
not evidence of 240 unique frames per second or HDR output. The combined
HDR10+ at 240 fps research goal is not implied by separate SDR240 and HDR10 work.

## Existing work carried forward

These additions supplement, rather than replace, the remaining P01-P28 work:

- Main10/HDR negotiation and actual HDR90 presentation, media pacing and A/V sync.
- Remaining QR/Wake-on-LAN, resume/watch/Spaces and standard-host compatibility.
- Touch/trackpad, text entry, virtual controls, gyro and advanced feedback.
- Remaining library/artwork refresh and offline behavior, Stage actions and full
  Space artwork editing where the host API permits it.
- HUD metrics, richer Doctor explanations and support/recovery actions, settings
  import/export, audio effects and remaining native settings equivalents.
- Companion/external-display and background/session behavior.
- Installed-artifact trust, ownership, lifecycle, compatibility and distribution
  acceptance. Preserve Android regression and release checks.

Update the [cross-product acceptance contract](https://github.com/papi-ux/polaris-nightly/blob/main/docs/deck-release-acceptance-v1.md)
with the expanded Linux profiles and evidence before admitting a supported Linux
release. Retain its existing Deck requirements and the separation from Nordstern
protocol research. Alpha publication does not mark these gates accepted.
