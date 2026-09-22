pragma Singleton
import QtQuick
import QtCore

QtObject {
    id: theme
    // Names and colors follow Android Nova v1.4.11. This store contains only
    // device appearance choices; pairing and host/session authority stay in C++.
    // Read startup values without automatic property write-back. With a partial
    // record, Settings can queue defaults for later properties before loading
    // their saved values (for example textScale when themeId is absent).
    property Settings preferences: Settings { category: "Appearance" }
    property string selectedTheme: "polaris"
    property real selectedTextScale: 1
    Component.onCompleted: {
        selectedTheme = preferences.value("themeId", "polaris")
        selectedTextScale = preferences.value("textScale", 1)
    }
    property SystemPalette systemPalette: SystemPalette { colorGroup: SystemPalette.Active }
    readonly property var choices: [
        { id: "polaris", title: "Polaris Aurora" },
        { id: "portable_chrome", title: "Portable Chrome" },
        { id: "oled", title: "Console OLED" },
        { id: "miami", title: "Miami Nebula" },
        { id: "high_contrast", title: "High Contrast" },
        { id: "material_you", title: "Material You" }
    ]
    readonly property string themeId: choices.some(choice => choice.id === selectedTheme)
        ? selectedTheme : selectedTheme === "psp" ? "portable_chrome" : "polaris"
    readonly property string label: choices.find(choice => choice.id === themeId).title
    readonly property real fontScale: [1, 1.15, 1.3].includes(selectedTextScale) ? selectedTextScale : 1
    readonly property bool highContrast: themeId === "high_contrast"
    readonly property var colors: ({
        polaris: { window: "#2A2840", panel: "#343150", raised: "#3E3A5E", input: "#343150", text: "#C8D6E5", secondary: "#A8B0B8", muted: "#7A8E95", divider: "#4C5265", accent: "#7C73FF" },
        portable_chrome: { window: "#14161A", panel: "#1E2228", raised: "#262B33", input: "#1A1D22", text: "#C9D1D9", secondary: "#9AA4AF", muted: "#838D9C", divider: "#3A424C", accent: "#5A93D6" },
        oled: { window: "#000000", panel: "#0A0A0E", raised: "#101016", input: "#0A0A0E", text: "#E0E6ED", secondary: "#A8B0B8", muted: "#87919B", divider: "#363640", accent: "#8B80FF" },
        miami: { window: "#130817", panel: "#241429", raised: "#341E3A", input: "#2C1734", text: "#FFF1F7", secondary: "#FFD3E2", muted: "#85C7D4", divider: "#6C3C6F", accent: "#FF5CAB" },
        high_contrast: { window: "#05070C", panel: "#0F172A", raised: "#172033", input: "#111827", text: "#FFFFFF", secondary: "#E5E7EB", muted: "#CBD5E1", divider: "#DBEAFE", accent: "#60A5FA" },
        material_you: { window: "#17141C", panel: "#28232E", raised: "#34303B", input: "#211D26", text: "#E9E0EF", secondary: "#CAC4D0", muted: "#AAA3B2", divider: "#605968", accent: systemPalette.highlight }
    })[themeId]
    readonly property color window: colors.window
    readonly property color panel: colors.panel
    readonly property color raised: colors.raised
    readonly property color input: colors.input
    readonly property color text: colors.text
    readonly property color secondary: colors.secondary
    readonly property color muted: colors.muted
    readonly property color divider: colors.divider
    readonly property color accent: colors.accent
    readonly property color focus: "#FFFFFF"
    readonly property color focusText: "#111118"
    readonly property color warning: "#FFD0A0"
    readonly property color danger: "#FFADB7"
    function alpha(color, opacity) { return Qt.rgba(color.r, color.g, color.b, opacity) }
    function setTheme(id) {
        if (choices.some(choice => choice.id === id)) {
            selectedTheme = id
            preferences.setValue("themeId", id)
        }
    }
    function setFontScale(value) {
        if ([1, 1.15, 1.3].includes(value)) {
            selectedTextScale = value
            preferences.setValue("textScale", value)
        }
    }
}
