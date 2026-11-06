// Native packet reception fixtures for Nova #442, linked against the patched core.
//
// "unit" drives the production collector, serializer, offer parser and gate function with
// explicit times. The "session-*" commands run real native sessions over loopback: an ENet
// control server that decrypts every control message, and a UDP sender that emits encrypted
// RTP video, data and Reed-Solomon parity, through the production receive thread, RTP queue,
// periodic control loop and SDP generator. Nothing here needs a host, device or network.
#include "Limelight-internal.h"
#include "rs.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <stddef.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

static int caseFailures;
static int passedCases;
static int failedCases;
static const char* currentCase;

#define CHECK(condition, ...)                                                  \
    do {                                                                       \
        if (!(condition)) {                                                    \
            caseFailures++;                                                    \
            fprintf(stderr, "FAIL %s line %d: ", currentCase, __LINE__);       \
            fprintf(stderr, __VA_ARGS__);                                      \
            fprintf(stderr, "\n");                                             \
        }                                                                      \
    } while (0)

static void beginCase(const char* name) {
    currentCase = name;
    caseFailures = 0;
}

static void endCase(void) {
    if (caseFailures == 0) {
        passedCases++;
        printf("CASE %s PASS\n", currentCase);
    }
    else {
        failedCases++;
        printf("CASE %s FAIL %d\n", currentCase, caseFailures);
    }
    fflush(stdout);
}

static uint64_t realMonotonicUs(void) {
    uint64_t now = 0;
    if (!PltGetMonotonicMicros(&now)) {
        fprintf(stderr, "monotonic clock unavailable on this fixture host\n");
        exit(2);
    }
    return now;
}

static void sleepMs(int ms) {
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    while (nanosleep(&ts, &ts) != 0 && errno == EINTR) {
    }
}

// Reference decoder: the exact validation a host applies (docs/native-packet-reception-feedback.md).
static uint32_t be32(const uint8_t* p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

static int decodeReport(const uint8_t* b, size_t n, PACKET_RECEPTION_REPORT* r, const char** why) {
    uint8_t i;

    memset(r, 0, sizeof(*r));
    *why = NULL;
    if (n < PACKET_RECEPTION_HEADER_BYTES) { *why = "short"; return -1; }
    if (b[0] != 1) { *why = "version"; return -1; }
    if (b[1] & 0xF0) { *why = "reserved flags"; return -1; }
    if (b[3] != 0) { *why = "reserved byte"; return -1; }
    if (b[2] > PACKET_RECEPTION_MAX_SAMPLES) { *why = "sample count"; return -1; }
    if (n != PACKET_RECEPTION_HEADER_BYTES + (size_t)PACKET_RECEPTION_SAMPLE_BYTES * b[2]) { *why = "length"; return -1; }

    r->flags = b[1];
    r->sampleCount = b[2];
    r->reportSequence = be32(b + 4);
    r->intervalUs = be32(b + 8);
    r->intervalEndUs = ((uint64_t)be32(b + 12) << 32) | be32(b + 16);
    r->receiveLoopTick = be32(b + 20);
    r->acceptedDataPackets = be32(b + 24);
    r->acceptedParityPackets = be32(b + 28);
    r->acceptedDataUdpBytes = be32(b + 32);
    r->acceptedParityUdpBytes = be32(b + 36);
    r->lastAcceptedAgeUs = be32(b + 40);
    r->firstFrame = be32(b + 44);
    r->lastFrame = be32(b + 48);
    for (i = 0; i < r->sampleCount; i++) {
        const uint8_t* s = b + PACKET_RECEPTION_HEADER_BYTES + PACKET_RECEPTION_SAMPLE_BYTES * i;
        r->samples[i].frameIndex = be32(s);
        r->samples[i].rtpSequence = (uint16_t)((s[4] << 8) | s[5]);
        r->samples[i].socketReadAgeUs = be32(s + 6);
    }

    if ((((uint64_t)r->acceptedDataPackets + r->acceptedParityPackets) > 0) != !!(r->flags & PACKET_RECEPTION_FLAG_HAS_ACCEPTED)) {
        *why = "HAS_ACCEPTED inconsistent"; return -1;
    }
    if (!(r->flags & PACKET_RECEPTION_FLAG_HAS_ACCEPTED) && (r->firstFrame != 0 || r->lastFrame != 0)) {
        *why = "frame bounds without HAS_ACCEPTED"; return -1;
    }
    if (r->firstFrame > r->lastFrame) { *why = "frame order"; return -1; }

    if (r->flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE) {
        if (!(r->flags & PACKET_RECEPTION_FLAG_INCOMPLETE) || r->sampleCount != 0 || r->intervalUs != 0 ||
                r->intervalEndUs != 0 || r->lastAcceptedAgeUs != UINT32_MAX) {
            *why = "clock sentinel"; return -1;
        }
        return 0;
    }

    if (r->intervalUs == 0) { *why = "zero interval"; return -1; }
    if (r->intervalUs > PACKET_RECEPTION_MAX_INTERVAL_US) {
        if (!(r->flags & PACKET_RECEPTION_FLAG_INCOMPLETE)) { *why = "unmarked long interval"; return -1; }
        if (r->sampleCount != 0) { *why = "long interval with samples"; return -1; }
    }
    if (r->intervalEndUs < r->intervalUs) { *why = "end before interval"; return -1; }
    for (i = 0; i < r->sampleCount; i++) {
        if (r->samples[i].socketReadAgeUs > PACKET_RECEPTION_MAX_SAMPLE_AGE_US ||
                r->samples[i].socketReadAgeUs > r->intervalEndUs) {
            *why = "sample age"; return -1;
        }
    }
    return 0;
}

static PACKET_RECEPTION_CAPTURE capture(uint64_t readUs, uint32_t bytes) {
    PACKET_RECEPTION_CAPTURE c;
    memset(&c, 0, sizeof(c));
    c.socketReadUs = readUs;
    c.udpBytes = bytes;
    c.clockValid = true;
    return c;
}

static void commitAt(PPACKET_RECEPTION_COLLECTOR c, uint64_t readUs, uint64_t commitUs, uint32_t frame, uint16_t seq, bool parity) {
    PACKET_RECEPTION_CAPTURE cap = capture(readUs, 1040);
    PacketReceptionCollectorCommit(c, &cap, true, commitUs, frame, seq, parity);
}

static bool takeValid(PPACKET_RECEPTION_COLLECTOR c, uint64_t nowUs, PACKET_RECEPTION_REPORT* decoded) {
    PACKET_RECEPTION_REPORT report;
    uint8_t wire[PACKET_RECEPTION_MAX_PAYLOAD_BYTES];
    const char* why;
    int length;

    if (!PacketReceptionCollectorTakeReport(c, true, nowUs, &report)) {
        return false;
    }
    length = PacketReceptionSerialize(&report, wire, sizeof(wire));
    CHECK(length == PACKET_RECEPTION_HEADER_BYTES + PACKET_RECEPTION_SAMPLE_BYTES * report.sampleCount, "length %d", length);
    CHECK(decodeReport(wire, (size_t)length, decoded, &why) == 0, "decode refused: %s", why ? why : "?");
    return true;
}

static void hexOf(const uint8_t* b, int n, char* out) {
    int i;
    for (i = 0; i < n; i++) {
        snprintf(out + 2 * i, 3, "%02x", b[i]);
    }
    out[2 * n] = 0;
}

static const char* const goldenNames[4] = { "zero", "accepted", "long", "clock" };
static const char* const goldenHex[4] = {
    "0100000000000001000186a000000000000186a00000000100000000000000000000000000000000ffffffff0000000000000000",
    "01010200000000020001879a0000000000030e3a0000003900000028000000040000a28000001040000004b0000000110000001200000011123400017ed000000012124000002ee0",
    "0103000000000003002625a000000000002933da00000052000000030000000000000c300000000000249f000000001300000013",
    "01060000000000040000000000000000000000000000005a00000000000000000000000000000000ffffffff0000000000000000",
};

static void goldenReport(int which, PACKET_RECEPTION_REPORT* r) {
    memset(r, 0, sizeof(*r));
    switch (which) {
    case 0:
        r->reportSequence = 1; r->intervalUs = 100000; r->intervalEndUs = 100000; r->receiveLoopTick = 1;
        r->lastAcceptedAgeUs = UINT32_MAX;
        break;
    case 1:
        r->flags = PACKET_RECEPTION_FLAG_HAS_ACCEPTED; r->reportSequence = 2; r->intervalUs = 100250;
        r->intervalEndUs = 200250; r->receiveLoopTick = 57; r->acceptedDataPackets = 40; r->acceptedParityPackets = 4;
        r->acceptedDataUdpBytes = 41600; r->acceptedParityUdpBytes = 4160; r->lastAcceptedAgeUs = 1200;
        r->firstFrame = 17; r->lastFrame = 18; r->sampleCount = 2;
        r->samples[0].frameIndex = 17; r->samples[0].rtpSequence = 0x1234; r->samples[0].socketReadAgeUs = 98000;
        r->samples[1].frameIndex = 18; r->samples[1].rtpSequence = 0x1240; r->samples[1].socketReadAgeUs = 12000;
        break;
    case 2:
        r->flags = PACKET_RECEPTION_FLAG_HAS_ACCEPTED | PACKET_RECEPTION_FLAG_INCOMPLETE; r->reportSequence = 3;
        r->intervalUs = 2500000; r->intervalEndUs = 2700250; r->receiveLoopTick = 82; r->acceptedDataPackets = 3;
        r->acceptedDataUdpBytes = 3120; r->lastAcceptedAgeUs = 2400000; r->firstFrame = 19; r->lastFrame = 19;
        break;
    default:
        r->flags = PACKET_RECEPTION_FLAG_INCOMPLETE | PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE; r->reportSequence = 4;
        r->receiveLoopTick = 90; r->lastAcceptedAgeUs = UINT32_MAX;
        break;
    }
}

static void unitTests(void) {
    PACKET_RECEPTION_COLLECTOR c;
    PACKET_RECEPTION_REPORT r, d;
    uint8_t wire[PACKET_RECEPTION_MAX_PAYLOAD_BYTES + 8];
    const char* why;
    int i;
    const uint64_t t0 = 5000000;

    beginCase("serializer_bounds_0_16_17");
    memset(&r, 0, sizeof(r));
    r.reportSequence = 1; r.intervalUs = 1; r.intervalEndUs = 1; r.lastAcceptedAgeUs = UINT32_MAX;
    CHECK(PacketReceptionSerialize(&r, wire, sizeof(wire)) == 52, "zero samples");
    r.flags = PACKET_RECEPTION_FLAG_HAS_ACCEPTED; r.acceptedDataPackets = 16; r.firstFrame = 1; r.lastFrame = 1;
    r.sampleCount = 16;
    for (i = 0; i < 16; i++) {
        r.samples[i].frameIndex = 1; r.samples[i].rtpSequence = (uint16_t)i; r.samples[i].socketReadAgeUs = 1;
    }
    memset(wire, 0xA5, sizeof(wire));
    CHECK(PacketReceptionSerialize(&r, wire, 212) == 212, "sixteen samples");
    for (i = 212; i < (int)sizeof(wire); i++) {
        CHECK(wire[i] == 0xA5, "wrote past 212 at %d", i);
    }
    CHECK(decodeReport(wire, 212, &d, &why) == 0 && d.sampleCount == 16 && d.samples[15].rtpSequence == 15, "decode 16");
    CHECK(PacketReceptionSerialize(&r, wire, 211) == -1, "short buffer accepted");
    memset(wire, 0xA5, sizeof(wire));
    CHECK(PacketReceptionSerialize(&r, wire, 211) == -1 && wire[0] == 0xA5, "short buffer was written");
    r.sampleCount = 17;
    CHECK(PacketReceptionSerialize(&r, wire, sizeof(wire)) == -1, "seventeen samples accepted");
    r.sampleCount = 0; r.flags = 0x10;
    CHECK(PacketReceptionSerialize(&r, wire, sizeof(wire)) == -1, "reserved flag accepted");
    CHECK(PacketReceptionSerialize(NULL, wire, sizeof(wire)) == -1, "NULL report accepted");
    endCase();

    beginCase("golden_encodings");
    for (i = 0; i < 4; i++) {
        char hex[2 * PACKET_RECEPTION_MAX_PAYLOAD_BYTES + 1];
        int n;
        goldenReport(i, &r);
        n = PacketReceptionSerialize(&r, wire, sizeof(wire));
        CHECK(n > 0, "golden %s refused", goldenNames[i]);
        if (n > 0) {
            hexOf(wire, n, hex);
            CHECK(strcmp(hex, goldenHex[i]) == 0, "golden %s = %s", goldenNames[i], hex);
            CHECK(decodeReport(wire, (size_t)n, &d, &why) == 0, "golden %s decode: %s", goldenNames[i], why ? why : "");
        }
    }
    endCase();

    beginCase("decoder_refuses_malformed");
    goldenReport(1, &r);
    i = PacketReceptionSerialize(&r, wire, sizeof(wire));
    CHECK(decodeReport(wire, (size_t)i - 1, &d, &why) != 0, "truncated");
    CHECK(decodeReport(wire, (size_t)i + 1, &d, &why) != 0, "extra byte");
    wire[0] = 2; CHECK(decodeReport(wire, (size_t)i, &d, &why) != 0, "version"); wire[0] = 1;
    wire[1] |= 0x80; CHECK(decodeReport(wire, (size_t)i, &d, &why) != 0, "reserved flags"); wire[1] &= 0x7F;
    wire[3] = 1; CHECK(decodeReport(wire, (size_t)i, &d, &why) != 0, "reserved byte"); wire[3] = 0;
    wire[2] = 17; CHECK(decodeReport(wire, (size_t)i, &d, &why) != 0, "count 17"); wire[2] = 2;
    wire[1] &= (uint8_t)~PACKET_RECEPTION_FLAG_HAS_ACCEPTED; CHECK(decodeReport(wire, (size_t)i, &d, &why) != 0, "HAS_ACCEPTED");
    endCase();

    beginCase("first_accepted_per_bin_keeps_socket_read_time");
    PacketReceptionCollectorStart(&c, true, t0);
    commitAt(&c, t0 + 10, t0 + 20, 1, 100, false);
    commitAt(&c, t0 + 30, t0 + 40, 1, 101, false);          // same bin 0: counted, not sampled
    commitAt(&c, t0 + 6000, t0 + 6260, 1, 102, false);      // bin 1, read earlier in bin 0
    commitAt(&c, t0 + 13000, t0 + 13001, 2, 103, true);     // bin 2 parity
    CHECK(takeValid(&c, t0 + 100000, &d), "no report");
    CHECK(d.reportSequence == 1 && d.intervalUs == 100000 && d.intervalEndUs == 100000, "header %u %u", d.reportSequence, d.intervalUs);
    CHECK(d.acceptedDataPackets == 3 && d.acceptedParityPackets == 1, "counts %u %u", d.acceptedDataPackets, d.acceptedParityPackets);
    CHECK(d.acceptedDataUdpBytes == 3 * 1040 && d.acceptedParityUdpBytes == 1040, "bytes");
    CHECK(d.flags == PACKET_RECEPTION_FLAG_HAS_ACCEPTED && d.firstFrame == 1 && d.lastFrame == 2, "flags %x", d.flags);
    CHECK(d.sampleCount == 3, "samples %u", d.sampleCount);
    CHECK(d.samples[0].rtpSequence == 100 && d.samples[0].socketReadAgeUs == 100000 - 10, "sample0");
    CHECK(d.samples[1].rtpSequence == 102 && d.samples[1].socketReadAgeUs == 100000 - 6000, "sample1 keeps read time");
    CHECK(d.samples[2].rtpSequence == 103 && d.samples[2].frameIndex == 2, "sample2");
    CHECK(d.lastAcceptedAgeUs == 100000 - 13001, "age %u", d.lastAcceptedAgeUs);
    endCase();

    beginCase("sixteen_samples_then_timing_sample_limit");
    PacketReceptionCollectorStart(&c, true, t0);
    for (i = 0; i < 16; i++) {
        commitAt(&c, t0 + (uint64_t)i * 6250, t0 + (uint64_t)i * 6250 + 1, 3, (uint16_t)(65530 + i), false);
    }
    commitAt(&c, t0 + 100001, t0 + 100002, 4, 10, false);    // bin 16
    CHECK(takeValid(&c, t0 + 110000, &d), "no report");
    CHECK(d.sampleCount == 16, "count %u", d.sampleCount);
    CHECK(d.acceptedDataPackets == 17 && d.lastFrame == 4, "counts cover all commits");
    CHECK(d.flags == (PACKET_RECEPTION_FLAG_HAS_ACCEPTED | PACKET_RECEPTION_FLAG_TIMING_SAMPLE_LIMIT), "flags %x", d.flags);
    CHECK(d.samples[5].rtpSequence == 65535 && d.samples[6].rtpSequence == 0, "RTP wrap kept verbatim");
    endCase();

    beginCase("zero_media_and_receive_loop_progress");
    PacketReceptionCollectorStart(&c, true, t0);
    PacketReceptionCollectorNoteReceiveCall(&c);
    PacketReceptionCollectorNoteReceiveCall(&c);
    CHECK(takeValid(&c, t0 + 100000, &d), "no report");
    CHECK(d.flags == 0 && d.acceptedDataPackets == 0 && d.firstFrame == 0 && d.lastFrame == 0 && d.sampleCount == 0, "zero");
    CHECK(d.lastAcceptedAgeUs == UINT32_MAX && d.receiveLoopTick == 2, "age %u tick %u", d.lastAcceptedAgeUs, d.receiveLoopTick);
    CHECK(takeValid(&c, t0 + 200000, &d), "stalled loop report missing");
    CHECK(d.reportSequence == 2 && d.receiveLoopTick == 2, "stalled loop must not advance tick");
    commitAt(&c, t0 + 250000, t0 + 250000, 9, 1, false);
    CHECK(takeValid(&c, t0 + 300000, &d), "report 3");
    CHECK(takeValid(&c, t0 + 400000, &d), "report 4");
    CHECK(d.flags == 0 && d.acceptedDataPackets == 0 && d.lastAcceptedAgeUs == 150000, "zero after media: age %u", d.lastAcceptedAgeUs);
    endCase();

    beginCase("delayed_acceptance_counts_in_accept_interval");
    PacketReceptionCollectorStart(&c, true, t0);
    CHECK(takeValid(&c, t0 + 100000, &d) && d.acceptedDataPackets == 0, "first interval");
    commitAt(&c, t0 + 50000, t0 + 130000, 5, 7, false);    // read in interval 1, accepted in 2
    CHECK(takeValid(&c, t0 + 200000, &d), "second interval");
    CHECK(d.acceptedDataPackets == 1 && d.sampleCount == 1, "counted at acceptance");
    CHECK(d.samples[0].socketReadAgeUs == 150000 && d.samples[0].socketReadAgeUs > d.intervalUs, "read time preserved");
    CHECK(!(d.flags & PACKET_RECEPTION_FLAG_INCOMPLETE), "delayed acceptance is valid");
    endCase();

    beginCase("stale_sample_omitted_incomplete");
    PacketReceptionCollectorStart(&c, true, t0);
    for (i = 1; i <= 20; i++) {
        CHECK(takeValid(&c, t0 + (uint64_t)i * 100000, &d), "tick %d", i);
    }
    commitAt(&c, t0 + 1000, t0 + 2000500, 6, 9, false);
    CHECK(takeValid(&c, t0 + 2100000, &d), "late report");
    CHECK(d.acceptedDataPackets == 1 && d.sampleCount == 0, "stale sample kept");
    CHECK(d.flags == (PACKET_RECEPTION_FLAG_HAS_ACCEPTED | PACKET_RECEPTION_FLAG_INCOMPLETE), "flags %x", d.flags);
    endCase();

    beginCase("long_interval_incomplete_without_samples");
    PacketReceptionCollectorStart(&c, true, t0);
    commitAt(&c, t0 + 10, t0 + 20, 7, 1, false);
    CHECK(takeValid(&c, t0 + 2500000, &d), "long report");
    CHECK(d.flags == (PACKET_RECEPTION_FLAG_HAS_ACCEPTED | PACKET_RECEPTION_FLAG_INCOMPLETE), "flags %x", d.flags);
    CHECK(d.intervalUs == 2500000 && d.intervalEndUs == 2500000 && d.sampleCount == 0, "interval %u", d.intervalUs);
    CHECK(d.acceptedDataPackets == 1 && d.lastAcceptedAgeUs == 2499980, "facts retained");
    commitAt(&c, t0 + 2500010, t0 + 2500020, 8, 2, false);
    CHECK(takeValid(&c, t0 + 2600000, &d), "next report");
    CHECK(d.flags == PACKET_RECEPTION_FLAG_HAS_ACCEPTED && d.sampleCount == 1 && d.intervalUs == 100000, "recovers");
    CHECK(takeValid(&c, t0 + 2600000 + 0x100000005ULL, &d), "saturated report");
    CHECK(d.intervalUs == UINT32_MAX && (d.flags & PACKET_RECEPTION_FLAG_INCOMPLETE) && d.sampleCount == 0, "saturation");
    CHECK(d.intervalEndUs == 2600000 + 0x100000005ULL, "end advances %llu", (unsigned long long)d.intervalEndUs);
    CHECK(d.lastAcceptedAgeUs == UINT32_MAX, "unrepresentable age");
    // Just over 2 s: the sample itself is young enough, but a long interval still carries none.
    PacketReceptionCollectorStart(&c, true, t0);
    commitAt(&c, t0 + 60000, t0 + 60000, 9, 3, false);
    CHECK(takeValid(&c, t0 + 2050000, &d), "just-long report");
    CHECK((d.flags & PACKET_RECEPTION_FLAG_INCOMPLETE) && d.sampleCount == 0 && d.acceptedDataPackets == 1, "samples in long interval");
    endCase();

    beginCase("counter_saturation_incomplete");
    PacketReceptionCollectorStart(&c, true, t0);
    c.windowUdpBytes[1] = UINT32_MAX - 10;
    c.windowPackets[1] = 1;
    c.windowHasAccepted = true;
    c.windowFirstFrame = c.windowLastFrame = 3;
    commitAt(&c, t0 + 1, t0 + 2, 3, 4, true);
    CHECK(takeValid(&c, t0 + 100000, &d), "report");
    CHECK(d.acceptedParityUdpBytes == UINT32_MAX && (d.flags & PACKET_RECEPTION_FLAG_INCOMPLETE), "saturation flagged");
    endCase();

    beginCase("clock_failure_sentinel_once");
    {
        PACKET_RECEPTION_CAPTURE bad = capture(t0 + 5, 1040);
        PacketReceptionCollectorStart(&c, true, t0);
        PacketReceptionCollectorNoteReceiveCall(&c);
        PacketReceptionCollectorCommit(&c, &bad, false, 0, 11, 3, false);
        CHECK(takeValid(&c, t0 + 100000, &d), "sentinel missing");
        CHECK(d.flags == (PACKET_RECEPTION_FLAG_HAS_ACCEPTED | PACKET_RECEPTION_FLAG_INCOMPLETE | PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE),
              "flags %x", d.flags);
        CHECK(d.intervalUs == 0 && d.intervalEndUs == 0 && d.sampleCount == 0 && d.lastAcceptedAgeUs == UINT32_MAX, "sentinel fields");
        CHECK(d.acceptedDataPackets == 1 && d.firstFrame == 11 && d.receiveLoopTick == 1, "retained facts");
        commitAt(&c, t0 + 200000, t0 + 200001, 12, 4, false);
        PacketReceptionCollectorNoteReceiveCall(&c);
        CHECK(!PacketReceptionCollectorTakeReport(&c, true, t0 + 300000, &r), "report after sentinel");
        CHECK(c.receiveLoopTick == 1 && c.windowPackets[0] == 0, "retired collector changed");
    }
    endCase();

    beginCase("clock_regression_paths");
    {
        PACKET_RECEPTION_CAPTURE cap;
        PacketReceptionCollectorStart(&c, true, t0);
        CHECK(takeValid(&c, t0 + 100000, &d), "first");
        commitAt(&c, t0 + 90000, t0 + 99999, 1, 1, false);       // commit before last snapshot
        CHECK(takeValid(&c, t0 + 200000, &d) && (d.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE), "commit regression");

        PacketReceptionCollectorStart(&c, true, t0);
        CHECK(takeValid(&c, t0 + 100000, &d), "first");
        CHECK(PacketReceptionCollectorTakeReport(&c, true, t0 + 50000, &r) &&
              (r.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE) && r.reportSequence == 2, "snapshot regression");

        PacketReceptionCollectorStart(&c, true, t0);
        cap = capture(t0 + 20, 1040);
        PacketReceptionCollectorCommit(&c, &cap, true, t0 + 10, 1, 1, false);   // read after commit
        CHECK(takeValid(&c, t0 + 100000, &d) && (d.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE), "read after commit");

        PacketReceptionCollectorStart(&c, true, t0);
        cap = capture(t0 - 1, 1040);
        PacketReceptionCollectorCommit(&c, &cap, true, t0 + 10, 1, 1, false);   // read before origin
        CHECK(takeValid(&c, t0 + 100000, &d) && (d.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE), "read before origin");

        PacketReceptionCollectorStart(&c, true, t0);
        cap = capture(t0 + 1, 1040);
        cap.clockValid = false;
        PacketReceptionCollectorCommit(&c, &cap, true, t0 + 10, 1, 1, false);   // failed socket-read clock
        CHECK(takeValid(&c, t0 + 100000, &d) && (d.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE), "socket read clock");

        PacketReceptionCollectorStart(&c, true, t0);
        CHECK(PacketReceptionCollectorTakeReport(&c, false, 0, &r) && (r.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE), "snapshot read");

        PacketReceptionCollectorStart(&c, false, 0);
        CHECK(takeValid(&c, 0, &d) && d.reportSequence == 1 && (d.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE), "unsupported at start");
        CHECK(!PacketReceptionCollectorTakeReport(&c, true, t0, &r), "restarted epoch inside a session");
    }
    endCase();

    beginCase("zero_length_interval_skipped_without_sequence");
    PacketReceptionCollectorStart(&c, true, t0);
    CHECK(!PacketReceptionCollectorTakeReport(&c, true, t0, &r), "zero-length report");
    CHECK(takeValid(&c, t0 + 1, &d) && d.reportSequence == 1 && d.intervalUs == 1, "sequence consumed");
    endCase();

    beginCase("sequence_stops_before_wrap_and_retire");
    PacketReceptionCollectorStart(&c, true, t0);
    c.lastSequence = UINT32_MAX - 1;
    CHECK(takeValid(&c, t0 + 100000, &d) && d.reportSequence == UINT32_MAX, "last sequence");
    CHECK(!PacketReceptionCollectorTakeReport(&c, true, t0 + 200000, &r), "wrapped");
    CHECK(!PacketReceptionCollectorTakeReport(&c, true, t0 + 300000, &r), "revived");
    PacketReceptionCollectorStart(&c, true, t0);
    PacketReceptionCollectorRetire(&c);
    commitAt(&c, t0 + 1, t0 + 2, 1, 1, false);
    CHECK(!PacketReceptionCollectorTakeReport(&c, true, t0 + 100000, &r) && c.windowPackets[0] == 0, "retired");
    endCase();

    beginCase("restart_resets_epoch");
    PacketReceptionCollectorStart(&c, true, t0);
    commitAt(&c, t0 + 1, t0 + 2, 40, 9, false);
    PacketReceptionCollectorNoteReceiveCall(&c);
    CHECK(takeValid(&c, t0 + 100000, &d) && takeValid(&c, t0 + 200000, &d) && d.reportSequence == 2, "old epoch");
    PacketReceptionCollectorStart(&c, true, t0 + 900000);
    CHECK(takeValid(&c, t0 + 1000000, &d), "new epoch");
    CHECK(d.reportSequence == 1 && d.intervalEndUs == 100000 && d.receiveLoopTick == 0, "sequence/origin/tick reset");
    CHECK(d.flags == 0 && d.lastAcceptedAgeUs == UINT32_MAX && d.sampleCount == 0, "no stale media");
    endCase();

    beginCase("host_offer_parser");
    {
        static const struct { const char* sdp; int expected; } offers[] = {
            { "v=0\r\na=x-ss-video.packetReceptionFeedbackVersion:1\r\n", PACKET_RECEPTION_OFFER_VERSION_1 },
            { "a=x-ss-video.packetReceptionFeedbackVersion:1\n", PACKET_RECEPTION_OFFER_VERSION_1 },
            { "a=x-ss-video.packetReceptionFeedbackVersion:1", PACKET_RECEPTION_OFFER_VERSION_1 },
            { "a=x-ss-video.packetReceptionFeedbackVersion: \t1 \r\n", PACKET_RECEPTION_OFFER_VERSION_1 },
            { "a=x-ss-video.packetReceptionFeedbackVersionX:1\n", PACKET_RECEPTION_OFFER_ABSENT },
            { "a=x-ss-general.featureFlags:3\n", PACKET_RECEPTION_OFFER_ABSENT },
            { "x a=x-ss-video.packetReceptionFeedbackVersion:1\n", PACKET_RECEPTION_OFFER_ABSENT },
            { "", PACKET_RECEPTION_OFFER_ABSENT },
            { NULL, PACKET_RECEPTION_OFFER_ABSENT },
            { "a=x-ss-video.packetReceptionFeedbackVersion:2\n", PACKET_RECEPTION_OFFER_REFUSED },
            { "a=x-ss-video.packetReceptionFeedbackVersion:01\n", PACKET_RECEPTION_OFFER_REFUSED },
            { "a=x-ss-video.packetReceptionFeedbackVersion:1 2\n", PACKET_RECEPTION_OFFER_REFUSED },
            { "a=x-ss-video.packetReceptionFeedbackVersion:1x\n", PACKET_RECEPTION_OFFER_REFUSED },
            { "a=x-ss-video.packetReceptionFeedbackVersion:\n", PACKET_RECEPTION_OFFER_REFUSED },
            { "a=x-ss-video.packetReceptionFeedbackVersion\n", PACKET_RECEPTION_OFFER_REFUSED },
            { "a=x-ss-video.packetReceptionFeedbackVersion :1\n", PACKET_RECEPTION_OFFER_REFUSED },
            { "a=x-ss-video.packetReceptionFeedbackVersion:1\na=x-ss-video.packetReceptionFeedbackVersion:2\n", PACKET_RECEPTION_OFFER_REFUSED },
            { "a=x-ss-video.packetReceptionFeedbackVersion:2\na=x-ss-video.packetReceptionFeedbackVersion:1\n", PACKET_RECEPTION_OFFER_REFUSED },
            { "a=x-ss-video.packetReceptionFeedbackVersion:1\na=x-ss-video.packetReceptionFeedbackVersion:1\n", PACKET_RECEPTION_OFFER_REFUSED },
            { "a=x-ss-video.packetReceptionFeedbackVersion:1\r\nv=0\r\na=x-ss-video.packetReceptionFeedbackVersion: 1 \r\n", PACKET_RECEPTION_OFFER_REFUSED },
        };
        for (i = 0; i < (int)(sizeof(offers) / sizeof(offers[0])); i++) {
            int got = PacketReceptionParseHostOffer(offers[i].sdp);
            CHECK(got == offers[i].expected, "offer %d: got %d expected %d", i, got, offers[i].expected);
        }
    }
    endCase();

    beginCase("gate_function");
    {
        const uint32_t both = SS_ENC_CONTROL_V2 | SS_ENC_VIDEO;
        CHECK(PacketReceptionGatesPass(true, true, PACKET_RECEPTION_OFFER_VERSION_1, true, both, true), "all gates");
        CHECK(!PacketReceptionGatesPass(false, true, PACKET_RECEPTION_OFFER_VERSION_1, true, both, true), "not Sunshine");
        CHECK(!PacketReceptionGatesPass(true, false, PACKET_RECEPTION_OFFER_VERSION_1, true, both, true), "not protocol 13");
        CHECK(!PacketReceptionGatesPass(true, true, PACKET_RECEPTION_OFFER_ABSENT, true, both, true), "absent offer");
        CHECK(!PacketReceptionGatesPass(true, true, PACKET_RECEPTION_OFFER_REFUSED, true, both, true), "refused offer");
        CHECK(!PacketReceptionGatesPass(true, true, PACKET_RECEPTION_OFFER_VERSION_1, false, both, true), "no connect data");
        CHECK(!PacketReceptionGatesPass(true, true, PACKET_RECEPTION_OFFER_VERSION_1, true, SS_ENC_VIDEO, true), "no control v2");
        CHECK(!PacketReceptionGatesPass(true, true, PACKET_RECEPTION_OFFER_VERSION_1, true, 0, true), "no encryption");
        CHECK(PacketReceptionGatesPass(true, true, PACKET_RECEPTION_OFFER_VERSION_1, true, SS_ENC_CONTROL_V2, true), "plaintext video refused");
        CHECK(!PacketReceptionGatesPass(true, true, PACKET_RECEPTION_OFFER_VERSION_1, true, both, false), "no clock");
    }
    endCase();

    beginCase("internal_bounds");
    CHECK(sizeof(PACKET_RECEPTION_SAMPLE) == 16, "sample %zu", sizeof(PACKET_RECEPTION_SAMPLE));
    CHECK(sizeof(PACKET_RECEPTION_COLLECTOR) <= 1024, "collector %zu", sizeof(PACKET_RECEPTION_COLLECTOR));
    CHECK(PACKET_RECEPTION_MAX_PAYLOAD_BYTES == 212 && 4 + PACKET_RECEPTION_MAX_PAYLOAD_BYTES < 256, "control scratch");
    printf("BOUNDS collector=%zu sample=%zu report=%zu payload=%d\n", sizeof(PACKET_RECEPTION_COLLECTOR),
           sizeof(PACKET_RECEPTION_SAMPLE), sizeof(PACKET_RECEPTION_REPORT), PACKET_RECEPTION_MAX_PAYLOAD_BYTES);
    endCase();
}

// ---------------------------------------------------------------------------------------------
// Loopback session fixtures

#define MAX_CONTROL_MESSAGES 1024
#define DATA_SHARDS 4
#define FEC_PERCENT 50
#define PARITY_SHARDS 2

typedef struct {
    uint16_t type;
    uint16_t length;
    uint8_t channel;
    uint32_t enetFlags;
    // The fixture host's own ENet view of the client's throttle deceleration when this arrived.
    uint32_t hostPeerDeceleration;
    uint64_t receivedUs;
    uint8_t payload[256];
} CONTROL_MESSAGE;

static pthread_mutex_t controlLock = PTHREAD_MUTEX_INITIALIZER;
static CONTROL_MESSAGE controlMessages[MAX_CONTROL_MESSAGES];
static int controlMessageCount;
static int controlDecryptFailures;
static uint32_t observedConnectData;
// Throttle deceleration the client's CONNECT gave the fixture host's peer. (ENet resets the host
// peer before it dispatches DISCONNECT, so the end of a connection is judged on the wire below.)
static uint32_t observedConnectDeceleration;

// Every ENet THROTTLE_CONFIGURE command the client sent, read from the host's received datagrams
// before ENet handles them; retransmissions share a reliable sequence number and count once.
#define MAX_THROTTLE_CONFIGURES 16
typedef struct {
    uint16_t reliableSequence;
    uint32_t interval;
    uint32_t acceleration;
    uint32_t deceleration;
} THROTTLE_CONFIGURE_SEEN;
static THROTTLE_CONFIGURE_SEEN throttleConfigures[MAX_THROTTLE_CONFIGURES];
static int throttleConfigureCount;
static int throttleConfigureCopies;

static int ENET_CALLBACK interceptThrottleConfigure(ENetHost* host, ENetEvent* event) {
    const enet_uint8* data = host->receivedData;
    size_t length = host->receivedDataLength;
    size_t offset;
    enet_uint16 peerId;

    (void)event;
    if (length < sizeof(peerId)) {
        return 0;
    }
    memcpy(&peerId, data, sizeof(peerId));
    peerId = ENET_NET_TO_HOST_16(peerId);
    if (peerId & ENET_PROTOCOL_HEADER_FLAG_COMPRESSED) {
        return 0;
    }
    offset = (peerId & ENET_PROTOCOL_HEADER_FLAG_SENT_TIME) ? sizeof(ENetProtocolHeader) : offsetof(ENetProtocolHeader, sentTime);
    if (host->checksum != NULL) {
        offset += sizeof(enet_uint32);
    }
    while (offset + sizeof(ENetProtocolCommandHeader) <= length) {
        const ENetProtocol* command = (const ENetProtocol*)(data + offset);
        enet_uint8 number = command->header.command & ENET_PROTOCOL_COMMAND_MASK;
        size_t size;

        if (number >= ENET_PROTOCOL_COMMAND_COUNT) {
            break;
        }
        size = enet_protocol_command_size(number);
        if (size == 0 || offset + size > length) {
            break;
        }
        if (number == ENET_PROTOCOL_COMMAND_THROTTLE_CONFIGURE) {
            enet_uint16 sequence = ENET_NET_TO_HOST_16(command->header.reliableSequenceNumber);
            int i;
            bool known = false;

            pthread_mutex_lock(&controlLock);
            throttleConfigureCopies++;
            for (i = 0; i < throttleConfigureCount; i++) {
                known = known || throttleConfigures[i].reliableSequence == sequence;
            }
            if (!known && throttleConfigureCount < MAX_THROTTLE_CONFIGURES) {
                THROTTLE_CONFIGURE_SEEN* seen = &throttleConfigures[throttleConfigureCount++];
                seen->reliableSequence = sequence;
                seen->interval = ENET_NET_TO_HOST_32(command->throttleConfigure.packetThrottleInterval);
                seen->acceleration = ENET_NET_TO_HOST_32(command->throttleConfigure.packetThrottleAcceleration);
                seen->deceleration = ENET_NET_TO_HOST_32(command->throttleConfigure.packetThrottleDeceleration);
            }
            pthread_mutex_unlock(&controlLock);
        }
        offset += size;
        switch (number) {
        case ENET_PROTOCOL_COMMAND_SEND_RELIABLE:
            offset += ENET_NET_TO_HOST_16(command->sendReliable.dataLength);
            break;
        case ENET_PROTOCOL_COMMAND_SEND_UNRELIABLE:
            offset += ENET_NET_TO_HOST_16(command->sendUnreliable.dataLength);
            break;
        case ENET_PROTOCOL_COMMAND_SEND_UNSEQUENCED:
            offset += ENET_NET_TO_HOST_16(command->sendUnsequenced.dataLength);
            break;
        case ENET_PROTOCOL_COMMAND_SEND_FRAGMENT:
        case ENET_PROTOCOL_COMMAND_SEND_UNRELIABLE_FRAGMENT:
            offset += ENET_NET_TO_HOST_16(command->sendFragment.dataLength);
            break;
        default:
            break;
        }
    }
    return 0;
}

// Exactly the expected THROTTLE_CONFIGURE decelerations, each with ENet's default interval and
// acceleration, in order.
static bool throttleConfiguresAre(const uint32_t* decelerations, int count) {
    bool same;
    int i;

    pthread_mutex_lock(&controlLock);
    same = throttleConfigureCount == count;
    for (i = 0; same && i < count; i++) {
        same = throttleConfigures[i].deceleration == decelerations[i] &&
               throttleConfigures[i].interval == ENET_PEER_PACKET_THROTTLE_INTERVAL &&
               throttleConfigures[i].acceleration == ENET_PEER_PACKET_THROTTLE_ACCELERATION;
    }
    pthread_mutex_unlock(&controlLock);
    return same;
}

static int throttleConfiguresSeen(void) {
    int count;
    pthread_mutex_lock(&controlLock);
    count = throttleConfigureCount;
    pthread_mutex_unlock(&controlLock);
    return count;
}
static volatile bool serverStop;
static pthread_t serverThreadHandle;
static ENetHost* server;
static int videoSocket = -1;
static struct sockaddr_in clientVideoAddr;
static volatile int terminationError;
static volatile bool fixtureStopping;
static volatile bool clockBroken;
static volatile bool clockRegressed;
static uint32_t controlConnectDataValue = 0x5A5A1234;

static const unsigned char aesKey[16] = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };

static void* serverThread(void* context) {
    PPLT_CRYPTO_CONTEXT crypto = PltCreateCryptoContext();
    (void)context;

    while (!serverStop) {
        ENetEvent event;
        int ret = enet_host_service(server, &event, 5);
        if (ret <= 0) {
            continue;
        }
        if (event.type == ENET_EVENT_TYPE_CONNECT) {
            observedConnectData = event.data;
            pthread_mutex_lock(&controlLock);
            observedConnectDeceleration = event.peer->packetThrottleDeceleration;
            pthread_mutex_unlock(&controlLock);
        }
        else if (event.type == ENET_EVENT_TYPE_RECEIVE) {
            const uint8_t* data = event.packet->data;
            size_t length = event.packet->dataLength;

            if (length >= 8 + 16 + 4 && data[0] == 0x01 && data[1] == 0x00) {
                unsigned char iv[12] = { 0 };
                unsigned char plain[512];
                int plainLength = (int)(length - 8 - 16);
                memcpy(iv, data + 4, 4);    // little-endian sequence bytes
                iv[10] = 'C';
                iv[11] = 'C';
                if (plainLength <= (int)sizeof(plain) &&
                        PltDecryptMessage(crypto, ALGORITHM_AES_GCM, 0, (unsigned char*)aesKey, 16, iv, 12,
                                          (unsigned char*)data + 8, 16, (unsigned char*)data + 24, plainLength,
                                          plain, &plainLength) && plainLength >= 4) {
                    pthread_mutex_lock(&controlLock);
                    if (controlMessageCount < MAX_CONTROL_MESSAGES) {
                        CONTROL_MESSAGE* m = &controlMessages[controlMessageCount++];
                        m->type = (uint16_t)(plain[0] | (plain[1] << 8));
                        m->length = (uint16_t)(plain[2] | (plain[3] << 8));
                        m->channel = event.channelID;
                        m->enetFlags = event.packet->flags;
                        m->hostPeerDeceleration = event.peer->packetThrottleDeceleration;
                        m->receivedUs = realMonotonicUs();
                        if (m->length > sizeof(m->payload) || (int)m->length != plainLength - 4) {
                            controlDecryptFailures++;
                            m->length = 0;
                        }
                        else {
                            memcpy(m->payload, plain + 4, m->length);
                        }
                    }
                    pthread_mutex_unlock(&controlLock);
                }
                else {
                    pthread_mutex_lock(&controlLock);
                    controlDecryptFailures++;
                    pthread_mutex_unlock(&controlLock);
                }
            }
            else {
                pthread_mutex_lock(&controlLock);
                controlDecryptFailures++;
                pthread_mutex_unlock(&controlLock);
            }
            enet_packet_destroy(event.packet);
        }
    }

    PltDestroyCryptoContext(crypto);
    return NULL;
}

// The control stream's packet reception transport rows, kept for the transport fixtures.
#define TRANSPORT_ROW_PREFIX "Packet reception transport:"
#define MAX_TRANSPORT_ROWS 256
static pthread_mutex_t transportRowLock = PTHREAD_MUTEX_INITIALIZER;
static char transportRows[MAX_TRANSPORT_ROWS][256];
static int transportRowCount;
static int transportRowOverflow;

static void logMessage(const char* format, ...) {
    char line[512];
    va_list args;

    va_start(args, format);
    vsnprintf(line, sizeof(line), format, args);
    va_end(args);
    if (strncmp(line, TRANSPORT_ROW_PREFIX, strlen(TRANSPORT_ROW_PREFIX)) == 0) {
        pthread_mutex_lock(&transportRowLock);
        if (transportRowCount < MAX_TRANSPORT_ROWS && strlen(line) < sizeof(transportRows[0])) {
            strcpy(transportRows[transportRowCount++], line);
        }
        else {
            transportRowOverflow++;
        }
        pthread_mutex_unlock(&transportRowLock);
    }
    if (getenv("NOVA_RECEPTION_VERBOSE") != NULL) {
        fputs(line, stderr);
    }
}

static void connectionTerminated(int errorCode) {
    // LiStopConnection() suppresses termination callbacks once stopping starts; these fixtures
    // call the stage functions directly, so only a termination while streaming counts.
    if (!fixtureStopping) {
        terminationError = errorCode;
    }
}

static int drSetup(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) {
    (void)videoFormat; (void)width; (void)height; (void)redrawRate; (void)context; (void)drFlags;
    return 0;
}

static int drSubmit(PDECODE_UNIT decodeUnit) {
    (void)decodeUnit;
    return DR_OK;
}

static bool fixtureClock(uint64_t* nowUs) {
    if (clockBroken) {
        return false;
    }
    if (!PltGetMonotonicMicros(nowUs)) {
        return false;
    }
    if (clockRegressed) {
        *nowUs -= 1000000;
    }
    return true;
}

// Receiver-thread-only clock faults for the clock review sessions. The control (loss-stats)
// thread is the first non-main thread to read the clock: it reports as soon as it starts, long
// before any video is sent, and only the receive thread reads the clock for a positive receive.
static pthread_mutex_t fixtureClockLock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t harnessMainThread;
static pthread_t fixtureControlThread;
static bool fixtureControlThreadKnown;
static bool receiverFailNext;
static bool receiverBackwardNext;
static int receiverFaultsServed;
static uint64_t lastReceiverReadUs;
// Ordered snapshot case: after the armed receive read completes, the control thread's next read
// lands strictly between its previous read and that receive read.
static bool receiverArmBetween;
static bool controlBetweenPending;
static bool controlBetweenStrict;
static uint64_t armedReceiverReadUs;
static uint64_t lastControlReadUs;

static bool receiverFaultClock(uint64_t* nowUs) {
    pthread_t self = pthread_self();
    bool valid;

    pthread_mutex_lock(&fixtureClockLock);
    valid = PltGetMonotonicMicros(nowUs);
    if (!pthread_equal(self, harnessMainThread)) {
        if (!fixtureControlThreadKnown) {
            fixtureControlThread = self;
            fixtureControlThreadKnown = true;
        }
        if (pthread_equal(self, fixtureControlThread)) {
            if (controlBetweenPending && valid) {
                uint64_t between = lastControlReadUs + (armedReceiverReadUs - lastControlReadUs) / 2;
                controlBetweenPending = false;
                controlBetweenStrict = lastControlReadUs < between && between < armedReceiverReadUs;
                receiverFaultsServed++;
                *nowUs = between;
            }
            else if (valid) {
                lastControlReadUs = *nowUs;
            }
        }
        else {
            if (receiverFailNext) {
                receiverFailNext = false;
                receiverFaultsServed++;
                valid = false;
            }
            else if (receiverBackwardNext && valid) {
                receiverBackwardNext = false;
                receiverFaultsServed++;
                *nowUs = lastReceiverReadUs - 1000;
            }
            else if (valid) {
                lastReceiverReadUs = *nowUs;
                if (receiverArmBetween) {
                    receiverArmBetween = false;
                    armedReceiverReadUs = *nowUs;
                    controlBetweenPending = true;
                }
            }
        }
    }
    pthread_mutex_unlock(&fixtureClockLock);
    return valid;
}

// Scripted clock for single-threaded lifecycle fixtures through the production entrypoints. A
// step may run a callback before it returns, which interleaves a control snapshot between a
// socket-read clock read and the receive path taking the collector mutex.
typedef struct {
    bool valid;
    uint64_t value;
    void (*before)(void);
} CLOCK_STEP;

static CLOCK_STEP clockScript[16];
static int clockScriptLength;
static int clockScriptIndex;
static bool clockScriptOverrun;

static bool scriptedClock(uint64_t* nowUs) {
    CLOCK_STEP step;

    if (clockScriptIndex >= clockScriptLength) {
        clockScriptOverrun = true;
        *nowUs = 0;
        return false;
    }
    step = clockScript[clockScriptIndex++];
    if (step.before != NULL) {
        step.before();
    }
    *nowUs = step.value;
    return step.valid;
}

static void setClockScript(const CLOCK_STEP* steps, int count) {
    memcpy(clockScript, steps, sizeof(CLOCK_STEP) * (size_t)count);
    clockScriptLength = count;
    clockScriptIndex = 0;
    clockScriptOverrun = false;
}

typedef struct {
    const char* describe;
    bool connectData;
    int encryptionFlags;
    uint32_t hostEncryptionSupported;
    int appVersion[4];
    // Route control through the delaying relay, starting with this host-to-client delay.
    bool relay;
    uint32_t relayDelayUs;
    // Runs after negotiation and before the control stream starts (withdrawal, clock fault).
    void (*afterNegotiate)(void);
} SESSION_OPTIONS;

static SESSION_OPTIONS defaultOptions(void) {
    SESSION_OPTIONS o;
    memset(&o, 0, sizeof(o));
    o.describe = "v=0\r\na=x-ss-general.featureFlags:0\r\na=x-ss-video.packetReceptionFeedbackVersion:1\r\n";
    o.connectData = true;
    o.encryptionFlags = ENCFLG_VIDEO;
    o.hostEncryptionSupported = SS_ENC_CONTROL_V2 | SS_ENC_AUDIO | SS_ENC_VIDEO;
    o.appVersion[0] = 7; o.appVersion[1] = 1; o.appVersion[2] = 431; o.appVersion[3] = -1;
    return o;
}

static struct sockaddr_in loopback(uint16_t port) {
    struct sockaddr_in a;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = htons(port);
    return a;
}

// ---------------------------------------------------------------------------------------------
// Control relay for the transport fixtures
//
// Sits between the client's ENet control peer and the fixture host on loopback. Client datagrams
// pass at once; host datagrams are held for relayDelayUs and released in arrival order, so every
// acknowledgement the client's real ENet receives carries a scripted round-trip time. Nothing is
// dropped, duplicated or reordered: any report the host misses was discarded inside the client.

#define RELAY_SLOTS 1024
#define RELAY_DATAGRAM_BYTES 4096

typedef struct {
    uint64_t releaseUs;
    int length;
    uint8_t data[RELAY_DATAGRAM_BYTES];
} RELAY_SLOT;

static RELAY_SLOT relayQueue[RELAY_SLOTS];
static int relayHead;
static int relayCount;
static int relayOverflows;
static int relayClientSocket = -1;
static int relayHostSocket = -1;
static struct sockaddr_in relayClientAddr;
static bool relayHasClient;
static volatile bool relayStop;
static bool relayRunning;
static pthread_t relayThreadHandle;
static pthread_mutex_t relayLock = PTHREAD_MUTEX_INITIALIZER;
static uint32_t relayDelayUs;
static uint64_t relayLastReleaseUs;
static int relaySendFailures;

static void setRelayDelayMs(uint32_t delayMs) {
    pthread_mutex_lock(&relayLock);
    relayDelayUs = delayMs * 1000;
    pthread_mutex_unlock(&relayLock);
}

static void* relayThread(void* context) {
    static uint8_t datagram[RELAY_DATAGRAM_BYTES];
    (void)context;

    while (!relayStop) {
        struct pollfd fds[2];
        uint64_t now;

        fds[0].fd = relayClientSocket;
        fds[0].events = POLLIN;
        fds[0].revents = 0;
        fds[1].fd = relayHostSocket;
        fds[1].events = POLLIN;
        fds[1].revents = 0;
        poll(fds, 2, 1);

        // Client to host: immediately.
        for (;;) {
            struct sockaddr_in from;
            socklen_t fromLength = sizeof(from);
            ssize_t n = recvfrom(relayClientSocket, datagram, sizeof(datagram), MSG_DONTWAIT, (struct sockaddr*)&from, &fromLength);
            if (n <= 0) {
                break;
            }
            pthread_mutex_lock(&relayLock);
            relayClientAddr = from;
            relayHasClient = true;
            pthread_mutex_unlock(&relayLock);
            if (send(relayHostSocket, datagram, (size_t)n, 0) != n) {
                relaySendFailures++;
            }
        }

        // Host to client: held for the current delay, never released ahead of an earlier datagram.
        for (;;) {
            ssize_t n = recv(relayHostSocket, datagram, sizeof(datagram), MSG_DONTWAIT);
            if (n <= 0) {
                break;
            }
            now = realMonotonicUs();
            pthread_mutex_lock(&relayLock);
            if (relayCount == RELAY_SLOTS) {
                relayOverflows++;
            }
            else {
                RELAY_SLOT* slot = &relayQueue[(relayHead + relayCount) % RELAY_SLOTS];
                uint64_t release = now + relayDelayUs;
                if (release < relayLastReleaseUs) {
                    release = relayLastReleaseUs;
                }
                relayLastReleaseUs = release;
                slot->releaseUs = release;
                slot->length = (int)n;
                memcpy(slot->data, datagram, (size_t)n);
                relayCount++;
            }
            pthread_mutex_unlock(&relayLock);
        }

        now = realMonotonicUs();
        pthread_mutex_lock(&relayLock);
        while (relayCount > 0 && relayHasClient && relayQueue[relayHead].releaseUs <= now) {
            RELAY_SLOT* slot = &relayQueue[relayHead];
            if (sendto(relayClientSocket, slot->data, (size_t)slot->length, 0,
                       (struct sockaddr*)&relayClientAddr, sizeof(relayClientAddr)) != slot->length) {
                relaySendFailures++;
            }
            relayHead = (relayHead + 1) % RELAY_SLOTS;
            relayCount--;
        }
        pthread_mutex_unlock(&relayLock);
    }
    return NULL;
}

// Returns the relay's client-facing port, or 0.
static uint16_t startRelay(uint16_t hostPort, uint32_t delayUs) {
    struct sockaddr_in any = loopback(0);
    struct sockaddr_in host = loopback(hostPort);
    struct sockaddr_in bound;
    socklen_t boundLength = sizeof(bound);

    relayHead = relayCount = relayOverflows = relaySendFailures = 0;
    relayHasClient = false;
    relayLastReleaseUs = 0;
    relayDelayUs = delayUs;
    relayStop = false;
    relayClientSocket = socket(AF_INET, SOCK_DGRAM, 0);
    relayHostSocket = socket(AF_INET, SOCK_DGRAM, 0);
    if (relayClientSocket < 0 || relayHostSocket < 0 ||
            bind(relayClientSocket, (struct sockaddr*)&any, sizeof(any)) != 0 ||
            bind(relayHostSocket, (struct sockaddr*)&any, sizeof(any)) != 0 ||
            connect(relayHostSocket, (struct sockaddr*)&host, sizeof(host)) != 0 ||
            getsockname(relayClientSocket, (struct sockaddr*)&bound, &boundLength) != 0 ||
            pthread_create(&relayThreadHandle, NULL, relayThread, NULL) != 0) {
        return 0;
    }
    relayRunning = true;
    return ntohs(bound.sin_port);
}

static void stopRelay(void) {
    if (relayRunning) {
        relayStop = true;
        pthread_join(relayThreadHandle, NULL);
        relayRunning = false;
    }
    if (relayClientSocket >= 0) {
        close(relayClientSocket);
        relayClientSocket = -1;
    }
    if (relayHostSocket >= 0) {
        close(relayHostSocket);
        relayHostSocket = -1;
    }
}

// RTSP-equivalent state, then the production SDP generator. Returns whether it echoed.
static bool negotiate(const SESSION_OPTIONS* o, uint32_t* encryptionEnabled) {
    static CONNECTION_LISTENER_CALLBACKS cl;
    static DECODER_RENDERER_CALLBACKS dr;
    static AUDIO_RENDERER_CALLBACKS ar;
    PCONNECTION_LISTENER_CALLBACKS clp = &cl;
    PDECODER_RENDERER_CALLBACKS drp = &dr;
    PAUDIO_RENDERER_CALLBACKS arp = &ar;
    struct sockaddr_in remote = loopback(0);
    char* sdp;
    int sdpLength = 0;
    bool echoed;

    memset(&cl, 0, sizeof(cl));
    memset(&dr, 0, sizeof(dr));
    memset(&ar, 0, sizeof(ar));
    cl.logMessage = logMessage;
    cl.connectionTerminated = connectionTerminated;
    dr.setup = drSetup;
    dr.submitDecodeUnit = drSubmit;
    dr.capabilities = CAPABILITY_DIRECT_SUBMIT;
    fixupMissingCallbacks(&drp, &arp, &clp);
    memcpy(&ListenerCallbacks, clp, sizeof(ListenerCallbacks));
    memcpy(&VideoCallbacks, drp, sizeof(VideoCallbacks));
    memcpy(&AudioCallbacks, arp, sizeof(AudioCallbacks));

    memcpy(AppVersionQuad, o->appVersion, sizeof(AppVersionQuad));
    memset(&StreamConfig, 0, sizeof(StreamConfig));
    StreamConfig.width = 1280;
    StreamConfig.height = 720;
    StreamConfig.fps = 60;
    StreamConfig.bitrate = 10000;
    StreamConfig.packetSize = 1024;
    StreamConfig.streamingRemotely = STREAM_CFG_LOCAL;
    StreamConfig.audioConfiguration = AUDIO_CONFIGURATION_STEREO;
    StreamConfig.supportedVideoFormats = VIDEO_FORMAT_H264;
    StreamConfig.encryptionFlags = o->encryptionFlags;
    memcpy(StreamConfig.remoteInputAesKey, aesKey, sizeof(aesKey));
    NegotiatedVideoFormat = VIDEO_FORMAT_H264;
    memset(&RemoteAddr, 0, sizeof(RemoteAddr));
    memcpy(&RemoteAddr, &remote, sizeof(remote));
    AddrLen = sizeof(remote);
    memset(&LocalAddr, 0, sizeof(LocalAddr));

    // What performRtspHandshake() records from DESCRIBE and SETUP.
    PacketReceptionResetNegotiation();
    SunshineFeatureFlags = 0;
    EncryptionFeaturesSupported = o->hostEncryptionSupported;
    EncryptionFeaturesRequested = SS_ENC_CONTROL_V2;
    EncryptionFeaturesEnabled = 0;
    PacketReceptionNoteHostOffer(o->describe);
    PacketReceptionNoteControlConnectData(o->connectData);
    ControlConnectData = o->connectData ? controlConnectDataValue : 0;

    sdp = getSdpPayloadForStreamConfig(14, &sdpLength);
    CHECK(sdp != NULL, "SDP generation failed");
    echoed = sdp != NULL && strstr(sdp, "a=x-ml-video.packetReceptionFeedbackVersion:1 \r\n") != NULL;
    if (sdp != NULL) {
        CHECK(strstr(sdp, "packetReceptionFeedbackVersion") == NULL || echoed, "malformed echo");
        free(sdp);
    }
    *encryptionEnabled = EncryptionFeaturesEnabled;
    return echoed;
}

static int startSession(const SESSION_OPTIONS* o, bool* echoed, uint32_t* encryptionEnabled) {
    ENetAddress address;
    struct sockaddr_in bind4 = loopback(0);
    struct sockaddr_in bound;
    socklen_t boundLength = sizeof(bound);
    int err;

    terminationError = 0;
    fixtureStopping = false;
    ConnectionInterrupted = false;
    pthread_mutex_lock(&controlLock);
    controlMessageCount = 0;
    controlDecryptFailures = 0;
    observedConnectData = 0;
    observedConnectDeceleration = 0;
    throttleConfigureCount = 0;
    throttleConfigureCopies = 0;
    pthread_mutex_unlock(&controlLock);

    err = initializePlatform();
    if (err != 0) {
        return err;
    }

    // Host side: ENet control server and a UDP video sender, both on loopback.
    enet_address_set_address(&address, (struct sockaddr*)&bind4, sizeof(bind4));
    enet_address_set_port(&address, 0);
    server = enet_host_create(AF_INET, &address, 4, CTRL_CHANNEL_COUNT, 0, 0);
    if (server == NULL) {
        return -1;
    }
    server->intercept = interceptThrottleConfigure;
    enet_socket_get_address(server->socket, &address);
    ControlPortNumber = ntohs(((struct sockaddr_in*)&address.address)->sin_port);
    if (o->relay) {
        ControlPortNumber = startRelay(ControlPortNumber, o->relayDelayUs);
        if (ControlPortNumber == 0) {
            return -1;
        }
    }

    videoSocket = socket(AF_INET, SOCK_DGRAM, 0);
    if (videoSocket < 0 || bind(videoSocket, (struct sockaddr*)&bind4, sizeof(bind4)) != 0 ||
            getsockname(videoSocket, (struct sockaddr*)&bound, &boundLength) != 0) {
        return -1;
    }
    VideoPortNumber = ntohs(bound.sin_port);
    memset(&VideoPingPayload, 0, sizeof(VideoPingPayload));

    *echoed = negotiate(o, encryptionEnabled);
    if (o->afterNegotiate != NULL) {
        o->afterNegotiate();
    }

    serverStop = false;
    if (pthread_create(&serverThreadHandle, NULL, serverThread, NULL) != 0) {
        return -1;
    }

    err = initializeControlStream();
    if (err != 0) {
        return err;
    }
    initializeVideoStream();
    err = startControlStream();
    if (err != 0) {
        return err;
    }
    err = startVideoStream(NULL, 0);
    if (err != 0) {
        return err;
    }

    // Learn the client's video port from its first ping.
    {
        struct timeval timeout = { 2, 0 };
        uint8_t ping[64];
        socklen_t peerLength = sizeof(clientVideoAddr);
        setsockopt(videoSocket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        if (recvfrom(videoSocket, ping, sizeof(ping), 0, (struct sockaddr*)&clientVideoAddr, &peerLength) <= 0) {
            return -1;
        }
    }
    return 0;
}

static void finishStoppedSession(bool checkStoppedCollector);

static void stopSession(bool checkStoppedCollector) {
    fixtureStopping = true;
    ConnectionInterrupted = true;
    stopVideoStream();
    stopControlStream();
    finishStoppedSession(checkStoppedCollector);
}

// Everything after the stage stop functions: destroy, collector checks and fixture teardown.
static void finishStoppedSession(bool checkStoppedCollector) {
    uint8_t buffer[PACKET_RECEPTION_MAX_PAYLOAD_BYTES];

    if (checkStoppedCollector) {
        // Stop retired it before teardown: nothing pending can still be taken.
        CHECK(PacketReceptionTakeReport(buffer, sizeof(buffer)) == 0, "report after control stop");
    }
    destroyVideoStream();
    destroyControlStream();
    CHECK(!PacketReceptionIsEnabled(), "observer survived destroy");
    CHECK(PacketReceptionTakeReport(buffer, sizeof(buffer)) == 0, "report after destroy");

    serverStop = true;
    pthread_join(serverThreadHandle, NULL);
    stopRelay();
    enet_host_destroy(server);
    server = NULL;
    close(videoSocket);
    videoSocket = -1;
    cleanupPlatform();
}

// One RTP video packet as the host sends it: RTP header with extension, NV header, payload.
static void buildPlainPacket(uint8_t* p, int plainLength, uint16_t seq, uint32_t frame, int shardIndex, uint8_t flags) {
    uint32_t fecInfo = ((uint32_t)shardIndex << 12) | ((uint32_t)DATA_SHARDS << 22) | ((uint32_t)FEC_PERCENT << 4);
    int i;

    memset(p, 0, (size_t)plainLength);
    p[0] = 0x80 | FLAG_EXTENSION;
    p[1] = 0;
    p[2] = (uint8_t)(seq >> 8);
    p[3] = (uint8_t)seq;
    p[4] = (uint8_t)(frame >> 8);   // RTP timestamp, any stable value per frame
    p[11] = 0x42;                   // SSRC
    // NV header at offset 16, little-endian fields.
    p[16] = (uint8_t)shardIndex;    // streamPacketIndex
    p[20] = (uint8_t)frame; p[21] = (uint8_t)(frame >> 8); p[22] = (uint8_t)(frame >> 16); p[23] = (uint8_t)(frame >> 24);
    p[24] = flags;
    p[26] = 0x10;                   // multiFecFlags
    p[27] = 0x00;                   // block 0 of 1
    p[28] = (uint8_t)fecInfo; p[29] = (uint8_t)(fecInfo >> 8); p[30] = (uint8_t)(fecInfo >> 16); p[31] = (uint8_t)(fecInfo >> 24);
    for (i = 32; i < plainLength; i++) {
        p[i] = (uint8_t)(frame * 31 + shardIndex * 7 + i);
    }
}

typedef struct {
    uint8_t shards[DATA_SHARDS + PARITY_SHARDS][2048];
    int plainLength;
} FRAME_SHARDS;

static void buildFrame(FRAME_SHARDS* f, uint16_t baseSeq, uint32_t frame) {
    unsigned char* pointers[DATA_SHARDS + PARITY_SHARDS];
    reed_solomon* rs;
    int i;

    f->plainLength = StreamConfig.packetSize + 16;
    for (i = 0; i < DATA_SHARDS; i++) {
        uint8_t flags = FLAG_CONTAINS_PIC_DATA;
        if (i == 0) flags |= FLAG_SOF;
        if (i == DATA_SHARDS - 1) flags |= FLAG_EOF;
        buildPlainPacket(f->shards[i], f->plainLength, (uint16_t)(baseSeq + i), frame, i, flags);
    }
    for (i = 0; i < DATA_SHARDS + PARITY_SHARDS; i++) {
        pointers[i] = f->shards[i];
    }
    rs = reed_solomon_new(DATA_SHARDS, PARITY_SHARDS);
    reed_solomon_encode(rs, pointers, DATA_SHARDS + PARITY_SHARDS, f->plainLength);
    reed_solomon_release(rs);
    // Like the host, stamp the parity shards' own RTP/NV identity after encoding.
    for (i = DATA_SHARDS; i < DATA_SHARDS + PARITY_SHARDS; i++) {
        uint8_t* p = f->shards[i];
        uint32_t fecInfo = ((uint32_t)i << 12) | ((uint32_t)DATA_SHARDS << 22) | ((uint32_t)FEC_PERCENT << 4);
        uint16_t seq = (uint16_t)(baseSeq + i);
        memset(p, 0, 16);
        p[0] = 0x80 | FLAG_EXTENSION;
        p[2] = (uint8_t)(seq >> 8);
        p[3] = (uint8_t)seq;
        p[4] = (uint8_t)(frame >> 8);
        p[11] = 0x42;
        p[20] = (uint8_t)frame; p[21] = (uint8_t)(frame >> 8); p[22] = (uint8_t)(frame >> 16); p[23] = (uint8_t)(frame >> 24);
        p[27] = 0x00;
        p[28] = (uint8_t)fecInfo; p[29] = (uint8_t)(fecInfo >> 8); p[30] = (uint8_t)(fecInfo >> 16); p[31] = (uint8_t)(fecInfo >> 24);
    }
}

static PPLT_CRYPTO_CONTEXT senderCrypto;
static uint32_t ivCounter;
static int datagramsSent;
static bool videoEncrypted;

// Send one shard as the host would: AES-GCM with the 32-byte prefix when video encryption was
// negotiated, otherwise plaintext. headerFrame is the pre-decrypt frame hint (0 disables it).
static void sendShard(const FRAME_SHARDS* f, int shard, uint32_t headerFrame, bool corruptTag) {
    uint8_t datagram[2048 + 32];
    int cipherLength = f->plainLength;
    uint8_t* iv = datagram;
    uint8_t* tag = datagram + 16;

    if (!videoEncrypted) {
        sendto(videoSocket, f->shards[shard], (size_t)f->plainLength, 0, (struct sockaddr*)&clientVideoAddr, sizeof(clientVideoAddr));
        datagramsSent++;
        sleepMs(2);
        return;
    }

    memset(datagram, 0, 32);
    ivCounter++;
    memcpy(iv, &ivCounter, sizeof(ivCounter));
    datagram[12] = (uint8_t)headerFrame; datagram[13] = (uint8_t)(headerFrame >> 8);
    datagram[14] = (uint8_t)(headerFrame >> 16); datagram[15] = (uint8_t)(headerFrame >> 24);
    PltEncryptMessage(senderCrypto, ALGORITHM_AES_GCM, 0, (unsigned char*)aesKey, 16, iv, 12, tag, 16,
                      (unsigned char*)f->shards[shard], f->plainLength, datagram + 32, &cipherLength);
    if (corruptTag) {
        tag[0] ^= 0xFF;
    }
    sendto(videoSocket, datagram, (size_t)(32 + cipherLength), 0, (struct sockaddr*)&clientVideoAddr, sizeof(clientVideoAddr));
    datagramsSent++;
    sleepMs(2);
}

static int collectReports(PACKET_RECEPTION_REPORT* reports, uint64_t* arrivals, int max, int* pings, int* fecStatuses,
                          uint8_t fecStatus[][32], int* badTransport) {
    int i, n = 0;
    const char* why;

    *pings = 0;
    *fecStatuses = 0;
    *badTransport = 0;
    pthread_mutex_lock(&controlLock);
    for (i = 0; i < controlMessageCount; i++) {
        CONTROL_MESSAGE* m = &controlMessages[i];
        if (m->type == 0x0200) {
            (*pings)++;
        }
        else if (m->type == SS_FRAME_FEC_PTYPE && *fecStatuses < 16) {
            memcpy(fecStatus[(*fecStatuses)++], m->payload, m->length < 32 ? m->length : 32);
        }
        else if (m->type == PACKET_RECEPTION_PTYPE && n < max) {
            if (m->channel != CTRL_CHANNEL_GENERIC || !(m->enetFlags & ENET_PACKET_FLAG_UNSEQUENCED) ||
                    (m->enetFlags & ENET_PACKET_FLAG_RELIABLE) || m->length > PACKET_RECEPTION_MAX_PAYLOAD_BYTES) {
                (*badTransport)++;
            }
            if (decodeReport(m->payload, m->length, &reports[n], &why) != 0) {
                CHECK(false, "report %d refused by host decoder: %s", n, why);
            }
            arrivals[n] = m->receivedUs;
            n++;
        }
    }
    pthread_mutex_unlock(&controlLock);
    return n;
}

typedef struct { uint32_t frame; uint16_t seq; bool parity; } ACCEPTED;

static bool isAccepted(const ACCEPTED* set, int n, uint32_t frame, uint16_t seq) {
    int i;
    for (i = 0; i < n; i++) {
        if (set[i].frame == frame && set[i].seq == seq) return true;
    }
    return false;
}

static void sessionPaths(bool encrypted) {
    SESSION_OPTIONS o = defaultOptions();
    FRAME_SHARDS f;
    PACKET_RECEPTION_REPORT reports[256];
    uint64_t arrivals[256];
    uint8_t fec[16][32];
    ACCEPTED accepted[32];
    int acceptedCount = 0;
    int pings, fecCount, badTransport, n, i, j;
    uint64_t data = 0, parity = 0, dataBytes = 0, parityBytes = 0;
    uint32_t encryptionEnabled = 0;
    bool echoed = false;
    bool sawZeroMediaWithProgress = false;
    bool sawReconstructionStatus = false;
    uint64_t minGap = UINT64_MAX;
    uint32_t minInterval = UINT32_MAX;
    int encryptedLength;

    beginCase(encrypted ? "session_encrypted_video_accepted_original_paths" : "session_plaintext_video_accepted_original_paths");
    if (!encrypted) {
        // The ordinary LAN default: the host neither offers nor requests video encryption.
        o.encryptionFlags = ENCFLG_NONE;
        o.hostEncryptionSupported = SS_ENC_CONTROL_V2 | SS_ENC_AUDIO;
    }
    videoEncrypted = encrypted;
    senderCrypto = PltCreateCryptoContext();
    datagramsSent = 0;
    if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
        CHECK(false, "session start failed");
        endCase();
        return;
    }
    CHECK(echoed, "client did not echo version 1");
    CHECK(encryptionEnabled == (SS_ENC_CONTROL_V2 | (encrypted ? SS_ENC_VIDEO : 0)), "encryption changed: %u", encryptionEnabled);
    CHECK(PacketReceptionIsEnabled(), "observer not started");
    encryptedLength = (encrypted ? 32 : 0) + StreamConfig.packetSize + 16;

    // No media at first: reports keep coming and the receive loop keeps polling. Long enough for
    // several ticks even on a loaded runner.
    sleepMs(450);

    // Frame 1, seq 0..5: all data; late parity then hits the pre-decrypt old-frame filter.
    buildFrame(&f, 0, 1);
    for (i = 0; i < DATA_SHARDS; i++) {
        sendShard(&f, i, 1, false);
        accepted[acceptedCount++] = (ACCEPTED){ 1, (uint16_t)i, false };
    }
    sendShard(&f, 4, 1, false);
    sendShard(&f, 5, 1, false);

    // Frame 2, seq 6..11: data 2 lost, parity 0 reconstructs it. Its late original and the
    // second parity arrive after completion.
    buildFrame(&f, 6, 2);
    sendShard(&f, 0, 2, false); accepted[acceptedCount++] = (ACCEPTED){ 2, 6, false };
    sendShard(&f, 1, 2, false); accepted[acceptedCount++] = (ACCEPTED){ 2, 7, false };
    sendShard(&f, 3, 2, false); accepted[acceptedCount++] = (ACCEPTED){ 2, 9, false };
    sendShard(&f, 4, 2, false); accepted[acceptedCount++] = (ACCEPTED){ 2, 10, true };
    sendShard(&f, 5, 2, false);
    sendShard(&f, 2, 2, false);

    // Frame 3, seq 12..17: a duplicate behind the window, then reordering so a second
    // duplicate reaches queuePacket()'s own duplicate scan.
    buildFrame(&f, 12, 3);
    sendShard(&f, 0, 3, false);
    sendShard(&f, 0, 3, false);
    sendShard(&f, 2, 3, false);
    sendShard(&f, 2, 3, false);
    sendShard(&f, 1, 3, false);
    sendShard(&f, 3, 3, false);
    for (i = 0; i < DATA_SHARDS; i++) {
        accepted[acceptedCount++] = (ACCEPTED){ 3, (uint16_t)(12 + i), false };
    }

    // A runt, and with encryption a packet that fails AES-GCM authentication.
    {
        uint8_t runt[8] = { 0 };
        sendto(videoSocket, runt, sizeof(runt), 0, (struct sockaddr*)&clientVideoAddr, sizeof(clientVideoAddr));
        datagramsSent++;
    }
    buildFrame(&f, 18, 4);
    if (encrypted) {
        sendShard(&f, 0, 4, true);
    }

    // Frame 4, seq 18..23.
    for (i = 0; i < DATA_SHARDS; i++) {
        sendShard(&f, i, 4, false);
        accepted[acceptedCount++] = (ACCEPTED){ 4, (uint16_t)(18 + i), false };
    }

    // An old packet without the pre-decrypt hint (or plaintext): it reaches the queue and is rejected.
    buildFrame(&f, 12, 3);
    sendShard(&f, 1, 0, false);

    // Frame 5, seq 24..29, plus a packet above the FEC block's sequence range.
    buildFrame(&f, 24, 5);
    sendShard(&f, 0, 5, false); accepted[acceptedCount++] = (ACCEPTED){ 5, 24, false };
    {
        FRAME_SHARDS outside;
        buildFrame(&outside, 31, 5);
        sendShard(&outside, 0, 5, false);
    }
    for (i = 1; i < DATA_SHARDS; i++) {
        sendShard(&f, i, 5, false);
        accepted[acceptedCount++] = (ACCEPTED){ 5, (uint16_t)(24 + i), false };
    }

    sleepMs(400);
    stopSession(true);
    PltDestroyCryptoContext(senderCrypto);

    n = collectReports(reports, arrivals, 256, &pings, &fecCount, fec, &badTransport);
    CHECK(n >= 5, "only %d reports", n);
    CHECK(pings >= n, "pings %d reports %d", pings, n);
    CHECK(badTransport == 0, "%d reports not unsequenced on the generic channel", badTransport);
    CHECK(controlDecryptFailures == 0, "%d control messages failed to decrypt", controlDecryptFailures);
    CHECK(observedConnectData == controlConnectDataValue, "connect data %x", observedConnectData);
    CHECK(terminationError == 0, "connection terminated %d", terminationError);
    for (i = 0; i < n; i++) {
        CHECK(reports[i].reportSequence == (uint32_t)(i + 1), "sequence %u at %d", reports[i].reportSequence, i);
        CHECK(!(reports[i].flags & (PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE | PACKET_RECEPTION_FLAG_INCOMPLETE)), "flags %x", reports[i].flags);
        if (i > 0) {
            CHECK(reports[i].intervalEndUs > reports[i - 1].intervalEndUs, "end did not advance");
            CHECK(reports[i].intervalEndUs - reports[i - 1].intervalEndUs == reports[i].intervalUs, "interval chain");
            CHECK((uint32_t)(reports[i].receiveLoopTick - reports[i - 1].receiveLoopTick) < 1000, "tick serial");
            if (arrivals[i] - arrivals[i - 1] < minGap) minGap = arrivals[i] - arrivals[i - 1];
            if (reports[i].intervalUs < minInterval) minInterval = reports[i].intervalUs;
        }
        if (reports[i].acceptedDataPackets == 0 && reports[i].acceptedParityPackets == 0 && i > 0 &&
                reports[i].receiveLoopTick != reports[i - 1].receiveLoopTick) {
            sawZeroMediaWithProgress = true;
        }
        data += reports[i].acceptedDataPackets;
        parity += reports[i].acceptedParityPackets;
        dataBytes += reports[i].acceptedDataUdpBytes;
        parityBytes += reports[i].acceptedParityUdpBytes;
        for (j = 0; j < reports[i].sampleCount; j++) {
            const PACKET_RECEPTION_REPORT_SAMPLE* s = &reports[i].samples[j];
            CHECK(isAccepted(accepted, acceptedCount, s->frameIndex, s->rtpSequence),
                  "sample of a packet that was not an accepted original: frame %u seq %u", s->frameIndex, s->rtpSequence);
            CHECK(s->frameIndex >= reports[i].firstFrame && s->frameIndex <= reports[i].lastFrame, "sample outside frame range");
        }
    }
    printf("SESSION reports=%d pings=%d data=%llu parity=%llu datagrams=%d tick=%u min_interval_us=%u min_arrival_gap_us=%llu\n",
           n, pings, (unsigned long long)data, (unsigned long long)parity, datagramsSent,
           n > 0 ? reports[n - 1].receiveLoopTick : 0, minInterval, (unsigned long long)minGap);
    CHECK(data == 19 && parity == 1, "accepted originals data %llu parity %llu, expected 19 and 1",
          (unsigned long long)data, (unsigned long long)parity);
    CHECK(dataBytes == 19ULL * (uint64_t)encryptedLength && parityBytes == (uint64_t)encryptedLength, "UDP bytes %llu %llu",
          (unsigned long long)dataBytes, (unsigned long long)parityBytes);
    CHECK(n > 0 && reports[n - 1].receiveLoopTick >= (uint32_t)datagramsSent, "tick %u < datagrams %d",
          n > 0 ? reports[n - 1].receiveLoopTick : 0, datagramsSent);
    CHECK(sawZeroMediaWithProgress, "no zero-media report with receive-loop progress");
    // Each report after the first covers at least one 100 ms control sleep: one per tick, no burst.
    CHECK(minInterval >= 90000, "report interval %u us: more than one report per tick", minInterval);
    for (i = 0; i < fecCount; i++) {
        uint32_t frame = be32(fec[i]);
        uint16_t receivedData = (uint16_t)((fec[i][14] << 8) | fec[i][15]);
        uint16_t receivedParity = (uint16_t)((fec[i][16] << 8) | fec[i][17]);
        if (frame == 2 && receivedData == 3 && receivedParity == 1) {
            sawReconstructionStatus = true;
        }
    }
    CHECK(sawReconstructionStatus, "frame 2 was not reconstructed through FEC (%d FEC statuses)", fecCount);
    endCase();
}

static void sessionReconnect(void) {
    SESSION_OPTIONS o = defaultOptions();
    FRAME_SHARDS f;
    PACKET_RECEPTION_REPORT reports[64];
    uint64_t arrivals[64];
    uint8_t fec[16][32];
    int pings, fecCount, badTransport, n, i, round;
    uint32_t encryptionEnabled;
    bool echoed;

    beginCase("session_reconnect_fresh_epoch");
    videoEncrypted = true;
    for (round = 0; round < 2; round++) {
        uint64_t data = 0;
        senderCrypto = PltCreateCryptoContext();
        if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
            CHECK(false, "start %d failed", round);
            break;
        }
        sleepMs(120);
        buildFrame(&f, 0, 1);
        for (i = 0; i < (round == 0 ? DATA_SHARDS : 2); i++) {
            sendShard(&f, i, 1, false);
        }
        sleepMs(250);
        stopSession(true);
        PltDestroyCryptoContext(senderCrypto);
        n = collectReports(reports, arrivals, 64, &pings, &fecCount, fec, &badTransport);
        CHECK(n >= 2 && reports[0].reportSequence == 1, "round %d first sequence %u", round, n ? reports[0].reportSequence : 0);
        CHECK(n >= 1 && reports[0].intervalEndUs < 1000000, "round %d origin not session-relative", round);
        for (i = 0; i < n; i++) {
            data += reports[i].acceptedDataPackets;
        }
        CHECK(data == (round == 0 ? 4u : 2u), "round %d data %llu", round, (unsigned long long)data);
    }
    endCase();
}

static int countMessagesAfter(uint16_t type, uint64_t afterUs) {
    int i, count = 0;
    pthread_mutex_lock(&controlLock);
    for (i = 0; i < controlMessageCount; i++) {
        if (controlMessages[i].type == type && controlMessages[i].receivedUs > afterUs) {
            count++;
        }
    }
    pthread_mutex_unlock(&controlLock);
    return count;
}

static void sessionClock(bool regression) {
    SESSION_OPTIONS o = defaultOptions();
    PACKET_RECEPTION_REPORT reports[64];
    uint64_t arrivals[64];
    uint8_t fec[16][32];
    int pings, fecCount, badTransport, n, i, sentinels = 0, pingsAfterSentinel = 0;
    uint64_t sentinelArrival = 0;
    uint32_t encryptionEnabled;
    bool echoed;

    beginCase(regression ? "session_clock_regression_sentinel" : "session_clock_failure_sentinel");
    clockBroken = false;
    clockRegressed = false;
    PacketReceptionSetTestClock(fixtureClock);
    senderCrypto = PltCreateCryptoContext();
    if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
        CHECK(false, "start failed");
        endCase();
        return;
    }
    sleepMs(250);
    if (regression) clockRegressed = true; else clockBroken = true;
    sleepMs(700);
    n = collectReports(reports, arrivals, 64, &pings, &fecCount, fec, &badTransport);
    for (i = 0; i < n; i++) {
        if (reports[i].flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE) {
            sentinelArrival = arrivals[i];
        }
    }
    if (sentinelArrival != 0) {
        pingsAfterSentinel = countMessagesAfter(0x0200, sentinelArrival);
    }
    stopSession(true);
    PltDestroyCryptoContext(senderCrypto);
    PacketReceptionSetTestClock(NULL);
    clockBroken = false;
    clockRegressed = false;

    for (i = 0; i < n; i++) {
        CHECK(reports[i].reportSequence == (uint32_t)(i + 1), "sequence %u", reports[i].reportSequence);
        if (reports[i].flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE) {
            sentinels++;
            CHECK(i == n - 1, "report after the clock sentinel");
            CHECK(reports[i].intervalUs == 0 && reports[i].intervalEndUs == 0 && reports[i].sampleCount == 0, "sentinel fields");
        }
    }
    CHECK(n >= 2, "reports %d", n);
    CHECK(sentinels == 1, "sentinels %d", sentinels);
    // The stream and its periodic ping carry on; only the observation stopped.
    CHECK(pingsAfterSentinel >= 2, "pings after sentinel %d", pingsAfterSentinel);
    CHECK(badTransport == 0, "transport");
    CHECK(terminationError == 0, "stream terminated %d", terminationError);
    endCase();
}

static int takeDecodedReport(PACKET_RECEPTION_REPORT* report) {
    uint8_t wire[PACKET_RECEPTION_MAX_PAYLOAD_BYTES];
    const char* why = NULL;
    int length = PacketReceptionTakeReport(wire, sizeof(wire));

    memset(report, 0, sizeof(*report));
    if (length > 0) {
        CHECK(decodeReport(wire, (size_t)length, report, &why) == 0, "report refused by host decoder: %s", why ? why : "?");
    }
    return length;
}

// Production negotiation (SDP generator, real clock probe), then the scripted clock from the
// collector origin on. No threads: the test thread calls the receive, commit and report entrypoints.
static bool startDirectLifecycle(const CLOCK_STEP* steps, int count) {
    SESSION_OPTIONS o = defaultOptions();
    uint32_t encryptionEnabled = 0;

    PacketReceptionSetTestClock(NULL);
    if (!negotiate(&o, &encryptionEnabled)) {
        return false;
    }
    setClockScript(steps, count);
    PacketReceptionSetTestClock(scriptedClock);
    PacketReceptionStart(true);
    return PacketReceptionIsEnabled();
}

static void endDirectLifecycle(int expectedClockReads) {
    CHECK(clockScriptIndex == expectedClockReads && !clockScriptOverrun, "clock reads %d of %d, overrun %d",
          clockScriptIndex, expectedClockReads, clockScriptOverrun);
    PacketReceptionStop();
    PacketReceptionDestroy();
    PacketReceptionSetTestClock(NULL);
}

static PACKET_RECEPTION_REPORT nestedReport;
static int nestedReportLength;

static void snapshotBetweenReadAndLock(void) {
    nestedReportLength = takeDecodedReport(&nestedReport);
}

static void clockReviewLifecycle(void) {
    const uint64_t t0 = 5000000;
    PACKET_RECEPTION_CAPTURE first, second;
    PACKET_RECEPTION_REPORT r;

    beginCase("lifecycle_rejected_read_clock_failure_sentinel");
    {
        const CLOCK_STEP steps[] = {
            { true, t0, NULL },                 // collector origin
            { false, 0, NULL },                 // socket read of a packet that is never accepted
            { true, t0 + 100000, NULL },        // clock recovered for the report
            { true, t0 + 200000, NULL },        // the tick after the sentinel
        };
        CHECK(startDirectLifecycle(steps, 4), "observer did not start");
        memset(&first, 0, sizeof(first));
        PacketReceptionNoteReceive(1040, &first);
        CHECK(!first.clockValid, "fixture clock did not fail the read");
        CHECK(takeDecodedReport(&r) > 0, "no report");
        CHECK((r.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE) && (r.flags & PACKET_RECEPTION_FLAG_INCOMPLETE),
              "observed receive clock failure was forgotten: flags %x interval %u", r.flags, r.intervalUs);
        CHECK(r.reportSequence == 1 && r.intervalUs == 0 && r.intervalEndUs == 0 && r.sampleCount == 0 &&
              r.lastAcceptedAgeUs == UINT32_MAX, "sentinel fields");
        CHECK(!(r.flags & PACKET_RECEPTION_FLAG_HAS_ACCEPTED) && r.acceptedDataPackets == 0 && r.acceptedParityPackets == 0 &&
              r.acceptedDataUdpBytes == 0 && r.firstFrame == 0 && r.lastFrame == 0, "a never-accepted packet was counted");
        CHECK(r.receiveLoopTick == 1, "tick %u", r.receiveLoopTick);
        CHECK(takeDecodedReport(&r) == 0, "report after the sentinel");
        endDirectLifecycle(4);
    }
    endCase();

    beginCase("lifecycle_receiver_backward_reads_sentinel");
    {
        const CLOCK_STEP rejectedOnly[] = {
            { true, t0, NULL },
            { true, t0 + 40000, NULL },         // rejected read
            { true, t0 + 39000, NULL },         // later rejected read, earlier time
            { true, t0 + 100000, NULL },
            { true, t0 + 200000, NULL },
        };
        const CLOCK_STEP afterCommit[] = {
            { true, t0, NULL },
            { true, t0 + 10, NULL },            // read, then accepted
            { true, t0 + 20, NULL },            // its commit on the same receive thread
            { true, t0 + 15, NULL },            // next read is earlier than that commit, then rejected
            { true, t0 + 100000, NULL },
        };
        CHECK(startDirectLifecycle(rejectedOnly, 5), "observer did not start");
        memset(&first, 0, sizeof(first));
        memset(&second, 0, sizeof(second));
        PacketReceptionNoteReceive(1040, &first);
        PacketReceptionNoteReceive(1040, &second);
        CHECK(takeDecodedReport(&r) > 0 && (r.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE),
              "backward receive read was forgotten: flags %x", r.flags);
        CHECK(r.acceptedDataPackets == 0 && r.receiveLoopTick == 2, "counts %u tick %u", r.acceptedDataPackets, r.receiveLoopTick);
        CHECK(takeDecodedReport(&r) == 0, "report after the sentinel");
        endDirectLifecycle(5);

        CHECK(startDirectLifecycle(afterCommit, 5), "observer did not start");
        memset(&first, 0, sizeof(first));
        memset(&second, 0, sizeof(second));
        PacketReceptionNoteReceive(1040, &first);
        PacketReceptionCommit(&first, 3, 100, false);
        PacketReceptionNoteReceive(1040, &second);
        CHECK(takeDecodedReport(&r) > 0 && (r.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE),
              "read earlier than a receive-thread commit was forgotten: flags %x", r.flags);
        CHECK((r.flags & PACKET_RECEPTION_FLAG_HAS_ACCEPTED) && r.acceptedDataPackets == 1 && r.firstFrame == 3,
              "accepted count before the fault must stay an incomplete fact");
        endDirectLifecycle(5);
    }
    endCase();

    beginCase("lifecycle_delayed_capture_before_snapshot_keeps_read_time");
    {
        // Read at t0+50 ms; the control tick snapshots at t0+100 ms before the receive path gets
        // the mutex; the packet is accepted at t0+130 ms and reported at t0+200 ms.
        const CLOCK_STEP steps[] = {
            { true, t0, NULL },
            { true, t0 + 50000, snapshotBetweenReadAndLock },
            { true, t0 + 100000, NULL },
            { true, t0 + 130000, NULL },
            { true, t0 + 200000, NULL },
        };
        nestedReportLength = 0;
        CHECK(startDirectLifecycle(steps, 5), "observer did not start");
        memset(&first, 0, sizeof(first));
        PacketReceptionNoteReceive(1040, &first);
        CHECK(first.clockValid && first.socketReadUs == t0 + 50000, "socket read time %llu", (unsigned long long)first.socketReadUs);
        CHECK(nestedReportLength > 0 && nestedReport.reportSequence == 1 && nestedReport.flags == 0 &&
              nestedReport.intervalUs == 100000 && nestedReport.receiveLoopTick == 0, "interleaved snapshot");
        PacketReceptionCommit(&first, 7, 42, false);
        CHECK(takeDecodedReport(&r) > 0, "no report");
        CHECK(r.flags == PACKET_RECEPTION_FLAG_HAS_ACCEPTED, "false clock fault for a read taken before the snapshot: flags %x", r.flags);
        CHECK(r.reportSequence == 2 && r.intervalUs == 100000 && r.intervalEndUs == 200000 && r.receiveLoopTick == 1, "header");
        CHECK(r.acceptedDataPackets == 1 && r.sampleCount == 1 && r.samples[0].frameIndex == 7 && r.samples[0].rtpSequence == 42,
              "sample identity");
        CHECK(r.samples[0].socketReadAgeUs == 150000 && r.samples[0].socketReadAgeUs > r.intervalUs,
              "earlier socket-read time not preserved: age %u", r.samples[0].socketReadAgeUs);
        endDirectLifecycle(5);
    }
    endCase();

    beginCase("lifecycle_snapshot_earlier_than_completed_rejected_read_sentinel");
    {
        // The rejected read at t0+200 ms completed under the mutex before the next snapshot read
        // its clock, so that snapshot at t0+150 ms is a definite regression even though the
        // last snapshot (or accepted commit) was earlier still.
        const CLOCK_STEP snapshotFloor[] = {
            { true, t0, NULL },
            { true, t0 + 100000, NULL },        // normal snapshot
            { true, t0 + 200000, NULL },        // positive read of a packet that is never accepted
            { true, t0 + 150000, NULL },        // next snapshot reads earlier than that read
            { true, t0 + 250000, NULL },
        };
        const CLOCK_STEP commitFloor[] = {
            { true, t0, NULL },
            { true, t0 + 10, NULL },            // read, then accepted
            { true, t0 + 120000, NULL },        // its commit
            { true, t0 + 200000, NULL },        // rejected read
            { true, t0 + 150000, NULL },        // snapshot earlier than the rejected read
        };
        CHECK(startDirectLifecycle(snapshotFloor, 5), "observer did not start");
        CHECK(takeDecodedReport(&r) > 0 && r.flags == 0 && r.reportSequence == 1, "first snapshot");
        memset(&first, 0, sizeof(first));
        PacketReceptionNoteReceive(1040, &first);
        CHECK(first.clockValid && first.socketReadUs == t0 + 200000, "socket read");
        CHECK(takeDecodedReport(&r) > 0, "no report");
        CHECK((r.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE) && (r.flags & PACKET_RECEPTION_FLAG_INCOMPLETE),
              "snapshot earlier than a completed receive read was accepted: flags %x interval %u", r.flags, r.intervalUs);
        CHECK(r.reportSequence == 2 && r.intervalUs == 0 && r.intervalEndUs == 0 && r.sampleCount == 0 &&
              r.lastAcceptedAgeUs == UINT32_MAX, "sentinel fields");
        CHECK(!(r.flags & PACKET_RECEPTION_FLAG_HAS_ACCEPTED) && r.acceptedDataPackets == 0 && r.receiveLoopTick == 1,
              "rejected packet counted or tick lost");
        CHECK(takeDecodedReport(&r) == 0, "report after the sentinel");
        endDirectLifecycle(5);

        CHECK(startDirectLifecycle(commitFloor, 5), "observer did not start");
        memset(&first, 0, sizeof(first));
        memset(&second, 0, sizeof(second));
        PacketReceptionNoteReceive(1040, &first);
        PacketReceptionCommit(&first, 4, 9, false);
        PacketReceptionNoteReceive(1040, &second);
        CHECK(takeDecodedReport(&r) > 0 && (r.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE),
              "snapshot earlier than a rejected read after a commit was accepted: flags %x", r.flags);
        CHECK((r.flags & PACKET_RECEPTION_FLAG_HAS_ACCEPTED) && r.acceptedDataPackets == 1 && r.firstFrame == 4,
              "accepted count before the fault must stay an incomplete fact");
        endDirectLifecycle(5);
    }
    endCase();

    beginCase("lifecycle_delayed_capture_keeps_higher_snapshot_floor");
    {
        // A read taken at t0+50 ms reaches the mutex after the t0+100 ms snapshot. It must not
        // lower that floor: a following snapshot at t0+75 ms is still a regression.
        const CLOCK_STEP steps[] = {
            { true, t0, NULL },
            { true, t0 + 50000, snapshotBetweenReadAndLock },
            { true, t0 + 100000, NULL },
            { true, t0 + 75000, NULL },
            { true, t0 + 300000, NULL },
        };
        nestedReportLength = 0;
        CHECK(startDirectLifecycle(steps, 5), "observer did not start");
        memset(&first, 0, sizeof(first));
        PacketReceptionNoteReceive(1040, &first);
        CHECK(nestedReportLength > 0 && nestedReport.flags == 0 && nestedReport.reportSequence == 1, "interleaved snapshot");
        CHECK(takeDecodedReport(&r) > 0 && (r.flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE) && r.reportSequence == 2,
              "delayed capture lowered the snapshot floor: flags %x", r.flags);
        CHECK(takeDecodedReport(&r) == 0, "report after the sentinel");
        endDirectLifecycle(5);
    }
    endCase();
}

static void sessionSnapshotEarlierThanReceiveRead(void) {
    SESSION_OPTIONS o = defaultOptions();
    PACKET_RECEPTION_REPORT reports[64];
    uint64_t arrivals[64];
    uint8_t fec[16][32];
    uint8_t runt[8] = { 0 };
    int pings, fecCount, badTransport, n, i, sentinels = 0, pingsAfterSentinel = 0, served;
    uint64_t sentinelArrival = 0, accepted = 0;
    uint32_t encryptionEnabled;
    bool echoed, strict;

    beginCase("session_snapshot_earlier_than_rejected_receive_read");
    pthread_mutex_lock(&fixtureClockLock);
    fixtureControlThreadKnown = false;
    receiverFailNext = false;
    receiverBackwardNext = false;
    receiverArmBetween = false;
    controlBetweenPending = false;
    controlBetweenStrict = false;
    receiverFaultsServed = 0;
    lastReceiverReadUs = 0;
    lastControlReadUs = 0;
    pthread_mutex_unlock(&fixtureClockLock);
    PacketReceptionSetTestClock(receiverFaultClock);
    videoEncrypted = true;
    senderCrypto = PltCreateCryptoContext();
    if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
        CHECK(false, "start failed");
        endCase();
        return;
    }
    sleepMs(250);
    pthread_mutex_lock(&fixtureClockLock);
    receiverArmBetween = true;
    pthread_mutex_unlock(&fixtureClockLock);
    sendto(videoSocket, runt, sizeof(runt), 0, (struct sockaddr*)&clientVideoAddr, sizeof(clientVideoAddr));
    sleepMs(450);
    n = collectReports(reports, arrivals, 64, &pings, &fecCount, fec, &badTransport);
    for (i = 0; i < n; i++) {
        accepted += reports[i].acceptedDataPackets + reports[i].acceptedParityPackets;
        CHECK(reports[i].reportSequence == (uint32_t)(i + 1), "sequence %u", reports[i].reportSequence);
        if (reports[i].flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE) {
            sentinels++;
            sentinelArrival = arrivals[i];
            CHECK(i == n - 1, "report after the clock sentinel");
        }
    }
    if (sentinelArrival != 0) {
        pingsAfterSentinel = countMessagesAfter(0x0200, sentinelArrival);
    }
    stopSession(true);
    PltDestroyCryptoContext(senderCrypto);
    PacketReceptionSetTestClock(NULL);
    pthread_mutex_lock(&fixtureClockLock);
    served = receiverFaultsServed;
    strict = controlBetweenStrict;
    pthread_mutex_unlock(&fixtureClockLock);

    CHECK(served == 1 && strict, "control read between the last snapshot and the receive read served %d strict %d", served, strict);
    CHECK(n >= 3, "reports %d", n);
    CHECK(sentinels == 1, "snapshot earlier than a completed receive read produced %d sentinels", sentinels);
    CHECK(accepted == 0, "a rejected packet was counted");
    CHECK(pingsAfterSentinel >= 2, "pings after sentinel %d", pingsAfterSentinel);
    CHECK(badTransport == 0 && terminationError == 0, "transport %d termination %d", badTransport, terminationError);
    endCase();
}

static void sessionReceiverClockFault(bool backward) {
    SESSION_OPTIONS o = defaultOptions();
    FRAME_SHARDS f;
    PACKET_RECEPTION_REPORT reports[64];
    uint64_t arrivals[64];
    uint8_t fec[16][32];
    int pings, fecCount, badTransport, n, i, sentinels = 0, pingsAfterSentinel = 0, served;
    uint64_t sentinelArrival = 0, accepted = 0;
    uint32_t encryptionEnabled;
    bool echoed;

    beginCase(backward ? "session_receiver_backward_read_on_rejected_runts" : "session_receiver_clock_failure_on_gcm_rejected_packet");
    pthread_mutex_lock(&fixtureClockLock);
    fixtureControlThreadKnown = false;
    receiverFailNext = false;
    receiverBackwardNext = false;
    receiverFaultsServed = 0;
    lastReceiverReadUs = 0;
    pthread_mutex_unlock(&fixtureClockLock);
    PacketReceptionSetTestClock(receiverFaultClock);
    videoEncrypted = true;
    senderCrypto = PltCreateCryptoContext();
    if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
        CHECK(false, "start failed");
        endCase();
        return;
    }
    sleepMs(250);
    if (backward) {
        uint8_t runt[8] = { 0 };
        sendto(videoSocket, runt, sizeof(runt), 0, (struct sockaddr*)&clientVideoAddr, sizeof(clientVideoAddr));
        sleepMs(20);
        pthread_mutex_lock(&fixtureClockLock);
        receiverBackwardNext = true;
        pthread_mutex_unlock(&fixtureClockLock);
        sendto(videoSocket, runt, sizeof(runt), 0, (struct sockaddr*)&clientVideoAddr, sizeof(clientVideoAddr));
    }
    else {
        // Full-size and positive, but it fails AES-GCM, so the queue never sees it.
        buildFrame(&f, 0, 1);
        pthread_mutex_lock(&fixtureClockLock);
        receiverFailNext = true;
        pthread_mutex_unlock(&fixtureClockLock);
        sendShard(&f, 0, 1, true);
    }
    sleepMs(450);
    n = collectReports(reports, arrivals, 64, &pings, &fecCount, fec, &badTransport);
    for (i = 0; i < n; i++) {
        accepted += reports[i].acceptedDataPackets + reports[i].acceptedParityPackets;
        CHECK(reports[i].reportSequence == (uint32_t)(i + 1), "sequence %u", reports[i].reportSequence);
        if (reports[i].flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE) {
            sentinels++;
            sentinelArrival = arrivals[i];
            CHECK(i == n - 1, "report after the clock sentinel");
        }
    }
    if (sentinelArrival != 0) {
        pingsAfterSentinel = countMessagesAfter(0x0200, sentinelArrival);
    }
    stopSession(true);
    PltDestroyCryptoContext(senderCrypto);
    PacketReceptionSetTestClock(NULL);
    pthread_mutex_lock(&fixtureClockLock);
    served = receiverFaultsServed;
    pthread_mutex_unlock(&fixtureClockLock);

    CHECK(served == 1, "receive-thread clock fault served %d times", served);
    CHECK(n >= 3, "reports %d", n);
    CHECK(sentinels == 1, "observed receive-thread clock fault produced %d sentinels", sentinels);
    CHECK(accepted == 0, "a rejected packet was counted");
    CHECK(pingsAfterSentinel >= 2, "pings after sentinel %d", pingsAfterSentinel);
    CHECK(badTransport == 0 && terminationError == 0, "transport %d termination %d", badTransport, terminationError);
    endCase();
}

// Publication barrier: parks the control thread after its snapshot left the collector mutex and
// before the control stream enqueues it, then waits up to 500 ms for stop to retire the
// collector. Stop must win: retire while the report is held, and that report must not be sent.
static pthread_mutex_t publicationLock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t publicationCond = PTHREAD_COND_INITIALIZER;
static bool publicationArmed;
static bool publicationParked;
static bool publicationReleased;
static bool publicationSawRetired;
static int publicationWaitedMs;
static uint32_t parkedSequence;

static void publicationBarrier(const uint8_t* report, int length) {
    int waited = 0;
    bool retired;

    pthread_mutex_lock(&publicationLock);
    if (!publicationArmed || length < PACKET_RECEPTION_HEADER_BYTES) {
        pthread_mutex_unlock(&publicationLock);
        return;
    }
    publicationArmed = false;
    parkedSequence = be32(report + 4);
    publicationParked = true;
    pthread_cond_broadcast(&publicationCond);
    pthread_mutex_unlock(&publicationLock);

    while (waited < 500 && !PacketReceptionTestIsRetired()) {
        sleepMs(1);
        waited++;
    }
    retired = PacketReceptionTestIsRetired();

    pthread_mutex_lock(&publicationLock);
    publicationSawRetired = retired;
    publicationWaitedMs = waited;
    publicationReleased = true;
    pthread_mutex_unlock(&publicationLock);
}

static void* stopStagesThread(void* context) {
    volatile bool* done = (volatile bool*)context;
    stopVideoStream();
    stopControlStream();
    *done = true;
    return NULL;
}

static void sessionStopPublicationFence(void) {
    SESSION_OPTIONS o = defaultOptions();
    PACKET_RECEPTION_REPORT reports[64];
    uint64_t arrivals[64];
    uint8_t fec[16][32];
    int pings, fecCount, badTransport, n, i;
    uint32_t encryptionEnabled;
    bool echoed, parked, released, sawRetired, parkedArrived = false;
    volatile bool stopDone = false;
    pthread_t stopper;
    uint64_t stopStart, stopUs;
    uint32_t sequence;
    int waitedMs;

    beginCase("session_no_report_enqueued_after_stop_retirement");
    pthread_mutex_lock(&publicationLock);
    publicationArmed = publicationParked = publicationReleased = publicationSawRetired = false;
    publicationWaitedMs = 0;
    parkedSequence = 0;
    pthread_mutex_unlock(&publicationLock);
    PacketReceptionSetTestPublicationHook(publicationBarrier);
    videoEncrypted = true;
    senderCrypto = PltCreateCryptoContext();
    if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
        CHECK(false, "start failed");
        PacketReceptionSetTestPublicationHook(NULL);
        endCase();
        return;
    }
    sleepMs(250);

    // Park the next prepared report between snapshot and enqueue.
    pthread_mutex_lock(&publicationLock);
    publicationArmed = true;
    {
        struct timespec deadline;
        struct timeval nowTv;
        gettimeofday(&nowTv, NULL);
        deadline.tv_sec = nowTv.tv_sec + 2;
        deadline.tv_nsec = (long)nowTv.tv_usec * 1000L;
        while (!publicationParked) {
            if (pthread_cond_timedwait(&publicationCond, &publicationLock, &deadline) != 0) {
                break;
            }
        }
    }
    parked = publicationParked;
    pthread_mutex_unlock(&publicationLock);
    CHECK(parked, "control thread never reached the publication window");

    // The real stop stages run on their own thread while the report is parked.
    fixtureStopping = true;
    ConnectionInterrupted = true;
    stopStart = realMonotonicUs();
    CHECK(pthread_create(&stopper, NULL, stopStagesThread, (void*)&stopDone) == 0, "stopper thread");
    pthread_join(stopper, NULL);
    stopUs = realMonotonicUs() - stopStart;
    finishStoppedSession(true);
    n = collectReports(reports, arrivals, 64, &pings, &fecCount, fec, &badTransport);
    PltDestroyCryptoContext(senderCrypto);
    PacketReceptionSetTestPublicationHook(NULL);

    pthread_mutex_lock(&publicationLock);
    released = publicationReleased;
    sawRetired = publicationSawRetired;
    waitedMs = publicationWaitedMs;
    sequence = parkedSequence;
    pthread_mutex_unlock(&publicationLock);
    for (i = 0; i < n; i++) {
        CHECK(reports[i].reportSequence == (uint32_t)(i + 1), "sequence %u", reports[i].reportSequence);
        CHECK(reports[i].reportSequence <= sequence, "a new snapshot followed the parked one: %u", reports[i].reportSequence);
        if (reports[i].reportSequence == sequence) {
            parkedArrived = true;
        }
    }
    printf("STOP parked_sequence=%u retired_while_held=%d waited_ms=%d stop_us=%llu reports=%d parked_report_delivered=%d pings=%d\n",
           sequence, sawRetired, waitedMs, (unsigned long long)stopUs, n, parkedArrived, pings);
    CHECK(stopDone && released, "stop stages did not complete (join) or the parked report was never released");
    // Stop wins: retirement completes before the publisher join while the prepared report is
    // held, and that report is then dropped rather than enqueued.
    CHECK(sawRetired, "stop did not retire the collector before releasing the held publisher (waited %d ms)", waitedMs);
    CHECK(!parkedArrived, "the held report was still sent instead of being dropped by stop (waited %d ms)", waitedMs);
    CHECK(stopUs < 5000000, "stop took %llu us", (unsigned long long)stopUs);
    CHECK(n >= 2 && pings >= 2 && badTransport == 0 && terminationError == 0, "reports %d pings %d", n, pings);
    {
        // The held publisher resumes after retirement and sees a retired collector, but stop had
        // already closed publication: the transport is configured once and never restored by stop.
        static const uint32_t expected[] = { 0 };
        CHECK(throttleConfiguresAre(expected, 1), "%d THROTTLE_CONFIGURE commands across the fenced stop", throttleConfiguresSeen());
    }
    endCase();

    // Reconnect once: a normal epoch with periodic reports and pings.
    beginCase("session_reconnect_after_fenced_stop");
    senderCrypto = PltCreateCryptoContext();
    if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
        CHECK(false, "restart failed");
        endCase();
        return;
    }
    sleepMs(350);
    stopSession(true);
    PltDestroyCryptoContext(senderCrypto);
    n = collectReports(reports, arrivals, 64, &pings, &fecCount, fec, &badTransport);
    CHECK(n >= 2 && reports[0].reportSequence == 1, "reports %d first %u", n, n > 0 ? reports[0].reportSequence : 0);
    for (i = 0; i < n; i++) {
        CHECK(reports[i].reportSequence == (uint32_t)(i + 1) &&
              !(reports[i].flags & (PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE | PACKET_RECEPTION_FLAG_INCOMPLETE)),
              "report %d sequence %u flags %x", i, reports[i].reportSequence, reports[i].flags);
    }
    CHECK(pings >= 2 && badTransport == 0 && terminationError == 0, "pings %d", pings);
    endCase();
}

// ---------------------------------------------------------------------------------------------
// Transport fixtures: the client's real ENet throttle under a scripted bad round-trip time

// The first acknowledgement (CONNECT, through the relay) opens ENet's 5 s throttle epoch with this
// RTT, so its threshold is about 20 + 2 * 10 ms. Every later acknowledgement at 120 ms exceeds it,
// and stock ENet lowers packetThrottle by 2 for each one. Each session ends inside that epoch.
#define TRANSPORT_BASELINE_DELAY_MS 20
#define TRANSPORT_BAD_DELAY_MS 120
#define TRANSPORT_BASELINE_PHASE_MS 300
#define TRANSPORT_BAD_PHASE_MS 2000
#define TRANSPORT_FEC_FRAMES 8
#define TRANSPORT_FEC_SPACING_MS 75
#define TRANSPORT_DRAIN_MS 400
#define TRANSPORT_MAX_SEQUENCE 1024

// Client-side publication record from the existing test hook: every report the collector produced,
// with its time, before the control stream enqueued it.
static pthread_mutex_t publishedLock = PTHREAD_MUTEX_INITIALIZER;
static uint32_t publishedSequence[TRANSPORT_MAX_SEQUENCE];
static uint64_t publishedUs[TRANSPORT_MAX_SEQUENCE];
static int publishedCount;

static void recordPublication(const uint8_t* report, int length) {
    if (length < PACKET_RECEPTION_HEADER_BYTES) {
        return;
    }
    pthread_mutex_lock(&publishedLock);
    if (publishedCount < TRANSPORT_MAX_SEQUENCE) {
        publishedSequence[publishedCount] = be32(report + 4);
        publishedUs[publishedCount] = realMonotonicUs();
        publishedCount++;
    }
    pthread_mutex_unlock(&publishedLock);
}

static int publicationsSoFar(void) {
    int count;
    pthread_mutex_lock(&publishedLock);
    count = publishedCount;
    pthread_mutex_unlock(&publishedLock);
    return count;
}

static void beginTransportCapture(void) {
    pthread_mutex_lock(&publishedLock);
    publishedCount = 0;
    pthread_mutex_unlock(&publishedLock);
    pthread_mutex_lock(&transportRowLock);
    transportRowCount = 0;
    transportRowOverflow = 0;
    pthread_mutex_unlock(&transportRowLock);
    PacketReceptionSetTestPublicationHook(recordPublication);
}

static void endTransportCapture(void) {
    PacketReceptionSetTestPublicationHook(NULL);
    PacketReceptionTestSetTransport(true, NULL);
}

// Stop 30 ms after a report was published: that tick's ping has flushed it, and the next report is
// about 70 ms away, so stop never races a report (the stop fence is tested separately).
static void waitBetweenTicks(void) {
    int before = publicationsSoFar();
    int waited = 0;
    while (publicationsSoFar() == before && waited < 400) {
        sleepMs(1);
        waited++;
    }
    sleepMs(30);
}

// Report n is this peer's n-th unreliable packet when nothing else unreliable is sent, so stock
// ENet gives it throttle counter 7n mod 32 and sends it only if that does not exceed packetThrottle.
static uint32_t reportThrottleCounter(uint32_t sequence) {
    return (uint32_t)(((uint64_t)sequence * ENET_PEER_PACKET_THROTTLE_COUNTER) % ENET_PEER_PACKET_THROTTLE_SCALE);
}

typedef struct {
    int setup;
    int setupConfigured;
    int periodic;
    int retired;
    int malformed;
    uint32_t lastPeriodicThrottle;
    uint32_t lastPeriodicDeceleration;
} TRANSPORT_ROWS;

// Every row must be exactly one of the control stream's three formats: numbers and fixed words.
static void readTransportRows(const char* label, TRANSPORT_ROWS* rows, bool print) {
    int i;

    memset(rows, 0, sizeof(*rows));
    pthread_mutex_lock(&transportRowLock);
    CHECK(transportRowOverflow == 0, "%s: %d transport rows overflowed", label, transportRowOverflow);
    for (i = 0; i < transportRowCount; i++) {
        const char* row = transportRows[i];
        char state[16], rebuilt[256];
        unsigned a, b, c, d, e, f, g, h, k, l;

        if (sscanf(row, TRANSPORT_ROW_PREFIX " extension active, throttle deceleration %15[a-z]; throttle %u -> %u of %u, counter %u, "
                        "interval %u ms, acceleration %u, deceleration %u -> %u, RTT %u ms, variance %u ms",
                   state, &a, &b, &c, &d, &e, &f, &g, &h, &k, &l) == 11) {
            snprintf(rebuilt, sizeof(rebuilt), TRANSPORT_ROW_PREFIX " extension active, throttle deceleration %s; throttle %u -> %u of %u, counter %u, "
                     "interval %u ms, acceleration %u, deceleration %u -> %u, RTT %u ms, variance %u ms\n", state, a, b, c, d, e, f, g, h, k, l);
            rows->setup++;
            if (strcmp(state, "off") == 0) {
                rows->setupConfigured++;
            }
            else if (strcmp(state, "unchanged") != 0) {
                rows->malformed++;
            }
        }
        else if (sscanf(row, TRANSPORT_ROW_PREFIX " report %u, throttle %u of %u, counter %u, deceleration %u, RTT %u ms, variance %u ms",
                        &a, &b, &c, &d, &e, &f, &g) == 7) {
            snprintf(rebuilt, sizeof(rebuilt), TRANSPORT_ROW_PREFIX " report %u, throttle %u of %u, counter %u, deceleration %u, RTT %u ms, variance %u ms\n",
                     a, b, c, d, e, f, g);
            rows->periodic++;
            rows->lastPeriodicThrottle = b;
            rows->lastPeriodicDeceleration = e;
            if (a % PACKET_RECEPTION_TRANSPORT_LOG_REPORTS != 0) {
                rows->malformed++;
            }
        }
        else if (sscanf(row, TRANSPORT_ROW_PREFIX " reports retired, throttle deceleration %u restored", &a) == 1) {
            snprintf(rebuilt, sizeof(rebuilt), TRANSPORT_ROW_PREFIX " reports retired, throttle deceleration %u restored\n", a);
            rows->retired++;
        }
        else {
            rebuilt[0] = 0;
        }
        if (strcmp(rebuilt, row) != 0) {
            rows->malformed++;
            CHECK(false, "%s: unexpected transport row: %s", label, row);
        }
        if (print) {
            printf("TRANSPORT_ROW label=%s %s", label, row);
        }
    }
    pthread_mutex_unlock(&transportRowLock);
}

static bool readClientTransport(PACKET_RECEPTION_TRANSPORT* transport) {
    memset(transport, 0, sizeof(*transport));
    return PacketReceptionTestReadTransport(transport);
}

static void checkStockSettings(const char* label, const PACKET_RECEPTION_TRANSPORT* t) {
    CHECK(t->packetThrottleInterval == ENET_PEER_PACKET_THROTTLE_INTERVAL &&
          t->packetThrottleAcceleration == ENET_PEER_PACKET_THROTTLE_ACCELERATION &&
          t->packetThrottleDeceleration == ENET_PEER_PACKET_THROTTLE_DECELERATION,
          "%s: client throttle settings %u/%u/%u are not ENet's defaults", label,
          t->packetThrottleInterval, t->packetThrottleAcceleration, t->packetThrottleDeceleration);
}

typedef struct {
    int generated;
    int reports;
    int pings;
    int duplicates;
    int unpublished;
    int badTransport;
    int missing;
    int missingBeforeFec;
    int missingBeforeFecCounterZero;
    int fecStatuses;
    uint32_t minIntervalUs;
    uint32_t hostConnectDeceleration;
    uint32_t hostLastDeceleration;
    int hostThrottleConfigures;
    int hostThrottleConfigureCopies;
    bool clientReadBeforeFec;
    bool clientReadEnd;
    PACKET_RECEPTION_TRANSPORT clientBeforeFec;
    PACKET_RECEPTION_TRANSPORT clientEnd;
    TRANSPORT_ROWS rows;
} BAD_RTT_RESULT;

// Host-side view against the client's own publication record.
static void analyzeTransport(const char* label, uint64_t fecStartUs, BAD_RTT_RESULT* r) {
    static PACKET_RECEPTION_REPORT reports[MAX_CONTROL_MESSAGES];
    static uint64_t arrivals[MAX_CONTROL_MESSAGES];
    static uint8_t received[TRANSPORT_MAX_SEQUENCE + 1];
    uint8_t fec[16][32];
    char missingList[1024];
    size_t used = 0;
    int i, listed = 0;

    memset(received, 0, sizeof(received));
    r->reports = collectReports(reports, arrivals, MAX_CONTROL_MESSAGES, &r->pings, &r->fecStatuses, fec, &r->badTransport);
    r->minIntervalUs = UINT32_MAX;
    for (i = 0; i < r->reports; i++) {
        uint32_t sequence = reports[i].reportSequence;
        if (sequence < 1 || sequence > TRANSPORT_MAX_SEQUENCE) {
            r->unpublished++;
            continue;
        }
        if (received[sequence]) {
            r->duplicates++;
        }
        received[sequence] = 1;
        if (i > 0 && reports[i].intervalUs < r->minIntervalUs) {
            r->minIntervalUs = reports[i].intervalUs;
        }
    }

    missingList[0] = 0;
    pthread_mutex_lock(&publishedLock);
    r->generated = publishedCount;
    for (i = 0; i < publishedCount; i++) {
        uint32_t sequence = publishedSequence[i];
        CHECK(sequence == (uint32_t)(i + 1), "%s: published sequence %u at %d", label, sequence, i);
        if (sequence >= 1 && sequence <= TRANSPORT_MAX_SEQUENCE && received[sequence]) {
            received[sequence] = 2;
            continue;
        }
        r->missing++;
        if (publishedUs[i] < fecStartUs) {
            r->missingBeforeFec++;
            if (reportThrottleCounter(sequence) == 0) {
                r->missingBeforeFecCounterZero++;
            }
        }
        if (listed < 64 && used + 16 < sizeof(missingList)) {
            used += (size_t)snprintf(missingList + used, sizeof(missingList) - used, " %u:%u", sequence, reportThrottleCounter(sequence));
            listed++;
        }
    }
    pthread_mutex_unlock(&publishedLock);
    for (i = 1; i <= TRANSPORT_MAX_SEQUENCE; i++) {
        if (received[i] == 1) {
            r->unpublished++;
        }
    }

    pthread_mutex_lock(&controlLock);
    r->hostConnectDeceleration = observedConnectDeceleration;
    r->hostThrottleConfigures = throttleConfigureCount;
    r->hostThrottleConfigureCopies = throttleConfigureCopies;
    r->hostLastDeceleration = UINT32_MAX;
    for (i = 0; i < controlMessageCount; i++) {
        r->hostLastDeceleration = controlMessages[i].hostPeerDeceleration;
    }
    pthread_mutex_unlock(&controlLock);
    readTransportRows(label, &r->rows, true);

    printf("TRANSPORT label=%s generated=%d reports=%d pings=%d missing=%d missing_before_fec=%d missing_before_fec_counter_zero=%d "
           "duplicates=%d unpublished=%d fec_frames=%d fec_statuses=%d min_interval_us=%u "
           "client_throttle_before_fec=%u client_throttle_end=%u client_limit_end=%u client_deceleration_end=%u reliable_window_scale_end=%u/32 "
           "host_deceleration_connect=%u host_deceleration_last=%u host_throttle_configures=%d host_throttle_configure_copies=%d "
           "setup_rows=%d periodic_rows=%d retired_rows=%d relay_overflows=%d relay_send_failures=%d\n",
           label, r->generated, r->reports, r->pings, r->missing, r->missingBeforeFec, r->missingBeforeFecCounterZero,
           r->duplicates, r->unpublished, fecStartUs != UINT64_MAX ? TRANSPORT_FEC_FRAMES : 0, r->fecStatuses,
           r->minIntervalUs == UINT32_MAX ? 0 : r->minIntervalUs,
           r->clientBeforeFec.packetThrottle, r->clientEnd.packetThrottle, r->clientEnd.packetThrottleLimit,
           r->clientEnd.packetThrottleDeceleration, r->clientEnd.packetThrottle,
           r->hostConnectDeceleration, r->hostLastDeceleration, r->hostThrottleConfigures, r->hostThrottleConfigureCopies,
           r->rows.setup, r->rows.periodic, r->rows.retired, relayOverflows, relaySendFailures);
    printf("TRANSPORT_MISSING label=%s sequence:counter%s\n", label, missingList);
}

static void sendRecoverableFrame(uint32_t frame) {
    FRAME_SHARDS f;
    // Data shard 2 is lost; parity shard 4 reconstructs it, so the queue reports FEC status.
    buildFrame(&f, (uint16_t)((frame - 1) * (DATA_SHARDS + PARITY_SHARDS)), frame);
    sendShard(&f, 0, frame, false);
    sendShard(&f, 1, frame, false);
    sendShard(&f, 3, frame, false);
    sendShard(&f, 4, frame, false);
}

// One encrypted extension session: baseline RTT, the bad RTT with reports only, then recoverable
// video frames so unreliable FEC status shares the throttle, then the production stop.
static bool runBadRttSession(const char* label, bool configure, BAD_RTT_RESULT* r) {
    SESSION_OPTIONS o = defaultOptions();
    uint32_t encryptionEnabled, frame;
    uint64_t fecStartUs;
    bool echoed = false;

    memset(r, 0, sizeof(*r));
    o.relay = true;
    o.relayDelayUs = TRANSPORT_BASELINE_DELAY_MS * 1000;
    beginTransportCapture();
    PacketReceptionTestSetTransport(configure, NULL);
    videoEncrypted = true;
    senderCrypto = PltCreateCryptoContext();
    if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
        CHECK(false, "%s: session start failed", label);
        PltDestroyCryptoContext(senderCrypto);
        endTransportCapture();
        return false;
    }
    CHECK(echoed && PacketReceptionIsEnabled(), "%s: extension not active", label);
    sleepMs(TRANSPORT_BASELINE_PHASE_MS);
    setRelayDelayMs(TRANSPORT_BAD_DELAY_MS);
    sleepMs(TRANSPORT_BAD_PHASE_MS);

    r->clientReadBeforeFec = readClientTransport(&r->clientBeforeFec);
    fecStartUs = realMonotonicUs();
    for (frame = 1; frame <= TRANSPORT_FEC_FRAMES; frame++) {
        sendRecoverableFrame(frame);
        sleepMs(TRANSPORT_FEC_SPACING_MS);
    }
    sleepMs(TRANSPORT_DRAIN_MS);
    waitBetweenTicks();
    r->clientReadEnd = readClientTransport(&r->clientEnd);
    stopSession(true);
    PltDestroyCryptoContext(senderCrypto);
    analyzeTransport(label, fecStartUs, r);
    endTransportCapture();

    CHECK(r->clientReadBeforeFec && r->clientReadEnd, "%s: client transport unreadable", label);
    CHECK(relayOverflows == 0 && relaySendFailures == 0, "%s: relay overflow %d send failures %d", label, relayOverflows, relaySendFailures);
    CHECK(controlDecryptFailures == 0, "%s: %d control messages failed to decrypt", label, controlDecryptFailures);
    CHECK(terminationError == 0, "%s: connection terminated %d", label, terminationError);
    // Shared by both modes: one report per tick from the collector, all published reports
    // unreliable and unsequenced on the generic channel, every reliable ping delivered.
    CHECK(r->generated >= 25, "%s: only %d reports generated", label, r->generated);
    CHECK(r->generated >= r->pings - 1 && r->generated <= r->pings + 1, "%s: %d reports for %d ticks", label, r->generated, r->pings);
    CHECK(r->duplicates == 0 && r->unpublished == 0, "%s: duplicates %d unpublished %d", label, r->duplicates, r->unpublished);
    CHECK(r->badTransport == 0, "%s: %d reports not unreliable unsequenced on the generic channel", label, r->badTransport);
    CHECK(r->reports < 2 || r->minIntervalUs >= 90000, "%s: report interval %u us: more than one report per tick", label, r->minIntervalUs);
    CHECK(r->hostConnectDeceleration == ENET_PEER_PACKET_THROTTLE_DECELERATION, "%s: connect deceleration %u", label, r->hostConnectDeceleration);
    CHECK(r->rows.setup == 1 && r->rows.malformed == 0 && r->rows.retired == 0, "%s: setup rows %d malformed %d retired %d",
          label, r->rows.setup, r->rows.malformed, r->rows.retired);
    CHECK(r->rows.periodic == r->generated / PACKET_RECEPTION_TRANSPORT_LOG_REPORTS, "%s: %d periodic rows for %d reports",
          label, r->rows.periodic, r->generated);
    return true;
}

static void transportStockControl(void) {
    BAD_RTT_RESULT r;

    beginCase("transport_stock_throttle_discards_reports_under_bad_rtt");
    if (runBadRttSession("stock", false, &r)) {
        // The fixture reproduces the field signature with ENet's own code: the throttle collapses,
        // enqueued reports vanish, and a report whose counter is 0 is never among them.
        CHECK(r.rows.setupConfigured == 0, "stock control configured the transport");
        checkStockSettings("stock", &r.clientEnd);
        CHECK(r.clientBeforeFec.packetThrottle <= 2, "stock throttle only fell to %u", r.clientBeforeFec.packetThrottle);
        CHECK(r.missingBeforeFec >= 10, "only %d reports discarded before FEC traffic", r.missingBeforeFec);
        CHECK(r.missingBeforeFecCounterZero == 0, "%d discarded reports had throttle counter 0", r.missingBeforeFecCounterZero);
        CHECK(r.fecStatuses < TRANSPORT_FEC_FRAMES, "all %d FEC statuses survived a collapsed throttle", r.fecStatuses);
        CHECK(r.hostLastDeceleration == ENET_PEER_PACKET_THROTTLE_DECELERATION && r.hostThrottleConfigures == 0,
              "host deceleration %u, %d THROTTLE_CONFIGURE without configuration", r.hostLastDeceleration, r.hostThrottleConfigures);
    }
    endCase();
}

static void transportCandidate(void) {
    BAD_RTT_RESULT r;

    beginCase("transport_reports_survive_bad_rtt_one_per_tick");
    if (runBadRttSession("candidate", true, &r)) {
        // Same sequence: every scheduled report arrives exactly once, with no retransmission or
        // catch-up, and the same peer-wide change keeps unreliable FEC status too.
        CHECK(r.missing == 0, "%d of %d reports silently discarded", r.missing, r.generated);
        CHECK(r.reports == r.generated, "reports %d generated %d", r.reports, r.generated);
        CHECK(r.rows.setupConfigured == 1, "transport not configured");
        CHECK(r.clientEnd.packetThrottleDeceleration == 0 && r.clientEnd.packetThrottle == r.clientEnd.packetThrottleLimit &&
              r.clientEnd.packetThrottleLimit == ENET_PEER_PACKET_THROTTLE_SCALE,
              "client throttle %u of %u deceleration %u", r.clientEnd.packetThrottle, r.clientEnd.packetThrottleLimit,
              r.clientEnd.packetThrottleDeceleration);
        CHECK(r.clientEnd.packetThrottleInterval == ENET_PEER_PACKET_THROTTLE_INTERVAL &&
              r.clientEnd.packetThrottleAcceleration == ENET_PEER_PACKET_THROTTLE_ACCELERATION,
              "interval %u acceleration %u changed", r.clientEnd.packetThrottleInterval, r.clientEnd.packetThrottleAcceleration);
        CHECK(r.fecStatuses == TRANSPORT_FEC_FRAMES, "%d of %d FEC statuses", r.fecStatuses, TRANSPORT_FEC_FRAMES);
        // Exactly one THROTTLE_CONFIGURE (deceleration 0, defaults otherwise) reached the host's
        // ENet and took effect there; stop sent no second configuration.
        {
            static const uint32_t expected[] = { 0 };
            CHECK(throttleConfiguresAre(expected, 1), "%d THROTTLE_CONFIGURE commands", r.hostThrottleConfigures);
        }
        CHECK(r.hostLastDeceleration == 0, "host deceleration %u", r.hostLastDeceleration);
        CHECK(r.rows.lastPeriodicThrottle == ENET_PEER_PACKET_THROTTLE_SCALE && r.rows.lastPeriodicDeceleration == 0,
              "last row throttle %u deceleration %u", r.rows.lastPeriodicThrottle, r.rows.lastPeriodicDeceleration);
    }
    endCase();
}

// Acknowledgements before the observer starts can already have lowered the throttle. ENet does
// not acknowledge CONNECT (VERIFY_CONNECT does), so START A's acknowledgement is the client's first
// RTT sample and opens the epoch; in production only START B's can then lower it, by one step.
// Here the hook lets both arrive at the baseline RTT, switches the relay to the bad RTT, and sends
// one more reliable control message until stock ENet has really lowered the throttle; then the
// transport decides.
static PACKET_RECEPTION_TRANSPORT loweredBeforeConfigure;
static bool loweredRead;
static int loweredExtraSends;

static void lowerThrottleBeforeConfigure(void) {
    int waited = 0;

    loweredRead = false;
    loweredExtraSends = 0;
    while (waited < 1000) {
        loweredRead = readClientTransport(&loweredBeforeConfigure);
        if (loweredRead && loweredBeforeConfigure.roundTripTime < ENET_PEER_DEFAULT_ROUND_TRIP_TIME) {
            break;
        }
        sleepMs(5);
        waited += 5;
    }
    sleepMs(60);
    setRelayDelayMs(TRANSPORT_BAD_DELAY_MS);
    for (waited = 0; waited < 3000; waited += 5) {
        if (waited % 500 == 0 && loweredExtraSends < 4) {
            LiSendExecServerCmd(0);
            loweredExtraSends++;
        }
        sleepMs(5);
        loweredRead = readClientTransport(&loweredBeforeConfigure);
        if (loweredRead && loweredBeforeConfigure.packetThrottle < loweredBeforeConfigure.packetThrottleLimit) {
            break;
        }
    }
}

static void transportLoweredRestored(void) {
    SESSION_OPTIONS o = defaultOptions();
    PACKET_RECEPTION_TRANSPORT started, end;
    BAD_RTT_RESULT r;
    uint32_t encryptionEnabled;
    bool echoed = false, startedRead, endRead;

    beginCase("transport_lowered_throttle_restored_at_start");
    memset(&r, 0, sizeof(r));
    o.relay = true;
    o.relayDelayUs = TRANSPORT_BASELINE_DELAY_MS * 1000;
    beginTransportCapture();
    PacketReceptionTestSetTransport(true, lowerThrottleBeforeConfigure);
    videoEncrypted = true;
    senderCrypto = PltCreateCryptoContext();
    if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
        CHECK(false, "session start failed");
        PltDestroyCryptoContext(senderCrypto);
        endTransportCapture();
        endCase();
        return;
    }
    startedRead = readClientTransport(&started);
    sleepMs(1000);
    waitBetweenTicks();
    endRead = readClientTransport(&end);
    stopSession(true);
    PltDestroyCryptoContext(senderCrypto);
    r.clientBeforeFec = started;
    r.clientEnd = end;
    analyzeTransport("lowered", UINT64_MAX, &r);
    endTransportCapture();

    printf("TRANSPORT_LOWERED before_configure=%u limit=%u deceleration=%u rtt=%u extra_reliable_sends=%d after_start=%u "
           "after_start_deceleration=%u end=%u\n",
           loweredBeforeConfigure.packetThrottle, loweredBeforeConfigure.packetThrottleLimit,
           loweredBeforeConfigure.packetThrottleDeceleration, loweredBeforeConfigure.roundTripTime, loweredExtraSends,
           started.packetThrottle, started.packetThrottleDeceleration, end.packetThrottle);
    CHECK(loweredRead && loweredBeforeConfigure.packetThrottle < loweredBeforeConfigure.packetThrottleLimit,
          "stock ENet did not lower the throttle before the start (%u)", loweredBeforeConfigure.packetThrottle);
    CHECK(loweredBeforeConfigure.packetThrottleDeceleration == ENET_PEER_PACKET_THROTTLE_DECELERATION, "deceleration before start %u",
          loweredBeforeConfigure.packetThrottleDeceleration);
    CHECK(startedRead && started.packetThrottle == started.packetThrottleLimit && started.packetThrottleDeceleration == 0,
          "throttle %u of %u deceleration %u after start", started.packetThrottle, started.packetThrottleLimit,
          started.packetThrottleDeceleration);
    CHECK(endRead && end.packetThrottle == end.packetThrottleLimit, "throttle fell again to %u", end.packetThrottle);
    CHECK(r.missing == 0 && r.generated >= 8 && r.duplicates == 0 && r.badTransport == 0, "missing %d of %d", r.missing, r.generated);
    CHECK(r.rows.setupConfigured == 1, "transport not configured");
    {
        static const uint32_t expected[] = { 0 };
        CHECK(throttleConfiguresAre(expected, 1), "%d THROTTLE_CONFIGURE commands", r.hostThrottleConfigures);
    }
    CHECK(terminationError == 0 && relayOverflows == 0, "termination %d relay overflow %d", terminationError, relayOverflows);
    endCase();
}

// Withdrawal after the echo, as the SDP generator does when it cannot add the attribute.
static void withdrawAfterNegotiation(void) {
    PacketReceptionNegotiationWithdrawn();
}

// Negotiated with a working clock, then the origin read fails when the observer starts.
static void breakClockBeforeStart(void) {
    clockBroken = true;
    PacketReceptionSetTestClock(fixtureClock);
}

static void transportNoneligiblePeers(void) {
    static const char* const names[] = {
        "offer_absent", "offer_version_2", "offer_duplicated", "connect_data_absent",
        "clock_unsupported", "withdrawn", "clock_failed_at_start",
    };
    PACKET_RECEPTION_REPORT reports[64];
    uint64_t arrivals[64];
    uint8_t fec[16][32];
    int v;

    beginCase("transport_noneligible_peers_keep_stock_throttle");
    for (v = 0; v < 7; v++) {
        SESSION_OPTIONS o = defaultOptions();
        PACKET_RECEPTION_TRANSPORT client;
        TRANSPORT_ROWS rows;
        uint32_t encryptionEnabled, hostLast = UINT32_MAX;
        int pings, fecCount, badTransport, n, i;
        bool echoed = false, read;

        switch (v) {
        case 0: o.describe = "v=0\r\na=x-ss-general.featureFlags:0\r\n"; break;
        case 1: o.describe = "v=0\r\na=x-ss-video.packetReceptionFeedbackVersion:2\r\n"; break;
        case 2: o.describe = "v=0\r\na=x-ss-video.packetReceptionFeedbackVersion:1\r\na=x-ss-video.packetReceptionFeedbackVersion:1\r\n"; break;
        case 3: o.connectData = false; break;
        case 4: clockBroken = true; PacketReceptionSetTestClock(fixtureClock); break;
        case 5: o.afterNegotiate = withdrawAfterNegotiation; break;
        default: o.afterNegotiate = breakClockBeforeStart; break;
        }
        beginTransportCapture();
        videoEncrypted = true;
        senderCrypto = PltCreateCryptoContext();
        if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
            CHECK(false, "%s: start failed", names[v]);
            PltDestroyCryptoContext(senderCrypto);
            endTransportCapture();
            continue;
        }
        sleepMs(350);
        read = readClientTransport(&client);
        stopSession(false);
        PltDestroyCryptoContext(senderCrypto);
        PacketReceptionSetTestClock(NULL);
        clockBroken = false;
        n = collectReports(reports, arrivals, 64, &pings, &fecCount, fec, &badTransport);
        readTransportRows(names[v], &rows, true);
        endTransportCapture();
        pthread_mutex_lock(&controlLock);
        for (i = 0; i < controlMessageCount; i++) {
            hostLast = controlMessages[i].hostPeerDeceleration;
        }
        CHECK(observedConnectDeceleration == ENET_PEER_PACKET_THROTTLE_DECELERATION &&
              hostLast == ENET_PEER_PACKET_THROTTLE_DECELERATION &&
              throttleConfigureCount == 0,
              "%s: host deceleration connect %u last %u, %d THROTTLE_CONFIGURE", names[v], observedConnectDeceleration, hostLast,
              throttleConfigureCount);
        pthread_mutex_unlock(&controlLock);
        printf("TRANSPORT_NONELIGIBLE variant=%s echoed=%d enabled_reports=%d pings=%d client_deceleration=%u client_interval=%u "
               "client_acceleration=%u rows=%d\n", names[v], echoed, n, pings, client.packetThrottleDeceleration,
               client.packetThrottleInterval, client.packetThrottleAcceleration, rows.setup + rows.periodic + rows.retired);
        CHECK(read, "%s: client transport unreadable", names[v]);
        checkStockSettings(names[v], &client);
        CHECK(rows.setup + rows.periodic + rows.retired == 0, "%s: transport rows logged", names[v]);
        CHECK(pings >= 2 && terminationError == 0, "%s: pings %d termination %d", names[v], pings, terminationError);
        if (v == 6) {
            // The started-but-untimed observer sends its one clock sentinel through stock ENet.
            CHECK(n == 1 && (reports[0].flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE), "%s: reports %d", names[v], n);
        }
        else {
            CHECK(n == 0, "%s: %d reports", names[v], n);
        }
    }
    endCase();
}

static void transportReconnect(void) {
    PACKET_RECEPTION_REPORT reports[64];
    uint64_t arrivals[64];
    uint8_t fec[16][32];
    int round;

    beginCase("transport_reconnect_restores_stock_defaults");
    for (round = 0; round < 3; round++) {
        SESSION_OPTIONS o = defaultOptions();
        PACKET_RECEPTION_TRANSPORT client;
        TRANSPORT_ROWS rows;
        uint32_t encryptionEnabled, hostLast = UINT32_MAX;
        int pings, fecCount, badTransport, n, i;
        bool echoed = false, read, active = round != 1;
        char label[32];

        snprintf(label, sizeof(label), "reconnect_%d", round);
        if (!active) {
            o.describe = "v=0\r\na=x-ss-general.featureFlags:0\r\n";
        }
        beginTransportCapture();
        videoEncrypted = true;
        senderCrypto = PltCreateCryptoContext();
        if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
            CHECK(false, "%s: start failed", label);
            PltDestroyCryptoContext(senderCrypto);
            endTransportCapture();
            break;
        }
        sleepMs(250);
        waitBetweenTicks();
        read = readClientTransport(&client);
        stopSession(true);
        PltDestroyCryptoContext(senderCrypto);
        n = collectReports(reports, arrivals, 64, &pings, &fecCount, fec, &badTransport);
        readTransportRows(label, &rows, true);
        endTransportCapture();
        pthread_mutex_lock(&controlLock);
        for (i = 0; i < controlMessageCount; i++) {
            hostLast = controlMessages[i].hostPeerDeceleration;
        }
        // Every connection starts from ENet's defaults; only an active one is configured, and
        // stop does not send a second configuration.
        CHECK(observedConnectDeceleration == ENET_PEER_PACKET_THROTTLE_DECELERATION, "%s: connect deceleration %u",
              label, observedConnectDeceleration);
        CHECK(hostLast == (active ? 0u : (uint32_t)ENET_PEER_PACKET_THROTTLE_DECELERATION) &&
              throttleConfigureCount == (active ? 1 : 0) && (!active || throttleConfigures[0].deceleration == 0),
              "%s: host deceleration last %u, %d THROTTLE_CONFIGURE", label, hostLast, throttleConfigureCount);
        pthread_mutex_unlock(&controlLock);
        printf("TRANSPORT_RECONNECT round=%d active=%d reports=%d first_sequence=%u client_deceleration=%u client_throttle=%u setup_rows=%d\n",
               round, active, n, n > 0 ? reports[0].reportSequence : 0, client.packetThrottleDeceleration, client.packetThrottle, rows.setup);
        CHECK(read, "%s: client transport unreadable", label);
        if (active) {
            CHECK(n >= 2 && reports[0].reportSequence == 1, "%s: reports %d", label, n);
            CHECK(client.packetThrottleDeceleration == 0 && rows.setupConfigured == 1 && rows.setup == 1, "%s: deceleration %u setup %d",
                  label, client.packetThrottleDeceleration, rows.setupConfigured);
        }
        else {
            CHECK(n == 0, "%s: %d reports", label, n);
            checkStockSettings(label, &client);
            CHECK(rows.setup + rows.periodic + rows.retired == 0, "%s: transport rows", label);
        }
        CHECK(pings >= 2 && badTransport == 0 && terminationError == 0, "%s: pings %d", label, pings);
    }
    endCase();
}

// The extension retires mid-stream (clock failure): its sentinel still goes out under the
// transport, then the loss-stats thread gives the peer back its stock deceleration.
static void transportRetiredRestores(void) {
    SESSION_OPTIONS o = defaultOptions();
    PACKET_RECEPTION_REPORT reports[64];
    uint64_t arrivals[64];
    uint8_t fec[16][32];
    PACKET_RECEPTION_TRANSPORT active, retired;
    TRANSPORT_ROWS rows;
    uint32_t encryptionEnabled, hostLast = UINT32_MAX, hostBeforeSentinel = UINT32_MAX, previous = UINT32_MAX;
    bool sawSentinel = false;
    int pings, fecCount, badTransport, n, i, sentinels = 0, waited = 0;
    bool echoed = false, activeRead, retiredRead = false;

    beginCase("transport_retired_extension_restores_deceleration");
    beginTransportCapture();
    clockBroken = false;
    PacketReceptionSetTestClock(fixtureClock);
    videoEncrypted = true;
    senderCrypto = PltCreateCryptoContext();
    if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
        CHECK(false, "start failed");
        PltDestroyCryptoContext(senderCrypto);
        PacketReceptionSetTestClock(NULL);
        endTransportCapture();
        endCase();
        return;
    }
    sleepMs(250);
    activeRead = readClientTransport(&active);
    clockBroken = true;
    while (waited < 1500) {
        retiredRead = readClientTransport(&retired);
        if (retiredRead && retired.packetThrottleDeceleration != 0) {
            break;
        }
        sleepMs(5);
        waited += 5;
    }
    sleepMs(300);
    stopSession(true);
    PltDestroyCryptoContext(senderCrypto);
    PacketReceptionSetTestClock(NULL);
    clockBroken = false;
    n = collectReports(reports, arrivals, 64, &pings, &fecCount, fec, &badTransport);
    readTransportRows("retired", &rows, true);
    endTransportCapture();

    pthread_mutex_lock(&controlLock);
    // ENet handles a whole datagram before dispatching its events, and the restoring
    // THROTTLE_CONFIGURE can share the sentinel's datagram, so judge the host by the message
    // before the sentinel (the previous tick) and by the last one.
    for (i = 0; i < controlMessageCount; i++) {
        if (!sawSentinel && controlMessages[i].type == PACKET_RECEPTION_PTYPE && controlMessages[i].length >= 2 &&
                (controlMessages[i].payload[1] & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE)) {
            sawSentinel = true;
            hostBeforeSentinel = previous;
        }
        previous = controlMessages[i].hostPeerDeceleration;
        hostLast = controlMessages[i].hostPeerDeceleration;
    }
    pthread_mutex_unlock(&controlLock);
    {
        // Configured at start, restored once at retirement, nothing at stop.
        static const uint32_t expected[] = { 0, ENET_PEER_PACKET_THROTTLE_DECELERATION };
        CHECK(throttleConfiguresAre(expected, 2), "%d THROTTLE_CONFIGURE commands", throttleConfiguresSeen());
    }
    for (i = 0; i < n; i++) {
        if (reports[i].flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE) {
            sentinels++;
        }
    }
    printf("TRANSPORT_RETIRED reports=%d sentinels=%d active_deceleration=%u retired_deceleration=%u host_before_sentinel=%u host_last=%u "
           "setup_rows=%d retired_rows=%d pings=%d\n", n, sentinels, active.packetThrottleDeceleration, retired.packetThrottleDeceleration,
           hostBeforeSentinel, hostLast, rows.setup, rows.retired, pings);
    CHECK(activeRead && active.packetThrottleDeceleration == 0, "active deceleration %u", active.packetThrottleDeceleration);
    CHECK(retiredRead, "client transport unreadable");
    checkStockSettings("retired", &retired);
    CHECK(sentinels == 1 && n >= 2 && (reports[n - 1].flags & PACKET_RECEPTION_FLAG_CLOCK_UNAVAILABLE), "reports %d sentinels %d", n, sentinels);
    CHECK(hostBeforeSentinel == 0 && hostLast == ENET_PEER_PACKET_THROTTLE_DECELERATION, "host deceleration before sentinel %u last %u",
          hostBeforeSentinel, hostLast);
    CHECK(rows.setupConfigured == 1 && rows.retired == 1 && rows.malformed == 0, "setup %d retired %d", rows.setupConfigured, rows.retired);
    CHECK(pings >= 4 && badTransport == 0 && terminationError == 0, "pings %d termination %d", pings, terminationError);
    endCase();
}

static void sessionGates(void) {
    static const struct { const char* name; int variant; } variants[] = {
        { "offer_absent", 0 }, { "offer_version_2", 1 }, { "offer_duplicated", 2 }, { "connect_data_absent", 3 },
    };
    PACKET_RECEPTION_REPORT reports[64];
    uint64_t arrivals[64];
    uint8_t fec[16][32];
    int pings, fecCount, badTransport, n, v;
    uint32_t baselineEncryption = 0, encryptionEnabled = 0;
    bool echoed;

    beginCase("session_gates_no_echo_no_reports");
    {
        SESSION_OPTIONS o = defaultOptions();
        CHECK(negotiate(&o, &baselineEncryption), "baseline must echo");
        CHECK(baselineEncryption == (SS_ENC_CONTROL_V2 | SS_ENC_VIDEO), "baseline encryption %u", baselineEncryption);
        PacketReceptionDestroy();
    }
    for (v = 0; v < 4; v++) {
        SESSION_OPTIONS o = defaultOptions();
        switch (variants[v].variant) {
        case 0: o.describe = "v=0\r\na=x-ss-general.featureFlags:0\r\n"; break;
        case 1: o.describe = "v=0\r\na=x-ss-video.packetReceptionFeedbackVersion:2\r\n"; break;
        case 2: o.describe = "v=0\r\na=x-ss-video.packetReceptionFeedbackVersion:1\r\na=x-ss-video.packetReceptionFeedbackVersion:1\r\n"; break;
        default: o.connectData = false; break;
        }
        senderCrypto = PltCreateCryptoContext();
        if (startSession(&o, &echoed, &encryptionEnabled) != 0) {
            CHECK(false, "%s: start failed", variants[v].name);
            continue;
        }
        sleepMs(350);
        stopSession(false);
        PltDestroyCryptoContext(senderCrypto);
        n = collectReports(reports, arrivals, 64, &pings, &fecCount, fec, &badTransport);
        CHECK(!echoed, "%s: echoed", variants[v].name);
        CHECK(n == 0, "%s: %d reports", variants[v].name, n);
        CHECK(pings >= 2, "%s: pings %d", variants[v].name, pings);
        CHECK(encryptionEnabled == baselineEncryption, "%s: encryption %u", variants[v].name, encryptionEnabled);
    }
    endCase();

    beginCase("sdp_gates_without_session");
    {
        SESSION_OPTIONS o = defaultOptions();
        o.appVersion[3] = 0;   // GFE, not Sunshine
        CHECK(!negotiate(&o, &encryptionEnabled), "non-Sunshine echoed");
        PacketReceptionDestroy();
        o = defaultOptions();
        o.appVersion[2] = 430;  // before encrypted control protocol 13
        CHECK(!negotiate(&o, &encryptionEnabled), "pre-protocol-13 echoed");
        PacketReceptionDestroy();
        o = defaultOptions();
        o.hostEncryptionSupported = SS_ENC_AUDIO | SS_ENC_VIDEO;   // no encrypted control v2
        CHECK(!negotiate(&o, &encryptionEnabled), "echoed without encrypted control v2");
        CHECK(!(encryptionEnabled & SS_ENC_CONTROL_V2), "control v2 enabled by the extension");
        PacketReceptionDestroy();
        o = defaultOptions();
        o.encryptionFlags = ENCFLG_NONE;
        o.hostEncryptionSupported = SS_ENC_CONTROL_V2 | SS_ENC_AUDIO;
        CHECK(negotiate(&o, &encryptionEnabled) && encryptionEnabled == SS_ENC_CONTROL_V2, "plaintext LAN video must echo unchanged");
        PacketReceptionDestroy();
        o = defaultOptions();
        clockBroken = true;
        PacketReceptionSetTestClock(fixtureClock);
        CHECK(!negotiate(&o, &encryptionEnabled), "unsupported clock echoed");
        CHECK(encryptionEnabled == (SS_ENC_CONTROL_V2 | SS_ENC_VIDEO), "clock gate changed encryption");
        PacketReceptionSetTestClock(NULL);
        clockBroken = false;
        PacketReceptionDestroy();
    }
    endCase();
}

int main(int argc, char** argv) {
    const char* command = argc > 1 ? argv[1] : "";

    harnessMainThread = pthread_self();

    if (strcmp(command, "unit") == 0) {
        unitTests();
    }
    else if (strcmp(command, "golden") == 0) {
        int i;
        for (i = 0; i < 4; i++) {
            printf("GOLDEN %s %s\n", goldenNames[i], goldenHex[i]);
        }
        return 0;
    }
    else if (strcmp(command, "session-paths") == 0) {
        sessionPaths(true);
        sessionPaths(false);
    }
    else if (strcmp(command, "session-reconnect") == 0) {
        sessionReconnect();
    }
    else if (strcmp(command, "session-clock") == 0) {
        sessionClock(false);
        sessionClock(true);
    }
    else if (strcmp(command, "session-gates") == 0) {
        sessionGates();
    }
    else if (strcmp(command, "stop-publication") == 0) {
        sessionStopPublicationFence();
    }
    else if (strcmp(command, "transport-throttle") == 0) {
        transportStockControl();
        transportCandidate();
        transportLoweredRestored();
    }
    else if (strcmp(command, "transport-lifecycle") == 0) {
        transportNoneligiblePeers();
        transportReconnect();
        transportRetiredRestores();
    }
    else if (strcmp(command, "clock-review") == 0) {
        clockReviewLifecycle();
        sessionReceiverClockFault(false);
        sessionReceiverClockFault(true);
        sessionSnapshotEarlierThanReceiveRead();
    }
    else {
        fprintf(stderr, "usage: %s unit|golden|session-paths|session-reconnect|session-clock|session-gates|clock-review|stop-publication|transport-throttle|transport-lifecycle\n", argv[0]);
        return 2;
    }

    printf("SUMMARY passed=%d failed=%d\n", passedCases, failedCases);
    return failedCases == 0 ? 0 : 1;
}
