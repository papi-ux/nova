import QtQuick
import QtQuick.Controls

Button {
    id: control
    property real unit: 1
    property bool primary: false
    property bool destructive: false
    property bool quiet: false
    implicitHeight: Math.max(48, 52 * unit, (contentItem ? contentItem.implicitHeight : font.pixelSize) + 20 * unit)
    implicitWidth: Math.max(150 * unit, (contentItem ? contentItem.implicitWidth : 100) + 32 * unit)
    leftPadding: 16 * unit
    rightPadding: 16 * unit
    font.pixelSize: 18 * unit * NovaTheme.fontScale
    font.weight: Font.DemiBold
    Accessible.name: text
    opacity: enabled ? 1 : 0.5
    contentItem: Text {
        id: label
        text: control.text
        textFormat: Text.PlainText
        font: control.font
        color: control.activeFocus ? NovaTheme.focusText : NovaTheme.text
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }
    background: Rectangle {
        radius: 8 * control.unit
        color: control.activeFocus ? NovaTheme.focus : control.down ? NovaTheme.raised
            : control.primary ? NovaTheme.alpha(NovaTheme.accent, 0.3) : control.quiet ? "transparent" : NovaTheme.panel
        border.width: control.activeFocus ? 3 : NovaTheme.highContrast ? 2 : control.quiet ? 0 : 1
        border.color: control.activeFocus ? NovaTheme.focus
            : control.destructive ? NovaTheme.danger : control.primary ? NovaTheme.accent : NovaTheme.divider
    }
    Keys.onReturnPressed: event => { if (!event.isAutoRepeat && enabled) clicked() }
    Keys.onEnterPressed: event => { if (!event.isAutoRepeat && enabled) clicked() }
}
