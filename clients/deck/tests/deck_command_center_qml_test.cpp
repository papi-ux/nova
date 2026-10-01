#include "runtime/deck_play_settings.h"
#include "stream/deck_stream_media_adapters.h"
#include "deck_preview_fixture.h"
#include <QGuiApplication>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QTemporaryDir>
#include <QTest>
#include <QDir>
#include <iostream>
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
int main(int argc, char** argv) {
    QTemporaryDir directory; qputenv("XDG_CONFIG_HOME", directory.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("Density");
    qmlRegisterType<nova::deck::stream::DeckQtQuickRhiVaapiItem>("Nova.Deck.Stream", 0, 1, "DeckVaapiPreviewSurface");
    PreviewSession session; PreviewPlayers players(session);
    nova::deck::runtime::DeckPlaySettings settings(directory.filePath("play.ini"));
    settings.setVideoDecodeSupport({.h264 = {4096,4096}, .hevc = {4096,4096}});
    session.startConfigured("fixture-host", "fixture-game", {});
    session.transition("active", true, "Your game is connected.");
    QQmlEngine engine; engine.rootContext()->setContextProperty("testSession", &session);
    engine.rootContext()->setContextProperty("testPlayers", &players); engine.rootContext()->setContextProperty("testSettings", &settings);
    QQmlComponent component(&engine);
    component.setData(R"(import QtQuick
import QtQuick.Controls
ApplicationWindow {
    width:1280; height:800; visible:true
    NativeStreamPreview { id:preview; session:testSession; inputHub:testPlayers; settingsProvider:testSettings
        hostId:"fixture-host"; gameId:"fixture-game"; gameTitle:"Moonlit Harbor"; hostName:"Living Room PC" }
    function largeText() { NovaTheme.setFontScale(1.3) }
    Component.onCompleted: { preview.open(); preview.attempted=true }
})", QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/CommandTest.qml"));
    auto root = std::unique_ptr<QObject>(component.create()); require(bool(root), qPrintable(component.errorString()));
    auto* window = qobject_cast<QQuickWindow*>(root.get()); require(window, "missing Command Center window"); QTest::qWait(150);
    const auto item = [&](const char* name) { auto* found = root->findChild<QQuickItem*>(name); require(found && found->isVisible(), name); return found; };
    const auto rect = [&](const char* name) { return item(name)->mapRectToScene(item(name)->boundingRect()); };
    const auto capture = [&](const char* name) { if (argc > 1) { QDir().mkpath(argv[1]); require(window->grabWindow().save(QDir(argv[1]).filePath(name)), "Command Center capture failed"); } };
    capture("command-center-800p.png");
    const auto hud = rect("native-hud-settings");
    std::cout << "800p first-page HUD bottom=" << hud.bottom() << '\n';
    require(hud.bottom() <= window->height() && hud.top() >= 0, "800p Command Center hid NovaHUD below first page");
    require(rect("native-preview-action").bottom() <= rect("native-players-reassign").top(), "header and Players actions overlap");
    window->resize(960,600); QMetaObject::invokeMethod(root.get(), "largeText"); QTest::qWait(150);
    for (const char* name : {"native-players-reassign", "native-appearance", "native-video-scale", "native-hud-settings", "native-doctor"}) {
        auto* target = item(name); target->forceActiveFocus(); QTest::qWait(80); const auto bounds = rect(name);
        require(window->activeFocusItem() == target && bounds.top() >= 0 && bounds.bottom() <= window->height(), "large-text focused action escaped scroll viewport");
        require(target->height() >= 48, "compact Command Center shrank a touch target below 48px");
    }
    capture("command-center-large-960.png");
    require(session.starts == 1 && session.stops == 0 && session.disconnects == 0 && session.controlsVisible(), "layout navigation changed stream lifecycle");
    std::cout << "800p first page and 960px large-text focus/touch/lifecycle checks passed\n";
}
