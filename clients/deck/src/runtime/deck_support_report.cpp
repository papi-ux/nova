#include "runtime/deck_support_report.h"
#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSet>
#include <QStandardPaths>
#include <QUuid>
#include <cmath>
#ifdef Q_OS_UNIX
#include <unistd.h>
#endif

namespace nova::deck::runtime {
namespace {
QString choice(const QVariant& value, std::initializer_list<const char*> choices) {
    if (value.metaType().id() != QMetaType::QString) return "unavailable";
    for (auto c : choices) if (value.toString() == c) return c;
    return "unavailable";
}
bool flag(const QVariantMap& map, const char* key) {
    const auto v = map.value(key);
    return v.metaType().id() == QMetaType::Bool && v.toBool();
}
QJsonValue number(const QVariant& value, const QString& suffix, double max = 1000000000) {
    if (value.metaType().id() != QMetaType::QString || value.toString().size() > 32) return QJsonValue::Null;
    const QRegularExpression expression("\\A([0-9]{1,10}(?:\\.[0-9]{1,3})?)" + QRegularExpression::escape(suffix) + "\\z");
    const auto match = expression.match(value.toString());
    const auto n = match.captured(1).toDouble();
    return match.hasMatch() && std::isfinite(n) && n <= max ? QJsonValue(n) : QJsonValue(QJsonValue::Null);
}
QJsonObject measurement(const QVariant& value) {
    const auto label = choice(value, {"Active", "Inactive", "Supported", "Not supported"});
    if (label != "unavailable") return {{"state", label}};
    for (auto unit : {"Mbps", "ms", "FPS", "%", "frames"}) {
        const auto n = number(value, QString(" ") + unit, QString(unit) == "%" ? 100 : 1000000000);
        if (!n.isNull()) return {{"value", n}, {"unit", unit}};
    }
    return {{"value", QJsonValue::Null}};
}
}
QJsonObject deckSupportReport(const QVariantMap& hud) {
    const bool hostFresh = flag(hud, "hostFresh"), clientFresh = flag(hud, "fresh");
    QJsonObject host{{"available", hostFresh}}, client{{"available", clientFresh}}, net;
    for (const auto& pair : {std::pair{"appliedBitrate", "encoder_applied_mbps"}, {"qualityLimit", "quality_ceiling_mbps"}})
        host[pair.second] = hostFresh ? number(hud.value(pair.first), "M", 300) : QJsonValue(QJsonValue::Null);
    host["processing_ms"] = clientFresh ? number(hud.value("host"), "ms") : QJsonValue(QJsonValue::Null);
    for (const auto& pair : {std::pair{"incoming", "incoming_fps"}, {"decoded", "decoded_fps"}, {"fps", "composed_fps"}})
        client[pair.second] = clientFresh ? number(hud.value(pair.first), "", 1000) : QJsonValue(QJsonValue::Null);
    client["codec"] = choice(hud.value("codec"), {"H.264", "HEVC", "HEVC10", "AV1", "AV1 Main10"});
    const auto resolution = hud.value("resolution").toString();
    const auto dimensions = QRegularExpression("\\A([1-9][0-9]{1,3})×([1-9][0-9]{1,3})\\z").match(resolution.left(32));
    if (resolution.size() <= 32 && dimensions.hasMatch()) {
        client["width"] = dimensions.captured(1).toInt(); client["height"] = dimensions.captured(2).toInt();
    }
    net["rtt_ms"] = clientFresh ? number(hud.value("rtt"), "ms") : QJsonValue(QJsonValue::Null);
    net["rtt_variation_ms"] = clientFresh ? number(hud.value("jitter"), "ms") : QJsonValue(QJsonValue::Null);
    net["video_payload_mbps"] = clientFresh ? number(hud.value("bitrate"), "M") : QJsonValue(QJsonValue::Null);
    net["client_media_loss_percent"] = QJsonValue::Null;
    const auto finding = hostFresh ? hud.value("doctor").toMap() : QVariantMap{};
    QJsonObject doctor{{"available", flag(finding, "available")},
        {"confidence", choice(finding.value("confidence"), {"low", "medium", "high", "unknown"})},
        {"action_state", choice(hud.value("doctorActionState"), {"recovered", "attention", "unavailable", "changed", "applying", "watching", "resolved", "undone", "rolled_back", "superseded", "rejected", "rollback_unconfirmed"})},
        {"saved_change", flag(hud, "doctorHasReceipt")}, {"undo_available", hostFresh && flag(hud, "doctorCanUndo")}};
    QJsonArray evidence; QSet<QString> seen;
    if (flag(finding, "available")) for (const auto& item : finding.value("evidence").toList().mid(0, 128)) {
        const auto row = item.toMap();
        const auto id = choice(row.value("id"), {"streaming", "capture_path", "encoder", "encoder_selection", "display_mode_decision",
            "packet_loss", "control_channel_packet_loss", "latency", "bitrate", "fec_protection", "live_bitrate_owner",
            "live_bitrate_control", "live_bitrate_retune", "effective_quality_ceiling", "target_fps_gap", "frame_pacing",
            "source_capture", "encode_cadence", "steam_input_compatibility", "decoder", "delivery_cadence", "receive_decode_render", "presentation"});
        if (id == "unavailable" || seen.contains(id)) continue;
        seen.insert(id);
        const auto source = choice(row.value("source"), {"Host stream telemetry", "Media transport", "Control channel", "Launch policy",
            "Encoder capability", "Bitrate controller", "Host Steam configuration", "Host packetizer", "Client telemetry"});
        // Preserve the control/media distinction even for a malformed caller.
        const bool unproven = source == "unavailable" || (id == "packet_loss" && source != "Media transport");
        evidence.append(QJsonObject{{"id", id}, {"source", source},
            {"grade", unproven ? "unavailable" : choice(row.value("grade"), {"Pass", "Observation", "Needs attention", "Unavailable"})},
            {"reading", unproven ? QJsonObject{{"value", QJsonValue::Null}} : measurement(row.value("reading"))}});
    }
    doctor["evidence"] = evidence;
    const auto version = QCoreApplication::applicationVersion();
    return {{"kind", "nova-deck-support-report-v1"}, {"generated_utc", QDateTime::currentDateTimeUtc().toString(Qt::ISODate)},
        {"client", "Nova Deck"}, {"version", QRegularExpression("\\A[0-9]{1,3}(?:\\.[0-9]{1,3}){2,3}\\z").match(version).hasMatch() ? version : "local-preview"},
        {"host", host}, {"network", net}, {"client_readings", client}, {"doctor", doctor},
        {"limitations", QJsonArray{"Composed FPS measures app draws, not panel presentation.",
            "Decoder latency and client media loss are not measured.", "Control-channel retries do not establish video loss."}},
        {"privacy", "Saved locally. No names, addresses, pairing data, session identities, artwork, journal contents or raw logs are included."}};
}
QString deckSupportReportDirectory() {
    const auto root = QStandardPaths::writableLocation(QStandardPaths::DocumentsLocation);
    return root.isEmpty() ? QString{} : root + "/Nova reports";
}
QVariantMap saveDeckSupportReport(const QVariantMap& hud, const QString& directory) {
    const auto failure = [](const char* text) { return QVariantMap{{"saved", false}, {"message", text}}; };
    if (directory.isEmpty() || !QDir::isAbsolutePath(directory)) return failure("The Documents folder is unavailable.");
    const QFileInfo info(directory);
    if (info.isSymLink() || (info.exists() && !info.isDir())) return failure("The Nova reports folder is unavailable.");
#ifdef Q_OS_UNIX
    if (info.exists() && info.ownerId() != ::geteuid()) return failure("The Nova reports folder is unavailable.");
#endif
    if (!QDir().mkpath(directory) || !QFile::setPermissions(directory, QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ExeOwner))
        return failure("Couldn't create the Nova reports folder. Check local storage and try again.");
    if (QDir(directory).entryList({"nova-report-*.json"}, QDir::Files).size() >= 64)
        return failure("The Nova reports folder is full. Remove an older report in Desktop Mode, then try again.");
    const auto name = "nova-report-" + QDateTime::currentDateTimeUtc().toString("yyyyMMdd-HHmmss") + "-" + QUuid::createUuid().toString(QUuid::WithoutBraces) + ".json";
    const auto bytes = QJsonDocument(deckSupportReport(hud)).toJson(QJsonDocument::Indented);
    QSaveFile file(QDir(directory).filePath(name)); file.setDirectWriteFallback(false);
    if (!file.open(QIODevice::WriteOnly) || !file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner) ||
        file.write(bytes) != bytes.size() || !file.commit()) return failure("Couldn't save the report. Check local storage and try again.");
    return {{"saved", true}, {"fileName", name}, {"message", "Saved in Documents → Nova reports. You can share the JSON file from Desktop Mode."}};
}
}
