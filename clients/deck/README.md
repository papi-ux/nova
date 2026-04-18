# Nova Deck Client

This directory is reserved for the native Steam Deck client.

Current status: scaffold only.

Planned role:

- first non-Android Nova client
- native Linux and SteamOS implementation
- controller-first fullscreen handheld UX
- built on top of the shared Nova backend layers rather than the Android shell

Expected dependencies:

- `../../shared/models/`
- `../../shared/polaris/`
- `../../shared/stream-core/`

Primary design reference:

- [`../../docs/steam_deck_native_port_study.md`](../../docs/steam_deck_native_port_study.md)

Guardrails:

- do not copy the Android UI framework into this client
- preserve Nova product behavior where it matters
- keep Deck-specific input, presentation, and lifecycle handling native to Linux and SteamOS
