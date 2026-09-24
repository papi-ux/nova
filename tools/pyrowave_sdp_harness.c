/**
 * @file tools/pyrowave_sdp_harness.c
 * @brief Exercises the PyroWave SDP matcher against payloads no host would send.
 *
 * The matcher is the one protocol decision this client makes by reading text, and the consequence of
 * getting it wrong is streaming a codec profile the decoder does not implement, which is a picture
 * that is merely wrong rather than an error. So it is tested here, away from an RTSP connection,
 * against truncation, repetition, near misses and the payloads an adversary would choose.
 *
 * Built and run by tools/test_pyrowave_sdp.py, under the sanitizers.
 */
#include "PyroWaveSdp.h"

#include <stdio.h>
#include <string.h>

static int failures = 0;

static void expect(bool got, bool want, const char *what) {
    if (got != want) {
        printf("FAIL %s: got %s, wanted %s\n", what, got ? "true" : "false", want ? "true" : "false");
        failures++;
    }
}

static void expect_offer(const char *payload, const char *token, bool want, const char *what) {
    expect(sdpOffersPyroWaveProfile(payload, token), want, what);
}

#define SDR "pyrowave-186f0393-sdr420-v1"
#define HDR "pyrowave-186f0393-hdr2020pq420-v1"
#define RTPMAP "a=rtpmap:99 PYROWAVE/90000\r\n"

int main(void) {
    // The shape a host actually sends, SDR only.
    expect_offer(RTPMAP "a=fmtp:99 " SDR "\r\n", SDR, true, "plain SDR offer");
    expect_offer(RTPMAP "a=fmtp:99 " SDR "\r\n", HDR, false, "SDR only host asked for HDR");

    // Both tokens in one line, which is what a host that can do both sends. The point of the list:
    // a client that knows one token still has to work against a host offering two.
    expect_offer(RTPMAP "a=fmtp:99 " SDR " " HDR "\r\n", SDR, true, "list, SDR wanted");
    expect_offer(RTPMAP "a=fmtp:99 " SDR " " HDR "\r\n", HDR, true, "list, HDR wanted");
    expect_offer(RTPMAP "a=fmtp:99 " HDR " " SDR "\r\n", SDR, true, "list, other order");

    // Whitespace a generator might produce.
    expect_offer(RTPMAP "a=fmtp:99  " SDR "   " HDR "  \r\n", HDR, true, "extra spaces");
    expect_offer(RTPMAP "a=fmtp:99\t" SDR "\t" HDR "\r\n", HDR, true, "tabs");
    expect_offer(RTPMAP "a=fmtp:99 " SDR "\n", SDR, true, "bare newline");

    // Near misses. Every one of these is a host this client must refuse rather than decode.
    expect_offer(RTPMAP "a=fmtp:99 " SDR "0\r\n", SDR, false, "longer token with our prefix");
    expect_offer(RTPMAP "a=fmtp:99 x" SDR "\r\n", SDR, false, "token with something in front");
    expect_offer(RTPMAP "a=fmtp:99 " SDR "-v2\r\n", SDR, false, "token with a suffix");
    expect_offer(RTPMAP "a=fmtp:990 " SDR "\r\n", SDR, false, "payload 990, not 99");
    expect_offer(RTPMAP "a=fmtp:9 " SDR "\r\n", SDR, false, "payload 9");
    expect_offer(RTPMAP "a=fmtp:99" SDR "\r\n", SDR, false, "no separator after the name");

    // The token somewhere it does not count.
    expect_offer(RTPMAP "a=tool:" SDR "\r\n", SDR, false, "token in an unrelated attribute");
    expect_offer("a=fmtp:99 " SDR "\r\n", SDR, false, "profile with no rtpmap");
    expect_offer(RTPMAP, SDR, false, "rtpmap with no profile");

    // A second claim on payload 99 is a host disagreeing with itself.
    expect_offer(RTPMAP "a=fmtp:99 " SDR "\r\na=fmtp:99 something-else\r\n", SDR, false,
                 "two profile lines");
    expect_offer(RTPMAP "a=fmtp:99 " SDR "\r\n" RTPMAP, SDR, false, "two rtpmap lines");
    expect_offer("a=rtpmap:99 H264/90000\r\na=fmtp:99 " SDR "\r\n", SDR, false,
                 "another codec on payload 99");

    // Truncation and emptiness, which is what a half written payload looks like.
    expect_offer("", SDR, false, "empty payload");
    expect_offer("a=fmtp:99", SDR, false, "name with nothing after it");
    expect_offer("a=fmtp:99 ", SDR, false, "name and a space");
    expect_offer(RTPMAP "a=fmtp:99 pyrowave-186f0393-sdr420-v", SDR, false, "token cut short");
    expect_offer("\r\n\r\n\r\n", SDR, false, "only line endings");

    // The list walk on its own, including the empty element cases.
    expect(sdpListOffersToken(SDR, SDR + strlen(SDR), SDR), true, "list of one");
    expect(sdpListOffersToken("", "", SDR), false, "empty list");
    expect(sdpListOffersToken("   ", "   " + 3, SDR), false, "list of spaces");
    expect(sdpListOffersToken(" " SDR " ", " " SDR " " + strlen(SDR) + 2, SDR), true, "padded element");

    if (failures == 0) {
        printf("all cases passed\n");
        return 0;
    }
    printf("%d case(s) failed\n", failures);
    return 1;
}
