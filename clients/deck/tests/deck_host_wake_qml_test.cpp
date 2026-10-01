#include "runtime/deck_host_wake.h"
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
using namespace nova::deck::runtime;
void require(bool value, const char* message) { if (!value) { std::cerr << message << '\n'; std::exit(1); } }
void settle() { QTest::qWait(80); }
QQuickItem* find(QQuickItem* item, const QString& name) { if (item->objectName() == name && item->isVisible()) return item; for (auto* child : item->childItems()) if (auto* found = find(child, name)) return found; return nullptr; }
int main(int argc, char** argv) {
    QTemporaryDir directory; qputenv("XDG_CONFIG_HOME", directory.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("WakeQml");
    int sends = 0;
    DeckHostWakeController controller(directory.filePath("wake.ini"), [&](const QByteArray&) { ++sends; return QString{}; });
    controller.setTarget("host", "Living Room PC");
    QQmlEngine engine; engine.rootContext()->setContextProperty("wakeBackend", &controller);
    bool warnings = false; QObject::connect(&engine, &QQmlEngine::warnings, [&](const QList<QQmlError>& errors) { warnings = true; for (const auto& error : errors) std::cerr << error.toString().toStdString() << '\n'; });
    QQmlComponent component(&engine);
    component.setData(R"(import QtQuick
import QtQuick.Controls
ApplicationWindow {
    width: 960; height: 600; visible: true; color: NovaTheme.window
    property bool sheetOpen: sheet.opened
    HostWake { id: sheet; controller: wakeBackend }
    function openSheet() { sheet.open() }
    function back() { sheet.back() }
    Component.onCompleted: { NovaTheme.setFontScale(1.3); sheet.open() }
})", QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/WakeTest.qml"));
    auto root = std::unique_ptr<QObject>(component.create());
    require(bool(root), qPrintable(component.errorString()));
    auto* window = qobject_cast<QQuickWindow*>(root.get()); require(window, "Wake QML window missing"); settle();
    const auto item = [&](const char* name) { auto* found = find(window->contentItem(), name); require(found, name); return found; };
    const auto click = [&](const char* name) { item(name)->forceActiveFocus(); settle(); QTest::keyClick(window, Qt::Key_Return); settle(); };
    require(window->activeFocusItem() == item("host-wake-mac") && !item("host-wake-send")->isEnabled(), "missing MAC enabled Wake or lost editor focus");
    click("host-wake-mac"); require(item("endpoint-key-F") && !find(window->contentItem(), "endpoint-keyboard-mode"), "MAC keyboard did not expose hexadecimal keys");
    item("endpoint-keyboard-entry")->setProperty("text", "02:11:22:33:44:55");
    QMetaObject::invokeMethod(root.get(), "back"); settle();
    require(root->property("sheetOpen").toBool() && controller.state().value("mac").toString().isEmpty() && item("host-wake-mac")->property("text").toString().isEmpty(), "B from keyboard closed parent or saved cancelled draft");
    click("host-wake-mac"); item("endpoint-keyboard-entry")->setProperty("text", "02:11:22:33:44:55"); click("endpoint-keyboard-done");
    require(sends == 0 && !item("host-wake-send")->isEnabled(), "keyboard Done sent Wake or authorized an unsaved draft");
    click("host-wake-save"); require(sends == 0 && window->activeFocusItem() == item("host-wake-send") && item("host-wake-send")->isEnabled(), "Save sent Wake or lost send focus");
    click("host-wake-send"); require(sends == 1 && item("host-wake-status")->property("text").toString().contains("does not confirm"), "Wake QML claimed the PC is awake or failed to send");
    click("host-wake-back"); require(!root->property("sheetOpen").toBool(), "Back kept Wake open");
    QMetaObject::invokeMethod(root.get(), "openSheet"); settle();
    item("host-wake-mac")->setProperty("text", "02:AA:BB:CC:DD:EE");
    require(!item("host-wake-send")->isEnabled(), "changed draft retained send authority");
    QMetaObject::invokeMethod(root.get(), "back"); settle();
    require(controller.state().value("mac") == "02:11:22:33:44:55" && sends == 1, "Back saved changed MAC");
    QMetaObject::invokeMethod(root.get(), "openSheet"); settle(); controller.setTarget("other", "Another PC"); settle();
    require(!root->property("sheetOpen").toBool() && !controller.state().value("canWake").toBool(), "host switch kept stale Wake editor or MAC");
    QMetaObject::invokeMethod(root.get(), "openSheet"); settle();
    item("host-wake-mac")->setProperty("text", "FF:FF:FF:FF:FF:FF"); click("host-wake-save");
    require(sends == 1 && !controller.state().value("canWake").toBool() && item("host-wake-status")->property("text").toString().contains("unicast"), "invalid MAC failure was hidden by draft copy");
    for (const char* name : {"host-wake-save", "host-wake-back"}) {
        item(name)->forceActiveFocus(); settle(); const auto rect = item(name)->mapRectToScene(item(name)->boundingRect());
        require(rect.top() >= 0 && rect.bottom() <= window->height(), "large-text Wake actions escaped the viewport");
    }
    if (argc > 1) { QDir().mkpath(argv[1]); require(window->grabWindow().save(QString::fromLocal8Bit(argv[1]) + "/wake-mac-960-large.png"), "Wake capture failed"); }
    require(!warnings, "Wake QML emitted warnings");
    std::cout << "Wake MAC hex keyboard, nested Back, explicit Save/Wake, host change, failure copy and large-text focus passed\n";
}
