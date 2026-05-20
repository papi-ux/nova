# Nova Video Baseline Evidence

This file records the measurement-only evidence pass for the Nova audit
follow-up video work. It does not tune frame-drop thresholds, decoder watchdog
timing, frame pacing policy, or launch-quality decisions.

## 2026-05-20 Retroid 6 1.1.0 Performance-Hardening Stream

- Branch: `nova/1.1.0-performance-hardening`
- Base commit: `796bb1fb351cba0bfc11af54accb043a0503a841`
- APK: `app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk`
- Package: `com.papi.nova.debug`
- Device: Retroid Pocket 6, Android 13
- Host: `pc-papi.lan`
- Scenario: install the ARM64 debug APK, repair Trusted Pair for the debug
  package, open the Polaris library, launch Steam Big Picture, resume the
  active session, enable NovaHUD from Command Center, then disconnect back to
  the Nova library.

### Build And Install

```bash
./gradlew -PnovaAbis=x86_64 testNonRoot_gameDebugUnitTest --console=plain
git submodule update --init --recursive
./gradlew -PnovaAbis=arm64-v8a assembleNonRoot_gameDebug --console=plain
adb -s adb-24c12bdd-gitDJe._adb-tls-connect._tcp install -r app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk
```

The first ARM64 assemble attempt found the checkout had not initialized the
native `moonlight-common-c` submodule. After initializing submodules, the ARM64
debug APK built successfully and installed over wireless ADB.

### Stream Observations

- Trusted Pair repaired the debug package pairing with the Polaris host.
- Polaris library loaded with `19` games and `13` recent entries.
- Steam Big Picture launched and resumed into
  `com.papi.nova.debug/com.papi.nova.Game`.
- Polaris reported `v1.0.18.dirty` with AI, GameLib, AIControl, Adaptive,
  Session, Devices, Lock, Cursor, and Sync enabled.
- Native stream logs reported `RTSP port: 49021`, `Starting video stream...`,
  and `Received first video packet after 0 ms`.
- Decoder setup selected `c2.qti.hevc.decoder.low_latency` for hardware
  decoding `video/hevc` with `width=1920`, `height=1080`, and
  `frame-rate=60`.
- Command Center toggled NovaHUD from `Off` to `On` during the focused pass.
  The live HUD showed host-render-limited status, `11/60` FPS, `6ms`,
  `21M`, `1080p`, and `HEVC`.
- Disconnect returned to
  `com.papi.nova.debug/com.papi.nova.ui.NovaLibraryActivity`.
- Disconnect logs included `Stopping video stream...`,
  `Stopping control stream...`, and `ENet peer acknowledged disconnection`.

### Sanitized HUD Summary

The focused pass produced the HUD summary before local disconnect:

```json
{
  "avg_fps": 11.816755746549308,
  "target_fps": 60.0,
  "low_1_percent_fps": 11.758942604064941,
  "min_fps": 11.758942604064941,
  "frame_pacing_bad_pct": 100.0,
  "safe_target_fps": 30.0,
  "avg_latency_ms": 6.756756756756757,
  "avg_bitrate_kbps": 25000,
  "packet_loss_pct": 0.0,
  "codec": "HEVC",
  "duration_s": 112,
  "samples": 222,
  "recommendation_version": 0,
  "health_grade": "watch",
  "primary_issue": "host_render_limited",
  "issues": ["host_render_limited"],
  "decoder_risk": "normal",
  "hdr_risk": "normal",
  "network_risk": "normal",
  "capture_path": "desktop",
  "safe_bitrate_kbps": 12000,
  "safe_codec": "hevc",
  "safe_display_mode": "headless",
  "safe_hdr": false,
  "relaunch_recommended": true
}
```

The summary omits host, device serial, unique client ID, token, and IP fields.
It contains only session metrics, health classification, codec/capture metadata,
and safe-stream recommendations.

### Log Checks

```bash
adb -s adb-24c12bdd-gitDJe._adb-tls-connect._tcp logcat -d > /tmp/nova-1.1.0-retroid-stream.log
adb -s adb-24c12bdd-gitDJe._adb-tls-connect._tcp logcat -b crash -d > /tmp/nova-1.1.0-retroid-crash.log
rg -i "FATAL EXCEPTION|ANR in com\\.papi\\.nova|AndroidRuntime.*FATAL" /tmp/nova-1.1.0-retroid-stream.log
rg -i "com\\.papi\\.nova|FATAL EXCEPTION|AndroidRuntime|ANR" /tmp/nova-1.1.0-retroid-crash.log
```

No fatal exception or ANR matches were found in the main log. The crash buffer
grep found no Nova crash entries.

### Notes

- The Retroid's Android launcher intercepted ADB-injected Guide/Mode chords, so
  the physical Guide+Y shortcut was not used for this automated pass. NovaHUD
  was enabled from Command Center through the Back-key quick-menu path.
- Disconnect is Nova's local disconnect action. The Polaris session remained
  resumable in the library afterward, as expected for disconnect rather than
  end-session.

## 2026-05-17 Flip 2 Current Master HUD Summary Stream

- Branch: `nova/video-current-master-evidence`
- Base commit: `15015164a8b43a942d7360f33a9f29520fccdaa6`
- APK: `app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk`
- Package: `com.papi.nova.debug`
- Device: Retroid Pocket Flip2, Android 13
- Host: `pc-papi.lan`
- Scenario: run current `master`, launch Steam Big Picture through the paired
  debug package, open Command Center, toggle local mouse cursor behavior, enable
  Nova HUD for the focused pass, then disconnect back to the Nova library.

### Build, Host, And Install

```bash
systemctl --user start polaris.service
nc -vz -w 2 10.0.0.232 47984
nc -vz -w 2 10.0.0.232 47989
nc -vz -w 2 10.0.0.232 47990
nc -vz -w 2 10.0.0.232 48010
./gradlew -PnovaAbis=arm64-v8a assembleNonRoot_gameDebug --console=plain
adb -s adb-498c8ae8-D3D2w1._adb-tls-connect._tcp install -r app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk
```

Polaris was initially inactive on the host, then started cleanly. The required
control and stream ports were reachable before launching Nova. The APK build
completed successfully and installed on the paired Flip 2 debug package.

### Stream Observations

- First clean launch reached `com.papi.nova.debug/com.papi.nova.Game`, selected
  Steam Big Picture, and rendered the Steam Big Picture home screen.
- Current `master` reported Polaris `v1.0.12.dirty` with AI, GameLib,
  AIControl, Adaptive, Session, Devices, Lock, Cursor, and Sync enabled.
- Auto Safe launch policy lowered bitrate from `10000` to `6000` kbps and FPS
  from `60.0` to `30.0`.
- The focused HUD pass resumed the active Steam Big Picture session with
  `rtspenc://10.0.0.232:48010`, `resume=1`, and `1280x720@30`.
- Decoder setup selected `OMX.qcom.video.decoder.hevc` for hardware decoding
  `video/hevc` with `width=1280`, `height=720`, and `frame-rate=30`.
- Native stream logs reported `Received first video packet after 0 ms`.
- Command Center showed Nova HUD toggled from `Off` to `On` during the focused
  pass. It also showed host-render-limited health and a live target of
  `4.7 Mbps`.
- Cursor visibility sync logged `true` at stream start and `false` after local
  cursor behavior was toggled.
- Disconnect returned to
  `com.papi.nova.debug/com.papi.nova.ui.NovaLibraryActivity`.
- Disconnect logs included `Stopping video stream...`,
  `Stopping control stream...`, and `ENet peer acknowledged disconnection`.

### Sanitized HUD Summary

The second pass produced the new sanitized log line before the HUD was dismissed:

```json
{
  "avg_fps": 9.928895027624328,
  "target_fps": 30.0,
  "low_1_percent_fps": 9.90999984741211,
  "min_fps": 9.65,
  "frame_pacing_bad_pct": 100.0,
  "avg_latency_ms": 2.3756906077348066,
  "avg_bitrate_kbps": 0,
  "packet_loss_pct": 0.0,
  "codec": "HEVC",
  "duration_s": 182,
  "samples": 181,
  "recommendation_version": 0,
  "health_grade": "watch",
  "primary_issue": "host_render_limited",
  "issues": ["host_render_limited"],
  "decoder_risk": "normal",
  "hdr_risk": "normal",
  "network_risk": "normal",
  "capture_path": "desktop",
  "safe_bitrate_kbps": 6000,
  "safe_codec": "hevc",
  "safe_display_mode": "headless",
  "safe_hdr": false
}
```

The summary omits host, device serial, unique client ID, token, and IP fields.
It contains only session metrics, health classification, codec/capture metadata,
and safe-stream recommendations.

### Log Checks

```bash
adb -s adb-498c8ae8-D3D2w1._adb-tls-connect._tcp logcat -d > /tmp/nova-flip2-current-master-second-pass-full.log
adb -s adb-498c8ae8-D3D2w1._adb-tls-connect._tcp logcat -b crash -d > /tmp/nova-flip2-current-master-second-pass-crash.log
journalctl --user -u polaris.service --since '2026-05-17 20:35:59' --no-pager > /tmp/polaris-current-master-second-pass-full.log
rg -i "FATAL EXCEPTION|ANR in com\\.papi\\.nova|Application Not Responding|AndroidRuntime.*FATAL" /tmp/nova-flip2-current-master-second-pass-full.log
rg -i "com\\.papi\\.nova|FATAL EXCEPTION|AndroidRuntime|ANR" /tmp/nova-flip2-current-master-second-pass-crash.log
```

No fatal exception or ANR matches were found in the main log. The crash buffer
grep found no Nova crash entries.

### Notes

- The first disconnect pass cleaned up normally but did not emit a HUD summary
  because Nova HUD was off. Its log was retained as
  `/tmp/nova-flip2-current-master-stream-full.log`.
- The focused second pass manually enabled Nova HUD from Command Center before
  disconnecting, which exercised the new summary log path on current `master`.
- Host logs confirmed the headless stream path and `hevc_nvenc` encoder. They
  also recorded the client cursor visibility changes and the client session
  report generated from the HUD summary.

## 2026-05-17 Flip 2 Debug Stream

- Branch: `nova/video-baseline-evidence`
- Base commit: `be8dbb8da3d29eb484b1c6d8f50bb500000692be`
- APK: `app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk`
- Package: `com.papi.nova.debug`
- Device: Retroid Pocket Flip2, Android 13
- Host: `pc-papi.lan`
- Scenario: launch Steam Big Picture through the paired debug package, hold the
  stream open, open Command Center, toggle local mouse cursor behavior, then
  disconnect back to the Nova library.

### Build And Install

```bash
./gradlew -PnovaAbis=arm64-v8a assembleNonRoot_gameDebug --console=plain
adb -s adb-498c8ae8-D3D2w1._adb-tls-connect._tcp install -r app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk
adb -s 24c12bdd install -r app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk
```

The APK built successfully and installed on both connected Retroid devices. The
Pocket 6 debug package was not paired, so the stream run used the paired Flip 2
debug package.

### Observations

- Stream launched into `com.papi.nova.debug/com.papi.nova.Game`.
- Launch profile reported `1280x720@30`, HEVC, and a safe live bitrate of
  `4.7 Mbps`.
- Decoder selected `OMX.qcom.video.decoder.hevc` for hardware decoding
  `video/hevc`.
- Decoder input format reported `width=1280` and `height=720`.
- Decoder output format reported HEVC raw output with HDR static info and
  `width=1280`.
- Native stream logs reported `Starting video stream...` and
  `Received first video packet after 0 ms`.
- Cursor visibility sync logged `true` at stream start and `false` after local
  cursor behavior was toggled.
- Disconnect returned to `com.papi.nova.debug/com.papi.nova.ui.NovaLibraryActivity`.
- Disconnect logs included `Stopping video stream...`,
  `Stopping control stream...`, and `ENet peer acknowledged disconnection`.

### Log Checks

```bash
adb -s adb-498c8ae8-D3D2w1._adb-tls-connect._tcp logcat -d > /tmp/nova-flip2-video-baseline.log
adb -s adb-498c8ae8-D3D2w1._adb-tls-connect._tcp logcat -b crash -d > /tmp/nova-flip2-video-baseline-crash.log
rg -i "FATAL EXCEPTION|ANR in com\\.papi\\.nova|Application Not Responding|AndroidRuntime.*FATAL" /tmp/nova-flip2-video-baseline.log
rg -i "com\\.papi\\.nova|FATAL EXCEPTION|AndroidRuntime|ANR" /tmp/nova-flip2-video-baseline-crash.log
```

No fatal exception or ANR matches were found in the main log. The crash buffer
was empty.

### Notes

- UIAutomator returned malformed XML on the Flip 2 library screen, so coordinate
  driving was verified against screenshots for this pass.
- The host kept Steam Big Picture resumable after client disconnect, which is the
  expected Command Center `Disconnect` behavior for this scenario.
- Follow-up tuning should wait for comparable before/after evidence on the same
  host, title, device, and launch profile.
