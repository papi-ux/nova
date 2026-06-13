#include "deck_layout.h"
#include "polaris_game_fixture.h"

#include <QCoreApplication>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QString>
#include <QTimer>
#include <QVariantList>
#include <QVariantMap>

#include <string_view>

namespace {
QString toQString(const std::string_view value) {
    return QString::fromUtf8(value.data(), static_cast<qsizetype>(value.size()));
}

QString toQString(const std::string& value) {
    return QString::fromStdString(value);
}

QVariantList toHostModel(const std::vector<nova::deck::DeckHostListItem>& hosts) {
    QVariantList model;
    for (const auto& host : hosts) {
        QVariantMap item;
        item.insert("id", toQString(host.id));
        item.insert("displayName", toQString(host.displayName));
        item.insert("statusLabel", toQString(host.statusLabel));
        item.insert("initialFocus", host.initialFocus);
        model.append(item);
    }
    return model;
}

QVariantMap toHostDetailModel(const nova::deck::DeckHostDetail& detail) {
    QVariantMap model;
    model.insert("id", toQString(detail.id));
    model.insert("displayName", toQString(detail.displayName));
    model.insert("statusLabel", toQString(detail.statusLabel));
    model.insert("subtitle", toQString(detail.subtitle));
    return model;
}

QVariantMap toLaunchCtaModel(const nova::deck::DeckLaunchCta& launchCta) {
    QVariantMap model;
    model.insert("id", toQString(launchCta.id));
    model.insert("label", toQString(launchCta.label));
    model.insert("helpText", toQString(launchCta.helpText));
    model.insert("enabled", launchCta.enabled);
    return model;
}
} // namespace

int main(int argc, char *argv[]) {
    QGuiApplication app(argc, argv);

    const auto profile = nova::deck::defaultWindowProfile();
    const auto sampleGame = nova::deck::loadSamplePolarisGameFixture();
    const auto demoHosts = nova::deck::demoHostListState();
    const auto selectedHostDetail = nova::deck::resolveHostDetail(demoHosts, nova::deck::initialHostFocusTarget(demoHosts));
    const auto launchCta = nova::deck::inertLaunchCtaFor(selectedHostDetail);

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
    engine.rootContext()->setContextProperty("novaDemoHosts", toHostModel(demoHosts));
    engine.rootContext()->setContextProperty("novaSelectedHostDetail", toHostDetailModel(selectedHostDetail));
    engine.rootContext()->setContextProperty("novaHostLaunchCta", toLaunchCtaModel(launchCta));
    engine.rootContext()->setContextProperty("novaInitialHostFocusTarget", toQString(nova::deck::initialHostFocusTarget(demoHosts)));
    engine.rootContext()->setContextProperty("novaEmptyHostFocusTarget", toQString(nova::deck::initialHostFocusTarget(nova::deck::emptyHostListState())));

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
