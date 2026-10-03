pragma Singleton
import QtQuick
import QtCore

QtObject {
    id: theme
    // Native Linux themes share Nova's palette. This store contains only
    // device appearance choices; pairing and host/session authority stay in C++.
    // Read startup values without automatic property write-back. With a partial
    // record, Settings can queue defaults for later properties before loading
    // their saved values (for example textScale when themeId is absent).
    property Settings preferences: Settings { category: "Appearance" }
    property string selectedTheme: "polaris"
    property real selectedTextScale: 0.8
    property string selectedControlSize: "standard"
    Component.onCompleted: {
        const savedTheme = preferences.value("themeId", "polaris")
        selectedTheme = savedTheme === "material_you" ? "polaris" : savedTheme
        selectedTextScale = validFontScale(preferences.value("textScale", 0.8))
        const savedSize = preferences.value("controlSize", "standard")
        selectedControlSize = controlSizes.some(choice => choice.id === savedSize) ? savedSize : "standard"
        // Retire the Android theme without rewriting unrelated saved choices.
        if (savedTheme === "material_you")
            preferences.setValue("themeId", "polaris")
    }
    readonly property var choices: [
        { id: "polaris", title: "Polaris Aurora" },
        { id: "portable_chrome", title: "Portable Chrome" },
        { id: "oled", title: "Console OLED" },
        { id: "miami", title: "Miami Nebula" },
        { id: "director", title: "< Congratulations, Director >" },
        { id: "high_contrast", title: "High Contrast" }
    ]
    readonly property string themeId: choices.some(choice => choice.id === selectedTheme)
        ? selectedTheme : selectedTheme === "psp" ? "portable_chrome" : "polaris"
    readonly property string label: choices.find(choice => choice.id === themeId).title
    readonly property var controlSizes: [
        { id: "compact", title: "Compact", scale: 0.72 },
        { id: "standard", title: "Standard", scale: 0.88 },
        { id: "large", title: "Large", scale: 1.15 }
    ]
    readonly property string controlSize: controlSizes.some(choice => choice.id === selectedControlSize)
        ? selectedControlSize : "standard"
    readonly property real controlScale: controlSizes.find(choice => choice.id === controlSize).scale
    readonly property real fontScale: validFontScale(selectedTextScale)
    readonly property bool highContrast: themeId === "high_contrast"
    readonly property var colors: ({
        polaris: { window: "#2A2840", panel: "#343150", raised: "#3E3A5E", input: "#343150", text: "#C8D6E5", secondary: "#A8B0B8", muted: "#7A8E95", divider: "#4C5265", accent: "#7C73FF" },
        portable_chrome: { window: "#14161A", panel: "#1E2228", raised: "#262B33", input: "#1A1D22", text: "#C9D1D9", secondary: "#9AA4AF", muted: "#838D9C", divider: "#3A424C", accent: "#5A93D6" },
        oled: { window: "#000000", panel: "#0A0A0E", raised: "#101016", input: "#0A0A0E", text: "#E0E6ED", secondary: "#A8B0B8", muted: "#87919B", divider: "#363640", accent: "#8B80FF" },
        miami: { window: "#130817", panel: "#241429", raised: "#341E3A", input: "#2C1734", text: "#FFF1F7", secondary: "#FFD3E2", muted: "#85C7D4", divider: "#6C3C6F", accent: "#FF5CAB" },
        director: { window: "#6E0B17", panel: "#881525", raised: "#9A1B2E", input: "#5E0A14", text: "#FFF5E8", secondary: "#F1D9CD", muted: "#DEC0B5", divider: "#12090B", accent: "#FFF5E8" },
        high_contrast: { window: "#05070C", panel: "#0F172A", raised: "#172033", input: "#111827", text: "#FFFFFF", secondary: "#E5E7EB", muted: "#CBD5E1", divider: "#DBEAFE", accent: "#60A5FA" }
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
    readonly property color focus: themeId === "director" ? "#FFF5E8" : "#FFFFFF"
    readonly property color focusText: themeId === "director" ? "#12090B" : "#111118"
    readonly property color warning: "#FFD0A0"
    readonly property color danger: themeId === "director" ? "#FFB7AC" : "#FFADB7"
    function alpha(color, opacity) { return Qt.rgba(color.r, color.g, color.b, opacity) }
    function setTheme(id) {
        if (choices.some(choice => choice.id === id)) {
            selectedTheme = id
            preferences.setValue("themeId", id)
        }
    }
    function setFontScale(value) {
        if (typeof value === "number" && isFinite(value) && value >= 0.8 && value <= 1.3) {
            selectedTextScale = Math.round(value * 100) / 100
            preferences.setValue("textScale", selectedTextScale)
        }
    }
    function validFontScale(value) {
        // INI-backed QSettings may return a saved number as a string after
        // restart. Accept decimal numbers only, without coercing booleans,
        // empty values or other strings into a smaller saved text size.
        const number = typeof value === "number" ? value
            : typeof value === "string" && /^\d+(?:\.\d+)?$/.test(value.trim()) ? Number(value.trim()) : NaN
        return isFinite(number) && number >= 0.8 && number <= 1.3
            ? Math.round(number * 100) / 100 : 0.8
    }
    function setControlSize(value) {
        if (controlSizes.some(choice => choice.id === value)) {
            selectedControlSize = value
            preferences.setValue("controlSize", value)
        }
    }
}
