# Live Tuning

Live Tuning is the host's adaptive bitrate preference. It does not require an AI
provider or a subscription. Doctor explanations remain a separate feature.

Polaris 1.5.0 turns Live Tuning on by default. A host that upgrades gets it on
once, including a host that deliberately saved Off before 1.5.0. Turn it off
again afterwards and it stays off.

The switch in Quick Controls and Audio/Video settings saves immediately. Nova's
Command Center uses the same preference. Its HUD shows tuning separately from
stream health. A saved On preference can be waiting for a stream, measuring,
adjusting, applying a bitrate, stable, or unavailable for the current encoder.
Disconnected or invalid status is Unknown; it is not permission to change settings.

The displayed applied bitrate comes from the encoder acknowledgement, not the
requested target. Turning tuning off holds the last confirmed bitrate. Choosing
an explicit fixed live bitrate also turns tuning off for the rest of that stream
and supersedes a pending Doctor bitrate action; the host's saved preference stays
as it was. Auto, Quality, High FPS, and Stability launch presets still
apply at the next explicit launch.

The Performance and Debug HUD show a brief line when Live Tuning eases the
bitrate, such as "Eased to 14 Mbps", and "Back to full quality" when the
confirmed bitrate reaches the quality limit again. Lines come only from the status
Nova already reads. A pending request or a measuring stream never counts, a change
must hold for 2 seconds (10 seconds for full quality), and lines are at least 30
seconds apart. A new session, host restart, changed setting or quality limit, or a
gap in readings starts over without a line. A line lasts 4 seconds from when Nova
decided it, fades over 180 ms, and appears without fading when animations are off
or Reduce Motion is on. It never names a cause. Lines appear only for H.264, HEVC
and AV1, where Live Tuning's rate is the encoder's video bitrate; other codecs,
including PyroWave, show none.

## API contract

`GET /api/live-tuning` requires web administrator authentication. Its response
contains `status` and `live_tuning`. `POST /api/live-tuning` accepts exactly one
boolean field, `enabled`, and requires the quoted `configuration_revision` in
`If-Match`. Cookie-authenticated mutations require the normal CSRF token. A valid
configured bearer credential or an authorized verified client certificate bypasses
cookie CSRF validation; the endpoint still enforces its authentication checks.

A stale or absent revision returns HTTP 412 without changing the controller.
A failed durable commit returns an error without changing the preference. Refresh
the state before asking the user to retry; do not automatically replay the save.
`GET /api/config` returns contents and revision from one secure file snapshot.
Configuration saves preserve the live preference when it is omitted.

The existing paired `/polaris/v1/session/adaptive-bitrate` route accepts
`configuration_revision` alongside the existing application session ID and
generation. Its sole-owner, permission, revocation and session-generation checks
remain mandatory. Legacy paired callers may omit the revision; new Nova sends it.

The additive `live_tuning` version 1 object is shared by web statistics, paired
session status and session events. It contains:

- `enabled`, `scope` (`host`), `state`, `supported`, and `reason`;
- `runtime_enabled`, `pending`, `quality_limit_kbps`, `requested_bitrate_kbps`,
  and `applied_bitrate_kbps`;
- `app_session_id`, `session_generation`, `configuration_revision`,
  `host_instance`, and increasing `sequence`.

A shared encoder or a mismatched session cannot claim a supported live actuator
or another session's applied bitrate. Clients reject malformed values, old
sequences and retired host instances. The shared conformance examples are in
`tests/fixtures/live-tuning-v1.json` in Polaris and
`app/src/test/resources/live-tuning-v1.json` in Nova.

Session status advertises `events_https_port`. Nova uses that HTTPS endpoint with
its existing pinned authenticated client, disables redirects, cancels blocked
reads on teardown, and requests a fresh status after connection loss or an event
sequence gap. Events are snapshots, not a replay log. Old connection callbacks
cannot update a replacement session. Missing or invalid canonical tuning becomes
Unknown until a successful resynchronization.

## Validation boundary

Unit and isolated transport tests cover conditional saves, failed persistence,
configuration contention, encoder acknowledgement races, recreation followed by
disable, owner/session attribution, shared fixtures, and stale UI delivery.
Physical game streaming and device-specific encoder behavior remain separate
acceptance checks. This change does not enable container multiseat production.
