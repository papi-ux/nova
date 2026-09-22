import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Flickable {
    id: viewport
    default property alias contents: column.data
    property alias spacing: column.spacing
    property bool reserveScrollBarSpace: false
    implicitHeight: column.implicitHeight
    contentHeight: column.implicitHeight
    contentWidth: width
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    flickableDirection: Flickable.VerticalFlick
    ScrollBar.vertical: ScrollBar { }
    function revealFocus() {
        const item = Window.window ? Window.window.activeFocusItem : null
        if (!item) return
        let ancestor = item
        while (ancestor && ancestor !== column) ancestor = ancestor.parent
        if (!ancestor) return
        const point = item.mapToItem(column, 0, 0)
        if (point.y < contentY) contentY = point.y
        else if (point.y + item.height > contentY + height) contentY = point.y + item.height - height
        contentY = Math.max(0, Math.min(contentY, Math.max(0, contentHeight - height)))
    }
    Connections {
        target: viewport.Window.window
        function onActiveFocusItemChanged() { Qt.callLater(viewport.revealFocus) }
    }
    onHeightChanged: Qt.callLater(revealFocus)
    onWidthChanged: Qt.callLater(revealFocus)
    onContentHeightChanged: Qt.callLater(revealFocus)
    ColumnLayout { id: column; width: viewport.width - (viewport.reserveScrollBarSpace || viewport.contentHeight > viewport.height ? 12 : 0) }
}
