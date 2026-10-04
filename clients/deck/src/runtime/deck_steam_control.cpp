#include "deck_steam_control.h"
#include <atomic>
#include <thread>

namespace nova::deck::runtime {
struct DeckSteamControl::Job {
    std::atomic<bool> cancelled {false};
    QVariantMap result;
};
DeckSteamControl::DeckSteamControl(QObject* parent) : QObject(parent) {}
DeckSteamControl::~DeckSteamControl() {
    if (job_) job_->cancelled = true;
    if (worker_) { worker_->wait(); delete worker_; }
}
void DeckSteamControl::setTarget(QString id, QString name, DeckSteamResolver resolver, QString identityKey) {
    if (id_ != id || name_ != name || identityKey_ != identityKey) { cancel(); state_.clear(); }
    id_ = std::move(id); name_ = std::move(name); identityKey_ = std::move(identityKey); resolver_ = std::move(resolver);
    state_["hostName"] = name_; emit stateChanged();
}
void DeckSteamControl::setBlocked(bool blocked) {
    if (blocked_ == blocked) return;
    blocked_ = blocked;
    if (blocked) cancel();
}
void DeckSteamControl::setWindowActive(bool active) { active_ = active; if (!active) cancel(); }
void DeckSteamControl::cancel() {
    ++generation_;
    if (job_) job_->cancelled = true;
    state_["available"] = false; state_["message"] = "Refresh Steam status before continuing.";
    emit stateChanged();
}
bool DeckSteamControl::refresh() { return start({}, false); }
bool DeckSteamControl::request(QString action, bool confirm) {
    if (action != "open" && action != "big_picture" && action != "quit") return false;
    if (!state_.value("available").toBool() || state_.value("revision").toString().isEmpty()) return false;
    if (action == "quit" && state_.value("gameActive").toBool() && !confirm) return false;
    return start(action, confirm);
}
bool DeckSteamControl::start(QString action, bool confirmed) {
    if (busy() || blocked_ || !active_ || !resolver_) return false;
    auto job = std::make_shared<Job>(); job_ = job;
    const auto generation = generation_;
    const auto revision = state_.value("revision").toString().toStdString();
    state_["busy"] = true; state_["available"] = false;
    state_["message"] = action.isEmpty() ? "Checking Steam…" : "Sending command…";
    worker_ = QThread::create([job, resolver = resolver_, action, confirmed, revision] {
        auto fail = [&](QString message) { job->result = {{"available", false}, {"message", message}}; };
        try {
            auto target = resolver();
            const auto valid = [&] { return !job->cancelled && target && target->identityValid && target->identityValid(); };
            if (!valid()) { fail("The selected PC or pairing changed. Refresh before continuing."); return; }
            if (!action.isEmpty()) {
                const auto result = target->act(action.toStdString(), revision, confirmed, [job] { return job->cancelled.load(); });
                if (!valid()) return;
                if (!result.ok() || !result.value->accepted) {
                    fail(result.value && !result.value->message.empty() ? QString::fromStdString(result.value->message)
                        : "Couldn't confirm the command. Refresh before retrying. It will not be retried automatically."); return;
                }
            }
            for (int attempt = 0; attempt < (action.isEmpty() ? 1 : 10); ++attempt) {
                if (!valid()) return;
                const auto result = target->read();
                if (!valid()) return;
                if (!result.ok() || !result.value->available()) {
                    fail(result.value && !result.value->message.empty() ? QString::fromStdString(result.value->message)
                        : "Steam controls are unavailable on this PC."); return;
                }
                const auto& value = *result.value;
                if (action.isEmpty() || (action == "quit" ? value.state == "stopped" : value.state == "running")) {
                    job->result = {{"available", true}, {"state", QString::fromStdString(value.state)},
                        {"revision", QString::fromStdString(value.revision)}, {"gameActive", value.gameActive},
                        {"effectiveKeepRunning", value.effectiveKeepRunning}, {"message", "Steam is " + QString::fromStdString(value.state) + "."}};
                    return;
                }
                for (int n = 0; n < 25 && !job->cancelled; ++n) std::this_thread::sleep_for(std::chrono::milliseconds(20));
            }
            fail("The command was accepted, but its outcome could not be confirmed. Refresh before retrying.");
        } catch (...) { fail("Couldn't check Steam. Refresh before retrying."); }
    });
    connect(worker_, &QThread::finished, this, [this, job, generation] {
        auto finished = worker_; worker_ = nullptr;
        if (!job->cancelled && generation == generation_) state_ = job->result;
        state_["busy"] = false; state_["hostName"] = name_;
        job_.reset(); finished->deleteLater(); emit stateChanged();
    });
    worker_->start(); emit stateChanged(); return true;
}
}
