#include "runtime/deck_vulkan_session_view.h"
#include "runtime/deck_play_settings.h"
#include "deck_preview_fixture.h"
#include <QGuiApplication>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickWindow>
#include <QScreen>
#include <QTemporaryDir>
#include <QTest>
#include <QDir>
#include <iostream>
#include <vulkan/vulkan.h>
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <QTimer>
extern "C" {
#include <libavutil/frame.h>
}
using namespace nova::deck::runtime;
using namespace nova::deck::stream;
namespace {
void require(bool ok, const char* why) {
    if (!ok) { std::cerr << why << '\n'; std::exit(1); }
}
void wait(const std::function<bool()>& done, const char* why) {
    QElapsedTimer clock; clock.start();
    while (!done() && clock.elapsed() < 7000) QTest::qWait(10);
    require(done(), why);
}
QQuickItem* visualChild(QQuickItem* item, const QString& name) {
    if (item->objectName()==name) return item;
    for (auto* child : item->childItems()) if (auto* found=visualChild(child,name)) return found;
    return nullptr;
}
void key(QWindow* window, int code) { QTest::keyClick(window, static_cast<Qt::Key>(code)); QTest::qWait(80); }
// Real queue pressure, with no driver/query interception. The guard releases
// the queue even if a regression parks GUI, turning a hang into a test failure.
struct QueueGate {
    VkDevice device = VK_NULL_HANDLE;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    std::atomic<bool> armed{false}, entered{false}, watchdog{false}, offGui{false};
    std::mutex mutex;
    std::condition_variable wake;
    bool released = false;
    std::thread guard;
    void block(QQuickWindow* window) {
        if (armed.exchange(true)) return;
        offGui = QThread::currentThread() != QCoreApplication::instance()->thread();
        auto* api = window->rendererInterface();
        auto* native = static_cast<VkDevice*>(api->getResource(window, QSGRendererInterface::DeviceResource));
        auto* queue = static_cast<VkQueue*>(api->getResource(window, QSGRendererInterface::CommandQueueResource));
        require(native && queue, "missing actual Qt Vulkan device/queue");
        device = *native;
        VkSemaphoreTypeCreateInfo timeline{VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO};
        timeline.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
        VkSemaphoreCreateInfo create{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO}; create.pNext = &timeline;
        require(vkCreateSemaphore(device, &create, nullptr, &semaphore) == VK_SUCCESS, "gate creation failed");
        const uint64_t value = 1;
        VkTimelineSemaphoreSubmitInfo values{VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO};
        values.waitSemaphoreValueCount = 1; values.pWaitSemaphoreValues = &value;
        const VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.pNext = &values;
        submit.waitSemaphoreCount = 1; submit.pWaitSemaphores = &semaphore; submit.pWaitDstStageMask = &stage;
        require(vkQueueSubmit(*queue, 1, &submit, VK_NULL_HANDLE) == VK_SUCCESS, "gate submission failed");
        guard = std::thread([this] {
            std::unique_lock lock(mutex);
            if (!wake.wait_for(lock, std::chrono::seconds(4), [this] { return released; })) {
                watchdog = true; signal();
            }
        });
        entered = true;
    }
    void signal() {
        VkSemaphoreSignalInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO};
        info.semaphore = semaphore; info.value = 1;
        require(vkSignalSemaphore(device, &info) == VK_SUCCESS, "gate signal failed");
        released = true; wake.notify_all();
    }
    void open() { std::lock_guard lock(mutex); if (!released && entered) signal(); }
    ~QueueGate() {
        open();
        if (guard.joinable()) guard.join();
        if (semaphore) {
            vkDeviceWaitIdle(device);
            vkDestroySemaphore(device, semaphore, nullptr);
        }
    }
};
AVFrame* frame() {
    auto* result = av_frame_alloc();
    require(result, "no video frame");
    result->format = AV_PIX_FMT_YUV420P10LE; result->width = 128; result->height = 80;
    result->color_primaries = AVCOL_PRI_BT2020; result->color_trc = AVCOL_TRC_SMPTE2084;
    result->colorspace = AVCOL_SPC_BT2020_NCL; result->color_range = AVCOL_RANGE_MPEG;
    require(av_frame_get_buffer(result, 32) == 0, "no video backing");
    for (int p = 0; p < 3; ++p) for (int y = 0; y < (p ? 40 : 80); ++y)
        for (int x = 0; x < (p ? 64 : 128); ++x)
            reinterpret_cast<uint16_t*>(result->data[p] + y * result->linesize[p])[x] = 512;
    return result;
}
}
int main(int argc, char** argv) {
    const bool pressure = argc > 1 && QByteArray(argv[1]) == "--thread-pressure";
    const bool refuse = argc > 1 && QByteArray(argv[1]) == "--reject-overlay";
    if (refuse) qputenv("QSG_RHI_BACKEND", "opengl");
    QQuickWindow::setGraphicsApi(refuse ? QSGRendererInterface::OpenGL : QSGRendererInterface::Vulkan);
    QTemporaryDir config;
    qputenv("XDG_CONFIG_HOME", config.path().toUtf8());
    QGuiApplication app(argc, argv);
    QCoreApplication::setOrganizationName("NovaDeckTests");
    QCoreApplication::setApplicationName("VulkanOverlay");
    qmlRegisterType<DeckQtQuickRhiVaapiItem>("Nova.Deck.Stream", 0, 1, "DeckVaapiPreviewSurface");
    // No network, hardware decoder or game launch: the production bridge binds
    // a disabled real session; the QML fixture supplies only player-facing state.
    DeckNativeSessionController owner(false, {});
    DeckDisplayCapabilities display;
    DeckPlaySettings settings(config.filePath("play.ini"));
    DeckDesktopInputBridge desktopInput(owner, settings);
    DeckVulkanSessionView view(owner, display, settings, desktopInput, true);
    PreviewSession session;
    PreviewPlayers players(session);
    settings.setVideoDecodeSupport({.h264 = {4096, 4096}});
    QQmlEngine engine;
    engine.rootContext()->setContextProperty("testSession", &session);
    engine.rootContext()->setContextProperty("testPlayers", &players);
    engine.rootContext()->setContextProperty("testSettings", &settings);
    engine.rootContext()->setContextProperty("testDisplay", &display);
    engine.rootContext()->setContextProperty("testView", &view);
    QQmlComponent component(&engine);
    component.setData("import QtQuick\nimport QtQuick.Controls\nimport \"" +
        QUrl::fromLocalFile(NOVA_DECK_QML_DIRECTORY).toEncoded() + "\"\n" + R"(
        ApplicationWindow {
            width: 1280; height: 800; visible: true
            Button { id: library; objectName: "library"; text: "Library"; focus: true }
            NativeStreamPreview {
                id: preview
                presentationBridge: testView
                session: testSession; inputHub: testPlayers; settingsProvider: testSettings
                displayCapabilities: testDisplay.state
                hostId: "fixture-host"; gameId: "fixture-game"
                hostName: "Living Room PC"; gameTitle: "Moonlit Harbor"
                onClosed: library.forceActiveFocus()
            }
            function showHud() { NovaHudPreferences.setEnabled(true) }
            function openPreview() { preview.open() }
        }
    )", QUrl());
    require(component.isReady(), qPrintable(component.errorString()));
    std::unique_ptr<QObject> root(component.create());
    require(bool(root), qPrintable(component.errorString()));
    auto* library = qobject_cast<QQuickWindow*>(root.get());
    auto* preview = root->findChild<QObject*>("native-stream-preview");
    auto* play = root->findChild<QQuickItem*>("native-preview-action");
    auto* end = root->findChild<QQuickItem*>("native-end-action");
    auto* controls = root->findChild<QQuickItem*>("native-show-controls");
    auto* resolution = root->findChild<QQuickItem*>("play-setup-resolution");
    require(library && preview && play && end && controls && resolution, "missing production controls");
    auto* target = view.window();
    auto* overlay = target->quickOverlayWindow();
    const auto focused = [&](QQuickItem* item) {
        wait([&] { return overlay->activeFocusItem() == item; }, "native navigation lost focus");
    };
    const auto pixels = [&] { return target->screen()->grabWindow(target->winId()).toImage(); };
    const auto capture = [&](const char* name) {
        if (argc < 2) return;
        require(QDir().mkpath(QString::fromLocal8Bit(argv[1])), "capture directory failed");
        require(pixels().save(QDir(QString::fromLocal8Bit(argv[1])).filePath(name)), "capture failed");
    };
    wait([&] { return library->isExposed(); }, "library did not expose");
    if (refuse) {
        // Reject an incompatible Qt renderer before enabling Play. Retrying
        // must also settle safely without leaving a hidden modal popup.
        for (int i = 0; i < 2; ++i) {
            QMetaObject::invokeMethod(root.get(), "openPreview");
            wait([&] { return !view.error().isEmpty() && library->isActive(); }, "initialization failure lost library");
            require(!view.ready() && !target->isVisible() && !play->isEnabled() && session.starts == 0,
                "initialization failure enabled Play or left a native window");
            require(preview->property("opened").toBool() && !preview->property("externalVideo").toBool(),
                "initialization failure hid recovery controls");
            require(root->findChild<QQuickItem*>("native-presentation-error")->isVisible(), "missing presentation error");
            wait([&] { return library->activeFocusItem() == root->findChild<QQuickItem*>("play-setup-back"); },
                "initialization failure did not focus Back");
            key(library, Qt::Key_Escape);
            wait([&] { return !preview->property("opened").toBool(); }, "failed initialization trapped Back");
        }
        std::cout << "Vulkan overlay initialization refusal and retry preserve library/Back and disable Play\n";
        return 0;
    }
    if (pressure) {
        auto* video = frame();
        for (int phase = 0; phase < 2; ++phase) {
            QMetaObject::invokeMethod(root.get(), "openPreview");
            wait([&] { return view.ready() && target->isActive(); }, "pressure preview did not open");
            wait([&] { return preview->property("opened").toBool(); }, "preview transition not finished");
            play->forceActiveFocus();
            require(target->submitFrame(*video), "pressure fixture refused");
            wait([&] { return target->composedFrames()->load() > uint64_t(phase); }, "pressure prime failed");
            QTest::qWait(150);
            QueueGate gate;
            const auto connection = phase == 0
                ? QObject::connect(overlay, &QQuickWindow::afterRendering, overlay, [&] { gate.block(overlay); }, Qt::DirectConnection)
                : QObject::connect(overlay, &QQuickWindow::afterFrameEnd, overlay, [&] { gate.block(overlay); }, Qt::DirectConnection);
            // Focus changes force a real overlay draw. Phase 0 stalls Qt's
            // endFrame; phase 1 stalls subsequent libplacebo/presentation work.
            QTest::keyClick(target, Qt::Key_Down);
            wait([&] { return gate.entered.load(); }, "GPU queue gate never entered");
            require(gate.offGui, "Vulkan rendering still runs on GUI");
            const auto submitted = target->presentationState().submittedFrames;
            const auto composed = target->composedFrames()->load();
            int heartbeats = 0;
            QTimer heartbeat; heartbeat.setInterval(5);
            QObject::connect(&heartbeat, &QTimer::timeout, [&] { ++heartbeats; }); heartbeat.start();
            QElapsedTimer latency; latency.start();
            QTest::keyClick(target, Qt::Key_Return);
            require(root->findChild<QObject*>("play-setup-picker")->property("opened").toBool(), "D-pad input blocked behind GPU");
            QMetaObject::invokeMethod(preview, "leave");
            // Queue many decoder replacements and resizes while GPU is held.
            for (int i = 0; i < 80; ++i) {
                require(target->submitFrame(*video), "coalesced frame refused");
                target->resize(1000 + i, 700);
            }
            QTest::qWait(120);
            require(latency.elapsed() < 600 && heartbeats >= 8 && !gate.watchdog,
                "GPU pressure starved controls or GUI timers");
            require(target->presentationState().submittedFrames == submitted,
                "queue gate did not stall actual GPU work");
            require(av_buffer_get_ref_count(video->buf[0]) <= 7, "pending frames grew beyond bounded ownership");
            // A touch opens a picker while the worker remains stalled.
            auto* touch = QTest::createTouchDevice();
            const auto point = resolution->mapToScene(QPointF(resolution->width() / 2, resolution->height() / 2)).toPoint();
            QTest::touchEvent(target, touch).press(0, point, target).commit();
            QTest::touchEvent(target, touch).release(0, point, target).commit();
            require(root->findChild<QObject*>("play-setup-picker")->property("opened").toBool(), "touch input blocked behind GPU");
            QMetaObject::invokeMethod(preview, "leave");
            latency.restart();
            QMetaObject::invokeMethod(preview, "close");
            wait([&] { return !target->isVisible() && library->isActive(); }, "cancel did not return to library");
            require(latency.elapsed() < 600 && !gate.watchdog && !view.ready(), "cancel waited for GPU teardown");
            // Reopen before drain completes: retain the surface, reject stale
            // completion, then render only the latest request after retirement.
            QMetaObject::invokeMethod(root.get(), "openPreview");
            require(!view.ready(), "reopen inherited cancelled ready state");
            QTest::qWait(80);
            require(!view.ready() && !gate.watchdog, "old work revived a cancelled preview");
            QObject::disconnect(connection);
            gate.open();
            wait([&] { return view.ready(); }, "reopen did not recover after GPU release");
            require(target->composedFrames()->load() == composed, "cancelled video changed the composition counter");
            wait([&] { return av_buffer_get_ref_count(video->buf[0]) == 1; }, "retirement leaked a decoder frame");
            QMetaObject::invokeMethod(preview, "close");
            wait([&] { return !target->isVisible() && library->isActive(); }, "recovered preview did not close");
            QTest::qWait(100);
            require(!gate.watchdog, "queue release required watchdog");
            std::cout << (phase == 0 ? "Qt completion" : "libplacebo/presentation")
                << ": real GPU stall; D-pad, touch, timers, bounded frames, async cancel/reopen passed\n";
        }
        // Close before a just-posted GUI synchronization callback can run.
        // Final surface destruction must cancel that ticket, not wait for a
        // callback behind itself on the GUI event queue.
        QMetaObject::invokeMethod(root.get(), "openPreview");
        wait([&] { return view.ready(); }, "sync-cancel preview did not open");
        std::atomic<bool> cancelPosted{false};
        const auto cancelConnection = QObject::connect(overlay, &QQuickWindow::beforeFrameBegin, overlay, [&] {
            if (cancelPosted.exchange(true)) return;
            QMetaObject::invokeMethod(&view, [&] {
                QMetaObject::invokeMethod(preview, "close");
                target->destroy(); // Forced lifetime boundary, pending sync.
            }, Qt::QueuedConnection);
        }, Qt::DirectConnection);
        play->forceActiveFocus();
        QTest::keyClick(target, Qt::Key_Down);
        wait([&] { return cancelPosted && !target->isVisible(); }, "pending GUI sync deadlocked close");
        QObject::disconnect(cancelConnection);
        QTest::qWait(50);
        QMetaObject::invokeMethod(root.get(), "openPreview");
        wait([&] { return view.ready(); }, "sync cancellation prevented recreation");
        QMetaObject::invokeMethod(preview, "close");
        QTest::qWait(100);
        require(av_buffer_get_ref_count(video->buf[0]) == 1, "sync cancellation retained frames");
        std::cout << "Pending GUI sync cancellation and forced surface recreation passed\n";
        av_frame_free(&video);
        return 0;
    }
    for (int cycle = 0; cycle < 3; ++cycle) {
        QMetaObject::invokeMethod(root.get(), "openPreview");
        wait([&] { return view.ready() || !view.error().isEmpty(); }, "Vulkan preview did not settle");
        require(view.ready(), qPrintable(view.error()));
        wait([&] { return target->isActive() && preview->property("opened").toBool(); }, "preview did not activate");
        require(preview->property("externalVideo").toBool(), "popup stayed on the library");
        focused(play);
        require(play->isEnabled() && session.starts == 0, "review launched or remained disabled");
        key(target, Qt::Key_Down); focused(resolution);
        key(target, Qt::Key_Return);
        require(root->findChild<QObject*>("play-setup-picker")->property("opened").toBool(), "picker failed");
        QMetaObject::invokeMethod(preview, "leave"); QTest::qWait(80); focused(resolution);
        if (cycle != 2) {
            QMetaObject::invokeMethod(preview, "close");
            wait([&] { return !target->isVisible() && library->isActive(); }, "close did not restore library");
            require(!view.ready() && !preview->property("externalVideo").toBool(), "close retained native presentation");
        }
    }
    play->forceActiveFocus(); key(target, Qt::Key_Return);
    require(session.starts == 1, "review did not launch exactly once");
    session.transition("active", true, "Your game is connected.");
    QTest::qWait(150); focused(play);
    auto* video = frame();
    require(target->submitFrame(*video), "video fixture refused");
    wait([&] { return target->composedFrames()->load() == 1; }, "video not composed");
    auto* scaleAction=root->findChild<QQuickItem*>("native-video-scale");
    auto* scalePopup=root->findChild<QObject*>("video-scale-popup");
    require(scaleAction && scalePopup,"scaling missing from native overlay");
    scaleAction->forceActiveFocus(); key(target,Qt::Key_Return);
    auto* scaleFill=visualChild(overlay->contentItem(),"video-scale-fill");
    auto* scaleBack=scalePopup->findChild<QQuickItem*>("video-scale-back");
    require(scaleFill && scaleBack && scaleFill->window()==overlay && scaleBack->window()==overlay,"scaling editor was left on library window");
    scaleFill->forceActiveFocus(); key(target,Qt::Key_Return);
    require(settings.videoScaleMode()=="fill" && target->videoScaleMode()==DeckVideoScaleMode::Fill,"saved scaling did not reach Vulkan bridge");
    focused(scaleFill); capture("vulkan-video-scaling.png");
    QMetaObject::invokeMethod(preview,"leave"); QTest::qWait(150); focused(scaleAction);
    require(!scalePopup->property("opened").toBool() && session.controlsVisible(),"scaling Back resumed or stranded editor");
    require(settings.resetVideoScaleMode() && target->videoScaleMode()==DeckVideoScaleMode::Fit,"scaling reset missed Vulkan bridge");
    play->forceActiveFocus();
    capture("vulkan-command-center.png");
    key(target, Qt::Key_Right); key(target, Qt::Key_Right); focused(end);
    key(target, Qt::Key_Return);
    require(root->findChild<QObject*>("native-end-confirmation")->property("opened").toBool() && session.stops == 0,
        "end bypassed confirmation");
    auto* stay = root->findChild<QQuickItem*>("native-end-stay");
    auto* confirm = root->findChild<QQuickItem*>("native-end-confirm");
    require(stay && confirm && stay->window() == overlay && confirm->window() == overlay,
        "end confirmation belongs to a different window");
    focused(stay);
    capture("vulkan-end-confirmation.png");
    key(target, Qt::Key_Return);
    require(session.stops == 0 && !root->findChild<QObject*>("native-end-confirmation")->property("opened").toBool(),
        "Keep playing ended the session");
    focused(end); key(target, Qt::Key_Return); focused(stay);
    key(target, Qt::Key_Down); focused(confirm);
    QKeyEvent held(QEvent::KeyPress, Qt::Key_Return, Qt::NoModifier, QString(), true, 1);
    QCoreApplication::sendEvent(target, &held);
    require(session.stops == 0, "held activation confirmed End Session");
    key(target, Qt::Key_Return);
    require(session.stops == 1 && !root->findChild<QObject*>("native-end-confirmation")->property("opened").toBool(),
        "confirmed End Session did not stop exactly once");
    session.transition("active", true, "Your game is connected.");
    session.showControls(); QTest::qWait(80);
    auto* endTouch = QTest::createTouchDevice();
    const auto tap = [&](QQuickItem* item) {
        const auto point = item->mapToScene(QPointF(item->width() / 2, item->height() / 2)).toPoint();
        QTest::touchEvent(target, endTouch).press(0, point, target).commit();
        QTest::touchEvent(target, endTouch).release(0, point, target).commit();
        QTest::qWait(80);
    };
    tap(end); focused(stay);
    require(session.stops == 1, "touch End Session skipped confirmation");
    tap(confirm);
    require(session.stops == 2 && !root->findChild<QObject*>("native-end-confirmation")->property("opened").toBool(),
        "touch confirmation did not stop exactly once");
    session.transition("active", true, "Your game is connected.");
    session.resumeInput();
    QMetaObject::invokeMethod(root.get(), "showHud"); QTest::qWait(150);
    wait([&] { const auto image = pixels(); return !image.isNull() && image.pixelColor(640, 400).red() > 100; },
        "opaque overlay hid the video");
    const auto image = pixels();
    const auto center = image.pixelColor(640, 400);
    require(center.red() < 180 && qAbs(center.red() - center.blue()) <= 2 && target->presentationState().toneMapped,
        "overlay changed HDR-to-SDR video color");
    require(image.pixelColor(30, 30) != center, "HUD was missing from composed pixels");
    capture("vulkan-video-hud.png");
    const auto redraws = target->presentationState().submittedFrames;
    for (int i = 0; i < 4; ++i) { emit session.hudChanged(); target->requestUpdate(); QTest::qWait(80); }
    require(target->presentationState().submittedFrames > redraws && target->composedFrames()->load() == 1,
        "HUD repaints inflated video composition count");
    // Native touch events are forwarded into the redirected QQuickWindow.
    auto* touch = QTest::createTouchDevice();
    auto point = controls->mapToScene(QPointF(controls->width() / 2, controls->height() / 2)).toPoint();
    QTest::touchEvent(target, touch).press(0, point, target).commit();
    QTest::touchEvent(target, touch).release(0, point, target).commit();
    wait([&] { return session.controlsVisible(); }, "native touch did not open controls");
    focused(play);
    session.resumeInput(); QTest::qWait(80);
    QTest::mouseClick(target, Qt::LeftButton, Qt::NoModifier, point);
    wait([&] { return session.controlsVisible(); }, "native pointer did not open controls");
    target->resize(960, 600);
    wait([&] { return target->presentationState().pixelSize == QSize(960, 600); }, "overlay resize failed");
    require(preview->property("width").toInt() == 960, "popup did not follow native size");
    capture("vulkan-command-center-960.png");
    session.resumeInput(); QTest::qWait(80);
    require(!target->presentVaapiSurface({}), "empty presentation descriptor was accepted as video");
    target->requestUpdate(); QTest::qWait(200);
    require(pixels().pixelColor(480, 300).red() < 3 && target->composedFrames()->load() == 1,
        "clear left stale video or counted a frame");
    require(target->submitFrame(*video), "replacement video refused");
    wait([&] { return target->composedFrames()->load() == 2; }, "replacement not counted once");
    QTest::qWait(150);
    const auto uiDraws = target->presentationState().overlayRenders;
    for (int i = 0; i < 12; ++i) {
        require(target->submitFrame(*video), "video-only update refused");
        wait([&] { return target->composedFrames()->load() == uint64_t(i + 3); }, "video-only update did not compose");
    }
    require(target->presentationState().overlayRenders == uiDraws, "video-only updates redrew unchanged production UI");
    std::cout << "Production overlay: 12 video-only compositions, 0 additional Qt UI draws\n";
    target->clearFrame();
    wait([&] { return target->presentationState().pendingSourceFrames == 0; }, "idle stream retained completed source mappings");
    // A presentation refusal must return accessible error/Back controls to the
    // library rather than leave an invisible modal popup on a dead surface.
    target->setOutputPreference(DeckWindowOutput::RequireHdr10);
    wait([&] { return !view.error().isEmpty() && !target->isVisible(); }, "failure did not restore library");
    require(!view.ready() && !preview->property("externalVideo").toBool(), "failed presentation stayed active");
    session.transition("failed", false, "Fixture ended.");
    QMetaObject::invokeMethod(preview, "close"); QTest::qWait(100);
    require(av_buffer_get_ref_count(video->buf[0]) == 1, "teardown retained video");
    av_frame_free(&video);
    std::cout << "Vulkan overlay passed: production review, repeated attach/detach, native keys/touch/pointer, Command Center, HUD/video pixels, distinct frame counts, resize, clear and failure return\n";
}
