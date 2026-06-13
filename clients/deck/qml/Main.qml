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
        Component.onCompleted: sampleGameCard.forceActiveFocus()

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
                        Layout.preferredWidth: 650
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
                                    "Initial focus starts on the library card",
                                    "D-pad right moves focus to this details placeholder",
                                    "D-pad left returns focus to the library card"
                                ]

                                delegate: Rectangle {
                                    Layout.preferredWidth: 594
                                    Layout.preferredHeight: 44
                                    radius: 14
                                    color: "#1B2445"
                                    border.color: index === 0 ? "#7C73FF" : "#39466F"
                                    border.width: 2

                                    Label {
                                        anchors.centerIn: parent
                                        text: modelData
                                        color: "#E9ECFF"
                                        font.pixelSize: 18
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
