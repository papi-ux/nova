# AYN Thor external-display audio field test

This protocol validates Nova's selected-stream-display audio wiring on real AYN Thor hardware.
Local JVM/emulator checks cannot prove which physical speaker or AYN volume slider owns playback.

## Safety and privacy

- Use a non-Steam test app when possible. Disconnect normally after every run.
- With `--serial`, the analyzer resolves Nova's PID and reads only that process using `adb logcat -d -v raw --pid`; it does not clear logs, change settings, or control Nova.
- Its JSON contains only display IDs, public audio-device ID/type values, focus booleans, provenance booleans, and known runtime-error counts.
- Saved-file/stdin input is parsed but marked `source_process_scoped: false`; it cannot produce `diagnostic_evidence_complete: true`. Prefer `--serial` for shareable field evidence.
- Do not post raw logcat, host names, IP addresses, pairing data, tokens, or screenshots containing them.
- `physical_audio_verified` intentionally remains `false`; the human result below is the hardware proof.

## Candidate identity

Use the non-root ARM64 debug APK supplied for the test:

```bash
APK=app-nonRoot_game-arm64-v8a-debug.apk
sha256sum "$APK"
aapt dump badging "$APK" | grep -E "^(package:|native-code:)"
adb -s "$SERIAL" install -r "$APK"
```

Record the APK SHA-256 and Nova commit from the handoff. Confirm `native-code: 'arm64-v8a'`.

## Nova settings

1. Enable **Use Android external display**.
2. Set **Android stream display** to the top/largest display.
3. Keep the companion controls enabled on the other display.
4. Use the same safe host app and stream settings for every run.

## Matrix

Run five clean cycles for each launch origin:

| Lane | Launch Nova from | Expected stream | Expected controls | Required cycles |
|---|---|---|---|---:|
| A | top display | top/largest | bottom | 5 |
| B | bottom display | top/largest | bottom | 5 |

For every cycle:

1. Clear the evidence window with `adb -s "$SERIAL" logcat -c`.
2. Launch Nova from the lane's requested display.
3. Start the same audio-producing stream.
4. Verify video is on top and companion controls are on bottom.
5. Press the physical volume buttons and open the AYN per-display volume overlay.
6. Record which slider changes playback: **top**, **bottom**, **both**, or **neither**.
7. Close the companion controls, confirm whether audio ownership changes, then reopen them from Nova's ongoing notification and check again.
8. Disconnect normally. Confirm Nova remains responsive and no stale companion window remains.
9. Capture the privacy-safe report:

```bash
python3 tools/nova_thor_audio_field.py \
  --serial "$SERIAL" \
  --package com.papi.nova.debug \
  --output "thor-audio-${LANE}-${RUN}.json"
```

On a hot-remove-capable setup, perform one additional removal/re-add cycle. The stream Activity should survive companion-display removal; controls should reopen on the newly assigned logical display ID.

## Reading the JSON

A healthy wiring report should show:

- `schema_version: 3`
- `source_process_scoped: true`
- `latest_run_marker_found: true`
- `audio_context_matches_stream: true` (selected stream ID, audio-claimed stream ID, and audio-context display ID all agree)
- `companion_stream_matches_stream: true`
- `audio_route_observed: true` and `audio_route_matches_stream: true` (device ID/type remain informational, not panel proof)
- `game_window_observed: true` and `game_top_resumed_observed: true` for the latest Game state on the selected stream display
- `companion_window_observed: true` on the reported companion display
- `runtime_errors_absent: true`
- `diagnostic_evidence_complete: true`

The decisive hardware result is still human-observed: playback and physical volume control follow the top/stream display while the bottom Presentation remains visible.

## What the audio inventory answers

`audio_route_observed` says where the track landed. It does not say where else it could have gone,
and on a dual-screen host those are different questions: both launch lanes can report the same
builtin speaker while the user hears different screens. From the routed device alone we cannot tell
whether that is one device the vendor re-points below the HAL or two devices we never asked about.

The inventory separates them, and the two outcomes have opposite fixes:

- `audio_output_choice_exists: true` — more than one output device is enumerated, so
  `AudioTrack.setPreferredDevice` (public since API 23, no permission) has somewhere to aim. Read
  `audio_outputs` for the device IDs, types and addresses to aim at.
- `presentation_route_offers_audio: true` — some media route carries live audio *and* names a
  display. `MediaRouter.RouteInfo.getPresentationDisplay()` is the only public API that maps an
  audio route onto a display, so this is the other pre-34 way to express display affinity.
- both `false` — the platform offers no pre-34 lever, and the display association fixed at launch
  is the whole story. That is a real finding, not a failed capture.

`null` on either check means no inventory line was seen at all, which is what a build predating this
diagnostic looks like. Do not read it as "this device has no choices".

Neither check feeds `diagnostic_evidence_complete`: the inventory describes the platform, it does
not grade the wiring.

## Issue reply template

```text
Thor model:
Android version / SDK:
Candidate commit:
APK SHA-256:

Lane A — launched from top, stream top/largest:
- Runs passing top-display audio ownership: __ / 5
- Slider controlling playback each run:
- Notification reopen preserved ownership: __ / 5

Lane B — launched from bottom, stream top/largest:
- Runs passing top-display audio ownership: __ / 5
- Slider controlling playback each run:
- Notification reopen preserved ownership: __ / 5

Hot-remove/re-add result:
New Nova crash / ANR / window errors: yes / no
Privacy-safe analyzer JSON attached: yes / no
Additional observations:
```

If any lane fails, attach its analyzer JSON and describe the exact human-observed slider/speaker behavior. Keep raw logcat private unless a maintainer requests a narrowly filtered excerpt.
