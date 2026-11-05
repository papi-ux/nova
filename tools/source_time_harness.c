// Nova #241: exercise the REAL core RTP/FEC queue and depacketizer, no decoder/device.
// The old ABI still builds and runs: it fails the timestamp assertions at runtime.
#include "Limelight-internal.h"
#include "rs.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <inttypes.h>

static int failures, cases;
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); ++failures; } } while (0)
#define SIZE 144
#define DATA 2
#define PARITY 1

static void frame(RTP_VIDEO_QUEUE* queue, uint32_t number, uint32_t timestamp, int missing) {
    unsigned char shards[DATA + PARITY][SIZE];
    unsigned char* pointers[DATA + PARITY];
    const uint16_t base = (uint16_t)(number * 3);
    memset(shards, 0, sizeof(shards));
    for (int i = 0; i < DATA; ++i) {
        PRTP_PACKET rtp = (PRTP_PACKET)shards[i];
        PNV_VIDEO_PACKET nv = (PNV_VIDEO_PACKET)(shards[i] + 16);
        rtp->header = 0x80 | FLAG_EXTENSION;
        rtp->sequenceNumber = base + i;
        // This is the production queue boundary, AFTER VideoStream's BE32 conversion.
        rtp->timestamp = BE32(BE32(timestamp));
        nv->streamPacketIndex = LE32(((number - 1) * DATA + i) << 8);
        nv->frameIndex = LE32(number);
        nv->flags = FLAG_CONTAINS_PIC_DATA | (i == 0 ? FLAG_SOF : FLAG_EOF);
        nv->multiFecFlags = 0x10;
        nv->fecInfo = LE32(((uint32_t)i << 12) | (DATA << 22) | (50 << 4));
        memset(shards[i] + 32, 0x12, SIZE - 32);
        if (i == 0) {
            shards[i][32] = 1; shards[i][35] = 2; // 8-byte frame header, IDR
            shards[i][36] = SIZE - 32; shards[i][37] = 0;
        }
        pointers[i] = shards[i];
    }
    pointers[DATA] = shards[DATA];
    reed_solomon* rs = reed_solomon_new(DATA, PARITY);
    reed_solomon_encode(rs, pointers, DATA + PARITY, SIZE);
    reed_solomon_release(rs);
    // Host stamps parity identity after encoding. Recovered RTP identity comes from queue head.
    PRTP_PACKET parity = (PRTP_PACKET)shards[DATA];
    PNV_VIDEO_PACKET nv = (PNV_VIDEO_PACKET)(shards[DATA] + 16);
    memset(parity, 0, 16); parity->header = 0x80 | FLAG_EXTENSION;
    parity->sequenceNumber = base + DATA; parity->timestamp = timestamp;
    nv->frameIndex = LE32(number); nv->fecInfo = LE32((DATA << 12) | (DATA << 22) | (50 << 4));
    nv->multiFecBlocks = 0;
    const uint64_t before = LiGetMillis();
    for (int i = 0; i < DATA + PARITY; ++i) {
        if (i == missing || (missing < 0 && i == DATA)) continue;
        unsigned char* allocation = calloc(1, SIZE + sizeof(RTPV_QUEUE_ENTRY));
        memcpy(allocation, shards[i], SIZE);
        PRTPV_QUEUE_ENTRY entry = (PRTPV_QUEUE_ENTRY)(allocation + SIZE);
        if (RtpvAddPacket(queue, (PRTP_PACKET)allocation, SIZE, entry) == RTPF_RET_REJECTED) free(allocation);
    }
    VIDEO_FRAME_HANDLE handle = NULL; PDECODE_UNIT du = NULL;
    CHECK(LiPollNextVideoFrame(&handle, &du));
    if (du) {
        CHECK((uint32_t)du->frameNumber == number);
        CHECK(du->receiveTimeMs >= before && du->enqueueTimeMs >= du->receiveTimeMs);
        if (timestamp / 90) CHECK(du->presentationTimeMs == timestamp / 90);
#ifdef LI_RTP_TIMESTAMP_VERSION
        CHECK(LI_RTP_TIMESTAMP_VERSION == 1);
        CHECK(du->rtpTimestamp == timestamp);
        printf("frame=%u repaired=%d raw=%" PRIu32 " us=%" PRIu64 " legacyMs=%u\n",
               number, missing, du->rtpTimestamp, (uint64_t)du->rtpTimestamp * 1000 / 90, du->presentationTimeMs);
#else
        fprintf(stderr, "GAP frame=%u repaired=%d legacyMs=%u: raw rtpTimestamp absent\n",
                number, missing, du->presentationTimeMs);
        CHECK(0 && "core must retain raw RTP for client sub-millisecond conversion");
#endif
        LiCompleteVideoFrame(handle, DR_OK);
    }
    ++cases;
}

int main(void) {
    CHECK(initializePlatform() == 0);
    AppVersionQuad[0] = 7; AppVersionQuad[1] = 1; AppVersionQuad[2] = 431; AppVersionQuad[3] = 0;
    memset(&StreamConfig, 0, sizeof(StreamConfig)); StreamConfig.packetSize = SIZE - 16;
    StreamConfig.colorSpace = COLORSPACE_REC_709; NegotiatedVideoFormat = VIDEO_FORMAT_AV1_MAIN8;
    LiInitializeVideoCallbacks(&VideoCallbacks); LiInitializeConnectionCallbacks(&ListenerCallbacks);
    CHECK(initializeControlStream() == 0); initializeVideoDepacketizer(StreamConfig.packetSize);
    RTP_VIDEO_QUEUE queue; RtpvInitializeQueue(&queue);
    frame(&queue, 1, 90017, -1); // Sub-ms precision cannot be recovered from the old milliseconds.
    frame(&queue, 2, 90018, 0);  // First data packet reconstructed by production Reed-Solomon.
    frame(&queue, 3, UINT32_MAX - 15, 1); // Last data packet reconstructed.
    frame(&queue, 4, 0, -1);    // Legitimate raw zero must NOT become a synthetic source time.
    frame(&queue, 5, 32, 0);    // Wrap exit still carries exact raw ticks.
    RtpvCleanupQueue(&queue); stopVideoDepacketizer(); destroyVideoDepacketizer();
    destroyControlStream(); cleanupPlatform();
    printf("cases=%d failures=%d\n", cases, failures);
    return failures ? 1 : 0;
}
