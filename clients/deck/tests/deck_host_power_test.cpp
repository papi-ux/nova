#include "runtime/deck_host_power.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QThread>
#include <atomic>
#include <cstdlib>
#include <iostream>
using namespace nova::deck::runtime;
using namespace nova::deck::polaris;
namespace {
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
void until(const std::function<bool()>& done) {
    QElapsedTimer clock; clock.start();
    while (!done() && clock.elapsed() < 3000) { QCoreApplication::processEvents(); QThread::msleep(2); }
    require(done(), "host-power operation did not settle");
}
void settle(int ms = 100) { QElapsedTimer clock; clock.start(); while (clock.elapsed() < ms) { QCoreApplication::processEvents(); QThread::msleep(2); } }
struct Fixture {
    std::atomic_int reads{0}, sleeps{0}, probes{0};
    std::atomic_bool feature{true}, enabled{true}, supported{true}, permitted{true}, valid{true}, awake{false}, fail{false}, block{false}, entered{false};
    long long lastAt = 101;
    DeckHostPowerResolver resolver() {
        const auto gui = QThread::currentThread();
        return [this, gui]() -> std::optional<DeckHostPowerTarget> {
            require(QThread::currentThread() != gui, "host request ran on GUI thread");
            DeckHostPowerTarget target;
            target.identityValid = [this] { return valid.load(); };
            target.capabilities = [this] {
                ++reads;
                if (block) { entered = true; while (block) QThread::msleep(1); }
                DeckPolarisCapabilities caps; caps.hostSleep = feature.load();
                caps.hostPower = {supported.load(), enabled.load(), permitted.load(), {}, {}, {}, 100};
                return DeckPolarisResult<DeckPolarisCapabilities>{DeckPolarisRequestStatus::Ok, 200, {}, caps};
            };
            target.sleep = [this](const std::function<bool()>& cancelled) {
                require(!cancelled(), "cancelled job reached sleep transport");
                ++sleeps;
                return fail ? DeckPolarisResult<DeckHostSleepReceipt>{DeckPolarisRequestStatus::Timeout, 0, {}, {}}
                    : DeckPolarisResult<DeckHostSleepReceipt>{DeckPolarisRequestStatus::Ok, 200, {}, DeckHostSleepReceipt{true, {}, {}}};
            };
            target.reachable = [this] { ++probes; return awake.load(); };
            target.power = [this] { return DeckPolarisResult<DeckHostPower>{DeckPolarisRequestStatus::Ok, 200, {},
                DeckHostPower{true, true, true, {}, "failed", "A task refused to suspend.", lastAt}}; };
            return target;
        };
    }
};
QString phase(const DeckHostPowerController& power) { return power.state().value("phase").toString(); }
void ready(DeckHostPowerController& power) { require(power.refresh(), "refresh refused"); until([&] { return !power.busy(); }); require(phase(power) == "ready", "host was not sleep-ready"); }
void countdown(DeckHostPowerController& power) { require(power.beginHold(), "hold refused"); until([&] { return phase(power) == "countdown"; }); }
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    require(!parseHostPower("[]"), "non-object power accepted");
    require(!parseHostPower(R"({"sleep_supported":"true","sleep_enabled":true,"sleep_permitted":true})")->allowed(), "string capability authorized sleep");
    require(!parseHostPower("{}")->allowed(), "absent capability authorized sleep");
    auto caps = parseCapabilities(R"({"features":{"host_sleep_v1":true},"host_power":{"sleep_supported":true,"sleep_enabled":true,"sleep_permitted":true}})");
    require(caps && caps->hostSleep && caps->hostPower.allowed(), "Android host-power contract not parsed");
    Fixture fixture;
    DeckHostPowerController power(nullptr, {30, 80, 3, 5});
    power.setTarget("pc-a", "Living Room PC", fixture.resolver());
    require(!power.refresh() && !power.beginHold(), "inactive window could arm sleep");
    power.setWindowActive(true);
    for (auto* gate : {&fixture.feature, &fixture.enabled, &fixture.supported, &fixture.permitted}) {
        *gate = false;
        require(power.refresh(), "unavailable capability could not be checked");
        until([&] { return !power.busy(); });
        require(phase(power) == "unavailable" && !power.beginHold() && fixture.sleeps == 0,
                "missing feature, support or permission authorized sleep");
        *gate = true;
    }
    ready(power);
    require(fixture.sleeps == 0 && power.beginHold(), "checking capabilities mutated host");
    power.releaseHold(); settle();
    require(phase(power) == "ready" && fixture.sleeps == 0, "tap slept the PC");
    countdown(power);
    require(!power.beginHold() && !power.refresh(), "second hold reset the countdown");
    power.cancel(); settle(150);
    require(fixture.sleeps == 0 && phase(power) == "ready", "cancelled countdown sent sleep");
    countdown(power); power.setWindowActive(false); settle(150);
    require(fixture.sleeps == 0, "focus loss failed to cancel countdown");
    power.setWindowActive(true);
    ready(power); countdown(power); fixture.permitted = false;
    until([&] { return !power.busy(); });
    require(phase(power) == "unavailable" && fixture.sleeps == 0, "stale permission authorized sleep");
    fixture.permitted = true;
    ready(power); countdown(power);
    until([&] { return !power.busy(); });
    require(phase(power) == "offline" && fixture.sleeps == 1 && fixture.probes == 1, "accepted sleep did not verify reachability");
    settle(200); require(fixture.sleeps == 1, "completed sleep was retried");
    fixture.awake = true;
    ready(power); countdown(power); until([&] { return !power.busy(); });
    require(phase(power) == "awake" && power.state().value("copy") == "A task refused to suspend.", "accepted but awake hid the fresh host outcome");
    fixture.lastAt = 100;
    ready(power); countdown(power); until([&] { return !power.busy(); });
    require(phase(power) == "awake" && !power.state().value("copy").toString().contains("refused"), "stale outcome was attributed to this sleep request");
    fixture.fail = true;
    ready(power); countdown(power); until([&] { return !power.busy(); });
    const auto sent = fixture.sleeps.load();
    require(phase(power) == "failed", "dropped answer was reported as asleep");
    settle(200); require(fixture.sleeps == sent, "ambiguous sleep was retried");
    power.setSessionActive(true);
    require(!power.refresh() && !power.beginHold(), "active session allowed sleep");
    power.setSessionActive(false); fixture.fail = false;
    ready(power); countdown(power); fixture.block = true;
    until([&] { return fixture.entered.load(); });
    power.setTarget("pc-b", "Other PC", fixture.resolver());
    fixture.block = false;
    until([&] { return !power.busy(); });
    require(fixture.sleeps == sent && power.state().value("hostName") == "Other PC", "old PC job survived selection change");
    ready(power); countdown(power); fixture.valid = false;
    until([&] { return !power.busy(); });
    require(fixture.sleeps == sent, "changed pairing authorized sleep");
    fixture.valid = true;
    // Closing/backgrounding after dispatch cannot undo the host action. It must
    // report an unknown outcome and never replay the accepted request.
    DeckHostPowerController interrupted(nullptr, {30, 80, 10, 100});
    interrupted.setTarget("pc-a", "Living Room PC", fixture.resolver());
    interrupted.setWindowActive(true);
    ready(interrupted); countdown(interrupted);
    until([&] { return phase(interrupted) == "confirming"; });
    interrupted.setWindowActive(false);
    until([&] { return !interrupted.busy(); });
    require(fixture.sleeps == sent + 1 && interrupted.state().value("copy").toString().contains("wasn't confirmed"),
            "cancel after dispatch claimed the PC was asleep or sent again");
    settle(200); require(fixture.sleeps == sent + 1, "interrupted confirmation replayed sleep");
    std::cout << "Host sleep passed: capabilities, hold/cancel, fresh permission, no retry, receipt/outcome, session/focus and identity guards\n";
}
