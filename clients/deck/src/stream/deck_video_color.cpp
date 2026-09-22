#include "stream/deck_video_color.h"
#include <Limelight.h>
extern "C" {
#include <libavcodec/codec_id.h>
#include <libavutil/frame.h>
#include <libavutil/hwcontext.h>
#include <libavutil/pixdesc.h>
}
#include <algorithm>

namespace nova::deck::stream {

std::optional<DeckVideoDecoderSpec> videoDecoderSpec(int format) {
    if (format == VIDEO_FORMAT_H264) return DeckVideoDecoderSpec{AV_CODEC_ID_H264, 8, "h264"};
    if (format == VIDEO_FORMAT_H265) return DeckVideoDecoderSpec{AV_CODEC_ID_HEVC, 8, "hevc-main"};
    if (format == VIDEO_FORMAT_H265_MAIN10) return DeckVideoDecoderSpec{AV_CODEC_ID_HEVC, 10, "hevc-main10"};
    return {};
}

DeckVideoColorInfo DeckVideoColorInfo::fromFrame(const AVFrame& frame) {
    auto format = static_cast<AVPixelFormat>(frame.format);
    if ((format == AV_PIX_FMT_VAAPI || format == AV_PIX_FMT_DRM_PRIME) && frame.hw_frames_ctx && frame.hw_frames_ctx->data
            && frame.hw_frames_ctx->size >= sizeof(AVHWFramesContext))
        format = reinterpret_cast<const AVHWFramesContext*>(frame.hw_frames_ctx->data)->sw_format;
    const auto* descriptor = av_pix_fmt_desc_get(format);
    int depth = 0;
    if (descriptor && !(descriptor->flags & AV_PIX_FMT_FLAG_HWACCEL))
        for (int i = 0; i < descriptor->nb_components; ++i) depth = std::max(depth, descriptor->comp[i].depth);
    const bool yuv420 = descriptor && descriptor->nb_components == 3 && descriptor->log2_chroma_w == 1
        && descriptor->log2_chroma_h == 1 && !(descriptor->flags & (AV_PIX_FMT_FLAG_RGB | AV_PIX_FMT_FLAG_HWACCEL));
    return {depth, frame.color_primaries, frame.color_trc, frame.colorspace, frame.color_range, yuv420};
}

bool DeckVideoColorInfo::hdrSignaled() const {
    return transfer == AVCOL_TRC_SMPTE2084 || transfer == AVCOL_TRC_ARIB_STD_B67;
}

bool DeckVideoColorInfo::hdr10() const {
    return yuv420 && bitDepth == 10 && primaries == AVCOL_PRI_BT2020 && transfer == AVCOL_TRC_SMPTE2084
        && matrix == AVCOL_SPC_BT2020_NCL && (range == AVCOL_RANGE_MPEG || range == AVCOL_RANGE_JPEG);
}

bool DeckVideoColorInfo::legacySdrCompatible() const {
    // Unknown legacy bit depth is retained for the existing VAAPI import gate,
    // which independently only admits R8/GR88. Explicit HDR/wide-gamut/10-bit
    // frames must never reach that fixed SDR shader.
    return bitDepth <= 8 && !hdrSignaled() && primaries != AVCOL_PRI_BT2020
        && matrix != AVCOL_SPC_BT2020_NCL && matrix != AVCOL_SPC_BT2020_CL;
}

} // namespace nova::deck::stream
