#include "stream/deck_stream_media_adapters.h"

#include <Limelight.h>

#include <QByteArray>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QGuiApplication>
#include <QQuickWindow>
#include <QSGRendererInterface>
#include <QTimer>

#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
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

std::mutex g_messageMutex;
std::vector<std::string> g_qtMessages;

void recordingMessageHandler(QtMsgType type, const QMessageLogContext& context, const QString& message) {
    (void)type;
    (void)context;
    const std::string line = message.toStdString();
    {
        std::lock_guard<std::mutex> lock(g_messageMutex);
        g_qtMessages.push_back(line);
    }
    std::cerr << line << '\n';
}

bool recordedMessageContains(std::string_view needle) {
    std::lock_guard<std::mutex> lock(g_messageMutex);
    return std::any_of(g_qtMessages.begin(), g_qtMessages.end(), [needle](const std::string& line) {
        return line.find(needle) != std::string::npos;
    });
}

int recordedMessageCount(std::string_view needle) {
    std::lock_guard<std::mutex> lock(g_messageMutex);
    return static_cast<int>(std::count_if(g_qtMessages.begin(), g_qtMessages.end(), [needle](const std::string& line) {
        return line.find(needle) != std::string::npos;
    }));
}

std::string joinedRecordedMessages() {
    std::lock_guard<std::mutex> lock(g_messageMutex);
    std::string joined;
    for (const std::string& line : g_qtMessages) {
        joined += line;
        joined += '\n';
    }
    return joined;
}

std::vector<std::uint8_t> readBinaryFile(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!require(input.good(), "expected generated H.264 sample to be readable")) {
        return {};
    }
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

std::vector<std::uint8_t> makeLocalAnnexBH264IdrSample() {
    const auto output = std::filesystem::temp_directory_path() / ("nova-deck-scenegraph-idr-" + std::to_string(getpid()) + ".h264");
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
    std::error_code ignored;
    std::filesystem::remove(output, ignored);
    return bytes;
}

DECODE_UNIT makeDecodeUnit(std::vector<std::uint8_t>& annexBBytes, LENTRY& entry, int frameNumber) {
    entry.next = nullptr;
    entry.data = reinterpret_cast<char*>(annexBBytes.data());
    entry.length = static_cast<int>(annexBBytes.size());
    entry.bufferType = BUFFER_TYPE_PICDATA;

    DECODE_UNIT unit{};
    unit.frameNumber = frameNumber;
    unit.frameType = FRAME_TYPE_IDR;
    unit.fullLength = entry.length;
    unit.bufferList = &entry;
    return unit;
}

bool waitForRenderPasses(QGuiApplication& app, QQuickWindow& window, nova::deck::stream::DeckQtQuickRhiVaapiItem& vaapiItem, int expectedPasses) {
    QElapsedTimer timer;
    timer.start();
    while (timer.elapsed() < 5000 && recordedMessageCount("Nova Deck QSGRenderNode VAAPI/EGL render path") < expectedPasses) {
        app.processEvents(QEventLoop::AllEvents, 50);
        window.requestUpdate();
        vaapiItem.update();
        QTimer::singleShot(0, &app, [] {});
    }
    return recordedMessageCount("Nova Deck QSGRenderNode VAAPI/EGL render path") >= expectedPasses;
}

std::string environmentDetail(const QQuickWindow& window, const nova::deck::stream::DeckRendererLifecycle& lifecycle) {
    return "WAYLAND_DISPLAY=" + std::string(qgetenv("WAYLAND_DISPLAY").constData()) +
        " XDG_RUNTIME_DIR=" + std::string(qgetenv("XDG_RUNTIME_DIR").constData()) +
        " QT_QPA_PLATFORM=" + std::string(qgetenv("QT_QPA_PLATFORM").constData()) +
        " QSG_RHI_BACKEND=" + std::string(qgetenv("QSG_RHI_BACKEND").constData()) +
        " graphicsApi=" + std::to_string(static_cast<int>(window.rendererInterface()->graphicsApi())) +
        " sceneGraphInitialized=" + std::to_string(window.isSceneGraphInitialized()) +
        " exposed=" + std::to_string(window.isExposed()) +
        " vaapi=" + lifecycle.runtimeStatus;
}

} // namespace

int main(int argc, char** argv) {
    qputenv("QT_QPA_PLATFORM", qgetenv("QT_QPA_PLATFORM").isEmpty() ? QByteArray("offscreen") : qgetenv("QT_QPA_PLATFORM"));
    qputenv("QSG_RHI_BACKEND", qgetenv("QSG_RHI_BACKEND").isEmpty() ? QByteArray("opengl") : qgetenv("QSG_RHI_BACKEND"));
    QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGLRhi);
    qInstallMessageHandler(recordingMessageHandler);
    QGuiApplication app(argc, argv);

    using nova::deck::stream::DeckLinuxMediaProbe;
    using nova::deck::stream::DeckQtQuickRhiVaapiItem;
    using nova::deck::stream::DeckVaapiFfmpegRenderer;

    const DeckLinuxMediaProbe mediaProbe = DeckLinuxMediaProbe::detect();
    if (!mediaProbe.runtimeVaapiDeviceAvailable) {
        std::cout << "Nova Deck QSGRenderNode scenegraph smoke skipped: " << mediaProbe.runtimeStatus << '\n';
        return 0;
    }

    QQuickWindow window;
    window.setTitle(QStringLiteral("Nova Deck QSGRenderNode scenegraph smoke"));
    window.resize(128, 72);

    auto vaapiItem = std::shared_ptr<DeckQtQuickRhiVaapiItem>(new DeckQtQuickRhiVaapiItem(), [](DeckQtQuickRhiVaapiItem* item) {
        delete item;
    });
    vaapiItem->setWidth(128);
    vaapiItem->setHeight(72);
    vaapiItem->setParentItem(window.contentItem());

    DeckVaapiFfmpegRenderer renderer;
    renderer.presentationHandoff().setSink(vaapiItem);
    const int setupResult = renderer.setup(VIDEO_FORMAT_H264, 128, 72, 1, nullptr, 0);
    NOVA_TEST_REQUIRE(setupResult == DR_OK);

    auto idrBytes = makeLocalAnnexBH264IdrSample();
    NOVA_TEST_REQUIRE(!idrBytes.empty());
    LENTRY idrEntry{};
    DECODE_UNIT idrUnit = makeDecodeUnit(idrBytes, idrEntry, 1);
    NOVA_TEST_REQUIRE(renderer.submitDecodeUnit(&idrUnit) == DR_OK);
    NOVA_TEST_REQUIRE(renderer.lifecycle().decodedHardwareFrames == 1);
    NOVA_TEST_REQUIRE(renderer.lifecycle().presentedHardwareFrames == 1);

    window.show();
    window.requestUpdate();
    vaapiItem->update();
    if (!waitForRenderPasses(app, window, *vaapiItem, 1)) {
        std::cerr << "QSGRenderNode render path did not enter product render() from the first Qt scenegraph pass; "
                  << environmentDetail(window, renderer.lifecycle()) << "\nRecorded Qt messages:\n"
                  << joinedRecordedMessages();
        return 1;
    }

    LENTRY secondIdrEntry{};
    DECODE_UNIT secondIdrUnit = makeDecodeUnit(idrBytes, secondIdrEntry, 2);
    NOVA_TEST_REQUIRE(renderer.submitDecodeUnit(&secondIdrUnit) == DR_OK);
    NOVA_TEST_REQUIRE(renderer.lifecycle().decodedHardwareFrames == 2);
    NOVA_TEST_REQUIRE(renderer.lifecycle().presentedHardwareFrames == 2);
    window.requestUpdate();
    vaapiItem->update();
    if (!waitForRenderPasses(app, window, *vaapiItem, 2)) {
        std::cerr << "QSGRenderNode render path did not enter product render() for two consecutive local VAAPI frames; "
                  << environmentDetail(window, renderer.lifecycle()) << " renderPasses="
                  << recordedMessageCount("Nova Deck QSGRenderNode VAAPI/EGL render path")
                  << "\nRecorded Qt messages:\n"
                  << joinedRecordedMessages();
        return 1;
    }
    NOVA_TEST_REQUIRE(recordedMessageContains("readiness stayed false until shader composition proof"));
    NOVA_TEST_REQUIRE(recordedMessageContains("layers=2"));
    const bool provedReady = recordedMessageContains("status=ready") && recordedMessageContains("ready=1");
    const bool blockedByHeadlessBackend = recordedMessageContains("status=unsupported-non-opengl-scene-graph") &&
        recordedMessageContains("graphicsApi=") &&
        recordedMessageContains("render-thread EGLImage import is not attempted");
    const bool blockedByMissingContext = recordedMessageContains("status=missing-render-context") &&
        recordedMessageContains("No current EGL display/context");
    if (!require(provedReady || blockedByHeadlessBackend || blockedByMissingContext,
            "expected scenegraph render pass either to prove ready or report an exact headless backend/context capability")) {
        std::cerr << joinedRecordedMessages();
        return 1;
    }

    renderer.cleanup();
    vaapiItem->setParentItem(nullptr);
    vaapiItem.reset();
    std::cout << "Nova Deck QSGRenderNode scenegraph smoke passed: product render-node path entered "
              << recordedMessageCount("Nova Deck QSGRenderNode VAAPI/EGL render path")
              << " consecutive render passes; "
              << (provedReady ? "imported two DRM_PRIME layers, proved shader composition, then reported ready"
                              : "blocked with exact headless Qt scenegraph EGL/OpenGL capability detail")
              << '\n';
    return 0;
}
