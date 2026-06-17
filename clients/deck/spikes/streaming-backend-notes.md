# Deck-T4 Streaming Backend Decision Notes

Status: decision spike, not an implementation claim. No network calls, host probes, copied native assets, or Steam Deck support claims were added for this spike.

## Recommendation

Build the first real Deck streaming vertical slice as a Nova-owned native Linux client that links `moonlight-common-c` directly, then supplies Nova's own Linux backend adapters for decode/presentation, audio, input, discovery, pairing, persistence, and Polaris session orchestration.

Do not fork an existing Linux Moonlight client as the product base for Deck-T4. Existing Linux clients remain useful references for backend behavior, dependency choices, and SteamOS quirks, but the first Nova slice should keep ownership of the shell, state machine, Polaris surfaces, and stream lifecycle boundary inside `clients/deck/` / `shared/stream-core/`.

## Evidence from current repo

- The existing Deck client is intentionally a preview shell. `clients/deck/README.md` says it validates layout, fake host/library states, inert launch preview, clipboard copy feedback, and controller primary-action routing only. It explicitly does not perform backend launch, streaming, discovery, pairing, persistence, network calls, shell execution, or real game launch behavior.
- The current Deck scaffold is already native CMake/C++20 with optional Qt/QML. The no-Qt path builds `nova_deck_core` and tests; the Qt path builds `nova-deck` only when Qt 6 Quick/QuickControls2 is present.
- Android streaming starts through `NvConnection`, which performs host/app/session negotiation, then calls `MoonBridge.startConnection(...)` after installing renderer/audio/listener callbacks.
- The JNI bridge is not portable as-is. `MoonBridge` owns Android/JVM static callback slots and loads `moonlight-core`; `callbacks.c` attaches native threads to the JVM, logs via Android logcat, uses Android CPU feature helpers, and forwards video/audio/listener callbacks back into Kotlin.
- The portable seam is lower: `moonlight-common-c` exposes `LiStartConnection(...)` with `CONNECTION_LISTENER_CALLBACKS`, `DECODER_RENDERER_CALLBACKS`, and `AUDIO_RENDERER_CALLBACKS`. That is the right contract to wrap for Deck, not the Android JNI bridge.
- `moonlight-common-c/README.md` says the C library is the shared GameStream client core used by Moonlight clients and is the right code to use when implementing a client that can use a C library. Its CMake also bundles the specific ENet variant required by the library, so the Deck build should consume that tree deliberately rather than accidentally linking a system ENet.
- Android-specific pieces are heavy: `MediaCodecDecoderRenderer`, `AndroidAudioRenderer`, Android discovery services/NSD/JmDNS wrappers, Android `Activity`/`Service` lifecycle, notification/keep-alive behavior, Android input capture, touch, USB controller drivers, and accessibility/key handling. These are behavior references, not source to port.
- Polaris product value currently lives above the stream core: capabilities, library, session truth, launch/watch modes, client settings, sync status, tuning, capture/encoder metadata, and NovaHUD/quick-menu state. A Deck backend that starts from a generic client shell would need to carve those surfaces back into someone else's navigation and state model.

## Compared paths

### A. Direct `moonlight-common-c` integration with Nova-owned Linux backends

Use this for the next vertical slice.

What gets reused:

- GameStream/Moonlight transport core, RTSP/control/input/audio/video stream machinery, port-stage callbacks, network connectivity helpers where portable.
- Existing Android behavior as a reference for host negotiation, paired/owned/watch-only handling, current-game/session-token decisions, launch/quit/resume behavior, error copy, and Polaris session truth.
- Shared Polaris DTOs and future `shared/stream-core` contracts.

What Nova must own on Deck:

- A `DeckStreamingSession` boundary that wraps `LiStartConnection`, cancellation, stage transitions, and reconnect/suspend outcomes.
- Linux decode/presentation adapter that implements `DECODER_RENDERER_CALLBACKS` without involving Android `MediaCodec` or JNI.
- Linux audio adapter that implements `AUDIO_RENDERER_CALLBACKS` without `AudioTrack`.
- Input adapter that maps Steam Deck controls to Moonlight controller packets and keeps Nova UI shortcuts separate from in-stream input.
- Linux host discovery and pairing storage with certificate pinning semantics matching Nova's Android security posture.
- Polaris-aware library/launch/session/tuning surfaces in the native shell.

Why this preserves Nova:

- Nova keeps the controller-first Deck shell and stream state machine instead of inheriting a generic Moonlight client UI.
- Polaris surfaces can be first-class: Continue, launch mode, watch/owned-session status, tuning/sync truth, NovaHUD, reconnect copy, and safe diagnostic copy are product primitives rather than add-ons.
- Standard Moonlight-compatible hosts still work because the transport remains `moonlight-common-c`; Polaris simply enriches the host and session model.

Risks:

- More backend code up front.
- Need to prove the Deck decode/presentation path before investing in more UI.
- Need a clean Linux input path that does not fight Steam Input or Game Mode focus.
- Need a suspend/resume policy because `LiInterruptConnection()` is asynchronous and Android currently serializes connection start/stop through `NvConnection`/`MoonBridge` locks.

### B. Adapt/fork an existing Linux Moonlight client

Do not use this as the product base for Deck-T4.

What it could reuse:

- A working Linux streamer stack around decode, audio, input, fullscreen, and packaging.
- Known Moonlight-compatible host behavior and SteamOS-ish runtime knowledge if verified in that client.
- Potentially faster first picture on screen.

Why it fights Nova:

- Nova's differentiator is not the generic app list/stream button; it is Polaris-aware handheld behavior. Forking another Linux client means either grafting Polaris into its screens/state model or replacing those screens anyway.
- The current Deck scaffold already has a Nova-owned preview shell and product boundary. Forking now would duplicate or discard that work.
- Another client may encode assumptions about settings, app launch, session ownership, overlays, shortcuts, and host records that differ from Nova's Android reference behavior.
- Maintenance would follow another upstream's UI/runtime architecture instead of a shared Nova `stream-core` boundary that Android, Deck, and future clients can use.

When to revisit:

- If the first decode/presentation spike cannot produce stable low-latency video on SteamOS after testing VA-API/FFmpeg/GStreamer-style candidates.
- If a small, separable backend component can be reused without importing the whole client shell and state model.
- If license/compliance review says adapting a specific client source is simpler than building equivalent adapters. Nova is already GPLv3-lineage, but imported code still needs provenance and attribution review before copying.

## Backend candidates for the direct path

### Video decode and presentation

First candidates to test:

1. Hardware decode through a Linux media stack such as FFmpeg/libavcodec with VA-API on Steam Deck-class AMD hardware, then present into the Deck shell via an explicit renderer surface.
2. GStreamer only if it shortens VA-API + presentation integration without hiding frame pacing/control details Nova needs.
3. Software decode only as a fallback/diagnostic path, not the target.

Decision for next test: prove H.264 first, then HEVC/AV1 and HDR after the control/audio/input skeleton works. Validate 1280x800 fullscreen, frame pacing, resize/fullscreen transitions, and overlay composition hooks before polishing library UI.

### Audio

First candidates:

- PipeWire as the preferred SteamOS-era target if it is straightforward to feed decoded PCM with acceptable latency.
- PulseAudio compatibility path if PipeWire integration adds too much first-slice friction.
- SDL audio is acceptable as a temporary spike adapter only if it gets decoded PCM playing quickly while the session boundary stabilizes.

The adapter must handle `AudioRenderer`-equivalent setup/start/stop/cleanup and arbitrary audio duration support because the Android native bridge advertises that capability today.

### Controller and Steam Input

First slice should handle the built-in Deck controls as a normal gamepad path first, using SDL/GameController-style mapping or a minimal evdev adapter if SDL is not introduced yet. Keep two routes distinct:

- Shell navigation shortcuts: focus movement, command center, NovaHUD, copy/diagnostics, stream stop confirmation.
- In-stream packets: Moonlight controller arrival, button/stick/trigger packets, mouse/keyboard escape hatches later.

Steam Input should be treated as an integration surface to test in Game Mode, not as an excuse to hardcode host-specific profiles into Nova.

### Discovery and pairing

Minimum next-slice surface:

- Manual host add first, because it avoids a network discovery matrix while the stream backend is still proving itself.
- Pairing/session credential storage that matches Nova's security posture: local-only, no backup/export by accident, pinned server cert after pairing where available.
- LAN discovery via Linux mDNS/zeroconf only after manual add + pairing contracts are stable.

### Fullscreen, Game Mode, and suspend/resume

The first real stream test must run in a fullscreen Game Mode-like path, not only a Desktop Mode window. It should record:

- Whether the shell can enter/exit fullscreen cleanly.
- Whether focus/controller input returns after overlay or stream transitions.
- What happens when the system suspends during connecting, active streaming, and disconnecting.
- Whether reconnect copy matches Android's owned/watch/session failure semantics.

## Minimum Polaris surface for the next Deck vertical slice

Required to feel like Nova rather than Moonlight cosplay:

- Host capability probe: detect Polaris vs standard Moonlight-compatible host.
- Library row/card model using Polaris game metadata when available, with standard app-list fallback.
- Launch intent model containing host id, game id/UUID, requested launch mode, stream display mode/headless/virtual-display hint, and safe copy for UI/debugging.
- Session truth: active/inactive, owned-by-this-client, owner device/name when available, watch-only eligibility, quit/resume/replace permissions, session token plumbing where exposed.
- Client presentation/tuning summary: target fps/bitrate/codec/display mode, source of truth, sync state, and relaunch-required message.
- HUD/reconnect event stream: connection stage, transient warnings, poor connection, no video/no frame/protected content/early termination, suspend/reconnect status.

Defer advanced Polaris surfaces until after first real stream: rich optimizer controls, profile editing, capture diagnostics UI, full NovaHUD parity, gyro/haptics polish, and every Android quick-menu action.

## First technical risks to test next

1. Video presentation: can a Deck-native adapter consume `moonlight-common-c` decode units and present low-latency fullscreen frames with overlay hooks?
2. Audio: can decoded PCM play through PipeWire/PulseAudio/temporary SDL without drift or bad teardown?
3. Controller input: can Deck controls be separated cleanly between shell shortcuts and in-stream Moonlight controller packets in Game Mode?
4. Host discovery/pairing: can manual add + pairing + pinned cert storage be represented without Android `Context`, NSD, or Android keystore assumptions?
5. Suspend/resume: can the session boundary interrupt, stop, reconnect, and report recovery states without corrupting `moonlight-common-c` lifecycle?
6. Game Mode fullscreen: can the Qt shell enter stream fullscreen, keep focus, and exit/recover with readable Nova copy?

## Deck-T7 hardware-backed video/audio prototype decision

Status: accepted follow-up decision for Deck-T7. This is still a local/offline
prototype plan: no `LiStartConnection`, sockets, host discovery, pairing,
credentials, native asset import, generated blobs, Android changes, or fake
"first stream" UI claims are part of this decision.

### Recommended first path

Use a Nova-owned Linux adapter pair behind the existing
`DeckStreamRenderer`/`DeckStreamAudio` seams:

1. **Renderer/presentation:** FFmpeg/libavcodec H.264 decode with VA-API
   hardware acceleration on Steam Deck-class AMD GPUs, exported toward a
   Qt Quick/QRhi scene-graph item in the existing `nova-deck` Wayland window.
   The first harness should prove the adapter can accept Annex-B decode units
   from the `DECODER_RENDERER_CALLBACKS::submitDecodeUnit` shape, keep the
   renderer non-blocking enough for `moonlight-common-c`, and present a
   fullscreen 1280x800 surface with a future NovaHUD/overlay composition hook.
2. **Audio:** PipeWire native output fed by the Moonlight Opus/audio callback
   path, with PulseAudio compatibility as the first fallback because SteamOS
   exposes Pulse-compatible clients through PipeWire. The adapter must honor
   `OPUS_MULTISTREAM_CONFIGURATION.samplesPerFrame` and advertise arbitrary
   duration support only when the implementation really sizes decoded buffers
   from that field.
3. **Shell/runtime boundary:** stay inside the Qt Deck shell for the first
   prototype instead of taking over DRM/KMS. Game Mode should see a normal
   fullscreen application surface through gamescope, letting Nova keep QML
   focus, overlays, copy, stream-stop confirmation, and suspend/resume UI in
   the same process.

This path is grounded in the code already in-tree: `clients/deck/CMakeLists.txt`
links the real `moonlight-common-c` tree, and
`clients/deck/src/stream/deck_stream_core.h` exposes Linux-facing renderer,
audio, input, and session-event interfaces around real
`CONNECTION_LISTENER_CALLBACKS`, `DECODER_RENDERER_CALLBACKS`,
`AUDIO_RENDERER_CALLBACKS`, and `STREAM_CONFIGURATION` structs. The focused
CTest already proves those callback structs can be initialized and routed
without starting the network. Deck-T7's job is to choose the concrete
hardware-backed adapter technology for the next proof, not to start a host
stream.

### Why FFmpeg + VA-API + Qt/QRhi first

- `moonlight-common-c` submits Annex-B elementary stream decode units and asks
  the renderer to return `DR_NEED_IDR` when it cannot process a unit. FFmpeg's
  parser/decoder APIs fit that input shape directly and keep H.264 first while
  leaving HEVC, AV1, 10-bit, HDR, and reference-frame invalidation for later
  capability gates.
- VA-API is the shortest hardware-decode path for Steam Deck-class AMD Linux
  systems and avoids committing Nova to a vendor-specific Vulkan decode stack
  before the lifecycle, timing, and overlay contracts are known.
- Presenting through a Qt Quick/QRhi item preserves the existing controller-first
  shell and gives Nova an overlay lane. A raw DRM/KMS renderer might be useful
  for a narrow benchmark later, but in Steam Deck Game Mode it risks fighting
  gamescope, focus restore, suspend/resume, and NovaHUD composition before the
  stream lifecycle is proven.
- The Fedora development host already has the relevant development packages
  discoverable by `pkg-config` (`libavcodec`, `libavutil`, `libva`,
  `libva-drm`, `libdrm`, `egl`, `wayland-client`, `Qt6Quick`, and
  `libpipewire-0.3`). Those probes are not product guarantees, but they show the
  next local CMake probe can test real headers/libraries instead of inventing a
  paper backend.

### Why PipeWire first for audio

- SteamOS-era desktops route audio through PipeWire, and PipeWire also covers
  PulseAudio-compatible apps. Starting with PipeWire keeps the target modern
  while preserving a fallback if the first slice needs compatibility glue.
- The Moonlight audio contract is small: `init`, `start`, `stop`, `cleanup`, and
  `decodeAndPlaySample(char*, int)`. A dedicated PipeWire stream can own latency,
  queue draining, and teardown explicitly instead of hiding it behind a game
  framework introduced only for audio.
- SDL audio remains acceptable as a throwaway emergency spike, but not the
  recommended product path. If Nova brings SDL in, it should be for a deliberate
  gamepad/window/input decision rather than as an incidental audio-only
  dependency.

### Rejected alternatives

- **Raw DRM/KMS/EGL primary renderer:** rejected for the first product slice.
  It could be useful for a dedicated benchmarking harness, but it bypasses the
  Qt shell that currently owns layout, focus, copy, and future NovaHUD surface.
  It also risks conflicting with gamescope and suspend/resume expectations in
  Steam Deck Game Mode.
- **SDL2 window/audio/input as the main streaming runtime:** rejected for now.
  SDL2 is installed and useful for isolated probes, but replacing or embedding a
  second window/input model would split shell navigation from stream focus before
  Nova has proven the renderer/audio lifecycle.
- **GStreamer:** defer. It may simplify a complete media pipeline, but it can
  hide frame pacing, decode-unit error handling, and overlay timing decisions
  that Nova needs to own around `moonlight-common-c` callbacks.
- **Vulkan decode:** defer until after H.264/VA-API works. It may become the
  right long-term zero-copy path, but it is too much API surface for the first
  no-network hardware proof.
- **Software decode:** keep only as a diagnostic fallback. It does not answer
  the Steam Deck hardware-backed question.
- **PulseAudio-only or ALSA-only audio:** reject as first choice. PulseAudio is a
  compatibility fallback through PipeWire; ALSA is too low-level for the first
  handheld lifecycle proof and makes device routing/suspend rougher than needed.

### Deck-T8 implementation card

Deck-T8 first hardware-backed Linux renderer/audio harness: add a local/offline
prototype under `clients/deck` that builds only when the required development
packages are present. It should connect the existing no-network stream-core
callbacks to an FFmpeg+VA-API H.264 renderer adapter and a PipeWire audio adapter,
feed them from deterministic test data created at test time or checked-in source
code only when licensing/provenance is explicit, and prove setup/start/submit or
decode/play/stop/cleanup boundaries without `LiStartConnection`, sockets, host
discovery, pairing, credentials, native asset blobs, or Android changes. Required
T8 verification: core Deck CMake/CTest, Qt smoke when available, adapter CTest or
probe skip with a clear dependency message, fullscreen/offscreen shell boundary
notes, `git diff --check`, and independent review before commit.
