# Nova UI/UX Backlog

This backlog tracks the Retroid-first Nova 1.1.0 UI/UX re-review. Keep entries evidence-based and scoped so release-branch polish stays focused.

## Review context

- Branch under review: `nova/1.1.0`
- Primary device: Retroid Pocket 6
- Debug package: `com.papi.nova.debug`
- App metadata verified on device: `versionName=1.1.0`, `versionCode=26`

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

## Retroid visual check log

| Surface | Evidence | Result | Notes |
| --- | --- | --- | --- |
| Library focused card/backdrop | Retroid screenshot pass | Passed | Readable/polished; no blocker-level clipping or contrast issue observed. |
| Game detail sheet | Retroid screenshot pass after CTA reorder | Passed | Primary Launch CTA is fully visible/readable above the nav bar. |
| Settings focused row | Retroid screenshot pass | Passed | Stronger focus treatment is readable on Retroid landscape. |
| Stream launch/session | Retroid launch smoke | Passed with note | Black Myth: Wukong stream entered `com.papi.nova.Game`, received video/audio packets, SSE reported `stream_active`, no Nova crash in package-specific scan. Screenshot was initially black while session was already active, then the game menu was visible during disconnect flow. |
| Stream startup confidence overlay | Retroid timed startup smoke | Passed | Startup showed explicit host-lock state instead of an ambiguous black screen; after unlock the stream rendered cleanly, Command Center opened over the stream, disconnect returned to `NovaLibraryActivity`, and crash log was empty. Logcat confirmed session progress overlay shown then dismissed on active stream. |
| Stream end/cleanup | Retroid quit confirmation | Passed | Confirming End returned focus to `NovaLibraryActivity`; Moonlight streams cleaned up; host cancel returned status 200; no Nova crash in package-specific scan. |

## Validation log

| Check | Result | Notes |
| --- | --- | --- |
| Focused source guard test | Passed | `./gradlew -PnovaAbis=x86_64 :app:testNonRoot_gameDebugUnitTest --tests 'com.papi.nova.ui.NovaComposeSourceGuardTest'` |
| Full unit tests | Passed | `./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest` |
| Lint | Passed | `./gradlew -PnovaAbis=x86_64 -PlintFailOnError=true lintNonRoot_gameDebug` |
| Retroid APK install | Passed | `./gradlew -PnovaAbis=arm64-v8a assembleNonRoot_gameDebug` then `adb -s 24c12bdd install -r -d app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk` |
| Package crash scan | Passed | No `FATAL EXCEPTION`, package ANR, or `Process: com.papi.nova.debug` crash found after UI and stream smoke passes. |

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

| Priority | Surface | Why it matters | Suggested scope |
| --- | --- | --- | --- |
| High | Pixel phone layout review | The 1.1.0 polish was Retroid-first, but the Pixel 10 install is the best check for touch ergonomics, portrait/landscape density, and whether the new status strip/detail controls feel natural on a phone. Pixel in-game screenshot showed the Command Center as a large bottom sheet that appeared visually high/floating and slightly off-center in landscape (`~580px` left margin vs `~407px` right margin on a `2410x1080` capture). | Implemented locally: in-stream Command Center now uses an anchored left-side drawer with bounded width, full-height panel, slide-in motion, and scrim-tap dismissal; pre-stream game/server detail surfaces remain bottom sheets. GameNative reference check (`app.gamenative.ui.component.QuickMenu`) supports this direction: their in-game menu is a left-edge full-height drawer with adaptive width, transparent/scrim dismiss area, horizontal slide animation, and header close affordance. Future optimizations worth considering: add an explicit close button in Nova’s header, add a lightweight section/tab rail only if the vertical list gets too long, set controller initial focus intentionally, and keep destructive session actions stable/protected near the lower session group. |
| High | Library home/hero redesign | 1.1.0 should feel like a first-party console launcher, not only a polished grid. The library needs a stronger home surface around active sessions, recent games, hero artwork, and clear next actions. | Redesign the library hierarchy around Continue/Resume, recent games, richer hero/backdrop fallback, polished empty/offline states, and one obvious primary action. Keep D-pad focus and Retroid density as hard constraints. |
| High | Stream startup visual feedback | Retroid smoke saw a black first capture while logs already showed stream active and packets arriving. Even if transient, users read black frames as failure. | Implemented locally: lightweight startup state now distinguishes connecting/native setup, stream active/waiting, host locked, and input-ready dismissal. Retroid smoke verified explicit lock-state clarity, clean unlock to usable stream, Command Center over-stream behavior, clean disconnect, and empty crash buffer. |
| High | Android TV / controller focus parity | This larger 1.1.0 release should not regress couch/D-pad use while leaning more console/game launcher. TV layouts can expose different focus-order and overscan issues than Retroid. | Run a TV/emulator D-pad gate: initial focus, rail/navigation, cards, detail sheet, settings, Command Center, Back/B dismissal, and clipped focus rings. Fix focus traps/clipping before release. |
| Medium | Command Center in-stream hierarchy | NovaHUD is the normal overlay path, while advanced/debug controls should stay secondary. The current Back-dismiss fix helps, but the drawer still needs a touch/controller feel pass. | Review Command Center rows on Pixel, Retroid, and TV: make End/Overlay/NovaHUD readable and reachable, keep destructive End protected, and avoid promoting MangoHUD/debug toggles above common actions. |
| Medium | Empty/loading/error states | The library polish improves happy-path presence, but public users will hit no-host, offline Polaris, empty library, stale artwork, and failed launch states first. | Tighten copy and CTAs for offline host, Polaris unavailable, no games, artwork fallback, and launch failure. Favor one clear next action per state. |
| Low | Motion and transition consistency | The new focus motion and sheets feel more Nova, but inconsistent transitions can make polish feel uneven. | Audit animation durations/easing for library cards, sheets, settings rows, and Command Center; centralize constants only where duplication is obvious. |
| Low | Accessibility/readability pass | Compact handheld UI can regress font scale, contrast, and TalkBack labels. | Check large font scale, touch target size, important content descriptions, and color contrast for the new status strip/detail controls. |
