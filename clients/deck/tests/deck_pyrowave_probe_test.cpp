#include "stream/deck_pyrowave_probe.h"
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QThread>
#include <cstdlib>
#include <iostream>
#include <thread>
#include <vector>
#include <sys/resource.h>
#include <csignal>
using namespace nova::deck::stream;
namespace { void require(bool value, const char* message) { if (!value) { std::cerr << message << '\n'; std::exit(1); } } }
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (app.arguments().size() == 3 && app.arguments().at(1) == "--real-helper") {
        const auto result = probePyrowaveInChild(app.arguments().at(2));
        require(result.reason.isEmpty() || result.reason ==
            "PyroWave Vulkan decoding is unavailable on this Linux device. Choose another codec.",
            "real helper did not return a valid availability result");
        require(result.reason.isEmpty() == result.limits.supports(128, 128), "real helper limits contradict its result");
        return 0;
    }
    if (app.arguments().size() == 2) {
        const auto mode = app.arguments().at(1);
        if (mode == "hang") { QThread::msleep(10000); return 0; }
        if (mode == "crash") { rlimit limit{0, 0}; setrlimit(RLIMIT_CORE, &limit); std::raise(SIGABRT); return 1; }
        if (mode == "exit") return 4;
        if (mode == "large") { std::cout << std::string(8192, 'x'); return 0; }
        if (mode == "good") std::cout << R"({"version":1,"available":true,"maxWidth":4096,"maxHeight":2160})";
        else if (mode == "unavailable") std::cout << R"({"version":1,"available":false,"maxWidth":0,"maxHeight":0})";
        else if (mode == "fraction") std::cout << R"({"version":1,"available":true,"maxWidth":128.5,"maxHeight":128})";
        else if (mode == "oversize") std::cout << R"({"version":1,"available":true,"maxWidth":65537,"maxHeight":128})";
        else if (mode == "contradictory") std::cout << R"({"version":1,"available":false,"maxWidth":4096,"maxHeight":2160})";
        else if (mode == "unknown-version") std::cout << R"({"version":2,"available":true,"maxWidth":4096,"maxHeight":2160})";
        else std::cout << "malformed driver output";
        return 0;
    }
    const auto program = QCoreApplication::applicationFilePath();
    const auto good = probePyrowaveInChild(program, {"good"}, 1500);
    require(good.limits.supports(1920, 1080) && !good.limits.supports(4096, 2161) && good.reason.isEmpty(), "valid child limits lost");
    for (const auto* mode : {"unavailable", "fraction", "oversize", "contradictory", "unknown-version", "malformed", "large", "exit", "crash"}) {
        const auto bad = probePyrowaveInChild(program, {mode}, 1500);
        require(!bad.limits.supports(128, 128) && !bad.reason.isEmpty(), "failed child admitted PyroWave");
    }
    require(!probePyrowaveInChild("/missing/nova-pyrowave-probe", {}, 100).reason.isEmpty(), "missing helper admitted");
    QElapsedTimer timer; timer.start();
    require(probePyrowaveInChild(program, {"hang"}, 100).reason.contains("timed out"), "hung probe did not time out");
    require(timer.elapsed() < 2000, "timeout did not bound child lifetime");
    DeckPyrowaveProbeCache cache;
    int calls = 0;
    const auto probe = [&] { ++calls; return good; };
    require(calls == 0, "cache construction probes eagerly");
    std::vector<std::thread> callers;
    for (int i = 0; i < 8; ++i) callers.emplace_back([&] { require(cache.get("driver-a", probe).limits.supports(128,128), "cached support lost"); });
    for (auto& caller : callers) caller.join();
    require(calls == 1, "concurrent review/launch repeated the device check");
    cache.get("driver-b", probe);
    require(calls == 2, "changed driver identity reused a stale result");
    const auto failedProbe = [&] { ++calls; return DeckPyrowaveProbeResult{{}, "unavailable"}; };
    cache.get("driver-c", failedProbe); cache.get("driver-c", probe);
    require(calls == 3 && !cache.get("driver-c", probe).reason.isEmpty(), "negative result was not cached");
    std::cout << "Isolated child success, failure, crash, malformed output, timeout and concurrent cache checks passed.\n";
}
