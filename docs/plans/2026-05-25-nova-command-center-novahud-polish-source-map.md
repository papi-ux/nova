# Nova Command Center + NovaHUD Polish Source Map

> **For Hermes:** This is a plan/source-map artifact only. Do not implement from this card. Use `subagent-driven-development` or the Kanban follow-up card to execute the plan with RED/GREEN tests first.

**Goal:** Make the in-game Command Center and NovaHUD feel like one polished overlay system over gameplay, not a debug drawer with a nice coat of paint and questionable life choices.

**Architecture:** Keep the existing full-screen Dialog-hosted left drawer and Compose HUD overlay. Tighten the state model, section order, focus/dismiss behavior, and HUD mode language around a shared in-game overlay visual contract. Preserve proven stream/session semantics: `Disconnect` returns to Library with the stream resumable; `End session` remains destructive/protected.

**Tech Stack:** Android/Kotlin, Jetpack Compose, Robolectric unit/source-guard tests, optional Compose instrumentation, Gradle `NonRoot_gameDebug`, Retroid ARM64 ADB smoke.

---

## Current checkout inspected

- Machine/repo: pc-papi `/home/papi/Documents/github/nova`
- Branch: `nova/next-level-ui-polish`
- HEAD at inspection: `b9bec2e2`
- Local-only policy still applies: do not push/open PRs; do not clear app data; do not confirm destructive `End session` during smoke unless Michael explicitly authorizes it.

Primary inputs read:

- `docs/ui-ux-backlog.md`
- `docs/plans/2026-05-25-nova-reduce-cockpit-friction-source-map.md`
- `app/src/main/java/com/papi/nova/ui/NovaQuickMenu.kt`
- `app/src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt`
- `app/src/main/java/com/papi/nova/ui/NovaQuickMenuUiState.kt`
- `app/src/main/java/com/papi/nova/ui/NovaHudUiState.kt`
- `app/src/main/java/com/papi/nova/ui/NovaStreamHud.kt`
- `app/src/main/java/com/papi/nova/ui/NovaStreamHudContent.kt`
- `app/src/main/java/com/papi/nova/ui/NovaStreamOverlayContent.kt`
- `app/src/main/java/com/papi/nova/ui/compose/NovaInGameOverlayTokens.kt`
- relevant tests/source guards under `app/src/test/java/com/papi/nova/ui/`, `app/src/test/java/com/papi/nova/preferences/`, and `app/src/androidTest/java/com/papi/nova/ui/`

---

## Non-negotiables

1. Preserve session semantics:
   - `Disconnect` calls `Game.disconnect()` and prepares background resume (`Game.kt:5781-5788`).
   - `End session`/owner quit calls `Game.quit()` and still shows the existing confirmation (`Game.kt:5801-5824`).
   - Viewer `Leave` remains a disconnect path (`NovaQuickMenu.kt:196-200`).
2. Keep Command Center in-stream only. Do not collapse Library/System two-zone semantics into this drawer.
3. Keep NovaHUD as the normal player-facing in-game overlay. MangoHud/perf/debug controls stay secondary.
4. Use TDD/source guards before production changes. The current source guards are intentionally strong; update the wrong ones rather than fighting them with duct tape.
5. Retroid smoke must be non-destructive unless Michael explicitly authorizes session-ending cleanup.

---

## Current source map

### Command Center host and action boundaries

- `NovaQuickMenu.kt:33-68`
  - Hosts the Command Center in a transparent full-screen `Dialog(game)`.
  - Sets lifecycle/saved-state owners on the `ComposeView`.
  - Removes decor padding/insets and uses `MATCH_PARENT` width/height with `Gravity.START|TOP`.
- `NovaQuickMenu.kt:178-203`
  - `onDisconnect` dismisses then calls `game.disconnect()`.
  - `onEndStream` dismisses then calls `game.disconnect()` for viewers or `game.quit()` for owners.
- `NovaQuickMenu.kt:386-401`
  - Overlay actions toggle NovaHUD and legacy perf stats; enabling perf stats dismisses NovaHUD first.
- `Game.kt:5781-5824`
  - `disconnect()` finishes with resumable background window.
  - `quit()` prompts before marking local session end and finishing.

### Command Center model/state

- `NovaQuickMenuUiState.kt:15-38`
  - `NovaQuickMenuActionId` already has the important action buckets: session, stability/sync/advanced, quick keys, overlay, controls, session utilities.
- `NovaQuickMenuUiState.kt:73-90`
  - Current state is grouped by fields, not an explicit section model:
    - `disconnectAction`, `endAction`
    - `stability`, `sync`, `advancedToggle`, `advancedRows`
    - `quickKeys`, `overlayRows`, `controlRows`, `sessionRows`
- `NovaQuickMenuUiState.kt:141-164`
  - Session health/mode copy and tone are already computed centrally.
- `NovaQuickMenuUiState.kt:212-248`
  - `stability` and `advancedToggle` are the current quality/advanced pressure points.
- `NovaQuickMenuUiState.kt:285-340`
  - `overlayRows`, `controlRows`, and `sessionRows` are hard-coded lists.
- `NovaQuickMenuUiState.kt:342-370`
  - Return object wires row lists but does not encode a first-paint section order contract.

### Command Center rendering and current first paint

- `NovaQuickMenuContent.kt:113-217`
  - `NovaQuickMenuDrawer(...)` already uses finger-tracked left drawer motion:
    - `Animatable(0f)` drawer progress.
    - scrim alpha follows progress.
    - `detectHorizontalDragGestures` with consumed horizontal drag.
    - swipe-left dismissal threshold via `NovaQuickMenuDrawerDismissProgress`.
- `NovaQuickMenuContent.kt:221-334`
  - Current body order is:
    1. handle + header
    2. session strip
    3. Quick Keys
    4. Stability/AI Auto Quality card
    5. Sync + Advanced cards
    6. Advanced rows, if expanded
    7. Overlays + Controls
    8. Session utilities
  - This conflicts with the requested hierarchy: Session actions → Quality/stream health → NovaHUD/Overlay → Quick Keys/Advanced.
- `NovaQuickMenuContent.kt:337-405`
  - Header has title plus `Disconnect` and `End session` buttons.
  - There is no explicit close button in the header yet.
- `NovaQuickMenuContent.kt:409-437`
  - Session strip already shows stream mode chip + health summary.
- `NovaQuickMenuContent.kt:687-725`
  - `NovaQuickMenuClickableSurface` owns focused background/border behavior for rows.

### Visual tokens and remaining alpha pressure points

- `NovaInGameOverlayTokens.kt:10-19`
  - Existing shared contract:
    - `CommandCenterScrim = 0.42f`
    - `GlassPanel = 0.94f`
    - `NestedTile = 0.76f`
    - `NestedControl = 0.82f`
    - `Border = 0.90f`
    - `AccentHandle`, `AccentDivider`, `SparklineGuide`, `SparklineFill`
- `NovaQuickMenuContent.kt`
  - Consumes shared tokens for scrim, panel, border, handle, nested controls, and nested tiles.
  - Remaining local alpha values worth naming or intentionally leaving:
    - disabled row alpha `0.45f` (`:711`)
    - chip fill alpha `0.16f/0.20f` (`:734`)
- `NovaStreamHudContent.kt`
  - Consumes shared tokens for panel, border, nested control, accent divider, sparkline guide/fill.
  - Remaining local alpha worth naming: sparkline top guide `0.10f` (`:406`).
- `NovaStreamOverlayContent.kt`
  - Startup/reconnect overlays still use local full-screen scrim/text alpha values (`0.80f`, `0.86f`, `0.18f`, `0.72f`, `0.56f`). These are not the same surface type as Command Center/HUD, but if touched, give them names so future passes do not grow another alpha junk drawer.

### NovaHUD model/rendering/behavior

- `NovaHudUiState.kt:6-27`
  - Current modes are `MINIMAL("minimal")`, `PERFORMANCE("performance")`, `DEBUG("debug")`.
  - Legacy aliases already exist: `fps_only/nano/compact` → Minimal; `banner/strip` → Performance; `full/command` → Debug.
  - `fromPreference(null)` currently defaults to Minimal.
- `NovaHudUiState.kt:202-233`
  - Formatting changes by mode:
    - Debug gets `TGT 120`, full bitrate, full resolution.
    - Performance gets `/120`, compact bitrate, `1080p`.
    - Minimal hides target FPS.
- `NovaStreamHud.kt:98-145`
  - HUD touch handler uses raw coordinates for drag and tap-to-cycle when not dragging.
  - `cycleMode()` preserves `view.x/y` after width changes.
- `NovaStreamHudContent.kt:47-51`
  - Dispatches by mode to `NovaStreamHudDebug`, `NovaStreamHudPerformance`, and `NovaStreamHudMinimal`.
- `NovaStreamHudContent.kt:55-198`
  - Debug is a dense full panel; Performance is a compact strip; Minimal is casual and avoids bitrate/sparkline.
- `app/src/main/res/values/arrays.xml:81-90`
  - Settings currently show labels `Minimal`, `Performance`, `Debug` with values `minimal`, `performance`, `debug`.

### Existing tests/source guards to preserve or update

- `NovaQuickMenuUiStateTest.kt`
  - Existing coverage: viewer session locks owner-only controls, sync relaunch chip, host-render recovery copy, touch controls copy, non-Polaris controls, preview core actions.
- `NovaComposeSourceGuardTest.kt:1516-1643`
  - Already protects anchored left drawer, finger-tracked drawer motion, shared opacity tokens.
- `NovaComposeSourceGuardTest.kt:1695-1727`
  - Current `commandCenterGroupsQuickKeysBeforeSecondaryPanels` guard explicitly requires Quick Keys before stability/sync/advanced. This guard must be replaced for the new requested hierarchy.
- `NovaComposeSourceGuardTest.kt:1796-1839`
  - Protects compact/bounded HUD labels and the “Minimal should not show bitrate/sparkline” contract.
- `NovaLaunchSourceGuardTest.kt:183-194`
  - Protects Back dismissing an already-open Command Center instead of reopening a second one.
- `NovaLaunchSourceGuardTest.kt:274-290`
  - Protects player-facing lifecycle language: Resume stream, Watch stream, Disconnect, End session.
- `NovaHudUiStateTest.kt`
  - Existing mode/format/compact-label tests are the right place to pin Minimal/Smart/Full behavior.
- `NovaStreamHudModeTest.kt`
  - Existing cycle/persist test should be updated when `Smart` becomes the default/middle mode.
- `NovaSettingsDefinitionsTest.kt:96-111`
  - Existing settings test should be updated with new HUD mode labels/values.

---

## Implementation contract

### 1. Command Center first-paint hierarchy

Target first paint:

1. **Header / Session actions**
   - Title/subtitle.
   - Explicit close affordance.
   - `Disconnect` visible and non-destructive.
   - `End session` visible but visually destructive/protected; never the initial focus target.
2. **Quality / stream health**
   - Existing session strip stays near top.
   - AI Auto Quality / stability recovery card follows immediately.
   - Sync status is secondary but still visible on first paint when space allows.
3. **NovaHUD / Overlay**
   - NovaHUD row first.
   - Stats Overlay second.
   - MangoHud remains Advanced/debug, not a peer to NovaHUD.
4. **Quick Keys**
   - ESC, Alt+Enter, Alt+F4, F11, Meta, Ctrl+V remain reachable, but after session/quality/HUD.
5. **Advanced / Controls / Session utilities**
   - Advanced collapsed by default.
   - Manual overrides, Clear Game Profile, MangoHud, mouse/keyboard/touch controls, rotate, paste, More Keys can live behind lower sections/collapse.

Implementation shape:

- Preferred: add explicit section state so tests can verify order without parsing Compose body strings forever.
  - Add `enum class NovaQuickMenuSectionId { SESSION, QUALITY, OVERLAY, QUICK_KEYS, CONTROLS, ADVANCED, UTILITIES }` or equivalent.
  - Add a small `data class NovaQuickMenuSection(val id, val title, val actions, val priority, val initiallyExpanded)` only if it reduces duplication. Do not overbuild a generic dashboard engine.
  - Keep `disconnectAction` and `endAction` separate because they are not ordinary rows.
- Lowest-risk fallback: keep current state fields and reorder `NovaQuickMenuContent(...)` directly, but replace the existing source guard with the new exact body-order guard.

### 2. Visual language

- Keep `NovaInGameOverlayAlpha` as the shared Command Center/HUD opacity contract.
- Add only the tokens that remove real duplication or magic values:
  - `DisabledControl` for `0.45f` if used in both Command Center/HUD/future overlay rows.
  - `StatusChipInactive` / `StatusChipActive` or similar only if chip alpha repeats beyond one component.
  - `SparklineCeilingGuide` for the remaining `0.10f` in HUD sparkline.
- Do not force startup full-screen black overlays into the same glass token set unless that code is touched. If touched, use a sibling object such as `NovaStreamTrustOverlayAlpha` to avoid semantic soup.
- Keep using `LocalNovaLibrarySurfaces` and `LocalNovaComposeColors` so Command Center/HUD inherit the Library/System drawer aesthetic.

### 3. NovaHUD modes and behavior

Target user-facing modes:

1. **Minimal**
   - Smallest casual readout.
   - FPS + latency/status only.
   - No bitrate, no codec, no resolution, no sparkline.
2. **Smart** (default for fresh installs)
   - Default in-game mode.
   - Uses the current Performance strip as a starting point, but only surfaces noisy technical details when useful.
   - Show FPS/target, latency, Auto Quality status, and maybe a tiny sparkline.
   - Gate bitrate/resolution/codec behind abnormal state or use tighter labels so gameplay remains primary.
3. **Full / debug**
   - Keeps power-user telemetry: codec, bitrate, resolution, timing, 1% low, sparkline, stream mode.
   - Stored preference value can remain `debug` to avoid migration pain.

Preference/migration recommendation:

- Rename internal `PERFORMANCE` to `SMART` if practical.
- Keep backward-compatible mapping:
  - `performance`, `banner`, `strip` → `SMART`
  - `debug`, `full`, `command` → `FULL_DEBUG`/`DEBUG`
  - null/unknown → `SMART` for fresh default
- Settings arrays should become values `minimal`, `smart`, `debug` and labels `Minimal`, `Smart`, `Full / debug`.
- Existing saved `minimal` and `debug` must keep working. Existing saved `performance` should map to Smart and be rewritten only when the user cycles/touches settings.

Behavior:

- Keep tap-to-cycle and raw-coordinate drag.
- Preserve dragged position across mode cycles.
- If implementing safe-zone clamping is cheap, clamp after drag end and after mode width changes; persist per orientation/display class only if it does not become a new state swamp.
- Expose an affordance in Command Center copy: NovaHUD row caption should tell users “Tap HUD to change mode; drag to move” or equivalent.

### 4. Touch/controller mechanics

- Add an explicit header close affordance:
  - Uses `callbacks.onDismiss`.
  - Content description: `Close Command Center`.
  - Should be touch-friendly but not louder than `Disconnect`.
- Back/B dismissal:
  - Preserve existing Back behavior guarded by `NovaLaunchSourceGuardTest`.
  - If the Dialog path changes, keep `onDismissRequest`/`hideGameMenu()` equivalent covered.
- Initial focus:
  - Request focus on `Disconnect` or the first safe non-destructive session action.
  - Never initial-focus `End session`.
  - If focus fights Dialog mount, use a delayed `LaunchedEffect`/`FocusRequester` pattern like the Library drawers.
- Scroll-inside-drawer:
  - Keep vertical scroll inside the drawer from dismissing the drawer.
  - Existing horizontal drag guard is good; add/keep source guard language that vertical `verticalScroll(...)` and horizontal `detectHorizontalDragGestures(...)` coexist intentionally.

---

## TDD / source-guard plan

### RED tests to add or replace first

1. `app/src/test/java/com/papi/nova/ui/NovaQuickMenuUiStateTest.kt`
   - Add `commandCenterStateSeparatesSessionQualityOverlayQuickKeysAndAdvanced()`.
   - Expected RED before implementation if an explicit section model is introduced.
   - Assert:
     - `disconnectAction.label == "Disconnect"`
     - `endAction.label == "End session"` for owner and `destructive == true`
     - viewer still sees `Leave`
     - NovaHUD is first overlay row
     - MangoHud stays in advanced rows
     - Quick keys remain present but not modeled as quality/overlay/session
2. `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`
   - Replace `commandCenterGroupsQuickKeysBeforeSecondaryPanels()` with `commandCenterFirstPaintPrioritizesSessionQualityAndHudBeforeQuickKeys()`.
   - Assert body order:
     - header/session strip before stability/sync
     - stability/sync before overlays
     - overlays before quick keys
     - quick keys before collapsed advanced/lower utilities, or whatever exact final order is chosen
   - Add/extend a guard for header close affordance and initial focus:
     - `contentDescription = "Close Command Center"`
     - `FocusRequester` wired to a non-destructive action
     - `End session` is not the requested initial focus target
3. `app/src/test/java/com/papi/nova/ui/NovaHudUiStateTest.kt`
   - Add `hudModesExposeMinimalSmartAndFullDebugWithBackwardCompatibleAliases()`.
   - Assert:
     - `fromPreference(null) == SMART`
     - `fromPreference("performance") == SMART`
     - `fromPreference("smart") == SMART`
     - `fromPreference("full")` and `fromPreference("debug")` map to full/debug
   - Add `smartHudKeepsGameplayLowNoiseUntilHealthNeedsAttention()`.
     - Normal Smart does not render as dense debug telemetry.
     - Warning/Danger Smart can surface the useful diagnostic bit.
4. `app/src/test/java/com/papi/nova/ui/NovaStreamHudModeTest.kt`
   - Update `cycleModePersistsNextHudModePreference()`:
     - Starting Minimal cycles to Smart.
     - Starting Smart cycles to Full/debug.
     - Full/debug cycles back to Minimal.
5. `app/src/test/java/com/papi/nova/preferences/NovaSettingsDefinitionsTest.kt`
   - Update `hudModePreferenceOffersCasualPerformanceAndDebugModes()` to the new labels/values:
     - default `smart`
     - values `minimal`, `smart`, `debug`
     - labels `Minimal`, `Smart`, `Full / debug`
6. `app/src/androidTest/java/com/papi/nova/ui/NovaQuickMenuContentComposeTest.kt`
   - Optional but valuable: assert the content composes with the new close affordance and reordered labels present. Keep this lightweight; do not make hardware smoke depend on a brittle Compose tree crawl.

### Production changes after RED

1. `NovaQuickMenuUiState.kt`
   - Add section/order model or reshape existing fields.
   - Keep action ownership clear:
     - `stability` + `sync` = Quality/stream health
     - `overlayRows` = NovaHUD + Stats Overlay
     - `advancedRows` = AI toggle, Clear Game Profile, MangoHud/manual/debug
     - `controlRows` and `sessionRows` = lower utilities unless design chooses a separate Controls section
2. `NovaQuickMenuContent.kt`
   - Reorder body to match the hierarchy.
   - Add header close affordance.
   - Add initial focus request to non-destructive session action or close affordance.
   - Preserve anchored drawer, scrim dismiss, horizontal drag, and vertical scroll.
3. `NovaInGameOverlayTokens.kt`
   - Add missing named alpha tokens only where they reduce real duplication.
4. `NovaHudUiState.kt`
   - Introduce Smart/default mapping and backward-compatible aliases.
   - Decide whether to keep enum name `DEBUG` internally or rename to `FULL_DEBUG`; do not break stored `debug` preference value.
5. `NovaStreamHudContent.kt`
   - Rename/render Smart mode.
   - Keep Minimal low-noise and Full/debug dense.
6. `NovaStreamHud.kt`
   - Preserve drag/tap/cycle behavior.
   - Add safe-zone clamp/persistence only if small and testable.
7. `app/src/main/res/values/arrays.xml` and possibly `strings.xml`
   - Update mode labels/values.
   - Add Command Center close/HUD affordance copy if needed.

### GREEN gates

Run narrow tests first from pc-papi `/home/papi/Documents/github/nova`:

```bash
./gradlew -PnovaAbis=x86_64 :app:testNonRoot_gameDebugUnitTest \
  --tests 'com.papi.nova.ui.NovaQuickMenuUiStateTest' \
  --tests 'com.papi.nova.ui.NovaHudUiStateTest' \
  --tests 'com.papi.nova.ui.NovaStreamHudModeTest' \
  --tests 'com.papi.nova.ui.NovaComposeSourceGuardTest' \
  --tests 'com.papi.nova.preferences.NovaSettingsDefinitionsTest' \
  --no-daemon --console=plain
```

Then widen:

```bash
./gradlew -PnovaAbis=x86_64 :app:testNonRoot_gameDebugUnitTest --no-daemon --console=plain
./gradlew -PnovaAbis=x86_64 :app:lintNonRoot_gameDebug :app:assembleNonRoot_gameDebug --no-daemon --console=plain
./gradlew -PnovaAbis=arm64-v8a :app:assembleNonRoot_gameDebug --no-daemon --console=plain
```

If the instrumentation Compose test changes and a device/emulator is available, run the targeted connected test separately; do not block deterministic unit/lint gates on a flaky emulator if the implementation did not rely on Android-only runtime behavior.

---

## Retroid live-smoke checklist

Use a fresh ARM64 debug APK from pc-papi. Install over existing app data only; do not wipe paired data.

Suggested prep:

```bash
ANDROID_SERIAL=24c12bdd ./gradlew -PnovaAbis=arm64-v8a :app:assembleNonRoot_gameDebug :app:installNonRoot_gameDebug --no-daemon --console=plain
```

Evidence target:

```bash
mkdir -p ~/.hermes/artifacts/nova/command-center-hud/$(date +%Y%m%d-%H%M%S)
```

Checklist:

1. Prove app/package freshness:
   - package `com.papi.nova.debug`
   - versionCode/versionName
   - installed base APK SHA matches built APK SHA
   - `primaryCpuAbi=arm64-v8a`
2. Launch through the real paired/populated Library path.
3. Start a real game stream from Library, not a direct Activity shortcut.
4. If first paint is locked-host, capture the locked state and unlock manually only if safe.
5. Open Command Center over the stream:
   - physical/safe controller shortcut first (`Start`/`AppSwitch`/validated chord)
   - helper fallback only if physical route is unavailable
6. Verify first paint visually:
   - `Disconnect` and protected `End session` visible and distinct
   - Quality/stream health visible before debug controls
   - NovaHUD/Overlay visible before Quick Keys/Advanced
   - explicit close affordance visible/reachable
7. Dismiss/reopen paths:
   - close affordance
   - scrim tap
   - Back/B
   - horizontal swipe/drag if implemented
8. Scroll inside drawer:
   - vertical scroll does not dismiss
   - lower utilities remain reachable
9. NovaHUD:
   - toggle from Command Center
   - tap HUD to cycle Minimal → Smart → Full/debug → Minimal
   - drag HUD and verify position does not reset when mode changes
   - capture bright and dark game-scene screenshots for readability
10. Destructive lifecycle rule:
   - Do not confirm `End session` unless explicitly authorized.
   - It is OK to verify the protected prompt appears, then cancel.
11. Package-scoped crash scan:
   - bounded from the smoke start timestamp
   - report `FATAL EXCEPTION`, ANR, native crash, or `Process: com.papi.nova.debug` separately from non-Nova noise

Useful helper entrypoints already exist:

```bash
python3 tools/nova_retroid_smoke.py command-center --serial 24c12bdd --skip-install --artifacts-dir <dir>
python3 tools/nova_retroid_smoke.py live-stream --serial 24c12bdd --skip-install --no-end-stream --artifacts-dir <dir>
```

Use `--no-end-stream` unless Michael explicitly authorizes cleanup via End session.

---

## Exact source files/tests expected to touch in implementation card

Production/source:

- `app/src/main/java/com/papi/nova/ui/NovaQuickMenuUiState.kt`
- `app/src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt`
- `app/src/main/java/com/papi/nova/ui/NovaQuickMenu.kt` only if the Dialog/focus/dismiss bridge needs wiring
- `app/src/main/java/com/papi/nova/ui/compose/NovaInGameOverlayTokens.kt`
- `app/src/main/java/com/papi/nova/ui/NovaHudUiState.kt`
- `app/src/main/java/com/papi/nova/ui/NovaStreamHud.kt`
- `app/src/main/java/com/papi/nova/ui/NovaStreamHudContent.kt`
- `app/src/main/res/values/arrays.xml`
- `app/src/main/res/values/strings.xml` if adding close/HUD affordance copy
- `app/src/main/res/xml/preferences.xml` only if preference defaults/entries need XML changes beyond arrays/default resources

Tests/source guards:

- `app/src/test/java/com/papi/nova/ui/NovaQuickMenuUiStateTest.kt`
- `app/src/test/java/com/papi/nova/ui/NovaHudUiStateTest.kt`
- `app/src/test/java/com/papi/nova/ui/NovaStreamHudModeTest.kt`
- `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`
- `app/src/test/java/com/papi/nova/preferences/NovaSettingsDefinitionsTest.kt`
- `app/src/test/java/com/papi/nova/ui/NovaLaunchSourceGuardTest.kt` only if dismiss/session semantics move
- `app/src/androidTest/java/com/papi/nova/ui/NovaQuickMenuContentComposeTest.kt` if adding Compose-level semantics coverage
- `tools/nova_retroid_smoke.py` and `tools/test_nova_retroid_smoke.py` only if helper/oracle strings need updating for the new hierarchy/HUD mode names

Docs/evidence after implementation:

- Update `docs/ui-ux-backlog.md` with the exact gates and Retroid evidence path.
- Store smoke artifacts under `~/.hermes/artifacts/nova/command-center-hud/` or a similarly named Hermes-owned path.

---

## Out of scope for this implementation slice

- Pushing/opening PRs.
- Clearing app/device data.
- Confirming `End session` during smoke without explicit authorization.
- Reworking Library/System drawer ownership.
- Long-session performance characterization.
- Pixel/Shield parity unless Michael expands the scope; this slice should go deep on Retroid in-game overlay feel first.
