#include "polaris/deck_game_tools.h"
#include "polaris/deck_spaces.h"
#include "polaris/deck_launch_modes.h"
#include "polaris/deck_host_settings.h"
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QUrl>
#include <QSet>
#include <cmath>

namespace nova::deck::polaris {
namespace {
const QStringList kinds{"poster", "hero", "logo", "icon"};
bool match(const QString& pattern, const QString& value) { return QRegularExpression(pattern).match(value).hasMatch(); }
bool clean(const QString& s, int max) { return !s.trimmed().isEmpty() && s.toUtf8().size() <= max && !match("[\\x00-\\x1f\\x7f-\\x9f]", s); }
QString bounded(const QJsonValue& v, int max = 400) { return v.isString() && clean(v.toString(), max) ? v.toString() : QString{}; }
QJsonObject candidate(const QVariantMap& v) {
    if (v.value("provider") != "steamgriddb" || !match("^[0-9]{1,20}$", v.value("provider_game_id").toString()) ||
        !clean(v.value("title").toString(), 160)) return {};
    QJsonObject o{{"provider", "steamgriddb"}, {"provider_game_id", v.value("provider_game_id").toString()}, {"title", v.value("title").toString()}};
    if (v.contains("steam_appid")) {
        if (!match("^[0-9]{1,10}$", v.value("steam_appid").toString())) return {};
        o.insert("steam_appid", v.value("steam_appid").toString());
    }
    return o;
}
QString preview(const QString& game, const QString& kind, const QJsonValue& path) {
    const auto prefix = "/polaris/v1/games/" + game + "/artwork/candidate/";
    const auto s = path.toString();
    return match("^" + QRegularExpression::escape(prefix) + "[0-9a-f]{32}/" + kind + "$", s) ? s : QString{};
}
}
bool validGameToolId(const QString& id) { return match("^[A-Za-z0-9][A-Za-z0-9._-]{0,255}$", id) && !id.startsWith("space.") && !isSpaceGame(id.toStdString()); }
bool validSpaceArtworkId(const QString& id) {
    const auto space = spaceGameIdentity(id.toStdString());
    return space && space->target != "big-picture-v1";
}
bool validEncoderChoice(const QString& id) { return id.isEmpty() || match("^[a-z0-9][a-z0-9_-]{0,63}$", id); }
std::optional<DeckGameToolRequest> gameToolRequest(const QString& game, const QString& action, const QVariantMap& v) {
    if (action == "spaceRefresh") {
        if (!validSpaceArtworkId(game) || !v.isEmpty()) return {};
        return DeckGameToolRequest{("/polaris/v1/games/" + game + "/space-artwork/resolve").toStdString(), "POST", "{\"policy\":\"missing_or_stale\"}"};
    }
    if (!validGameToolId(game)) return {};
    const QString base = "/polaris/v1/games/" + game;
    DeckGameToolRequest r;
    QJsonObject body;
    if (action == "settings") r.path = "/polaris/v1/client-settings";
    else if (action == "search") {
        const auto query = v.value("query").toString().trimmed();
        if (!clean(query, 160)) return {};
        r.path = (base + "/artwork/candidates?query=" + QString::fromLatin1(QUrl::toPercentEncoding(query))).toStdString();
    } else if (action == "choices" || action == "apply") {
        body = candidate(v.value("candidate").toMap());
        if (body.isEmpty()) return {};
        if (action == "choices") {
            const auto kind = v.value("kind").toString(); if (!kinds.contains(kind)) return {};
            r.path = (base + "/artwork/choices/" + kind).toStdString();
        } else {
            QJsonObject selections; QSet<QString> tokens;
            const auto values = v.value("selections").toMap();
            if (values.isEmpty() || values.size() > 4) return {};
            for (auto it = values.cbegin(); it != values.cend(); ++it) {
                const auto token = it.value().toString();
                if (!kinds.contains(it.key()) || !match("^[0-9a-f]{32}$", token) || tokens.contains(token)) return {};
                selections.insert(it.key(), token); tokens.insert(token);
            }
            body.insert("selections", selections); r.path = (base + "/artwork/match").toStdString();
        }
        r.method = "POST";
    } else if (action == "reset") { r.path = (base + "/artwork/override").toStdString(); r.method = "DELETE"; }
    else if (action == "refreshArt") { r.path = (base + "/artwork/resolve").toStdString(); r.method = "POST"; body.insert("policy", "missing_or_stale"); }
    else if (action == "steam") {
        const auto mode = v.value("mode").toString(); if (mode != "direct" && mode != "big-picture") return {};
        r.path = (base + "/steam-launch-mode").toStdString(); r.method = "POST";
        body = {{"game_id", game}, {"mode", mode}};
    } else if (action == "plan") {
        const auto preset = v.value("profilePreference", "auto").toString(), encoder = v.value("encoderBackend").toString();
        if (!QStringList{"auto", "quality", "high_fps", "stability"}.contains(preset) || !validEncoderChoice(encoder)) return {};
        const auto w = v.value("width").toInt(), h = v.value("height").toInt(), fps = v.value("fps").toInt(), rate = v.value("bitrateKbps").toInt();
        if (w < 640 || w > 3840 || h < 480 || h > 2160 || fps < 30 || fps > 90 || rate < 1000 || rate > 150000) return {};
        QString path = "/polaris/v1/optimize?device=steam_deck&game=" + game + "&preference=" + preset +
            QString("&width=%1&height=%2&fps=%3&display_locked=1&bitrate_kbps=%4&bitrate_locked=1&hdr=0&client_max_fps=%3").arg(w).arg(h).arg(fps).arg(rate);
        if (!encoder.isEmpty()) path += "&encoder=" + encoder;
        const auto mode = v.value("launchMode", "default").toString();
        if (mode != "default") { if (!isSessionLaunchMode(mode.toStdString())) return {}; path += "&mode=" + mode + "&topology_locked=1"; }
        r.path = path.toStdString();
    } else return {};
    r.body = QJsonDocument(body).toJson(QJsonDocument::Compact).toStdString();
    return r;
}
std::optional<QVariantMap> gameToolReply(const QString& game, const QString& action, const QVariantMap& v, std::string_view json) {
    if (!(action == "spaceRefresh" ? validSpaceArtworkId(game) : validGameToolId(game)) || json.size() > 2 * 1024 * 1024 || !uniqueJsonFields(json, 32)) return {};
    const auto d = QJsonDocument::fromJson(QByteArray(json.data(), json.size())); if (!d.isObject()) return {};
    const auto o = d.object(); if (o.contains("status") && o.value("status") != QJsonValue(true)) return {};
    QVariantMap result;
    if (action == "settings") {
        const auto parsed = parseHostSettings(json); if (!parsed) return {};
        result = parsed->publicState(); result["revision"] = parsed->revision;
        const auto settings = o.contains("client_settings") ? o.value("client_settings").toObject() : o;
        const auto caps = settings.value("capabilities").toObject(); QVariantList encoders;
        if (caps.value("session_encoder_override") == QJsonValue(true)) {
            const auto all = caps.value("encoders").toArray(); if (all.size() > 32) return {};
            QSet<QString> seen;
            for (const auto entry : all) {
                const auto e = entry.toObject(); const auto id = e.value("value");
                if (!id.isString() || id.toString().isEmpty() || !validEncoderChoice(id.toString()) || seen.contains(id.toString())) return {};
                seen.insert(id.toString());
                if (e.value("available") != QJsonValue(true)) continue;
                const auto label = bounded(e.value("label"), 80);
                encoders.append(QVariantMap{{"encoderBackend", id.toString()}, {"label", label.isEmpty() ? id.toString() : label},
                    {"detail", bounded(e.value("reason"))}, {"fallbackAllowed", e.value("fallback_allowed") == QJsonValue(true)}});
            }
        }
        result["encoders"] = encoders;
    } else if (action == "search") {
        if (o.value("status") != QJsonValue(true) || !o.value("candidates").isArray()) return {};
        QVariantList entries; QSet<QString> seen;
        for (const auto raw : o.value("candidates").toArray()) {
            auto c = candidate(raw.toObject().toVariantMap()); if (c.isEmpty() || seen.contains(c.value("provider_game_id").toString())) continue;
            seen.insert(c.value("provider_game_id").toString()); auto e = c.toVariantMap();
            e["previewPath"] = preview(game, "poster", raw.toObject().value("preview").toObject().value("poster")); entries.append(e);
            if (entries.size() == 5) break;
        }
        result["candidates"] = entries;
    } else if (action == "choices") {
        const auto kind = v.value("kind").toString();
        if (!kinds.contains(kind) || o.value("status") != QJsonValue(true) || o.value("kind") != kind || !o.value("choices").isArray()) return {};
        QVariantList entries; QSet<QString> seen;
        for (const auto raw : o.value("choices").toArray()) {
            const auto c = raw.toObject(); const auto token = c.value("selection_token").toString();
            const auto path = preview(game, kind, c.value("preview"));
            if (!match("^[0-9a-f]{32}$", token) || path != "/polaris/v1/games/" + game + "/artwork/candidate/" + token + "/" + kind || seen.contains(token)) continue;
            seen.insert(token); entries.append(QVariantMap{{"token", token}, {"kind", kind}, {"previewPath", path}, {"expiresAt", c.value("expires_at").toDouble()}});
            if (entries.size() == 24) break;
        }
        result["choices"] = entries;
    } else if (action == "plan") {
        const auto p = o.value("resolved_profile").toObject();
        if (o.value("source") != "deterministic_preset_v1" || p.value("policy_version") != QJsonValue(1) || !p.value("fields").isObject()) return {};
        QVariantList fields;
        const auto all = p.value("fields").toObject();
        const QStringList sources{"explicit_launch_request", "client_launch_request", "paired_client", "client_profile", "device_profile_v1", "capability_validation", "composed_display_components"};
        const auto field = [&](const char* key) -> QJsonValue {
            const auto f = all.value(key).toObject();
            if (!sources.contains(f.value("source").toString()) || bounded(f.value("reason_code"), 128).isEmpty() ||
                !f.value("locked").isBool() || !f.value("normalized").isBool()) return QJsonValue(QJsonValue::Undefined);
            return f.value("value");
        };
        const auto width = field("display_width"), height = field("display_height"), fps = field("target_fps"), bitrate = field("target_bitrate_kbps");
        const auto integral = [](QJsonValue value, double lo, double hi) { return value.isDouble() && std::isfinite(value.toDouble()) &&
            value.toDouble() >= lo && value.toDouble() <= hi && std::floor(value.toDouble()) == value.toDouble(); };
        const auto mode = field("display_mode").toString().split('x');
        if (!integral(width, 320, 16384) || !integral(height, 240, 16384) || !integral(bitrate, 1000, 300000) ||
            !fps.isDouble() || !std::isfinite(fps.toDouble()) || fps.toDouble() < 15 || fps.toDouble() > 240 ||
            !field("hdr").isBool() || mode.size() != 3 || mode[0].toInt() != width.toInt() || mode[1].toInt() != height.toInt() ||
            std::abs(mode[2].toDouble() - fps.toDouble()) > 0.001) return {};
        for (const auto* key : {"display_mode", "target_bitrate_kbps", "target_fps", "preferred_codec", "hdr"}) {
            const auto f = all.value(key).toObject(); const auto value = field(key);
            if (value.isUndefined() || value.isNull() || value.isObject() || value.isArray() || bounded(f.value("source"), 80).isEmpty()) continue;
            auto text = value.toVariant().toString(); if (!clean(text, 128)) continue;
            fields.append(QVariantMap{{"key", QString::fromLatin1(key)}, {"value", text}, {"source", bounded(f.value("source"), 80)},
                {"locked", f.value("locked") == QJsonValue(true)}, {"normalized", f.value("normalized") == QJsonValue(true)}});
        }
        if (fields.isEmpty()) return {};
        result = {{"fields", fields}, {"preset", bounded(p.value("preset"), 64)}, {"label", bounded(p.value("preset_label"), 80)}};
    } else if (action == "steam") {
        // The controller independently reads the fresh catalog to confirm the write.
        if (o.value("status") != QJsonValue(true)) return {};
        result["accepted"] = true;
    } else {
        auto manifest = o.value("artwork").toObject();
        if (manifest.isEmpty()) manifest = o.value("game").toObject().value("artwork").toObject();
        if (manifest.isEmpty()) manifest = o.value("data").toObject().value("artwork").toObject();
        if (manifest.isEmpty()) manifest = o.value("data").toObject().value("game").toObject().value("artwork").toObject();
        if (manifest.isEmpty() && (o.contains("revision") || o.contains("assets"))) manifest = o;
        if (manifest.isEmpty() || (manifest.contains("version") && manifest.value("version") != QJsonValue(1)) ||
            (!manifest.contains("revision") && !manifest.value("assets").isObject()) ||
            (manifest.contains("revision") && (!manifest.value("revision").isString() ||
                (!manifest.value("revision").toString().isEmpty() && bounded(manifest.value("revision"),128).isEmpty())))) return {};
        result["revision"] = manifest.value("revision").toString();
        if (action == "spaceRefresh") {
            if (manifest.value("version") != QJsonValue(1) || !manifest.value("assets").isObject()) return {};
            const auto resolution = manifest.value("resolution").toObject();
            const auto status = resolution.value("status").toString();
            if (!QStringList{"healthy", "updated", "partial_failure"}.contains(status)) return {};
            const auto kindsList = [&](const char* key) -> std::optional<QStringList> {
                if (!resolution.value(key).isArray()) return {};
                QStringList values;
                for (const auto raw : resolution.value(key).toArray()) {
                    if (!raw.isString() || !kinds.contains(raw.toString()) || values.contains(raw.toString())) return {};
                    values.append(raw.toString());
                }
                return values;
            };
            const auto requested = kindsList("requested_kinds"), remaining = kindsList("remaining_kinds");
            if (!requested || !remaining) return {};
            for (const auto& kind : *remaining) if (!requested->contains(kind)) return {};
            if ((status == "healthy" && !requested->isEmpty()) || (status == "updated" && (requested->isEmpty() || !remaining->isEmpty())) ||
                (status == "partial_failure" && remaining->isEmpty())) return {};
            result["resolution"] = status; result["requested"] = *requested; result["remaining"] = *remaining;
        }
    }
    return result;
}
}
