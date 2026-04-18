# Nova Multi-Platform Monorepo

Nova is moving toward a single-repo, multi-client layout.

The goal is simple:

- keep Android shipping from this repo today
- add a native Steam Deck client here next
- add an iOS client here later
- keep Android TV inside the Android client rather than splitting it into a separate app
- publish all platform artifacts from one Nova release page when they are ready

Today, only the Android client ships. This document defines the repo direction that future client work should follow.

## Repository layout

| Path | Purpose | Status |
|---|---|---|
| `app/` | Current Android client for handhelds, phones, tablets, and future Android TV polish | Active |
| `clients/deck/` | Native Steam Deck and Linux handheld client | Scaffold |
| `clients/ios/` | Native iOS client | Scaffold |
| `shared/models/` | Cross-client DTOs for hosts, sessions, capabilities, launch metadata, and settings concepts | Scaffold |
| `shared/polaris/` | Cross-client Polaris contract and mapping layer | Scaffold |
| `shared/stream-core/` | Cross-client streaming session boundary around Moonlight transport and pairing behavior | Scaffold |

## Core rules

### 1. Platform UIs stay platform-specific

Nova should share backend behavior, contracts, and data models, not force every platform into one UI stack.

That means:

- Android keeps its existing Android-first UI
- Steam Deck gets a controller-first native shell
- iOS gets a native iOS shell
- Android TV is handled as a form factor of the Android client

### 2. Shared layers stay below the UI

The first shared layers should cover:

- host discovery contracts
- pairing/session contracts
- streaming session lifecycle
- Polaris API contracts and response mapping
- shared models for hosts, sessions, recommendations, and preferences

The shared layers should not own:

- Android `Activity` or `Service` behavior
- Deck windowing and controller UX
- iOS view hierarchy and navigation
- platform-specific media decode, audio output, or input plumbing

### 3. Android remains the reference implementation

The current Android app is the living product and should remain buildable and releasable while shared layers are introduced.

Extraction rule:

- move contracts and models first
- keep Android behavior stable
- do not block Android releases on unfinished Deck or iOS work

## Release model

Nova should use one repo and one release page.

Release expectations:

- one version tag for Nova
- one GitHub release page per version
- multiple assets on that release page as platform clients mature

Examples:

- Android APKs
- Steam Deck Linux artifact(s)
- iOS-related distribution artifact(s) when that client is ready

Until a platform client is ready, the release page may contain only Android assets. The release model stays the same.

## Rollout order

### Phase 1: Monorepo foundation

- keep `app/` as the active Android client
- create `clients/` and `shared/` scaffolding
- document repo boundaries and shared-layer intent

### Phase 2: Steam Deck first

- add the first non-Android client in `clients/deck/`
- prove the shared streaming and Polaris layers against Android plus Deck
- keep the Deck MVP focused on first-stream usability, controller input, fullscreen UX, reconnect, and Polaris essentials

### Phase 3: iOS

- add `clients/ios/` after the Android plus Deck shared boundaries stabilize
- reuse shared models and Polaris contracts where they actually help
- keep iOS UI and platform behaviors native-ish

### Phase 4: Android TV polish

- keep Android TV inside `app/`
- add TV-specific polish in the Android client rather than fragmenting the repo into a second Android app unless there is a strong product reason later

## Relationship to the Deck study

Use this document for repo shape, client boundaries, and release model.

Use [Steam Deck Native Port Study](steam_deck_native_port_study.md) for the Deck-specific client architecture and rollout details.
