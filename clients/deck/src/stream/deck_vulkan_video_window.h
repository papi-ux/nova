#pragma once

#include <QWindow>
#include <memory>
#include "stream/deck_stream_media_adapters.h"
#include "stream/deck_video_scale.h"

struct AVFrame;

namespace nova::deck::stream {

enum class DeckWindowOutput { Sdr, PreferHdr10, RequireHdr10 };

struct DeckWindowPresentationState {
    bool ready = false;
    bool hdr10Surface = false; // Surface-format evidence, not a panel/pacing claim.
    bool hdrActive = false;
    bool toneMapped = false;
    QSize pixelSize;
    quint64 submittedFrames = 0; // Submissions, not physical presentation counts.
    quint64 overlayRenders = 0; // Qt draws, independent of video compositions.
    quint64 deferredFrames = 0; // Retry attempts, not dropped/decoded frame counts.
    size_t pendingSourceFrames = 0;
    QString error;
};

// Opt-in presentation backend for the native session controller.
// All methods and destruction belong to the window's GUI thread. Retains only
// the latest submitted AVFrame; decoder buffers stay owned until GPU completion.
class DeckVulkanVideoWindow final : public QWindow, public DeckQtQuickRhiPresentationSink {
    Q_OBJECT
public:
    explicit DeckVulkanVideoWindow(bool allowSoftware = false, QWindow* parent = nullptr);
    ~DeckVulkanVideoWindow() override;
    bool submitFrame(const AVFrame& frame);
    bool presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& descriptor) override;
    std::shared_ptr<const std::atomic<std::uint64_t>> composedFrames() const;
    // Enable before showing. Qt Quick Vulkan must be selected before any scene.
    QQuickWindow* enableQuickOverlay();
    QQuickWindow* quickOverlayWindow() const;
    void clearFrame();
    // Hide immediately, retire GPU use asynchronously, then destroy the surface.
    void retireSurface();
    void setOutputPreference(DeckWindowOutput output);
    void setVideoScaleMode(DeckVideoScaleMode mode);
    DeckVideoScaleMode videoScaleMode() const;
    DeckWindowPresentationState presentationState() const;
signals:
    void presentationChanged();
    void closeRequested();
protected:
    bool event(QEvent* event) override;
    void exposeEvent(QExposeEvent*) override;
    void resizeEvent(QResizeEvent*) override;
private:
    struct Impl;
    std::unique_ptr<Impl> d;
    void release();
    void render();
};

} // namespace nova::deck::stream
