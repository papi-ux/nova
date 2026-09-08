#pragma once

#include <functional>
#include <optional>
#include <string>
#include <string_view>

#include "stream/deck_gamestream_launch.h"
#include "stream/deck_stream_core.h"

// Assembles a DeckStreamConnectionInfo from a host: read its serverinfo for the
// versions and codec support, issue the launch to get an RTSP session, and pair
// that with the AES keys. The host is reached through an injected fetcher, so the
// assembly is testable without a network; production wires the fetcher to the
// pinned-certificate GET the Polaris client already performs.
namespace nova::deck::stream {

/// The fields a launch needs from the host's GameStream serverinfo.
struct DeckServerInfo {
    std::string appVersion;            ///< appversion tag
    std::string gfeVersion;            ///< GfeVersion tag, empty when absent
    int serverCodecModeSupport = 0;    ///< ServerCodecModeSupport tag
};

/// Parse the serverinfo fields a launch needs. Pure; nullopt on a bad root or a
/// missing appversion.
std::optional<DeckServerInfo> parseServerInfo(std::string_view xml);

/// One host request: the fetcher is given a request target ("/path" or
/// "/path?query") and returns the HTTP status, the body, and whether the
/// transport reached the host at all.
struct DeckHttpResponse {
    bool transportOk = false;
    int status = 0;
    std::string body;
};
using DeckHttpFetcher = std::function<DeckHttpResponse(const std::string& target)>;

/// The outcome of assembling a connection descriptor.
struct DeckSessionBuildResult {
    bool ok = false;
    std::string error;   ///< public-safe reason when not ok; never carries the address
    DeckStreamConnectionInfo connectionInfo;
};

/// Read serverinfo and issue the launch through `fetch`, then assemble the
/// connection descriptor. `keys` are supplied so a test can pin them; production
/// passes generateStreamKeys(). The host address is not requested here, only
/// carried into the result, so the fetcher owns how the host is reached.
DeckSessionBuildResult buildStreamConnection(
    const DeckHttpFetcher& fetch,
    const std::string& serverAddress,
    const DeckLaunchRequest& request,
    const DeckStreamKeys& keys);

}  // namespace nova::deck::stream
