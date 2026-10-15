#pragma once
#include <QString>

class QSettings;

namespace nova::deck::runtime {
// Records the theme an installation starts on, once, before QML first reads the
// store. A new installation, with nothing stored, starts on Director. One that
// ran before Director became the default and never chose a theme has been
// showing Polaris Aurora, so it keeps that theme: any key already in the store
// belongs to an earlier run. A stored theme, chosen or migrated, is never
// touched. Recording either choice keeps a later start, after this run has
// saved other settings, from mistaking a new installation for an earlier one.
// Returns the theme recorded, or an empty string when one was already stored.
QString recordStartingTheme(QSettings& settings);
// The application's own store, the one QSettings() and QML Settings address.
QString recordStartingTheme();
}
