#pragma once
#include "deck_host_settings_fixture.h"
#include "runtime/deck_game_tools.h"
#include <QDateTime>

namespace game_tools_fixture {
inline std::string settings() {
    auto o = host_settings_fixture::settings(); auto caps = o["capabilities"].toObject();
    caps["session_encoder_override"] = true;
    caps["encoders"] = QJsonArray{QJsonObject{{"value","vaapi"},{"label","AMD / VA-API"},{"available",true},{"fallback_allowed",false},{"reason","Use the host's AMD encoder."}},
        QJsonObject{{"value","nvenc"},{"label","NVIDIA NVENC"},{"available",false}}};
    o["capabilities"] = caps; return host_settings_fixture::json(o);
}
inline std::string plan() {
    QJsonObject fields;
    const QJsonObject values{{"display_mode","1280x800x60"},{"display_width",1280},{"display_height",800},{"target_fps",60},{"target_bitrate_kbps",20000},{"preferred_codec","h264"},{"hdr",false}};
    for (auto it = values.begin(); it != values.end(); ++it) fields[it.key()] = QJsonObject{{"value",it.value()},{"source","paired_client"},{"reason_code","paired_setting"},{"locked",false},{"normalized",false}};
    return host_settings_fixture::json({{"source","deterministic_preset_v1"},{"resolved_profile",QJsonObject{{"policy_version",1},{"preset","quality"},{"preset_label","Quality"},{"fields",fields}}}});
}
inline QVariantMap candidate() { return {{"provider","steamgriddb"},{"provider_game_id","42"},{"title","Moonlit Harbor"},{"steam_appid","123"}}; }
inline QString token(int index = 0) { return QString(31, 'a') + QString::number(index); }
inline std::string search() {
    auto c = QJsonObject::fromVariantMap(candidate()); c["preview"] = QJsonObject{{"poster","/polaris/v1/games/game/artwork/candidate/" + token() + "/poster"}};
    return host_settings_fixture::json({{"status",true},{"candidates",QJsonArray{c}}});
}
inline std::string choices(const QString& kind, bool expired = false) {
    QJsonArray list;
    for (int i = 0; i < 4; ++i) list.append(QJsonObject{{"selection_token",token(i)}, {"preview","/polaris/v1/games/game/artwork/candidate/" + token(i) + "/" + kind}, {"expires_at",QDateTime::currentSecsSinceEpoch() + (expired ? -1 : 600)}});
    return host_settings_fixture::json({{"status",true},{"kind",kind},{"choices",list}});
}
template<class T> nova::deck::polaris::DeckPolarisResult<T> ok(T value) { return {nova::deck::polaris::DeckPolarisRequestStatus::Ok,200,{},std::move(value)}; }
struct Host {
    std::atomic<bool> valid{true}, allowed{true}, lost{false}, hold{false}, entered{false}, expired{false};
    std::atomic<int> writes{0};
    QString steam = "direct";
    nova::deck::runtime::DeckGameToolsResolver resolver() {
        return [this]() -> std::optional<nova::deck::runtime::DeckGameToolsTarget> {
            return nova::deck::runtime::DeckGameToolsTarget{
                [this](const QString& game, const QString& action, const QVariantMap& values, const std::function<bool()>& cancelled) {
                    using namespace nova::deck::polaris;
                    entered = true;
                    while (hold && !cancelled()) QThread::msleep(1);
                    if (cancelled()) return DeckPolarisResult<QVariantMap>{};
                    if (action == "apply" || action == "reset" || action == "refreshArt" || action == "steam") {
                        ++writes;
                        if (lost) return DeckPolarisResult<QVariantMap>{DeckPolarisRequestStatus::Timeout,0,{}, {}};
                        if (action == "steam") steam = values.value("mode").toString();
                    }
                    const auto json = action == "settings" ? settings() : action == "plan" ? plan() : action == "search" ? search()
                        : action == "choices" ? choices(values.value("kind").toString(), expired) : action == "steam" ? std::string{"{\"status\":true}"}
                        : std::string{"{\"status\":true,\"artwork\":{\"revision\":\"2\"}}"};
                    const auto parsed = gameToolReply(game, action, values, json);
                    return parsed ? ok(*parsed) : DeckPolarisResult<QVariantMap>{DeckPolarisRequestStatus::MalformedBody,200,{}, {}};
                },
                [this](const auto&) {
                    nova::deck::polaris::DeckPolarisGame game;
                    game.id = "game"; game.name = "Moonlit Harbor"; game.appId = 42;
                    game.steamLaunchAvailable = true; game.steamLaunchMode = steam.toStdString(); game.steamLaunchAllowedModes = {"direct","big-picture"};
                    return ok(allowed ? std::vector{game} : std::vector<nova::deck::polaris::DeckPolarisGame>{});
                }, [this] { return valid.load(); }};
        };
    }
};
}
