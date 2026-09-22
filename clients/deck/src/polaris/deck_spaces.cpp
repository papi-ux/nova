#include "polaris/deck_spaces.h"
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QSet>
#include <QStringDecoder>
#include <algorithm>
#include <charconv>

namespace nova::deck::polaris {
namespace {
bool matches(std::string_view value, const char* pattern) {
    return QRegularExpression(QString::fromLatin1(pattern)).match(QString::fromUtf8(value.data(), value.size())).hasMatch();
}
bool validTitleNumber(std::string_view target) {
    if (!matches(target, "^[1-9][0-9]{0,9}\\z")) return false;
    unsigned long long value = 0;
    const auto parsed = std::from_chars(target.data(), target.data() + target.size(), value);
    return parsed.ec == std::errc{} && parsed.ptr == target.data() + target.size() && value <= 4294967295ULL;
}
bool validTarget(std::string_view target) {
    // Match Android's WorkerLaunchContract: an identity carries no launcher
    // family, so admit their union. The host enforces the Space's family.
    if (target == "big-picture-v1" || target == "library-v1") return true;
    if (target.starts_with("id.")) return validTitleNumber(target.substr(3));
    const auto dot = target.find('.');
    if (dot == std::string_view::npos) return validTitleNumber(target);
    const auto runner = target.substr(0, dot);
    return (runner == "epic" || runner == "gog" || runner == "amazon" || runner == "sideload") &&
        matches(target.substr(dot + 1), "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\z");
}
bool nameValid(const QString& name) {
    return !name.trimmed().isEmpty() && name.toUtf8().size() <= 128 &&
        std::none_of(name.begin(), name.end(), [](QChar c) { return c.unicode() < 32 || c.unicode() == 127; });
}
// The JSON parser checks grammar; this small walk retains object-key identity.
struct Keys {
    std::string_view json;
    std::size_t pos = 0;
    int limit;
    void whitespace() { while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t' || json[pos] == '\r' || json[pos] == '\n')) ++pos; }
    bool take(char c) { whitespace(); if (pos == json.size() || json[pos] != c) return false; ++pos; return true; }
    std::optional<QString> string() {
        whitespace(); const auto start = pos;
        if (!take('"')) return {};
        bool escaped = false, ended = false;
        while (pos < json.size()) {
            const char c = json[pos++];
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (c == '"') { ended = true; break; }
        }
        if (!ended) return {};
        const auto token = json.substr(start, pos - start);
        const auto parsed = QJsonDocument::fromJson("[" + QByteArray(token.data(), token.size()) + "]");
        if (!parsed.isArray() || parsed.array().size() != 1 || !parsed.array().first().isString()) return {};
        return parsed.array().first().toString();
    }
    bool value(int depth) {
        whitespace(); if (depth > limit || pos == json.size()) return false;
        if (json[pos] == '{') {
            ++pos; QSet<QString> keys;
            if (take('}')) return true;
            do {
                const auto key = string();
                if (!key || keys.contains(*key) || !take(':')) return false;
                keys.insert(*key);
                if (!value(depth + 1)) return false;
                if (take('}')) return true;
            } while (take(','));
            return false;
        }
        if (json[pos] == '[') {
            ++pos; if (take(']')) return true;
            do { if (!value(depth + 1)) return false; if (take(']')) return true; } while (take(','));
            return false;
        }
        if (json[pos] == '"') return string().has_value();
        const auto start = pos;
        while (pos < json.size() && json[pos] != ',' && json[pos] != '}' && json[pos] != ']') ++pos;
        return pos > start;
    }
};
}
bool validSpaceId(std::string_view id) { return id != "desktop" && matches(id, "^[A-Za-z0-9_][A-Za-z0-9_-]{0,127}$"); }
std::optional<DeckSpaceGameIdentity> spaceGameIdentity(std::string_view id) {
    if (!id.starts_with("space.")) return {};
    const auto dot = id.find('.', 6);
    if (dot == std::string_view::npos || !validSpaceId(id.substr(6, dot - 6))) return {};
    const auto target = id.substr(dot + 1);
    if (!validTarget(target)) return {};
    return DeckSpaceGameIdentity{std::string(id.substr(6, dot - 6)), std::string(target)};
}
bool isSpaceGame(std::string_view id) {
    // Keep ordinary-session controls off for the entire reserved namespace,
    // including old or malformed cached IDs. Admission uses the strict parser.
    return id == kLegacySpaceAppUuid || id == "1347244801" || id.starts_with("space.");
}
const DeckSpace* DeckSpaces::selected() const {
    const auto item = std::find_if(spaces.begin(), spaces.end(), [&](const auto& s) { return s.id == selectedId && s.selected; });
    return item == spaces.end() ? nullptr : &*item;
}
bool DeckSpaces::permitsSelection(std::string_view id) const {
    if (!enabled || !available || !canSwitch) return false;
    if (id == "desktop") return desktopAllowed;
    return std::any_of(spaces.begin(), spaces.end(), [&](const auto& s) { return s.id == id && s.state != "unavailable"; });
}
bool DeckSpaces::permitsPlay(std::string_view id) const {
    if (!enabled) return id == "desktop";
    if (!available || selectedId != id) return false;
    if (id == "desktop") return desktopAllowed;
    const auto* current = selected();
    return current && current->openable();
}
bool uniqueJsonFields(std::string_view json, int maximumDepth) {
    if (json.empty() || json.size() > 2 * 1024 * 1024) return false;
    QStringDecoder utf8(QStringDecoder::Utf8); const QString decoded = utf8.decode(QByteArrayView(json.data(), json.size()));
    if (utf8.hasError()) return false;
    Keys walk{json, 0, maximumDepth};
    if (!walk.value(0)) return false;
    walk.whitespace();
    return walk.pos == json.size();
}
std::optional<DeckSpaces> parseSpaces(std::string_view json) {
    if (!uniqueJsonFields(json, 4)) return {};
    const auto document = QJsonDocument::fromJson(QByteArray(json.data(), json.size()));
    if (!document.isObject()) return {};
    const auto o = document.object();
    if (!o.value("schema").isDouble() || o.value("schema").toDouble() != 1 || o.value("status") != QJsonValue(true)) return {};
    for (const auto* key : {"enabled", "available", "can_switch"}) if (!o.value(key).isBool()) return {};
    if (!o.value("selected_space_id").isString() || !o.value("spaces").isArray() || o.value("spaces").toArray().size() > 4096) return {};
    const auto optionalWord = [](const QJsonObject& obj, const char* key, std::string& output) {
        const auto v = obj.value(key);
        if (v.isUndefined() || v.isNull() || v == QJsonValue("")) return true;
        if (!v.isString() || !matches(v.toString().toStdString(), "^[A-Za-z0-9_:.-]{1,128}$")) return false;
        output = v.toString().toStdString(); return true;
    };
    DeckSpaces result;
    result.enabled = o.value("enabled").toBool(); result.available = o.value("available").toBool();
    result.canSwitch = o.value("can_switch").toBool(); result.selectedId = o.value("selected_space_id").toString().toStdString();
    if (o.contains("desktop_allowed") && !o.value("desktop_allowed").isBool()) return {};
    result.desktopAllowed = o.value("desktop_allowed").toBool();
    if (!optionalWord(o, "unavailable_reason", result.unavailableReason) || !optionalWord(o, "switch_blocked_reason", result.switchBlockedReason)) return {};
    QSet<QString> ids; int selected = 0;
    for (const auto v : o.value("spaces").toArray()) {
        if (!v.isObject()) return {};
        const auto s = v.toObject();
        const auto id = s.value("id").toString(), name = s.value("name").toString(), state = s.value("state").toString();
        if (!validSpaceId(id.toStdString()) || ids.contains(id) || !nameValid(name) || !s.value("selected").isBool() ||
            !QStringList{"ready", "starting", "running", "stopping", "in_use", "unavailable"}.contains(state)) return {};
        for (const auto* key : {"library_enabled", "can_open"}) if (s.contains(key) && !s.value(key).isBool()) return {};
        DeckSpace space{id.toStdString(), name.toStdString(), state.toStdString()};
        space.selected = s.value("selected").toBool(); space.libraryEnabled = s.value("library_enabled").toBool();
        space.canOpen = s.contains("can_open") ? s.value("can_open").toBool() : state == "ready" || state == "running";
        if (!optionalWord(s, "blocked_reason", space.blockedReason)) return {};
        if (space.selected) { ++selected; if (space.id != result.selectedId) return {}; }
        ids.insert(id); result.spaces.push_back(std::move(space));
    }
    if ((result.available && !result.enabled) || (result.canSwitch && !result.available)) return {};
    if (result.available) {
        if (result.selectedId == "desktop") { if (!result.desktopAllowed || selected) return {}; }
        else if (selected != 1) return {};
    } else if (!result.selectedId.empty() || selected) return {};
    return result;
}
}
