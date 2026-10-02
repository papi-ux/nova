#include "stream/deck_pyrowave_probe.h"
#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>
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
        require(result.refusal.has_value(), "real helper did not return the versioned refusal contract");
        require(result.reason.isEmpty() == (*result.refusal == nova::pyrowave::RefusalCause::None),
            "real helper cause contradicts its message");
        require(result.reason.isEmpty() == result.limits.supports(128, 128), "real helper limits contradict its result");
        return 0;
    }
    if (app.arguments().size() == 3 && app.arguments().at(1) == "--reason-case") {
        const auto cause = app.arguments().at(2);
        const std::vector<std::pair<QString, QString>> cases{
            {"api", "PyroWave needs its bundled 0.6.0 codec library. Reinstall Nova or choose another codec."},
            {"device", "PyroWave could not create a Vulkan device. Check this device's Vulkan driver or choose another codec."},
            {"decoder", "PyroWave could not initialize or run its decoder. Restart Nova or choose another codec."},
            {"interop", "PyroWave needs Vulkan external-memory support on this device. Choose another codec."},
            {"dmabuf", "PyroWave cannot export DMA-BUF images on this device. Check the Vulkan driver or choose another codec."},
            {"queue", "PyroWave could not use a Vulkan graphics/compute queue. Restart Nova or choose another codec."},
            {"limits", "This device's Vulkan image limits cannot support PyroWave. Choose another codec."},
            {"unavailable", "PyroWave Vulkan decoding is unavailable on this Linux device. Choose another codec."},
        };
        for (const auto& [code, expected] : cases) if (code == cause) {
            const auto result = probePyrowaveInChild(QCoreApplication::applicationFilePath(), {"cause-" + cause}, 1500);
            require(!result.limits.supports(128, 128), "named refusal admitted PyroWave");
            require(result.reason == expected, "versioned device refusal lost its actionable category");
            require(result.refusal == nova::pyrowave::parseRefusalCause(cause.toStdString()),
                "parsed refusal code lost");
            return 0;
        }
        require(false, "unknown refusal test case");
    }
    if (app.arguments().size() == 2) {
        const auto mode = app.arguments().at(1);
        if (mode.startsWith("cause-")) {
            const auto cause = nova::pyrowave::parseRefusalCause(mode.mid(6).toStdString());
            char output[256];
            const auto size = cause ? nova::pyrowave::formatProbeResult(output, sizeof(output), false, 0, 0, *cause) : 0;
            require(size > 0, "producer refused a known code");
            std::cout.write(output, size);
            return 0;
        }
        if (mode.startsWith("wire-")) {
            QJsonObject object{{"version", 2}, {"available", false}, {"maxWidth", 0}, {"maxHeight", 0}, {"reason", "device"}};
            const auto test = mode.mid(5);
            if (test == "unknown") object["reason"] = "raw PRIVATE driver error";
            else if (test == "missing") object.remove("reason");
            else if (test == "number") object["reason"] = 2;
            else if (test == "bool") object["reason"] = true;
            else if (test == "null") object["reason"] = QJsonValue();
            else if (test == "none-unavailable") object["reason"] = "none";
            else if (test == "dimensions-unavailable") object["maxWidth"] = 128;
            else if (test == "success-refusal") { object["available"] = true; object["maxWidth"] = 128; object["maxHeight"] = 128; }
            else if (test == "success-zero") { object["available"] = true; object["reason"] = "none"; }
            else if (test == "case") object["reason"] = "DEVICE";
            else if (test == "version-string") object["version"] = "2";
            else if (test == "availability-string") object["available"] = "false";
            else if (test == "extra") object["driver"] = "PRIVATE driver error";
            else if (test == "legacy") object["version"] = 1;
            else if (test == "size-string") object["maxWidth"] = "0";
            else require(false, "unknown invalid-wire fixture");
            std::cout << QJsonDocument(object).toJson(QJsonDocument::Compact).toStdString();
            return 0;
        }
        if (mode == "hang") { QThread::msleep(10000); return 0; }
        if (mode == "crash") { rlimit limit{0, 0}; setrlimit(RLIMIT_CORE, &limit); std::raise(SIGABRT); return 1; }
        if (mode == "exit") return 4;
        if (mode == "large") { std::cout << std::string(8192, 'x'); return 0; }
        if (mode == "good") std::cout << R"({"version":2,"available":true,"maxWidth":4096,"maxHeight":2160,"reason":"none"})";
        else if (mode == "unavailable") std::cout << R"({"version":2,"available":false,"maxWidth":0,"maxHeight":0,"reason":"device"})";
        else if (mode == "fraction") std::cout << R"({"version":2,"available":true,"maxWidth":128.5,"maxHeight":128,"reason":"none"})";
        else if (mode == "oversize") std::cout << R"({"version":2,"available":true,"maxWidth":65537,"maxHeight":128,"reason":"none"})";
        else if (mode == "contradictory") std::cout << R"({"version":2,"available":false,"maxWidth":4096,"maxHeight":2160,"reason":"device"})";
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
    for (const auto* code : {"api", "device", "decoder", "interop", "dmabuf", "queue", "limits", "unavailable"}) {
        const auto result = probePyrowaveInChild(program, {QString("cause-") + code}, 1500);
        require(result.refusal == nova::pyrowave::parseRefusalCause(code) && !result.reason.isEmpty() &&
            !result.limits.supports(128, 128), "producer/parser named-refusal disagreement");
    }
    for (const auto* test : {"unknown", "missing", "number", "bool", "null", "none-unavailable", "dimensions-unavailable",
            "success-refusal", "success-zero", "case", "version-string", "availability-string", "extra", "legacy", "size-string"}) {
        const auto result = probePyrowaveInChild(program, {QString("wire-") + test}, 1500);
        require(!result.refusal && result.reason == "PyroWave device check returned an invalid result. Choose another codec." &&
            !result.limits.supports(128, 128), "invalid or contradictory refusal was not rejected safely");
    }
    char buffer[256];
    using nova::pyrowave::RefusalCause;
    require(nova::pyrowave::formatProbeResult(buffer, sizeof(buffer), true, 128, 128, RefusalCause::None) > 0,
        "producer lost successful no-refusal result");
    require(!nova::pyrowave::formatProbeResult(buffer, sizeof(buffer), false, 0, 0, RefusalCause::None) &&
        !nova::pyrowave::formatProbeResult(buffer, sizeof(buffer), true, 128, 128, RefusalCause::Api) &&
        !nova::pyrowave::formatProbeResult(buffer, sizeof(buffer), false, 128, 0, RefusalCause::Device) &&
        !nova::pyrowave::formatProbeResult(buffer, sizeof(buffer), false, 0, 0, static_cast<RefusalCause>(999)) &&
        !nova::pyrowave::formatProbeResult(buffer, 1, true, 128, 128, RefusalCause::None),
        "producer serialized an invalid or truncated result");
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
