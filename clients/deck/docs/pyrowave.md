# PyroWave in Nova

PyroWave is an experimental Vulkan compute video codec for a fast local network.
Each frame is encoded independently, so picture quality can require much more
bandwidth than H.264, HEVC or AV1. Start on a wired connection; there is no single
bitrate that guarantees good quality or sustained performance on every device.

**Select PyroWave in Nova. There is no switch to enable it in the Polaris
console.** Both ends need compatible builds and Vulkan-capable GPUs. The official
Polaris v1.4.13 Linux packages include the host encoder; custom builds can omit
it. A beta label on Nova alone does not mean its package includes the decoder.

Standard Moonlight clients do not contain this decoder. A host setting cannot
add it to Moonlight.

## Turn it on in Nova Linux

Nova Linux supports Linux desktops, laptops and handhelds, including Steam Deck.

1. For 1.4.14 and later, install the standard **`Nova-Linux-x86_64-alpha.flatpak`**
   and verify its checksum. It includes PyroWave; no separate codec bundle is needed.
   Version 1.4.13 used a separate PyroWave bundle. See the
   [installation steps below](#packaging-and-compatibility).
2. Open Nova, pair your Polaris PC if needed, and open a game's **Play Setup**.
   Use the normal **Desktop** destination; PyroWave is unavailable in Spaces.
3. Choose **Video Codec → PyroWave · Experimental**. **Auto does not select it.**
   Start with SDR at a modest mode such as 1280×720 at 60 FPS.
4. Start the stream. Confirm a moving picture and **PyroWave** in NovaHUD, then
   check audio, input and received video bitrate. A decoder starting successfully
   without receiving frames is not a working stream.

The Encoder row then shows **PyroWave · Vulkan**, selected by the codec. Saved
NVENC, VA-API or other encoder choices remain available when switching back to
another codec, but are not sent with a PyroWave launch. Tuning presets still apply
and require the usual host authorization. An unsupported build, host, GPU or
stream size explains why PyroWave cannot start; an explicit choice never falls
back silently to another codec.

## Turn it on in Nova Android beta

1. Install the beta APK for your device using the
   [Android beta guide](../../../docs/updates.md#android-install-a-beta). Open the
   beta app and pair your PC there; stable and beta keep separate pairing data.
   Stable Android builds do not expose PyroWave.
2. In builds with the per-game codec row, open **Play Setup → This Game → Video
   Codec → PyroWave (experimental)**. Nova saves that choice for this game on this
   PC. **App setting** restores the codec selected in Nova Settings.
3. Older builds, including v1.4.13-beta.3, use **Settings → Client Stream Defaults
   → Change codec settings → PyroWave (experimental)** instead. Auto does not
   select PyroWave on either path.
4. Start an SDR stream and check the picture, codec, received video, audio and
   input. Selecting the codec does not establish that the GPU and host capture
   path can sustain it.

**The 1.4.14 beta fixes native-library and APK alignment for 16 KB memory pages.**
The current signed app installed, started and loaded both streaming libraries
on an enforcing 16 KB emulator. The older v1.4.13-beta.3 PyroWave library was
refused there specifically for its 4 KB program alignment. This does not prove
PyroWave playback on the emulator: its GPU lacks required codec features.

Android checks the actual Vulkan feature set before offering PyroWave. Missing
8-bit storage or timeline semaphores, as on Shield, produce an unavailable reason
instead of a choice that will fail on launch. Other codecs remain available.
Older beta.2/beta.3 builds could show PyroWave even when their library could not
load; use a current aligned beta rather than treating that choice as support.

## Packaging and compatibility

Starting with 1.4.14, the standard Flatpak includes the pinned PyroWave decoder
and a separate device-check helper. The app ID stays `com.papi_ux.Nova`, so an
upgrade from either older bundle retains pairing and preferences.

Download the standard bundle and checksum from the same release, then run these
commands in that download directory with Nova closed:

```sh
sha256sum -c Nova-Linux-x86_64-alpha.flatpak.sha256
flatpak install --user ./Nova-Linux-x86_64-alpha.flatpak
flatpak run com.papi_ux.Nova --standalone
```

Nova checks this device the first time you choose PyroWave. H.264, HEVC and Auto
do not run that check. Play waits for it, and the result lasts until Nova restarts.
Passing the check does not prove that a stream plays; check picture, audio and
input after starting one.

Version 1.4.13 attached a separate PyroWave bundle. It is retired. If you installed
it, install the standard bundle over it with the commands above; your pairing and
settings stay. Keep app data; do not uninstall with `--delete-data`.

## Limits and troubleshooting

- **Missing or disabled choice:** check the package first. The standard Linux
  Flatpak before 1.4.14 and the stable Android app do not provide this experimental path. On
  Linux, Play Setup also explains incompatible host support, unavailable Vulkan
  decoding or an unsupported stream size. Use Nova Linux 1.4.14 or later, a
  compatible host/device and a supported size; selecting Auto does not enable PyroWave.
- **A session starts but receives no video:** inspect the host's capture log.
  PyroWave cannot read FP16 DMA-BUF capture, including `ABGR16161616F` (`AB4H`)
  produced by some KDE HDR configurations. Current compatible Polaris builds
  refuse unsupported formats before video establishment; older hosts may refuse
  them after the client decoder starts. For an SDR test, disable HDR on the host
  display or use a supported capture route. Older diagnostics can mislabel FP16
  as `bgra8`; current diagnostics report `rgba16f`. Neither clearer diagnostics
  nor an earlier refusal adds FP16 conversion.
- **HDR:** Polaris also has an HDR profile, but this Nova Linux route accepts
  SDR 4:2:0. Host support alone does not enable Linux HDR. HDR, 4:4:4 and Spaces
  remain outside this Linux route; Android HDR needs its own compatible profile,
  capture path and validation. A successful SDR session is not HDR acceptance.
- **Unexpectedly high bandwidth or slow capture:** each frame is independent.
  Compare the received bitrate with the available link capacity and reduce the
  resolution or frame rate if needed. The host converts colour on the GPU by
  default. Frames captured in host memory are uploaded first; compatible
  GPU-resident DMA-BUF frames can be imported without that CPU upload. The CPU
  colour converter is an SDR fallback when GPU input is disabled or cannot
  start. Seeing a Vulkan encoder alone does not establish GPU-native capture.
  Follow the
  [Polaris guide](https://github.com/papi-ux/polaris/blob/master/docs/pyrowave.md)
  for host capture requirements.

The Linux client decodes on Vulkan and exports three R8 DMA-BUF planes for its
EGL presenter. There is no production CPU decode fallback. High refresh rates
and sustained performance still need device measurements.

## Bitrate advice and limits

The calibrated model uses a 31 dB target for handheld viewing and 35 dB for a
screen across the room. Android 1080p/120 in 4:4:4 gives about 215 Mbps as a
handheld request. Linux uses its own SDR 4:2:0 profile; do not copy a 4:4:4
figure into that profile or treat the model as measured picture quality. Host
advice is preferred when available, with a local calibrated estimate otherwise.
If the requested starting bitrate is low, Play Setup explains the shortfall.

Automatic raises and **Use recommended** stay at or below 300 Mbps. Manual
requests use the PC's advertised limit, up to 500 Mbps; an absent capability
means 300 Mbps. Android offers manual exact entry. Linux Play Setup offers
presets through 300 Mbps and keeps an allowed saved custom value; its live
slider and 10 Mbps buttons can reach the advertised manual limit. Raising the
limit does not prove that a particular device or link can start or sustain it.

A request budget and encoder video bitrate can differ because audio, FEC and
other host budget rules are applied. A compatible host supplies the actual
units and conversion assumptions. Received video is a third, measured value;
compare like units rather than treating every Mbps label as encoder output.

## HUD and live bitrate

NovaHUD identifies the active decoder as **PyroWave**. Received video bitrate is
measured from complete video payloads delivered to the decoder; it excludes audio,
FEC, transport headers and packets that never become a complete frame. It is not
the encoder's maximum bitrate. Simple scenes can use less than the budget.

The debug HUD and local support report distinguish the PC's requested encoder target, the
encoder-confirmed applied bitrate, and the measured received video bitrate.
`WORK` is average time in the decoder submission callback, including validation,
GPU waits and frame handoff; it excludes Qt composition and is not GPU-only timing.
`REFUSED` is the cumulative number of decoder callback refusals in this session.
It is not a network-loss count. Stale measurements become unavailable.

**Command Center → Live Bitrate** changes the current stream after the matching
host confirms that its encoder accepted the target. This explicit fixed-rate
choice turns automatic Live Tuning off for this stream only and supersedes
pending Doctor bitrate changes. It does not change the saved Play Setup or
the host's saved Live Tuning setting. The host's configured bitrate
bounds still apply; a bounded or refused target must not be shown as applied.
The picker keeps **Your last request**, **PC target**, and **Encoder applied**
separate. If the PC reports a different target, Nova explains the mismatch and
leaves the saved preference alone; it never retries or calls that request applied.

Linux live controls use 10 Mbps buttons plus a touch slider. **Use recommended**
keeps its automatic ceiling and is available when request units and advice are
known. Android uses ten-percent PyroWave steps and 5 Mbps H.264/HEVC steps.
Unknown units remain read-only. A compatible Doctor can offer a reversible
quality increase on a clean network or a reduction after sustained measured
media loss; Verify checks the result and Undo restores bitrate and Live Tuning.
The host's Live Tuning loss response is unchanged from 1.4.13.

PyroWave's host encoder can update its per-frame budget without restarting the
stream. The host derives the budget from the requested bitrate and its effective
frame rate; transport limits still apply. Runtime rate control does not guarantee
equal sharpness at equal bitrate across codecs.

## Building the Linux client

Build with `-DNOVA_DECK_BUILD_PYROWAVE=ON` and the pinned `pyrowave-shared` 0.6.0
development package. The standard manifest,
`packaging/flatpak/com.papi_ux.Nova.json`, enables it and installs the isolated
`nova-deck-pyrowave-probe` helper beside the app. Codec-disabled developer builds
remain supported.

## Linux SDR transport contract

The SDR profile is shared with Android. Android additionally negotiates the
host's `pyrowave-186f0393-hdr2020pq420-v1` profile for compatible HDR sessions;
that does not extend the Linux client contract below.

The Linux route uses GameStream transport with this contract:

- Upstream PyroWave commit `186f0393b77f7755953b5ecde994bb1cec2e4155`, C API 0.6.0.
- Client format `0x10000`; server capability `0x00800000`; SDP `bitStreamFormat=3`.
- DESCRIBE advertises `a=rtpmap:99 PYROWAVE/90000` and
  `a=fmtp:99 pyrowave-186f0393-sdr420-v1`. Match complete attributes for payload
  99, including the exact profile token, before selecting the decoder. Both LF
  and CRLF, spaces or tabs after the payload identifier, and trailing horizontal
  whitespace are accepted. Missing, conflicting or duplicate attributes are refused,
  including conflicts using different whitespace or a zero-padded payload identifier.
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
- The Polaris v1.4.13 host derives a per-frame target from bitrate divided by
  frame rate, with a minimum of 4096 bytes. It has no separate 3 MiB codec budget
  ceiling or PyroWave-specific 992-byte payload minimum. GameStream transport
  limits still apply: a frame needing more than the allowed FEC blocks is sent
  without parity protection.
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

Reference: [upstream PyroWave](https://github.com/Themaister/pyrowave).
