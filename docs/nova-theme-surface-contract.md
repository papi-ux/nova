# Nova Theme Surface Contract

This contract makes the Nova side of the Nova/Polaris cockpit requirements explicit. Treat it as a product guard for Android UI work, Retroid smoke runs, and Polaris pairing evidence.

## Smoke build pairing

- Use the latest available debug Nova APK by default for Retroid, Shield, Pixel, and paired-library smoke runs.
- Use that Nova debug APK with the latest available debug Polaris build or live debug Polaris host unless a release, pinned, or archival build is explicitly requested.
- Evidence must name the installed Nova package and APK hash or lastUpdateTime when available, and must not imply release coverage when the run used debug bits.

## Theme picker and selectable themes

- The picker must expose every selectable theme in a compact controller-friendly layout: Polaris Aurora, PSP Chrome / Portable Chrome, Console OLED, Miami Nebula, High Contrast, and Material You when the platform supports it.
- PSP Chrome / Portable Chrome must remain eye-scan visible as the primary label, with the PSP / Portable Chrome wording still discoverable in supporting copy.
- D-pad focus must be visible and readable, including a live focused theme label or equivalent text feedback.
- Keep only meaningful state badges such as Current. There should be no redundant Press A badges repeated per row.
- The compact layout must keep Material You reachable and visible instead of pushing it below the Retroid landscape fold.

## Shared drawer, sheet, and dialog chrome

- Drawers, sheets, dialogs, picker surfaces, host context menus, and game-detail surfaces are theme surfaces, not one-off panels.
- The selected theme should visibly apply across the picker, host/context drawers, and game-detail surfaces with distinct palette personality.
- Shared sheet chrome must preserve transparent/glass character for NovaHUD and gameplay-adjacent contexts.
- High Contrast may be more opaque for readability, but PSP Chrome / Portable Chrome and Miami Nebula should keep a transparent/glass feel rather than becoming opaque slabs.
- Avoid clipped selected strokes, square-vs-round drift, mixed purple overlays, or per-callsite sheet backgrounds that bypass shared theme tokens.

## PSP Chrome / Portable Chrome palette

- PSP Chrome / Portable Chrome should read as steel-blue/graphite, smoked graphite, and dim Moonlight-grey/silver shell chrome.
- Muted green/status accents are allowed for PSP Chrome / Portable Chrome and semantic online/status states.
- Purple/violet accents must not appear in PSP Chrome / Portable Chrome chrome, and PSP green must not leak into non-PSP selection, focus, host, or button accents.
- Text must stay readable on all PSP panels; avoid washed-out light panels with weak dark text or bright green body text.

## Regression expectations

- Source guards should cover theme registry order, PSP aliasing, per-theme accent tokens, shared transparent/glass sheet chrome, picker D-pad behavior, Material You visibility, and the no redundant Press A badges rule.
- Device evidence should include the picker, at least one host/context drawer, and a game-detail surface after applying the selected theme.
- When validating smoke behavior, prefer the latest available debug Nova APK plus latest available debug Polaris build pairing unless Michael explicitly asks for another build lane.
