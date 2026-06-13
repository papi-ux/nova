#pragma once

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
    bool mangohud = false;
    bool hdrSupported = false;
    PolarisLaunchModeFixture launchMode;
    PolarisSteamLaunchFixture steamLaunch;
};

std::filesystem::path samplePolarisGameFixturePath();
PolarisGameFixture loadPolarisGameFixture(const std::filesystem::path& path);
PolarisGameFixture loadSamplePolarisGameFixture();

} // namespace nova::deck
