# Shared Stream Core Boundary

This directory is reserved for the cross-client streaming session boundary.

Expected contents:

- pairing session contracts
- host discovery contracts
- streaming session lifecycle contracts
- platform backend interfaces for render, audio, and input integration

This directory does not replace the existing Android-native Moonlight core yet.

Near-term role:

- define the portable boundary that Android and Deck can both consume
- wrap or coordinate with the existing `moonlight-common-c` integration over time
- keep platform-specific media and input implementations outside the shared core
