#include "stream/deck_audio_output.h"

#include <condition_variable>
#include <cstdlib>
#include <iostream>
#include <mutex>
#include <thread>

using namespace nova::deck::stream;
using namespace std::chrono_literals;
namespace {
void require(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}
template<class Predicate> void until(Predicate predicate) {
    const auto deadline = std::chrono::steady_clock::now() + 3s;
    while (!predicate() && std::chrono::steady_clock::now() < deadline) std::this_thread::sleep_for(1ms);
    require(predicate(), "audio recovery fixture timed out");
}
struct Barrier {
    std::mutex mutex;
    std::condition_variable wake;
    std::atomic<bool> entered{false};
    bool released = false;
    void wait() {
        std::unique_lock lock(mutex); entered = true;
        require(wake.wait_for(lock, 3s, [&] { return released; }), "audio barrier timed out");
    }
    void release() { const std::lock_guard lock(mutex); released = true; wake.notify_all(); }
};
struct State {
    std::atomic<bool> available{true}, flowing{true};
    bool allowOpen = true, blockOpen = false, disconnectOnStart = false;
    Barrier opening;
    std::atomic<int> opens{0}, starts{0}, closes{0};
    std::atomic<std::uint64_t> submitted{0}, dropped{0};
    DeckAudioFormat format{};
    std::thread::id openThread;
};
class Output final : public DeckPcmOutput {
public:
    explicit Output(std::shared_ptr<State> state) : state(std::move(state)) {}
    ~Output() override { close(); }
    bool open(const DeckAudioFormat& format) override {
        state->format = format;
        state->openThread = std::this_thread::get_id();
        ++state->opens;
        if (state->blockOpen) state->opening.wait();
        return state->allowOpen;
    }
    bool start() override { ++state->starts; if (state->disconnectOnStart) state->available = false; return true; }
    void stop() override { close(); }
    void close() override { if (!closed) { ++state->closes; closed = true; } }
    bool write(std::span<const float> samples) override {
        require(!closed, "write raced retired backend");
        if (!state->available || !state->flowing) { state->dropped += samples.size() / state->format.channels; return false; }
        state->submitted += samples.size() / state->format.channels;
        return true;
    }
    DeckAudioOutputStats stats() const override {
        return {state->submitted, 0, state->dropped, !closed && state->available, !closed && state->flowing};
    }
    std::shared_ptr<State> state;
    bool closed = false;
};
const DeckAudioFormat stereo{48000, 2, 3};
const std::array<float, 4> samples{0.25f, 0.5f, 0.25f, 0.5f};

void bufferGenerations() {
    DeckPcmRingBuffer ring;
    ring.configure(8);
    require(ring.push(samples, 1), "cannot queue old route PCM");
    require(ring.push(samples, 2), "cannot queue new route PCM");
    std::array<float, 8> result{};
    std::size_t discarded = 0;
    require(ring.pop(result, 2, &discarded) == 4 && discarded == 4, "old route PCM survived a generation change");
    // A producer that entered before pause may publish after the consumer's
    // first flush. Its captured generation must still make that PCM obsolete.
    require(ring.push(samples, 1) && ring.push(samples, 2), "wrap fixture failed");
    require(ring.pop(result, 2, &discarded) == 4 && discarded == 4, "late old producer escaped retirement");
    require(ring.push(samples, 2) && ring.discard() == 4 && ring.pop(result, 2) == 0,
        "teardown retained queued audio");
}
void recoveryAndCancellation(bool cancelDuringOpen) {
    auto first = std::make_shared<State>(), second = std::make_shared<State>();
    second->blockOpen = true;
    std::atomic<int> created{0};
    auto output = makeDeckRecoveringOutput([&] {
        if (++created == 1) return std::make_unique<Output>(first);
        require(first->closes == 1, "replacement opened before old RT output closed");
        return std::make_unique<Output>(second);
    });
    require(output->open(stereo) && output->start() && output->write(samples), "recovery fixture failed");
    first->available = false;
    until([&] { return second->opening.entered.load(); });
    require(output->stats().recovering && !output->stats().available, "outage not visible");
    const auto before = std::chrono::steady_clock::now();
    for (int i = 0; i < 100; ++i) require(!output->write(samples), "unavailable output queued stale PCM");
    require(std::chrono::steady_clock::now() - before < 100ms, "writes waited for device open");
    require(output->stats().submittedFrames == 2 && output->stats().droppedFrames == 200, "outage lost counter provenance");
    if (cancelDuringOpen) {
        std::thread closer([&] { output->close(); });
        until([&] { return !output->stats().recovering; });
        second->opening.release();
        closer.join();
        require(!output->stats().available && !output->stats().flowing && second->closes == 1 && second->starts == 0 && second->submitted == 0,
            "late open survived close or replayed PCM");
    } else {
        second->opening.release();
        until([&] { return output->stats().recoveries == 1; });
        require(second->format.sampleRate == 48000 && second->format.channels == 2 && second->format.channelMask == 3,
            "recovery renegotiated immutable layout");
        require(second->openThread != std::this_thread::get_id() && second->submitted == 0,
            "device recovery ran on decoder thread or replayed old PCM");
        require(output->write(samples) && output->stats().submittedFrames == 4 && !output->stats().recovering,
            "fresh PCM did not reach recovered output");
        output->close();
    }
    const int attempts = created;
    std::this_thread::sleep_for(50ms);
    require(created == attempts && !output->write(samples), "stop allowed a late retry/write");
}
void pausedRouteAndBackoff() {
    auto state = std::make_shared<State>();
    state->flowing = false;
    auto output = makeDeckRecoveringOutput([&] { return std::make_unique<Output>(state); });
    require(output->open(stereo) && output->start(), "paused fixture failed");
    require(output->stats().available && output->stats().recovering && !output->write(samples), "unlinked route accumulated stale audio");
    state->flowing = true;
    require(output->write(samples) && !output->stats().recovering && state->opens == 1, "routing return unnecessarily recreated backend");
    output->close();

    auto initial = std::make_shared<State>(), failed = std::make_shared<State>();
    failed->allowOpen = false;
    int created = 0;
    output = makeDeckRecoveringOutput([&] { return std::make_unique<Output>(++created == 1 ? initial : failed); },
        {0ms, 5s, 5s, 5s});
    require(output->open(stereo) && output->start(), "backoff fixture failed");
    initial->available = false;
    until([&] { return failed->closes.load() >= 1; });
    const auto before = std::chrono::steady_clock::now();
    output->stop();
    require(std::chrono::steady_clock::now() - before < 100ms && created == 2, "stop waited for retry delay or created another output");

    auto unstable = std::make_shared<State>();
    unstable->disconnectOnStart = true;
    output = makeDeckRecoveringOutput([&] { return std::make_unique<Output>(unstable); }, {0ms, 60ms, 80ms, 100ms});
    require(output->open(stereo) && output->start(), "unstable fixture failed");
    until([&] { return output->stats().recoveryAttempts >= 1; });
    std::this_thread::sleep_for(190ms);
    output->stop();
    require(unstable->opens >= 3 && unstable->opens <= 4, "short-lived connections reset backoff into a busy loop");
}
}
int main() {
    bufferGenerations();
    recoveryAndCancellation(false);
    recoveryAndCancellation(true);
    pausedRouteAndBackoff();
    std::cout << "Audio recovery: generation retirement, nonblocking decode, immutable layout, counters and cancellation passed\n";
}
