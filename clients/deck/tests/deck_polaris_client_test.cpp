// Tests for the Polaris read-only client: the JSON contracts Nova already
// depends on, and the failure shape when nothing answers.
#include "polaris/deck_polaris_client.h"

#include <QCoreApplication>

#include <cassert>
#include <chrono>
#include <string>

namespace {

using namespace nova::deck::polaris;

void testParsesCapabilities() {
    const auto capabilities = parseCapabilities(R"({
        "server": "Polaris", "version": "1.4.4",
        "features": {"game_library": true, "session_lifecycle": true, "client_settings_v1": true,
                     "resolved_profile_provenance_v1": true, "expected_topology_assertion_v1": false},
        "capture": {"backend": "wlr", "compositor": "labwc", "max_fps": 120, "codecs": ["h264", "hevc"]}
    })");
    assert(capabilities.has_value());
    assert(capabilities->server == "Polaris");
    assert(capabilities->version == "1.4.4");
    assert(capabilities->gameLibrary);
    assert(capabilities->sessionLifecycle);
    assert(capabilities->clientSettings);
    assert(capabilities->resolvedProfileProvenance);
    assert(!capabilities->expectedTopologyAssertion);
    assert(capabilities->captureBackend == "wlr");
    assert(capabilities->codecs.size() == 2 && capabilities->codecs[1] == "hevc");
    assert(!parseCapabilities("not json").has_value());
    assert(!parseCapabilities("[1,2]").has_value());
}

void testParsesGamesPageFromHostShape() {
    const auto page = parseGamesPage(R"({
        "games": [
            {"id": "0123456789abcdef0123456789abcdef", "app_id": 7, "name": "Portal 2", "steam_appid": "620",
             "category": "fast_action", "source": "steam", "installed": true, "hdr_supported": false,
             "cover_url": "/polaris/v1/games/0123456789abcdef0123456789abcdef/cover", "last_launched": 1718187600000,
             "platform": "linux", "runtime": "proton", "genres": ["Puzzle"],
             "launch_mode": {"preferred_mode": "virtual_display", "recommended_mode": "headless",
                             "allowed_modes": ["headless", "virtual_display"], "mode_reason": "Host default is headless."},
             "steam_launch": {"available": true, "mode": "big-picture", "recommended_mode": "direct",
                              "allowed_modes": ["direct", "big-picture"], "mode_reason": "Steam Input fallback."}},
            {"id": "", "name": "dropped because it has no id"},
            {"id": "ffff", "app_id": "9", "name": "Bare entry"}
        ],
        "total": 3
    })");
    assert(page.has_value());
    assert(page->total == 3);
    assert(page->games.size() == 2);
    const auto& portal = page->games[0];
    assert(portal.id == "0123456789abcdef0123456789abcdef");
    assert(portal.appId == 7);
    assert(portal.name == "Portal 2");
    assert(portal.steamAppid == "620");
    assert(portal.source == "steam");
    assert(portal.platform == "linux");
    assert(portal.runtime == "proton");
    assert(portal.lastLaunched == 1718187600000LL);
    assert(portal.genres.size() == 1);
    assert(portal.launchPreferredMode == "virtual_display");
    assert(portal.launchRecommendedMode == "headless");
    assert(portal.launchAllowedModes.size() == 2);
    assert(portal.launchModeReason == "Host default is headless.");
    assert(portal.steamLaunchAvailable);
    assert(portal.steamLaunchRecommendedMode == "direct");
    const auto& bare = page->games[1];
    assert(bare.appId == 9 && "Polaris emits app_id as a string");
    assert(bare.installed);
    assert(bare.launchRecommendedMode.empty());
    assert(!bare.steamLaunchAvailable);
    assert(!parseGamesPage(R"({"total": 1})").has_value());
}

void testParsesSessionStatus() {
    const auto status = parseSessionStatus(R"({
        "state": "streaming", "streaming_active": true, "game": "Portal 2", "game_uuid": "abc",
        "owner_device_name": "Pixel10Pro", "client_role": "VIEWER", "owned_by_client": false, "viewer_count": 1
    })");
    assert(status.has_value());
    assert(status->state == "streaming");
    assert(status->streamingActive);
    assert(status->game == "Portal 2");
    assert(status->gameUuid == "abc");
    assert(status->ownerDeviceName == "Pixel10Pro");
    assert(status->clientRole == "viewer");
    assert(!status->ownedByClient);
    assert(status->viewerCount == 1);
    const auto idle = parseSessionStatus("{}");
    assert(idle.has_value());
    assert(idle->state == "unknown");
    assert(idle->clientRole == "none");
}

void testParsesServerInfoHttpsPort() {
    assert(parseServerInfoHttpsPort("<root status_code=\"200\"><hostname>pc</hostname><HttpsPort>47984</HttpsPort></root>") == 47984);
    assert(parseServerInfoHttpsPort("<root><HttpsPort>1229</HttpsPort></root>") == 1229);
    assert(!parseServerInfoHttpsPort("<root><hostname>pc</hostname></root>").has_value());
    assert(!parseServerInfoHttpsPort("<root><HttpsPort></HttpsPort></root>").has_value());
    assert(!parseServerInfoHttpsPort("<root><HttpsPort>47a84</HttpsPort></root>").has_value());
    assert(!parseServerInfoHttpsPort("<root><HttpsPort>99999</HttpsPort></root>").has_value());
    assert(!resolveHttpsPortFromServerInfo("", 47989, std::chrono::milliseconds(50)).has_value());
}

void testDescribeCoversEveryStatus() {
    assert(describe(DeckPolarisRequestStatus::Ok) == "ok");
    assert(describe(DeckPolarisRequestStatus::CertMismatch) == "cert-mismatch");
    assert(describe(DeckPolarisRequestStatus::Unauthorized) == "unauthorized");
    assert(describe(DeckPolarisRequestStatus::MalformedBody) == "malformed-body");
}

void testInvalidIdentityFailsClosedWithoutTouchingTheNetwork() {
    const DeckPolarisClient client(DeckPolarisEndpoint{.address = "localhost", .httpsPort = 1}, DeckPolarisTlsIdentity{}, std::chrono::milliseconds(200));
    const auto result = client.get("/polaris/v1/capabilities");
    assert(result.status == DeckPolarisRequestStatus::InvalidIdentity);
    assert(!result.ok());
    const auto capabilities = client.fetchCapabilities();
    assert(capabilities.status == DeckPolarisRequestStatus::InvalidIdentity);
    assert(!capabilities.value.has_value());
}

} // namespace

int main(int argc, char* argv[]) {
    QCoreApplication app(argc, argv);
    testParsesCapabilities();
    testParsesGamesPageFromHostShape();
    testParsesSessionStatus();
    testParsesServerInfoHttpsPort();
    testDescribeCoversEveryStatus();
    testInvalidIdentityFailsClosedWithoutTouchingTheNetwork();
    return 0;
}
