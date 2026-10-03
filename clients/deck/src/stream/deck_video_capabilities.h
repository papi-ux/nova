#pragma once
#include <string_view>

struct AVBufferRef;

namespace nova::deck::stream {

enum class DeckDecoderBackend { Unavailable, Vaapi, V4l2, Pyrowave };
enum class DeckFrameTransferPath { DmaBuf, CpuUpload };

struct DeckDecodeLimits {
    int maxWidth = 0, maxHeight = 0;
    DeckDecoderBackend backend = DeckDecoderBackend::Vaapi;
    bool supports(int width, int height) const {
        return width > 0 && height > 0 && width <= maxWidth && height <= maxHeight;
    }
};

// Decoder support only. Main10 never implies an HDR display/presenter.
struct DeckVideoDecodeSupport {
    DeckDecodeLimits h264, hevc, main10, pyrowave;
    bool supports(int videoFormat, int width, int height) const;
};

// probeVideoDecodeSupport queries only the supplied VAAPI device. Detection
// also qualifies the development V4L2 path when explicitly enabled.
// PyroWave is checked separately, on selection,
// through the isolated cached probe; generic startup never initializes it.
// Missing profiles, decode entrypoints, surface formats or size limits remain unsupported.
DeckVideoDecodeSupport probeVideoDecodeSupport(AVBufferRef* device);
// Reuse an already-open VAAPI device while also qualifying absent SDR codecs
// through the explicitly enabled Frame V4L2 path. Startup and launch share this.
DeckVideoDecodeSupport detectVideoDecodeSupport(AVBufferRef* device);
DeckVideoDecodeSupport detectVideoDecodeSupport();

// Auto prefers HEVC only when both endpoints support this stream size.
int selectSdrVideoFormat(std::string_view preference, bool hostH264, bool hostHevc,
    const DeckVideoDecodeSupport& decoder, int width, int height, bool hostPyrowave = false);

} // namespace nova::deck::stream
