# Shared Polaris Layer

This directory is reserved for the Polaris contract layer shared across Nova clients.

Expected contents:

- capabilities mapping
- session status mapping
- library and launch-mode mapping
- request and response helpers shared by Android, Deck, and later iOS

Rules:

- preserve Polaris wire behavior where practical
- keep this layer free of platform UI concerns
- keep it focused on transport, parsing, and contract normalization
