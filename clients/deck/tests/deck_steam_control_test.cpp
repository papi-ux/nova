#include "runtime/deck_steam_control.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <atomic>
#include <cstdlib>
#include <iostream>
using namespace nova::deck::runtime;
using namespace nova::deck::polaris;
namespace {
void require(bool value, const char* text) { if (!value) { std::cerr << text << '\n'; std::exit(1); } }
void until(const std::function<bool()>& done) {
    QElapsedTimer time; time.start();
    while (!done() && time.elapsed() < 3000) { QCoreApplication::processEvents(); QThread::msleep(2); }
    require(done(), "Steam operation did not settle");
}
struct Fixture {
    std::atomic<int> posts {0};
    std::atomic<bool> valid {true}, fail {false}, block {false}, entered {false};
    std::string state = "running", revision = "reviewed-root";
    bool game = true;
    DeckSteamResolver resolver() {
        const auto gui = QThread::currentThread();
        return [this, gui]() -> std::optional<DeckSteamTarget> {
            require(gui != QThread::currentThread(), "Steam transport ran on GUI thread");
            DeckSteamTarget target;
            target.identityValid = [this] { return valid.load(); };
            target.read = [this] {
                if (block) { entered = true; while (block) QThread::msleep(1); }
                return DeckPolarisResult<DeckDesktopSteam> {DeckPolarisRequestStatus::Ok, 200, {},
                    DeckDesktopSteam {state, revision, {}, game, true, false}};
            };
            target.act = [this](const std::string& action, const std::string& observed, bool confirmed, const std::function<bool()>& cancelled) {
                require(!cancelled(), "Cancelled Steam job reached mutation");
                require(observed == revision, "Mutation did not use reviewed revision");
                require(action != "quit" || !game || confirmed, "Active game quit had no confirmation");
                ++posts;
                if (fail) return DeckPolarisResult<DeckDesktopSteam> {DeckPolarisRequestStatus::Timeout, 0, {}, {}};
                state = action == "quit" ? "stopped" : "running";
                return DeckPolarisResult<DeckDesktopSteam> {DeckPolarisRequestStatus::Ok, 200, {},
                    DeckDesktopSteam {state, revision, {}, game, true, true}};
            };
            return target;
        };
    }
};
void ready(DeckSteamControl& control) {
    require(control.refresh(), "Refresh refused"); until([&] { return !control.busy(); });
    require(control.state().value("available").toBool(), "Fresh status unavailable");
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    require(!parseDesktopSteam("{}"), "Missing contract authorized Steam");
    require(!parseDesktopSteam(R"({"version":1,"status":true,"state":"running","revision":"a","game_active":"false","effective_keep_running":true})"), "String game flag authorized Steam");
    auto blocked = parseDesktopSteam(R"({"version":1,"status":false,"state":"unavailable","error":"Private runtime"})");
    require(blocked && !blocked->available() && blocked->message == "Private runtime", "Host refusal lost");
    Fixture f; DeckSteamControl control;
    control.setTarget("pc-a", "First PC", f.resolver()); ready(control);
    require(!control.request("quit"), "Unconfirmed quit accepted"); require(f.posts == 0, "Unconfirmed quit sent");
    require(!control.request("shell"), "Arbitrary command accepted");
    require(control.request("quit", true), "Confirmed quit refused"); until([&] { return !control.busy(); });
    require(f.posts == 1 && control.state().value("state") == "stopped", "Quit was not observed");
    ready(control); f.fail = true;
    require(control.request("open"), "Open refused"); until([&] { return !control.busy(); });
    require(f.posts == 2 && !control.state().value("available").toBool(), "Timeout retried or retained authority");
    f.fail = false; ready(control);
    control.setTarget("pc-a", "First PC", f.resolver(), "new-pairing");
    require(!control.state().value("available").toBool(), "New pairing retained old Steam review");
    require(!control.request("open"), "New pairing accepted an old review");
    ready(control); f.valid = false;
    require(control.request("open"), "Mutation did not enter identity guard"); until([&] { return !control.busy(); });
    require(f.posts == 2, "Changed pairing reached mutation");
    f.valid = true; f.block = true;
    require(control.refresh(), "Blocked refresh refused"); until([&] { return f.entered.load(); });
    control.setTarget("pc-b", "Second PC", {}); f.block = false;
    until([&] { return !control.busy(); });
    require(!control.state().value("available").toBool() && control.state().value("hostName") == "Second PC", "Old PC result escaped cancellation");
    return 0;
}
