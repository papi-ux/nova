<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/Polaris_icon_1_dark.svg">
  <img src="docs/screenshots/Polaris_icon_1.svg" width="140" alt="Nova">
</picture>

# Nova

**Your PC games, in your hands.**

Nova plays games from your PC on Android handhelds, phones, tablets and TVs.
Browse your library with a controller, see how a game will launch before it
starts, and keep the stream's controls a shortcut away. It's at its best with
Polaris on a Linux PC, and other Moonlight-compatible hosts work too. Native
Linux and Steam Deck builds are in alpha.

[![Stars](https://img.shields.io/github/stars/papi-ux/nova?style=for-the-badge&color=7c73ff&labelColor=1f1d31)](https://github.com/papi-ux/nova/stargazers)
[![Matrix](https://img.shields.io/badge/Matrix-Join_chat-0dbd8b?style=for-the-badge&logo=matrix&logoColor=white&labelColor=1f1d31)](https://matrix.to/#/#papi-ux:papi-ux.com)
[![License](https://img.shields.io/github/license/papi-ux/nova?style=for-the-badge&color=4c5265&labelColor=1f1d31)](LICENSE.txt)
[![Release](https://img.shields.io/github/v/release/papi-ux/nova?style=for-the-badge&color=c8d6e5&labelColor=1f1d31&label=latest)](https://github.com/papi-ux/nova/releases/latest)

[**Get Nova**](https://papi-ux.com/downloads/?product=nova) ·
[Explore Nova](https://papi-ux.com/nova/) ·
[Quick start](https://papi-ux.com/docs/nova/quickstart/) ·
[Join the Matrix community](https://matrix.to/#/#papi-ux:papi-ux.com)

</div>

<img src="docs/screenshots/divider-aurora.svg" width="100%" height="3" alt="">

![Nova on a Retroid Pocket 6 showing Control Ultimate Edition: hours played, the stream it will ask for (1920×1080 at 120 FPS, SDR) and Launch, Play Setup and Clear Game Profile](docs/screenshots/beta2/nova-control-detail-aurora.webp)

## Why Nova

- **A library that feels like a console.** Your host's games appear with their
  cover art, ready to browse with a controller, touch or keyboard. Stage,
  Regular and Compact layouts fit a TV across the room or a handheld in your
  lap, and text and control sizes adjust separately.
- **Know before you press play.** Play Setup shows where the game will run, its
  resolution, frame rate and codec, before anything starts. A choice for one
  session leaves your host's defaults alone, and its advice is a starting
  point, not a promise about your network.
- **Controls a shortcut away.** Command Center brings tuning, Doctor's advice,
  the NovaHUD stream readout, input helpers and Steam controls over the running
  game. Press Guide + Start to open it and Close to get straight back to play.
- **It understands your host.** With Polaris, Nova knows the launch choices your
  PC offers, who owns a session and what Doctor found. Any Moonlight-compatible
  host still gets pairing, browsing, Wake-on-LAN, launch and streaming. Steam
  controls and the Android Tailscale readout need Polaris 1.5.0-beta.2 or newer.
- **Leave on your terms.** Disconnect keeps the game running for later. End
  Session asks before stopping the game you started, and Watch joins a stream
  without taking it over. [Stream reports](docs/stream-reports.md) save what
  you saw and send nothing unless you share them.

![Nova Play Setup for Control: Private Stream, where it runs, resolution, frame rate, codec and what Steam does after End Session](docs/screenshots/beta2/nova-play-setup-aurora.webp)

Polaris Aurora is the default look, with five more themes to choose from.
1.5.0-beta.2 adds a seventh, `< Congratulations, Director >`: charcoal, warm
white and red. See them in the [theme gallery](https://papi-ux.com/nova/#themes).

![The same Nova game page in the < Congratulations, Director > theme: charcoal, warm white and red](docs/screenshots/beta2/nova-control-detail-director.webp)

## Get started

[**Get Nova**](https://papi-ux.com/downloads/?product=nova) helps you pick the
right download for your device. If you'd rather choose yourself:

| Your device | Download |
| --- | --- |
| Most Android handhelds, phones, tablets and TVs | [Nova-Android-arm64-v8a.apk](https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-arm64-v8a.apk) |
| 32-bit Android, including Shield models with a 32-bit app ABI | [Nova-Android-armeabi-v7a.apk](https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-armeabi-v7a.apk) |
| Android x86_64 devices and emulators | [Nova-Android-x86_64.apk](https://github.com/papi-ux/nova/releases/latest/download/Nova-Android-x86_64.apk) |
| Linux and Steam Deck, **alpha** | `Nova-Linux-x86_64-alpha.flatpak` from [Releases](https://github.com/papi-ux/nova/releases); see the [Linux guide](https://papi-ux.com/docs/nova/linux/) and [Steam Deck guide](https://papi-ux.com/docs/nova/steam-deck/) |
| Steam Frame, **experimental** | A separate ARM64 alpha, only when a release includes it; see the [Steam Frame guide](https://papi-ux.com/docs/nova/steam-frame/) |

Choose by the **Android app ABI**, not the processor name: a 64-bit processor
can still offer only 32-bit Android app support.

1. Install Nova, open **Hosts**, find or add your PC, and pair.
2. Pick a game, check Play Setup, and play.
3. During play, Guide + Start opens Command Center. Guide + Y shows or cycles
   NovaHUD.

**Updates and betas.** Follow Nova with
[Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.papi.nova%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpapi-ux%2Fnova%22%2C%22author%22%3A%22papi-ux%22%2C%22name%22%3A%22Nova%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22Nova-Android-arm64-v8a%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v%28.%2B%29%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%221%5C%22%7D%22%7D)
or [GitHub Store](https://github-store.org/app?repo=papi-ux/nova).
[Latest](https://github.com/papi-ux/nova/releases/latest) is always stable. A
beta installs as a separate Nova Beta app (`Nova-Beta-Android-<ABI>.apk`) with
its own pairing and preferences; see
[updates and betas](https://papi-ux.com/docs/nova/updates/).

## Good to know

- **Android 5.0 and later.** Codecs, HDR, controls and frame rates depend on
  your device and host. Check the
  [compatibility guide](https://papi-ux.com/docs/nova/compatibility/).
- **This page follows the source**, which can be ahead of the newest release.
  Install only files attached to a published release.
- **Experimental and off by default:** PyroWave on Linux and Steam Frame and in
  Nova Beta for Android, plus SGSR 1 upscaling for PyroWave SDR streams in Nova
  Beta for Android. In one short Control test on a Retroid Pocket 6, 720p
  looked good with or without SGSR 1, with no noticeable difference, and native
  1080p looked nicer. That's one look at one scene, not a measurement; the
  [PyroWave guide](https://papi-ux.com/docs/pyrowave-beta/#optional-sgsr-1-upscaling-on-android)
  has the details.
- **Alpha is maturity, not a channel.** The Linux and Steam Deck build ships in
  stable and beta releases; its wider desktop interface is in 1.5.0-beta.2.
  Steam is optional for ordinary Linux use, and the Frame alpha is not Steam
  Frame Verified.

## Learn more

- [Nova documentation](https://papi-ux.com/docs/nova/) · [Quick start](https://papi-ux.com/docs/nova/quickstart/) · [Play Setup](https://papi-ux.com/docs/nova/play-setup/) · [Spaces in Nova](https://papi-ux.com/docs/nova/spaces/) · [Connection facts and profiles](docs/network-profiles.md) · [Compatibility](https://papi-ux.com/docs/nova/compatibility/) · [Steam Deck](https://papi-ux.com/docs/nova/steam-deck/)
- [Roadmap](https://papi-ux.com/docs/roadmap/) · [Changelog](CHANGELOG.md) · [Releases](https://github.com/papi-ux/nova/releases)
- [Matrix community](https://matrix.to/#/#papi-ux:papi-ux.com) · [Issues](https://github.com/papi-ux/nova/issues) · [Discussions](https://github.com/papi-ux/nova/discussions) · [Security policy](SECURITY.md) · [Contributing](.github/CONTRIBUTING.md) · [Source](https://github.com/papi-ux/nova)

<sub>Screenshots: 1.5.0-beta.2 ([details](docs/screenshots/beta2/provenance.json)).
Website gallery: [image sources](https://papi-ux.com/images/products/showcase-v1.3.8-v1.3.6-provenance.json).</sub>

## Acknowledgments

Nova for Android grew from moonlight-android, and every Nova build streams
through moonlight-common-c. Thanks to the Moonlight community for that
foundation. SGSR 1 is Qualcomm's Snapdragon Game Super Resolution 1
(BSD-3-Clause), adapted after RubyLight's Vulkan port.

## AI Transparency

I use tools including Codex, Claude and local models for research, drafting,
review and debugging. I review and test changes, decide what ships, and own
the engineering and trust-boundary decisions.

## Contributing

Focused fixes, docs, translations and careful feature work are welcome.
Explain the problem and keep changes scoped; see [CONTRIBUTING](.github/CONTRIBUTING.md).

## License

Nova is free and open-source software licensed under the [GNU General Public
License v3.0](LICENSE.txt).

<div align="center">

<img src="docs/screenshots/divider-aurora.svg" width="100%" height="3" alt="">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/Polaris_icon_1_dark.svg">
  <img src="docs/screenshots/Polaris_icon_1.svg" width="56" alt="Polaris and Nova mascot">
</picture>

<sub>[Website](https://papi-ux.com/nova/) · [Matrix](https://matrix.to/#/#papi-ux:papi-ux.com) · [Documentation](https://papi-ux.com/docs/nova/) · [Releases](https://github.com/papi-ux/nova/releases) · [Security](SECURITY.md)</sub>

</div>
