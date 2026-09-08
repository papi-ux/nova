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
    return findKey(object, key);
}

std::string entryBytes(const DeckSteamShortcut& shortcut, std::uint32_t appId) {
    return serializeBinaryVdf(registerShortcut(DeckVdfObject{}, shortcut).document);
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
    bytes += std::string("\x08", 1);  // end of the root object, the last byte Steam writes
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

    // Steam and the player touch the entry between runs: play time, a custom
    // icon, a hidden flag, a collection tag and a key Nova has never heard of.
    auto touched = first.document;
    auto& novaEntry = touched.front().second.children.back().second.children;
    const auto originalAppId = find(novaEntry, "appid")->number;
    for (auto& [key, value] : novaEntry) {
        if (key == "LastPlayTime") value.number = 1725760000;
        if (key == "icon") value.text = "/srv/icons/nova.png";
        if (key == "IsHidden") value.number = 1;
        if (key == "tags") { DeckVdfValue extra; extra.kind = DeckVdfValue::Kind::String; extra.text = "Favorites"; value.children.emplace_back("1", extra); }
    }
    DeckVdfValue unknown; unknown.kind = DeckVdfValue::Kind::String; unknown.text = "keep me";
    novaEntry.emplace_back("SomeFutureSteamKey", unknown);

    nova.exe = "\"/usr/bin/flatpak\"";
    nova.launchOptions = "run com.papi_ux.Nova --live";
    const auto second = registerShortcut(touched, nova);
    assert(second.replaced);
    assert(second.entryKey == "1");
    assert(second.document.front().second.children.size() == 2);
    const auto& merged = second.document.front().second.children.back().second.children;
    assert(find(merged, "LaunchOptions")->text == "run com.papi_ux.Nova --live");
    assert(find(merged, "appid")->number == originalAppId && "a replace keeps the app id grid art hangs off");
    assert(second.appId == static_cast<std::uint32_t>(originalAppId));
    assert(find(merged, "LastPlayTime")->number == 1725760000);
    assert(find(merged, "icon")->text == "/srv/icons/nova.png" && "an empty icon in the request leaves the player's icon alone");
    assert(find(merged, "IsHidden")->number == 1);
    assert(find(merged, "SomeFutureSteamKey")->text == "keep me");
    assert(find(merged, "tags")->children.size() == 2 && "existing tags stay, Nova's tag is not duplicated");

    const auto fromEmpty = registerShortcut(DeckVdfObject{}, nova);
    assert(!fromEmpty.replaced && fromEmpty.entryKey == "0");
    assert(parseBinaryVdf(serializeBinaryVdf(fromEmpty.document)).has_value());
}

void testLowercaseKeysAreFoundAndSpellingIsKept() {
    // Older clients and third-party tools write lowercase keys; Steam reads them case-insensitively.
    std::string bytes;
    bytes += std::string("\x00shortcuts\x00", 11);
    bytes += std::string("\x00" "0" "\x00", 3);
    bytes += std::string("\x02" "appid" "\x00", 7) + std::string("\x01\x02\x03\x84", 4);
    bytes += std::string("\x01" "appname" "\x00" "Nova" "\x00", 14);
    bytes += std::string("\x01" "exe" "\x00" "\"/usr/bin/flatpak\"" "\x00", 24);
    bytes += std::string("\x01" "launchoptions" "\x00" "run com.papi_ux.Nova" "\x00", 36);
    bytes += std::string("\x08", 1) + std::string("\x08", 1) + std::string("\x08", 1);
    const auto parsed = parseBinaryVdf(bytes);
    assert(parsed.has_value());
    DeckSteamShortcut nova;
    nova.appName = "Nova";
    nova.exe = "\"/usr/bin/flatpak\"";
    nova.startDir = "\"/usr/bin/\"";
    nova.launchOptions = "run com.papi_ux.Nova --live";
    const auto registration = registerShortcut(*parsed, nova);
    assert(registration.replaced && "the lowercase entry is Nova's and is updated, not duplicated");
    const auto& shortcuts = registration.document.front().second.children;
    assert(shortcuts.size() == 1);
    const auto& entry = shortcuts.front().second.children;
    bool sawLowercase = false;
    for (const auto& [key, value] : entry) {
        if (key == "launchoptions") { sawLowercase = true; assert(value.text == "run com.papi_ux.Nova --live"); }
        assert(key != "LaunchOptions" && "no second spelling is added");
    }
    assert(sawLowercase);
    assert(find(entry, "StartDir") != nullptr && "a key Nova owns that was missing is added");
}

void testUnrelatedShortcutNamedNovaIsLeftAlone() {
    const auto parsed = parseBinaryVdf(syntheticFile());
    assert(parsed.has_value());
    DeckSteamShortcut other;
    other.appName = "Nova";
    other.exe = "\"/opt/some-other-tool/nova\"";
    other.startDir = "\"/opt/some-other-tool/\"";
    other.launchOptions = "--fancy";
    other.identityMarkers = {"some-other-tool"};
    const auto withOther = registerShortcut(*parsed, other);
    assert(!withOther.replaced);

    DeckSteamShortcut nova;
    nova.appName = "Nova";
    nova.exe = "\"/usr/bin/flatpak\"";
    nova.startDir = "\"/usr/bin/\"";
    nova.launchOptions = "run com.papi_ux.Nova --live";
    const auto ours = registerShortcut(withOther.document, nova);
    assert(!ours.replaced && "same name, not ours: appended, never overwritten");
    const auto& shortcuts = ours.document.front().second.children;
    assert(shortcuts.size() == 3);
    assert(find(shortcuts[1].second.children, "Exe")->text == "\"/opt/some-other-tool/nova\"");
    assert(find(shortcuts[2].second.children, "LaunchOptions")->text == "run com.papi_ux.Nova --live");
}

void testOneBadProfileRefusesTheWholeRun() {
    const auto root = fs::temp_directory_path() / ("nova-deck-profiles-" + std::to_string(::getpid()));
    fs::create_directories(root / "userdata" / "111" / "config");
    fs::create_directories(root / "userdata" / "222" / "config");
    const auto files = defaultShortcutFiles(root);
    assert(files.size() == 2);
    { std::ofstream out(files[0], std::ios::binary); const auto b = syntheticFile(); out.write(b.data(), static_cast<std::streamsize>(b.size())); }
    { std::ofstream out(files[1], std::ios::binary); out << "definitely not a KeyValues file"; }
    DeckSteamShortcut nova;
    nova.appName = "Nova";
    nova.exe = "\"/usr/bin/flatpak\"";
    nova.startDir = "\"/usr/bin/\"";
    nova.launchOptions = "run com.papi_ux.Nova --live";
    const auto result = writeShortcutForAccount(files, nova, false);
    assert(!result.ok);
    assert(result.written.empty() && "nothing is written when a later profile cannot be read");
    assert(result.detail.find("222") != std::string::npos);
    assert(readAll(files[0]) == syntheticFile() && "the good profile is untouched");
    fs::remove_all(root);
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
    testLowercaseKeysAreFoundAndSpellingIsKept();
    testUnrelatedShortcutNamedNovaIsLeftAlone();
    testOneBadProfileRefusesTheWholeRun();
    testWritesFilesAtomicallyAndRefusesWhileSteamRuns();
    testSteamDetectionReadsProc();
    return 0;
}
