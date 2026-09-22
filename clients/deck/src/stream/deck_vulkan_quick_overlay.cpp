#include "stream/deck_vulkan_quick_overlay.h"
#include <QQuickGraphicsDevice>
#include <QQuickRenderTarget>
#include <QQuickItem>
#include <QVulkanInstance>
#include <cmath>

namespace nova::deck::stream {
namespace {
class Control final : public QQuickRenderControl {
public:
    explicit Control(QWindow& target) : target_(target) {}
    QWindow* renderWindow(QPoint* offset) override {
        if (offset) *offset = {};
        return &target_;
    }
private:
    QWindow& target_;
};
}

DeckVulkanQuickOverlay::DeckVulkanQuickOverlay(QWindow& target, QVulkanInstance& instance)
    : control_(std::make_unique<Control>(target)), window_(std::make_unique<QQuickWindow>(control_.get())) {
    window_->setVulkanInstance(&instance);
    window_->setColor(Qt::transparent);
    const auto changed = [this, &target] { ++revision_; target.requestUpdate(); };
    QObject::connect(control_.get(), &QQuickRenderControl::renderRequested, &target, changed);
    QObject::connect(control_.get(), &QQuickRenderControl::sceneChanged, &target, changed);
}

DeckVulkanQuickOverlay::~DeckVulkanQuickOverlay() { release(); }

void DeckVulkanQuickOverlay::prepareThread(QThread* thread) { control_->prepareThread(thread); }

void DeckVulkanQuickOverlay::polish(const QSize& pixels, qreal ratio) {
    window_->setGeometry(0, 0, qRound(pixels.width() / ratio), qRound(pixels.height() / ratio));
    window_->contentItem()->setSize(window_->size());
    control_->polishItems();
}

void DeckVulkanQuickOverlay::shutdown() { release(); }


bool DeckVulkanQuickOverlay::initialize(pl_vulkan vk) {
    if (vk_) return vk_ == vk && ready_;
    if (!vk || QQuickWindow::graphicsApi() != QSGRendererInterface::Vulkan) {
        error_ = QStringLiteral("The streaming overlay requires Qt Quick Vulkan.");
        return false;
    }
    vk_ = vk;
    window_->setGraphicsDevice(QQuickGraphicsDevice::fromDeviceObjects(
        vk->phys_device, vk->device, int(vk->queue_graphics.index), 0));
    if (!control_->initialize()) { error_ = QStringLiteral("Could not initialize the streaming overlay."); return false; }
    pl_vulkan_sem_params semaphore{};
    semaphore.type = VK_SEMAPHORE_TYPE_TIMELINE;
    ready_ = pl_vulkan_sem_create(vk_->gpu, &semaphore);
    if (!ready_) { error_ = QStringLiteral("Could not synchronize the streaming overlay."); return false; }
    return true;
}

void DeckVulkanQuickOverlay::release() {
    if (!vk_) return;
    pl_gpu_finish(vk_->gpu);
    // Qt must stop referencing the texture and device before either is freed.
    window_->setRenderTarget({});
    control_->invalidate();
    // invalidate() releases scene nodes, but the QRhi wrapper persists until
    // the control is destroyed. Both Qt objects must retire before the device.
    control_.reset(); // QRhi is destroyed on its render thread before Vulkan.
    // The GUI-owned QQuickWindow is destroyed later, on the GUI thread.
    pl_tex_destroy(vk_->gpu, &texture_);
    pl_vulkan_sem_destroy(vk_->gpu, &ready_);
    vk_ = nullptr;
    serial_ = 0; held_ = false;
}

DeckOverlayUpdate DeckVulkanQuickOverlay::acquire() {
    if (!held_) {
        pl_vulkan_hold_params hold{};
        hold.tex = texture_;
        hold.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        hold.qf = vk_->queue_graphics.index;
        hold.semaphore = {ready_, ++serial_};
        if (!pl_vulkan_hold_ex(vk_->gpu, &hold)) {
            error_ = QStringLiteral("Could not acquire the streaming overlay."); return DeckOverlayUpdate::Failed;
        }
        held_ = true;
        acquisition_.start();
        pl_gpu_flush(vk_->gpu);
    }
    VkSemaphoreWaitInfo wait{VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO};
    wait.semaphoreCount = 1; wait.pSemaphores = &ready_; wait.pValues = &serial_;
    const auto status = vkWaitSemaphores(vk_->device, &wait, 0);
    if (status == VK_TIMEOUT && acquisition_.elapsed() < 1000) return DeckOverlayUpdate::Deferred;
    if (status != VK_SUCCESS) {
        error_ = QStringLiteral("The streaming overlay GPU wait failed."); return DeckOverlayUpdate::Failed;
    }
    return DeckOverlayUpdate::Ready;
}

void DeckVulkanQuickOverlay::releaseTexture() {
    pl_vulkan_release_params release{};
    release.tex = texture_;
    release.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    release.qf = vk_->queue_graphics.index;
    pl_vulkan_release_ex(vk_->gpu, &release);
    held_ = false;
}

DeckOverlayUpdate DeckVulkanQuickOverlay::render(const QSize& pixels, qreal ratio, const Synchronize& synchronize) {
    error_.clear();
    if (!vk_ || !ready_ || pixels.isEmpty() || !std::isfinite(ratio) || ratio <= 0) {
        error_ = QStringLiteral("Invalid streaming overlay target."); return DeckOverlayUpdate::Failed;
    }
    if (!texture_ || texture_->params.w != pixels.width() || texture_->params.h != pixels.height()) {
        if (texture_) {
            // Resize also waits by polling: Qt must not drop an image still
            // sampled by libplacebo. A deferred resize keeps the old texture.
            const auto status = acquire();
            if (status != DeckOverlayUpdate::Ready) return status;
            releaseTexture();
            window_->setRenderTarget({});
            pl_tex_destroy(vk_->gpu, &texture_);
        }
        pl_tex_params params{};
        params.w = pixels.width(); params.h = pixels.height();
        params.format = pl_find_named_fmt(vk_->gpu, "rgba8");
        params.renderable = params.sampleable = true;
        if (!params.format || !(texture_ = pl_tex_create(vk_->gpu, &params))) {
            error_ = QStringLiteral("Could not allocate the streaming overlay."); return DeckOverlayUpdate::Failed;
        }
        renderedRevision_ = 0;
    }
    if (!held_ && renderedRevision_ == revision_.load() && renderedRatio_ == ratio) return DeckOverlayUpdate::Ready;
    const auto status = acquire();
    if (status != DeckOverlayUpdate::Ready) return status;
    VkFormat format{};
    auto image = pl_vulkan_unwrap(vk_->gpu, texture_, &format, nullptr);
    if (format != VK_FORMAT_R8G8B8A8_UNORM) {
        error_ = QStringLiteral("Unsupported streaming overlay texture format."); return DeckOverlayUpdate::Failed;
    }
    auto target = QQuickRenderTarget::fromVulkanImage(image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, format, pixels);
    target.setDevicePixelRatio(ratio);
    window_->setRenderTarget(target);
    if (!synchronize) polish(pixels, ratio);
    control_->beginFrame(); // May wait: never park GUI around begin/endFrame.
    uint64_t revision = 0;
    const auto sync = [&] { revision = revision_.load(); control_->sync(); };
    const bool synced = synchronize ? synchronize(sync) : (sync(), true);
    if (!synced) {
        control_->endFrame();
        releaseTexture();
        return DeckOverlayUpdate::Deferred;
    }
    control_->render();
    // Qt's endOffscreenFrame submits and waits for completion. The imported
    // color attachment stays in COLOR_ATTACHMENT_OPTIMAL (validation-covered).
    control_->endFrame();
    releaseTexture();
    part_.src = part_.dst = {0, 0, float(pixels.width()), float(pixels.height())};
    renderedRevision_ = revision;
    renderedRatio_ = ratio;
    ++renderedFrames_;
    return DeckOverlayUpdate::Ready;
}

pl_overlay DeckVulkanQuickOverlay::overlay() const {
    pl_overlay result{};
    if (held_) return result; // A deferred update must never sample a held image.
    result.tex = texture_;
    result.coords = PL_OVERLAY_COORDS_DST_FRAME;
    result.repr = pl_color_repr_rgb;
    result.repr.alpha = PL_ALPHA_PREMULTIPLIED;
    result.color = pl_color_space_srgb;
    result.parts = &part_; result.num_parts = 1;
    return result;
}
}
