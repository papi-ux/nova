#include "runtime/deck_appearance_defaults.h"
#include <QSettings>

namespace nova::deck::runtime {
QString recordStartingTheme(QSettings& settings) {
    const auto key = QStringLiteral("Appearance/themeId");
    if (settings.contains(key)) return {};
    // Only this store's own keys are an earlier run; a system-wide file is not.
    settings.setFallbacksEnabled(false);
    const auto theme = settings.allKeys().isEmpty() ? QStringLiteral("director") : QStringLiteral("polaris");
    settings.setValue(key, theme);
    settings.sync();
    return theme;
}

QString recordStartingTheme() {
    QSettings settings;
    return recordStartingTheme(settings);
}
}
