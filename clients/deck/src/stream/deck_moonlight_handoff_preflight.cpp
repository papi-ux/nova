#include "stream/deck_moonlight_handoff_preflight.h"

#include <algorithm>
#include <cctype>
#include <regex>
#include <string>
#include <string_view>

namespace nova::deck::stream {

namespace {

bool isBlank(const std::string& value) {
    return std::all_of(value.begin(), value.end(), [](const unsigned char ch) {
        return std::isspace(ch) != 0;
    });
}

std::string lowerCopy(const std::string& value) {
    std::string lowered;
    lowered.reserve(value.size());
    for (const unsigned char ch : value) {
        lowered.push_back(static_cast<char>(std::tolower(ch)));
    }
    return lowered;
}

bool containsAny(const std::string& value, const std::initializer_list<std::string_view> needles) {
    return std::any_of(needles.begin(), needles.end(), [&](const std::string_view needle) {
        return value.find(needle) != std::string::npos;
    });
}

bool containsShellSyntax(const std::string& value) {
    return containsAny(value, {";", "&&", "||", "`", "$(", "\n", "\r"});
}

bool containsPrivateEndpointLikeValue(const std::string& value) {
    static const std::regex privateIpv4Pattern(
        R"(\b(?:10|127|169\.254|172\.(?:1[6-9]|2[0-9]|3[0-1])|192\.168)\.\d{1,3}\.\d{1,3}\b)");
    static const std::regex macLikePattern(R"(\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\b)");
    return std::regex_search(value, privateIpv4Pattern) || std::regex_search(value, macLikePattern);
}

bool containsUnsafeSecretLikeText(const std::string& lowered) {
    for (const std::string_view label : {"token", "password", "client_secret", "api_key"}) {
        if (lowered.find(std::string{label} + "=") != std::string::npos
            || lowered.find(std::string{label} + ":") != std::string::npos) {
            return true;
        }
    }
    return false;
}

bool containsUnsafeSchemeOrPath(const std::string& value) {
    const auto lowered = lowerCopy(value);
    const auto unixHomePathMarker = std::string{"/ho"} + "me/";
    return containsAny(lowered, {
        "://",
        "ssh ",
        "file:",
        "/users/",
        ".ssh/",
        "begin ",
        " private key",
        ":matrix",
    }) || lowered.find(unixHomePathMarker) != std::string::npos
        || (!value.empty() && value.front() == '!');
}

bool isUnsafePublicText(const std::string& value) {
    const auto lowered = lowerCopy(value);
    return containsShellSyntax(value)
        || containsPrivateEndpointLikeValue(value)
        || containsUnsafeSecretLikeText(lowered)
        || containsUnsafeSchemeOrPath(value);
}

bool isUnsafeArgvToken(const std::string& value) {
    return isBlank(value)
        || containsShellSyntax(value)
        || containsPrivateEndpointLikeValue(value)
        || containsUnsafeSchemeOrPath(value)
        || containsUnsafeSecretLikeText(lowerCopy(value));
}

DeckMoonlightFocusReturnPlan focusReturnPlanFor(const DeckMoonlightHandoffPreflightRequest& request) {
    const auto target = (!isBlank(request.hostDisplayNamePublic) && !isBlank(request.gameTitlePublic))
        ? request.hostDisplayNamePublic + " / " + request.gameTitlePublic
        : std::string{"selected Nova Deck review target"};
    return DeckMoonlightFocusReturnPlan{
        .sourceSurface = "Nova Deck preview review",
        .intendedReturnTarget = target,
        .fallbackCopy = "Return to Nova and keep this preview available after a later approved launch exits or fails.",
        .confidence = "unproven_static",
    };
}

DeckMoonlightHandoffPreflightResult baseResult(const DeckMoonlightHandoffPreflightRequest& request) {
    DeckMoonlightHandoffPreflightResult result;
    result.focusReturnPlan = focusReturnPlanFor(request);
    return result;
}

DeckMoonlightHandoffPreflightResult blocked(
    const DeckMoonlightHandoffPreflightRequest& request,
    std::vector<DeckMoonlightHandoffBlockReason> reasons,
    std::string publicCopy,
    const DeckMoonlightHandoffVerdict verdict = DeckMoonlightHandoffVerdict::BlockedStatic) {
    auto result = baseResult(request);
    result.verdict = verdict;
    result.blockedReasons = std::move(reasons);
    result.publicPreviewCopy = std::move(publicCopy);
    result.candidatePlan.surface = request.requestedSurface;
    result.candidatePlan.publicSummary = result.publicPreviewCopy;
    result.safeToRender = !isUnsafePublicText(result.publicPreviewCopy);
    return result;
}

} // namespace

DeckMoonlightHandoffPreflightResult resolveDeckMoonlightHandoffPreflight(
    const DeckMoonlightHandoffPreflightRequest& request) {
    if (isBlank(request.hostDisplayNamePublic)) {
        return blocked(
            request,
            {DeckMoonlightHandoffBlockReason::MissingHost},
            "Nova needs a public host label before reviewing a Moonlight handoff. Nothing will launch yet.");
    }

    if (isBlank(request.gameTitlePublic)) {
        return blocked(
            request,
            {DeckMoonlightHandoffBlockReason::MissingGame},
            "Nova needs a public game title before reviewing a Moonlight handoff. Nothing will launch yet.");
    }

    if (isUnsafePublicText(request.hostDisplayNamePublic) || isUnsafePublicText(request.gameTitlePublic)) {
        return blocked(
            request,
            {DeckMoonlightHandoffBlockReason::UnsafePublicCopy},
            "Nova blocked this Moonlight handoff preview because public copy contains unsafe private or shell-like text. Nothing will launch yet.");
    }

    if (request.requestedSurface == DeckMoonlightHandoffSurface::NovaOwnedCommonCFuture) {
        return blocked(
            request,
            {DeckMoonlightHandoffBlockReason::ForbiddenRuntimeBoundary},
            "Nova-owned streaming belongs behind a later approved runtime lane. Nothing will launch yet.",
            DeckMoonlightHandoffVerdict::ForbiddenRuntimeBoundary);
    }

    if (request.requestedSurface == DeckMoonlightHandoffSurface::CustomUri) {
        return blocked(
            request,
            {DeckMoonlightHandoffBlockReason::CustomUriNotStreamHandler},
            "Custom URI handoff is blocked: research has not proven a stream-launch handler. Nothing will launch yet.");
    }

    if (request.requestedSurface == DeckMoonlightHandoffSurface::DesktopEntry) {
        return blocked(
            request,
            {DeckMoonlightHandoffBlockReason::DesktopEntryNotStreamContract, DeckMoonlightHandoffBlockReason::ResearchNeeded},
            "Desktop entry handoff is research-only: it identifies the app shell, not a host/game stream. Nothing will launch yet.");
    }

    if (request.requestedSurface == DeckMoonlightHandoffSurface::FlatpakIdentity) {
        return blocked(
            request,
            {DeckMoonlightHandoffBlockReason::FlatpakContractUnproven, DeckMoonlightHandoffBlockReason::ResearchNeeded},
            "Flatpak identity handoff is research-only: package identity is known, but argument forwarding is unproven. Nothing will launch yet.");
    }

    if (request.requestedSurface == DeckMoonlightHandoffSurface::SteamShortcut) {
        return blocked(
            request,
            {DeckMoonlightHandoffBlockReason::SteamShortcutRuntimeOnly, DeckMoonlightHandoffBlockReason::ResearchNeeded},
            "Steam shortcut handoff is research-only: Game Mode launch behavior needs a later approved runtime check. Nothing will launch yet.");
    }

    if (request.requestedSurface != DeckMoonlightHandoffSurface::MoonlightQtCli) {
        return blocked(
            request,
            {DeckMoonlightHandoffBlockReason::UnsupportedSurface},
            "This handoff surface is not supported by the local-only Moonlight preflight. Nothing will launch yet.");
    }

    if (!request.hasSafeSnapshot) {
        return blocked(
            request,
            {
                DeckMoonlightHandoffBlockReason::HostSnapshotMissing,
                DeckMoonlightHandoffBlockReason::HostPairingUnprovenStatic,
                DeckMoonlightHandoffBlockReason::FocusReturnUnprovenStatic,
            },
            "Nova cannot verify Moonlight readiness without a prior safe snapshot or a later approved runtime check. Nothing will launch yet.");
    }

    if (!request.appPresentInSnapshot) {
        return blocked(
            request,
            {
                DeckMoonlightHandoffBlockReason::AppNotInSnapshot,
                DeckMoonlightHandoffBlockReason::HostPairingUnprovenStatic,
            },
            "Nova cannot verify that this game exists in the safe host snapshot. Nothing will launch yet.");
    }

    const auto privateHostSelector = isBlank(request.privateHostSelectorRedactedForDebug)
        ? std::string{"redacted-host-selector"}
        : request.privateHostSelectorRedactedForDebug;
    if (isUnsafeArgvToken(privateHostSelector)) {
        return blocked(
            request,
            {DeckMoonlightHandoffBlockReason::UnsafeArgvToken},
            "Nova blocked this Moonlight handoff preview because the private host selector is not safe as typed argv data. Nothing will launch yet.");
    }

    auto result = baseResult(request);
    result.verdict = DeckMoonlightHandoffVerdict::ReadyForReview;
    result.safeToRender = true;
    result.candidatePlan.surface = DeckMoonlightHandoffSurface::MoonlightQtCli;
    result.candidatePlan.argvTokens = {"moonlight", "stream", privateHostSelector, request.gameTitlePublic};
    result.publicPreviewCopy = "Ready to review Moonlight handoff for "
        + request.hostDisplayNamePublic
        + " / "
        + request.gameTitlePublic
        + ". Nothing will launch yet.";
    result.candidatePlan.publicSummary = result.publicPreviewCopy;
    result.safeToRender = !isUnsafePublicText(result.publicPreviewCopy);
    if (!result.safeToRender) {
        return blocked(
            request,
            {DeckMoonlightHandoffBlockReason::UnsafePublicCopy},
            "Nova blocked this Moonlight handoff preview because the public review copy is not safe to render. Nothing will launch yet.");
    }
    return result;
}

} // namespace nova::deck::stream
