#include "stream/deck_placebo_color_renderer.h"
#include "stream/deck_video_color.h"
#include <Limelight.h>
#include <libplacebo/vulkan.h>
#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QProcess>
#include <QTemporaryDir>
#include <QTextStream>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/hwcontext_drm.h>
#include <libavutil/hwcontext.h>
}
#include <libdrm/drm_fourcc.h>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

using namespace nova::deck::stream;
namespace {
void require(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
constexpr int width = 144, height = 72;
constexpr std::array<int, 9> levels{64, 128, 256, 400, 401, 512, 650, 720, 512};
std::vector<float> pixels(pl_gpu gpu, pl_tex texture) {
    std::vector<float> values(width * height * 4);
    pl_tex_transfer_params transfer{};
    transfer.tex = texture; transfer.ptr = values.data();
    require(pl_tex_download(gpu, &transfer), "texture readback failed");
    for (float value : values) require(std::isfinite(value), "non-finite rendered component");
    return values;
}
float sample(const std::vector<float>& values, int band, int component = 0) {
    return values[((height / 2) * width + band * 16 + 8) * 4 + component];
}
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    QTemporaryDir directory;
    require(directory.isValid(), "temporary fixture directory failed");
    const auto input = directory.filePath("pq.yuv"), encoded = directory.filePath("pq.hevc");
    QFile raw(input);
    require(raw.open(QIODevice::ReadWrite), "raw fixture open failed");
    auto writeWord = [&](int value) {
        const char bytes[]{static_cast<char>(value & 255), static_cast<char>(value >> 8)};
        require(raw.write(bytes, 2) == 2, "raw fixture write failed");
    };
    for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) writeWord(levels[x / 16]);
    for (int plane = 0; plane < 2; ++plane)
        for (int y = 0; y < height / 2; ++y) for (int x = 0; x < width / 2; ++x)
            writeWord(x >= 64 ? (plane == 0 ? 600 : 400) : 512);
    require(raw.seek(0), "raw fixture rewind failed");
    const auto firstFrame = raw.readAll();
    require(raw.write(firstFrame) == firstFrame.size(), "second raw frame write failed");
    raw.close();
    QProcess encoder;
    encoder.start("ffmpeg", {"-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pixel_format", "yuv420p10le",
        "-video_size", "144x72", "-i", input, "-frames:v", "2", "-c:v", "libx265", "-preset", "ultrafast",
        "-profile:v", "main10", "-pix_fmt", "yuv420p10le", "-color_range", "tv", "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc",
        "-x265-params", "qp=0:no-sao=1:no-deblock=1:colorprim=9:transfer=16:colormatrix=9:repeat-headers=1:keyint=60:min-keyint=1:scenecut=0:bframes=0:pools=1:frame-threads=1:log-level=error:master-display=G(8500,39850)B(6550,2300)R(35400,14600)WP(15635,16450)L(10000000,50):max-cll=1000,400",
        "-f", "hevc", encoded});
    require(encoder.waitForFinished(20000) && encoder.exitStatus() == QProcess::NormalExit && encoder.exitCode() == 0,
        "Main10 HDR10 fixture encoding failed");
    QFile file(encoded);
    require(file.open(QIODevice::ReadOnly), "encoded fixture open failed");
    auto bytes = file.readAll();
    const int encodedSize = bytes.size();
    bytes.append(QByteArray(AV_INPUT_BUFFER_PADDING_SIZE, '\0'));
    const auto spec = videoDecoderSpec(VIDEO_FORMAT_H265_MAIN10);
    auto* decoder = avcodec_alloc_context3(avcodec_find_decoder(static_cast<AVCodecID>(spec->codecId)));
    require(decoder && avcodec_open2(decoder, decoder->codec, nullptr) == 0, "Main10 decoder open failed");
    auto* parser = av_parser_init(static_cast<AVCodecID>(spec->codecId));
    require(parser, "HEVC parser unavailable");
    uint8_t* accessUnit = nullptr;
    int accessUnitSize = 0;
    // Two frames prevent x265 choosing a still-picture-only profile. Decode the
    // first real Annex-B access unit, as delivered by the streaming protocol.
    require(av_parser_parse2(parser, decoder, &accessUnit, &accessUnitSize,
        reinterpret_cast<const uint8_t*>(bytes.constData()), encodedSize, AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0) > 0 && accessUnitSize > 0,
        "first HEVC access unit missing");
    AVPacket* packet = av_packet_alloc();
    require(packet && av_new_packet(packet, accessUnitSize) == 0, "packet allocation failed");
    std::copy_n(accessUnit, accessUnitSize, packet->data);
    av_parser_close(parser);
    require(avcodec_send_packet(decoder, packet) == 0, "Main10 packet rejected");
    av_packet_free(&packet);
    AVFrame* frame = av_frame_alloc();
    require(frame, "decoded frame allocation failed");
    int result = avcodec_receive_frame(decoder, frame);
    if (result == AVERROR(EAGAIN)) { require(avcodec_send_packet(decoder, nullptr) == 0, "decoder flush failed"); result = avcodec_receive_frame(decoder, frame); }
    std::cout << "Decoded fixture: result=" << result << " format=" << frame->format << " profile=" << decoder->profile
        << " primaries=" << frame->color_primaries << " transfer=" << frame->color_trc << " matrix=" << frame->colorspace
        << " range=" << frame->color_range << '\n';
    require(result == 0 && decoder->profile == AV_PROFILE_HEVC_MAIN_10 && frame->format == AV_PIX_FMT_YUV420P10LE && DeckVideoColorInfo::fromFrame(*frame).hdr10(),
        "real HEVC decode lost Main10/PQ colorimetry");
    avcodec_free_context(&decoder);
    const int initialFrameReferences = av_buffer_get_ref_count(frame->buf[0]);
    const auto* md = av_frame_get_side_data(frame, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
    const auto* cl = av_frame_get_side_data(frame, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
    require(md && cl && av_q2d(reinterpret_cast<const AVMasteringDisplayMetadata*>(md->data)->max_luminance) == 1000 &&
        reinterpret_cast<const AVContentLightMetadata*>(cl->data)->MaxCLL == 1000, "encoded static HDR10 metadata was lost");

    auto vkParams = pl_vulkan_default_params;
    vkParams.allow_software = true; // This test deliberately permits a software Vulkan device.
    pl_vulkan vk = pl_vulkan_create(nullptr, &vkParams);
    require(vk, "Vulkan color validation requires a Vulkan device (software is allowed)");
    auto format = pl_find_fmt(vk->gpu, PL_FMT_FLOAT, 4, 16, 32,
        static_cast<pl_fmt_caps>(PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_HOST_READABLE));
    require(format, "missing floating-point render/readback format");
    pl_tex_params targetParams{};
    targetParams.w = width; targetParams.h = height; targetParams.format = format;
    targetParams.renderable = targetParams.host_readable = targetParams.blit_dst = true;
    pl_tex target = pl_tex_create(vk->gpu, &targetParams);
    require(target, "target allocation failed");
    {
        DeckPlaceboColorRenderer renderer(vk->gpu);
        if (!renderer.render(*frame, target, DeckColorOutput::Hdr10Pq)) require(false, renderer.error().c_str());
        require(av_buffer_get_ref_count(frame->buf[0]) > initialFrameReferences, "queued GPU work did not retain its source frame");
        require(renderer.inputColor().primaries == PL_COLOR_PRIM_BT_2020 && renderer.inputColor().transfer == PL_COLOR_TRC_PQ &&
            renderer.inputRepresentation().bits.color_depth == 10 && renderer.inputRepresentation().sys == PL_COLOR_SYSTEM_BT_2020_NC &&
            renderer.inputColor().hdr.max_luma == 1000 &&
            renderer.inputColor().hdr.max_cll == 1000 && renderer.inputColor().hdr.max_fall == 400,
            "AVFrame-to-renderer metadata changed");
        const auto hdr = pixels(vk->gpu, target);
        for (int i = 1; i < 8; ++i) require(sample(hdr, i) > sample(hdr, i - 1), "HDR shades lost ordering or precision");
        require(sample(hdr, 4) - sample(hdr, 3) > 0.0005f, "adjacent 10-bit codes collapsed to an 8-bit step");
        for (int i = 1; i < 8; ++i)
            require(std::abs(sample(hdr, i) - (levels[i] - 64.0f) / 876.0f) < 0.012f, "PQ reference output changed unexpectedly");
        pl_swapchain_frame surface{};
        surface.fbo = target;
        surface.color_repr = pl_color_repr_rgb;
        surface.color_space = pl_color_space_hdr10;
        surface.color_space.hdr.min_luma = 0.005f;
        surface.color_space.hdr.max_luma = 1000;
        surface.color_space.hdr.prim = *pl_raw_primaries_get(PL_COLOR_PRIM_BT_2020);
        require(deckSurfaceColorOutput(surface) == DeckColorOutput::Hdr10Pq && renderer.renderToSurface(*frame, surface),
                "explicit acquired PQ surface was refused");
        const auto surfaceHdr = pixels(vk->gpu, target);
        for (int i = 1; i < 8; ++i)
            require(std::abs(sample(surfaceHdr, i) - sample(hdr, i)) < 0.002f, "surface PQ output differs from reference");
        surface.color_space.hdr.max_luma = 500;
        require(renderer.renderToSurface(*frame, surface), "500-nit acquired target failed");
        const auto lowerPeak = pixels(vk->gpu, target);
        require(sample(lowerPeak, 7) < sample(hdr, 7) - 0.025f, "surface metadata was replaced by the 1000-nit reference");
        surface.color_space.transfer = PL_COLOR_TRC_HLG;
        require(!deckSurfaceColorOutput(surface) && !renderer.renderToSurface(*frame, surface), "unknown surface transfer was relabeled PQ");
        surface.color_space = pl_color_space_srgb;
        require(deckSurfaceColorOutput(surface) == DeckColorOutput::Srgb && renderer.renderToSurface(*frame, surface),
                "acquired sRGB surface did not accept tone mapping");
        surface.color_repr.levels = PL_COLOR_LEVELS_LIMITED;
        require(!deckSurfaceColorOutput(surface), "limited-range surface was interpreted as full range");
        surface.color_repr = pl_color_repr_rgb;
        surface.color_space = pl_color_space_hdr10;
        surface.color_repr.bits.color_depth = 8;
        require(!deckSurfaceColorOutput(surface), "8-bit surface representation was advertised as HDR10");
        const auto decoded = [&](int plane) {
            const int row = plane == 0 ? height / 2 : height / 4;
            const int x = plane == 0 ? 136 : 68;
            return reinterpret_cast<const uint16_t*>(frame->data[plane] + row * frame->linesize[plane])[x];
        };
        const float y = (decoded(0) - 64.0f) / 876, cb = (decoded(1) - 512.0f) / 896, cr = (decoded(2) - 512.0f) / 896;
        const float expected[]{y + 1.4746f * cr, y - 0.164553f * cb - 0.571353f * cr, y + 1.8814f * cb};
        std::cout << "BT.2020 reference RGB=" << expected[0] << ',' << expected[1] << ',' << expected[2]
            << " rendered=" << sample(hdr, 8, 0) << ',' << sample(hdr, 8, 1) << ',' << sample(hdr, 8, 2) << '\n';
        for (int i = 0; i < 3; ++i) require(std::abs(sample(hdr, 8, i) - expected[i]) < 0.002f, "BT.2020 color matrix was not used");

        AVFrame* p010 = av_frame_alloc();
        require(p010, "P010 allocation failed");
        p010->format = AV_PIX_FMT_P010LE; p010->width = width; p010->height = height;
        require(av_frame_get_buffer(p010, 32) == 0 && av_frame_copy_props(p010, frame) == 0, "P010 backing allocation failed");
        for (int row = 0; row < height; ++row) for (int x = 0; x < width; ++x)
            reinterpret_cast<uint16_t*>(p010->data[0] + row * p010->linesize[0])[x] =
                reinterpret_cast<uint16_t*>(frame->data[0] + row * frame->linesize[0])[x] << 6;
        for (int row = 0; row < height / 2; ++row) for (int x = 0; x < width / 2; ++x) for (int c = 0; c < 2; ++c)
            reinterpret_cast<uint16_t*>(p010->data[1] + row * p010->linesize[1])[x * 2 + c] =
                reinterpret_cast<uint16_t*>(frame->data[c + 1] + row * frame->linesize[c + 1])[x] << 6;
        if (!renderer.render(*p010, target, DeckColorOutput::Hdr10Pq)) require(false, renderer.error().c_str());
        const auto packed = pixels(vk->gpu, target);
        for (int band = 0; band < 9; ++band) for (int c = 0; c < 3; ++c)
            require(std::abs(sample(packed, band, c) - sample(hdr, band, c)) < 0.001f, "P010 packing changed rendered color");
        av_frame_free(&p010);

        AVFrame* drmFrame = av_frame_alloc();
        require(drmFrame, "DRM fixture allocation failed");
        drmFrame->format = AV_PIX_FMT_DRM_PRIME; drmFrame->width = width; drmFrame->height = height;
        require(av_frame_copy_props(drmFrame, frame) == 0, "DRM fixture metadata copy failed");
        drmFrame->hw_frames_ctx = av_buffer_allocz(sizeof(AVHWFramesContext));
        drmFrame->buf[0] = av_buffer_allocz(sizeof(AVDRMFrameDescriptor));
        require(drmFrame->hw_frames_ctx && drmFrame->buf[0], "DRM fixture buffers failed");
        reinterpret_cast<AVHWFramesContext*>(drmFrame->hw_frames_ctx->data)->sw_format = AV_PIX_FMT_P010LE;
        drmFrame->data[0] = drmFrame->buf[0]->data;
        auto& drm = *reinterpret_cast<AVDRMFrameDescriptor*>(drmFrame->data[0]);
        drm.nb_objects = 1; drm.nb_layers = 1; drm.layers[0].nb_planes = 2;
        require(!renderer.render(*drmFrame, target, DeckColorOutput::Hdr10Pq) && renderer.error() == "Unsupported DRM PRIME plane layout",
            "combined DRM layer reached an asserting import helper");
        drm.nb_layers = 2;
        drm.layers[0].nb_planes = drm.layers[1].nb_planes = 1;
        drm.layers[0].format = DRM_FORMAT_R16; drm.layers[1].format = DRM_FORMAT_GR1616;
        drm.layers[0].planes[0].object_index = 5;
        require(!renderer.render(*drmFrame, target, DeckColorOutput::Hdr10Pq) && renderer.error() == "Unsupported DRM PRIME plane layout",
            "invalid DRM object index reached the GPU importer");
        // Two NV12-shaped layers with a three-plane software context previously
        // passed the depth-only gate and could assert inside libplacebo.
        auto* drmContext = reinterpret_cast<AVHWFramesContext*>(drmFrame->hw_frames_ctx->data);
        drmContext->sw_format = AV_PIX_FMT_YUV420P10LE;
        drm.objects[0].fd = 0; drm.objects[0].size = width * height * 4;
        drm.layers[0].planes[0] = {0, 0, width * 2};
        drm.layers[1].planes[0] = {0, width * height * 2, width * 2};
        require(!renderer.render(*drmFrame, target, DeckColorOutput::Hdr10Pq) && renderer.error() == "Unsupported DRM PRIME plane layout",
            "planar context reached a two-layer import helper");
        av_frame_free(&drmFrame);

        if (!renderer.render(*frame, target, DeckColorOutput::Srgb)) require(false, renderer.error().c_str());
        const auto sdr = pixels(vk->gpu, target);
        for (int i = 1; i < 8; ++i) require(sample(sdr, i) > sample(sdr, i - 1), "SDR tone mapping flattened distinct shades");
        require(std::abs(sample(sdr, 5) - sample(hdr, 5)) > 0.05f, "HDR was copied into SDR without a transfer conversion");
        for (float value : sdr) require(value >= -0.001f && value <= 1.001f, "SDR tone mapping escaped output range");
        if (argc > 1) {
            const QDir evidence(QString::fromLocal8Bit(argv[1]));
            require(QDir().mkpath(evidence.path()), "capture directory creation failed");
            QFile csv(evidence.filePath("rendered-pixels.csv"));
            require(csv.open(QIODevice::WriteOnly), "pixel evidence open failed");
            QTextStream rows(&csv);
            rows << "band,pq_r,pq_g,pq_b,srgb_r,srgb_g,srgb_b\n";
            rows.setRealNumberPrecision(9);
            for (int band = 0; band < 9; ++band) {
                rows << band;
                for (const auto* output : {&hdr, &sdr}) for (int c = 0; c < 3; ++c) rows << ',' << sample(*output, band, c);
                rows << '\n';
            }
            rows.flush();
            require(csv.error() == QFileDevice::NoError, "pixel evidence write failed");
        }

        auto lowPrecision = targetParams;
        lowPrecision.format = pl_find_fmt(vk->gpu, PL_FMT_UNORM, 4, 8, 8, PL_FMT_CAP_RENDERABLE);
        require(lowPrecision.format, "missing 8-bit validation target");
        lowPrecision.host_readable = false;
        auto low = pl_tex_create(vk->gpu, &lowPrecision);
        require(low && !renderer.render(*frame, low, DeckColorOutput::Hdr10Pq), "HDR silently used an 8-bit target");
        pl_tex_destroy(vk->gpu, &low);
        frame->color_trc = AVCOL_TRC_ARIB_STD_B67;
        require(!renderer.render(*frame, target, DeckColorOutput::Hdr10Pq), "HLG was relabeled HDR10");
        frame->color_trc = AVCOL_TRC_SMPTE2084;
        frame->color_primaries = AVCOL_PRI_UNSPECIFIED;
        require(!renderer.render(*frame, target, DeckColorOutput::Srgb), "incomplete HDR colorimetry was guessed");
        frame->color_primaries = AVCOL_PRI_BT2020;
        av_frame_remove_side_data(frame, AV_FRAME_DATA_MASTERING_DISPLAY_METADATA);
        av_frame_remove_side_data(frame, AV_FRAME_DATA_CONTENT_LIGHT_LEVEL);
        require(renderer.render(*frame, target, DeckColorOutput::Srgb) && renderer.inputColor().hdr.max_cll == 0 &&
            renderer.inputColor().hdr.max_fall == 0, "prior frame's static metadata leaked into the next frame");
        frame->color_primaries = AVCOL_PRI_BT709;
        frame->color_trc = AVCOL_TRC_BT709;
        frame->colorspace = AVCOL_SPC_BT709;
        require(renderer.render(*frame, target, DeckColorOutput::Srgb) && renderer.inputColor().transfer != PL_COLOR_TRC_PQ,
            "SDR frame inherited the previous HDR transfer");
        require(!renderer.render(*frame, target, DeckColorOutput::Hdr10Pq), "10-bit SDR was relabeled HDR10");
        require(!renderer.render(*frame, target, static_cast<DeckColorOutput>(99)), "unknown output encoding was accepted");
        pl_gpu_finish(vk->gpu);
    }
    require(av_buffer_get_ref_count(frame->buf[0]) == initialFrameReferences, "color renderer teardown retained mapped source frames");
    pl_tex_destroy(vk->gpu, &target);
    pl_vulkan_destroy(&vk);
    av_frame_free(&frame);
    std::cout << "Main10 color passed: real HEVC decode, static HDR10 metadata, planar/P010 precision, Vulkan PQ pixels, SDR tone mapping and refusal paths\n";
}
