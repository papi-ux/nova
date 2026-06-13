#include "deck_layout.h"
#include "polaris_game_fixture.h"

#include <QCoreApplication>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QString>
#include <QTimer>

#include <string_view>

namespace {
QString toQString(const std::string_view value) {
    return QString::fromUtf8(value.data(), static_cast<qsizetype>(value.size()));
}

QString toQString(const std::string& value) {
    return QString::fromStdString(value);
}
} // namespace

int main(int argc, char *argv[]) {
    QGuiApplication app(argc, argv);

    const auto profile = nova::deck::defaultWindowProfile();
    const auto sampleGame = nova::deck::loadSamplePolarisGameFixture();

    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty("novaDeckShellName", toQString(profile.shellName));
    engine.rootContext()->setContextProperty("novaDeckWidth", profile.width);
    engine.rootContext()->setContextProperty("novaDeckHeight", profile.height);
    engine.rootContext()->setContextProperty("novaDeckFullscreenPreferred", profile.fullscreenPreferred);
    engine.rootContext()->setContextProperty("novaSampleGameName", toQString(sampleGame.name));
    engine.rootContext()->setContextProperty("novaSampleGameSource", toQString(sampleGame.source));
    engine.rootContext()->setContextProperty("novaSampleGameRuntime", toQString(sampleGame.runtime));
    engine.rootContext()->setContextProperty("novaSampleGameLaunchMode", toQString(sampleGame.launchMode.recommendedMode));
    engine.rootContext()->setContextProperty("novaSampleGameSteamMode", toQString(sampleGame.steamLaunch.recommendedMode));

    const bool smokeExit = QCoreApplication::arguments().contains("--smoke-exit");

    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreationFailed,
        &app,
        []() { QCoreApplication::exit(-1); },
        Qt::QueuedConnection
    );
    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreated,
        &app,
        [smokeExit, &app](QObject *object) {
            if (smokeExit && object != nullptr) {
                QTimer::singleShot(0, &app, &QCoreApplication::quit);
            }
        },
        Qt::QueuedConnection
    );

    engine.loadFromModule("Nova.Deck", "Main");

    return app.exec();
}
