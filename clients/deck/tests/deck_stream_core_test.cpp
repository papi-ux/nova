#include "stream/deck_stream_core.h"

#include <Limelight.h>

#include <cassert>
#include <cstdint>
#include <string>
#include <string_view>
#include <type_traits>
#include <vector>

namespace {

using nova::deck::stream::DeckStreamRequest;
using nova::deck::stream::DeckStreamSession;
using nova::deck::stream::DeckStreamSessionState;

struct RendererCall {
    int videoFormat = 0;
    int width = 0;
    int height = 0;
    int redrawRate = 0;
    int flags = 0;

    bool operator==(const RendererCall&) const = default;
};

struct AudioCall {
    int audioConfiguration = 0;
    int flags = 0;

    bool operator==(const AudioCall&) const = default;
};

struct InputCall {
    uint16_t controllerNumber = 0;
    uint16_t first = 0;
    uint16_t second = 0;
    uint16_t third = 0;

    bool operator==(const InputCall&) const = default;
};

class RecordingEvents final : public nova::deck::stream::DeckStreamSessionEvents {
public:
    void onSessionEvent(DeckStreamSessionState state, std::string_view reason) override {
        states.push_back(state);
        reasons.emplace_back(reason);
    }

    std::vector<DeckStreamSessionState> states;
    std::vector<std::string> reasons;
};

class StubRenderer final : public nova::deck::stream::DeckStreamRenderer {
public:
    std::string_view adapterName() const override { return "stub-renderer"; }

    int setup(int videoFormat, int width, int height, int redrawRate, void* context, int flags) override {
        setupCalls.push_back(RendererCall{videoFormat, width, height, redrawRate, flags});
        contexts.push_back(context);
        return setupResult;
    }

    void start() override { ++startCalls; }
    void stop() override { ++stopCalls; }
    void cleanup() override { ++cleanupCalls; }
    int submitDecodeUnit(PDECODE_UNIT decodeUnit) override {
        decodeUnits.push_back(decodeUnit);
        return DR_OK;
    }

    int setupResult = 0;
    int startCalls = 0;
    int stopCalls = 0;
    int cleanupCalls = 0;
    std::vector<RendererCall> setupCalls;
    std::vector<void*> contexts;
    std::vector<PDECODE_UNIT> decodeUnits;
};

class StubAudio final : public nova::deck::stream::DeckStreamAudio {
public:
    std::string_view adapterName() const override { return "stub-audio"; }

    int init(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int flags) override {
        initCalls.push_back(AudioCall{audioConfiguration, flags});
        opusConfigs.push_back(opusConfig);
        contexts.push_back(context);
        return initResult;
    }

    void start() override { ++startCalls; }
    void stop() override { ++stopCalls; }
    void cleanup() override { ++cleanupCalls; }
    void decodeAndPlaySample(char* sampleData, int sampleLength) override {
        samples.push_back(sampleData);
        sampleLengths.push_back(sampleLength);
    }

    int initResult = 0;
    int startCalls = 0;
    int stopCalls = 0;
    int cleanupCalls = 0;
    std::vector<AudioCall> initCalls;
    std::vector<POPUS_MULTISTREAM_CONFIGURATION> opusConfigs;
    std::vector<void*> contexts;
    std::vector<char*> samples;
    std::vector<int> sampleLengths;
};

class StubInput final : public nova::deck::stream::DeckStreamInput {
public:
    std::string_view adapterName() const override { return "stub-input"; }

    void rumble(uint16_t controllerNumber, uint16_t lowFreqMotor, uint16_t highFreqMotor) override {
        rumbles.push_back(InputCall{controllerNumber, lowFreqMotor, highFreqMotor, 0});
    }

    void setMotionEventState(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) override {
        motionStates.push_back(InputCall{controllerNumber, motionType, reportRateHz, 0});
    }

    void setControllerLed(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) override {
        leds.push_back(InputCall{controllerNumber, r, g, b});
    }

    std::vector<InputCall> rumbles;
    std::vector<InputCall> motionStates;
    std::vector<InputCall> leds;
};

DeckStreamRequest validRequest(std::string gameId = "game-123") {
    return DeckStreamRequest{
        .hostId = "host-gaming-pc",
        .gameId = std::move(gameId),
        .width = 1280,
        .height = 800,
        .fps = 60,
        .bitrateKbps = 20000,
    };
}

} // namespace

static_assert(!std::is_copy_constructible_v<DeckStreamSession>);
static_assert(!std::is_copy_assignable_v<DeckStreamSession>);
static_assert(!std::is_move_constructible_v<DeckStreamSession>);
static_assert(!std::is_move_assignable_v<DeckStreamSession>);

int main() {
    STREAM_CONFIGURATION streamConfig;
    LiInitializeStreamConfiguration(&streamConfig);
    assert(streamConfig.width == 0);
    assert((VIDEO_FORMAT_H264 & VIDEO_FORMAT_MASK_H264) != 0);

    DECODER_RENDERER_CALLBACKS videoCallbacks;
    LiInitializeVideoCallbacks(&videoCallbacks);
    AUDIO_RENDERER_CALLBACKS audioCallbacks;
    LiInitializeAudioCallbacks(&audioCallbacks);
    CONNECTION_LISTENER_CALLBACKS listenerCallbacks;
    LiInitializeConnectionCallbacks(&listenerCallbacks);

    assert(videoCallbacks.setup == nullptr);
    assert(audioCallbacks.init == nullptr);
    assert(listenerCallbacks.stageStarting == nullptr);

    StubRenderer renderer;
    StubAudio audio;
    StubInput input;
    RecordingEvents events;

    DeckStreamSession session(renderer, audio, input, events);
    assert(session.state() == DeckStreamSessionState::Idle);
    assert(session.moonlightBoundary().videoCallbacks != nullptr);
    assert(session.moonlightBoundary().audioCallbacks != nullptr);
    assert(session.moonlightBoundary().listenerCallbacks != nullptr);
    assert(session.moonlightBoundary().callbackContext != nullptr);
    assert(!session.moonlightBoundary().networkStartAllowed);

    const auto* sessionVideoCallbacks = session.moonlightBoundary().videoCallbacks;
    const auto* sessionAudioCallbacks = session.moonlightBoundary().audioCallbacks;
    const auto* sessionListenerCallbacks = session.moonlightBoundary().listenerCallbacks;
    void* callbackContext = session.moonlightBoundary().callbackContext;
    assert(sessionVideoCallbacks->setup != nullptr);
    assert(sessionVideoCallbacks->start != nullptr);
    assert(sessionVideoCallbacks->stop != nullptr);
    assert(sessionVideoCallbacks->cleanup != nullptr);
    assert(sessionVideoCallbacks->submitDecodeUnit != nullptr);
    assert(sessionAudioCallbacks->init != nullptr);
    assert(sessionAudioCallbacks->start != nullptr);
    assert(sessionAudioCallbacks->stop != nullptr);
    assert(sessionAudioCallbacks->cleanup != nullptr);
    assert(sessionAudioCallbacks->decodeAndPlaySample != nullptr);
    assert(sessionListenerCallbacks->stageStarting != nullptr);
    assert(sessionListenerCallbacks->stageComplete != nullptr);
    assert(sessionListenerCallbacks->stageFailed != nullptr);
    assert(sessionListenerCallbacks->connectionStarted != nullptr);
    assert(sessionListenerCallbacks->connectionTerminated != nullptr);
    assert(sessionListenerCallbacks->rumble != nullptr);
    assert(sessionListenerCallbacks->setMotionEventState != nullptr);
    assert(sessionListenerCallbacks->setControllerLED != nullptr);

    const auto prepared = session.prepare(validRequest());
    assert(prepared.state == DeckStreamSessionState::Preparing);
    assert(!prepared.networkStarted);
    assert(session.state() == DeckStreamSessionState::Preparing);

    assert(sessionVideoCallbacks->setup(VIDEO_FORMAT_H264, 1280, 800, 60, callbackContext, 0) == 0);
    sessionVideoCallbacks->start();
    assert(sessionVideoCallbacks->submitDecodeUnit(nullptr) == DR_OK);
    sessionVideoCallbacks->stop();
    sessionVideoCallbacks->cleanup();
    assert(renderer.setupCalls.size() == 1);
    assert(renderer.setupCalls[0].width == 1280);
    assert((renderer.contexts == std::vector<void*>{callbackContext}));
    assert(renderer.startCalls == 1);
    assert(renderer.stopCalls == 1);
    assert(renderer.cleanupCalls == 1);
    assert(renderer.decodeUnits == std::vector<PDECODE_UNIT>{nullptr});

    OPUS_MULTISTREAM_CONFIGURATION opusConfig{};
    assert(sessionAudioCallbacks->init(AUDIO_CONFIGURATION_STEREO, &opusConfig, callbackContext, 0) == 0);
    sessionAudioCallbacks->start();
    char sample[] = {'n', 'o', 'v', 'a'};
    sessionAudioCallbacks->decodeAndPlaySample(sample, 4);
    sessionAudioCallbacks->stop();
    sessionAudioCallbacks->cleanup();
    assert(audio.initCalls.size() == 1);
    assert(audio.initCalls[0].audioConfiguration == AUDIO_CONFIGURATION_STEREO);
    assert((audio.opusConfigs == std::vector<POPUS_MULTISTREAM_CONFIGURATION>{&opusConfig}));
    assert((audio.contexts == std::vector<void*>{callbackContext}));
    assert(audio.startCalls == 1);
    assert(audio.stopCalls == 1);
    assert(audio.cleanupCalls == 1);
    assert(audio.samples == std::vector<char*>{sample});
    assert(audio.sampleLengths == std::vector<int>{4});

    sessionListenerCallbacks->stageStarting(STAGE_VIDEO_STREAM_INIT);
    sessionListenerCallbacks->stageComplete(STAGE_VIDEO_STREAM_INIT);
    sessionListenerCallbacks->connectionStarted();
    sessionListenerCallbacks->rumble(0, 100, 200);
    sessionListenerCallbacks->setMotionEventState(0, 1, 120);
    sessionListenerCallbacks->setControllerLED(0, 8, 16, 32);
    sessionListenerCallbacks->connectionTerminated(ML_ERROR_GRACEFUL_TERMINATION);
    assert((input.rumbles == std::vector<InputCall>{InputCall{0, 100, 200, 0}}));
    assert((input.motionStates == std::vector<InputCall>{InputCall{0, 1, 120, 0}}));
    assert((input.leds == std::vector<InputCall>{InputCall{0, 8, 16, 32}}));

    const auto started = session.startNoNetwork();
    assert(started.state == DeckStreamSessionState::Active);
    assert(!started.networkStarted);
    assert(session.state() == DeckStreamSessionState::Active);

    const auto stopped = session.stop();
    assert(stopped.state == DeckStreamSessionState::Stopped);
    assert(session.state() == DeckStreamSessionState::Stopped);

    const std::vector<DeckStreamSessionState> expectedMainSequence{
        DeckStreamSessionState::Preparing,
        DeckStreamSessionState::Preparing,
        DeckStreamSessionState::Preparing,
        DeckStreamSessionState::Preparing,
        DeckStreamSessionState::Preparing,
        DeckStreamSessionState::Starting,
        DeckStreamSessionState::Active,
        DeckStreamSessionState::Stopping,
        DeckStreamSessionState::Stopped,
    };
    assert(events.states == expectedMainSequence);

    RecordingEvents invalidStartEvents;
    DeckStreamSession invalidStart(renderer, audio, input, invalidStartEvents);
    const auto startBeforePrepare = invalidStart.startNoNetwork();
    assert(startBeforePrepare.state == DeckStreamSessionState::Failed);
    assert(startBeforePrepare.reason == "start requested before prepare");
    assert(invalidStart.state() == DeckStreamSessionState::Failed);
    assert(invalidStartEvents.states == std::vector<DeckStreamSessionState>{DeckStreamSessionState::Failed});

    RecordingEvents invalidStopEvents;
    DeckStreamSession invalidStop(renderer, audio, input, invalidStopEvents);
    const auto stopBeforeStart = invalidStop.stop();
    assert(stopBeforeStart.state == DeckStreamSessionState::Failed);
    assert(stopBeforeStart.reason == "stop requested before active stream");
    assert(invalidStop.state() == DeckStreamSessionState::Failed);
    assert(invalidStopEvents.states == std::vector<DeckStreamSessionState>{DeckStreamSessionState::Failed});

    RecordingEvents invalidPrepareEvents;
    DeckStreamSession invalidPrepare(renderer, audio, input, invalidPrepareEvents);
    auto invalidRequest = validRequest("game-invalid");
    invalidRequest.width = 0;
    const auto rejectedPrepare = invalidPrepare.prepare(invalidRequest);
    assert(rejectedPrepare.state == DeckStreamSessionState::Failed);
    assert(rejectedPrepare.reason == "invalid stream request dimensions or bitrate");
    assert(invalidPrepare.state() == DeckStreamSessionState::Failed);

    RecordingEvents cancellableEvents;
    DeckStreamSession cancellable(renderer, audio, input, cancellableEvents);
    cancellable.prepare(validRequest("game-456"));
    const auto cancelled = cancellable.cancel("user backed out before network start");
    assert(cancelled.state == DeckStreamSessionState::Cancelled);
    assert(cancelled.reason == "user backed out before network start");
    assert(cancellable.state() == DeckStreamSessionState::Cancelled);

    RecordingEvents failingEvents;
    DeckStreamSession failing(renderer, audio, input, failingEvents);
    const auto failed = failing.fail("renderer adapter unavailable");
    assert(failed.state == DeckStreamSessionState::Failed);
    assert(failed.reason == "renderer adapter unavailable");
    assert(failing.state() == DeckStreamSessionState::Failed);

    StubRenderer ownerARenderer;
    StubAudio ownerAAudio;
    StubInput ownerAInput;
    RecordingEvents ownerAEvents;
    DeckStreamSession ownerA(ownerARenderer, ownerAAudio, ownerAInput, ownerAEvents);
    ownerA.prepare(validRequest("game-owner-a"));
    auto* ownerAVideoStart = ownerA.moonlightBoundary().videoCallbacks->start;
    auto* ownerAAudioStart = ownerA.moonlightBoundary().audioCallbacks->start;
    auto* ownerARumble = ownerA.moonlightBoundary().listenerCallbacks->rumble;
    StubRenderer ownerBRenderer;
    StubAudio ownerBAudio;
    StubInput ownerBInput;
    RecordingEvents ownerBEvents;
    DeckStreamSession ownerB(ownerBRenderer, ownerBAudio, ownerBInput, ownerBEvents);
    const auto ownerBPrepare = ownerB.prepare(validRequest("game-owner-b"));
    assert(ownerBPrepare.state == DeckStreamSessionState::Failed);
    assert(ownerBPrepare.reason == "another stream callback owner is active");
    ownerAVideoStart();
    ownerAAudioStart();
    ownerARumble(2, 300, 400);
    assert(ownerARenderer.startCalls == 1);
    assert(ownerAAudio.startCalls == 1);
    assert((ownerAInput.rumbles == std::vector<InputCall>{InputCall{2, 300, 400, 0}}));
    assert(ownerBRenderer.startCalls == 0);
    assert(ownerBAudio.startCalls == 0);
    assert(ownerBInput.rumbles.empty());
    ownerA.cancel("owner lifetime probe complete");

    StubRenderer staleRenderer;
    StubAudio staleAudio;
    StubInput staleInput;
    RecordingEvents staleEvents;
    DecoderRendererSetup staleVideoSetup = nullptr;
    DecoderRendererStart staleVideoStart = nullptr;
    AudioRendererInit staleAudioInit = nullptr;
    AudioRendererStart staleAudioStart = nullptr;
    ConnListenerRumble staleRumble = nullptr;
    void* staleContext = nullptr;
    {
        DeckStreamSession scoped(staleRenderer, staleAudio, staleInput, staleEvents);
        scoped.prepare(validRequest("game-stale"));
        staleVideoSetup = scoped.moonlightBoundary().videoCallbacks->setup;
        staleVideoStart = scoped.moonlightBoundary().videoCallbacks->start;
        staleAudioInit = scoped.moonlightBoundary().audioCallbacks->init;
        staleAudioStart = scoped.moonlightBoundary().audioCallbacks->start;
        staleRumble = scoped.moonlightBoundary().listenerCallbacks->rumble;
        staleContext = scoped.moonlightBoundary().callbackContext;
    }
    assert(staleVideoSetup(VIDEO_FORMAT_H264, 1280, 800, 60, staleContext, 0) == DR_NEED_IDR);
    OPUS_MULTISTREAM_CONFIGURATION staleOpusConfig{};
    assert(staleAudioInit(AUDIO_CONFIGURATION_STEREO, &staleOpusConfig, staleContext, 0) == -1);
    staleVideoStart();
    staleAudioStart();
    staleRumble(1, 2, 3);
    assert(staleRenderer.startCalls == 0);
    assert(staleAudio.startCalls == 0);
    assert(staleInput.rumbles.empty());

    StubRenderer laterRenderer;
    StubAudio laterAudio;
    StubInput laterInput;
    RecordingEvents laterEvents;
    DeckStreamSession later(laterRenderer, laterAudio, laterInput, laterEvents);
    later.prepare(validRequest("game-later"));
    assert(staleVideoSetup(VIDEO_FORMAT_H264, 1280, 800, 60, staleContext, 0) == DR_NEED_IDR);
    assert(staleAudioInit(AUDIO_CONFIGURATION_STEREO, &staleOpusConfig, staleContext, 0) == -1);
    later.cancel("later owner release");

    for (int i = 0; i < 24; ++i) {
        StubRenderer sequentialRenderer;
        StubAudio sequentialAudio;
        StubInput sequentialInput;
        RecordingEvents sequentialEvents;
        DeckStreamSession sequential(sequentialRenderer, sequentialAudio, sequentialInput, sequentialEvents);
        const auto preparedSequential = sequential.prepare(validRequest("game-sequential-" + std::to_string(i)));
        assert(preparedSequential.state == DeckStreamSessionState::Preparing);
        sequential.cancel("sequential owner release");
    }

    return 0;
}
