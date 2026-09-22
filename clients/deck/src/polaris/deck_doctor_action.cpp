#include "polaris/deck_doctor_action.h"
#include "polaris/deck_doctor.h"
#include <QJsonArray>
#include <QRegularExpression>
#include <cmath>

namespace nova::deck::polaris {
namespace {
std::optional<qint64> number(const QJsonValue& v, qint64 min = 1, qint64 max = 9007199254740991LL) {
    const auto n = v.toDouble(-1);
    if (!v.isDouble() || !std::isfinite(n) || n < min || n > max || n != std::floor(n)) return {};
    return static_cast<qint64>(n);
}
bool boolean(const QJsonObject& o, const char* key, bool expected) {
    const auto v = o.value(key); return v.isBool() && v.toBool() == expected;
}
QString token(const QJsonObject& o, const char* key) {
    const auto v = o.value(key);
    return v.isString() && !v.toString().trimmed().isEmpty() && v.toString().size() <= 256 ? v.toString() : QString{};
}
bool emptyString(const QJsonObject& o, const char* key) { return o.value(key).isString() && o.value(key).toString().isEmpty(); }
bool liveAction(const QString& action) { return action == "lower_bitrate" || action == "restore_quality"; }
bool scope(const QJsonObject& o, const DeckDoctorRequest& r) {
    return token(o, "app_session_id") == r.appSession && number(o.value("session_generation")) == r.generation;
}
bool optionalScope(const QJsonObject& o, const DeckDoctorRequest& r) {
    return (!o.contains("app_session_id") && !o.contains("session_generation")) || scope(o, r);
}
}
std::optional<DeckDoctorOffer> parseDoctorOffer(const QJsonObject& d) {
    if (doctorPresentation(d).isEmpty()) return {};
    const auto a = d.value("safe_recovery_action").toObject(), p = a.value("payload_preview").toObject();
    const auto v = a.value("verification").toObject(), u = a.value("undo").toObject();
    DeckDoctorOffer offer;
    offer.action = token(a, "id"); offer.result = token(d, "result_id"); offer.appSession = token(p, "app_session_id");
    const auto gen = number(p.value("session_generation")), controller = number(p.value("controller_revision"));
    const auto evidence = number(p.value("evidence_revision")), target = number(p.value("target_bitrate_kbps"), 1000, 300000);
    const auto delay = number(v.value("delay_seconds"), 8, 60);
    // The observational Android contract intentionally advertises the web route
    // and false owner_tuning_allowed. Paired ownership is checked in session status;
    // transport always uses our fixed paired route, never a host-supplied URL.
    if (!liveAction(offer.action) || offer.result.isEmpty() || offer.appSession.isEmpty() || !gen || !controller || !evidence || !target || !delay ||
        a.value("capability") != "auto_fix" || a.value("kind") != "live_tuning" || a.value("endpoint") != "/api/doctor/action" ||
        a.value("method") != "POST" || !boolean(a, "destructive", false) || !boolean(a, "requires_confirmation", false) ||
        !boolean(a, "requires_owner", true) || !boolean(a, "allowed_in_viewer_mode", false) || !boolean(a, "owner_tuning_allowed", false) ||
        !emptyString(a, "paired_endpoint") || p.value("action_id") != offer.action || p.value("source_result_id") != offer.result ||
        v.value("endpoint") != "/api/doctor/action" || !boolean(u, "supported", true) || u.value("endpoint") != "/api/doctor/action" ||
        !emptyString(u, "paired_endpoint")) return {};
    QJsonObject loss, latency, ceiling;
    for (const auto& item : d.value("evidence").toArray()) {
        const auto e = item.toObject();
        if (e.value("id") == "packet_loss") loss = e;
        if (e.value("id") == "latency") latency = e;
        if (e.value("id") == "effective_quality_ceiling") ceiling = e;
    }
    const auto measured = [](const QJsonObject& e, const char* source, const char* state, double max) {
        const auto v = e.value("value"); const auto n = v.toDouble(-1);
        return e.value("source") == source && e.value("status") == state && v.isDouble() && std::isfinite(n) && n >= 0 && n <= max;
    };
    if (offer.action == "lower_bitrate") {
        if (d.value("primary_issue") != "network_jitter" || v.value("mode") != "live_telemetry" ||
            !((measured(loss, "media_transport", "fail", 100) && loss.value("value").toDouble() > 2) ||
              (measured(latency, "stream_stats", "fail", 1000000) && latency.value("value").toDouble() >= 45))) return {};
    } else {
        const bool lossClear = (measured(loss, "media_transport", "pass", 100) && loss.value("value").toDouble() <= 2) ||
            (loss.value("source") == "unavailable" && loss.value("status") == "unknown" && loss.value("value").isNull());
        if (d.value("primary_issue") != "quality_reduced_live" || v.value("mode") != "graduated_live_telemetry" ||
            !measured(latency, "stream_stats", "pass", 1000000) || latency.value("value").toDouble() >= 45 || !lossClear ||
            ceiling.value("source") != "launch_policy" || ceiling.value("status") != "watch" ||
            number(ceiling.value("value"), 1000, 300000) != target) return {};
    }
    offer.generation = *gen; offer.controllerRevision = *controller; offer.evidenceRevision = *evidence;
    offer.targetKbps = static_cast<int>(*target); offer.delaySeconds = static_cast<int>(*delay);
    return offer;
}
std::optional<QJsonObject> doctorRequestBody(const DeckDoctorRequest& r) {
    if (r.appSession.trimmed().isEmpty() || r.appSession.size() > 256 || r.generation <= 0 || r.generation > 9007199254740991LL) return {};
    QJsonObject body{{"action_id", r.action}, {"app_session_id", r.appSession}, {"session_generation", r.generation}};
    if (liveAction(r.action)) {
        static const QRegularExpression uuid("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$");
        if (!r.offer || !r.runId.isEmpty() || !uuid.match(r.requestId).hasMatch()) return {};
        const auto& o = *r.offer;
        if (o.action != r.action || o.appSession != r.appSession || o.generation != r.generation || o.result.trimmed().isEmpty() ||
            o.result.size() > 256 || o.targetKbps < 1000 || o.targetKbps > 300000 || o.controllerRevision <= 0 ||
            o.controllerRevision > 9007199254740991LL || o.evidenceRevision <= 0 || o.evidenceRevision > 9007199254740991LL) return {};
        body.insert("request_id", r.requestId); body.insert("source_result_id", o.result);
        body.insert("target_bitrate_kbps", o.targetKbps); body.insert("controller_revision", o.controllerRevision);
        body.insert("evidence_revision", o.evidenceRevision);
    } else if ((r.action == "verify" || r.action == "undo") && r.runId.startsWith("doctor-run-") &&
        r.runId.size() > 11 && r.runId.size() <= 256 && r.requestId.isEmpty() && !r.offer) body.insert("run_id", r.runId);
    else return {};
    return body;
}
std::optional<DeckDoctorReceipt> parseDoctorReceipt(const QJsonObject& b, int http, const DeckDoctorRequest& r) {
    if (!doctorRequestBody(r) || !scope(b, r) || !b.value("status").isBool() || !b.value("changed").isBool()) return {};
    const QString state = token(b, "state"), run = token(b, "run_id");
    if (http == 409 && boolean(b, "status", false) && boolean(b, "changed", false) &&
        (state == "evidence_changed" || state == "scope_unavailable" || state == "scope_mismatch" || state == "expired" ||
         state == "generation_action_limit")) return DeckDoctorReceipt{"rejected", {}, false, 0};
    if (!run.startsWith("doctor-run-") || run.size() <= 11 || (liveAction(r.action) ? token(b, "request_id") != r.requestId : run != r.runId)) return {};
    const auto u = b.value("undo").toObject(), v = b.value("verification").toObject();
    const bool undo = boolean(u, "available", true) && u.value("action_id") == "undo" && token(u, "run_id") == run && optionalScope(u, r);
    const bool noUndo = boolean(u, "available", false) && (!u.contains("action_id") || emptyString(u, "action_id"));
    const auto delay = number(v.value("delay_seconds"), 8, 60);
    const bool verifies = delay && v.value("action_id") == "verify" && token(v, "run_id") == run && optionalScope(v, r);
    const bool changed = b.value("changed").toBool();
    if (state == "rollback_unconfirmed") {
        if (http != 409 || !boolean(b, "status", false) || !changed || !noUndo) return {};
    } else {
        if (http != 200 || !boolean(b, "status", true)) return {};
        if (state == "applying" || state == "watching") {
            if (r.action == "undo" || !undo || (liveAction(r.action) && !verifies) ||
                (r.action == "verify" && (changed ? !verifies : !v.isEmpty()))) return {};
        } else if (state == "resolved") {
            const auto window = b.value("verification_window").toObject();
            if (r.action != "verify" || changed || !undo || !v.isEmpty() || !boolean(window, "complete", true) ||
                !number(window.value("samples")) || (b.contains("encoder_application_confirmed") && !boolean(b, "encoder_application_confirmed", true))) return {};
        } else if (state == "undone") {
            if (r.action != "undo" || !changed || !noUndo || !v.isEmpty()) return {};
        } else if (state == "rolled_back" || state == "superseded") {
            if (changed != (state == "rolled_back") || !noUndo || !v.isEmpty()) return {};
        } else return {};
    }
    int next = verifies ? static_cast<int>(*delay) : 8;
    if (b.contains("retry_after_seconds")) {
        const auto retry = number(b.value("retry_after_seconds"), 1, 60);
        if (!retry) return {};
        next = static_cast<int>(*retry);
    }
    return DeckDoctorReceipt{state, run, undo, next};
}
}
