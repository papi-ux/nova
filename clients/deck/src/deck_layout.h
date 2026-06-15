#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace nova::deck {

struct PolarisGameFixture;
struct PolarisGameLibraryFixture;

struct DeckWindowProfile {
    int width;
    int height;
    bool fullscreenPreferred;
    std::string_view shellName;
};

struct DeckFocusTarget {
    std::string_view id;
    std::string_view label;
    int row;
    int column;
    bool initialFocus;
};

struct DeckHostListItem {
    std::string_view id;
    std::string_view displayName;
    std::string_view statusLabel;
    int row;
    bool initialFocus;
};

struct DeckHostDetail {
    std::string_view id;
    std::string_view displayName;
    std::string_view statusLabel;
    std::string_view subtitle;
};

struct DeckLibraryGameCard {
    std::string id;
    std::string title;
    std::string sourceRuntimeLabel;
    std::string launchModeLabel;
    std::string installedLabel;
    int row = 0;
    bool initialFocus = false;
};

struct DeckLaunchCta {
    std::string_view id;
    std::string_view label;
    std::string_view helpText;
    std::string previewStateLabel;
    std::string previewText;
    bool enabled;
};

enum class DeckLaunchIntentBoundaryKind {
    PreviewOnly,
};

struct DeckLaunchIntentBoundary {
    DeckLaunchIntentBoundaryKind kind = DeckLaunchIntentBoundaryKind::PreviewOnly;
    std::string id;
    std::string label;
    std::string reason;
    bool previewOnly = true;
    bool allowsNetwork = false;
    bool allowsProcessExecution = false;
    bool allowsMoonlight = false;
    bool allowsHostMutation = false;
};

enum class DeckHostAddressClass {
    DemoOnly,
    SnapshotOnly,
    UnknownUnavailable,
};

enum class DeckGameIdentityKind {
    SteamApp,
    LibraryFixture,
    Unknown,
};

enum class DeckLaunchMode {
    SteamDirect,
    SteamBigPicture,
    UnsupportedPreview,
};

enum class DeckPreflightState {
    ReadyPreview,
    HostUnavailable,
    HostBusy,
    PairingNeeded,
    UnsupportedLaunchMode,
};

enum class DeckPreviewRedactionPolicy {
    PublicSafe,
    LocalPrivateRedacted,
};

struct DeckHostIdentity {
    std::string id;
    std::string displayName;
    DeckHostAddressClass addressClass = DeckHostAddressClass::DemoOnly;
    std::string addressLabel;
};

struct DeckGameIdentity {
    DeckGameIdentityKind identityKind = DeckGameIdentityKind::Unknown;
    std::string libraryId;
    std::string title;
    int appId = 0;
    std::string steamAppId;
};

struct DeckStreamProfilePreview {
    std::string id;
    std::string displayName;
    bool virtualDisplayRecommended = false;
    bool headlessRecommended = false;
};

struct DeckPreflightFailureState {
    DeckPreflightState state = DeckPreflightState::ReadyPreview;
    std::string reason;
};

struct DeckPreviewPrivacyPolicy {
    DeckPreviewRedactionPolicy redactionPolicy = DeckPreviewRedactionPolicy::PublicSafe;
    bool publicSafeCopyOnly = true;
    bool localPrivateArtRedacted = true;
};

struct DeckPreviewSafetyBooleans {
    bool allowsNetwork = false;
    bool allowsProcessExecution = false;
    bool allowsMoonlight = false;
    bool allowsHostMutation = false;
    bool executable = false;
};

struct DeckLaunchIntent {
    std::string targetHostId;
    std::string targetHostName;
    std::string sampleGameId;
    std::string gameTitle;
    std::string streamLaunchMode;
    std::string steamLaunchMode;
    DeckLaunchIntentBoundary boundary;
    bool executable = false;
    std::string safetyLabel;
    DeckHostIdentity host;
    DeckGameIdentity game;
    DeckLaunchMode launchMode = DeckLaunchMode::UnsupportedPreview;
    DeckStreamProfilePreview streamProfile;
    DeckPreflightFailureState preflight;
    DeckPreviewPrivacyPolicy privacy;
    DeckPreviewSafetyBooleans safety;
    std::string publicPreviewCopy;
    std::string inertPreviewUri;
};

enum class DeckStreamProvider {
    PreviewOnly,
};

enum class DeckStreamAction {
    NoopPreview,
};

enum class DeckStreamSessionState {
    NotStarted,
};

enum class DeckStreamLifecycle {
    PreflightOnly,
};

enum class DeckStreamRecovery {
    UserReviewRequired,
};

struct DeckStreamSessionPreview {
    DeckStreamSessionState state = DeckStreamSessionState::NotStarted;
    std::string reason;
};

struct DeckStreamIntent {
    DeckStreamProvider provider = DeckStreamProvider::PreviewOnly;
    DeckStreamAction action = DeckStreamAction::NoopPreview;
    DeckStreamSessionPreview session;
    DeckStreamLifecycle lifecycle = DeckStreamLifecycle::PreflightOnly;
    DeckStreamRecovery recovery = DeckStreamRecovery::UserReviewRequired;
    DeckPreviewPrivacyPolicy privacy;
    DeckPreviewSafetyBooleans safety;
    std::string publicCopy;
};

struct DeckLaunchPreview {
    std::string text;
    std::string stateLabel;
    std::string boundaryId;
    std::string boundaryLabel;
    bool copyOnly = true;
    bool executable = false;
    bool networkAllowed = false;
    bool processExecutionAllowed = false;
    bool moonlightAllowed = false;
    bool hostMutationAllowed = false;
};

struct DeckLaunchPreviewCopyAction {
    std::string_view id;
    std::string_view label;
    std::string previewText;
    std::string idleStatusLabel;
    std::string successToast;
    std::string inertToast;
    bool enabled = false;
    bool copyOnly = true;
    bool uiLocalClipboardOnly = true;
    bool executable = false;
};

struct DeckLaunchPreviewCopyResult {
    std::string previewText;
    std::string statusLabel;
    std::string toastLabel;
    bool copied = false;
};

struct DeckLaunchPreviewBinding {
    std::string selectedHostId;
    std::string selectedHostName;
    std::string selectedGameId;
    std::string selectedGameTitle;
    DeckHostDetail hostDetail;
    DeckLibraryGameCard gameCard;
    DeckLaunchIntent intent;
    DeckLaunchPreview preview;
    DeckLaunchCta launchCta;
    DeckLaunchPreviewCopyAction copyAction;
};

class DeckLocalClipboard {
public:
    virtual ~DeckLocalClipboard() = default;
    virtual bool publishPreviewText(std::string_view value) = 0;
};

enum class DeckFocusDirection {
    Left,
    Right,
    Up,
    Down,
};

DeckWindowProfile defaultWindowProfile();

std::vector<DeckFocusTarget> defaultLibraryFocusTargets();

std::string_view initialLibraryFocusTarget(const std::vector<DeckFocusTarget>& targets);

std::string_view nextLibraryFocusTarget(
    const std::vector<DeckFocusTarget>& targets,
    std::string_view currentId,
    DeckFocusDirection direction);

std::vector<DeckHostListItem> emptyHostListState();

std::vector<DeckHostListItem> demoHostListState();

std::vector<DeckHostListItem> libraryHostListStateFor(const PolarisGameLibraryFixture& library);

std::vector<DeckLibraryGameCard> libraryGameCardsFor(const PolarisGameLibraryFixture& library);

std::string_view initialLibraryGameFocusTarget(const std::vector<DeckLibraryGameCard>& games);

std::string_view nextLibraryGameFocusTarget(
    const std::vector<DeckLibraryGameCard>& games,
    std::string_view currentId,
    DeckFocusDirection direction);

std::string_view initialHostFocusTarget(const std::vector<DeckHostListItem>& hosts);

std::string_view nextHostFocusTarget(
    const std::vector<DeckHostListItem>& hosts,
    std::string_view currentId,
    DeckFocusDirection direction);

DeckHostDetail resolveHostDetail(const std::vector<DeckHostListItem>& hosts, std::string_view hostId);

DeckLaunchIntentBoundary previewOnlyLaunchIntentBoundary();

DeckLaunchIntent resolveLaunchIntent(const DeckHostDetail& detail, const PolarisGameFixture& game);

DeckStreamIntent resolveStreamIntent(const DeckLaunchIntent& intent);

DeckLaunchPreviewBinding resolveLaunchPreviewBinding(
    const std::vector<DeckHostListItem>& hosts,
    const PolarisGameLibraryFixture& library,
    std::string_view selectedHostId,
    std::string_view selectedGameId);

bool canExecuteLaunchIntent(const DeckLaunchIntent& intent);

DeckLaunchPreview fakeLaunchCommandPreviewFor(const DeckLaunchIntent& intent);

DeckLaunchPreviewCopyAction copyLaunchPreviewActionFor(const DeckLaunchPreview& preview);

DeckLaunchPreviewCopyResult activateLaunchPreviewCopy(const DeckLaunchPreviewCopyAction& action);

DeckLaunchPreviewCopyResult copyLaunchPreviewToLocalClipboard(
    const DeckLaunchPreviewCopyAction& action,
    DeckLocalClipboard& clipboard);

DeckLaunchCta inertLaunchCtaFor(const DeckHostDetail& detail, const PolarisGameFixture& game);
DeckLaunchCta inertLaunchCtaFor(const DeckHostDetail& detail);

std::vector<DeckFocusTarget> hostDetailFocusTargets(
    const DeckHostDetail& detail,
    const DeckLaunchCta& launchCta,
    const DeckLaunchPreviewCopyAction& copyAction);

std::string_view nextHostDetailFocusTarget(
    const std::vector<DeckFocusTarget>& targets,
    std::string_view currentId,
    DeckFocusDirection direction);

bool isDeckNativeAspect(int width, int height);

} // namespace nova::deck
