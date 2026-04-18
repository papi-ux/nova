# Nova Clients

This directory holds non-Android Nova clients that live alongside the existing Android app in `app/`.

Current direction:

- `app/` remains the shipping Android client
- `clients/deck/` is the first non-Android target
- `clients/ios/` follows after the shared Android plus Deck layers stabilize

The repo uses platform-specific UIs with shared backend layers where they are actually useful.

See:

- [`../docs/multi_platform_monorepo.md`](../docs/multi_platform_monorepo.md)
- [`../docs/steam_deck_native_port_study.md`](../docs/steam_deck_native_port_study.md)
