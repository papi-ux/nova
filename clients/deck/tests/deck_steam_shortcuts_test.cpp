// Tests for the Steam shortcuts writer: binary KeyValues fidelity, the
// non-Steam app id, idempotent registration, and the refusal to write while
// Steam runs. NOVA_DECK_STEAM_SHORTCUTS_FIXTURE can point at a real
// shortcuts.vdf copy for a byte-for-byte round trip.
#include "runtime/deck_steam_shortcuts.h"

#include <cassert>
#include <cstdlib>
#include <unistd.h>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>

namespace {

using namespace nova::deck::runtime;
namespace fs = std::filesystem;

std::string readAll(const fs::path& path) {
    std::ifstream in(path, std::ios::binary);
    return std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

const DeckVdfValue* find(const DeckVdfObject& object, const std::string& key) {
    for (const auto& [k, v] : object) {
        if (k == key) {
            return &v;
        }
    }
    return nullptr;
}

std::string syntheticFile() {
    // One existing entry the way Steam writes it, then the closing pair of end markers.
    std::string bytes;
    bytes += std::string("\x00shortcuts\x00", 11);
    bytes += std::string("\x00" "0" "\x00", 3);
    bytes += std::string("\x02" "appid" "\x00", 7) + std::string("\x01\x02\x03\x84", 4);
    bytes += std::string("\x01" "AppName" "\x00" "Lutris" "\x00", 16);
    bytes += std::string("\x01" "Exe" "\x00" "\"/usr/bin/lutris\"" "\x00", 23);
    bytes += std::string("\x01" "StartDir" "\x00" "\"/usr/bin/\"" "\x00", 22);
    bytes += std::string("\x02" "IsHidden" "\x00", 10) + std::string("\x00\x00\x00\x00", 4);
    bytes += std::string("\x00" "tags" "\x00", 6) + std::string("\x08", 1);
    bytes += std::string("\x08", 1);  // end entry 0
    bytes += std::string("\x08", 1);  // end shortcuts
    bytes += std::string("\x08", 1);  // end document
    return bytes;
}

void testRoundTripsSyntheticFile() {
    const auto bytes = syntheticFile();
    const auto parsed = parseBinaryVdf(bytes);
    assert(parsed.has_value());
    assert(parsed->size() == 1 && parsed->front().first == "shortcuts");
    const auto& shortcuts = parsed->front().second.children;
    assert(shortcuts.size() == 1 && shortcuts.front().first == "0");
    const auto& entry = shortcuts.front().second.children;
    assert(find(entry, "AppName")->text == "Lutris");
    assert(find(entry, "appid")->kind == DeckVdfValue::Kind::Int32);
    assert(static_cast<std::uint32_t>(find(entry, "appid")->number) == 0x84030201u);
    assert(serializeBinaryVdf(*parsed) == bytes);

    assert(!parseBinaryVdf("").has_value());
    assert(!parseBinaryVdf(bytes.substr(0, bytes.size() - 1)).has_value() && "missing document terminator");
    assert(!parseBinaryVdf(bytes + "x").has_value() && "trailing bytes");
}

void testRoundTripsARealFileWhenProvided() {
    const char* fixture = std::getenv("NOVA_DECK_STEAM_SHORTCUTS_FIXTURE");
    if (fixture == nullptr || *fixture == '\0') {
        return;
    }
    const auto bytes = readAll(fixture);
    assert(!bytes.empty());
    const auto parsed = parseBinaryVdf(bytes);
    assert(parsed.has_value() && "a real Steam shortcuts.vdf must parse");
    assert(serializeBinaryVdf(*parsed) == bytes && "a real Steam shortcuts.vdf must round-trip byte for byte");
}

void testAppIdMatchesSteam() {
    // crc32("\"/usr/bin/flatpak\"Nova") with the top bit set; the seed is exe then name.
    const auto id = steamShortcutAppId("\"/usr/bin/flatpak\"", "Nova");
    assert((id & 0x80000000u) != 0);
    assert(id == steamShortcutAppId("\"/usr/bin/flatpak\"", "Nova"));
    assert(id != steamShortcutAppId("\"/usr/bin/flatpak\"", "Nova 2"));
    assert(steamShortcutAppId("", "") == (0x00000000u | 0x80000000u));
}

void testRegistrationAppendsThenReplaces() {
    const auto parsed = parseBinaryVdf(syntheticFile());
    assert(parsed.has_value());
    DeckSteamShortcut nova;
    nova.appName = "Nova";
    nova.exe = "\"/usr/bin/flatpak\"";
    nova.startDir = "\"/usr/bin/\"";
    nova.launchOptions = "run com.papi_ux.Nova";
    nova.tags = {"Nova"};

    const auto first = registerShortcut(*parsed, nova);
    assert(!first.replaced);
    assert(first.entryKey == "1");
    const auto& shortcuts = first.document.front().second.children;
    assert(shortcuts.size() == 2);
    const auto& entry = shortcuts.back().second.children;
    assert(find(entry, "AppName")->text == "Nova");
    assert(find(entry, "Exe")->text == "\"/usr/bin/flatpak\"");
    assert(find(entry, "LaunchOptions")->text == "run com.papi_ux.Nova");
    assert(find(entry, "AllowOverlay")->number == 1);
    assert(find(entry, "tags")->children.size() == 1 && find(entry, "tags")->children.front().second.text == "Nova");
    assert(static_cast<std::uint32_t>(find(entry, "appid")->number) == first.appId);
    // The original entry is byte-identical after the append.
    assert(serializeBinaryVdf(first.document).find(syntheticFile().substr(11, 80)) != std::string::npos);

    nova.launchOptions = "run com.papi_ux.Nova --live";
    const auto second = registerShortcut(first.document, nova);
    assert(second.replaced);
    assert(second.entryKey == "1");
    assert(second.document.front().second.children.size() == 2);
    assert(find(second.document.front().second.children.back().second.children, "LaunchOptions")->text == "run com.papi_ux.Nova --live");

    const auto fromEmpty = registerShortcut(DeckVdfObject{}, nova);
    assert(!fromEmpty.replaced && fromEmpty.entryKey == "0");
    assert(parseBinaryVdf(serializeBinaryVdf(fromEmpty.document)).has_value());
}

void testWritesFilesAtomicallyAndRefusesWhileSteamRuns() {
    const auto root = fs::temp_directory_path() / ("nova-deck-shortcuts-" + std::to_string(::getpid()));
    fs::create_directories(root / "userdata" / "11324806" / "config");
    fs::create_directories(root / "userdata" / "0" / "config");
    fs::create_directories(root / "userdata" / "anonymous" / "config");
    const auto files = defaultShortcutFiles(root);
    assert(files.size() == 1 && files.front().parent_path().parent_path().filename() == "11324806");

    {
        std::ofstream out(files.front(), std::ios::binary);
        const auto bytes = syntheticFile();
        out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    }
    DeckSteamShortcut nova;
    nova.appName = "Nova";
    nova.exe = "\"/usr/bin/flatpak\"";
    nova.startDir = "\"/usr/bin/\"";
    nova.launchOptions = "run com.papi_ux.Nova";

    const auto refused = writeShortcutForAccount(files, nova, true);
    assert(!refused.ok && refused.detail.find("close Steam") != std::string::npos);
    assert(readAll(files.front()) == syntheticFile() && "nothing written while Steam runs");

    const auto written = writeShortcutForAccount(files, nova, false);
    assert(written.ok);
    assert(written.written.size() == 1);
    const auto after = parseBinaryVdf(readAll(files.front()));
    assert(after.has_value() && after->front().second.children.size() == 2);
    assert(!fs::exists(files.front().string() + ".nova-tmp"));

    const auto again = writeShortcutForAccount(files, nova, false);
    assert(again.ok);
    assert(parseBinaryVdf(readAll(files.front()))->front().second.children.size() == 2 && "re-registering replaces, never duplicates");

    const auto none = writeShortcutForAccount({}, nova, false);
    assert(!none.ok);

    fs::remove_all(root);
}

void testSteamDetectionReadsProc() {
    const auto root = fs::temp_directory_path() / ("nova-deck-proc-" + std::to_string(::getpid()));
    fs::create_directories(root / "4242");
    fs::create_directories(root / "4243");
    fs::create_directories(root / "notapid");
    { std::ofstream(root / "4242" / "comm") << "steam\n"; std::ofstream(root / "4242" / "status") << "Name:\tsteam\nUid:\t1000\t1000\t1000\t1000\n"; }
    { std::ofstream(root / "4243" / "comm") << "steamwebhelper\n"; std::ofstream(root / "4243" / "status") << "Name:\tsteamwebhelper\nUid:\t1000\t1000\t1000\t1000\n"; }
    assert(steamClientRunning(root, 1000));
    assert(!steamClientRunning(root, 1001));
    fs::remove(root / "4242" / "comm");
    assert(!steamClientRunning(root, 1000));
    fs::remove_all(root);
}

} // namespace

int main() {
    testRoundTripsSyntheticFile();
    testRoundTripsARealFileWhenProvided();
    testAppIdMatchesSteam();
    testRegistrationAppendsThenReplaces();
    testWritesFilesAtomicallyAndRefusesWhileSteamRuns();
    testSteamDetectionReadsProc();
    return 0;
}
