# Nova UI/UX Backlog

This backlog tracks the Retroid-first Nova 1.1.0 UI/UX re-review. Keep entries evidence-based and scoped so release-branch polish stays focused.

## Review context

- Branch under review: `nova/next-level-ui-polish` (1.1.0 polish stack)
- Primary device: Retroid Pocket 6
- Debug package: `com.papi.nova.debug`
- App metadata verified on device: `versionName=1.1.0`, `versionCode=26`
- Latest populated Retroid smoke APK: commit `49e01ce0` (`fix: make menu button target system drawer`), ARM64-only debug APK SHA-256 `250f4ec110ae13d5f9ab398ec8a8bb4c5864e9929e25e60079db1115437574e0`
- Latest populated Shield TV smoke APK: isolated pc-papi debug-key build from the same local branch state, ARM64-only debug APK SHA-256 `cc6ff1a0844e21c5418e56f74012be7e115dcfb259180800df844e046486f1e5`
- Latest populated Pixel phone smoke APK: commit `e6f1a85` (`docs: add populated Shield TV smoke evidence`), ARM64-only debug APK SHA-256 `42a573f44b50780fdd9d77902bfe97afb49c30588a46c0cf48d422d3d9e166e0`
- Latest local implementation checkpoint: commit `3105810` (`feat: add library recovery states`), Mac-built ARM64-only debug APK SHA-256 `7d82b41461feb752838ff3f6098f76b49d15184228dffe6ac112788096cae0c1`.
- Latest post-recovery Retroid smoke APK: pc-papi debug-key build from the synced local branch after `67b4452`, ARM64-only debug APK SHA-256 `bf12c375bcdd483a986790749c2a5cd6566bd869cc8ada90a2f62af215f852de`; installed base APK hash matched.
- Latest post-recovery Shield TV smoke APK: pc-papi debug-key build from the synced local branch after `67b4452`, ARM64-only debug APK SHA-256 `bf12c375bcdd483a986790749c2a5cd6566bd869cc8ada90a2f62af215f852de`; installed base APK hash matched.

## Completed in this pass

- Library focused backdrop falls back to visible filtered/recent games before leaving the backdrop blank.
- Library backdrop artwork loading can use the game-id loader fallback instead of requiring a non-empty cover URL.
- Game detail cover loading is keyed by game identity/cover URL to avoid stale reused artwork.
- Game detail primary Launch CTA is placed above mode choices/profile summary so it is visible on Retroid landscape.
- Settings rows, search, quick pills, and category rows use shared Nova focus motion with a stronger focus outline.
- Performance HUD width is capped while still allowing narrow parents to constrain the overlay.
- Launch mode drawer duplication was removed after Pixel 10 review: Headless/Virtual are now directly selectable in the game detail sheet, while non-duplicative tuning remains separate.
- MangoHUD is no longer a prominent launch-drawer switch; existing MangoHUD state is preserved for launch and only surfaces as a quiet status when already enabled.
- Library now has a compact Polaris-aware status strip ahead of counts in both Retroid rail and phone header layouts, showing Polaris readiness, AI quality state, launch mode, and resumable-session availability.
- Source guard coverage was added for the UI behaviors above.
- Stream startup now distinguishes launch progress from host-lock and input-ready states so transient black frames read as a guided handoff instead of a dead stream.
- Library shell now uses the two-zone GameNative-inspired model: `X` opens Library Options, `Menu` opens System, and `LB/RB` plus D-pad left/right hop between drawers.
- Library recovery states now route empty-library, source-filter-empty, recent-empty, offline/failed host, Polaris-unavailable, generic load failure, and failed-launch cases through one durable recovery-card model with one clear CTA per state.

## Retroid visual check log

| Surface | Evidence | Result | Notes |
| --- | --- | --- | --- |
| Library focused card/backdrop | Retroid screenshot pass | Passed | Readable/polished; no blocker-level clipping or contrast issue observed. |
| Game detail sheet | Retroid screenshot pass after CTA reorder | Passed | Primary Launch CTA is fully visible/readable above the nav bar. |
| Settings focused row | Retroid screenshot pass | Passed | Stronger focus treatment is readable on Retroid landscape. |
| Stream launch/session | Retroid launch smoke | Passed with note | Black Myth: Wukong stream entered `com.papi.nova.Game`, received video/audio packets, SSE reported `stream_active`, no Nova crash in package-specific scan. Screenshot was initially black while session was already active, then the game menu was visible during disconnect flow. |
| Stream startup confidence overlay | Retroid timed startup smoke | Passed | Startup showed explicit host-lock state instead of an ambiguous black screen; after unlock the stream rendered cleanly, Command Center opened over the stream, disconnect returned to `NovaLibraryActivity`, and crash log was empty. Logcat confirmed session progress overlay shown then dismissed on active stream. |
| Stream end/cleanup | Retroid quit confirmation | Passed | Confirming End returned focus to `NovaLibraryActivity`; Moonlight streams cleaned up; host cancel returned status 200; no Nova crash in package-specific scan. |
| Populated Library base grid | Retroid paired-launch smoke, 2026-05-23 | Passed with note | Real host endpoint returned 19 games; app showed `19 shown`, Polaris ready, and representative titles (`Indiana Jones and the Great Circle`, `Steam Big Picture`, `ARC Raiders`, `Grand Theft Auto V Enhanced`). Visual pass: first row is readable with real cover art; lower row is partially behind the persistent hint bar/viewport, which is acceptable for scrollable grid evidence but worth watching for perceived density. |
| Library Options drawer | Retroid `X` capture, 2026-05-23 | Passed with note | Left drawer showed search, refresh, filter chips, and sort rows. Visual pass: readable and correctly anchored; lower drawer content is clipped/scroll-dependent at the bottom but not a blocker. |
| System drawer | Retroid `Menu` capture, 2026-05-23 | Passed | Right drawer showed host/IP, Polaris/headless status, Switch host, Settings, Polaris sync, Manage server, Help/diagnostics, and About Nova without blocker-level clipping. |
| Drawer hopping | Retroid D-pad + shoulder capture, 2026-05-23 | Passed | `X` → D-pad right and `X` → `RB` landed on System; `Menu` → D-pad left and `Menu` → `LB` landed on Library Options; `B` and Back dismissed to the populated grid. |

## Android TV / Shield visual check log

| Surface | Evidence | Result | Notes |
| --- | --- | --- | --- |
| Populated Library base grid | Shield paired-launch smoke, 2026-05-23 | Passed | Projectivy/launcher entrypoint opened Nova to the paired `pc-papi.lan` Library with `19 shown`, Polaris ready, real cover art, and representative titles (`Steam Big Picture`, `ARC Raiders`, `Grand Theft Auto V Enhanced`, `Indiana Jones and the Great Circle`). Visual pass: 10-foot readability is strong, top Library/System affordances are not clipped, cover density is high but acceptable for TV, and the hint bar remains readable at the bottom. |
| Library Options drawer | Shield `X` capture, 2026-05-23 | Passed with note | Left drawer showed search, refresh, filter chips, sort rows, and layout controls without mixing in host/system actions. Visual pass: anchored left and readable; the drawer is intentionally scrollable and lower rows continue behind the bottom hint bar, so watch perceived bottom clipping during TV polish but not a release blocker. |
| System drawer | Shield `BUTTON_START` capture, 2026-05-23 | Passed | Right drawer showed host/IP, Polaris/headless status, Switch host, Settings, Polaris sync, Manage server, Help/diagnostics, and About Nova without mixing library filters. Visual pass: anchored right, readable, and free of blocker-level overscan/clipping. |
| Drawer hopping and dismissal | Shield D-pad + shoulder capture, 2026-05-23 | Passed | `X` → D-pad right and `X` → `RB` landed on System; `BUTTON_START` → D-pad left and `BUTTON_START` → `LB` landed on Library Options; `B` and Back dismissed overlays to the populated grid. Synthetic `KEYCODE_MENU` was not reliable on this Shield, so the validated System shortcut is `KEYCODE_BUTTON_START` / controller Menu. |
| Post-recovery Shield parity check | Shield paired-launch smoke, 2026-05-23 | Passed with stale-detail-oracle note | Fresh pc-papi-signed ARM64 build opened the paired 19-game Library; base grid, Library/System drawers, D-pad/shoulder hopping, and Back/B dismissal passed. Game detail first paint shows current `Launch controls`, primary `Launch 60 FPS`, and inline Headless/Virtual choices; the helper's old `Launch options`/`Review & Launch` detail markers are stale, not product regressions. Crash scan stayed clean. |

## Pixel phone visual check log

| Surface | Evidence | Result | Notes |
| --- | --- | --- | --- |
| Populated Library base grid | Pixel 10 Pro wireless-ADB paired-launch smoke, 2026-05-23 | Passed | Launcher opened the paired `pc-papi.lan` Library with `19 shown`, Polaris ready, real cover art, and representative titles (`Indiana Jones and the Great Circle`, `Steam Big Picture`, `ARC Raiders`, `Grand Theft Auto V Enhanced`, `Slay the Spire 2`). Visual pass: portrait density is strong, top Library/System touch affordances are readable, cover grid scrolls naturally, and the bottom controller hint bar is readable without blocking the active first paint. |
| Library Options drawer | Pixel touch capture, 2026-05-23 | Passed with note | Left drawer showed search, refresh, filters, sort rows, and layout controls without host/system actions. The primary filter row is horizontally scrollable on phone: first paint exposes All/Recent/Sources plus a partial HDR affordance, and a horizontal swipe revealed HDR and More. |
| System drawer | Pixel touch capture, 2026-05-23 | Passed | Right drawer showed host/IP, Polaris/headless status, Switch host, Settings, Polaris sync, Manage server, Help/diagnostics, and About Nova without mixing library filters. No blocker-level clipping or touch target issues observed. |
| Game detail / launch options sheet | Pixel touch capture, 2026-05-23 | Passed | Launch options first paint showed cover art, title metadata, direct Headless/Virtual choices, the primary Review & Launch CTA, launch profile summary, and reset control in portrait. The prior duplicate mode drawer concern remains resolved for phone. |
| Back dismissal / focus recovery | Pixel touch + Back capture, 2026-05-23 | Passed | Back dismissed Library Options, System, and launch options back to `NovaLibraryActivity` with the populated library still focused. Package-scoped logcat scan was clean. |

## Retroid smoke automation checkpoint

- 2026-05-23 populated Retroid two-zone drawer report: `/Users/papi/claude-hub/artifacts/nova/retroid-real-library/two_zone_20260523_163229/summary.json`
  - Host proof: `https://127.0.0.1:47984/polaris/v1/games?limit=100` returned HTTP 200 with 19 games using the Retroid app client certificate/key; representative titles included `Steam Big Picture`, `ARC Raiders`, `Grand Theft Auto V Enhanced`, and `Indiana Jones and the Great Circle`.
  - Device/app proof: Retroid Pocket 6 serial `24c12bdd`, `com.papi.nova.debug`, `versionName=1.1.0`, `versionCode=26`, `primaryCpuAbi=arm64-v8a`, `lastUpdateTime=2026-05-23 16:25:37`; installed APK hash matched `250f4ec110ae13d5f9ab398ec8a8bb4c5864e9929e25e60079db1115437574e0`.
  - Marker result: base grid, Library Options drawer, System drawer, D-pad drawer hops, shoulder drawer hops, and Back/B dismissal all passed.
  - Crash scan: no `FATAL EXCEPTION`, ANR, native crash, or package crash; three legacy `computers*.db` migration probe errors were logged and classified as non-crash noise.
  - Artifacts: `base_populated_grid.png/xml`, `left_library_drawer_x.png/xml`, `right_system_drawer_menu.png/xml`, `hop_left_to_right_dpad.png/xml`, `hop_left_to_right_r1.png/xml`, `hop_right_to_left_dpad.png/xml`, `hop_right_to_left_l1.png/xml`, `after_b_dismiss.png/xml`, `after_back_dismiss.png/xml`, `logcat_since.txt`, `crash_scan.json`.
  - Caveat: direct `NovaLibraryActivity` host-only starts can show `0 shown` even when the paired host has games; use the paired launcher/host path for release evidence so UUID/certificate/server context is intact.
- 2026-05-23 post-recovery Retroid drawer regression smoke: `/Users/papi/claude-hub/artifacts/nova/retroid-real-library/two_zone_20260523_184824_post_recovery/summary.json`
  - Device/app proof: Retroid Pocket 6 serial `24c12bdd`, `com.papi.nova.debug`, `versionName=1.1.0`, `versionCode=26`, `primaryCpuAbi=arm64-v8a`, `lastUpdateTime=2026-05-23 18:47:02`; installed base APK SHA-256 matched `bf12c375bcdd483a986790749c2a5cd6566bd869cc8ada90a2f62af215f852de`.
  - Marker result: populated base grid, `X` Library Options drawer, controller Menu/System drawer via `KEYCODE_BUTTON_START`, D-pad drawer hops, shoulder drawer hops, and Back/B dismissal all passed after the empty/offline/error-state slice.
  - Crash scan: no `FATAL EXCEPTION`, ANR, native crash, or package crash; three legacy `computers*.db` migration probe errors were logged and classified as non-crash noise.
  - Visual pass: real 19-game grid stayed readable, Library/System affordances remained visible, and both translucent drawers were readable without blocker-level clipping. The lower grid row and lower drawer content remain scroll/hint-bar-adjacent, matching the prior accepted caveat.
  - Artifacts: `base_populated_grid.png/xml`, `left_library_drawer_x.png/xml`, `right_system_drawer_start_fallback.png/xml`, `hop_left_to_right_dpad.png/xml`, `hop_left_to_right_r1.png/xml`, `hop_right_to_left_dpad.png/xml`, `hop_right_to_left_l1.png/xml`, `after_b_dismiss.png/xml`, `after_back_dismiss.png/xml`, `device_proof.json`, `logcat_since.txt`, `crash_scan.json`.
- 2026-05-23 populated Shield TV two-zone drawer report: `/Users/papi/claude-hub/artifacts/nova/shield-tv/two_zone_20260523_171155/shield_startseq_two_zone_summary.json`
  - Device/app proof: Shield TV serial `10.0.0.6:5555`, `com.papi.nova.debug`; Mac-built APK could not replace the existing install because the Shield package was signed by pc-papi's debug key, so the validation APK was built in an isolated `/tmp/nova-shield-build-current` copy on pc-papi and installed without clearing app data. Installed APK artifact hash: `cc6ff1a0844e21c5418e56f74012be7e115dcfb259180800df844e046486f1e5`.
  - Marker result: paired Library base grid, Library Options drawer, System drawer, D-pad drawer hops, shoulder drawer hops, and Back/B overlay dismissal all passed over a populated 19-game library.
  - Crash scan: no `FATAL EXCEPTION`, ANR, native crash, or package crash after install/smoke. Observed non-crash noise: Shield package-replace broadcast warnings, ActivityTaskManager pause timeouts from invalid direct `NovaLibraryActivity` probes, and legacy `computers*.db` migration probe errors.
  - Artifacts: `shield_startseq_base.png/xml`, `shield_startseq_left_x.png/xml`, `shield_startseq_right_start.png/xml`, `shield_startseq_left_to_right_dpad.png/xml`, `shield_startseq_left_to_right_r1.png/xml`, `shield_startseq_right_to_left_dpad.png/xml`, `shield_startseq_right_to_left_l1.png/xml`, `shield_startseq_after_b_from_system.png/xml`, `shield_startseq_after_back_from_system.png/xml`, `shield_startseq_after_b_from_left.png/xml`, `shield_startseq_after_back_from_left.png/xml`, `shield_log_scan.json`, and `shield_logcat_since_install.txt`.
  - Caveat: direct `am start` of `NovaLibraryActivity` on Shield returned to Projectivy Launcher; use the actual launcher/PcView paired path for TV release evidence. Synthetic `KEYCODE_MENU` did not open System in ADB, but `KEYCODE_BUTTON_START` and controller shoulder hopping validated the intended Menu/System path.
- 2026-05-23 post-recovery Shield TV parity smoke: `/Users/papi/claude-hub/artifacts/nova/shield-tv/two_zone_20260523_191548_post_recovery/README.md`
  - Device/app proof: Shield TV serial `10.0.0.6:5555`, `com.papi.nova.debug`, `versionName=1.1.0`, `versionCode=26`, `primaryCpuAbi=arm64-v8a`, `lastUpdateTime=2026-05-23 19:09:34`; installed base APK SHA-256 matched `bf12c375bcdd483a986790749c2a5cd6566bd869cc8ada90a2f62af215f852de`.
  - Marker result: paired populated base grid, `X` Library Options drawer, controller Menu/System drawer via `KEYCODE_BUTTON_START`, D-pad drawer hops, shoulder drawer hops, and Back/B dismissal all passed after the recovery-state slice.
  - Crash scan: no `FATAL EXCEPTION`, ANR, native crash, or package crash markers after the post-recovery Shield smoke.
  - Visual pass: real 19-game TV grid stayed readable; Library/System affordances and bottom hint bar were visible; left/right drawers were anchored/readable without blocker-level overscan; game detail first paint showed `Launch controls`, `Launch 60 FPS`, Headless, and Virtual.
  - Caveat: the detail-state automation expected old `Launch options`/`Review & Launch` labels, so its detail subcheck false-failed; the product UI and XML evidence match the current post-recovery design.
- 2026-05-23 populated Pixel phone smoke report: `/Users/papi/claude-hub/artifacts/nova/pixel-phone/two_zone_20260523_174704/manual/manual_summary.json`
  - Device/app proof: Pixel 10 Pro wireless ADB serial `adb-56250DLCH001S3-8VvuUE._adb-tls-connect._tcp`, `com.papi.nova.debug`, `versionName=1.1.0`, `versionCode=26`, `primaryCpuAbi=arm64-v8a`, `lastUpdateTime=2026-05-23 17:47:06`; installed base APK SHA-256 matched `42a573f44b50780fdd9d77902bfe97afb49c30588a46c0cf48d422d3d9e166e0`.
  - Marker result: paired Library base grid, left Library Options drawer, right System drawer, touch Back dismissal from both drawers, and game detail launch options first paint all passed over a populated 19-game library.
  - Crash scan: no `FATAL EXCEPTION`, ANR, native crash, or package crash after the manual phone smoke window.
  - Artifacts: `base_library.png/xml`, `left_library_options.png/xml`, `left_library_options_filter_scrolled.png/xml`, `right_system.png/xml`, `launch_options_sheet.png/xml`, `after_*_back.png/xml`, `logcat_since_manual.txt`, and `pixel_package_info.txt`.
  - Caveat: the current `tools/nova_retroid_smoke.py phone` helper still carries old dashboard/base-library label expectations and reported a false `FAIL`; use the manual two-zone phone artifact set above until the helper oracle is updated for paired-launch-to-Library and drawer-owned filters.
- 2026-05-22 automation reports were local historical checkpoints for this shell polish stack, not durable release evidence for the current hardened helper.
- Historical Retroid library report: `/tmp/nova_retroid_smoke/nova_retroid_library_20260522_201919.txt`
  - Previous-oracle result: `status=PASS`, `missing=[]`, `failures=[]`, `rail_right=539`, `hint_left=590`, `hint_gap=51`.
  - Caveat: these geometry values came from the pre-review helper and should be treated as old-oracle evidence only. Rerun the current drawer-first helper before using this as release proof.
  - Artifacts: `nova_retroid_library_20260522_201919.png`, `.xml`, and `_after_dpad.xml` in the same directory.
- Historical live stream automation report: `/tmp/nova_retroid_smoke/nova_retroid_live_stream_20260522_201959.txt`
  - Previous-oracle result: `status=PASS`, `missing=[]`, `failures=[]`, `stream_active=True`, `video_stream_started=True`, `audio_stream_started=True`, `clean_disconnect=True`, `quick_keys_top=149`, `touch_controls_visible=True`.
  - Artifacts: `nova_retroid_live_stream_20260522_201959_library.xml`, `_stream.png`, `_stream.xml`, `_command_center.png`, `_command_center.xml`, `_command_center_controls.xml`, `_end_attempt_*`, and `_end_confirm.xml` in the same directory.
- Helper-test checkpoint before review hardening: local commit `4b65537 test(retroid): stabilize landscape smoke automation` reported 11 helper tests OK. Review hardening expanded the helper suite to cover required hint bars, focused-node parsing, live-stream log evidence, dry-run ADB skipping, repo-root defaults, and stateful End cleanup.
- Historical Shield TV library smoke report: `/tmp/nova-shield-smoke-20260522_220610-settled/nova_retroid_library_20260522_220612.txt`
  - Previous-oracle result: `status=PASS`, `missing=[]`, `failures=[]`, `rail_right=468`, `hint_left=512`, `hint_gap=44`, `focused_after_dpad=(41, 705, 255, 803)`.
  - Artifacts copied locally under `/tmp/nova-shield-smoke-20260522_220610-settled/`: screenshot, UI XML, and `_after_dpad.xml`.
  - Caveat: this Shield reported `0 Games`, so this checkpoint proves TV library shell/focus/hint layout only; launch/live-stream smoke still needs a populated library target before release blessing.
- Caveat: `/tmp` artifacts are ephemeral. Copy the report set to a durable release archive and rerun the current helper before any final release handoff.

## Validation log

| Check | Result | Notes |
| --- | --- | --- |
| Focused source guard test | Passed | `./gradlew -PnovaAbis=x86_64 :app:testNonRoot_gameDebugUnitTest --tests 'com.papi.nova.ui.NovaComposeSourceGuardTest'` |
| Full unit tests | Passed | `./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest` |
| Lint | Passed | `./gradlew -PnovaAbis=x86_64 -PlintFailOnError=true lintNonRoot_gameDebug` |
| Retroid APK install | Passed | ARM64 debug APK built from `49e01ce0`; device installed `versionName=1.1.0`, `versionCode=26`, `primaryCpuAbi=arm64-v8a`, and installed base APK SHA-256 matched `250f4ec110ae13d5f9ab398ec8a8bb4c5864e9929e25e60079db1115437574e0`. |
| Package crash scan | Passed | No `FATAL EXCEPTION`, package ANR, native crash, or `Process: com.papi.nova.debug` crash found after the 2026-05-23 populated two-zone drawer smoke. Legacy `computers*.db` migration probe errors were non-crash noise. |
| Retroid populated two-zone drawer smoke | Passed | Durable artifacts under `/Users/papi/claude-hub/artifacts/nova/retroid-real-library/two_zone_20260523_163229/`; marker checks passed for populated grid, `X` Library drawer, `Menu` System drawer, D-pad/shoulder drawer hopping, and Back/B dismissal. |
| Shield TV populated two-zone drawer smoke | Passed | Durable artifacts under `/Users/papi/claude-hub/artifacts/nova/shield-tv/two_zone_20260523_171155/`; paired launcher/PcView path showed `19 shown`, marker checks passed for `X` Library drawer, `BUTTON_START` System drawer, D-pad/shoulder drawer hopping, and Back/B dismissal; package crash scan was clean. |
| Pixel phone populated two-zone smoke | Passed with helper caveat | Durable artifacts under `/Users/papi/claude-hub/artifacts/nova/pixel-phone/two_zone_20260523_174704/`; paired launcher opened `19 shown`, touch Library/System drawers passed, Back dismissed overlays, launch options first paint was usable, and package crash scan was clean. The reusable phone helper currently has a stale oracle and false-failed before the manual two-zone pass. |
| Library recovery states unit/source guards | Passed | `./gradlew -PnovaAbis=x86_64 :app:testNonRoot_gameDebugUnitTest --tests 'com.papi.nova.ui.NovaLibraryUiStateTest' --tests 'com.papi.nova.ui.NovaComposeSourceGuardTest'` passed after the recovery-state implementation. |
| Local implementation gate | Passed | `./gradlew -PnovaAbis=x86_64 :app:testNonRoot_gameDebugUnitTest :app:lintNonRoot_gameDebug :app:assembleNonRoot_gameDebug` passed; `./gradlew -PnovaAbis=arm64-v8a :app:assembleNonRoot_gameDebug` passed; `git diff --check` passed; staged added-line credential scan reported clean before commit `3105810`. |
| Post-recovery Retroid install/smoke | Passed | Synced the local branch to pc-papi, built/installed with pc-papi's debug key using `ANDROID_SERIAL=24c12bdd ./gradlew -PnovaAbis=arm64-v8a :app:assembleNonRoot_gameDebug :app:installNonRoot_gameDebug --no-daemon --console=plain`, then ran paired-launch Retroid drawer smoke. Installed APK hash matched `bf12c375bcdd483a986790749c2a5cd6566bd869cc8ada90a2f62af215f852de`; all drawer/base/dismiss markers passed and package crash scan was clean. |
| Post-recovery Shield TV install/smoke | Passed with stale-detail-oracle caveat | Synced the local branch to pc-papi, built/installed with pc-papi's Shield debug key using `ANDROID_SERIAL=10.0.0.6:5555 ./gradlew -PnovaAbis=arm64-v8a :app:assembleNonRoot_gameDebug :app:installNonRoot_gameDebug --no-daemon --console=plain`, then ran paired-launch Shield parity smoke. Installed APK hash matched `bf12c375bcdd483a986790749c2a5cd6566bd869cc8ada90a2f62af215f852de`; base grid, Library/System drawers, D-pad/shoulder hops, Back/B dismissal, visual review, and package crash scan passed. The detail subcheck false-failed only because it expected old `Launch options`/`Review & Launch` labels; current first paint shows `Launch controls`, `Launch 60 FPS`, Headless, and Virtual. |

## Deferred / not worth doing for 1.1.0

| Surface | Decision | Reason |
| --- | --- | --- |
| Additional stale UI branch merging | Defer wholesale merges | The useful polish from `nova/ui-004`, `nova/ui-007`, and `nova/ui-009` was audited and rebuilt selectively; merging stale branches risks regressing the release branch. |
| Deeper long-session performance validation | Defer to release/hardware soak | The smoke confirmed launch, decode setup, active stream, and cleanup. Longer performance characterization is outside this focused UI polish pass. |

## Future backlog

| Priority | Surface | Finding | Proposed follow-up |
| --- | --- | --- | --- |
| High | Game detail launch mode drawer | Pixel 10 review says the launch mode drawer felt clunky. Detail sheet already showed Headless/Virtual mode pills while Launch Options opened a drawer with the same selections, so the flow was duplicative. | Completed locally: Headless/Virtual are direct inline choices and Launch Options no longer duplicates mode selection. Keep watching the feel on Pixel/Retroid before release. |
| Medium | Game detail MangoHUD prominence | MangoHUD is a specialized/debug overlay and competes with NovaHUD as the normal in-stream overlay path. | Completed locally: removed the prominent launch-drawer switch while preserving existing MangoHUD launch state and showing only a passive enabled status. |
| Medium | Stream startup capture | First screenshot after launch can be black even though logs show stream active and packets arriving. | Completed locally: stream startup now exposes explicit progress/host-lock/input-ready state; Retroid smoke showed lock overlay clarity, clean unlock into a usable stream, Command Center over-stream behavior, clean disconnect, and no crash-buffer entries. If users still report black-screen starts, collect a timed screenshot/video trace and correlate with decoder output/first rendered frame timing. |
| Low | Command Center | End flow uses a second quit confirmation after tapping End. | Keep unless user feedback says it feels redundant; it protects against accidental session termination. |

## 1.1.0 next UI/UX pass

Direction locked for this larger 1.1.0 release: ship the current Nova app updates, lean the long-term identity toward a console/game launcher, treat Android TV/controller parity as a release gate, and do the bigger library home/hero redesign now rather than deferring it.

### GameNative Retroid reference sweep

Live reference sweep captured from GameNative on the Retroid is preserved in `docs/plans/2026-05-22-gamenative-inspired-nova-console-shell.md`. Useful patterns for Nova: a fast library Options surface for sort/layout choices, an explicit controller hint bar, source-specific empty states with direct CTAs, a short system/account menu separate from browsing filters, and sectioned settings rows with readable title/subtitle/control hierarchy. Caveats: D-pad probing surfaced GameNative's Ko-fi/share dialog, and touch Search/Add Game entry points were not fully validated during the sweep. Nova should borrow the console-shell patterns, not GameNative's store/account/debug assumptions.

| Priority | Surface | Why it matters | Suggested scope |
| --- | --- | --- | --- |
| High | Pixel phone layout review | The 1.1.0 polish was Retroid-first, but the Pixel 10 install is the best check for touch ergonomics, portrait/landscape density, and whether the new status strip/detail controls feel natural on a phone. Pixel in-game screenshot showed the Command Center as a large bottom sheet that appeared visually high/floating and slightly off-center in landscape (`~580px` left margin vs `~407px` right margin on a `2410x1080` capture). | Implemented locally: in-stream Command Center now uses an anchored left-side drawer with bounded width, full-height panel, slide-in motion, and scrim-tap dismissal; pre-stream game/server detail surfaces remain bottom sheets. GameNative reference check (`app.gamenative.ui.component.QuickMenu`) supports this direction: their in-game menu is a left-edge full-height drawer with adaptive width, transparent/scrim dismiss area, horizontal slide animation, and header close affordance. Future optimizations worth considering: add an explicit close button in Nova’s header, add a lightweight section/tab rail only if the vertical list gets too long, set controller initial focus intentionally, and keep destructive session actions stable/protected near the lower session group. |
| High | Library home/hero redesign | 1.1.0 should feel like a first-party console launcher, not only a polished grid. The library needs a stronger home surface around active sessions, recent games, hero artwork, and clear next actions. | Redesign the library hierarchy around Continue/Resume, recent games, richer hero/backdrop fallback, polished empty/offline states, and one obvious primary action. Keep D-pad focus and Retroid density as hard constraints. |
| High | Stream startup visual feedback | Retroid smoke saw a black first capture while logs already showed stream active and packets arriving. Even if transient, users read black frames as failure. | Implemented locally: lightweight startup state now distinguishes connecting/native setup, stream active/waiting, host locked, and input-ready dismissal. Retroid smoke verified explicit lock-state clarity, clean unlock to usable stream, Command Center over-stream behavior, clean disconnect, and empty crash buffer. |
| High | Android TV / controller focus parity | This larger 1.1.0 release should not regress couch/D-pad use while leaning more console/game launcher. TV layouts can expose different focus-order and overscan issues than Retroid. | Run a TV/emulator D-pad gate: initial focus, rail/navigation, cards, detail sheet, settings, Command Center, Back/B dismissal, and clipped focus rings. Fix focus traps/clipping before release. |
| Medium | Command Center in-stream hierarchy | NovaHUD is the normal overlay path, while advanced/debug controls should stay secondary. The current Back-dismiss fix helps, but the drawer still needs a touch/controller feel pass. | Review Command Center rows on Pixel, Retroid, and TV: make End/Overlay/NovaHUD readable and reachable, keep destructive End protected, and avoid promoting MangoHUD/debug toggles above common actions. |
| Medium | Command Center / NovaHUD aesthetic polish | The Library/System drawers now have the subtle translucent console-shell look that should become Nova's visual language instead of stopping at the library shell. | Future pass: extend the same glassy/translucent anchored-drawer treatment to the in-game Command Center, then explore NovaHUD optimization around lower visual noise, readable critical stats, and clearer separation between player-facing overlay state and debug/perf details. |
| Medium | Empty/loading/error states | The library polish improves happy-path presence, but public users will hit no-host, offline Polaris, empty library, stale artwork, and failed launch states first. | Implemented locally in `3105810`: tightened copy and one clear CTA for empty library, source-filter-empty, recent-empty, offline/failed host, Polaris-unavailable, generic load failure, and failed launch. Needs fresh device smoke before final release blessing. |
| Low | Motion and transition consistency | The new focus motion and sheets feel more Nova, but inconsistent transitions can make polish feel uneven. | Audit animation durations/easing for library cards, sheets, settings rows, and Command Center; centralize constants only where duplication is obvious. |
| Low | Accessibility/readability pass | Compact handheld UI can regress font scale, contrast, and TalkBack labels. | Check large font scale, touch target size, important content descriptions, and color contrast for the new status strip/detail controls. |
