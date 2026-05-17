# Kotlin Optimization Audit

Date: 2026-05-16
Branch: `nova/kotlin-optimization-audit`

This audit starts the Kotlin-era optimization work with measured follow-up PRs. It intentionally does not change stream, input, video, Polaris resume, or disconnect behavior.

## Scope

- Streaming lifecycle and background work in `app/src/main/java/com/papi/nova/Game.kt`.
- Video decode and frame pacing in `app/src/main/java/com/papi/nova/binding/video/MediaCodecDecoderRenderer.kt`.
- Controller input, sensors, rumble, and mouse emulation in `app/src/main/java/com/papi/nova/binding/input/ControllerHandler.kt`.
- Library and HUD UI in `app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt`, `NovaQuickMenu.kt`, and `NovaStreamHud.kt`.
- Gradle and dependency graph setup in `.github/workflows/dependency-submission.yml`, `build.gradle`, `app/build.gradle`, and `gradle.properties`.

## Baseline Evidence

Static scan highlights:

| Area | Current evidence | Risk |
| --- | --- | --- |
| `Game.kt` | 6000 lines, 986 non-null assertions, ad hoc `Thread` use in stream shutdown/reporting and bitrate adjustment, many lifecycle callbacks mutating shared stream state. | High blast radius for ANRs, null crashes, and teardown races. |
| `MediaCodecDecoderRenderer.kt` | 2027 lines, 77 non-null assertions, a raw renderer thread, a choreographer `HandlerThread`, blocking queue use, watchdog flush/recovery, and dense frame-drop policy. | High performance impact; behavior changes need device evidence. |
| `ControllerHandler.kt` | 2864 lines, a dedicated `HandlerThread`, delayed battery/sensor jobs, main-thread mouse emulation loop, and one `Thread.sleep()` path in button-up handling. | Medium-high input latency and teardown risk. |
| Library/HUD UI | Compose library screen maps UI state in composition, uses `AndroidView` image loading for covers, and updates HUD through a mutable state holder. | Medium jank risk; easier to measure and isolate than stream runtime. |
| Gradle/build | Configuration cache and build cache are enabled, while root dynamic task wiring, global resolution rules, and a disabled packaged-manifest cache remain. | Medium build-time opportunity; low runtime risk. |

Dependency graph state:

- The checked-in workflow `.github/workflows/dependency-submission.yml` is still Nova's source of truth. It runs on `master` push and `workflow_dispatch`, grants `contents: write`, and uses `gradle/actions/dependency-submission@v6`.
- The GitHub Settings toggle for managed automatic dependency submission is disabled. The latest `master` Actions history shows the checked-in `Dependency Submission` workflow for each SHA and no dynamic `Automatic Dependency Submission (Gradle)` run.

## Ranked Candidates

### 1. Stream Lifecycle And Runtime Task Ownership

Files: `Game.kt`, `NovaRuntimeTasks.kt`, `NovaStreamKeepAlive.kt`

Why first:

- `Game.kt` owns connection state, decoder lifetime, input capture, HUD, Polaris status polling, background resume windows, and shutdown reporting.
- It already has `NovaRuntimeTasks`, but still starts raw `Thread` instances for session reporting, host bitrate adjustment, and connection stop/quit behavior.
- Stream state is spread across nullable fields and flags such as `connecting`, `connected`, `isStreamActive`, `attemptedConnection`, and `surfaceCreated`.

Recommended PR slice:

1. Route Nova-owned background work in `Game.kt` through `NovaRuntimeTasks` where it is lifecycle-bound and cancellable.
2. Convert the smallest high-risk null assertion clusters to guarded local values in teardown/reporting paths.
3. Add tests around task cancellation and duplicate launch prevention before changing broader stream state.

Validation:

- `./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest --console=plain`
- Focused tests: `GameRuntimeTaskLifecycleTest`, `BackgroundResumePolicyTest`, and a new cancellation/teardown test if raw threads are removed.
- Device smoke required before merging any change that touches connect, disconnect, resume, or input grab state.

Do not include:

- Polaris resume/disconnect behavior changes.
- Decoder policy changes.
- Large formatting-only cleanup of `Game.kt`.

### 2. Video Decode And Frame Pacing Measurement Harness

Files: `MediaCodecDecoderRenderer.kt`, `VideoStats.kt`, `MediaCodecHelper.kt`

Why second:

- This is the most performance-sensitive path.
- The renderer thread mixes dequeue timing, frame drop decisions, decode latency stats, watchdog recovery, and codec exception recovery.
- Current code has multiple policies controlled by `preferLowerDelays`, `framePacing`, stream FPS, display refresh, jitter estimates, and recent drop counters.

Recommended PR slice:

1. Extract pure frame pacing/drop decision math into a small internal policy type with no Android dependencies.
2. Add unit tests for late-frame, cooldown, refresh-mismatch, and smoothness policy cases.
3. Add lightweight counters to distinguish decoder starvation, intentional drops, watchdog flushes, and format changes.

Validation:

- Unit tests for the extracted policy before behavior changes.
- `./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest --console=plain`
- Device smoke with 60 FPS and high-refresh displays before any policy threshold changes.

Do not include:

- New drop thresholds without before/after frame-time evidence.
- Replacing `MediaCodec` threading wholesale in the first PR.

### 3. Controller Input Scheduling And Cleanup

Files: `ControllerHandler.kt`, controller migration tests

Why third:

- Controller input is latency-sensitive but easier to isolate than stream lifecycle.
- `handleButtonUp()` uses `Thread.sleep()` to enforce a minimum press duration.
- Mouse emulation, battery polling, and sensor re-enable work are scheduled through handlers and need clear stop semantics.
- `destroy()` calls `backgroundHandlerThread.quit()` without joining, so queued callbacks can outlive logical shutdown briefly.

Recommended PR slice:

1. Replace the button-up blocking sleep with a delayed key-up send path that preserves minimum press behavior without blocking the caller.
2. Use `quitSafely()` or explicit callback draining where safe, then document the teardown contract.
3. Guard delayed battery/sensor callbacks with `stopped` checks.

Validation:

- `KotlinControllerHandlerMigrationTest`
- A focused unit test for minimum button duration behavior if the send path can be faked.
- Physical controller smoke for rumble, guide/back behavior, and mouse emulation.

### 4. Library And HUD UI Jank Audit

Files: `NovaLibraryActivity.kt`, `NovaQuickMenu.kt`, `NovaStreamHud.kt`, `NovaHudUiState.kt`

Why fourth:

- UI work is user-visible and lower risk than streaming internals.
- `NovaLibraryActivity` rebuilds `NovaLibraryUiStateMapper.build(allGames, searchQuery, filterState)` inside composition.
- Cover images are loaded through `AndroidView` and `apiClient.loadCoverInto()`, while the activity clears the API cover cache after loading games.
- HUD updates are frequent during streaming and should stay cheap.

Recommended PR slice:

1. Wrap library model mapping in `remember`/derived state keyed by `allGames`, `searchQuery`, and `filterState`.
2. Measure cover loading churn before changing the loader. If churn is confirmed, prefer a stable image pipeline using existing Coil dependency.
3. Add a lightweight HUD update benchmark or trace marker around publish/update frequency before optimizing state shape.

Validation:

- Existing Compose UI state tests.
- Manual library navigation on TV/controller and touch.
- Screenshot or trace evidence for library scroll and HUD update jank before and after.

### 5. Gradle And Build Configuration Hygiene

Files: `build.gradle`, `app/build.gradle`, `gradle.properties`, `.github/workflows/dependency-submission.yml`

Why fifth:

- Build cache and configuration cache are already enabled.
- The root build still has dynamic task wiring in `gradle.projectsEvaluated`, global dependency resolution rules, and an explicit packaged-manifest cache opt-out.
- Dependency submission deliberately runs Gradle with `--no-configuration-cache`, so build optimization and dependency graph reliability should stay separate.

Recommended PR slice:

1. Run a configuration-cache report and capture the current blockers.
2. Move dynamic aggregate-test wiring toward a cache-friendlier task registration pattern if the report identifies it.
3. Keep the Netty and toolchain security constraints unless a dependency report proves they are obsolete.

Validation:

- `./gradlew help --configuration-cache --console=plain`
- `./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest --console=plain`
- `./gradlew -PnovaAbis=x86_64 assembleNonRoot_gameDebug --console=plain` before merging build logic changes.

## Follow-up PR Plan

Merged follow-up PRs:

1. `nova/runtime-task-ownership` (#40): route runtime cleanup tasks through lifecycle scope.
2. `nova/video-pacing-policy-tests` (#41): extract video frame pacing policy with tests.
3. `nova/controller-input-scheduling` (#42): schedule controller button releases without blocking input.
4. `nova/library-hud-jank` (#43): memoize library UI state mapping.
5. `nova/gradle-cache-hygiene` (#44): modernize Gradle task wiring.

## Dependency Submission Follow-up

Keep `.github/workflows/dependency-submission.yml` enabled. The managed duplicate was disabled through repository Settings:

1. `Dependency graph`, `Dependabot alerts`, CodeQL, and the checked-in `Dependency Submission` workflow remain enabled.
2. `Automatic dependency submission` is disabled.
3. The latest `master` runs for PRs #40-#44 include the checked-in `Dependency Submission` workflow and no dynamic `Automatic Dependency Submission (Gradle)` workflow for the same SHAs.
