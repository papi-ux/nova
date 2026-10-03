#include "stream/deck_v4l2_decoder.h"
#include <cstdio>
#include <Limelight.h>
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QThread>
extern "C" {
#include <libavcodec/avcodec.h>
}
#include <algorithm>
#include <memory>

using namespace nova::deck::stream;
namespace {
struct FreeContext { void operator()(AVCodecContext* context) const { avcodec_free_context(&context); } };
struct FreeFrame { void operator()(AVFrame* frame) const { av_frame_free(&frame); } };
struct FreePacket { void operator()(AVPacket* packet) const { av_packet_free(&packet); } };
bool decode(int format, const std::string& device, const char* resource) {
    std::string error;
    std::unique_ptr<AVCodecContext, FreeContext> context(openDeckV4l2Decoder(format, 1920, 1080, device, error));
    if (!context) {
        std::fprintf(stderr, "%s: %s\n", deckV4l2DecoderName(format), error.c_str());
        return false;
    }
    QFile fixture(QString::fromUtf8(resource));
    if (!fixture.open(QIODevice::ReadOnly)) return false;
    auto bytes = fixture.readAll();
    const int size = bytes.size();
    if (size <= 0 || size > 1024 * 1024) return false;
    bytes.append(QByteArray(AV_INPUT_BUFFER_PADDING_SIZE, '\0'));
    auto* parser = av_parser_init(context->codec_id);
    if (!parser) return false;
    std::unique_ptr<AVCodecParserContext, decltype(&av_parser_close)> ownedParser(parser, av_parser_close);
    std::unique_ptr<AVFrame, FreeFrame> frame(av_frame_alloc());
    std::unique_ptr<AVPacket, FreePacket> packet(av_packet_alloc());
    if (!frame || !packet) return false;
    QElapsedTimer deadline; deadline.start();
    int offset = 0;
    bool flushing = false;
    while (deadline.elapsed() < 4000) {
        if (!packet->size && !flushing) {
            uint8_t* accessUnit = nullptr;
            int accessUnitBytes = 0;
            const int consumed = av_parser_parse2(parser, context.get(), &accessUnit, &accessUnitBytes,
                reinterpret_cast<const uint8_t*>(bytes.constData()) + offset, size - offset,
                AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
            if (consumed < 0 || (consumed == 0 && accessUnitBytes == 0 && offset < size)) return false;
            offset += consumed;
            if (accessUnitBytes > 0) {
                if (av_new_packet(packet.get(), accessUnitBytes) < 0) return false;
                std::copy_n(accessUnit, accessUnitBytes, packet->data);
            } else if (offset == size) flushing = true;
            else continue;
        }
        if (packet->size || flushing) {
            const int sent = avcodec_send_packet(context.get(), packet->size ? packet.get() : nullptr);
            if (sent < 0 && sent != AVERROR(EAGAIN) && sent != AVERROR_EOF) return false;
            if (sent == 0) av_packet_unref(packet.get());
        }
        const int received = avcodec_receive_frame(context.get(), frame.get());
        if (received == 0) {
            if (!deckV4l2Nv12LayoutSupported(*frame) || frame->width != 1920 || frame->height != 1080) {
                std::fprintf(stderr, "%s: capture layout/color refused (format=%d size=%dx%d pitch=%d,%d)\n",
                    deckV4l2DecoderName(format), frame->format, frame->width, frame->height,
                    frame->linesize[0], frame->linesize[1]);
                return false;
            }
            // A successful open or an empty dequeue is insufficient. Read the
            // real gray fixture at its validated plane pitches after DQBUF.
            const auto y = frame->data[0][540 * frame->linesize[0] + 960];
            const auto u = frame->data[1][270 * frame->linesize[1] + 960];
            const auto v = frame->data[1][270 * frame->linesize[1] + 961];
            const bool gray = y >= 100 && y <= 160 && u >= 120 && u <= 136 && v >= 120 && v <= 136;
            if (!gray) std::fprintf(stderr, "%s: decoded gray pixels refused (%u,%u,%u)\n",
                deckV4l2DecoderName(format), unsigned(y), unsigned(u), unsigned(v));
            return gray;
        }
        if (received != AVERROR(EAGAIN)) {
            std::fprintf(stderr, "%s: capture receive failed (%d)\n", deckV4l2DecoderName(format), received);
            return false;
        }
        QThread::msleep(2);
    }
    std::fprintf(stderr, "%s: no decoded frame before qualification deadline\n", deckV4l2DecoderName(format));
    return false;
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    if (argc != 2) return 1;
    const std::string device(argv[1]);
    // Both opens independently recheck qcom-iris and the private provider
    // options. No VAAPI/software fallback is allowed in this helper.
    const bool h264 = decode(VIDEO_FORMAT_H264, device, ":/nova-v4l2/frame-1080p.h264");
    const bool hevc = decode(VIDEO_FORMAT_H265, device, ":/nova-v4l2/frame-1080p.hevc");
    const QJsonObject result{{"version", 1}, {"h264", h264}, {"hevc", hevc},
        {"device", QString::fromStdString(device)}};
    const auto json = QJsonDocument(result).toJson(QJsonDocument::Compact);
    QFile output;
    if (!output.open(stdout, QIODevice::WriteOnly)) return 1;
    return output.write(json) == json.size() ? 0 : 1;
}
