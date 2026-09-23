#include "runtime/deck_window_controller.h"
#include <QGuiApplication>
#include <QKeyEvent>
#include <QQuickWindow>
#include <QElapsedTimer>
#include <QThread>
#include <QScreen>
#include <QTemporaryDir>
#include <cstdlib>
#include <iostream>
using namespace nova::deck::runtime;
void require(bool ok, const char* why) { if (!ok) { std::cerr << why << '\n'; std::exit(1); } }
int main(int argc, char** argv) {
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) qputenv("QT_QPA_PLATFORM", "offscreen");
    qunsetenv("XDG_CURRENT_DESKTOP"); qunsetenv("XDG_SESSION_DESKTOP");
    QTemporaryDir config;
    qputenv("XDG_CONFIG_HOME", config.path().toUtf8());
    QGuiApplication app(argc, argv);
    app.setOrganizationName("NovaDeckTests"); app.setApplicationName("WindowController");
    if (app.arguments().contains("--compositor")) {
        // Run separately under a disposable X11/Wayland compositor. Wait for
        // actual configure events and rendered windows, not only Qt's requested
        // visibility. The compositor owns placement on Wayland.
        DeckWindowController controller;
        QQuickWindow window;
        window.setColor(Qt::darkBlue);
        window.resize(720, 480);
        controller.watchWindow(&window);
        const auto wait = [&](const auto& done, const char* why) {
            QElapsedTimer timer; timer.start();
            while (!done() && timer.elapsed() < 5000) { app.processEvents(); QThread::msleep(5); }
            require(done(), why);
        };
        wait([&] { return window.isExposed() && window.size() == QSize(720, 480); }, "compositor did not expose windowed size");
        controller.setFullscreen(true);
        wait([&] { return window.visibility() == QWindow::FullScreen && window.size() == window.screen()->geometry().size(); }, "compositor did not apply fullscreen dimensions");
        QKeyEvent down(QEvent::KeyPress, Qt::Key_F, Qt::ControlModifier | Qt::AltModifier | Qt::ShiftModifier);
        QKeyEvent up(QEvent::KeyRelease, Qt::Key_F, Qt::NoModifier);
        QCoreApplication::sendEvent(&window, &down); QCoreApplication::sendEvent(&window, &up);
        wait([&] { return window.visibility() == QWindow::Windowed && window.size() == QSize(720, 480); }, "compositor did not restore windowed size");
        window.resize(900, 600);
        wait([&] { return window.size() == QSize(900, 600); }, "compositor ignored desktop resize");
        controller.setFullscreen(true); controller.setFullscreen(false);
        wait([&] { return window.visibility() == QWindow::Windowed && window.size() == QSize(900, 600); }, "rapid transition lost resized geometry");
        std::cout << "Compositor configured fullscreen, shortcut return and resized geometry on " << QGuiApplication::platformName().toStdString() << '\n';
        return 0;
    }
    const QRect desktop(-1920, 32, 1920, 1048);
    const auto restored = deckWindowGeometry({3000, 2000, 2560, 1440}, desktop);
    require(desktop.contains(restored) && restored.size() == desktop.size(), "removed display stranded window");
    require(deckWindowGeometry({-1800, 100, 1100, 700}, desktop) == QRect(-1800, 100, 1100, 700), "valid negative monitor coordinates changed");
    require(QRect(0, 0, 320, 200).contains(deckWindowGeometry({}, {0, 0, 320, 200})), "small screen exceeded bounds");
    QRect normal;
    {
        DeckWindowController controller;
        QWindow library, stream, unrelated;
        library.setGeometry(20, 30, 700, 500);
        controller.watchWindow(&library);
        normal = library.geometry();
        require(!controller.fullscreen() && library.visibility() == QWindow::Windowed, "desktop did not default to windowed");
        int transitions = 0;
        QObject::connect(&controller, &DeckWindowController::modeAboutToChange, [&] {
            ++transitions;
            if (transitions == 1) require(library.visibility() == QWindow::Windowed, "input release happened after entering fullscreen");
        });
        controller.setFullscreen(true);
        require(controller.fullscreen() && library.visibility() == QWindow::FullScreen && transitions == 1, "fullscreen mode not applied");
        controller.watchWindow(&stream);
        require(stream.visibility() == QWindow::FullScreen, "presentation did not inherit fullscreen");
        controller.setFullscreen(false);
        require(stream.visibility() == QWindow::Windowed && stream.geometry() == normal, "presentation lost restored geometry");
        stream.resize(680, 460);
        controller.watchWindow(&library);
        require(library.visibility() == QWindow::Windowed && library.geometry() == normal, "return to library lost its own geometry");
        const auto chord = Qt::ControlModifier | Qt::AltModifier | Qt::ShiftModifier;
        QKeyEvent down(QEvent::KeyPress, Qt::Key_F, chord), repeat(QEvent::KeyPress, Qt::Key_F, chord, {}, true);
        QKeyEvent up(QEvent::KeyRelease, Qt::Key_F, Qt::NoModifier);
        QCoreApplication::sendEvent(&unrelated, &down);
        require(!controller.fullscreen(), "unrelated window switched mode");
        QCoreApplication::sendEvent(&library, &down); QCoreApplication::sendEvent(&library, &repeat);
        require(controller.fullscreen(), "shortcut repeated or failed");
        QCoreApplication::sendEvent(&library, &up);
        controller.watchWindow(&stream);
        controller.setFullscreen(false);
        require(stream.size() == QSize(680, 460), "presentation lost its own geometry on reopen");
        controller.setFullscreen(true);
        controller.watchWindow(nullptr);
    }
    {
        DeckWindowController restoredController;
        QWindow window;
        restoredController.watchWindow(&window);
        require(restoredController.fullscreen() && window.visibility() == QWindow::FullScreen, "mode did not survive restart");
        restoredController.setFullscreen(false);
        require(window.size() == QSize(680, 460), "fullscreen overwrote saved window size");
        window.showMaximized();
        restoredController.setFullscreen(true); restoredController.setFullscreen(false);
        require(window.visibility() == QWindow::Maximized, "fullscreen lost maximized state");
        window.showMinimized();
        require(!restoredController.fullscreen(), "minimize changed preference");
    }
    qputenv("XDG_CURRENT_DESKTOP", "gamescope");
    {
        DeckWindowController gameMode;
        QWindow window;
        gameMode.watchWindow(&window);
        require(gameMode.fullscreen(), "Game Mode did not default to fullscreen");
        gameMode.watchWindow(nullptr);
    }
    qunsetenv("XDG_CURRENT_DESKTOP");
    require(!DeckWindowController().fullscreen(), "Game Mode overwrote desktop preference");
    std::cout << "Window modes, geometry recovery, shortcuts, Game Mode isolation and presentation handoff passed\n";
}
