#pragma once

#include <chrono>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

// A read-only client for Polaris's /polaris/v1 API on the GameStream HTTPS
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

struct DeckPolarisCapabilities {
    std::string server;
    std::string version;
    bool gameLibrary = false;
    bool sessionLifecycle = false;
    bool clientSettings = false;
    bool resolvedProfileProvenance = false;
    bool expectedTopologyAssertion = false;
    std::string captureBackend;
    std::vector<std::string> codecs;
};

struct DeckPolarisGame {
    std::string id;
    int appId = 0;
    std::string name;
    std::string source;
    std::string platform;
    std::string runtime;
    std::string steamAppid;
    std::string category;
    std::string coverUrl;
    bool installed = true;
    bool hdrSupported = false;
    long long lastLaunched = 0;
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
std::optional<DeckPolarisSessionStatus> parseSessionStatus(std::string_view json);

/// The HttpsPort a GameStream host advertises in its plain-HTTP serverinfo XML.
std::optional<int> parseServerInfoHttpsPort(std::string_view xml);

/// Ask the host's plain-HTTP port which HTTPS port to use, the way Moonlight does
/// before every connection; nullopt when the host does not answer or advertise one.
std::optional<int> resolveHttpsPortFromServerInfo(const std::string& address, int httpPort, std::chrono::milliseconds timeout);

class DeckPolarisClient {
public:
    DeckPolarisClient(
        DeckPolarisEndpoint endpoint,
        DeckPolarisTlsIdentity identity,
        std::chrono::milliseconds timeout = std::chrono::milliseconds(4000));

    [[nodiscard]] DeckPolarisResult<std::string> get(const std::string& path) const;
    [[nodiscard]] DeckPolarisResult<DeckPolarisCapabilities> fetchCapabilities() const;
    [[nodiscard]] DeckPolarisResult<DeckPolarisGamesPage> fetchGamesPage(int limit, int offset) const;
    [[nodiscard]] DeckPolarisResult<std::vector<DeckPolarisGame>> fetchAllGames(int pageSize = 100) const;
    [[nodiscard]] DeckPolarisResult<DeckPolarisSessionStatus> fetchSessionStatus() const;

    [[nodiscard]] const DeckPolarisEndpoint& endpoint() const {
        return endpoint_;
    }

private:
    struct Session;

    DeckPolarisEndpoint endpoint_;
    DeckPolarisTlsIdentity identity_;
    std::chrono::milliseconds timeout_;
    std::shared_ptr<Session> session_;
};

} // namespace nova::deck::polaris
