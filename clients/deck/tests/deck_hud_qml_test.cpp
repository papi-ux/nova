#include <QDir>
#include <QGuiApplication>
#include <QElapsedTimer>
#include <QKeyEvent>
#include <QJSValue>
#include <QMouseEvent>
#include <QQmlComponent>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QSettings>
#include <QTemporaryDir>
#include <QThread>
#include <cstdlib>
#include <iostream>
#include <functional>
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
void settle(int ms = 60) { for (int i = 0; i < ms; i += 5) { QCoreApplication::processEvents(); QThread::msleep(5); } }
void key(QQuickWindow* window, int code) {
    QKeyEvent down(QEvent::KeyPress, code, Qt::NoModifier), up(QEvent::KeyRelease, code, Qt::NoModifier);
    QCoreApplication::sendEvent(window, &down); QCoreApplication::sendEvent(window, &up); settle();
}
int main(int argc, char** argv) {
    QTemporaryDir config;
    qputenv("XDG_CONFIG_HOME", config.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("Hud");
    auto engine = std::make_unique<QQmlEngine>();
    auto component = std::make_unique<QQmlComponent>(engine.get());
    const QByteArray source = R"(import QtQuick
import QtQuick.Controls
ApplicationWindow {
    id: root
    width: 1280; height: 800; visible: true
    property int gameTaps: 0
    property int holds: 0
    property var sample: ({fps:"59.8", incoming:"60.0", decoded:"60.0", target:"/ 60 target", host:"2.1ms", rtt:"18ms", jitter:"2ms", bitrate:"19.6M", resolution:"1280×800", codec:"H.264", history:[59,60,59,55,60,60,59.8], truth:"Composed FPS · video payload bitrate", healthLabel:"Display override", healthTone:"warning", hostTone:"warning", netTone:"stable", clientTone:"stable", tuningLabel:"Tuning: Applying", tuningTone:"info", appliedBitrate:"20.0M", qualityLimit:"25.0M"})
    Rectangle { anchors.fill: parent; gradient: Gradient { GradientStop { position:0; color:"#213D50" } GradientStop { position:1; color:"#081720" } } }
    Label { anchors.centerIn: parent; text:"NovaHUD · synthetic stream preview"; color:"#8096A0"; font.pixelSize:22 }
    MouseArea { anchors.fill: parent; onClicked: root.gameTaps++ }
    NovaHud { id: overlay; readings: root.sample; visible: NovaHudPreferences.enabled; onCommandCenterRequested: root.holds++ }
    HudSettings { id: settings }
    function openSettings() { settings.open() }
    function largeText() { NovaTheme.setFontScale(1.3) }
    function setCorner(x, y) { NovaHudPreferences.setPosition(x, y) }
    function state() { return { mode:NovaHudPreferences.mode, enabled:NovaHudPreferences.enabled, opacity:NovaHudPreferences.panelOpacity, x:NovaHudPreferences.positionX, y:NovaHudPreferences.positionY } }
    Component.onCompleted: settings.open()
})";
    component->setData(source, QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/HudTest.qml"));
    require(component->isReady(), qPrintable(component->errorString()));
    std::unique_ptr<QObject> root(component->create());
    require(root != nullptr, qPrintable(component->errorString()));
    auto* window = qobject_cast<QQuickWindow*>(root.get());
    settle(200);
    auto* hud = root->findChild<QQuickItem*>("nova-stream-hud");
    auto* settings = root->findChild<QObject*>("hud-settings-popup");
    std::function<QQuickItem*(QQuickItem*, const QString&)> findVisual = [&](QQuickItem* parent, const QString& name) -> QQuickItem* {
        if (parent->objectName() == name) return parent;
        for (auto* child : parent->childItems()) if (auto* found = findVisual(child, name)) return found;
        return nullptr;
    };
    const auto item = [&](const char* name) { auto* v = findVisual(window->contentItem(), QString::fromUtf8(name)); require(v, name); return v; };
    const auto click = [&](const char* name) { auto* v = item(name); v->forceActiveFocus(); settle(); key(window, Qt::Key_Return); };
    const auto capture = [&](const QString& name) {
        if (argc < 2) return;
        require(QDir().mkpath(QString::fromLocal8Bit(argv[1])), "capture directory failed");
        require(window->grabWindow().save(QDir(QString::fromLocal8Bit(argv[1])).filePath(name)), "HUD screenshot failed");
    };
    const auto bounds = [&] {
        require(hud->x() >= 0 && hud->y() >= 0 && hud->x() + hud->width() <= window->width() && hud->y() + hud->height() <= window->height(), "HUD escaped viewport after mode/size change");
    };
    require(hud && settings && !hud->isVisible(), "HUD did not default off");
    require(window->activeFocusItem() == item("hud-toggle"), "settings lost initial focus");
    key(window, Qt::Key_Return); key(window, Qt::Key_Down);
    require(window->activeFocusItem() == item("hud-mode-slim"), "D-pad cannot reach mode picker");
    for (const auto* mode : {"slim", "minimal", "performance", "debug"}) {
        const QByteArray name = QByteArray("hud-mode-") + mode;
        click(name.constData());
        key(window, Qt::Key_Escape);
        require(!settings->property("opened").toBool() && hud->isVisible(), "picker did not close to visible HUD");
        bounds(); capture(QString("hud-%1-1280.png").arg(mode));
        QMetaObject::invokeMethod(root.get(), "openSettings"); settle();
    }
    QMetaObject::invokeMethod(root.get(), "largeText"); window->resize(960, 600); settle();
    click("hud-position"); click("hud-position");
    click("hud-opacity"); click("hud-opacity"); click("hud-opacity"); // 64 -> 90 -> 100 -> 0
    capture("hud-settings-large-960.png");
    key(window, Qt::Key_Escape); settle(250); bounds(); capture("hud-debug-unboxed-960.png");
    for (const auto* mode : {"slim", "minimal", "performance", "debug"}) {
        QMetaObject::invokeMethod(root.get(), "openSettings"); settle();
        const QByteArray name = QByteArray("hud-mode-") + mode;
        click(name.constData()); key(window, Qt::Key_Escape); settle(200);
        bounds(); capture(QString("hud-%1-large-960.png").arg(mode));
    }
    require(item("hud-fps")->property("text") == "59.8", "HUD lost measured FPS");
    auto point = hud->mapToScene(QPointF(hud->width() / 2, hud->height() / 2));
    QElapsedTimer pointerClock; pointerClock.start();
    const auto mouse = [&](QEvent::Type type, QPointF p, Qt::MouseButtons buttons) {
        QMouseEvent event(type, p, window->mapToGlobal(p), type == QEvent::MouseMove ? Qt::NoButton : Qt::LeftButton, buttons, Qt::NoModifier);
        event.setTimestamp(pointerClock.elapsed() + 1);
        QCoreApplication::sendEvent(window, &event); settle();
    };
    mouse(QEvent::MouseButtonPress, QPointF(25, 300), Qt::LeftButton); mouse(QEvent::MouseButtonRelease, QPointF(25, 300), Qt::NoButton);
    root->setProperty("gameTaps", 0);
    mouse(QEvent::MouseButtonPress, point, Qt::LeftButton); mouse(QEvent::MouseButtonRelease, point, Qt::NoButton);
    require(root->property("gameTaps").toInt() == 1 && root->property("holds").toInt() == 0, "plain HUD tap swallowed gameplay or opened controls");
    mouse(QEvent::MouseButtonPress, point, Qt::LeftButton); settle(1000); mouse(QEvent::MouseButtonRelease, point, Qt::NoButton);
    require(root->property("holds").toInt() == 1 && root->property("gameTaps").toInt() == 1, "HUD hold did not request Command Center or leaked a gameplay tap");
    const auto original = hud->position();
    mouse(QEvent::MouseButtonPress, point, Qt::LeftButton);
    mouse(QEvent::MouseMove, point - QPointF(40, 40), Qt::LeftButton);
    mouse(QEvent::MouseMove, point - QPointF(160, 120), Qt::LeftButton);
    mouse(QEvent::MouseButtonRelease, point - QPointF(160, 120), Qt::NoButton);
    require(hud->position() != original && root->property("gameTaps").toInt() == 1, "HUD drag did not move or leaked a gameplay tap"); bounds();
    require(item("hud-tuning")->property("text") == "Tuning: Applying" &&
        item("hud-applied-limit")->property("text") == "20.0M applied / 25.0M limit" &&
        item("hud-health-label")->property("text") == "Display override", "HUD lost host state or applied bitrate");
    auto stale = root->property("sample").value<QJSValue>().toVariant().toMap();
    stale.remove("healthLabel"); stale.remove("healthTone"); stale.remove("tuningLabel");
    stale.remove("tuningTone"); stale.remove("appliedBitrate"); stale.remove("qualityLimit");
    root->setProperty("sample", stale); settle();
    require(item("hud-fps")->property("text") == "59.8" && item("hud-tuning")->property("text") == "Tuning: Unknown" &&
        item("hud-applied-limit")->property("text") == "-- applied / -- limit" &&
        item("hud-health-label")->property("text") == "Host readings unavailable", "stale host readings persisted or cleared local FPS");
    capture("hud-host-unavailable-960.png");
    root->setProperty("sample", QVariantMap{}); settle();
    require(item("hud-fps")->property("text") == "--", "unavailable telemetry kept stale FPS");
    capture("hud-unavailable-960.png");
    // Destroying/recreating the engine reads persisted preferences, not the old singleton.
    root.reset(); component.reset(); engine.reset();
    QSettings saved;
    require(saved.value("NovaHUD/enabled").toBool() && saved.value("NovaHUD/mode") == "debug" && saved.value("NovaHUD/panelOpacity").toInt() == 0,
        "HUD choices were not persisted");
    engine = std::make_unique<QQmlEngine>(); component = std::make_unique<QQmlComponent>(engine.get());
    component->setData(source, QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/HudTest.qml"));
    root.reset(component->create()); settle();
    QVariant result; QMetaObject::invokeMethod(root.get(), "state", Q_RETURN_ARG(QVariant, result));
    const auto restored = result.toMap();
    require(restored.value("enabled").toBool() && restored.value("mode") == "debug" && restored.value("opacity").toInt() == 0, "restart lost HUD settings");
    require(restored.value("x").toDouble() > 0 && restored.value("x").toDouble() < 1 && restored.value("y").toDouble() > 0 && restored.value("y").toDouble() < 1,
        "restart lost the dragged position");
    std::cout << "HUD QML passed: layouts, picker, opacity, resize, large text, taps, hold, drag, unavailable readings and persistence\n";
}
