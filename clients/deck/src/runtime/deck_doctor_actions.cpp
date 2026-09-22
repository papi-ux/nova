#include "runtime/deck_doctor_actions.h"
#include <QUuid>
#include <QCryptographicHash>
#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <cmath>

namespace nova::deck::runtime {
using namespace polaris;
namespace {
QString scopeDigest(const DeckLiveTuningTelemetry& live) {
    return QString::fromLatin1(QCryptographicHash::hash(QJsonDocument(QJsonArray{
        "nova-deck-doctor-stream-v1", live.instance, live.appSession, live.generation}).toJson(QJsonDocument::Compact), QCryptographicHash::Sha256).toHex());
}
std::optional<qint64> integer(const QJsonValue& v, qint64 min = 1, qint64 max = 9007199254740991LL) {
    const auto n = v.toDouble(-1);
    if (!v.isDouble() || !std::isfinite(n) || n < min || n > max || n != std::floor(n)) return {};
    return static_cast<qint64>(n);
}
}
QJsonObject DeckDoctorActions::checkpoint() const {
    if (!initial_ || !origin_) return {};
    auto body = doctorRequestBody(*initial_);
    if (!body) return {};
    body->remove("app_session_id"); // A fresh matching scope supplies this after restart.
    QJsonObject saved{{"version", 1}, {"scope", scopeDigest(*origin_)}, {"created_ms", createdEpochMs_},
        {"initial", *body}, {"delay_seconds", initial_->offer->delaySeconds},
        {"pending_action", inFlight_ || uncertain_ ? dispatched_ : QString{}}};
    if (receipt_ && receipt_->undo) saved["receipt"] = QJsonObject{{"state", receipt_->state},
        {"run", receipt_->runId}, {"undo", true}, {"delay_seconds", receipt_->delaySeconds}};
    return saved;
}
DeckDoctorActions::Recovery DeckDoctorActions::restore(const QJsonObject& saved, const DeckLiveTuningTelemetry& current, qint64 epochMs) {
    static const QRegularExpression digest("^[a-f0-9]{64}$");
    const auto created = integer(saved.value("created_ms"));
    if (saved.size() != (saved.contains("receipt") ? 7 : 6) || busy() || initial_ || integer(saved.value("version")) != 1 || !created ||
        !digest.match(saved.value("scope").toString()).hasMatch() || !saved.value("initial").isObject()) return Recovery::Invalid;
    if (*created > epochMs || epochMs - *created > 24 * 60 * 60 * 1000LL || saved.value("scope") != scopeDigest(current)) return Recovery::Obsolete;
    const auto body = saved.value("initial").toObject();
    const auto generation = integer(body.value("session_generation")), controller = integer(body.value("controller_revision"));
    const auto evidence = integer(body.value("evidence_revision")), target = integer(body.value("target_bitrate_kbps"), 1000, 300000);
    const auto delay = integer(saved.value("delay_seconds"), 8, 60);
    if (!generation || *generation != current.generation || !controller || !evidence || !target || !delay || body.contains("app_session_id")) return Recovery::Invalid;
    DeckDoctorOffer offer{body.value("action_id").toString(), body.value("source_result_id").toString(), current.appSession,
        *generation, *controller, *evidence, static_cast<int>(*target), static_cast<int>(*delay)};
    DeckDoctorRequest request{offer.action, current.appSession, body.value("request_id").toString(), {}, *generation, offer};
    auto reconstructed = doctorRequestBody(request);
    if (!reconstructed) return Recovery::Invalid;
    reconstructed->remove("app_session_id");
    if (*reconstructed != body) return Recovery::Invalid;
    std::optional<DeckDoctorReceipt> receipt;
    if (saved.contains("receipt")) {
        const auto r = saved.value("receipt").toObject(); const auto state = r.value("state").toString(), run = r.value("run").toString();
        const auto next = integer(r.value("delay_seconds"), 1, 60);
        if (r.size() != 4 || !next || !r.value("undo").isBool() || !r.value("undo").toBool() ||
            (state != "applying" && state != "watching" && state != "resolved") || !run.startsWith("doctor-run-") || run.size() <= 11 || run.size() > 256) return Recovery::Invalid;
        receipt = DeckDoctorReceipt{state, run, true, static_cast<int>(*next)};
    }
    const auto pending = saved.value("pending_action");
    if (!pending.isString() || (receipt ? (!pending.toString().isEmpty() && pending != "verify" && pending != "undo")
        : pending != request.action)) return Recovery::Invalid;
    dispatched_ = pending.toString();
    initial_ = request; origin_ = current; receipt_ = receipt; createdEpochMs_ = *created;
    recovered_ = true; uncertain_ = true; due_ = 0; deadline_ = 0; checks_ = 0;
    state_ = "recovered";
    message_ = !receipt ? "The saved request has no confirmed reply. Recover change sends the original request; Polaris may finish it if still valid."
        : dispatched_ == "undo" ? "Undo was interrupted. Finish Undo checks the original restore request for this stream."
        : "A saved Doctor change belongs to this stream. Check result before using Undo.";
    return Recovery::Restored;
}
void DeckDoctorActions::storageFailure() {
    inFlight_ = false; pending_.clear(); due_ = 0; uncertain_ = true;
    state_ = "attention"; message_ = "Nova couldn't save the Doctor receipt. Check local storage before changing live settings.";
}
bool DeckDoctorActions::fresh(qint64 now) const { return live_ && now - observed_ <= 3500; }
bool DeckDoctorActions::sameScope(const DeckLiveTuningTelemetry& live) const {
    return origin_ && live.instance == origin_->instance && live.appSession == origin_->appSession && live.generation == origin_->generation;
}
void DeckDoctorActions::invalidate() { live_.reset(); offer_.reset(); }
void DeckDoctorActions::retire(const QString& message) {
    initial_.reset(); receipt_.reset(); origin_.reset(); reviewed_.reset(); pending_.clear();
    due_ = 0; uncertain_ = recovered_ = false; dispatched_.clear(); state_ = "unavailable"; message_ = message;
}
void DeckDoctorActions::observe(const DeckHostTelemetry* sample, bool authorized, qint64 now) {
    invalidate();
    if (!sample) return; // A failed read pauses receipt authority without inventing an outcome.
    if (!authorized || !sample->live || sample->live->generation <= 0) {
        if (origin_) retire("This stream no longer authorizes the Doctor change.");
        return;
    }
    if (origin_ && !sameScope(*sample->live)) retire("The stream changed. The previous Doctor receipt is no longer active here.");
    live_ = sample->live; observed_ = now;
    if (sample->doctorOffer && !live_->enabled && live_->supported && live_->applied > 0 &&
        sample->doctorOffer->appSession == live_->appSession && sample->doctorOffer->generation == live_->generation)
        offer_ = sample->doctorOffer;
}
bool DeckDoctorActions::apply(qint64 now) {
    if (!fresh(now) || busy() || !offer_ || initial_ || (receipt_ && receipt_->undo)) return false;
    reviewed_ = offer_; origin_ = live_; pending_ = "apply"; message_ = "Checking the proposed fix against the current session…";
    return true;
}
bool DeckDoctorActions::undo(qint64 now) {
    if (!fresh(now) || busy() || recovered_ || uncertain_ || !receipt_ || !receipt_->undo || !sameScope(*live_)) return false;
    pending_ = "undo"; message_ = "Checking the current session before Undo…"; return true;
}
bool DeckDoctorActions::check(qint64 now) {
    if (!fresh(now) || busy() || !origin_ || !sameScope(*live_) ||
        (!(uncertain_ && initial_) && !(receipt_ && receipt_->undo))) return false;
    pending_ = "check"; message_ = "Checking the Doctor result…"; return true;
}
std::optional<DeckDoctorRequest> DeckDoctorActions::next(qint64 now) {
    if (inFlight_) return {};
    const bool automatic = pending_.isEmpty() && due_ > 0 && now >= due_;
    if (pending_.isEmpty() && !automatic) return {};
    const auto operation = pending_; pending_.clear();
    if (!fresh(now) || !origin_ || !sameScope(*live_)) {
        due_ = 0; state_ = "attention"; message_ = "Readings are unavailable. Refresh, then check the result when the host returns.";
        if (operation == "apply") { reviewed_.reset(); origin_.reset(); }
        return {};
    }
    if (automatic && (now >= deadline_ || checks_ >= 64)) {
        due_ = 0; state_ = "attention"; message_ = "Verification is taking longer than expected. Check the result or Undo."; return {};
    }
    DeckDoctorRequest request;
    if (operation == "apply") {
        if (!offer_ || !reviewed_ || offer_->action != reviewed_->action || offer_->targetKbps != reviewed_->targetKbps ||
            offer_->controllerRevision != reviewed_->controllerRevision || live_->sequence <= origin_->sequence) {
            reviewed_.reset(); origin_.reset(); state_ = "changed";
            message_ = "The proposed fix changed. Review the new recommendation before applying it."; return {};
        }
        request = {offer_->action, offer_->appSession, QUuid::createUuid().toString(QUuid::WithoutBraces), {}, offer_->generation, offer_};
        initial_ = request; receipt_.reset(); uncertain_ = false; checks_ = 0; deadline_ = now + 180000;
        createdEpochMs_ = QDateTime::currentMSecsSinceEpoch();
        message_ = "Requesting the guarded bitrate change…";
    } else if (operation == "check" && uncertain_ && initial_ && !receipt_) {
        // Only an explicit Check result can repeat the original idempotent
        // request. It retains every byte of authority and the same request ID.
        request = *initial_; message_ = "Recovering the original Doctor receipt…";
    } else if (receipt_ && receipt_->undo) {
        request = {(operation == "undo" || (operation == "check" && uncertain_ && dispatched_ == "undo")) ? "undo" : "verify", origin_->appSession, {}, receipt_->runId, origin_->generation, {}};
        message_ = request.action == "undo" ? "Restoring the previous live settings…" : "Checking encoder and stream evidence…";
        if (request.action == "verify") ++checks_;
    } else return {};
    if (recovered_ && operation == "check" && deadline_ == 0) deadline_ = now + 180000;
    dispatched_ = request.action;
    due_ = 0; inFlight_ = true;
    return request;
}
void DeckDoctorActions::complete(const std::optional<DeckDoctorReceipt>& receipt, qint64 now) {
    inFlight_ = false;
    if (!receipt) {
        uncertain_ = true; due_ = 0; state_ = "attention";
        message_ = dispatched_ == "undo" ? "Undo wasn't confirmed. Finish Undo checks the original restore request."
            : !receipt_ ? "The change wasn't confirmed. Recover change sends the original request; Polaris may finish it if still valid."
            : "The host outcome wasn't confirmed. Check the result before making another change."; return;
    }
    receipt_ = receipt; uncertain_ = recovered_ = false; state_ = receipt->state;
    if (state_ == "applying" || state_ == "watching") {
        message_ = state_ == "applying" ? "Waiting for the encoder to acknowledge the change…"
            : "Collecting post-change stream evidence…";
        due_ = now + receipt->delaySeconds * 1000;
    } else {
        due_ = 0;
        if (state_ == "resolved") message_ = "Verified by Polaris using post-change stream evidence. Undo is available for this stream.";
        else if (state_ == "undone") message_ = "Polaris confirmed the previous live settings were restored.";
        else if (state_ == "rolled_back") message_ = "Verification did not pass. Polaris restored the previous live settings.";
        else if (state_ == "superseded") message_ = "A newer setting replaced this fix. Doctor left that choice unchanged.";
        else if (state_ == "rejected") message_ = "The host rejected this request. Refresh and review the current session before trying again.";
        else message_ = "The restore target was requested, but the encoder did not confirm it. Check the current stream readings.";
        if (!receipt->undo) initial_.reset();
    }
}
QVariantMap DeckDoctorActions::view(qint64 now) const {
    const bool ready = fresh(now) && !busy();
    const bool canUndo = ready && !recovered_ && !uncertain_ && receipt_ && receipt_->undo && sameScope(*live_);
    const bool canApply = ready && offer_ && !initial_ && !(receipt_ && receipt_->undo);
    const bool canCheck = ready && origin_ && sameScope(*live_) && ((uncertain_ && initial_) || (receipt_ && receipt_->undo));
    QString proposal;
    if (offer_) proposal = offer_->action == "lower_bitrate"
        ? QString("Lower bitrate toward %1 Mbps. Polaris checks the result and restores your previous settings if verification fails.").arg(offer_->targetKbps / 1000.0, 0, 'f', 1)
        : QString("Restore quality in steps toward %1 Mbps. Polaris checks each step and restores your previous settings if verification fails.").arg(offer_->targetKbps / 1000.0, 0, 'f', 1);
    return {{"doctorCanApply", canApply}, {"doctorCanUndo", canUndo}, {"doctorCanCheck", canCheck},
        {"doctorActionBusy", busy()}, {"doctorActionState", state_}, {"doctorActionMessage", message_},
        {"doctorProposal", proposal}, {"doctorActionLabel", offer_ ? QString("Auto Fix · %1 Mbps").arg(offer_->targetKbps / 1000.0, 0, 'f', 1) : QString{}},
        {"doctorCheckLabel", uncertain_ && dispatched_ == "undo" ? "Finish Undo" : uncertain_ && initial_ && !receipt_ ? "Recover change" : "Check result"},
        {"doctorHasReceipt", (receipt_ && receipt_->undo) || initial_.has_value()}};
}
}
