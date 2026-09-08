# Nova Deck Client

This directory is the first native Steam Deck client slice for Nova. It is intentionally a scaffold, not the streamer port.

Current status:

- CMake builds a small native core library on Linux/SteamOS-capable development hosts.
- Qt 6/QML shell builds when Qt Quick and QuickControls2 development packages are installed.
- Fallback build path keeps the core/controller/library smoke runnable without Qt.
- The shell consumes a generated sample Polaris game fixture shaped after shared/polaris/model/src/commonMain/kotlin/com/papi/nova/shared/polaris/model/PolarisGame.kt.

## Current preview smoke scope

This slice is a preview-only Deck smoke shell. It validates the native window, 1280x800 controller-first layout, fake host list states, an inert launch preview, local clipboard copy feedback, and Steam Input primary-action routing for the copy-preview flow.

It intentionally does **not** validate or perform backend launch, Moonlight streaming, host discovery, pairing, HostStore persistence, network calls, shell/process execution, or real game launch behavior. Keep that boundary visible until the next vertical slice wires a real read-only data source or typed launch-intent contract.

Planned role:

- first non-Android Nova client
- native Linux and SteamOS implementation
- controller-first fullscreen handheld UX
- built on top of the shared Nova backend layers rather than the Android shell

## Runnable smoke paths

Fallback native core and controller/library placeholder, no Qt required:

    cmake -S clients/deck -B build/deck-smoke-core -DNOVA_DECK_BUILD_QT_SHELL=OFF
    cmake --build build/deck-smoke-core
    ctest --test-dir build/deck-smoke-core --output-on-failure

Full Qt shell smoke, when Qt deps are present:

    cmake -S clients/deck -B build/deck-smoke-qt
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

`src/runtime/deck_moonlight_launcher.*` is the only place in the client that starts another program. It finds Moonlight as a native binary on PATH, as the `com.moonlight_stream.Moonlight` Flatpak, or through `NOVA_DECK_MOONLIGHT_BIN` for tests; inside a Flatpak sandbox it prefixes `flatpak-spawn --host`. Every argv token is checked to be plain (no control characters; shell characters are fine because no shell is involved, and game titles carry ampersands), the child is started without a shell, and its output is kept only as a bounded backend-only tail that never reaches the shell.

Headless proof, with a real Moonlight or a recorder script:

    NOVA_DECK_MOONLIGHT_BIN=/path/to/recorder QT_QPA_PLATFORM=offscreen nova-deck --live --handoff-game "Desktop" --handoff-wait-ms 5000

It prints the launch phase, how Moonlight ended, and a bounded tail of Moonlight's own output (backend-only: it can carry host addresses, so the shell never shows it). Exit codes: 0 launched and exited, 3 never launched, 4 still running when the wait ran out. Add `--handoff-windowed` to keep a real Moonlight in a window, for a proof on a desktop rather than a Deck.

## Game Mode and packaging

`packaging/flatpak/` builds the shell as the `com.papi_ux.Nova` Flatpak on `org.kde.Platform` 6.10, the runtime Moonlight-Qt already installs on a Steam Deck; see its README for the build, install and permission notes. `nova-deck --register-steam-shortcut` adds Nova to Steam as a non-Steam game so Game Mode can launch it: `src/runtime/deck_steam_shortcuts.*` parses and rewrites Steam's binary `shortcuts.vdf` byte for byte, registers or replaces one "Nova" entry, writes atomically, and refuses while Steam runs because Steam rewrites that file on exit. Inside the Flatpak the entry runs `flatpak run com.papi_ux.Nova --live`. Fixtures are found at runtime under `/app/share/nova-deck/fixtures` or next to the installed binary before the source tree is tried, and moonlight-common-c is linked statically so an installed binary carries it.

## Shared Polaris DTO boundary

Native C++ cannot include Kotlin source directly. For this first slice, fixtures/sample_polaris_game.json is a generated/shared-contract sample using the same snake_case keys covered by the Kotlin shared DTO tests. src/polaris_game_fixture.h and src/polaris_game_fixture.cpp load that fixture into a tiny native projection so the Deck shell can exercise a real library-card shape while the actual native Polaris API/client bridge is still future work.

Keep this boundary explicit until the shared contract is exported through a real native-consumable API. Do not fake Kotlin/C++ interop by including .kt files.

## Stream core skeleton boundary

clients/deck/src/stream/deck_stream_core.h is the first no-network native stream-core seam for the direct moonlight-common-c path. The CMake target links the real app/src/main/jni/moonlight-core/moonlight-common-c tree and the focused CTest includes Limelight.h, initializes STREAM_CONFIGURATION plus listener/video/audio callback structs, and verifies that the Deck lifecycle can move through idle, preparing, starting, active, stopping, stopped, cancelled, and failed states without opening sockets or calling LiStartConnection.

The skeleton intentionally exposes adapter seams for renderer/presentation, audio, input, and session events, but ships only inert Linux-facing interfaces. Next backend work should add a hardware-backed Linux renderer/audio/input spike behind those seams while keeping host pairing, credentials, and real network start disabled until the lifecycle contract is reviewed.

## Fedora or SteamOS dependency notes

The fallback smoke needs CMake, C/C++ compilers, OpenSSL crypto development headers, and the checked-out moonlight-common-c submodule.

For the Qt shell on Fedora, install the Qt 6 development packages if CMake warns that Qt6 Quick or QuickControls2 is missing:

    sudo dnf install cmake gcc-c++ qt6-qtbase-devel qt6-qtdeclarative-devel

On Fedora, qt6-qtdeclarative-devel provides cmake(Qt6QuickControls2). SteamOS package names may differ; the required CMake components are Qt6 Core, Qt6 Gui, Qt6 Qml, Qt6 Quick, and Qt6 QuickControls2.

Primary design reference:

- ../../docs/steam_deck_native_port_study.md

Guardrails:

- do not copy the Android UI framework into this client
- preserve Nova product behavior where it matters
- keep Deck-specific input, presentation, and lifecycle handling native to Linux and SteamOS
