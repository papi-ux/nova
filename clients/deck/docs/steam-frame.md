# Private native Steam Frame qualification

Tracking: [Nova #231](https://gitea.centaur-frog.ts.net/papi-ux/nova/issues/231).
The acceptance target is the virtual 1920×1080 SDR screen at nominal 90 fps.
Per-eye panel specifications do not determine a GameStream request.
This work is separate from beta.1 acceptance and public publication.

## Decoder and presentation boundaries

The ordinary package keeps its existing device permissions and VA-API path.
The Frame development launcher enables `NOVA_DECK_FRAME_V4L2=1` for that
process. Discovery then checks an accessible `qcom-iris` mem2mem device and
runs a bounded child using the exact bundled `h264_v4l2m2m` and
`hevc_v4l2m2m` decoders. Each codec remains unavailable until its embedded
1080p gray fixture produces a reference-owned linear NV12 frame with actual
gray pixel values. This establishes a decode route and size; it is not a
refresh-rate or streaming receipt. Main10 remains unadvertised on this path.

The private ARM FFmpeg provider uses SDK source revision
`f46e514491172d15bd74b4abb1814cd2f05a763e` (7.1.3). Its private decoder
options select that device and poll capture without blocking. Resolution
changes with retained capture buffers are refused instead of waiting for a
render-thread release. Nonzero V4L2 data offsets reduce the reference's
reported allocation length. The provider's public ABI remains FFmpeg 7.1.3.
No libx264/libx265 encoder is added to the shipped provider; fixture encoders
belong in the separate test environment.

V4L2 capture buffers stay leased through libplacebo's three-slot upload pool.
Plane pitches, allocation extents, chroma layout and SDR color are validated
before mapping. Qualcomm compressed capture layouts are refused. Hardware
decoding and CPU-to-GPU transfer are separate facts. The HUD/report fields
identify the decoder and transfer path and distinguish incoming, decoded,
handoff-submitted and successfully composed video rates. CPU upload
compositions count distinct frames composed through that path; they do not
measure panel flips or GPU completion latency.

The development launcher also enables `NOVA_DECK_FRAME_TRACE=1`. Each new
stream reading appends the sanitized support report and its sample timestamp
to an owner-private JSONL file in `frame-qualification` under the isolated
application data directory. Existing files are never overwritten. A file is
capped at 16 MiB and creation stops at 16 trace files; failures are reported
in the development log. Verify a fresh trace before each soak. Disable the
trace environment variable for a separate measurement of recording overhead.
The trace does not invent panel, audio-underrun or thermal measurements;
collect those separately where available.

PyroWave keeps explicit selection and an independent refusal result. Its
three R8 DMA-BUF planes carry a typed YUV420P context for Vulkan import, with
the PyroWave owner retaining the actual device, allocations and fds. The
presenter validates all three layer extents before import. ARM Vulkan driver
library identities participate in the probe cache. Real Adreno decode,
export/import, synchronization and presentation still require device evidence.

## Installation and rollback

Build/export a private `aarch64` bundle on branch `frame-dev`, with the existing
KDE 6.10 runtime and pinned libplacebo/PyroWave modules. Use an isolated SDK
installation and the canonical build-host `heavy.sh`; retain its exact SDK
and Platform commits. The bundle name includes architecture and source SHA.
Leave the development update channel disabled. Architecture-specific beta
update requests are verified separately against an isolated fixture endpoint;
no feed is published by this qualification.

Before installing on Frame, record any existing Nova ref and commit:

```sh
flatpak info --user --show-ref com.papi_ux.Nova
flatpak info --user --show-commit com.papi_ux.Nova
```

An absent existing application is a valid fresh-install case. Retain the
previous bundle/commit if one exists. Install the private bundle with
`flatpak install --user ./Nova-Linux-aarch64-<version>-<sha>-frame-dev.flatpak`,
then run `frame-development-launcher.sh`. Its `--device=all` permission is
temporary because Flatpak lacks a video-only permission. It uses separate
configuration, data and cache directories for standalone pairing and settings.
It never changes the shared manifest or persistent Flatpak overrides.

Stop Nova before rollback. Remove only this development ref:

```sh
flatpak uninstall --user app/com.papi_ux.Nova/aarch64/frame-dev
```

Do not use `--delete-data`. If there was a previous Nova branch, restore its
recorded current branch with `flatpak make-current --user com.papi_ux.Nova
<previous-branch>` and verify its ref/commit. A fresh installation needs no
previous app restoration. Keep development pairing/settings until the owner
chooses to remove them; regular saved settings stay separate.

## Required receipts

Run Release tests with assertions enabled. Retain backend selection, missing
device and child failure/timeout results; NV12 stride, allocation, color and
lifetime boundaries; bounded handoff and upload pressure; late callbacks,
teardown and reconnect; existing VA-API and PyroWave tests. Audit AArch64 ELF
machine, runtime loader closure, named codec availability, source/dependency
pins, licenses and architecture-specific updater requests. Retain the x86_64
regression result separately.

After connectivity returns, record standalone pairing, library browsing,
first image, audio, Steam Input navigation, Back, Command Center, gamepad
forwarding and clean stop. Bring each codec up at 60 fps before a separate
15-minute 1080p/90 soak. Record requested, decoded, handoff-submitted and
available composition/presentation measurements independently, alongside
drops, queue depth, audio underruns and thermal observations. Unavailable
measurements stay unavailable.

With the owner wearing Frame, confirm legible UI, smooth motion, colors,
synchronized audio, controllers, headset removal, suspend/resume and network
interruption recovery. Completion requires H.264, HEVC and PyroWave all
passing; a build, capability probe or headless test does not satisfy it.

Moonlight XR v0.4 is a bounded later assessment of OpenXR screens, positioning,
input and depth-generated 3D. Its Quest/Pico testing is not Frame acceptance.
Immersive XR, HDR, foveation and higher refresh rates follow native streaming.
