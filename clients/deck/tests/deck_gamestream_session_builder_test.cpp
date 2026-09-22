// Tests for assembling a connection descriptor from serverinfo plus a launch,
// through an injected fetcher. No network.
#include "stream/deck_gamestream_session_builder.h"

#include <QCoreApplication>

#include <array>
#include <cassert>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

using namespace nova::deck::stream;

namespace {

const std::string kServerInfo =
    "<?xml version=\"1.0\"?><root status_code=\"200\">"
    "<hostname>pc-papi</hostname><appversion>7.1.431.-1</appversion>"
    "<GfeVersion>3.23.0.74</GfeVersion><ServerCodecModeSupport>65793</ServerCodecModeSupport>"
    "<state>POLARIS_SERVER_FREE</state></root>";

const std::string kLaunchOk =
    "<root status_code=\"200\"><gamesession>1</gamesession>"
    "<sessionUrl0>rtsp://192.0.2.10:48010</sessionUrl0>"
    "<sessionToken>tok-abc</sessionToken></root>";

const std::string kCancelOk = "<root status_code=\"200\"><cancel>1</cancel></root>";
const std::string kCancelBusy =
    "<root status_code=\"409\" status_message=\"The active session changed or is already stopping\"><cancel>0</cancel></root>";

DeckStreamKeys fixedKeys() {
    std::array<std::uint8_t, 16> key{};
    for (std::size_t i = 0; i < key.size(); ++i) {
        key[i] = static_cast<std::uint8_t>(i + 1);
    }
    return buildStreamKeys(key, 7);
}

DeckLaunchRequest sampleRequest() {
    DeckLaunchRequest request;
    request.appId = 881448767;
    request.width = 1280;
    request.height = 800;
    request.fps = 60;
    return request;
}

// A fetcher that answers from a fixed table, records the targets it saw, and can
// force a transport failure for one target.
struct FakeHost {
    std::map<std::string, DeckHttpResponse> table;
    std::vector<std::string> seen;

    DeckHttpFetcher fetcher() {
        return [this](const std::string& target) -> DeckHttpResponse {
            seen.push_back(target);
            // Match /launch and /resume by prefix, everything else exactly.
            for (const auto& [key, value] : table) {
                if (target == key || (key.size() < target.size() && target.compare(0, key.size(), key) == 0)) {
                    return value;
                }
            }
            return DeckHttpResponse{false, 0, ""};
        };
    }
};

void testServerInfoParse() {
    const auto info = parseServerInfo(kServerInfo);
    assert(info.has_value());
    assert(info->appVersion == "7.1.431.-1");
    assert(info->gfeVersion == "3.23.0.74");
    assert(info->serverCodecModeSupport == 65793);

    // Missing appversion, an error root, and garbage all fail to parse.
    assert(!parseServerInfo("<root status_code=\"200\"><state>x</state></root>").has_value());
    assert(!parseServerInfo("<root status_code=\"401\"></root>").has_value());
    assert(!parseServerInfo("not xml").has_value());
    // An error root that still carries an appversion is rejected on its status.
    assert(!parseServerInfo("<root status_code=\"401\"><appversion>7.1</appversion></root>").has_value());

    // GfeVersion and codec support are optional; codec defaults to zero.
    const auto minimal = parseServerInfo("<root status_code=\"200\"><appversion>7.1</appversion></root>");
    assert(minimal.has_value() && minimal->gfeVersion.empty() && minimal->serverCodecModeSupport == 0);
}

void testBuildOk() {
    FakeHost host;
    host.table["/serverinfo"] = DeckHttpResponse{true, 200, kServerInfo};
    host.table["/launch"] = DeckHttpResponse{true, 200, kLaunchOk};

    const auto keys = fixedKeys();
    const auto result = buildStreamConnection(host.fetcher(), "192.0.2.10", sampleRequest(), keys);
    assert(result.ok);
    const auto& info = result.connectionInfo;
    assert(info.serverAddress == "192.0.2.10");
    assert(info.appVersion == "7.1.431.-1");
    assert(info.gfeVersion == "3.23.0.74");
    assert(info.rtspSessionUrl == "rtsp://192.0.2.10:48010");
    assert(info.serverCodecModeSupport == 65793);
    assert(info.keys.aesKey == keys.aesKey && info.keys.rikeyId == keys.rikeyId);
    // The host's session token is carried so the cancel can name the session.
    assert(info.hostSessionToken == "tok-abc");
    assert(!result.launchRefused && result.launchStatusCode == 200);

    // serverinfo first, then the launch carrying this session's rikey.
    assert(host.seen.size() == 2 && host.seen[0] == "/serverinfo");
    assert(host.seen[1].rfind("/launch?appid=881448767", 0) == 0);
    assert(host.seen[1].find("rikeyid=7") != std::string::npos);
}

void testUnsupportedCodecNeverLaunches() {
    for (const auto* value : {"256", "512", "262144", "broken", "-1", "2147483648"}) {
        FakeHost host;
        host.table["/serverinfo"] = DeckHttpResponse{true, 200,
            std::string("<root status_code=\"200\"><appversion>7.1</appversion><ServerCodecModeSupport>") + value +
            "</ServerCodecModeSupport></root>"};
        const auto result = buildStreamConnection(host.fetcher(), "192.0.2.10", sampleRequest(), fixedKeys());
        assert(!result.ok && !result.hostSessionStarted && host.seen == std::vector<std::string>{"/serverinfo"});
    }
}

void testHevcAdmission() {
    for (const auto* codec : {"h264", "hevc", "auto", "main10", "av1"}) for (int mask : {0, 1, 256, 257, 512, 768, 262144}) {
        FakeHost host;
        host.table["/serverinfo"] = DeckHttpResponse{true, 200,
            std::string("<root status_code=\"200\"><appversion>7.1</appversion><ServerCodecModeSupport>") + std::to_string(mask) +
            "</ServerCodecModeSupport></root>"};
        host.table["/launch"] = DeckHttpResponse{true, 200, kLaunchOk};
        auto request = sampleRequest(); request.videoCodec = codec;
        const auto result = buildStreamConnection(host.fetcher(), "192.0.2.10", request, fixedKeys());
        const bool expected = (request.videoCodec == "h264" && (mask == 0 || (mask & 1))) ||
            (request.videoCodec == "hevc" && (mask & 256));
        assert(result.ok == expected && result.hostSessionStarted == expected);
        assert(host.seen.size() == (expected ? 2 : 1));
    }
}

void testBuildFailures() {
    const auto keys = fixedKeys();

    // serverinfo transport failure: the host never answered a launch.
    {
        FakeHost host;
        const auto r = buildStreamConnection(host.fetcher(), "192.0.2.10", sampleRequest(), keys);
        assert(!r.ok && r.error == "could not reach the host for serverinfo");
        assert(!r.launchRefused);
    }
    // serverinfo non-200.
    {
        FakeHost host;
        host.table["/serverinfo"] = DeckHttpResponse{true, 401, "<root status_code=\"401\"/>"};
        const auto r = buildStreamConnection(host.fetcher(), "192.0.2.10", sampleRequest(), keys);
        assert(!r.ok && r.error == "host serverinfo returned an unexpected status");
    }
    // launch HTTP error (wrong endpoint / server error) is surfaced distinctly.
    {
        FakeHost host;
        host.table["/serverinfo"] = DeckHttpResponse{true, 200, kServerInfo};
        host.table["/launch"] = DeckHttpResponse{true, 500, "server error"};
        const auto r = buildStreamConnection(host.fetcher(), "192.0.2.10", sampleRequest(), keys);
        assert(!r.ok && r.error == "host launch returned an unexpected status");
        assert(r.launchRefused && r.launchStatusCode == 500);
    }
    // launch not started (host busy): a refusal, with the host's own status.
    {
        FakeHost host;
        host.table["/serverinfo"] = DeckHttpResponse{true, 200, kServerInfo};
        host.table["/launch"] = DeckHttpResponse{true, 200,
            "<root status_code=\"503\" status_message=\"The host is busy\"><gamesession>0</gamesession></root>"};
        const auto r = buildStreamConnection(host.fetcher(), "192.0.2.10", sampleRequest(), keys);
        assert(!r.ok && r.error == "the host did not start the session");
        assert(r.launchRefused && r.launchStatusCode == 503 && r.launchStatusMessage == "The host is busy");
    }
    // launch started but no RTSP url.
    {
        FakeHost host;
        host.table["/serverinfo"] = DeckHttpResponse{true, 200, kServerInfo};
        host.table["/launch"] = DeckHttpResponse{true, 200, "<root status_code=\"200\"><gamesession>1</gamesession></root>"};
        const auto r = buildStreamConnection(host.fetcher(), "192.0.2.10", sampleRequest(), keys);
        assert(!r.ok && r.error == "the host started the session without an RTSP url");
    }
}

std::string resumeInfo(std::string fields) {
    return "<root status_code=\"200\"><appversion>7.1</appversion>" + fields + "</root>";
}

void testOwnedResumeSelection() {
    const std::string identity = "<currentgame>881448767</currentgame><currentgameuuid>fixture-game</currentgameuuid>";
    const std::string authority = "<PairStatus>1</PairStatus><currentgameowned>1</currentgameowned>"
        "<currentgamesessiontoken>old-token</currentgamesessiontoken>";
    auto request = sampleRequest();
    request.appUuid = "fixture-game";
    request.streamMode = "headless_stream";
    for (const auto mode : {DeckSessionStartMode::PlayOrResume, DeckSessionStartMode::ResumeOnly}) {
        FakeHost host;
        host.table["/serverinfo"] = {true, 200, resumeInfo(identity + authority)};
        host.table["/resume"] = {true, 200, "<root status_code=\"200\"><resume>1</resume>"
            "<sessionUrl0>rtsp://192.0.2.10:48010</sessionUrl0><sessionToken>old-token</sessionToken></root>"};
        const auto result = buildStreamConnection(host.fetcher(), "192.0.2.10", request, fixedKeys(), {}, mode, "old-token");
        assert(result.ok && result.resumed && result.hostSessionStarted);
        assert(host.seen.size() == 2 && host.seen[1].starts_with("/resume?"));
        assert(host.seen[1].find("&sessiontoken=old-token") != std::string::npos);
        assert(host.seen[1].find("streamMode=") == std::string::npos);
        assert(result.connectionInfo.hostSessionToken == "old-token");
    }
    const std::vector<std::string> refused{
        "<currentgame>7</currentgame><currentgameuuid>fixture-game</currentgameuuid>" + authority,
        "<currentgame>881448767</currentgame><currentgameuuid>other-game</currentgameuuid>" + authority,
        identity + "<PairStatus>1</PairStatus><currentgameowned>0</currentgameowned><currentgamesessiontoken>old-token</currentgamesessiontoken>",
        identity + "<PairStatus>1</PairStatus><currentgameowned>1</currentgameowned>",
        identity + "<PairStatus>0</PairStatus><currentgameowned>1</currentgameowned><currentgamesessiontoken>old-token</currentgamesessiontoken>",
        identity + "<PairStatus>1</PairStatus>", // legacy has no exact ownership/token proof
        identity + "<PairStatus>1</PairStatus><currentgameowned>1</currentgameowned><currentgamesessiontoken>new-token</currentgamesessiontoken>",
        "<currentgame>0</currentgame>"
    };
    for (const auto& fields : refused) {
        FakeHost host;
        host.table["/serverinfo"] = {true, 200, resumeInfo(fields)};
        const auto result = buildStreamConnection(host.fetcher(), "192.0.2.10", request, fixedKeys(), {},
            DeckSessionStartMode::ResumeOnly, "old-token");
        assert(!result.ok && result.sessionSelectionRejected && !result.hostSessionStarted);
        assert(host.seen == std::vector<std::string>{"/serverinfo"});
        assert(result.error.find("old-token") == std::string::npos && result.error.find("new-token") == std::string::npos);
    }
    for (const auto* fields : {
        "<currentgame>1</currentgame><currentgame>2</currentgame>",
        "<currentgameowned>0</currentgameowned><currentgameowned>1</currentgameowned>",
        "<currentgamesessiontoken>a</currentgamesessiontoken><currentgamesessiontoken>b</currentgamesessiontoken>",
        "<currentgame>-1</currentgame>", "<currentgame>2147483648</currentgame>",
        "<currentgameowned>true</currentgameowned>", "<PairStatus>2</PairStatus>",
        "<currentgame><nested>17</nested></currentgame>"})
        assert(!parseServerInfo(resumeInfo(fields)));
    // Plain Play may launch only when no active session was advertised.
    FakeHost idle;
    idle.table["/serverinfo"] = {true, 200, resumeInfo("<currentgame>0</currentgame><PairStatus>1</PairStatus>")};
    idle.table["/launch"] = {true, 200, kLaunchOk};
    const auto launched = buildStreamConnection(idle.fetcher(), "192.0.2.10", request, fixedKeys(), {}, DeckSessionStartMode::PlayOrResume);
    assert(launched.ok && !launched.resumed && idle.seen[1].starts_with("/launch?"));
    for (const auto& response : {
        DeckHttpResponse{true, 200, "<root status_code=\"470\"><resume>1</resume></root>"},
        DeckHttpResponse{true, 200, "<root status_code=\"200\"><resume>1</resume><sessionToken>changed</sessionToken><sessionUrl0>rtsp://192.0.2.10:48010</sessionUrl0></root>"},
        DeckHttpResponse{true, 200, "<root status_code=\"200\"><resume>1</resume><sessionToken>old-token</sessionToken></root>"},
        DeckHttpResponse{false, 0, ""}}) {
        FakeHost host;
        host.table["/serverinfo"] = {true, 200, resumeInfo(identity + authority)};
        host.table["/resume"] = response;
        const auto result = buildStreamConnection(host.fetcher(), "192.0.2.10", request, fixedKeys(), {}, DeckSessionStartMode::PlayOrResume);
        assert(!result.ok && result.resumed && host.seen.size() == 2 && host.seen[1].starts_with("/resume?"));
    }
    bool cancelled = false;
    FakeHost host;
    host.table["/serverinfo"] = {true, 200, resumeInfo(identity + authority)};
    const auto fetch = host.fetcher();
    const auto stopped = buildStreamConnection([&](const auto& path) {
        auto reply = fetch(path); cancelled = true; return reply;
    }, "192.0.2.10", request, fixedKeys(), [&] { return cancelled; }, DeckSessionStartMode::PlayOrResume);
    assert(!stopped.ok && host.seen.size() == 1);
}

void testHostCancel() {
    // The cancel names the session the launch returned and is confirmed by the host.
    {
        FakeHost host;
        host.table["/cancel"] = DeckHttpResponse{true, 200, kCancelOk};
        const auto outcome = requestHostSessionCancel(host.fetcher(), "tok-abc");
        assert(outcome.requested && outcome.transportOk && outcome.cancelled);
        assert(outcome.httpStatus == 200 && outcome.hostStatusCode == 200);
        assert(host.seen.size() == 1 && host.seen[0] == "/cancel?sessiontoken=tok-abc");
        assert(outcome.summary.find("confirmed") != std::string::npos);
    }
    // Without a token the request is bare.
    {
        FakeHost host;
        host.table["/cancel"] = DeckHttpResponse{true, 200, kCancelOk};
        const auto outcome = requestHostSessionCancel(host.fetcher(), "");
        assert(outcome.cancelled && host.seen.size() == 1 && host.seen[0] == "/cancel");
    }
    // A host still counting the session (409) is retried and then confirms.
    {
        int calls = 0;
        const DeckHttpFetcher fetcher = [&calls](const std::string& target) {
            ++calls;
            assert(target.rfind("/cancel", 0) == 0);
            return calls == 1 ? DeckHttpResponse{true, 200, kCancelBusy} : DeckHttpResponse{true, 200, kCancelOk};
        };
        const auto outcome = requestHostSessionCancel(fetcher, "tok-abc");
        assert(outcome.cancelled && calls == 2);
        assert(outcome.summary.find("attempt 2") != std::string::npos);
    }
    // A refusal on other grounds is reported once, with the host's message.
    {
        FakeHost host;
        host.table["/cancel"] = DeckHttpResponse{true, 200,
            "<root status_code=\"470\" status_message=\"The current session belongs to another client\"><cancel>0</cancel></root>"};
        const auto outcome = requestHostSessionCancel(host.fetcher(), "tok-abc");
        assert(outcome.requested && !outcome.cancelled && outcome.hostStatusCode == 470);
        assert(host.seen.size() == 1);
        assert(outcome.summary.find("belongs to another client") != std::string::npos);
    }
    // Transport failure and a missing fetcher are reported, never thrown.
    {
        FakeHost host;
        const auto outcome = requestHostSessionCancel(host.fetcher(), "tok-abc");
        assert(outcome.requested && !outcome.transportOk && !outcome.cancelled);
        const auto none = requestHostSessionCancel(DeckHttpFetcher{}, "tok-abc");
        assert(!none.requested && !none.cancelled);
        assert(none.summary.find("no host fetcher") != std::string::npos);
    }
}

void testFetcherOverPolarisClient() {
    // An unusable identity never reaches the network and is a transport failure,
    // not an answer, so the builder reports the host as unreachable.
    const nova::deck::polaris::DeckPolarisClient client(
        nova::deck::polaris::DeckPolarisEndpoint{.address = "192.0.2.10", .httpsPort = 47984},
        nova::deck::polaris::DeckPolarisTlsIdentity{});
    const DeckHttpFetcher fetcher = fetcherOverPolarisClient(client);
    const auto reply = fetcher("/serverinfo");
    assert(!reply.transportOk && reply.status == 0 && reply.body.empty() && !reply.retryableTransportFailure);
    const auto r = buildStreamConnection(fetcher, "192.0.2.10", sampleRequest(), fixedKeys());
    assert(!r.ok && r.error == "could not reach the host for serverinfo" && !r.retryableTransportFailure);
    const auto offline = buildStreamConnection([](const auto&) { return DeckHttpResponse{false, 0, {}, true}; },
        "192.0.2.10", sampleRequest(), fixedKeys(), {}, DeckSessionStartMode::ResumeOnly, "expected");
    assert(!offline.ok && offline.retryableTransportFailure && !offline.hostSessionStarted);
}

}  // namespace

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    testServerInfoParse();
    testBuildOk();
    testUnsupportedCodecNeverLaunches();
    testHevcAdmission();
    testBuildFailures();
    testHostCancel();
    testOwnedResumeSelection();
    testFetcherOverPolarisClient();
    return 0;
}
