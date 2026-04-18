# Changelog

## 2026-04-17

### Release and repo hygiene

- Tightened the public Obtainium configuration so it resolves directly to `app-nonRoot_game-arm64-v8a-release.apk` and tracks `v*` GitHub tags cleanly.
- Refreshed the public docs around the multi-platform repo layout, Steam Deck planning notes, and release/install guidance.
- Updated the Android CI workflows to use the current `setup-android` action and restored the emulator smoke test's hosted-runner KVM setup.

### Polaris-aware library and AI surfaces

- Added clearer Polaris-backed session labels across the library, quick menu, HUD, and detail surfaces:
  `Baseline`, `AI tune`, `Cached AI`, `Recovery tune`, and `Host adjusted`.
- Improved the host-specific Polaris library screen with a stronger featured `Continue` card:
  live/watch state, cover art, summary text, and a clearer primary action.
- Exposed richer Polaris session metadata in Nova so launch and in-stream UI can reflect source, confidence, freshness, and host-side normalization more accurately.

### Session reporting and grading alignment

- Extended Nova's Polaris reporting path so end-of-session data includes the target FPS used for host-side grading.
- Updated Polaris parsing coverage in Nova tests to keep the newer host metadata contract stable.
