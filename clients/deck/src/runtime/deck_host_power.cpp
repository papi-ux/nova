#include "runtime/deck_host_power.h"
#include <atomic>
#include <algorithm>
#include <mutex>
#include <thread>

namespace nova::deck::runtime {
using namespace polaris;
namespace {
QString unavailable(const DeckPolarisCapabilities& caps) {
    if (!caps.hostSleep) return "This PC doesn't support Sleep Host.";
    if (!caps.hostPower.enabled) return "Sleep Host is turned off on this PC. Enable Allow Clients To Sleep This Host in Polaris.";
    if (!caps.hostPower.supported) return caps.hostPower.blockedMessage.empty()
        ? "This PC cannot sleep right now." : QString::fromStdString(caps.hostPower.blockedMessage);
    if (!caps.hostPower.permitted) return "This device isn't permitted to put the PC to sleep.";
    return {};
}
QString readFailure(DeckPolarisRequestStatus status) {
    if (status == DeckPolarisRequestStatus::CertMismatch) return "This PC's certificate changed. Check its pairing before continuing.";
    if (status == DeckPolarisRequestStatus::Unauthorized) return "This PC no longer permits this pairing.";
    return "Couldn't check this PC. Check the connection, then try again.";
}
}
struct DeckHostPowerController::Job {
    std::atomic_bool cancelled{false}, sent{false};
    std::mutex mutex;
    QString phase = "checking", copy = "Checking this PC…";
    bool eligible = false;
    unsigned generation = 0;
    void result(QString next, QString message, bool allowed = false) {
        const std::lock_guard lock(mutex);
        phase = std::move(next); copy = std::move(message); eligible = allowed;
    }
};
DeckHostPowerController::DeckHostPowerController(QObject* parent, DeckHostPowerTiming timing)
    : QObject(parent), timing_(timing) {
    timing_.holdMs = std::max(1, timing_.holdMs);
    timing_.graceMs = std::max(1, timing_.graceMs);
    timing_.confirmAttempts = std::clamp(timing_.confirmAttempts, 1, 10);
    timing_.confirmIntervalMs = std::clamp(timing_.confirmIntervalMs, 1, 1000);
    connect(&timer_, &QTimer::timeout, this, &DeckHostPowerController::tick);
    timer_.setInterval(20);
    publish("idle", "Choose Sleep Host to check this PC.");
}
DeckHostPowerController::~DeckHostPowerController() {
    if (job_) job_->cancelled = true;
    if (worker_) { worker_->wait(); delete worker_; }
}
bool DeckHostPowerController::busy() const {
    return worker_ || phase_ == "holding" || phase_ == "countdown";
}
void DeckHostPowerController::publish(QString phase, QString copy, bool eligible, double progress, int remaining) {
    phase_ = std::move(phase);
    eligible_ = eligible;
    if (busy() && !timer_.isActive()) timer_.start();
    else if (!busy()) timer_.stop();
    const QVariantMap next{{"phase", phase_}, {"copy", copy}, {"hostName", hostName_},
        {"busy", busy()}, {"canHold", eligible_ && windowActive_ && !sessionActive_ && (phase_ == "ready" || phase_ == "holding")},
        {"progress", progress}, {"remaining", remaining}};
    if (next != state_) { state_ = next; emit stateChanged(); }
}
void DeckHostPowerController::setTarget(QString id, QString name, DeckHostPowerResolver resolver) {
    ++generation_;
    if (job_) job_->cancelled = true;
    hostId_ = std::move(id); hostName_ = std::move(name); resolver_ = std::move(resolver);
    verifiedClock_.invalidate();
    publish("idle", "Choose Sleep Host to check this PC.");
}
void DeckHostPowerController::setSessionActive(bool active) {
    if (sessionActive_ == active) return;
    sessionActive_ = active;
    if (active) {
        cancel();
        publish("unavailable", "Disconnect from your game before putting this PC to sleep.");
    } else publish("idle", "Check this PC before putting it to sleep.");
}
void DeckHostPowerController::setWindowActive(bool active) {
    if (windowActive_ == active) return;
    windowActive_ = active;
    if (!active) cancel();
    publish(phase_, state_.value("copy").toString(), eligible_);
}
bool DeckHostPowerController::refresh() {
    if (busy() || !windowActive_) return false;
    if (sessionActive_) { publish("unavailable", "Disconnect from your game before putting this PC to sleep."); return false; }
    if (!resolver_ || hostId_.isEmpty()) { publish("unavailable", "This PC isn't available for Sleep Host. Refresh the library or check its pairing."); return false; }
    startJob(false); return true;
}
bool DeckHostPowerController::beginHold() {
    if (busy() || phase_ != "ready" || !eligible_ || !windowActive_ || sessionActive_) return false;
    if (!verifiedClock_.isValid() || verifiedClock_.elapsed() > 60000) { refresh(); return false; }
    phaseClock_.start();
    publish("holding", "Keep holding to start the cancel countdown.", true);
    return true;
}
void DeckHostPowerController::releaseHold() {
    if (phase_ == "holding") publish("ready", "Hold to sleep this PC. You'll have five seconds to cancel.", true);
}
void DeckHostPowerController::cancel() {
    if (job_) job_->cancelled = true;
    if (phase_ == "holding" || phase_ == "countdown")
        publish("ready", "Sleep cancelled. No request was sent.", eligible_);
}
void DeckHostPowerController::startJob(bool sendSleep) {
    auto job = std::make_shared<Job>();
    job->generation = generation_; job_ = job;
    worker_ = QThread::create([job, resolver = resolver_, timing = timing_, sendSleep] {
        try {
            auto target = resolver ? resolver() : std::nullopt;
            const auto valid = [&] { return !job->cancelled && target && target->identityValid && target->identityValid(); };
            if (!valid()) { job->result("unavailable", "The selected PC or pairing changed. Refresh before continuing."); return; }
            const auto caps = target->capabilities();
            if (!valid()) { job->result("unavailable", "The selected PC or pairing changed. No sleep request was sent."); return; }
            if (!caps.ok()) { job->result("unavailable", readFailure(caps.status)); return; }
            const auto reason = unavailable(*caps.value);
            if (!reason.isEmpty()) { job->result("unavailable", reason); return; }
            if (!sendSleep) { job->result("ready", "Hold to sleep this PC. You'll have five seconds to cancel.", true); return; }
            // Re-read capabilities after the grace period, then check the exact
            // saved identity again immediately before the single mutation.
            if (!valid()) return;
            job->sent = true;
            const auto receipt = target->sleep([job] { return job->cancelled.load(); });
            if (!receipt.ok() || !receipt.value->accepted) {
                const auto message = receipt.value ? QString::fromStdString(receipt.value->message) : QString{};
                job->result("failed", message.isEmpty()
                    ? "Couldn't confirm the sleep request. It will not be retried automatically." : message);
                return;
            }
            job->result("confirming", "The PC accepted sleep. Waiting for it to stop responding…");
            for (int attempt = 0; attempt < timing.confirmAttempts; ++attempt) {
                if (!valid()) { job->result("uncertain", "Sleep was accepted, but its outcome could not be confirmed."); return; }
                if (!target->reachable()) {
                    job->result("offline", "The PC accepted sleep and is no longer responding."); return;
                }
                for (int elapsed = 0; elapsed < timing.confirmIntervalMs && !job->cancelled; elapsed += 20)
                    std::this_thread::sleep_for(std::chrono::milliseconds(std::min(20, timing.confirmIntervalMs - elapsed)));
            }
            if (!valid()) { job->result("uncertain", "Sleep was accepted, but its outcome could not be confirmed."); return; }
            const auto power = target->power();
            const bool freshOutcome = power.ok() && power.value->lastAt > 0 && power.value->lastAt != caps.value->hostPower.lastAt;
            job->result("awake", freshOutcome && !power.value->lastMessage.empty()
                ? QString::fromStdString(power.value->lastMessage) : "The PC accepted the request but is still responding. Check it before trying again.");
        } catch (...) { job->result("failed", "Couldn't check the sleep outcome. No request will be retried automatically."); }
    });
    publish(sendSleep ? "requesting" : "checking", sendSleep ? "Rechecking permission before sending sleep…" : "Checking this PC…");
    worker_->start();
}
void DeckHostPowerController::tick() {
    if (phase_ == "holding") {
        const double progress = std::min(1.0, double(phaseClock_.elapsed()) / timing_.holdMs);
        if (progress < 1) publish("holding", state_.value("copy").toString(), true, progress);
        else { phaseClock_.restart(); publish("countdown", "Sleep will be sent after the countdown. You can still cancel.", true, 1, (timing_.graceMs + 999) / 1000); }
    } else if (phase_ == "countdown") {
        const auto remaining = timing_.graceMs - phaseClock_.elapsed();
        if (remaining > 0) publish("countdown", state_.value("copy").toString(), true, 1, (remaining + 999) / 1000);
        else if (windowActive_ && !sessionActive_) startJob(true);
        else cancel();
    }
    if (!worker_) return;
    const bool finished = worker_->isFinished();
    QString phase, copy; bool eligible;
    { const std::lock_guard lock(job_->mutex); phase = job_->phase; copy = job_->copy; eligible = job_->eligible; }
    if (finished) { worker_->wait(); delete worker_; worker_ = nullptr; }
    if (job_->generation != generation_) { if (finished) publish("idle", "Check the selected PC before continuing."); return; }
    if (job_->cancelled) {
        if (finished) publish("idle", job_->sent ? "The sleep outcome wasn't confirmed. No request will be retried." : "Cancelled. No sleep request was sent.");
        return;
    }
    if (finished) {
        if (eligible) verifiedClock_.start();
        publish(phase, copy, eligible);
    } else if (phase == "confirming") publish(phase, copy);
}
} // namespace nova::deck::runtime
