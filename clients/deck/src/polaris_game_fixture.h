#pragma once
#include "polaris/deck_launch_modes.h"
#include "polaris/deck_stream_capabilities.h"

#include "polaris/deck_artwork.h"
#include "polaris/deck_game_time.h"

#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

namespace nova::deck {

struct PolarisLaunchModeFixture {
    std::string preferredMode;
    std::string recommendedMode;
    std::vector<std::string> allowedModes;
    std::string modeReason;
};

struct PolarisSteamLaunchFixture {
    bool available = false;
    std::string mode;
    std::string recommendedMode;
    std::vector<std::string> allowedModes;
    std::string modeReason;
};

struct PolarisHostFixture {
    std::string id;
    std::string displayName;
    std::string statusLabel;
    std::string subtitle;
};

struct PolarisGameFixture {
    std::string id;
    int appId = 0;
    std::string name;
    std::string source;
    std::string launcherSource;
    std::string launcherDetail;
    std::string platform;
    std::string runtime;
    std::string platformLabel;
    std::string runtimeLabel;
    std::string steamAppid;
    std::string category;
    bool installed = false;
    std::string coverUrl;
    std::vector<std::string> genres;
    std::int64_t lastLaunched = 0;
    DeckGameTime gameTime;
    std::string spaceId, spaceName;
    bool mangohud = false;
    bool hdrSupported = false;
    PolarisLaunchModeFixture launchMode;
    PolarisSteamLaunchFixture steamLaunch;
    DeckArtworkManifest artwork;
    DeckLaunchModePolicy launchPolicy;
    DeckStreamCapabilities streamCapabilities;
    DeckDisplayPlanner displayPlanner;
};

struct PolarisGameLibraryFixture {
    std::string sourceLabel;
    bool readOnly = true;
    std::vector<PolarisHostFixture> hosts;
    std::vector<PolarisGameFixture> games;
};

std::filesystem::path samplePolarisGameFixturePath();
std::filesystem::path samplePolarisGameLibraryFixturePath();
PolarisGameFixture loadPolarisGameFixture(const std::filesystem::path& path);
PolarisGameFixture loadSamplePolarisGameFixture();
PolarisGameLibraryFixture loadPolarisGameLibraryFixture(const std::filesystem::path& path);
PolarisGameLibraryFixture loadSamplePolarisGameLibraryFixture();

} // namespace nova::deck
