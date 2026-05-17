# Nova Video Baseline Evidence

This file records the measurement-only evidence pass for the Nova audit
follow-up video work. It does not tune frame-drop thresholds, decoder watchdog
timing, frame pacing policy, or launch-quality decisions.

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
