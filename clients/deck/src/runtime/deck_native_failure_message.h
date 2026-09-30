#pragma once

#include "stream/deck_stream_core.h"
#include <QVariantMap>
#include <cstdint>

namespace nova::deck::runtime {

// Shared host refusal code: historically both capture and display/HDR refusal.
// Its numeric value alone does not identify which condition occurred.
inline bool deckHostStreamRefusal(int code) {
    return static_cast<std::uint32_t>(code) == 0x800e9403u;
}

// Only typed numeric facts enter QML. Raw transport logs remain private.
inline QVariantMap deckNativeFailureDiagnostics(const stream::DeckMoonlightConnectionStatus& status) {
    return {{"failureSource", status.failedStage >= 0 ? "stage" : status.terminated ? "termination" : "start"},
        {"failedStage", status.failedStage}, {"failedStageErrorCode", status.failedStageErrorCode},
        {"terminationErrorCode", status.terminationErrorCode}, {"connectionStartCode", status.startErrorCode}};
}

inline std::string deckNativeFailureMessage(const stream::DeckMoonlightConnectionStatus& status, bool pyrowave) {
    const std::string ports = "Check your connection and streaming ports 47998 to 48010 on the PC and network.";
    const std::string hostRefused = "The PC refused this stream. Check its display and stream settings, then try again.";
    if (status.terminated && status.failedStage < 0) {
        if (deckHostStreamRefusal(status.terminationErrorCode)) return hostRefused;
        switch (status.terminationErrorCode) {
        case ML_ERROR_NO_VIDEO_TRAFFIC:
            return "No video traffic reached Nova. Check that the PC can capture this screen. " + ports;
        case ML_ERROR_NO_VIDEO_FRAME:
            return "No complete video frame reached Nova. Lower the resolution or bitrate, check the connection, then try again.";
        case ML_ERROR_UNEXPECTED_EARLY_TERMINATION:
            return "The stream ended shortly after it started. Check the PC's capture and encoder, then try again.";
        case ML_ERROR_PROTECTED_CONTENT:
            return "The PC stopped the stream because it encountered protected content. Close the protected content and try again.";
        case ML_ERROR_FRAME_CONVERSION:
            return "The PC could not convert the captured video. Choose another codec or a supported capture mode on the PC.";
        case ML_ERROR_GRACEFUL_TERMINATION: return "The PC ended the stream.";
        default:
            return "The stream ended with an unrecognized native error (error " + std::to_string(status.terminationErrorCode)
                + "). Check the PC and connection, then try again.";
        }
    }
    if (deckHostStreamRefusal(status.failedStageErrorCode)) return hostRefused;
    if (status.failedStage == STAGE_RTSP_HANDSHAKE && status.failedStageErrorCode == 503)
        return pyrowave ? "The PC refused PyroWave for this stream. Choose HEVC or H.264, or review the PC's PyroWave capture support."
            : "The PC refused the stream's RTSP handshake (status 503). Check its capture settings and current sessions, then try again.";
    if (status.failedStage == STAGE_RTSP_HANDSHAKE && status.failedStageErrorCode == 403)
        return "The PC refused permission to start the stream. Check this device's permissions on the PC, then try again.";
    std::string step, action;
    switch (status.failedStage) {
    case STAGE_PLATFORM_INIT: step="platform setup"; action="Restart Nova and check this device's graphics and audio support."; break;
    case STAGE_NAME_RESOLUTION: step="PC name or address lookup"; action="Check the saved PC address and that the PC is reachable."; break;
    case STAGE_AUDIO_STREAM_INIT: step="audio channel setup"; action="Check this device's audio settings and connection, then try again."; break;
    case STAGE_RTSP_HANDSHAKE: step="RTSP handshake"; action=ports; break;
    case STAGE_CONTROL_STREAM_INIT: step="control channel setup"; action=ports; break;
    case STAGE_VIDEO_STREAM_INIT: step="video channel setup"; action=ports; break;
    case STAGE_INPUT_STREAM_INIT: step="game input setup"; action="Reconnect the controller and try again."; break;
    case STAGE_CONTROL_STREAM_START: step="control connection"; action=ports; break;
    case STAGE_VIDEO_STREAM_START: step="video stream setup"; action="Check this device's video decoder and the PC's capture settings, then try again."; break;
    case STAGE_AUDIO_STREAM_START: step="audio stream setup"; action="Check this device's audio decoder and playback settings, then try again."; break;
    case STAGE_INPUT_STREAM_START: step="game input connection"; action=ports; break;
    default:
        if (status.failedStage >= 0)
            return "Stream setup failed at an unrecognized stage (stage " + std::to_string(status.failedStage)
                + ", error " + std::to_string(status.failedStageErrorCode) + "). Refresh the PC and try again.";
        return "Stream setup failed without a reported stage (error " + std::to_string(status.startErrorCode)
            + "). Refresh the PC and try again.";
    }
    return "The " + step + " failed (error " + std::to_string(status.failedStageErrorCode) + "). " + action;
}
} // namespace nova::deck::runtime
