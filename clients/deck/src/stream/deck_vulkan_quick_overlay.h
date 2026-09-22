#pragma once
#include <libplacebo/vulkan.h>
#include <libplacebo/renderer.h>
#include <QQuickWindow>
#include <QQuickRenderControl>
#include <QElapsedTimer>
#include <memory>
#include <atomic>
#include <functional>

namespace nova::deck::stream {

// Qt Quick on the shared presentation device. GUI owns items and polish; a
// prepared render thread owns GPU calls. Only scene synchronization parks GUI.
enum class DeckOverlayUpdate { Ready, Deferred, Failed };

class DeckVulkanQuickOverlay final {
public:
    DeckVulkanQuickOverlay(QWindow& window, QVulkanInstance& instance);
    ~DeckVulkanQuickOverlay();
    QQuickWindow* window() const { return window_.get(); }
    bool initialize(pl_vulkan vk);
    using Synchronize = std::function<bool(const std::function<void()>&)>;
    void prepareThread(QThread* thread); // GUI, before first initialization.
    void polish(const QSize& pixels, qreal ratio); // GUI, immediately before sync.
    void shutdown(); // Render thread, with GUI parked during final destruction.
    DeckOverlayUpdate render(const QSize& pixels, qreal devicePixelRatio, const Synchronize& synchronize = {});
    uint64_t renderedFrames() const { return renderedFrames_; }
    pl_overlay overlay() const;
    QString error() const { return error_; }
private:
    void release();
    DeckOverlayUpdate acquire();
    void releaseTexture();
    std::unique_ptr<QQuickRenderControl> control_;
    std::unique_ptr<QQuickWindow> window_;
    pl_vulkan vk_ = nullptr;
    pl_tex texture_ = nullptr;
    VkSemaphore ready_ = VK_NULL_HANDLE;
    uint64_t serial_ = 0;
    std::atomic<uint64_t> revision_{1};
    uint64_t renderedRevision_ = 0, renderedFrames_ = 0;
    qreal renderedRatio_ = 0;
    QElapsedTimer acquisition_;
    bool held_ = false;
    pl_overlay_part part_{};
    QString error_;
};
}
