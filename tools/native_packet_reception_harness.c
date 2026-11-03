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
    uint64_t receivedUs;
    uint8_t payload[256];
} CONTROL_MESSAGE;

static pthread_mutex_t controlLock = PTHREAD_MUTEX_INITIALIZER;
static CONTROL_MESSAGE controlMessages[MAX_CONTROL_MESSAGES];
static int controlMessageCount;
static int controlDecryptFailures;
static uint32_t observedConnectData;
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

static void logMessage(const char* format, ...) {
    va_list args;
    if (getenv("NOVA_RECEPTION_VERBOSE") == NULL) {
        return;
    }
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);
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

typedef struct {
    const char* describe;
    bool connectData;
    int encryptionFlags;
    uint32_t hostEncryptionSupported;
    int appVersion[4];
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
    enet_socket_get_address(server->socket, &address);
    ControlPortNumber = ntohs(((struct sockaddr_in*)&address.address)->sin_port);

    videoSocket = socket(AF_INET, SOCK_DGRAM, 0);
    if (videoSocket < 0 || bind(videoSocket, (struct sockaddr*)&bind4, sizeof(bind4)) != 0 ||
            getsockname(videoSocket, (struct sockaddr*)&bound, &boundLength) != 0) {
        return -1;
    }
    VideoPortNumber = ntohs(bound.sin_port);
    memset(&VideoPingPayload, 0, sizeof(VideoPingPayload));

    *echoed = negotiate(o, encryptionEnabled);

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

static void stopSession(bool checkStoppedCollector) {
    uint8_t buffer[PACKET_RECEPTION_MAX_PAYLOAD_BYTES];

    fixtureStopping = true;
    ConnectionInterrupted = true;
    stopVideoStream();
    stopControlStream();
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
    else {
        fprintf(stderr, "usage: %s unit|golden|session-paths|session-reconnect|session-clock|session-gates\n", argv[0]);
        return 2;
    }

    printf("SUMMARY passed=%d failed=%d\n", passedCases, failedCases);
    return failedCases == 0 ? 0 : 1;
}
