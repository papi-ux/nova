#include "runtime/deck_hud_host.h"
#include "runtime/deck_doctor_actions.h"
#include "runtime/deck_doctor_receipts.h"
#include <QDateTime>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <algorithm>
#include <utility>

namespace nova::deck::runtime {
using namespace polaris;
namespace {
bool oneOf(const QString& value, std::initializer_list<const char*> values) {
    for (const auto* v : values) if (value == v) return true;
    return false;
}
QString bitrate(int kbps) { return kbps > 0 ? QString::number(kbps / 1000.0, 'f', 1) + "M" : "--"; }
using Clock = std::chrono::steady_clock;
qint64 nowMs() { return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now().time_since_epoch()).count(); }
}
QVariantMap DeckHudHostReducer::unavailable() {
    return {{"hostFresh", false}, {"hostRefreshing", false}, {"healthLabel", "Host readings unavailable"}, {"healthTone", "muted"},
        {"hostTone", "muted"}, {"netTone", "muted"}, {"clientTone", "muted"},
        {"tuningLabel", "Tuning: Unknown"}, {"tuningTone", "muted"},
        {"appliedBitrate", "--"}, {"qualityLimit", "--"},
        {"canTune", false}, {"tuningKnown", false}, {"tuningEnabled", false}, {"tuningBusy", false},
        {"tuningCopy", "Live Tuning is unavailable for this stream."},
        {"canSetBitrate", false}, {"appliedBitrateKbps", 0}, {"bitrateRequestKbps", 0}, {"bitrateBusy", false},
        {"bitrateCopy", "Live bitrate is unavailable for this stream."},
        {"canSyncProfile", false}, {"syncBusy", false}, {"syncPhase", "idle"}, {"syncCopy", ""}, {"syncVersion", 0},
        {"doctor", QVariantMap{}}, {"canRefreshDiagnostics", false}, {"diagnosticsRefreshing", false}};
}
namespace {
QVariantMap refreshingView() {
    auto view = DeckHudHostReducer::unavailable();
    view["hostRefreshing"] = true;
    view["tuningCopy"] = view["bitrateCopy"] = "Refreshing host state…";
    return view;
}
}
QVariantMap DeckHudHostReducer::accept(const DeckHostTelemetry& s) {
    auto out = unavailable();
    if (!s.authorityValid || !s.active || s.ending || !s.owned || s.role != "owner" || context_.gameId <= 0 ||
        context_.gameUuid.isEmpty() || context_.sessionToken.isEmpty() || s.gameId != context_.gameId ||
        s.gameUuid != context_.gameUuid || s.sessionToken != context_.sessionToken) return out;
    // The whole response is delayed when its typed tuning observation regresses.
    // Never refresh a newer Doctor/bitrate reading with that old response.
    if (s.live) {
        const auto& live = *s.live;
        if (!s.generation || *s.generation != live.generation || s.appSession.isEmpty() || s.appSession != live.appSession ||
            retired_.contains(live.instance)) return out;
        if (last_ && last_->instance == live.instance && (live.sequence <= last_->sequence ||
            live.generation != last_->generation || live.appSession != last_->appSession)) return out;
        if (last_ && last_->instance != live.instance) {
            if (retired_.size() >= 8) return out;
            retired_.insert(last_->instance);
        }
        last_ = live;
        out["tuningLabel"] = !live.enabled ? "Tuning: Off"
            : live.state == "unavailable" ? "Tuning: Unavailable"
            : live.state == "waiting" ? "Tuning: Waiting"
            : live.state == "applying" ? "Tuning: Applying"
            : live.state == "measuring" ? "Tuning: Measuring"
            : live.state == "adjusting" ? "Tuning: Adjusting" : "Tuning: On";
        out["tuningTone"] = live.enabled ? (live.supported ? "info" : "muted") : "muted";
        out["qualityLimit"] = bitrate(live.qualityLimit);
        out["tuningKnown"] = true; out["tuningEnabled"] = live.enabled;
        out["canTune"] = s.hostTuningAllowed && live.generation > 0;
        out["tuningCopy"] = !s.hostTuningAllowed ? "This session doesn't allow host tuning."
            : live.generation <= 0 ? "Waiting for a current game session."
            : "Changes save immediately. Turning tuning off holds the last confirmed bitrate.";
        // Requested, adaptive target and legacy encoder bitrate are never used
        // as acknowledgements. Even a requested > applied transition shows applied.
        if (live.supported) { out["appliedBitrate"] = bitrate(live.applied); out["appliedBitrateKbps"] = live.applied; }
        out["canSetBitrate"] = out.value("canTune").toBool() && live.supported && live.applied > 0;
        out["bitrateCopy"] = !out.value("canTune").toBool() ? out.value("tuningCopy")
            : !live.supported ? QVariant("This encoder cannot change bitrate during the stream.")
            : !live.applied ? QVariant("Waiting for the encoder's current bitrate.")
            : QVariant("Choose a fixed bitrate. Applying it turns Live Tuning off.");
    } else if (!s.livePresent && s.legacyTuning) {
        out["tuningLabel"] = *s.legacyTuning ? "Tuning: On (legacy)" : "Tuning: Off (legacy)";
        out["tuningTone"] = *s.legacyTuning ? "info" : "muted";
        out["tuningCopy"] = "Update Polaris to change Live Tuning from this device.";
    }
    out["hostFresh"] = true;
    out["doctor"] = s.doctor;
    const bool observation = oneOf(s.primaryIssue, {"network_observation", "control_channel_observation"});
    const bool unknownNetwork = !s.networkWarning && !observation && oneOf(s.primaryIssue, {"network_jitter", "packet_loss", "network_limited"});
    const bool legacyWarning = !s.doctorAuthoritative && oneOf(s.grade, {"watch", "degraded"}) && !observation && !unknownNetwork;
    const bool verdictWarning = s.doctorVerdictPresent && !s.doctorHealthy;
    const bool knownHealthy = s.doctorHealthy || (!s.doctorAuthoritative && oneOf(s.grade, {"good", "ok"}));
    const bool hostWarn = s.hostWarning || s.hostLimited || s.primaryIssue == "frame_pacing" || legacyWarning;
    const bool clientWarn = s.clientWarning || s.primaryIssue.contains("decoder");
    const bool otherIssue = !s.primaryIssue.isEmpty() && s.primaryIssue != "none" && !observation && !unknownNetwork;
    const bool warning = s.hdrDowngraded || s.displayOverride || s.evidenceWarning || verdictWarning || hostWarn || clientWarn || otherIssue;
    out["hostTone"] = hostWarn || s.hdrDowngraded ? "warning" : knownHealthy ? "stable" : "muted";
    out["netTone"] = s.networkWarning ? "warning" : observation || unknownNetwork ? "muted" : knownHealthy ? "stable" : "muted";
    out["clientTone"] = clientWarn ? "warning" : knownHealthy ? "stable" : "muted";
    out["healthTone"] = warning ? "warning" : observation || unknownNetwork ? "muted" : knownHealthy ? "stable" : "muted";
    out["healthLabel"] = s.hdrDowngraded ? "HDR downgraded" : s.displayOverride ? "Display override"
        : s.hostLimited ? "Host capped" : s.primaryIssue == "frame_pacing" ? "Frame pacing"
        : warning ? "Needs attention" : s.primaryIssue == "control_channel_observation" ? "Link retries"
        : observation || unknownNetwork ? "Network recheck" : knownHealthy ? "Stable" : "Host health unknown";
    return out;
}

struct DeckHudHostObserver::Shared {
    struct Profile { QString display; bool clear = false; QVariantMap reviewed; };
    struct Intent { bool enabled; DeckLiveTuningTelemetry observed; int bitrateKbps = 0; std::optional<Profile> profile; };
    std::atomic_bool stopped{false};
    mutable std::mutex mutex;
    std::condition_variable wake;
    QVariantMap view = DeckHudHostReducer::unavailable();
    std::optional<DeckLiveTuningTelemetry> live;
    std::optional<Intent> pending;
    bool busy = false, fixedChange = false;
    int requestedBitrate = 0;
    QString message;
    bool syncChange = false;
    QString syncPhase = "idle", syncCopy;
    quint64 syncVersion = 0;
    void syncResult(QString phase, QString copy) {
        if (syncPhase == phase && syncCopy == copy) return;
        syncPhase = std::move(phase); syncCopy = std::move(copy); ++syncVersion;
    }
    Clock::time_point observed{};
    int staleMs = 3500;
    std::atomic_bool eventsStopped{false}, eventFatal{false};
    int eventsPort = 0;
    quint64 eventEpoch = 0, refreshVersion = 0;
    bool refreshRequested = false;
    bool observing = false, manualRefresh = false;
    Clock::time_point lastManualRefresh{};
    DeckDoctorActions doctor;
    bool doctorStorageReady = true;
};
DeckHudHostObserver::DeckHudHostObserver(DeckHudHostFactory factory, DeckHudHostContext context, DeckHudHostTiming timing,
    std::function<bool()> sessionEnded) : shared_(std::make_shared<Shared>()) {
    timing.intervalMs = std::clamp(timing.intervalMs, 10, 10000);
    timing.staleMs = std::clamp(timing.staleMs, 20, 10000);
    timing.maxBackoffMs = std::clamp(timing.maxBackoffMs, timing.intervalMs, 30000);
    timing.confirmationMs = std::clamp(timing.confirmationMs, 20, 10000);
    timing.eventRetryMs = std::clamp(timing.eventRetryMs, 20, 15000);
    shared_->staleMs = timing.staleMs;
    worker_.reset(QThread::create([state = shared_, factory = std::move(factory), context = std::move(context), timing,
        sessionEnded = std::move(sessionEnded)] {
        try {
            auto target = factory ? factory() : std::nullopt;
            if (!target || !target->fetch || !target->identityValid) return;
            auto savedReceipt = target->doctorReceipts ? target->doctorReceipts->load() : QJsonObject{};
            bool recoveryPending = !savedReceipt.isEmpty();
            QJsonObject lastSaved = savedReceipt;
            {
                const std::lock_guard lock(state->mutex);
                state->doctorStorageReady = !target->doctorReceipts || target->doctorReceipts->ready();
                if (!state->doctorStorageReady) state->doctor.storageFailure();
            }
            const auto persistDoctor = [&] {
                if (!target->doctorReceipts) return true;
                QJsonObject checkpoint;
                {
                    const std::lock_guard lock(state->mutex);
                    if (!state->doctorStorageReady) return false;
                    if (recoveryPending) return true;
                    checkpoint = state->doctor.checkpoint();
                }
                if (checkpoint == lastSaved) return true;
                if (target->doctorReceipts->save(checkpoint)) { lastSaved = checkpoint; return true; }
                const std::lock_guard lock(state->mutex);
                state->doctorStorageReady = false; state->doctor.storageFailure(); return false;
            };
            { const std::lock_guard lock(state->mutex); state->observing = true; }
            const auto cancelled = [&] { return state->stopped || state->eventFatal || (sessionEnded && sessionEnded()); };
            const auto valid = [&] { return !cancelled() && target->identityValid(); };
            const auto permanent = [](const auto& result) {
                return result.status == DeckPolarisRequestStatus::CertMismatch || result.status == DeckPolarisRequestStatus::Unauthorized ||
                    result.status == DeckPolarisRequestStatus::InvalidIdentity || result.httpStatus == 404 || result.httpStatus == 405;
            };
            // Both workers are joined before the owning observer can disappear.
            const auto joinEvents = [state](QThread* thread) {
                if (!thread) return;
                state->eventsStopped = true; state->wake.notify_all(); thread->wait(); delete thread;
            };
            std::unique_ptr<QThread, decltype(joinEvents)> eventsWorker(nullptr, joinEvents);
            if (target->events) {
                eventsWorker.reset(QThread::create([state, listen = target->events, timing] {
                    quint64 disabledEpoch = 0;
                    int delay = timing.eventRetryMs;
                    while (!state->stopped && !state->eventsStopped && !state->eventFatal) {
                        int port; quint64 epoch;
                        {
                            std::unique_lock lock(state->mutex);
                            state->wake.wait(lock, [&] { return state->stopped || state->eventsStopped ||
                                state->eventFatal || (state->eventsPort > 0 && state->eventEpoch != disabledEpoch); });
                            if (state->stopped || state->eventsStopped || state->eventFatal) break;
                            port = state->eventsPort; epoch = state->eventEpoch;
                        }
                        const auto stop = [state, epoch] {
                            const std::lock_guard lock(state->mutex);
                            return state->stopped || state->eventsStopped || state->eventFatal || state->eventEpoch != epoch;
                        };
                        const auto refresh = [state, epoch] {
                            const std::lock_guard lock(state->mutex);
                            if (state->stopped || state->eventsStopped || state->eventFatal || state->eventEpoch != epoch) return;
                            ++state->refreshVersion; state->refreshRequested = true;
                            state->view = refreshingView(); state->live.reset();
                            state->doctor.invalidate();
                            if (!state->busy) state->message.clear();
                            state->wake.notify_all();
                        };
                        DeckPolarisResult<bool> result;
                        const auto began = Clock::now();
                        try { result = listen(port, refresh, stop); }
                        catch (...) { result.status = DeckPolarisRequestStatus::MalformedBody; }
                        if (stop()) { delay = timing.eventRetryMs; continue; }
                        refresh(); // EOF or lost connection has no retained replay log.
                        if (result.status == DeckPolarisRequestStatus::Unauthorized ||
                            result.status == DeckPolarisRequestStatus::CertMismatch ||
                            result.status == DeckPolarisRequestStatus::InvalidIdentity) {
                            state->eventFatal = true; state->wake.notify_all(); break;
                        }
                        // Unsupported/invalid event services fall back to the regular
                        // paired status poll. Only a new advertised endpoint retries.
                        if (result.status == DeckPolarisRequestStatus::MalformedBody ||
                            (result.httpStatus >= 300 && result.httpStatus < 500)) disabledEpoch = epoch;
                        if (Clock::now() - began >= std::chrono::seconds(15)) delay = timing.eventRetryMs;
                        std::unique_lock lock(state->mutex);
                        state->wake.wait_for(lock, std::chrono::milliseconds(delay), [&] {
                            return state->stopped || state->eventsStopped || state->eventFatal || state->eventEpoch != epoch;
                        });
                        delay = std::min(15000, delay * 2);
                    }
                }));
                eventsWorker->start();
            }
            DeckHudHostReducer reducer(context);
            const auto project = [&](const DeckPolarisResult<DeckHostTelemetry>& result) {
                auto view = result.ok() ? reducer.accept(*result.value) : DeckHudHostReducer::unavailable();
                if (!target->setEnabled) view["canTune"] = false;
                if (!target->setBitrate) view["canSetBitrate"] = false;
                view["canSyncProfile"] = view.value("canTune").toBool() && bool(target->readProfile) && bool(target->writeProfile);
                return view;
            };
            const auto publish = [&](const DeckPolarisResult<DeckHostTelemetry>& result, QVariantMap& view, quint64 version) {
                const std::lock_guard lock(state->mutex);
                if (state->refreshVersion != version) view = refreshingView();
                else state->manualRefresh = false;
                if (state->eventFatal) view = DeckHudHostReducer::unavailable();
                // An unavailable/failed GET doesn't revoke an established event
                // port. A fresh valid status explicitly removing it does.
                if (view.value("hostFresh").toBool() && result.value->eventsHttpsPort != state->eventsPort) {
                    state->eventsPort = result.value->eventsHttpsPort; ++state->eventEpoch; state->wake.notify_all();
                }
                state->live = (view.value("canTune").toBool() || view.value("canSetBitrate").toBool()) && result.ok() ? result.value->live : std::nullopt;
                state->view = view; state->observed = Clock::now();
                const bool current = state->refreshVersion == version && !state->eventFatal;
                const bool doctorAuthorized = current && view.value("canTune").toBool() && bool(target->doctorAction);
                if (recoveryPending && doctorAuthorized && result.value->live) {
                    const auto recovered = state->doctor.restore(savedReceipt, *result.value->live, QDateTime::currentMSecsSinceEpoch());
                    recoveryPending = false; savedReceipt = {};
                    if (recovered == DeckDoctorActions::Recovery::Invalid) {
                        state->doctorStorageReady = false; state->doctor.storageFailure();
                    }
                }
                state->doctor.observe(current && result.ok() ? &*result.value : nullptr,
                    doctorAuthorized, nowMs());
            };
            const auto beginRead = [&] {
                const std::lock_guard lock(state->mutex); state->refreshRequested = false; return state->refreshVersion;
            };
            const auto sameSession = [](const DeckLiveTuningTelemetry& a, const DeckLiveTuningTelemetry& b) {
                return a.instance == b.instance && a.generation == b.generation && a.appSession == b.appSession;
            };
            std::optional<Shared::Intent> awaiting;
            Clock::time_point confirmationDeadline;
            const auto confirmBitrate = [&](const DeckPolarisResult<DeckHostTelemetry>& result, const QVariantMap& view, quint64 version) {
                if (!awaiting) return;
                const std::lock_guard lock(state->mutex);
                const bool expired = Clock::now() >= confirmationDeadline;
                if (!expired && !state->eventFatal && (state->refreshVersion != version || view.value("hostRefreshing").toBool())) {
                    state->message = "Refreshing host state before confirming the encoder…";
                    return; // Keep the existing deadline; an event is not a failed GET.
                }
                const bool matching = state->refreshVersion == version && !state->eventFatal && view.value("canSetBitrate").toBool() && result.value->live &&
                    sameSession(*result.value->live, awaiting->observed) && !result.value->live->enabled &&
                    result.value->live->qualityLimit == awaiting->bitrateKbps;
                const bool applied = matching && result.value->live->applied == awaiting->bitrateKbps;
                if (applied) state->message = "Applied " + bitrate(awaiting->bitrateKbps) + "bps. Live Tuning is off.";
                else if (!matching || expired) state->message = "The encoder change wasn't confirmed. Check the current bitrate before trying again.";
                else state->message = "Requested " + bitrate(awaiting->bitrateKbps) + "bps. Waiting for the encoder…";
                if (awaiting->profile) {
                    if (applied) state->syncResult("confirmed", "Profile saved. Bitrate applied now: " + bitrate(awaiting->bitrateKbps) + "bps. Resolution and frame rate apply to the next stream. Live Tuning is off.");
                    else if (!matching || expired) state->syncResult("unconfirmed", "Profile saved for the next stream. The live encoder change wasn't confirmed; check the current bitrate before trying again.");
                    else state->syncResult("confirming", "Profile saved. Waiting for the encoder to confirm " + bitrate(awaiting->bitrateKbps) + "bps. Resolution and frame rate apply to the next stream.");
                }
                if (applied || !matching || expired) { awaiting.reset(); state->busy = false; }
            };
            int delay = timing.intervalMs;
            while (valid()) {
                std::optional<Shared::Intent> intent;
                { const std::lock_guard lock(state->mutex); intent = std::exchange(state->pending, {}); }
                auto version = beginRead();
                auto result = target->fetch(cancelled);
                if (!valid()) break;
                auto view = project(result);
                publish(result, view, version);
                bool stop = permanent(result);
                confirmBitrate(result, view, version);
                if (intent && intent->profile) {
                    const auto requested = *intent;
                    const auto profile = *requested.profile;
                    const auto authorized = [&] {
                        return !stop && view.value(profile.clear ? "canSyncProfile" : "canSetBitrate").toBool() &&
                            result.ok() && result.value->live && sameSession(*result.value->live, requested.observed) &&
                            result.value->live->revision == requested.observed.revision &&
                            result.value->live->enabled == requested.observed.enabled &&
                            result.value->live->qualityLimit == requested.observed.qualityLimit &&
                            result.value->live->sequence > requested.observed.sequence;
                    };
                    QString phase = "changed", copy = "The session, permissions or profile changed. Refresh and review before choosing again.";
                    const auto mutationCancelled = [&] {
                        if (!valid()) return true;
                        const std::lock_guard lock(state->mutex); return state->refreshVersion != version;
                    };
                    if (authorized() && valid()) {
                        const auto reviewed = target->readProfile(mutationCancelled);
                        if (!valid()) break;
                        if (reviewed.ok() && reviewed.value->profileReview() == profile.reviewed &&
                            reviewed.value->displayOverride && reviewed.value->bitrateOverride && !mutationCancelled()) {
                            // Recheck owned session after the profile GET too. Neither the
                            // old UI map nor a successful profile read grants stream authority.
                            result = target->fetch(mutationCancelled);
                            if (!valid()) break;
                            view = project(result); publish(result, view, version); stop = permanent(result);
                            if (authorized() && !mutationCancelled()) {
                                { const std::lock_guard lock(state->mutex); state->syncResult("saving", "Saving the paired profile…"); }
                                const auto receipt = target->writeProfile(profile.display, requested.bitrateKbps, profile.clear,
                                    *result.value->live, mutationCancelled);
                                phase = "unconfirmed"; copy = "The profile save wasn't confirmed. Refresh to read its current values; Nova will not resend it.";
                                stop = permanent(receipt);
                                if (!valid()) break;
                                if (!stop) {
                                    // One POST only. A lost receipt is followed by reads, never replay.
                                    const auto saved = target->readProfile(cancelled);
                                    if (!valid()) break;
                                    const auto matches = [&](const DeckHostSettings& s) {
                                        return s.desiredDisplay == profile.display && s.desiredBitrate == requested.bitrateKbps;
                                    };
                                    const bool confirmed = receipt.ok() && saved.ok() && matches(*receipt.value) && matches(*saved.value);
                                    if (saved.ok() && matches(*saved.value) && !confirmed)
                                        copy = "The saved profile now matches, but the save response wasn't confirmed. Review the live readings before another change.";
                                    version = beginRead(); result = target->fetch(cancelled);
                                    if (!valid()) break;
                                    view = project(result); publish(result, view, version); stop = permanent(result) || permanent(saved);
                                    if (confirmed && profile.clear) {
                                        phase = "saved"; copy = "Paired profile cleared for the next stream. No live bitrate change was requested; Nova defaults are unchanged.";
                                    } else if (confirmed) {
                                        awaiting = requested; confirmationDeadline = Clock::now() + std::chrono::milliseconds(timing.confirmationMs);
                                        { const std::lock_guard lock(state->mutex); state->syncResult("confirming", "Profile saved. Checking the encoder; resolution and frame rate apply to the next stream."); }
                                        confirmBitrate(result, view, version);
                                        const std::lock_guard lock(state->mutex); phase = state->syncPhase; copy = state->syncCopy;
                                    }
                                }
                                if (stop) { view = DeckHudHostReducer::unavailable(); publish({}, view, version); }
                            }
                        } else if (permanent(reviewed)) { stop = true; view = DeckHudHostReducer::unavailable(); publish({}, view, version); }
                    }
                    { const std::lock_guard lock(state->mutex); state->busy = awaiting.has_value(); state->syncResult(phase, copy); }
                    intent.reset();
                }
                if (intent) {
                    QString message = "Host settings or permissions changed. Review the current state before trying again.";
                    // Preserve the exact decision the player saw. A fresh snapshot may
                    // advance its sequence, but cannot silently rebase the intent onto
                    // a new revision, host instance, app session or preference.
                    const bool unchanged = !stop && view.value(intent->bitrateKbps ? "canSetBitrate" : "canTune").toBool() && result.value->live &&
                        sameSession(*result.value->live, intent->observed) &&
                        result.value->live->revision == intent->observed.revision &&
                        result.value->live->enabled == intent->observed.enabled &&
                        (!intent->bitrateKbps || result.value->live->qualityLimit == intent->observed.qualityLimit) &&
                        result.value->live->sequence > intent->observed.sequence;
                    if (unchanged && valid()) {
                        { const std::lock_guard lock(state->mutex); state->message = intent->bitrateKbps ? "Requesting fixed bitrate…" : "Saving Live Tuning…"; }
                        // The transport checks this immediately before dispatch, at
                        // TLS admission and during a blocked request. Never replay.
                        const auto mutationCancelled = [&] {
                            if (!valid()) return true;
                            const std::lock_guard lock(state->mutex); return state->refreshVersion != version;
                        };
                        const auto receipt = intent->bitrateKbps
                            ? target->setBitrate(intent->bitrateKbps, *result.value->live, mutationCancelled)
                            : target->setEnabled(intent->enabled, *result.value->live, mutationCancelled);
                        if (!valid()) break;
                        const bool accepted = receipt.ok() && *receipt.value;
                        message = receipt.httpStatus == 412
                            ? "Host settings changed. Review the refreshed state before trying again."
                            : "Couldn't confirm the change. Check the current state before trying again.";
                        stop = permanent(receipt);
                        if (stop) {
                            view = DeckHudHostReducer::unavailable(); publish({}, view, version);
                        } else {
                            { const std::lock_guard lock(state->mutex); state->message = "Checking the saved state…"; }
                            // Refresh even after a dropped/rejected response; the POST
                            // may have committed. Only this GET can paint a new state.
                            version = beginRead(); result = target->fetch(cancelled);
                            if (!valid()) break;
                            view = project(result);
                            publish(result, view, version);
                            stop = permanent(result);
                            if (accepted && intent->bitrateKbps) {
                                awaiting = *intent; confirmationDeadline = Clock::now() + std::chrono::milliseconds(timing.confirmationMs);
                                confirmBitrate(result, view, version);
                                const std::lock_guard lock(state->mutex); message = state->message;
                            } else if (accepted && view.value("hostFresh").toBool() && result.value->live &&
                                sameSession(*result.value->live, intent->observed) && result.value->live->enabled == intent->enabled)
                                message = intent->enabled ? "Live Tuning turned on." : "Live Tuning turned off. The last confirmed bitrate is held.";
                        }
                    }
                    { const std::lock_guard lock(state->mutex); state->busy = awaiting.has_value();
                        state->message = state->refreshVersion == version && !state->eventFatal ? message
                            : awaiting ? QStringLiteral("Refreshing host state before confirming the encoder…")
                            : QStringLiteral("Host state changed. Review the refreshed state before trying again."); }
                }
                if (stop) break;
                persistDoctor();
                std::optional<DeckDoctorRequest> doctorRequest;
                {
                    const std::lock_guard lock(state->mutex);
                    if (!state->busy && state->doctorStorageReady && target->doctorAction) doctorRequest = state->doctor.next(nowMs());
                    if (doctorRequest) { state->doctor.invalidate(); state->view = refreshingView(); state->live.reset(); }
                }
                // Commit the initial idempotency key/intent before HTTP can
                // leave. After a crash, that same request can be checked once
                // fresh scope has been established, never silently reapplied.
                if (doctorRequest && persistDoctor()) {
                    const auto mutationCancelled = [&] {
                        if (!valid()) return true;
                        const std::lock_guard lock(state->mutex); return state->refreshVersion != version;
                    };
                    const auto receipt = target->doctorAction(*doctorRequest, mutationCancelled);
                    if (!valid()) break;
                    { const std::lock_guard lock(state->mutex); state->doctor.complete(receipt.ok() ? receipt.value : std::nullopt, nowMs()); }
                    persistDoctor();
                    if (permanent(receipt)) break;
                    // A submitted action or ambiguous response always gets a fresh
                    // paired read before its receipt is actionable in the UI.
                    version = beginRead(); result = target->fetch(cancelled);
                    if (!valid()) break;
                    view = project(result); publish(result, view, version);
                    if (permanent(result)) break;
                }
                delay = result.ok() ? timing.intervalMs : std::min(timing.maxBackoffMs, delay * 2);
                std::unique_lock lock(state->mutex);
                // A burst of events requests at most one immediate refresh, with
                // a short floor to keep a noisy peer from spinning status GETs.
                if (state->refreshRequested) state->wake.wait_for(lock, std::chrono::milliseconds(50), [&] { return state->stopped || state->eventFatal; });
                else state->wake.wait_for(lock, std::chrono::milliseconds(delay), [&] {
                    return state->stopped || state->eventFatal || state->pending.has_value() || state->refreshRequested || state->doctor.busy();
                });
            }
        } catch (...) { /* No raw network/identity exception enters the HUD. */ }
        const std::lock_guard lock(state->mutex);
        state->view = DeckHudHostReducer::unavailable(); state->live.reset(); state->pending.reset();
        if (state->busy) state->message = "Couldn't confirm the change. Check the connection before trying again.";
        if (state->busy && state->syncChange) state->syncResult("unconfirmed", "The session ended or became unavailable. The profile change wasn't confirmed. Review it after reconnecting; Nova will not resend it.");
        state->busy = false;
        state->observing = state->manualRefresh = false;
        state->doctor = {};
    }));
    worker_->start();
}
DeckHudHostObserver::~DeckHudHostObserver() {
    shared_->stopped = true;
    shared_->wake.notify_all();
    worker_->wait();
}
bool DeckHudHostObserver::setLiveTuningEnabled(bool enabled) {
    const std::lock_guard lock(shared_->mutex);
    if (shared_->stopped || shared_->busy || shared_->doctor.busy() || !shared_->view.value("canTune").toBool() || !shared_->live ||
        shared_->live->enabled == enabled || Clock::now() - shared_->observed > std::chrono::milliseconds(shared_->staleMs)) return false;
    shared_->pending = Shared::Intent{enabled, *shared_->live}; shared_->busy = true;
    shared_->fixedChange = false; shared_->requestedBitrate = 0;
    shared_->syncChange = false; shared_->syncResult("idle", "");
    shared_->message = "Checking the current session…";
    shared_->wake.notify_all(); return true;
}
bool DeckHudHostObserver::setFixedBitrate(int bitrateKbps) {
    const std::lock_guard lock(shared_->mutex);
    if (bitrateKbps < 1000 || bitrateKbps > 300000 || shared_->stopped || shared_->busy || shared_->doctor.busy() ||
        !shared_->view.value("canSetBitrate").toBool() || !shared_->live ||
        Clock::now() - shared_->observed > std::chrono::milliseconds(shared_->staleMs)) return false;
    shared_->pending = Shared::Intent{false, *shared_->live, bitrateKbps}; shared_->busy = true;
    shared_->fixedChange = true; shared_->requestedBitrate = bitrateKbps;
    shared_->syncChange = false; shared_->syncResult("idle", "");
    shared_->message = "Checking the current session…";
    shared_->wake.notify_all(); return true;
}
bool DeckHudHostObserver::setSyncProfile(const QString& display, int bitrateKbps, bool clear, const QVariantMap& reviewed) {
    const std::lock_guard lock(shared_->mutex);
    if ((clear ? (!display.isEmpty() || bitrateKbps != 0) : !validHostProfile(display, bitrateKbps)) ||
        reviewed.size() != 5 || reviewed.value("revision").toString().isEmpty() ||
        shared_->stopped || shared_->eventFatal || shared_->busy || shared_->doctor.busy() ||
        !shared_->view.value("canSyncProfile").toBool() || (!clear && !shared_->view.value("canSetBitrate").toBool()) ||
        !shared_->live || Clock::now() - shared_->observed > std::chrono::milliseconds(shared_->staleMs)) return false;
    shared_->pending = Shared::Intent{false, *shared_->live, bitrateKbps, Shared::Profile{display, clear, reviewed}};
    shared_->busy = shared_->syncChange = true;
    shared_->fixedChange = !clear; shared_->requestedBitrate = bitrateKbps;
    shared_->message.clear(); shared_->syncResult("checking", "Rechecking the session and saved profile…");
    shared_->wake.notify_all(); return true;
}
bool DeckHudHostObserver::refreshDiagnostics() {
    const std::lock_guard lock(shared_->mutex);
    if (shared_->stopped || shared_->eventFatal || !shared_->observing || shared_->busy || shared_->doctor.busy() || shared_->manualRefresh ||
        shared_->view.value("hostRefreshing").toBool() || Clock::now() - shared_->lastManualRefresh < std::chrono::milliseconds(500)) return false;
    shared_->lastManualRefresh = Clock::now(); shared_->manualRefresh = true;
    ++shared_->refreshVersion; shared_->refreshRequested = true;
    shared_->view = refreshingView(); shared_->live.reset(); shared_->message.clear();
    shared_->doctor.invalidate();
    shared_->wake.notify_all(); return true;
}
bool DeckHudHostObserver::applyDoctorFix() {
    const std::lock_guard lock(shared_->mutex);
    if (shared_->stopped || shared_->eventFatal || shared_->busy || !shared_->doctorStorageReady || !shared_->view.value("hostFresh").toBool() ||
        Clock::now() - shared_->observed > std::chrono::milliseconds(shared_->staleMs) || !shared_->doctor.apply(nowMs())) return false;
    shared_->wake.notify_all(); return true;
}
bool DeckHudHostObserver::undoDoctorFix() {
    const std::lock_guard lock(shared_->mutex);
    if (shared_->stopped || shared_->eventFatal || shared_->busy || !shared_->doctorStorageReady || !shared_->view.value("hostFresh").toBool() ||
        Clock::now() - shared_->observed > std::chrono::milliseconds(shared_->staleMs) || !shared_->doctor.undo(nowMs())) return false;
    shared_->wake.notify_all(); return true;
}
bool DeckHudHostObserver::checkDoctorResult() {
    const std::lock_guard lock(shared_->mutex);
    if (shared_->stopped || shared_->eventFatal || shared_->busy || !shared_->doctorStorageReady || !shared_->view.value("hostFresh").toBool() ||
        Clock::now() - shared_->observed > std::chrono::milliseconds(shared_->staleMs) || !shared_->doctor.check(nowMs())) return false;
    shared_->wake.notify_all(); return true;
}
QVariantMap DeckHudHostObserver::snapshot() const {
    const std::lock_guard lock(shared_->mutex);
    auto view = shared_->stopped || Clock::now() - shared_->observed > std::chrono::milliseconds(shared_->staleMs)
        ? DeckHudHostReducer::unavailable() : shared_->view;
    view["tuningBusy"] = shared_->busy;
    view["bitrateBusy"] = shared_->busy && shared_->fixedChange;
    view["bitrateRequestKbps"] = shared_->requestedBitrate;
    view["syncBusy"] = shared_->busy && shared_->syncChange;
    view["syncPhase"] = shared_->syncPhase; view["syncCopy"] = shared_->syncCopy; view["syncVersion"] = shared_->syncVersion;
    if (shared_->busy) { view["canTune"] = false; view["canSetBitrate"] = false; view["canSyncProfile"] = false; }
    if (!shared_->message.isEmpty()) view[shared_->fixedChange ? "bitrateCopy" : "tuningCopy"] = shared_->message;
    view["diagnosticsRefreshing"] = shared_->manualRefresh || view.value("hostRefreshing").toBool();
    view["canRefreshDiagnostics"] = shared_->observing && !shared_->stopped && !shared_->eventFatal && !shared_->busy &&
        !shared_->doctor.busy() && !view.value("diagnosticsRefreshing").toBool() && Clock::now() - shared_->lastManualRefresh >= std::chrono::milliseconds(500);
    const auto doctor = shared_->doctor.view(nowMs());
    for (auto it = doctor.begin(); it != doctor.end(); ++it) view[it.key()] = it.value();
    if (shared_->doctor.busy()) { view["canTune"] = false; view["canSetBitrate"] = false; view["canSyncProfile"] = false; }
    if (shared_->busy || !shared_->doctorStorageReady || !view.value("hostFresh").toBool() || shared_->eventFatal || shared_->stopped) {
        view["doctorCanApply"] = false; view["doctorCanUndo"] = false; view["doctorCanCheck"] = false;
    }
    return view;
}
}
