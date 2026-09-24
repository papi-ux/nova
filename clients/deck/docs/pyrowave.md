# Experimental PyroWave on Linux

PyroWave is an optional Vulkan compute codec for a fast local network. It requires
matching Nova and Polaris builds. This implementation uses GameStream transport;
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

## Shared Android/Linux transport contract

Both client implementations must use the same contract:

- Upstream PyroWave commit `186f0393b77f7755953b5ecde994bb1cec2e4155`, C API 0.6.0.
- Client format `0x10000`; server capability `0x00800000`; SDP `bitStreamFormat=3`.
- DESCRIBE advertises `a=rtpmap:99 PYROWAVE/90000` and
  `a=x-polaris-pyrowave:pyrowave-186f0393-sdr420-v1`.
- ANNOUNCE includes `x-polaris-pyrowave:pyrowave-186f0393-sdr420-v1`.
  The revision must match exactly. Do not silently negotiate a different codec.
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

## Validation

`nova_pyrowave_parser_test` exercises malformed inner payloads without a GPU.
`nova_pyrowave_test` exercises moving frames, sequence wrap, restart and exported
frame ownership. `nova_deck_pyrowave_presenter_test` verifies Vulkan decode through
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
