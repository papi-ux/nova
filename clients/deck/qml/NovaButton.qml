import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Button {
    id: control
    property real unit: 1
    property bool primary: false
    property bool destructive: false
    property bool quiet: false
    // Density changes spacing and the surface, independently of text size.
    // Even Compact keeps a usable touch target on small windows.
    readonly property real geometryScale: unit * NovaTheme.controlScale
    implicitHeight: Math.max(48, 64 * geometryScale, (contentItem ? contentItem.implicitHeight : font.pixelSize) + 20 * geometryScale)
    implicitWidth: Math.max(48, 150 * geometryScale, (contentItem ? contentItem.implicitWidth : 100) + 32 * geometryScale)
    Layout.minimumHeight: implicitHeight
    topPadding: 10 * geometryScale
    bottomPadding: 10 * geometryScale
    topInset: 0
    bottomInset: 0
    leftPadding: 16 * geometryScale
    rightPadding: 16 * geometryScale
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
        wrapMode: Text.Wrap
        elide: Text.ElideNone
    }
    background: Rectangle {
        radius: 8 * control.geometryScale
        color: control.activeFocus ? NovaTheme.focus : control.down ? NovaTheme.raised
            : control.primary ? NovaTheme.alpha(NovaTheme.accent, 0.3) : control.quiet ? "transparent" : NovaTheme.panel
        border.width: control.activeFocus ? 3 : NovaTheme.highContrast ? 2 : control.quiet ? 0 : 1
        border.color: control.activeFocus ? NovaTheme.focus
            : control.destructive ? NovaTheme.danger : control.primary ? NovaTheme.accent : NovaTheme.divider
    }
    Keys.onReturnPressed: event => { if (!event.isAutoRepeat && enabled) clicked() }
    Keys.onEnterPressed: event => { if (!event.isAutoRepeat && enabled) clicked() }
}
