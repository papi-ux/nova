import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ApplicationWindow {
    id: root

    width: novaDeckWidth
    height: novaDeckHeight
    visible: true
    title: novaDeckShellName
    color: "#070B18"

    readonly property int deckSafeMargin: 32
    readonly property int deckShellSpacing: 16
    readonly property int deckPanelSpacing: 12
    readonly property int deckRowSpacing: 16
    readonly property int hostColumnWidth: 336
    readonly property int sampleCardWidth: 392
    readonly property int detailColumnWidth: 424
    readonly property int hostCardHeight: 104
    readonly property int detailPanelHeight: 196
    readonly property int launchPreviewHeight: 236
    readonly property int hostTextWidth: hostColumnWidth - 40
    readonly property int sampleTextWidth: sampleCardWidth - 48
    readonly property int detailTextWidth: detailColumnWidth - 48
    property int previewCopyActivationCount: 0

    function activateLaunchPreviewCopyFromController() {
        const canCopyPreview = novaLaunchPreviewCopyAction.enabled
            && novaLaunchPreviewCopyAction.previewText.length > 0
            && novaLaunchPreviewCopyAction.copyOnly
            && novaLaunchPreviewCopyAction.uiLocalClipboardOnly
            && !novaLaunchPreviewCopyAction.executable
        const didCopyPreview = canCopyPreview
            && novaLocalClipboard.copyPreviewText(novaLaunchPreviewCopyAction.previewText)
        if (didCopyPreview) {
            previewCopyActivationCount += 1
        }
        copyStatusLabel.text = didCopyPreview
            ? novaLaunchPreviewCopyAction.successToast + " · A pressed #" + previewCopyActivationCount
            : novaLaunchPreviewCopyAction.inertToast + " · A press stayed preview-only"
        copyStatusLabel.color = didCopyPreview ? "#8AFFC1" : "#FFDDA8"
    }

    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#111936" }
            GradientStop { position: 1.0; color: "#070B18" }
        }
    }

    Connections {
        target: novaGamepad
        function onPrimaryActionPressed(activationCount) {
            activateLaunchPreviewCopyFromController()
        }
    }

    FocusScope {
        id: libraryFocusScope
        anchors.fill: parent
        focus: true
        Component.onCompleted: Qt.callLater(function() {
            if (novaDemoHosts.length > 0 && hostRepeater.itemAt(0) !== null) {
                hostRepeater.itemAt(0).forceActiveFocus()
            } else {
                emptyHostState.forceActiveFocus()
            }
        })

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: deckSafeMargin
            spacing: deckShellSpacing

            Label {
                text: novaDeckShellName
                color: "#E9ECFF"
                font.pixelSize: 48
                font.bold: true
            }

            Label {
                text: "Controller-first Steam Deck shell scaffold"
                color: "#A8B0D8"
                font.pixelSize: 24
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 2
                color: "#7C73FF"
                opacity: 0.65
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: deckRowSpacing

                ColumnLayout {
                    Layout.preferredWidth: hostColumnWidth
                    spacing: deckPanelSpacing

                    Label {
                        text: "Demo hosts"
                        color: "#E9ECFF"
                        font.pixelSize: 28
                        font.bold: true
                    }

                    Rectangle {
                        id: emptyHostState
                        objectName: "host-empty-state"
                        visible: novaDemoHosts.length === 0
                        Layout.preferredWidth: hostColumnWidth
                        Layout.preferredHeight: visible ? 120 : 0
                        radius: 20
                        color: activeFocus ? "#202B55" : "#151D39"
                        border.color: activeFocus ? "#B8C2FF" : "#39466F"
                        border.width: activeFocus ? 5 : 2
                        focus: visible
                        activeFocusOnTab: visible
                        KeyNavigation.right: hostDetailPanel
                        Keys.onRightPressed: hostDetailPanel.forceActiveFocus()

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 18
                            spacing: 5

                            Label {
                                text: "No demo hosts yet"
                                color: "#E9ECFF"
                                font.pixelSize: 22
                                font.bold: true
                            }

                            Label {
                                text: "Empty host state is focusable and deterministic."
                                color: "#A8B0D8"
                                font.pixelSize: 12
                            }
                        }
                    }

                    Repeater {
                        id: hostRepeater
                        model: novaDemoHosts

                        delegate: Rectangle {
                            required property int index
                            required property var modelData

                            objectName: modelData.id
                            Layout.preferredWidth: hostColumnWidth
                            Layout.preferredHeight: hostCardHeight
                            radius: 20
                            color: activeFocus ? "#202B55" : "#151D39"
                            border.color: activeFocus ? "#B8C2FF" : "#7C73FF"
                            border.width: activeFocus ? 5 : 3
                            focus: modelData.initialFocus
                            activeFocusOnTab: true
                            KeyNavigation.right: hostDetailPanel
                            Keys.onRightPressed: hostDetailPanel.forceActiveFocus()
                            Keys.onDownPressed: {
                                const next = hostRepeater.itemAt(index + 1)
                                if (next !== null) {
                                    next.forceActiveFocus()
                                }
                            }
                            Keys.onUpPressed: {
                                const previous = hostRepeater.itemAt(index - 1)
                                if (previous !== null) {
                                    previous.forceActiveFocus()
                                }
                            }

                            ColumnLayout {
                                anchors.fill: parent
                                anchors.margins: 18
                                spacing: 5

                                Label {
                                    text: modelData.displayName
                                    color: "#E9ECFF"
                                    font.pixelSize: 20
                                    font.bold: true
                                }

                                Label {
                                    text: modelData.statusLabel
                                    color: "#B8C2F0"
                                    font.pixelSize: 16
                                }
                            }
                        }
                    }
                }

                ColumnLayout {
                    id: libraryGameList
                    objectName: "library-game-list"
                    Layout.preferredWidth: sampleCardWidth
                    spacing: deckPanelSpacing

                    Label {
                        text: "Read-only Polaris library"
                        color: "#E9ECFF"
                        font.pixelSize: 24
                        font.bold: true
                    }

                    Label {
                        Layout.preferredWidth: sampleTextWidth
                        text: novaLibraryFixtureSource + (novaLibraryReadOnly ? " · read-only" : "")
                        color: "#A8B0D8"
                        font.pixelSize: 13
                        wrapMode: Text.WordWrap
                    }

                    Repeater {
                        id: libraryGameRepeater
                        model: novaLibraryGames

                        delegate: Rectangle {
                            required property int index
                            required property var modelData

                            objectName: modelData.id
                            Layout.preferredWidth: sampleCardWidth
                            Layout.preferredHeight: 88
                            radius: 18
                            color: activeFocus ? "#202B55" : "#151D39"
                            border.color: activeFocus ? "#B8C2FF" : "#7C73FF"
                            border.width: activeFocus ? 5 : 2
                            focus: modelData.initialFocus
                            activeFocusOnTab: true
                            KeyNavigation.right: hostDetailPanel
                            Keys.onRightPressed: hostDetailPanel.forceActiveFocus()
                            Keys.onDownPressed: {
                                const next = libraryGameRepeater.itemAt(index + 1)
                                if (next !== null) {
                                    next.forceActiveFocus()
                                }
                            }
                            Keys.onUpPressed: {
                                const previous = libraryGameRepeater.itemAt(index - 1)
                                if (previous !== null) {
                                    previous.forceActiveFocus()
                                }
                            }
                            Keys.onLeftPressed: {
                                if (novaDemoHosts.length > 0 && hostRepeater.itemAt(0) !== null) {
                                    hostRepeater.itemAt(0).forceActiveFocus()
                                } else {
                                    emptyHostState.forceActiveFocus()
                                }
                            }

                            ColumnLayout {
                                anchors.fill: parent
                                anchors.margins: 16
                                spacing: 4

                                Label {
                                    text: modelData.title
                                    color: "#E9ECFF"
                                    font.pixelSize: 20
                                    font.bold: true
                                }

                                Label {
                                    text: modelData.sourceRuntimeLabel + " · " + modelData.installedLabel
                                    color: "#B8C2F0"
                                    font.pixelSize: 13
                                }

                                Label {
                                    text: modelData.launchModeLabel
                                    color: "#A8B0D8"
                                    font.pixelSize: 12
                                }
                            }
                        }
                    }
                }

                ColumnLayout {
                    Layout.preferredWidth: detailColumnWidth
                    spacing: deckPanelSpacing

                    Rectangle {
                        id: hostDetailPanel
                        objectName: "host-detail-panel"
                        Layout.preferredWidth: detailColumnWidth
                        Layout.preferredHeight: detailPanelHeight
                        radius: 22
                        color: activeFocus ? "#202B55" : "#151D39"
                        border.color: activeFocus ? "#B8C2FF" : "#39466F"
                        border.width: activeFocus ? 5 : 2
                        focus: true
                        activeFocusOnTab: true
                        KeyNavigation.left: hostRepeater.itemAt(0) !== null ? hostRepeater.itemAt(0) : emptyHostState
                        KeyNavigation.down: launchCtaPlaceholder
                        Keys.onLeftPressed: {
                            if (novaDemoHosts.length > 0 && hostRepeater.itemAt(0) !== null) {
                                hostRepeater.itemAt(0).forceActiveFocus()
                            } else {
                                emptyHostState.forceActiveFocus()
                            }
                        }
                        Keys.onDownPressed: launchCtaPlaceholder.forceActiveFocus()

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 20
                            spacing: 8

                            Label {
                                text: "Demo host detail"
                                color: "#7C88B8"
                                font.pixelSize: 16
                            }

                            Label {
                                text: novaSelectedHostDetail.displayName
                                color: "#E9ECFF"
                                font.pixelSize: 30
                                font.bold: true
                            }

                            Label {
                                text: novaSelectedHostDetail.statusLabel
                                color: "#B8C2F0"
                                font.pixelSize: 19
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaSelectedHostDetail.subtitle
                                color: "#A8B0D8"
                                font.pixelSize: 16
                                wrapMode: Text.WordWrap
                            }
                        }
                    }

                    Rectangle {
                        id: launchCtaPlaceholder
                        objectName: novaHostLaunchCta.id
                        Layout.preferredWidth: detailColumnWidth
                        Layout.preferredHeight: launchPreviewHeight
                        radius: 20
                        color: activeFocus ? "#2A2948" : "#181D34"
                        border.color: activeFocus ? "#B8C2FF" : "#39466F"
                        border.width: activeFocus ? 5 : 2
                        opacity: novaHostLaunchCta.enabled ? 1.0 : 0.72
                        focus: false
                        activeFocusOnTab: true
                        KeyNavigation.up: hostDetailPanel
                        KeyNavigation.down: copyPreviewButton
                        Keys.onUpPressed: hostDetailPanel.forceActiveFocus()
                        Keys.onDownPressed: copyPreviewButton.forceActiveFocus()
                        Keys.onReturnPressed: activateLaunchPreviewCopyFromController()
                        Keys.onEnterPressed: activateLaunchPreviewCopyFromController()
                        Keys.onSpacePressed: activateLaunchPreviewCopyFromController()
                        Keys.onLeftPressed: {
                            if (novaDemoHosts.length > 0 && hostRepeater.itemAt(0) !== null) {
                                hostRepeater.itemAt(0).forceActiveFocus()
                            } else {
                                emptyHostState.forceActiveFocus()
                            }
                        }

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 18
                            spacing: 5

                            Label {
                                text: novaHostLaunchCta.label
                                color: "#E9ECFF"
                                font.pixelSize: 20
                                font.bold: true
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaHostLaunchCta.helpText
                                color: "#B8C2F0"
                                font.pixelSize: 12
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaHostLaunchCta.previewStateLabel
                                color: "#FFDDA8"
                                font.pixelSize: 12
                                font.bold: true
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: "Typed launch boundary: " + novaLaunchIntentBoundary.label + " · network/process/Moonlight blocked"
                                color: "#FFDDA8"
                                font.pixelSize: 11
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaLaunchIntentBoundary.reason
                                color: "#A8B0D8"
                                font.pixelSize: 10
                                wrapMode: Text.WordWrap
                            }

                            Label {
                                Layout.preferredWidth: detailTextWidth
                                text: novaHostLaunchCta.previewText
                                color: "#A8B0D8"
                                font.pixelSize: 12
                                font.family: "monospace"
                                wrapMode: Text.WrapAnywhere
                            }

                            Button {
                                id: copyPreviewButton
                                objectName: novaLaunchPreviewCopyAction.id
                                text: activeFocus ? "A · " + novaLaunchPreviewCopyAction.label : novaLaunchPreviewCopyAction.label
                                enabled: novaLaunchPreviewCopyAction.enabled
                                focusPolicy: Qt.StrongFocus
                                activeFocusOnTab: true
                                KeyNavigation.up: launchCtaPlaceholder
                                Keys.onUpPressed: launchCtaPlaceholder.forceActiveFocus()
                                Keys.onLeftPressed: {
                                    if (novaDemoHosts.length > 0 && hostRepeater.itemAt(0) !== null) {
                                        hostRepeater.itemAt(0).forceActiveFocus()
                                    } else {
                                        emptyHostState.forceActiveFocus()
                                    }
                                }
                                Keys.onReturnPressed: activateLaunchPreviewCopyFromController()
                                Keys.onEnterPressed: activateLaunchPreviewCopyFromController()
                                Keys.onSpacePressed: activateLaunchPreviewCopyFromController()
                                onClicked: activateLaunchPreviewCopyFromController()
                            }

                            Label {
                                id: copyStatusLabel
                                Layout.preferredWidth: detailTextWidth
                                text: novaLaunchPreviewCopyAction.idleStatusLabel + " Press A on Copy to verify."
                                color: "#FFDDA8"
                                font.pixelSize: 12
                                wrapMode: Text.WordWrap
                            }
                        }
                    }
                }
            }

            Item { Layout.fillHeight: true }

            Label {
                text: novaDeckFullscreenPreferred
                    ? "Deck default: 1280×800 · fullscreen-first · controller-first"
                    : "Deck default: 1280×800 · windowed test mode"
                color: "#7C88B8"
                font.pixelSize: 18
            }
        }
    }
}
