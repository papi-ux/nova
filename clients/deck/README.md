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
