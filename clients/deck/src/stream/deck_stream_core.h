#pragma once

#include <Limelight.h>

#include <cstdint>
#include <cstddef>
#include <string>
#include <string_view>

namespace nova::deck::stream {

enum class DeckStreamSessionState {
    Idle,
    Preparing,
    Starting,
    Active,
    Failed,
    Stopping,
    Stopped,
    Cancelled,
};

struct DeckStreamRequest {
    std::string hostId;
    std::string gameId;
    int width = 1280;
    int height = 800;
    int fps = 60;
    int bitrateKbps = 20000;
};

struct DeckStreamTransition {
    DeckStreamSessionState state = DeckStreamSessionState::Idle;
    std::string reason;
    bool networkStarted = false;
};

struct DeckMoonlightBoundary {
    const CONNECTION_LISTENER_CALLBACKS* listenerCallbacks = nullptr;
    const DECODER_RENDERER_CALLBACKS* videoCallbacks = nullptr;
    const AUDIO_RENDERER_CALLBACKS* audioCallbacks = nullptr;
    const STREAM_CONFIGURATION* streamConfig = nullptr;
    void* callbackContext = nullptr;
    bool networkStartAllowed = false;
};

class DeckStreamRenderer {
public:
    virtual ~DeckStreamRenderer() = default;
    virtual std::string_view adapterName() const = 0;
    virtual int setup(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) = 0;
    virtual void start() = 0;
    virtual void stop() = 0;
    virtual void cleanup() = 0;
    virtual int submitDecodeUnit(PDECODE_UNIT decodeUnit) = 0;
};

class DeckStreamAudio {
public:
    virtual ~DeckStreamAudio() = default;
    virtual std::string_view adapterName() const = 0;
    virtual int init(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) = 0;
    virtual void start() = 0;
    virtual void stop() = 0;
    virtual void cleanup() = 0;
    virtual void decodeAndPlaySample(char* sampleData, int sampleLength) = 0;
};

class DeckStreamInput {
public:
    virtual ~DeckStreamInput() = default;
    virtual std::string_view adapterName() const = 0;
    virtual void rumble(uint16_t controllerNumber, uint16_t lowFreqMotor, uint16_t highFreqMotor) = 0;
    virtual void setMotionEventState(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) = 0;
    virtual void setControllerLed(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) = 0;
};

class DeckStreamSessionEvents {
public:
    virtual ~DeckStreamSessionEvents() = default;
    virtual void onSessionEvent(DeckStreamSessionState state, std::string_view reason) = 0;
};

class DeckStreamSession {
public:
    DeckStreamSession(
        DeckStreamRenderer& renderer,
        DeckStreamAudio& audio,
        DeckStreamInput& input,
        DeckStreamSessionEvents& events);
    ~DeckStreamSession();
    DeckStreamSession(const DeckStreamSession&) = delete;
    DeckStreamSession& operator=(const DeckStreamSession&) = delete;
    DeckStreamSession(DeckStreamSession&&) = delete;
    DeckStreamSession& operator=(DeckStreamSession&&) = delete;

    DeckStreamSessionState state() const;
    const DeckMoonlightBoundary& moonlightBoundary() const;

    DeckStreamTransition prepare(const DeckStreamRequest& request);
    DeckStreamTransition startNoNetwork();
    DeckStreamTransition stop();
    DeckStreamTransition cancel(std::string_view reason);
    DeckStreamTransition fail(std::string_view reason);

private:
    static constexpr std::size_t kInvalidCallbackSlot = static_cast<std::size_t>(-1);

    static DeckStreamSession* ownerFromContext(void* context);
    static void* contextForSlot(std::size_t slot);
    static DeckStreamSession* ownerForSlot(std::size_t slot);
    static bool hasActiveOwnerOtherThan(const DeckStreamSession& session);
    static void setCallbackOwner(DeckStreamSession& session);
    static void clearCallbackOwner(DeckStreamSession& session);
    static int videoSetupForSlot(std::size_t slot, int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStartForSlot(std::size_t slot);
    static void videoStopForSlot(std::size_t slot);
    static void videoCleanupForSlot(std::size_t slot);
    static int videoSubmitDecodeUnitForSlot(std::size_t slot, PDECODE_UNIT decodeUnit);
    static int audioInitForSlot(std::size_t slot, int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStartForSlot(std::size_t slot);
    static void audioStopForSlot(std::size_t slot);
    static void audioCleanupForSlot(std::size_t slot);
    static void audioDecodeAndPlaySampleForSlot(std::size_t slot, char* sampleData, int sampleLength);
    static void listenerStageStartingForSlot(std::size_t slot, int stage);
    static void listenerStageCompleteForSlot(std::size_t slot, int stage);
    static void listenerStageFailedForSlot(std::size_t slot, int stage, int errorCode);
    static void listenerConnectionStartedForSlot(std::size_t slot);
    static void listenerConnectionTerminatedForSlot(std::size_t slot, int errorCode);
    static void listenerRumbleForSlot(std::size_t slot, unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventStateForSlot(std::size_t slot, uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLedForSlot(std::size_t slot, uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    void installCallbackThunks();
    bool hasCallbackSlot() const;
    void noteSessionEvent(std::string_view reason);

    static int videoSetup0(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart0();
    static void videoStop0();
    static void videoCleanup0();
    static int videoSubmitDecodeUnit0(PDECODE_UNIT decodeUnit);
    static int audioInit0(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart0();
    static void audioStop0();
    static void audioCleanup0();
    static void audioDecodeAndPlaySample0(char* sampleData, int sampleLength);
    static void listenerStageStarting0(int stage);
    static void listenerStageComplete0(int stage);
    static void listenerStageFailed0(int stage, int errorCode);
    static void listenerConnectionStarted0();
    static void listenerConnectionTerminated0(int errorCode);
    static void listenerRumble0(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState0(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed0(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup1(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart1();
    static void videoStop1();
    static void videoCleanup1();
    static int videoSubmitDecodeUnit1(PDECODE_UNIT decodeUnit);
    static int audioInit1(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart1();
    static void audioStop1();
    static void audioCleanup1();
    static void audioDecodeAndPlaySample1(char* sampleData, int sampleLength);
    static void listenerStageStarting1(int stage);
    static void listenerStageComplete1(int stage);
    static void listenerStageFailed1(int stage, int errorCode);
    static void listenerConnectionStarted1();
    static void listenerConnectionTerminated1(int errorCode);
    static void listenerRumble1(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState1(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed1(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup2(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart2();
    static void videoStop2();
    static void videoCleanup2();
    static int videoSubmitDecodeUnit2(PDECODE_UNIT decodeUnit);
    static int audioInit2(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart2();
    static void audioStop2();
    static void audioCleanup2();
    static void audioDecodeAndPlaySample2(char* sampleData, int sampleLength);
    static void listenerStageStarting2(int stage);
    static void listenerStageComplete2(int stage);
    static void listenerStageFailed2(int stage, int errorCode);
    static void listenerConnectionStarted2();
    static void listenerConnectionTerminated2(int errorCode);
    static void listenerRumble2(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState2(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed2(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup3(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart3();
    static void videoStop3();
    static void videoCleanup3();
    static int videoSubmitDecodeUnit3(PDECODE_UNIT decodeUnit);
    static int audioInit3(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart3();
    static void audioStop3();
    static void audioCleanup3();
    static void audioDecodeAndPlaySample3(char* sampleData, int sampleLength);
    static void listenerStageStarting3(int stage);
    static void listenerStageComplete3(int stage);
    static void listenerStageFailed3(int stage, int errorCode);
    static void listenerConnectionStarted3();
    static void listenerConnectionTerminated3(int errorCode);
    static void listenerRumble3(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState3(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed3(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup4(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart4();
    static void videoStop4();
    static void videoCleanup4();
    static int videoSubmitDecodeUnit4(PDECODE_UNIT decodeUnit);
    static int audioInit4(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart4();
    static void audioStop4();
    static void audioCleanup4();
    static void audioDecodeAndPlaySample4(char* sampleData, int sampleLength);
    static void listenerStageStarting4(int stage);
    static void listenerStageComplete4(int stage);
    static void listenerStageFailed4(int stage, int errorCode);
    static void listenerConnectionStarted4();
    static void listenerConnectionTerminated4(int errorCode);
    static void listenerRumble4(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState4(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed4(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup5(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart5();
    static void videoStop5();
    static void videoCleanup5();
    static int videoSubmitDecodeUnit5(PDECODE_UNIT decodeUnit);
    static int audioInit5(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart5();
    static void audioStop5();
    static void audioCleanup5();
    static void audioDecodeAndPlaySample5(char* sampleData, int sampleLength);
    static void listenerStageStarting5(int stage);
    static void listenerStageComplete5(int stage);
    static void listenerStageFailed5(int stage, int errorCode);
    static void listenerConnectionStarted5();
    static void listenerConnectionTerminated5(int errorCode);
    static void listenerRumble5(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState5(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed5(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup6(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart6();
    static void videoStop6();
    static void videoCleanup6();
    static int videoSubmitDecodeUnit6(PDECODE_UNIT decodeUnit);
    static int audioInit6(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart6();
    static void audioStop6();
    static void audioCleanup6();
    static void audioDecodeAndPlaySample6(char* sampleData, int sampleLength);
    static void listenerStageStarting6(int stage);
    static void listenerStageComplete6(int stage);
    static void listenerStageFailed6(int stage, int errorCode);
    static void listenerConnectionStarted6();
    static void listenerConnectionTerminated6(int errorCode);
    static void listenerRumble6(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState6(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed6(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup7(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart7();
    static void videoStop7();
    static void videoCleanup7();
    static int videoSubmitDecodeUnit7(PDECODE_UNIT decodeUnit);
    static int audioInit7(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart7();
    static void audioStop7();
    static void audioCleanup7();
    static void audioDecodeAndPlaySample7(char* sampleData, int sampleLength);
    static void listenerStageStarting7(int stage);
    static void listenerStageComplete7(int stage);
    static void listenerStageFailed7(int stage, int errorCode);
    static void listenerConnectionStarted7();
    static void listenerConnectionTerminated7(int errorCode);
    static void listenerRumble7(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState7(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed7(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup8(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart8();
    static void videoStop8();
    static void videoCleanup8();
    static int videoSubmitDecodeUnit8(PDECODE_UNIT decodeUnit);
    static int audioInit8(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart8();
    static void audioStop8();
    static void audioCleanup8();
    static void audioDecodeAndPlaySample8(char* sampleData, int sampleLength);
    static void listenerStageStarting8(int stage);
    static void listenerStageComplete8(int stage);
    static void listenerStageFailed8(int stage, int errorCode);
    static void listenerConnectionStarted8();
    static void listenerConnectionTerminated8(int errorCode);
    static void listenerRumble8(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState8(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed8(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup9(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart9();
    static void videoStop9();
    static void videoCleanup9();
    static int videoSubmitDecodeUnit9(PDECODE_UNIT decodeUnit);
    static int audioInit9(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart9();
    static void audioStop9();
    static void audioCleanup9();
    static void audioDecodeAndPlaySample9(char* sampleData, int sampleLength);
    static void listenerStageStarting9(int stage);
    static void listenerStageComplete9(int stage);
    static void listenerStageFailed9(int stage, int errorCode);
    static void listenerConnectionStarted9();
    static void listenerConnectionTerminated9(int errorCode);
    static void listenerRumble9(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState9(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed9(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup10(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart10();
    static void videoStop10();
    static void videoCleanup10();
    static int videoSubmitDecodeUnit10(PDECODE_UNIT decodeUnit);
    static int audioInit10(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart10();
    static void audioStop10();
    static void audioCleanup10();
    static void audioDecodeAndPlaySample10(char* sampleData, int sampleLength);
    static void listenerStageStarting10(int stage);
    static void listenerStageComplete10(int stage);
    static void listenerStageFailed10(int stage, int errorCode);
    static void listenerConnectionStarted10();
    static void listenerConnectionTerminated10(int errorCode);
    static void listenerRumble10(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState10(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed10(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup11(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart11();
    static void videoStop11();
    static void videoCleanup11();
    static int videoSubmitDecodeUnit11(PDECODE_UNIT decodeUnit);
    static int audioInit11(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart11();
    static void audioStop11();
    static void audioCleanup11();
    static void audioDecodeAndPlaySample11(char* sampleData, int sampleLength);
    static void listenerStageStarting11(int stage);
    static void listenerStageComplete11(int stage);
    static void listenerStageFailed11(int stage, int errorCode);
    static void listenerConnectionStarted11();
    static void listenerConnectionTerminated11(int errorCode);
    static void listenerRumble11(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState11(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed11(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup12(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart12();
    static void videoStop12();
    static void videoCleanup12();
    static int videoSubmitDecodeUnit12(PDECODE_UNIT decodeUnit);
    static int audioInit12(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart12();
    static void audioStop12();
    static void audioCleanup12();
    static void audioDecodeAndPlaySample12(char* sampleData, int sampleLength);
    static void listenerStageStarting12(int stage);
    static void listenerStageComplete12(int stage);
    static void listenerStageFailed12(int stage, int errorCode);
    static void listenerConnectionStarted12();
    static void listenerConnectionTerminated12(int errorCode);
    static void listenerRumble12(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState12(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed12(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup13(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart13();
    static void videoStop13();
    static void videoCleanup13();
    static int videoSubmitDecodeUnit13(PDECODE_UNIT decodeUnit);
    static int audioInit13(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart13();
    static void audioStop13();
    static void audioCleanup13();
    static void audioDecodeAndPlaySample13(char* sampleData, int sampleLength);
    static void listenerStageStarting13(int stage);
    static void listenerStageComplete13(int stage);
    static void listenerStageFailed13(int stage, int errorCode);
    static void listenerConnectionStarted13();
    static void listenerConnectionTerminated13(int errorCode);
    static void listenerRumble13(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState13(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed13(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup14(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart14();
    static void videoStop14();
    static void videoCleanup14();
    static int videoSubmitDecodeUnit14(PDECODE_UNIT decodeUnit);
    static int audioInit14(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart14();
    static void audioStop14();
    static void audioCleanup14();
    static void audioDecodeAndPlaySample14(char* sampleData, int sampleLength);
    static void listenerStageStarting14(int stage);
    static void listenerStageComplete14(int stage);
    static void listenerStageFailed14(int stage, int errorCode);
    static void listenerConnectionStarted14();
    static void listenerConnectionTerminated14(int errorCode);
    static void listenerRumble14(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState14(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed14(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    static int videoSetup15(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags);
    static void videoStart15();
    static void videoStop15();
    static void videoCleanup15();
    static int videoSubmitDecodeUnit15(PDECODE_UNIT decodeUnit);
    static int audioInit15(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags);
    static void audioStart15();
    static void audioStop15();
    static void audioCleanup15();
    static void audioDecodeAndPlaySample15(char* sampleData, int sampleLength);
    static void listenerStageStarting15(int stage);
    static void listenerStageComplete15(int stage);
    static void listenerStageFailed15(int stage, int errorCode);
    static void listenerConnectionStarted15();
    static void listenerConnectionTerminated15(int errorCode);
    static void listenerRumble15(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor);
    static void listenerSetMotionEventState15(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz);
    static void listenerSetControllerLed15(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b);
    DeckStreamTransition transitionTo(DeckStreamSessionState state, std::string_view reason, bool networkStarted = false);

    DeckStreamRenderer& renderer_;
    DeckStreamAudio& audio_;
    DeckStreamInput& input_;
    DeckStreamSessionEvents& events_;
    DeckStreamSessionState state_ = DeckStreamSessionState::Idle;
    DeckStreamRequest request_;
    std::size_t callbackSlot_ = kInvalidCallbackSlot;
    void* callbackContext_ = nullptr;
    STREAM_CONFIGURATION streamConfig_{};
    CONNECTION_LISTENER_CALLBACKS listenerCallbacks_{};
    DECODER_RENDERER_CALLBACKS videoCallbacks_{};
    AUDIO_RENDERER_CALLBACKS audioCallbacks_{};
    DeckMoonlightBoundary moonlightBoundary_{};
};

} // namespace nova::deck::stream
