import QtQuick

// Font-independent versions of the symbols printed on the Deck's View/Menu keys.
Item {
    id: glyph
    property string button: "menu"
    property color color: NovaTheme.text
    implicitWidth: 28
    implicitHeight: 28
    Accessible.ignored: true
    Rectangle {
        anchors.fill: parent
        radius: width / 2
        color: "transparent"
        border.color: glyph.color
        border.width: Math.max(1, glyph.width / 28)
    }
    Item {
        visible: glyph.button === "view"
        anchors.fill: parent
        Rectangle {
            x: glyph.width * 0.23; y: glyph.height * 0.27
            width: glyph.width * 0.37; height: glyph.height * 0.34
            color: "transparent"; border.color: glyph.color
            border.width: Math.max(1.5, glyph.width / 18)
        }
        Rectangle {
            x: glyph.width * 0.40; y: glyph.height * 0.43
            width: glyph.width * 0.37; height: glyph.height * 0.34
            color: "transparent"; border.color: glyph.color
            border.width: Math.max(1.5, glyph.width / 18)
        }
    }
    Repeater {
        model: glyph.button === "menu" ? 3 : 0
        Rectangle {
            required property int index
            x: glyph.width * 0.27
            y: glyph.height * (0.30 + index * 0.18)
            width: glyph.width * 0.46
            height: Math.max(1.5, glyph.height / 16)
            color: glyph.color
        }
    }
}
