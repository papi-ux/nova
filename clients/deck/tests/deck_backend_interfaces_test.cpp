#include "backend/deck_backend_interfaces.h"

#include <algorithm>
#include <cassert>
#include <string>
#include <string_view>
#include <vector>

#include "polaris_game_fixture.h"

namespace {

using nova::deck::backend::DeckBackendReadiness;
using nova::deck::backend::DeckCredentialMetadata;
using nova::deck::backend::DeckCredentialStore;
using nova::deck::backend::DeckDiagnosticsModel;
using nova::deck::backend::DeckEndpointClass;
using nova::deck::backend::DeckFakeHostRepository;
using nova::deck::backend::DeckFixtureReadOnlyStateProvider;
using nova::deck::backend::DeckHostState;
using nova::deck::backend::DeckLabGate;
using nova::deck::backend::DeckLabGateMode;
using nova::deck::backend::DeckLaunchPreflightInput;
using nova::deck::backend::DeckLaunchPreflightService;
using nova::deck::backend::DeckLibraryAvailability;
using nova::deck::backend::DeckPublicBackendPreviewRequest;
using nova::deck::backend::DeckPreflightBlockerCategory;
using nova::deck::backend::DeckPreflightReport;
using nova::deck::backend::DeckSessionSummary;
using nova::deck::backend::DeckStreamSessionCoordinator;
using nova::deck::backend::buildReadOnlyHostLibraryStateMatrix;
using nova::deck::backend::requestDeckBackendDiagnosticsPreview;
using nova::deck::backend::requestDeckBackendPreflightPreview;

bool hasBlocker(const DeckPreflightReport& report, DeckPreflightBlockerCategory category) {
    return std::any_of(report.blockers.begin(), report.blockers.end(), [category](const auto& blocker) {
        return blocker.category == category;
    });
}

void requireBlocker(const DeckPreflightReport& report, DeckPreflightBlockerCategory category) {
    assert(hasBlocker(report, category));
    assert(!report.approved);
    assert(!report.coordinatorRequest.launchAllowed);
    assert(!report.coordinatorRequest.streamAllowed);
    assert(!report.publicCopy.empty());
}

DeckLaunchPreflightInput validInput() {
    return DeckLaunchPreflightInput{
        .host = nova::deck::backend::DeckHostSummary{
            .id = "host-gaming-pc",
            .displayName = "Gaming PC",
            .state = DeckHostState::Paired,
            .endpointClass = DeckEndpointClass::Manual,
            .fixtureOnly = false,
            .hasEndpointCandidate = true,
            .polarisAvailable = true,
            .standardAppListAvailable = true,
            .rawEndpointForBackendOnly = "10.0.0.42:47989",
        },
        .credentials = DeckCredentialMetadata{
            .hostId = "host-gaming-pc",
            .paired = true,
            .pinnedCertFingerprint = "sha256:ABCD1234",
            .certMismatch = false,
            .authRejected = false,
            .rawTokenForBackendOnly = "token-super-secret",
            .rawCertificateForBackendOnly = "-----BEGIN CERTIFICATE----- secret -----END CERTIFICATE-----",
            .rawPrivateKeyForBackendOnly = "[REDACTED PRIVATE KEY]",
        },
        .library = DeckLibraryAvailability{
            .available = true,
            .gameAvailable = true,
            .sourceLabel = "polaris-fixture-cache",
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
        .labGate = DeckLabGate::forMode(DeckLabGateMode::LaunchDryRun),
        .requestedGameId = "game-123",
        .requestedProfileId = "balanced-800p",
        .requestUrlForBackendOnly = "https://10.0.0.42:47989/launch?token=super-secret",
    };
}

void assertPreflightBlocksEachRequiredCategory() {
    DeckLaunchPreflightService service;

    auto fixture = validInput();
    fixture.host->fixtureOnly = true;
    requireBlocker(service.evaluate(fixture), DeckPreflightBlockerCategory::FixtureOnly);

    auto missingHost = validInput();
    missingHost.host.reset();
    requireBlocker(service.evaluate(missingHost), DeckPreflightBlockerCategory::MissingHost);

    auto pairingRequired = validInput();
    pairingRequired.credentials.paired = false;
    requireBlocker(service.evaluate(pairingRequired), DeckPreflightBlockerCategory::PairingRequired);

    auto certMismatch = validInput();
    certMismatch.credentials.certMismatch = true;
    requireBlocker(service.evaluate(certMismatch), DeckPreflightBlockerCategory::CertMismatch);

    auto authRejected = validInput();
    authRejected.credentials.authRejected = true;
    requireBlocker(service.evaluate(authRejected), DeckPreflightBlockerCategory::AuthRejected);

    auto libraryUnavailable = validInput();
    libraryUnavailable.library.available = false;
    requireBlocker(service.evaluate(libraryUnavailable), DeckPreflightBlockerCategory::LibraryUnavailable);

    auto sessionOwned = validInput();
    sessionOwned.session.ownedByAnotherClient = true;
    sessionOwned.session.watchAvailable = false;
    requireBlocker(service.evaluate(sessionOwned), DeckPreflightBlockerCategory::SessionOwnedByAnotherClient);

    auto rendererUnavailable = validInput();
    rendererUnavailable.backendReadiness.rendererAvailable = false;
    requireBlocker(service.evaluate(rendererUnavailable), DeckPreflightBlockerCategory::RendererUnavailable);

    auto audioUnavailable = validInput();
    audioUnavailable.backendReadiness.audioAvailable = false;
    requireBlocker(service.evaluate(audioUnavailable), DeckPreflightBlockerCategory::AudioUnavailable);

    auto inputUnavailable = validInput();
    inputUnavailable.backendReadiness.inputAvailable = false;
    requireBlocker(service.evaluate(inputUnavailable), DeckPreflightBlockerCategory::InputUnavailable);

    auto labGateDisabled = validInput();
    labGateDisabled.labGate = DeckLabGate::forMode(DeckLabGateMode::Disabled);
    requireBlocker(service.evaluate(labGateDisabled), DeckPreflightBlockerCategory::LabGateDisabled);
}

void assertFakeRepositoryAndMetadataFacadeStaySanitized() {
    DeckFakeHostRepository repository;
    repository.upsertFixtureHost("fixture-host", "Fixture Host");
    repository.upsertManualHostForTest("manual-host", "Manual Host", "192.168.1.77:47989");

    const auto hosts = repository.listHosts();
    assert(hosts.size() == 2);
    assert(hosts[0].id == "fixture-host");
    assert(hosts[0].fixtureOnly);
    assert(hosts[0].endpointClass == DeckEndpointClass::Unknown);
    assert(hosts[0].rawEndpointForBackendOnly.empty());
    assert(hosts[1].id == "manual-host");
    assert(hosts[1].endpointClass == DeckEndpointClass::Manual);
    assert(hosts[1].rawEndpointForBackendOnly.empty());
    assert(repository.backendEndpointForTest("manual-host") == "192.168.1.77:47989");

    DeckCredentialStore credentialStore;
    credentialStore.upsertMetadata(DeckCredentialMetadata{
        .hostId = "manual-host",
        .paired = true,
        .pinnedCertFingerprint = "sha256:ABCD1234",
        .rawTokenForBackendOnly = "token-123",
        .rawCertificateForBackendOnly = "-----BEGIN CERTIFICATE----- nope -----END CERTIFICATE-----",
        .rawPrivateKeyForBackendOnly = "[REDACTED PRIVATE KEY]",
    });
    const auto metadata = credentialStore.metadataForHost("manual-host");
    assert(metadata.has_value());
    assert(metadata->paired);
    assert(metadata->pinnedCertFingerprint == "sha256:ABCD1234");
    assert(metadata->rawTokenForBackendOnly.empty());
    assert(metadata->rawCertificateForBackendOnly.empty());
    assert(metadata->rawPrivateKeyForBackendOnly.empty());
}

void assertApprovedDryRunStillDoesNotStartNetwork() {
    DeckLaunchPreflightService service;
    const auto report = service.evaluate(validInput());
    assert(report.approved);
    assert(report.coordinatorRequest.launchAllowed);
    assert(!report.coordinatorRequest.streamAllowed);
    assert(report.coordinatorRequest.hostId == "host-gaming-pc");
    assert(report.coordinatorRequest.gameId == "game-123");

    DeckStreamSessionCoordinator coordinator;
    const auto result = coordinator.dryRun(report.coordinatorRequest);
    assert(result.accepted);
    assert(!result.networkStarted);
    assert(!result.rawStartCalled);
    assert(result.statusCode == "coordinator-dry-run-ready");
}

void assertDiagnosticsAndPreflightCopyArePrivate() {
    DeckLaunchPreflightService service;
    const auto report = service.evaluate(validInput());
    DeckDiagnosticsModel diagnostics;
    diagnostics.updatePreflight(report);
    diagnostics.updateCoordinatorStatus("coordinator-dry-run-ready");
    diagnostics.updateHost(validInput().host.value());
    diagnostics.updateCredentials(validInput().credentials);
    diagnostics.updateBackendReadiness(validInput().backendReadiness);

    const std::vector<std::string> publicCopies{
        report.publicCopy,
        diagnostics.copyText(),
    };
    const std::vector<std::string_view> forbidden{
        "token-super-secret",
        "BEGIN CERTIFICATE",
        "BEGIN PRIVATE KEY",
        "https://10.0.0.42:47989/launch?token=super-secret",
        "10.0.0.42",
        "192.168.",
        "private-hostname.local",
        "rawEndpointForBackendOnly",
        "rawTokenForBackendOnly",
    };
    for (const auto& copy : publicCopies) {
        for (const auto forbiddenToken : forbidden) {
            assert(copy.find(forbiddenToken) == std::string::npos);
        }
    }
}

void assertReadOnlyPreviewBridgeReturnsOnlyPublicBackendDtos() {
    DeckFakeHostRepository repository;
    repository.upsertManualHostForTest("manual-host", "Manual Host", "192.168.1.77:47989");

    DeckCredentialStore credentialStore;
    credentialStore.upsertMetadata(DeckCredentialMetadata{
        .hostId = "manual-host",
        .paired = true,
        .pinnedCertFingerprint = "sha256:ABCD1234",
        .rawTokenForBackendOnly = "token-super-secret",
        .rawCertificateForBackendOnly = "-----BEGIN CERTIFICATE----- secret -----END CERTIFICATE-----",
        .rawPrivateKeyForBackendOnly = "[REDACTED PRIVATE KEY]",
    });

    DeckLaunchPreflightService preflightService;
    DeckStreamSessionCoordinator coordinator;
    DeckDiagnosticsModel diagnostics;
    const DeckLabGate labGate = DeckLabGate::forMode(DeckLabGateMode::Disabled);
    const DeckPublicBackendPreviewRequest request{
        .hostId = "manual-host",
        .gameId = "game-123",
        .profileId = "balanced-800p",
    };

    const auto preflight = requestDeckBackendPreflightPreview(
        repository,
        credentialStore,
        preflightService,
        coordinator,
        labGate,
        request);
    assert(!preflight.approved);
    assert(preflight.statusCode == "backend-preflight-blocked");
    assert(preflight.publicCopy.find("Deck launch preflight") != std::string::npos);
    assert(std::find(preflight.blockerCodes.begin(), preflight.blockerCodes.end(), "lab-gate-disabled") != preflight.blockerCodes.end());
    assert(!preflight.launchDryRunAllowed);
    assert(!preflight.streamAllowed);
    assert(!preflight.backendPowerStarted);

    const auto diagnostic = requestDeckBackendDiagnosticsPreview(
        repository,
        credentialStore,
        preflightService,
        coordinator,
        diagnostics,
        labGate,
        request);
    assert(diagnostic.statusCode == "backend-diagnostics-ready");
    assert(diagnostic.copyText.find("privacy=redacted") != std::string::npos);

    const std::vector<std::string> publicCopies{
        preflight.publicCopy,
        diagnostic.copyText,
    };
    for (const auto& copy : publicCopies) {
        for (const auto forbiddenToken : std::vector<std::string_view>{
                 "token-super-secret",
                 "BEGIN CERTIFICATE",
                 "BEGIN PRIVATE KEY",
                 "192.168.",
                 "rawEndpointForBackendOnly",
                 "rawTokenForBackendOnly",
             }) {
            assert(copy.find(forbiddenToken) == std::string::npos);
        }
    }
}

void assertReadOnlyHostLibraryStateComesFromBackendSummaries() {
    const auto library = nova::deck::loadSamplePolarisGameLibraryFixture();
    DeckFakeHostRepository repository;
    repository.upsertSanitizedHostSummary(nova::deck::backend::DeckHostSummary{
        .id = "host-snapshot-primary",
        .displayName = "Polaris Snapshot Primary",
        .state = DeckHostState::Fixture,
        .endpointClass = DeckEndpointClass::Unknown,
        .fixtureOnly = true,
        .hasEndpointCandidate = false,
        .polarisAvailable = true,
        .standardAppListAvailable = true,
        .publicStatusLabel = "Backend-owned read-only summary · fixture provenance",
        .publicSubtitle = "Backend read-only host summary — no discovery, join-flow, endpoint, cert, or private material was read.",
        .publicProvenanceLabel = "fixture/read-only/backend-owned",
        .rawEndpointForBackendOnly = "10.0.0.42:47989",
    });

    DeckLaunchPreflightService preflightService;
    const auto state = buildReadOnlyHostLibraryState(
        repository,
        library,
        preflightService,
        DeckLabGate::forMode(DeckLabGateMode::Disabled));

    assert(state.sourceLabel == "Shared Polaris contract fixture");
    assert(state.readOnly);
    assert(state.hosts.size() == 1);
    assert(state.hosts[0].id == "host-snapshot-primary");
    assert(state.hosts[0].displayName == "Polaris Snapshot Primary");
    assert(state.hosts[0].statusLabel == "Backend-owned read-only summary · fixture provenance");
    assert(state.hosts[0].subtitle == "Backend read-only host summary — no discovery, join-flow, endpoint, cert, or private material was read.");
    assert(state.hosts[0].provenanceLabel == "fixture/read-only/backend-owned");
    assert(state.hosts[0].initialFocus);
    assert(state.games.size() == 2);
    assert(state.games[0].id == "game-123");
    assert(state.games[0].title == "Portal 2");
    assert(state.games[0].sourceRuntimeLabel == "Steam · Linux · Proton");
    assert(state.games[0].launchModeLabel == "Stream: headless · Steam: direct");
    assert(state.preflight.statusCode == "backend-read-only-preflight-blocked");
    assert(std::find(state.preflight.blockerCodes.begin(), state.preflight.blockerCodes.end(), "lab-gate-disabled") != state.preflight.blockerCodes.end());
    assert(std::find(state.preflight.blockerCodes.begin(), state.preflight.blockerCodes.end(), "fixture-only") != state.preflight.blockerCodes.end());
    assert(state.preflight.publicCopy.find("Lab gate is disabled") != std::string::npos);
    assert(state.preflight.publicCopy.find("Fixture provenance only") != std::string::npos);
    assert(state.preflight.publicCopy.find("Host is unpaired") != std::string::npos);
    assert(!state.preflight.launchDryRunAllowed);
    assert(!state.preflight.streamAllowed);
    assert(!state.preflight.backendPowerStarted);
    assert(state.playerState.title == "Product state: Lab gate locked");
    assert(state.playerState.body == "Launch blocked by lab gate.");
    assert(state.playerState.actionLabel == "Ask an operator to open the lab gate before any start path.");
    assert(state.playerState.safetyLabel == "Backend power, launch, stream, discovery, and media stay off.");
    assert(state.playerState.provenanceLabel == "dto-player-state/backend-owned/redacted-public");
    assert(state.playerState.focusOrder == "state-card-copy-diagnostics");
    assert(state.playerState.focusOrderCopy == "Focus order: state card → Copy plan → Show diagnostics");
    assert(state.dtoParity.contractId == "backend-owned-read-only-dto-v1");
    assert(state.dtoParity.ownerCode == "backend-owned-read-only-model");
    assert(state.dtoParity.privacyCode == "redacted-public-dto");
    assert(state.dtoParity.readinessCode == "dto-parity-ready");
    assert(state.dtoParity.collapsedSummary.find("Backend-owned DTO parity") != std::string::npos);
    assert(state.dtoParity.collapsedSummary.find(state.preflight.statusCode) != std::string::npos);
    assert(state.dtoParity.expandedDiagnostics.find("backend-owned-read-only-dto-v1") != std::string::npos);
    assert(state.dtoParity.expandedDiagnostics.find("privacy=redacted-public-dto") != std::string::npos);
    assert(state.dtoParity.artifactSummary.find("backendPowerStarted=false") != std::string::npos);
    assert(state.dtoParity.artifactSummary.find("stream=false") != std::string::npos);

    const std::vector<std::string> publicStateCopies{
        state.hosts[0].statusLabel,
        state.hosts[0].subtitle,
        state.preflight.publicCopy,
        state.dtoParity.collapsedSummary,
        state.dtoParity.expandedDiagnostics,
        state.dtoParity.artifactSummary,
    };
    for (const auto& copy : publicStateCopies) {
        for (const auto forbiddenToken : std::vector<std::string_view>{
                 "10.0.0.42",
                 "47989",
                 "BEGIN CERTIFICATE",
                 "token-super-secret",
                 "rawEndpointForBackendOnly",
             }) {
            assert(copy.find(forbiddenToken) == std::string::npos);
        }
    }
}

void assertReadOnlyHostLibraryStateMatrixCoversDeterministicBlockers() {
    const auto library = nova::deck::loadSamplePolarisGameLibraryFixture();
    DeckLaunchPreflightService preflightService;
    const auto matrix = buildReadOnlyHostLibraryStateMatrix(library, preflightService);

    assert(matrix.size() == 5);

    auto stateByScenario = [&matrix](const std::string_view scenarioId) -> const nova::deck::backend::DeckPublicReadOnlyHostLibraryState& {
        const auto found = std::find_if(matrix.begin(), matrix.end(), [scenarioId](const auto& state) {
            return state.scenarioId == scenarioId;
        });
        assert(found != matrix.end());
        return *found;
    };
    auto hasPublicBlocker = [](const auto& state, const std::string_view blocker) {
        return std::find(state.preflight.blockerCodes.begin(), state.preflight.blockerCodes.end(), blocker) != state.preflight.blockerCodes.end();
    };

    const auto& empty = stateByScenario("empty");
    assert(empty.scenarioLabel.find("Ready for setup") != std::string::npos);
    assert(empty.hosts.empty());
    assert(empty.games.empty());
    assert(hasPublicBlocker(empty, "missing-host"));
    assert(hasPublicBlocker(empty, "library-unavailable"));
    assert(empty.preflight.publicCopy.find("source=backend-owned-read-only-matrix") != std::string::npos);

    const auto& offline = stateByScenario("offline");
    assert(!offline.hosts.empty());
    assert(offline.hosts[0].statusLabel.find("offline") != std::string::npos);
    assert(hasPublicBlocker(offline, "host-unreachable"));
    assert(!offline.preflight.backendPowerStarted);

    const auto& unpaired = stateByScenario("unpaired");
    assert(!unpaired.hosts.empty());
    assert(unpaired.hosts[0].statusLabel.find("unpaired") != std::string::npos);
    assert(hasPublicBlocker(unpaired, "pairing-required"));
    assert(unpaired.preflight.publicCopy.find("credential reads stay disabled") != std::string::npos);

    const auto& libraryUnavailable = stateByScenario("library-unavailable");
    assert(!libraryUnavailable.hosts.empty());
    assert(libraryUnavailable.games.empty());
    assert(hasPublicBlocker(libraryUnavailable, "library-unavailable"));
    assert(libraryUnavailable.preflight.publicCopy.find("Backend library summary is unavailable") != std::string::npos);

    const auto& labGated = stateByScenario("lab-gated");
    assert(!labGated.hosts.empty());
    assert(!labGated.games.empty());
    assert(hasPublicBlocker(labGated, "lab-gate-disabled"));
    assert(hasPublicBlocker(labGated, "network-disabled"));
    assert(labGated.preflight.publicCopy.find("backendPowerStarted=false") != std::string::npos);

    assert(empty.scenarioLabel == "Ready for setup · no host or game selected");
    assert(empty.playerState.title == "Product state: Ready for setup");
    assert(empty.playerState.body == "Select a host and game to continue.");
    assert(empty.playerState.actionLabel == "Add a backend host before previewing a launch plan.");
    assert(offline.scenarioLabel == "Host offline · reconnect before preview");
    assert(offline.playerState.title == "Product state: Host offline");
    assert(offline.playerState.body == "Host offline. Reconnect or pick another host.");
    assert(offline.playerState.actionLabel == "Reconnect the host or choose another backend-owned snapshot.");
    assert(offline.playerState.safetyLabel == "Backend power stays off; no retry or network probe runs.");
    assert(unpaired.scenarioLabel == "Pair host · approved pairing required");
    assert(unpaired.playerState.title == "Product state: Pair host");
    assert(unpaired.playerState.body == "Pair this host before launch preview.");
    assert(libraryUnavailable.scenarioLabel == "Library unavailable · read-only snapshot missing");
    assert(libraryUnavailable.playerState.title == "Product state: Library unavailable");
    assert(libraryUnavailable.playerState.body == "Library unavailable. Try again when the read-only snapshot is back.");
    assert(labGated.scenarioLabel == "Lab gate locked · start paths disabled");
    assert(labGated.playerState.title == "Product state: Lab gate locked");

    for (const auto& state : matrix) {
        assert(state.readOnly);
        assert(!state.scenarioId.empty());
        assert(!state.scenarioLabel.empty());
        assert(!state.preflight.launchDryRunAllowed);
        assert(!state.preflight.streamAllowed);
        assert(!state.preflight.backendPowerStarted);
        assert(state.sourceLabel.find("read-only") != std::string::npos);
        assert(state.dtoParity.contractId == "backend-owned-read-only-dto-v1");
        assert(state.dtoParity.ownerCode == "backend-owned-read-only-model");
        assert(state.dtoParity.privacyCode == "redacted-public-dto");
        assert(state.dtoParity.readinessCode == "dto-parity-ready");
        assert(state.dtoParity.collapsedSummary.find(state.preflight.statusCode) != std::string::npos);
        assert(state.dtoParity.expandedDiagnostics.find(state.scenarioId) != std::string::npos);
        assert(state.dtoParity.artifactSummary.find("backendPowerStarted=false") != std::string::npos);
        assert(state.dtoParity.artifactSummary.find("stream=false") != std::string::npos);
        assert(!state.playerState.title.empty());
        assert(!state.playerState.body.empty());
        assert(!state.playerState.actionLabel.empty());
        assert(!state.playerState.safetyLabel.empty());
        assert(state.playerState.provenanceLabel == "dto-player-state/backend-owned/redacted-public");
        assert(state.playerState.focusOrder == "state-card-copy-diagnostics");
        assert(state.playerState.focusOrderCopy == "Focus order: state card → Copy plan → Show diagnostics");
        for (const auto& copy : std::vector<std::string>{
                 state.sourceLabel,
                 state.scenarioLabel,
                 state.playerState.title,
                 state.playerState.body,
                 state.playerState.actionLabel,
                 state.playerState.safetyLabel,
                 state.playerState.provenanceLabel,
                 state.playerState.focusOrderCopy,
                 state.preflight.publicCopy,
                 state.dtoParity.collapsedSummary,
                 state.dtoParity.expandedDiagnostics,
                 state.dtoParity.artifactSummary,
             }) {
            for (const auto forbiddenToken : std::vector<std::string_view>{
                     "10.0.0.",
                     "192.168.",
                     "47989",
                     "BEGIN CERTIFICATE",
                     "token-super-secret",
                     "rawEndpointForBackendOnly",
                 }) {
                assert(copy.find(forbiddenToken) == std::string::npos);
            }
        }
    }
}

void assertReadOnlyProviderOwnsStateAssemblyAndMatrixParity() {
    const auto library = nova::deck::loadSamplePolarisGameLibraryFixture();
    DeckLaunchPreflightService preflightService;
    DeckFixtureReadOnlyStateProvider provider(library, preflightService);

    const auto matrix = provider.stateMatrix();
    const auto legacyMatrix = buildReadOnlyHostLibraryStateMatrix(library, preflightService);
    assert(matrix.size() == legacyMatrix.size());
    assert(matrix.size() == 5);

    const auto selected = provider.stateForScenario("lab-gated");
    assert(selected.scenarioId == "lab-gated");
    assert(selected.scenarioLabel == "Lab gate locked · start paths disabled");
    assert(!selected.hosts.empty());
    assert(!selected.games.empty());
    assert(!selected.preflight.backendPowerStarted);
    assert(selected.playerState.title == "Product state: Lab gate locked");
    assert(selected.playerState.focusOrder == "state-card-copy-diagnostics");
    assert(selected.playerState.focusOrderCopy == "Focus order: state card → Copy plan → Show diagnostics");
    assert(selected.dtoParity.ownerCode == "backend-owned-read-only-model");

    const auto fallback = provider.stateForScenario("missing-scenario");
    assert(fallback.scenarioId == "lab-gated");

    for (std::size_t index = 0; index < matrix.size(); ++index) {
        assert(matrix[index].scenarioId == legacyMatrix[index].scenarioId);
        assert(matrix[index].scenarioLabel == legacyMatrix[index].scenarioLabel);
        assert(matrix[index].hosts.size() == legacyMatrix[index].hosts.size());
        assert(matrix[index].games.size() == legacyMatrix[index].games.size());
        assert(matrix[index].preflight.statusCode == legacyMatrix[index].preflight.statusCode);
        assert(matrix[index].preflight.blockerCodes == legacyMatrix[index].preflight.blockerCodes);
        assert(matrix[index].playerState.title == legacyMatrix[index].playerState.title);
        assert(matrix[index].playerState.focusOrder == legacyMatrix[index].playerState.focusOrder);
        assert(matrix[index].playerState.focusOrderCopy == legacyMatrix[index].playerState.focusOrderCopy);
        assert(matrix[index].dtoParity.contractId == legacyMatrix[index].dtoParity.contractId);
    }
}

void assertReadOnlyProviderDefaultsMissingPlayerStateAndCopiesFocusOrder() {
    const auto library = nova::deck::loadSamplePolarisGameLibraryFixture();
    DeckLaunchPreflightService preflightService;
    DeckFixtureReadOnlyStateProvider provider(library, preflightService);

    auto state = provider.stateForScenario("offline");
    state.playerState = nova::deck::backend::DeckPublicReadOnlyPlayerState{};
    const auto repaired = provider.withDefaultPlayerState(state);

    assert(repaired.scenarioId == "offline");
    assert(repaired.playerState.title == "Product state: Host offline");
    assert(repaired.playerState.body == "Host offline. Reconnect or pick another host.");
    assert(repaired.playerState.actionLabel == "Reconnect the host or choose another backend-owned snapshot.");
    assert(repaired.playerState.safetyLabel == "Backend power stays off; no retry or network probe runs.");
    assert(repaired.playerState.provenanceLabel == "dto-player-state/backend-owned/redacted-public");
    assert(repaired.playerState.focusOrder == "state-card-copy-diagnostics");
    assert(repaired.playerState.focusOrderCopy == "Focus order: state card → Copy plan → Show diagnostics");
}

} // namespace

int main() {
    assertFakeRepositoryAndMetadataFacadeStaySanitized();
    assertPreflightBlocksEachRequiredCategory();
    assertApprovedDryRunStillDoesNotStartNetwork();
    assertDiagnosticsAndPreflightCopyArePrivate();
    assertReadOnlyPreviewBridgeReturnsOnlyPublicBackendDtos();
    assertReadOnlyHostLibraryStateComesFromBackendSummaries();
    assertReadOnlyHostLibraryStateMatrixCoversDeterministicBlockers();
    assertReadOnlyProviderOwnsStateAssemblyAndMatrixParity();
    assertReadOnlyProviderDefaultsMissingPlayerStateAndCopiesFocusOrder();
    return 0;
}
