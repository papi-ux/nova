<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/Polaris_icon_1_dark.svg">
  <img src="docs/screenshots/Polaris_icon_1.svg" width="140" alt="Nova">
</picture>

# Nova

**Game streaming for Android handhelds that understands the host.**

Nova is an Android client for Polaris and standard Moonlight-compatible hosts.
With Polaris it turns a basic app grid into a controller-first Library, explains
each launch before it happens, and keeps live session controls close while you
play.

[![Stars](https://img.shields.io/github/stars/papi-ux/nova?style=for-the-badge&color=7c73ff&labelColor=1f1d31)](https://github.com/papi-ux/nova/stargazers)
[![License](https://img.shields.io/github/license/papi-ux/nova?style=for-the-badge&color=4c5265&labelColor=1f1d31)](LICENSE.txt)
[![Release](https://img.shields.io/github/v/release/papi-ux/nova?style=for-the-badge&color=c8d6e5&labelColor=1f1d31&label=latest)](https://github.com/papi-ux/nova/releases/latest)

[**Explore Nova**](https://papi-ux.com/nova/) ·
[**Install ARM64**](https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-arm64-v8a.apk) ·
[All releases](https://github.com/papi-ux/nova/releases/latest) ·
[Quick start](https://papi-ux.com/docs/nova/quickstart/)

</div>

<img src="docs/screenshots/divider-aurora.svg" width="100%" height="3" alt="">

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

![Nova Polaris Aurora Game Detail for Big Walk with its landscape artwork, ready state, and play actions](docs/screenshots/nova-game-detail-bigwalk-aurora-v1.3.6.webp)

Stage view puts the selected title into its landscape artwork while keeping the
row controller-readable. Control Ultimate Edition leads the Aurora showcase;
the [website gallery](https://papi-ux.com/nova/#themes) compares the same screen
in Portable Chrome, Console OLED, Miami Nebula, High Contrast, and Material You.

<p align="center">
  <a href="https://papi-ux.com/nova/#themes"><img src="docs/screenshots/theme-cycle.webp" width="720" alt="Nova Stage view cycling through the Portable Chrome, Console OLED, Miami Nebula, High Contrast, and Material You themes"></a><br>
  <a href="https://papi-ux.com/nova/#themes"><img src="docs/screenshots/theme-dots.svg" height="14" alt="Theme accent colors"></a><br>
  <sub><a href="https://papi-ux.com/nova/#themes">Compare every theme in the website gallery</a></sub>
</p>

### Decide

Play Setup describes what will happen for this title: where it runs, the client
resolution, tuning policy, and Steam launch behavior. A session-scoped choice
does not silently rewrite the host default.

![Nova Polaris Aurora Play Setup for Control Ultimate Edition, showing Private Stream and session choices](docs/screenshots/nova-play-setup-control-aurora-v1.3.6.webp)

### <img src="docs/screenshots/pulse-ready.svg" width="14" height="14" alt=""> Control

During a stream, Command Center brings session health, Doctor guidance, tuning,
NovaHUD, input helpers, safe disconnect, and protected end-session actions into
a controller-first drawer.

![Nova live private stream of Control Ultimate Edition on a Retroid Pocket 6](docs/screenshots/nova-live-rp6-aurora-v1.3.6.webp)

![Nova Polaris Aurora Command Center over a live private stream, showing runtime truth and Doctor guidance](docs/screenshots/nova-command-center-live-aurora-v1.3.6.webp)

Every capture above and across [papi-ux.com](https://papi-ux.com/nova/) comes from the tagged public release; the [pixel-level provenance manifest](https://papi-ux.com/images/products/showcase-v1.3.8-v1.3.6-provenance.json) ships with the site.

## Host compatibility

With a standard Moonlight-compatible host, Nova supports the familiar pairing,
Wake-on-LAN, app browsing, launch, input, and streaming path.

With Polaris, Nova additionally understands the host Library, display-mode
catalog, session owner, watch and resume behavior, per-session launch choices,
Polaris Sync, server-backed stream health, Doctor actions, and tuning state.
Those enhanced surfaces require compatible Polaris metadata; Nova falls back to
the standard path when it is not present.

<img src="docs/screenshots/divider-aurora.svg" width="100%" height="3" alt="">

## Install and start a first stream

<div align="center">

[![Get it on Obtainium](https://img.shields.io/badge/Obtainium-Get_Nova-7c73ff?style=for-the-badge&logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0iI2ZmZiIgZD0iTTEyIDJMMi41IDcuNVYxNi41TDEyIDIybDkuNS01LjVWNy41TDEyIDJ6bTAgMi4xN2w2LjkgNHYuMDFsLTYuOSA0LTYuOS00di0uMDFMNiA4LjE3bDYtMy44M3oiLz48L3N2Zz4=&labelColor=1f1d31)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.papi.nova%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpapi-ux%2Fnova%22%2C%22author%22%3A%22papi-ux%22%2C%22name%22%3A%22Nova%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22Nova-Android-arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v%28.%2B%29%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%221%5C%22%7D%22%7D)
&nbsp;
[![Get it on GitHub Store](https://img.shields.io/badge/GitHub_Store-Get_Nova-24292f?style=for-the-badge&logo=github&labelColor=1f1d31)](https://github-store.org/app?repo=papi-ux/nova)
&nbsp;
[![Get it on GitHub](https://img.shields.io/badge/GitHub-Releases-4c5265?style=for-the-badge&logo=github&labelColor=1f1d31)](https://github.com/papi-ux/nova/releases/latest)
&nbsp;
[![Latest APK](https://img.shields.io/badge/Latest-ARM64_APK-c8d6e5?style=for-the-badge&logo=android&labelColor=1f1d31)](https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-arm64-v8a.apk)

</div>

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

<img src="docs/screenshots/divider-aurora.svg" width="100%" height="3" alt="">

## Documentation and project links

- [Nova documentation](https://papi-ux.com/docs/nova/) · [Quick start](https://papi-ux.com/docs/nova/quickstart/) · [Compatibility](https://papi-ux.com/docs/nova/compatibility/)
- [Roadmap](https://papi-ux.com/docs/roadmap/) · [Changelog](CHANGELOG.md) · [Releases](https://github.com/papi-ux/nova/releases)
- [Issues](https://github.com/papi-ux/nova/issues) · [Discussions](https://github.com/papi-ux/nova/discussions) · [Source](https://github.com/papi-ux/nova)
- [Security policy](SECURITY.md) · [Contributing](.github/CONTRIBUTING.md)

## Acknowledgments

Nova builds on the moonlight-android client lineage. Thanks to the Moonlight community for the foundation Nova grew from.

## AI Transparency

Nova is built and released by me, with assistance from tools such as OpenAI Codex, Claude, and local models.

I use those tools for documentation polish, release workflow cleanup, store-readiness checks, build/test triage, implementation review, and to compare approaches while debugging. They do not decide what Nova or Polaris are, what features ship, or what releases are published. I review, edit, build, test, and approve the changes before release, and I own the final engineering and trust-boundary decisions.

## Contributing

Contributions are welcome, especially focused fixes, UI polish, docs, translations, and careful feature work. Nova is still a small maintainer-led project, so the easiest pull requests to review are the ones that explain the problem clearly and keep the change scoped. See [CONTRIBUTING](.github/CONTRIBUTING.md) for the full workflow.

## License

Nova is free and open-source software licensed under the [GNU General Public
License v3.0](LICENSE.txt).

<div align="center">

<img src="docs/screenshots/divider-aurora.svg" width="100%" height="3" alt="">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/Polaris_icon_1_dark.svg">
  <img src="docs/screenshots/Polaris_icon_1.svg" width="56" alt="Polaris mascot">
</picture>

<sub>[Website](https://papi-ux.com/nova/) · [Documentation](https://papi-ux.com/docs/nova/) · [Releases](https://github.com/papi-ux/nova/releases) · [Security](SECURITY.md)</sub>

</div>
