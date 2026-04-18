# Nova iOS Client

This directory is reserved for the future native iOS client.

Current status: scaffold only.

Planned role:

- iPhone and iPad client in the same Nova repo
- native iOS UX rather than an Android-style shell
- reuse shared models, session contracts, and Polaris mappings where they fit cleanly

Expected dependencies:

- `../../shared/models/`
- `../../shared/polaris/`
- `../../shared/stream-core/`

Guardrails:

- keep the iOS UI native-ish
- reuse backend contracts, not Android lifecycle assumptions
- add this client after the Android plus Deck shared boundaries settle
