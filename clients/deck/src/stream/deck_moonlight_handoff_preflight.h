#pragma once

#include <string>
#include <vector>

namespace nova::deck::stream {

enum class DeckMoonlightHandoffSurface {
    MoonlightQtCli,
    HostAppSnapshot,
    DesktopEntry,
    FlatpakIdentity,
    SteamShortcut,
    CustomUri,
    NovaOwnedCommonCFuture,
    Unsupported,
};

enum class DeckMoonlightHandoffVerdict {
    ReadyForReview,
    BlockedStatic,
    ForbiddenRuntimeBoundary,
};

enum class DeckMoonlightHandoffBlockReason {
    MissingHost,
    MissingGame,
    UnsupportedSurface,
    HostSnapshotMissing,
    HostPairingUnprovenStatic,
    AppNotInSnapshot,
    UnsafePublicCopy,
    UnsafeArgvToken,
    CustomUriNotStreamHandler,
    DesktopEntryNotStreamContract,
    FlatpakContractUnproven,
    SteamShortcutRuntimeOnly,
    FocusReturnUnprovenStatic,
    ResearchNeeded,
    ForbiddenRuntimeBoundary,
};

struct DeckMoonlightHandoffPreflightRequest {
    std::string hostDisplayNamePublic;
    std::string gameTitlePublic;
    std::string privateHostSelectorRedactedForDebug;
    DeckMoonlightHandoffSurface requestedSurface = DeckMoonlightHandoffSurface::Unsupported;
    bool hasSafeSnapshot = false;
    bool appPresentInSnapshot = false;
};

struct DeckMoonlightHandoffCandidatePlan {
    DeckMoonlightHandoffSurface surface = DeckMoonlightHandoffSurface::Unsupported;
    std::vector<std::string> argvTokens;
    std::string publicSummary;
};

struct DeckMoonlightFocusReturnPlan {
    std::string sourceSurface;
    std::string intendedReturnTarget;
    std::string fallbackCopy;
    std::string confidence;
};

struct DeckMoonlightHandoffPreflightResult {
    DeckMoonlightHandoffVerdict verdict = DeckMoonlightHandoffVerdict::BlockedStatic;
    bool executable = false;
    bool allowsNetwork = false;
    bool allowsProcessExecution = false;
    bool allowsMoonlight = false;
    bool allowsHostMutation = false;
    bool safeToRender = false;
    DeckMoonlightHandoffCandidatePlan candidatePlan;
    DeckMoonlightFocusReturnPlan focusReturnPlan;
    std::string publicPreviewCopy;
    std::vector<DeckMoonlightHandoffBlockReason> blockedReasons;
};

DeckMoonlightHandoffPreflightResult resolveDeckMoonlightHandoffPreflight(
    const DeckMoonlightHandoffPreflightRequest& request);

} // namespace nova::deck::stream
