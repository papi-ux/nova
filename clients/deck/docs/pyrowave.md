# Experimental PyroWave on Linux

PyroWave is an optional Vulkan compute codec for a fast local network. It requires
Nova and Polaris builds supporting the same codec profile. This implementation uses GameStream transport;
it does not connect to Punktfunk's QUIC service.

Build the native client with `-DNOVA_DECK_BUILD_PYROWAVE=ON` and the pinned
`pyrowave-shared` 0.6.0 development package. The experimental Flatpak manifest is
`packaging/flatpak/com.papi_ux.Nova.pyrowave.json`. The regular manifest keeps the
feature disabled. In Video & Stream, choose **PyroWave · Experimental** explicitly;
Auto does not select it.

The host currently converts CPU BGRA capture to full-range Rec.709 YUV420 before
Vulkan encoding. The Linux client decodes on Vulkan and exports three R8 DMA-BUF
planes for its EGL presenter. There is no production CPU decode fallback.
HDR, 4:4:4, Spaces, and a Punktfunk connection are outside this first route.
High refresh rates and sustained performance still need device measurements.

## HUD and live bitrate

NovaHUD identifies the active decoder as **PyroWave**. Received video bitrate is
measured from complete video payloads delivered to the decoder; it excludes audio,
FEC, transport headers and packets that never become a complete frame. It is not
the encoder's maximum bitrate. Simple scenes can use less than the budget.

The debug HUD and local support report distinguish the requested bitrate, the
encoder-confirmed applied bitrate, and the measured received video bitrate.
`WORK` is average time in the decoder submission callback, including validation,
GPU waits and frame handoff; it excludes Qt composition and is not GPU-only timing.
`REFUSED` is the cumulative number of decoder callback refusals in this session.
It is not a network-loss count. Stale measurements become unavailable.

**Command Center → Live bitrate** changes the current stream after the matching
host confirms that its encoder accepted the target. This explicit fixed-rate
choice turns automatic Live Tuning off and supersedes pending Doctor bitrate
changes. It does not change the saved Play Setup. The host's configured bitrate
bounds still apply; a bounded or refused target must not be shown as applied.

PyroWave's host encoder can update its per-frame budget without restarting the
stream. The budget follows the negotiated rational frame rate and remains subject
to transport bounds. This adds runtime rate control, not a codec-specific automatic
quality policy or a promise of equal sharpness at equal bitrate across codecs.

## Shared Android/Linux transport contract

Both client implementations must use the same contract:

- Upstream PyroWave commit `186f0393b77f7755953b5ecde994bb1cec2e4155`, C API 0.6.0.
- Client format `0x10000`; server capability `0x00800000`; SDP `bitStreamFormat=3`.
- DESCRIBE advertises `a=rtpmap:99 PYROWAVE/90000` and
  `a=fmtp:99 pyrowave-186f0393-sdr420-v1`. Match complete attributes for payload
  99, including the exact profile token, before selecting the decoder. Both LF
  and CRLF are accepted. Missing, conflicting or duplicate attributes are refused.
- ANNOUNCE selects `bitStreamFormat=3`, accepting the single offered profile.
  No separate revision echo is required. Explicit selection fails when the
  offered profile is incompatible; it never silently chooses another codec.
- `PolarisPyrowaveBitstream` in serverinfo and `pyrowave_bitstream` in capture
  capabilities are optional early compatibility hints. A present hint must match;
  an absent hint leaves the mandatory RTSP check to decide compatibility.
- SDR 8-bit 4:2:0, full-range Rec.709 (`encoderCscMode=3`, `dynamicRangeMode=0`,
  `chromaSamplingType=0`). Even output dimensions, 16 through 4096 per axis.
- One GameStream decode unit contains one complete **raw upstream bitstream**.
  The PyroWave coefficient packets are concatenated in order. Their block
  headers are self-delimiting; there is no Nova-specific outer envelope.
- The current host permits up to 3 MiB per frame, within GameStream's shard-count
  limits, and accepts network payloads of at least 992 bytes (1024 minus the
  video encryption prefix). Larger frames can lose FEC parity protection.
- All frames are IDR. GameStream's short frame header carries the exact final
  payload length, so FEC padding never reaches the codec.
- Validate dimensions, block count/order/sequence, payload lengths and coefficient
  bounds before calling the upstream decoder. Never display a failed decode.

The C API version alone does not identify a stable bitstream. Update both peers
and the negotiated revision together when changing the pinned upstream codec.
The token identifies this codec profile, not an application release: compatible
Nova and Polaris releases can reuse it. The dependency pins are not yet verified
against the token automatically across both projects; keep the feature opt-in.

## Validation

`nova_pyrowave_parser_test` exercises malformed inner payloads without a GPU.
`nova_pyrowave_protocol_test` rejects missing, ambiguous or inexact SDP profiles.
`nova_pyrowave_test` exercises moving frames, sequence wrap, restart and exported
frame ownership. It also starts a fresh GPU decoder at every sequence value,
without a preceding CPU-output decode or capability probe, and checks that a
rejected GPU frame preserves the previous image and permits the next valid frame.
Command storage and immutable device capabilities are reused within a decoder
session. Exported frame images retain independent ownership; a new frame never
overwrites an image still held by the presenter.
`nova_deck_pyrowave_presenter_test` verifies Vulkan decode through
the actual EGL shader with distinct chroma values and checks frame lifetime.

For a matched host check, run Polaris's `PyroWaveEncodeTests.*` with
`POLARIS_PYROWAVE_TEST_FRAME=/absolute/test-frame.bin`, then pass that file to
`nova_deck_pyrowave_presenter_test`. It verifies the host's resizing, letterboxing
and full-range Rec.709 conversion through Nova's renderer.

A real stream can be checked with the existing explicitly invoked native launcher:
`nova-deck --standalone --native-launch '<test app>' --native-codec pyrowave
--native-mode 1280x800x60 --native-bitrate-kbps 100000 --native-wait-ms 10000`.
This starts and stops the selected paired host application. Use an isolated test
host for automated checks. Decoded-frame evidence does not establish physical
presentation, input/audio quality, or performance on a Steam Deck.

References: [upstream](https://github.com/Themaister/pyrowave) and
[Punktfunk's PyroWave overview](https://docs.punktfunk.unom.io/docs/pyrowave).
