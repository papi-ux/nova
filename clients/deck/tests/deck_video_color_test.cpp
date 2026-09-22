#include "stream/deck_stream_media_adapters.h"
#include <QGuiApplication>
extern "C" {
#include <libavcodec/codec_id.h>
#include <libavutil/frame.h>
#include <libavutil/hwcontext.h>
#include <libavutil/mastering_display_metadata.h>
}
#include <cstdlib>
#include <iostream>

using namespace nova::deck::stream;
namespace { void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } } }

int main(int argc, char** argv) {
    QGuiApplication app(argc, argv);
    require(videoDecoderSpec(VIDEO_FORMAT_H264)->codecId == AV_CODEC_ID_H264, "H.264 decoder mapping changed");
    require(videoDecoderSpec(VIDEO_FORMAT_H265)->codecId == AV_CODEC_ID_HEVC && videoDecoderSpec(VIDEO_FORMAT_H265)->bitDepth == 8, "HEVC Main did not select an 8-bit decoder");
    require(videoDecoderSpec(VIDEO_FORMAT_H265_MAIN10)->codecId == AV_CODEC_ID_HEVC, "Main10 did not select HEVC");
    for (const int bad : {0, -1, VIDEO_FORMAT_H264 | VIDEO_FORMAT_H265_MAIN10, VIDEO_FORMAT_H264 | VIDEO_FORMAT_H265, VIDEO_FORMAT_AV1_MAIN10})
        require(!videoDecoderSpec(bad), "unsupported format or mask selected a decoder");
    AVFrame* frame = av_frame_alloc();
    require(frame, "frame allocation failed");
    frame->format = AV_PIX_FMT_P010LE;
    frame->color_primaries = AVCOL_PRI_BT2020;
    frame->color_trc = AVCOL_TRC_SMPTE2084;
    frame->colorspace = AVCOL_SPC_BT2020_NCL;
    frame->color_range = AVCOL_RANGE_MPEG;
    auto color = DeckVideoColorInfo::fromFrame(*frame);
    require(color.hdr10() && color.bitDepth == 10 && !color.legacySdrCompatible(), "P010 HDR10 was misclassified");
    frame->format = AV_PIX_FMT_YUV420P10LE;
    require(DeckVideoColorInfo::fromFrame(*frame).hdr10(), "planar Main10 was misclassified");
    frame->format = AV_PIX_FMT_YUV444P10LE;
    require(!DeckVideoColorInfo::fromFrame(*frame).hdr10(), "4:4:4 was accepted as this client's Main10 4:2:0 profile");
    frame->format = AV_PIX_FMT_NV12;
    require(!DeckVideoColorInfo::fromFrame(*frame).hdr10() && !DeckVideoColorInfo::fromFrame(*frame).legacySdrCompatible(),
        "8-bit PQ became HDR10 or ordinary SDR");
    frame->color_trc = AVCOL_TRC_ARIB_STD_B67;
    require(!DeckVideoColorInfo::fromFrame(*frame).hdr10() && DeckVideoColorInfo::fromFrame(*frame).hdrSignaled(), "HLG became PQ HDR10");
    frame->color_trc = AVCOL_TRC_BT709;
    require(!DeckVideoColorInfo::fromFrame(*frame).legacySdrCompatible(), "wide-gamut SDR reached fixed BT.709 shader");
    frame->color_primaries = AVCOL_PRI_BT709;
    frame->colorspace = AVCOL_SPC_BT709;
    require(DeckVideoColorInfo::fromFrame(*frame).legacySdrCompatible(), "existing SDR path rejected");

    // Synthetic hardware ownership validates metadata lifetime/guards only.
    frame->format = AV_PIX_FMT_VAAPI;
    frame->buf[0] = av_buffer_alloc(1);
    frame->hw_frames_ctx = av_buffer_allocz(sizeof(AVHWFramesContext));
    require(frame->buf[0] && frame->hw_frames_ctx, "hardware fixture allocation failed");
    auto* context = reinterpret_cast<AVHWFramesContext*>(frame->hw_frames_ctx->data);
    context->sw_format = AV_PIX_FMT_P010LE;
    frame->data[3] = reinterpret_cast<std::uint8_t*>(1);
    frame->color_primaries = AVCOL_PRI_BT2020;
    frame->colorspace = AVCOL_SPC_BT2020_NCL;
    frame->color_trc = AVCOL_TRC_SMPTE2084;
    auto* metadata = av_mastering_display_metadata_create_side_data(frame);
    require(metadata, "metadata allocation failed");
    metadata->has_luminance = 1;
    metadata->max_luminance = {1000, 1};
    auto lease = DeckQrhiVaapiFrameLease::cloneHardwareFrame(*frame);
    av_frame_free(&frame);
    require(lease && lease->colorInfo().hdr10(), "hardware sw_format or color tags lost");
    const auto* side = av_frame_get_side_data(lease->frame(), AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    require(side && reinterpret_cast<const AVMasteringDisplayMetadata*>(side->data)->max_luminance.num == 1000,
        "HDR metadata did not survive source release");
    DeckQrhiVaapiPresentationDescriptor descriptor{.width = 128, .height = 72, .surfaceId = 1, .hardwareBacked = true, .frameLease = lease};
    DeckQtQuickRhiVaapiItem item;
    require(!item.presentVaapiSurface(descriptor) && item.presentedFrames() == 0, "SDR item accepted HDR");
    DeckQtQuickRhiVaapiRenderNode node(descriptor);
    require(node.planQrhiImport(nullptr).status == DeckQrhiVaapiImportStatus::UnsupportedColorSpace,
        "direct render node bypassed color gate");
    const auto readiness = DeckVaapiEglImagePresenter::readinessReportForPlan(node.planQrhiImport(nullptr));
    require(!readiness.ready && readiness.statusCode == "unsupported-color-space", "color refusal reported ready");
    DeckVaapiFfmpegRenderer decoder;
    require(decoder.setup(VIDEO_FORMAT_H264 | VIDEO_FORMAT_H265_MAIN10, 128, 72, 90, nullptr, 0) != DR_OK,
        "decoder accepted a format mask");
    require(!decoder.lifecycle().ownsCodecContext && !decoder.lifecycle().ownsHardwareDevice, "failed format retained decoder resources");
    std::cout << "Video color passed: decoder selection, bit depth, PQ/HLG distinction, retained metadata and SDR gates\n";
}
