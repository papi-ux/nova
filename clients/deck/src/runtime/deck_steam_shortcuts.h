#pragma once

#include <cstdint>
#include <filesystem>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

// Steam keeps non-Steam shortcuts in a binary KeyValues file,
// userdata/<id>/config/shortcuts.vdf. Registering Nova there is what makes it
// launchable from Game Mode. The file is rewritten by Steam on exit, so edits
// only stick while Steam is closed, and every other entry must survive the
// round trip byte for byte.
namespace nova::deck::runtime {

/// One value in a binary KeyValues object, in file order.
struct DeckVdfValue;
using DeckVdfObject = std::vector<std::pair<std::string, DeckVdfValue>>;

struct DeckVdfValue {
    enum class Kind {
        Object,
        String,
        Int32,
    };
    Kind kind = Kind::String;
    std::string text;
    std::int32_t number = 0;
    DeckVdfObject children;
};

/// Parse a whole binary KeyValues document (the root object, `shortcuts` for this file). nullopt on malformed input.
std::optional<DeckVdfObject> parseBinaryVdf(std::string_view bytes);

/// Serialize back to the exact bytes Steam writes.
std::string serializeBinaryVdf(const DeckVdfObject& root);

struct DeckSteamShortcut {
    std::string appName;
    std::string exe;  ///< quoted the way Steam stores it, e.g. "/usr/bin/flatpak"
    std::string startDir;  ///< quoted, e.g. "/usr/bin/"
    std::string icon;
    std::string launchOptions;
    std::vector<std::string> tags;
};

/// Steam's id for a non-Steam shortcut: crc32(exe + appName) with the top bit set.
std::uint32_t steamShortcutAppId(std::string_view exe, std::string_view appName);

/**
 * @brief Add or replace the shortcut whose AppName matches, leaving every other entry untouched.
 * @return the new document and whether an existing entry was replaced.
 */
struct DeckShortcutRegistration {
    DeckVdfObject document;
    bool replaced = false;
    std::uint32_t appId = 0;
    std::string entryKey;
};
DeckShortcutRegistration registerShortcut(const DeckVdfObject& document, const DeckSteamShortcut& shortcut);

/// Candidate shortcuts.vdf files for the current account: one per Steam user id under userdata.
std::vector<std::filesystem::path> defaultShortcutFiles(const std::filesystem::path& steamRoot);
std::vector<std::filesystem::path> defaultSteamRoots();

/// True when a Steam client process runs for this account; shortcuts.vdf edits are lost while it does.
bool steamClientRunning(const std::filesystem::path& procRoot, unsigned uid);

struct DeckShortcutWriteResult {
    bool ok = false;
    std::string detail;
    std::vector<std::filesystem::path> written;
    std::uint32_t appId = 0;
};

/// Register the shortcut in every candidate file (creating the first when none exists). Refuses while Steam runs.
DeckShortcutWriteResult writeShortcutForAccount(
    const std::vector<std::filesystem::path>& shortcutFiles,
    const DeckSteamShortcut& shortcut,
    bool steamRunning);

} // namespace nova::deck::runtime
