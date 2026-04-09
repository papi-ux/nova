# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What is Nova

Nova is a Polaris-aware Android game streaming client, forked from Artemis (ClassicOldSong's Moonlight Android fork). It speaks the Moonlight/Sunshine wire protocol via the `moonlight-common-c` native submodule and adds a modern UI + Polaris server integration on top.

**Target devices:** Retroid Pocket 6 (landscape handheld, primary), Pixel 10.
**Server:** Polaris (custom Apollo/Sunshine fork) at `~/Documents/github/polaris/`.

## Build

```bash
git submodule update --init --recursive   # moonlight-common-c + enet
./gradlew assembleNonRoot_gameRelease     # release APK (arm64, x86, x86_64, armeabi-v7a)
./gradlew assembleNonRoot_gameDebug       # debug APK (applicationId: com.papi.nova.debug)
```

Release APK applicationId is `com.papi.nova`. Debug is `com.papi.nova.debug`.

Requires JDK 17, NDK 27.0.12077973, Android SDK with compileSdk 36.

## Tests

```bash
./gradlew :app:testNonRoot_gameDebugUnitTest   # run unit tests (Robolectric)
./gradlew :app:testDebugUnitTest               # all flavors
```

Tests live in `app/src/test/java/com/limelight/`. Robolectric config in `robolectric.properties` (custom shadow: `ShadowBackdropFrameRenderer`).

## Architecture: Two Package Namespaces

The codebase has two distinct layers:

### `com.papi.nova.*` — Inherited Moonlight/Artemis (Java, do not restructure)
The upstream streaming client core, now under the Nova package namespace. Touch this only for targeted modifications (e.g., adding TOFU pairing logic to `PcView.java`). Do not refactor or reorganize — it's battle-tested.

- **Activities:** `PcView` (server list + pairing), `AppView` (game grid), `Game` (active stream), `GameMenu` (overlay)
- **Protocol:** `nvstream/http/NvHTTP.java` (HTTP API to server), `nvstream/http/PairingManager.java` (challenge-response pairing), `nvstream/jni/MoonBridge.java` (JNI bridge to native core)
- **Bindings:** `binding/video/` (MediaCodec decoders), `binding/audio/` (AudioTrack renderer), `binding/input/` (controller/mouse/keyboard), `binding/crypto/` (key management)
- **Discovery:** `computers/ComputerManagerService.java` (mDNS + polling), `discovery/` (mDNS via jmdns/NsdManager)

### `com.papi.nova.*` — Nova additions (Kotlin)
All new Polaris-specific code goes here.

- **`api/`** — `PolarisApiClient` (REST), `PolarisEventSource` (SSE), `PolarisCapabilities`, `PolarisSessionStatus`, `PolarisGame`
- **`manager/`** — `FeatureFlagManager`, `ConnectionResilienceManager`
- **`ui/`** — `NovaThemeManager`, `SpaceParticleView`, `NovaStreamHud`, `NovaQuickMenu`, `NovaLibraryActivity`, `NovaGameAdapter`, `NovaWelcomeActivity`, `NovaQrScanActivity`, `NovaSnackbar`, `ReconnectOverlay`, `SessionProgressOverlay`, `LockScreenOverlay`
- **`service/`** — `NovaQsTile` (Quick Settings), `NovaStreamNotification`
- **`jni/`** — `PolarisNativeHook` (future native extensions)

## Native Layer (JNI)

`app/src/main/jni/moonlight-core/` — NDK build via `Android.mk`. Contains:
- `moonlight-common-c` submodule (streaming protocol, enet transport, reed-solomon FEC)
- Pre-built `libopus` and `openssl` per ABI (arm64-v8a, armeabi-v7a, x86, x86_64)

JNI methods are in `com.papi.nova.nvstream.jni.MoonBridge` — native function signatures encode the Java package name (`Java_com_papi_nova_*` in C code).

## Build Flavors

- **`nonRoot_game`** — Standard build (applicationId `com.papi.nova`). This is what you want.
- **`root`** — Legacy rooted device support (maxSdk 25). Ignore.

## Key Integration Points

- **Pairing:** `PcView.java:doPair()` handles TOFU (trusted subnet auto-pair with PIN "0000"), OTP, QR scan, and manual PIN flows. Server-side TOFU requires `trusted_subnets` in Polaris config.
- **Polaris API:** Nova Kotlin code calls `PolarisApiClient` at `https://<server>:47984/polaris/v1/` using mutual TLS with Moonlight pairing certs.
- **Themes:** `NovaThemeManager` — Polaris (Space Whale navy, default) and OLED Dark Galaxy (black). Colors defined in `res/values/colors.xml`.
- **XML parsing:** Server responses are XML (not JSON). Use `NvHTTP.getXmlString()` which calls `verifyResponseStatus()` — any non-200 `status_code` attribute throws `HostHttpResponseException`.

## CI

GitHub Actions (`.github/workflows/build.yml`) builds `assembleNonRoot_gameRelease` and signs the arm64 APK. **Note:** workflow triggers on `moonlight-noir` branch — needs updating to `master`.

## Pending Work

- Package rename: `com.limelight` → `com.papi.nova` — **COMPLETE** (Java, JNI, manifest, ProGuard, tests all updated)
- QR code scanner wiring (ZXing dependency already added, `NovaQrScanActivity` exists)
