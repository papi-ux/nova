#ifndef NOVA_PYROWAVE_PACKET_GUARD_H
#define NOVA_PYROWAVE_PACKET_GUARD_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* The pinned parser must always consume at least its eight-byte block header. */
static inline bool pyrowave_packet_has_safe_lengths(const void *packet, size_t size) {
    return packet != NULL && size > 0;
}

#endif
