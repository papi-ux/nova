#pragma once
#include <QVariantMap>
#include <optional>
#include <string>

namespace nova::deck::polaris {
struct DeckGameToolRequest { std::string path, method = "GET", body; };
// Closed route/body vocabulary. Neither QML nor host-supplied preview URLs can
// select an arbitrary request target or attach pairing material to another host.
std::optional<DeckGameToolRequest> gameToolRequest(const QString& game, const QString& action, const QVariantMap& values);
std::optional<QVariantMap> gameToolReply(const QString& game, const QString& action, const QVariantMap& values, std::string_view json);
bool validGameToolId(const QString& id);
bool validSpaceArtworkId(const QString& id);
bool validEncoderChoice(const QString& id);
}
