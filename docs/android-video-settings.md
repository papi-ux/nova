# Video settings on Android

Start with **Recommended** in Play Setup. If the picture is clear and controls
feel right, you can leave the advanced options alone. They are alternatives to
try for a particular device, rather than a list of upgrades to turn on together.

This guide covers Nova on Android, including Android TV. Nova on Linux, Steam
Deck and Steam Frame uses a different renderer and has its own settings.

## Why can I only choose 30 or 60 FPS?

The screen you are watching matters, even when your PC can run the game faster.
A 60 Hz phone normally offers 30 and 60 FPS. A 120 Hz screen can offer 120 FPS,
provided the rest of the setup supports it. The display's fastest supported
mode determines which standard choices Nova offers; the active display mode,
decoder, PC and network still have to keep up.

**Unlock all possible frame rates** cannot turn a 60 Hz screen into a 120 Hz
screen. It is unavailable when the display cannot support the faster choices.
An external display can have different limits from the phone's own screen.

These numbers describe different things:

- **Game FPS:** how often the game draws a new frame on the PC.
- **Stream FPS:** the video cadence requested or delivered by the host.
- **Display Hz:** how often the client screen refreshes.

A selected FPS is a request. Check the applied stream settings and measured
readings before assuming it is the rate you actually receive or see.

## Settings or Play Setup?

Open **System → Settings** to find **Video frame rate**, **Video frame pacing**
and the advanced switches below. Settings Profiles keep those preferences
separately. The profile selected for a launch supplies its advanced preferences.

Use **Play Setup** for the game you are about to launch: its resolution, frame
rate, codec and other available launch choices. The host can resolve a lower
or fixed rate for that launch. Not every advanced setting appears in Play Setup,
and changing a saved preference does not mean an existing stream changed.

## Video frame pacing

Pacing controls when Nova presents decoded frames and how it handles a queue.
It balances responsiveness against even motion.

- **Prefer lowest latency** favors a recent frame over older queued frames.
  Motion can look less even when delivery is irregular.
- **Balanced** schedules presentation with the display's frame callbacks.
- **Balanced with FPS limit** can request slightly below the display refresh
  rate on legacy launches. It does not override the exact cadence resolved for
  a modern Polaris launch or a Space.
- **Prefer smoothest video** favors presentation pacing, with a possible latency
  tradeoff. A name does not guarantee the best result on every device.

Try a normal pacing choice before the experimental options. Keep the same game
scene and change one option at a time so you can tell whether it helped.

## LFR (Experimental)

LFR favors quicker presentation in the Android MediaCodec renderer. Outside
Balanced, it can discard older decoded frames and present the newest available
one instead of letting a queue build up.

This can increase intentional frame drops or make motion less even. It does not
shorten the network round trip. LFR is off by default and does not tune the
PyroWave renderer, whose presentation path is separate.

## Ultra Low Latency (Experimental)

Nova already requests normal low-latency decoder behavior with this switch off.
Turning it on allows additional vendor decoder hints instead of stopping at an
advertised Android low-latency mode alone. Nova tries less aggressive options
when a decoder rejects a configuration.

The effect depends on the decoder, device firmware and driver. A supported
setting is not proof of a latency improvement. This switch is off by default,
affects the Android MediaCodec path, and does not change PyroWave's renderer.
If decoding becomes unstable, turn it back off and start a fresh stream.

## Warp Drive and Warp 2 (Experimental)

On legacy launches, **Warp Drive** requests twice the transport frame rate and
**Warp 2** requests four times it. They use the lowest-latency pacing policy.
When Polaris supplies a trusted, resolved cadence, or a Space supplies its
launch contract, Nova keeps that exact rate and skips the multiplier.

Warp does not generate new frames, enable VRR or overclock the display. It can
ask more of the host, decoder and network. With Polaris's frame-rate limiter
on, the host can also multiply the bitrate request before applying its limits;
those limits can prevent the final rate from increasing by the same factor.
The game's rendering rate and the encoder's cadence can remain different from
the transport request.

There is no universal measured latency benefit to promise here. Leave Warp off
unless you have a reason to compare it. If picture, sound or responsiveness
gets worse, return to the previous pacing choice.

## If you need help

Keep a note of the device, codec, resolution, applied FPS and the one option you
changed. Nova's [stream reports](stream-reports.md) can keep a local snapshot to
share. Missing measurements are unknown, rather than evidence that there was no
latency or loss.

For the implementation behind this guide, see the
[display capability rules](../app/src/main/java/com/papi/nova/preferences/NovaDisplayFpsCapability.kt),
[launch cadence](../app/src/main/java/com/papi/nova/Game.kt),
[MediaCodec renderer](../app/src/main/java/com/papi/nova/binding/video/MediaCodecDecoderRenderer.kt)
and [decoder hints](../app/src/main/java/com/papi/nova/binding/video/MediaCodecHelper.kt).
