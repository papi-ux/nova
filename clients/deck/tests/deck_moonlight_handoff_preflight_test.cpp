#include "stream/deck_moonlight_handoff_preflight.h"

#include <algorithm>
#include <cassert>
#include <initializer_list>
#include <string>
#include <string_view>
#include <type_traits>
#include <utility>
#include <vector>

namespace {

using nova::deck::stream::DeckMoonlightFocusReturnPlan;
using nova::deck::stream::DeckMoonlightHandoffBlockReason;
using nova::deck::stream::DeckMoonlightHandoffPreflightRequest;
using nova::deck::stream::DeckMoonlightHandoffPreflightResult;
using nova::deck::stream::DeckMoonlightHandoffSurface;
using nova::deck::stream::DeckMoonlightHandoffVerdict;
using nova::deck::stream::DeckMoonlightReadinessCheck;
using nova::deck::stream::DeckMoonlightReadinessCheckStatus;
using nova::deck::stream::resolveDeckMoonlightHandoffPreflight;

DeckMoonlightHandoffPreflightRequest validRequest(
    const DeckMoonlightHandoffSurface surface = DeckMoonlightHandoffSurface::MoonlightQtCli) {
    return DeckMoonlightHandoffPreflightRequest{
        .hostDisplayNamePublic = "MacPapi Gaming Host",
        .gameTitlePublic = "Black Myth: Wukong",
        .privateHostSelectorRedactedForDebug = "redacted-host-selector",
        .requestedSurface = surface,
        .hasSafeSnapshot = true,
        .appPresentInSnapshot = true,
    };
}

bool hasReason(
    const DeckMoonlightHandoffPreflightResult& result,
    const DeckMoonlightHandoffBlockReason reason) {
    return std::find(result.blockedReasons.begin(), result.blockedReasons.end(), reason) != result.blockedReasons.end();
}

bool contains(const std::string& text, const std::string_view needle) {
    return text.find(needle) != std::string::npos;
}

std::string pieces(const std::initializer_list<std::string_view> parts) {
    std::string value;
    for (const auto part : parts) {
        value.append(part);
    }
    return value;
}

void assertRuntimeBoundaryClosed(const DeckMoonlightHandoffPreflightResult& result) {
    assert(!result.executable);
    assert(!result.allowsNetwork);
    assert(!result.allowsProcessExecution);
    assert(!result.allowsMoonlight);
    assert(!result.allowsHostMutation);
}

void assertBlockedStatic(const DeckMoonlightHandoffPreflightResult& result) {
    assert(result.verdict == DeckMoonlightHandoffVerdict::BlockedStatic);
    assertRuntimeBoundaryClosed(result);
    assert(result.candidatePlan.argvTokens.empty());
    assert(!contains(result.publicPreviewCopy, "moonlight://"));
    assert(!contains(result.publicPreviewCopy, "http://"));
    assert(!contains(result.publicPreviewCopy, "https://"));
    assert(!contains(result.publicPreviewCopy, "ssh"));
}

void assertFocusReturnUnproven(const DeckMoonlightFocusReturnPlan& plan) {
    assert(plan.sourceSurface == "Nova Deck preview review");
    assert(plan.intendedReturnTarget == "MacPapi Gaming Host / Black Myth: Wukong");
    assert(plan.confidence == "unproven_static");
    assert(contains(plan.fallbackCopy, "Return to Nova"));
    assert(contains(plan.fallbackCopy, "later approved launch"));
}

const DeckMoonlightReadinessCheck& readinessCheck(
    const DeckMoonlightHandoffPreflightResult& result,
    const std::string_view id) {
    const auto match = std::find_if(
        result.readinessChecks.begin(),
        result.readinessChecks.end(),
        [&](const DeckMoonlightReadinessCheck& check) {
            return check.id == id;
        });
    assert(match != result.readinessChecks.end());
    return *match;
}

void assertReadinessCheck(
    const DeckMoonlightHandoffPreflightResult& result,
    const std::string_view id,
    const DeckMoonlightReadinessCheckStatus status,
    const std::string_view detailNeedle) {
    const auto& check = readinessCheck(result, id);
    assert(check.status == status);
    assert(!check.label.empty());
    assert(contains(check.detail, detailNeedle));
    assert(!contains(check.detail, "moonlight://"));
    assert(!contains(check.detail, "http://"));
    assert(!contains(check.detail, "https://"));
    assert(!contains(check.detail, "ssh"));
}

} // namespace

static_assert(std::is_default_constructible_v<DeckMoonlightHandoffPreflightRequest>);
static_assert(std::is_default_constructible_v<DeckMoonlightHandoffPreflightResult>);

int main() {
    {
        const auto result = resolveDeckMoonlightHandoffPreflight(validRequest());
        assert(result.verdict == DeckMoonlightHandoffVerdict::ReadyForReview);
        assert(result.safeToRender);
        assertRuntimeBoundaryClosed(result);
        assert(result.candidatePlan.surface == DeckMoonlightHandoffSurface::MoonlightQtCli);
        assert((result.candidatePlan.argvTokens == std::vector<std::string>{
            "moonlight",
            "stream",
            "redacted-host-selector",
            "Black Myth: Wukong",
        }));
        assert(contains(result.publicPreviewCopy, "Ready to review Moonlight handoff"));
        assert(contains(result.publicPreviewCopy, "MacPapi Gaming Host"));
        assert(contains(result.publicPreviewCopy, "Black Myth: Wukong"));
        assert(contains(result.publicPreviewCopy, "Nothing will launch yet"));
        assert(!contains(result.publicPreviewCopy, "redacted-host-selector"));
        assertFocusReturnUnproven(result.focusReturnPlan);
        assert(result.blockedReasons.empty());
        assert(result.readinessChecks.size() == 4);
        assertReadinessCheck(result, "safe-snapshot", DeckMoonlightReadinessCheckStatus::Passed, "Read-only host snapshot");
        assertReadinessCheck(result, "app-snapshot", DeckMoonlightReadinessCheckStatus::Passed, "Game appears in snapshot");
        assertReadinessCheck(result, "typed-argv", DeckMoonlightReadinessCheckStatus::Passed, "Typed argv preview is redacted");
        assertReadinessCheck(result, "focus-return", DeckMoonlightReadinessCheckStatus::ReviewOnly, "Focus return remains unproven_static");
    }

    {
        auto request = validRequest();
        request.hostDisplayNamePublic.clear();
        const auto result = resolveDeckMoonlightHandoffPreflight(request);
        assertBlockedStatic(result);
        assert(hasReason(result, DeckMoonlightHandoffBlockReason::MissingHost));
        assert(contains(result.publicPreviewCopy, "public host label"));
    }

    {
        auto request = validRequest();
        request.gameTitlePublic.clear();
        const auto result = resolveDeckMoonlightHandoffPreflight(request);
        assertBlockedStatic(result);
        assert(hasReason(result, DeckMoonlightHandoffBlockReason::MissingGame));
        assert(contains(result.publicPreviewCopy, "game title"));
    }

    {
        auto request = validRequest();
        request.hasSafeSnapshot = false;
        const auto result = resolveDeckMoonlightHandoffPreflight(request);
        assertBlockedStatic(result);
        assert(hasReason(result, DeckMoonlightHandoffBlockReason::HostSnapshotMissing));
        assert(hasReason(result, DeckMoonlightHandoffBlockReason::HostPairingUnprovenStatic));
        assert(hasReason(result, DeckMoonlightHandoffBlockReason::FocusReturnUnprovenStatic));
        assert(contains(result.publicPreviewCopy, "cannot verify Moonlight readiness"));
        assertFocusReturnUnproven(result.focusReturnPlan);
        assertReadinessCheck(result, "safe-snapshot", DeckMoonlightReadinessCheckStatus::Blocked, "Needs safe host snapshot");
        assertReadinessCheck(result, "app-snapshot", DeckMoonlightReadinessCheckStatus::Passed, "Game appears in snapshot");
        assertReadinessCheck(result, "typed-argv", DeckMoonlightReadinessCheckStatus::Blocked, "Snapshot gate must pass first");
    }

    {
        auto request = validRequest();
        request.appPresentInSnapshot = false;
        const auto result = resolveDeckMoonlightHandoffPreflight(request);
        assertBlockedStatic(result);
        assert(hasReason(result, DeckMoonlightHandoffBlockReason::AppNotInSnapshot));
        assert(hasReason(result, DeckMoonlightHandoffBlockReason::HostPairingUnprovenStatic));
        assertReadinessCheck(result, "safe-snapshot", DeckMoonlightReadinessCheckStatus::Passed, "Read-only host snapshot");
        assertReadinessCheck(result, "app-snapshot", DeckMoonlightReadinessCheckStatus::Blocked, "Game missing from snapshot");
        assertReadinessCheck(result, "typed-argv", DeckMoonlightReadinessCheckStatus::Blocked, "App snapshot gate must pass first");
    }

    {
        const auto result = resolveDeckMoonlightHandoffPreflight(validRequest(DeckMoonlightHandoffSurface::CustomUri));
        assertBlockedStatic(result);
        assert(hasReason(result, DeckMoonlightHandoffBlockReason::CustomUriNotStreamHandler));
        assert(!contains(result.candidatePlan.publicSummary, "moonlight://"));
    }

    {
        const std::vector<std::pair<DeckMoonlightHandoffSurface, DeckMoonlightHandoffBlockReason>> blockedSurfaces{
            {DeckMoonlightHandoffSurface::DesktopEntry, DeckMoonlightHandoffBlockReason::DesktopEntryNotStreamContract},
            {DeckMoonlightHandoffSurface::FlatpakIdentity, DeckMoonlightHandoffBlockReason::FlatpakContractUnproven},
            {DeckMoonlightHandoffSurface::SteamShortcut, DeckMoonlightHandoffBlockReason::SteamShortcutRuntimeOnly},
        };
        for (const auto& [surface, reason] : blockedSurfaces) {
            const auto result = resolveDeckMoonlightHandoffPreflight(validRequest(surface));
            assertBlockedStatic(result);
            assert(hasReason(result, reason));
            assert(contains(result.publicPreviewCopy, "research-only"));
        }
    }

    {
        const auto result = resolveDeckMoonlightHandoffPreflight(validRequest(DeckMoonlightHandoffSurface::NovaOwnedCommonCFuture));
        assert(result.verdict == DeckMoonlightHandoffVerdict::ForbiddenRuntimeBoundary);
        assertRuntimeBoundaryClosed(result);
        assert(hasReason(result, DeckMoonlightHandoffBlockReason::ForbiddenRuntimeBoundary));
        assert(result.candidatePlan.argvTokens.empty());
    }

    {
        const std::vector<std::string> unsafePublicValues{
            pieces({"10", ".0", ".0", ".232"}),
            pieces({"http", "://", "host.local"}),
            pieces({"s", "sh pc-papi"}),
            pieces({"/Us", "ers/", "papi/", ".s", "sh/", "id_ed25519"}),
            pieces({"token", "=redacted-test-value"}),
            pieces({"pass", "word: redacted-test-value"}),
            pieces({"moon", "light", "://", "stream/host/app"}),
            pieces({"MacPapi", ";", " rm -rf /"}),
            pieces({"aa", ":bb", ":cc", ":dd", ":ee", ":ff"}),
            pieces({"!", "abcdef", ":matrix.local"}),
        };
        for (const auto& unsafeValue : unsafePublicValues) {
            auto hostRequest = validRequest();
            hostRequest.hostDisplayNamePublic = unsafeValue;
            const auto hostResult = resolveDeckMoonlightHandoffPreflight(hostRequest);
            assertBlockedStatic(hostResult);
            assert(hasReason(hostResult, DeckMoonlightHandoffBlockReason::UnsafePublicCopy));
            assert(!contains(hostResult.publicPreviewCopy, unsafeValue));

            auto gameRequest = validRequest();
            gameRequest.gameTitlePublic = unsafeValue;
            const auto gameResult = resolveDeckMoonlightHandoffPreflight(gameRequest);
            assertBlockedStatic(gameResult);
            assert(hasReason(gameResult, DeckMoonlightHandoffBlockReason::UnsafePublicCopy));
            assert(!contains(gameResult.publicPreviewCopy, unsafeValue));
        }
    }

    {
        auto request = validRequest();
        request.privateHostSelectorRedactedForDebug = "host selector; launch";
        const auto result = resolveDeckMoonlightHandoffPreflight(request);
        assertBlockedStatic(result);
        assert(hasReason(result, DeckMoonlightHandoffBlockReason::UnsafeArgvToken));
        assert(!contains(result.publicPreviewCopy, "host selector; launch"));
        assertReadinessCheck(result, "typed-argv", DeckMoonlightReadinessCheckStatus::Blocked, "Typed argv preview is not public-safe");
    }

    return 0;
}
