#pragma once

#include <functional>
#include <optional>
#include <string>
#include <string_view>

#include "polaris/deck_polaris_client.h"
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

/// A fetcher over the pinned-certificate Polaris client, so the GameStream
/// requests ride the same mTLS identity the library read uses. `client` must
/// outlive the fetcher. A host that could not be reached at all (no answer,
/// timeout, unusable identity) is a transport failure; every other outcome is
/// an answer and carries the HTTP status, with the body only when it was 2xx.
DeckHttpFetcher fetcherOverPolarisClient(const polaris::DeckPolarisClient& client);

/// The outcome of assembling a connection descriptor.
struct DeckSessionBuildResult {
    bool ok = false;
    std::string error;   ///< public-safe reason when not ok; never carries the address
    /// The host answered the launch and did not start the session (an HTTP
    /// error, a busy host, a refusal). False when the host was never reached
    /// or serverinfo already failed.
    bool launchRefused = false;
    int launchStatusCode = 0;        ///< the launch answer's root status_code, when parsed
    std::string launchStatusMessage; ///< the launch answer's status_message, when present
    DeckStreamConnectionInfo connectionInfo;  ///< carries the host session token when the host returned one
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

/// The outcome of asking the host to end the app after the stream is down.
struct DeckHostCancelOutcome {
    bool requested = false;      ///< a cancel request was sent
    bool transportOk = false;    ///< the host was reached
    int httpStatus = 0;
    bool cancelled = false;      ///< the host confirmed the app ended
    int hostStatusCode = 0;      ///< the answer's root status_code
    std::string hostStatusMessage;
    std::string summary;         ///< one public-safe line for a report
};

/// Ask the host to end the app this client launched. Best effort: the outcome
/// is reported, never thrown. The host refuses while a stream session is still
/// attached, so callers tear the connection down first; a refusal on that
/// ground is retried a few times with a short pause.
DeckHostCancelOutcome requestHostSessionCancel(const DeckHttpFetcher& fetch, const std::string& sessionToken);

}  // namespace nova::deck::stream
