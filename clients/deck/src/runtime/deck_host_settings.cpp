#include "runtime/deck_host_settings.h"
#include <atomic>

namespace nova::deck::runtime {
using namespace polaris;
namespace {
QString failure(DeckPolarisRequestStatus status) {
    if (status == DeckPolarisRequestStatus::CertMismatch) return "This PC's certificate changed. Check its pairing before continuing.";
    if (status == DeckPolarisRequestStatus::Unauthorized) return "This PC no longer permits this pairing.";
    return "Couldn't verify the host settings. Check the connection and Refresh.";
}
QString profileDisplay(const QVariantMap& values) {
    return QString("%1x%2x%3").arg(values.value("width").toInt()).arg(values.value("height").toInt()).arg(values.value("fps").toInt());
}
}
struct DeckHostSettingsController::Job {
    std::atomic_bool cancelled{false};
    bool sent = false, idle = false;
    unsigned generation = 0;
    QString hostId;
    std::optional<DeckHostSettings> settings;
    QString phase = "unavailable", copy = "Couldn't verify these settings. Refresh to try again.";
    bool useProfile = false;
    bool automatic = false, restoreSync = false;
    QVariantMap novaDefaults;
    std::function<bool()> identityValid;
};
DeckHostSettingsController::DeckHostSettingsController(QObject* parent) : QObject(parent) {
    timer_.setInterval(20);
    connect(&timer_, &QTimer::timeout, this, &DeckHostSettingsController::poll);
    clock_.start();
    syncTimer_.setInterval(3000); // Frozen Android host-settings polling interval.
    connect(&syncTimer_, &QTimer::timeout, this, [this] {
        if (opened_ && windowActive_ && (!sessionActive_ || readOnly_) && !busy() && resolver_ && (syncView_ || keepInStep() == "on")) start({}, {}, true);
    });
    syncTimer_.start();
    publish("idle", "Open Every Game to check this PC's defaults.");
}
DeckHostSettingsController::~DeckHostSettingsController() {
    if (job_) job_->cancelled = true;
    if (worker_) { worker_->wait(); delete worker_; }
}
void DeckHostSettingsController::publish(QString phase, QString copy) {
    phase_ = std::move(phase);
    const bool ready = opened_ && windowActive_ && !readOnly_ && !sessionActive_ && !busy() && idle_ && settings_ && phase_ == "ready";
    const auto nova = playSettings_ ? playSettings_->streamDefaults() : QVariantMap{};
    const auto display = nova.isEmpty() ? QString{} : profileDisplay(nova);
    const int bitrate = nova.value("bitrateKbps").toInt();
    const bool matched = settings_ && settings_->hasProfile() && settings_->overrideDisplay() == display && settings_->overrideBitrate() == bitrate;
    const bool canSend = ready && playSettings_ && settings_->displayOverride && settings_->bitrateOverride;
    const bool canUse = ready && playSettings_ && playSettings_->defaultsFromHost(settings_->overrideDisplay(), settings_->overrideBitrate()).has_value();
    const QString profileState = !settings_ ? "Not verified" : !settings_->hasProfile() ? "No Polaris profile"
        : matched ? "Matches Nova" : "Different from Nova";
    QVariantList actions;
    const auto action = [&](QString id, QString label, QString detail, bool enabled) {
        actions.append(QVariantMap{{"id", id}, {"label", label}, {"detail", detail}, {"enabled", enabled}});
    };
    action("match", "Match Nova", "Save Nova's default resolution, frame rate and bitrate for this paired device on Polaris.", canSend && !matched);
    action("send", "Send Nova", "Send Nova's current stream defaults to this paired-device profile. Per-game choices are not sent.", canSend);
    action("use", "Use Polaris", settings_ && settings_->hasProfile() && playSettings_ && !canUse && ready
        ? "This profile uses stream settings Nova Deck does not support yet. Nothing will be partly imported."
        : "Use the Polaris profile as Nova's defaults on this device, across PCs. Existing per-game overrides stay in place.", canUse);
    action("clear", "Clear profile", "Remove this paired device's resolution and bitrate overrides on Polaris. Nova's defaults stay in place.",
        canSend && settings_->hasProfile());
    auto factory = DeckPlayConfiguration{}.toMap(); factory.remove("faceButtonLayout"); factory.remove("launchMode");
    factory.remove("videoCodec"); factory.remove("profilePreference"); factory.remove("encoderBackend");
    action("reset", "Reset Nova defaults", "Restore 1280 × 800, 60 fps and 20 Mbps on this device. Keep per-game choices and the Polaris profile.",
        opened_ && windowActive_ && !readOnly_ && !sessionActive_ && !busy() && playSettings_ && (nova != factory || keepInStep() != "off"));
    const auto sync = keepInStep();
    const QString syncCopy = job_ && job_->restoreSync && !job_->cancelled
        ? "Saving Nova's defaults to this paired PC profile. Choose Off to stop further automatic updates."
        : sync == "paused"
        ? "Keep in step is paused. Refresh to check both profiles, then choose Resume. No automatic save will be retried."
        : "While this settings view is open, keep this paired PC profile matched to Nova's device defaults. Per-game choices stay separate. Use Polaris, Clear profile and Reset turn this off.";
    QVariantMap next{{"phase", phase_}, {"copy", copy}, {"hostId", hostId_}, {"hostName", name_},
        {"supported", bool(resolver_)}, {"busy", busy()}, {"readOnly", readOnly_},
        {"canRefresh", opened_ && windowActive_ && (!sessionActive_ || readOnly_) && !busy()},
        {"canChange", opened_ && windowActive_ && !readOnly_ && !sessionActive_ && !busy() && idle_ && settings_.has_value() && phase_ == "ready"},
        {"settings", settings_ ? settings_->publicState() : QVariantMap{}}, {"profileActions", actions}, {"profileState", profileState},
        {"profileReview", settings_ ? settings_->profileReview() : QVariantMap{}},
        {"novaDefaults", nova}, {"novaDisplay", display}, {"novaBitrate", bitrate},
        {"keepInStep", sync}, {"keepInStepCopy", syncCopy}, {"canEnableSync", canSend && sync != "on"},
        {"canDisableSync", !readOnly_ && !sessionActive_ && playSettings_ && !hostId_.isEmpty() && sync != "off"},
        {"canEditDefaults", opened_ && windowActive_ && !readOnly_ && !sessionActive_ && !busy() && playSettings_},
        {"canChangeResumeTimeout", ready && settings_->resumeTimeoutControl},
        {"automaticCheck", job_ && job_->automatic}};
    if (state_ != next) { state_ = std::move(next); emit stateChanged(); }
}
void DeckHostSettingsController::setPlaySettings(DeckPlaySettings* settings) {
    if (playSettings_) disconnect(playSettings_, nullptr, this, nullptr);
    playSettings_ = settings;
    if (settings) connect(settings, &DeckPlaySettings::streamDefaultsChanged, this,
        [this] {
            if (job_ && job_->automatic && job_->restoreSync) {
                cancel();
                publish("idle", "Nova's defaults changed during a save. Refresh before resuming Keep in step.");
            } else publish(phase_, state_.value("copy").toString());
        });
    publish(phase_, state_.value("copy").toString());
}
void DeckHostSettingsController::cancel() {
    ++generation_;
    if (job_) job_->cancelled = true;
    settings_.reset(); idle_ = false;
}
void DeckHostSettingsController::setTarget(QString hostId, QString name, DeckHostSettingsResolver resolver) {
    cancel(); hostId_ = std::move(hostId); name_ = std::move(name); resolver_ = std::move(resolver);
    publish("idle", "Refresh to check the selected PC's defaults.");
}
void DeckHostSettingsController::setSessionActive(bool active) {
    if (sessionActive_ == active) return;
    sessionActive_ = active;
    if (active) cancel();
    publish("idle", active ? "Disconnect before changing host defaults." : "Refresh to check the host defaults.");
}
void DeckHostSettingsController::setWindowActive(bool active) {
    if (windowActive_ == active) return;
    windowActive_ = active;
    if (!active) { cancel(); publish("idle", "Refresh after returning to Nova to check the current defaults."); }
    else publish(phase_, state_.value("copy").toString());
}
void DeckHostSettingsController::open() { cancel(); opened_ = true; readOnly_ = syncView_ = false; refresh(); }
void DeckHostSettingsController::openSync(bool readOnly) {
    cancel(); opened_ = true; syncView_ = true; readOnly_ = readOnly;
    publish("idle", readOnly ? "Refresh to compare the saved profiles. Changes stay locked during play." : "Refresh to compare Nova and Polaris.");
    refresh();
}
void DeckHostSettingsController::close() { opened_ = false; cancel(); publish("idle", "Open Every Game to check this PC's defaults."); }
bool DeckHostSettingsController::refresh() {
    if (busy() || !opened_ || !windowActive_ || (sessionActive_ && !readOnly_)) return false;
    if (!resolver_ || hostId_.isEmpty()) { publish("unavailable", "Every Game requires a paired Polaris PC. Refresh the library or check its pairing."); return false; }
    start(); return true;
}
bool DeckHostSettingsController::selectMode(const QString& mode) {
    if (!state_.value("canChange").toBool() || !settings_ || !settings_->permits(mode) || mode == settings_->desiredMode) return false;
    start(mode); return true;
}
bool DeckHostSettingsController::profileAction(const QString& action) {
    bool enabled = false;
    for (const auto& item : state_.value("profileActions").toList())
        if (item.toMap().value("id") == action) enabled = item.toMap().value("enabled").toBool();
    if (!enabled || !playSettings_) return false;
    // These explicit choices would otherwise be undone by the next sync poll.
    if ((action == "use" || action == "clear" || action == "reset") && keepInStep() != "off" && !setKeepInStep(false)) return false;
    if (action == "reset") {
        if (!playSettings_->resetStreamDefaults()) { publish(phase_, "Couldn't save Nova's defaults. Nothing was changed."); return false; }
        publish(phase_, "Nova's stream defaults were reset. Per-game choices and the Polaris profile were kept.");
        emit novaDefaultsChanged(); return true;
    }
    start({}, action); return true;
}
bool DeckHostSettingsController::saveDefaults(const QVariantMap& values, const QVariantMap& expected) {
    if (!state_.value("canEditDefaults").toBool() || !playSettings_ || playSettings_->streamDefaults() != expected) return false;
    if (!playSettings_->saveStreamDefaults(values)) return false;
    publish(phase_, "Saved Nova's device defaults. Existing per-game choices stay in place.");
    emit novaDefaultsChanged(); return true;
}
bool DeckHostSettingsController::setResumeTimeout(int seconds) {
    if (seconds < 0 || seconds > 86400 || !state_.value("canChangeResumeTimeout").toBool()
        || !settings_ || settings_->desiredResumeTimeout == seconds) return false;
    start({}, {}, false, seconds); return true;
}
QString DeckHostSettingsController::keepInStep() const {
    if (pausedHosts_.contains(hostId_)) return "paused";
    return playSettings_ ? playSettings_->keepInStep(hostId_) : QStringLiteral("off");
}
void DeckHostSettingsController::pauseKeepInStep() {
    pausedHosts_.insert(hostId_);
    if (playSettings_) playSettings_->saveKeepInStep(hostId_, "paused");
}
bool DeckHostSettingsController::setKeepInStep(bool enabled) {
    if (!state_.value(enabled ? "canEnableSync" : "canDisableSync").toBool() || !playSettings_) return false;
    if (!playSettings_->saveKeepInStep(hostId_, enabled ? "on" : "off")) {
        pauseKeepInStep();
        if (job_ && (job_->automatic || job_->restoreSync)) cancel();
        publish(phase_, "Couldn't save Keep in step. Automatic updates are paused on this device.");
        return false;
    }
    pausedHosts_.remove(hostId_);
    if (!enabled && job_ && (job_->automatic || job_->restoreSync)) cancel();
    publish(phase_, enabled ? "Keep in step is on for this PC." : "Keep in step is off for this PC.");
    if (enabled) maybeKeepInStep();
    return true;
}
void DeckHostSettingsController::maybeKeepInStep() {
    if (keepInStep() != "on" || !opened_ || !windowActive_ || readOnly_ || sessionActive_ || busy() || !idle_ || !settings_ || !playSettings_) return;
    if (!settings_->displayOverride || !settings_->bitrateOverride) {
        pauseKeepInStep(); publish(phase_, "This PC no longer allows profile updates. Keep in step is paused."); return;
    }
    const auto nova = playSettings_->streamDefaults();
    if (settings_->overrideDisplay() == profileDisplay(nova) && settings_->overrideBitrate() == nova.value("bitrateKbps").toInt()) return;
    if (lastSync_.contains(hostId_) && clock_.elapsed() - lastSync_.value(hostId_) < 5000) return;
    lastSync_[hostId_] = clock_.elapsed();
    start({}, "send", true);
}
void DeckHostSettingsController::start(const QString& mode, const QString& profileAction, bool automatic, int resumeTimeout) {
    const bool reading = mode.isEmpty() && profileAction.isEmpty() && resumeTimeout < 0;
    if (!reading && (readOnly_ || sessionActive_)) return;
    const bool syncWrite = !reading && resumeTimeout < 0 && profileAction != "use" && keepInStep() == "on";
    // Durable intent before any sync-enabled POST: interruption or a lost reply
    // stays paused even across restart. Only a verified receipt can re-arm it.
    if (syncWrite) {
        if (!playSettings_ || !playSettings_->saveKeepInStep(hostId_, "paused")) {
            pauseKeepInStep(); publish(phase_, "Couldn't save Keep in step's pending state. No request was sent."); return;
        }
    }
    auto job = std::make_shared<Job>(); job_ = job; job->generation = generation_; job->hostId = hostId_;
    job->automatic = automatic; job->restoreSync = syncWrite;
    job->novaDefaults = playSettings_ ? playSettings_->streamDefaults() : QVariantMap{};
    const auto reviewed = settings_;
    if (!automatic) { settings_.reset(); idle_ = false; }
    worker_ = QThread::create([job, resolver = resolver_, reviewed, mode, profileAction, resumeTimeout, readOnly = readOnly_] {
        try {
            if (job->cancelled) return;
            auto target = resolver();
            if (!target || !target->identityValid || !target->capabilities || !target->read || !target->idle || !target->writeMode) return;
            const auto cancelled = [job, valid = target->identityValid] { return job->cancelled.load() || !valid(); };
            job->identityValid = target->identityValid;
            if (cancelled()) return;
            const auto caps = target->capabilities();
            if (cancelled()) return;
            if (!caps.ok() || !caps.value->clientSettings) { job->copy = "This PC does not currently offer Polaris host settings."; return; }
            const auto fresh = target->read(cancelled);
            if (cancelled()) return;
            if (!fresh.ok()) { job->copy = failure(fresh.status); return; }
            const auto idle = target->idle(cancelled);
            if (cancelled()) return;
            if (!idle.ok()) { job->copy = "Couldn't verify whether the PC is idle. Refresh before changing defaults."; return; }
            job->settings = fresh.value; job->idle = *idle.value;
            if (readOnly) { job->phase = "ready"; job->copy = "Saved profiles checked. Live encoder readings are shown separately."; return; }
            if (!job->idle) { job->phase = "ready"; job->copy = "End the active game on this PC before changing its default display."; return; }
            if (mode.isEmpty() && profileAction.isEmpty() && resumeTimeout < 0) { job->phase = "ready"; job->copy = "Host defaults checked. Changes apply to future streams on this PC."; return; }
            // The host has no revision CAS for topology writes. Compare the full
            // reviewed authority immediately before one narrow POST; never claim
            // atomic protection from other host writers between GET and POST.
            if (!reviewed || reviewed->revision != fresh.value->revision || reviewed->publicState() != fresh.value->publicState()) {
                job->phase = "ready"; job->copy = "The host settings changed. Review the refreshed values before choosing again."; return;
            }
            if (cancelled()) return;
            if (resumeTimeout >= 0) {
                if (!fresh.value->resumeTimeoutControl || !target->writeResumeTimeout) return;
                job->sent = true;
                const auto reply = target->writeResumeTimeout(resumeTimeout, cancelled);
                job->settings.reset(); job->idle = false; job->phase = "unconfirmed";
                job->copy = "The timeout change wasn't confirmed. Refresh to read the PC's setting; Nova will not resend it.";
                if (cancelled() || !reply.ok() || reply.value->desiredResumeTimeout != resumeTimeout) return;
                job->settings = reply.value; job->idle = true; job->phase = "ready";
                job->copy = reply.value->effectiveResumeTimeout == resumeTimeout
                    ? "Saved the PC's resume timeout. This affects sessions from all paired devices."
                    : "Saved the requested resume timeout. The PC still reports a different effective value.";
                return;
            }
            if (profileAction == "use") { job->useProfile = true; job->phase = "ready"; return; }
            if (profileAction.isEmpty() ? !fresh.value->permits(mode)
                : (!target->writeProfile || !fresh.value->displayOverride || !fresh.value->bitrateOverride || job->novaDefaults.isEmpty())) return;
            const bool clear = profileAction == "clear";
            job->sent = true;
            const auto reply = profileAction.isEmpty() ? target->writeMode(mode, cancelled)
                : target->writeProfile(clear ? QString{} : profileDisplay(job->novaDefaults),
                    clear ? 0 : job->novaDefaults.value("bitrateKbps").toInt(), clear, cancelled);
            job->settings.reset(); job->idle = false;
            job->phase = "unconfirmed";
            job->copy = "The change wasn't confirmed. Refresh to read the host's actual settings. Nova will not resend it.";
            if (cancelled()) return;
            if (!reply.ok()) return;
            if (profileAction.isEmpty() ? reply.value->desiredMode != mode
                : clear ? (!reply.value->desiredDisplay.isEmpty() || reply.value->desiredBitrate != 0)
                : (reply.value->desiredDisplay != profileDisplay(job->novaDefaults) || reply.value->desiredBitrate != job->novaDefaults.value("bitrateKbps").toInt())) return;
            job->settings = reply.value; job->idle = true; job->phase = "ready";
            if (!profileAction.isEmpty()) job->copy = clear ? "Cleared this paired device's profile on Polaris. Nova's defaults were kept."
                : "Saved Nova's defaults for this paired device on Polaris. Review the next game before Play.";
            else job->copy = reply.value->relaunchRequired || reply.value->desiredMode != reply.value->effectiveMode
                ? "Saved on the PC. Desired and effective differ; the host still needs to apply the default."
                : "Saved on the PC. This is the default for future streams.";
        } catch (...) {
            job->settings.reset(); job->idle = false;
            job->phase = job->sent ? "unconfirmed" : "unavailable";
            if (job->sent) job->copy = "The change wasn't confirmed. Refresh to read the host's actual settings. Nova will not resend it.";
        }
    });
    publish(reading ? "checking" : "saving", reading ? "Checking host defaults…" : "Rechecking the profile before saving…");
    timer_.start(); worker_->start();
}
void DeckHostSettingsController::poll() {
    if (!worker_ || !worker_->isFinished()) return;
    worker_->wait(); delete worker_; worker_ = nullptr; timer_.stop();
    const auto job = job_; job_.reset();
    // Count from completion too: slow preflight/transport must not compress
    // consecutive POSTs into less than the minimum interval.
    if (job->restoreSync && job->sent) lastSync_[job->hostId] = clock_.elapsed();
    if (job->generation == generation_ && !job->cancelled) {
        settings_ = job->settings; idle_ = job->idle;
        if (job->useProfile) {
            const auto imported = playSettings_ && settings_ ? playSettings_->defaultsFromHost(settings_->overrideDisplay(), settings_->overrideBitrate()) : std::nullopt;
            if (!job->identityValid || !job->identityValid()) {
                settings_.reset(); idle_ = false; job->phase = "unavailable";
                job->copy = "This PC's pairing changed. Refresh before using its profile.";
            } else if (!playSettings_ || playSettings_->streamDefaults() != job->novaDefaults) {
                job->copy = "Nova's defaults changed. Review the current values before using Polaris.";
            } else if (!imported) job->copy = "This profile uses unsupported stream settings. Nova's defaults were kept.";
            else if (!playSettings_->saveStreamDefaults(*imported)) job->copy = "Couldn't save Nova's defaults. Nothing was changed.";
            else { job->copy = "Saved as Nova's defaults on this device. Per-game overrides were kept."; emit novaDefaultsChanged(); }
        }
        if (job->restoreSync) {
            if (job->sent && job->phase == "ready" && playSettings_ && job->identityValid && job->identityValid() &&
                playSettings_->saveKeepInStep(hostId_, "on")) pausedHosts_.remove(hostId_);
            else {
                pauseKeepInStep();
                job->copy += " Keep in step is paused; review the profiles before resuming.";
            }
        } else if (!readOnly_ && keepInStep() == "on" && job->phase != "ready") {
            pauseKeepInStep(); job->copy += " Keep in step is paused.";
        }
        publish(job->phase, job->copy);
    } else publish("idle", job->sent ? "A change may have reached the PC. Refresh to check its actual settings." : "Refresh to check the current host defaults.");
    if (job->sent && job->hostId == hostId_) emit hostDefaultsMayHaveChanged();
    // A fresh read may schedule one guarded save. No retry follows a failed,
    // stale or cancelled write, including after close/reopen or restart.
    if (!job->sent && !job->restoreSync && job->generation == generation_ && !job->cancelled && job->phase == "ready") maybeKeepInStep();
}
}
