#pragma once

#include <atomic>
#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <functional>
#include <span>
#include <vector>

namespace nova::deck::stream {

// Interleaved float PCM in the channel order of GameStream's speaker mask.
struct DeckAudioFormat {
    int sampleRate = 0;
    int channels = 0;
    unsigned channelMask = 0;
};

struct DeckAudioOutputStats {
    std::uint64_t submittedFrames = 0;
    std::uint64_t silenceFrames = 0;
    std::uint64_t droppedFrames = 0;
    bool available = false;
    bool flowing = false; // PipeWire is processing; not proof of audible output.
    bool recovering = false;
    std::uint64_t recoveries = 0;
    std::uint64_t recoveryAttempts = 0;
    std::uint64_t discardedFrames = 0; // Previously queued PCM retired after a route change.
};

// One producer (the Opus callback), one consumer (PipeWire's realtime thread).
// Configure/reset only when both are stopped. Neither push nor pop allocates,
// blocks or overwrites data still being read. Overflow rejects the entire write.
class DeckPcmRingBuffer {
public:
    void configure(std::size_t capacitySamples);
    bool push(std::span<const float> samples, std::uint64_t generation = 0);
    std::size_t pop(std::span<float> destination, std::uint64_t generation = 0, std::size_t* discarded = nullptr);
    // Consumer only, or after both threads stop. Does not reset producer state.
    std::size_t discard();
    std::size_t capacity() const;

private:
    std::vector<float> samples_;
    std::vector<std::uint64_t> generations_;
    alignas(64) std::atomic<std::uint64_t> writeIndex_{0};
    alignas(64) std::atomic<std::uint64_t> readIndex_{0};
};

// Tests inject a recorder. Production owns the PipeWire implementation; no
// display, host connection, or QML object is needed to decode and play audio.
class DeckPcmOutput {
public:
    virtual ~DeckPcmOutput() = default;
    virtual bool open(const DeckAudioFormat& format) = 0;
    virtual bool start() = 0;
    virtual void stop() = 0;
    virtual void close() = 0;
    virtual bool write(std::span<const float> samples) = 0;
    // Frame counters are monotonic until the next open(), including across
    // stop/close, so the recovering owner can retain final retirement totals.
    virtual DeckAudioOutputStats stats() const = 0;
};

std::unique_ptr<DeckPcmOutput> makeDeckPipeWireOutput();
// Initial open stays fail-fast. After start, a worker recreates failed outputs
// with capped backoff; decode/write never waits for open/close or a retry delay.
std::unique_ptr<DeckPcmOutput> makeDeckRecoveringOutput(
    std::function<std::unique_ptr<DeckPcmOutput>()> factory,
    std::array<std::chrono::milliseconds, 4> delays = {
        std::chrono::milliseconds{0}, std::chrono::milliseconds{250},
        std::chrono::milliseconds{1000}, std::chrono::milliseconds{3000}});

} // namespace nova::deck::stream
