#include "runtime/deck_host_settings.h"
#include "deck_host_settings_fixture.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QTemporaryDir>
#include <atomic>
#include <cstdlib>
#include <iostream>

using namespace nova::deck::runtime;
using namespace nova::deck::polaris;
namespace {
void require(bool value, const char* message) { if (!value) { std::cerr << message << '\n'; std::abort(); } }
template<class F> void until(F condition, int timeout = 9000) {
    QElapsedTimer time; time.start();
    while (!condition() && time.elapsed() < timeout) { QCoreApplication::processEvents(); QThread::msleep(2); }
    require(condition(), "background sync timed out");
}
void drain(int ms = 400) { QElapsedTimer time; time.start(); until([&] { return time.elapsed() >= ms; }, ms + 1000); }
template<class T> DeckPolarisResult<T> ok(T value) { return {DeckPolarisRequestStatus::Ok, 200, {}, std::move(value)}; }
struct Fixture {
    QTemporaryDir directory;
    DeckPlaySettings preferences{directory.filePath("preferences.ini")};
    DeckHostSettings current = *parseHostSettings(host_settings_fixture::json(host_settings_fixture::settings()));
    std::atomic<int> reads{0}, writes{0};
    std::atomic_bool valid{true}, idle{true}, hold{false}, entered{false}, lost{false};
    QStringList writtenHosts;
    DeckHostSettingsController controller;
    Fixture() {
        current.desiredDisplay.clear(); current.effectiveDisplay.clear();
        current.desiredBitrate = current.effectiveBitrate = 0;
    }
    DeckHostSettingsResolver resolver(QString id = "pc") {
        return [this, id]() -> std::optional<DeckHostSettingsTarget> {
            DeckHostSettingsTarget target;
            target.identityValid = [this] { return valid.load(); };
            target.capabilities = [] { DeckPolarisCapabilities caps; caps.clientSettings = true; return ok(caps); };
            target.read = [this](const auto& cancelled) {
                ++reads; entered = true;
                while (hold && !cancelled()) QThread::msleep(2);
                return ok(current);
            };
            target.idle = [this](const auto&) { return ok(idle.load()); };
            target.writeMode = [this](const auto&, const auto&) { require(false, "background sync changed PC-wide topology"); return ok(current); };
            target.writeResumeTimeout = [this](int, const auto&) { require(false, "background sync changed PC-wide timeout"); return ok(current); };
            target.writeProfile = [this, id](const QString& display, int bitrate, bool clear, const auto& cancelled) -> DeckPolarisResult<DeckHostSettings> {
                require(!clear && !cancelled() && valid && idle, "background write bypassed admission");
                require(preferences.keepInStep(id) == "paused", "automatic write had no durable interrupted state");
                ++writes; writtenHosts.append(id);
                current.desiredDisplay = current.effectiveDisplay = display;
                current.desiredBitrate = current.effectiveBitrate = bitrate;
                current.revision = QString::number(writes.load());
                if (lost) return {DeckPolarisRequestStatus::Unreachable, 0, {}, {}};
                return ok(current);
            };
            return target;
        };
    }
    void connect(bool newPairing = true) {
        if (newPairing) require(preferences.initializeKeepInStep("pc"), "new pairing preference failed");
        controller.setPlaySettings(&preferences);
        controller.setTarget("pc", "Fixture PC", resolver());
        controller.setWindowActive(true);
    }
    void differentProfile() {
        current.desiredDisplay = current.effectiveDisplay = "1920x1080x60";
        current.desiredBitrate = current.effectiveBitrate = 30000;
    }
};
void migrationAndConflict() {
    {
        Fixture f; f.connect(false); drain();
        require(f.reads == 0 && f.writes == 0 && f.preferences.keepInStep("pc") == "off", "upgrade changed existing default-Off pairing");
    }
    for (const auto& choice : {QString("off"), QString("paused")}) {
        Fixture f; require(f.preferences.saveKeepInStep("pc", choice), "cannot save existing choice");
        f.connect(); drain();
        require(f.reads == 0 && f.writes == 0 && f.preferences.keepInStep("pc") == choice, "pairing replaced a prior choice");
    }
    for (bool usePolaris : {false, true}) {
        Fixture f; f.differentProfile(); f.connect();
        until([&] { return f.controller.state().value("syncNeedsReview").toBool(); });
        require(f.writes == 0 && f.preferences.keepInStep("pc") == "review", "different pre-existing profile was overwritten");
        f.controller.close(); drain();
        require(f.writes == 0 && f.preferences.keepInStep("pc") == "review", "closing conflict review granted sync");
        f.controller.openSync(false); until([&] { return !f.controller.busy(); });
        require(f.controller.profileAction(usePolaris ? "use" : "match"), "profile choice refused");
        until([&] { return !f.controller.busy() && f.preferences.keepInStep("pc") == "on"; });
        require(!f.controller.state().value("syncNeedsReview").toBool(), "resolved conflict stayed visible");
        require(f.writes == (usePolaris ? 0 : 1), "profile choice sent unexpected writes");
        require(f.preferences.streamDefaults().value("width") == (usePolaris ? 1920 : 1280), "wrong profile was adopted");
    }
    {
        Fixture f;
        f.current.desiredDisplay = f.current.effectiveDisplay = "1280x800x60";
        f.current.desiredBitrate = f.current.effectiveBitrate = 20000;
        f.connect(); until([&] { return !f.controller.busy() && f.preferences.keepInStep("pc") == "on"; });
        require(f.writes == 0, "matching first profile needed a POST");
    }
}
void backgroundAndDeferral() {
    Fixture f;
    require(f.preferences.saveChoice("pc", "game", {{"bitrateKbps", 80000}}), "per-game setup failed");
    f.connect(); until([&] { return !f.controller.busy() && f.writes == 1; });
    require(f.current.desiredDisplay == "1280x800x60" && f.current.desiredBitrate == 20000, "first background save did not use device defaults");
    f.controller.setSessionActive(true);
    auto defaults = f.preferences.streamDefaults(); defaults["fps"] = 90;
    require(f.preferences.saveStreamDefaults(defaults), "cannot edit defaults during deferral");
    drain(); require(f.writes == 1, "sync wrote during a local stream");
    f.controller.setSessionActive(false);
    f.controller.setBackgroundBlocked(true); f.controller.setInteractionPaused(true);
    drain(); require(f.writes == 1, "sync ignored host/library operation");
    f.controller.setBackgroundBlocked(false); drain(); require(f.writes == 1, "sync interrupted another open UI flow");
    f.controller.setInteractionPaused(false);
    until([&] { return !f.controller.busy() && f.writes == 2; });
    require(f.current.desiredDisplay == "1280x800x90" && f.current.desiredBitrate == 20000, "deferred defaults were not synchronized");
    require(f.preferences.load("pc", "game").value("configuration").toMap().value("bitrateKbps") == 80000, "sync changed a game override");
    f.controller.close(); // Background operation must survive closing Settings.
    defaults["bitrateKbps"] = 40000; require(f.preferences.saveStreamDefaults(defaults), "cannot edit closed-view defaults");
    f.idle = false; drain(700); require(f.writes == 2, "remote active game did not defer background write");
    f.idle = true; until([&] { return !f.controller.busy() && f.writes == 3; });
    require(f.current.desiredBitrate == 40000, "closed settings view prevented background sync");
    f.controller.setWindowActive(false);
    defaults["fps"] = 120; require(f.preferences.saveStreamDefaults(defaults), "cannot stage focus deferral");
    drain(); require(f.writes == 3, "unfocused app changed profile");
    f.controller.setWindowActive(true);
    until([&] { return !f.controller.busy() && f.writes == 4; });
    require(f.controller.setKeepInStep(false), "cannot turn background sync off");
    defaults["fps"] = 60; require(f.preferences.saveStreamDefaults(defaults), "cannot edit after Off");
    drain(); require(f.writes == 4, "Off allowed a background write");
}
void interruptionAndHostChange() {
    {
        Fixture f; f.lost = true; f.connect();
        until([&] { return !f.controller.busy() && f.writes == 1; });
        require(f.preferences.keepInStep("pc") == "paused", "lost reply did not pause");
        f.controller.shutdown(); f.lost = false;
        DeckHostSettingsController restarted;
        restarted.setPlaySettings(&f.preferences); restarted.setTarget("pc", "Fixture", f.resolver()); restarted.setWindowActive(true);
        drain(700); require(f.writes == 1 && f.preferences.keepInStep("pc") == "paused", "restart retried an unconfirmed save");
    }
    {
        Fixture f; f.hold = true; f.connect(); until([&] { return f.entered.load(); });
        require(f.preferences.initializeKeepInStep("second"), "second pairing setup failed");
        f.controller.setTarget("second", "Second PC", f.resolver("second"));
        f.hold = false; until([&] { return !f.controller.busy() && f.writes == 1; });
        require(f.writtenHosts == QStringList{"second"}, "switching hosts wrote the previous PC");
    }
    {
        Fixture f; f.hold = true; f.connect(); until([&] { return f.entered.load(); });
        f.controller.shutdown(); f.hold = false; until([&] { return !f.controller.busy(); });
        drain(); require(f.writes == 0, "closing Nova allowed a pending write");
    }
    {
        Fixture f; f.hold = true; f.connect(); until([&] { return f.entered.load(); });
        f.valid = false; f.hold = false; until([&] { return !f.controller.busy(); });
        drain(); require(f.writes == 0, "changed pairing authorized background sync");
    }
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    migrationAndConflict(); backgroundAndDeferral(); interruptionAndHostChange();
    std::cout << "New-pair defaults, profile conflict choices, background sync and interruption guards passed\n";
}
