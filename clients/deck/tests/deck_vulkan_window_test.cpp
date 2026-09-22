#include "stream/deck_vulkan_video_window.h"
#include "stream/deck_placebo_color_renderer.h"
#include <libplacebo/vulkan.h>
#include <QGuiApplication>
#include <QScreen>
#include <QElapsedTimer>
#include <QThread>
#include <QImage>
#include <QPixmap>
#include <QDir>
#include <cstdlib>
#include <functional>
#include <iostream>
#include <vector>
extern "C" {
#include <libavutil/frame.h>
}

using namespace nova::deck::stream;
namespace {
void require(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}
void wait(const std::function<bool()>& condition, const char* message) {
    QElapsedTimer timer;
    timer.start();
    while (!condition() && timer.elapsed() < 7000) {
        QCoreApplication::processEvents();
        QThread::msleep(5);
    }
    require(condition(), message);
}
AVFrame* makeFrame(bool hdr) {
    AVFrame* frame = av_frame_alloc();
    require(frame, "frame allocation failed");
    frame->format = hdr ? AV_PIX_FMT_YUV420P10LE : AV_PIX_FMT_YUV420P;
    frame->width = 128; frame->height = 72;
    frame->sample_aspect_ratio = {1, 1};
    frame->color_primaries = hdr ? AVCOL_PRI_BT2020 : AVCOL_PRI_BT709;
    frame->color_trc = hdr ? AVCOL_TRC_SMPTE2084 : AVCOL_TRC_BT709;
    frame->colorspace = hdr ? AVCOL_SPC_BT2020_NCL : AVCOL_SPC_BT709;
    frame->color_range = AVCOL_RANGE_MPEG;
    require(av_frame_get_buffer(frame, 32) == 0, "frame backing allocation failed");
    for (int p = 0; p < 3; ++p) {
        const int w = p ? 64 : 128, h = p ? 36 : 72;
        for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
            if (hdr)
                reinterpret_cast<uint16_t*>(frame->data[p] + y * frame->linesize[p])[x] =
                    p ? 512 : x < w / 2 ? 64 : 512;
            else
                frame->data[p][y * frame->linesize[p] + x] = p ? 128 : x < w / 2 ? 16 : 235;
        }
    }
    return frame;
}
QImage capture(DeckVulkanVideoWindow& window) {
    return window.screen()->grabWindow(window.winId()).toImage();
}
int right(DeckVulkanVideoWindow& window) {
    const auto image = capture(window);
    if (image.isNull()) return -1;
    return image.pixelColor(image.width() * 3 / 4, image.height() / 2).red();
}
int referenceSdrPixel(const AVFrame& frame) {
    auto params = pl_vulkan_default_params;
    params.allow_software = true;
    auto vk = pl_vulkan_create(nullptr, &params);
    require(vk, "reference Vulkan device unavailable");
    pl_tex_params target{};
    target.w = frame.width; target.h = frame.height;
    target.format = pl_find_fmt(vk->gpu, PL_FMT_FLOAT, 4, 16, 32,
        static_cast<pl_fmt_caps>(PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_HOST_READABLE));
    target.renderable = target.host_readable = true;
    require(target.format, "reference format unavailable");
    auto texture = pl_tex_create(vk->gpu, &target);
    require(texture, "reference texture allocation failed");
    std::vector<float> values(frame.width * frame.height * 4);
    {
        DeckPlaceboColorRenderer renderer(vk->gpu);
        require(renderer.render(frame, texture, DeckColorOutput::Srgb), "reference tone mapping failed");
        pl_tex_transfer_params transfer{};
        transfer.tex = texture; transfer.ptr = values.data();
        require(pl_tex_download(vk->gpu, &transfer), "reference readback failed");
    }
    const int pixel = qRound(values[(frame.height / 2 * frame.width + frame.width * 3 / 4) * 4] * 255);
    pl_tex_destroy(vk->gpu, &texture);
    pl_vulkan_destroy(&vk);
    return pixel;
}
}

int main(int argc, char** argv) {
    QGuiApplication app(argc, argv);
    AVFrame* hdr = makeFrame(true);
    AVFrame* sdr = makeFrame(false);
    // Compare the captured 8-bit window with the separately rendered floating
    // point reference. This checks the real surface interpretation and channel
    // mapping without guessing the tone mapper's output luminance.
    const int expectedPixel = referenceSdrPixel(*hdr);
    require(expectedPixel > 8 && expectedPixel < 247, "reference fixture lost its visible midtone");
    const int hdrRefs = av_buffer_get_ref_count(hdr->buf[0]), sdrRefs = av_buffer_get_ref_count(sdr->buf[0]);
    {
        // Explicit software permission belongs only to this local test. Xvfb
        // is deliberately SDR; neither synthetic pixels nor window submission
        // is evidence of hardware decode, an HDR panel or physical frame pacing.
        DeckVulkanVideoWindow window(true);
        window.setTitle("Nova local Vulkan presentation check");
        window.resize(320, 240);
        window.setOutputPreference(DeckWindowOutput::PreferHdr10);
        require(window.submitFrame(*hdr), "HDR10 fixture was refused");
        window.show();
        wait([&] { return window.presentationState().submittedFrames > 0; }, "no Vulkan window submission");
        auto state = window.presentationState();
        if (!state.error.isEmpty()) std::cerr << state.error.toStdString() << '\n';
        require(state.ready && state.error.isEmpty() && !state.hdr10Surface && !state.hdrActive && state.toneMapped,
                "SDR surface did not explicitly tone-map HDR input");
        require(state.pixelSize == QSize(320, 240), "acquired swapchain size differs from window");
        std::cout << "Surface pixel=" << right(window) << " floating-point reference=" << expectedPixel << '\n';
        wait([&] { return std::abs(right(window) - expectedPixel) <= 3; }, "window pixels differ from the color reference");
        const auto hdrImage = capture(window);
        const auto lit = hdrImage.pixelColor(240, 120);
        require(std::abs(lit.red() - lit.green()) <= 2 && std::abs(lit.green() - lit.blue()) <= 2,
                "neutral HDR fixture was tinted");
        require(hdrImage.pixelColor(240, 12).red() <= 2 && hdrImage.pixelColor(80, 120).red() <= 2,
                "letterbox or source black was not black");
        if (argc > 1) {
            require(QDir().mkpath(QString::fromLocal8Bit(argv[1])), "capture directory creation failed");
            require(hdrImage.save(QDir(QString::fromLocal8Bit(argv[1])).filePath("vulkan-hdr-to-sdr.png")), "capture write failed");
        }

        quint64 before = state.submittedFrames;
        const auto composedBefore=window.composedFrames()->load();
        window.setVideoScaleMode(DeckVideoScaleMode::Fill);
        wait([&] { return window.presentationState().submittedFrames>before && capture(window).pixelColor(240,12).red()>8; },
            "scaling did not redraw retained frame");
        before=window.presentationState().submittedFrames;
        window.setVideoScaleMode(DeckVideoScaleMode::Stretch);
        wait([&] { return window.presentationState().submittedFrames>before; },"Stretch did not redraw");
        before=window.presentationState().submittedFrames;
        window.setVideoScaleMode(DeckVideoScaleMode::Fit);
        wait([&] { return window.presentationState().submittedFrames>before && capture(window).pixelColor(240,12).red()<=2; },
            "Fit left stale Fill pixels");
        require(window.composedFrames()->load()==composedBefore,"scaling redraw inflated video frame counts");
        before=window.presentationState().submittedFrames;
        window.setOutputPreference(DeckWindowOutput::RequireHdr10);
        wait([&] { return window.presentationState().submittedFrames > before; }, "forced HDR did not settle");
        require(!window.presentationState().error.isEmpty() && !window.presentationState().hdrActive,
                "forced HDR silently fell back on an SDR surface");
        wait([&] { return right(window) <= 2; }, "refused HDR left stale pixels on screen");
        before = window.presentationState().submittedFrames;
        window.setOutputPreference(DeckWindowOutput::PreferHdr10);
        wait([&] { return window.presentationState().submittedFrames > before && window.presentationState().toneMapped; },
                "supported fallback could not recover after refusal");

        before = window.presentationState().submittedFrames;
        window.resize(400, 240);
        wait([&] { return window.presentationState().submittedFrames > before &&
                         window.presentationState().pixelSize == QSize(400, 240); }, "resize did not rebuild the swapchain");
        before = window.presentationState().submittedFrames;
        require(window.submitFrame(*sdr), "SDR fixture was refused");
        wait([&] { return window.presentationState().submittedFrames > before; }, "SDR replacement did not render");
        require(!window.presentationState().toneMapped && !window.presentationState().hdrActive &&
                window.presentationState().error.isEmpty(), "SDR inherited HDR state");
        wait([&] { return right(window) >= 250; }, "SDR white did not reach the screen");

        window.hide();
        wait([&] { return !window.isExposed(); }, "window did not hide");
        before = window.presentationState().submittedFrames;
        for (int i = 0; i < 12; ++i) require(window.submitFrame(*hdr), "hidden frame update failed");
        QCoreApplication::processEvents();
        require(window.presentationState().submittedFrames == before, "hidden window kept submitting frames");
        require(av_buffer_get_ref_count(hdr->buf[0]) <= hdrRefs + 4, "hidden updates accumulated unbounded frame references");

        for (int i = 0; i < 3; ++i) {
            window.destroy();
            require(!window.presentationState().ready && !window.presentationState().hdrActive,
                    "surface destruction left presentation active");
            before = window.presentationState().submittedFrames;
            window.show();
            wait([&] { return window.presentationState().submittedFrames > before && window.presentationState().toneMapped; },
                    "surface recreation did not restore rendering");
        }

        before = window.presentationState().submittedFrames;
        hdr->color_trc = AVCOL_TRC_ARIB_STD_B67;
        require(!window.submitFrame(*hdr), "HLG was accepted as HDR10");
        wait([&] { return window.presentationState().submittedFrames > before; }, "invalid frame did not clear the screen");
        wait([&] { return right(window) <= 2; }, "invalid input left stale pixels on screen");
        require(!window.presentationState().hdrActive && !window.presentationState().toneMapped,
                "invalid input retained HDR state");
        hdr->color_trc = AVCOL_TRC_SMPTE2084;
        require(window.submitFrame(*hdr), "valid frame did not recover");
        wait([&] { return window.presentationState().toneMapped; }, "valid frame did not resume");
        before = window.presentationState().submittedFrames;
        window.clearFrame();
        wait([&] { return window.presentationState().submittedFrames > before; }, "clear did not submit black");
        wait([&] { return right(window) <= 2; }, "clear left stale video");
        require(!window.presentationState().hdrActive && !window.presentationState().toneMapped,
                "clear left an HDR presentation claim");
    }
    require(av_buffer_get_ref_count(hdr->buf[0]) == hdrRefs && av_buffer_get_ref_count(sdr->buf[0]) == sdrRefs,
            "window teardown retained decoder frame buffers");
    av_frame_free(&hdr); av_frame_free(&sdr);
    std::cout << "Vulkan window passed: real swapchain pixels, HDR-to-SDR fallback, forced-HDR refusal, letterbox, resize, hidden-frame bound, surface recreation and teardown\n";
}
