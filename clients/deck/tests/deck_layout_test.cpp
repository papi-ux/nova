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

int qmlReadonlyIntProperty(const std::string& qml, std::string_view propertyName) {
    const std::string needle = "readonly property int " + std::string(propertyName) + ": ";
    const auto propertyStart = qml.find(needle);
    assert(propertyStart != std::string::npos);
    const auto valueStart = propertyStart + needle.size();
    const auto valueEnd = qml.find_first_not_of("0123456789", valueStart);
    assert(valueEnd != valueStart);
    return std::stoi(qml.substr(valueStart, valueEnd - valueStart));
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
    assert(mainQml.find("Backend-fed library snapshot") != std::string::npos);
    assert(mainQml.find("novaLaunchIntentBoundary") != std::string::npos);
    assert(mainQml.find("novaHostLaunchCta.helpText") != std::string::npos);
    assert(mainQml.find("D-pad Navigate") != std::string::npos);
    assert(mainQml.find("selectedHostForPreview") != std::string::npos);
    assert(mainQml.find("selectedGameForPreview") != std::string::npos);
    assert(mainQml.find("refreshLaunchPreviewBinding") != std::string::npos);
    assert(mainQml.find("selectedLaunchPreviewText") != std::string::npos);
    assert(mainQml.find("Selected host") != std::string::npos);
    assert(mainQml.find("Selected game") != std::string::npos);
    assert(mainQml.find("No games in read-only snapshot") != std::string::npos);
    assert(mainQml.find("backend-owned read-only model") != std::string::npos);
    assert(mainQml.find("Snapshot unavailable in this preview shell") != std::string::npos);
    assert(mainQml.find("launchPreviewCopyAction.idleStatusLabel") != std::string::npos);
    assert(mainQml.find("novaLaunchIntentPreview") != std::string::npos);
    assert(mainQml.find("novaBackendReadOnlyState") != std::string::npos);
    assert(mainQml.find("novaBackendReadOnlyStateMatrix") != std::string::npos);
    assert(mainQml.find("backendReadOnlyDtoParity") != std::string::npos);
    assert(mainQml.find("selectedBackendReadOnlyDtoSummary") != std::string::npos);
    assert(mainQml.find("backendReadOnlyDtoParity.collapsedSummary") != std::string::npos);
    assert(mainQml.find("backendReadOnlyDtoParity.expandedDiagnostics") != std::string::npos);
    assert(mainQml.find("backendReadOnlyPlayerState") != std::string::npos);
    assert(mainQml.find("backendReadOnlyPlayerState.title") != std::string::npos);
    assert(mainQml.find("backendReadOnlyPlayerState.body") != std::string::npos);
    assert(mainQml.find("backendReadOnlyPlayerState.actionLabel") != std::string::npos);
    assert(mainQml.find("backendReadOnlyPlayerState.safetyLabel") != std::string::npos);
    assert(mainQml.find("backendReadOnlyPlayerState.provenanceLabel") != std::string::npos);
    assert(mainQml.find("backendReadOnlyPlayerState.focusOrder") != std::string::npos);
    assert(mainQml.find("backendReadOnlyPlayerState.focusOrderCopy") != std::string::npos);
    assert(mainQml.find("function defaultBackendReadOnlyPlayerState") != std::string::npos);
    assert(mainQml.find("backendReadOnlyPlayerState && backendReadOnlyPlayerState.title") != std::string::npos);
    assert(mainQml.find("backendReadOnlyPlayerState && backendReadOnlyPlayerState.focusOrder") == std::string::npos);
    assert(mainQml.find("text: backendReadOnlyPlayerState.focusOrderCopy") != std::string::npos);
    assert(mainQml.find("Focus order: state card → Copy plan → Show diagnostics · DTO focus=") == std::string::npos);
    assert(mainQml.find("Backend-owned DTO parity") != std::string::npos);
    assert(mainQml.find("selectedBackendReadOnlyScenarioLabel") != std::string::npos);
    assert(mainQml.find("compactReadOnlyBlockerCopy(backendReadOnlyPreflight, selectedBackendReadOnlyScenarioLabel)") == std::string::npos);
    assert(mainQml.find("runBackendReadOnlyStateMatrixSmoke") != std::string::npos);
    assert(mainQml.find("backendReadOnlyPreflight") != std::string::npos);
    assert(mainQml.find("selectedLaunchPublicCopy") != std::string::npos);
    assert(mainQml.find("selectedStreamLifecycleCopy") != std::string::npos);
    assert(mainQml.find("novaPresenterReadiness") != std::string::npos);
    assert(mainQml.find("text: \"VAAPI/EGL presenter readiness: \"") == std::string::npos);
    assert(mainQml.find("Readiness checks · safe preview · stream off") != std::string::npos);
    assert(mainQml.find("hardwarePresenterPlanned") != std::string::npos);
    assert(mainQml.find("statusCode") != std::string::npos);
    assert(mainQml.find("import Nova.Deck.Stream 0.1") != std::string::npos);
    assert(mainQml.find("DeckVaapiPreviewSurface") != std::string::npos);
    assert(mainQml.find("nova-product-preview-surface") != std::string::npos);
    assert(mainQml.find("visible: novaPresenterReadiness.ready") != std::string::npos);
    assert(qmlReadonlyIntProperty(mainQml, "launchPreviewHeight") >= 286);
    assert(qmlReadonlyIntProperty(mainQml, "detailPanelHeight") + qmlReadonlyIntProperty(mainQml, "deckPanelSpacing")
        + qmlReadonlyIntProperty(mainQml, "launchPreviewHeight") <= 540);
    assert(mainQml.find("Press A on Copy to verify. A Copy preview saves this safe plan locally for inspection.") == std::string::npos);
    assert(mainQml.find("text: selectedStreamLifecycleCopy") == std::string::npos);
    assert(mainQml.find("copy locally to inspect the preview URI") == std::string::npos);
    assert(mainQml.find("state=copy-preview-only") == std::string::npos);
    assert(mainQml.find("readonly property color focusRingColor") != std::string::npos);
    assert(mainQml.find("readonly property color focusGlowColor") != std::string::npos);
    assert(mainQml.find("cursorShape: Qt.BlankCursor") != std::string::npos);
    assert(mainQml.find("D-pad focus") != std::string::npos);
    assert(mainQml.find("Exact preview details stay behind Copy preview details") != std::string::npos);
    assert(mainQml.find("text: selectedLaunchPreviewText") == std::string::npos);
    assert(mainQml.find("font.family: monospace") == std::string::npos);
    assert(mainQml.find("Backend-fed library snapshot") != std::string::npos);
    assert(mainQml.find("Backend-fed hosts") != std::string::npos);
    assert(mainQml.find("Provenance: ") != std::string::npos);
    assert(mainQml.find("backend-owned read-only model") != std::string::npos);
    assert(mainQml.find("Preflight blockers") != std::string::npos);
    assert(mainQml.find("compactReadOnlyBlockerCopy") == std::string::npos);
    assert(mainQml.find("readOnlyBlockerDiagnostics") != std::string::npos);
    assert(mainQml.find("readOnlyDtoParityDiagnostics") != std::string::npos);
    assert(mainQml.find("primaryBlockerCopy") != std::string::npos);
    assert(mainQml.find("secondaryDiagnosticsCopy") != std::string::npos);
    assert(mainQml.find("Launch blocked by lab gate.") == std::string::npos);
    assert(mainQml.find("Host offline. Reconnect or pick another host.") == std::string::npos);
    assert(mainQml.find("Pair this host before launch preview.") == std::string::npos);
    assert(mainQml.find("Library unavailable. Try again when the read-only snapshot is back.") == std::string::npos);
    assert(mainQml.find("backendReadOnlyPreflight.publicCopy") != std::string::npos);
    assert(mainQml.find("Secondary diagnostics stay collapsed on first paint") != std::string::npos);
    assert(mainQml.find("visible: diagnosticsExpanded") != std::string::npos);
    assert(mainQml.find("property bool diagnosticsExpanded: false") != std::string::npos);
    assert(mainQml.find("id: secondaryDiagnosticsToggle") != std::string::npos);
    assert(mainQml.find("objectName: \"secondary-diagnostics-toggle\"") != std::string::npos);
    assert(mainQml.find("visible: true") != std::string::npos);
    assert(mainQml.find("D-pad focus · A · ") != std::string::npos);
    assert(mainQml.find("KeyNavigation.down: secondaryDiagnosticsToggle") != std::string::npos);
    assert(mainQml.find("secondaryDiagnosticsToggle.forceActiveFocus()") != std::string::npos);
    assert(mainQml.find("collapsedFirstPaint") != std::string::npos);
    assert(mainQml.find("expansionToggleControllerReachable") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsVisible") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsCopy") != std::string::npos);
    assert(mainQml.find("runExpandedDiagnosticsFrameSmoke") != std::string::npos);
    assert(mainQml.find("liveExpandedBy") != std::string::npos);
    assert(mainQml.find("expandedFrameSanitized") != std::string::npos);
    assert(mainQml.find("expandedFrameReadable") != std::string::npos);
    assert(mainQml.find("expandedFrameFocusTarget") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsLaneFocusTarget") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsLaneReadable") != std::string::npos);
    assert(mainQml.find("expandedDensityRowsPaged") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsPageAffordanceVisible") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsPageAffordancePosition") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsPageAffordanceText") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsScrollNavigationMoved") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsPostScrollCue") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsPostScrollCueContrast") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsPostScrollCueSpacing") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsPostScrollCueOverlapsBlocker") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsPostScrollTarget") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsFocusAffordance") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsPage2Readable") != std::string::npos);
    assert(mainQml.find("readonly property int expandedDiagnosticsLaneHeight: 132") != std::string::npos);
    assert(mainQml.find("readonly property string expandedDiagnosticsCueContrastRatio: \"13.56:1\"") != std::string::npos);
    assert(mainQml.find("readonly property string expandedDiagnosticsFocusAffordance: \"4px focus ring + active focus badge\"") != std::string::npos);
    assert(mainQml.find("id: diagnosticsPagePositionLabel") != std::string::npos);
    assert(mainQml.find("Diagnostics page 1 of 2 · scroll for lifecycle + DTO below") != std::string::npos);
    assert(mainQml.find("Diagnostics page 2 of 2 · lifecycle=") != std::string::npos);
    assert(mainQml.find("DTO privacy=") != std::string::npos);
    assert(mainQml.find("id: diagnosticsPostScrollCueLabel") != std::string::npos);
    assert(mainQml.find("id: diagnosticsPostScrollCueLabel\n"
                        "                                            Layout.preferredWidth: expandedDiagnosticsLane.width - 28\n"
                        "                                            Layout.topMargin: 8\n"
                        "                                            text: \"Diagnostics page 2 of 2 · lifecycle=\" + previewLifecycleReport.state\n"
                        "                                                + \"/no stream · DTO privacy=\" + backendDiagnosticsPreview.privacyCode\n"
                        "                                            color: \"#FFDDA8\"\n"
                        "                                            font.pixelSize: 10\n"
                        "                                            font.bold: true\n"
                        "                                            wrapMode: Text.WordWrap\n"
                        "                                            visible: true") != std::string::npos);
    assert(mainQml.find("id: expandedDiagnosticsPostScrollOverlay") != std::string::npos);
    assert(mainQml.find("Layout.topMargin: 8") != std::string::npos);
    assert(mainQml.find("id: expandedDiagnosticsPostScrollOverlay\n"
                        "                                    anchors.bottom: parent.bottom\n"
                        "                                    anchors.left: parent.left\n"
                        "                                    anchors.right: parent.right\n"
                        "                                    anchors.margins: 12\n"
                        "                                    z: 2\n"
                        "                                    text: \"Diagnostics page 2 of 2 · lifecycle=\" + previewLifecycleReport.state\n"
                        "                                        + \"/no stream · DTO privacy=\" + backendDiagnosticsPreview.privacyCode\n"
                        "                                    color: \"#FFDDA8\"\n"
                        "                                    font.pixelSize: 10\n"
                        "                                    font.bold: true\n"
                        "                                    wrapMode: Text.WordWrap\n"
                        "                                    visible: expandedDiagnosticsLaneScrolledToDetails") != std::string::npos);
    assert(mainQml.find("background: Rectangle") != std::string::npos);
    assert(mainQml.find("opacity: 0.94") != std::string::npos);
    assert(mainQml.find("text: \"FOCUS\"") != std::string::npos);
    assert(mainQml.find("Lifecycle page 2 · status=") != std::string::npos);
    assert(mainQml.find("DTO page 2 · preflight=") != std::string::npos);
    assert(mainQml.find("backend-owned-read-only-dto-v1") != std::string::npos);
    assert(mainQml.find("dto-parity-ready") != std::string::npos);
    assert(mainQml.find("id: lifecycleDiagnosticsPageLabel") != std::string::npos);
    assert(mainQml.find("id: dtoDiagnosticsPageLabel") != std::string::npos);
    assert(mainQml.find("id: expandedDiagnosticsLane") != std::string::npos);
    assert(mainQml.find("objectName: \"expanded-diagnostics-lane\"") != std::string::npos);
    assert(mainQml.find("id: expandedDiagnosticsScrollView") != std::string::npos);
    assert(mainQml.find("objectName: \"expanded-diagnostics-scroll-view\"") != std::string::npos);
    assert(mainQml.find("scrollExpandedDiagnosticsLaneToDetails") != std::string::npos);
    assert(mainQml.find("const page2AnchorY = lifecycleDiagnosticsPageLabel.y > 0 ? lifecycleDiagnosticsPageLabel.y - 6 : maxContentY") != std::string::npos);
    assert(mainQml.find("const targetContentY = Math.min(maxContentY, Math.max(0, page2AnchorY))") != std::string::npos);
    assert(mainQml.find("ScrollView") != std::string::npos);
    assert(mainQml.find("KeyNavigation.down: diagnosticsExpanded ? expandedDiagnosticsLane : copyPreviewButton") != std::string::npos);
    assert(mainQml.find("Keys.onDownPressed: diagnosticsExpanded ? expandedDiagnosticsLane.forceActiveFocus() : copyPreviewButton.forceActiveFocus()") != std::string::npos);
    assert(mainQml.find("expandedDiagnosticsLane.forceActiveFocus()") != std::string::npos);
    assert(mainQml.find("expandedFrameFirstPaintCrowding") != std::string::npos);
    assert(mainQml.find("secondaryDiagnosticsToggle.clicked()") != std::string::npos);
    assert(mainQml.find("id: diagnosticsPagePositionLabel") < mainQml.find("id: readonlyDiagnosticsLabel"));
    assert(mainQml.find("text: selectedLaunchPublicCopy\n                                color: \"#C9F0D4\"") != std::string::npos);
    assert(mainQml.find("visible: diagnosticsExpanded\n                                Layout.preferredWidth: detailTextWidth") != std::string::npos);
    assert(mainQml.find("maximumLineCount: 3") != std::string::npos);
    assert(mainQml.find("Matrix state: "
                        " + selectedBackendReadOnlyScenarioLabel\n"
                        "                                    + \" · Read-only model · \" + backendReadOnlyPreflight.statusCode") == std::string::npos);
    assert(mainQml.find("Polaris library preview") == std::string::npos);
    assert(mainQml.find("Preview lifecycle: ") == std::string::npos);
    assert(mainQml.find("Operator authorization: ") == std::string::npos);
    assert(mainQml.find("Backend preflight DTO: ") == std::string::npos);
    assert(mainQml.find("Backend diagnostics DTO: ") == std::string::npos);
    assert(mainQml.find("readonly property string deckPlayerFlowGate: \"deck-player-flow-product-shell-v1\"") != std::string::npos);
    assert(mainQml.find("Choose host → Pick game → Review safe launch plan") != std::string::npos);
    assert(mainQml.find("1 · Pick host") != std::string::npos);
    assert(mainQml.find("2 · Pick game") != std::string::npos);
    assert(mainQml.find("3 · Review launch plan") != std::string::npos);
    assert(mainQml.find("Selected game · A copies preview") != std::string::npos);
    assert(mainQml.find("A = Copy safe launch plan") != std::string::npos);
    assert(mainQml.find("Blocked safely: lab gate keeps backend power and streams off.") != std::string::npos);
    assert(mainQml.find("Diagnostics explain why; they never start discovery, backend power, or media.") != std::string::npos);
    assert(mainQml.find("readonly property string deckProductStateGate: \"deck-product-state-matrix-v1\"") != std::string::npos);
    assert(mainQml.find("function readOnlyProductStateHeadline") == std::string::npos);
    assert(mainQml.find("function readOnlyProductStateAction") == std::string::npos);
    assert(mainQml.find("function readOnlyProductStateSafety") == std::string::npos);
    assert(mainQml.find("Product state: Ready for setup") == std::string::npos);
    assert(mainQml.find("Product state: Host offline") == std::string::npos);
    assert(mainQml.find("Product state: Pair host") == std::string::npos);
    assert(mainQml.find("Product state: Library unavailable") == std::string::npos);
    assert(mainQml.find("Product state: Lab gate locked") == std::string::npos);
    assert(mainQml.find("Add a backend host before previewing a launch plan.") == std::string::npos);
    assert(mainQml.find("Reconnect the host or choose another backend-owned snapshot.") == std::string::npos);
    assert(mainQml.find("Pair this host in an approved flow before preview launch.") == std::string::npos);
    assert(mainQml.find("Wait for the read-only library snapshot to return.") == std::string::npos);
    assert(mainQml.find("Ask an operator to open the lab gate before any start path.") == std::string::npos);
    assert(mainQml.find("Focus order: state card → Copy plan → Show diagnostics") != std::string::npos);
    assert(mainQml.find("text: backendReadOnlyPlayerState.focusOrderCopy") != std::string::npos);
    assert(mainQml.find("DTO provenance: ") != std::string::npos);
    assert(mainQml.find("objectName: \"selected-game-readability-card\"") != std::string::npos);
    assert(mainQml.find("objectName: \"deck-player-flow-stepper\"") != std::string::npos);
    assert(mainQml.find("objectName: \"safe-launch-plan-cta\"") != std::string::npos);
    assert(mainQml.find("font.pixelSize: 26\n                                    font.bold: true") != std::string::npos);

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
