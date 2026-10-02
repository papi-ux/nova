import QtQuick

NovaPercentSetting {
    objectName: "menu-opacity-settings-popup"
    title: "Menu Opacity"
    currentValue: NovaStreamPreferences.menuOpacityPercent
    minimumValue: 0
    maximumValue: 100
    resetValue: 64
    onValueSaved: value => NovaStreamPreferences.setMenuOpacity(value)
}
