# Deck release parity

Owner-approved target: standalone Nova with built-in streaming, OLED HDR10 at
90 fps, and full Android product parity. Android reference:
[`a14758c8`](https://github.com/papi-ux/nova/commit/a14758c8487c03235c4bd91eb63606d5eee71181),
released as **v1.4.11**. The owner expanded the reference on 2026-09-20 and
authorized the complete UI/UX and supporting feature parity pass, developed and
tested locally. Later releases do not silently move this reference.

The [Nightly acceptance contract](https://github.com/papi-ux/polaris-nightly/blob/docs/deck-first-reconciliation/docs/deck-release-acceptance-v1.md)
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

| ID | Capability and Android reference | Original Deck audit status (2026-09-17) | Required acceptance |
|---|---|---|---|
| P01 | Servers, discovery, manual add, Wake-on-LAN (`PcView`, discovery/manager) | Partial: existing Moonlight identities and live probes | Fresh install finds/adds, wakes, selects, forgets and rechecks hosts without Moonlight. Offline and service-unavailable states differ. |
| P02 | PIN/QR pairing and trust (`PairingManager`, `NovaQrScanActivity`) | Partial: native PIN and Trusted Pair, touch address/port keypad, pinned reads of imported identity | Trusted Pair uses explicit host capability and the Android handshake, with PIN fallback only when unavailable. Native identity persistence, certificate mismatch, re-pair and revoke tests are local; installed trusted pairing acceptance and QR/OTP remain open. QR uses an available camera or image/file input; no built-in Deck camera is assumed. |
| P03 | Library, details, source/platform/runtime labels (`NovaLibrary*`, `NovaGameDetail*`) | Partial: standalone browsing, cinematic details and optional host duration metadata | Browse/search/select real libraries and emulator/utility entries with controller/touch; empty, offline and unavailable states remain actionable. |
| P04 | Live library/artwork refresh (`NovaLibraryLiveRefresh`, `NovaArtworkLibraryUpdater`) | Partial: pinned polling and cached artwork; events/reconnect remain open | Host edits update an open library without losing selection, identity or per-game settings. Reconnect resynchronizes. |
| P05 | Artwork Studio (`NovaArtworkStudio`) | Missing | Desktop search, select, reset and save plus device-local logo placement are implemented locally. Space previews, automatic artwork refresh and local logo placement are also implemented locally; manual Space image selection requires a Polaris API extension. Full Space editing and installed acceptance remain open. |
| P06 | Pin to Home Screen and pinned state | Partial: Nova app Steam shortcut only | Canonical per-game Steam entries, available artwork, stable IDs and Steam-running refusal/retry are implemented locally. Actual Steam round-trip, upgrade and installed launch acceptance remain open. |
| P07 | Play Setup (`NovaPlaySetup*`, `NovaLaunch*`) | Partial | Destination-aware read/action columns and independent per-game resolution, FPS, bitrate, face-button and permitted launch-mode choices/reset are implemented locally. Encoder/preset choices, scoped host defaults, Steam behavior and a verified read-only resolved plan are now implemented locally; custom stream profiles, advanced safe host resolutions and the PC-wide resume timeout are now implemented locally; complete settings/Sync and installed acceptance remain open. |
| P08 | Face-button layout and per-game overrides (`NovaFaceButtonLayoutOverrides`) | Missing | Label/position choices persist per game and affect host input consistently. |
| P09 | Spaces (`NovaSpace*`) | Partial | Local paired Desktop/Space chooser, scoped library/artwork, permission/readiness copy and fresh destination launch guard are implemented. Full worker launch/profile, session recovery and installed permitted/denied Space acceptance remain open. |
| P10 | Native session start and media (`NvConnection`, `MoonBridge`, decoder/audio bindings) | Partial: headless native H.264 decode | GUI launches without blocking; real video, sound and controls remain in Nova; cancellation at each connection stage cleans up. |
| P11 | Ownership, watch, resume, disconnect and quit | Partial: native stop/host cancellation | Distinguish disconnect from ending the owned host game; watch/non-owner paths cannot mutate or terminate another session. |
| P12 | Reconnect and suspend (`Game`, `ReconnectOverlay`) | Partial: termination bookkeeping | Network/host interruption and suspend recover or return an actionable terminal state without stale sessions or stuck input. |
| P13 | SDR/codec/pacing/scaling settings | Partial: local scaling and lowest-latency/Balanced delivery choices; remaining codec/pacing and physical acceptance remain open | Honor effective stream settings with supported codec selection, stable frame pacing and truthful fallback. Standard hosts remain supported. |
| P14 | HDR and display rates (`GameHdrDecision`, display/device policies) | Missing | LCD SDR60 and OLED SDR90/HDR90 including HDR+90 together; Main10 decode, correct color/metadata and physical presentation, not counters alone. |
| P15 | Audio output, channel configuration, host audio and effects | Missing: callback counters only | Opus decode and native playback, channel mapping, output/routing changes, supported processing preferences and A/V sync through teardown/recovery. |
| P16 | Command Center (`NovaQuickMenu*`) | Missing | All reference actions and states reachable by controller with safe focus return; no overlay shortcut leaks into the game. |
| P17 | NovaHUD (`NovaHud*`, `NovaStreamHud*`) | Missing | Full/slim/FPS modes, opacity/layout preferences, current client/host stats, provenance and unavailable states; no fabricated media loss or presentation rate. |
| P18 | Live Tuning (`NovaPolarisSync*`, live-tuning contract) | Missing | Paired revision-aware changes, encoder-confirmed applied bitrate, enable/disable/fixed-rate semantics and stale event resync. |
| P19 | Doctor, support reports, Auto Fix and Undo | Partial | Refresh current evidence, distinguish host/network/client findings, execute only permitted reversible actions, verify/undo exact session changes and export sanitized reports. |
| P20 | Polaris Sync and settings ownership | Partial: library Sync and guarded in-game profile/live controls | Same host/client/per-game scope, conflict/revision behavior, relaunch-required messaging and offline recovery as the reference. |
| P21 | Themes, layouts, font scale and accessibility (`NovaTheme*`, library/menu preferences) | Partial: six themes, Grid/Compact/Stage and 100/115/130% text | Reference theme choices and high-contrast/font-scale behavior adapted to 1280x800, controller focus, touch and external display layouts. |
| P22 | Built-in/external controllers and Steam Input | Partial: shell primary/secondary actions | Full buttons/sticks/triggers, deadzones, hotplug/multiple controllers and correct controller identity; no double delivery. |
| P23 | Mouse, keyboard, touch, gestures and virtual controls | Missing | Mirror Desktop and game input, relative/absolute modes, cursor/trackpad preferences, touch gestures and virtual keyboard/control customization with native focus/capture. |
| P24 | Gyro, rumble, audio haptics and advanced controller features | Missing | Supported motion/touchpad/LED/battery/rumble features and gyro aim preferences work on applicable devices; no false support for absent hardware. |
| P25 | Companion/external display behavior | Missing | Native display targeting, companion controls, focus, hiding and restoration preserve the player capability when a second display exists. |
| P26 | Background/session affordances and keep-awake | Missing | Game Mode/application lifecycle and native notifications preserve session visibility and safe control; no Android service or notification API is required. |
| P27 | Settings persistence, reset, logs and import/export | Partial: searchable Settings hub, shared in-game video scaling, existing preference stores and individual device-setting resets | All applicable reference preferences have native equivalents, survive upgrades, reset correct scopes, and export no credentials or private raw diagnostics. Full preference coverage and settings import/export remain open. |
| P28 | Install/update/release | Partial: shell Flatpak and CI | Self-contained signed/versioned distribution with explicit permissions, reproducible dependencies, Steam entry, fresh install and upgrade; APK release flow remains intact. |

The settings audit includes every leaf preference in the frozen
[Android preference catalog](https://github.com/papi-ux/nova/blob/a14758c8487c03235c4bd91eb63606d5eee71181/app/src/main/res/xml/preferences.xml)
and the modern settings, per-game override and UI-state implementations at that
same commit. Category-to-capability coverage is: stream quality P07/P13/P14;
display/audio P13/P15; dual screen P25; input/controllers P08/P22/P23/P24;
overlays/controls P16/P17/P23/P24; appearance/accessibility P21;
network/session/advanced/storage/support P01/P02/P11/P12/P19/P20/P26/P27.
An implementation PR names the exact preferences it closes and retains their
default, scope, availability, persistence and reset behavior in its tests.

## v1.4.11 parity pass (2026-09-20)

The original status column is historical; the progress entries below retain the
completed local work. A theme, menu label or disabled placeholder alone does not
close its supporting feature. The ordered work packages are:

| Package | Required completion | Capabilities |
|---|---|---|
| Shared presentation | Android theme tokens, shared controls/sheets, font scaling, immediate touch-to-D-pad focus, responsive Grid/Compact/Stage and reference captures | P03/P21/P27 |
| Browsing and setup | Full details, Artwork Studio, per-game Steam shortcuts, discovery, QR/file pairing, Wake and hold/countdown Sleep Host | P01-P06 |
| Play Setup and settings | Every reference preference, scope/reset/migration, destination and Spaces, effective encoder/display plan, Steam behavior | P07-P09/P20/P27 |
| In-game experience | Command Center, unboxed HUD with measured HOST/NET/CLIENT layers, Live Tuning, Doctor receipts/verification/Undo, Sync | P11/P16-P20 |
| Players and input | Named controllers before joining, reserved built-in P1, explicit press-order Reassign, per-player feedback, mouse/keyboard/touch/gyro | P08/P22-P24 |
| Native completion | Codec/pacing/scaling, HDR90 presentation, audio processing, recovery, external/companion display and lifecycle | P10-P15/P25-P26 |
| Admission | Local Flatpak build/upgrade evidence, then separately scheduled physical acceptance and distribution | P28 |

Sleep Host follows v1.4.10/v1.4.11's capability and permission checks, one
non-retried request after the cancellable hold/countdown, and confirmed outcome.
Players/Reassign also works with standard hosts. An explicit Reassign releases
the initial built-in P1 reservation and assigns all pads by subsequent press
order, as Android does. v1.4.11's Play Setup copy describes the selected place;
the Doctor retains actionable display-override evidence even under a green
verdict. These additions do not waive any pre-existing acceptance row.

### Local implementation progress — 2026-09-20

The first v1.4.11 implementation covers shared theme tokens and button/scroll
controls, persisted appearance and 100/115/130% text size, responsive portrait
posters in Compact, and focus-preserving library/Play Setup navigation. All six
reference theme names are selectable. Material You uses the desktop palette's
accent; full Android dynamic-color derivation is still open. Pairing, numeric/ABC
entry, saved PCs, audio and rumble use the shared palette; longer forms scroll to
the focused control at larger text sizes.

The active-stream overlay now has the Command Center header with Close,
Disconnect and End Session, named Players (including waiting controllers),
Reassign, and Appearance. Input packets and feedback carry each player's slot;
Reassign releases old input and feedback, removes the initial handheld P1
reservation, and joins by fresh presses after neutral input. Disconnecting P2
preserves P1. Steam Input virtual controllers are identified by Valve device IDs;
when accessible virtual pads are present, their set takes precedence over raw
physical nodes. The built-in reservation follows the primary Steam Input slot on
Deck, which still needs Game Mode/controller-reordering acceptance.

System → Sleep Host now implements the Android hold and cancellation flow for
paired Polaris PCs: hold for one second, then a five-second Cancel countdown.
Capabilities, host enablement, client permission and saved identity are checked
again before one pinned, non-replayed POST. Focus loss, early release, selection
changes and active streaming prevent a pending action. Touch, keyboard and
physical-controller hold lifetimes share the same backend timer. Acceptance is
followed by reachability checks; a PC that remains reachable shows a fresh host
outcome when available, otherwise an explicit still-responding result. A dropped
answer is never retried or reported as confirmed sleep.

Local policy, production-QML, actual-app and loopback mTLS tests cover the full
flow, including permission withdrawal during the countdown, cancellation,
certificate mismatch, dropped replies and redirect refusal. The local artifact,
source manifests and reviewed captures are recorded in
`build/deck-host-power/EVIDENCE.md`. Physical Deck holds, host suspend/wake and
installed-artifact acceptance remain open; no real host was put to sleep.

NovaHUD now has local Slim, Minimal, Performance and Debug layouts matching the
reference's compact rows and unboxed HOST/NET/CLIENT groups. Command Center
exposes visibility, layout, background opacity and position; dragging persists a
bounded position, plain taps pass through and a hold opens Command Center.
Settings survive restart, large text and resize; the HUD hides in controls and
non-active session states. Default visibility is off, mode Minimal and opacity
64%, matching Android.

The native worker samples once per second. Received video payload bitrate,
incoming complete video units, decoded frames, scenegraph-composed frames,
GameStream RTT/variation and optional frame-header host processing time remain
distinct. The primary readout says COMP FPS; it does not claim panel cadence.
Stalls, missing readings, counter resets and reconnects clear stale values and
the bounded sparkline. No per-frame string formatting is added.

The local host-observation slice now reads pinned `/polaris/v1/session/status`
once per second on a separate cancellable worker, with bounded responses,
transient backoff and no tuning mutations. It requires the active owned game,
exact token/UUID, current saved identity and consistent generation/app session.
Canonical tuning snapshots enforce increasing sequence and retired-host-instance
rejection. Debug distinguishes encoder-applied bitrate, quality limit and received
video payload throughput. Present-but-invalid canonical status is Unknown;
legacy preference alone cannot acknowledge encoder bitrate. Doctor v2 takes
precedence over legacy health, preserving v1.4.11 display-override evidence even
under a green verdict. Control-channel observations do not become media loss.
Errors immediately clear host values; 3.5-second expiry also covers blocked reads.
Standard hosts retain local metrics without Polaris polling.

These local layout/preferences, available metrics and read-only health/tuning
slices are off the implementation queue. Decode latency, media loss, drops,
1% lows, event breadcrumbs and full P17 remain open. Evidence:
`build/deck-hud/EVIDENCE.md` and `build/deck-hud-host/EVIDENCE.md`.
Physical Deck input, media and installed acceptance remain open.

Command Center now adds the immediate Live Tuning On/Off switch. The row retains
focus while pending and presents confirmation/error copy within its scrollable
bounds. No optimistic preference or bitrate is painted. A save performs a fresh
pinned status read and rejects changed permission, ownership, pairing, token,
generation, app session, host instance, sequence or configuration revision.
Exactly one conditional paired POST is followed by status refresh, including
after a dropped response or HTTP 412. Refresh cannot automatically replay the
operator's earlier decision. Stream teardown cancels outstanding work; tuning
requests run outside the controller-input worker. Encoder-applied bitrate stays
separate from the saved preference. Canonical status is required for Deck saves;
legacy status remains read-only. This enable/disable implementation slice is off
the queue; legacy mutation compatibility,
full P18 and physical tuning acceptance remain open.
Evidence is in `build/deck-live-tuning/EVIDENCE.md`.

Fixed live bitrate now has a local 1–300 Mbps picker with touch, controller steps,
explicit Apply and Back without mutation. One paired `/session/bitrate` request
turns tuning off and supersedes pending Doctor bitrate actions on the host;
next-launch Play Setup remains separate. The observer rechecks permission,
pairing, exact session identity, canonical revision and quality limit before
sending. The host enforces paired identity, sole ownership and session scope, but this
route does not support a server-side configuration-revision CAS. Failed
or dropped responses are never replayed. The receipt's bitrate is a controller
target, so fresh canonical applied bitrate, tuning Off and matching quality limit
are required for encoder confirmation. Delayed confirmation is observed for five
seconds plus any in-flight bounded read. Missing, mismatched or failed readings
stay unconfirmed. This fixed-rate implementation slice is off the queue; physical
encoder behavior, legacy compatibility and full P18 remain open.
Evidence is in `build/deck-live-bitrate/EVIDENCE.md`.

Paired session-event resynchronization is now implemented locally. The selected
host's pinned status must advertise a valid HTTPS port; Deck never guesses a web
port or accepts an event-provided URL. A separate mTLS worker uses the fixed
`/api/polaris/events` route, exact paired certificate, no redirects and no replay
cursor. Connection/reconnection, event-ID gaps/duplicates, invalid or regressed
tuning and meaningful state changes invalidate readings and coalesce fresh status
requests. Unchanged canonical heartbeats do not trigger additional reads. Events
remain hints: they cannot establish ownership, paint encoder acknowledgement or
terminate a stream. Overlapping GETs and mutation preflights cannot restore stale
authority. Focus stays on the tuning row while “Refreshing…” blocks activation;
confirmed permission withdrawal still returns focus to NovaHUD.

Bounded parsing, idle deadlines, cancellable backoff and independent thread
ownership keep input responsive. Missing/unsupported or malformed event services
retain paired polling; pin/authentication failures stop tuning authority. Endpoint
replacement cancels the old stream and ignores its late callbacks. Tests cover
the parser, observer races, production target over loopback mTLS, native input/
teardown and production QML. This event-resync slice is off the implementation
queue. Real-host/installed acceptance, lifecycle/lock-screen event presentation,
HUD breadcrumbs, library event invalidation, legacy mutation compatibility and
full P18 remain open. Evidence: `build/deck-session-events/EVIDENCE.md`.

The read-only Doctor screen and session diagnostics are now implemented locally.
Command Center opens Diagnosis, Evidence and Session pages with touch selection/
scrolling and explicit D-pad page, scroll, refresh and Back routes. As in the
Android v1.4.11 diagnosis card, the finding leads, followed by the first supported
actionable evidence, confidence beside that evidence and a manual next step.
Known Doctor v2 evidence is projected with fixed labels and typed measurements;
raw prose, action endpoints/payloads, result/session IDs, paths and AI advice are
excluded. Unknown schemas or ambiguous duplicate evidence do not revive a legacy
diagnosis. Display-mode override evidence (including Android's `launch` source)
survives a healthy verdict; control-channel loss and informational watches remain
observational, and untyped/unproven network values cannot claim confirmed pressure.

Session separates HOST / NET / CLIENT metrics and explicitly distinguishes
encoder-applied bitrate, quality ceiling, received throughput and composed FPS.
Refresh readings is one coalesced status GET on the existing worker, with a
cooldown, event/in-flight invalidation, no host mutation and no blocked input.
Stale, foreign, failed or ending sessions clear Doctor findings. Refresh keeps
focus; Back restores the Doctor row, and resume/terminal transitions close the
modal. This read-only implementation slice is off the queue. Full P19 remains open.
Evidence: `build/deck-doctor/EVIDENCE.md`.

The reversible live-bitrate Doctor action slice is now implemented locally.
Typed Android-compatible envelopes gate Lower bitrate/Restore quality using
media-loss/RTT provenance and the validated quality ceiling. Auto Fix preflights
the reviewed target, action, controller authority and exact owned stream before
one fixed-route paired POST. Receipt identities stay outside QML. Applying,
watching, verified, undone, rolled back, superseded and unconfirmed rollback have
distinct outcomes. Verification uses the host's delay and scoped run, requires
an explicit complete evidence window before Verified, and is bounded to 64
checks/three minutes. Failure pauses automatic verification and offers an explicit
Check result; only a missing initial receipt can be recovered with the original
idempotency key/payload. Verify/Undo are single-shot transitions. Fresh status is
required before receipt actions; scope/permission loss retires local authority.
Touch/D-pad controls preserve focus and show the operation outcome above the fixed
footer. Contract/state-machine, production mTLS factory, native input/teardown and
production-QML fixtures cover the slice. All mutations are confined to fixtures.
Persistent receipt recovery after restarting the app, richer explanations/custom
evidence, sanitized support reports, Sync, full P19 and real-host/installed physical
acceptance remain open. Evidence: `build/deck-doctor-actions/EVIDENCE.md`.

These are local implementation slices, not completion of P01/P16/P17/P21/P22/P24 or the
full approved pass. Remaining Doctor/report/receipt work, Live Tuning, Sync and HUD work,
discovery/WOL, QR, full settings and complete Spaces support remain open, as do
the other native-media and physical acceptance rows. Later progress entries below
record the local desktop Artwork Studio and per-game Steam entry implementation.
Source, tests, screenshots and the resulting preview artifact are recorded in
local `build/deck-parity-v1411/EVIDENCE.md`; none of this changes installed Deck
acceptance or publishes a release.

The owner-requested 2026-09-20 Doctor Actions preview is now installed and its
running binary verified. Inspection confirmed the desktop library and real
posters on the Deck after restoring this pairing's permitted desktop destination.
This is library/startup evidence only. At installation time P09 still needed an
explicit desktop/Space destination picker and the scoped Space artwork route;
the subsequent local implementation below is not installed evidence.
Desktop/Steam launcher source defaults use `--standalone`. The owner-authorized
2026-09-21 Steam restart repaired both saved Nova shortcuts from `--live` to
`--standalone`, preserving app ID and every other shortcut field. Post-restart
read-back and a normal Steam shortcut launch verified the standalone route and
Gamescope focus. Device repair evidence: `build/deck-launcher-repair/EVIDENCE.md`.
This uses the installed 2026-09-20 Doctor Actions package; subsequent local
feature packages are not installed by this launcher repair. Original deployment
evidence remains in `build/deck-doctor-actions/deck-install/EVIDENCE.md`.

The next local library/details pass follows Android v1.4.11 at `a14758c8`:
compact host/search/filter chrome, unframed portrait posters, a single active
poster outline and 180 ms scale/lift. The overview uses the selected backdrop,
a logo ready at entry or stable title, concise source/last-played metadata,
optional host-provided played time and separate main/extras/completionist length
estimates. Missing values are hidden; zero played time remains explicit. Only
the existing authenticated game-list response supplies these fields. Large-text
details scroll with fixed Play/Back; Play continues to the existing setup review.
Local actual-app checks and screenshots cover both 1280×800 and 960×600, themes,
130% text, late/denied art, pointer selection and focus/scroll restoration.
Evidence: `build/deck-library-polish/EVIDENCE.md`. The local package is isolated
from unfinished persistent Doctor receipt/report work. This is implementation
progress for P03/P21, not completed parity or installed acceptance.

The subsequent local destination/artwork slice implements **Where to play**:
Desktop remains visible with a clear access explanation, assigned Spaces show
current choice and readiness, and focus does not change selection. A fresh
paired permission/previous-selection check precedes one non-replayed POST.
Unconfirmed outcomes clear the old library and require a read-only Refresh.
The authenticated host retains the device's destination. Desktop uses an
explicit desktop query; Spaces use their own library and exact poster/hero/logo/
icon routes. Stale artwork and launch resolvers retire on a change. Space
readiness gates Play, and launch/resume rechecks the selected destination.
Only a 404 Spaces endpoint permits the legacy route; denied or malformed
responses never substitute Desktop. Ordinary compatible hosts retain their path.

Parser/controller tests, actual pinned TLS requests and production-app fixtures
cover lost replies, permission/pin changes, concurrent destination changes,
scoped libraries/assets, controller/pointer focus and 130% text at 1280×800 and
960×600. Evidence and isolated package: `build/deck-spaces/EVIDENCE.md`.
This removes the bounded picker/artwork slice from the coding queue. P09 remains
partial: full worker launch/profile/recovery and prepared-host physical Space
acceptance are still required. Persistent Doctor receipt/report work remains
preserved outside this artifact. No installation or release is claimed.

The local 2026-09-21 Play Setup slice adds Android-style read/action columns with
the reviewed Desktop/Space, effective stream/audio/buttons and launch behavior.
Each setting identifies its explicit or inherited scope, and an individual reset
preserves all other choices. Versioned sparse records read existing full records
without rewriting on load; a reset tombstone prevents old choices returning.
An open review is invalidated when destination identity or readiness changes,
even when a legacy game ID stays the same. The plan is controller-scrollable,
settings restore focus, and Play/Back remain fixed at large text sizes.

Settings, production-QML and actual-app fixtures cover independent reset,
migration/write failure, destination separation, stale review, pointer/controller
navigation, 130% text at both sizes and persistence after restart. Related launch,
stream-plan and audio behavior retain their own regressions. Evidence and isolated
package: `build/deck-play-setup/EVIDENCE.md`. This read-plan/choice slice is off the
coding queue. P07 remains partial: Every Game means Android's host Sync contract,
which is not implemented by these local per-game preferences. Encoder/profile
resolution, presets, custom sizes/rates, Steam launch behavior and installed
acceptance remain open. Unfinished Doctor persistence is still excluded.

The following local **Every Game** slice adds host default-display selection and
desired/effective profile readouts. The host's known mode catalog, unavailable
reasons and desired/effective distinction are retained. An explicit choice
changes the PC default for future Desktop streams; this host-global scope is
stated before selection. Space launch settings and local per-game choices remain
separate. The worker freshly checks identity, capability, reviewed settings and
revision, and host idle state before one standalone paired POST. Only a confirmed
desired mode is presented as saved. Uncertain outcomes clear old authority and
require GET reconciliation. The endpoint has no topology revision CAS; the fresh
comparison does not close the GET/POST race with other host writers.

Local parser/controller, real pinned TLS and actual-app fixtures cover stale
settings, active hosts, unavailable modes, pairing/capability changes, redirects,
dropped replies without replay, cancellation, focus, 130% text and resize. Host
writes invalidate the old Play review, and its warning stays visible above the
scrolling body. Evidence: `build/deck-host-scope/EVIDENCE.md`. This bounded
default-display/profile-readout slice advances P07/P20 and is off the coding
queue. Full Sync and
resolved next-launch encoder/profile truth remain open. No installed acceptance
or persistent Doctor receipt/report completion is claimed.

The following local manual-profile slice adds Match Nova, Send Nova, Use Polaris
and Clear profile (P07/P20/P27). Match/Send write Nova's device stream defaults to
this pairing's profile on the selected PC. Clear removes its display/bitrate
overrides without changing local choices or the PC-wide default display. Use
freshly reads the desired profile with Android's field-by-field effective
fallback and saves Nova's device defaults across PCs. Per-game overrides remain
independent; missing profile fields preserve local defaults. Reset Nova defaults
has a separate device-only scope. Existing sparse/legacy game records and reset
tombstones retain their meaning.

Fresh pairing, advertised profile capabilities, reviewed settings and idle checks
precede one host POST. Uncertain results require GET reconciliation, never replay.
The host profile route has no revision CAS. Local import rejects changed identity,
stale host/local settings, unsupported profiles and failed local persistence;
no partial or rounded import is acknowledged. An attempted host write or saved
device default retires the old Play review. Controller/pointer use, large-text
comparison/recovery and restart/scope evidence is recorded in
`build/deck-profile-sync/EVIDENCE.md`. This supported-profile manual action slice
is off the coding queue. Custom resolutions, additional/fractional rates and
bitrates beyond current client choices, full Sync and installed
acceptance remain required.

The local Keep in step follow-up (P07/P20/P27) adds a per-PC default-off toggle
and view-scoped three-second polling, with a five-second minimum between
automatic profile attempts. Only Nova device defaults are sent; game overrides
and PC-global topology remain independent. A durable paused record precedes each
sync-enabled POST and only a confirmed receipt restores On. An uncertain result,
stale preflight, interrupted write or failed preference save requires explicit
read-back/resume, including after restart. Off remains usable during a pending
sync; Use/Clear/Reset disable it before their explicit action. Periodic checks
preserve reading/controller focus. This deliberately tightens Android's automatic
retry behavior for an endpoint without revision CAS.

Source, backend/transport/app evidence and the isolated local artifact are in
`build/deck-keep-in-step/EVIDENCE.md`. This bounded Keep in step implementation is
off the coding queue; broader Sync controls, custom display profiles, resolved
launch truth and installed acceptance remain open. No unfinished Doctor
persistence is included and no Deck installation or publication is claimed.

The same pass fixes partial appearance records (P21/P27): loading an absent theme
default no longer queues a write that replaces an already saved text size.
Startup reads are separate from explicit appearance saves. The production-app
profile restart test begins with only the 130% text setting and requires it to
survive restart; the existing theme-selection/persistence flow remains covered.

Next UI implementation order: remaining settings/Sync parity, then richer Doctor diagnostics. Same-stream restart recovery and local report export are implemented in the slice below. Manual Space image selection remains a host API gap; the supported Space artwork flow is implemented locally below. Custom stream profiles, advanced safe display choices and device-local logo placement are implemented in the local slices below. The preset/encoder/Steam controls, desktop Artwork Studio and canonical Steam game-entry slices below are implemented locally and still need installed acceptance. The saved app shortcut migration was
verified after the authorized Steam restart; per-game shortcuts remain separate.
Nordstern resumes after the Deck release.

The subsequent owner-requested current-build update is installed on the Deck:
Keep in step package commit `f509d164791767e5255427e3685bf242e59d3d1d8d18e91b1e6d93d613a34964`.
The installed and Steam-launched executable match the retained package, existing
pairing/settings remain byte-identical after startup, and Gamescope focuses the
1280x800 standalone library. The current capture shows compact library chrome,
the Desktop destination, 26 entries and real artwork. This advances installed
startup/library evidence only; physical streaming/input, complete settings/Space
acceptance and the broader release gates remain open. Evidence:
`build/deck-keep-in-step/deck-install/EVIDENCE.md`. Rollback package and private
configuration backups remain on the device; Nova is open for owner exploration.

### Play Setup, Artwork Studio and Steam game entries — 2026-09-21

The owner-requested local slice advances P05/P06/P07. Play Setup now offers the
Android Auto/Quality/High FPS/Stability presets and the paired host's available
encoder catalog. Choices persist independently per PC/game, reset independently,
and are admitted again on the native worker before launch. Exact encoder or
preset refusal cannot silently launch a different choice. Resume requests omit
these launch-only parameters. High FPS follows the current display limit unless
an explicit per-game frame rate is saved. The read column accepts only the
version-one deterministic resolved-profile contract, with checked display/rate,
bitrate, HDR types and per-field provenance. It labels a reviewed host plan,
not measured encoder output. Stream choices remain explicit and SDR remains the
native presentation contract. Steam Direct/Big Picture is a separately labelled
host game setting, checked against a fresh permitted catalog and read back after
one non-replayed write. Existing Every Game ownership and Keep in step remain
separate from these local per-game preferences.

Desktop Artwork Studio adds title search and the touch keyboard, matched game
selection, poster/backdrop/logo/icon choices, current-versus-selected previews,
explicit Apply, Discard, Refresh art and confirmed Reset match. Candidate tokens
are bound to their game and kind; expired selections cannot be applied. Images
use opaque provider keys over the saved paired connection, bounded image reads
and a fixed candidate route. A fresh permitted desktop catalog precedes each
action; changed identity, lost replies, denied actions and retired previews cannot
become automatic mutation retries. Confirmed changes refresh library artwork
without changing the selected game. Search and choice completion restore D-pad
focus to the intended control. Logo placement transforms and Space artwork
editing are not included in this desktop Studio slice.

Details now offers Add to Steam. The entry encodes canonical saved PC/game/
destination identity, keeps existing Steam app IDs through game renames, and
copies the available poster/backdrop/logo/icon into that profile's Steam artwork.
Same-title games on different PCs are distinct and unrelated VDF entries survive.
An active or unobservable Steam process refuses the update and offers Retry;
Nova does not restart Steam. The game link opens the saved destination and game
from the fresh paired library, uses its saved stream choices and attempts normal
native launch. Opening a settings picker cancels automatic start. Back from the
dedicated game flow exits Nova to Steam. Offline/missing PCs, destinations or games
remain actionable and cannot fall through to another title.

Local evidence is in `build/deck-setup-artwork/EVIDENCE.md`, including production
QML D-pad/touch checks, 130% text at 1280×800 and 960×600, real loopback mTLS
mutation checks, native admission/launch-parameter tests, scratch Steam VDF/art
updates, and production application routing. All host/Steam changes in those
checks use isolated fixtures. The Deck preview and installed package are
unchanged; no GitHub push, physical game launch or installed acceptance is claimed.

### Advanced display and scoped profiles — 2026-09-21

The approved local follow-up adds **Custom…** to resolution, frame rate and
bitrate in Play Setup, plus **Edit Nova stream defaults** in Every Game. Both
use the same controller/touch form and numeric pad, with decimal Mbps entry,
explicit Save/Cancel and fixed footer actions at 130% text. A game's edits save
only that field; the device editor changes defaults for games without an
explicit choice across PCs. Individual resets inherit those current defaults.
Existing records remain readable without a load-time rewrite. Importing supported
Polaris profiles now retains custom sizes/rates/bitrates exactly, including
partial imports, instead of rejecting values outside the original preset list.

Stored sizes are even dimensions within 320–4096 by 240–4096, frame rates are
whole numbers from 30–90 fps, and bitrate is 1–300 Mbps with kilobit precision.
These storage bounds do not claim that every combination can play: the selected
codec's actual hardware decoder, fresh host stream capabilities and the current
display are checked during review and again before native launch. The host's
safe, visible advanced/custom resolution choices within those bounds now reach the picker, with
unsupported codec/decoder choices visibly unavailable. A resolution choice never
adopts the host planner's trailing frame rate. Current 40/45/50/72/75 Hz modes
now bound the effective stream rate; the existing 60/90 Hz tolerance and unknown
display fallback are retained. High FPS follows that same current-mode limit.
Saved values stay intact when review adjusts an effective rate.

Every Game also exposes the host's advertised **Resume timeout**, using Android's
1/5/10/30-minute choices and separate requested/effective values. Copy states that
this is a PC-wide setting for all paired devices. One narrow pinned request
follows fresh identity, capability, full-settings revision and idle checks; a
lost reply leaves the outcome unconfirmed and Refresh reads without resending.
No per-game or Keep in step profile silently sends this host-global field. The
current Polaris API explicitly rejects the removed AI Auto Quality setting, so
that deprecated Android control is not offered.

Evidence and the compatible local bundle are recorded in
`build/deck-display-settings/EVIDENCE.md`. Native/configuration, parser/transport,
production-QML and isolated full-app checks cover the slice. The running Deck
preview and installed package remain unchanged. Fractional rates, rates above
90 fps, HDR presentation, complete settings/Sync and installed/physical acceptance
remain open; full P07/P20/P27 are not accepted by these local checks.

### Artwork logo placement — 2026-09-21

Artwork Studio now offers **Logo placement** for a game with a logo. Its preview
uses Android's logo frame proportions, scale range (25–400%), normalized X/Y
position, 10% size steps and 5% position steps. Controller and touch buttons offer
Smaller/Larger, four directions and Reset layout. Save and Cancel remain fixed
while the form scrolls at larger text sizes. Saved placement immediately reaches
game details, with clipping that keeps large logos inside their artwork area.

The reference stores transforms locally. Deck preserves that ownership with
explicit Save/Cancel and keys the preference by saved PC plus canonical game
identity, avoiding same-ID collisions between hosts. Host-provided transforms
are the fallback until a local layout is saved. Reset layout previews centered
100% placement and still requires Save. Closing or changing PC/game discards the
draft. Failed persistence and stale edits do not overwrite the prior layout.
Stream-profile resets and host artwork-match resets remain separate scopes.
No layout edit writes to Polaris or modifies a Steam shortcut's artwork.

The parsed fallback also participates in public metadata refresh without changing
the image cache identity. Local parser/settings/provider, production-QML and
actual-app checks, screenshots and the compatible preview bundle are recorded
in `build/deck-logo-placement/EVIDENCE.md`. This bounded logo placement slice is
off the coding queue. Space image search/apply, broader settings/Sync and Doctor
recovery/report work remain open, as does installed/physical acceptance. The
Deck installation and running preview were not replaced.

### Space artwork previews and refresh — 2026-09-21

Space game details now open a dedicated artwork view for Poster, Backdrop, Logo
and Icon, with controller/touch selection and fixed actions at 130% text. Logo
placement uses the same device-local editor, scoped by saved PC and canonical
Space/game identity. Refresh art calls the existing `space-artwork/resolve`
contract once to fetch missing Steam artwork while retaining cached images.
Healthy, updated and partial-failure receipts have distinct copy; missing kinds
remain visible. A lost or malformed reply is unconfirmed and cannot be retried by
another Refresh until Check again reads the current library. That check issues
no refresh POST and does not claim that an uncertain write succeeded.

The worker checks saved identity, selected Space access before and after its
fresh library read, and the exact installed game before the request. A changed
selection or missing/revoked game prevents the write. Ordinary Desktop image
search/apply/reset routes, client-profile writes and native launches are never
used for this Space action. The host still owns authorization at the request;
its API has no compare-and-swap for concurrent selected-Space changes.

Contract inspection found that Android's Space library update uses this refresh
route, while the current Polaris manual search/choice/apply/reset handlers look
up Desktop library apps only. Consequently manual Space image selection needs
new Polaris support; exposing Desktop edit actions would not implement it.
This flow deliberately offers supported refresh and local placement, with that
limitation in the UI. It does not mark full P05 accepted. Steam Big Picture and
malformed/reserved identities cannot issue refresh requests.

Local source, contract provenance, parser/controller and pinned-transport tests,
production UI/app fixtures, screenshots and compatible bundle are retained at
`build/deck-space-artwork/EVIDENCE.md`. The installed Deck preview is unchanged.
Manual Space edits, physical acceptance and the remaining Android parity work
stay on the checklist.

## Approved SteamOS direction — 2026-09-21

Keep Nova Deck in the main Nova repository, with separate platform builds,
packaging, tests and release schedules. Android parity establishes consistent
player capabilities and visual language. SteamOS-specific work adapts input,
lifecycle and displays. A future repository split should follow independent
ownership/access or product direction, rather than completion of parity.

Ship the admitted Deck experience first. The approved ecosystem priorities are:

1. Complete direct per-game Steam launches with canonical PC/game/Space identity,
   artwork, supported wake and a clean return to Steam. The existing app shortcut
   does not satisfy per-game launching. Preserve unrelated shortcuts and refuse
   unsafe Steam-running writes.
2. Reliable suspend/resume with fresh connection/session ownership checks,
   restored audio/input and distinct resume-versus-launch behavior.
3. Deck-specific controls: trackpad mouse, gyro aiming, rear-button actions and
   separate browsing/streaming layouts, including radial menus where supported.
4. Separate handheld/docked display, audio and interface preferences; evaluate
   decoder/rendering power together with measured latency and frame pacing.
5. Explicit cross-device continuation of the owned host session. Steam Machine
   adds TV readability, multiple controllers and HDMI audio/display acceptance.
   Polaris retains host responsibilities.

Steam Frame starts with a separate flat-game virtual-screen prototype. Evaluate
the Android APK and native ARM64 routes before choosing the client implementation;
full VR streaming requires its own tracking/timing/rendering work. Reference:
[Valve Steam Frame](https://partner.steamgames.com/doc/steamhardware/steamframe?l=english),
[Steam Machine](https://partner.steamgames.com/doc/steamhardware/steammachine?l=english),
and [Steam Input](https://partner.steamgames.com/doc/features/steam_controller).
These are approved product directions, not claims of implemented support or new
Deck admission gates. Existing parity requirements and Nordstern's post-Deck
resumption gate remain in place. Development and testing remain local.

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

Implementation progress after the frozen reference (2026-09-17):

- Local controller rumble (P24/P27) adds System's device-wide, default-on
  `checkbox_enable_rumble` equivalent with persistence, isolated reset and a
  launch-time snapshot retained through reconnect/wake. The single-controller
  native path forwards two-motor feedback only during focused, neutral-armed
  gameplay. Overlay, focus loss, unplug, sleep, interruption and teardown stop
  feedback; return requires fresh commands. A dedicated worker resolves the
  exact joystick's sibling evdev identity, verifies `FF_RUMBLE`, and owns one
  short-lived renewed effect. Unsupported/failed output preserves input and
  never selects another controller. Worker/syscall fixtures, native callbacks,
  settings and actual-app touch/D-pad tests cover ownership, races, errors,
  persistence/reset and both screen sizes. This bounded implementation is off
  the coding queue. Installed Deck/external-controller rumble in Desktop/Game
  Mode, multiple controllers, trigger motors, gyro/LEDs, audio haptics and
  device vibration fallback remain open; P24 is not accepted.

- Local audio-output recovery (P15/P12) keeps Opus decoding while a separate
  worker recreates failed PipeWire outputs with capped backoff. Available paused
  graphs wait for system relinking; no system default is changed. Incoming PCM
  drops during outages, generation tags retire queued audio across observed
  pause/route boundaries, and teardown cancels retries and late activation.
  Recovery retains the initial format/PC-audio request without a host relaunch.
  The in-game notice preserves controller focus and clears on graph flow.
  Private null-sink removal/relink and daemon restart tests pass for 2/6/8
  channels; portable tests cover blocked open, cancellation, counters and
  unstable retries, and production QML covers notices at both screen sizes.
  This bounded implementation is off the coding queue. SteamOS automatic
  device selection, speakers/headphones, Bluetooth/dock switching, audible
  recovery, surround placement and A/V sync remain physical acceptance work.

- Local System → Audio implements Android `list_audio_config` and
  `checkbox_host_audio` (P15/P27): device-wide stereo/5.1/7.1 and PC playback,
  stereo/off defaults, immediate persistence and audio-only reset. Play Setup
  shows the effective layout and PC-audio request. A new stream snapshots the
  preferences for matching host/decoder configuration; reconnect and wake resume
  keep that snapshot, and Space retains stereo without changing the preference.
  Backend/session/UI tests cover malformed/corrupt values, scope, reset, failed
  writes, applied launch/resume values, controller/touch focus, restart and
  1280×800/960×600 layouts. Private PipeWire playback/disconnect checks cover
  all speaker positions for 2/6/8 channels; real Opus decoding retains mapping.
  This bounded preference implementation is off the coding queue. Effects,
  audible Deck/PC playback, physical surround placement and
  A/V sync through recovery remain required; P15 as a whole is not accepted.

- Local sleep/wake handling advances P12 through logind notifications and a
  delay inhibitor bounded to three seconds or the host's shorter allowance.
  Active ordinary streams release controls and detach while preserving the game;
  pending retries and browsing checks pause. Wake offers manual Resume game with
  fresh saved-pairing, game, ownership and exact-token checks and the reviewed
  settings. No automatic wake launch/resume or held-input replay occurs. Confirmed
  End, pending new-launch cleanup and Space leave retain their existing intent.
  Private-bus/worker/QML regressions cover stale signals/replies, service loss,
  denied delay support, bounded cleanup, wake/teardown races, cancelled pending
  resume, stale ownership and touch/D-pad focus at 1280×800/960×600. This bounded
  implementation is complete locally; five installed suspend/resume cycles,
  network/device return and physical video/audio/controller continuity remain
  required. Low-level sleep outside logind and complete standard/Space recovery
  are not accepted by these tests.

- Local automatic recovery now follows Android's four attempts with 0/1/3/7
  second backoff for recoverable Polaris connection termination (P12). Every
  attempt rechecks current pairing and the exact owned game/session. Only
  timeout/unreachable host checks retry; trust/permission/session/protocol
  failures stop. The busy state spans teardown/backoff, with visible attempt
  count and Cancel reconnect. Close, focus loss and destruction cancel future
  work without quitting the game. A manual retry resets the budget; automatic
  recovery requires 15 seconds of advancing successful composition before
  replenishing it. Queueing, failed imports and redraws do not count. Graceful,
  protected-content/frame-conversion and controller-input failures do not start
  automatic retries; media setup failures retain manual Reconnect. Local
  regression coverage includes budget exhaustion across unstable streams,
  cancellation, exact-session refusal and production QML. All 14 related checks
  pass; this bounded automatic-recovery implementation is complete locally. Physical suspend,
  standard/Space recovery and physical automatic-recovery acceptance remain open.
- Local interrupted-stream recovery now advances P11/P12: loss of an active
  ordinary stream or controller-input delivery preserves the host game,
  including after a fresh launch. After complete teardown, Reconnect rechecks
  current pairing, exact game, ownership and session token and keeps the
  reviewed settings. Verified media-connection failures permit another manual
  retry; refused or unverifiable sessions return to the library. Held controls
  are neutralized and Continue game remains explicit. Local worker/QML coverage
  includes blocked teardown and close races, repeated interruptions, exact
  session refusal, cancellation, touch and D-pad recovery. All 13 related tests
  and the KDE Flatpak startup smoke pass; reviewed 1280×800/960×600 views retain
  clear focus. This bounded local implementation is complete. Graceful host exits
  and unverified legacy sessions cannot reconnect; Space cleanup is separate.
  Physical suspend/recovery acceptance remains open; automatic recovery and sleep handling are recorded above.
- Local native Resume now follows a fresh paired ownership check and exact
  app/session token match (P11/P12). A successful disconnect exposes Resume game
  plus Back to details; the original stream settings stay in backend memory.
  Ordinary Play also resumes a matching owned game after reopening Nova, without
  replacing an active game or falling back to launch after a refused resume.
  Cancellation, missing/mismatched replies and stream failures preserve existing
  games; confirmed End game still terminates the exact resumed session. Thirteen
  related tests pass, including actual loopback mTLS and production QML. Legacy
  hosts without ownership/token proof, Space resume, watch,
  physical suspend recovery and installed disconnect/resume acceptance remain open.
- Local session controls now separate Disconnect from confirmed End game,
  following Android's ordinary-session exit behavior (P11/P16). Disconnect and
  active-window close stop the local stream without requesting host app
  termination. Pending launches retain cancellation cleanup; Space entries do
  not offer ordinary disconnect. The first exit choice survives repeated clicks
  and close/destruction; input is released before teardown and cannot cross into
  the next session. End confirmation defaults to Keep playing and safely handles
  B/Escape, held activation, touch and host termination. Ten related tests pass;
  reviewed 1280×800/960×600 views retain clear focus. This implementation stays
  local. Legacy-host resume, full ownership/watch behavior and physical
  disconnect/rejoin acceptance remain open.
- On 2026-09-18 the owner successfully added a PC and confirmed live native video
  on the Deck after selecting the EGL/OpenGL startup path. Startup now defaults
  to that path, with an actual-app render-context regression. The same session
  exposed lost game input: Nova's neutral capture/overlay packets removed the
  preallocated host controller, leaving the isolated game with its old device.
  Capture/focus/overlay transitions now preserve the connected device while
  clearing held and queued input; real unplug and teardown still remove it.
  Nine related local tests pass. After installation, the owner confirmed that
  game controls work. This accepts the reported basic video/controller preview
  on that artifact; audio, extended input/recovery checks, HDR90 and the full
  release matrix remain open.
- [Audio PR #320](https://github.com/papi-ux/nova/pull/320) is merged and adds real
  Opus decoding and bounded PipeWire output toward P10/P15. Real-packet tests,
  private null-sink playback/disconnect tests and a KDE Flatpak build passed.
- [Controller/focus PR #325](https://github.com/papi-ux/nova/pull/325) is merged.
  The owner confirmed physical D-pad navigation works and focus is clear in the
  simplified library. This accepts shell navigation only, not streamed input.
- Local branch `nova/deck-native-session-local` adds an opt-in `--live --native-ui`
  preview: asynchronous launch/connect/stop, bounded frame delivery to Qt and
  reuse of the native audio adapter. Cancellation preserves host-session cleanup
  even when launch completes after cancellation or omits its RTSP URL. Local
  lifecycle checks pass, including blocked-launch UI heartbeat, connection interrupt,
  disconnect/retry/shutdown and duplicate-start exclusion. The review and missing-
  identity failure screens were rendered locally at 1280×800. This branch is
  intentionally local; basic video is now owner-confirmed as recorded above,
  while audio and complete media acceptance remain open.
- The same local branch now forwards one controller's buttons, hats, sticks and
  triggers on the session worker. View/Menu opens controls without forwarding
  that chord. Neutral re-arming, quick taps, focus/unplug release, queue overflow,
  input-send failure and release-before-stop have local regression coverage.
  Basic physical game input is now owner-confirmed as recorded above. Multiple
  controllers, physical rumble, motion, keyboard/mouse/touch and reconnect/suspend recovery
  remain unaccepted.
- The local renderer now applies Qt transforms, opacity and clipping, uses
  desktop core-compatible GL vertex buffers, and fits the video aspect ratio
  through resizes. Software-Mesa pixel tests cover placement, both clip paths
  and Qt overlays. The production QML preview component has navigation,
  cancellation, stop/failure and return-focus coverage, plus reviewed 1280×800
  fixture captures. Current full Linux coverage, including the standard-host
  slices below: **52 passing tests, one hardware scenegraph smoke skipped**
  because VAAPI is unavailable, with local HDR validation enabled. Seven affected
  media/color targets also pass after the final lifetime/import review.
  Synthetic shader textures do not
  prove dmabuf import or installed-Deck presentation.
- The local `--standalone` route now adds manual PC entry, cancellable PIN
  pairing, Nova-owned RSA identity and private atomic persistence. Successful
  pairing opens the native library; later starts reuse the saved identity
  without Moonlight fallback or handoff. Known-answer crypto, a real loopback
  HTTP/mTLS handshake, wrong PIN/signature, exact certificate admission before
  HTTP, corrupt-store refusal, cancellation/rollback and production setup-QML
  interactions are covered locally.
- **Trusted Pair** now opts into the Android-compatible trusted-subnet flow
  only after the host advertises it. Missing/disabled support shows a fresh PIN;
  failed handshakes never silently fall back. Signed challenges, exact mTLS pins,
  host identity, saved-host protection and cleanup remain enforced. Tap-open
  address/port entry now provides a numeric keypad and ABC hostname keyboard,
  caret editing, clear/cancel/done and D-pad focus. Seven targeted pairing,
  identity, library and production-QML checks pass locally, including real
  loopback HTTP/mTLS trusted pairing and pointer/keypad interactions. This
  implementation slice is complete; real host/Deck pairing acceptance stays open.
- The standalone library now opens **Saved PCs**, with explicit pinned unpair,
  local-only forget and Add PC for a fresh PIN handshake. Failed or ambiguous
  remote removal preserves the saved record. Identity/host-change guards and
  the pairing lock protect concurrent operations; returning to the library
  recreates its session/controller from the updated identity. Confirmed HTTPS
  ports survive restart. Local coverage includes real loopback mTLS unpair,
  stale selection, cancellation, failure/re-pair, production confirmation focus
  and two actual application navigation cycles under Xvfb. Discovery/WOL,
  QR/OTP, cross-host revocation behavior and
  physical acceptance remain open; `DECK-02` is implementation in progress.
- The local library now supports standard GameStream hosts when the Polaris
  capabilities endpoint is absent. It requires the saved TLS pin, authenticated
  host ID and paired state, parses a bounded complete app list, and preserves
  Polaris precedence and authoritative empty lists. Native GUI launch uses
  numeric app IDs; certificate/auth failures cannot retry through this path.
  Nine portable CTest targets and the source guards pass on macOS, including
  loopback mTLS app-list/launch/cancel and negative-response coverage. The full
  Linux build and suite now pass. An actual-app regression renders the live
  standard-host library at 1280×800 using temporary loopback mTLS credentials,
  verifies the request sequence and unchanged identity, and excludes launch or
  pairing requests. A reviewed fixture capture confirms focus and literal game
  titles. The standalone launch card no longer displays the old fixture lab-gate
  warning or internal DTO label. Actual standard-host media, artwork and
  installed-Deck acceptance remain open.
- The standalone library now implements explicit saved-PC selection and manual
  refresh in the background, advancing P01/P03/P04. Picker focus alone does not
  change PCs; selection loads only that PC's games and native launch targets.
  Refresh preserves a surviving selected game. Busy requests and active native
  sessions exclude conflicting actions; denied, empty and stale results cannot
  retain old launch authority or substitute a different PC. Credentials are
  checked before/after refresh and before launch. Controller tests and an
  actual-app keyboard regression with two loopback mTLS hosts cover matching
  app IDs, selected-game focus, rejection, empty lists and recovery. Reviewed
  1280×800 captures cover refreshed, switched and unavailable libraries. This
  closes the local manual-refresh/PC-switching implementation slice.
- Standalone automatic library checks now advance P04: pinned reads every 30
  seconds while browsing, with pauses for inactive windows, other controls,
  modal screens and native sessions. Resuming browsing queues a fresh check;
  transient errors back off to 60/120 seconds, while trust failures stop automatic
  retries. The current game/focus survives a response even if navigation changed
  during the read; unchanged models are not rebuilt and removed games select a
  neighboring row. Local controller and actual-app loopback mTLS regressions
  cover scheduling, startup/runtime trust failures, focus, pauses, outages,
  recovery and shutdown. Reviewed 1280×800 captures show updated and recovered
  libraries. These periodic reads do not establish host-event subscriptions,
  artwork, imported-profile refresh, streamed-session recovery or installed-Deck
  suspend/reconnect acceptance.


- The 2026-09-18 UI/UX slice advances P03/P04/P21 locally. The standalone
  library now follows `NovaLibraryStage.kt`, `NovaLibraryPosterCard.kt`,
  `NovaLibraryUiState.kt` and `NovaGameDetailOverview.kt`: host-first Options/System
  chrome, portrait posters, Grid/Compact, name/library-order sorting, title
  search and a separate details destination. Layout/sort survive restart;
  controller and touch share the details flow, with selection/scroll restored
  on Back. Refresh and Saved PCs move into System. Basic standard-host and
  Polaris covers load with saved-pin checks, fixed routes, opaque QML keys,
  byte/pixel bounds and stale-result rejection; missing art has title fallbacks.
  The production app regressions now cover those interactions, preference
  restart, long lists, 1280×800/960×600, and the existing refresh/PC-switch flows.
  A real loopback mTLS image response and isolated artwork boundary tests also
  pass. Reviewed captures use fixture games and missing-art placeholders.
  This closes the basic browsing/details/poster implementation slice, not P03,
  P04 or P21 acceptance. The slices below advance this further. Full Play Setup,
  advanced artwork editing, offline artwork and event/reconnect invalidation,
  complete accessibility and installed-Deck validation remain open. Later entries
  record desktop Artwork Studio and theme/font-scale progress.


- The local Android filter/metadata slice further advances P03/P04/P21: All,
  Recent, Sources, HDR, category/genre filters and all six Android sort choices
  now use real public host metadata. Source grouping and equal-value ordering
  follow `NovaLibraryUiStateMapper`; views never reorder the backend library.
  Search composes with filters, D-pad focus remains distinct from the selected
  filter marker, and empty views/menus retain a way back. Filter/sort preferences
  survive restart for the same PC; switching PCs resets search/filter constraints.
  Details now show source/platform/runtime, category/genres, installation state,
  game HDR support and epoch-second last-played dates. The HDR label makes no
  stream/display acceptance claim. Query/parser tests and an actual-app loopback
  mTLS Polaris regression cover filtering, sorting, details/back, metadata-only
  automatic updates, selected-game removal, standard-host empty states, PC
  switching and persistence. Concurrent artwork/library identity checks now share
  read locks; credential writes remain exclusive and nonblocking. A deterministic
  regression reproduces the prior reader conflict and checks reader/writer
  exclusion. This filter/basic-metadata implementation slice is
  off the queue. Full details/Play Setup, artwork editing/offline storage and
  event/reconnect invalidation, themes/font scale/accessibility and physical
  acceptance remain.

- Local Stage browsing now advances P03/P04/P21: the persisted third layout
  follows Android's selected-game title/metadata above a horizontal poster rail.
  Left/Right reaches every filtered game without wrapping; Up reaches Review and
  launch and the filters. Review and poster activation open details without a
  launch request. Back restores the selection and scroll position. An actual-app
  mTLS regression covers a 26-game rail, touch, both edges, review/back, long
  titles, 1280×800/960×600, reordered/removed games, empty search recovery, PC
  switching and restart persistence, with unchanged credentials and no launch
  or pairing mutations. This completes the basic Stage browsing implementation.
  Cached cinematic artwork is covered below. Active-session Stage actions,
  full Play Setup and installed-Deck acceptance remain open.

- Local Play Setup advances P07/P13/P27 with Android's two-column review and
  persisted client stream choices per PC/game: four resolutions (1280×800,
  1280×720, 1920×1080, 1920×1200), 30/60 fps (plus gated SDR90 below) and
  10/20/30/40 Mbps. Reset restores only that
  game's defaults. Opening/editing does not start a session. Explicit Play passes
  a validated immutable configuration through native host launch and stream
  setup; paired-library target authority and cleanup remain unchanged. A changed
  library selection invalidates review instead of redirecting Play. Backend,
  production-QML and actual-app mTLS tests cover invalid/corrupt values, write
  failure, persistence/reset boundaries, applied host/stream values, picker Back,
  stale selection, touch, cancellation and 1280×800/960×600. Basic client stream
  choices are off the implementation queue. Host/Space destination,
  automatic/custom display planning, rates above 90 fps, encoder,
  tuning/presets, Steam behavior, Every Game host scope and physical
  stream/display acceptance remain open. P07 as a whole is not accepted.

- Local cached cinematic artwork advances P03/P04/P21: accepted Polaris
  manifests supply posters, heroes, transparent logos and icons through pinned,
  bounded reads on fixed game/kind routes. Stage and details share an Android-style
  hero backdrop and scrims; details prefer a logo and fall back to the title.
  Missing/denied heroes retain the ambient background. Metadata-only updates
  retain opaque image keys and Qt's cache; changed revisions, removed assets and
  PC switches retire keys and in-flight results. Artwork-only changes now reach
  the view through browsing polling. Parser/provider regressions and an actual-app
  mTLS test cover URL/cache boundaries, decode bounds/transparency, stable caching,
  revision updates, removal, denied images and PC isolation. Reviewed 1280×800
  captures use synthetic fixture artwork. This implementation slice is off the
  queue. Desktop Artwork Studio is implemented in the later slice above. Logo
  transforms, Space-specific artwork, screenshots/trailers, persistent offline
  caching, event/reconnect invalidation and physical
  acceptance remain open; P03/P04/P21 as a whole are not accepted.

- Local face-button layout advances P07/P08/P27, following
  `NovaFaceButtonLayoutOverrides`, Android's Play Setup choices and launch-time
  input policy. System sets a device default; Play Setup chooses inheritance,
  Match labels or Match positions per PC/game and shows the effective layout.
  Positions swaps A/B and X/Y only in outgoing gameplay packets. The native
  worker retains the launch-time choice; UI navigation, analog input, D-pad and
  overlay shortcuts retain their existing meaning. Reset preserves the device
  default and other games. Legacy stream-settings records retain their values
  and inherit the default. Settings/controller/native-session regressions cover
  all button combinations, precedence, invalid/corrupt values, failed writes,
  immutable mapping, neutral/release guards, shortcut consumption and teardown.
  Production-QML and actual-app mTLS tests cover controller/pointer choices,
  cancellation, scope/reset/restart and 1280×800/960×600 layouts. This completes
  P08's local implementation on the single-controller native path. Physical
  Deck input acceptance remains open; the reference-status column is unchanged.

- Local per-game launch modes advance P07/P27 using Android's canonical mode
  names, typed catalog checks and session-scoped override behavior. Play Setup
  intersects host availability/session permission with game allowed modes;
  Headless Dongle stays excluded. Host default omits `streamMode`; an explicit
  choice requires fresh pinned capabilities/catalog/game reads and a saved-pairing
  recheck before launch. Cancellation stops subsequent reads. Valid authority
  retires withdrawn saved choices to Host default with a notice; restoring a mode
  does not restore the old override. Missing/malformed authority preserves a saved
  explicit choice and blocks Play. Parser/settings/protocol/session and loopback
  mTLS regressions cover malformed contracts, migration, default omission,
  withdrawn permissions, changed/removed games, identity, cancellation and scoped
  persistence. Actual-app checks cover retirement/outage/restart, PC/game scope,
  controller/pointer choices and 1280×800/960×600. This local implementation slice
  is off the queue. Standard and legacy hosts without typed catalogs expose only
  Host default; Android's legacy fallback, Space routing, Every Game host scope,
  full display planning and physical mode acceptance remain open.

- Local capability-aware stream review advances P07/P13/P27. Safe, visible PC
  display-planner recommendations annotate matching supported presets, including
  1920×1200; their trailing FPS never becomes a stream pin. Recommendations are
  advisory and do not remove existing presets. The PC's capture rate limit filters
  30/60 fps choices; a lower effective rate is disclosed without overwriting the
  saved preference. The review explicitly shows H.264/SDR and passes its exact
  effective configuration to native launch and streaming. Missing legacy metadata
  preserves the baseline; malformed or incompatible advertised capabilities block
  Play. Fresh pinned capabilities and a saved-pairing recheck precede launch;
  serverinfo independently rejects malformed flags or explicit codec sets without
  H.264 4:2:0. Parser/settings/session/protocol, loopback mTLS, production-QML and
  actual-app regressions cover changes, refusal, cancellation, persistence/scope,
  restart and 1280×800/960×600 views. This bounded stream-review slice is off the
  queue. Decoder probing, peak-mode enumeration, automatic/custom display planning,
  rates above 90 fps, HEVC/Main10/HDR and resolved-profile/encoder/tuning contracts remain open;
  P07/P13/P14 and physical presentation are not accepted.

- Local current-display detection and SDR90 advance P07/P13/P14. The active
  QWindow's QScreen reports its current refresh rate; changes/removal update the
  review and a worker-safe rate limit. Only a reported rate of at least 88 Hz
  and an explicitly advertised host ceiling of at least 90 fps expose 90 fps.
  Unknown/60 Hz displays and legacy host ceilings retain 30/60. No OLED inference,
  peak-mode enumeration or mode switching occurs. Saved 90 fps preferences survive
  a disclosed fallback; withdrawn popup choices return focus to the rate row.
  Display checks before launch, including after serverinfo, refuse stale plans
  without starting a game. Active streams retain their reviewed configuration.
  Video delivery now uses a coalescing queued GUI notification instead of the
  16 ms lifecycle/input timer. Pending leases and late publishers are retired
  safely. Unit/session and production-QML tests cover limits, screen signals,
  removal, cancellation, in-flight rate changes, cleanup, coalescing and
  destruction races. Reviewed 1280×800/960×600 captures inject a synthetic display
  rate, and do not measure frame cadence. This bounded implementation is off the
  queue. Physical OLED SDR90, LCD fallback, presentation pacing, A/V sync,
  Main10/HDR90 and full P07/P13/P14 acceptance remain open.

- Local Main10 color groundwork advances P13/P14 without enabling HDR in the app.
  The VAAPI decoder accepts exact H.264/Main10 format values and validates decoded
  bit depth/4:2:0 chroma. Frame leases retain color tags and static HDR metadata;
  the existing Qt SDR surface/render node refuse incompatible frames. An opt-in
  libplacebo/Vulkan texture renderer maps planar 10-bit/P010, renders a defined
  1000-nit BT.2020/PQ reference or tone-maps to sRGB, and refuses ambiguous HDR or
  low-precision targets. A real HEVC Main10 sample verifies profile, decoded tags,
  static metadata, rendered pixel values, adjacent 10-bit precision, packing,
  metadata retirement and SDR conversion/refusal paths. At most three mapped frames
  retain source ownership until GPU reads finish; teardown drains work. Unsupported
  DRM layouts and invalid object/plane bounds are refused before import. The local Vulkan device
  is software lavapipe; synthetic hardware leases prove ownership only. This is
  decoder/color-rendering groundwork, not a completed presentation path. HDR negotiation, native Vulkan/Qt window/overlay
  integration, compositor metadata, verified display support and physical HDR90
  remain open. The default remains H.264/SDR; the subsequent local codec slice adds HEVC SDR.

- Local codec selection advances P07/P13/P14: Play Setup has per-game Auto,
  H.264 and HEVC choices, a resolved SDR plan, visible fallback/refusal and retained
  preferences. Hardware decoder profiles, entrypoints, formats and dimensions are
  checked at startup, before launch and on the owned VAAPI decoder. Fresh Polaris
  and GameStream metadata gate the reviewed codec; the Moonlight callback refuses
  an unexpected format. HEVC Main software decode, settings/session/protocol and
  production-QML touch/D-pad checks cover the slice. Spaces and old records keep
  H.264. Main10 probing is decoder evidence only; HDR negotiation, Vulkan/Qt
  presentation, compositor/output metadata and physical SDR/HEVC/HDR90 acceptance
  remain open. The new work stays local, separate from the installed Keep in step
  build and the unfinished Doctor receipt work.

- Local Vulkan window work advances P13/P14: the opt-in color backend now renders
  into a real Qt native window with a libplacebo swapchain. Actual surface formats
  and the acquired frame's color representation gate HDR; preferred HDR can
  tone-map to sRGB, while required HDR is refused when unavailable. Resize,
  aspect-preserving black bars, hidden latest-frame ownership, surface recreation,
  clear/refusal and GPU teardown have local window-pixel/lifecycle coverage on
  Xvfb/lavapipe with Vulkan validation requested. The Main10 color test checks
  acquired-target metadata and precision independently of the reference target.
  The later opt-in session integration below connects it to the application;
  HDR negotiation, compositor/output verification and installed hardware HDR90
  remain open; the installed Deck build is unchanged.

- Local Vulkan streaming controls advance P11/P13/P14: a separate default-off
  build option plus explicit launch flag rehosts the existing Play Setup,
  Command Center and NovaHUD on a GPU-resident Qt Quick texture. Native key,
  pointer and touch forwarding preserve the production controls; a guarded
  presentation sink connects the latest-frame session handoff and distinct video
  composition counter. HUD-only redraws do not count as new video frames. Local
  Xvfb/lavapipe checks cover review/focus, choices, confirmation, HUD/video pixels,
  repeated open/close, resize, clear and accessible failure return. Session tests
  cover busy target/counter refusal and sink detachment/destruction. Qt keeps one
  shared device across surface recreation and releases its renderer before the
  device at final teardown. Rendering and presentation can still wait on the GUI
  thread; the later pacing slice below improves ownership acquisition. This bounded integration is off the coding queue; hardware import,
  physical input/Game Mode, latency/pacing, live Main10/HDR negotiation and OLED
  output remain open. Default app/Flatpak and installed Deck are unchanged.

- Local Vulkan scheduling work advances P13: unchanged Qt Quick overlays are
  reused; semaphore readiness and resize use zero-timeout polls with deferred
  retries. Pending UI changes coalesce, and the held texture is never sampled.
  The fixed three-frame source pool defers new video before swapchain acquisition
  under pressure and retires completed mappings even after Clear. The production
  popup test composes twelve new video frames without another Qt UI draw. Real
  queue-gate checks cover GUI timers, resize, updated pixels, DPR and timeout
  recovery. Only the source-pool stress scenario suppresses optional shader
  timing queries, because the local software driver's query waits for device
  idle; normal rendering tests keep the driver unchanged. Qt completion,
  libplacebo dispatch/presentation and teardown may still block. This is a bounded
  scheduling improvement, not accepted asynchronous rendering or physical cadence.
  Hardware import remains unverified: the local VAAPI driver cannot initialize.
  The work stays local; installed Deck and default packaging remain unchanged.

- Local Vulkan render-thread work advances P11/P13: Qt completion, libplacebo
  rendering/presentation and normal preview-close retirement now run on a
  dedicated worker. Input/QML/polish remain on GUI; only scene synchronization
  parks it, after GPU acquisition and beginFrame. One active request plus the
  latest replacement bounds submissions. Close immediately returns to the
  library; cancelled generations cannot revive readiness or count old video.
  Real queue stalls with the unaltered driver cover production D-pad/touch,
  timers, eighty frame/resize replacements, cancel/reopen, source release and
  pending-sync cancellation across forced surface recreation. The bounded local
  threading slice is off the coding queue. Final object destruction or an
  OS-forced surface lifetime boundary still joins the worker and may wait on the
  driver. Physical cadence, VAAPI import, Game Mode and HDR/OLED acceptance remain
  open; default packaging and the installed Deck are unchanged.

- Local Vulkan import readiness advances P13/P14: exact NV12/P010 software
  formats, DRM layer/bounds validation and Vulkan format/modifier sampling checks
  now guard the production importer. A device lacking DMA-BUF texture import
  cannot enable Play on the experimental route. The retained HEVC Main/Main10
  hardware test requires VAAPI output and compares imported SDR/PQ/tone-mapped
  pixels, static HDR metadata and source retirement with software references.
  Required-hardware mode cannot turn unavailable hardware or software reference
  checks into acceptance. Local layout/refusal and software-reference checks pass;
  the hardware run is unavailable because VAAPI cannot initialize. The bounded
  guard/test implementation is complete. Actual VAAPI import, live HDR
  negotiation, physical output/pacing and installed OLED acceptance remain open;
  default packaging and the Deck installation are unchanged.

- The temporary Steam Deck hardware probe now supplies bounded P13/P14 import
  evidence. Actual VAAPI NV12/P010 decoding and production Vulkan imports pass
  the SDR/PQ/tone-mapped pixel, static metadata and repeated source-retirement
  checks. The verified source snapshot was built for the existing KDE 6.10
  runtime with pinned libplacebo 7.360.1; hardware mode disallows software decode
  fallback and software Vulkan. Installed Nova, saved data/shortcuts, running
  Nova/Steam processes and runtimes were verified unchanged. This closes the
  temporary headless import check, not installed release acceptance. No window
  or game was opened; native-window/Game Mode, live A/V/input, frame cadence,
  HDR negotiation/compositor metadata and OLED output remain open.

- A temporary full-application Vulkan preview now renders the library/artwork and
  native Play Setup at 1280×800 in Steam Deck Game Mode. The existing KDE 6.10
  runtime selects the actual AMD GPU, Game Mode focuses the native window, and
  import readiness enables Play with no setup error. Injected navigation verifies
  routing only. The installed app is paused during the preview, with a watcher
  to resume it on exit; settings are copied privately on-device. Installed Nova,
  original data/shortcuts, Steam and runtimes remain unchanged. Live game input,
  audio sync, close/reopen recovery, cadence and HDR output are still pending.
  This is temporary presentation-startup evidence, not installed acceptance.

- In-game overlay visibility now advances P11 locally: Appearance has independent,
  persistent Command Center-button and shortcut-hint switches. The normal hint
  uses Deck View/Menu glyphs, and the floating button is a compact Menu symbol.
  Hiding both preserves the input chord/Escape; without a controller the touch
  entry stays available, and actionable release/connection notices remain visible.
  Production-QML checks cover separate toggles, new-engine persistence, keyboard
  and touch navigation, hidden-menu recovery and 130% text at 960×600/1280×800.
  Native session, Vulkan overlay and both graphics-startup checks pass. The updated
  runtime-compatible preview is staged separately; the active game is not replaced.
  Physical acceptance of this UI change remains pending.

- A live-preview End Session report exposed a P11 Vulkan dialog-parent bug:
  the confirmation's explicit library-overlay parent survived rehosting, leaving
  the dialog on the wrong window. Parenting it to the moving stream content fixes
  visibility and input. A regression fails before the fix and verifies the actual
  overlay window, safe Keep playing focus, held-input refusal, and explicit
  keyboard/touch confirmation afterward. Physical exit acceptance remains open
  until the updated preview is exercised on-device.

The reference-status column above remains the audited baseline. Full native input,
Main10/HDR90 presentation, complete standalone setup acceptance and the other
required parity rows remain open; a local preview does not admit a Deck release.

Deck publication remains held while any required row is incomplete. The existing
headless native proof and offline diagnostics gate remain valuable bounded
evidence, not a supported Deck release. Static HDR10 belongs to this target;
HDR10+, true 240 fps and a new Nordstern transport remain separate research work.

## Doctor restart recovery and local reports — 2026-09-21

The local candidate now integrates the previously unfinished private receipt
journal with the current artwork/settings baseline. A paired host certificate,
client certificate and canonical game key own each journal; a hashed host
instance/app-session/generation must match fresh owned telemetry before recovery.
Journals expire after 24 hours, have private permissions and atomic bounded writes,
and hold an exclusive writer lock. A failed durable write blocks the Doctor POST.

After reconnecting to the same still-running game, saved receipts offer an
explicit Check result before Undo is available. Opening Doctor does not replay a
request. An initial lost reply offers **Recover change**, retaining the exact
original idempotency key and payload; Polaris can finish an undispatched request
only if its original authority remains valid. Interrupted Undo offers **Finish
Undo** and preserves the original restore operation instead of changing it into
verification. New pairing, host instance or game generation cannot inherit the
old change. Terminal confirmation retires the journal. This does not add recovery
for Space streams, queued next-launch trials or crash-log collection.

Doctor now has a controller/touch **Report** tab. Save report writes a private JSON
file in Documents → Nova reports, with a clear save result and no upload. The
closed schema includes current HOST/NET/CLIENT measurements, known Doctor evidence,
change state and missing-measurement limits. It excludes names, IP addresses,
hostnames, paths, certificates, tokens, raw session/run identities, raw logs and
arbitrary host prose. Stale readings become unavailable; control-channel retry
observations never become client media loss. The Flatpak manifest grants access
only to its report subfolder under Documents for this export.

The Android reference remains v1.4.11 at
`a14758c8487c03235c4bd91eb63606d5eee71181`. Android's broader crash/log report sharing
and Polaris upload flow remain outside this bounded local export. Full P19,
settings/Sync, manual Space artwork selection (host API gap) and installed/physical
acceptance remain open. Local evidence: `build/deck-doctor-recovery/EVIDENCE.md`.

## Dedicated Polaris Sync surfaces — 2026-09-21

System → Polaris Sync now opens the existing profile/settings engine directly
from the library, without selecting a game or entering Play Setup. It keeps the
same Match Nova, Send Nova, Use Polaris, Clear profile, device-default reset,
Keep in step, PC display and resume-timeout boundaries as Every Game. A dedicated
Sync view refreshes every three seconds while open and focused, including with
Keep in step off. Reads do not imply automatic profile writes.

Command Center → Polaris Sync shows the selected PC's desired/effective display,
Nova defaults, paired-device and host-reported profiles, PC-wide timeout and saved
sync preference during play. This surface is explicitly read-only. Every mutating
controller method and automatic syncing is blocked there, including local import,
reset and preference changes; a failed read cannot alter the saved sync preference.
The stream's live bitrate remains in Live Tuning. Full active-stream Sync editing
is still open because paired profile updates can change live bitrate on Polaris.

Both surfaces share the existing backend and scope checks. Unavailable reads clear
stale profiles, returning online requires fresh reads, and closing, changing pairing
or losing window focus cancels the active work. Read-only refreshes preserve the
reading/control focus. Back returns to the originating System/Command Center row;
resuming, disconnecting or closing the stream dismisses its Sync view.

Android stays pinned to v1.4.11 `a14758c8487c03235c4bd91eb63606d5eee71181`.
Deprecated Android Auto Quality controls remain excluded because current Polaris
rejects that API. These dedicated entry points and in-game profile review are off
the local coding queue; active-stream editing, broader settings parity and
installed/physical acceptance remain open. Evidence: `build/deck-sync-surface/EVIDENCE.md`.

## Searchable Settings hub — 2026-09-21

The library's **Settings** button now opens a full-screen hub based on the frozen
Android `NovaSettingsScreen`: a category rail, search across all categories,
setting/value cards, individual resets and a fixed Back control. Audio, face-button
defaults, rumble, theme, text size, library layout, Command Center visibility and
NovaHUD preferences use their existing stores. Selection dialogs save only on an
explicit choice; Cancel and focus movement preserve values. Search supports the
touch/controller keyboard and an actionable empty state. Choosing a category
clears the query, and closing a child editor returns to its originating setting.

Every row states its device/game/PC scope and when the choice applies. Individual
resets preserve adjacent preferences and per-game overrides. Stream defaults open
the existing Every Game editor; Polaris Sync opens the shared guarded host view.
No new host authority or preference migration is introduced. Browsing/searching
the hub does not open the host settings controller. The System menu retains its
existing quick actions; Command Center retains the same preference stores.

Android remains v1.4.11 `a14758c8487c03235c4bd91eb63606d5eee71181`.
This completes the bounded Settings hub implementation locally, not the entire
Android preference inventory. Active-stream Sync editing, remaining video/input
preferences, settings import/export and installed/physical acceptance remain open.
Local build, route, scope and visual evidence: `build/deck-settings-hub/EVIDENCE.md`.


## In-game Polaris Sync controls — 2026-09-21

Command Center → Polaris Sync now adds explicit Match Nova, Send Nova and Clear
paired profile, alongside Live Tuning and the existing fixed-bitrate editor.
Resolution/frame-rate changes are labeled for the next stream. Sending also asks
Polaris to apply the bitrate now, turns Live Tuning off and supersedes pending
Doctor bitrate changes. Clear removes the paired profile for the next stream
without requesting a live bitrate change. Per-game choices and device defaults
remain separate; Keep in step stays paused and PC-wide topology/timeout stay locked.
This extends the earlier Command Center profile-review scope above.

The native session bridge requires the active focused session, open controls and
matching selected PC. The observer rechecks the exact owned stream before and
after fetching the reviewed profile. Changed profile/capability/revision or
stream authority cancels the intent; event invalidation, identity loss and session
teardown cancel pending I/O. One paired POST carries backend-only session scope.
A response plus profile GET confirms storage; only fresh matching encoder telemetry
can confirm applied bitrate. Lost responses, differing readback and absent encoder
acknowledgement remain unconfirmed and are never automatically resent. Polaris
has no atomic profile revision CAS; local preflight cannot prevent a concurrent
writer racing the host commit. Session identifiers remain outside QML.

The legacy host-settings controller remains read-only during play. In-game Sync
uses a separate session-owned mutation path; idle editing, Keep in step, host
power and other authority boundaries are unchanged. D-pad/touch controls preserve
focus through readback and permission loss. Back closes the nested bitrate editor
before Sync; resume/disconnect dismiss both.

Local source, tests, reviewed large-text captures and Deck-compatible candidate:
`build/deck-session-sync/EVIDENCE.md`. This bounded implementation is off the coding
queue. P20/P27 remain partial: broader preference coverage, settings import/export,
remaining display/profile work and installed/physical acceptance remain open.
Android stays pinned to v1.4.11 `a14758c8487c03235c4bd91eb63606d5eee71181`.


## Video scaling — 2026-09-21

Settings → Video & stream and Command Center now share Android's Fit, Fill and
Stretch choices. Fit preserves the whole picture with black bars when needed;
Fill preserves proportions while cropping edges; Stretch fills the display by
changing proportions. The device-wide choice applies immediately and survives
restart. Reset to Fit changes only scaling, preserving stream profiles, audio,
per-game overrides and host settings. Focus movement and Back do not save.

Both Vulkan/libplacebo and the legacy OpenGL presenter use shared aspect-ratio
geometry, including portrait, ultrawide and non-square pixels. A mode change
redraws the retained frame without counting another decoded/composed video frame.
Letterbox areas are cleared and menus/HUD retain screen coordinates and size.
The in-game editor follows the rehosted Vulkan stream window; controller focus
returns to its originating row. Errors are visible and large-text actions stay
outside the scroll area.

Local evidence: `build/deck-video-scaling/EVIDENCE.md`. Android remains pinned to
v1.4.11 `a14758c8487c03235c4bd91eb63606d5eee71181` (`list_video_scale_mode`,
default Fit). This bounded scaling implementation leaves the coding queue.
P13/P27 remain partial: frame-pacing preferences, remaining video/input settings,
import/export and installed/physical SDR/HDR acceptance remain open. Local pixel
and UI checks do not establish Deck display or controller acceptance.


## Initial frame-pacing preferences — 2026-09-21

Settings → Video & stream now adds Android's default Prefer lowest latency and
Balanced choices, with search, saved selection and an isolated reset. Lowest
latency preserves latest-frame coalescing. Balanced schedules decoded-frame
handoff using monotonic deadlines at the admitted stream FPS, retains at most two
pending leases, re-buffers after underflow and discards overdue backlog after a
stall. Display vsync remains owned by the existing renderer/SteamOS path; this is
a native adaptation, not Android Choreographer scanout scheduling.

The preference is snapshotted before launch and configured before decoded frames
arrive. The resolved stream rate supplies the cadence. Reconnect and wake retain
the original choice; a deliberate new stream reads current defaults. Invalid or
unsupported values fall back to latency. Host FPS/resolution/bitrate and per-game,
audio and scaling preferences are unchanged. Only latency and balanced are
accepted; Android Warp/Warp 2, FPS-limit and smoothest-video modes remain open.

Evidence: `build/deck-frame-pacing/EVIDENCE.md`. The reference remains v1.4.11
`a14758c8487c03235c4bd91eb63606d5eee71181`. This bounded two-mode implementation
leaves the coding queue. P13/P27 remain partial: remaining pacing modes, advanced
input/video preferences, import/export and installed physical cadence, A/V-sync
and SDR/HDR acceptance still require work.


## Controller stick deadzone — 2026-09-21

Settings → Controls now adds a saved Android-style −20% to +20% stick deadzone,
with a 5% default, touch slider, D-pad 1% steps, explicit Save/Cancel and isolated
row reset. The Default 5% editor action changes only the draft. Positive values
use a radial cutoff without rescaling movement outside it. Negative values
preserve direction while boosting small movements; the Android 1% center floor
also applies at zero. Full signed stick travel is retained.

The decoder now preserves oriented raw stick values, replacing its fixed axial
cutoff. Each native session applies the selected radial policy once before
routing. Joining/reassign and gameplay share the same neutral test. Startup
re-evaluates retained raw states before capture so a threshold change cannot arm
held input without a new joystick event. The preference is frozen per stream,
including reconnect and wake. Device defaults, per-game profiles, face-button
mapping, hats, triggers, rumble and Steam Input device selection keep their scopes.

Evidence: `build/deck-deadzones/EVIDENCE.md`. Android remains v1.4.11
`a14758c8487c03235c4bd91eb63606d5eee71181` (`seekbar_deadzone` and
`ControllerHandler.handleDeadZone`). This bounded P22/P27 implementation leaves
the coding queue. Mouse/keyboard/trackpad/gyro and other remaining preferences,
installed controller/Steam Input acceptance, and broader Deck release gates
remain open. Local input fixtures do not measure physical stick drift or latency.


## Mouse and keyboard foundation — 2026-09-22

The active native stream now forwards physical keyboard keys, direct mouse
positions, five buttons and both wheel axes on the owning stream worker.
Ctrl + Alt + Shift + M opens Command Center; Escape reaches the game. Menu
presses/repeat pairs do not leak through Resume, and focus/window transitions,
sleep, interruption and teardown release held input before the connection ends.
Bounded queues preserve key/button/scroll edges, coalesce only adjacent pointer
motion, reject stale generations and stop on failure without quitting an
ordinary host game.

Fit/Fill/Stretch and pixel aspect share the rendered video geometry. Letterbox
clicks are excluded and active drags clamp to the picture. HUD/Command Center
mouse gestures and synthesized touchscreen input stay local. The actual native
presentation window owns the route, avoiding duplicate forwarding through its
redirected Quick window. Linux physical US game-key positions, distinct left/right
modifiers, keypad digits and logical injected-key fallback are included.

Evidence: `build/deck-desktop-input/EVIDENCE.md`. This bounded direct-pointer and
physical-keyboard implementation leaves the coding queue. P23 remains partial:
relative capture/aiming, touch/trackpad modes, pixel-only scrolling, IME/text and
keyboard layout preferences, virtual controls, gyro and installed input acceptance
remain open. Direct pointer input is not full mouse gaming support. Android stays
frozen at v1.4.11 `a14758c8487c03235c4bd91eb63606d5eee71181`; Nordstern follows Deck
release. Development and testing remain local.
