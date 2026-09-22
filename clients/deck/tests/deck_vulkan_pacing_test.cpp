#include "stream/deck_vulkan_quick_overlay.h"
#include "stream/deck_placebo_color_renderer.h"
#include <QGuiApplication>
#include <QQmlComponent>
#include <QQmlEngine>
#include <QQuickItem>
#include <QQuickGraphicsConfiguration>
#include <QVulkanInstance>
#include <QTest>
#include <QTimer>
#include <atomic>
#include <condition_variable>
#include <iostream>
#include <mutex>
#include <thread>
#include <vector>
#include <cstring>
extern "C" {
#include <libavutil/frame.h>
}
using namespace nova::deck::stream;
namespace {
// On this lavapipe version, a supposedly non-waiting shader timing query calls
// DeviceWaitIdle. For the source-pool pressure scenario only, report optional
// timing statistics as unavailable. Actual rendering, fences, queue waits and
// source-texture polling still use the real driver and validation layer.
// This test therefore does NOT establish nonblocking libplacebo shader dispatch.
std::atomic<bool> deferTimingQueries{false};
std::atomic<unsigned> deferredTimingQueries{0};
VKAPI_ATTR VkResult VKAPI_CALL queryResults(VkDevice device, VkQueryPool pool, uint32_t first,
        uint32_t count, size_t size, void* data, VkDeviceSize stride, VkQueryResultFlags flags) {
    if (deferTimingQueries && !(flags & VK_QUERY_RESULT_WAIT_BIT)) {
        ++deferredTimingQueries;
        return VK_NOT_READY;
    }
    return vkGetQueryPoolResults(device, pool, first, count, size, data, stride, flags);
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL deviceFunction(VkDevice device, const char* name) {
    if (!strcmp(name, "vkGetQueryPoolResults")) return reinterpret_cast<PFN_vkVoidFunction>(queryResults);
    return vkGetDeviceProcAddr(device, name);
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL instanceFunction(VkInstance instance, const char* name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return reinterpret_cast<PFN_vkVoidFunction>(deviceFunction);
    if (!strcmp(name, "vkGetQueryPoolResults")) return reinterpret_cast<PFN_vkVoidFunction>(queryResults);
    return vkGetInstanceProcAddr(instance, name);
}
void require(bool ok, const char* why) {
    if (!ok) { std::cerr << why << '\n'; std::exit(1); }
}
// Hold the actual graphics queue on an unsignaled timeline semaphore. A safety
// thread releases it if a regression blocks a supposedly polling call.
struct QueueGate {
    pl_vulkan vk;
    VkSemaphore semaphore;
    std::mutex mutex;
    std::condition_variable wake;
    bool released = false;
    std::atomic<bool> watchdog{false};
    std::thread thread;
    explicit QueueGate(pl_vulkan device) : vk(device) {
        pl_vulkan_sem_params params{}; params.type = VK_SEMAPHORE_TYPE_TIMELINE;
        semaphore = pl_vulkan_sem_create(vk->gpu, &params);
        require(semaphore, "missing gate semaphore");
        const uint64_t value = 1;
        VkTimelineSemaphoreSubmitInfo timeline{VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO};
        timeline.waitSemaphoreValueCount = 1; timeline.pWaitSemaphoreValues = &value;
        const VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.pNext = &timeline; submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &semaphore; submit.pWaitDstStageMask = &stage;
        VkQueue queue; vkGetDeviceQueue(vk->device, vk->queue_graphics.index, 0, &queue);
        vk->lock_queue(vk, vk->queue_graphics.index, 0);
        const auto status = vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE);
        vk->unlock_queue(vk, vk->queue_graphics.index, 0);
        require(status == VK_SUCCESS, "queue gate submit failed");
        thread = std::thread([this] {
            std::unique_lock lock(mutex);
            if (!wake.wait_for(lock, std::chrono::seconds(4), [this] { return released; })) {
                watchdog = true; signal();
            }
        });
    }
    void signal() {
        VkSemaphoreSignalInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO};
        info.semaphore = semaphore; info.value = 1;
        require(vkSignalSemaphore(vk->device, &info) == VK_SUCCESS, "queue gate signal failed");
        released = true; wake.notify_all();
    }
    void open() { const std::lock_guard lock(mutex); if (!released) signal(); }
    ~QueueGate() {
        open(); thread.join(); pl_gpu_finish(vk->gpu);
        pl_vulkan_sem_destroy(vk->gpu, &semaphore);
    }
};
DeckOverlayUpdate finish(DeckVulkanQuickOverlay& overlay, QSize size, qreal ratio = 1) {
    QElapsedTimer clock; clock.start();
    DeckOverlayUpdate result;
    do {
        result = overlay.render(size, ratio);
        if (result != DeckOverlayUpdate::Deferred) return result;
        QTest::qWait(2);
    } while (clock.elapsed() < 1500);
    return result;
}
}
int main(int argc, char** argv) {
    QQuickWindow::setGraphicsApi(QSGRendererInterface::Vulkan);
    QGuiApplication app(argc, argv);
    QVulkanInstance instance;
    instance.setApiVersion(QVersionNumber(1, 2));
    instance.setExtensions(QQuickGraphicsConfiguration::preferredInstanceExtensions());
    require(instance.create(), "no Vulkan instance");
    QWindow target; target.setSurfaceType(QSurface::VulkanSurface); target.setVulkanInstance(&instance);
    auto params = pl_vulkan_default_params;
    params.instance = instance.vkInstance(); params.allow_software = true;
    params.get_proc_addr = instanceFunction;
    params.async_compute = params.async_transfer = false; params.queue_count = 1;
    auto vk = pl_vulkan_create(nullptr, &params);
    require(vk, "no software validation device");
    {
        DeckVulkanQuickOverlay overlay(target, instance);
        require(overlay.initialize(vk), qPrintable(overlay.error()));
        QQmlEngine engine; QQmlComponent component(&engine);
        component.setData("import QtQuick\nRectangle { width: parent ? parent.width : 64; height: parent ? parent.height : 64; color: 'red' }", QUrl());
        std::unique_ptr<QObject> item(component.create());
        require(bool(item), qPrintable(component.errorString()));
        qobject_cast<QQuickItem*>(item.get())->setParentItem(overlay.window()->contentItem());
        require(finish(overlay, {64, 64}) == DeckOverlayUpdate::Ready, "initial overlay failed");
        // Allow initial geometry/polish invalidations to settle.
        QTest::qWait(20); require(finish(overlay, {64, 64}) == DeckOverlayUpdate::Ready, "settle failed");
        const auto draws = overlay.renderedFrames();
        const auto texture = overlay.overlay().tex;
        {
            QueueGate gate(vk);
            QElapsedTimer elapsed; elapsed.start();
            for (int i = 0; i < 20; ++i)
                require(overlay.render({64, 64}, 1) == DeckOverlayUpdate::Ready, "unchanged overlay acquired a busy texture");
            require(elapsed.elapsed() < 250 && overlay.renderedFrames() == draws && overlay.overlay().tex == texture,
                "unchanged UI rendered or waited for the GPU");
            item->setProperty("color", "lime"); QCoreApplication::processEvents();
            elapsed.restart();
            require(overlay.render({64, 64}, 1) == DeckOverlayUpdate::Deferred, "busy overlay did not defer");
            require(elapsed.elapsed() < 250 && overlay.error().isEmpty() && !overlay.overlay().tex,
                "pending UI waited, failed or exposed a held texture");
            int heartbeats = 0;
            QTimer timer; timer.setInterval(2);
            QObject::connect(&timer, &QTimer::timeout, [&] { ++heartbeats; }); timer.start();
            for (int i = 0; i < 8; ++i) {
                require(overlay.render({96, 64}, 1) == DeckOverlayUpdate::Deferred, "pending resize lost ownership");
                QTest::qWait(4);
            }
            require(heartbeats >= 4 && !gate.watchdog && overlay.renderedFrames() == draws,
                "GPU backpressure starved GUI events or drew early");
            gate.open();
            require(finish(overlay, {96, 64}) == DeckOverlayUpdate::Ready, "deferred resize did not recover");
            require(!gate.watchdog && overlay.renderedFrames() == draws + 1, "deferred updates accumulated draws");
        }
        // Verify that the updated texture has the latest UI pixels.
        pl_tex_params textureParams{};
        textureParams.w = 96; textureParams.h = 64;
        textureParams.format = pl_find_named_fmt(vk->gpu, "rgba8");
        textureParams.renderable = textureParams.host_readable = true;
        auto output = pl_tex_create(vk->gpu, &textureParams);
        require(output, "missing output texture");
        {
            DeckPlaceboColorRenderer renderer(vk->gpu);
            pl_swapchain_frame surface{}; surface.fbo = output;
            surface.color_repr = pl_color_repr_rgb; surface.color_space = pl_color_space_srgb;
            require(renderer.renderOverlayToSurface(surface, overlay.overlay()), "overlay composition failed");
            std::vector<uint8_t> pixels(96 * 64 * 4);
            pl_tex_transfer_params transfer{}; transfer.tex = output; transfer.ptr = pixels.data();
            require(pl_tex_download(vk->gpu, &transfer), "overlay readback failed");
            const auto middle = (32 * 96 + 48) * 4;
            require(pixels[middle] < 3 && pixels[middle + 1] > 250 && pixels[middle + 2] < 3,
                "deferred repaint retained stale pixels");
            require(finish(overlay, {96, 64}, 2) == DeckOverlayUpdate::Ready && overlay.window()->size() == QSize(48, 32),
                "pixel-ratio change reused stale logical geometry");
            // Prime the shader before gating the queue, then fill the real
            // three-frame pool. Pressure must be visible before image acquire.
            auto* frame = av_frame_alloc(); require(frame, "no frame");
            frame->format = AV_PIX_FMT_YUV420P; frame->width = 96; frame->height = 64;
            frame->color_primaries = AVCOL_PRI_BT709; frame->color_trc = AVCOL_TRC_BT709;
            frame->colorspace = AVCOL_SPC_BT709; frame->color_range = AVCOL_RANGE_MPEG;
            require(av_frame_get_buffer(frame, 32) == 0, "no frame backing");
            for (int p = 0; p < 3; ++p) for (int y = 0; y < (p ? 32 : 64); ++y)
                memset(frame->data[p] + y * frame->linesize[p], 128, p ? 48 : 96);
            require(renderer.render(*frame, output, DeckColorOutput::Srgb), "initial video draw failed");
            pl_gpu_finish(vk->gpu); require(renderer.canAcceptFrame(), "completed source did not retire");
            deferTimingQueries = true;
            {
                QueueGate gate(vk);
                for (int i = 0; i < 3; ++i) {
                    require(renderer.canAcceptFrame(), "pool filled early");
                    require(renderer.render(*frame, output, DeckColorOutput::Srgb), "bounded video draw failed");
                    pl_gpu_flush(vk->gpu);
                }
                QElapsedTimer elapsed; elapsed.start();
                require(!renderer.canAcceptFrame() && renderer.pendingFrames() == 3 && renderer.error().empty(),
                    "source pressure grew the pool or became a rendering failure");
                require(elapsed.elapsed() < 250 && !gate.watchdog, "source polling waited for GPU");
                gate.open(); pl_gpu_finish(vk->gpu); renderer.retireFrames();
                require(renderer.pendingFrames() == 0 && renderer.canAcceptFrame(), "idle renderer retained completed sources");
            }
            deferTimingQueries = false;
            require(deferredTimingQueries > 0, "source-pool timing-query isolation was not exercised");
            av_frame_free(&frame);
        }
        pl_tex_destroy(vk->gpu, &output);
        // A stalled acquisition eventually fails, but each GUI call stays a
        // poll. Releasing the same pending semaphore recovers without re-hold.
        {
            QueueGate gate(vk);
            item->setProperty("color", "blue"); QCoreApplication::processEvents();
            require(overlay.render({96, 64}, 2) == DeckOverlayUpdate::Deferred, "stall did not begin pending");
            QTest::qWait(1050);
            require(overlay.render({96, 64}, 2) == DeckOverlayUpdate::Failed && !overlay.error().isEmpty(), "stalled GPU never failed");
            gate.open(); pl_gpu_finish(vk->gpu);
            require(finish(overlay, {96, 64}, 2) == DeckOverlayUpdate::Ready && overlay.error().isEmpty() && !gate.watchdog,
                "signaled pending texture could not recover");
        }
    }
    pl_vulkan_destroy(&vk);
    target.setVulkanInstance(nullptr);
    std::cout << "Vulkan pacing passed: cached UI, zero-wait ownership, GUI heartbeats under GPU pressure, coalesced resize, latest pixels, DPR, bounded source pressure/retirement and timeout recovery\n";
    std::cout << "Source-pool scenario alone deferred " << deferredTimingQueries << " optional shader timing queries; driver-wide nonblocking rendering remains unverified\n";
}
