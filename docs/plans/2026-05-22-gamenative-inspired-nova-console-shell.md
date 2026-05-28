# GameNative-Inspired Nova Console Shell Plan

Goal: use the live GameNative review on Retroid as design fuel for Nova’s next console-launcher polish pass without blindly cloning another app’s quirks. Nova stays Polaris-first, premium, self-hosted, and controller-native.

Storage note: this plan intentionally lives in `docs/plans/` so it survives disconnects and stays with the repo. Do not stage `.hermes/` for this workflow.

## Current context

- Repo: `<repo-root>`
- Branch under review: `nova/next-level-ui-polish`
- Current git state at planning time: branch was local-only with existing Nova UI work; keep review/fix commits local until the release path is explicit.
- Target device: Retroid/Android handheld or TV; pass `--serial <adb-serial>` or set `NOVA_ADB_SERIAL` unless exactly one ADB device is connected.
- Nova debug package for device checks: `com.papi.nova.debug`
- Live reference app reviewed: GameNative package `app.gamenative`

## GameNative review evidence

Captured files from the Retroid live sweep:

- Home/library: `/tmp/gamenative_review_20260522_110527_sweep_home.png` and `.xml`
- Top-left Options drawer/sheet: `/tmp/gamenative_review_20260522_110527_options.png`, `_options_scroll1.png`, `_options_scroll2.png`, `_options_scroll3.png`, plus XML siblings
- Top-right Menu: `/tmp/gamenative_review_20260522_110527_menu.png` and `.xml`
- Settings top-left flow: `/tmp/gamenative_review_20260522_110527_settings.png`, `_settings_scroll1.png` through `_settings_scroll9.png`, plus XML siblings
- Source empty states: `/tmp/gamenative_review_20260522_110527_sweep_tab_gog.png`, `_tab_epic.png`, `_tab_amazon.png`, `_tab_custom.png`, plus XML siblings
- D-pad interruption caveat: `/tmp/gamenative_review_20260522_110527_sweep_dpad_after_right_down.png` showed GameNative’s “Thank you for using GameNative!” Ko-fi/share dialog after D-pad probing.

## What GameNative does well enough to steal, politely

1. **Game-first home grid**
   - First paint is a large 5-column landscape game grid.
   - Top row is all navigation/action chrome; games get the body.
   - Cards carry quick metadata: compatibility/recommendation, source, installed state.

2. **Options as a fast library command center**
   - Top-left Options exposes sort modes, app type, app status, and layout modes: `List`, `Capsule`, `Hero`, `Carousel`.
   - This is better than hiding every browsing decision in a permanent left rail.

3. **Controller hint bar**
   - Bottom hint bar explicitly maps actions: `Select`, `Options`, `System`, `Menu`, `Search`, `Add Game`.
   - This is a big handheld win: the user should not have to remember secret chords like they’re entering a Konami prayer.

4. **Simple account/system menu**
   - Top-right Menu shows profile/online status and a short list: `Settings`, `Downloads & Storage`, `Help & Support`, `Hall of Fame`.
   - It reads as system/account shell, not game browsing.

5. **Settings hierarchy**
   - Settings uses title/subtitle rows grouped by section: Emulation, Interface, Downloads & Storage, Info, Debug.
   - Rows are readable on Retroid landscape and controls sit inline where possible.

6. **Source-specific empty states**
   - GOG/Epic/Amazon tabs do not just show blank grids. They show centered guidance and a direct CTA: sign in / add custom game.
   - Nova should use this pattern for Polaris offline, source filters with no games, no recent games, and empty library.

## What not to copy

- Do not add GameNative’s store/account model to Nova. Nova is Polaris/self-hosted first.
- Do not promote debug/proton/container controls into Nova’s normal library shell.
- Do not let a donation/share prompt interrupt controller first-run navigation.
- Do not make touch-only top actions the only path; GameNative’s Search/Add Game did not open during this touch sweep, while controller hints implied dedicated actions.

---

## Task 0: Stabilize the current working tree before new polish

**Objective:** avoid mixing the current verified Nova 1.1.0 polish slice with this new GameNative-inspired pass.

**Files:**
- Inspect only: current modified files from `git status --short --branch`

**Steps:**
1. Run `git status --short --branch`.
2. Decide whether the current uncommitted files belong to the previous UI slice or this new plan.
3. Run `git diff --check`.
4. If the current slice is complete, run the gates from `docs/plans/2026-05-21-next-level-pristine-ui-ux.md` and commit it before starting Task 1 here.
5. If the current slice is intentionally continuing, add a short note to the final implementation report explaining which tasks overlapped.

**Gate:** no new GameNative-inspired code starts until the current worktree boundary is explicit.

## Task 1: Preserve the GameNative review notes in the UI backlog

**Objective:** make the reference-app findings discoverable without needing this chat transcript.

**Files:**
- Modify: `docs/ui-ux-backlog.md`
- Existing plan reference: `docs/plans/2026-05-22-gamenative-inspired-nova-console-shell.md`

**Steps:**
1. Add a subsection under the 1.1.0 UI/UX pass called `GameNative Retroid reference sweep`.
2. Record the useful patterns: quick Options, controller hint bar, source empty states, concise system menu, sectioned settings.
3. Record the caveat: D-pad probe surfaced GameNative’s Ko-fi/share dialog; touch Search/Add Game were not fully validated in this sweep.
4. Keep this note concise; the detailed task breakdown stays in this plan.

**Gate:** docs-only diff, no source changes.

**Suggested commit:** `docs(ui): capture gamenative retroid reference sweep`

## Task 2: Add a Nova Library Quick Options sheet

**Objective:** move browse controls into an explicit controller/touch-friendly Options surface inspired by GameNative, reducing permanent rail pressure.

**Files:**
- Modify: `app/src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt`
- Modify: `app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/papi/nova/ui/NovaLibraryUiStateTest.kt`
- Test: `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`

**Scope:**
- Add durable UI state for library sort and layout mode:
  - Sort: `Recent`, `Name A-Z`, `Name Z-A`, `Source`, `HDR first`.
  - Layout: keep implementation conservative for 1.1.0: `Grid` and `Compact grid` first. Treat `Hero`/`Carousel` as later unless the grid can prove it stays usable on Retroid.
- Add a `LibraryOptionsSheet` opened from the existing rail/actions and from the new controller hint bar in Task 3.
- Keep Polaris source filters under Nova’s existing `Sources` filter path; do not add GameNative store sign-in concepts.

**Steps:**
1. Add sort/layout enums and mapper hooks in `NovaLibraryUiState.kt`.
2. Write failing tests for sort order and default layout in `NovaLibraryUiStateTest`.
3. Implement mapper sort behavior with default preserving current behavior.
4. Add source guards proving `LibraryOptionsSheet` contains Sort and Layout sections and is scrollable/focusable.
5. Implement the sheet in `NovaLibraryActivity.kt` using existing Nova focus motion and `NovaActionButton`/chip patterns.
6. Add strings for sheet title, close copy, sort labels, layout labels.
7. Verify the sheet can be dismissed with Back/B and does not steal search IME focus.

**Focused gate:**
```bash
./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest --tests com.papi.nova.ui.NovaLibraryUiStateTest --tests com.papi.nova.ui.NovaComposeSourceGuardTest
```

**Suggested commit:** `feat(ui): add library quick options sheet`

## Task 3: Add a reusable Nova controller hint bar

**Objective:** make Retroid/TV controls self-documenting across library, detail, settings, and stream shell.

**Files:**
- Create: `app/src/main/java/com/papi/nova/ui/compose/NovaControllerHintBar.kt`
- Modify: `app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt`
- Modify: `app/src/main/java/com/papi/nova/ui/NovaGameDetailSheet.kt`
- Modify: `app/src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt`
- Modify: `app/src/main/java/com/papi/nova/ui/NovaQuickMenuContent.kt` if stream/menu hints are included in this slice
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`

**Scope:**
- Add a compact bottom bar for landscape/controller contexts.
- Library hints: `Select`, `Back`, `Options`, `Search`, `Menu`.
- Detail hints: `Launch`, `Back`, `Options/Profile` if exposed.
- Settings hints: `Select`, `Back`, `Search`, `Legacy` if shown.
- Stream hints can be a follow-up if this slice grows too large.

**Steps:**
1. Create a small data model such as `NovaControllerHint(label: String, action: String)` inside the new file.
2. Implement `NovaControllerHintBar(hints, modifier)` using Nova colors, small rounded keycaps, and no more than one row on Retroid landscape.
3. Add source guards that the library uses the hint bar in landscape and does not overlay it on top of focusable cards without bottom padding.
4. Wire the library bar first; verify it does not recreate the old bottom clipping problem.
5. Wire settings/detail only after library is stable.
6. If the bar reduces vertical space too much, make it auto-hide on touch-only portrait and reserve it for controller/landscape.

**Focused gate:**
```bash
./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest --tests com.papi.nova.ui.NovaComposeSourceGuardTest
```

**Suggested commit:** `feat(ui): add controller hint bar`

## Task 4: Upgrade source/offline/empty states with direct CTAs

**Objective:** make every no-content state explain what happened and offer one obvious next action.

**Files:**
- Modify: `app/src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt`
- Modify: `app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/papi/nova/ui/NovaLibraryUiStateTest.kt`
- Test: `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`

**Scope:**
- Keep existing empty states, but make source-filter empty states more specific:
  - No games from this source → `Clear filters` + `Manage library`.
  - No recent games → `View all games`.
  - Polaris offline/load failure → `Retry` first, `Manage server` second.
  - Empty library → `Manage library`.
- Do not add store sign-in CTAs. Nova’s answer is Polaris management, not third-party account flows.

**Steps:**
1. Extend `NovaLibraryEmptyState` only if current states cannot express source-specific empty copy cleanly.
2. Add failing tests for source-filter empty copy/action priority.
3. Implement mapper copy/action changes.
4. Update the composable empty card to look centered and intentional on Retroid, not like a debug placeholder wearing lipstick.
5. Add source guards for CTA order.

**Focused gate:**
```bash
./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest --tests com.papi.nova.ui.NovaLibraryUiStateTest --tests com.papi.nova.ui.NovaComposeSourceGuardTest
```

**Suggested commit:** `feat(ui): clarify library empty states`

## Task 5: Add a concise Nova system menu sheet

**Objective:** create a clear top-level system/account surface separate from browsing filters.

**Files:**
- Modify: `app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`

**Scope:**
- Top-level rows should stay short and self-hosted:
  - `Settings`
  - `Polaris sync`
  - `Manage server`
  - `Help / diagnostics`
  - `About Nova`
- Header should show active server/host and online/offline/Polaris readiness from existing state where available.
- Keep destructive session/end actions out of this menu unless protected by confirmation.

**Steps:**
1. Add state for `activeSystemMenu` near the existing filter sheet state.
2. Build `NovaSystemMenuSheet` using existing panel/sheet visual language.
3. Wire Menu hint/action to open it.
4. Add source guard for menu rows and Back/B dismissal.
5. Keep it shallow; if a row needs a full workflow, route to existing settings/sync surfaces.

**Focused gate:**
```bash
./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest --tests com.papi.nova.ui.NovaComposeSourceGuardTest
```

**Suggested commit:** `feat(ui): add library system menu`

## Task 6: Settings Retroid hierarchy polish

**Objective:** make Nova settings feel as readable and controller-aware as GameNative’s sectioned list while preserving Nova’s richer search/category system.

**Files:**
- Modify: `app/src/main/java/com/papi/nova/preferences/NovaSettingsScreen.kt`
- Modify: `app/src/main/java/com/papi/nova/preferences/NovaSettingsUiState.kt` only if state support is needed
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/papi/nova/preferences/NovaSettingsUiStateTest.kt`
- Test: `app/src/test/java/com/papi/nova/preferences/NovaSettingsDefinitionsTest.kt`
- Test: `app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt`

**Scope:**
- Keep Nova’s current category rail/search/quick strip architecture.
- Add controller hint bar from Task 3.
- Ensure row title/subtitle/control hierarchy stays readable at Retroid landscape density.
- Make dangerous/debug actions visually secondary and clearly labeled.

**Steps:**
1. Review current wide layout (`screenWidthDp >= 720`) on Retroid.
2. Add or adjust source guards for category rail width, row focus motion, and search not trapping D-pad input.
3. Apply minimal density/spacing/focus changes.
4. Keep the legacy settings action available but not visually dominant.

**Focused gate:**
```bash
./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest --tests com.papi.nova.preferences.NovaSettingsUiStateTest --tests com.papi.nova.preferences.NovaSettingsDefinitionsTest --tests com.papi.nova.ui.NovaComposeSourceGuardTest
```

**Suggested commit:** `feat(ui): polish settings controller hierarchy`

## Task 7: Retroid, Pixel, and TV verification pass

**Objective:** prove the new shell feels good on real handheld constraints, not just “compiled therefore blessed.”

**Files/artifacts:**
- Retroid automation: `tools/nova_retroid_smoke.py`
- Retroid screenshots/XML/report files in `/tmp/nova_retroid_smoke/nova_retroid_*`
- Update `docs/ui-ux-backlog.md` visual check log after verification

**Commands:**
```bash
./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest
./gradlew -PnovaAbis=x86_64 -PlintFailOnError=true lintNonRoot_gameDebug
./gradlew -PnovaAbis=arm64-v8a assembleNonRoot_gameDebug
adb -s <adb-serial> install -r -d app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk
python3 tools/nova_retroid_smoke.py library --serial <adb-serial> --artifacts-dir /tmp/nova_retroid_smoke
python3 tools/nova_retroid_smoke.py live-stream --serial <adb-serial> --artifacts-dir /tmp/nova_retroid_smoke --launch-text "Steam Big Picture"
```

**Retroid smoke checklist:**
1. Library first paint: grid remains primary; hint bar does not clip cards or rail actions.
2. D-pad: initial focus, rail/actions, Options sheet, System menu, cards, detail sheet, Back/B dismissal.
3. Search: D-pad focus does not summon/trap IME; explicit edit still works.
4. Empty states: source/no recent/offline/no games have direct CTAs and readable copy.
5. Settings: category rail, rows, quick strip, search, Back behavior.
6. Live stream smoke when Retroid/host is available: launch a known safe target, verify active stream UI, Command Center, Quick Keys, normal End flow, crash/ANR scan.
7. Pixel/phone check: portrait and landscape density do not inherit Retroid-only assumptions.
8. TV/emulator check: focus rings visible, no overscan clipping, no hidden mouse-only actions.

**Final gates:**
```bash
git diff --check
git status --short --branch
```

**Suggested commit:** `test(ui): verify gamenative-inspired shell polish`

## Execution order summary

1. Stabilize current uncommitted UI slice.
2. Backlog note for GameNative findings.
3. Library Quick Options sheet.
4. Controller hint bar.
5. Empty/offline/source state polish.
6. System menu sheet.
7. Settings hierarchy pass.
8. Full Retroid/Pixel/TV verification with live stream smoke on Retroid when available.

## Non-negotiable acceptance criteria

- Nova still feels Nova/Polaris, not GameNative cosplay.
- Game selection remains visually dominant on Retroid landscape.
- Controller users can discover Options/System/Search without guessing.
- Every sheet/menu dismisses cleanly with Back/B.
- No new clipping at the bottom safe area or hint bar.
- Source/no-content states give one clear next action.
- Full unit/lint/assemble gates pass before completion.
- Retroid live stream smoke is run unless the Retroid/host is unavailable or explicitly skipped.
