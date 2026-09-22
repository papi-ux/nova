#pragma once
#include <string_view>

struct AVBufferRef;

namespace nova::deck::stream {

struct DeckDecodeLimits {
    int maxWidth = 0, maxHeight = 0;
    bool supports(int width, int height) const {
        return width > 0 && height > 0 && width <= maxWidth && height <= maxHeight;
    }
};

// Decoder support only. Main10 never implies an HDR display/presenter.
struct DeckVideoDecodeSupport {
    DeckDecodeLimits h264, hevc, main10;
    bool supports(int videoFormat, int width, int height) const;
};

// Query the actual FFmpeg VAAPI device used by the decoder. Missing profiles,
// decode entrypoints, surface formats or size limits remain unsupported.
DeckVideoDecodeSupport probeVideoDecodeSupport(AVBufferRef* device);
DeckVideoDecodeSupport detectVideoDecodeSupport();

// Auto prefers HEVC only when both endpoints support this stream size.
int selectSdrVideoFormat(std::string_view preference, bool hostH264, bool hostHevc,
    const DeckVideoDecodeSupport& decoder, int width, int height);

} // namespace nova::deck::stream
