#include "runtime/deck_steam_control.h"
#include <QGuiApplication>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QImage>
#include <QTemporaryDir>
#include <QElapsedTimer>
#include <atomic>
#include <iostream>
using namespace nova::deck::runtime;
using namespace nova::deck::polaris;
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
void until(const std::function<bool()>& done) {
    QElapsedTimer time; time.start();
    while (!done() && time.elapsed() < 3000) { QCoreApplication::processEvents(); QThread::msleep(2); }
    require(done(), "Steam popup did not settle");
}
int main(int argc, char** argv) {
    QTemporaryDir settings; qputenv("XDG_CONFIG_HOME", settings.path().toUtf8());
    QGuiApplication app(argc, argv);
    std::atomic<int> posts {0}; std::atomic<bool> running {true};
    DeckSteamControl control;
    control.setTarget("fixture", "Living Room PC", [&]() -> std::optional<DeckSteamTarget> {
        DeckSteamTarget target; target.identityValid = [] { return true; };
        target.read = [&] { return DeckPolarisResult<DeckDesktopSteam> {DeckPolarisRequestStatus::Ok, 200, {},
            DeckDesktopSteam {running ? "running" : "stopped", "root", {}, true, true, false}}; };
        target.act = [&](const std::string& action, const std::string&, bool confirmed, const std::function<bool()>&) {
            require(action == "quit" && confirmed, "Popup sent an unconfirmed quit"); ++posts; running = false;
            return DeckPolarisResult<DeckDesktopSteam> {DeckPolarisRequestStatus::Ok, 200, {},
                DeckDesktopSteam {"stopped", "root", {}, true, true, true}};
        };
        return target;
    });
    QQmlEngine engine; engine.rootContext()->setContextProperty("steamBackend", &control);
    QQmlComponent component(&engine);
    component.setData(R"(import QtQuick
import QtQuick.Controls
ApplicationWindow {
    width: 960; height: 800; visible: true
    SteamControls { id: sheet; controller: steamBackend }
    Component.onCompleted: { NovaTheme.setFontScale(1.3); sheet.open() }
})", QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/SteamTest.qml"));
    require(component.isReady(), qPrintable(component.errorString()));
    std::unique_ptr<QObject> root(component.create()); require(bool(root), "Steam popup did not create");
    auto* window = qobject_cast<QQuickWindow*>(root.get()); require(window, "Steam window missing");
    until([&] { return !control.busy() && control.state().value("available").toBool(); });
    auto find = [&](const char* name) { auto* item = root->findChild<QQuickItem*>(name); require(item, name); return item; };
    require(window->activeFocusItem() == find("steam-close"), "Steam popup did not focus its safe action");
    if (const auto screenshot = qgetenv("NOVA_STEAM_SCREENSHOT"); !screenshot.isEmpty()) {
        QElapsedTimer frame; frame.start();
        while (frame.elapsed() < 200) { QCoreApplication::processEvents(); QThread::msleep(2); }
        require(window->grabWindow().save(QString::fromUtf8(screenshot)), "Steam screenshot failed");
    }
    require(QMetaObject::invokeMethod(find("steam-quit"), "clicked"), "Quit button failed");
    require(posts == 0 && find("steam-keep")->isVisible(), "Game quit did not pause for confirmation");
    require(window->activeFocusItem() == find("steam-keep"), "Game quit did not focus Keep Steam running");
    require(QMetaObject::invokeMethod(find("steam-confirm-quit"), "clicked"), "Confirm quit failed");
    until([&] { return !control.busy(); });
    require(posts == 1 && control.state().value("state") == "stopped", "Confirmed quit was not observed");
    control.setTarget("other", "Other PC", {});
    require(!find("steam-open")->isEnabled(), "Host change retained old Steam authority");
    return 0;
}
