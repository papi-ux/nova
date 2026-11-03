#include "video_payload_stats.h"
#include <limits.h>
#include <stddef.h>
#include <pthread.h>
#include <string.h>

/* Moonlight owns one connection per process. This observer has no controller input. */
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static int64_t generation;
static int64_t received_bytes;
static bool active;
static bool exhausted;
static bool submission_prepared;
static bool submission_valid;
static nova_video_frame_submission_snapshot_t submission;

void nova_video_payload_begin(void) {
    pthread_mutex_lock(&mutex);
    active = false;
    received_bytes = 0;
    if (generation == INT64_MAX) {
        exhausted = true;
    } else if (!exhausted) {
        generation++;
    }
    memset(&submission, 0, sizeof(submission));
    uint8_t nonce[16] = {0};
    submission_prepared = !exhausted && nova_video_payload_random_nonce(nonce);
    uint8_t nonzero = 0;
    for (size_t i = 0; i < sizeof(nonce); i++) nonzero |= nonce[i];
    submission_prepared = submission_prepared && nonzero != 0;
    submission_valid = submission_prepared;
    if (submission_prepared) {
        static const char hex[] = "0123456789abcdef";
        for (size_t i = 0; i < sizeof(nonce); i++) {
            submission.connection_nonce[i * 2] = hex[nonce[i] >> 4];
            submission.connection_nonce[i * 2 + 1] = hex[nonce[i] & 15];
        }
        submission.native_epoch = generation;
    }
    pthread_mutex_unlock(&mutex);
}

void nova_video_payload_start(void) {
    pthread_mutex_lock(&mutex);
    active = generation > 0 && !exhausted;
    pthread_mutex_unlock(&mutex);
}

void nova_video_payload_stop(void) {
    pthread_mutex_lock(&mutex);
    active = false;
    submission_prepared = false;
    submission_valid = false;
    pthread_mutex_unlock(&mutex);
}

static void record_payload_locked(int bytes) {
    if (bytes <= 0) return;
    if (active) {
        if (received_bytes > INT64_MAX - bytes) {
            active = false;
        } else {
            received_bytes += bytes;
        }
    }
}

void nova_video_payload_record(int bytes) {
    if (bytes <= 0) return;
    pthread_mutex_lock(&mutex);
    record_payload_locked(bytes);
    pthread_mutex_unlock(&mutex);
}

void nova_video_frame_submission_record(uint32_t frame_index, int bytes) {
    pthread_mutex_lock(&mutex);
    const bool was_active = active;
    record_payload_locked(bytes);
    if (was_active && !active) submission_valid = false;
    if (active && submission_prepared && submission_valid) {
        const int64_t now_ms = nova_video_payload_clock_ms();
        if (bytes <= 0 || frame_index == 0 || frame_index <= submission.last_frame_index ||
            submission.decode_units_submitted_total == INT64_MAX ||
            submission.payload_bytes_total > INT64_MAX - bytes || now_ms <= 0 ||
            now_ms < submission.last_submission_timestamp_ms ||
            now_ms < submission.monotonic_timestamp_ms) {
            submission_valid = false;
        } else {
            submission.last_frame_index = frame_index;
            submission.decode_units_submitted_total++;
            submission.payload_bytes_total += bytes;
            submission.last_submission_timestamp_ms = now_ms;
        }
    }
    pthread_mutex_unlock(&mutex);
}

int64_t nova_video_frame_submission_epoch(void) {
    pthread_mutex_lock(&mutex);
    const int64_t epoch = submission_prepared && submission_valid ? submission.native_epoch : 0;
    pthread_mutex_unlock(&mutex);
    return epoch;
}

bool nova_video_frame_submission_snapshot(int64_t expected_epoch,
                                         nova_video_frame_submission_snapshot_t* snapshot) {
    if (snapshot == NULL) return false;
    bool available = false;
    pthread_mutex_lock(&mutex);
    if (active && submission_prepared && submission_valid && expected_epoch > 0 &&
        expected_epoch == submission.native_epoch && submission.decode_units_submitted_total > 0) {
        const int64_t now_ms = nova_video_payload_clock_ms();
        if (now_ms <= 0 || now_ms < submission.last_submission_timestamp_ms ||
            now_ms < submission.monotonic_timestamp_ms || submission.snapshot_sequence == INT64_MAX) {
            submission_valid = false;
        } else if (now_ms > submission.monotonic_timestamp_ms) {
            /* Same-millisecond polling is unavailable, without consuming sequence or epoch. */
            submission.monotonic_timestamp_ms = now_ms;
            submission.snapshot_sequence++;
            *snapshot = submission;
            available = true;
        }
    }
    pthread_mutex_unlock(&mutex);
    return available;
}

#ifdef NOVA_VIDEO_STATS_TEST
void nova_video_frame_submission_test_limits(int64_t units, int64_t bytes, int64_t sequence) {
    pthread_mutex_lock(&mutex);
    submission.decode_units_submitted_total = units;
    submission.payload_bytes_total = bytes;
    submission.snapshot_sequence = sequence;
    pthread_mutex_unlock(&mutex);
}
void nova_video_frame_submission_test_epoch(int64_t epoch) {
    pthread_mutex_lock(&mutex);
    generation = epoch;
    pthread_mutex_unlock(&mutex);
}
#endif

bool nova_video_payload_snapshot(nova_video_payload_snapshot_t* snapshot) {
    if (snapshot == NULL) return false;
    bool available = false;
    pthread_mutex_lock(&mutex);
    if (active) {
        const int64_t now_ms = nova_video_payload_clock_ms();
        if (now_ms >= 0) {
            *snapshot = (nova_video_payload_snapshot_t) {generation, received_bytes, now_ms};
            available = true;
        }
    }
    pthread_mutex_unlock(&mutex);
    return available;
}
