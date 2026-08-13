<div align="center">

<img src="docs/screenshots/Polaris_icon_1.svg" width="140" alt="Nova" />

# Nova

**Game streaming for Android handhelds that understands the host.**

Nova is an Android client for Polaris and standard Moonlight-compatible hosts.
With Polaris it turns a basic app grid into a controller-first Library, explains
each launch before it happens, and keeps live session controls close while you
play.

[![Stars](https://img.shields.io/github/stars/papi-ux/nova?style=for-the-badge&color=7c73ff&labelColor=1a1a2e)](https://github.com/papi-ux/nova/stargazers)
[![License](https://img.shields.io/github/license/papi-ux/nova?style=for-the-badge&color=4c5265&labelColor=1a1a2e)](LICENSE.txt)
[![Release](https://img.shields.io/github/v/release/papi-ux/nova?style=for-the-badge&color=4ade80&labelColor=1a1a2e&label=latest)](https://github.com/papi-ux/nova/releases/latest)

[**Explore Nova**](https://papi-ux.com/nova/) ·
[**Install ARM64**](https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-arm64-v8a.apk) ·
[All releases](https://github.com/papi-ux/nova/releases/latest) ·
[Quick start](https://papi-ux.com/docs/nova/quickstart/)

</div>

> [!IMPORTANT]
> Nova is an Android app today. Android handhelds, phones, and Android TV are
> the supported client boundary; Nova is not a Windows, macOS, iOS, or native
> Linux client.

![Nova Polaris Aurora Library in Stage view with Control Ultimate Edition selected against its landscape artwork](docs/screenshots/nova-library-control-aurora-v1.3.6.webp)

## A compatible client, with more context

| Normal compatible client | Nova with Polaris |
|---|---|
| Pairs, browses apps, launches, and streams | Adds a host-backed Library with art, sources, and launch health |
| Starts the host's configured app path | Shows Private Stream and other available display choices before launch |
| Treats a running session as a generic state | Distinguishes active, resumable, watchable, and owner-aware sessions |
| Offers basic disconnect or quit actions | Separates safe disconnect from ending the host session |
| Shows client-side stream statistics | Adds server-backed runtime truth, Doctor, tuning provenance, and NovaHUD |

Standard Moonlight-compatible hosts remain useful; Polaris supplies the richer
metadata and controls.

## Browse, decide, control

### Browse

Stage view puts the selected title into its landscape artwork while keeping the
row controller-readable. Control Ultimate Edition leads the Aurora showcase;
the [website gallery](https://papi-ux.com/nova/#themes) compares the same screen
in Portable Chrome, Console OLED, Miami Nebula, High Contrast, and Material You.

### Decide

Play Setup describes what will happen for this title: where it runs, the client
resolution, tuning policy, and Steam launch behavior. A session-scoped choice
does not silently rewrite the host default.

![Nova Polaris Aurora Play Setup for Control Ultimate Edition, showing Private Stream and session choices](docs/screenshots/nova-play-setup-control-aurora-v1.3.6.webp)

### Control

During a stream, Command Center brings session health, Doctor guidance, tuning,
NovaHUD, input helpers, safe disconnect, and protected end-session actions into
a controller-first drawer.

![Nova Polaris Aurora Command Center over a live private stream, showing runtime truth and Doctor guidance](docs/screenshots/nova-command-center-live-aurora-v1.3.6.webp)

## Host compatibility

With a standard Moonlight-compatible host, Nova supports the familiar pairing,
Wake-on-LAN, app browsing, launch, input, and streaming path.

With Polaris, Nova additionally understands the host Library, display-mode
catalog, session owner, watch and resume behavior, per-session launch choices,
Polaris Sync, server-backed stream health, Doctor actions, and tuning state.
Those enhanced surfaces require compatible Polaris metadata; Nova falls back to
the standard path when it is not present.

## Install and start a first stream

1. Install the public APK that matches the Android device.
2. Open **Servers**, discover or add the host, and pair.
3. Open the Library, review a title, and launch.
4. During play, open Command Center with Guide/Mode + Start/Menu. Guide/Mode + Y
   shows or cycles NovaHUD.

| Architecture | Direct download | Typical devices |
|---|---|---|
| ARM64 | [Nova-Android-arm64-v8a.apk](https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-arm64-v8a.apk) | Most current handhelds, phones, Shield, and ARM64 Android TV |
| ARMv7 | [Nova-Android-armeabi-v7a.apk](https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-armeabi-v7a.apk) | 32-bit ARM Android TV devices |
| x86_64 | [Nova-Android-x86_64.apk](https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-x86_64.apk) | Android x86_64 devices and emulators |

The [quick-start guide](https://papi-ux.com/docs/nova/quickstart/) covers
Obtainium, pairing methods, first-stream controls, and architecture selection.

## Compatibility and platform boundaries

Nova preserves Android 5.0 / API 21 support, though features and codec behavior
depend on the device, Android build, decoder, controller mapping, network, and
host. Android handhelds are the primary experience; Android TV and phones are
supported with their own navigation constraints. Review the maintained [Nova
compatibility guide](https://papi-ux.com/docs/nova/compatibility/) for current
device, architecture, codec, HDR, sensor, and host notes.

## Documentation and project links

- [Nova documentation](https://papi-ux.com/docs/nova/) · [Quick start](https://papi-ux.com/docs/nova/quickstart/) · [Compatibility](https://papi-ux.com/docs/nova/compatibility/)
- [Roadmap](https://papi-ux.com/docs/roadmap/) · [Changelog](CHANGELOG.md) · [Releases](https://github.com/papi-ux/nova/releases)
- [Issues](https://github.com/papi-ux/nova/issues) · [Discussions](https://github.com/papi-ux/nova/discussions) · [Source](https://github.com/papi-ux/nova)
- [Security policy](SECURITY.md) · [Contributing](.github/CONTRIBUTING.md)

## License

Nova is free and open-source software licensed under the [GNU General Public
License v3.0](LICENSE.txt).
