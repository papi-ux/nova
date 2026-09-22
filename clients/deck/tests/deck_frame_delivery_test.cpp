#include "runtime/deck_frame_delivery.h"
#include <QCoreApplication>
#include <QThread>
#include <QElapsedTimer>
#include <atomic>
#include <cstdlib>
#include <future>
#include <iostream>
#include <thread>
#include <vector>

using namespace nova::deck::runtime;
namespace { void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } } }

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    for (int fps : {30, 60, 90}) {
        DeckFrameCadence cadence;
        require(cadence.configure(DeckFramePacing::Balanced, fps), "valid cadence rejected");
        const std::int64_t period = 1'000'000'000LL / fps;
        cadence.arm(0);
        require(cadence.capacity() == 2 && cadence.delay(0) == period, "Balanced did not bound/buffer frames");
        for (int i = 1; i <= 900; ++i) {
            require(cadence.delay(i * period - 1) == 1 && cadence.delay(i * period) == 0, "cadence deadline drifted");
            cadence.advance(i * period);
        }
        const auto stalled = 950 * period;
        require(cadence.missed(stalled), "stalled GUI was not detected");
        cadence.advance(stalled);
        require(cadence.delay(stalled) == period, "missed deadlines caused catch-up bursts");
        cadence.arm(1000 * period);
        require(cadence.delay(1000 * period) == period, "underflow did not rebuffer");
    }
    DeckFrameCadence invalid;
    require(!invalid.configure(DeckFramePacing::Balanced, 0) && !invalid.configure(DeckFramePacing::Balanced, 91)
        && !invalid.configure(static_cast<DeckFramePacing>(100), 60) && invalid.capacity() == 1, "invalid cadence changed defaults");

    // Real queued/timer path: bounded buffering, ordered spaced frames, and
    // responsive GUI work while a frame is held for its deadline.
    {
        std::vector<int> frames;
        DeckFrameDelivery balanced([&](const auto& frame) { frames.push_back(frame.width); });
        const auto configure = balanced.configurator();
        require(configure(DeckFramePacing::Balanced, 30) && !configure(DeckFramePacing::Latency, 60), "stream cadence was not frozen");
        const auto send = balanced.publisher();
        for (int i = 1; i <= 20; ++i) require(send({.width = i}), "balanced publisher rejected frame");
        QCoreApplication::processEvents();
        require(frames.empty(), "Balanced bypassed its first deadline");
        QElapsedTimer timer; timer.start(); int heartbeats = 0;
        while (frames.size() < 2 && timer.elapsed() < 1500) {
            QCoreApplication::processEvents(); ++heartbeats; QThread::msleep(1);
        }
        require(frames == std::vector<int>({19, 20}) && heartbeats > 2 && timer.elapsed() >= 60, "Balanced accumulated frames or failed to pace delivery");
        require(send({.width = 21}) && send({.width = 22}), "stalled fixture publish failed");
        QThread::msleep(110); QCoreApplication::processEvents();
        require(frames.back() == 22 && frames.size() == 3, "GUI stall replayed an obsolete queued frame");
        require(send({.width = 23}), "backlog fixture failed"); QThread::msleep(110);
        require(send({.width = 24}), "newest backlog frame rejected"); QCoreApplication::processEvents();
        require(frames.back() == 24 && frames.size() == 4, "new arrival postponed an overdue backlog");
        auto owner = std::make_shared<int>(1); std::weak_ptr<int> retained = owner;
        DeckFrameDelivery::Frame held;
        held.frameLease = std::shared_ptr<nova::deck::stream::DeckQrhiVaapiFrameLease>(owner, nullptr);
        require(send(held), "pending frame rejected"); held.frameLease.reset(); owner.reset();
        require(!retained.expired(), "timer lost its frame lease");
        balanced.close();
        require(retained.expired() && !send({}) && !configure(DeckFramePacing::Balanced, 30), "closed cadence retained ownership");
        QThread::msleep(40); QCoreApplication::processEvents();
        require(frames.size() == 4, "closed timer dispatched a stale frame");
    }
    std::vector<int> delivered;
    auto receiver = std::make_unique<DeckFrameDelivery>([&](const auto& frame) {
        require(QThread::currentThread() == app.thread(), "producer invoked the GUI consumer directly");
        delivered.push_back(frame.width);
    });
    const auto publish = receiver->publisher();
    const auto configure = receiver->configurator();
    std::thread burst([&] {
        for (int i = 1; i <= 1000; ++i) require(publish({.width = i}), "open delivery rejected a frame");
    });
    burst.join();
    require(delivered.empty(), "frame did not wait for GUI dispatch");
    QCoreApplication::processEvents();
    require(delivered == std::vector<int>{1000}, "producer burst replayed obsolete frames");
    require(!configure(DeckFramePacing::Balanced, 60), "live stream cadence changed");
    // Frame delivery depends on events, with no 16 ms media polling deadline.
    for (int i = 1001; i <= 1180; ++i) {
        require(publish({.width = i}), "delivery failed to rearm");
        QCoreApplication::processEvents();
        require(delivered.back() == i, "queued frame needed a lifecycle timer tick");
    }
    auto owner = std::make_shared<int>(1);
    std::weak_ptr<int> lease = owner;
    // Aliasing ownership verifies lifetime without claiming a decoded GPU frame.
    DeckFrameDelivery::Frame held;
    held.frameLease = std::shared_ptr<nova::deck::stream::DeckQrhiVaapiFrameLease>(owner, nullptr);
    require(publish(held), "cannot retain pending lease");
    held.frameLease.reset(); owner.reset();
    require(!lease.expired(), "pending frame lease was released early");
    require(publish({.width = 1200}) && lease.expired(), "replaced frame retained its lease");
    receiver->close();
    QCoreApplication::processEvents();
    require(delivered.back() == 1180 && !publish({.width = 1201}), "closed receiver replayed or accepted a late frame");
    receiver.reset();
    require(!publish({.width = 1202}), "publisher dereferenced a destroyed receiver");
    require(!configure(DeckFramePacing::Balanced, 60), "configurator dereferenced a destroyed receiver");

    // A producer can race GUI destruction; retained publishers must become inert.
    for (int iteration = 0; iteration < 20; ++iteration) {
        auto racing = std::make_unique<DeckFrameDelivery>([](const auto&) { require(false, "destroyed receiver dispatched a frame"); });
        auto send = racing->publisher();
        std::promise<void> started;
        std::thread producer([&] {
            require(send({.width = 1}), "race fixture was closed early");
            started.set_value();
            while (send({.width = 2})) std::this_thread::yield();
        });
        started.get_future().wait();
        racing.reset();
        producer.join();
        QCoreApplication::processEvents();
    }
    std::cout << "Frame delivery passed: 30/60/90 cadence, bounded buffering, deadline/stall recovery, GUI responsiveness, frozen configuration, queued handoff, leases and destruction races\n";
}
