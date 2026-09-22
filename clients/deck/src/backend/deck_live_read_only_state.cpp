#include "backend/deck_live_read_only_state.h"
#include "stream/deck_gamestream_library.h"

#include <future>
#include <optional>
#include <sstream>
#include <utility>

namespace nova::deck::backend {

namespace {

using polaris::DeckPolarisRequestStatus;

/// Why a host cannot be asked anything, or nullopt when it can.
std::optional<std::string> reasonNotProbeable(const identity::DeckMoonlightIdentity& identity, const identity::DeckMoonlightHostRecord& host) {
    if (!identity.hasClientIdentity()) {
        return identity.sourceLabel == "nova-native" ? "Nova has no usable client certificate on this device" : "Moonlight has no client certificate on this device";
    }
    if (!host.hasServerCertificate()) {
        return identity.sourceLabel == "nova-native" ? "Nova has not pinned this host's certificate" : "Moonlight has not pinned this host's certificate";
    }
    return std::nullopt;
}

struct ParallelProbeResults {
    /// One slot per identity host, in Moonlight's order; nullopt where the host was not askable.
    std::vector<std::optional<DeckLivePolarisFetch>> fetches;
    /// The host that was asked for its library in the first round, if any.
    std::optional<std::size_t> libraryAskedIndex;
};

/// Ask every askable host at once. Each probe blocks on its own nested event
/// loop with per-request timeouts, so on one thread N paired hosts cost N
/// timeouts in a row before the window can appear (a Deck away from home hits
/// this on every LAN address Moonlight remembered); on N threads they cost the
/// slowest one. The first askable host in Moonlight's order is the likely
/// library source, so it is asked for the library in the same round trip.
ParallelProbeResults probeHostsInParallel(const identity::DeckMoonlightIdentity& identity, const DeckLivePolarisFetcher& fetcher) {
    ParallelProbeResults results;
    results.fetches.resize(identity.hosts.size());
    std::vector<std::pair<std::size_t, std::future<DeckLivePolarisFetch>>> pending;
    for (std::size_t index = 0; index < identity.hosts.size(); ++index) {
        const auto& host = identity.hosts[index];
        if (reasonNotProbeable(identity, host)) {
            continue;
        }
        const bool wantLibrary = !results.libraryAskedIndex.has_value();
        if (wantLibrary) {
            results.libraryAskedIndex = index;
        }
        pending.emplace_back(index, std::async(std::launch::async, [&fetcher, &host, wantLibrary] {
            return fetcher(host, wantLibrary);
        }));
    }
    for (auto& [index, future] : pending) {
        results.fetches[index] = future.get();
    }
    return results;
}

std::string hostStatusLabel(const DeckLiveHostProbe& probe, bool native) {
    const std::string owner = native ? "Nova" : "Moonlight";
    switch (probe.status) {
    case DeckPolarisRequestStatus::Ok:
        if (probe.standardHost) return "GameStream host · paired through " + owner;
        return "Polaris " + (probe.serverVersion.empty() ? std::string{"host"} : probe.serverVersion) + " · paired through " + owner;
    case DeckPolarisRequestStatus::Unauthorized:
        return "Paired in " + owner + ", but the host rejected this client";
    case DeckPolarisRequestStatus::CertMismatch:
        return "Host certificate changed since " + owner + " paired";
    case DeckPolarisRequestStatus::InvalidIdentity:
        return owner + " pairing incomplete on this device";
    case DeckPolarisRequestStatus::MalformedBody:
    case DeckPolarisRequestStatus::HttpError:
        return "Host answered, but its library could not be read";
    case DeckPolarisRequestStatus::Timeout:
    case DeckPolarisRequestStatus::Unreachable:
        return native ? "Offline right now · check your PC" : "Offline right now · showing Moonlight's cached apps";
    }
    return "Unknown host state";
}

std::string hostSubtitle(const DeckLiveHostProbe& probe, bool native) {
    if (probe.status == DeckPolarisRequestStatus::Ok) {
        if (probe.standardHost) return std::to_string(probe.gameCount) + " apps from the PC's live GameStream list.";
        return std::to_string(probe.gameCount) + " games from the Polaris library.";
    }
    if (probe.spacesSupported) return "Check this PC's destination before loading games.";
    if (probe.cachedAppCount > 0) {
        return std::to_string(probe.cachedAppCount) + " apps remembered by Moonlight. Reconnect to load the Polaris library.";
    }
    return native ? "Connect to your PC to load its library." : "Nothing cached yet. Open this host in Moonlight once, then come back.";
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
    entry.platformLabel = game.platformLabel;
    entry.runtimeLabel = game.runtimeLabel;
    entry.steamAppid = game.steamAppid;
    entry.category = game.category;
    entry.installed = game.installed;
    entry.coverUrl = game.coverUrl;
    entry.artwork = game.artwork;
    entry.launchPolicy = game.launchPolicy;
    entry.streamCapabilities = game.streamCapabilities;
    entry.displayPlanner = game.displayPlanner;
    entry.genres = game.genres;
    entry.lastLaunched = game.lastLaunched;
    entry.gameTime = game.gameTime;
    entry.spaceId = game.spaceId; entry.spaceName = game.spaceName;
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
    const bool native = identity.sourceLabel == "nova-native";
    snapshot.identityLoaded = identity.loaded;
    snapshot.clientIdentityUsable = identity.hasClientIdentity();
    snapshot.identitySourceLabel = identity.sourceLabel;
    snapshot.clientFingerprintShort = identity::shortFingerprint(identity.clientCertificateFingerprintSha256());
    snapshot.library.readOnly = true;
    snapshot.library.sourceLabel = native ? "No Nova pairing found on this device" : "No Moonlight pairing found on this device";

    if (!identity.loaded) {
        return snapshot;
    }

    auto probed = probeHostsInParallel(identity, fetcher);

    // Keep Polaris precedence across hosts, then try live standard libraries.
    // An empty successful library is authoritative, even when a cache exists.
    std::optional<std::size_t> libraryIndex;
    std::vector<bool> libraryLoaded(identity.hosts.size(), false);
    if (probed.libraryAskedIndex) libraryLoaded[*probed.libraryAskedIndex] = true;
    for (bool standardHost : {false, true}) {
        for (std::size_t index = 0; index < probed.fetches.size() && !libraryIndex; ++index) {
            auto& fetch = probed.fetches[index];
            if (!fetch || fetch->status != DeckPolarisRequestStatus::Ok || fetch->standardHost != standardHost) continue;
            if (!libraryLoaded[index]) {
                fetch = fetcher(identity.hosts[index], true);
                libraryLoaded[index] = true;
            }
            if (fetch->status == DeckPolarisRequestStatus::Ok && fetch->standardHost == standardHost) libraryIndex = index;
        }
    }

    for (std::size_t index = 0; index < identity.hosts.size(); ++index) {
        const auto& host = identity.hosts[index];
        DeckLiveHostProbe probe;
        probe.hostId = host.stableId();
        probe.displayName = host.displayName();
        probe.cachedAppCount = static_cast<int>(host.apps.size());

        if (const auto reason = reasonNotProbeable(identity, host)) {
            probe.status = DeckPolarisRequestStatus::InvalidIdentity;
            probe.detail = *reason;
        } else {
            auto& fetch = *probed.fetches[index];
            probe.status = fetch.status;
            probe.detail = std::move(fetch.detail);
            probe.serverVersion = std::move(fetch.serverVersion);
            probe.resolvedHttpsPort = fetch.httpsPort;
            probe.standardHost = fetch.standardHost;
            probe.spacesSupported = fetch.spacesSupported; probe.spaces = fetch.spaces;
            if (probe.status == DeckPolarisRequestStatus::Ok) {
                probe.librarySource = fetch.standardHost ? "gamestream-live" : "polaris-live";
                if (libraryIndex == index) {
                    probe.gameCount = static_cast<int>(fetch.games.size());
                    snapshot.library.games.clear();
                    for (const auto& game : fetch.games) {
                        snapshot.library.games.push_back(toLibraryGame(game));
                    }
                    snapshot.library.sourceLabel = (fetch.standardHost ? "GameStream library · " : "Polaris library · ") + probe.displayName;
                    snapshot.selectedHostId = probe.hostId;
                }
            }
        }

        if (probe.status != DeckPolarisRequestStatus::Ok) {
            probe.librarySource = probe.cachedAppCount > 0 && !probe.spacesSupported ? "moonlight-cached-app-list" : "none";
        }

        snapshot.hosts.push_back(DeckHostSummary{
            .id = probe.hostId,
            .displayName = probe.displayName,
            .state = hostState(probe),
            .endpointClass = endpointClass(host),
            .fixtureOnly = false,
            .hasEndpointCandidate = !host.preferredAddress().empty(),
            .polarisAvailable = probe.status == DeckPolarisRequestStatus::Ok && !probe.standardHost,
            .standardAppListAvailable = probe.status == DeckPolarisRequestStatus::Ok || (probe.cachedAppCount > 0 && !probe.spacesSupported),
            .standardLibraryAvailable = probe.status == DeckPolarisRequestStatus::Ok && probe.standardHost,
            .publicStatusLabel = hostStatusLabel(probe, native),
            .publicSubtitle = hostSubtitle(probe, native),
            .publicProvenanceLabel = (native ? "nova-pairing/" : "moonlight-pairing/") + identity.sourceLabel + "/redacted-public",
        });
        snapshot.probes.push_back(std::move(probe));
    }

    // Nobody answered as Polaris: the first host with a visible cached
    // Moonlight app stands in, else the first host with any cached app.
    if (!libraryIndex) {
        std::optional<std::size_t> cachedIndex;
        for (std::size_t index = 0; index < identity.hosts.size() && !cachedIndex; ++index) {
            // A legacy cache has no Desktop/Space identity. Never substitute
            // it when an advertised destination route failed to authorize one.
            if (snapshot.probes[index].spacesSupported) continue;
            for (const auto& app : identity.hosts[index].apps) {
                if (!app.hidden) {
                    cachedIndex = index;
                    break;
                }
            }
        }
        for (std::size_t index = 0; index < identity.hosts.size() && !cachedIndex; ++index) {
            if (snapshot.probes[index].spacesSupported) continue;
            if (!identity.hosts[index].apps.empty()) {
                cachedIndex = index;
            }
        }
        if (cachedIndex) {
            for (const auto& app : identity.hosts[*cachedIndex].apps) {
                if (!app.hidden) {
                    snapshot.library.games.push_back(toLibraryGame(app));
                }
            }
            snapshot.library.sourceLabel = "Moonlight's cached apps · " + snapshot.probes[*cachedIndex].displayName;
            snapshot.selectedHostId = snapshot.probes[*cachedIndex].hostId;
        }
        if (snapshot.selectedHostId.empty()) for (const auto& probe : snapshot.probes) {
            if (!probe.spacesSupported) continue;
            snapshot.selectedHostId = probe.hostId;
            snapshot.library.sourceLabel = "Game list unavailable";
            break;
        }
    }

    if (identity.hosts.empty()) {
        snapshot.library.sourceLabel = native ? "Nova has no paired host yet" : "Moonlight is installed but has no paired host yet";
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
        // Moonlight learns the HTTPS port from serverinfo on every connection
        // because forwarded hosts do not keep the five-port spacing.
        const auto httpPort = host.preferredHttpPort();
        const auto serverInfo = polaris::probeServerInfoHttpsPort(host.preferredAddress(), httpPort, timeout);
        fetch.httpsPort = serverInfo.httpsPort.value_or(host.nativeHttpsPort > 0 ? host.nativeHttpsPort
            : identity::polarisHttpsPortForMoonlightHttpPort(httpPort));
        // HTTP and HTTPS can have different firewall policies. Even after an
        // HTTP timeout, try the paired HTTPS fallback with certificate pinning.
        const auto client = polarisClientForHost(identity, host, fetch.httpsPort, timeout);
        const auto capabilities = client.fetchCapabilities();
        fetch.status = capabilities.status;
        fetch.detail = capabilities.detail;
        if (!capabilities.ok()) {
            // Only an absent API admits the standard path. Authentication,
            // certificate, malformed-body, timeout and server errors stay visible.
            if (capabilities.status != DeckPolarisRequestStatus::HttpError ||
                (capabilities.httpStatus != 404 && capabilities.httpStatus != 501)) return fetch;
            fetch.standardHost = true;
            const auto clientId = identity.clientCertificateFingerprintSha256();
            auto info = client.get(stream::standardHostTarget("/serverinfo", clientId), stream::kStandardServerInfoLimit);
            if (!info.ok()) { fetch.status = info.status; fetch.detail = info.detail; return fetch; }
            const auto verified = stream::parseStandardHostInfo(*info.value);
            if (!verified.ok()) { fetch.status = verified.status; fetch.detail = verified.detail; return fetch; }
            if (verified.value->id != host.uuid || !verified.value->paired) {
                fetch.status = DeckPolarisRequestStatus::Unauthorized;
                fetch.detail = "the pinned host did not confirm this saved pairing";
                return fetch;
            }
            fetch.status = DeckPolarisRequestStatus::Ok;
            fetch.detail.clear();
            if (!wantLibrary) return fetch;
            auto reply = client.get(stream::standardHostTarget("/applist", clientId), stream::kStandardAppListLimit);
            if (!reply.ok()) { fetch.status = reply.status; fetch.detail = reply.detail; return fetch; }
            const auto apps = stream::parseStandardAppList(*reply.value);
            fetch.status = apps.status;
            fetch.detail = apps.detail;
            if (apps.ok()) for (const auto& app : *apps.value) {
                polaris::DeckPolarisGame game;
                game.id = "gamestream-app-" + std::to_string(app.id);
                game.appId = app.id;
                game.name = app.title;
                game.source = "gamestream";
                game.hdrSupported = app.hdrSupported;
                game.streamCapabilities = verified.value->streamCapabilities;
                fetch.games.push_back(std::move(game));
            }
            return fetch;
        }
        fetch.serverVersion = capabilities.value->version;
        if (!wantLibrary) {
            return fetch;
        }
        fetch.spacesSupported = capabilities.value->spaces;
        if (fetch.spacesSupported) {
            const auto spaces = client.fetchSpaces();
            if (spaces.ok()) fetch.spaces = *spaces.value;
            else if (spaces.status == DeckPolarisRequestStatus::HttpError && spaces.httpStatus == 404)
                fetch.spacesSupported = false; // Only an absent endpoint admits the legacy route.
            else { fetch.status = spaces.status; fetch.detail = spaces.detail; return fetch; }
        }
        if (fetch.spaces && fetch.spaces->enabled && !fetch.spaces->available) return fetch;
        const bool scoped = fetch.spaces && fetch.spaces->enabled;
        const auto* selectedSpace = scoped ? fetch.spaces->selected() : nullptr;
        auto games = selectedSpace && selectedSpace->libraryEnabled
            ? client.fetchSpaceLibrary(selectedSpace->id)
            : client.fetchAllGames(100, {}, scoped && fetch.spaces->selectedId == "desktop");
        fetch.status = games.status;
        if (!games.ok()) {
            fetch.detail = games.detail;
            return fetch;
        }
        fetch.games = std::move(*games.value);
        // A Space identity must agree with the independently authenticated choice.
        for (const auto& game : fetch.games) {
            const bool spaceGame = polaris::isSpaceGame(game.id);
            if ((spaceGame && (!selectedSpace || (!game.spaceId.empty() && game.spaceId != selectedSpace->id))) ||
                (selectedSpace && !spaceGame)) {
                fetch.status = DeckPolarisRequestStatus::MalformedBody;
                fetch.detail = "library destination changed"; fetch.games.clear(); return fetch;
            }
        }
        for (auto& game : fetch.games) game.streamCapabilities = capabilities.value->streamCapabilities;
        if (capabilities.value->clientSettings) {
            const auto reply = client.get("/polaris/v1/client-settings", 128 * 1024);
            if (reply.status == DeckPolarisRequestStatus::Unauthorized || reply.status == DeckPolarisRequestStatus::CertMismatch ||
                reply.status == DeckPolarisRequestStatus::InvalidIdentity) {
                fetch.status = reply.status;
                fetch.detail = reply.detail;
                fetch.games.clear();
                return fetch;
            }
            if (reply.ok()) if (const auto catalog = polaris::parseLaunchModeCatalog(*reply.value))
                for (auto& game : fetch.games) game.launchPolicy = polaris::launchModePolicy(game, *catalog);
        }
        if (scoped) {
            const auto after = client.fetchSpaces();
            if (!after.ok() || !after.value->enabled || !after.value->available ||
                after.value->selectedId != fetch.spaces->selectedId) {
                fetch.status = after.ok() ? DeckPolarisRequestStatus::MalformedBody : after.status;
                fetch.detail = "destination could not be verified after reading games";
                fetch.games.clear(); return fetch;
            }
            fetch.spaces = *after.value;
        }
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
    const bool native = snapshot.identitySourceLabel == "nova-native";
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
            .sourceTag = native ? "nova-pairing-live-read-only" : "moonlight-pairing-live-read-only",
        });
    state_.scenarioId = std::string(kScenarioId);
    if (!snapshot.identityLoaded) {
        state_.scenarioLabel = native ? "Nova not paired on this device" : "Moonlight not paired on this device";
    } else if (snapshot.hosts.empty()) {
        state_.scenarioLabel = native ? "Nova has no paired host" : "Moonlight has no paired host";
    } else {
        int online = 0, standard = 0;
        for (const auto& probe : snapshot.probes) {
            if (probe.status == DeckPolarisRequestStatus::Ok) {
                ++online;
                if (probe.standardHost) ++standard;
            }
        }
        state_.scenarioLabel = online > 0
            ? "Live · " + std::to_string(online) + (standard ? " streaming host" : " Polaris host") + (online == 1 ? "" : "s") + " reachable"
            : native ? "Offline · reconnect to your PC" : "Offline · Moonlight's cached apps";
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
