#pragma once
#include "polaris/deck_doctor_action.h"
#include <QJsonArray>
#include <QJsonDocument>

namespace doctor_fixture {
using namespace nova::deck::polaris;
inline QJsonObject doctor(bool restore = false) {
    const QString action = restore ? "restore_quality" : "lower_bitrate";
    QJsonArray evidence{
        QJsonObject{{"id", "packet_loss"}, {"source", "media_transport"}, {"status", restore ? "pass" : "fail"}, {"value", restore ? 0 : 6}},
        QJsonObject{{"id", "latency"}, {"source", "stream_stats"}, {"status", "pass"}, {"value", 4}},
        QJsonObject{{"id", "effective_quality_ceiling"}, {"source", "launch_policy"}, {"status", "watch"}, {"value", 40000}}};
    return {{"version", 2}, {"result_id", "private-result"}, {"status", "needs_action"}, {"severity", "warning"},
        {"traffic_light", "amber"}, {"primary_issue", restore ? "quality_reduced_live" : "network_jitter"}, {"evidence", evidence},
        {"safe_recovery_action", QJsonObject{{"id", action}, {"capability", "auto_fix"}, {"kind", "live_tuning"},
            {"endpoint", "/api/doctor/action"}, {"method", "POST"}, {"destructive", false}, {"requires_confirmation", false},
            {"requires_owner", true}, {"allowed_in_viewer_mode", false}, {"owner_tuning_allowed", false}, {"paired_endpoint", ""},
            {"payload_preview", QJsonObject{{"action_id", action}, {"source_result_id", "private-result"}, {"app_session_id", "fixture-session"},
                {"session_generation", 41}, {"controller_revision", 3}, {"evidence_revision", 12}, {"target_bitrate_kbps", restore ? 40000 : 16000}}},
            {"verification", QJsonObject{{"mode", restore ? "graduated_live_telemetry" : "live_telemetry"}, {"delay_seconds", 8}, {"endpoint", "/api/doctor/action"}}},
            {"undo", QJsonObject{{"supported", true}, {"endpoint", "/api/doctor/action"}, {"paired_endpoint", ""}}}}}};
}
inline QJsonObject envelope(int sequence = 1) {
    return {{"streaming_active", true}, {"owned_by_client", true}, {"client_role", "owner"},
        {"game_id", 17}, {"game_uuid", "private-game"}, {"session_token", "private-token"},
        {"app_session_id", "fixture-session"}, {"session_generation", 41},
        {"controls", QJsonObject{{"host_tuning_allowed", true}}}, {"doctor", doctor()},
        {"live_tuning", QJsonObject{{"version", 1}, {"scope", "host"}, {"enabled", false}, {"supported", true}, {"state", "off"},
            {"configuration_revision", QString(64, 'a')}, {"host_instance", "fixture-instance"}, {"sequence", sequence},
            {"app_session_id", "fixture-session"}, {"session_generation", 41}, {"quality_limit_kbps", 40000},
            {"requested_bitrate_kbps", 20000}, {"applied_bitrate_kbps", 20000}}}};
}
inline QJsonObject receipt(const DeckDoctorRequest& request, QString state = {}) {
    if (state.isEmpty()) state = request.action == "undo" ? "undone" : request.action == "verify" ? "resolved" : "applying";
    const QString run = request.runId.isEmpty() ? "doctor-run-fixture" : request.runId;
    const bool active = state == "applying" || state == "watching" || state == "resolved";
    QJsonObject b{{"status", state != "rollback_unconfirmed"}, {"changed", state == "undone" || state == "rolled_back" ||
        state == "rollback_unconfirmed" || (state == "applying" && request.action != "verify")},
        {"state", state}, {"run_id", run}, {"app_session_id", request.appSession}, {"session_generation", request.generation},
        {"message", "private host prose"}, {"undo", active ? QJsonObject{{"available", true}, {"action_id", "undo"}, {"run_id", run}}
            : QJsonObject{{"available", false}}}};
    if (!request.requestId.isEmpty()) b["request_id"] = request.requestId;
    if ((state == "applying" || state == "watching") && request.action != "verify")
        b["verification"] = QJsonObject{{"action_id", "verify"}, {"delay_seconds", 8}, {"run_id", run}};
    if (state == "resolved") b["verification_window"] = QJsonObject{{"complete", true}, {"samples", 8}};
    return b;
}
}
