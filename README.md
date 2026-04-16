<div align="center">

# Nova

**Game streaming that feels native on Android.**

Stream PC games to your phone or handheld over your local network.
Built for [Polaris](https://github.com/papi-ux/polaris), compatible with any Moonlight server.

[![Stars](https://img.shields.io/github/stars/papi-ux/nova?style=for-the-badge&color=7c73ff&labelColor=1a1a2e)](https://github.com/papi-ux/nova/stargazers)
[![License](https://img.shields.io/github/license/papi-ux/nova?style=for-the-badge&color=4c5265&labelColor=1a1a2e)](LICENSE.txt)
[![Release](https://img.shields.io/github/v/release/papi-ux/nova?style=for-the-badge&color=4ade80&labelColor=1a1a2e&label=latest)](https://github.com/papi-ux/nova/releases/latest)

[Install](#install) · [Why Nova](#why-nova) · [Screenshots](#screenshots) · [Build](#build-from-source) · [FAQ](#faq)

**Support**: [Issues](https://github.com/papi-ux/nova/issues) · **Donate**: [Ko-fi](https://ko-fi.com/papiux) · [PayPal](https://www.paypal.com/donate/?hosted_button_id=KD9R5KLYF6GN4)

<br/>

<picture>
  <source media="(prefers-color-scheme: light)" srcset="docs/screenshots/nova-showcase.gif" width="720" />
  <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/nova-showcase-oled.gif" width="720" />
  <img src="docs/screenshots/nova-showcase.gif" width="720" alt="Nova — Welcome, Servers, Games, Library, Quick Menu" />
</picture>

</div>

<br/>

## Install

<div align="center">

[![Get it on Obtainium](https://img.shields.io/badge/Obtainium-Get_Nova-7c73ff?style=for-the-badge&logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0iI2ZmZiIgZD0iTTEyIDJMMi41IDcuNVYxNi41TDEyIDIybDkuNS01LjVWNy41TDEyIDJ6bTAgMi4xN2w2LjkgNHYuMDFsLTYuOSA0LTYuOS00di0uMDFMNiA4LjE3bDYtMy44M3oiLz48L3N2Zz4=&labelColor=1a1a2e)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%20%22com.papi.nova%22%2C%20%22url%22%3A%20%22https%3A//github.com/papi-ux/nova%22%2C%20%22author%22%3A%20%22papi-ux%22%2C%20%22name%22%3A%20%22Nova%22%2C%20%22additionalSettings%22%3A%20%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22arm64%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v%28.%2B%29%5C%22%7D%22%7D)
&nbsp;
[![Get it on GitHub](https://img.shields.io/badge/GitHub-Releases-4c5265?style=for-the-badge&logo=github&labelColor=1a1a2e)](https://github.com/papi-ux/nova/releases/latest)

</div>

> [!NOTE]
> If you distribute Nova from a private GitHub repo, Obtainium requires a Personal Access Token with `repo` scope. Public release repos do not.

**Target devices:** Retroid Pocket 6 (primary), Pixel 10

---

## Why Nova

Nova is a Moonlight-compatible Android client with a UI and feature set tuned for handhelds, phones, and Polaris-powered setups.

- **Fast pairing**: TOFU on trusted LANs, plus QR and manual PIN pairing.
- **Handheld-first UI**: game grid, quick menu, drag-and-tap HUD, and theme support that feel native on Android.
- **Polaris-aware features**: richer library metadata, live session state, quality recommendations, and session reporting.
- **Explicit 10-bit opt-in**: enabling HDR can request a 10-bit stream from Polaris even when the handheld panel itself is SDR-only.
- **Connection resilience**: reconnect overlay with backoff instead of dropping the session immediately.
- **Input depth**: gyro aim, audio haptics, multiple mouse modes, and broad controller support.

## At A Glance

| Area | What You Get |
|---|---|
| Pairing | TOFU, QR code, manual PIN |
| Streaming | H.264, HEVC, AV1 decode |
| HUD | Full, banner, FPS-only modes |
| Input | Gyro aim, audio haptics, gamepads, mouse modes |
| Polaris | REST + SSE integration, cover art, recommendations, session reports |
| Background | Quick Settings tile, keep-alive service, lock screen overlay |

---

## Screenshots

<table>
<tr>
<td><img src="docs/screenshots/welcome.png" width="400" alt="Welcome"/><br/><sub>Welcome — landscape two-column onboarding</sub></td>
<td><img src="docs/screenshots/servers.png" width="400" alt="Servers"/><br/><sub>Servers — auto-discovered with status, QR pairing sidebar</sub></td>
</tr>
<tr>
<td><img src="docs/screenshots/games.png" width="400" alt="Games"/><br/><sub>Game grid — cover art from Polaris, search bar, virtual display</sub></td>
<td><img src="docs/screenshots/library.png" width="400" alt="Library"/><br/><sub>Nova Library — genre chips, source badges, category filters</sub></td>
</tr>
<tr>
<td><img src="docs/screenshots/quick-menu.png" width="400" alt="Quick Menu"/><br/><sub>Quick menu — hotkeys, overlays, controls, session actions</sub></td>
<td><img src="docs/screenshots/pairing.png" width="400" alt="Pairing"/><br/><sub>Discovery — "Online · Not Paired" with lock icon, TOFU ready</sub></td>
</tr>
<tr>
<td><img src="docs/screenshots/servers-oled.png" width="400" alt="OLED"/><br/><sub>OLED Dark Galaxy — true black with space particles</sub></td>
<td><img src="docs/screenshots/games-oled.png" width="400" alt="Games OLED"/><br/><sub>Game grid (OLED) — cover art pops on pure black</sub></td>
</tr>
</table>

---

## Features

**Streaming** — H.264, HEVC, AV1 decode. Streaming presets (Performance / Balanced / Quality) apply with one tap. Proactive bitrate monitor auto-reduces via the Polaris API when FPS drops, gradually recovers when stable. On Polaris/Main10-capable hosts, enabling HDR in Nova is treated as an explicit 10-bit opt-in, so Nova can request a 10-bit SDR stream even when the Android display itself does not expose HDR10 support.

**Input** — Gyro aiming maps device gyroscope to mouse delta for FPS camera control. Audio haptics convert bass frequencies into vibration (Off / Subtle / Strong). Up to 8 gamepads with USB driver support, per-axis deadzone, face button flip. Mouse modes: Direct, Trackpad, Relative.

**HUD Modes** — Tap to cycle, drag to reposition:

| Mode | Display |
|------|---------|
| Full | Sparkline FPS history, 1% low, per-stat colors, codec + resolution + bitrate |
| Banner | `HEVC │ 60 FPS │ 12ms │ 50 Mbps │ 1080p │ ~~~` |
| FPS Only | Floating pill: `60 fps` |

**Quick Menu** — Bottom sheet with 6 hotkeys (ESC, Alt+Enter, Alt+F4, F11, Super, Ctrl+V), three columns of toggles (Nova HUD, MangoHud, Perf Stats / Mouse, Controller, Keyboard / Paste, Rotate, Special Keys), Disconnect and End buttons. On Polaris-backed Steam Big Picture and Steam/Proton titles, Nova warns before enabling MangoHud on the next launch.

**Polaris Integration** — Capabilities probing on connect. Live session state via SSE. Game library with cover art, genres, and optimization recommendations. Smart launch sends display dimensions for resolution matching. Session reports feed the recommendation loop.

**Background** — Quick Settings tile starts streaming from your notification shade. Keep-alive foreground service on app-switch (5-min auto-stop). Lock screen overlay.

---

## Nova vs Moonlight

| | Nova | Moonlight |
|---|---|---|
| **Pairing** | TOFU + QR code + PIN | PIN only |
| **Performance HUD** | 3 modes, sparkline, drag-to-reposition, proactive bitrate | Static overlay |
| **Reconnection** | 4-attempt auto-reconnect with backoff | — |
| **Input** | Gyro aiming, audio haptics | Standard gamepad/touch |
| **Game library** | Cover art grid, genres, recommendations, search | Text list |
| **Settings** | 6 categories, streaming presets | 15+ categories |
| **Themes** | Space Whale + OLED Galaxy + Material You | Single dark |
| **Server integration** | REST API, SSE events, session reports | NVHTTP only |
| **Quick menu** | 3-column with hotkeys, MangoHud toggle | Basic menu |

> [!TIP]
> Nova is fully backward-compatible. It works with Sunshine and Apollo — Polaris features activate automatically when a Polaris server is detected.

---

## Architecture

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

All new code lives in the Kotlin layer. The Java core is battle-tested Moonlight — targeted modifications only.

---

## Build From Source

### Requirements

| Tool | Version |
|------|---------|
| JDK | 17 |
| Android NDK | 27.0.12077973 |
| Android SDK | compileSdk 36 |
| Git | with submodule support |

### Build

```bash
git clone --recursive https://github.com/papi-ux/nova.git
cd nova

# Release APK (arm64-only by default, signed with debug key)
./gradlew assembleNonRoot_gameRelease

# Debug APK (separate package ID, can install alongside release)
./gradlew assembleNonRoot_gameDebug
```

Output: `app/build/outputs/apk/nonRoot_game/release/app-nonRoot_game-arm64-v8a-release.apk`

> [!TIP]
> Nova now defaults to an `arm64-v8a` deliverable to keep the shipped APK smaller and the release path simpler for modern Android handhelds and phones.
>
> Need extra ABIs for emulator or legacy-device testing?
> `./gradlew assembleNonRoot_gameDebug -PnovaAbis=arm64-v8a,armeabi-v7a,x86,x86_64`

### Install on device

```bash
adb install -r app/build/outputs/apk/nonRoot_game/release/app-nonRoot_game-arm64-v8a-release.apk
```

<details>
<summary><b>Build flavors & tests</b></summary>

| Flavor | Package | Notes |
|--------|---------|-------|
| `nonRoot_game` | `com.papi.nova` | Standard build — use this |
| `nonRoot_gameDebug` | `com.papi.nova.debug` | Debug, installs alongside release |

```bash
./gradlew :app:testNonRoot_gameDebugUnitTest   # Robolectric tests
```

</details>

---

## FAQ

<details>
<summary><b>Does Nova work with Sunshine / Apollo, not just Polaris?</b></summary>

Yes. Nova is a standard Moonlight client and works with Moonlight-compatible servers. Polaris-specific features activate automatically when a Polaris server is detected.

</details>

<details>
<summary><b>Why does Nova warn me before enabling MangoHud?</b></summary>

On Polaris-backed Steam Big Picture and Steam/Proton titles, MangoHud can crash helper processes early enough to leave the session black-screened. Nova now flags those launches before you enable MangoHud so the safer default is obvious.

</details>

<details>
<summary><b>Can Nova request a 10-bit stream on an SDR display?</b></summary>

Yes. When you explicitly enable HDR in Nova and the server supports Main10, Nova can request a 10-bit stream even if the handheld screen itself is SDR-only. This is especially useful with Polaris on handhelds such as Retroid devices: Nova requests the 10-bit path, and Polaris can keep the active stream on `p010` without requiring the device panel to advertise HDR10.

</details>

<details>
<summary><b>Why can't I find Nova on the Play Store?</b></summary>

Nova is distributed via GitHub Releases and Obtainium. If you keep your own fork private, Obtainium will require a GitHub Personal Access Token with `repo` scope to check for updates.

</details>

<details>
<summary><b>My server shows "Online · Not Paired"</b></summary>

Your server was discovered but pairing has not completed. Use one of three paths: **TOFU** on a trusted LAN, **QR code** from the Polaris web UI, or a **manual PIN** from the server UI.

</details>

<details>
<summary><b>The stream has audio static/crackle at startup</b></summary>

This was a known issue fixed in v1.4.0. The audio receiver now defers playback until real audio data arrives, eliminating the buffer underrun crackle. Update to the latest version.

</details>

<details>
<summary><b>How do I switch between HUD modes?</b></summary>

Tap the HUD overlay to cycle through modes: Full panel → Banner → FPS only → Off. Drag the HUD to reposition it anywhere on screen. The HUD can be toggled from the Quick Menu under Overlays.

</details>

<details>
<summary><b>Can I use gyro aiming with any game?</b></summary>

Gyro aiming maps your device's gyroscope to mouse movement. It works with any game that accepts mouse input for camera control — most FPS and third-person games. Adjust sensitivity and Y-axis inversion in Settings → Input & Controllers.

</details>

<details>
<summary><b>What's the difference between the three themes?</b></summary>

**Space Whale** (default) — Deep navy backgrounds with ice-blue text and purple accents. **OLED Dark Galaxy** — Pure black backgrounds for OLED screens (saves battery, looks stunning). **Material You** — Pulls your Android 12+ system accent color for a personalized look (falls back to Space Whale on older devices).

</details>

---

## Donate

I build Nova and Polaris in my spare time because game streaming on Linux and Android deserves better. If you find it useful, a donation helps keep development going.

[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-ff5e5b?style=for-the-badge&logo=ko-fi&labelColor=1a1a2e)](https://ko-fi.com/papiux)
&nbsp;
[![PayPal](https://img.shields.io/badge/PayPal-Donate-7c73ff?style=for-the-badge&logo=paypal&labelColor=1a1a2e)](https://www.paypal.com/donate/?hosted_button_id=KD9R5KLYF6GN4)

Thank you to everyone who's supported the project — you're the reason it keeps getting better.

---

## Contributing

Contributions are welcome — bug fixes, new features, UI polish, translations.

1. Fork the repo and create a branch from `master`
2. Build with `./gradlew assembleNonRoot_gameDebug` and test on a device or emulator
3. New Polaris integration code goes in `com.papi.nova` (Kotlin). Moonlight core changes go in the existing Java layer — targeted modifications only.
4. Open a pull request with a clear description of what changed and why

> [!NOTE]
> The native streaming layer (`app/src/main/jni/moonlight-core/`) is a git submodule. Run `git submodule update --init --recursive` after cloning.

---

## License

Nova is licensed under the **GNU General Public License v3.0** — see [LICENSE.txt](LICENSE.txt) for the full text.

Nova is a fork of [Artemis](https://github.com/ClassicOldSong/moonlight-android) by ClassicOldSong, which is itself a fork of [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) by Cameron Gutman, Diego Waxemberg, Aaron Neyer, and Andrew Hennessy. All are GPLv3. The native streaming core is [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c).
