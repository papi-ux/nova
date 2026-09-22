#include "stream/deck_placebo_color_renderer.h"
#include <libplacebo/vulkan.h>
#include <QCoreApplication>
#include <cstdlib>
#include <iostream>
#include <vector>
extern "C" {
#include <libavutil/frame.h>
}
using namespace nova::deck::stream;
namespace {
void check(bool ok, const char* message) { if (!ok) { std::cerr << message << '\n'; std::exit(1); } }
AVFrame* pattern(int width, int height, AVRational sar) {
    auto* f = av_frame_alloc(); check(f, "frame allocation");
    f->width = width; f->height = height; f->format = AV_PIX_FMT_YUV420P; f->sample_aspect_ratio = sar;
    f->color_primaries = AVCOL_PRI_BT709; f->color_trc = AVCOL_TRC_BT709;
    f->colorspace = AVCOL_SPC_BT709; f->color_range = AVCOL_RANGE_MPEG;
    check(av_frame_get_buffer(f, 32) == 0, "frame backing");
    for (int p = 0; p < 3; ++p) for (int y = 0; y < (p ? height/2 : height); ++y)
        for (int x = 0; x < (p ? width/2 : width); ++x)
            f->data[p][y*f->linesize[p] + x] = p ? 128 : x < width/20 || x >= width*19/20 || y < height/20 || y >= height*19/20
                ? 235 : y < height/2 ? 80 : 140;
    return f;
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    auto params = pl_vulkan_default_params; params.allow_software = true;
    auto vk = pl_vulkan_create(nullptr, &params); check(vk, "Vulkan reference device unavailable");
    auto format = pl_find_fmt(vk->gpu, PL_FMT_FLOAT, 4, 16, 32,
        static_cast<pl_fmt_caps>(PL_FMT_CAP_RENDERABLE | PL_FMT_CAP_HOST_READABLE));
    check(format, "readback format unavailable");
    struct Case { int sw, sh, tw, th; AVRational sar; int wider; };
    // Deck 16:10, matching 16:9, ultrawide, portrait display/source and non-square pixels.
    const Case cases[] = {{160,90,320,200,{1,1},1}, {160,90,320,180,{1,1},0},
        {160,90,344,144,{1,1},-1}, {160,90,180,320,{1,1},1},
        {90,160,320,180,{1,1},-1}, {160,90,320,200,{1,2},-1}};
    for (const auto& c : cases) {
        AVFrame* frame = pattern(c.sw,c.sh,c.sar);
        pl_tex_params tex{}; tex.w=c.tw; tex.h=c.th; tex.format=format; tex.renderable=tex.host_readable=tex.blit_dst=true;
        auto target=pl_tex_create(vk->gpu,&tex); check(target,"target allocation");
        pl_swapchain_frame surface{}; surface.fbo=target; surface.color_repr=pl_color_repr_rgb; surface.color_space=pl_color_space_srgb;
        {
            DeckPlaceboColorRenderer renderer(vk->gpu);
            auto uncleared=tex; uncleared.blit_dst=false;
            auto bad=pl_tex_create(vk->gpu,&uncleared); check(bad,"non-clearable fixture");
            auto badSurface=surface; badSurface.fbo=bad;
            check(!renderer.renderToSurface(*frame,badSurface) && renderer.error().find("clear letterbox")!=std::string::npos,
                "non-clearable target accepted");
            pl_tex_destroy(vk->gpu,&bad);
            const auto read = [&] {
                std::vector<float> pixels(c.tw*c.th*4);
                pl_tex_transfer_params transfer{}; transfer.tex=target; transfer.ptr=pixels.data();
                check(pl_tex_download(vk->gpu,&transfer),"pixel download");
                renderer.retireFrames(); return pixels;
            };
            // Switching back to Fit must clear pixels left by Fill/Stretch.
            for (auto mode : {DeckVideoScaleMode::Fit,DeckVideoScaleMode::Fill,DeckVideoScaleMode::Stretch,DeckVideoScaleMode::Fit}) {
                check(renderer.renderToSurface(*frame,surface,nullptr,mode),renderer.error().c_str());
                const auto pixels=read();
                const auto at = [&](int x,int y) { return pixels[(y*c.tw+x)*4]; };
                const float left=at(2,c.th/2), top=at(c.tw/2,2);
                std::cout << c.sw << "x" << c.sh << " sar=" << c.sar.num << "/" << c.sar.den << " target=" << c.tw << "x" << c.th << " mode=" << int(mode) << " edges=" << left << "," << top << std::endl;
                const auto white=[](float v) { return v > 0.94f; };
                const auto gray=[](float v) { return v > 0.15f && v < 0.85f; };
                if (mode==DeckVideoScaleMode::Stretch || !c.wider) check(white(left)&&white(top),"full picture edge missing");
                else if (mode==DeckVideoScaleMode::Fit) check(c.wider>0 ? top<0.02f&&white(left) : left<0.02f&&white(top),"Fit bars/edge wrong or stale");
                else check(c.wider>0 ? gray(left)&&white(top) : white(left)&&gray(top),"Fill failed to crop the correct source edge");
                surface.flipped=true;
                check(renderer.renderToSurface(*frame,surface,nullptr,mode),"flipped render failed");
                const auto flipped=read();
                for (int y=0;y<c.th;++y) for (int x=0;x<c.tw;++x)
                    check(std::abs(pixels[(y*c.tw+x)*4]-flipped[((c.th-1-y)*c.tw+x)*4]) < 0.003f,"surface flip changed crop or color");
                surface.flipped=false;
            }
        }
        check(av_buffer_get_ref_count(frame->buf[0])==1,"source frame leaked after scaling");
        pl_tex_destroy(vk->gpu,&target); av_frame_free(&frame);
    }
    pl_vulkan_destroy(&vk);
    std::cout << "Video scaling passed: 48 Vulkan pixel renders across Deck/docked/portrait/SAR, Fit/Fill/Stretch, flipped surfaces and source retirement\n";
}
