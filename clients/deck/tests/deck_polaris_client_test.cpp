// Tests for the Polaris read-only client: the JSON contracts Nova already
// depends on, and the failure shape when nothing answers.
#include "polaris/deck_polaris_client.h"

#include <QCoreApplication>

#include <cassert>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <sstream>
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

std::string readFixture(const char* name) {
    std::ifstream in(std::filesystem::path(NOVA_DECK_FIXTURE_SOURCE_DIR) / name);
    assert(in && "fixture must exist");
    std::ostringstream body;
    body << in.rdbuf();
    return body.str();
}

// The hand-written bodies above encode what we assumed Polaris serves. These
// two were recorded from Polaris 1.4.4 on pc-papi with a paired Moonlight
// certificate (fixtures/polaris_1_4_4_*.json, verbatim), and pin the three
// things the assumed shape got wrong the first time: `app_id` is a string,
// `total` counts the page, not the library, and ids are dashed UUIDs.
void testParsesBodiesPolarisActuallyServed() {
    const auto capabilities = parseCapabilities(readFixture("polaris_1_4_4_capabilities.json"));
    assert(capabilities.has_value());
    assert(capabilities->server == "polaris" && "lowercase on the wire");
    assert(capabilities->version == "1.4.4");
    assert(capabilities->gameLibrary && capabilities->sessionLifecycle && capabilities->clientSettings);
    assert(capabilities->captureBackend == "portal");
    assert(capabilities->codecs.size() == 1 && capabilities->codecs[0] == "h264");

    const auto page = parseGamesPage(readFixture("polaris_1_4_4_games_limit2_offset0.json"));
    assert(page.has_value());
    assert(page->games.size() == 2);
    assert(page->total == 2 && "limit=2 on a bigger library: total is the page, so paging must not stop on it");

    const auto& bigPicture = page->games[0];
    assert(bigPicture.id == "CDEAD7A8-D05B-3E2C-F20B-C6D58352A19D");
    assert(bigPicture.appId == 1231570251 && "served as the string \"1231570251\"");
    assert(bigPicture.name == "Steam Big Picture");
    assert(bigPicture.source == "manual");
    assert(bigPicture.steamAppid.empty());
    assert(bigPicture.installed && !bigPicture.hdrSupported);
    assert(bigPicture.platform.empty() && bigPicture.runtime.empty() && "not served for manual entries");
    assert(bigPicture.lastLaunched == 1787198388LL);
    assert(bigPicture.launchPreferredMode == "headless_stream");
    assert(bigPicture.launchRecommendedMode == "headless_stream");
    assert(bigPicture.launchAllowedModes.size() == 6);
    assert(!bigPicture.launchModeReason.empty());
    assert(!bigPicture.steamLaunchAvailable && bigPicture.steamLaunchAllowedModes.empty());
    assert(bigPicture.genres.empty());

    const auto& indy = page->games[1];
    assert(indy.appId == 1630208108);
    assert(indy.name == "Indiana Jones and the Great Circle");
    assert(indy.source == "steam" && indy.steamAppid == "2677660");
    assert(indy.category == "fast_action");
    assert(indy.launchPreferredMode == "host_virtual_display");
    assert(indy.launchRecommendedMode == "headless_stream");
    assert(indy.steamLaunchAvailable && indy.steamLaunchMode == "direct" && indy.steamLaunchRecommendedMode == "direct");
    assert(indy.steamLaunchAllowedModes.size() == 2);
    assert(indy.genres.size() == 2 && indy.genres[0] == "Action");
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
    const auto unaddressed = probeServerInfoHttpsPort("", 47989, std::chrono::milliseconds(50));
    assert(!unaddressed.httpsPort.has_value() && !unaddressed.timedOut);
    // A refusal answers at once and must not be mistaken for a silent address:
    // the live route skips the HTTPS probe only for the latter.
    const auto refused = probeServerInfoHttpsPort("127.0.0.1", 1, std::chrono::milliseconds(2000));
    assert(!refused.httpsPort.has_value() && !refused.timedOut);
}

void testSplitsPathAndQueryForQUrl() {
    // Field bug from the first pc-papi probe: the games page went out as
    // /polaris/v1/games%3Flimit=100 and the host answered 404.
    const auto paged = splitRequestTarget("/polaris/v1/games?limit=100&offset=0");
    assert(paged.path == "/polaris/v1/games");
    assert(paged.query == "limit=100&offset=0");
    const auto plain = splitRequestTarget("/polaris/v1/capabilities");
    assert(plain.path == "/polaris/v1/capabilities");
    assert(plain.query.empty());
    const auto emptyQuery = splitRequestTarget("/x?");
    assert(emptyQuery.path == "/x" && emptyQuery.query.empty());
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
    testParsesBodiesPolarisActuallyServed();
    testParsesSessionStatus();
    testParsesServerInfoHttpsPort();
    testSplitsPathAndQueryForQUrl();
    testDescribeCoversEveryStatus();
    testInvalidIdentityFailsClosedWithoutTouchingTheNetwork();
    return 0;
}
