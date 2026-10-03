#include "runtime/deck_app_shortcuts.h"
#include <QGuiApplication>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QTemporaryDir>
#include <QTest>
#include <QDir>
#include <atomic>
#include <iostream>
using namespace nova::deck::runtime;
void require(bool ok, const char* copy) { if (!ok) { std::cerr << copy << '\n'; std::exit(1); } }
QQuickItem* find(QQuickItem* item, const QString& name) { if (item->objectName() == name && item->isVisible()) return item; for (auto* child : item->childItems()) if (auto* found = find(child, name)) return found; return nullptr; }
int main(int argc, char** argv) {
    QTemporaryDir directory; qputenv("XDG_CONFIG_HOME", directory.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("SteamAppQml");
    const auto flatpak = novaSteamShortcut(true, "/unused/nova-deck");
    const auto native = novaSteamShortcut(false, "/opt/nova/nova-deck");
    require(flatpak.exe == "\"/usr/bin/flatpak\"" && flatpak.launchOptions == "run com.papi_ux.Nova --standalone", "Flatpak shortcut lost standalone entrypoint");
    require(native.exe == "\"/opt/nova/nova-deck\"" && native.launchOptions == "--standalone", "native shortcut lost installed entrypoint");
    std::atomic<int> calls{0};
    DeckAppShortcuts backend([&] { const int call = ++calls; QThread::msleep(100); return DeckShortcutWriteResult{.ok = call > 1, .detail = "Close Steam and retry."}; });
    QQmlEngine engine; engine.rootContext()->setContextProperty("shortcutBackend", &backend);
    bool warnings = false; QObject::connect(&engine, &QQmlEngine::warnings, [&](const QList<QQmlError>& errors) { warnings = true; for (const auto& error : errors) std::cerr << error.toString().toStdString() << '\n'; });
    QQmlComponent component(&engine);
    component.setData(R"(import QtQuick
import QtQuick.Controls
ApplicationWindow {
    width: 960; height: 600; visible: true; color: NovaTheme.window
    property bool sheetOpen: sheet.opened
    AppSteamShortcut { id: sheet; controller: shortcutBackend }
    function openSheet() { sheet.open() }
    Component.onCompleted: { NovaTheme.setFontScale(1.3); sheet.open() }
})", QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/SteamTest.qml"));
    auto root = std::unique_ptr<QObject>(component.create()); require(bool(root), qPrintable(component.errorString()));
    auto* window = qobject_cast<QQuickWindow*>(root.get()); require(window, "missing Steam sheet window"); QTest::qWait(80);
    const auto item = [&](const char* name) { auto* found = find(window->contentItem(), name); require(found, name); return found; };
    const auto click = [&](const char* name) { item(name)->forceActiveFocus(); QTest::qWait(30); QTest::keyClick(window, Qt::Key_Return); };
    require(calls == 0 && window->activeFocusItem() == item("app-steam-add"), "opening Steam sheet performed a write or lost Add focus");
    click("app-steam-back"); QTest::qWait(50); require(!root->property("sheetOpen").toBool() && calls == 0, "Back performed registration");
    QMetaObject::invokeMethod(root.get(), "openSheet"); QTest::qWait(80);
    click("app-steam-add"); require(backend.state().value("busy").toBool() && !backend.add(), "duplicate request was accepted while busy");
    QTest::qWait(200); require(calls == 1 && backend.state().value("retry").toBool() && item("app-steam-status")->property("text").toString().contains("Close Steam"), "Steam refusal lost Retry or its next step");
    click("app-steam-add"); QTest::qWait(200);
    require(calls == 2 && backend.state().value("ok").toBool() && item("app-steam-status")->property("text").toString().contains("Non-Steam"), "explicit Retry failed to expose Steam success next step");
    backend.setBlocked(true); require(!backend.add() && !item("app-steam-add")->isEnabled() && calls == 2, "stream/update blocking did not guard app registration");
    for (const char* name : {"app-steam-add", "app-steam-back"}) {
        item(name)->forceActiveFocus(); QTest::qWait(50); const auto rect = item(name)->mapRectToScene(item(name)->boundingRect());
        require(rect.top() >= 0 && rect.bottom() <= window->height(), "large-text Steam action escaped viewport");
    }
    if (argc > 1) { QDir().mkpath(argv[1]); require(window->grabWindow().save(QString::fromLocal8Bit(argv[1]) + "/add-nova-steam-960-large.png"), "Steam capture failed"); }
    require(!warnings, "Steam sheet emitted QML warnings");
    std::cout << "Steam app entrypoint, no automatic writes, explicit Add/Retry, busy/blocking, refusal copy and large-text focus passed\n";
}
