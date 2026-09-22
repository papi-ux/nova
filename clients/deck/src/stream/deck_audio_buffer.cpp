#include "stream/deck_audio_output.h"

#include <algorithm>

namespace nova::deck::stream {
static_assert(std::atomic<std::uint64_t>::is_always_lock_free);

void DeckPcmRingBuffer::configure(std::size_t capacitySamples) {
    samples_.assign(capacitySamples, 0.0f);
    generations_.assign(capacitySamples, 0);
    writeIndex_.store(0, std::memory_order_relaxed);
    readIndex_.store(0, std::memory_order_relaxed);
}

bool DeckPcmRingBuffer::push(std::span<const float> samples, std::uint64_t generation) {
    const auto write = writeIndex_.load(std::memory_order_relaxed);
    const auto read = readIndex_.load(std::memory_order_acquire);
    if (samples.size() > samples_.size() - (write - read)) return false;
    for (std::size_t i = 0; i < samples.size(); ++i) {
        const auto index = (write + i) % samples_.size();
        samples_[index] = samples[i];
        generations_[index] = generation;
    }
    writeIndex_.store(write + samples.size(), std::memory_order_release);
    return true;
}

std::size_t DeckPcmRingBuffer::pop(std::span<float> destination, std::uint64_t generation, std::size_t* discarded) {
    auto read = readIndex_.load(std::memory_order_relaxed);
    const auto write = writeIndex_.load(std::memory_order_acquire);
    std::size_t copied = 0, skipped = 0;
    while (read < write && copied < destination.size()) {
        const auto index = read++ % samples_.size();
        if (generations_[index] == generation) destination[copied++] = samples_[index];
        else ++skipped;
    }
    readIndex_.store(read, std::memory_order_release);
    if (discarded) *discarded = skipped;
    return copied;
}

std::size_t DeckPcmRingBuffer::discard() {
    const auto read = readIndex_.load(std::memory_order_relaxed);
    const auto write = writeIndex_.load(std::memory_order_acquire);
    readIndex_.store(write, std::memory_order_release);
    return write - read;
}

std::size_t DeckPcmRingBuffer::capacity() const { return samples_.size(); }
}
