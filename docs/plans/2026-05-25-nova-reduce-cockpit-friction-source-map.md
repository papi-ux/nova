# Nova Reduce Cockpit Friction Source Map

Date: 2026-05-25
Branch: `nova/next-level-ui-polish`
Scope: implementation-ready plan/source map only; no app behavior changes in this card.

## Goal

Reduce Nova's post-parity “power-user cockpit” feel without breaking the proven Retroid/Pixel/Shield flows. Keep the two-zone Library/System model, keep controller-first clarity on Retroid/TV, and adapt touch/phone presentation so normal users see confident game-launcher actions before debug/runtime internals.

## Evidence inputs

- `docs/ui-ux-backlog.md:24-39` — completed local polish and current contracts: two-zone Library/System shell, recovery states, startup progress/locked-host state, home/hero model, source guards.
- `docs/ui-ux-backlog.md:41-86` — Retroid, Shield, and Pixel visual logs showing the current Library, drawers, detail sheet, startup, Command Center, HUD/stat overlay, and return-to-Library paths are product-green with caveats.
- `docs/ui-ux-backlog.md:242-260` — locked 1.1.0 direction: console/game-launcher identity, TV/controller parity as release gate, Command Center/HUD hierarchy, accessibility/readability follow-up.
- Retroid RC walkthrough: `/Users/papi/.hermes/artifacts/nova/rc-retroid-polish-20260524-233355-EDT/RC_WALKTHROUGH_REPORT.md`.
- Pixel parity report: `/Users/papi/.hermes/artifacts/nova/post-retroid-parity/pixel_20260525-072944-EDT/PIXEL_PARITY_REPORT.md`.
- Shield parity report: `/Users/papi/.hermes/artifacts/nova/post-retroid-parity/shield_20260525-063730/SUMMARY.txt`.

## Non-negotiables

- Local-only: do not push and do not open a PR.
- Preserve branch naming guidance from `AGENTS.md`; current branch is already `nova/next-level-ui-polish`.
- Preserve two-zone ownership: left = Library/current-view controls; right = System/host/app controls.
- Preserve controller contracts on Retroid/TV: `X Library`, `Y Layout`, `Menu System`, `LB/RB Library / System`, `B`/Back dismiss.
- Phone/tablet may reduce controller chrome, but must not remove touch access to Library, Layout, or System.
- Prefer mapper/state/source-guard changes before broad Compose rewrites. Keep implementation in bite-sized cards; the child Kanban chain already splits this into Library/home, launch/session, Command Center/HUD, deterministic gates, device smoke, and final checkpoint.

## Source map by approved recommendation

### 1. Context-aware chrome and device-class presentation

Recommendation coverage: task items 1 and 10.

Primary files/classes:
- `app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt`
  - `NovaLibraryScreen(...)` (`:804`) owns form-factor layout, drawer/menu state, controller hint placement, Library/System affordances, and base grid composition.
  - `novaLibraryControllerHints(...)` (`:1095`) defines the current controller copy: `A Select`, `B Back`, `X Library`, `Y Layout`, `Menu System`, and landscape `LB/RB Library / System`.
  - `NovaLibraryLandscapeToolbar(...)` (`:1375`) and `NovaLibraryTopHeader(...)` (`:1473`) are the right places for touch-native Library/Layout/System affordances instead of forcing phone users to read gamepad hints.
  - `NovaLibraryOptionsSheet(...)` (`:2848`) and `NovaSystemMenuSheet(...)` (`:2577`) remain the left/right overlay owners.
- `app/src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt`
  - `NovaLibraryLayoutMode.next()` (`:38`) owns Grid -> Compact grid -> List cycling.
  - `controllerHintBarBottomPaddingDp(...)` (`:790`), `showLandscapeControlRail()` (`:829`), and layout sizing functions (`:713-788`) should own device density rules instead of one-off Compose branches.
- `app/src/main/java/com/papi/nova/ui/compose/NovaFocusComponents.kt`
  - `NovaControllerHint` / `NovaControllerHintBar(...)` (`:138`, `:144`) should support hiding/replacing the bar when the presentation profile is touch-first.

Tests/guards:
- `app/src/test/java/com/papi/nova/ui/NovaLibraryActivitySourceTest.kt`
  - Existing guards: `landscapeLibraryControlsAreDrawerFirstInsteadOfPermanentRail`, `libraryOptionsDrawerOwnsFiltersRefreshAndGridCustomization`, `yButtonCyclesLibraryLayoutWithoutOpeningADrawer`, `systemMenuIsRightDrawerAndOwnsHostLevelActions`.
  - Add/adjust guards proving Retroid/TV hint strings stay intact while phone/touch presentation exposes touch-native Library/Layout/System controls.
- `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`
  - Extend existing controller hint/chrome guards so touch profile does not keep a persistent controller-only footer when it crowds phone content.
- `app/src/test/java/com/papi/nova/ui/NovaLibraryUiStateTest.kt`
  - Keep/extend `libraryLayoutModesCycleForTheYShortcut` and device sizing tests.

Acceptance criteria:
- Retroid/TV still show the controller hint contract exactly enough for existing smoke helpers to pass, including `Y Layout`.
- Phone/tablet first paint has touch-readable Library/System/Layout affordances and does not present a giant gamepad-instruction footer as the primary navigation model.
- Base Library still keeps drawer-owned filters/search/sort out of first paint.

### 2. Artwork fallback polish

Recommendation coverage: task item 2.

Primary files/classes:
- `app/src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt`
  - `NovaLibraryHeroState` (`:96`) and `gameHero(...)` (`:423`) should carry any explicit fallback-art identity needed by Compose: title initials, source/platform label, gradient seed, or health state.
  - `heroState(...)` (`:239`) already chooses active session, constrained game, recent game, first filtered game, or empty fallback.
- `app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt`
  - `NovaLibraryFocusedBackdrop(...)` (`:1128`) crossfades current artwork and calls `apiClient.loadCoverInto(...)` keyed by `targetGame.id` and `targetGame.coverUrl`.
  - `NovaLibraryHomeHero(...)` (`:1184`), `NovaLibraryHeroFallbackArtwork(...)` (`:1315`), and `NovaLibraryGameCard(...)` (`:2132`) are the visible fallback-art surfaces.
- `app/src/main/java/com/papi/nova/api/PolarisApiClient.kt`
  - `getPreferredCoverUrl(...)` (`:850`) and `loadCoverInto(...)` (`:864`) are the current cover URL/load fallback boundaries.
- `app/src/main/java/com/papi/nova/grid/assets/CachedAppAssetLoader.kt`
  - `populateImageView(...)` (`:292-298`) and placeholder visibility (`:59`, `:195-197`, `:324-326`) cover legacy grid asset fallback behavior.

Tests/guards:
- `app/src/test/java/com/papi/nova/ui/NovaLibraryUiStateTest.kt`
  - Existing: `heroArtworkFallbackRemainsUsefulWhenCoverIsMissing`.
  - Add mapper tests for fallback identity derived from title/source/platform without requiring a cover URL.
- `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`
  - Existing: `libraryCoverLoadingIsKeyedOutsideAndroidViewUpdate`, `libraryFocusedBackdropUsesFallbackArtworkCandidates`, `gameDetailCoverLoadingIsKeyedByGameIdentity`.
  - Add guards that generic fallback art does not dominate repeated game cards or first paint.

Acceptance criteria:
- Missing covers render varied title/source-aware fallbacks, not repeated giant generic Nova cards.
- Fallbacks preserve Nova identity but stay visually smaller than real artwork/title hierarchy.
- Cover loading remains keyed by game identity and does not restart from every recomposition.
- Optional follow-up, not required in the first implementation card: cover-health report for missing/bad/stale cover counts.

### 3. Launch/profile language

Recommendation coverage: task item 3.

Primary files/classes:
- `app/src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt`
  - `primaryPlayLabel(...)` (`:493`), `steamLaunchModeLabel(...)` (`:501`), `steamLaunchCaption(...)` (`:508`), `buildLaunchIntro(...)` (`:516`), `profileStateLabel(...)` (`:733`), `LaunchControlsPanel(...)` (`:1114`), `LaunchControls(...)` (`:1199`), and `LaunchProfileSummaryInline(...)` (`:1354`) own launch-sheet copy and hierarchy.
- `app/src/main/java/com/papi/nova/ui/NovaGameDetailUiState.kt`
  - `NovaGameDetailUiState` (`:6`) and `MangoHudRisk.from(...)` (`:33`) are state boundaries for advanced launch detail.
- `app/src/main/java/com/papi/nova/ui/NovaLaunchProfileSummary.kt`
  - `buildNovaLaunchProfileSummary(...)` (`:20`), `preferenceLabel(...)` (`:185`), `selectedLabelFromState(...)` (`:194`), and `issueLabel(...)` (`:205`) own high-FPS/recommendation/recovery copy.
- `app/src/main/java/com/papi/nova/api/PolarisGame.kt`
  - `LaunchModeChoice`, `SteamLaunchContract`, and `resolveLaunchModeChoice(...)` (`:53`, `:34`, `:64`) own launch-mode availability and should not be bypassed by copy-only rewrites.

Tests/guards:
- `app/src/test/java/com/papi/nova/ui/NovaLaunchProfileSummaryTest.kt`
  - Existing tests cover high-FPS recovery/trial/satisfied copy and avoiding false “limited” language.
  - Add expected product copy examples: `Headless / private stream`, `Virtual display`, `High FPS profile`, `Nova recommends High FPS for this game` where context supports it.
- `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`
  - Existing: `gameDetailRetroidFirstPaintUsesCompactGameIdentityHeader`, `gameDetailLaunchControlsPrioritizePrimaryPlayFocus`, `gameDetailLaunchModeUsesSingleInlineSelectorInsteadOfDuplicateOptionsDrawer`, `gameDetailKeepsMangoHudOutOfPrimaryLaunchDrawer`.
- `app/src/test/java/com/papi/nova/ui/NovaLaunchSourceGuardTest.kt`
  - Keep launch preflight/MangoHUD sync guards passing when labels change.

Acceptance criteria:
- Normal launch feels like a confident game action, not approving a deployment.
- Internal terms remain available as advanced/detail copy, not primary first-paint jargon.
- Product copy changes intentionally update smoke helper/oracle strings only when needed.

### 4. Startup and locked-host trust state

Recommendation coverage: task item 4.

Primary files/classes:
- `app/src/main/java/com/papi/nova/ui/NovaStreamOverlayContent.kt`
  - `NovaSessionProgressUiState` (`:34`) and `NovaSessionProgressUiState.from(...)` (`:160`) map raw startup stages to user-visible progress copy.
  - `NovaSessionProgressOverlayContent(...)` (`:223`) renders the Compose startup/locked-host overlay.
- `app/src/main/java/com/papi/nova/ui/SessionProgressOverlay.kt`
  - Runtime wrapper: `show()`, `updateState(...)`, `dismiss()` (`:20`, `:45`, `:54`).
- `app/src/main/java/com/papi/nova/Game.kt`
  - Native lifecycle callbacks feed overlay state: `stageStarting(...)` (`:4258`), `connectionStarted()` (`:4579`), `handleStreamStartedState()` (`:4675`), `onSessionEvent(...)` (`:5215`), and `onStateUpdate(...)` (`:5219`).
- `app/src/test/java/com/papi/nova/ui/LockScreenOverlayTest.kt` if lock/unlock overlay behavior changes.

Tests/guards:
- `app/src/test/java/com/papi/nova/ui/NovaStreamOverlayUiStateTest.kt`
  - Existing tests cover known stages, raw Moonlight stage names, stream-active vs input-ready, explicit stage labels, and idle-state hiding.
  - Add product-copy guard for Nova-native locked-host prompt and clear `Tap to unlock`/waiting/input-ready state.
- `app/src/test/java/com/papi/nova/ui/NovaLaunchSourceGuardTest.kt`
  - Existing: `streamStartupOverlayWaitsForNativeConnectionStartedBeforeDismissal`.

Acceptance criteria:
- Locked host reads as Nova UI, not a default/system error screen.
- Startup distinguishes connecting, stream active/waiting, host locked, input ready, and dismissing.
- No regression to ambiguous black-frame first paint in Retroid smoke.

### 5. Command Center hierarchy

Recommendation coverage: task item 5.

Primary files/classes:
- `app/src/main/java/com/papi/nova/ui/NovaQuickMenuUiState.kt`
  - `NovaQuickMenuActionId` (`:15`) and `NovaQuickMenuUiState.from(...)` (`:93`) own action definitions, chips, warnings, session stability, and quick keys.
  - Action builders such as `syncAction(...)` (`:427`), `aiAction(...)` (`:497`), `mangoAction(...)` (`:590`), `quickKeyActions(...)` (`:684`), and `optimizationRuntimeCaption(...)` (`:657`) are the current hierarchy pressure points.
- `app/src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt`
  - `NovaQuickMenuDrawer(...)` (`:113`) owns anchored drawer motion/dismiss.
  - `NovaQuickMenuContent(...)` (`:221`), `NovaQuickMenuHeader(...)` (`:338`), `NovaQuickMenuSessionStrip(...)` (`:409`), `NovaQuickMenuStabilityCard(...)` (`:441`), `NovaQuickKeys(...)` (`:587`), and `NovaQuickMenuRow(...)` (`:649`) own first paint and row ordering.
- `app/src/main/java/com/papi/nova/Game.kt`
  - `showGameMenu(...)` (`:5826`), `hideGameMenu()` (`:5839`), `disconnect()` (`:5781`), `quit()` (`:5801`) connect menu actions to stream/session behavior.

Tests/guards:
- `app/src/test/java/com/papi/nova/ui/NovaQuickMenuUiStateTest.kt`
  - Existing tests cover viewer-session locking, relaunch captions, host-limited recovery copy, touch overlay copy, non-Polaris session controls, preview core actions.
  - Add tests for section/order contract: Session, Quality, Overlay/HUD, Quick Keys/Advanced; End distinct from Disconnect.
- `app/src/test/java/com/papi/nova/ui/NovaLaunchSourceGuardTest.kt`
  - Existing: `gameBackPressClosesOpenQuickMenuInsteadOfReopeningIt`.

Acceptance criteria:
- First paint is simple and sectioned; destructive End is protected/distinct; close affordance is explicit for touch while Back/B still dismisses.
- MangoHUD/debug controls remain secondary to NovaHUD/common session controls.
- Warnings prefer action-oriented copy when practical without expanding scope.

### 6. NovaHUD modes

Recommendation coverage: task item 6.

Primary files/classes:
- `app/src/main/java/com/papi/nova/ui/NovaHudUiState.kt`
  - `NovaHudMode` (`:6`) currently maps persisted modes and `next()` cycling.
  - `NovaHudUiState.from(...)` (`:144`), `formatTargetFps(...)` (`:202`), `formatBitrate(...)` (`:214`), `formatResolution(...)` (`:226`), `buildSessionModeLabel(...)` (`:256`), and `NovaHudSessionStats` (`:330`) own player-facing vs debug data.
- `app/src/main/java/com/papi/nova/ui/NovaStreamHud.kt`
  - `cycleMode()` (`:132`), `publishState()` (`:301`), `layoutWidthForMode(...)` (`:358`) own runtime display behavior.
- `app/src/main/java/com/papi/nova/ui/NovaStreamHudContent.kt`
  - `NovaStreamHudContent(...)` (`:43`) dispatches Minimal/Performance/Debug rendering.
  - `NovaStreamHudMinimal(...)` (`:202`), `NovaStreamHudPerformance(...)` (`:148`), `NovaStreamHudDebug(...)` (`:55`), and `NovaHudSparkline(...)` (`:369`) own visual hierarchy.
- `app/src/main/java/com/papi/nova/preferences/NovaSettingsUiState.kt` and `app/src/test/java/com/papi/nova/preferences/NovaSettingsDefinitionsTest.kt`
  - Preference labels and available HUD modes must match any mode rename/addition.

Tests/guards:
- `app/src/test/java/com/papi/nova/ui/NovaHudUiStateTest.kt`
  - Existing tests cover mode mapping, compact labels, recovery/autopilot tone, safe bitrate cap, sparkline buffer, structured perf samples.
  - Add Minimal/Smart/Full-debug copy/visibility expectations.
- `app/src/test/java/com/papi/nova/ui/NovaStreamHudModeTest.kt`
  - Existing: `cycleModePersistsNextHudModePreference`; update if mode cycle changes.
- `app/src/test/java/com/papi/nova/preferences/NovaSettingsDefinitionsTest.kt`
  - Existing: `hudModePreferenceOffersCasualPerformanceAndDebugModes`; update labels if modes become Minimal/Smart/Full/debug.

Acceptance criteria:
- Default HUD is user-facing and low-noise; abnormal stream health can surface in Smart mode without exposing raw telemetry by default.
- Full/debug retains codec, bitrate, resolution, timing, and stats for power users.
- Existing tap/cycle/drag behavior and safe readable width are preserved.
- Safe-zone snapping/persistence is a follow-up unless the current architecture already stores HUD position cleanly.

### 7. Library Options discoverability

Recommendation coverage: task item 7.

Primary files/classes:
- `app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt`
  - `cycleLibraryLayoutMode()` (`:344`) owns `Y Layout` action.
  - `NovaLibraryOptionsSheet(...)` (`:2848`), `NovaLibraryFilterSheet(...)` (`:3051`), `sortModeLabel(...)` (`:3260`), `layoutModeLabel(...)` (`:3280`), and `layoutModeDetail(...)` (`:3290`) own Sort/Layout discoverability inside the left drawer.
- `app/src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt`
  - `NovaLibraryOptionsState` (`:45`) and layout metrics functions own state and density.

Tests/guards:
- `app/src/test/java/com/papi/nova/ui/NovaLibraryActivitySourceTest.kt`
  - Existing: left drawer owns filters/refresh/grid customization and `Y` cycles layout without opening a drawer.
- `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`
  - Existing: `libraryQuickOptionsSheetExposesSortAndLayoutControls`, `libraryOptionsDrawerUsesCompactBrowseControlsOnRetroidLandscape`, `libraryOptionsDrawerKeepsBottomControlsScrollableAboveSafeArea`.
- `app/src/test/java/com/papi/nova/ui/NovaLibraryUiStateTest.kt`
  - Existing layout mode cycle/density tests.

Acceptance criteria:
- Layout is near Sort or exposed as a compact Grid/Compact/List row on small screens; it must not be buried below first-paint essentials.
- Two-zone ownership remains: layout stays left/current-view, not right/System.

### 8. Session lifecycle copy

Recommendation coverage: task item 8.

Primary files/classes:
- `app/src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt`
  - `NovaLibraryActiveSessionUiState.from(...)` (`:142`), `activeSessionHero(...)` (`:356`), `streamDetail(...)` (`:407`), and recovery methods (`:558-680`) own Library-side Resume/Watch/Reconnect/failed-launch copy.
- `app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt`
  - `NovaLibraryActiveSessionCard(...)` (`:1650`) and `NovaLibraryHomeHero(...)` (`:1184`) render active/resumable session copy.
  - `resumeActiveSession(...)` (`:632`) and `endActiveSession(...)` (`:671`) wire actions.
- `app/src/main/java/com/papi/nova/ui/NovaQuickMenuUiState.kt` / `NovaQuickMenuContent.kt`
  - Command Center session strips/actions should distinguish client disconnect from ending the host session.
- `app/src/main/java/com/papi/nova/Game.kt`
  - `disconnect()` (`:5781`), `quit()` (`:5801`), `markLocalSessionEnd()` (`:5768`) are semantic action boundaries.

Tests/guards:
- `app/src/test/java/com/papi/nova/ui/NovaLibraryUiStateTest.kt`
  - Existing active-session hero tests; add literal copy expectations for `Resume`, `Reconnect`, `Game still running`, `End session`, `Session ended` where state supports it.
- `app/src/test/java/com/papi/nova/ui/NovaQuickMenuUiStateTest.kt`
  - Add Command Center action label/description expectations for Disconnect vs End.
- `app/src/test/java/com/papi/nova/ui/NovaHudSessionStatsTest.kt`
  - Keep session summary logging free of identifiers if copy/state touches session report fields.

Acceptance criteria:
- Users can tell whether they are leaving the client stream or ending the host game session.
- Library return after Disconnect makes the resumable state obvious without implying the host game ended.

### 9. First-run and recovery polish

Recommendation coverage: task item 9.

Primary files/classes:
- `app/src/main/java/com/papi/nova/ui/NovaWelcomeActivity.kt`
  - `shouldShow(...)` (`:52`) and action routing own first-run entry behavior.
- `app/src/main/java/com/papi/nova/PcView.kt`
  - Host/no-host/pairing/startup paths: `updateEmptyState(...)` (`:881`), `launchQuickLibrary(...)` (`:939`), `launchPolarisStartupForPreferredHost(...)` (`:950`), `handleWelcomeAction(...)` (`:1106`), `doPair(...)` (`:1451`), `handlePolarisStartupResult(...)` (`:1754`), and `doNovaLibrary(...)` (`:1825`).
- `app/src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt`
  - Recovery model: `NovaLibraryRecoveryUiState` (`:85`), `emptyRecoveryState(...)` (`:572`), `loadFailureRecoveryState(...)` (`:613`), `launchFailureRecoveryState(...)` (`:670`).
- `app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt`
  - `NovaLibraryRecoveryState(...)` (`:2474`) renders recovery cards.

Tests/guards:
- `app/src/test/java/com/papi/nova/ui/NovaWelcomeRefreshTest.kt`
  - Existing tests cover three controller actions, scoped welcome copy, seen flag/action routing.
- `app/src/test/java/com/papi/nova/ui/NovaLibraryUiStateTest.kt`
  - Existing recovery tests for one clear CTA and failed-launch recovery.
- `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`
  - Existing recovery source guards and persistent retry/launch failure guards.
- `app/src/test/java/com/papi/nova/ui/NovaFocusDrawableTest.kt`
  - Existing dashboard/server focus/readability tests if PcView first-run copy/actions change.

Acceptance criteria:
- Recovery states stay one-primary-CTA and hide cert/API details behind Diagnostics/Help.
- No-host/offline/Polaris-unavailable/empty-library/failed-launch states read as Nova product states, not raw API errors.
- First-run/welcome remains scoped to verified flows; do not invent onboarding steps not currently supported.

## Implementation card mapping

### Card 1: `t_691e046c` — Library/home shell polish

Covers: context-aware chrome, artwork fallback polish, Library Options discoverability, device-class presentation.

Touch first:
1. Add/adjust failing tests in `NovaLibraryActivitySourceTest`, `NovaComposeSourceGuardTest`, and `NovaLibraryUiStateTest` for Retroid/TV hints plus phone/touch affordances, fallback-art identity, and Layout near Sort.
2. Implement mapper/state changes in `NovaLibraryUiState.kt` before Compose branching.
3. Update `NovaLibraryActivity.kt` surfaces: header/toolbar/hint bar, fallback art composables, Options drawer Layout row.
4. Run focused tests named above and report any helper/oracle copy updates needed.

### Card 2: `t_1db903bd` — Launch/profile/startup/session language

Covers: launch/profile language, startup/locked-host trust state, session lifecycle copy, recovery polish.

Touch first:
1. Add/adjust tests in `NovaLaunchProfileSummaryTest`, `NovaStreamOverlayUiStateTest`, `NovaLibraryUiStateTest`, `NovaQuickMenuUiStateTest`, and source guards for intentional copy shifts.
2. Update copy/state in `NovaLaunchProfileSummary.kt`, `NovaGameDetailSheet.kt`, `NovaStreamOverlayContent.kt`, `NovaLibraryUiState.kt`, and `NovaQuickMenuUiState.kt`.
3. Keep `PolarisGame.kt` and launch preflight logic as the source of launch-mode truth; do not replace rules with string matching.

### Card 3: `t_e3612953` — Command Center + HUD hierarchy

Covers: Command Center hierarchy and NovaHUD modes.

Touch first:
1. Add/adjust tests in `NovaQuickMenuUiStateTest`, `NovaHudUiStateTest`, `NovaStreamHudModeTest`, and `NovaSettingsDefinitionsTest`.
2. Reorder/model Command Center sections in `NovaQuickMenuUiState.kt`; render the sectioning and close affordance in `NovaQuickMenuContent.kt`.
3. Rename/reshape HUD modes in `NovaHudUiState.kt`, `NovaStreamHud.kt`, `NovaStreamHudContent.kt`, and settings definitions only after tests pin the expected cycle/copy.
4. Preserve Game action semantics in `Game.kt`; especially `disconnect()` vs `quit()`.

### Gates: `t_4b2bb18b`, `t_1fec19f2`, `t_265ca117`

Deterministic verification should run the focused tests named by implementation cards, then widen to:

```bash
./gradlew -PnovaAbis=x86_64 :app:testNonRoot_gameDebugUnitTest
./gradlew -PnovaAbis=x86_64 -PlintFailOnError=true :app:lintNonRoot_gameDebug
./gradlew -PnovaAbis=x86_64 :app:assembleNonRoot_gameDebug
./gradlew -PnovaAbis=arm64-v8a :app:assembleNonRoot_gameDebug
python3 -m unittest tools.test_nova_retroid_smoke -v  # only if smoke helper/oracle files changed
```

Device evidence should store new artifacts under `~/.hermes/artifacts/nova/cockpit-friction/` and separate product pass/fail from stale helper/oracle failures.

## Out-of-scope / follow-up candidates

- Full cover-health report/dashboard can be a follow-up if richer local fallback rendering lands first.
- Persisted HUD safe-zone drag/snap should be a follow-up unless existing state persistence makes it trivial.
- Full accessibility pass for font scale/TalkBack/contrast/overscan should be scheduled after the three implementation lanes, not buried inside the first Library card.
- Long-session performance characterization remains release/hardware soak, not part of this cockpit-friction UI slice.

## Plan-only verification for this card

Docs-only card verification:

```bash
git status --short --branch
git diff --stat
git diff -- docs/plans/2026-05-25-nova-reduce-cockpit-friction-source-map.md
git diff --check
```

No Gradle/device tests are required for this plan-only source map.
