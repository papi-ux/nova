#include "stream/deck_vulkan_video_window.h"
#include "stream/deck_placebo_color_renderer.h"
#include "stream/deck_video_color.h"
#include "stream/deck_vulkan_quick_overlay.h"
#include <libplacebo/vulkan.h>
#define PL_LIBAV_IMPLEMENTATION 0
#include <libplacebo/utils/libav.h>
#include <QPlatformSurfaceEvent>
#include <QQuickGraphicsConfiguration>
#include <QThread>
#include <QVulkanInstance>
#include <QCoreApplication>
#include <QCloseEvent>
#include <QTimer>
#include <QElapsedTimer>
#include <algorithm>
#include <vector>
#include <QQuickItem>
#include <condition_variable>
#include <mutex>
#include <chrono>
extern "C" {
#include <libavutil/frame.h>
}

namespace nova::deck::stream {
namespace {
struct FreeFrame { void operator()(AVFrame* frame) const { av_frame_free(&frame); } };

bool supportsHdr10(QVulkanInstance& instance, pl_vulkan vk, VkSurfaceKHR surface) {
    if (!instance.extensions().contains(QByteArrayLiteral("VK_EXT_swapchain_colorspace"))) return false;
    auto query = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>(
        instance.getInstanceProcAddr("vkGetPhysicalDeviceSurfaceFormatsKHR"));
    uint32_t count = 0;
    if (!query || query(vk->phys_device, surface, &count, nullptr) != VK_SUCCESS || !count || count > 256)
        return false;
    std::vector<VkSurfaceFormatKHR> formats(count);
    if (query(vk->phys_device, surface, &count, formats.data()) != VK_SUCCESS) return false;
    formats.resize(count);
    return std::any_of(formats.begin(), formats.end(), [](const auto& format) {
        return format.colorSpace == VK_COLOR_SPACE_HDR10_ST2084_EXT &&
            (format.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
             format.format == VK_FORMAT_A2R10G10B10_UNORM_PACK32 ||
             format.format == VK_FORMAT_R16G16B16A16_SFLOAT);
    });
}
}

struct DeckVulkanVideoWindow::Impl {
    // GUI-only: native window, mailbox, published state and scheduling.
    QVulkanInstance instance;
    QThread thread;
    QObject* worker = new QObject;
    std::unique_ptr<DeckVulkanQuickOverlay> overlay;
    std::shared_ptr<AVFrame> frame;
    std::shared_ptr<std::atomic<std::uint64_t>> composed = std::make_shared<std::atomic<std::uint64_t>>(0);
    std::shared_ptr<std::atomic<bool>> cancelled = std::make_shared<std::atomic<bool>>(false);
    uint64_t frameSerial = 0, revision = 0, epoch = 0;
    DeckWindowOutput output = DeckWindowOutput::Sdr;
    DeckVideoScaleMode scale = DeckVideoScaleMode::Fit;
    DeckWindowPresentationState state;
    bool allowSoftware = false, busy = false, dirty = false, retiring = false;
    QTimer retry, retire;

    // Render-thread-only. No native surface can be destroyed until release().
    struct Gpu {
        VkSurfaceKHR surface = VK_NULL_HANDLE;
        pl_vulkan vk = nullptr;
        pl_swapchain swapchain = nullptr;
        std::unique_ptr<DeckPlaceboColorRenderer> renderer;
        DeckWindowPresentationState state;
        uint64_t composedSerial = 0;
        QElapsedTimer pressure;
    } gpu;
    struct Job {
        VkSurfaceKHR surface;
        QSize pixels;
        qreal ratio;
        std::shared_ptr<AVFrame> frame;
        uint64_t serial, revision, epoch;
        DeckWindowOutput output;
        DeckVideoScaleMode scale;
        std::shared_ptr<std::atomic<bool>> cancelled;
    };
    struct Sync {
        std::mutex mutex;
        std::condition_variable wake;
        bool guiReady = false, done = false;
    };
    bool synchronize(DeckVulkanVideoWindow* window, const Job& job, const std::function<void()>& sync) {
        auto ticket = std::make_shared<Sync>();
        QMetaObject::invokeMethod(window, [this, ticket, job] {
            if (*job.cancelled) return;
            overlay->polish(job.pixels, job.ratio);
            std::unique_lock lock(ticket->mutex);
            ticket->guiReady = true;
            ticket->wake.notify_all();
            // The worker has already completed acquire/beginFrame. Only sync
            // runs while GUI is parked, per QQuickRenderControl's contract.
            ticket->wake.wait(lock, [&] { return ticket->done; });
        }, Qt::QueuedConnection);
        std::unique_lock lock(ticket->mutex);
        while (!ticket->guiReady && !*job.cancelled)
            ticket->wake.wait_for(lock, std::chrono::milliseconds(5));
        const bool run = ticket->guiReady && !*job.cancelled;
        lock.unlock();
        if (run) sync();
        lock.lock(); ticket->done = true; ticket->wake.notify_all();
        return run;
    }
    bool initialize(DeckVulkanVideoWindow* window, const Job& job);
    void release();
    bool render(DeckVulkanVideoWindow* window, const Job& job);
};

DeckVulkanVideoWindow::DeckVulkanVideoWindow(bool allowSoftware, QWindow* parent)
    : QWindow(parent), d(std::make_unique<Impl>()) {
    d->allowSoftware = allowSoftware;
    d->worker->moveToThread(&d->thread);
    connect(&d->thread, &QThread::finished, d->worker, &QObject::deleteLater);
    d->thread.setObjectName(QStringLiteral("Nova Vulkan render"));
    d->thread.start();
    d->retry.setSingleShot(true);
    d->retry.setInterval(2);
    connect(&d->retry, &QTimer::timeout, this, [this] { if (isExposed()) requestUpdate(); });
    d->retire.setInterval(4);
    connect(&d->retire, &QTimer::timeout, this, [this] {
        if (d->busy || d->retiring) return;
        d->busy = true;
        const auto epoch = d->epoch;
        QMetaObject::invokeMethod(d->worker, [this, epoch] {
            if (d->gpu.renderer) d->gpu.renderer->retireFrames();
            const auto pending = d->gpu.renderer ? d->gpu.renderer->pendingFrames() : 0;
            QMetaObject::invokeMethod(this, [this, epoch, pending] {
                if (epoch != d->epoch) return;
                d->busy = false;
                d->state.pendingSourceFrames = pending;
                if (!pending) d->retire.stop();
                if (d->dirty) requestUpdate();
            }, Qt::QueuedConnection);
        }, Qt::QueuedConnection);
    });
    setSurfaceType(QSurface::VulkanSurface);
    d->instance.setApiVersion(QVersionNumber(1, 2));
    auto extensions = QQuickGraphicsConfiguration::preferredInstanceExtensions();
    if (!extensions.contains(QByteArrayLiteral("VK_EXT_swapchain_colorspace")))
        extensions.append(QByteArrayLiteral("VK_EXT_swapchain_colorspace"));
    d->instance.setExtensions(extensions);
    if (d->instance.supportedApiVersion() < QVersionNumber(1, 2) || !d->instance.create())
        d->state.error = QStringLiteral("Vulkan 1.2 presentation is unavailable.");
    else
        setVulkanInstance(&d->instance);
    connect(this, &QWindow::screenChanged, this, [this] {
        ++d->revision;
        d->state.hdr10Surface = d->state.hdrActive = false;
        requestUpdate();
        emit presentationChanged();
    });
}

DeckVulkanVideoWindow::~DeckVulkanVideoWindow() {
    release();
    // Final object destruction must join. Normal preview close uses the async
    // retireSurface path, leaving the library responsive during GPU draining.
    QMetaObject::invokeMethod(d->worker, [this] {
        if (d->overlay) d->overlay->shutdown();
        pl_vulkan_destroy(&d->gpu.vk);
    }, Qt::BlockingQueuedConnection);
    d->overlay.reset();
    destroy();
    d->thread.quit(); d->thread.wait();
    setVulkanInstance(nullptr);
}

DeckWindowPresentationState DeckVulkanVideoWindow::presentationState() const { return d->state; }
std::shared_ptr<const std::atomic<std::uint64_t>> DeckVulkanVideoWindow::composedFrames() const { return d->composed; }
QQuickWindow* DeckVulkanVideoWindow::enableQuickOverlay() {
    Q_ASSERT(!d->busy && !isVisible());
    if (!d->overlay) {
        d->overlay = std::make_unique<DeckVulkanQuickOverlay>(*this, d->instance);
        d->overlay->prepareThread(&d->thread);
    }
    return d->overlay->window();
}
QQuickWindow* DeckVulkanVideoWindow::quickOverlayWindow() const { return d->overlay ? d->overlay->window() : nullptr; }

bool DeckVulkanVideoWindow::presentVaapiSurface(const DeckQrhiVaapiPresentationDescriptor& descriptor) {
    if (!descriptor.frameLease) { clearFrame(); return false; }
    const auto& lease = descriptor.frameLease;
    if (!descriptor.hardwareBacked || !lease->valid() || !lease->frame() || descriptor.surfaceId != lease->surfaceId()
            || descriptor.width != lease->frame()->width || descriptor.height != lease->frame()->height) {
        clearFrame();
        return false;
    }
    return submitFrame(*lease->frame());
}

bool DeckVulkanVideoWindow::submitFrame(const AVFrame& frame) {
    Q_ASSERT(QThread::currentThread() == thread());
    const auto color = DeckVideoColorInfo::fromFrame(frame);
    if (frame.width <= 0 || frame.height <= 0 || frame.width > 8192 || frame.height > 8192
            || (color.bitDepth != 8 && color.bitDepth != 10) || !color.yuv420
            || (color.hdrSignaled() && !color.hdr10())) {
        clearFrame();
        d->state.error = QStringLiteral("Unsupported video color format.");
        emit presentationChanged();
        return false;
    }
    std::shared_ptr<AVFrame> owned(av_frame_clone(&frame), FreeFrame{});
    if (!owned) {
        d->state.error = QStringLiteral("Could not retain the video frame.");
        emit presentationChanged();
        return false;
    }
    d->frame = std::move(owned);
    ++d->frameSerial;
    d->state.error.clear();
    requestUpdate();
    return true;
}

void DeckVulkanVideoWindow::clearFrame() {
    Q_ASSERT(QThread::currentThread() == thread());
    d->frame.reset();
    ++d->revision;
    d->state.hdrActive = d->state.toneMapped = false;
    d->state.error.clear();
    requestUpdate();
}

void DeckVulkanVideoWindow::setOutputPreference(DeckWindowOutput output) {
    Q_ASSERT(QThread::currentThread() == thread());
    d->output = output;
    ++d->revision;
    d->state.hdrActive = d->state.toneMapped = false;
    requestUpdate();
}

bool DeckVulkanVideoWindow::Impl::initialize(DeckVulkanVideoWindow* window, const Job& job) {
    if (gpu.swapchain) return true;
    gpu.surface = job.surface;
    auto params = pl_vulkan_default_params;
    params.instance = instance.vkInstance();
    params.surface = gpu.surface;
    params.allow_software = allowSoftware;
    // Qt and libplacebo share one queue, serialized on this render thread.
    params.async_transfer = params.async_compute = false;
    params.queue_count = 1;
    if (!gpu.vk) gpu.vk = pl_vulkan_create(nullptr, &params);
    if (gpu.vk && instance.supportsPresent(gpu.vk->phys_device, gpu.vk->queue_graphics.index, window)) {
        pl_vulkan_swapchain_params swap{};
        swap.surface = gpu.surface;
        swap.present_mode = VK_PRESENT_MODE_FIFO_KHR;
        swap.swapchain_depth = 2;
        swap.disable_10bit_sdr = true;
        gpu.swapchain = pl_vulkan_create_swapchain(gpu.vk, &swap);
    }
    if (!gpu.swapchain) {
        release();
        gpu.state.error = QStringLiteral("Could not create the Vulkan presentation swapchain.");
        return false;
    }
    // Software fixtures deliberately test rendering without a hardware import
    // device. Production must not enable Play on a device lacking DMA-BUF input.
    if (!allowSoftware && !(gpu.vk->gpu->import_caps.tex & PL_HANDLE_DMA_BUF)) {
        release();
        gpu.state.error = QStringLiteral("This Vulkan device cannot import decoded video.");
        return false;
    }
    gpu.renderer = std::make_unique<DeckPlaceboColorRenderer>(gpu.vk->gpu);
    if (overlay && !overlay->initialize(gpu.vk)) {
        gpu.state.error = overlay->error(); release(); return false;
    }
    gpu.state.ready = true;
    return true;
}

void DeckVulkanVideoWindow::Impl::release() {
    gpu.pressure.invalidate();
    gpu.renderer.reset(); // GPU drains on the worker, retaining decoder leases.
    gpu.state.pendingSourceFrames = 0;
    pl_swapchain_destroy(&gpu.swapchain);
    // Qt owns its borrowed QRhi until final destruction. Cache the shared
    // device across native surface recreation, with no retained source frames.
    if (!overlay) pl_vulkan_destroy(&gpu.vk);
    gpu.surface = VK_NULL_HANDLE;
    gpu.state.ready = gpu.state.hdr10Surface = gpu.state.hdrActive = gpu.state.toneMapped = false;
    gpu.state.pixelSize = {};
}

void DeckVulkanVideoWindow::release() {
    *d->cancelled = true;
    ++d->epoch;
    d->retry.stop(); d->retire.stop();
    // An OS-enforced surface destruction or final C++ destruction cannot defer
    // VkSurface lifetime. Cancel any pending GUI sync before joining the worker.
    QMetaObject::invokeMethod(d->worker, [this] { d->release(); }, Qt::BlockingQueuedConnection);
    d->busy = d->retiring = d->dirty = false;
    d->cancelled = std::make_shared<std::atomic<bool>>(false);
    d->state.ready = d->state.hdr10Surface = d->state.hdrActive = d->state.toneMapped = false;
    d->state.pendingSourceFrames = 0;
    d->state.pixelSize = {};
}

void DeckVulkanVideoWindow::retireSurface() {
    Q_ASSERT(QThread::currentThread() == thread());
    hide();
    if (d->retiring) return;
    *d->cancelled = true;
    const auto epoch = ++d->epoch;
    d->retiring = true;
    d->retry.stop(); d->retire.stop();
    d->state.ready = d->state.hdr10Surface = d->state.hdrActive = d->state.toneMapped = false;
    d->frame.reset();
    QMetaObject::invokeMethod(d->worker, [this, epoch] {
        d->release();
        QMetaObject::invokeMethod(this, [this, epoch] {
            if (epoch != d->epoch) return;
            d->busy = d->retiring = false;
            d->state.pendingSourceFrames = 0;
            d->cancelled = std::make_shared<std::atomic<bool>>(false);
            if (!isVisible()) destroy();
            else requestUpdate();
        }, Qt::QueuedConnection);
    }, Qt::QueuedConnection);
}

void DeckVulkanVideoWindow::setVideoScaleMode(DeckVideoScaleMode mode) {
    Q_ASSERT(QThread::currentThread() == thread());
    if (d->scale == mode) return;
    d->scale = mode; ++d->revision;
    // Redraw the retained frame, including while the stream sends no new frames.
    requestUpdate();
}
DeckVideoScaleMode DeckVulkanVideoWindow::videoScaleMode() const { return d->scale; }

void DeckVulkanVideoWindow::render() {
    if (!isExposed() || width() <= 0 || height() <= 0) return;
    if (d->busy || d->retiring) { d->dirty = true; return; }
    if (!d->instance.isValid()) { emit presentationChanged(); return; }
    const auto surface = QVulkanInstance::surfaceForWindow(this);
    if (!surface) {
        d->state.error = QStringLiteral("Could not create the Vulkan window surface.");
        emit presentationChanged(); return;
    }
    const auto ratio = devicePixelRatio();
    Impl::Job job{surface, QSize(qRound(width() * ratio), qRound(height() * ratio)), ratio,
        d->frame, d->frameSerial, d->revision, d->epoch, d->output, d->scale, d->cancelled};
    d->busy = true; d->dirty = false;
    QMetaObject::invokeMethod(d->worker, [this, job] {
        const bool retry = d->render(this, job);
        const auto state = d->gpu.state;
        QMetaObject::invokeMethod(this, [this, job, state, retry] {
            if (job.epoch != d->epoch) return;
            d->busy = false;
            if (job.revision == d->revision && isExposed()) {
                d->state = state;
                if (state.pendingSourceFrames) d->retire.start();
                emit presentationChanged();
            } else d->dirty = true;
            if (d->dirty) requestUpdate();
            else if (retry) d->retry.start();
        }, Qt::QueuedConnection);
    }, Qt::QueuedConnection);
}

bool DeckVulkanVideoWindow::Impl::render(DeckVulkanVideoWindow* window, const Job& job) {
    if (*job.cancelled || !initialize(window, job)) return false;
    // Do not acquire/clear a swapchain image when the bounded source pool is
    // full. Leave the last image visible and let newer submissions coalesce.
    if (job.frame && !gpu.renderer->canAcceptFrame()) {
        if (!gpu.pressure.isValid()) gpu.pressure.start();
        if (gpu.pressure.elapsed() >= 1000) {
            gpu.state.error = QStringLiteral("The video renderer stopped making progress.");

        } else { ++gpu.state.deferredFrames; return true; }
        return false;
    }
    gpu.pressure.invalidate();
    gpu.state.error.clear();
    gpu.state.hdrActive = gpu.state.toneMapped = false;
    gpu.state.hdr10Surface = supportsHdr10(instance, gpu.vk, gpu.surface);
    const bool hdrSource = job.frame && DeckVideoColorInfo::fromFrame(*job.frame).hdr10();
    const bool knownPreference = job.output == DeckWindowOutput::Sdr || job.output == DeckWindowOutput::PreferHdr10
        || job.output == DeckWindowOutput::RequireHdr10;
    const bool refuse = !knownPreference || (job.output == DeckWindowOutput::RequireHdr10 &&
        (!hdrSource || !gpu.state.hdr10Surface));
    auto hint = pl_color_space_srgb;
    if (!refuse && hdrSource && job.output != DeckWindowOutput::Sdr && gpu.state.hdr10Surface)
        pl_color_space_from_avframe(&hint, job.frame.get());
    // Every update replaces the old hint, clearing stale HDR metadata on SDR.
    pl_swapchain_colorspace_hint(gpu.swapchain, &hint);
    int w = job.pixels.width(), h = job.pixels.height();
    if (!pl_swapchain_resize(gpu.swapchain, &w, &h) || w <= 0 || h <= 0) {
        gpu.state.error = QStringLiteral("Could not resize the Vulkan presentation surface.");

        return false;
    }
    std::optional<pl_overlay> overlay;
    if (this->overlay) {
        auto overlayJob = job;
        overlayJob.pixels = QSize(w, h);
        const auto update = this->overlay->render(overlayJob.pixels, job.ratio, [&](const auto& sync) {
            return synchronize(window, overlayJob, sync);
        });
        if (update == DeckOverlayUpdate::Deferred) {
            ++gpu.state.deferredFrames; return true;
        }
        if (update == DeckOverlayUpdate::Failed) {
            gpu.state.error = this->overlay->error(); return false;
        }
        gpu.state.overlayRenders = this->overlay->renderedFrames();
        overlay = this->overlay->overlay();
    }
    if (*job.cancelled) return false;
    pl_swapchain_frame surface{};
    if (!pl_swapchain_start_frame(gpu.swapchain, &surface)) {
        gpu.state.error = QStringLiteral("The presentation surface is temporarily unavailable.");

        return false;
    }
    // start/submit are a lock pair, including refusal/render failure paths.
    gpu.state.pixelSize = QSize(surface.fbo->params.w, surface.fbo->params.h);
    const auto actual = deckSurfaceColorOutput(surface);
    const bool wrongOutput = !actual ||
        (job.output == DeckWindowOutput::Sdr && *actual != DeckColorOutput::Srgb) ||
        (job.output == DeckWindowOutput::RequireHdr10 && *actual != DeckColorOutput::Hdr10Pq);
    bool rendered = false;
    if (refuse || wrongOutput) {
        gpu.state.error = QStringLiteral("The requested output format is unavailable on this surface.");
    } else if (job.frame) {
        rendered = gpu.renderer->renderToSurface(*job.frame, surface, overlay ? &*overlay : nullptr, job.scale);
        gpu.state.error = rendered ? QString() : QString::fromStdString(gpu.renderer->error());
    } else if (overlay) {
        rendered = gpu.renderer->renderOverlayToSurface(surface, *overlay);
        gpu.state.error = rendered ? QString() : QString::fromStdString(gpu.renderer->error());
    }
    if (!rendered) {
        pl_frame target{};
        pl_frame_from_swapchain(&target, &surface);
        const float black[]{0, 0, 0};
        pl_frame_clear(gpu.vk->gpu, &target, black);
    }
    gpu.state.pendingSourceFrames = gpu.renderer->pendingFrames();
    // Qt's platform hooks perform Wayland frame-callback / X11 resize-sync
    // bookkeeping even though libplacebo owns vkQueuePresentKHR.
    instance.presentAboutToBeQueued(window);
    const bool submitted = pl_swapchain_submit_frame(gpu.swapchain);
    instance.presentQueued(window);
    if (submitted) {
        ++gpu.state.submittedFrames;
        const bool videoRendered = rendered && bool(job.frame);
        if (videoRendered && !*job.cancelled && gpu.composedSerial != job.serial) {
            gpu.composedSerial = job.serial;
            composed->fetch_add(1);
        }
        gpu.state.hdrActive = videoRendered && actual == DeckColorOutput::Hdr10Pq;
        gpu.state.toneMapped = videoRendered && hdrSource && actual == DeckColorOutput::Srgb;
        pl_swapchain_swap_buffers(gpu.swapchain);
    } else {
        gpu.state.error = QStringLiteral("The Vulkan presentation surface was lost.");
        release();
    }
    return false;
}

void DeckVulkanVideoWindow::exposeEvent(QExposeEvent*) {
    if (isExposed()) requestUpdate();
    else {
        d->state.hdrActive = d->state.toneMapped = false;
        emit presentationChanged();
    }
}

void DeckVulkanVideoWindow::resizeEvent(QResizeEvent*) { requestUpdate(); }

bool DeckVulkanVideoWindow::event(QEvent* event) {
    if (event->type() == QEvent::Close && d->overlay) {
        static_cast<QCloseEvent*>(event)->ignore();
        emit closeRequested();
        return true;
    }
    if (d->overlay) {
        switch (event->type()) {
        case QEvent::KeyPress: case QEvent::KeyRelease: case QEvent::ShortcutOverride:
        case QEvent::MouseButtonPress: case QEvent::MouseButtonRelease: case QEvent::MouseMove:
        case QEvent::MouseButtonDblClick: case QEvent::Wheel:
        case QEvent::TouchBegin: case QEvent::TouchUpdate: case QEvent::TouchEnd: case QEvent::TouchCancel:
        case QEvent::FocusIn: case QEvent::FocusOut:
            return QCoreApplication::sendEvent(d->overlay->window(), event);
        default: break;
        }
    }
    if (event->type() == QEvent::UpdateRequest) {
        render();
        return true;
    }
    if (event->type() == QEvent::PlatformSurface &&
        static_cast<QPlatformSurfaceEvent*>(event)->surfaceEventType() == QPlatformSurfaceEvent::SurfaceAboutToBeDestroyed)
        release();
    return QWindow::event(event);
}

} // namespace nova::deck::stream
