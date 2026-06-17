#include "stream/deck_stream_media_adapters.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/hwcontext.h>
#include <libavutil/log.h>
#include <libavutil/pixfmt.h>
#include <libavutil/version.h>
#include <pipewire/pipewire.h>
#include <pulse/version.h>
#include <va/va.h>
}

#include <QtQuick/QQuickItem>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace nova::deck::stream {

namespace {

std::string ffmpegErrorString(const int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(errorCode, buffer, sizeof(buffer));
    return buffer;
}

AVPixelFormat vaapiHardwareFormatCallback(AVCodecContext*, const AVPixelFormat* pixelFormats) {
    for (const AVPixelFormat* format = pixelFormats; format != nullptr && *format != AV_PIX_FMT_NONE; ++format) {
        if (*format == AV_PIX_FMT_VAAPI) {
            return *format;
        }
    }
    return AV_PIX_FMT_NONE;
}

std::vector<std::uint8_t> copyDecodeUnitBytes(PDECODE_UNIT decodeUnit) {
    std::vector<std::uint8_t> bytes;
    if (decodeUnit == nullptr || decodeUnit->fullLength <= 0 || decodeUnit->bufferList == nullptr) {
        return bytes;
    }
    bytes.reserve(static_cast<std::size_t>(decodeUnit->fullLength));
    for (PLENTRY entry = decodeUnit->bufferList; entry != nullptr; entry = entry->next) {
        if (entry->data == nullptr || entry->length <= 0) {
            return {};
        }
        const auto* first = reinterpret_cast<const std::uint8_t*>(entry->data);
        bytes.insert(bytes.end(), first, first + entry->length);
    }
    if (bytes.size() != static_cast<std::size_t>(decodeUnit->fullLength)) {
        return {};
    }
    return bytes;
}

} // namespace

DeckLinuxMediaProbe DeckLinuxMediaProbe::detect() {
    const AVHWDeviceType vaapiType = av_hwdevice_find_type_by_name("vaapi");
    AVBufferRef* hardwareDevice = nullptr;
    const int priorLogLevel = av_log_get_level();
    av_log_set_level(AV_LOG_QUIET);
    const int hardwareDeviceResult = av_hwdevice_ctx_create(&hardwareDevice, AV_HWDEVICE_TYPE_VAAPI, nullptr, nullptr, 0);
    av_log_set_level(priorLogLevel);
    if (hardwareDevice != nullptr) {
        av_buffer_unref(&hardwareDevice);
    }
    return DeckLinuxMediaProbe{
        .ffmpegLibavcodecHeadersLinked = avcodec_version() > 0,
        .ffmpegLibavutilHeadersLinked = avutil_version() > 0,
        .vaapiHeadersLinked = VA_MAJOR_VERSION >= 1,
        .qtQuickRhiPresentationBoundary = QQuickItem::staticMetaObject.className() != nullptr,
        .h264DecoderAvailable = avcodec_find_decoder(AV_CODEC_ID_H264) != nullptr,
        .runtimeVaapiDeviceAvailable = hardwareDeviceResult == 0,
        .hardwareDeviceTypeName = vaapiType == AV_HWDEVICE_TYPE_VAAPI ? "vaapi" : "missing-vaapi",
        .runtimeStatus = hardwareDeviceResult == 0 ? "vaapi runtime device opened" : "av_hwdevice_ctx_create(VAAPI) failed: " + ffmpegErrorString(hardwareDeviceResult),
    };
}

std::string_view DeckVaapiFfmpegRenderer::adapterName() const {
    return "ffmpeg-vaapi-h264-qt-rhi-prototype";
}

DeckVaapiFfmpegRenderer::~DeckVaapiFfmpegRenderer() {
    resetDecoder();
}

int DeckVaapiFfmpegRenderer::setup(
    const int videoFormat,
    const int width,
    const int height,
    const int redrawRate,
    void* context,
    const int drFlags) {
    (void)context;
    (void)drFlags;
    ++lifecycle_.setupCalls;
    lifecycle_.videoFormat = videoFormat;
    lifecycle_.width = width;
    lifecycle_.height = height;
    lifecycle_.redrawRate = redrawRate;
    lifecycle_.networkStartAllowed = false;
    lifecycle_.decodedHardwareFrames = 0;
    lifecycle_.lastFrameWasHardwareBacked = false;
    lifecycle_.lastRuntimeError.clear();
    resetDecoder();

    const DeckLinuxMediaProbe probe = DeckLinuxMediaProbe::detect();
    lifecycle_.runtimeVaapiDeviceAvailable = probe.runtimeVaapiDeviceAvailable;
    lifecycle_.runtimeStatus = probe.runtimeStatus;
    if (videoFormat != VIDEO_FORMAT_H264) {
        lifecycle_.lastRuntimeError = "unsupported video format for Deck FFmpeg VA-API renderer";
        return DR_NEED_IDR;
    }

    int result = av_hwdevice_ctx_create(&hardwareDevice_, AV_HWDEVICE_TYPE_VAAPI, nullptr, nullptr, 0);
    if (result < 0) {
        lifecycle_.lastRuntimeError = "av_hwdevice_ctx_create(VAAPI) failed: " + ffmpegErrorString(result);
        lifecycle_.runtimeStatus = lifecycle_.lastRuntimeError;
        resetDecoder();
        return DR_NEED_IDR;
    }
    lifecycle_.ownsHardwareDevice = true;
    lifecycle_.runtimeVaapiDeviceAvailable = true;
    lifecycle_.runtimeStatus = "vaapi runtime device opened and owned";

    const AVCodec* codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (codec == nullptr) {
        lifecycle_.lastRuntimeError = "FFmpeg H.264 decoder unavailable";
        resetDecoder();
        return DR_NEED_IDR;
    }

    codecContext_ = avcodec_alloc_context3(codec);
    if (codecContext_ == nullptr) {
        lifecycle_.lastRuntimeError = "avcodec_alloc_context3(H.264) failed";
        resetDecoder();
        return DR_NEED_IDR;
    }
    codecContext_->width = width;
    codecContext_->height = height;
    codecContext_->get_format = vaapiHardwareFormatCallback;
    codecContext_->hw_device_ctx = av_buffer_ref(hardwareDevice_);
    if (codecContext_->hw_device_ctx == nullptr) {
        lifecycle_.lastRuntimeError = "av_buffer_ref(VAAPI device) failed";
        resetDecoder();
        return DR_NEED_IDR;
    }

    result = avcodec_open2(codecContext_, codec, nullptr);
    if (result < 0) {
        lifecycle_.lastRuntimeError = "avcodec_open2(H.264 VA-API) failed: " + ffmpegErrorString(result);
        resetDecoder();
        return DR_NEED_IDR;
    }

    decodedFrame_ = av_frame_alloc();
    if (decodedFrame_ == nullptr) {
        lifecycle_.lastRuntimeError = "av_frame_alloc() failed";
        resetDecoder();
        return DR_NEED_IDR;
    }

    lifecycle_.ownsCodecContext = true;
    ready_ = true;
    return DR_OK;
}

void DeckVaapiFfmpegRenderer::start() {
    ++lifecycle_.startCalls;
}

void DeckVaapiFfmpegRenderer::stop() {
    ++lifecycle_.stopCalls;
}

void DeckVaapiFfmpegRenderer::cleanup() {
    ++lifecycle_.cleanupCalls;
    resetDecoder();
}

int DeckVaapiFfmpegRenderer::submitDecodeUnit(PDECODE_UNIT decodeUnit) {
    ++lifecycle_.submitCalls;
    lifecycle_.lastFrameWasHardwareBacked = false;
    if (!ready_ || decodeUnit == nullptr) {
        lifecycle_.acceptedNullDecodeUnit = false;
        return DR_NEED_IDR;
    }

    const std::vector<std::uint8_t> bytes = copyDecodeUnitBytes(decodeUnit);
    if (bytes.empty()) {
        lifecycle_.lastRuntimeError = "decode unit did not contain Annex-B H.264 bytes";
        return DR_NEED_IDR;
    }

    AVPacket* packet = av_packet_alloc();
    if (packet == nullptr) {
        lifecycle_.lastRuntimeError = "av_packet_alloc() failed";
        return DR_NEED_IDR;
    }
    int result = av_new_packet(packet, static_cast<int>(bytes.size()));
    if (result < 0) {
        lifecycle_.lastRuntimeError = "av_new_packet() failed: " + ffmpegErrorString(result);
        av_packet_free(&packet);
        return DR_NEED_IDR;
    }
    std::copy(bytes.begin(), bytes.end(), packet->data);

    result = avcodec_send_packet(codecContext_, packet);
    av_packet_free(&packet);
    if (result < 0) {
        lifecycle_.lastRuntimeError = "avcodec_send_packet() failed: " + ffmpegErrorString(result);
        return DR_NEED_IDR;
    }

    while ((result = avcodec_receive_frame(codecContext_, decodedFrame_)) == 0) {
        const bool hardwareBacked = decodedFrame_->format == AV_PIX_FMT_VAAPI;
        lifecycle_.lastFrameWasHardwareBacked = hardwareBacked;
        av_frame_unref(decodedFrame_);
        if (hardwareBacked) {
            ++lifecycle_.decodedHardwareFrames;
            return DR_OK;
        }
    }

    if (result == AVERROR(EAGAIN)) {
        lifecycle_.lastRuntimeError = "H.264 packet accepted but no VA-API hardware frame was ready";
    } else if (result == AVERROR_EOF) {
        lifecycle_.lastRuntimeError = "H.264 decoder reached EOF before a VA-API hardware frame";
    } else {
        lifecycle_.lastRuntimeError = "avcodec_receive_frame() failed: " + ffmpegErrorString(result);
    }
    return DR_NEED_IDR;
}

const DeckRendererLifecycle& DeckVaapiFfmpegRenderer::lifecycle() const {
    return lifecycle_;
}

void DeckVaapiFfmpegRenderer::resetDecoder() {
    ready_ = false;
    if (decodedFrame_ != nullptr) {
        av_frame_free(&decodedFrame_);
    }
    if (codecContext_ != nullptr) {
        avcodec_free_context(&codecContext_);
    }
    if (hardwareDevice_ != nullptr) {
        av_buffer_unref(&hardwareDevice_);
    }
    lifecycle_.ownsHardwareDevice = hardwareDevice_ != nullptr;
    lifecycle_.ownsCodecContext = codecContext_ != nullptr;
}

DeckLinuxAudioProbe DeckLinuxAudioProbe::detect() {
    const char* pipeWireVersion = pw_get_headers_version();
    const char* pulseVersion = pa_get_headers_version();
    return DeckLinuxAudioProbe{
        .pipeWireHeadersLinked = pipeWireVersion != nullptr,
        .pulseFallbackHeadersLinked = pulseVersion != nullptr,
        .pipeWireHeaderVersion = pipeWireVersion == nullptr ? "" : pipeWireVersion,
        .pulseHeaderVersion = pulseVersion == nullptr ? "" : pulseVersion,
    };
}

std::string_view DeckPipeWireAudio::adapterName() const {
    return "pipewire-pcm-pulse-fallback-prototype";
}

int DeckPipeWireAudio::init(
    const int audioConfiguration,
    POPUS_MULTISTREAM_CONFIGURATION opusConfig,
    void* context,
    const int arFlags) {
    (void)context;
    (void)arFlags;
    ++lifecycle_.initCalls;
    lifecycle_.audioConfiguration = audioConfiguration;
    lifecycle_.samplesPerFrame = opusConfig == nullptr ? 0 : opusConfig->samplesPerFrame;
    lifecycle_.networkStartAllowed = false;

    const DeckLinuxAudioProbe probe = DeckLinuxAudioProbe::detect();
    ready_ = probe.pipeWireHeadersLinked && audioConfiguration != 0 && opusConfig != nullptr && opusConfig->samplesPerFrame > 0;
    return ready_ ? 0 : -1;
}

void DeckPipeWireAudio::start() {
    ++lifecycle_.startCalls;
}

void DeckPipeWireAudio::stop() {
    ++lifecycle_.stopCalls;
}

void DeckPipeWireAudio::cleanup() {
    ++lifecycle_.cleanupCalls;
    ready_ = false;
}

void DeckPipeWireAudio::decodeAndPlaySample(char* sampleData, const int sampleLength) {
    if (!ready_ || sampleData == nullptr || sampleLength <= 0) {
        return;
    }
    ++lifecycle_.sampleCalls;
    lifecycle_.lastSampleLength = sampleLength;
}

const DeckAudioLifecycle& DeckPipeWireAudio::lifecycle() const {
    return lifecycle_;
}

} // namespace nova::deck::stream
