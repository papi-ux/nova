#include "stream/deck_audio_output.h"

#include <algorithm>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace nova::deck::stream {
namespace {
using namespace std::chrono_literals;

class RecoveringOutput final : public DeckPcmOutput {
public:
    RecoveringOutput(std::function<std::unique_ptr<DeckPcmOutput>()> factory,
        std::array<std::chrono::milliseconds, 4> delays) : factory_(std::move(factory)), delays_(delays) {}
    ~RecoveringOutput() override { close(); }

    bool open(const DeckAudioFormat& format) override {
        close();
        auto output = create(format);
        const std::lock_guard lock(mutex_);
        format_ = format;
        totals_ = {};
        output_ = std::move(output);
        return output_ != nullptr;
    }

    bool start() override {
        const std::lock_guard lock(mutex_);
        if (running_) return true;
        if (!output_) return false;
        // A service loss between init and start should use the same recovery
        // route. Actual device open/close work is never done in write().
        restart_ = !output_->start();
        running_ = true;
        try { worker_ = std::thread([this] { recover(); }); }
        catch (...) { running_ = false; return false; }
        return true;
    }

    void stop() override { close(); }
    void close() override {
        {
            const std::lock_guard lock(mutex_);
            running_ = false;
            wake_.notify_all();
        }
        if (worker_.joinable()) worker_.join();
        retire();
    }

    bool write(std::span<const float> samples) override {
        const std::lock_guard lock(mutex_);
        if (!running_ || !format_.channels || samples.size() % format_.channels) return false;
        if (!output_) {
            totals_.droppedFrames += samples.size() / format_.channels;
            return false;
        }
        return output_->write(samples);
    }

    DeckAudioOutputStats stats() const override {
        const std::lock_guard lock(mutex_);
        auto result = totals_;
        if (output_) {
            const auto current = output_->stats();
            add(result, current);
            result.available = current.available;
            result.flowing = running_ && current.flowing;
        }
        result.recovering = running_ && (restart_ || !result.available || !result.flowing);
        return result;
    }

private:
    static void add(DeckAudioOutputStats& to, const DeckAudioOutputStats& from) {
        to.submittedFrames += from.submittedFrames;
        to.silenceFrames += from.silenceFrames;
        to.droppedFrames += from.droppedFrames;
        to.discardedFrames += from.discardedFrames;
    }
    std::unique_ptr<DeckPcmOutput> create(const DeckAudioFormat& format) {
        try {
            auto output = factory_();
            if (output && output->open(format)) return output;
        } catch (...) { /* Only sanitized state crosses the audio boundary. */ }
        return {};
    }
    void retire() {
        std::unique_ptr<DeckPcmOutput> old;
        DeckAudioOutputStats before;
        {
            const std::lock_guard lock(mutex_);
            old = std::move(output_);
            if (!old) return;
            before = old->stats();
            add(totals_, before);
        }
        // No writer can retain the old backend. Its RT loop must fully stop
        // before a replacement is published, and before this object can close.
        old->close();
        const auto after = old->stats();
        const std::lock_guard lock(mutex_);
        totals_.submittedFrames += after.submittedFrames - before.submittedFrames;
        totals_.silenceFrames += after.silenceFrames - before.silenceFrames;
        totals_.droppedFrames += after.droppedFrames - before.droppedFrames;
        totals_.discardedFrames += after.discardedFrames - before.discardedFrames;
    }
    void recover() {
        std::size_t delayIndex = 0;
        auto stableSince = std::chrono::steady_clock::now();
        auto lastProgress = stableSince;
        std::uint64_t frames = 0;
        for (;;) {
            {
                std::unique_lock lock(mutex_);
                if (!running_) break;
                const auto current = output_ ? output_->stats() : DeckAudioOutputStats{};
                if (output_ && current.available && !restart_) {
                    const auto now = std::chrono::steady_clock::now();
                    if (!current.flowing || now - lastProgress > 250ms) stableSince = now;
                    if (current.submittedFrames > frames) {
                        lastProgress = now;
                        if (current.flowing && now - stableSince >= 2s) delayIndex = 0;
                    }
                    frames = current.submittedFrames;
                    wake_.wait_for(lock, 25ms, [this] { return !running_; });
                    continue;
                }
            }
            retire();
            {
                std::unique_lock lock(mutex_);
                if (!running_ || wake_.wait_for(lock, delays_[std::min(delayIndex, delays_.size() - 1)],
                    [this] { return !running_; })) break;
                delayIndex = std::min(delayIndex + 1, delays_.size() - 1);
                ++totals_.recoveryAttempts;
            }
            auto replacement = create(format_);
            {
                const std::lock_guard lock(mutex_);
                // close() may have arrived during a bounded backend open.
                // A late candidate is destroyed here without becoming writable.
                if (!running_) break;
                if (replacement && replacement->start()) {
                    output_ = std::move(replacement);
                    restart_ = false;
                    ++totals_.recoveries;
                    frames = 0;
                    stableSince = lastProgress = std::chrono::steady_clock::now();
                }
            }
        }
    }

    std::function<std::unique_ptr<DeckPcmOutput>()> factory_;
    std::array<std::chrono::milliseconds, 4> delays_;
    mutable std::mutex mutex_;
    std::condition_variable wake_;
    std::thread worker_;
    std::unique_ptr<DeckPcmOutput> output_;
    DeckAudioFormat format_{};
    DeckAudioOutputStats totals_{};
    bool running_ = false, restart_ = false;
};
}

std::unique_ptr<DeckPcmOutput> makeDeckRecoveringOutput(
    std::function<std::unique_ptr<DeckPcmOutput>()> factory,
    std::array<std::chrono::milliseconds, 4> delays) {
    return std::make_unique<RecoveringOutput>(std::move(factory), delays);
}
}
