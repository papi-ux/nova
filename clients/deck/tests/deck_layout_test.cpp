#include "deck_layout.h"
#include "deck_gamepad.h"
#include "polaris_game_fixture.h"

#include <cassert>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>
#include <string_view>

namespace {
bool containsIpv4AddressLike(const std::string& text) {
    int dotSeparatedNumberRuns = 0;
    bool inDigits = false;
    for (const unsigned char ch : text) {
        if (std::isdigit(ch)) {
            inDigits = true;
            continue;
        }
        if (ch == char(46) && inDigits) {
            ++dotSeparatedNumberRuns;
            inDigits = false;
            if (dotSeparatedNumberRuns >= 3) {
                return true;
            }
            continue;
        }
        dotSeparatedNumberRuns = 0;
        inDigits = false;
    }
    return false;
}
std::string readTextFile(const char* path) {
    std::ifstream stream(path);
    assert(stream.good());
    return std::string(std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>());
}

class RecordingLocalClipboard final : public nova::deck::DeckLocalClipboard {
public:
    bool publishPreviewText(std::string_view value) override {
        ++writeCount;
        publishedText = std::string(value);
        return allowWrite;
    }

    bool allowWrite = true;
    int writeCount = 0;
    std::string publishedText;
};
} // namespace

int main() {
    const auto profile = nova::deck::defaultWindowProfile();

    assert(profile.width == 1280);
    assert(profile.height == 800);
    assert(profile.fullscreenPreferred);
    assert(profile.shellName == std::string_view("Nova Deck"));

#ifndef NOVA_DECK_MAIN_QML_SOURCE
#error "NOVA_DECK_MAIN_QML_SOURCE must point at the Deck QML shell for layout regression checks"
#endif
    const auto mainQml = readTextFile(NOVA_DECK_MAIN_QML_SOURCE);
    assert(mainQml.find("anchors.margins: 56") == std::string::npos);
    assert(mainQml.find("Layout.preferredWidth: 480") == std::string::npos);
    assert(mainQml.find("Layout.preferredWidth: 410") == std::string::npos);
    assert(mainQml.find("Controller-first Steam Deck shell scaffold") == std::string::npos);
    assert(mainQml.find("property int previewCopyActivationCount: 0") != std::string::npos);
    assert(mainQml.find("previewCopyActivationCount += 1") != std::string::npos);
    assert(mainQml.find("A pressed #") != std::string::npos);
    assert(mainQml.find("target: novaGamepad") != std::string::npos);
    assert(mainQml.find("onPrimaryActionPressed") != std::string::npos);
    assert(mainQml.find("novaLibraryGames") != std::string::npos);
    assert(mainQml.find("novaLibraryHosts") != std::string::npos);
    assert(mainQml.find("libraryGameRepeater") != std::string::npos);
    assert(mainQml.find("Polaris library preview") != std::string::npos);
    assert(mainQml.find("novaHostLaunchCta.helpText") != std::string::npos);
    assert(mainQml.find("novaHostLaunchCta.previewStateLabel") != std::string::npos);
    assert(mainQml.find("text: novaHostLaunchCta.helpText") != std::string::npos);
    assert(mainQml.find("D-pad Navigate") != std::string::npos);
    assert(mainQml.find("selectedHostForPreview") != std::string::npos);
    assert(mainQml.find("selectedGameForPreview") != std::string::npos);
    assert(mainQml.find("refreshLaunchPreviewBinding") != std::string::npos);
    assert(mainQml.find("selectedLaunchPreviewText") != std::string::npos);
    assert(mainQml.find("Selected host") != std::string::npos);
    assert(mainQml.find("selectedGameForPreview.title") != std::string::npos);
    assert(mainQml.find("No games in read-only snapshot") != std::string::npos);
    assert(mainQml.find("Preview snapshot ready") != std::string::npos);
    assert(mainQml.find("Snapshot unavailable in this preview shell") != std::string::npos);
    assert(mainQml.find("A copies preview locally; no launch") != std::string::npos);
    assert(mainQml.find("novaLaunchIntentPreview") != std::string::npos);
    assert(mainQml.find("selectedLaunchPublicCopy") != std::string::npos);
    assert(mainQml.find("selectedStreamLifecycleCopy") != std::string::npos);
    assert(mainQml.find("state=copy-preview-only") == std::string::npos);
    assert(mainQml.find("readonly property color focusRingColor") != std::string::npos);
    assert(mainQml.find("readonly property color focusGlowColor") != std::string::npos);
    assert(mainQml.find("cursorShape: Qt.BlankCursor") != std::string::npos);
    assert(mainQml.find("D-pad focus") != std::string::npos);
    assert(mainQml.find("Exact preview details stay behind Copy preview details") == std::string::npos);
    assert(mainQml.find("objectName: \"moonlight-handoff-panel\"") != std::string::npos);
    assert(mainQml.find("objectName: \"moonlight-handoff-title-row\"") != std::string::npos);
    assert(mainQml.find("objectName: \"moonlight-safety-chip-row\"") != std::string::npos);
    assert(mainQml.find("objectName: \"moonlight-runtime-gate-chip\"") != std::string::npos);
    assert(mainQml.find("objectName: \"moonlight-focus-chip\"") != std::string::npos);
    assert(mainQml.find("objectName: \"copy-preview-action-row\"") != std::string::npos);
    assert(mainQml.find("Moonlight handoff preview") != std::string::npos);
    assert(mainQml.find("No launch") != std::string::npos);
    assert(mainQml.find("No network/process") != std::string::npos);
    assert(mainQml.find("Focus: unproven_static") != std::string::npos);
    assert(mainQml.find("Copy locally — no launch") != std::string::npos);
    assert(mainQml.find("selectedMoonlightHandoffCopy") != std::string::npos);
    assert(mainQml.find("selectedMoonlightHandoffArgvPreview") != std::string::npos);
    assert(mainQml.find("Typed argv plan") != std::string::npos);
    assert(mainQml.find("redacted host selector") != std::string::npos);
    assert(mainQml.find("Runtime gates: network off · process off · Moonlight off · host mutation off") != std::string::npos);
    assert(mainQml.find("unproven_static") != std::string::npos);
    assert(mainQml.find("Nothing will launch yet") != std::string::npos);
    assert(mainQml.find("novaMoonlightHandoffPreflightBridge.resolve") != std::string::npos);
    assert(mainQml.find("moonlightHandoffPreflight.executable") != std::string::npos);
    assert(mainQml.find("moonlightHandoffPreflight.safeToRender") != std::string::npos);
    assert(mainQml.find("moonlightHandoffRuntimeGatesClosed") != std::string::npos);
    assert(mainQml.find("!moonlightHandoffPreflight.allowsNetwork") != std::string::npos);
    assert(mainQml.find("!moonlightHandoffPreflight.allowsProcessExecution") != std::string::npos);
    assert(mainQml.find("!moonlightHandoffPreflight.allowsMoonlight") != std::string::npos);
    assert(mainQml.find("!moonlightHandoffPreflight.allowsHostMutation") != std::string::npos);
    assert(mainQml.find("Moonlight handoff preview blocked until safe public copy is available") != std::string::npos);
    assert(mainQml.find("text: selectedLaunchPreviewText") == std::string::npos);
    assert(mainQml.find("font.family: monospace") == std::string::npos);
    assert(mainQml.find("onClicked: activateMoonlight") == std::string::npos);
    assert(mainQml.find("QProcess") == std::string::npos);

    assert(nova::deck::decodeGamepadAction(nova::deck::DeckGamepadEvent{
        .timeMs = 10,
        .value = 1,
        .type = nova::deck::kDeckGamepadButtonEvent,
        .number = nova::deck::kDeckGamepadPrimaryButton,
    }) == nova::deck::DeckGamepadAction::PrimaryPressed);
    assert(nova::deck::decodeGamepadAction(nova::deck::DeckGamepadEvent{
        .timeMs = 11,
        .value = 0,
        .type = nova::deck::kDeckGamepadButtonEvent,
        .number = nova::deck::kDeckGamepadPrimaryButton,
    }) == nova::deck::DeckGamepadAction::None);
    assert(nova::deck::decodeGamepadAction(nova::deck::DeckGamepadEvent{
        .timeMs = 12,
        .value = 1,
        .type = static_cast<unsigned char>(nova::deck::kDeckGamepadButtonEvent | nova::deck::kDeckGamepadInitEvent),
        .number = nova::deck::kDeckGamepadPrimaryButton,
    }) == nova::deck::DeckGamepadAction::None);
    assert(nova::deck::decodeGamepadAction(nova::deck::DeckGamepadEvent{
        .timeMs = 13,
        .value = 1,
        .type = nova::deck::kDeckGamepadButtonEvent,
        .number = static_cast<unsigned char>(nova::deck::kDeckGamepadPrimaryButton + 1),
    }) == nova::deck::DeckGamepadAction::None);

    const auto focusTargets = nova::deck::defaultLibraryFocusTargets();
    assert(focusTargets.size() >= 2);
    assert(focusTargets.front().id == std::string_view("sample-game-card"));
    assert(focusTargets.front().initialFocus);
    assert(nova::deck::initialLibraryFocusTarget(focusTargets) == std::string_view("sample-game-card"));
    assert(nova::deck::nextLibraryFocusTarget(focusTargets, "sample-game-card", nova::deck::DeckFocusDirection::Right)
        == std::string_view("details-placeholder"));
    assert(nova::deck::nextLibraryFocusTarget(focusTargets, "details-placeholder", nova::deck::DeckFocusDirection::Left)
        == std::string_view("sample-game-card"));

    const auto emptyHosts = nova::deck::emptyHostListState();
    assert(emptyHosts.empty());
    assert(nova::deck::initialHostFocusTarget(emptyHosts) == std::string_view("host-empty-state"));
    assert(nova::deck::nextHostFocusTarget(emptyHosts, "missing-host", nova::deck::DeckFocusDirection::Down)
        == std::string_view("host-empty-state"));

    const auto demoHosts = nova::deck::demoHostListState();
    assert(demoHosts.size() >= 2);
    assert(demoHosts[0].id == std::string_view("host-gaming-pc"));
    assert(demoHosts[0].displayName == std::string_view("Gaming PC"));
    assert(demoHosts[0].statusLabel == std::string_view("Ready for local demo"));
    assert(demoHosts[1].id == std::string_view("host-living-room-pc"));
    assert(demoHosts[1].displayName == std::string_view("Living Room PC"));
    assert(nova::deck::initialHostFocusTarget(demoHosts) == std::string_view("host-gaming-pc"));

    const auto realLibrary = nova::deck::loadSamplePolarisGameLibraryFixture();
    const auto libraryHosts = nova::deck::libraryHostListStateFor(realLibrary);
    assert(libraryHosts.size() == 2);
    assert(libraryHosts[0].id == std::string_view("host-snapshot-primary"));
    assert(libraryHosts[0].displayName == std::string_view("Polaris Snapshot Primary"));
    assert(libraryHosts[0].statusLabel == std::string_view("Ready from read-only library snapshot"));
    assert(libraryHosts[0].initialFocus);
    assert(libraryHosts[1].id == std::string_view("host-snapshot-living-room"));
    assert(libraryHosts[1].displayName == std::string_view("Polaris Snapshot Living Room"));
    assert(libraryHosts[1].statusLabel == std::string_view("Available from read-only library snapshot"));
    assert(!libraryHosts[1].initialFocus);
    assert(nova::deck::initialHostFocusTarget(libraryHosts) == std::string_view("host-snapshot-primary"));
    assert(nova::deck::nextHostFocusTarget(demoHosts, "host-gaming-pc", nova::deck::DeckFocusDirection::Down)
        == std::string_view("host-living-room-pc"));
    assert(nova::deck::nextHostFocusTarget(demoHosts, "host-living-room-pc", nova::deck::DeckFocusDirection::Up)
        == std::string_view("host-gaming-pc"));
    assert(nova::deck::nextHostFocusTarget(demoHosts, "host-living-room-pc", nova::deck::DeckFocusDirection::Down)
        == std::string_view("host-gaming-pc"));
    assert(nova::deck::nextHostFocusTarget(demoHosts, "host-gaming-pc", nova::deck::DeckFocusDirection::Up)
        == std::string_view("host-living-room-pc"));

    const auto detail = nova::deck::resolveHostDetail(demoHosts, "host-gaming-pc");
    assert(detail.id == std::string_view("host-gaming-pc"));
    assert(detail.displayName == std::string_view("Gaming PC"));
    assert(detail.statusLabel == std::string_view("Ready for local demo"));
    assert(detail.subtitle == std::string_view("Demo host detail only — not discovered from the network."));

    const auto launchCta = nova::deck::inertLaunchCtaFor(detail);
    assert(launchCta.id == std::string_view("host-detail-launch-cta"));
    assert(launchCta.label == std::string_view("Safe launch preview"));
    assert(launchCta.helpText == std::string_view("Safe preview: no game, stream, or network launch starts from this screen."));
    assert(!launchCta.enabled);
    assert(launchCta.previewStateLabel == std::string_view("Preview only — not executable"));
    assert(launchCta.previewText == std::string_view("preview://nova-deck/launch?host=host-gaming-pc&game=Portal%202&mode=steam-direct&stream=headless&state=noop-preview"));

    const auto launchGame = nova::deck::loadSamplePolarisGameFixture();
    const auto launchIntent = nova::deck::resolveLaunchIntent(detail, launchGame);
    assert(launchIntent.targetHostId == "host-gaming-pc");
    assert(launchIntent.targetHostName == "Gaming PC");
    assert(launchIntent.gameTitle == "Portal 2");
    assert(launchIntent.sampleGameId == "game-123");
    assert(launchIntent.streamLaunchMode == "headless");
    assert(launchIntent.steamLaunchMode == "direct");
    assert(launchIntent.boundary.kind == nova::deck::DeckLaunchIntentBoundaryKind::PreviewOnly);
    assert(launchIntent.boundary.id == "deck-launch-preview-only");
    assert(launchIntent.boundary.label == "Safe preview: no game, stream, or network launch starts from this screen.");
    assert(launchIntent.boundary.previewOnly);
    assert(!launchIntent.boundary.allowsNetwork);
    assert(!launchIntent.boundary.allowsProcessExecution);
    assert(!launchIntent.boundary.allowsMoonlight);
    assert(!launchIntent.boundary.allowsHostMutation);
    assert(launchIntent.boundary.reason == "Nova Deck shows a copyable preview plan only; games, streams, and network launches stay off.");
    assert(!launchIntent.executable);
    assert(launchIntent.safetyLabel == "Preview only — not executable");
    assert(launchIntent.host.addressClass == nova::deck::DeckHostAddressClass::DemoOnly);
    assert(launchIntent.host.id == "host-gaming-pc");
    assert(launchIntent.host.displayName == "Gaming PC");
    assert(launchIntent.game.identityKind == nova::deck::DeckGameIdentityKind::SteamApp);
    assert(launchIntent.game.libraryId == "game-123");
    assert(launchIntent.game.steamAppId == "620");
    assert(launchIntent.launchMode == nova::deck::DeckLaunchMode::SteamDirect);
    assert(launchIntent.streamProfile.id == "headless");
    assert(launchIntent.streamProfile.displayName == "Headless preview");
    assert(launchIntent.preflight.state == nova::deck::DeckPreflightState::ReadyPreview);
    assert(launchIntent.privacy.redactionPolicy == nova::deck::DeckPreviewRedactionPolicy::PublicSafe);
    assert(launchIntent.publicPreviewCopy == "Review Portal 2 on Gaming PC via Steam direct. Safe preview only; no game or stream starts.");
    assert(launchIntent.inertPreviewUri == "preview://nova-deck/launch?host=host-gaming-pc&game=Portal%202&mode=steam-direct&stream=headless&state=noop-preview");
    assert(!nova::deck::canExecuteLaunchIntent(launchIntent));

    const auto streamIntent = nova::deck::resolveStreamIntent(launchIntent);
    assert(streamIntent.provider == nova::deck::DeckStreamProvider::PreviewOnly);
    assert(streamIntent.action == nova::deck::DeckStreamAction::NoopPreview);
    assert(streamIntent.session.state == nova::deck::DeckStreamSessionState::NotStarted);
    assert(streamIntent.lifecycle == nova::deck::DeckStreamLifecycle::PreflightOnly);
    assert(streamIntent.recovery == nova::deck::DeckStreamRecovery::UserReviewRequired);
    assert(streamIntent.privacy.redactionPolicy == nova::deck::DeckPreviewRedactionPolicy::PublicSafe);
    assert(streamIntent.publicCopy == "Safe preview of Portal 2 on Gaming PC; stream remains not started.");
    assert(!streamIntent.safety.allowsNetwork);
    assert(!streamIntent.safety.allowsProcessExecution);
    assert(!streamIntent.safety.allowsMoonlight);

    const auto previewLibrary = nova::deck::loadSamplePolarisGameLibraryFixture();
    const auto selectedBinding = nova::deck::resolveLaunchPreviewBinding(
        demoHosts,
        previewLibrary,
        "host-living-room-pc",
        "game-456");
    assert(selectedBinding.selectedHostId == "host-living-room-pc");
    assert(selectedBinding.selectedHostName == "Living Room PC");
    assert(selectedBinding.selectedGameId == "game-456");
    assert(selectedBinding.selectedGameTitle == "Hades");
    assert(selectedBinding.hostDetail.id == std::string_view("host-living-room-pc"));
    assert(selectedBinding.gameCard.title == "Hades");
    assert(selectedBinding.intent.targetHostId == "host-living-room-pc");
    assert(selectedBinding.intent.targetHostName == "Living Room PC");
    assert(selectedBinding.intent.sampleGameId == "game-456");
    assert(selectedBinding.intent.gameTitle == "Hades");
    assert(selectedBinding.intent.streamLaunchMode == "virtual_display");
    assert(selectedBinding.intent.steamLaunchMode == "big-picture");
    assert(!selectedBinding.intent.executable);
    assert(!nova::deck::canExecuteLaunchIntent(selectedBinding.intent));
    assert(selectedBinding.preview.text == "preview://nova-deck/launch?host=host-living-room-pc&game=Hades&mode=steam-big-picture&stream=virtual_display&state=noop-preview");
    assert(selectedBinding.preview.copyOnly);
    assert(!selectedBinding.preview.executable);
    assert(!selectedBinding.preview.networkAllowed);
    assert(!selectedBinding.preview.processExecutionAllowed);
    assert(!selectedBinding.preview.moonlightAllowed);
    assert(!selectedBinding.preview.hostMutationAllowed);
    assert(selectedBinding.launchCta.previewText == selectedBinding.preview.text);
    assert(!selectedBinding.launchCta.enabled);
    assert(selectedBinding.copyAction.previewText == selectedBinding.preview.text);
    assert(selectedBinding.copyAction.enabled);
    assert(selectedBinding.copyAction.copyOnly);
    assert(!selectedBinding.copyAction.executable);

    const auto fallbackBinding = nova::deck::resolveLaunchPreviewBinding(
        demoHosts,
        previewLibrary,
        "missing-host",
        "missing-game");
    assert(fallbackBinding.selectedHostId == "host-gaming-pc");
    assert(fallbackBinding.selectedGameId == "game-123");
    assert(fallbackBinding.preview.text == "preview://nova-deck/launch?host=host-gaming-pc&game=Portal%202&mode=steam-direct&stream=headless&state=noop-preview");

    const auto commandPreview = nova::deck::fakeLaunchCommandPreviewFor(launchIntent);
    assert(commandPreview.text == "preview://nova-deck/launch?host=host-gaming-pc&game=Portal%202&mode=steam-direct&stream=headless&state=noop-preview");
    assert(commandPreview.stateLabel == "Preview only — not executable");
    assert(commandPreview.boundaryId == "deck-launch-preview-only");
    assert(commandPreview.boundaryLabel == "Safe preview: no game, stream, or network launch starts from this screen.");
    assert(commandPreview.copyOnly);
    assert(!commandPreview.executable);
    assert(!commandPreview.networkAllowed);
    assert(!commandPreview.processExecutionAllowed);
    assert(!commandPreview.moonlightAllowed);
    assert(!commandPreview.hostMutationAllowed);
    assert(commandPreview.text.find("moonlight") == std::string::npos);
    assert(commandPreview.text.find("http") == std::string::npos);
    assert(commandPreview.text.find("ssh") == std::string::npos);
    assert(commandPreview.text.find(";") == std::string::npos);
    assert(commandPreview.text.find("&&") == std::string::npos);
    assert(commandPreview.text.find("|") == std::string::npos);
    assert(commandPreview.text.find(std::string{"/"} + "home/") == std::string::npos);
    assert(commandPreview.text.find("Users") == std::string::npos);

    const auto copyAction = nova::deck::copyLaunchPreviewActionFor(commandPreview);
    assert(copyAction.id == std::string_view("host-detail-copy-preview"));
    assert(copyAction.label == std::string_view("Copy preview details"));
    assert(copyAction.previewText == commandPreview.text);
    assert(copyAction.idleStatusLabel == "A Copy preview saves this safe plan locally for inspection. No game, stream, or network launch starts.");
    assert(copyAction.successToast == "Preview text copied for inspection only — still not executable.");
    assert(copyAction.inertToast == "No preview text to copy — preview-only action stayed inert.");
    assert(copyAction.copyOnly);
    assert(copyAction.uiLocalClipboardOnly);
    assert(!copyAction.executable);
    assert(copyAction.enabled);
    assert(!containsIpv4AddressLike(copyAction.previewText));
    assert(copyAction.previewText.find(";") == std::string::npos);
    assert(copyAction.previewText.find("&&") == std::string::npos);
    assert(copyAction.previewText.find("|") == std::string::npos);

    RecordingLocalClipboard recordingClipboard;
    const auto localClipboardResult = nova::deck::copyLaunchPreviewToLocalClipboard(copyAction, recordingClipboard);
    assert(localClipboardResult.copied);
    assert(localClipboardResult.previewText == commandPreview.text);
    assert(localClipboardResult.statusLabel == copyAction.successToast);
    assert(localClipboardResult.toastLabel == copyAction.successToast);
    assert(recordingClipboard.writeCount == 1);
    assert(recordingClipboard.publishedText == commandPreview.text);

    const auto copiedResult = nova::deck::activateLaunchPreviewCopy(copyAction);
    assert(copiedResult.copied);
    assert(copiedResult.previewText == commandPreview.text);
    assert(copiedResult.statusLabel == "Preview text copied for inspection only — still not executable.");
    assert(copiedResult.toastLabel == "Preview text copied for inspection only — still not executable.");
    assert(copiedResult.statusLabel.find("Preview") != std::string::npos);
    assert(copiedResult.statusLabel.find("not executable") != std::string::npos);
    assert(copiedResult.previewText.find("moonlight") == std::string::npos);
    assert(copiedResult.previewText.find("http") == std::string::npos);
    assert(copiedResult.previewText.find("ssh") == std::string::npos);
    assert(copiedResult.previewText.find(";") == std::string::npos);
    assert(copiedResult.previewText.find("&&") == std::string::npos);
    assert(copiedResult.previewText.find("|") == std::string::npos);
    assert(!containsIpv4AddressLike(copiedResult.previewText));

    const auto emptyCopyAction = nova::deck::copyLaunchPreviewActionFor(nova::deck::DeckLaunchPreview{});
    assert(emptyCopyAction.previewText.empty());
    assert(!emptyCopyAction.enabled);
    assert(emptyCopyAction.copyOnly);
    assert(emptyCopyAction.uiLocalClipboardOnly);
    assert(!emptyCopyAction.executable);
    assert(emptyCopyAction.idleStatusLabel == "No preview text to copy — preview-only action stayed inert.");
    assert(emptyCopyAction.inertToast == "No preview text to copy — preview-only action stayed inert.");

    RecordingLocalClipboard inertRecordingClipboard;
    const auto inertLocalClipboardResult = nova::deck::copyLaunchPreviewToLocalClipboard(emptyCopyAction, inertRecordingClipboard);
    assert(!inertLocalClipboardResult.copied);
    assert(inertLocalClipboardResult.previewText.empty());
    assert(inertLocalClipboardResult.statusLabel == emptyCopyAction.inertToast);
    assert(inertLocalClipboardResult.toastLabel == emptyCopyAction.inertToast);
    assert(inertRecordingClipboard.writeCount == 0);
    assert(inertRecordingClipboard.publishedText.empty());

    const auto inertCopiedResult = nova::deck::activateLaunchPreviewCopy(emptyCopyAction);
    assert(!inertCopiedResult.copied);
    assert(inertCopiedResult.previewText.empty());
    assert(inertCopiedResult.statusLabel == "No preview text to copy — preview-only action stayed inert.");
    assert(inertCopiedResult.toastLabel == "No preview text to copy — preview-only action stayed inert.");

    const auto detailFocus = nova::deck::hostDetailFocusTargets(detail, launchCta, copyAction);
    assert(detailFocus.size() == 3);
    assert(detailFocus[0].id == std::string_view("host-detail-panel"));
    assert(detailFocus[1].id == std::string_view("host-detail-launch-cta"));
    assert(detailFocus[2].id == std::string_view("host-detail-copy-preview"));
    assert(detailFocus[2].label == std::string_view("Copy preview details"));
    assert(nova::deck::nextHostDetailFocusTarget(detailFocus, "host-detail-panel", nova::deck::DeckFocusDirection::Down)
        == std::string_view("host-detail-launch-cta"));
    assert(nova::deck::nextHostDetailFocusTarget(detailFocus, "host-detail-launch-cta", nova::deck::DeckFocusDirection::Down)
        == std::string_view("host-detail-copy-preview"));
    assert(nova::deck::nextHostDetailFocusTarget(detailFocus, "host-detail-copy-preview", nova::deck::DeckFocusDirection::Up)
        == std::string_view("host-detail-launch-cta"));
    assert(nova::deck::nextHostDetailFocusTarget(detailFocus, "host-detail-launch-cta", nova::deck::DeckFocusDirection::Up)
        == std::string_view("host-detail-panel"));
    assert(nova::deck::nextHostDetailFocusTarget(detailFocus, "host-detail-copy-preview", nova::deck::DeckFocusDirection::Down)
        == std::string_view("host-detail-panel"));
    assert(nova::deck::nextHostDetailFocusTarget(detailFocus, "host-detail-panel", nova::deck::DeckFocusDirection::Up)
        == std::string_view("host-detail-copy-preview"));

    assert(nova::deck::isDeckNativeAspect(1280, 800));
    assert(nova::deck::isDeckNativeAspect(2560, 1600));
    assert(!nova::deck::isDeckNativeAspect(1920, 1080));
    assert(!nova::deck::isDeckNativeAspect(0, 800));

    const auto compiledFixturePath = nova::deck::samplePolarisGameFixturePath();
    assert(!compiledFixturePath.empty());
    setenv("NOVA_DECK_SAMPLE_GAME_FIXTURE_PATH", compiledFixturePath.c_str(), 1);
    assert(nova::deck::samplePolarisGameFixturePath() == compiledFixturePath);

    const auto libraryFixturePath = nova::deck::samplePolarisGameLibraryFixturePath();
    assert(!libraryFixturePath.empty());
    setenv("NOVA_DECK_SAMPLE_LIBRARY_FIXTURE_PATH", libraryFixturePath.c_str(), 1);
    assert(nova::deck::samplePolarisGameLibraryFixturePath() == libraryFixturePath);

    const auto library = nova::deck::loadSamplePolarisGameLibraryFixture();
    unsetenv("NOVA_DECK_SAMPLE_LIBRARY_FIXTURE_PATH");
    assert(library.readOnly);
    assert(library.sourceLabel == "Shared Polaris contract fixture");
    assert(library.games.size() == 2);
    assert(library.games[0].name == "Portal 2");
    assert(library.games[1].name == "Hades");
    assert(library.games[1].steamAppid == "1145360");

    const auto libraryCards = nova::deck::libraryGameCardsFor(library);
    assert(libraryCards.size() == 2);
    assert(libraryCards[0].id == "game-123");
    assert(libraryCards[0].title == "Portal 2");
    assert(libraryCards[0].sourceRuntimeLabel == "Steam · Linux · Proton");
    assert(libraryCards[0].launchModeLabel == "Stream: headless · Steam: direct");
    assert(libraryCards[0].initialFocus);
    assert(libraryCards[1].id == "game-456");
    assert(libraryCards[1].title == "Hades");
    assert(libraryCards[1].sourceRuntimeLabel == "Steam · Linux · Proton");
    assert(libraryCards[1].launchModeLabel == "Stream: virtual_display · Steam: big-picture");
    assert(!libraryCards[1].initialFocus);
    assert(nova::deck::initialLibraryGameFocusTarget({}) == std::string_view("game-empty-state"));
    assert(nova::deck::initialLibraryGameFocusTarget(libraryCards) == std::string_view("game-123"));
    assert(nova::deck::nextLibraryGameFocusTarget(libraryCards, "game-123", nova::deck::DeckFocusDirection::Down)
        == std::string_view("game-456"));
    assert(nova::deck::nextLibraryGameFocusTarget(libraryCards, "game-456", nova::deck::DeckFocusDirection::Down)
        == std::string_view("game-123"));
    assert(nova::deck::nextLibraryGameFocusTarget(libraryCards, "game-123", nova::deck::DeckFocusDirection::Up)
        == std::string_view("game-456"));
    assert(nova::deck::nextLibraryGameFocusTarget({}, "missing-game", nova::deck::DeckFocusDirection::Up)
        == std::string_view("game-empty-state"));

    const auto hadesLaunchCta = nova::deck::inertLaunchCtaFor(detail, library.games[1]);
    assert(hadesLaunchCta.previewText == std::string_view("preview://nova-deck/launch?host=host-gaming-pc&game=Hades&mode=steam-big-picture&stream=virtual_display&state=noop-preview"));
    assert(!hadesLaunchCta.enabled);

    const auto game = nova::deck::loadSamplePolarisGameFixture();
    unsetenv("NOVA_DECK_SAMPLE_GAME_FIXTURE_PATH");
    assert(game.id == "game-123");
    assert(game.appId == 456);
    assert(game.name == "Portal 2");
    assert(game.source == "steam");
    assert(game.platform == "linux");
    assert(game.runtime == "proton");
    assert(game.steamAppid == "620");
    assert(game.installed);
    assert(game.genres.size() == 2);
    assert(game.genres[0] == "Action");
    assert(game.genres[1] == "Puzzle");
    assert(game.launchMode.preferredMode == "virtual_display");
    assert(game.launchMode.recommendedMode == "headless");
    assert(game.launchMode.allowedModes.size() == 2);
    assert(game.steamLaunch.available);
    assert(game.steamLaunch.mode == "big-picture");
    assert(game.steamLaunch.recommendedMode == "direct");

    return 0;
}
