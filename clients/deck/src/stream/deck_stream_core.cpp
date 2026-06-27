#include "stream/deck_stream_core.h"

namespace nova::deck::stream {

namespace {

constexpr std::size_t kMaxCallbackSlots = 16;
DeckStreamSession* callbackOwners[kMaxCallbackSlots] = {};
bool callbackSlotReserved[kMaxCallbackSlots] = {};
std::uintptr_t nextCallbackToken = 1;

std::size_t reserveCallbackSlot() {
    for (std::size_t slot = 0; slot < kMaxCallbackSlots; ++slot) {
        if (!callbackSlotReserved[slot]) {
            callbackSlotReserved[slot] = true;
            return slot;
        }
    }
    return kMaxCallbackSlots;
}

void releaseCallbackSlot(const std::size_t slot) {
    if (slot < kMaxCallbackSlots) {
        callbackOwners[slot] = nullptr;
        callbackSlotReserved[slot] = false;
    }
}

void* nextOpaqueCallbackContext() {
    const auto token = nextCallbackToken++;
    if (nextCallbackToken == 0) {
        nextCallbackToken = 1;
    }
    return reinterpret_cast<void*>(token);
}

bool isValidStreamRequest(const DeckStreamRequest& request) {
    return request.width > 0 && request.height > 0 && request.fps > 0 && request.bitrateKbps > 0;
}

} // namespace

DeckStreamSession* DeckStreamSession::ownerFromContext(void* context) {
    if (context == nullptr) {
        return nullptr;
    }
    for (auto* owner : callbackOwners) {
        if (owner != nullptr && owner->callbackContext_ == context) {
            return owner;
        }
    }
    return nullptr;
}

void* DeckStreamSession::contextForSlot(const std::size_t slot) {
    if (slot >= kMaxCallbackSlots) {
        return nullptr;
    }
    return reinterpret_cast<void*>(slot + 1);
}

DeckStreamSession* DeckStreamSession::ownerForSlot(const std::size_t slot) {
    if (slot >= kMaxCallbackSlots) {
        return nullptr;
    }
    return callbackOwners[slot];
}

bool DeckStreamSession::hasActiveOwnerOtherThan(const DeckStreamSession& session) {
    for (auto* owner : callbackOwners) {
        if (owner != nullptr && owner != &session) {
            return true;
        }
    }
    return false;
}

void DeckStreamSession::setCallbackOwner(DeckStreamSession& session) {
    if (session.hasCallbackSlot()) {
        callbackOwners[session.callbackSlot_] = &session;
    }
}

void DeckStreamSession::clearCallbackOwner(DeckStreamSession& session) {
    if (session.hasCallbackSlot() && callbackOwners[session.callbackSlot_] == &session) {
        callbackOwners[session.callbackSlot_] = nullptr;
    }
}

int DeckStreamSession::videoSetupForSlot(const std::size_t slot, const int videoFormat, const int width, const int height, const int redrawRate, void* context, const int drFlags) {
    auto* owner = ownerFromContext(context);
    if (owner == nullptr || owner != ownerForSlot(slot)) {
        return DR_NEED_IDR;
    }
    return owner->renderer_.setup(videoFormat, width, height, redrawRate, context, drFlags);
}

void DeckStreamSession::videoStartForSlot(const std::size_t slot) { if (auto* owner = ownerForSlot(slot)) { owner->renderer_.start(); } }
void DeckStreamSession::videoStopForSlot(const std::size_t slot) { if (auto* owner = ownerForSlot(slot)) { owner->renderer_.stop(); } }
void DeckStreamSession::videoCleanupForSlot(const std::size_t slot) { if (auto* owner = ownerForSlot(slot)) { owner->renderer_.cleanup(); } }
int DeckStreamSession::videoSubmitDecodeUnitForSlot(const std::size_t slot, PDECODE_UNIT decodeUnit) { if (auto* owner = ownerForSlot(slot)) { return owner->renderer_.submitDecodeUnit(decodeUnit); } return DR_NEED_IDR; }

int DeckStreamSession::audioInitForSlot(const std::size_t slot, const int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, const int arFlags) {
    auto* owner = ownerFromContext(context);
    if (owner == nullptr || owner != ownerForSlot(slot)) {
        return -1;
    }
    return owner->audio_.init(audioConfiguration, opusConfig, context, arFlags);
}

void DeckStreamSession::audioStartForSlot(const std::size_t slot) { if (auto* owner = ownerForSlot(slot)) { owner->audio_.start(); } }
void DeckStreamSession::audioStopForSlot(const std::size_t slot) { if (auto* owner = ownerForSlot(slot)) { owner->audio_.stop(); } }
void DeckStreamSession::audioCleanupForSlot(const std::size_t slot) { if (auto* owner = ownerForSlot(slot)) { owner->audio_.cleanup(); } }
void DeckStreamSession::audioDecodeAndPlaySampleForSlot(const std::size_t slot, char* sampleData, const int sampleLength) { if (auto* owner = ownerForSlot(slot)) { owner->audio_.decodeAndPlaySample(sampleData, sampleLength); } }

void DeckStreamSession::listenerStageStartingForSlot(const std::size_t slot, const int stage) { (void)stage; if (auto* owner = ownerForSlot(slot)) { owner->noteSessionEvent("moonlight stage starting"); } }
void DeckStreamSession::listenerStageCompleteForSlot(const std::size_t slot, const int stage) { (void)stage; if (auto* owner = ownerForSlot(slot)) { owner->noteSessionEvent("moonlight stage complete"); } }
void DeckStreamSession::listenerStageFailedForSlot(const std::size_t slot, const int stage, const int errorCode) { (void)stage; (void)errorCode; if (auto* owner = ownerForSlot(slot)) { owner->noteSessionEvent("moonlight stage failed"); } }
void DeckStreamSession::listenerConnectionStartedForSlot(const std::size_t slot) { if (auto* owner = ownerForSlot(slot)) { owner->noteSessionEvent("moonlight connection started callback received in no-network adapter"); } }
void DeckStreamSession::listenerConnectionTerminatedForSlot(const std::size_t slot, const int errorCode) { (void)errorCode; if (auto* owner = ownerForSlot(slot)) { owner->noteSessionEvent("moonlight connection terminated callback received in no-network adapter"); } }
void DeckStreamSession::listenerRumbleForSlot(const std::size_t slot, const unsigned short controllerNumber, const unsigned short lowFreqMotor, const unsigned short highFreqMotor) { if (auto* owner = ownerForSlot(slot)) { owner->input_.rumble(controllerNumber, lowFreqMotor, highFreqMotor); } }
void DeckStreamSession::listenerSetMotionEventStateForSlot(const std::size_t slot, const uint16_t controllerNumber, const uint8_t motionType, const uint16_t reportRateHz) { if (auto* owner = ownerForSlot(slot)) { owner->input_.setMotionEventState(controllerNumber, motionType, reportRateHz); } }
void DeckStreamSession::listenerSetControllerLedForSlot(const std::size_t slot, const uint16_t controllerNumber, const uint8_t r, const uint8_t g, const uint8_t b) { if (auto* owner = ownerForSlot(slot)) { owner->input_.setControllerLed(controllerNumber, r, g, b); } }

int DeckStreamSession::videoSetup0(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(0, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart0() { videoStartForSlot(0); }
void DeckStreamSession::videoStop0() { videoStopForSlot(0); }
void DeckStreamSession::videoCleanup0() { videoCleanupForSlot(0); }
int DeckStreamSession::videoSubmitDecodeUnit0(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(0, decodeUnit); }
int DeckStreamSession::audioInit0(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(0, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart0() { audioStartForSlot(0); }
void DeckStreamSession::audioStop0() { audioStopForSlot(0); }
void DeckStreamSession::audioCleanup0() { audioCleanupForSlot(0); }
void DeckStreamSession::audioDecodeAndPlaySample0(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(0, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting0(int stage) { listenerStageStartingForSlot(0, stage); }
void DeckStreamSession::listenerStageComplete0(int stage) { listenerStageCompleteForSlot(0, stage); }
void DeckStreamSession::listenerStageFailed0(int stage, int errorCode) { listenerStageFailedForSlot(0, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted0() { listenerConnectionStartedForSlot(0); }
void DeckStreamSession::listenerConnectionTerminated0(int errorCode) { listenerConnectionTerminatedForSlot(0, errorCode); }
void DeckStreamSession::listenerRumble0(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(0, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState0(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(0, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed0(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(0, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup1(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(1, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart1() { videoStartForSlot(1); }
void DeckStreamSession::videoStop1() { videoStopForSlot(1); }
void DeckStreamSession::videoCleanup1() { videoCleanupForSlot(1); }
int DeckStreamSession::videoSubmitDecodeUnit1(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(1, decodeUnit); }
int DeckStreamSession::audioInit1(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(1, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart1() { audioStartForSlot(1); }
void DeckStreamSession::audioStop1() { audioStopForSlot(1); }
void DeckStreamSession::audioCleanup1() { audioCleanupForSlot(1); }
void DeckStreamSession::audioDecodeAndPlaySample1(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(1, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting1(int stage) { listenerStageStartingForSlot(1, stage); }
void DeckStreamSession::listenerStageComplete1(int stage) { listenerStageCompleteForSlot(1, stage); }
void DeckStreamSession::listenerStageFailed1(int stage, int errorCode) { listenerStageFailedForSlot(1, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted1() { listenerConnectionStartedForSlot(1); }
void DeckStreamSession::listenerConnectionTerminated1(int errorCode) { listenerConnectionTerminatedForSlot(1, errorCode); }
void DeckStreamSession::listenerRumble1(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(1, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState1(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(1, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed1(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(1, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup2(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(2, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart2() { videoStartForSlot(2); }
void DeckStreamSession::videoStop2() { videoStopForSlot(2); }
void DeckStreamSession::videoCleanup2() { videoCleanupForSlot(2); }
int DeckStreamSession::videoSubmitDecodeUnit2(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(2, decodeUnit); }
int DeckStreamSession::audioInit2(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(2, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart2() { audioStartForSlot(2); }
void DeckStreamSession::audioStop2() { audioStopForSlot(2); }
void DeckStreamSession::audioCleanup2() { audioCleanupForSlot(2); }
void DeckStreamSession::audioDecodeAndPlaySample2(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(2, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting2(int stage) { listenerStageStartingForSlot(2, stage); }
void DeckStreamSession::listenerStageComplete2(int stage) { listenerStageCompleteForSlot(2, stage); }
void DeckStreamSession::listenerStageFailed2(int stage, int errorCode) { listenerStageFailedForSlot(2, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted2() { listenerConnectionStartedForSlot(2); }
void DeckStreamSession::listenerConnectionTerminated2(int errorCode) { listenerConnectionTerminatedForSlot(2, errorCode); }
void DeckStreamSession::listenerRumble2(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(2, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState2(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(2, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed2(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(2, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup3(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(3, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart3() { videoStartForSlot(3); }
void DeckStreamSession::videoStop3() { videoStopForSlot(3); }
void DeckStreamSession::videoCleanup3() { videoCleanupForSlot(3); }
int DeckStreamSession::videoSubmitDecodeUnit3(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(3, decodeUnit); }
int DeckStreamSession::audioInit3(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(3, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart3() { audioStartForSlot(3); }
void DeckStreamSession::audioStop3() { audioStopForSlot(3); }
void DeckStreamSession::audioCleanup3() { audioCleanupForSlot(3); }
void DeckStreamSession::audioDecodeAndPlaySample3(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(3, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting3(int stage) { listenerStageStartingForSlot(3, stage); }
void DeckStreamSession::listenerStageComplete3(int stage) { listenerStageCompleteForSlot(3, stage); }
void DeckStreamSession::listenerStageFailed3(int stage, int errorCode) { listenerStageFailedForSlot(3, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted3() { listenerConnectionStartedForSlot(3); }
void DeckStreamSession::listenerConnectionTerminated3(int errorCode) { listenerConnectionTerminatedForSlot(3, errorCode); }
void DeckStreamSession::listenerRumble3(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(3, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState3(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(3, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed3(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(3, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup4(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(4, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart4() { videoStartForSlot(4); }
void DeckStreamSession::videoStop4() { videoStopForSlot(4); }
void DeckStreamSession::videoCleanup4() { videoCleanupForSlot(4); }
int DeckStreamSession::videoSubmitDecodeUnit4(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(4, decodeUnit); }
int DeckStreamSession::audioInit4(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(4, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart4() { audioStartForSlot(4); }
void DeckStreamSession::audioStop4() { audioStopForSlot(4); }
void DeckStreamSession::audioCleanup4() { audioCleanupForSlot(4); }
void DeckStreamSession::audioDecodeAndPlaySample4(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(4, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting4(int stage) { listenerStageStartingForSlot(4, stage); }
void DeckStreamSession::listenerStageComplete4(int stage) { listenerStageCompleteForSlot(4, stage); }
void DeckStreamSession::listenerStageFailed4(int stage, int errorCode) { listenerStageFailedForSlot(4, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted4() { listenerConnectionStartedForSlot(4); }
void DeckStreamSession::listenerConnectionTerminated4(int errorCode) { listenerConnectionTerminatedForSlot(4, errorCode); }
void DeckStreamSession::listenerRumble4(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(4, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState4(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(4, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed4(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(4, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup5(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(5, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart5() { videoStartForSlot(5); }
void DeckStreamSession::videoStop5() { videoStopForSlot(5); }
void DeckStreamSession::videoCleanup5() { videoCleanupForSlot(5); }
int DeckStreamSession::videoSubmitDecodeUnit5(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(5, decodeUnit); }
int DeckStreamSession::audioInit5(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(5, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart5() { audioStartForSlot(5); }
void DeckStreamSession::audioStop5() { audioStopForSlot(5); }
void DeckStreamSession::audioCleanup5() { audioCleanupForSlot(5); }
void DeckStreamSession::audioDecodeAndPlaySample5(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(5, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting5(int stage) { listenerStageStartingForSlot(5, stage); }
void DeckStreamSession::listenerStageComplete5(int stage) { listenerStageCompleteForSlot(5, stage); }
void DeckStreamSession::listenerStageFailed5(int stage, int errorCode) { listenerStageFailedForSlot(5, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted5() { listenerConnectionStartedForSlot(5); }
void DeckStreamSession::listenerConnectionTerminated5(int errorCode) { listenerConnectionTerminatedForSlot(5, errorCode); }
void DeckStreamSession::listenerRumble5(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(5, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState5(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(5, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed5(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(5, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup6(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(6, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart6() { videoStartForSlot(6); }
void DeckStreamSession::videoStop6() { videoStopForSlot(6); }
void DeckStreamSession::videoCleanup6() { videoCleanupForSlot(6); }
int DeckStreamSession::videoSubmitDecodeUnit6(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(6, decodeUnit); }
int DeckStreamSession::audioInit6(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(6, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart6() { audioStartForSlot(6); }
void DeckStreamSession::audioStop6() { audioStopForSlot(6); }
void DeckStreamSession::audioCleanup6() { audioCleanupForSlot(6); }
void DeckStreamSession::audioDecodeAndPlaySample6(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(6, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting6(int stage) { listenerStageStartingForSlot(6, stage); }
void DeckStreamSession::listenerStageComplete6(int stage) { listenerStageCompleteForSlot(6, stage); }
void DeckStreamSession::listenerStageFailed6(int stage, int errorCode) { listenerStageFailedForSlot(6, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted6() { listenerConnectionStartedForSlot(6); }
void DeckStreamSession::listenerConnectionTerminated6(int errorCode) { listenerConnectionTerminatedForSlot(6, errorCode); }
void DeckStreamSession::listenerRumble6(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(6, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState6(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(6, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed6(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(6, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup7(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(7, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart7() { videoStartForSlot(7); }
void DeckStreamSession::videoStop7() { videoStopForSlot(7); }
void DeckStreamSession::videoCleanup7() { videoCleanupForSlot(7); }
int DeckStreamSession::videoSubmitDecodeUnit7(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(7, decodeUnit); }
int DeckStreamSession::audioInit7(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(7, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart7() { audioStartForSlot(7); }
void DeckStreamSession::audioStop7() { audioStopForSlot(7); }
void DeckStreamSession::audioCleanup7() { audioCleanupForSlot(7); }
void DeckStreamSession::audioDecodeAndPlaySample7(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(7, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting7(int stage) { listenerStageStartingForSlot(7, stage); }
void DeckStreamSession::listenerStageComplete7(int stage) { listenerStageCompleteForSlot(7, stage); }
void DeckStreamSession::listenerStageFailed7(int stage, int errorCode) { listenerStageFailedForSlot(7, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted7() { listenerConnectionStartedForSlot(7); }
void DeckStreamSession::listenerConnectionTerminated7(int errorCode) { listenerConnectionTerminatedForSlot(7, errorCode); }
void DeckStreamSession::listenerRumble7(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(7, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState7(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(7, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed7(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(7, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup8(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(8, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart8() { videoStartForSlot(8); }
void DeckStreamSession::videoStop8() { videoStopForSlot(8); }
void DeckStreamSession::videoCleanup8() { videoCleanupForSlot(8); }
int DeckStreamSession::videoSubmitDecodeUnit8(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(8, decodeUnit); }
int DeckStreamSession::audioInit8(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(8, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart8() { audioStartForSlot(8); }
void DeckStreamSession::audioStop8() { audioStopForSlot(8); }
void DeckStreamSession::audioCleanup8() { audioCleanupForSlot(8); }
void DeckStreamSession::audioDecodeAndPlaySample8(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(8, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting8(int stage) { listenerStageStartingForSlot(8, stage); }
void DeckStreamSession::listenerStageComplete8(int stage) { listenerStageCompleteForSlot(8, stage); }
void DeckStreamSession::listenerStageFailed8(int stage, int errorCode) { listenerStageFailedForSlot(8, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted8() { listenerConnectionStartedForSlot(8); }
void DeckStreamSession::listenerConnectionTerminated8(int errorCode) { listenerConnectionTerminatedForSlot(8, errorCode); }
void DeckStreamSession::listenerRumble8(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(8, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState8(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(8, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed8(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(8, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup9(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(9, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart9() { videoStartForSlot(9); }
void DeckStreamSession::videoStop9() { videoStopForSlot(9); }
void DeckStreamSession::videoCleanup9() { videoCleanupForSlot(9); }
int DeckStreamSession::videoSubmitDecodeUnit9(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(9, decodeUnit); }
int DeckStreamSession::audioInit9(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(9, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart9() { audioStartForSlot(9); }
void DeckStreamSession::audioStop9() { audioStopForSlot(9); }
void DeckStreamSession::audioCleanup9() { audioCleanupForSlot(9); }
void DeckStreamSession::audioDecodeAndPlaySample9(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(9, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting9(int stage) { listenerStageStartingForSlot(9, stage); }
void DeckStreamSession::listenerStageComplete9(int stage) { listenerStageCompleteForSlot(9, stage); }
void DeckStreamSession::listenerStageFailed9(int stage, int errorCode) { listenerStageFailedForSlot(9, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted9() { listenerConnectionStartedForSlot(9); }
void DeckStreamSession::listenerConnectionTerminated9(int errorCode) { listenerConnectionTerminatedForSlot(9, errorCode); }
void DeckStreamSession::listenerRumble9(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(9, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState9(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(9, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed9(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(9, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup10(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(10, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart10() { videoStartForSlot(10); }
void DeckStreamSession::videoStop10() { videoStopForSlot(10); }
void DeckStreamSession::videoCleanup10() { videoCleanupForSlot(10); }
int DeckStreamSession::videoSubmitDecodeUnit10(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(10, decodeUnit); }
int DeckStreamSession::audioInit10(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(10, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart10() { audioStartForSlot(10); }
void DeckStreamSession::audioStop10() { audioStopForSlot(10); }
void DeckStreamSession::audioCleanup10() { audioCleanupForSlot(10); }
void DeckStreamSession::audioDecodeAndPlaySample10(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(10, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting10(int stage) { listenerStageStartingForSlot(10, stage); }
void DeckStreamSession::listenerStageComplete10(int stage) { listenerStageCompleteForSlot(10, stage); }
void DeckStreamSession::listenerStageFailed10(int stage, int errorCode) { listenerStageFailedForSlot(10, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted10() { listenerConnectionStartedForSlot(10); }
void DeckStreamSession::listenerConnectionTerminated10(int errorCode) { listenerConnectionTerminatedForSlot(10, errorCode); }
void DeckStreamSession::listenerRumble10(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(10, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState10(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(10, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed10(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(10, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup11(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(11, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart11() { videoStartForSlot(11); }
void DeckStreamSession::videoStop11() { videoStopForSlot(11); }
void DeckStreamSession::videoCleanup11() { videoCleanupForSlot(11); }
int DeckStreamSession::videoSubmitDecodeUnit11(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(11, decodeUnit); }
int DeckStreamSession::audioInit11(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(11, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart11() { audioStartForSlot(11); }
void DeckStreamSession::audioStop11() { audioStopForSlot(11); }
void DeckStreamSession::audioCleanup11() { audioCleanupForSlot(11); }
void DeckStreamSession::audioDecodeAndPlaySample11(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(11, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting11(int stage) { listenerStageStartingForSlot(11, stage); }
void DeckStreamSession::listenerStageComplete11(int stage) { listenerStageCompleteForSlot(11, stage); }
void DeckStreamSession::listenerStageFailed11(int stage, int errorCode) { listenerStageFailedForSlot(11, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted11() { listenerConnectionStartedForSlot(11); }
void DeckStreamSession::listenerConnectionTerminated11(int errorCode) { listenerConnectionTerminatedForSlot(11, errorCode); }
void DeckStreamSession::listenerRumble11(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(11, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState11(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(11, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed11(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(11, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup12(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(12, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart12() { videoStartForSlot(12); }
void DeckStreamSession::videoStop12() { videoStopForSlot(12); }
void DeckStreamSession::videoCleanup12() { videoCleanupForSlot(12); }
int DeckStreamSession::videoSubmitDecodeUnit12(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(12, decodeUnit); }
int DeckStreamSession::audioInit12(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(12, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart12() { audioStartForSlot(12); }
void DeckStreamSession::audioStop12() { audioStopForSlot(12); }
void DeckStreamSession::audioCleanup12() { audioCleanupForSlot(12); }
void DeckStreamSession::audioDecodeAndPlaySample12(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(12, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting12(int stage) { listenerStageStartingForSlot(12, stage); }
void DeckStreamSession::listenerStageComplete12(int stage) { listenerStageCompleteForSlot(12, stage); }
void DeckStreamSession::listenerStageFailed12(int stage, int errorCode) { listenerStageFailedForSlot(12, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted12() { listenerConnectionStartedForSlot(12); }
void DeckStreamSession::listenerConnectionTerminated12(int errorCode) { listenerConnectionTerminatedForSlot(12, errorCode); }
void DeckStreamSession::listenerRumble12(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(12, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState12(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(12, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed12(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(12, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup13(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(13, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart13() { videoStartForSlot(13); }
void DeckStreamSession::videoStop13() { videoStopForSlot(13); }
void DeckStreamSession::videoCleanup13() { videoCleanupForSlot(13); }
int DeckStreamSession::videoSubmitDecodeUnit13(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(13, decodeUnit); }
int DeckStreamSession::audioInit13(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(13, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart13() { audioStartForSlot(13); }
void DeckStreamSession::audioStop13() { audioStopForSlot(13); }
void DeckStreamSession::audioCleanup13() { audioCleanupForSlot(13); }
void DeckStreamSession::audioDecodeAndPlaySample13(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(13, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting13(int stage) { listenerStageStartingForSlot(13, stage); }
void DeckStreamSession::listenerStageComplete13(int stage) { listenerStageCompleteForSlot(13, stage); }
void DeckStreamSession::listenerStageFailed13(int stage, int errorCode) { listenerStageFailedForSlot(13, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted13() { listenerConnectionStartedForSlot(13); }
void DeckStreamSession::listenerConnectionTerminated13(int errorCode) { listenerConnectionTerminatedForSlot(13, errorCode); }
void DeckStreamSession::listenerRumble13(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(13, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState13(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(13, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed13(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(13, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup14(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(14, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart14() { videoStartForSlot(14); }
void DeckStreamSession::videoStop14() { videoStopForSlot(14); }
void DeckStreamSession::videoCleanup14() { videoCleanupForSlot(14); }
int DeckStreamSession::videoSubmitDecodeUnit14(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(14, decodeUnit); }
int DeckStreamSession::audioInit14(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(14, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart14() { audioStartForSlot(14); }
void DeckStreamSession::audioStop14() { audioStopForSlot(14); }
void DeckStreamSession::audioCleanup14() { audioCleanupForSlot(14); }
void DeckStreamSession::audioDecodeAndPlaySample14(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(14, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting14(int stage) { listenerStageStartingForSlot(14, stage); }
void DeckStreamSession::listenerStageComplete14(int stage) { listenerStageCompleteForSlot(14, stage); }
void DeckStreamSession::listenerStageFailed14(int stage, int errorCode) { listenerStageFailedForSlot(14, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted14() { listenerConnectionStartedForSlot(14); }
void DeckStreamSession::listenerConnectionTerminated14(int errorCode) { listenerConnectionTerminatedForSlot(14, errorCode); }
void DeckStreamSession::listenerRumble14(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(14, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState14(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(14, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed14(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(14, controllerNumber, r, g, b); }
int DeckStreamSession::videoSetup15(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) { return videoSetupForSlot(15, videoFormat, width, height, redrawRate, context, drFlags); }
void DeckStreamSession::videoStart15() { videoStartForSlot(15); }
void DeckStreamSession::videoStop15() { videoStopForSlot(15); }
void DeckStreamSession::videoCleanup15() { videoCleanupForSlot(15); }
int DeckStreamSession::videoSubmitDecodeUnit15(PDECODE_UNIT decodeUnit) { return videoSubmitDecodeUnitForSlot(15, decodeUnit); }
int DeckStreamSession::audioInit15(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) { return audioInitForSlot(15, audioConfiguration, opusConfig, context, arFlags); }
void DeckStreamSession::audioStart15() { audioStartForSlot(15); }
void DeckStreamSession::audioStop15() { audioStopForSlot(15); }
void DeckStreamSession::audioCleanup15() { audioCleanupForSlot(15); }
void DeckStreamSession::audioDecodeAndPlaySample15(char* sampleData, int sampleLength) { audioDecodeAndPlaySampleForSlot(15, sampleData, sampleLength); }
void DeckStreamSession::listenerStageStarting15(int stage) { listenerStageStartingForSlot(15, stage); }
void DeckStreamSession::listenerStageComplete15(int stage) { listenerStageCompleteForSlot(15, stage); }
void DeckStreamSession::listenerStageFailed15(int stage, int errorCode) { listenerStageFailedForSlot(15, stage, errorCode); }
void DeckStreamSession::listenerConnectionStarted15() { listenerConnectionStartedForSlot(15); }
void DeckStreamSession::listenerConnectionTerminated15(int errorCode) { listenerConnectionTerminatedForSlot(15, errorCode); }
void DeckStreamSession::listenerRumble15(unsigned short controllerNumber, unsigned short lowFreqMotor, unsigned short highFreqMotor) { listenerRumbleForSlot(15, controllerNumber, lowFreqMotor, highFreqMotor); }
void DeckStreamSession::listenerSetMotionEventState15(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) { listenerSetMotionEventStateForSlot(15, controllerNumber, motionType, reportRateHz); }
void DeckStreamSession::listenerSetControllerLed15(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) { listenerSetControllerLedForSlot(15, controllerNumber, r, g, b); }

bool DeckStreamSession::hasCallbackSlot() const {
    return callbackSlot_ != kInvalidCallbackSlot && callbackSlot_ < kMaxCallbackSlots;
}

void DeckStreamSession::installCallbackThunks() {
    callbackSlot_ = reserveCallbackSlot();
    if (callbackSlot_ >= kMaxCallbackSlots) {
        callbackSlot_ = kInvalidCallbackSlot;
        return;
    }
    switch (callbackSlot_) {
    case 0:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup0;
        videoCallbacks_.start = &DeckStreamSession::videoStart0;
        videoCallbacks_.stop = &DeckStreamSession::videoStop0;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup0;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit0;
        audioCallbacks_.init = &DeckStreamSession::audioInit0;
        audioCallbacks_.start = &DeckStreamSession::audioStart0;
        audioCallbacks_.stop = &DeckStreamSession::audioStop0;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup0;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample0;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting0;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete0;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed0;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted0;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated0;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble0;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState0;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed0;
        break;
    case 1:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup1;
        videoCallbacks_.start = &DeckStreamSession::videoStart1;
        videoCallbacks_.stop = &DeckStreamSession::videoStop1;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup1;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit1;
        audioCallbacks_.init = &DeckStreamSession::audioInit1;
        audioCallbacks_.start = &DeckStreamSession::audioStart1;
        audioCallbacks_.stop = &DeckStreamSession::audioStop1;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup1;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample1;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting1;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete1;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed1;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted1;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated1;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble1;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState1;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed1;
        break;
    case 2:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup2;
        videoCallbacks_.start = &DeckStreamSession::videoStart2;
        videoCallbacks_.stop = &DeckStreamSession::videoStop2;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup2;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit2;
        audioCallbacks_.init = &DeckStreamSession::audioInit2;
        audioCallbacks_.start = &DeckStreamSession::audioStart2;
        audioCallbacks_.stop = &DeckStreamSession::audioStop2;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup2;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample2;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting2;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete2;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed2;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted2;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated2;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble2;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState2;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed2;
        break;
    case 3:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup3;
        videoCallbacks_.start = &DeckStreamSession::videoStart3;
        videoCallbacks_.stop = &DeckStreamSession::videoStop3;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup3;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit3;
        audioCallbacks_.init = &DeckStreamSession::audioInit3;
        audioCallbacks_.start = &DeckStreamSession::audioStart3;
        audioCallbacks_.stop = &DeckStreamSession::audioStop3;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup3;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample3;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting3;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete3;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed3;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted3;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated3;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble3;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState3;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed3;
        break;
    case 4:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup4;
        videoCallbacks_.start = &DeckStreamSession::videoStart4;
        videoCallbacks_.stop = &DeckStreamSession::videoStop4;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup4;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit4;
        audioCallbacks_.init = &DeckStreamSession::audioInit4;
        audioCallbacks_.start = &DeckStreamSession::audioStart4;
        audioCallbacks_.stop = &DeckStreamSession::audioStop4;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup4;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample4;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting4;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete4;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed4;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted4;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated4;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble4;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState4;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed4;
        break;
    case 5:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup5;
        videoCallbacks_.start = &DeckStreamSession::videoStart5;
        videoCallbacks_.stop = &DeckStreamSession::videoStop5;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup5;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit5;
        audioCallbacks_.init = &DeckStreamSession::audioInit5;
        audioCallbacks_.start = &DeckStreamSession::audioStart5;
        audioCallbacks_.stop = &DeckStreamSession::audioStop5;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup5;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample5;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting5;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete5;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed5;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted5;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated5;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble5;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState5;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed5;
        break;
    case 6:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup6;
        videoCallbacks_.start = &DeckStreamSession::videoStart6;
        videoCallbacks_.stop = &DeckStreamSession::videoStop6;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup6;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit6;
        audioCallbacks_.init = &DeckStreamSession::audioInit6;
        audioCallbacks_.start = &DeckStreamSession::audioStart6;
        audioCallbacks_.stop = &DeckStreamSession::audioStop6;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup6;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample6;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting6;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete6;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed6;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted6;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated6;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble6;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState6;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed6;
        break;
    case 7:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup7;
        videoCallbacks_.start = &DeckStreamSession::videoStart7;
        videoCallbacks_.stop = &DeckStreamSession::videoStop7;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup7;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit7;
        audioCallbacks_.init = &DeckStreamSession::audioInit7;
        audioCallbacks_.start = &DeckStreamSession::audioStart7;
        audioCallbacks_.stop = &DeckStreamSession::audioStop7;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup7;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample7;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting7;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete7;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed7;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted7;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated7;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble7;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState7;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed7;
        break;
    case 8:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup8;
        videoCallbacks_.start = &DeckStreamSession::videoStart8;
        videoCallbacks_.stop = &DeckStreamSession::videoStop8;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup8;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit8;
        audioCallbacks_.init = &DeckStreamSession::audioInit8;
        audioCallbacks_.start = &DeckStreamSession::audioStart8;
        audioCallbacks_.stop = &DeckStreamSession::audioStop8;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup8;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample8;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting8;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete8;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed8;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted8;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated8;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble8;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState8;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed8;
        break;
    case 9:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup9;
        videoCallbacks_.start = &DeckStreamSession::videoStart9;
        videoCallbacks_.stop = &DeckStreamSession::videoStop9;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup9;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit9;
        audioCallbacks_.init = &DeckStreamSession::audioInit9;
        audioCallbacks_.start = &DeckStreamSession::audioStart9;
        audioCallbacks_.stop = &DeckStreamSession::audioStop9;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup9;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample9;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting9;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete9;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed9;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted9;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated9;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble9;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState9;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed9;
        break;
    case 10:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup10;
        videoCallbacks_.start = &DeckStreamSession::videoStart10;
        videoCallbacks_.stop = &DeckStreamSession::videoStop10;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup10;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit10;
        audioCallbacks_.init = &DeckStreamSession::audioInit10;
        audioCallbacks_.start = &DeckStreamSession::audioStart10;
        audioCallbacks_.stop = &DeckStreamSession::audioStop10;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup10;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample10;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting10;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete10;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed10;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted10;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated10;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble10;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState10;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed10;
        break;
    case 11:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup11;
        videoCallbacks_.start = &DeckStreamSession::videoStart11;
        videoCallbacks_.stop = &DeckStreamSession::videoStop11;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup11;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit11;
        audioCallbacks_.init = &DeckStreamSession::audioInit11;
        audioCallbacks_.start = &DeckStreamSession::audioStart11;
        audioCallbacks_.stop = &DeckStreamSession::audioStop11;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup11;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample11;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting11;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete11;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed11;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted11;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated11;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble11;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState11;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed11;
        break;
    case 12:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup12;
        videoCallbacks_.start = &DeckStreamSession::videoStart12;
        videoCallbacks_.stop = &DeckStreamSession::videoStop12;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup12;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit12;
        audioCallbacks_.init = &DeckStreamSession::audioInit12;
        audioCallbacks_.start = &DeckStreamSession::audioStart12;
        audioCallbacks_.stop = &DeckStreamSession::audioStop12;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup12;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample12;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting12;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete12;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed12;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted12;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated12;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble12;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState12;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed12;
        break;
    case 13:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup13;
        videoCallbacks_.start = &DeckStreamSession::videoStart13;
        videoCallbacks_.stop = &DeckStreamSession::videoStop13;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup13;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit13;
        audioCallbacks_.init = &DeckStreamSession::audioInit13;
        audioCallbacks_.start = &DeckStreamSession::audioStart13;
        audioCallbacks_.stop = &DeckStreamSession::audioStop13;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup13;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample13;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting13;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete13;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed13;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted13;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated13;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble13;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState13;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed13;
        break;
    case 14:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup14;
        videoCallbacks_.start = &DeckStreamSession::videoStart14;
        videoCallbacks_.stop = &DeckStreamSession::videoStop14;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup14;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit14;
        audioCallbacks_.init = &DeckStreamSession::audioInit14;
        audioCallbacks_.start = &DeckStreamSession::audioStart14;
        audioCallbacks_.stop = &DeckStreamSession::audioStop14;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup14;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample14;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting14;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete14;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed14;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted14;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated14;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble14;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState14;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed14;
        break;
    case 15:
        videoCallbacks_.setup = &DeckStreamSession::videoSetup15;
        videoCallbacks_.start = &DeckStreamSession::videoStart15;
        videoCallbacks_.stop = &DeckStreamSession::videoStop15;
        videoCallbacks_.cleanup = &DeckStreamSession::videoCleanup15;
        videoCallbacks_.submitDecodeUnit = &DeckStreamSession::videoSubmitDecodeUnit15;
        audioCallbacks_.init = &DeckStreamSession::audioInit15;
        audioCallbacks_.start = &DeckStreamSession::audioStart15;
        audioCallbacks_.stop = &DeckStreamSession::audioStop15;
        audioCallbacks_.cleanup = &DeckStreamSession::audioCleanup15;
        audioCallbacks_.decodeAndPlaySample = &DeckStreamSession::audioDecodeAndPlaySample15;
        listenerCallbacks_.stageStarting = &DeckStreamSession::listenerStageStarting15;
        listenerCallbacks_.stageComplete = &DeckStreamSession::listenerStageComplete15;
        listenerCallbacks_.stageFailed = &DeckStreamSession::listenerStageFailed15;
        listenerCallbacks_.connectionStarted = &DeckStreamSession::listenerConnectionStarted15;
        listenerCallbacks_.connectionTerminated = &DeckStreamSession::listenerConnectionTerminated15;
        listenerCallbacks_.rumble = &DeckStreamSession::listenerRumble15;
        listenerCallbacks_.setMotionEventState = &DeckStreamSession::listenerSetMotionEventState15;
        listenerCallbacks_.setControllerLED = &DeckStreamSession::listenerSetControllerLed15;
        break;
    default:
        break;
    }
}

DeckStreamSession::DeckStreamSession(
    DeckStreamRenderer& renderer,
    DeckStreamAudio& audio,
    DeckStreamInput& input,
    DeckStreamSessionEvents& events)
    : renderer_(renderer)
    , audio_(audio)
    , input_(input)
    , events_(events) {
    LiInitializeStreamConfiguration(&streamConfig_);
    LiInitializeConnectionCallbacks(&listenerCallbacks_);
    LiInitializeVideoCallbacks(&videoCallbacks_);
    LiInitializeAudioCallbacks(&audioCallbacks_);

    callbackContext_ = nextOpaqueCallbackContext();

    audioCallbacks_.capabilities = CAPABILITY_SUPPORTS_ARBITRARY_AUDIO_DURATION;
    installCallbackThunks();
    streamConfig_.streamingRemotely = STREAM_CFG_AUTO;
    streamConfig_.audioConfiguration = AUDIO_CONFIGURATION_STEREO;
    streamConfig_.supportedVideoFormats = VIDEO_FORMAT_H264;

    moonlightBoundary_ = DeckMoonlightBoundary{
        .listenerCallbacks = &listenerCallbacks_,
        .videoCallbacks = &videoCallbacks_,
        .audioCallbacks = &audioCallbacks_,
        .streamConfig = &streamConfig_,
        .callbackContext = callbackContext_,
        .networkStartAllowed = false,
    };

}

DeckStreamSession::~DeckStreamSession() {
    clearCallbackOwner(*this);
    releaseCallbackSlot(callbackSlot_);
    callbackSlot_ = kInvalidCallbackSlot;
}

DeckStreamSessionState DeckStreamSession::state() const {
    return state_;
}

const DeckMoonlightBoundary& DeckStreamSession::moonlightBoundary() const {
    return moonlightBoundary_;
}

DeckStreamTransition DeckStreamSession::prepare(const DeckStreamRequest& request) {
    if (state_ != DeckStreamSessionState::Idle && state_ != DeckStreamSessionState::Stopped) {
        return fail("prepare requested while stream session is not idle");
    }
    if (!isValidStreamRequest(request)) {
        return fail("invalid stream request dimensions or bitrate");
    }
    if (!hasCallbackSlot()) {
        return fail("no moonlight callback slot available");
    }
    if (hasActiveOwnerOtherThan(*this)) {
        return fail("another stream callback owner is active");
    }

    request_ = request;
    setCallbackOwner(*this);
    streamConfig_.width = request.width;
    streamConfig_.height = request.height;
    streamConfig_.fps = request.fps;
    streamConfig_.bitrate = request.bitrateKbps;
    streamConfig_.packetSize = 1024;
    return transitionTo(DeckStreamSessionState::Preparing, "prepared no-network moonlight-common-c boundary");
}

DeckStreamTransition DeckStreamSession::startNoNetwork() {
    if (state_ != DeckStreamSessionState::Preparing) {
        return fail("start requested before prepare");
    }

    transitionTo(DeckStreamSessionState::Starting, "start requested; no-network skeleton keeps raw stream start disabled");
    return transitionTo(DeckStreamSessionState::Active, "active skeleton session; no sockets or host connection opened");
}

DeckStreamTransition DeckStreamSession::stop() {
    if (state_ != DeckStreamSessionState::Starting && state_ != DeckStreamSessionState::Active) {
        return fail("stop requested before active stream");
    }

    transitionTo(DeckStreamSessionState::Stopping, "stopping skeleton session; LiStopConnection not called because network never started");
    auto stopped = transitionTo(DeckStreamSessionState::Stopped, "stopped no-network skeleton session");
    clearCallbackOwner(*this);
    return stopped;
}

DeckStreamTransition DeckStreamSession::cancel(const std::string_view reason) {
    auto cancelled = transitionTo(DeckStreamSessionState::Cancelled, reason.empty() ? "cancelled before network start" : reason);
    clearCallbackOwner(*this);
    return cancelled;
}

DeckStreamTransition DeckStreamSession::fail(const std::string_view reason) {
    auto failed = transitionTo(DeckStreamSessionState::Failed, reason.empty() ? "stream skeleton failed before network start" : reason);
    clearCallbackOwner(*this);
    return failed;
}

void DeckStreamSession::noteSessionEvent(const std::string_view reason) {
    events_.onSessionEvent(state_, reason);
}

DeckStreamTransition DeckStreamSession::transitionTo(
    const DeckStreamSessionState state,
    const std::string_view reason,
    const bool networkStarted) {
    state_ = state;
    events_.onSessionEvent(state_, reason);
    return DeckStreamTransition{
        .state = state_,
        .reason = std::string(reason),
        .networkStarted = networkStarted,
    };
}

} // namespace nova::deck::stream
