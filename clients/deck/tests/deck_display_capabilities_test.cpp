#include "runtime/deck_display_capabilities.h"
#include <QGuiApplication>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <thread>

using namespace nova::deck::runtime;
namespace { void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } } }

int main(int argc, char** argv) {
    QGuiApplication app(argc, argv);
    double reported = 90;
    std::function<int()> retained;
    {
        DeckDisplayCapabilities display([&](QScreen*) { return reported; });
        retained = display.rateLimitReader();
        require(retained() == 60 && !display.state().value("known").toBool(), "missing window inferred a fast display");
        auto window = std::make_unique<QWindow>();
        display.watchWindow(window.get());
        require(retained() == 90 && display.state().value("refreshHz") == 90, "active window rate not read");
        int changes = 0;
        QObject::connect(&display, &DeckDisplayCapabilities::stateChanged, [&] { ++changes; });
        display.refresh();
        require(changes == 0, "unchanged display churned UI state");
        for (const double hz : {40.0, 45.0, 50.0, 59.94, 60.0, 72.0, 75.0, 87.9, 88.0, 89.94, 90.0, 120.0, 240.0, 0.0, -1.0, 1001.0,
                std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity()}) {
            reported = hz;
            // Exercise the real screen-signal binding, not only direct refresh.
            require(QMetaObject::invokeMethod(window->screen(), "refreshRateChanged", Q_ARG(qreal, hz)), "cannot signal display change");
            const bool known = std::isfinite(hz) && hz > 0 && hz <= 1000;
            const int expected = !known ? 60 : hz >= 88 ? 90 : hz >= 58 && hz < 60 ? 60 : static_cast<int>(std::floor(hz));
            require(display.state().value("known").toBool() == known && retained() == expected, "invalid refresh classification");
            std::thread worker([&] { require(retained() == expected, "worker did not observe rate change"); });
            worker.join();
        }
        reported = 90;
        display.refresh();
        window.reset();
        require(!display.state().value("known").toBool() && retained() == 60, "removed window retained high FPS authority");
        QWindow replacement;
        display.watchWindow(&replacement);
        require(retained() == 90, "replacement window was not observed");
        require(display.state().size() == 3, "display identifiers leaked into public state");
    }
    require(retained() == 60, "destroyed display provider retained high FPS authority");
    QWindow window;
    DeckDisplayCapabilities real;
    real.watchWindow(&window);
    const auto actual = window.screen()->refreshRate();
    require(real.state().value("refreshHz").toDouble() == actual, "production reader did not use the window's screen");
    std::cout << "Display capabilities passed: current screen, rate signals, thresholds, removal and worker lifetime\n";
}
