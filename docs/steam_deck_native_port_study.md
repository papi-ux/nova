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

## First implementation spike

Before full implementation, run one short spike that answers these questions decisively:

1. Is a new Qt/QML client with `moonlight-common-c` easier to shape around Nova than adapting an existing Linux Moonlight client?
2. Which Linux decode and presentation backend gives the cleanest Steam Deck path with acceptable latency?
3. What is the minimum Polaris surface needed to make the Deck client feel meaningfully like Nova instead of a generic Moonlight shell?

If that spike does not invalidate the assumptions above, proceed with Option A.
