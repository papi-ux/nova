#include "runtime/deck_steam_shortcuts.h"

#include <QByteArray>
#include <QProcess>
#include <QSaveFile>
#include <QString>
#include <QStringList>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <system_error>

namespace nova::deck::runtime {

namespace {

constexpr char kTypeObject = '\x00';
constexpr char kTypeString = '\x01';
constexpr char kTypeInt32 = '\x02';
constexpr char kEndObject = '\x08';

bool sameKey(const std::string_view a, const std::string_view b) {
    if (a.size() != b.size()) {
        return false;
    }
    for (std::size_t i = 0; i < a.size(); ++i) {
        if (std::tolower(static_cast<unsigned char>(a[i])) != std::tolower(static_cast<unsigned char>(b[i]))) {
            return false;
        }
    }
    return true;
}

struct Reader {
    std::string_view bytes;
    std::size_t pos = 0;

    bool done() const {
        return pos >= bytes.size();
    }

    std::optional<char> byte() {
        if (done()) {
            return std::nullopt;
        }
        return bytes[pos++];
    }

    std::optional<std::string> cstring() {
        const auto end = bytes.find('\0', pos);
        if (end == std::string_view::npos) {
            return std::nullopt;
        }
        std::string value(bytes.substr(pos, end - pos));
        pos = end + 1;
        return value;
    }

    std::optional<std::int32_t> int32() {
        if (pos + 4 > bytes.size()) {
            return std::nullopt;
        }
        std::uint32_t raw = 0;
        for (int i = 3; i >= 0; --i) {
            raw = (raw << 8) | static_cast<unsigned char>(bytes[pos + static_cast<std::size_t>(i)]);
        }
        pos += 4;
        return static_cast<std::int32_t>(raw);
    }
};

std::optional<DeckVdfObject> parseObject(Reader& reader) {
    DeckVdfObject object;
    for (;;) {
        const auto type = reader.byte();
        if (!type) {
            return std::nullopt;
        }
        if (*type == kEndObject) {
            return object;
        }
        auto key = reader.cstring();
        if (!key) {
            return std::nullopt;
        }
        DeckVdfValue value;
        if (*type == kTypeObject) {
            value.kind = DeckVdfValue::Kind::Object;
            auto children = parseObject(reader);
            if (!children) {
                return std::nullopt;
            }
            value.children = std::move(*children);
        } else if (*type == kTypeString) {
            value.kind = DeckVdfValue::Kind::String;
            auto text = reader.cstring();
            if (!text) {
                return std::nullopt;
            }
            value.text = std::move(*text);
        } else if (*type == kTypeInt32) {
            value.kind = DeckVdfValue::Kind::Int32;
            auto number = reader.int32();
            if (!number) {
                return std::nullopt;
            }
            value.number = *number;
        } else {
            return std::nullopt;
        }
        object.emplace_back(std::move(*key), std::move(value));
    }
}

void serializeObject(const DeckVdfObject& object, std::string& out) {
    for (const auto& [key, value] : object) {
        switch (value.kind) {
        case DeckVdfValue::Kind::Object:
            out.push_back(kTypeObject);
            out += key;
            out.push_back('\0');
            serializeObject(value.children, out);
            break;
        case DeckVdfValue::Kind::String:
            out.push_back(kTypeString);
            out += key;
            out.push_back('\0');
            out += value.text;
            out.push_back('\0');
            break;
        case DeckVdfValue::Kind::Int32: {
            out.push_back(kTypeInt32);
            out += key;
            out.push_back('\0');
            auto raw = static_cast<std::uint32_t>(value.number);
            for (int i = 0; i < 4; ++i) {
                out.push_back(static_cast<char>(raw & 0xff));
                raw >>= 8;
            }
            break;
        }
        }
    }
    out.push_back(kEndObject);
}

std::uint32_t crc32(std::string_view data) {
    static const auto table = [] {
        std::array<std::uint32_t, 256> t{};
        for (std::uint32_t i = 0; i < 256; ++i) {
            std::uint32_t c = i;
            for (int k = 0; k < 8; ++k) {
                c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            }
            t[i] = c;
        }
        return t;
    }();
    std::uint32_t crc = 0xFFFFFFFFu;
    for (const unsigned char ch : data) {
        crc = table[(crc ^ ch) & 0xff] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFFu;
}

DeckVdfValue stringValue(std::string text) {
    DeckVdfValue value;
    value.kind = DeckVdfValue::Kind::String;
    value.text = std::move(text);
    return value;
}

DeckVdfValue intValue(const std::int32_t number) {
    DeckVdfValue value;
    value.kind = DeckVdfValue::Kind::Int32;
    value.number = number;
    return value;
}

/// Set a key, keeping the spelling already in the object when it exists.
void setString(DeckVdfObject& object, const std::string_view key, std::string text) {
    if (auto* existing = findKey(object, key); existing != nullptr && existing->kind == DeckVdfValue::Kind::String) {
        existing->text = std::move(text);
        return;
    }
    object.emplace_back(std::string(key), stringValue(std::move(text)));
}

bool stringContains(const DeckVdfObject& object, const std::string_view key, const std::string_view needle) {
    const auto* value = findKey(object, key);
    return value != nullptr && value->kind == DeckVdfValue::Kind::String && value->text.find(needle) != std::string::npos;
}

bool isNovaEntry(const DeckVdfObject& entry, const DeckSteamShortcut& shortcut) {
    const auto* name = findKey(entry, "AppName");
    if (name == nullptr || name->kind != DeckVdfValue::Kind::String || !sameKey(name->text, shortcut.appName)) {
        return false;
    }
    if (const auto* exe = findKey(entry, "Exe"); exe != nullptr && exe->kind == DeckVdfValue::Kind::String && exe->text == shortcut.exe) {
        return true;
    }
    for (const auto& marker : shortcut.identityMarkers) {
        if (stringContains(entry, "Exe", marker) || stringContains(entry, "LaunchOptions", marker)) {
            return true;
        }
    }
    return false;
}

DeckVdfObject newEntry(const DeckSteamShortcut& shortcut, const std::uint32_t appId) {
    DeckVdfObject entry;
    entry.emplace_back("appid", intValue(static_cast<std::int32_t>(appId)));
    entry.emplace_back("AppName", stringValue(shortcut.appName));
    entry.emplace_back("Exe", stringValue(shortcut.exe));
    entry.emplace_back("StartDir", stringValue(shortcut.startDir));
    entry.emplace_back("icon", stringValue(shortcut.icon));
    entry.emplace_back("ShortcutPath", stringValue(""));
    entry.emplace_back("LaunchOptions", stringValue(shortcut.launchOptions));
    entry.emplace_back("IsHidden", intValue(0));
    entry.emplace_back("AllowDesktopConfig", intValue(1));
    entry.emplace_back("AllowOverlay", intValue(1));
    entry.emplace_back("OpenVR", intValue(0));
    entry.emplace_back("Devkit", intValue(0));
    entry.emplace_back("DevkitGameID", stringValue(""));
    entry.emplace_back("DevkitOverrideAppID", intValue(0));
    entry.emplace_back("LastPlayTime", intValue(0));
    entry.emplace_back("FlatpakAppID", stringValue(""));
    DeckVdfValue tags;
    tags.kind = DeckVdfValue::Kind::Object;
    int index = 0;
    for (const auto& tag : shortcut.tags) {
        tags.children.emplace_back(std::to_string(index++), stringValue(tag));
    }
    entry.emplace_back("tags", std::move(tags));
    return entry;
}

/// Update only what Nova owns on an entry that is already Nova's.
void mergeEntry(DeckVdfObject& entry, const DeckSteamShortcut& shortcut) {
    setString(entry, "AppName", shortcut.appName);
    setString(entry, "Exe", shortcut.exe);
    setString(entry, "StartDir", shortcut.startDir);
    setString(entry, "LaunchOptions", shortcut.launchOptions);
    if (!shortcut.icon.empty()) {
        setString(entry, "icon", shortcut.icon);
    }
    auto* tags = findKey(entry, "tags");
    if (tags == nullptr || tags->kind != DeckVdfValue::Kind::Object) {
        DeckVdfValue container;
        container.kind = DeckVdfValue::Kind::Object;
        entry.emplace_back("tags", std::move(container));
        tags = &entry.back().second;
    }
    for (const auto& tag : shortcut.tags) {
        bool present = false;
        for (const auto& [key, value] : tags->children) {
            present = present || (value.kind == DeckVdfValue::Kind::String && value.text == tag);
        }
        if (!present) {
            int next = 0;
            for (const auto& [key, value] : tags->children) {
                char* end = nullptr;
                const long parsed = std::strtol(key.c_str(), &end, 10);
                if (end != nullptr && *end == '\0' && parsed >= next) {
                    next = static_cast<int>(parsed) + 1;
                }
            }
            tags->children.emplace_back(std::to_string(next), stringValue(tag));
        }
    }
}

} // namespace

std::optional<DeckVdfObject> parseBinaryVdf(const std::string_view bytes) {
    Reader reader{bytes};
    // The document is one object: the end marker that closes it is the last
    // byte of the file. Anything after it means the file is not what Steam writes.
    auto root = parseObject(reader);
    if (!root || !reader.done()) {
        return std::nullopt;
    }
    return root;
}

std::string serializeBinaryVdf(const DeckVdfObject& root) {
    std::string out;
    serializeObject(root, out);
    return out;
}

const DeckVdfValue* findKey(const DeckVdfObject& object, const std::string_view key) {
    for (const auto& [k, v] : object) {
        if (sameKey(k, key)) {
            return &v;
        }
    }
    return nullptr;
}

DeckVdfValue* findKey(DeckVdfObject& object, const std::string_view key) {
    for (auto& [k, v] : object) {
        if (sameKey(k, key)) {
            return &v;
        }
    }
    return nullptr;
}

std::uint32_t steamShortcutAppId(const std::string_view exe, const std::string_view appName) {
    std::string seed(exe);
    seed += appName;
    return crc32(seed) | 0x80000000u;
}

DeckShortcutRegistration registerShortcut(const DeckVdfObject& document, const DeckSteamShortcut& shortcut) {
    DeckShortcutRegistration registration;
    registration.document = document;

    auto* shortcuts = findKey(registration.document, "shortcuts");
    if (shortcuts == nullptr || shortcuts->kind != DeckVdfValue::Kind::Object) {
        DeckVdfValue container;
        container.kind = DeckVdfValue::Kind::Object;
        registration.document.emplace_back("shortcuts", std::move(container));
        shortcuts = &registration.document.back().second;
    }

    for (auto& [key, value] : shortcuts->children) {
        if (value.kind != DeckVdfValue::Kind::Object || !isNovaEntry(value.children, shortcut)) {
            continue;
        }
        mergeEntry(value.children, shortcut);
        registration.replaced = true;
        registration.entryKey = key;
        if (const auto* appId = findKey(value.children, "appid"); appId != nullptr && appId->kind == DeckVdfValue::Kind::Int32) {
            registration.appId = static_cast<std::uint32_t>(appId->number);
        } else {
            registration.appId = steamShortcutAppId(shortcut.exe, shortcut.appName);
            value.children.insert(value.children.begin(), {"appid", intValue(static_cast<std::int32_t>(registration.appId))});
        }
        return registration;
    }

    int next = 0;
    for (const auto& [key, value] : shortcuts->children) {
        char* end = nullptr;
        const long parsed = std::strtol(key.c_str(), &end, 10);
        if (end != nullptr && *end == '\0' && parsed >= next) {
            next = static_cast<int>(parsed) + 1;
        }
    }
    registration.appId = steamShortcutAppId(shortcut.exe, shortcut.appName);
    DeckVdfValue entry;
    entry.kind = DeckVdfValue::Kind::Object;
    entry.children = newEntry(shortcut, registration.appId);
    registration.entryKey = std::to_string(next);
    shortcuts->children.emplace_back(registration.entryKey, std::move(entry));
    return registration;
}

std::vector<std::filesystem::path> defaultShortcutFiles(const std::filesystem::path& steamRoot) {
    std::vector<std::filesystem::path> files;
    std::error_code ec;
    const auto userdata = steamRoot / "userdata";
    std::filesystem::directory_iterator it(userdata, ec);
    const std::filesystem::directory_iterator end;
    for (; !ec && it != end; it.increment(ec)) {
        const auto name = it->path().filename().string();
        if (name.empty() || name == "0" || name.find_first_not_of("0123456789") != std::string::npos) {
            continue;
        }
        if (std::filesystem::is_directory(it->path() / "config", ec)) {
            files.push_back(it->path() / "config" / "shortcuts.vdf");
        }
    }
    std::sort(files.begin(), files.end());
    return files;
}

std::vector<std::filesystem::path> defaultSteamRoots() {
    std::vector<std::filesystem::path> roots;
    // NOVA_DECK_STEAM_ROOT points a proof at a scratch Steam directory instead of the real one.
    if (const char* override = std::getenv("NOVA_DECK_STEAM_ROOT"); override != nullptr && *override != '\0') {
        roots.emplace_back(override);
        return roots;
    }
    const char* home = std::getenv("HOME");
    if (home == nullptr || *home == '\0') {
        return roots;
    }
    const std::filesystem::path base(home);
    // SteamOS and native Steam share one directory through a symlink; Flatpak Steam keeps its own.
    for (const auto& candidate : {base / ".local" / "share" / "Steam", base / ".steam" / "steam", base / ".var" / "app" / "com.valvesoftware.Steam" / ".local" / "share" / "Steam"}) {
        std::error_code ec;
        if (!std::filesystem::is_directory(candidate / "userdata", ec)) {
            continue;
        }
        const auto canonical = std::filesystem::canonical(candidate, ec);
        const auto path = ec ? candidate : canonical;
        if (std::find(roots.begin(), roots.end(), path) == roots.end()) {
            roots.push_back(path);
        }
    }
    return roots;
}

bool steamClientRunning(const std::filesystem::path& procRoot, const unsigned uid) {
    std::error_code ec;
    std::filesystem::directory_iterator it(procRoot, ec);
    const std::filesystem::directory_iterator end;
    for (; !ec && it != end; it.increment(ec)) {
        const auto name = it->path().filename().string();
        if (name.empty() || name.find_first_not_of("0123456789") != std::string::npos) {
            continue;
        }
        std::ifstream comm(it->path() / "comm");
        std::string process;
        if (!std::getline(comm, process) || process != "steam") {
            continue;
        }
        std::ifstream status(it->path() / "status");
        std::string line;
        while (std::getline(status, line)) {
            if (line.rfind("Uid:", 0) == 0) {
                if (std::strtoul(line.c_str() + 4, nullptr, 10) == uid) {
                    return true;
                }
                break;
            }
        }
    }
    return false;
}

std::optional<bool> steamClientRunningForAccount(const bool insideFlatpak, const unsigned uid) {
    if (!insideFlatpak) {
        return steamClientRunning("/proc", uid);
    }
    // The sandbox has its own PID namespace; ask the host session instead.
    QProcess probe;
    probe.setProgram(QStringLiteral("flatpak-spawn"));
    probe.setArguments({QStringLiteral("--host"), QStringLiteral("pgrep"), QStringLiteral("-x"), QStringLiteral("-u"), QString::number(uid), QStringLiteral("steam")});
    probe.setStandardOutputFile(QProcess::nullDevice());
    probe.setStandardErrorFile(QProcess::nullDevice());
    probe.start();
    if (!probe.waitForStarted(5000) || !probe.waitForFinished(10000) || probe.exitStatus() != QProcess::NormalExit) {
        return std::nullopt;
    }
    // pgrep: 0 = at least one match, 1 = none; anything else is an error.
    if (probe.exitCode() == 0) {
        return true;
    }
    if (probe.exitCode() == 1) {
        return false;
    }
    return std::nullopt;
}

DeckShortcutWriteResult writeShortcutForAccount(
    const std::vector<std::filesystem::path>& shortcutFiles,
    const DeckSteamShortcut& shortcut,
    const bool steamRunning) {
    DeckShortcutWriteResult result;
    if (steamRunning) {
        result.detail = "Steam is running; it rewrites shortcuts.vdf on exit, so close Steam first.";
        return result;
    }
    if (shortcutFiles.empty()) {
        result.detail = "No Steam user data directory was found for this account.";
        return result;
    }

    // Prepare every file before touching any, so one bad profile refuses the
    // whole run instead of leaving the others half done.
    std::vector<std::pair<std::filesystem::path, std::string>> prepared;
    for (const auto& file : shortcutFiles) {
        DeckVdfObject document;
        std::error_code ec;
        if (std::filesystem::exists(file, ec)) {
            std::ifstream in(file, std::ios::binary);
            std::string bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
            auto parsed = parseBinaryVdf(bytes);
            if (!parsed) {
                result.detail = "Could not read the existing shortcuts file for profile " + file.parent_path().parent_path().filename().string() + "; nothing was changed.";
                return result;
            }
            document = std::move(*parsed);
        }
        const auto registration = registerShortcut(document, shortcut);
        result.appId = registration.appId;
        prepared.emplace_back(file, serializeBinaryVdf(registration.document));
    }

    for (const auto& [file, bytes] : prepared) {
        QSaveFile out(QString::fromStdString(file.string()));
        if (!out.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
            result.detail = "Could not write the shortcuts file for profile " + file.parent_path().parent_path().filename().string() + ".";
            return result;
        }
        const auto written = out.write(bytes.data(), static_cast<qint64>(bytes.size()));
        if (written != static_cast<qint64>(bytes.size()) || !out.commit()) {
            result.detail = "Could not finish writing the shortcuts file for profile " + file.parent_path().parent_path().filename().string() + ".";
            return result;
        }
        result.written.push_back(file);
    }
    result.ok = true;
    result.detail = result.written.size() == 1
        ? "Registered in one Steam profile. Restart Steam to see it."
        : "Registered in " + std::to_string(result.written.size()) + " Steam profiles. Restart Steam to see it.";
    return result;
}

} // namespace nova::deck::runtime
