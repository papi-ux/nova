# Steam Deck Native Port Study

Nova is currently an Android client. A native Steam Deck version is therefore a Linux/SteamOS client project, not a packaging or APK distribution tweak.

Within Nova's multi-client repo direction, that client should live in `clients/deck/` and consume shared layers from `shared/models/`, `shared/polaris/`, and `shared/stream-core/`.

This note turns that observation into a concrete architecture recommendation grounded in the current Nova codebase.

## Current Nova shape

Nova is split into three broad layers today:

| Layer | Current implementation | Port value for Steam Deck |
|---|---|---|
| Native streaming core | `app/src/main/jni/moonlight-core/moonlight-common-c/` | Highest reuse potential |
| App protocol and Polaris integration | `app/src/main/java/com/papi/nova/api/`, `nvstream/`, `manager/` | Reuse behavior and contracts, not Android app structure |
| Android shell | `AndroidManifest.xml`, `PcView`, `AppView`, `Game`, `ui/`, `service/`, `binding/` | Replace with Linux-native equivalents |

Important current Android assumptions:

- Video decode is built around `MediaCodecDecoderRenderer` and `MediaCodecHelper`.
- Audio output is built around `AndroidAudioRenderer`.
- Input capture and controller plumbing are built around Android device, evdev shim, USB driver, and touch abstractions in `binding/input/`.
- Discovery relies on Android services and Android NSD in addition to JmDNS.
- The app shell depends on Android `Activity`, `Service`, notification, accessibility, and manifest-driven lifecycle behavior.
- Polaris UI surfaces are Android activities and views such as `NovaLibraryActivity`, `NovaQuickMenu`, `NovaStreamHud`, and `ReconnectOverlay`.

The practical conclusion is that only the native Moonlight streaming core is a clean code-level reuse candidate. Most of the Kotlin and Java layers should be treated as product reference material rather than portable code.

## Recommendation

The default direction should be:

1. Add a native SteamOS client in `clients/deck/`.
2. Use `moonlight-common-c` as the streaming core anchor.
3. Build the new shell in Qt/QML for controller-first Steam Deck UX.
4. Preserve Nova's Polaris-aware behavior and UX priorities, but reimplement those surfaces natively.

This should be chosen over trying to "port the Android app" directly.

## Architecture options

### Option A: New `clients/deck/` Qt/QML client plus `moonlight-common-c`

**Recommendation: preferred path**

Why it fits:

- Steam Deck needs a controller-first fullscreen shell, suspend/resume tolerance, and Game Mode friendliness.
- Qt/QML maps well to handheld UIs, focus navigation, overlays, and Linux packaging.
- It gives full control over Nova-specific flows such as Continue, launch modes, quick menu state, and Polaris session surfaces.
- It avoids carrying Android lifecycle and UI assumptions into SteamOS.

Costs:

- Highest upfront implementation cost.
- Requires new Linux-native render, audio, discovery, and input backends.
- Requires a new persistence and packaging story.

### Option B: Fork an existing Linux Moonlight client and layer Nova/Polaris features on top

**Recommendation: investigate, but keep as fallback**

Why it is attractive:

- Faster path to first stream on Linux.
- Existing Linux render/audio/input stacks may already work well on Steam Deck.
- Could reduce early decoder and windowing work.

Why it is not the default:

- Nova's product value is in the handheld-first UX and Polaris-aware surfaces, not only basic Moonlight connectivity.
- Retrofitting those flows into an existing Linux client may be harder than it looks.
- UI, state, and settings structures may fight the shape Nova wants on Deck.

The first technical spike should still compare this option against the new-client path before final commitment.

## Reuse and replacement matrix

| Subsystem | Current Nova source | Deck strategy |
|---|---|---|
| Streaming transport and protocol core | `moonlight-common-c` | Reuse directly where possible |
| Host HTTP / Polaris API behavior | `api/PolarisApiClient.kt`, `nvstream/http/` | Reimplement natively, then move reusable contract logic toward `shared/polaris/` |
| Pairing and certificates | current Moonlight/Nova client cert flow | Reuse protocol behavior, move storage and TLS wiring into shared and Linux-specific layers |
| Discovery | `DiscoveryService`, `JmDNSDiscoveryAgent`, `NsdManagerDiscoveryAgent` | Replace with Linux mDNS/zeroconf service layer |
| Video decode and presentation | `binding/video/MediaCodec*`, `StreamView` | Replace with Linux decode/render pipeline |
| Audio output | `binding/audio/AndroidAudioRenderer` | Replace with PipeWire or PulseAudio backend |
| Controller input | `binding/input/ControllerHandler`, `GameInputDevice` | Replace with SDL or Qt input abstraction |
| Gyro and haptics | `GyroAimController`, `AudioHapticEngine` | Phase 2 unless Linux support is clean |
| Touch and virtual controller | `binding/input/touch/`, `virtual_controller/` | Defer for Deck MVP |
| HUD and overlays | `NovaStreamHud`, `ReconnectOverlay`, `SessionProgressOverlay` | Rebuild as native overlay components |
| Library and launch UX | `NovaLibraryActivity`, `NovaGameDetailSheet`, `NovaQuickMenu` | Rebuild natively, preserve behavior goals |
| Background keep-alive / notifications | `NovaStreamKeepAlive`, `NovaStreamNotification`, `NovaQsTile` | Replace with SteamOS-appropriate lifecycle behavior |
| Settings and profiles | current Android preferences/profile flows | Replace with Linux-native settings store backed by shared models |

## Native interfaces to define up front

These should be created as implementation boundaries before platform work spreads across the codebase:

### `HostDiscovery`

Responsibilities:

- scan for hosts
- manual add
- resolve host metadata
- cache last-known endpoints

Expected events:

- host appeared
- host updated
- host disappeared
- discovery failed

### `PairingSession`

Responsibilities:

- advertise supported pairing paths
- start TOFU, QR, or PIN flow
- persist or revoke client credentials
- surface failure reasons cleanly

Expected states:

- idle
- awaiting input
- awaiting host confirmation
- paired
- failed

### `StreamingSession`

Responsibilities:

- negotiate launch parameters
- start and stop stream
- emit connection, performance, and recovery events
- manage reconnect and suspend/resume behavior

### `PolarisClient`

Responsibilities:

- fetch capabilities
- fetch and refresh library
- query session state
- send launch and control actions
- expose recommendation and launch-mode metadata in a UI-friendly form

### `InputBackend`

Responsibilities:

- buttons, triggers, sticks, mouse mode
- focus on Steam Deck controls first
- optionally expose gyro and haptics without making them MVP blockers

### `RendererBackend`

Responsibilities:

- decoded frame ingestion
- fullscreen presentation
- resize and orientation handling
- overlay composition hooks for HUD and reconnect state

### `SettingsStore`

Responsibilities:

- persist hosts
- persist stream preferences
- persist per-host and per-profile tuning
- surface Deck-specific defaults cleanly

## Steam Deck product defaults

The native client should assume:

- 1280x800 is the primary layout target
- controller-first navigation is mandatory
- Game Mode is the primary usage environment
- Desktop Mode is supported but secondary
- fullscreen stream entry and exit should feel natural from Steam
- suspend/resume and temporary network interruption are first-class flows

Do not carry over these Android-specific assumptions into the Deck design:

- notifications as the keep-alive mechanism
- accessibility service keyboard tricks
- Android-specific pointer capture
- touch-first fallback as a primary control path
- Quick Settings tile integration

## MVP scope for the future port

The first implementation milestone should target:

- manual host add and LAN discovery
- pairing for Polaris and standard Moonlight-compatible hosts
- browse and launch from a basic library or app list
- start and stop a fullscreen stream
- Steam Deck controller input
- reconnect overlay and session failure handling
- persisted settings
- enough Polaris support to expose library, session truth, and launch-mode context

Explicitly defer unless a low-cost Linux path appears during implementation:

- full Android feature parity
- virtual touch controller
- Android-specific background behaviors
- accessibility-driven keyboard flows
- advanced gyro and haptics polish
- every current Nova cosmetic surface

## Milestones

### Milestone 1: First stream on Steam Deck

- Linux shell boots
- host can be added
- stream can start and stop
- controller input works
- video/audio path is stable enough for local testing

### Milestone 2: Deck-native shell

- controller-first navigation
- settings and profiles
- reconnect behavior
- fullscreen transitions that feel correct in Game Mode

### Milestone 3: Polaris-aware experience

- capabilities probe
- library and Continue surfaces
- launch modes
- session state and quick controls

### Milestone 4: Deck polish

- suspend/resume hardening
- performance tuning
- optional gyro and haptics work
- packaging and distribution polish

## Risks and mitigations

| Risk | Why it matters | Mitigation |
|---|---|---|
| Reusing too much Android app code | Slows the port and spreads platform assumptions everywhere | Treat Android code as behavior reference, not shared app framework |
| Linux decode/render complexity | This is the biggest technical replacement area | Spike render and audio backends before large UI work |
| Polaris contract drift between Android and Deck clients | Can fragment the product | Keep Polaris-facing logic behind a dedicated `PolarisClient` interface and reuse the same response model where practical |
| Steam Deck suspend/resume regressions | Handheld experience will feel broken even if basic streaming works | Make suspend/resume part of Milestone 1 validation, not late polish |
| Over-scoping parity | Delays first usable build | Lock MVP around first stream plus core Polaris UX, defer Android-only features |

## Acceptance scenarios

The native Deck MVP should be considered successful only if it can:

- pair with a Polaris host over LAN
- pair with a Sunshine or Apollo host without Polaris-only features
- start and stop a stream from Steam Deck controls only
- survive a short network interruption with a clear recovery path
- resume cleanly after Steam Deck suspend without corrupting client state
- display a readable, controller-friendly UI at 1280x800
- persist host settings and reload them on next launch

Non-goals for the MVP:

- matching every Android-only Nova feature
- touch overlay parity
- perfect gyro and haptics parity
- background-service behavior identical to Android

## Deck-T4 streaming backend decision

Decision: proceed with a Nova-owned native Linux streaming backend that links
`moonlight-common-c` directly and supplies Deck-specific adapters for decode,
presentation, audio, input, discovery, pairing, persistence, and Polaris session
orchestration.

Reject adapting an existing Linux Moonlight client as the product base for the
next Deck slice. Existing Linux Moonlight clients remain useful references for
backend behavior, SteamOS quirks, and dependency choices, but Nova should not
inherit another client's shell, state model, settings, or launch/session UX.

Detailed spike notes live in
`clients/deck/spikes/streaming-backend-notes.md`.

### Why the direct `moonlight-common-c` path wins

The current Android implementation already shows the seam Nova needs to keep:
Android owns product orchestration while `moonlight-common-c` owns the stream
transport. Android's `NvConnection` negotiates host/app/session state, installs
video/audio/listener callbacks through `MoonBridge`, and then starts the native
connection. The native bridge eventually calls `LiStartConnection(...)` with
`CONNECTION_LISTENER_CALLBACKS`, `DECODER_RENDERER_CALLBACKS`, and
`AUDIO_RENDERER_CALLBACKS`.

For Deck, the reusable boundary is that C callback contract, not the Android JNI
bridge. The JNI layer is tied to the JVM, Android logcat, Android CPU feature
helpers, `MediaCodecDecoderRenderer`, `AndroidAudioRenderer`, Android discovery,
Android input capture, activities, services, notifications, and other lifecycle
assumptions. Those pieces should remain behavior references only.

Direct integration keeps Nova's product shape intact:

- Standard Moonlight-compatible hosts still work through `moonlight-common-c`.
- Polaris can stay first-class instead of being grafted onto a generic client UI.
- Continue, launch modes, watch/owned-session state, tuning sync, NovaHUD copy,
  reconnect messaging, and safe diagnostics can be modeled as Deck-native Nova
  surfaces from the start.
- `shared/stream-core` can become the cross-client session boundary Android and
  Deck both converge toward, rather than wrapping a forked Linux app.

### Rejected alternative: adapt an existing Linux Moonlight client

This path remains a fallback, not the default.

It is attractive because a mature Linux client may already have working decode,
audio, input, fullscreen, and packaging behavior. It could get a generic first
picture on screen faster.

It is rejected for Deck-T4 because Nova's value is not just launching an app list
and displaying a stream. Nova needs Polaris-aware library metadata, launch-mode
intent, watch/resume/replace semantics, session ownership truth, tuning sync,
controller-first overlays, and handheld recovery copy. Retrofitting those into
another client's screens and state machine risks producing Moonlight cosplay with
Nova labels bolted on. It would also duplicate or discard the existing
`clients/deck` shell and delay the shared-stream boundary Nova needs for Android,
Deck, and future clients.

Revisit this only if the direct backend cannot produce stable low-latency
SteamOS video/audio after focused testing, or if a small separable backend
component can be reused with clean provenance and attribution. Nova is already
GPLv3-lineage, but any copied client code still needs explicit license and
maintenance review before entering the repo.

### Linux backend candidates for the direct path

**Video and presentation** should be the first technical proof. Start with H.264
hardware decode on a Linux media stack such as FFmpeg/libavcodec with VA-API on
Steam Deck-class AMD hardware, then prove fullscreen presentation and overlay
composition hooks. Consider GStreamer only if it shortens VA-API and
presentation integration without hiding frame pacing control. Treat software
decode as a fallback or diagnostic path, not the target.

**Audio** should prefer PipeWire for SteamOS-era integration, with PulseAudio as
the compatibility fallback. SDL audio is acceptable as a temporary spike adapter
only if it gets decoded PCM playing while the session lifecycle boundary is being
validated.

**Controller input** should use the Deck's built-in controls as a normal gamepad
path first, likely through SDL/GameController-style mapping or a minimal evdev
adapter if SDL is not introduced yet. Keep shell shortcuts separate from
in-stream Moonlight packets so Nova can own Command Center, NovaHUD, stream stop,
and diagnostic copy without stealing gameplay input accidentally.

**Discovery and pairing** should start with manual host add plus pairing/cert
storage. LAN mDNS/zeroconf can follow after the no-Android credential and host
record contracts are stable.

**Fullscreen and suspend/resume** must be tested in a Game Mode-like fullscreen
path, not only a Desktop Mode window. The stream boundary must handle connecting,
active streaming, disconnecting, suspend, interrupt, and reconnect states without
corrupting `moonlight-common-c` lifecycle.

### Minimum Polaris surface for the next Deck vertical slice

The next real slice should include only the Polaris surface needed to make Deck
feel like Nova instead of a generic Moonlight client:

- host capability probe that distinguishes Polaris from standard
  Moonlight-compatible hosts
- library/app card model with Polaris metadata when available and standard app
  list fallback
- launch intent containing host id, game id/UUID, launch mode, stream display
  mode/headless/virtual-display hint, and safe debug copy
- session truth for active/inactive, owned-by-this-client, owner name/device,
  watch eligibility, quit/resume/replace permissions, and session token plumbing
  where exposed
- client presentation/tuning summary for target fps, bitrate, codec, display
  mode, sync state, source of truth, and relaunch-required messaging
- HUD/reconnect event stream for connection stages, transient warnings, poor
  connection, no-video/no-frame/protected-content/early-termination, suspend,
  and reconnect status

Defer rich optimizer controls, profile editing, capture diagnostics UI, full
NovaHUD parity, gyro/haptics polish, touch overlays, and every Android quick-menu
action until after the first real Deck stream works.

### First technical risks to test next

1. Video presentation: can a Deck-native adapter consume `moonlight-common-c`
   decode units and present low-latency fullscreen frames with overlay hooks?
2. Audio: can decoded PCM play through PipeWire, PulseAudio, or temporary SDL
   without drift or bad teardown?
3. Controller input: can Deck controls be split cleanly between shell shortcuts
   and in-stream Moonlight controller packets in Game Mode?
4. Host discovery/pairing: can manual add, pairing, and pinned certificate
   storage work without Android `Context`, NSD, or keystore assumptions?
5. Suspend/resume: can the session boundary interrupt, stop, reconnect, and
   report recovery states safely?
6. Game Mode fullscreen: can the Qt shell enter stream fullscreen, keep focus,
   and exit/recover with readable Nova copy?

## Deck-T7 hardware-backed Linux video/audio prototype decision

Decision: the first hardware-backed Deck prototype should use
FFmpeg/libavcodec H.264 decode with VA-API on Steam Deck-class AMD Linux
hardware, then present through the existing Qt Deck shell via a Qt Quick/QRhi
scene-graph item instead of taking over raw DRM/KMS. Audio should start with a
native PipeWire stream fed from the Moonlight Opus/audio callback path, with
PulseAudio compatibility as the first fallback and SDL audio only as a temporary
throwaway spike if PipeWire blocks the lifecycle proof.

This keeps the direct `moonlight-common-c` decision intact while choosing the
first concrete Linux media boundary. The existing Deck CMake target already links
the checked-out `moonlight-common-c` tree, and the no-network stream-core seam in
`clients/deck/src/stream/deck_stream_core.h` owns real
`CONNECTION_LISTENER_CALLBACKS`, `DECODER_RENDERER_CALLBACKS`,
`AUDIO_RENDERER_CALLBACKS`, and `STREAM_CONFIGURATION` storage. Deck-T6 proved
those callbacks can be initialized and routed through Nova-owned renderer,
audio, input, and session-event interfaces without `LiStartConnection` or
sockets. Deck-T7 therefore chooses the first hardware-backed adapter
implementation target rather than changing the product shell or claiming a real
stream.

### Why this path wins

- FFmpeg fits `moonlight-common-c`'s Annex-B decode-unit callback shape and lets
  the first proof focus on H.264 before HEVC, AV1, 10-bit, HDR, and advanced
  reference-frame invalidation capability gates.
- VA-API is the lowest-friction hardware-decode target for Steam Deck-class AMD
  Linux systems. Vulkan decode may become useful later, but it is too much API
  surface for the first offline harness.
- Presenting inside Qt Quick/QRhi preserves Nova's controller-first shell,
  overlays, copy affordances, stream-stop confirmation, focus recovery, and
  suspend/resume messaging. Raw DRM/KMS is rejected for the first product slice
  because Steam Deck Game Mode already runs apps under gamescope; bypassing the
  shell would fight the compositor and NovaHUD composition before the stream
  lifecycle is proven.
- PipeWire is the right SteamOS-era audio target and still gives a PulseAudio
  compatibility lane. ALSA-only output is too low-level for the first handheld
  lifecycle proof, and SDL should not become a product dependency just because it
  can make PCM noise quickly.
- Local Fedora dependency probes found the expected development packages for the
  next CMake probe (`libavcodec`, `libavutil`, `libva`, `libva-drm`, `libdrm`,
  `egl`, `wayland-client`, `Qt6Quick`, `libpipewire-0.3`, and `sdl2`). That does
  not guarantee SteamOS packaging, but it means the next local harness can test
  real headers/libraries rather than a paper backend.

### Rejected alternatives

- **Raw DRM/KMS/EGL first:** useful later for benchmarking or a minimal renderer
  harness, but rejected as the first product path because it bypasses the Qt
  shell and risks gamescope/focus/suspend fights.
- **SDL2 as the main stream runtime:** useful for isolated probes, but rejected
  as the primary path because it would introduce a second window/input/audio
  model beside the existing Qt shell before Nova has proven the stream lifecycle.
- **GStreamer first:** defer unless FFmpeg/VA-API integration stalls. It can hide
  frame pacing, callback error handling, and overlay timing decisions Nova needs
  to own directly.
- **Software decode first:** diagnostic fallback only; it does not answer the
  hardware-backed Deck question.
- **PulseAudio-only or ALSA-only first:** PulseAudio remains a compatibility
  fallback through PipeWire; ALSA is too raw for the first suspend/resume and
  device-routing proof.

Detailed Deck-T7 notes and the Deck-T8 card live in
`clients/deck/spikes/streaming-backend-notes.md`.

### Recommended next implementation card

Deck-T8 first hardware-backed Linux renderer/audio harness: add a local/offline
prototype under `clients/deck` that builds only when the required development
packages are present. Connect the existing no-network stream-core callbacks to an
FFmpeg+VA-API H.264 renderer adapter and a PipeWire audio adapter, feed them from
deterministic test data created at test time or checked-in source code only when
licensing/provenance is explicit, and prove setup/start/submit or
decode/play/stop/cleanup boundaries without `LiStartConnection`, sockets, host
discovery, pairing, credentials, native asset blobs, Android changes, or fake
streaming UI. Required verification: core Deck CMake/CTest, Qt smoke when
available, adapter CTest or probe skip with a clear dependency message,
fullscreen/offscreen shell boundary notes, `git diff --check`, and independent
review before commit.
