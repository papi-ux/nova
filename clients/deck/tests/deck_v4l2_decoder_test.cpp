#include "stream/deck_v4l2_decoder.h"
#include "stream/deck_stream_media_adapters.h"
#include <Limelight.h>
extern "C" {
#include <libavcodec/avcodec.h>
}
#include "runtime/deck_frame_delivery.h"
#include <QCoreApplication>
#include <QFile>
#include <QTemporaryDir>
#include <cassert>
#include <string_view>
#include <algorithm>
#include <linux/videodev2.h>

using namespace nova::deck::stream;
namespace {
void softwareFixture(const char* path, AVCodecID codec, int profile) {
    QFile file(QString::fromUtf8(path));
    assert(file.open(QIODevice::ReadOnly));
    auto bytes = file.readAll();
    const int encoded = bytes.size();
    bytes.append(QByteArray(AV_INPUT_BUFFER_PADDING_SIZE, '\0'));
    auto* context = avcodec_alloc_context3(avcodec_find_decoder(codec));
    assert(context && avcodec_open2(context, context->codec, nullptr) == 0);
    auto* parser = av_parser_init(codec);
    assert(parser);
    uint8_t* accessUnit = nullptr; int size = 0;
    assert(av_parser_parse2(parser, context, &accessUnit, &size,
        reinterpret_cast<const uint8_t*>(bytes.constData()), encoded,
        AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0) > 0 && size > 0);
    auto* packet = av_packet_alloc();
    assert(packet && av_new_packet(packet, size) == 0);
    std::copy_n(accessUnit, size, packet->data);
    av_parser_close(parser);
    assert(avcodec_send_packet(context, packet) == 0);
    av_packet_free(&packet);
    auto* decoded = av_frame_alloc(); assert(decoded);
    int result = avcodec_receive_frame(context, decoded);
    if (result == AVERROR(EAGAIN)) {
        assert(avcodec_send_packet(context, nullptr) == 0);
        result = avcodec_receive_frame(context, decoded);
    }
    assert(result == 0 && decoded->width == 1920 && decoded->height == 1080 && context->profile == profile);
    assert(decoded->colorspace == AVCOL_SPC_BT709 && decoded->color_primaries == AVCOL_PRI_BT709 &&
        decoded->color_trc == AVCOL_TRC_BT709 && decoded->color_range == AVCOL_RANGE_MPEG);
    av_frame_free(&decoded); avcodec_free_context(&context);
}
}
int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    qunsetenv("NOVA_DECK_FRAME_V4L2");
    const auto decoderCaps = V4L2_CAP_STREAMING | V4L2_CAP_VIDEO_M2M_MPLANE;
    assert(deckV4l2DecoderDeviceIdentitySupported("qcom-iris", "", decoderCaps));
    // Actual Frame QUERYCAP identity differs from its qcom-iris sysfs name.
    assert(deckV4l2DecoderDeviceIdentitySupported("iris_driver", "iris_decoder", decoderCaps));
    assert(!deckV4l2DecoderDeviceIdentitySupported("iris_driver", "iris_encoder", decoderCaps));
    assert(!deckV4l2DecoderDeviceIdentitySupported("iris_driver", "", decoderCaps));
    assert(!deckV4l2DecoderDeviceIdentitySupported("other_driver", "iris_decoder", decoderCaps));
    assert(!deckV4l2DecoderDeviceIdentitySupported("qcom-iris", "", V4L2_CAP_STREAMING));
    assert(!deckV4l2DecoderDeviceIdentitySupported("iris_driver", "iris_decoder", V4L2_CAP_VIDEO_M2M_MPLANE));
    softwareFixture(":/nova-v4l2/frame-1080p.h264", AV_CODEC_ID_H264, AV_PROFILE_H264_HIGH);
    softwareFixture(":/nova-v4l2/frame-1080p.hevc", AV_CODEC_ID_HEVC, AV_PROFILE_HEVC_MAIN);
    auto* frame = av_frame_alloc();
    assert(frame);
    frame->format = AV_PIX_FMT_NV12; frame->width = 1920; frame->height = 1080;
    frame->colorspace = AVCOL_SPC_BT709; frame->color_primaries = AVCOL_PRI_BT709;
    frame->color_trc = AVCOL_TRC_BT709; frame->color_range = AVCOL_RANGE_MPEG;
    assert(av_frame_get_buffer(frame, 64) == 0);
    assert(deckV4l2Nv12LayoutSupported(*frame));
    assert(std::string_view(deckV4l2DecoderName(VIDEO_FORMAT_H264)) == "h264_v4l2m2m");
    assert(std::string_view(deckV4l2DecoderName(VIDEO_FORMAT_H265)) == "hevc_v4l2m2m");
    assert(!deckV4l2DecoderName(VIDEO_FORMAT_H265_MAIN10));
    assert(!deckV4l2DecoderName(VIDEO_FORMAT_H264 | VIDEO_FORMAT_H265));
    // The VAAPI factory must never relabel a software allocation as VAAPI.
    assert(!DeckQrhiVaapiFrameLease::cloneHardwareFrame(*frame));
    auto lease = DeckQrhiVaapiFrameLease::retainV4l2Frame(*frame);
    assert(lease && lease->valid());
    assert(lease->colorInfo().matrix == AVCOL_SPC_BT709);
    assert(lease->backend() == DeckDecoderBackend::V4l2);
    assert(lease->transferPath() == DeckFrameTransferPath::CpuUpload);
    const auto stride = frame->linesize[0];
    frame->linesize[0] = 1919; assert(!deckV4l2Nv12LayoutSupported(*frame));
    frame->linesize[0] = -stride; assert(!deckV4l2Nv12LayoutSupported(*frame));
    frame->linesize[0] = stride;
    auto* uv = frame->data[1];
    frame->data[1] = frame->buf[0]->data + frame->buf[0]->size - 1;
    assert(!deckV4l2Nv12LayoutSupported(*frame));
    frame->data[1] = frame->data[0]; assert(!deckV4l2Nv12LayoutSupported(*frame));
    frame->data[1] = uv;
    const auto bytes = frame->buf[0]->size; frame->buf[0]->size = 100;
    assert(!deckV4l2Nv12LayoutSupported(*frame)); frame->buf[0]->size = bytes;
    frame->format = AV_PIX_FMT_NV21; assert(!deckV4l2Nv12LayoutSupported(*frame));
    frame->format = AV_PIX_FMT_DRM_PRIME; assert(!deckV4l2Nv12LayoutSupported(*frame));
    frame->format = AV_PIX_FMT_NV12;
    frame->color_trc = AVCOL_TRC_SMPTE2084; assert(!deckV4l2Nv12LayoutSupported(*frame));
    frame->color_trc = AVCOL_TRC_BT709;
    frame->width = 8193; assert(!deckV4l2Nv12LayoutSupported(*frame)); frame->width = 1920;
    // A lease survives decoder/source teardown and retains immutable color.
    av_frame_free(&frame);
    assert(lease->valid() && lease->frame()->width == 1920);
    assert(lease->frame()->color_trc == AVCOL_TRC_BT709);
    {
        int consumed = 0;
        nova::deck::runtime::DeckFrameDelivery delivery([&](const auto&) { ++consumed; });
        auto publish = delivery.publisher();
        auto configure = delivery.configurator();
        assert(configure(nova::deck::runtime::DeckFramePacing::Latency, 90));
        DeckQrhiVaapiPresentationDescriptor descriptor{.width=1920, .height=1080,
            .surfaceId=lease->surfaceId(), .hardwareBacked=true, .frameLease=lease,
            .decoderBackend=DeckDecoderBackend::V4l2, .transferPath=DeckFrameTransferPath::CpuUpload};
        const auto references = lease.use_count();
        for (int i = 0; i < 20; ++i) assert(publish(descriptor));
        assert(delivery.stats().queued == 1 && delivery.stats().dropped == 19);
        assert(lease.use_count() <= references + 3); // bounded queue, no 20-frame pool
        delivery.close();
        assert(delivery.stats().queued == 0 && delivery.stats().dropped == 19);
        assert(lease.use_count() == references);
        assert(!publish(descriptor));
        QCoreApplication::processEvents();
        assert(consumed == 0); // late callback cannot revive a closed session
        assert(!configure(nova::deck::runtime::DeckFramePacing::Latency, 90));
    }
    lease.reset();
    QTemporaryDir directory;
    assert(directory.isValid());
    const auto program = directory.filePath("fake-probe");
    const auto script = [&](const QByteArray& body) {
        QFile file(program);
        assert(file.open(QIODevice::WriteOnly));
        assert(file.write("#!/bin/sh\n" + body + "\n") > 0);
        file.close();
        assert(file.setPermissions(QFile::ReadOwner | QFile::WriteOwner | QFile::ExeOwner));
    };
    const std::string device = "/dev/video22";
    script("printf '%s' '{\"version\":1,\"h264\":true,\"hevc\":false,\"device\":\"/dev/video22\"}'");
    const auto qualified = probeDeckV4l2InChild(program, device, 1000);
    assert(qualified.h264.supports(1920, 1080) && !qualified.hevc.supports(1920, 1080));
    assert(qualified.h264.backend == DeckDecoderBackend::V4l2);
    assert(!qualified.h264.supports(1921,1080));
    script("printf '%s' '{\"version\":1,\"h264\":true,\"hevc\":true,\"device\":\"/dev/video23\"}'");
    assert(probeDeckV4l2InChild(program,device,1000).device.empty());
    script("printf '%s' '{\"version\":1,\"h264\":1,\"hevc\":true,\"device\":\"/dev/video22\"}'");
    assert(probeDeckV4l2InChild(program,device,1000).device.empty());
    script("exec sleep 2");
    assert(probeDeckV4l2InChild(program,device,30).device.empty());
    assert(probeDeckV4l2InChild(directory.filePath("absent"),device,100).device.empty());
#ifdef NOVA_DECK_BUILD_PYROWAVE
    nova::pyrowave::GpuImage image{128,72,{},std::make_shared<int>(1)};
    for (int p = 0; p < 3; ++p) {
        const unsigned w = p ? 64 : 128, h = p ? 36 : 72;
        image.planes[p] = {0, 0, 0, w, w * h}; // metadata fixture; no fd import
    }
    auto pyroLease = DeckQrhiVaapiFrameLease::retainPyrowaveFrame(image);
    assert(pyroLease && pyroLease->valid() && pyroLease->colorInfo().yuv420 && pyroLease->colorInfo().bitDepth == 8);
    assert(pyroLease->backend() == DeckDecoderBackend::Pyrowave &&
        pyroLease->transferPath() == DeckFrameTransferPath::DmaBuf);
    image = {}; pyroLease.reset();
#endif
    const DeckVideoDecodeSupport v4l2{.h264={1920,1080,DeckDecoderBackend::V4l2},
        .hevc={1920,1080,DeckDecoderBackend::V4l2}};
    assert(selectSdrVideoFormat("auto",true,true,v4l2,1920,1080) == VIDEO_FORMAT_H265);
    assert(selectSdrVideoFormat("hevc",true,true,v4l2,1921,1080) == 0);
    assert(!v4l2.supports(VIDEO_FORMAT_H265_MAIN10,1920,1080));
    std::string error;
    auto* decoder = openDeckV4l2Decoder(VIDEO_FORMAT_H264, 1920, 1080,
        "/dev/nova-test-missing-device", error);
    assert(!decoder && !error.empty());
    assert(qualifyDeckV4l2Decoder().device.empty()); // no development opt-in
    DeckVaapiFfmpegRenderer renderer;
    renderer.stop(); renderer.cleanup(); renderer.cleanup();
    assert(!renderer.lifecycle().ownsCodecContext);
    assert(renderer.setup(VIDEO_FORMAT_H264, 0, 1080, 90, nullptr, 0) == DR_NEED_IDR);
    renderer.cleanup();
    assert(renderer.submitDecodeUnit(nullptr) == DR_NEED_IDR);
}
