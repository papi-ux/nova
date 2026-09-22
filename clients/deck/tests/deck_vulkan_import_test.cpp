#include "stream/deck_placebo_color_renderer.h"
#include "stream/deck_video_color.h"
#include <libplacebo/vulkan.h>
#include <QCoreApplication>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
#include <libavutil/mastering_display_metadata.h>
}
#include <algorithm>
#include <cmath>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>
using namespace nova::deck::stream;
namespace {
void check(bool ok, const char* reason) { if (!ok) throw std::runtime_error(reason); }
struct FreeFrame { void operator()(AVFrame* p) const { av_frame_free(&p); } };
using Frame = std::unique_ptr<AVFrame, FreeFrame>;
struct Device { AVBufferRef* p = nullptr; ~Device() { av_buffer_unref(&p); } };
struct Codec { AVCodecContext* p = nullptr; ~Codec() { avcodec_free_context(&p); } };
struct Parser { AVCodecParserContext* p = nullptr; ~Parser() { if (p) av_parser_close(p); } };
struct Packet { AVPacket* p = av_packet_alloc(); ~Packet() { av_packet_free(&p); } };
struct Vulkan { pl_vulkan p = nullptr; ~Vulkan() { pl_vulkan_destroy(&p); } };
struct Texture { pl_gpu gpu; pl_tex p = nullptr; ~Texture() { pl_tex_destroy(gpu, &p); } };
AVPixelFormat onlyVaapi(AVCodecContext*, const AVPixelFormat* formats) {
    for (; *formats != AV_PIX_FMT_NONE; ++formats) if (*formats == AV_PIX_FMT_VAAPI) return *formats;
    return AV_PIX_FMT_NONE; // Never silently decode the hardware check on CPU.
}
Frame decode(const QByteArray& encoded, AVBufferRef* device, bool ten) {
    auto bytes = encoded; bytes.append(QByteArray(AV_INPUT_BUFFER_PADDING_SIZE, '\0'));
    Codec codec{avcodec_alloc_context3(avcodec_find_decoder(AV_CODEC_ID_HEVC))};
    check(codec.p, "decoder-allocation");
    codec.p->thread_count = 1;
    if (device) {
        codec.p->get_format = onlyVaapi;
        codec.p->hw_device_ctx = av_buffer_ref(device);
        check(codec.p->hw_device_ctx, "device-reference");
    }
    check(avcodec_open2(codec.p, codec.p->codec, nullptr) == 0, "decoder-open");
    Parser parser{av_parser_init(AV_CODEC_ID_HEVC)}; check(parser.p, "hevc-parser");
    uint8_t* access = nullptr; int size = 0;
    check(av_parser_parse2(parser.p, codec.p, &access, &size,
        reinterpret_cast<const uint8_t*>(bytes.constData()), encoded.size(), AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0) > 0 && size > 0,
        "hevc-access-unit");
    Packet packet; check(packet.p && av_new_packet(packet.p, size) == 0, "packet-allocation");
    std::copy_n(access, size, packet.p->data);
    check(avcodec_send_packet(codec.p, packet.p) == 0, "hardware-or-software-decode-send");
    Frame frame(av_frame_alloc()); check(bool(frame), "frame-allocation");
    int status = avcodec_receive_frame(codec.p, frame.get());
    if (status == AVERROR(EAGAIN)) {
        check(avcodec_send_packet(codec.p, nullptr) == 0, "decoder-flush");
        status = avcodec_receive_frame(codec.p, frame.get());
    }
    check(status == 0 && codec.p->profile == (ten ? AV_PROFILE_HEVC_MAIN_10 : AV_PROFILE_HEVC_MAIN), "decoded-profile");
    if (device) {
        check(frame->format == AV_PIX_FMT_VAAPI && frame->hw_frames_ctx, "hardware-frame-required");
        const auto* hw = reinterpret_cast<const AVHWFramesContext*>(frame->hw_frames_ctx->data);
        check(hw->sw_format == (ten ? AV_PIX_FMT_P010LE : AV_PIX_FMT_NV12), "decoded-surface-format");
    } else check(frame->format == (ten ? AV_PIX_FMT_YUV420P10LE : AV_PIX_FMT_YUV420P), "software-reference-format");
    // The returned hardware surface must remain usable after decoder teardown.
    return frame;
}
Frame packReference(const AVFrame& source, bool ten) {
    Frame packed(av_frame_alloc()); check(bool(packed), "packed-allocation");
    packed->width = source.width; packed->height = source.height;
    packed->format = ten ? AV_PIX_FMT_P010LE : AV_PIX_FMT_NV12;
    check(av_frame_get_buffer(packed.get(), 32) == 0 && av_frame_copy_props(packed.get(), &source) == 0, "packed-backing");
    for (int p = 0; p < 3; ++p) for (int y = 0; y < (p ? (source.height + 1) / 2 : source.height); ++y)
        for (int x = 0; x < (p ? (source.width + 1) / 2 : source.width); ++x) {
            const int targetPlane = p ? 1 : 0, column = p ? x * 2 + p - 1 : x;
            if (ten) reinterpret_cast<uint16_t*>(packed->data[targetPlane] + y * packed->linesize[targetPlane])[column] =
                reinterpret_cast<const uint16_t*>(source.data[p] + y * source.linesize[p])[x] << 6;
            else packed->data[targetPlane][y * packed->linesize[targetPlane] + column] = source.data[p][y * source.linesize[p] + x];
        }
    return packed;
}
void metadata(const AVFrame& frame, bool ten) {
    const auto color = DeckVideoColorInfo::fromFrame(frame);
    check(color.yuv420 && color.bitDepth == (ten ? 10 : 8), "color-depth");
    check(ten ? color.hdr10() : color.legacySdrCompatible() && !color.hdrSignaled(), "color-encoding");
    if (ten) {
        const auto* mastering = av_frame_get_side_data(&frame, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
        const auto* light = av_frame_get_side_data(&frame, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
        check(mastering && light, "hdr-static-metadata-missing");
        check(av_q2d(reinterpret_cast<const AVMasteringDisplayMetadata*>(mastering->data)->max_luminance) == 1000 &&
            reinterpret_cast<const AVContentLightMetadata*>(light->data)->MaxCLL == 1000 &&
            reinterpret_cast<const AVContentLightMetadata*>(light->data)->MaxFALL == 400, "hdr-static-metadata-value");
    }
}
std::vector<float> render(pl_gpu gpu, DeckPlaceboColorRenderer& renderer, const AVFrame& frame, DeckColorOutput output) {
    const int refs = av_buffer_get_ref_count(frame.buf[0]);
    pl_tex_params params{}; params.w = frame.width; params.h = frame.height;
    params.format = pl_find_fmt(gpu, PL_FMT_FLOAT, 4, 16, 32,
        static_cast<pl_fmt_caps>(PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_HOST_READABLE));
    params.renderable = params.host_readable = true;
    check(params.format, "float-readback-format");
    Texture target{gpu, pl_tex_create(gpu, &params)}; check(target.p, "render-target");
    check(renderer.render(frame, target.p, output), "production-render-import");
    check(av_buffer_get_ref_count(frame.buf[0]) > refs && renderer.pendingFrames() > 0, "source-not-retained");
    std::vector<float> pixels(frame.width * frame.height * 4);
    pl_tex_transfer_params transfer{}; transfer.tex = target.p; transfer.ptr = pixels.data();
    check(pl_tex_download(gpu, &transfer), "render-readback");
    for (float value : pixels) check(std::isfinite(value), "nonfinite-pixel");
    pl_gpu_finish(gpu); renderer.retireFrames();
    check(renderer.pendingFrames() == 0 && av_buffer_get_ref_count(frame.buf[0]) == refs, "source-not-retired");
    return pixels;
}
void compare(const std::vector<float>& actual, const std::vector<float>& expected) {
    check(actual.size() == expected.size() && !actual.empty(), "pixel-size");
    float worst = 0; double total = 0, low = 1, high = 0;
    for (size_t i = 0; i < actual.size(); ++i) {
        if (i % 4 == 3) continue;
        const float error = std::abs(actual[i] - expected[i]);
        worst = std::max(worst, error); total += error;
        low = std::min(low, double(expected[i])); high = std::max(high, double(expected[i]));
    }
    check(high - low > 0.2, "reference-lacks-contrast");
    // Permit small decoder rounding differences, never a blank/mispacked image.
    check(worst <= 0.02f && total / (actual.size() / 4 * 3) <= 0.002, "pixel-reference-mismatch");
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    bool reference = false, required = false;
    QByteArray devicePath;
    QJsonObject result{{"schema", 1}, {"status", "failed"}, {"stage", "arguments"},
        {"hardware_import_verified", false}, {"hdr_display_verified", false},
        {"nv12_sdr", false}, {"p010_pq", false}, {"p010_sdr", false}};
    const auto receipt = [&](int exit) {
        std::cout << QJsonDocument(result).toJson(QJsonDocument::Compact).constData() << '\n'; return exit;
    };
    try {
        for (int i = 1; i < argc; ++i) {
            const QByteArray arg(argv[i]);
            if (arg == "--reference") reference = true;
            else if (arg == "--require-hardware") required = true;
            else if (arg == "--device" && i + 1 < argc) devicePath = argv[++i];
            else throw std::runtime_error("invalid-arguments");
        }
        check(!reference || (!required && devicePath.isEmpty()), "conflicting-modes");
        result["mode"] = reference ? "software-reference" : "hardware";
        Device device;
        if (!reference) {
            result["stage"] = "vaapi-device";
            if (av_hwdevice_ctx_create(&device.p, AV_HWDEVICE_TYPE_VAAPI,
                    devicePath.isEmpty() ? nullptr : devicePath.constData(), nullptr, 0) < 0) {
                result["status"] = "unavailable";
                return receipt(required ? 1 : 77);
            }
        }
        result["stage"] = "vulkan-device";
        auto params = pl_vulkan_default_params;
        params.allow_software = reference;
        params.async_compute = params.async_transfer = false; params.queue_count = 1;
        Vulkan vk{pl_vulkan_create(nullptr, &params)};
        check(vk.p, "vulkan-unavailable");
        if (!reference) check(vk.p->gpu->import_caps.tex & PL_HANDLE_DMA_BUF, "dma-buf-unavailable");
        DeckPlaceboColorRenderer renderer(vk.p->gpu);
        for (bool ten : {false, true}) {
            result["stage"] = ten ? "main10-decode" : "main-decode";
            QFile file(QStringLiteral(NOVA_DECK_IMPORT_FIXTURE_DIR) + (ten ? "/hevc-main10-hdr.b64" : "/hevc-main-sdr.b64"));
            check(file.open(QIODevice::ReadOnly), "fixture-open");
            const auto bytes = QByteArray::fromBase64(file.readAll()); check(!bytes.isEmpty(), "fixture-empty");
            auto software = decode(bytes, nullptr, ten);
            auto source = reference ? packReference(*software, ten) : decode(bytes, device.p, ten);
            metadata(*software, ten); metadata(*source, ten);
            check(source->width == software->width && source->height == software->height, "decode-size");
            for (const auto output : {DeckColorOutput::Srgb, DeckColorOutput::Hdr10Pq}) {
                if (!ten && output == DeckColorOutput::Hdr10Pq) continue;
                result["stage"] = output == DeckColorOutput::Hdr10Pq ? "p010-pq-import" : ten ? "p010-sdr-import" : "nv12-sdr-import";
                const auto expected = render(vk.p->gpu, renderer, *software, output);
                // Reimport the same decoded surface to exercise mapped-object
                // retirement; no upload/download fallback exists in production.
                for (int i = 0; i < 3; ++i) compare(render(vk.p->gpu, renderer, *source, output), expected);
                if (ten) check(renderer.inputColor().hdr.max_cll == 1000 && renderer.inputColor().hdr.max_fall == 400,
                    "render-static-metadata");
                result[output == DeckColorOutput::Hdr10Pq ? "p010_pq" : ten ? "p010_sdr" : "nv12_sdr"] = true;
            }
        }
        result["stage"] = "complete"; result["status"] = "passed";
        result["hardware_import_verified"] = !reference;
        return receipt(0);
    } catch (const std::exception& error) {
        result["reason"] = QString::fromLatin1(error.what());
        return receipt(1);
    }
}
