#include "runtime/deck_native_session.h"
#include "runtime/deck_session_failure_message.h"
#include "runtime/deck_support_report.h"
#include "runtime/deck_rumble.h"
#include <QScopeGuard>
extern "C" {
#include <libavutil/frame.h>
}

#include <atomic>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>

namespace nova::deck::runtime {
using namespace stream;

namespace {
// moonlight-common-c has a single process-global connection. Hold this through
// host cleanup, including across multiple controller instances.
std::mutex nativeConnectionMutex;

QVariantMap publicState(const QString& phase, const QString& copy, bool busy) {
    return {{"phase", phase}, {"copy", copy}, {"busy", busy}};
}
}

// Kept only in backend memory. No token, endpoint, or credentials enter QML or
// settings. Resuming always resolves the current paired host/library again.
struct DeckNativeSessionController::ResumeTicket {
    QString hostId, gameId;
    std::optional<DeckPlayConfiguration> configuration;
    int appId = 0;
    std::string appUuid, sessionToken;
    bool automaticReconnect = false;
    DeckAudioConfiguration audio;
    bool rumbleEnabled = true;
    DeckFramePacing framePacing = DeckFramePacing::Latency;
    int stickDeadzonePercent = kDeckDefaultStickDeadzone;
};

struct DeckNativeSessionController::Shared final : DeckQtQuickRhiPresentationSink, DeckStreamInput {
    std::atomic<bool> cancelled{false};
    std::atomic<bool> keepHostRunning{false};
    std::atomic<bool> endGameRequested{false};
    std::atomic<bool> suspendRequested{false};
    std::shared_ptr<const ResumeTicket> resumeTicket;
    bool automaticRetry = false; // published only after the worker finishes
    int automaticAttempt = 0;
    std::atomic<bool> done{false};
    std::mutex mutex;
    std::condition_variable wake;
    QVariantMap state;
    std::optional<DeckHudSample> hud;
    QVariantMap hostHud = DeckHudHostReducer::unavailable();
    // Called only under mutex. Cleared before the owning worker destroys the observer.
    std::function<bool(bool)> setTuning;
    std::function<bool(int)> setBitrate;
    std::function<bool(const QString&, int, bool, const QVariantMap&)> setSync;
    QString syncHostId;
    std::function<bool()> refreshDiagnostics;
    std::function<bool()> applyDoctorFix, undoDoctorFix, checkDoctorResult;
    DeckFrameDelivery::Publisher deliverFrame;
    DeckFrameDelivery::Configure configureFrames;
    DeckFramePacing framePacing = DeckFramePacing::Latency;
    int stickDeadzonePercent = kDeckDefaultStickDeadzone;
    std::mutex interruptMutex;
    DeckMoonlightConnectionDriver* startingDriver = nullptr;
    struct Input {
        DeckControllerPacket packet;
        std::uint64_t generation;
    };
    std::deque<Input> inputs;
    struct DesktopInput { DeckDesktopPacket packet; std::uint64_t generation; };
    std::deque<DesktopInput> desktopInputs;
    std::uint64_t desktopGeneration = 0;
    std::array<std::uint64_t, 16> inputGeneration{};
    bool acceptingInput = false, inputOverflow = false;
    bool rumbleEnabled = true;
    std::uint16_t feedbackAllowed = 0;
    std::array<std::optional<DeckRumble>, 16> pendingRumble;
    bool finishing = false; // mutex-protected: terminal cleanup freezes exit intent

    std::string_view adapterName() const override { return "native-controller-feedback"; }
    void rumble(uint16_t controller, uint16_t low, uint16_t high) override {
        const std::lock_guard lock(mutex);
        if (controller >= 16 || !(feedbackAllowed & (1u << controller)) || cancelled || done) return;
        pendingRumble[controller] = DeckRumble{low, high};
    }
    void setMotionEventState(uint16_t, uint8_t, uint16_t) override {}
    void setControllerLed(uint16_t, uint8_t, uint8_t, uint8_t) override {}

    void publish(const QString& phase, const QString& copy, bool canDisconnect = false) {
        const std::lock_guard lock(mutex);
        state = publicState(phase, copy, true);
        state.insert("canDisconnect", canDisconnect);
        state.insert("automaticReconnect", automaticAttempt > 0 && phase != "active");
        state.insert("reconnectAttempt", automaticAttempt);
        state.insert("framePacingMode", deckFramePacingName(framePacing));
        state.insert("stickDeadzonePercent", stickDeadzonePercent);
    }
    void finish(const QString& phase, const QString& copy) {
        const std::lock_guard lock(mutex);
        state = publicState(phase, copy, false);
        done = true;
    }
    bool presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& descriptor) override {
        if (cancelled || done) return false;
        return deliverFrame && deliverFrame(descriptor);
    }
    void interrupt() {
        const std::lock_guard lock(interruptMutex);
        if (startingDriver) startingDriver->interrupt();
    }

    // Interrupt only this driver's pending start. Never call stop concurrently
    // with start or from a moonlight callback. Repeated interrupts cover the
    // library resetting its interruption flag at the beginning of start().
    struct Driver final : DeckMoonlightConnectionDriver {
        Shared& shared;
        DeckMoonlightConnectionDriver& delegate;
        bool startAttempted = false;
        Driver(Shared& shared, DeckMoonlightConnectionDriver& delegate)
            : shared(shared), delegate(delegate) {}
        int start(SERVER_INFORMATION& server, STREAM_CONFIGURATION& config,
            CONNECTION_LISTENER_CALLBACKS& listener, DECODER_RENDERER_CALLBACKS& video,
            AUDIO_RENDERER_CALLBACKS& audio, void* context) override {
            {
                const std::lock_guard lock(shared.interruptMutex);
                if (shared.cancelled) return -1;
                startAttempted = true;
                shared.startingDriver = &delegate;
            }
            // Always unpublish the pointer, including when an injected driver throws.
            struct Clear {
                Shared& shared;
                ~Clear() {
                    const std::lock_guard lock(shared.interruptMutex);
                    shared.startingDriver = nullptr;
                }
            } clear{shared};
            try {
                return delegate.start(server, config, listener, video, audio, context);
            } catch (...) {
                // Keep the core's one-stop-per-start rule on the exception seam.
                stop();
                throw;
            }
        }
        void stop() override {
            if (startAttempted) {
                startAttempted = false;
                delegate.stop();
            }
        }
    };
};

DeckNativeSessionController::DeckNativeSessionController(bool enabled, DeckNativeTargetResolver resolver,
    DeckMoonlightConnectionDriver& driver, DeckControllerSend sendController, QObject* parent, DeckDesktopSend sendDesktop)
    : QObject(parent), enabled_(enabled), resolver_(std::move(resolver)), driver_(driver), sendController_(std::move(sendController)),
      sendDesktop_(std::move(sendDesktop)), state_(publicState("idle", "Ready to preview video and audio in Nova.", false)) {
    inputClock_.start();
    timer_.setInterval(16);
    connect(&timer_, &QTimer::timeout, this, &DeckNativeSessionController::poll);
    reconnectTimer_.setSingleShot(true);
    reconnectTimer_.setTimerType(Qt::PreciseTimer);
    connect(&reconnectTimer_, &QTimer::timeout, this, [this] {
        if (!reconnectPending_) return;
        const auto ticket = resumeTicket_;
        reconnectPending_ = false;
        if (ticket && !automaticSuppressed_ && inputFocused_ &&
            startRequest(ticket->hostId, ticket->gameId, ticket->configuration, ticket, true)) return;
        state_ = publicState("interrupted", "Reconnect paused. Return to the game when you're ready.", false);
        state_.insert("canReconnect", static_cast<bool>(ticket));
        emit stateChanged();
    });
}

DeckNativeSessionController::~DeckNativeSessionController() {
    emit rumbleRequested(0, 0);
    for (unsigned player = 0; player < 16; ++player) emit playerRumbleRequested(player, 0, 0);
    closeSession();
    timer_.stop();
    if (frameDelivery_) frameDelivery_->close();
    if (worker_) {
        shared_->cancelled = true;
        shared_->wake.notify_all();
        // Normal window close waits asynchronously in QML. This also covers
        // application quit and destruction without allowing a detached worker
        // to outlive the controller, driver, Qt application or decoder.
        while (!worker_->wait(20)) shared_->interrupt();
        delete worker_;
    }
}

void DeckNativeSessionController::setSurface(DeckQtQuickRhiVaapiItem* surface) {
    setPresentationSink(surface, surface, surface ? surface->composedFrames() : nullptr);
}

bool DeckNativeSessionController::setPresentationSink(QObject* lifetime, DeckQtQuickRhiPresentationSink* sink,
        std::shared_ptr<const std::atomic<std::uint64_t>> composed) {
    if (bool(lifetime) != bool(sink)) return false;
    if (busy() && lifetime && (presentationOwner_ != lifetime || presentationSink_ != sink || presentationFrames_ != composed)) return false;
    if (presentationOwner_ == lifetime && presentationSink_ == sink && presentationFrames_ == composed) return true;
    if (presentationOwner_ && presentationSink_) presentationSink_->presentVaapiSurface({});
    hudMetrics_.reset();
    presentationOwner_ = lifetime;
    presentationSink_ = sink;
    presentationFrames_ = std::move(composed);
    return true;
}

bool DeckNativeSessionController::setDisplayRateLimitReader(std::function<int()> reader) {
    if (busy() || !reader) return false;
    displayRateLimit_ = std::move(reader);
    return true;
}

bool DeckNativeSessionController::setTargetResolver(DeckNativeTargetResolver resolver) {
    if (busy()) return false;
    resolver_ = std::move(resolver);
    return true;
}

bool DeckNativeSessionController::capturesGamepad() const {
    return !sleeping_ && busy() && state_.value("phase") == "active" && !controlsVisible_;
}

void DeckNativeSessionController::sendDesktopInput(const DeckDesktopPacket& packet) {
    if (!shared_ || !capturesDesktopInput() || !packet.valid() || packet.kind == DeckDesktopPacket::ReleaseAll) return;
    const std::lock_guard lock(shared_->mutex);
    if (!shared_->acceptingInput || shared_->cancelled || shared_->done || shared_->inputOverflow) return;
    auto& queue = shared_->desktopInputs;
    // Only adjacent motion is replaceable: preserve click/scroll/key ordering.
    if (packet.kind == DeckDesktopPacket::Position && !queue.empty() && queue.back().packet.kind == DeckDesktopPacket::Position)
        queue.back().packet = packet;
    else if (queue.size() >= 256) {
        shared_->inputOverflow = true;
        queue.clear(); ++shared_->desktopGeneration;
    } else queue.push_back({packet, shared_->desktopGeneration});
    shared_->wake.notify_all();
}

void DeckNativeSessionController::releaseDesktopInput() {
    if (!shared_) return;
    const std::lock_guard lock(shared_->mutex);
    ++shared_->desktopGeneration;
    shared_->desktopInputs.clear();
    if (shared_->acceptingInput && !shared_->cancelled && !shared_->done)
        shared_->desktopInputs.push_back({{}, shared_->desktopGeneration});
    shared_->wake.notify_all();
}

std::optional<DeckDesktopPacket> DeckNativeSessionController::desktopPosition(QPointF point, QSizeF viewport,
        DeckVideoScaleMode scale, bool clamp) const {
    const auto video = desktopVideo_.isEmpty()
        ? QSizeF(state_.value("videoWidth").toInt(), state_.value("videoHeight").toInt()) : desktopVideo_;
    return deckDesktopPosition(point, viewport, video, scale, clamp, desktopPixelAspect_);
}

QString DeckNativeSessionController::controllerHint() const {
    if (!controllerMask_) return "Connect a controller, or use a mouse and keyboard.";
    for (unsigned player = 0; player < 16; ++player)
        if ((controllerMask_ & (1u << player)) && controllerRouters_[player].awaitingNeutral())
            return "Release the buttons, sticks and triggers to continue.";
    // The normal shortcut uses Deck button glyphs in QML. This text is reserved
    // for actionable connection/neutral-input notices, even with hints hidden.
    return {};
}

void DeckNativeSessionController::routeController(DeckControllerRoute route, unsigned player) {
    if (player >= controllerRouters_.size()) return;
    const auto feedbackGuard = qScopeGuard([this] { syncRumble(); });
    if (route.openControls) showControls();
    if (!shared_ || route.packets.empty()) return;
    const std::lock_guard lock(shared_->mutex);
    if (!shared_->acceptingInput || shared_->cancelled || shared_->inputOverflow) return;
    if (route.discardPendingInput) {
        ++shared_->inputGeneration[player];
        std::erase_if(shared_->inputs, [player](const auto& input) { return input.packet.controllerNumber == player; });
    }
    for (auto packet : route.packets) {
        packet.controllerNumber = player;
        packet.activeMask = controllerMask_;
        if (!packet.connected) {
            ++shared_->inputGeneration[player];
            std::erase_if(shared_->inputs, [player](const auto& input) { return input.packet.controllerNumber == player; });
        }
        if (shared_->inputs.size() >= 256) {
            shared_->inputOverflow = true;
            shared_->inputs.clear();
            for (auto& generation : shared_->inputGeneration) ++generation;
            shared_->inputs.push_back({{{}, false}, shared_->inputGeneration[0]});
            break;
        }
        shared_->inputs.push_back({packet, shared_->inputGeneration[player]});
    }
    shared_->wake.notify_all();
}

void DeckNativeSessionController::syncRumble() {
    if (!shared_) return;
    std::uint16_t stop = 0;
    {
        const std::lock_guard lock(shared_->mutex);
        std::uint16_t allowed = 0;
        if (capturesGamepad() && inputFocused_ && shared_->rumbleEnabled && shared_->acceptingInput &&
            !shared_->cancelled && !shared_->done && !shared_->finishing) {
            for (unsigned player = 0; player < 16; ++player)
                if ((controllerMask_ & (1u << player)) && !controllerRouters_[player].awaitingNeutral())
                    allowed |= std::uint16_t(1u << player);
        }
        const auto changed = shared_->feedbackAllowed ^ allowed;
        stop = shared_->feedbackAllowed & ~allowed;
        shared_->feedbackAllowed = allowed;
        for (unsigned player = 0; player < 16; ++player)
            if (changed & (1u << player)) shared_->pendingRumble[player].reset();
    }
    for (unsigned player = 0; player < 16; ++player) if (stop & (1u << player)) {
        if (!player) emit rumbleRequested(0, 0);
        emit playerRumbleRequested(player, 0, 0);
    }
}

void DeckNativeSessionController::updateController(DeckControllerState state, bool connected) {
    updatePlayerController(0, state, connected);
}

void DeckNativeSessionController::updatePlayerController(unsigned player, DeckControllerState state, bool connected) {
    if (player >= controllerRouters_.size()) return;
    const auto before = controllerHint();
    physicalControllers_[player] = connected ? state : DeckControllerState{};
    state = withStickDeadzone(physicalControllers_[player], stickDeadzonePercent_);
    if (connected) controllerMask_ |= std::uint16_t(1u << player);
    else controllerMask_ &= ~std::uint16_t(1u << player);
    routeController(controllerRouters_[player].update(state, connected, inputClock_.elapsed()), player);
    if (connected && capturesGamepad() && inputFocused_ && !controllerRouters_[player].capturing())
        routeController(controllerRouters_[player].capture(true, inputClock_.elapsed()), player);
    if (!controllerMask_ && capturesGamepad()) showControls();
    if (before != controllerHint()) emit controlsChanged();
}

void DeckNativeSessionController::setInputFocus(bool focused) {
    inputFocused_ = focused;
    if (!focused) {
        showControls();
        if (reconnectPending_ || state_.value("automaticReconnect").toBool()) stop();
    }
}

void DeckNativeSessionController::showControls() {
    const bool changed = !controlsVisible_;
    controlsVisible_ = true;
    releaseDesktopInput();
    for (unsigned player = 0; player < 16; ++player)
        if (controllerMask_ & (1u << player)) routeController(controllerRouters_[player].capture(false, inputClock_.elapsed()), player);
    if (changed) emit controlsChanged();
}

void DeckNativeSessionController::resumeInput() {
    if (sleeping_ || !busy() || state_.value("phase") != "active" || !inputFocused_) return;
    controlsVisible_ = false;
    for (unsigned player = 0; player < 16; ++player)
        if (controllerMask_ & (1u << player)) routeController(controllerRouters_[player].capture(true, inputClock_.elapsed()), player);
    emit controlsChanged();
}

bool DeckNativeSessionController::start(const QString& hostId, const QString& gameId) {
    return startRequest(hostId, gameId, std::nullopt);
}

bool DeckNativeSessionController::startConfigured(const QString& hostId, const QString& gameId, const QVariantMap& values) {
    auto configuration = DeckPlayConfiguration::fromMap(values);
    if (!configuration || configuration->fps > displayRateLimit_()) return false;
    // Resolve the inherited setting once, before starting the worker. Edits
    // during a stream cannot change held-button meaning midway through a press.
    if (configuration->faceButtonLayout == "default")
        configuration->faceButtonLayout = DeckPlaySettings{}.defaultFaceButtonLayout();
    return startRequest(hostId, gameId, configuration);
}

bool DeckNativeSessionController::startRequest(const QString& hostId, const QString& gameId,
    std::optional<DeckPlayConfiguration> configuration, std::shared_ptr<const ResumeTicket> resume, bool automatic) {
    if (!enabled_ || sleeping_ || busy() || !resolver_) return false;
    if (!automatic) {
        reconnectPolicy_ = {};
        automaticSuppressed_ = false;
    }
    showControls();
    resumeTicket_.reset();
    shared_ = std::make_shared<Shared>();
    desktopVideo_ = {}; desktopPixelAspect_ = 1;
    hudMetrics_.reset(); hudSampleMs_ = hudReceivedMs_ = -1;
    hud_ = DeckHudMetrics::empty(); emit hudChanged();
    shared_->rumbleEnabled = resume ? resume->rumbleEnabled : DeckPlaySettings{}.rumbleEnabled();
    shared_->framePacing = resume ? resume->framePacing
        : deckFramePacing(DeckPlaySettings{}.framePacingMode()).value_or(DeckFramePacing::Latency);
    shared_->stickDeadzonePercent = resume ? resume->stickDeadzonePercent : DeckPlaySettings{}.stickDeadzonePercent();
    stickDeadzonePercent_ = shared_->stickDeadzonePercent;
    // A changed threshold must re-evaluate already held sticks before capture
    // can arm, including when no fresh joystick event arrives before Resume.
    for (unsigned player = 0; player < controllerRouters_.size(); ++player)
        routeController(controllerRouters_[player].update(withStickDeadzone(physicalControllers_[player], stickDeadzonePercent_),
            bool(controllerMask_ & (1u << player)), inputClock_.elapsed()), player);
    shared_->automaticAttempt = automatic ? reconnectPolicy_.attempts() : 0;
    frameDelivery_ = std::make_unique<DeckFrameDelivery>([this, current = std::weak_ptr<Shared>(shared_)](const auto& frame) {
        const auto session = current.lock();
        if (session && session == shared_ && !session->cancelled && !session->done && presentationOwner_ && presentationSink_) {
            desktopVideo_ = QSizeF(frame.width, frame.height);
            const auto* av = frame.frameLease ? frame.frameLease->frame() : nullptr;
            desktopPixelAspect_ = av && av->sample_aspect_ratio.num > 0 && av->sample_aspect_ratio.den > 0
                ? double(av->sample_aspect_ratio.num) / av->sample_aspect_ratio.den : 1;
            presentationSink_->presentVaapiSurface(frame);
        }
    });
    shared_->deliverFrame = frameDelivery_->publisher();
    shared_->configureFrames = frameDelivery_->configurator();
    shared_->publish("preparing", resume ? "Checking your existing game…" : "Preparing the selected game…");
    state_ = shared_->state;
    if (presentationOwner_ && presentationSink_) presentationSink_->presentVaapiSurface({});
    auto audio = resume ? resume->audio : DeckPlaySettings{}.audioConfiguration();
    // Android's Space worker path currently requests stereo irrespective of
    // the device's saved surround choice. Keep that exception explicit.
    if (polaris::isSpaceGame(gameId.toStdString())) audio.channels = 2;
    emit inputSessionStarted(static_cast<bool>(resume));
    worker_ = QThread::create([shared = shared_, resolver = resolver_, hostId, gameId, configuration, audio, resume = std::move(resume), displayRateLimit = displayRateLimit_, &driver = driver_, send = sendController_, desktop = sendDesktop_]() {
        run(shared, resolver, hostId, gameId, configuration, resume, audio, displayRateLimit, driver, send, desktop);
    });
    worker_->start();
    timer_.start();
    emit stateChanged();
    return true;
}

void DeckNativeSessionController::stop() {
    automaticSuppressed_ = true;
    if (reconnectPending_) {
        reconnectTimer_.stop();
        reconnectPending_ = false;
        state_ = publicState("interrupted", "Automatic reconnect cancelled. The game was not asked to quit.", false);
        state_.insert("canReconnect", static_cast<bool>(resumeTicket_));
        emit stateChanged();
        return;
    }
    requestStop(false);
}

bool DeckNativeSessionController::disconnectFromHost() {
    const bool accepted = requestStop(true);
    if (accepted) automaticSuppressed_ = true;
    return accepted;
}

bool DeckNativeSessionController::resumeDisconnected(const QString& hostId, const QString& gameId) {
    if (busy() || !resumeTicket_ || state_.value("phase") != "disconnected" ||
        hostId != resumeTicket_->hostId || gameId != resumeTicket_->gameId) return false;
    const auto ticket = resumeTicket_;
    return startRequest(hostId, gameId, ticket->configuration, ticket);
}

bool DeckNativeSessionController::reconnect(const QString& hostId, const QString& gameId) {
    if (busy() || !resumeTicket_ || state_.value("phase") != "interrupted" ||
        hostId != resumeTicket_->hostId || gameId != resumeTicket_->gameId) return false;
    const auto ticket = resumeTicket_;
    return startRequest(hostId, gameId, ticket->configuration, ticket);
}

void DeckNativeSessionController::closeSession() {
    if (!disconnectFromHost()) stop();
}

void DeckNativeSessionController::setSystemSleeping(bool sleeping) {
    if (sleeping_ == sleeping) return;
    sleeping_ = sleeping;
    showControls();
    if (sleeping) {
        automaticSuppressed_ = true;
        if (worker_) {
            {
                const std::lock_guard lock(shared_->mutex);
                // Sleep cannot replace an already chosen End game or exit.
                if (!shared_->cancelled && !shared_->done) shared_->suspendRequested = true;
            }
            if (!requestStop(true)) requestStop(false);
        } else if (reconnectPending_) {
            stop();
            state_ = publicState("disconnected", "Stream paused for sleep. Resume when you're ready.", false);
            state_.insert("canResume", static_cast<bool>(resumeTicket_));
        }
    }
    // Wake is an explicit Resume action, never an automatic replay of a
    // launch, retry, held input or an exit that was already in progress.
    emit stateChanged();
    if (sleeping && !worker_) emit sleepPreparationFinished();
}

bool DeckNativeSessionController::requestStop(bool keepHostRunning) {
    if (!worker_ || shared_->done || shared_->cancelled) return false;
    {
        const std::lock_guard lock(shared_->mutex);
        if (shared_->done || shared_->cancelled || shared_->finishing) return false;
        if (keepHostRunning && (shared_->state.value("phase") != "active" ||
            !shared_->state.value("canDisconnect").toBool())) return false;
        // A pending connection may become active before the GUI observes it.
        // Cancel is still not the confirmed End game action in that interval.
        if (!keepHostRunning && state_.value("phase") != "active" &&
            shared_->state.value("phase") == "active" && shared_->state.value("canDisconnect").toBool())
            keepHostRunning = true;
        // First explicit exit wins. A close or repeated click cannot upgrade a
        // pending disconnect to host termination, or undo an explicit End game.
        shared_->keepHostRunning = keepHostRunning;
        shared_->endGameRequested = !keepHostRunning && shared_->state.value("phase") == "active";
        shared_->cancelled = true;
    }
    showControls();
    if (frameDelivery_) frameDelivery_->close();
    shared_->wake.notify_all();
    shared_->interrupt();
    state_ = publicState("stopping", keepHostRunning ? "Disconnecting from the game…"
        : shared_->endGameRequested ? "Ending the game…" : "Cancelling the connection…", true);
    if (presentationOwner_ && presentationSink_) presentationSink_->presentVaapiSurface({});
    emit stateChanged();
    return true;
}

bool DeckNativeSessionController::setLiveTuningEnabled(bool enabled) {
    if (!shared_ || !worker_ || !inputFocused_ || sleeping_ || !controlsVisible_ || state_.value("phase") != "active") return false;
    const std::lock_guard lock(shared_->mutex);
    if (shared_->cancelled || shared_->done || shared_->finishing || !shared_->setTuning) return false;
    return shared_->setTuning(enabled);
}

bool DeckNativeSessionController::setFixedBitrate(int bitrateKbps) {
    if (!shared_ || !worker_ || !inputFocused_ || sleeping_ || !controlsVisible_ || state_.value("phase") != "active") return false;
    const std::lock_guard lock(shared_->mutex);
    if (shared_->cancelled || shared_->done || shared_->finishing || !shared_->setBitrate) return false;
    return shared_->setBitrate(bitrateKbps);
}
bool DeckNativeSessionController::refreshDiagnostics() {
    if (!shared_ || !worker_ || !inputFocused_ || sleeping_ || !controlsVisible_ || state_.value("phase") != "active") return false;
    const std::lock_guard lock(shared_->mutex);
    if (shared_->cancelled || shared_->done || shared_->finishing || !shared_->refreshDiagnostics) return false;
    return shared_->refreshDiagnostics();
}
bool DeckNativeSessionController::setSyncProfile(const QString& hostId, const QString& display, int bitrateKbps, bool clear, const QVariantMap& reviewed) {
    if (!shared_ || !worker_ || !inputFocused_ || sleeping_ || !controlsVisible_ || state_.value("phase") != "active") return false;
    const std::lock_guard lock(shared_->mutex);
    if (hostId.isEmpty() || hostId != shared_->syncHostId || shared_->cancelled || shared_->done || shared_->finishing || !shared_->setSync) return false;
    return shared_->setSync(display, bitrateKbps, clear, reviewed);
}

QVariantMap DeckNativeSessionController::exportSupportReport() {
    const QVariantMap unavailable{{"saved", false}, {"message", "Open Doctor during the active stream to save its readings."}};
    if (!shared_ || !worker_ || !inputFocused_ || sleeping_ || !controlsVisible_ || state_.value("phase") != "active") return unavailable;
    { const std::lock_guard lock(shared_->mutex);
      if (shared_->cancelled || shared_->done || shared_->finishing) return unavailable; }
    return saveDeckSupportReport(hud_, deckSupportReportDirectory());
}

bool DeckNativeSessionController::applyDoctorFix() {
    if (!shared_ || !worker_ || !inputFocused_ || sleeping_ || !controlsVisible_ || state_.value("phase") != "active") return false;
    const std::lock_guard lock(shared_->mutex);
    return !shared_->cancelled && !shared_->done && !shared_->finishing && shared_->applyDoctorFix && shared_->applyDoctorFix();
}
bool DeckNativeSessionController::undoDoctorFix() {
    if (!shared_ || !worker_ || !inputFocused_ || sleeping_ || !controlsVisible_ || state_.value("phase") != "active") return false;
    const std::lock_guard lock(shared_->mutex);
    return !shared_->cancelled && !shared_->done && !shared_->finishing && shared_->undoDoctorFix && shared_->undoDoctorFix();
}
bool DeckNativeSessionController::checkDoctorResult() {
    if (!shared_ || !worker_ || !inputFocused_ || sleeping_ || !controlsVisible_ || state_.value("phase") != "active") return false;
    const std::lock_guard lock(shared_->mutex);
    return !shared_->cancelled && !shared_->done && !shared_->finishing && shared_->checkDoctorResult && shared_->checkDoctorResult();
}

void DeckNativeSessionController::poll() {
    if (!worker_) return;
    for (unsigned player = 0; player < 16; ++player)
        if (controllerMask_ & (1u << player)) routeController(controllerRouters_[player].tick(inputClock_.elapsed()), player);
    if (shared_->cancelled) shared_->interrupt();
    const bool finished = worker_->isFinished();
    if (finished) worker_->wait();
    QVariantMap next;
    std::optional<DeckHudSample> hudSample;
    QVariantMap hostHud;
    std::array<std::optional<DeckRumble>, 16> rumble;
    {
        const std::lock_guard lock(shared_->mutex);
        next = shared_->state;
        hudSample = shared_->hud;
        hostHud = shared_->hostHud;
        for (unsigned player = 0; player < 16; ++player) {
            if (shared_->pendingRumble[player]) {
                rumble[player] = (shared_->feedbackAllowed & (1u << player)) && !shared_->cancelled && next.value("phase") == "active"
                    ? *shared_->pendingRumble[player] : DeckRumble{};
                shared_->pendingRumble[player].reset();
            }
        }
    }
    for (unsigned player = 0; player < 16; ++player) if (rumble[player]) {
        if (!player) emit rumbleRequested(rumble[player]->low, rumble[player]->high);
        emit playerRumbleRequested(player, rumble[player]->low, rumble[player]->high);
    }
    if (!shared_->done) {
        if (shared_->cancelled) next = state_;
    }
    // Remain busy until the complete worker (including session destructors)
    // has ended. No stale completion can be applied to a subsequent run.
    if (finished) {
        delete worker_;
        worker_ = nullptr;
        timer_.stop();
        frameDelivery_.reset();
        resumeTicket_ = shared_->resumeTicket;
        if (shared_->suspendRequested && resumeTicket_) {
            next = publicState("disconnected", "Stream paused for sleep. Resume when you're ready.", false);
        }
        next.insert("canResume", resumeTicket_ && next.value("phase") == "disconnected");
        next.insert("canReconnect", resumeTicket_ && next.value("phase") == "interrupted");
        if (presentationOwner_ && presentationSink_) presentationSink_->presentVaapiSurface({});
        if (shared_->automaticRetry && resumeTicket_ && resumeTicket_->automaticReconnect &&
            !automaticSuppressed_ && !sleeping_ && inputFocused_) {
            if (const auto delay = reconnectPolicy_.nextDelay()) {
                reconnectPending_ = true;
                next = publicState("reconnecting", "Checking whether your game is still available…", true);
                next.insert("automaticReconnect", true);
                next.insert("reconnectAttempt", reconnectPolicy_.attempts());
                reconnectTimer_.start(*delay);
            } else {
                next.insert("copy", "Automatic reconnect stopped after four attempts. Check your connection, then reconnect when you're ready.");
            }
        }
    } else {
        next.insert("busy", true);
    }
    if (next.value("phase") == "active" && presentationOwner_ && presentationFrames_) {
        const auto frames = presentationFrames_->load();
        if (state_.value("phase") != "active") reconnectPolicy_.beginStream(frames, inputClock_.elapsed());
        else reconnectPolicy_.sample(frames, inputClock_.elapsed());
    }
    auto nextHud = hud_;
    if (!finished && !shared_->cancelled && next.value("phase") == "active" && hudSample) {
        if (hudSample->atMs != hudSampleMs_) {
            hudSampleMs_ = hudSample->atMs;
            hudReceivedMs_ = inputClock_.elapsed();
            hudSample->compositionAvailable = presentationOwner_ && bool(presentationFrames_);
            hudSample->composed = hudSample->compositionAvailable ? presentationFrames_->load() : 0;
            nextHud = hudMetrics_.sample(*hudSample);
        } else if (inputClock_.elapsed() - hudReceivedMs_ > 2500) {
            hudMetrics_.reset(); nextHud = DeckHudMetrics::empty();
        }
    } else { hudMetrics_.reset(); nextHud = DeckHudMetrics::empty(); }
    const auto hostView = !finished && !shared_->cancelled && next.value("phase") == "active"
        ? hostHud : DeckHudHostReducer::unavailable();
    for (auto it = hostView.cbegin(); it != hostView.cend(); ++it) nextHud.insert(it.key(), it.value());
    if (nextHud != hud_) { hud_ = std::move(nextHud); emit hudChanged(); }
    if (next != state_) {
        state_ = next;
        if (state_.value("phase") != "active") showControls();
        emit stateChanged();
    }
    if (finished && sleeping_) emit sleepPreparationFinished();
}

void DeckNativeSessionController::run(const std::shared_ptr<Shared>& shared,
    const DeckNativeTargetResolver& resolver, const QString& hostId,
    const QString& gameId, const std::optional<DeckPlayConfiguration>& configuration,
    const std::shared_ptr<const ResumeTicket>& resume,
    const DeckAudioConfiguration& audio,
    const std::function<int()>& displayRateLimit,
    DeckMoonlightConnectionDriver& driver, const DeckControllerSend& sendController, const DeckDesktopSend& sendDesktop) {
    const std::unique_lock ownership(nativeConnectionMutex, std::try_to_lock);
    if (!ownership.owns_lock()) {
        shared->finish("failed", "Another native preview is still running.");
        return;
    }
    Shared::Driver cancellableDriver(*shared, driver);
    DeckGuardedStreamSessionPreviewProducer producer(cancellableDriver, *shared);
    producer.decodedFrameProducer().presentationHandoff().setSink(shared);
    DeckGuardedPreviewLifecycleGate gate(producer);
    std::optional<DeckNativeLaunchTarget> target;
    DeckSessionBuildResult built;
    bool gateOwnsLaunch = false;
    bool cleanupConfirmed = true;
    bool controllerSent = false;
    bool inputFailed = false;
    DeckDesktopLedger desktopLedger;
    bool activeOrdinary = false;
    std::shared_ptr<const ResumeTicket> activeTicket;
    std::unique_ptr<DeckHudHostObserver> hostObserver;
    auto cleanup = [&]() {
        // Cancellation aborts its bounded read before credentials/stream state
        // are torn down. No observer survives into a replacement connection.
        { const std::lock_guard lock(shared->mutex); shared->setTuning = {}; shared->setBitrate = {}; shared->setSync = {}; shared->refreshDiagnostics = {};
            shared->applyDoctorFix = {}; shared->undoDoctorFix = {}; shared->checkDoctorResult = {}; }
        hostObserver.reset();
        {
            const std::lock_guard lock(shared->mutex);
            shared->acceptingInput = false;
            shared->feedbackAllowed = false;
            for (auto& rumble : shared->pendingRumble) rumble = DeckRumble{};
            shared->inputs.clear();
            shared->desktopInputs.clear();
        }
        // All input and stream teardown run on this worker. Never send on a
        // subsequent connection or after LiStopConnection has destroyed input.
        if (desktopLedger.release(sendDesktop) != 0) inputFailed = true;
        if (controllerSent) {
            controllerSent = false;
            if (sendController({{}, false}) != 0) inputFailed = true;
        }
        if (gateOwnsLaunch) {
            // Once an ordinary game is connected, losing media or input must
            // never turn into Quit. Pending new launches still owe cleanup.
            const bool preserveGame = shared->keepHostRunning ||
                ((built.resumed || activeOrdinary) && !shared->endGameRequested);
            const auto result = preserveGame ? gate.disconnect() : gate.stop();
            if (result.hostCancelRequested) cleanupConfirmed = result.hostCancelled;
        } else if (built.hostSessionStarted && !built.resumed && target) {
            cleanupConfirmed = requestHostSessionCancel(target->fetch, built.connectionInfo.hostSessionToken).cancelled;
        }
    };
    auto finish = [&](const QString& phase, QString copy) {
        if (phase == "cancelled" && shared->suspendRequested && resume && !built.sessionSelectionRejected)
            shared->resumeTicket = resume;
        if (!cleanupConfirmed) copy += " The host has not confirmed that the game ended; check the host before retrying.";
        shared->finish(phase, copy);
    };
    try {
        if (!shared->cancelled) target = resolver(hostId, gameId);
        if (shared->cancelled) {
            finish("cancelled", "Preview cancelled.");
            return;
        }
        if (!target || target->appId <= 0 || !target->fetch || target->serverAddress.empty()) {
            finish("failed", "This game needs a paired host and a matching library entry.");
            return;
        }
        if (resume && (target->appId != resume->appId || target->appUuid != resume->appUuid)) {
            finish("failed", "This game changed in the library. Return and review it again before playing.");
            return;
        }
        if (shared->automaticAttempt && !target->automaticReconnect) {
            finish("failed", "Automatic reconnect is no longer available for this PC. Return to the library and review it again.");
            return;
        }
        if (configuration) {
            target->request.width = configuration->width;
            target->request.height = configuration->height;
            target->request.fps = configuration->fps;
            target->request.bitrateKbps = configuration->bitrateKbps;
        }
        target->request.profilePreference.clear(); target->request.encoderBackend.clear();
        if (!resume && configuration && (configuration->profilePreference != "auto" || !configuration->encoderBackend.isEmpty())) {
            if (!target->authorizeSetup || !target->authorizeSetup(configuration->profilePreference, configuration->encoderBackend,
                    [shared] { return shared->cancelled.load(); }) || !resolver(hostId, gameId)) {
                finish(shared->cancelled ? "cancelled" : "failed", "The preset or encoder is no longer available. Refresh this PC and review Play Setup.");
                return;
            }
            target->request.profilePreference = configuration->profilePreference.toStdString();
            target->request.encoderBackend = configuration->encoderBackend.toStdString();
        }
        target->request.streamMode.clear();
        target->request.audioConfiguration = audio.channels == 8 ? AUDIO_CONFIGURATION_71_SURROUND
            : audio.channels == 6 ? AUDIO_CONFIGURATION_51_SURROUND : AUDIO_CONFIGURATION_STEREO;
        target->request.playHostAudio = audio.playHostAudio;
        if (target->request.fps > displayRateLimit()) {
            finish("failed", "The display rate changed. Review Play Setup again before starting.");
            return;
        }
        const auto capabilities = target->verifyStreamCapabilities
            ? target->verifyStreamCapabilities([shared]() { return shared->cancelled.load(); })
            : std::optional<DeckStreamCapabilities>{target->streamCapabilities};
        if (!capabilities && resume && !shared->cancelled && target->transientCapabilityFailure &&
            target->transientCapabilityFailure()) {
            shared->resumeTicket = resume;
            shared->automaticRetry = true;
            finish("interrupted", "This PC did not answer. Check your connection and try reconnecting.");
            return;
        }
        if (!capabilities || !capabilities->supports(target->request.width, target->request.height, target->request.fps) ||
            (target->verifyStreamCapabilities && !resolver(hostId, gameId))) {
            finish(shared->cancelled ? "cancelled" : "failed", shared->cancelled ? "Preview cancelled."
                : "These stream settings could not be verified. Refresh this PC and review Play Setup again.");
            return;
        }
        const auto videoSupport = target->probeVideoSupport ? target->probeVideoSupport() : DeckVideoDecodeSupport{};
        target->request.videoFormat = selectSdrVideoFormat(configuration ? configuration->videoCodec.toStdString() : "h264",
            capabilities->h264, capabilities->hevc && !polaris::isSpaceGame(gameId.toStdString()), videoSupport,
            target->request.width, target->request.height);
        if (!target->request.videoFormat) {
            finish("failed", "The selected video codec is no longer available. Review Play Setup again before starting.");
            return;
        }
        if (!resume && configuration && configuration->launchMode != "default") {
            const auto mode = configuration->launchMode.toStdString();
            if (!target->authorizeLaunchMode || !target->authorizeLaunchMode(mode, [shared]() { return shared->cancelled.load(); })
                || !resolver(hostId, gameId)) {
                finish(shared->cancelled ? "cancelled" : "failed", shared->cancelled ? "Preview cancelled."
                    : "That launch mode could not be verified. Refresh this PC and review Play Setup again.");
                return;
            }
            target->request.streamMode = mode;
        }
        if (shared->cancelled) { finish("cancelled", "Preview cancelled."); return; }
        if (target->request.fps > displayRateLimit()) {
            finish("failed", "The display rate changed. Review Play Setup again before starting.");
            return;
        }
        if (!shared->configureFrames(shared->framePacing, target->request.fps)) {
            finish(shared->cancelled ? "cancelled" : "failed", "Video frame delivery could not be prepared. Try starting again.");
            return;
        }
        shared->publish("launching", resume ? "Resuming your game…" : "Starting or resuming the selected game…");
        built = buildStreamConnection(target->fetch, target->serverAddress,
            launchRequestForStream(target->request, target->appId, target->appUuid),
            generateStreamKeys(), [shared, &displayRateLimit, fps = target->request.fps]() {
                return shared->cancelled.load() || fps > displayRateLimit();
            }, resume ? DeckSessionStartMode::ResumeOnly : polaris::isSpaceGame(gameId.toStdString())
                ? DeckSessionStartMode::Launch : DeckSessionStartMode::PlayOrResume,
            resume ? resume->sessionToken : std::string{});
        if (!built.ok || shared->cancelled) {
            cleanup();
            if (resume && built.retryableTransportFailure && !shared->cancelled) {
                shared->resumeTicket = resume;
                shared->automaticRetry = true;
                finish("interrupted", "This PC did not answer. Check your connection and try reconnecting.");
                return;
            }
            const DeckSessionFailure failure{
                .cancelled = shared->cancelled,
                .displayRateChanged = target->request.fps > displayRateLimit(),
                .sessionSelectionRejected = built.sessionSelectionRejected,
                .launchRefused = built.launchRefused,
                .resumed = built.resumed,
                .builderError = built.error,
                .hostMessage = built.launchStatusMessage,
            };
            finish(shared->cancelled ? "cancelled" : "failed",
                QString::fromStdString(deckSessionFailureMessage(failure)));
            return;
        }
        shared->publish("connecting", "Connecting video and audio…");
        DeckOperatorStartAuthorizationPolicy authorization;
        authorization.authorizeStart("deck-native-preview-user-start");
        gateOwnsLaunch = true;
        const auto started = gate.startAuthorizedHostSession(authorization.snapshot(), target->request,
            built.connectionInfo, target->fetch, !built.resumed);
        if (started.hostCancelRequested) cleanupConfirmed = started.hostCancelled;
        const bool active = started.networkStarted;
        if (active && !shared->cancelled) {
            if (target->hostTelemetry && !built.connectionInfo.hostSessionToken.empty()) {
                try {
                    hostObserver = std::make_unique<DeckHudHostObserver>(target->hostTelemetry,
                        DeckHudHostContext{target->appId, QString::fromStdString(target->appUuid),
                            QString::fromStdString(built.connectionInfo.hostSessionToken)}, DeckHudHostTiming{},
                        [weak = std::weak_ptr<Shared>(shared)] {
                            const auto current = weak.lock();
                            return !current || current->cancelled || current->done;
                        });
                    const std::lock_guard lock(shared->mutex);
                    shared->setTuning = [observer = hostObserver.get()](bool enabled) { return observer->setLiveTuningEnabled(enabled); };
                    shared->setBitrate = [observer = hostObserver.get()](int kbps) { return observer->setFixedBitrate(kbps); };
                    shared->setSync = [observer = hostObserver.get()](const QString& display, int kbps, bool clear, const QVariantMap& reviewed) {
                        return observer->setSyncProfile(display, kbps, clear, reviewed);
                    };
                    shared->syncHostId = hostId;
                    shared->refreshDiagnostics = [observer = hostObserver.get()] { return observer->refreshDiagnostics(); };
                    shared->applyDoctorFix = [observer = hostObserver.get()] { return observer->applyDoctorFix(); };
                    shared->undoDoctorFix = [observer = hostObserver.get()] { return observer->undoDoctorFix(); };
                    shared->checkDoctorResult = [observer = hostObserver.get()] { return observer->checkDoctorResult(); };
                } catch (...) { /* Optional observation cannot fail the stream. */ }
            }
            activeOrdinary = !polaris::isSpaceGame(gameId.toStdString());
            { const std::lock_guard lock(shared->mutex); shared->acceptingInput = true; }
            if (!polaris::isSpaceGame(gameId.toStdString()) && !built.connectionInfo.hostSessionToken.empty()) {
                activeTicket = std::make_shared<ResumeTicket>(ResumeTicket{hostId, gameId, configuration,
                    target->appId, target->appUuid, built.connectionInfo.hostSessionToken, target->automaticReconnect, audio, shared->rumbleEnabled, shared->framePacing, shared->stickDeadzonePercent});
            }
            // Android's Space leave action ends its session. Keep detach scoped
            // to ordinary games until Deck has the full Space lifecycle.
            shared->publish("active", built.resumed ? "Game resumed. Continue to send controls to the game."
                : "Native stream connected. Continue to send controls to the game.",
                !polaris::isSpaceGame(gameId.toStdString()));
            { const std::lock_guard lock(shared->mutex);
                shared->state.insert("videoWidth", target->request.width);
                shared->state.insert("videoHeight", target->request.height);
            }
        }
        QElapsedTimer hudClock; hudClock.start();
        qint64 lastHudSample = -1000;
        while (active && !shared->cancelled && gate.sessionState() == DeckStreamSessionState::Active) {
            if (hudClock.elapsed() - lastHudSample >= 1000) {
                lastHudSample = hudClock.elapsed();
                const auto renderer = producer.rendererLifecycle();
                DeckHudSample sample;
                sample.atMs = lastHudSample;
                sample.incoming = renderer.incomingFrames; sample.bytes = renderer.videoBytes;
                sample.decoded = std::max(0, renderer.decodedHardwareFrames);
                sample.hostLatencySamples = renderer.hostLatencySamples;
                sample.hostLatencyTenths = renderer.hostLatencyTenths;
                sample.width = renderer.width; sample.height = renderer.height;
                sample.targetFps = renderer.redrawRate;
                sample.codec = renderer.videoFormat == VIDEO_FORMAT_H264 ? "H.264"
                    : renderer.videoFormat == VIDEO_FORMAT_H265 ? "HEVC"
                    : renderer.videoFormat == VIDEO_FORMAT_H265_MAIN10 ? "HEVC10" : "";
                std::uint32_t rtt = 0, variation = 0;
                if (driver.estimatedRtt(rtt, variation)) { sample.rttMs = rtt; sample.rttVariationMs = variation; }
                const std::lock_guard lock(shared->mutex);
                shared->hud = std::move(sample);
            }
            const auto audioStatus = producer.audioLifecycle();
            const QString audioCopy = !audioStatus.initCalls ? QString{} : audioStatus.outputRecovering
                ? audioStatus.outputReady ? "Audio is waiting for an output. Check your SteamOS audio output."
                    : "Reconnecting audio. Video and controls can continue."
                : !audioStatus.active ? "Audio is unavailable. Check your SteamOS audio output, then reconnect the stream."
                : QString{};
            std::deque<Shared::Input> inputs;
            std::deque<Shared::DesktopInput> desktopInputs;
            const auto observedHost = hostObserver ? hostObserver->snapshot() : DeckHudHostReducer::unavailable();
            {
                std::unique_lock lock(shared->mutex);
                shared->hostHud = observedHost;
                if (shared->state.value("phase") == "active") shared->state.insert("audioCopy", audioCopy);
                shared->wake.wait_for(lock, std::chrono::milliseconds(50), [&]() {
                    return shared->cancelled.load() || !shared->inputs.empty() || !shared->desktopInputs.empty() || shared->inputOverflow;
                });
                inputs.swap(shared->inputs);
                desktopInputs.swap(shared->desktopInputs);
                inputFailed = shared->inputOverflow;
            }
            for (const auto& input : inputs) {
                if (shared->cancelled || gate.sessionState() != DeckStreamSessionState::Active) break;
                {
                    const std::lock_guard lock(shared->mutex);
                    if (input.generation != shared->inputGeneration[input.packet.controllerNumber]) continue;
                }
                controllerSent = true;
                if (sendController(withFaceButtonLayout(input.packet,
                        configuration && configuration->faceButtonLayout == "positions")) != 0) {
                    inputFailed = true; break;
                }
            }
            if (inputFailed) break;
            for (const auto& input : desktopInputs) {
                if (shared->cancelled || gate.sessionState() != DeckStreamSessionState::Active) break;
                {
                    const std::lock_guard lock(shared->mutex);
                    if (input.generation != shared->desktopGeneration) continue;
                }
                if (desktopLedger.deliver(input.packet, sendDesktop) != 0) { inputFailed = true; break; }
            }
            if (inputFailed) break;
        }
        const auto connection = gate.connectionStatus();
        {
            // A close/Stop while transport teardown blocks cannot change a
            // detected interruption into an explicit End game.
            const std::lock_guard lock(shared->mutex);
            shared->finishing = true;
            shared->state = publicState("stopping", shared->keepHostRunning ||
                ((built.resumed || activeOrdinary) && !shared->endGameRequested)
                    ? "Closing the stream without ending the game…"
                    : "Ending the preview and cleaning up the host session…", true);
        }
        const bool interrupted = activeOrdinary && !shared->cancelled &&
            (inputFailed || !connection.terminated || connection.terminationErrorCode != ML_ERROR_GRACEFUL_TERMINATION);
        cleanup();
        if (interrupted) {
            shared->resumeTicket = std::move(activeTicket);
            shared->automaticRetry = !inputFailed && connection.terminated &&
                connection.terminationErrorCode != ML_ERROR_PROTECTED_CONTENT &&
                connection.terminationErrorCode != ML_ERROR_FRAME_CONVERSION;
            finish("interrupted", shared->resumeTicket
                ? inputFailed ? "Game input was interrupted. Nova did not ask the PC to end the game. Reconnect when you're ready."
                    : "The connection was interrupted. Nova did not ask the PC to end the game. Check your connection, then reconnect."
                : "The connection was interrupted. Nova did not ask the PC to end the game. Return to the library and check this PC.");
        }
        else if (!active && resume && built.ok && !shared->cancelled) {
            // Ownership was verified, but transport setup failed. A manual
            // retry must verify the exact session again; never fall back to Launch.
            shared->resumeTicket = resume;
            finish("interrupted", "Couldn't reconnect to the game. Nova did not ask the PC to end it. Check your connection and try again.");
        }
        else if (inputFailed) finish("failed", "Game input could not be delivered. The preview was stopped; reconnect and try again.");
        else if (shared->cancelled && shared->keepHostRunning) {
            shared->resumeTicket = std::move(activeTicket);
            finish("disconnected", "Disconnected without ending the game.");
        }
        else if (shared->cancelled) finish(active ? "stopped" : "cancelled",
            built.resumed && !shared->endGameRequested ? "Resume cancelled without ending the game."
            : active ? "Game ended." : "Preview cancelled.");
        else if (!active || (connection.terminated && connection.terminationErrorCode != 0))
            finish("failed", "The native connection ended unexpectedly. Check the host and try again.");
        else finish("stopped", "The host ended the preview.");
    } catch (...) {
        // Raw transport/decoder exception text can include private material.
        try { cleanup(); } catch (...) { cleanupConfirmed = false; }
        finish("failed", "The native preview could not finish.");
    }
}

} // namespace nova::deck::runtime
