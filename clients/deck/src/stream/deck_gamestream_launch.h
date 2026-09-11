#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <string_view>

// GameStream launch protocol for the Deck's own streaming session. A live
// stream needs three things this file produces, all before any socket is
// opened: the AES stream keys the host and client share, the launch/resume
// request the host answers with an RTSP session, and the parse of that answer.
// Nothing here touches moonlight-common-c or the network; it is pure protocol
// so it can be unit-tested on any host. deck_stream_core owns the moonlight
// structs and the connection call; this only prepares what they need.
namespace nova::deck::stream {

/// The AES stream keys a GameStream launch negotiates.
///
/// `rikeyId` is a signed 32-bit id that rides the launch URL as a decimal int.
/// The IV is 16 bytes with `rikeyId` in big-endian order in the first four and
/// zero after, which is what moonlight-common-c expects in
/// `STREAM_CONFIGURATION.remoteInputAesIv`; `aesKey` is `remoteInputAesKey`.
struct DeckStreamKeys {
    std::array<std::uint8_t, 16> aesKey{};
    std::int32_t rikeyId = 0;
    std::array<std::uint8_t, 16> aesIv{};
};

/// Build the keys from a fixed 16-byte key and id. The IV is derived from the
/// id; the key is copied through. Pure, so tests can pin exact bytes.
DeckStreamKeys buildStreamKeys(const std::array<std::uint8_t, 16>& aesKey, std::int32_t rikeyId);

/// Generate fresh keys from the system's cryptographic random source.
DeckStreamKeys generateStreamKeys();

/// Lowercase hex of 16 bytes, as the launch URL's `rikey` value.
std::string toHexLower(const std::array<std::uint8_t, 16>& bytes);

/// The parameters Nova sends to start or resume a session.
struct DeckLaunchRequest {
    int appId = 0;
    std::string appUuid;          ///< optional; omitted from the URL when empty
    int width = 1280;
    int height = 800;
    int fps = 60;
    bool sops = true;             ///< host-side optimal settings
    bool playLocalAudio = false;
    int surroundAudioInfo = 0;    ///< channel-count/mask the audio config reports
    int gamepadMask = 0;          ///< remoteControllersBitmap and gcmap
    bool persistGamepads = false; ///< gcpersist
    bool resume = false;          ///< false = /launch, true = /resume
    /// Extra query the host wants appended verbatim (it already begins with `&`).
    /// Production passes moonlight-common-c's LiGetLaunchUrlQueryParameters(),
    /// which enables extended Sunshine handshake features; kept as a caller
    /// string so this module needs no moonlight dependency.
    std::string extraQuery;
};

/// Build the request target (path plus query) for the launch or resume GET.
/// The host is never named here; the caller sends it over its own connection.
/// Caller-supplied values (the app uuid, the extra query's keys and values) are
/// percent-encoded so they cannot smuggle extra parameters into the query.
std::string buildLaunchTarget(const DeckLaunchRequest& request, const DeckStreamKeys& keys);

/// The outcome of a launch or resume response.
struct DeckLaunchResult {
    bool started = false;         ///< gamesession (launch) or resume element was non-zero
    int statusCode = 0;           ///< the root element's status_code attribute
    std::string statusMessage;    ///< the root element's status_message attribute, when present
    std::string rtspSessionUrl;   ///< sessionUrl0, the RTSP URL for the session
    std::string sessionToken;     ///< sessionToken, when the host returns one
};

/// Parse a launch or resume XML response. `resume` selects which element proves
/// the session started. Pure; a malformed body yields a not-started result.
DeckLaunchResult parseLaunchResponse(bool resume, std::string_view xml);

/// Build the request target that asks the host to end the app this client
/// started. The session token the launch returned rides along when known, so a
/// host that tracks sessions by token can match it; the paired identity is
/// what authorizes the request either way.
std::string buildCancelTarget(std::string_view sessionToken);

/// The outcome of a cancel response.
struct DeckCancelResult {
    bool cancelled = false;       ///< the cancel element was non-zero
    int statusCode = 0;           ///< the root element's status_code attribute
    std::string statusMessage;    ///< the root element's status_message attribute, when present
};

/// Parse a cancel XML response. Pure; a malformed body yields a not-cancelled result.
DeckCancelResult parseCancelResponse(std::string_view xml);

}  // namespace nova::deck::stream
