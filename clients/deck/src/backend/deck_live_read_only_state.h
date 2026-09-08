#pragma once

#include "backend/deck_backend_interfaces.h"
#include "identity/deck_moonlight_identity.h"
#include "polaris/deck_polaris_client.h"
#include "polaris_game_fixture.h"

#include <chrono>
#include <functional>
#include <string>
#include <vector>

// The live read-only route: hosts come from Moonlight-Qt's pairing file, the
// library comes from Polaris over the pinned mTLS client, and when Polaris is
// not reachable the cached Moonlight app list stands in. Everything public
// stays a sanitized DTO; addresses, certificates and keys never leave the
// snapshot builder.
namespace nova::deck::backend {

struct DeckLivePolarisFetch {
    polaris::DeckPolarisRequestStatus status = polaris::DeckPolarisRequestStatus::Unreachable;
    std::string detail;
    std::string serverVersion;
    int httpsPort = 0;
    std::vector<polaris::DeckPolarisGame> games;
};

/// Probe one host. `wantLibrary` is true only for the host expected to supply the library; the others are asked
/// for capabilities alone. Called concurrently from one thread per host, so it must not share mutable state.
using DeckLivePolarisFetcher = std::function<DeckLivePolarisFetch(const identity::DeckMoonlightHostRecord& host, bool wantLibrary)>;

struct DeckLiveHostProbe {
    std::string hostId;
    std::string displayName;
    polaris::DeckPolarisRequestStatus status = polaris::DeckPolarisRequestStatus::Unreachable;
    std::string detail;
    std::string serverVersion;
    std::string librarySource;  ///< polaris-live, moonlight-cached-app-list, or none
    int gameCount = 0;
    int cachedAppCount = 0;
    int resolvedHttpsPort = 0;  ///< the port Polaris was actually reached on (serverinfo first, then the five-port rule)
};

struct DeckLiveHostLibrarySnapshot {
    bool identityLoaded = false;
    bool clientIdentityUsable = false;
    std::string identitySourceLabel;
    std::string clientFingerprintShort;
    std::string selectedHostId;
    std::vector<DeckHostSummary> hosts;
    std::vector<DeckLiveHostProbe> probes;
    PolarisGameLibraryFixture library;
};

/// Probe every paired host through @p fetcher and build the sanitized snapshot. Pure apart from the fetcher.
/// Hosts are probed concurrently (the fetcher is called from one thread per host), so the wait is the
/// slowest host's, not the sum. The first reachable Polaris host in Moonlight's order supplies the
/// library and becomes the selected host; other hosts are only asked for capabilities.
DeckLiveHostLibrarySnapshot buildLiveSnapshot(const identity::DeckMoonlightIdentity& identity, const DeckLivePolarisFetcher& fetcher);

/// Hosts in presentation order: the selected host first, then Moonlight's order.
std::vector<DeckHostSummary> hostsSelectedFirst(const DeckLiveHostLibrarySnapshot& snapshot);

/// The credential facts the preflight should judge for the selected host.
DeckCredentialMetadata credentialsForSelectedHost(const DeckLiveHostLibrarySnapshot& snapshot);

/// One word for the header: where this read-only state came from.
inline constexpr std::string_view kLiveProvenanceLabel = "moonlight-pairing provenance";

/// The HTTPS port for a host: what its serverinfo advertises, else the five-port rule.
int resolvePolarisHttpsPort(const identity::DeckMoonlightHostRecord& host, std::chrono::milliseconds timeout);

/// A client for one host over the identity's certificate and that host's pinned server certificate, on the given port.
polaris::DeckPolarisClient polarisClientForHost(
    const identity::DeckMoonlightIdentity& identity,
    const identity::DeckMoonlightHostRecord& host,
    int httpsPort,
    std::chrono::milliseconds timeout);

/// A fetcher that talks to Polaris with the identity's certificate and the host's pinned server certificate.
DeckLivePolarisFetcher polarisNetworkFetcher(const identity::DeckMoonlightIdentity& identity, std::chrono::milliseconds timeout);

/// Load the default Moonlight identity and probe its hosts over the network. Never throws; an absent identity yields an empty snapshot.
DeckLiveHostLibrarySnapshot buildLiveSnapshotFromDefaultIdentity(std::chrono::milliseconds timeout);

/// Sanitized multi-line summary for the terminal: no addresses, ports, certificates or keys.
std::string describeLiveSnapshotForTerminal(const DeckLiveHostLibrarySnapshot& snapshot);

/// Map a Polaris game onto the fixture-shaped library entry the shell already renders.
PolarisGameFixture toLibraryGame(const polaris::DeckPolarisGame& game);

/// Map a cached Moonlight app onto a library entry when Polaris cannot be asked.
PolarisGameFixture toLibraryGame(const identity::DeckMoonlightAppRecord& app);

class DeckLiveReadOnlyStateProvider final : public DeckReadOnlyStateProvider {
public:
    DeckLiveReadOnlyStateProvider(DeckLiveHostLibrarySnapshot snapshot, const DeckLaunchPreflightService& preflightService);

    [[nodiscard]] std::vector<DeckPublicReadOnlyHostLibraryState> stateMatrix() const override;
    [[nodiscard]] DeckPublicReadOnlyHostLibraryState stateForScenario(std::string_view scenarioId) const override;

    static constexpr std::string_view kScenarioId = "live";

private:
    DeckPublicReadOnlyHostLibraryState state_;
};

} // namespace nova::deck::backend
