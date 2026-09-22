#pragma once

#include "backend/deck_live_read_only_state.h"
#include "stream/deck_gamestream_session_builder.h"
#include "runtime/deck_hud_host.h"
#include "stream/deck_video_capabilities.h"
#include <QString>
#include <functional>
#include <optional>

namespace nova::deck::runtime {
/// Backend-only launch material. Never registered with QML or put in a public
/// state map. The fetcher owns the lifetime of its pinned HTTPS client.
struct DeckNativeLaunchTarget {
    stream::DeckStreamRequest request;
    int appId = 0;
    std::string appUuid;
    std::string serverAddress;
    stream::DeckHttpFetcher fetch;
    // Fresh pinned catalog and game-contract check, only for explicit modes.
    std::function<bool(const std::string&, const std::function<bool()>&)> authorizeLaunchMode;
    std::function<bool(const QString&, const QString&, const std::function<bool()>&)> authorizeSetup;
    DeckStreamCapabilities streamCapabilities;
    std::function<std::optional<DeckStreamCapabilities>(const std::function<bool()>&)> verifyStreamCapabilities;
    std::function<stream::DeckVideoDecodeSupport()> probeVideoSupport;
    bool automaticReconnect = false; // Polaris route, still requires fresh ownership/token checks
    std::function<bool()> transientCapabilityFailure;
    DeckHudHostFactory hostTelemetry;
};
using DeckNativeTargetResolver = std::function<std::optional<DeckNativeLaunchTarget>(
    const QString& hostId, const QString& gameId)>;

/// Resolve only the host whose library supplied the selected game. Construct
/// the pinned HTTPS client on the calling worker, never on the UI thread.
DeckNativeTargetResolver nativeTargetResolver(
    std::optional<identity::DeckMoonlightIdentity> identity,
    std::optional<backend::DeckLiveHostLibrarySnapshot> snapshot);

} // namespace nova::deck::runtime
