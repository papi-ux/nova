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

    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#111936" }
            GradientStop { position: 1.0; color: "#070B18" }
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
            anchors.margins: 56
            spacing: 24

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
                spacing: 24

                ColumnLayout {
                    Layout.preferredWidth: 480
                    spacing: 12

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
                        Layout.preferredWidth: 480
                        Layout.preferredHeight: visible ? 132 : 0
                        radius: 20
                        color: activeFocus ? "#202B55" : "#151D39"
                        border.color: activeFocus ? "#B8C2FF" : "#39466F"
                        border.width: activeFocus ? 5 : 2
                        focus: visible
                        activeFocusOnTab: visible
                        KeyNavigation.right: sampleGameCard
                        Keys.onRightPressed: sampleGameCard.forceActiveFocus()

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 20
                            spacing: 8

                            Label {
                                text: "No demo hosts yet"
                                color: "#E9ECFF"
                                font.pixelSize: 24
                                font.bold: true
                            }

                            Label {
                                text: "Empty host state is focusable and deterministic."
                                color: "#A8B0D8"
                                font.pixelSize: 17
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
                            Layout.preferredWidth: 480
                            Layout.preferredHeight: 116
                            radius: 20
                            color: activeFocus ? "#202B55" : "#151D39"
                            border.color: activeFocus ? "#B8C2FF" : "#7C73FF"
                            border.width: activeFocus ? 5 : 3
                            focus: modelData.initialFocus
                            activeFocusOnTab: true
                            KeyNavigation.right: sampleGameCard
                            Keys.onRightPressed: sampleGameCard.forceActiveFocus()
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
                                anchors.margins: 20
                                spacing: 6

                                Label {
                                    text: modelData.displayName
                                    color: "#E9ECFF"
                                    font.pixelSize: 26
                                    font.bold: true
                                }

                                Label {
                                    text: modelData.statusLabel
                                    color: "#B8C2F0"
                                    font.pixelSize: 18
                                }
                            }
                        }
                    }
                }

                Rectangle {
                    id: sampleGameCard
                    objectName: "sample-game-card"
                    Layout.preferredWidth: 480
                    Layout.preferredHeight: 220
                    radius: 22
                    color: activeFocus ? "#202B55" : "#151D39"
                    border.color: activeFocus ? "#B8C2FF" : "#7C73FF"
                    border.width: activeFocus ? 5 : 3
                    focus: true
                    activeFocusOnTab: true
                    KeyNavigation.right: detailsPlaceholder
                    Keys.onRightPressed: detailsPlaceholder.forceActiveFocus()
                    Keys.onDownPressed: detailsPlaceholder.forceActiveFocus()
                    Keys.onLeftPressed: {
                        if (novaDemoHosts.length > 0 && hostRepeater.itemAt(0) !== null) {
                            hostRepeater.itemAt(0).forceActiveFocus()
                        } else {
                            emptyHostState.forceActiveFocus()
                        }
                    }

                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: 24
                        spacing: 10

                        Label {
                            text: "Shared Polaris DTO fixture"
                            color: "#7C88B8"
                            font.pixelSize: 18
                        }

                        Label {
                            text: novaSampleGameName
                            color: "#E9ECFF"
                            font.pixelSize: 34
                            font.bold: true
                        }

                        Label {
                            text: novaSampleGameSource + " · " + novaSampleGameRuntime
                            color: "#B8C2F0"
                            font.pixelSize: 20
                        }

                        Label {
                            text: "Stream: " + novaSampleGameLaunchMode + " · Steam: " + novaSampleGameSteamMode
                            color: "#A8B0D8"
                            font.pixelSize: 18
                        }
                    }
                }

                ColumnLayout {
                    spacing: 12

                    Rectangle {
                        id: detailsPlaceholder
                        objectName: "details-placeholder"
                        Layout.preferredWidth: 410
                        Layout.preferredHeight: 220
                        radius: 22
                        color: activeFocus ? "#202B55" : "#151D39"
                        border.color: activeFocus ? "#B8C2FF" : "#39466F"
                        border.width: activeFocus ? 5 : 2
                        focus: true
                        activeFocusOnTab: true
                        KeyNavigation.left: sampleGameCard
                        Keys.onLeftPressed: sampleGameCard.forceActiveFocus()
                        Keys.onUpPressed: sampleGameCard.forceActiveFocus()

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 24
                            spacing: 12

                            Label {
                                text: "Controller placeholder scope"
                                color: "#E9ECFF"
                                font.pixelSize: 28
                                font.bold: true
                            }

                            Repeater {
                                model: [
                                    "Initial focus starts on the first demo host",
                                    "D-pad down moves through host rows",
                                    "D-pad right enters the library/details lane"
                                ]

                                delegate: Rectangle {
                                    Layout.preferredWidth: 354
                                    Layout.preferredHeight: 44
                                    radius: 14
                                    color: "#1B2445"
                                    border.color: index === 0 ? "#7C73FF" : "#39466F"
                                    border.width: 2

                                    Label {
                                        anchors.centerIn: parent
                                        text: modelData
                                        color: "#E9ECFF"
                                        font.pixelSize: 16
                                    }
                                }
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
