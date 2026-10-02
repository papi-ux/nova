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
#include <QImage>
#include <QSettings>
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
    function menuOpacity(value) {
        if (typeof NovaStreamPreferences.setMenuOpacity !== "function") return false
        NovaStreamPreferences.setMenuOpacity(value)
        return true
    }
    function hudOpacity() { return NovaHudPreferences.panelOpacity }
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
    // Observe the real streaming panel and actions, not a token arithmetic mirror.
    auto* center = item("native-command-center");
    require(center->width() <= 560, "parity Command Center still occupies a desktop-wide panel");
    window->resize(360, 640); QTest::qWait(150);
    const auto close = rect("native-preview-action"), disconnect = rect("native-disconnect-action"), end = rect("native-end-action");
    require(close.bottom() <= disconnect.top() && disconnect.bottom() <= end.top(),
        "narrow Command Center did not stack its three distinct session actions");
    for (const auto* name : {"native-preview-action", "native-disconnect-action", "native-end-action"}) {
        const auto bounds = rect(name);
        require(bounds.left() >= 0 && bounds.right() <= window->width() && item(name)->height() >= 48,
            "narrow Command Center clipped or shrank an interaction owner");
    }
    item("native-hud-settings")->forceActiveFocus(); QTest::qWait(80);
    require(rect("native-hud-settings").bottom() <= window->height(), "narrow panel did not reveal its focused row");
    capture("command-center-portrait-360.png");
    window->resize(960, 600); QTest::qWait(100);
    const QPointF sample = center->mapToScene(QPointF(8, center->height() - 8));
    QColor body[4]; int opacityValues[] = {0, 25, 64, 100};
    for (int i = 0; i < 4; ++i) {
        QVariant changed;
        QMetaObject::invokeMethod(root.get(), "menuOpacity", Q_RETURN_ARG(QVariant, changed), Q_ARG(QVariant, opacityValues[i]));
        require(changed.toBool(), "independent menu opacity setter is missing");
        QTest::qWait(60);
        const auto rendered = window->grabWindow(); require(!rendered.isNull(), "menu opacity capture failed");
        const QPoint pixel(sample.x() * rendered.width() / window->width(), sample.y() * rendered.height() / window->height());
        require(rendered.rect().contains(pixel), "menu panel-body sample escaped actual capture");
        body[i] = rendered.pixelColor(pixel);
        require(item("native-preview-action")->isVisible() && item("native-hud-settings")->isVisible(),
            "menu opacity hid labels or interaction owners");
    }
    require(body[0] != body[1] && body[1] != body[2] && body[2] != body[3],
        "menu opacity did not produce four distinct actual panel-body pixels");
    QVariant ignored, hudOpacity;
    QMetaObject::invokeMethod(root.get(), "menuOpacity", Q_RETURN_ARG(QVariant, ignored), Q_ARG(QVariant, 64));
    QMetaObject::invokeMethod(root.get(), "hudOpacity", Q_RETURN_ARG(QVariant, hudOpacity));
    QSettings saved;
    require(saved.value("InGameControls/menuOpacityPercent").toInt() == 64 && hudOpacity.toInt() == 64,
        "menu opacity did not persist independently of HUD opacity");
    require(session.starts == 1 && session.stops == 0 && session.disconnects == 0 && session.controlsVisible(),
        "presentation choices changed stream lifecycle");
    std::cout << "800p first page and 960px large-text focus/touch/lifecycle checks passed\n";
}
