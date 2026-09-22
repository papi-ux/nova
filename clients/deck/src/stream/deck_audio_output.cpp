#include "stream/deck_audio_output.h"

#include <algorithm>
#include <array>
#include <bit>
#include <mutex>
#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>

namespace nova::deck::stream {

namespace {

class PipeWireOutput final : public DeckPcmOutput {
public:
    ~PipeWireOutput() override { close(); }

    bool open(const DeckAudioFormat& format) override {
        close();
        // These are the positions encoded by GameStream's WAVEFORMATEXTENSIBLE
        // speaker mask. Keep the order explicit rather than assuming Opus/Vorbis.
        static constexpr std::array<spa_audio_channel, 11> positions{
            SPA_AUDIO_CHANNEL_FL, SPA_AUDIO_CHANNEL_FR, SPA_AUDIO_CHANNEL_FC,
            SPA_AUDIO_CHANNEL_LFE, SPA_AUDIO_CHANNEL_RL, SPA_AUDIO_CHANNEL_RR,
            SPA_AUDIO_CHANNEL_FLC, SPA_AUDIO_CHANNEL_FRC, SPA_AUDIO_CHANNEL_RC,
            SPA_AUDIO_CHANNEL_SL, SPA_AUDIO_CHANNEL_SR,
        };
        if (format.sampleRate != 48000 || format.channels < 1 || format.channels > 8
            || (format.channelMask >> positions.size()) != 0
            || std::popcount(format.channelMask) != format.channels) {
            return false;
        }
        format_ = format;
        ring_.configure(static_cast<std::size_t>(format.sampleRate * format.channels * 120 / 1000));
        submittedFrames_ = 0;
        silenceFrames_ = 0;
        droppedFrames_ = 0;
        discardedFrames_ = 0;
        static std::once_flag initialization;
        std::call_once(initialization, [] { pw_init(nullptr, nullptr); });
        loop_ = pw_thread_loop_new("nova-audio", nullptr);
        if (loop_ == nullptr) {
            return false;
        }
        static const pw_stream_events events = [] {
            pw_stream_events result{};
            result.version = PW_VERSION_STREAM_EVENTS;
            result.state_changed = stateChanged;
            result.process = process;
            return result;
        }();
        stream_ = pw_stream_new_simple(pw_thread_loop_get_loop(loop_), "Nova audio",
            pw_properties_new(PW_KEY_MEDIA_TYPE, "Audio", PW_KEY_MEDIA_CATEGORY, "Playback",
                PW_KEY_MEDIA_ROLE, "Game", PW_KEY_NODE_NAME, "nova-audio",
                PW_KEY_NODE_DONT_RECONNECT, "false", "node.dont-move", "false",
                PW_KEY_NODE_LATENCY, "240/48000", nullptr),
            &events, this);
        if (stream_ == nullptr || pw_thread_loop_start(loop_) < 0) {
            close();
            return false;
        }
        loopStarted_ = true;
        spa_audio_info_raw info{};
        info.format = SPA_AUDIO_FORMAT_F32;
        info.rate = format.sampleRate;
        info.channels = format.channels;
        unsigned outputChannel = 0;
        for (unsigned bit = 0; bit < positions.size(); ++bit) {
            if (format.channelMask & (1u << bit)) {
                info.position[outputChannel++] = positions[bit];
            }
        }
        std::array<std::uint8_t, 1024> storage{};
        spa_pod_builder builder = SPA_POD_BUILDER_INIT(storage.data(), storage.size());
        const spa_pod* params[] = {spa_format_audio_raw_build(&builder, SPA_PARAM_EnumFormat, &info)};
        pw_thread_loop_lock(loop_);
        const int result = pw_stream_connect(stream_, PW_DIRECTION_OUTPUT, PW_ID_ANY,
            static_cast<pw_stream_flags>(PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_INACTIVE
                | PW_STREAM_FLAG_MAP_BUFFERS | PW_STREAM_FLAG_RT_PROCESS), params, 1);
        timespec deadline{};
        pw_thread_loop_get_time(loop_, &deadline, 2 * SPA_NSEC_PER_SEC);
        auto state = pw_stream_get_state(stream_, nullptr);
        while (result >= 0 && state == PW_STREAM_STATE_CONNECTING) {
            if (pw_thread_loop_timed_wait_full(loop_, &deadline) < 0) {
                break;
            }
            state = pw_stream_get_state(stream_, nullptr);
        }
        const bool ready = result >= 0 && (state == PW_STREAM_STATE_PAUSED || state == PW_STREAM_STATE_STREAMING);
        pw_thread_loop_unlock(loop_);
        if (!ready) {
            close();
        }
        return ready;
    }

    bool start() override {
        if (!stream_ || !loopStarted_) {
            return false;
        }
        pw_thread_loop_lock(loop_);
        const bool started = available_.load(std::memory_order_relaxed)
            && pw_stream_set_active(stream_, true) >= 0;
        pw_thread_loop_unlock(loop_);
        return started;
    }

    void stop() override {
        // Joining PipeWire's loop and destroying its stream establishes a hard
        // boundary before ring storage can be reused. Old PCM never crosses a
        // session restart. AudioRenderer start/stop is once per initialized run.
        close();
    }

    void close() override {
        available_ = false;
        streaming_ = false;
        ++generation_;
        if (loopStarted_) {
            pw_thread_loop_stop(loop_);
            loopStarted_ = false;
        }
        if (stream_) {
            pw_stream_destroy(stream_);
            stream_ = nullptr;
        }
        if (loop_) {
            pw_thread_loop_destroy(loop_);
            loop_ = nullptr;
        }
        available_.store(false, std::memory_order_relaxed);
        streaming_.store(false, std::memory_order_relaxed);
        if (format_.channels) discardedFrames_.fetch_add(ring_.discard() / format_.channels);
    }

    bool write(const std::span<const float> samples) override {
        if (!format_.channels || samples.size() % format_.channels) return false;
        const auto generation = generation_.load();
        // No PCM backlog while an output is absent or the graph is paused.
        // Generation tags also reject a write racing a pause/relink boundary.
        if (!available_.load() || !streaming_.load() || !ring_.push(samples, generation)) {
            droppedFrames_.fetch_add(samples.size() / format_.channels, std::memory_order_relaxed);
            return false;
        }
        return true;
    }

    DeckAudioOutputStats stats() const override {
        return {submittedFrames_.load(std::memory_order_relaxed),
            silenceFrames_.load(std::memory_order_relaxed), droppedFrames_.load(std::memory_order_relaxed),
            available_.load(std::memory_order_relaxed), streaming_.load(std::memory_order_relaxed),
            false, 0, 0, discardedFrames_.load(std::memory_order_relaxed)};
    }

private:
    static void stateChanged(void* data, pw_stream_state, pw_stream_state state, const char*) {
        auto& self = *static_cast<PipeWireOutput*>(data);
        self.available_.store(state == PW_STREAM_STATE_PAUSED || state == PW_STREAM_STATE_STREAMING,
            std::memory_order_relaxed);
        self.streaming_.store(state == PW_STREAM_STATE_STREAMING);
        if (state != PW_STREAM_STATE_STREAMING) ++self.generation_;
        pw_thread_loop_signal(self.loop_, false);
    }

    static void process(void* data) {
        auto& self = *static_cast<PipeWireOutput*>(data);
        pw_buffer* queued = pw_stream_dequeue_buffer(self.stream_);
        if (!queued) {
            return;
        }
        spa_buffer* buffer = queued->buffer;
        if (!buffer || buffer->n_datas == 0 || !buffer->datas[0].data || !buffer->datas[0].chunk) {
            pw_stream_queue_buffer(self.stream_, queued);
            return;
        }
        auto& plane = buffer->datas[0];
        const std::size_t channels = self.format_.channels;
        const std::size_t maxFrames = plane.maxsize / (sizeof(float) * channels);
        const auto frames = queued->requested ? std::min<std::size_t>(queued->requested, maxFrames) : maxFrames;
        const std::span<float> output(static_cast<float*>(plane.data), frames * channels);
        const auto generation = self.generation_.load();
        std::size_t discarded = 0;
        auto copied = self.ring_.pop(output, generation, &discarded);
        if (!self.streaming_.load() || generation != self.generation_.load()) {
            discarded += copied;
            copied = 0;
        }
        self.discardedFrames_.fetch_add(discarded / channels, std::memory_order_relaxed);
        std::fill(output.begin() + copied, output.end(), 0.0f);
        plane.chunk->offset = 0;
        plane.chunk->stride = sizeof(float) * channels;
        plane.chunk->size = output.size_bytes();
        queued->size = frames;
        if (pw_stream_queue_buffer(self.stream_, queued) >= 0) {
            self.submittedFrames_.fetch_add(copied / channels, std::memory_order_relaxed);
            self.silenceFrames_.fetch_add((output.size() - copied) / channels, std::memory_order_relaxed);
        } else {
            self.available_.store(false, std::memory_order_relaxed);
            self.streaming_ = false;
            ++self.generation_;
            self.discardedFrames_.fetch_add(copied / channels, std::memory_order_relaxed);
        }
    }

    DeckAudioFormat format_{};
    DeckPcmRingBuffer ring_;
    pw_thread_loop* loop_ = nullptr;
    pw_stream* stream_ = nullptr;
    bool loopStarted_ = false;
    std::atomic<bool> available_{false};
    std::atomic<bool> streaming_{false};
    std::atomic<std::uint64_t> generation_{0};
    std::atomic<std::uint64_t> discardedFrames_{0};
    std::atomic<std::uint64_t> submittedFrames_{0};
    std::atomic<std::uint64_t> silenceFrames_{0};
    std::atomic<std::uint64_t> droppedFrames_{0};
};

} // namespace

std::unique_ptr<DeckPcmOutput> makeDeckPipeWireOutput() {
    return makeDeckRecoveringOutput([] { return std::make_unique<PipeWireOutput>(); });
}

} // namespace nova::deck::stream
