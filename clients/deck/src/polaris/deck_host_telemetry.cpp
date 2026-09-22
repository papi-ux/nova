#include "polaris/deck_host_telemetry.h"
#include "polaris/deck_doctor.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QRegularExpression>
#include <cmath>
#include <limits>

namespace nova::deck::polaris {
namespace {
std::optional<qint64> integer(const QJsonValue& value, qint64 max = 9007199254740991LL) {
    const double n = value.toDouble(-1);
    if (!value.isDouble() || !std::isfinite(n) || n < 0 || n > max || std::floor(n) != n) return {};
    return static_cast<qint64>(n);
}
QString text(const QJsonObject& object, const char* key, int limit = 256) {
    const auto value = object.value(key);
    if (!value.isString() || value.toString().size() > limit) return {};
    return value.toString();
}
QString code(const QJsonObject& object, const char* key) { return text(object, key, 80).trimmed().toLower(); }
bool in(const QString& value, std::initializer_list<const char*> choices) {
    for (const auto* choice : choices) if (value == choice) return true;
    return false;
}
}
std::optional<DeckLiveTuningTelemetry> parseLiveTuningTelemetry(const QJsonObject& o) {
    static const QRegularExpression hash("^[a-fA-F0-9]{64}$");
    if (integer(o.value("version")) != 1 || !o.value("enabled").isBool() || !o.value("supported").isBool() ||
        text(o, "scope") != "host" || !hash.match(text(o, "configuration_revision")).hasMatch()) return {};
    DeckLiveTuningTelemetry v;
    v.enabled = o.value("enabled").toBool(); v.supported = o.value("supported").toBool();
    v.state = text(o, "state"); v.revision = text(o, "configuration_revision");
    v.instance = text(o, "host_instance"); v.appSession = text(o, "app_session_id");
    const auto sequence = integer(o.value("sequence")), generation = integer(o.value("session_generation"));
    const auto quality = integer(o.value("quality_limit_kbps"), std::numeric_limits<int>::max());
    const auto requested = integer(o.value("requested_bitrate_kbps"), std::numeric_limits<int>::max());
    const auto applied = integer(o.value("applied_bitrate_kbps"), std::numeric_limits<int>::max());
    if (!sequence || !*sequence || !generation || !quality || !requested || !applied ||
        v.instance.trimmed().isEmpty() || !o.value("app_session_id").isString() ||
        !in(v.state, {"off", "waiting", "unavailable", "applying", "measuring", "adjusting", "stable"})) return {};
    v.sequence = *sequence; v.generation = *generation;
    v.qualityLimit = static_cast<int>(*quality); v.requested = static_cast<int>(*requested); v.applied = static_cast<int>(*applied);
    return v;
}
std::optional<DeckHostTelemetry> parseHostTelemetry(std::string_view json) {
    if (json.size() > 128 * 1024) return {};
    QJsonParseError error;
    const auto doc = QJsonDocument::fromJson(QByteArray(json.data(), static_cast<qsizetype>(json.size())), &error);
    if (error.error != QJsonParseError::NoError || !doc.isObject()) return {};
    const auto o = doc.object(), controls = o.value("controls").toObject();
    DeckHostTelemetry t;
    t.active = o.value("streaming_active").toBool(false);
    t.owned = o.value("owned_by_client").toBool(false);
    t.ending = o.value("shutdown_requested").toBool(false) || controls.value("shutdown_in_progress").toBool(false);
    t.role = code(o, "client_role");
    t.authorityValid = o.value("streaming_active").isBool() && o.value("owned_by_client").isBool() &&
        in(t.role, {"owner", "viewer", "none"}) && controls.value("host_tuning_allowed").isBool() &&
        (!o.contains("shutdown_requested") || o.value("shutdown_requested").isBool()) &&
        (!controls.contains("shutdown_in_progress") || controls.value("shutdown_in_progress").isBool());
    t.hostTuningAllowed = controls.value("host_tuning_allowed").toBool(false);
    t.gameId = static_cast<int>(integer(o.value("game_id"), std::numeric_limits<int>::max()).value_or(0));
    t.eventsHttpsPort = static_cast<int>(integer(o.value("events_https_port"), 65535).value_or(0));
    t.gameUuid = text(o, "game_uuid"); t.sessionToken = text(o, "session_token"); t.appSession = text(o, "app_session_id");
    t.generation = integer(o.value("session_generation"));
    t.livePresent = o.contains("live_tuning");
    if (o.value("live_tuning").isObject()) t.live = parseLiveTuningTelemetry(o.value("live_tuning").toObject());
    auto enabled = o.value("tuning").toObject().value("adaptive_bitrate_enabled");
    if (enabled.isUndefined()) enabled = o.value("adaptive_bitrate_enabled");
    if (enabled.isBool()) t.legacyTuning = enabled.toBool();

    const auto health = o.value("health").toObject();
    auto doctorValue = o.value("doctor");
    if (doctorValue.isUndefined()) doctorValue = health.value("doctor");
    const auto doctor = doctorValue.toObject();
    t.doctor = doctorPresentation(doctor);
    t.doctorOffer = parseDoctorOffer(doctor);
    t.doctorAuthoritative = integer(doctor.value("version")).value_or(0) >= 2 && !text(doctor, "result_id").trimmed().isEmpty();
    const auto status = code(doctor, "status"), severity = code(doctor, "severity"), light = code(doctor, "traffic_light");
    t.doctorVerdictPresent = t.doctorAuthoritative && (!status.isEmpty() || !severity.isEmpty() || !light.isEmpty());
    t.doctorHealthy = t.doctorAuthoritative && status == "ok" && severity == "info" && light == "green";
    t.primaryIssue = code(t.doctorAuthoritative ? doctor : health, "primary_issue");
    t.grade = code(health, "grade");
    t.hostLimited = t.primaryIssue == "host_render_limited" || (!t.doctorAuthoritative && health.value("host_render_limited").toBool(false));
    const auto downgrade = code(health, "hdr_downgrade_reason");
    t.hdrDowngraded = code(health, "primary_issue") == "hdr_downgraded" ||
        (!downgrade.isEmpty() && !in(downgrade, {"none", "unknown", "not_applicable"}));
    const auto evidence = doctor.value("evidence").toArray();
    if (evidence.size() > 128) return {}; // Bound processing as well as wire bytes.
    for (const auto& value : evidence) {
        const auto item = value.toObject();
        const auto id = code(item, "id"), state = code(item, "status");
        if (!in(state, {"watch", "warning", "fail", "degraded", "needs_action"}) || id == "control_channel_packet_loss") continue;
        if (id == "packet_loss" && (state != "fail" || code(item, "source") != "media_transport")) continue;
        if (id == "latency" && state != "fail") continue;
        if (id != "display_mode_decision" && t.doctorHealthy && state == "watch") continue;
        t.evidenceWarning = true;
        t.displayOverride |= id == "display_mode_decision";
        t.hostWarning |= in(id, {"capture_path", "encoder", "encoder_selection", "frame_pacing", "target_fps_gap", "source_capture", "encode_cadence", "effective_quality_ceiling", "display_mode_decision"});
        t.networkWarning |= in(id, {"packet_loss", "latency", "transport"});
        t.clientWarning |= in(id, {"decoder", "delivery_cadence", "receive_decode_render", "presentation"});
    }
    return t;
}
}
