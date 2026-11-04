# Native packet reception feedback, version 1

Shared wire contract for Nova #442 (client sender) and Polaris #615 (host receiver).
Nova's sender is native patch
`0011-feat-video-report-bounded-native-packet-reception.patch`
over the pinned moonlight-common-c `c9994368`; it adds `src/PacketReception.{c,h}`.
Patch `0012-fix-video-keep-ENet-s-throttle-from-discarding-recep.patch` keeps ENet's
unreliable throttle from silently discarding those reports ([below](#enet-unreliable-throttle-patch-0012)).
Android applies both with `tools/apply-native-patches.sh`; the Deck applies them after the
RTSP pair in its build-private `nova_deck_prepare_rtsp()` copy, before the optional
PyroWave patch.

This is an observation. It reports **original RTP video packets that the native RTP
queue accepted** (coverage `queue_accepted_original`) and their userspace socket-read
times, in whichever video encryption mode existing policy already negotiated. It is
not a packet census or loss denominator, and it does not measure capacity, absolute
one-way delay, a pure network queue, media health or recovery. Nothing on the client
acts on it, and it grants no controller, bitrate, FEC or policy authority on either
side. ROOT decisions folded in: wire clarifications 01, 02 and 04 and design
addendum 03 (`ROOT-STABLE150/LT-NATIVE-FEEDBACK-V1-20261103/`).

## Negotiation

| Step | Exact form |
| --- | --- |
| Host DESCRIBE offer | `a=x-ss-video.packetReceptionFeedbackVersion:1` |
| Client ANNOUNCE echo | `a=x-ml-video.packetReceptionFeedbackVersion:1` |

The client writes the echo with the existing SDP serializer, so the line on the wire is
`a=x-ml-video.packetReceptionFeedbackVersion:1 \r\n` (trailing space). Both sides
require **exactly one** such attribute carrying the single token `1` after trimming
surrounding ASCII whitespace. Junk, another token, another version or **any duplicate,
including an identical one**, refuses only the extension; ordinary streaming is
unchanged. A longer attribute name that merely shares the prefix is a different
attribute.

The client echoes, and later sends, only when **all** of these hold:

1. The host offered exactly version 1 in this RTSP session.
2. Sunshine host (`IS_SUNSHINE()`), on the modern periodic-ping ENet control path.
3. Encrypted control protocol 13: app version at least 7.1.431, so the client requests
   `x-nv-general.useReliableUdp:13` and `streamid=control/13/0`.
4. `SS_ENC_CONTROL_V2` is already in `x-ss-general.encryptionEnabled` from existing
   policy, so every report travels on authenticated encrypted control. `SS_ENC_VIDEO`
   is **not** a gate: video is observed in either mode. The extension never requests,
   enables or changes any encryption.
5. The control SETUP reply carried `X-SS-Connect-Data`, mapped through the existing
   `ML_FF_SESSION_ID_V1` connect data. There is no IP-only fallback.
6. A monotonic microsecond clock is available on the client.

Old, unknown, absent, malformed, duplicated or one-sided offers, and peers without
encrypted control v2, get no echo, no report and unchanged streaming. The host enables
parsing only when it offered and exactly one echo is present; it must not expect
reports otherwise.

### Media provenance

| Negotiated video | What a count means |
| --- | --- |
| `SS_ENC_VIDEO` set | The packet passed the existing AES-GCM check before the queue accepted it; failed authentication is excluded |
| `SS_ENC_VIDEO` clear (ordinary LAN default) | The queue accepted it; nothing authenticated the media. An on-path party could inject or modify such packets |

The report itself always arrives on authenticated encrypted control v2. There is no
wire flag for media authentication: the host labels `media_authenticated` from its own
session `encryptionFlagsEnabled & SS_ENC_VIDEO`, never from client input, and keeps every
sender ledger identity, byte, tuple, fallback and freshness check. Neither mode carries
a cryptographic media proof, clean-delivery credit or any authority.

## Transport

| Property | Value |
| --- | --- |
| Message type | **`0x7F10`**, client to host, a private matched Polaris/Nova allocation (not an upstream reservation, not a feature bit) |
| Framing | Inside the existing encrypted control v2 message (`NVCTL_ENCRYPTED_PACKET_HEADER`, type `0x0001`); the inner V2 header carries type `0x7F10` and the payload length, little-endian like every other control message |
| ENet | Channel `CTRL_CHANNEL_GENERIC` (0), `ENET_PACKET_FLAG_UNSEQUENCED`, unreliable |
| Cadence | At most one report per existing periodic control tick (100 ms sleep plus work). It is queued immediately before the existing reliable `0x0200` ping, which flushes both |
| Failure | A failed send drops that observation only. No retry, queue, catch-up burst, probe or extra media. The sequence still advanced. On a connection with the active extension, ENet's unreliable throttle no longer discards reports (patch 0012); wire loss still does |

Collision check (2026-11-03): `0x7F10` appears in none of moonlight-common-c `c9994368`
with Nova patches 0001-0010, Nova's tree, the Deck PyroWave patch, or the Polaris
`ff8a0af1` control dispatch.

## ENet unreliable throttle (patch 0012)

Stock ENet `115a10ba` gives every unreliable packet a rotating `packetThrottleCounter`
(+7, mod 32) when it is flushed, and frees the packet inside `enet_host_service()`, after
`enet_peer_send()` already succeeded, if the counter exceeds `packetThrottle`
(`protocol.c` `enet_protocol_check_outgoing_commands`). Every acknowledgement slower than the
previous 5 s epoch's lowest RTT plus twice its variance lowers `packetThrottle` by the
deceleration, 2 (`peer.c` `enet_peer_throttle`). At 0 only the packet whose counter is 0
survives: every 32nd report when reports are the peer's only unreliable traffic. The discard
is silent: no send failure, no log, and the report sequence has already advanced. ROOT's
actual RP6 traces (`ROOT-NATIVE-AUTO-ACTUAL-E836-R1-20261104/PREFIX-REVIEW`) showed 218 absent
reports that all fit this `7n mod 32` signature while the reliable ping and video continued.

| Aspect | Behavior |
| --- | --- |
| Gate | `PacketReceptionIsTimed()`: the negotiated observer started, its clock is valid and it has not retired. Old, absent, refused, duplicated, withdrawn, mutex-failed and clock-failed peers keep stock ENet throttling and send nothing new |
| When | Once per connection in `startControlStream()`, after `PacketReceptionStart()` and before the loss-stats thread exists, under `enetMutex` because the control receive thread already services ENet |
| What | `enet_peer_throttle_configure(peer, interval, acceleration, 0)` with the peer's current interval and acceleration (ENet's defaults, 5000 ms and 2, for every Nova and Polaris peer), then a `packetThrottle` below `packetThrottleLimit` is set to that limit |
| Why the restore | The API only changes future deceleration. ENet does not acknowledge CONNECT (VERIFY_CONNECT does), so START A's acknowledgement is the client's first RTT sample and opens the epoch, and START B's can lower the throttle one step, to 30, before the observer starts. The restore is the assignment ENet makes itself in its stable-RTT branch, and it keeps any bandwidth limit |
| Retirement | If the extension retires while the stream continues (its clock sentinel, or the sequence limit), the loss-stats thread sends one more `THROTTLE_CONFIGURE` that restores the previous deceleration, after the sentinel was enqueued |
| Stop and reconnect | Stop closes publication under `enetMutex` before it retires the collector, so it never sends a configuration. Every connection creates a new ENet host and peer with ENet's defaults, and the host's peer takes the client's values from CONNECT |
| Unchanged | Reports stay unreliable and unsequenced on channel 0, at most one per tick, with no retry, queue or catch-up; wire loss still loses them. Negotiation, encryption, bounds, collector, stop fence, deadlines, ENet itself, the submodule pins and the host are unchanged |

### Peer-wide effects

The throttle belongs to the ENet peer, not to a message type, so on a configured connection:

* No unreliable client control packet on this peer is RTT-throttled any more: frame FEC
  status (`0x5502`), controller motion (non-null gyro and accelerometer), batchable touch and
  pen hover/move input, and reports. Under a collapsed stock throttle all of them were
  discarded too. They are still unreliable and still lost on wire loss; under sustained
  congestion the client sends more of them than stock ENet would.
* The client's reliable window is no longer scaled down. ENet keeps reliable data in transit
  below `max(packetThrottle * windowSize / 32, mtu)`; with the throttle at its limit that is
  the negotiated window (64 KiB against Polaris's unlimited bandwidth), where stock ENet would
  shrink it towards one MTU after RTT spikes. Retransmission and peer timeouts are unchanged.
* `THROTTLE_CONFIGURE` is an ENet protocol command (reliable, channel `0xFF`), outside the
  encrypted control payloads and their sequence numbers. Polaris e836 handles it in the same
  pinned ENet (`enet_protocol_handle_throttle_configure`), so its peer for this client stops
  decelerating as well. Polaris e836 sends only reliable control messages, so on the host this
  changes only that peer's reliable window scaling; host unreliable sends, if a later host adds
  any, would no longer be RTT-throttled either.
* `packetThrottleLimit` still applies, so a host that advertised a bandwidth limit would still
  cap the throttle (Polaris creates its ENet host with unlimited bandwidth). Acceleration still
  raises the throttle up to that limit.

### Diagnostics

For a connection with the active extension the control stream logs one setup row and then one
row per 10 report sequences, about one a second, from the loss-stats thread after it enqueued
that report. Values are read under `enetMutex`:

```text
Packet reception transport: extension active, throttle deceleration off; throttle 30 -> 32 of 32, counter 0, interval 5000 ms, acceleration 2, deceleration 2 -> 0, RTT 6 ms, variance 3 ms
Packet reception transport: report 10, throttle 32 of 32, counter 31, deceleration 0, RTT 5 ms, variance 2 ms
Packet reception transport: reports retired, throttle deceleration 2 restored
```

A periodic row is read before that tick's ping flushes the report, so the report still has to
pass `(counter + 7) mod 32 <= throttle`. Rows carry only these ENet counters and estimates: no
address, peer or session identity, key, nonce or report payload. Nothing is logged for a peer
without the active extension.

### Evidence boundary

The `transport-throttle` and `transport-lifecycle` fixtures run the production control stream
and pinned ENet over loopback, through a relay that holds host-to-client datagrams (20 ms, then
120 ms). With the configuration switched off by a test-only hook, stock ENet collapses the
throttle to 0 and discards most enqueued reports, none with counter 0, and most FEC status;
with the patch every report and FEC status arrives once, one report per tick. A fixture host
counts each `THROTTLE_CONFIGURE` on the wire. This is source and loopback evidence only: it is
not device, automatic cut or physical gaming acceptance, and the client's throttle on a real
network is observed only through the rows above.

## Payload

All multibyte fields are **big-endian**, encoded bytewise. Length is exactly
`52 + 10 * sample_count`, at most **212** bytes, with no trailing data.

### Header (52 bytes)

| Offset | Size | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | u8 | `version` | Exactly `1` |
| 1 | u8 | `flags` | Defined bits below; `0xF0` reserved, must be zero |
| 2 | u8 | `sample_count` | `0..16` |
| 3 | u8 | `reserved` | Zero |
| 4 | u32 | `report_sequence` | `1` for the first report of a native connection, `+1` for every scheduled report including a failed send and the clock sentinel. The extension stops after `0xFFFFFFFF`; it never wraps |
| 8 | u32 | `interval_us` | Actual microseconds since the previous snapshot (the collector origin for the first report) |
| 12 | u64 | `interval_end_us` | Microseconds since the native connection's monotonic origin. Never wall time |
| 20 | u32 | `receive_loop_tick` | Completed video `recvUdpSocket` calls, including 100 ms timeouts, since the origin. Serial counter: compare deltas only. Not media arrival |
| 24 | u32 | `accepted_data_packets` | Queue-accepted original data packets committed in this interval |
| 28 | u32 | `accepted_parity_packets` | Queue-accepted original parity packets committed in this interval |
| 32 | u32 | `accepted_data_udp_bytes` | Original UDP payload lengths of those data packets, including the 32-byte encrypted-video prefix when video is encrypted (plaintext video has none); excludes UDP/IP/link headers |
| 36 | u32 | `accepted_parity_udp_bytes` | The same for parity |
| 40 | u32 | `last_accepted_age_us` | `interval_end` minus the last commit time on this connection; `0xFFFFFFFF` if none or not representable |
| 44 | u32 | `first_frame` | Lowest accepted frame index committed in this interval; `0` unless `HAS_ACCEPTED` |
| 48 | u32 | `last_frame` | Highest accepted frame index committed in this interval; `0` unless `HAS_ACCEPTED` |

### Sample (10 bytes, starting at offset `52 + 10 * i`)

| Offset | Size | Field | Meaning |
| ---: | ---: | --- | --- |
| 0 | u32 | `frame_index` | The packet's frame index as accepted |
| 4 | u16 | `rtp_sequence` | The packet's RTP sequence number (no unwrapping) |
| 6 | u32 | `socket_read_age_us` | `interval_end` minus the monotonic time read immediately after the positive `recvfrom` return. Not commit, decode or frame-submission time |

### Flags

| Bit | Name | Meaning |
| --- | --- | --- |
| `0x01` | `HAS_ACCEPTED` | Set iff `accepted_data_packets + accepted_parity_packets > 0`; frame bounds are meaningful only when set |
| `0x02` | `INCOMPLETE` | Counter or byte saturation, an omitted sample older than 2 s, an interval longer than 2 s, or clock failure. Counts and timing are unusable for rate-local delivery; never join or trend them |
| `0x04` | `CLOCK_UNAVAILABLE` | Clock failure sentinel (below); no timing at all |
| `0x08` | `TIMING_SAMPLE_LIMIT` | A commit landed after the sixteenth 6.25 ms bin and was not sampled. Counts still cover the whole interval. Expected sparse coverage, not invalidity by itself; never claim full timing coverage |

## Collection semantics

* **Capture.** On the video receive thread, immediately after `recvUdpSocket` returns a
  positive length, the client reads the monotonic clock and keeps the original UDP length
  in locals. Every completed receive call, including a timeout, advances
  `receive_loop_tick`. Under the collector mutex that time is then checked against the
  receive thread's own earlier socket reads and commits only: a read taken before a
  concurrent control snapshot is valid and keeps its earlier time. Once checked, the read
  raises (never lowers) the floor that every later snapshot and commit must meet, so a
  snapshot reading earlier than a completed receive read is a clock regression even if
  that packet was rejected.
* **Commit.** Only inside the RTP queue's acceptance branch, after `queuePacket` accepted an
  original packet and before `reconstructFrame` can stage, submit or free it. Data versus
  parity is the queue's own classification against `bufferFirstParitySequenceNumber`.
  Runts, AES-GCM failures and the pre-decrypt old-frame filter (encrypted video),
  duplicates, packets rejected by the queue, parity arriving after its frame completed,
  and FEC-synthesized packets are never counted or sampled.
* **Intervals.** Counts, bytes and the frame range belong to the interval in which a packet
  was *accepted*. A sample keeps its earlier socket-read time, so `socket_read_age_us` may
  exceed `interval_us` when verification or acceptance was delayed; that is valid and is
  never moved to a later time.
* **Samples.** The first accepted commit in each `floor((commit - interval_start) / 6250 us)`
  bin, for bins 0..15, never replaced. This is a selection rule, not a statistical sample.
* **Zero interval.** `HAS_ACCEPTED` clear, counts and frame bounds zero, no samples. The
  sequence still advances; `receive_loop_tick` and `last_accepted_age_us` stay informative.
  It refreshes no media or delivery evidence.

Sequence, progress and age keep these cases apart: a missing sequence number is a missed
report; an unchanged `receive_loop_tick` is no receive-loop progress; a zero interval with
growing `last_accepted_age_us` is no accepted media; `CLOCK_UNAVAILABLE` is an unavailable
clock. None of them identifies a cause or authorizes recovery.

### Special reports

| Case | Encoding |
| --- | --- |
| Complete, clock available | `0 < interval_us <= 2,000,000`, `interval_end_us` strictly advancing, every sample age `<= 2,000,000` and `<= interval_end_us` |
| Long or saturated scheduling interval (ROOT clarification 01) | `INCOMPLETE`; `interval_us` the actual positive elapsed time, or `0xFFFFFFFF` only on saturation; actual advancing `interval_end_us`; **zero samples**; counts, range, tick and age retained as explicitly incomplete diagnostic facts |
| Sample older than 2 s | Sample omitted, `INCOMPLETE` set |
| Zero-length interval (same microsecond) | Not sent and no sequence consumed |
| Clock failure or regression | One sentinel at the next tick: flags `INCOMPLETE` and `CLOCK_UNAVAILABLE` (plus `HAS_ACCEPTED` if counts are retained), `sample_count = 0`, `interval_us = interval_end_us = 0`, `last_accepted_age_us = 0xFFFFFFFF`. Then the extension stays off until a fresh native connection. No epoch restarts inside a session |

Clock failure includes an unsupported clock at collector start, a failed read, and any
time that runs backwards between socket read, commit and snapshot. A failed or backward
socket-read clock ends timing even when that packet is then rejected or never reaches the
queue; the packet itself is still never counted or sampled. An unsupported clock at
ANNOUNCE time means no echo. There is no `gettimeofday` fallback.

## Host validation (Polaris #615)

Reject, without refreshing any observation: wrong length or trailing bytes, version not 1,
nonzero reserved byte or `0xF0` flag bits, `sample_count > 16`, `HAS_ACCEPTED` inconsistent
with the counts, nonzero frame bounds without `HAS_ACCEPTED`, `first_frame > last_frame`,
a non-advancing `report_sequence`, and any arithmetic overflow. With `CLOCK_UNAVAILABLE`
clear: a non-advancing `interval_end_us`, `interval_us == 0`, `interval_us > 2,000,000`
without `INCOMPLETE`, a long or saturated `INCOMPLETE` interval carrying samples, or any
sample age above 2,000,000 or above `interval_end_us`. With `CLOCK_UNAVAILABLE` set: it must
be the sentinel above. A structurally valid long `INCOMPLETE` report is accepted as an
unavailable observation and clears prior derived metrics (ROOT clarification 01).

## Lifecycle and bounds

The collector starts when the native control stream starts, before the loss-stats and video
receive threads exist: origin, sequence, window, samples and progress all reset. Control
stop wins over publication: it first closes reception reports under `enetMutex`, the mutex
the control stream already holds across each enqueue, then retires the collector, both
before joining the loss-stats thread and before ENet teardown. A report that thread has
prepared but not yet enqueued is dropped, and nothing is enqueued after retirement; a report
enqueued before the close is ordinary in-flight traffic during the existing graceful
disconnect. Control destroy clears the collector. A new connection starts again at sequence 1
with a new origin. No Java lifecycle is involved,
and the existing 10 s first-video/first-frame and control timeouts are unchanged.

Fixed memory: one 344-byte collector (64-bit build) with a sixteen-entry 16-byte sample array,
one stack report and one 212-byte stack wire buffer per tick. No per-packet allocation.
One clock read per positive socket return and one per accepted commit, O(1) under a short
mutex; O(16) at the tick, with encoding and the ENet call outside that mutex. Encrypted
control framing is at most 240 bytes per report (2,400 B/s at 10 Hz) before ENet, UDP, IP
and link headers; whole-link cost, CPU and scheduling cost are unmeasured.

## Golden encodings

Produced by the production serializer and asserted by `tools/test_native_packet_reception.py`.

| Case | Hex |
| --- | --- |
| Zero interval, sequence 1, 100 ms, tick 1 | `0100000000000001000186a000000000000186a00000000100000000000000000000000000000000ffffffff0000000000000000` |
| Two samples, 40 data + 4 parity | `01010200000000020001879a0000000000030e3a0000003900000028000000040000a28000001040000004b0000000110000001200000011123400017ed000000012124000002ee0` |
| Long 2.5 s interval, `INCOMPLETE`, no samples | `0103000000000003002625a000000000002933da00000052000000030000000000000c300000000000249f000000001300000013` |
| Clock sentinel, sequence 4 | `01060000000000040000000000000000000000000000005a00000000000000000000000000000000ffffffff0000000000000000` |
