#include "stream/deck_stream_media_adapters.h"

#include <Limelight.h>

extern "C" {
#include <libavutil/buffer.h>
#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>
}

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <QGuiApplication>
#include <QByteArray>
#include <QtQuick/QSGNode>

#include <cstdlib>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <string>
#include <string_view>
#include <type_traits>
#include <vector>

#include <sys/wait.h>
#include <unistd.h>

namespace {

bool require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << message << '\n';
        return false;
    }
    return true;
}

#define NOVA_TEST_REQUIRE(...) \
    do { \
        if (!require(static_cast<bool>((__VA_ARGS__)), "expected " #__VA_ARGS__)) { \
            return 1; \
        } \
    } while (false)

class NoopInput final : public nova::deck::stream::DeckStreamInput {
public:
    std::string_view adapterName() const override { return "noop-input"; }
    void rumble(uint16_t controllerNumber, uint16_t lowFreqMotor, uint16_t highFreqMotor) override {
        (void)controllerNumber;
        (void)lowFreqMotor;
        (void)highFreqMotor;
    }
    void setMotionEventState(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) override {
        (void)controllerNumber;
        (void)motionType;
        (void)reportRateHz;
    }
    void setControllerLed(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) override {
        (void)controllerNumber;
        (void)r;
        (void)g;
        (void)b;
    }
};

class RecordingEvents final : public nova::deck::stream::DeckStreamSessionEvents {
public:
    void onSessionEvent(nova::deck::stream::DeckStreamSessionState state, std::string_view reason) override {
        states.push_back(state);
        reasons.emplace_back(reason);
    }

    std::vector<nova::deck::stream::DeckStreamSessionState> states;
    std::vector<std::string> reasons;
};

std::vector<std::uint8_t> readBinaryFile(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!require(input.good(), "expected generated H.264 sample to be readable")) {
        return {};
    }
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

std::vector<std::uint8_t> makeLocalAnnexBH264IdrSample() {
    const auto output = std::filesystem::temp_directory_path() / ("nova-deck-local-idr-" + std::to_string(getpid()) + ".h264");
    const pid_t pid = fork();
    if (!require(pid >= 0, "expected ffmpeg sample encoder process to fork")) {
        return {};
    }
    if (pid == 0) {
        execlp(
            "ffmpeg",
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "color=c=black:s=128x72:r=1:d=1",
            "-frames:v",
            "1",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-tune",
            "zerolatency",
            "-x264-params",
            "keyint=1:min-keyint=1:scenecut=0",
            "-f",
            "h264",
            output.c_str(),
            static_cast<char*>(nullptr));
        _exit(127);
    }
    int status = 0;
    if (!require(waitpid(pid, &status, 0) == pid, "expected ffmpeg sample encoder process to exit")) {
        return {};
    }
    if (!require(WIFEXITED(status), "expected ffmpeg sample encoder to exit normally")) {
        return {};
    }
    if (!require(WEXITSTATUS(status) == 0, "expected ffmpeg sample encoder to succeed")) {
        return {};
    }
    auto bytes = readBinaryFile(output);
    if (!require(!bytes.empty(), "expected generated H.264 sample bytes")) {
        return {};
    }
    return bytes;
}

DECODE_UNIT makeDecodeUnit(std::vector<std::uint8_t>& annexBBytes, LENTRY& entry) {
    entry.next = nullptr;
    entry.data = reinterpret_cast<char*>(annexBBytes.data());
    entry.length = static_cast<int>(annexBBytes.size());
    entry.bufferType = BUFFER_TYPE_PICDATA;

    DECODE_UNIT unit{};
    unit.frameNumber = 1;
    unit.frameType = FRAME_TYPE_IDR;
    unit.fullLength = entry.length;
    unit.bufferList = &entry;
    return unit;
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

} // namespace

int main(int argc, char** argv) {
    qputenv("QT_QPA_PLATFORM", qgetenv("QT_QPA_PLATFORM").isEmpty() ? QByteArray("minimal") : qgetenv("QT_QPA_PLATFORM"));
    QGuiApplication app(argc, argv);

    using nova::deck::stream::DeckLinuxAudioProbe;
    using nova::deck::stream::DeckLinuxMediaProbe;
    using nova::deck::stream::DeckPipeWireAudio;
    using nova::deck::stream::DeckQrhiVaapiFrameLease;
    using nova::deck::stream::DeckQtQuickRhiVaapiItem;
    using nova::deck::stream::DeckQtQuickRhiVaapiRenderNode;
    using nova::deck::stream::DeckQrhiVaapiImportStatus;
    using nova::deck::stream::DeckQrhiVaapiImportPlan;
    using nova::deck::stream::DeckQrhiVaapiPresentationDescriptor;
    using nova::deck::stream::DeckQrhiVaapiPresentationHandoff;
    using nova::deck::stream::DeckVaapiPreviewFramePump;
    using nova::deck::stream::DeckVaapiPresenterReadinessState;
    using nova::deck::stream::DeckQtQuickRhiPresentationSink;
    using nova::deck::stream::DeckVaapiEglImagePresenter;
    using nova::deck::stream::DeckProductPreviewPipeline;
    using nova::deck::stream::DeckGuardedPreviewLifecycleGate;
    using nova::deck::stream::DeckGuardedStreamSessionPreviewProducer;
    using nova::deck::stream::DeckOperatorStartAuthorizationMode;
    using nova::deck::stream::DeckOperatorStartAuthorizationPolicy;
    using nova::deck::stream::DeckStreamRequest;
    using nova::deck::stream::DeckStreamSession;
    using nova::deck::stream::DeckStreamSessionState;
    using nova::deck::stream::DeckVaapiFfmpegRenderer;

    static_assert(!std::is_copy_constructible_v<DeckVaapiFfmpegRenderer>);
    static_assert(!std::is_move_constructible_v<DeckVaapiFfmpegRenderer>);

    const DeckLinuxMediaProbe mediaProbe = DeckLinuxMediaProbe::detect();
    NOVA_TEST_REQUIRE(mediaProbe.ffmpegLibavcodecHeadersLinked);
    NOVA_TEST_REQUIRE(mediaProbe.ffmpegLibavutilHeadersLinked);
    NOVA_TEST_REQUIRE(mediaProbe.vaapiHeadersLinked);
    NOVA_TEST_REQUIRE(mediaProbe.qtQuickRhiPresentationBoundary);
    NOVA_TEST_REQUIRE(mediaProbe.hardwareDeviceTypeName == std::string("vaapi"));
    NOVA_TEST_REQUIRE(!mediaProbe.runtimeStatus.empty());

    class RecordingPresentationSink final : public DeckQtQuickRhiPresentationSink {
    public:
        bool presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& descriptor) override {
            ++presentCalls;
            lastDescriptor = descriptor;
            return descriptor.hardwareBacked && descriptor.surfaceId != 0;
        }

        int presentCalls = 0;
        DeckQrhiVaapiPresentationDescriptor lastDescriptor{};
    };

    auto presentationSink = std::make_shared<RecordingPresentationSink>();
    DeckQrhiVaapiPresentationHandoff presentationHandoff;
    presentationHandoff.setSink(presentationSink);
    NOVA_TEST_REQUIRE(presentationHandoff.presentVaapiSurface(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = 42,
        .hardwareBacked = true,
        .source = "test-vaapi-surface",
    }));
    NOVA_TEST_REQUIRE(presentationHandoff.presentedFrames() == 1);
    NOVA_TEST_REQUIRE(presentationSink->presentCalls == 1);
    NOVA_TEST_REQUIRE(presentationSink->lastDescriptor.width == 1280);
    NOVA_TEST_REQUIRE(presentationSink->lastDescriptor.height == 800);
    NOVA_TEST_REQUIRE(presentationSink->lastDescriptor.redrawRate == 60);
    NOVA_TEST_REQUIRE(presentationSink->lastDescriptor.surfaceId == 42);
    NOVA_TEST_REQUIRE(presentationSink->lastDescriptor.hardwareBacked);
    NOVA_TEST_REQUIRE(presentationSink->lastDescriptor.source == std::string("test-vaapi-surface"));
    NOVA_TEST_REQUIRE(!presentationHandoff.presentVaapiSurface(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = 0,
        .hardwareBacked = false,
        .source = "software-frame-rejected",
    }));
    NOVA_TEST_REQUIRE(presentationHandoff.presentedFrames() == 1);

    class TestableQtQuickRhiVaapiItem final : public DeckQtQuickRhiVaapiItem {
    public:
        using DeckQtQuickRhiVaapiItem::updatePaintNode;
    };

    auto makeFakeFrameLease = [](std::uintptr_t surfaceId) {
        AVFrame* fakeVaapiFrame = av_frame_alloc();
        if (fakeVaapiFrame == nullptr) {
            return std::shared_ptr<DeckQrhiVaapiFrameLease>{};
        }
        fakeVaapiFrame->format = AV_PIX_FMT_VAAPI;
        fakeVaapiFrame->buf[0] = av_buffer_alloc(1);
        if (fakeVaapiFrame->buf[0] == nullptr) {
            av_frame_free(&fakeVaapiFrame);
            return std::shared_ptr<DeckQrhiVaapiFrameLease>{};
        }
        fakeVaapiFrame->data[3] = reinterpret_cast<std::uint8_t*>(surfaceId);
        std::shared_ptr<DeckQrhiVaapiFrameLease> lease = DeckQrhiVaapiFrameLease::cloneHardwareFrame(*fakeVaapiFrame);
        av_frame_free(&fakeVaapiFrame);
        return lease;
    };

    DeckQrhiVaapiPresentationHandoff previewHandoff;
    auto previewSink = std::make_shared<RecordingPresentationSink>();
    previewHandoff.setSink(previewSink);
    DeckVaapiPreviewFramePump previewFramePump(previewHandoff);
    std::shared_ptr<DeckQrhiVaapiFrameLease> previewFrame1 = makeFakeFrameLease(0x101);
    std::shared_ptr<DeckQrhiVaapiFrameLease> previewFrame2 = makeFakeFrameLease(0x102);
    std::weak_ptr<DeckQrhiVaapiFrameLease> previewFrame1Weak = previewFrame1;
    std::weak_ptr<DeckQrhiVaapiFrameLease> previewFrame2Weak = previewFrame2;
    NOVA_TEST_REQUIRE(previewFramePump.enqueueDecodedFrame(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = previewFrame1->surfaceId(),
        .hardwareBacked = true,
        .frameLease = previewFrame1,
        .source = "preview-fixture-older",
    }));
    previewFrame1.reset();
    NOVA_TEST_REQUIRE(!previewFrame1Weak.expired());
    NOVA_TEST_REQUIRE(previewFramePump.enqueueDecodedFrame(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = previewFrame2->surfaceId(),
        .hardwareBacked = true,
        .frameLease = previewFrame2,
        .source = "preview-fixture-newest",
    }));
    previewFrame2.reset();
    NOVA_TEST_REQUIRE(previewFrame1Weak.expired());
    NOVA_TEST_REQUIRE(!previewFrame2Weak.expired());
    NOVA_TEST_REQUIRE(previewFramePump.queuedFrames() == 2);
    NOVA_TEST_REQUIRE(previewFramePump.coalescedFrames() == 1);
    NOVA_TEST_REQUIRE(previewFramePump.pendingFrames() == 1);
    NOVA_TEST_REQUIRE(previewFramePump.flushNewest());
    NOVA_TEST_REQUIRE(previewFramePump.flushedFrames() == 1);
    NOVA_TEST_REQUIRE(previewFramePump.pendingFrames() == 0);
    NOVA_TEST_REQUIRE(previewSink->presentCalls == 1);
    NOVA_TEST_REQUIRE(previewSink->lastDescriptor.surfaceId == 0x102);
    NOVA_TEST_REQUIRE(previewSink->lastDescriptor.source == std::string("preview-fixture-newest"));
    NOVA_TEST_REQUIRE(!previewFramePump.flushNewest());
    NOVA_TEST_REQUIRE(!previewFramePump.enqueueDecodedFrame(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = 0,
        .hardwareBacked = false,
        .source = "preview-invalid-reset",
    }));
    NOVA_TEST_REQUIRE(previewFramePump.invalidatedFrames() == 1);
    NOVA_TEST_REQUIRE(previewFramePump.pendingFrames() == 0);
    NOVA_TEST_REQUIRE(previewSink->presentCalls == 2);
    NOVA_TEST_REQUIRE(previewSink->lastDescriptor.source == std::string("preview-invalid-reset"));
    NOVA_TEST_REQUIRE(previewFrame2Weak.expired());

    auto productPreviewPipeline = std::make_shared<DeckProductPreviewPipeline>();
    auto productPreviewSink = std::make_shared<RecordingPresentationSink>();
    productPreviewPipeline->attachSink(productPreviewSink);
    NOVA_TEST_REQUIRE(!productPreviewPipeline->presentVaapiSurface(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = 0,
        .hardwareBacked = false,
        .source = "product-offline-preview-fixture-missing-lease",
    }));
    const auto missingLeaseReadiness = productPreviewPipeline->lastReadinessReport();
    NOVA_TEST_REQUIRE(missingLeaseReadiness.statusCode == std::string("missing-frame-lease"));
    NOVA_TEST_REQUIRE(!missingLeaseReadiness.ready);
    NOVA_TEST_REQUIRE(!missingLeaseReadiness.hardwarePresenterPlanned);
    NOVA_TEST_REQUIRE(missingLeaseReadiness.detail.find("fail-closed") != std::string::npos);
    NOVA_TEST_REQUIRE(productPreviewPipeline->invalidatedFrames() == 1);
    NOVA_TEST_REQUIRE(productPreviewPipeline->pendingFrames() == 0);
    NOVA_TEST_REQUIRE(productPreviewPipeline->presentedFrames() == 0);
    NOVA_TEST_REQUIRE(productPreviewSink->presentCalls == 1);
    NOVA_TEST_REQUIRE(productPreviewSink->lastDescriptor.source == std::string("product-offline-preview-fixture-missing-lease"));

    std::shared_ptr<DeckQrhiVaapiFrameLease> productFixtureFrame = makeFakeFrameLease(0x201);
    std::weak_ptr<DeckQrhiVaapiFrameLease> productFixtureFrameWeak = productFixtureFrame;
    NOVA_TEST_REQUIRE(productPreviewPipeline->presentVaapiSurface(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = productFixtureFrame->surfaceId(),
        .hardwareBacked = true,
        .frameLease = productFixtureFrame,
        .source = "product-decoded-preview-fixture-vaapi",
    }));
    const auto queuedFixtureReadiness = productPreviewPipeline->lastReadinessReport();
    productFixtureFrame.reset();
    NOVA_TEST_REQUIRE(queuedFixtureReadiness.statusCode == std::string("hardware-frame-ready"));
    NOVA_TEST_REQUIRE(queuedFixtureReadiness.hardwarePresenterPlanned);
    NOVA_TEST_REQUIRE(!queuedFixtureReadiness.ready);
    NOVA_TEST_REQUIRE(queuedFixtureReadiness.detail.find("product Deck preview pipeline") != std::string::npos);
    NOVA_TEST_REQUIRE(productPreviewPipeline->queuedFrames() == 1);
    NOVA_TEST_REQUIRE(productPreviewPipeline->flushedFrames() == 1);
    NOVA_TEST_REQUIRE(productPreviewPipeline->presentedFrames() == 1);
    NOVA_TEST_REQUIRE(productPreviewSink->lastDescriptor.surfaceId == 0x201);
    NOVA_TEST_REQUIRE(productPreviewSink->lastDescriptor.source == std::string("product-decoded-preview-fixture-vaapi"));
    NOVA_TEST_REQUIRE(!productFixtureFrameWeak.expired());

    DeckProductPreviewPipeline guardedStreamPipeline;
    auto guardedStreamSink = std::make_shared<RecordingPresentationSink>();
    guardedStreamPipeline.attachSink(guardedStreamSink);
    DeckGuardedStreamSessionPreviewProducer guardedStreamProducer;
    NOVA_TEST_REQUIRE(!guardedStreamProducer.moonlightBoundary().networkStartAllowed);
    guardedStreamProducer.attachProductPreviewPipeline(guardedStreamPipeline);
    std::shared_ptr<DeckQrhiVaapiFrameLease> guardedStreamFrame = makeFakeFrameLease(0x301);
    NOVA_TEST_REQUIRE(guardedStreamProducer.decodedFrameProducer().presentationHandoff().presentVaapiSurface(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = guardedStreamFrame->surfaceId(),
        .hardwareBacked = true,
        .frameLease = guardedStreamFrame,
        .source = "guarded-stream-session-producer",
    }));
    NOVA_TEST_REQUIRE(guardedStreamPipeline.presentedFrames() == 1);
    NOVA_TEST_REQUIRE(guardedStreamSink->lastDescriptor.surfaceId == 0x301);
    NOVA_TEST_REQUIRE(guardedStreamSink->lastDescriptor.source == std::string("guarded-stream-session-producer"));
    const auto guardedPrepared = guardedStreamProducer.prepareNoNetwork(DeckStreamRequest{
        .hostId = "offline-guarded-host",
        .gameId = "offline-guarded-game",
        .width = 1280,
        .height = 800,
        .fps = 60,
        .bitrateKbps = 20000,
    });
    NOVA_TEST_REQUIRE(guardedPrepared.state == DeckStreamSessionState::Preparing);
    NOVA_TEST_REQUIRE(!guardedPrepared.networkStarted);
    NOVA_TEST_REQUIRE(guardedStreamProducer.rendererLifecycle().setupCalls == 0);
    const int guardedSetup = guardedStreamProducer.moonlightBoundary().videoCallbacks->setup(
        VIDEO_FORMAT_H264,
        1280,
        800,
        60,
        guardedStreamProducer.moonlightBoundary().callbackContext,
        0);
    NOVA_TEST_REQUIRE(guardedSetup == (guardedStreamProducer.rendererLifecycle().runtimeVaapiDeviceAvailable ? DR_OK : DR_NEED_IDR));
    NOVA_TEST_REQUIRE(guardedStreamProducer.rendererLifecycle().setupCalls == 1);
    const auto guardedStarted = guardedStreamProducer.startNoNetwork();
    NOVA_TEST_REQUIRE(guardedStarted.state == DeckStreamSessionState::Active);
    NOVA_TEST_REQUIRE(!guardedStarted.networkStarted);
    guardedStreamProducer.stop();

    DeckProductPreviewPipeline guardedLifecyclePipeline;
    auto guardedLifecycleSink = std::make_shared<RecordingPresentationSink>();
    guardedLifecyclePipeline.attachSink(guardedLifecycleSink);
    DeckGuardedStreamSessionPreviewProducer guardedLifecycleProducer;
    DeckGuardedPreviewLifecycleGate guardedLifecycleGate(guardedLifecycleProducer);
    NOVA_TEST_REQUIRE(!guardedLifecycleGate.lastReport().networkStartAllowed);
    NOVA_TEST_REQUIRE(guardedLifecycleGate.lastReport().statusCode == std::string("idle-no-network"));
    guardedLifecycleGate.attachProductPreviewPipeline(guardedLifecyclePipeline);
    std::shared_ptr<DeckQrhiVaapiFrameLease> guardedLifecycleFrame = makeFakeFrameLease(0x302);
    NOVA_TEST_REQUIRE(guardedLifecycleProducer.decodedFrameProducer().presentationHandoff().presentVaapiSurface(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = guardedLifecycleFrame->surfaceId(),
        .hardwareBacked = true,
        .frameLease = guardedLifecycleFrame,
        .source = "guarded-preview-lifecycle-gate",
    }));
    NOVA_TEST_REQUIRE(guardedLifecyclePipeline.presentedFrames() == 1);
    NOVA_TEST_REQUIRE(guardedLifecycleSink->lastDescriptor.surfaceId == 0x302);
    const auto lifecycleArmed = guardedLifecycleGate.armNoNetwork(DeckStreamRequest{
        .hostId = "offline-guarded-host",
        .gameId = "offline-guarded-game",
        .width = 1280,
        .height = 800,
        .fps = 60,
        .bitrateKbps = 20000,
    });
    NOVA_TEST_REQUIRE(lifecycleArmed.state == DeckStreamSessionState::Active);
    NOVA_TEST_REQUIRE(lifecycleArmed.statusCode == std::string("active-no-network"));
    NOVA_TEST_REQUIRE(lifecycleArmed.hostId == std::string("offline-guarded-host"));
    NOVA_TEST_REQUIRE(lifecycleArmed.gameId == std::string("offline-guarded-game"));
    NOVA_TEST_REQUIRE(lifecycleArmed.width == 1280);
    NOVA_TEST_REQUIRE(lifecycleArmed.height == 800);
    NOVA_TEST_REQUIRE(lifecycleArmed.fps == 60);
    NOVA_TEST_REQUIRE(lifecycleArmed.bitrateKbps == 20000);
    NOVA_TEST_REQUIRE(lifecycleArmed.prepared);
    NOVA_TEST_REQUIRE(lifecycleArmed.armed);
    NOVA_TEST_REQUIRE(!lifecycleArmed.networkStartAllowed);
    NOVA_TEST_REQUIRE(!lifecycleArmed.networkStarted);
    NOVA_TEST_REQUIRE(lifecycleArmed.reason.find("offline-guarded-host") == std::string::npos);
    NOVA_TEST_REQUIRE(lifecycleArmed.reason.find("token") == std::string::npos);
    NOVA_TEST_REQUIRE(lifecycleArmed.reason.find("credential") == std::string::npos);
    NOVA_TEST_REQUIRE(lifecycleArmed.transitionCount >= 3);
    NOVA_TEST_REQUIRE(guardedLifecycleProducer.rendererLifecycle().setupCalls == 0);
    const auto hostStartBlocked = guardedLifecycleGate.requestGuardedHostNetworkStart();
    NOVA_TEST_REQUIRE(hostStartBlocked.state == DeckStreamSessionState::Active);
    NOVA_TEST_REQUIRE(hostStartBlocked.statusCode == std::string("host-network-start-blocked"));
    NOVA_TEST_REQUIRE(hostStartBlocked.prepared);
    NOVA_TEST_REQUIRE(hostStartBlocked.armed);
    NOVA_TEST_REQUIRE(hostStartBlocked.hostStartBoundaryExplicit);
    NOVA_TEST_REQUIRE(hostStartBlocked.hostStartContractAuthorized == false);
    NOVA_TEST_REQUIRE(!hostStartBlocked.networkStartAllowed);
    NOVA_TEST_REQUIRE(!hostStartBlocked.networkStarted);
    NOVA_TEST_REQUIRE(hostStartBlocked.reason.find("operator authorization") != std::string::npos);
    NOVA_TEST_REQUIRE(hostStartBlocked.reason.find("offline-guarded-host") == std::string::npos);
    NOVA_TEST_REQUIRE(hostStartBlocked.reason.find("token") == std::string::npos);
    NOVA_TEST_REQUIRE(hostStartBlocked.reason.find("credential") == std::string::npos);
    NOVA_TEST_REQUIRE(guardedLifecycleProducer.rendererLifecycle().setupCalls == 0);

    DeckOperatorStartAuthorizationPolicy defaultOperatorPolicy;
    const auto defaultAuthorization = defaultOperatorPolicy.snapshot();
    NOVA_TEST_REQUIRE(defaultAuthorization.mode == DeckOperatorStartAuthorizationMode::Blocked);
    NOVA_TEST_REQUIRE(defaultAuthorization.statusCode == std::string("operator-start-blocked"));
    NOVA_TEST_REQUIRE(defaultAuthorization.dryRunAuthorized == false);
    NOVA_TEST_REQUIRE(defaultAuthorization.startAuthorized == false);
    NOVA_TEST_REQUIRE(defaultAuthorization.tokenless);
    NOVA_TEST_REQUIRE(defaultAuthorization.opaqueLocalStateId.empty());
    NOVA_TEST_REQUIRE(defaultAuthorization.networkStarted == false);

    const auto defaultDryRunDenied = guardedLifecycleGate.requestOperatorAuthorizedDryRun(defaultAuthorization);
    NOVA_TEST_REQUIRE(defaultDryRunDenied.statusCode == std::string("operator-dry-run-blocked"));
    NOVA_TEST_REQUIRE(defaultDryRunDenied.operatorAuthorizationState == std::string("blocked"));
    NOVA_TEST_REQUIRE(defaultDryRunDenied.hostStartContractAuthorized == false);
    NOVA_TEST_REQUIRE(defaultDryRunDenied.networkStarted == false);
    NOVA_TEST_REQUIRE(guardedLifecycleProducer.rendererLifecycle().setupCalls == 0);

    DeckOperatorStartAuthorizationPolicy dryRunOperatorPolicy;
    dryRunOperatorPolicy.authorizeDryRun("local-operator-dry-run-ok");
    const auto dryRunAuthorization = dryRunOperatorPolicy.snapshot();
    NOVA_TEST_REQUIRE(dryRunAuthorization.mode == DeckOperatorStartAuthorizationMode::DryRunAuthorized);
    NOVA_TEST_REQUIRE(dryRunAuthorization.statusCode == std::string("operator-dry-run-authorized"));
    NOVA_TEST_REQUIRE(dryRunAuthorization.dryRunAuthorized);
    NOVA_TEST_REQUIRE(!dryRunAuthorization.startAuthorized);
    NOVA_TEST_REQUIRE(dryRunAuthorization.tokenless);
    NOVA_TEST_REQUIRE(dryRunAuthorization.opaqueLocalStateId == std::string("local-operator-dry-run-ok"));
    const auto dryRunApproved = guardedLifecycleGate.requestOperatorAuthorizedDryRun(dryRunAuthorization);
    NOVA_TEST_REQUIRE(dryRunApproved.statusCode == std::string("operator-dry-run-authorized"));
    NOVA_TEST_REQUIRE(dryRunApproved.operatorAuthorizationState == std::string("dry-run-authorized"));
    NOVA_TEST_REQUIRE(dryRunApproved.hostStartBoundaryExplicit);
    NOVA_TEST_REQUIRE(dryRunApproved.hostStartContractAuthorized == false);
    NOVA_TEST_REQUIRE(!dryRunApproved.networkStartAllowed);
    NOVA_TEST_REQUIRE(!dryRunApproved.networkStarted);
    NOVA_TEST_REQUIRE(guardedLifecycleProducer.rendererLifecycle().setupCalls == 0);

    DeckOperatorStartAuthorizationPolicy startOperatorPolicy;
    startOperatorPolicy.authorizeStart("local-operator-start-ok");
    const auto startAuthorization = startOperatorPolicy.snapshot();
    NOVA_TEST_REQUIRE(startAuthorization.mode == DeckOperatorStartAuthorizationMode::StartAuthorized);
    NOVA_TEST_REQUIRE(startAuthorization.statusCode == std::string("operator-start-authorized"));
    NOVA_TEST_REQUIRE(startAuthorization.dryRunAuthorized);
    NOVA_TEST_REQUIRE(startAuthorization.startAuthorized);
    NOVA_TEST_REQUIRE(startAuthorization.tokenless);
    NOVA_TEST_REQUIRE(startAuthorization.opaqueLocalStateId == std::string("local-operator-start-ok"));
    const auto startNotReady = guardedLifecycleGate.requestOperatorAuthorizedHostNetworkStart(startAuthorization);
    NOVA_TEST_REQUIRE(startNotReady.statusCode == std::string("operator-start-not-ready"));
    NOVA_TEST_REQUIRE(startNotReady.operatorAuthorizationState == std::string("start-authorized"));
    NOVA_TEST_REQUIRE(startNotReady.hostStartBoundaryExplicit);
    NOVA_TEST_REQUIRE(startNotReady.hostStartContractAuthorized);
    NOVA_TEST_REQUIRE(!startNotReady.networkStartAllowed);
    NOVA_TEST_REQUIRE(!startNotReady.networkStarted);
    NOVA_TEST_REQUIRE(startNotReady.reason.find("external host readiness") != std::string::npos);
    NOVA_TEST_REQUIRE(startNotReady.reason.find("local-operator-start-ok") == std::string::npos);
    NOVA_TEST_REQUIRE(guardedLifecycleProducer.rendererLifecycle().setupCalls == 0);

    const DeckStreamRequest selectedHostPreflightRequest{
        .hostId = "offline-preflight-host",
        .gameId = "offline-preflight-game",
        .width = 1280,
        .height = 800,
        .fps = 60,
        .bitrateKbps = 20000,
    };
    const auto missingHostPreflight = guardedLifecycleGate.requestHostStartDryRunPreflight(
        startAuthorization,
        DeckStreamRequest{
            .hostId = "",
            .gameId = "offline-preflight-game",
            .width = 1280,
            .height = 800,
            .fps = 60,
            .bitrateKbps = 20000,
        });
    NOVA_TEST_REQUIRE(missingHostPreflight.statusCode == std::string("host-start-preflight-missing-host"));
    NOVA_TEST_REQUIRE(missingHostPreflight.hostId.empty());
    NOVA_TEST_REQUIRE(missingHostPreflight.gameId == std::string("offline-preflight-game"));
    NOVA_TEST_REQUIRE(missingHostPreflight.dryRunPreflightRequested);
    NOVA_TEST_REQUIRE(!missingHostPreflight.hostStartContractAuthorized);
    NOVA_TEST_REQUIRE(!missingHostPreflight.networkStartAllowed);
    NOVA_TEST_REQUIRE(!missingHostPreflight.networkStarted);
    NOVA_TEST_REQUIRE(missingHostPreflight.reason.find("missing host selection") != std::string::npos);

    const auto blockedPreflight = guardedLifecycleGate.requestHostStartDryRunPreflight(
        defaultAuthorization,
        selectedHostPreflightRequest);
    NOVA_TEST_REQUIRE(blockedPreflight.statusCode == std::string("host-start-preflight-contract-blocked"));
    NOVA_TEST_REQUIRE(blockedPreflight.hostId == std::string("offline-preflight-host"));
    NOVA_TEST_REQUIRE(blockedPreflight.gameId == std::string("offline-preflight-game"));
    NOVA_TEST_REQUIRE(blockedPreflight.width == 1280);
    NOVA_TEST_REQUIRE(blockedPreflight.height == 800);
    NOVA_TEST_REQUIRE(blockedPreflight.fps == 60);
    NOVA_TEST_REQUIRE(blockedPreflight.bitrateKbps == 20000);
    NOVA_TEST_REQUIRE(blockedPreflight.dryRunPreflightRequested);
    NOVA_TEST_REQUIRE(!blockedPreflight.hostStartContractAuthorized);
    NOVA_TEST_REQUIRE(!blockedPreflight.networkStartAllowed);
    NOVA_TEST_REQUIRE(!blockedPreflight.networkStarted);
    NOVA_TEST_REQUIRE(blockedPreflight.reason.find("operator start contract") != std::string::npos);
    NOVA_TEST_REQUIRE(blockedPreflight.reason.find("offline-preflight-host") == std::string::npos);
    NOVA_TEST_REQUIRE(blockedPreflight.reason.find("token") == std::string::npos);

    const auto authorizedPreflight = guardedLifecycleGate.requestHostStartDryRunPreflight(
        startAuthorization,
        selectedHostPreflightRequest);
    NOVA_TEST_REQUIRE(authorizedPreflight.statusCode == std::string("host-start-dry-run-preflight-authorized"));
    NOVA_TEST_REQUIRE(authorizedPreflight.hostId == std::string("offline-preflight-host"));
    NOVA_TEST_REQUIRE(authorizedPreflight.gameId == std::string("offline-preflight-game"));
    NOVA_TEST_REQUIRE(authorizedPreflight.dryRunPreflightRequested);
    NOVA_TEST_REQUIRE(authorizedPreflight.hostStartContractAuthorized);
    NOVA_TEST_REQUIRE(!authorizedPreflight.networkStartAllowed);
    NOVA_TEST_REQUIRE(!authorizedPreflight.networkStarted);
    NOVA_TEST_REQUIRE(authorizedPreflight.reason.find("report-only") != std::string::npos);
    NOVA_TEST_REQUIRE(authorizedPreflight.reason.find("offline-preflight-host") == std::string::npos);
    NOVA_TEST_REQUIRE(guardedLifecycleProducer.rendererLifecycle().setupCalls == 0);

    const auto realStartStillUnavailable = guardedLifecycleGate.requestOperatorAuthorizedHostNetworkStart(startAuthorization);
    NOVA_TEST_REQUIRE(realStartStillUnavailable.statusCode == std::string("operator-start-not-ready"));
    NOVA_TEST_REQUIRE(!realStartStillUnavailable.dryRunPreflightRequested);
    NOVA_TEST_REQUIRE(!realStartStillUnavailable.networkStartAllowed);
    NOVA_TEST_REQUIRE(!realStartStillUnavailable.networkStarted);
    NOVA_TEST_REQUIRE(realStartStillUnavailable.reason.find("network disabled") != std::string::npos);
    const auto lifecycleStopped = guardedLifecycleGate.stop();
    NOVA_TEST_REQUIRE(lifecycleStopped.state == DeckStreamSessionState::Stopped);
    NOVA_TEST_REQUIRE(lifecycleStopped.statusCode == std::string("stopped-no-network"));
    NOVA_TEST_REQUIRE(!lifecycleStopped.dryRunPreflightRequested);
    NOVA_TEST_REQUIRE(!lifecycleStopped.networkStarted);
    NOVA_TEST_REQUIRE(guardedLifecycleGate.transitions().size() >= 4);

    DeckGuardedStreamSessionPreviewProducer idempotentLifecycleProducer;
    DeckGuardedPreviewLifecycleGate idempotentLifecycleGate(idempotentLifecycleProducer);
    const auto idempotentFirstArm = idempotentLifecycleGate.armNoNetwork(DeckStreamRequest{
        .hostId = "offline-guarded-host",
        .gameId = "offline-guarded-game",
        .width = 1280,
        .height = 800,
        .fps = 60,
        .bitrateKbps = 20000,
    });
    NOVA_TEST_REQUIRE(idempotentFirstArm.statusCode == std::string("active-no-network"));
    const auto idempotentTransitionCountAfterArm = idempotentLifecycleGate.transitions().size();
    const auto idempotentSecondArm = idempotentLifecycleGate.armNoNetwork(DeckStreamRequest{
        .hostId = "offline-guarded-host",
        .gameId = "offline-guarded-game",
        .width = 1280,
        .height = 800,
        .fps = 60,
        .bitrateKbps = 20000,
    });
    NOVA_TEST_REQUIRE(idempotentSecondArm.state == DeckStreamSessionState::Active);
    NOVA_TEST_REQUIRE(idempotentSecondArm.statusCode == std::string("already-active-no-network"));
    NOVA_TEST_REQUIRE(idempotentSecondArm.armed);
    NOVA_TEST_REQUIRE(!idempotentSecondArm.networkStartAllowed);
    NOVA_TEST_REQUIRE(!idempotentSecondArm.networkStarted);
    NOVA_TEST_REQUIRE(idempotentLifecycleGate.transitions().size() == idempotentTransitionCountAfterArm);
    const auto idempotentFirstStop = idempotentLifecycleGate.stop();
    NOVA_TEST_REQUIRE(idempotentFirstStop.statusCode == std::string("stopped-no-network"));
    const auto idempotentTransitionCountAfterStop = idempotentLifecycleGate.transitions().size();
    const auto idempotentSecondStop = idempotentLifecycleGate.stop();
    NOVA_TEST_REQUIRE(idempotentSecondStop.state == DeckStreamSessionState::Stopped);
    NOVA_TEST_REQUIRE(idempotentSecondStop.statusCode == std::string("already-stopped-no-network"));
    NOVA_TEST_REQUIRE(!idempotentSecondStop.armed);
    NOVA_TEST_REQUIRE(!idempotentSecondStop.networkStarted);
    NOVA_TEST_REQUIRE(idempotentLifecycleGate.transitions().size() == idempotentTransitionCountAfterStop);

    AVFrame* fakeVaapiFrame = av_frame_alloc();
    if (!require(fakeVaapiFrame != nullptr, "expected test VAAPI frame allocation")) {
        return 1;
    }
    fakeVaapiFrame->format = AV_PIX_FMT_VAAPI;
    fakeVaapiFrame->buf[0] = av_buffer_alloc(1);
    if (!require(fakeVaapiFrame->buf[0] != nullptr, "expected test VAAPI frame buffer allocation")) {
        av_frame_free(&fakeVaapiFrame);
        return 1;
    }
    fakeVaapiFrame->data[3] = reinterpret_cast<std::uint8_t*>(0x2a);
    std::shared_ptr<DeckQrhiVaapiFrameLease> itemFrameLease = DeckQrhiVaapiFrameLease::cloneHardwareFrame(*fakeVaapiFrame);
    av_frame_free(&fakeVaapiFrame);
    if (!require(itemFrameLease != nullptr, "expected test VAAPI frame lease to clone")) {
        return 1;
    }
    std::weak_ptr<DeckQrhiVaapiFrameLease> itemFrameLeaseWeak = itemFrameLease;
    auto vaapiItem = std::make_shared<TestableQtQuickRhiVaapiItem>();
    DeckQrhiVaapiPresentationHandoff qtQuickPresentationHandoff;
    qtQuickPresentationHandoff.setSink(vaapiItem);
    if (!require(qtQuickPresentationHandoff.presentVaapiSurface(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = itemFrameLease->surfaceId(),
        .hardwareBacked = true,
        .frameLease = itemFrameLease,
        .source = "qt-quick-rhi-item-test",
    }), "expected handoff to accept the Qt Quick VAAPI item as a sink")) {
        return 1;
    }
    if (!require(qtQuickPresentationHandoff.presentedFrames() == 1, "expected handoff presented frame count")) {
        return 1;
    }
    if (!require(vaapiItem->presentedFrames() == 1, "expected Qt Quick VAAPI item presented frame count")) {
        return 1;
    }
    itemFrameLease.reset();
    if (!require(!itemFrameLeaseWeak.expired(), "expected Qt Quick item to retain frame lease before scene graph update")) {
        return 1;
    }
    QSGNode* sceneGraphNode = vaapiItem->updatePaintNode(nullptr, nullptr);
    if (!require(sceneGraphNode != nullptr, "expected Qt Quick item to create a scene graph node")) {
        return 1;
    }
    if (!require(sceneGraphNode->type() == QSGNode::RenderNodeType, "expected Qt Quick item to create a QSGRenderNode")) {
        delete sceneGraphNode;
        return 1;
    }
    auto* vaapiRenderNode = static_cast<DeckQtQuickRhiVaapiRenderNode*>(sceneGraphNode);
    if (!require(vaapiRenderNode->descriptor().width == 1280, "expected render node width descriptor")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->descriptor().height == 800, "expected render node height descriptor")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->descriptor().surfaceId == 42, "expected render node VAAPI surface id")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->descriptor().source == std::string("qt-quick-rhi-item-test"), "expected render node descriptor source")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->hasFrameLease(), "expected render node to retain frame lease")) {
        delete sceneGraphNode;
        return 1;
    }
    const auto fakeDrmPrimeExport = vaapiRenderNode->descriptor().frameLease->exportDrmPrimeDescriptor();
    if (!require(fakeDrmPrimeExport.status == DeckQrhiVaapiImportStatus::MissingHardwareFramesContext,
            "expected fake VAAPI frame to report missing hardware frames context before DRM_PRIME export")) {
        delete sceneGraphNode;
        return 1;
    }
    const auto missingRenderStatePlan = vaapiRenderNode->planQrhiImport(nullptr);
    if (!require(missingRenderStatePlan.status == DeckQrhiVaapiImportStatus::MissingRenderState,
            "expected null render state to block QRhi import planning")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->lastReadinessReport().state == DeckVaapiPresenterReadinessState::NotAttempted,
            "expected render node readiness report to start as not attempted")) {
        delete sceneGraphNode;
        return 1;
    }
    nova::deck::stream::DeckQrhiVaapiDrmPrimeDescriptor testDrmPrimeDescriptor;
    testDrmPrimeDescriptor.status = DeckQrhiVaapiImportStatus::DrmPrimeExported;
    testDrmPrimeDescriptor.objectCount = 1;
    testDrmPrimeDescriptor.layerCount = 1;
    testDrmPrimeDescriptor.objects[0].fd = 0;
    testDrmPrimeDescriptor.layers[0].format = 0x34325258; // DRM_FORMAT_XRGB8888
    testDrmPrimeDescriptor.layers[0].planeCount = 1;
    testDrmPrimeDescriptor.layers[0].planes[0].objectIndex = 0;
    testDrmPrimeDescriptor.layers[0].planes[0].pitch = 5120;
    const auto missingTargetPlan = DeckVaapiEglImagePresenter::planOpenGlTextureImport(nullptr, testDrmPrimeDescriptor, QSize(1280, 800));
    if (!require(missingTargetPlan.status == DeckQrhiVaapiImportStatus::DeckTargetUnavailable,
            "expected EGLImage presenter to require a Deck Qt Quick target window")) {
        delete sceneGraphNode;
        return 1;
    }
    const auto missingTargetReadiness = DeckVaapiEglImagePresenter::readinessReportForPlan(missingTargetPlan);
    if (!require(missingTargetReadiness.state == DeckVaapiPresenterReadinessState::DeckTargetUnavailable,
            "expected readiness report to preserve missing Deck target diagnostics")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(missingTargetReadiness.statusCode == std::string("deck-target-unavailable"),
            "expected stable missing Deck target status code")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(!missingTargetReadiness.ready && !missingTargetReadiness.hardwarePresenterPlanned,
            "expected missing Deck target readiness to stay not ready and not planned")) {
        delete sceneGraphNode;
        return 1;
    }
    const auto frameBoundMissingTargetReadiness = DeckVaapiEglImagePresenter::readinessReportForDecodedFrameProof(
        testDrmPrimeDescriptor,
        missingTargetPlan);
    if (!require(frameBoundMissingTargetReadiness.state == DeckVaapiPresenterReadinessState::DeckTargetUnavailable,
            "expected decoded frame proof to bind to missing target readiness")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(frameBoundMissingTargetReadiness.hardwarePresenterPlanned && !frameBoundMissingTargetReadiness.ready,
            "expected decoded frame plus missing target to stay planned but not texture-ready")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(frameBoundMissingTargetReadiness.importPlan.drmPrimeObjectCount == 1 &&
            frameBoundMissingTargetReadiness.importPlan.drmPrimeLayerCount == 1,
            "expected decoded frame bound readiness to preserve DRM_PRIME object/layer counts")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(frameBoundMissingTargetReadiness.detail.find("Hardware-backed VAAPI frame decoded") != std::string::npos &&
            frameBoundMissingTargetReadiness.detail.find("Qt Quick target") != std::string::npos,
            "expected decoded frame bound readiness to explain both frame proof and render-target gate")) {
        delete sceneGraphNode;
        return 1;
    }
    nova::deck::stream::DeckQrhiVaapiDrmPrimeDescriptor incompleteDrmPrimeDescriptor;
    incompleteDrmPrimeDescriptor.status = DeckQrhiVaapiImportStatus::DrmPrimeExported;
    const auto incompleteMetadataPlan = DeckVaapiEglImagePresenter::validateDrmPrimeMetadata(incompleteDrmPrimeDescriptor);
    if (!require(incompleteMetadataPlan.status == DeckQrhiVaapiImportStatus::IncompleteDrmPrimeMetadata,
            "expected EGLImage presenter to reject incomplete DRM_PRIME plane metadata")) {
        delete sceneGraphNode;
        return 1;
    }
    nova::deck::stream::DeckQrhiVaapiDrmPrimeDescriptor multiLayerDrmPrimeDescriptor;
    multiLayerDrmPrimeDescriptor.status = DeckQrhiVaapiImportStatus::DrmPrimeExported;
    multiLayerDrmPrimeDescriptor.objectCount = 1;
    multiLayerDrmPrimeDescriptor.layerCount = 2;
    multiLayerDrmPrimeDescriptor.objects[0].fd = 0;
    multiLayerDrmPrimeDescriptor.layers[0].format = 0x20203852; // DRM_FORMAT_R8
    multiLayerDrmPrimeDescriptor.layers[0].planeCount = 1;
    multiLayerDrmPrimeDescriptor.layers[0].planes[0].objectIndex = 0;
    multiLayerDrmPrimeDescriptor.layers[0].planes[0].pitch = 1280;
    multiLayerDrmPrimeDescriptor.layers[1].format = 0x38385247; // DRM_FORMAT_GR88
    multiLayerDrmPrimeDescriptor.layers[1].planeCount = 1;
    multiLayerDrmPrimeDescriptor.layers[1].planes[0].objectIndex = 0;
    multiLayerDrmPrimeDescriptor.layers[1].planes[0].pitch = 1280;
    const auto multiLayerMetadataPlan = DeckVaapiEglImagePresenter::validateDrmPrimeMetadata(multiLayerDrmPrimeDescriptor);
    if (!require(multiLayerMetadataPlan.status == DeckQrhiVaapiImportStatus::DrmPrimeExported,
            "expected EGLImage presenter to accept the real Deck two-layer Y/UV DRM_PRIME shape")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(multiLayerMetadataPlan.detail.find("2-layer DRM_PRIME") != std::string::npos &&
            multiLayerMetadataPlan.detail.find("YUV") != std::string::npos &&
            multiLayerMetadataPlan.detail.find("shader") != std::string::npos,
            "expected multi-layer plan to document two EGLImages and shader composition")) {
        delete sceneGraphNode;
        return 1;
    }
    const auto multiLayerReadiness = DeckVaapiEglImagePresenter::readinessReportForPlan(multiLayerMetadataPlan);
    if (!require(multiLayerReadiness.state == DeckVaapiPresenterReadinessState::HardwarePresenterPlanned,
            "expected accepted two-layer DRM_PRIME metadata to report hardware presenter planned")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(multiLayerReadiness.statusCode == std::string("hardware-presenter-planned"),
            "expected stable planned readiness status code for two-layer DRM_PRIME")) {
        delete sceneGraphNode;
        return 1;
    }
    DeckVaapiEglImagePresenter::Resource noContextPresenterResource;
    const auto noContextImportPlan = DeckVaapiEglImagePresenter::importOpenGlTextureForCurrentContext(
        multiLayerDrmPrimeDescriptor,
        QSize(1280, 800),
        noContextPresenterResource);
    if (!require(noContextImportPlan.status == DeckQrhiVaapiImportStatus::MissingRenderContext,
            "expected current-context live import smoke to fail closed with a distinct missing render context status")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(noContextImportPlan.detail.find("No current EGL display/context") != std::string::npos,
            "expected current-context live import failure to name the missing EGL context capability")) {
        delete sceneGraphNode;
        return 1;
    }
    multiLayerDrmPrimeDescriptor.layers[1].format = 0x34325258; // DRM_FORMAT_XRGB8888
    const auto unsupportedMultiLayerFormatPlan = DeckVaapiEglImagePresenter::validateDrmPrimeMetadata(multiLayerDrmPrimeDescriptor);
    if (!require(unsupportedMultiLayerFormatPlan.status == DeckQrhiVaapiImportStatus::UnsupportedDrmPrimeFormat,
            "expected non-Y/UV two-layer DRM_PRIME formats to fail closed with an explicit status")) {
        delete sceneGraphNode;
        return 1;
    }
    multiLayerDrmPrimeDescriptor.layerCount = 3;
    multiLayerDrmPrimeDescriptor.layers[1].format = 0x38385247; // DRM_FORMAT_GR88
    multiLayerDrmPrimeDescriptor.layers[2] = multiLayerDrmPrimeDescriptor.layers[1];
    const auto unsupportedThreeLayerPlan = DeckVaapiEglImagePresenter::validateDrmPrimeMetadata(multiLayerDrmPrimeDescriptor);
    if (!require(unsupportedThreeLayerPlan.status == DeckQrhiVaapiImportStatus::UnsupportedMultiLayerDrmPrimeImport,
            "expected more-than-two-layer DRM_PRIME descriptors to fail closed without truncating layers")) {
        delete sceneGraphNode;
        return 1;
    }
    const std::array<std::pair<DeckQrhiVaapiImportStatus, std::string>, 10> presenterFailureCodes{{
        {DeckQrhiVaapiImportStatus::UnsupportedNonOpenGlSceneGraph, "unsupported-non-opengl-scene-graph"},
        {DeckQrhiVaapiImportStatus::MissingEglDmabufExtensions, "missing-egl-dmabuf-extensions"},
        {DeckQrhiVaapiImportStatus::MissingRenderContext, "missing-render-context"},
        {DeckQrhiVaapiImportStatus::IncompleteDrmPrimeMetadata, "incomplete-drm-prime-metadata"},
        {DeckQrhiVaapiImportStatus::UnsupportedMultiLayerDrmPrimeImport, "unsupported-multilayer-drm-prime-import"},
        {DeckQrhiVaapiImportStatus::UnsupportedDrmPrimeFormat, "unsupported-drm-prime-format"},
        {DeckQrhiVaapiImportStatus::EglImageCreationFailed, "eglimage-creation-failed"},
        {DeckQrhiVaapiImportStatus::GlTextureBindFailed, "gl-texture-bind-failed"},
        {DeckQrhiVaapiImportStatus::EglImageShaderCompositionFailed, "eglimage-shader-composition-failed"},
        {DeckQrhiVaapiImportStatus::MissingFrameLease, "missing-frame-lease"},
    }};
    for (const auto& [status, statusCode] : presenterFailureCodes) {
        const auto readiness = DeckVaapiEglImagePresenter::readinessReportForPlan(DeckQrhiVaapiImportPlan{
            .status = status,
            .detail = statusCode,
        });
        if (!require(readiness.statusCode == statusCode, "expected distinct presenter readiness failure status code")) {
            delete sceneGraphNode;
            return 1;
        }
        if (!require(!readiness.ready && !readiness.hardwarePresenterPlanned, "expected presenter failure not to report ready/planned")) {
            delete sceneGraphNode;
            return 1;
        }
    }
    const auto plannedReadiness = DeckVaapiEglImagePresenter::readinessReportForPlan(DeckQrhiVaapiImportPlan{
        .status = DeckQrhiVaapiImportStatus::DrmPrimeExported,
        .drmPrimeObjectCount = 1,
        .drmPrimeLayerCount = 1,
        .detail = "DRM_PRIME dmabuf metadata is complete for EGLImage import",
    });
    if (!require(plannedReadiness.state == DeckVaapiPresenterReadinessState::HardwarePresenterPlanned,
            "expected exported DRM_PRIME plan to report hardware presenter planning success")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(plannedReadiness.statusCode == std::string("hardware-presenter-planned"),
            "expected stable hardware presenter planned status code")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(!plannedReadiness.ready && plannedReadiness.hardwarePresenterPlanned,
            "expected planned presenter readiness to be planned but not texture-ready yet")) {
        delete sceneGraphNode;
        return 1;
    }
    DeckVaapiEglImagePresenter::Resource sourcePresenterResource;
    sourcePresenterResource.glProgram = 42;
    DeckVaapiEglImagePresenter::Resource readyPresenterResource;
    readyPresenterResource.qtTexture = reinterpret_cast<QSGTexture*>(0x1);
    readyPresenterResource.eglImage = reinterpret_cast<void*>(0x2);
    readyPresenterResource.glTexture = 7;
    readyPresenterResource.shaderCompositionDetail = "test shader proof not attempted";
    const auto importedButUncomposedReadiness = DeckVaapiEglImagePresenter::readinessReportForResource(plannedReadiness.importPlan, readyPresenterResource);
    if (!require(importedButUncomposedReadiness.state == DeckVaapiPresenterReadinessState::HardwarePresenterPlanned,
            "expected imported EGLImage texture to stay planned until shader composition proof passes")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(!importedButUncomposedReadiness.ready && importedButUncomposedReadiness.hardwarePresenterPlanned,
            "expected imported but uncomposed presenter not to become texture-ready")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(importedButUncomposedReadiness.detail.find("test shader proof not attempted") != std::string::npos,
            "expected uncomposed readiness to preserve the exact shader composition failure detail")) {
        delete sceneGraphNode;
        return 1;
    }
    readyPresenterResource.shaderCompositionProved = true;
    const auto readyReadiness = DeckVaapiEglImagePresenter::readinessReportForResource(plannedReadiness.importPlan, readyPresenterResource);
    readyPresenterResource.qtTexture = nullptr;
    readyPresenterResource.eglImage = nullptr;
    readyPresenterResource.glTexture = 0;
    if (!require(readyReadiness.state == DeckVaapiPresenterReadinessState::Ready,
            "expected imported resource to report ready hardware presenter")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(readyReadiness.statusCode == std::string("ready"),
            "expected stable ready presenter status code")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(readyReadiness.ready && readyReadiness.hardwarePresenterPlanned,
            "expected ready presenter to be both ready and planned")) {
        delete sceneGraphNode;
        return 1;
    }
    DeckVaapiEglImagePresenter::Resource targetPresenterResource;
    targetPresenterResource = std::move(sourcePresenterResource);
    if (!require(sourcePresenterResource.glProgram == 0,
            "expected moved-from EGLImage presenter resource to release GL program ownership")) {
        targetPresenterResource.glProgram = 0;
        delete sceneGraphNode;
        return 1;
    }
    targetPresenterResource.glProgram = 0;
    vaapiRenderNode->render(nullptr);
    if (!require(vaapiRenderNode->lastImportPlan().status == DeckQrhiVaapiImportStatus::MissingRenderState,
            "expected render node to retain last failed QRhi import plan")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->lastReadinessReport().state == DeckVaapiPresenterReadinessState::MissingRenderState,
            "expected render node to convert the actual render failure into a readiness report")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->lastReadinessReport().statusCode == std::string("missing-render-state"),
            "expected render node readiness report to expose a stable failure code")) {
        delete sceneGraphNode;
        return 1;
    }
    std::shared_ptr<DeckQrhiVaapiFrameLease> replacementFrameLease = makeFakeFrameLease(0x2b);
    if (!require(replacementFrameLease != nullptr, "expected replacement VAAPI frame lease to clone")) {
        delete sceneGraphNode;
        return 1;
    }
    std::weak_ptr<DeckQrhiVaapiFrameLease> replacementFrameLeaseWeak = replacementFrameLease;
    if (!require(qtQuickPresentationHandoff.presentVaapiSurface(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = replacementFrameLease->surfaceId(),
        .hardwareBacked = true,
        .frameLease = replacementFrameLease,
        .source = "qt-quick-rhi-item-test-replacement",
    }), "expected handoff to accept a consecutive Qt Quick VAAPI frame")) {
        delete sceneGraphNode;
        return 1;
    }
    replacementFrameLease.reset();
    QSGNode* replacementSceneGraphNode = vaapiItem->updatePaintNode(sceneGraphNode, nullptr);
    if (!require(replacementSceneGraphNode == sceneGraphNode,
            "expected consecutive VAAPI frames to update the existing render node instead of replacing the QSG node")) {
        delete replacementSceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->descriptor().surfaceId == 43,
            "expected consecutive render node descriptor to switch to the replacement VAAPI surface")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->descriptor().source == std::string("qt-quick-rhi-item-test-replacement"),
            "expected consecutive render node descriptor to preserve the replacement source")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(itemFrameLeaseWeak.expired(), "expected render node replacement to release the prior frame lease immediately")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(!replacementFrameLeaseWeak.expired(), "expected render node replacement to retain only the newest frame lease")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->lastImportPlan().status == DeckQrhiVaapiImportStatus::NotAttempted,
            "expected render node replacement to reset stale import plan state before the next render")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->lastReadinessReport().state == DeckVaapiPresenterReadinessState::NotAttempted,
            "expected render node replacement to reset stale readiness before the next render")) {
        delete sceneGraphNode;
        return 1;
    }
    vaapiRenderNode->render(nullptr);
    if (!require(vaapiRenderNode->lastImportPlan().status == DeckQrhiVaapiImportStatus::MissingRenderState,
            "expected replacement render to fail closed until the scenegraph supplies render state")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(vaapiRenderNode->lastReadinessReport().statusCode == std::string("missing-render-state"),
            "expected replacement render failure to keep readiness not-ready with an exact status")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(!replacementFrameLeaseWeak.expired(), "expected render node to keep replacement frame lease alive")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(!vaapiItem->presentVaapiSurface(DeckQrhiVaapiPresentationDescriptor{
            .width = 1280,
            .height = 800,
            .redrawRate = 60,
            .surfaceId = 0,
            .hardwareBacked = false,
            .source = "invalid-replacement",
        }), "expected Qt Quick VAAPI item to reject an invalid replacement frame")) {
        delete sceneGraphNode;
        return 1;
    }
    sceneGraphNode = vaapiItem->updatePaintNode(sceneGraphNode, nullptr);
    if (!require(sceneGraphNode == nullptr,
            "expected invalid replacement to remove the stale render node instead of leaving old readiness visible")) {
        delete sceneGraphNode;
        return 1;
    }
    if (!require(replacementFrameLeaseWeak.expired(), "expected invalid replacement to release the stale replacement frame lease")) {
        return 1;
    }

    itemFrameLease = makeFakeFrameLease(0x2a);
    if (!require(itemFrameLease != nullptr, "expected follow-up VAAPI frame lease to clone after invalid replacement")) {
        return 1;
    }
    itemFrameLeaseWeak = itemFrameLease;
    if (!require(qtQuickPresentationHandoff.presentVaapiSurface(DeckQrhiVaapiPresentationDescriptor{
        .width = 1280,
        .height = 800,
        .redrawRate = 60,
        .surfaceId = itemFrameLease->surfaceId(),
        .hardwareBacked = true,
        .frameLease = itemFrameLease,
        .source = "qt-quick-rhi-item-test-after-invalid",
    }), "expected handoff to accept a new valid frame after invalid replacement reset")) {
        return 1;
    }
    itemFrameLease.reset();
    sceneGraphNode = vaapiItem->updatePaintNode(nullptr, nullptr);
    if (!require(sceneGraphNode != nullptr, "expected Qt Quick item to recreate a render node after invalid replacement reset")) {
        return 1;
    }
    vaapiRenderNode = static_cast<DeckQtQuickRhiVaapiRenderNode*>(sceneGraphNode);
    if (!require(!itemFrameLeaseWeak.expired(), "expected recreated render node to retain the follow-up frame lease")) {
        delete sceneGraphNode;
        return 1;
    }
    vaapiRenderNode->releaseResources();
    if (!require(itemFrameLeaseWeak.expired(), "expected render node releaseResources to release frame lease")) {
        delete sceneGraphNode;
        return 1;
    }
    delete sceneGraphNode;

    DeckVaapiFfmpegRenderer renderer;
    NOVA_TEST_REQUIRE(renderer.adapterName() == "ffmpeg-vaapi-h264-qt-rhi-prototype");
    const int rendererSetup = renderer.setup(VIDEO_FORMAT_H264, 1280, 800, 60, nullptr, 0);
    NOVA_TEST_REQUIRE(rendererSetup == (renderer.lifecycle().runtimeVaapiDeviceAvailable ? DR_OK : DR_NEED_IDR));
    renderer.start();
    NOVA_TEST_REQUIRE(renderer.submitDecodeUnit(nullptr) == DR_NEED_IDR);
    renderer.stop();
    renderer.cleanup();
    NOVA_TEST_REQUIRE(renderer.lifecycle().setupCalls == 1);
    NOVA_TEST_REQUIRE(renderer.lifecycle().startCalls == 1);
    NOVA_TEST_REQUIRE(renderer.lifecycle().submitCalls == 1);
    NOVA_TEST_REQUIRE(renderer.lifecycle().stopCalls == 1);
    NOVA_TEST_REQUIRE(renderer.lifecycle().cleanupCalls == 1);
    NOVA_TEST_REQUIRE(!renderer.lifecycle().acceptedNullDecodeUnit);
    NOVA_TEST_REQUIRE(renderer.lifecycle().networkStartAllowed == false);
    NOVA_TEST_REQUIRE(renderer.lifecycle().runtimeVaapiDeviceAvailable == mediaProbe.runtimeVaapiDeviceAvailable);
    NOVA_TEST_REQUIRE(!renderer.lifecycle().runtimeStatus.empty());

    DeckVaapiFfmpegRenderer decodeRenderer;
    auto decodePresentationSink = std::make_shared<RecordingPresentationSink>();
    decodeRenderer.presentationHandoff().setSink(decodePresentationSink);
    const int decodeSetup = decodeRenderer.setup(VIDEO_FORMAT_H264, 128, 72, 1, nullptr, 0);
    auto idrBytes = makeLocalAnnexBH264IdrSample();
    NOVA_TEST_REQUIRE(!idrBytes.empty());
    LENTRY idrEntry{};
    DECODE_UNIT idrUnit = makeDecodeUnit(idrBytes, idrEntry);
    if (decodeRenderer.lifecycle().runtimeVaapiDeviceAvailable) {
        NOVA_TEST_REQUIRE(decodeSetup == DR_OK);
        NOVA_TEST_REQUIRE(decodeRenderer.lifecycle().ownsHardwareDevice);
        NOVA_TEST_REQUIRE(decodeRenderer.lifecycle().ownsCodecContext);
        NOVA_TEST_REQUIRE(decodeRenderer.submitDecodeUnit(&idrUnit) == DR_OK);
        NOVA_TEST_REQUIRE(decodeRenderer.lifecycle().decodedHardwareFrames == 1);
        NOVA_TEST_REQUIRE(decodeRenderer.lifecycle().lastFrameWasHardwareBacked);
        NOVA_TEST_REQUIRE(decodeRenderer.lifecycle().presentedHardwareFrames == 1);
        NOVA_TEST_REQUIRE(decodePresentationSink->presentCalls == 1);
        NOVA_TEST_REQUIRE(decodePresentationSink->lastDescriptor.width == 128);
        NOVA_TEST_REQUIRE(decodePresentationSink->lastDescriptor.height == 72);
        NOVA_TEST_REQUIRE(decodePresentationSink->lastDescriptor.redrawRate == 1);
        NOVA_TEST_REQUIRE(decodePresentationSink->lastDescriptor.surfaceId != 0);
        NOVA_TEST_REQUIRE(decodePresentationSink->lastDescriptor.hardwareBacked);
        NOVA_TEST_REQUIRE(decodePresentationSink->lastDescriptor.frameLease != nullptr);
        NOVA_TEST_REQUIRE(decodePresentationSink->lastDescriptor.frameLease->valid());
        NOVA_TEST_REQUIRE(decodePresentationSink->lastDescriptor.frameLease->surfaceId() == decodePresentationSink->lastDescriptor.surfaceId);
        NOVA_TEST_REQUIRE(decodePresentationSink->lastDescriptor.source == std::string("ffmpeg-vaapi-h264"));
        const auto realDrmPrimeExport = decodePresentationSink->lastDescriptor.frameLease->exportDrmPrimeDescriptor();
        NOVA_TEST_REQUIRE(realDrmPrimeExport.status == DeckQrhiVaapiImportStatus::DrmPrimeExported);
        NOVA_TEST_REQUIRE(realDrmPrimeExport.objectCount > 0);
        NOVA_TEST_REQUIRE(realDrmPrimeExport.layerCount > 0);
        NOVA_TEST_REQUIRE(!realDrmPrimeExport.detail.empty());
        const auto realFrameReadiness = DeckVaapiEglImagePresenter::readinessReportForDecodedFrameProof(realDrmPrimeExport);
        NOVA_TEST_REQUIRE(realFrameReadiness.state == DeckVaapiPresenterReadinessState::HardwareFrameReady);
        NOVA_TEST_REQUIRE(realFrameReadiness.statusCode == std::string("hardware-frame-ready"));
        NOVA_TEST_REQUIRE(realFrameReadiness.hardwarePresenterPlanned);
        NOVA_TEST_REQUIRE(!realFrameReadiness.ready);
        NOVA_TEST_REQUIRE(realFrameReadiness.importPlan.drmPrimeObjectCount == realDrmPrimeExport.objectCount);
        NOVA_TEST_REQUIRE(realFrameReadiness.importPlan.drmPrimeLayerCount == realDrmPrimeExport.layerCount);
        const auto realFrameMissingTargetPlan = DeckVaapiEglImagePresenter::planOpenGlTextureImport(
            nullptr,
            realDrmPrimeExport,
            QSize(decodePresentationSink->lastDescriptor.width, decodePresentationSink->lastDescriptor.height));
        const auto realFrameRenderTargetReadiness = DeckVaapiEglImagePresenter::readinessReportForDecodedFrameProof(
            realDrmPrimeExport,
            realFrameMissingTargetPlan);
        if (realFrameMissingTargetPlan.status == DeckQrhiVaapiImportStatus::UnsupportedMultiLayerDrmPrimeImport) {
            NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.state == DeckVaapiPresenterReadinessState::UnsupportedMultiLayerDrmPrimeImport);
            NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.statusCode == std::string("unsupported-multilayer-drm-prime-import"));
            NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.detail.find("2-layer DRM_PRIME") != std::string::npos);
            NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.detail.find("YUV") != std::string::npos);
        } else if (realFrameMissingTargetPlan.status == DeckQrhiVaapiImportStatus::IncompleteDrmPrimeMetadata) {
            NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.state == DeckVaapiPresenterReadinessState::IncompleteDrmPrimeMetadata);
            NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.statusCode == std::string("incomplete-drm-prime-metadata"));
        } else {
            NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.state == DeckVaapiPresenterReadinessState::DeckTargetUnavailable);
            NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.statusCode == std::string("deck-target-unavailable"));
        }
        NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.hardwarePresenterPlanned);
        NOVA_TEST_REQUIRE(!realFrameRenderTargetReadiness.ready);
        NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.importPlan.drmPrimeObjectCount == realDrmPrimeExport.objectCount);
        NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.importPlan.drmPrimeLayerCount == realDrmPrimeExport.layerCount);
        NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.detail.find("Hardware-backed VAAPI frame decoded") != std::string::npos);
        NOVA_TEST_REQUIRE(realFrameRenderTargetReadiness.detail.find("render-target readiness") != std::string::npos);

        DeckVaapiFfmpegRenderer productDecodeRenderer;
        auto productDecodePipeline = std::make_shared<DeckProductPreviewPipeline>();
        auto productDecodeSink = std::make_shared<RecordingPresentationSink>();
        productDecodePipeline->attachSink(productDecodeSink);
        productDecodeRenderer.presentationHandoff().setSink(productDecodePipeline);
        NOVA_TEST_REQUIRE(productDecodeRenderer.setup(VIDEO_FORMAT_H264, 128, 72, 1, nullptr, 0) == DR_OK);
        LENTRY productIdrEntry{};
        DECODE_UNIT productIdrUnit = makeDecodeUnit(idrBytes, productIdrEntry);
        NOVA_TEST_REQUIRE(productDecodeRenderer.submitDecodeUnit(&productIdrUnit) == DR_OK);
        NOVA_TEST_REQUIRE(productDecodeRenderer.lifecycle().decodedHardwareFrames == 1);
        NOVA_TEST_REQUIRE(productDecodeRenderer.lifecycle().presentedHardwareFrames == 1);
        NOVA_TEST_REQUIRE(productDecodePipeline->queuedFrames() == 1);
        NOVA_TEST_REQUIRE(productDecodePipeline->flushedFrames() == 1);
        NOVA_TEST_REQUIRE(productDecodePipeline->presentedFrames() == 1);
        NOVA_TEST_REQUIRE(productDecodePipeline->lastReadinessReport().statusCode == std::string("hardware-frame-ready"));
        NOVA_TEST_REQUIRE(productDecodePipeline->lastReadinessReport().hardwarePresenterPlanned);
        NOVA_TEST_REQUIRE(!productDecodePipeline->lastReadinessReport().ready);
        NOVA_TEST_REQUIRE(productDecodeSink->presentCalls == 1);
        NOVA_TEST_REQUIRE(productDecodeSink->lastDescriptor.source == std::string("ffmpeg-vaapi-h264"));
        NOVA_TEST_REQUIRE(productDecodeSink->lastDescriptor.frameLease != nullptr);
        NOVA_TEST_REQUIRE(productDecodeSink->lastDescriptor.frameLease->valid());
        NOVA_TEST_REQUIRE(productDecodeSink->lastDescriptor.surfaceId != 0);
        NOVA_TEST_REQUIRE(productDecodeSink->lastDescriptor.hardwareBacked);
        NOVA_TEST_REQUIRE(productDecodePipeline->lastReadinessReport().detail.find("decoded hardware frame") != std::string::npos);
        productDecodeRenderer.cleanup();

        if (realDrmPrimeExport.layerCount == 2 && std::getenv("NOVA_DECK_REQUIRE_LIVE_EGL_COMPOSITION") != nullptr) {
            ScopedLiveEglContext liveEglContext;
            NOVA_TEST_REQUIRE(liveEglContext.valid());
            DeckVaapiEglImagePresenter::Resource livePresenterResource;
            const auto liveImportPlan = DeckVaapiEglImagePresenter::importOpenGlTextureForCurrentContext(
                realDrmPrimeExport,
                QSize(decodePresentationSink->lastDescriptor.width, decodePresentationSink->lastDescriptor.height),
                livePresenterResource);
            NOVA_TEST_REQUIRE(liveImportPlan.status == DeckQrhiVaapiImportStatus::DrmPrimeExported);
            const auto importedReadiness = DeckVaapiEglImagePresenter::readinessReportForResource(liveImportPlan, livePresenterResource);
            NOVA_TEST_REQUIRE(importedReadiness.hardwarePresenterPlanned);
            NOVA_TEST_REQUIRE(!importedReadiness.ready);
            NOVA_TEST_REQUIRE(DeckVaapiEglImagePresenter::proveOpenGlShaderCompositionForCurrentContext(
                livePresenterResource,
                QSize(decodePresentationSink->lastDescriptor.width, decodePresentationSink->lastDescriptor.height)));
            const auto liveReadyReadiness = DeckVaapiEglImagePresenter::readinessReportForResource(liveImportPlan, livePresenterResource);
            NOVA_TEST_REQUIRE(liveImportPlan.drmPrimeLayerCount == 2);
            NOVA_TEST_REQUIRE(liveReadyReadiness.ready);
            NOVA_TEST_REQUIRE(liveReadyReadiness.statusCode == std::string("ready"));
            NOVA_TEST_REQUIRE(liveReadyReadiness.detail.find("2-layer DRM_PRIME Y/UV") != std::string::npos);
            std::cout << "Nova Deck live EGL two-layer composition "
                      << liveReadyReadiness.statusCode << ' '
                      << "objects=" << liveReadyReadiness.importPlan.drmPrimeObjectCount << ' '
                      << "layers=" << liveReadyReadiness.importPlan.drmPrimeLayerCount << ' '
                      << liveEglContext.detail() << ' '
                      << liveReadyReadiness.detail << '\n';
        }
        std::cout << "Nova Deck VAAPI/EGL presenter readiness "
                  << realFrameReadiness.statusCode << ' '
                  << "objects=" << realFrameReadiness.importPlan.drmPrimeObjectCount << ' '
                  << "layers=" << realFrameReadiness.importPlan.drmPrimeLayerCount << ' '
                  << realFrameReadiness.detail << '\n';
    } else {
        NOVA_TEST_REQUIRE(decodeSetup == DR_NEED_IDR);
        NOVA_TEST_REQUIRE(!decodeRenderer.lifecycle().ownsHardwareDevice);
        NOVA_TEST_REQUIRE(!decodeRenderer.lifecycle().ownsCodecContext);
        NOVA_TEST_REQUIRE(decodeRenderer.submitDecodeUnit(&idrUnit) == DR_NEED_IDR);
        NOVA_TEST_REQUIRE(decodeRenderer.lifecycle().decodedHardwareFrames == 0);
        NOVA_TEST_REQUIRE(decodeRenderer.lifecycle().presentedHardwareFrames == 0);
        NOVA_TEST_REQUIRE(decodePresentationSink->presentCalls == 0);
        NOVA_TEST_REQUIRE(!decodeRenderer.lifecycle().lastRuntimeError.empty());
        NOVA_TEST_REQUIRE(decodeRenderer.lifecycle().lastRuntimeError.find("av_hwdevice_ctx_create(VAAPI) failed") != std::string::npos);
    }
    decodeRenderer.cleanup();

    const DeckLinuxAudioProbe audioProbe = DeckLinuxAudioProbe::detect();
    NOVA_TEST_REQUIRE(audioProbe.pipeWireHeadersLinked);
    NOVA_TEST_REQUIRE(audioProbe.pulseFallbackHeadersLinked);

    OPUS_MULTISTREAM_CONFIGURATION opusConfig{};
    opusConfig.samplesPerFrame = 240;
    DeckPipeWireAudio audio;
    NOVA_TEST_REQUIRE(audio.adapterName() == "pipewire-pcm-pulse-fallback-prototype");
    NOVA_TEST_REQUIRE(audio.init(AUDIO_CONFIGURATION_STEREO, &opusConfig, nullptr, 0) == 0);
    audio.start();
    char pcm[] = {'p', 'c', 'm', '!'};
    audio.decodeAndPlaySample(pcm, 4);
    audio.stop();
    audio.cleanup();
    NOVA_TEST_REQUIRE(audio.lifecycle().initCalls == 1);
    NOVA_TEST_REQUIRE(audio.lifecycle().startCalls == 1);
    NOVA_TEST_REQUIRE(audio.lifecycle().sampleCalls == 1);
    NOVA_TEST_REQUIRE(audio.lifecycle().lastSampleLength == 4);
    NOVA_TEST_REQUIRE(audio.lifecycle().samplesPerFrame == 240);
    NOVA_TEST_REQUIRE(audio.lifecycle().stopCalls == 1);
    NOVA_TEST_REQUIRE(audio.lifecycle().cleanupCalls == 1);
    NOVA_TEST_REQUIRE(audio.lifecycle().networkStartAllowed == false);

    DeckVaapiFfmpegRenderer callbackRenderer;
    DeckPipeWireAudio callbackAudio;
    NoopInput input;
    RecordingEvents events;
    DeckStreamSession session(callbackRenderer, callbackAudio, input, events);
    NOVA_TEST_REQUIRE(!session.moonlightBoundary().networkStartAllowed);
    const auto prepared = session.prepare(DeckStreamRequest{
        .hostId = "offline-harness-host",
        .gameId = "offline-harness-game",
        .width = 1280,
        .height = 800,
        .fps = 60,
        .bitrateKbps = 20000,
    });
    NOVA_TEST_REQUIRE(prepared.state == DeckStreamSessionState::Preparing);
    NOVA_TEST_REQUIRE(!prepared.networkStarted);
    const int callbackRendererSetup = session.moonlightBoundary().videoCallbacks->setup(VIDEO_FORMAT_H264, 1280, 800, 60, session.moonlightBoundary().callbackContext, 0);
    NOVA_TEST_REQUIRE(callbackRendererSetup == (callbackRenderer.lifecycle().runtimeVaapiDeviceAvailable ? DR_OK : DR_NEED_IDR));
    NOVA_TEST_REQUIRE(session.moonlightBoundary().videoCallbacks->submitDecodeUnit(nullptr) == DR_NEED_IDR);
    OPUS_MULTISTREAM_CONFIGURATION callbackOpusConfig{};
    callbackOpusConfig.samplesPerFrame = 240;
    NOVA_TEST_REQUIRE(session.moonlightBoundary().audioCallbacks->init(AUDIO_CONFIGURATION_STEREO, &callbackOpusConfig, session.moonlightBoundary().callbackContext, 0) == 0);
    NOVA_TEST_REQUIRE(callbackRenderer.lifecycle().setupCalls == 1);
    NOVA_TEST_REQUIRE(callbackRenderer.lifecycle().submitCalls == 1);
    NOVA_TEST_REQUIRE(callbackAudio.lifecycle().initCalls == 1);
    const auto started = session.startNoNetwork();
    NOVA_TEST_REQUIRE(started.state == DeckStreamSessionState::Active);
    NOVA_TEST_REQUIRE(!started.networkStarted);
    session.stop();

    return 0;
}
