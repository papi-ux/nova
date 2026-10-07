# Library route timing and observed shutdown

The library navigation driver emits one `NOVA_DECK_ROUTE_TIMING_V1` JSON record per app process. CTest retains these records in JUnit `system-out` and `LastTest.log`, including successful tests. Each record identifies the original route flags, the process phase, the configured watchdog, navigation completion and observed exit. Failed or prematurely exited processes keep unknown completion times unset.

The eight audited routes are filters, text readability, Spaces, setup parity, host scope, profile sync, Keep in step and library polish. Their initial process waits for two newly completed automatic library refreshes after navigation. Completion is observed after the Qt controller consumes each result; a request arriving at the HTTPS fixture alone does not establish completion. The driver checks the selected PC, destination, game and focus after each cycle, plus pairing identity and fixture request violations.

After these checks, the driver writes an atomic quit request for the exact process PID and a unique token. The app acknowledges that token and quits through the normal Qt event loop. A zero exit caused only by the watchdog cannot substitute for this acknowledgement. Restart phases retain their assertions and request an observed clean exit; the 600 ms persistence run retains its existing timer.

The explicit app hook requires a library observation path, a positive smoke watchdog, `--frontend-smoke-quit-file` and `--frontend-smoke-quit-token`. The private observation's `smokeRefresh` counters advance only for consumed automatic results. Each request file contains the expected `pid` and `token`; stale, partial or oversized requests are ignored. The acknowledgement is `NOVA_DECK_SMOKE_QUIT_ACCEPTED <token>` in the app log.

Every observed exit also checks the entire app log, unchanged identity and late fixture violations. A shutdown crash, a QML error or an unexpected late request must fail the route. The driver keeps the existing app watchdog values, per-state waits, CTest deadlines and negative throttle windows. Tests sharing `RESOURCE_LOCK nova_deck_x11` remain serialized.

For timing comparisons, retain exact source refs, build profile, registration inventory, actual execution logs and JUnit. Separate navigation, explicit refresh observation, requested quit and teardown. The old navigation-to-exit tail includes background work and teardown; its duration alone is not a measured saving. Compare first-attempt runs after all checks pass, and preserve the first failure before investigating source or fixture changes.

These loopback HTTPS and real QML window fixtures qualify UI behavior. Device-visible streaming and physical SteamOS transitions have separate acceptance gates.
