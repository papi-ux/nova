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

namespace nova::deck {
namespace {

std::string readTextFile(const std::filesystem::path& path) {
    std::ifstream input(path);
    if (!input) {
        throw std::runtime_error("Unable to open Polaris game fixture: " + path.string());
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
        throw std::runtime_error("Missing Polaris game fixture key: " + std::string(key));
    }

    const auto colonPos = json.find(char(58), keyPos + needle.size());
    if (colonPos == std::string::npos) {
        throw std::runtime_error("Malformed Polaris game fixture key: " + std::string(key));
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
        throw std::runtime_error("Expected string in Polaris game fixture");
    }

    const auto end = json.find(char(34), pos + 1);
    if (end == std::string::npos) {
        throw std::runtime_error("Unterminated string in Polaris game fixture");
    }

    return json.substr(pos + 1, end - pos - 1);
}

std::string readString(const std::string& json, const std::string_view key, const std::size_t start = 0) {
    return readStringAt(json, findKey(json, key, start));
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
        throw std::runtime_error("Invalid integer in Polaris game fixture: " + std::string(key));
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
        throw std::runtime_error("Invalid integer in Polaris game fixture: " + std::string(key));
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
    throw std::runtime_error("Invalid boolean in Polaris game fixture: " + std::string(key));
}

std::vector<std::string> readStringArray(const std::string& json, const std::string_view key, const std::size_t start = 0) {
    auto pos = skipWhitespace(json, findKey(json, key, start));
    if (pos >= json.size() || json[pos] != char(91)) {
        throw std::runtime_error("Expected array in Polaris game fixture: " + std::string(key));
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
            throw std::runtime_error("Unterminated array in Polaris game fixture: " + std::string(key));
        }
        if (json[pos] == char(44)) {
            ++pos;
        }
    }

    throw std::runtime_error("Unterminated array in Polaris game fixture: " + std::string(key));
}

std::size_t objectStart(const std::string& json, const std::string_view key) {
    const auto pos = skipWhitespace(json, findKey(json, key));
    if (pos >= json.size() || json[pos] != char(123)) {
        throw std::runtime_error("Expected object in Polaris game fixture: " + std::string(key));
    }
    return pos;
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

PolarisGameFixture loadPolarisGameFixture(const std::filesystem::path& path) {
    const auto json = readTextFile(path);
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

PolarisGameFixture loadSamplePolarisGameFixture() {
    return loadPolarisGameFixture(samplePolarisGameFixturePath());
}

} // namespace nova::deck
