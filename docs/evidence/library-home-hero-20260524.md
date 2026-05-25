# Nova Library home/hero evidence — 2026-05-24

Status: PASS for Retroid + Shield hardware evidence using the current local Library home/hero worktree.

## Artifact roots

- Evidence index: `/Users/papi/claude-hub/artifacts/nova/library-home-hero/README.md`
- Retroid: `/Users/papi/claude-hub/artifacts/nova/library-home-hero/retroid_20260524_131011`
- Shield TV: `/Users/papi/claude-hub/artifacts/nova/library-home-hero/shield_20260524_171021`
- Contact sheet: `/Users/papi/.hermes/image_cache/nova-library-home-hero-contact-sheet-20260524.jpg`
- Curated ZIP: `/Users/papi/.hermes/cache/documents/nova-library-home-hero-evidence-20260524.zip`

## What the evidence proves

- The same ARM64 debug APK SHA-256 (`91d7605efa926c99d9dc6ab9b24c8f03af800bcfa6d0f520d1033784537b4733`) installed and hash-matched on Retroid Pocket 6 and Shield TV without clearing paired data.
- Both devices reached a paired, populated Polaris-backed Library with 19 games.
- Library home/hero first paint is readable with Polaris ready state, controller hints, real grid cover art, and the hero/home surface visible.
- Two-zone controls stayed intact: `X` Library drawer, `Start/Menu` System drawer, shoulder/D-pad hopping, Back/B dismissal, and detail first paint.
- Retroid additionally covered `Y` layout cycle and screenshot-based focus traversal from header into the first grid card.
- Both bounded package crash scans were clean.

## Caveats

- No fresh Shield ADB interaction was run while this handoff was written; the Shield artifact was produced before the user directive not to touch the TV again today.
- Shield System evidence uses `KEYCODE_BUTTON_START`; synthetic `KEYCODE_MENU` remains unreliable on that device.
- Retroid `Steam Big Picture`/manual item uses expected fallback artwork, while other populated cards show real cover art.
- Logcat artifacts are redacted; no certificate/private-key material is intentionally preserved.

## Backlog linkage

`docs/ui-ux-backlog.md` now points the high-priority Library home/hero row at this completed local implementation/evidence set instead of leaving it as open planning work.
