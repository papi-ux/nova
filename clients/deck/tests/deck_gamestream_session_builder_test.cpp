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
    "<sessionUrl0>rtsp://192.0.2.10:48010</sessionUrl0></root>";

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

    // serverinfo first, then the launch carrying this session's rikey.
    assert(host.seen.size() == 2 && host.seen[0] == "/serverinfo");
    assert(host.seen[1].rfind("/launch?appid=881448767", 0) == 0);
    assert(host.seen[1].find("rikeyid=7") != std::string::npos);
}

void testBuildFailures() {
    const auto keys = fixedKeys();

    // serverinfo transport failure.
    {
        FakeHost host;
        const auto r = buildStreamConnection(host.fetcher(), "192.0.2.10", sampleRequest(), keys);
        assert(!r.ok && r.error == "could not reach the host for serverinfo");
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
    }
    // launch not started (host busy).
    {
        FakeHost host;
        host.table["/serverinfo"] = DeckHttpResponse{true, 200, kServerInfo};
        host.table["/launch"] = DeckHttpResponse{true, 200, "<root status_code=\"200\"><gamesession>0</gamesession></root>"};
        const auto r = buildStreamConnection(host.fetcher(), "192.0.2.10", sampleRequest(), keys);
        assert(!r.ok && r.error == "the host did not start the session");
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

}  // namespace

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    testServerInfoParse();
    testBuildOk();
    testBuildFailures();
    return 0;
}
