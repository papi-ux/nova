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

/* Separate coherent diagnostic population; never combine with Java/HUD counters. */
typedef struct {
    char connection_nonce[33];
    int64_t native_epoch;
    int64_t snapshot_sequence;
    uint32_t last_frame_index;
    int64_t decode_units_submitted_total;
    int64_t payload_bytes_total;
    int64_t monotonic_timestamp_ms;
    int64_t last_submission_timestamp_ms;
} nova_video_frame_submission_snapshot_t;

/* Called once per complete DU, including decoder refusals, before NAL splitting.
 * Updates the legacy payload counter with its original counting semantics too. */
void nova_video_frame_submission_record(uint32_t frame_index, int bytes);
/* Available during setup (before start) so the renderer can bind its lifetime. */
int64_t nova_video_frame_submission_epoch(void);
bool nova_video_frame_submission_snapshot(int64_t expected_epoch,
                                         nova_video_frame_submission_snapshot_t* snapshot);
bool nova_video_payload_random_nonce(uint8_t nonce[16]);

#ifdef NOVA_VIDEO_STATS_TEST
/* Exercise arithmetic boundaries in the production observer, without billions of DUs. */
void nova_video_frame_submission_test_limits(int64_t units, int64_t bytes, int64_t sequence);
void nova_video_frame_submission_test_epoch(int64_t epoch);
#endif

/* The JNI binding supplies the receiver clock; tests supply a deterministic clock.
 * Called only while holding the counter mutex, never calls back into the bridge. */
int64_t nova_video_payload_clock_ms(void);
