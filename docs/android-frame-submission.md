# Android complete-DU diagnostics

The optional `sample.frame_submission` object observes complete encoded decode units
at `BridgeDrSubmitDecodeUnit`, once before NAL splitting, including decoder refusal.
Its mutex-coherent cursor, units, bytes and receiver BOOTTIME timestamps form a
separate population from Java performance counters. Missing frames here are absent
at decoder submission; these fields cannot establish network loss, encoder CBR,
available capacity or presentation completion. They provide no controller input.

Each native DR setup obtains a nonzero random 128-bit nonce and a positive,
nonwrapping process epoch. Entropy failure omits this evidence. The renderer binds
the epoch during setup, before native start. Stop and cleanup retire it. Duplicate,
regressing or wrapping cursors, invalid clocks and counter/sequence exhaustion
disable this evidence until a fresh DR setup. The existing three-field
`getVideoPayloadStats()` and payload HUD/scalar retain their byte domain and behavior.
Equal-millisecond snapshot polling is omitted without consuming sequence or retiring
the epoch; a later increasing timestamp can still produce a valid observation.

Every actual launch/resume HTTP attempt, including transparent HTTP retries, obtains
a separate nonzero random 128-bit `frameSubmissionLaunchNonce`. A network interceptor
adds the optional query without changing retry behavior. Nova retains identity only
after a successful response contains one strictly valid direct-root matching echo
and its session token. Absent/malformed echoes on old hosts preserve legacy launch.
The paired status must echo that same nonce from its active generation. The report's
required `launch_nonce` is captured from the successful connection, independently of
the native `connection_nonce`.

The renderer captures its actual producing connection and successful launch identity
during native setup. Game captures the immutable observation, API and connection identity, native epoch
and validated host owner/token/app/generation scope on the producing callback,
before queueing UI work. Scope transitions retire that optional telemetry lifetime,
including an A -> unavailable/B -> A transition, permanently for that native epoch.
Initial unavailable status can wait for the first exact launch-nonce match. After
pinning, that epoch cannot bind another host generation or nonce. Immediately before HTTP body
construction, the upload rechecks connection, epoch, scope lifetime, request scope,
capability, upload lease and the existing five-second receiver media coverage bound.
Old queued work is omitted, never relabeled with the
upload's newer status. Late completion cannot release a newer upload lease.

Only a literal `features.live_media_frame_submission_v1: true` permits the optional
object. Absent, false or malformed capability and unavailable observations preserve
the existing legacy body. The transport remains the paired session telemetry POST;
there are no Moonlight packet/opcode/gitlink changes or Deck producer changes.
The host owns interval subtraction, ledger joins, coverage checks and diagnostic
receipts, including advancing a valid coherent baseline after unavailable joins.
Empty snapshots retain the last submission time and do not refresh media evidence.

`tools/tests/video_frame_submission_test.c` exercises the production C observer with
deterministic providers, concurrency and arithmetic boundaries. It is registered
beside the legacy native payload test in CTest. Kotlin tests exercise the production
typed snapshot, serializer and binding/lease helpers; API parsing tests cover the
public parser facade. Full Android/JNI builds and exact-head CI remain distinct
from these focused local tests and from package or physical-device acceptance.
