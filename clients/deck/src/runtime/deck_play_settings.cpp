#include "runtime/deck_play_settings.h"
#include "runtime/deck_frame_pacing.h"
#include "stream/deck_video_scale.h"
#include "stream/deck_controller_input.h"
#include "polaris/deck_launch_modes.h"
#include "polaris/deck_game_tools.h"
#include "polaris/deck_stream_capabilities.h"

#include <Limelight.h>
#include <QCryptographicHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QSettings>
#include <cmath>
#include <algorithm>
#include <memory>

namespace nova::deck::runtime {
namespace {
QString keyFor(const QString& host, const QString& game) {
    if (host.isEmpty() || game.isEmpty() || host.size() > 512 || game.size() > 512 || game == "game-empty-state") return {};
    // Structured pairs avoid slash/concatenation collisions between PCs/games.
    const auto pair = QJsonDocument(QJsonArray{host, game}).toJson(QJsonDocument::Compact);
    return "PlaySetup/v1/" + QCryptographicHash::hash(pair, QCryptographicHash::Sha256).toHex();
}
QString syncKey(const QString& host) {
    if (host.trimmed().isEmpty() || host.size() > 512) return {};
    return "PolarisSync/v1/" + QCryptographicHash::hash(host.toUtf8(), QCryptographicHash::Sha256).toHex();
}
std::unique_ptr<QSettings> store(const QString& fileName) {
    auto settings = fileName.isEmpty() ? std::make_unique<QSettings>()
        : std::make_unique<QSettings>(fileName, QSettings::IniFormat);
    settings->setFallbacksEnabled(false);
    return settings;
}
bool writeValue(QSettings& settings, const QString& key, const QVariant& value) {
    const auto previous = settings.value(key);
    const bool existed = settings.contains(key);
    if (value.isValid()) settings.setValue(key, value);
    else settings.remove(key);
    settings.sync();
    if (settings.status() == QSettings::NoError) return true;
    // QSettings shares its cache across instances. A failed save must not look
    // successful on the next in-process load, even when the file is unwritable.
    if (existed) settings.setValue(key, previous);
    else settings.remove(key);
    settings.sync();
    return false;
}
std::optional<QVariantMap> logoValues(const QVariantMap& values) {
    if (values.size() != 3) return {};
    QVariantMap result;
    for (const auto* field : {"scale", "x", "y"}) {
        const auto value = values.value(field);
        switch (value.metaType().id()) {
        case QMetaType::Int: case QMetaType::UInt: case QMetaType::LongLong:
        case QMetaType::ULongLong: case QMetaType::Double: case QMetaType::Float: break;
        default: return {};
        }
        const double n = value.toDouble();
        const bool scale = QString::fromLatin1(field) == "scale";
        if (!std::isfinite(n) || n < (scale ? 0.25 : 0.0) || n > (scale ? 4.0 : 1.0)) return {};
        result[field] = n;
    }
    return result;
}
QString logoKey(const QString& host, const QString& game) {
    return keyFor(host, game).replace("PlaySetup/v1/", "Artwork/v1/logo/");
}
std::optional<int> integer(const QVariant& value) {
    switch (value.metaType().id()) {
    case QMetaType::Int: case QMetaType::UInt: case QMetaType::LongLong:
    case QMetaType::ULongLong: case QMetaType::Double: case QMetaType::Float: break;
    default: return std::nullopt;
    }
    const auto number = value.toDouble();
    if (!std::isfinite(number) || number < 0 || number > 100000 || std::floor(number) != number) return std::nullopt;
    return static_cast<int>(number);
}
std::optional<int> stickDeadzoneValue(const QVariant& value) {
    switch (value.metaType().id()) {
    case QMetaType::Int: case QMetaType::UInt: case QMetaType::LongLong:
    case QMetaType::ULongLong: case QMetaType::Double: case QMetaType::Float: break;
    default: return {};
    }
    const auto n = value.toDouble();
    if (!std::isfinite(n) || n < -20 || n > 20 || std::floor(n) != n) return {};
    return static_cast<int>(n);
}
QStringList choiceKeys(const QString& field) {
    if (field == "resolution") return {"width", "height"};
    if (field == "fps" || field == "bitrateKbps" || field == "faceButtonLayout" || field == "launchMode" || field == "videoCodec" || field == "profilePreference" || field == "encoderBackend") return {field};
    return {};
}
QVariantMap mergedConfiguration(const QVariantMap& choices) {
    auto values = DeckPlayConfiguration{}.toMap();
    for (auto it = choices.cbegin(); it != choices.cend(); ++it) values[it.key()] = it.value();
    return values;
}
bool validChoices(const QVariantMap& choices) {
    if (choices.contains("width") != choices.contains("height")) return false;
    return DeckPlayConfiguration::fromMap(mergedConfiguration(choices)).has_value();
}
QString choiceKey(QString legacyKey) { return legacyKey.replace("PlaySetup/v1/", "PlaySetup/v2/"); }
QVariantMap readChoices(QSettings& settings, const QString& legacyKey) {
    if (legacyKey.isEmpty()) return {};
    const auto key = choiceKey(legacyKey);
    if (settings.contains(key)) {
        const auto value = settings.value(key);
        const auto record = value.toMap();
        const auto choices = record.value("choices");
        // An invalid v2 record must never resurrect an older v1 override.
        if (value.metaType().id() != QMetaType::QVariantMap || record.size() != 2 ||
            integer(record.value("version")) != std::optional<int>{2} || choices.metaType().id() != QMetaType::QVariantMap ||
            !validChoices(choices.toMap())) return {};
        return choices.toMap();
    }
    const auto old = settings.value(legacyKey).toMap();
    if (!DeckPlayConfiguration::fromMap(old)) return {};
    auto choices = old;
    for (const auto* inherited : {"faceButtonLayout", "launchMode"})
        if (choices.value(inherited) == "default") choices.remove(inherited);
    return choices; // Read-only migration retains each old explicit field.
}
bool writeChoices(QSettings& settings, const QString& legacyKey, QVariantMap choices) {
    for (const auto* inherited : {"faceButtonLayout", "launchMode"})
        if (choices.value(inherited) == "default") choices.remove(inherited);
    if (choices.value("profilePreference") == "auto") choices.remove("profilePreference");
    if (choices.value("encoderBackend").toString().isEmpty()) choices.remove("encoderBackend");
    // Keep an empty v2 record on reset as a tombstone over any legacy values.
    // One atomic QSettings value also gives write failure a single rollback.
    return writeValue(settings, choiceKey(legacyKey), QVariantMap{{"version", 2}, {"choices", choices}});
}
}

QVariantMap DeckPlayConfiguration::toMap() const {
    return {{"width", width}, {"height", height}, {"fps", fps}, {"bitrateKbps", bitrateKbps},
        {"faceButtonLayout", faceButtonLayout}, {"launchMode", launchMode}, {"videoCodec", videoCodec}, {"profilePreference", profilePreference}, {"encoderBackend", encoderBackend}};
}

QVariantMap DeckAudioConfiguration::toMap() const {
    return {{"channels", channels}, {"playHostAudio", playHostAudio}};
}

std::optional<DeckAudioConfiguration> DeckAudioConfiguration::fromMap(const QVariantMap& values) {
    if (values.size() != 2 || !values.contains("channels") || !values.contains("playHostAudio")) return {};
    const auto channels = integer(values.value("channels"));
    const auto host = values.value("playHostAudio");
    if (!channels || (*channels != 2 && *channels != 6 && *channels != 8) || host.metaType().id() != QMetaType::Bool) return {};
    return DeckAudioConfiguration{*channels, host.toBool()};
}

QVariantMap DeckPlaySettings::logoPlacement(const QString& host, const QString& game, const QVariantMap& fallback) const {
    const auto defaults = logoValues(fallback).value_or(QVariantMap{{"scale", 1.0}, {"x", 0.5}, {"y", 0.5}});
    const auto key = logoKey(host, game);
    return key.isEmpty() ? defaults : logoValues(store(fileName_)->value(key).toMap()).value_or(defaults);
}

bool DeckPlaySettings::saveLogoPlacement(const QString& host, const QString& game, const QVariantMap& values,
    const QVariantMap& expected, const QVariantMap& fallback) {
    const auto key = logoKey(host, game);
    const auto parsed = logoValues(values), previous = logoValues(expected);
    if (key.isEmpty() || !parsed || !previous || logoPlacement(host, game, fallback) != *previous) return false;
    auto settings = store(fileName_);
    if (!writeValue(*settings, key, *parsed)) return false;
    emit logoPlacementChanged();
    return true;
}

DeckAudioConfiguration DeckPlaySettings::audioConfiguration() const {
    return DeckAudioConfiguration::fromMap(store(fileName_)->value("Audio/v1/configuration").toMap())
        .value_or(DeckAudioConfiguration{});
}

bool DeckPlaySettings::saveAudioSettings(const QVariantMap& values) {
    const auto configuration = DeckAudioConfiguration::fromMap(values);
    if (!configuration) return false;
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Audio/v1/configuration", configuration->toMap())) return false;
    emit audioSettingsChanged();
    return true;
}

bool DeckPlaySettings::resetAudioSettings() {
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Audio/v1/configuration", {})) return false;
    emit audioSettingsChanged();
    return true;
}

bool DeckPlaySettings::rumbleEnabled() const {
    const auto value = store(fileName_)->value("Input/v1/rumble").toMap();
    const auto enabled = value.value("enabled");
    return value.size() == 1 && enabled.metaType().id() == QMetaType::Bool ? enabled.toBool() : true;
}

bool DeckPlaySettings::setRumbleEnabled(const QVariant& value) {
    if (value.metaType().id() != QMetaType::Bool) return false;
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Input/v1/rumble", QVariantMap{{"enabled", value}})) return false;
    emit rumbleEnabledChanged();
    return true;
}

bool DeckPlaySettings::resetRumble() {
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Input/v1/rumble", {})) return false;
    emit rumbleEnabledChanged();
    return true;
}

int DeckPlaySettings::stickDeadzonePercent() const {
    // A typed map preserves numeric types across an actual INI reload.
    const auto value = store(fileName_)->value("Input/v1/stickDeadzone");
    if (value.metaType().id() != QMetaType::QVariantMap) return stream::kDeckDefaultStickDeadzone;
    const auto record = value.toMap();
    return record.size() == 1 ? stickDeadzoneValue(record.value("percent")).value_or(stream::kDeckDefaultStickDeadzone)
        : stream::kDeckDefaultStickDeadzone;
}
bool DeckPlaySettings::setStickDeadzonePercent(const QVariant& percent) {
    const auto value = stickDeadzoneValue(percent);
    if (!value) return false;
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Input/v1/stickDeadzone", QVariantMap{{"percent", *value}})) return false;
    emit stickDeadzonePercentChanged();
    return true;
}
bool DeckPlaySettings::resetStickDeadzonePercent() {
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Input/v1/stickDeadzone", {})) return false;
    emit stickDeadzonePercentChanged();
    return true;
}

QString DeckPlaySettings::framePacingMode() const {
    const auto value = store(fileName_)->value("Video/v1/framePacing");
    return value.metaType().id() == QMetaType::QString && deckFramePacing(value.toString())
        ? value.toString() : QStringLiteral("latency");
}
bool DeckPlaySettings::setFramePacingMode(const QString& mode) {
    if (!deckFramePacing(mode)) return false;
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Video/v1/framePacing", mode)) return false;
    emit framePacingModeChanged();
    return true;
}
bool DeckPlaySettings::resetFramePacingMode() {
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Video/v1/framePacing", {})) return false;
    emit framePacingModeChanged();
    return true;
}

QString DeckPlaySettings::videoScaleMode() const {
    const auto value = store(fileName_)->value("Video/v1/scaleMode");
    return value.metaType().id() == QMetaType::QString && stream::deckVideoScaleMode(value.toString())
        ? value.toString() : QStringLiteral("fit");
}
bool DeckPlaySettings::setVideoScaleMode(const QString& mode) {
    if (!stream::deckVideoScaleMode(mode)) return false;
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Video/v1/scaleMode", mode)) return false;
    emit videoScaleModeChanged();
    return true;
}
bool DeckPlaySettings::resetVideoScaleMode() {
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Video/v1/scaleMode", {})) return false;
    emit videoScaleModeChanged();
    return true;
}

std::optional<DeckPlayConfiguration> DeckPlayConfiguration::fromMap(const QVariantMap& values) {
    // Older four/five-field records retain their choices and use host default.
    if (values.size() < 4 || values.size() > 9) return std::nullopt;
    for (auto it = values.cbegin(); it != values.cend(); ++it)
        if (it.key() != "width" && it.key() != "height" && it.key() != "fps" && it.key() != "bitrateKbps" &&
            it.key() != "faceButtonLayout" && it.key() != "launchMode" && it.key() != "videoCodec" && it.key() != "profilePreference" && it.key() != "encoderBackend") return std::nullopt;
    const auto preset = values.value("profilePreference", QStringLiteral("auto"));
    const auto encoder = values.value("encoderBackend", QString{});
    if (preset.metaType().id() != QMetaType::QString || !QStringList{"auto", "quality", "high_fps", "stability"}.contains(preset.toString()) ||
        encoder.metaType().id() != QMetaType::QString || !polaris::validEncoderChoice(encoder.toString())) return {};
    const auto codec = values.value("videoCodec", QStringLiteral("h264"));
    if (codec.metaType().id() != QMetaType::QString || (codec != "h264" && codec != "hevc" && codec != "auto")) return {};
    const auto mode = values.value("launchMode", QStringLiteral("default"));
    if (mode.metaType().id() != QMetaType::QString ||
        (mode != "default" && !polaris::isSessionLaunchMode(mode.toString().toStdString()))) return std::nullopt;
    const auto face = values.value("faceButtonLayout", QStringLiteral("default"));
    if (face.metaType().id() != QMetaType::QString ||
        (face != "default" && face != "labels" && face != "positions")) return std::nullopt;
    const auto width = integer(values.value("width")), height = integer(values.value("height"));
    const auto fps = integer(values.value("fps")), bitrate = integer(values.value("bitrateKbps"));
    if (!width || !height || !fps || !bitrate) return std::nullopt;
    if (!supportedDeckResolution(*width, *height)) return std::nullopt;
    // Saved preferences may outlive the display they were selected on. Review
    // resolves them against the current display; the worker rechecks before launch.
    if (*fps < 30 || *fps > 90) return std::nullopt;
    if (*bitrate < 1000 || *bitrate > 300000) return std::nullopt;
    return DeckPlayConfiguration{*width, *height, *fps, *bitrate, face.toString(), mode.toString(), codec.toString(), preset.toString(), encoder.toString()};
}

DeckPlaySettings::DeckPlaySettings(QString fileName, QObject* parent)
    : QObject(parent), fileName_(std::move(fileName)) {}

QString DeckPlaySettings::keepInStep(const QString& hostId) const {
    const auto key = syncKey(hostId);
    if (key.isEmpty()) return "off";
    const auto value = store(fileName_)->value(key);
    if (value.metaType().id() != QMetaType::QString) return "off";
    const auto state = value.toString();
    return state == "on" || state == "paused" ? state : "off";
}

bool DeckPlaySettings::saveKeepInStep(const QString& hostId, const QString& state) {
    const auto key = syncKey(hostId);
    if (key.isEmpty() || (state != "off" && state != "on" && state != "paused")) return false;
    auto settings = store(fileName_);
    return writeValue(*settings, key, state);
}

QVariantMap DeckPlaySettings::streamDefaults() const {
    const auto values = store(fileName_)->value("Stream/v1/defaults").toMap();
    if (values.size() == 4 && DeckPlayConfiguration::fromMap(values)) return values;
    auto defaults = DeckPlayConfiguration{}.toMap();
    defaults.remove("faceButtonLayout"); defaults.remove("launchMode"); defaults.remove("videoCodec"); defaults.remove("profilePreference"); defaults.remove("encoderBackend");
    return defaults;
}
bool DeckPlaySettings::saveStreamDefaults(const QVariantMap& values) {
    if (values.size() != 4 || !DeckPlayConfiguration::fromMap(values)) return false;
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Stream/v1/defaults", values)) return false;
    emit streamDefaultsChanged();
    return true;
}
bool DeckPlaySettings::resetStreamDefaults() {
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Stream/v1/defaults", {})) return false;
    emit streamDefaultsChanged();
    return true;
}
std::optional<QVariantMap> DeckPlaySettings::defaultsFromHost(const QString& display, int bitrate) const {
    if (display.isEmpty() && bitrate <= 0) return {};
    auto values = streamDefaults();
    if (!display.isEmpty()) {
        const auto parts = display.split('x');
        if (parts.size() != 3) return {};
        bool widthOk, heightOk, fpsOk;
        const auto width = parts[0].toDouble(&widthOk), height = parts[1].toDouble(&heightOk), fps = parts[2].toDouble(&fpsOk);
        if (!widthOk || !heightOk || !fpsOk) return {};
        values["width"] = width; values["height"] = height; values["fps"] = fps;
    }
    if (bitrate > 0) values["bitrateKbps"] = bitrate;
    const auto checked = DeckPlayConfiguration::fromMap(values);
    if (!checked) return {}; // Never round or partly import an unsupported host profile.
    auto normalized = checked->toMap(); normalized.remove("faceButtonLayout"); normalized.remove("launchMode"); normalized.remove("videoCodec"); normalized.remove("profilePreference"); normalized.remove("encoderBackend");
    return normalized;
}

int DeckPlaySettings::displayRateLimit(double refreshHz) const { return deckDisplayRateLimit(refreshHz); }

QVariantMap DeckPlaySettings::streamPlan(const QVariantMap& values, const QVariantMap& capabilities,
    const QVariantMap& planner, const QVariantMap& display, bool spaceSession) const {
    const auto requested = DeckPlayConfiguration::fromMap(values);
    auto effective = requested.value_or(DeckPlayConfiguration{});
    DeckStreamCapabilities limits;
    limits.valid = capabilities.value("valid", true).toBool();
    limits.h264 = capabilities.value("h264", true).toBool();
    limits.hevc = !spaceSession && capabilities.value("hevc", false).toBool();
    const auto selectedFormat = stream::selectSdrVideoFormat(effective.videoCodec.toStdString(),
        limits.h264, limits.hevc, videoSupport_, effective.width, effective.height);
    if (selectedFormat) effective.videoCodec = selectedFormat == VIDEO_FORMAT_H265 ? "hevc" : "h264";
    limits.maxFps = capabilities.value("maxFps", 0).toDouble();
    if (!std::isfinite(limits.maxFps) || limits.maxFps < 0 || limits.maxFps > 1000) limits.valid = false;
    const auto hz = display.value("refreshHz").toDouble();
    const bool displayKnown = display.value("known").toBool() && std::isfinite(hz) && hz > 0 && hz <= 1000;
    const int displayMaxFps = displayKnown ? deckDisplayRateLimit(hz) : 60;
    QVariantList resolutions;
    const auto appendResolution = [&](int width, int height, const QVariantMap& hint = {}) {
        if (!supportedDeckResolution(width, height)) return;
        for (const auto& row : resolutions) if (row.toMap().value("width") == width && row.toMap().value("height") == height) return;
        const bool recommended = hint.value("recommended").toBool();
        const bool available = stream::selectSdrVideoFormat(effective.videoCodec.toStdString(), limits.h264,
            limits.hevc, videoSupport_, width, height) != 0;
        QString label = QString("%1 × %2").arg(width).arg(height);
        if (recommended) label += " · Best for this device";
        else if (hint.value("advanced").toBool() || hint.value("custom").toBool()) label += " · Advanced";
        QString detail = hint.value("detail").toString().left(240);
        if (detail.isEmpty()) detail = "Change the stream size. Your frame-rate choice stays separate.";
        if (!available) detail = "Unavailable with the current PC, codec and decoder. Choose another codec or size.";
        resolutions.append(QVariantMap{{"width", width}, {"height", height}, {"label", label},
            {"detail", detail}, {"recommended", recommended}, {"available", available}});
    };
    const auto hints = planner.value("available").toBool() ? planner.value("choices").toList() : QVariantList{};
    for (const auto& size : {QPair{1280, 800}, QPair{1280, 720}, QPair{1920, 1080}, QPair{1920, 1200}}) {
        QVariantMap hint;
        for (const auto& entry : hints) if (entry.toMap().value("width") == size.first && entry.toMap().value("height") == size.second) {
            hint = entry.toMap(); break;
        }
        appendResolution(size.first, size.second, hint);
    }
    for (const auto& entry : hints) {
        const auto hint = entry.toMap();
        appendResolution(hint.value("width").toInt(), hint.value("height").toInt(), hint);
    }
    appendResolution(effective.width, effective.height, {{"custom", true}});
    const int hostMaxFps = std::isfinite(limits.maxFps) && limits.maxFps > 0 && limits.maxFps <= 1000 ? static_cast<int>(limits.maxFps) : 60;
    const int allowedFps = std::min(displayMaxFps, hostMaxFps);
    QList<int> rateChoices{30, 60, 90};
    if (effective.fps >= 30 && effective.fps <= allowedFps && !rateChoices.contains(effective.fps)) rateChoices.append(effective.fps);
    if (allowedFps >= 30 && allowedFps <= 90 && !rateChoices.contains(allowedFps)) rateChoices.append(allowedFps);
    std::sort(rateChoices.begin(), rateChoices.end());
    QVariantList rates;
    for (const int fps : rateChoices) if (fps <= allowedFps) {
        rates.append(QVariantMap{{"fps", fps}, {"label", QString("%1 fps").arg(fps)},
            {"detail", fps == 30 ? "Reduce streaming work with fewer frames each second." : "Smoother motion, with more frames each second."}});
    }
    if (!rates.empty() && effective.fps > rates.back().toMap().value("fps").toInt())
        effective.fps = rates.back().toMap().value("fps").toInt();
    QString reason;
    if (!requested || !limits.valid) reason = "Stream capabilities could not be verified. Refresh this PC and try again.";
    else if (!selectedFormat) reason = spaceSession && effective.videoCodec == "hevc"
        ? "Spaces currently use H.264. Choose Auto or H.264 to play here."
        : "The selected codec is unavailable for this PC and stream size. Choose Auto or another codec, or refresh this PC.";
    else if (rates.empty()) reason = "This PC cannot provide a supported frame rate. Check its streaming settings.";
    QString adjustment;
    if (requested && effective.fps != requested->fps) {
        const auto cause = limits.maxFps > 0 && limits.maxFps < requested->fps && limits.maxFps <= displayMaxFps
            ? QString("Your PC supports up to %1 fps.").arg(limits.maxFps)
            : displayMaxFps < requested->fps
                ? (displayKnown ? QString("Your display is running at %1 Hz.").arg(hz, 0, 'f', 1)
                                : QString("The current display rate is unavailable."))
                : QString("Your PC has not reported support for %1 fps.").arg(requested->fps);
        adjustment = cause + QString(" This stream will use %1 fps; your saved %2 fps preference is unchanged.")
            .arg(effective.fps).arg(requested->fps);
    }
    QVariantList codecs;
    for (const auto* codec : {"auto", "h264", "hevc"}) {
        const bool available = stream::selectSdrVideoFormat(codec, limits.h264, limits.hevc, videoSupport_, effective.width, effective.height) != 0;
        const QString label = QString(codec) == "auto" ? "Auto" : QString(codec) == "hevc" ? "HEVC" : "H.264";
        const QString detail = !available ? (spaceSession && QString(codec) == "hevc" ? "Spaces currently use H.264." : "Unavailable for this PC and stream size.")
            : QString(codec) == "auto" ? "Prefer HEVC when both devices support it; otherwise use H.264."
            : QString(codec) == "hevc" ? "Use HEVC for more efficient video compression. HDR is not available yet."
            : "Use H.264 for broad compatibility. HDR is not available yet.";
        codecs.append(QVariantMap{{"videoCodec", codec}, {"label", label + (available ? "" : " · Unavailable")}, {"detail", detail}});
    }
    const QString codecDetail = requested && requested->videoCodec == "auto" && selectedFormat
        ? (selectedFormat == VIDEO_FORMAT_H265 ? "Auto selected HEVC. HDR is not available yet." : "Auto selected H.264; HEVC is unavailable for this stream.")
        : "HDR is not available yet.";
    return {{"configuration", effective.toMap()}, {"playable", reason.isEmpty()}, {"reason", reason},
        {"adjustment", reason.isEmpty() ? adjustment : QString{}}, {"resolutions", resolutions}, {"rates", rates},
        {"codecs", codecs}, {"codecDetail", codecDetail},
        {"videoLabel", selectedFormat == VIDEO_FORMAT_H265 ? "HEVC · SDR" : selectedFormat == VIDEO_FORMAT_H264 ? "H.264 · SDR" : "Codec unavailable"}, {"maxClientFps", 90}, {"displayMaxFps", displayMaxFps},
        {"displayLabel", displayKnown ? QString("%1 Hz display").arg(hz, 0, 'f', hz == std::floor(hz) ? 0 : 1)
                                      : QString("Display rate unknown")}};
}

QString DeckPlaySettings::defaultFaceButtonLayout() const {
    const auto value = store(fileName_)->value("Input/v1/faceButtonLayout");
    return value.metaType().id() == QMetaType::QString && value == "positions" ? "positions" : "labels";
}

bool DeckPlaySettings::setDefaultFaceButtonLayout(const QString& layout) {
    if (layout != "labels" && layout != "positions") return false;
    auto settings = store(fileName_);
    if (!writeValue(*settings, "Input/v1/faceButtonLayout", layout)) return false;
    emit defaultFaceButtonLayoutChanged();
    return true;
}

QVariantMap DeckPlaySettings::load(const QString& hostId, const QString& gameId) const {
    const auto key = keyFor(hostId, gameId);
    auto settings = store(fileName_);
    const auto choices = readChoices(*settings, key);
    QVariantMap overrides;
    for (const auto* field : {"resolution", "fps", "bitrateKbps", "faceButtonLayout", "launchMode", "videoCodec", "profilePreference", "encoderBackend"})
        overrides[field] = choices.contains(choiceKeys(field).front());
    auto merged = mergedConfiguration(streamDefaults());
    for (auto it = choices.cbegin(); it != choices.cend(); ++it) merged[it.key()] = it.value();
    return {{"configuration", merged}, {"custom", !choices.isEmpty()}, {"overrides", overrides}};
}

bool DeckPlaySettings::save(const QString& hostId, const QString& gameId, const QVariantMap& values) {
    const auto key = keyFor(hostId, gameId);
    const auto configuration = DeckPlayConfiguration::fromMap(values);
    if (key.isEmpty() || !configuration) return false;
    auto settings = store(fileName_);
    return writeChoices(*settings, key, configuration->toMap());
}

bool DeckPlaySettings::saveChoice(const QString& hostId, const QString& gameId, const QVariantMap& choice) {
    const auto key = keyFor(hostId, gameId);
    if (key.isEmpty() || choice.isEmpty()) return false;
    const auto keys = choiceKeys(choice.contains("width") || choice.contains("height") ? QString("resolution") : choice.firstKey());
    if (keys.isEmpty() || keys.size() != choice.size()) return false;
    for (const auto& field : keys) if (!choice.contains(field)) return false;
    if (!validChoices(choice)) return false;
    auto settings = store(fileName_);
    auto choices = readChoices(*settings, key);
    for (auto it = choice.cbegin(); it != choice.cend(); ++it) choices[it.key()] = it.value();
    return writeChoices(*settings, key, choices);
}

bool DeckPlaySettings::resetChoice(const QString& hostId, const QString& gameId, const QString& field) {
    const auto key = keyFor(hostId, gameId);
    const auto keys = choiceKeys(field);
    if (key.isEmpty() || keys.isEmpty()) return false;
    auto settings = store(fileName_);
    auto choices = readChoices(*settings, key);
    for (const auto& fieldKey : keys) choices.remove(fieldKey);
    return writeChoices(*settings, key, choices);
}

bool DeckPlaySettings::reset(const QString& hostId, const QString& gameId) {
    const auto key = keyFor(hostId, gameId);
    if (key.isEmpty()) return false;
    auto settings = store(fileName_);
    return writeChoices(*settings, key, {});
}

} // namespace nova::deck::runtime
