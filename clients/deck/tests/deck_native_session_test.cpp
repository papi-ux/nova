#include <QFile>
#include <QDir>
#include "runtime/deck_support_report.h"
#include "runtime/deck_native_session.h"
#include "runtime/deck_desktop_input_bridge.h"
#include "deck_doctor_fixture.h"

#include <QCoreApplication>
#include <QGuiApplication>
#include <QQuickWindow>
#include <QQuickItem>
#include <QMouseEvent>
#include <QWheelEvent>
#include <QElapsedTimer>
#include <QJsonDocument>
#include <QSettings>
#include <QTemporaryDir>
#include <atomic>
#include <condition_variable>
#include <cstdlib>
#include <iostream>
#include <mutex>
#include <thread>

using namespace nova::deck::runtime;
using namespace nova::deck::stream;
using namespace std::chrono_literals;

namespace {
void require(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}
template<typename Predicate> void until(Predicate predicate, int timeoutMs = 3000) {
    QElapsedTimer deadline;
    deadline.start();
    while (!predicate() && deadline.elapsed() < timeoutMs) {
        QCoreApplication::processEvents();
        QThread::msleep(1);
    }
    require(predicate(), "timed out waiting for native session");
}
QString phase(const DeckNativeSessionController& controller) { return controller.state().value("phase").toString(); }
void settled(DeckNativeSessionController& controller) { until([&]() { return !controller.busy(); }); }

struct Barrier {
    std::mutex mutex;
    std::condition_variable wake;
    std::atomic<bool> entered{false};
    bool released = false;
    void wait() {
        std::unique_lock lock(mutex);
        entered = true;
        require(wake.wait_for(lock, 3s, [&]() { return released; }), "barrier timed out");
    }
    void release() { const std::lock_guard lock(mutex); released = true; wake.notify_all(); }
};

struct Driver final : DeckMoonlightConnectionDriver {
    using RumbleCallback = void (*)(unsigned short, unsigned short, unsigned short);
    std::atomic<RumbleCallback> feedback{nullptr};
    std::atomic<int> starts{0}, stops{0}, interrupts{0};
    std::atomic<int> rttReads{0};
    bool estimatedRtt(std::uint32_t& rtt, std::uint32_t& variation) override {
        require(QThread::currentThread() == startThread && starts > stops, "RTT read outside owning active worker");
        ++rttReads; rtt = 17; variation = 3; return true;
    }
    STREAM_CONFIGURATION receivedConfiguration{};
    bool blockStart = false, blockStop = false;
    Barrier stopBarrier;
    bool failStart = false;
    Barrier startBarrier;
    QThread* startThread = nullptr;
    QThread* stopThread = nullptr;
    std::mutex mutex;
    std::condition_variable wake;
    bool end = false;
    bool disconnect = false;
    int terminationCode = -7;
    std::thread listenerThread;

    int start(SERVER_INFORMATION&, STREAM_CONFIGURATION& configuration, CONNECTION_LISTENER_CALLBACKS& listener,
        DECODER_RENDERER_CALLBACKS&, AUDIO_RENDERER_CALLBACKS&, void*) override {
        startThread = QThread::currentThread();
        receivedConfiguration = configuration;
        feedback = listener.rumble;
        ++starts;
        if (blockStart) { startBarrier.wait(); return -1; }
        if (failStart) return -1;
        end = disconnect = false;
        listenerThread = std::thread([this, callback = listener.connectionTerminated]() {
            std::unique_lock lock(mutex);
            wake.wait(lock, [&]() { return end || disconnect; });
            const bool terminated = disconnect;
            const int error = terminationCode;
            lock.unlock();
            if (terminated) callback(error);
        });
        return 0;
    }
    void interrupt() override { ++interrupts; startBarrier.release(); }
    void stop() override {
        stopThread = QThread::currentThread();
        ++stops;
        { const std::lock_guard lock(mutex); end = true; wake.notify_all(); }
        if (listenerThread.joinable()) listenerThread.join();
        if (blockStop) stopBarrier.wait();
    }
    void terminate(int error = -7) { const std::lock_guard lock(mutex); terminationCode = error; disconnect = true; wake.notify_all(); }
};

struct Host {
    std::atomic<int> requests{0}, launches{0}, resumes{0}, cancels{0}, resolves{0};
    QString expectedGame = "game";
    std::string blockPath;
    Barrier barrier;
    bool refuseLaunch = false;
    bool failServerInfo = false;
    bool missingUrl = false;
    bool refuseCancel = false;
    Driver* driver = nullptr;
    std::string launchRequest, resumeRequest, serverInfoOverride;
    int appId = 17;
    std::string appUuid;
    std::string launchToken = "private-token";
    bool rejectResolve = false;
    bool automaticReconnect = false, transientServerFailure = false, transientCapabilities = false;
    std::string resumeResponse = "<root status_code=\"200\"><resume>1</resume><sessionToken>private-token</sessionToken>"
        "<sessionUrl0>rtsp://192.0.2.10:48010</sessionUrl0></root>";
    DeckVideoDecodeSupport decoderSupport{.h264 = {4096, 4096}, .hevc = {1920, 1200}};
    DeckHudHostFactory hostTelemetry;
    std::function<bool(const QString&, const QString&, const std::function<bool()>&)> authorizeSetup;
    std::function<bool(const std::string&, const std::function<bool()>&)> authorizeMode;
    std::function<std::optional<nova::deck::DeckStreamCapabilities>(const std::function<bool()>&)> verifyStream;

    DeckNativeTargetResolver resolver() {
        return [this](const QString& host, const QString& game) -> std::optional<DeckNativeLaunchTarget> {
            ++resolves;
            require(QThread::currentThread() != QCoreApplication::instance()->thread(), "resolver blocked GUI thread");
            if (rejectResolve || host != "host" || game != expectedGame) return std::nullopt;
            DeckNativeLaunchTarget target;
            target.request = {.hostId = "host", .gameId = game.toStdString()};
            target.appId = appId;
            target.appUuid = appUuid;
            target.authorizeSetup = authorizeSetup;
            target.authorizeLaunchMode = authorizeMode;
            target.verifyStreamCapabilities = verifyStream;
            target.probeVideoSupport = [this] { return decoderSupport; };
            target.hostTelemetry = hostTelemetry;
            target.automaticReconnect = automaticReconnect;
            target.transientCapabilityFailure = [this] { return transientCapabilities; };
            target.serverAddress = "192.0.2.10";
            target.fetch = [this](const std::string& request) {
                const auto path = request.substr(0, request.find('?'));
                ++requests;
                require(QThread::currentThread() != QCoreApplication::instance()->thread(), "HTTP blocked GUI thread");
                if (path == "/launch") ++launches;
                if (path == "/resume") ++resumes;
                if (path == blockPath) barrier.wait();
                if (path == "/resume") { resumeRequest = request; return DeckHttpResponse{true, 200, resumeResponse}; }
                if (path == "/serverinfo" && failServerInfo) return DeckHttpResponse{false, 0, {}, transientServerFailure};
                if (path == "/serverinfo" && !serverInfoOverride.empty()) return DeckHttpResponse{true, 200, serverInfoOverride};
                if (path == "/serverinfo") return failServerInfo ? DeckHttpResponse{false, 0, {}} :
                    DeckHttpResponse{true, 200, "<root status_code=\"200\"><appversion>7.1.431.0</appversion></root>"};
                if (path == "/launch") {
                    launchRequest = request;
                    if (refuseLaunch) return DeckHttpResponse{true, 200, "<root status_code=\"503\"><gamesession>0</gamesession></root>"};
                    return DeckHttpResponse{true, 200, std::string("<root status_code=\"200\"><gamesession>1</gamesession><sessionToken>") + launchToken + "</sessionToken>"
                        + (missingUrl ? "" : "<sessionUrl0>rtsp://192.0.2.10:48010</sessionUrl0>") + "</root>"};
                }
                require(path == "/cancel", "unexpected native HTTP target");
                require(request == "/cancel?sessiontoken=private-token", "cleanup lost session token");
                if (driver && driver->starts > 0) require(driver->stops == driver->starts, "host cancel ran before stream teardown");
                ++cancels;
                return DeckHttpResponse{true, 200, refuseCancel
                    ? "<root status_code=\"470\" status_message=\"private-token\"><cancel>0</cancel></root>"
                    : "<root status_code=\"200\"><cancel>1</cancel></root>"};
            };
            return target;
        };
    }
};

void testNoAutomaticStartOrInvalidSelection() {
    Host host;
    Driver driver;
    DeckNativeSessionController disabled(false, host.resolver(), driver);
    require(!disabled.start("host", "game") && host.requests == 0, "disabled preview started network");
    DeckNativeSessionController controller(true, host.resolver(), driver);
    require(host.requests == 0 && driver.starts == 0, "constructing controller started a session");
    require(controller.start("wrong-host", "game"), "invalid selection was not handled asynchronously");
    settled(controller);
    require(phase(controller) == "failed" && host.requests == 0 && driver.starts == 0, "invalid selection reached host");
}

void testPresentationLifetime() {
    struct Sink : QObject, DeckQtQuickRhiPresentationSink {
        explicit Sink(int& clears) : clears(clears) {}
        int& clears;
        bool presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& frame) override {
            require(!frame.frameLease, "fixture unexpectedly received video");
            ++clears; return false;
        }
    };
    for (bool destroy : {false, true}) {
        Host host; Driver driver;
        int clears = 0, otherClears = 0;
        auto sink = std::make_unique<Sink>(clears);
        Sink other(otherClears);
        auto frames = std::make_shared<std::atomic<uint64_t>>(0);
        DeckNativeSessionController controller(true, host.resolver(), driver);
        require(!controller.setPresentationSink(nullptr, sink.get()) &&
            !controller.setPresentationSink(sink.get(), nullptr), "unbounded presentation target accepted");
        require(controller.setPresentationSink(sink.get(), sink.get(), frames), "presentation bind failed");
        require(controller.start("host", "game"), "presentation fixture did not start");
        until([&] { return phase(controller) == "active" && controller.hud().value("fps") == "0.0"; });
        require(controller.setPresentationSink(sink.get(), sink.get(), frames) &&
            !controller.setPresentationSink(&other, &other, frames) &&
            !controller.setPresentationSink(sink.get(), sink.get(), std::make_shared<std::atomic<uint64_t>>(0)),
            "busy target or HUD counter was replaced");
        frames->fetch_add(30);
        until([&] { const auto fps = controller.hud().value("fps").toString(); return fps != "--" && fps != "0.0"; });
        if (destroy) sink.reset();
        else require(controller.setPresentationSink(nullptr, nullptr), "live detachment failed");
        const auto stoppedClears = clears;
        frames->fetch_add(1000);
        until([&] { return controller.hud().value("fps") == "--"; });
        controller.stop(); settled(controller);
        require(clears == stoppedClears && otherClears == 0 && driver.stops == 1 && host.cancels == 1,
            "teardown touched a detached/destroyed sink or changed host cleanup");
        require(controller.setPresentationSink(&other, &other, frames), "idle presentation replacement failed");
        require(controller.setPresentationSink(nullptr, nullptr) && otherClears == 1, "replacement was not cleared");
    }
}

void testRevalidatedPresetAndEncoder() {
    for (const int admission : {0, 1, 2}) {
        Host host; Driver driver;
        std::atomic<int> checks{0};
        if (admission) host.authorizeSetup = [&](const QString& preset, const QString& encoder, const auto& cancelled) {
            require(QThread::currentThread() != QCoreApplication::instance()->thread(), "setup admission blocked GUI");
            ++checks;
            require(preset == "quality" && encoder == "vaapi" && !cancelled(), "reviewed setup changed before admission");
            return admission == 2;
        };
        DeckNativeSessionController controller(true, host.resolver(), driver);
        auto configuration = DeckPlayConfiguration{}.toMap(); configuration["profilePreference"] = "quality"; configuration["encoderBackend"] = "vaapi";
        require(controller.startConfigured("host","game",configuration), "preset stream did not start asynchronously");
        if (admission != 2) {
            settled(controller);
            require(phase(controller) == "failed" && host.launches == 0 && driver.starts == 0 && checks == admission, "unverified setup reached host launch");
        } else {
            until([&] { return phase(controller) == "active"; });
            require(checks == 1 && host.launches == 1 && host.launchRequest.find("profilePreference=quality") != std::string::npos &&
                host.launchRequest.find("encoderBackend=vaapi") != std::string::npos, "admitted setup did not reach host launch");
            controller.stop(); settled(controller);
        }
    }
}

void testReviewedConfiguration() {
    Host host;
    Driver driver;
    DeckNativeSessionController controller(true, host.resolver(), driver);
    auto values = DeckPlayConfiguration{2560, 1440, 45, 27500}.toMap();
    auto bad = values;
    bad["fps"] = 120;
    require(!controller.startConfigured("host", "game", bad) && host.resolves == 0 && host.requests == 0,
        "invalid settings reached the resolver or host");
    require(controller.startConfigured("host", "game", values), "reviewed configuration rejected");
    values["width"] = 1280;
    values["height"] = 720;
    values["fps"] = 60;
    values["bitrateKbps"] = 10000;
    require(!controller.startConfigured("host", "game", values), "in-flight configuration replaced");
    until([&]() { return phase(controller) == "active"; });
    require(host.launchRequest.find("mode=2560x1440x45") != std::string::npos,
        "reviewed resolution/rate did not reach host launch");
    const auto& received = driver.receivedConfiguration;
    require(received.width == 2560 && received.height == 1440 && received.fps == 45 && received.bitrate == 27500,
        "stream configuration differs from the immutable review");
    controller.stop();
    settled(controller);
    require(driver.stops == 1 && host.cancels == 1, "configured launch broke cleanup");
    require(controller.startConfigured("wrong-host", "game", values), "invalid host was not resolved asynchronously");
    settled(controller);
    require(phase(controller) == "failed" && host.launches == 1, "configuration bypassed host/game authority");
}

void testLaunchModeAuthorization() {
    for (int scenario = 0; scenario < 5; ++scenario) {
        Host host;
        Driver driver;
        Barrier check;
        std::atomic<int> checked{0};
        host.authorizeMode = [&](const std::string& mode, const std::function<bool()>& cancelled) {
            require(mode == "headless_stream", "mode validation received a different choice");
            ++checked;
            if (scenario == 3) check.wait();
            return scenario != 1 && !cancelled();
        };
        auto resolver = host.resolver();
        DeckNativeSessionController controller(true, [&](const QString& pc, const QString& game) {
            if (scenario == 4 && checked.load()) return std::optional<DeckNativeLaunchTarget>{};
            return resolver(pc, game);
        }, driver);
        auto values = DeckPlayConfiguration{}.toMap();
        values["launchMode"] = scenario == 2 ? "default" : "headless_stream";
        require(controller.startConfigured("host", "game", values), "launch mode choice rejected");
        values["launchMode"] = "desktop_display";
        if (scenario == 3) { until([&]() { return check.entered.load(); }); controller.stop(); check.release(); }
        if (scenario == 0 || scenario == 2) {
            until([&]() { return phase(controller) == "active"; });
            require((host.launchRequest.find("&streamMode=headless_stream") != std::string::npos) == (scenario == 0),
                "explicit mode lost or default became an override");
            require(checked == (scenario == 0 ? 1 : 0), "default performed unnecessary mode authorization");
            controller.stop();
        }
        settled(controller);
        if (scenario == 1 || scenario == 3 || scenario == 4)
            require(host.launches == 0 && host.cancels == 0 && driver.starts == 0, "denied/stale/cancelled mode mutated the host");
    }
    Host host;
    Driver driver;
    DeckNativeSessionController standard(true, host.resolver(), driver);
    auto values = DeckPlayConfiguration{}.toMap(); values["launchMode"] = "headless_stream";
    require(standard.startConfigured("host", "game", values), "missing authority did not settle asynchronously");
    settled(standard);
    require(phase(standard) == "failed" && host.requests == 0, "missing authority launched an override");
    values["launchMode"] = "headless_dongle";
    require(!standard.startConfigured("host", "game", values), "host-wide dongle mode accepted");
}

void testStreamCapabilitiesAtLaunch() {
    for (int scenario = 0; scenario < 7; ++scenario) {
        Host host;
        Driver driver;
        Barrier check;
        std::atomic<bool> checked{false};
        host.verifyStream = [&](const std::function<bool()>& cancelled) -> std::optional<nova::deck::DeckStreamCapabilities> {
            checked = true;
            if (scenario == 5) check.wait();
            if (scenario == 4 || cancelled()) return {};
            nova::deck::DeckStreamCapabilities caps;
            if (scenario == 1) caps.maxFps = 20;
            if (scenario == 2) caps.h264 = false;
            if (scenario == 3) caps.valid = false;
            return caps;
        };
        const auto original = host.resolver();
        DeckNativeSessionController controller(true, [&](const QString& pc, const QString& game) {
            if (scenario == 6 && checked.load()) return std::optional<DeckNativeLaunchTarget>{};
            return original(pc, game);
        }, driver);
        const auto values = DeckPlayConfiguration{1920, 1200, 30, 20000}.toMap();
        require(controller.startConfigured("host", "game", values), "stream review not accepted");
        if (scenario == 5) { until([&]() { return check.entered.load(); }); controller.stop(); check.release(); }
        if (scenario == 0) {
            until([&]() { return phase(controller) == "active"; });
            require(host.launchRequest.find("mode=1920x1200x30") != std::string::npos &&
                driver.receivedConfiguration.height == 1200 && driver.receivedConfiguration.fps == 30,
                "effective stream plan did not reach launch and decoder configuration");
            controller.stop();
        }
        settled(controller);
        if (scenario != 0) require(host.requests == 0 && driver.starts == 0, "withdrawn/stale/cancelled capabilities reached launch");
    }
}

void testVideoCodecAtLaunch() {
    // Fresh local decode, catalog and serverinfo checks precede host mutation.
    for (int scenario = 0; scenario < 8; ++scenario) {
        Host host;
        Driver driver;
        host.verifyStream = [&](const auto&) -> std::optional<nova::deck::DeckStreamCapabilities> {
            nova::deck::DeckStreamCapabilities capabilities;
            capabilities.hevc = true;
            if (scenario == 1) capabilities.hevc = false;
            if (scenario == 7) capabilities.h264 = false;
            return capabilities;
        };
        host.serverInfoOverride = "<root status_code=\"200\"><appversion>7.1</appversion><ServerCodecModeSupport>" +
            std::string(scenario == 4 ? "513" : "257") + "</ServerCodecModeSupport></root>";
        if (scenario == 2 || scenario == 5) host.decoderSupport.hevc = {};
        if (scenario == 3) host.decoderSupport.hevc = {1280, 720};
        if (scenario == 6) host.expectedGame = "space.worker";
        DeckNativeSessionController controller(true, host.resolver(), driver);
        auto values = DeckPlayConfiguration{}.toMap();
        values["videoCodec"] = scenario == 5 ? "auto" : "hevc";
        require(controller.startConfigured("host", host.expectedGame, values), "codec review not accepted");
        const bool allowed = scenario == 0 || scenario == 5 || scenario == 7;
        if (allowed) {
            until([&] { return phase(controller) == "active"; });
            require(driver.receivedConfiguration.supportedVideoFormats == (scenario == 5 ? VIDEO_FORMAT_H264 : VIDEO_FORMAT_H265),
                "reviewed codec did not reach Moonlight negotiation");
            require(!(driver.receivedConfiguration.supportedVideoFormats & VIDEO_FORMAT_MASK_10BIT) &&
                driver.receivedConfiguration.colorSpace == COLORSPACE_REC_709, "SDR selected HDR negotiation");
            controller.stop();
        }
        settled(controller);
        if (!allowed) require(host.launches == 0 && host.resumes == 0 && host.cancels == 0 && driver.starts == 0,
            "unsupported/stale codec mutated host or started transport");
    }
}

void testDisplayRateAtLaunch() {
    const auto values = DeckPlayConfiguration{1280, 800, 90, 20000}.toMap();
    {
        Host host;
        Driver driver;
        DeckNativeSessionController controller(true, host.resolver(), driver);
        require(!controller.startConfigured("host", "game", values) && host.resolves == 0,
            "unverified display allowed a 90 FPS request");
    }
    for (int scenario = 0; scenario < 6; ++scenario) {
        Host host;
        Driver driver;
        host.driver = &driver;
        Barrier check;
        std::atomic<int> displayLimit{90};
        host.verifyStream = [&](const std::function<bool()>& cancelled) -> std::optional<nova::deck::DeckStreamCapabilities> {
            if (scenario == 3 || scenario == 5) check.wait();
            if (cancelled()) return {};
            nova::deck::DeckStreamCapabilities caps;
            caps.maxFps = scenario == 1 ? 60 : scenario == 2 ? 0 : 120;
            return caps;
        };
        if (scenario == 4) host.blockPath = "/serverinfo";
        DeckNativeSessionController controller(true, host.resolver(), driver);
        require(controller.setDisplayRateLimitReader([&] { return displayLimit.load(); }), "cannot bind display rate");
        require(controller.startConfigured("host", "game", values), "supported display rejected review");
        require(!controller.setDisplayRateLimitReader([] { return 60; }), "busy session replaced its display reader");
        if (scenario == 3 || scenario == 5) {
            until([&] { return check.entered.load(); });
            if (scenario == 5) controller.stop();
            else displayLimit = 60;
            check.release();
        }
        if (scenario == 4) {
            until([&] { return host.barrier.entered.load(); });
            displayLimit = 60;
            host.barrier.release();
        }
        if (scenario == 0) {
            until([&] { return phase(controller) == "active"; });
            require(host.launchRequest.find("mode=1280x800x90") != std::string::npos && driver.receivedConfiguration.fps == 90,
                "90 FPS review did not reach both launch and stream configuration");
            displayLimit = 60;
            QElapsedTimer timer; timer.start();
            while (timer.elapsed() < 50) { QCoreApplication::processEvents(); QThread::msleep(1); }
            require(phase(controller) == "active" && driver.receivedConfiguration.fps == 90 && driver.stops == 0,
                "display change renegotiated or ended an active game");
            controller.stop();
        }
        settled(controller);
        if (scenario == 0) require(driver.stops == 1 && host.cancels == 1, "90 FPS stream broke cleanup");
        else {
            require(host.launches == 0 && driver.starts == 0 && host.cancels == 0,
                "unsupported, changed or cancelled display plan launched a game");
            require(phase(controller) == (scenario == 5 ? "cancelled" : "failed"), "display failure phase is incorrect");
            if (scenario == 3 || scenario == 4)
                require(controller.state().value("copy").toString().contains("display rate changed"), "display change lacks recovery guidance");
        }
    }
}

void testBackendTargetAuthority() {
    nova::deck::identity::DeckMoonlightIdentity identity;
    identity.loaded = true;
    identity.clientCertificatePem = "test certificate";
    identity.clientPrivateKeyPemForBackendOnly = "test key";
    identity.hosts.push_back({.uuid = "host", .localAddress = "192.0.2.10", .serverCertificatePem = "test pin"});
    identity.hosts.push_back({.uuid = "other-host", .localAddress = "192.0.2.11", .serverCertificatePem = "other pin"});
    nova::deck::backend::DeckLiveHostLibrarySnapshot snapshot;
    snapshot.selectedHostId = "host";
    snapshot.library.games.push_back({.id = "game-uuid", .appId = 17, .name = "Same title"});
    snapshot.library.games.push_back({.id = "moonlight-app-23", .appId = 23, .name = "Same title"});
    const auto resolve = nativeTargetResolver(identity, snapshot);
    require(!resolve("other-host", "game-uuid"), "library app id crossed host boundary");
    require(!resolve("host", "Same title"), "display title became launch authority");
    require(!resolve("host", "missing"), "unknown id became launch authority");
    const auto polaris = resolve("host", "game-uuid");
    require(polaris && polaris->appId == 17 && polaris->appUuid == "game-uuid" && polaris->automaticReconnect && polaris->hostTelemetry, "Polaris id mapping changed");
    const auto cached = resolve("host", "moonlight-app-23");
    require(cached && cached->appId == 23 && cached->appUuid.empty() && !cached->automaticReconnect && !cached->hostTelemetry, "synthetic id sent as app UUID");
    auto standardSnapshot = snapshot;
    standardSnapshot.probes.push_back({.hostId = "host", .status = nova::deck::polaris::DeckPolarisRequestStatus::Ok, .standardHost = true});
    const auto standard = nativeTargetResolver(identity, standardSnapshot)("host", "game-uuid");
    require(standard && standard->appId == 17 && standard->appUuid.empty() && !standard->automaticReconnect && !standard->hostTelemetry, "standard host received a Polaris app UUID");
    standardSnapshot.probes.front().status = nova::deck::polaris::DeckPolarisRequestStatus::Unauthorized;
    require(!nativeTargetResolver(identity, standardSnapshot)("host", "moonlight-app-23"), "cached app bypassed explicit authorization rejection");
    // Structurally present but unreadable credentials still fail in the actual
    // pinned transport before any network request can be made.
    require(!cached->fetch("/serverinfo").transportOk, "invalid credentials reached transport");
    identity.hosts.front().serverCertificatePem.clear();
    require(!nativeTargetResolver(identity, snapshot)("host", "game-uuid"), "missing certificate was accepted");
    require(!nativeTargetResolver(std::nullopt, snapshot)("host", "game-uuid"), "missing identity was accepted");
}

void testCancelDuringHttp(const std::string& path) {
    Host host;
    Driver driver;
    host.blockPath = path;
    DeckNativeSessionController controller(true, host.resolver(), driver);
    int heartbeat = 0;
    QTimer timer;
    QObject::connect(&timer, &QTimer::timeout, [&]() { ++heartbeat; });
    timer.start(1);
    require(controller.start("host", "game"), "start rejected");
    until([&]() { return host.barrier.entered.load() && heartbeat > 5; });
    require(!controller.start("host", "game"), "duplicate launch accepted");
    require(!controller.setTargetResolver({}), "running session accepted a different library resolver");
    controller.stop();
    require(phase(controller) == "stopping" && controller.busy(), "cancel did not return immediately in busy state");
    host.barrier.release();
    settled(controller);
    require(phase(controller) == "cancelled" && driver.starts == 0, "cancelled HTTP attempt started stream");
    require(host.launches == (path == "/launch" ? 1 : 0), "cancelled serverinfo still launched game");
    require(host.cancels == host.launches, "cancelled launch was not cleaned up exactly once");
}

void testConnectionCancellation() {
    Host host;
    Driver driver;
    host.driver = &driver;
    driver.blockStart = true;
    DeckNativeSessionController controller(true, host.resolver(), driver);
    require(controller.start("host", "game"), "start rejected");
    until([&]() { return driver.startBarrier.entered.load(); });
    controller.stop();
    settled(controller);
    require(driver.interrupts > 0 && driver.stops == 1 && host.cancels == 1, "pending connect was not interrupted and settled");
    require(phase(controller) == "cancelled", "pending connect cancellation reported failure");
    require(driver.startThread == driver.stopThread && driver.startThread != QCoreApplication::instance()->thread(), "stream teardown changed threads");
}

void testActiveStopRetryAndDisconnect() {
    Host host;
    Driver driver;
    host.driver = &driver;
    DeckNativeSessionController controller(true, host.resolver(), driver);
    for (int attempt = 1; attempt <= 3; ++attempt) {
        require(controller.start("host", "game"), "retry rejected after cleanup");
        until([&]() { return phase(controller) == "active"; });
        if (attempt == 2) driver.terminate();
        else { controller.stop(); controller.stop(); }
        settled(controller);
        require(phase(controller) == (attempt == 2 ? "interrupted" : "stopped"), "wrong terminal phase");
        require(driver.stops == attempt && host.cancels == (attempt == 1 ? 1 : attempt - 1), "stop or interruption changed host cleanup");
    }
}

void testFailures() {
    for (int scenario = 0; scenario < 5; ++scenario) {
        Host host;
        Driver driver;
        host.driver = &driver;
        host.failServerInfo = scenario == 0;
        host.refuseLaunch = scenario == 1;
        host.missingUrl = scenario == 2;
        driver.failStart = scenario == 3;
        host.refuseCancel = scenario == 4;
        DeckNativeSessionController controller(true, host.resolver(), driver);
        require(controller.start("host", "game"), "start rejected");
        if (scenario == 4) { until([&]() { return phase(controller) == "active"; }); controller.stop(); }
        settled(controller);
        require(host.cancels == (scenario >= 2 ? 1 : 0), "launch ownership lost on failure");
        if (scenario < 4) require(phase(controller) == "failed", "failed attempt reported success");
        else require(controller.state().value("copy").toString().contains("not confirmed"), "cleanup refusal hidden");
        const auto publicJson = QJsonDocument::fromVariant(controller.state()).toJson();
        require(!publicJson.contains("private-token") && !publicJson.contains("192.0.2.10"), "private session material leaked to UI");
    }
}

void testShutdownAndGlobalExclusion() {
    Host host;
    Driver driver;
    host.driver = &driver;
    {
        DeckNativeSessionController controller(true, host.resolver(), driver);
        require(controller.start("host", "game"), "start rejected");
        until([&]() { return phase(controller) == "active"; });
        Host otherHost;
        Driver otherDriver;
        DeckNativeSessionController other(true, otherHost.resolver(), otherDriver);
        require(other.start("host", "game"), "second controller did not report asynchronously");
        settled(other);
        require(phase(other) == "failed" && otherHost.requests == 0, "second controller launched overlapping host session");
    }
    require(driver.stops == 1 && host.cancels == 0, "closing an active stream ended the host game");
}

struct InputRecorder {
    Driver& driver;
    std::mutex mutex;
    std::vector<DeckControllerPacket> packets;
    bool blockA = false, failA = false;
    Barrier barrier;
    explicit InputRecorder(Driver& driver) : driver(driver) {}
    DeckControllerSend sender() {
        return [this](const DeckControllerPacket& packet) {
            require(QThread::currentThread() == driver.startThread, "input left the stream's owning worker");
            require(driver.starts > driver.stops, "input sent before start or after stream teardown");
            { const std::lock_guard lock(mutex); packets.push_back(packet); }
            if (packet.state.buttons == A_FLAG) {
                if (blockA) barrier.wait();
                if (failA) return -1;
            }
            return 0;
        };
    }
    std::vector<DeckControllerPacket> snapshot() { const std::lock_guard lock(mutex); return packets; }
    bool lastIs(DeckControllerPacket packet) {
        const auto seen = snapshot();
        return !seen.empty() && seen.back() == packet;
    }
};


void testHudObserverDoesNotBlockInput() {
    using namespace nova::deck::polaris;
    for (int scenario = 0; scenario < 4; ++scenario) {
        Host host; Driver driver; InputRecorder input(driver);
        host.appUuid = "private-game";
        std::atomic<int> reads{0}; std::atomic<bool> waiting{false}, cancelled{false}, eventsWaiting{false}, eventsCancelled{false};
        host.hostTelemetry = [&]() -> std::optional<DeckHudHostTarget> {
            return DeckHudHostTarget{[&](const std::function<bool()>& stop) {
                require(QThread::currentThread() != driver.startThread && QThread::currentThread() != QCoreApplication::instance()->thread(),
                    "host telemetry blocked input or GUI worker");
                if (++reads > 1) {
                    waiting = true;
                    while (!stop()) QThread::msleep(1);
                    cancelled = true;
                }
                DeckHostTelemetry sample;
                sample.active = sample.owned = sample.authorityValid = true;
                sample.role = "owner"; sample.gameId = 17; sample.gameUuid = "private-game"; sample.sessionToken = "private-token";
                sample.eventsHttpsPort = scenario == 3 ? 47990 : 0;
                sample.doctorAuthoritative = sample.doctorVerdictPresent = sample.doctorHealthy = true;
                return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Ok, 200, {}, sample};
            }, [] { return true; }, {}, {}, [&](int port, const auto&, const auto& stop) {
                require(port == 47990 && QThread::currentThread() != driver.startThread &&
                    QThread::currentThread() != QCoreApplication::instance()->thread(), "events blocked input or GUI worker");
                eventsWaiting = true;
                while (!stop()) QThread::msleep(1);
                eventsCancelled = true;
                return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout};
            }};
        };
        if (scenario == 1) host.launchToken.clear();
        if (scenario == 2) driver.failStart = true;
        DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
        controller.setInputFocus(true); controller.updateController({}, true);
        require(controller.start("host", "game"), "observer fixture start failed");
        if (scenario == 2) { settled(controller); require(reads == 0, "failed connection started host observer"); continue; }
        until([&] { return phase(controller) == "active"; });
        if (scenario == 0 || scenario == 3) {
            until([&] { return controller.hud().value("healthLabel") == "Stable"; });
            until([&] { return waiting.load(); });
            if (scenario == 3) until([&] { return eventsWaiting.load(); });
        }
        controller.resumeInput(); controller.updateController({.buttons = A_FLAG}, true);
        until([&] { return input.lastIs({{.buttons = A_FLAG}, true}); });
        require(scenario == 0 || scenario == 3 || reads == 0, "missing session token started host observer");
        QElapsedTimer elapsed; elapsed.start();
        // Disconnect avoids host cancellation when the fixture intentionally has no token.
        require(controller.disconnectFromHost(), "observer fixture disconnect rejected"); settled(controller);
        require(elapsed.elapsed() < 600 && (scenario != 0 || cancelled), "stream stop waited on blocked telemetry request");
        require(scenario != 3 || eventsCancelled, "stream stop did not cancel SSE");
        require(controller.hud() == DeckHudMetrics::empty(), "observer data survived stream stop");
        const int stoppedReads = reads.load();
        if (scenario == 0) {
            host.hostTelemetry = {};
            require(controller.start("host", "game"), "replacement stream failed");
            until([&] { return phase(controller) == "active"; });
            require(!controller.hud().value("hostFresh").toBool() && reads == stoppedReads, "old observer published into replacement stream");
            controller.stop(); settled(controller);
        }
    }
}


void testDiagnosticsRefreshBoundary() {
    using namespace nova::deck::polaris;
    Host host; Driver driver; InputRecorder input(driver);
    host.appUuid = "private-game";
    std::atomic<int> reads{0}; std::atomic<bool> waiting{false}, cancelled{false};
    host.hostTelemetry = [&]() -> std::optional<DeckHudHostTarget> {
        return DeckHudHostTarget{[&](const auto& stop) {
            if (++reads > 1) { waiting = true; while (!stop()) QThread::msleep(1); cancelled = true; }
            DeckHostTelemetry sample; sample.active = sample.owned = sample.authorityValid = true;
            sample.role = "owner"; sample.gameId = 17; sample.gameUuid = "private-game"; sample.sessionToken = "private-token";
            return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Ok, 200, {}, sample};
        }, [] { return true; }};
    };
    DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
    require(!controller.refreshDiagnostics() && !controller.exportSupportReport().value("saved").toBool(), "idle diagnostics operation was allowed");
    controller.setInputFocus(true); controller.updateController({}, true);
    require(controller.start("host", "game"), "diagnostics fixture did not start");
    until([&] { return phase(controller) == "active" && controller.hud().value("canRefreshDiagnostics").toBool(); });
    controller.setInputFocus(false); require(!controller.refreshDiagnostics() && !controller.exportSupportReport().value("saved").toBool(), "unfocused diagnostics operation allowed");
    controller.setInputFocus(true); controller.resumeInput(); require(!controller.refreshDiagnostics() && !controller.exportSupportReport().value("saved").toBool(), "hidden diagnostics operation allowed");
    controller.showControls();
    const auto report=controller.exportSupportReport();
    require(report.value("saved").toBool() && QFile::exists(deckSupportReportDirectory()+"/"+report.value("fileName").toString()), "active controller did not export locally");
    require(controller.refreshDiagnostics() && !controller.refreshDiagnostics(), "refresh failed or duplicated");
    until([&] { return waiting.load(); });
    controller.resumeInput(); controller.updateController({.buttons = A_FLAG}, true);
    until([&] { return input.lastIs({{.buttons = A_FLAG}, true}); });
    QElapsedTimer elapsed; elapsed.start(); controller.stop(); settled(controller);
    require(cancelled && elapsed.elapsed() < 600 && !controller.refreshDiagnostics() && controller.hud() == DeckHudMetrics::empty(),
        "pending diagnostics blocked teardown or survived stop");
}

void testDoctorActionBoundary() {
    using namespace nova::deck::polaris;
    Host host; Driver driver; InputRecorder input(driver); host.appUuid = "private-game";
    std::atomic<int> reads{0}, writes{0}; std::atomic<bool> waiting{false}, cancelled{false};
    host.hostTelemetry = [&]() -> std::optional<DeckHudHostTarget> {
        DeckHudHostTarget target;
        target.fetch = [&](const auto&) {
            auto sample = parseHostTelemetry(QJsonDocument(doctor_fixture::envelope(++reads)).toJson().toStdString());
            return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Ok, 200, {}, sample};
        };
        target.identityValid = [] { return true; };
        target.setEnabled = [](bool, const auto&, const auto&) { return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Ok, 200, {}, true}; };
        target.doctorAction = [&](const auto&, const auto& stop) {
            ++writes; waiting = true; while (!stop()) QThread::msleep(1); cancelled = true;
            return DeckPolarisResult<DeckDoctorReceipt>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
        };
        return target;
    };
    DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
    require(!controller.applyDoctorFix() && !controller.undoDoctorFix() && !controller.checkDoctorResult(), "idle Doctor action allowed");
    controller.setInputFocus(true); controller.updateController({}, true);
    require(controller.start("host", "game"), "Doctor fixture did not start");
    until([&] { return phase(controller) == "active" && controller.hud().value("doctorCanApply").toBool(); });
    controller.setInputFocus(false); require(!controller.applyDoctorFix(), "unfocused Doctor action allowed");
    controller.setInputFocus(true); controller.resumeInput(); require(!controller.applyDoctorFix(), "hidden Doctor action allowed");
    controller.showControls(); require(controller.applyDoctorFix() && !controller.applyDoctorFix(), "Doctor action failed or duplicated");
    until([&] { return waiting.load(); });
    controller.resumeInput(); controller.updateController({.buttons = A_FLAG}, true);
    until([&] { return input.lastIs({{.buttons = A_FLAG}, true}); });
    QElapsedTimer elapsed; elapsed.start(); controller.stop(); settled(controller);
    require(writes == 1 && cancelled && elapsed.elapsed() < 600 && !controller.applyDoctorFix() && !controller.undoDoctorFix() &&
        !controller.checkDoctorResult() && controller.hud() == DeckHudMetrics::empty(), "Doctor action outlived stream or blocked teardown");
}

void testLiveTuningSessionBoundary() {
    using namespace nova::deck::polaris;
    Host host; Driver driver; InputRecorder input(driver);
    host.appUuid = "private-game";
    std::atomic<int> reads{0}, writes{0}; std::atomic<bool> entered{false}, cancelled{false};
    host.hostTelemetry = [&]() -> std::optional<DeckHudHostTarget> {
        return DeckHudHostTarget{[&](const std::function<bool()>&) {
            DeckHostTelemetry sample;
            sample.active = sample.owned = sample.authorityValid = sample.hostTuningAllowed = true;
            sample.role = "owner"; sample.gameId = 17; sample.gameUuid = "private-game"; sample.sessionToken = "private-token";
            sample.generation = 41; sample.appSession = "fixture-session"; sample.livePresent = true;
            sample.live = DeckLiveTuningTelemetry{false, true, "off", QString(64, 'a'), "instance", "fixture-session", ++reads, 41, 20000, 0, 20000};
            return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Ok, 200, {}, sample};
        }, [] { return true; }, [&](bool enabled, const DeckLiveTuningTelemetry&, const std::function<bool()>& stop) {
            require(enabled && QThread::currentThread() != driver.startThread, "tuning save blocked input worker");
            ++writes; entered = true; while (!stop()) QThread::msleep(1); cancelled = true;
            return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
        }};
    };
    DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
    require(!controller.setLiveTuningEnabled(true), "idle session allowed tuning save");
    controller.setInputFocus(true); controller.updateController({}, true);
    require(controller.start("host", "game"), "tuning stream did not start");
    until([&] { return controller.hud().value("canTune").toBool(); });
    controller.resumeInput();
    require(!controller.setLiveTuningEnabled(true), "hidden Command Center allowed save");
    controller.showControls(); controller.setInputFocus(false);
    require(!controller.setLiveTuningEnabled(true), "unfocused window allowed save");
    controller.setInputFocus(true);
    require(controller.setLiveTuningEnabled(true), "active tuning save rejected");
    require(!controller.setLiveTuningEnabled(true), "double activation queued another save");
    until([&] { return entered.load(); });
    controller.resumeInput(); controller.updateController({.buttons = A_FLAG}, true);
    until([&] { return input.lastIs({{.buttons = A_FLAG}, true}); });
    QElapsedTimer elapsed; elapsed.start(); controller.stop(); settled(controller);
    require(cancelled && writes == 1 && elapsed.elapsed() < 600 && controller.hud() == DeckHudMetrics::empty(), "stop retained or replayed pending save");
    require(!controller.setLiveTuningEnabled(true), "stopped session allowed mutation");
}

void testFixedBitrateSessionBoundary() {
    using namespace nova::deck::polaris;
    Host host; Driver driver; InputRecorder input(driver);
    host.appUuid = "private-game";
    std::atomic<int> reads{0}, writes{0}; std::atomic<bool> entered{false}, cancelled{false};
    host.hostTelemetry = [&]() -> std::optional<DeckHudHostTarget> {
        return DeckHudHostTarget{[&](const std::function<bool()>&) {
            DeckHostTelemetry sample;
            sample.active = sample.owned = sample.authorityValid = sample.hostTuningAllowed = true;
            sample.role = "owner"; sample.gameId = 17; sample.gameUuid = "private-game"; sample.sessionToken = "private-token";
            sample.generation = 41; sample.appSession = "fixture-session"; sample.livePresent = true;
            sample.live = DeckLiveTuningTelemetry{false, true, "off", QString(64, 'a'), "instance", "fixture-session", ++reads, 41, 20000, 0, 20000};
            return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Ok, 200, {}, sample};
        }, [] { return true; }, {}, [&](int kbps, const DeckLiveTuningTelemetry&, const std::function<bool()>& stop) {
            require(kbps == 15000 && QThread::currentThread() != driver.startThread, "tuning save blocked input worker");
            ++writes; entered = true; while (!stop()) QThread::msleep(1); cancelled = true;
            return DeckPolarisResult<bool>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
        }};
    };
    DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
    require(!controller.setFixedBitrate(15000), "idle session allowed tuning save");
    controller.setInputFocus(true); controller.updateController({}, true);
    require(controller.start("host", "game"), "tuning stream did not start");
    until([&] { return controller.hud().value("canSetBitrate").toBool(); });
    controller.resumeInput();
    require(!controller.setFixedBitrate(15000), "hidden Command Center allowed save");
    controller.showControls(); controller.setInputFocus(false);
    require(!controller.setFixedBitrate(15000), "unfocused window allowed save");
    controller.setInputFocus(true);
    require(controller.setFixedBitrate(15000), "active tuning save rejected");
    require(!controller.setFixedBitrate(15000), "double activation queued another save");
    until([&] { return entered.load(); });
    controller.resumeInput(); controller.updateController({.buttons = A_FLAG}, true);
    until([&] { return input.lastIs({{.buttons = A_FLAG}, true}); });
    QElapsedTimer elapsed; elapsed.start(); controller.stop(); settled(controller);
    require(cancelled && writes == 1 && elapsed.elapsed() < 600 && controller.hud() == DeckHudMetrics::empty(), "stop retained or replayed pending save");
    require(!controller.setFixedBitrate(15000), "stopped session allowed mutation");
}

void testSyncProfileSessionBoundary() {
    using namespace nova::deck::polaris;
    Host host; Driver driver; InputRecorder input(driver);
    host.appUuid = "private-game";
    DeckHostSettings profile; profile.revision = "1"; profile.displayOverride = profile.bitrateOverride = true;
    profile.desiredDisplay = "1280x800x60"; profile.desiredBitrate = 20000;
    const auto review = profile.profileReview();
    std::atomic<int> reads{0}, writes{0}; std::atomic<bool> entered{false}, cancelled{false};
    host.hostTelemetry = [&]() -> std::optional<DeckHudHostTarget> {
        return DeckHudHostTarget{[&](const std::function<bool()>&) {
            DeckHostTelemetry sample;
            sample.active = sample.owned = sample.authorityValid = sample.hostTuningAllowed = true;
            sample.role = "owner"; sample.gameId = 17; sample.gameUuid = "private-game"; sample.sessionToken = "private-token";
            sample.generation = 41; sample.appSession = "fixture-session"; sample.livePresent = true;
            sample.live = DeckLiveTuningTelemetry{false, true, "off", QString(64, 'a'), "instance", "fixture-session", ++reads, 41, 20000, 0, 20000};
            return DeckPolarisResult<DeckHostTelemetry>{DeckPolarisRequestStatus::Ok, 200, {}, sample};
        }, [] { return true; }, [](bool, const auto&, const auto&) { return DeckPolarisResult<bool>{}; },
        [](int, const auto&, const auto&) { return DeckPolarisResult<bool>{}; }, {}, {}, {},
        [&](const auto&) { return DeckPolarisResult<DeckHostSettings>{DeckPolarisRequestStatus::Ok,200,{},profile}; },
        [&](const QString& display, int kbps, bool clear, const DeckLiveTuningTelemetry&, const std::function<bool()>& stop) {
            require(display == "1920x1080x60" && !clear && kbps == 15000 && QThread::currentThread() != driver.startThread, "profile save blocked input worker");
            ++writes; entered = true; while (!stop()) QThread::msleep(1); cancelled = true;
            return DeckPolarisResult<DeckHostSettings>{DeckPolarisRequestStatus::Timeout, 0, {}, {}};
        }};
    };
    DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
    require(!controller.setSyncProfile("host", "1920x1080x60", 15000, false, review), "idle session allowed profile save");
    controller.setInputFocus(true); controller.updateController({}, true);
    require(controller.start("host", "game"), "profile stream did not start");
    until([&] { return controller.hud().value("canSyncProfile").toBool(); });
    require(!controller.setSyncProfile("different-host", "1920x1080x60", 15000, false, review), "different PC review accepted");
    controller.resumeInput();
    require(!controller.setSyncProfile("host", "1920x1080x60", 15000, false, review), "hidden Command Center allowed save");
    controller.showControls(); controller.setInputFocus(false);
    require(!controller.setSyncProfile("host", "1920x1080x60", 15000, false, review), "unfocused window allowed save");
    controller.setInputFocus(true);
    require(controller.setSyncProfile("host", "1920x1080x60", 15000, false, review), "active profile save rejected");
    require(!controller.setSyncProfile("host", "1920x1080x60", 15000, false, review), "double activation queued another save");
    until([&] { return entered.load(); });
    controller.resumeInput(); controller.updateController({.buttons = A_FLAG}, true);
    until([&] { return input.lastIs({{.buttons = A_FLAG}, true}); });
    QElapsedTimer elapsed; elapsed.start(); controller.stop(); settled(controller);
    require(cancelled && writes == 1 && elapsed.elapsed() < 600 && controller.hud() == DeckHudMetrics::empty(), "stop retained or replayed pending save");
    require(!controller.setSyncProfile("host", "1920x1080x60", 15000, false, review), "stopped session allowed mutation");
}

void testDisconnectAndExitChoice() {
    for (int scenario = 0; scenario < 3; ++scenario) {
        Host host;
        Driver driver;
        host.driver = &driver;
        InputRecorder input(driver);
        DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
        require(!controller.disconnectFromHost(), "idle disconnect was accepted");
        controller.setInputFocus(true);
        controller.updateController({}, true);
        require(controller.start("host", "game"), "disconnect fixture did not start");
        until([&] { return phase(controller) == "active"; });
        require(controller.state().value("canDisconnect").toBool(), "ordinary stream cannot disconnect");
        controller.resumeInput();
        controller.updateController({.buttons = A_FLAG, .leftTrigger = 255}, true);
        until([&] { return input.lastIs({{.buttons = A_FLAG, .leftTrigger = 255}, true}); });
        driver.blockStop = true;
        if (scenario == 0) require(controller.disconnectFromHost(), "active disconnect refused");
        if (scenario == 1) controller.closeSession();
        if (scenario == 2) controller.stop();
        until([&] { return driver.stopBarrier.entered.load(); });
        require(!controller.start("host", "game"), "new stream raced unfinished teardown");
        require(!controller.disconnectFromHost(), "repeat exit changed pending disposition");
        controller.stop();
        controller.closeSession();
        require(input.lastIs({{}, false}), "exit left buttons/triggers held on host");
        require(host.cancels == 0, "host quit ran before stream teardown completed");
        driver.stopBarrier.release();
        settled(controller);
        require(phase(controller) == (scenario == 2 ? "stopped" : "disconnected"), "wrong exit result");
        require(host.cancels == (scenario == 2 ? 1 : 0), "exit choice changed under repeat/close");
        require(driver.stops == 1 && driver.stopThread == driver.startThread, "exit changed transport ownership");
        require(!controller.state().value("canDisconnect").toBool(), "finished stream still offers disconnect");
        // A disconnected prior session must not suppress the next launch's cleanup.
        driver.blockStop = false;
        require(controller.start("host", "game"), "next launch retained disconnected worker");
        until([&] { return phase(controller) == "active"; });
        controller.stop();
        settled(controller);
        require(host.cancels == (scenario == 2 ? 2 : 1) && driver.stops == 2,
            "disconnect disposition leaked into next session");
    }
    {
        Host host;
        Driver driver;
        host.driver = &driver;
        host.blockPath = "/launch";
        DeckNativeSessionController controller(true, host.resolver(), driver);
        require(controller.start("host", "game"), "pending fixture did not start");
        until([&] { return host.barrier.entered.load(); });
        require(!controller.disconnectFromHost(), "pending launch accepted keep-running disposition");
        controller.closeSession();
        host.barrier.release();
        settled(controller);
        require(host.cancels == 1 && driver.starts == 0, "close leaked a late successful launch");
    }
    {
        Host host;
        Driver driver;
        host.driver = &driver;
        host.expectedGame = "space.fixture";
        DeckNativeSessionController controller(true, host.resolver(), driver);
        require(controller.start("host", host.expectedGame), "Space fixture did not start");
        until([&] { return phase(controller) == "active"; });
        require(!controller.state().value("canDisconnect").toBool() && !controller.disconnectFromHost(),
            "ordinary disconnect was incorrectly offered for a Space session");
        controller.closeSession();
        settled(controller);
        require(host.cancels == 1 && driver.stops == 1, "Space close left its session running");
    }
}

std::string ownedRunningGame(const std::string& app = "17", const std::string& token = "private-token",
    const std::string& owned = "1") {
    return "<root status_code=\"200\"><appversion>7.1</appversion><PairStatus>1</PairStatus><currentgame>" + app +
        "</currentgame><currentgameuuid>game</currentgameuuid><currentgameowned>" + owned +
        "</currentgameowned><currentgamesessiontoken>" + token + "</currentgamesessiontoken></root>";
}

void testDisconnectedResume() {
    Host host;
    host.appUuid = "game";
    Driver driver;
    host.driver = &driver;
    InputRecorder input(driver);
    DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
    controller.setInputFocus(true);
    controller.updateController({}, true);
    require(!controller.resumeDisconnected("host", "game"), "resume without a ticket was accepted");
    DeckPlayConfiguration configuration{1920, 1080, 30, 30000, "positions"};
    require(controller.startConfigured("host", "game", configuration.toMap()), "resume fixture did not launch");
    until([&] { return phase(controller) == "active"; });
    controller.resumeInput();
    controller.updateController({.buttons = A_FLAG}, true);
    until([&] { return input.lastIs({{.buttons = B_FLAG}, true}); });
    require(controller.disconnectFromHost(), "could not disconnect resume fixture");
    settled(controller);
    require(controller.state().value("canResume").toBool(), "successful detach has no resume action");
    require(host.cancels == 0 && driver.stops == 1, "detach ended game before resume");
    const auto publicState = QJsonDocument::fromVariant(controller.state()).toJson();
    require(!publicState.contains("private-token") && !publicState.contains("192.0.2.10"), "resume ticket leaked to UI");
    const int before = host.requests;
    require(!controller.resumeDisconnected("other", "game") && !controller.resumeDisconnected("host", "other") && host.requests == before,
        "resume ticket crossed host/game boundaries");
    host.serverInfoOverride = ownedRunningGame();
    require(controller.resumeDisconnected("host", "game"), "valid resume rejected");
    require(!controller.resumeDisconnected("host", "game"), "double Resume started a second connection");
    until([&] { return phase(controller) == "active"; });
    require(host.launches == 1 && host.resumes == 1 && host.cancels == 0, "resume launched/replaced/quit a game");
    require(host.resumeRequest.starts_with("/resume?") && host.resumeRequest.find("sessiontoken=private-token") != std::string::npos,
        "resume did not name its exact session");
    require(driver.receivedConfiguration.width == 1920 && driver.receivedConfiguration.fps == 30 &&
        driver.receivedConfiguration.bitrate == 30000, "resume lost reviewed stream settings");
    controller.resumeInput();
    require(controller.controllerHint().contains("Release"), "resume replayed a held game button");
    controller.updateController({}, true);
    until([&] { return input.lastIs({{}, true}); });
    controller.updateController({.buttons = A_FLAG}, true);
    until([&] { return input.lastIs({{.buttons = B_FLAG}, true}); });
    controller.stop();
    settled(controller);
    require(host.cancels == 1 && driver.stops == 2 && input.lastIs({{}, false}), "explicit End after resume lost cleanup/input release");
    require(!controller.state().value("canResume").toBool() && !controller.resumeDisconnected("host", "game"), "End retained a stale resume ticket");

    // After reopening Nova, ordinary Play still rechecks the host snapshot and
    // resumes the owned matching game without a local ticket or new launch.
    require(controller.startConfigured("host", "game", configuration.toMap()), "fresh Play rejected");
    until([&] { return phase(controller) == "active"; });
    require(host.launches == 1 && host.resumes == 2, "fresh Play ignored owned running game");
    driver.terminate();
    settled(controller);
    require(phase(controller) == "interrupted" && controller.state().value("canReconnect").toBool() && host.cancels == 1 && driver.stops == 3,
        "resumed connection failure ended the existing game");
}

void testInterruptedRecovery() {
    Host host;
    host.appUuid = "game";
    Driver driver;
    host.driver = &driver;
    InputRecorder input(driver);
    DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
    controller.setInputFocus(true);
    controller.updateController({}, true);
    const DeckPlayConfiguration config{1920, 1080, 30, 30000, "positions"};
    require(!controller.reconnect("host", "game"), "idle reconnect reached a host");
    require(controller.startConfigured("host", "game", config.toMap()), "recovery fixture did not start");
    until([&] { return phase(controller) == "active"; });
    controller.resumeInput();
    controller.updateController({.buttons = A_FLAG, .leftTrigger = 255}, true);
    until([&] { return input.lastIs({{.buttons = B_FLAG, .leftTrigger = 255}, true}); });
    driver.blockStop = true;
    driver.terminate();
    until([&] { return driver.stopBarrier.entered.load(); });
    require(!controller.reconnect("host", "game") && !controller.state().value("canReconnect").toBool(),
        "reconnect raced old transport teardown");
    controller.stop();
    controller.closeSession();
    require(!controller.disconnectFromHost(), "teardown accepted a changed exit choice");
    require(input.lastIs({{}, false}) && host.cancels == 0, "interruption did not release controls before transport stop");
    driver.stopBarrier.release();
    settled(controller);
    driver.blockStop = false;
    require(phase(controller) == "interrupted" && controller.state().value("canReconnect").toBool() &&
        !controller.state().value("canResume").toBool(), "interruption lost its dedicated recovery action");
    const auto publicJson = QJsonDocument::fromVariant(controller.state()).toJson();
    require(!publicJson.contains("private-token") && !publicJson.contains("192.0.2.10"), "recovery leaked a backend ticket");
    require(!controller.reconnect("other", "game") && !controller.reconnect("host", "other") &&
        !controller.resumeDisconnected("host", "game"), "recovery ticket crossed host/game/exit boundaries");
    require(host.launches == 1 && host.resumes == 0 && host.cancels == 0, "interruption performed an automatic host action");

    host.serverInfoOverride = ownedRunningGame();
    driver.failStart = true;
    require(controller.reconnect("host", "game"), "manual reconnect was refused");
    require(!controller.reconnect("host", "game"), "double reconnect started two workers");
    settled(controller);
    require(phase(controller) == "interrupted" && controller.state().value("canReconnect").toBool(),
        "transport failure stranded a verified retry");
    require(host.launches == 1 && host.resumes == 1 && host.cancels == 0 && driver.stops == 2,
        "failed reconnect replaced/ended a game or leaked its driver");
    driver.failStart = false;
    require(controller.reconnect("host", "game"), "second manual reconnect was refused");
    until([&] { return phase(controller) == "active"; });
    require(controller.controlsVisible() && driver.receivedConfiguration.width == 1920 &&
        driver.receivedConfiguration.height == 1080 && driver.receivedConfiguration.fps == 30 &&
        driver.receivedConfiguration.bitrate == 30000, "reconnect lost configuration or auto-captured input");
    controller.resumeInput();
    require(controller.controllerHint().contains("Release"), "reconnect replayed held controls");
    controller.updateController({}, true);
    until([&] { return input.lastIs({{}, true}); });
    controller.updateController({.buttons = A_FLAG}, true);
    until([&] { return input.lastIs({{.buttons = B_FLAG}, true}); });
    require(host.resumes == 2 && host.launches == 1 && host.cancels == 0, "recovery did not reuse the exact game");
    driver.terminate();
    settled(controller);
    host.serverInfoOverride = ownedRunningGame("17", "changed-token");
    require(controller.reconnect("host", "game"), "changed-token check did not run asynchronously");
    settled(controller);
    require(phase(controller) == "failed" && !controller.state().value("canReconnect").toBool() &&
        !controller.reconnect("host", "game"), "changed session retained a recovery ticket");
    require(host.resumes == 2 && host.launches == 1 && host.cancels == 0, "stale reconnect mutated the new session");
}

void testInterruptionScopeAndGracefulEnd() {
    for (int scenario = 0; scenario < 3; ++scenario) {
        Host host;
        Driver driver;
        host.driver = &driver;
        if (scenario == 1) host.launchToken.clear();
        if (scenario == 2) host.expectedGame = "space.fixture";
        DeckNativeSessionController controller(true, host.resolver(), driver);
        require(controller.start("host", host.expectedGame), "termination fixture did not start");
        until([&] { return phase(controller) == "active"; });
        driver.terminate(scenario == 0 ? ML_ERROR_GRACEFUL_TERMINATION : -7);
        settled(controller);
        require(driver.stops == 1 && host.cancels == (scenario == 2 ? 1 : 0),
            "ordinary termination sent Quit or Space lost its cleanup");
        require(phase(controller) == (scenario == 0 ? "stopped" : scenario == 1 ? "interrupted" : "failed"),
            "wrong graceful/legacy/Space termination state");
        require(!controller.state().value("canReconnect").toBool() && !controller.reconnect("host", host.expectedGame),
            "graceful/legacy/Space termination offered unsupported reconnect");
    }
}

void testResumeFailuresAndCancellation() {
    for (const bool interrupted : {false, true}) {
        for (int scenario = 0; scenario < 11; ++scenario) {
            Host host;
            Driver driver;
            host.driver = &driver;
            host.appUuid = "game";
            DeckNativeSessionController controller(true, host.resolver(), driver);
            require(controller.start("host", "game"), "fixture launch rejected");
            until([&] { return phase(controller) == "active"; });
            if (interrupted) driver.terminate();
            else require(controller.disconnectFromHost(), "fixture detach rejected");
            settled(controller);
            host.serverInfoOverride = ownedRunningGame();
            if (scenario == 0) host.serverInfoOverride = "<root status_code=\"200\"><appversion>7.1</appversion><currentgame>0</currentgame></root>";
            if (scenario == 1) host.serverInfoOverride = ownedRunningGame("19");
            if (scenario == 2) host.serverInfoOverride = ownedRunningGame("17", "replacement-token");
            if (scenario == 3) host.serverInfoOverride = ownedRunningGame("17", "private-token", "0");
            if (scenario == 4) host.rejectResolve = true;
            if (scenario == 5) host.resumeResponse = "<root status_code=\"470\"><resume>0</resume></root>";
            if (scenario == 6) host.resumeResponse = "<root status_code=\"200\"><resume>1</resume><sessionToken>private-token</sessionToken></root>";
            if (scenario == 7) driver.failStart = true;
            if (scenario == 8) host.blockPath = "/resume";
            if (scenario == 9) driver.blockStart = true;
            if (scenario == 10) host.appId = 19;
            require(interrupted ? controller.reconnect("host", "game") : controller.resumeDisconnected("host", "game"),
                "resume/reconnect should resolve asynchronously");
            if (scenario == 8) {
                until([&] { return host.barrier.entered.load(); });
                controller.stop();
                host.barrier.release();
            }
            if (scenario == 9) {
                until([&] { return driver.startBarrier.entered.load(); });
                controller.closeSession();
            }
            settled(controller);
            require(host.launches == 1 && host.cancels == 0, "failed/cancelled resume launched or ended a game");
            require(host.resumes == (scenario >= 5 && scenario <= 9 ? 1 : 0), "rejected session identity reached resume endpoint");
            require(driver.stops == driver.starts, "resume failure leaked transport");
            require(phase(controller) == (scenario == 8 || scenario == 9 ? "cancelled" : scenario == 7 ? "interrupted" : "failed"), "wrong resume failure phase");
            const auto state = QJsonDocument::fromVariant(controller.state()).toJson();
            require(!state.contains("private-token") && !state.contains("replacement-token"), "resume error leaked session token");
            require(!controller.resumeDisconnected("host", "game"), "failed resume retained stale direct retry");
            require(controller.state().value("canReconnect").toBool() == (scenario == 7), "unsafe failure kept reconnect authority");
        }
    }
}

void testConfiguredFaceButtons() {
    DeckPlaySettings settings;
    for (const auto& choice : {QString("default"), QString("labels"), QString("positions"), QString("legacy")}) {
        require(settings.setDefaultFaceButtonLayout(choice == "positions" ? "labels" : "positions"), "cannot set device layout");
        Host host;
        Driver driver;
        InputRecorder input(driver);
        DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
        controller.setInputFocus(true);
        controller.updateController({.buttons = A_FLAG}, true);
        auto values = DeckPlayConfiguration{}.toMap();
        if (choice == "legacy") values.remove("faceButtonLayout");
        else values["faceButtonLayout"] = choice;
        const bool swapped = choice != "labels";
        require(controller.startConfigured("host", "game", values), "face-button review rejected");
        // Both the inherited default and caller's map are immutable for this run.
        require(settings.setDefaultFaceButtonLayout("labels"), "cannot change next-launch default");
        values["faceButtonLayout"] = "labels";
        require(!controller.startConfigured("host", "game", values), "busy stream accepted a new layout");
        until([&]() { return phase(controller) == "active"; });
        controller.resumeInput();
        require(controller.controllerHint().contains("Release"), "layout override bypassed held-start guard");
        controller.updateController({}, true);
        until([&]() { return input.lastIs({{}, true}); });
        const unsigned physical[] = {A_FLAG, B_FLAG, X_FLAG, Y_FLAG};
        const unsigned expected[] = {B_FLAG, A_FLAG, Y_FLAG, X_FLAG};
        for (unsigned i = 0; i < 4; ++i) {
            controller.updateController({.buttons = physical[i], .leftTrigger = 155, .rightX = 12000}, true);
            until([&]() { return input.lastIs({{.buttons = swapped ? expected[i] : physical[i], .leftTrigger = 155, .rightX = 12000}, true}); });
            controller.updateController({}, true);
            until([&]() { return input.lastIs({{}, true}); });
        }
        controller.updateController({.buttons = A_FLAG}, true);
        until([&]() { return input.lastIs({{.buttons = swapped ? B_FLAG : A_FLAG}, true}); });
        controller.setInputFocus(false);
        until([&]() { return input.lastIs({{}, true}); });
        controller.setInputFocus(true);
        controller.resumeInput();
        require(controller.controllerHint().contains("Release"), "layout bypassed focus-return neutral guard");
        controller.updateController({}, true);
        until([&]() { return input.lastIs({{}, true}); });
        controller.updateController({.buttons = BACK_FLAG}, true);
        controller.updateController({.buttons = BACK_FLAG | PLAY_FLAG}, true);
        until([&]() { return controller.controlsVisible() && input.lastIs({{}, true}); });
        for (const auto& packet : input.snapshot())
            require(!(packet.state.buttons & (BACK_FLAG | PLAY_FLAG)), "layout mapping leaked overlay chord");
        controller.stop();
        settled(controller);
        require(input.lastIs({{}, false}), "layout lost stop release");
        require(controller.startConfigured("host", "game", DeckPlayConfiguration{}.toMap()), "next launch refused");
        until([&]() { return phase(controller) == "active"; });
        controller.updateController({}, true);
        controller.resumeInput();
        until([&]() { return input.lastIs({{}, true}); });
        controller.updateController({.buttons = A_FLAG}, true);
        until([&]() { return input.lastIs({{.buttons = A_FLAG}, true}); });
        controller.updateController({}, false);
        until([&]() { return input.lastIs({{}, false}); });
        controller.stop();
        settled(controller);
        require(host.cancels == 2 && driver.stops == 2, "layout changes broke teardown or next-session isolation");
    }
}

void testReconnectPolicy() {
    DeckReconnectPolicy policy;
    for (const int delay : {0, 1000, 3000, 7000}) require(policy.nextDelay() == delay, "Android retry schedule changed");
    require(!policy.nextDelay(), "retry budget is unbounded");
    policy.beginStream(10, 0);
    policy.sample(11, 1000);
    policy.sample(11, 20000);
    require(!policy.nextDelay(), "one frame or a long scheduling gap reset recovery");
    policy.beginStream(0, 20000);
    for (int second = 1; second <= 15; ++second) policy.sample(second, 20000 + second * 1000);
    require(!policy.nextDelay(), "recovery budget reset before 15 seconds of progress");
    policy.sample(16, 36000);
    require(policy.nextDelay() == 0, "stable composition did not replenish retry budget");
    policy.beginStream(0, 37000);
    for (int second = 1; second <= 10; ++second) policy.sample(second, 37000 + second * 1000);
    policy.sample(10, 48000); // stalled video restarts the stability window
    for (int second = 1; second <= 14; ++second) policy.sample(10 + second, 48000 + second * 1000);
    require(policy.attempts() == 1, "stalled video earned a fresh budget");
    policy.beginStream(0, 63000); // a relaunch never replenishes attempts by itself
    require(policy.attempts() == 1, "a new connection reset the retry budget");
}

void testAutomaticReconnectBudget() {
    Host host;
    Driver driver;
    host.driver = &driver;
    host.appUuid = "game";
    host.automaticReconnect = true;
    DeckNativeSessionController controller(true, host.resolver(), driver);
    controller.setInputFocus(true);
    require(controller.startConfigured("host", "game", DeckPlayConfiguration{1920, 1080, 30, 30000}.toMap()), "automatic fixture failed");
    until([&] { return phase(controller) == "active"; });
    host.serverInfoOverride = ownedRunningGame();
    for (int attempt = 1; attempt <= 4; ++attempt) {
        driver.terminate();
        until([&] { return driver.starts == attempt + 1 && phase(controller) == "active"; }, 12000);
        require(controller.controlsVisible() && host.launches == 1 && host.resumes == attempt && host.cancels == 0,
            "automatic reconnect lost input handoff or mutated the host game");
        require(driver.receivedConfiguration.width == 1920 && driver.receivedConfiguration.bitrate == 30000,
            "automatic reconnect lost stream settings");
    }
    driver.terminate();
    settled(controller);
    require(phase(controller) == "interrupted" && controller.state().value("canReconnect").toBool() &&
        controller.state().value("copy").toString().contains("four attempts"), "unstable reconnects escaped their shared budget");
    require(driver.starts == 5 && driver.stops == 5 && host.resumes == 4 && host.cancels == 0,
        "exhausted budget launched another automatic connection");
    require(controller.reconnect("host", "game"), "exhausted budget lost manual recovery");
    until([&] { return phase(controller) == "active"; });
    driver.terminate();
    until([&] { return driver.starts == 7 && phase(controller) == "active"; });
    controller.stop();
    settled(controller);
    require(host.cancels == 1 && driver.starts == driver.stops, "manual reset or End game after auto recovery broke cleanup");
}

void testAutomaticReconnectCancellation() {
    for (int scenario = 0; scenario < 4; ++scenario) {
        Host host;
        Driver driver;
        host.driver = &driver;
        host.automaticReconnect = true;
        host.appUuid = "game";
        auto controller = std::make_unique<DeckNativeSessionController>(true, host.resolver(), driver);
        controller->setInputFocus(true);
        require(controller->start("host", "game"), "cancel fixture failed");
        until([&] { return phase(*controller) == "active"; });
        host.serverInfoOverride = ownedRunningGame();
        driver.terminate();
        until([&] { return driver.starts == 2 && phase(*controller) == "active"; });
        driver.terminate();
        until([&] { return phase(*controller) == "reconnecting" && controller->state().value("reconnectAttempt") == 2; });
        require(controller->busy() && !controller->start("host", "game") && !controller->setTargetResolver({}),
            "backoff released session exclusion");
        if (scenario == 0) controller->stop();
        if (scenario == 1) controller->closeSession();
        if (scenario == 2) controller->setInputFocus(false);
        if (scenario == 3) controller.reset();
        if (controller) require(!controller->busy() && phase(*controller) == "interrupted" &&
            controller->state().value("canReconnect").toBool(), "cancel backoff lost manual recovery");
        QElapsedTimer elapsed; elapsed.start();
        until([&] { return elapsed.elapsed() > 1100; });
        require(host.resumes == 1 && host.cancels == 0 && driver.starts == 2 && driver.stops == 2,
            "cancelled/destroyed recovery performed a late host action");
    }
}

void testAutomaticReconnectRefusals() {
    for (int scenario = 0; scenario < 9; ++scenario) {
        Host host;
        Driver driver;
        host.driver = &driver;
        host.automaticReconnect = true;
        host.appUuid = "game";
        DeckNativeSessionController controller(true, host.resolver(), driver);
        controller.setInputFocus(true);
        require(controller.start("host", "game"), "refusal fixture failed");
        until([&] { return phase(controller) == "active"; });
        host.serverInfoOverride = ownedRunningGame();
        if (scenario == 0) host.serverInfoOverride = ownedRunningGame("17", "changed-token");
        if (scenario == 1) host.rejectResolve = true;
        if (scenario == 2) host.failServerInfo = true; // unclassified failure is not retryable
        if (scenario == 3) controller.setInputFocus(false);
        if (scenario == 4) host.serverInfoOverride = ownedRunningGame("17", "private-token", "0");
        if (scenario == 5) host.automaticReconnect = false;
        driver.terminate(scenario == 6 ? ML_ERROR_PROTECTED_CONTENT : scenario == 7 ? ML_ERROR_FRAME_CONVERSION
            : scenario == 8 ? ML_ERROR_GRACEFUL_TERMINATION : -7);
        settled(controller);
        require(host.resumes == 0 && host.cancels == 0 && driver.starts == 1 && driver.stops == 1,
            "unsafe or nonrecoverable state automatically reconnected");
    }
    for (const bool capabilityFailure : {false, true}) {
        Host host;
        Driver driver;
        host.driver = &driver;
        host.automaticReconnect = true;
        host.appUuid = "game";
        DeckNativeSessionController controller(true, host.resolver(), driver);
        controller.setInputFocus(true);
        require(controller.start("host", "game"), "transient fixture failed");
        until([&] { return phase(controller) == "active"; });
        if (capabilityFailure) {
            host.transientCapabilities = true;
            host.verifyStream = [](const auto&) -> std::optional<nova::deck::DeckStreamCapabilities> { return {}; };
        } else {
            host.failServerInfo = host.transientServerFailure = true;
        }
        driver.terminate();
        until([&] { return phase(controller) == "reconnecting" && controller.state().value("reconnectAttempt") == 2; });
        controller.stop();
        require(!controller.busy() && controller.state().value("canReconnect").toBool() && host.resumes == 0 && host.cancels == 0,
            "transient host checks lost cancellable bounded retry");
    }
}

void testAudioPreferences() {
    DeckPlaySettings settings;
    for (const int channels : {2, 6, 8}) {
        const auto selected = DeckAudioConfiguration{channels, true}.toMap();
        require(settings.saveAudioSettings(selected), "audio fixture cannot save preferences");
        Host host;
        Driver driver;
        host.driver = &driver;
        host.appUuid = "game";
        host.automaticReconnect = true;
        DeckNativeSessionController controller(true, host.resolver(), driver);
        controller.setInputFocus(true);
        require(settings.setStickDeadzonePercent(-10), "deadzone recovery fixture failed");
        require(settings.setFramePacingMode("balanced"), "pacing snapshot fixture failed");
        require(controller.startConfigured("host", "game", DeckPlayConfiguration{}.toMap()), "audio fixture cannot start");
        // Change defaults before the worker reads its target. The accepted
        // launch and all recovery paths retain their original immutable choice.
        require(settings.resetAudioSettings(), "audio fixture cannot reset defaults");
        require(settings.resetStickDeadzonePercent(), "deadzone recovery reset failed");
        require(settings.resetFramePacingMode(), "pacing snapshot reset failed");
        until([&] { return phase(controller) == "active"; });
        require(controller.state()["stickDeadzonePercent"] == -10, "recovery changed deadzone snapshot");
        require(controller.state()["framePacingMode"] == "balanced", "accepted stream reread pacing defaults");
        const int expected = channels == 8 ? AUDIO_CONFIGURATION_71_SURROUND
            : channels == 6 ? AUDIO_CONFIGURATION_51_SURROUND : AUDIO_CONFIGURATION_STEREO;
        const auto surround = "surroundAudioInfo=" + std::to_string(SURROUNDAUDIOINFO_FROM_AUDIO_CONFIGURATION(expected));
        require(driver.receivedConfiguration.audioConfiguration == expected && host.launchRequest.find(surround) != std::string::npos
            && host.launchRequest.find("localAudioPlayMode=1") != std::string::npos, "audio launch and decoder configuration diverged");
        host.serverInfoOverride = ownedRunningGame();
        driver.terminate();
        until([&] { return driver.starts == 2 && phase(controller) == "active"; });
        require(controller.state()["stickDeadzonePercent"] == -10, "recovery changed deadzone snapshot");
        require(controller.state()["framePacingMode"] == "balanced", "recovery changed pacing snapshot");
        require(driver.receivedConfiguration.audioConfiguration == expected && host.resumeRequest.find(surround) != std::string::npos
            && host.resumeRequest.find("localAudioPlayMode=1") != std::string::npos, "automatic reconnect reread changed audio defaults");
        controller.setSystemSleeping(true);
        settled(controller);
        controller.setSystemSleeping(false);
        require(controller.resumeDisconnected("host", "game"), "audio wake resume failed");
        until([&] { return phase(controller) == "active"; });
        require(controller.state()["stickDeadzonePercent"] == -10, "recovery changed deadzone snapshot");
        require(controller.state()["framePacingMode"] == "balanced", "recovery changed pacing snapshot");
        require(driver.receivedConfiguration.audioConfiguration == expected && host.resumeRequest.find(surround) != std::string::npos
            && host.resumeRequest.find("localAudioPlayMode=1") != std::string::npos, "sleep recovery changed audio snapshot");
        controller.closeSession();
        settled(controller);
        // A deliberate new Play picks up the new device preference.
        host.serverInfoOverride.clear();
        require(controller.start("host", "game"), "new audio stream failed");
        until([&] { return phase(controller) == "active"; });
        require(controller.state()["stickDeadzonePercent"] == 5, "new stream ignored deadzone default");
        require(controller.state()["framePacingMode"] == "latency", "new stream ignored pacing default");
        require(driver.receivedConfiguration.audioConfiguration == AUDIO_CONFIGURATION_STEREO &&
            host.launchRequest.find("localAudioPlayMode=0") != std::string::npos, "new stream ignored changed audio defaults");
        controller.stop();
        settled(controller);
    }
    require(settings.saveAudioSettings(DeckAudioConfiguration{8, true}.toMap()), "Space audio fixture failed");
    Host host;
    Driver driver;
    host.expectedGame = "space.fixture";
    DeckNativeSessionController controller(true, host.resolver(), driver);
    require(controller.start("host", host.expectedGame), "Space audio start failed");
    until([&] { return phase(controller) == "active"; });
    require(driver.receivedConfiguration.audioConfiguration == AUDIO_CONFIGURATION_STEREO &&
        host.launchRequest.find("localAudioPlayMode=1") != std::string::npos && settings.audioConfiguration().channels == 8,
        "Space stereo exception changed host audio or saved preference");
    controller.stop();
    settled(controller);
    require(settings.resetAudioSettings(), "audio test leaked defaults");
}

void testSleepAndWake() {
    for (const bool wakeDuringTeardown : {false, true}) {
        Host host;
        Driver driver;
        host.driver = &driver;
        host.appUuid = "game";
        host.automaticReconnect = true;
        InputRecorder input(driver);
        DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
        int prepared = 0;
        QObject::connect(&controller, &DeckNativeSessionController::sleepPreparationFinished, [&] { ++prepared; });
        controller.setInputFocus(true);
        controller.updateController({}, true);
        const auto settings = DeckPlayConfiguration{1920, 1080, 30, 30000}.toMap();
        require(controller.startConfigured("host", "game", settings), "sleep fixture failed");
        until([&] { return phase(controller) == "active"; });
        controller.resumeInput();
        controller.updateController({.buttons = A_FLAG, .leftTrigger = 255}, true);
        until([&] { return input.lastIs({{.buttons = A_FLAG, .leftTrigger = 255}, true}); });
        driver.blockStop = true;
        controller.setSystemSleeping(true);
        controller.setSystemSleeping(true);
        until([&] { return driver.stopBarrier.entered.load(); });
        require(input.lastIs({{}, false}) && !controller.capturesGamepad() && controller.controlsVisible(),
            "sleep retained held controls or gameplay capture");
        require(prepared == 0 && controller.busy() && controller.state().value("sleeping").toBool(),
            "sleep delay was released before complete transport cleanup");
        controller.resumeInput();
        require(!controller.start("host", "game") && !controller.resumeDisconnected("host", "game"),
            "sleep started a competing connection");
        if (wakeDuringTeardown) {
            controller.setSystemSleeping(false);
            require(controller.busy() && !controller.start("host", "game"), "early wake raced teardown");
        }
        driver.stopBarrier.release();
        settled(controller);
        require(prepared == (wakeDuringTeardown ? 0 : 1) && host.cancels == 0 && driver.stops == 1 &&
            controller.state().value("canResume").toBool() && phase(controller) == "disconnected",
            "sleep ended the game or lost manual recovery");
        if (!wakeDuringTeardown) {
            require(!controller.resumeDisconnected("host", "game") && !controller.startConfigured("host", "game", settings),
                "sleep accepted a settled resume or new launch");
            controller.setSystemSleeping(false);
        }
        require(host.resumes == 0 && driver.starts == 1 && controller.controlsVisible(), "wake automatically replayed session or input");
        driver.blockStop = false;
        host.serverInfoOverride = ownedRunningGame();
        require(controller.resumeDisconnected("host", "game"), "wake lost resume ticket");
        until([&] { return phase(controller) == "active"; });
        require(driver.receivedConfiguration.width == 1920 && driver.receivedConfiguration.fps == 30 &&
            driver.receivedConfiguration.bitrate == 30000 && host.launches == 1 && host.resumes == 1,
            "wake resume changed reviewed settings or launched again");
        controller.resumeInput();
        require(controller.controllerHint().contains("Release"), "wake replayed held controls");
        controller.updateController({}, true);
        until([&] { return input.lastIs({{}, true}); });
        controller.setSystemSleeping(true);
        settled(controller);
        controller.setSystemSleeping(false);
        host.serverInfoOverride = ownedRunningGame("17", "replacement-token");
        require(controller.resumeDisconnected("host", "game"), "wake authorization check rejected before fresh host lookup");
        settled(controller);
        require(host.resumes == 1 && host.launches == 1 && host.cancels == 0 && phase(controller) == "failed",
            "wake reused stale ownership or replaced another session");
    }
}

void testSleepDuringPendingWork() {
    for (int scenario = 0; scenario < 4; ++scenario) {
        Host host;
        Driver driver;
        host.driver = &driver;
        host.appUuid = "game";
        if (scenario == 0) host.blockPath = "/launch";
        if (scenario == 2) host.expectedGame = "space.fixture";
        DeckNativeSessionController controller(true, host.resolver(), driver);
        require(controller.start("host", host.expectedGame), "pending sleep fixture failed");
        if (scenario == 0) until([&] { return host.barrier.entered.load(); });
        else until([&] { return phase(controller) == "active"; });
        if (scenario == 1) {
            driver.blockStop = true;
            controller.stop(); // confirmed End wins over the following sleep
            until([&] { return driver.stopBarrier.entered.load(); });
        }
        if (scenario == 3) {
            require(controller.disconnectFromHost(), "pending resume fixture could not detach");
            settled(controller);
            host.serverInfoOverride = ownedRunningGame();
            host.blockPath = "/resume";
            require(controller.resumeDisconnected("host", "game"), "pending resume fixture failed");
            until([&] { return host.barrier.entered.load(); });
        }
        controller.setSystemSleeping(true);
        host.barrier.release();
        driver.stopBarrier.release();
        settled(controller);
        require(host.cancels == (scenario == 3 ? 0 : 1), "sleep changed launch cleanup, End, Space, or resume ownership");
        require(controller.state().value("canResume").toBool() == (scenario == 3), "sleep invented or discarded a resume ticket");
        controller.setSystemSleeping(false);
        if (scenario == 3) {
            host.blockPath.clear();
            require(controller.resumeDisconnected("host", "game"), "cancelled resume could not be retried after wake");
            until([&] { return phase(controller) == "active"; });
            controller.closeSession();
            settled(controller);
            require(host.launches == 1 && host.resumes == 2 && host.cancels == 0, "wake repeated launch or quit resumed game");
        }
    }
    Host host;
    Driver driver;
    host.driver = &driver;
    host.automaticReconnect = true;
    host.appUuid = "game";
    DeckNativeSessionController controller(true, host.resolver(), driver);
    int prepared = 0;
    QObject::connect(&controller, &DeckNativeSessionController::sleepPreparationFinished, [&] { ++prepared; });
    controller.setSystemSleeping(true);
    controller.setSystemSleeping(true);
    require(prepared == 1 && !controller.busy() && !controller.start("host", "game") && host.requests == 0,
        "idle sleep touched the host or duplicated preparation");
    controller.setSystemSleeping(false);
    controller.setInputFocus(true);
    require(controller.start("host", "game"), "sleep backoff fixture failed");
    until([&] { return phase(controller) == "active"; });
    host.failServerInfo = host.transientServerFailure = true;
    driver.terminate();
    until([&] { return phase(controller) == "reconnecting" && controller.state().value("reconnectAttempt") == 2; });
    controller.setSystemSleeping(true);
    require(!controller.busy() && prepared == 2 && phase(controller) == "disconnected" &&
        controller.state().value("canResume").toBool(), "sleep backoff lost immediate cleanup or recovery");
    controller.setSystemSleeping(false);
    const auto requests = host.requests.load();
    QElapsedTimer elapsed; elapsed.start();
    until([&] { return elapsed.elapsed() > 1100; });
    require(host.requests == requests && host.resumes == 0 && host.cancels == 0 && driver.starts == 1,
        "wake allowed a stale retry timer to reach the host");
}

void testDeadzoneSnapshotAndRelease() {
    DeckPlaySettings settings;
    require(settings.setStickDeadzonePercent(20), "deadzone snapshot fixture failed");
    Host host; Driver driver; host.driver=&driver; InputRecorder input(driver);
    DeckNativeSessionController controller(true,host.resolver(),driver,input.sender());
    controller.setInputFocus(true);
    controller.updateController({.leftX=3000},true);
    require(controller.start("host","game"),"deadzone start failed");
    require(settings.setStickDeadzonePercent(-20),"new deadzone default failed");
    until([&] { return phase(controller)=="active"; }); controller.resumeInput();
    require(controller.controllerHint().isEmpty() && controller.controllerNeutral({.leftX=3000}),"changed positive threshold did not recheck held drift");
    until([&] { return input.lastIs({{},true}); });
    controller.updateController({.leftX=20000},true);
    until([&] { return input.lastIs({{.leftX=20000},true}); });
    controller.updateController({.leftX=1000},true);
    until([&] { return input.lastIs({{},true}); });
    require(controller.state()["stickDeadzonePercent"]==20,"active stream reread changed preference");
    controller.closeSession(); settled(controller);
    require(input.lastIs({{},false}),"deadzone stream teardown lost release");
    host.serverInfoOverride.clear();
    // No fresh joystick event: old threshold saw this held stick as neutral.
    require(controller.start("host","game"),"anti-deadzone start failed");
    until([&] { return phase(controller)=="active"; }); controller.resumeInput();
    require(controller.controllerHint().contains("Release") && !controller.controllerNeutral({.leftX=1000}),"new threshold armed already held input");
    controller.updateController({},true);
    controller.updateController({.leftX=328},true);
    until([&] { const auto seen=input.snapshot(); return !seen.empty() && seen.back().state.leftX>6000 && seen.back().state.leftX<8000; });
    controller.setInputFocus(false); until([&] { return input.lastIs({{},true}); });
    controller.setInputFocus(true); controller.resumeInput();
    require(controller.controllerHint().contains("Release"),"anti-deadzone leaked held input on focus return");
    controller.updateController({},false); until([&] { return input.lastIs({{},false}); });
    controller.updateController({.leftX=328},true);
    require(controller.controllerHint().contains("Release"),"replug armed held anti-deadzone input");
    controller.updateController({},true); controller.updateController({.leftX=-32768,.rightX=32767},true);
    until([&] { return input.lastIs({{.leftX=-32768,.rightX=32767},true}); });
    controller.stop(); settled(controller);
    require(input.lastIs({{},false}) && settings.resetStickDeadzonePercent(),"anti-deadzone stop/reset failed");
}

struct DesktopRecorder {
    Driver& driver;
    std::mutex mutex;
    std::vector<DeckDesktopPacket> packets;
    bool blockDown = false, failDown = false;
    Barrier barrier;
    DeckDesktopSend sender() { return [this](const DeckDesktopPacket& packet) {
        require(QThread::currentThread()==driver.startThread && driver.starts>driver.stops,
            "desktop input sent outside owning live worker");
        { const std::lock_guard lock(mutex); packets.push_back(packet); }
        if (packet.kind==DeckDesktopPacket::Key && packet.code=='W' && packet.down) {
            if (blockDown) barrier.wait();
            if (failDown) return -1;
        }
        return 0;
    }; }
    std::vector<DeckDesktopPacket> snapshot() { const std::lock_guard lock(mutex); return packets; }
    bool seen(DeckDesktopPacket packet) { const auto p=snapshot(); return std::find(p.begin(),p.end(),packet)!=p.end(); }
};

void testDesktopWorkerOwnership() {
    for (int scenario=0;scenario<5;++scenario) {
        Host host; Driver driver; host.driver=&driver;
        DesktopRecorder desktop{driver};
        desktop.blockDown = scenario==0 || scenario==1;
        desktop.failDown = scenario==2;
        DeckNativeSessionController controller(true,host.resolver(),driver,[](const auto&) { return 0; },nullptr,desktop.sender());
        const DeckDesktopPacket down{DeckDesktopPacket::Key,'W',0,0,true}, up{DeckDesktopPacket::Key,'W'};
        controller.sendDesktopInput(down);
        require(desktop.snapshot().empty(),"idle keyboard reached host");
        controller.setInputFocus(true);
        require(controller.start("host","game"),"desktop worker fixture failed");
        until([&] { return phase(controller)=="active"; });
        controller.sendDesktopInput(down);
        require(desktop.snapshot().empty(),"menu keyboard reached host");
        controller.resumeInput();
        controller.sendDesktopInput(down);
        if (desktop.blockDown) {
            until([&] { return desktop.barrier.entered.load(); });
            const int count=scenario==0 ? 4 : 300;
            for (int i=0;i<count;++i) controller.sendDesktopInput({DeckDesktopPacket::Key,'A',0,0,bool(i%2)});
            if (scenario==0) controller.showControls();
            desktop.barrier.release();
        }
        if (scenario==0) {
            until([&] { return desktop.seen(up); });
            require(desktop.snapshot().size()==2,"overlay allowed stale queued key edges");
            controller.closeSession();
        } else if (scenario==3) {
            until([&] { return desktop.seen(down); });
            controller.sendDesktopInput({DeckDesktopPacket::Button,3,0,0,true});
            until([&] { return desktop.seen({DeckDesktopPacket::Button,3,0,0,true}); });
            controller.setSystemSleeping(true);
        } else if (scenario==4) {
            until([&] { return desktop.seen(down); });
            driver.terminate();
        }
        settled(controller);
        require(desktop.seen(up) && host.cancels==0 && driver.stops==1,"desktop failure/sleep/disconnect lost release or ended game");
        if (scenario==3) require(desktop.seen({DeckDesktopPacket::Button,3}),"sleep left a mouse button held");
        if (scenario==1 || scenario==2 || scenario==4) require(phase(controller)=="interrupted","input failure was not actionable");
        const auto count=desktop.snapshot().size();
        controller.sendDesktopInput(down);
        require(desktop.snapshot().size()==count,"desktop event crossed teardown");
    }
}

void testDesktopWindowRouting() {
    Host host; Driver driver; host.driver=&driver;
    DesktopRecorder desktop{driver};
    DeckNativeSessionController controller(true,host.resolver(),driver,[](const auto&) { return 0; },nullptr,desktop.sender());
    DeckPlaySettings settings;
    DeckDesktopInputBridge bridge(controller,settings);
    QQuickWindow window, unrelated;
    window.resize(1280,800); window.show(); window.requestActivate();
    bridge.watchWindow(&window,&window);
    until([&] { return window.isActive(); }); controller.setInputFocus(true);
    const auto key=[&](int code,bool down,Qt::KeyboardModifiers mods=Qt::NoModifier,bool repeat=false,QWindow* target=nullptr) {
        QKeyEvent event(down ? QEvent::KeyPress : QEvent::KeyRelease,code,mods,{},repeat);
        QCoreApplication::sendEvent(target ? target : &window,&event);
    };
    const auto mouse=[&](QEvent::Type type,QPointF p,Qt::MouseButton button,Qt::MouseButtons buttons,Qt::MouseEventSource source=Qt::MouseEventNotSynthesized) {
        QMouseEvent event(type,p,p,window.mapToGlobal(p),button,buttons,Qt::NoModifier,source);
        QCoreApplication::sendEvent(&window,&event);
    };
    key(Qt::Key_Return,true); // The menu's held Resume button is never sent.
    require(controller.start("host","game"),"desktop event fixture failed");
    until([&] { return phase(controller)=="active"; }); controller.resumeInput();
    key(Qt::Key_Return,false);
    key(Qt::Key_Escape,true); key(Qt::Key_Escape,false);
    until([&] { return desktop.seen({DeckDesktopPacket::Key,27}); });
    require(!controller.controlsVisible() && !desktop.seen({DeckDesktopPacket::Key,13}),"Escape opened local UI or Resume leaked");
    key(Qt::Key_A,true,Qt::NoModifier,false,&unrelated); key(Qt::Key_A,false,Qt::NoModifier,false,&unrelated);
    key(Qt::Key_W,true); key(Qt::Key_W,false,Qt::NoModifier,true); key(Qt::Key_W,true,Qt::NoModifier,true);
    until([&] { return desktop.seen({DeckDesktopPacket::Key,'W',0,0,true}); });
    require(!desktop.seen({DeckDesktopPacket::Key,'W'}) && !desktop.seen({DeckDesktopPacket::Key,'A',0,0,true}),"repeat/unrelated window leaked");
    mouse(QEvent::MouseButtonPress,{640,400},Qt::LeftButton,Qt::LeftButton);
    mouse(QEvent::MouseMove,{700,410},Qt::NoButton,Qt::LeftButton);
    mouse(QEvent::MouseButtonRelease,{700,410},Qt::LeftButton,Qt::NoButton);
    until([&] { return desktop.seen({DeckDesktopPacket::Button,1}); });
    const auto seen=desktop.snapshot();
    const auto press=std::find(seen.begin(),seen.end(),DeckDesktopPacket{DeckDesktopPacket::Button,1,0,0,true});
    require(press!=seen.end() && press!=seen.begin() && (press-1)->kind==DeckDesktopPacket::Position,
        "click was not preceded by its pointer position");
    QWheelEvent wheel({640,400},window.mapToGlobal(QPointF(640,400)),{},QPoint(120,-240),Qt::NoButton,Qt::NoModifier,Qt::NoScrollPhase,false);
    QCoreApplication::sendEvent(&window,&wheel);
    until([&] { return desktop.seen({DeckDesktopPacket::Scroll,0,120,-240}); });
    QQuickItem local(window.contentItem()); local.setParent(&window);
    local.setObjectName("native-show-controls"); local.setPosition({10,10}); local.setSize({100,100});
    const auto count=desktop.snapshot().size();
    mouse(QEvent::MouseButtonPress,{30,30},Qt::RightButton,Qt::RightButton);
    mouse(QEvent::MouseMove,{640,400},Qt::NoButton,Qt::RightButton);
    mouse(QEvent::MouseButtonRelease,{640,400},Qt::RightButton,Qt::NoButton);
    mouse(QEvent::MouseButtonPress,{640,400},Qt::MiddleButton,Qt::MiddleButton,Qt::MouseEventSynthesizedByQt);
    mouse(QEvent::MouseButtonRelease,{640,400},Qt::MiddleButton,Qt::NoButton,Qt::MouseEventSynthesizedByQt);
    key(Qt::Key_W,false);
    until([&] { return desktop.seen({DeckDesktopPacket::Key,'W'}); });
    require(desktop.snapshot().size()==count+1,"local/synthetic pointer leaked or swallowed held keyboard release");
    key(Qt::Key_M,true,Qt::ControlModifier|Qt::AltModifier|Qt::ShiftModifier);
    require(controller.controlsVisible(),"keyboard shortcut did not open Command Center");
    controller.resumeInput(); key(Qt::Key_M,false);
    key(Qt::Key_B,true);
    until([&] { return desktop.seen({DeckDesktopPacket::Key,'B',0,0,true}); });
    QFocusEvent focusOut(QEvent::FocusOut); QCoreApplication::sendEvent(&window,&focusOut);
    until([&] { return desktop.seen({DeckDesktopPacket::Key,'B'}); });
    require(controller.controlsVisible() && !desktop.seen({DeckDesktopPacket::Key,'M',0,0,true}),"focus loss or local shortcut guard failed");
    controller.setInputFocus(true); controller.resumeInput();
    key(Qt::Key_B,true,Qt::NoModifier,true); key(Qt::Key_B,false);
    controller.closeSession(); settled(controller);
    require(host.cancels==0,"desktop window close ended game");
}

void testForwardedInputAndFocus() {
    Host host;
    Driver driver;
    host.driver = &driver;
    InputRecorder input(driver);
    DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
    controller.setInputFocus(true);
    controller.updateController({.buttons = A_FLAG}, true);
    require(controller.start("host", "game"), "start rejected");
    until([&]() { return phase(controller) == "active"; });
    controller.resumeInput();
    require(controller.capturesGamepad() && controller.controllerHint().contains("Release"), "held launch button was not gated");
    until([&]() { return input.lastIs({{}, true}); });
    controller.updateController({}, true);
    until([&]() { return input.lastIs({{}, true}); });
    controller.updateController({.buttons = A_FLAG}, true);
    controller.updateController({}, true);
    until([&]() {
        const auto seen = input.snapshot();
        return seen.size() >= 3 && seen[seen.size() - 2].state.buttons == A_FLAG && seen.back().state.neutral();
    });
    require(controller.capturesGamepad(), "gameplay A accidentally opened UI");
    const DeckControllerState held{.buttons = B_FLAG, .rightTrigger = 255, .leftX = 17000};
    controller.updateController(held, true);
    until([&]() { return input.lastIs({held, true}); });
    controller.setInputFocus(false);
    until([&]() { return input.lastIs({{}, true}); });
    require(controller.controlsVisible() && !controller.capturesGamepad(), "focus loss did not pause gameplay input");
    controller.setInputFocus(true);
    require(controller.controlsVisible(), "focus return resumed without player action");
    controller.resumeInput();
    require(controller.controllerHint().contains("Release"), "focus-return held input was replayed");
    controller.updateController({}, true);
    until([&]() { return input.lastIs({{}, true}); });
    controller.updateController({.buttons = BACK_FLAG}, true);
    controller.updateController({.buttons = BACK_FLAG | PLAY_FLAG}, true);
    until([&]() { return input.lastIs({{}, true}); });
    require(controller.controlsVisible(), "controller chord did not open Nova controls");
    for (const auto& packet : input.snapshot()) {
        require(packet.connected, "focus/overlay transition destroyed the host controller device");
        require(!(packet.state.buttons & (BACK_FLAG | PLAY_FLAG)), "overlay chord leaked to host");
    }
    controller.stop();
    settled(controller);
    require(driver.stops == 1 && host.cancels == 1 && input.lastIs({{}, false}), "input was not released before stop");
    const auto count = input.snapshot().size();
    controller.updateController(held, true);
    require(input.snapshot().size() == count, "stopped session accepted new input");
}

void testRumbleSessionBoundaries() {
    DeckPlaySettings settings;
    for (int boundary = 0; boundary < 6; ++boundary) {
        require(settings.resetRumble(), "cannot reset rumble fixture");
        Host host; Driver driver; host.driver = &driver;
        InputRecorder input(driver);
        std::vector<std::pair<int, int>> values;
        DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
        QObject::connect(&controller, &DeckNativeSessionController::rumbleRequested, [&](quint16 low, quint16 high) {
            require(QThread::currentThread() == QCoreApplication::instance()->thread(), "feedback reached the device bridge off the GUI thread");
            values.emplace_back(low, high);
        });
        auto send = [&](unsigned short number, unsigned short low, unsigned short high) {
            const auto callback = driver.feedback.load(); require(callback, "native stream omitted feedback callback");
            std::thread incoming([=] { callback(number, low, high); }); incoming.join();
        };
        auto positives = [&] { return std::count_if(values.begin(), values.end(), [](const auto& value) { return value.first || value.second; }); };
        auto quiet = [&](const auto& action) {
            const auto before = positives(); action();
            QElapsedTimer time; time.start(); until([&] { return time.elapsed() > 45; });
            require(positives() == before, "inactive/stale/wrong-controller rumble reached the bridge");
        };
        auto enableInput = [&] {
            controller.setInputFocus(true); controller.updateController({}, true);
            controller.resumeInput(); controller.updateController({}, true);
        };
        controller.setInputFocus(true);
        controller.updateController({.buttons = A_FLAG}, true);
        require(controller.start("host", "game") && settings.setRumbleEnabled(false), "rumble stream fixture failed");
        until([&] { return phase(controller) == "active"; });
        quiet([&] { send(0, 10, 20); }); // controls still open
        controller.resumeInput();
        quiet([&] { send(0, 10, 20); }); // held button still awaits neutral
        controller.updateController({}, true);
        quiet([&] { send(1, 10, 20); }); // only the one admitted controller exists
        quiet([&] { send(0, 10, 20); send(0, 0, 0); }); // latest stop replaces queued vibration
        send(0, 65535, 32768);
        until([&] { return !values.empty() && values.back() == std::pair{65535, 32768}; });
        if (boundary == 0) {
            controller.updateController({.buttons = BACK_FLAG}, true);
            controller.updateController({.buttons = BACK_FLAG | PLAY_FLAG}, true);
        } else if (boundary == 1) controller.setInputFocus(false);
        else if (boundary == 2) controller.updateController({}, false);
        else if (boundary == 3) controller.setSystemSleeping(true);
        else if (boundary == 4) require(controller.disconnectFromHost(), "rumble disconnect refused");
        else driver.terminate();
        until([&] { return !values.empty() && values.back() == std::pair{0, 0}; });
        if (boundary >= 3) settled(controller);
        quiet([&] { send(0, 30, 40); });
        if (boundary >= 3) {
            host.serverInfoOverride = ownedRunningGame();
            if (boundary == 3) controller.setSystemSleeping(false);
            require(boundary == 5 ? controller.reconnect("host", "game") : controller.resumeDisconnected("host", "game"),
                "rumble recovery fixture refused");
            until([&] { return phase(controller) == "active"; });
        }
        quiet(enableInput); // never replay feedback from before controls/unplug/reconnect
        send(0, 200, 300);
        until([&] { return values.back() == std::pair{200, 300}; });
        controller.stop(); settled(controller);
        require(values.back() == std::pair{0, 0}, "stream stop retained vibration");
        quiet([&] { send(0, 400, 500); });
        // A deliberate new Play reads the newer disabled default. Editing it
        // after start cannot change this session, in either direction.
        host.serverInfoOverride.clear();
        require(controller.start("host", "game") && settings.setRumbleEnabled(true), "disabled rumble fixture failed");
        until([&] { return phase(controller) == "active"; });
        enableInput();
        quiet([&] { send(0, 600, 700); });
        controller.updateController({.buttons = A_FLAG}, true);
        until([&] { return input.lastIs({{.buttons = A_FLAG}, true}); });
        controller.stop(); settled(controller);
    }
    require(settings.resetRumble(), "cannot restore rumble defaults");
}

void testMultiplePlayerInputAndFeedback() {
    DeckPlaySettings settings;
    require(settings.resetRumble(), "cannot reset multiplayer rumble fixture");
    Host host; Driver driver; host.driver = &driver;
    InputRecorder input(driver);
    std::vector<std::array<int, 3>> feedback;
    DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
    QObject::connect(&controller, &DeckNativeSessionController::playerRumbleRequested,
        [&](quint16 player, quint16 low, quint16 high) { feedback.push_back({player, low, high}); });
    auto observed = [&](unsigned player, unsigned buttons, unsigned mask) {
        const auto packets = input.snapshot();
        return std::any_of(packets.begin(), packets.end(), [&](const auto& packet) {
            return packet.controllerNumber == player && packet.state.buttons == buttons && packet.effectiveMask() == mask;
        });
    };
    controller.setInputFocus(true);
    controller.updateController({}, true);
    require(controller.start("host", "game"), "multiplayer start refused");
    until([&] { return phase(controller) == "active"; });
    controller.resumeInput();
    controller.updateController({}, true);
    controller.updateController({.buttons = A_FLAG}, true);
    until([&] { return observed(0, A_FLAG, 1); });
    controller.updatePlayerController(1, {}, true);
    controller.updatePlayerController(1, {}, true);
    controller.updatePlayerController(1, {.buttons = B_FLAG}, true);
    until([&] { return observed(1, B_FLAG, 3); });
    const auto callback = driver.feedback.load();
    require(callback, "multiplayer missing feedback callback");
    callback(1, 123, 456);
    until([&] { return !feedback.empty() && feedback.back() == std::array<int, 3>{1, 123, 456}; });
    const auto positive = feedback.size();
    controller.showControls();
    until([&] { return observed(0, 0, 3) && observed(1, 0, 3); });
    require(feedback.size() > positive && feedback.back()[1] == 0 && feedback.back()[2] == 0,
        "overlay did not stop player feedback");
    const auto paused = feedback.size();
    callback(1, 500, 500);
    QElapsedTimer clock; clock.start(); until([&] { return clock.elapsed() > 40; });
    require(feedback.size() == paused, "overlay accepted player feedback");
    controller.resumeInput();
    controller.updateController({}, true);
    controller.updatePlayerController(1, {}, true);
    controller.updatePlayerController(1, {}, false);
    until([&] { return observed(1, 0, 1); });
    require(controller.capturesGamepad(), "P2 unplug interrupted P1");
    controller.updateController({.buttons = X_FLAG}, true);
    until([&] { return observed(0, X_FLAG, 1); });
    controller.updatePlayerController(16, {.buttons = B_FLAG}, true);
    for (const auto& packet : input.snapshot()) require(packet.controllerNumber < 2, "phantom player was sent");
    controller.stop(); settled(controller);
    require(input.lastIs({{}, false}) && driver.stops == 1 && host.cancels == 1, "multiplayer exit lost cleanup");
}

void testQueuedInputBoundaries() {
    for (int scenario = 0; scenario < 6; ++scenario) {
        Host host;
        Driver driver;
        host.driver = &driver;
        InputRecorder input(driver);
        input.blockA = scenario != 2;
        input.failA = scenario == 2;
        DeckNativeSessionController controller(true, host.resolver(), driver, input.sender());
        controller.setInputFocus(true);
        controller.updateController({}, true);
        require(controller.start("host", "game"), "start rejected");
        until([&]() { return phase(controller) == "active"; });
        controller.resumeInput();
        until([&]() { return input.lastIs({{}, true}); });
        controller.updateController({.buttons = A_FLAG}, true);
        if (input.blockA) {
            until([&]() { return input.barrier.entered.load(); });
            const int queued = scenario == 1 ? 400 : 5;
            for (int i = 0; i < queued; ++i) controller.updateController({.buttons = i % 2 ? X_FLAG : Y_FLAG}, true);
            if (scenario == 0) controller.setInputFocus(false);
            if (scenario == 3) controller.showControls();
            if (scenario == 4) {
                // The last queued packet is already neutral: the chord must
                // replace it after clearing the queue, not deduplicate it away.
                controller.updateController({}, true);
                controller.updateController({.buttons = BACK_FLAG}, true);
                controller.updateController({.buttons = BACK_FLAG | PLAY_FLAG}, true);
            }
            if (scenario == 5) controller.updateController({}, false);
            input.barrier.release();
        }
        if (scenario == 0 || scenario >= 3) {
            until([&]() { return input.lastIs({{}, scenario != 5}); });
            if (scenario != 5) {
                for (const auto& packet : input.snapshot())
                    require(packet.connected, "pausing input removed the host device");
            }
            controller.stop();
        }
        settled(controller);
        if (scenario == 1 || scenario == 2) require(phase(controller) == "interrupted" && controller.state().value("canReconnect").toBool(),
            "queue overflow/send failure lost recovery");
        for (const auto& packet : input.snapshot())
            require(!(packet.state.buttons & (X_FLAG | Y_FLAG)), "stale queued input survived focus loss or overflow");
        require(input.lastIs({{}, false}) && driver.stops == 1 && host.cancels == (scenario == 1 || scenario == 2 ? 0 : 1),
            "input failure did not release and clean up the session");
    }
}
}

int main(int argc, char** argv) {
    QTemporaryDir settingsDirectory;
    require(settingsDirectory.isValid(), "missing isolated settings directory");
    qputenv("XDG_CONFIG_HOME",settingsDirectory.path().toUtf8());
    QFile userDirs(settingsDirectory.path()+"/user-dirs.dirs");
    require(userDirs.open(QIODevice::WriteOnly), "missing user directories fixture");
    userDirs.write("XDG_DOCUMENTS_DIR=\""+settingsDirectory.path().toUtf8()+"/Documents\"\n"); userDirs.close();
    qputenv("QT_QPA_PLATFORM","offscreen");
    qputenv("QT_QUICK_BACKEND","software");
    QGuiApplication app(argc, argv);
    require(deckSupportReportDirectory().startsWith(settingsDirectory.path()+"/"), "support report destination is not isolated");
    QCoreApplication::setOrganizationName("NovaDeckTests");
    QCoreApplication::setApplicationName("NativeSession");
    testDesktopWorkerOwnership();
    testDesktopWindowRouting();
    QSettings::setDefaultFormat(QSettings::IniFormat);
    QSettings::setPath(QSettings::IniFormat, QSettings::UserScope, settingsDirectory.path());
    testNoAutomaticStartOrInvalidSelection();
    testPresentationLifetime();
    testReviewedConfiguration();
    testRevalidatedPresetAndEncoder();
    {
        Host host;
        Driver driver;
        DeckNativeSessionController controller(true, host.resolver(), driver);
        require(controller.start("host", "game"), "HUD fixture did not start");
        until([&] { return controller.hud().value("rtt") == "17ms"; });
        require(driver.rttReads >= 2 && controller.hud().value("fps") == "--" &&
            controller.hud().value("codec") == "--", "HUD invented rendered frames or a negotiated codec");
        controller.stop(); settled(controller);
        require(controller.hud() == DeckHudMetrics::empty(), "stopped stream retained live HUD readings");
        const auto reads = driver.rttReads.load();
        QCoreApplication::processEvents();
        require(driver.rttReads == reads, "RTT read continued after teardown");
    }
    testLaunchModeAuthorization();
    testStreamCapabilitiesAtLaunch();
    testVideoCodecAtLaunch();
    testDisplayRateAtLaunch();
    testBackendTargetAuthority();
    testCancelDuringHttp("/serverinfo");
    testCancelDuringHttp("/launch");
    testConnectionCancellation();
    testActiveStopRetryAndDisconnect();
    testFailures();
    testShutdownAndGlobalExclusion();
    testDeadzoneSnapshotAndRelease();
    testForwardedInputAndFocus();
    testHudObserverDoesNotBlockInput();
    testDiagnosticsRefreshBoundary();
    testDoctorActionBoundary();
    testLiveTuningSessionBoundary();
    testFixedBitrateSessionBoundary();
    testSyncProfileSessionBoundary();
    testConfiguredFaceButtons();
    testDisconnectAndExitChoice();
    testDisconnectedResume();
    testInterruptedRecovery();
    testInterruptionScopeAndGracefulEnd();
    testResumeFailuresAndCancellation();
    testQueuedInputBoundaries();
    testMultiplePlayerInputAndFeedback();
    testReconnectPolicy();
    testAutomaticReconnectBudget();
    testAutomaticReconnectCancellation();
    testAutomaticReconnectRefusals();
    testSleepAndWake();
    testSleepDuringPendingWork();
    testAudioPreferences();
    testRumbleSessionBoundaries();
    std::cout << "Native session lifecycle checks passed\n";
}
