# Live bitrate scope diagnostic

This read-only diagnostic observes the existing Android owner, rendered Picture row and exact-value form. It never refreshes status, sends a request, authorizes a change, changes a guard or saves a setting. Normal UI stays unchanged. It is off by default and requires both a debug build (`BuildConfig.DEBUG`) and an explicit `DEBUG` log level for `NovaBitrateScope`.

For a separately authorized device session, enable the tag **before opening Command Center**, collect its log, then disable the tag:

```sh
adb shell setprop log.tag.NovaBitrateScope DEBUG
adb logcat -v monotonic -s NovaBitrateScope:D '*:S'
# After collection:
adb shell setprop log.tag.NovaBitrateScope INFO
```

These are instructions only; source validation does not execute them or authorize device access. Reopen Command Center after enabling the tag. Each menu has a process-local diagnostic `menuId`, the actual menu validation generation and its own form revision. These identifiers convey no permissions.

Every JSON event has schema 1, emission revision/time, actual menu validation/showing/API/connection comparisons, an owner snapshot, and nullable captured Picture/form snapshots. Events come from Picture composition, opening the exact form, panel stack changes, existing owner/status collectors and menu closure; there is no new poller or timer. `formOnStack` uses page object identity, so another form with the same key does not revive a closed form. `formOnTop` describes the current panel top, not proof of pixels or physical input. Captured form state is retained as captured after it closes, and marked off-stack/off-top.

Owner fields describe current API/connection reference equality and stream activity, the most recently accepted status and its availability/authority/owner/app/generation comparisons, the actual local `streamRevision`/`inputRevision`, input equality with the last processed observation, and published token/rate/canChange/busy/units/bounds. Observation revision/time change only when the owner accepts an observation or retires; `observationElapsedMs` is null after retirement. Times use Android elapsed realtime milliseconds; host sequence is a host read sequence, not a clock. No freshness deadline is inferred.

`published*` values are the real current presentation, which may precede a supplier change. In particular, `publishedCanChange=true` alone is not current permission. `inputsMatchObservation=false` can accompany unchanged local revisions before the next owner observation. Captured tokens are compared literally with current local revisions; matching app/generation/fingerprint cannot replace a token or menu-lifetime comparison. A captured scope fingerprint/generation is null if its rendered token or current inputs/API/connection fail to align with the owner observation at capture. A current unavailable fingerprint never falls back to a captured value.

Unavailable values are explicit JSON null; false remains false. Units are `VIDEO`, `REQUEST` or `UNKNOWN`. Requested kbps may be null even when a numeric row minimum exists. Published and captured minimum/maximum are **UI row bounds**, including existing defaults and learned limits; they are not a claim that the host explicitly advertised those limits. No controller means null owner bounds. Unknown units or an unavailable current value must not be treated as a measured numeric bitrate. No authority, readiness or quality verdict is emitted.

Only booleans, numbers, closed enums, diagnostic IDs, revision pairs and an optional full scope digest are serialized. Raw app/session tokens, API keys, host instance, certificates, names, IPs, game/library contents, server reason strings and app data are excluded. The noncredential local revision pair is emitted literally; it cannot authorize a server request.

## Scope digest recipe

The full lowercase hexadecimal SHA256 digest is computed from this exact byte sequence:

1. ASCII `nova-live-bitrate-scope-v1`, followed by one zero byte.
2. `hostInstance`, then `appSessionId`, then the positive generation's base-10 decimal text, without leading zeroes.
3. Each of those three values is UTF8 encoded without normalization and prefixed by its byte length as a four-byte unsigned big-endian integer.

Nonblank host/app, positive generation and matching live-tuning versus top-level app/generation are required; otherwise the owner emits null. For host `host-α`, app `session/β`, generation `7`, the canonical bytes are:

```text
6e6f76612d6c6976652d626974726174652d73636f70652d76310000000007686f73742dceb10000000a73657373696f6e2fceb20000000137
```

The digest is `914fa66fea778731b6dd56771f5e2e9dfafb07ae607f9eaa7ac202321cccb268`. The test vector was independently computed with Python hashlib/struct.

At inspected Polaris `bb41143b39d0c5e5e90a4a51f63820fe7e448af2`, `live_tuning::snapshot` generates a process-local random UUID for `host_instance` (`src/live_tuning.cpp:34-43`). Normal paired status (`src/nvhttp.cpp:9558`) and admin stream stats (`src/confighttp.cpp:10988`) call that same producer. A separately admitted safe host-side projection can compute this recipe in memory to compare the same scope without exporting raw fields. This patch supplies no host collector and no observed cross-surface equality, requester permission, Root-owned launch provenance, physical UI acceptance, bitrate write or media/quality acceptance. A digest is diagnostic equality evidence only.
