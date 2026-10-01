#include "pyrowave_packet_guard.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static void block(uint8_t *out, unsigned words, unsigned index) {
    memset(out, 0, 8);
    out[2] = (uint8_t)words;
    out[3] = (uint8_t)(words >> 8);
    out[5] = (uint8_t)index;
}

int main(int argc, char **argv) {
    uint8_t bytes[16384 + 24] = {0};
    if (argc > 1 && strcmp(argv[1], "zero") == 0) {
        block(bytes, 2, 7);
        block(bytes + 8, 0, 7);
        assert(!pyrowave_packet_has_safe_lengths(bytes, 16));
    }
    assert(!pyrowave_packet_has_safe_lengths(NULL, 8));
    assert(!pyrowave_packet_has_safe_lengths(bytes, 0));
    for (size_t size = 1; size < 8; ++size)
        assert(!pyrowave_packet_has_safe_lengths(bytes, size));

    block(bytes, 2, 7);
    assert(pyrowave_packet_has_safe_lengths(bytes, 8));
    block(bytes + 8, 2, 7); /* Valid duplicates remain safe. */
    assert(pyrowave_packet_has_safe_lengths(bytes, 16));
    for (unsigned words = 0; words < 2; ++words) {
        block(bytes + 8, words, 7); /* The upstream duplicate fast path misses these. */
        assert(!pyrowave_packet_has_safe_lengths(bytes, 16));
    }

    block(bytes, 3, 4);
    assert(!pyrowave_packet_has_safe_lengths(bytes, 8));
    assert(pyrowave_packet_has_safe_lengths(bytes, 12));
    for (size_t tail = 1; tail < 8; ++tail)
        assert(!pyrowave_packet_has_safe_lengths(bytes, 12 + tail));

    /* A sequence header has no block payload length. */
    memset(bytes, 0, sizeof(bytes));
    bytes[3] = 0x80;
    assert(pyrowave_packet_has_safe_lengths(bytes, 8));
    block(bytes + 8, 2, 0);
    block(bytes + 16, 2, 9); /* Gaps in block indexes do not require a complete frame. */
    assert(pyrowave_packet_has_safe_lengths(bytes, 24));

    block(bytes, 4095, 0); /* Largest payload encoded by the twelve-bit length. */
    assert(pyrowave_packet_has_safe_lengths(bytes, 16380));
    assert(!pyrowave_packet_has_safe_lengths(bytes, 16379));
    memmove(bytes + 1, bytes, 16380); /* Transport buffers need not be word aligned. */
    assert(pyrowave_packet_has_safe_lengths(bytes + 1, 16380));

    unsigned random = 12345;
    for (size_t size = 0; size < sizeof(bytes); ++size) {
        random = random * 1664525u + 1013904223u;
        bytes[size] = (uint8_t)(random >> 24);
        (void)pyrowave_packet_has_safe_lengths(bytes, size + 1);
    }
    assert(argc > 2);
    FILE *fixture = fopen(argv[2], "rb");
    assert(fixture != NULL);
    const size_t fixture_size = fread(bytes, 1, sizeof(bytes), fixture);
    assert(fixture_size > 0 && fgetc(fixture) == EOF);
    assert(fclose(fixture) == 0);
    assert(pyrowave_packet_has_safe_lengths(bytes, fixture_size));
    puts("all packet length cases passed");
    return 0;
}
