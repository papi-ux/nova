#include "polaris_game_fixture.h"

#include <charconv>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string_view>

#ifndef NOVA_DECK_SAMPLE_GAME_FIXTURE
#define NOVA_DECK_SAMPLE_GAME_FIXTURE "../fixtures/sample_polaris_game.json"
#endif

#ifndef NOVA_DECK_SAMPLE_LIBRARY_FIXTURE
#define NOVA_DECK_SAMPLE_LIBRARY_FIXTURE "../fixtures/sample_polaris_library.json"
#endif

namespace nova::deck {
namespace {

std::string readTextFile(const std::filesystem::path& path) {
    std::ifstream input(path);
    if (!input) {
        throw std::runtime_error("Unable to open Polaris fixture: " + path.string());
    }

    return std::string(
        std::istreambuf_iterator<char>(input),
        std::istreambuf_iterator<char>()
    );
}

std::size_t findKey(const std::string& json, const std::string_view key, const std::size_t start = 0) {
    const std::string needle = "\"" + std::string(key) + "\"";
    const auto keyPos = json.find(needle, start);
    if (keyPos == std::string::npos) {
        throw std::runtime_error("Missing Polaris fixture key: " + std::string(key));
    }

    const auto colonPos = json.find(char(58), keyPos + needle.size());
    if (colonPos == std::string::npos) {
        throw std::runtime_error("Malformed Polaris fixture key: " + std::string(key));
    }

    return colonPos + 1;
}

std::size_t skipWhitespace(const std::string& json, std::size_t pos) {
    while (pos < json.size() && (json[pos] == 32 || json[pos] == 10 || json[pos] == 13 || json[pos] == 9)) {
        ++pos;
    }
    return pos;
}

std::string readStringAt(const std::string& json, std::size_t pos) {
    pos = skipWhitespace(json, pos);
    if (pos >= json.size() || json[pos] != char(34)) {
        throw std::runtime_error("Expected string in Polaris fixture");
    }

    std::string value;
    bool escaped = false;
    for (std::size_t index = pos + 1; index < json.size(); ++index) {
        const char ch = json[index];
        if (escaped) {
            value.push_back(ch);
            escaped = false;
            continue;
        }
        if (ch == char(92)) {
            escaped = true;
            continue;
        }
        if (ch == char(34)) {
            return value;
        }
        value.push_back(ch);
    }

    throw std::runtime_error("Unterminated string in Polaris fixture");
}

std::string readString(const std::string& json, const std::string_view key, const std::size_t start = 0) {
    return readStringAt(json, findKey(json, key, start));
}

std::string readOptionalString(const std::string& json, const std::string_view key, const std::string_view fallback) {
    try {
        return readString(json, key);
    } catch (const std::runtime_error&) {
        return std::string(fallback);
    }
}

int readInt(const std::string& json, const std::string_view key) {
    auto pos = skipWhitespace(json, findKey(json, key));
    const auto end = json.find_first_of(",}\n", pos);
    int value = 0;
    const auto* begin = json.data() + pos;
    const auto* finish = json.data() + (end == std::string::npos ? json.size() : end);
    const auto [ptr, error] = std::from_chars(begin, finish, value);
    (void)ptr;
    if (error != std::errc()) {
        throw std::runtime_error("Invalid integer in Polaris fixture: " + std::string(key));
    }
    return value;
}

std::int64_t readInt64(const std::string& json, const std::string_view key) {
    auto pos = skipWhitespace(json, findKey(json, key));
    const auto end = json.find_first_of(",}\n", pos);
    std::int64_t value = 0;
    const auto* begin = json.data() + pos;
    const auto* finish = json.data() + (end == std::string::npos ? json.size() : end);
    const auto [ptr, error] = std::from_chars(begin, finish, value);
    (void)ptr;
    if (error != std::errc()) {
        throw std::runtime_error("Invalid integer in Polaris fixture: " + std::string(key));
    }
    return value;
}

bool readBool(const std::string& json, const std::string_view key, const std::size_t start = 0) {
    const auto pos = skipWhitespace(json, findKey(json, key, start));
    if (json.compare(pos, 4, "true") == 0) {
        return true;
    }
    if (json.compare(pos, 5, "false") == 0) {
        return false;
    }
    throw std::runtime_error("Invalid boolean in Polaris fixture: " + std::string(key));
}

bool readOptionalBool(const std::string& json, const std::string_view key, const bool fallback) {
    try {
        return readBool(json, key);
    } catch (const std::runtime_error&) {
        return fallback;
    }
}

std::vector<std::string> readStringArray(const std::string& json, const std::string_view key, const std::size_t start = 0) {
    auto pos = skipWhitespace(json, findKey(json, key, start));
    if (pos >= json.size() || json[pos] != char(91)) {
        throw std::runtime_error("Expected array in Polaris fixture: " + std::string(key));
    }

    std::vector<std::string> values;
    ++pos;
    while (pos < json.size()) {
        pos = skipWhitespace(json, pos);
        if (pos < json.size() && json[pos] == char(93)) {
            return values;
        }
        values.push_back(readStringAt(json, pos));
        pos = json.find_first_of(",]", pos + 1);
        if (pos == std::string::npos) {
            throw std::runtime_error("Unterminated array in Polaris fixture: " + std::string(key));
        }
        if (json[pos] == char(44)) {
            ++pos;
        }
    }

    throw std::runtime_error("Unterminated array in Polaris fixture: " + std::string(key));
}

std::size_t objectStart(const std::string& json, const std::string_view key) {
    const auto pos = skipWhitespace(json, findKey(json, key));
    if (pos >= json.size() || json[pos] != char(123)) {
        throw std::runtime_error("Expected object in Polaris fixture: " + std::string(key));
    }
    return pos;
}

std::size_t arrayStart(const std::string& json, const std::string_view key) {
    const auto pos = skipWhitespace(json, findKey(json, key));
    if (pos >= json.size() || json[pos] != char(91)) {
        throw std::runtime_error("Expected array in Polaris fixture: " + std::string(key));
    }
    return pos;
}

std::string readObjectAt(const std::string& json, std::size_t pos) {
    pos = skipWhitespace(json, pos);
    if (pos >= json.size() || json[pos] != char(123)) {
        throw std::runtime_error("Expected object in Polaris fixture array");
    }

    int depth = 0;
    bool inString = false;
    bool escaped = false;
    for (std::size_t index = pos; index < json.size(); ++index) {
        const char ch = json[index];
        if (inString) {
            if (escaped) {
                escaped = false;
            } else if (ch == char(92)) {
                escaped = true;
            } else if (ch == char(34)) {
                inString = false;
            }
            continue;
        }

        if (ch == char(34)) {
            inString = true;
            continue;
        }
        if (ch == char(123)) {
            ++depth;
            continue;
        }
        if (ch == char(125)) {
            --depth;
            if (depth == 0) {
                return json.substr(pos, index - pos + 1);
            }
        }
    }

    throw std::runtime_error("Unterminated object in Polaris fixture array");
}

std::vector<std::string> readObjectArray(const std::string& json, const std::string_view key) {
    std::vector<std::string> objects;
    std::size_t pos = arrayStart(json, key) + 1;
    while (pos < json.size()) {
        pos = skipWhitespace(json, pos);
        if (pos < json.size() && json[pos] == char(93)) {
            return objects;
        }

        const auto objectJson = readObjectAt(json, pos);
        objects.push_back(objectJson);
        pos += objectJson.size();
        pos = skipWhitespace(json, pos);
        if (pos < json.size() && json[pos] == char(44)) {
            ++pos;
            continue;
        }
        if (pos < json.size() && json[pos] == char(93)) {
            return objects;
        }
        throw std::runtime_error("Malformed object array in Polaris fixture: " + std::string(key));
    }

    throw std::runtime_error("Unterminated object array in Polaris fixture: " + std::string(key));
}

PolarisHostFixture parsePolarisHostFixtureJson(const std::string& json) {
    PolarisHostFixture host;
    host.id = readString(json, "id");
    host.displayName = readString(json, "display_name");
    host.statusLabel = readString(json, "status_label");
    host.subtitle = readOptionalString(json, "subtitle", "Read-only host snapshot fixture — not discovered from the network.");
    return host;
}

PolarisGameFixture parsePolarisGameFixtureJson(const std::string& json) {
    const auto launchModeStart = objectStart(json, "launch_mode");
    const auto steamLaunchStart = objectStart(json, "steam_launch");

    PolarisGameFixture game;
    game.id = readString(json, "id");
    game.appId = readInt(json, "app_id");
    game.name = readString(json, "name");
    game.source = readString(json, "source");
    game.launcherSource = readString(json, "launcher_source");
    game.launcherDetail = readString(json, "launcher_detail");
    game.platform = readString(json, "platform");
    game.runtime = readString(json, "runtime");
    game.platformLabel = readString(json, "platform_label");
    game.runtimeLabel = readString(json, "runtime_label");
    game.steamAppid = readString(json, "steam_appid");
    game.category = readString(json, "category");
    game.installed = readBool(json, "installed");
    game.coverUrl = readString(json, "cover_url");
    game.genres = readStringArray(json, "genres");
    game.lastLaunched = readInt64(json, "last_launched");
    game.mangohud = readBool(json, "mangohud");
    game.hdrSupported = readBool(json, "hdr_supported");

    game.launchMode.preferredMode = readString(json, "preferred_mode", launchModeStart);
    game.launchMode.recommendedMode = readString(json, "recommended_mode", launchModeStart);
    game.launchMode.allowedModes = readStringArray(json, "allowed_modes", launchModeStart);
    game.launchMode.modeReason = readString(json, "mode_reason", launchModeStart);

    game.steamLaunch.available = readBool(json, "available", steamLaunchStart);
    game.steamLaunch.mode = readString(json, "mode", steamLaunchStart);
    game.steamLaunch.recommendedMode = readString(json, "recommended_mode", steamLaunchStart);
    game.steamLaunch.allowedModes = readStringArray(json, "allowed_modes", steamLaunchStart);
    game.steamLaunch.modeReason = readString(json, "mode_reason", steamLaunchStart);

    return game;
}

} // namespace

std::filesystem::path samplePolarisGameFixturePath() {
    if (const auto* overridePath = std::getenv("NOVA_DECK_SAMPLE_GAME_FIXTURE_PATH")) {
        if (overridePath[0] != char(0)) {
            return std::filesystem::path(overridePath);
        }
    }
    return std::filesystem::path(NOVA_DECK_SAMPLE_GAME_FIXTURE);
}

std::filesystem::path samplePolarisGameLibraryFixturePath() {
    if (const auto* overridePath = std::getenv("NOVA_DECK_SAMPLE_LIBRARY_FIXTURE_PATH")) {
        if (overridePath[0] != char(0)) {
            return std::filesystem::path(overridePath);
        }
    }
    return std::filesystem::path(NOVA_DECK_SAMPLE_LIBRARY_FIXTURE);
}

PolarisGameFixture loadPolarisGameFixture(const std::filesystem::path& path) {
    return parsePolarisGameFixtureJson(readTextFile(path));
}

PolarisGameFixture loadSamplePolarisGameFixture() {
    return loadPolarisGameFixture(samplePolarisGameFixturePath());
}

PolarisGameLibraryFixture loadPolarisGameLibraryFixture(const std::filesystem::path& path) {
    const auto json = readTextFile(path);
    PolarisGameLibraryFixture library;
    library.sourceLabel = readOptionalString(json, "fixture_source", "Shared Polaris contract fixture");
    library.readOnly = readOptionalBool(json, "read_only", true);

    for (const auto& objectJson : readObjectArray(json, "hosts")) {
        library.hosts.push_back(parsePolarisHostFixtureJson(objectJson));
    }

    for (const auto& objectJson : readObjectArray(json, "games")) {
        library.games.push_back(parsePolarisGameFixtureJson(objectJson));
    }

    if (library.games.empty()) {
        throw std::runtime_error("Polaris game library fixture must contain at least one game: " + path.string());
    }

    return library;
}

PolarisGameLibraryFixture loadSamplePolarisGameLibraryFixture() {
    return loadPolarisGameLibraryFixture(samplePolarisGameLibraryFixturePath());
}

} // namespace nova::deck
