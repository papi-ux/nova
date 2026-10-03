#include "stream/deck_v4l2_decoder.h"
#include "stream/deck_video_color.h"
#include <Limelight.h>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/opt.h>
}
#include <QCoreApplication>
#include <QDir>
#include <QDateTime>
#include <QElapsedTimer>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <mutex>
#include <memory>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace nova::deck::stream {
namespace {
bool ownedPlane(const AVFrame& frame, int plane, std::size_t rows, std::size_t rowBytes,
    std::uintptr_t& start, std::uintptr_t& end) {
    if (!frame.data[plane] || frame.linesize[plane] <= 0 ||
        std::size_t(frame.linesize[plane]) < rowBytes) return false;
    const auto bytes = (rows - 1) * std::size_t(frame.linesize[plane]) + rowBytes;
    start = reinterpret_cast<std::uintptr_t>(frame.data[plane]);
    if (bytes > UINTPTR_MAX - start) return false;
    end = start + bytes;
    const auto contains = [&](const AVBufferRef* buffer) {
        if (!buffer || !buffer->data) return false;
        const auto base = reinterpret_cast<std::uintptr_t>(buffer->data);
        return start >= base && start - base <= buffer->size && bytes <= buffer->size - (start - base);
    };
    for (const auto* buffer : frame.buf) if (contains(buffer)) return true;
    for (int i = 0; i < frame.nb_extended_buf; ++i) if (contains(frame.extended_buf[i])) return true;
    return false;
}
bool irisDevice(const std::string& path) {
    const int fd = open(path.c_str(), O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return false;
    v4l2_capability caps{};
    const bool queried = ioctl(fd, VIDIOC_QUERYCAP, &caps) == 0;
    const auto capabilities = caps.capabilities & V4L2_CAP_DEVICE_CAPS ? caps.device_caps : caps.capabilities;
    const bool iris = queried && deckV4l2DecoderDeviceIdentitySupported(
        std::string_view(reinterpret_cast<const char*>(caps.driver),
            strnlen(reinterpret_cast<const char*>(caps.driver), sizeof(caps.driver))),
        std::string_view(reinterpret_cast<const char*>(caps.card),
            strnlen(reinterpret_cast<const char*>(caps.card), sizeof(caps.card))), capabilities);
    const auto hasFormat = [&](v4l2_buf_type type, uint32_t first, uint32_t second) {
        for (uint32_t index = 0; index < 256; ++index) {
            v4l2_fmtdesc format{};
            format.index = index; format.type = type;
            if (ioctl(fd, VIDIOC_ENUM_FMT, &format) != 0) break;
            if (format.pixelformat == first || format.pixelformat == second) return true;
        }
        return false;
    };
    // The same driver can expose an encoder node. Only consider nodes with
    // compressed input and explicitly linear NV12 capture; the child then
    // proves each codec with an actual decoded fixture.
    const bool decoder = iris &&
        hasFormat(V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE, V4L2_PIX_FMT_H264, V4L2_PIX_FMT_HEVC) &&
        hasFormat(V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE, V4L2_PIX_FMT_NV12, V4L2_PIX_FMT_NV12M);
    close(fd);
    return decoder;
}
}

bool deckV4l2DecoderDeviceIdentitySupported(std::string_view driver, std::string_view card,
    std::uint32_t capabilities) {
    const bool decoder = driver == "qcom-iris" || (driver == "iris_driver" && card == "iris_decoder");
    return decoder && (capabilities & V4L2_CAP_STREAMING) &&
        (capabilities & V4L2_CAP_VIDEO_M2M_MPLANE);
}

bool deckV4l2Nv12LayoutSupported(const AVFrame& frame) {
    if (frame.format != AV_PIX_FMT_NV12 || frame.width <= 0 || frame.height <= 0 ||
        frame.width > 8192 || frame.height > 8192 || frame.hw_frames_ctx || frame.nb_extended_buf < 0 || frame.nb_extended_buf > 64 ||
        (frame.nb_extended_buf && !frame.extended_buf) || frame.data[2] || frame.data[3]) return false;
    const auto color = DeckVideoColorInfo::fromFrame(frame);
    if (!color.yuv420 || color.bitDepth != 8 || !color.legacySdrCompatible()) return false;
    std::uintptr_t yStart, yEnd, uvStart, uvEnd;
    return ownedPlane(frame, 0, frame.height, frame.width, yStart, yEnd) &&
        ownedPlane(frame, 1, (frame.height + 1) / 2, ((frame.width + 1) / 2) * 2, uvStart, uvEnd) &&
        (yEnd <= uvStart || uvEnd <= yStart);
}

const char* deckV4l2DecoderName(int format) {
    if (format == VIDEO_FORMAT_H264) return "h264_v4l2m2m";
    if (format == VIDEO_FORMAT_H265) return "hevc_v4l2m2m";
    return nullptr;
}

std::string deckV4l2Device() {
    QDir dev("/dev");
    for (const auto& name : dev.entryList({"video*"}, QDir::System | QDir::Files, QDir::Name)) {
        const auto path = dev.filePath(name).toStdString();
        if (irisDevice(path)) return path;
    }
    return {};
}

AVCodecContext* openDeckV4l2Decoder(int format, int width, int height,
    const std::string& device, std::string& error) {
    error.clear();
    const auto refuse = [&](const char* reason) -> AVCodecContext* { error = reason; return nullptr; };
    const auto* name = deckV4l2DecoderName(format);
    if (!name || width <= 0 || height <= 0 || width > 1920 || height > 1080)
        return refuse("V4L2 supports only qualified SDR H.264/HEVC sizes through 1920x1080");
    if (device.empty() || !irisDevice(device)) return refuse("The qualified qcom-iris device is unavailable");
    const auto* codec = avcodec_find_decoder_by_name(name);
    if (!codec || !(codec->capabilities & AV_CODEC_CAP_HARDWARE))
        return refuse("The bundled FFmpeg V4L2 decoder is unavailable");
    auto* context = avcodec_alloc_context3(codec);
    if (!context) return refuse("Could not allocate the V4L2 decoder");
    context->width = context->coded_width = width;
    context->height = context->coded_height = height;
    context->pix_fmt = AV_PIX_FMT_NV12;
    context->thread_count = 1;
    // Setting these before opening is mandatory: an unpatched system FFmpeg
    // must never silently scan a different device or block the session thread.
    if (!context->priv_data || av_opt_set(context->priv_data, "nova_device", device.c_str(), 0) < 0 ||
        av_opt_set_int(context->priv_data, "nova_nonblocking", 1, 0) < 0 ||
        av_opt_set_int(context->priv_data, "num_capture_buffers", 12, 0) < 0 ||
        av_opt_set_int(context->priv_data, "num_output_buffers", 4, 0) < 0 ||
        avcodec_open2(context, codec, nullptr) < 0) {
        avcodec_free_context(&context);
        return refuse("Could not open the bounded V4L2 provider on the qualified device");
    }
    return context;
}

DeckV4l2Qualification probeDeckV4l2InChild(const QString& program, const std::string& device, int timeoutMs) {
    if (device.empty() || timeoutMs <= 0) return {};
    const auto dispose = [](QProcess* process) {
        if (process->state() != QProcess::NotRunning) {
            process->kill();
            if (!process->waitForFinished(1000) && process->state() != QProcess::NotRunning) {
                // A driver stuck in an uninterruptible ioctl cannot be reaped
                // synchronously. Retain the stranded process until app exit.
                return;
            }
        }
        delete process;
    };
    std::unique_ptr<QProcess, decltype(dispose)> owned(new QProcess, dispose);
    auto& child = *owned;
    child.setStandardInputFile(QProcess::nullDevice());
    child.setStandardErrorFile(QProcess::nullDevice());
    child.start(program, {QString::fromStdString(device)}, QIODevice::ReadOnly);
    QElapsedTimer clock; clock.start();
    QByteArray bytes;
    if (!child.waitForStarted(timeoutMs)) return {};
    while (child.state() != QProcess::NotRunning && clock.elapsed() < timeoutMs && bytes.size() <= 4096) {
        child.waitForReadyRead(std::min(50, std::max(1, timeoutMs - int(clock.elapsed()))));
        bytes += child.readAllStandardOutput();
    }
    if (child.state() != QProcess::NotRunning) {
        child.kill();
        child.waitForFinished(1000);
        return {};
    }
    bytes += child.readAllStandardOutput();
    if (bytes.size() > 4096 || child.exitStatus() != QProcess::NormalExit || child.exitCode() != 0) return {};
    const auto doc = QJsonDocument::fromJson(bytes);
    const auto object = doc.object();
    if (!doc.isObject() || object.size() != 4 || object.value("version").toDouble(-1) != 1 ||
        !object.value("h264").isBool() || !object.value("hevc").isBool() ||
        object.value("device").toString().toStdString() != device) return {};
    DeckV4l2Qualification result;
    result.device = device;
    if (object.value("h264").toBool()) result.h264 = {1920, 1080, DeckDecoderBackend::V4l2};
    if (object.value("hevc").toBool()) result.hevc = {1920, 1080, DeckDecoderBackend::V4l2};
    return result;
}

DeckV4l2Qualification qualifyDeckV4l2Decoder() {
    if (qgetenv("NOVA_DECK_FRAME_V4L2") != "1") return {};
    const auto device = deckV4l2Device();
    if (device.empty()) return {};
    const auto program = QCoreApplication::applicationDirPath() + "/nova-deck-v4l2-probe";
    struct stat node{};
    if (stat(device.c_str(), &node) != 0) return {};
    const QFileInfo helper(program);
    const auto identity = device + ":" + std::to_string(node.st_rdev) + ":" + std::to_string(node.st_ino) + ":" +
        helper.canonicalFilePath().toStdString() + ":" + std::to_string(helper.size()) + ":" +
        std::to_string(helper.lastModified().toMSecsSinceEpoch()) + ":" + std::to_string(avcodec_version());
    static std::mutex mutex;
    static std::string key;
    static DeckV4l2Qualification result;
    const std::lock_guard lock(mutex);
    if (key != identity) { result = probeDeckV4l2InChild(program, device, 10000); key = identity; }
    return result;
}
}
