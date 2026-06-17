#pragma once

#include "stream/deck_stream_core.h"

#include <string>
#include <string_view>

struct AVBufferRef;
struct AVCodecContext;
struct AVFrame;

namespace nova::deck::stream {

struct DeckLinuxMediaProbe {
    bool ffmpegLibavcodecHeadersLinked = false;
    bool ffmpegLibavutilHeadersLinked = false;
    bool vaapiHeadersLinked = false;
    bool qtQuickRhiPresentationBoundary = false;
    bool h264DecoderAvailable = false;
    bool runtimeVaapiDeviceAvailable = false;
    std::string hardwareDeviceTypeName;
    std::string runtimeStatus;

    static DeckLinuxMediaProbe detect();
};

struct DeckRendererLifecycle {
    int setupCalls = 0;
    int startCalls = 0;
    int submitCalls = 0;
    int stopCalls = 0;
    int cleanupCalls = 0;
    bool acceptedNullDecodeUnit = false;
    bool networkStartAllowed = false;
    bool runtimeVaapiDeviceAvailable = false;
    bool ownsHardwareDevice = false;
    bool ownsCodecContext = false;
    int decodedHardwareFrames = 0;
    bool lastFrameWasHardwareBacked = false;
    std::string runtimeStatus;
    std::string lastRuntimeError;
    int width = 0;
    int height = 0;
    int redrawRate = 0;
    int videoFormat = 0;
};

class DeckVaapiFfmpegRenderer final : public DeckStreamRenderer {
public:
    ~DeckVaapiFfmpegRenderer() override;
    DeckVaapiFfmpegRenderer() = default;
    DeckVaapiFfmpegRenderer(const DeckVaapiFfmpegRenderer&) = delete;
    DeckVaapiFfmpegRenderer& operator=(const DeckVaapiFfmpegRenderer&) = delete;
    DeckVaapiFfmpegRenderer(DeckVaapiFfmpegRenderer&&) = delete;
    DeckVaapiFfmpegRenderer& operator=(DeckVaapiFfmpegRenderer&&) = delete;

    std::string_view adapterName() const override;
    int setup(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) override;
    void start() override;
    void stop() override;
    void cleanup() override;
    int submitDecodeUnit(PDECODE_UNIT decodeUnit) override;

    const DeckRendererLifecycle& lifecycle() const;

private:
    void resetDecoder();

    DeckRendererLifecycle lifecycle_{};
    bool ready_ = false;
    AVBufferRef* hardwareDevice_ = nullptr;
    AVCodecContext* codecContext_ = nullptr;
    AVFrame* decodedFrame_ = nullptr;
};

struct DeckLinuxAudioProbe {
    bool pipeWireHeadersLinked = false;
    bool pulseFallbackHeadersLinked = false;
    std::string pipeWireHeaderVersion;
    std::string pulseHeaderVersion;

    static DeckLinuxAudioProbe detect();
};

struct DeckAudioLifecycle {
    int initCalls = 0;
    int startCalls = 0;
    int sampleCalls = 0;
    int stopCalls = 0;
    int cleanupCalls = 0;
    int audioConfiguration = 0;
    int samplesPerFrame = 0;
    int lastSampleLength = 0;
    bool networkStartAllowed = false;
};

class DeckPipeWireAudio final : public DeckStreamAudio {
public:
    std::string_view adapterName() const override;
    int init(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) override;
    void start() override;
    void stop() override;
    void cleanup() override;
    void decodeAndPlaySample(char* sampleData, int sampleLength) override;

    const DeckAudioLifecycle& lifecycle() const;

private:
    DeckAudioLifecycle lifecycle_{};
    bool ready_ = false;
};

} // namespace nova::deck::stream
