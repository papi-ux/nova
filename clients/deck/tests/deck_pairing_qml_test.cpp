#include <QDir>
#include <QGuiApplication>
#include <QKeyEvent>
#include <QMouseEvent>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QThread>
#include <QTemporaryDir>
#include <QVariantMap>

#include <cstdlib>
#include <iostream>

class PairFixture : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
    Q_PROPERTY(QVariantList savedHosts READ savedHosts NOTIFY savedHostsChanged)
public:
    QVariantMap state() const { return model; }
    QVariantList savedHosts() const { return hosts; }
    Q_INVOKABLE void reset() { set("idle", "Enter a PC address.", false); }
    Q_INVOKABLE bool removeHost(const QString& id, bool local) {
        ++removals; removedId = id; localOnly = local;
        set("removing", "Unpairing Nova from the PC…", true);
        return true;
    }
    void clearHosts() { hosts.clear(); emit savedHostsChanged(); }
    Q_INVOKABLE bool start(const QString& address, int port) {
        ++starts; selectedAddress = address; selectedPort = port;
        set("waiting", "Enter this PIN in your host's pairing screen.", true, "1234");
        return true;
    }
    Q_INVOKABLE bool startTrusted(const QString& address, int port) {
        ++trustedStarts; selectedAddress = address; selectedPort = port;
        set("trusted", "Connecting with Trusted Pair…", true);
        return true;
    }
    Q_INVOKABLE void cancel() { ++cancels; set("cancelling", "Cancelling pairing…", true); }
    void set(QString phase, QString copy, bool busy, QString pin = {}) {
        model = {{"phase", phase}, {"copy", copy}, {"busy", busy}, {"pin", pin}};
        emit stateChanged();
    }
    int starts = 0, trustedStarts = 0, cancels = 0, selectedPort = 0;
    int removals = 0;
    bool localOnly = false;
    QString removedId;
    QVariantList hosts{QVariantMap{{"id", "saved-pc"}, {"name", "Living room PC"}}};
    QString selectedAddress;
signals:
    void stateChanged();
    void savedHostsChanged();
private:
    QVariantMap model{{"phase", "idle"}, {"copy", "Enter the PC's address. Nova will give you a PIN to enter on the host."}, {"busy", false}, {"pin", ""}};
};
class GamepadFixture : public QObject {
    Q_OBJECT
public:
    Q_INVOKABLE void activateFocusedItem() {}
signals:
    void primaryActionPressed(int count);
    void secondaryActionPressed(int count);
};

namespace {
void require(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}
void settle() {
    for (int n = 0; n < 8; ++n) { QCoreApplication::processEvents(); QThread::msleep(10); }
}
void key(QQuickWindow& window, int code) {
    QKeyEvent press(QEvent::KeyPress, code, Qt::NoModifier), release(QEvent::KeyRelease, code, Qt::NoModifier);
    QCoreApplication::sendEvent(&window, &press);
    QCoreApplication::sendEvent(&window, &release);
    settle();
}
QQuickItem* item(QQuickItem* parent, const QString& name) {
    if (parent->objectName() == name) return parent;
    for (auto* child : parent->childItems()) if (auto* found = item(child, name)) return found;
    return nullptr;
}
void tap(QQuickWindow& window, QQuickItem* target) {
    require(target && target->isVisible() && target->isEnabled(), "tap target is unavailable");
    const auto point = target->mapToScene(QPointF(target->width()/2, target->height()/2));
    QMouseEvent press(QEvent::MouseButtonPress, point, point, Qt::LeftButton, Qt::LeftButton, Qt::NoModifier);
    QMouseEvent release(QEvent::MouseButtonRelease, point, point, Qt::LeftButton, Qt::NoButton, Qt::NoModifier);
    QCoreApplication::sendEvent(&window, &press);
    QCoreApplication::sendEvent(&window, &release);
    settle();
}
}

int main(int argc, char** argv) {
    QTemporaryDir config;
    require(config.isValid(), "cannot create isolated UI settings");
    qputenv("XDG_CONFIG_HOME", config.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests");
    QCoreApplication::setApplicationName("Pairing");
    app.setQuitOnLastWindowClosed(false);
    PairFixture fixture;
    GamepadFixture gamepad;
    QQmlEngine engine;
    engine.rootContext()->setContextProperty("novaPairing", &fixture);
    engine.rootContext()->setContextProperty("novaGamepad", &gamepad);
    QQmlComponent component(&engine, QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/PairHost.qml"));
    require(component.isReady(), qPrintable(component.errorString()));
    std::unique_ptr<QObject> root(component.create());
    auto* window = qobject_cast<QQuickWindow*>(root.get());
    require(window != nullptr, "pairing window did not load");
    settle();
    auto* address = root->findChild<QQuickItem*>("pair-address");
    auto* port = root->findChild<QQuickItem*>("pair-port");
    auto* primary = root->findChild<QQuickItem*>("pair-primary");
    auto* trusted = root->findChild<QQuickItem*>("pair-trusted");
    auto* cancel = root->findChild<QQuickItem*>("pair-cancel");
    auto* pin = root->findChild<QQuickItem*>("pair-pin");
    require(address && port && primary && trusted && cancel && pin, "pairing controls missing");
    auto capture = [&](const char* name) {
        if (argc < 2) return;
        const QDir directory(QString::fromLocal8Bit(argv[1]));
        require(QDir().mkpath(directory.path()) && window->grabWindow().save(directory.filePath(name)), "pairing screenshot failed");
    };
    require(window->activeFocusItem() == address && fixture.starts == 0, "opening setup must only focus the address");
    capture("pairing-address.png");
    // Use actual pointer events: tapping a field opens the keypad; keys edit at
    // the caret, Done commits, and Cancel preserves the original field.
    auto find = [&](const char* name) { return item(window->contentItem(), QString::fromUtf8(name)); };
    auto click = [&](const char* name) { tap(*window, find(name)); };
    tap(*window, address);
    auto* entry = find("endpoint-keyboard-entry");
    require(entry && entry->hasActiveFocus(), "tapping address did not open a focused keypad");
    capture("pairing-number-pad.png");
    for (const auto c : QString("192.0.2.4")) {
        const auto name = "endpoint-key-" + QString(c);
        tap(*window, item(window->contentItem(), name));
    }
    require(entry->property("text").toString() == "192.0.2.4", "numeric keypad did not enter an IP address");
    entry->setProperty("cursorPosition", 1);
    click("endpoint-keyboard-backspace");
    click("endpoint-key-1");
    require(entry->property("text").toString() == "192.0.2.4", "keypad did not edit at the caret");
    QMetaObject::invokeMethod(entry, "select", Q_ARG(int, 0), Q_ARG(int, 3));
    click("endpoint-key-8");
    require(entry->property("text").toString() == "8.0.2.4", "keypad did not replace selected text");
    click("endpoint-keyboard-done");
    require(address->property("text").toString() == "8.0.2.4" && address->hasActiveFocus(), "Done did not commit address and restore focus");
    tap(*window, address);
    click("endpoint-keyboard-clear");
    click("endpoint-keyboard-mode");
    capture("pairing-hostname-keyboard.png");
    for (const auto c : QString("game-pc")) tap(*window, item(window->contentItem(), "endpoint-key-" + QString(c)));
    require(entry->property("text").toString() == "game-pc", "ABC keyboard did not enter a hostname");
    click("endpoint-keyboard-cancel");
    require(address->property("text").toString() == "8.0.2.4", "Cancel committed an unfinished address");
    tap(*window, address);
    click("endpoint-keyboard-clear");
    click("endpoint-keyboard-mode");
    for (const auto c : QString("game-pc")) tap(*window, item(window->contentItem(), "endpoint-key-" + QString(c)));
    click("endpoint-keyboard-done");
    require(address->property("text").toString() == "game-pc", "hostname was not committed");
    tap(*window, port);
    require(!find("endpoint-key-.")->isEnabled() && !find("endpoint-key-:")->isEnabled() &&
        !find("endpoint-keyboard-mode")->isVisible(), "port keypad accepted address characters");
    click("endpoint-keyboard-clear");
    for (const auto c : QString("47989")) tap(*window, item(window->contentItem(), "endpoint-key-" + QString(c)));
    click("endpoint-keyboard-done");
    require(port->property("text").toString() == "47989", "port keypad did not commit digits");
    // The same keypad can be navigated with the controller and dismissed with B.
    tap(*window, address);
    click("endpoint-keyboard-clear");
    key(*window, Qt::Key_Down);
    require(window->activeFocusItem() == find("endpoint-key-1"), "keypad D-pad focus did not enter keys");
    key(*window, Qt::Key_Right);
    key(*window, Qt::Key_Return);
    require(entry->property("text").toString() == "2", "keypad D-pad activation failed");
    emit gamepad.secondaryActionPressed(1);
    settle();
    require(window->isVisible() && address->hasActiveFocus() && address->property("text").toString() == "game-pc",
        "B should cancel the keypad without closing setup or committing edits");
    address->setProperty("text", "fixture-pc");
    key(*window, Qt::Key_Down);
    require(window->activeFocusItem() == port, "address-to-port focus failed");
    key(*window, Qt::Key_Down);
    require(window->activeFocusItem() == trusted, "port-to-trusted-pair focus failed");
    key(*window, Qt::Key_Right);
    require(window->activeFocusItem() == primary, "port-to-pair focus failed");
    key(*window, Qt::Key_Return);
    require(fixture.starts == 1 && fixture.selectedAddress == "fixture-pc" && fixture.selectedPort == 47989,
            "pair action did not use the entered endpoint exactly once");
    require(!address->isEnabled() && !primary->isEnabled() && window->activeFocusItem() == cancel && pin->isVisible(),
            "pending pairing must show its PIN and focus cancellation");
    capture("pairing-pin-fixture.png");
    key(*window, Qt::Key_Return);
    require(fixture.cancels == 1 && !pin->isVisible(), "cancel must clear the displayed PIN");
    fixture.set("cancelled", "Pairing cancelled.", false);
    settle();
    require(window->activeFocusItem() == primary && primary->property("text") == "Pair with PIN", "cancelled pairing must offer focused retry");
    key(*window, Qt::Key_Return);
    require(fixture.starts == 2, "retry did not start a fresh attempt");
    fixture.set("paired", "PC paired with Nova. Open your library to choose a game.", false);
    settle();
    require(primary->property("text") == "Open library" && !pin->isVisible() && !address->isEnabled(), "paired state did not clear the PIN or offer library");
    capture("pairing-complete-fixture.png");
    key(*window, Qt::Key_Return);
    require(!window->isVisible() && root->property("openLibrary").toBool(), "paired continuation must request the native library");
    root.reset();
    fixture.set("idle", "Enter a PC address.", false);
    root.reset(component.create());
    window = qobject_cast<QQuickWindow*>(root.get());
    require(window != nullptr, "second setup window failed");
    fixture.start("fixture-pc", 47989);
    settle();
    window->close();
    settle();
    require(window->isVisible() && fixture.cancels == 2, "window close must await pairing cancellation");
    fixture.set("cancelled", "Pairing cancelled.", false);
    settle();
    require(!window->isVisible() && !root->property("openLibrary").toBool(), "cancelled window did not close without opening library");
    root.reset();
    fixture.reset();
    QQmlComponent management(&engine, QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/SavedPcs.qml"));
    require(management.isReady(), qPrintable(management.errorString()));
    root.reset(management.create());
    window = qobject_cast<QQuickWindow*>(root.get());
    require(window != nullptr, "saved-PC window did not load");
    settle();
    auto* list = root->findChild<QQuickItem*>("saved-pcs-list");
    auto* keep = root->findChild<QQuickItem*>("saved-pcs-keep");
    auto* forget = root->findChild<QQuickItem*>("saved-pcs-forget");
    auto* unpair = root->findChild<QQuickItem*>("saved-pcs-unpair");
    auto* add = root->findChild<QQuickItem*>("saved-pcs-add");
    if (!list || !keep || !forget || !unpair || !add || !list->hasActiveFocus())
        std::cerr << "Saved-PC focus: " << (window->activeFocusItem() ? window->activeFocusItem()->objectName().toStdString() : "none")
                  << "; controls=" << bool(list) << bool(keep) << bool(forget) << bool(unpair) << bool(add) << '\n';
    require(list && keep && forget && unpair && add && list->hasActiveFocus(), "saved-PC initial focus failed");
    capture("saved-pcs-fixture.png");
    key(*window, Qt::Key_Return);
    require(window->activeFocusItem() == keep && fixture.removals == 0, "management must default to Keep PC before mutation");
    capture("saved-pcs-confirm-fixture.png");
    key(*window, Qt::Key_Return);
    require(list->hasActiveFocus() && fixture.removals == 0, "Keep PC unexpectedly removed a pairing");
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Left);
    require(window->activeFocusItem() == forget, "local-forget navigation failed");
    key(*window, Qt::Key_Left);
    require(window->activeFocusItem() == unpair, "unpair navigation failed");
    key(*window, Qt::Key_Return);
    require(fixture.removals == 1 && fixture.removedId == "saved-pc" && !fixture.localOnly && !unpair->isEnabled(),
            "unpair did not select the remote action once");
    fixture.set("failed", "The PC did not confirm unpairing. Its saved record is kept.", false);
    settle();
    require(list->hasActiveFocus() && fixture.savedHosts().size() == 1, "failed unpair lost saved-PC focus");
    capture("saved-pcs-failure-fixture.png");
    key(*window, Qt::Key_Return);
    key(*window, Qt::Key_Left);
    key(*window, Qt::Key_Return);
    require(fixture.removals == 2 && fixture.localOnly, "explicit local forget did not select its separate action");
    fixture.clearHosts();
    fixture.set("removed", "Forgotten on this Deck. Remove Nova Deck from the PC's paired devices to revoke its access.", false);
    settle();
    require(window->activeFocusItem() == add, "empty saved list must focus Add PC");
    key(*window, Qt::Key_Return);
    auto* child = root->findChild<QQuickWindow*>("pair-window");
    require(child && child->isVisible(), "Add PC did not open the pairing screen");
    auto* childAddress = child->findChild<QQuickItem*>("pair-address");
    require(childAddress && child->activeFocusItem() == childAddress, "add-PC focus did not reach address entry");
    child->close();
    settle();
    require(window->isVisible() && window->activeFocusItem() == add, "closing setup did not return to the saved-PC screen");
    // Back returns to the library only when saved hosts remain.
    key(*window, Qt::Key_Right);
    key(*window, Qt::Key_Return);
    require(!window->isVisible() && !root->property("openLibrary").toBool(), "empty manager should close without an unusable library");
    root.reset();
    fixture.hosts = {QVariantMap{{"id", "saved-pc"}, {"name", "Living room PC"}}};
    fixture.reset();
    root.reset(management.create());
    window = qobject_cast<QQuickWindow*>(root.get());
    settle();
    key(*window, Qt::Key_Escape);
    require(!window->isVisible() && root->property("openLibrary").toBool(), "saved-PC back did not request a fresh library");
    root.reset();
    root.reset(management.create());
    window = qobject_cast<QQuickWindow*>(root.get());
    fixture.removeHost("saved-pc", false);
    settle();
    const int beforeCancel = fixture.cancels;
    window->close();
    settle();
    require(window->isVisible() && fixture.cancels == beforeCancel + 1, "manager close did not await the pending change");
    fixture.set("cancelled", "Cancelled. This PC remains saved.", false);
    settle();
    require(!window->isVisible() && fixture.savedHosts().size() == 1, "manager cancellation changed the saved list");
    root.reset();
    fixture.reset();
    root.reset(component.create());
    window = qobject_cast<QQuickWindow*>(root.get());
    settle();
    address = root->findChild<QQuickItem*>("pair-address");
    trusted = root->findChild<QQuickItem*>("pair-trusted");
    primary = root->findChild<QQuickItem*>("pair-primary");
    pin = root->findChild<QQuickItem*>("pair-pin");
    cancel = root->findChild<QQuickItem*>("pair-cancel");
    address->setProperty("text", "trusted-pc");
    tap(*window, trusted);
    require(fixture.trustedStarts == 1 && fixture.selectedAddress == "trusted-pc" && !pin->isVisible() &&
        !trusted->isEnabled() && !primary->isEnabled() && cancel->hasActiveFocus(), "Trusted Pair did not start without a PIN");
    capture("pairing-trusted-pending.png");
    fixture.set("waiting", "Trusted Pair is unavailable here. Enter this PIN in your host's pairing screen.", true, "1234");
    settle();
    require(pin->isVisible() && cancel->hasActiveFocus(), "trusted fallback did not show its PIN with cancellation focused");
    capture("pairing-trusted-fallback.png");
    fixture.set("failed", "The PC's certificate did not match. Nova did not trust the connection.", false);
    settle();
    require(trusted->hasActiveFocus(), "trusted retry did not restore its selected action");
    key(*window, Qt::Key_Return);
    require(fixture.trustedStarts == 2, "trusted retry did not start exactly once");
    fixture.set("paired", "PC paired with Nova. Open your library to choose a game.", false);
    settle();
    require(!trusted->isVisible() && primary->hasActiveFocus() && primary->property("text") == "Open library",
        "trusted success did not focus Open library");
    key(*window, Qt::Key_Return);
    require(!window->isVisible() && root->property("openLibrary").toBool(), "trusted pairing did not continue to library");
    // Large text on a smaller viewport must keep focused controls reachable,
    // including long pairing outcomes and the numeric keyboard footer.
    root.reset();
    QQmlComponent appearance(&engine);
    appearance.setData("import QtQuick\nQtObject { function enlarge() { NovaTheme.setFontScale(1.3) } }",
        QUrl::fromLocalFile(QStringLiteral(NOVA_DECK_QML_DIRECTORY) + "/AppearanceTest.qml"));
    std::unique_ptr<QObject> theme(appearance.create());
    require(theme && QMetaObject::invokeMethod(theme.get(), "enlarge"), "cannot select larger text");
    fixture.reset();
    root.reset(component.create());
    window = qobject_cast<QQuickWindow*>(root.get());
    window->resize(960, 600);
    settle();
    auto visibleFocus = [&] {
        auto* focused = window->activeFocusItem();
        require(focused, "large text lost focus");
        const auto rect = focused->mapRectToScene(focused->boundingRect());
        require(rect.top() >= 0 && rect.left() >= 0 && rect.bottom() <= window->height()
            && rect.right() <= window->width(), "large text focus escaped the viewport");
        for (auto* parent = focused->parentItem(); parent; parent = parent->parentItem())
            if (parent->clip()) require(parent->mapRectToScene(parent->boundingRect()).adjusted(-1, -1, 1, 1).contains(rect),
                "focused control was clipped by its scroll viewport");
    };
    visibleFocus();
    tap(*window, root->findChild<QQuickItem*>("pair-address"));
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    key(*window, Qt::Key_Down);
    for (int i = 0; i < 4; ++i) key(*window, Qt::Key_Right);
    require(window->activeFocusItem()->objectName() == "endpoint-keyboard-done", "large keypad Done was unreachable");
    visibleFocus();
    capture("pairing-large-keypad-960.png");
    key(*window, Qt::Key_Escape);
    fixture.set("trusted", "Connecting with Trusted Pair…", true);
    settle();
    visibleFocus();
    fixture.set("waiting", "Trusted Pair is unavailable here. Enter this PIN in your host's pairing screen. You can cancel and check the selected PC before trying again.", true, "1234");
    settle();
    visibleFocus();
    capture("pairing-large-pin-960.png");
    fixture.reset();
    root.reset();
    root.reset(management.create());
    window = qobject_cast<QQuickWindow*>(root.get());
    window->resize(960, 600);
    settle();
    key(*window, Qt::Key_Return);
    visibleFocus();
    key(*window, Qt::Key_Right);
    visibleFocus();
    capture("saved-pcs-large-confirm-960.png");
    std::cout << "Pairing QML passed: PIN setup, saved PCs, explicit unpair/forget, focus, retry, safe close and large-text scrolling\n";
}
#include "deck_pairing_qml_test.moc"
