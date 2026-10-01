#include "polaris/deck_client_media.h"
#include "polaris/deck_polaris_client.h"
#include "runtime/deck_hud_host.h"
#include "stream/deck_media_counters.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QJsonDocument>
#include <QJsonObject>
#include <QThread>
#include <atomic>
#include <cstdlib>
#include <iostream>
using namespace nova::deck::polaris;
using namespace nova::deck::runtime;
namespace {
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
template<class F> void until(F predicate) {
    QElapsedTimer timer; timer.start();
    while (!predicate() && timer.elapsed() < 4000) QThread::msleep(1);
    require(predicate(), "media observer timed out");
}
DeckClientMediaSample sample() { const auto now = clientMediaMonotonicMs(); return {now, {10, 8, 2, 1, now}}; }
DeckHostTelemetry owned() {
    DeckHostTelemetry h; h.authorityValid = h.active = h.owned = true; h.role = "owner";
    h.gameId = 17; h.gameUuid = "game"; h.sessionToken = h.appSession = "token"; h.generation = 41;
    return h;
}
DeckPolarisResult<DeckHostTelemetry> success(const DeckHostTelemetry& h) { return {DeckPolarisRequestStatus::Ok, 200, {}, h}; }
void countersAndWire() {
    nova::deck::stream::DeckVideoFrameCounters c;
    c.receive(0, 1000); c.receive(0, 1200); c.receive(1, 1500); c.receive(4, 2000);
    auto v = c.counts();
    require(v.expected == 5 && v.received == 3 && v.lost == 2 && v.lastFrameAtMs == 2000, "duplicate/frame gap counters wrong");
    const auto generation = v.decoderGeneration;
    c.receive(1, 2500); v = c.counts();
    require(v.decoderGeneration == generation + 1 && v.expected == 1 && v.received == 1 && v.lost == 0, "sequence reset bridged old epoch");
    c.receive(UINT32_MAX, 3000); c.receive(0, 3500); v = c.counts();
    require(v.decoderGeneration == generation + 2 && v.expected == 1 && v.lost == 0, "sequence wrap fabricated loss");
    c.reset(); require(c.counts().received == 0 && c.counts().lastFrameAtMs == 0, "decoder setup retained counters");
    c.receive(5, 0); require(c.counts().received == 0, "invalid timestamp admitted");
    auto s = sample(); const DeckClientMediaScope scope{"token", 41};
    require(validClientMedia(s) && freshClientMedia(s, s.atMs), "valid sample refused");
    const auto body = clientMediaBody(s, scope);
    require(body && body->size() < 16 * 1024, "raw sample not bounded");
    const auto json = QJsonDocument::fromJson(*body).object(), raw = json.value("sample").toObject();
    require(json.size() == 3 && json.value("app_session_id") == "token" && json.value("session_generation") == 41 && raw.size() == 5 &&
        raw.value("frames_expected") == 10 && raw.value("frames_received") == 8 && raw.value("frames_lost") == 2, "wire scope/raw counters changed");
    require(!freshClientMedia(s, s.atMs - 1) && !freshClientMedia(s, s.atMs + 2501), "future/stale sample accepted");
    auto reset = s; ++reset.counts.decoderGeneration; reset.atMs += 5000;
    require(!clientMediaEpochReady(reset, s), "upload bridged decoder epoch at host coverage limit");
    ++reset.atMs; require(clientMediaEpochReady(reset, s), "host coverage-gap baseline was never admitted");
    require(clientMediaEpochReady(s, {}) && clientMediaEpochReady(s, s), "same epoch held unnecessarily");
    auto stopped = s; stopped.counts.lastFrameAtMs -= 2501; require(!freshClientMedia(stopped, s.atMs), "stopped video appeared fresh");
    for (int field = 0; field < 6; ++field) {
        auto bad = s;
        if (field == 0) bad.counts.received = 11;
        if (field == 1) bad.counts.lost = 1;
        if (field == 2) bad.counts.expected = UINT64_MAX;
        if (field == 3) bad.counts.decoderGeneration = 0;
        if (field == 4) bad.counts.lastFrameAtMs = s.atMs + 1;
        if (field == 5) bad.counts.received = 0;
        require(!validClientMedia(bad) && !clientMediaBody(bad, scope), "malformed counters serialized");
    }
    for (const auto& bad : {DeckClientMediaScope{"", 41}, DeckClientMediaScope{" token", 41}, DeckClientMediaScope{"token\n", 41}, DeckClientMediaScope{"token", 0}})
        require(!clientMediaBody(s, bad), "malformed scope serialized");
    for (const auto* jsonText : {R"({"features":{}})", R"({"features":{"live_media_telemetry_v1":"true"}})", R"({"features":{"live_media_telemetry_v1":1}})"}) {
        auto caps = parseCapabilities(jsonText); require(caps && !caps->liveMediaTelemetry, "unadvertised/ill-typed feature enabled upload");
    }
    auto caps = parseCapabilities(R"({"features":{"live_media_telemetry_v1":true}})"); require(caps && caps->liveMediaTelemetry, "typed feature missing");
}
void admission() {
    for (int condition = 0; condition < 9; ++condition) {
        std::atomic<int> reads{0}, uploads{0};
        auto h = owned();
        if (condition == 1) h.owned = false;
        if (condition == 2) h.authorityValid = false;
        if (condition == 3) h.generation.reset();
        if (condition == 4) h.appSession = "another-stream";
        if (condition == 5) h.generation = 0;
        if (condition == 6) h.ending = true;
        if (condition == 7) h.role = "guest";
        if (condition == 8) h.generation = 9007199254740992LL;
        DeckHudHostObserver observer([&]() -> std::optional<DeckHudHostTarget> {
            DeckHudHostTarget t; t.identityValid = [] { return true; };
            t.fetch = [&](const auto&) { ++reads; return success(h); };
            if (condition != 0) t.uploadMedia = [&](const auto&, const auto&, const auto&) { ++uploads; return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Ok, 200, {}, true}; };
            return t;
        }, {17, "game", "token"}, {20, 100, 100});
        until([&] { return reads >= 2; });
        require(!observer.submitClientMedia(sample()), "unadvertised/missing owned scope admitted sample");
        QThread::msleep(30); require(uploads == 0, "unadvertised/unauthorized upload dispatched");
    }
}
void onceAndSwap() {
    std::atomic<int> reads{0}, uploads{0}; std::atomic<bool> swap{false};
    DeckHudHostObserver observer([&]() -> std::optional<DeckHudHostTarget> {
        DeckHudHostTarget t; t.identityValid = [] { return true; };
        t.fetch = [&](const auto&) { ++reads; auto h = owned(); if (swap) h.generation = 42; return success(h); };
        t.uploadMedia = [&](const auto& s, const auto& scope, const auto& cancelled) {
            require(!cancelled() && scope.appSession == "token" && scope.generation == 41 && validClientMedia(s), "wrong scope dispatched");
            ++uploads; return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
        }; return t;
    }, {17, "game", "token"}, {20, 100, 100});
    until([&] { return observer.snapshot().value("hostFresh").toBool(); });
    auto s = sample(); require(observer.submitClientMedia(s), "fresh owned sample refused");
    require(!observer.submitClientMedia(s), "duplicate timestamp queued twice");
    until([&] { return uploads == 1; }); QThread::msleep(120);
    require(uploads == 1, "lost upload response replayed sample");
    auto reset = sample(); ++reset.counts.decoderGeneration;
    require(observer.submitClientMedia(reset), "new decoder sample failed admission"); QThread::msleep(30);
    require(uploads == 1, "new decoder upload bridged the host baseline");
    swap = true; const auto before = reads.load(); until([&] { return reads > before + 1; });
    require(!observer.submitClientMedia(sample()) && uploads == 1, "stream generation swap reused old renderer scope");
}
void staleOrRevokedRead() {
    for (int condition = 0; condition < 4; ++condition) {
        std::atomic<int> reads{0}, uploads{0}; std::atomic<bool> blocked{false}, release{false}, identity{true};
        DeckHudHostObserver observer([&]() -> std::optional<DeckHudHostTarget> {
            DeckHudHostTarget t; t.identityValid = [&] { return identity.load(); };
            t.fetch = [&](const auto& cancelled) {
                if (++reads > 1) { blocked = true; while (!release && !cancelled()) QThread::msleep(1); }
                auto h = owned(); if (condition == 1) h.owned = reads <= 1; return success(h);
            };
            t.uploadMedia = [&](const auto&, const auto&, const auto&) { ++uploads; return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Ok, 200, {}, true}; }; return t;
        }, {17, "game", "token"}, {1000, 100, 1000});
        until([&] { return observer.snapshot().value("hostFresh").toBool(); });
        require(observer.submitClientMedia(sample()), "fresh queue admission failed"); until([&] { return blocked.load(); });
        if (condition == 0) { QThread::msleep(2600); require(!observer.submitClientMedia(sample()), "stale host authority accepted new sample"); }
        if (condition == 2) identity = false;
        if (condition == 3) {
            for (int i = 0; i < 20; ++i) { QThread::msleep(2); require(observer.submitClientMedia(sample()), "fresh bounded queue refused sample"); }
        }
        release = true; QThread::msleep(50);
        if (condition == 3) require(uploads == 1 && reads <= 3, "telemetry burst created an unbounded upload/read queue");
        else require(uploads == 0, "stale/revoked blocked read dispatched telemetry");
    }
}
void unsupportedStopsUploads() {
    std::atomic<int> uploads{0};
    DeckHudHostObserver observer([&]() -> std::optional<DeckHudHostTarget> {
        DeckHudHostTarget t; t.identityValid = [] { return true; };
        t.fetch = [](const auto&) { return success(owned()); };
        t.uploadMedia = [&](const auto&, const auto&, const auto&) { ++uploads; return DeckPolarisResult<bool>{DeckPolarisRequestStatus::HttpError, 404, {}, {}}; };
        return t;
    }, {17, "game", "token"}, {20, 100, 100});
    until([&] { return observer.snapshot().value("hostFresh").toBool(); });
    require(observer.submitClientMedia(sample()), "initial advertised upload refused"); until([&] { return uploads == 1; });
    QThread::msleep(40);
    require(observer.snapshot().value("hostFresh").toBool() && !observer.submitClientMedia(sample()) && uploads == 1,
        "unsupported telemetry retried or failed optional host readings");
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv); countersAndWire(); admission(); onceAndSwap(); staleOrRevokedRead(); unsupportedStopsUploads();
    std::cout << "Video sequence epochs, raw schema, advertisement, owner/freshness fences and no replay passed\n";
}
