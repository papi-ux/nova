import QtQuick

Row {
    id: shortcut
    property real unit: 1
    property color color: NovaTheme.text
    readonly property real scaleUnit: unit * NovaTheme.fontScale
    spacing: 8 * scaleUnit
    Accessible.role: Accessible.StaticText
    Accessible.name: "Press the View and Menu buttons together to open Command Center"
    DeckButtonGlyph {
        button: "view"; color: shortcut.color
        width: 28 * shortcut.scaleUnit; height: width
    }
    Text {
        text: "+"; color: shortcut.color
        font.pixelSize: 18 * shortcut.scaleUnit
        anchors.verticalCenter: parent.verticalCenter
        Accessible.ignored: true
    }
    DeckButtonGlyph {
        button: "menu"; color: shortcut.color
        width: 28 * shortcut.scaleUnit; height: width
    }
    Text {
        text: "Command Center"; color: shortcut.color
        font.pixelSize: 16 * shortcut.scaleUnit
        anchors.verticalCenter: parent.verticalCenter
        Accessible.ignored: true
    }
}
