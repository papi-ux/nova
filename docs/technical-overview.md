# Nova Technical Overview

Nova is an Android client with a Moonlight-compatible streaming core and a Kotlin layer for Nova-specific product behavior. The README stays focused on what users can do; this page keeps the source layout, build commands, and architecture details in one place.

## Architecture

```mermaid
block-beta
  columns 1

  block:kotlin["com.papi.nova - Kotlin"]:1
    columns 4
    api["api/\nREST + SSE"]
    manager["manager/\nRuntime tasks\nPolaris startup"]
    ui["ui/\nHUD, Themes\nLibrary, Menu"]
    preferences["preferences/\nSettings\nProfiles"]
  end

  block:java["com.papi.nova - Java (Moonlight core)"]:1
    columns 5
    PcView["PcView\nServers"]
    AppView["AppView\nGames"]
    Game["Game\nStream"]
    nvstream["nvstream/\nProtocol"]
    binding["binding/\nDecode + input"]
  end

  block:native["moonlight-common-c - C / NDK"]:1
    columns 3
    enet["enet\nTransport"]
    fec["Reed-Solomon\nFEC"]
    opus["Opus\nAudio"]
  end

  kotlin --> java --> native

  style kotlin fill:#7c73ff22,stroke:#7c73ff,color:#d4dde8
  style java fill:#4c526522,stroke:#687b81,color:#a8b0b8
  style native fill:#1a1a2e,stroke:#4c5265,color:#687b81
```

All new Nova-specific behavior lives in the Kotlin layer where practical. The Java core stays close to Moonlight and is changed surgically.

## Source Layout

| Path | Purpose |
|---|---|
| `app/` | Shipping Android client |
| `app/src/main/java/com/papi/nova/api/` | Polaris REST and SSE integration |
| `app/src/main/java/com/papi/nova/manager/` | Runtime helpers and Polaris startup coordination |
| `app/src/main/java/com/papi/nova/preferences/` | Kotlin settings model, repository, profile override, and Compose UI |
| `app/src/main/java/com/papi/nova/ui/` | Nova Library, quick menu, HUD, themes, and Compose surfaces |
| `app/src/main/jni/moonlight-core/` | Moonlight native streaming submodule |
| `baselineprofile/` | Baseline Profile generation for release performance coverage |
| `docs/` | Packaging, performance, and technical notes |

Android is the only public release target today.

## Build Requirements

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK | compileSdk 36 |
| Android NDK | 27.0.12077973 |
| Git | with submodule support |

## Clone

```bash
git clone --recursive https://github.com/papi-ux/nova.git
cd nova
```

If the repo was cloned without submodules:

```bash
git submodule update --init --recursive
```

## Build

```bash
# Release APKs
./gradlew assembleNonRoot_gameRelease

# Debug APKs, installed as com.papi.nova.debug
./gradlew assembleNonRoot_gameDebug
```

By default, local source builds produce split APKs for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

To override the ABI set locally:

```bash
./gradlew assembleNonRoot_gameDebug -PnovaAbis=arm64-v8a,armeabi-v7a,x86,x86_64
```

## Install A Local Build

Use the ABI-specific APK that matches your device from `app/build/outputs/apk/nonRoot_game/<buildType>/`.

Example for a real ARM64 device:

```bash
adb install -r app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk
```

## Build Flavors

| Flavor | Package | Notes |
|---|---|---|
| `nonRoot_game` | `com.papi.nova` | Standard release build |
| `nonRoot_gameDebug` | `com.papi.nova.debug` | Debug build, installs alongside release |

Official GitHub releases ship signed APKs for ARM64 devices, 32-bit ARM Android TV devices such as Chromecast with Google TV and Google TV Streamer, and x86_64 devices.

## Tests

```bash
./gradlew :app:testNonRoot_gameDebugUnitTest
```

For release readiness, pair focused unit coverage with a release assemble:

```bash
./gradlew :app:testNonRoot_gameDebugUnitTest :app:assembleNonRoot_gameRelease
```

## Release Performance Notes

Nova includes Baseline Profile generation infrastructure for startup and library flows. Release-performance evidence and current limitations are tracked in [Video Baseline Evidence](video_baseline_evidence.md).
