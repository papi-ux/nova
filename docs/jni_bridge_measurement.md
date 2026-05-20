# JNI Bridge Measurement

Date: 2026-05-20
Release target: Nova 1.1.0

## Candidate Calls

Primitive-only input calls that could be measured for `@FastNative` or
`@CriticalNative`:

- `MoonBridge.sendMouseMove`
- `MoonBridge.sendMouseButton`
- `MoonBridge.sendMultiControllerInput`
- `MoonBridge.sendControllerMotionEvent`
- `MoonBridge.sendKeyboardInput`
- `MoonBridge.sendMouseHighResScroll`
- `MoonBridge.sendMouseHighResHScroll`

## Compatibility Rule

Nova supports `minSdk 21`. `@CriticalNative` changes the native ABI and is not
allowed in 1.1.0 unless the measured benefit is large enough to justify explicit
registration and old-device validation. `@FastNative` is also gated because it
can delay garbage collection while native code runs.

## 1.1.0 Decision

Nova 1.1.0 does not apply JNI bridge annotations by default. The release ships
the HUD and Baseline Profile work first. JNI bridge annotations can follow in a
separate branch after trace evidence shows JNI transition cost is a real input
latency contributor.

## Measurement Command

Use a physical device and a debug build:

```bash
./gradlew -PnovaAbis=arm64-v8a assembleNonRoot_gameDebug --console=plain
adb -s 24c12bdd install -r app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk
adb -s 24c12bdd logcat -c
```

Capture input-heavy stream behavior with Perfetto or Android Studio profiler,
then compare JNI bridge time against decoder, render, and input scheduling time.
Do not annotate JNI calls from static inspection alone.
