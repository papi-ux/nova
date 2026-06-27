#pragma once

#include "stream/deck_stream_core.h"

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include <QtCore/QSize>
#include <QtQuick/QQuickItem>
#include <QtQuick/QSGRenderNode>

class QQuickWindow;
class QSGTexture;

struct AVBufferRef;
struct AVCodecContext;
struct AVFrame;

namespace nova::deck::stream {

enum class DeckQrhiVaapiImportStatus {
    NotAttempted,
    DrmPrimeExported,
    MissingFrameLease,
    InvalidVaapiFrame,
    MissingHardwareFramesContext,
    DrmPrimeMapFailed,
    MissingRenderState,
    MissingQrhiCommandBuffer,
    MissingRenderContext,
    DeckTargetUnavailable,
    UnsupportedNonOpenGlSceneGraph,
    MissingEglDmabufExtensions,
    IncompleteDrmPrimeMetadata,
    UnsupportedMultiLayerDrmPrimeImport,
    UnsupportedDrmPrimeFormat,
    EglImageCreationFailed,
    GlTextureBindFailed,
    EglImageShaderCompositionFailed,
    UnsupportedPublicQtLinuxDmabufImport,
};

struct DeckVaapiDrmPrimeObject {
    int fd = -1;
    std::uint64_t formatModifier = 0;
};

struct DeckVaapiDrmPrimePlane {
    int objectIndex = -1;
    std::int64_t offset = 0;
    std::int64_t pitch = 0;
};

struct DeckVaapiDrmPrimeLayer {
    std::uint32_t format = 0;
    int planeCount = 0;
    std::array<DeckVaapiDrmPrimePlane, 4> planes{};
};

class DeckQrhiVaapiDrmPrimeDescriptor final {
public:
    DeckQrhiVaapiDrmPrimeDescriptor() = default;
    ~DeckQrhiVaapiDrmPrimeDescriptor();
    DeckQrhiVaapiDrmPrimeDescriptor(const DeckQrhiVaapiDrmPrimeDescriptor&) = delete;
    DeckQrhiVaapiDrmPrimeDescriptor& operator=(const DeckQrhiVaapiDrmPrimeDescriptor&) = delete;
    DeckQrhiVaapiDrmPrimeDescriptor(DeckQrhiVaapiDrmPrimeDescriptor&& other) noexcept;
    DeckQrhiVaapiDrmPrimeDescriptor& operator=(DeckQrhiVaapiDrmPrimeDescriptor&& other) noexcept;

    DeckQrhiVaapiImportStatus status = DeckQrhiVaapiImportStatus::NotAttempted;
    int objectCount = 0;
    int layerCount = 0;
    std::array<DeckVaapiDrmPrimeObject, 4> objects{};
    std::array<DeckVaapiDrmPrimeLayer, 4> layers{};
    std::string detail;

private:
    friend class DeckQrhiVaapiFrameLease;
    explicit DeckQrhiVaapiDrmPrimeDescriptor(AVFrame* mappedFrame);
    void reset();

    AVFrame* mappedFrame_ = nullptr;
};

struct DeckQrhiVaapiImportPlan {
    DeckQrhiVaapiImportStatus status = DeckQrhiVaapiImportStatus::NotAttempted;
    int drmPrimeObjectCount = 0;
    int drmPrimeLayerCount = 0;
    std::string detail;
};

enum class DeckVaapiPresenterReadinessState {
    NotAttempted,
    Ready,
    HardwarePresenterPlanned,
    HardwareFrameReady,
    MissingFrameLease,
    InvalidVaapiFrame,
    MissingHardwareFramesContext,
    DrmPrimeMapFailed,
    MissingRenderState,
    MissingQrhiCommandBuffer,
    MissingRenderContext,
    DeckTargetUnavailable,
    UnsupportedNonOpenGlSceneGraph,
    MissingEglDmabufExtensions,
    IncompleteDrmPrimeMetadata,
    UnsupportedMultiLayerDrmPrimeImport,
    UnsupportedDrmPrimeFormat,
    EglImageCreationFailed,
    GlTextureBindFailed,
    EglImageShaderCompositionFailed,
    UnsupportedPublicQtLinuxDmabufImport,
};

struct DeckVaapiPresenterReadinessReport {
    DeckVaapiPresenterReadinessState state = DeckVaapiPresenterReadinessState::NotAttempted;
    DeckQrhiVaapiImportPlan importPlan;
    std::string statusCode;
    std::string label;
    std::string detail;
    bool ready = false;
    bool hardwarePresenterPlanned = false;
};

class DeckVaapiEglImagePresenter final {
public:
    struct Resource {
        ~Resource();
        Resource() = default;
        Resource(const Resource&) = delete;
        Resource& operator=(const Resource&) = delete;
        Resource(Resource&& other) noexcept;
        Resource& operator=(Resource&& other) noexcept;

        bool hasTexture() const;

        QSGTexture* qtTexture = nullptr;
        void* eglDisplay = nullptr;
        void* eglImage = nullptr;
        unsigned int glTexture = 0;
        unsigned int glProgram = 0;
        std::array<void*, 4> eglImages{};
        std::array<unsigned int, 4> glTextures{};
        int importedLayerCount = 0;
        bool shaderCompositionProved = false;
        std::string shaderCompositionDetail;
    };

    static DeckQrhiVaapiImportPlan validateDrmPrimeMetadata(const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor);
    static DeckQrhiVaapiImportPlan planOpenGlTextureImport(
        QQuickWindow* targetWindow,
        const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor,
        const QSize& size);
    static DeckQrhiVaapiImportPlan importOpenGlTexture(
        QQuickWindow* targetWindow,
        const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor,
        const QSize& size,
        Resource& resource);
    static DeckQrhiVaapiImportPlan importOpenGlTextureForCurrentContext(
        const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor,
        const QSize& size,
        Resource& resource);
    static bool proveOpenGlShaderCompositionForCurrentContext(
        Resource& resource,
        const QSize& size);
    static DeckVaapiPresenterReadinessReport readinessReportForPlan(const DeckQrhiVaapiImportPlan& plan);
    static DeckVaapiPresenterReadinessReport readinessReportForDecodedFrameProof(
        const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor);
    static DeckVaapiPresenterReadinessReport readinessReportForDecodedFrameProof(
        const DeckQrhiVaapiDrmPrimeDescriptor& drmPrimeDescriptor,
        const DeckQrhiVaapiImportPlan& renderTargetPlan);
    static DeckVaapiPresenterReadinessReport readinessReportForResource(
        const DeckQrhiVaapiImportPlan& plan,
        const Resource& resource);
};

struct DeckLinuxMediaProbe {
    bool ffmpegLibavcodecHeadersLinked = false;
    bool ffmpegLibavutilHeadersLinked = false;
    bool vaapiHeadersLinked = false;
    bool qtQuickRhiPresentationBoundary = false;
    bool h264DecoderAvailable = false;
    bool runtimeVaapiDeviceAvailable = false;
    std::string hardwareDeviceTypeName;
    std::string runtimeStatus;

    static DeckLinuxMediaProbe detect();
};

struct DeckRendererLifecycle {
    int setupCalls = 0;
    int startCalls = 0;
    int submitCalls = 0;
    int stopCalls = 0;
    int cleanupCalls = 0;
    bool acceptedNullDecodeUnit = false;
    bool networkStartAllowed = false;
    bool runtimeVaapiDeviceAvailable = false;
    bool ownsHardwareDevice = false;
    bool ownsCodecContext = false;
    int decodedHardwareFrames = 0;
    int presentedHardwareFrames = 0;
    bool lastFrameWasHardwareBacked = false;
    std::string runtimeStatus;
    std::string lastRuntimeError;
    int width = 0;
    int height = 0;
    int redrawRate = 0;
    int videoFormat = 0;
};

class DeckQrhiVaapiFrameLease final {
public:
    ~DeckQrhiVaapiFrameLease();
    DeckQrhiVaapiFrameLease(const DeckQrhiVaapiFrameLease&) = delete;
    DeckQrhiVaapiFrameLease& operator=(const DeckQrhiVaapiFrameLease&) = delete;
    DeckQrhiVaapiFrameLease(DeckQrhiVaapiFrameLease&&) = delete;
    DeckQrhiVaapiFrameLease& operator=(DeckQrhiVaapiFrameLease&&) = delete;

    static std::shared_ptr<DeckQrhiVaapiFrameLease> cloneHardwareFrame(const AVFrame& frame);
    bool valid() const;
    std::uintptr_t surfaceId() const;
    DeckQrhiVaapiDrmPrimeDescriptor exportDrmPrimeDescriptor() const;

private:
    explicit DeckQrhiVaapiFrameLease(AVFrame* frame);

    AVFrame* frame_ = nullptr;
};

struct DeckQrhiVaapiPresentationDescriptor {
    int width = 0;
    int height = 0;
    int redrawRate = 0;
    std::uintptr_t surfaceId = 0;
    bool hardwareBacked = false;
    std::shared_ptr<DeckQrhiVaapiFrameLease> frameLease;
    std::string source;
};

class DeckQtQuickRhiPresentationSink {
public:
    virtual ~DeckQtQuickRhiPresentationSink() = default;
    virtual bool presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& descriptor) = 0;
};

class DeckQrhiVaapiPresentationHandoff final {
public:
    void setSink(std::shared_ptr<DeckQtQuickRhiPresentationSink> sink);
    void setBorrowedSink(DeckQtQuickRhiPresentationSink* sink);
    void clearSink();
    bool presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& descriptor);
    int presentedFrames() const;

private:
    std::weak_ptr<DeckQtQuickRhiPresentationSink> sink_;
    DeckQtQuickRhiPresentationSink* borrowedSink_ = nullptr;
    int presentedFrames_ = 0;
};

class DeckVaapiPreviewFramePump final {
public:
    explicit DeckVaapiPreviewFramePump(DeckQrhiVaapiPresentationHandoff& handoff);

    bool enqueueDecodedFrame(DeckQrhiVaapiPresentationDescriptor descriptor);
    bool flushNewest();
    void clearPending();

    int queuedFrames() const;
    int coalescedFrames() const;
    int flushedFrames() const;
    int invalidatedFrames() const;
    int pendingFrames() const;

private:
    static bool isValidPreviewFrame(const DeckQrhiVaapiPresentationDescriptor& descriptor);

    DeckQrhiVaapiPresentationHandoff& handoff_;
    DeckQrhiVaapiPresentationDescriptor pendingDescriptor_{};
    bool hasPendingDescriptor_ = false;
    int queuedFrames_ = 0;
    int coalescedFrames_ = 0;
    int flushedFrames_ = 0;
    int invalidatedFrames_ = 0;
};

class DeckProductPreviewPipeline final : public DeckQtQuickRhiPresentationSink {
public:
    DeckProductPreviewPipeline();

    void attachSink(std::shared_ptr<DeckQtQuickRhiPresentationSink> sink);
    void attachBorrowedSink(DeckQtQuickRhiPresentationSink* sink);
    bool presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& descriptor) override;
    const DeckVaapiPresenterReadinessReport& lastReadinessReport() const;

    int queuedFrames() const;
    int flushedFrames() const;
    int invalidatedFrames() const;
    int pendingFrames() const;
    int presentedFrames() const;

private:
    DeckQrhiVaapiPresentationHandoff handoff_{};
    DeckVaapiPreviewFramePump previewFramePump_{handoff_};
    DeckVaapiPresenterReadinessReport lastReadinessReport_{};
};

class DeckQtQuickRhiVaapiRenderNode final : public QSGRenderNode {
public:
    explicit DeckQtQuickRhiVaapiRenderNode(DeckQrhiVaapiPresentationDescriptor descriptor, QQuickWindow* targetWindow = nullptr);
    ~DeckQtQuickRhiVaapiRenderNode() override;

    const DeckQrhiVaapiPresentationDescriptor& descriptor() const;
    // Scenegraph-thread only: replaces the retained frame and drops GL/EGL resources owned by the prior frame.
    void replaceDescriptor(DeckQrhiVaapiPresentationDescriptor descriptor, QQuickWindow* targetWindow = nullptr);
    bool hasFrameLease() const;
    DeckQrhiVaapiImportPlan planQrhiImport(const RenderState* state) const;
    const DeckQrhiVaapiImportPlan& lastImportPlan() const;
    const DeckVaapiPresenterReadinessReport& lastReadinessReport() const;
    StateFlags changedStates() const override;
    void render(const RenderState* state) override;
    void releaseResources() override;
    RenderingFlags flags() const override;
    QRectF rect() const override;

private:
    DeckQrhiVaapiPresentationDescriptor descriptor_{};
    DeckQrhiVaapiImportPlan lastImportPlan_{};
    DeckVaapiPresenterReadinessReport readinessReport_{};
    QQuickWindow* targetWindow_ = nullptr;
    DeckVaapiEglImagePresenter::Resource presenterResource_{};
};

class DeckQtQuickRhiVaapiItem : public QQuickItem, public DeckQtQuickRhiPresentationSink {
public:
    explicit DeckQtQuickRhiVaapiItem(QQuickItem* parent = nullptr);
    ~DeckQtQuickRhiVaapiItem() override;

    bool presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& descriptor) override;
    int presentedFrames() const;

protected:
    QSGNode* updatePaintNode(QSGNode* oldNode, UpdatePaintNodeData* updatePaintNodeData) override;

private:
    DeckQrhiVaapiPresentationDescriptor pendingDescriptor_{};
    bool hasPendingDescriptor_ = false;
    bool pendingDescriptorValid_ = false;
    int presentedFrames_ = 0;
};

class DeckVaapiFfmpegRenderer final : public DeckStreamRenderer {
public:
    ~DeckVaapiFfmpegRenderer() override;
    DeckVaapiFfmpegRenderer() = default;
    DeckVaapiFfmpegRenderer(const DeckVaapiFfmpegRenderer&) = delete;
    DeckVaapiFfmpegRenderer& operator=(const DeckVaapiFfmpegRenderer&) = delete;
    DeckVaapiFfmpegRenderer(DeckVaapiFfmpegRenderer&&) = delete;
    DeckVaapiFfmpegRenderer& operator=(DeckVaapiFfmpegRenderer&&) = delete;

    std::string_view adapterName() const override;
    int setup(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) override;
    void start() override;
    void stop() override;
    void cleanup() override;
    int submitDecodeUnit(PDECODE_UNIT decodeUnit) override;

    const DeckRendererLifecycle& lifecycle() const;
    DeckQrhiVaapiPresentationHandoff& presentationHandoff();
    const DeckQrhiVaapiPresentationHandoff& presentationHandoff() const;

private:
    void resetDecoder();

    DeckRendererLifecycle lifecycle_{};
    bool ready_ = false;
    AVBufferRef* hardwareDevice_ = nullptr;
    AVCodecContext* codecContext_ = nullptr;
    AVFrame* decodedFrame_ = nullptr;
    DeckQrhiVaapiPresentationHandoff presentationHandoff_{};
    DeckVaapiPreviewFramePump previewFramePump_{presentationHandoff_};
};

struct DeckLinuxAudioProbe {
    bool pipeWireHeadersLinked = false;
    bool pulseFallbackHeadersLinked = false;
    std::string pipeWireHeaderVersion;
    std::string pulseHeaderVersion;

    static DeckLinuxAudioProbe detect();
};

struct DeckAudioLifecycle {
    int initCalls = 0;
    int startCalls = 0;
    int sampleCalls = 0;
    int stopCalls = 0;
    int cleanupCalls = 0;
    int audioConfiguration = 0;
    int samplesPerFrame = 0;
    int lastSampleLength = 0;
    bool networkStartAllowed = false;
};

class DeckPipeWireAudio final : public DeckStreamAudio {
public:
    std::string_view adapterName() const override;
    int init(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int arFlags) override;
    void start() override;
    void stop() override;
    void cleanup() override;
    void decodeAndPlaySample(char* sampleData, int sampleLength) override;

    const DeckAudioLifecycle& lifecycle() const;

private:
    DeckAudioLifecycle lifecycle_{};
    bool ready_ = false;
};

class DeckGuardedStreamSessionPreviewProducer final : private DeckStreamSessionEvents {
public:
    DeckGuardedStreamSessionPreviewProducer();
    ~DeckGuardedStreamSessionPreviewProducer() override;
    DeckGuardedStreamSessionPreviewProducer(const DeckGuardedStreamSessionPreviewProducer&) = delete;
    DeckGuardedStreamSessionPreviewProducer& operator=(const DeckGuardedStreamSessionPreviewProducer&) = delete;
    DeckGuardedStreamSessionPreviewProducer(DeckGuardedStreamSessionPreviewProducer&&) = delete;
    DeckGuardedStreamSessionPreviewProducer& operator=(DeckGuardedStreamSessionPreviewProducer&&) = delete;

    void attachProductPreviewPipeline(DeckProductPreviewPipeline& pipeline);
    DeckStreamTransition prepareNoNetwork(const DeckStreamRequest& request);
    DeckStreamTransition startNoNetwork();
    DeckStreamTransition stop();

    const DeckMoonlightBoundary& moonlightBoundary() const;
    DeckVaapiFfmpegRenderer& decodedFrameProducer();
    const DeckRendererLifecycle& rendererLifecycle() const;
    const std::vector<DeckStreamTransition>& transitions() const;

private:
    class NoopInput final : public DeckStreamInput {
    public:
        std::string_view adapterName() const override;
        void rumble(uint16_t controllerNumber, uint16_t lowFreqMotor, uint16_t highFreqMotor) override;
        void setMotionEventState(uint16_t controllerNumber, uint8_t motionType, uint16_t reportRateHz) override;
        void setControllerLed(uint16_t controllerNumber, uint8_t r, uint8_t g, uint8_t b) override;
    };

    void onSessionEvent(DeckStreamSessionState state, std::string_view reason) override;

    DeckVaapiFfmpegRenderer renderer_{};
    DeckPipeWireAudio audio_{};
    NoopInput input_{};
    DeckStreamSession session_;
    std::vector<DeckStreamTransition> transitions_{};
};

struct DeckGuardedPreviewLifecycleReport {
    DeckStreamSessionState state = DeckStreamSessionState::Idle;
    std::string statusCode = "idle-no-network";
    std::string reason = "guarded product preview lifecycle is idle; host/network start remains disabled";
    std::string hostId;
    std::string gameId;
    int width = 0;
    int height = 0;
    int fps = 0;
    int bitrateKbps = 0;
    bool prepared = false;
    bool armed = false;
    bool dryRunPreflightRequested = false;
    bool hostStartBoundaryExplicit = false;
    bool hostStartContractAuthorized = false;
    std::string operatorAuthorizationState = "blocked";
    bool networkStartAllowed = false;
    bool networkStarted = false;
    std::size_t transitionCount = 0;
};

enum class DeckOperatorStartAuthorizationMode {
    Blocked,
    DryRunAuthorized,
    StartAuthorized,
};

struct DeckOperatorStartAuthorizationSnapshot {
    DeckOperatorStartAuthorizationMode mode = DeckOperatorStartAuthorizationMode::Blocked;
    std::string statusCode = "operator-start-blocked";
    std::string reason = "operator has not approved a host/network start contract";
    std::string opaqueLocalStateId;
    bool dryRunAuthorized = false;
    bool startAuthorized = false;
    bool tokenless = true;
    bool networkStarted = false;
};

class DeckOperatorStartAuthorizationPolicy final {
public:
    [[nodiscard]] const DeckOperatorStartAuthorizationSnapshot& snapshot() const;
    void block(std::string reason = "operator start contract returned to blocked state");
    void authorizeDryRun(std::string opaqueLocalStateId);
    void authorizeStart(std::string opaqueLocalStateId);

private:
    DeckOperatorStartAuthorizationSnapshot snapshot_{};
};

class DeckGuardedPreviewLifecycleGate final {
public:
    explicit DeckGuardedPreviewLifecycleGate(DeckGuardedStreamSessionPreviewProducer& producer);

    void attachProductPreviewPipeline(DeckProductPreviewPipeline& pipeline);
    DeckGuardedPreviewLifecycleReport armNoNetwork(const DeckStreamRequest& request);
    DeckGuardedPreviewLifecycleReport requestGuardedHostNetworkStart();
    DeckGuardedPreviewLifecycleReport requestOperatorAuthorizedDryRun(
        const DeckOperatorStartAuthorizationSnapshot& authorization);
    DeckGuardedPreviewLifecycleReport requestHostStartDryRunPreflight(
        const DeckOperatorStartAuthorizationSnapshot& authorization,
        const DeckStreamRequest& request);
    DeckGuardedPreviewLifecycleReport requestOperatorAuthorizedHostNetworkStart(
        const DeckOperatorStartAuthorizationSnapshot& authorization);
    DeckGuardedPreviewLifecycleReport stop();

    const DeckGuardedPreviewLifecycleReport& lastReport() const;
    const std::vector<DeckStreamTransition>& transitions() const;

private:
    DeckGuardedPreviewLifecycleReport reportForTransition(
        const DeckStreamTransition& transition,
        std::string statusCode,
        bool prepared,
        bool armed,
        const DeckStreamRequest* request = nullptr) const;

    DeckGuardedStreamSessionPreviewProducer& producer_;
    DeckGuardedPreviewLifecycleReport lastReport_{};
};

} // namespace nova::deck::stream
