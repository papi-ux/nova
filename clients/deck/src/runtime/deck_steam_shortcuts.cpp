#include "runtime/deck_steam_shortcuts.h"

#include <array>
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

const DeckVdfValue* find(const DeckVdfObject& object, const std::string_view key) {
    for (const auto& [k, v] : object) {
        if (k == key) {
            return &v;
        }
    }
    return nullptr;
}

DeckVdfObject shortcutEntry(const DeckSteamShortcut& shortcut, const std::uint32_t appId) {
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

std::uint32_t steamShortcutAppId(const std::string_view exe, const std::string_view appName) {
    std::string seed(exe);
    seed += appName;
    return crc32(seed) | 0x80000000u;
}

DeckShortcutRegistration registerShortcut(const DeckVdfObject& document, const DeckSteamShortcut& shortcut) {
    DeckShortcutRegistration registration;
    registration.document = document;
    registration.appId = steamShortcutAppId(shortcut.exe, shortcut.appName);

    DeckVdfObject* shortcuts = nullptr;
    for (auto& [key, value] : registration.document) {
        if (key == "shortcuts" && value.kind == DeckVdfValue::Kind::Object) {
            shortcuts = &value.children;
        }
    }
    if (shortcuts == nullptr) {
        DeckVdfValue container;
        container.kind = DeckVdfValue::Kind::Object;
        registration.document.emplace_back("shortcuts", std::move(container));
        shortcuts = &registration.document.back().second.children;
    }

    for (auto& [key, value] : *shortcuts) {
        if (value.kind != DeckVdfValue::Kind::Object) {
            continue;
        }
        const auto* name = find(value.children, "AppName");
        if (name != nullptr && name->kind == DeckVdfValue::Kind::String && name->text == shortcut.appName) {
            value.children = shortcutEntry(shortcut, registration.appId);
            registration.replaced = true;
            registration.entryKey = key;
            return registration;
        }
    }

    int next = 0;
    for (const auto& [key, value] : *shortcuts) {
        char* end = nullptr;
        const long parsed = std::strtol(key.c_str(), &end, 10);
        if (end != nullptr && *end == '\0' && parsed >= next) {
            next = static_cast<int>(parsed) + 1;
        }
    }
    DeckVdfValue entry;
    entry.kind = DeckVdfValue::Kind::Object;
    entry.children = shortcutEntry(shortcut, registration.appId);
    registration.entryKey = std::to_string(next);
    shortcuts->emplace_back(registration.entryKey, std::move(entry));
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
    // The SteamOS layout first; the two are usually the same directory through a symlink.
    for (const auto& candidate : {base / ".local" / "share" / "Steam", base / ".steam" / "steam", base / ".var" / "app" / "com.valvesoftware.Steam" / ".local" / "share" / "Steam"}) {
        std::error_code ec;
        if (std::filesystem::is_directory(candidate / "userdata", ec)) {
            const auto canonical = std::filesystem::canonical(candidate, ec);
            const auto path = ec ? candidate : canonical;
            bool seen = false;
            for (const auto& existing : roots) {
                seen = seen || existing == path;
            }
            if (!seen) {
                roots.push_back(path);
            }
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
    for (const auto& file : shortcutFiles) {
        DeckVdfObject document;
        std::error_code ec;
        if (std::filesystem::exists(file, ec)) {
            std::ifstream in(file, std::ios::binary);
            std::string bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
            auto parsed = parseBinaryVdf(bytes);
            if (!parsed) {
                result.detail = "Could not read the existing shortcuts file: " + file.filename().string();
                return result;
            }
            document = std::move(*parsed);
        }
        const auto registration = registerShortcut(document, shortcut);
        result.appId = registration.appId;
        const auto serialized = serializeBinaryVdf(registration.document);
        const auto temp = file.string() + ".nova-tmp";
        {
            std::ofstream out(temp, std::ios::binary | std::ios::trunc);
            if (!out) {
                result.detail = "Could not write next to the shortcuts file.";
                return result;
            }
            out.write(serialized.data(), static_cast<std::streamsize>(serialized.size()));
        }
        std::filesystem::rename(temp, file, ec);
        if (ec) {
            result.detail = "Could not replace the shortcuts file: " + ec.message();
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
