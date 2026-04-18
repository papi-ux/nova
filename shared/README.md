# Nova Shared Layers

This directory holds backend layers intended to be reused across Nova clients.

These layers are for:

- portable data models
- portable Polaris contracts
- streaming and pairing session boundaries

These layers are not for:

- platform UI
- Android `Activity` or `Service` behavior
- Steam Deck shell logic
- iOS navigation or presentation code

The goal is shared behavior below the UI, with each platform keeping a platform-tuned client shell.
