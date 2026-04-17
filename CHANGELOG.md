# Changelog

## 2026-04-17

### Polaris-aware library and AI surfaces

- Added clearer Polaris-backed session labels across the library, quick menu, HUD, and detail surfaces:
  `Baseline`, `AI tune`, `Cached AI`, `Recovery tune`, and `Host adjusted`.
- Improved the host-specific Polaris library screen with a stronger featured `Continue` card:
  live/watch state, cover art, summary text, and a clearer primary action.
- Exposed richer Polaris session metadata in Nova so launch and in-stream UI can reflect source, confidence, freshness, and host-side normalization more accurately.

### Session reporting and grading alignment

- Extended Nova's Polaris reporting path so end-of-session data includes the target FPS used for host-side grading.
- Updated Polaris parsing coverage in Nova tests to keep the newer host metadata contract stable.
