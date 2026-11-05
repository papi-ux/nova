#include "runtime/deck_frame_delivery.h"
#include "runtime/deck_source_delivery_policy.h"
#include <QCoreApplication>
#include <QThread>
#include <QElapsedTimer>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <future>
#include <iostream>
#include <thread>
#include <vector>

using namespace nova::deck::runtime;
namespace { void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } } }

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    for (int fps : {15, 30, 60, 90, 120, 144, 165, 240}) {
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
    require(!invalid.configure(DeckFramePacing::Balanced, 0) && !invalid.configure(DeckFramePacing::Balanced, 241)
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
        // Equivalent to the display refresh connection while source timing is OFF:
        // both queued frames and their old generation survive the timing reset.
        balanced.resetTiming(999);
        require(balanced.stats().queued==2, "OFF refresh reset cleared the legacy queue");
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
#ifdef __linux__
    {
        const auto monotonicNs = [] { return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count(); };
        std::vector<int> frames; std::vector<std::int64_t> actual;
        DeckFrameDelivery source([&](const auto& frame) { frames.push_back(frame.width); actual.push_back(monotonicNs()); });
        require(source.setSourceTimingEnabled(true) && source.configurator()(DeckFramePacing::Balanced,120), "source timing could not configure its finite deadline");
        source.resetTiming(7);
        auto send=source.publisher();
        const auto start=monotonicNs()/1000;
        const auto timed=[&](int id,std::int64_t target,std::uint64_t generation=7) {
            DeckFrameDelivery::Frame f; f.width=id; f.timing.presentationGeneration=generation;
            f.timing.clock.epoch=1; f.timing.clock.frame=id; f.timing.clock.sourceUs=id*1000;
            f.timing.clock.valid=f.timing.clock.active=true; f.timing.clock.targetUs=target; return f;
        };
        require(send(timed(1,start+5500)) && send(timed(2,start+10750)), "source publish rejected");
        QCoreApplication::processEvents(); require(frames.empty(), "future source frame delivered early");
        QElapsedTimer deadline; deadline.start();
        while(frames.size()<2 && deadline.elapsed()<1500) { QCoreApplication::processEvents(); QThread::usleep(100); }
        require(frames==std::vector<int>({1,2}), "source frames were lost or reordered");
        require(actual[0]>=(start+5500)*1000 && actual[1]>=(start+10750)*1000, "sub-ms source target rounded early");
        // A sub-second source jump with no following observations must never arm
        // its distant deadline (and must never be submitted early).
        require(send(timed(99,monotonicNs()/1000+886984)), "jump rejection failed");
        QCoreApplication::processEvents();
        require(frames.size()==2 && source.stats().queued==0 &&
            source.stats().fallback=="hold_ceiling_exceeded", "source jump escaped final hold ceiling");
        source.resetTiming(8);
        require(send(timed(3,monotonicNs()/1000-30000,8)) && send(timed(4,monotonicNs()/1000-20000,8)), "overdue publish failed");
        QCoreApplication::processEvents(); require(frames.size()==3 && frames.back()==4, "stalled source backlog caused a catch-up burst");
        require(send(timed(5,monotonicNs()/1000-10000,8)), "sole late publish failed");
        QCoreApplication::processEvents(); require(frames.size()==4 && frames.back()==5, "sole late current frame was dropped after stall recovery");
        // A target 100 delivered at 140; B target 120 decoded at 145.
        // Both are overdue, but B has newer source chronology and must display.
        source.resetTiming(9);
        auto a=timed(80,monotonicNs()/1000-40000,9);
        require(send(a), "A fixture failed"); QCoreApplication::processEvents();
        auto b=timed(81,a.timing.clock.targetUs+20000,9);
        QThread::msleep(5); require(send(b), "B fixture failed"); QCoreApplication::processEvents();
        require(frames.back()==81, "newer sole late B was superseded by wall time");
        require(send(a), "stale A consume failed"); QCoreApplication::processEvents();
        require(frames.back()==81 && source.stats().fallback=="stale_frame", "older A redisplayed");
        source.resetTiming(8);
        require(send(timed(6,monotonicNs()/1000+50000,7)), "old generation was not safely consumed");
        QCoreApplication::processEvents(); require(source.stats().queued==0, "recreated surface accepted old timing");
        // Queue old-epoch active work, then a new-epoch warmup point while
        // the GUI is stalled. Publication fences old leases before dispatch.
        auto epochOwner=std::make_shared<int>(1); std::weak_ptr<int> epochLease=epochOwner;
        auto old=timed(70,monotonicNs()/1000+10000,8);
        old.frameLease=std::shared_ptr<nova::deck::stream::DeckQrhiVaapiFrameLease>(epochOwner,nullptr);
        require(send(old), "old epoch fixture failed"); old.frameLease.reset(); epochOwner.reset();
        auto warm=timed(71,0,8); warm.timing.clock.epoch=2; warm.timing.clock.active=false;
        require(send(warm) && epochLease.expired(), "warmup epoch failed to release old lease");
        QThread::msleep(25); QCoreApplication::processEvents();
        require(frames.back()!=70, "old epoch delivered after GUI stall");
        require(send(old), "stale descriptor was not consumed"); QCoreApplication::processEvents();
        require(source.stats().queued==0 && source.stats().fallback=="stale_epoch", "old epoch was rearmed");
        auto owner=std::make_shared<int>(1); std::weak_ptr<int> lease=owner;
        auto held=timed(7,monotonicNs()/1000+10000,8); held.timing.clock.epoch=2;
        held.frameLease=std::shared_ptr<nova::deck::stream::DeckQrhiVaapiFrameLease>(owner,nullptr);
        require(send(held), "future lease rejected"); held.frameLease.reset(); owner.reset();
        source.close(); require(lease.expired() && !send({}), "source close retained a lease or accepted late work");
    }
#endif
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
    std::cout << "Frame delivery passed: 15–240 FPS cadence, bounded buffering, deadline/stall recovery, GUI responsiveness, frozen configuration, queued handoff, leases and destruction races\n";
}
