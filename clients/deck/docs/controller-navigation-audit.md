# Controller navigation acceptance

The Settings audit found an empty Nova category when no updater backend exists.
Moving right focused a hidden Clear Search button. Empty categories now focus the
visible Back action; empty search results still focus Clear Search.

The headless regression uses directions and OK to enter the empty category,
leave it, close Settings, and reopen it. Existing QML tests cover pairing,
settings choices, on-screen text entry, errors, saved-PC and library navigation.
Run those tests for the candidate; test coverage is not physical pad acceptance.

Before leaving draft, walk discovery, PIN pairing, errors/cancellation, library,
Play Setup and saved overrides, every Settings category, and in-stream controls
with a physical controller. Repeat with no hosts, no games, no search results,
disabled actions, and a disconnected pad. Check visible focus, scrolling, Back,
and focus restoration. Record gaps separately from the Settings fix in this PR.
