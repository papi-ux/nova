#include "runtime/deck_resume_state.h"
#include <algorithm>

namespace nova::deck::runtime {
using namespace polaris;
namespace {
bool sameSession(const DeckPolarisSessionStatus& a, const DeckPolarisSessionStatus& b) {
    return a.resumable() && b.resumable() && a.gameId == b.gameId && a.gameUuid == b.gameUuid && a.sessionToken == b.sessionToken;
}
}
struct DeckResumeState::Job {
    std::atomic<bool> cancelled{false}, done{false};
    unsigned long long generation = 0;
    qint64 started = 0;
    bool stopping = false;
    std::optional<DeckPolarisSessionStatus> status;
    QString game;
};
DeckResumeState::DeckResumeState(DeckResumeStarter starter, QObject* parent, int pollMs, int freshnessMs)
    : QObject(parent), starter_(std::move(starter)), pollMs_(pollMs), freshnessMs_(freshnessMs) {
    clock_.start();
    timer_.setInterval(std::min(100, pollMs_));
    connect(&timer_, &QTimer::timeout, this, &DeckResumeState::tick);
    timer_.start();
    publish();
}
DeckResumeState::~DeckResumeState() {
    if (job_) job_->cancelled = true;
    if (worker_) { worker_->wait(); delete worker_; }
}
void DeckResumeState::clear() { observed_.reset(); observedAt_ = -1; game_.clear(); publish(); }
void DeckResumeState::publish() {
    const bool visible = observed_.has_value();
    QVariantMap next{{"visible", visible}, {"busy", busy()},
        {"hostId", host_}, {"gameId", game_}, {"title", visible ? QString::fromStdString(observed_->game) : QString{}},
        {"canEnd", visible && observed_->stopAllowed && !actionsBlocked_ && !busy()}, {"canResume", visible && !actionsBlocked_ && !busy()}};
    if (next != state_) { state_ = next; emit stateChanged(); }
}
void DeckResumeState::setContext(QString host, QString scope, DeckResumeResolver resolver) {
    // Refreshing a catalog also revokes the old worker's authority even when
    // the selected host is unchanged. Late answers cannot restore that card.
    ++generation_; pendingEnd_.reset();
    if (job_) job_->cancelled = true;
    host_ = std::move(host); scope_ = std::move(scope); resolver_ = std::move(resolver);
    clear(); nextRead_ = 0;
    tick();
}
void DeckResumeState::setActionsBlocked(bool blocked) {
    if (actionsBlocked_ == blocked) return;
    actionsBlocked_ = blocked; publish();
}
void DeckResumeState::setActive(bool active) {
    if (active_ == active) return;
    active_ = active; ++generation_; pendingEnd_.reset();
    if (job_) job_->cancelled = true;
    clear(); nextRead_ = 0;
    tick();
}
bool DeckResumeState::refresh() { return request(false); }
bool DeckResumeState::endSession() {
    if (actionsBlocked_ || !observed_ || !observed_->stopAllowed || clock_.elapsed() - observedAt_ >= freshnessMs_) return false;
    if (busy()) return false;
    if (worker_) {
        // An ordinary poll must not swallow a press of End. Cancel that read
        // and retain only this click's session for a fresh stop revalidation.
        pendingEnd_ = observed_; job_->cancelled = true; clear();
        return true;
    }
    return request(true);
}
bool DeckResumeState::startConfigured(const QString& host, const QString& game, const QVariantMap& configuration) {
    if (!active_ || actionsBlocked_ || !observed_ || !starter_ || host != host_ || game != game_ ||
        (worker_ && job_->stopping) || clock_.elapsed() - observedAt_ >= freshnessMs_) return false;
    const auto token = observed_->sessionToken;
    const bool accepted = starter_(host, game, configuration, token);
    if (accepted) clear();
    return accepted;
}
bool DeckResumeState::request(bool stop) {
    if (!active_ || worker_ || !resolver_ || host_.isEmpty() || scope_.isEmpty()) return false;
    auto job = std::make_shared<Job>();
    job->generation = generation_; job->started = clock_.elapsed(); job->stopping = stop;
    job_ = job; jobStopping_ = stop;
    worker_ = QThread::create([job, resolver = resolver_, expected = stop && pendingEnd_ ? pendingEnd_ : observed_] {
        const auto cancelled = [job] { return job->cancelled.load(); };
        try {
            auto target = resolver();
            if (target && target->valid && target->valid() && !cancelled()) {
                const auto fresh = target->read(cancelled);
                if (fresh.ok() && target->valid() && !cancelled() && fresh.value->resumable()) {
                    // Identity must be a unique exact match in this destination's
                    // catalog. A title or an app id alone never selects a game.
                    const auto& s = *fresh.value;
                    const auto match = [&](const auto& g) { return g.appId == s.gameId && g.id == s.gameUuid; };
                    const auto game = std::find_if(target->games.begin(), target->games.end(), match);
                    if (game != target->games.end() && std::count_if(target->games.begin(), target->games.end(), match) == 1) {
                        if (job->stopping) {
                            if (expected && sameSession(*expected, s) && s.stopAllowed && target->stop && target->valid() && !cancelled())
                                target->stop(s.sessionToken, cancelled); // One POST. An uncertain answer is never replayed.
                        } else { job->status = s; job->game = QString::fromStdString(game->id); }
                    }
                }
            }
        } catch (...) { /* No transport exception may retain authority. */ }
        job->done = true;
    });
    pendingEnd_.reset();
    worker_->start();
    if (stop) clear();
    publish(); return true;
}
void DeckResumeState::tick() {
    const auto now = clock_.elapsed();
    if (observedAt_ >= 0 && now - observedAt_ >= freshnessMs_) clear();
    if (worker_ && job_->done) {
        worker_->wait(); delete worker_; worker_ = nullptr; jobStopping_ = false;
        if (!job_->cancelled && job_->generation == generation_ && active_ &&
            now - job_->started < freshnessMs_) {
            observed_ = job_->status; game_ = job_->game;
            observedAt_ = observed_ ? job_->started : -1;
            nextRead_ = now + pollMs_;
        }
        job_.reset(); publish();
    }
    if (active_ && !worker_ && pendingEnd_) { request(true); return; }
    if (active_ && !worker_ && now >= nextRead_) { nextRead_ = now + pollMs_; request(false); }
}
}
