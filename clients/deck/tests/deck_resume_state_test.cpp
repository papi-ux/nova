#include "runtime/deck_resume_state.h"
#include "polaris/deck_doctor.h"
#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <iostream>
#include <cstdlib>
using namespace nova::deck::runtime;
using namespace nova::deck::polaris;
namespace {
void require(bool ok, const char* why) { if (!ok) { std::cerr << why << '\n'; std::exit(1); } }
template<class F> void until(F f) {
    QElapsedTimer clock; clock.start();
    while (!f() && clock.elapsed() < 2000) { QCoreApplication::processEvents(); QThread::msleep(1); }
    require(f(), "observer did not reach expected state");
}
void process(int ms) { QElapsedTimer clock; clock.start(); while (clock.elapsed() < ms) { QCoreApplication::processEvents(); QThread::msleep(1); } }
QJsonObject paused() {
    return {{"state", "paused"}, {"game_id", 42}, {"game_uuid", "game-42"}, {"game", "Fixture game"},
        {"owned_by_client", true}, {"client_role", "none"}, {"session_token", "private-token"},
        {"streaming_active", false}, {"shutdown_requested", false},
        {"controls", QJsonObject{{"stop_allowed", true}, {"shutdown_in_progress", false}}}};
}
DeckPolarisSessionStatus parse(const QJsonObject& o) {
    const auto s = parseSessionStatus(QJsonDocument(o).toJson().toStdString());
    require(bool(s), "valid JSON not parsed"); return *s;
}
struct Fixture {
    std::atomic<int> mode{0}, reads{0}, posts{0};
    std::atomic<bool> valid{true}, hold{false}, entered{false};
    DeckResumeResolver resolver() {
        return [this]() -> std::optional<DeckResumeTarget> {
            nova::deck::PolarisGameFixture game; game.id = "game-42"; game.appId = 42;
            return DeckResumeTarget{[this] { return valid.load(); }, [this](const auto&) {
                require(QThread::currentThread() != QCoreApplication::instance()->thread(), "read ran on GUI thread");
                ++reads; entered = true;
                QElapsedTimer clock; clock.start();
                while (hold && clock.elapsed() < 1500) QThread::msleep(1);
                auto o = paused();
                if (mode == 1) { o["state"] = "streaming"; o["streaming_active"] = true; o["client_role"] = "owner"; }
                if (mode == 2 || mode == 3) { o["state"] = mode == 2 ? "idle" : "tearing_down"; o["game_id"] = 0; }
                if (mode == 4) return DeckPolarisResult<DeckPolarisSessionStatus>{DeckPolarisRequestStatus::Timeout};
                if (mode == 5) o["session_token"] = "replacement-token";
                if (mode == 6) { o["owned_by_client"] = false; o["client_role"] = "viewer"; }
                if (mode == 7) o["game_uuid"] = "another-game";
                return DeckPolarisResult<DeckPolarisSessionStatus>{DeckPolarisRequestStatus::Ok, 200, {}, parse(o)};
            }, [this](const auto& token, const auto&) {
                require(token == "private-token", "End targeted replacement session"); ++posts;
                return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout}; // no replay after uncertain response
            }, {game}};
        };
    }
};
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    require(parse(paused()).resumable(), "paused fixture refused");
    for (const char* field : {"state", "game_id", "game_uuid", "session_token", "owned_by_client", "client_role", "shutdown_requested", "controls", "streaming_active"}) {
        auto o = paused(); o.remove(field); require(!parse(o).resumable(), "missing authority accepted");
    }
    for (const auto state : {"idle", "ended", "expired", "tearing_down", "unknown"}) {
        auto o = paused(); o["state"] = state; require(!parse(o).resumable(), "terminal state retained resume");
    }
    auto o = paused(); o["game_id"] = 42.5; require(!parse(o).resumable(), "fractional app id accepted");
    o = paused(); o["streaming_active"] = true; require(!parse(o).resumable(), "contradictory paused state accepted");
    o = paused(); o["shutdown_requested"] = true; require(!parse(o).resumable(), "shutdown retained resume");
    Fixture f;
    int starts = 0;
    DeckResumeState state([&](const auto& host, const auto& game, const auto&, const auto& token) {
        require(host == "host" && game == "game-42" && token == "private-token", "resume scope changed"); ++starts; return true;
    }, nullptr, 30, 100);
    state.setContext("host", "pin-client-destination", f.resolver()); state.setActive(true);
    until([&] { return state.state().value("visible").toBool(); });
    require(!state.state().contains("sessionToken") && !state.state().values().contains("private-token"), "token leaked to QML");
    require(!state.startConfigured("other-host", "game-42", {}), "wrong host resume accepted");
    require(state.startConfigured("host", "game-42", {}) && starts == 1, "resume not sent");
    until([&] { return state.state().value("visible").toBool(); });
    f.mode = 1; const int read = f.reads; require(state.refresh(), "resumed poll refused"); until([&] { return f.reads > read; }); process(10);
    require(state.state().value("visible").toBool(), "resumed session disappeared");
    for (int mode : {2,3,4,6,7}) {
        f.mode = mode; until([&] { return !state.state().value("visible").toBool(); });
        f.mode = 0; until([&] { return state.state().value("visible").toBool(); });
    }
    f.mode = 5; require(state.endSession(), "End revalidation refused");
    process(80); require(f.posts == 0, "changed token was ended");
    f.mode = 0; state.setActive(false); state.setActive(true);
    until([&] { return state.state().value("visible").toBool(); });
    require(state.endSession(), "owned End refused"); process(100);
    require(f.posts == 1, "End was omitted or replayed after timeout");
    until([&] { return state.state().value("visible").toBool(); });
    f.entered = false; f.hold = true; until([&] { return f.entered.load(); });
    require(state.endSession(), "in-flight poll swallowed End");
    require(!state.endSession(), "queued End accepted duplicate click");
    f.hold = false; until([&] { return f.posts == 2; }); process(80);
    require(f.posts == 2, "queued End was replayed");
    until([&] { return state.state().value("visible").toBool(); });
    f.entered = false; f.hold = true; until([&] { return f.entered.load(); });
    until([&] { return !state.state().value("visible").toBool(); });
    state.setContext("other-host", "other-pin", {}); f.hold = false; process(50);
    require(!state.state().value("visible").toBool(), "late response restored changed host");
    state.setContext("host", "new-pin", f.resolver()); until([&] { return state.state().value("visible").toBool(); });
    f.valid = false; until([&] { return !state.state().value("visible").toBool(); });
    require(!state.startConfigured("host", "game-42", {}), "changed identity resumed");
    auto doctor = QJsonObject{{"version", 2}, {"result_id", "fixture"}, {"status", "needs_action"},
        {"severity", "warning"}, {"traffic_light", "amber"}, {"primary_issue", "frame_pacing"}, {"evidence", QJsonArray{}},
        {"summary", "Frames are arriving unevenly."}, {"recommendation", QJsonObject{{"body", "Cap the game's frame rate, then check again."}}}};
    auto view = doctorPresentation(doctor);
    require(view.value("title") == "Frames are arriving unevenly." && view.value("advice") == "Cap the game's frame rate, then check again.", "host Doctor wording discarded");
    doctor.remove("summary"); doctor.remove("recommendation");
    require(doctorPresentation(doctor).value("title") == "Frames are arriving unevenly", "legacy Doctor fallback is technical");
    std::cout << "paused/resumed/ended/expired/stale authority and host wording passed\n";
}
