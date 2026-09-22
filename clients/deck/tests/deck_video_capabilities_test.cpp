#include "stream/deck_stream_media_adapters.h"
#include <QCoreApplication>
#include <QFile>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
}
#include <algorithm>
#include <cstdlib>
#include <iostream>

using namespace nova::deck::stream;
namespace { void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } } }

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    const DeckVideoDecodeSupport support{.h264 = {4096, 4096}, .hevc = {1920, 1200}, .main10 = {4096, 4096}};
    require(selectSdrVideoFormat("auto", true, true, support, 1920, 1200) == VIDEO_FORMAT_H265, "Auto did not prefer supported HEVC");
    require(selectSdrVideoFormat("auto", true, true, support, 1921, 1200) == VIDEO_FORMAT_H264, "Auto ignored decoder size limits");
    require(selectSdrVideoFormat("hevc", true, true, support, 1921, 1200) == 0, "explicit HEVC silently changed codec");
    require(selectSdrVideoFormat("hevc", false, true, support, 1280, 800) == VIDEO_FORMAT_H265, "HEVC-only host was rejected");
    require(selectSdrVideoFormat("h264", true, true, support, 1280, 800) == VIDEO_FORMAT_H264, "explicit H.264 upgraded codec");
    for (const auto* preference : {"auto", "hevc", "h264", "main10", "av1", "", "HEVC"}) {
        require(selectSdrVideoFormat(preference, true, true, {}, 1280, 800) == 0, "missing decoder was guessed");
        require(selectSdrVideoFormat(preference, false, false, support, 1280, 800) == 0, "missing host support was guessed");
        require(selectSdrVideoFormat(preference, true, true, support, -1, 800) == 0, "invalid dimensions admitted");
    }
    require(selectSdrVideoFormat("auto", true, true, {.main10 = {4096, 4096}}, 1280, 800) == 0,
        "Main10 decoding implied SDR or HDR presentation support");
    require(!support.supports(VIDEO_FORMAT_H264 | VIDEO_FORMAT_H265, 1280, 800), "format mask passed exact decoder guard");
    require(!probeVideoDecodeSupport(nullptr).h264.supports(1280, 800), "missing device became hardware support");
    auto* wrongDevice = av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_VULKAN);
    if (wrongDevice) {
        require(!probeVideoDecodeSupport(wrongDevice).hevc.supports(1280, 800), "non-VAAPI device was accepted");
        av_buffer_unref(&wrongDevice);
    }

    // Retained two-frame x265 Main (not Main Still Picture) Annex-B sample.
    // Software decode is deterministic even on hosts without VAAPI. It proves
    // the actual codec/profile/color route, not hardware presentation or HDR.
    QFile fixture(QStringLiteral(NOVA_DECK_HEVC_FIXTURE));
    require(fixture.open(QIODevice::ReadOnly), "HEVC fixture missing");
    auto bytes = QByteArray::fromBase64(fixture.readAll());
    require(!bytes.isEmpty(), "empty HEVC fixture");
    const int encodedSize = bytes.size();
    bytes.append(QByteArray(AV_INPUT_BUFFER_PADDING_SIZE, '\0'));
    const auto spec = videoDecoderSpec(VIDEO_FORMAT_H265);
    auto* decoder = avcodec_alloc_context3(avcodec_find_decoder(static_cast<AVCodecID>(spec->codecId)));
    require(decoder && avcodec_open2(decoder, decoder->codec, nullptr) == 0, "HEVC decoder open failed");
    auto* parser = av_parser_init(static_cast<AVCodecID>(spec->codecId));
    require(parser, "HEVC parser missing");
    uint8_t* accessUnit = nullptr;
    int size = 0;
    require(av_parser_parse2(parser, decoder, &accessUnit, &size, reinterpret_cast<const uint8_t*>(bytes.constData()),
        encodedSize, AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0) > 0 && size > 0, "HEVC access unit missing");
    auto* packet = av_packet_alloc();
    require(packet && av_new_packet(packet, size) == 0, "packet allocation failed");
    std::copy_n(accessUnit, size, packet->data);
    av_parser_close(parser);
    require(avcodec_send_packet(decoder, packet) == 0, "real HEVC access unit rejected");
    av_packet_free(&packet);
    auto* frame = av_frame_alloc();
    require(frame, "frame allocation failed");
    int result = avcodec_receive_frame(decoder, frame);
    if (result == AVERROR(EAGAIN)) {
        require(avcodec_send_packet(decoder, nullptr) == 0, "HEVC flush failed");
        result = avcodec_receive_frame(decoder, frame);
    }
    const auto color = DeckVideoColorInfo::fromFrame(*frame);
    require(result == 0 && decoder->profile == AV_PROFILE_HEVC_MAIN && frame->width == 128 && frame->height == 72 &&
        color.bitDepth == 8 && color.yuv420 && color.legacySdrCompatible() && !color.hdrSignaled(),
        "real HEVC sample did not decode through the supported SDR path");
    require(frame->color_primaries == AVCOL_PRI_BT709 && frame->color_trc == AVCOL_TRC_BT709 &&
        frame->colorspace == AVCOL_SPC_BT709 && frame->color_range == AVCOL_RANGE_MPEG, "HEVC SDR color tags changed");
    av_frame_free(&frame);
    avcodec_free_context(&decoder);
    const auto detected = DeckLinuxMediaProbe::detect().videoDecodeSupport;
    std::cout << "Codec policy and HEVC Main software decode passed; runtime VAAPI 1280x800 support: H264="
        << detected.h264.supports(1280, 800) << " HEVC=" << detected.hevc.supports(1280, 800)
        << " Main10=" << detected.main10.supports(1280, 800) << ". Hardware playback is a separate acceptance check.\n";
}
