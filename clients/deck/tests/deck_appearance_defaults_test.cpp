#include "runtime/deck_appearance_defaults.h"
#include <QCoreApplication>
#include <QSettings>
#include <QTemporaryDir>
#include <QVariantMap>
#include <cstdlib>
#include <iostream>
using namespace nova::deck::runtime;
void require(bool value, const char* message) { if (!value) { std::cerr << message << '\n'; std::exit(1); } }
QVariantMap contents(QSettings& settings) {
    settings.sync(); QVariantMap result;
    for (const auto& key : settings.allKeys()) result[key] = settings.value(key);
    return result;
}
int main(int argc, char** argv) {
    QTemporaryDir config; require(config.isValid(), "missing isolated appearance settings");
    qputenv("XDG_CONFIG_HOME", config.path().toUtf8());
    QCoreApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("AppearanceDefaults");
    const auto file = [&](const QString& name) { return config.filePath(name + ".ini"); };

    // A new installation starts on Director, and a later start, after this run saved
    // other settings, keeps it rather than mistaking the store for an earlier run's.
    QSettings fresh(file("fresh"), QSettings::IniFormat);
    require(recordStartingTheme(fresh) == "director", "a new installation did not start on Director");
    fresh.setValue("Window/fullscreen", false); fresh.setValue("Library/layoutMode", "grid"); fresh.sync();
    QSettings restarted(file("fresh"), QSettings::IniFormat);
    require(recordStartingTheme(restarted).isEmpty() && contents(restarted).value("Appearance/themeId") == "director",
        "a second start moved a new installation off Director");

    // An earlier installation that never chose a theme keeps Polaris Aurora, and nothing else changes.
    QSettings earlier(file("earlier"), QSettings::IniFormat);
    earlier.setValue("Window/fullscreen", false); earlier.setValue("Appearance/textScale", 1.15);
    earlier.setValue("Library/layoutMode", "stage"); earlier.sync();
    auto expected = contents(earlier); expected["Appearance/themeId"] = "polaris";
    require(recordStartingTheme(earlier) == "polaris", "an earlier installation that never chose a theme lost Polaris Aurora");
    QSettings reread(file("earlier"), QSettings::IniFormat);
    require(contents(reread) == expected, "keeping the earlier theme changed another saved choice");
    require(recordStartingTheme(reread).isEmpty() && contents(reread) == expected, "the earlier theme was recorded twice");

    // A stored theme stays, whichever it is; Material You still migrates in QML.
    for (const QString& chosen : {QString("miami"), QString("director"), QString("polaris"), QString("material_you")}) {
        QSettings store(file("chosen-" + chosen), QSettings::IniFormat);
        store.setValue("Appearance/themeId", chosen); store.setValue("Appearance/controlSize", "large"); store.sync();
        const auto before = contents(store);
        require(recordStartingTheme(store).isEmpty() && contents(store) == before, "a stored theme was replaced");
    }

    // The application store QML Settings reads: an earlier run's library choice keeps Polaris Aurora.
    { QSettings application; application.setValue("Library/layoutMode", "grid"); application.sync(); }
    require(recordStartingTheme() == "polaris", "the application store did not keep the earlier theme");
    QSettings application;
    require(application.value("Appearance/themeId") == "polaris" && application.value("Library/layoutMode") == "grid",
        "the application store did not record Polaris Aurora beside its earlier choices");
    std::cout << "New installations start on Director; earlier installations and stored themes keep theirs\n";
}
