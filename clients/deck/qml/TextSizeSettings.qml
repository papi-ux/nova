import QtQuick

NovaPercentSetting {
    objectName: "text-size-settings-popup"
    title: "Text Size"
    currentValue: Math.round(NovaTheme.fontScale * 100)
    minimumValue: 80
    maximumValue: 130
    resetValue: 80
    textPreview: true
    onValueSaved: value => NovaTheme.setFontScale(value / 100)
}
