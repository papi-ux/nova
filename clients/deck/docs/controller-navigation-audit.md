# Controller navigation acceptance

Source review on 2026-09-26 found one confirmed gap: without an updater backend,
Nova's Settings category has no rows. Moving right focused the hidden Clear
Search button. Empty categories now focus Back to Library; empty search results
still focus the visible Clear action. Closing a child sheet uses the same
fallback if its optional backend has disappeared.

This carries forward the implementation preserved in the closed preliminary
controller-navigation PR. The expanded regression enters every category with
directions and OK, checks a visible enabled destination and the route back,
clears an empty search, closes and reopens Settings, and returns from the update
sheet after its backend disappears. The latter also checks the visible exit at
960 by 600 with 130% text. Browsing these categories must not read or write host
settings.

## Reviewed routes and executable coverage

The paths below are relative to `clients/deck`. They name automated coverage,
not physical controller acceptance or proof of every state combination.

| Journey | Source route | Existing regression coverage |
| --- | --- | --- |
| Discovery and manual entry | `qml/HostSearch.qml`, `qml/PairHost.qml` | `tests/deck_pairing_qml_test.cpp`: local search selection, removed/expired results, Back, endpoint keyboard and large-text scrolling |
| Pairing and failure recovery | `qml/PairHost.qml` | Same fixture: PIN, Trusted Pair fallback, certificate refusal, cancellation, retry, successful entry and safe close |
| Saved PCs and an empty list | `qml/SavedPcs.qml` | Same fixture: Keep PC, explicit forget/unpair, refused removal, Add PC when empty, cancellation and returning from pairing |
| Library, filtering and details | `qml/LibraryBrowser.qml`, `qml/Main.qml` | `tests/deck_library_navigation_test.py`: directional entry, detail/preview return, no search matches, restored selection, scrolling and visible focus |
| Play Setup and choices | `qml/PlaySetup.qml`, `qml/NativeStreamPreview.qml` | `tests/deck_native_preview_qml_test.cpp` and the play-setup app route: choices, Back, adjusted/unsupported plans and return to Play |
| Settings and child sheets | `qml/SettingsHub.qml` | `tests/deck_settings_hub_qml_test.cpp`: all categories, empty category/results, optional backend loss, row restoration, keyboard cancellation, failed persistence, resets and large text |
| Game tools and host settings | `qml/ArtworkStudio.qml`, `qml/HostDefaults.qml`, `qml/Doctor.qml` | `tests/deck_game_tools_qml_test.cpp` plus the game-tools app route; unavailable host settings keep a route back |
| Command Center and ending play | `qml/NativeStreamPreview.qml` | Preview fixture: Continue, Disconnect, End Session confirmation, Players/Reassign, local overlay entry and restored focus |
| Disconnect/error recovery | `qml/NativeStreamPreview.qml` | Preview fixture: connection cancellation, failure return, stale resume refusal and reconnect exit |

Run the QML and app-route tests against the candidate. The app routes require
Xvfb/xdotool and should run serially. The native-preview fixture's original
45-second budget is shorter than its deliberate waits plus rendering; use the
separately reviewed native-fixture corrections when recording combined results.
Do not attribute a combined test result to an unchanged standalone PR revision.

## Physical acceptance still required

Before closing the controller-navigation issue, repeat the complete journey
using only a physical controller on a supported handheld and desktop profile.
Include no hosts, no games, no search results, disabled actions, a disconnected
pad, Steam Input and recovery after reconnect. Check directional movement,
activation, visible focus, scrolling, Back, close/reopen and focus restoration.
Record the exact artifacts, compositor, controller and each remaining gap.

Keyboard events in Qt exercise the direction/activation routes but do not prove
physical mappings, hotplug, Game Mode or controller latency. This change does not
claim those results, and does not change pairing trust or session authority.
