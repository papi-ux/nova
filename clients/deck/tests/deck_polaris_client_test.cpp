// Tests for the Polaris read-only client: the JSON contracts Nova already
// depends on, and the failure shape when nothing answers.
#include "polaris/deck_polaris_client.h"

#include <QCoreApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

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
    assert(capabilities->streamCapabilities.supports(1920, 1200, 60));
    assert(capabilities->streamCapabilities.supports(1280, 800, 90));
    assert(!capabilities->streamCapabilities.supports(1280, 800, 120));
    assert(!parseCapabilities("{}")->streamCapabilities.supports(1280, 800, 90));
    for (const auto* capture : {R"({"max_fps":"60"})", R"({"max_fps":0})", R"({"max_fps":-1})",
        R"({"max_fps":1001})", R"({"codecs":"h264"})", R"({"codecs":[true,"h264"]})", "false"}) {
        const auto parsed = parseCapabilities(std::string("{\"capture\":") + capture + "}");
        assert(parsed && !parsed->streamCapabilities.valid);
    }
    const auto hevcOnly = parseCapabilities(R"({"capture":{"codecs":["hevc","av1"],"max_fps":240}})");
    assert(hevcOnly && hevcOnly->streamCapabilities.valid && !hevcOnly->streamCapabilities.h264);
    const auto limited = parseCapabilities(R"({"capture":{"codecs":["H264"],"max_fps":30}})");
    assert(limited && limited->streamCapabilities.supports(1280, 800, 30) && !limited->streamCapabilities.supports(1280, 800, 60));
    assert(parseCapabilities("{}")->streamCapabilities.supports(1280, 800, 60));
}

void testDisplayRecommendations() {
    const auto page = parseGamesPage(R"({"games":[{"id":"game","name":"Fixture","display_planner":{
        "available":true,"recommended_id":"balanced","choices":[
            {"id":"balanced","target_mode":"1920x1200x90","reason":"Preserve aspect ratio."},
            {"id":"sharp","target_mode":"3840x2400x90","safe":true,"advanced":true,"custom":true},
            {"id":"unsafe","target_mode":"1280x800x60","safe":false},
            {"id":"hidden","target_mode":"1280x720x60","hidden":true},
            {"id":"malformed","target_mode":"1920x1080x60","safe":"true"},
            {"id":"injected","target_mode":"1920x1080x60&hdr=1"},
            {"id":"fractional","target_mode":"1920x1080x59.94"}]}}]})");
    assert(page && page->games.size() == 1);
    const auto& plan = page->games.front().displayPlanner;
    assert(plan.available && plan.choices.size() == 3);
    assert(plan.choices.front().recommended && plan.choices.front().width == 1920 && plan.choices.front().height == 1200);
    assert(!plan.choices.back().recommended);
    assert(plan.choices[1].width == 3840 && plan.choices[1].advanced && plan.choices[1].custom);
    for (const auto* planner : {"null", "false", R"({"available":"true","choices":[]})",
        R"({"available":true,"choices":[{"id":"same","target_mode":"1280x800x60"},{"id":"same","target_mode":"1920x1080x60"}]})"}) {
        const auto bad = parseGamesPage(std::string(R"({"games":[{"id":"game","name":"Fixture","display_planner":)") + planner + "}]}");
        assert(bad && !bad->games.front().displayPlanner.available);
    }
}

void testParsesGamesPageFromHostShape() {
    const auto page = parseGamesPage(R"({
        "games": [
            {"id": "0123456789abcdef0123456789abcdef", "app_id": 7, "name": "Portal 2", "steam_appid": "620",
             "category": "fast_action", "source": "steam", "installed": true, "hdr_supported": false,
             "cover_url": "/polaris/v1/games/0123456789abcdef0123456789abcdef/cover", "last_launched": 1718187600000,
             "platform": "linux", "runtime": "proton", "genres": ["Puzzle"],
             "platform_label": "Custom Linux", "runtime_label": "Proton Experimental",
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
    assert(portal.platformLabel == "Custom Linux" && portal.runtimeLabel == "Proton Experimental");
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
    const auto invalidDates = parseGamesPage(R"({"games":[
        {"id":"huge","name":"Huge","last_launched":1e100},{"id":"negative","name":"Negative","last_launched":-1},
        {"id":"wrong-type","name":"Wrong type","last_launched":"yesterday"},{"id":"seconds","name":"Seconds","last_launched":1700000000}]})");
    assert(invalidDates && invalidDates->games.size() == 4);
    assert(invalidDates->games[0].lastLaunched == 0 && invalidDates->games[1].lastLaunched == 0 && invalidDates->games[2].lastLaunched == 0);
    assert(invalidDates->games[3].lastLaunched == 1700000000LL);
}

void testGameTimeMetadata() {
    const auto parse = [](QJsonObject fields) {
        fields["id"] = "fixture"; fields["name"] = "Fixture";
        return parseGamesPage(QJsonDocument(QJsonObject{{"games", QJsonArray{fields}}}).toJson().toStdString())->games.front().gameTime;
    };
    const auto complete = parse({{"play_time", QJsonObject{{"seconds", 30000}, {"source", "steam"}}},
        {"beat_time", QJsonObject{{"main_seconds", 36000}, {"extras_seconds", 54000},
            {"completionist_seconds", 72000}, {"matched_name", "Fixture"}}}});
    assert(complete.playedSeconds == 30000 && complete.playSource == "steam");
    assert(complete.mainSeconds == 36000 && complete.extrasSeconds == 54000 && complete.completionistSeconds == 72000);
    assert(complete.matchedName == "Fixture");
    const auto absent = parse({});
    assert(!absent.playedSeconds && !absent.mainSeconds && !absent.extrasSeconds && !absent.completionistSeconds);
    const auto zero = parse({{"play_time", QJsonObject{{"seconds", 0}}}, {"beat_time", QJsonObject{{"main_seconds", 0}, {"extras_seconds", 1800}}}});
    assert(zero.playedSeconds == 0 && !zero.mainSeconds && zero.extrasSeconds == 1800);
    for (const auto& bad : QList<QJsonValue>{QJsonValue{}, "3600", true, -1, 0.5, 1e100, 9007199254740992.0, QJsonArray{1}}) {
        const auto invalid = parse({{"play_time", QJsonObject{{"seconds", bad}, {"source", "untrusted"}}},
            {"beat_time", QJsonObject{{"main_seconds", bad}, {"extras_seconds", 7200}, {"completionist_seconds", bad}}}});
        assert(!invalid.playedSeconds && invalid.playSource.empty() && !invalid.mainSeconds &&
            invalid.extrasSeconds == 7200 && !invalid.completionistSeconds);
    }
    assert(!parse({{"play_time", "invalid"}, {"beat_time", QJsonArray{1}}}).playedSeconds);
}

void testArtworkManifestBoundaries() {
    QJsonObject assets;
    for (const auto* kind : {"poster", "hero", "logo", "icon"})
        assets[kind] = QJsonObject{{"url", QString("/polaris/v1/games/game-7/artwork/%1?revision=one").arg(kind)}, {"cached", true}};
    QJsonObject manifest{{"version", 1}, {"revision", "one"}, {"assets", assets}};
    const auto parse = [&](const QJsonObject& value, const QString& id = "game-7") {
        const auto bytes = QJsonDocument(QJsonObject{{"games", QJsonArray{
            QJsonObject{{"id", id}, {"name", "Fixture"}, {"artwork", value}}}}}).toJson();
        const auto page = parseGamesPage(bytes.toStdString());
        return page && !page->games.empty() ? page->games.front().artwork : nova::deck::DeckArtworkManifest{};
    };
    const auto valid = parse(manifest);
    assert(valid.hero == "/polaris/v1/games/game-7/artwork/hero?revision=one");
    assert(!valid.poster.empty() && !valid.logo.empty() && !valid.icon.empty() && valid.key.size() == 64);
    assert(valid.key.find("polaris") == std::string::npos);
    auto transformed = manifest;
    transformed["override"] = QJsonObject{{"logo_transform",QJsonObject{{"scale",2.5},{"x",0.1},{"y",0.9}}}};
    const auto layout = parse(transformed);
    assert(layout.logoScale == 2.5 && layout.logoX == 0.1 && layout.logoY == 0.9);
    assert(layout.key == valid.key); // Layout changes never invalidate image bytes.
    transformed["override"] = QJsonObject{{"logo_transform",QJsonObject{{"scale","2.5"},{"x",-1},{"y",2}}}};
    const auto invalidLayout = parse(transformed);
    assert(invalidLayout.logoScale == 1 && invalidLayout.logoX == 0.5 && invalidLayout.logoY == 0.5);
    auto changed = manifest; changed["revision"] = "two";
    assert(parse(changed).key != valid.key);
    for (const QString& path : {"https://foreign.invalid/polaris/v1/games/game-7/artwork/hero",
            "//foreign.invalid/x", "/polaris/v1/games/other/artwork/hero", "/polaris/v1/games/game-7/artwork/logo",
            "/polaris/v1/games/game-7/artwork/../hero", "/polaris/v1/games/game-7/artwork/%2e%2e/hero",
            "/polaris/v1/games/game-7/artwork/hero#fragment", "/polaris/v1/games/game-7/artwork/hero?redirect=/launch"}) {
        auto invalidAssets = assets;
        invalidAssets["hero"] = QJsonObject{{"url", path}, {"cached", true}};
        auto invalid = manifest; invalid["assets"] = invalidAssets;
        assert(parse(invalid).hero.empty() && !parse(invalid).poster.empty());
    }
    for (const QJsonValue cached : {QJsonValue(false), QJsonValue("true"), QJsonValue()}) {
        auto invalidAssets = assets;
        invalidAssets["hero"] = QJsonObject{{"url", "/polaris/v1/games/game-7/artwork/hero"}, {"cached", cached}};
        auto invalid = manifest; invalid["assets"] = invalidAssets;
        assert(parse(invalid).hero.empty());
    }
    for (const QJsonValue version : {QJsonValue(2), QJsonValue("1"), QJsonValue(true)}) {
        auto invalid = manifest; invalid["version"] = version;
        assert(parse(invalid).poster.empty());
    }
    assert(parse(manifest, "../game-7").hero.empty());
    assert(parse(manifest, "space.room.game-7").poster.empty());
}

std::string readFixture(const char* name) {
    std::ifstream in(std::filesystem::path(NOVA_DECK_FIXTURE_SOURCE_DIR) / name);
    assert(in && "fixture must exist");
    std::ostringstream body;
    body << in.rdbuf();
    return body.str();
}

// The hand-written bodies above encode what we assumed Polaris serves. These
// two preserve recorded Polaris 1.4.4 response shapes, with synthetic host
// identifiers and usage metadata (fixtures/polaris_1_4_4_*.json), and pin the three
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
    assert(bigPicture.id == "11111111-2222-4333-8444-555555555555");
    assert(bigPicture.appId == 1000000001 && "served as the string \"1000000001\"");
    assert(bigPicture.name == "Steam Big Picture");
    assert(bigPicture.source == "manual");
    assert(bigPicture.steamAppid.empty());
    assert(bigPicture.installed && !bigPicture.hdrSupported);
    assert(bigPicture.platform.empty() && bigPicture.runtime.empty() && "not served for manual entries");
    assert(bigPicture.lastLaunched == 1700000000LL);
    assert(bigPicture.launchPreferredMode == "headless_stream");
    assert(bigPicture.launchRecommendedMode == "headless_stream");
    assert(bigPicture.launchAllowedModes.size() == 6);
    assert(!bigPicture.launchModeReason.empty());
    assert(!bigPicture.steamLaunchAvailable && bigPicture.steamLaunchAllowedModes.empty());
    assert(bigPicture.genres.empty());

    const auto& indy = page->games[1];
    assert(indy.appId == 1000000002);
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
    // A refusal answers at once and must not be mistaken for an HTTP timeout.
    // Neither result prevents the live route from trying pinned HTTPS.
    const auto refused = probeServerInfoHttpsPort("127.0.0.1", 1, std::chrono::milliseconds(2000));
    assert(!refused.httpsPort.has_value() && !refused.timedOut);
}

void testSplitsPathAndQueryForQUrl() {
    // Field bug from the first host probe: the games page went out as
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

void testLaunchModeAuthority() {
    const auto json = QJsonDocument::fromJson(R"({"version":1,
        "desired":{"stream_display_mode":"headless_dongle"},"effective":{"stream_display_mode":"desktop_display"},
        "capabilities":{"modes":[
            {"value":"headless","available":true},
            {"value":"desktop_display","available":true,"session_overridable":true},
            {"value":"headless_dongle","available":true,"session_overridable":true},
            {"value":"host_virtual_display","available":false,"session_overridable":true},
            {"value":"gamescope_stream","available":true,"session_overridable":false}]}})").object();
    const auto parse = [](QJsonObject object) { return parseLaunchModeCatalog(QJsonDocument(object).toJson().toStdString()); };
    const auto catalog = parse(json);
    assert(catalog && catalog->desired == "headless_dongle");
    assert(parse(QJsonObject{{"client_settings", json}}));
    DeckPolarisGame game;
    game.id = "game-7";
    game.launchAllowedModes = {"headless_stream", "headless_dongle", "host_virtual_display", "gamescope_stream"};
    auto policy = launchModePolicy(game, *catalog);
    assert(policy.known && policy.hostDefault == "headless_dongle" && policy.allowed == std::vector<std::string>{"headless_stream"});
    game.launchAllowedModes.clear();
    assert(launchModePolicy(game, *catalog).allowed.size() == 2);
    game.launchContractValid = false;
    assert(!launchModePolicy(game, *catalog).known);
    game.launchContractValid = true; game.id = "space.fixture.game";
    assert(!launchModePolicy(game, *catalog).known);
    for (const auto& version : QList<QJsonValue>{true, "1", 2, QJsonValue()}) {
        auto bad = json; bad["version"] = version; assert(!parse(bad));
    }
    for (const auto& field : {"desired", "effective", "capabilities"}) {
        auto bad = json; bad.remove(field); assert(!parse(bad));
    }
    for (int scenario = 0; scenario < 7; ++scenario) {
        auto bad = json;
        auto capabilities = bad["capabilities"].toObject();
        auto modes = capabilities["modes"].toArray();
        auto mode = modes[0].toObject();
        if (scenario == 0) mode["available"] = "true";
        if (scenario == 1) mode["session_overridable"] = 1;
        if (scenario == 2) mode["value"] = "unknown_mode";
        if (scenario == 3) mode.remove("available");
        modes[0] = mode;
        if (scenario == 4) modes.append(QJsonObject{{"value", "headless_stream"}, {"available", false}});
        if (scenario == 5) modes = QJsonArray{};
        if (scenario == 6) bad["desired"] = QJsonObject{{"stream_display_mode", "unknown_mode"}};
        capabilities["modes"] = modes; bad["capabilities"] = capabilities;
        assert(!parse(bad));
    }
    for (const auto& allowed : QList<QJsonValue>{"headless_stream", QJsonArray{true}, QJsonArray{"unknown"}, QJsonArray{"headless", "headless_stream"}}) {
        const auto body = QJsonDocument(QJsonObject{{"games", QJsonArray{QJsonObject{{"id", "game"}, {"name", "Fixture"},
            {"launch_mode", QJsonObject{{"allowed_modes", allowed}}}}}}, {"total", 1}}).toJson();
        const auto games = parseGamesPage(body.toStdString());
        assert(games && games->games.size() == 1 && !games->games.front().launchContractValid);
    }
}

int main(int argc, char* argv[]) {
    QCoreApplication app(argc, argv);
    testParsesCapabilities();
    testLaunchModeAuthority();
    testDisplayRecommendations();
    testParsesGamesPageFromHostShape();
    testGameTimeMetadata();
    testArtworkManifestBoundaries();
    testParsesBodiesPolarisActuallyServed();
    testParsesSessionStatus();
    testParsesServerInfoHttpsPort();
    testSplitsPathAndQueryForQUrl();
    testDescribeCoversEveryStatus();
    testInvalidIdentityFailsClosedWithoutTouchingTheNetwork();
    return 0;
}
