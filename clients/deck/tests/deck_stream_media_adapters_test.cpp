#include "stream/deck_stream_media_adapters.h"

#include <Limelight.h>

#include <cassert>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <string_view>
#include <type_traits>
#include <vector>

#include <sys/wait.h>
#include <unistd.h>

namespace {

class NoopInput final : public nova::deck::stream::DeckStreamInput {
public:
    std::string_view adapterName() const override { return "noop-input"; }
    void rumble(uint16_t controllerNumber, uint16_t lowFreqMotor, uint16_t highFreqMotor) override {
        (void)controllerNumber;
        (void)lowFreqMotor;
        (void)highFreqMotor;
    }
    void setMotionEventState(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) override {
        (void)controllerNumber;
        (void)motionType;
        (void)reportRateHz;
    }
    void setControllerLed(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) override {
        (void)controllerNumber;
        (void)r;
        (void)g;
        (void)b;
    }
};

class RecordingEvents final : public nova::deck::stream::DeckStreamSessionEvents {
public:
    void onSessionEvent(nova::deck::stream::DeckStreamSessionState state, std::string_view reason) override {
        states.push_back(state);
        reasons.emplace_back(reason);
    }

    std::vector<nova::deck::stream::DeckStreamSessionState> states;
    std::vector<std::string> reasons;
};

std::vector<std::uint8_t> readBinaryFile(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    assert(input.good());
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

std::vector<std::uint8_t> makeLocalAnnexBH264IdrSample() {
    const auto output = std::filesystem::temp_directory_path() / ("nova-deck-local-idr-" + std::to_string(getpid()) + ".h264");
    const pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        execlp(
            "ffmpeg",
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "color=c=black:s=128x72:r=1:d=1",
            "-frames:v",
            "1",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-tune",
            "zerolatency",
            "-x264-params",
            "keyint=1:min-keyint=1:scenecut=0",
            "-f",
            "h264",
            output.c_str(),
            static_cast<char*>(nullptr));
        _exit(127);
    }
    int status = 0;
    assert(waitpid(pid, &status, 0) == pid);
    assert(WIFEXITED(status));
    assert(WEXITSTATUS(status) == 0);
    auto bytes = readBinaryFile(output);
    assert(!bytes.empty());
    return bytes;
}

DECODE_UNIT makeDecodeUnit(std::vector<std::uint8_t>& annexBBytes, LENTRY& entry) {
    entry.next = nullptr;
    entry.data = reinterpret_cast<char*>(annexBBytes.data());
    entry.length = static_cast<int>(annexBBytes.size());
    entry.bufferType = BUFFER_TYPE_PICDATA;

    DECODE_UNIT unit{};
    unit.frameNumber = 1;
    unit.frameType = FRAME_TYPE_IDR;
    unit.fullLength = entry.length;
    unit.bufferList = &entry;
    return unit;
}

} // namespace

int main() {
    using nova::deck::stream::DeckLinuxAudioProbe;
    using nova::deck::stream::DeckLinuxMediaProbe;
    using nova::deck::stream::DeckPipeWireAudio;
    using nova::deck::stream::DeckStreamRequest;
    using nova::deck::stream::DeckStreamSession;
    using nova::deck::stream::DeckStreamSessionState;
    using nova::deck::stream::DeckVaapiFfmpegRenderer;

    static_assert(!std::is_copy_constructible_v<DeckVaapiFfmpegRenderer>);
    static_assert(!std::is_move_constructible_v<DeckVaapiFfmpegRenderer>);

    const DeckLinuxMediaProbe mediaProbe = DeckLinuxMediaProbe::detect();
    assert(mediaProbe.ffmpegLibavcodecHeadersLinked);
    assert(mediaProbe.ffmpegLibavutilHeadersLinked);
    assert(mediaProbe.vaapiHeadersLinked);
    assert(mediaProbe.qtQuickRhiPresentationBoundary);
    assert(mediaProbe.hardwareDeviceTypeName == std::string("vaapi"));
    assert(!mediaProbe.runtimeStatus.empty());

    DeckVaapiFfmpegRenderer renderer;
    assert(renderer.adapterName() == "ffmpeg-vaapi-h264-qt-rhi-prototype");
    const int rendererSetup = renderer.setup(VIDEO_FORMAT_H264, 1280, 800, 60, nullptr, 0);
    assert(rendererSetup == (renderer.lifecycle().runtimeVaapiDeviceAvailable ? DR_OK : DR_NEED_IDR));
    renderer.start();
    assert(renderer.submitDecodeUnit(nullptr) == DR_NEED_IDR);
    renderer.stop();
    renderer.cleanup();
    assert(renderer.lifecycle().setupCalls == 1);
    assert(renderer.lifecycle().startCalls == 1);
    assert(renderer.lifecycle().submitCalls == 1);
    assert(renderer.lifecycle().stopCalls == 1);
    assert(renderer.lifecycle().cleanupCalls == 1);
    assert(!renderer.lifecycle().acceptedNullDecodeUnit);
    assert(renderer.lifecycle().networkStartAllowed == false);
    assert(renderer.lifecycle().runtimeVaapiDeviceAvailable == mediaProbe.runtimeVaapiDeviceAvailable);
    assert(!renderer.lifecycle().runtimeStatus.empty());

    DeckVaapiFfmpegRenderer decodeRenderer;
    const int decodeSetup = decodeRenderer.setup(VIDEO_FORMAT_H264, 128, 72, 1, nullptr, 0);
    auto idrBytes = makeLocalAnnexBH264IdrSample();
    LENTRY idrEntry{};
    DECODE_UNIT idrUnit = makeDecodeUnit(idrBytes, idrEntry);
    if (decodeRenderer.lifecycle().runtimeVaapiDeviceAvailable) {
        assert(decodeSetup == DR_OK);
        assert(decodeRenderer.lifecycle().ownsHardwareDevice);
        assert(decodeRenderer.lifecycle().ownsCodecContext);
        assert(decodeRenderer.submitDecodeUnit(&idrUnit) == DR_OK);
        assert(decodeRenderer.lifecycle().decodedHardwareFrames == 1);
        assert(decodeRenderer.lifecycle().lastFrameWasHardwareBacked);
    } else {
        assert(decodeSetup == DR_NEED_IDR);
        assert(!decodeRenderer.lifecycle().ownsHardwareDevice);
        assert(!decodeRenderer.lifecycle().ownsCodecContext);
        assert(decodeRenderer.submitDecodeUnit(&idrUnit) == DR_NEED_IDR);
        assert(decodeRenderer.lifecycle().decodedHardwareFrames == 0);
        assert(!decodeRenderer.lifecycle().lastRuntimeError.empty());
        assert(decodeRenderer.lifecycle().lastRuntimeError.find("av_hwdevice_ctx_create(VAAPI) failed") != std::string::npos);
    }
    decodeRenderer.cleanup();

    const DeckLinuxAudioProbe audioProbe = DeckLinuxAudioProbe::detect();
    assert(audioProbe.pipeWireHeadersLinked);
    assert(audioProbe.pulseFallbackHeadersLinked);

    OPUS_MULTISTREAM_CONFIGURATION opusConfig{};
    opusConfig.samplesPerFrame = 240;
    DeckPipeWireAudio audio;
    assert(audio.adapterName() == "pipewire-pcm-pulse-fallback-prototype");
    assert(audio.init(AUDIO_CONFIGURATION_STEREO, &opusConfig, nullptr, 0) == 0);
    audio.start();
    char pcm[] = {'p', 'c', 'm', '!'};
    audio.decodeAndPlaySample(pcm, 4);
    audio.stop();
    audio.cleanup();
    assert(audio.lifecycle().initCalls == 1);
    assert(audio.lifecycle().startCalls == 1);
    assert(audio.lifecycle().sampleCalls == 1);
    assert(audio.lifecycle().lastSampleLength == 4);
    assert(audio.lifecycle().samplesPerFrame == 240);
    assert(audio.lifecycle().stopCalls == 1);
    assert(audio.lifecycle().cleanupCalls == 1);
    assert(audio.lifecycle().networkStartAllowed == false);

    DeckVaapiFfmpegRenderer callbackRenderer;
    DeckPipeWireAudio callbackAudio;
    NoopInput input;
    RecordingEvents events;
    DeckStreamSession session(callbackRenderer, callbackAudio, input, events);
    assert(!session.moonlightBoundary().networkStartAllowed);
    const auto prepared = session.prepare(DeckStreamRequest{
        .hostId = "offline-harness-host",
        .gameId = "offline-harness-game",
        .width = 1280,
        .height = 800,
        .fps = 60,
        .bitrateKbps = 20000,
    });
    assert(prepared.state == DeckStreamSessionState::Preparing);
    assert(!prepared.networkStarted);
    const int callbackRendererSetup = session.moonlightBoundary().videoCallbacks->setup(VIDEO_FORMAT_H264, 1280, 800, 60, session.moonlightBoundary().callbackContext, 0);
    assert(callbackRendererSetup == (callbackRenderer.lifecycle().runtimeVaapiDeviceAvailable ? DR_OK : DR_NEED_IDR));
    assert(session.moonlightBoundary().videoCallbacks->submitDecodeUnit(nullptr) == DR_NEED_IDR);
    OPUS_MULTISTREAM_CONFIGURATION callbackOpusConfig{};
    callbackOpusConfig.samplesPerFrame = 240;
    assert(session.moonlightBoundary().audioCallbacks->init(AUDIO_CONFIGURATION_STEREO, &callbackOpusConfig, session.moonlightBoundary().callbackContext, 0) == 0);
    assert(callbackRenderer.lifecycle().setupCalls == 1);
    assert(callbackRenderer.lifecycle().submitCalls == 1);
    assert(callbackAudio.lifecycle().initCalls == 1);
    const auto started = session.startNoNetwork();
    assert(started.state == DeckStreamSessionState::Active);
    assert(!started.networkStarted);
    session.stop();

    return 0;
}
