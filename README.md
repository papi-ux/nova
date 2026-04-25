<div align="center">

# Nova

**Game streaming that feels native on Android.**

Stream PC games to phones and handhelds over your local network.
Built for [Polaris](https://github.com/papi-ux/polaris), compatible with other Moonlight-compatible hosts.

[![Stars](https://img.shields.io/github/stars/papi-ux/nova?style=for-the-badge&color=7c73ff&labelColor=1a1a2e)](https://github.com/papi-ux/nova/stargazers)
[![License](https://img.shields.io/github/license/papi-ux/nova?style=for-the-badge&color=4c5265&labelColor=1a1a2e)](LICENSE.txt)
[![Release](https://img.shields.io/github/v/release/papi-ux/nova?style=for-the-badge&color=4ade80&labelColor=1a1a2e&label=latest)](https://github.com/papi-ux/nova/releases/latest)

[Install](#install) · [Quick Start](#quick-start) · [Compatibility](#compatibility) · [Known Limitations](#known-limitations) · [Roadmap](ROADMAP.md) · [Why Nova](#why-nova) · [With Polaris](#with-polaris) · [Screenshots](#screenshots) · [Build](#build-from-source) · [Security](SECURITY.md) · [Changelog](CHANGELOG.md) · [FAQ](#faq)

**Support**: [Issues](https://github.com/papi-ux/nova/issues) · [Discussions](https://github.com/papi-ux/nova/discussions)

<br/>

<picture>
  <img src="docs/screenshots/nova-showcase.gif" width="820" alt="Nova on Android: server browser, game grid, library detail sheet, quick menu, and live stream HUD" />
</picture>

</div>

<br/>

## Install

<div align="center">

[![Get it on Obtainium](https://img.shields.io/badge/Obtainium-Get_Nova-7c73ff?style=for-the-badge&logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0iI2ZmZiIgZD0iTTEyIDJMMi41IDcuNVYxNi41TDEyIDIybDkuNS01LjVWNy41TDEyIDJ6bTAgMi4xN2w2LjkgNHYuMDFsLTYuOSA0LTYuOS00di0uMDFMNiA4LjE3bDYtMy44M3oiLz48L3N2Zz4=&labelColor=1a1a2e)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.papi.nova%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpapi-ux%2Fnova%22%2C%22author%22%3A%22papi-ux%22%2C%22name%22%3A%22Nova%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22app-nonRoot_game-arm64-v8a-release%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v%28.%2B%29%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%221%5C%22%7D%22%7D)
&nbsp;
[![Get it on GitHub Store](https://img.shields.io/badge/GitHub_Store-Get_Nova-24292f?style=for-the-badge&logo=github&labelColor=1a1a2e)](https://github-store.org/app?repo=papi-ux/nova)
&nbsp;
[![Get it on GitHub](https://img.shields.io/badge/GitHub-Releases-4c5265?style=for-the-badge&logo=github&labelColor=1a1a2e)](https://github.com/papi-ux/nova/releases/latest)

</div>

**Recommended install path**

1. Download the latest release from GitHub Releases, add Nova to Obtainium, or open it in GitHub Store on Android.
2. Install the APK that matches your Android device.
   Most phones and handhelds use `app-nonRoot_game-arm64-v8a-release.apk`; x86_64 Android devices and emulators use `app-nonRoot_game-x86_64-release.apk`.
3. Open Nova, add or discover your host, then pair it.

| Public release asset | Use it for |
|---|---|
| `app-nonRoot_game-arm64-v8a-release.apk` | Recommended Android install for phones and handhelds |
| `app-nonRoot_game-x86_64-release.apk` | Android x86_64 devices and emulators |
| `*.apk.sha256` | Integrity checks for the public APKs |

The Obtainium link above is preconfigured for the public `app-nonRoot_game-arm64-v8a-release.apk` asset so updates resolve to one APK cleanly. The GitHub Store link opens Nova's public release repo for users who prefer that installer; GitHub Store filters assets for the device it is running on, so its desktop app may show Nova as unavailable because Nova ships Android APKs.

If you install manually, verify the download before sideloading:

```bash
sha256sum -c app-nonRoot_game-arm64-v8a-release.apk.sha256
```

> [!NOTE]
> If you distribute Nova from a private GitHub fork, Obtainium needs a Personal Access Token with `repo` scope. Public release repos do not.

> [!NOTE]
> `v1.0.0` is the first public Nova release line. Nova is already usable, but this is still an early public release and you should expect bugs, regressions, and rough edges while the Android client and Polaris integration continue to harden. `app/` is the only shipping client today.

**Built and tested most heavily on:** Retroid Pocket 6, Retroid Pocket Flip 2, Pixel 10 Pro.

## Quick Start

### First stream

1. **Install Nova** from GitHub Store, Obtainium, or GitHub Releases.
2. **Add your server** from the Servers screen. Polaris hosts appear automatically on the LAN when discovery is enabled.
3. **Pair once** using one of three paths:
   - **Trusted Pair (TOFU)** on a trusted subnet
   - **QR pairing** from the Polaris web UI
   - **Manual PIN** pairing for standard Moonlight servers
4. **Launch a game** from the game grid or the Polaris library.
5. **Use the quick menu** for stream tuning, overlays, controller actions, and quit/disconnect controls.

### If you use Polaris

Nova gets the best experience when the host is Polaris:

- featured **Continue** surface with live/watch state, cover art, and one-tap resume
- host-recommended **Headless** or **Virtual Display** launch modes
- live **ACT / TGT FPS** HUD readouts
- watch active stream without stealing ownership
- owner-aware quit and resume
- clear **Baseline / AI tune / Cached AI / Recovery tune** labels in Polaris-backed flows
- live host tuning for Adaptive Bitrate, AI Optimizer, and MangoHud
- richer library metadata, cover art, and per-game recommendations

### If you use another compatible host

Nova still works as a standard Moonlight client. Pair normally, launch normally, and stream normally. Polaris-only UI simply stays out of the way.

## Compatibility

| Area | Status | Notes |
|---|---|---|
| Android handhelds | Primary target | Designed first for landscape handheld use |
| Android phones and tablets | Supported | Works well, but the UX is tuned most heavily for handhelds |
| Polaris | Best experience | Full launch-mode, watch-mode, tuning, library, and live-session integration |
| Other Moonlight-compatible hosts | Compatible | Standard Moonlight-compatible client flow |
| High refresh devices | Supported | Nova can request 90/120 Hz when the device display and host both support it |
| Official release assets | `arm64-v8a`, `x86_64` | Public GitHub Releases ship separate APKs per Android ABI |

## Known Limitations

- Advanced launch modes, watch mode, live host tuning, and richer session telemetry are Polaris-specific.
- Nova is not on the Play Store; the public install paths are GitHub Releases, Obtainium, and GitHub Store.
- High refresh streaming is limited by the real display panel on the Android device, not just the selected setting in Nova.
- Public releases currently ship `arm64-v8a` and `x86_64` APKs. Other ABIs are available from local source builds.
- Today, only the Android client ships.

## Why Nova

Nova is a Moonlight-compatible Android client built for handhelds first, not desktop assumptions squeezed onto a touch screen.

- **Handheld-first UI**: large game art, clear session actions, controller-friendly navigation, and OLED-aware themes
- **Clear launch surfaces**: host library screens keep the primary action obvious instead of burying resume/watch behind generic grids
- **Practical session controls**: quick menu, multi-mode HUD, reconnect overlay, and live stream state
- **Deep input support**: gyro aim, audio haptics, gamepads, mouse modes, and touch controls
- **Polaris-aware workflow**: library metadata, launch-mode choices, watch mode, session ownership, live tuning, and stream reports

## With Polaris

| Capability | What It Does |
|---|---|
| Launch modes | Pick **Headless** or **Virtual Display** per launch when the host supports it |
| 10-bit opt-in | Enabling HDR can request a 10-bit stream even on SDR handheld displays |
| Watch Stream | Join an active session as a passive viewer instead of taking ownership |
| Session truth | HUD and quick menu show the live mode, owner/viewer role, and negotiated stream state |
| AI state | Library and quick menu can distinguish baseline device tuning, live AI, cached AI, recovery tuning, and host-adjusted recommendations |
| Stream tuning | Toggle Adaptive Bitrate, AI Optimizer, and MangoHud from the quick menu |
| Library | Cover art, genres, source badges, recommendations, and per-game launch guidance |

## Core Features

- **Streaming and HUD**: H.264, HEVC, and AV1 decode; full, banner, and FPS-only HUD modes; actual vs target FPS labels; reconnect overlay; quality presets for quick setup
- **Input**: gyro aim, audio haptics, broad controller support, Direct/Trackpad/Relative mouse modes, and compact handheld on-screen controls
- **Polaris flow**: host-backed library, Continue/watch flows, explicit Headless vs Virtual Display launches, AI source labels, live tuning, and warnings before risky MangoHud launches

## Screenshots

<table>
<tr>
<td><img src="docs/screenshots/nova-themes.gif" width="400" alt="Themes"/><br/><sub>Main menu and theme system</sub></td>
<td><img src="docs/screenshots/nova-hud-modes.gif" width="400" alt="HUD modes"/><br/><sub>Nova HUD modes and on-stream toggles</sub></td>
</tr>
<tr>
<td><img src="docs/screenshots/nova-home.png" width="400" alt="Games home"/><br/><sub>Games home with Continue rail and host shortcuts</sub></td>
<td><img src="docs/screenshots/nova-library-grid.png" width="400" alt="Library grid"/><br/><sub>Polaris library with filters, search, and HDR-ready badges</sub></td>
</tr>
<tr>
<td><img src="docs/screenshots/nova-library-detail.png" width="400" alt="Library detail sheet"/><br/><sub>Per-game launch modes and next-launch tuning</sub></td>
<td><img src="docs/screenshots/nova-quick-menu-detail.png" width="400" alt="Quick menu"/><br/><sub>Quick menu for tuning, overlays, controls, and session actions</sub></td>
</tr>
</table>

<details>
<summary><b>Architecture</b></summary>

```mermaid
block-beta
  columns 1

  block:kotlin["com.papi.nova — Kotlin"]:1
    columns 4
    api["api/\nREST + SSE"]
    manager["manager/\nResilience"]
    ui["ui/\nHUD, Themes\nLibrary, Menu"]
    service["service/\nQS Tile\nKeep-alive"]
  end

  block:java["com.papi.nova — Java (Moonlight core)"]:1
    columns 5
    PcView["PcView\nServers"]
    AppView["AppView\nGames"]
    Game["Game\nStream"]
    nvstream["nvstream/\nProtocol"]
    binding["binding/\nDecode"]
  end

  block:native["moonlight-common-c — C / NDK"]:1
    columns 3
    enet["enet\nTransport"]
    fec["Reed-Solomon\nFEC"]
    opus["Opus\nAudio"]
  end

  kotlin --> java --> native

  style kotlin fill:#7c73ff22,stroke:#7c73ff,color:#d4dde8
  style java fill:#4c526522,stroke:#687b81,color:#a8b0b8
  style native fill:#1a1a2e,stroke:#4c5265,color:#687b81
```

All new Nova-specific behavior lives in the Kotlin layer. The Java core stays close to Moonlight and is changed surgically.

</details>

## Build From Source

### Requirements

| Tool | Version |
|------|---------|
| JDK | 17 |
| Android NDK | 27.0.12077973 |
| Android SDK | compileSdk 36 |
| Git | with submodule support |

### Clone

```bash
git clone --recursive https://github.com/papi-ux/nova.git
cd nova
```

### Repository Layout

| Path | Purpose |
|---|---|
| `app/` | Current Android client |

Android is the only public release target today.

### Build

```bash
# Release APKs
./gradlew assembleNonRoot_gameRelease

# Debug APKs (installs alongside release as com.papi.nova.debug)
./gradlew assembleNonRoot_gameDebug
```

By default, local source builds produce split APKs for `arm64-v8a` and `x86_64`.

> [!TIP]
> Official GitHub releases ship a signed `arm64-v8a` APK for real devices as `app-nonRoot_game-arm64-v8a-release.apk`.
>
> If you want a different ABI set locally:
> `./gradlew assembleNonRoot_gameDebug -PnovaAbis=arm64-v8a,armeabi-v7a,x86,x86_64`

### Install on device

Use the ABI-specific APK that matches your device from `app/build/outputs/apk/nonRoot_game/<buildType>/`.

Example for a real arm64 device:

```bash
adb install -r app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-arm64-v8a-debug.apk
```

<details>
<summary><b>Build flavors and tests</b></summary>

| Flavor | Package | Notes |
|--------|---------|-------|
| `nonRoot_game` | `com.papi.nova` | Standard release build |
| `nonRoot_gameDebug` | `com.papi.nova.debug` | Debug build, installs alongside release |

```bash
./gradlew :app:testNonRoot_gameDebugUnitTest
```

</details>

## FAQ

<details>
<summary><b>Does Nova work with other Moonlight-compatible hosts, not just Polaris?</b></summary>

Yes. Nova is a Moonlight-compatible client. Polaris adds the richest integration, but Nova still works with other Moonlight servers.

</details>

<details>
<summary><b>What is Trusted Pair?</b></summary>

Trusted Pair is Nova’s TOFU flow. If Polaris trusts the subnet you are on, Nova can complete first pairing without the usual PIN ceremony. You can still use QR or manual PIN pairing when you want the traditional flow.

</details>

<details>
<summary><b>What is the difference between Headless and Virtual Display?</b></summary>

**Headless** launches against Polaris’ isolated compositor path without touching your physical desktop layout. **Virtual Display** asks the host for a virtual display-backed launch instead. Nova’s Polaris library now shows what the host recommends, what the app prefers, and which modes are currently allowed.

</details>

<details>
<summary><b>Can Nova request a 10-bit stream on an SDR display?</b></summary>

Yes. When you explicitly enable HDR in Nova and the server supports Main10, Nova can request a 10-bit stream even if the handheld screen itself does not advertise HDR10. This is especially useful with Polaris on handhelds such as Retroid devices.

</details>

<details>
<summary><b>What does Watch Stream do?</b></summary>

Watch Stream lets a second device join an already running Polaris session as a passive viewer. It does not take ownership, and viewer sessions are limited to the active stream profile rather than silently renegotiating their own version.

</details>

<details>
<summary><b>Why does Nova warn me before enabling MangoHud?</b></summary>

On Polaris-backed Steam Big Picture and Steam/Proton titles, MangoHud can crash helper processes early enough to leave the session black-screened. Nova flags those launches before you enable MangoHud so the safer choice is obvious.

</details>

<details>
<summary><b>Is there a native Steam Deck or iOS client yet?</b></summary>

Not today. Nova currently ships as an Android client only.

</details>

<details>
<summary><b>Why can't I find Nova on the Play Store?</b></summary>

Nova is distributed through GitHub Releases, Obtainium, and GitHub Store. The official public release path is GitHub first.

</details>

## AI Transparency

Nova is built by me, with help from AI tools including Anthropic Claude, OpenAI Codex, and local models.

I use them as a sounding board: to compare approaches, pressure-test UI ideas, draft tests and docs, chase down awkward bugs, and spot things I might have missed. They do not decide what Nova is or what ships. I have been around engineering and IT for a while, and that has made me careful about validation, trust boundaries, and release quality. I review the work, test the pieces I can test, and own the final decisions.

## Contributing

Contributions are welcome, especially focused fixes, UI polish, docs, translations, and careful feature work. Nova is still a small maintainer-led project, so the easiest pull requests to review are the ones that explain the problem clearly and keep the change scoped.

1. Fork the repo and branch from `master`.
2. Build with `./gradlew assembleNonRoot_gameDebug`.
3. Test on a real device or emulator.
4. Open a pull request that explains what changed, why it helps, and what you were able to test.

> [!NOTE]
> The native streaming layer in `app/src/main/jni/moonlight-core/` is a git submodule. Run `git submodule update --init --recursive` after cloning.

## Donate

Nova is something I build in my spare time because I want handheld game streaming to feel more thoughtful, more capable, and less fragile. If it becomes part of your setup, donations are appreciated but never expected. They help with testing devices, packaging work, release time, and the unglamorous maintenance that keeps the project moving. Bug reports, testing notes, and thoughtful feedback help too.

[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-ff5e5b?style=for-the-badge&logo=ko-fi&labelColor=1a1a2e)](https://ko-fi.com/papiux)
&nbsp;
[![PayPal](https://img.shields.io/badge/PayPal-Donate-7c73ff?style=for-the-badge&logo=paypal&labelColor=1a1a2e)](https://www.paypal.com/donate/?hosted_button_id=KD9R5KLYF6GN4)

## License

Nova is licensed under the **GNU General Public License v3.0**. See [LICENSE.txt](LICENSE.txt) for the full text.

Nova builds on [Artemis](https://github.com/ClassicOldSong/moonlight-android), [Moonlight Android](https://github.com/moonlight-stream/moonlight-android), and [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) under GPLv3 lineage.
