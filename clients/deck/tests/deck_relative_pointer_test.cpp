#include "runtime/deck_relative_pointer.h"
#include <QGuiApplication>
#include <QQuickWindow>
#include <QElapsedTimer>
#include <QThread>
#include <QCursor>
#include <QtGui/qguiapplication_platform.h>
#include <xcb/xtest.h>
#include <xcb/xinput.h>
#include <map>
#include <vector>
#include <cstdlib>
#include <iostream>
using namespace nova::deck::runtime;
void require(bool ok, const char* why) { if (!ok) { std::cerr << why << '\n'; std::exit(1); } }
int main(int argc, char** argv) {
    QGuiApplication app(argc, argv);
    const bool wayland = app.arguments().contains("--wayland");
    DeckRelativePointer pointer;
    QQuickWindow window;
    window.setGeometry(100, 100, 640, 480);
    window.setCursor(QCursor(Qt::CrossCursor));
    if (wayland) window.showFullScreen(); else window.show();
    window.requestActivate();
    const auto wait = [&](const auto& ready, const char* why) {
        QElapsedTimer timer; timer.start();
        while (!ready() && timer.elapsed() < 3000) { app.processEvents(); QThread::msleep(5); }
        require(ready(), why);
    };
    wait([&] { return window.isActive() && window.isExposed(); }, "test window did not activate");
    wait([&] { return pointer.available(); }, "native relative pointer backend unavailable");
    auto* x11 = app.nativeInterface<QNativeInterface::QX11Application>();
    auto* connection = x11 ? x11->connection() : nullptr;
    const auto masks = [&] {
        std::map<std::pair<xcb_window_t, uint16_t>, std::vector<uint32_t>> result;
        if (!connection) return result;
        for (auto screens = xcb_setup_roots_iterator(xcb_get_setup(connection)); screens.rem; xcb_screen_next(&screens)) {
            auto* reply = xcb_input_xi_get_selected_events_reply(connection, xcb_input_xi_get_selected_events(connection, screens.data->root), nullptr);
            require(reply, "could not inspect Qt input subscriptions");
            for (auto it = xcb_input_xi_get_selected_events_masks_iterator(reply); it.rem; xcb_input_event_mask_next(&it)) {
                const auto* data = xcb_input_event_mask_mask(it.data);
                auto words = std::vector<uint32_t>(data, data + it.data->mask_len);
                while (!words.empty() && words.back() == 0) words.pop_back();
                if (!words.empty()) result[{screens.data->root, it.data->deviceid}] = words;
            }
            std::free(reply);
        }
        return result;
    };
    const auto originalMasks = masks();
    double dx = 0, dy = 0;
    QObject::connect(&pointer, &DeckRelativePointer::motion, [&](double x, double y) { dx += x; dy += y; });
    require(pointer.start(&window), "relative capture refused");
    wait([&] { return pointer.active(); }, "relative capture did not become active");
    require(window.cursor().shape() == Qt::BlankCursor, "capture cursor stayed visible");
    require(wayland || connection, "X11 input injection test requires X11");
    // XTEST on a disposable Xvfb server: movements exceed the entire display,
    // proving that raw input continues while the desktop cursor is at an edge.
    for (int i = 0; i < 4; ++i) {
        if (connection) {
            xcb_test_fake_input(connection, XCB_MOTION_NOTIFY, 1, XCB_CURRENT_TIME, XCB_NONE, 4000, -3000, 0);
            xcb_flush(connection);
        }
        wait([&] { return dx >= 4000 * (i + 1) && dy <= -3000 * (i + 1); }, "relative movement stopped at a screen edge");
    }
    pointer.stop();
    require(!pointer.active() && !pointer.pending() && window.cursor().shape() == Qt::CrossCursor,
        "release retained grab or changed the previous cursor");
    require(masks() == originalMasks, "capture changed Qt device subscriptions after release");
    const double stopped = dx;
    if (connection) { xcb_test_fake_input(connection, XCB_MOTION_NOTIFY, 1, XCB_CURRENT_TIME, XCB_NONE, -100, 0, 0); xcb_flush(connection); }
    QElapsedTimer settle; settle.start(); while (settle.elapsed() < 80) { app.processEvents(); QThread::msleep(5); }
    require(dx == stopped, "released pointer kept observing motion");
    require(pointer.start(&window), "second capture refused");
    wait([&] { return pointer.active(); }, "second capture did not activate");
    pointer.stop();
    std::cout << "Native relative capture, edge-free motion, cursor restore and release passed\n";
}
