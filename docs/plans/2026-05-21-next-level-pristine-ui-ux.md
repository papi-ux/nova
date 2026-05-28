# Next-Level Pristine UI/UX Plan

Goal: Make Nova 1.1.0 feel polished, confident, and console-native across library, stream startup, Android TV / D-pad, and command-center flows.

Storage note: this plan intentionally lives in docs/plans/, not .hermes/. Do not stage .hermes/ for this plan workflow.

## Current context

- Repo: `<repo-root>`
- Branch target: nova/1.1.0
- Preferred workflow: small verified slices, frequent commits, no giant UI glitter bomb.
- First rule: finish/commit the already verified UI slice before starting more polish.

## Task 1: Commit the current verified stream/library slice

Files expected in the current slice:
- app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt
- app/src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt
- app/src/main/java/com/papi/nova/ui/NovaStreamOverlayContent.kt
- app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt
- app/src/test/java/com/papi/nova/ui/NovaLibraryUiStateTest.kt
- app/src/test/java/com/papi/nova/ui/NovaStreamOverlayUiStateTest.kt

Steps:
1. Run git status --short --branch.
2. Run git diff --check.
3. Stage only the six source/test files above.
4. Commit: git commit -m "feat(ui): polish library hero and stream startup states"
5. Confirm .hermes/ is not staged.

## Task 2: Re-run release gates from the commit boundary

Run:
- ./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest
- ./gradlew -PnovaAbis=x86_64 -PlintFailOnError=true lintNonRoot_gameDebug
- ./gradlew -PnovaAbis=arm64-v8a assembleNonRoot_gameDebug

Expected: all exit 0 before new UI work starts.

## Task 3: Stream startup confidence polish

Objective: make startup stages explicit and reassuring: RTSP, control, video, audio, input, idle/preflight, and unknown fallback.

Likely files:
- app/src/main/java/com/papi/nova/ui/NovaStreamOverlayContent.kt
- app/src/test/java/com/papi/nova/ui/NovaStreamOverlayUiStateTest.kt

Focused gate:
- ./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest --tests com.papi.nova.ui.NovaStreamOverlayUiStateTest

Commit when green: feat(ui): clarify stream startup progress states

## Task 4: Library home and hero polish

Objective: make active sessions, filtered results, recent games, empty library, and Manage Library CTAs feel deliberate.

Likely files:
- app/src/main/java/com/papi/nova/ui/NovaLibraryActivity.kt
- app/src/main/java/com/papi/nova/ui/NovaLibraryUiState.kt
- app/src/test/java/com/papi/nova/ui/NovaLibraryUiStateTest.kt
- app/src/test/java/com/papi/nova/ui/NovaComposeSourceGuardTest.kt

Protect the landscape ordering: hero -> content/grid -> recent rail. Do not drop the Recent rail again, little goblin.

Focused gate:
- ./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest --tests com.papi.nova.ui.NovaLibraryUiStateTest --tests com.papi.nova.ui.NovaComposeSourceGuardTest

Commit when green: feat(ui): refine library hero hierarchy

## Task 5: Android TV / D-pad gate

Objective: verify couch usability: focus order, focus visibility, no trapped controls, no mouse-only affordances.

Steps:
1. Inspect focusable controls in Library and Command Center.
2. Verify D-pad traversal follows visual hierarchy.
3. Add source guard or unit coverage for fragile ordering if needed.
4. Run full unit gate.

## Task 6: Bounded Command Center hierarchy polish

Objective: improve hierarchy without redesigning the whole subsystem.

Constraints:
- One bounded polish slice.
- No architecture rewrite.
- Add tests/source guards only for durable state or ordering rules.

Commit when green: feat(ui): polish command center hierarchy

## Final verification

Before calling this complete, run:
- ./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest
- ./gradlew -PnovaAbis=x86_64 -PlintFailOnError=true lintNonRoot_gameDebug
- ./gradlew -PnovaAbis=arm64-v8a assembleNonRoot_gameDebug
- git diff --check
- git status --short --branch
