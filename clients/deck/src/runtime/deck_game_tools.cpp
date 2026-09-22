#include "runtime/deck_game_tools.h"
#include "runtime/deck_play_settings.h"
#include <QDateTime>
#include <algorithm>

namespace nova::deck::runtime {
using namespace polaris;
struct DeckGameTools::Job {
    std::atomic<bool> cancelled{false};
    quint64 generation = 0;
    QString action, copy;
    QVariantMap values, result, settings, steam;
    bool ok = false, sent = false, available = false;
    std::function<bool()> valid;
};
namespace {
QString failure(const DeckPolarisResult<QVariantMap>& r, bool sent) {
    if (r.status == DeckPolarisRequestStatus::Unauthorized || r.httpStatus == 403) return "This paired device is not allowed to make that change. Check device access in Polaris.";
    if (r.status == DeckPolarisRequestStatus::CertMismatch || r.status == DeckPolarisRequestStatus::InvalidIdentity) return "The PC's pairing changed. Return to your PCs and pair again.";
    if (r.httpStatus == 404 || r.httpStatus == 501) return "This PC does not offer this feature yet.";
    if (r.httpStatus == 409 || r.httpStatus == 410) return "These choices expired or changed on the PC. Search again before applying artwork.";
    if (r.httpStatus == 429) return "The artwork provider is busy. Wait a moment, then search again.";
    return sent ? "The PC did not confirm the change. Refresh the library before trying again." : "Couldn't check this PC. Try again when it is reachable.";
}
bool mutation(const QString& action) { return action == "apply" || action == "reset" || action == "refreshArt" || action == "spaceRefresh" || action == "steam"; }
QString spaceResult(const QVariantMap& result) {
    const auto status = result.value("resolution").toString();
    if (status == "healthy") return "Polaris checked the artwork it can provide. No missing images needed downloading.";
    if (status == "updated") return "Downloaded the missing Space artwork. Refreshing the library…";
    QStringList missing;
    for (const auto& kind : result.value("remaining").toStringList()) missing.append(kind == "hero" ? "backdrop" : kind);
    return "Some artwork is still missing: " + missing.join(", ") + ". Existing images were kept. You can refresh again later.";
}
}
DeckGameTools::DeckGameTools(QObject* parent) : QObject(parent) {
    timer_.setInterval(20); connect(&timer_, &QTimer::timeout, this, &DeckGameTools::poll);
}
DeckGameTools::~DeckGameTools() { if (job_) job_->cancelled = true; if (worker_) { worker_->wait(); delete worker_; } }
bool DeckGameTools::writing() const { return busy() && job_ && mutation(job_->action); }
QVariantMap DeckGameTools::state() const {
    return {{"busy", busy()}, {"writing", writing()}, {"available", available_ && !blocked_}, {"copy", copy_}, {"host", host_}, {"game", game_},
        {"settings", settings_}, {"plan", plan_}, {"steam", steam_}, {"candidates", candidates_}, {"choices", choices_},
        {"candidate", candidate_}, {"kind", kind_}, {"selections", selections_}, {"uncertain", uncertain_}, {"artworkResolution", artworkResolution_},
        {"spaceArtwork", validSpaceArtworkId(game_)},
        {"canRefreshArtwork", active_ && available_ && !blocked_ && !busy() && !uncertain_ && (validGameToolId(game_) || validSpaceArtworkId(game_))},
        {"canCheckArtwork", active_ && !blocked_ && !busy() && validSpaceArtworkId(game_)},
        {"canApply", active_ && !blocked_ && !busy() && !uncertain_ && !candidate_.isEmpty() && !selections_.isEmpty()}};
}
void DeckGameTools::setTarget(QString host, DeckGameToolsResolver resolver) {
    if (host != host_) { active_ = false; game_.clear(); artworkResolution_.clear(); }
    ++generation_; if (job_) job_->cancelled = true;
    resolver_ = std::move(resolver); host_ = std::move(host); available_ = false; settings_.clear(); plan_.clear(); steam_.clear();
    pendingReview_.reset(); clearEditing(); emit stateChanged();
    if (active_ && !game_.isEmpty()) QTimer::singleShot(0, this, [this] { if (active_) review(configuration_); });
}
void DeckGameTools::setSessionActive(bool active) {
    if (blocked_ == active) return;
    blocked_ = active;
    if (active) { ++generation_; if (job_) job_->cancelled = true; pendingReview_.reset(); }
    emit stateChanged();
}
bool DeckGameTools::prepare(const QString& host, const QString& game, const QVariantMap& configuration) {
    if (host != host_ || blocked_ || !(validGameToolId(game) || validSpaceArtworkId(game)) || !resolver_) {
        close(); available_ = false;
        copy_ = "Artwork tools are unavailable for this entry. Select a game in a paired PC's library."; emit stateChanged(); return false;
    }
    if (game_ != game) { ++generation_; if (job_) job_->cancelled = true; clearEditing(); settings_.clear(); steam_.clear(); artworkResolution_.clear(); }
    game_ = game; active_ = true; uncertain_ = false;
    return review(configuration);
}
bool DeckGameTools::review(const QVariantMap& configuration) {
    if (!active_ || blocked_ || !DeckPlayConfiguration::fromMap(configuration)) return false;
    configuration_ = configuration; plan_.clear();
    if (busy()) { ++generation_; job_->cancelled = true; pendingReview_ = configuration; emit stateChanged(); return true; }
    return start("review", configuration);
}
void DeckGameTools::clearEditing() { if (retirePreviews_) retirePreviews_(); candidate_.clear(); selections_.clear(); choiceCache_.clear(); candidates_.clear(); choices_.clear(); kind_ = "poster"; }
void DeckGameTools::discard() { if (busy()) return; clearEditing(); copy_ = "Unsaved artwork choices discarded."; emit stateChanged(); }
void DeckGameTools::close() { active_ = false; ++generation_; if (job_) job_->cancelled = true; pendingReview_.reset(); clearEditing(); emit stateChanged(); }
bool DeckGameTools::search(const QString& query) { if (busy() || !validGameToolId(game_)) return false; clearEditing(); uncertain_ = false; return start("search", {{"query", query}}); }
bool DeckGameTools::selectCandidate(int index) {
    if (busy() || index < 0 || index >= candidates_.size()) return false;
    if (retirePreviews_) retirePreviews_();
    candidate_ = candidates_[index].toMap(); selections_.clear(); choiceCache_.clear(); return selectKind("poster");
}
bool DeckGameTools::selectKind(const QString& kind) {
    if (busy() || candidate_.isEmpty() || !QStringList{"poster", "hero", "logo", "icon"}.contains(kind)) return false;
    kind_ = kind; choices_.clear();
    if (choiceCache_.contains(kind)) { choices_ = choiceCache_.value(kind); emit stateChanged(); return true; }
    return start("choices", {{"candidate", candidate_}, {"kind", kind}});
}
bool DeckGameTools::selectArtwork(int index) {
    if (busy() || blocked_ || index < 0 || index >= choices_.size()) return false;
    const auto c = choices_[index].toMap();
    if (c.value("expiresAt").toLongLong() <= QDateTime::currentSecsSinceEpoch()) { copy_ = "This artwork choice expired. Search again to get fresh choices."; emit stateChanged(); return false; }
    selections_[kind_] = c; copy_ = "Preview only. Apply saves your selected artwork to this PC."; emit stateChanged(); return true;
}
bool DeckGameTools::applyArtwork() {
    if (!state().value("canApply").toBool()) return false;
    QVariantMap tokens;
    for (auto it = selections_.cbegin(); it != selections_.cend(); ++it) {
        const auto c = it.value().toMap();
        if (c.value("expiresAt").toLongLong() <= QDateTime::currentSecsSinceEpoch()) { copy_ = "These artwork choices expired. Search again before applying."; emit stateChanged(); return false; }
        tokens[it.key()] = c.value("token");
    }
    return start("apply", {{"candidate", candidate_}, {"selections", tokens}});
}
bool DeckGameTools::resetArtwork() { return validGameToolId(game_) && !uncertain_ && start("reset"); }
bool DeckGameTools::refreshArtwork() {
    if (!state().value("canRefreshArtwork").toBool()) return false;
    artworkResolution_.clear();
    return start(validSpaceArtworkId(game_) ? "spaceRefresh" : "refreshArt");
}
bool DeckGameTools::checkArtwork() { return validSpaceArtworkId(game_) && start("checkArtwork"); }
bool DeckGameTools::setSteamMode(const QString& mode) {
    if (uncertain_ || !steam_.value("allowed").toStringList().contains(mode)) return false;
    return start("steam", {{"mode", mode}, {"previous", steam_.value("mode")}});
}
bool DeckGameTools::start(const QString& action, QVariantMap values) {
    if (busy() || blocked_ || !active_ || !resolver_ || game_.isEmpty()) return false;
    auto job = std::make_shared<Job>(); job->generation = generation_; job->action = action; job->values = values; job_ = job;
    copy_ = validSpaceArtworkId(game_) ? "Checking Space artwork…" : action == "review" ? "Checking the host's launch plan…" : "Working…";
    worker_ = QThread::create([job, resolver = resolver_, game = game_] {
        try {
            const auto target = resolver();
            if (!target || !target->identityValid || !target->request || !target->games) { job->copy = "Refresh this PC before using its game tools."; return; }
            job->valid = target->identityValid;
            const auto cancelled = [job] { return job->cancelled.load() || !job->valid(); };
            if (cancelled()) return;
            // The resolver checks the selected destination and returns only its
            // fresh permitted catalog. Stale cards cannot authorize a write.
            const auto catalog = target->games(cancelled);
            if (cancelled()) return;
            if (!catalog.ok()) { job->copy = "Couldn't verify access to this game. Refresh the library."; return; }
            const auto item = std::find_if(catalog.value->begin(), catalog.value->end(), [&](const auto& g) { return g.id == game.toStdString(); });
            if (item == catalog.value->end()) { job->copy = "This game is no longer available to this paired device."; return; }
            job->available = true;
            if (validSpaceArtworkId(game)) {
                const auto space = spaceGameIdentity(game.toStdString());
                if (item->spaceId != space->spaceId || !item->installed) { job->available = false; job->copy = "Refresh the Space library before changing its artwork."; return; }
                if (job->action == "review" || job->action == "checkArtwork") {
                    job->ok = true;
                    job->copy = "Space library checked. Refresh art looks for missing images.";
                    return;
                }
                if (job->action != "spaceRefresh") { job->copy = "Manual image selection is not available for Space games on this PC."; return; }
            }
            QStringList modes;
            if (item->steamLaunchAvailable) for (const auto& mode : item->steamLaunchAllowedModes)
                if (mode == "direct" || mode == "big-picture") modes.append(QString::fromStdString(mode));
            job->steam = {{"allowed", modes}, {"mode", QString::fromStdString(item->steamLaunchMode)}};
            if (job->action == "steam" && (!modes.contains(job->values.value("mode").toString()) ||
                job->values.value("previous").toString() != QString::fromStdString(item->steamLaunchMode))) {
                job->copy = "Steam launch settings changed. Review the current choice, then try again."; return;
            }
            if (job->action == "review") {
                const auto settings = target->request(game, "settings", {}, cancelled);
                if (cancelled()) return;
                if (!settings.ok()) { job->copy = failure(settings, false); return; }
                job->settings = *settings.value;
                const auto plan = target->request(game, "plan", job->values, cancelled);
                if (cancelled()) return;
                if (plan.ok()) job->result = *plan.value;
                job->ok = true;
                job->copy = plan.ok() ? "Host plan checked. Changes apply to the next launch." : "The PC did not provide a verified launch plan. Your stream choices remain visible.";
                return;
            }
            if (cancelled()) return;
            job->sent = mutation(job->action);
            const auto reply = target->request(game, job->action, job->values, cancelled);
            if (cancelled()) return;
            if (!reply.ok()) {
                job->copy = job->action == "spaceRefresh" && (reply.status == DeckPolarisRequestStatus::Timeout || reply.status == DeckPolarisRequestStatus::Unreachable || reply.status == DeckPolarisRequestStatus::MalformedBody)
                    ? "The PC did not confirm the refresh. Check again reads current artwork without repeating the request." : failure(reply, job->sent);
                return;
            }
            if (job->action == "steam") {
                const auto check = target->games(cancelled);
                if (cancelled()) return;
                if (!check.ok()) { job->copy = "The Steam launch change needs a library refresh to confirm."; return; }
                const auto fresh = std::find_if(check.value->begin(), check.value->end(), [&](const auto& g) { return g.id == game.toStdString(); });
                if (fresh == check.value->end() || QString::fromStdString(fresh->steamLaunchMode) != job->values.value("mode")) {
                    job->copy = "The PC did not confirm that Steam launch choice. Refresh the library."; return;
                }
                job->steam["mode"] = job->values.value("mode");
            }
            job->result = *reply.value; job->ok = true;
            job->copy = job->action == "spaceRefresh" ? spaceResult(job->result)
                : mutation(job->action) ? "Saved on this PC. Refreshing the library…" : "Choose artwork to preview. Apply saves it on this PC.";
        } catch (...) { job->copy = "The PC could not finish this action. Refresh before trying again."; }
    });
    worker_->start(); timer_.start(); emit stateChanged(); return true;
}
void DeckGameTools::poll() {
    if (!worker_ || !worker_->isFinished()) return;
    worker_->wait(); delete worker_; worker_ = nullptr; timer_.stop();
    const auto job = std::move(job_); const bool current = job->generation == generation_ && active_ && !blocked_;
    bool libraryChanged = false;
    if (current && !job->cancelled && (!job->valid || job->valid())) {
        copy_ = job->copy; available_ = job->available;
        if (!job->steam.isEmpty()) steam_ = job->steam;
        if (job->action == "review") {
            settings_ = job->settings; plan_ = job->result; uncertain_ = false;
            if (job->ok && validSpaceArtworkId(game_) && !artworkResolution_.isEmpty()) copy_ = spaceResult(artworkResolution_);
        }
        if (job->ok) {
            if (job->action == "search") {
                candidates_ = job->result.value("candidates").toList();
                if (previews_) candidates_ = previews_(host_, game_, candidates_);
                if (candidates_.isEmpty()) copy_ = "No artwork matches. Try a different title.";
            }
            if (job->action == "choices") {
                choices_ = job->result.value("choices").toList();
                if (previews_) choices_ = previews_(host_, game_, choices_);
                choiceCache_[kind_] = choices_;
                if (choices_.isEmpty()) copy_ = "No images for this kind. Choose another kind or game match.";
            }
            if (job->action == "spaceRefresh") artworkResolution_ = job->result;
            if (job->action == "checkArtwork") { uncertain_ = false; artworkResolution_.clear(); libraryChanged = true; }
            if (mutation(job->action)) { clearEditing(); libraryChanged = true; }
        } else if (job->sent) uncertain_ = true;
    }
    if (current && job->valid && !job->valid()) {
        available_ = false; plan_.clear(); uncertain_ = uncertain_ || job->sent;
        copy_ = "This PC's pairing changed. Refresh the library before using game tools.";
    }
    emit stateChanged();
    if (libraryChanged) emit this->libraryChanged();
    if (pendingReview_ && active_ && !blocked_) { const auto values = *pendingReview_; pendingReview_.reset(); review(values); }
}
}
