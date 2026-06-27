# Deck product-readiness checklist

This checklist promotes the Deck diagnostics lane into a reusable product-readiness gate instead of another round of smoke-only cosmetics. Use it for every future diagnostics/read-only DTO card before claiming the shell is product-ready.

## Gate: deck-diagnostics-expanded-lane-v1

Status: active for Deck diagnostics lane work.

Pass criteria:

- collapsed first paint: secondary diagnostics stay collapsed until the operator expands them.
- page-1 cue: the first expanded diagnostics page shows the page-position affordance before long blocker copy.
- controller accessibility: the diagnostics toggle and expanded lane are reachable with controller/D-pad focus.
- focus affordance: expanded diagnostics preserve the 4px focus ring and active focus badge.
- D-pad scroll to page 2: the frontend smoke must prove controller/key navigation moves to lifecycle + DTO details.
- right-rail breathing room: collapsed readiness copy stays player-facing, and the expanded diagnostics lane keeps extra vertical room instead of cramming every backend/status token into first paint.
- page-2 cue contrast/readability: the page-2 cue stays readable at the recorded 13.56:1 contrast and does not overlap blocker copy.
- lifecycle idle/no stream: lifecycle detail remains idle/no stream while the smoke route is offline.
- sanitized DTO detail: diagnostics expose redacted-public-dto only.
- backend-fed DTO parity: collapsed summary, expanded diagnostics, and smoke artifacts expose the same backend-owned read-only DTO contract (`backend-owned-read-only-dto-v1`) with `dto-parity-ready` readiness, never raw backend fields.
- DTO-owned player state: title, body, action, safety, provenance, and focus-order copy come from the sanitized read-only DTO (`dto-player-state/backend-owned/redacted-public`) instead of QML fixture/debug branches.
- sanitized artifacts: frontend smoke artifacts contain no private addresses, PEM blocks, or raw* shaped fields.
- backendPowerStarted=false: all read-only matrix states must preserve backendPowerStarted=false.
- stream=false: dry-run/preview state must keep stream/network start disallowed.
- product state matrix: empty, offline, unpaired, library-unavailable, and lab-gated states must render as player-facing product states with a visible next action and safety reassurance before diagnostics.
- focus order: controller focus must make the product state card reachable before Copy plan and the secondary diagnostics toggle.

## Evidence required before pass

- `clients/deck/tests/deck_frontend_smoke_test.py` asserts the reusable gate appears in smoke summary output.
- `clients/deck/scripts/deck_frontend_smoke.py` writes `product_readiness_gate=deck-diagnostics-expanded-lane-v1`, `product_readiness_verdict=pass`, and `product_readiness_next=backend-fed-read-only-dto-parity` only after the existing expanded diagnostics assertions pass.
- `clients/deck/tests/deck_layout_test.cpp` keeps the QML source contract observable for collapsed first paint, page-position affordance, focus affordance, D-pad lane focus, page-2 copy, lifecycle/DTO labels, and privacy copy.
- `clients/deck/tests/deck_backend_interfaces_test.cpp` asserts every read-only matrix state carries product-safe DTO player-state fields plus redacted provenance/focus order, and that the backend read-only state provider owns matrix assembly/default player-state repair before Qt/QML consumption.
- `clients/deck/tests/deck_media_assert_guard_test.py` keeps raw backend/start symbols out of UI and stream-core surfaces.
- Real Deck gamescope smoke, when the Deck is reachable, must copy the artifact directory back under `build/deck-frontend-smoke-artifacts` and retain non-empty captures/smoke text.

## Non-goals for this gate

This gate must not add host scanning, pairing flows, secret storage access, app launch, media start, controller packets into an active session, real stream lab work, or raw start calls. If future backend cards need those capabilities, they need their own reviewed backend gate first. Tiny copy/QML changes are allowed only when they make the existing contract observable.

## Next-card direction

The next single-card recommendation is review-and-accept M28 evidence after this backend-fed read-only DTO parity slice, then consider a separate explicitly approved backend-fed data source spike that still does not start real streaming. Stop polishing diagnostics cosmetics unless a criterion above becomes unobservable or fails.
