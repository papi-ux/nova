#include "runtime/deck_native_target.h"
#include "runtime/deck_doctor_receipts.h"
#include <QSslCertificate>
#include "stream/deck_gamestream_library.h"

#include <algorithm>

namespace nova::deck::runtime {
using namespace stream;
DeckNativeTargetResolver nativeTargetResolver(
    std::optional<identity::DeckMoonlightIdentity> identity,
    std::optional<backend::DeckLiveHostLibrarySnapshot> snapshot) {
    return [identity = std::move(identity), snapshot = std::move(snapshot)]
        (const QString& hostId, const QString& gameId) -> std::optional<DeckNativeLaunchTarget> {
        if (!identity || !snapshot || hostId.toStdString() != snapshot->selectedHostId) return std::nullopt;
        const auto* host = identity->hostById(hostId.toStdString());
        if (!host || !host->hasServerCertificate() || !identity->hasClientIdentity()) return std::nullopt;
        const auto& games = snapshot->library.games;
        const auto game = std::find_if(games.begin(), games.end(), [&](const auto& candidate) {
            return candidate.id == gameId.toStdString();
        });
        if (game == games.end() || game->appId <= 0) return std::nullopt;
        int port = host->nativeHttpsPort > 0 ? host->nativeHttpsPort
            : identity::polarisHttpsPortForMoonlightHttpPort(host->preferredHttpPort());
        bool standardHost = false;
        std::optional<std::string> destination;
        for (const auto& probe : snapshot->probes) {
            if (probe.hostId != host->stableId()) continue;
            if (probe.status == polaris::DeckPolarisRequestStatus::Unauthorized ||
                probe.status == polaris::DeckPolarisRequestStatus::CertMismatch ||
                probe.status == polaris::DeckPolarisRequestStatus::InvalidIdentity) return std::nullopt;
            if (probe.resolvedHttpsPort > 0) port = probe.resolvedHttpsPort;
            standardHost = probe.standardHost;
            if (probe.spacesSupported) {
                if (!probe.spaces || (probe.spaces->enabled && !probe.spaces->available)) return {};
                destination = probe.spaces->enabled ? probe.spaces->selectedId : "desktop";
            }
        }
        const auto space = polaris::spaceGameIdentity(game->id);
        const bool spaceGame = polaris::isSpaceGame(game->id);
        if (game->id.starts_with("space.") && !space) return {};
        if (spaceGame && (!destination || *destination == "desktop" ||
            (space && space->spaceId != *destination) || game->appId != polaris::kSpaceAppId)) return {};
        if (!spaceGame && destination && *destination != "desktop") return {};
        auto client = std::make_shared<polaris::DeckPolarisClient>(
            backend::polarisClientForHost(*identity, *host, port, std::chrono::milliseconds(4000)));
        DeckNativeLaunchTarget target;
        target.request.hostId = host->stableId();
        target.request.gameId = game->id;
        target.appId = game->appId;
        target.streamCapabilities = game->streamCapabilities;
#ifdef NOVA_DECK_NATIVE_MEDIA
        target.probeVideoSupport = detectVideoDecodeSupport;
#endif
        // Cached app ids are local identifiers, not Polaris app UUIDs.
        if (!game->id.starts_with("moonlight-app-") && !standardHost) target.appUuid = game->id;
        target.serverAddress = host->preferredAddress();
        const auto clientId = identity->clientCertificateFingerprintSha256();
        target.fetch = [client, standardHost, clientId, destination](const std::string& path) {
            // Recheck immediately before launch/resume, including default mode.
            // A read-only library snapshot never authorizes a different place.
            if (destination && (path.starts_with("/launch?") || path.starts_with("/resume?"))) {
                const auto fresh = client->fetchSpaces();
                if (!fresh.ok() || !fresh.value->permitsPlay(*destination)) return stream::DeckHttpResponse{};
            }
            return fetcherOverPolarisClient(*client)(standardHost ? standardHostTarget(path, clientId) : path);
        };
        if (!standardHost && !game->id.starts_with("moonlight-app-")) {
            target.automaticReconnect = !spaceGame;
            if (!spaceGame) target.authorizeSetup = [client, gameId](const QString& preset, const QString& encoder, const auto& cancelled) {
                const auto caps = client->fetchCapabilities();
                if (cancelled() || !caps.ok() || (preset != "auto" && !caps.value->resolvedProfileProvenance)) return false;
                const auto settings = client->gameTool(gameId, "settings", {}, cancelled);
                if (cancelled() || !settings.ok()) return false;
                if (encoder.isEmpty()) return true;
                for (const auto& e : settings.value->value("encoders").toList())
                    if (e.toMap().value("encoderBackend") == encoder) return true;
                return false;
            };
            auto transientFailure = std::make_shared<bool>(false);
            target.transientCapabilityFailure = [transientFailure] { return *transientFailure; };
            target.verifyStreamCapabilities = [client, transientFailure](const std::function<bool()>& cancelled)
                -> std::optional<DeckStreamCapabilities> {
                if (cancelled && cancelled()) return {};
                const auto capabilities = client->fetchCapabilities();
                *transientFailure = capabilities.status == polaris::DeckPolarisRequestStatus::Unreachable ||
                    capabilities.status == polaris::DeckPolarisRequestStatus::Timeout;
                if ((cancelled && cancelled()) || !capabilities.ok()) return {};
                return capabilities.value->streamCapabilities;
            };
        }
        if (!standardHost && !game->id.starts_with("moonlight-app-") && !spaceGame) {
            // The observer owns a separate client on its own thread. A slow
            // status read cannot block controller delivery or stream teardown.
            target.hostTelemetry = [savedIdentity = *identity, savedHost = *host, port, gameUuid = QString::fromStdString(game->id)]() -> std::optional<DeckHudHostTarget> {
                auto observerClient = std::make_shared<polaris::DeckPolarisClient>(
                    backend::polarisClientForHost(savedIdentity, savedHost, port, std::chrono::milliseconds(2000)));
                return DeckHudHostTarget{
                    [observerClient](const std::function<bool()>& cancelled) { return observerClient->fetchHostTelemetry(cancelled); },
                    [] { return true; },
                    [observerClient](bool enabled, const polaris::DeckLiveTuningTelemetry& observed, const std::function<bool()>& cancelled) {
                        return observerClient->setLiveTuningEnabled(enabled, observed, cancelled);
                    },
                    [observerClient](int kbps, const polaris::DeckLiveTuningTelemetry& observed, const std::function<bool()>& cancelled) {
                        return observerClient->setFixedBitrate(kbps, observed, cancelled);
                    },
                    [savedIdentity, savedHost, port](int eventsPort, const std::function<void()>& refresh, const std::function<bool()>& cancelled) {
                        // Construct TLS and network objects on the event thread.
                        auto eventsClient = backend::polarisClientForHost(savedIdentity, savedHost, port, std::chrono::milliseconds(2000));
                        return eventsClient.watchSessionEvents(eventsPort, refresh, cancelled);
                    },
                    [observerClient](const polaris::DeckDoctorRequest& request, const std::function<bool()>& cancelled) {
                        return observerClient->runDoctorAction(request, cancelled);
                    }, std::make_shared<DeckDoctorReceiptStore>(DeckDoctorReceiptStore::directory(), DeckDoctorReceiptStore::key(
                        QSslCertificate(QByteArray::fromStdString(savedHost.serverCertificatePem)).toDer(),
                        QSslCertificate(QByteArray::fromStdString(savedIdentity.clientCertificatePem)).toDer(), gameUuid)),
                    [observerClient](const std::function<bool()>& cancelled) { return observerClient->fetchHostSettings(cancelled); },
                    [observerClient](const QString& display, int bitrate, bool clear, const polaris::DeckLiveTuningTelemetry& observed,
                        const std::function<bool()>& cancelled) {
                        return observerClient->setSessionProfile(display, bitrate, clear, observed, cancelled);
                    }};
            };
            target.authorizeLaunchMode = [client, id = game->id, appId = game->appId, destination]
                (const std::string& mode, const std::function<bool()>& cancelled) {
                if (!polaris::isSessionLaunchMode(mode)) return false;
                if (cancelled && cancelled()) return false;
                const auto capabilities = client->fetchCapabilities();
                if (cancelled && cancelled()) return false;
                if (!capabilities.ok() || !capabilities.value->clientSettings) return false;
                const auto settings = client->get("/polaris/v1/client-settings", 128 * 1024);
                if (cancelled && cancelled()) return false;
                if (!settings.ok()) return false;
                const auto catalog = polaris::parseLaunchModeCatalog(*settings.value);
                if (!catalog) return false;
                const auto games = client->fetchAllGames(100, cancelled, destination == std::optional<std::string>{"desktop"});
                if (!games.ok()) return false;
                if (std::count_if(games.value->begin(), games.value->end(),
                    [&](const auto& entry) { return entry.id == id; }) != 1) return false;
                const auto current = std::find_if(games.value->begin(), games.value->end(),
                    [&](const auto& entry) { return entry.id == id && entry.appId == appId; });
                if (current == games.value->end()) return false;
                const auto policy = polaris::launchModePolicy(*current, *catalog);
                return policy.known && std::find(policy.allowed.begin(), policy.allowed.end(), mode) != policy.allowed.end();
            };
        }
        return target;
    };
}

} // namespace nova::deck::runtime
