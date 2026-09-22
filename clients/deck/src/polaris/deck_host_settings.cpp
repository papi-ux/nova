#include "polaris/deck_host_settings.h"
#include "polaris/deck_launch_modes.h"
#include "polaris/deck_spaces.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QRegularExpression>
#include <QSet>
#include <cmath>

namespace nova::deck::polaris {
namespace {
bool text(const QJsonValue& value, int limit, bool empty = true) {
    if (!value.isString()) return false;
    const auto s = value.toString();
    if (s.size() > limit || (!empty && s.trimmed().isEmpty())) return false;
    for (const auto c : s) if (c.unicode() < 32 || c.unicode() == 127) return false;
    return true;
}
bool mode(const QJsonValue& value) {
    return text(value, 64, false) && normalizeLaunchMode(value.toString().toStdString()) == value.toString().toStdString();
}
bool profile(const QJsonObject& o, QString& display, int& bitrate) {
    const auto d = o.value("display_mode"), b = o.value("target_bitrate_kbps");
    if (!text(d, 80) || !b.isDouble() || !std::isfinite(b.toDouble()) ||
        std::floor(b.toDouble()) != b.toDouble() || (b.toDouble() != 0 && (b.toDouble() < 1000 || b.toDouble() > 300000))) return false;
    display = d.toString(); bitrate = b.toInt();
    if (display.isEmpty()) return true;
    const auto match = QRegularExpression("^([1-9][0-9]{0,4})x([1-9][0-9]{0,4})x([0-9]+(?:\\.[0-9]+)?)$").match(display);
    return match.hasMatch() && match.captured(1).toInt() <= 16384 && match.captured(2).toInt() <= 16384 &&
        match.captured(3).toDouble() > 0 && match.captured(3).toDouble() <= 1000;
}
QString fallbackLabel(const QString& mode) {
    static const QMap<QString, QString> labels{{"headless_stream", "Private Stream"}, {"host_virtual_display", "Host Virtual Display"},
        {"desktop_display", "Mirror Desktop"}, {"desktop_takeover", "Desktop Takeover"}, {"windowed_stream", "Private Stream (GPU-native)"},
        {"gamescope_stream", "Gamescope Stream"}, {"headless_dongle", "Headless Dongle"}};
    return labels.value(mode, mode);
}
}
bool DeckHostSettings::permits(const QString& mode) const {
    for (const auto& value : modes) if (value.toMap().value("id") == mode) return value.toMap().value("available").toBool();
    return false;
}
bool validHostProfile(const QString& display, int bitrate) {
    QString parsedDisplay; int parsedBitrate = 0;
    return !display.isEmpty() && bitrate > 0 && profile(
        QJsonObject{{"display_mode", display}, {"target_bitrate_kbps", bitrate}}, parsedDisplay, parsedBitrate);
}
QVariantMap DeckHostSettings::publicState() const {
    const auto label = [&](const QString& id) {
        for (const auto& value : modes) if (value.toMap().value("id") == id) return value.toMap().value("label").toString();
        return fallbackLabel(id);
    };
    return {{"desiredMode", desiredMode}, {"effectiveMode", effectiveMode}, {"desiredLabel", label(desiredMode)},
        {"effectiveLabel", label(effectiveMode)}, {"desiredDisplay", desiredDisplay}, {"effectiveDisplay", effectiveDisplay},
        {"desiredBitrate", desiredBitrate}, {"effectiveBitrate", effectiveBitrate}, {"relaunchRequired", relaunchRequired}, {"modes", modes},
        {"displayOverride", displayOverride}, {"bitrateOverride", bitrateOverride},
        {"resumeTimeoutControl", resumeTimeoutControl}, {"desiredResumeTimeout", desiredResumeTimeout}, {"effectiveResumeTimeout", effectiveResumeTimeout}};
}
QVariantMap DeckHostSettings::profileReview() const {
    // Effective bitrate may move on every telemetry sample. Review the saved
    // profile and its capability/revision, not an adaptive encoder observation.
    return {{"revision", revision}, {"display", desiredDisplay}, {"bitrate", desiredBitrate},
        {"displayOverride", displayOverride}, {"bitrateOverride", bitrateOverride}};
}
std::optional<DeckHostSettings> parseHostSettings(std::string_view json) {
    if (!uniqueJsonFields(json, 32)) return {};
    const auto document = QJsonDocument::fromJson(QByteArray(json.data(), json.size()));
    if (!document.isObject()) return {};
    const auto root = document.object();
    if (root.contains("status") && root.value("status") != QJsonValue(true)) return {};
    if (root.contains("client_settings") && !root.value("client_settings").isObject()) return {};
    const auto o = root.contains("client_settings") ? root.value("client_settings").toObject() : root;
    if (o.value("version") != QJsonValue(1) || !o.value("desired").isObject() || !o.value("effective").isObject() ||
        !o.value("relaunch_required").isBool() || !text(o.value("revision"), 128, false) ||
        !QRegularExpression("^[A-Za-z0-9_.:-]{1,128}$").match(o.value("revision").toString()).hasMatch()) return {};
    const auto desired = o.value("desired").toObject(), effective = o.value("effective").toObject();
    if (!mode(desired.value("stream_display_mode")) || !mode(effective.value("stream_display_mode"))) return {};
    DeckHostSettings result;
    result.revision = o.value("revision").toString(); result.relaunchRequired = o.value("relaunch_required").toBool();
    result.desiredMode = desired.value("stream_display_mode").toString(); result.effectiveMode = effective.value("stream_display_mode").toString();
    if (!profile(desired, result.desiredDisplay, result.desiredBitrate) || !profile(effective, result.effectiveDisplay, result.effectiveBitrate)) return {};
    const auto capabilities = o.value("capabilities").toObject();
    for (const auto* key : {"display_mode_override", "target_bitrate_override", "disconnect_resume_timeout_control"})
        if (capabilities.contains(key) && !capabilities.value(key).isBool()) return {};
    result.resumeTimeoutControl = capabilities.value("disconnect_resume_timeout_control").toBool(false);
    const auto readTimeout = [&](const QJsonObject& values, int& target) {
        const auto value = values.value("disconnect_resume_timeout_seconds");
        if (value.isUndefined()) return !result.resumeTimeoutControl;
        if (!value.isDouble() || !std::isfinite(value.toDouble()) || std::floor(value.toDouble()) != value.toDouble()
            || value.toDouble() < 0 || value.toDouble() > 86400) return false;
        target = value.toInt(); return true;
    };
    if (!readTimeout(desired, result.desiredResumeTimeout) || !readTimeout(effective, result.effectiveResumeTimeout)) return {};
    result.displayOverride = capabilities.value("display_mode_override").toBool(false);
    result.bitrateOverride = capabilities.value("target_bitrate_override").toBool(false);
    const auto modes = capabilities.value("modes");
    if (!modes.isArray() || modes.toArray().isEmpty() || modes.toArray().size() > 16) return {};
    QSet<QString> ids;
    for (const auto v : modes.toArray()) {
        if (!v.isObject()) return {};
        const auto m = v.toObject();
        const auto id = m.value("value").toString();
        if (!mode(m.value("value")) || ids.contains(id) || !m.value("available").isBool()) return {};
        for (const auto* key : {"label", "reason", "unavailable_reason"}) if (m.contains(key) && !text(m.value(key), 400)) return {};
        for (const auto* key : {"restart_required", "session_overridable"}) if (m.contains(key) && !m.value(key).isBool()) return {};
        ids.insert(id);
        auto label = m.value("label").toString(); if (label.trimmed().isEmpty()) label = fallbackLabel(id);
        auto reason = m.value("unavailable_reason").toString(); if (reason.isEmpty()) reason = m.value("reason").toString();
        result.modes.append(QVariantMap{{"id", id}, {"label", label}, {"available", m.value("available").toBool()}, {"reason", reason}});
    }
    if (!ids.contains(result.desiredMode) || !ids.contains(result.effectiveMode)) return {};
    return result;
}
std::optional<bool> parseHostSettingsIdle(std::string_view json) {
    if (!uniqueJsonFields(json, 32)) return {};
    const auto d = QJsonDocument::fromJson(QByteArray(json.data(), json.size()));
    if (!d.isObject()) return {};
    const auto o = d.object();
    if ((o.contains("status") && o.value("status") != QJsonValue(true)) || !o.value("streaming_active").isBool() ||
        !text(o.value("state"), 64, false) || !text(o.value("game_uuid"), 512)) return {};
    return o.value("state") == QJsonValue("idle") && !o.value("streaming_active").toBool() && o.value("game_uuid").toString().isEmpty();
}
}
