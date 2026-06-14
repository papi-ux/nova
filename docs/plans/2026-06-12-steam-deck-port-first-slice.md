# Steam Deck Port First Slice Plan

**Goal:** start Nova's native Steam Deck port with a buildable client scaffold, a strict platform boundary, and a small shared-core extraction path that does not destabilize Android.

**Architecture:** Nova should not try to ship the Android APK on SteamOS. The Deck client lives under `clients/deck/`, uses a native Linux shell, and consumes shared models/contracts as they are extracted from the Android reference implementation. Android remains the shipping client while Deck work proves one vertical slice at a time.

**Tech Stack:** CMake, C++20, Qt 6/QML for the experimental Deck shell, existing `moonlight-common-c` as the streaming-protocol anchor, and focused JVM/Android tests for extracted shared contracts.

---

## Current repo facts

- `docs/steam_deck_native_port_study.md` already recommends a native SteamOS client in `clients/deck/` instead of direct Android porting.
- `docs/multi_platform_monorepo.md` defines `clients/deck/`, `clients/ios/`, `shared/models/`, `shared/polaris/`, and `shared/stream-core/` as the long-term repo shape.
- `shared/` currently contains intent docs only. It is not a buildable shared module yet.
- Android Polaris contracts currently live in `app/src/main/java/com/papi/nova/api/` and are the first candidates for shared extraction.

## Non-goals for this slice

- No public release promise for Steam Deck yet.
- No Android feature parity claim.
- No Waydroid/APK-on-Deck path as the product plan.
- No invasive refactor of Android streaming code before a Deck-native shell and backend spike prove direction.

## Task 1: Add native Deck scaffold

**Objective:** create the first buildable `clients/deck/` project without touching Android behavior.

**Files:**
- Create: `clients/deck/CMakeLists.txt`
- Create: `clients/deck/README.md`
- Create: `clients/deck/src/deck_layout.h`
- Create: `clients/deck/src/deck_layout.cpp`
- Create: `clients/deck/tests/deck_layout_test.cpp`

**Steps:**
1. Add a CMake project that builds a tiny `nova_deck_core` library.
2. Add a focused test for Deck defaults: 1280x800, fullscreen-preferred, `Nova Deck` shell name, and 16:10 aspect detection.
3. Watch the test fail before implementing the core.
4. Implement the minimal core constants and aspect helper.
5. Build and run the CTest target on Linux.

**Gate:**

```bash
cmake -S clients/deck -B build/deck -DNOVA_DECK_BUILD_QT_SHELL=OFF
cmake --build build/deck
ctest --test-dir build/deck --output-on-failure
```

## Task 2: Add minimal Qt/QML shell proof

**Objective:** prove the native shell can boot as a Deck-shaped fullscreen-oriented app.

**Files:**
- Modify: `clients/deck/CMakeLists.txt`
- Create: `clients/deck/src/main.cpp`
- Create: `clients/deck/qml/Main.qml`

**Steps:**
1. Add an optional Qt 6 Quick executable named `nova-deck`.
2. Bind the C++ Deck defaults into the window size/title.
3. Render a simple controller-first placeholder: title, status, and first-actions column.
4. Keep Qt optional so CI or contributor machines without Qt can still build `nova_deck_core` and tests.
5. Build on Linux with Qt installed.

**Gate:**

```bash
cmake -S clients/deck -B build/deck
cmake --build build/deck
ctest --test-dir build/deck --output-on-failure
```

## Task 3: Extract first Polaris contract candidate

**Objective:** identify and extract one small pure contract from Android into shared code without changing runtime behavior.

**Files:**
- Candidate source: `app/src/main/java/com/papi/nova/api/PolarisGame.kt`
- Candidate shared target: `shared/polaris/` or a future Gradle module under that path
- Candidate tests: existing `app/src/test/java/com/papi/nova/...` plus new shared tests once the module exists

**Steps:**
1. Start with a pure behavior such as Steam launch mode normalization or launch-mode availability.
2. Write a failing test around the shared API shape.
3. Move only the pure model/helper logic. Do not move Android context, bitmap, OkHttp, certificate store, or UI references.
4. Keep Android importing the extracted contract so Android behavior stays stable.
5. Run focused Android unit tests and the shared test.

**Gate:**

```bash
./gradlew -PnovaAbis=x86_64 :app:testNonRoot_gameDebugUnitTest
```

## Task 4: Choose Deck streaming backend path with evidence

**Objective:** answer whether to build directly on `moonlight-common-c` or adapt an existing Linux Moonlight client before investing in UI polish.

**Files:**
- Update: `docs/steam_deck_native_port_study.md`
- Optional create: `clients/deck/spikes/streaming-backend-notes.md`

**Steps:**
1. Spike direct `moonlight-common-c` integration feasibility for Linux decode/audio/input handoff.
2. Spike existing Linux Moonlight client adaptation cost.
3. Record build dependencies, code ownership risks, latency/control tradeoffs, and how each path preserves Nova's Polaris-aware UX.
4. Pick the backend for the next vertical slice.

**Gate:** written decision with one recommended path and rejected alternatives.

## Task 5: First real Deck vertical slice

**Objective:** make the Deck shell do one meaningful Nova thing before building a giant beautiful empty canoe.

**Scope:** manual host add or mocked local host list first, then Polaris capabilities/library probe once pairing material is available.

**Steps:**
1. Add a Deck `HostDiscovery`/`HostStore` boundary.
2. Add a fake/in-memory host provider for UI and test work.
3. Render a controller-first host list at 1280x800.
4. Add a no-network test path so layout/control work can run on CI.
5. Only then wire live LAN discovery or pairing.

**Gate:** Deck app opens to a host list or explicit empty state with controller-friendly actions.

## Verification discipline

- Android must remain buildable after every shared extraction.
- Deck core must keep a no-Qt test path for fast verification.
- Linux shell verification should run on an actual Linux host, not only macOS.
- Steam Deck hardware smoke is required before calling anything more than a scaffold/spike.
