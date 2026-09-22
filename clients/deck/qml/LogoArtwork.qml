import QtQuick

// Match Android's logo frame and normalized position. Clipping keeps a 4x
// logo inside its artwork surface rather than over adjacent controls.
Item {
    id: art
    property url source: ""
    property var placement: ({scale: 1, x: 0.5, y: 0.5})
    readonly property int status: logo.status
    property string description: ""
    clip: true
    Image {
        id: logo
        objectName: "placed-logo-image"
        source: art.source
        asynchronous: true
        fillMode: Image.PreserveAspectFit
        width: parent.width * 0.56
        height: parent.height * 0.46
        x: (parent.width - width) * art.placement.x
        y: (parent.height - height) * art.placement.y
        scale: art.placement.scale
        transformOrigin: Item.Center
        Accessible.role: Accessible.Graphic
        Accessible.name: art.description
    }
}
