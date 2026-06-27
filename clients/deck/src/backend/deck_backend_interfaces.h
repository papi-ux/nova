#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace nova::deck {
struct PolarisGameLibraryFixture;
} // namespace nova::deck

namespace nova::deck::backend {

enum class DeckEndpointClass {
    Unknown,
    Local,
    Manual,
    Discovered,
};

enum class DeckHostState {
    Fixture,
    Manual,
    Discovered,
    Offline,
    Online,
    PairingNeeded,
    Paired,
    CertMismatch,
    AuthRejected,
    Unsupported,
};

struct DeckHostSummary {
    std::string id;
    std::string displayName;
    DeckHostState state = DeckHostState::Fixture;
    DeckEndpointClass endpointClass = DeckEndpointClass::Unknown;
    bool fixtureOnly = true;
    bool hasEndpointCandidate = false;
    bool polarisAvailable = false;
    bool standardAppListAvailable = false;
    std::string publicStatusLabel;
    std::string publicSubtitle;
    std::string publicProvenanceLabel;

    // Backend-only test seam. Public models returned from DeckHostRepository clear this field.
    std::string rawEndpointForBackendOnly;
};

struct DeckCredentialMetadata {
    std::string hostId;
    bool paired = false;
    std::string pinnedCertFingerprint;
    bool certMismatch = false;
    bool authRejected = false;

    // Backend-only test seams. Public metadata facade clears these fields.
    std::string rawTokenForBackendOnly;
    std::string rawCertificateForBackendOnly;
    std::string rawPrivateKeyForBackendOnly;
};

struct DeckLibraryAvailability {
    bool available = false;
    bool gameAvailable = false;
    std::string sourceLabel;
};

struct DeckSessionSummary {
    bool ownedByAnotherClient = false;
    bool watchAvailable = false;
};

struct DeckBackendReadiness {
    bool rendererAvailable = false;
    bool audioAvailable = false;
    bool inputAvailable = false;
};

enum class DeckLabGateMode {
    Disabled,
    ReadOnlyNetwork,
    PairingLab,
    LaunchDryRun,
    LaunchLab,
    StreamLab,
};

class DeckLabGate {
public:
    static DeckLabGate forMode(DeckLabGateMode mode);

    [[nodiscard]] DeckLabGateMode mode() const;
    [[nodiscard]] std::string modeLabel() const;
    [[nodiscard]] bool networkReadAllowed() const;
    [[nodiscard]] bool pairingAllowed() const;
    [[nodiscard]] bool launchDryRunAllowed() const;
    [[nodiscard]] bool hostLaunchAllowed() const;
    [[nodiscard]] bool streamStartAllowed() const;

private:
    explicit DeckLabGate(DeckLabGateMode mode);

    DeckLabGateMode mode_ = DeckLabGateMode::Disabled;
};

enum class DeckPreflightBlockerCategory {
    FixtureOnly,
    NetworkDisabled,
    MissingHost,
    HostUnreachable,
    PairingRequired,
    CertMismatch,
    AuthRejected,
    LibraryUnavailable,
    AppNotFound,
    SessionOwnedByAnotherClient,
    WatchNotAvailable,
    LaunchNotAllowed,
    LabGateDisabled,
    RendererUnavailable,
    AudioUnavailable,
    InputUnavailable,
};

struct DeckPreflightBlocker {
    DeckPreflightBlockerCategory category = DeckPreflightBlockerCategory::MissingHost;
    std::string code;
    std::string publicReason;
};

struct DeckCoordinatorRequest {
    std::string hostId;
    std::string gameId;
    std::string profileId;
    bool launchAllowed = false;
    bool streamAllowed = false;
    std::string publicPlan;
};

struct DeckPreflightReport {
    bool approved = false;
    std::vector<DeckPreflightBlocker> blockers;
    DeckCoordinatorRequest coordinatorRequest;
    std::string publicCopy;
};

struct DeckLaunchPreflightInput {
    std::optional<DeckHostSummary> host;
    DeckCredentialMetadata credentials;
    DeckLibraryAvailability library;
    DeckSessionSummary session;
    DeckBackendReadiness backendReadiness;
    DeckLabGate labGate = DeckLabGate::forMode(DeckLabGateMode::Disabled);
    std::string requestedGameId;
    std::string requestedProfileId;

    // Backend-only test seam; never copied into public reports.
    std::string requestUrlForBackendOnly;
};

class DeckHostRepository {
public:
    virtual ~DeckHostRepository() = default;
    [[nodiscard]] virtual std::vector<DeckHostSummary> listHosts() const = 0;
    [[nodiscard]] virtual std::optional<DeckHostSummary> hostById(std::string_view hostId) const = 0;
};

class DeckFakeHostRepository final : public DeckHostRepository {
public:
    void upsertFixtureHost(std::string id, std::string displayName);
    void upsertSanitizedHostSummary(DeckHostSummary host);
    void upsertManualHostForTest(std::string id, std::string displayName, std::string rawEndpoint);

    [[nodiscard]] std::vector<DeckHostSummary> listHosts() const override;
    [[nodiscard]] std::optional<DeckHostSummary> hostById(std::string_view hostId) const override;
    [[nodiscard]] std::string backendEndpointForTest(std::string_view hostId) const;

private:
    std::vector<DeckHostSummary> hosts_;
};

class DeckCredentialStore {
public:
    void upsertMetadata(DeckCredentialMetadata metadata);
    [[nodiscard]] std::optional<DeckCredentialMetadata> metadataForHost(std::string_view hostId) const;

private:
    std::vector<DeckCredentialMetadata> metadata_;
};

class DeckLaunchPreflightService {
public:
    [[nodiscard]] DeckPreflightReport evaluate(const DeckLaunchPreflightInput& input) const;
};

struct DeckCoordinatorResult {
    bool accepted = false;
    bool networkStarted = false;
    bool rawStartCalled = false;
    std::string statusCode;
    std::string publicCopy;
};

class DeckStreamSessionCoordinator {
public:
    [[nodiscard]] DeckCoordinatorResult dryRun(const DeckCoordinatorRequest& request) const;
};

class DeckDiagnosticsModel {
public:
    void updateHost(const DeckHostSummary& host);
    void updateCredentials(const DeckCredentialMetadata& credentials);
    void updateBackendReadiness(const DeckBackendReadiness& readiness);
    void updatePreflight(const DeckPreflightReport& report);
    void updateCoordinatorStatus(std::string statusCode);

    [[nodiscard]] std::string copyText() const;

private:
    std::string hostCategory_ = "host=unknown";
    std::string trustCategory_ = "trust=unknown";
    std::string backendCategory_ = "backend=unknown";
    std::string preflightCategory_ = "preflight=unknown";
    std::string coordinatorStatus_ = "coordinator=idle";
};

struct DeckPublicBackendPreviewRequest {
    std::string hostId;
    std::string gameId;
    std::string profileId;
};

struct DeckPublicPreflightPreview {
    std::string statusCode;
    bool approved = false;
    std::vector<std::string> blockerCodes;
    bool launchDryRunAllowed = false;
    bool streamAllowed = false;
    bool backendPowerStarted = false;
    std::string publicCopy;
};

struct DeckPublicDiagnosticsPreview {
    std::string statusCode;
    std::string privacyCode;
    std::string copyText;
};

struct DeckPublicReadOnlyHostItem {
    std::string id;
    std::string displayName;
    std::string statusLabel;
    std::string subtitle;
    std::string provenanceLabel;
    bool initialFocus = false;
};

struct DeckPublicReadOnlyGameItem {
    std::string id;
    std::string title;
    std::string sourceRuntimeLabel;
    std::string launchModeLabel;
    std::string installedLabel;
    bool initialFocus = false;
};

struct DeckPublicReadOnlyPreflightState {
    std::string statusCode;
    std::vector<std::string> blockerCodes;
    bool launchDryRunAllowed = false;
    bool streamAllowed = false;
    bool backendPowerStarted = false;
    std::string publicCopy;
};

struct DeckPublicReadOnlyDtoParity {
    std::string contractId;
    std::string ownerCode;
    std::string privacyCode;
    std::string readinessCode;
    std::string collapsedSummary;
    std::string expandedDiagnostics;
    std::string artifactSummary;
};

struct DeckPublicReadOnlyPlayerState {
    std::string title;
    std::string body;
    std::string actionLabel;
    std::string safetyLabel;
    std::string provenanceLabel;
    std::string focusOrder;
    std::string focusOrderCopy;
};

struct DeckPublicReadOnlyHostLibraryState {
    std::string scenarioId;
    std::string scenarioLabel;
    std::string sourceLabel;
    bool readOnly = true;
    std::vector<DeckPublicReadOnlyHostItem> hosts;
    std::vector<DeckPublicReadOnlyGameItem> games;
    DeckPublicReadOnlyPreflightState preflight;
    DeckPublicReadOnlyPlayerState playerState;
    DeckPublicReadOnlyDtoParity dtoParity;
};

class DeckReadOnlyStateProvider {
public:
    virtual ~DeckReadOnlyStateProvider() = default;
    [[nodiscard]] virtual std::vector<DeckPublicReadOnlyHostLibraryState> stateMatrix() const = 0;
    [[nodiscard]] virtual DeckPublicReadOnlyHostLibraryState stateForScenario(std::string_view scenarioId) const = 0;
};

class DeckFixtureReadOnlyStateProvider final : public DeckReadOnlyStateProvider {
public:
    DeckFixtureReadOnlyStateProvider(
        const PolarisGameLibraryFixture& library,
        const DeckLaunchPreflightService& preflightService);

    [[nodiscard]] std::vector<DeckPublicReadOnlyHostLibraryState> stateMatrix() const override;
    [[nodiscard]] DeckPublicReadOnlyHostLibraryState stateForScenario(std::string_view scenarioId) const override;
    [[nodiscard]] DeckPublicReadOnlyHostLibraryState withDefaultPlayerState(DeckPublicReadOnlyHostLibraryState state) const;

private:
    std::vector<DeckPublicReadOnlyHostLibraryState> matrix_;
};

[[nodiscard]] DeckPublicPreflightPreview requestDeckBackendPreflightPreview(
    const DeckHostRepository& repository,
    const DeckCredentialStore& credentialStore,
    const DeckLaunchPreflightService& preflightService,
    const DeckStreamSessionCoordinator& coordinator,
    const DeckLabGate& labGate,
    const DeckPublicBackendPreviewRequest& request);

[[nodiscard]] DeckPublicDiagnosticsPreview requestDeckBackendDiagnosticsPreview(
    const DeckHostRepository& repository,
    const DeckCredentialStore& credentialStore,
    const DeckLaunchPreflightService& preflightService,
    const DeckStreamSessionCoordinator& coordinator,
    DeckDiagnosticsModel& diagnostics,
    const DeckLabGate& labGate,
    const DeckPublicBackendPreviewRequest& request);

[[nodiscard]] DeckPublicReadOnlyHostLibraryState buildReadOnlyHostLibraryState(
    const DeckHostRepository& repository,
    const PolarisGameLibraryFixture& library,
    const DeckLaunchPreflightService& preflightService,
    const DeckLabGate& labGate);

[[nodiscard]] std::vector<DeckPublicReadOnlyHostLibraryState> buildReadOnlyHostLibraryStateMatrix(
    const PolarisGameLibraryFixture& library,
    const DeckLaunchPreflightService& preflightService);

[[nodiscard]] std::string toPublicCode(DeckPreflightBlockerCategory category);

} // namespace nova::deck::backend
