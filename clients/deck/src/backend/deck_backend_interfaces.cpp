#include "backend/deck_backend_interfaces.h"
#include "polaris_game_fixture.h"

#include <algorithm>
#include <sstream>
#include <utility>

namespace nova::deck::backend {

namespace {

DeckHostSummary sanitizedHost(DeckHostSummary host) {
    host.rawEndpointForBackendOnly.clear();
    return host;
}

DeckCredentialMetadata sanitizedCredentialMetadata(DeckCredentialMetadata metadata) {
    metadata.rawTokenForBackendOnly.clear();
    metadata.rawCertificateForBackendOnly.clear();
    metadata.rawPrivateKeyForBackendOnly.clear();
    return metadata;
}

std::string endpointClassLabel(const DeckEndpointClass endpointClass) {
    switch (endpointClass) {
    case DeckEndpointClass::Unknown:
        return "unknown-endpoint";
    case DeckEndpointClass::Local:
        return "local-endpoint";
    case DeckEndpointClass::Manual:
        return "manual-endpoint";
    case DeckEndpointClass::Discovered:
        return "discovered-endpoint";
    }
    return "unknown-endpoint";
}

std::string hostStateLabel(const DeckHostState state) {
    switch (state) {
    case DeckHostState::Fixture:
        return "fixture";
    case DeckHostState::Manual:
        return "manual";
    case DeckHostState::Discovered:
        return "discovered";
    case DeckHostState::Offline:
        return "offline";
    case DeckHostState::Online:
        return "online";
    case DeckHostState::PairingNeeded:
        return "pairing-needed";
    case DeckHostState::Paired:
        return "paired";
    case DeckHostState::CertMismatch:
        return "cert-mismatch";
    case DeckHostState::AuthRejected:
        return "auth-rejected";
    case DeckHostState::Unsupported:
        return "unsupported";
    }
    return "unknown";
}

std::string sourceLabelFor(const std::string& source) {
    if (source == "steam") {
        return "Steam";
    }
    if (source == "lutris") {
        return "Lutris";
    }
    if (source == "heroic") {
        return "Heroic";
    }
    if (source == "manual") {
        return "Manual";
    }
    return source.empty() ? std::string{"Other"} : source;
}

std::string sourceRuntimeLabelFor(const PolarisGameFixture& game) {
    std::vector<std::string> parts;
    parts.push_back(sourceLabelFor(game.launcherSource.empty() ? game.source : game.launcherSource));
    if (!game.platformLabel.empty()) {
        parts.push_back(game.platformLabel);
    }
    if (!game.runtimeLabel.empty() && game.runtimeLabel != game.platformLabel) {
        parts.push_back(game.runtimeLabel);
    }

    std::string label;
    for (const auto& part : parts) {
        if (part.empty()) {
            continue;
        }
        if (!label.empty()) {
            label += " · ";
        }
        label += part;
    }
    return label;
}

std::string launchModeLabelFor(const PolarisGameFixture& game) {
    const std::string streamMode = game.launchMode.recommendedMode.empty() ? "preview" : game.launchMode.recommendedMode;
    const std::string steamMode = game.steamLaunch.recommendedMode.empty() ? "direct" : game.steamLaunch.recommendedMode;
    return "Stream: " + streamMode + " · Steam: " + steamMode;
}

std::string defaultHostStatusLabelFor(const DeckHostSummary& host) {
    if (!host.publicStatusLabel.empty()) {
        return host.publicStatusLabel;
    }
    if (host.fixtureOnly) {
        return "Backend-owned fixture summary · read-only";
    }
    if (host.state == DeckHostState::Offline) {
        return "Backend-owned offline summary · read-only";
    }
    if (host.state == DeckHostState::PairingNeeded) {
        return "Backend-owned unpaired summary · read-only";
    }
    return "Backend-owned sanitized host summary · read-only";
}

std::string defaultHostSubtitleFor(const DeckHostSummary& host) {
    if (!host.publicSubtitle.empty()) {
        return host.publicSubtitle;
    }
    return "Backend read-only host summary — no discovery, join-flow, endpoint, cert, or private material was read.";
}

std::string defaultHostProvenanceFor(const DeckHostSummary& host) {
    if (!host.publicProvenanceLabel.empty()) {
        return host.publicProvenanceLabel;
    }
    return host.fixtureOnly ? "fixture/read-only/backend-owned" : "sanitized/read-only/backend-owned";
}

DeckPreflightBlocker blockerFor(const DeckPreflightBlockerCategory category) {
    auto publicReasonFor = [](const DeckPreflightBlockerCategory reasonCategory) {
        switch (reasonCategory) {
        case DeckPreflightBlockerCategory::FixtureOnly:
            return "Fixture provenance only; backend will not treat this as a live host.";
        case DeckPreflightBlockerCategory::NetworkDisabled:
            return "Network reads are disabled in this Deck preview.";
        case DeckPreflightBlockerCategory::MissingHost:
            return "No backend host summary is selected.";
        case DeckPreflightBlockerCategory::HostUnreachable:
            return "Host is offline or has no reachable sanitized endpoint candidate.";
        case DeckPreflightBlockerCategory::PairingRequired:
            return "Host is unpaired; pairing and credential reads stay disabled.";
        case DeckPreflightBlockerCategory::CertMismatch:
            return "Host trust metadata reports a certificate mismatch.";
        case DeckPreflightBlockerCategory::AuthRejected:
            return "Host trust metadata reports rejected authorization.";
        case DeckPreflightBlockerCategory::LibraryUnavailable:
            return "Backend library summary is unavailable.";
        case DeckPreflightBlockerCategory::AppNotFound:
            return "Selected app is absent from the backend library summary.";
        case DeckPreflightBlockerCategory::SessionOwnedByAnotherClient:
            return "Session is owned by another client.";
        case DeckPreflightBlockerCategory::WatchNotAvailable:
            return "Watch mode is unavailable for this read-only preview.";
        case DeckPreflightBlockerCategory::LaunchNotAllowed:
            return "Host launch is not allowed by the lab gate.";
        case DeckPreflightBlockerCategory::LabGateDisabled:
            return "Lab gate is disabled; dry-run launch, stream start, and backend power stay off.";
        case DeckPreflightBlockerCategory::RendererUnavailable:
            return "Renderer readiness is blocked.";
        case DeckPreflightBlockerCategory::AudioUnavailable:
            return "Audio readiness is blocked.";
        case DeckPreflightBlockerCategory::InputUnavailable:
            return "Input readiness is blocked.";
        }
        return "Read-only backend preflight blocked.";
    };

    return DeckPreflightBlocker{
        .category = category,
        .code = toPublicCode(category),
        .publicReason = publicReasonFor(category),
    };
}

void appendBlocker(std::vector<DeckPreflightBlocker>& blockers, const DeckPreflightBlockerCategory category) {
    blockers.push_back(blockerFor(category));
}

bool atLeastLaunchDryRun(const DeckLabGateMode mode) {
    return mode == DeckLabGateMode::LaunchDryRun
        || mode == DeckLabGateMode::LaunchLab
        || mode == DeckLabGateMode::StreamLab;
}

DeckLaunchPreflightInput publicPreviewInputFor(
    const DeckHostRepository& repository,
    const DeckCredentialStore& credentialStore,
    const DeckLabGate& labGate,
    const DeckPublicBackendPreviewRequest& request) {
    const auto host = repository.hostById(request.hostId);
    DeckCredentialMetadata credentials;
    if (const auto metadata = credentialStore.metadataForHost(request.hostId)) {
        credentials = metadata.value();
    } else {
        credentials.hostId = request.hostId;
    }

    return DeckLaunchPreflightInput{
        .host = host,
        .credentials = credentials,
        .library = DeckLibraryAvailability{
            .available = host.has_value() && host->polarisAvailable,
            .gameAvailable = host.has_value() && host->standardAppListAvailable && !request.gameId.empty(),
            .sourceLabel = host.has_value() ? "sanitized-backend-snapshot" : "sanitized-backend-missing-host",
        },
        .session = DeckSessionSummary{
            .ownedByAnotherClient = false,
            .watchAvailable = true,
        },
        .backendReadiness = DeckBackendReadiness{
            .rendererAvailable = true,
            .audioAvailable = true,
            .inputAvailable = true,
        },
        .labGate = labGate,
        .requestedGameId = request.gameId,
        .requestedProfileId = request.profileId,
    };
}

DeckPublicPreflightPreview publicPreviewFor(
    const DeckPreflightReport& report,
    const DeckCoordinatorResult& coordinatorResult) {
    std::vector<std::string> blockerCodes;
    blockerCodes.reserve(report.blockers.size());
    for (const auto& blocker : report.blockers) {
        blockerCodes.push_back(blocker.code);
    }

    return DeckPublicPreflightPreview{
        .statusCode = report.approved ? "backend-preflight-approved-dry-run" : "backend-preflight-blocked",
        .approved = report.approved,
        .blockerCodes = std::move(blockerCodes),
        .launchDryRunAllowed = report.coordinatorRequest.launchAllowed,
        .streamAllowed = report.coordinatorRequest.streamAllowed,
        .backendPowerStarted = coordinatorResult.networkStarted || coordinatorResult.rawStartCalled,
        .publicCopy = report.publicCopy + "; coordinator=" + coordinatorResult.statusCode,
    };
}

DeckPublicReadOnlyDtoParity readOnlyDtoParityFor(
    const DeckPublicReadOnlyPreflightState& preflight,
    const std::string& scenarioId,
    const std::string& scenarioLabel) {
    const std::string scenario = scenarioId.empty() ? std::string{"default"} : scenarioId;
    const std::string label = scenarioLabel.empty() ? std::string{"Read-only fixture state"} : scenarioLabel;
    return DeckPublicReadOnlyDtoParity{
        .contractId = "backend-owned-read-only-dto-v1",
        .ownerCode = "backend-owned-read-only-model",
        .privacyCode = "redacted-public-dto",
        .readinessCode = "dto-parity-ready",
        .collapsedSummary = "Backend-owned DTO parity · contract=backend-owned-read-only-dto-v1 · readiness=dto-parity-ready · status=" + preflight.statusCode,
        .expandedDiagnostics = "DTO parity: scenario=" + scenario + " · label=" + label + " · contract=backend-owned-read-only-dto-v1 · owner=backend-owned-read-only-model · privacy=redacted-public-dto · readiness=dto-parity-ready",
        .artifactSummary = "dto_contract=backend-owned-read-only-dto-v1 dto_owner=backend-owned-read-only-model dto_privacy=redacted-public-dto dto_readiness=dto-parity-ready backendPowerStarted=false stream=false",
    };
}

bool hasBlockerCode(const DeckPublicReadOnlyPreflightState& preflight, const std::string_view code) {
    return std::find(preflight.blockerCodes.begin(), preflight.blockerCodes.end(), code) != preflight.blockerCodes.end();
}

DeckPublicReadOnlyPlayerState playerStateFor(const DeckPublicReadOnlyPreflightState& preflight, const std::string& scenarioLabel) {
    DeckPublicReadOnlyPlayerState playerState{
        .title = scenarioLabel.empty() ? "Product state: Launch preview blocked" : "Product state: " + scenarioLabel,
        .body = scenarioLabel.empty() ? "Launch preview blocked. Open diagnostics." : "Launch preview blocked. Open diagnostics for " + scenarioLabel + ".",
        .actionLabel = "Review the safe launch plan before copying it locally.",
        .safetyLabel = "Read-only state only; diagnostics are secondary and safe to inspect.",
        .provenanceLabel = "dto-player-state/backend-owned/redacted-public",
        .focusOrder = "state-card-copy-diagnostics",
        .focusOrderCopy = "Focus order: state card → Copy plan → Show diagnostics",
    };

    if (hasBlockerCode(preflight, "missing-host")) {
        playerState.title = "Product state: Ready for setup";
        playerState.body = "Select a host and game to continue.";
        playerState.actionLabel = "Add a backend host before previewing a launch plan.";
        return playerState;
    }
    if (hasBlockerCode(preflight, "lab-gate-disabled") || hasBlockerCode(preflight, "fixture-only")) {
        playerState.title = "Product state: Lab gate locked";
        playerState.body = "Launch blocked by lab gate.";
        playerState.actionLabel = "Ask an operator to open the lab gate before any start path.";
        playerState.safetyLabel = "Backend power, launch, stream, discovery, and media stay off.";
        return playerState;
    }
    if (hasBlockerCode(preflight, "library-unavailable") || hasBlockerCode(preflight, "app-not-found")) {
        playerState.title = "Product state: Library unavailable";
        playerState.body = "Library unavailable. Try again when the read-only snapshot is back.";
        playerState.actionLabel = "Wait for the read-only library snapshot to return.";
        playerState.safetyLabel = "No Polaris API call or fallback app-list fetch runs from this shell.";
        return playerState;
    }
    if (hasBlockerCode(preflight, "host-unreachable")) {
        playerState.title = "Product state: Host offline";
        playerState.body = "Host offline. Reconnect or pick another host.";
        playerState.actionLabel = "Reconnect the host or choose another backend-owned snapshot.";
        playerState.safetyLabel = "Backend power stays off; no retry or network probe runs.";
        return playerState;
    }
    if (hasBlockerCode(preflight, "pairing-required")) {
        playerState.title = "Product state: Pair host";
        playerState.body = "Pair this host before launch preview.";
        playerState.actionLabel = "Pair this host in an approved flow before preview launch.";
        playerState.safetyLabel = "Credentials stay unread until an approved pair flow exists.";
        return playerState;
    }
    if (preflight.statusCode == "backend-read-only-preflight-approved-dry-run") {
        playerState.title = "Product state: Dry-run ready";
        playerState.body = "Dry-run preview ready. Stream stays off.";
    }
    return playerState;
}

} // namespace

DeckLabGate DeckLabGate::forMode(const DeckLabGateMode mode) {
    return DeckLabGate(mode);
}

DeckLabGate::DeckLabGate(const DeckLabGateMode mode)
    : mode_(mode) {}

DeckLabGateMode DeckLabGate::mode() const {
    return mode_;
}

std::string DeckLabGate::modeLabel() const {
    switch (mode_) {
    case DeckLabGateMode::Disabled:
        return "disabled";
    case DeckLabGateMode::ReadOnlyNetwork:
        return "read-only-network";
    case DeckLabGateMode::PairingLab:
        return "pairing-lab";
    case DeckLabGateMode::LaunchDryRun:
        return "launch-dry-run";
    case DeckLabGateMode::LaunchLab:
        return "launch-lab";
    case DeckLabGateMode::StreamLab:
        return "stream-lab";
    }
    return "disabled";
}

bool DeckLabGate::networkReadAllowed() const {
    return mode_ != DeckLabGateMode::Disabled;
}

bool DeckLabGate::pairingAllowed() const {
    return mode_ == DeckLabGateMode::PairingLab
        || mode_ == DeckLabGateMode::LaunchLab
        || mode_ == DeckLabGateMode::StreamLab;
}

bool DeckLabGate::launchDryRunAllowed() const {
    return atLeastLaunchDryRun(mode_);
}

bool DeckLabGate::hostLaunchAllowed() const {
    return mode_ == DeckLabGateMode::LaunchLab || mode_ == DeckLabGateMode::StreamLab;
}

bool DeckLabGate::streamStartAllowed() const {
    return mode_ == DeckLabGateMode::StreamLab;
}

void DeckFakeHostRepository::upsertFixtureHost(std::string id, std::string displayName) {
    hosts_.push_back(DeckHostSummary{
        .id = std::move(id),
        .displayName = std::move(displayName),
        .state = DeckHostState::Fixture,
        .endpointClass = DeckEndpointClass::Unknown,
        .fixtureOnly = true,
        .hasEndpointCandidate = false,
        .polarisAvailable = false,
        .standardAppListAvailable = false,
        .publicStatusLabel = "Backend-owned fixture summary · read-only",
        .publicSubtitle = "Backend read-only host summary — no discovery, join-flow, endpoint, cert, or private material was read.",
        .publicProvenanceLabel = "fixture/read-only/backend-owned",
    });
}

void DeckFakeHostRepository::upsertSanitizedHostSummary(DeckHostSummary host) {
    host.rawEndpointForBackendOnly.clear();
    hosts_.push_back(std::move(host));
}

void DeckFakeHostRepository::upsertManualHostForTest(std::string id, std::string displayName, std::string rawEndpoint) {
    hosts_.push_back(DeckHostSummary{
        .id = std::move(id),
        .displayName = std::move(displayName),
        .state = DeckHostState::Manual,
        .endpointClass = DeckEndpointClass::Manual,
        .fixtureOnly = false,
        .hasEndpointCandidate = true,
        .polarisAvailable = false,
        .standardAppListAvailable = false,
        .rawEndpointForBackendOnly = std::move(rawEndpoint),
    });
}

std::vector<DeckHostSummary> DeckFakeHostRepository::listHosts() const {
    std::vector<DeckHostSummary> sanitized;
    sanitized.reserve(hosts_.size());
    for (auto host : hosts_) {
        sanitized.push_back(sanitizedHost(std::move(host)));
    }
    return sanitized;
}

std::optional<DeckHostSummary> DeckFakeHostRepository::hostById(const std::string_view hostId) const {
    const auto found = std::find_if(hosts_.begin(), hosts_.end(), [hostId](const auto& host) {
        return host.id == hostId;
    });
    if (found == hosts_.end()) {
        return std::nullopt;
    }
    return sanitizedHost(*found);
}

std::string DeckFakeHostRepository::backendEndpointForTest(const std::string_view hostId) const {
    const auto found = std::find_if(hosts_.begin(), hosts_.end(), [hostId](const auto& host) {
        return host.id == hostId;
    });
    if (found == hosts_.end()) {
        return {};
    }
    return found->rawEndpointForBackendOnly;
}

void DeckCredentialStore::upsertMetadata(DeckCredentialMetadata metadata) {
    const auto found = std::find_if(metadata_.begin(), metadata_.end(), [&metadata](const auto& existing) {
        return existing.hostId == metadata.hostId;
    });
    if (found == metadata_.end()) {
        metadata_.push_back(std::move(metadata));
        return;
    }
    *found = std::move(metadata);
}

std::optional<DeckCredentialMetadata> DeckCredentialStore::metadataForHost(const std::string_view hostId) const {
    const auto found = std::find_if(metadata_.begin(), metadata_.end(), [hostId](const auto& metadata) {
        return metadata.hostId == hostId;
    });
    if (found == metadata_.end()) {
        return std::nullopt;
    }
    return sanitizedCredentialMetadata(*found);
}

DeckPreflightReport DeckLaunchPreflightService::evaluate(const DeckLaunchPreflightInput& input) const {
    std::vector<DeckPreflightBlocker> blockers;

    if (!input.host.has_value()) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::MissingHost);
    } else {
        const auto& host = input.host.value();
        if (host.fixtureOnly) {
            appendBlocker(blockers, DeckPreflightBlockerCategory::FixtureOnly);
        }
        if (!host.hasEndpointCandidate) {
            appendBlocker(blockers, DeckPreflightBlockerCategory::HostUnreachable);
        }
        if (host.state == DeckHostState::CertMismatch) {
            appendBlocker(blockers, DeckPreflightBlockerCategory::CertMismatch);
        }
        if (host.state == DeckHostState::AuthRejected) {
            appendBlocker(blockers, DeckPreflightBlockerCategory::AuthRejected);
        }
    }

    if (input.labGate.mode() == DeckLabGateMode::Disabled || !input.labGate.launchDryRunAllowed()) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::LabGateDisabled);
    }
    if (!input.labGate.networkReadAllowed()) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::NetworkDisabled);
    }
    if (!input.credentials.paired) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::PairingRequired);
    }
    if (input.credentials.certMismatch) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::CertMismatch);
    }
    if (input.credentials.authRejected) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::AuthRejected);
    }
    if (!input.library.available) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::LibraryUnavailable);
    } else if (!input.library.gameAvailable) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::AppNotFound);
    }
    if (input.session.ownedByAnotherClient) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::SessionOwnedByAnotherClient);
        if (!input.session.watchAvailable) {
            appendBlocker(blockers, DeckPreflightBlockerCategory::WatchNotAvailable);
        }
    }
    if (!input.backendReadiness.rendererAvailable) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::RendererUnavailable);
    }
    if (!input.backendReadiness.audioAvailable) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::AudioUnavailable);
    }
    if (!input.backendReadiness.inputAvailable) {
        appendBlocker(blockers, DeckPreflightBlockerCategory::InputUnavailable);
    }

    const bool approved = blockers.empty();
    DeckCoordinatorRequest coordinatorRequest;
    if (input.host.has_value()) {
        coordinatorRequest.hostId = input.host->id;
    }
    coordinatorRequest.gameId = input.requestedGameId;
    coordinatorRequest.profileId = input.requestedProfileId;
    coordinatorRequest.launchAllowed = approved && input.labGate.launchDryRunAllowed();
    coordinatorRequest.streamAllowed = false;
    coordinatorRequest.publicPlan = approved
        ? "launch dry-run approved; host launch and stream start remain disabled"
        : "launch preflight blocked before backend power";

    std::ostringstream copy;
    copy << "Deck launch preflight: " << (approved ? "approved-dry-run" : "blocked")
         << " blockers=" << blockers.size()
         << " lab=" << input.labGate.modeLabel();
    if (input.host.has_value()) {
        copy << " host=" << hostStateLabel(input.host->state)
             << " endpoint=" << endpointClassLabel(input.host->endpointClass);
    } else {
        copy << " host=missing";
    }
    copy << " library=" << (input.library.available ? "available" : "unavailable")
         << " renderer=" << (input.backendReadiness.rendererAvailable ? "ready" : "blocked")
         << " audio=" << (input.backendReadiness.audioAvailable ? "ready" : "blocked")
         << " input=" << (input.backendReadiness.inputAvailable ? "ready" : "blocked");
    if (!blockers.empty()) {
        copy << " reasons=";
        for (std::size_t index = 0; index < blockers.size(); ++index) {
            if (index != 0) {
                copy << " | ";
            }
            copy << blockers[index].code << ": " << blockers[index].publicReason;
        }
    }

    return DeckPreflightReport{
        .approved = approved,
        .blockers = std::move(blockers),
        .coordinatorRequest = std::move(coordinatorRequest),
        .publicCopy = copy.str(),
    };
}

DeckCoordinatorResult DeckStreamSessionCoordinator::dryRun(const DeckCoordinatorRequest& request) const {
    if (!request.launchAllowed) {
        return DeckCoordinatorResult{
            .accepted = false,
            .networkStarted = false,
            .rawStartCalled = false,
            .statusCode = "coordinator-dry-run-blocked",
            .publicCopy = "Coordinator refused blocked request; no network or host mutation occurred.",
        };
    }
    return DeckCoordinatorResult{
        .accepted = true,
        .networkStarted = false,
        .rawStartCalled = false,
        .statusCode = "coordinator-dry-run-ready",
        .publicCopy = "Coordinator accepted sanitized dry-run request for host category; no launch, stream, discovery, pairing, credential read, or network call occurred.",
    };
}

void DeckDiagnosticsModel::updateHost(const DeckHostSummary& host) {
    hostCategory_ = "host=" + hostStateLabel(host.state) + ";endpoint=" + endpointClassLabel(host.endpointClass);
}

void DeckDiagnosticsModel::updateCredentials(const DeckCredentialMetadata& credentials) {
    trustCategory_ = credentials.certMismatch ? "trust=cert-mismatch"
        : credentials.authRejected ? "trust=auth-rejected"
        : credentials.paired ? "trust=paired-metadata"
        : "trust=pairing-required";
}

void DeckDiagnosticsModel::updateBackendReadiness(const DeckBackendReadiness& readiness) {
    backendCategory_ = "renderer=" + std::string(readiness.rendererAvailable ? "ready" : "blocked")
        + ";audio=" + std::string(readiness.audioAvailable ? "ready" : "blocked")
        + ";input=" + std::string(readiness.inputAvailable ? "ready" : "blocked");
}

void DeckDiagnosticsModel::updatePreflight(const DeckPreflightReport& report) {
    preflightCategory_ = "preflight=" + std::string(report.approved ? "approved-dry-run" : "blocked")
        + ";blockers=" + std::to_string(report.blockers.size());
}

void DeckDiagnosticsModel::updateCoordinatorStatus(std::string statusCode) {
    coordinatorStatus_ = "coordinator=" + std::move(statusCode);
}

std::string DeckDiagnosticsModel::copyText() const {
    return "Nova Deck diagnostics: " + hostCategory_
        + "; " + trustCategory_
        + "; " + backendCategory_
        + "; " + preflightCategory_
        + "; " + coordinatorStatus_
        + "; privacy=redacted";
}

DeckPublicPreflightPreview requestDeckBackendPreflightPreview(
    const DeckHostRepository& repository,
    const DeckCredentialStore& credentialStore,
    const DeckLaunchPreflightService& preflightService,
    const DeckStreamSessionCoordinator& coordinator,
    const DeckLabGate& labGate,
    const DeckPublicBackendPreviewRequest& request) {
    const auto input = publicPreviewInputFor(repository, credentialStore, labGate, request);
    const auto report = preflightService.evaluate(input);
    const auto coordinatorResult = coordinator.dryRun(report.coordinatorRequest);
    return publicPreviewFor(report, coordinatorResult);
}

DeckPublicDiagnosticsPreview requestDeckBackendDiagnosticsPreview(
    const DeckHostRepository& repository,
    const DeckCredentialStore& credentialStore,
    const DeckLaunchPreflightService& preflightService,
    const DeckStreamSessionCoordinator& coordinator,
    DeckDiagnosticsModel& diagnostics,
    const DeckLabGate& labGate,
    const DeckPublicBackendPreviewRequest& request) {
    const auto input = publicPreviewInputFor(repository, credentialStore, labGate, request);
    const auto report = preflightService.evaluate(input);
    const auto coordinatorResult = coordinator.dryRun(report.coordinatorRequest);

    if (input.host.has_value()) {
        diagnostics.updateHost(input.host.value());
    }
    diagnostics.updateCredentials(input.credentials);
    diagnostics.updateBackendReadiness(input.backendReadiness);
    diagnostics.updatePreflight(report);
    diagnostics.updateCoordinatorStatus(coordinatorResult.statusCode);

    return DeckPublicDiagnosticsPreview{
        .statusCode = "backend-diagnostics-ready",
        .privacyCode = "redacted-public-dto",
        .copyText = diagnostics.copyText(),
    };
}

DeckPublicReadOnlyHostLibraryState buildReadOnlyHostLibraryState(
    const DeckHostRepository& repository,
    const PolarisGameLibraryFixture& library,
    const DeckLaunchPreflightService& preflightService,
    const DeckLabGate& labGate) {
    DeckPublicReadOnlyHostLibraryState state;
    state.sourceLabel = library.sourceLabel;
    state.readOnly = library.readOnly;

    const auto hosts = repository.listHosts();
    state.hosts.reserve(hosts.size());
    int hostRow = 0;
    for (const auto& host : hosts) {
        state.hosts.push_back(DeckPublicReadOnlyHostItem{
            .id = host.id.empty() ? "host-empty-state" : host.id,
            .displayName = host.displayName.empty() ? "Read-only backend host" : host.displayName,
            .statusLabel = defaultHostStatusLabelFor(host),
            .subtitle = defaultHostSubtitleFor(host),
            .provenanceLabel = defaultHostProvenanceFor(host),
            .initialFocus = hostRow == 0,
        });
        ++hostRow;
    }

    state.games.reserve(library.games.size());
    int gameRow = 0;
    for (const auto& game : library.games) {
        state.games.push_back(DeckPublicReadOnlyGameItem{
            .id = game.id.empty() ? "library-game-" + std::to_string(gameRow) : game.id,
            .title = game.name.empty() ? "Untitled game" : game.name,
            .sourceRuntimeLabel = sourceRuntimeLabelFor(game),
            .launchModeLabel = launchModeLabelFor(game),
            .installedLabel = game.installed ? "Installed" : "Not installed",
            .initialFocus = gameRow == 0,
        });
        ++gameRow;
    }

    DeckCredentialMetadata credentials;
    DeckLaunchPreflightInput input;
    input.host = hosts.empty() ? std::optional<DeckHostSummary>{} : std::optional<DeckHostSummary>{hosts.front()};
    input.credentials = credentials;
    if (input.host.has_value()) {
        input.credentials.hostId = input.host->id;
    }
    input.library = DeckLibraryAvailability{
        .available = library.readOnly && !library.games.empty(),
        .gameAvailable = !library.games.empty(),
        .sourceLabel = "backend-owned-read-only-library-summary",
    };
    input.session = DeckSessionSummary{
        .ownedByAnotherClient = false,
        .watchAvailable = false,
    };
    input.backendReadiness = DeckBackendReadiness{
        .rendererAvailable = true,
        .audioAvailable = true,
        .inputAvailable = true,
    };
    input.labGate = labGate;
    input.requestedGameId = library.games.empty() ? std::string{} : library.games.front().id;
    input.requestedProfileId = "read-only-preview";

    const auto report = preflightService.evaluate(input);
    state.preflight.statusCode = report.approved ? "backend-read-only-preflight-approved-dry-run" : "backend-read-only-preflight-blocked";
    state.preflight.launchDryRunAllowed = report.coordinatorRequest.launchAllowed;
    state.preflight.streamAllowed = report.coordinatorRequest.streamAllowed;
    state.preflight.backendPowerStarted = false;
    state.preflight.publicCopy = report.publicCopy + "; source=backend-owned-read-only-model; backendPowerStarted=false";
    state.preflight.blockerCodes.reserve(report.blockers.size());
    for (const auto& blocker : report.blockers) {
        state.preflight.blockerCodes.push_back(blocker.code);
    }
    state.playerState = playerStateFor(state.preflight, state.scenarioLabel);
    state.dtoParity = readOnlyDtoParityFor(state.preflight, state.scenarioId, state.scenarioLabel);
    return state;
}

std::vector<DeckPublicReadOnlyHostLibraryState> buildReadOnlyHostLibraryStateMatrix(
    const PolarisGameLibraryFixture& library,
    const DeckLaunchPreflightService& preflightService) {
    auto withMatrixLabels = [](DeckPublicReadOnlyHostLibraryState state, std::string scenarioId, std::string scenarioLabel) {
        state.scenarioId = std::move(scenarioId);
        state.scenarioLabel = std::move(scenarioLabel);
        state.sourceLabel = "backend-owned read-only matrix · " + state.scenarioLabel;
        state.preflight.publicCopy += "; source=backend-owned-read-only-matrix; scenario=" + state.scenarioId;
        state.playerState = playerStateFor(state.preflight, state.scenarioLabel);
        state.dtoParity = readOnlyDtoParityFor(state.preflight, state.scenarioId, state.scenarioLabel);
        return state;
    };

    auto firstGameLibrary = library;
    if (firstGameLibrary.games.empty()) {
        firstGameLibrary.sourceLabel = "Backend read-only matrix fixture";
    }

    std::vector<DeckPublicReadOnlyHostLibraryState> matrix;
    matrix.reserve(5);

    {
        PolarisGameLibraryFixture emptyLibrary = library;
        emptyLibrary.hosts.clear();
        emptyLibrary.games.clear();
        emptyLibrary.sourceLabel = "Backend read-only matrix empty fixture";
        DeckFakeHostRepository emptyRepository;
        matrix.push_back(withMatrixLabels(
            buildReadOnlyHostLibraryState(
                emptyRepository,
                emptyLibrary,
                preflightService,
                DeckLabGate::forMode(DeckLabGateMode::Disabled)),
            "empty",
            "Ready for setup · no host or game selected"));
    }

    {
        DeckFakeHostRepository offlineRepository;
        offlineRepository.upsertSanitizedHostSummary(DeckHostSummary{
            .id = "matrix-offline-host",
            .displayName = "Offline backend host",
            .state = DeckHostState::Offline,
            .endpointClass = DeckEndpointClass::Unknown,
            .fixtureOnly = false,
            .hasEndpointCandidate = false,
            .polarisAvailable = true,
            .standardAppListAvailable = true,
            .publicStatusLabel = "Backend-owned offline summary · read-only",
            .publicSubtitle = "Offline sanitized host state — no discovery, ping, endpoint, cert, or credential material was read.",
            .publicProvenanceLabel = "offline/read-only/backend-owned",
        });
        matrix.push_back(withMatrixLabels(
            buildReadOnlyHostLibraryState(
                offlineRepository,
                firstGameLibrary,
                preflightService,
                DeckLabGate::forMode(DeckLabGateMode::LaunchDryRun)),
            "offline",
            "Host offline · reconnect before preview"));
    }

    {
        DeckFakeHostRepository unpairedRepository;
        unpairedRepository.upsertSanitizedHostSummary(DeckHostSummary{
            .id = "matrix-unpaired-host",
            .displayName = "Unpaired backend host",
            .state = DeckHostState::PairingNeeded,
            .endpointClass = DeckEndpointClass::Manual,
            .fixtureOnly = false,
            .hasEndpointCandidate = true,
            .polarisAvailable = true,
            .standardAppListAvailable = true,
            .publicStatusLabel = "Backend-owned unpaired summary · read-only",
            .publicSubtitle = "Unpaired sanitized host state — pairing, token, certificate, and private-key reads stay disabled.",
            .publicProvenanceLabel = "unpaired/read-only/backend-owned",
        });
        matrix.push_back(withMatrixLabels(
            buildReadOnlyHostLibraryState(
                unpairedRepository,
                firstGameLibrary,
                preflightService,
                DeckLabGate::forMode(DeckLabGateMode::LaunchDryRun)),
            "unpaired",
            "Pair host · approved pairing required"));
    }

    {
        PolarisGameLibraryFixture unavailableLibrary = library;
        unavailableLibrary.games.clear();
        unavailableLibrary.sourceLabel = "Backend read-only matrix unavailable library fixture";
        DeckFakeHostRepository libraryUnavailableRepository;
        libraryUnavailableRepository.upsertSanitizedHostSummary(DeckHostSummary{
            .id = "matrix-library-unavailable-host",
            .displayName = "Library unavailable host",
            .state = DeckHostState::PairingNeeded,
            .endpointClass = DeckEndpointClass::Manual,
            .fixtureOnly = false,
            .hasEndpointCandidate = true,
            .polarisAvailable = false,
            .standardAppListAvailable = false,
            .publicStatusLabel = "Backend-owned library unavailable summary · read-only",
            .publicSubtitle = "Library summary unavailable — no Polaris API request, credential read, or network retry was made.",
            .publicProvenanceLabel = "library-unavailable/read-only/backend-owned",
        });
        matrix.push_back(withMatrixLabels(
            buildReadOnlyHostLibraryState(
                libraryUnavailableRepository,
                unavailableLibrary,
                preflightService,
                DeckLabGate::forMode(DeckLabGateMode::LaunchDryRun)),
            "library-unavailable",
            "Library unavailable · read-only snapshot missing"));
    }

    {
        DeckFakeHostRepository labGatedRepository;
        labGatedRepository.upsertSanitizedHostSummary(DeckHostSummary{
            .id = "matrix-lab-gated-host",
            .displayName = "Lab-gated backend host",
            .state = DeckHostState::Fixture,
            .endpointClass = DeckEndpointClass::Unknown,
            .fixtureOnly = true,
            .hasEndpointCandidate = false,
            .polarisAvailable = true,
            .standardAppListAvailable = true,
            .publicStatusLabel = "Backend-owned lab-gated summary · read-only",
            .publicSubtitle = "Lab gate disabled — dry-run launch, stream start, discovery, pairing, and backend power remain off.",
            .publicProvenanceLabel = "lab-gated/read-only/backend-owned",
        });
        matrix.push_back(withMatrixLabels(
            buildReadOnlyHostLibraryState(
                labGatedRepository,
                firstGameLibrary,
                preflightService,
                DeckLabGate::forMode(DeckLabGateMode::Disabled)),
            "lab-gated",
            "Lab gate locked · start paths disabled"));
    }

    return matrix;
}

DeckFixtureReadOnlyStateProvider::DeckFixtureReadOnlyStateProvider(
    const PolarisGameLibraryFixture& library,
    const DeckLaunchPreflightService& preflightService)
    : matrix_(buildReadOnlyHostLibraryStateMatrix(library, preflightService)) {}

std::vector<DeckPublicReadOnlyHostLibraryState> DeckFixtureReadOnlyStateProvider::stateMatrix() const {
    std::vector<DeckPublicReadOnlyHostLibraryState> matrix;
    matrix.reserve(matrix_.size());
    for (auto state : matrix_) {
        matrix.push_back(withDefaultPlayerState(std::move(state)));
    }
    return matrix;
}

DeckPublicReadOnlyHostLibraryState DeckFixtureReadOnlyStateProvider::stateForScenario(const std::string_view scenarioId) const {
    const auto found = std::find_if(matrix_.begin(), matrix_.end(), [scenarioId](const auto& state) {
        return !scenarioId.empty() && state.scenarioId == scenarioId;
    });
    if (found != matrix_.end()) {
        return withDefaultPlayerState(*found);
    }

    const auto fallback = std::find_if(matrix_.begin(), matrix_.end(), [](const auto& state) {
        return state.scenarioId == "lab-gated";
    });
    if (fallback != matrix_.end()) {
        return withDefaultPlayerState(*fallback);
    }
    return matrix_.empty() ? DeckPublicReadOnlyHostLibraryState{} : withDefaultPlayerState(matrix_.front());
}

DeckPublicReadOnlyHostLibraryState DeckFixtureReadOnlyStateProvider::withDefaultPlayerState(DeckPublicReadOnlyHostLibraryState state) const {
    const auto defaults = playerStateFor(state.preflight, state.scenarioLabel);
    if (state.playerState.title.empty()) {
        state.playerState.title = defaults.title;
    }
    if (state.playerState.body.empty()) {
        state.playerState.body = defaults.body;
    }
    if (state.playerState.actionLabel.empty()) {
        state.playerState.actionLabel = defaults.actionLabel;
    }
    if (state.playerState.safetyLabel.empty()) {
        state.playerState.safetyLabel = defaults.safetyLabel;
    }
    if (state.playerState.provenanceLabel.empty()) {
        state.playerState.provenanceLabel = defaults.provenanceLabel;
    }
    if (state.playerState.focusOrder.empty()) {
        state.playerState.focusOrder = defaults.focusOrder;
    }
    if (state.playerState.focusOrderCopy.empty()) {
        state.playerState.focusOrderCopy = defaults.focusOrderCopy;
    }
    return state;
}

std::string toPublicCode(const DeckPreflightBlockerCategory category) {
    switch (category) {
    case DeckPreflightBlockerCategory::FixtureOnly:
        return "fixture-only";
    case DeckPreflightBlockerCategory::NetworkDisabled:
        return "network-disabled";
    case DeckPreflightBlockerCategory::MissingHost:
        return "missing-host";
    case DeckPreflightBlockerCategory::HostUnreachable:
        return "host-unreachable";
    case DeckPreflightBlockerCategory::PairingRequired:
        return "pairing-required";
    case DeckPreflightBlockerCategory::CertMismatch:
        return "cert-mismatch";
    case DeckPreflightBlockerCategory::AuthRejected:
        return "auth-rejected";
    case DeckPreflightBlockerCategory::LibraryUnavailable:
        return "library-unavailable";
    case DeckPreflightBlockerCategory::AppNotFound:
        return "app-not-found";
    case DeckPreflightBlockerCategory::SessionOwnedByAnotherClient:
        return "session-owned-by-another-client";
    case DeckPreflightBlockerCategory::WatchNotAvailable:
        return "watch-not-available";
    case DeckPreflightBlockerCategory::LaunchNotAllowed:
        return "launch-not-allowed";
    case DeckPreflightBlockerCategory::LabGateDisabled:
        return "lab-gate-disabled";
    case DeckPreflightBlockerCategory::RendererUnavailable:
        return "renderer-unavailable";
    case DeckPreflightBlockerCategory::AudioUnavailable:
        return "audio-unavailable";
    case DeckPreflightBlockerCategory::InputUnavailable:
        return "input-unavailable";
    }
    return "unknown-blocker";
}

} // namespace nova::deck::backend
