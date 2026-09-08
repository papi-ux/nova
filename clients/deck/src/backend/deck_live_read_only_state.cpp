#include "backend/deck_live_read_only_state.h"

#include <sstream>
#include <utility>

namespace nova::deck::backend {

namespace {

using polaris::DeckPolarisRequestStatus;

std::string hostStatusLabel(const DeckLiveHostProbe& probe) {
    switch (probe.status) {
    case DeckPolarisRequestStatus::Ok:
        return "Polaris " + (probe.serverVersion.empty() ? std::string{"host"} : probe.serverVersion) + " · paired through Moonlight";
    case DeckPolarisRequestStatus::Unauthorized:
        return "Paired in Moonlight, but the host rejected this client";
    case DeckPolarisRequestStatus::CertMismatch:
        return "Host certificate changed since Moonlight paired";
    case DeckPolarisRequestStatus::InvalidIdentity:
        return "Moonlight pairing incomplete on this device";
    case DeckPolarisRequestStatus::MalformedBody:
    case DeckPolarisRequestStatus::HttpError:
        return "Host answered, but not as a Polaris host";
    case DeckPolarisRequestStatus::Timeout:
    case DeckPolarisRequestStatus::Unreachable:
        return "Offline right now · showing Moonlight's cached apps";
    }
    return "Unknown host state";
}

std::string hostSubtitle(const DeckLiveHostProbe& probe) {
    if (probe.status == DeckPolarisRequestStatus::Ok) {
        return std::to_string(probe.gameCount) + " games from the Polaris library.";
    }
    if (probe.cachedAppCount > 0) {
        return std::to_string(probe.cachedAppCount) + " apps remembered by Moonlight. Reconnect to load the Polaris library.";
    }
    return "Nothing cached yet. Open this host in Moonlight once, then come back.";
}

DeckHostState hostState(const DeckLiveHostProbe& probe) {
    switch (probe.status) {
    case DeckPolarisRequestStatus::Ok:
    case DeckPolarisRequestStatus::HttpError:
    case DeckPolarisRequestStatus::MalformedBody:
        return DeckHostState::Online;
    case DeckPolarisRequestStatus::Unauthorized:
        return DeckHostState::AuthRejected;
    case DeckPolarisRequestStatus::CertMismatch:
        return DeckHostState::CertMismatch;
    case DeckPolarisRequestStatus::InvalidIdentity:
        return DeckHostState::PairingNeeded;
    case DeckPolarisRequestStatus::Timeout:
    case DeckPolarisRequestStatus::Unreachable:
        return DeckHostState::Offline;
    }
    return DeckHostState::Unsupported;
}

DeckEndpointClass endpointClass(const identity::DeckMoonlightHostRecord& host) {
    if (!host.manualAddress.empty()) {
        return DeckEndpointClass::Manual;
    }
    if (!host.localAddress.empty() || !host.remoteAddress.empty() || !host.ipv6Address.empty()) {
        return DeckEndpointClass::Discovered;
    }
    return DeckEndpointClass::Unknown;
}

} // namespace

PolarisGameFixture toLibraryGame(const polaris::DeckPolarisGame& game) {
    PolarisGameFixture entry;
    entry.id = game.id;
    entry.appId = game.appId;
    entry.name = game.name;
    entry.source = game.source;
    entry.launcherSource = game.source;
    entry.platform = game.platform;
    entry.runtime = game.runtime;
    entry.steamAppid = game.steamAppid;
    entry.category = game.category;
    entry.installed = game.installed;
    entry.coverUrl = game.coverUrl;
    entry.genres = game.genres;
    entry.lastLaunched = game.lastLaunched;
    entry.hdrSupported = game.hdrSupported;
    entry.launchMode.preferredMode = game.launchPreferredMode;
    entry.launchMode.recommendedMode = game.launchRecommendedMode;
    entry.launchMode.allowedModes = game.launchAllowedModes;
    entry.launchMode.modeReason = game.launchModeReason;
    entry.steamLaunch.available = game.steamLaunchAvailable;
    entry.steamLaunch.mode = game.steamLaunchMode;
    entry.steamLaunch.recommendedMode = game.steamLaunchRecommendedMode;
    entry.steamLaunch.allowedModes = game.steamLaunchAllowedModes;
    entry.steamLaunch.modeReason = game.steamLaunchModeReason;
    return entry;
}

PolarisGameFixture toLibraryGame(const identity::DeckMoonlightAppRecord& app) {
    PolarisGameFixture entry;
    entry.id = "moonlight-app-" + std::to_string(app.id);
    entry.appId = app.id;
    entry.name = app.name;
    entry.source = "moonlight";
    entry.launcherSource = "moonlight";
    entry.launcherDetail = "cached";
    entry.installed = true;
    entry.hdrSupported = app.hdr;
    return entry;
}

DeckLiveHostLibrarySnapshot buildLiveSnapshot(const identity::DeckMoonlightIdentity& identity, const DeckLivePolarisFetcher& fetcher) {
    DeckLiveHostLibrarySnapshot snapshot;
    snapshot.identityLoaded = identity.loaded;
    snapshot.clientIdentityUsable = identity.hasClientIdentity();
    snapshot.identitySourceLabel = identity.sourceLabel;
    snapshot.clientFingerprintShort = identity::shortFingerprint(identity.clientCertificateFingerprintSha256());
    snapshot.library.readOnly = true;
    snapshot.library.sourceLabel = "No Moonlight pairing found on this device";

    if (!identity.loaded) {
        return snapshot;
    }

    bool librarySelected = false;
    for (const auto& host : identity.hosts) {
        DeckLiveHostProbe probe;
        probe.hostId = host.stableId();
        probe.displayName = host.displayName();
        probe.cachedAppCount = static_cast<int>(host.apps.size());

        if (!identity.hasClientIdentity()) {
            probe.status = DeckPolarisRequestStatus::InvalidIdentity;
            probe.detail = "Moonlight has no client certificate on this device";
        } else if (!host.hasServerCertificate()) {
            probe.status = DeckPolarisRequestStatus::InvalidIdentity;
            probe.detail = "Moonlight has not pinned this host's certificate";
        } else {
            auto fetch = fetcher(host, !librarySelected);
            probe.status = fetch.status;
            probe.detail = std::move(fetch.detail);
            probe.serverVersion = std::move(fetch.serverVersion);
            probe.resolvedHttpsPort = fetch.httpsPort;
            if (probe.status == DeckPolarisRequestStatus::Ok) {
                probe.librarySource = "polaris-live";
                if (!librarySelected) {
                    probe.gameCount = static_cast<int>(fetch.games.size());
                    snapshot.library.games.clear();
                    for (const auto& game : fetch.games) {
                        snapshot.library.games.push_back(toLibraryGame(game));
                    }
                    snapshot.library.sourceLabel = "Polaris library · " + probe.displayName;
                    snapshot.selectedHostId = probe.hostId;
                    librarySelected = true;
                }
            }
        }

        if (probe.status != DeckPolarisRequestStatus::Ok) {
            probe.librarySource = probe.cachedAppCount > 0 ? "moonlight-cached-app-list" : "none";
            if (!librarySelected && probe.cachedAppCount > 0 && snapshot.library.games.empty()) {
                for (const auto& app : host.apps) {
                    if (!app.hidden) {
                        snapshot.library.games.push_back(toLibraryGame(app));
                    }
                }
                snapshot.library.sourceLabel = "Moonlight's cached apps · " + probe.displayName;
                snapshot.selectedHostId = probe.hostId;
            }
        }

        snapshot.hosts.push_back(DeckHostSummary{
            .id = probe.hostId,
            .displayName = probe.displayName,
            .state = hostState(probe),
            .endpointClass = endpointClass(host),
            .fixtureOnly = false,
            .hasEndpointCandidate = !host.preferredAddress().empty(),
            .polarisAvailable = probe.status == DeckPolarisRequestStatus::Ok,
            .standardAppListAvailable = probe.status == DeckPolarisRequestStatus::Ok || probe.cachedAppCount > 0,
            .publicStatusLabel = hostStatusLabel(probe),
            .publicSubtitle = hostSubtitle(probe),
            .publicProvenanceLabel = "moonlight-pairing/" + identity.sourceLabel + "/redacted-public",
        });
        snapshot.probes.push_back(std::move(probe));
    }

    if (identity.hosts.empty()) {
        snapshot.library.sourceLabel = "Moonlight is installed but has no paired host yet";
    }
    return snapshot;
}

std::vector<DeckHostSummary> hostsSelectedFirst(const DeckLiveHostLibrarySnapshot& snapshot) {
    std::vector<DeckHostSummary> ordered;
    ordered.reserve(snapshot.hosts.size());
    for (const auto& host : snapshot.hosts) {
        if (host.id == snapshot.selectedHostId) {
            ordered.push_back(host);
        }
    }
    for (const auto& host : snapshot.hosts) {
        if (host.id != snapshot.selectedHostId) {
            ordered.push_back(host);
        }
    }
    return ordered;
}

DeckCredentialMetadata credentialsForSelectedHost(const DeckLiveHostLibrarySnapshot& snapshot) {
    DeckCredentialMetadata credentials;
    const auto ordered = hostsSelectedFirst(snapshot);
    if (ordered.empty()) {
        return credentials;
    }
    credentials.hostId = ordered.front().id;
    for (const auto& probe : snapshot.probes) {
        if (probe.hostId != credentials.hostId) {
            continue;
        }
        credentials.paired = snapshot.clientIdentityUsable && probe.status != DeckPolarisRequestStatus::InvalidIdentity;
        credentials.certMismatch = probe.status == DeckPolarisRequestStatus::CertMismatch;
        credentials.authRejected = probe.status == DeckPolarisRequestStatus::Unauthorized;
        credentials.pinnedCertFingerprint = snapshot.clientFingerprintShort;
        break;
    }
    return credentials;
}

int resolvePolarisHttpsPort(const identity::DeckMoonlightHostRecord& host, const std::chrono::milliseconds timeout) {
    const auto httpPort = host.preferredHttpPort();
    // Moonlight learns the HTTPS port from serverinfo on every connection
    // because forwarded hosts do not keep the five-port spacing.
    return polaris::resolveHttpsPortFromServerInfo(host.preferredAddress(), httpPort, timeout)
        .value_or(identity::polarisHttpsPortForMoonlightHttpPort(httpPort));
}

polaris::DeckPolarisClient polarisClientForHost(
    const identity::DeckMoonlightIdentity& identity,
    const identity::DeckMoonlightHostRecord& host,
    const int httpsPort,
    const std::chrono::milliseconds timeout) {
    return polaris::DeckPolarisClient(
        polaris::DeckPolarisEndpoint{.address = host.preferredAddress(), .httpsPort = httpsPort},
        polaris::DeckPolarisTlsIdentity{
            .clientCertificatePem = identity.clientCertificatePem,
            .clientPrivateKeyPem = identity.clientPrivateKeyPemForBackendOnly,
            .pinnedServerCertificatePem = host.serverCertificatePem,
        },
        timeout);
}

DeckLivePolarisFetcher polarisNetworkFetcher(const identity::DeckMoonlightIdentity& identity, const std::chrono::milliseconds timeout) {
    return [&identity, timeout](const identity::DeckMoonlightHostRecord& host, const bool wantLibrary) {
        DeckLivePolarisFetch fetch;
        fetch.httpsPort = resolvePolarisHttpsPort(host, timeout);
        const auto client = polarisClientForHost(identity, host, fetch.httpsPort, timeout);
        const auto capabilities = client.fetchCapabilities();
        fetch.status = capabilities.status;
        fetch.detail = capabilities.detail;
        if (!capabilities.ok()) {
            return fetch;
        }
        fetch.serverVersion = capabilities.value->version;
        if (!wantLibrary) {
            return fetch;
        }
        auto games = client.fetchAllGames();
        fetch.status = games.status;
        if (!games.ok()) {
            fetch.detail = games.detail;
            return fetch;
        }
        fetch.games = std::move(*games.value);
        return fetch;
    };
}

DeckLiveHostLibrarySnapshot buildLiveSnapshotFromDefaultIdentity(const std::chrono::milliseconds timeout) {
    const auto identity = identity::loadDefaultMoonlightIdentity();
    if (!identity) {
        return buildLiveSnapshot(identity::DeckMoonlightIdentity{}, [](const identity::DeckMoonlightHostRecord&, bool) {
            return DeckLivePolarisFetch{};
        });
    }
    const auto fetcher = polarisNetworkFetcher(*identity, timeout);
    return buildLiveSnapshot(*identity, fetcher);
}

std::string describeLiveSnapshotForTerminal(const DeckLiveHostLibrarySnapshot& snapshot) {
    std::ostringstream out;
    out << "nova-deck live snapshot\n";
    out << "  identity: " << (snapshot.identityLoaded ? snapshot.identitySourceLabel : std::string{"none"})
        << " client=" << (snapshot.clientIdentityUsable ? "usable" : "missing")
        << " fingerprint=" << (snapshot.clientFingerprintShort.empty() ? std::string{"-"} : snapshot.clientFingerprintShort) << "\n";
    out << "  hosts: " << snapshot.hosts.size() << "\n";
    for (const auto& probe : snapshot.probes) {
        out << "    - " << probe.displayName << " [" << probe.hostId << "] status=" << polaris::describe(probe.status)
            << " library=" << probe.librarySource << " games=" << probe.gameCount << " cached=" << probe.cachedAppCount;
        if (!probe.serverVersion.empty()) {
            out << " polaris=" << probe.serverVersion;
        }
        if (!probe.detail.empty() && probe.status != DeckPolarisRequestStatus::Ok) {
            out << " detail=\"" << probe.detail << "\"";
        }
        out << "\n";
    }
    out << "  library: " << snapshot.library.sourceLabel << " (" << snapshot.library.games.size() << " entries)\n";
    std::size_t shown = 0;
    for (const auto& game : snapshot.library.games) {
        if (shown++ >= 12) {
            out << "    ...\n";
            break;
        }
        out << "    - " << game.name << " [" << game.source << "]\n";
    }
    return out.str();
}

DeckLiveReadOnlyStateProvider::DeckLiveReadOnlyStateProvider(
    DeckLiveHostLibrarySnapshot snapshot,
    const DeckLaunchPreflightService& preflightService) {
    // The selected host goes first so focus, the preflight and the detail card
    // all describe the host the library came from.
    DeckFakeHostRepository repository;
    for (const auto& host : hostsSelectedFirst(snapshot)) {
        repository.upsertSanitizedHostSummary(host);
    }
    state_ = buildReadOnlyHostLibraryState(
        repository,
        snapshot.library,
        preflightService,
        DeckLabGate::forMode(DeckLabGateMode::ReadOnlyNetwork),
        DeckReadOnlyStateOptions{
            .credentials = credentialsForSelectedHost(snapshot),
            .sourceTag = "moonlight-pairing-live-read-only",
        });
    state_.scenarioId = std::string(kScenarioId);
    if (!snapshot.identityLoaded) {
        state_.scenarioLabel = "Moonlight not paired on this device";
    } else if (snapshot.hosts.empty()) {
        state_.scenarioLabel = "Moonlight has no paired host";
    } else {
        int online = 0;
        for (const auto& probe : snapshot.probes) {
            if (probe.status == DeckPolarisRequestStatus::Ok) {
                ++online;
            }
        }
        state_.scenarioLabel = online > 0
            ? "Live · " + std::to_string(online) + " Polaris host" + (online == 1 ? "" : "s") + " reachable"
            : "Offline · Moonlight's cached apps";
    }
    state_.sourceLabel = "live read-only · " + snapshot.library.sourceLabel;
    state_.preflight.backendPowerStarted = false;
    state_.preflight.streamAllowed = false;
    state_.preflight.publicCopy += "; handoff=not-yet-executable";
    state_.playerState = playerStateFor(state_.preflight, state_.scenarioLabel);
    state_.dtoParity = readOnlyDtoParityFor(state_.preflight, state_.scenarioId, state_.scenarioLabel);
}

std::vector<DeckPublicReadOnlyHostLibraryState> DeckLiveReadOnlyStateProvider::stateMatrix() const {
    return {state_};
}

DeckPublicReadOnlyHostLibraryState DeckLiveReadOnlyStateProvider::stateForScenario(std::string_view) const {
    return state_;
}

} // namespace nova::deck::backend
