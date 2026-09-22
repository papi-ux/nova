#pragma once
#include "polaris/deck_launch_modes.h"
#include "polaris/deck_stream_capabilities.h"

#include "polaris/deck_artwork.h"
#include "polaris/deck_game_tools.h"
#include "polaris/deck_game_time.h"
#include "polaris/deck_spaces.h"
#include "polaris/deck_host_telemetry.h"
#include "polaris/deck_host_settings.h"

#include <chrono>
#include <cstddef>
#include <memory>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

// A pinned client for Polaris's /polaris/v1 API on the GameStream HTTPS
// port. It authenticates with the paired Moonlight client certificate and
// trusts exactly one server certificate, the one Moonlight pinned at pairing.
// Any paired certificate that calls /polaris/v1 is promoted to the nova client
// family on the host, so Nova and Moonlight are one identity there.
namespace nova::deck::polaris {

struct DeckPolarisTlsIdentity {
    std::string clientCertificatePem;
    std::string clientPrivateKeyPem;
    std::string pinnedServerCertificatePem;
};

struct DeckPolarisEndpoint {
    std::string address;
    int httpsPort = 47984;
};

enum class DeckPolarisRequestStatus {
    Ok,
    InvalidIdentity,
    Unreachable,
    Timeout,
    CertMismatch,
    Unauthorized,
    HttpError,
    MalformedBody,
};

std::string_view describe(DeckPolarisRequestStatus status);

template <typename T>
struct DeckPolarisResult {
    DeckPolarisRequestStatus status = DeckPolarisRequestStatus::Unreachable;
    int httpStatus = 0;
    std::string detail;
    std::optional<T> value;

    [[nodiscard]] bool ok() const {
        return status == DeckPolarisRequestStatus::Ok && value.has_value();
    }
};

struct DeckHostPower {
    bool supported = false, enabled = false, permitted = false;
    std::string blockedMessage, lastOutcome, lastMessage;
    long long lastAt = 0;
    bool allowed() const { return supported && enabled && permitted; }
};
struct DeckHostSleepReceipt {
    bool accepted = false;
    std::string code, message;
};
std::optional<DeckHostPower> parseHostPower(std::string_view json);

struct DeckPolarisCapabilities {
    std::string server;
    std::string version;
    bool gameLibrary = false;
    bool sessionLifecycle = false;
    bool clientSettings = false;
    bool resolvedProfileProvenance = false;
    bool expectedTopologyAssertion = false;
    bool hostSleep = false;
    bool spaces = false;
    DeckHostPower hostPower;
    std::string captureBackend;
    std::vector<std::string> codecs;
    DeckStreamCapabilities streamCapabilities;
};

struct DeckPolarisGame {
    std::string id;
    int appId = 0;
    std::string name;
    std::string source;
    std::string platform;
    std::string runtime;
    std::string platformLabel;
    std::string runtimeLabel;
    std::string steamAppid;
    std::string category;
    std::string coverUrl;
    bool installed = true;
    bool hdrSupported = false;
    long long lastLaunched = 0;
    DeckGameTime gameTime;
    std::string spaceId, spaceName;
    std::vector<std::string> genres;
    std::string launchPreferredMode;
    std::string launchRecommendedMode;
    std::vector<std::string> launchAllowedModes;
    std::string launchModeReason;
    bool steamLaunchAvailable = false;
    std::string steamLaunchMode;
    std::string steamLaunchRecommendedMode;
    std::vector<std::string> steamLaunchAllowedModes;
    std::string steamLaunchModeReason;
    DeckArtworkManifest artwork;
    bool launchContractValid = true;
    DeckLaunchModePolicy launchPolicy;
    DeckStreamCapabilities streamCapabilities;
    DeckDisplayPlanner displayPlanner;
};

struct DeckPolarisGamesPage {
    std::vector<DeckPolarisGame> games;
    int total = 0;
};

struct DeckPolarisSessionStatus {
    std::string state;
    bool streamingActive = false;
    std::string game;
    std::string gameUuid;
    std::string ownerDeviceName;
    std::string clientRole;
    bool ownedByClient = false;
    int viewerCount = 0;
};

std::optional<DeckPolarisCapabilities> parseCapabilities(std::string_view json);
std::optional<DeckPolarisGamesPage> parseGamesPage(std::string_view json);
std::optional<DeckLaunchModeCatalog> parseLaunchModeCatalog(std::string_view json);
DeckLaunchModePolicy launchModePolicy(const DeckPolarisGame& game, const DeckLaunchModeCatalog& catalog);
std::optional<DeckPolarisSessionStatus> parseSessionStatus(std::string_view json);

/// The HttpsPort a GameStream host advertises in its plain-HTTP serverinfo XML.
std::optional<int> parseServerInfoHttpsPort(std::string_view xml);

/// Split "/path?query" into its two parts; QUrl needs them set separately or it encodes the `?`.
struct DeckPolarisRequestTarget {
    std::string path;
    std::string query;
};
DeckPolarisRequestTarget splitRequestTarget(std::string_view pathWithQuery);

/// What the host's plain-HTTP serverinfo said, or how it failed to.
struct DeckPolarisServerInfoProbe {
    std::optional<int> httpsPort;  ///< the advertised HttpsPort, when the host answered with one
    bool timedOut = false;         ///< nothing came back on the HTTP port within the timeout
};

/// Ask the host's plain-HTTP port which HTTPS port to use, the way Moonlight does
/// before every connection. A refusal or a non-GameStream answer leaves httpsPort
/// empty; an HTTP timeout also sets timedOut for diagnostics. This does not
/// establish that the paired HTTPS endpoint is unreachable.
DeckPolarisServerInfoProbe probeServerInfoHttpsPort(const std::string& address, int httpPort, std::chrono::milliseconds timeout);

/// probeServerInfoHttpsPort without the failure shape: nullopt when the host does not answer or advertise one.
std::optional<int> resolveHttpsPortFromServerInfo(const std::string& address, int httpPort, std::chrono::milliseconds timeout);

class DeckPolarisClient {
public:
    DeckPolarisClient(
        DeckPolarisEndpoint endpoint,
        DeckPolarisTlsIdentity identity,
        std::chrono::milliseconds timeout = std::chrono::milliseconds(4000));

    [[nodiscard]] DeckPolarisResult<std::string> get(const std::string& path, std::size_t maxBodyBytes = 4 * 1024 * 1024) const;
    [[nodiscard]] DeckPolarisResult<DeckPolarisCapabilities> fetchCapabilities() const;
    [[nodiscard]] DeckPolarisResult<DeckPolarisGamesPage> fetchGamesPage(int limit, int offset, bool desktop = false) const;
    [[nodiscard]] DeckPolarisResult<std::vector<DeckPolarisGame>> fetchAllGames(int pageSize = 100,
        const std::function<bool()>& cancelled = {}, bool desktop = false) const;
    [[nodiscard]] DeckPolarisResult<DeckSpaces> fetchSpaces(const std::function<bool()>& cancelled = {}) const;
    [[nodiscard]] DeckPolarisResult<DeckSpaces> selectSpace(const std::string& id, const std::string& previous,
        const std::function<bool()>& cancelled = {}) const;
    [[nodiscard]] DeckPolarisResult<std::vector<DeckPolarisGame>> fetchSpaceLibrary(const std::string& id,
        const std::function<bool()>& cancelled = {}) const;
    [[nodiscard]] DeckPolarisResult<DeckPolarisSessionStatus> fetchSessionStatus() const;
    [[nodiscard]] DeckPolarisResult<DeckHostTelemetry> fetchHostTelemetry(const std::function<bool()>& cancelled = {}) const;
    [[nodiscard]] DeckPolarisResult<DeckDoctorReceipt> runDoctorAction(const DeckDoctorRequest& request,
        const std::function<bool()>& cancelled = {}) const;
    // Fixed path on the selected host's authenticated advertised port. Each
    // connection requests a fresh snapshot; Last-Event-ID replay is not used.
    [[nodiscard]] DeckPolarisResult<bool> watchSessionEvents(int advertisedPort,
        const std::function<void()>& refresh, const std::function<bool()>& cancelled,
        std::chrono::milliseconds idleTimeout = std::chrono::milliseconds(15000)) const;
    // A single conditional paired mutation; never retried on a lost response.
    [[nodiscard]] DeckPolarisResult<bool> setLiveTuningEnabled(bool enabled,
        const DeckLiveTuningTelemetry& observed, const std::function<bool()>& cancelled = {}) const;
    [[nodiscard]] DeckPolarisResult<bool> setFixedBitrate(int bitrateKbps,
        const DeckLiveTuningTelemetry& observed, const std::function<bool()>& cancelled = {}) const;
    [[nodiscard]] DeckPolarisResult<DeckHostPower> fetchHostPower() const;
    [[nodiscard]] DeckPolarisResult<DeckHostSettings> fetchHostSettings(const std::function<bool()>& cancelled = {}) const;
    [[nodiscard]] DeckPolarisResult<bool> fetchHostSettingsIdle(const std::function<bool()>& cancelled = {}) const;
    [[nodiscard]] DeckPolarisResult<DeckHostSettings> setHostResumeTimeout(int seconds,
        const std::function<bool()>& cancelled = {}) const;
    [[nodiscard]] DeckPolarisResult<DeckHostSettings> setSessionProfile(const QString& display, int bitrate, bool clear,
        const DeckLiveTuningTelemetry& observed, const std::function<bool()>& cancelled = {}) const;
    [[nodiscard]] DeckPolarisResult<DeckHostSettings> setHostProfile(const QString& display, int bitrate, bool clear,
        const std::function<bool()>& cancelled = {}) const;
    [[nodiscard]] DeckPolarisResult<DeckHostSettings> setHostDefaultMode(const QString& mode,
        const std::function<bool()>& cancelled = {}) const;
    // Fixed endpoint/body, fresh connection, and non-rewindable upload. Never
    // replay a request after a timeout, dropped answer, redirect or auth error.
    [[nodiscard]] DeckPolarisResult<DeckHostSleepReceipt> requestHostSleep(
        const std::function<bool()>& cancelled = {}) const;

    [[nodiscard]] const DeckPolarisEndpoint& endpoint() const {
        return endpoint_;
    }

    [[nodiscard]] DeckPolarisResult<QVariantMap> gameTool(const QString& game, const QString& action,
        const QVariantMap& values, const std::function<bool()>& cancelled = {}) const;
private:
    struct Session;
    DeckPolarisResult<std::string> request(const std::string& path, std::size_t maxBodyBytes,
        bool post, const std::function<bool()>& cancelled = {}, std::string postBody = "{}", bool remove = false) const;

    DeckPolarisEndpoint endpoint_;
    DeckPolarisTlsIdentity identity_;
    std::chrono::milliseconds timeout_;
    std::shared_ptr<Session> session_;
};

} // namespace nova::deck::polaris
