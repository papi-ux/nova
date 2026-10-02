#include "stream/deck_stream_media_adapters.h"
#include "stream/deck_placebo_color_renderer.h"
#include <libplacebo/vulkan.h>
extern "C" {
#include <libavutil/frame.h>
#include <libavutil/mem.h>
}
#include <cassert>
#include <cmath>
#include <vector>

using namespace nova::deck::stream;
int main() {
    constexpr int width = 64, height = 32, stride = 96;
    constexpr std::size_t yBytes = stride * height, bytes = yBytes * 3 / 2;
    int freed = 0;
    auto* data = static_cast<uint8_t*>(av_malloc(bytes));
    auto* frame = av_frame_alloc();
    assert(data && frame);
    frame->buf[0] = av_buffer_create(data, bytes, [](void* opaque, uint8_t* allocation) {
        ++*static_cast<int*>(opaque); av_free(allocation);
    }, &freed, 0);
    assert(frame->buf[0]);
    frame->data[0] = data; frame->data[1] = data + yBytes;
    frame->linesize[0] = frame->linesize[1] = stride;
    frame->format = AV_PIX_FMT_NV12; frame->width = width; frame->height = height;
    frame->colorspace = AVCOL_SPC_BT709; frame->color_primaries = AVCOL_PRI_BT709;
    frame->color_trc = AVCOL_TRC_BT709; frame->color_range = AVCOL_RANGE_MPEG;
    for (int y = 0; y < height; ++y) for (int x = 0; x < stride; ++x)
        data[y * stride + x] = x < width / 2 ? 16 : 235;
    std::fill(data + yBytes, data + bytes, 128);
    auto lease = DeckQrhiVaapiFrameLease::retainV4l2Frame(*frame);
    assert(lease);
    av_frame_free(&frame);
    auto params = pl_vulkan_default_params;
    params.allow_software = true;
    auto vk = pl_vulkan_create(nullptr, &params);
    assert(vk);
    const auto format = pl_find_fmt(vk->gpu, PL_FMT_FLOAT, 4, 16, 32,
        static_cast<pl_fmt_caps>(PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_HOST_READABLE));
    assert(format);
    pl_tex_params texture{};
    texture.w = width; texture.h = height; texture.format = format;
    texture.renderable = texture.host_readable = texture.blit_dst = true;
    auto target = pl_tex_create(vk->gpu, &texture);
    assert(target);
    {
        DeckPlaceboColorRenderer renderer(vk->gpu);
        assert(renderer.render(*lease->frame(), target, DeckColorOutput::Srgb));
        assert(renderer.pendingFrames() > 0 && renderer.pendingFrames() <= 3);
        for (int i = 0; i < 50; ++i) {
            if (renderer.canAcceptFrame())
                assert(renderer.render(*lease->frame(), target, DeckColorOutput::Srgb));
            assert(renderer.pendingFrames() <= 3);
        }
        lease.reset();
        assert(freed == 0); // GPU upload owns the source after decoder/lease teardown.
        std::vector<float> pixels(width * height * 4);
        pl_tex_transfer_params transfer{};
        transfer.tex = target; transfer.ptr = pixels.data();
        assert(pl_tex_download(vk->gpu, &transfer));
        for (int c = 0; c < 3; ++c) {
            const auto black = pixels[(height / 2 * width + 8) * 4 + c];
            const auto white = pixels[(height / 2 * width + 56) * 4 + c];
            assert(std::isfinite(black) && std::isfinite(white));
            assert(std::abs(black) < 0.02 && std::abs(white - 1) < 0.02);
        }
        pl_gpu_finish(vk->gpu);
        renderer.retireFrames();
        assert(renderer.pendingFrames() == 0 && freed == 1);
    }
    pl_tex_destroy(vk->gpu, &target);
    pl_vulkan_destroy(&vk);
}
