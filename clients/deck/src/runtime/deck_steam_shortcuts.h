#pragma once

#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

// Steam keeps non-Steam shortcuts in a binary KeyValues file,
// userdata/<id>/config/shortcuts.vdf. Registering Nova there is what makes it
// launchable from Game Mode. The file is rewritten by Steam on exit, so edits
// only stick while Steam is closed, and every other entry must survive the
// round trip byte for byte. KeyValues keys are case-insensitive; Steam and
// third-party tools spell them differently, so lookups never assume a case.
namespace nova::deck::runtime {

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

/// Parse a whole binary KeyValues document. nullopt on malformed input or trailing bytes.
std::optional<DeckVdfObject> parseBinaryVdf(std::string_view bytes);

/// Serialize back to the exact bytes Steam writes.
std::string serializeBinaryVdf(const DeckVdfObject& root);

/// Case-insensitive key lookup, the way Steam reads KeyValues.
const DeckVdfValue* findKey(const DeckVdfObject& object, std::string_view key);
DeckVdfValue* findKey(DeckVdfObject& object, std::string_view key);

struct DeckSteamShortcut {
    std::string appName;
    std::string exe;  ///< quoted the way Steam stores it, e.g. "/usr/bin/flatpak"
    std::string startDir;  ///< quoted, e.g. "/usr/bin/"
    std::string icon;  ///< left alone on replace when empty
    std::string launchOptions;
    std::vector<std::string> tags;
    /// Substrings that mark an entry as Nova's own (in Exe or LaunchOptions), so an
    /// unrelated shortcut that happens to share the name is never touched.
    std::vector<std::string> identityMarkers = {"com.papi_ux.Nova", "nova-deck"};
};

/// Steam's id for a non-Steam shortcut: crc32(exe + appName) with the top bit set.
std::uint32_t steamShortcutAppId(std::string_view exe, std::string_view appName);

/**
 * @brief Add Nova's entry, or update the fields Nova owns on the entry that is already Nova's.
 *
 * A replace keeps the entry's appid (grid art and controller layouts hang off
 * it), its play time, hidden and overlay flags, and every key Nova does not
 * own; only AppName, Exe, StartDir, LaunchOptions, a non-empty icon and the
 * presence of Nova's tags change.
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

/// True when a `steam` process runs for @p uid, read from a procfs root.
bool steamClientRunning(const std::filesystem::path& procRoot, unsigned uid);

/**
 * @brief Whether Steam runs for this account, seen from where Nova runs.
 *
 * Inside a Flatpak the sandbox has its own PID namespace and never sees the
 * host's Steam, so the question goes to the host through flatpak-spawn.
 * nullopt means the answer could not be obtained; callers refuse in that case.
 */
std::optional<bool> steamClientRunningForAccount(bool insideFlatpak, unsigned uid);

struct DeckShortcutWriteResult {
    bool ok = false;
    std::string detail;
    std::vector<std::filesystem::path> written;
    std::uint32_t appId = 0;
};

/**
 * @brief Register the shortcut in every candidate file, creating the first when none exists.
 *
 * Every file is parsed and serialized before any is written, so a bad file
 * refuses the whole run instead of leaving profiles half done; each write is
 * atomic (temp file, flush, rename). Refuses while Steam runs.
 */
DeckShortcutWriteResult writeShortcutForAccount(
    const std::vector<std::filesystem::path>& shortcutFiles,
    const DeckSteamShortcut& shortcut,
    bool steamRunning);

} // namespace nova::deck::runtime
