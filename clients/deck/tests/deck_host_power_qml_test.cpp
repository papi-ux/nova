#include "runtime/deck_host_power.h"
#include <QGuiApplication>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickWindow>
#include <QQuickItem>
#include <QKeyEvent>
#include <QTemporaryDir>
#include <QThread>
#include <atomic>
#include <iostream>
using namespace nova::deck::runtime;
using namespace nova::deck::polaris;
class Pad final : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool primaryHeld READ held NOTIFY primaryHeldChanged)
public:
    bool held() const { return held_; }
    void set(bool held) { held_ = held; emit primaryHeldChanged(); }
signals:
    void primaryHeldChanged();
private:
    bool held_ = false;
};
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
void settle(int ms = 40) { QElapsedTimer t; t.start(); while (t.elapsed() < ms) { QCoreApplication::processEvents(); QThread::msleep(2); } }
void until(const std::function<bool()>& predicate) { QElapsedTimer t; t.start(); while (!predicate() && t.elapsed() < 2500) settle(5); require(predicate(), "power QML did not settle"); }
void key(QQuickWindow* window, int code) {
    QKeyEvent press(QEvent::KeyPress, code, Qt::NoModifier), release(QEvent::KeyRelease, code, Qt::NoModifier);
    QCoreApplication::sendEvent(window, &press); QCoreApplication::sendEvent(window, &release); settle();
}
int main(int argc, char** argv) {
    QTemporaryDir settings;
    qputenv("XDG_CONFIG_HOME", settings.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests"); QCoreApplication::setApplicationName("HostPower");
    std::atomic_int sleeps{0};
    DeckHostPowerController power(nullptr, {200, 350, 1, 1});
    power.setTarget("fixture", "Living Room PC", [&]() -> std::optional<DeckHostPowerTarget> {
        DeckHostPowerTarget target;
        target.identityValid = [] { return true; };
        target.capabilities = [] {
            DeckPolarisCapabilities caps; caps.hostSleep = true; caps.hostPower = {true, true, true};
            return DeckPolarisResult<DeckPolarisCapabilities>{DeckPolarisRequestStatus::Ok, 200, {}, caps};
        };
        target.sleep = [&](const std::function<bool()>&) { ++sleeps; return DeckPolarisResult<DeckHostSleepReceipt>{DeckPolarisRequestStatus::Ok, 200, {}, DeckHostSleepReceipt{true}}; };
        target.reachable = [] { return false; };
        return target;
    });
    power.setWindowActive(true);
    Pad pad;
    QQmlEngine engine;
    engine.rootContext()->setContextProperty("powerBackend", &power);
    engine.rootContext()->setContextProperty("testPad", &pad);
    QQmlComponent component(&engine);
    component.setData(R"(import QtQuick
import QtQuick.Controls
ApplicationWindow {
    width: 960; height: 600; visible: true
    HostPower { id: sheet; controller: powerBackend; gamepad: testPad }
    function openPower() { sheet.open() }
    function closePower() { sheet.close() }
    Component.onCompleted: { NovaTheme.setFontScale(1.3); sheet.open() }
})", QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/HostPowerTest.qml"));
    require(component.isReady(), qPrintable(component.errorString()));
    std::unique_ptr<QObject> root(component.create());
    auto* window = qobject_cast<QQuickWindow*>(root.get());
    require(window, "power window missing");
    const auto phase = [&] { return power.state().value("phase").toString(); };
    const auto ready = [&] { until([&] { return phase() == "ready" && window->activeFocusItem() && window->activeFocusItem()->objectName() == "host-power-hold"; }); };
    ready();
    // The real bridge sends an immediate Return pair while physical A stays
    // down. Releasing A, navigating away, or closing must end the hold.
    pad.set(true); key(window, Qt::Key_Return);
    require(phase() == "holding", "synthetic key release cancelled a physically held A");
    pad.set(false); settle(650);
    require(phase() == "ready" && sleeps == 0, "short physical A press sent sleep");
    pad.set(true); key(window, Qt::Key_Return); key(window, Qt::Key_Down);
    require(phase() == "ready" && window->activeFocusItem()->objectName() == "host-power-cancel", "leaving the hold stole focus back or kept holding");
    pad.set(false); key(window, Qt::Key_Up);
    pad.set(true); key(window, Qt::Key_Return);
    until([&] { return phase() == "countdown"; });
    pad.set(false);
    require(window->activeFocusItem()->objectName() == "host-power-cancel", "countdown did not focus Cancel");
    auto* focused = window->activeFocusItem();
    const auto rect = focused->mapRectToScene(focused->boundingRect());
    require(rect.top() >= 0 && rect.bottom() <= window->height(), "large-text Cancel escaped the viewport");
    key(window, Qt::Key_Return); settle(650);
    require(sleeps == 0, "controller Cancel failed");
    QMetaObject::invokeMethod(root.get(), "openPower"); ready();
    pad.set(true); key(window, Qt::Key_Return);
    until([&] { return phase() == "countdown"; });
    QMetaObject::invokeMethod(root.get(), "closePower"); pad.set(false); settle(650);
    require(sleeps == 0, "closing the popup left a scheduled request");
    QMetaObject::invokeMethod(root.get(), "openPower"); ready();
    pad.set(true); key(window, Qt::Key_Return);
    until([&] { return phase() == "countdown"; });
    pad.set(false);
    until([&] { return phase() == "offline"; });
    require(sleeps == 1, "completed controller hold did not send exactly once");
    std::cout << "Host power QML passed: physical-A lifecycle, focus escape, cancel, close, large text and one completed hold\n";
}
#include "deck_host_power_qml_test.moc"
