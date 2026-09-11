// Tests for the GameStream launch protocol: key derivation, the launch/resume
// request URL, and parsing the host's answer. All pure; no network.
#include "stream/deck_gamestream_launch.h"

#include <QCoreApplication>

#include <array>
#include <cassert>
#include <cstdint>
#include <string>

using namespace nova::deck::stream;

namespace {

void testKeyDerivation() {
    std::array<std::uint8_t, 16> key{};
    for (std::size_t i = 0; i < key.size(); ++i) {
        key[i] = static_cast<std::uint8_t>(i);
    }
    const auto keys = buildStreamKeys(key, 0x01020304);
    assert(keys.aesKey == key);
    assert(keys.rikeyId == 0x01020304);
    // IV: id big-endian in the first four bytes, zero after.
    assert(keys.aesIv[0] == 0x01 && keys.aesIv[1] == 0x02 && keys.aesIv[2] == 0x03 && keys.aesIv[3] == 0x04);
    for (std::size_t i = 4; i < keys.aesIv.size(); ++i) {
        assert(keys.aesIv[i] == 0x00);
    }

    // A negative id is two's-complement big-endian.
    const auto negative = buildStreamKeys(key, -1);
    assert(negative.aesIv[0] == 0xFF && negative.aesIv[1] == 0xFF && negative.aesIv[2] == 0xFF && negative.aesIv[3] == 0xFF);
    assert(negative.aesIv[4] == 0x00);

    assert(toHexLower(key) == "000102030405060708090a0b0c0d0e0f");
}

void testGeneratedKeys() {
    const auto a = generateStreamKeys();
    const auto b = generateStreamKeys();
    // Distinct across calls, and the IV always agrees with its own id.
    assert(a.aesKey != b.aesKey || a.rikeyId != b.rikeyId);
    assert(buildStreamKeys(a.aesKey, a.rikeyId).aesIv == a.aesIv);
    bool anyNonZero = false;
    for (const auto byte : a.aesKey) {
        anyNonZero = anyNonZero || byte != 0;
    }
    assert(anyNonZero && "a generated key is not all zero");
}

void testLaunchTarget() {
    std::array<std::uint8_t, 16> key{};
    for (std::size_t i = 0; i < key.size(); ++i) {
        key[i] = static_cast<std::uint8_t>(i);
    }
    const auto keys = buildStreamKeys(key, 16909060);  // 0x01020304

    DeckLaunchRequest request;
    request.appId = 42;
    request.width = 1280;
    request.height = 800;
    request.fps = 60;
    request.sops = true;
    request.playLocalAudio = false;
    request.surroundAudioInfo = 131075;  // stereo: (2 << 16) | 0x3
    request.gamepadMask = 0;
    request.persistGamepads = false;

    const std::string expected =
        "/launch?appid=42&mode=1280x800x60&additionalStates=1&sops=1"
        "&rikey=000102030405060708090a0b0c0d0e0f&rikeyid=16909060"
        "&localAudioPlayMode=0&surroundAudioInfo=131075"
        "&remoteControllersBitmap=0&gcmap=0&gcpersist=0";
    assert(buildLaunchTarget(request, keys) == expected);

    // A host extra query is appended verbatim after the built parameters.
    DeckLaunchRequest withExtra = request;
    withExtra.extraQuery = "&corever=5&somefeature=1";
    const std::string built = buildLaunchTarget(withExtra, keys);
    assert(built == expected + "&corever=5&somefeature=1");

    // With an app uuid and resume verb.
    request.appUuid = "e720-6600";
    request.resume = true;
    request.sops = false;
    const std::string expectedResume =
        "/resume?appid=42&appuuid=e720-6600&mode=1280x800x60&additionalStates=1&sops=0"
        "&rikey=000102030405060708090a0b0c0d0e0f&rikeyid=16909060"
        "&localAudioPlayMode=0&surroundAudioInfo=131075"
        "&remoteControllersBitmap=0&gcmap=0&gcpersist=0";
    assert(buildLaunchTarget(request, keys) == expectedResume);

    // Caller-supplied values are percent-encoded so they stay one parameter:
    // an app uuid with a space, an ampersand and an equals sign, and an extra
    // query whose values carry spaces. The extra query's own separators survive.
    DeckLaunchRequest escaped = request;
    escaped.resume = false;
    escaped.appUuid = "a b&c=d";
    escaped.extraQuery = "&x=1 2&flag&y=%";
    const std::string escapedTarget = buildLaunchTarget(escaped, keys);
    assert(escapedTarget.find("&appuuid=a%20b%26c%3Dd&") != std::string::npos);
    assert(escapedTarget.find("&x=1%202&flag&y=%25") != std::string::npos);
    assert(escapedTarget.find("a b") == std::string::npos);
}

void testCancelProtocol() {
    assert(buildCancelTarget("") == "/cancel");
    assert(buildCancelTarget("tok-abc") == "/cancel?sessiontoken=tok-abc");
    assert(buildCancelTarget("tok/1 2") == "/cancel?sessiontoken=tok%2F1%202");

    const auto ok = parseCancelResponse("<root status_code=\"200\"><cancel>1</cancel></root>");
    assert(ok.cancelled && ok.statusCode == 200 && ok.statusMessage.empty());

    const auto busy = parseCancelResponse(
        "<root status_code=\"409\" status_message=\"The active session changed or is already stopping\"><cancel>0</cancel></root>");
    assert(!busy.cancelled && busy.statusCode == 409);
    assert(busy.statusMessage == "The active session changed or is already stopping");

    assert(!parseCancelResponse("<root status_code=\"200\"></root>").cancelled);
    assert(!parseCancelResponse("not xml <<<").cancelled);
    assert(!parseCancelResponse("").cancelled);
}

void testParseResponses() {
    const std::string launchOk =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        "<root status_code=\"200\"><gamesession>1</gamesession>"
        "<sessionUrl0>rtsp://192.0.2.10:48010</sessionUrl0>"
        "<sessionToken>tok-abc</sessionToken></root>";
    auto ok = parseLaunchResponse(false, launchOk);
    assert(ok.started && ok.statusCode == 200);
    assert(ok.rtspSessionUrl == "rtsp://192.0.2.10:48010");
    assert(ok.sessionToken == "tok-abc");

    const std::string launchBusy =
        "<root status_code=\"200\"><gamesession>0</gamesession></root>";
    auto busy = parseLaunchResponse(false, launchBusy);
    assert(!busy.started && busy.statusCode == 200 && busy.statusMessage.empty());

    // A refusal carries the host's status message alongside its code.
    const std::string launchRefused =
        "<root status_code=\"503\" status_message=\"The host is busy\"><gamesession>0</gamesession></root>";
    auto refused = parseLaunchResponse(false, launchRefused);
    assert(!refused.started && refused.statusCode == 503 && refused.statusMessage == "The host is busy");

    const std::string resumeOk =
        "<root status_code=\"200\"><resume>1</resume>"
        "<sessionUrl0>rtsp://192.0.2.10:48010</sessionUrl0></root>";
    auto resumed = parseLaunchResponse(true, resumeOk);
    assert(resumed.started && resumed.rtspSessionUrl == "rtsp://192.0.2.10:48010");
    // The same body judged as a launch has no gamesession element, so not started.
    assert(!parseLaunchResponse(false, resumeOk).started);

    const std::string denied = "<root status_code=\"401\"></root>";
    auto d = parseLaunchResponse(false, denied);
    assert(!d.started && d.statusCode == 401);

    assert(!parseLaunchResponse(false, "not xml at all <<<").started);
    assert(!parseLaunchResponse(false, "").started);
}

}  // namespace

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    testKeyDerivation();
    testGeneratedKeys();
    testLaunchTarget();
    testCancelProtocol();
    testParseResponses();
    return 0;
}
