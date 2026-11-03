#pragma once

#include <stdbool.h>
#include <stdint.h>

/* Complete encoded payload submitted to the renderer bridge, once per decode unit.
 * Includes reconstructed video; excludes frames dropped before submission, repair
 * shards, socket traffic and decoder output. */
typedef struct {
    int64_t generation;
    int64_t bytes;
    int64_t now_ms;
} nova_video_payload_snapshot_t;

void nova_video_payload_begin(void);
void nova_video_payload_start(void);
void nova_video_payload_stop(void);
void nova_video_payload_record(int bytes);
bool nova_video_payload_snapshot(nova_video_payload_snapshot_t* snapshot);

/* The JNI binding supplies the receiver clock; tests supply a deterministic clock.
 * Called only while holding the counter mutex, never calls back into the bridge. */
int64_t nova_video_payload_clock_ms(void);
