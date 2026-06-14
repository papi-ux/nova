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

## Shared Polaris DTO boundary

Native C++ cannot include Kotlin source directly. For this first slice, fixtures/sample_polaris_game.json is a generated/shared-contract sample using the same snake_case keys covered by the Kotlin shared DTO tests. src/polaris_game_fixture.h and src/polaris_game_fixture.cpp load that fixture into a tiny native projection so the Deck shell can exercise a real library-card shape while the actual native Polaris API/client bridge is still future work.

Keep this boundary explicit until the shared contract is exported through a real native-consumable API. Do not fake Kotlin/C++ interop by including .kt files.

## Fedora or SteamOS dependency notes

The fallback smoke only needs CMake and a C++20 compiler.

For the Qt shell on Fedora, install the Qt 6 development packages if CMake warns that Qt6 Quick or QuickControls2 is missing:

    sudo dnf install cmake gcc-c++ qt6-qtbase-devel qt6-qtdeclarative-devel

On Fedora, qt6-qtdeclarative-devel provides cmake(Qt6QuickControls2). SteamOS package names may differ; the required CMake components are Qt6 Core, Qt6 Gui, Qt6 Qml, Qt6 Quick, and Qt6 QuickControls2.

Primary design reference:

- ../../docs/steam_deck_native_port_study.md

Guardrails:

- do not copy the Android UI framework into this client
- preserve Nova product behavior where it matters
- keep Deck-specific input, presentation, and lifecycle handling native to Linux and SteamOS
