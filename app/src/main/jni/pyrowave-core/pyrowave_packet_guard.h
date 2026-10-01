#ifndef NOVA_PYROWAVE_PACKET_GUARD_H
#define NOVA_PYROWAVE_PACKET_GUARD_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/*
 * The pinned parser returns early for duplicate blocks before validating their payload_words.
 * A duplicate with zero words therefore never advances. Validate lengths before every push,
 * without requiring all blocks, ordered indexes, or unique blocks: partial decode still belongs
 * to the codec. Read the little-endian descriptor by byte so unaligned buffers stay safe.
 */
static inline bool pyrowave_packet_has_safe_lengths(const void *packet, size_t size) {
    if (packet == NULL || size == 0) return false;
    const uint8_t *data = (const uint8_t *)packet;
    while (size > 0) {
        if (size < 8) return false;
        const unsigned descriptor = (unsigned)data[2] | ((unsigned)data[3] << 8);
        const size_t bytes = (descriptor & 0x8000u) ? 8 : (descriptor & 0x0fffu) * 4;
        if (bytes < 8 || bytes > size) return false;
        data += bytes;
        size -= bytes;
    }
    return true;
}

#endif
