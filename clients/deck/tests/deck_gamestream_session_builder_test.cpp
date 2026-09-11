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
    assert(!reply.transportOk && reply.status == 0 && reply.body.empty());
    const auto r = buildStreamConnection(fetcher, "192.0.2.10", sampleRequest(), fixedKeys());
    assert(!r.ok && r.error == "could not reach the host for serverinfo");
}

}  // namespace

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    testServerInfoParse();
    testBuildOk();
    testBuildFailures();
    testHostCancel();
    testFetcherOverPolarisClient();
    return 0;
}
