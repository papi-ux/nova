#include "stream/deck_stream_media_adapters.h"
#include "codec.h"
#include <QGuiApplication>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <algorithm>
#include <cmath>
#include <iostream>
#include <fstream>
#include <iterator>
#include <stdexcept>
using namespace nova::deck::stream;
namespace {
void require(bool value, const std::string& message) {
    if (!value) throw std::runtime_error(message);
}
class ScopedLiveEglContext final {
public:
    ScopedLiveEglContext() {
        const char* clientExtensions = eglQueryString(EGL_NO_DISPLAY, EGL_EXTENSIONS);
        auto getPlatformDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
        if (getPlatformDisplay != nullptr && clientExtensions != nullptr &&
            std::string_view(clientExtensions).find("EGL_MESA_platform_surfaceless") != std::string_view::npos) {
            display_ = getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
        }
        if (display_ == EGL_NO_DISPLAY) {
            display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        }
        if (display_ == EGL_NO_DISPLAY || eglInitialize(display_, nullptr, nullptr) != EGL_TRUE) {
            detail_ = "eglInitialize failed for surfaceless/default display";
            display_ = EGL_NO_DISPLAY;
            return;
        }
        if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) {
            detail_ = "eglBindAPI(EGL_OPENGL_ES_API) failed";
            return;
        }

        const EGLint configAttributes[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE,
        };
        EGLConfig config = nullptr;
        EGLint configCount = 0;
        if (eglChooseConfig(display_, configAttributes, &config, 1, &configCount) != EGL_TRUE || configCount <= 0) {
            detail_ = "eglChooseConfig failed for GLES2 pbuffer";
            return;
        }

        const EGLint surfaceAttributes[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
        surface_ = eglCreatePbufferSurface(display_, config, surfaceAttributes);
        if (surface_ == EGL_NO_SURFACE) {
            detail_ = "eglCreatePbufferSurface failed";
            return;
        }
        const EGLint contextAttributes[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
        context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, contextAttributes);
        if (context_ == EGL_NO_CONTEXT) {
            detail_ = "eglCreateContext(GLES2) failed";
            return;
        }
        if (eglMakeCurrent(display_, surface_, surface_, context_) != EGL_TRUE) {
            detail_ = "eglMakeCurrent failed";
            return;
        }
        valid_ = true;
        detail_ = "live EGL/GLES2 pbuffer context current";
    }

    ~ScopedLiveEglContext() {
        if (display_ != EGL_NO_DISPLAY) {
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (context_ != EGL_NO_CONTEXT) {
                eglDestroyContext(display_, context_);
            }
            if (surface_ != EGL_NO_SURFACE) {
                eglDestroySurface(display_, surface_);
            }
            eglTerminate(display_);
        }
    }

    bool valid() const { return valid_; }
    const std::string& detail() const { return detail_; }

private:
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLContext context_ = EGL_NO_CONTEXT;
    bool valid_ = false;
    std::string detail_ = "not attempted";
};

}
int main(int argc, char** argv) try {
    qputenv("QT_QPA_PLATFORM", "minimal");
    QGuiApplication application(argc, argv);
    ScopedLiveEglContext egl;
    if (!egl.valid()) { std::cerr << egl.detail() << '\n'; return 77; }
    constexpr int width = 128, height = 96;
    nova::pyrowave::Codec encoder, decoder;
    if (!encoder.open(width, height, true) || !decoder.open(width, height, false)) {
        std::cerr << encoder.error() << decoder.error() << '\n'; return 77;
    }
    auto source = nova::pyrowave::Image::allocate(width, height);
    std::fill(source.planes[0].begin(), source.planes[0].end(), 128);
    std::fill(source.planes[1].begin(), source.planes[1].end(), 90);
    std::fill(source.planes[2].begin(), source.planes[2].end(), 160);
    std::vector<std::uint8_t> encoded;
    const bool hostFixture = argc == 2;
    if (hostFixture) {
        std::ifstream input(argv[1], std::ios::binary);
        require(input.good(), "host fixture unavailable");
        encoded.assign(std::istreambuf_iterator<char>(input), {});
        require(encoded.size() <= nova::pyrowave::maxFrameBytes, "host fixture too large");
    } else {
        require(encoder.encode(source, 64000, encoded), encoder.error());
    }
    nova::pyrowave::GpuImage gpu;
    require(decoder.decodeGpu(encoded, gpu), decoder.error());
    auto lease = DeckQrhiVaapiFrameLease::retainPyrowaveFrame(gpu);
    require(lease && lease->valid(), "GPU frame lease invalid");
    gpu = {}; decoder.close(); encoder.close();
    auto drm = lease->exportDrmPrimeDescriptor();
    require(drm.status == DeckQrhiVaapiImportStatus::DrmPrimeExported && drm.layerCount == 3, "three planes not exported");
    DeckVaapiEglImagePresenter::Resource resource;
    auto plan = DeckVaapiEglImagePresenter::importOpenGlTextureForCurrentContext(drm, QSize(width, height), resource);
    require(plan.status == DeckQrhiVaapiImportStatus::DrmPrimeExported, plan.detail);
    std::vector<std::uint8_t> pixels;
    require(DeckVaapiEglImagePresenter::proveOpenGlShaderCompositionForCurrentContext(resource, QSize(width, height), &pixels),
        resource.shaderCompositionDetail);
    require(pixels.size() == width * height * 4, "missing renderer readback");
    const int expected[] = {179, 120, 58};
    for (int y = hostFixture ? 24 : 8; y < height - (hostFixture ? 24 : 8); y += 8) for (int x = 8; x < width - 8; x += 8)
        for (int c = 0; c < 3; ++c) {
            const auto actual = pixels[(y * width + x) * 4 + c];
            require(std::abs(int(actual) - expected[c]) <= 18,
                "PyroWave renderer color mismatch: channel " + std::to_string(c) + " value " + std::to_string(actual));
        }
    if (hostFixture) for (int y : {4, height - 5}) for (int c = 0; c < 3; ++c)
        require(pixels[(y * width + width / 2) * 4 + c] <= 18, "host letterbox is not black");
    DeckVaapiFfmpegRenderer renderer;
    require(renderer.setup(VIDEO_FORMAT_PYROWAVE, width, height, 60, nullptr, 0) == DR_OK, "renderer setup failed");
    LENTRY entry{};
    entry.data = reinterpret_cast<char*>(encoded.data()); entry.length = int(encoded.size());
    entry.bufferType = BUFFER_TYPE_PICDATA;
    DECODE_UNIT unit{};
    unit.frameNumber = 1; unit.frameType = FRAME_TYPE_IDR; unit.fullLength = entry.length; unit.bufferList = &entry;
    require(renderer.submitDecodeUnit(&unit) == DR_OK, "renderer refused valid frame");
    encoded[0] ^= 2;
    require(renderer.submitDecodeUnit(&unit) == DR_NEED_IDR, "renderer accepted invalid frame");
    encoded[0] ^= 2;
    require(renderer.submitDecodeUnit(&unit) == DR_OK, "renderer failed to recover");
    const auto measured = renderer.lifecycle();
    require(measured.incomingFrames == 3 && measured.decodedHardwareFrames == 2 && measured.refusedFrames == 1 &&
        measured.videoWorkSamples == 3 && measured.videoWorkMicros > 0 && measured.lastRuntimeError.empty(),
        "renderer metrics conflate decoder refusal with transport loss or retain a recovered error");
    require(renderer.setup(VIDEO_FORMAT_PYROWAVE, width, height, 60, nullptr, 0) == DR_OK, "renderer restart failed");
    require(renderer.lifecycle().refusedFrames == 0 && renderer.lifecycle().videoWorkSamples == 0, "restart kept old counters");
    std::cout << "PyroWave Vulkan -> DMA-BUF -> Nova EGL renderer color and lifetime checks passed\n";
    return 0;
} catch (const std::exception& error) {
    std::cerr << error.what() << '\n'; return 1;
}
