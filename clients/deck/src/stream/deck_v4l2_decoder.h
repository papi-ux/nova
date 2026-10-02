#pragma once
#include "stream/deck_video_capabilities.h"
#include <string>
#include <QString>

struct AVFrame;
struct AVCodecContext;

namespace nova::deck::stream {
// This backend admits only bounded, reference-owned linear NV12. Qualcomm
// compressed capture formats, DRM descriptors and borrowed pointers are refused.
bool deckV4l2Nv12LayoutSupported(const AVFrame& frame);
const char* deckV4l2DecoderName(int videoFormat);
std::string deckV4l2Device();
AVCodecContext* openDeckV4l2Decoder(int videoFormat, int width, int height,
    const std::string& device, std::string& error);
struct DeckV4l2Qualification {
    DeckDecodeLimits h264, hevc;
    std::string device;
};
DeckV4l2Qualification probeDeckV4l2InChild(const QString& program,
    const std::string& device, int timeoutMs = 10000);
// Opt-in development path. A separate bounded child must decode each fixture
// on the chosen device before that codec is advertised. No persistent receipt.
DeckV4l2Qualification qualifyDeckV4l2Decoder();
}
