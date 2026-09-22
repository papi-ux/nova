#include "runtime/deck_library_controller.h"

#include <QCoreApplication>
#include <QElapsedTimer>
#include <atomic>
#include <condition_variable>
#include <cstdlib>
#include <iostream>
#include <mutex>

using namespace nova::deck;
using namespace nova::deck::runtime;
using namespace std::chrono_literals;

namespace {
void require(bool value, const char* message) {
    if (!value) { std::cerr << message << '\n'; std::exit(1); }
}
template<typename Predicate> void until(Predicate predicate) {
    QElapsedTimer clock;
    clock.start();
    while (!predicate() && clock.elapsed() < 3000) {
        QCoreApplication::processEvents();
        QThread::msleep(1);
    }
    require(predicate(), "library request did not settle");
}
void processFor(int milliseconds) {
    QElapsedTimer clock;
    clock.start();
    while (clock.elapsed() < milliseconds) {
        QCoreApplication::processEvents();
        QThread::msleep(1);
    }
}
struct Fixture {
    identity::DeckMoonlightIdentity saved;
    std::mutex mutex;
    std::condition_variable wake;
    bool released = false;
    std::atomic<bool> block{false}, entered{false};
    std::atomic<int> mode{0}, aCalls{0}, bCalls{0};
    Fixture() {
        saved.loaded = true;
        saved.sourceLabel = "nova-native";
        saved.clientCertificatePem = "fixture-client-cert";
        saved.clientPrivateKeyPemForBackendOnly = "fixture-private-key";
        for (const auto id : {"a", "b"}) {
            identity::DeckMoonlightHostRecord host;
            host.uuid = id;
            host.hostname = std::string("PC ") + id;
            host.manualAddress = std::string("fixture-") + id;
            host.manualPort = 47989;
            host.serverCertificatePem = std::string("fixture-pin-") + id;
            saved.hosts.push_back(host);
        }
    }
    auto load() { const std::lock_guard lock(mutex); return std::optional{saved}; }
    void changePin() { const std::lock_guard lock(mutex); saved.hosts[1].serverCertificatePem += "changed"; }
    void release() { const std::lock_guard lock(mutex); released = true; wake.notify_all(); }
    backend::DeckLivePolarisFetch fetch(const identity::DeckMoonlightHostRecord& host, bool) {
        require(QThread::currentThread() != QCoreApplication::instance()->thread(), "fetch blocked GUI thread");
        if (host.uuid == "a") ++aCalls; else ++bCalls;
        if (block) {
            std::unique_lock lock(mutex);
            entered = true;
            require(wake.wait_for(lock, 3s, [&] { return released; }), "fixture wait timed out");
        }
        if (mode == 3) throw std::runtime_error("fixture transport exception");
        backend::DeckLivePolarisFetch result;
        result.status = mode == 1 ? polaris::DeckPolarisRequestStatus::Unauthorized
            : mode == 4 ? polaris::DeckPolarisRequestStatus::Timeout
            : mode == 5 ? polaris::DeckPolarisRequestStatus::CertMismatch
            : mode == 6 ? polaris::DeckPolarisRequestStatus::InvalidIdentity : polaris::DeckPolarisRequestStatus::Ok;
        result.standardHost = true;
        result.httpsPort = host.uuid == "a" ? 48001 : 48002;
        if (mode == 0) {
            polaris::DeckPolarisGame game;
            game.id = "gamestream-app-42";
            game.appId = 42;
            game.name = "Game on " + host.uuid;
            result.games.push_back(game);
        }
        return result;
    }
};

void testAutomaticRefresh() {
    Fixture fixture;
    const auto original = fixture.saved;
    auto initial = backend::buildLiveSnapshot(original, [&](const auto& host, bool library) { return fixture.fetch(host, library); });
    DeckLibraryController controller(original, initial, [&] { return fixture.load(); },
        [&](const auto&) { return [&](const auto& host, bool library) { return fixture.fetch(host, library); }; });
    fixture.aCalls = fixture.bCalls = 0;
    int publications = 0;
    QObject::connect(&controller, &DeckLibraryController::snapshotChanged, [&] { ++publications; });
    controller.configureAutomaticRefresh(40, 160);
    controller.setInteractionPaused(false);
    processFor(70);
    require(fixture.aCalls == 0, "inactive window polled its host");
    controller.setWindowActive(true);
    controller.setInteractionPaused(true);
    processFor(70);
    require(fixture.aCalls == 0, "modal interaction polled its host");
    controller.setInteractionPaused(false);
    until([&] { return fixture.aCalls == 1 && !controller.busy(); });
    require(controller.state().value("automatic").toBool() && fixture.bCalls == 0, "automatic request changed hosts");
    controller.setSessionBusy(true);
    processFor(100);
    require(fixture.aCalls == 1, "native session allowed a library request");
    controller.setSessionBusy(false);
    until([&] { return fixture.aCalls == 2 && !controller.busy(); });

    fixture.block = true;
    controller.setWindowActive(false);
    controller.setWindowActive(true);
    until([&] { return fixture.entered.load(); });
    processFor(100);
    require(fixture.aCalls == 3 && controller.busy() && !controller.refresh(), "automatic requests overlapped");
    controller.setWindowActive(false);
    fixture.release();
    until([&] { return !controller.busy(); });
    fixture.block = false;
    processFor(100);
    require(fixture.aCalls == 3, "completion restarted polling in the background");

    fixture.mode = 4;
    controller.setWindowActive(true);
    until([&] { return fixture.aCalls == 4 && !controller.busy(); });
    processFor(50); // The first failure waits 80ms, twice the normal interval.
    require(fixture.aCalls == 4, "offline retry ignored first backoff");
    until([&] { return fixture.aCalls == 5 && !controller.busy(); });
    processFor(100); // Further failures wait the capped 160ms interval.
    require(fixture.aCalls == 5, "offline retry ignored capped backoff");
    until([&] { return fixture.aCalls == 6 && !controller.busy(); });
    QElapsedTimer cappedWait;
    cappedWait.start();
    until([&] { return fixture.aCalls == 7 && !controller.busy(); });
    require(cappedWait.elapsed() < 300, "offline backoff grew past its cap");

    fixture.mode = 1;
    require(controller.refresh(), "explicit trust recheck was refused");
    until([&] { return !controller.busy(); });
    const int deniedCalls = fixture.aCalls;
    const int deniedPublications = publications;
    controller.setWindowActive(false);
    controller.setWindowActive(true);
    processFor(220);
    require(fixture.aCalls == deniedCalls && publications == deniedPublications && !controller.targetResolver(),
            "authentication rejection retried automatically or retained launch authority");
    fixture.mode = 0;
    require(controller.refresh(), "explicit recovery was refused");
    until([&] { return !controller.busy(); });
    until([&] { return fixture.aCalls > deniedCalls + 1 && !controller.busy(); });

    fixture.changePin();
    const int beforeChange = fixture.aCalls;
    until([&] { return controller.state().value("failed").toBool() && !controller.busy(); });
    const int stalePublications = publications;
    processFor(220);
    require(fixture.aCalls == beforeChange && publications == stalePublications && controller.snapshot().library.games.empty(),
            "saved pairing change was retried or left stale games");

    { const std::lock_guard lock(fixture.mutex); fixture.saved = original; fixture.released = false; }
    fixture.block = true;
    fixture.entered = false;
    require(controller.refresh(), "shutdown check did not start");
    until([&] { return fixture.entered.load(); });
    controller.suspendAutomaticRefresh();
    fixture.release();
    until([&] { return !controller.busy(); });
    const int closingCalls = fixture.aCalls;
    processFor(100);
    require(fixture.aCalls == closingCalls && !controller.refresh() && !controller.targetResolver(),
            "closing restarted library work or launch authority");
}

void testInitialTrustFailures() {
    for (const int mode : {1, 5, 6}) {
        Fixture fixture;
        fixture.mode = mode;
        const auto original = fixture.saved;
        auto initial = backend::buildLiveSnapshot(original, [&](const auto& host, bool library) { return fixture.fetch(host, library); });
        DeckLibraryController controller(original, initial, [&] { return fixture.load(); },
            [&](const auto&) { return [&](const auto& host, bool library) { return fixture.fetch(host, library); }; });
        fixture.aCalls = fixture.bCalls = 0;
        controller.configureAutomaticRefresh(20, 40);
        controller.setInteractionPaused(false);
        controller.setWindowActive(true);
        processFor(70);
        require(fixture.aCalls == 0 && fixture.bCalls == 0, "startup trust rejection was retried automatically");
        require(controller.refresh(), "explicit trust check was refused");
        until([&] { return !controller.busy(); });
        processFor(70);
        require(fixture.aCalls == 1 && fixture.bCalls == 0 && !controller.targetResolver(),
                "trust rejection did not stop subsequent automatic requests");
        fixture.mode = 0;
        require(controller.refresh(), "trust recovery was refused");
        until([&] { return !controller.busy(); });
        until([&] { return fixture.aCalls >= 3 && !controller.busy(); });
        require(controller.targetResolver() != nullptr, "explicit trust recovery did not restore library access");
        controller.suspendAutomaticRefresh();
    }
}
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    Fixture fixture;
    const auto original = fixture.saved;
    auto initial = backend::buildLiveSnapshot(original, [&](const auto& host, bool library) { return fixture.fetch(host, library); });
    DeckLibraryController controller(original, initial, [&] { return fixture.load(); },
        [&](const auto&) { return [&](const auto& host, bool library) { return fixture.fetch(host, library); }; });
    fixture.aCalls = fixture.bCalls = 0;
    controller.setSessionBusy(true);
    require(!controller.selectHost("b") && fixture.bCalls == 0, "selection changed during streaming");
    controller.setSessionBusy(false);
    require(!controller.selectHost("missing"), "unknown PC was accepted");

    fixture.block = true;
    require(controller.selectHost("b") && controller.busy(), "selection did not start");
    require(!controller.refresh() && !controller.selectHost("a"), "overlapping request accepted");
    require(!controller.targetResolver(), "loading library retained launch authority");
    bool heartbeat = false;
    QTimer::singleShot(0, [&] { heartbeat = true; });
    until([&] { return fixture.entered && heartbeat; });
    fixture.release();
    until([&] { return !controller.busy(); });
    require(fixture.aCalls == 0 && fixture.bCalls == 1, "explicit selection contacted another PC");
    require(controller.snapshot().selectedHostId == "b" && controller.snapshot().hosts.size() == 2 &&
            controller.snapshot().library.games.front().name == "Game on b", "selection kept the other PC's library");
    auto resolver = controller.targetResolver();
    require(resolver && !resolver("a", "gamestream-app-42"), "colliding app ID authorized wrong PC");
    const auto target = resolver("b", "gamestream-app-42");
    require(target && target->serverAddress == "fixture-b" && target->appId == 42 && target->appUuid.empty(),
            "new library did not resolve the matching native target");
    fixture.block = false;

    fixture.mode = 1;
    require(controller.selectHost("a"), "denied selection did not start");
    until([&] { return !controller.busy(); });
    require(controller.snapshot().selectedHostId == "a" && controller.snapshot().library.games.empty() &&
            controller.state().value("failed").toBool() && !controller.targetResolver() && fixture.bCalls == 1,
            "denied PC retained stale games or fell back to its neighbor");

    fixture.mode = 2;
    require(controller.selectHost("b"), "empty selection did not start");
    until([&] { return !controller.busy(); });
    require(!controller.state().value("failed").toBool() && controller.snapshot().library.games.empty() &&
            !controller.targetResolver()("b", "gamestream-app-42"), "empty list restored old games");
    fixture.mode = 0;
    require(controller.refresh(), "refresh did not retry selected PC");
    until([&] { return !controller.busy(); });
    require(controller.snapshot().selectedHostId == "b" && controller.snapshot().library.games.size() == 1,
            "refresh changed the selected PC");

    resolver = controller.targetResolver();
    fixture.changePin();
    require(!resolver("b", "gamestream-app-42"), "changed saved pairing authorized launch");
    const int priorCalls = fixture.bCalls;
    require(controller.refresh(), "changed pairing did not report asynchronously");
    until([&] { return !controller.busy(); });
    require(fixture.bCalls == priorCalls && controller.snapshot().library.games.empty() && !controller.targetResolver(),
            "changed pairing sent a request or kept old games");

    { const std::lock_guard lock(fixture.mutex); fixture.saved = original; fixture.released = false; }
    fixture.block = true;
    fixture.entered = false;
    require(controller.refresh(), "concurrent-change request did not start");
    until([&] { return fixture.entered.load(); });
    fixture.changePin();
    fixture.release();
    until([&] { return !controller.busy(); });
    require(controller.snapshot().library.games.empty() && !controller.targetResolver(), "stale completion was admitted");

    { const std::lock_guard lock(fixture.mutex); fixture.saved = original; }
    fixture.block = false;
    fixture.mode = 3;
    require(controller.refresh(), "exception request did not start");
    until([&] { return !controller.busy(); });
    require(controller.state().value("failed").toBool() && controller.snapshot().library.games.empty(),
            "transport exception kept old data");
    require(!controller.state().value("copy").toString().contains("fixture"), "raw error reached UI");
    testAutomaticRefresh();
    testInitialTrustFailures();
    std::cout << "Library selection, focus-independent refresh, automatic pause/resume/backoff, trust rejection and shutdown passed\n";
}
