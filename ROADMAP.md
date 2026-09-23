# Nova Roadmap

Nova is public and usable today, but it is still early. This roadmap explains
where the Android and native Linux clients are heading and where testing helps most. **Direction,
not a release calendar:** priorities can move when real devices, regressions, or
measurements teach us something better.

For the shared host-and-client view, see the
[Polaris + Nova public roadmap](https://papi-ux.com/docs/roadmap/).

## How to read this roadmap

- **What stays true** describes the compatibility and product boundaries users
  can rely on now.
- **Now** is active reliability and support work.
- **Next** is work we expect to evaluate after the current foundations are sound.
- **Explore later** records real goals, not promised releases or dates.

## What stays true

- Android is available today. The native Steam Deck client is available as an
  Alpha Flatpak starting with v1.4.12; full Linux support and parity are in progress.
- Nova remains usable with Moonlight-compatible hosts for standard pairing,
  launch, and streaming.
- Polaris provides the richest host-aware experience: launch modes, live host
  context, session state, diagnostics, and coordinated tuning.
- Public releases continue to use predictable ARM64, ARMv7, and x86_64 APKs through
  GitHub Releases, Obtainium, and GitHub Store.
- The native client targets Linux laptops, desktops and handhelds, with Steam Deck
  as a device profile. Supported combinations need their own acceptance evidence.
  iOS is planned without a release date.

## Now — make handheld streaming dependable

- Watch the public v1.4.0 path on real devices and prioritize reproducible
  pairing, launch, artwork, stream, resume, and teardown regressions over feature
  churn.
- Make pairing, discovery, launch, reconnect, stop, cleanup, and watch/resume
  behavior predictable on real devices.
- Keep controller-first navigation fast and readable across handhelds, phones,
  tablets, Android TV, and high-refresh Android devices.
- Continue polishing the Library, game details, launch choices, Command Center,
  NovaHUD, and error recovery without hiding host or stream state.
- Keep Play Setup, Artwork Studio, home-screen pin state, and the final launch
  envelope aligned around one canonical game identity, including Heroic and
  built-in utility entries.
- Improve troubleshooting guidance for input, decoder, network, frame pacing,
  stream quality, and Polaris-specific launch modes.
- Publish matched Polaris/Nova compatibility notes without making standard hosts
  second-class.

## Next — make the current path smoother

- Tighten high-refresh pacing through the current 90/120 Hz paths before claiming
  more ambitious rates.
- Improve decoder and presentation visibility so repeated, dropped, late, and
  unavailable frames are reported honestly.
- Measure client buffering, output timing, power, thermals, and network recovery as
  separate changes rather than one opaque “latency mode.”
- Broaden device and controller coverage with reproducible physical-device smokes.
- Expand automated coverage around pairing, launch, stream UI, controller input,
  resume, stop, and cleanup.

## Native Linux client

The [approved Linux roadmap](docs/linux-client-roadmap.md) expands the native
client beyond Steam Deck to laptops, desktops and other handhelds:

- Continue UI/UX refinement and complete UI/backend flows for controller,
  mouse/keyboard and touch use.
- Add accessible fullscreen/windowed controls and robust resizing, scaling,
  monitor selection and focus restoration.
- Complete relative mouse capture for aiming alongside existing pointer input.
- Find compatible hosts on local/trusted networks so pairing usually needs no
  typed IP address; retain manual entry and explicit host-authorized pairing.
- Remove Material You from Linux, preserve saved appearance preferences through
  fallback, and standardize interface titles such as **Video & Stream**.
- Remove arbitrary Deck-specific bitrate/FPS caps and support custom rates,
  including 240 fps where the host, protocol and client can support them.
- Audit Wayland/X11, graphics/decoder choices, audio, input, packaging and
  laptop/external-display compatibility, and publish what has actually been tested.

The 240 fps goal requires 240 unique frames per second decoded and physically
presented on a validated 240 Hz Linux system, with host, network, decoder,
display, quality, latency, thermals and power measured. Exposing a setting or
selecting a 240 Hz display mode does not prove that result. Deck LCD SDR60 and
OLED SDR90/HDR90 remain required profiles; broader support does not remove them.

## Build alongside — clean seams, no hidden rewrite

When current work already touches media units, decoder output, session lifecycle,
device capabilities, settings, or host contracts, Nova may extract a small
protocol-neutral boundary first. It must preserve today's Moonlight-compatible
behavior and remain useful even if no new transport is ever built.

## Explore later — only if evidence says yes

### A native streaming path

Nova will keep its Moonlight-compatible path and standard-host support. A native
Polaris/Nova path is research and must earn its place through measured improvement,
security review, clean fallback, and a user-visible benefit. It is not a forced
migration plan.

### Proper HDR10+

The goal is end-to-end dynamic metadata that survives the complete host/client
path and reaches a compatible display with truthful HDR10 and SDR fallback. Nova
must not turn an Android capability flag, static HDR, or generic tone mapping into
a false HDR10+ claim.

Passing HDR10+ and the Linux 240 fps goal separately does not prove a combined profile.
That combination would need its own exact device, codec, color, bitrate, quality,
latency, thermal, fallback, and rollback evidence.

### More client platforms

- Follow the active Linux client roadmap above; keep untested configurations
  explicit while extending support beyond the Alpha.
- Pursue iOS only when the media, controller, packaging, and maintenance contracts
  are clear enough to support it responsibly.
- Add new Android variants only when real users and devices justify the release and
  testing cost.

## What this roadmap does not promise

- No feature here has an implied date until it appears in a published release.
- Research does not remove standard Moonlight-compatible host support.
- A stream that starts is not considered healthy if controller input, physical
  presentation, teardown, fallback, or resume is broken.
- The roadmap may change when public testing or measured evidence disproves an
  assumption.

## Useful feedback

- Device model, Android version, Nova version, APK ABI, host software, display
  refresh rate, controller, and network details.
- Screenshots or recordings for navigation, focus, video quality, frame pacing,
  launch-mode, or input issues.
- Bounded logcat captures around crashes, ANRs, decoder failures, launch failures,
  reconnects, or stream cleanup.
- Comparisons with standard Moonlight on the same host and game, including matched
  Polaris/Nova version pairs for Polaris-specific behavior.
