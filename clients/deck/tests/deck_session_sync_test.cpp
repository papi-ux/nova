#include "runtime/deck_hud_host.h"
#include "deck_host_settings_fixture.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QJsonDocument>
#include <atomic>
#include <cstdlib>
#include <iostream>

using namespace nova::deck::runtime;
using namespace nova::deck::polaris;
namespace {
int scenario = 0;
void require(bool value, const char* message) {
    if (!value) { std::cerr << "Sync scenario " << scenario << ": " << message << '\n'; std::exit(1); }
}
template<class F> void until(F predicate) {
    QElapsedTimer timer; timer.start();
    while (!predicate() && timer.elapsed() < 2500) QThread::msleep(1);
    require(predicate(), "observer timed out");
}
template<class T> DeckPolarisResult<T> ok(T value) { return {DeckPolarisRequestStatus::Ok, 200, {}, value}; }
DeckHostTelemetry sample() {
    DeckHostTelemetry s;
    s.active = s.owned = s.authorityValid = s.hostTuningAllowed = s.livePresent = true;
    s.role = "owner"; s.gameUuid = "private-game"; s.sessionToken = "private-token"; s.appSession = "private-session";
    s.gameId = 17; s.generation = 41;
    s.live = DeckLiveTuningTelemetry{true, true, "stable", QString(64, 'a'), "private-instance", "private-session", 1, 41, 20000, 20000, 20000};
    return s;
}
void outcomesAndPreflight() {
    // Successful/delayed/absent encoder proof, clear (including unsupported
    // encoders), lost receipt, changed profile, and each owned-session boundary.
    for (scenario = 0; scenario < 24; ++scenario) {
        const bool clear = scenario == 3 || scenario == 4;
        auto profile = *parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
        const auto reviewed = profile.profileReview();
        std::atomic<int> reads{0}, profileReads{0}, writes{0}, otherWrites{0};
        std::atomic<bool> armed{false}, committed{false}, ack{scenario != 1}, identity{true};
        auto s = sample();
        if (scenario == 4 || scenario == 5) s.live->supported = false;
        DeckHudHostObserver observer([&]() -> std::optional<DeckHudHostTarget> {
            DeckHudHostTarget t;
            t.identityValid = [&] { return identity.load(); };
            t.fetch = [&](const auto&) {
                auto next = s; next.live->sequence = ++reads;
                if (armed && scenario >= 12 && scenario <= 18) {
                    if (scenario == 12) next.live->instance = "replacement";
                    if (scenario == 13) { next.generation = 42; next.live->generation = 42; }
                    if (scenario == 14) { next.appSession = "replacement"; next.live->appSession = "replacement"; }
                    if (scenario == 15) next.sessionToken = "replacement";
                    if (scenario == 16) next.hostTuningAllowed = false;
                    if (scenario == 17) next.live->revision = QString(64, 'b');
                    if (scenario == 18) next.live->qualityLimit = 25000;
                }
                if (profileReads > 0 && scenario == 21) next.hostTuningAllowed = false;
                if (committed && !clear) {
                    next.live->enabled = false; next.live->revision = QString(64, 'b');
                    next.live->qualityLimit = next.live->requested = 15000;
                    if (ack && scenario != 2) next.live->applied = 15000;
                    if (scenario == 10) return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Unauthorized, 403};
                    if (scenario == 11) next.live->instance = "replacement";
                }
                return ok(next);
            };
            t.setEnabled = [&](bool, const auto&, const auto&) { ++otherWrites; return ok(true); };
            t.setBitrate = [&](int, const auto&, const auto&) { ++otherWrites; return ok(true); };
            t.readProfile = [&](const auto&) {
                ++profileReads;
                auto next = profile;
                if (scenario == 19) next.revision = "replacement";
                if (scenario == 20) next.bitrateOverride = false;
                if (scenario == 22) identity = false;
                if (scenario == 23) return DeckPolarisResult<DeckHostSettings>{DeckPolarisRequestStatus::Unauthorized, 403};
                if (committed && scenario == 8) next.desiredBitrate = 25000;
                if (committed && scenario == 9) return DeckPolarisResult<DeckHostSettings>{DeckPolarisRequestStatus::MalformedBody};
                return ok(next);
            };
            t.writeProfile = [&](const QString& display, int bitrate, bool clearing, const auto& observed, const auto& stop) {
                require(!stop() && observed.sequence >= 3 && observed.appSession == "private-session" && observed.generation == 41,
                    "write did not carry the freshly rechecked backend scope");
                require(clearing == clear && display == (clear ? "" : "1920x1080x60") && bitrate == (clear ? 0 : 15000), "profile request changed");
                ++writes;
                if (scenario == 7) return DeckPolarisResult<DeckHostSettings>{DeckPolarisRequestStatus::HttpError, 409};
                profile.desiredDisplay = display; profile.desiredBitrate = bitrate; profile.revision = "2";
                committed = true;
                if (scenario == 6) return DeckPolarisResult<DeckHostSettings>{DeckPolarisRequestStatus::Timeout};
                return ok(profile);
            };
            return t;
        }, {17, "private-game", "private-token"}, {100, 1000, 200, 150});
        until([&] { return observer.snapshot().value("canSyncProfile").toBool(); });
        require(!observer.setSyncProfile("", 0, false, reviewed) && !observer.setSyncProfile("1920x1080x60", 15000, true, reviewed)
            && !observer.setSyncProfile("1920x1080x60", 15000, false, {}), "invalid request accepted");
        if (scenario == 5) {
            require(!observer.setSyncProfile("1920x1080x60", 15000, false, reviewed) && writes == 0, "unsupported encoder accepted send");
            continue;
        }
        armed = true;
        require(observer.setSyncProfile(clear ? "" : "1920x1080x60", clear ? 0 : 15000, clear, reviewed), "valid review not queued");
        require(!observer.setSyncProfile("1920x1080x60", 15000, false, reviewed) && !observer.setFixedBitrate(30000)
            && !observer.setLiveTuningEnabled(false), "competing mutation was queued");
        if (scenario == 1) {
            until([&] { return observer.snapshot().value("syncPhase") == "confirming"; });
            require(observer.snapshot().value("syncBusy").toBool() && observer.snapshot().value("appliedBitrateKbps") == 20000,
                "saved profile masqueraded as an encoder acknowledgement");
            ack = true;
        }
        until([&] { return !observer.snapshot().value("syncBusy").toBool(); });
        const auto actual = observer.snapshot(); const auto phase = actual.value("syncPhase").toString();
        require(writes == (scenario >= 12 ? 0 : 1) && otherWrites == 0, "unexpected write count or live endpoint used for clear");
        if (scenario <= 1) require(phase == "confirmed" && actual.value("appliedBitrateKbps") == 15000, "confirmed encoder change missing");
        else if (clear) require(phase == "saved" && actual.value("tuningEnabled").toBool(), "clear changed live settings or claimed immediate application");
        else if (scenario < 12) require(phase == "unconfirmed", "uncertain outcome claimed success");
        else require(phase == "changed" || phase == "unconfirmed", "stale review did not require new intent");
        if (scenario == 2) require(actual.value("syncCopy").toString().contains("Profile saved") && actual.value("appliedBitrateKbps") == 20000,
            "durable profile and failed encoder confirmation conflated");
        if (scenario == 6) require(actual.value("syncCopy").toString().contains("response wasn't confirmed") && actual.value("appliedBitrateKbps") == 15000,
            "lost receipt hid fresh truth or claimed success");
        const auto json = QJsonDocument::fromVariant(actual).toJson();
        require(!json.contains("private-") && !json.contains(QString(64, 'a').toUtf8()), "session scope leaked into UI");
        QThread::msleep(110); require(writes == (scenario >= 12 ? 0 : 1), "profile write retried automatically");
    }
}
void cancellation() {
    for (scenario = 24; scenario < 28; ++scenario) {
        auto profile = *parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
        std::atomic<int> reads{0}, writes{0};
        std::atomic<bool> blocked{false}, cancelled{false}, identity{true}, ended{false};
        auto observer = std::make_unique<DeckHudHostObserver>([&]() -> std::optional<DeckHudHostTarget> {
            DeckHudHostTarget t;
            t.identityValid = [&] { return identity.load(); };
            t.fetch = [&](const auto& stop) {
                auto next = sample(); next.live->sequence = ++reads;
                if (scenario == 27 && reads > 1) { blocked = true; while (!stop()) QThread::msleep(1); cancelled = true; }
                return ok(next);
            };
            t.setEnabled = [](bool, const auto&, const auto&) { return ok(true); };
            t.setBitrate = [](int, const auto&, const auto&) { return ok(true); };
            t.readProfile = [&](const auto&) { return ok(profile); };
            t.writeProfile = [&](const auto&, int, bool, const auto&, const auto& stop) {
                ++writes; blocked = true;
                while (!stop()) QThread::msleep(1);
                cancelled = true; return DeckPolarisResult<DeckHostSettings>{DeckPolarisRequestStatus::Timeout};
            };
            return t;
        }, DeckHudHostContext{17, "private-game", "private-token"}, DeckHudHostTiming{20, 30, 40}, [&] { return ended.load(); });
        until([&] { return observer->snapshot().value("canSyncProfile").toBool(); });
        if (scenario == 27) {
            until([&] { return blocked.load(); });
            until([&] { return !observer->snapshot().value("hostFresh").toBool(); });
            require(!observer->setSyncProfile("1920x1080x60",15000,false,profile.profileReview()) && writes == 0, "stale observation accepted");
        } else {
            require(observer->setSyncProfile("1920x1080x60",15000,false,profile.profileReview()), "active request rejected");
            until([&] { return blocked.load(); });
            if (scenario == 25) identity = false;
            if (scenario == 26) ended = true;
            if (scenario != 24) {
                until([&] { return !observer->snapshot().value("syncBusy").toBool(); });
                require(observer->snapshot().value("syncPhase") == "unconfirmed" && !observer->snapshot().value("canSyncProfile").toBool(), "revocation retained write authority");
            }
        }
        observer.reset(); require(cancelled && writes == (scenario == 27 ? 0 : 1), "pending I/O was not cancelled exactly once");
    }
}
void eventInvalidation() {
    for (scenario = 28; scenario < 31; ++scenario) {
        auto profile = *parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
        std::atomic<int> reads{0}, writes{0};
        std::atomic<bool> blocked{false}, committed{false}, fire{false}, refreshed{false}, ack{false};
        DeckHudHostObserver observer([&]() -> std::optional<DeckHudHostTarget> {
            DeckHudHostTarget t;
            t.identityValid = [] { return true; };
            t.fetch = [&](const auto&) {
                auto next = sample(); next.live->sequence = ++reads; next.eventsHttpsPort = 47990;
                if (committed) {
                    next.live->enabled = false; next.live->qualityLimit = next.live->requested = 15000;
                    if (ack) next.live->applied = 15000;
                }
                return ok(next);
            };
            t.setEnabled = [](bool, const auto&, const auto&) { return ok(true); };
            t.setBitrate = [](int, const auto&, const auto&) { return ok(true); };
            t.events = [&](int, const auto& refresh, const auto& stop) {
                while (!stop()) {
                    if (fire.exchange(false)) { refresh(); refreshed = true; }
                    QThread::msleep(1);
                }
                return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout};
            };
            t.readProfile = [&](const auto& stop) {
                if (scenario == 28) { blocked = true; while (!stop()) QThread::msleep(1); }
                return ok(profile);
            };
            t.writeProfile = [&](const QString& display, int bitrate, bool, const auto&, const auto& stop) {
                ++writes;
                if (scenario == 29) { blocked = true; while (!stop()) QThread::msleep(1); return DeckPolarisResult<DeckHostSettings>{DeckPolarisRequestStatus::Timeout}; }
                profile.desiredDisplay = display; profile.desiredBitrate = bitrate; committed = true;
                return ok(profile);
            };
            return t;
        }, {17,"private-game","private-token"}, {30,500,100,1000,20});
        until([&] { return observer.snapshot().value("canSyncProfile").toBool(); });
        require(observer.setSyncProfile("1920x1080x60",15000,false,profile.profileReview()), "event fixture request refused");
        if (scenario == 30) until([&] { return observer.snapshot().value("syncPhase") == "confirming"; });
        else until([&] { return blocked.load(); });
        fire = true; until([&] { return refreshed.load(); });
        if (scenario == 30) {
            require(observer.snapshot().value("syncPhase") != "confirmed", "event alone acknowledged the encoder");
            ack = true;
        }
        until([&] { return !observer.snapshot().value("syncBusy").toBool(); });
        require(writes == (scenario == 28 ? 0 : 1), "event invalidation admitted or replayed profile write");
        require(observer.snapshot().value("syncPhase") == (scenario == 28 ? "changed" : scenario == 29 ? "unconfirmed" : "confirmed"),
            "event hint was confused with profile or encoder evidence");
    }
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv); outcomesAndPreflight(); cancellation(); eventInvalidation();
    std::cout << "Session Sync: 31 profile/live confirmation, authority, readback, cancellation and event scenarios passed\n";
}
