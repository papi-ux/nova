#include "stream/deck_stream_media_adapters.h"

#include <bit>
#include <opus/opus_multistream.h>

namespace nova::deck::stream {

namespace {
constexpr int kSampleRate = 48000;
constexpr int kMaxSamplesPerChannel = kSampleRate * 120 / 1000;
}

DeckPipeWireAudio::DeckPipeWireAudio()
    : ownedOutput_(makeDeckPipeWireOutput()), output_(ownedOutput_.get()) {}

DeckPipeWireAudio::DeckPipeWireAudio(DeckPcmOutput& output) : output_(&output) {}

DeckPipeWireAudio::~DeckPipeWireAudio() {
    const std::lock_guard lock(lifecycleMutex_);
    resetAudioLocked();
}

std::string_view DeckPipeWireAudio::adapterName() const {
    return "opus-pipewire-float-pcm";
}

void DeckPipeWireAudio::resetAudioLocked() {
    ready_ = false;
    lifecycle_.active = false;
    lifecycle_.outputReady = false;
    output_->close();
    if (decoder_) {
        opus_multistream_decoder_destroy(decoder_);
        decoder_ = nullptr;
    }
    pcm_.clear();
    channels_ = 0;
}

int DeckPipeWireAudio::init(const int audioConfiguration,
    POPUS_MULTISTREAM_CONFIGURATION config, void*, int) {
    const std::lock_guard lock(lifecycleMutex_);
    resetAudioLocked();
    ++lifecycle_.initCalls;
    readOutputStats_ = false;
    // Lifecycle call counts are cumulative; media counters describe this run.
    lifecycle_.sampleCalls = 0;
    lifecycle_.lastSampleLength = 0;
    lifecycle_.decodedFrames = 0;
    lifecycle_.concealedFrames = 0;
    lifecycle_.queuedFrames = 0;
    lifecycle_.decodeErrors = 0;
    lifecycle_.audioConfiguration = audioConfiguration;
    lifecycle_.samplesPerFrame = config ? config->samplesPerFrame : 0;
    lifecycle_.lastError.clear();
    const auto channelMask = static_cast<unsigned>(CHANNEL_MASK_FROM_AUDIO_CONFIGURATION(audioConfiguration));
    if (!config || config->sampleRate != kSampleRate || config->channelCount < 1
        || config->channelCount > AUDIO_CONFIGURATION_MAX_CHANNEL_COUNT
        || config->channelCount != CHANNEL_COUNT_FROM_AUDIO_CONFIGURATION(audioConfiguration)
        || std::popcount(channelMask) != config->channelCount || (channelMask >> 11) != 0
        || config->streams < 1 || config->streams > config->channelCount
        || config->coupledStreams < 0 || config->coupledStreams > config->streams
        || config->streams + config->coupledStreams > config->channelCount
        || config->samplesPerFrame < 120 || config->samplesPerFrame > kMaxSamplesPerChannel
        || config->samplesPerFrame % 120 != 0) {
        lifecycle_.lastError = "Unsupported audio configuration";
        return -1;
    }
    int error = OPUS_OK;
    decoder_ = opus_multistream_decoder_create(config->sampleRate, config->channelCount,
        config->streams, config->coupledStreams, config->mapping, &error);
    if (!decoder_ || error != OPUS_OK) {
        resetAudioLocked();
        lifecycle_.lastError = "Could not initialize Opus audio";
        return -1;
    }
    channels_ = config->channelCount;
    pcm_.resize(static_cast<std::size_t>(kMaxSamplesPerChannel * channels_));
    if (!output_->open({config->sampleRate, channels_, channelMask})) {
        resetAudioLocked();
        lifecycle_.lastError = "Audio output is unavailable";
        return -1;
    }
    ready_ = true;
    readOutputStats_ = true;
    lifecycle_.outputReady = true;
    return 0;
}

void DeckPipeWireAudio::start() {
    const std::lock_guard lock(lifecycleMutex_);
    ++lifecycle_.startCalls;
    if (!ready_ || lifecycle_.active) {
        return;
    }
    lifecycle_.active = output_->start();
    lifecycle_.outputReady = lifecycle_.active;
    if (!lifecycle_.active) {
        lifecycle_.lastError = "Audio output could not start";
    } else {
        lifecycle_.lastError.clear();
    }
}

void DeckPipeWireAudio::stop() {
    const std::lock_guard lock(lifecycleMutex_);
    ++lifecycle_.stopCalls;
    lifecycle_.active = false;
    lifecycle_.outputReady = false;
    ready_ = false;
    output_->stop();
}

void DeckPipeWireAudio::cleanup() {
    const std::lock_guard lock(lifecycleMutex_);
    ++lifecycle_.cleanupCalls;
    resetAudioLocked();
}

void DeckPipeWireAudio::decodeAndPlaySample(char* sampleData, const int sampleLength) {
    const std::lock_guard lock(lifecycleMutex_);
    // moonlight-common-c reports a missing packet with exactly (nullptr, 0).
    // Generate only the negotiated packet duration, not the maximum decode size.
    const bool concealLoss = sampleData == nullptr && sampleLength == 0;
    if (!ready_ || !lifecycle_.active || (!concealLoss && (!sampleData || sampleLength <= 0))) {
        return;
    }
    ++lifecycle_.sampleCalls;
    lifecycle_.lastSampleLength = sampleLength;
    const int frames = opus_multistream_decode_float(decoder_,
        reinterpret_cast<const unsigned char*>(sampleData), sampleLength,
        pcm_.data(), concealLoss ? lifecycle_.samplesPerFrame : kMaxSamplesPerChannel, 0);
    if (frames < 0) {
        ++lifecycle_.decodeErrors;
        lifecycle_.lastError = "Invalid Opus audio packet";
        return;
    }
    lifecycle_.decodedFrames += frames;
    if (concealLoss) {
        lifecycle_.concealedFrames += frames;
    }
    if (output_->write(std::span<const float>(pcm_.data(), static_cast<std::size_t>(frames * channels_)))) {
        lifecycle_.queuedFrames += frames;
    } else if (const auto status = output_->stats(); !status.available && !status.recovering) {
        ready_ = false;
        lifecycle_.active = false;
        lifecycle_.outputReady = false;
        lifecycle_.lastError = "Audio output disconnected";
    }
}

DeckAudioLifecycle DeckPipeWireAudio::lifecycle() const {
    const std::lock_guard lock(lifecycleMutex_);
    auto snapshot = lifecycle_;
    const auto output = readOutputStats_ ? output_->stats() : DeckAudioOutputStats{};
    snapshot.submittedFrames = output.submittedFrames;
    snapshot.silenceFrames = output.silenceFrames;
    snapshot.droppedFrames = output.droppedFrames;
    snapshot.discardedFrames = output.discardedFrames;
    snapshot.outputStreaming = output.flowing;
    snapshot.outputRecovering = output.recovering;
    snapshot.audioRecoveries = output.recoveries;
    snapshot.audioRecoveryAttempts = output.recoveryAttempts;
    if (ready_ && !output.available) {
        snapshot.active = lifecycle_.active && output.recovering;
        snapshot.outputReady = false;
        snapshot.lastError = output.recovering ? "Reconnecting audio output" : "Audio output disconnected";
    }
    return snapshot;
}

} // namespace nova::deck::stream
