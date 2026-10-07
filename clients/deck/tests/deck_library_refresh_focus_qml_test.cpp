#include "runtime/deck_play_settings.h"

#include <QCoreApplication>
#include <QElapsedTimer>
#include <QGuiApplication>
#include <QKeyEvent>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickWindow>
#include <QTemporaryDir>
#include <QTest>

#include <cstdlib>
#include <iostream>
#include <memory>

namespace {
void require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << message << '\n';
        std::abort();
    }
}

QQuickItem* find(QQuickItem* parent, const QString& name) {
    if (parent->objectName() == name && parent->isVisible()) return parent;
    for (auto* child : parent->childItems())
        if (auto* result = find(child, name)) return result;
    return nullptr;
}
}

int main(int argc, char** argv) {
    QTemporaryDir config;
    require(config.isValid(), "isolated config directory unavailable");
    qputenv("XDG_CONFIG_HOME", config.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests");
    QCoreApplication::setApplicationName("LibraryRefreshFocus");
    nova::deck::runtime::DeckPlaySettings settings(config.filePath("play.ini"));
    QQmlEngine engine;
    engine.rootContext()->setContextProperty("settings", &settings);
    bool warnings = false;
    QObject::connect(&engine, &QQmlEngine::warnings, [&](const QList<QQmlError>& errors) {
        warnings = true;
        for (const auto& error : errors) std::cerr << error.toString().toStdString() << '\n';
    });
    QQmlComponent component(&engine);
    component.setData("import QtQuick\nimport QtQuick.Controls\nimport \"" +
        QUrl::fromLocalFile(NOVA_DECK_QML_DIRECTORY).toEncoded() + "\"\n" + R"(
        ApplicationWindow {
            width: 1280; height: 800; visible: true
            LibraryBrowser {
                id: library; anchors.fill: parent
                settingsProvider: settings
                host: ({id: "host", displayName: "Fixture PC"})
                refreshState: ({busy: false, automatic: true, failed: false, destinationPlayable: true})
                games: [{id: "game-42", title: "Control Ultimate Edition", source: "steam"}]
            }
            function backgroundCompletion() { library.prepare(true); library.apply() }
            function manualCompletion() { library.prepare(false); library.apply() }
            function constrain() { library.setFilter("source", "steam") }
        }
    )", QUrl());
    auto root = std::unique_ptr<QObject>(component.create());
    if (!root) std::cerr << component.errorString().toStdString();
    require(bool(root), "actual library could not be created");
    auto* window = qobject_cast<QQuickWindow*>(root.get());
    require(window, "library window unavailable");
    QTest::qWait(150);
    const auto item = [&](const char* name) {
        auto* result = find(window->contentItem(), QString::fromLatin1(name));
        require(result, name);
        return result;
    };
    const auto complete = [&](const char* method) {
        require(QMetaObject::invokeMethod(root.get(), method), "refresh completion unavailable");
    };
    const auto keyBeforeDeferredRestore = [&](Qt::Key key) {
        // Deliver input synchronously while the real apply() restoration is
        // still queued. No sleeps or network timing choose this ordering.
        QKeyEvent press(QEvent::KeyPress, key, Qt::NoModifier);
        QKeyEvent release(QEvent::KeyRelease, key, Qt::NoModifier);
        QCoreApplication::sendEvent(window, &press);
        QCoreApplication::sendEvent(window, &release);
    };
    auto* filter = item("library-filter-all");
    auto* search = item("library-search");
    auto* options = item("library-options");
    filter->forceActiveFocus();
    complete("backgroundCompletion");
    keyBeforeDeferredRestore(Qt::Key_Up);
    require(window->activeFocusItem() == search, "Up did not reach Search before deferred restore");
    QTest::qWait(150);
    require(window->activeFocusItem() == search, "background completion undid the player's newer Search focus");

    complete("backgroundCompletion");
    keyBeforeDeferredRestore(Qt::Key_Up);
    require(window->activeFocusItem() == options, "Up did not reach Options before deferred restore");
    QTest::qWait(150);
    require(window->activeFocusItem() == options, "background completion undid the player's newer Options focus");

    filter->forceActiveFocus();
    complete("backgroundCompletion");
    keyBeforeDeferredRestore(Qt::Key_Down);
    QTest::qWait(150);
    require(window->activeFocusItem() && window->activeFocusItem()->objectName() == "game-42",
            "background completion undid the player's newer game focus");

    complete("constrain");
    QTest::qWait(150);
    auto* more = item("library-filter-more");
    auto* clear = item("library-clear-filters");
    more->forceActiveFocus();
    complete("backgroundCompletion");
    keyBeforeDeferredRestore(Qt::Key_Right);
    require(window->activeFocusItem() == clear, "Right did not reach Clear before deferred restore");
    QTest::qWait(150);
    require(window->activeFocusItem() == clear, "background completion undid the player's newer Clear focus");

    filter->forceActiveFocus();
    complete("backgroundCompletion");
    QTest::qWait(150);
    require(window->activeFocusItem() == filter, "unchanged background completion lost existing focus");
    complete("manualCompletion");
    QElapsedTimer deadline;
    deadline.start();
    while ((!window->activeFocusItem() || window->activeFocusItem()->objectName() != "game-42") && deadline.elapsed() < 3000)
        QTest::qWait(20);
    require(window->activeFocusItem() && window->activeFocusItem()->objectName() == "game-42", "explicit refresh lost its game focus recovery");
    require(!warnings, "library refresh focus emitted QML warnings");
    std::cout << "Deferred background completion retains newer Search, Options, game and Clear focus; unchanged and explicit refresh recovery passed.\n";
}
