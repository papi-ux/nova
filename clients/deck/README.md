# Nova Deck Client

This directory contains Nova's native Steam Deck development client. It has a
Qt shell, live library reads, a Moonlight handoff, and an opt-in asynchronous
native GameStream preview with hardware decoding and Opus/PipeWire audio. It is
not yet a supported Deck release. The active [release parity checklist](../../docs/deck-release-parity.md)
requires standalone pairing, in-app media and controls, Android product parity,
and OLED HDR10 at 90 fps together.

Current status:

- CMake builds a small native core library on Linux/SteamOS-capable development hosts.
- Qt 6/QML shell builds when Qt Quick and QuickControls2 development packages are installed.
- Fallback build path keeps the core/controller/library smoke runnable without Qt.
- The shell consumes a generated sample Polaris game fixture shaped after shared/polaris/model/src/commonMain/kotlin/com/papi/nova/shared/polaris/model/PolarisGame.kt.

## Local Android v1.4.11 parity work

The standalone library now shares theme colors and focus controls across pairing,
PC management, browsing, Play Setup, audio, rumble and the streaming overlay.
System → Appearance offers six themes and 100/115/130% text size. Compact uses a
dense portrait-poster grid; scrollable forms keep controller focus visible at
smaller window sizes.

Appearance also has separate **Command Center button** and **Controller shortcut
hint** switches. They apply during play and persist on the Deck, independently
of NovaHUD. The floating button uses the Deck's Menu symbol; the shortcut shows
the View/Menu symbols instead of text button names. Hiding both keeps the chord
and Escape available. Without a controller, the touch button remains available;
controller connection/release notices remain visible even with the hint hidden.

The Vulkan stream's End Session confirmation now follows the streaming content
when it moves away from the library window. It stays visible and receives input
on the active stream window. The regression checks Keep playing, held-button
protection, and explicit End game with native-window keyboard and touch events.

The next library/details pass follows Android v1.4.11's quieter chrome, unframed
portrait posters and 180 ms focus scale/lift. Details use the backdrop, a logo
already ready on entry (otherwise a stable title), compact metadata and fixed
Play/Back actions. Optional host `play_time` and `beat_time` provide played time
and individual length estimates. Missing/invalid fields disappear; known zero
playtime remains visible. This reads the existing paired game list and makes no
external metadata lookup or host mutation. At larger text sizes the details body
scrolls independently, leaving Play reachable; Play still opens stream setup.

`nova_deck_library_polish_test` drives the actual app against isolated mTLS
fixtures: missing/zero/partial durations, delayed or denied artwork, all layouts,
six themes, 130% text, small windows, pointer selection and controller focus.
Local source, screenshots, checks and Flatpak evidence are retained in
`build/deck-library-polish/EVIDENCE.md`. This package excludes the unfinished
persistent Doctor receipt/report work. Installed acceptance remains separate.

The library header now names the selected Desktop or Space. **Where to play**
lists Desktop and assigned Spaces with the current choice, readiness and access
explanations. Controller focus alone changes nothing; selection uses one paired
request after a fresh permission/previous-destination check. A lost reply clears
the old library and offers Refresh to read the actual selection without replay.
Desktop explicitly loads the PC's regular library. A Space loads only its scoped
games and poster/hero/logo/icon routes; its Big Picture entry keeps a placeholder.
Busy Spaces remain browsable while Play follows fresh host readiness. Switching
retires old artwork/launch targets, and launch/resume rechecks the destination.

`nova_deck_spaces_test`, `nova_deck_live_network_test` and
`nova_deck_spaces_route_test` cover parsing, identity/permission boundaries,
non-replayed requests, concurrent selection changes, exact library/artwork
routes, and controller/pointer navigation at both sizes with 130% text. Local
package evidence is in `build/deck-spaces/EVIDENCE.md`. This completes the bounded
destination picker/artwork implementation, not full Space streaming/recovery or
installed acceptance.

Play Setup now follows Android v1.4.11's read/action columns: the plan names the
selected Desktop or Space, effective stream/audio/buttons and launch behavior;
the settings identify defaults and this game's explicit choices. Each choice
can reset independently. Existing saved configurations remain readable without
a load-time rewrite, and reset cannot resurrect older values. Changing the
destination or losing readiness invalidates an open review, including legacy
games whose ID stays unchanged. Both columns remain reachable with controller
navigation and large text; Play/Back stay fixed.

`nova_deck_setup_parity_route_test` covers destination-specific choices,
individual resets, controller/pointer focus, 130% text, resizing and restart.
Settings and production-QML checks cover migration, failed writes and stale
reviews. Evidence and the isolated local package are in
`build/deck-play-setup/EVIDENCE.md`. This completes the bounded read-plan and
independent-choice slice. Later sections record Every Game host defaults,
verified host plans, encoder/preset and Steam behavior, desktop Artwork Studio
and per-game Steam entries. Custom display planning, complete settings/Sync and
remaining Doctor recovery/report work remain open.

Play Setup → **Every Game** now reads Polaris host defaults in a separate
read/action view. It names desired and effective display modes, the paired
device's saved size/bitrate and the host-reported profile. The host's complete
known mode catalog remains readable, including unavailable choices and reasons.
An explicit choice updates only the PC's default display; the view explains
that this affects future Desktop streams from other devices too. Spaces keep
their own launch settings. Per-game choices are not rewritten.

Before saving, a worker rechecks the paired identity, advertised capability,
reviewed settings/revision and idle host. It sends one standalone POST and
requires the host to confirm the desired mode. A lost/rejected/malformed answer
clears authority and offers read-only Refresh; Nova does not replay the write.
The host API has no revision CAS for this field, so the fresh comparison cannot
eliminate a separate writer's change between the GET and POST. Native Play,
Sleep Host and library switching are excluded during this worker's operation.
An attempted write invalidates the game's old review, with a visible message
above the settings. Focus, scope changes and shutdown cancel pending work.

Local parser/controller, pinned TLS and production-app evidence is retained in
`build/deck-host-scope/EVIDENCE.md`. This completes the bounded Every Game
default-display and host-profile readout slice. Full host Sync,
resolved launch encoder/profile information and
physical acceptance remain open. This local build is not installed on the Deck.

Every Game also adds manual **Match Nova**, **Send Nova**, **Use Polaris** and
**Clear profile**. Match/Send copy Nova's device stream defaults to this pairing's
profile on the selected PC; they do not send per-game overrides or change the
PC-wide display mode. Clear removes that pairing's size/rate/bitrate overrides.
Use imports the desired profile, falling back field-by-field to the effective
profile as Android does, into Nova's device defaults across PCs. Explicit game
choices remain in place; unspecified profile fields retain Nova's current
defaults. Reset Nova defaults restores 1280x800/60/20 Mbps without changing game
choices or Polaris. Device defaults persist and individual game resets inherit
the current defaults.

Profile writes require both advertised override capabilities and fresh reviewed
settings, pairing and idle checks. They use one paired POST, clear authority on
an uncertain response and require read-only reconciliation. This profile route
also lacks server revision CAS. Use freshly checks the host and preserves local
defaults on cancellation, changed identity, unsupported settings or failed saves.
Imported profiles currently use the implemented resolution/FPS/bitrate choices;
unsupported profiles stay readable and cannot be partially imported. These
limits and full Sync remain on the parity queue.
Both host changes and successful local imports/resets invalidate an old Play
review. Local validation and the isolated package are recorded in
`build/deck-profile-sync/EVIDENCE.md`.
Every Game now includes **Keep in step**, saved separately for each PC and off
by default. While this view is open and Nova is active, it checks every three
seconds and sends differing device defaults at most once every five seconds.
It uses the same fresh pairing, capability, reviewed-profile and idle checks as
manual actions. It does not copy per-game choices or alter the PC's display mode.
Periodic checks preserve controller focus and the comparison being read.

An automatic save persists a paused state before sending; only a matching host
receipt re-arms it. Lost replies, stale preflight values, failed local saves and
interrupted writes leave it paused, including after restart. Refresh only reads
back while paused; Resume is explicit. Off cancels pending automatic work, though
an already dispatched request may have reached the PC. Use Polaris, Clear profile
and Reset Nova defaults turn it off first so a later poll cannot undo that choice.
The host still has no profile revision CAS; the GET/POST race and resolved-launch
truth remain outside this client slice. Evidence and local package:
`build/deck-keep-in-step/EVIDENCE.md`. Physical acceptance remains open.

Appearance startup now reads theme and text size without writing missing defaults
over saved values. A partial record containing only a larger text size keeps that
size after profile changes and restart.

Command Center adds named Players, waiting controllers and Reassign. The input
hub routes up to 16 player slots with separate feedback, neutral-input gating and
hotplug handling. On Deck, the primary Steam Input slot initially occupies P1;
explicit Reassign uses subsequent press order. Accessible Steam Input virtual
pads take precedence over raw physical devices to avoid duplicate presses.
Physical Steam Input mapping, controller reordering, multi-pad input and rumble
still require installed acceptance.

System → Sleep Host checks the selected Polaris PC's support, enablement and
paired-device permission. Hold for one second to start a five-second Cancel
countdown. The app rechecks permission and identity, sends one pinned request
without replay, then checks whether the PC stops responding. If it stays online,
the app shows its fresh outcome or a still-responding message. Streaming, focus
loss and a changed PC/pairing block or cancel pending actions. Local controller,
touch, keyboard and mTLS fixtures pass; physical host sleep/wake and installed
Deck acceptance remain open. Standard hosts cannot use this Polaris action.

Command Center → NovaHUD offers Slim, Minimal, Performance and Debug layouts,
background opacity and position controls. It defaults off; layout, visibility,
opacity and dragged position persist. The unboxed Debug layout groups HOST,
NET and CLIENT readings. Plain taps pass through; dragging moves the HUD and
holding it opens Command Center. The HUD hides during controls and recovery.
Sampling runs once per second on the session worker. Composed FPS, decoded and
incoming rates, received video bitrate, RTT/variation and host processing time
have distinct sources. Composed FPS is not a physical presentation claim.
A separate cancellable worker reads pinned Polaris session status for the owned
active game. HOST BIT uses the canonical encoder-applied bitrate, alongside the
quality limit and current tuning state. NET VIDEO remains received video
payload throughput. Doctor health and display overrides use fixed labels;
control-channel observations never become media-loss readings. Session/token,
generation, host-instance and saved-pairing checks reject stale or foreign data.
Failed reads clear host values; readings expire after 3.5 seconds. Legacy hosts
can show the tuning preference but cannot claim an applied bitrate. Standard
hosts retain local metrics without Polaris polling. Decode latency, media loss,
frame drops, 1% lows and complete HUD/physical parity remain open.

Command Center now has an immediate Live Tuning On/Off switch. Its caption stays
inside the focused row, including during saves and at large text sizes. A save
rechecks the active owner, permission, pairing, game/session and exact configuration
revision, sends one conditional paired request, then refreshes host status.
Lost replies and conflicts never replay the request; they show the refreshed
state before another explicit choice. Turning tuning off holds the confirmed
bitrate. The switch saves a host preference even when the current encoder cannot
adjust live; it does not claim an immediate encoder change. Legacy status without
canonical revisions remains observational.

Command Center → Live bitrate now provides a 1–300 Mbps picker with touch,
controller steps and an explicit Apply action. Editing or backing out sends no
request. The single session-scoped host request turns Live Tuning off and replaces
pending Doctor bitrate adjustments; Play Setup stays unchanged. Fresh permission,
pairing, generation, host-instance, revision and quality-limit checks guard the
send. This host route has no server-side revision precondition, so only the
preflight checks the revision. The receipt is treated as a request acknowledgement;
only canonical encoder telemetry matching the chosen target and tuning Off earns
an Applied message. Delayed acknowledgement is polled for five seconds; rejection,
changed authority, timeout or lost replies cannot fabricate success or replay the
request. Unsupported encoders remain unavailable. Doctor/Undo and full Live
Tuning/physical parity remain open.

During an owned stream, Deck now subscribes to the selected host's paired session
events on the HTTPS port advertised by authenticated status. Reconnects, missing
or out-of-order event IDs, changed tuning and session hints trigger a fresh status
read. Events never establish ownership, close a game or confirm a bitrate by
themselves. A read that overlaps an invalidation cannot restore stale controls.
The focused tuning row stays in place with “Refreshing…” until the current state
arrives; no action can be submitted while that state is unknown. The event worker
is separate from input and status reads and is cancelled on stream teardown.
Unavailable/invalid event services fall back to regular status polling; a failed
pairing certificate or authentication clears tuning authority for that session.
Event frames are bounded, stalled connections time out, and retries back off.
This closes the local session-event resynchronization implementation slice.
Real-host/installed acceptance, event breadcrumbs, library event invalidation and
the remaining Live Tuning/Sync work are still open.

Command Center → Doctor now provides Diagnosis, Evidence and Session pages.
The finding comes first, followed by the strongest supported evidence, its
confidence and a suggested manual next step. Evidence shows separate HOST / NET /
CLIENT groups, measurements and their sources. Session distinguishes the encoder's
applied bitrate from the quality ceiling and received video throughput, and keeps
incoming, decoded and composed rates separate. D-pad navigation scrolls the reading
pane; touch can scroll and select pages. Refresh readings queues a fresh status GET,
clears stale findings and preserves focus while waiting. It never runs a host action.
Back returns to the Doctor row; resume, stop and disconnect close the modal.

Doctor v2 must pass the current owned-session checks. Unknown or malformed
evidence is unavailable; control retries never become video loss, informational
capability watches do not become failures, and actionable display overrides stay
visible under a green host verdict. This preview uses bounded known-issue copy
and typed measurements, excluding raw host prose, action payloads, paths, IDs and
AI advice from the view. Unsupported/custom evidence and richer host explanations
remain parity work.

Doctor now offers Auto Fix when the host supplies a valid, reversible live-bitrate
action. Lower bitrate requires confirmed media loss or RTT pressure; Restore
quality requires clean evidence and a matching quality ceiling. The proposed
target is reviewed again using fresh owned-session status before a single paired
POST. Changes stay pending until Polaris acknowledges the encoder and verifies
post-change evidence. The result distinguishes Verified, restored/rolled back,
superseded and an unconfirmed rollback. Undo restores this run's prior live
settings through the host's scoped receipt contract.

A lost response offers Check result. For a missing initial receipt, that explicit
press uses the original idempotency key and payload; it cannot create a different
fix. Verification and Undo requests are never automatically replayed. Automatic
verification is bounded to 64 checks/three minutes, pauses on uncertainty and
loses authority when the session changes. Receipts currently live only for the
active native session; app-restart receipt recovery remains open. Host-managed
verification/rollback can continue after the menu closes. All local mutation
checks use isolated loopback hosts, not the player's running host.

Support-report export, richer explanations, persistent receipt recovery, Sync and
real-host/physical acceptance remain open. The full
[parity inventory](../../docs/deck-release-parity.md) remains open.

## Current preview smoke scope

The default offline smoke validates the native window, 1280x800 controller-first layout, fake host list states, an inert launch preview, local clipboard copy feedback, and controller routing. It does not load an identity or start a stream.

Live library reads, Moonlight handoff and native streaming are separate opt-in routes described below. Offline smoke results do not establish physical streaming acceptance.

Planned role:

- first non-Android Nova client
- native Linux and SteamOS implementation
- controller-first fullscreen handheld UX
- built on top of the shared Nova backend layers rather than the Android shell

## Runnable smoke paths

Native core and controller/library tests with the QML shell disabled (Qt and Linux media development dependencies are still required):

    cmake -S clients/deck -B build/deck-smoke-core -DNOVA_DECK_BUILD_QT_SHELL=OFF -DCMAKE_BUILD_TYPE=Debug
    cmake --build build/deck-smoke-core
    ctest --test-dir build/deck-smoke-core --output-on-failure

Full Qt shell smoke, when Qt deps are present:

    cmake -S clients/deck -B build/deck-smoke-qt -DCMAKE_BUILD_TYPE=Debug
    cmake --build build/deck-smoke-qt
    ctest --test-dir build/deck-smoke-qt --output-on-failure

The Qt smoke runs nova-deck --smoke-exit with QT_QPA_PLATFORM=offscreen, so it verifies QML object creation and sample library-card data binding without launching a visible desktop window. It does not verify real D-pad focus or game launch behavior yet.

Steam Deck Game Mode rootless Podman validation route, for preview/QSG render cards that must run against the actual Deck gamescope socket:

    python3 clients/deck/scripts/deck_t31_podman_validation.py

The script syncs the current source tree to `deck@<deck-host>:/var/tmp/nova-t31-src`, runs `localhost/nova-t24-arch-qt-buildtools` with `/run/user/1000` and `/dev/dri` mounted, builds `clients/deck` with CMake/Ninja, runs Deck CTest, runs `nova_deck_qsg_render_node_scenegraph_smoke` directly with `QT_QPA_PLATFORM=wayland WAYLAND_DISPLAY=gamescope-0 QSG_RHI_BACKEND=opengl LIBVA_DRIVER_NAME=radeonsi` so the CTest offscreen property cannot mask the live gamescope route, and pulls logs into `build/deck-t31-artifacts`. Use `--dry-run` to print the exact sync/container/artifact commands, or `--skip-sync` when the Deck source directory is already prepared.

The route now runs the T32 preview pump oracle after pulling artifacts, so the same command exits non-zero unless the Deck artifacts machine-prove all of the following: `nova_deck_stream_media_adapters_test` covered newest-frame coalescing and invalid-reset stale-presentation clearing, full remote CTest passed, `qsg-gamescope-smoke.log` contains a real Deck render proof with `status=ready objects=1 layers=2 ready=1`, and the route source still avoids host streaming, discovery, pairing, credential, and Polaris launch paths. To check already-pulled artifacts directly, run:

    python3 clients/deck/scripts/deck_t32_preview_pump_oracle.py --artifacts build/deck-t31-artifacts

Visible frontend smoke route, for judging the Deck product shell on the actual Game Mode Wayland path without host networking:

    python3 clients/deck/scripts/deck_frontend_smoke.py --local-artifacts build/deck-frontend-smoke-artifacts

The frontend smoke uses the same rootless Deck Podman image with `--network=none`, launches `nova-deck` visibly through `QT_QPA_PLATFORM=wayland WAYLAND_DISPLAY=gamescope-0`, and asks the app to save its own `frontend-frame-capture.png`. Artifacts include `environment-summary.txt`, `ui-launch.log`, `qml-runtime.log`, `smoke-summary.txt`, and the frame capture when Qt can grab the window.

## Live route: real hosts and library through Moonlight's pairing

`nova-deck --live` (or `NOVA_DECK_LIVE=1`) replaces the fixture with the hosts Moonlight-Qt has already paired on this device and the library Polaris serves for them. Nothing is launched and no session is started; the preflight stays read-only with `backendPowerStarted=false`.

How it works:

- `src/identity/deck_moonlight_identity.*` reads Moonlight-Qt's own settings file: the Flatpak copy under `~/.var/app/com.moonlight_stream.Moonlight/config/`, the native copy under `~/.config/`, or the file named by `NOVA_DECK_MOONLIGHT_CONF`. That file holds the client certificate and key, every paired host with its pinned server certificate, and the cached app list per host. The private key stays inside the identity object; no public DTO or log line carries it, and host addresses never reach the shell either.
- `src/polaris/deck_polaris_client.*` learns the HTTPS port from the host's plain-HTTP serverinfo the way Moonlight does, then calls `/polaris/v1/capabilities` and `/polaris/v1/games` with that client certificate. The server certificate Moonlight pinned at pairing is the only trust anchor: the handshake fails against any other certificate before the request is sent, and the host is reported as a certificate mismatch. Network failures are reported with fixed wording, never with the address.
- `src/backend/deck_live_read_only_state.*` turns the probes into the same sanitized read-only DTOs the fixture route produces. Every paired host is probed at the same time, so the wait before the shell appears is the slowest host's timeouts, not the sum over hosts. The first reachable Polaris host supplies the library; when none answers, the apps Moonlight cached stand in, labelled as such.

On the host side, any paired certificate that calls `/polaris/v1` is promoted to the `nova` client family, so after the first live run Polaris sees the Deck's Moonlight pairing as a Nova client. That is the intended shape: one pairing, one identity, two apps on the Deck until the media slice lands.

To check the route without opening a window:

    QT_QPA_PLATFORM=offscreen nova-deck --print-live-state

It prints the identity source, each host's probe result (`ok`, `unreachable`, `cert-mismatch`, `unauthorized`, ...), which library source won, and the first titles. Exit code 2 means no Moonlight pairing file was found.

## Handoff: Play in Moonlight

On the live route the launch card's A button hands the highlighted game to Moonlight-Qt instead of copying a plan. The first press arms the request for eight seconds and says so, B or Escape cancels, and a second press within the window runs `moonlight stream <host-uuid> "<app>" --display-mode fullscreen`, where the host is named by the UUID Moonlight already knows, so no address enters the command line. While Moonlight runs, Nova asks Polaris for the session truth every five seconds and shows it under the card; A asks Moonlight to end the stream; when Moonlight exits, Nova asks for focus back.

`src/runtime/deck_moonlight_launcher.*` is the only place in the client that starts another program. It finds Moonlight as a native binary on PATH, as the `com.moonlight_stream.Moonlight` Flatpak, or through `NOVA_DECK_MOONLIGHT_BIN` for tests; inside a Flatpak sandbox it prefixes `flatpak-spawn --host` and forwards the display Nova is on, because the host runs the command with its own environment and under gamescope that environment names no display, so Moonlight would try to drive DRM directly and be refused. Only two names travel: `DISPLAY` as Nova sees it, and `WAYLAND_DISPLAY` translated back to the host's socket name, since Flatpak renames any host socket that does not start with `wayland-` (the Deck's `gamescope-0`) to `wayland-0` inside the sandbox; the bound socket keeps its inode, and the manifest binds `xdg-run/gamescope-0`, so the launcher finds the host name by matching inodes. When a Wayland socket travels, `QT_QPA_PLATFORM=wayland;xcb` goes with it so Moonlight draws on it directly. Sandbox-only paths such as `XAUTHORITY` and Nova's own platform choice never leave the sandbox. Every argv token is checked to be plain (no control characters; shell characters are fine because no shell is involved, and game titles carry ampersands), the child is started without a shell, and its output is kept only as a bounded backend-only tail that never reaches the shell.

Headless proof, with a real Moonlight or a recorder script:

    NOVA_DECK_MOONLIGHT_BIN=/path/to/recorder QT_QPA_PLATFORM=offscreen nova-deck --live --handoff-game "Desktop" --handoff-wait-ms 5000

It prints the launch phase, how Moonlight ended, and a bounded tail of Moonlight's own output (backend-only: it can carry host addresses, so the shell never shows it). Exit codes: 0 launched and exited, 3 never launched, 4 still running when the wait ran out. Add `--handoff-windowed` to keep a real Moonlight in a window, for a proof on a desktop rather than a Deck.

## Game Mode and packaging

### Standalone setup (local development)

    nova-deck --standalone

On a fresh profile this opens **Pair a PC**: enter a PC name or IP address and
its GameStream HTTP port (47989 by default), select **Pair PC**, and enter Nova's
four-digit PIN in the host's pairing screen. **Open library** continues in the
same app using Nova's identity and the native preview action. Subsequent starts
load the saved hosts. `nova-deck --pair` opens setup to add another PC.

**Saved PCs** in the standalone library opens pairing management. It is also
available through `nova-deck --manage-pcs`. Select a saved PC to choose **Unpair
from PC**, **Forget on this Deck**, or **Keep PC** (the initial focus). Unpair
checks the saved certificate and authenticated host ID before sending the TLS
request. Only the host's successful unpair response removes the saved record;
timeouts, changed certificates and ambiguous responses leave it available for
retry. Forget is an explicit local action and makes no network request. Its
result reminds the player to remove Nova from the host's paired devices.

Both actions preserve Nova's client identity and other saved PCs. Pairing and
removal share the process lock and reject stale selections. **Add PC** returns
to the PIN flow, allowing a fresh handshake after removal. Returning to the
library destroys the old session/controller and reloads the saved identity;
the management action is unavailable while a native session is busy. Saved
records now retain the confirmed HTTPS port for nonstandard host forwarding.

Nova creates its RSA client certificate only after Pair is selected. It stores
that identity and verified host pins in `$XDG_CONFIG_HOME/nova-deck/identity.json`
(default `~/.config/nova-deck/identity.json`), with a private directory, owner-only
files and atomic writes. `NOVA_DECK_IDENTITY_DIR` can isolate a development profile.
Corrupt, mismatched, public or symlinked identity files fail without silent
replacement. Concurrent Nova pairing processes are excluded; saved host IDs or
endpoints cannot be silently re-paired or have their pins replaced.

**Trusted Pair** follows Android Nova's trusted-subnet flow: Nova checks for
`TofuEnabled=1`, opts in with `trustedpair=1` on the certificate request only,
and uses the protocol's fixed `0000` challenge input. Polaris must have trusted
subnet pairing enabled for the Deck's network. The capability hint does not
replace signed-challenge checks, exact certificate pinning, mutual TLS or
authenticated host-ID confirmation. Saved pairings cannot be overwritten.
If the capability is absent or disabled, setup displays a fresh normal PIN.
An invalid response, failed handshake, cancellation or uncertain cleanup never
automatically starts another attempt. **Pair with PIN** always remains explicit
and never sends the trusted-pair flag, even if the host advertises support.

Tap **PC address** or **Host HTTP port** to open the touch keypad. Addresses
start with numbers, dots and colons; **ABC** switches to a hostname keyboard and
**#123** returns to numbers. Port entry accepts digits only. Backspace edits at
the caret; **Clear**, **Cancel** and **Done** are also reachable by D-pad.
Cancel/B keeps the original field; Done applies the edit and restores focus.
The popup does not pair, save credentials or make network requests.

The generation-7 GameStream handshake follows Nova Android's
[PairingManager](../../app/src/main/java/com/papi/nova/nvstream/http/PairingManager.kt):
PIN-derived AES challenge, certificate-bound hashes, signed secrets, then exact
pinned mutual TLS confirmation and authenticated host identity. The PIN and
private key are never sent as query values; only the protocol's challenge
material and public certificate travel over the initial HTTP pairing route.
Redirects are refused, replies are bounded, and each request has a deadline.
The new pairing transport and the existing library client check the exact peer
at Qt's [encrypted signal](https://doc.qt.io/qt-6/qnetworkreply.html#encrypted),
before HTTP data is sent. A certificate signed by a pinned CA is not accepted as
the pinned peer.

Key generation, pairing and persistence run on one worker. Cancel clears the
displayed PIN and aborts pending I/O; close waits asynchronously for cleanup.
Before host authorization, rollback clears the pending pairing transaction.
After authorization may have occurred, rollback uses pinned TLS to revoke it.
An unconfirmed rollback remains visible in the result. Only a completed PIN,
TLS and host-identity check may persist a host.

The local regression covers crypto known answers, permissions/corruption,
duplicate pairing, wrong PIN/signature, malformed and oversized replies,
redirects, mismatched certificates, cleanup failures, UI responsiveness and
restart persistence. A loopback host completes the actual HTTP and mutual TLS
exchange. The setup QML has endpoint-entry, focus, PIN clearing, cancel/retry,
success and window-close coverage. Captures use fixtures, not a real pairing.

Management coverage includes wrong host/pin, cancelled probes, ambiguous unpair
replies, explicit offline forget, stable client identity, stale selection and
concurrent-change refusal, plus a fresh PIN handshake after removal. A loopback
host handles real pinned mTLS unpair requests. The production saved-PC QML tests
check confirmation focus, failure/retry, empty-list focus, Add PC and safe close.
With Xvfb and xdotool available, `nova_deck_saved_pcs_route_test` runs the actual
app through two library/management/rebuilt-library cycles, forgets a fixture PC
and verifies persistence and clean exit. These are local fixture results; host
acknowledgement does not establish installed-device or cross-host revocation
acceptance.

This remains a development preview. Automatic discovery, Wake-on-LAN,
QR/OTP setup, cross-host revocation behavior and
installed-Deck acceptance remain open. The explicit `--live` route
imports Moonlight's identity; desktop and newly registered Steam shortcuts use
`--standalone`, which never falls
back to it or hands Nova-owned credentials to Moonlight.

### Standard-host libraries (local development)

When the pinned HTTPS host reports that the Polaris capabilities endpoint is
absent (HTTP 404 or 501), Nova checks its authenticated GameStream host ID and
pairing state, then reads `/applist` over the same pinned client. App IDs, titles
and HDR capability are mapped into the library. The native GUI uses the numeric
app ID and GameStream request identifiers for launch and stop; synthetic library
IDs are not sent as Polaris app UUIDs. A cached entry cannot authorize a native
launch after an explicit pairing or certificate rejection.

Polaris libraries retain precedence over standard-host libraries across saved
PCs. An empty successful live list stays empty. Authentication failures, changed
certificates, redirects, malformed responses, timeouts and server failures do
not trigger a standard-host retry. A failure from an existing Polaris games
endpoint also remains a failure. Imported Moonlight caches retain their offline
display role; Nova-owned profiles do not borrow those caches.

GameStream XML parsing follows the fields consumed by Android's
[NvHTTP](../../app/src/main/java/com/papi/nova/nvstream/http/NvHTTP.kt). It rejects
partial documents, duplicate app IDs/known fields, DTDs, invalid IDs and missing
titles. The app list is limited to 1 MiB and 4096 apps, with bounded field lengths
and nesting. The shared pinned HTTP client now bounds each response and enforces
an absolute deadline even when a peer keeps sending bytes. Replies and host
errors are represented by fixed diagnostic text, without raw response bodies.

Nine portable test targets pass locally on macOS, including real loopback mTLS
library/launch/cancel requests, negative fallback cases, empty lists, response
limits, slow replies, library selection and existing Polaris/identity contracts.
This protocol-only target needs Qt Core/Network, OpenSSL's test executable and
the pinned `moonlight-common-c` headers:

    cmake -S clients/deck/tests/portable -B build/deck-portable -DCMAKE_BUILD_TYPE=Debug
    cmake --build build/deck-portable -j6
    ctest --test-dir build/deck-portable --output-on-failure

This portable target does not build the Deck application or exercise Linux
media. The full Linux build and CTest suite now pass: **52 passed, one hardware
scenegraph smoke skipped** because VAAPI is unavailable, with the optional HDR
validation backend enabled. The seven affected media/color targets also pass
after the final GPU-lifetime and import-guard review. The actual standalone
application also renders a standard-host library at 1280×800 using temporary
loopback mTLS credentials, with no launch/pairing requests or credential changes.
Its regression checks the request sequence, game titles and clean QML startup;
the reviewed capture confirms clear focus and literal rendering of markup in
titles. The standalone view now explains the review step without showing the
old fixture lab-gate warning or internal DTO label.

To retain a fixture capture while running that application regression locally:

    python3 clients/deck/tests/deck_standard_library_route_test.py build/deck/nova-deck --capture-dir build/deck-preview-artifacts

Installed standard-host/Deck media acceptance remains open.
Artwork editing/offline storage, stream reconnect and full settings/media
compatibility remain part of the release parity work.

### Standalone library refresh and PC selection (local development)

In `--standalone`, choose a saved PC with A/Enter or touch to load that PC's
library. Moving focus through the picker does not change the selection. The
**System → Refresh games** action rechecks the selected PC in the background and preserves the
selected game by ID when it is still present. Both Polaris and standard
GameStream libraries use the existing pinned requests. Explicit selection never
substitutes another online PC when the chosen PC is unavailable.

Refresh and PC selection are disabled during a native session. Launch is
disabled during a library request, and failed or empty responses clear previous
games and launch targets. A fresh response updates the library and native
resolver together, so matching numeric app IDs on different PCs cannot cross
hosts. Saved credentials are checked before and after refresh and again before
native launch. If the saved pairings change, reopen **Saved PCs** to reload them.
Closing during a refresh waits asynchronously for the bounded read to finish.

Controller tests cover responsiveness, overlapping requests, active sessions,
matching app IDs, denied/empty libraries, exceptions and changed pairings. A
real-app keyboard regression uses two temporary loopback mTLS hosts to verify
refresh focus, selection, rejection and recovery without launching any game or
changing credentials. To retain its 1280×800 fixture captures:

    xvfb-run -a -s '-screen 0 1280x800x24' python3 clients/deck/tests/deck_library_navigation_test.py build/deck/nova-deck --capture-dir build/deck-preview-artifacts

The standalone library also checks the selected PC automatically every 30
seconds while the game list has focus. Checks pause when the window is inactive,
focus is on other controls, a picker/preview/diagnostics screen is open, or a
native session is busy. Returning to browsing after backgrounding or a session
queues a fresh check. Transient failures back off to 60, then 120 seconds;
pairing rejection, certificate mismatch, invalid identity or changed saved
credentials stop automatic retries until an explicit refresh or PC selection
succeeds. An in-flight read still finishes within the existing request deadlines.

Background checks leave game navigation usable. Completion preserves the
selection at that moment, including moves made while the response was pending.
If a game disappears, the nearest surviving row takes focus. Unchanged public
models are not rebuilt. Automatic updates avoid inserting a loading banner or
dimming the list; the Refresh button briefly says **Checking…**. Launch remains
blocked during the read, and failed/empty results cannot retain stale games.

Controller coverage includes scheduling, overlap, foreground/session/modal
pauses, capped retries, startup/runtime trust rejection and shutdown. The actual
application regression covers navigation during a delayed response, reordered
and removed games, picker/preview pauses, window reactivation, library recovery
after an outage and stopped automatic retries after a rejection:

    xvfb-run -a -s '-screen 0 1280x800x24' python3 clients/deck/tests/deck_library_navigation_test.py build/deck/nova-deck --automatic --capture-dir build/deck-preview-artifacts

These are periodic pinned reads, not a host event subscription. Artwork,
imported-profile refresh, streamed-session recovery and installed-Deck
suspend/reconnect acceptance remain open.

### Native GUI preview (local development)

    nova-deck --live --native-ui

This adds **Preview in Nova** below the Moonlight action. Opening it displays a
review screen; **Start preview** explicitly launches the selected backend-owned
host/game. No network start occurs just from enabling the option or opening the
screen. This is the explicit legacy route; the default Steam shortcut opens
the standalone interface and its native Play flow.

Launch, connection setup, teardown and host cancellation run on one worker.
Cancellation before launch prevents the launch; cancellation during launch waits
for its bounded HTTPS reply and cleans up a successfully created host session.
Pending GameStream setup is interrupted, then stopped on its original worker.
New starts remain blocked until the worker and cleanup finish. During setup,
**End preview**, B or Escape cancels the attempt. During play, **Disconnect**
tears down Nova's stream without asking the host to quit. **End game** opens a
confirmation, initially focused on **Keep playing**, before asking the host to
end this session's game. Closing Nova detaches an active ordinary stream and
waits asynchronously for teardown; a pending launch still gets cancelled and
cleaned up. A host that refuses or does not confirm an End game request remains
visible in the result. Host idle/exit policies still apply after disconnect.

The decoder feeds a single-frame mailbox; only the GUI thread updates the Qt
surface. Opus playback uses the existing PipeWire adapter. This is an H.264 SDR
1280×800/60 preview with basic controller forwarding. **HDR, physical suspend recovery,
keyboard/mouse/touch forwarding, motion input and physical rumble acceptance remain
unfinished**. The `--live` route uses the imported Moonlight identity;
`--standalone` uses Nova's saved identity. Both require the exact pinned
certificate. The native preview must pass installed-device video/audio
acceptance before it replaces handoff. Local fake-host lifecycle tests and UI
captures do not establish decoded/presented frame or audible-output acceptance.

`nova_deck_native_session_test` checks UI heartbeat during blocked launch work,
cancellation at HTTP/connection stages, duplicate starts/stops, disconnect,
retry, shutdown, global connection exclusion and session-token cleanup. It also
checks that private host/session material stays out of the QML state map.

The video surface fits each frame inside its current bounds without stretching.
Its GL composition follows Qt's model-view/projection matrices, inherited
opacity and scissor/stencil clips, using vertex buffers compatible with desktop
core OpenGL. These follow the [QSGRenderNode rendering contract](https://doc.qt.io/qt-6/qsgrendernode.html#render).
`nova_deck_video_composition_test` checks actual shader pixels under software
Mesa/Xvfb: translation/scale, texture orientation, opacity, an overlaid Qt item,
both clip paths and letterboxing after resize. It needs `xvfb-run` and a working
software OpenGL driver; CMake reports when this CTest cannot be registered.
The test uses synthetic Y/UV textures and makes no VAAPI/dmabuf claim. The
separate hardware scenegraph smoke reports **Skipped** when its VAAPI or
headless EGL prerequisites are unavailable.

On X11, startup defaults to Qt's EGL integration and OpenGL scenegraph so the
VAAPI importer has a current EGL context; an explicit backend override remains
available for tests. `nova_deck_graphics_startup_test` checks the actual app's
render-thread OpenGL/EGL context under Xvfb. The owner confirmed live video on
the installed Deck after this correction; that does not accept audio or HDR.

`NativeStreamPreview.qml` owns the preview controls and focus transitions.
`nova_deck_native_preview_qml_test` loads that production component with a local
session fixture and checks Start, Continue, End game, pointer-opened controls,
cancellation, failure, retry and return to library. Hidden actions cannot retain
play focus. Optional test argument `/path/to/captures` saves 1280×800 screenshots
of the active controls and completed stop; these contain no live stream.

When the stream connects, select **Continue game** to route the controller to
the host. A/B and the D-pad then operate the game. Press **View + Menu together**
(within 150 ms), tap the **Menu symbol**, or press Escape to open Nova's overlay. Both
shortcut buttons stay local; an individual View/Menu press still reaches the
game after that short chord window, including a quick tap. In the overlay,
Continue/B resumes. **Disconnect** leaves the host game open; **End game** needs
an explicit confirmation. B/Escape dismisses that confirmation before it can
resume gameplay, and hidden/stale actions cannot end a later session.

The worker releases held input before teardown. The first exit choice is fixed:
a second click, window close or destruction cannot change Disconnect into End
game. Disconnect is offered only after a successful connection and is not
offered for a Space entry, whose Android leave behavior ends that session.
Failed new launches retain their existing host cleanup. Lifecycle and production-QML
tests cover blocked teardown, late successful launch, repeat/close races, input
release, next-session isolation, touch/D-pad/keyboard confirmation and return
focus at 1280×800 and 960×600.

After a successful disconnect, **Resume game** reconnects using the previous
stream settings, with **Back to details** still available. The backend retains
the host/game IDs, app identity, reviewed configuration and session token only
in memory; no token enters QML or settings. Resume resolves the current saved
pairing/library again, checks the live paired ownership and exact app/session,
and sends the expected token with fresh stream keys. A changed or expired
session is refused without launching a replacement. The host's running launch
mode is preserved. Held input must return to neutral before gameplay resumes.

Ordinary **Play** also resumes a matching, positively owned, token-backed game
reported by the host, including after reopening Nova. Another game's session
or one owned by another device is not replaced. Hosts without ownership/token
support still allow fresh launches, but existing games need to be ended on the
PC before starting again; legacy resume remains open. Space resume and watch
mode remain separate work. Failed, cancelled or interrupted resume attempts
leave the existing host game alone; only a confirmed End game after connecting
requests its termination. Local protocol, worker, production-QML and real
loopback mTLS tests cover identity/ownership, token changes, refusal, pairing
revocation, wrong pins, pending cancellation, settings and input/focus recovery.
Installed disconnect/resume acceptance remains open.

An unexpected ordinary-stream interruption now leaves the game on the PC
without requesting Quit, including after a fresh launch or controller-send
failure. Once the old worker and transport finish, **Reconnect** and **Back to
details** are available when Nova holds an exact session token. Reconnect uses
the reviewed settings and the same fresh pairing, game, ownership and token
checks as Resume. It never launches a replacement game. A verified resume that
fails during media connection can be retried manually; rejected or unverifiable
session checks return to the library. Held controls must return to neutral, and
the player chooses Continue game before input resumes. Closing the recovery
screen does not ask the PC to end the game.

Graceful host termination offers no reconnect. Hosts without session tokens
receive an actionable return to the library; Space cleanup remains separate.
Local worker and production-QML tests cover blocked teardown/close races,
repeated interruption and retry, controller-send/queue failures, exact-session
refusals, cancellation, touch/D-pad focus and unchanged settings. Physical
suspend and recovery acceptance remain open.

For a positively identified Polaris game, a recoverable connection termination
now starts **automatic reconnect** after full transport teardown. It follows
Android's four-attempt budget with delays of 0, 1, 3 and 7 seconds. Every attempt
rechecks the current saved pairing, game, ownership and exact session token.
Only explicit timeout/unreachable results permit another host check; changed
certificates, revoked access, changed sessions and malformed responses stop the
automatic path. Graceful exits, protected-content/frame-conversion errors,
controller-input failures and unsupported standard/Space sessions do not trigger
automatic retries. Media setup failures retain manual Reconnect.

The recovery screen shows the attempt number and **Cancel reconnect**, with
touch, controller and Escape support. Cancel, close, lost window focus and
destruction stop pending retries without asking the PC to quit. A pending
connection that becomes active before the GUI sees it still treats Cancel as
disconnect. After the budget runs out, Reconnect and Back to details remain
available. A manual retry starts a fresh budget.

A briefly connected stream does not reset the budget. Fifteen seconds of
continuously advancing successful composition samples earns it back; queued
frames, repeated redraws, failed imports and stalled samples do not count. This
is a render-path counter, not proof of physical display scanout. Local tests
exercise the schedule, repeated unstable connections, cancellation and identity
refusals. Physical automatic recovery and video/audio/controller continuity
remain unaccepted.

Sleep now closes an active ordinary stream without asking the PC to end the
game. Controls are released before transport teardown, pending retries stop,
and browsing checks pause. Wake offers **Resume game** with the original stream
settings; it never automatically launches, resumes or replays held controls.
Resume repeats the existing saved-pairing, exact-game, ownership and session-token
checks, then requires **Continue game** and neutral controls before gameplay input.
An End game already in progress keeps its original intent. A pending new launch
still owes cleanup; Space sessions retain their separate leave behavior. A
cancelled pending owned-session resume retains its ticket for a fresh check after
wake, unless the host has already rejected the session selection.

The Linux adapter listens to logind's `PrepareForSleep` and reconciles its current
state on service changes. A delay inhibitor gives cleanup up to three seconds
(or less under host policy) before Nova releases it; cleanup completion releases
it sooner. Nova does not request sleep or prevent it indefinitely. Missing or
denied delay support leaves the application usable. Flatpak grants service-scoped
`org.freedesktop.login1` access. This follows the official
[logind inhibitor protocol](https://systemd.io/INHIBITOR_LOCKS/), and Android
`Game.onStop()` provides the preserve-game/background-stop behavior reference.
Low-level suspend that bypasses logind is outside this notification path.

Private D-Bus, native-worker and production-QML regressions cover bounded delay,
duplicate/stale signals and replies, service replacement/loss, denied locks,
teardown/wake races, explicit End precedence, pending launch/resume/Space cleanup,
retry cancellation, exact-session refusal and touch/D-pad recovery. Local tests
never suspend a machine. Installed Deck sleep/wake cycles, network/device return,
audible audio and controller continuity remain physical acceptance work.

The current input slice supports one joystick (`/dev/input/js0`, or
`NOVA_DECK_GAMEPAD_DEVICE`). It queries the device's axis/button maps, handles
the Steam/Xbox legacy X/Y labels, normalizes sticks/triggers and uses a small
fixed deadzone. See the [Linux gamepad specification](https://docs.kernel.org/input/gamepad.html)
and the [xpad legacy button mapping](https://github.com/torvalds/linux/blob/master/drivers/input/joystick/xpad.c).
Unknown or unreadable maps do not use guessed button indices for streaming.
Individual device layouts and advanced controller settings still need acceptance.

Entering play requires all controls to return to neutral. Overlay opening and
focus loss release held controls and discard queued presses while keeping the
host controller connected. This preserves the device given to an isolated game
at launch. A physical unplug removes it, including while the overlay is open.
Focus return requires Continue; held buttons are not replayed. The
worker preserves button edges in a bounded queue and sends a final neutral
removal before stream teardown. Overflow or a send failure ends the preview
with a visible failure. Local mapping/router and worker tests cover these
paths. On 2026-09-18 the owner confirmed basic game controls work on the updated
Deck preview. Extended focus/overlay, hotplug and recovery acceptance remains open.

### Flatpak and Steam shortcut

`packaging/flatpak/` builds the shell as the `com.papi_ux.Nova` Flatpak on `org.kde.Platform` 6.10; see its README for the build, install and permission notes. `nova-deck --register-steam-shortcut` adds Nova to Steam as a non-Steam game so Game Mode can launch it: `src/runtime/deck_steam_shortcuts.*` parses and rewrites Steam's binary `shortcuts.vdf` byte for byte, registers or replaces one "Nova" entry, writes atomically, and refuses while Steam runs because Steam rewrites that file on exit. Inside the Flatpak the entry runs `flatpak run com.papi_ux.Nova --standalone`. Updating a Flatpak does not change existing Steam launch options; re-register an older `--live` shortcut with Steam closed. Registration preserves the app ID and player customizations. Fixtures are found at runtime under `/app/share/nova-deck/fixtures` or next to the installed binary before the source tree is tried, and moonlight-common-c is linked statically so an installed binary carries it.

## Shared Polaris DTO boundary

Native C++ cannot include Kotlin source directly. For this first slice, fixtures/sample_polaris_game.json is a generated/shared-contract sample using the same snake_case keys covered by the Kotlin shared DTO tests. src/polaris_game_fixture.h and src/polaris_game_fixture.cpp load that fixture into a tiny native projection so the Deck shell can exercise a real library-card shape while the actual native Polaris API/client bridge is still future work.

Keep this boundary explicit until the shared contract is exported through a real native-consumable API. Do not fake Kotlin/C++ interop by including .kt files.

## Stream core skeleton boundary

The original no-network skeleton now also has an explicitly gated real connection
path through `DeckStreamSession::startNetwork`. `--native-launch "<title>"`
exercises launch, hardware decoding, teardown and host cancellation headlessly.
It does not prove visible presentation, audible playback or working game input.
The historical no-network route below remains available for offline tests.

clients/deck/src/stream/deck_stream_core.h is the first no-network native stream-core seam for the direct moonlight-common-c path. The CMake target links the real app/src/main/jni/moonlight-core/moonlight-common-c tree and the focused CTest includes Limelight.h, initializes STREAM_CONFIGURATION plus listener/video/audio callback structs, and verifies that the Deck lifecycle can move through idle, preparing, starting, active, stopping, stopped, cancelled, and failed states without opening sockets or calling LiStartConnection.

The media adapters now provide H.264 VA-API decode/presentation and Opus/PipeWire audio. The native GUI worker forwards basic controller input and routes two-motor rumble to the active controller when supported. Motion and LED callbacks remain unimplemented. The offline lifecycle stays network-disabled; only the explicit CLI launch and native GUI preview routes reach the guarded real-start lane.

## Fedora or SteamOS dependency notes

The native tests need CMake, C/C++ compilers, OpenSSL crypto development headers, Qt (including Qt DBus), the Linux media libraries required by CMake, and the checked-out moonlight-common-c submodule. The network regression also needs the `openssl` executable to create ephemeral test certificates in a temporary directory. Sleep-monitor tests require `dbus-run-session` and run a fake login service only on their private daemon.

For the Qt shell on Fedora, install the Qt 6 development packages if CMake warns that Qt6 Quick or QuickControls2 is missing:

    sudo dnf install cmake gcc-c++ openssl qt6-qtbase-devel qt6-qtdeclarative-devel

On Fedora, qt6-qtdeclarative-devel provides cmake(Qt6QuickControls2). SteamOS package names may differ; the required CMake components are Qt6 Core, Qt6 Gui, Qt6 Network, Qt6 DBus, Qt6 Qml, Qt6 Quick, and Qt6 QuickControls2.

Primary design reference:

- ../../docs/steam_deck_native_port_study.md

Guardrails:

- do not copy the Android UI framework into this client
- preserve Nova product behavior where it matters
- keep Deck-specific input, presentation, and lifecycle handling native to Linux and SteamOS


### Android-style library and details (local development)

The standalone interface now follows Android Nova's host-first landscape
header, portrait poster grid and separate game-details flow. **Options** switches
between Grid and Compact and offers all six Android sort modes; these choices
survive restart. Search narrows real game titles and has an actionable empty
state. A/Enter or touch opens details; B/Escape restores the selected game and
scroll position. Only the active control has the strong white focus treatment.
**System** contains refresh and Saved PCs. Automatic refresh pauses while using
these screens and preserves browsing selection when it resumes.

Posters load asynchronously through the saved PC's pinned client. Standard hosts
use `/appasset`; Polaris uses a cached manifest poster or the fixed cover route.
QML sees opaque image keys, not remote URLs or credentials. Pairing is checked before and after I/O,
old-library results are discarded, and response/decoded-image bounds apply.
Missing artwork retains a readable title placeholder. Cached manifests and
hero/logo/icon presentation are covered below; Artwork Studio, offline artwork
storage and event/reconnect invalidation still need parity work.

Local regressions cover grid/details/back navigation, search/no-results, pointer
activation, persisted options, 1280×800 and 960×600 layouts, refresh/PC switching,
and denied/stale/oversized artwork. The artwork network loop has its own worker
so resizing or cancelling an image cannot reenter Qt's pixmap job queue. Fixture
captures use invented libraries and missing-art placeholders; they are not
installed-Deck acceptance. To retain the UI captures (Xvfb, xdotool and ImageMagick):

    xvfb-run -a -s '-screen 0 1280x800x24' python3 clients/deck/tests/deck_library_navigation_test.py build/deck/nova-deck --experience --capture-dir build/deck-preview-artifacts

Remaining UI/UX parity includes full game details/Play Setup, artwork editing,
themes/font scale/accessibility and the remaining system destinations.


### Android library filters and metadata (local development)

The filter bar provides **All**, **Recent**, **Sources**, **HDR** and **More**.
Sources come from the selected PC's library; More exposes Android's available
categories and up to ten distinct genres. Filters combine with title search.
**Options → Sort** offers Library order, Recently played, Name A–Z, Name Z–A,
Source and HDR first, following `NovaLibraryUiStateMapper` ordering and stable
ties. The small blue underline marks the selected filter; the white treatment
continues to identify keyboard/controller focus.

Filters survive restart for the same PC. Switching PCs clears search/filter
constraints while retaining display/sort choices. B clears active constraints
from the library; empty results have a Show all games action. A standard host
that supplies no history or genre metadata gets an honest empty state, with a
focusable Back action for empty filter menus. Filtering is local and never
changes pairing, host metadata or launch permissions.

Details now show the host's source/platform/runtime labels, category, genres,
installation state, game HDR support and last-played date. Polaris timestamps
are epoch seconds; malformed/out-of-range dates are omitted. **Game HDR support
is library metadata, not evidence of an HDR-capable stream or display.**
Metadata-only background updates refresh the active filter/sort, preserve a
surviving selection, and retire a removed selection's details/Play action.

Query regressions cover the Android filter/sort contract without mutating host
order. The actual-app mTLS test covers mixed Polaris metadata, search/details/
Back, filter menus, sorting, metadata-only automatic refresh, switching to a
standard host, empty recovery and restart persistence. To retain fixture views:

    xvfb-run -a -s '-screen 0 1280x800x24' python3 clients/deck/tests/deck_library_navigation_test.py build/deck/nova-deck --filters --capture-dir build/deck-preview-artifacts

Concurrent artwork/library credential validation now uses shared read locks on
the native identity directory. Initialization, saving and forgetting a PC retain
exclusive nonblocking locks; reads still refuse an active writer. A deterministic
regression reproduces the old reader conflict and verifies both write exclusion
and stable identity. This prevents a cover request from making a library refresh
mistake a harmless concurrent read for changed credentials.

### Android Stage browsing (local development)

Options cycles **Grid → Compact → Stage** and retains the layout across restarts
and PC changes. Stage follows Android's selected-game title and metadata above
a horizontal 2:3 poster rail. Left/Right reaches the full library and stops at
each end. Up reaches **Review and launch**, then the filters; Down returns to the
selected poster. The rail and review action open the existing details flow.
Opening review does not start a game or stream.

Back restores the selected game and scroll position. Touch uses the same route.
Search and filters also apply in Stage; empty results hide the hero and retain
recovery controls. Background reorder/removal replaces the hero and selected
launch target together. The actual-app mTLS regression covers a 26-game rail,
edge navigation, review/back, touch, long titles, resizing between 1280×800 and
960×600, refresh, empty recovery, PC switching and persisted layout. Fixture
captures use title placeholders where poster artwork is missing.

    xvfb-run -a -s '-screen 0 1280x800x24' python3 clients/deck/tests/deck_library_navigation_test.py build/deck/nova-deck --stage --capture-dir build/deck-preview-artifacts

This completes the local Stage browsing layout. Cached cinematic artwork is
covered below. Active-session Stage actions, full Play Setup and physical Deck
acceptance remain open.

### Cached cinematic artwork (local development)

The library now reads Polaris artwork manifests and renders cached posters,
heroes, transparent title logos and icons. A shared hero backdrop follows the
selected game across Grid, Stage and details, with Android's horizontal/vertical
scrims keeping text readable. Stage includes the game icon; details use the logo
when available and the game title otherwise. Missing or denied heroes use the
ambient background, without stretching a poster across the screen.

Only cached assets on the selected game's fixed Polaris routes are accepted;
external/cross-game URLs and arbitrary query strings are rejected. Saved-pin
checks, opaque image keys and byte/pixel limits apply to every kind. Decoding
preserves logo transparency and does not upscale small images. Metadata-only
refreshes retain keys and Qt's image cache. A changed manifest revision, removed
asset or PC switch retires old keys, including requests still in flight.
Artwork-only changes now propagate through the existing browsing refresh.

Parser/provider regressions and an actual-app loopback mTLS test cover these
boundaries, stable caching, revision refresh, removed/denied images and PC
isolation. Reviewed 1280×800 captures use synthetic fixture artwork and do not
extend physical Deck acceptance. To retain these views locally:

    xvfb-run -a -s '-screen 0 1280x800x24' python3 clients/deck/tests/deck_library_navigation_test.py build/deck/nova-deck --artwork --capture-dir build/deck-preview-artifacts

This cached-artwork presentation and polling-refresh slice is complete locally.
Desktop Artwork Studio search/select/reset is implemented in the later section
below. Logo transforms, Space-specific artwork, screenshots/trailers, persistent
offline caching and event/reconnect-driven invalidation remain open.

### Play Setup stream choices (local development)

The details Play action now opens Android's two-column **Play Setup** review:
what will happen on the selected PC, and what the player can change. The local
stream choices are 1280×800, 1280×720, 1920×1080 or 1920×1200; 30/60 fps plus
90 fps when the current display and PC permit it; and 10, 20, 30
or 40 Mbps. Audio follows the device preferences in System. Choices save immediately for this PC/game
on this device. Reset removes only that override and restores 1280×800, 60 fps,
20 Mbps. Opening or editing the review sends no host mutation.

Only Play starts a session. The native controller validates a complete numeric
configuration before resolving a target, snapshots it for the worker, and uses
the same dimensions/rate for host launch and stream setup. Bitrate reaches the
stream configuration. Settings cannot supply host IDs, app IDs, credentials or
URLs. Existing paired-library authority and cleanup remain in force. Review
keeps its original PC/game and disables Play if the library selection changes;
returning to details and reopening creates a fresh review.

Settings storage uses versioned hashes of structured PC/game IDs to avoid key
collisions. Corrupt/unsupported stored values fall back to defaults; failed
writes are reported and rolled back in the shared settings cache. Tests cover
validation, scoped persistence/reset, write failure, immutable launch values,
controller/pointer focus, picker Back, stale selection, start/cancel/return and
the actual mTLS library route through restart and 1280×800/960×600 captures.

    xvfb-run -a -s '-screen 0 1280x800x24' python3 clients/deck/tests/deck_library_navigation_test.py build/deck/nova-deck --play-setup --capture-dir build/deck-preview-artifacts

This completes the local per-game client stream-choice slice of P07/P13/P27.
Android's host/Space destination, automatic/custom display
planning, rates above 90 fps, encoder, tuning/presets,
Steam launch behavior and Every Game host scope still need implementation.
HDR and physical Deck stream acceptance remain open.

### Face-button layout (local development)

Play Setup now offers Android's **Match labels**, **Match positions**, and an
inherited **Device default** for each PC/game. Match labels sends A/B/X/Y by
their names. Match positions swaps A/B and X/Y for a Switch-style layout.
**System → Face buttons** sets the device default; an explicit game choice takes
precedence. The review shows the effective layout before Play. Reset returns
that game's stream choices to defaults and its buttons to device inheritance,
without changing another game's override or the device default.

The stream snapshots the effective choice before launch and applies it only to
outgoing gameplay packets. Nova navigation, D-pad, sticks, triggers and View/Menu
shortcut handling retain their existing behavior. Focus loss, disconnect and
stop still send neutral releases. Changing preferences cannot reinterpret a
held button during an active stream. Prior four-field stream-settings records
keep their resolution/rate/bitrate and inherit the device default.

Local settings, controller, native-session, production-QML and actual-app mTLS
regressions cover all face-button combinations, inheritance/override precedence,
legacy records, invalid/corrupt settings, failed writes, per-PC/game scope,
restart/reset, immutable input mapping, neutral guards, shortcut consumption
and cleanup. Reviewed fixture captures at 1280×800 and 960×600 retain one clear
focus target. This implements P08 on the current single-controller native path;
physical Deck input acceptance and full Android parity remain open.


### Per-game launch modes (local development)

Play Setup now offers **Host default** and the session modes currently allowed
by both the selected game and the PC's typed launch-mode catalog: Private Stream,
Host Virtual Display, Mirror Desktop, Desktop Takeover, Private Stream (GPU-native)
and Gamescope Stream. Headless Dongle remains a host-wide setting and is never a
per-game choice. The review names the selected mode and, when known, the host
default. Choices persist independently for each PC/game on this device.

An explicit choice uses only the GameStream `/launch?streamMode=` parameter;
Host default omits it. Neither editing nor launching writes the PC's global
settings. Before an explicit launch, pinned reads recheck capabilities, the typed
catalog, the game ID/app ID and its allowed modes, then saved pairing is rechecked.
Missing/malformed authority, withdrawn permission, a removed game, changed
identity or cancellation prevents the launch. Cancellation is checked between
requests and library pages; an active request retains the bounded network timeout.

Reopening Play Setup with valid authority retires a no-longer-allowed override,
shows a notice and saves Host default without changing other stream choices.
Restoring that mode does not resurrect the old override. A temporary catalog
outage preserves the saved choice and disables Play until verification succeeds
or the player explicitly chooses Host default. Standard hosts and older Polaris
hosts without a typed catalog expose Host default only. Android's legacy catalog
fallback is not implemented in this slice.

Parser, settings, protocol, native-session, real loopback mTLS and actual-app
regressions cover validation, migration, scoped persistence, mode withdrawal,
permission/identity changes, catalog outages, cancellation and default omission.
Fixture views cover controller/pointer navigation and 1280×800/960×600 layouts.

    xvfb-run -a -s '-screen 0 1280x800x24' python3 clients/deck/tests/deck_library_navigation_test.py build/deck/nova-deck --launch-modes --capture-dir build/deck-preview-artifacts

This completes the local per-game session launch-mode slice of P07/P27. Space
routing, Every Game host scope, legacy-host explicit choices, capability-driven
display planning and physical launch/display acceptance remain open.


### Capability-aware stream review (local development)

Play Setup now shows the effective resolution, frame rate, bitrate, the resolved **H.264 · SDR** or **HEVC · SDR** format
and the selected device audio layout before Play. The supported presets include 1920×1200 for a more
detailed 16:10 picture. Safe, visible Polaris `display_planner` recommendations
annotate matching presets, with a clear **Recommended by your PC** explanation.
Malformed, hidden, unsafe or unsupported recommendations are ignored. These are
advisory resolution hints, not display permission or physical panel acceptance;
the existing supported presets remain available. Choosing a resolution never
copies the planner's trailing FPS into the stream configuration.

The PC's advertised `capture.max_fps` limits the frame-rate picker. If a saved
60 fps preference meets a 30 fps PC limit, the review shows 30 fps and explains
the adjustment. Play sends that exact effective configuration; storage keeps the
saved 60 fps preference for a compatible PC configuration later. Missing legacy
metadata preserves the H.264/SDR baseline. Malformed capabilities, an advertised
codec set without a common supported codec, or no common frame rate disable Play with an actionable
message. A game's HDR badge never changes the selected stream format.

Before launch, Polaris capabilities are read again over the saved certificate
pin and pairing is rechecked. Changed, unreadable or unsupported capabilities
stop before any launch; cancellation prevents subsequent requests. The existing
GameStream serverinfo read also rejects malformed codec flags and explicit
codec sets without the reviewed H.264/HEVC 4:2:0 format, while preserving the legacy
zero/missing H.264 default.
This protects standard-host launches as well.

Parser/settings/session/protocol and real loopback mTLS regressions cover
malformed/withdrawn capabilities, cancellation, fresh pairing checks, immutable
applied dimensions/rate and saved preferences. Production-QML and actual-app
checks cover adjustment, codec refusal, recommendation refresh, PC/game scope,
restart and reviewed 1280×800/960×600 fixture captures.

    xvfb-run -a -s '-screen 0 1280x800x24' python3 clients/deck/tests/deck_library_navigation_test.py build/deck/nova-deck --stream-plan --capture-dir build/deck-preview-artifacts

This implements the current SDR stream review, not the full Android display
planner. Peak-mode enumeration, automatic display selection,
custom and higher resolutions, rates above 90 fps, Main10/HDR presentation and the Android
resolved-profile/encoder/tuning contracts remain open. No physical Deck
acceptance is added by these local checks.

### Current display rate and SDR90 (local development)

Play Setup now names the active window's reported display rate. A 90 fps choice
appears only when Qt reports at least 88 Hz (Android's 2 Hz tolerance) and the
PC explicitly advertises a capture ceiling of at least 90 fps. This uses the
current `QWindow`/`QScreen` refresh rate, follows screen and refresh changes, and
does not infer OLED hardware, enumerate peak modes or switch display modes.
Unknown/60 Hz displays and hosts without an explicit high-rate ceiling retain
the 30/60 fps baseline. H.264 and HEVC SDR are the implemented video formats.

A saved 90 fps preference resolves to the supported lower rate when needed.
The review explains why and preserves the preference for a compatible setup.
If a display or PC change withdraws a rate while its picker is open, the picker
closes back to the frame-rate row with visible focus. Native launch checks the
display again before capabilities and immediately before the game-start request,
including after a slow serverinfo read. A display change during an active stream
affects the next launch; it does not silently renegotiate or end the game.

Decoded frames now notify the GUI through a queued, bounded latest-frame handoff.
A pending notification consumes the newest frame instead of accumulating old
frames. The 16 ms timer remains for lifecycle/input handling, not video delivery.
Closing or replacing a session drops pending leases and makes late publishers
inert. This removes a software polling limit; it does not prove physical cadence.

Tests cover display thresholds, screen signals/removal, worker-safe lifetime,
host/display withdrawal during launch, immutable active configuration, frame
coalescing/leases/destruction races, and the production QML rate picker and
fallback at 1280×800/960×600. The 90 Hz UI fixture injects a synthetic reported
rate into the production display provider; it is not a physical 90 Hz capture.

    xvfb-run -a -s '-screen 0 1280x800x24' build/deck/nova_deck_native_preview_qml_test build/deck-preview-artifacts/display-90-qml

Installed LCD SDR60/OLED SDR90 frame pacing, audible audio/A/V sync and Main10/HDR90
presentation remain acceptance work. These local checks do not close P13/P14 or
admit a Deck release.

### Main10 color rendering groundwork (local development)

The native VAAPI decoder selects H.264, HEVC Main or HEVC Main10 from an exact Moonlight
format value. Decoded bit depth and 4:2:0 chroma must match the request; unsupported
formats and format masks are refused. Retained frame leases preserve the decoded
AVFrame's color tags and static HDR side data. The existing Qt OpenGL surface and
render node now reject 10-bit, PQ/HLG and BT.2020 frames before the fixed 8-bit SDR
shader can misinterpret them. The application negotiates H.264 or HEVC SDR only.

An opt-in libplacebo backend renders decoded frames into a caller-owned GPU texture.
It maps AVFrame colorimetry and static HDR10 metadata, handles planar 10-bit/P010,
and supports an explicit sRGB tone-mapped target or a 1000-nit BT.2020/PQ reference
target with at least 10-bit precision. The latter is an offscreen reference, not
a detected monitor capability. HLG, incomplete HDR colorimetry, 10-bit SDR labeled
as HDR10, low-precision HDR targets and unknown output encodings are refused.
Per-frame metadata is cleared between mappings, and GPU/frame resources have
bounded ownership: at most three mapped frames stay alive until nonblocking GPU
polls report completion, and teardown drains pending work. VAAPI import requests
direct DRM_PRIME mapping; combined layers and invalid plane/object bounds are
refused before libplacebo import. Physical zero-copy behavior remains unverified.

The optional build needs libplacebo 7.349 or newer, Vulkan and libavformat in
addition to the normal dependencies. The default app/packaging dependency set is
unchanged. The color backend now also has the separate Vulkan window described
below; it is not yet connected to the native streaming screen.

    cmake -S clients/deck -B build/deck -DNOVA_DECK_BUILD_HDR_VALIDATION=ON
    cmake --build build/deck -j6
    ctest --test-dir build/deck --output-on-failure -R 'nova_deck_(video_color|hdr_color)_test'

The color test encodes a real two-frame HEVC Main10/PQ sample, parses its first
Annex-B access unit and decodes it with FFmpeg. It checks the Main10 profile,
BT.2020/PQ tags, mastering metadata and content-light levels, then inspects Vulkan
pixel readbacks against the decoded YUV values. Adjacent 10-bit shades remain
distinct; planar and P010 packing agree; SDR tone mapping changes the transfer
without carrying old HDR metadata into later frames. This validation permits a
software Vulkan device and fails when no suitable device is available. The local
run explicitly selected Mesa lavapipe. Synthetic VAAPI leases test ownership and
SDR rejection, not hardware decoding or dmabuf import. Vulkan checks also verify
source-frame retention/release; malformed DRM layouts are rejected before the GPU
import helper.

Runtime VAAPI profile, entrypoint, surface-format and size probing is now wired
into the app; Main10 results describe decoding only. Next integration work remains:
HDR-aware stream negotiation and hardware validation of the opt-in Vulkan session path,
verified compositor/output color metadata and display support. Only then can
Play Setup offer HDR. Installed OLED HDR90, audio/pacing and release acceptance
remain open. Implementation references: [libplacebo frame rendering](https://libplacebo.org/renderer/)
and [Qt HDR swapchains](https://doc.qt.io/qt-6/qrhiswapchain.html).

### Vulkan presentation window (local development)

The same opt-in build now includes `DeckVulkanVideoWindow`, a real Qt native
window backed by a libplacebo Vulkan swapchain. It accepts retained decoded
frames, fits their aspect ratio with black bars and handles resize, hide,
surface destruction/recreation and GPU teardown. Hidden updates retain only the
latest frame. Submissions are reported separately from physical presentation.

The backend queries the actual surface's Vulkan formats before requesting HDR10.
SDR, prefer-HDR10 and require-HDR10 are internal backend policies, not player
settings yet. A preferred HDR source is tone-mapped when the acquired surface is
sRGB; a required HDR output is refused if unavailable. Rendering uses the acquired
surface's color representation, precision and metadata, never the requested hint
or the offscreen 1000-nit reference. Unknown output encodings, low-precision PQ
and ambiguous source HDR are refused. Color hints are replaced each frame, so
returning to SDR clears the previous HDR hint. Refusal and clear submit black
instead of leaving the previous picture visible.

The local window test uses Xvfb and software Vulkan. Captured window pixels are
compared with a floating-point color-rendering reference, with checks for black
bars, HDR refusal/recovery, SDR transition, resize, hidden frame bounds, three
surface recreations, invalid source rejection and buffer release. The existing
real HEVC Main10 test additionally checks acquired-target metadata, including a
500-nit target that must not be replaced with the 1000-nit reference.

    VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.x86_64.json \
      VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation \
      ctest --test-dir build/deck --output-on-failure \
      -R 'nova_deck_(video_color|hdr_color|vulkan_window)_test'

This backend remains outside the default application and Flatpak. The opt-in
streaming integration below reuses the existing controls. HDR negotiation and
installed OLED output/pacing acceptance remain open. An advertised surface
format is not proof of an HDR panel or its measured luminance. References:
[Qt Vulkan surfaces](https://doc.qt.io/qt-6/qvulkaninstance.html),
[libplacebo swapchain contract](https://github.com/haasn/libplacebo/blob/v7.360.1/src/include/libplacebo/swapchain.h),
and [Qt Quick rendering into an external engine](https://doc.qt.io/qt-6/qquickrendercontrol.html).

### Vulkan streaming controls (local development)

The application can now explicitly route the native session to the Vulkan window:

    cmake -S clients/deck -B build/deck -DNOVA_DECK_BUILD_VULKAN_STREAM=ON
    cmake --build build/deck --target nova-deck
    build/deck/nova-deck --standalone --experimental-vulkan-stream

Both the build option and launch flag are required. Ordinary launches retain the
OpenGL/EGL presenter; the normal Flatpak does not enable this option. The opt-in
route selects Vulkan for Qt Quick before creating any window. It rehosts the
existing `NativeStreamPreview.qml`, including Play Setup, Command Center, NovaHUD
and its nested controls, without a second UI implementation. Play waits for the
presentation surface. Closing returns to the library; a presentation failure
cleans up the session and restores visible error/Back controls.

`DeckVulkanQuickOverlay` renders Qt Quick into an RGBA texture on the same Vulkan
device. Explicit ownership synchronization surrounds Qt's offscreen frame, and
libplacebo composes the premultiplied sRGB overlay into the acquired output.
There is no CPU readback/upload of the overlay. Unchanged UI is now reused.
Texture ownership is checked with zero-timeout semaphore polls; changes and
resize coalesce while the renderer is busy. A dedicated render thread now owns
Qt frame completion, libplacebo rendering/presentation and GPU resource retirement.
QML items, input and polish stay on GUI. A cancellable handshake parks GUI only
for Qt scene synchronization, after the worker has finished acquisition and
beginFrame. One active render request and one replaceable latest frame bound the
mailbox; the existing three-frame GPU source pool remains bounded.

Closing the preview restores the library immediately and retires the native
surface asynchronously. Reopen waits for that retirement; generation checks
prevent old work from restoring readiness or counting cancelled video. Qt retains
its borrowed device wrapper across native-surface recreation. Final C++ object
destruction and an OS-forced surface destruction must still join the worker to
protect native lifetimes, and can wait for a stalled driver. This is not a
hard real-time guarantee or physical frame-pacing acceptance. Qt's complete
renderer is destroyed on its render thread before the shared Vulkan device.

Native keyboard/D-pad-key, pointer and touch events reach the redirected scene.
The existing controller chord and input ownership still belong to the native
session. A lifetime-guarded presentation sink receives the bounded latest-frame
handoff and exposes distinct successful video compositions to NovaHUD. Repainting
controls, resizing, or clearing the screen cannot inflate that video counter;
composition is still not a measured physical display flip.

Local Xvfb/lavapipe checks exercise the production popup's review/focus, choices,
Command Center confirmation, native touch/pointer, HUD over tone-mapped video,
repeated attach/detach, resize, clear and error return. Session regressions cover
busy replacement refusal, sink detachment/destruction and HUD counter ownership.
Real-app startup checks cover the explicit Vulkan route and default OpenGL/EGL.
The existing full production streaming-popup test remains in use.

The local pacing checks now hold a real Vulkan queue on a timeline semaphore.
They verify cached UI, responsive GUI timers during pending ownership, a held
texture never being sampled, coalesced resize, updated pixels, pixel-ratio
changes and timeout/recovery. The production popup test composes twelve new video
frames with zero additional Qt UI draws. A bounded three-frame source pool is
polled before swapchain acquisition; temporary pressure defers the latest frame
without submitting black or ending the session, and a one-second persistent stall
reports an error. Completed source mappings retire on a separate polling timer,
including after video is cleared, so idle screens do not retain them.

The source-pool stress scenario alone reports optional shader timing queries as
unavailable through a test-only Vulkan loader callback: this lavapipe version's
query path otherwise waits for the whole device. Rendering, queue synchronization
and texture-completion checks remain real. All other rendering checks use the
unaltered driver. This proves the application's pool/ownership behavior, not a
fully nonblocking renderer or physical pacing. A separate render-thread stress
test uses the unaltered driver and holds the actual queue during Qt completion
and subsequent libplacebo/presentation. Production-popup D-pad, touch and timers
remain responsive; eighty frame/resize replacements stay bounded, close returns
to the library before drain, and immediate reopen ignores cancelled completions.
It also exercises pending GUI-sync cancellation across forced surface recreation.
The build machine's read-only VAAPI query fails driver initialization, so hardware
import stays unverified.

This is an experimental **SDR streaming integration**. It negotiates only the
existing H.264/HEVC SDR choices; synthetic HDR video in the rendering tests does
not enable live Main10/HDR. Hardware VAAPI-to-Vulkan import, physical gamepad and
Game Mode behavior on this route, audible A/V sync, compositor/output metadata,
HDR negotiation and installed OLED HDR90 remain open. Nothing is installed on the
Deck by these checks. See the [Qt render control contract](https://doc.qt.io/qt-6/qquickrendercontrol.html)
and [offscreen-frame completion contract](https://doc.qt.io/qt-6/qrhi.html#endOffscreenFrame).

### Vulkan hardware import validation (local development)

The importer now admits only exact NV12 or P010 software layouts with matching
separate DRM layers. It checks plane/object bounds before libplacebo, and checks
DMA-BUF sampling plus each format/modifier before texture import. This prevents a
same-depth but three-plane or reversed-chroma context from reaching an asserting
helper. Production Vulkan setup refuses a device without DMA-BUF texture import
before enabling Play. Software fixtures retain their explicit test-only allowance.
These capability checks do not establish that a particular surface can be imported.

With `NOVA_DECK_BUILD_HDR_VALIDATION=ON`, build `nova_deck_vulkan_import_test`.
On a machine with an actual VAAPI decoder and hardware Vulkan device, run:

    build/deck/nova_deck_vulkan_import_test --require-hardware
    # Optional explicit decoder device:
    build/deck/nova_deck_vulkan_import_test --require-hardware --device /dev/dri/renderD128

Do not force the software Vulkan ICD for a hardware run. This headless test uses
retained synthetic HEVC Main/SDR and Main10/HDR10 bitstreams, requires real VAAPI
NV12/P010 output with no decoder fallback, and passes those frames through the
production direct-map renderer after closing the decoder. It compares all rendered
RGB pixels against separately decoded software references, retains static HDR10
metadata, repeats imports and verifies source retirement. Pixel error must remain
at most 0.02 per component and 0.002 on average. It checks NV12-to-sRGB,
P010-to-PQ and P010-to-sRGB; PQ is an offscreen reference, not an HDR monitor.
The new Main10 fixture is a two-frame 144×72 synthetic grayscale/color pattern
with PQ/BT.2020 NCL, 1000-nit mastering/MaxCLL and 400-nit MaxFALL metadata.

The tool emits a JSON result. Required-hardware mode fails on unavailable hardware.
The optional CTest hardware check exits 77 and is marked skipped when VAAPI cannot
open. `--reference` exercises software planar-versus-NV12/P010 rendering and the
comparison/retirement path; it always reports `hardware_import_verified: false`.
Conflicting reference/hardware options are refused. `hdr_display_verified` remains
false in every mode. A passed import check does not accept window presentation,
Game Mode, cadence, A/V sync or HDR90. The current build machine still cannot open
VAAPI, so its hardware result is unavailable; local software results are not
hardware evidence. Live HDR negotiation and player-facing HDR settings remain off.

The subsequent temporary Steam Deck probe passed on the actual AMD VAAPI/Vulkan
path: NV12-to-sRGB, P010-to-PQ and P010-to-sRGB pixel comparisons, static HDR10
metadata and repeated source retention/retirement. It used the same verified
production renderer sources, built against the Deck's existing KDE 6.10 runtime
with a bundled pinned libplacebo 7.360.1. Hardware mode required VAAPI output and
disallowed software Vulkan. The installed Nova binary/commit, configuration/data,
Steam shortcuts, running Nova/Steam processes and runtime commits were unchanged.
This is headless hardware-import evidence only: the probe opened no window and
launched no game. Native-window/Game Mode, real streaming/audio/input, cadence,
HDR negotiation and OLED output still need their own checks.

The subsequent temporary full-application preview also reaches the native Vulkan
Play Setup window in Steam Deck Game Mode. The KDE 6.10 build selects Vulkan on
the actual AMD GPU; the library/artwork and shared Qt Quick setup controls render
at 1280×800, Game Mode focuses the native window, and the import-readiness gate
enables Play without an error. Injected navigation checks window routing only.
The installed app is paused while the preview uses a private copy of its settings;
the preview watcher resumes it on exit. The installed binary, original saved
data, Steam process/shortcuts and runtimes remain unchanged. Live game streaming,
physical controls, A/V sync, close/reopen recovery, cadence and HDR display output
remain pending; this temporary startup check does not accept the installed build.

### Audio preferences (local development)

**System → Audio** now offers Stereo, 5.1 surround, 7.1 surround and **Play audio
on PC**. These match the frozen Android `list_audio_config` and
`checkbox_host_audio` preferences: device-wide scope, stereo/PC-audio-off defaults
and next-stream application. They save immediately, survive restart and apply
across PCs/games. Reset audio defaults changes only audio; per-game reset leaves
device audio intact. Failed writes retain the prior values and show an error.

Play Setup names the selected layout and PC-audio choice before Play. Surround
requires a compatible output; the menu does not detect or promise one. SteamOS
continues to control output routing. The existing Space worker path requests
stereo, matching Android, without overwriting the saved surround preference.

A new native stream snapshots audio preferences before starting its worker. The
host launch/resume query and Moonlight audio configuration derive their channel
layout from that same snapshot; PC playback uses the session's
`localAudioPlayMode`. These requests do not write host-wide settings. Manual,
automatic and sleep/wake recovery retain the original snapshot; a new Play uses
the current device settings. Opus decoding and PipeWire retain explicit speaker
positions for 2/6/8-channel output.

Local regressions cover preference validation, corruption, persistence, reset
scope, failed writes, applied launch/resume values, immutable recovery settings,
Space stereo and production touch/controller UI. Isolated PipeWire playback and
daemon-loss checks link every stereo/5.1/7.1 speaker position to a private null
sink. Real-packet decoder tests compare PCM with an independent Opus decoder.
Those checks do not prove audible output, surround speaker placement or A/V sync.

    xvfb-run -a -s '-screen 0 1280x800x24' python3 clients/deck/tests/deck_library_navigation_test.py build/deck/nova-deck --audio-settings --capture-dir build/deck-preview-artifacts/audio-settings

This closes the bounded local P15/P27 audio-preference implementation. Effects,
audible Deck/PC playback, physical surround and A/V sync through
suspend/reconnect remain open. Local output recovery is described below.

### Audio output recovery (local development)

An active native stream now keeps decoding through audio-service loss and
reopens the output on a separate worker with capped retry delays. An available
but paused graph waits for SteamOS to select/link an output. Incoming PCM is
dropped during the gap, and generation-tagged queued samples cannot cross an
observed pause/route boundary. Recovery retains the stream's original layout and
PC-audio request. Device work stays off the decode callback and GUI; teardown
cancels retries, retires queued audio and prevents late output activation.

A small in-game notice says when audio is reconnecting or waiting for an output.
It leaves controller focus with the game; the controls overlay also shows the
status. The notice clears when the graph starts processing again. Startup still
fails if the audio server cannot be opened initially. Nova follows system
routing without changing the default device or storing device names.

Private PipeWire tests exercise null-sink removal/relink and daemon
restart/recovery for stereo, 5.1 and 7.1, including stopping during another
outage. Portable tests cover nonblocking decode, stale-PCM retirement, immutable
format, counters, unstable reconnect backoff and cancellation during open/wait.
Production QML checks cover continued controls and 1280×800/960×600 notices.
See [the native audio contract](../../docs/deck-native-audio.md).

This bounded P15/P12 implementation is off the coding queue. Installed Deck
speakers/headphones, Bluetooth and dock/default-device changes, audible recovery,
physical surround and A/V sync still require physical acceptance. Null-sink
tests establish graph submission and lifecycle behavior only.

### Controller rumble (local development)

**System → Controller rumble** implements Android's `checkbox_enable_rumble`:
enabled by default, device-wide persistence and application to the next new
stream. Reset rumble default affects only that preference; audio, face-button
layout and per-game stream choices retain their scope. Failed writes retain the
previous value. Manual/automatic reconnect and wake resume retain the original
stream preference; changing it does not alter a running session.

The single-controller native GUI path now consumes GameStream two-motor rumble
for controller zero. A bounded latest-value mailbox bridges the transport
callback to the GUI, then a dedicated output worker. It accepts feedback only
while gameplay has focus, the controller is connected, controls are hidden and
held input has returned to neutral. Opening controls, losing focus, unplugging,
sleep, disconnect, interruption and teardown stop feedback. Returning to play
requires a fresh host command; old commands cannot cross a device/session change.

The output worker retains the exact joystick descriptor used for input and
resolves its sibling evdev node through kernel sysfs identity. It rechecks
device numbers, live joystick access and `FF_RUMBLE` support before accepting the
endpoint. One per-descriptor effect maps low/high frequency to strong/weak
motors, with a one-second lease renewed while requested. Stop/removal and close
retire that effect. Failed or unsupported feedback leaves ordinary controller
input working; replug/reopen rechecks availability. Global device gain and
system settings are unchanged. See the [Linux force-feedback interface](https://docs.kernel.org/input/ff.html).

Local tests cover matching identity, reused nodes, unsupported/denied devices,
motor values, lease renewal, failed upload/write, cleanup, coalescing during a
blocked open and late hotplug/destruction. Native callback tests cover overlay,
neutral gating, controller number, focus/unplug/sleep/disconnect/interruption,
fresh recovery commands and immutable preferences. Settings and actual-app
touch/D-pad tests cover persistence, reset, PC switching, errors, Back focus and
1280×800/960×600 screens. Hardware I/O is simulated in those tests.

This bounded P24/P27 implementation is off the coding queue. Built-in Deck and
external-controller rumble still need installed Desktop/Game Mode acceptance.
Multi-controller feedback, trigger motors, gyro, LEDs, audio haptics and device
vibration fallback remain open; the presence of the preference does not prove
hardware support.


### H.264 and HEVC codec choice (local development)

Play Setup now has a per-game **Video codec** choice: Auto, H.264 or HEVC.
Auto prefers 8-bit HEVC when the PC and local hardware decoder both support the
selected size; otherwise it uses H.264 and explains the choice. An explicit
unavailable codec disables Play with a reason. The review sends the exact
resolved codec while storage retains Auto. Existing preferences and reset keep
H.264. Spaces remain H.264; game HDR badges never enable HDR negotiation.

The startup VAAPI probe checks FFmpeg hardware decoding, profile/VLD entrypoint,
4:2:0 surface format and maximum dimensions separately for H.264 High, HEVC Main
and HEVC Main10. Missing evidence remains unsupported. The session worker probes
again before launch, and the decoder checks its owned VAAPI device during setup.
Polaris capture capabilities and standard GameStream serverinfo both supply codec
support. Fresh serverinfo must still offer the reviewed format before launch or
resume. The Moonlight setup callback refuses a different negotiated codec; no
Main10, AV1 or 4:4:4 format is advertised by this SDR path.

Tests exercise per-game persistence/reset, legacy records, Auto/forced selection,
size limits, Main10-only refusal, standard-host parsing, stale preflight and exact
Moonlight configuration. A retained two-frame HEVC Main fixture decodes through
FFmpeg with 8-bit 4:2:0 and limited-range BT.709 metadata. Production-QML tests
exercise touch and D-pad, focus return, capability withdrawal and enlarged text.
This is local software validation; physical VAAPI HEVC playback remains open.
Hardware Main10 probing does not establish HDR window/compositor/display support.

The fixture at `tests/fixtures/hevc-main-sdr.b64` is synthetic FFmpeg `testsrc2`
(128x72, two frames, 60 fps), encoded with libx265 Main/yuv420p, no B-frames,
repeat headers, keyint 60 and `colorprim=1:transfer=1:colormatrix=1`, then base64
encoded. Encoding two frames avoids the Main Still Picture profile. The fixture
contains no game content or private data.

UI smoke builds (`BUILD_TESTING=ON`) accept `--frontend-smoke-codecs` only with
`--frontend-smoke-library-state`. This supplies a synthetic review-only decoder
snapshot and disables the native session controller. It lets headless UI tests
exercise choices without a hardware GPU. The packaged build omits the switch;
production launch and decoder setup always use actual device evidence.

### Play Setup host plan, Artwork Studio and Steam game entries (local development)

Play Setup now saves Auto, Quality, High FPS and Stability per PC/game, alongside
available host encoder choices. The native worker checks capability again before
launch; resume keeps the existing session's launch settings. The left column
shows the host's validated deterministic plan with readable field provenance.
Explicit stream choices stay visible; this does not enable HDR. Steam Direct and
Big Picture are host game preferences with fresh permission checks and read-back.

Open **Artwork** from game details to search for a match, preview poster,
backdrop, logo and icon choices, and Apply them through the paired PC. Type opens
the touch keyboard. Discard keeps the existing artwork; Reset match requires a
second confirmation. A dropped save response requires a fresh review, and expired
selection tokens require another search. Desktop library artwork editing is the
implemented scope; logo placement transforms and Space edits remain separate.

**Add to Steam** creates or updates a per-game entry and copies its available
artwork. Close Steam in Desktop Mode while leaving Nova open, then choose Retry.
Nova refuses writes while Steam runs or its state cannot be checked. It preserves
unrelated shortcuts and the existing app ID through a game rename. The canonical
PC/game/destination link launches with saved Nova preferences; opening a settings picker cancels automatic start. Back from this dedicated flow returns to Steam.

Evidence and local build artifacts: `build/deck-setup-artwork/EVIDENCE.md`.
These controls have not replaced the running Deck preview or received physical
acceptance. Development and tests remain local.

### Custom stream profiles and PC resume timeout (local development)

Choose **Custom…** in Play Setup's Resolution, Frame rate or Bitrate picker. The
numeric form accepts even sizes from 320–4096 × 240–4096, whole-number 30–90 fps,
and 1–300 Mbps, including decimal Mbps. Save affects only that game's selected
field; Cancel leaves its preferences unchanged. Available host recommendations
include safe advanced sizes. Codec/decoder, host and current-display checks still
control whether the reviewed stream can start. Saving a larger profile does not
establish hardware or physical playback support.

**Every Game → Edit Nova stream defaults** edits the inherited profile on this
device across PCs. Existing game overrides stay in place, and resetting one game
choice uses these current defaults. Keep in step can copy the device profile to
the selected paired PC while Every Game is open. Supported Polaris profile imports
retain custom values exactly. Fractional rates and rates above 90 fps remain
unavailable.

**Every Game → Resume timeout** appears when Polaris advertises support. The
1/5/10/30-minute choices affect the PC for all paired devices; the form distinguishes
requested and effective values. A dropped save is not retried. Refresh reads back
the current setting before another explicit choice. Editing is unavailable during
an active host game or local stream. This is separate from ending a session.

Local validation, screenshots and bundle: `build/deck-display-settings/EVIDENCE.md`.
The running Deck preview has not been replaced by this local development slice.

### Logo placement (local development)

Open a game's **Artwork → Logo placement** to adjust its existing logo. Smaller,
Larger and direction buttons work with touch or D-pad. The preview supports
25–400% size and horizontal/vertical positioning; **Reset layout** returns the
draft to centered 100% size. **Save** updates game details and persists the layout;
**Cancel** or Back discards the draft. Save/Cancel stay visible at 130% text.

Placement belongs to this device and this PC/game. It starts from the host's
layout when no local choice exists. It does not change images on Polaris or in
Steam, and stream setting resets do not remove it. Saving selected images through
Artwork Studio remains a separate host action. Space image editing and installed
acceptance remain open. Evidence: `build/deck-logo-placement/EVIDENCE.md`.

### Space artwork (local development)

A Space game's **Artwork** button opens its Poster, Backdrop, Logo and Icon
previews. **Refresh art** asks Polaris to fetch missing Steam images while keeping
existing artwork. Partial downloads name the images still missing. If Polaris
does not confirm the refresh, **Check again** reads the current library before
another explicit refresh; it does not repeat the previous request.

**Logo placement** saves size and position on this device for that PC/Space/game.
Manual Space image search, replacement and reset need a Polaris API extension;
the current host only exposes those operations for Desktop games. The Space
view explains that limitation and offers the supported operations. Refresh is
unavailable for Steam Big Picture or when Space/game access changes.

Local evidence and screenshots: `build/deck-space-artwork/EVIDENCE.md`. This build
has not replaced the installed Deck preview, and full Space editing remains open.

### Doctor recovery and support reports

Doctor keeps a private, paired-host/client/game journal for reversible live fixes.
After returning to the same running game, choose Check result before Undo. Recover
change explicitly resends an unconfirmed original request with the same identity;
Finish Undo preserves an interrupted restore. Restarting never submits a change
by itself. Changed session scope or expired receipts cannot authorize recovery.
A storage error disables Doctor writes until the journal is available again.

The Report tab saves sanitized current readings to **Documents → Nova reports**.
Find and share the JSON from Desktop Mode. Nothing is uploaded; no pairing data,
addresses, session identities or raw logs are exported. Missing readings stay
unavailable. Reports are limited to 64 files; remove an older report if the folder
is full. Full Android crash/log sharing, host upload, Space Doctor actions and
next-launch trials are not included. Evidence: `build/deck-doctor-recovery/EVIDENCE.md`.

### Settings hub

Open **Settings** in the library header to browse Video & stream, Audio, Controls,
Appearance, In-game UI and Polaris Sync. Search matches setting names and related
terms across all categories; tap the field or press A to type with Nova's keyboard.
The category rail starts with focus. Right enters the settings, Left returns to
the rail, and Back returns from an editor to the same setting.

Rows show the saved value, scope and when it applies. Reset changes only that
device setting: resetting audio channels keeps PC audio playback, resetting the
theme keeps text size, and resetting a default keeps per-game overrides. Save
failures remain visible. Theme, HUD and library choices share the existing stores,
so the System and Command Center shortcuts see the same values.

Stream defaults and Polaris Sync reuse the existing paired-PC editor and its
authority/conflict checks. Browsing the hub does not request or write host
settings. The existing System quick actions remain available. Unsupported Android
preferences and settings import/export remain outside this local hub slice.
Active-stream Sync controls are described below. Evidence: `../../build/deck-settings-hub/EVIDENCE.md`.

### Polaris Sync

Open **System → Polaris Sync** from the library to compare Nova's device defaults
with this PC's paired profile and use the existing profile actions. Per-game
settings stay separate. The screen refreshes while open; automatic writes still
require Keep in step to be explicitly enabled.

During a game, **Command Center → Polaris Sync** compares saved profiles with the
encoder's current bitrate and offers **Match Nova**, **Send Nova** and **Clear
paired profile**. Sending saves Nova's device defaults to the paired profile:
resolution/frame rate apply next stream, while bitrate is requested now and turns
Live Tuning off. The screen confirms a saved profile separately from an applied
encoder bitrate. An uncertain response requires review; Nova does not resend it.
Clear affects the next stream and does not request a live bitrate change.

Live Tuning and Change live bitrate are also available here. Keep in step stays
paused during play, preserving its saved choice. PC-wide display topology and
resume timeout remain locked. Device defaults and per-game choices remain separate.
Back closes a nested editor first, then returns to the same Command Center row.
Fresh pairing, owned-session, profile and permission checks guard each explicit
write; the host API has no atomic profile revision check. Local evidence and the
compatible preview candidate: `../../build/deck-session-sync/EVIDENCE.md`.
Installed Deck and physical acceptance remain pending.


### Video scaling

Choose **Settings → Video & stream → Video scaling**, or open **Video scaling**
in Command Center during a game:

- **Fit** shows the whole picture, with black bars when its shape differs from
  the display. This is the default.
- **Fill** fills the display and keeps proportions by cropping the outer edges.
- **Stretch** fills the display by changing the picture's proportions.

A choice applies immediately to this device's streams and survives restart.
Menus and NovaHUD keep their size; the host's resolution and stream settings stay
the same. Moving focus or pressing Back leaves the saved choice unchanged.
**Reset to Fit** resets only scaling. Save errors stay visible so you can retry.

Local rendering, controller/touch, persistence and large-text evidence:
`../../build/deck-video-scaling/EVIDENCE.md`. Installed Deck and physical SDR/HDR
acceptance remain pending.


### Video frame pacing

Open **Settings → Video & stream → Video frame pacing**:

- **Prefer lowest latency** keeps the newest decoded frame and hands it to the
  renderer as soon as possible. This remains the default.
- **Balanced** spaces decoded-frame delivery at the stream's frame rate, with at
  most two pending frames. It adds a small buffer to absorb uneven arrivals.
  Overdue frames are discarded after a stall instead of replaying a backlog.

This device-wide choice applies to the next new stream. Reconnect and wake keep
the current stream's original choice. Reset affects only pacing; host resolution,
frame rate, bitrate, scaling and per-game settings stay the same. SteamOS and the
renderer still determine final display timing. Warp, FPS-limit and smoothest-video
modes remain unavailable on Deck.

Local evidence: `../../build/deck-frame-pacing/EVIDENCE.md`. This implements two
pacing choices; it does not establish physical frame-pacing, A/V-sync or HDR
acceptance on a Deck display.


### Stick deadzone

Open **Settings → Controls → Stick deadzone**. Use the slider or 1% buttons to
choose −20% through +20%, then **Save**. Positive values ignore small movements
near the center; negative values boost small movements and may amplify drift.
The Android default is 5%. At zero or below, a tiny 1% center floor remains.
Both sticks use a radial cutoff, and full stick travel remains available.

**Cancel** discards the draft. **Default 5%** changes the draft until you save;
**Reset** on the Settings row restores only this preference. The device-wide
choice applies to all controllers on the next new stream. Reconnect and wake
keep the stream's original choice. Steam Input and the game may apply their own
deadzones too; Nova does not change those settings.

Controller joining and gameplay use the same neutral test. Start, focus loss,
overlays and unplug still release input and require held controls to return to
neutral. The slider does not adjust D-pad navigation, buttons, triggers, rumble
or per-game choices. Local evidence: `../../build/deck-deadzones/EVIDENCE.md`.
Physical Deck/external-controller acceptance remains pending.


### Mouse and keyboard forwarding

The active native stream now forwards physical keyboard keys and direct mouse
pointer movement, five mouse buttons, and vertical/horizontal wheel scrolling.
Close Command Center to send input to the PC. **Ctrl + Alt + Shift + M** opens
Command Center; plain **Escape** goes to the game. The Deck View/Menu shortcut
and touch access remain available. Nova's HUD and Command Center clicks remain
local, including drags that leave their bounds.

This first mouse mode follows the displayed picture. Fit excludes letterbox
bars; Fill maps into the cropped source, and Stretch follows the full image.
Non-square pixel aspect is included. This is suitable for desktop applications
and pointer-driven menus; **relative mouse capture for aiming is still open**.
Touchscreen/trackpad gestures, pixel-only scrolling, IME/text composition and
non-US text-entry preferences are not included in this slice. Physical Linux
keyboard scancodes use US game-key positions; native left/right modifiers and
keypad digits remain distinct, with logical Qt fallback for injected events.

Input shares the session worker and cannot outlive its connection. Opening
menus, losing focus, hiding/switching the presentation window, sleeping,
disconnecting and stopping release held keys/buttons. Menu presses and Qt
repeat pairs are not replayed after Resume. Only adjacent pointer motion is
coalesced; key/button/scroll order is retained. Queue overflow or transport failure
stops the stream with an actionable message and preserves an ordinary host game.

The real library or Vulkan presentation window owns forwarding, preventing the
redirected Quick overlay from sending an event twice. Existing controller routing,
permissions, pairing and stream settings are preserved. Local evidence:
`../../build/deck-desktop-input/EVIDENCE.md`. Installed mouse/keyboard, Steam Input
and device-removal behavior still require physical acceptance.
